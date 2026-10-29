package com.celestial.latent.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How a print has aged (step 97a, the AGE step): where it was kept, how far its colour faded and how worn the paper is.
 * [fade] and [wear] run 0 … 1.4: 1 is the story's own years (fridge 8, album 25, wallet 15, shoebox 30).
 */
data class AgeLook(
    val story: String = "",
    /** The dyes fading: colour only. */
    val fade: Float = 1f,
    /** The paper's damage only: curl, glue, folds and cracks, scuffs, spots, silvering, frayed corners. */
    val wear: Float = 1f,
) {
    val on: Boolean get() = story in AgedPrint.STORIES

    fun key(): String = listOf("v1", story, "%.3f".format(Locale.US, fade), "%.3f".format(Locale.US, wear)).joinToString("|")

    companion object {
        fun parse(s: String?): AgeLook {
            if (s.isNullOrEmpty()) return AgeLook()
            return runCatching {
                val p = s.split("|")
                AgeLook(p[1], p[2].toFloat().coerceIn(0f, AgedPrint.MAX), p[3].toFloat().coerceIn(0f, AgedPrint.MAX))
            }.getOrDefault(AgeLook())
        }
    }
}

/** Each photo's age, kept with the photo (like its lith look): an aged print is that photo's, never the recipe's. */
object AgeLooks {
    private const val FILE = "latent_age"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun load(context: Context, photo: Uri): AgeLook = AgeLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: AgeLook) {
        prefs(context).edit().apply { if (look == AgeLook()) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}

/**
 * Age this print (97a): the developed print as it would look after years on a fridge door, in an album, in a wallet or
 * in a shoebox. The approved proofs (aged-print/proof/scripts/aged.py, the FADE + WEAR dials of v2/scripts/age2.py),
 * made to work on a print of any size; `aged-print/97a/scripts/agep.py` is this file in Python, checked against the
 * proof and against this code.
 *
 * Colour: the print is turned back into the amounts of its paper's three dyes (Kodak Ektacolor Edge, from the engine's
 * own profile), the dyes fade as they really do — light takes magenta and yellow first (a fridge print turns cyan), the
 * dark takes cyan first (an album print warms) — and the paper yellows; then the dyes are lit again, spectrally, in
 * daylight. Both directions go through tables (the dye fit 33³, made from the proof's own per-pixel fit; the render
 * 33³ × 7 stain levels, worked out here from the paper's spectra in `assets/age/paper.bin`).
 *
 * Damage: drawn as the paper's surface (a height) and lit from the top left, so creases and scuffs catch the light.
 * Everything that needs neighbours is worked out once on a map of the print whose photo's long edge is [MAP_LONG], and
 * laid on the print at any size, so the preview and the saved print carry the same folds, spots and scuffs. Sizes are
 * the proof's at 640 px, scaled. The random marks come from a counter hash, the same in the Python port.
 *
 * The print keeps a margin of its paper; where the corners are rounded or frayed away, a dark scanner lid shows.
 */
object AgedPrint {
    val STORIES = listOf("fridge", "album", "wallet", "shoebox")
    /** The story's own years, for the labels. */
    val YEARS = mapOf("fridge" to 8, "album" to 25, "wallet" to 15, "shoebox" to 30)
    const val MAX = 1.4f
    /** The damage map's photo long edge. */
    const val MAP_LONG = 960

    private val BORDER = mapOf("fridge" to 20, "album" to 26, "wallet" to 18, "shoebox" to 24)
    private val FADE = mapOf("fridge" to floatArrayOf(0.18f, 0.55f, 0.62f), "album" to floatArrayOf(0.30f, 0.10f, 0.06f),
        "wallet" to floatArrayOf(0.15f, 0.25f, 0.25f), "shoebox" to floatArrayOf(0.45f, 0.18f, 0.10f))
    private val MULK = mapOf("fridge" to floatArrayOf(0f, 0f, 0f), "album" to floatArrayOf(0.14f, 0.22f, 0.38f),
        "wallet" to floatArrayOf(0.75f, 0.75f, 0.75f), "shoebox" to floatArrayOf(0.25f, 0.42f, 0.62f))
    private val SILVER = floatArrayOf(0.30f, 0.33f, 0.38f)
    private val PAPER = floatArrayOf(0.96f, 0.955f, 0.94f)
    private val BG = floatArrayOf(0.07f, 0.07f, 0.075f)

    /** The margin of paper round a [w] × [h] picture, in its own pixels. */
    fun border(w: Int, h: Int, story: String): Int = Math.rint(BORDER.getValue(story) * max(w, h) / 640.0).toInt()
    fun outputSize(w: Int, h: Int, story: String): Pair<Int, Int> = border(w, h, story).let { b -> (w + 2 * b) to (h + 2 * b) }

    // ---------------------------------------------------------------------------------------------------------------
    // The paper's tables
    // ---------------------------------------------------------------------------------------------------------------

    internal class Paper(val n: Int, val fit: FloatArray, val nc: Int, val nw: Int, val cmax: Float, val wmax: Float,
                         val lt: FloatArray, val mout: FloatArray) {
        /** The dye amounts of an sRGB-encoded colour (0..1), trilinear in the fit table. */
        fun dyes(r: Float, g: Float, b: Float, out: FloatArray) {
            val s = (n - 1).toFloat()
            val x = r.coerceIn(0f, 1f) * s; val y = g.coerceIn(0f, 1f) * s; val z = b.coerceIn(0f, 1f) * s
            val i = min(x.toInt(), n - 2); val j = min(y.toInt(), n - 2); val k = min(z.toInt(), n - 2)
            val fx = x - i; val fy = y - j; val fz = z - k
            for (c in 0..2) {
                fun at(a: Int, bb: Int, cc: Int) = fit[((a * n + bb) * n + cc) * 3 + c]
                val c00 = at(i, j, k) + (at(i, j, k + 1) - at(i, j, k)) * fz
                val c01 = at(i, j + 1, k) + (at(i, j + 1, k + 1) - at(i, j + 1, k)) * fz
                val c10 = at(i + 1, j, k) + (at(i + 1, j, k + 1) - at(i + 1, j, k)) * fz
                val c11 = at(i + 1, j + 1, k) + (at(i + 1, j + 1, k + 1) - at(i + 1, j + 1, k)) * fz
                val c0 = c00 + (c01 - c00) * fy; val c1 = c10 + (c11 - c10) * fy
                out[c] = c0 + (c1 - c0) * fx
            }
        }

        /** Dyes [c] on paper with [stain] of yellowing, seen in daylight: sRGB encoded, clipped (lamps.show). */
        fun render(c: FloatArray, stain: Float, out: FloatArray) {
            val s = (nc - 1).toFloat()
            val p0 = sqrt(c[0].coerceIn(0f, cmax) / cmax) * s; val p1 = sqrt(c[1].coerceIn(0f, cmax) / cmax) * s
            val p2 = sqrt(c[2].coerceIn(0f, cmax) / cmax) * s; val p3 = stain.coerceIn(0f, wmax) / wmax * (nw - 1)
            val i = min(p0.toInt(), nc - 2); val j = min(p1.toInt(), nc - 2); val k = min(p2.toInt(), nc - 2); val l = min(p3.toInt(), nw - 2)
            val f0 = p0 - i; val f1 = p1 - j; val f2 = p2 - k; val f3 = p3 - l
            var x0 = 0f; var x1 = 0f; var x2 = 0f
            for (corner in 0 until 16) {
                val a = corner shr 3 and 1; val bb = corner shr 2 and 1; val cc = corner shr 1 and 1; val d = corner and 1
                val wgt = (if (a == 1) f0 else 1f - f0) * (if (bb == 1) f1 else 1f - f1) * (if (cc == 1) f2 else 1f - f2) * (if (d == 1) f3 else 1f - f3)
                if (wgt == 0f) continue
                val o = ((((i + a) * nc + j + bb) * nc + k + cc) * nw + l + d) * 3
                x0 += wgt * lt[o]; x1 += wgt * lt[o + 1]; x2 += wgt * lt[o + 2]
            }
            val X = exp(x0); val Y = exp(x1); val Z = exp(x2)
            for (q in 0..2) out[q] = srgbEncode(mout[q * 3] * X + mout[q * 3 + 1] * Y + mout[q * 3 + 2] * Z).coerceIn(0f, 1f)
        }

        companion object {
            /** assets/age/paper.bin: the dyes, base, stain, daylight × observer weights, output matrix and the dye fit. */
            fun parse(bytes: ByteArray): Paper {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.int; val n = bb.int; val nc = bb.int; val nw = bb.int
                val cmax = bb.float; val wmax = bb.float
                fun read(k: Int) = FloatArray(k) { bb.float }
                val dye = read(81 * 3); val base = read(81); val stain = read(81); val wt = read(81 * 3); val mout = read(9)
                val fit = read(n * n * n * 3)
                // the render table: ln XYZ of each grid point's reflectance; density grid squared (finer near white)
                val gc = FloatArray(nc) { val t = it / (nc - 1f); cmax * t * t }
                val t0 = Array(nc) { i -> FloatArray(81) { 10.0.pow(-(gc[i] * dye[it * 3]).toDouble()).toFloat() } }
                val t1 = Array(nc) { i -> FloatArray(81) { 10.0.pow(-(gc[i] * dye[it * 3 + 1]).toDouble()).toFloat() } }
                val t2 = Array(nc) { i -> FloatArray(81) { 10.0.pow(-(gc[i] * dye[it * 3 + 2]).toDouble()).toFloat() } }
                val ts = Array(nw) { w -> FloatArray(81) { 10.0.pow(-(base[it] + w * wmax / (nw - 1) * stain[it]).toDouble()).toFloat() } }
                val lt = FloatArray(nc * nc * nc * nw * 3)
                for (i in 0 until nc) for (j in 0 until nc) {
                    val ab = FloatArray(81) { t0[i][it] * t1[j][it] }
                    for (k in 0 until nc) {
                        val abc = FloatArray(81) { ab[it] * t2[k][it] }
                        for (w in 0 until nw) {
                            var X = 0.0; var Y = 0.0; var Z = 0.0
                            val sw = ts[w]
                            for (q in 0 until 81) { val r = (abc[q] * sw[q]).toDouble(); X += r * wt[q * 3]; Y += r * wt[q * 3 + 1]; Z += r * wt[q * 3 + 2] }
                            val o = (((i * nc + j) * nc + k) * nw + w) * 3
                            lt[o] = ln(X).toFloat(); lt[o + 1] = ln(Y).toFloat(); lt[o + 2] = ln(Z).toFloat()
                        }
                    }
                }
                return Paper(n, fit, nc, nw, cmax, wmax, lt, mout)
            }
        }
    }

    @Volatile private var paperCache: Paper? = null
    internal fun paper(context: Context): Paper = paperCache ?: synchronized(this) {
        paperCache ?: Paper.parse(context.assets.open("age/paper.bin").use { it.readBytes() }).also { paperCache = it }
    }

    private fun srgbEncode(l: Float): Float = if (l <= 0.0031308f) 12.92f * l else (1.055 * abs(l).toDouble().pow(1 / 2.4) - 0.055).toFloat()

    // ---------------------------------------------------------------------------------------------------------------
    // Random numbers: a counter hash (splitmix64), so the port draws the same marks
    // ---------------------------------------------------------------------------------------------------------------

    private const val GOLDEN = -0x61c8864680b583ebL   // 0x9E3779B97F4A7C15
    private fun hash64(seed: Long, i: Long): Long {
        var x = seed * GOLDEN + i
        x += GOLDEN
        var z = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L   // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L        // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }
    private fun uni(seed: Long, i: Long): Double = (hash64(seed, i) ushr 11).toDouble() * (1.0 / (1L shl 53))

    private class Stream(val seed: Long) {
        var n = 0L
        fun u(): Double = uni(seed, n++)
        fun uniform(a: Double, b: Double) = a + (b - a) * u()
        fun normal(m: Double, sd: Double): Double { val u1 = u(); val u2 = u(); return m + sd * sqrt(-2 * ln(1 - u1)) * cos(2 * Math.PI * u2) }
        fun integers(a: Int, b: Int): Int = a + min(b - a - 1, (u() * (b - a)).toInt())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fields on the map
    // ---------------------------------------------------------------------------------------------------------------

    /** Gaussian blur along rows (x) or columns (y), edges held. */
    private fun gauss1d(a: FloatArray, w: Int, h: Int, sigma: Double, alongX: Boolean): FloatArray {
        if (sigma <= 0) return a
        val r = ceil(3 * sigma).toInt()
        val k = DoubleArray(2 * r + 1) { exp(-0.5 * ((it - r) / sigma).let { v -> v * v }) }
        val sum = k.sum(); val kf = FloatArray(k.size) { (k[it] / sum).toFloat() }
        val out = FloatArray(a.size)
        if (alongX) {
            for (y in 0 until h) { val o = y * w; for (x in 0 until w) { var s = 0f; for (j in kf.indices) s += kf[j] * a[o + (x + j - r).coerceIn(0, w - 1)]; out[o + x] = s } }
        } else {
            for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (j in kf.indices) s += kf[j] * a[(y + j - r).coerceIn(0, h - 1) * w + x]; out[y * w + x] = s }
        }
        return out
    }
    private fun blur(a: FloatArray, w: Int, h: Int, sigma: Double) = gauss1d(gauss1d(a, w, h, sigma, false), w, h, sigma, true)

    /** Bilinear enlargement by [q] (a pixel centre of the big grid at (y + .5) / q − .5 of the small one), edges held. */
    private fun upsample(a: FloatArray, wq: Int, hq: Int, w: Int, h: Int, q: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ys = ((y + 0.5) / q - 0.5).coerceIn(0.0, hq - 1.0)
            val y0 = min(floor(ys).toInt(), hq - 1); val y1 = min(y0 + 1, hq - 1); val fy = (ys - y0).toFloat()
            for (x in 0 until w) {
                val xs = ((x + 0.5) / q - 0.5).coerceIn(0.0, wq - 1.0)
                val x0 = min(floor(xs).toInt(), wq - 1); val x1 = min(x0 + 1, wq - 1); val fx = (xs - x0).toFloat()
                val top = a[y0 * wq + x0] * (1 - fx) + a[y0 * wq + x1] * fx
                val bot = a[y1 * wq + x0] * (1 - fx) + a[y1 * wq + x1] * fx
                out[y * w + x] = top * (1 - fy) + bot * fy
            }
        }
        return out
    }

    /** White noise blurred by [r] px, unit spread; wide blurs are made small and enlarged (they are smooth). */
    private fun noise(w: Int, h: Int, r: Double, seed: Long): FloatArray {
        val q = max(1, floor(r / 4).toInt()); val hq = (h + q - 1) / q; val wq = (w + q - 1) / q
        var g = FloatArray(hq * wq) { i ->
            val u1 = uni(seed, 2L * i); val u2 = uni(seed, 2L * i + 1)
            (sqrt(-2 * ln(1 - u1)) * cos(2 * Math.PI * u2)).toFloat()
        }
        g = blur(g, wq, hq, r / q)
        var mean = 0.0; for (v in g) mean += v; mean /= g.size
        var vr = 0.0; for (v in g) vr += (v - mean) * (v - mean); val sd = sqrt(vr / g.size) + 1e-9
        for (i in g.indices) g[i] = ((g[i] - mean) / sd).toFloat()
        return if (q == 1) g else upsample(g, wq, hq, w, h, q)
    }

    private fun edgeDist(x: Int, y: Int, w: Int, h: Int) = min(min(x, y), min(w - 1 - x, h - 1 - y)).toFloat()

    private val LV = floatArrayOf(-0.45f, -0.55f, 0.70f).let { v -> val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); FloatArray(3) { v[it] / n } }
    private val HV = floatArrayOf(LV[0], LV[1], LV[2] + 1f).let { v -> val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); FloatArray(3) { v[it] / n } }

    /** The paper's surface lit from the top left: the diffuse factor into [d], the sheen into [s]. */
    private fun light(height: FloatArray, w: Int, h: Int, k: Float, sheen: Float, d: FloatArray, s: FloatArray) {
        for (y in 0 until h) for (x in 0 until w) {
            val gy = when (y) { 0 -> height[w + x] - height[x]; h - 1 -> height[y * w + x] - height[(y - 1) * w + x]; else -> (height[(y + 1) * w + x] - height[(y - 1) * w + x]) / 2 }
            val gx = when (x) { 0 -> height[y * w + 1] - height[y * w]; w - 1 -> height[y * w + x] - height[y * w + x - 1]; else -> (height[y * w + x + 1] - height[y * w + x - 1]) / 2 }
            val nx = -gx * k; val ny = -gy * k; val nn = sqrt(nx * nx + ny * ny + 1f)
            d[y * w + x] = ((nx * LV[0] + ny * LV[1] + LV[2]) / nn).coerceIn(0f, 1f) / LV[2]
            s[y * w + x] = ((nx * HV[0] + ny * HV[1] + HV[2]) / nn).coerceIn(0f, 1f).toDouble().pow(60.0).toFloat() * sheen
        }
    }

    /** Rounded corners, and with [wear] the edge nibbled. */
    private fun corners(w: Int, h: Int, r: Int, wear: Float, seed: Long, sc: Double): FloatArray {
        val a = FloatArray(w * h) { 1f }
        for (y in 0 until h) for (x in 0 until w) {
            val cy = if (y < r) r else if (y > h - r - 1) h - r - 1 else continue
            val cx = if (x < r) r else if (x > w - r - 1) w - r - 1 else continue
            if ((y - cy) * (y - cy) + (x - cx) * (x - cx) > r * r) a[y * w + x] = 0f
        }
        if (wear > 0f) {
            val e = noise(w, h, 2 * sc, seed)
            for (y in 0 until h) for (x in 0 until w) {
                if (edgeDist(x, y, w, h) < (2 + max(e[y * w + x] * wear, 0f) * 3) * sc) a[y * w + x] = 0f
            }
        }
        return a
    }

    /** Thin straight scratches, each a random grey; drawn in half-pixel steps, the brighter wins. */
    private fun scratches(w: Int, h: Int, n: Int, sc: Double, st: Stream): FloatArray {
        val out = FloatArray(w * h)
        val rad = max(1, Math.rint(sc).toInt())
        repeat(n) {
            val x = st.uniform(0.0, w.toDouble()); val y = st.uniform(0.0, h.toDouble()); val a = st.normal(0.3, 0.6)
            val len = st.uniform(15.0, 120.0) * sc; val v = (st.uniform(60.0, 200.0) / 255).toFloat()
            val steps = (len * 2).toInt() + 1; val ca = cos(a); val sa = kotlin.math.sin(a)
            for (k in 0..steps) {
                val px = x + ca * len * k / steps; val py = y + sa * len * k / steps
                val ix = px.toInt() - (rad - 1) / 2; val iy = py.toInt() - (rad - 1) / 2
                for (oy in 0 until rad) for (ox in 0 until rad) {
                    val jx = ix + ox; val jy = iy + oy
                    if (jx in 0 until w && jy in 0 until h && out[jy * w + jx] < v) out[jy * w + jx] = v
                }
            }
        }
        return out
    }

    /** The damage on the map: everything that needs neighbours, over the whole print (paper margin included). */
    internal class Layers(val w: Int, val h: Int, val pw: Int, val ph: Int, val b: Int,
                          val fadeK: FloatArray, val stain: FloatArray, val m: FloatArray, val add: FloatArray,
                          val silver: FloatArray, val mulB: FloatArray, val d: FloatArray, val s: FloatArray, val alpha: FloatArray)

    /** The map's photo size for a [w] × [h] print. */
    internal fun mapSize(w: Int, h: Int): Pair<Int, Int> =
        if (w >= h) MAP_LONG to max(8, (h.toDouble() * MAP_LONG / w).roundToInt()) else max(8, (w.toDouble() * MAP_LONG / h).roundToInt()) to MAP_LONG

    /**
     * The damage for a map photo [pw] × [ph], as `agep.layers`. [back]: the writing on the back, mirrored and blurred,
     * over the whole map (shoebox only; null for none).
     */
    internal fun layers(story: String, pw: Int, ph: Int, fade: Float, wear: Float, seed: Long, back: FloatArray? = null): Layers {
        val sc = max(pw, ph) / 640.0
        val b = Math.rint(BORDER.getValue(story) * sc).toInt()
        val w = pw + 2 * b; val h = ph + 2 * b; val n = w * h
        val fadeK = FloatArray(n) { 1f }; val stain = FloatArray(n); val m = FloatArray(n); val add = FloatArray(n)
        val silver = FloatArray(n); val mulB = FloatArray(n) { 1f }
        val d = FloatArray(n); val s = FloatArray(n)
        val sd = seed * 16
        val scF = sc.toFloat()
        val alpha: FloatArray
        when (story) {
            "fridge" -> {
                // the window side fades more; the edges lift off the door. 97b: no magnet square on a print alone (with no
                // magnet in the picture it read as a fault); the fridge collage puts it back under its magnet.
                for (y in 0 until ph) for (x in 0 until pw) {
                    fadeK[(y + b) * w + x + b] = (1.0 - 0.4 * x / max(1, pw - 1)).toFloat()
                }
                val nz = noise(w, h, 1.0 * sc, sd + 1)
                val height = FloatArray(n) { i -> exp(-edgeDist(i % w, i / w, w, h) / (14 * scF)) * 5 * scF * wear + nz[i] * 0.05f * scF }
                light(height, w, h, 1.0f, 0.05f, d, s)
                alpha = corners(w, h, Math.rint(6 * sc).toInt(), 0f, sd + 2, sc)
            }
            "album" -> {
                // dark fading, the paper yellowing, and glue at the photo corners
                val nz = noise(pw, ph, 60 * sc, sd + 3)
                for (y in 0 until ph) for (x in 0 until pw) stain[(y + b) * w + x + b] = (0.10f + 0.04f * nz[y * pw + x]) * fade
                for (y in 0 until h) for (x in 0 until w) {
                    if (min(y, h - 1 - y) + min(x, w - 1 - x) < 70 * sc) m[y * w + x] = wear
                }
                val tex = noise(w, h, 1.0 * sc, sd + 4)
                val height = FloatArray(n) { tex[it] * 0.04f * scF }
                light(height, w, h, 1.0f, 0.04f, d, s)
                alpha = corners(w, h, Math.rint(4 * sc).toInt(), 0f, sd + 5, sc)
            }
            "wallet" -> {
                // folded in half and in half again: a ridge in the paper, and the emulsion cracked white along it; scuffs
                val st = Stream(sd + 6)
                for (y in 0 until ph) for (x in 0 until pw) stain[(y + b) * w + x + b] = 0.05f * fade
                val wx = noise(1, h, 25 * sc, sd + 7); val wy = noise(w, 1, 25 * sc, sd + 8)
                val fold = min(wear * 1.5f, 1f)
                val cw = max(wear - 0.25f, 0f) / 0.75f
                val n1 = noise(w, h, 1.5 * sc, sd + 9); val n2 = noise(w, h, 1.5 * sc, sd + 10); val n3 = noise(w, h, 3 * sc, sd + 11)
                val height = FloatArray(n)
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val dx = abs(x - (w / 2 + wx[y] * 2f * scF)); val dy = abs(y - (h / 2 + 6 * scF + wy[x] * 2f * scF))
                    height[i] = (6 * exp(-dx / (5 * scF)) + 4 * exp(-dy / (5 * scF))) * scF * fold
                    val crack = (dx < (1.6f + 1.2f * max(n1[i], 0f)) * cw * scF || dy < (1.2f + 1.0f * max(n2[i], 0f)) * cw * scF) && n3[i] > 1.4f - 2f * cw
                    if (crack) { m[i] = 1f; add[i] = 0.72f }
                }
                val scuff = scratches(w, h, (140 * wear.toDouble()).toInt(), sc, st)
                val tex = noise(w, h, 1.0 * sc, sd + 12)
                for (i in 0 until n) { add[i] += scuff[i] * 0.18f; height[i] += scuff[i] * 0.6f * scF + tex[i] * 0.08f * scF }
                light(height, w, h, 1.2f, 0.25f * max(wear, 0.3f), d, s)
                alpha = corners(w, h, Math.rint((4 + 18 * wear.toDouble()) * sc).toInt(), wear, sd + 13, sc)
            }
            else -> {
                // a strong warm fade and stain, brown foxing spots, silvering creeping in from the edges, the back's writing
                val st = Stream(sd + 14)
                val nz = noise(pw, ph, 80 * sc, sd + 15)
                for (y in 0 until ph) for (x in 0 until pw) stain[(y + b) * w + x + b] = (0.18f + 0.06f * nz[y * pw + x]) * fade
                val spots = m
                repeat(Math.rint(9 * wear.toDouble()).toInt()) {
                    val cy = st.uniform(0.0, h.toDouble()); val cx = st.uniform(0.0, w.toDouble())
                    repeat(st.integers(6, 20)) {
                        val y = cy + st.normal(0.0, 40 * sc); val x = cx + st.normal(0.0, 40 * sc)
                        val r = st.uniform(1.5, 7.0) * sc; val a = st.uniform(0.4, 1.0)
                        val rr = ceil(4 * r).toInt()
                        val ya = max(0, y.toInt() - rr); val yb = min(h, y.toInt() + rr + 1); val xa = max(0, x.toInt() - rr); val xb = min(w, x.toInt() + rr + 1)
                        for (yy in ya until yb) for (xx in xa until xb) {
                            spots[yy * w + xx] += (exp(-((yy - y) * (yy - y) + (xx - x) * (xx - x)) / (2 * r * r)) * a).toFloat()
                        }
                    }
                }
                for (i in 0 until n) spots[i] = spots[i].coerceIn(0f, 1f)
                val sn = noise(w, h, 12 * sc, sd + 16)
                val reach = 60 * scF * max(wear, 1e-3f)
                for (i in 0 until n) silver[i] = (1 - (edgeDist(i % w, i / w, w, h) + 14 * scF * sn[i]) / reach).coerceIn(0f, 1f) * min(wear, 1f)
                if (back != null) for (i in 0 until n) mulB[i] = 1 - back[i] * 0.22f * fade
                val tex = noise(w, h, 1.0 * sc, sd + 17)
                val height = FloatArray(n) { tex[it] * 0.05f * scF + spots[it] * 0.2f * scF }
                light(height, w, h, 1.0f, 0.06f, d, s)
                alpha = corners(w, h, Math.rint((4 + 6 * wear.toDouble()) * sc).toInt(), 0.4f * wear, sd + 18, sc)
            }
        }
        return Layers(w, h, pw, ph, b, fadeK, stain, m, add, silver, mulB, d, s, alpha)
    }

    /**
     * Rows [y0, y1) of the aged print, [ow] × [oh] with the photo at [bo]: [photoRow] fills a row of the photo
     * (sRGB encoded, three floats a pixel), [emit] takes a finished row (sRGB encoded). The map's layers are sampled
     * bilinearly, so at the map's own size this is `agep.finish` exactly.
     */
    internal fun rows(paper: Paper, L: Layers, story: String, fade: Float, ow: Int, oh: Int, bo: Int, pwOut: Int, phOut: Int,
                      y0: Int, y1: Int, photoRow: (Int, FloatArray) -> Unit, emit: (Int, FloatArray) -> Unit) {
        val fv = FADE.getValue(story); val mk = MULK.getValue(story)
        val kx = L.w.toFloat() / ow; val ky = L.h.toFloat() / oh
        val src = FloatArray(pwOut * 3); val out = FloatArray(ow * 3)
        val c = FloatArray(3); val rgb = FloatArray(3)
        for (y in y0 until y1) {
            val sy = y - bo
            val inRow = sy in 0 until phOut
            if (inRow) photoRow(sy, src)
            val my = ((y + 0.5f) * ky - 0.5f).coerceIn(0f, L.h - 1f)
            val j0 = min(my.toInt(), L.h - 1); val j1 = min(j0 + 1, L.h - 1); val fy = my - j0
            for (x in 0 until ow) {
                val mx = ((x + 0.5f) * kx - 0.5f).coerceIn(0f, L.w - 1f)
                val i0 = min(mx.toInt(), L.w - 1); val i1 = min(i0 + 1, L.w - 1); val fx = mx - i0
                val a00 = j0 * L.w + i0; val a01 = j0 * L.w + i1; val a10 = j1 * L.w + i0; val a11 = j1 * L.w + i1
                fun at(f: FloatArray): Float {
                    val t = f[a00] + (f[a01] - f[a00]) * fx; val u = f[a10] + (f[a11] - f[a10]) * fx
                    return t + (u - t) * fy
                }
                val sx = x - bo
                if (inRow && sx in 0 until pwOut) {
                    paper.dyes(src[sx * 3], src[sx * 3 + 1], src[sx * 3 + 2], c)
                    val k = at(L.fadeK) * fade
                    for (q in 0..2) c[q] *= 1 - k * fv[q]
                    paper.render(c, at(L.stain), rgb)
                } else { rgb[0] = PAPER[0]; rgb[1] = PAPER[1]; rgb[2] = PAPER[2] }
                val mm = at(L.m); val ad = at(L.add)
                for (q in 0..2) rgb[q] = rgb[q] * (1 - mm * mk[q]) + ad
                val sv = at(L.silver)
                if (sv > 0f) { val g = (1 - (rgb[0] + rgb[1] + rgb[2]) / 3f * 2f).coerceIn(0f, 1f) * sv; for (q in 0..2) rgb[q] += g * SILVER[q] }
                val mb = at(L.mulB); val dd = at(L.d); val ss = at(L.s); val al = at(L.alpha)
                for (q in 0..2) {
                    val v = ((rgb[q] * mb).coerceIn(0f, 1f) * dd + ss).coerceIn(0f, 1f)
                    out[x * 3 + q] = v * al + BG[q] * (1 - al)
                }
            }
            emit(y, out)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // In the app
    // ---------------------------------------------------------------------------------------------------------------

    /** The same marks on every print of one photo, different from photo to photo. */
    fun seedOf(photo: Uri): Long = (photo.toString().hashCode().toLong() and 0xffffffffL) % 100003L + 1

    /** What is written on the back of the shoebox print: the month it was taken, and the film. */
    fun backWords(context: Context, photo: Uri, film: String): List<String> {
        val taken = runCatching {
            context.contentResolver.query(photo, arrayOf(android.provider.MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val month = java.text.SimpleDateFormat("MMMM yyyy", Locale.US).format(java.util.Date(taken))
        val stock = Develop.FILMS.firstOrNull { it.first == film }?.second ?: ""
        return listOfNotNull(month, stock.takeIf { it.isNotEmpty() }?.let { "- $it" })
    }

    /** The writing on the back as it shows through: drawn on the map, blurred, mirrored. */
    private fun backMask(w: Int, h: Int, sc: Double, words: List<String>): FloatArray? {
        if (words.isEmpty()) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        try {
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textSize = (w * 0.075f)
            }
            val fm = p.fontMetrics
            words.take(2).forEachIndexed { i, t ->
                cv.drawText(t, w * (if (i == 0) 0.16f else 0.32f), h * (if (i == 0) 0.62f else 0.72f) - fm.ascent, p)
            }
            val bytes = ByteBuffer.allocate(bmp.rowBytes * h); bmp.copyPixelsToBuffer(bytes)
            val a = bytes.array()
            val raw = FloatArray(w * h) { i -> (a[(i / w) * bmp.rowBytes + i % w].toInt() and 255) / 255f }
            val bl = blur(raw, w, h, 2.2 * sc)
            return FloatArray(w * h) { i -> bl[(i / w) * w + (w - 1 - i % w)] }
        } finally { bmp.recycle() }
    }

    private fun layersFor(look: AgeLook, w: Int, h: Int, seed: Long, words: List<String>): Layers {
        val (pw, ph) = mapSize(w, h)
        val sc = max(pw, ph) / 640.0
        val b = Math.rint(BORDER.getValue(look.story) * sc).toInt()
        val back = if (look.story == "shoebox") backMask(pw + 2 * b, ph + 2 * b, sc, words) else null
        return layers(look.story, pw, ph, look.fade, look.wear, seed, back)
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }

    /**
     * The print in [data] ([w] × [h], three floats a pixel in [cs]) aged, in its margin of paper. Returns a new
     * buffer (native when big, as [Develop.pictureBuffer] gives) and its size.
     */
    fun apply(context: Context, data: ByteBuffer, w: Int, h: Int, cs: ColorSpace, look: AgeLook, seed: Long, words: List<String>): Triple<ByteBuffer, Int, Int> {
        val paper = paper(context)
        val L = layersFor(look, w, h, seed, words)
        val bo = border(w, h, look.story)
        val ow = w + 2 * bo; val oh = h + 2 * bo
        val out = Develop.pictureBuffer(ow.toLong() * oh * 12L)
        val same = cs == ColorSpace.SRGB
        val toS = mul(Saturation.fromLms(ColorSpace.SRGB), Saturation.toLms(cs))
        val fromS = mul(Saturation.fromLms(cs), Saturation.toLms(ColorSpace.SRGB))
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(oh)
        val workers = (0 until bands).map { band ->
            Thread {
                val src = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                val dst = out.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                val lin = FloatArray(3)
                rows(paper, L, look.story, look.fade, ow, oh, bo, w, h, oh * band / bands, oh * (band + 1) / bands,
                    photoRow = { sy, row ->
                        src.position(sy * w * 3); src.get(row)
                        if (!same) for (x in 0 until w) {
                            val o = x * 3
                            for (q in 0..2) lin[q] = Saturation.decode(cs, row[o + q])
                            for (q in 0..2) row[o + q] = srgbEncode((toS[q * 3] * lin[0] + toS[q * 3 + 1] * lin[1] + toS[q * 3 + 2] * lin[2]).coerceIn(0f, 1f))
                        }
                    },
                    emit = { y, row ->
                        if (!same) for (x in 0 until ow) {
                            val o = x * 3
                            for (q in 0..2) lin[q] = Saturation.decode(ColorSpace.SRGB, row[o + q])
                            for (q in 0..2) row[o + q] = Saturation.encode(cs, (fromS[q * 3] * lin[0] + fromS[q * 3 + 1] * lin[1] + fromS[q * 3 + 2] * lin[2]).coerceIn(0f, 1f))
                        }
                        synchronized(dst) { dst.position(y * ow * 3); dst.put(row) }
                    })
            }.apply { start() }
        }
        workers.forEach { it.join() }
        return Triple(out, ow, oh)
    }

    /** An aged copy of [picture] (an sRGB bitmap) at most [longEdge] px, for the AGE step to show. */
    fun sample(context: Context, picture: Bitmap, look: AgeLook, seed: Long, words: List<String>, longEdge: Int = 1080): Bitmap? = runCatching {
        if (!look.on) return@runCatching null
        val sc = min(1f, longEdge / max(picture.width, picture.height).toFloat())
        val sw = max(8, (picture.width * sc).roundToInt()); val sh = max(8, (picture.height * sc).roundToInt())
        val small = Bitmap.createScaledBitmap(picture, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== picture) small.recycle()
        val paper = paper(context)
        val L = layersFor(look, sw, sh, seed, words)
        val bo = border(sw, sh, look.story)
        val ow = sw + 2 * bo; val oh = sh + 2 * bo
        val pix = IntArray(ow * oh)
        rows(paper, L, look.story, look.fade, ow, oh, bo, sw, sh, 0, oh,
            photoRow = { sy, row -> for (x in 0 until sw) { val p = px[sy * sw + x]; row[x * 3] = ((p shr 16) and 255) / 255f; row[x * 3 + 1] = ((p shr 8) and 255) / 255f; row[x * 3 + 2] = (p and 255) / 255f } },
            emit = { y, row ->
                for (x in 0 until ow) {
                    val r = (row[x * 3] * 255f + 0.5f).toInt(); val g = (row[x * 3 + 1] * 255f + 0.5f).toInt(); val b = (row[x * 3 + 2] * 255f + 0.5f).toInt()
                    pix[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            })
        Bitmap.createBitmap(pix, ow, oh, Bitmap.Config.ARGB_8888)
    }.onFailure { android.util.Log.e("Latent", "age: sample failed", it) }.getOrNull()
}
