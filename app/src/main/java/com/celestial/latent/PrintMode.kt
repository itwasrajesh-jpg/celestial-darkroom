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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.celestial.latent.develop.ExposureMap
import com.celestial.latent.develop.Recipe
import kotlin.math.PI
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import com.celestial.latent.develop.RaysLook
import com.celestial.latent.develop.Fog
import com.celestial.latent.develop.FogLook
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import com.celestial.latent.develop.Region
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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
    // Four names in one row, measured: "TEST STRIP" and 16 dp gaps would need ~394 dp of the ~357
    // a phone has, and the last would be crushed. "STRIP", 12 dp gaps and 1.2 sp letter spacing
    // come to about 331 dp.
    val steps = listOf("STRIP", "COLOUR", "DODGE & BURN", "SOFTEN", "FOG", "LIGHT")
    // six names are wider than a phone: the row scrolls sideways
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, label ->
            val on = i == active
            val bar by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "step")
            Column(Modifier.pointerInput(i) { detectTapGestures(onTap = { onSelect(i) }) }) {
                Text("${i + 1}  $label", color = when { on -> LatentColors.Amber; i < available -> LatentColors.TextDim; else -> LatentColors.Line },
                    fontSize = 10.sp, letterSpacing = 1.2.sp, maxLines = 1, softWrap = false)
                Box(Modifier.padding(top = 4.dp).height(2.dp).width((40 * bar).dp).clip(RoundedCornerShape(1.dp)).background(LatentColors.Amber))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The host, the shared cache, and step two: the colour ring-around.
// ---------------------------------------------------------------------------------------------

/**
 * Everything painted on a photo, as one value, so a render can never mix them up. For rays, no
 * coverage means "everywhere", so whether rays are on at all is said separately: test strips and
 * the ring-around are made without them.
 */
data class Masks(
    val dodge: ExposureMap? = null,
    val soften: ExposureMap? = null,
    val fog: ExposureMap? = null,
    val rays: ExposureMap? = null,
    val raysOn: Boolean = false,
)

/** The height of a painting step's tabbed controls: the same for every tab, so the photo never moves. */
private val CONTROLS_HEIGHT = 196.dp

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
 * Test strip for how long, ring-around for what colour, dodge and burn for where the light
 * falls, and soften for where the diffusion glows.
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
    exposureMap: ExposureMap?,
    onExposureMap: (ExposureMap?) -> Unit,
    softenMap: ExposureMap?,
    onSoftenMap: (ExposureMap?) -> Unit,
    onDiffusionOn: () -> Unit,
    fogMap: ExposureMap?,
    onFogMap: (ExposureMap?) -> Unit,
    fogLook: FogLook,
    onFogLook: (FogLook) -> Unit,
    sampleScene: (Float, Float) -> FloatArray?,
    raysMap: ExposureMap?,
    onRaysMap: (ExposureMap?) -> Unit,
    raysLook: RaysLook,
    onRaysLook: (RaysLook) -> Unit,
    extraLights: List<RaysLook> = emptyList(),
    onExtraLights: (List<RaysLook>) -> Unit = {},
    renderWithMasks: (Recipe, Int, Masks) -> Bitmap?,
    renderRegionWithMasks: ((Recipe, Int, Masks, Region) -> Bitmap?)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = remember { PrintCache() }
    var step by remember { mutableStateOf(0) }
    // the fog step's eyedropper: on, the next tap on the print takes the scene's light there
    var fogPicking by remember { mutableStateOf(false) }
    // the rays step's light: placing it, the next tap on the print sets where the light comes from
    var raysPlacing by remember { mutableStateOf(false) }
    // Which light the LIGHT step edits: 0 is light 1 (kept where it always was), then lights 2-4.
    var selected by remember { mutableStateOf(0) }
    // bumped when the depth model is installed from the SURFACE tab, so the print re-develops with the light
    var depthTick by remember { mutableStateOf(0) }
    val lights = listOf(raysLook) + extraLights
    val sel = lights[selected.coerceIn(0, lights.size - 1)]
    fun setSel(l: RaysLook) {
        if (selected <= 0 || selected > extraLights.size) onRaysLook(l)
        else onExtraLights(extraLights.toMutableList().also { it[selected - 1] = l })
    }
    // the selected light as it is being dragged; follows the saved one whenever that changes
    var raysLive by remember(sel) { mutableStateOf(sel.withDefaults()) }
    // all four masks as they stand; each painting step swaps in its own, as it is being painted
    val all = Masks(exposureMap, softenMap, fogMap, raysMap, raysOn = true)
    val pickScope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    val render: (Recipe, Int) -> Bitmap? = { r, e -> cache.peek(r, e) ?: renderAt(r, e)?.also { cache.put(r, it) } }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2600); note = "" } }

    Column(modifier.background(LatentColors.Background)) {
        StepIndicator(
            active = step, available = 6,
            onSelect = { i -> if (i != step) Haptics.tick(context); step = i },
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
                1 -> RingAroundStep(recipe, render, { r, e -> cache.peek(r, e) }, onFilters, Modifier.fillMaxSize())
                // each painting step shows the print with BOTH masks: it is one print
                2 -> PaintStep(DODGE_BURN_SPEC, recipe, exposureMap, onExposureMap,
                    renderWith = { r, e, m -> renderWithMasks(r, e, all.copy(dodge = m)) },
                    startMap = { a -> ExposureMap.blank(a) },
                    modifier = Modifier.fillMaxSize(),
                    renderRegion = renderRegionWithMasks?.let { f -> { r, e, m, reg -> f(r, e, all.copy(dodge = m), reg) } })
                3 -> PaintStep(SOFTEN_SPEC, recipe, softenMap, onSoftenMap,
                    renderWith = { r, e, m -> renderWithMasks(r, e, all.copy(soften = m)) },
                    // begin from what is on screen: softened everywhere if diffusion is on, else sharp
                    startMap = { a -> ExposureMap.blank(a).also { if (recipe.diffusion) it.stops.fill(1f) } },
                    modifier = Modifier.fillMaxSize(),
                    onPaintPlus = onDiffusionOn,
                    renderRegion = renderRegionWithMasks?.let { f -> { r, e, m, reg -> f(r, e, all.copy(soften = m), reg) } })
                4 -> PaintStep(FOG_SPEC, recipe, fogMap, onFogMap,
                    renderWith = { r, e, m -> renderWithMasks(r, e, all.copy(fog = m)) },
                    startMap = { a -> ExposureMap.blank(a) },
                    modifier = Modifier.fillMaxSize(),
                    renderRegion = renderRegionWithMasks?.let { f -> { r, e, m, reg -> f(r, e, all.copy(fog = m), reg) } },
                    pickMode = fogPicking,
                    onPick = { u, v ->
                        fogPicking = false
                        pickScope.launch {
                            val light = withContext(Dispatchers.Default) { sampleScene(u, v) }
                            // keeps warmth and amount: only the colour changes
                            if (light != null) onFogLook(fogLook.copy(mode = "picked", picked = light.toList()))
                        }
                    },
                    tabs = listOf("COLOUR" to @Composable { FogColourRow(fogLook, picking = fogPicking, onLook = onFogLook, onPick = { fogPicking = !fogPicking }) }),
                    lookState = fogLook, onRestoreLook = { onFogLook(it as FogLook) }, pickOnTab = 0,
                    // a new colour or amount re-develops the print — it used to wait for the next stroke
                    refreshKey = fogLook)
                else -> PaintStep(RAYS_SPEC, recipe, raysMap, onRaysMap,
                    renderWith = { r, e, m -> renderWithMasks(r, e, all.copy(rays = m)) },
                    // where rays may fall: unpainted is everywhere, so the first stroke starts from full
                    startMap = { a -> ExposureMap.blank(a).also { it.stops.fill(1f) } },
                    modifier = Modifier.fillMaxSize(),
                    renderRegion = renderRegionWithMasks?.let { f -> { r, e, m, reg -> f(r, e, all.copy(rays = m), reg) } },
                    pickMode = raysPlacing || !sel.placed,
                    onPick = { u, v ->
                        raysPlacing = false
                        // placed for the first time: start as a lamp — on surfaces, no old-style glow in the air
                        val first = !sel.placed
                        val placed = sel.copy(u = u, v = v, u2 = Float.NaN, v2 = Float.NaN).let {
                            if (first) it.copy(amount = 0f, surface = maxOf(it.surface, 1f), reach = 0.3f, reveal = maxOf(it.reveal, 0.3f)) else it
                        }
                        setSel(placed.withDefaults())
                    },
                    pickOnTab = 0,
                    // one history for the brush and every light; compare shows the print with all lights off
                    lookState = lights,
                    onRestoreLook = { st ->
                        @Suppress("UNCHECKED_CAST") val ls = st as List<RaysLook>
                        onRaysLook(ls.first()); onExtraLights(ls.drop(1))
                        selected = selected.coerceIn(0, ls.size - 1)
                    },
                    renderBefore = { r, e -> renderWithMasks(r, e, all.copy(raysOn = false)) },
                    // while a handle is dragged only the outline moves; the print re-develops on release
                    handlesFor = { a -> raysHandles(raysLive, a) },
                    onHandleDrag = { i, u, v, final, a -> raysLive = raysDragged(raysLive, i, u, v, a); if (final) setSel(raysLive) },
                    overlayDraw = { a, toScreen ->
                        // the other lights: a small ring each, so you can see where they all are
                        lights.forEachIndexed { i, l ->
                            if (i != selected && l.placed) {
                                val c = toScreen(l.u, l.v)
                                drawCircle(LatentColors.Amber.copy(alpha = 0.55f), 7.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                                drawCircle(LatentColors.Amber.copy(alpha = 0.55f), 2.dp.toPx(), c)
                            }
                        }
                        drawRaysLight(raysLive, a, toScreen)
                    },
                    tabs = listOf(
                        "LIGHT" to @Composable {
                            RaysLightTab(sel, placing = raysPlacing || !sel.placed, onLook = { setSel(it) }, onPlace = { raysPlacing = !raysPlacing },
                                count = lights.size, selected = selected.coerceIn(0, lights.size - 1),
                                onSelect = { i -> selected = i; raysPlacing = false },
                                onAdd = {
                                    // a new lamp: lights surfaces, no beams to start; tap the photo to place it
                                    onExtraLights(extraLights + RaysLook(type = "point", amount = 0f, surface = 1f, reach = 0.3f, reveal = 0.3f))
                                    selected = extraLights.size + 1; raysPlacing = true
                                },
                                onRemove = {
                                    if (selected >= 1) onExtraLights(extraLights.filterIndexed { i, _ -> i != selected - 1 })
                                    else if (extraLights.isNotEmpty()) { onRaysLook(extraLights.first()); onExtraLights(extraLights.drop(1)) }
                                    else onRaysLook(RaysLook())                       // light 1 alone: cleared, ready to place again
                                    selected = 0; raysPlacing = false
                                })
                        },
                        "SURFACE" to @Composable {
                            SurfaceTab(sel, sceneScale = raysLook.scale, onLook = { setSel(it) },
                                onScene = { sc -> onRaysLook(raysLook.copy(scale = sc)) },
                                onDepthReady = { depthTick++ },
                                sceneBounce = raysLook.bounce, onBounce = { b -> onRaysLook(raysLook.copy(bounce = b)) })
                        },
                        "BEAM" to @Composable { RaysBeamTab(sel, onLook = { setSel(it) }) },
                    ),
                    // any light changing re-develops the print — and the depth model arriving
                    refreshKey = lights to depthTick)
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

// ---------------------------------------------------------------------------------------------
// Step three: dodge and burn.
// ---------------------------------------------------------------------------------------------

/** Dodge & burn prints are viewed large, so they are developed at the darkroom's fine size. */
private const val DB_EDGE = 640
/** Brush radius, as a fraction of the picture's long edge: small, medium, large. */
private val BRUSH = floatArrayOf(0.045f, 0.085f, 0.15f)
/** Stops added by one full pass at the centre of the brush: gentle, medium, strong. */
private val STRENGTH = floatArrayOf(0.25f, 0.5f, 1.0f)
private val BURN_TINT = Color(0xFF3A1A0C)   // where light was added: warm and dark
private val DODGE_TINT = Color(0xFFF4ECE0)  // where it was held back: pale

/**
 * What one painting step paints, and how it reads: dodge & burn paints light, soften paints
 * diffusion. The painting itself — brush, overlay, re-develop on lift, undo — is shared.
 *
 * min/max: the range a place can hold (stops of light; or 0..1 of diffusion).
 * overlayFull: the value at which the guide overlay is at its strongest.
 */
private class PaintSpec(
    val hint: String,
    val minusLabel: String,
    val plusLabel: String,
    val min: Float,
    val max: Float,
    val overlayFull: Float,
    val tintPlus: Color,
    val tintMinus: Color,
    val status: (ExposureMap?, Recipe) -> String,
    /** Scales how much one pass adds (1 = the usual gentle/medium/strong). */
    val passScale: Float = 1f,
    /**
     * A gradient tool: drag from near to far and the value builds smoothly along the line, from
     * nothing where the finger lands to [gradientAmount] (gentle/medium/strong) at the far end and
     * beyond. Null = no gradient tool.
     */
    val gradientAmount: FloatArray? = null,
    val gradientHint: String = "",
)

private val DODGE_BURN_SPEC = PaintSpec(
    hint = "Paint to burn (darker) or dodge (lighter). Lift your finger to see it develop.",
    minusLabel = "Dodge", plusLabel = "Burn",
    min = -ExposureMap.LIMIT, max = ExposureMap.LIMIT, overlayFull = ExposureMap.LIMIT,
    tintPlus = BURN_TINT, tintMinus = DODGE_TINT,
    status = { m, _ ->
        if (m == null || m.isBlank) "untouched" else {
            val (most, least) = m.extremes()
            "+${String.format(Locale.US, "%.1f", most)} burned · −${String.format(Locale.US, "%.1f", least)} dodged"
        }
    },
)

/** A pale haze: where the diffusion filter's glow goes. */
private val SOFT_TINT = Color(0xFFDCE6F2)

/**
 * Soften: 0 is sharp, 1 the diffusion filter as set in FULL, 2 twice as much of a place's light
 * scattered — so already-soft areas can be softened further. (It once stopped at 1, which made
 * Soften do nothing on areas that started soft.)
 */
private val SOFTEN_SPEC = PaintSpec(
    hint = "Paint where the diffusion glows — up to twice the filter. Sharpen paints it away.",
    minusLabel = "Sharpen", plusLabel = "Soften",
    min = 0f, max = 2f, overlayFull = 2f,
    tintPlus = SOFT_TINT, tintMinus = SOFT_TINT,
    status = { m, r ->
        when {
            !r.diffusion -> "diffusion is off"
            m == null -> "softened everywhere, 1×"
            else -> {
                val area = Math.round(m.stops.count { it > 0.05f } * 100f / m.stops.size)
                val most = m.stops.maxOrNull() ?: 0f
                if (area <= 0) "sharp everywhere"
                else "$area% softened · up to ${String.format(Locale.US, "%.1f", most)}×"
            }
        }
    },
)

/** A pale gold haze for the rays' guide overlay. */
private val RAYS_TINT = Color(0xFFF2DFA8)

/** Rays: where they may fall — 1 fully, 0 not at all. Unpainted, they fall everywhere. */
private val RAYS_SPEC = PaintSpec(
    hint = "Place the light and aim it. Paint Clear where it should not fall — in the air or on surfaces.",
    minusLabel = "Clear", plusLabel = "Rays",
    min = 0f, max = 1f, overlayFull = 1f,
    tintPlus = RAYS_TINT, tintMinus = RAYS_TINT,
    status = { m, _ ->
        if (m == null) "rays fall everywhere" else {
            val area = Math.round(m.stops.count { it > 0.05f } * 100f / m.stops.size)
            if (area <= 0) "rays fall nowhere" else "rays fall on $area% of the picture"
        }
    },
)

/** One handle position, kept on the picture. */
private fun keep(u: Float, v: Float) = u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)

/**
 * The spot's two cone-edge handles: its aim turned by ±cone about the light. Worked in true
 * proportions (across scaled by the picture's shape), so the cone's angle is the real angle.
 */
private fun coneEdges(d: RaysLook, aspect: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
    val ax = (d.u2 - d.u) * aspect; val ay = d.v2 - d.v
    fun turn(t: Float): Pair<Float, Float> {
        val c = kotlin.math.cos(t); val sn = kotlin.math.sin(t)
        return keep(d.u + (ax * c - ay * sn) / aspect, d.v + (ax * sn + ay * c))
    }
    return turn(d.cone) to turn(-d.cone)
}

/** The light's handles, in picture coordinates. */
private fun raysHandles(l: RaysLook, aspect: Float): List<Pair<Float, Float>> {
    if (!l.placed) return emptyList()
    val d = l.withDefaults()
    return when (d.type) {
        "sun" -> listOf(d.u to d.v, d.u2 to d.v2)
        "spot" -> { val (e1, e2) = coneEdges(d, aspect); listOf(d.u to d.v, d.u2 to d.v2, e1, e2) }
        // a panel: its middle, where it aims, and a corner for its size
        "area" -> listOf(d.u to d.v, d.u2 to d.v2, (d.u + d.aw / 2f) to (d.v + d.ah / 2f))
        else -> listOf(d.u to d.v)
    }
}

/** The light after handle [i] is dragged to (u, v). Shapes that move whole keep their size. */
private fun raysDragged(l: RaysLook, i: Int, u: Float, v: Float, aspect: Float): RaysLook {
    val d = l.withDefaults()
    fun moved(du: Float, dv: Float): RaysLook {
        // move both points by the same amount, but no further than keeps both on the picture
        val mdu = du.coerceIn(-minOf(d.u, d.u2), 1f - maxOf(d.u, d.u2))
        val mdv = dv.coerceIn(-minOf(d.v, d.v2), 1f - maxOf(d.v, d.v2))
        return d.copy(u = d.u + mdu, v = d.v + mdv, u2 = d.u2 + mdu, v2 = d.v2 + mdv)
    }
    return when (d.type) {
        "sun" -> if (i == 0) moved(u - d.u, v - d.v) else d.copy(u2 = u, v2 = v)
        "spot" -> when (i) {
            0 -> moved(u - d.u, v - d.v)
            1 -> d.copy(u2 = u, v2 = v)
            else -> {
                // a cone edge: the cone is the angle between the finger and the aim, in true proportions
                val ax = (d.u2 - d.u) * aspect; val ay = d.v2 - d.v
                val fx = (u - d.u) * aspect; val fy = v - d.v
                val n = kotlin.math.sqrt((ax * ax + ay * ay) * (fx * fx + fy * fy))
                if (n < 1e-9f) d else d.copy(cone = kotlin.math.acos(((ax * fx + ay * fy) / n).coerceIn(-1f, 1f))
                    .coerceIn(Math.toRadians(5.0).toFloat(), Math.toRadians(80.0).toFloat()))
            }
        }
        "area" -> when (i) {
            0 -> d.copy(u = u, v = v)                                                  // moves; keeps its aim and size
            1 -> d.copy(u2 = u, v2 = v)                                                // turns to face this point
            else -> d.copy(aw = (2f * kotlin.math.abs(u - d.u)).coerceIn(0.02f, 1f), ah = (2f * kotlin.math.abs(v - d.v)).coerceIn(0.02f, 1f))
        }
        else -> d.copy(u = u, v = v)
    }
}

/** The light's shape on the print: a small sun, an arrow, a cone, or a line. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRaysLight(l: RaysLook, aspect: Float, at: (Float, Float) -> Offset) {
    if (!l.placed) return
    val d = l.withDefaults()
    val a = LatentColors.Amber; val w = 2.dp.toPx()
    val p = at(d.u, d.v)
    when (d.type) {
        "sun" -> {
            val q = at(d.u2, d.v2)
            drawLine(a, p, q, w)
            val ang = kotlin.math.atan2(q.y - p.y, q.x - p.x); val hl = 14.dp.toPx()
            for (side in listOf(-1f, 1f)) {
                val t = ang + PI.toFloat() + side * 0.45f
                drawLine(a, q, Offset(q.x + hl * kotlin.math.cos(t), q.y + hl * kotlin.math.sin(t)), w)
            }
        }
        "spot" -> {
            val (e1, e2) = coneEdges(d, aspect)
            val q = at(d.u2, d.v2)
            drawLine(a.copy(alpha = 0.5f), p, q, w)
            for (e in listOf(e1, e2)) {
                val pe = at(e.first, e.second)
                // the cone's edge, drawn on past its handle so the spread reads at a glance
                drawLine(a, p, Offset(p.x + (pe.x - p.x) * 1.6f, p.y + (pe.y - p.y) * 1.6f), w)
            }
        }
        "area" -> {
            // the panel, and a line to where it faces (it lights from its front only)
            val c1 = at(d.u - d.aw / 2f, d.v - d.ah / 2f); val c2 = at(d.u + d.aw / 2f, d.v - d.ah / 2f)
            val c3 = at(d.u + d.aw / 2f, d.v + d.ah / 2f); val c4 = at(d.u - d.aw / 2f, d.v + d.ah / 2f)
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(c1.x, c1.y); lineTo(c2.x, c2.y); lineTo(c3.x, c3.y); lineTo(c4.x, c4.y); close() }
            drawPath(path, a.copy(alpha = 0.18f))
            drawPath(path, a, style = Stroke(w))
            drawLine(a.copy(alpha = 0.6f), p, at(d.u2, d.v2), w)
        }
        else -> {
            for (k in 0 until 8) {
                val t = k * PI.toFloat() / 4f
                drawLine(a, Offset(p.x + 13.dp.toPx() * kotlin.math.cos(t), p.y + 13.dp.toPx() * kotlin.math.sin(t)),
                    Offset(p.x + 18.dp.toPx() * kotlin.math.cos(t), p.y + 18.dp.toPx() * kotlin.math.sin(t)), w)
            }
        }
    }
}

/** A labelled slider that settles on release (re-developing once, not at every step of a drag). */
@Composable
private fun SettleSlider(left: String, right: String, value: Float, range: ClosedFloatingPointRange<Float>, onSettle: (Float) -> Unit) {
    var live by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = LatentColors.TextDim, fontSize = 10.sp)
        androidx.compose.material3.Slider(
            value = live, onValueChange = { live = it }, onValueChangeFinished = { onSettle(live) }, valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber,
                activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Surface),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(right, color = LatentColors.TextDim, fontSize = 10.sp)
    }
}

/** The rays' LIGHT tab: its kind, its mode, where it is, and how bright. */
@Composable
private fun RaysLightTab(
    look: RaysLook, placing: Boolean, onLook: (RaysLook) -> Unit, onPlace: () -> Unit,
    count: Int = 1, selected: Int = 0, onSelect: (Int) -> Unit = {}, onAdd: () -> Unit = {}, onRemove: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp)) {
        // which light: a few lights per photo, chosen to enhance — not one on every lamp
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("light", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(end = 2.dp))
                for (i in 0 until count) Chip("${i + 1}", on = i == selected) { onSelect(i) }
                if (count < 1 + com.celestial.latent.develop.ExtraLights.MAX) Chip("+", on = false) { onAdd() }
            }
            if (selected >= 1 || look.placed) Chip("remove", on = false) { onRemove() }
        }
        // the kind of light, as in 3D software; switching keeps where it is
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("point", "sun", "spot", "area").forEach { t ->
                Chip(t, on = look.type == t) { onLook(look.copy(type = t, u2 = Float.NaN, v2 = Float.NaN).withDefaults()) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("add light", on = look.mode == "add") { onLook(look.copy(mode = "add")) }
                Chip("through gaps", on = look.mode == "gaps") { onLook(look.copy(mode = "gaps")) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (placing) "tap the photo where the light comes from" else when (look.type) {
                    "sun" -> "drag the arrow's tip to aim it, its tail to move it"
                    "spot" -> "drag the light, its aim, or the cone's edges"
                    "area" -> "drag the middle to move, the dot to aim, a corner to size"
                    else -> "drag the light to move it"
                },
                color = if (placing) LatentColors.Amber else LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (look.placed) Chip(if (placing) "cancel" else "re-place", on = placing) { onPlace() }
        }
        // how bright in the air (the beams), beside a swatch of the light's colour
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(999.dp)).background(Color(Fog.swatchOf(look.chroma()))))
            Box(Modifier.weight(1f).padding(start = 6.dp)) {
                SettleSlider("in the air", "${(look.amount * 100f).roundToInt()}%", look.amount, 0f..1f) { onLook(look.copy(amount = (it * 100f).roundToInt() / 100f)) }
            }
        }
        // the light's colour, for the whole light (it used to sit in BEAM)
        LightColourRows(look, onLook)
    }
}

/**
 * The SURFACE tab: the selected light on what it meets — how strongly, where it is in depth, how
 * far its pool spreads, how much it may reveal in darkness — and the scene's size, which every
 * light shares. Worked out from the photo's depth, so it needs the depth model.
 */
@Composable
private fun SurfaceTab(look: RaysLook, sceneScale: Float, onLook: (RaysLook) -> Unit, onScene: (Float) -> Unit, onDepthReady: () -> Unit = {},
                       sceneBounce: Float = 0.5f, onBounce: (Float) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp)) {
        val context = androidx.compose.ui.platform.LocalContext.current
        // The depth model is installed from right here: pick the saved file; it is checked
        // against its fingerprint before use, exactly as from Settings.
        var ready by remember { mutableStateOf(com.celestial.latent.develop.Depth.isReady(context)) }
        var status by remember { mutableStateOf("") }
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                status = "importing…"
                Thread {
                    com.celestial.latent.develop.Depth.importFrom(context, uri) { got, total -> status = "importing… ${got * 100 / total}%" }
                        .onSuccess { ready = true; status = ""; onDepthReady() }
                        .onFailure { t -> status = "not imported: ${t.message}"; android.util.Log.e("Latent", "depth import failed", t) }
                }.start()
            }
        }
        if (!ready) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Light on surfaces needs the depth model (about 50 MB, once).", color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Chip("import file", on = false) { picker.launch(arrayOf("*/*")) }
            }
        }
        if (status.isNotEmpty()) Text(status, color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        SettleSlider("on surfaces", "${(look.surface * 100f).roundToInt()}%", look.surface, 0f..2f) { onLook(look.copy(surface = (it * 100f).roundToInt() / 100f)) }
        if (look.type == "sun") {
            // the arrow aims across the picture; in depth the sun can be in front of the subject or behind it
            SettleSlider("in front", "behind", look.front, -1f..1f) { onLook(look.copy(front = (it * 20f).roundToInt() / 20f)) }
        } else {
            // a lamp sits at the depth where it was tapped; this tucks it nearer or farther
            SettleSlider("nearer", "farther", look.nudge, -1f..1f) { onLook(look.copy(nudge = (it * 20f).roundToInt() / 20f)) }
            SettleSlider("small pool", "wide pool", look.reach, 0f..1f) { onLook(look.copy(reach = (it * 20f).roundToInt() / 20f)) }
        }
        // how much the light may show where the photo recorded almost nothing
        SettleSlider("strict", "reveal", look.reveal, 0f..1f) { onLook(look.copy(reveal = (it * 20f).roundToInt() / 20f)) }
        // the scene's size, shared by every light: sets the depth's scale, and so how far shadows reach
        SettleSlider("scene: close-up", "wide", sceneScale, 0f..1f) { onScene((it * 20f).roundToInt() / 20f) }
        // light bouncing off what the lights hit, onto everything near: shared by every light
        SettleSlider("bounce", "${(sceneBounce * 100f).roundToInt()}%", sceneBounce, 0f..1f) { onBounce((it * 20f).roundToInt() / 20f) }
    }
}

/** The light's colour — the whole light's, on surfaces and in the air — moved word for word from BEAM. */
@Composable
private fun LightColourRows(look: RaysLook, onLook: (RaysLook) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // the light's colour: a colour temperature, and optionally a hue of its own
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SettleSlider("cool", "warm", look.warmth, -1f..1f) { onLook(look.copy(warmth = it)) } }
            Spacer(Modifier.width(6.dp))
            Chip("colour", on = look.coloured) { onLook(look.copy(coloured = !look.coloured)) }
        }
        if (look.coloured) {
            var hueLive by remember(look.hue) { mutableStateOf(look.hue) }
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(999.dp))) {
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient((0..12).map { k -> Color.hsv(k * 30f % 360f, 0.85f, 0.95f) }))
                }
                androidx.compose.material3.Slider(
                    value = hueLive, onValueChange = { hueLive = it }, onValueChangeFinished = { onLook(look.copy(hue = hueLive)) },
                    valueRange = 0f..360f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber,
                        activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettleSlider("pale", "vivid", look.tint, 0f..1f) { onLook(look.copy(tint = it)) }
        }
    }
}

/** The rays' BEAM tab: how far it reaches, its colour, its dust, and how much it needs fog. */
@Composable
private fun RaysBeamTab(look: RaysLook, onLook: (RaysLook) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp)) {
        SettleSlider("short", "long", look.length, 0.1f..1f) { onLook(look.copy(length = (it * 100f).roundToInt() / 100f)) }
        SettleSlider("smooth", "dusty", look.dust, 0f..1f) { onLook(look.copy(dust = it)) }
        // real beams only show in hazy air: lean them on the painted fog as much as you like
        SettleSlider("always", "only in fog", look.fogOnly, 0f..1f) { onLook(look.copy(fogOnly = it)) }
    }
}

/** A pale, cool haze for the fog's guide overlay. */
private val FOG_TINT = Color(0xFFE6EBF0)

/**
 * Fog: how thick the air is in front of each place — 0 clear, up to 3 (by then only 5% of the
 * place gets through the fog). Paint it thicker in the distance to give the picture depth.
 */
private val FOG_SPEC = PaintSpec(
    hint = "Paint fog thicker where things are further away. Clear paints it away.",
    minusLabel = "Clear", plusLabel = "Fog",
    min = 0f, max = 3f, overlayFull = 3f,
    tintPlus = FOG_TINT, tintMinus = FOG_TINT,
    // Haze shows most over dark places — a thickness of 0.25 already lifts a deep shadow by about
    // two stops — so fog builds up in half-size passes, fine enough to layer gradually.
    passScale = 0.5f,
    // a full gradient: 0.5, 1 or 2 thick at the far end — about 39%, 63% or 86% veiled
    gradientAmount = floatArrayOf(0.5f, 1f, 2f),
    gradientHint = "Drag from near to far — the fog thickens smoothly along the line.",
    status = { m, _ ->
        if (m == null || m.isBlank) "no fog" else {
            val area = Math.round(m.stops.count { it > 0.05f } * 100f / m.stops.size)
            val veil = Math.round((1f - kotlin.math.exp(-(m.stops.maxOrNull() ?: 0f))) * 100f)
            "$area% fogged · up to $veil% veiled"
        }
    },
)

/**
 * The fog's colour: Auto (the scene's own brightest light — the safe choice), a preset, your own
 * colour (a hue and how strongly tinted), or light taken from the photo. Cool–warm and amount
 * apply to all of them. Every colour stays pale, as real fog is.
 */
@Composable
private fun FogColourRow(look: FogLook, picking: Boolean, onLook: (FogLook) -> Unit, onPick: () -> Unit) {
    val sliderColours = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber,
        activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Surface)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp)) {
        // seven choices: wider than a phone, so they scroll; switching keeps warmth, amount, hue, tint
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("auto", on = look.mode == "auto") { onLook(look.copy(mode = "auto")) }
            Fog.PRESETS.forEach { (name, _) -> Chip(name, on = look.mode == name) { onLook(look.copy(mode = name)) } }
            Chip("colour", on = look.mode == "colour") { onLook(look.copy(mode = "colour")) }
            Chip(if (picking) "tap photo" else "from photo", on = picking || look.mode == "picked") { onPick() }
        }
        // your own colour: a hue strip, and how strongly tinted
        if (look.mode == "colour") {
            var hueLive by remember(look.hue) { mutableStateOf(look.hue) }
            var tintLive by remember(look.tint) { mutableStateOf(look.tint) }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(36.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(999.dp))) {
                    val stops = (0..12).map { k -> Color.hsv(k * 30f % 360f, 0.55f, 0.95f) }
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(stops))
                }
                androidx.compose.material3.Slider(
                    value = hueLive, onValueChange = { hueLive = it },
                    onValueChangeFinished = { onLook(look.copy(hue = hueLive)) }, valueRange = 0f..360f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber,
                        activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("neutral", color = LatentColors.TextDim, fontSize = 10.sp)
                androidx.compose.material3.Slider(
                    value = tintLive, onValueChange = { tintLive = it },
                    onValueChangeFinished = { onLook(look.copy(tint = tintLive)) }, valueRange = 0f..1f,
                    colors = sliderColours, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("tinted", color = LatentColors.TextDim, fontSize = 10.sp)
            }
        }
        // cool–warm, for every colour, beside a swatch of the fog's colour as it stands
        var warmthLive by remember(look.warmth) { mutableStateOf(look.warmth) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(999.dp)).background(Color(Fog.swatch(look.copy(warmth = warmthLive)))))
            Text("cool", color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
            androidx.compose.material3.Slider(
                value = warmthLive, onValueChange = { warmthLive = it },
                onValueChangeFinished = { onLook(look.copy(warmth = warmthLive)) }, valueRange = -1f..1f,
                colors = sliderColours, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("warm", color = LatentColors.TextDim, fontSize = 10.sp)
        }
        // how much of the painted fog is applied: paint the shape, then dial the whole of it
        var amountLive by remember(look.amount) { mutableStateOf(look.amount) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("amount", color = LatentColors.TextDim, fontSize = 10.sp)
            androidx.compose.material3.Slider(
                value = amountLive, onValueChange = { amountLive = it },
                onValueChangeFinished = { onLook(look.copy(amount = (amountLive * 100f).roundToInt() / 100f)) },
                valueRange = 0f..1f, colors = sliderColours, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("${(amountLive * 100f).roundToInt()}%", color = LatentColors.Text, fontSize = 10.sp)
        }
    }
}

/**
 * A painting step: dodge & burn (where the enlarger light falls) or soften (where the diffusion
 * filter glows). While you paint, a soft overlay shows where; when you lift your finger, the print
 * re-develops through the engine with the real effect and the overlay fades away.
 *
 * Each mask belongs to this photo alone (see ExposureMaps) and applies wherever the photo is
 * previewed or printed. Test strips and the ring-around are made without them, as in a darkroom,
 * where they come before the dodging.
 *
 * startMap: the mask to begin from on the first stroke, for a picture of this aspect.
 * onPaintPlus: called when a stroke adds (burns, or softens).
 */
@Composable
private fun PaintStep(
    spec: PaintSpec,
    recipe: Recipe,
    map: ExposureMap?,
    onMap: (ExposureMap?) -> Unit,
    renderWith: (Recipe, Int, ExposureMap?) -> Bitmap?,
    startMap: (Float) -> ExposureMap,
    modifier: Modifier = Modifier,
    onPaintPlus: () -> Unit = {},
    renderRegion: ((Recipe, Int, ExposureMap?, Region) -> Bitmap?)? = null,
    pickMode: Boolean = false,
    onPick: ((Float, Float) -> Unit)? = null,
    /** Tabs of controls before the brush (which is always the last tab); null = brush only, no tabs. */
    tabs: List<Pair<String, @Composable () -> Unit>>? = null,
    /** Anything besides painting that changes the print (the fog's colour and amount): a change re-develops. */
    refreshKey: Any? = null,
    /**
     * Handles on the print that can be dragged (the rays' light), in picture coordinates, given
     * the picture's shape (width ÷ height). A touch on a handle drags it instead of painting.
     */
    handlesFor: ((Float) -> List<Pair<Float, Float>>)? = null,
    /** A handle dragged to (u, v): [final] on release. The picture's shape comes last. */
    onHandleDrag: ((Int, Float, Float, Boolean, Float) -> Unit)? = null,
    /** Draws the light's shape over the print: given the picture's shape and a picture → screen mapping. */
    overlayDraw: (androidx.compose.ui.graphics.drawscope.DrawScope.(Float, (Float, Float) -> Offset) -> Unit)? = null,
    /**
     * The step's own settings besides the mask (the lights, the fog's colour). Kept in the same
     * history as the brush, so one undo covers everything in the step, on every tab.
     */
    lookState: Any? = null,
    onRestoreLook: ((Any?) -> Unit)? = null,
    /** Tapping the photo places or picks only on this tab (LIGHT, the fog's COLOUR); elsewhere it paints or does nothing. */
    pickOnTab: Int? = null,
    /** The print without this step's effect, for the compare button. Null: without this step's mask. */
    renderBefore: ((Recipe, Int) -> Bitmap?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(recipe)
    val renderNow by rememberUpdatedState(renderWith)
    val picking by rememberUpdatedState(pickMode)
    val pickNow by rememberUpdatedState(onPick)
    val handlesNow by rememberUpdatedState(handlesFor)
    val dragNow by rememberUpdatedState(onHandleDrag)
    // Zoom: two fingers; the brush keeps its size on screen; the zoomed part develops sharp.
    val zoom = remember { ZoomState() }
    var detail by remember { mutableStateOf<Pair<Region, Bitmap>?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var sharpening by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(map?.copy()) }
    var print by remember { mutableStateOf<Bitmap?>(null) }
    var developing by remember { mutableStateOf(true) }
    var version by remember { mutableStateOf(0) }
    var plus by remember { mutableStateOf(true) }
    // the gradient tool, and the line being dragged (picture coordinates: from, to)
    var gradientTool by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<Pair<Pair<Float, Float>, Pair<Float, Float>>?>(null) }
    var brush by remember { mutableStateOf(1) }
    var strength by remember { mutableStateOf(1) }
    var showMask by remember { mutableStateOf(false) }
    var stroke by remember { mutableStateOf<FloatArray?>(null) }
    var strokeTick by remember { mutableStateOf(0) }
    val overlay = remember { Animatable(0f) }
    // one history for the mask and the step's own settings: (mask, settings) before each change
    val undo = remember { ArrayDeque<Pair<ExposureMap?, Any?>>() }
    val redo = remember { ArrayDeque<Pair<ExposureMap?, Any?>>() }
    var historyTick by remember { mutableStateOf(0) }
    var lastLook by remember { mutableStateOf(lookState) }
    // a change to the step's settings from its controls is remembered like a brush stroke
    LaunchedEffect(lookState) {
        if (lookState != lastLook) {
            undo.addLast(working?.copy() to lastLook); if (undo.size > 30) undo.removeFirst()
            redo.clear(); historyTick++; lastLook = lookState
        }
    }

    // Develop the print with the mask, a moment after the last stroke (so quick strokes coalesce).
    LaunchedEffect(version, refreshKey) {
        if (version > 0) delay(180)
        developing = true
        val m = working
        val bmp = withContext(Dispatchers.Default) { renderNow(current, DB_EDGE, m) }
        if (!isActive) return@LaunchedEffect
        if (bmp != null) print = bmp
        developing = false
        // the real print now shows the effect: the guide overlay steps back
        overlay.animateTo(if (showMask) 0.55f else 0f, tween(520, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(showMask) { if (!developing) overlay.animateTo(if (showMask) 0.55f else 0f, tween(260)) }

    val aspect = print?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
    // The zoomed-in part, developed sharp once the zoom settles or the print changes.
    LaunchedEffect(print) { detail = null }
    LaunchedEffect(zoom.scale, zoom.focusU, zoom.focusV, print) {
        val ask = renderRegion ?: return@LaunchedEffect
        if (!zoom.zoomed || print == null || canvasSize.width == 0) return@LaunchedEffect
        delay(320)
        val vw = canvasSize.width.toFloat(); val vh = canvasSize.height.toFloat()
        val pr = printRect(vw, vh, aspect)
        val region = zoom.visible(FitRect(pr.left, pr.top, pr.width, pr.height), vw, vh)
        if (detail?.first == region) return@LaunchedEffect
        val edge = maxOf(canvasSize.width, canvasSize.height).coerceAtMost(1600)
        val m = working
        sharpening = true
        try {
            val bmp = withContext(Dispatchers.Default) { ask(current, edge, m, region) }
            if (bmp != null && isActive) detail = region to bmp
        } finally { sharpening = false }
    }

    /** Commit a change to the mask: remember the old one for undo, show and save the new. */
    fun commit(next: ExposureMap?) {
        undo.addLast(working?.copy() to lastLook); if (undo.size > 30) undo.removeFirst()
        redo.clear(); historyTick++
        working = next; onMap(next); version++
    }

    /** Back one change — a stroke or a setting — restoring the mask and the step's settings together. */
    fun undoStep() {
        if (undo.isEmpty()) return
        redo.addLast(working?.copy() to lastLook); val (m, l) = undo.removeLast(); historyTick++
        working = m; onMap(m); version++
        if (l != lastLook) { lastLook = l; onRestoreLook?.invoke(l) }
    }
    fun redoStep() {
        if (redo.isEmpty()) return
        undo.addLast(working?.copy() to lastLook); val (m, l) = redo.removeLast(); historyTick++
        working = m; onMap(m); version++
        if (l != lastLook) { lastLook = l; onRestoreLook?.invoke(l) }
    }

    // compare: the print without this step's effect, prepared once the print is ready (and again if the recipe changes)
    var comparing by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf<Bitmap?>(null) }
    var beforeFor by remember { mutableStateOf<Recipe?>(null) }
    val beforeNow by rememberUpdatedState(renderBefore ?: { r: Recipe, e: Int -> renderWith(r, e, null) })
    LaunchedEffect(developing, recipe) {
        if (developing || (before != null && beforeFor == recipe)) return@LaunchedEffect
        val r = current
        val b = withContext(Dispatchers.Default) { beforeNow(r, DB_EDGE) }
        if (isActive && b != null) { before = b; beforeFor = r }
    }

    /** Stamp the brush into the current stroke at a picture position (0..1 across and down). */
    /** How much a whole stroke adds where it is at full strength: a gradient's own amounts, or a brush pass. */
    fun strokeAmount(): Float {
        val g = spec.gradientAmount
        return if (gradientTool && g != null) g[strength] else STRENGTH[strength] * spec.passScale
    }

    /**
     * The gradient from (u0, v0) to (u1, v1): nothing behind the start, building smoothly to full
     * at the end and full beyond it. Distances in units of the long edge, so a diagonal drag
     * builds evenly on any shape of picture.
     */
    fun fillGradient(u0: Float, v0: Float, u1: Float, v1: Float) {
        val m = working ?: startMap(aspect).also { working = it }
        val st = stroke ?: FloatArray(m.width * m.height).also { stroke = it }
        val aw = if (aspect >= 1f) 1f else aspect
        val ah = if (aspect >= 1f) 1f / aspect else 1f
        val dx = (u1 - u0) * aw; val dy = (v1 - v0) * ah
        val len2 = dx * dx + dy * dy
        for (j in 0 until m.height) for (i in 0 until m.width) {
            val px = ((i + 0.5f) / m.width - u0) * aw; val py = ((j + 0.5f) / m.height - v0) * ah
            val t = if (len2 < 1e-8f) 0f else ((px * dx + py * dy) / len2).coerceIn(0f, 1f)
            st[j * m.width + i] = t * t * (3f - 2f * t)          // smooth at both ends
        }
        strokeTick++
    }

    fun stamp(u: Float, v: Float) {
        val m = working ?: startMap(aspect).also { working = it }
        val st = stroke ?: FloatArray(m.width * m.height).also { stroke = it }
        val r = BRUSH[brush] / zoom.scale      // the same size on screen at any zoom
        // distances in units of the long edge, so the brush is round on any shape of picture
        val aw = if (aspect >= 1f) 1f else aspect
        val ah = if (aspect >= 1f) 1f / aspect else 1f
        val i0 = ((u - r / aw) * m.width).toInt().coerceAtLeast(0); val i1 = ((u + r / aw) * m.width).toInt().coerceAtMost(m.width - 1)
        val j0 = ((v - r / ah) * m.height).toInt().coerceAtLeast(0); val j1 = ((v + r / ah) * m.height).toInt().coerceAtMost(m.height - 1)
        for (j in j0..j1) for (i in i0..i1) {
            val dx = ((i + 0.5f) / m.width - u) * aw; val dy = ((j + 0.5f) / m.height - v) * ah
            val d = kotlin.math.sqrt(dx * dx + dy * dy) / r
            if (d >= 1f) continue
            val t = 1f - d; val fall = t * t * (3f - 2f * t)          // soft edge, like a card's shadow
            val k = j * m.width + i
            if (fall > st[k]) st[k] = fall
        }
        strokeTick++
    }

    /** The finger lifted: add the stroke to the mask. */
    fun endStroke() {
        val st = stroke ?: return
        stroke = null
        val base = working ?: return
        val sign = if (plus) 1f else -1f
        val next = base.copy()
        for (k in next.stops.indices) {
            if (st[k] > 0f) next.stops[k] = (next.stops[k] + sign * strokeAmount() * st[k]).coerceIn(spec.min, spec.max)
        }
        if (plus) onPaintPlus()
        // `working` still holds the mask as it was before the stroke (the stroke lived apart), so
        // commit records exactly that for undo
        commit(next)
    }

    /** The overlay image: the mask (plus the stroke being painted), tinted, at the mask's size. */
    val overlayBitmap = remember(working, strokeTick) {
        val m = working ?: return@remember null
        val st = stroke
        val sign = if (plus) 1f else -1f
        val px = IntArray(m.width * m.height)
        for (k in px.indices) {
            val sNow = (m.stops[k] + (if (st != null) sign * strokeAmount() * st[k] else 0f)).coerceIn(spec.min, spec.max)
            val a = (kotlin.math.abs(sNow) / spec.overlayFull).coerceIn(0f, 1f)
            val tint = if (sNow >= 0f) spec.tintPlus else spec.tintMinus
            val alpha = (a * 200).toInt().coerceIn(0, 255)
            px[k] = (alpha shl 24) or ((tint.red * 255).toInt() shl 16) or ((tint.green * 255).toInt() shl 8) or (tint.blue * 255).toInt()
        }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    // The step's own tab, when it has tabs; painting only happens on BRUSH, so a touch in another
    // tab (placing or aiming a light) never paints by accident.
    var tab by remember { mutableStateOf(0) }
    val tabNow by rememberUpdatedState(tab)
    val pickTab by rememberUpdatedState(pickOnTab)
    val paintNow by rememberUpdatedState(tabs == null || tab == tabs.size)
    /** The brush controls: what you paint with, how strongly, and the way back. */
    val brushRows: @Composable () -> Unit = {
        // Three rows, each measured to fit a phone (about 357 dp inside the margins): one row
        // would be ~410 dp and squeeze its last chips — the bug that once crushed the Develop button.
        // 1. what you paint with, and how big
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(false to spec.minusLabel, true to spec.plusLabel).forEach { (b, label) -> Chip(label, on = plus == b) { plus = b } }
                if (spec.gradientAmount != null) Chip("gradient", on = gradientTool) { gradientTool = !gradientTool }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..2) {
                    val on = brush == i
                    val d = (10 + i * 6).dp
                    Box(Modifier.size(28.dp).pointerInput(i) { detectTapGestures(onTap = { Haptics.tick(context); brush = i }) },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(d).clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface))
                    }
                }
            }
        }
        // 2. how strongly, and whether to see the mask
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("gentle", "medium", "strong").forEachIndexed { i, label -> Chip(label, on = strength == i) { strength = i } }
            }
            Chip("mask", on = showMask) { showMask = !showMask }
        }
        // 3. where the mask stands, and the way back
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (developing) "developing…" else spec.status(working, recipe),
                color = LatentColors.Text, fontSize = 12.sp,
                // takes what the buttons leave, and wraps if it must — the buttons are never squeezed
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                @Suppress("UNUSED_VARIABLE") val h = historyTick   // read, so the buttons follow the history
                Chip("undo", on = false, enabled = undo.isNotEmpty()) { undoStep() }
                Chip("redo", on = false, enabled = redo.isNotEmpty()) { redoStep() }
                Chip("clear", on = false, enabled = working?.isBlank == false) { commit(null) }
            }
        }
    }
    Column(modifier) {
        Text(
            if (gradientTool) spec.gradientHint else spec.hint,
            color = LatentColors.TextDim, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = 10.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Canvas(
                Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.pointerInput(aspect, print != null) {
                    if (print == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val vw = size.width.toFloat(); val vh = size.height.toFloat()
                        val pr = printRect(vw, vh, aspect)
                        val fit = FitRect(pr.left, pr.top, pr.width, pr.height)
                        fun at(o: Offset) = zoom.toPicture(o.x, o.y, fit, vw, vh)
                        val (u0, v0) = at(down.position)
                        // eyedropper: this tap picks a colour from the picture instead of painting
                        val pick = pickNow
                        // only on its own tab (a brush stroke on BRUSH must never place a light)
                        if (picking && pick != null && (tabs == null || pickTab == null || tabNow == pickTab)) {
                            if (u0 in 0f..1f && v0 in 0f..1f) { Haptics.tick(context); pick(u0, v0) }
                            down.consume()
                            return@awaitEachGesture
                        }
                        // a touch on a handle drags the handle — painting never starts there
                        val hs = handlesNow?.invoke(aspect).orEmpty()
                        val drag = dragNow
                        if (hs.isNotEmpty() && drag != null) {
                            val grab = 28.dp.toPx()
                            val hit = hs.indices.minByOrNull { i ->
                                val sp = zoom.toScreen(hs[i].first, hs[i].second, fit, vw, vh)
                                (sp - down.position).getDistance()
                            }?.takeIf { i -> (zoom.toScreen(hs[i].first, hs[i].second, fit, vw, vh) - down.position).getDistance() <= grab }
                            if (hit != null) {
                                Haptics.tick(context)
                                down.consume()
                                var last = at(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty() || pressed.size >= 2) break
                                    last = at(pressed.first().position)
                                    drag(hit, last.first.coerceIn(0f, 1f), last.second.coerceIn(0f, 1f), false, aspect)
                                    event.changes.forEach { it.consume() }
                                }
                                drag(hit, last.first.coerceIn(0f, 1f), last.second.coerceIn(0f, 1f), true, aspect)
                                return@awaitEachGesture
                            }
                        }
                        var painting = paintNow && u0 in 0f..1f && v0 in 0f..1f
                        var zooming = false
                        var last = Pair(u0, v0)
                        val dragging = gradientTool && spec.gradientAmount != null
                        if (painting) {
                            Haptics.tick(context)
                            // the guide shows at full strength while painting (gesture code cannot
                            // animate itself, so the change is handed to a coroutine)
                            scope.launch { overlay.snapTo(1f) }
                            if (dragging) { fillGradient(u0, v0, u0, v0); line = Pair(u0, v0) to Pair(u0, v0) } else stamp(u0, v0)
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // a second finger: this is a zoom, and the dab the first finger
                                // began is not kept as a stray mark
                                if (!zooming) { zooming = true; if (painting) { painting = false; stroke = null; line = null; strokeTick++ } }
                                zoom.pinch(event.calculateCentroid(), event.calculatePan(), event.calculateZoom(), fit, vw, vh)
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (zooming || !painting) { event.changes.forEach { it.consume() }; continue }
                            val change = pressed.first()
                            val (u, v) = at(change.position)
                            if (dragging) {
                                // the gradient follows the finger: from where it landed to here
                                fillGradient(u0, v0, u, v); line = Pair(u0, v0) to Pair(u, v)
                                change.consume(); continue
                            }
                            // fill the gap between touch samples, so a quick stroke stays continuous
                            val du = u - last.first; val dv = v - last.second
                            val steps = maxOf(1, (kotlin.math.sqrt(du * du + dv * dv) / (BRUSH[brush] / zoom.scale / 3f)).toInt())
                            for (k in 1..steps) stamp(last.first + du * k / steps, last.second + dv * k / steps)
                            last = Pair(u, v)
                            change.consume()
                        }
                        if (painting) endStroke()
                        line = null
                    }
                },
            ) {
                val pr = printRect(size.width, size.height, aspect)
                val fit = FitRect(pr.left, pr.top, pr.width, pr.height)
                // the whole print's place on screen, through the zoom (at 1× it is the fitted place)
                val tl = zoom.toScreen(0f, 0f, fit, size.width, size.height)
                val br = zoom.toScreen(1f, 1f, fit, size.width, size.height)
                val r = PrintRect(tl.x, tl.y, br.x - tl.x, br.y - tl.y)
                val border = 6.dp.toPx()
                clipRect {
                    drawRect(Color.Black.copy(alpha = 0.45f), Offset(r.left - border + 4.dp.toPx(), r.top - border + 8.dp.toPx()), Size(r.width + 2 * border, r.height + 2 * border))
                    drawRect(PAPER, Offset(r.left - border, r.top - border), Size(r.width + 2 * border, r.height + 2 * border))
                    val shown = if (comparing && before != null) before else print
                    shown?.let { drawImage(it.asImageBitmap(), dstOffset = IntOffset(r.left.toInt(), r.top.toInt()), dstSize = IntSize(r.width.toInt(), r.height.toInt())) }
                    if (print == null) drawRect(PAPER, Offset(r.left, r.top), Size(r.width, r.height))
                    // the zoomed-in part, developed sharp, laid exactly over itself
                    val tile = detail
                    if (tile != null && zoom.zoomed) {
                        val a = zoom.toScreen(tile.first.u0, tile.first.v0, fit, size.width, size.height)
                        val c = zoom.toScreen(tile.first.u1, tile.first.v1, fit, size.width, size.height)
                        drawImage(tile.second.asImageBitmap(), dstOffset = IntOffset(a.x.toInt(), a.y.toInt()),
                            dstSize = IntSize((c.x - a.x).toInt(), (c.y - a.y).toInt()))
                    }
                    // the light's shape, and its handles to drag (hidden while comparing: the photo alone)
                    if (!comparing) overlayDraw?.invoke(this, aspect) { mu, mv -> zoom.toScreen(mu, mv, fit, size.width, size.height) }
                    if (!comparing) handlesFor?.invoke(aspect)?.forEach { (hu, hv) ->
                        val c = zoom.toScreen(hu, hv, fit, size.width, size.height)
                        drawCircle(Color(0xCC161615), 10.dp.toPx(), c)
                        drawCircle(LatentColors.Amber, 10.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    }
                    line?.let { (a, b) ->
                        val pa = zoom.toScreen(a.first, a.second, fit, size.width, size.height)
                        val pb = zoom.toScreen(b.first, b.second, fit, size.width, size.height)
                        drawLine(LatentColors.Amber, pa, pb, 2.dp.toPx())
                        drawCircle(LatentColors.Amber, 6.dp.toPx(), pa, style = Stroke(2.dp.toPx()))
                        drawCircle(LatentColors.Amber, 6.dp.toPx(), pb)
                    }
                    val ov = overlay.value
                    val ob = overlayBitmap
                    if (ob != null && ov > 0.001f) drawImage(ob.asImageBitmap(), dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                        dstSize = IntSize(r.width.toInt(), r.height.toInt()), alpha = ov)
                }
            }
            // Compare: hold to see the print without this step's effect — the lights off, the fog
            // gone, the dodging undone — and let go to come back. A label, never a hidden gesture:
            // on the photo itself a touch already places, drags or paints.
            Text(
                when { comparing && before == null -> "developing…"; comparing -> "BEFORE"; else -> "hold: before" },
                color = if (comparing) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (comparing) LatentColors.Amber else Color(0xCC161615))
                    .pointerInput(Unit) { detectTapGestures(onPress = { comparing = true; tryAwaitRelease(); comparing = false }) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            if (zoom.zoomed) Text("${String.format(Locale.US, "%.1f", zoom.scale)}×  · " + (if (sharpening) "sharpening… · fit" else "fit"), color = LatentColors.AmberInk, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                    .pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tick(context); zoom.reset(); detail = null }) }
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (tabs == null) brushRows() else {
            // Tabs: one group of controls at a time, in a space of fixed height, so the photo
            // keeps its size whichever tab is open (a long tab scrolls within the space).
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                (tabs.map { it.first } + "BRUSH").forEachIndexed { i, name ->
                    val on = i == tab
                    val bar by animateFloatAsState(if (on) 1f else 0f, tween(220), label = "tab")
                    Column(Modifier.pointerInput(i) { detectTapGestures(onTap = { if (i != tab) Haptics.tick(context); tab = i }) }) {
                        Text(name, color = if (on) LatentColors.Amber else LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 1.5.sp)
                        Box(Modifier.padding(top = 3.dp).height(2.dp).width((28 * bar).dp).clip(RoundedCornerShape(1.dp)).background(LatentColors.Amber))
                    }
                }
            }
            // undo and redo on every tab: one history for the brush and the step's settings
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                key(historyTick) {
                    Text("undo", color = if (undo.isNotEmpty()) LatentColors.Amber else LatentColors.TextDim, fontSize = 11.sp,
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { undoStep() }) })
                    Text("redo", color = if (redo.isNotEmpty()) LatentColors.Amber else LatentColors.TextDim, fontSize = 11.sp,
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { redoStep() }) })
                }
            }
            }
            Box(Modifier.fillMaxWidth().height(CONTROLS_HEIGHT)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (tab < tabs.size) tabs[tab].second() else brushRows()
                }
            }
        }
    }
}

/** A small rounded choice, amber when on. */
@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean = true, onTap: () -> Unit) {
    val context = LocalContext.current
    Text(
        label, fontSize = 11.sp,
        color = when { on -> LatentColors.AmberInk; enabled -> LatentColors.Text; else -> LatentColors.Line },
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
            .pointerInput(label, on, enabled) { detectTapGestures(onTap = { if (enabled) { Haptics.tick(context); onTap() } }) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
