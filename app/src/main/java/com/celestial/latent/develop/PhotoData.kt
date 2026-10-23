package com.celestial.latent.develop

import android.content.Context
import android.net.Uri

/**
 * Everything the darkroom keeps beside one photo — framing, fog, light, lith, wet, its own recipe and
 * its painted masks — removed when the photo is deleted from the Roll (73a), so nothing is left
 * behind under an address that no longer leads anywhere.
 */
object PhotoData {
    private val STORES = listOf("latent_frame", "latent_fog", "latent_rays", "latent_extra_lights", "latent_lith", "latent_wet", "latent_photo_recipes", "latent_hdr", "latent_fastshot")

    fun forget(context: Context, photo: Uri) {
        val key = photo.toString()
        STORES.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().remove(key).apply() }
        context.getSharedPreferences("latent_double", Context.MODE_PRIVATE).edit().remove("pair:$key").apply()
        ExposureMaps.forget(context, photo)
    }
}
