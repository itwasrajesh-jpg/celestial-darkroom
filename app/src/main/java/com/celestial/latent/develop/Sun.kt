package com.celestial.latent.develop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The sun on surfaces (step 50b): the light lands on what faces it and is blocked by what stands
 * in its way, worked out from the photo's estimated depth — the method proven on five photos
 * (a cat, a portrait, a plant, a flower, a bottle) before a line of this was written.
 *
 *  1. Depth: Depth Anything V2 Small, once per photo and framing, remembered on disk.
 *  2. Shape: the depth becomes a 3D scene (its scale set by the user, close-up to wide); thin
 *     things like whiskers and grass are ignored for shape and shadow (they'd cast nonsense).
 *  3. Light: each surface gets the sun by the angle it faces it (wrapped a little: fur and skin
 *     scatter round edges), times whether the sun reaches it — a march towards the sun through
 *     the depth: real cast shadows.
 *  4. Clean-up: on abrupt depth edges the angle is a guess, so those pixels take their own
 *     surface's light (no seams); and away from the plane of focus the light is blurred as the
 *     lens blurred the scene (out-of-focus depth is unreliable, and real light there is soft).
 *  5. Applied before the film: each pixel's light is multiplied by (1 + strength × sun), so the
 *     added light scales what each surface reflects — black stays black, as in reality.
 */
object Sun {
    /** The map's long side, in pixels: the light is smooth enough to be worked out small. */
    private const val GRID = 384
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    /** Linear ProPhoto (D50) to linear sRGB (D65, Bradford), for the picture the model sees. */
    private val PROPHOTO_TO_SRGB = floatArrayOf(
        2.0341926f, -0.7274198f, -0.3067728f,
        -0.2288247f, 1.2317259f, -0.0029012f,
        -0.0085557f, -0.1532907f, 1.1618464f,
    )

    /** What one photo gives the sun: its depth, and a small luminance copy for finding focus. */
    class Input(val depth: FloatArray, val lum: FloatArray, val gw: Int, val gh: Int)

    /** The sun's light on the picture, 0..1, at GRID size; sampled to any size. */
    class LightMap(val w: Int, val h: Int, val v: FloatArray) {
        fun sample(u: Float, vv: Float): Float {
            val x = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat()); val y = (vv * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = x - x0; val fy = y - y0
            val a = v[y0 * w + x0] * (1 - fx) + v[y0 * w + x1] * fx
            val b = v[y1 * w + x0] * (1 - fx) + v[y1 * w + x1] * fx
            return a * (1 - fy) + b * fy
        }
    }

    /** Whether this look asks for sun on surfaces at all. */
    fun wanted(look: RaysLook): Boolean = look.type == "sun" && look.placed && look.surface > 0f

    /**
     * The direction towards the sun. Across the picture it is against the arrow's travel (the
     * arrow shows where the light goes); in depth it is the user's front/behind: −1 behind the
     * camera, +1 behind the subject. Worked in true proportions (across scaled by the aspect).
     */
    fun toward(look: RaysLook, aspect: Float): FloatArray? {
        val l = look.withDefaults()
        if (l.type != "sun" || !l.placed || l.u2.isNaN()) return null
        var dx = (l.u2 - l.u) * aspect; var dy = l.v2 - l.v
        val n = sqrt(dx * dx + dy * dy); if (n < 1e-6f) return null
        dx /= n; dy /= n
        val z = look.front.coerceIn(-0.95f, 0.95f); val k = sqrt(1f - z * z)
        return floatArrayOf(-dx * k, -dy * k, z)
    }

    // ---------------------------------------------------------------- depth, once per photo ----

    private val memory = object : LinkedHashMap<String, Input>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Input>?) = size > 6
    }

    private fun depthFile(context: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "depth").apply { mkdirs() }, "$h.bin")
    }

    /**
     * The photo's depth and focus copy, from the framed picture about to be developed — before
     * fog or light touch it. Remembered in memory and on disk under the photo and its framing,
     * so the model runs once per photo however often it is developed. Null without the model.
     */
    fun prepare(context: Context, photo: Uri, framing: Framing, src: Develop.Source, log: (String) -> Unit = {}): Input? {
        if (!Depth.isReady(context)) { log("sun on surfaces needs the depth model (Settings → Depth test)"); return null }
        // the photo and its framing — not the pixel size, so the preview and the full develop share one depth
        val key = "$photo|${framing.key()}|" + "%.4f".format(java.util.Locale.US, src.width.toFloat() / src.height)
        synchronized(memory) { memory[key] }?.let { return it }
        val (gw, gh, small) = smallCopy(src)
        val lum = FloatArray(gw * gh) { LUM[0] * small[it * 3] + LUM[1] * small[it * 3 + 1] + LUM[2] * small[it * 3 + 2] }
        val f = depthFile(context, key)
        val depth = runCatching {
            if (f.exists() && f.length() == (Depth.SIZE * Depth.SIZE * 4).toLong()) {
                val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(Depth.SIZE * Depth.SIZE) { bb.getFloat() }
            } else null
        }.getOrNull() ?: run {
            log("estimating depth…")
            val d = Depth.estimate(context, srgbBitmap(small, gw, gh))
            runCatching {
                val bb = ByteBuffer.allocate(d.size * 4).order(ByteOrder.LITTLE_ENDIAN); d.forEach { bb.putFloat(it) }
                f.writeBytes(bb.array())
            }
            d
        }
        return Input(depth, lum, gw, gh).also { synchronized(memory) { memory[key] = it } }
    }

    /** A box-averaged copy of the picture at GRID size: linear ProPhoto RGB. */
    private fun smallCopy(src: Develop.Source): Triple<Int, Int, FloatArray> {
        val w = src.width; val h = src.height
        val gw: Int; val gh: Int
        if (w >= h) { gw = min(GRID, w); gh = max(1, Math.round(gw.toFloat() * h / w)) } else { gh = min(GRID, h); gw = max(1, Math.round(gh.toFloat() * w / h)) }
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val sum = FloatArray(gw * gh * 3); val count = IntArray(gw * gh)
        for (y in 0 until h) {
            val gy = (y * gh / h).coerceAtMost(gh - 1)
            for (x in 0 until w) {
                val g = gy * gw + (x * gw / w).coerceAtMost(gw - 1); val o = (y * w + x) * 3
                sum[g * 3] += f.get(o); sum[g * 3 + 1] += f.get(o + 1); sum[g * 3 + 2] += f.get(o + 2); count[g]++
            }
        }
        for (g in 0 until gw * gh) { val c = max(count[g], 1); sum[g * 3] /= c; sum[g * 3 + 1] /= c; sum[g * 3 + 2] /= c }
        return Triple(gw, gh, sum)
    }

    /** The small copy as an ordinary sRGB picture — what the depth model was trained on — auto-exposed. */
    private fun srgbBitmap(small: FloatArray, gw: Int, gh: Int): Bitmap {
        val n = gw * gh
        val lum = FloatArray(n) { LUM[0] * small[it * 3] + LUM[1] * small[it * 3 + 1] + LUM[2] * small[it * 3 + 2] }
        val sorted = lum.copyOf().also { it.sort() }
        val gain = 0.9f / max(sorted[((n - 1) * 0.99f).toInt()], 1e-6f)
        val px = IntArray(n)
        for (i in 0 until n) {
            val r = small[i * 3] * gain; val g = small[i * 3 + 1] * gain; val b = small[i * 3 + 2] * gain
            fun enc(c: Float): Int {
                val x = c.coerceIn(0f, 1f)
                val e = if (x <= 0.0031308f) x * 12.92f else 1.055f * Math.pow(x.toDouble(), 1 / 2.4).toFloat() - 0.055f
                return (e * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            val m = PROPHOTO_TO_SRGB
            px[i] = (0xFF shl 24) or (enc(m[0] * r + m[1] * g + m[2] * b) shl 16) or
                (enc(m[3] * r + m[4] * g + m[5] * b) shl 8) or enc(m[6] * r + m[7] * g + m[8] * b)
        }
        return Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888)
    }

    // -------------------------------------------------------------- the light on surfaces ----

    private val maps = object : LinkedHashMap<String, LightMap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LightMap>?) = size > 6
    }

    /** The scene's size from the user's scale: field of view, nearest and farthest, in metres. */
    private fun scene(scale: Float): Triple<Float, Float, Float> {
        val s = scale.coerceIn(0f, 1f)
        val hfov = 20f + 55f * s
        val near = (0.6f * Math.pow(4.0, s.toDouble())).toFloat()
        val far = (4f * Math.pow(15.0, s.toDouble())).toFloat()
        return Triple(hfov, near, far)
    }

    /** The sun's light for this photo and look, remembered until either changes. */
    fun lightMap(input: Input, look: RaysLook, log: (String) -> Unit = {}): LightMap? {
        val l = toward(look, input.gw.toFloat() / input.gh) ?: return null
        val key = "${System.identityHashCode(input)}|${l.joinToString { "%.4f".format(java.util.Locale.US, it) }}|${"%.3f".format(java.util.Locale.US, look.scale)}"
        synchronized(maps) { maps[key] }?.let { return it }
        val t0 = System.currentTimeMillis()
        val m = compute(input, l, look.scale)
        log("sun on surfaces worked out in ${System.currentTimeMillis() - t0} ms")
        return m.also { synchronized(maps) { maps[key] = it } }
    }

    /** The method itself — the same steps, in the same order, as the version tested on five photos. */
    internal fun compute(input: Input, toward: FloatArray, scale: Float): LightMap {
        val gw = input.gw; val gh = input.gh; val n = gw * gh
        val (hfov, near, far) = scene(scale)
        val f = (gw / 2f) / tan(Math.toRadians(hfov / 2.0)).toFloat(); val cx = gw / 2f; val cy = gh / 2f
        // the depth, stretched back to the picture's shape (the model saw it squeezed square)
        val dn = FloatArray(n)
        val S = Depth.SIZE
        for (y in 0 until gh) for (x in 0 until gw) {
            val sx = ((x + 0.5f) * S / gw - 0.5f).coerceIn(0f, (S - 1).toFloat()); val sy = ((y + 0.5f) * S / gh - 0.5f).coerceIn(0f, (S - 1).toFloat())
            val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = min(x0 + 1, S - 1); val y1 = min(y0 + 1, S - 1); val fx = sx - x0; val fy = sy - y0
            val d = input.depth
            dn[y * gw + x] = (d[y0 * S + x0] * (1 - fx) + d[y0 * S + x1] * fx) * (1 - fy) + (d[y1 * S + x0] * (1 - fx) + d[y1 * S + x1] * fx) * fy
        }
        val Z = FloatArray(n) { 1f / (1f / far + dn[it] * (1f / near - 1f / far)) }
        // thin near things (whiskers, hair, grass) set neither shape nor shadow: an opening of nearness
        val k = max(3, gw / 90).let { if (it % 2 == 0) it + 1 else it }
        val nearness = FloatArray(n) { 1f / Z[it] }
        val opened = dilate(erode(nearness, gw, gh, k), gw, gh, k)
        val Zs = FloatArray(n) { 1f / max(opened[it], 1e-6f) }
        // shape: positions and the way each surface faces, from the smoothed depth
        val Zb = gauss(Zs, gw, gh, 2.5f)
        val P = FloatArray(n * 3)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x; val z = Zb[i]
            P[i * 3] = (x + 0.5f - cx) / f * z; P[i * 3 + 1] = (y + 0.5f - cy) / f * z; P[i * 3 + 2] = z
        }
        val N = FloatArray(n * 3)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            val xa = max(x - 1, 0); val xb = min(x + 1, gw - 1); val ya = max(y - 1, 0); val yb = min(y + 1, gh - 1)
            val dux = (P[(y * gw + xb) * 3] - P[(y * gw + xa) * 3]) / (xb - xa); val duy = (P[(y * gw + xb) * 3 + 1] - P[(y * gw + xa) * 3 + 1]) / (xb - xa); val duz = (P[(y * gw + xb) * 3 + 2] - P[(y * gw + xa) * 3 + 2]) / (xb - xa)
            val dvx = (P[(yb * gw + x) * 3] - P[(ya * gw + x) * 3]) / (yb - ya); val dvy = (P[(yb * gw + x) * 3 + 1] - P[(ya * gw + x) * 3 + 1]) / (yb - ya); val dvz = (P[(yb * gw + x) * 3 + 2] - P[(ya * gw + x) * 3 + 2]) / (yb - ya)
            var nx = duy * dvz - duz * dvy; var ny = duz * dvx - dux * dvz; var nz = dux * dvy - duy * dvx
            val len = sqrt(nx * nx + ny * ny + nz * nz) + 1e-9f
            nx /= len; ny /= len; nz /= len
            if (nz > 0) { nx = -nx; ny = -ny; nz = -nz }                 // facing the camera
            N[i * 3] = nx; N[i * 3 + 1] = ny; N[i * 3 + 2] = nz
        }
        // light: by the angle each surface faces the sun, times whether the sun reaches it
        val lx = toward[0]; val ly = toward[1]; val lz = toward[2]
        val wrap = 0.3f
        val march = 0.375f * far; val thick = 0.11f * far; val step0 = 0.001f * far
        val tol = 0.002f * far; val rampIn = 0.005f * far; val rampOut = 0.0125f * far
        val M = 48
        val rng = java.util.Random(2)
        val direct = FloatArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            val facing = ((N[i * 3] * lx + N[i * 3 + 1] * ly + N[i * 3 + 2] * lz + wrap) / (1f + wrap)).coerceIn(0f, 1f)
            if (facing <= 0f) { rng.nextFloat(); continue }
            // the surface point — from the smoothed depth, like its angle — stepped off the surface first
            val sx = P[i * 3] + N[i * 3] * step0; val sy = P[i * 3 + 1] + N[i * 3 + 1] * step0; val sz = P[i * 3 + 2] + N[i * 3 + 2] * step0
            val jit = rng.nextFloat()
            var vis = 1f
            for (s in 1..M) {
                val t = march * ((s - 1 + jit) / M)
                val qx = sx + lx * t; val qy = sy + ly * t; val qz = sz + lz * t
                if (qz <= 0.05f) continue
                val qu = (qx / qz * f + cx).toInt(); val qv = (qy / qz * f + cy).toInt()
                if (qu < 0 || qu >= gw || qv < 0 || qv >= gh) continue
                val zs = Zs[qv * gw + qu]
                val behind = ((qz - zs - tol) / rampIn).coerceIn(0f, 1f) * ((zs + thick - qz) / rampOut).coerceIn(0f, 1f)
                vis *= 1f - behind
                if (vis < 0.01f) break
            }
            direct[i] = facing * vis
        }
        // on abrupt depth edges the angle is a guess: those pixels take their own surface's light
        val lz2 = FloatArray(n) { ln(Zs[it]) }
        val good = FloatArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            val xa = max(x - 1, 0); val xb = min(x + 1, gw - 1); val ya = max(y - 1, 0); val yb = min(y + 1, gh - 1)
            val gx = (lz2[y * gw + xb] - lz2[y * gw + xa]) / (xb - xa); val gy = (lz2[yb * gw + x] - lz2[ya * gw + x]) / (yb - ya)
            good[i] = if (sqrt(gx * gx + gy * gy) < 0.04f) 1f else 0f
        }
        val num = depthSmooth(FloatArray(n) { direct[it] * good[it] }, Zs, gw, gh, 3, 0.04f)
        val den = depthSmooth(good, Zs, gw, gh, 3, 0.04f)
        for (i in 0 until n) if (good[i] < 0.5f && den[i] > 1e-3f) direct[i] = num[i] / den[i]
        val smoothed = depthSmooth(direct, Z, gw, gh, 2, 0.05f)
        // away from the plane of focus, blur the light as the lens blurred the scene
        val wf = focusWeight(input.lum, Zs, gw, gh)
        val blurred = gauss(smoothed, gw, gh, gw / 40f)
        return LightMap(gw, gh, FloatArray(n) { wf[it] * smoothed[it] + (1f - wf[it]) * blurred[it] })
    }

    /**
     * How sharp the lens rendered each depth: the plane of focus is where the photo is sharpest,
     * and blur grows with the difference in inverse depth from it (the circle of confusion).
     */
    private fun focusWeight(lum: FloatArray, Zs: FloatArray, gw: Int, gh: Int): FloatArray {
        val n = gw * gh
        val l = gauss(FloatArray(n) { sqrt(max(lum[it], 0f)) }, gw, gh, 0.7f)
        val lap = FloatArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val c = l[y * gw + x]
            val xl = l[y * gw + max(x - 1, 0)]; val xr = l[y * gw + min(x + 1, gw - 1)]
            val yu = l[max(y - 1, 0) * gw + x]; val yd = l[min(y + 1, gh - 1) * gw + x]
            lap[y * gw + x] = abs(xl + xr + yu + yd - 4 * c)
        }
        val lapB = gauss(lap, gw, gh, 3f)
        val base = gauss(FloatArray(n) { sqrt(max(lum[it], 0f)) }, gw, gh, 3f)
        val sharp = FloatArray(n) { lapB[it] / (base[it] + 0.02f) }
        val inv = FloatArray(n) { 1f / Zs[it] }
        val cut = sharp.copyOf().also { it.sort() }[((n - 1) * 0.95f).toInt()]
        val focused = ArrayList<Float>(); for (i in 0 until n) if (sharp[i] >= cut) focused += inv[i]
        focused.sort(); val invF = if (focused.isEmpty()) inv[n / 2] else focused[focused.size / 2]
        val sortedInv = inv.copyOf().also { it.sort() }
        val span = max(sortedInv[((n - 1) * 0.98f).toInt()] - sortedInv[((n - 1) * 0.02f).toInt()], 1e-6f)
        return FloatArray(n) { val d = abs(inv[it] - invF) / span / 0.18f; exp(-d * d) }
    }

    /** Averages with neighbours at a similar depth only: noise goes, edges between depths stay. */
    private fun depthSmooth(a: FloatArray, Z: FloatArray, gw: Int, gh: Int, radius: Int, depthSigma: Float): FloatArray {
        val out = FloatArray(gw * gh); val s2 = 2f * (radius / 2f) * (radius / 2f)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x; val z = Z[i]; var sum = 0f; var wsum = 0f
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val xx = (x + dx).coerceIn(0, gw - 1); val yy = (y + dy).coerceIn(0, gh - 1); val j = yy * gw + xx
                val dz = (Z[j] - z) / (depthSigma * z)
                val w = exp(-(dx * dx + dy * dy) / s2) * exp(-dz * dz)
                sum += w * a[j]; wsum += w
            }
            out[i] = sum / wsum
        }
        return out
    }

    private fun erode(a: FloatArray, gw: Int, gh: Int, k: Int): FloatArray = minMax(a, gw, gh, k, true)
    private fun dilate(a: FloatArray, gw: Int, gh: Int, k: Int): FloatArray = minMax(a, gw, gh, k, false)
    private fun minMax(a: FloatArray, gw: Int, gh: Int, k: Int, isMin: Boolean): FloatArray {
        val r = k / 2; val tmp = FloatArray(gw * gh); val out = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = a[y * gw + x]
            for (d in -r..r) { val v = a[y * gw + (x + d).coerceIn(0, gw - 1)]; m = if (isMin) min(m, v) else max(m, v) }
            tmp[y * gw + x] = m
        }
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = tmp[y * gw + x]
            for (d in -r..r) { val v = tmp[(y + d).coerceIn(0, gh - 1) * gw + x]; m = if (isMin) min(m, v) else max(m, v) }
            out[y * gw + x] = m
        }
        return out
    }

    private fun gauss(a: FloatArray, gw: Int, gh: Int, sigma: Float): FloatArray {
        if (sigma <= 0f) return a.copyOf()
        val r = max(1, (3 * sigma).toInt()); val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); exp(-d * d / (2 * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(gw * gh); val out = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) { var s = 0f; for (d in -r..r) s += k[d + r] * a[y * gw + (x + d).coerceIn(0, gw - 1)]; tmp[y * gw + x] = s }
        for (y in 0 until gh) for (x in 0 until gw) { var s = 0f; for (d in -r..r) s += k[d + r] * tmp[(y + d).coerceIn(0, gh - 1) * gw + x]; out[y * gw + x] = s }
        return out
    }

    // ------------------------------------------------------------------------- applying ----

    /**
     * Adds the sun to the picture, in place, before the film: each pixel's light is multiplied
     * by 1 + strength × sun × colour, where the brush ([mask]) lets it fall. Multiplying means
     * the sun scales what each surface reflects: black stays black, and nothing is painted on.
     */
    fun apply(src: Develop.Source, look: RaysLook, mask: ExposureMap?, map: LightMap, log: (String) -> Unit = {}) {
        if (look.surface <= 0f) return
        log("sun on surfaces")
        val w = src.width; val h = src.height
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val c = look.chroma(); val k = look.surface
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                var d = map.sample(u, v)
                if (mask != null) d *= mask.sample(u, v).coerceIn(0f, 1f)
                if (d <= 0f) continue
                val o = (y * w + x) * 3
                f.put(o, f.get(o) * (1f + k * d * c[0])); f.put(o + 1, f.get(o + 1) * (1f + k * d * c[1])); f.put(o + 2, f.get(o + 2) * (1f + k * d * c[2]))
            }
        }
    }
}
