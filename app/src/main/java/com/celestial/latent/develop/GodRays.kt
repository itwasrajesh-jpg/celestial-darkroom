package com.celestial.latent.develop

import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * God rays (60f): sunlight streaming past leaves, trunks and rock towards the camera, seen in the
 * air. The open sky is found at fine detail — bright AND far (the depth, sharpened by the photo as
 * the fog's edges are in 60d) — so every gap in a canopy counts. From each place the way towards
 * the sun's place on the picture is walked, gathering how much open sky lies along it (nearer
 * counts more): a bright streak behind each gap, a dark one behind each trunk or leaf. The streaks
 * are added as light in the mist, in the sun's colour, and the mist is dimmed a little where a
 * streak is shadowed, so rays read even against a bright sky; a sky already near white gets no
 * more (it washed out in the first proof).
 *
 * Proved in Python on seven free photos (god-rays/scripts/rays_proof2.py): a park path under trees
 * and a forest with the sun behind its trunks were convincing; a sea cave soft; open, already white
 * mist got none — correctly, with nothing in the way there are no rays. The app's "through gaps"
 * did almost nothing on the same photos.
 *
 * Worked out once on a copy about 800 px on its long edge, before the fog, and laid over the
 * picture after the fog and the light in the air.
 *
 * 61b, proved on Celestial's fence photo (sun-off-frame/scripts/best.py): two walks — a long one
 * (soft, far-reaching) and a short one that remembers only the last stretch, so a shaft is crisp
 * just past its gap and softer further on — each starting a little later at every place, so the
 * walk's steps no longer show as ripples; the lit mist warmer than the sun's own colour and the
 * shadowed mist cooler; and the rays only as strong as the air in front of a place ("near
 * things"), so a fence or a trunk close to the camera stays clear.
 */
object GodRays {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    private const val SIZE = 800
    private const val STEPS = 96
    private const val DECAY = 0.985f
    private const val REACH = 0.9f
    private const val SHADE = 0.35f
    private const val SHORT_STEPS = 48
    private const val SHORT_DECAY = 0.93f
    private const val SHORT_REACH = 0.25f
    /** Sunlight seen through haze, a little warmer than the sun itself (sRGB 1, 0.88, 0.70 in linear ProPhoto, luminance 1). */
    private val WARM = floatArrayOf(1.0250406f, 0.9898964f, 0.8107369f)
    /** The shadowed mist: red dims most, blue least — a cooler shade. */
    private val COOL = floatArrayOf(1.15f, 1f, 0.8f)

    /** Whether this light makes god rays: a placed sun with its slider up. */
    fun wanted(look: RaysLook): Boolean = look.placed && look.type == "sun" && look.godRays > 0f

    /**
     * One sun's streaks on the small copy, normalised so its brightest are ~1, the levels it is laid
     * with, and [air]: how much air lies in front of each place, 0 (close to the camera) … 1 (far).
     */
    class Map(val w: Int, val h: Int, val r: FloatArray, val top: Float, val level: Float, val look: RaysLook, val air: FloatArray)

    /** [src] as the lights have left it, before the fog; [input] its depth. Empty when no light wants rays. */
    fun prepare(src: Develop.Source, lights: List<RaysLook>, input: Sun.Input, log: (String) -> Unit = {}): List<Map> {
        val suns = lights.filter { wanted(it) }
        if (suns.isEmpty()) return emptyList()
        val t0 = System.currentTimeMillis()
        val w = src.width; val h = src.height
        val k = max(1, max(w, h) / SIZE); val sw = max(1, w / k); val sh = max(1, h / k)
        // the small copy's brightness: box averages of k × k
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val y = FloatArray(sw * sh)
        for (j in 0 until sh) for (i in 0 until sw) {
            var sum = 0f
            for (yy in j * k until j * k + k) for (xx in i * k until i * k + k) {
                val o = (yy * w + xx) * 3
                sum += LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2)
            }
            y[j * sw + i] = sum / (k * k)
        }
        // how far each place is, sharpened by the photo so leaves and branches keep their gaps
        val near = Air.nearOn(input.depth, Depth.SIZE, sw, sh)
        val q0 = FloatArray(sw * sh) { Air.distance(near[it]).first }
        val s = FloatArray(sw * sh) { sqrt(max(y[it], 0f)) }
        val q = guided(s, q0, sw, sh, max(1, (2f * max(sw, sh) / 128f).roundToInt()), 0.02f * 0.02f)
        val far = FloatArray(sw * sh) { ((q[it].coerceIn(0f, 1f) - 0.55f) / 0.3f).coerceIn(0f, 1f) }
        val farY = y.filterIndexed { i, _ -> far[i] > 0.5f }.toFloatArray()
        val top = if (farY.size > 50) Air.quantile(farY, 0.9f) else Air.quantile(y, 0.98f)
        val open = FloatArray(sw * sh) { far[it] * ((y[it] / max(top, 1e-6f) - 0.45f) / 0.4f).coerceIn(0f, 1f) }
        val top995 = Air.quantile(y, 0.995f)
        val level = 0.5f * Air.quantile(input.lum, 0.95f).coerceAtLeast(0.02f)
        // the air in front of each place (61b): the distance sharpened once more by the photo, finely,
        // so the rays stop at a fence or a trunk close to the camera
        val qc = FloatArray(sw * sh) { q[it].coerceIn(0f, 1f) }
        val qf = guided(s, qc, sw, sh, max(1, (6f * max(sw, sh) / 1440f).roundToInt()), 1e-4f)
        val air = FloatArray(sw * sh) { val t = ((qf[it].coerceIn(0f, 1f) - 0.05f) / 0.35f).coerceIn(0f, 1f); t * t * (3f - 2f * t) }
        val maps = suns.map { look ->
            val long = normalised(streaks(open, sw, sh, look.u, look.v, STEPS, DECAY, REACH))
            val short = normalised(streaks(open, sw, sh, look.u, look.v, SHORT_STEPS, SHORT_DECAY, SHORT_REACH))
            val r = FloatArray(sw * sh) { (0.75f * long[it] + 0.6f * short[it]).coerceIn(0f, 1.5f) }
            Map(sw, sh, r, top995, level, look, air)
        }
        log("god rays worked out in ${System.currentTimeMillis() - t0} ms")
        return maps
    }

    /**
     * From each place, the open sky gathered along the way towards the sun at (su, sv). The sun
     * may be outside the picture (61a): past the edge counts as nothing — we cannot see what is
     * there, and repeating the edge greyed a whole bridge photo in the proof — and the walk is at
     * most one diagonal long, so a far sun does not stretch the streaks.
     */
    internal fun streaks(open: FloatArray, w: Int, h: Int, su: Float, sv: Float, steps: Int, decay: Float, reachMax: Float): FloatArray {
        val out = FloatArray(w * h)
        val px = su * w; val py = sv * h
        val wt = FloatArray(steps) { decay.pow(it) }; val wsum = wt.sum()
        val diag = sqrt((w * w + h * h).toFloat())
        for (j in 0 until h) for (i in 0 until w) {
            val dx = px - i; val dy = py - j
            val reach = min(reachMax, diag / max(sqrt(dx * dx + dy * dy), 1e-6f))
            val j0 = jitter(i, j)                                     // each place starts its walk a little later (61b)
            var acc = 0f
            for (s in 0 until steps) {
                val t = (s + j0) / steps * reach
                val x = i + dx * t; val y = j + dy * t
                if (x < -0.5f || x > w - 0.5f || y < -0.5f || y > h - 0.5f) continue
                acc += wt[s] * bilinear(open, w, h, x, y)
            }
            out[j * w + i] = acc / wsum
        }
        return out
    }

    /** A fixed, even-looking number in 0..1 for each place: an integer hash, the same on every run. */
    internal fun jitter(i: Int, j: Int): Float {
        var x = (i * 73856093) xor (j * 19349663)
        x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15)
        return (x ushr 8) / 16777216f
    }

    /** [r] divided by its 99.5th percentile, so its brightest are ~1. */
    private fun normalised(r: FloatArray): FloatArray {
        val n = max(Air.quantile(r, 0.995f), 1e-6f)
        return FloatArray(r.size) { r[it] / n }
    }

    /** [a] at pixel coordinates (x, y) — pixel centres at whole numbers — clamped at the edges. */
    internal fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0; val fy = yc - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }

    /** An edge-keeping (guided) filter of [p] by [g] on a small grid, the window shrinking at the edges. */
    private fun guided(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        fun box(a: FloatArray): FloatArray {
            val c = DoubleArray((w + 1) * (h + 1))
            for (j in 0 until h) { var row = 0.0; for (i in 0 until w) { row += a[j * w + i]; c[(j + 1) * (w + 1) + i + 1] = c[j * (w + 1) + i + 1] + row } }
            return FloatArray(w * h) { k ->
                val i = k % w; val j = k / w
                val x0 = max(0, i - r); val x1 = min(w, i + r + 1); val y0 = max(0, j - r); val y1 = min(h, j + r + 1)
                ((c[y1 * (w + 1) + x1] - c[y0 * (w + 1) + x1] - c[y1 * (w + 1) + x0] + c[y0 * (w + 1) + x0]) / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        val mI = box(g); val mP = box(p)
        val mIP = box(FloatArray(w * h) { g[it] * p[it] }); val mII = box(FloatArray(w * h) { g[it] * g[it] })
        val a = FloatArray(w * h) { (mIP[it] - mI[it] * mP[it]) / (mII[it] - mI[it] * mI[it] + eps) }
        val b = FloatArray(w * h) { mP[it] - a[it] * mI[it] }
        val ma = box(a); val mb = box(b)
        return FloatArray(w * h) { ma[it] * g[it] + mb[it] }
    }

    /** Lays the rays over [src] after the fog and the light in the air; [fogMask] × [fogAmount] says where the mist is. */
    fun apply(src: Develop.Source, maps: List<Map>, fogMask: ExposureMap?, fogAmount: Float, log: (String) -> Unit = {}) {
        if (maps.isEmpty()) return
        log("god rays (${maps.size})")
        val w = src.width; val h = src.height
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        // the rays' colour: the sun's own, warmed a little as sunlight in haze is, kept at luminance 1
        val chroma = maps.map { m ->
            val c = m.look.chroma(); val wr = c[0] * WARM[0]; val wg = c[1] * WARM[1]; val wb = c[2] * WARM[2]
            val l = max(LUM[0] * wr + LUM[1] * wg + LUM[2] * wb, 1e-6f)
            floatArrayOf(wr / l, wg / l, wb / l)
        }
        val mist = fogMask?.takeIf { !it.isBlank }; val amount = fogAmount.coerceIn(0f, 1f)   // as the fog reads them
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val veil = mist?.let { 1f - exp(-it.sample(u, v) * amount) } ?: 0f
                val haze = 0.25f + 0.75f * veil                          // rays need air to show in
                val o = (y * w + x) * 3
                var r = f.get(o); var g = f.get(o + 1); var b = f.get(o + 2)
                for ((m, map) in maps.withIndex()) {
                    val rn = bilinear(map.r, map.w, map.h, u * map.w - 0.5f, v * map.h - 0.5f)
                    val strength = map.look.godRays
                    // only as much as the air in front of this place: near things stay clear, as far as "near things" says
                    val near = map.look.godNear.coerceIn(0f, 1f)
                    val front = near + (1f - near) * bilinear(map.air, map.w, map.h, u * map.w - 0.5f, v * map.h - 0.5f)
                    val hz = haze * front
                    val shade = SHADE * min(strength, 1f) * hz * (1f - min(rn, 1f))
                    val now = LUM[0] * r + LUM[1] * g + LUM[2] * b
                    val room = (1f - now / (1.1f * map.top + 1e-6f)).coerceIn(0f, 1f).pow(1.5f)
                    val add = strength * 2f * map.level * rn * hz * room
                    val c = chroma[m]
                    r = r * (1f - shade * COOL[0]) + add * c[0]; g = g * (1f - shade * COOL[1]) + add * c[1]; b = b * (1f - shade * COOL[2]) + add * c[2]
                }
                f.put(o, r); f.put(o + 1, g); f.put(o + 2, b)
            }
        }
    }
}
