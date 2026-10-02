package com.celestial.latent.develop

import android.content.Context

/**
 * Darkroom choices that belong to the act of exporting, not to a look.
 *
 * Kept apart from the recipe on purpose: a recipe travels — it is what the camera develops each
 * shot with — and an export choice like print size must never ride along into that, or every
 * shot would quietly take several times longer. Kept apart from AppSettings too: the main screen
 * holds its own copy of those and saves it whole, which would overwrite a choice made here.
 */
object DarkroomPrefs {
    private const val FILE = "latent_darkroom"

    /**
     * The sizes offered. 1.5× was measured on the 15 Ultra (about 75 s, no trouble); 2× is offered
     * with its memory peak logged, so 2.5× can be decided on a measurement rather than a guess.
     */
    val PRINT_SIZES = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    /** "1×", "1.25×", "2×" — never "2.0×". */
    fun label(size: Float): String = (if (size % 1f == 0f) size.toInt().toString() else size.toString()) + "×"

    fun printSize(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("printSize", 1f)
            .takeIf { it in PRINT_SIZES } ?: 1f

    fun setPrintSize(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat("printSize", value).apply()
    }
}
