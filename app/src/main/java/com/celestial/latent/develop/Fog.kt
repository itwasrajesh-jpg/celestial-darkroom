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
data class FogLook(
    val mode: String = "auto",
    /** Cool (−1) … warm (+1): a colour-temperature shift on top of whichever colour is chosen. */
    val warmth: Float = 0f,
    val picked: List<Float>? = null,
    /** How much of the painted fog is applied, 0..1 — the whole painting, dialled up or down. */
    val amount: Float = 1f,
    /** Your own colour ("colour" mode): a hue, 0..360 as on a colour wheel… */
    val hue: Float = 30f,
    /** …and how strongly tinted, 0 neutral … 1 the strongest believable fog tint. */
    val tint: Float = 0.5f,
    /**
     * An atmosphere's air (60a): its brightness as a share of the scene's brightest light, read
     * from the farthest part of the photo ([Air.read]). 0: the plain fog's brightness.
     */
    val airLevel: Float = 0f,
    /** Dusk's glow towards the sun: 0 none, 1 full ([Air.glow]), centred on the sun at (glowU, glowV). */
    val glow: Float = 0f,
    val glowU: Float = 0.5f,
    val glowV: Float = 0.3f,
    /**
     * The atmosphere's recipe (60b), so it can be built again on another photo from that photo's
     * own depth: its kind (one of [Air.KINDS], "" for painted fog), how thick against the proven
     * atmosphere, and whether the foreground was kept clear. Its colour is [mode] and the rest, as
     * for any fog — so a re-coloured atmosphere carries its new colour.
     */
    val air: String = "",
    val airScale: Float = 1f,
    val airKeep: Boolean = false,
) {
    /**
     * Which atmosphere this fog is, or null for painted fog. Saves from 60a, before the kind was
     * kept, are known by their colour mode being an atmosphere's name while the air is set.
     */
    fun airKind(): String? = when {
        airLevel <= 0f -> null
        air in Air.KINDS -> air
        mode in Air.KINDS -> mode
        else -> null
    }

    /**
     * Saved as "v2|mode|warmth|amount|hue|tint[|r|g|b]". The version mark matters: the older
     * comma forms were told apart by counting parts, and a fifth field would have made a new
     * colour indistinguishable from an old picked one.
     */
    fun key(): String = (if (airLevel <= 0f && glow <= 0f)
        listOf("v2", mode, "%.3f".format(Locale.US, warmth), "%.3f".format(Locale.US, amount),
            "%.1f".format(Locale.US, hue), "%.3f".format(Locale.US, tint))
    else if (air.isNotEmpty())
        // an atmosphere with its recipe (60b): v4 adds air|airScale|airKeep after v3's fields
        listOf("v4", mode, "%.3f".format(Locale.US, warmth), "%.3f".format(Locale.US, amount),
            "%.1f".format(Locale.US, hue), "%.3f".format(Locale.US, tint), "%.4f".format(Locale.US, airLevel),
            "%.3f".format(Locale.US, glow), "%.4f".format(Locale.US, glowU), "%.4f".format(Locale.US, glowV),
            air, "%.3f".format(Locale.US, airScale), if (airKeep) "1" else "0")
    else
        // an atmosphere's air (60a): v3 adds airLevel|glow|glowU|glowV; plain fog stays v2, so its
        // saves and JPEG notes read as before
        listOf("v3", mode, "%.3f".format(Locale.US, warmth), "%.3f".format(Locale.US, amount),
            "%.1f".format(Locale.US, hue), "%.3f".format(Locale.US, tint), "%.4f".format(Locale.US, airLevel),
            "%.3f".format(Locale.US, glow), "%.4f".format(Locale.US, glowU), "%.4f".format(Locale.US, glowV))
    ).joinToString("|") + (picked?.joinToString("|", prefix = "|") { "%.5f".format(Locale.US, it) } ?: "")

    companion object {
        fun parse(s: String?): FogLook {
            if (s.isNullOrEmpty()) return FogLook()
            return runCatching {
                if (s.startsWith("v4|")) {
                    val p = s.split("|")
                    FogLook(p[1], p[2].toFloat(),
                        if (p.size >= 16) listOf(p[13].toFloat(), p[14].toFloat(), p[15].toFloat()) else null,
                        p[3].toFloat().coerceIn(0f, 1f), p[4].toFloat(), p[5].toFloat().coerceIn(0f, 1f),
                        p[6].toFloat().coerceAtLeast(0f), p[7].toFloat().coerceIn(0f, 1f), p[8].toFloat(), p[9].toFloat(),
                        p[10], p[11].toFloat().coerceIn(0.1f, 3f), p[12] == "1")
                } else if (s.startsWith("v3|")) {
                    val p = s.split("|")
                    FogLook(p[1], p[2].toFloat(),
                        if (p.size >= 13) listOf(p[10].toFloat(), p[11].toFloat(), p[12].toFloat()) else null,
                        p[3].toFloat().coerceIn(0f, 1f), p[4].toFloat(), p[5].toFloat().coerceIn(0f, 1f),
                        p[6].toFloat().coerceAtLeast(0f), p[7].toFloat().coerceIn(0f, 1f), p[8].toFloat(), p[9].toFloat())
                } else if (s.startsWith("v2|")) {
                    val p = s.split("|")
                    FogLook(p[1], p[2].toFloat(),
                        if (p.size >= 9) listOf(p[6].toFloat(), p[7].toFloat(), p[8].toFloat()) else null,
                        p[3].toFloat().coerceIn(0f, 1f), p[4].toFloat(), p[5].toFloat().coerceIn(0f, 1f))
                } else {
                    // the older forms: mode, warmth[, r, g, b] (2 or 5 parts), or
                    // mode, warmth, amount[, r, g, b] (3 or 6 parts)
                    val p = s.split(",")
                    val hasAmount = p.size == 3 || p.size == 6
                    val amount = if (hasAmount) p[2].toFloat() else 1f
                    val at = if (hasAmount) 3 else 2
                    FogLook(p[0], p[1].toFloat(),
                        if (p.size >= at + 3) listOf(p[at].toFloat(), p[at + 1].toFloat(), p[at + 2].toFloat()) else null,
                        amount.coerceIn(0f, 1f))
                }
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

    /** 6500K, neutral: the middle of the cool–warm slider. */
    private const val NEUTRAL_MIRED = 153.8f

    /** Linear screen (sRGB) colours into linear ProPhoto, white kept white (Bradford D65→D50). */
    private val SCREEN_TO_PROPHOTO = floatArrayOf(0.529346f, 0.330073f, 0.140581f, 0.098374f, 0.873461f, 0.028165f, 0.016883f, 0.117672f, 0.865444f)
    /** …and back, for showing a fog colour on screen. */
    private val PROPHOTO_TO_SCREEN = floatArrayOf(2.034076f, -0.727334f, -0.306742f, -0.228813f, 1.231730f, -0.002917f, -0.008570f, -0.153287f, 1.161856f)

    private fun mul(m: FloatArray, c: FloatArray) = FloatArray(3) { r -> m[r * 3] * c[0] + m[r * 3 + 1] * c[1] + m[r * 3 + 2] * c[2] }

    /**
     * Your own colour: a hue as seen on screen, faded toward a light grey IN SCREEN TERMS until
     * its saturation (in linear ProPhoto, where the fog works) is [tint] of the ceiling; at
     * luminance 1. Fading in screen terms keeps the hue the eye chose — fading in linear light
     * drifted violets toward blue by about 15° (now under 8° anywhere on the wheel).
     */
    fun hueChroma(hue: Float, tint: Float, maxSat: Float = SATURATION_MAX): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        val disp = when (h.toInt()) { 0 -> floatArrayOf(1f, x, 0f); 1 -> floatArrayOf(x, 1f, 0f); 2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f); 4 -> floatArrayOf(x, 0f, 1f); else -> floatArrayOf(1f, 0f, x) }
        fun at(k: Float): FloatArray {
            val d = FloatArray(3) { 0.8f * (1f - k) + disp[it] * k }
            val lin = mul(SCREEN_TO_PROPHOTO, FloatArray(3) { Math.pow(d[it].toDouble(), 2.2).toFloat() })
            val y = maxOf(lum(lin), 1e-6f)
            return FloatArray(3) { lin[it] / y }
        }
        fun sat(v: FloatArray): Float { val mx = v.max(); return if (mx <= 0f) 0f else (mx - v.min()) / mx }
        val target = tint.coerceIn(0f, 1f) * maxSat
        var lo = 0f; var hi = 1f
        repeat(30) { val mid = (lo + hi) / 2f; if (sat(at(mid)) > target) hi = mid else lo = mid }
        return at(lo)
    }

    /**
     * A fog colour as it would look on screen, for the swatch: its chroma (at luminance 1) brought
     * to a light grey and into display colours. Approximate — the film changes it — but honest
     * about hue and tint.
     */
    fun swatch(look: FogLook): Int {
        val base = when {
            look.mode == "colour" -> hueChroma(look.hue, look.tint)
            look.mode == "picked" && look.picked != null && look.picked.size == 3 -> {
                val p = look.picked.toFloatArray(); val y = maxOf(lum(p), 1e-6f); FloatArray(3) { p[it] / y }
            }
            look.mode == "auto" -> floatArrayOf(1f, 1f, 1f)
            else -> presetChroma(look.mode)
        }
        val c = capped(withWarmth(base, look.warmth))
        val scr = mul(PROPHOTO_TO_SCREEN, FloatArray(3) { c[it] * 0.75f })
        val e = IntArray(3) { (Math.pow(scr[it].coerceIn(0f, 1f).toDouble(), 1 / 2.2) * 255).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (e[0] shl 16) or (e[1] shl 8) or e[2]
    }

    /** Any colour at luminance 1 as a screen colour, for a swatch. */
    fun swatchOf(c: FloatArray): Int {
        val scr = mul(PROPHOTO_TO_SCREEN, FloatArray(3) { c[it] * 0.75f })
        val e = IntArray(3) { (Math.pow(scr[it].coerceIn(0f, 1f).toDouble(), 1 / 2.2) * 255).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (e[0] shl 16) or (e[1] shl 8) or e[2]
    }

    /** A colour shifted cool or warm by the slider, kept at luminance 1. */
    internal fun withWarmth(c: FloatArray, warmth: Float): FloatArray {
        val shift = tintFor(NEUTRAL_MIRED + WARMTH_MIREDS * warmth.coerceIn(-1f, 1f))
        val v = FloatArray(3) { c[it] * shift[it] }
        val y = maxOf(lum(v), 1e-6f)
        return FloatArray(3) { v[it] / y }
    }

    /**
     * A preset's colour at luminance 1: its colour temperature — and for smog, a pale brown (60a):
     * warm light through smoke, whose yellow-brown particles take some blue. Kept pale by the ceiling.
     */
    fun presetChroma(mode: String): FloatArray {
        val base = tintFor(PRESETS.firstOrNull { it.first == mode }?.second ?: NEUTRAL_MIRED)
        if (mode != "smog") return base
        val v = tintFor(SMOG_MIRED).let { t -> FloatArray(3) { t[it] * SMOG_FILTER[it] } }
        val y = maxOf(lum(v), 1e-6f)
        return FloatArray(3) { v[it] / y }
    }
    private const val SMOG_MIRED = 260f
    private val SMOG_FILTER = floatArrayOf(1f, 0.97f, 0.80f)

    /** Luminance of linear ProPhoto RGB. */
    fun luminance(r: Float, g: Float, b: Float) = LUM[0] * r + LUM[1] * g + LUM[2] * b

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

    /** A colour at luminance 1, made no more saturated than [max] by moving it toward grey. */
    internal fun capped(c: FloatArray, max: Float = SATURATION_MAX): FloatArray {
        fun sat(k: Float): Float {
            val v = FloatArray(3) { 1f + (c[it] - 1f) * k }
            val mx = v.max(); return if (mx <= 0f) 0f else (mx - v.min()) / mx
        }
        if (sat(1f) <= max) return c
        var lo = 0f; var hi = 1f
        repeat(24) { val mid = (lo + hi) / 2f; if (sat(mid) > max) hi = mid else lo = mid }
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
     *  - a preset: that colour temperature, at the scene's brightness;
     *  - colour: your own hue and tint, at the scene's brightness;
     *  - picked: the picked light's own colour and brightness;
     * then shifted cool or warm by the slider, whichever it is.
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
            look.mode == "colour" -> hueChroma(look.hue, look.tint) to lum(auto)
            else -> presetChroma(if (PRESETS.any { it.first == look.mode }) look.mode else PRESETS[0].first) to lum(auto)
        }
        // an atmosphere's air: as bright as the distance, not the brightest light (picked keeps its own)
        val lit = if (look.airLevel > 0f && look.mode != "picked") lum(auto) * look.airLevel else level
        // cool–warm works on every colour, then the ceiling keeps the result pale
        val c = capped(withWarmth(chroma, look.warmth))
        return FloatArray(3) { c[it] * lit }
    }

    /** Fogs [src] in place by the painted thickness [mask] and the colour [look]. */
    fun apply(src: Develop.Source, mask: ExposureMap, look: FogLook, log: (String) -> Unit = {}) {
        if (mask.isBlank) return
        val amount = look.amount.coerceIn(0f, 1f)
        if (amount <= 0f) return
        log("fog")
        val a = light(src, look)
        val w = src.width; val h = src.height
        // dusk's glow towards the sun, on the mask's own grid and laid over like the mask
        val glow = if (look.glow > 0f) ExposureMap(mask.width, mask.height,
            Air.glow(look.glowU, look.glowV, mask.width, mask.height).let { gl -> FloatArray(gl.size) { 1f + (gl[it] - 1f) * look.glow } }) else null
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val d = mask.sample(u, v) * amount
                if (d <= 0.0005f) continue
                val t = exp(-d)
                val gl = glow?.sample(u, v) ?: 1f
                val o = (y * w + x) * 3
                for (c in 0 until 3) f.put(o + c, f.get(o + c) * t + a[c] * gl * (1f - t))
            }
        }
    }
}

/** Each photo's fog colour, kept beside it like its masks. */
object FogLooks {
    private const val FILE = "latent_fog"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** Every photo with something saved here, by the address it was saved under. */
    fun photos(context: Context): Set<String> = prefs(context).all.keys

    fun load(context: Context, photo: Uri): FogLook = FogLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: FogLook) {
        prefs(context).edit().apply { if (look == FogLook()) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}
