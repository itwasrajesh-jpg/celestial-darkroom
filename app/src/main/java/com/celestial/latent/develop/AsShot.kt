package com.celestial.latent.develop

import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 115a: REST OF THE PHOTO · AS SHOT (LAYERS). With it on, only the layers that have a NEGATIVE look of
 * their own get film; everything else stays the photo exactly as shot, the same as Before and NO FILM.
 * Celestial, 4 Nov: "if I tap NO FILM the look is removed; a preset on the subject must leave the
 * background as shot". FILM (off) keeps the way it always was: the rest gets the whole photo's film.
 * Approved proof: layers/no-film-rest/ (Celestial's own photos, the real engine).
 *
 * How: the photo is prepared once more as it was shot ([plain]: no noise cleaning, no LIGHT tab, no
 * filters; a RAW read by Android's own decoder, the one Before is drawn with, see [Develop.openAsShot]),
 * written into the print in the print's own colours ([encodeInto]), and the layers' own prints are
 * blended over it by their areas as before ([LayerLooks.blend]). What PRINT paints before the film
 * (fog, light, wet) and the layers' own exposure, warmth and colour stay on it, like on the film.
 */
object AsShot {

    /** The settings the as-shot base is prepared with: nothing the film's settings do before the film. */
    fun plain(r: Recipe): Recipe = r.copy(
        chromaDenoise = 0f, lumaDenoise = 0f, nightClean = false, lens85b = false,
        diffusion = false, printDiffusion = false, fastDiffusion = false,
        lightHighlights = 0f, lightShadows = 0f, lightWhites = 0f, lightBlacks = 0f,
    )

    /**
     * The app's working light is linear ProPhoto made from sRGB by [Develop.openImage]'s matrix; this
     * is that matrix's exact inverse, so a JPEG comes back to its own pixels.
     */
    private val TO_SRGB: FloatArray = invert(floatArrayOf(
        0.5294f, 0.3300f, 0.1406f,
        0.0983f, 0.8735f, 0.0282f,
        0.0168f, 0.1178f, 0.8654f,
    ))

    internal fun invert(m: FloatArray): FloatArray {
        val a = m.map { it.toDouble() }
        val c0 = a[4] * a[8] - a[5] * a[7]; val c1 = a[5] * a[6] - a[3] * a[8]; val c2 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c0 + a[1] * c1 + a[2] * c2
        return doubleArrayOf(
            c0, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c1, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c2, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        ).map { (it / det).toFloat() }.toFloatArray()
    }

    private fun srgbCurve(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else (1.055 * x.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    /**
     * Writes [src] (linear ProPhoto, its own size) into [out] (the print: interleaved RGB, [w]×[h]) in
     * the print's encoding: display sRGB, or linear sRGB when [linear]. [other] converts a display sRGB
     * triple to the print's space when the engine was asked for another (Adobe RGB, ProPhoto, Rec.2020);
     * null for sRGB. Made smaller by averaging the pixels each print pixel covers, larger bilinearly.
     */
    fun encodeInto(src: FloatBuffer, sw: Int, sh: Int, out: FloatBuffer, w: Int, h: Int, linear: Boolean = false,
                   other: ((FloatArray) -> Unit)? = null) {
        val m = TO_SRGB
        val down = w <= sw && h <= sh
        fun band(ya: Int, yb: Int) {
            val px = FloatArray(3)
            for (y in ya until yb) {
                for (x in 0 until w) {
                    var r = 0f; var g = 0f; var b = 0f
                    if (down) {
                        val x0 = (x.toLong() * sw / w).toInt(); val x1 = max(x0 + 1, ((x + 1).toLong() * sw / w).toInt())
                        val y0 = (y.toLong() * sh / h).toInt(); val y1 = max(y0 + 1, ((y + 1).toLong() * sh / h).toInt())
                        var n = 0
                        for (yy in y0 until min(y1, sh)) { var i = 3 * (yy * sw + x0); for (xx in x0 until min(x1, sw)) { r += src.get(i); g += src.get(i + 1); b += src.get(i + 2); i += 3; n++ } }
                        if (n > 0) { r /= n; g /= n; b /= n }
                    } else {
                        val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat()); val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1); val tx = fx - x0; val ty = fy - y0
                        fun at(xx: Int, yy: Int, c: Int) = src.get(3 * (yy * sw + xx) + c)
                        for (c in 0..2) {
                            val v = (at(x0, y0, c) * (1 - tx) + at(x1, y0, c) * tx) * (1 - ty) + (at(x0, y1, c) * (1 - tx) + at(x1, y1, c) * tx) * ty
                            when (c) { 0 -> r = v; 1 -> g = v; else -> b = v }
                        }
                    }
                    // a broken value counts as black, as everywhere in the blend
                    if (r.isNaN()) r = 0f; if (g.isNaN()) g = 0f; if (b.isNaN()) b = 0f
                    val lr = m[0] * r + m[1] * g + m[2] * b; val lg = m[3] * r + m[4] * g + m[5] * b; val lb = m[6] * r + m[7] * g + m[8] * b
                    if (linear) { px[0] = lr.coerceIn(0f, 1f); px[1] = lg.coerceIn(0f, 1f); px[2] = lb.coerceIn(0f, 1f) }
                    else { px[0] = srgbCurve(lr); px[1] = srgbCurve(lg); px[2] = srgbCurve(lb) }
                    other?.invoke(px)
                    val p = 3 * (y * w + x)
                    out.put(p, px[0]); out.put(p + 1, px[1]); out.put(p + 2, px[2])
                }
            }
        }
        val bands = max(1, min(Runtime.getRuntime().availableProcessors(), h / 64))
        if (bands == 1) band(0, h) else {
            val step = (h + bands - 1) / bands
            (0 until bands).map { k -> Thread { band(k * step, min(h, (k + 1) * step)) }.apply { start() } }.forEach { it.join() }
        }
    }

    /** [encodeInto] for a prepared photo and an engine print. */
    fun lay(base: Develop.Source, print: com.spectrafilm.engine.SimResult) {
        val cs = print.colorSpace
        val other: ((FloatArray) -> Unit)? = when (cs) {
            com.spectrafilm.engine.ColorSpace.SRGB, com.spectrafilm.engine.ColorSpace.LINEAR_SRGB -> null
            // (Android 7 has no colour conversions: the print's own space is read as sRGB there)
            else -> if (android.os.Build.VERSION.SDK_INT < 26) null else {
                val named = when (cs) {
                    com.spectrafilm.engine.ColorSpace.ADOBE_RGB -> android.graphics.ColorSpace.Named.ADOBE_RGB
                    com.spectrafilm.engine.ColorSpace.PROPHOTO -> android.graphics.ColorSpace.Named.PRO_PHOTO_RGB
                    com.spectrafilm.engine.ColorSpace.REC2020 -> android.graphics.ColorSpace.Named.BT2020
                    else -> android.graphics.ColorSpace.Named.ACES
                }
                val connector = android.graphics.ColorSpace.connect(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB), android.graphics.ColorSpace.get(named))
                val toPrint: (FloatArray) -> Unit = { px -> val t = connector.transform(px[0], px[1], px[2]); px[0] = t[0]; px[1] = t[1]; px[2] = t[2] }
                toPrint
            }
        }
        encodeInto(base.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer(), base.width, base.height,
            print.data.order(ByteOrder.nativeOrder()).asFloatBuffer(), print.width, print.height,
            linear = cs == com.spectrafilm.engine.ColorSpace.LINEAR_SRGB, other = other)
    }
}
