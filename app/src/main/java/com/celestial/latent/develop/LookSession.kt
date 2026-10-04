package com.celestial.latent.develop

import android.graphics.Bitmap
import android.net.Uri

/**
 * What the film builder is in the middle of, kept outside the screen.
 *
 * Choosing references and a test shot is real work, and a fit takes minutes. Holding that in
 * the screen meant a back gesture threw it all away — so it lives here instead, and lasts until
 * the app closes or the session is cleared on purpose.
 */
/**
 * Adjustments made to a fit's answer by hand: the things worth correcting by eye rather than
 * re-running a search for. Brightness and colour balance are the print's own controls, the
 * diffusion is the lens filter, and the output space decides what the file is written in.
 */
data class Tweak(
    val brightness: Float = 0f,        // stops, applied to the print exposure
    val warmCool: Float = 0f,          // the enlarger's yellow filter
    val greenMagenta: Float = 0f,      // its magenta filter
    val diffusion: Float = 0f,         // 0 = none
    val diffusionFamily: String = "black_pro_mist",
    val outputSpace: String = "SRGB",
)

object LookSession {

    var references: List<Pair<Uri, Fingerprint>> = emptyList()
    var textures: List<Texture> = emptyList()
    var thumbs: Map<Uri, Bitmap> = emptyMap()
    var testShot: Uri? = null
    /** Each reference's fog and light, when it was made in this app. */
    var atmospheres: Map<Uri, Atmosphere> = emptyMap()
    /** The screen's atmosphere switch: on, the film is matched with that air held fixed. */
    var useAtmosphere: Boolean = true
    var testIsRaw: Boolean = true
    var result: Reconstruct.Attempt? = null
    var resultBitmap: Bitmap? = null
    var progress: Reconstruct.Progress? = null
    var saved: String = ""
    var tweak: Tweak = Tweak()

    val isEmpty: Boolean
        get() = references.isEmpty() && testShot == null && result == null

    /** Forgets everything, so the next session starts clean. */
    fun clear() {
        references = emptyList()
        textures = emptyList()
        thumbs = emptyMap()
        testShot = null
        atmospheres = emptyMap()
        useAtmosphere = true
        testIsRaw = true
        result = null
        resultBitmap = null
        progress = null
        saved = ""
        tweak = Tweak()
    }
}
