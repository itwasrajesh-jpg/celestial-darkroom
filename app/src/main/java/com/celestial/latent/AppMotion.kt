package com.celestial.latent

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.celestial.latent.ui.AppTheme
import com.celestial.latent.ui.LatentColors
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/*
 * 102c: motion around the app, as in the approved proofs (visual-5/8-9-around-the-app.mp4): a new
 * theme spreads out in a circle from the chip that was tapped, and the roll carousel's prints lean a
 * little and catch the light as the phone tilts. Both stand still when the phone's animations are off.
 */

/** A theme change waiting to be revealed: where it was tapped (in the window) and the theme. */
internal object ThemeReveal {
    var pending by mutableStateOf<Pair<Offset, AppTheme>?>(null); private set

    /** Changes to [theme], spreading from [at]; at once when animations are off or no place is known. */
    fun start(ctx: Context, at: Offset?, theme: AppTheme) {
        if (theme == LatentColors.theme) return
        if (at == null || !motionOn()) LatentColors.set(ctx, theme) else pending = at to theme
    }
    internal fun taken() { pending = null }
}

private const val REVEAL_MS = 650

/**
 * Around the whole app: when a theme change is asked for, the screen as it is is caught in one frame;
 * then the new colours come in under it, through a circle growing from the chip, with a faint lit rim.
 */
@Composable
internal fun ThemeRevealHost(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val layer = rememberGraphicsLayer()
    var old by remember { mutableStateOf<ImageBitmap?>(null) }
    var centre by remember { mutableStateOf(Offset.Zero) }
    val r = remember { Animatable(0f) }
    val ask = ThemeReveal.pending
    LaunchedEffect(ask) {
        if (ask == null) return@LaunchedEffect
        withFrameNanos { }                      // the frame that recorded the old colours has been drawn
        old = runCatching { layer.toImageBitmap() }.getOrNull()
        centre = ask.first
        LatentColors.set(ctx, ask.second); ThemeReveal.taken()
        if (old != null) { r.snapTo(0f); r.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing)) }
        old = null
    }
    Box(Modifier.fillMaxSize().drawWithContent {
        // the old colours are only recorded for the one frame they are needed in
        if (ThemeReveal.pending != null && old == null) { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
        else drawContent()
        val img = old ?: return@drawWithContent
        val far = max(max(hypot(centre.x, centre.y), hypot(size.width - centre.x, centre.y)),
            max(hypot(centre.x, size.height - centre.y), hypot(size.width - centre.x, size.height - centre.y)))
        val radius = r.value * far
        val hole = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, radius)) }
        clipPath(hole, ClipOp.Difference) { drawImage(img) }
        if (radius > 0f) drawCircle(Color.White.copy(alpha = 0.30f * (1 - r.value)), radius, centre, style = Stroke(3.dp.toPx()))
    }) { content() }
}

/**
 * How the phone is tilted away from how it has been held, in degrees ([Offset.x]: turned left/right,
 * [Offset.y]: forward/back), within ±7 and ±4. The resting angle follows slowly, so holding the
 * phone at any angle is level; only moving it leans the prints. Listens only while shown, and not at
 * all when the phone's animations are off.
 */
@Composable
internal fun rememberTilt(): State<Offset> {
    val ctx = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null || !motionOn()) return@DisposableEffect onDispose { }
        var restX = Float.NaN; var restY = 0f; var x = 0f; var y = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = Math.toDegrees(atan2(e.values[0], e.values[2]).toDouble()).toFloat()
                val ay = Math.toDegrees(atan2(e.values[1], e.values[2]).toDouble()).toFloat()
                if (restX.isNaN()) { restX = ax; restY = ay }
                restX += (ax - restX) * 0.02f; restY += (ay - restY) * 0.02f           // about 2 s to come level
                x += ((ax - restX) * 0.5f - x) * 0.25f; y += ((ay - restY) * 0.5f - y) * 0.25f
                tilt.value = Offset(x.coerceIn(-7f, 7f), y.coerceIn(-4f, 4f))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}
