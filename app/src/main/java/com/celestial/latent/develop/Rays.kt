package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * God rays for one photo: where the light comes from ([u], [v], 0..1 across and down the framed
 * picture; NaN until placed), how bright the rays are ([amount], 0..1) and how far they reach
 * ([length], as a share of the picture's long edge).
 */
data class RaysLook(val u: Float = Float.NaN, val v: Float = Float.NaN, val amount: Float = 0.3f, val length: Float = 0.5f) {
    val placed: Boolean get() = u in 0f..1f && v in 0f..1f
    fun key(): String = listOf(u, v, amount, length).joinToString(",") { "%.4f".format(Locale.US, it) }

    companion object {
        fun parse(s: String?): RaysLook {
            if (s.isNullOrEmpty()) return RaysLook()
            return runCatching { val p = s.split(",").map { it.toFloat() }; RaysLook(p[0], p[1], p[2], p[3]) }.getOrDefault(RaysLook())
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

    fun apply(src: Develop.Source, look: RaysLook, mask: ExposureMap?, log: (String) -> Unit = {}) {
        if (!look.placed || look.amount <= 0f) return
        if (mask != null && mask.stops.all { it <= 0f }) return
        log("god rays")
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
        // 3. trace: from each place toward the light, gathering bright light that fades with distance
        val lx = look.u * gw; val ly = look.v * gh
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
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val px = gx + 0.5f; val py = gy + 0.5f
            val dist = sqrt((lx - px) * (lx - px) + (ly - py) * (ly - py)) / long
            var r = 0f; var g = 0f; var b = 0f
            for (i in 0 until STEPS) {
                val t = i.toFloat() / STEPS
                val wgt = exp(-dist * t / len)
                val sx = px + (lx - px) * t; val sy = py + (ly - py) * t
                r += sample(sx, sy, 0) * wgt; g += sample(sx, sy, 1) * wgt; b += sample(sx, sy, 2) * wgt
            }
            val o = (gy * gw + gx) * 3
            val k = look.amount / STEPS
            rays[o] = r * k; rays[o + 1] = g * k; rays[o + 2] = b * k
        }
        // 4. enlarge smoothly onto the picture and add, where the rays may fall
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            val gyf = (v * gh - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = gyf.toInt(); val y1 = minOf(y0 + 1, gh - 1); val fy = gyf - y0
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val cover = mask?.sample(u, v)?.coerceIn(0f, 1f) ?: 1f
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

/** Each photo's rays, kept beside it like its masks. */
object RaysLooks {
    private const val FILE = "latent_rays"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun load(context: Context, photo: Uri): RaysLook = RaysLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: RaysLook) {
        prefs(context).edit().apply { if (!look.placed) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}
