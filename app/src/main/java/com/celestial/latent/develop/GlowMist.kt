package com.celestial.latent.develop

import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Glowing mist (62b): the fog lit by the lights placed in it. Plain fog has one even light — its
 * colour at the brightness of the distance ([Fog.light]) — so the mist beside a sun or a lamp was
 * no brighter than the mist on the far side. Real mist is lit by the light inside it: bright and in
 * the light's colour towards it, cool and dim away from it, brightest looking towards the sun
 * (droplets throw light forwards: Henyey–Greenstein, g = 0.6, as dusk and the beams), and darker
 * where something stands between it and the light.
 *
 * The fog's light becomes one colour per mask cell, laid over the photo as the mask is:
 *
 *     light = (1 − lit) · today's + lit · (sky + suns + lamps)
 *     sky   = level · 0.4 · cool (10,000K)                       the mist lit by the sky alone
 *     sun   = level · 0.6 · warm sun · forward scattering · open way to the sun
 *     lamp  = the lamp's own brightness · its glow in the air along each line of sight
 *
 * so at lit 0 the fog is exactly today's. "Open way to the sun": from each cell the way towards the
 * sun's place is walked over open sky (far and bright, as the god rays find it), nearer counting
 * more — mist behind fronds and trunks stays in their shade. A sun set "in front" (behind the
 * camera) lights the mist from behind you: dimmer and cooler, as real mist is then. A lamp is a
 * point at its place and depth; each line of sight is walked through the mist, gathering its light
 * as it falls with distance (1/(r² + r0²), r0 from the light's reach) and is dimmed by the mist.
 *
 * Proved in Python on Celestial's palm, the forest path and the night lamps
 * (`glowing-mist/scripts/glow.py`), then this port against it. Kept free of Android.
 */
object GlowMist {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    private const val G = 0.6f                                    // forward scattering, as dusk and the beams
    private const val AMB = 0.4f                                  // the share of the mist's light from the sky alone
    private const val CAP = 8f                                    // the brightest towards a sun, against the average
    private const val GATHER = 1.6f                               // the glow drawn in towards the sun, as Beams' "gathered"
    private const val FOV = 70.0                                  // across the long edge, as Air.glow
    private const val COOL_MIRED = 100f                           // skylight, 10,000K
    private const val LAMP_STEPS = 48
    private const val WALK_STEPS = 40
    private const val WALK_DECAY = 0.95f
    /** Sunlight seen through haze, a little warmer than the sun itself (as GodRays). */
    private val WARM = floatArrayOf(1.0250406f, 0.9898964f, 0.8107369f)

    /** Whether a light lights the mist: any placed light that adds its own light. */
    fun wanted(look: RaysLook): Boolean = look.placed && look.amount > 0f && look.mode != "gaps"

    /**
     * One light as the mist sees it. [colour] at luminance 1. A sun: its place ([u], [v], may be off
     * the picture) and [front] (−1 behind the camera … +1 behind the subject). A lamp: its place,
     * [strength] (1 at the usual brightness) and [reach] (0 … 1, how far its glow spreads).
     */
    class Light(val sun: Boolean, val u: Float, val v: Float, val front: Float, val colour: FloatArray, val strength: Float, val reach: Float)

    /** The mist's light per mask cell, linear RGB, and how to read it anywhere on the picture. */
    class Field(val w: Int, val h: Int, val rgb: Array<FloatArray>) {
        fun sample(c: Int, u: Float, v: Float): Float {
            val a = rgb[c]
            val x = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat()); val y = (v * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = x - x0; val fy = y - y0
            return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
        }
    }

    /** The app's lights as the mist sees them; a lamp's colour is the glow it spills round itself in the photo, tinted by the light's colour. */
    fun lights(looks: List<RaysLook>, f: FloatBuffer, w: Int, h: Int): List<Light> = looks.filter { wanted(it) }.map { l ->
        if (l.type == "sun") {
            val c = l.chroma(); val wc = FloatArray(3) { c[it] * WARM[it] }
            Light(true, l.u, l.v, l.front, Fog.capped(unit(wc)), 1f, 0f)
        } else {
            val photo = lampColour(f, w, h, l.u, l.v); val c = l.chroma()
            Light(false, l.u, l.v, 0f, Fog.capped(unit(FloatArray(3) { photo[it] * c[it] }), 0.6f), l.amount / 0.3f, l.reach)
        }
    }

    /**
     * The mist's light on a [gw] × [gh] mask grid. [f] the picture (interleaved linear RGB, [w] × [h]),
     * [depth] the depth model's [size] × [size] nearness, [a] today's fog light, [glow] dusk's glow
     * per cell (or null), [lit] 0 … 1, [thick] how thick the air is against the proven atmosphere.
     */
    fun field(f: FloatBuffer, w: Int, h: Int, gw: Int, gh: Int, depth: FloatArray, size: Int, a: FloatArray, glow: FloatArray?,
              lit: Float, thick: Float, lights: List<Light>): Field {
        val n = gw * gh
        val m = lit.coerceIn(0f, 1f)
        val today = Array(3) { c -> FloatArray(n) { k -> a[c] * (glow?.get(k) ?: 1f) } }
        if (m <= 0f || lights.isEmpty()) return Field(gw, gh, today)
        val near = Air.nearOn(depth, size, gw, gh)
        val q = FloatArray(n) { Air.distance(near[it]).first }
        val z = FloatArray(n) { Air.distance(near[it]).second }
        val gy = cellMeans(f, w, h, gw, gh)
        val level = LUM[0] * a[0] + LUM[1] * a[1] + LUM[2] * a[2]
        val cool = Fog.capped(Fog.tintFor(COOL_MIRED))
        val out = Array(3) { c -> FloatArray(n) { level * AMB * cool[c] } }
        // each cell's line of sight; the long edge spans FOV
        val long = max(gw, gh).toFloat(); val fl = (0.5 / tan(Math.toRadians(FOV / 2))).toFloat()
        val dx = FloatArray(n); val dy = FloatArray(n); val dz = FloatArray(n)
        for (k in 0 until n) {
            val x = (((k % gw) + 0.5f) / gw - 0.5f) * gw / long; val y = (((k / gw) + 0.5f) / gh - 0.5f) * gh / long
            val l = sqrt(x * x + y * y + fl * fl); dx[k] = x / l; dy[k] = y / l; dz[k] = fl / l
        }
        fun dirOf(u: Float, v: Float): FloatArray {
            val x = (u - 0.5f) * gw / long; val y = (v - 0.5f) * gh / long; val l = sqrt(x * x + y * y + fl * fl)
            return floatArrayOf(x / l, y / l, fl / l)
        }
        val hg90 = hg(0f)
        val suns = lights.count { it.sun }
        // open sky, far and bright (as GodRays), for the walk towards a sun
        val open: FloatArray? = if (suns == 0) null else {
            val far = FloatArray(n) { ((q[it] - 0.55f) / 0.3f).coerceIn(0f, 1f) }
            val farY = gy.filterIndexed { i, _ -> far[i] > 0.5f }.toFloatArray()
            val top = max(if (farY.size > 20) Air.quantile(farY, 0.9f) else Air.quantile(gy, 0.98f), 1e-9f)
            FloatArray(n) { far[it] * ((gy[it] / top - 0.45f) / 0.4f).coerceIn(0f, 1f) }
        }
        val sig = 1.4f / (Air.RANGE - 1f) * thick
        for (L in lights) {
            if (L.sun) {
                // a sun "in front" (towards the camera) is mirrored behind it, by as much
                val d0 = dirOf(L.u, L.v); val s = if (L.front >= 0f) 1f else 1f + 2f * L.front.coerceAtLeast(-1f)
                val sl = sqrt(d0[0] * d0[0] + d0[1] * d0[1] + d0[2] * d0[2] * s * s)
                val sx = d0[0] / sl; val sy = d0[1] / sl; val sz = d0[2] * s / sl
                val p = FloatArray(n) { hg(dx[it] * sx + dy[it] * sy + dz[it] * sz) / hg90 }
                var mean = p.average().toFloat()
                if (mean > 1f) {                                    // the sun in front: the glow gathered towards it, the average kept
                    for (k in 0 until n) p[k] = (p[k] / mean).pow(GATHER)
                    mean = p.average().toFloat()
                    for (k in 0 until n) p[k] /= mean
                }
                val vis = if (s < 1f) FloatArray(n) { 1f } else walkVis(open!!, gw, gh, L.u, L.v)
                val share = level * (1f - AMB) / suns
                for (k in 0 until n) { val e = share * min(p[k], CAP) * vis[k]; for (c in 0 until 3) out[c][k] += e * L.colour[c] }
            } else {
                val d0 = dirOf(L.u, L.v)
                val di = (L.v * size).toInt().coerceIn(0, size - 1) * size + (L.u * size).toInt().coerceIn(0, size - 1)
                val zl = Air.distance(depth[di]).second
                val lx = d0[0] * zl; val ly = d0[1] * zl; val lz = d0[2] * zl
                val r0 = (0.05f + 0.25f * L.reach.coerceIn(0f, 1f)) * zl * 0.25f
                val bright = lampBrightness(f, w, h, L.u, L.v)
                val kk = 10f * L.strength * r0 * r0                // 10 × the lamp at its core, at the usual strength
                for (k in 0 until n) {
                    val zz = min(z[k], Air.RANGE)
                    var acc = 0f; var ws = 0f
                    for (i in 0 until LAMP_STEPS) {
                        val s = (i + 0.5f) / LAMP_STEPS * zz
                        val tx = lx - dx[k] * s; val ty = ly - dy[k] * s; val tz = lz - dz[k] * s
                        val r2 = tx * tx + ty * ty + tz * tz; val rn = sqrt(r2) + 1e-9f
                        val cos = (dx[k] * tx + dy[k] * ty + dz[k] * tz) / rn
                        val e = 1f / (r2 + r0 * r0) * exp(-sig * rn)
                        val wgt = exp(-sig * s) * sig * zz / LAMP_STEPS
                        acc += wgt * e * hg(cos) / hg90; ws += wgt
                    }
                    val g = bright * kk * acc / max(ws, 1e-9f)
                    for (c in 0 until 3) out[c][k] += g * L.colour[c]
                }
            }
        }
        return Field(gw, gh, Array(3) { c -> FloatArray(n) { (1f - m) * today[c][it] + m * out[c][it] } })
    }

    private fun hg(cos: Float): Float = ((1 - G * G) / (1 + G * G - 2 * G * cos).toDouble().pow(1.5)).toFloat()

    private fun unit(c: FloatArray): FloatArray { val y = max(LUM[0] * c[0] + LUM[1] * c[1] + LUM[2] * c[2], 1e-6f); return FloatArray(3) { c[it] / y } }

    /** The picture's brightness averaged over each cell (cell edges at whole pixels, floor(j·h/gh)). */
    internal fun cellMeans(f: FloatBuffer, w: Int, h: Int, gw: Int, gh: Int): FloatArray {
        val sum = DoubleArray(gw * gh); val cnt = IntArray(gw * gh)
        val xs = IntArray(w); var cx = 0
        for (x in 0 until w) { while ((cx + 1) * w / gw <= x) cx++; xs[x] = cx }
        var cy = 0
        for (y in 0 until h) {
            while ((cy + 1) * h / gh <= y) cy++
            for (x in 0 until w) {
                val o = (y * w + x) * 3; val k = cy * gw + xs[x]
                sum[k] += (LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2)).toDouble(); cnt[k]++
            }
        }
        return FloatArray(gw * gh) { (sum[it] / max(cnt[it], 1)).toFloat() }
    }

    /** The window around a lamp's place: [r] of the picture each way. */
    private fun window(w: Int, h: Int, u: Float, v: Float, r: Float): IntArray = intArrayOf(
        ((u - r) * w).toInt().coerceIn(0, w - 1), ((u + r) * w).toInt().coerceIn(1, w),
        ((v - r) * h).toInt().coerceIn(0, h - 1), ((v + r) * h).toInt().coerceIn(1, h))

    private fun windowLum(f: FloatBuffer, w: Int, h: Int, u: Float, v: Float, r: Float): Pair<FloatArray, IntArray> {
        val (x0, x1, y0, y1) = window(w, h, u, v, r)
        val l = FloatArray(max(0, x1 - x0) * max(0, y1 - y0)); var k = 0
        for (y in y0 until y1) for (x in x0 until x1) { val o = (y * w + x) * 3; l[k++] = LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2) }
        return l to intArrayOf(x0, x1, y0, y1)
    }

    /** A lamp's brightness: the brightest 5% within 3% of the picture around its place, as the photo recorded it. */
    internal fun lampBrightness(f: FloatBuffer, w: Int, h: Int, u: Float, v: Float): Float {
        val (l, _) = windowLum(f, w, h, u, v, 0.03f)
        return if (l.isEmpty()) 0f else Air.quantile(l, 0.95f)
    }

    /**
     * A lamp's own colour, at luminance 1: the light it spills around itself — the middle-bright
     * pixels (between the median and the brightest 10%) within 8% of the picture of its place. Its
     * glass is usually burnt out to near white; the glow round it keeps the lamp's colour.
     */
    internal fun lampColour(f: FloatBuffer, w: Int, h: Int, u: Float, v: Float): FloatArray {
        val (l, b) = windowLum(f, w, h, u, v, 0.08f)
        if (l.isEmpty()) return floatArrayOf(1f, 1f, 1f)
        val lo = Air.quantile(l, 0.5f); val hi = Air.quantile(l, 0.9f); val s = DoubleArray(3)
        for (y in b[2] until b[3]) for (x in b[0] until b[1]) {
            val o = (y * w + x) * 3
            val y0 = LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2)
            if (y0 >= lo && y0 <= hi) { for (c in 0 until 3) s[c] += f.get(o + c).toDouble() }
        }
        val c = FloatArray(3) { s[it].toFloat() }
        return if (LUM[0] * c[0] + LUM[1] * c[1] + LUM[2] * c[2] <= 1e-9f) floatArrayOf(1f, 1f, 1f) else unit(c)
    }

    /**
     * From each cell towards the sun at (su, sv): the share of open sky on the way, nearer counting
     * more. Past the picture's edge counts as open — nothing seen there blocks the sun. Softened by
     * a 3 × 3 average and scaled so open sky reads as fully lit.
     */
    internal fun walkVis(open: FloatArray, gw: Int, gh: Int, su: Float, sv: Float): FloatArray {
        val px = su * gw - 0.5f; val py = sv * gh - 0.5f
        val diag = sqrt((gw * gw + gh * gh).toFloat())
        val wt = FloatArray(WALK_STEPS) { WALK_DECAY.pow(it + 1) }; val wsum = wt.sum()
        val v = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val ddx = px - i; val ddy = py - j; val dist = sqrt(ddx * ddx + ddy * ddy) + 1e-6f
            val reach = min(dist, 0.6f * diag)
            var acc = 0f
            for (s in 1..WALK_STEPS) {
                val t = s.toFloat() / WALK_STEPS * reach / dist
                val x = i + ddx * t; val y = j + ddy * t
                val inside = x >= -0.5f && x <= gw - 0.5f && y >= -0.5f && y <= gh - 0.5f
                val o = if (inside) open[y.roundToInt().coerceIn(0, gh - 1) * gw + x.roundToInt().coerceIn(0, gw - 1)] else 1f
                acc += wt[s - 1] * o
            }
            v[j * gw + i] = acc / wsum
        }
        val b = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            var s = 0f; var c = 0
            for (y in max(0, j - 1)..min(gh - 1, j + 1)) for (x in max(0, i - 1)..min(gw - 1, i + 1)) { s += v[y * gw + x]; c++ }
            b[j * gw + i] = s / c
        }
        val top = max(Air.quantile(b, 0.95f), 1e-6f)
        return FloatArray(gw * gh) { (b[it] / top).coerceIn(0f, 1f) }
    }
}
