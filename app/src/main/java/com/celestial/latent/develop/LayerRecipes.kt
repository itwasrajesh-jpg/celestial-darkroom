package com.celestial.latent.develop

import org.json.JSONObject

/**
 * 109c: NEGATIVE per layer. Once a photo has layers, NEGATIVE's APPLY TO row says where a change
 * goes: ALL (the whole photo) or one layer. A layer changed there keeps a whole recipe of its own
 * ([Layer.recipe]); one never changed simply has the whole photo's.
 *
 * A change on ALL reaches the whole picture, layers included (Celestial, 2 Nov):
 *  - a number moves by the same amount everywhere, so a layer set 1 stop darker stays 1 stop darker
 *    (the enlarger's exposure, a time, by the same ratio);
 *  - anything else (a switch, a filter, the film) is set the same everywhere;
 *  - a look picked on ALL puts every layer on that look ([pickedOnAll]); each keeps its own light.
 * A layer whose settings end up the same as the whole photo's is just the whole photo's again.
 */
object LayerRecipes {

    /** The settings a layer develops with. */
    fun of(l: Layer, all: Recipe): Recipe = l.recipe ?: all

    /**
     * [r] moved the way [old] moved to [new]: what changed between them changes in [r] too, a number
     * by the same amount (clamped where it has hard limits), anything else to [new]'s. What did not
     * change between them stays [r]'s own. Also used to carry a render's own changes (a test strip's
     * step, the quick pass's lighter settings, the preview size) over to a layer's settings.
     */
    fun follow(r: Recipe, old: Recipe, new: Recipe): Recipe {
        if (old == new) return r
        if (r == old) return new
        val o = JSONObject(old.toJson()); val n = JSONObject(new.toJson()); val x = JSONObject(r.toJson())
        val keys = n.keys().asSequence().toList()
        for (k in keys) {
            val a = o.opt(k); val b = n.opt(k)
            if (a?.toString() == b?.toString()) continue
            val mine = x.opt(k)
            x.put(k, when {
                mine == null || mine.toString() == a?.toString() -> b
                a is Number && b is Number && mine is Number -> shifted(k, mine, a, b)
                else -> b
            })
        }
        return Recipe.fromJson(x.toString())
    }

    private fun shifted(k: String, mine: Number, a: Number, b: Number): Any = when {
        k == "printExposure" ->
            (mine.toDouble() * b.toDouble() / a.toDouble().coerceAtLeast(0.01)).coerceIn(PRINT_EXPOSURE_MIN.toDouble(), PRINT_EXPOSURE_MAX.toDouble())
        a is Int && b is Int -> mine.toInt() + (b - a)
        else -> {
            val v = mine.toDouble() + (b.toDouble() - a.toDouble())
            when {
                k == "exposureEv" -> v.coerceIn(-3.0, 3.0)
                k.startsWith("light") -> v.coerceIn(-1.0, 1.0)
                // the chosen strength of something can go to nothing, never below
                k.endsWith("Amount") || k.endsWith("Strength") -> v.coerceAtLeast(0.0)
                else -> v
            }
        }
    }

    /** A layer whose own settings came back to the whole photo's has none of its own. */
    fun tidy(l: Layer, all: Recipe): Layer = if (l.recipe != null && l.recipe == all) l.copy(recipe = null, lookId = null) else l

    /** The whole photo's settings went from [old] to [new]: every layer with its own follows. */
    fun followAll(layers: List<Layer>, old: Recipe, new: Recipe): List<Layer> =
        if (old == new || layers.none { it.recipe != null }) layers
        else layers.map { l -> if (l.recipe == null) l else tidy(l.copy(recipe = follow(l.recipe, old, new)), new) }

    /**
     * A look picked on ALL: every layer with its own settings takes it too, keeping its own light the
     * way picking a look keeps it ([SceneLight.keepLight]); [lookOf] is the look a layer's settings
     * started from (the layer's own, or the photo's before the pick).
     */
    fun pickedOnAll(layers: List<Layer>, newAll: Recipe, look: Recipe, lookOf: (Layer) -> Recipe): List<Layer> =
        layers.map { l -> if (l.recipe == null) l else tidy(l.copy(recipe = SceneLight.keepLight(l.recipe, lookOf(l), look), lookId = null), newAll) }

    /** Layers that have settings of their own, and are shown. */
    fun own(layers: List<Layer>?): List<Layer> = layers?.filter { it.visible && it.recipe != null && !it.mask.isBlank } ?: emptyList()
}
