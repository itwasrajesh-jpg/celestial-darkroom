package com.celestial.latent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Other folders shown in the Roll (73a), each picked once through Android's own folder picker. The
 * access Android grants is remembered, so the app never needs a permission to see every photo on the
 * phone. Removing a folder only stops showing it here; its photos stay where they are.
 */
object RollFolders {
    private const val FILE = "latent_roll_folders"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    class Photo(val uri: Uri, val name: String, val date: Long)

    /** The picked folders, in the order they were added. */
    fun list(context: Context): List<Uri> =
        prefs(context).all.entries.sortedBy { (it.value as? Long) ?: 0L }.map { Uri.parse(it.key) }

    fun add(context: Context, tree: Uri) {
        prefs(context).edit().putLong(tree.toString(), System.currentTimeMillis()).apply()
    }

    fun remove(context: Context, tree: Uri) {
        prefs(context).edit().remove(tree.toString()).apply()
        runCatching {
            context.contentResolver.releasePersistableUriPermission(tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** "primary:DCIM/Camera" reads as "Camera". */
    fun label(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?.substringAfter(':')?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null } ?: "Folder"

    private val RAW = listOf(".dng", ".raw", ".arw", ".cr2", ".cr3", ".nef", ".orf", ".raf", ".rw2")
    fun isRaw(name: String) = RAW.any { name.endsWith(it, true) }

    /** The pictures directly in the folder, newest first. An unreachable folder (unplugged card, access withdrawn) is empty. */
    fun photos(context: Context, tree: Uri): List<Photo> = runCatching {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val out = ArrayList<Photo>()
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR || name.startsWith(".")) continue
                if (!mime.startsWith("image/") && !isRaw(name)) continue
                out += Photo(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, (if (c.isNull(3)) 0L else c.getLong(3)) / 1000)
            }
        }
        out.sortedByDescending { it.date }
    }.getOrDefault(emptyList())

    /** Deletes a picture from a picked folder. Folders have no trash, so this is final. */
    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}
