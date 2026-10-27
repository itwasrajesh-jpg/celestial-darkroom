package com.celestial.latent.develop

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 90a: recipes travel between phones as one small JSON file (approved mock recipe-share/proof/).
 *
 * A darkroom recipe is only its film settings, so it travels as its [Recipe] JSON. A film built in the
 * Film Builder is also its own profile on disk, plus the air and light kept with it ([StockAirs]); all
 * of it travels together, or the film would not exist on the other phone. Nothing that belongs to one
 * photo (painted areas, placed lights, merge picks) is in a recipe, so none of it travels.
 *
 * The file says what it is and which version wrote it; unknown fields are ignored and missing ones take
 * the recipe's defaults, so an older or newer app can read it.
 */
object RecipeShare {
    const val VERSION = 1
    private const val KIND = "celestial-darkroom-recipes"

    /** One recipe in a file, or about to go into one. [profile]/[air]/[light] only for built films. */
    class Item(val name: String, val recipe: Recipe, val profile: String? = null, val air: String? = null, val light: String? = null) {
        val built get() = profile != null
    }

    class Pack(val from: String, val date: Long, val items: List<Item>)

    /** Everything saved on this phone that can be exported: your recipes first, then the films built here. */
    fun local(ctx: Context): List<Item> {
        EngineAssets.attach(ctx)
        val own = Recipes.saved(ctx).map { (n, r) -> Item(n, r) }
        val built = Recipes.stocks(ctx).map { (n, r) ->
            Item(n, r, profile = runCatching { EngineAssets.profileFile(r.film)?.readText() }.getOrNull(),
                air = StockAirs.load(ctx, r.film)?.key(), light = StockAirs.loadLight(ctx, r.film)?.key())
        }.filter { it.profile != null }
        return own + built
    }

    fun write(items: List<Item>): String = JSONObject().apply {
        put("kind", KIND); put("version", VERSION)
        put("from", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim())
        put("date", System.currentTimeMillis())
        put("items", JSONArray().apply {
            items.forEach { i ->
                put(JSONObject().apply {
                    put("name", i.name)
                    put("recipe", JSONObject(i.recipe.toJson()))
                    i.profile?.let { put("profile", JSONObject(it)) }
                    i.air?.let { put("air", it) }
                    i.light?.let { put("light", it) }
                })
            }
        })
    }.toString()

    /** The file's contents, or null if it is not a recipes file. */
    fun read(text: String): Pack? = runCatching {
        val o = JSONObject(text)
        if (o.optString("kind") != KIND) return null
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).mapNotNull { k ->
            val it = arr.optJSONObject(k) ?: return@mapNotNull null
            val name = Recipes.cleanName(it.optString("name"))
            val rec = it.optJSONObject("recipe") ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            Item(name, Recipe.fromJson(rec.toString()), it.optJSONObject("profile")?.toString(),
                it.optString("air").ifEmpty { null }, it.optString("light").ifEmpty { null })
        }
        Pack(o.optString("from"), o.optLong("date"), items)
    }.getOrNull()

    enum class Clash { KEEP_BOTH, REPLACE, SKIP }

    /** A free name: "Night lamps 2", "Night lamps 3", ... */
    fun freeName(ctx: Context, name: String): String {
        if (!Recipes.exists(ctx, name)) return name
        var n = 2
        while (Recipes.exists(ctx, Recipes.cleanName("$name $n"))) n++
        return Recipes.cleanName("$name $n")
    }

    /**
     * Adds [item] to this phone; [clash] says what to do when its name is taken. A built film's profile is
     * written under its own id unless a different profile already has that id, in which case keeping both
     * gives it a new id ("_2"...) and replacing overwrites it. Call off the main thread (it may copy the
     * engine's assets first). False if nothing was added.
     */
    fun add(ctx: Context, item: Item, clash: Clash): Boolean {
        val taken = Recipes.exists(ctx, item.name)
        if (taken && clash == Clash.SKIP) return false
        val name = if (taken && clash == Clash.KEEP_BOTH) freeName(ctx, item.name) else item.name
        var recipe = item.recipe
        if (item.profile != null) {
            EngineAssets.prepare(ctx) ?: return false
            var id = item.recipe.film
            if (!id.startsWith("celestial_")) return false
            val existing = EngineAssets.profileFile(id)?.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()).toString() }.getOrNull() }
            val incoming = JSONObject(item.profile)
            if (existing != null && existing != incoming.toString() && clash != Clash.REPLACE) {
                var n = 2
                while (EngineAssets.profileFile("${id}_$n")?.exists() == true) n++
                id = "${id}_$n"
                incoming.optJSONObject("info")?.put("stock", id)
            }
            val file = EngineAssets.profileFile(id) ?: return false
            file.parentFile?.mkdirs()
            file.writeText(incoming.toString())
            recipe = recipe.copy(film = id)
            item.air?.let { StockAirs.save(ctx, id, FogLook.parse(it)) }
            item.light?.let { StockAirs.saveLight(ctx, id, RaysLook.parse(it)) }
        }
        Recipes.save(ctx, name, recipe)
        return true
    }
}
