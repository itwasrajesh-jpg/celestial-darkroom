package com.celestial.latent.develop

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Air in references from outside the app — mist, haze, a cloud sea — found so the Film Builder can
 * look past it: the film is measured only where the air is clear. Without this a misty landscape's
 * lifted, greyed distance is blamed on the film, and the search builds a faded film to match.
 *
 * Proven before it was written, on two misty mountain photos:
 *  - Film fade and air are told apart: a fade is a floor that lifts everything equally, so nothing
 *    is darker than it; air only adds above it. A fade added at a known amount — even a warm,
 *    coloured one — was found within 0.002, channel by channel, while the air reading did not move.
 *  - The air is found point by point (the dark channel: the darkest colour in a small
 *    neighbourhood shows how much air lies in front of it), fog banks included.
 *  - Removing the air made garish pictures (clouds, which are air, turned cyan) — so it is not
 *    removed; those parts are left out of the measurement, as figures that cannot be judged are.
 * It needs no depth: the depth model proved the separation, but finding the air works from the
 * picture alone.
 */
object Haze {
    /**
     * [clear]: one flag per fingerprint sample (the same grid and order as [Fingerprint.of]),
     * true where the air is clear enough to measure the film from. [clearShare]: how much of the
     * picture that is. [airShare]: how much of it has real air in front.
     */
    class Reading(val clear: BooleanArray, val clearShare: Float, val airShare: Float)

    /** The air in a picture; null when there is little (under a tenth of the picture) or too much to measure past. */
    fun read(bmp: Bitmap): Reading? {
        // the fingerprint's own grid: every step-th pixel, row by row
        val step = max(1, max(bmp.width, bmp.height) / 320)
        val gw = (bmp.width + step - 1) / step; val gh = (bmp.height + step - 1) / step
        val n = gw * gh
        val lin = FloatArray(n * 3)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val p = bmp.getPixel(min(gx * step, bmp.width - 1), min(gy * step, bmp.height - 1))
            val i = (gy * gw + gx) * 3
            lin[i] = dec(((p shr 16) and 0xFF) / 255f); lin[i + 1] = dec(((p shr 8) and 0xFF) / 255f); lin[i + 2] = dec((p and 0xFF) / 255f)
        }
        // the film's fade: the floor nothing goes below (per channel) — kept, never treated as air
        val fade = FloatArray(3) { c -> FloatArray(n) { lin[it * 3 + c] }.also { it.sort() }[((n - 1) * 0.005f).toInt()] }
        val I = FloatArray(n * 3) { k -> val c = k % 3; ((lin[k] - fade[c]) / max(1f - fade[c], 1e-3f)).coerceAtLeast(0f) }
        val patch = max(7, gw / 60).let { if (it % 2 == 0) it + 1 else it }
        // the air's colour: where it is thickest
        val dark = minFilter(FloatArray(n) { min(I[it * 3], min(I[it * 3 + 1], I[it * 3 + 2])) }, gw, gh, patch)
        val cut = dark.copyOf().also { it.sort() }[((n - 1) * 0.999f).toInt()]
        val air = FloatArray(3); var an = 0
        for (i in 0 until n) if (dark[i] >= cut) { air[0] += I[i * 3]; air[1] += I[i * 3 + 1]; air[2] += I[i * 3 + 2]; an++ }
        for (c in 0 until 3) air[c] = max(air[c] / max(an, 1), 0.05f)
        // how much of the scene shows through the air, point by point, its edges kept on the scene's edges
        val rough = minFilter(FloatArray(n) { i -> min(I[i * 3] / air[0], min(I[i * 3 + 1] / air[1], I[i * 3 + 2] / air[2])) }, gw, gh, patch)
        val through0 = FloatArray(n) { 1f - 0.95f * rough[it] }
        val guide = FloatArray(n) { sqrt(max(0f, 0.2126f * I[it * 3] + 0.7152f * I[it * 3 + 1] + 0.0722f * I[it * 3 + 2])) }
        val through = guided(through0, guide, gw, gh, max(4, gw / 80), 1e-3f)
        // Measured where the air is clear: full weight from 90% showing through, none below 65% —
        // a little stricter than the proof, so the faint blue of far ridges does not count.
        val clear = BooleanArray(n); var nc = 0; var na = 0
        for (i in 0 until n) {
            val w = ((through[i] - 0.65f) / 0.25f).coerceIn(0f, 1f).pow(2)
            if (w > 0.5f) { clear[i] = true; nc++ }
            if (through[i] < 0.8f) na++
        }
        val clearShare = nc.toFloat() / n; val airShare = na.toFloat() / n
        if (airShare < 0.10f || clearShare < 0.15f) return null          // little air, or too little clear to measure
        return Reading(clear, clearShare, airShare)
    }

    private fun dec(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun minFilter(a: FloatArray, w: Int, h: Int, k: Int): FloatArray {
        val r = k / 2; val t = FloatArray(w * h); val o = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var m = a[y * w + x]; for (d in -r..r) m = min(m, a[y * w + (x + d).coerceIn(0, w - 1)]); t[y * w + x] = m }
        for (y in 0 until h) for (x in 0 until w) { var m = t[y * w + x]; for (d in -r..r) m = min(m, t[(y + d).coerceIn(0, h - 1) * w + x]); o[y * w + x] = m }
        return o
    }

    private fun box(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h); val o = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; var c = 0; for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += a[y * w + xx]; c++ } }; t[y * w + x] = s / c }
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; var c = 0; for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += t[yy * w + x]; c++ } }; o[y * w + x] = s / c }
        return o
    }

    /** The guided filter (as in the proof): smooths [p] while keeping the edges of [g]. */
    private fun guided(p: FloatArray, g: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val mg = box(g, w, h, r); val mp = box(p, w, h, r)
        val gp = box(FloatArray(n) { g[it] * p[it] }, w, h, r); val gg = box(FloatArray(n) { g[it] * g[it] }, w, h, r)
        val a = FloatArray(n) { (gp[it] - mg[it] * mp[it]) / (gg[it] - mg[it] * mg[it] + eps) }
        val b = FloatArray(n) { mp[it] - a[it] * mg[it] }
        val ma = box(a, w, h, r); val mb = box(b, w, h, r)
        return FloatArray(n) { (ma[it] * g[it] + mb[it]).coerceIn(0f, 1f) }
    }
}
