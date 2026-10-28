package com.celestial.latent

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * 92b: the light meter (approved proof visual-4/2). While you set S or ISO yourself, the EV cell shows a
 * needle: how far the picture now is, in stops, from what the phone's own auto exposure would give.
 *
 * How it knows: while the camera is on auto, the meter learns the brightness the phone aims for (a
 * centre-weighted average of the camera picture in linear light, the EV dial taken out). In manual it
 * measures the same average of the picture your settings make, and the needle shows the ratio in stops.
 * The picture is the camera's own, before any film look (FilmPreviewView.meterFrame), or the plain
 * preview copied small. Centre is exact by construction; far from centre the phone's tone curve squeezes
 * the reading a little, so ±2 stops is the scale and the ends read approximately.
 */
object LightMeter {
    /** Stops from the phone's aim, or null before the first reading. */
    var stops by mutableStateOf<Float?>(null)
        private set

    /** The phone's aim, linear, at EV 0. 18% grey until it is learned. */
    private var aim = 0.18f
    private var dirty = false
    private const val PREFS = "latent_camera_ui"
    private const val KEY = "meter_aim"

    fun load(ctx: Context) {
        aim = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 0.18f) }.getOrDefault(0.18f).coerceIn(0.01f, 0.8f)
    }

    private fun save(ctx: Context) = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, aim).apply() }

    /** Centre-weighted average luminance of an interleaved linear RGB frame. */
    fun level(rgb: FloatArray, w: Int, h: Int): Float {
        var sum = 0.0; var wsum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            val dx = (x + 0.5f) / w - 0.5f; val dy = (y + 0.5f) / h - 0.5f
            val wt = 1.0 + 2.0 * exp(-(dx * dx + dy * dy) / 0.06)
            sum += wt * (0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]); wsum += wt
        }
        return (sum / wsum).toFloat()
    }

    /**
     * One reading. [auto]: the phone is choosing the exposure, so learn its aim (slowly: the average of
     * the last few seconds, so a moving auto exposure is smoothed). Otherwise show the needle.
     */
    fun feed(ctx: Context, level: Float, auto: Boolean, locked: Boolean, evStops: Float) {
        if (level <= 0f || level.isNaN()) return
        if (auto) {
            stops = null
            if (locked) return
            val a = (level / 2f.pow(evStops)).coerceIn(0.01f, 0.8f)
            aim += (a - aim) * 0.15f; dirty = true
            return
        }
        // kept for the next start, which may open straight into manual
        if (dirty) { save(ctx); dirty = false }
        stops = (ln((level / aim).toDouble()) / ln(2.0)).toFloat().coerceIn(-3f, 3f)
    }

    private val SRGB = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private var small: Bitmap? = null

    /** The plain preview (no film look): copied small on the main thread, then averaged in linear light. */
    fun plainLevel(view: TextureView): Float? {
        if (!view.isAvailable || view.width <= 0) return null
        val b = small ?: Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).also { small = it }
        if (runCatching { view.getBitmap(b) }.isFailure) return null
        val px = IntArray(32 * 24); b.getPixels(px, 0, 32, 0, 0, 32, 24)
        val rgb = FloatArray(px.size * 3)
        for (i in px.indices) { val c = px[i]; rgb[i * 3] = SRGB[(c shr 16) and 255]; rgb[i * 3 + 1] = SRGB[(c shr 8) and 255]; rgb[i * 3 + 2] = SRGB[c and 255] }
        return level(rgb, 32, 24)
    }
}

/**
 * The needle in the EV cell: two stops each way over − ○ +, the ring lit when the needle sits in it
 * (within a sixth of a stop). The needle swings on a spring, like a meter's coil, unless animations are off.
 */
@Composable
internal fun MeterCell(stops: Float?, modifier: Modifier = Modifier) {
    val target = stops ?: 0f
    val shown by animateFloatAsState(target, if (motionOn()) spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow * 2.5f) else snap(), label = "needle")
    val measurer = rememberTextMeasurer()
    val ink = LatentColors.TextDim; val bright = LatentColors.TextBright; val accent = LatentColors.Amber
    val onTarget = stops != null && abs(stops) < 0.17f
    Column(modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(70.dp, 21.dp)) { // the height of the value line in the other cells, so the row does not jump
            val cx = size.width / 2f; val py = size.height + 22.dp.toPx(); val rr = 42.dp.toPx()
            val span = 30f // degrees each side for ±2 stops
            fun at(deg: Float, r: Float) = Offset(cx + r * sin(Math.toRadians(deg.toDouble())).toFloat(), py - r * cos(Math.toRadians(deg.toDouble())).toFloat())
            // the scale: a tick each stop, a short one each half
            for (h in -4..4) {
                val d = h * span / 4f; val long = h % 2 == 0
                drawLine(ink, at(d, rr - (if (long) 6f else 3.5f).dp.toPx()), at(d, rr), strokeWidth = (if (long) 1.3f else 0.9f).dp.toPx())
            }
            val st = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            listOf(-1 to "−", 1 to "+").forEach { (s, t) ->
                val l = measurer.measure(t, st); val p = at(s * (span + 9f), rr - 5.dp.toPx())
                drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f))
            }
            drawCircle(if (onTarget) accent else ink, radius = 3.dp.toPx(), center = at(0f, rr + 3.dp.toPx()), style = Stroke(1.2.dp.toPx()))
            // no reading yet: no needle, rather than a needle that means nothing
            if (stops != null) {
                val d = (shown.coerceIn(-2.25f, 2.25f) / 2f) * span
                drawLine(if (onTarget) accent else bright, at(d, 20.dp.toPx()), at(d, rr + 1.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(if (stops == null || onTarget) "METER" else "METER " + String.format(java.util.Locale.US, "%+.1f", stops).replace("-", "−"),
            color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
