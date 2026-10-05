package com.celestial.latent.develop

import android.graphics.Bitmap
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How sharp a picture looks: the width of its sharpest clean edges, and whether they carry the
 * paired bright-and-dark fringes that sharpening leaves. Proven in Python before it was written:
 * on four scenes, the width grew steadily with known blur and narrowed with sharpening, grain
 * barely moved it, and a backlit cat's rim of light was not mistaken for a halo.
 *
 *  - Only clean edges count: a strong step, flat and even on both sides (a branch against sky).
 *  - Of those, the sharpest 30% — they show the lens, film and scan, not an out-of-focus background.
 *  - Width: the 10–90% rise across the edge. A sharpened soft edge and a naturally crisp one look
 *    alike and cannot be told apart, so this measures what is seen, not how it was made.
 *  - Halo: the SMALLER of the bright-side and dark-side fringes — sharpening always makes both,
 *    equally; a rim of light makes one.
 *
 * Measured close up (a full-resolution piece of the picture), since film's gentle softness is
 * finer than a shrunken copy can show, and given as a fraction of the frame's long side.
 */
object Softness {
    class Reading(val width: Float, val halo: Float, val edges: Int)

    /** From a picture: its display luminance, measured as it is. */
    fun of(bmp: Bitmap): Reading? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val L = FloatArray(w * h) { i -> val p = px[i]; (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f }
        return measure(L, w, h)
    }

    /** From a develop source (linear ProPhoto): its middle, at most [side] pixels square, as a display would show it. */
    fun of(src: Develop.Source, side: Int = 768): Reading? {
        val w = min(side, src.width); val h = min(side, src.height)
        val x0 = (src.width - w) / 2; val y0 = (src.height - h) / 2
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val L = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = ((y + y0) * src.width + x + x0) * 3
            val lin = 0.2880f * f.get(o) + 0.7119f * f.get(o + 1) + 0.0001f * f.get(o + 2)
            L[y * w + x] = max(0f, lin).pow(1f / 2.2f).coerceAtMost(1f)
        }
        return measure(L, w, h)
    }

    fun measure(L: FloatArray, w: Int, h: Int, half: Float = 7f, stepT: Float = 0.25f, keep: Float = 0.3f): Reading? {
        if (w < 40 || h < 40) return null
        // edge strength and direction (Sobel)
        val gx = FloatArray(w * h); val gy = FloatArray(w * h); val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun at(dx: Int, dy: Int) = L[(y + dy) * w + x + dx]
            val sx = (at(1, -1) + 2 * at(1, 0) + at(1, 1)) - (at(-1, -1) + 2 * at(-1, 0) + at(-1, 1))
            val sy = (at(-1, 1) + 2 * at(0, 1) + at(1, 1)) - (at(-1, -1) + 2 * at(0, -1) + at(1, -1))
            val i = y * w + x; gx[i] = sx; gy[i] = sy; mag[i] = hypot(sx, sy)
        }
        val cut = mag.copyOf().also { it.sort() }[((w * h - 1) * 0.985f).toInt()]
        if (cut <= 0f) return null
        val n = ((2 * half) / stepT).toInt() + 1
        val t = FloatArray(n) { -half + it * stepT }; val mid = n / 2
        val out = ArrayList<FloatArray>()
        val rng = java.util.Random(0)
        val margin = 12
        val candidates = ArrayList<Int>()
        for (y in margin until h - margin) for (x in margin until w - margin) if (mag[y * w + x] > cut) candidates += y * w + x
        val pick = if (candidates.size > 8000) candidates.shuffled(rng).take(8000) else candidates
        val p = FloatArray(n)
        for (i in pick) {
            val y = i / w; val x = i % w; val nx = gx[i] / mag[i]; val ny = gy[i] / mag[i]
            for (k in 0 until n) p[k] = bilinear(L, w, h, x + t[k] * nx, y + t[k] * ny)
            // a clean step: flat and even on both sides
            var sa = 0f; var sb = 0f; var na = 0; var nb = 0
            for (k in 0 until n) { if (t[k] <= -3.5f) { sa += p[k]; na++ } else if (t[k] >= 3.5f) { sb += p[k]; nb++ } }
            val lo = sa / na; val hi = sb / nb; val c = hi - lo
            if (c < 0.12f) continue
            var va = 0f; var vb = 0f
            for (k in 0 until n) { if (t[k] <= -3.5f) va += (p[k] - lo) * (p[k] - lo) else if (t[k] >= 3.5f) vb += (p[k] - hi) * (p[k] - hi) }
            if (kotlin.math.sqrt(va / na) > 0.04f * c + 0.006f || kotlin.math.sqrt(vb / nb) > 0.04f * c + 0.006f) continue
            val q = FloatArray(n) { (p[it] - lo) / c }
            // line it up at its midpoint (the 50% crossing nearest the centre)
            var best = -1; var bestD = Int.MAX_VALUE
            for (k in 0 until n - 1) if (q[k] < 0.5f && q[k + 1] >= 0.5f && abs(k - mid) < bestD) { best = k; bestD = abs(k - mid) }
            if (best < 0) continue
            val t0 = t[best] + (0.5f - q[best]) / (q[best + 1] - q[best]) * stepT
            val qa = FloatArray(n) { interp(t[it] + t0, t, q) }
            // the 10% point before the middle, the 90% point after it (rising only)
            var lo10 = t[0]; for (k in mid downTo 1) if (qa[k - 1] < 0.1f && qa[k] >= 0.1f) { lo10 = t[k - 1] + (0.1f - qa[k - 1]) / (qa[k] - qa[k - 1]) * stepT; break }
            var run = qa[mid]; var hi90 = t[n - 1]
            for (k in mid + 1 until n) { val prev = run; run = max(run, qa[k]); if (prev < 0.9f && run >= 0.9f) { hi90 = t[k - 1] + (0.9f - prev) / (run - prev) * stepT; break } }
            var over = 0f; var under = 0f
            for (k in 0 until n) { if (t[k] > 0f && t[k] <= 3f) over = max(over, qa[k] - 1f); if (t[k] < 0f && t[k] >= -3f) under = max(under, -qa[k]) }
            out += floatArrayOf(hi90 - lo10, min(over, under))
        }
        if (out.size < 10) return null
        out.sortBy { it[0] }
        val sharp = out.take(max(10, (out.size * keep).toInt()))
        return Reading(median(sharp.map { it[0] }), median(sharp.map { it[1] }), out.size)
    }

    private fun median(v: List<Float>): Float = v.sorted()[v.size / 2]
    private fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
        if (x <= xs[0]) return ys[0]; if (x >= xs[xs.size - 1]) return ys[ys.size - 1]
        val step = xs[1] - xs[0]; val k = ((x - xs[0]) / step).toInt().coerceIn(0, xs.size - 2); val f = (x - xs[k]) / step
        return ys[k] * (1 - f) + ys[k + 1] * f
    }
    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1); val fx = xc - x0; val fy = yc - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }

    /**
     * The lens blur that makes the test shot as soft as the references: the difference of the two
     * edge spreads, as a Gaussian on the film (the engine's lens blur is exactly that: a Gaussian
     * of sigma = lens_blur_um / pixel size). A 10–90% rise is 2.563 sigma. Widths are fractions of
     * the frame's long side; the film's long side is [filmMm]. Zero when the references are crisper.
     */
    fun matchingBlurUm(refWidth: Float, testWidth: Float, filmMm: Float): Float {
        if (refWidth.isNaN() || testWidth.isNaN()) return 0f
        val sr = refWidth / 2.563f; val st = testWidth / 2.563f
        if (sr <= st * 1.05f) return 0f
        return (kotlin.math.sqrt(sr * sr - st * st) * filmMm * 1000f).coerceAtMost(60f)
    }
}
