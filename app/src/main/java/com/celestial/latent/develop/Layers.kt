package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One layer (109a, PRINT → LAYERS): a part of the photo, and what is done to its light.
 *
 * [mask] is where the layer is, on a mask grid (0 outside … 1 inside); it is painted, selected or
 * found coarse, and snapped to the photo's own edges when the photo develops ([Layers.apply]).
 * [ev] is exposure in stops, [warmth] −1 cool … +1 warm, [colour] ×0 grey … ×2 twice the colour.
 */
data class Layer(
    val id: Int,
    val name: String,
    /** Which of [Layers.COLOURS] tints the layer on the photo and on its card. */
    val tint: Int,
    val mask: ExposureMap,
    val ev: Float = 0f,
    val warmth: Float = 0f,
    val colour: Float = 1f,
    /** The eye on the card: a hidden layer keeps everything but changes nothing. */
    val visible: Boolean = true,
    /**
     * 109c: the layer's own NEGATIVE settings, the whole recipe, made when something is changed in
     * NEGATIVE with this layer chosen under APPLY TO; null = the whole photo's (ALL). The photo is
     * developed once more with it and blended in by the layer's area ([LayerLooks]).
     */
    val recipe: Recipe? = null,
    /** 109c: the look last picked for this layer in NEGATIVE (a preset id, or "stock:<name>"); null = the photo's. */
    val lookId: String? = null,
) {
    /** Does it change the light at all? */
    val acts: Boolean get() = visible && (abs(ev) > 1e-4f || abs(warmth) > 1e-4f || abs(colour - 1f) > 1e-4f) && !mask.isBlank

    /** What the card says under the name, as in the approved mock: "−0.6 EV · warm +0.35 · colour ×1.10". */
    fun summary(): String {
        val parts = buildList {
            if (abs(ev) >= 0.05f) add(String.format(java.util.Locale.US, "%+.1f EV", ev).replace('-', '−'))
            if (abs(warmth) >= 0.01f) add((if (warmth > 0) "warm " else "cool ") + String.format(java.util.Locale.US, "%+.2f", warmth).replace('-', '−'))
            if (abs(colour - 1f) >= 0.01f) add(String.format(java.util.Locale.US, "colour ×%.2f", colour))
        }
        return if (parts.isEmpty()) "no changes" else parts.joinToString(" · ")
    }

    /** Its mask hashed at the 1/1000 it is saved with, so a layer read back from the photo has the same key. */
    fun key(): String {
        var h = 1
        for (s in mask.stops) h = 31 * h + (if (s.isFinite()) s * 1000f else 0f).roundToInt()
        return "$id:${mask.width}x${mask.height}:$h:$ev:$warmth:$colour:$visible"
    }

    /** 109c: the area's hash only, for [LayerLooks.key] (its own settings do not care about the light changes). */
    internal fun maskKey(): String {
        var h = 1
        for (s in mask.stops) h = 31 * h + (if (s.isFinite()) s * 1000f else 0f).roundToInt()
        return "${mask.width}x${mask.height}:$h"
    }
}

/**
 * LAYERS (109a): every layer's changes, made to the scene's own light BEFORE the film (like the
 * LIGHT tab and dodging the negative), so the film still draws the result and a brightened sky
 * rolls off softly instead of clipping. Approved proof: layers/proof/ (scripts layers.py, auto.py).
 *
 * Overlapping layers add up, as two cards held over the light would: their stops add, their warmth
 * adds, and their colour changes add (×1.2 and ×1.1 → ×1.3).
 *
 * Edges: a mask is a coarse grid (128 cells on the long edge), so before it is used it is snapped to
 * the photo's edges with a guided filter, worked out on a picture of at most [GUIDE_EDGE] pixels and
 * brought to full size as the filter's own straight-line fit (He's fast guided filter): sharp where
 * the photo has an edge, soft where it has none, and the same at any print size.
 */
object Layers {
    /** The layers' colours on the photo and their cards: sky blue, amber, pink, green, violet, sand. */
    val COLOURS = intArrayOf(0xFF5B9BF0.toInt(), 0xFFF4B740.toInt(), 0xFFE86AB4.toInt(), 0xFF6CCB8A.toInt(), 0xFFA98BF2.toInt(), 0xFFE0B48A.toInt())
    const val MAX = 8

    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    /** The guide picture's long edge: the snapping's work does not grow with the photo. */
    const val GUIDE_EDGE = 512
    /** The guided filter's reach, of the guide's long edge (about one and a half mask cells), and its edge sensitivity. */
    const val REACH = 0.01f
    const val EPS = 1e-3f
    /** How much one step of warmth tilts red against blue (as the proof: ±12% at ±1). */
    const val WARM = 0.12f

    fun wanted(layers: List<Layer>?): Boolean = layers?.any { it.acts } == true

    /** 109d: the other side of an area: 1 − its share in each cell (a broken cell counts as outside). */
    fun inverted(m: ExposureMap): ExposureMap =
        ExposureMap(m.width, m.height, FloatArray(m.stops.size) { k -> val v = m.stops[k]; if (v.isFinite()) 1f - v.coerceIn(0f, 1f) else 1f })

    /** What the preparation reads: "-" when no layer changes anything. */
    fun key(layers: List<Layer>?): String = if (!wanted(layers)) "-" else layers!!.filter { it.acts }.sortedBy { it.id }.joinToString(";") { it.key() }   // 109b: order changes nothing in the picture, so not the key either

    /** The guide: brightness as a 0..1 value (mid grey 0.5), so shadows and highlights both have edges to snap to. */
    private fun guideOf(r: Float, g: Float, b: Float): Float {
        val y = LUM[0] * r + LUM[1] * g + LUM[2] * b
        // 109b: a broken pixel (NaN, infinite) must not reach the running sums, which would carry it
        // across the whole picture; it guides as black (NaN) or white (too bright)
        if (!(y > 0f)) return 0f
        if (y == Float.POSITIVE_INFINITY) return 1f
        return y / (y + 0.18f)
    }

    /** Box mean over (2r+1)² clipped at the borders (each cell divided by its own count). */
    internal fun boxMean(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0.0
            val row = y * w
            for (x in 0..min(r, w - 1)) s += a[row + x]
            for (x in 0 until w) {
                val lo = x - r; val hi = x + r
                val n = min(hi, w - 1) - max(lo, 0) + 1
                tmp[row + x] = (s / n).toFloat()
                if (hi + 1 < w) s += a[row + hi + 1]
                if (lo >= 0) s -= a[row + lo]
            }
        }
        for (x in 0 until w) {
            var s = 0.0
            for (y in 0..min(r, h - 1)) s += tmp[y * w + x]
            for (y in 0 until h) {
                val lo = y - r; val hi = y + r
                val n = min(hi, h - 1) - max(lo, 0) + 1
                out[y * w + x] = (s / n).toFloat()
                if (hi + 1 < h) s += tmp[(hi + 1) * w + x]
                if (lo >= 0) s -= tmp[lo * w + x]
            }
        }
        return out
    }

    /** The guide at the small size: each small pixel the mean of the full-size pixels in its box. */
    internal fun smallGuide(f: FloatBuffer, w: Int, h: Int, sw: Int, sh: Int): FloatArray {
        val sum = DoubleArray(sw * sh); val cnt = IntArray(sw * sh)
        val xs = IntArray(w) { x -> min(sw - 1, (x.toLong() * sw / w).toInt()) }
        for (y in 0 until h) {
            val sy = min(sh - 1, (y.toLong() * sh / h).toInt())
            var p = y * w * 3
            for (x in 0 until w) {
                val k = sy * sw + xs[x]
                sum[k] += guideOf(f.get(p), f.get(p + 1), f.get(p + 2)).toDouble(); cnt[k]++
                p += 3
            }
        }
        return FloatArray(sw * sh) { k -> if (cnt[k] > 0) (sum[k] / cnt[k]).toFloat() else 0f }
    }

    /** The guided filter's straight-line fit (a, b) of a mask against the small guide. */
    internal fun fit(I: FloatArray, mask: ExposureMap, sw: Int, sh: Int): Pair<FloatArray, FloatArray> {
        // 109e: a broken cell counts as outside (as [inverted] counts it), so it never spreads through the sums
        val p = FloatArray(sw * sh) { k -> mask.sample(((k % sw) + 0.5f) / sw, ((k / sw) + 0.5f) / sh).let { v -> if (v.isNaN()) 0f else v.coerceIn(0f, 1f) } }
        val r = max(1, (REACH * max(sw, sh)).roundToInt())
        val mI = boxMean(I, sw, sh, r); val mp = boxMean(p, sw, sh, r)
        val Ip = boxMean(FloatArray(sw * sh) { I[it] * p[it] }, sw, sh, r)
        val II = boxMean(FloatArray(sw * sh) { I[it] * I[it] }, sw, sh, r)
        val a = FloatArray(sw * sh); val b = FloatArray(sw * sh)
        for (k in a.indices) {
            a[k] = (Ip[k] - mI[k] * mp[k]) / (II[k] - mI[k] * mI[k] + EPS)
            b[k] = mp[k] - a[k] * mI[k]
        }
        return boxMean(a, sw, sh, r) to boxMean(b, sw, sh, r)
    }

    /** The small size for a picture: at most [GUIDE_EDGE] on its long edge, never larger than it is. */
    internal fun smallSize(w: Int, h: Int): Pair<Int, Int> {
        val s = min(1f, GUIDE_EDGE.toFloat() / max(w, h))
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /** Every layer's changes, in place, on interleaved linear RGB. */
    fun applyTo(f: FloatBuffer, w: Int, h: Int, layers: List<Layer>) {
        val act = layers.filter { it.acts }
        if (act.isEmpty()) return
        val (sw, sh) = smallSize(w, h)
        val I = smallGuide(f, w, h, sw, sh)
        val fits = act.map { fit(I, it.mask, sw, sh) }
        val n = act.size
        val ev = FloatArray(n) { act[it].ev }; val wm = FloatArray(n) { act[it].warmth }; val cl = FloatArray(n) { act[it].colour - 1f }
        val As = Array(n) { fits[it].first }; val Bs = Array(n) { fits[it].second }
        val fx = 1f * sw / w; val fy = 1f * sh / h
        // 109b: rows in bands over the phone's cores; each pixel is worked out on its own, so the answer
        // is the same for any number of bands
        val bands = max(1, min(Runtime.getRuntime().availableProcessors(), h / 64))
        fun band(y0b: Int, y1b: Int) { for (y in y0b until y1b) {
            val sy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = sy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = sy - y0
            var p = y * w * 3
            for (x in 0 until w) {
                val sx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = sx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = sx - x0
                val k00 = y0 * sw + x0; val k01 = y0 * sw + x1; val k10 = y1 * sw + x0; val k11 = y1 * sw + x1
                val r = f.get(p); val g = f.get(p + 1); val b = f.get(p + 2)
                val gi = guideOf(r, g, b)
                var e = 0f; var warm = 0f; var sat = 1f; var touched = false
                for (i in 0 until n) {
                    val A = As[i]; val B = Bs[i]
                    val aa = (A[k00] * (1 - tx) + A[k01] * tx) * (1 - ty) + (A[k10] * (1 - tx) + A[k11] * tx) * ty
                    val bb = (B[k00] * (1 - tx) + B[k01] * tx) * (1 - ty) + (B[k10] * (1 - tx) + B[k11] * tx) * ty
                    val wt = (aa * gi + bb).let { v -> if (v.isNaN()) 0f else v.coerceIn(0f, 1f) }
                    if (wt <= 0f) continue
                    touched = true
                    e += wt * ev[i]; warm += wt * wm[i]; sat += wt * cl[i]
                }
                // 109b: a pixel no layer reaches stays exactly as it was
                if (!touched) { p += 3; continue }
                if (sat < 0f) sat = 0f
                val gain = if (e == 0f) 1f else 2f.pow(e)
                val r2 = r * gain * (1f + WARM * warm); val g2 = g * gain; val b2 = b * gain * (1f - WARM * warm)
                val yy = LUM[0] * r2 + LUM[1] * g2 + LUM[2] * b2
                f.put(p, max(0f, yy + (r2 - yy) * sat)); f.put(p + 1, max(0f, yy + (g2 - yy) * sat)); f.put(p + 2, max(0f, yy + (b2 - yy) * sat))
                p += 3
            }
        } }
        if (bands == 1) band(0, h) else {
            val step = (h + bands - 1) / bands
            val ts = (0 until bands).map { k -> Thread { band(k * step, min(h, (k + 1) * step)) }.apply { start() } }
            ts.forEach { it.join() }
        }
    }

    fun apply(src: Develop.Source, layers: List<Layer>?, log: (String) -> Unit = {}) {
        if (!wanted(layers)) return
        val t0 = System.nanoTime()
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        applyTo(f, src.width, src.height, layers!!)
        log("layers")
        Log.i("Latent", "layers: ${layers.count { it.acts }} applied to ${src.width}x${src.height} in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    // ------------------------------------------------------------------------------------------
    // Finding a layer: the sky (auto.py, at the size of a mask grid), and "everything else".
    // ------------------------------------------------------------------------------------------

    /**
     * The sky, as auto.py finds it: far (within 0.10 of the farthest 2% by depth), bright, smooth
     * (glass, walls and leaves have texture even when far and bright), in pieces that start in the
     * upper 35% of the frame and are not specks, at least one of them touching the top edge. Worked out on [rgb] (display sRGB 0..1, interleaved)
     * and [near] (1 nearest … 0 farthest), both [w]×[h]; null when there is too little sky (under 1%).
     * The answer is hard-edged; the guided filter snaps it to the photo when it develops.
     */
    fun findSky(rgb: FloatArray, near: FloatArray, w: Int, h: Int): FloatArray? {
        val n = w * h
        val L = FloatArray(n) { k -> sqrt(max(0f, 0.2126f * rgb[3 * k] + 0.7152f * rgb[3 * k + 1] + 0.0722f * rgb[3 * k + 2])) }
        val farLimit = Air.quantile(near, 0.02f) + 0.10f
        val m = boxMean(L, w, h, 1); val m2 = boxMean(FloatArray(n) { L[it] * L[it] }, w, h, 1)
        var smooth = BooleanArray(n) { k -> sqrt(max(0f, m2[k] - m[k] * m[k])) < SKY_SMOOTH }
        smooth = dilate(erode(smooth, w, h), w, h)                      // an opening: specks of smoothness go
        val cand = BooleanArray(n) { k -> near[k] < farLimit && L[k] > 0.45f && smooth[k] }
        // pieces of sky (4-connected); a piece cut off from the top by leaves or wires still counts
        val label = IntArray(n); var next = 0
        val keep = ArrayList<Int>(); var touches = false
        val stack = IntArray(n)
        for (s in 0 until n) {
            if (!cand[s] || label[s] != 0) continue
            next++; var top = h; var size = 0; var sp = 0
            stack[sp++] = s; label[s] = next
            while (sp > 0) {
                val k = stack[--sp]; size++
                val x = k % w; val y = k / w
                if (y < top) top = y
                if (x > 0 && cand[k - 1] && label[k - 1] == 0) { label[k - 1] = next; stack[sp++] = k - 1 }
                if (x < w - 1 && cand[k + 1] && label[k + 1] == 0) { label[k + 1] = next; stack[sp++] = k + 1 }
                if (y > 0 && cand[k - w] && label[k - w] == 0) { label[k - w] = next; stack[sp++] = k - w }
                if (y < h - 1 && cand[k + w] && label[k + w] == 0) { label[k + w] = next; stack[sp++] = k + w }
            }
            if (top < 0.35f * h && size > 0.003f * n) { keep.add(next); if (top == 0) touches = true }
        }
        // a sky reaches the top of the frame: bright far windows and misty streets seen through an
        // arch start lower down and are not taken for it (found on the cat and the arch, 109a)
        if (!touches) return null
        val kept = keep.toHashSet()
        val sky = FloatArray(n) { k -> if (label[k] != 0 && label[k] in kept) 1f else 0f }
        return if (sky.sum() > 0.01f * n) sky else null
    }
    /** Sky is smooth: the local spread of brightness (3×3, on a grid twice the mask's) below this. */
    const val SKY_SMOOTH = 0.02f

    private fun erode(a: BooleanArray, w: Int, h: Int) = BooleanArray(w * h) { k ->
        val x = k % w; val y = k / w
        a[k] && (x == 0 || a[k - 1]) && (x == w - 1 || a[k + 1]) && (y == 0 || a[k - w]) && (y == h - 1 || a[k + w])
    }
    private fun dilate(a: BooleanArray, w: Int, h: Int) = BooleanArray(w * h) { k ->
        val x = k % w; val y = k / w
        a[k] || (x > 0 && a[k - 1]) || (x < w - 1 && a[k + 1]) || (y > 0 && a[k - w]) || (y < h - 1 && a[k + w])
    }

    /** A grid twice the mask's size, brought to the mask's grid: each cell the mean of its 2×2. */
    fun halve(a: FloatArray, w2: Int, h2: Int, w: Int, h: Int): FloatArray = FloatArray(w * h) { k ->
        val i = k % w; val j = k / w; var s = 0f; var c = 0
        for (dj in 0..1) for (di in 0..1) { val x = 2 * i + di; val y = 2 * j + dj; if (x < w2 && y < h2) { s += a[y * w2 + x]; c++ } }
        if (c > 0) s / c else 0f
    }

    /** "Everything else": what no other layer covers (overlaps count once). */
    fun everythingElse(others: List<Layer>, w: Int, h: Int): FloatArray = FloatArray(w * h) { k ->
        var covered = 0f
        val u = ((k % w) + 0.5f) / w; val v = ((k / w) + 0.5f) / h
        for (l in others) covered = max(covered, l.mask.sample(u, v).coerceIn(0f, 1f))
        1f - covered
    }

    // ------------------------------------------------------------------------------------------
    // Kept with the photo, like its painted masks: never in the recipe, which travels to new shots.
    // ------------------------------------------------------------------------------------------

    private fun file(context: Context, photo: Uri): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(photo.toString().toByteArray())
        return File(File(context.filesDir, "layers").apply { mkdirs() }, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun load(context: Context, photo: Uri): List<Layer> = runCatching {
        val f = file(context, photo)
        if (!f.exists()) return emptyList()
        val arr = JSONObject(f.readText()).getJSONArray("layers")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val w = o.getInt("w"); val h = o.getInt("h"); val m = o.getJSONArray("mask")
            require(m.length() == w * h)
            Layer(o.getInt("id"), o.getString("name"), o.getInt("tint"), ExposureMap(w, h, FloatArray(w * h) { m.getDouble(it).toFloat() }),
                o.getDouble("ev").toFloat(), o.getDouble("warmth").toFloat(), o.getDouble("colour").toFloat(), o.getBoolean("visible"),
                o.optJSONObject("recipe")?.let { Recipe.fromJson(it.toString()) },
                o.optString("lookId").takeIf { it.isNotEmpty() })
        }
    }.onFailure { Log.w("Latent", "layers: could not read this photo's layers: ${it.message}") }.getOrDefault(emptyList())

    fun save(context: Context, photo: Uri, layers: List<Layer>) {
        runCatching {
            val f = file(context, photo)
            if (layers.isEmpty()) { f.delete(); return }
            val arr = JSONArray()
            for (l in layers) arr.put(JSONObject().apply {
                put("id", l.id); put("name", l.name); put("tint", l.tint); put("w", l.mask.width); put("h", l.mask.height)
                put("ev", l.ev.toDouble()); put("warmth", l.warmth.toDouble()); put("colour", l.colour.toDouble()); put("visible", l.visible)
                if (l.recipe != null) put("recipe", JSONObject(l.recipe.toJson()))
                if (l.lookId != null) put("lookId", l.lookId)
                put("mask", JSONArray().apply { for (s in l.mask.stops) put((s * 1000f).roundToInt() / 1000.0) })
            })
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("version", 1).put("layers", arr).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w("Latent", "layers: could not save: ${it.message}") }
    }

    fun forget(context: Context, photo: Uri) { runCatching { file(context, photo).delete() } }
}
