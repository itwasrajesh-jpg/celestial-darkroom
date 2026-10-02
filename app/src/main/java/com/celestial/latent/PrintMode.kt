package com.celestial.latent

import android.graphics.Bitmap
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
import com.celestial.latent.develop.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** The print exposure the engine accepts; the same limits as the darkroom's sliders. */
private const val MIN_EXPOSURE = 0.4f
private const val MAX_EXPOSURE = 2.2f
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
 * PRINT mode: the darkroom done the way a printer works — by trying and choosing, not by sliders.
 *
 * Step one is the test strip: the photo cut into five strips, each printed a third of a stop
 * longer than the last. Each comes up out of the paper as it is developed. Tap the strip that
 * looks right and that becomes the print exposure — the same value the FULL sliders show. Hold a
 * strip to see the whole print at that time.
 *
 * @param renderAt develops the preview at a given print exposure. It blocks and queues for the
 *   engine itself, so it is only ever called off the main thread.
 */
@Composable
fun PrintPanel(
    recipe: Recipe,
    renderAt: (Float) -> Bitmap?,
    onExposure: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
            val bmp = withContext(Dispatchers.Default) { renderAt(exposures[i]) }
            if (!isActive) return@LaunchedEffect
            images[i] = bmp
            developed = i + 1
            launch { paperCover[i].animateTo(0f, tween(900, easing = FastOutSlowInEasing)) }
        }
    }

    val outline by animateFloatAsState(if (images[selected] != null) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "outline")
    val holdAlpha by animateFloatAsState(if (holding != null) 1f else 0f, tween(220), label = "hold")

    Column(modifier.background(LatentColors.Background)) {
        StepIndicator(active = 0, modifier = Modifier.padding(horizontal = 18.dp).padding(top = 6.dp))
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

/** The three steps of printing; later ones stay dim until they exist. */
@Composable
private fun StepIndicator(active: Int, modifier: Modifier = Modifier) {
    val steps = listOf("TEST STRIP", "COLOUR", "DODGE & BURN")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        steps.forEachIndexed { i, label ->
            val on = i == active
            val bar by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "step")
            Column {
                Text("${i + 1}  $label", color = if (on) LatentColors.Amber else LatentColors.Line, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Box(Modifier.padding(top = 4.dp).height(2.dp).width((40 * bar).dp).clip(RoundedCornerShape(1.dp)).background(LatentColors.Amber))
            }
        }
    }
}
