package com.celestial.latent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestial.latent.motionOn
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 111b: the gliding selection (approved proof touch-motion/2-gliding-selection.mp4). A row of choices has
 * ONE accent pill, drawn behind them by the row, which slides and stretches to the chosen one
 * (damping 0.75, stiffness 500, ~0.25 s at full speed) instead of the colour jumping between chips.
 * A choice's own background fades where the pill covers it and its words take the ink colour by how much
 * of it the pill covers, so the pill reads as one object passing underneath.
 *
 * Use [GlideRow] (or [glideHost] on a row) with the selected key; each choice marks itself with [glideItem]
 * and reads its words' colour from [Glide.ink]. A toggle in the same row (on, but not the row's choice)
 * keeps its own accent, so a row can mix a choice and a switch.
 */
class Glide(
    /** 112: a pill of another colour (the camera's lens circle is white over the picture), else the accent. */
    internal val pillColour: Color? = null,
    /** 112: the words' colour under that pill, else the accent's ink. */
    internal val pillInk: Color? = null,
) {
    internal var host: LayoutCoordinates? = null
    internal val rects = mutableStateMapOf<Any, Rect>()
    internal var selected: Any? by mutableStateOf(null)
    /** 111e: a choice just tapped whose work has not landed yet: the pill already heads there. */
    internal var pending: Any? by mutableStateOf(null)
    internal val left = Animatable(0f)
    internal val right = Animatable(0f)
    internal val top = Animatable(0f)
    internal val bottom = Animatable(0f)
    internal val shown = Animatable(0f)
    internal var radius: Float = -1f

    /**
     * 111e: send the pill to [key] now, before the tap's work recomposes the row (APPLY TO swaps NEGATIVE to the
     * layer's settings, which took a frame or more before the pill learned of the new choice). Pair it with
     * [afterPill] around the work. Reverts by itself if the choice never arrives.
     */
    fun pick(key: Any) { if (motionOn() && key != selected) pending = key }

    /** How much of [key] the pill covers, 0..1. */
    fun coverage(key: Any): Float {
        val a = shown.value
        if (a <= 0f) return 0f
        val r = rects[key] ?: return if (key == selected) a else 0f
        if (r.width <= 0f) return 0f
        val over = (minOf(right.value, r.right) - maxOf(left.value, r.left)).coerceAtLeast(0f)
        return (over / r.width).coerceIn(0f, 1f) * a
    }

    /** The words' colour for [key]: [off] where uncovered, the accent's ink under the pill; a toggle that is [on]
     *  but not the row's choice keeps the ink. */
    fun ink(key: Any, off: Color, on: Boolean = false): Color =
        if (on && key != selected) (pillInk ?: LatentColors.AmberInk) else lerp(off, pillInk ?: LatentColors.AmberInk, coverage(key))
}

val LocalGlide = compositionLocalOf<Glide?> { null }

/** A Row with a gliding pill under its [selected] choice (null: no choice, the pill fades away). */
@Composable
fun GlideRow(
    selected: Any?,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    corner: Dp = 999.dp,
    pillColour: Color? = null,
    pillInk: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val g = remember(pillColour, pillInk) { Glide(pillColour, pillInk) }
    CompositionLocalProvider(LocalGlide provides g) {
        Row(modifier.glideHost(g, selected, corner), horizontalArrangement, verticalAlignment, content)
    }
}

/** Draws [g]'s pill behind this row's children and moves it to [selected]. Put it after any horizontalScroll,
 *  so the pill scrolls with the chips. */
fun Modifier.glideHost(g: Glide, selected: Any?, corner: Dp = 999.dp): Modifier = this.then(
    Modifier
        .onGloballyPositioned { g.host = it }
        .drawBehind {
            val a = g.shown.value
            if (a <= 0f) return@drawBehind
            val l = g.left.value; val t = g.top.value
            val w = g.right.value - l; val h = g.bottom.value - t
            if (w <= 0f || h <= 0f) return@drawBehind
            val rad = minOf(corner.toPx(), h / 2f)
            g.pillColour?.let { drawRoundRect(it, Offset(l, t), Size(w, h), CornerRadius(rad), alpha = a); return@drawBehind }
            val p = LatentColors.palette
            val brush = p.accentTop?.let { Brush.verticalGradient(listOf(it, p.amber), startY = t, endY = t + h) }
            if (brush != null) drawRoundRect(brush, Offset(l, t), Size(w, h), CornerRadius(rad), alpha = a)
            else drawRoundRect(p.amber, Offset(l, t), Size(w, h), CornerRadius(rad), alpha = a)
        }
).then(GlideMover(g, selected))

@Composable
private fun GlideMoverEffect(g: Glide, selected: Any?) {
    if (g.selected != selected) g.selected = selected
    // 111e: a tapped choice leads; it is dropped once the real one catches up (or did not come)
    val pending = g.pending
    LaunchedEffect(pending, selected) {
        if (pending == null) return@LaunchedEffect
        if (pending != selected) delay(1000)
        g.pending = null
    }
    val want = pending ?: selected
    val target = want?.let { g.rects[it] }
    LaunchedEffect(want, target) {
        if (target == null) { if (want == null) g.shown.animateTo(0f, tween(Touch.ms(150))); return@LaunchedEffect }
        val fresh = g.shown.value <= 0f
        if (fresh || !motionOn()) {
            g.left.snapTo(target.left); g.right.snapTo(target.right); g.top.snapTo(target.top); g.bottom.snapTo(target.bottom)
            if (motionOn() && fresh) g.shown.animateTo(1f, tween(Touch.ms(150))) else g.shown.snapTo(1f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch { g.left.animateTo(target.left, Touch.spring(0.75f, 500f)) }
            launch { g.right.animateTo(target.right, Touch.spring(0.75f, 500f)) }
            launch { g.top.animateTo(target.top, Touch.spring(0.75f, 500f)) }
            launch { g.bottom.animateTo(target.bottom, Touch.spring(0.75f, 500f)) }
            launch { g.shown.animateTo(1f, tween(Touch.ms(120))) }
        }
    }
}

/**
 * 111f: wait until the pill has (nearly) landed on the tapped choice, then let the tap's work run. 111e waited only
 * two frames, and APPLY TO's layer switch froze the screen with the pill hardly moved, so it still seemed to wait
 * for the layer. Gives up after ~0.35 s (at the user's pace), or at once when the pill never starts.
 */
suspend fun Glide.afterPill() {
    val t0 = withFrameNanos { it }
    var started = false
    while (true) {
        val t = withFrameNanos { it }
        val ms = (t - t0) / 1_000_000L
        val running = left.isRunning || right.isRunning
        if (running) started = true
        if (ms > Touch.ms(350)) return
        if (!started) { if (ms > 120) return else continue }
        val near = kotlin.math.abs(left.value - left.targetValue) < 3f && kotlin.math.abs(right.value - right.targetValue) < 3f
        if (!running || near) return
    }
}

/** The moving part of [glideHost], as a composed modifier (it needs a coroutine to run the springs). */
private fun GlideMover(g: Glide, selected: Any?): Modifier = Modifier.composed {
    GlideMoverEffect(g, selected)
    Modifier
}

/**
 * Marks a choice in a gliding row: records where it is, and draws its [off] background (faded where the pill
 * covers it) or, for a toggle that is [on] but not the row's choice, the accent. Put it where accentFill was,
 * after the clip.
 */
fun Modifier.glideItem(g: Glide, key: Any, off: Color, on: Boolean = false): Modifier = this
    .onGloballyPositioned { c ->
        val h = g.host
        if (h != null && h.isAttached && c.isAttached) {
            val r = h.localBoundingBoxOf(c, clipBounds = false)
            if (g.rects[key] != r) g.rects[key] = r
        }
    }
    .drawBehind {
        if (on && key != g.selected) {
            val p = LatentColors.palette
            val top = p.accentTop
            if (top != null) drawRect(Brush.verticalGradient(listOf(top, p.amber))) else drawRect(p.amber)
        } else if (off.alpha > 0f) {
            val k = 1f - g.coverage(key)
            if (k > 0f) drawRect(off, alpha = k)
        }
    }
