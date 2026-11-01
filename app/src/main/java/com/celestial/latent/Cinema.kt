package com.celestial.latent

import android.content.Context
import android.net.Uri
import com.celestial.latent.develop.Recipe

/**
 * Cinema mode: stills shot the way a motion-picture camera would see them — a Vision3 negative
 * printed on a cinema print stock, a Super 35 frame (so grain and halation are relatively larger,
 * as they are on a cinema frame), a widescreen crop, and optionally the 85B filter that lets
 * tungsten film shoot in daylight, and film-still bars.
 */
object Cinema {
    val FILMS = listOf("kodak_vision3_50d" to "50D", "kodak_vision3_250d" to "250D",
        "kodak_vision3_200t" to "200T", "kodak_vision3_500t" to "500T")
    val PRINTS = listOf("kodak_2383" to "2383", "kodak_2393" to "2393")
    val ASPECTS = listOf(2.39f to "2.39", 2f to "2:1", 1.85f to "1.85")

    /** The Super 35 frame is 24.89 mm wide; 25 is within half a percent. */
    const val SUPER35_MM = 25f

    /** The T stocks are balanced for 3200K tungsten light — the ones an 85B is for. */
    fun tungsten(film: String) = film.endsWith("t")

    /** The recipe a cinema shot develops with: the normal recipe with the cinema choices on top. */
    fun recipe(base: Recipe, s: AppSettings): Recipe = base.copy(
        film = s.cinemaFilm, paper = s.cinemaPaper, filmFormatMm = SUPER35_MM,
        lens85b = s.cinema85b && tungsten(s.cinemaFilm),
    )

    /**
     * The 85B for the viewfinder. Its gains on linear light are 1.2044 : 1 : 0.4923 (see
     * Develop.lensFilterSource); the preview image is gamma-encoded, so they are taken to 1/2.2.
     */
    val PREVIEW_TINT_85B = floatArrayOf(1.0882f, 1f, 0.7245f)

    fun label(s: AppSettings): String {
        val film = FILMS.firstOrNull { it.first == s.cinemaFilm }?.second ?: s.cinemaFilm
        val print = PRINTS.firstOrNull { it.first == s.cinemaPaper }?.second ?: s.cinemaPaper
        val aspect = ASPECTS.firstOrNull { kotlin.math.abs(it.first - s.cinemaAspect) < 0.01f }?.second ?: "%.2f".format(s.cinemaAspect)
        return "CINEMA · $film · $print · $aspect" + (if (s.cinema85b && tungsten(s.cinemaFilm)) " · 85B" else "")
    }
}

/**
 * A photo's own recipe. A cinema shot is given one when it is taken: it must not follow the
 * shared recipe the camera develops new shots with (opening it would show it in the wrong film,
 * or, if edited, send Vision3 into every new photo).
 *
 * 107b: every photo edited or developed in the darkroom keeps its own too — what it was last
 * edited or developed with — so it opens with its own look whatever the camera's film is now, and
 * its edits never change the camera's film. Kept apart from the cinema ones ([edited]), so a
 * cinema shot is still known as one after it has been edited.
 */
object PhotoRecipes {
    private const val FILE = "latent_photo_recipes"
    private const val EDITS = "latent_photo_edits"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun edits(context: Context) = context.getSharedPreferences(EDITS, Context.MODE_PRIVATE)

    /** The photo's own recipe: as last edited or developed, else the cinema one it was shot with, else none. */
    fun load(context: Context, photo: Uri): Recipe? = edited(context, photo) ?: cinema(context, photo)

    /** The recipe a cinema shot was taken with, or null for every other photo. */
    fun cinema(context: Context, photo: Uri): Recipe? =
        prefs(context).getString(photo.toString(), null)?.let { runCatching { Recipe.fromJson(it) }.getOrNull() }

    /** What the photo was last edited or developed with (107b), or null when it never was. */
    fun edited(context: Context, photo: Uri): Recipe? =
        edits(context).getString(photo.toString(), null)?.let { runCatching { Recipe.fromJson(it) }.getOrNull() }

    /** A cinema shot's recipe, as it is taken. */
    fun save(context: Context, photo: Uri, recipe: Recipe) {
        prefs(context).edit().putString(photo.toString(), recipe.toJson()).apply()
    }

    /** The photo as edited or developed (107b): its own from now on. */
    fun saveEdited(context: Context, photo: Uri, recipe: Recipe) {
        edits(context).edit().putString(photo.toString(), recipe.toJson()).apply()
    }
}
