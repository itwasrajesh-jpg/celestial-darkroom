package com.celestial.latent

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 105a: two touches on the camera, from the approved video proofs (visual-6/breathing-shutter-v4-orbit.mp4,
 * visual-6/four-touches.mp4 part 3). Both stand still when the phone's animations are off.
 *
 *  - The breathing shutter: in manual, when the light meter says the exposure is right, the whole shutter
 *    wakes (two heartbeat bumps, one pulse), turns gold and lit from inside, and breathes: it swells and
 *    brightens on a 3 s breath while three small lights circle it on tilted paths, like an atom, dim behind
 *    the button and bright in front. A press flings them outward and the glow lets go.
 *  - The level line: near level, a thin horizon line fades into the viewfinder with the angle; exactly
 *    level, it turns gold with a small pulse for a moment, then rests faint.
 */

private val GOLD = Color(0xFFFFCD78)
private val HEART = Color(0xFFFFF7E4)
private val SPARK = Color(0xFFFFECBE)

private fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
private fun eout(t: Float): Float { val x = t.coerceIn(0f, 1f); return 1 - (1 - x).pow(3) }
private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

/** One frame of the shutter's light: everything the drawing below reads. */
@Stable
internal class Aura {
    /** 0 asleep .. 1 awake */
    var on by mutableFloatStateOf(0f)
    /** 0 out .. 1 in */
    var breath by mutableFloatStateOf(0f)
    /** seconds since it woke: where the circling lights are */
    var spin by mutableFloatStateOf(0f)
    /** the circling lights' reach: they start close in, and fly out on a press */
    var reach by mutableFloatStateOf(1f)
    /** the pulse that leaves the ring as it wakes, 0..1 (≥ 1 = gone) */
    var pulse by mutableFloatStateOf(1f)
    /** set by [fire]: when the shutter was pressed while awake */
    internal var firedAt = 0L

    /** The shutter was pressed: the lights fly out and the glow lets go; it wakes again a moment later. */
    fun fire() { if (on > 0.05f) firedAt = System.nanoTime() }

    /** How much the whole button swells. */
    val swell get() = 1f + 0.075f * on * breath
}

/**
 * The shutter's light. [stops] is the light meter's reading (null outside manual): it wakes within a third of a
 * stop, and sleeps again past 0.4, so a reading that wavers on the edge does not make it flicker.
 */
@Composable
internal fun rememberAura(stops: Float?): Aura {
    val ctx = LocalContext.current
    val aura = remember { Aura() }
    var ready by remember { mutableStateOf(false) }
    ready = when {
        stops == null -> false
        ready -> abs(stops) <= 0.4f
        else -> abs(stops) <= 1f / 3f
    }
    LaunchedEffect(ready) {
        if (!motionOn()) {
            // still: lit when it is right, nothing moving
            aura.on = if (ready) 1f else 0f; aura.breath = 0.5f; aura.reach = 1f; aura.pulse = 1f; aura.spin = 0f
            return@LaunchedEffect
        }
        if (!ready) {
            // dialled away: it settles back to the plain button
            val from = aura.on; val t0 = withFrameNanos { it }
            while (aura.on > 0f) withFrameNanos { n -> aura.on = from * (1f - ease((n - t0) / 1e9f / 0.35f)) }
            return@LaunchedEffect
        }
        while (true) {
            // wakes: a tick under the thumb, two heartbeat bumps, one pulse
            Haptics.tick(ctx)
            val woke = withFrameNanos { it }
            val startOn = aura.on
            var fired = 0L
            while (true) {
                val now = withFrameNanos { it }
                val ph = (now - woke) / 1e9f
                if (aura.firedAt > woke && fired == 0L) fired = aura.firedAt
                var on = startOn + (1f - startOn) * ease(ph / 0.5f)
                var breath = 0.5f - 0.5f * cos(ph * 2f * PI.toFloat() / 3f)          // 3 s: in for 1.5, out for 1.5
                if (ph < 0.3f) breath = maxOf(breath, 0.9f * sin(ph / 0.3f * PI.toFloat()))
                else if (ph in 0.35f..0.65f) breath = maxOf(breath, 0.7f * sin((ph - 0.35f) / 0.3f * PI.toFloat()))
                var reach = 0.6f + 0.4f * ease(ph / 0.8f)
                if (fired != 0L) {
                    val since = (now - fired) / 1e9f
                    on *= 1f - ease(seg(since, 0.1f, 0.7f)); reach *= 1f + 0.6f * ease(since / 0.7f)
                    if (since > 1.9f) break                                           // let go; wake again if still right
                }
                aura.on = on; aura.breath = breath; aura.spin = ph; aura.reach = reach; aura.pulse = ph / 1.2f
            }
            aura.on = 0f
        }
    }
    return aura
}

/** A soft round light, like a blurred disc: full inside [r] − [blur], gone by [r] + 2·[blur]. */
private fun DrawScope.softDisc(c: Offset, r: Float, blur: Float, color: Color, alpha: Float) {
    if (alpha <= 0.003f) return
    val e = r + 2 * blur
    drawCircle(Brush.radialGradient(
        0f to color.copy(alpha = alpha), ((r - blur) / e).coerceIn(0f, 1f) to color.copy(alpha = alpha),
        (r / e) to color.copy(alpha = alpha * 0.5f), ((r + blur) / e) to color.copy(alpha = alpha * 0.16f), 1f to color.copy(alpha = 0f),
        center = c, radius = e), radius = e, center = c)
}

private class Orbit(val tiltDeg: Float, val secondsPerTurn: Float, val start: Float)
private val ORBITS = listOf(Orbit(0f, 2.6f, 0f), Orbit(60f, 3.1f, 0.33f), Orbit(-60f, 3.6f, 0.66f))

/** The circling lights, on the side of the button asked for ([back] = behind it). */
private fun DrawScope.orbits(a: Aura, back: Boolean) {
    if (a.on <= 0.01f) return
    // the button's swell is its graphics layer's scale, which this is drawn under
    val c = center; val k = a.reach
    val ax = 69.dp.toPx() * k; val bx = 19.5.dp.toPx() * k
    for (o in ORBITS) {
        val tr = Math.toRadians(o.tiltDeg.toDouble()); val ct = cos(tr).toFloat(); val st = sin(tr).toFloat()
        fun at(th: Float): Triple<Float, Float, Float> {
            val x = ax * cos(th); val y = bx * sin(th)
            return Triple(c.x + x * ct - y * st, c.y + x * st + y * ct, sin(th))
        }
        val th = 2f * PI.toFloat() * (a.spin / o.secondsPerTurn + o.start)
        // the path: a hair-thin trace
        var prev = at(th)
        for (i in 1..120) {
            val p = at(th - 2f * PI.toFloat() * i / 120f)
            if ((prev.third < 0f) == back) drawLine(GOLD.copy(alpha = 0.15f * a.on), Offset(prev.first, prev.second), Offset(p.first, p.second), 0.6.dp.toPx())
            prev = p
        }
        // the light and its tail
        for (i in 25 downTo 0) {
            val (x, y, z) = at(th - i * 0.045f)
            if ((z < 0f) != back) continue
            val f = 1f - i / 26f
            val alpha = a.on * f.pow(1.6f) * (if (back) 0.45f else 1f)
            val r = (1.1f + 2f * f * f).dp.toPx()
            softDisc(Offset(x, y), r + 1.dp.toPx(), 3.5.dp.toPx(), SPARK, 0.55f * alpha)
            drawCircle(SPARK.copy(alpha = alpha), r, Offset(x, y))
        }
    }
}

/**
 * Around the shutter (the 78 dp ring): its glow and the lights behind it, then the button, then the gold
 * ring, the waking pulse and the lights in front.
 */
internal fun Modifier.auraAround(a: Aura, ring: Color): Modifier = drawWithContent {
    val on = a.on
    val rr = size.minDimension / 2f
    if (on > 0.01f) {
        val glow = on * (0.35f + 0.65f * a.breath)
        softDisc(center, rr + 6.dp.toPx(), 9.dp.toPx(), GOLD, 0.65f * glow)
        softDisc(center, rr, 4.dp.toPx(), GOLD, 0.35f * glow)
        orbits(a, back = true)
    }
    drawContent()
    if (on > 0.01f) {
        val u = a.pulse
        if (u < 1f) drawCircle(Color(0xFFFFD58C).copy(alpha = 0.78f * (1f - u) * on), rr + 40.dp.toPx() * eout(u), center,
            style = Stroke((0.5f + 1.5f * (1f - u)).dp.toPx()))
        drawCircle(lerp(ring, GOLD, on), rr - 1.dp.toPx(), center, style = Stroke((2f + 1.5f * on).dp.toPx()))
        orbits(a, back = false)
    }
}

/** The button's face, lit from inside: warm white at the heart, gold at the edge. */
internal fun Modifier.auraFace(a: Aura): Modifier = drawWithContent {
    drawContent()
    val li = a.on * (0.55f + 0.45f * a.breath)
    if (li > 0.01f) drawCircle(Brush.radialGradient(
        0f to HEART.copy(alpha = li), 0.55f to lerp(HEART, GOLD, 0.3f).copy(alpha = li), 1f to GOLD.copy(alpha = li),
        center = center, radius = size.minDimension / 2f), radius = size.minDimension / 2f)
}

/** The phone's roll from the gravity sensor, smoothed; null while it lies flat (looking down or up). */
@Composable
private fun rememberHorizon(): Offset? {
    val ctx = LocalContext.current
    var h by remember { mutableStateOf<Offset?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        var gx = 0f; var gy = 0f; var first = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                if (first) { gx = x; gy = y; first = false } else { gx += (x - gx) * 0.2f; gy += (y - gy) * 0.2f }
                val g = sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
                // flat on a table or pointed at the sky: there is no horizon to show
                h = if (abs(z) / g > 0.8f) null else Offset(gx, gy)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return h
}

/**
 * The level line, over the whole viewfinder. Within 4° of level (held upright or sideways) it fades in, with
 * the angle under it; exactly level (a quarter degree) it turns gold with a pulse and a tick, then rests faint.
 */
@Composable
internal fun LevelLine(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val horizon = rememberHorizon()
    val up = horizon ?: Offset(0f, 1f)
    // the screen's "up" is (gx, −gy) in drawing terms; the horizon runs across it
    val roll = Math.toDegrees(atan2(up.x.toDouble(), up.y.toDouble())).toFloat()
    val err = roll - (roll / 90f).roundToInt() * 90f
    val near = if (horizon == null) 0f else 1f - seg(abs(err), 2f, 4f)
    val level = horizon != null && abs(err) < 0.25f
    var levelSince by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(level) {
        if (!level) { levelSince = 0L; return@LaunchedEffect }
        levelSince = System.nanoTime(); Haptics.tick(ctx)
        if (!motionOn()) return@LaunchedEffect
        while ((now - levelSince) / 1e9f < 2.1f) withFrameNanos { now = System.nanoTime() }
    }
    if (near <= 0.01f) return
    val since = if (level && levelSince != 0L) ((now - levelSince) / 1e9f).coerceAtLeast(0f) else 0f
    val moving = motionOn()
    val gold = level && (!moving || since < 1.6f)
    val alpha = near * (if (level) 0.85f - 0.45f * seg(since, 1f, 2f) else 0.85f)
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val c = center
        val n = hypot(up.x, up.y).coerceAtLeast(0.1f)
        val hx = up.y / n; val hy = up.x / n                    // along the horizon, in drawing terms
        val col = (if (gold) GOLD else Color(0xFFF4F7FC)).copy(alpha = alpha)
        val w = (if (gold) 1.1f else 0.8f).dp.toPx()
        for (s in listOf(-1f, 1f)) {
            drawLine(col, Offset(c.x + s * 30.dp.toPx() * hx, c.y + s * 30.dp.toPx() * hy), Offset(c.x + s * 125.dp.toPx() * hx, c.y + s * 125.dp.toPx() * hy), w)
        }
        // the phone's own level: two short fixed marks on the nearest screen axis
        val sideways = abs(hy) > abs(hx)
        for (s in listOf(-1f, 1f)) {
            val a = if (sideways) Offset(c.x, c.y + s * 135.dp.toPx()) else Offset(c.x + s * 135.dp.toPx(), c.y)
            val b = if (sideways) Offset(c.x, c.y + s * 150.dp.toPx()) else Offset(c.x + s * 150.dp.toPx(), c.y)
            drawLine(col, a, b, 1.2.dp.toPx())
        }
        if (level) {
            if (moving && since < 0.5f) drawCircle(GOLD.copy(alpha = 0.86f * (1f - since / 0.5f)), (3f + 15f * since / 0.5f).dp.toPx(), c, style = Stroke(1.dp.toPx()))
            if (gold) drawCircle(GOLD.copy(alpha = alpha), 1.75.dp.toPx(), c)
        } else {
            val t = if (abs(err) >= 1f) "${abs(err).roundToInt()}°" else String.format(java.util.Locale.US, "%.1f°", abs(err))
            val l = measurer.measure(t, TextStyle(color = Color(0xFFF4F7FC).copy(alpha = alpha * 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Medium))
            drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y + 12.dp.toPx()))
        }
    }
}
