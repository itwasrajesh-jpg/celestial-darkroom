package com.celestial.latent

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 102b: the saving motion, as in the approved proofs (visual-5/5-7-saving.mp4): a new photo drops
 * into the roll like a print onto a table, a finished full-size print rises out of a slot like a
 * print from a printer, and a saved recipe gets a stamp pressed onto its card. All of it stands
 * still when the phone's animations are off.
 */

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun smoothstep(t: Float) = t * t * (3 - 2 * t)
private fun easeOut(t: Float) = 1 - (1 - t).pow(3)
private fun easeIn(t: Float) = t * t * t

/**
 * Which photos are new to the roll. The first reading in the app's life only learns what is there
 * (a roll of hundreds must not rain down); after that a photo the roll has not shown before, or one
 * that has just been developed, lands.
 */
internal object RollLanding {
    private val seen = HashSet<String>()
    private val developed = HashSet<String>()
    private var primed = false

    /** [frames]: each photo's key and whether it is developed. Returns the keys that should land. */
    @Synchronized fun arrivals(frames: List<Pair<String, Boolean>>): Set<String> {
        val out = HashSet<String>()
        for ((k, dev) in frames) {
            if (primed && (k !in seen || (dev && k !in developed))) out += k
            seen += k; if (dev) developed += k
        }
        primed = true
        return out
    }
}

/** How long a print takes to land, and the gap between prints when several arrive at once. */
private const val LAND_MS = 900
private const val LAND_STAGGER_MS = 90

/**
 * A roll tile that may be landing: it falls in from above, turned a little and held larger as if
 * nearer the eye, settles with a small bounce, its shadow tightening as it meets the table. [order]
 * staggers several prints arriving together; [onLanded] once it is down.
 */
@Composable
internal fun Modifier.landing(arriving: Boolean, order: Int, onLanded: () -> Unit): Modifier {
    val p = remember { Animatable(if (arriving && motionOn()) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) { delay((order.coerceAtMost(6) * LAND_STAGGER_MS).toLong()); p.animateTo(1f, tween(LAND_MS, easing = LinearEasing)) }
        if (arriving) onLanded()     // once down, it stays down when scrolled away and back
    }
    val v = p.value
    if (v >= 1f) return this
    return this.graphicsLayer {
        val fall = 0.6f
        if (v < fall) {
            val q = v / fall
            translationY = -(1f - easeIn(q).pow(0.8f)) * (size.height * 1.6f)
            val s = 1.35f - 0.35f * easeOut(q); scaleX = s; scaleY = s
            rotationZ = 9f * (1f - easeOut(q))
            alpha = (q * 3f).coerceAtMost(1f)
            shadowElevation = (22f - 12f * q) * density
        } else {
            val q = (v - fall) / (1f - fall)
            val s = 1f - 0.035f * sin(PI.toFloat() * q) * (1f - 0.3f * q); scaleX = s; scaleY = s
            shadowElevation = 10f * (1f - q) * density
        }
    }
}

/**
 * The full-size print leaving the darkroom: a slot opens across the bottom of the photo, the print
 * (on its white paper) rises out of it, lifts free with a small turn, then settles onto the photo,
 * its paper falling away so it becomes the picture; "saved" shows beneath it.
 */
internal class PrintOut {
    val t = Animatable(1f)
    var print by mutableStateOf<ImageBitmap?>(null); private set
    var note by mutableStateOf(""); private set

    /** Plays for [bitmap]; false when the phone's animations are off (nothing to show). */
    fun play(scope: CoroutineScope, bitmap: Bitmap, saved: String): Boolean {
        if (!motionOn()) return false
        print = bitmap.asImageBitmap(); note = saved
        scope.launch { t.snapTo(0f); t.animateTo(1f, tween(PRINT_OUT_MS, easing = LinearEasing)); print = null }
        return true
    }
    val playing get() = print != null && t.value < 1f
}

private const val PRINT_OUT_MS = 4600

/** The paper's white, the same as the print that develops (87c). */
private val PAPER = Color(0xFFF4F1EA)

@Composable
internal fun PrintOutLayer(state: PrintOut, modifier: Modifier = Modifier) {
    val img = state.print ?: return
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val u = state.t.value * PRINT_OUT_MS / 1000f
        val fit = fitRect(size.width, size.height, img.width.toFloat() / img.height)
        val slotY = minOf(fit.top + fit.height + 6.dp.toPx(), size.height - 4.dp.toPx())
        val open = smoothstep(seg(u, 0f, 0.3f)) * (1 - smoothstep(seg(u, 2.3f, 2.7f)))
        // the print: 72% of the photo's width on a paper border, rising, lifting, then settling into the photo
        val pw = fit.width * 0.72f; val border = pw * 0.031f
        val ph = pw / fit.width * fit.height + 2 * border
        val rise = easeOut(seg(u, 0.2f, 1.4f))
        val lift = smoothstep(seg(u, 1.4f, 1.8f)); val settle = smoothstep(seg(u, 2.0f, 2.6f))
        val risenCy = slotY - ph * rise + ph / 2f - 10.dp.toPx() * rise
        val homeCy = fit.top + fit.height / 2f
        val cx = size.width / 2f
        val cy = risenCy - 14.dp.toPx() * lift * (1 - settle) + (homeCy - risenCy) * settle
        val grow = 1f + (fit.width / pw - 1f) * settle
        val turn = -2.5f * lift * (1 - settle)
        val paper = 1f - settle
        val shadowA = 0.45f * (0.4f + 0.6f * lift) * (1 - settle)
        fun drawPrint() {
            translate(cx, cy) {
                rotate(turn, Offset.Zero) {
                    scale(grow, grow, Offset.Zero) {
                        if (shadowA > 0f) drawRoundRect(Color.Black.copy(alpha = shadowA), Offset(-pw / 2f - border, -ph / 2f + (6 + 14 * lift).dp.toPx() / grow),
                            Size(pw + 2 * border, ph), CornerRadius(3.dp.toPx()))
                        drawRoundRect(PAPER.copy(alpha = paper), Offset(-pw / 2f, -ph / 2f), Size(pw, ph), CornerRadius(3.dp.toPx() * paper))
                        val inner = pw - 2 * border * paper; val innerH = inner / img.width * img.height
                        drawImage(img, dstOffset = IntOffset((-inner / 2f).roundToInt(), (-innerH / 2f).roundToInt()),
                            dstSize = IntSize(inner.roundToInt(), innerH.roundToInt()))
                    }
                }
            }
        }
        // still in the "printer" below the slot until it lifts free
        if (lift < 1f) clipRect(bottom = slotY) { drawPrint() }
        if (lift > 0f) {
            if (lift < 1f) clipRect(top = slotY) { drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, slotY, size.width, size.height),
                androidx.compose.ui.graphics.Paint().apply { alpha = lift }); drawPrint(); drawContext.canvas.restore() }
            else drawPrint()
        }
        if (open > 0f) {
            drawRoundRect(Color(0xFF05070D).copy(alpha = open), Offset(fit.left + fit.width * 0.055f, slotY - 4.dp.toPx()), Size(fit.width * 0.89f, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawRect(com.celestial.latent.ui.LatentColors.Line.copy(alpha = 0.8f * open), Offset(fit.left + fit.width * 0.055f, slotY + 4.dp.toPx()), Size(fit.width * 0.89f, 2.dp.toPx()))
        }
        // the note, once the print is down
        val na = smoothstep(seg(u, 2.4f, 2.7f)) * (1 - smoothstep(seg(u, 4.2f, 4.6f)))
        if (na > 0f && state.note.isNotEmpty()) {
            val text = measurer.measure(state.note, TextStyle(color = Color(0xFFF4F7FC).copy(alpha = na), fontSize = 11.sp))
            val w = text.size.width + 28.dp.toPx(); val h = text.size.height + 12.dp.toPx()
            val top = fit.top + fit.height - h - 10.dp.toPx()
            drawRoundRect(Color(0xFF111522).copy(alpha = 0.85f * na), Offset(cx - w / 2f, top), Size(w, h), CornerRadius(h / 2f))
            drawText(text, topLeft = Offset(cx - text.size.width / 2f, top + 6.dp.toPx()))
        }
    }
}

/** The stamp's ink: a red that reads on every theme's cards. */
private val STAMP_INK = Color(0xFFE06A4E)

/**
 * "SAVED · 30 OCT" pressed onto a recipe's card as it is saved: it comes down from above, lands with
 * a small press ring, then fades after a few seconds. [onDone] when it has gone.
 */
@Composable
internal fun RecipeStamp(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!motionOn()) { delay(2500); onDone(); return@LaunchedEffect }
        t.animateTo(1f, tween(4000, easing = LinearEasing)); onDone()
    }
    val measurer = rememberTextMeasurer()
    val day = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date()).uppercase() }
    Canvas(modifier) {
        val u = if (motionOn()) t.value * 4f else 1f
        val drop = seg(u, 0f, 0.28f); val hit = u >= 0.28f
        val sc = if (!hit) 1.9f - 0.9f * easeIn(drop) else 1f - 0.06f * sin(PI.toFloat() * seg(u, 0.28f, 0.5f))
        val a = (if (!hit) 0.25f + 0.75f * easeIn(drop) else 1f - 0.25f * smoothstep(seg(u, 1.2f, 1.8f))) * (1 - smoothstep(seg(u, 3.4f, 4f)))
        val text = measurer.measure("SAVED · $day", TextStyle(color = STAMP_INK.copy(alpha = 0.92f * a), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp))
        val bw = text.size.width + 12.dp.toPx(); val bh = text.size.height + 6.dp.toPx()
        val c = Offset(size.width * 0.62f, size.height * 0.62f)
        rotate(-12f, c) {
            scale(sc, sc, c) {
                drawRoundRect(STAMP_INK.copy(alpha = 0.92f * a), Offset(c.x - bw / 2f, c.y - bh / 2f), Size(bw, bh), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
                drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
            }
        }
        if (hit && u < 0.6f) {
            val r = seg(u, 0.28f, 0.6f); val g = 5.dp.toPx() * r
            drawRoundRect(STAMP_INK.copy(alpha = 0.6f * (1 - r)), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(6.dp.toPx() + g), style = Stroke(1.5.dp.toPx()))
        }
    }
}
