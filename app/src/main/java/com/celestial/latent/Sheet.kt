package com.celestial.latent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Contact-print paper, the same cream as the prints view, in every theme. */
private val SHEET_PAPER = Color(0xFFF4F1EA)
/** The film's rebate as it prints: near black, the sprocket holes a touch blacker. */
private val REBATE = Color(0xFF1D1A17)
private val HOLE = Color(0xFF080707)
/** Edge printing (film name, frame numbers) prints light and warm through the rebate. */
private val EDGE_INK = Color(0xFFE6BE86)
/** The china marker that rings picked frames. */
private val GREASE = Color(0xFFCC3B2A)
private const val PER_STRIP = 3

/**
 * 89a: the roll as a contact sheet (approved proof visual-3/proof/1-contact-sheet.jpg): strips of three
 * frames on cream paper, the film's name and the frame numbers printed along the rebate, a frame's kind
 * (RAW, HDR, BURST) printed on its edge, picked frames ringed in red china marker.
 *
 * Tap opens a frame, a double tap picks it (the same choosing as a long press in the other views), and a
 * hold brings up a loupe over the sheet that follows the finger until it lets go. [card] draws a frame's
 * picture fitted upright in its 3:2 frame; [picture] gives the best picture to hand for the loupe, and
 * [onLoupe] is told which frame is under it so a sharper one can be loaded.
 */
@Composable
internal fun <T> RollSheet(
    items: List<T>, key: (T) -> String, film: String, title: String,
    note: (T) -> String, picked: (T) -> Boolean, picture: (T) -> ImageBitmap?,
    onOpen: (T) -> Unit, onPick: (T) -> Unit, onLoupe: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** The key of a frame flying back from the darkroom: the sheet scrolls to its strip (as 88d does for the other views). */
    home: String? = null,
    card: @Composable BoxScope.(T) -> Unit,
) {
    val context = LocalContext.current
    val tm = rememberTextMeasurer()
    val edge = TextStyle(color = EDGE_INK, fontSize = 6.5.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
    val strips = items.chunked(PER_STRIP)
    val list = rememberLazyListState()
    // where each frame's picture is on screen, for the loupe to find what is under the finger
    val cells = remember { HashMap<String, Rect>() }
    var loupe by remember { mutableStateOf<Pair<T, Offset>?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(home, items.size) {
        val i = if (home == null) -1 else items.indexOfFirst { key(it) == home }
        if (i < 0) return@LaunchedEffect
        val item = 1 + i / PER_STRIP
        if (list.layoutInfo.visibleItemsInfo.none { it.index == item }) list.scrollToItem(item)
        withFrameNanos { }
        val info = list.layoutInfo
        val me = info.visibleItemsInfo.firstOrNull { it.index == item } ?: return@LaunchedEffect
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        list.scrollBy(me.offset + me.size / 2f - mid)
    }
    fun under(at: Offset): T? = items.firstOrNull { cells[key(it)]?.contains(at) == true }
    Box(modifier.onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        LazyColumn(
            Modifier.fillMaxSize().shadow(10.dp, RoundedCornerShape(2.dp), ambientColor = sheetShadow(), spotColor = sheetShadow())
                .background(SHEET_PAPER),
            state = list, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item(key = "title") {
                // written along the top of the sheet, as you would
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, color = Color(0xFF5E5850), fontSize = 10.sp, letterSpacing = 1.6.sp)
                    Text("${items.size} ${if (items.size == 1) "FRAME" else "FRAMES"}", color = Color(0xFF8C857B), fontSize = 9.sp, letterSpacing = 1.4.sp)
                }
            }
            itemsIndexed(strips, key = { _, s -> key(s.first()) }) { si, strip ->
                Row(
                    Modifier.fillMaxWidth().drawBehind {
                        drawRect(REBATE)
                        // sprocket holes along both edges
                        val hw = 2.4.dp.toPx(); val hh = 3.2.dp.toPx(); val step = 6.3.dp.toPx(); val inset = 1.6.dp.toPx()
                        var x = step / 2f
                        while (x < size.width) {
                            drawRoundRect(HOLE, Offset(x - hw / 2f, inset), Size(hw, hh), CornerRadius(0.7.dp.toPx()))
                            drawRoundRect(HOLE, Offset(x - hw / 2f, size.height - inset - hh), Size(hw, hh), CornerRadius(0.7.dp.toPx()))
                            x += step
                        }
                    }.padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (c in 0 until PER_STRIP) {
                        val item = strip.getOrNull(c)
                        if (item == null) { Spacer(Modifier.weight(1f)); continue }
                        val n = si * PER_STRIP + c + 1
                        val k = key(item)
                        val text = note(item)
                        val ring = picked(item)
                        Column(
                            Modifier.weight(1f).drawBehind {
                                // edge print: film name and the frame's kind above, frame numbers below
                                val top = 4.6.dp.toPx() - 0.5f; val bottom = size.height - 11.dp.toPx()
                                drawText(tm, film, Offset(4.dp.toPx(), top), edge.copy(fontSize = 5.6.sp, color = EDGE_INK.copy(alpha = 0.75f)))
                                if (text.isNotEmpty()) drawText(tm, text, Offset(size.width - 18.dp.toPx(), top), edge.copy(fontSize = 5.6.sp, color = Color(0xFFFFE2B0)))
                                drawText(tm, "$n", Offset(2.dp.toPx(), bottom), edge)
                                drawText(tm, "▸${n}A", Offset(size.width * 0.5f, bottom), edge.copy(color = EDGE_INK.copy(alpha = 0.8f)))
                            }.padding(vertical = 13.dp),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1.5f)
                                    // the ring is drawn outside the frame's clip: it loops out over the rebate
                                    .drawWithContent { drawContent(); if (ring) greaseRing(size, seed = n) }
                                    .clip(RoundedCornerShape(1.dp)).background(Color(0xFF050505))
                                    .onGloballyPositioned { cells[k] = it.boundsInRoot() }
                                    .pointerInput(k) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            var lifted = false
                                            val waited = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { lifted = waitForUpOrCancellation() != null; true }
                                            if (waited == null) {
                                                // held: the loupe follows the finger until it lets go
                                                val at0 = cells[k]?.topLeft ?: Offset.Zero
                                                down.consume()
                                                loupe = item to (at0 + down.position); onLoupe(item)
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                    ch.consume()
                                                    if (!ch.pressed) break
                                                    val at = at0 + ch.position
                                                    val now = under(at)
                                                    if (now != null && now != loupe?.first) { Haptics.tick(context); onLoupe(now) }
                                                    loupe = (now ?: loupe!!.first) to at
                                                }
                                                loupe = null
                                            } else if (lifted) {
                                                // a second tap soon after picks; one tap alone opens
                                                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                                                if (second != null) { second.consume(); waitForUpOrCancellation(); onPick(item) } else onOpen(item)
                                            }
                                        }
                                    },
                            ) { card(item) }
                        }
                    }
                }
            }
        }
        loupe?.let { (item, at) -> picture(item)?.let { pic -> Loupe(pic, cells[key(item)], at, origin) } }
    }
}

private fun sheetShadow() = if (LatentColors.palette.depth) LatentColors.palette.shadow else Color.Black

/** A china-marker loop round a picked frame: drawn by hand, so it wobbles and doesn't quite close. */
private fun DrawScope.greaseRing(box: Size, seed: Int) {
    val cx = box.width / 2f; val cy = box.height / 2f
    val rx = box.width / 2f + 1.5.dp.toPx(); val ry = box.height / 2f + 4.dp.toPx()
    val a0 = java.util.Random(seed * 7919L + 13).nextFloat() * 6.28f
    val path = Path()
    val n = 64; val turns = 1.12f; val sq = 0.35f
    for (k in 0..n) {
        val t = k / n.toFloat()
        val a = a0 + t * turns * 6.2832f
        val wob = 1f + 0.045f * sin(a * 3f + seed) + 0.03f * cos(a * 5f + seed * 2)
        // squarer than an ellipse, as a quick ring round a rectangle is
        val c = cos(a); val s = sin(a)
        val px = cx + rx * wob * (c * (1 - sq) + sq * Math.signum(c) * kotlin.math.sqrt(kotlin.math.abs(c)))
        val py = cy + ry * wob * (s * (1 - sq) + sq * Math.signum(s) * kotlin.math.sqrt(kotlin.math.abs(s))) + t * 2.dp.toPx()
        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, GREASE.copy(alpha = 0.92f), style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The loupe: a round glass held just above the finger, showing the frame under the finger about three
 * times larger, centred on the spot under it, in a dark metal rim.
 */
@Composable
private fun BoxScope.Loupe(pic: ImageBitmap, cell: Rect?, at: Offset, origin: Offset) {
    val d = 150.dp
    val density = LocalDensity.current
    val dPx = with(density) { d.toPx() }
    val lift = with(density) { 96.dp.toPx() }
    val local = at - origin
    Canvas(
        Modifier.offset { IntOffset((local.x - dPx / 2f).roundToInt(), (local.y - lift - dPx / 2f).roundToInt()) }
            .size(d).shadow(18.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black),
    ) {
        val r = size.minDimension / 2f
        clipPath(Path().apply { addOval(Rect(Offset.Zero, size)) }) {
            drawRect(Color(0xFF050505))
            val iw = pic.width.toFloat(); val ih = pic.height.toFloat()
            if (cell != null && cell.width > 0f) {
                // the picture as the sheet fits it into its frame, then that spot magnified 3x
                val fit = minOf(cell.width / iw, cell.height / ih)
                val shownW = iw * fit; val shownH = ih * fit
                val left = cell.left + (cell.width - shownW) / 2f; val top = cell.top + (cell.height - shownH) / 2f
                val zoom = 3f
                val sc = fit * zoom
                val fx = (at.x - left).coerceIn(0f, shownW) / fit; val fy = (at.y - top).coerceIn(0f, shownH) / fit
                drawImage(pic, dstOffset = IntOffset((r - fx * sc).roundToInt(), (r - fy * sc).roundToInt()),
                    dstSize = IntSize((iw * sc).roundToInt().coerceAtLeast(1), (ih * sc).roundToInt().coerceAtLeast(1)))
            }
            // the glass: darker toward the rim, a soft reflection top left
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), center, r), r)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), Offset(r * 0.6f, r * 0.55f), r * 0.6f), r * 0.6f, Offset(r * 0.6f, r * 0.55f))
        }
        drawCircle(Brush.linearGradient(listOf(Color(0xFF6B6D72), Color(0xFF1B1C1F), Color(0xFF3A3C41)), Offset.Zero, Offset(size.width, size.height)), r - 4.dp.toPx(), style = Stroke(8.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.35f), r - 0.8.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

/* ---------------------------------------------- empty states ---------------------------------------------- */

enum class EmptyKind { ROLL, DEVELOPED, LATENT, BURSTS, FOLDER }

/**
 * 89b: what the roll shows when a view has nothing in it (approved proof visual-3/proof/2-empty-states.jpg):
 * a small drawing in the glyphs' line, one sentence that says how, and the next step if there is one.
 */
@Composable
internal fun EmptyState(kind: EmptyKind, modifier: Modifier = Modifier, action: String = "", onAction: () -> Unit = {}) {
    val context = LocalContext.current
    val (title, line) = when (kind) {
        EmptyKind.ROLL -> "Nothing on this roll yet" to "Load a film on the camera and take your first shot. It lands here as a frame."
        EmptyKind.DEVELOPED -> "Nothing developed yet" to "Open a frame and press Develop, or develop every waiting RAW at once."
        EmptyKind.LATENT -> "No RAWs waiting" to "Every RAW on this roll has been developed. New RAW shots wait here."
        EmptyKind.BURSTS -> "No bursts yet" to "Set the button next to the shutter to 16, FS or HDR, then shoot. Bursts and brackets land here."
        EmptyKind.FOLDER -> "This folder has no photos" to "Only photos directly in the folder show here, not ones in folders inside it."
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(150.dp, 120.dp)) { drawEmpty(kind, LatentColors.TextDim, LatentColors.Amber) }
        Spacer(Modifier.height(18.dp))
        Text(title.uppercase(), color = LatentColors.TextBright, fontSize = 12.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(line, color = LatentColors.Text, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        if (action.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(action, color = LatentColors.AmberInk, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill()
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) { Haptics.tick(context); onAction() } } }
                    .padding(horizontal = 18.dp, vertical = 9.dp))
        }
    }
}

/** The empty-state drawings: the glyph line at a larger size, on a 150 x 120 grid. */
private fun DrawScope.drawEmpty(kind: EmptyKind, ink: Color, accent: Color) {
    val u = size.width / 150f
    val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color = ink) = drawRoundRect(c, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = line)
    fun seg(vararg pts: Pair<Float, Float>, c: Color = ink) = drawPath(Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) } }, c, style = line)
    fun dot(x: Float, y: Float, r: Float, c: Color = ink) = drawCircle(c, r * u, p(x, y))
    // a faint floor line so the drawing sits on something
    seg(18f to 108f, 132f to 108f, c = ink.copy(alpha = 0.35f))
    when (kind) {
        EmptyKind.ROLL -> {
            // a film canister, its leader out across the floor with no frames on it yet
            box(22f, 40f, 40f, 62f, 6f); seg(30f to 32f, 54f to 32f); seg(34f to 32f, 34f to 40f); seg(50f to 32f, 50f to 40f)
            box(28f, 58f, 28f, 24f, 2f, ink.copy(alpha = 0.5f))
            drawPath(Path().apply { moveTo(62f * u, 66f * u); cubicTo(86f * u, 60f * u, 100f * u, 70f * u, 128f * u, 64f * u)
                lineTo(128f * u, 88f * u); cubicTo(100f * u, 94f * u, 86f * u, 84f * u, 62f * u, 90f * u) }, ink, style = line)
            for (k in 0 until 6) { val x = 70f + k * 10f; val y0 = 66.5f + 1.6f * sin(k * 0.9f); dot(x, y0 + 3f, 1.6f, ink.copy(alpha = 0.7f)); dot(x, y0 + 19f, 1.6f, ink.copy(alpha = 0.7f)) }
            dot(116f, 34f, 4f, accent); seg(116f to 22f, 116f to 26f, c = accent); seg(116f to 42f, 116f to 46f, c = accent); seg(104f to 34f, 108f to 34f, c = accent); seg(124f to 34f, 128f to 34f, c = accent)
        }
        EmptyKind.DEVELOPED -> {
            // a developing tray with a blank sheet in it, waiting
            seg(20f to 70f, 26f to 104f, 124f to 104f, 130f to 70f)
            drawPath(Path().apply { moveTo(28f * u, 84f * u); cubicTo(50f * u, 78f * u, 62f * u, 90f * u, 75f * u, 84f * u); cubicTo(88f * u, 78f * u, 100f * u, 90f * u, 122f * u, 84f * u) }, accent, style = line)
            rotate(-6f, p(75f, 54f)) { box(50f, 30f, 50f, 46f, 2f); box(56f, 36f, 38f, 28f, 1f, ink.copy(alpha = 0.4f)) }
            box(112f, 18f, 14f, 22f, 2f, ink.copy(alpha = 0.7f)); seg(115f to 18f, 115f to 12f, 123f to 12f, 123f to 18f, c = ink.copy(alpha = 0.7f))
        }
        EmptyKind.LATENT -> {
            // a developed strip: every frame shows a little picture, a tick at the end
            box(14f, 46f, 110f, 40f, 3f)
            for (k in 0 until 4) { val x = 20f + k * 26f; box(x, 54f, 22f, 24f, 1.5f)
                seg(x + 3f to 74f, x + 9f to 66f, x + 13f to 71f, x + 16f to 67f, x + 19f to 74f, c = ink.copy(alpha = 0.7f)) }
            for (k in 0 until 10) { dot(18f + k * 11f, 50f, 1.4f, ink.copy(alpha = 0.6f)); dot(18f + k * 11f, 82f, 1.4f, ink.copy(alpha = 0.6f)) }
            seg(128f to 32f, 134f to 38f, 146f to 24f, c = accent)
        }
        EmptyKind.BURSTS -> {
            // a stack of frames fanned out, the top one blank
            rotate(-10f, p(70f, 66f)) { box(40f, 36f, 56f, 64f, 4f, ink.copy(alpha = 0.4f)) }
            rotate(-4f, p(72f, 64f)) { box(44f, 32f, 56f, 64f, 4f, ink.copy(alpha = 0.7f)) }
            box(50f, 28f, 56f, 64f, 4f)
            drawCircle(accent, 12f * u, p(78f, 60f), style = line); dot(78f, 60f, 3f, accent)
            seg(116f to 40f, 128f to 34f, c = ink.copy(alpha = 0.6f)); seg(118f to 52f, 132f to 52f, c = ink.copy(alpha = 0.6f)); seg(116f to 64f, 128f to 70f, c = ink.copy(alpha = 0.6f))
        }
        EmptyKind.FOLDER -> {
            seg(28f to 40f, 28f to 100f, 122f to 100f, 122f to 50f, 76f to 50f, 66f to 36f, 28f to 36f, 28f to 40f)
            seg(60f to 76f, 90f to 76f, c = ink.copy(alpha = 0.5f))
            drawCircle(accent, 9f * u, p(108f, 28f), style = line); seg(114.5f to 34.5f, 122f to 42f, c = accent)
        }
    }
}
