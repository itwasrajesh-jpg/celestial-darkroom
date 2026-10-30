@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Collage
import com.celestial.latent.develop.CollageSources
import com.celestial.latent.develop.AgedPrint
import com.celestial.latent.develop.Occasion
import com.celestial.latent.develop.Occasions
import com.celestial.latent.develop.Select
import com.celestial.latent.ui.Glyph
import com.celestial.latent.ui.GlyphIcon
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.glow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A frame picked in the roll for a collage: the capture (or photo) and when it was taken. */
class CollagePick(val uri: Uri, val isRaw: Boolean, val date: Long)

/** 99a: the calendar to open on, after Next month: its layout, and the month ([month] 1..12). */
class CalendarResume(val layout: String, val year: Int, val month: Int)

/** The preview's size, in pixels per page unit (a page 930 px wide), and the saved collage's (1984 px wide). */
private const val PREVIEW_SCALE = 0.75f
private const val SAVE_SCALE = 1.6f

/**
 * The Collage page (98a), opened from the roll's picking bar: the collage fills the top with Save in the corner; the
 * picked photos sit underneath (tap one to make it the HERO); SCENES | LAYOUTS chips pick the page; Shuffle tries
 * another arrangement; FADE and WEAR age every print at once. Tapping a title or caption on the page types it.
 * The collage is saved to the roll as a new photo; the photos it was made from stay as they are.
 *
 * CALENDAR (99a) is the same six pages with a month on them, one month at a time: ‹ › picks the month, the days the
 * roll has photos from get a dot, and a tap on a day circles it and types its note. Once a month is saved, Next takes
 * the roll to picking for the month after ([onNextMonth]), and the collage comes back on that month ([resume]).
 *
 * OCCASIONS (103b), under the month: the month's birthdays, anniversaries and festivals, each kept once and back every
 * year. + Add (or a tap on one) opens a small form: the name, the day, the kind, and its photos, picked by tapping the
 * prints in the strip below. Every calendar page then shows them (each face in its date, a row of portraits), and the
 * Hearts and Portraits pages are built round them.
 */
@Composable
fun CollageScreen(picks: List<CollagePick>, resume: CalendarResume? = null, onBack: () -> Unit, onNextMonth: (CalendarResume) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // each pick's picture (its developed print), small enough for the preview
    var pictures by remember { mutableStateOf<List<Uri?>>(emptyList()) }
    var smalls by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var hero by remember { mutableStateOf(0) }
    // "scenes", "layouts" or "calendar"
    var tab by remember { mutableStateOf(if (resume != null) "calendar" else "layouts") }
    val scenes = tab == "scenes"; val calendar = tab == "calendar"
    var layout by remember { mutableStateOf(resume?.layout?.takeIf { it in Collage.LAYOUTS } ?: "grid") }
    var seed by remember { mutableStateOf(0L) }
    // the dials as shown, and as last let go (the page is aged again only then)
    var fade by remember { mutableStateOf(0f) }
    var wear by remember { mutableStateOf(0f) }
    var fadeSet by remember { mutableStateOf(0f) }
    var wearSet by remember { mutableStateOf(0f) }
    val month = remember(picks) {
        val d = picks.firstOrNull()?.date ?: 0L
        java.util.Date(if (d in 1 until 100_000_000_000L) d * 1000 else if (d > 0) d else System.currentTimeMillis())
    }
    var title by remember { mutableStateOf(java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(month) + " days") }
    var sub by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(month) + " · the roll") }
    var captions by remember {
        mutableStateOf(picks.map { p ->
            val d = if (p.date in 1 until 100_000_000_000L) p.date * 1000 else p.date
            if (d > 0) java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(d)) else ""
        })
    }
    var note by remember {
        val ds = picks.map { if (it.date in 1 until 100_000_000_000L) it.date * 1000 else it.date }.filter { it > 0 }
        val f = java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
        mutableStateOf(if (ds.isEmpty()) "" else if (f.format(ds.min()) == f.format(ds.max())) f.format(ds.min()) else f.format(ds.min()) + " – " + f.format(ds.max()))
    }
    // Number and Letter: the figure made of photos (blank: "1", and the title's first letter)
    var big by remember { mutableStateOf("") }
    // the calendar: its month (from the photos, or the one Next asked for), the days with photos, the circled day
    val start = remember(picks) { java.time.Instant.ofEpochMilli(month.time).atZone(java.time.ZoneId.systemDefault()) }
    var calYear by remember { mutableStateOf(resume?.year ?: start.year) }
    var calMonth by remember { mutableStateOf(resume?.month ?: start.monthValue) }
    var marks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var circle by remember { mutableStateOf<Int?>(null) }
    var dayNote by remember { mutableStateOf("") }
    // the calendar's title and line follow the month until typed over
    var calTitle by remember { mutableStateOf<String?>(null) }
    var calSub by remember { mutableStateOf<String?>(null) }
    var savedMonth by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 103b: every occasion kept, the one being added or changed, and the faces of this month's (by id)
    var occasions by remember { mutableStateOf(Occasions.all(context)) }
    var occEdit by remember { mutableStateOf<Occasion?>(null) }
    var faces by remember { mutableStateOf<Map<Long, Bitmap>>(emptyMap()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<Collage.Page?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    // Cut-out: the hero's picture read by the tap-to-select model, the spot tapped, and its outline
    var modelsReady by remember { mutableStateOf(Select.isReady(context)) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    var embedding by remember { mutableStateOf<Pair<Int, Select.Embedding>?>(null) }
    var cutTap by remember { mutableStateOf(0.5f to 0.5f) }
    var answer by remember { mutableStateOf<Select.Answer?>(null) }
    var choosing by remember { mutableStateOf(false) }

    // only frames with a print can go on the page: an undeveloped RAW or a burst has none yet
    LaunchedEffect(picks) {
        val (pics, bmps) = withContext(Dispatchers.IO) {
            val pics = picks.map { CollageSources.pictureOf(context, it.uri, it.isRaw) }
            pics to pics.mapIndexedNotNull { i, u -> u?.let { CollageSources.load(context, it, 1000) }?.let { i to it } }.toMap()
        }
        pictures = pics; smalls = bmps; loading = false
        if (hero !in bmps) hero = bmps.keys.minOrNull() ?: 0
    }
    DisposableEffect(Unit) { onDispose { Select.release() } }
    val usable = picks.indices.filter { it in smalls }
    // the page's photos: the hero first, then the others as picked
    val order = listOf(hero).filter { it in smalls } + usable.filter { it != hero }

    fun words() = if (calendar) Collage.Words(calTitle ?: Collage.monthName(calMonth), calSub ?: "$calYear", order.map { captions.getOrElse(it) { "" } }, note, big)
        else Collage.Words(title, sub, order.map { captions.getOrElse(it) { "" } }, note, big)
    val monthOcc = Occasions.inMonth(occasions, calYear, calMonth)
    fun monthOf() = if (calendar) Collage.Month(calYear, calMonth, marks, circle, dayNote,
        monthOcc.map { Collage.Occ(it.name, it.day, it.kind, faces[it.id]) }) else null
    // each of the month's occasions' first photo, read once
    LaunchedEffect(monthOcc.map { it.id to it.photos.firstOrNull() }) {
        val want = monthOcc.associate { it.id to it.photos.firstOrNull() }
        faces = withContext(Dispatchers.IO) {
            want.mapNotNull { (id, u) -> u?.let { CollageSources.load(context, Uri.parse(it), 900) }?.let { id to it } }.toMap()
        }
    }

    LaunchedEffect(calYear, calMonth) { marks = withContext(Dispatchers.IO) { CollageSources.shotDays(context, calYear, calMonth) } }
    fun moveMonth(by: Int) {
        val m = java.time.YearMonth.of(calYear, calMonth).plusMonths(by.toLong())
        calYear = m.year; calMonth = m.monthValue; circle = null; dayNote = ""; savedMonth = null; status = ""; occEdit = null
        if (editing?.startsWith("day") == true) editing = null
    }

    // the hero read once by the model for Cut-out; its outline from the spot tapped
    LaunchedEffect(layout, hero, modelsReady, smalls.size) {
        if (layout != "cutout" || !modelsReady) return@LaunchedEffect
        val b = smalls[hero] ?: return@LaunchedEffect
        if (embedding?.first != hero) {
            answer = null; status = "reading the hero for the cut-out…"
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            val e = withContext(Dispatchers.Default) { runCatching { Select.embed(context, px, b.width, b.height) }.getOrNull() }
            embedding = e?.let { hero to it }
            status = if (e == null) "could not read the hero (Settings → Logs says why)" else ""
        }
    }
    LaunchedEffect(embedding, cutTap) {
        val e = embedding ?: return@LaunchedEffect
        val b = smalls[e.first] ?: return@LaunchedEffect
        answer = withContext(Dispatchers.Default) { CollageSources.outline(context, e.second, b.width, b.height, cutTap.first, cutTap.second) }
    }

    // the preview, drawn again whenever anything on it changes (a newer change cancels an older drawing)
    LaunchedEffect(smalls, hero, layout, seed, fadeSet, wearSet, title, sub, captions, note, big, faces, answer, tab, calYear, calMonth, marks, circle, dayNote, calTitle, calSub) {
        if (smalls.isEmpty() || order.size < 2) return@LaunchedEffect
        delay(80)
        rendering = true
        val o = order
        val p = withContext(Dispatchers.Default) {
            val aged = o.map { i ->
                val b = smalls.getValue(i)
                CollageSources.aged(context, b, CollageSources.storyOf(context, picks[i].uri), fadeSet, wearSet, AgedPrint.seedOf(picks[i].uri))
            }
            val cut = if (layout == "cutout" && embedding?.first == hero) answer?.let { CollageSources.cut(aged[0], it) } else null
            Collage.render(layout, aged, words(), seed, PREVIEW_SCALE, Collage.fonts(context), cut, monthOf()).also {
                cut?.recycle(); aged.forEachIndexed { k, b -> if (b !== smalls[o[k]]) b.recycle() }
            }
        }
        // the old page is left to the garbage collector: it may still be on screen this frame
        page = p; rendering = false
    }

    fun save() {
        if (saving || order.size < 2) return
        saving = true; status = "saving the collage…"
        val o = order; val lay = layout; val sd = seed; val fd = fadeSet; val wr = wearSet; val w = words(); val ans = answer
        val mo = monthOf()
        val cutOn = lay == "cutout" && embedding?.first == hero
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // the hero is loaded big enough to fill the page; the others at the size of the largest tile they get
                    val full = o.mapIndexed { k, i ->
                        val b = pictures[i]?.let { CollageSources.load(context, it, if (k == 0) 2600 else 1300) } ?: smalls.getValue(i)
                        CollageSources.aged(context, b, CollageSources.storyOf(context, picks[i].uri), fd, wr, AgedPrint.seedOf(picks[i].uri)).also { if (it !== b && b !== smalls[i]) b.recycle() }
                    }
                    val cut = if (cutOn) ans?.let { CollageSources.cut(full[0], it) } else null
                    val pg = Collage.render(lay, full, w, sd, SAVE_SCALE, Collage.fonts(context), cut, mo)
                    cut?.recycle(); full.forEachIndexed { k, b -> if (b !== smalls[o[k]]) b.recycle() }
                    try { CollageSources.save(context, pg.bitmap) } finally { pg.bitmap.recycle() }
                    android.util.Log.i("Latent", "collage saved: $lay, ${o.size} photos, ${pg.width}x${pg.height}" + (mo?.let { " · calendar ${it.year}-${it.month}" } ?: ""))
                }.onFailure { android.util.Log.e("Latent", "collage: save failed", it) }.isSuccess
            }
            saving = false
            status = if (ok) "saved to the roll" else "could not save (Settings → Logs says why)"
            if (ok && mo != null) { savedMonth = mo.year to mo.month; status = "" }
            if (ok) Haptics.click(context)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LatentColors.Background).glow().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.BACK, LatentColors.Text, Modifier.combinedClickable(onClick = onBack).padding(horizontal = 6.dp), 22.dp)
            Text("COLLAGE", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 4.sp)
            val can = !saving && page != null
            Text(if (saving) "Saving…" else "Save", color = if (can) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).then(if (can) Modifier.accentFill() else Modifier.background(LatentColors.Surface))
                    .combinedClickable(enabled = can, onClick = { Haptics.tick(context); save() }).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        // the page; a tap on its title or a caption types it
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, vertical = 6.dp).onSizeChanged { boxSize = it }
            .pointerInput(page) {
                detectTapGestures { o ->
                    val p = page ?: return@detectTapGestures
                    val k = min(boxSize.width / p.width.toFloat(), boxSize.height / p.height.toFloat())
                    val left = (boxSize.width - p.width * k) / 2; val top = (boxSize.height - p.height * k) / 2
                    val units = Collage.W / p.width.toFloat()
                    val ux = (o.x - left) / k * units; val uy = (o.y - top) / k * units
                    val hit = p.spots.lastOrNull { it.box.contains(ux, uy) }
                    val day = hit?.id?.removePrefix("day:")?.toIntOrNull()
                    if (day != null) {
                        // a day: circle it (a new day starts a new note) and type its note
                        Haptics.tick(context)
                        if (circle != day) { circle = day; dayNote = "" }
                        editing = "day"
                    } else editing = hit?.id
                }
            }, contentAlignment = Alignment.Center) {
            val p = page
            if (p != null) Image(p.bitmap.asImageBitmap(), "collage", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
            if (loading || (rendering && p == null)) CircularProgressIndicator(color = LatentColors.Amber, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            else if (!loading && usable.size < 2) Text("A collage needs at least 2 developed photos.\nDevelop the RAW ones first.",
                color = LatentColors.Text, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        // typing the words tapped on the page
        val ed = editing
        if (ed != null) {
            val label = when { ed == "title" -> "TITLE"; ed == "sub" -> "LINE UNDER THE TITLE"; ed == "note" -> "NOTE"
                ed == "big" -> if (layout == "number") "NUMBER" else "LETTER"
                ed == "day" -> "${circle ?: ""} ${Collage.monthName(calMonth).take(3).uppercase()} NOTE"; else -> "CAPTION" }
            val capIdx = ed.removePrefix("cap:").toIntOrNull()?.let { order.getOrNull(it) }
            val value = when {
                ed == "title" -> if (calendar) calTitle ?: Collage.monthName(calMonth) else title
                ed == "sub" -> if (calendar) calSub ?: "$calYear" else sub
                ed == "note" -> note; ed == "big" -> big; ed == "day" -> dayNote; capIdx != null -> captions.getOrElse(capIdx) { "" }; else -> "" }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.width(70.dp))
                BasicTextField(value, onValueChange = { v ->
                    val t = v.take(60)
                    when { ed == "title" -> if (calendar) calTitle = t else title = t; ed == "sub" -> if (calendar) calSub = t else sub = t
                        ed == "note" -> note = v.take(160); ed == "day" -> dayNote = v.take(40)
                        ed == "big" -> big = v.trim().take(if (layout == "number") 3 else 2)
                        capIdx != null -> captions = captions.toMutableList().also { it[capIdx] = t } }
                    savedMonth = null
                }, singleLine = ed != "note", textStyle = TextStyle(color = LatentColors.TextBright, fontSize = 13.sp), cursorBrush = SolidColor(LatentColors.Amber),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(LatentColors.Surface).padding(horizontal = 10.dp, vertical = 8.dp))
                if (ed == "day") Text("Uncircle", color = LatentColors.Text, fontSize = 12.sp,
                    modifier = Modifier.combinedClickable(onClick = { circle = null; dayNote = ""; editing = null; savedMonth = null }).padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
                Text("Done", color = LatentColors.Amber, fontSize = 12.sp, modifier = Modifier.combinedClickable(onClick = { editing = null }).padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
            }
        }
        // the picked photos: tap one to make it the hero
        val oe = occEdit
        Text(if (oe != null) "TAP THE PHOTOS OF ${oe.name.ifBlank { "THIS OCCASION" }.uppercase()} · ${oe.photos.size} PICKED"
            else "${usable.size} PHOTOS · TAP ONE TO MAKE IT THE HERO" + if (usable.size < picks.size) " · ${picks.size - usable.size} NOT DEVELOPED" else "",
            color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp, maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            usable.forEach { i ->
                val pic = pictures.getOrNull(i)?.toString()
                val inOcc = oe != null && pic != null && pic in oe.photos
                val on = if (oe != null) inOcc else i == hero
                Box(Modifier.size(40.dp, 50.dp).clip(RoundedCornerShape(3.dp)).then(if (on) Modifier.border(2.dp, LatentColors.Amber, RoundedCornerShape(3.dp)) else Modifier)
                    .combinedClickable(onClick = {
                        Haptics.tick(context)
                        if (oe != null) { if (pic != null) occEdit = oe.copy(photos = if (inOcc) oe.photos - pic else oe.photos + pic) }
                        else if (hero == i && layout == "cutout" && modelsReady) choosing = true else hero = i
                    })) {
                    smalls[i]?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (on) Text(if (oe != null) "✓" else "HERO", color = LatentColors.AmberInk, fontSize = 7.sp, letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().accentFill().padding(vertical = 1.dp))
                }
            }
        }
        // two kinds: staged scenes (the next step) and flat layouts
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            for ((label, on) in listOf("SCENES" to scenes, "LAYOUTS" to (tab == "layouts"), "CALENDAR" to calendar)) Column(Modifier.combinedClickable(onClick = {
                tab = label.lowercase(); editing = null; occEdit = null; status = "" })) {
                Text(label, color = if (on) LatentColors.Amber else LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Box(Modifier.padding(top = 4.dp).height(2.dp).width(if (on) 40.dp else 0.dp).clip(RoundedCornerShape(1.dp)).accentFill())
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (scenes) listOf("Table", "Album", "Fridge", "Wall").forEach { Chip(it, on = false, enabled = false) {} }
            else Collage.LAYOUTS.forEach { id -> Chip(Collage.LABELS.getValue(id), on = id == layout) { Haptics.tick(context); layout = id; editing = null } }
        }
        // the calendar's month
        if (calendar) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MONTH", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.weight(1f))
            Text("‹", color = LatentColors.Amber, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); moveMonth(-1) }).padding(horizontal = 12.dp))
            Text("${Collage.monthName(calMonth).uppercase()} $calYear", color = LatentColors.TextBright, fontSize = 12.sp, letterSpacing = 1.5.sp)
            Text("›", color = LatentColors.Amber, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); moveMonth(1) }).padding(horizontal = 12.dp))
        }
        // 103b: the month's occasions, and the form for one
        if (calendar) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("OCCASIONS", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(end = 10.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                monthOcc.forEach { o -> Chip("${o.name.ifBlank { "?" }} · ${o.day}", on = occEdit?.id == o.id) { Haptics.tick(context); editing = null; occEdit = occasions.firstOrNull { it.id == o.id } } }
                Chip("+ Add", on = occEdit != null && occasions.none { it.id == occEdit?.id }) {
                    Haptics.tick(context); editing = null
                    occEdit = Occasion(System.currentTimeMillis(), "", calMonth, circle ?: 1, "birthday", emptyList())
                }
            }
        }
        val form = occEdit
        if (calendar && form != null) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(form.name, onValueChange = { occEdit = form.copy(name = it.take(30)) }, singleLine = true,
                    textStyle = TextStyle(color = LatentColors.TextBright, fontSize = 13.sp), cursorBrush = SolidColor(LatentColors.Amber),
                    decorationBox = { inner -> Box { if (form.name.isEmpty()) Text("Name", color = LatentColors.TextDim, fontSize = 13.sp); inner() } },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(LatentColors.Surface).padding(horizontal = 10.dp, vertical = 8.dp))
                val days = java.time.Month.of(form.month).maxLength()
                Text("‹", color = LatentColors.Amber, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = { occEdit = form.copy(day = if (form.day <= 1) days else form.day - 1) }).padding(horizontal = 10.dp))
                Text("${form.day} ${Collage.monthName(form.month).take(3).uppercase()}", color = LatentColors.TextBright, fontSize = 12.sp, letterSpacing = 1.sp)
                Text("›", color = LatentColors.Amber, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = { occEdit = form.copy(day = if (form.day >= days) 1 else form.day + 1) }).padding(horizontal = 10.dp))
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Occasion.KINDS.forEach { k -> Chip(k.replaceFirstChar { it.uppercase() }, on = form.kind == k) { occEdit = form.copy(kind = k) } }
                Spacer(Modifier.weight(1f))
                val known = occasions.any { it.id == form.id }
                if (known) Text("Delete", color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.combinedClickable(onClick = {
                    Occasions.remove(context, form.id); occasions = Occasions.all(context); occEdit = null; savedMonth = null }).padding(6.dp))
                Text("Cancel", color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.combinedClickable(onClick = { occEdit = null }).padding(6.dp))
                val ok = form.name.isNotBlank()
                Text("Keep", color = if (ok) LatentColors.Amber else LatentColors.TextDim, fontSize = 12.sp, modifier = Modifier.combinedClickable(enabled = ok, onClick = {
                    Haptics.click(context); Occasions.put(context, form); occasions = Occasions.all(context); occEdit = null; savedMonth = null
                    android.util.Log.i("Latent", "occasion kept: ${form.kind} ${form.day}/${form.month}, ${form.photos.size} photos")
                }).padding(6.dp))
            }
        }
        // a month saved: on to the next one, picked in the roll
        val saved = savedMonth
        if (calendar && saved != null) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val next = java.time.YearMonth.of(saved.first, saved.second).plusMonths(1)
            Text("✓ ${Collage.monthName(saved.second)} saved to the roll", color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Next: ${Collage.monthName(next.monthValue)}", color = LatentColors.AmberInk, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill()
                    .combinedClickable(onClick = { Haptics.tick(context); onNextMonth(CalendarResume(layout, next.year, next.monthValue)) }).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        val cap = (if (calendar) Collage.CALENDAR_CAP else Collage.CAP).getValue(layout)
        val line = when {
            scenes -> "Table, Album, Fridge and Wall come in the next update"
            status.isNotEmpty() -> status
            calendar && circle != null -> "${circle} ${Collage.monthName(calMonth).take(3)} circled · tap it to change the note"
            calendar && layout in Collage.OCCASION && monthOcc.isEmpty() -> "${Collage.LABELS[layout]} · add this month's birthdays under OCCASIONS"
            calendar && layout != "cutout" -> "Dots: days with photos · tap a day to circle it"
            layout == "cutout" && !modelsReady -> downloading?.let { "downloading the tap-to-select models… ${(it * 100).roundToInt()}%" } ?: "Cut-out uses the tap-to-select models (25 MB)"
            layout == "cutout" -> "Cut-out · tap the hero again to change who"
            usable.size > cap -> "${Collage.LABELS[layout]} · uses $cap of your ${usable.size} photos"
            layout in Collage.BIG -> "${Collage.LABELS[layout]} · tap the ${if (layout == "number") "number" else "letter"} to change it"
            layout in Collage.CAPTIONED -> "${Collage.LABELS[layout]} · tap a caption to type"
            else -> "${Collage.LABELS[layout]} · tap the title to type"
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (calendar && saved != null) Spacer(Modifier.weight(1f))
            else Text(line, color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 2)
            if (!scenes && layout == "cutout" && !modelsReady) {
                Text("Download", color = if (downloading == null) LatentColors.Amber else LatentColors.TextDim, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(enabled = downloading == null, onClick = {
                            downloading = 0f
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Select.download(context) { done, total -> downloading = done.toFloat() / max(1L, total) } }
                                downloading = null; modelsReady = Select.isReady(context)
                                if (r.isFailure) status = "the download failed: ${r.exceptionOrNull()?.message ?: ""}".take(90)
                            }
                        }).padding(horizontal = 12.dp, vertical = 6.dp))
            } else if (!scenes) Text("Shuffle", color = LatentColors.Amber, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); seed = (seed + 1) * 31 + System.nanoTime() % 997 }).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Dial("fade", "colour", fade, { fade = it }) { fadeSet = fade; status = "" }
        Dial("wear", "paper", wear, { wear = it }) { wearSet = wear; status = "" }
        Spacer(Modifier.height(8.dp))
    }
    // Cut-out: tap the person or thing on the hero that should step out of the frame
    if (choosing) smalls[hero]?.let { b ->
        Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.CLOSE, LatentColors.Text, Modifier.combinedClickable(onClick = { choosing = false }).padding(horizontal = 6.dp), 20.dp)
                Text("TAP WHO STEPS OUT", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 3.sp)
                Spacer(Modifier.width(32.dp))
            }
            var sz by remember { mutableStateOf(IntSize.Zero) }
            Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { sz = it }.pointerInput(b) {
                detectTapGestures { o ->
                    val k = min(sz.width / b.width.toFloat(), sz.height / b.height.toFloat())
                    val u = (o.x - (sz.width - b.width * k) / 2) / (b.width * k); val v = (o.y - (sz.height - b.height * k) / 2) / (b.height * k)
                    if (u in 0f..1f && v in 0f..1f) { Haptics.tick(context); cutTap = u to v; choosing = false }
                }
            }) { Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, fontSize = 11.sp, letterSpacing = 1.sp, color = when { on -> LatentColors.AmberInk; enabled -> LatentColors.Text; else -> LatentColors.TextDim }, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill(on, LatentColors.Surface)
            .combinedClickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** One of the age dials, its value always shown; the page is aged again when the dial is let go. */
@Composable
private fun Dial(name: String, what: String, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name.uppercase(), color = LatentColors.Text, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.width(40.dp))
        Text(what, color = LatentColors.TextDim, fontSize = 9.sp, modifier = Modifier.width(40.dp))
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = 0f..AgedPrint.MAX,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.SliderFill, inactiveTrackColor = LatentColors.SliderTrackDim),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
        Text("${(value * 100f).roundToInt()}%", color = LatentColors.Text, fontSize = 10.sp, modifier = Modifier.width(44.dp).padding(start = 4.dp))
    }
}
