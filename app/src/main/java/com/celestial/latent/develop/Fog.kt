package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp

/**
 * The fog's colour. [mode] is "auto" (from the scene's own brightest light — the safe pick), one
 * of the presets ("mist", "morning", "dusk", "smog") tuned by [warmth] (−1 cool … +1 warm), or
 * "picked" — light taken from a spot in the scene, as linear RGB in [picked].
 */
data class FogLook(val mode: String = "auto", val warmth: Float = 0f, val picked: List<Float>? = null) {
    fun key(): String = "$mode,${"%.2f".format(Locale.US, warmth)}" +
        (picked?.joinToString(",", prefix = ",") { "%.5f".format(Locale.US, it) } ?: "")

    companion object {
        fun parse(s: String?): FogLook {
            if (s.isNullOrEmpty()) return FogLook()
            return runCatching {
                val p = s.split(",")
                FogLook(p[0], p[1].toFloat(), if (p.size >= 5) listOf(p[2].toFloat(), p[3].toFloat(), p[4].toFloat()) else null)
            }.getOrDefault(FogLook())
        }
    }
}

/**
 * Fog in the air between the camera and the scene: each place shows part of itself and part of
 * the fog's own light, by how much air is in front of it. The painted mask holds that thickness
 * (optical depth); the share that gets through is e^−thickness — the standard law for light
 * through haze. So painting thicker in the distance gives a picture depth: far things fade into
 * the fog's colour, near ones keep their own.
 *
 *     out = scene × t + fog light × (1 − t),   t = e^−thickness
 *
 * Applied to the light before anything else — air comes before the lens, so before the 85B, the
 * diffusion filter and the film — and the film responds to the fogged light as it would.
 */
object Fog {
    /** Luminance weights for linear ProPhoto RGB (its XYZ Y row). */
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)

    /**
     * Presets as colour temperatures of the light the fog is lit by, in mireds (1,000,000 ÷
     * kelvin): mist 6500K (neutral), morning 8000K, dusk 4000K, smog 5000K. Real fog is nearly
     * colourless — its droplets scatter all colours alike — so any colour is the light's.
     * (These replaced arbitrary colour ratios that, with the slider at cool, came out as blue
     * paint: saturation 0.65, against 0.15 for real 8000K light.)
     */
    val PRESETS = listOf("mist" to 153.8f, "morning" to 125f, "dusk" to 250f, "smog" to 200f)

    /** How far the warmth slider moves the colour temperature, in mireds either way. */
    private const val WARMTH_MIREDS = 90f

    /** No fog is more saturated than this ((max − min) ÷ max): fog is pale, never paint. */
    private const val SATURATION_MAX = 0.25f

    /** How much of the scene's own light colour Auto keeps; the rest is neutral, as fog is. */
    private const val AUTO_TINT = 0.35f

    /**
     * Light of each colour temperature as linear ProPhoto RGB, relative to 6500K and at
     * luminance 1 — every 10 mireds from 30 (33,000K) to 350 (2,860K). Blackbody light through
     * the CIE 1931 observer, as for the 85B.
     */
    private val TINTS = arrayOf(
        floatArrayOf(1.0067f, 0.9972f, 1.8190f),
        floatArrayOf(1.0025f, 0.9989f, 1.7516f),
        floatArrayOf(0.9986f, 1.0005f, 1.6825f),
        floatArrayOf(0.9952f, 1.0019f, 1.6123f),
        floatArrayOf(0.9923f, 1.0030f, 1.5419f),
        floatArrayOf(0.9902f, 1.0039f, 1.4716f),
        floatArrayOf(0.9888f, 1.0045f, 1.4022f),
        floatArrayOf(0.9882f, 1.0047f, 1.3339f),
        floatArrayOf(0.9885f, 1.0046f, 1.2672f),
        floatArrayOf(0.9896f, 1.0042f, 1.2025f),
        floatArrayOf(0.9916f, 1.0034f, 1.1399f),
        floatArrayOf(0.9945f, 1.0022f, 1.0795f),
        floatArrayOf(0.9983f, 1.0007f, 1.0216f),
        floatArrayOf(1.0029f, 0.9988f, 0.9662f),
        floatArrayOf(1.0084f, 0.9966f, 0.9132f),
        floatArrayOf(1.0146f, 0.9941f, 0.8628f),
        floatArrayOf(1.0216f, 0.9913f, 0.8147f),
        floatArrayOf(1.0293f, 0.9882f, 0.7691f),
        floatArrayOf(1.0377f, 0.9848f, 0.7259f),
        floatArrayOf(1.0467f, 0.9812f, 0.6848f),
        floatArrayOf(1.0563f, 0.9773f, 0.6460f),
        floatArrayOf(1.0665f, 0.9732f, 0.6092f),
        floatArrayOf(1.0772f, 0.9688f, 0.5744f),
        floatArrayOf(1.0884f, 0.9643f, 0.5415f),
        floatArrayOf(1.1002f, 0.9596f, 0.5105f),
        floatArrayOf(1.1123f, 0.9546f, 0.4811f),
        floatArrayOf(1.1249f, 0.9496f, 0.4534f),
        floatArrayOf(1.1378f, 0.9443f, 0.4273f),
        floatArrayOf(1.1511f, 0.9390f, 0.4026f),
        floatArrayOf(1.1647f, 0.9334f, 0.3793f),
        floatArrayOf(1.1787f, 0.9278f, 0.3574f),
        floatArrayOf(1.1929f, 0.9220f, 0.3366f),
        floatArrayOf(1.2074f, 0.9162f, 0.3171f)
    )

    /** The colour of light at [mired], interpolated from the table. */
    fun tintFor(mired: Float): FloatArray {
        val x = ((mired - 30f) / 10f).coerceIn(0f, (TINTS.size - 1).toFloat())
        val i = x.toInt().coerceAtMost(TINTS.size - 2); val f = x - i
        val c = FloatArray(3) { k -> TINTS[i][k] * (1 - f) + TINTS[i + 1][k] * f }
        // the table is rounded to four places; rescale so brightness is kept exactly
        val y = LUM[0] * c[0] + LUM[1] * c[1] + LUM[2] * c[2]
        return FloatArray(3) { c[it] / y }
    }

    private fun lum(c: FloatArray) = LUM[0] * c[0] + LUM[1] * c[1] + LUM[2] * c[2]

    /** A colour at luminance 1, made no more saturated than the ceiling by moving it toward grey. */
    private fun capped(c: FloatArray): FloatArray {
        fun sat(k: Float): Float {
            val v = FloatArray(3) { 1f + (c[it] - 1f) * k }
            val mx = v.max(); return if (mx <= 0f) 0f else (mx - v.min()) / mx
        }
        if (sat(1f) <= SATURATION_MAX) return c
        var lo = 0f; var hi = 1f
        repeat(24) { val mid = (lo + hi) / 2f; if (sat(mid) > SATURATION_MAX) hi = mid else lo = mid }
        return FloatArray(3) { 1f + (c[it] - 1f) * lo }
    }

    /**
     * The scene's own brightest light: the average colour of its brightest 5% — usually the
     * sky. The safe choice: fog the colour and brightness of the light that is really there.
     */
    fun autoLight(src: Develop.Source): FloatArray {
        val f = src.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = src.width * src.height
        val step = maxOf(1, n / 200_000)
        val count = (n + step - 1) / step
        val l = FloatArray(count)
        var k = 0
        var i = 0
        while (i < n && k < count) { val o = i * 3; l[k++] = LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2); i += step }
        val sorted = l.copyOf(k).also { it.sort() }
        val threshold = sorted[(k * 0.95f).toInt().coerceIn(0, k - 1)]
        var r = 0.0; var g = 0.0; var b = 0.0; var m = 0
        i = 0
        while (i < n) {
            val o = i * 3
            val y = LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2)
            if (y >= threshold) { r += f.get(o); g += f.get(o + 1); b += f.get(o + 2); m++ }
            i += step
        }
        if (m == 0) return floatArrayOf(threshold, threshold, threshold)
        return floatArrayOf((r / m).toFloat(), (g / m).toFloat(), (b / m).toFloat())
    }

    /**
     * The fog's light, for a look: a pale colour at a brightness. Every mode passes the same
     * saturation ceiling, the eyedropper included — pick a vivid blue sky and the fog is a pale
     * blue mist.
     *  - auto: the scene's brightest light, at its brightness, mostly neutral with a hint of its colour;
     *  - a preset: that colour temperature, moved by the warmth slider, at the scene's brightness;
     *  - picked: the picked light's own colour and brightness.
     */
    fun light(src: Develop.Source, look: FogLook): FloatArray {
        val auto = autoLight(src)
        val (chroma, level) = when {
            look.mode == "picked" && look.picked != null && look.picked.size == 3 -> {
                val p = look.picked.toFloatArray(); val y = maxOf(lum(p), 1e-6f)
                FloatArray(3) { p[it] / y } to y
            }
            look.mode == "auto" -> {
                val y = maxOf(lum(auto), 1e-6f)
                FloatArray(3) { 1f + (auto[it] / y - 1f) * AUTO_TINT } to y
            }
            else -> {
                val base = PRESETS.firstOrNull { it.first == look.mode }?.second ?: PRESETS[0].second
                tintFor(base + WARMTH_MIREDS * look.warmth.coerceIn(-1f, 1f)) to lum(auto)
            }
        }
        val c = capped(chroma)
        return FloatArray(3) { c[it] * level }
    }

    /** Fogs [src] in place by the painted thickness [mask] and the colour [look]. */
    fun apply(src: Develop.Source, mask: ExposureMap, look: FogLook, log: (String) -> Unit = {}) {
        if (mask.isBlank) return
        log("fog")
        val a = light(src, look)
        val w = src.width; val h = src.height
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val d = mask.sample((x + 0.5f) / w, v)
                if (d <= 0.0005f) continue
                val t = exp(-d)
                val o = (y * w + x) * 3
                for (c in 0 until 3) f.put(o + c, f.get(o + c) * t + a[c] * (1f - t))
            }
        }
    }
}

/** Each photo's fog colour, kept beside it like its masks. */
object FogLooks {
    private const val FILE = "latent_fog"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun load(context: Context, photo: Uri): FogLook = FogLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: FogLook) {
        prefs(context).edit().apply { if (look == FogLook()) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}
