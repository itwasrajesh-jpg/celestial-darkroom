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
    /** The note's full text, when this came from one: it also records the develop's texture. */
    val noteText: String? = null,
) {
    val hasFog: Boolean get() = fogCover > 0.001f && fog.amount > 0f
    val hasRays: Boolean get() = rays.placed && rays.amount > 0f
    val isEmpty: Boolean get() = !hasFog && !hasRays

    /** The same air, more or less of it: ADJUST's fog and rays, as multiples of the references'. */
    fun scaled(fogScale: Float, raysScale: Float): Atmosphere = copy(
        fog = fog.copy(amount = (fog.amount * fogScale).coerceIn(0f, 2f)),
        rays = rays.copy(amount = (rays.amount * raysScale).coerceIn(0f, 2f)),
    )

    /** What a full develop is given: framing, and the fog and rays with their masks. */
    data class Parts(val framing: Framing, val fogMask: ExposureMap?, val fogLook: FogLook, val raysMask: ExposureMap?, val raysLook: RaysLook)

    /** A short description for the screen. */
    fun describe(): String = listOfNotNull(
        // an atmosphere from the AIR tab (60b): rebuilt from each photo's own depth
        if (hasFog && fog.airKind() != null) "${fog.airKind()} air ×${"%.1f".format(Locale.US, fog.airScale)}, from each photo's depth"
        // how much of the scene the average veil hides — the fog's own e^-thickness, times its amount
        else if (hasFog) "fog veil ${(100f * fog.amount * (1f - kotlin.math.exp(-fogCover))).toInt().coerceIn(1, 100)}%" else null,
        if (hasRays) "rays (${rays.type}, ${if (rays.mode == "gaps") "through gaps" else "added light"})" else null,
    ).joinToString(" · ").ifEmpty { "none" }

    /**
     * The note written inside a developed JPEG, one field per line: the air, and — as [texture],
     * a line from [Texture.noteOf] — the grain, halation, diffusion filter and glare it used.
     */
    fun note(texture: String? = null): String = listOfNotNull(
        "$HEADER v1",
        "stem=${originalStem ?: ""}",
        "fog=${fog.key()}",
        "fogcover=" + "%.4f".format(Locale.US, fogCover),
        "rays=${rays.key()}",
        texture,
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
         * The original a developed file came from: the photo whose name the reference's begins
         * with, then "_" (or exactly the one the note names). Looked for three ways:
         *
         *  1. every photo with fog, rays, framing or a painted mask saved — under whatever address
         *     it was saved. An imported photo keeps the file picker's address, so this is the
         *     only way to find what was painted on it;
         *  2. the whole photo library by name — not just the app's folder: an imported original
         *     lives wherever the other camera put it;
         *  3. for each library match, the addresses a file picker gives the same photo, since
         *     that is what an import was saved under.
         *
         * A developed file's name can itself be the start of another's (`X_400`, `X_400_2`), so a
         * photo with fog or rays saved wins, then a RAW, then the longest name.
         */
        fun findOriginal(context: Context, knownStem: String?, refStem: String): Pair<Uri, String>? = runCatching {
            val found = LinkedHashMap<String, Triple<Uri, String, String>>()   // address -> uri, stem, extension
            fun consider(uri: Uri, name: String) {
                val stem = name.substringBeforeLast('.')
                val fits = if (knownStem != null) stem == knownStem else refStem.startsWith("${stem}_") && stem != refStem
                if (fits) found[uri.toString()] = Triple(uri, stem, name.substringAfterLast('.', ""))
            }
            // 1. what has settings saved, by its own address
            (FogLooks.photos(context) + RaysLooks.photos(context) + Framings.photos(context) + ExposureMaps.photos(context))
                .forEach { key -> val uri = Uri.parse(key); displayName(context, uri)?.let { consider(uri, it) } }
            // 2 + 3. the library by name, in every address form a picker gives
            val stems = if (knownStem != null) listOf(knownStem)
                else refStem.indices.filter { refStem[it] == '_' }.map { refStem.substring(0, it) }.takeLast(8)
            if (stems.isNotEmpty()) context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
                stems.joinToString(" OR ") { "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?" }, stems.map { "$it.%" }.toTypedArray(), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val name = c.getString(1) ?: continue; val rel = c.getString(2)
                    consider(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), name)
                    consider(android.provider.DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "image:$id"), name)
                    if (rel != null) consider(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel$name"), name)
                }
            }
            Log.i("Latent", "atmosphere: originals matching \"${knownStem ?: refStem}\": " +
                found.values.joinToString { "${it.second}.${it.third} @ ${it.first}" }.ifEmpty { "none" })
            found.values.maxWithOrNull(compareBy<Triple<Uri, String, String>>(
                { !ofPhoto(context, it.first, it.second).isEmpty },
                { it.third.equals("dng", ignoreCase = true) },
                { it.second.length },
            ))?.let { it.first to it.second }
        }.getOrElse { t -> Log.e("Latent", "atmosphere: could not search for the original", t); null }

        /** A file's name as its provider reports it; null when it cannot be read (permission gone, file deleted). */
        private fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

        /** The exact texture a reference was developed with, from the note inside it. */
        fun textureOf(context: Context, reference: Uri): Texture? =
            readNote(context, reference)?.noteText?.let { Texture.fromNote(it) }

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
                    if (text.startsWith(HEADER)) return parseNote(text)?.copy(noteText = text)
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
        /** Whether the test shot is the very photo the reference was developed from. */
        fun isSamePhoto(context: Context, testShot: Uri, atmo: Atmosphere): Boolean =
            atmo.original != null && atmo.originalStem != null && Develop.baseNameOf(context, testShot) == atmo.originalStem

        /**
         * The atmosphere as a full develop takes it — chosen exactly as [applyTo] chooses for the
         * preview, so the full-size picture shows what was judged: on the same photo, its own
         * framing, masks and light; on another, an even veil and the light where it was.
         */
        fun developParts(context: Context, testShot: Uri, atmo: Atmosphere?): Parts {
            if (atmo == null || atmo.isEmpty) return Parts(Framing(), null, FogLook(), null, RaysLook())
            if (isSamePhoto(context, testShot, atmo)) {
                val photo = atmo.original!!
                return Parts(
                    Framings.load(context, photo),
                    ExposureMaps.load(context, photo, ExposureMaps.FOG), atmo.fog,
                    ExposureMaps.load(context, photo, ExposureMaps.RAYS), atmo.rays,
                )
            }
            // an atmosphere: the one the preview rebuilt from this photo's depth (60b)
            if (atmo.hasFog) carriedAlready(testShot, atmo.fog)?.let { c ->
                return Parts(Framing(), c.mask, c.look(atmo.fog), null, if (atmo.hasRays) atmo.rays else RaysLook())
            }
            val veil = if (atmo.hasFog) ExposureMap.blank(1.5f).also { m -> java.util.Arrays.fill(m.stops, atmo.fogCover) } else null
            return Parts(Framing(), veil, atmo.fog, null, if (atmo.hasRays) atmo.rays else RaysLook())
        }

        // ---- an atmosphere carried to another photo (60b) ----

        /**
         * An atmosphere rebuilt on another photo: its thickness on the fog's grid, from that photo's
         * depth, and that photo's own air (its brightness and sun), which [look] puts into the
         * carried fog's colour.
         */
        class Carried(val mask: ExposureMap, val reading: Air.Reading) {
            fun look(fog: FogLook): FogLook = fog.copy(
                airLevel = reading.level * (if (fog.airKind() == "smog") Air.SMOG_DARKER else 1f),
                glowU = reading.sunU, glowV = reading.sunV)
        }

        /** Rebuilt atmospheres, by photo and recipe: the preview builds one, the full develop takes the same. */
        private val carriedMemory = object : LinkedHashMap<String, Carried>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Carried>?) = size > 4
        }
        private fun carriedKey(photo: Uri, fog: FogLook) =
            "$photo|${fog.airKind()}|${"%.3f".format(Locale.US, fog.airScale)}|${fog.airKeep}"

        /** The atmosphere already rebuilt on [photo] for this fog, if the preview has built it. */
        fun carriedAlready(photo: Uri, fog: FogLook): Carried? =
            if (fog.airKind() == null) null else synchronized(carriedMemory) { carriedMemory[carriedKey(photo, fog)] }

        /**
         * [fog]'s atmosphere built on another photo, exactly as the AIR tab builds it: the kind's
         * thickness from [src]'s depth, times its scale, the foreground cleared if it was, and the
         * air's brightness read from [src]'s farthest part. [src] must be the unframed photo,
         * before any fog. Null for painted fog, or without the depth model (an even veil then).
         */
        fun carried(context: Context, photo: Uri, src: Develop.Source, fog: FogLook, log: (String) -> Unit = {}): Carried? {
            val kind = fog.airKind() ?: return null
            carriedAlready(photo, fog)?.let { return it }
            if (!Depth.isReady(context)) { log("atmosphere: the reference's $kind air needs the depth model to be rebuilt — an even veil instead"); return null }
            return runCatching {
                val input = Sun.prepare(context, photo, Framing(), src, log) ?: return null
                val grid = ExposureMap.blank(src.width.toFloat() / src.height); val w = grid.width; val h = grid.height
                val near = Air.nearOn(input.depth, Depth.SIZE, w, h)
                val thick = Air.built(kind, fog.airScale, near, w, h, if (fog.airKeep) Air.foreground(near) else null)
                val reading = Develop.readAirOf(src, Framing(), near, w, h)
                Log.i("Latent", "atmosphere: carried $kind ×${"%.2f".format(Locale.US, fog.airScale)}${if (fog.airKeep) ", foreground clear" else ""} · " +
                    "average thickness ${"%.3f".format(Locale.US, thick.average())} · brightness ${"%.2f".format(Locale.US, reading.level)} · sun at ${"%.2f".format(Locale.US, reading.sunU)},${"%.2f".format(Locale.US, reading.sunV)}")
                Carried(ExposureMap(w, h, thick), reading).also { c -> synchronized(carriedMemory) { carriedMemory[carriedKey(photo, fog)] = c } }
            }.getOrElse { t -> Log.e("Latent", "atmosphere: could not rebuild the air", t); null }
        }

        fun applyTo(context: Context, src: Develop.Source, testShot: Uri, atmo: Atmosphere?, log: (String) -> Unit = {}): Develop.Source {
            if (atmo == null || atmo.isEmpty) return src
            val same = isSamePhoto(context, testShot, atmo)
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
                    // an atmosphere from the AIR tab is built again from this photo's depth (60b);
                    // painted fog, which has no recipe, stays an even veil of its average
                    val carried = if (atmo.hasFog) carried(context, testShot, src, atmo.fog, log) else null
                    val veil = carried?.mask ?: if (atmo.hasFog) ExposureMap.blank(src.width.toFloat() / src.height).also { m ->
                        java.util.Arrays.fill(m.stops, atmo.fogCover)
                    } else null
                    val look = carried?.look(atmo.fog) ?: atmo.fog
                    veil?.let { Fog.apply(src, it, look, log) }
                    if (atmo.hasRays) Rays.apply(src, atmo.rays, null, veil, atmo.fog.amount, log)
                    log(if (carried != null) "atmosphere: the reference's ${atmo.fog.airKind()} air, rebuilt from this photo's depth, and its light"
                        else "atmosphere: an even veil and the reference's light, on another photo")
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

/**
 * The air a saved film carries (60b): when a stock is saved from the Film Builder while its
 * references' atmosphere is on, the atmosphere's recipe — kind, thickness, foreground, colour — is
 * kept under the film's id, and the darkroom's AIR tab offers it on any photo with that film.
 * Its light too (60c): the reference's light 1, offered on the LIGHT tab.
 */
object StockAirs {
    private const val FILE = "latent_stock_air"

    fun save(context: Context, film: String, fog: FogLook) = prefs(context).edit().putString(film, fog.key()).apply()

    /** The film's air, or null when it has none (or what was kept is not an atmosphere). */
    fun load(context: Context, film: String): FogLook? =
        prefs(context).getString(film, null)?.let { FogLook.parse(it) }?.takeIf { it.airKind() != null }

    fun delete(context: Context, film: String) = prefs(context).edit().remove(film).remove(film + LIGHT).apply()

    /**
     * The film's light (60c): everything about it but its window — an opening's corners belong to
     * the one photo they were fitted on, so a sun through a window travels as a plain sun.
     */
    fun saveLight(context: Context, film: String, light: RaysLook) = prefs(context).edit()
        .putString(film + LIGHT, light.copy(ox0 = Float.NaN, oy0 = Float.NaN, ox1 = Float.NaN, oy1 = Float.NaN).withDefaults().key()).apply()

    /** The film's light, or null when it has none (or what was kept is not a placed light). */
    fun loadLight(context: Context, film: String): RaysLook? =
        prefs(context).getString(film + LIGHT, null)?.let { RaysLook.parse(it) }?.takeIf { it.placed }

    fun deleteLight(context: Context, film: String) = prefs(context).edit().remove(film + LIGHT).apply()

    private const val LIGHT = "|light"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
