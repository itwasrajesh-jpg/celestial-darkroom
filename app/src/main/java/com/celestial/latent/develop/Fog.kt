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

    /** Preset colours, as chromaticity only: brightness always comes from the scene. */
    val PRESETS = listOf(
        "mist" to floatArrayOf(1.00f, 1.00f, 1.00f),     // neutral white-grey: overcast, river mist
        "morning" to floatArrayOf(0.82f, 0.97f, 1.30f),  // cool blue: early morning valleys
        "dusk" to floatArrayOf(1.30f, 1.00f, 0.62f),     // warm gold: low sun through dust
        "smog" to floatArrayOf(1.12f, 1.04f, 0.72f),     // dull brownish-yellow: city haze
    )

    private fun lum(c: FloatArray) = LUM[0] * c[0] + LUM[1] * c[1] + LUM[2] * c[2]

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

    /** The fog's light, for a look: a chosen colour at the scene's own brightness, or as picked. */
    fun light(src: Develop.Source, look: FogLook): FloatArray {
        val auto = autoLight(src)
        if (look.mode == "auto") return auto
        if (look.mode == "picked" && look.picked != null && look.picked.size == 3) return look.picked.toFloatArray()
        val base = PRESETS.firstOrNull { it.first == look.mode }?.second ?: PRESETS[0].second
        val w = look.warmth.coerceIn(-1f, 1f)
        val tinted = floatArrayOf(base[0] * (1f + 0.25f * w), base[1], base[2] * (1f - 0.35f * w))
        val scale = lum(auto) / maxOf(lum(tinted), 1e-6f)          // the scene's brightness, this colour
        return floatArrayOf(tinted[0] * scale, tinted[1] * scale, tinted[2] * scale)
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
