package com.celestial.latent.develop

import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Brightness noise, cleaned with an edge-keeping (guided) filter — the sensor's digital speckle,
 * which film never sees. Within each small area the brightness is explained as a smooth function
 * of itself, so real edges and texture survive while random speckle averages away. It works on
 * a perceptual scale (the square root of linear light), so dark areas are cleaned as carefully as
 * bright ones; colour is untouched (each pixel keeps its own colour ratios).
 *
 * Two uses, one filter:
 *  - the sensor noise cleaner before the film (amount from the recipe, automatic by ISO);
 *  - the cleaned copy a light adds its light to, so the photo's noise is never multiplied
 *    (measured on the chandelier: lit ceiling speckle 1.5–1.8× → 0.8× of the original).
 *
 * Memory: it works in horizontal bands, so its working arrays are a few megabytes whatever the
 * photo's size. The answer is one factor per pixel (the cleaned brightness over the original).
 */
object GuidedClean {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    private const val BAND = 192

    /** The automatic strength for an ISO: nothing at low ISO, gently rising — film grain stays the texture. */
    fun strengthForIso(iso: Int): Float = when {
        iso <= 200 -> 0f
        iso <= 400 -> 0.15f
        iso <= 800 -> 0.3f
        iso <= 1600 -> 0.45f
        iso <= 3200 -> 0.6f
        else -> 0.75f
    }

    /**
     * For each pixel, the factor that takes its brightness to the cleaned brightness (linear
     * light): multiply its RGB by it. [amount] 0 … 1 blends from untouched to fully cleaned; the
     * filter itself also grows gentler-to-stronger with it, as tested.
     */
    fun factors(f: FloatBuffer, w: Int, h: Int, amount: Float): FloatArray {
        val out = FloatArray(w * h) { 1f }
        if (amount <= 0f) return out
        bands(f, w, h, amount) { y0, band -> band.copyInto(out, y0 * w) }
        return out
    }

    /**
     * The factors one band of rows at a time (81b): [emit] gets the band's first row and its factors,
     * row by row. Each band reads the picture's rows just above and below it, so a caller that changes
     * the picture must hold a band back until the next one has been worked out ([applyInPlace]).
     */
    private fun bands(f: FloatBuffer, w: Int, h: Int, amount: Float, emit: (Int, FloatArray) -> Unit) {
        val r = max(1, (2f * min(w, h) / 960f).roundToInt())         // 2 px at a 1280-wide picture, as tested
        val e = 0.004f + 0.012f * amount; val eps = e * e
        // the perceptual brightness of one row (clamped at the picture's edges)
        fun sRow(y: Int, dst: FloatArray) {
            val yy = y.coerceIn(0, h - 1); val o = yy * w * 3
            for (x in 0 until w) {
                val l = LUM[0] * f.get(o + x * 3) + LUM[1] * f.get(o + x * 3 + 1) + LUM[2] * f.get(o + x * 3 + 2)
                dst[x] = sqrt(max(l, 0f))
            }
        }
        // a horizontal box average of a row, the window shrinking at the edges
        fun hbox(src: FloatArray, dst: FloatArray, pre: DoubleArray) {
            pre[0] = 0.0; for (x in 0 until w) pre[x + 1] = pre[x] + src[x]
            for (x in 0 until w) { val a = max(0, x - r); val b = min(w, x + r + 1); dst[x] = ((pre[b] - pre[a]) / (b - a)).toFloat() }
        }
        val pre = DoubleArray(w + 1)
        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + BAND)
            // rows needed: the output band, plus r for the second average, plus r more for the first
            val a0 = y0 - 2 * r; val a1 = y1 + 2 * r                  // s rows (clamped when read)
            val n = a1 - a0
            val s = Array(n) { FloatArray(w) }; val hs = Array(n) { FloatArray(w) }; val hss = Array(n) { FloatArray(w) }
            val tmp = FloatArray(w)
            for (k in 0 until n) {
                sRow(a0 + k, s[k])
                hbox(s[k], hs[k], pre)
                for (x in 0 until w) tmp[x] = s[k][x] * s[k][x]
                hbox(tmp, hss[k], pre)
            }
            // first average (vertical) → a, b for rows y0-r … y1+r
            val m = (y1 + r) - (y0 - r)
            val A = Array(m) { FloatArray(w) }; val B = Array(m) { FloatArray(w) }
            for (j in 0 until m) {
                val y = y0 - r + j
                val lo = max(0, y - r); val hi = min(h - 1, y + r); val cnt = (hi - lo + 1).toFloat()
                for (x in 0 until w) {
                    var ms = 0f; var mss = 0f
                    for (yy in lo..hi) { val k = yy - a0; ms += hs[k][x]; mss += hss[k][x] }
                    ms /= cnt; mss /= cnt
                    val v = max(mss - ms * ms, 0f)
                    val a = v / (v + eps)
                    A[j][x] = a; B[j][x] = ms - a * ms
                }
            }
            // second average of a and b, and the cleaned brightness for the output band
            val band = FloatArray((y1 - y0) * w)
            val ha = FloatArray(w); val hb = FloatArray(w)
            val HA = Array(m) { FloatArray(w) }; val HB = Array(m) { FloatArray(w) }
            for (j in 0 until m) { hbox(A[j], ha, pre); hbox(B[j], hb, pre); ha.copyInto(HA[j]); hb.copyInto(HB[j]) }
            for (y in y0 until y1) {
                val lo = max(0, y - r); val hi = min(h - 1, y + r); val cnt = (hi - lo + 1).toFloat()
                val ks = y - a0
                for (x in 0 until w) {
                    var ma = 0f; var mb = 0f
                    for (yy in lo..hi) { val j = yy - (y0 - r); ma += HA[j][x]; mb += HB[j][x] }
                    ma /= cnt; mb /= cnt
                    val s0 = s[ks][x]
                    val sc = ma * s0 + mb
                    val mix = s0 + (sc - s0) * amount
                    band[(y - y0) * w + x] = if (s0 > 1e-4f) ((mix / s0) * (mix / s0)).coerceIn(0f, 4f) else 1f
                }
            }
            emit(y0, band)
            y0 = y1
        }
    }

    /** Cleans the picture's brightness noise in place: each pixel's RGB times its factor. */
    fun applyInPlace(f: FloatBuffer, w: Int, h: Int, amount: Float) {
        if (amount <= 0f) return
        // 81b: a band at a time, written once the next band has read the rows it needs, so the
        // filter sees the untouched picture everywhere and only two bands of factors are ever kept
        // (whole, they were a photo-sized array: 200 MB at 50 MP).
        var pending: FloatArray? = null; var pendingY0 = 0
        fun write(y0: Int, k: FloatArray) {
            for (j in k.indices) {
                val o = (y0 * w + j) * 3; val m = k[j]
                if (m != 1f) { f.put(o, f.get(o) * m); f.put(o + 1, f.get(o + 1) * m); f.put(o + 2, f.get(o + 2) * m) }
            }
        }
        bands(f, w, h, amount) { y0, band -> pending?.let { write(pendingY0, it) }; pending = band; pendingY0 = y0 }
        pending?.let { write(pendingY0, it) }
    }
}
