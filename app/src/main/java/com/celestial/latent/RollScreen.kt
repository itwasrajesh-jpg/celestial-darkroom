@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.DevelopQueue
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.launch

private data class Frame(
    val uri: Uri, val name: String, val isRaw: Boolean, val developed: Boolean, val date: Long = 0, val isBurst: Boolean = false,
    /** Its developed JPGs in the gallery, which the roll hides behind the capture (73a: offered for deletion with it). */
    val copies: List<Uri> = emptyList(),
    /** The added folder it comes from (73a); null for the app's own roll. */
    val folder: String? = null,
)

/** The roll: every capture in DCIM/Latent, developed ones in colour, undeveloped ones waiting; plus any folders added (73a). */
@Composable
fun RollScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenDarkroom: (Uri, Boolean) -> Unit = { _, _ -> },
    onOpenLookBuilder: () -> Unit = {},
    onOpenBurst: (Uri) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var frames by remember { mutableStateOf<List<Frame>>(emptyList()) }
    var filter by remember { mutableStateOf("all") }
    var queued by remember { mutableStateOf(DevelopQueue.queued) }
    var thumbs by remember { mutableStateOf<Map<Uri, Bitmap>>(emptyMap()) }
    // 73a: added folders, and choosing photos to delete
    var folders by remember { mutableStateOf(RollFolders.list(context)) }
    var removeFolderAsk by remember { mutableStateOf<Uri?>(null) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var deleteAsk by remember { mutableStateOf(false) }
    var withCopies by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var deleteNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun scan() {
        val raws = ArrayList<Frame>()
        val developedStems = HashSet<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/Latent%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val all = ArrayList<Pair<Uri, String>>()
            val dates = HashMap<Uri, Long>()
            while (c.moveToNext()) { val u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)); all += u to c.getString(1); dates[u] = c.getLong(2) }
            // A developed file is named <capture>_<film>.jpg, so its stem identifies the capture.
            val copies = HashMap<String, MutableList<Uri>>()
            val allStems = all.mapTo(HashSet()) { it.second.substringBeforeLast('.') }
            all.forEach { (u, n) -> if (n.endsWith(".jpg", true) && n.contains('_')) {
                val capture = n.substringBeforeLast('.').substringBeforeLast('_')
                developedStems += capture; copies.getOrPut(capture) { ArrayList() } += u
            } }
            all.forEach { (u, n) ->
                val stem = n.substringBeforeLast('.')
                val isRaw = n.endsWith(".dng", true)
                // a developed copy hides behind its capture, but shows once the capture is gone (73a)
                if (isRaw || !developedStems.contains(stem.substringBeforeLast('_')) || stem.substringBeforeLast('_') !in allStems)
                    raws += Frame(u, n, isRaw, developed = developedStems.contains(stem), date = dates[u] ?: 0,
                        copies = copies[stem].orEmpty().filter { it != u })
            }
        }
        // Added folders (73a): shown under their own chip, never mixed into the roll's own counts.
        RollFolders.list(context).forEach { tree ->
            RollFolders.photos(context, tree).forEach { p ->
                raws += Frame(p.uri, p.name, RollFolders.isRaw(p.name), developed = developedStems.contains(p.name.substringBeforeLast('.')), date = p.date, folder = tree.toString())
            }
        }
        // Burst rolls (68a) live outside the gallery, one tile per burst, in date order with the rest.
        com.celestial.latent.camera.BurstRollFiles.list(context).forEach { b -> raws += Frame(b.uri, b.name, isRaw = true, developed = false, date = b.dateAdded, isBurst = true) }
        raws.sortByDescending { it.date }
        frames = raws
    }

    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scan() } }
    fun rescan() { scope.launch(kotlinx.coroutines.Dispatchers.IO) { scan() } }

    // 73a: an added folder, picked through Android's own folder picker; the access is kept across restarts
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            RollFolders.add(context, tree); folders = RollFolders.list(context)
            filter = "folder:$tree"; rescan()
        }
    }

    // 73a: the gallery's own photos are moved to the phone's trash through Android's confirmation
    val trashConfirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) pendingDelete.forEach { u ->
            // Android 10 only asked for leave to touch the photo; delete it now
            if (android.os.Build.VERSION.SDK_INT < 30) runCatching { context.contentResolver.delete(u, null, null) }
            com.celestial.latent.develop.PhotoData.forget(context, u)
        }
        pendingDelete = emptyList(); rescan()
    }

    fun deleteChosen() {
        val chosen = frames.filter { it.uri in selected }
        var gone = 0
        // bursts and added-folder photos have no trash: deleted for good, as the confirmation said
        chosen.filter { it.isBurst }.forEach { if (com.celestial.latent.camera.BurstRollFiles.delete(context, it.uri)) { com.celestial.latent.develop.PhotoData.forget(context, it.uri); gone++ } }
        chosen.filter { it.folder != null }.forEach { if (RollFolders.delete(context, it.uri)) { com.celestial.latent.develop.PhotoData.forget(context, it.uri); gone++ } }
        val media = chosen.filter { !it.isBurst && it.folder == null }
        val uris = media.map { it.uri } + (if (withCopies) media.flatMap { it.copies } else emptyList())
        val failed = chosen.size - media.size - gone
        deleteNote = if (failed > 0) "$failed could not be deleted" else ""
        selected = emptySet(); deleteAsk = false
        if (uris.isEmpty()) { rescan(); return }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            pendingDelete = uris
            val ask = runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, true) }.getOrNull()
            if (ask != null) trashConfirm.launch(androidx.activity.result.IntentSenderRequest.Builder(ask.intentSender).build())
            else { pendingDelete = emptyList(); deleteNote = "could not delete"; rescan() }
        } else {
            // Android 10: the app's own photos delete straight away; one it no longer owns asks first
            for (u in uris) {
                val r = runCatching { context.contentResolver.delete(u, null, null) > 0 }
                if (r.getOrDefault(false)) com.celestial.latent.develop.PhotoData.forget(context, u)
                val e = r.exceptionOrNull()
                if (e is android.app.RecoverableSecurityException && pendingDelete.isEmpty()) {
                    pendingDelete = listOf(u)
                    trashConfirm.launch(androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
            }
            rescan()
        }
    }

    fun openInGallery(frame: Frame) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(frame.uri, context.contentResolver.getType(frame.uri) ?: "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
    }
    DisposableEffect(Unit) {
        val prevChanged = DevelopQueue.onChanged; val prevDone = DevelopQueue.onDeveloped
        DevelopQueue.onChanged = { queued = DevelopQueue.queued }
        DevelopQueue.onDeveloped = { scan() }
        onDispose { DevelopQueue.onChanged = prevChanged; DevelopQueue.onDeveloped = prevDone }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // A document provider need not answer a MediaStore column, so ask for the standard
            // one too and fall back to the path rather than crashing on an odd provider.
            val name = (runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment).orEmpty()
            val isRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true) || name.endsWith(".arw", true) || name.endsWith(".cr2", true) || name.endsWith(".nef", true)
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            // An imported photo opens in the darkroom. Developing it immediately would decide
            // the look for you, which is the opposite of why you would import something.
            onOpenDarkroom(uri, isRaw)
        }
    }

    val own = frames.filter { it.folder == null }
    // a removed folder's chip goes, so fall back to the whole roll
    LaunchedEffect(folders) { if (filter.startsWith("folder:") && folders.none { "folder:$it" == filter }) filter = "all" }
    val shown = if (filter.startsWith("folder:")) frames.filter { "folder:" + it.folder == filter } else own.filter {
        when (filter) { "developed" -> it.developed; "latent" -> it.isRaw && !it.developed && !it.isBurst; "bursts" -> it.isBurst; else -> true }
    }
    val selecting = selected.isNotEmpty()

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
        if (selecting) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("✕", color = LatentColors.Text, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = { selected = emptySet(); deleteAsk = false }).padding(horizontal = 6.dp))
            Text("${selected.size} SELECTED", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 3.sp)
            Text(if (shown.all { it.uri in selected }) "none" else "all", color = LatentColors.Amber, fontSize = 12.sp,
                modifier = Modifier.combinedClickable(onClick = {
                    selected = if (shown.all { it.uri in selected }) selected - shown.map { it.uri }.toSet() else selected + shown.map { it.uri }
                    if (selected.isEmpty()) deleteAsk = false
                }).padding(6.dp))
        } else Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = LatentColors.Text, fontSize = 22.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 6.dp))
            Text("ROLL", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 4.sp)
            // Both actions sit together on the right so the title stays centred.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("look", color = LatentColors.Amber, fontSize = 12.sp,
                modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); onOpenLookBuilder() }).padding(6.dp))
            Text("import", color = LatentColors.Amber, fontSize = 12.sp,
                modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); // Some file providers report a DNG as a generic binary, which "image/*" would hide.
                    importer.launch(arrayOf("image/*", "image/x-adobe-dng", "application/octet-stream")) }).padding(6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
            listOf("all" to "ALL ${own.size}", "developed" to "DEVELOPED ${own.count { it.developed }}", "latent" to "LATENT ${own.count { it.isRaw && !it.developed && !it.isBurst }}",
                "bursts" to "BURSTS ${own.count { it.isBurst }}").forEach { (id, label) ->
                val on = id == filter
                Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                        .combinedClickable(onClick = { filter = id }).padding(horizontal = 10.dp, vertical = 5.dp))
            }
            // 73a: each added folder has its own chip; long-press to stop showing it
            folders.forEach { tree ->
                val id = "folder:$tree"; val on = id == filter
                Text("${RollFolders.label(tree).uppercase()} ${frames.count { it.folder == tree.toString() }}", color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                        .combinedClickable(onClick = { filter = id }, onLongClick = { Haptics.click(context); removeFolderAsk = tree })
                        .padding(horizontal = 10.dp, vertical = 5.dp))
            }
            Text("+ FOLDER", color = LatentColors.Amber, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); folderPicker.launch(null) }).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        val removing = removeFolderAsk
        if (removing != null) Column(Modifier.padding(bottom = 10.dp)) {
            Text("Stop showing ${RollFolders.label(removing)} here? Its photos stay on your phone.", color = LatentColors.Text, fontSize = 11.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remove", color = LatentColors.AmberInk, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                        .combinedClickable(onClick = {
                            RollFolders.remove(context, removing); folders = RollFolders.list(context)
                            frames = frames.filter { it.folder != removing.toString() }; removeFolderAsk = null
                        }).padding(horizontal = 12.dp, vertical = 7.dp))
                Text("Keep", color = LatentColors.Text, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(onClick = { removeFolderAsk = null }).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        if (filter.startsWith("folder:") && shown.isEmpty())
            Text("No photos found directly in this folder.", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.uri.toString() }) { frame ->
                LaunchedEffect(frame.uri) {
                    if (!thumbs.containsKey(frame.uri)) {
                        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (frame.isBurst) com.celestial.latent.camera.BurstRollFiles.sharpestPreview(context, frame.uri)
                                ?.let { j -> android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }
                            else runCatching { context.contentResolver.loadThumbnail(frame.uri, Size(300, 300), null) }.getOrNull()
                                // a folder's provider may give no thumbnail: decode the picture small instead
                                ?: if (frame.folder != null && !frame.isRaw) runCatching { context.contentResolver.openInputStream(frame.uri)?.use {
                                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) } }.getOrNull() else null
                        }
                        // keep the newest few hundred: an added folder can hold thousands of photos
                        if (b != null) thumbs = (thumbs + (frame.uri to b)).let { if (it.size > 240) it.entries.drop(it.size - 240).associate { e -> e.key to e.value } else it }
                    }
                }
                val picked = frame.uri in selected
                // 73a: long-press starts choosing photos; while choosing, a tap adds or removes one
                fun toggle() { selected = if (picked) selected - frame.uri else selected + frame.uri; if (selected.isEmpty()) deleteAsk = false }
                Box(Modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(4.dp)).background(LatentColors.Surface)
                    .then(if (picked) Modifier.border(2.dp, LatentColors.Amber, RoundedCornerShape(4.dp)) else Modifier)
                    .combinedClickable(onClick = {
                        Haptics.tick(context)
                        if (selecting) toggle() else if (frame.isBurst) onOpenBurst(frame.uri) else onOpenDarkroom(frame.uri, frame.isRaw)
                    }, onLongClick = {
                        Haptics.click(context); toggle()
                    })) {
                    thumbs[frame.uri]?.let { Image(it.asImageBitmap(), contentDescription = frame.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (frame.isBurst) {
                        Text(if (frame.name.contains("_FOCUS")) "FOCUS" else "BURST", color = LatentColors.AmberInk, fontSize = 9.sp, letterSpacing = 1.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 6.dp, vertical = 2.dp))
                    } else if (frame.isRaw && !frame.developed) {
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xAA161615)), contentAlignment = Alignment.Center) {
                            Text("RAW", color = LatentColors.Text, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                    }
                    if (selecting) Box(Modifier.align(Alignment.TopStart).padding(5.dp).clip(CircleShape)
                        .background(if (picked) LatentColors.Amber else androidx.compose.ui.graphics.Color(0x88000000))
                        .border(1.dp, LatentColors.TextBright, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(if (picked) "✓" else " ", color = LatentColors.AmberInk, fontSize = 10.sp)
                    }
                }
            }
        }
        if (deleteNote.isNotEmpty() && !selecting) Text(deleteNote, color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        if (selecting) {
            val chosen = frames.filter { it.uri in selected }
            val copyCount = chosen.filter { !it.isBurst && it.folder == null }.sumOf { it.copies.size }
            val forGood = chosen.count { it.isBurst || it.folder != null }
            if (deleteAsk) Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("Delete ${chosen.size} ${if (chosen.size == 1) "photo" else "photos"}?", color = LatentColors.TextBright, fontSize = 12.sp)
                if (chosen.size > forGood) Text(if (android.os.Build.VERSION.SDK_INT >= 30) "Photos from the roll go to your phone's trash for about 30 days." else "Photos from the roll are deleted.",
                    color = LatentColors.Text, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                if (forGood > 0) Text("Bursts and photos from added folders are deleted for good ($forGood).", color = LatentColors.Text, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                if (copyCount > 0) Text((if (withCopies) "✓ " else "○ ") + "also delete the developed ${if (copyCount == 1) "copy" else "copies"} ($copyCount)",
                    color = if (withCopies) LatentColors.Amber else LatentColors.Text, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp).combinedClickable(onClick = { withCopies = !withCopies }).padding(vertical = 4.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delete", color = LatentColors.AmberInk, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                            .combinedClickable(onClick = { Haptics.click(context); deleteChosen() }).padding(horizontal = 14.dp, vertical = 8.dp))
                    Text("Keep", color = LatentColors.Text, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                            .combinedClickable(onClick = { deleteAsk = false }).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            } else Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                val one = chosen.singleOrNull()?.takeIf { !it.isBurst }
                Text("Open in gallery", color = if (one != null) LatentColors.Text else LatentColors.TextDim, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(enabled = one != null, onClick = { one?.let { openInGallery(it) } }).padding(horizontal = 14.dp, vertical = 8.dp))
                Text("Delete", color = LatentColors.AmberInk, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                        .combinedClickable(onClick = { Haptics.tick(context); withCopies = true; deleteAsk = true }).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        } else Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val filmLabel = Develop.FILMS.firstOrNull { it.first == settings.film }?.second?.uppercase() ?: ""
            Text(if (queued > 0) "$queued DEVELOPING · $filmLabel" else "$filmLabel · TAP A FRAME FOR THE DARKROOM", color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp)
            val undeveloped = own.filter { it.isRaw && !it.developed && !it.isBurst }
            Text("Develop all", color = if (undeveloped.isEmpty()) LatentColors.TextDim else LatentColors.AmberInk, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (undeveloped.isEmpty()) LatentColors.Surface else LatentColors.Amber)
                    .combinedClickable(enabled = undeveloped.isNotEmpty(), onClick = {
                        Haptics.click(context)
                        val r = com.celestial.latent.develop.Recipes.current(context).copy(film = settings.film)
                        undeveloped.forEach { DevelopQueue.submit(context, DevelopQueue.Job(it.uri, r, isRaw = true)) }
                    }).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}
