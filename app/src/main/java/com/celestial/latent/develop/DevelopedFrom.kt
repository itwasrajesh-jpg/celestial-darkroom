package com.celestial.latent.develop

import android.content.Context
import android.net.Uri

/**
 * 107b: what a photo's saved print was developed from — its recipe, framing and everything painted
 * on it — written down when the print is saved. When the darkroom opens the photo with exactly the
 * same, the print it already made is the picture: it is shown as it is instead of being developed
 * all over again. Anything different (an edit since, a print made before 107b) and the darkroom
 * develops as it always did.
 *
 * The age (97a) and the HDR glow (78a) are left out: the age only makes its own copy, and the
 * glow lives behind the picture, not in it.
 */
object DevelopedFrom {
    private const val FILE = "latent_developed_from"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun mapKey(m: ExposureMap?) = m?.let { "${it.width}x${it.height}:${it.stops.contentHashCode()}" } ?: "-"

    /** Everything that makes the print, as one short text. The defaults are a photo with nothing painted on it. */
    fun keyOf(
        recipe: Recipe, framing: Framing,
        exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null,
        fogMask: ExposureMap? = null, fogLook: FogLook = FogLook(),
        raysMask: ExposureMap? = null, raysLook: RaysLook = RaysLook(), extraLights: List<RaysLook> = emptyList(),
        depthSplit: ExposureMap? = null, saturationMap: ExposureMap? = null, lith: LithLook = LithLook(),
        wetMask: ExposureMap? = null, wetLook: WetLook = WetLook(), rainMask: ExposureMap? = null,
    ): String {
        val text = listOf(
            // the preview size is not the look; once through the photo's saved form, so a recipe read back
            // from it gives the same text. Auto exposure stays in: the darkroom always works without it,
            // so a print the camera made with it is not what the darkroom would make.
            Recipe.fromJson(recipe.copy(previewMaxSize = 0).toJson()).toJson(), framing.key(),
            mapKey(exposureMap), mapKey(softenMask), mapKey(fogMask), fogLook.key(),
            mapKey(raysMask), if (raysLook.placed) raysLook.key() else "-", ExtraLights.key(extraLights),
            mapKey(depthSplit), mapKey(saturationMap), lith.key(), mapKey(wetMask), wetLook.key(), mapKey(rainMask),
        ).joinToString("|")
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return sha.joinToString("") { "%02x".format(it) }
    }

    /** Written when the photo's print has been saved. */
    fun record(context: Context, photo: Uri, key: String) {
        prefs(context).edit().putString(photo.toString(), key).apply()
    }

    /** What the photo's last print was developed from, or null when that is not known. */
    fun of(context: Context, photo: Uri): String? = prefs(context).getString(photo.toString(), null)
}
