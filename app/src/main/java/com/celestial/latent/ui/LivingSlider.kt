package com.celestial.latent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.Haptics
import com.celestial.latent.motionOn
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 111c: the one slider of the darkroom (approved proofs touch-motion/3-living-sliders.mp4 and 3b).
 *
 * Held, its knob grows (50% wider, 22% taller) and the value rises into a bubble above the finger. It clicks
 * at the points that mean something — every half stop on an EV slider, 0 and ±half on a two-sided one, 100%
 * on an amount — and holds there for a moment (a small catch zone), with one haptic tick; nowhere else ticks.
 * Let go and the knob springs back; a value let go close to a click point settles onto it.
 *
 * Colours: the theme's (Darkroom amber, Midnight blue); in Clay each slider of a tab takes the next pastel
 * ([SliderOrder]). Same call shape as Material's Slider, so it replaces it where it stood; [colors] is
 * accepted for that and ignored (the theme decides).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LivingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") colors: SliderColors? = null,
    label: ((Float) -> String)? = null,
    detents: List<Float>? = null,
    kind: NumKind = NumKind.AMOUNT,
) {
    val context = LocalContext.current
    val min = valueRange.start; val max = valueRange.endInclusive
    val clicks = remember(min, max, detents, kind) { (detents ?: Num.detents(min, max, kind)).filter { it > min && it < max } }
    val zone = (max - min) * 0.018f
    val source = remember { MutableInteractionSource() }
    val dragged by source.collectIsDraggedAsState()
    val pressed by source.collectIsPressedAsState()
    val held = (dragged || pressed) && enabled
    val grow = remember { Animatable(0f) }
    LaunchedEffect(held) {
        if (!motionOn()) grow.snapTo(if (held) 1f else 0f)
        else grow.animateTo(if (held) 1f else 0f, Touch.spring(0.6f, 700f))
    }
    var onClick by remember { mutableStateOf<Float?>(null) }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val now by rememberUpdatedState(value)
    val tint = SliderOrder.tint()
    val measurer = rememberTextMeasurer()
    val bubbleText = label?.invoke(value)

    Slider(
        value = value.coerceIn(min, max),
        onValueChange = { raw ->
            // a click point holds the value while the finger is within its catch zone
            val c = clicks.firstOrNull { abs(raw - it) < zone }
            if (c != null && onClick != c) Haptics.tick(context)
            onClick = c
            change(c ?: raw)
        },
        onValueChangeFinished = {
            // let go close to a click point: settle onto it
            val v = now
            if (onClick == null) clicks.firstOrNull { abs(v - it) < zone * 2.2f }?.let { change(it) }
            onClick = null
            finished?.invoke()
        },
        valueRange = valueRange, steps = steps, enabled = enabled, interactionSource = source,
        colors = SliderDefaults.colors(
            thumbColor = tint.thumb, activeTrackColor = tint.fill, inactiveTrackColor = tint.track,
            disabledThumbColor = LatentColors.Line, disabledActiveTrackColor = LatentColors.Line, disabledInactiveTrackColor = LatentColors.Surface,
        ),
        thumb = {
            val g = grow.value
            Box(
                Modifier
                    .size(width = (4f + 2f * g).dp, height = (44f + 10f * g).dp)
                    .drawWithContent {
                        drawContent()
                        if (g > 0.02f && bubbleText != null) {
                            // the value, in a bubble above the knob
                            val style = TextStyle(fontSize = (11f + 2f * g).sp, fontWeight = FontWeight.Medium, color = tint.bubbleInk)
                            val t = measurer.measure(bubbleText, style)
                            val w = t.size.width + 20.dp.toPx(); val h = t.size.height + 10.dp.toPx()
                            val cx = size.width / 2f
                            val top = -h - 10.dp.toPx() - 6.dp.toPx() * g
                            val a = g.coerceIn(0f, 1f)
                            drawRoundRect(tint.bubble, Offset(cx - w / 2f, top), Size(w, h), CornerRadius(h / 2f), alpha = a)
                            val tip = Path().apply {
                                moveTo(cx - 6.dp.toPx(), top + h - 1f); lineTo(cx + 6.dp.toPx(), top + h - 1f); lineTo(cx, top + h + 6.dp.toPx()); close()
                            }
                            drawPath(tip, tint.bubble, alpha = a)
                            drawText(t, topLeft = Offset(cx - t.size.width / 2f, top + (h - t.size.height) / 2f), alpha = a)
                        }
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabled) tint.thumb else LatentColors.Line),
            )
        },
        modifier = modifier.drawWithContent {
            drawContent()
            // the click points, shown while held
            val g = grow.value
            if (g > 0.02f && max > min) for (c in clicks) {
                val f = (c - min) / (max - min)
                val x = 3.dp.toPx() + f * (size.width - 6.dp.toPx())
                val vx = 3.dp.toPx() + ((value - min) / (max - min)).coerceIn(0f, 1f) * (size.width - 6.dp.toPx())
                if (abs(x - vx) < 10.dp.toPx()) continue
                drawCircle(if (x < vx) tint.dotOnFill else tint.dotOnTrack, radius = 1.6.dp.toPx(), center = Offset(x, size.height / 2f), alpha = g)
            }
        },
    )
}

/** The colours of one slider. */
class SliderTint(val fill: Color, val track: Color, val thumb: Color, val bubble: Color, val bubbleInk: Color, val dotOnFill: Color, val dotOnTrack: Color)

/**
 * 111c: in Clay each slider of a tab takes the next pastel (Celestial, 3 Nov). A tab's content is wrapped in
 * [Group]; each slider takes its number once, in the order it first appears.
 */
class SliderOrder {
    private var n = 0
    fun next(): Int = n++

    companion object {
        private val Local = compositionLocalOf<SliderOrder?> { null }

        @Composable
        fun Group(key: Any?, content: @Composable () -> Unit) {
            key(key) {
                val order = remember { SliderOrder() }
                CompositionLocalProvider(Local provides order, content = content)
            }
        }

        @Composable
        fun tint(): SliderTint {
            val p = LatentColors.palette
            if (LatentColors.theme != AppTheme.CLAY) return SliderTint(
                fill = LatentColors.SliderFill, track = LatentColors.SliderTrackDim, thumb = LatentColors.Amber,
                bubble = LatentColors.TextBright, bubbleInk = LatentColors.Background,
                dotOnFill = LatentColors.Background, dotOnTrack = LatentColors.TextBright,
            )
            val order = Local.current
            val i = remember(order) { order?.next() ?: 0 }
            val pastel = LatentColors.card(i)
            val deep = lerp(pastel, Color.Black, 0.28f)
            return SliderTint(
                fill = pastel, track = Color(0xFFF7F9FD), thumb = deep,
                bubble = pastel, bubbleInk = p.textBright, dotOnFill = p.textBright, dotOnTrack = p.text,
            )
        }
    }
}

/** 111c: what a slider's number is, for [Num.format] and its click points. */
enum class NumKind { EV, AMOUNT, SIGNED, PLAIN }

/**
 * 111c: one way of writing numbers on every darkroom slider (Celestial, 3 Nov: "+0.0 EV, +0.00, ×1.00, −44"
 * all appeared). The rules:
 *  - stops: one decimal and "EV", signed when the slider goes both ways ("+0.3 EV", "−0.5 EV", "0 EV");
 *  - amounts (a multiplier with no unit, 0 … a few): a percentage, 1 = 100% ("45%", "120%");
 *  - two-sided amounts within −1 … 1: whole steps of 100, signed ("+12", "−44", "0");
 *  - numbers with a unit (nm, µm, px, s) or counts: as their format says;
 *  - a real minus sign (−) everywhere, and never "+0".
 */
object Num {
    fun kindOf(fmt: String, min: Float, max: Float): NumKind {
        if (fmt.contains("EV") || fmt.contains("stop")) return NumKind.EV
        val spec = Regex("%[-+ 0#]*\\d*(\\.\\d+)?[fd]").find(fmt) ?: return NumKind.PLAIN
        val rest = fmt.removeRange(spec.range).replace("×", "").trim()
        if (rest.isNotEmpty()) return NumKind.PLAIN          // a unit: nm, µm, px, s, %
        val whole = spec.value.endsWith("d") || spec.value.contains(".0f")
        return when {
            min < 0f -> NumKind.SIGNED
            whole -> NumKind.PLAIN                              // a count
            max <= 4f -> NumKind.AMOUNT
            else -> NumKind.PLAIN
        }
    }

    fun format(fmt: String, v: Float, min: Float, max: Float): String {
        if (!fmt.contains('%')) return minus(fmt)   // already written (LIGHT's tones)
        return when (kindOf(fmt, min, max)) {
            NumKind.EV -> {
                val r = (v * 10f).roundToInt() / 10f
                if (r == 0f) "0 EV" else if (min < 0f) signed(String.format(Locale.US, "%.1f", abs(r)), r) + " EV" else String.format(Locale.US, "%.1f EV", r)
            }
            NumKind.AMOUNT -> {
                val pct = v * 100f
                if (max - min < 0.5f) String.format(Locale.US, "%.1f%%", pct) else "${pct.roundToInt()}%"
            }
            NumKind.SIGNED -> {
                val whole = if (max <= 1f && min >= -1f) (v * 100f).roundToInt() else v.roundToInt()
                if (whole == 0) "0" else signed(abs(whole).toString(), whole.toFloat())
            }
            NumKind.PLAIN -> minus(String.format(Locale.US, fmt, v))
        }
    }

    /** The click points (see [LivingSlider]). */
    fun detents(min: Float, max: Float, kind: NumKind): List<Float> = when (kind) {
        NumKind.EV -> generateSequence(Math.ceil(min * 2.0).toFloat() / 2f) { it + 0.5f }.takeWhile { it <= max }.toList()
        NumKind.SIGNED -> if (min < 0f && max > 0f) listOf(min / 2f, 0f, max / 2f) else emptyList()
        NumKind.AMOUNT -> if (1f > min && 1f < max) listOf(1f) else listOf((min + max) / 2f)
        NumKind.PLAIN -> emptyList()
    }

    private fun signed(body: String, v: Float) = (if (v < 0f) "−" else "+") + body
    private fun minus(s: String) = s.replace('-', '−')
}
