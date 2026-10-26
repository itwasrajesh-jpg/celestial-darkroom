@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.celestial.latent

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 87c: the camera's motion, as in the approved proofs (themes/motion): a click-stop dial for shutter
 * and ISO, an iris that closes over the viewfinder on each shot, and a print that develops out of
 * white paper in the darkroom. All of it stands still when the phone's animations are turned off.
 */
internal fun motionOn(): Boolean = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

/**
 * The click-stop dial: the ladder of [labels] runs past a notch in the middle; drag it, or tap a value,
 * and each stop it passes ticks under the finger. Let go and it settles on the nearest stop with a small
 * spring. [index] is the value now in use; [onIndex] is called as each stop is reached.
 */
@Composable
internal fun ClickDial(labels: List<String>, index: Int, onIndex: (Int) -> Unit, onAuto: () -> Unit, autoLabel: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val stepPx = with(density) { 58.dp.toPx() }
    val last = (labels.size - 1).coerceAtLeast(0)
    val pos = remember { Animatable(index.coerceIn(0, last).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var reached by remember { mutableStateOf(index) }
    // the ring that pulses out from the notch on each click
    val pulse = remember { Animatable(1f) }
    fun click(i: Int) {
        if (i == reached) return
        reached = i; Haptics.tick(context); onIndex(i)
        if (motionOn()) scope.launch { pulse.snapTo(0f); pulse.animateTo(1f, tween(320)) }
    }
    fun settle(i: Int) = scope.launch {
        if (motionOn()) pos.animateTo(i.toFloat(), spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) else pos.snapTo(i.toFloat())
    }
    // a value set elsewhere (auto, a reset) turns the dial to it
    LaunchedEffect(index, labels.size) { if (!dragging && index != pos.value.roundToInt()) { reached = index; settle(index.coerceIn(0, last)) } }
    val measurer = rememberTextMeasurer()
    val ink = LatentColors.Text; val bright = LatentColors.TextBright; val notch = LatentColors.Amber
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(autoLabel, color = LatentColors.AmberInk, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill().combinedClickable(onClick = onAuto).padding(horizontal = 10.dp, vertical = 4.dp))
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(46.dp)
            .pointerInput(labels.size) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; val i = pos.value.roundToInt().coerceIn(0, last); click(i); settle(i) },
                    onDragCancel = { dragging = false; settle(pos.value.roundToInt().coerceIn(0, last)) },
                ) { change, dx ->
                    change.consume()
                    val v = (pos.value - dx / stepPx).coerceIn(-0.3f, last + 0.3f)
                    scope.launch { pos.snapTo(v) }
                    click(v.roundToInt().coerceIn(0, last))
                }
            }
            .pointerInput(labels.size) {
                // a tap on a value turns the dial to it
                detectTapGestures { p ->
                    val i = (pos.value + (p.x - size.width / 2f) / stepPx).roundToInt().coerceIn(0, last)
                    click(i); settle(i)
                }
            }) {
            val cx = size.width / 2f; val h = size.height
            val tickBase = h - 9.dp.toPx()
            for (i in labels.indices) {
                val x = cx + (i - pos.value) * stepPx
                if (x < -stepPx || x > size.width + stepPx) continue
                // stops fade out towards the ends of the strip
                val fade = (1f - abs(x - cx) / (size.width / 2f + stepPx * 0.3f)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                if (fade <= 0.01f) continue
                val near = abs(x - cx) < stepPx / 2f
                drawLine(ink.copy(alpha = fade), Offset(x, tickBase - 9.dp.toPx()), Offset(x, tickBase), strokeWidth = 1.5.dp.toPx())
                // a short tick halfway between stops
                val xm = x + stepPx / 2f
                if (i < last) drawLine(ink.copy(alpha = fade * 0.5f), Offset(xm, tickBase - 4.dp.toPx()), Offset(xm, tickBase), strokeWidth = 1.dp.toPx())
                val layout = measurer.measure(labels[i], TextStyle(color = if (near) bright else ink, fontSize = if (near) 15.sp else 12.sp,
                    fontWeight = if (near) FontWeight.Medium else FontWeight.Normal))
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, (h - 20.dp.toPx() - layout.size.height) / 2f), alpha = fade)
            }
            // the notch, and the ring pulsing out of it as a stop clicks in
            val ny = h - 1.dp.toPx(); val s = 5.dp.toPx()
            drawPath(Path().apply { moveTo(cx - s, ny); lineTo(cx + s, ny); lineTo(cx, ny - s * 1.3f); close() }, notch)
            val pv = pulse.value
            if (pv < 1f) drawCircle(bright.copy(alpha = (1f - pv * 2.5f).coerceIn(0f, 1f) * 0.6f), radius = 4.dp.toPx() + 14.dp.toPx() * pv,
                center = Offset(cx, ny - s * 0.6f), style = Stroke(1.dp.toPx()))
        }
    }
}

/** The iris over the viewfinder: [close] in 0.12 s, hold, open in 0.22 s, as in the approved proof. */
internal class IrisState {
    val shut = Animatable(0f)
    fun fire(scope: CoroutineScope) {
        if (!motionOn()) return
        scope.launch {
            shut.snapTo(0f)
            shut.animateTo(1f, tween(120))
            kotlinx.coroutines.delay(50)
            shut.animateTo(0f, tween(220))
        }
    }
}

/** Six blades closing to a turning hexagon; each blade's edge faintly lit. Drawn over the whole viewfinder. */
@Composable
internal fun Iris(state: IrisState, modifier: Modifier = Modifier) {
    val c = state.shut.value
    if (c <= 0.001f) return
    val blade = Color(0xFF05070D); val edge = LatentColors.palette.let { if (it.depth) it.rim else Color(0xFF3A3A38) }
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r0 = hypot(size.width / 2f, size.height / 2f) + 20.dp.toPx()
        val r = r0 * (1f - c) + 1f; val rot = c * 0.9f
        val pts = (0 until 6).map { k -> val a = rot + k * Math.PI.toFloat() / 3f; Offset(cx + r * cos(a), cy + r * sin(a)) }
        val hole = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        drawPath(hole, blade)
        val far = 2f * r0
        // each blade's edge runs out from a corner of the opening, over the blades only
        clipPath(hole) {
            pts.forEachIndexed { k, p ->
                val a = rot + k * Math.PI.toFloat() / 3f + Math.PI.toFloat() / 2f + 0.5f
                drawLine(edge, p, Offset(p.x + far * cos(a), p.y + far * sin(a)), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

/**
 * The print developing: from paper white, density builds in linear light, the shadows first and the
 * highlights last (the proof's maths, as a GPU shader). Needs Android 13; earlier phones show the print at once.
 */
internal class PrintDevelop {
    val t = Animatable(1f)
    fun play(scope: CoroutineScope) {
        if (!motionOn() || android.os.Build.VERSION.SDK_INT < 33) return
        scope.launch { t.snapTo(0f); t.animateTo(1f, tween(3400, easing = LinearEasing)) }
    }
    private val shader by lazy { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(DEVELOP_AGSL) else null }
    /** The effect for the print's layer at the current moment, or null once developed. */
    fun effect(): androidx.compose.ui.graphics.RenderEffect? {
        val v = t.value
        if (v >= 1f || android.os.Build.VERSION.SDK_INT < 33) return null
        val s = shader as android.graphics.RuntimeShader
        s.setFloatUniform("dev", v)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(s, "image").asComposeRenderEffect()
    }
}

/** Paper F4F1EA. D is the picture's density over the paper (it may be negative where the picture is brighter). */
private const val DEVELOP_AGSL = """
uniform shader image;
uniform float dev;
float3 lin(float3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(0.04045, c)); }
float3 srgb(float3 c) { return mix(c * 12.92, 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
half4 main(float2 p) {
    half4 c = image.eval(p);
    if (c.a < 0.002) return c;
    float3 paper = lin(float3(0.9569, 0.9451, 0.9176));
    float3 l = lin(clamp(float3(c.rgb) / float(c.a), 0.0, 1.0));
    float3 d = -log(max(l, float3(0.0001)) / paper) / log(10.0);
    float dm = (max(d.r, 0.0) + max(d.g, 0.0) + max(d.b, 0.0)) / 3.0;
    float start = 0.55 * (1.0 - clamp(dm / 2.0, 0.0, 1.0));
    float k = clamp((dev - start) / (1.0 - start + 0.000001), 0.0, 1.0);
    k = k * k * (3.0 - 2.0 * k);
    float3 o = srgb(paper * pow(float3(10.0), -d * k));
    return half4(half3(o) * c.a, c.a);
}
"""
