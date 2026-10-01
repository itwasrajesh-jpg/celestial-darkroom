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

    /** The sizes offered. 2× is held back until a full-size export is measured on the phone. */
    val PRINT_SIZES = listOf(1.0f, 1.25f, 1.5f)

    fun printSize(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("printSize", 1f)
            .takeIf { it in PRINT_SIZES } ?: 1f

    fun setPrintSize(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat("printSize", value).apply()
    }
}
