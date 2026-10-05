package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * God rays for one photo, from one of four kinds of light, as in 3D software:
 *  - point: rays spread from one spot ([u], [v]) — a lamp, the sun in the frame;
 *  - sun: a directional light, the sun far outside the frame — parallel rays travelling from the
 *    arrow's tail ([u], [v]) toward its tip ([u2], [v2]);
 *  - spot: a point light ([u], [v]) shining only within a cone of half-angle [cone] around its aim
 *    ([u2], [v2]) — a stage light, a torch, a shaded streetlamp;
 *  - area: a light with size, the line from ([u], [v]) to ([u2], [v2]) — a window, a doorway.
 * Positions are 0..1 across and down the framed picture; NaN until placed. [amount] is how
 * bright, [length] how far the rays reach (a share of the picture's long edge).
 */
data class RaysLook(
    val type: String = "point",
    val u: Float = Float.NaN, val v: Float = Float.NaN,
    val u2: Float = Float.NaN, val v2: Float = Float.NaN,
    val cone: Float = 0.35f,
    val amount: Float = 0.3f, val length: Float = 0.5f,
    /** "add": the light emits its own beam. "gaps": the photo's own light streams through gaps. */
    val mode: String = "add",
    /** The light's colour: cool (−1) … warm (+1), and optionally a hue of its own. */
    val warmth: Float = 0f,
    val coloured: Boolean = false,
    val hue: Float = 40f,
    val tint: Float = 0.6f,
    /** Dusty streaks in the beam, 0 smooth … 1 strongly streaked. */
    val dust: Float = 0.5f,
    /** 0: beams everywhere … 1: beams only where fog is painted (real beams need hazy air). */
    val fogOnly: Float = 0f,
    /**
     * How strongly the light lands on surfaces (0 = only in the air): the sun lighting what it
     * faces, with real shadows, worked out from the photo's estimated depth. Sun only for now.
     */
    val surface: Float = 0f,
    /** Where the sun is in depth: −1 behind the camera (lighting faces) … +1 behind the subject (rims). */
    val front: Float = 0f,
    /** How big the scene is: 0 a close-up … 1 a wide view — sets the depth's scale, and so its shadows. */
    val scale: Float = 0.3f,
    /**
     * A lamp's place in depth, beyond where it was tapped: −1 nearer the camera … +1 farther. A
     * lamp tapped on a bulb sits at the bulb's depth; this tucks it in front or behind, or places
     * one that is outside the frame.
     */
    val nudge: Float = 0f,
    /** How far a lamp's pool of light spreads before fading (inverse square, set by eye): 0 … 1. */
    val reach: Float = 0.4f,
    /**
     * How much the light may reveal where the photo recorded almost nothing: 0 = it only scales
     * what is there (strict, safe) … 1 = it assumes an ordinary surface there, in the room's own
     * colour and with the photo's own faint texture — so a lamp's pool can show in a dark room.
     */
    val reveal: Float = 0f,
    /** An area light's width and height, as fractions of the picture's width and height (set by its corner handle). */
    val aw: Float = 0.2f, val ah: Float = 0.12f,
    /**
     * Light bouncing off what the added lights hit, onto everything those surfaces can see: 0 … 1.
     * A scene setting (light 1's is used), like the scene's size.
     */
    val bounce: Float = 0.5f,
) {
    val placed: Boolean get() = u in 0f..1f && v in 0f..1f

    /** The second point each kind needs, placed sensibly if it is not set yet. */
    fun withDefaults(): RaysLook {
        if (!placed || (type == "point") || (!u2.isNaN() && !v2.isNaN())) return this
        return when (type) {
            "sun" -> copy(u2 = (u + 0.12f).coerceIn(0f, 1f), v2 = (v + 0.2f).coerceIn(0f, 1f))
            "spot" -> copy(u2 = u, v2 = (v + 0.3f).coerceIn(0f, 1f))
            // a panel faces its aim point: below and towards the picture's middle to start
            "area" -> copy(u2 = (u + (0.5f - u) * 0.5f).coerceIn(0f, 1f), v2 = (v + 0.3f).coerceIn(0f, 1f))
            else -> this
        }
    }

    /**
     * The light's colour, at luminance 1. Lights may be vivid — stage lights, neon, sodium lamps —
     * so the ceiling is far higher than fog's.
     */
    fun chroma(): FloatArray = Fog.capped(
        Fog.withWarmth(if (coloured) Fog.hueChroma(hue, tint, LIGHT_SATURATION_MAX) else floatArrayOf(1f, 1f, 1f), warmth),
        LIGHT_SATURATION_MAX)

    /** Saved as "v3|…" with every field; older saves were all made in "through gaps". */
    fun key(): String = listOf("v6", type, mode).joinToString("|") + "|" +
        listOf(u, v, u2, v2, cone, amount, length, warmth, if (coloured) 1f else 0f, hue, tint, dust, fogOnly, surface, front, scale, nudge, reach, reveal, aw, ah, bounce)
            .joinToString("|") { "%.4f".format(Locale.US, it) }

    companion object {
        const val LIGHT_SATURATION_MAX = 0.9f

        fun parse(s: String?): RaysLook {
            if (s.isNullOrEmpty()) return RaysLook()
            return runCatching {
                when {
                    s.startsWith("v6|") -> {
                        val p = s.split("|"); val f = p.drop(3).map { it.toFloat() }
                        RaysLook(p[1], f[0], f[1], f[2], f[3], f[4], f[5], f[6], p[2], f[7], f[8] > 0.5f, f[9], f[10], f[11], f[12], f[13], f[14], f[15], f[16], f[17], f[18], f[19], f[20], f[21])
                    }
                    s.startsWith("v5|") -> {
                        val p = s.split("|"); val f = p.drop(3).map { it.toFloat() }
                        RaysLook(p[1], f[0], f[1], f[2], f[3], f[4], f[5], f[6], p[2], f[7], f[8] > 0.5f, f[9], f[10], f[11], f[12], f[13], f[14], f[15], f[16], f[17], f[18])
                    }
                    s.startsWith("v4|") -> {
                        val p = s.split("|"); val f = p.drop(3).map { it.toFloat() }
                        RaysLook(p[1], f[0], f[1], f[2], f[3], f[4], f[5], f[6], p[2], f[7], f[8] > 0.5f, f[9], f[10], f[11], f[12], f[13], f[14], f[15])
                    }
                    s.startsWith("v3|") -> {
                        val p = s.split("|"); val f = p.drop(3).map { it.toFloat() }
                        RaysLook(p[1], f[0], f[1], f[2], f[3], f[4], f[5], f[6], p[2], f[7], f[8] > 0.5f, f[9], f[10], f[11], f[12])
                    }
                    s.startsWith("v2|") -> {
                        val p = s.split("|")
                        RaysLook(p[1], p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), mode = "gaps")
                    }
                    else -> {
                        val p = s.split(",").map { it.toFloat() }
                        RaysLook("point", p[0], p[1], amount = p[2], length = p[3], mode = "gaps")
                    }
                }
            }.getOrDefault(RaysLook())
        }
    }
}

/**
 * Light scattered in the air toward the camera from the light source, through the gaps between
 * whatever blocks it — crepuscular rays. Each place collects light from the scene's bright parts
 * along the line toward the source, fading with distance; where leaves or window frames block the
 * bright parts, a shadow streams out instead, and the beams appear between.
 *
 * What counts as a light: anything above half the scene's brightest light AND at least four times
 * its typical brightness, building up smoothly to the brightest. (The first rule alone made a dull,
 * evenly lit scene shine everywhere; a fixed top share found nothing when the sky filled a quarter
 * of the frame — both caught on a test scene before building.)
 *
 * Traced on a version of the picture 512 pixels on its long edge and smoothly enlarged: rays are
 * soft, and this keeps them identical at every print size. Added to the light before the film, so
 * halation and glow act on them as on any light.
 */
object Rays {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)
    private const val GRID = 512
    private const val STEPS = 64
    /** An area light is traced as this many points along its line, each with fewer steps. */
    private const val AREA_POINTS = 5
    private const val AREA_STEPS = 40

    fun apply(src: Develop.Source, look: RaysLook, mask: ExposureMap?, fogMask: ExposureMap? = null, fogAmount: Float = 1f,
              log: (String) -> Unit = {}) {
        if (!look.placed || look.amount <= 0f) return
        if (mask != null && mask.stops.all { it <= 0f }) return
        log(if (look.mode == "gaps") "god rays, through gaps" else "god rays, added light")
        val w = src.width; val h = src.height
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        // 1. a small copy of the picture, box-averaged
        val gw: Int; val gh: Int
        if (w >= h) { gw = minOf(GRID, w); gh = maxOf(1, Math.round(gw.toFloat() * h / w)) } else { gh = minOf(GRID, h); gw = maxOf(1, Math.round(gh.toFloat() * w / h)) }
        val small = FloatArray(gw * gh * 3)
        val count = IntArray(gw * gh)
        for (y in 0 until h) {
            val gy = (y * gh / h).coerceAtMost(gh - 1)
            for (x in 0 until w) {
                val g = gy * gw + (x * gw / w).coerceAtMost(gw - 1); val o = (y * w + x) * 3
                small[g * 3] += f.get(o); small[g * 3 + 1] += f.get(o + 1); small[g * 3 + 2] += f.get(o + 2); count[g]++
            }
        }
        for (g in 0 until gw * gh) { val c = maxOf(count[g], 1); small[g * 3] /= c; small[g * 3 + 1] /= c; small[g * 3 + 2] /= c }
        // 2. the bright parts: above half the brightest and four times the typical
        val lum = FloatArray(gw * gh) { LUM[0] * small[it * 3] + LUM[1] * small[it * 3 + 1] + LUM[2] * small[it * 3 + 2] }
        val sorted = lum.copyOf().also { it.sort() }
        val top = sorted[((sorted.size - 1) * 0.995f).toInt()]
        val median = sorted[sorted.size / 2]
        val thr = maxOf(0.5f * top, 4f * median, 1e-6f)
        val bright = FloatArray(gw * gh * 3)
        for (g in 0 until gw * gh) {
            val k = ((lum[g] - thr) / thr).coerceIn(0f, 1f)
            if (k > 0f) { bright[g * 3] = small[g * 3] * k; bright[g * 3 + 1] = small[g * 3 + 1] * k; bright[g * 3 + 2] = small[g * 3 + 2] * k }
        }
        // 3. the rays on the small grid, by mode
        val rays = if (look.mode == "gaps") traceGaps(look, bright, gw, gh) else emit(look, gw, gh, sorted[((sorted.size - 1) * 0.95f).toInt()])
        // the light's colour (through gaps, the photo's own light, tinted)
        val tint = look.chroma()
        for (g in 0 until gw * gh) { rays[g * 3] *= tint[0]; rays[g * 3 + 1] *= tint[1]; rays[g * 3 + 2] *= tint[2] }
        // 4. enlarge smoothly onto the picture and add, where the rays may fall
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            val gyf = (v * gh - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = gyf.toInt(); val y1 = minOf(y0 + 1, gh - 1); val fy = gyf - y0
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                var cover = mask?.sample(u, v)?.coerceIn(0f, 1f) ?: 1f
                // real beams show in hazy air: "only in fog" leans the beam on the painted fog's veil
                if (look.fogOnly > 0f) {
                    val veil = fogMask?.let { 1f - exp(-it.sample(u, v) * fogAmount) } ?: 0f
                    cover *= (1f - look.fogOnly) + look.fogOnly * veil
                }
                if (cover <= 0f) continue
                val gxf = (u * gw - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = gxf.toInt(); val x1 = minOf(x0 + 1, gw - 1); val fx = gxf - x0
                val o = (y * w + x) * 3
                for (c in 0 until 3) {
                    val t = rays[(y0 * gw + x0) * 3 + c] * (1 - fx) + rays[(y0 * gw + x1) * 3 + c] * fx
                    val bt = rays[(y1 * gw + x0) * 3 + c] * (1 - fx) + rays[(y1 * gw + x1) * 3 + c] * fx
                    f.put(o + c, f.get(o + c) + (t * (1 - fy) + bt * fy) * cover)
                }
            }
        }
    }
}

/** The tracing for "through gaps": the photo's own bright light, gathered toward the light. */
private fun traceGaps(look: RaysLook, bright: FloatArray, gw: Int, gh: Int): FloatArray {
    val STEPS = 64; val AREA_POINTS = 5; val AREA_STEPS = 40
    val long = maxOf(gw, gh).toFloat()
    val len = maxOf(look.length, 1e-3f)
    val rays = FloatArray(gw * gh * 3)
        fun sample(x: Float, y: Float, c: Int): Float {
        val sx = (x - 0.5f).coerceIn(0f, (gw - 1).toFloat()); val sy = (y - 0.5f).coerceIn(0f, (gh - 1).toFloat())
        val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = minOf(x0 + 1, gw - 1); val y1 = minOf(y0 + 1, gh - 1)
        val fx = sx - x0; val fy = sy - y0
        val t = bright[(y0 * gw + x0) * 3 + c] * (1 - fx) + bright[(y0 * gw + x1) * 3 + c] * fx
        val b = bright[(y1 * gw + x0) * 3 + c] * (1 - fx) + bright[(y1 * gw + x1) * 3 + c] * fx
        return t * (1 - fy) + b * fy
    }
    /** Light reaching a place from a point source at (lx, ly), weighted by [share]. */
    fun fromPoint(px: Float, py: Float, lx: Float, ly: Float, steps: Int, share: Float, o: Int) {
        val dist = sqrt((lx - px) * (lx - px) + (ly - py) * (ly - py)) / long
        var r = 0f; var g = 0f; var b = 0f
        for (i in 0 until steps) {
            val t = i.toFloat() / steps
            val wgt = exp(-dist * t / len)
            val sx = px + (lx - px) * t; val sy = py + (ly - py) * t
            r += sample(sx, sy, 0) * wgt; g += sample(sx, sy, 1) * wgt; b += sample(sx, sy, 2) * wgt
        }
        val k = look.amount * share / steps
        rays[o] += r * k; rays[o + 1] += g * k; rays[o + 2] += b * k
    }
    val lx = look.u * gw; val ly = look.v * gh
    val l2x = (if (look.u2.isNaN()) look.u else look.u2) * gw; val l2y = (if (look.v2.isNaN()) look.v else look.v2) * gh
    for (gy in 0 until gh) for (gx in 0 until gw) {
        val px = gx + 0.5f; val py = gy + 0.5f
        val o = (gy * gw + gx) * 3
        when (look.type) {
            "sun" -> {
                // parallel light: march back against the direction it travels, the same way for every place
                val dx = l2x - lx; val dy = l2y - ly; val n = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val ux = dx / n; val uy = dy / n
                val reach = minOf(3f * len, 1.5f) * long
                var r = 0f; var g = 0f; var b = 0f
                for (i in 0 until STEPS) {
                    val s = i.toFloat() / STEPS * reach
                    val wgt = exp(-(s / long) / len)
                    r += sample(px - ux * s, py - uy * s, 0) * wgt; g += sample(px - ux * s, py - uy * s, 1) * wgt; b += sample(px - ux * s, py - uy * s, 2) * wgt
                }
                val k = look.amount / STEPS
                rays[o] = r * k; rays[o + 1] = g * k; rays[o + 2] = b * k
            }
            "spot" -> {
                // a point light that only shines within its cone, its edge softened
                val ax = l2x - lx; val ay = l2y - ly
                val vx = px - lx; val vy = py - ly
                val an = sqrt(ax * ax + ay * ay); val vn = sqrt(vx * vx + vy * vy)
                val gate = if (an < 1e-6f || vn < 1e-6f) 1f else {
                    val cos = ((ax * vx + ay * vy) / (an * vn)).coerceIn(-1f, 1f)
                    val ang = kotlin.math.acos(cos)
                    val inner = look.cone * 0.8f; val outer = look.cone * 1.2f
                    val t = ((ang - inner) / (outer - inner)).coerceIn(0f, 1f)
                    1f - t * t * (3f - 2f * t)
                }
                if (gate > 0f) fromPoint(px, py, lx, ly, STEPS, gate, o)
            }
            else -> fromPoint(px, py, lx, ly, STEPS, 1f, o)
        }
    }
    return rays
}

/** Smooth, repeatable streak noise, 0..1, around a circle or across a sun's shafts. */
private val STREAK_FREQS = floatArrayOf(7f, 11f, 17f, 23f, 31f, 41f, 53f)
private val STREAK_PHASES = floatArrayOf(3.9276f, 5.6374f, 4.8738f, 1.4150f, 1.8860f, 5.4887f, 0.0331f)
private fun streak(x: Float): Float {
    var v = 0f; var norm = 0f
    for (i in STREAK_FREQS.indices) { val a = 1f / sqrt(STREAK_FREQS[i]); v += a * kotlin.math.sin(STREAK_FREQS[i] * x + STREAK_PHASES[i]); norm += a }
    return (v + norm) / (2f * norm)
}

/**
 * "add": the light you placed emits its own beam into the air, shaped by its kind, fading with
 * distance, streaked by dust — at [level] (the scene's own bright light) times the amount, so a
 * beam is always in proportion to the photo it is in.
 */
private fun emit(look: RaysLook, gw: Int, gh: Int, level: Float): FloatArray {
    val long = maxOf(gw, gh).toFloat()
    val len = maxOf(look.length, 1e-3f) * long
    val out = FloatArray(gw * gh * 3)
    val lx = look.u * gw; val ly = look.v * gh
    val l2x = (if (look.u2.isNaN()) look.u else look.u2) * gw; val l2y = (if (look.v2.isNaN()) look.v else look.v2) * gh
    val dust = look.dust.coerceIn(0f, 1f)
    // dust at full makes shafts clearly visible (about 30% brightness variation across a beam)
    fun dusty(x: Float) = maxOf(0f, 1f + dust * (2f * streak(x) - 1f) * 1.4f)
    fun point(px: Float, py: Float, sx: Float, sy: Float): Float {
        val dx = px - sx; val dy = py - sy
        return exp(-sqrt(dx * dx + dy * dy) / len) * dusty(kotlin.math.atan2(dy, dx))
    }
    val k = look.amount * level
    for (gy in 0 until gh) for (gx in 0 until gw) {
        val px = gx + 0.5f; val py = gy + 0.5f
        val e = when (look.type) {
            "sun" -> {
                // parallel shafts entering at the arrow's tail, travelling toward its tip
                val dx = l2x - lx; val dy = l2y - ly; val n = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val ux = dx / n; val uy = dy / n
                val t = (px - lx) * ux + (py - ly) * uy          // how far along the light's travel
                val q = -(px - lx) * uy + (py - ly) * ux         // across it: which shaft
                val edge = 0.02f * long
                val start = ((t + edge) / (2f * edge)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                start * exp(-maxOf(t, 0f) / len) * dusty(q / long * 2f * PI.toFloat())
            }
            "spot" -> {
                val ax = l2x - lx; val ay = l2y - ly; val vx = px - lx; val vy = py - ly
                val an = sqrt(ax * ax + ay * ay); val vn = sqrt(vx * vx + vy * vy)
                val gate = if (an < 1e-6f || vn < 1e-6f) 1f else {
                    val ang = kotlin.math.acos(((ax * vx + ay * vy) / (an * vn)).coerceIn(-1f, 1f))
                    val t = ((ang - look.cone * 0.8f) / (look.cone * 0.4f)).coerceIn(0f, 1f)
                    1f - t * t * (3f - 2f * t)
                }
                if (gate > 0f) point(px, py, lx, ly) * gate else 0f
            }
            else -> point(px, py, lx, ly)
        }
        val o = (gy * gw + gx) * 3
        out[o] = e * k; out[o + 1] = e * k; out[o + 2] = e * k
    }
    return out
}

/**
 * A photo's extra lights — lights 2, 3 and 4 — each the same kind of light as light 1 (which
 * stays in [RaysLooks], so nothing made before changes). Saved one per line under the photo.
 */
object ExtraLights {
    const val MAX = 3
    private fun prefs(context: Context) = context.getSharedPreferences("latent_extra_lights", Context.MODE_PRIVATE)
    fun load(context: Context, photo: Uri): List<RaysLook> =
        prefs(context).getString(photo.toString(), null)?.lines()?.filter { it.isNotBlank() }?.map { RaysLook.parse(it) }?.take(MAX) ?: emptyList()
    fun save(context: Context, photo: Uri, lights: List<RaysLook>) {
        prefs(context).edit().apply {
            if (lights.isEmpty()) remove(photo.toString()) else putString(photo.toString(), lights.take(MAX).joinToString("\n") { it.key() })
        }.apply()
    }
    /** Every extra light's settings, for a develop's "prepared for" fingerprint. */
    fun key(lights: List<RaysLook>): String = lights.joinToString(";") { it.key() }
}

/** Each photo's rays, kept beside it like its masks. */
object RaysLooks {
    private const val FILE = "latent_rays"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** Every photo with something saved here, by the address it was saved under. */
    fun photos(context: Context): Set<String> = prefs(context).all.keys

    fun load(context: Context, photo: Uri): RaysLook = RaysLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: RaysLook) {
        prefs(context).edit().apply { if (!look.placed) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}
