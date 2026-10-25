package com.celestial.latent.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** Burst rolls on the phone: finding them, opening them, and turning a frame back into a DNG. */
object BurstRollFiles {
    class Item(val uri: Uri, val name: String, val sizeBytes: Long, val dateAdded: Long)

    /** Every finished burst roll this app saved, newest first. */
    fun list(context: Context): List<Item> {
        val out = ArrayList<Item>()
        val files = MediaStore.Files.getContentUri("external")
        runCatching {
            context.contentResolver.query(files,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("${BurstRoll.FOLDER}%", "%.${BurstRoll.EXT}"), "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) out += Item(ContentUris.withAppendedId(files, c.getLong(0)), c.getString(1), c.getLong(2), c.getLong(3))
            }
        }
        return out
    }

    /** Opens a roll for reading. Close the returned reader when done (it closes the file too). */
    fun open(context: Context, uri: Uri): BurstRoll.Reader {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("cannot open the burst")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return try { BurstRoll.Reader(stream.channel) } catch (e: Exception) { stream.close(); throw e }
    }

    /** "LATENT_20261019_142736_1x_BURST" from "LATENT_20261019_142736_1x_BURST.lburst". */
    fun stem(name: String) = name.substringBeforeLast('.')

    /** Frame [i] (0-based) as a DNG in DCIM/Latent, exactly as it was shot. Returns its Uri. */
    fun exportFrame(context: Context, reader: BurstRoll.Reader, rollName: String, i: Int): Uri {
        val name = "${stem(rollName)}_%02d.dng".format(i + 1)
        val c = reader.index.common
        val meta = reader.dngMeta(i, "Celestial Darkroom burst frame ${i + 1} of ${reader.index.frames.size} - ${c.lens}${if (c.dcg) " (DCG)" else ""}")
        val px = reader.pixels(i)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.buffered(1 shl 20).use { DngWriter.write(it, meta, px) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * The whole roll merged into one RAW (68b) in DCIM/Latent: a burst averaged on its sharpest frame
     * (`<stem>_MERGED.dng`), or a focus stack blended front to back (`<stem>_STACKED.dng`). Values
     * are kept at higher precision (x16 for 10-bit, x4 for 14-bit DCG) with black and white scaled the same.
     * [picks] (86d): an HDR bracket's areas picked by hand in the merge editor.
     */
    fun merge(context: Context, reader: BurstRoll.Reader, rollName: String, picks: List<MergePicks.Pick> = emptyList(), progress: (String) -> Unit): Uri {
        val c = reader.index.common; val n = reader.index.frames.size
        val focus = c.kind == BurstRoll.KIND_FOCUS
        val bracket = c.kind == BurstRoll.KIND_BRACKET
        val t0 = System.nanoTime()
        val r = if (focus) BurstMerge.focus(n, reader::pixels, c.width, c.height, c.black, c.white, c.cfa, progress)
                else if (bracket) BurstMerge.bracket(n, reader::pixels, c.width, c.height, c.black, c.white, c.cfa, exposures(reader), progress, picks, c.orientation == 6)
                else BurstMerge.burst(n, reader::pixels, c.width, c.height, c.black, c.white, c.cfa, reader.sharpest, progress)
        val secs = (System.nanoTime() - t0) / 1e9
        val hdr = c.kind == BurstRoll.KIND_HDR
        val desc = if (focus) "Celestial Darkroom focus stack of $n frames - ${c.lens}${if (c.dcg) " (DCG)" else ""}"
                   else if (bracket) "Celestial Darkroom HDR bracket: ${r.used} of $n frames, merged on frame ${r.ref + 1}${if (picks.isNotEmpty()) ", ${picks.size} area(s) picked by hand" else ""} - ${c.lens}${if (c.dcg) " (DCG)" else ""}"
                   else if (hdr) "Celestial Darkroom HDR stack: ${r.used} of $n frames shot ${"%.1f".format(c.brighten)} stops dark, merged on frame ${r.ref + 1} - ${c.lens}${if (c.dcg) " (DCG)" else ""}"
                   else "Celestial Darkroom merge of ${r.used} of $n frames on frame ${r.ref + 1} - ${c.lens}${if (c.dcg) " (DCG)" else ""}"
        val m = reader.dngMeta(r.ref, desc)
        val meta = DngWriter.Meta(m.width, m.height, m.blackLevel * r.scale, m.whiteLevel * r.scale, m.cfaPattern, m.colorMatrix1, m.colorMatrix2,
            m.forwardMatrix1, m.cameraCalibration1, m.illuminant1, m.illuminant2, m.asShotNeutral, m.orientation, m.uniqueModel, desc, m.exposureNs, m.iso,
            if (bracket) r.baseline else m.baselineExposure)
        android.util.Log.i("Latent", "burst merge $rollName: ${if (focus) "focus stack" else if (hdr) "hdr stack (+${"%.2f".format(c.brighten)} EV)" else if (bracket) "hdr bracket" else "merge"} of ${r.used}/$n, ref #${r.ref + 1}, ${"%.1f".format(secs)} s. ${r.note}")
        progress("Saving…")
        val name = "${stem(rollName)}_${if (focus) "STACKED" else "MERGED"}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.buffered(1 shl 20).use { DngWriter.write(it, meta, r.pixels) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * 86b: each frame's exposure (shutter x ISO) as the camera reported it. A frame missing a number
     * gets its neighbour's (the merge then treats them as the same exposure).
     */
    fun exposures(reader: BurstRoll.Reader): DoubleArray {
        val fr = reader.index.frames
        val e = DoubleArray(fr.size) { i -> val f = fr[i]; if (f.exposureNs != null && f.iso != null && f.exposureNs > 0 && f.iso > 0) f.exposureNs.toDouble() * f.iso else 0.0 }
        val known = e.firstOrNull { it > 0 } ?: 1.0
        for (i in e.indices) if (e[i] <= 0) e[i] = (if (i > 0) e[i - 1] else known)
        return e
    }

    /** The DNG already exported for frame [i], if any (so Develop does not make a second copy). */
    fun exported(context: Context, rollName: String, i: Int): Uri? {
        val name = "${stem(rollName)}_%02d.dng".format(i + 1)
        return runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf("DCIM/Latent%", name), null,
            )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }
        }.getOrNull()
    }

    fun delete(context: Context, uri: Uri): Boolean = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /** Reads the whole preview of the sharpest frame (for a roll tile), or null. */
    fun sharpestPreview(context: Context, uri: Uri): ByteArray? = runCatching { open(context, uri).use { it.preview(it.sharpest) } }.getOrNull()
}
