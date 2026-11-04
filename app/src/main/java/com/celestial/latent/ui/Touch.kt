package com.celestial.latent.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.draw.drawWithContent
import com.celestial.latent.motionOn
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 111a: how the app answers a finger (approved proofs touch-motion/1-press-in.mp4 and 3b/4-6): whatever is
 * pressed sinks a little and springs back, and a ring in the theme's colour spreads from the touch.
 *
 * [speed] is the user's pace from Settings → Look → Motion: 1 is the proofs' pace (the fastest), down to 0.5,
 * where every touch motion takes twice as long. A spring is slowed by scaling its stiffness by speed², which
 * keeps its shape (the same overshoot, just slower), so a slower setting still moves smoothly.
 * Everything here stands still when the phone's animations are off; the haptics are the callers' and stay.
 */
object Touch {
    private const val PREFS = "latent_ui_motion"
    const val SPEED_MIN = 0.5f

    /** The pace of every touch motion, 0.5..1 (1 = as in the proofs). */
    var speed by mutableStateOf(1f)
        private set

    fun load(ctx: Context) {
        speed = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("speed", 1f) }.getOrDefault(1f).coerceIn(SPEED_MIN, 1f)
    }

    fun setSpeed(ctx: Context, s: Float) {
        speed = s.coerceIn(SPEED_MIN, 1f)
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("speed", speed).apply() }
    }

    /** A spring at the user's pace. */
    fun <T> spring(dampingRatio: Float, stiffness: Float): SpringSpec<T> =
        androidx.compose.animation.core.spring(dampingRatio = dampingRatio, stiffness = stiffness * speed * speed)

    /** [ms] at the user's pace (0.5 doubles it). */
    fun ms(ms: Int): Int = (ms / speed).toInt()

    /**
     * The ring's colour (proof 4-6): Darkroom amber, Midnight pale blue, Clay a soft blue, unless the
     * thing touched has a colour of its own (a pastel card, a slider), which the caller passes instead.
     */
    val ring: Color
        get() = when (LatentColors.theme) {
            AppTheme.DARKROOM -> Color(0xFFFAC775)
            AppTheme.MIDNIGHT -> Color(0xFF8CAFF5)
            AppTheme.CLAY -> Color(0xFF8EA6F7)
        }
}

/**
 * 111a: press-in. While a finger is down, this sinks to 96% (a firm spring, no bounce); on release it comes
 * back with a soft spring that overshoots by a hair and settles in about 0.25 s. A ring in [ring] (default
 * the theme's) spreads from the touch point and fades. Put it FIRST in the chain, before any clip, so the
 * ring can spread past the shape. It only watches the finger, so the element's own click still works,
 * and a press that turns into a scroll or drag lets go at once.
 */
fun Modifier.pressIn(enabled: Boolean = true, ring: Color? = null): Modifier = if (!enabled) this else composed {
    val scale = remember { Animatable(1f) }
    val ringAge = remember { Animatable(1f) }
    var ringAt by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    val tint = ring ?: Touch.ring
    var bounds by remember { mutableStateOf(Rect.Zero) }
    // 111e: the LIGHT wave's lit edge is drawn by the panel ([waveHost]) from this list, so a passing wave
    // redraws the panel once a frame instead of every control in it
    val me = remember { Wave.Edge() }
    DisposableEffect(Unit) { Wave.edges.add(me); onDispose { Wave.edges.remove(me) } }
    me.colour = ring
    this
        .onGloballyPositioned { bounds = it.boundsInRoot(); me.rect = bounds }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // 111d: where the last touch was and its colour, for a panel wave that this touch may start
                Wave.lastAt = bounds.topLeft + down.position; Wave.lastTint = tint
                if (!motionOn()) return@awaitEachGesture
                ringAt = down.position
                scope.launch { scale.animateTo(0.96f, Touch.spring(1f, 1500f)) }
                scope.launch { ringAge.snapTo(0f); ringAge.animateTo(1f, tween(Touch.ms(400))) }
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || (c.position - down.position).getDistance() > slop) break
                }
                scope.launch { scale.animateTo(1f, Touch.spring(0.5f, 800f)) }
            }
        }
        // a wide row sinks by the same few dp as a button, not 4% of the screen
        .graphicsLayer {
            val k = if (size.width > 0f) minOf(1f, 150f * density / size.width) else 1f
            var s = 1f - (1f - scale.value) * k
            // 111d WATER: bob up a hair as the wave passes
            val b = Wave.bob(bounds, density)
            if (b != 0f) { s *= 1f + 0.018f * b; translationY = -3.3f * density * b }
            scaleX = s; scaleY = s
        }
        .drawWithContent {
            drawContent()
            val a = ringAge.value
            if (a < 1f) {
                val r = 12f * density + 40f * density * a
                drawCircle(tint.copy(alpha = 0.65f * (1f - a) * (1f - a)), radius = r, center = ringAt, style = Stroke(width = 2f * density))
            }
        }
}

/**
 * 111d: the panel wave (approved proofs touch-motion/4-ripple-on-water, 5-light-wave, 6-colour-wash). On a big
 * moment — a look picked, APPLY TO changed, Auto level, Start fresh, a print developed — a wave spreads from
 * the touch across the panel, in the style chosen in Settings → Look:
 *  - LIGHT: a soft band of light sweeps out and lights each control's edge as it crosses;
 *  - WATER: each control bobs up a hair and settles as the ring passes, nearest first;
 *  - WASH: a faint tint of what was touched spreads over the panel and fades;
 *  - OFF.
 * It crosses the panel in about 0.45 s at full speed, slower with Settings' motion speed. The panel draws
 * the light band or wash ([waveHost]); each control answers in [pressIn].
 */
object Wave {
    enum class Style(val id: String, val label: String) { LIGHT("light", "Light"), WATER("water", "Water"), WASH("wash", "Wash"), OFF("off", "Off") }

    private const val PREFS = "latent_ui_motion"
    var style by mutableStateOf(Style.LIGHT)
        private set
    fun load(ctx: Context) {
        val id = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("wave", null) }.getOrNull()
        style = Style.entries.firstOrNull { it.id == id } ?: Style.LIGHT
    }
    fun set(ctx: Context, s: Style) {
        style = s
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("wave", s.id).apply() }
    }

    /** 111e: a control the LIGHT wave lights the edge of: where it is (root px) and its own colour, if any. */
    internal class Edge { var rect: Rect = Rect.Zero; var colour: Color? = null }
    internal val edges = LinkedHashSet<Edge>()

    internal var lastAt = Offset.Zero
    internal var lastTint = Color.Unspecified
    internal var origin = Offset.Zero
    internal var tint by mutableStateOf(Color(0xFFFAC775))
    /** Seconds since the wave started, at the user's pace (large when none is running). */
    internal var age by mutableFloatStateOf(99f)
    internal var serial by mutableIntStateOf(0)

    private const val SPEED_DP = 870f   // dp per second
    private const val BAND_DP = 55f

    /** Start a wave from the last touch, in its colour; [at] (root px) for one that no finger started. */
    fun start(at: Offset? = null) {
        if (style == Style.OFF || !motionOn()) return
        origin = at ?: lastAt
        tint = if (lastTint != Color.Unspecified && at == null) lastTint else Touch.ring
        age = 0f; serial++
    }

    private fun dist(r: Rect): Float {
        val dx = maxOf(r.left - origin.x, 0f, origin.x - r.right); val dy = maxOf(r.top - origin.y, 0f, origin.y - r.bottom)
        return hypot(dx, dy)
    }

    internal fun bob(r: Rect, density: Float): Float {
        if (style != Style.WATER || age > 1.6f || r == Rect.Zero) return 0f
        val p = age - dist(r) / (SPEED_DP * density)
        return if (p <= 0f || p > 0.6f) 0f else exp(-p * 9f) * sin(p * 26f)
    }

    internal fun rim(r: Rect, density: Float): Float {
        if (style != Style.LIGHT || age > 1.2f || r == Rect.Zero) return 0f
        val front = SPEED_DP * density * age
        val e = (dist(r) - front) / (BAND_DP * 0.7f * density)
        return exp(-e * e) * (1f - age / 1.0f).coerceAtLeast(0f)
    }
}

/** 111d: the panel a wave crosses: runs the wave's clock and draws its light band or wash under the controls. */
fun Modifier.waveHost(): Modifier = composed {
    var at by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(Wave.serial) {
        if (Wave.serial == 0) return@LaunchedEffect
        val t0 = withFrameNanos { it }
        while (Wave.age < 1.6f) {
            val now = withFrameNanos { it }
            Wave.age = (now - t0) / 1e9f * Touch.speed
        }
        Wave.age = 99f
    }
    this
        .onGloballyPositioned { at = it.positionInRoot() }
        .drawWithContent {
            val age = Wave.age
            if (age > 1.2f) { drawContent(); return@drawWithContent }
            val o = Wave.origin - at
            val r = 870f * density * age
            when (Wave.style) {
                Wave.Style.WASH -> {
                    val fade = (1f - age / 1.0f).coerceAtLeast(0f)
                    val a = (if (LatentColors.palette.light) 0.27f else 0.2f) * fade
                    if (a > 0f && r > 1f) drawCircle(
                        Brush.radialGradient(0f to Wave.tint.copy(alpha = a), 0.75f to Wave.tint.copy(alpha = a), 1f to Color.Transparent, center = o, radius = r * 1.15f),
                        radius = r * 1.15f, center = o)
                }
                Wave.Style.LIGHT -> {
                    val fade = (1f - age / 0.9f).coerceAtLeast(0f)
                    val band = 55f * density
                    val a = (if (LatentColors.palette.light) 0.24f else 0.18f) * fade
                    val outer = r + band
                    if (a > 0f && outer > 1f) {
                        val inner = ((r - band) / outer).coerceIn(0f, 0.999f)
                        drawCircle(
                            Brush.radialGradient(0f to Color.Transparent, inner to Color.Transparent, (r / outer).coerceIn(inner, 1f) to Wave.tint.copy(alpha = a), 1f to Color.Transparent, center = o, radius = outer),
                            radius = outer, center = o)
                    }
                }
                else -> {}
            }
            drawContent()
            // 111d LIGHT: each control's edge lights as the band crosses it (in Clay, in the thing's own colour)
            if (Wave.style == Wave.Style.LIGHT) {
                val mine = Rect(at, size)
                for (e in Wave.edges) {
                    val r = e.rect
                    if (r.width <= 0f || r.height <= 0f || !r.overlaps(mine)) continue
                    val lit = Wave.rim(r, density)
                    if (lit <= 0.03f) continue
                    val c = e.colour ?: Wave.tint
                    val rad = CornerRadius(minOf(r.height / 2f, 16f * density))
                    val tl = r.topLeft - at
                    drawRoundRect(c, tl, r.size, rad, style = Stroke(width = 1.5f * density), alpha = 0.95f * lit)
                    drawRoundRect(c, tl, r.size, rad, style = Stroke(width = 5f * density), alpha = 0.22f * lit)
                }
            }
        }
}
