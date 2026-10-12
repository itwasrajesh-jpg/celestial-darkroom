package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Wet ground's settings (64a), kept with each photo like its fog colour. Where it is wet is painted
 * (the WET step's brush, [ExposureMaps.WET]): 0 dry … 1 wet … 2 standing water.
 */
data class WetLook(
    /** 0 a sharp mirror … 1 long soft streaks (rough ground). The proof's roughness 0.5 is 0.5. */
    val shine: Float = 0.5f,
    /** The reflection's strength against real water: ×1 as physics has it … ×4. */
    val reflection: Float = 1f,
    /** Patches of standing water that form by themselves in ground painted wet: 0 none … 0.6 of it. */
    val puddles: Float = 0.25f,
    /** Plants beside the water stand up out of the ground and show in it. */
    val plants: Boolean = true,
) {
    fun key(): String = listOf("v1", "%.3f".format(Locale.US, shine), "%.3f".format(Locale.US, reflection),
        "%.3f".format(Locale.US, puddles), if (plants) "1" else "0").joinToString("|")

    companion object {
        fun parse(s: String?): WetLook {
            if (s.isNullOrEmpty()) return WetLook()
            return runCatching {
                val p = s.split("|")
                require(p[0] == "v1")
                WetLook(p[1].toFloat().coerceIn(0f, 1f), p[2].toFloat().coerceIn(1f, 4f), p[3].toFloat().coerceIn(0f, 0.6f), p[4] == "1")
            }.getOrDefault(WetLook())
        }
    }
}

/** Each photo's wet settings, kept beside it like its masks. */
object WetLooks {
    private const val FILE = "latent_wet"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun load(context: Context, photo: Uri): WetLook = WetLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: WetLook) {
        prefs(context).edit().apply { if (look == WetLook()) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}

/**
 * Wet ground (64a): rain on the places the user paints — darker and richer where water soaks in,
 * a reflection of everything above it (stretched into vertical streaks on rough ground, sharp in
 * standing water), stronger at grazing angles, caught on the tops of the ground's own grain.
 *
 * The proof (`wet-streets/scripts/wet.py`, approved on Celestial's photos with the forest fixes)
 * made into one pass over a print of any size:
 *  1. The painted area is the ground: one flat plane through its points in 3D (the depth model's,
 *     at the light's scene size), worst points dropped, held level.
 *  2. Each ground pixel's line of sight is mirrored in that plane and marched through the depth
 *     until it meets something; rays that leave the picture take its edge (the top band for the
 *     sky). Highlights are stretched back out first (a JPEG squeezes a lamp into 1.0).
 *  3. Plants stand up: the depth sees a low verge as flat ground, so the march flew over it. Thick
 *     patches of leaves become half-metre walls, mirrored only by mud and water (forest fix).
 *  4. Rough ground blurs the reflection more the further away the reflected thing is: tall
 *     streaks, as wet asphalt draws a lamp into a pillar. Standing water is nearly a mirror.
 *  5. Fresnel (water, F0 0.02): weak looking down, strong looking along the ground.
 * All the fields that need neighbours are worked out on a grid ([GRID] long), then laid over the
 * full picture with each pixel's own grain for the glints — fine detail stays the photo's own.
 *
 * Applied before the film, after the lights on surfaces (a lamp's pool of light shows in the
 * water) and before the fog (the air is in front of the puddles).
 */
object Wet {
    /** The working grid's long side. */
    private const val GRID = 1280
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    private val PROPHOTO_TO_SRGB = floatArrayOf(
        2.0341926f, -0.7274198f, -0.3067728f,
        -0.2288247f, 1.2317259f, -0.0029012f,
        -0.0085557f, -0.1532907f, 1.1618464f,
    )
    /** The proof's scene: 47.5° across, 1.2 m … 15.5 m (Sun's scene size at 0.5). */
    private const val SCENE = 0.5f
    /** How far a clipped highlight is stretched back out. */
    private const val LIFT = 12f
    private const val STEPS = 64
    /** Blur levels for the reflection, in pixels of a 1600-wide picture. */
    private val LEVELS = floatArrayOf(0f, 2f, 6f, 16f, 36f)
    /** How tall a thick patch of plants stands, in metres. */
    private const val PLANT_HEIGHT = 0.5f

    /** True when [map] has anything painted wet. */
    fun wanted(map: ExposureMap?): Boolean = map != null && map.stops.any { it > 0.02f }

    /** Makes the painted ground wet, in place, before the film. Needs the photo's depth. */
    fun apply(src: Develop.Source, map: ExposureMap, look: WetLook, input: Sun.Input, log: (String) -> Unit = {}) {
        if (!wanted(map)) return
        val t0 = System.currentTimeMillis()
        log("wet ground")
        val f = src.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        process(f, src.width, src.height, map, look, input.depth, Depth.SIZE)
        android.util.Log.i("Latent", "wet: ${src.width}×${src.height} in ${System.currentTimeMillis() - t0} ms")
    }

    /**
     * The work itself, on plain buffers: [f] is the picture, three floats per pixel (linear
     * ProPhoto), [w] × [h]; [depth] the model's nearness, [ds] × [ds] (0 far … 1 near). In place.
     */
    internal fun process(f: FloatBuffer, w: Int, h: Int, map: ExposureMap, look: WetLook, depth: FloatArray, ds: Int) {
        // ---- the grid copy, and the scale of the proof's pixel sizes (it worked 1600 across)
        val gw: Int; val gh: Int
        if (w >= h) { gw = min(GRID, w); gh = max(1, (gw.toFloat() * h / w).roundToInt()) } else { gh = min(GRID, h); gw = max(1, (gh.toFloat() * w / h).roundToInt()) }
        val n = gw * gh
        val u = gw / 1600f                                   // as the proof: its sizes were for a picture 1600 across
        val small = FloatArray(n * 3); val count = IntArray(n)
        for (y in 0 until h) {
            val gy = (y.toLong() * gh / h).toInt().coerceAtMost(gh - 1)
            for (x in 0 until w) {
                val g = gy * gw + (x.toLong() * gw / w).toInt().coerceAtMost(gw - 1); val o = (y * w + x) * 3
                small[g * 3] += f.get(o); small[g * 3 + 1] += f.get(o + 1); small[g * 3 + 2] += f.get(o + 2); count[g]++
            }
        }
        for (g in 0 until n) { val c = max(count[g], 1); small[g * 3] /= c; small[g * 3 + 1] /= c; small[g * 3 + 2] /= c }
        // a JPEG's white is 1.0; a RAW may run past it: the highlights are measured from the top
        val peaks = FloatArray(n) { max(small[it * 3], max(small[it * 3 + 1], small[it * 3 + 2])) }.also { it.sort() }
        val e = 1f / max(peaks[((n - 1) * 0.999f).toInt()], 1f)
        for (i in small.indices) small[i] *= e
        val lh = FloatArray(n * 3) { hdr(small[it]) }

        // ---- where it is wet, on the grid
        val wv = FloatArray(n) { map.sample(((it % gw) + 0.5f) / gw, ((it / gw) + 0.5f) / gh) }
        val ground = BooleanArray(n) { wv[it] > 0.05f }
        if (ground.count { it } < 50) return

        // ---- the scene in 3D (as Sun.scene)
        val hfov = 20f + 55f * SCENE
        val near = (0.6f * 4.0.pow(SCENE.toDouble())).toFloat(); val far = (4f * 15.0.pow(SCENE.toDouble())).toFloat()
        val fpx = (gw / 2f) / tan(Math.toRadians(hfov / 2.0)).toFloat(); val cx = gw / 2f; val cy = gh / 2f
        val nearness = FloatArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val sx = ((x + 0.5f) * ds / gw - 0.5f).coerceIn(0f, (ds - 1).toFloat()); val sy = ((y + 0.5f) * ds / gh - 0.5f).coerceIn(0f, (ds - 1).toFloat())
            val dn = idx(depth, ds, ds, sx, sy)
            nearness[y * gw + x] = 1f / far + dn * (1f / near - 1f / far)          // 1 / Z
        }
        // thin near things set no shape: an opening of the nearness, then a light blur
        val k = max(3, gw / 90).let { if (it % 2 == 0) it + 1 else it }
        val opened = minMax(minMax(nearness, gw, gh, k, true), gw, gh, k, false)
        val zb = gauss(FloatArray(n) { 1f / max(opened[it], 1e-6f) }, gw, gh, 2.5f * u, 2.5f * u)
        val px = FloatArray(n * 3)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x; val z = zb[i]
            px[i * 3] = (x + 0.5f - cx) / fpx * z; px[i * 3 + 1] = (y + 0.5f - cy) / fpx * z; px[i * 3 + 2] = z
        }
        val plane = planeFit(px, BooleanArray(n) { wv[it] > 0.3f }.takeIf { b -> b.count { it } >= 50 } ?: ground) ?: return
        val nx = plane[0]; val ny = plane[1]; val nz = plane[2]; val c0 = plane[3]; val c1 = plane[4]; val c2 = plane[5]

        // ---- what the ground is made of: smooth or coarse, bare or leaves
        val lum0 = FloatArray(n) { LUM[0] * small[it * 3] + LUM[1] * small[it * 3 + 1] + LUM[2] * small[it * 3 + 2] }
        val r9 = max(1, (4f * u).roundToInt())
        val m1 = box(lum0, gw, gh, r9); val m2 = box(FloatArray(n) { lum0[it] * lum0[it] }, gw, gh, r9)
        val sd = FloatArray(n) { sqrt(max(m2[it] - m1[it] * m1[it], 0f)) / (m1[it] + 0.02f) }
        val sdb = gauss(sd, gw, gh, 6f * u, 6f * u)
        val leafRaw = FloatArray(n)
        val m = PROPHOTO_TO_SRGB
        val encLut = FloatArray(4097) { encExact(it / 4096f) }
        fun enc(c: Float): Float { val x = c.coerceIn(0f, 1f) * 4096f; val i = min(x.toInt(), 4095); val t = x - i; return encLut[i] + (encLut[i + 1] - encLut[i]) * t }
        for (i in 0 until n) {
            val r = small[i * 3]; val g = small[i * 3 + 1]; val b = small[i * 3 + 2]
            val sr = enc(m[0] * r + m[1] * g + m[2] * b); val sg = enc(m[3] * r + m[4] * g + m[5] * b); val sb = enc(m[6] * r + m[7] * g + m[8] * b)
            val hi = max(sr, max(sg, sb)); val lo = min(sr, min(sg, sb)); val span = hi - lo
            val sat = if (hi > 0f) span / hi else 0f
            val hue = if (span <= 0f) 0f else when (hi) {
                sr -> 60f * (((sg - sb) / span) % 6f + if (sg < sb) 6f else 0f)
                sg -> 60f * ((sb - sr) / span + 2f)
                else -> 60f * ((sr - sg) / span + 4f)
            }
            leafRaw[i] = clip01((hue - 36f) / 8f) * clip01((165f - hue) / 10f) * clip01((sat - 0.15f) / 0.1f)
        }
        val leaf = gauss(leafRaw, gw, gh, 2f * u, 2f * u)
        val elig = FloatArray(n) { max(clip01(1f - (sdb[it] - 0.15f) / 0.25f), 0.6f) * (1f - leaf[it]) * clip01((lum0[it] - 0.015f) / 0.02f) }

        // ---- standing water: painted (1.2 … 1.8), and patches that form in wet ground by themselves
        val pud = FloatArray(n)
        if (look.puddles > 0f) {
            val acc = puddleNoise(px, n)
            var pn = 0
            for (i in 0 until n) if (ground[i] && elig[i] > 0.5f && wv[i] >= 0.5f) pn++
            val pool = IntArray(pn); pn = 0
            for (i in 0 until n) if (ground[i] && elig[i] > 0.5f && wv[i] >= 0.5f) pool[pn++] = i
            if (pool.size > 20) {
                val vals = FloatArray(pool.size) { acc[pool[it]] }
                val mean = vals.average().toFloat(); val std = sqrt(vals.fold(0.0) { s, v -> s + (v - mean) * (v - mean) } / vals.size).toFloat()
                vals.sort()
                val cut = vals[((vals.size - 1) * (1f - look.puddles)).toInt()]
                val soft = max(0.3f * std, 1e-6f)
                for (i in 0 until n) if (ground[i]) pud[i] = clip01((acc[i] - cut) / soft)
                val pb = gauss(pud, gw, gh, u, u)
                for (i in 0 until n) pud[i] = pb[i] * clip01(elig[i] * 1.5f) * clip01((wv[i] - 0.5f) / 0.5f)
            }
        }
        for (i in 0 until n) { val t = clip01((wv[i] - 1.2f) / 0.6f); pud[i] = max(pud[i], t * t * (3f - 2f * t)) }

        // ---- the reflection: each ground pixel's mirrored line of sight, marched through the depth
        val refl = FloatArray(n * 3); val dist = FloatArray(n)
        val rows = max(4, gh / 12)
        val band = FloatArray(gw * 3)
        for (x in 0 until gw) for (c in 0 until 3) { var s = 0f; for (y in 0 until rows) s += lh[(y * gw + x) * 3 + c]; band[x * 3 + c] = s / rows }
        val bandB = FloatArray(gw * 3)
        for (c in 0 until 3) { val ch = gauss(FloatArray(gw) { band[it * 3 + c] }, gw, 1, 20f * u, 0f); for (x in 0 until gw) bandB[x * 3 + c] = ch[x] }
        val lb = Array(3) { c -> gauss(FloatArray(n) { lh[it * 3 + c] }, gw, gh, 12f * u, 12f * u) }
        val steps = FloatArray(STEPS) { (0.02 * (25.0 / 0.02).pow(it / (STEPS - 1.0))).toFloat() }
        val dc = c0 * nx + c1 * ny + c2 * nz
        parallel(gh) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until gw) {
                val i = y * gw + x
                if (!ground[i]) continue
                var vx = (x + 0.5f - cx) / fpx; var vy = (y + 0.5f - cy) / fpx; var vz = 1f
                val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
                val den = vx * nx + vy * ny + vz * nz
                val t = dc / (if (abs(den) < 1e-6f) 1e-6f else den)
                if (t <= 0f) continue
                val gx = vx * t; val gy = vy * t; val gz = vz * t
                val rx = vx - 2 * den * nx; val ry = vy - 2 * den * ny; val rz = vz - 2 * den * nz
                val d = sqrt(gx * gx + gy * gy + gz * gz)
                var prev = 0f; var hit = false; var hu = 0f; var hv = 0f
                var top = false; var exited = false; var eu = 0f; var ev = 0f; var active = true
                for (s in steps) {
                    val qx = gx + s * d * rx; val qy = gy + s * d * ry; val qz = gz + s * d * rz
                    val front = qz > 1e-3f
                    val pu = if (front) fpx * qx / qz + cx else -1e9f; val pv = if (front) fpx * qy / qz + cy else -1e9f
                    if (!(pu >= 0f && pu <= gw - 1f && pv >= 0f && pv <= gh - 1f)) {
                        if (front && pv < 0f) top = true
                        if (front) { exited = true; eu = pu.coerceIn(0f, gw - 1f); ev = pv.coerceIn(0f, gh - 1f) }
                        active = false; break
                    }
                    val zs = idx(zb, gw, gh, pu, pv)
                    if (qz >= zs && qz - zs < 0.25f * zs + 0.05f) {
                        var lo = prev; var hiS = s
                        repeat(6) {
                            val mid = 0.5f * (lo + hiS)
                            val mx = gx + mid * d * rx; val my = gy + mid * d * ry; val mz = max(gz + mid * d * rz, 1e-3f)
                            val zm = idx(zb, gw, gh, (fpx * mx / mz + cx).coerceIn(0f, gw - 1f), (fpx * my / mz + cy).coerceIn(0f, gh - 1f))
                            if (mz >= zm) hiS = mid else lo = mid
                        }
                        val hx = gx + hiS * d * rx; val hy = gy + hiS * d * ry; val hz = max(gz + hiS * d * rz, 1e-3f)
                        hu = (fpx * hx / hz + cx).coerceIn(0f, gw - 1f); hv = (fpx * hy / hz + cy).coerceIn(0f, gh - 1f)
                        hit = true; active = false; break
                    }
                    prev = s
                }
                if (active) top = true                       // never met anything: open sky, far away
                if (hit) {
                    for (c in 0 until 3) refl[i * 3 + c] = idx3(lh, gw, gh, c, hu, hv)
                    dist[i] = hypot(hu - x, hv - y)
                } else if (top) {
                    val col = (if (exited) eu else x.toFloat()).toInt().coerceIn(0, gw - 1)
                    for (c in 0 until 3) {
                        val sky = bandB[col * 3 + c] * 0.9f
                        refl[i * 3 + c] = if (exited) 0.5f * idx(lb[c], gw, gh, eu, ev) + 0.5f * sky else sky
                    }
                    dist[i] = gh * 0.5f
                } else if (exited) {
                    for (c in 0 until 3) refl[i * 3 + c] = idx(lb[c], gw, gh, eu, ev)
                    dist[i] = hypot(eu - x, ev - y)
                }
            }
        }

        // ---- plants standing beside the water, mirrored in it
        if (look.plants) {
            val dense = gauss(leafRaw, gw, gh, 14f * u, 14f * u)
            val tall = FloatArray(n) { PLANT_HEIGHT * clip01((dense[it] - 0.45f) / 0.3f) }
            val cards = FloatArray(n * 3); val got = FloatArray(n)
            val cardSteps = FloatArray(48) { (0.004 * (1.0 / 0.004).pow(it / 47.0)).toFloat() }
            parallel(gh) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until gw) {
                    val i = y * gw + x
                    if (!ground[i]) continue
                    var vx = (x + 0.5f - cx) / fpx; var vy = (y + 0.5f - cy) / fpx; var vz = 1f
                    val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
                    val den = vx * nx + vy * ny + vz * nz
                    val t0 = dc / (if (abs(den) < 1e-6f) 1e-6f else den)
                    val gx = vx * t0; val gy = vy * t0; val gz = vz * t0
                    val rx = vx - 2 * den * nx; val ry = vy - 2 * den * ny; val rz = vz - 2 * den * nz
                    val rise = max(rx * nx + ry * ny + rz * nz, 1e-3f)
                    val tmax = min(0.8f / rise, 8f)
                    for (kk in cardSteps) {
                        val t = kk * tmax
                        val qx = gx + t * rx; val qy = gy + t * ry; val qz = gz + t * rz
                        val fx = qx - t * rise * nx; val fy = qy - t * rise * ny; val fz = qz - t * rise * nz
                        if (fz <= 1e-3f) continue
                        val uf = fpx * fx / fz + cx; val vf = fpx * fy / fz + cy
                        if (uf < 0f || uf > gw - 1f || vf < 0f || vf > gh - 1f) continue
                        if (t * rise < idx(tall, gw, gh, uf, vf)) {
                            val qzc = max(qz, 1e-3f)
                            val uq = (fpx * qx / qzc + cx).coerceIn(0f, gw - 1f); val vq = (fpx * qy / qzc + cy).coerceIn(0f, gh - 1f)
                            for (c in 0 until 3) cards[i * 3 + c] = idx3(lh, gw, gh, c, uq, vq)
                            got[i] = 1f
                            break
                        }
                    }
                }
            }
            // only mud and water mirror: not the leaves themselves, nor the gaps inside a bush
            val gotB = gauss(got, gw, gh, 1.5f * u, 1.5f * u)
            val leafB3 = gauss(leafRaw, gw, gh, 3f * u, 3f * u)
            val tallB = gauss(FloatArray(n) { if (tall[it] > 0.02f) 1f else 0f }, gw, gh, 3f * u, 3f * u)
            for (i in 0 until n) {
                val mc = gotB[i] * (1f - leafB3[i]) * (1f - tallB[i])
                if (mc <= 0f) continue
                for (c in 0 until 3) refl[i * 3 + c] = refl[i * 3 + c] * (1f - mc) + cards[i * 3 + c] * mc
                dist[i] = dist[i] * (1f - mc) + min(dist[i], 12f) * mc
            }
        }

        // ---- roughness: the reflection blurred more the further the reflected thing stands above the ground
        val want0 = FloatArray(n) {
            val a = clip01(wv[it])
            (look.shine * (1.5f + 0.08f * dist[it] / u) * (1f + 1.5f * (1f - a))).coerceIn(0f, LEVELS.last())
        }
        val want = gauss(want0, gw, gh, 4f * u, 4f * u)
        for (i in 0 until n) want[i] *= 1f - 0.92f * pud[i]
        val rm = FloatArray(n * 3)
        for (li in LEVELS.indices) {
            val wts = FloatArray(n) { levelWeight(want[it], li) }
            if (wts.all { it <= 0f }) continue
            val s = LEVELS[li]
            for (c in 0 until 3) {
                val ch = FloatArray(n) { refl[it * 3 + c] }
                val bl = if (s <= 0f) ch else gauss(ch, gw, gh, 1.1f * s * u, 5f * s * u)
                for (i in 0 until n) if (wts[i] > 0f) rm[i * 3 + c] += wts[i] * bl[i]
            }
        }

        // ---- laid over the whole picture, with each pixel's own grain for the glints
        val skyRows = max(2, gh / 60)
        val sky = FloatArray(3)
        for (c in 0 until 3) { var s = 0.0; for (i in 0 until skyRows * gw) s += lh[i * 3 + c]; sky[c] = (s / (skyRows * gw)).toFloat() }
        val loc = gauss(lum0, gw, gh, 3f * u, 3f * u)
        val fFull = fpx * w / gw                                // the grid's focal length, at the picture's size
        val gain = look.reflection
        parallel(h) { y0, y1 ->
            val row = FloatArray(w * 3)
            for (y in y0 until y1) {
                val v = (y + 0.5f) / h
                val gyc = ((y + 0.5f) * gh / h - 0.5f).coerceIn(0f, gh - 1f)
                var changed = false
                f.duplicate().also { it.position(y * w * 3); it.get(row) }
                for (x in 0 until w) {
                    val a = clip01(map.sample((x + 0.5f) / w, v))
                    if (a < 0.002f) continue
                    val gxc = ((x + 0.5f) * gw / w - 0.5f).coerceIn(0f, gw - 1f)
                    val o = x * 3
                    val r = row[o] * e; val g = row[o + 1] * e; val b = row[o + 2] * e
                    val lum = LUM[0] * r + LUM[1] * g + LUM[2] * b
                    val p = idx(pud, gw, gh, gxc, gyc); val lf = idx(leaf, gw, gh, gxc, gyc)
                    val bump = (lum / (idx(loc, gw, gh, gxc, gyc) + 1e-4f)).coerceIn(0f, 3f)
                    val glint = (0.35f + 0.65f * bump * bump).coerceIn(0f, 3f) * (1f - p) + p
                    val dark = (0.45f * (1f - lf) + 0.85f * lf) * (1f - p) + 0.15f * p
                    var vx = (x + 0.5f - w / 2f) / fFull; var vy = (y + 0.5f - h / 2f) / fFull; var vz = 1f
                    val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
                    val cos = abs(vx * nx + vy * ny + vz * nz).coerceIn(0.02f, 1f)
                    val q = 1f - cos; val q2 = q * q
                    val fres = 0.02f + 0.98f * q2 * q2 * q
                    val sheenK = 0.05f * lf * (1f - p) * (bump - 1f).coerceIn(0f, 2f).let { it * it }
                    val k = gain * fres * glint
                    for (c in 0 until 3) {
                        val l = if (c == 0) r else if (c == 1) g else b
                        val diffuse = max(l + 0.25f * (l - lum), 0f) * dark
                        val wetC = diffuse + k * idx3(rm, gw, gh, c, gxc, gyc) + sheenK * sky[c]
                        row[o + c] = (l * (1f - a) + wetC * a) / e
                    }
                    changed = true
                }
                if (changed) f.duplicate().also { it.position(y * w * 3); it.put(row) }
            }
        }
    }


    /** A clipped highlight stretched back out (an inverse Reinhard roll-off): 0.5 → 0.74, 0.8 → 2.3, 1 → [LIFT]. */
    internal fun hdr(l: Float): Float {
        val k = 1f - 1f / LIFT
        val c = l.coerceIn(0f, 1f)
        return l / max(1f - k * c * sqrt(c), 1f / LIFT)
    }

    /** The blur levels' share at blur [want] for level [li]: straight lines between neighbours. */
    private fun levelWeight(want: Float, li: Int): Float {
        val wc = want.coerceIn(0f, LEVELS.last())
        var i = 0
        while (i < LEVELS.size - 2 && wc > LEVELS[i + 1]) i++
        val t = (wc - LEVELS[i]) / (LEVELS[i + 1] - LEVELS[i])
        return when (li) { i -> 1f - t; i + 1 -> t; else -> 0f }
    }

    /**
     * The ground as one flat plane through these points (least squares, the worst fifth dropped
     * three times), held level: no sideways roll. Its up (towards −y) and a point on it, or null.
     */
    private fun planeFit(p: FloatArray, sel: BooleanArray): FloatArray? {
        val idxs = sel.indices.filter { sel[it] }
        if (idxs.size < 10) return null
        val stride = max(1, idxs.size / 20000)
        val pts = idxs.filterIndexed { i, _ -> i % stride == 0 }
        val m = pts.size
        val xs = DoubleArray(m) { p[pts[it] * 3].toDouble() }; val ys = DoubleArray(m) { p[pts[it] * 3 + 1].toDouble() }; val zs = DoubleArray(m) { p[pts[it] * 3 + 2].toDouble() }
        val keep = BooleanArray(m) { true }
        var n = doubleArrayOf(0.0, -1.0, 0.0); var c = doubleArrayOf(0.0, 0.0, 0.0)
        repeat(3) {
            var cnt = 0; var mx = 0.0; var my = 0.0; var mz = 0.0
            for (i in 0 until m) if (keep[i]) { mx += xs[i]; my += ys[i]; mz += zs[i]; cnt++ }
            if (cnt < 3) return null
            mx /= cnt; my /= cnt; mz /= cnt
            val cov = DoubleArray(9)
            for (i in 0 until m) if (keep[i]) {
                val a = xs[i] - mx; val b = ys[i] - my; val d = zs[i] - mz
                cov[0] += a * a; cov[1] += a * b; cov[2] += a * d; cov[4] += b * b; cov[5] += b * d; cov[8] += d * d
            }
            cov[3] = cov[1]; cov[6] = cov[2]; cov[7] = cov[5]
            n = smallestEigenvector(cov)
            if (n[1] > 0) n = doubleArrayOf(-n[0], -n[1], -n[2])           // up is −y
            c = doubleArrayOf(mx, my, mz)
            val r = DoubleArray(m) { abs((xs[it] - mx) * n[0] + (ys[it] - my) * n[1] + (zs[it] - mz) * n[2]) }
            val q = r.sortedArray()[((m - 1) * 0.8).toInt()]
            for (i in 0 until m) keep[i] = r[i] <= q
        }
        val l = hypot(n[1], n[2]); if (l < 1e-9) return null
        return floatArrayOf(0f, (n[1] / l).toFloat(), (n[2] / l).toFloat(), c[0].toFloat(), c[1].toFloat(), c[2].toFloat())
    }

    /** The eigenvector of a symmetric 3 × 3 matrix with the smallest eigenvalue (Jacobi rotations). */
    private fun smallestEigenvector(a0: DoubleArray): DoubleArray {
        val a = a0.copyOf(); val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(50) {
            var p = 0; var q = 1; var big = abs(a[1])
            if (abs(a[2]) > big) { p = 0; q = 2; big = abs(a[2]) }
            if (abs(a[5]) > big) { p = 1; q = 2; big = abs(a[5]) }
            if (big < 1e-15) return@repeat
            val th = 0.5 * Math.atan2(2 * a[p * 3 + q], a[q * 3 + q] - a[p * 3 + p])
            val cs = Math.cos(th); val sn = Math.sin(th)
            for (k in 0 until 3) {                                   // A ← A·J
                val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
                a[k * 3 + p] = cs * akp - sn * akq; a[k * 3 + q] = sn * akp + cs * akq
            }
            for (k in 0 until 3) {                                   // A ← Jᵀ·A
                val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
                a[p * 3 + k] = cs * apk - sn * aqk; a[q * 3 + k] = sn * apk + cs * aqk
            }
            for (k in 0 until 3) {                                   // V ← V·J
                val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]
                v[k * 3 + p] = cs * vkp - sn * vkq; v[k * 3 + q] = sn * vkp + cs * vkq
            }
        }
        val j = (0 until 3).minByOrNull { a[it * 3 + it] }!!
        return doubleArrayOf(v[j], v[3 + j], v[6 + j])
    }

    /** Puddles lie flat on the ground: smooth noise in metres across (X) and away (Z), so far ones squash. */
    private fun puddleNoise(p: FloatArray, n: Int): FloatArray {
        val acc = FloatArray(n)
        val octaves = listOf(1.2f to 0.6f, 0.5f to 0.3f, 0.2f to 0.1f)
        for ((k, o) in octaves.withIndex()) {
            val (size, weight) = o
            val rnd = java.util.Random(3L * 1000 + k)
            val raw = FloatArray(64 * 64) { rnd.nextFloat() }
            val grid = gaussWrap(raw, 64, 1.5f)
            for (i in 0 until n) {
                val gu = floorMod(p[i * 3] / size, 64f); val gv = floorMod(p[i * 3 + 2] / size, 64f)
                val x0 = gu.toInt() % 64; val y0 = gv.toInt() % 64; val x1 = (x0 + 1) % 64; val y1 = (y0 + 1) % 64
                val fx = gu - floor(gu); val fy = gv - floor(gv)
                val top = grid[y0 * 64 + x0] * (1 - fx) + grid[y0 * 64 + x1] * fx
                val bot = grid[y1 * 64 + x0] * (1 - fx) + grid[y1 * 64 + x1] * fx
                acc[i] += weight * (top * (1 - fy) + bot * fy)
            }
        }
        return acc
    }

    private fun floorMod(a: Float, m: Float): Float { val r = a - m * floor(a / m); return if (r >= m) 0f else r }

    /** A Gaussian blur that wraps round (for the tiling noise). */
    private fun gaussWrap(a: FloatArray, s: Int, sigma: Float): FloatArray {
        val r = (3 * sigma).toInt(); val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); exp(-d * d / (2 * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(s * s); val out = FloatArray(s * s)
        for (y in 0 until s) for (x in 0 until s) { var t = 0f; for (d in -r..r) t += k[d + r] * a[y * s + Math.floorMod(x + d, s)]; tmp[y * s + x] = t }
        for (y in 0 until s) for (x in 0 until s) { var t = 0f; for (d in -r..r) t += k[d + r] * tmp[Math.floorMod(y + d, s) * s + x]; out[y * s + x] = t }
        return out
    }

    private fun clip01(x: Float) = x.coerceIn(0f, 1f)

    /** sRGB's curve, for reading a colour's hue and strength as the eye (and the proof) does. */
    private fun encExact(c: Float): Float {
        val x = c.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.toDouble().pow(1 / 2.4).toFloat() - 0.055f
    }

    /** Bilinear at index coordinates (pixel x's centre at x), clamped at the edges. */
    private fun idx(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0; val fy = yc - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }
    private fun idx3(a: FloatArray, w: Int, h: Int, c: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0; val fy = yc - y0
        return (a[(y0 * w + x0) * 3 + c] * (1 - fx) + a[(y0 * w + x1) * 3 + c] * fx) * (1 - fy) +
            (a[(y1 * w + x0) * 3 + c] * (1 - fx) + a[(y1 * w + x1) * 3 + c] * fx) * fy
    }

    /** Erosion (min) or dilation (max) over a k × k square. */
    private fun minMax(a: FloatArray, w: Int, h: Int, k: Int, isMin: Boolean): FloatArray {
        val r = k / 2; val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = a[y * w + x]
            for (d in -r..r) { val v = a[y * w + (x + d).coerceIn(0, w - 1)]; m = if (isMin) min(m, v) else max(m, v) }
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = tmp[y * w + x]
            for (d in -r..r) { val v = tmp[(y + d).coerceIn(0, h - 1) * w + x]; m = if (isMin) min(m, v) else max(m, v) }
            out[y * w + x] = m
        }
        return out
    }

    /** A box average of radius [r] (a (2r+1)² square), edges held. */
    private fun box(a: FloatArray, w: Int, h: Int, r: Int): FloatArray = boxPass(boxPass(a, w, h, r, true), w, h, r, false)

    /** One box pass along rows ([alongX]) or columns, by running sums: as fast for any radius. */
    private fun boxPass(a: FloatArray, w: Int, h: Int, r: Int, alongX: Boolean): FloatArray {
        if (r <= 0) return a.copyOf()
        val out = FloatArray(w * h)
        val len = if (alongX) w else h; val lines = if (alongX) h else w
        val norm = 1f / (2 * r + 1)
        val body = { l0: Int, l1: Int ->
            for (l in l0 until l1) {
                fun at(i: Int): Float { val c = i.coerceIn(0, len - 1); return if (alongX) a[l * w + c] else a[c * w + l] }
                var s = 0f
                for (i in -r..r) s += at(i)
                for (i in 0 until len) {
                    if (alongX) out[l * w + i] = s * norm else out[i * w + l] = s * norm
                    s += at(i + r + 1) - at(i - r)
                }
            }
        }
        if (w.toLong() * h < 65536) body(0, lines) else parallel(lines, body)
        return out
    }

    /**
     * A Gaussian blur, [sx] across and [sy] down, as three box passes each way (within a few per
     * cent of the true curve, and as fast for a 150-pixel streak as for a 2-pixel one).
     */
    internal fun gauss(a: FloatArray, w: Int, h: Int, sx: Float, sy: Float): FloatArray {
        var out = a
        for (pass in 0 until 3) {
            if (sx >= 0.3f) out = boxPass(out, w, h, boxRadius(sx, pass), true)
            if (sy >= 0.3f && h > 1) out = boxPass(out, w, h, boxRadius(sy, pass), false)
        }
        return if (out === a) a.copyOf() else out
    }

    /** The radii of three boxes whose sum of variances is σ² (Kovesi's split). */
    private fun boxRadius(sigma: Float, pass: Int): Int {
        val wIdeal = sqrt(12.0 * sigma * sigma / 3 + 1)
        var wl = floor(wIdeal).toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - 3 * wl * wl - 4 * 3 * wl - 3 * 3) / (-4 * wl - 4)
        val mm = mIdeal.roundToInt()
        val size = if (pass < mm) wl else wu
        return max(0, (size - 1) / 2)
    }

    /** Rows [0, rows) in bands, one per core; each band's work is its own, so the split never changes the result. */
    private fun parallel(rows: Int, body: (Int, Int) -> Unit) {
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(rows)
        if (bands <= 1) { body(0, rows); return }
        var failure: Throwable? = null
        val workers = (0 until bands).map { b ->
            Thread { runCatching { body(rows * b / bands, rows * (b + 1) / bands) }.onFailure { failure = it } }.apply { start() }
        }
        workers.forEach { it.join() }
        failure?.let { throw it }
    }
}
