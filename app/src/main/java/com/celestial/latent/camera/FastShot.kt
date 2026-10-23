package com.celestial.latent.camera

import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Fast shot (79a): fixed focal lengths, like the street mode on Xiaomi's own camera.
 *
 * Each focal length is made from the longest real lens (or in-sensor mode) that is not longer than it,
 * and a centred crop for the rest: 35 mm is the 23 mm main with a third of each edge trimmed, 50 mm is the
 * main's in-sensor ×2 (46 mm) trimmed a little, 135 mm the 100 mm periscope. The RAW keeps the lens's
 * whole frame; the crop is recorded with the photo, so the darkroom prints it as the focal length and
 * FRAME can take it away again.
 */
object FastShot {
    val FOCALS = listOf(28, 35, 50, 75, 90, 135)

    /** Photo address -> (crop, focal length) of fast shots just saved, for the screen to record with the photo. */
    val savedCrops = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Int>>()

    /** Snap focus distances in metres, in the order a tap cycles them; 0 = autofocus, -1 = infinity. */
    val SNAPS = listOf(0f, 1f, 2f, 3f, 5f, -1f)

    /** Slowest shutter the motion meter picks when nothing moves: handheld, walking, street. */
    const val SLOWEST_NS = 8_000_000L        // 1/125 s
    /** Fastest it picks. */
    const val FASTEST_NS = 250_000L          // 1/4000 s

    /**
     * How a focal length is made: [lens] at [zoom] (1, or an in-sensor ×2) gives [sourceMm]; [crop] is
     * how much closer the crop brings it (1 = none).
     */
    data class Plan(val focal: Int, val lens: Lens, val zoom: Float, val sourceMm: Float, val crop: Float) {
        /** Megapixels left after the crop, from a RAW of [rawMp]. */
        fun megapixels(rawMp: Float = 12.6f) = rawMp / (crop * crop)
    }

    /** The plan for [focal] on this phone, or null when every lens is longer than it. */
    fun plan(focal: Int): Plan? {
        val options = Lenses.ALL.filter { it.raw && it.mm > 0 }.flatMap { l ->
            listOf(Triple(l, 1f, l.mm.toFloat())) +
                Lenses.sensorZoomSteps(l).filter { it == 2 }.mapNotNull { step ->
                    Lenses.sensorZoomFactor(l, step.toFloat())?.let { f -> Triple(l, step.toFloat(), l.mm * f) }
                }
        }
        // A source a few percent longer still counts (the 46 mm in-sensor mode for 47 mm would): the
        // crop is then 1 and the picture a hair tighter than asked, never a digital enlargement.
        val best = options.filter { it.third <= focal * 1.03f }.maxByOrNull { it.third } ?: return null
        return Plan(focal, best.first, best.second, best.third, (focal / best.third).coerceAtLeast(1f))
    }

    /** The focal lengths this phone can make. */
    fun available(): List<Int> = FOCALS.filter { plan(it) != null }

    fun snapLabel(m: Float): String = when { m == 0f -> "AF"; m < 0f -> "∞"; else -> "${m.toInt()} m" }

    /** The lens focus for a snap distance, in dioptres, or null for autofocus. */
    fun snapDiopters(m: Float): Float? = when { m == 0f -> null; m < 0f -> 0f; else -> 1f / m }

    fun nextSnap(m: Float): Float = SNAPS[(SNAPS.indexOf(m).coerceAtLeast(0) + 1) % SNAPS.size]

    // ---- the viewfinder sample ------------------------------------------------------------------

    /** The small picture the meters work on: [W] × [H] cells of the RAW, each the average of 4 colour quads. */
    const val W = 128
    const val H = 96

    /**
     * Fills [out] (W × H, 0..1 above black) from a RAW frame given as 16-bit samples with [rowStride]
     * samples per row. Reads 16 pixels per cell (four 2×2 colour quads), so a 12 MP frame costs about
     * 200 000 reads. Returns the average over the cells inside the centred [crop].
     */
    fun sample(raw: java.nio.ShortBuffer, w: Int, h: Int, rowStride: Int, black: Int, white: Int, crop: Float, out: FloatArray): Float {
        val cw = w / W; val ch = h / H
        val scale = 1f / ((white - black).coerceAtLeast(1) * 16f)
        val x0 = ((W - W / crop) / 2f).toInt(); val x1 = W - x0
        val y0 = ((H - H / crop) / 2f).toInt(); val y1 = H - y0
        var sum = 0.0; var n = 0
        for (j in 0 until H) {
            // two quads across, two down, inside the cell, on even rows and columns so each is R G G B
            val ra = (j * ch + ch / 4) and 1.inv(); val rb = (j * ch + 3 * ch / 4) and 1.inv()
            for (i in 0 until W) {
                val ca = (i * cw + cw / 4) and 1.inv(); val cb = (i * cw + 3 * cw / 4) and 1.inv()
                var acc = 0
                for (r in intArrayOf(ra, rb)) for (c in intArrayOf(ca, cb)) {
                    val o = r * rowStride + c
                    acc += (raw.get(o).toInt() and 0xFFFF) + (raw.get(o + 1).toInt() and 0xFFFF) +
                        (raw.get(o + rowStride).toInt() and 0xFFFF) + (raw.get(o + rowStride + 1).toInt() and 0xFFFF) - 4 * black
                }
                val v = (acc * scale).coerceAtLeast(0f)
                out[j * W + i] = v
                if (i in x0 until x1 && j in y0 until y1) { sum += v; n++ }
            }
        }
        return if (n > 0) (sum / n).toFloat() else 0f
    }

    // ---- motion ---------------------------------------------------------------------------------

    /**
     * How fast the picture moves, from consecutive samples of the RAW stream. Optical stabilisation has
     * already taken out the hand's tremor, so what is left — a walking person, a car, the camera panned
     * or carried — is exactly what would blur the photo.
     *
     * Per 16 × 16-cell block, the displacement is the classic gradient estimate |ΔI| / |∇I| (in cells),
     * on brightness-normalised frames so an exposure change does not read as motion; done at full and half
     * scale and the larger kept, because the full-scale estimate stops growing past about a cell. The
     * 90th percentile of textured blocks wins, so a moving subject counts even on a still background.
     * Fast to rise, slow to fall (half-life about 0.4 s), so a passer-by is not forgotten between frames.
     */
    class MotionMeter {
        private var prev: FloatArray? = null
        private var prevMean = 0f
        private var prevNs = 0L
        /** Cells per second the picture moves (cells are 1/[W] of the frame's long edge). */
        var speed = 0f; private set

        fun reset() { prev = null; speed = 0f }

        fun feed(frame: FloatArray, mean: Float, ns: Long) {
            val p = prev
            val dt = (ns - prevNs) / 1e9f
            if (p != null && dt in 0.01f..0.5f && mean > 1e-4f && prevMean > 1e-4f) {
                val d = maxOf(displacement(p, prevMean, frame, mean, W, H, 1), 2f * displacement(halve(p), prevMean, halve(frame), mean, W / 2, H / 2, 2))
                val now = d / dt
                speed = if (now > speed) now else speed * exp(-dt * ln(2f) / 0.4f) + now * (1f - exp(-dt * ln(2f) / 0.4f))
            }
            prev = frame.copyOf(); prevMean = mean; prevNs = ns
        }

        /** The shutter that keeps motion blur under 1/1500 of the final picture's long edge (a sharp print). */
        fun shutterNs(crop: Float): Long = shutterFor(speed, crop)
    }

    /** See [MotionMeter.shutterNs]. Public for the JVM check. */
    fun shutterFor(cellsPerSecond: Float, crop: Float): Long {
        // budget in cells: the final picture's long edge is W / crop cells, blur ≤ 1/1500 of it
        val budget = W / (crop * 1500f)
        if (cellsPerSecond <= 1e-3f) return SLOWEST_NS
        val ns = (budget / cellsPerSecond * 1e9).toLong()
        return ns.coerceIn(FASTEST_NS, SLOWEST_NS)
    }

    private fun halve(a: FloatArray): FloatArray {
        val w = W / 2; val h = H / 2
        return FloatArray(w * h) { k -> val i = (k % w) * 2; val j = (k / w) * 2
            (a[j * W + i] + a[j * W + i + 1] + a[(j + 1) * W + i] + a[(j + 1) * W + i + 1]) * 0.25f }
    }

    /** Robust displacement in cells of this scale between two frames (see [MotionMeter]). Public for the JVM check. */
    fun displacement(a: FloatArray, ma: Float, b: FloatArray, mb: Float, w: Int, h: Int, blockShrink: Int): Float {
        val bs = 16 / blockShrink
        val ka = 1f / ma; val kb = 1f / mb
        val found = ArrayList<Float>()
        var by = 0
        while (by + bs <= h) {
            var bx = 0
            while (bx + bs <= w) {
                var grad = 0.0; var diff = 0.0
                for (y in by until by + bs) for (x in bx until bx + bs) {
                    val i = y * w + x
                    val av = a[i] * ka; val bv = b[i] * kb
                    val gx = if (x + 1 < w) (a[i + 1] * ka + b[i + 1] * kb - av - bv) * 0.5f else 0f
                    val gy = if (y + 1 < h) (a[i + w] * ka + b[i + w] * kb - av - bv) * 0.5f else 0f
                    grad += gx * gx + gy * gy
                    val t = bv - av
                    diff += t * t
                }
                // a block needs texture (an edge of a few percent contrast) to say anything about motion
                if (grad > bs * bs * 4e-4) found += sqrt(diff / grad).toFloat()
                bx += bs
            }
            by += bs
        }
        if (found.size < 3) return 0f
        found.sort()
        val d = found[((found.size - 1) * 0.9f).toInt()]
        // below a fifth of a cell is noise and the shimmer of the light, not motion
        return if (d < 0.2f) 0f else d
    }

    // ---- exposure -------------------------------------------------------------------------------

    /**
     * Our own auto exposure, so the shutter can be anything we choose. The phone's auto exposure first
     * settles as usual and teaches us its brightness ([learn]: the RAW's average level at its exposure).
     * Then each sample says how bright the scene is (level ÷ (shutter × ISO)), and the shutter (from the
     * motion, or yours) and ISO are chosen to bring the level back to what the phone wanted, EV included.
     */
    class Exposure {
        /** The RAW level the phone's auto exposure aimed for, at EV [evAtLearn], or 0 before learning. */
        var target = 0f; private set
        private var evAtLearn = 0f
        /** shutter × ISO now asked for (ns·ISO), smoothed. */
        var product = 0.0; private set
        var dim = false; private set

        val learned get() = target > 0f

        fun forget() { target = 0f; product = 0.0; dim = false }

        fun learn(level: Float, shutterNs: Long, iso: Int, evStops: Float) {
            target = level.coerceIn(0.01f, 0.6f); evAtLearn = evStops
            product = shutterNs.toDouble() * iso
        }

        /** One measurement: the level of a frame taken at [shutterNs] × [iso]. */
        fun update(level: Float, shutterNs: Long, iso: Int, evStops: Float) {
            if (!learned || shutterNs <= 0 || iso <= 0) return
            val want = target * Math.pow(2.0, (evStops - evAtLearn).toDouble())
            val got = shutterNs.toDouble() * iso
            val needed = when {
                level > 0.92f -> got * 0.35            // clipped: the true level is unknown, step well down
                level < 2e-4f -> got * 4.0             // black: step well up
                else -> got * want / level
            }
            // half the way in stops each time: settles in a few frames without hunting
            product = if (product <= 0.0) needed else product * Math.sqrt(needed / product)
        }

        /**
         * Shutter and ISO for [shutterNs] (the shutter wanted) within the lens's ranges. When even the
         * highest ISO is not enough the picture is darker and [dim] says so; when the lowest is too much
         * the shutter gets faster instead.
         */
        fun settings(shutterNs: Long, isoMin: Int, isoMax: Int, shutterMin: Long): Pair<Long, Int> {
            var t = shutterNs
            var iso = (product / t).toInt()
            if (iso < isoMin) { t = (product / isoMin).toLong().coerceAtLeast(shutterMin); iso = isoMin }
            dim = iso > isoMax * 1.05
            return t to iso.coerceIn(isoMin, isoMax)
        }

        /** ISO for a fixed [shutterNs] and, with a fixed [iso], the shutter: for your own S or ISO. */
        fun shutterForIso(iso: Int, shutterMin: Long, shutterMax: Long): Long = (product / iso).toLong().coerceIn(shutterMin, shutterMax)
    }
}
