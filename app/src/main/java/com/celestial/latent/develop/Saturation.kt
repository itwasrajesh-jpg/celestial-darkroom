package com.celestial.latent.develop

import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Painted saturation (62a): colour made richer or paler place by place, on the finished print.
 *
 * Painted on DODGE & BURN's "saturation" chip, kept per photo like the other masks
 * ([ExposureMaps.SATURATION]): each cell holds −1 … +1, and the colour's strength there is
 * multiplied by 1 + that (−1 grey, 0 untouched, +1 twice as strong).
 *
 * Applied after the film, to the engine's output: making the film's own chemistry change place by
 * place would mean surgery on the engine. The change is made in Oklab, where brightness (L) and
 * colour (a, b) are apart, so only the colour's strength moves — the light stays where the print
 * put it. Richer has a soft ceiling ([CAP], Oklab chroma): weak colours grow, colours already
 * strong barely move, so reds do not turn neon. Paler has none: −1 is grey.
 *
 * Proven against the proof (`saturation-proof/scripts/proof.py`) and its port check.
 */
object Saturation {
    /** The soft ceiling on a boost, in Oklab chroma (an sRGB red is about 0.26). */
    const val CAP = 0.22f
    /** A cell this close to 0 changes nothing, and its pixels are skipped. */
    private const val QUIET = 0.002f
    private const val LUT = 4096

    /** True when [map] would change anything. */
    fun wanted(map: ExposureMap?): Boolean = map != null && map.stops.any { abs(it) >= QUIET }

    /** The new chroma for chroma [c] at gain [g]: scaled, with the soft ceiling when it grows. */
    fun chroma(c: Float, g: Float): Float {
        if (g <= 1f) return c * maxOf(0f, g)
        val capped = CAP * tanh(c * g / CAP)
        return maxOf(capped, c)
    }

    // Oklab (Björn Ottosson): linear sRGB → LMS, and LMS' (cube roots) → Lab.
    private val M2 = floatArrayOf(
        0.2104542553f, 0.7936177850f, -0.0040720468f,
        1.9779984951f, -2.4285922050f, 0.4505937099f,
        0.0259040371f, 0.7827717662f, -0.8086757660f,
    )
    private val M2_INV = floatArrayOf(
        1.0f, 0.3963377774f, 0.2158037573f,
        1.0f, -0.1055613458f, -0.0638541728f,
        1.0f, -0.0894841775f, -1.2914855480f,
    )

    /**
     * Each output space's linear RGB straight to Oklab's LMS, and back: Oklab's own matrix after a
     * change of primaries to sRGB's, white points adapted to D65 (Bradford) — so the space's white
     * lands on Oklab's white and a grey stays grey. Rows sum to 1. Worked out in
     * `saturation/scripts/mats.py`.
     */
    private fun toLms(cs: ColorSpace): FloatArray = when (cs) {
        ColorSpace.ADOBE_RGB -> floatArrayOf(0.5764322615f, 0.3699132211f, 0.0536545174f, 0.2963164739f, 0.5916761266f, 0.1120073994f, 0.1234782548f, 0.2194986958f, 0.6570230494f)
        ColorSpace.PROPHOTO -> floatArrayOf(0.7154424272f, 0.3527978350f, -0.0682402623f, 0.2744081529f, 0.6678051474f, 0.0577866996f, 0.1097832179f, 0.1862005159f, 0.7040162663f)
        ColorSpace.REC2020 -> floatArrayOf(0.6167557872f, 0.3601983994f, 0.0230458134f, 0.2651330640f, 0.6358393641f, 0.0990275718f, 0.1001026342f, 0.2039065194f, 0.6959908464f)
        ColorSpace.ACES2065_1 -> floatArrayOf(0.8904168753f, 0.2608507978f, -0.1512676732f, 0.3445028151f, 0.6776538492f, -0.0221566644f, 0.1350936450f, 0.1902030641f, 0.6747032910f)
        else -> floatArrayOf(0.4122214708f, 0.5363325363f, 0.0514459929f, 0.2119034982f, 0.6806995451f, 0.1073969566f, 0.0883024619f, 0.2817188376f, 0.6299787005f)
    }
    private fun fromLms(cs: ColorSpace): FloatArray = when (cs) {
        ColorSpace.ADOBE_RGB -> floatArrayOf(2.5540368479f, -1.6219762026f, 0.0679393546f, -1.2684380041f, 2.6097574007f, -0.3413193963f, -0.0562347472f, -0.5670418341f, 1.6232765812f)
        ColorSpace.PROPHOTO -> floatArrayOf(1.7383710577f, -0.9879649045f, 0.2495938467f, -0.7070394029f, 1.9343467487f, -0.2273073456f, -0.0840788343f, -0.3575405970f, 1.4416194312f)
        ColorSpace.REC2020 -> floatArrayOf(2.1399067357f, -1.2463895088f, 0.1064827730f, -0.8847358625f, 2.1632309821f, -0.2784951194f, -0.0485737580f, -0.4545031429f, 1.5030769009f)
        ColorSpace.ACES2065_1 -> floatArrayOf(1.3057283784f, -0.5794427325f, 0.2737143540f, -0.6662082348f, 1.7578452308f, -0.0916369958f, -0.0736334906f, -0.3795275963f, 1.4531610869f)
        else -> floatArrayOf(4.0767416613f, -3.3077115904f, 0.2309699287f, -1.2684380041f, 2.6097574007f, -0.3413193963f, -0.0041960865f, -0.7034186145f, 1.7076147009f)
    }

    /** The space's curve undone: its encoded value (0..1) → linear light. As the engine encodes (model/color_output.cpp). */
    internal fun decode(cs: ColorSpace, v: Float): Float {
        val x = v.toDouble()
        return when (cs) {
            ColorSpace.ADOBE_RGB -> x.pow(1.0 / 0.4547069271758437)
            ColorSpace.PROPHOTO -> if (x < 16.0 * 0.001953125) x / 16.0 else x.pow(1.8)
            ColorSpace.REC2020 -> if (x < 4.5 * 0.018) x / 4.5 else ((x + 0.099) / 1.099).pow(1.0 / 0.45)
            ColorSpace.ACES2065_1, ColorSpace.LINEAR_SRGB -> x
            else -> if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }.toFloat()
    }
    /** And back: linear light → the space's encoded value. */
    internal fun encode(cs: ColorSpace, l: Float): Float {
        val x = l.toDouble()
        return when (cs) {
            ColorSpace.ADOBE_RGB -> x.pow(0.4547069271758437)
            ColorSpace.PROPHOTO -> if (x < 0.001953125) x * 16.0 else x.pow(1.0 / 1.8)
            ColorSpace.REC2020 -> if (x < 0.018) x * 4.5 else 1.099 * x.pow(0.45) - 0.099
            ColorSpace.ACES2065_1, ColorSpace.LINEAR_SRGB -> x
            else -> if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        }.toFloat()
    }

    /**
     * Makes the colour richer or paler where [map] says, in place. [data] is the engine's result:
     * three floats per pixel, [w] × [h], encoded in [cs] (0..1). Pixels whose cell is untouched
     * are skipped. Returns true if anything changed.
     *
     * The curves are tables (decoded on an even grid, encoded on a grid even in √light, which is
     * finest in the shadows); worst difference from the exact curves well under one 8-bit step.
     */
    fun apply(data: ByteBuffer, w: Int, h: Int, cs: ColorSpace, map: ExposureMap?): Boolean {
        if (map == null || !wanted(map)) return false
        val dec = FloatArray(LUT + 1) { decode(cs, it.toFloat() / LUT) }
        val enc = FloatArray(LUT + 1) { val s = it.toFloat() / LUT; encode(cs, s * s) }
        val to = toLms(cs); val from = fromLms(cs)
        // rows in bands, one per core: every pixel is worked out on its own, so the result is the
        // same whatever the split (a 12 MP print, all painted: ~3 s on one desktop core)
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(h)
        val workers = (0 until bands).map { b ->
            Thread { rows(data, w, h * b / bands, h * (b + 1) / bands, h, map, dec, enc, to, from) }.apply { start() }
        }
        workers.forEach { it.join() }
        return true
    }

    /** Rows [y0, y1) of [apply]: each with its own view of the buffer and its own row of pixels. */
    private fun rows(data: ByteBuffer, w: Int, y0: Int, y1: Int, h: Int, map: ExposureMap,
                     dec: FloatArray, enc: FloatArray, to: FloatArray, from: FloatArray) {
        val f = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun lin(v: Float): Float { val x = v.coerceIn(0f, 1f) * LUT; val i = minOf(x.toInt(), LUT - 1); val t = x - i; return dec[i] + (dec[i + 1] - dec[i]) * t }
        fun gam(l: Float): Float { val x = sqrt(l.coerceIn(0f, 1f)) * LUT; val i = minOf(x.toInt(), LUT - 1); val t = x - i; return enc[i] + (enc[i + 1] - enc[i]) * t }
        val px = FloatArray(w * 3)
        for (y in y0 until y1) {
            val v = (y + 0.5f) / h
            f.position(y * w * 3); f.get(px)
            var changed = false
            for (x in 0 until w) {
                val s = map.sample((x + 0.5f) / w, v)
                if (abs(s) < QUIET) continue
                val o = x * 3
                val r = lin(px[o]); val g = lin(px[o + 1]); val b = lin(px[o + 2])
                val l = cbrt(to[0] * r + to[1] * g + to[2] * b)
                val m = cbrt(to[3] * r + to[4] * g + to[5] * b)
                val q = cbrt(to[6] * r + to[7] * g + to[8] * b)
                val okL = M2[0] * l + M2[1] * m + M2[2] * q
                val okA = M2[3] * l + M2[4] * m + M2[5] * q
                val okB = M2[6] * l + M2[7] * m + M2[8] * q
                val c = sqrt(okA * okA + okB * okB) + 1e-6f
                val k = chroma(c, 1f + s) / c
                val a2 = okA * k; val b2 = okB * k
                val l2 = M2_INV[0] * okL + M2_INV[1] * a2 + M2_INV[2] * b2
                val m2 = M2_INV[3] * okL + M2_INV[4] * a2 + M2_INV[5] * b2
                val q2 = M2_INV[6] * okL + M2_INV[7] * a2 + M2_INV[8] * b2
                val l3 = l2 * l2 * l2; val m3 = m2 * m2 * m2; val q3 = q2 * q2 * q2
                px[o] = gam(from[0] * l3 + from[1] * m3 + from[2] * q3)
                px[o + 1] = gam(from[3] * l3 + from[4] * m3 + from[5] * q3)
                px[o + 2] = gam(from[6] * l3 + from[7] * m3 + from[8] * q3)
                changed = true
            }
            if (changed) { f.position(y * w * 3); f.put(px) }
        }
    }
}
