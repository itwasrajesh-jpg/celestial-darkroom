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

private data class Frame(val uri: Uri, val name: String, val isRaw: Boolean, val developed: Boolean, val date: Long = 0, val isBurst: Boolean = false)

/** The roll: every capture in DCIM/Latent, developed ones in colour, undeveloped ones waiting. */
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
            all.forEach { (_, n) -> if (n.endsWith(".jpg", true) && n.contains('_')) developedStems += n.substringBeforeLast('.').substringBeforeLast('_') }
            all.forEach { (u, n) ->
                val stem = n.substringBeforeLast('.')
                val isRaw = n.endsWith(".dng", true)
                if (isRaw || !developedStems.contains(stem.substringBeforeLast('_')))
                    raws += Frame(u, n, isRaw, developed = developedStems.contains(stem), date = dates[u] ?: 0)
            }
        }
        // Burst rolls (68a) live outside the gallery, one tile per burst, in date order with the rest.
        com.celestial.latent.camera.BurstRollFiles.list(context).forEach { b -> raws += Frame(b.uri, b.name, isRaw = true, developed = false, date = b.dateAdded, isBurst = true) }
        raws.sortByDescending { it.date }
        frames = raws
    }

    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scan() } }
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

    val shown = frames.filter {
        when (filter) { "developed" -> it.developed; "latent" -> it.isRaw && !it.developed && !it.isBurst; "bursts" -> it.isBurst; else -> true }
    }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            listOf("all" to "ALL ${frames.size}", "developed" to "DEVELOPED ${frames.count { it.developed }}", "latent" to "LATENT ${frames.count { it.isRaw && !it.developed && !it.isBurst }}",
                "bursts" to "BURSTS ${frames.count { it.isBurst }}").forEach { (id, label) ->
                val on = id == filter
                Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                        .combinedClickable(onClick = { filter = id }).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.uri.toString() }) { frame ->
                LaunchedEffect(frame.uri) {
                    if (!thumbs.containsKey(frame.uri)) {
                        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (frame.isBurst) com.celestial.latent.camera.BurstRollFiles.sharpestPreview(context, frame.uri)
                                ?.let { j -> android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }
                            else runCatching { context.contentResolver.loadThumbnail(frame.uri, Size(300, 300), null) }.getOrNull()
                        }
                        if (b != null) thumbs = thumbs + (frame.uri to b)
                    }
                }
                Box(Modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(4.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = {
                        Haptics.tick(context); if (frame.isBurst) onOpenBurst(frame.uri) else onOpenDarkroom(frame.uri, frame.isRaw)
                    }, onLongClick = {
                        if (frame.isBurst) return@combinedClickable
                        Haptics.click(context)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(frame.uri, context.contentResolver.getType(frame.uri) ?: "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
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
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val filmLabel = Develop.FILMS.firstOrNull { it.first == settings.film }?.second?.uppercase() ?: ""
            Text(if (queued > 0) "$queued DEVELOPING · $filmLabel" else "$filmLabel · TAP A FRAME FOR THE DARKROOM", color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp)
            val undeveloped = frames.filter { it.isRaw && !it.developed && !it.isBurst }
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
