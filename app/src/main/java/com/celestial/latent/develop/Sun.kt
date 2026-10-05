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

    /**
     * What one photo gives the lights: its depth; a small luminance copy for finding focus; and,
     * for "reveal", the room's own colour and its broad brightness (to keep the photo's texture).
     */
    class Input(val depth: FloatArray, val lum: FloatArray, val gw: Int, val gh: Int, val tint: FloatArray, val lumBig: FloatArray,
                val small: FloatArray)

    /** A light's effect on the picture, 0..1, at GRID size; sampled to any size. */
    /**
     * [measured]: for a sun through an opening, its strength in the photo's own exposure (÷ π, as a
     * surface reflects it); 0 for lights set by eye. [floor]: the light bouncing up from where
     * that sun lands on the assumed floor (÷ π), at the same size; null if none.
     */
    class LightMap(val w: Int, val h: Int, val v: FloatArray, val measured: Float = 0f, val floor: FloatArray? = null) {
        fun sample(u: Float, vv: Float): Float = bilinear(v, w, h, u, vv)
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, u: Float, vv: Float): Float {
        val x = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat()); val y = (vv * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = x - x0; val fy = y - y0
        val p = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
        val q = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
        return p * (1 - fy) + q * fy
    }

    /** Whether this light lights surfaces at all. */
    fun wanted(look: RaysLook): Boolean = look.placed && look.surface > 0f

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
        // for "reveal": the room's own colour (a heavy blur, at luminance 1) and its broad brightness
        val room = FloatArray(gw * gh * 3)
        for (c in 0 until 3) {
            val ch = gauss(FloatArray(gw * gh) { small[it * 3 + c] }, gw, gh, gw / 10f)
            for (i in 0 until gw * gh) room[i * 3 + c] = ch[i]
        }
        val tint = FloatArray(gw * gh * 3)
        for (i in 0 until gw * gh) {
            val l = max(LUM[0] * room[i * 3] + LUM[1] * room[i * 3 + 1] + LUM[2] * room[i * 3 + 2], 1e-5f)
            for (c in 0 until 3) tint[i * 3 + c] = (room[i * 3 + c] / l).coerceIn(0f, 2.5f)
        }
        val lumBig = gauss(lum, gw, gh, gw / 40f)
        return Input(depth, lum, gw, gh, tint, lumBig, small).also { synchronized(memory) { memory[key] = it } }
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

    /** The scene as 3D, shared by every light: built once per photo and scale. */
    internal class Scene(
        val gw: Int, val gh: Int, val f: Float, val cx: Float, val cy: Float, val near: Float, val far: Float,
        val Z: FloatArray, val Zs: FloatArray, val P: FloatArray, val N: FloatArray, val T: FloatArray, val wf: FloatArray,
    )

    private val scenes = object : LinkedHashMap<String, Scene>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Scene>?) = size > 3
    }
    private val maps = object : LinkedHashMap<String, LightMap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LightMap>?) = size > 12
    }

    /** The scene's size from the user's scale: field of view, nearest and farthest, in metres. */
    private fun sceneSize(scale: Float): Triple<Float, Float, Float> {
        val s = scale.coerceIn(0f, 1f)
        val hfov = 20f + 55f * s
        val near = (0.6f * Math.pow(4.0, s.toDouble())).toFloat()
        val far = (4f * Math.pow(15.0, s.toDouble())).toFloat()
        return Triple(hfov, near, far)
    }

    /** One light's effect, remembered until the light, the photo or the scene's scale changes. */
    fun lightMap(input: Input, look: RaysLook, sceneScale: Float, log: (String) -> Unit = {}): LightMap? {
        if (!wanted(look)) return null
        val key = "${System.identityHashCode(input)}|${look.key()}|${"%.3f".format(java.util.Locale.US, sceneScale)}"
        synchronized(maps) { maps[key] }?.let { return it }
        val t0 = System.currentTimeMillis()
        val sc = scene(input, sceneScale)
        val m = light(sc, look, input) ?: return null
        log("${look.type} on surfaces worked out in ${System.currentTimeMillis() - t0} ms")
        return m.also { synchronized(maps) { maps[key] = it } }
    }

    internal fun scene(input: Input, scale: Float): Scene {
        val key = "${System.identityHashCode(input)}|${"%.3f".format(java.util.Locale.US, scale)}"
        synchronized(scenes) { scenes[key] }?.let { return it }
        val gw = input.gw; val gh = input.gh; val n = gw * gh
        val (hfov, near, far) = sceneSize(scale)
        val f = (gw / 2f) / tan(Math.toRadians(hfov / 2.0)).toFloat(); val cx = gw / 2f; val cy = gh / 2f
        // the depth, stretched back to the picture's shape (the model saw it squeezed square)
        val dn = FloatArray(n); val S = Depth.SIZE
        for (y in 0 until gh) for (x in 0 until gw) {
            val sx = ((x + 0.5f) * S / gw - 0.5f).coerceIn(0f, (S - 1).toFloat()); val sy = ((y + 0.5f) * S / gh - 0.5f).coerceIn(0f, (S - 1).toFloat())
            val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = min(x0 + 1, S - 1); val y1 = min(y0 + 1, S - 1); val fx = sx - x0; val fy = sy - y0
            val d = input.depth
            dn[y * gw + x] = (d[y0 * S + x0] * (1 - fx) + d[y0 * S + x1] * fx) * (1 - fy) + (d[y1 * S + x0] * (1 - fx) + d[y1 * S + x1] * fx) * fy
        }
        val Z = FloatArray(n) { 1f / (1f / far + dn[it] * (1f / near - 1f / far)) }
        // thin near things (whiskers, hair, grass) set neither shape nor shadow: an opening of nearness
        val k = max(3, gw / 90).let { if (it % 2 == 0) it + 1 else it }
        val opened = dilate(erode(FloatArray(n) { 1f / Z[it] }, gw, gh, k), gw, gh, k)
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
        // how far each surface plausibly extends behind what the camera sees: about as deep as
        // its object is wide (twice the distance to the nearest depth edge). A fixed thickness made
        // a chandelier's thin arms into walls and blocked a lamp from its whole ceiling.
        val edge = BooleanArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val xa = max(x - 1, 0); val xb = min(x + 1, gw - 1); val ya = max(y - 1, 0); val yb = min(y + 1, gh - 1)
            val gx = (ln(Zs[y * gw + xb]) - ln(Zs[y * gw + xa])) / (xb - xa); val gy = (ln(Zs[yb * gw + x]) - ln(Zs[ya * gw + x])) / (yb - ya)
            edge[y * gw + x] = sqrt(gx * gx + gy * gy) >= 0.04f
        }
        val dist = distanceToEdge(edge, gw, gh)
        val T = FloatArray(n) { (2f * dist[it] * Zs[it] / f).coerceIn(0.02f, 0.11f * far) }
        val wf = focusWeight(input.lum, Zs, gw, gh)
        return Scene(gw, gh, f, cx, cy, near, far, Z, Zs, P, N, T, wf).also { synchronized(scenes) { scenes[key] = it } }
    }

    /** A window the sun shines in through: the wall's depth, which pixels let light through, the sun's strength. */
    internal class Opening(val wallZ: Float, val pass: BooleanArray, val sun: Float)

    /**
     * The opening of a sun light, from its rectangle: the bright parts inside (the panes, above the
     * rectangle's median brightness and a little more) let light through; the dark parts (frame,
     * bars) don't; the wall is at the depth of those dark parts. The sun is measured from the
     * panes: direct sun is about ten times a bright daylit surface, in the photo's own exposure.
     */
    internal fun opening(sc: Scene, input: Input, l: RaysLook): Opening? {
        if (!l.hasOpening) return null
        val gw = sc.gw; val gh = sc.gh
        val x0 = (min(l.ox0, l.ox1) * gw).toInt().coerceIn(0, gw - 1); val x1 = (max(l.ox0, l.ox1) * gw).toInt().coerceIn(0, gw - 1)
        val y0 = (min(l.oy0, l.oy1) * gh).toInt().coerceIn(0, gh - 1); val y1 = (max(l.oy0, l.oy1) * gh).toInt().coerceIn(0, gh - 1)
        if (x1 <= x0 || y1 <= y0) return null
        val inside = ArrayList<Float>()
        for (y in y0..y1) for (x in x0..x1) inside += input.lum[y * gw + x]
        val sorted = inside.sorted(); val cut = sorted[(sorted.size * 0.55f).toInt().coerceAtMost(sorted.size - 1)]
        val pass = BooleanArray(gw * gh); val panes = ArrayList<Float>(); val frame = ArrayList<Float>()
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * gw + x
            if (input.lum[i] > cut) { pass[i] = true; panes += input.lum[i] } else frame += sc.Zs[i]
        }
        if (panes.isEmpty()) return null
        val wallZ = (if (frame.isNotEmpty()) frame.sorted()[frame.size / 2] else sc.Zs[((y0 + y1) / 2) * gw + (x0 + x1) / 2])
        return Opening(wallZ, pass, 10f * panes.sorted()[panes.size / 2])
    }

    /**
     * Sun through the opening at a point: only in the room (in front of the wall), only if its line
     * towards the sun leaves through a pane, and only if nothing in the room blocks the way there.
     */
    internal fun throughOpening(sc: Scene, op: Opening, px: Float, py: Float, pz: Float, d: FloatArray, jit: Float, M: Int = 24): Float {
        if (d[2] < 0.02f || pz >= op.wallZ - 0.01f * sc.far) return 0f
        val t = (op.wallZ - pz) / d[2]
        val qx = px + d[0] * t; val qy = py + d[1] * t; val qz = op.wallZ
        val qu = (qx / qz * sc.f + sc.cx).toInt(); val qv = (qy / qz * sc.f + sc.cy).toInt()
        if (qu < 0 || qu >= sc.gw || qv < 0 || qv >= sc.gh || !op.pass[qv * sc.gw + qu]) return 0f
        val span = t * 0.95f; var vis = 1f
        for (s in 1..M) {
            val tt = span * ((s - 1 + jit) / M)
            val rx = px + d[0] * tt; val ry = py + d[1] * tt; val rz = pz + d[2] * tt
            if (rz <= 0.05f) continue
            val ru = (rx / rz * sc.f + sc.cx).toInt(); val rv = (ry / rz * sc.f + sc.cy).toInt()
            if (ru < 0 || ru >= sc.gw || rv < 0 || rv >= sc.gh) continue
            val j = rv * sc.gw + ru; val zs = sc.Zs[j]; val th = max(sc.T[j], 1.5f * span / M)
            val b = ((rz - zs - 0.002f * sc.far) / (0.005f * sc.far)).coerceIn(0f, 1f) * ((zs + th - rz) / max(0.1f * th, 0.005f)).coerceIn(0f, 1f)
            vis *= 1f - b
            if (vis < 0.01f) break
        }
        return vis
    }

    /**
     * Where the sun through the opening lands on an assumed floor (0.6–2.6 m below the camera, the
     * photo taken roughly level), that patch reflects 30% of it upwards as a soft light; each visible
     * surface gets it by distance and angle. Returned ÷ π, in the photo's own exposure. Physically
     * this is modest — one narrow window's patch adds ~10% to a wall two metres away — as it should be.
     */
    private fun floorBounce(sc: Scene, op: Opening, l: RaysLook, d: FloatArray): FloatArray? {
        if (d[2] < 0.02f || d[1] >= 0f) return null                         // the sun must be beyond the window and above
        val floorY = 0.6f + 2.0f * l.floor
        val gx = 120; val gz = 90; val halfX = 0.6f * sc.far
        val cellArea = (2f * halfX / gx) * ((op.wallZ - 0.2f) / gz)
        val pts = ArrayList<FloatArray>()
        for (iz in 0 until gz) for (ix in 0 until gx) {
            val x = -halfX + (ix + 0.5f) * 2f * halfX / gx; val z = 0.2f + (iz + 0.5f) * (op.wallZ - 0.2f) / gz
            val t = (op.wallZ - z) / d[2]
            val qx = x + d[0] * t; val qy = floorY + d[1] * t
            val qu = (qx / op.wallZ * sc.f + sc.cx).toInt(); val qv = (qy / op.wallZ * sc.f + sc.cy).toInt()
            if (qu in 0 until sc.gw && qv in 0 until sc.gh && op.pass[qv * sc.gw + qu]) pts += floatArrayOf(x, floorY, z)
        }
        if (pts.isEmpty()) return null
        val rng = java.util.Random(3); val use = if (pts.size > 160) pts.shuffled(rng).take(160) else pts
        val weight = pts.size.toFloat() / use.size
        val radiosity = 0.30f * op.sun * (-d[1])                            // what the patch sends up (÷ π later)
        val n = sc.gw * sc.gh; val e = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (p in use) {
                val tx = p[0] - sc.P[i * 3]; val ty = p[1] - sc.P[i * 3 + 1]; val tz = p[2] - sc.P[i * 3 + 2]
                val r2 = max(tx * tx + ty * ty + tz * tz, 1e-4f); val r = sqrt(r2)
                val down = ty / r; if (down <= 0f) continue                    // the floor sees only what is above it
                val recv = (sc.N[i * 3] * tx + sc.N[i * 3 + 1] * ty + sc.N[i * 3 + 2] * tz) / r; if (recv <= 0f) continue
                sum += down * recv / r2
            }
            e[i] = radiosity * sum * cellArea * weight / (PI_F * PI_F)        // irradiance ÷ π, then ÷ π as reflected
        }
        return depthSmooth(e, sc.Z, sc.gw, sc.gh, 3, 0.08f)
    }

    /** A point in the scene at a place on the picture, at the depth seen there, nudged nearer or farther. */
    internal fun pointAt(sc: Scene, u: Float, v: Float, nudge: Float): FloatArray {
        val z0 = bilinear(sc.Zs, sc.gw, sc.gh, u, v)
        val z = (z0 - 0.012f * sc.far + nudge * 0.25f * sc.far).coerceAtLeast(sc.near * 0.3f)    // just in front of what it sits on
        val x = u * sc.gw; val y = v * sc.gh
        return floatArrayOf((x - sc.cx) / sc.f * z, (y - sc.cy) / sc.f * z, z)
    }

    /** One light on every surface: by the angle each faces it, how far it is, and whether it is in shadow. */
    private fun light(sc: Scene, look: RaysLook, input: Input): LightMap? {
        val l = look.withDefaults()
        val gw = sc.gw; val gh = sc.gh; val n = gw * gh
        val direct = FloatArray(n)
        val rng = java.util.Random(2)
        val wrap = 0.3f; val M = 48
        val step0 = 0.001f * sc.far; val tol = 0.002f * sc.far; val rampIn = 0.005f * sc.far
        fun visible(i: Int, lx: Float, ly: Float, lz: Float, span: Float, jit: Float): Float {
            val sx = sc.P[i * 3] + sc.N[i * 3] * step0; val sy = sc.P[i * 3 + 1] + sc.N[i * 3 + 1] * step0; val sz = sc.P[i * 3 + 2] + sc.N[i * 3 + 2] * step0
            val minThick = 1.5f * span / M                     // never thinner than a step: thin things cannot be skipped over
            var vis = 1f
            for (s in 1..M) {
                val t = span * ((s - 1 + jit) / M)
                val qx = sx + lx * t; val qy = sy + ly * t; val qz = sz + lz * t
                if (qz <= 0.05f) continue
                val qu = (qx / qz * sc.f + sc.cx).toInt(); val qv = (qy / qz * sc.f + sc.cy).toInt()
                if (qu < 0 || qu >= gw || qv < 0 || qv >= gh) continue
                val j = qv * gw + qu; val zs = sc.Zs[j]; val th = max(sc.T[j], minThick)
                val behind = ((qz - zs - tol) / rampIn).coerceIn(0f, 1f) * ((zs + th - qz) / max(0.1f * th, 0.005f)).coerceIn(0f, 1f)
                vis *= 1f - behind
                if (vis < 0.01f) break
            }
            return vis
        }
        fun facing(i: Int, dx: Float, dy: Float, dz: Float) = ((sc.N[i * 3] * dx + sc.N[i * 3 + 1] * dy + sc.N[i * 3 + 2] * dz + wrap) / (1f + wrap)).coerceIn(0f, 1f)
        when (l.type) {
            "sun" -> {
                val d = toward(l, gw.toFloat() / gh) ?: return null
                val march = 0.375f * sc.far
                val op = opening(sc, input, l)
                for (i in 0 until n) {
                    val fa = facing(i, d[0], d[1], d[2]); val jit = rng.nextFloat()
                    if (fa > 0f) direct[i] = fa * (if (op != null)
                        throughOpening(sc, op, sc.P[i * 3] + sc.N[i * 3] * step0, sc.P[i * 3 + 1] + sc.N[i * 3 + 1] * step0, sc.P[i * 3 + 2] + sc.N[i * 3 + 2] * step0, d, jit)
                        else visible(i, d[0], d[1], d[2], march, jit))
                }
                if (op != null) {
                    val m = clean(sc, direct)
                    return LightMap(m.w, m.h, m.v, measured = op.sun / PI_F, floor = floorBounce(sc, op, l, d))
                }
            }
            "area" -> {
                // A panel, as in Maya: a rectangle facing its aim point, lighting only from its front
                // face (brightest straight ahead), sampled across its surface — so the bigger it is,
                // the softer its shadows (measured: full shadow 8% → 0.5% of the cat, 5 cm → 60 cm).
                val C = pointAt(sc, l.u, l.v, l.nudge)
                val A = if (!l.u2.isNaN()) pointAt(sc, l.u2, l.v2, 0f) else floatArrayOf(C[0], C[1] + 1f, C[2])
                var fx = A[0] - C[0]; var fy = A[1] - C[1]; var fz = A[2] - C[2]
                val fn = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f); fx /= fn; fy /= fn; fz /= fn
                // its own axes: across (level with the ground) and up its face
                var ux = -fz; var uy = 0f; var uz = fx                                // F × (0, −1, 0)
                val un = sqrt(ux * ux + uz * uz)
                if (un < 1e-4f) { ux = 1f; uz = 0f } else { ux /= un; uz /= un }
                val vx = fy * uz - fz * uy; val vy = fz * ux - fx * uz; val vz = fx * uy - fy * ux
                val hw = l.aw / 2f * gw / sc.f * C[2]; val hh = l.ah / 2f * gh / sc.f * C[2]
                val reachM = (0.05f + 0.6f * l.reach) * sc.far
                val S = 4
                for (i in 0 until n) {
                    var sum = 0f
                    for (a in 0 until S) for (b in 0 until S) {
                        val x = ((a + rng.nextFloat()) / S * 2f - 1f) * hw; val y = ((b + rng.nextFloat()) / S * 2f - 1f) * hh
                        val Lx = C[0] + ux * x + vx * y; val Ly = C[1] + uy * x + vy * y; val Lz = C[2] + uz * x + vz * y
                        val tx = Lx - sc.P[i * 3]; val ty = Ly - sc.P[i * 3 + 1]; val tz = Lz - sc.P[i * 3 + 2]
                        val r = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-4f)
                        val dx = tx / r; val dy = ty / r; val dz = tz / r
                        val emit = -(dx * fx + dy * fy + dz * fz); if (emit <= 0f) continue      // behind the panel: dark
                        val fa = facing(i, dx, dy, dz); if (fa <= 0f) continue
                        val fall = reachM * reachM / (r * r + reachM * reachM)
                        sum += fa * emit * fall * visible(i, dx, dy, dz, max(r - 0.01f * sc.far, 0f), rng.nextFloat())
                    }
                    direct[i] = sum / (S * S)
                }
            }
            else -> {
                // a lamp
                val lamps = listOf(pointAt(sc, l.u, l.v, l.nudge))
                val reachM = (0.05f + 0.6f * l.reach) * sc.far
                val exclude = 0.03f * sc.far                     // the lamp's own housing does not block it
                // a spot shines only within its cone, aimed at the depth seen at its aim point
                val aim = if (l.type == "spot" && !l.u2.isNaN()) pointAt(sc, l.u2, l.v2, 0f).let { a ->
                    val L = lamps[0]; val ax = a[0] - L[0]; val ay = a[1] - L[1]; val az = a[2] - L[2]; val an = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
                    floatArrayOf(ax / an, ay / an, az / an) } else null
                for (i in 0 until n) {
                    val jit = rng.nextFloat()
                    var sum = 0f
                    for (L in lamps) {
                        val tx = L[0] - sc.P[i * 3]; val ty = L[1] - sc.P[i * 3 + 1]; val tz = L[2] - sc.P[i * 3 + 2]
                        val r = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-4f)
                        val dx = tx / r; val dy = ty / r; val dz = tz / r
                        val fa = facing(i, dx, dy, dz); if (fa <= 0f) continue
                        var gate = 1f
                        if (aim != null) {
                            val cosA = -(dx * aim[0] + dy * aim[1] + dz * aim[2])
                            val ang = kotlin.math.acos(cosA.coerceIn(-1f, 1f))
                            val t = ((ang - l.cone * 0.8f) / (l.cone * 0.4f)).coerceIn(0f, 1f)
                            gate = 1f - t * t * (3f - 2f * t)
                            if (gate <= 0f) continue
                        }
                        val fall = reachM * reachM / (r * r + reachM * reachM)
                        sum += fa * fall * gate * visible(i, dx, dy, dz, max(r - exclude, 0f), jit)
                    }
                    direct[i] = sum / lamps.size
                }
            }
        }
        return clean(sc, direct)
    }

    private val bounces = object : LinkedHashMap<String, FloatArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 4
    }

    /**
     * One bounce, from what the photo shows (screen-space global illumination): each surface
     * gathers, over 24 directions in the half of space it faces (more straight out, as real light
     * arrives), the light that the visible surfaces it sees received from the added lights, tinted
     * by their colour (an average surface reflects ~18%). Only the added lights bounce — the
     * photo's real light already has its own bounce. Surfaces the camera never saw cannot bounce.
     * Returns RGB at GRID size, remembered until the lights or the scene change.
     */
    fun bounce(input: Input, lights: List<Pair<RaysLook, LightMap>>, sceneScale: Float, log: (String) -> Unit = {}): FloatArray? {
        if (lights.isEmpty()) return null
        val key = "${System.identityHashCode(input)}|${"%.3f".format(java.util.Locale.US, sceneScale)}|" + lights.joinToString(";") { it.first.key() }
        synchronized(bounces) { bounces[key] }?.let { return it }
        val t0 = System.currentTimeMillis()
        val sc = scene(input, sceneScale); val gw = sc.gw; val gh = sc.gh; val n = gw * gh
        // what each surface sends onwards: the added light it received x its colour
        val sorted = input.lum.copyOf().also { it.sort() }; val median = max(sorted[n / 2], 1e-5f)
        val send = FloatArray(n * 3)
        for ((look, map) in lights) {
            val c = look.chroma(); val k = look.surface
            for (i in 0 until n) {
                val d = map.v[i] * k; if (d <= 0f) continue
                for (ch in 0 until 3) send[i * 3 + ch] += (0.18f * input.small[i * 3 + ch] / median).coerceIn(0f, 0.9f) * d * c[ch]
            }
        }
        val K = 24; val M = 16; val span = 0.3f * sc.far
        val rng = java.util.Random(5)
        val got = FloatArray(n * 3)
        for (i in 0 until n) {
            val nx = sc.N[i * 3]; val ny = sc.N[i * 3 + 1]; val nz = sc.N[i * 3 + 2]
            // a frame around the surface's normal
            var ax = 0f; var ay = 1f; var az = 0f
            if (abs(ny) > 0.9f) { ax = 1f; ay = 0f }
            var t1x = ny * az - nz * ay; var t1y = nz * ax - nx * az; var t1z = nx * ay - ny * ax
            val tl = sqrt(t1x * t1x + t1y * t1y + t1z * t1z) + 1e-9f; t1x /= tl; t1y /= tl; t1z /= tl
            val t2x = ny * t1z - nz * t1y; val t2y = nz * t1x - nx * t1z; val t2z = nx * t1y - ny * t1x
            val sx = sc.P[i * 3] + nx * 0.002f * sc.far; val sy = sc.P[i * 3 + 1] + ny * 0.002f * sc.far; val sz = sc.P[i * 3 + 2] + nz * 0.002f * sc.far
            var r0 = 0f; var g0 = 0f; var b0 = 0f
            for (k in 0 until K) {
                val u1 = (k + rng.nextFloat()) / K; val u2 = rng.nextFloat()
                val rr = sqrt(u1); val phi = 2f * PI_F * u2; val z = sqrt(1f - u1)
                val dx = t1x * rr * kotlin.math.cos(phi) + t2x * rr * kotlin.math.sin(phi) + nx * z
                val dy = t1y * rr * kotlin.math.cos(phi) + t2y * rr * kotlin.math.sin(phi) + ny * z
                val dz = t1z * rr * kotlin.math.cos(phi) + t2z * rr * kotlin.math.sin(phi) + nz * z
                val jit = rng.nextFloat()
                for (st in 1..M) {
                    val q = (st - 1 + jit) / M; val t = span * q * sqrt(q)                 // finer steps near the surface
                    val qx = sx + dx * t; val qy = sy + dy * t; val qz = sz + dz * t
                    if (qz <= 0.05f) continue
                    val qu = (qx / qz * sc.f + sc.cx).toInt(); val qv = (qy / qz * sc.f + sc.cy).toInt()
                    if (qu < 0 || qu >= gw || qv < 0 || qv >= gh) break
                    val j = qv * gw + qu; val zs = sc.Zs[j]
                    if (qz > zs + 0.002f * sc.far && qz < zs + max(sc.T[j], 1.5f * span / M)) {
                        r0 += send[j * 3]; g0 += send[j * 3 + 1]; b0 += send[j * 3 + 2]; break
                    }
                }
            }
            got[i * 3] = r0 / K; got[i * 3 + 1] = g0 / K; got[i * 3 + 2] = b0 / K
        }
        // bounce light is soft: smoothed within surfaces, never across depth edges
        val out = FloatArray(n * 3)
        for (ch in 0 until 3) {
            val one = depthSmooth(FloatArray(n) { got[it * 3 + ch] }, sc.Zs, gw, gh, 5, 0.06f)
            for (i in 0 until n) out[i * 3 + ch] = one[i]
        }
        log("bounce worked out in ${System.currentTimeMillis() - t0} ms")
        return out.also { synchronized(bounces) { bounces[key] = it } }
    }
    private const val PI_F = 3.14159265f

    /** No seams on depth edges; noise smoothed within surfaces; the light blurred as the lens blurred the scene. */
    private fun clean(sc: Scene, direct: FloatArray): LightMap {
        val gw = sc.gw; val gh = sc.gh; val n = gw * gh
        val good = FloatArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val xa = max(x - 1, 0); val xb = min(x + 1, gw - 1); val ya = max(y - 1, 0); val yb = min(y + 1, gh - 1)
            val gx = (ln(sc.Zs[y * gw + xb]) - ln(sc.Zs[y * gw + xa])) / (xb - xa); val gy = (ln(sc.Zs[yb * gw + x]) - ln(sc.Zs[ya * gw + x])) / (yb - ya)
            good[y * gw + x] = if (sqrt(gx * gx + gy * gy) < 0.04f) 1f else 0f
        }
        val num = depthSmooth(FloatArray(n) { direct[it] * good[it] }, sc.Zs, gw, gh, 3, 0.04f)
        val den = depthSmooth(good, sc.Zs, gw, gh, 3, 0.04f)
        for (i in 0 until n) if (good[i] < 0.5f && den[i] > 1e-3f) direct[i] = num[i] / den[i]
        val smoothed = depthSmooth(direct, sc.Z, gw, gh, 2, 0.05f)
        val blurred = gauss(smoothed, gw, gh, gw / 40f)
        return LightMap(gw, gh, FloatArray(n) { sc.wf[it] * smoothed[it] + (1f - sc.wf[it]) * blurred[it] })
    }

    /** Distance in pixels to the nearest edge pixel: a two-pass chamfer (3-4), close to true distance. */
    private fun distanceToEdge(edge: BooleanArray, gw: Int, gh: Int): FloatArray {
        val big = 1e9f; val d = FloatArray(gw * gh) { if (edge[it]) 0f else big }
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x; var m = d[i]
            if (x > 0) m = min(m, d[i - 1] + 1f)
            if (y > 0) { m = min(m, d[i - gw] + 1f); if (x > 0) m = min(m, d[i - gw - 1] + 1.4142f); if (x < gw - 1) m = min(m, d[i - gw + 1] + 1.4142f) }
            d[i] = m
        }
        for (y in gh - 1 downTo 0) for (x in gw - 1 downTo 0) {
            val i = y * gw + x; var m = d[i]
            if (x < gw - 1) m = min(m, d[i + 1] + 1f)
            if (y < gh - 1) { m = min(m, d[i + gw] + 1f); if (x < gw - 1) m = min(m, d[i + gw + 1] + 1.4142f); if (x > 0) m = min(m, d[i + gw - 1] + 1.4142f) }
            d[i] = m
        }
        return FloatArray(gw * gh) { min(d[it], max(gw, gh).toFloat()) }
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
    internal fun depthSmooth(a: FloatArray, Z: FloatArray, gw: Int, gh: Int, radius: Int, depthSigma: Float): FloatArray {
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

    internal fun gauss(a: FloatArray, gw: Int, gh: Int, sigma: Float): FloatArray {
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
     * Adds every light to the picture, in place, before the film. Each light adds
     * strength × light × colour × what the surface reflects — the photo's own value there, or,
     * with "reveal", at least an ordinary surface in the room's colour carrying the photo's own
     * faint texture. With reveal at 0 this only scales what was recorded: black stays black.
     * The brush ([mask]) says where light may fall, for every light alike.
     */
    fun apply(src: Develop.Source, lights: List<Pair<RaysLook, LightMap>>, mask: ExposureMap?, input: Input, log: (String) -> Unit = {},
              bounce: FloatArray? = null, bounceAmount: Float = 0f) {
        val active = lights.filter { it.first.surface > 0f }
        if (active.isEmpty()) return
        log("light on surfaces (${active.size})")
        val w = src.width; val h = src.height
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val chroma = active.map { it.first.chroma() }
        val gw = input.gw; val gh = input.gh
        // The light lands on a cleaned copy of the photo: real added light brings clean signal, so
        // the photo's own noise must never be multiplied (it made lit areas 1.5–1.8× grainier).
        val clean = GuidedClean.factors(f, w, h, 1f)
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val cover = mask?.sample(u, v)?.coerceIn(0f, 1f) ?: 1f
                if (cover <= 0f) continue
                val o = (y * w + x) * 3
                val r = f.get(o); val g = f.get(o + 1); val b = f.get(o + 2)
                val k = clean[y * w + x]; val cr = r * k; val cg = g * k; val cb = b * k     // what the surface reflects, without the noise
                var ar = 0f; var ag = 0f; var ab = 0f
                for ((li, pair) in active.withIndex()) {
                    val (look, map) = pair
                    val d = map.sample(u, v) * cover
                    if (d <= 0f) continue
                    var fr = cr; var fg = cg; var fb = cb
                    if (look.reveal > 0f) {
                        // an ordinary surface where too little was recorded, keeping the photo's own texture — from the cleaned copy
                        val l = LUM[0] * cr + LUM[1] * cg + LUM[2] * cb
                        val tex = ((l + 1e-4f) / (bilinear(input.lumBig, gw, gh, u, v) + 1e-4f)).coerceIn(0.3f, 3f)
                        val fl = look.reveal * 0.03f * tex
                        fr = max(cr, fl * bilinear3(input.tint, gw, gh, u, v, 0)); fg = max(cg, fl * bilinear3(input.tint, gw, gh, u, v, 1)); fb = max(cb, fl * bilinear3(input.tint, gw, gh, u, v, 2))
                    }
                    val c = chroma[li]
                    if (map.measured > 0f) {
                        // a sun through a window, in absolute terms: its measured strength x the surface's own
                        // colour (the room's tint, the photo's texture, an ordinary 18%) — as in the proof that read as real
                        val l = LUM[0] * cr + LUM[1] * cg + LUM[2] * cb
                        val tex = ((l + 1e-4f) / (bilinear(input.lumBig, gw, gh, u, v) + 1e-4f)).coerceIn(0.3f, 3f)
                        val s = look.surface * d * map.measured * 0.18f * tex
                        ar += s * c[0] * bilinear3(input.tint, gw, gh, u, v, 0); ag += s * c[1] * bilinear3(input.tint, gw, gh, u, v, 1); ab += s * c[2] * bilinear3(input.tint, gw, gh, u, v, 2)
                    } else {
                        val s = look.surface * d
                        ar += s * c[0] * fr; ag += s * c[1] * fg; ab += s * c[2] * fb
                    }
                }
                // where window sunlight lands on the floor, it bounces up into the room (real at the middle of the slider)
                for ((li, pair) in active.withIndex()) {
                    val (look, map) = pair
                    val fm = map.floor ?: continue
                    val e = bounceStrength(bounceAmount) * look.surface * cover * bilinear(fm, gw, gh, u, v)
                    if (e <= 0f) continue
                    val l = LUM[0] * cr + LUM[1] * cg + LUM[2] * cb
                    val tex = ((l + 1e-4f) / (bilinear(input.lumBig, gw, gh, u, v) + 1e-4f)).coerceIn(0.3f, 3f)
                    val c = chroma[li]; val s = e * 0.18f * tex
                    ar += s * c[0] * bilinear3(input.tint, gw, gh, u, v, 0); ag += s * c[1] * bilinear3(input.tint, gw, gh, u, v, 1); ab += s * c[2] * bilinear3(input.tint, gw, gh, u, v, 2)
                }
                if (bounce != null && bounceAmount > 0f) {
                    // the bounce lands like any light: on what each surface reflects, without the noise
                    val m = bounceStrength(bounceAmount) * cover
                    ar += m * bilinear3(bounce, gw, gh, u, v, 0) * cr
                    ag += m * bilinear3(bounce, gw, gh, u, v, 1) * cg
                    ab += m * bilinear3(bounce, gw, gh, u, v, 2) * cb
                }
                if (ar != 0f || ag != 0f || ab != 0f) { f.put(o, r + ar); f.put(o + 1, g + ag); f.put(o + 2, b + ab) }
            }
        }
    }

    /** The bounce slider: 0 none … 0.5 real (physical) … 1 dramatic (4× real). */
    fun bounceStrength(b: Float): Float = if (b <= 0.5f) b / 0.5f else 1f + (b - 0.5f) / 0.5f * 3f

    private fun bilinear3(a: FloatArray, w: Int, h: Int, u: Float, vv: Float, c: Int): Float {
        val x = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat()); val y = (vv * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = x - x0; val fy = y - y0
        val p = a[(y0 * w + x0) * 3 + c] * (1 - fx) + a[(y0 * w + x1) * 3 + c] * fx
        val q = a[(y1 * w + x0) * 3 + c] * (1 - fx) + a[(y1 * w + x1) * 3 + c] * fx
        return p * (1 - fy) + q * fy
    }
}
