@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.camera.BurstRoll
import com.celestial.latent.camera.BurstRollFiles
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The burst viewer (68a): swipe through every frame of one burst roll, see which is sharpest, and
 * develop or save the one you want. Previews are the small JPEGs stored in the roll, so swiping is
 * instant; the full frame is only unpacked when it is developed or saved.
 */
@Composable
fun BurstScreen(
    roll: Uri,
    onOpenDarkroom: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reader by remember { mutableStateOf<BurstRoll.Reader?>(null) }
    var item by remember { mutableStateOf<BurstRollFiles.Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previews by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var armDelete by remember { mutableStateOf(false) }
    var merged by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(roll) {
        withContext(Dispatchers.IO) {
            item = BurstRollFiles.list(context).firstOrNull { it.uri == roll }
            runCatching { BurstRollFiles.open(context, roll) }
                .onSuccess { reader = it }.onFailure { error = it.message ?: "cannot open this burst" }
        }
    }
    DisposableEffect(roll) { onDispose { runCatching { reader?.close() } } }

    val r = reader
    val frames = r?.index?.frames ?: emptyList()
    val sharpest = r?.sharpest ?: 0
    val pager = rememberPagerState(initialPage = 0) { frames.size }
    LaunchedEffect(r) { if (r != null) pager.scrollToPage(r.sharpest) }
    val page = pager.currentPage.coerceAtMost((frames.size - 1).coerceAtLeast(0))
    val rollName = item?.name ?: "LATENT_BURST.${BurstRoll.EXT}"
    val focusStack = r?.index?.common?.kind == BurstRoll.KIND_FOCUS
    val hdrStack = r?.index?.common?.kind == BurstRoll.KIND_HDR
    val hdrBracket = r?.index?.common?.kind == BurstRoll.KIND_BRACKET

    fun runBusy(label: String, work: suspend () -> Unit) {
        if (busy != null) return
        busy = label; note = ""
        scope.launch {
            try { work() } catch (e: Exception) { note = "Failed: ${e.message}" }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = LatentColors.Text, fontSize = 22.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 6.dp))
            Text((if (focusStack) "FOCUS STACK" else if (hdrStack) "HDR STACK" else if (hdrBracket) "HDR BRACKET" else "BURST") + (if (frames.isNotEmpty()) " · ${frames.size} FRAMES" else "") + (r?.index?.common?.let { if (it.dcg) " · DCG" else "" } ?: ""),
                color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 3.sp)
            Text(item?.let { "%.0f MB".format(it.sizeBytes / 1e6) } ?: "", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
        }

        if (error != null) {
            Text("This burst can't be opened: $error", color = LatentColors.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        if (r == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LatentColors.Amber) }
            return@Column
        }

        // The frames
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { i ->
            LaunchedEffect(i) {
                if (!previews.containsKey(i)) {
                    val b = withContext(Dispatchers.IO) { runCatching { r.preview(i)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() }
                    if (b != null) previews = previews + (i to b)
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                previews[i]?.let { Image(it.asImageBitmap(), contentDescription = "Frame ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    ?: CircularProgressIndicator(color = LatentColors.Amber)
                if (i == sharpest && !focusStack && !hdrBracket) Text("SHARPEST", color = LatentColors.AmberInk, fontSize = 10.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }

        // What this frame is
        val f = frames.getOrNull(page)
        if (f != null) {
            val shutter = f.exposureNs?.let { ns -> if (ns >= 1_000_000_000L) "%.1f s".format(ns / 1e9) else "1/${Math.round(1e9 / ns)} s" } ?: "–"
            // A focus stack shows where each frame was focused (dioptres → cm; 0 is far away).
            val focus = if (!focusStack || f.focusD < 0) "" else " · focus " + (if (f.focusD < 0.05f) "far" else "${Math.round(100 / f.focusD)} cm") + if (f.lensMoving) " (lens moving)" else ""
            Text("${page + 1} / ${frames.size} · $shutter · ISO ${f.iso ?: "–"} · +${f.tOffsetNs / 1_000_000} ms$focus",
                color = LatentColors.TextBright, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // Sharpness of every frame: tap a bar to jump to that frame. Bars are scaled within this burst.
        val lo = frames.minOfOrNull { it.sharpness } ?: 0f; val hi = frames.maxOfOrNull { it.sharpness } ?: 1f
        Row(Modifier.fillMaxWidth().height(34.dp).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            frames.forEachIndexed { i, fr ->
                val t = if (hi > lo) (fr.sharpness - lo) / (hi - lo) else 1f
                Box(Modifier.weight(1f).fillMaxHeight(0.25f + 0.75f * t).clip(RoundedCornerShape(2.dp))
                    .background(if (i == page) LatentColors.Amber else if (i == sharpest) LatentColors.TextBright else LatentColors.Line)
                    .combinedClickable(onClick = { scope.launch { pager.animateScrollToPage(i) } }))
            }
        }
        Text("SHARPNESS · TAP A BAR", color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp)

        // Actions
        @Composable
        fun Action(label: String, primary: Boolean = false, onClick: () -> Unit) {
            Text(label, color = if (primary) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (primary) LatentColors.Amber else LatentColors.Surface)
                    .combinedClickable(enabled = busy == null, onClick = { Haptics.tick(context); onClick() }).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        // 68b: the whole roll in one RAW. A burst is merged for less noise; a focus stack is blended front to back.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (focusStack) "Stack all frames" else "Merge all frames", primary = true) {
                runBusy(if (focusStack) "Stacking…" else "Merging…") {
                    val uri = withContext(Dispatchers.Default) { BurstRollFiles.merge(context, r, rollName) { busy = it } }
                    merged = uri
                    note = (if (focusStack) "Stacked" else "Merged") + " · saved to DCIM/Latent" +
                        (if (hdrStack) " · develops ${"%+.1f".format(r.index.common.brighten)} EV brighter" else "") +
                        (if (hdrBracket) " · sky from the darkest frame, shadows from the brightest" else "")
                }
            }
            merged?.let { u -> Action("Develop it") { onOpenDarkroom(u) } }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Develop") {
                runBusy("Unpacking frame ${page + 1}…") {
                    val uri = withContext(Dispatchers.IO) { BurstRollFiles.exported(context, rollName, page) ?: BurstRollFiles.exportFrame(context, r, rollName, page) }
                    onOpenDarkroom(uri)
                }
            }
            Action("Save DNG") {
                runBusy("Saving frame ${page + 1}…") {
                    withContext(Dispatchers.IO) { BurstRollFiles.exported(context, rollName, page) ?: BurstRollFiles.exportFrame(context, r, rollName, page) }
                    note = "Frame ${page + 1} saved to DCIM/Latent"
                }
            }
            Action("Save all") {
                runBusy("Saving all frames…") {
                    for (i in frames.indices) {
                        busy = "Saving frame ${i + 1} of ${frames.size}…"
                        withContext(Dispatchers.IO) { BurstRollFiles.exported(context, rollName, i) ?: BurstRollFiles.exportFrame(context, r, rollName, i) }
                    }
                    note = "All ${frames.size} frames saved to DCIM/Latent"
                }
            }
            Action(if (armDelete) "Tap to delete" else "Delete") {
                if (!armDelete) { armDelete = true; note = "Deletes the whole burst file. Saved DNGs stay." }
                else runBusy("Deleting…") {
                    runCatching { r.close() }
                    val ok = withContext(Dispatchers.IO) { BurstRollFiles.delete(context, roll) }
                    if (ok) onBack() else { note = "Could not delete it"; armDelete = false }
                }
            }
        }
        Text(busy ?: note, color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}
