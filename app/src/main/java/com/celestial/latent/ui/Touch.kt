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
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
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
            val s = 1f - (1f - scale.value) * k
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
