package com.celestial.latent

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.ExposureMap
import com.celestial.latent.develop.Layer
import com.celestial.latent.develop.Layers
import com.celestial.latent.develop.Recipe
import com.celestial.latent.develop.Region
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// 109a: PRINT → LAYERS. Parts of the photo, each with its own exposure, warmth and colour, made to
// the light before the film (develop/Layers.kt). Approved mock: layers/mock/v3-0-layers-mock-sheet.jpg.
// Under the photo a strip of layer cards; "+ layer" adds one (Tap to select, Paint, Sky when one is
// found, Near or far from the depth, Everything else); tap a card to edit it (ADJUST, BRUSH); hold a
// card to rename, copy, move or delete it. The painting itself is the painting steps' own (PaintStep):
// the same brush, zoom, undo, tap to select and depth bar as DODGE & BURN.
// ---------------------------------------------------------------------------------------------

/** What the LAYERS step keeps while it is open: the chosen layer, the menus, the sky it found. */
private class LayersUi {
    var selected by mutableStateOf(-1)
    var showAll by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var menuFor by mutableStateOf<Int?>(null)
    var renaming by mutableStateOf<String?>(null)
    /** Raised to open tap to select in the painting step (for a new layer). */
    var selectTick by mutableStateOf(0)
    /** The print without layers, as last made: the cards' thumbnails, and what the sky is found in. */
    var picture by mutableStateOf<Bitmap?>(null)
    /** The sky found in this photo (on a mask grid), or null; [skyLooked] once it has been looked for. */
    var sky by mutableStateOf<ExposureMap?>(null)
    var skyLooked by mutableStateOf(false)
    var depthBusy by mutableStateOf(false)
    var note by mutableStateOf("")
    /** The layers as last handed on, and the list they were made from: two changes in one moment (an undo
     *  restores the painting and the sliders together) build on each other instead of the second undoing the first. */
    var latest: List<Layer>? = null
    var latestFrom: List<Layer>? = null
    /** "+ layer → Tap to select" chosen: the selection, once used, becomes a new layer (not the chosen one). */
    var newBySelect by mutableStateOf(false)
    /** 110a: delete asked about this layer (another step uses it), waiting for an answer. */
    var deleteAsk by mutableStateOf(false)
}

/**
 * 110a: what uses a layer, for its card's second line and delete's question: its own NEGATIVE look,
 * its own light (ADJUST), and the PRINT steps that keep it ([others], e.g. LITH).
 */
private fun usesOf(context: android.content.Context, l: Layer, others: List<String>): List<String> =
    listOfNotNull(if (l.recipe == null) null else l.lookId?.let { com.celestial.latent.develop.LayerLooks.name(context, it) } ?: "OWN NEGATIVE") +
        l.summary().split(" · ").filter { it != "no changes" } + others

/** The brush paints a layer's area in: 0 outside … 1 inside, its colour on the photo. */
private fun layerSpec(l: Layer?): PaintSpec = PaintSpec(
    hint = if (l == null) "No layers yet. Tap + layer, or paint on the photo to start one."
        else "${l.name}: tinted area. ADJUST its light, BRUSH its area.",
    minusLabel = "take away", plusLabel = "add",
    min = 0f, max = 1f, overlayFull = 1f,
    tintPlus = Color(l?.let { Layers.COLOURS[Math.floorMod(it.tint, Layers.COLOURS.size)] } ?: Layers.COLOURS[0]), tintMinus = Color(0xFF9A9A9A),
    // a layer is in or out: "medium" fills it in two passes, "strong" at once
    passScale = 1f,
    status = { m, _ ->
        if (m == null || m.isBlank) "the layer is empty: paint it in, or use select"
        else "covers ${Math.round(m.stops.count { it > 0.5f } * 100f / m.stops.size)}% of the picture"
    },
)

/** A new layer's name, kept apart from the others' (SKY, SKY 2 …). Names stay short: they head a card and the tabs. */
/** 109d/109e: the opposite's name: NOT SKY, and the opposite of NOT SKY is SKY again. */
private fun oppositeName(name: String): String {
    if (name.startsWith("NOT ")) return name.removePrefix("NOT ").ifBlank { "LAYER" }
    return "NOT $name".take(16)
}

private fun freeName(base: String, layers: List<Layer>): String {
    if (layers.none { it.name == base }) return base
    var n = 2
    while (layers.any { it.name == "$base $n" }) n++
    return "$base $n"
}

/** The first colour no layer uses yet (sky blue for a sky), else the next in turn. */
private fun freeTint(layers: List<Layer>, prefer: Int? = null): Int {
    if (prefer != null && layers.none { it.tint == prefer }) return prefer
    return (Layers.COLOURS.indices).firstOrNull { c -> layers.none { it.tint == c } } ?: (layers.size % Layers.COLOURS.size)
}

/** A picture's grid, each cell the mean of the pixels in its box, as display values 0..1 (interleaved RGB). */
private fun gridOf(b: Bitmap, w: Int, h: Int): FloatArray {
    val bw = b.width; val bh = b.height
    val px = IntArray(bw * bh); b.getPixels(px, 0, bw, 0, 0, bw, bh)
    val sum = DoubleArray(w * h * 3); val cnt = IntArray(w * h)
    for (y in 0 until bh) {
        val gy = minOf(h - 1, (y.toLong() * h / bh).toInt())
        for (x in 0 until bw) {
            val k = gy * w + minOf(w - 1, (x.toLong() * w / bw).toInt()); val c = px[y * bw + x]
            sum[3 * k] += ((c shr 16) and 0xFF) / 255.0; sum[3 * k + 1] += ((c shr 8) and 0xFF) / 255.0; sum[3 * k + 2] += (c and 0xFF) / 255.0
            cnt[k]++
        }
    }
    return FloatArray(w * h * 3) { i -> (sum[i] / maxOf(1, cnt[i / 3])).toFloat() }
}

/** A layer's area as a tinted picture the size of its mask, for the photo and the card's thumbnail. */
private fun tintOf(l: Layer, alpha: Float = 0.55f): Bitmap {
    val m = l.mask; val c = Layers.COLOURS[Math.floorMod(l.tint, Layers.COLOURS.size)] and 0xFFFFFF
    val px = IntArray(m.width * m.height) { k -> ((m.stops[k].coerceIn(0f, 1f) * alpha * 255f).toInt() shl 24) or c }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun LayersStep(
    recipe: Recipe,
    layers: List<Layer>,
    onLayers: (List<Layer>) -> Unit,
    depthRanks: DepthRanks?,
    selectPicture: SelectPicture?,
    depthSplit: ExposureMap?, onDepthSplit: (ExposureMap?) -> Unit,
    depthFix: ExposureMap?, onDepthFix: (ExposureMap?) -> Unit,
    /** The print with these layers (everything else as the darkroom has it). */
    render: (Recipe, Int, List<Layer>) -> Bitmap?,
    renderRegion: ((Recipe, Int, List<Layer>, Region) -> Bitmap?)?,
    modifier: Modifier = Modifier,
    /** 110a: the other PRINT steps that keep a layer (by id), e.g. "lith", for the cards and delete's question. */
    usedBy: (Int) -> List<String> = { emptyList() },
) {
    val context = LocalContext.current
    val ui = remember { LayersUi() }
    val sel = layers.firstOrNull { it.id == ui.selected } ?: layers.firstOrNull()
    /** Changes one layer, on top of any change already handed on in this same moment. */
    fun edit(id: Int, f: (Layer) -> Layer) {
        val base = ui.latest?.takeIf { ui.latestFrom === layers } ?: layers
        val next = base.map { if (it.id == id) f(it) else it }
        ui.latest = next; ui.latestFrom = layers
        onLayers(next)
    }
    fun newId() = (layers.maxOfOrNull { it.id } ?: 0) + 1
    fun aspect() = ui.picture?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
    /** Adds a layer and edits it. */
    fun add(name: String, mask: ExposureMap, prefer: Int? = null) {
        if (layers.size >= Layers.MAX) { ui.note = "${Layers.MAX} layers at most"; ui.adding = false; return }
        val l = Layer(newId(), freeName(name, layers), freeTint(layers, prefer), mask)
        onLayers(layers + l); ui.selected = l.id; ui.adding = false; ui.showAll = false
        android.util.Log.i("Latent", "layers: added ${l.name} (${Math.round(mask.stops.average() * 100)}% of the picture)")
    }
    // The depth, once, from the print without layers: the sky and "near or far" need it.
    LaunchedEffect(ui.picture != null) {
        val dr = depthRanks ?: return@LaunchedEffect
        val pic = ui.picture ?: return@LaunchedEffect
        if (dr.raw == null) { ui.depthBusy = true; dr.ensure(context, pic); while (dr.busy) delay(150); ui.depthBusy = false }
        if (dr.raw == null || ui.skyLooked) return@LaunchedEffect
        val dims = ExposureMap.blank(pic.width.toFloat() / pic.height); val w = dims.width; val h = dims.height
        val found = withContext(Dispatchers.Default) {
            runCatching {
                val near = dr.near(2 * w, 2 * h) ?: return@runCatching null
                Layers.findSky(gridOf(pic, 2 * w, 2 * h), near, 2 * w, 2 * h)?.let { ExposureMap(w, h, Layers.halve(it, 2 * w, 2 * h, w, h)) }
            }.getOrNull()
        }
        ui.sky = found; ui.skyLooked = true
        android.util.Log.i("Latent", "layers: sky " + (found?.let { "found, ${Math.round(it.stops.average() * 100)}% of the picture" } ?: "not found"))
    }
    /** Near or far: the photo split at its depths' natural gap (the depth bar's own foreground). */
    fun byDepth(near: Boolean) {
        val dr = depthRanks ?: return
        val d = ExposureMap.blank(aspect())
        val fg = dr.foreground(d.width, d.height) ?: run { ui.note = "the depth is not ready yet"; return }
        add(if (near) "NEAR" else "FAR", ExposureMap(d.width, d.height, FloatArray(fg.size) { if (near) fg[it] else 1f - fg[it] }))
    }
    LaunchedEffect(ui.note) { if (ui.note.isNotEmpty()) { delay(2600); ui.note = "" } }
    // the other layers' tints for "show all", made again only when the layers change
    val tints = remember(layers) { layers.associate { it.id to tintOf(it) } }

    key(sel?.id ?: -1) {
        val spec = remember(sel?.name, sel?.tint) { layerSpec(sel) }
        PaintStep(
            spec, recipe, sel?.mask,
            onMap = { m ->
                if (sel == null) { if (m != null && !m.isBlank) add("LAYER", m) }
                else edit(sel.id) { it.copy(mask = m ?: ExposureMap.blank(aspect())) }
            },
            renderWith = { r, e, m -> render(r, e, if (sel == null) layers else layers.map { if (it.id == sel.id) it.copy(mask = m ?: ExposureMap.blank(aspect())) else it }) },
            stepStartMap = { a -> ExposureMap.blank(a) },
            modifier = modifier,
            renderRegion = renderRegion?.let { f -> { r, e, m, reg -> f(r, e, if (sel == null) layers else layers.map { if (it.id == sel.id) it.copy(mask = m ?: ExposureMap.blank(aspect())) else it }, reg) } },
            tabs = if (sel == null) null else listOf("ADJUST" to @Composable { AdjustRows(sel) { f -> edit(sel.id, f) } }),
            tabsLead = sel?.name,
            // the other layers' settings re-develop the print; this one's painting does so itself
            refreshKey = layers.joinToString(";") { (if (it.id == sel?.id) "${it.ev}:${it.warmth}:${it.colour}:${it.visible}" else it.key()) + ":" + (it.recipe?.toJson()?.hashCode() ?: 0) },
            lookState = sel?.let { Triple(it.ev, it.warmth, it.colour) },
            onRestoreLook = { st ->
                @Suppress("UNCHECKED_CAST") val t = st as? Triple<Float, Float, Float>
                if (sel != null && t != null) edit(sel.id) { it.copy(ev = t.first, warmth = t.second, colour = t.third) }
            },
            renderBefore = { r, e -> render(r, e, emptyList()) },
            depthRanks = depthRanks, selectPicture = selectPicture,
            depthSplit = depthSplit, onDepthSplit = onDepthSplit, depthFix = depthFix, onDepthFix = onDepthFix,
            // 109b: for the open layer the selection goes through the brush (commit), so it shows and undoes
            selectUse = if (sel == null || ui.newBySelect) Pair<String, ((ExposureMap, Boolean) -> Unit)?>("make it a layer") { m, inverted -> ui.newBySelect = false; add(if (inverted) "BACKGROUND" else "SUBJECT", m) }
                        else Pair<String, ((ExposureMap, Boolean) -> Unit)?>("use for ${sel.name}", null),
            openSelectKey = ui.selectTick,
            startShowMask = sel != null && !ui.showAll,
            onBefore = { b -> ui.picture = b },
            overlayDraw = if (!ui.showAll) null else { a, toScreen ->
                val tl = toScreen(0f, 0f); val br = toScreen(1f, 1f)
                // every shown layer, the chosen one too (its own mask tint starts off while "show all" is on)
                for (l in layers) if (l.visible) tints[l.id]?.let { bmp ->
                    drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(tl.x.toInt(), tl.y.toInt()), dstSize = IntSize((br.x - tl.x).toInt(), (br.y - tl.y).toInt()))
                }
            },
            strip = @Composable { LayersStrip(layers, sel, ui, tints, onLayers, { n, m, p -> add(n, m, p) }, aspect = { aspect() }, hasDepth = depthRanks != null,
                byDepth = { near -> byDepth(near) }, usedBy = usedBy) },
        )
    }
}

/** The LAYERS label, "show all", the cards and "+ layer"; under them the add menu or a card's own menu. */
@Composable
private fun LayersStrip(
    layers: List<Layer>, sel: Layer?, ui: LayersUi, tints: Map<Int, Bitmap>,
    onLayers: (List<Layer>) -> Unit, add: (String, ExposureMap, Int?) -> Unit, aspect: () -> Float, hasDepth: Boolean,
    byDepth: (Boolean) -> Unit, usedBy: (Int) -> List<String>,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (ui.note.isNotEmpty()) ui.note else "LAYERS", color = if (ui.note.isNotEmpty()) LatentColors.Amber else LatentColors.TextDim,
                fontSize = if (ui.note.isNotEmpty()) 11.sp else 10.sp, letterSpacing = if (ui.note.isNotEmpty()) 0.sp else 1.5.sp)
            if (layers.size > 1) PrintChip("show all", on = ui.showAll) { ui.showAll = !ui.showAll }
        }
        // Card widths: 92 dp each + 8 dp gaps; four cards and "+" (56 dp) ≈ 408 dp, so the row scrolls on a phone.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in layers) LayerCard(l, usesOf(context, l, usedBy(l.id)), chosen = l.id == sel?.id, picture = ui.picture, tint = tints[l.id],
                onTap = { ui.selected = l.id; ui.menuFor = null; ui.adding = false; ui.newBySelect = false },
                onEye = { onLayers(layers.map { if (it.id == l.id) it.copy(visible = !it.visible) else it }) },
                onHold = { ui.menuFor = l.id; ui.adding = false; ui.renaming = null; ui.deleteAsk = false })
            Box(Modifier.width(56.dp).height(120.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, if (ui.adding) LatentColors.Amber else LatentColors.Line, RoundedCornerShape(10.dp))
                .pointerInput(layers.size) { detectTapGestures(onTap = { Haptics.tick(context); ui.adding = !ui.adding; ui.menuFor = null }) },
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = LatentColors.Text, fontSize = 18.sp)
                    Text("layer", color = LatentColors.TextDim, fontSize = 10.sp)
                }
            }
        }
        if (ui.adding) AddMenu(layers, ui, add, aspect, hasDepth, byDepth)
        val menu = ui.menuFor?.let { id -> layers.firstOrNull { it.id == id } }
        // delete asks only about what lives outside LAYERS: the layer's NEGATIVE look and other PRINT steps
        if (menu != null) CardMenu(menu, layers, ui, onLayers,
            listOfNotNull(if (menu.recipe == null) null else "its NEGATIVE look (" + (menu.lookId?.let { com.celestial.latent.develop.LayerLooks.name(context, it) } ?: "own settings") + ")") +
                usedBy(menu.id).map { it.uppercase(Locale.US) })
    }
}

/**
 * One layer's card: its area tinted on the photo, the eye (top right), the colour dot and name, and
 * its changes in two lines. 92 × 120 dp: thumbnail 92 × 60, name 11 sp, two 9 sp lines.
 */
@Composable
private fun LayerCard(l: Layer, uses: List<String>, chosen: Boolean, picture: Bitmap?, tint: Bitmap?, onTap: () -> Unit, onEye: () -> Unit, onHold: () -> Unit) {
    val context = LocalContext.current
    val colour = Color(Layers.COLOURS[Math.floorMod(l.tint, Layers.COLOURS.size)])
    // 109b: the gesture is set up once per card, so it must read the newest actions (an old onEye
    // would hand back the list of layers as it was when the card first appeared)
    val tapNow by rememberUpdatedState(onTap); val eyeNow by rememberUpdatedState(onEye); val holdNow by rememberUpdatedState(onHold)
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(92.dp).height(120.dp).clip(shape).background(LatentColors.Surface)
        .border(if (chosen) 1.5.dp else 0.dp, if (chosen) LatentColors.Amber else Color.Transparent, shape)
        .pointerInput(l.id) {
            detectTapGestures(
                // the eye's touch area is the thumbnail's top-right 40 × 36 dp (109b: was 30 × 26)
                onTap = { p -> Haptics.tick(context); if (p.x > size.width - 40.dp.toPx() && p.y < 36.dp.toPx()) eyeNow() else tapNow() },
                onLongPress = { Haptics.tick(context); holdNow() })
        }.padding(5.dp)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(6.dp))) {
            drawRect(Color(0xFF242424))
            val pic = picture
            if (pic != null) {
                // the photo filling the thumbnail (cropped to its shape), its area tinted over it
                val k = maxOf(size.width / pic.width, size.height / pic.height)
                val w = pic.width * k; val h = pic.height * k
                val o = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()); val s = IntSize(w.toInt(), h.toInt())
                drawImage(pic.asImageBitmap(), dstOffset = o, dstSize = s, alpha = if (l.visible) 1f else 0.45f)
                if (tint != null && l.visible) drawImage(tint.asImageBitmap(), dstOffset = o, dstSize = s)
            }
            // the eye: open when the layer shows, struck through when hidden
            val c = Offset(size.width - 11.dp.toPx(), 9.dp.toPx())
            drawCircle(Color(0xCC161615), 8.dp.toPx(), c)
            drawOval(Color.White, Offset(c.x - 5.dp.toPx(), c.y - 3.dp.toPx()), Size(10.dp.toPx(), 6.dp.toPx()), style = Stroke(1.2.dp.toPx()))
            drawCircle(Color.White, 1.6.dp.toPx(), c)
            if (!l.visible) drawLine(Color.White, Offset(c.x - 5.dp.toPx(), c.y + 4.dp.toPx()), Offset(c.x + 5.dp.toPx(), c.y - 4.dp.toPx()), 1.4.dp.toPx())
        }
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(colour))
            // 110a: the whole name, one size smaller (BACKGROUND was cut to "BACKGRO…"); a long one a size smaller again
            Text(l.name, color = if (chosen) LatentColors.Amber else LatentColors.Text, fontSize = if (l.name.length > 10) 9.sp else 10.sp, letterSpacing = 0.3.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp))
        }
        // 109c/110a: what uses the layer (its own look, its own light, LITH…), or that nothing does yet
        val parts = uses.ifEmpty { listOf("not used yet") }
        Text(if (!l.visible) "hidden" else parts.joinToString(" · "), color = LatentColors.TextDim, fontSize = 9.sp, lineHeight = 11.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "+ layer": what the new layer is. Two rows, measured: Tap to select ~96 + Paint ~50 + Sky ~40 dp;
 * Near ~48 + Far ~40 + Everything else ~112 + cancel ~60 dp, each under the ~357 dp a phone has.
 */
@Composable
private fun AddMenu(layers: List<Layer>, ui: LayersUi, add: (String, ExposureMap, Int?) -> Unit, aspect: () -> Float, hasDepth: Boolean,
                    byDepth: (Boolean) -> Unit) {
    val depthReady = hasDepth && !ui.depthBusy && ui.skyLooked
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp)) {
        Text("New layer from…", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PrintChip("Tap to select", on = false) {
                // the layer is made when the selection is used; until then nothing is added
                ui.newBySelect = true; ui.adding = false; ui.selectTick++
            }
            PrintChip("Paint", on = false) { add("LAYER", ExposureMap.blank(aspect()), null) }
            ui.sky?.let { s -> PrintChip("Sky", on = false) { add("SKY", s.copy(), 0) } }
        }
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PrintChip("Near", on = false, enabled = depthReady) { byDepth(true) }
            PrintChip("Far", on = false, enabled = depthReady) { byDepth(false) }
            PrintChip("Everything else", on = false, enabled = layers.isNotEmpty()) {
                val d = ExposureMap.blank(aspect())
                add("THE REST", ExposureMap(d.width, d.height, Layers.everythingElse(layers.filter { it.visible }, d.width, d.height)), null)
            }
            PrintChip("cancel", on = false) { ui.adding = false }
        }
        Text(
            when {
                !hasDepth -> "Near, far and the sky need the depth model (LIGHT → SURFACE installs it)."
                ui.depthBusy || !ui.skyLooked -> "Working out the depth…"
                ui.sky == null -> "No sky found in this photo: paint one, or tap to select it."
                else -> "Sky found. Near and far split the photo by distance."
            },
            color = LatentColors.TextDim, fontSize = 11.sp,
        )
    }
}

/** Hold a card: rename, copy, move left or right, delete; 109d: opposite, invert (second row). Widths: rename 62 + copy 48 + ‹ 30 + › 30 + delete 56 + done 50 ≈ 296 dp. */
@Composable
private fun CardMenu(l: Layer, layers: List<Layer>, ui: LayersUi, onLayers: (List<Layer>) -> Unit, uses: List<String>) {
    val i = layers.indexOfFirst { it.id == l.id }
    fun delete() { onLayers(layers.filter { it.id != l.id }); ui.menuFor = null; ui.deleteAsk = false; if (ui.selected == l.id) ui.selected = -1 }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp)) {
        val naming = ui.renaming
        // 110a: a layer something else uses asks once before it goes (it used to vanish silently, and
        // a lith kept in it spread over the whole print)
        if (ui.deleteAsk) {
            Text("${l.name} is used by ${uses.joinToString(", ")}. Delete it? " +
                (if (uses.any { it == "LITH" }) "LITH goes back to the whole print." else "Those changes go with it."),
                color = LatentColors.Text, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PrintChip("delete", on = true) { delete() }
                PrintChip("keep it", on = false) { ui.deleteAsk = false }
            }
        } else if (naming != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(naming, onValueChange = { ui.renaming = it.uppercase(Locale.getDefault()).take(12) }, singleLine = true,
                    textStyle = TextStyle(color = LatentColors.Text, fontSize = 13.sp, letterSpacing = 1.sp), cursorBrush = SolidColor(LatentColors.Amber),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(LatentColors.Surface).padding(horizontal = 10.dp, vertical = 7.dp))
                PrintChip("save", on = naming.isNotBlank(), enabled = naming.isNotBlank()) {
                    onLayers(layers.map { if (it.id == l.id) it.copy(name = naming.trim()) else it }); ui.renaming = null; ui.menuFor = null
                }
                PrintChip("cancel", on = false) { ui.renaming = null }
            }
        } else Column {
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PrintChip("rename", on = false) { ui.renaming = l.name }
            PrintChip("copy", on = false, enabled = layers.size < Layers.MAX) {
                val c = l.copy(id = (layers.maxOfOrNull { it.id } ?: 0) + 1, name = freeName(l.name, layers), tint = freeTint(layers), mask = l.mask.copy())
                    // 109e: a copy keeps the NEGATIVE settings only while there is room for one more layer with its own
                    .let { if (it.recipe != null && layers.count { o -> o.recipe != null } >= com.celestial.latent.develop.LayerLooks.MAX_EXTRA) it.copy(recipe = null, lookId = null) else it }
                onLayers(layers.toMutableList().also { it.add(i + 1, c) }); ui.selected = c.id; ui.menuFor = null
            }
            PrintChip("‹", on = false, enabled = i > 0) { onLayers(layers.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }
            PrintChip("›", on = false, enabled = i < layers.size - 1) { onLayers(layers.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }
            PrintChip("delete", on = false) { if (uses.isEmpty()) delete() else ui.deleteAsk = true }
            PrintChip("done", on = false) { ui.menuFor = null }
          }
          // 109d: the other side of this layer. Snapping is linear in the area, so the opposite's snapped
          // edge is exactly the complement of this one's: the two meet with no gap and no overlap.
          Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PrintChip("opposite", on = false, enabled = layers.size < Layers.MAX) {
                val c = Layer((layers.maxOfOrNull { it.id } ?: 0) + 1, freeName(oppositeName(l.name), layers), freeTint(layers), Layers.inverted(l.mask))
                onLayers(layers.toMutableList().also { it.add(i + 1, c) }); ui.selected = c.id; ui.menuFor = null; ui.showAll = false
            }
            PrintChip("invert", on = false) {
                onLayers(layers.map { if (it.id == l.id) it.copy(mask = Layers.inverted(it.mask)) else it }); ui.menuFor = null
            }
          }
        }
        if (!ui.deleteAsk) Text("${l.name}: opposite makes a new layer of everything outside it; invert flips its own area. Overlaps add up, so order changes only the strip.", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** ADJUST: the chosen layer's Exposure, Warmth and Colour, each with its value; the print re-develops on release. */
@Composable
private fun AdjustRows(l: Layer, onLayer: ((Layer) -> Layer) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        LayerSlider("Exposure", l.ev, -2f, 2f, 0f, { String.format(Locale.US, "%+.1f EV", it).replace('-', '−') }) { v -> onLayer { it.copy(ev = v) } }
        LayerSlider("Warmth", l.warmth, -1f, 1f, 0f, { String.format(Locale.US, "%+.2f", it).replace('-', '−') }) { v -> onLayer { it.copy(warmth = v) } }
        LayerSlider("Colour", l.colour, 0f, 2f, 1f, { String.format(Locale.US, "×%.2f", it) }) { v -> onLayer { it.copy(colour = v) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PrintChip("reset layer", on = false, enabled = abs(l.ev) + abs(l.warmth) + abs(l.colour - 1f) > 1e-4f) { onLayer { it.copy(ev = 0f, warmth = 0f, colour = 1f) } }
        }
    }
}

/**
 * A slider as in the approved mock: its name and value above, the fill drawn from the neutral point
 * (0 EV, no warmth, ×1 colour) to the value, so which way it moved is plain. Snaps to neutral within
 * 3% of the range; double-tap goes back to it.
 */
@Composable
private fun LayerSlider(name: String, value: Float, min: Float, max: Float, neutral: Float, label: (Float) -> String, onDone: (Float) -> Unit) {
    // 109b: one state for the slider's life, following [value] when it changes from outside (undo, reset),
    // and the newest onDone: the gestures are set up once and would otherwise keep the first ones
    var live by remember { mutableStateOf(value) }
    LaunchedEffect(value) { live = value }
    val done by rememberUpdatedState(onDone)
    fun snap(v: Float) = v.coerceIn(min, max).let { if (abs(it - neutral) < (max - min) * 0.03f) neutral else (it * 100f).roundToInt() / 100f }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = LatentColors.Text, fontSize = 11.sp)
            Text(label(live), color = LatentColors.Text, fontSize = 11.sp)
        }
        val fill = LatentColors.SliderFill; val track = LatentColors.SliderTrackDim; val thumb = LatentColors.Amber
        Canvas(Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(min, max) {
                detectTapGestures(onDoubleTap = { live = neutral; done(neutral) },
                    onTap = { p -> live = snap(min + (max - min) * (p.x / size.width)); done(live) })
            }
            .pointerInput(min, max) {
                detectHorizontalDragGestures(onDragEnd = { done(live) }, onDragCancel = { done(live) }) { ch, _ ->
                    live = snap(min + (max - min) * (ch.position.x / size.width)); ch.consume()
                }
            }) {
            val y = size.height / 2f; val r = 8.dp.toPx(); val x0 = r; val x1 = size.width - r
            fun at(v: Float) = x0 + (x1 - x0) * (v - min) / (max - min)
            drawLine(track, Offset(x0, y), Offset(x1, y), 3.dp.toPx())
            drawLine(fill, Offset(at(neutral), y), Offset(at(live), y), 3.dp.toPx())
            drawCircle(fill, 2.dp.toPx(), Offset(at(neutral), y))
            drawCircle(thumb, r, Offset(at(live), y))
        }
    }
}
