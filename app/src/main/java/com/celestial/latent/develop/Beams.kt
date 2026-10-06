package com.celestial.latent.develop

import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Light in the air, in 3D (step 54a): the same lights that light the surfaces, glowing in hazy
 * air with real shadows from the photo's depth — replacing the old flat "add light" glow (which
 * made a sun into a gradient). Proven first on the cat's backlight and on sun through a window.
 *
 * For each pixel, the line of sight from the camera to the surface seen there is walked through
 * the air; at each step every light adds what reaches that point — the sun along its direction,
 * a lamp falling with distance, a spot within its cone, an area panel from its front face — if
 * nothing in the depth blocks it, weighted by forward scattering (haze sends light on mostly in
 * the direction it was going, so beams glow brightest looking towards the light).
 *
 * Haze: everywhere, or only where fog is painted ("always ↔ only in fog"); "smooth ↔ dusty" adds
 * streaks running along the light, as dust in a sunbeam does; "short ↔ long" is how far a lamp's
 * glow spreads. "Through gaps" — the photo's own bright parts streaming — stays 2D in [Rays].
 */
object Beams {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)
    private const val G = 0.6f                                      // forward scattering, as in the proofs

    /** Whether this light glows in the air in 3D (with the depth model; otherwise the 2D glow stands in). */
    fun wanted(look: RaysLook): Boolean = look.placed && look.amount > 0f && look.mode != "gaps"

    private val cache = object : LinkedHashMap<String, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 8
    }

    /**
     * One light's glow in the air at GRID size, normalised so its brightest beams are ~1;
     * remembered until the light, the photo, the scene's size or the fog changes.
     */
    fun air(input: Sun.Input, look: RaysLook, sceneScale: Float, fogMask: ExposureMap?, log: (String) -> Unit = {}): FloatArray? {
        if (!wanted(look)) return null
        val fogKey = fogMask?.let { "${it.width}x${it.height}:${it.stops.contentHashCode()}" } ?: "-"
        val key = "${System.identityHashCode(input)}|${look.key()}|${"%.3f".format(java.util.Locale.US, sceneScale)}|$fogKey"
        synchronized(cache) { cache[key] }?.let { return it }
        val t0 = System.currentTimeMillis()
        val sc = Sun.scene(input, sceneScale)
        val gw = sc.gw; val gh = sc.gh; val n = gw * gh
        val l = look.withDefaults()
        val far = sc.far
        val sigma = 1.4f / far                                       // haze: ~1.4 of optical depth across the scene
        val N = 32; val M = 12
        val rng = java.util.Random(11)
        // the light's geometry
        val sunDir = if (l.type == "sun") Sun.toward(l, gw.toFloat() / gh) ?: return null else null
        // a sun through a window: only through its panes, only in the room — the frame and bars stripe the shaft
        val op = if (sunDir != null) Sun.opening(sc, input, l) else null
        val L = if (l.type != "sun") Sun.pointAt(sc, l.u, l.v, l.nudge) else null
        // how far a lamp's glow spreads: ~10 cm … 1.5 m in a room (tested: a wider range made a veil, not a glow)
        val lenM = (0.02f + 0.3f * l.length) * far
        val aim: FloatArray? = if ((l.type == "spot" || l.type == "area") && !l.u2.isNaN() && L != null) {
            val a = Sun.pointAt(sc, l.u2, l.v2, 0f)
            val ax = a[0] - L[0]; val ay = a[1] - L[1]; val az = a[2] - L[2]; val an = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
            floatArrayOf(ax / an, ay / an, az / an)
        } else null
        // an area panel's own axes and half sizes (as on surfaces)
        var ux = 0f; var uz = 0f; var vx = 0f; var vy = 0f; var vz = 0f; var hw = 0f; var hh = 0f
        if (l.type == "area" && L != null && aim != null) {
            ux = -aim[2]; uz = aim[0]; val un = sqrt(ux * ux + uz * uz); if (un < 1e-4f) { ux = 1f; uz = 0f } else { ux /= un; uz /= un }
            vx = aim[1] * uz; vy = aim[2] * ux - aim[0] * uz; vz = -aim[1] * ux
            hw = l.aw / 2f * gw / sc.f * L[2]; hh = l.ah / 2f * gh / sc.f * L[2]
        }
        fun blocked(px: Float, py: Float, pz: Float, dx: Float, dy: Float, dz: Float, span: Float, jit: Float): Float {
            var vis = 1f
            for (s in 1..M) {
                val t = span * ((s - 1 + jit) / M)
                val qx = px + dx * t; val qy = py + dy * t; val qz = pz + dz * t
                if (qz <= 0.05f) continue
                val qu = (qx / qz * sc.f + sc.cx).toInt(); val qv = (qy / qz * sc.f + sc.cy).toInt()
                if (qu < 0 || qu >= gw || qv < 0 || qv >= gh) continue
                val j = qv * gw + qu; val zs = sc.Zs[j]; val th = max(sc.T[j], 1.5f * span / M)
                val b = ((qz - zs - 0.002f * far) / (0.005f * far)).coerceIn(0f, 1f) * ((zs + th - qz) / max(0.1f * th, 0.005f)).coerceIn(0f, 1f)
                vis *= 1f - b
                if (vis < 0.01f) break
            }
            return vis
        }
        val air = FloatArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            // haze along this line of sight: everywhere, or leaning on the painted fog
            val fogHere = fogMask?.sample((x + 0.5f) / gw, (y + 0.5f) / gh)?.coerceIn(0f, 1f) ?: 0f
            val dens = (1f - l.fogOnly) + l.fogOnly * fogHere
            if (dens <= 0f) { rng.nextFloat(); continue }
            var rx = (x + 0.5f - sc.cx) / sc.f; var ry = (y + 0.5f - sc.cy) / sc.f; var rz = 1f
            val rl = sqrt(rx * rx + ry * ry + rz * rz)
            val dist = min(sc.Z[i], far) * rl
            rx /= rl; ry /= rl; rz /= rl
            val ds = dist / N; val jit = rng.nextFloat()
            var trans = 1f; var sum = 0f
            for (k in 0 until N) {
                val s = ds * (k + jit)
                val px = rx * s; val py = ry * s; val pz = rz * s
                var lit = 0f; var tx = 0f; var ty = 0f; var tz = 0f       // light arriving, and the way it travels
                if (sunDir != null) {
                    lit = if (op != null) Sun.throughOpening(sc, op, px, py, pz, sunDir, rng.nextFloat(), M)
                        else blocked(px, py, pz, sunDir[0], sunDir[1], sunDir[2], 0.375f * far, rng.nextFloat())
                    tx = -sunDir[0]; ty = -sunDir[1]; tz = -sunDir[2]
                } else if (L != null) {
                    var lx = L[0]; var ly = L[1]; var lz = L[2]
                    if (l.type == "area" && aim != null) {                // a random point on the panel: soft
                        val a = (rng.nextFloat() * 2f - 1f) * hw; val b = (rng.nextFloat() * 2f - 1f) * hh
                        lx += ux * a + vx * b; ly += vy * b; lz += uz * a + vz * b
                    }
                    val dx0 = lx - px; val dy0 = ly - py; val dz0 = lz - pz
                    val r = sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0).coerceAtLeast(1e-4f)
                    val dx = dx0 / r; val dy = dy0 / r; val dz = dz0 / r
                    var gate = 1f
                    if (aim != null) {
                        val c = -(dx * aim[0] + dy * aim[1] + dz * aim[2])
                        gate = if (l.type == "area") c.coerceAtLeast(0f) else {
                            val ang = kotlin.math.acos(c.coerceIn(-1f, 1f))
                            val t = ((ang - l.cone * 0.8f) / (l.cone * 0.4f)).coerceIn(0f, 1f); 1f - t * t * (3f - 2f * t)
                        }
                    }
                    if (gate > 0f) {
                        val fall = lenM * lenM / (r * r + lenM * lenM)
                        lit = gate * fall * blocked(px, py, pz, dx, dy, dz, max(r - 0.03f * far, 0f), rng.nextFloat())
                    }
                    tx = -dx; ty = -dy; tz = -dz
                }
                if (lit > 0f) {
                    // forward scattering: brightest where the light travels towards the camera
                    val cosT = -(tx * rx + ty * ry + tz * rz)
                    val hg = (1f - G * G) / (4f * 3.14159265f * Math.pow((1f + G * G - 2f * G * cosT).toDouble(), 1.5).toFloat())
                    var streak = 1f
                    if (l.dust > 0f) streak = (1f - l.dust) + l.dust * 2f * streakNoise(px, py, pz, tx, ty, tz, far)
                    sum += sigma * dens * streak * lit * hg * trans * ds
                }
                trans *= exp(-sigma * dens * ds)
            }
            air[i] = sum
        }
        val smoothed = Sun.depthSmooth(air, sc.Z, gw, gh, 2, 0.08f)
        val top = smoothed.copyOf().also { it.sort() }[((n - 1) * 0.995f).toInt()].coerceAtLeast(1e-9f)
        val out = FloatArray(n) { (smoothed[it] / top).coerceIn(0f, 2f) }
        log("${l.type} in the air worked out in ${System.currentTimeMillis() - t0} ms")
        return out.also { synchronized(cache) { cache[key] = it } }
    }

    /**
     * Dust in a beam: smooth noise on the plane across the light's travel, so streaks run along
     * the light, as they do in a dusty sunbeam. Mean 0.5.
     */
    private fun streakNoise(px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float, far: Float): Float {
        // two axes across the travel direction
        var ax = ty; var ay = -tx; var az = 0f
        var an = sqrt(ax * ax + ay * ay); if (an < 1e-4f) { ax = 1f; ay = 0f; an = 1f }
        ax /= an; ay /= an
        val bx = ty * az - tz * ay; val by = tz * ax - tx * az; val bz = tx * ay - ty * ax
        val k = 40f / far
        return valueNoise((px * ax + py * ay + pz * az) * k, (px * bx + py * by + pz * bz) * k)
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        fun h(a: Int, b: Int): Float { var v = a * 374761393 + b * 668265263; v = (v xor (v ushr 13)) * 1274126177; return ((v xor (v ushr 16)) and 0xFFFF) / 65535f }
        val a = h(xi, yi) * (1 - sx) + h(xi + 1, yi) * sx
        val b = h(xi, yi + 1) * (1 - sx) + h(xi + 1, yi + 1) * sx
        return a * (1 - sy) + b * sy
    }

    /**
     * Adds the lights' glow in the air, in front of everything (after the light on surfaces):
     * at full "in the air" a light's brightest beams reach half the photo's bright level, in its
     * own colour; the scene behind is veiled very slightly by the haze. The brush says where.
     */
    fun apply(src: Develop.Source, airs: List<Pair<RaysLook, FloatArray>>, mask: ExposureMap?, input: Sun.Input, log: (String) -> Unit = {}) {
        if (airs.isEmpty()) return
        log("light in the air (${airs.size})")
        val w = src.width; val h = src.height; val gw = input.gw; val gh = input.gh
        val sorted = input.lum.copyOf().also { it.sort() }
        val bright = 0.5f * sorted[((sorted.size - 1) * 0.95f).toInt()].coerceAtLeast(0.02f)
        val chroma = airs.map { it.first.chroma() }
        val veil = 1f - 0.15f * airs.maxOf { it.first.amount }       // the haze dims what is behind it, a little
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val cover = mask?.sample(u, v)?.coerceIn(0f, 3f) ?: 1f
                var ar = 0f; var ag = 0f; var ab = 0f
                if (cover > 0f) for ((k, pair) in airs.withIndex()) {
                    // gathered: the glow raised to a power, so it concentrates towards the light
                    // (a hand-made edit that read as real sunlight used about 1.6)
                    var g = sample(pair.second, gw, gh, u, v)
                    if (pair.first.gather > 0f && g > 0f) g = Math.pow(g.toDouble(), 1.0 + 1.2 * pair.first.gather).toFloat()
                    val a = g * pair.first.amount * bright * cover
                    if (a <= 0f) continue
                    ar += a * chroma[k][0]; ag += a * chroma[k][1]; ab += a * chroma[k][2]
                }
                val o = (y * w + x) * 3
                val m = if (cover > 0f) 1f - (1f - veil) * minOf(cover, 1f) else 1f     // more light does not thicken the air
                f.put(o, f.get(o) * m + ar); f.put(o + 1, f.get(o + 1) * m + ag); f.put(o + 2, f.get(o + 2) * m + ab)
            }
        }
    }

    private fun sample(a: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val x = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat()); val y = (v * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = x - x0; val fy = y - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }
}
