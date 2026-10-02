package com.celestial.latent

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.PRINT_EXPOSURE_MAX
import com.celestial.latent.develop.PRINT_EXPOSURE_MIN
import com.celestial.latent.develop.Recipe
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** The print exposure the engine accepts; the same limits as the darkroom's sliders. */
private const val MIN_EXPOSURE = PRINT_EXPOSURE_MIN
private const val MAX_EXPOSURE = PRINT_EXPOSURE_MAX
/** A third of a stop between strips — the step darkroom printers use for a first test. */
private val STEP = 2f.pow(1f / 3f)
private const val STRIPS = 5
/** Print time at exposure 1.0, so the strips can be labelled the way a printer would read them. */
private const val BASE_SECONDS = 8f
/** Photographic paper, slightly warm — what an undeveloped strip looks like. */
private val PAPER = Color(0xFFECE7DD)

/**
 * The five exposures for a test strip around [centre]. The window slides to stay inside the
 * engine's range: at the ends, clamping would make neighbouring strips identical, which would
 * look like a fault rather than a limit.
 */
internal fun stripExposures(centre: Float): List<Float> {
    // Slide by whole strips, so the current print always sits exactly on one of them — preferring
    // the middle, then nearer the middle, then the ends.
    val c = centre.coerceIn(MIN_EXPOSURE, MAX_EXPOSURE)
    for (j in intArrayOf(2, 1, 3, 0, 4)) {
        val lowest = c / STEP.pow(j)
        if (lowest >= MIN_EXPOSURE * 0.999f && lowest * STEP.pow(STRIPS - 1) <= MAX_EXPOSURE * 1.001f)
            return List(STRIPS) { k -> lowest * STEP.pow(k) }
    }
    val lowest = MIN_EXPOSURE                       // cannot happen: the range spans more than four steps
    return List(STRIPS) { k -> lowest * STEP.pow(k) }
}

/** "6.3 s", "10 s" — the way the time on a darkroom timer reads. */
internal fun printSeconds(exposure: Float): String {
    val s = BASE_SECONDS * exposure
    return if (s < 10f) String.format(Locale.US, "%.1f s", s) else String.format(Locale.US, "%.0f s", s)
}

/**
 * Step one, the test strip: the photo cut into five strips, each printed a third of a stop longer
 * than the last. Each comes up out of the paper as it is developed. Tap the strip that looks right
 * and that becomes the print exposure — the same value the FULL sliders show. Hold a strip to see
 * the whole print at that time.
 */
@Composable
private fun TestStripStep(
    recipe: Recipe,
    render: (Recipe, Int) -> Bitmap?,
    onExposure: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val current by rememberUpdatedState(recipe)
    val scope = rememberCoroutineScope()
    var centre by remember { mutableStateOf(recipe.printExposure) }
    val exposures = remember(centre) { stripExposures(centre) }
    val images = remember { mutableStateListOf<Bitmap?>().apply { repeat(STRIPS) { add(null) } } }
    val paperCover = remember { List(STRIPS) { Animatable(1f) } }
    var selected by remember { mutableStateOf(exposures.indices.minByOrNull { abs(exposures[it] - recipe.printExposure) } ?: 2) }
    var holding by remember { mutableStateOf<Int?>(null) }
    var developed by remember { mutableStateOf(0) }
    val measurer = rememberTextMeasurer()

    // Develop the strips left to right, each coming up out of the paper as it finishes.
    LaunchedEffect(centre) {
        developed = 0
        for (i in 0 until STRIPS) { images[i] = null; paperCover[i].snapTo(1f) }
        for (i in 0 until STRIPS) {
            val bmp = withContext(Dispatchers.Default) { render(current.copy(printExposure = exposures[i]), STRIP_EDGE) }
            if (!isActive) return@LaunchedEffect
            images[i] = bmp
            developed = i + 1
            launch { paperCover[i].animateTo(0f, tween(900, easing = FastOutSlowInEasing)) }
        }
    }

    val outline by animateFloatAsState(if (images[selected] != null) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "outline")
    val holdAlpha by animateFloatAsState(if (holding != null) 1f else 0f, tween(220), label = "hold")

    Column(modifier) {
        Text(
            "Tap the strip that looks right. Hold one to see the whole print.",
            color = LatentColors.TextDim, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = 10.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 12.dp)) {
            val first = images.firstOrNull { it != null }
            val aspect = first?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
            Canvas(
                Modifier.fillMaxSize().pointerInput(exposures, aspect) {
                    val labelPx = LABEL_SPACE.toPx()
                    detectTapGestures(
                        onPress = { pos ->
                            val i = stripAt(pos, size.width.toFloat(), size.height.toFloat() - labelPx, aspect) ?: return@detectTapGestures
                            val show = scope.launch { delay(320); if (images[i] != null) { holding = i; Haptics.tick(context) } }
                            tryAwaitRelease()
                            show.cancel(); holding = null
                        },
                        onLongPress = { },   // a hold is for looking; it must not also select
                        onTap = { pos ->
                            val i = stripAt(pos, size.width.toFloat(), size.height.toFloat() - labelPx, aspect) ?: return@detectTapGestures
                            if (images[i] == null) return@detectTapGestures
                            Haptics.tick(context)
                            selected = i
                            onExposure(exposures[i])
                        },
                    )
                },
            ) {
                val r = printRect(size.width, size.height - LABEL_SPACE.toPx(), aspect)
                val border = 6.dp.toPx()
                // the sheet of paper, lifted off the bench by a soft shadow
                drawRect(Color.Black.copy(alpha = 0.45f), Offset(r.left - border + 4.dp.toPx(), r.top - border + 8.dp.toPx()), Size(r.width + 2 * border, r.height + 2 * border))
                drawRect(PAPER, Offset(r.left - border, r.top - border), Size(r.width + 2 * border, r.height + 2 * border))
                val w = r.width / STRIPS
                for (i in 0 until STRIPS) {
                    val x = r.left + i * w
                    val img = images[i]
                    if (img != null) {
                        val sx = (img.width * i / STRIPS)
                        val sw = (img.width / STRIPS).coerceAtLeast(1)
                        drawImage(
                            img.asImageBitmap(),
                            srcOffset = IntOffset(sx, 0), srcSize = IntSize(sw, img.height),
                            dstOffset = IntOffset(x.toInt(), r.top.toInt()), dstSize = IntSize((w + 1).toInt(), r.height.toInt()),
                        )
                    }
                    // undeveloped paper, clearing as the strip comes up
                    val cover = paperCover[i].value
                    if (cover > 0.001f) drawRect(PAPER.copy(alpha = cover), Offset(x, r.top), Size(w + 1, r.height))
                    // the faint line where the card was moved along
                    if (i > 0) drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, r.top), Offset(x, r.top + r.height), 1.dp.toPx())
                    // the time, under each strip, as a printer would mark it
                    val label = measurer.measure(printSeconds(exposures[i]), TextStyle(
                        color = if (i == selected && img != null) LatentColors.Amber else LatentColors.TextDim, fontSize = 11.sp))
                    drawText(label, topLeft = Offset(x + (w - label.size.width) / 2f, r.top + r.height + border + 8.dp.toPx()))
                }
                // the chosen strip
                if (outline > 0f) {
                    val sx = r.left + selected * w
                    drawRect(LatentColors.Amber.copy(alpha = outline), Offset(sx + 1.dp.toPx(), r.top + 1.dp.toPx()),
                        Size(w - 2.dp.toPx(), r.height - 2.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
                // held: the whole print at that strip's time, over the strips
                val h = holding
                if (h != null && holdAlpha > 0f) {
                    images[h]?.let { full ->
                        drawImage(full.asImageBitmap(), dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                            dstSize = IntSize(r.width.toInt(), r.height.toInt()), alpha = holdAlpha)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (developed < STRIPS) "developing strip ${developed + 1} of $STRIPS…"
                else "print time ${printSeconds(exposures[selected])} · exposure ${String.format(Locale.US, "%.2f", exposures[selected])}×",
                color = LatentColors.Text, fontSize = 12.sp,
            )
            // a second, finer look, centred on the choice — what a printer does next
            val canRetest = developed == STRIPS && abs(exposures[selected] - centre) > 0.001f
            Text(
                "test around it", color = if (canRetest) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (canRetest) LatentColors.Amber else LatentColors.Surface)
                    .then(if (canRetest) Modifier.pointerInput(selected) {
                        detectTapGestures(onTap = {
                            Haptics.tick(context)
                            val chosen = exposures[selected]
                            centre = chosen
                            // near the ends the window slides, so the choice is not always the middle
                            val next = stripExposures(chosen)
                            selected = next.indices.minByOrNull { abs(next[it] - chosen) } ?: 2
                        })
                    } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Room left under the print for the strip times. */
private val LABEL_SPACE = 28.dp

private data class PrintRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Where the print sits: as large as fits, centred, keeping the photo's shape. */
private fun printRect(areaW: Float, areaH: Float, aspect: Float): PrintRect {
    val pad = 10f
    var w = areaW - 2 * pad; var h = w / aspect
    if (h > areaH - 2 * pad) { h = areaH - 2 * pad; w = h * aspect }
    return PrintRect((areaW - w) / 2f, (areaH - h) / 2f, w, h)
}

/** Which strip a touch landed on, or null if it missed the print. */
private fun stripAt(pos: Offset, areaW: Float, areaH: Float, aspect: Float): Int? {
    // exactly the geometry the drawing uses — the caller passes the height minus the label room
    val r = printRect(areaW, areaH, aspect)
    if (pos.x < r.left || pos.x > r.left + r.width || pos.y < r.top || pos.y > r.top + r.height) return null
    return ((pos.x - r.left) / (r.width / STRIPS)).toInt().coerceIn(0, STRIPS - 1)
}

/** The three steps of printing. Steps that exist are tappable; one that doesn't yet says so. */
@Composable
private fun StepIndicator(active: Int, available: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val steps = listOf("TEST STRIP", "COLOUR", "DODGE & BURN")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        steps.forEachIndexed { i, label ->
            val on = i == active
            val bar by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "step")
            Column(Modifier.pointerInput(i) { detectTapGestures(onTap = { onSelect(i) }) }) {
                Text("${i + 1}  $label", color = when { on -> LatentColors.Amber; i < available -> LatentColors.TextDim; else -> LatentColors.Line },
                    fontSize = 10.sp, letterSpacing = 1.5.sp)
                Box(Modifier.padding(top = 4.dp).height(2.dp).width((40 * bar).dp).clip(RoundedCornerShape(1.dp)).background(LatentColors.Amber))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The host, the shared cache, and step two: the colour ring-around.
// ---------------------------------------------------------------------------------------------

/** Test strips are large enough to hold up to a long press showing the whole print. */
private const val STRIP_EDGE = 560
/** Ring-around tiles are small on screen; a third of the pixels makes each about three times faster. */
private const val TILE_EDGE = 320
/** The colour filters' range, as on the FULL sliders. */
private const val FILTER_LIMIT = 20f

/**
 * Every print the panel develops, remembered by what was printed: exposure and both filters. A
 * print is never developed twice — moving between steps, or tapping a ring-around tile whose
 * neighbours were already made, costs nothing for what already exists. Kept small; least recently
 * used goes first. Synchronised: a cancelled step can still be finishing a render while the next
 * step starts.
 */
private class PrintCache {
    // Exposure and filters, rounded so tiny float differences still match — plus everything else in
    // the recipe, so a print can never be served for a recipe that differs in any other way.
    private data class Key(val e: Int, val y: Int, val m: Int, val rest: Int)
    private fun key(r: Recipe) = Key(
        Math.round(r.printExposure * 1000), Math.round(r.yFilterShift * 10), Math.round(r.mFilterShift * 10),
        r.copy(printExposure = 0f, yFilterShift = 0f, mFilterShift = 0f, previewMaxSize = 0).hashCode(),
    )
    private val map = object : LinkedHashMap<Key, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?) = size > 40
    }
    /** A print at least nearly as large as asked for, or null. */
    @Synchronized fun peek(r: Recipe, edge: Int): Bitmap? =
        map[key(r)]?.takeIf { maxOf(it.width, it.height) >= edge * 0.9f }
    @Synchronized fun put(r: Recipe, b: Bitmap) {
        val old = map[key(r)]
        if (old == null || maxOf(b.width, b.height) > maxOf(old.width, old.height)) map[key(r)] = b
    }
}

/**
 * PRINT mode: the darkroom done the way a printer works — by trying and choosing, not by sliders.
 * Test strip for how long, ring-around for what colour; dodge and burn comes next.
 *
 * @param renderAt develops a preview of a recipe at a given size. It blocks and queues for the
 *   engine itself, so it is only ever called off the main thread.
 */
@Composable
fun PrintPanel(
    recipe: Recipe,
    renderAt: (Recipe, Int) -> Bitmap?,
    onExposure: (Float) -> Unit,
    onFilters: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = remember { PrintCache() }
    var step by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    val render: (Recipe, Int) -> Bitmap? = { r, e -> cache.peek(r, e) ?: renderAt(r, e)?.also { cache.put(r, it) } }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2600); note = "" } }

    Column(modifier.background(LatentColors.Background)) {
        StepIndicator(
            active = step, available = 2,
            onSelect = { i ->
                if (i < 2) { if (i != step) Haptics.tick(context); step = i }
                else note = "Dodge & burn is coming next."
            },
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = 6.dp),
        )
        val noteAlpha by animateFloatAsState(if (note.isNotEmpty()) 1f else 0f, tween(240), label = "note")
        if (noteAlpha > 0f) Text(note, color = LatentColors.Amber.copy(alpha = noteAlpha), fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = 6.dp))
        // Steps slide sideways in the direction you move, and fade, so it reads as one bench.
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(320)) { w -> dir * w / 6 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(260)) { w -> -dir * w / 6 } + fadeOut(tween(200)))
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = "print step",
        ) { s ->
            when (s) {
                0 -> TestStripStep(recipe, render, onExposure, Modifier.fillMaxSize())
                else -> RingAroundStep(recipe, render, { r, e -> cache.peek(r, e) }, onFilters, Modifier.fillMaxSize())
            }
        }
    }
}

/** Grid order: the current print, then the four straight colour moves, then the corners. */
private val RING_ORDER = intArrayOf(4, 1, 3, 5, 7, 0, 2, 6, 8)
private val STEP_SIZES = listOf(10f, 5f, 2f)

/** Average colour of a print, for measuring what a tile actually looks like. */
private fun meanRgb(b: Bitmap): FloatArray {
    val small = Bitmap.createScaledBitmap(b, 24, 18, true)
    val px = IntArray(24 * 18); small.getPixels(px, 0, 24, 0, 0, 24, 18)
    var r = 0f; var g = 0f; var bl = 0f
    for (c in px) { r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); bl += (c and 0xFF) }
    val n = px.size * 255f
    if (small !== b) small.recycle()
    return floatArrayOf(r / n, g / n, bl / n)
}
/** How warm (red against blue) and how green (green against magenta) a colour is. */
private fun warmth(c: FloatArray) = c[0] - c[2]
private fun greenness(c: FloatArray) = c[1] - (c[0] + c[2]) / 2f

/** Describes a tile by what it measurably looks like next to the centre — never by assumption. */
private fun describe(tile: FloatArray, centre: FloatArray): String {
    val dw = warmth(tile) - warmth(centre); val dg = greenness(tile) - greenness(centre)
    val t = 0.006f
    val parts = buildList {
        if (dw > t) add("warmer") else if (dw < -t) add("cooler")
        if (dg > t) add("greener") else if (dg < -t) add("more magenta")
    }
    return if (parts.isEmpty()) "much the same" else parts.joinToString(" · ")
}

/**
 * Step two, the ring-around: how colour printers found their colour. Nine small prints, the
 * current one in the middle and around it the same print with a little more or less of each
 * filter. Tap the one you like: it glides to the centre, the prints already made slide with it,
 * and only the new edge is developed.
 *
 * Which way a filter pushes the colour is not assumed: each tile is labelled by measuring it
 * against the centre, and the first grid learns which side is warm, so from then on warmer is
 * always to the right and more magenta always below.
 */
@Composable
private fun RingAroundStep(
    recipe: Recipe,
    render: (Recipe, Int) -> Bitmap?,
    peek: (Recipe, Int) -> Bitmap?,
    onFilters: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(recipe)
    var centreY by remember { mutableStateOf(recipe.yFilterShift) }
    var centreM by remember { mutableStateOf(recipe.mFilterShift) }
    var stepSize by remember { mutableStateOf(5f) }
    var ySign by remember { mutableStateOf(com.celestial.latent.develop.DarkroomPrefs.ringSign(context, "y")) }
    var mSign by remember { mutableStateOf(com.celestial.latent.develop.DarkroomPrefs.ringSign(context, "m")) }
    val tiles = remember { mutableStateListOf<Bitmap?>().apply { repeat(9) { add(null) } } }
    val labels = remember { mutableStateListOf<String>().apply { repeat(9) { add("") } } }
    val cover = remember { List(9) { Animatable(1f) } }
    val glideX = remember { Animatable(0f) }
    val glideY = remember { Animatable(0f) }
    var gliding by remember { mutableStateOf(false) }
    var developed by remember { mutableStateOf(0) }
    val measurer = rememberTextMeasurer()

    fun yAt(i: Int) = centreY + ySign * ((i % 3) - 1) * stepSize
    fun mAt(i: Int) = centreM + mSign * ((i / 3) - 1) * stepSize
    fun inRange(i: Int) = abs(yAt(i)) <= FILTER_LIMIT + 0.001f && abs(mAt(i)) <= FILTER_LIMIT + 0.001f
    fun variant(i: Int) = current.copy(yFilterShift = yAt(i), mFilterShift = mAt(i))

    LaunchedEffect(centreY, centreM, stepSize, ySign, mSign) {
        // Anything already developed appears at once, so a tap keeps its neighbours in place.
        developed = 0
        for (i in 0 until 9) {
            val have = if (inRange(i)) peek(variant(i), TILE_EDGE) else null
            tiles[i] = have; labels[i] = ""
            cover[i].snapTo(if (have != null) 0f else 1f)
            if (have != null || !inRange(i)) developed++
        }
        for (i in RING_ORDER) {
            if (tiles[i] != null || !inRange(i)) continue
            val bmp = withContext(Dispatchers.Default) { render(variant(i), TILE_EDGE) }
            if (!isActive) return@LaunchedEffect
            tiles[i] = bmp; developed++
            launch { cover[i].animateTo(0f, tween(700, easing = FastOutSlowInEasing)) }
        }
        // Measure every tile against the centre, and label it by what it actually looks like.
        val centreBmp = tiles[4] ?: return@LaunchedEffect
        val measured = withContext(Dispatchers.Default) {
            val c = meanRgb(centreBmp)
            List(9) { i -> tiles[i]?.let { if (i == 4) "current" else describe(meanRgb(it), c) } ?: "" } to
                Pair(tiles[5]?.let { warmth(meanRgb(it)) - warmth(c) }, tiles[7]?.let { greenness(meanRgb(it)) - greenness(c) })
        }
        if (!isActive) return@LaunchedEffect
        measured.first.forEachIndexed { i, l -> labels[i] = l }
        // Learn the layout once: warmer on the right, more magenta below. If this engine pushes the
        // other way, mirror the grid — the prints are the same, only their places change.
        val (rightWarmth, belowGreen) = measured.second
        if (rightWarmth != null && rightWarmth < -0.004f) {
            ySign = -ySign; com.celestial.latent.develop.DarkroomPrefs.setRingSign(context, "y", ySign)
        }
        if (belowGreen != null && belowGreen > 0.004f) {
            mSign = -mSign; com.celestial.latent.develop.DarkroomPrefs.setRingSign(context, "m", mSign)
        }
    }

    Column(modifier) {
        Text(
            "Tap the print with the colour you like. It moves to the centre.",
            color = LatentColors.TextDim, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = 10.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            val first = tiles.firstOrNull { it != null }
            val aspect = first?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
            Canvas(
                Modifier.fillMaxSize().pointerInput(aspect, centreY, centreM, stepSize, ySign, mSign) {
                    detectTapGestures(onTap = { pos ->
                        if (gliding) return@detectTapGestures
                        val g = ringGeometry(size.width.toFloat(), size.height.toFloat(), aspect, density)
                        val i = g.tileAt(pos) ?: return@detectTapGestures
                        if (i == 4 || tiles[i] == null || !inRange(i)) return@detectTapGestures
                        Haptics.tick(context)
                        val ny = yAt(i); val nm = mAt(i)
                        val dx = ((i % 3) - 1).toFloat(); val dy = ((i / 3) - 1).toFloat()
                        scope.launch {
                            gliding = true
                            // the whole sheet slides so the chosen print arrives in the middle
                            launch { glideX.animateTo(-dx, tween(380, easing = FastOutSlowInEasing)) }
                            glideY.animateTo(-dy, tween(380, easing = FastOutSlowInEasing))
                            centreY = ny; centreM = nm
                            onFilters(ny, nm)
                            glideX.snapTo(0f); glideY.snapTo(0f)
                            gliding = false
                        }
                    })
                },
            ) {
                val g = ringGeometry(size.width, size.height, aspect, density)
                val border = 3.dp.toPx()
                val sx = glideX.value * (g.tileW + g.gap); val sy = glideY.value * (g.tileH + g.cellExtra + g.gap)
                clipRect {
                    for (i in 0 until 9) {
                        val (x0, y0) = g.origin(i)
                        val x = x0 + sx; val y = y0 + sy
                        drawRect(Color.Black.copy(alpha = 0.4f), Offset(x - border + 2.dp.toPx(), y - border + 4.dp.toPx()), Size(g.tileW + 2 * border, g.tileH + 2 * border))
                        drawRect(PAPER, Offset(x - border, y - border), Size(g.tileW + 2 * border, g.tileH + 2 * border))
                        val img = tiles[i]
                        if (img != null) drawImage(img.asImageBitmap(), dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize(g.tileW.toInt(), g.tileH.toInt()))
                        val c = cover[i].value
                        if (c > 0.001f) drawRect(PAPER.copy(alpha = c), Offset(x, y), Size(g.tileW, g.tileH))
                        if (!inRange(i)) {
                            val t = measurer.measure("limit", TextStyle(color = Color(0x99000000), fontSize = 10.sp))
                            drawText(t, topLeft = Offset(x + (g.tileW - t.size.width) / 2f, y + (g.tileH - t.size.height) / 2f))
                        }
                        if (i == 4 && !gliding) drawRect(LatentColors.Amber, Offset(x - border, y - border),
                            Size(g.tileW + 2 * border, g.tileH + 2 * border), style = Stroke(2.dp.toPx()))
                        val text = labels[i]
                        if (text.isNotEmpty() && !gliding) {
                            val t = measurer.measure(text, TextStyle(color = if (i == 4) LatentColors.Amber else LatentColors.TextDim, fontSize = 10.sp))
                            drawText(t, topLeft = Offset(x + (g.tileW - t.size.width) / 2f, y + g.tileH + border + 3.dp.toPx()))
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (developed < 9) "developing ${developed} of 9…"
                else "filtration Y ${String.format(Locale.US, "%+.0f", centreY)} · M ${String.format(Locale.US, "%+.0f", centreM)}",
                color = LatentColors.Text, fontSize = 12.sp,
            )
            // how big a step between neighbours: coarse to find the area, fine to settle it
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                STEP_SIZES.forEach { sz ->
                    val on = sz == stepSize
                    Text(
                        String.format(Locale.US, "%.0f", sz), color = if (on) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                            .pointerInput(sz, on) { detectTapGestures(onTap = { if (!on && !gliding) { Haptics.tick(context); stepSize = sz } }) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** Where the nine tiles sit: as large as fits, each with room for its label beneath. */
private class RingGeometry(val left: Float, val top: Float, val tileW: Float, val tileH: Float, val gap: Float, val cellExtra: Float) {
    fun origin(i: Int): Pair<Float, Float> = Pair(left + (i % 3) * (tileW + gap), top + (i / 3) * (tileH + cellExtra + gap))
    fun tileAt(p: Offset): Int? {
        for (i in 0 until 9) {
            val (x, y) = origin(i)
            if (p.x in x..(x + tileW) && p.y in y..(y + tileH)) return i
        }
        return null
    }
}

private fun ringGeometry(w: Float, h: Float, aspect: Float, density: Float): RingGeometry {
    val gap = 12f * density; val extra = 18f * density             // the label under each tile
    // Room at the edges for each print's paper border and shadow — without it the top row's
    // border was cut off.
    val margin = 7f * density
    val aw = w - 2 * margin; val ah = h - 2 * margin
    var tileW = (aw - 2 * gap) / 3f; var tileH = tileW / aspect
    val need = 3 * tileH + 3 * extra + 2 * gap
    if (need > ah) { tileH = (ah - 3 * extra - 2 * gap) / 3f; tileW = tileH * aspect }
    val totalW = 3 * tileW + 2 * gap; val totalH = 3 * tileH + 3 * extra + 2 * gap
    return RingGeometry((w - totalW) / 2f, (h - totalH) / 2f, tileW, tileH, gap, extra)
}
