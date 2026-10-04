package com.celestial.latent.develop

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The air in a photo — its fog and its rays — as the Film Builder needs it.
 *
 * A reference made in this app carries fog and light that are not the film's doing. Measured as
 * they are, the fog's colour and veil were blamed on the film, so the builder made a flat, tinted
 * emulsion and left the fog itself out. Knowing the atmosphere instead lets every candidate film
 * be developed *with it* — the search then changes only the film, and the comparison is like for
 * like.
 *
 * Read from the note a developed JPEG carries inside it; failing that, from its original in the
 * roll, found by name (a developed file is named `<original>_<film>.jpg`).
 */
data class Atmosphere(
    val fog: FogLook,
    /** The painted fog's average over the picture: on another photo, an even veil this thick stands in for it. */
    val fogCover: Float,
    val rays: RaysLook,
    /** The original photo's file name, without extension — how a test shot is recognised as the same photo. */
    val originalStem: String?,
    /** The original in the roll, when it was found there: its masks and framing are read from it. */
    val original: Uri? = null,
) {
    val hasFog: Boolean get() = fogCover > 0.001f && fog.amount > 0f
    val hasRays: Boolean get() = rays.placed && rays.amount > 0f
    val isEmpty: Boolean get() = !hasFog && !hasRays

    /** A short description for the screen. */
    fun describe(): String = listOfNotNull(
        // how much of the scene the average veil hides — the fog's own e^-thickness, times its amount
        if (hasFog) "fog veil ${(100f * fog.amount * (1f - kotlin.math.exp(-fogCover))).toInt().coerceIn(1, 100)}%" else null,
        if (hasRays) "rays (${rays.type}, ${if (rays.mode == "gaps") "through gaps" else "added light"})" else null,
    ).joinToString(" · ").ifEmpty { "none" }

    /** The note written inside a developed JPEG, one field per line. */
    fun note(): String = listOf(
        "$HEADER v1",
        "stem=${originalStem ?: ""}",
        "fog=${fog.key()}",
        "fogcover=" + "%.4f".format(Locale.US, fogCover),
        "rays=${rays.key()}",
    ).joinToString("\n")

    companion object {
        const val HEADER = "CelestialDarkroom-Atmosphere"

        fun parseNote(text: String): Atmosphere? = runCatching {
            val lines = text.lines()
            if (lines.firstOrNull()?.startsWith(HEADER) != true) return null
            val f = lines.drop(1).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            Atmosphere(
                fog = FogLook.parse(f["fog"]),
                fogCover = f["fogcover"]?.toFloatOrNull() ?: 0f,
                rays = RaysLook.parse(f["rays"]),
                originalStem = f["stem"]?.ifBlank { null },
            )
        }.getOrNull()

        /** What a photo in the roll has painted on it now. */
        fun ofPhoto(context: Context, photo: Uri, stem: String?): Atmosphere {
            val fogMask = ExposureMaps.load(context, photo, ExposureMaps.FOG)
            return Atmosphere(
                fog = FogLooks.load(context, photo),
                fogCover = fogMask?.let { m -> m.stops.average().toFloat().coerceAtLeast(0f) } ?: 0f,
                rays = RaysLooks.load(context, photo),
                originalStem = stem,
                original = photo,
            )
        }

        /**
         * A reference's atmosphere. The note inside the file comes first — it records the fog
         * and light exactly as they were when it was developed. The original in the roll then
         * adds its masks and framing (and stands in entirely for files developed before notes
         * were written). Null for references from outside the app.
         */
        fun ofReference(context: Context, reference: Uri): Atmosphere? {
            val fromNote = readNote(context, reference)
            val refStem = Develop.baseNameOf(context, reference)
            val original = findOriginal(context, fromNote?.originalStem, refStem)
            val found = when {
                fromNote != null -> fromNote.copy(original = original?.first, originalStem = fromNote.originalStem ?: original?.second)
                original != null -> ofPhoto(context, original.first, original.second)
                else -> null
            }
            // Every lookup says what it did, in the log and on screen: a silent "none" cannot be
            // told apart from a lookup that broke.
            lastReport = when {
                found != null && !found.isEmpty -> "found: ${found.describe()}" + (if (fromNote != null) " (from the note in the file)" else " (from ${original?.second})")
                original == null -> "no original found for \"$refStem\""
                else -> "original ${original.second} has no fog or rays saved"
            }
            Log.i("Latent", "atmosphere: reference \"$refStem\" (${reference}) · note ${if (fromNote != null) "found" else "none"} · " +
                "original ${original?.let { "${it.second} (${it.first})" } ?: "none"} · ${found?.let { "fog cover %.3f amount %.2f, rays placed=%s amount %.2f".format(Locale.US, it.fogCover, it.fog.amount, it.rays.placed, it.rays.amount) } ?: "-"} · $lastReport")
            return found?.takeIf { !it.isEmpty }
        }

        /** What the last [ofReference] found or why it found nothing, in plain words. */
        @Volatile var lastReport: String = ""

        /**
         * The original a developed file came from: the roll's photo whose name the reference's
         * begins with, then "_". A developed file's name can itself be the start of another's
         * (`X_400` and `X_400_2`), so a photo with fog or rays saved wins, then a RAW, then the
         * longest name.
         */
        fun findOriginal(context: Context, knownStem: String?, refStem: String): Pair<Uri, String>? = runCatching {
            val found = ArrayList<Triple<Uri, String, String>>()   // uri, stem, extension
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/Latent%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val stem = name.substringBeforeLast('.')
                    val fits = if (knownStem != null) stem == knownStem else refStem.startsWith("${stem}_") && stem != refStem
                    if (fits) found += Triple(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), stem, name.substringAfterLast('.', ""))
                }
            }
            Log.i("Latent", "atmosphere: originals matching \"${knownStem ?: refStem}\": " +
                found.joinToString { "${it.second}.${it.third}" }.ifEmpty { "none among the app's photos" })
            found.maxWithOrNull(compareBy<Triple<Uri, String, String>>(
                { !ofPhoto(context, it.first, it.second).isEmpty },
                { it.third.equals("dng", ignoreCase = true) },
                { it.second.length },
            ))?.let { it.first to it.second }
        }.getOrElse { t -> Log.e("Latent", "atmosphere: could not search the app's photos", t); null }

        // ---- the note inside a JPEG: a comment segment, readable by any tool, ignored by viewers ----

        /** The JPEG with [text] in a comment segment, placed after the file's leading APP segments. */
        fun withNote(jpeg: ByteArray, text: String): ByteArray {
            if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
            val payload = text.toByteArray(Charsets.UTF_8)
            if (payload.size > 65000) return jpeg
            // after SOI and any APPn segments (JFIF and EXIF expect to come first)
            var at = 2
            while (at + 4 <= jpeg.size && jpeg[at] == 0xFF.toByte() && (jpeg[at + 1].toInt() and 0xFF) in 0xE0..0xEF) {
                val len = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
                at += 2 + len
            }
            if (at > jpeg.size) return jpeg
            val out = ByteArrayOutputStream(jpeg.size + payload.size + 4)
            out.write(jpeg, 0, at)
            val len = payload.size + 2
            out.write(0xFF); out.write(0xFE); out.write(len shr 8); out.write(len and 0xFF)
            out.write(payload)
            out.write(jpeg, at, jpeg.size - at)
            return out.toByteArray()
        }

        /** The atmosphere note inside a JPEG, if it has one. Reads only the file's opening segments. */
        fun readNote(context: Context, uri: Uri): Atmosphere? = runCatching {
            val head = context.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(256 * 1024); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                buf.copyOf(n)
            } ?: return null
            if (head.size < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
            var at = 2
            while (at + 4 <= head.size && head[at] == 0xFF.toByte()) {
                val marker = head[at + 1].toInt() and 0xFF
                if (marker == 0xDA) break                      // start of the picture itself: no more segments
                val len = ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
                if (marker == 0xFE && at + 2 + len <= head.size) {
                    val text = String(head, at + 4, len - 2, Charsets.UTF_8)
                    if (text.startsWith(HEADER)) return parseNote(text)
                }
                at += 2 + len
            }
            null
        }.getOrNull()

        // ---- putting the air into a decoded test photo, exactly as the darkroom does ----

        /**
         * The test photo with this atmosphere, ready for the film: framed, fogged and lit in the
         * darkroom's own order. On the *same* photo as the reference, its framing, painted masks
         * and light position fit exactly and are used as they are. On another photo the fog
         * becomes an even veil of the painted average, and the light keeps its place in the
         * frame — the closest stand-in available for a search.
         *
         * Returns [src] itself when nothing is framed; otherwise a new source, and [src] is closed.
         */
        fun applyTo(context: Context, src: Develop.Source, testShot: Uri, atmo: Atmosphere?, log: (String) -> Unit = {}): Develop.Source {
            if (atmo == null || atmo.isEmpty) return src
            val same = atmo.original != null && atmo.originalStem != null &&
                Develop.baseNameOf(context, testShot) == atmo.originalStem
            var framed: Develop.Source = src
            return runCatching {
                if (same) {
                    val photo = atmo.original!!
                    val framing = Framings.load(context, photo)
                    if (!framing.isIdentity) {
                        val (fw, fh) = framing.outputSize(src.width, src.height)
                        framed = src.blankSized(fw, fh).also { it.frameFrom(src, framing) }
                    }
                    val fogMask = ExposureMaps.load(context, photo, ExposureMaps.FOG)
                    val raysMask = ExposureMaps.load(context, photo, ExposureMaps.RAYS)
                    if (atmo.hasFog && fogMask != null) Fog.apply(framed, fogMask, atmo.fog, log)
                    if (atmo.hasRays) Rays.apply(framed, atmo.rays, raysMask, fogMask, atmo.fog.amount, log)
                    log("atmosphere: the reference's own fog and light, on the same photo")
                    if (framed !== src) src.close()
                    framed
                } else {
                    val veil = if (atmo.hasFog) ExposureMap.blank(src.width.toFloat() / src.height).also { m ->
                        java.util.Arrays.fill(m.stops, atmo.fogCover)
                    } else null
                    veil?.let { Fog.apply(src, it, atmo.fog, log) }
                    if (atmo.hasRays) Rays.apply(src, atmo.rays, null, veil, atmo.fog.amount, log)
                    log("atmosphere: an even veil and the reference's light, on another photo")
                    src
                }
            }.getOrElse { t ->
                // nothing half-done reaches the search: the plain photo goes on, and a framed
                // copy made before the failure is closed rather than left holding memory
                Log.e("Latent", "could not add the atmosphere to the test photo", t)
                if (framed !== src) runCatching { framed.close() }
                src
            }
        }
    }
}
