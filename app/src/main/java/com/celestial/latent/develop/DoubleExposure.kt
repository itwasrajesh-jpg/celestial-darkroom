package com.celestial.latent.develop

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size

/**
 * Double exposure: two frames of light on one piece of film.
 *
 * In the camera, "Don't advance" holds the last RAW as the first exposure — the film is not wound
 * on — until the next single RAW is taken, which completes the pair. The held frame survives the
 * app closing, as an unwound frame would. Each completed pair is remembered (second → first) so
 * the darkroom can show and print the combined frame. The light itself is added in Develop.
 */
object DoubleExposure {
    private const val FILE = "latent_double"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The first exposure waiting for its second, or null. */
    fun pending(context: Context): Uri? = prefs(context).getString("pending", null)?.let(Uri::parse)

    fun setPending(context: Context, first: Uri?) {
        prefs(context).edit().apply { if (first == null) remove("pending") else putString("pending", first.toString()) }.apply()
    }

    /** Remember that [second] was exposed onto [first]. */
    fun record(context: Context, first: Uri, second: Uri) {
        prefs(context).edit().putString("pair:$second", first.toString()).apply()
        Log.i("Latent", "double exposure: $second exposed onto $first")
    }

    /** The first exposure that [second] was made onto, if it is half of a pair. */
    fun firstFor(context: Context, second: Uri): Uri? = prefs(context).getString("pair:$second", null)?.let(Uri::parse)

    /**
     * The held frame, upright, for the viewfinder's ghost. The camera saves a JPEG beside every
     * RAW; it is there at once (the RAW's own develop takes the better part of a minute) and its
     * orientation is already right, so the ghost lines up with the live view. Falls back to the
     * system's thumbnail of the RAW.
     */
    fun ghost(context: Context, raw: Uri, maxEdge: Int = 1080): Bitmap? {
        val companion = runCatching {
            val stem = Develop.baseNameOf(context, raw)
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf("DCIM/Latent%", "$stem.jpg"), null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
        }.getOrNull()
        val fromJpeg = companion?.let { uri ->
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val long = maxOf(info.size.width, info.size.height)
                    if (long > maxEdge) {
                        val s = maxEdge.toFloat() / long
                        decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        return fromJpeg ?: runCatching { context.contentResolver.loadThumbnail(raw, Size(maxEdge, maxEdge), null) }.getOrNull()
    }
}
