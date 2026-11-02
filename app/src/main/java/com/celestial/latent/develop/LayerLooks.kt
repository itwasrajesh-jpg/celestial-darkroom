package com.celestial.latent.develop

import android.content.Context
import android.util.Log
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** 109c: one set of layer settings to develop: the recipe, where it goes (those layers' areas together), and whose they are. */
data class LayerLook(val id: String, val recipe: Recipe, val mask: ExposureMap)

/**
 * 109c: NEGATIVE per layer ([LayerRecipes]). The film engine develops a whole picture with one set of
 * settings, so a layer with its own is made by developing the photo once more with them and blending
 * that print in by the layer's area, snapped to the print's own edges with the same guided filter as
 * the layers' light (proof: layers/109c-preset-per-layer/, the real engine on the palm and the cat).
 *
 * Each extra set costs one more develop and one more print held in memory, so at most [MAX_EXTRA]
 * besides the photo's own; layers with the same settings share one develop. Where a layer's settings
 * change what happens before the film (the LIGHT tab, noise cleaning, Night Clean, the 85B, the
 * diffusion filters, the film size), the photo is also prepared again for it.
 *
 * What always stays the photo's: the preview size, the GPU preview and the output colour space, so
 * both prints come out the same size and in the same colours to blend.
 */
object LayerLooks {
    const val MAX_EXTRA = 3

    /** A look's own starting recipe: a preset, or a recipe saved or built here ("stock:<name>"). */
    fun lookRecipe(context: Context, id: String): Recipe? =
        if (id.startsWith("stock:")) Recipes.load(context, id.removePrefix("stock:")) else Presets.byId(context, id)?.recipe

    /** A look's short name, for the layer's card: "GOLD 200", "BLEACH BYPASS", or a saved recipe's name. */
    fun name(context: Context, id: String): String =
        (if (id.startsWith("stock:")) id.removePrefix("stock:") else Presets.byId(context, id)?.name?.substringBefore(" — ") ?: id).uppercase()

    fun wanted(layers: List<Layer>?): Boolean = LayerRecipes.own(layers).isNotEmpty()

    /** What the prints read: "-" when no layer has settings of its own (so keys made before 109c still match). */
    fun key(layers: List<Layer>?): String = LayerRecipes.own(layers).sortedBy { it.id }
        .joinToString(";") { "${it.recipe!!.toJson().hashCode()}@${it.maskKey()}" }.ifEmpty { "-" }

    /**
     * The sets to develop for a print made with [main]: the photo's settings as this render uses
     * them (a test strip's step, the quick pass's lighter settings, the preview size included), when
     * the darkroom's own are [all]. Each layer's settings take the same changes ([LayerRecipes.follow]);
     * one that comes out the same as [main] needs nothing.
     */
    fun resolve(layers: List<Layer>?, all: Recipe, main: Recipe): List<LayerLook> {
        val ls = LayerRecipes.own(layers)
        if (ls.isEmpty()) return emptyList()
        val groups = ls.map { alignTo(LayerRecipes.follow(it.recipe!!, all, main), main) to it }
            .filter { it.first != main }
            .groupBy({ it.first }, { it.second })
        if (groups.size > MAX_EXTRA) Log.w("Latent", "layers: ${groups.size} sets of own settings, only the first $MAX_EXTRA are developed: " +
            groups.values.drop(MAX_EXTRA).flatten().joinToString { it.name })
        return groups.entries.take(MAX_EXTRA).map { (r, group) ->
                val m0 = group.first().mask
                // the group's areas together (overlaps count once): all on the first one's grid
                val m = if (group.size == 1) m0 else ExposureMap(m0.width, m0.height, FloatArray(m0.width * m0.height) { k ->
                    val u = ((k % m0.width) + 0.5f) / m0.width; val v = ((k / m0.width) + 0.5f) / m0.height
                    group.sumOf { it.mask.sample(u, v).coerceIn(0f, 1f).toDouble() }.toFloat().coerceAtMost(1f)
                })
                LayerLook(group.joinToString("+") { it.name }, r, m)
            }
    }

    /** The parts of a layer's recipe that must stay the photo's, so the two prints line up. */
    private fun alignTo(r: Recipe, main: Recipe): Recipe = r.copy(
        previewMaxSize = main.previewMaxSize, gpuPreview = main.gpuPreview,
        outputColorSpace = main.outputColorSpace, outputGamutCompress = main.outputGamutCompress,
    )

    /** The look masks for a part of the picture (the quick pass's centre, a zoomed region). */
    fun crop(looks: List<LayerLook>, f: (ExposureMap) -> ExposureMap): List<LayerLook> = looks.map { it.copy(mask = f(it.mask)) }

    /**
     * Develops [source] once with each layer's settings and blends them into [main] (the photo's own
     * print, in place) by each one's area snapped to [main]'s edges. [params] makes a set's engine
     * settings (the same as the photo's print gets: dodge & burn, print size). Where a set prepares the
     * photo differently from [mainRecipe], [prepare] gives the photo prepared for it (and whether it is
     * to be closed after; null = use [source]). All are developed first and then blended in one pass,
     * so every area snaps against the photo's print as it came from the film; where areas overlap past
     * full, they share the place.
     */
    fun blend(engine: SpektraEngine, source: Develop.Source, main: SimResult, looks: List<LayerLook>, preview: Boolean,
              mainRecipe: Recipe, prepare: ((Recipe) -> Pair<Develop.Source, Boolean>?)?,
              params: (Recipe) -> com.spectrafilm.engine.SpektraParams, log: (String) -> Unit = {}) {
        if (looks.isEmpty()) return
        val t = System.nanoTime()
        val w = main.width; val h = main.height
        val results = ArrayList<SimResult>()
        try {
            val used = ArrayList<LayerLook>()
            for (look in looks) {
                val own = if (prepare != null && Develop.preparedKeyOf(look.recipe) != Develop.preparedKeyOf(mainRecipe)) {
                    log("preparing the photo for ${look.id}…")
                    runCatching { prepare(look.recipe) }.getOrElse { t -> Log.e("Latent", "layer ${look.id}: could not prepare the photo", t); null }
                } else null
                log("developing ${look.id} with its own settings (${used.size + 1} of ${looks.size})…")
                val s = own?.first ?: source
                val r = try {
                    if (preview) engine.simulatePreview(s.image, params(look.recipe)) else engine.simulate(s.image, params(look.recipe))
                } finally { if (own != null && own.second) own.first.close() }
                if (r.width != w || r.height != h) { Log.w("Latent", "layer ${look.id}: ${r.width}x${r.height} is not the print's ${w}x$h; skipped"); r.close(); continue }
                results.add(r); used.add(look)
            }
            if (results.isEmpty()) return
            blendInto(main.data.order(ByteOrder.nativeOrder()).asFloatBuffer(), w, h,
                results.map { it.data.order(ByteOrder.nativeOrder()).asFloatBuffer() }, used.map { it.mask })
            Log.i("Latent", "layers' own settings ${used.joinToString { it.id }} developed and blended at ${w}x$h in ${(System.nanoTime() - t) / 1_000_000} ms")
        } finally {
            results.forEach { it.close() }
        }
    }

    /**
     * The blend itself: [outs] (prints in the looks, the same size as [f]) into [f] (the photo's print,
     * interleaved RGB, in place), each by its [masks] area snapped to [f]'s own edges.
     */
    fun blendInto(f: java.nio.FloatBuffer, w: Int, h: Int, outs: List<java.nio.FloatBuffer>, masks: List<ExposureMap>) {
        val n = outs.size
        // the guide: the photo's print as it came from the film (display brightness 0..1), small
        val (sw, sh) = Layers.smallSize(w, h)
        val I = run {
            val sum = DoubleArray(sw * sh); val cnt = IntArray(sw * sh)
            val xs = IntArray(w) { x -> min(sw - 1, (x.toLong() * sw / w).toInt()) }
            for (y in 0 until h) {
                val sy = min(sh - 1, (y.toLong() * sh / h).toInt()); var p = y * w * 3
                for (x in 0 until w) { val k = sy * sw + xs[x]; sum[k] += guideOf(f.get(p), f.get(p + 1), f.get(p + 2)).toDouble(); cnt[k]++; p += 3 }
            }
            FloatArray(sw * sh) { k -> if (cnt[k] > 0) (sum[k] / cnt[k]).toFloat() else 0f }
        }
        val As = Array(n) { Layers.fit(I, masks[it], sw, sh) }
        val fx = 1f * sw / w; val fy = 1f * sh / h
        val bands = max(1, min(Runtime.getRuntime().availableProcessors(), h / 64))
        fun band(ya: Int, yb: Int) {
            val wt = FloatArray(n)
            for (y in ya until yb) {
                val sy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = sy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = sy - y0
                for (x in 0 until w) {
                    val sx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = sx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = sx - x0
                    val p = 3 * (y * w + x)
                    val gi = guideOf(f.get(p), f.get(p + 1), f.get(p + 2))
                    var total = 0f
                    for (i in 0 until n) {
                        val (A, B) = As[i]
                        val aa = (A[y0 * sw + x0] * (1 - tx) + A[y0 * sw + x1] * tx) * (1 - ty) + (A[y1 * sw + x0] * (1 - tx) + A[y1 * sw + x1] * tx) * ty
                        val bb = (B[y0 * sw + x0] * (1 - tx) + B[y0 * sw + x1] * tx) * (1 - ty) + (B[y1 * sw + x0] * (1 - tx) + B[y1 * sw + x1] * tx) * ty
                        val v = aa * gi + bb
                        wt[i] = if (v.isNaN()) 0f else v.coerceIn(0f, 1f); total += wt[i]
                    }
                    if (total <= 0f) continue
                    // overlapping past full: they share the place
                    val scale = if (total > 1f) 1f / total else 1f
                    val keep = 1f - total * scale
                    for (c in 0..2) {
                        var v = f.get(p + c) * keep
                        for (i in 0 until n) v += outs[i].get(p + c) * wt[i] * scale
                        f.put(p + c, v)
                    }
                }
            }
        }
        if (bands == 1) band(0, h) else {
            val step = (h + bands - 1) / bands
            (0 until bands).map { b -> Thread { band(b * step, min(h, (b + 1) * step)) }.apply { start() } }.forEach { it.join() }
        }
    }

    /** The print's brightness for snapping (display values 0..1); a broken value counts as black. */
    private fun guideOf(r: Float, g: Float, b: Float): Float {
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (y > 0f) min(1f, y) else 0f
    }
}
