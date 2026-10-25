package com.celestial.latent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.camera.Bracket
import com.celestial.latent.camera.BurstRoll
import com.celestial.latent.camera.BurstRollFiles
import com.celestial.latent.camera.MergePicks
import com.celestial.latent.develop.Select
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long side of the live preview, in pixels: small enough to redo on every change. */
private const val LIVE = 640
/** Brush radius, as a share of the picture's long side. */
private const val BRUSH_R = 0.045f
/** How much one brush dab adds at its centre (a stroke builds up). */
private const val BRUSH_STEP = 0.35f

/**
 * The merge editor (86d) for an HDR bracket: choose an area (tap to select, or brush, or erase), then
 * the frame it should come from. AUTO hands it back to the merge. The picture is a live merge of the
 * roll's small previews at the brightest frame's level, as the darkroom develops it; "Merge with my
 * picks" builds the full RAW with them (BurstMerge.bracket). Picks are kept with the roll.
 *
 * A picked area comes from that frame AS IT LOOKED: the dark frame's lamp keeps its detail, the bright
 * frame's shadows keep their brightness. A frame that clipped there stays clipped: it is your pick.
 */
@Composable
fun ColumnScope.MergeEditor(
    reader: BurstRoll.Reader,
    rollName: String,
    busy: String?,
    onMerge: (List<MergePicks.Pick>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val frames = reader.index.frames
    val n = frames.size
    val exposure = remember(reader) { BurstRollFiles.exposures(reader) }
    // the strip: darkest to brightest, labelled by shutter (or ISO when the shutter never changed)
    val order = remember(reader) { exposure.indices.sortedBy { exposure[it] } }
    val sameShutter = frames.map { it.exposureNs }.distinct().size == 1
    fun label(i: Int) = frames[i].let { f -> if (sameShutter) "ISO ${f.iso ?: "–"}" else f.exposureNs?.let { Bracket.shutterLabel(it) } ?: "–" }

    // the previews, at the live size, and the merge's own result (worked out once)
    var thumbs by remember { mutableStateOf<List<Bitmap>?>(null) }
    var argb by remember { mutableStateOf<List<IntArray>?>(null) }
    var auto by remember { mutableStateOf<FloatArray?>(null) }
    var lw by remember { mutableStateOf(0) }; var lh by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    // the areas: what is saved with the roll, edited here; Cancel leaves the saved ones as they were
    val areas = remember { mutableStateListOf<MergePicks.Area>() }
    var current by remember { mutableStateOf(-1) }
    var version by remember { mutableStateOf(0) }       // any change to the areas
    var tool by remember { mutableStateOf(0) }           // 0 tap to select, 1 brush, 2 erase
    var status by remember { mutableStateOf("") }
    var gw by remember { mutableStateOf(2) }; var gh by remember { mutableStateOf(2) }

    // tap to select: the picture read once by the model, and the last outline answer per area
    var selReady by remember { mutableStateOf(Select.isReady(context)) }
    var embedding by remember { mutableStateOf<Select.Embedding?>(null) }
    var selBusy by remember { mutableStateOf(false) }
    val answers = remember { HashMap<MergePicks.Area, Select.Answer>() }

    fun changed() { version++ }

    LaunchedEffect(reader) {
        val ok = withContext(Dispatchers.Default) {
            runCatching {
                val big = (0 until n).map { i -> reader.preview(i)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: error("frame ${i + 1} has no preview") }
                val w0 = big[0].width; val h0 = big[0].height
                val k = minOf(1f, LIVE.toFloat() / maxOf(w0, h0))
                val w = maxOf(16, Math.round(w0 * k)); val h = maxOf(16, Math.round(h0 * k))
                val small = big.map { b -> if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() } }
                val px = small.map { b -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
                val t0 = System.nanoTime()
                val a = MergePicks.previewAuto(px, w, h, exposure)
                android.util.Log.i("Latent", "merge editor: ${n} previews ${w}x$h, live merge ${(System.nanoTime() - t0) / 1_000_000} ms")
                val (g1, g2) = MergePicks.grid(w, h)
                val saved = MergePicks.load(context.filesDir, rollName, g1, g2)
                withContext(Dispatchers.Main) {
                    lw = w; lh = h; gw = g1; gh = g2; thumbs = small; argb = px; auto = a
                    areas.clear(); areas.addAll(saved.map { it.copy() }); current = if (areas.isEmpty()) -1 else areas.size - 1
                    changed()
                }
            }.onFailure { failed = it.message ?: "could not read the previews"; android.util.Log.e("Latent", "merge editor", it) }.isSuccess
        }
        // the model reads the picture as the merge shows it, once
        if (ok && selReady) {
            val px = argb ?: return@LaunchedEffect; val a = auto ?: return@LaunchedEffect
            val shot = withContext(Dispatchers.Default) { MergePicks.previewWithPicks(a, px, lw, lh, emptyList()) }
            embedding = withContext(Dispatchers.Default) { runCatching { Select.embed(context, shot, lw, lh) }.onFailure { android.util.Log.e("Latent", "merge editor: select", it) }.getOrNull() }
        }
    }

    // the live picture: worked out off the main thread, the latest change only
    LaunchedEffect(Unit) {
        snapshotFlow { version }.conflate().collect {
            val px = argb ?: return@collect; val a = auto ?: return@collect
            val picks = MergePicks.picks(areas.toList(), n)
            val w = lw; val h = lh
            val bmp = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(MergePicks.previewWithPicks(a, px, w, h, picks), w, h, Bitmap.Config.ARGB_8888)
            }
            shown = bmp
        }
    }

    // the outline of each area: the one being edited in amber, the others faint
    val overlay = remember(version, current, gw, gh) {
        if (areas.isEmpty()) return@remember null
        val px = IntArray(gw * gh)
        for ((ai, area) in areas.withIndex()) {
            val m = area.hard(); val on = ai == current
            for (j in 0 until gh) for (i in 0 until gw) {
                val k = j * gw + i
                if (m[k] < 0.5f) continue
                val edge = (i == 0 || m[k - 1] < 0.5f) || (i == gw - 1 || m[k + 1] < 0.5f) || (j == 0 || m[k - gw] < 0.5f) || (j == gh - 1 || m[k + gw] < 0.5f)
                px[k] = when {
                    on && edge -> 0xFFFAC775.toInt()
                    on -> 0x30FAC775
                    edge -> 0x90F1EFE8.toInt()
                    else -> px[k]
                }
            }
        }
        Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888)
    }

    fun newArea(): MergePicks.Area = MergePicks.Area(-1, gw, gh).also { areas.add(it); current = areas.size - 1 }
    fun area(): MergePicks.Area? = areas.getOrNull(current)
    fun frameName(f: Int) = if (f < 0) "AUTO" else "FRAME ${f + 1}"

    /** A tap on the picture with Tap to select: outline the thing under it (the same spot again: the next size). */
    fun selectTap(u: Float, v: Float) {
        if (!selReady) { status = "tap to select needs its model: get it below"; return }
        val e = embedding ?: run { status = "still reading the picture…"; return }
        if (selBusy) return
        val a = area() ?: newArea()
        val ans = answers[a]
        val first = a.taps.singleOrNull()
        val aspect = lw.toFloat() / lh
        val aw = if (aspect >= 1f) 1f else aspect; val ah = if (aspect >= 1f) 1f / aspect else 1f
        if (first != null && ans != null && ans.masks.size == 3 && kotlin.math.hypot((u - first.u) * aw, (v - first.v) * ah) < 0.04f) {
            a.level = (a.level + 1) % 3
            ans.masks[a.level].copyInto(a.select); changed(); return
        }
        val taps = a.taps + MergePicks.Tap(u, v, true)
        selBusy = true; status = ""
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching { Select.outlines(context, e, taps.map { Select.Tap(it.u, it.v, it.add) }, a.gw, a.gh) }
                    .onFailure { android.util.Log.e("Latent", "merge editor: outline", it) }.getOrNull()
            }
            selBusy = false
            if (r == null) { status = "could not outline there (Settings → Logs says why)"; return@launch }
            a.taps = taps; answers[a] = r
            a.level = if (r.masks.size == 3) r.scores.indices.maxByOrNull { r.scores[it] } ?: 0 else 0
            r.masks[a.level].copyInto(a.select)
            changed()
            status = if (a.frame < 0) "now pick the frame it comes from" else "area ${current + 1} from ${label(a.frame)}"
        }
    }

    /** One soft dab of the brush (or the eraser) at (u, v). */
    fun dab(a: MergePicks.Area, u: Float, v: Float, erase: Boolean) {
        val cx = u * a.gw; val cy = v * a.gh
        val r = BRUSH_R * maxOf(a.gw, a.gh)
        val x0 = maxOf(0, (cx - r).toInt()); val x1 = minOf(a.gw - 1, (cx + r).toInt() + 1)
        val y0 = maxOf(0, (cy - r).toInt()); val y1 = minOf(a.gh - 1, (cy + r).toInt() + 1)
        for (j in y0..y1) for (i in x0..x1) {
            val dx = i + 0.5f - cx; val dy = j + 0.5f - cy
            val f = 1f - (dx * dx + dy * dy) / (r * r)
            if (f <= 0f) continue
            val k = j * a.gw + i
            a.paint[k] = (a.paint[k] + (if (erase) -1f else 1f) * BRUSH_STEP * f * f).coerceIn(-1f, 1f)
        }
    }
    var lastDab by remember { mutableStateOf<Offset?>(null) }
    fun paintAt(u: Float, v: Float, start: Boolean) {
        val erase = tool == 2
        val a = area() ?: if (erase) { status = "nothing to erase: make an area first"; return } else newArea()
        val prev = lastDab
        if (start || prev == null) dab(a, u, v, erase)
        else {
            // fill the gap since the last dab so a fast stroke stays smooth
            val d = kotlin.math.hypot(u - prev.x, v - prev.y)
            val steps = maxOf(1, (d / (BRUSH_R / 3f)).toInt())
            for (s in 1..steps) dab(a, prev.x + (u - prev.x) * s / steps, prev.y + (v - prev.y) * s / steps, erase)
        }
        lastDab = Offset(u, v)
        changed()
    }
    val selectNow by rememberUpdatedState<(Float, Float) -> Unit>({ u, v -> selectTap(u, v) })
    val paintNow by rememberUpdatedState<(Float, Float, Boolean) -> Unit>({ u, v, s -> paintAt(u, v, s) })

    BackHandler { onClose() }

    // ---- the picture ----
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        val pic = shown
        if (failed != null) { Text("The merge editor can't open: $failed", color = LatentColors.Text, fontSize = 13.sp); return@BoxWithConstraints }
        if (pic == null || lw == 0) { CircularProgressIndicator(color = LatentColors.Amber); return@BoxWithConstraints }
        val aspect = lw.toFloat() / lh
        val fitW = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
        Box(Modifier.size(fitW, fitW / aspect)
            .pointerInput(tool) {
                if (tool == 0) detectTapGestures(onTap = { o -> selectNow(o.x / size.width, o.y / size.height) })
                else detectTapGestures(onTap = { o -> lastDab = null; paintNow(o.x / size.width, o.y / size.height, true) })
            }
            .pointerInput(tool) {
                if (tool != 0) detectDragGestures(
                    onDragStart = { o -> paintNow(o.x / size.width, o.y / size.height, true) },
                    onDragEnd = { lastDab = null },
                    onDragCancel = { lastDab = null },
                ) { change, _ -> change.consume(); paintNow(change.position.x / size.width, change.position.y / size.height, false) }
            }) {
            Image(pic.asImageBitmap(), contentDescription = "The merge, live", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            overlay?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize()) }
            if (selBusy) CircularProgressIndicator(color = LatentColors.Amber, modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
    }

    // ---- EDIT MERGE · what is happening ----
    val a = area()
    val picked = areas.count { it.frame >= 0 && !it.isEmpty }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("EDIT MERGE", color = LatentColors.Amber, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(when {
            status.isNotEmpty() -> status
            a != null && a.frame >= 0 -> "area ${current + 1} now from ${label(a.frame)}${if (sameShutter) "" else " s"}"
            picked > 0 -> "$picked area${if (picked > 1) "s" else ""} picked"
            else -> "tap an area, then pick its frame"
        }, color = LatentColors.Text, fontSize = 11.sp)
    }

    // ---- the frames: AUTO, then darkest to brightest ----
    val th = thumbs
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        @Composable
        fun Tile(on: Boolean, caption: String, onTap: () -> Unit, content: @Composable () -> Unit) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(LatentColors.Surface)
                    .border(if (on) 1.5.dp else 0.dp, if (on) LatentColors.Amber else LatentColors.Surface, RoundedCornerShape(4.dp))
                    .pointerInput(caption) { detectTapGestures(onTap = { Haptics.tick(context); onTap() }) }, contentAlignment = Alignment.Center) { content() }
                Text(caption, color = if (on) LatentColors.Amber else LatentColors.TextDim, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
        fun pick(f: Int) {
            val cur = area() ?: run { status = "first tap or brush an area"; return }
            cur.frame = f; status = ""; changed()
        }
        Tile(a != null && a.frame < 0, "merge", { pick(-1) }) { Text("AUTO", color = LatentColors.TextBright, fontSize = 10.sp) }
        for (i in order) Tile(a != null && a.frame == i, label(i), { pick(i) }) {
            th?.getOrNull(i)?.let { Image(it.asImageBitmap(), contentDescription = "Frame ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }

    // ---- the areas ----
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((ai, ar) in areas.withIndex()) {
            val on = ai == current
            Row(Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface), verticalAlignment = Alignment.CenterVertically) {
                Text("AREA ${ai + 1} · ${frameName(ar.frame)}", color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 11.sp,
                    modifier = Modifier.pointerInput(ai) { detectTapGestures(onTap = { Haptics.tick(context); current = ai; status = ""; changed() }) }
                        .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp))
                Text("×", color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 13.sp,
                    modifier = Modifier.pointerInput(ai, areas.size) { detectTapGestures(onTap = {
                        Haptics.tick(context); answers.remove(areas[ai]); areas.removeAt(ai)
                        current = if (areas.isEmpty()) -1 else minOf(current, areas.size - 1).coerceAtLeast(0); status = ""; changed()
                    }) }.padding(start = 2.dp, end = 10.dp, top = 3.dp, bottom = 3.dp))
            }
        }
        EditorChip(if (areas.isEmpty()) "+ Area" else "+ New area", false) {
            // an empty area being edited is reused, so taps on this chip never pile up empty ones
            if (area()?.isEmpty != true) newArea()
            status = "tap or brush the new area"; changed()
        }
    }

    // ---- how to choose ----
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorChip("Tap to select", tool == 0) { tool = 0; lastDab = null }
        EditorChip("Brush", tool == 1) { tool = 1 }
        EditorChip("Erase", tool == 2) { tool = 2 }
        if (!selReady) EditorChip("Get model (25 MB)", false) {
            status = "downloading…"
            Thread {
                Select.download(context) { got, total -> status = "downloading… ${got * 100 / total}%" }
                    .onSuccess {
                        status = "reading the picture…"
                        val px = argb; val au = auto
                        if (px != null && au != null) embedding = runCatching { Select.embed(context, MergePicks.previewWithPicks(au, px, lw, lh, emptyList()), lw, lh) }.getOrNull()
                        selReady = true; status = if (embedding != null) "" else "the picture could not be read"
                    }
                    .onFailure { t -> status = "not downloaded: ${t.message}" }
            }.start()
        }
    }

    // ---- what to do ----
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorAction("Merge with my picks", primary = true, enabled = busy == null && auto != null) {
            val keep = areas.filter { !it.isEmpty }
            MergePicks.save(context.filesDir, rollName, keep)
            onMerge(MergePicks.picks(keep, n))
        }
        EditorAction("All auto", enabled = busy == null) { areas.clear(); answers.clear(); current = -1; status = "all auto now (Cancel keeps saved picks)"; changed() }
        EditorAction("Cancel", enabled = busy == null) { onClose() }
    }
    Text(busy ?: "Auto: the merge decides. A frame: that area comes only from it, as it looked, with a soft edge.",
        color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
}

@Composable
private fun EditorChip(label: String, on: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    Text(label, fontSize = 11.sp, color = if (on) LatentColors.AmberInk else LatentColors.TextBright,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
            .pointerInput(label, on) { detectTapGestures(onTap = { Haptics.tick(context); onTap() }) }
            .padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun EditorAction(label: String, primary: Boolean = false, enabled: Boolean = true, onTap: () -> Unit) {
    val context = LocalContext.current
    Text(label, color = if (primary) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (primary) LatentColors.Amber else LatentColors.Surface)
            .pointerInput(label, enabled) { detectTapGestures(onTap = { if (enabled) { Haptics.tick(context); onTap() } }) }
            .padding(horizontal = 12.dp, vertical = 8.dp))
}
