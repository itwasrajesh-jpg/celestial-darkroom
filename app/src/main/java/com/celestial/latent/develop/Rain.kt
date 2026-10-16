package com.celestial.latent.develop

import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rain on the window (65a), the WET step's WINDOW chip: kept inside the photo's [WetLook]. Off on
 * every photo until it is turned on. Where drops are wiped away (or added) is painted on its own
 * map, [ExposureMaps.RAIN]: −1 wiped clean … 0 as the rain left it … +1 twice the drops.
 */
data class RainLook(
    val on: Boolean = false,
    /** False: the camera focused on the glass, the street behind melts. True: on the street, the drops go soft. */
    val streetFocus: Boolean = false,
    /** How rainy: 0.3 … 2, the proof's number of drops at 1. */
    val amount: Float = 1f,
    /** Drop size: 0.6 … 1.6. */
    val size: Float = 1f,
    /** How many big drops ran down, leaving trails: 0 … 2. */
    val trails: Float = 1f,
    /** How soft the out-of-focus part is: 0.4 … 1.6 (the street, or the drops). */
    val blur: Float = 1f,
    /** Which drops: a new number is a new pattern (the shuffle button). */
    val seed: Int = 5,
) {
    fun key(): String = listOf("r1", if (on) "1" else "0", if (streetFocus) "1" else "0", f(amount), f(size), f(trails), f(blur), seed.toString()).joinToString("|")
    private fun f(v: Float) = "%.3f".format(Locale.US, v)

    companion object {
        fun parse(s: String?): RainLook {
            if (s.isNullOrEmpty()) return RainLook()
            return runCatching {
                val p = s.split("|")
                require(p[0] == "r1")
                RainLook(p[1] == "1", p[2] == "1", p[3].toFloat().coerceIn(0.3f, 2f), p[4].toFloat().coerceIn(0.6f, 1.6f),
                    p[5].toFloat().coerceIn(0f, 2f), p[6].toFloat().coerceIn(0.4f, 1.6f), p[7].toInt())
            }.getOrDefault(RainLook())
        }
    }
}

/**
 * Rain on the window (65a): the photo as if taken through a rainy pane. The proof
 * (`raindrops/scripts/drops.py`, v2, approved by Celestial) made into one pass over a print of any size:
 *  1. Water on the glass: fine mist beads, drops of mixed sizes gathered in patches, and a few big
 *     drops that ran down, leaving thin broken trails with beads behind. Sizes are the proof's, for a
 *     picture 1600 across.
 *  2. Each drop is a small fisheye lens: it shows a fixed wide cone of the scene upside down, goes
 *     dark at its steep rim, and catches a highlight and a soft caustic lit by what is around it (at
 *     night only the drops near a lamp shine). A trail only bends the view sideways.
 *  3. Drops in focus: the scene behind blurs evenly as a lens does it (a disc), and every compact
 *     light gathers into one round disc; a bright sky is left as it is. Street in focus: the drops
 *     go soft over the sharp photo.
 * Everything that needs neighbours is worked on a 1600 grid (and the background blur on a 400 one);
 * the drops themselves are drawn at the print's own size, so they stay crisp.
 *
 * Applied before the film, last of the things in front of the lens (after the fog and the rays),
 * before the lens filter: the film sees the rain as it sees everything else.
 */
object Rain {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    private const val MID = 1600
    private const val QUARTER = 400
    private const val BAND = 64

    fun wanted(look: RainLook): Boolean = look.on

    /** Puts the rain on the glass, in place, before the film. */
    fun apply(src: Develop.Source, look: RainLook, wipe: ExposureMap?, log: (String) -> Unit = {}) {
        if (!wanted(look)) return
        val t0 = System.currentTimeMillis()
        log("rain on the window")
        val f = src.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        process(f, src.width, src.height, look, wipe)
        android.util.Log.i("Latent", "rain: ${src.width}×${src.height} in ${System.currentTimeMillis() - t0} ms")
    }

    /** One spot of water on the glass, in the print's pixels. */
    internal class Cap(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val sag: Float, val height: Float, val trail: Boolean)

    /** A plain three-channel picture. */
    internal class Img(val w: Int, val h: Int, val p: FloatArray = FloatArray(w * h * 3))

    /** The work itself, on a plain buffer: [f] three floats per pixel (linear ProPhoto), [w] × [h]. In place. */
    internal fun process(f: FloatBuffer, w: Int, h: Int, look: RainLook, wipe: ExposureMap?) {
        val scene = scene(f, w, h, look)
        draw(f, scene, glass(w, h, scene.u, look, wipe))
    }

    /**
     * Everything the rain needs from the picture behind the glass, worked out once: the living
     * photo (66a) draws many panes of moving water over one scene.
     */
    internal class Scene(val w: Int, val h: Int, val look: RainLook, val u: Float, val e: Float, val sm: Float, val um: Float,
                         val sq: Float, val mw: Int, val mh: Int, val seen: Img, val sky: Img, val back: Img?)

    /** The picture behind the glass, read for the rain (the first half of [process]). */
    internal fun scene(f: FloatBuffer, w: Int, h: Int, look: RainLook): Scene {
        val long = max(w, h)
        val u = long / 1600f                                   // the proof's pixel sizes were for 1600 across

        // ---- the scene on the 1600 grid, its white found from the top (a RAW may run past 1.0)
        val mid = shrink(f, w, h, min(MID, long))
        val mw = mid.w; val mh = mid.h; val mn = mw * mh
        val sm = mw.toFloat() / w                              // grid pixels per print pixel
        val um = u * sm
        val peaks = FloatArray(mn) { max(mid.p[it * 3], max(mid.p[it * 3 + 1], mid.p[it * 3 + 2])) }.also { it.sort() }
        val e = 1f / max(peaks[((mn - 1) * 0.999f).toInt()], 1f)
        for (i in mid.p.indices) mid.p[i] *= e

        // ---- lights: 'clipped' is relative to the photo's own brightest light (a phone JPEG rarely reaches white)
        val lum = FloatArray(mn) { LUM[0] * mid.p[it * 3] + LUM[1] * mid.p[it * 3 + 1] + LUM[2] * mid.p[it * 3 + 2] }
        val sorted = lum.copyOf().also { it.sort() }
        val top = max(sorted[((mn - 1) * 0.9995f).toInt()], 0.25f)
        val clip = FloatArray(mn) { clip01((lum[it] / top - 0.8f) / 0.2f).pow(1.5f) }
        val R = 46f * um * look.blur                           // the lens' disc, on the grid
        val discs = lights(mid, clip, (12f * um).roundToInt().coerceAtLeast(1), R)   // also clears [clip] where a patch is no light

        // what the drops see (lights stretched back out) and what is left behind the glass without the lights
        val lh = Img(mw, mh); val base = Img(mw, mh)
        for (i in 0 until mn) for (c in 0..2) {
            val v = mid.p[i * 3 + c]
            lh.p[i * 3 + c] = v * (1f + 10f * clip[i]); base.p[i * 3 + c] = v * (1f - 0.97f * clip[i])
        }
        val seen = blur3(lh, 1f * um)

        // ---- the 400 grid: the light falling on the drops, and (drops in focus) the blurred street with its discs
        val qLong = min(QUARTER, max(mw, mh))
        val sq = qLong.toFloat() / max(mw, mh)                 // quarter pixels per grid pixel
        val lhq = shrinkImg(lh, qLong)
        val sky = blur3(lhq, 45f * um * sq).also { for (i in it.p.indices) it.p[i] *= 1.6f }
        val back = if (look.streetFocus) null else {
            val bq = discBlur(shrinkImg(base, qLong), R * sq)
            for (d in discs) drawDisc(bq, d, sq, 3f * um * sq)
            bq
        }

        return Scene(w, h, look, u, e, sm, um, sq, mw, mh, seen, sky, back)
    }

    /** The water [caps] drawn over [f] (the picture [scene] was read from), in place (the second half of [process]). */
    internal fun draw(f: FloatBuffer, scene: Scene, caps: List<Cap>) {
        val w = scene.w; val h = scene.h; val look = scene.look; val u = scene.u; val e = scene.e
        val sm = scene.sm; val um = scene.um; val sq = scene.sq; val mw = scene.mw; val mh = scene.mh; val mn = mw * mh
        val seen = scene.seen; val sky = scene.sky; val back = scene.back
        val zt = 0.15f * u

        // one drop's colour at a print point, into [o], given the cap on top there; returns how much water covers it
        fun shade(cap: Cap, x: Float, y: Float, z: Float, o: FloatArray): Float {
            val px = (x - cap.cx) / cap.rx
            val dy = y - cap.cy
            val ryy = if (dy > 0f) cap.ry * (1f + cap.sag) else cap.ry
            val py = dy / ryy
            val rho = min(sqrt(px * px + py * py), 0.999f)
            val k = min(cap.rx, cap.ry) * 0.75f * cap.height
            val s = sqrt(1f - rho * rho)
            val gx = -k * px / (cap.rx * max(s, 0.12f)); val gy = -k * py / (ryy * max(s, 0.12f))
            val slope = sqrt(gx * gx + gy * gy)
            val cov = clip01((z - zt) / max(slope, 0.3f) + 0.5f)
            if (cov <= 0f) return 0f
            // what it shows: the scene upside down and squeezed (a fixed wide cone), or bent sideways in a trail
            val sx: Float; val sy: Float
            if (cap.trail) { sx = x - px * 6f * u; sy = y } else {
                val fe = 55f * u / (s + 0.35f)
                sx = cap.cx - px * fe; sy = cap.cy - py * fe
            }
            sampleReflect(seen, (sx + 0.5f) * sm - 0.5f, (sy + 0.5f) * sm - 0.5f, o)
            val rim = clip01((rho - 0.5f) / 0.45f).pow(1.3f)
            val dark = 0.82f * (1f - (if (cap.trail) 0.45f else 0.88f) * rim)
            val nz = 1f / sqrt(1f + slope * slope)
            val d = (-gx * nz) * -0.45f + (-gy * nz) * -0.75f
            val spec = clip01((max(d, 0f) * min(slope, 1.5f) - 0.55f) / 0.35f).let { it * it * it }
            val caus = if (slope > 0.15f) max(-d, 0f) * (1f - rim) else 0f
            val qx = ((x + 0.5f) * sm) * sq - 0.5f; val qy = ((y + 0.5f) * sm) * sq - 0.5f
            for (c in 0..2) {
                val l = bilinear(sky, qx, qy, c)
                o[c] = o[c] * dark + spec * l * 2.2f + caus * caus * l * 0.25f
            }
            return cov
        }

        if (!look.streetFocus) {
            // drops in focus: every print pixel is the blurred street, the drops drawn over it at full size
            val bq = back!!
            drawDrops(caps, w, h, 1f) { y0, y1, top0, idx ->
                val o = FloatArray(3); val bk = FloatArray(3)
                for (y in y0 until y1) for (x in 0 until w) {
                    val qx = ((x + 0.5f) * sm) * sq - 0.5f; val qy = ((y + 0.5f) * sm) * sq - 0.5f
                    for (c in 0..2) bk[c] = bilinear(bq, qx, qy, c)
                    val j = (y - y0) * w + x
                    val ci = idx[j]
                    val cov = if (ci >= 0) shade(caps[ci], x.toFloat(), y.toFloat(), top0[j], o) else 0f
                    val p = (y * w + x) * 3
                    for (c in 0..2) f.put(p + c, shoulder(bk[c] * (1f - cov) + o[c] * cov) / e)
                }
            }
        } else {
            // street in focus: the drops worked on the grid, softened, then laid over the sharp print
            val cov = FloatArray(mn); val ins = Img(mw, mh)
            val inv = 1f / sm
            val capsMid = caps.map { Cap(it.cx * sm, it.cy * sm, it.rx * sm, it.ry * sm, it.sag, it.height, it.trail) }
            drawDrops(capsMid, mw, mh, 1f) { y0, y1, top0, idx ->
                val o = FloatArray(3)
                for (y in y0 until y1) for (x in 0 until mw) {
                    val j = (y - y0) * mw + x; val ci = idx[j]
                    if (ci < 0) continue
                    // shade in print coordinates, from the grid's own drop (z scales with the drop)
                    val c0 = caps[ci]
                    val cv = shade(c0, (x + 0.5f) * inv - 0.5f, (y + 0.5f) * inv - 0.5f, top0[j] * inv, o)
                    val i = y * mw + x
                    cov[i] = cv
                    for (c in 0..2) ins.p[i * 3 + c] = o[c] * cv
                }
            }
            val sig = 5f * um * look.blur
            val a = Wet.gauss(cov, mw, mh, sig, sig)
            val d = blur3(ins, sig)
            for (i in 0 until mn) {
                val ai = max(a[i], 1e-4f)
                for (c in 0..2) d.p[i * 3 + c] = shoulder(d.p[i * 3 + c] / ai)
                a[i] = a[i] * 0.9f
            }
            parallel(h) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until w) {
                    val gx = (x + 0.5f) * sm - 0.5f; val gy = (y + 0.5f) * sm - 0.5f
                    val al = bilinear1(a, mw, mh, gx, gy)
                    if (al < 1e-4f) continue
                    val p = (y * w + x) * 3
                    for (c in 0..2) f.put(p + c, f.get(p + c) * (1f - al) + bilinear(d, gx, gy, c) / e * al)
                }
            }
        }
    }

    /** The proof's highlight roll-off, above 0.75 only: below it the picture is untouched. */
    private fun shoulder(x: Float): Float {
        val k = 0.75f
        return if (x < k) x else k + (1f - k) * (1f - exp(-(x - k) / (1f - k) * 1.5f))
    }

    // ---------------------------------------------------------------- the glass

    /** The water on the glass, as the proof's make_glass, in print pixels. */
    internal fun glass(w: Int, h: Int, u: Float, look: RainLook, wipe: ExposureMap?): List<Cap> {
        val rng = Random(look.seed.toLong() * 7919L + 17L)
        val caps = ArrayList<Cap>()
        val area = (w / u) * (h / u) / (1600f * 1200f)
        // real glass is patchy: some areas collect many drops, some stay almost clear
        val pw = 8; val ph = 6
        val patch = FloatArray(pw * ph) { rng.nextFloat() }
        val lo = patch.min(); val hi = patch.max()
        for (i in patch.indices) patch[i] = (patch[i] - lo) / (hi - lo + 1e-6f)
        fun density(x: Float, y: Float): Float {
            val gx = (x / w * pw - 0.5f).coerceIn(0f, pw - 1f); val gy = (y / h * ph - 0.5f).coerceIn(0f, ph - 1f)
            val x0 = gx.toInt(); val y0 = gy.toInt(); val x1 = min(x0 + 1, pw - 1); val y1 = min(y0 + 1, ph - 1)
            val fx = smooth(gx - x0); val fy = smooth(gy - y0)
            val v = (patch[y0 * pw + x0] * (1 - fx) + patch[y0 * pw + x1] * fx) * (1 - fy) + (patch[y1 * pw + x0] * (1 - fx) + patch[y1 * pw + x1] * fx) * fy
            return v.coerceIn(0f, 1f).pow(1.5f)
        }
        fun wiped(x: Float, y: Float): Float = wipe?.sample(x / w, y / h)?.coerceIn(-1f, 1f) ?: 0f
        // rejection sampling on the patches, times the painted wipe (−1 none … +1 twice)
        fun place(yMin: Float = 0f): FloatArray? {
            repeat(400) {
                val x = rng.nextFloat() * w; val y = yMin + rng.nextFloat() * (h - yMin)
                val p = (0.15f + 0.85f * density(x, y)) * (1f + wiped(x, y))
                if (rng.nextFloat() * 2f < p) return floatArrayOf(x, y)
            }
            return null
        }
        fun cap(cx: Float, cy: Float, rx: Float, ry: Float, sag: Float = 0f, height: Float = 1f, trail: Boolean = false) {
            caps.add(Cap(cx, cy, rx, ry, sag, height, trail))
        }
        val sz = look.size
        // tiny mist beads
        repeat((3500 * area * look.amount).toInt()) {
            val r = (1.0f + rng.nextFloat() * 1.6f) * u
            val p = place() ?: return@repeat
            cap(p[0], p[1], r, r * (0.9f + rng.nextFloat() * 0.2f))
        }
        // small and medium drops: lognormal sizes, most small, not perfect circles
        repeat((800 * area * look.amount).toInt()) {
            val r = exp(ln(4.5) + 0.65 * rng.nextGaussian()).toFloat().coerceIn(2f, 30f) * u * sz
            val el = 0.8f + rng.nextFloat() * 0.5f
            val p = place() ?: return@repeat
            val sag = if (r > 9f * u * sz) rng.nextFloat() * 0.35f else 0f
            cap(p[0], p[1], r * (0.85f + rng.nextFloat() * 0.15f), r * el, sag)
        }
        // big drops that ran: a thin broken trail above, beads left on it
        repeat((7 * area * look.trails * sqrt(look.amount)).roundToInt()) {
            val r = (13f + rng.nextFloat() * 11f) * u * sz
            val p = place(0.25f * h) ?: return@repeat
            val cx = p[0]; val cy = p[1]
            val len = (0.15f + rng.nextFloat() * 0.45f) * h
            val n = max(2, (len / u).toInt())                  // one step per proof pixel
            val ys = FloatArray(n) { cy - len + it * u }
            val wob = FloatArray(n)
            var acc = 0f
            val period = (30f + rng.nextFloat() * 60f) * u; val amp = (4f + rng.nextFloat() * 10f) * u
            for (i in 0 until n) { acc += (rng.nextGaussian() * 0.9).toFloat() * u; wob[i] = acc + sin(ys[i] / period) * amp }
            val drift = (rng.nextGaussian() * 0.08).toFloat() * len
            val xs = FloatArray(n) { cx + wob[it] - wob[n - 1] + drift * (1f - it / (n - 1f)) }
            val tw = r * (0.12f + rng.nextFloat() * 0.10f)
            var gap = false
            for (i in 0 until n) {
                if (rng.nextFloat() < 0.004f) gap = !gap      // the film breaks up into pieces
                if (i % 2 != 0) continue
                val t = i / (n - 1f)
                if (gap && t < 0.85f) continue
                if (wiped(xs[i], ys[i]) < -0.5f) continue
                cap(xs[i], ys[i], tw * (0.3f + 0.7f * t) * (0.8f + rng.nextFloat() * 0.3f), tw * 1.5f, height = 0.35f, trail = true)
            }
            repeat((len / (25f * u)).toInt()) {
                val i = rng.nextInt(n)
                val br = (1.5f + rng.nextFloat() * 3f) * u
                if (wiped(xs[i], ys[i]) > -0.5f) cap(xs[i] + (rng.nextGaussian() * 1.5).toFloat() * u, ys[i], br, br)
            }
            cap(xs[n - 1], cy, r, r * 1.1f, sag = 0.45f)
        }
        return caps
    }

    /**
     * Draws [caps] over a [w] × [h] picture in bands of rows, the highest water winning each pixel,
     * and hands each band to [body] (first row, end row, the band's heights, the band's cap indices,
     * −1 for dry glass). Bands run in parallel; each is its own, so the split never changes the result.
     */
    private fun drawDrops(caps: List<Cap>, w: Int, h: Int, scale: Float, body: (Int, Int, FloatArray, IntArray) -> Unit) {
        val bands = (h + BAND - 1) / BAND
        val lists = Array(bands) { ArrayList<Int>() }
        for ((i, c) in caps.withIndex()) {
            val y0 = floor((c.cy - c.ry - 2f) * scale).toInt().coerceAtLeast(0)
            val y1 = ceil((c.cy + c.ry * (1f + c.sag) + 3f) * scale).toInt().coerceAtMost(h - 1)
            if (y1 < y0) continue
            for (b in y0 / BAND..y1 / BAND) lists[b].add(i)
        }
        parallel(bands) { b0, b1 ->
            val zb = FloatArray(BAND * w); val ib = IntArray(BAND * w)
            for (b in b0 until b1) {
                val y0 = b * BAND; val y1 = min(h, y0 + BAND)
                zb.fill(0f); ib.fill(-1)
                for (ci in lists[b]) {
                    val c = caps[ci]
                    val x0 = max(0, floor(c.cx - c.rx - 2f).toInt()); val x1 = min(w - 1, ceil(c.cx + c.rx + 3f).toInt())
                    val ya = max(y0, floor(c.cy - c.ry - 2f).toInt()); val yb = min(y1 - 1, ceil(c.cy + c.ry * (1f + c.sag) + 3f).toInt())
                    val k = min(c.rx, c.ry) * 0.75f * c.height
                    for (y in ya..yb) {
                        val dy = y - c.cy
                        val ryy = if (dy > 0f) c.ry * (1f + c.sag) else c.ry
                        val qy = dy / ryy
                        for (x in x0..x1) {
                            val qx = (x - c.cx) / c.rx
                            val d2 = qx * qx + qy * qy
                            if (d2 >= 1f) continue
                            val z = sqrt(1f - d2) * k
                            val j = (y - y0) * w + x
                            if (z > zb[j]) { zb[j] = z; ib[j] = ci }
                        }
                    }
                }
                body(y0, y1, zb, ib)
            }
        }
    }

    // ---------------------------------------------------------------- lights

    /** One out-of-focus light: its centre and radius on the 1600 grid, and its colour (energy per grid pixel). */
    private class Disc(val x: Float, val y: Float, val r: Float, val rgb: FloatArray)

    /**
     * Every compact bright patch gathers into ONE round disc at its centre, radius max([R], 0.62·√area),
     * holding 5× its light. Patches over 2% of the frame are a bright sky, not a light: [clip] is
     * cleared there (and wherever a patch is too small), so they stay in the picture and just blur.
     */
    private fun lights(mid: Img, clip: FloatArray, grow: Int, R: Float): List<Disc> {
        val w = mid.w; val h = mid.h; val n = w * h
        val raw = FloatArray(n) { if (clip[it] > 0.08f) 1f else 0f }
        val grown = dilate(raw, w, h, grow)
        val lab = IntArray(n) { -1 }
        val stack = IntArray(n)
        val keep = ArrayList<Boolean>()
        val out = ArrayList<Disc>()
        var label = 0
        for (s in 0 until n) {
            if (grown[s] < 0.5f || lab[s] >= 0) continue
            var sp = 0; stack[sp++] = s; lab[s] = label
            var count = 0; var a = 0; var sx = 0.0; var sy = 0.0
            val e = DoubleArray(3)
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                count++; sx += x; sy += y
                if (clip[i] > 0.08f) a++
                for (c in 0..2) e[c] += (mid.p[i * 3 + c] * clip[i]).toDouble()
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (grown[j] >= 0.5f && lab[j] < 0) { lab[j] = label; stack[sp++] = j }
                }
            }
            val ok = a >= 4 && count <= 0.02f * n
            keep.add(ok)
            if (ok) {
                val r = max(R, 0.62f * sqrt(a.toFloat()))
                val k = 5f / (PI.toFloat() * r * r)
                out.add(Disc((sx / count).toFloat(), (sy / count).toFloat(), r, FloatArray(3) { (e[it] * k).toFloat() }))
            }
            label++
        }
        for (i in 0 until n) if (lab[i] < 0 || !keep[lab[i]]) clip[i] = 0f
        return out
    }

    /** Adds [d] (grid units) to the quarter picture [q] ([sq] quarter pixels per grid pixel), its edge softened by [soft]. */
    private fun drawDisc(q: Img, d: Disc, sq: Float, soft: Float) {
        val cx = d.x * sq; val cy = d.y * sq; val r = d.r * sq
        val edge = max(1f, soft * 2.5f)
        val x0 = max(0, floor(cx - r - edge).toInt()); val x1 = min(q.w - 1, ceil(cx + r + edge).toInt())
        val y0 = max(0, floor(cy - r - edge).toInt()); val y1 = min(q.h - 1, ceil(cy + r + edge).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val cov = clip01((r - dist) / edge + 0.5f)
            if (cov <= 0f) continue
            for (c in 0..2) q.p[(y * q.w + x) * 3 + c] += d.rgb[c] * cov
        }
    }

    // ---------------------------------------------------------------- pictures

    /** The print box-averaged to [long] on its long side. */
    private fun shrink(f: FloatBuffer, w: Int, h: Int, long: Int): Img {
        val gw: Int; val gh: Int
        if (w >= h) { gw = min(long, w); gh = max(1, (gw.toFloat() * h / w).roundToInt()) } else { gh = min(long, h); gw = max(1, (gh.toFloat() * w / h).roundToInt()) }
        val out = Img(gw, gh); val count = IntArray(gw * gh)
        for (y in 0 until h) {
            val gy = (y.toLong() * gh / h).toInt().coerceAtMost(gh - 1)
            for (x in 0 until w) {
                val g = gy * gw + (x.toLong() * gw / w).toInt().coerceAtMost(gw - 1); val o = (y * w + x) * 3
                out.p[g * 3] += f.get(o); out.p[g * 3 + 1] += f.get(o + 1); out.p[g * 3 + 2] += f.get(o + 2); count[g]++
            }
        }
        for (g in 0 until gw * gh) { val c = max(count[g], 1); for (k in 0..2) out.p[g * 3 + k] /= c }
        return out
    }

    private fun shrinkImg(a: Img, long: Int): Img {
        if (max(a.w, a.h) <= long) return Img(a.w, a.h, a.p.copyOf())
        return shrink(FloatBuffer.wrap(a.p), a.w, a.h, long)
    }

    /** Each channel Gaussian-blurred by [sigma]. */
    private fun blur3(a: Img, sigma: Float): Img {
        val n = a.w * a.h; val out = Img(a.w, a.h)
        for (c in 0..2) {
            val ch = FloatArray(n) { a.p[it * 3 + c] }
            val b = Wet.gauss(ch, a.w, a.h, sigma, sigma)
            for (i in 0 until n) out.p[i * 3 + c] = b[i]
        }
        return out
    }

    /** A lens' out-of-focus blur: the average over a disc of radius [r] (its rim anti-aliased), edges mirrored. */
    private fun discBlur(a: Img, r: Float): Img {
        val ri = ceil(r + 0.5f).toInt()
        val dx = ArrayList<Int>(); val dy = ArrayList<Int>(); val wt = ArrayList<Float>()
        for (y in -ri..ri) for (x in -ri..ri) {
            val v = clip01(r + 0.5f - sqrt((x * x + y * y).toFloat()))
            if (v > 0f) { dx.add(x); dy.add(y); wt.add(v) }
        }
        val sum = wt.sum()
        val kx = dx.toIntArray(); val ky = dy.toIntArray(); val kw = FloatArray(wt.size) { wt[it] / sum }
        val out = Img(a.w, a.h)
        parallel(a.h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until a.w) {
                var r0 = 0f; var g0 = 0f; var b0 = 0f
                for (k in kx.indices) {
                    val xx = mirror(x + kx[k], a.w); val yy = mirror(y + ky[k], a.h)
                    val p = (yy * a.w + xx) * 3; val q = kw[k]
                    r0 += a.p[p] * q; g0 += a.p[p + 1] * q; b0 += a.p[p + 2] * q
                }
                val o = (y * a.w + x) * 3
                out.p[o] = r0; out.p[o + 1] = g0; out.p[o + 2] = b0
            }
        }
        return out
    }

    /** Dilation (max) over a (2r+1)² square. */
    private fun dilate(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (d in -r..r) { val xx = x + d; if (xx in 0 until w) m = max(m, a[y * w + xx]) }
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (d in -r..r) { val yy = y + d; if (yy in 0 until h) m = max(m, tmp[yy * w + x]) }
            out[y * w + x] = m
        }
        return out
    }

    /** Index [i] mirrored into [0, n) (OpenCV's BORDER_REFLECT, as the proof's remap). */
    private fun mirror(i: Int, n: Int): Int {
        if (n == 1) return 0
        var v = i
        val period = 2 * n
        v = ((v % period) + period) % period
        return if (v < n) v else period - 1 - v
    }

    /** Bilinear at index coordinates, edges mirrored, into [o]. */
    private fun sampleReflect(a: Img, x: Float, y: Float, o: FloatArray) {
        val x0f = floor(x); val y0f = floor(y)
        val fx = x - x0f; val fy = y - y0f
        val xa = mirror(x0f.toInt(), a.w); val xb = mirror(x0f.toInt() + 1, a.w)
        val ya = mirror(y0f.toInt(), a.h); val yb = mirror(y0f.toInt() + 1, a.h)
        for (c in 0..2) {
            val t = a.p[(ya * a.w + xa) * 3 + c] * (1 - fx) + a.p[(ya * a.w + xb) * 3 + c] * fx
            val b = a.p[(yb * a.w + xa) * 3 + c] * (1 - fx) + a.p[(yb * a.w + xb) * 3 + c] * fx
            o[c] = t * (1 - fy) + b * fy
        }
    }

    /** Bilinear at index coordinates, edges held. */
    private fun bilinear(a: Img, x: Float, y: Float, c: Int): Float {
        val xc = x.coerceIn(0f, (a.w - 1).toFloat()); val yc = y.coerceIn(0f, (a.h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, a.w - 1); val y1 = min(y0 + 1, a.h - 1)
        val fx = xc - x0; val fy = yc - y0
        return (a.p[(y0 * a.w + x0) * 3 + c] * (1 - fx) + a.p[(y0 * a.w + x1) * 3 + c] * fx) * (1 - fy) +
            (a.p[(y1 * a.w + x0) * 3 + c] * (1 - fx) + a.p[(y1 * a.w + x1) * 3 + c] * fx) * fy
    }

    private fun bilinear1(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0; val fy = yc - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }

    private fun clip01(x: Float) = x.coerceIn(0f, 1f)
    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Rows [0, rows) in bands, one per core; each band's work is its own, so the split never changes the result. */
    private fun parallel(rows: Int, body: (Int, Int) -> Unit) {
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(rows)
        if (bands <= 1) { body(0, rows); return }
        var failure: Throwable? = null
        val workers = (0 until bands).map { b ->
            Thread { runCatching { body(rows * b / bands, rows * (b + 1) / bands) }.onFailure { failure = it } }.apply { start() }
        }
        workers.forEach { it.join() }
        failure?.let { throw it }
    }
}
