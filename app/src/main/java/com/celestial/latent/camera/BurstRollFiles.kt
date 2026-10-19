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
