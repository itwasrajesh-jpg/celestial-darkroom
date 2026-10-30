// 89c: the app's own icon set (approved proof visual-3/proof/3-icon-set.jpg), replacing the text
// characters (‹ ✕ ⚙ ﹀ ?) that drew differently on every phone's font. Every glyph is drawn on a 24-unit grid with the same round-capped line (1/12.5 of its size: 1.6 dp at
// 20 dp) and, where it has one, a single accent detail (the dial's dot, the lens glint) in the theme accent.
package com.celestial.latent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.celestial.latent.motionOn
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph(val label: String) {
    BACK("back"), CLOSE("close"), FOLD("fold"), ADD("add"), SETTINGS("settings"), HELP("help"),
    GRID("grid"), CAROUSEL("carousel"), PRINTS("prints"), SHEET("contact sheet"),
    IMPORT("import"), LOOK("look"), FOLDER("folder"), DELETE("delete"), SHARE("share"),
    LIGHT("light"), FILM("film"), HALATION("halation"), GRAIN("grain"), DIFFUSION("diffusion"), CAMERA("camera"),
    DEVELOP("develop"), BURST("burst"), HDR("hdr"), LOUPE("loupe"),
    ENLARGER("enlarger"), SCANNER("scanner"), GLARE("glare"), COLOUR("colour"), ENGINE("engine"), UNFOLD("unfold"), NEXT("next"),
}

/**
 * A glyph. 101a: it plays its short motion ([drawGlyphMoving]) when tapped anywhere in [modifier]'s area
 * ([tap], watched without taking the tap from the button), and whenever [play] changes to a new non-null
 * value (a darkroom tab as it is chosen). Nothing moves when the phone's "remove animations" is on.
 */
@Composable
fun GlyphIcon(g: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp, accent: Color = LatentColors.Amber,
              tap: Boolean = true, play: Any? = null) {
    val t = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun go() { if (motionOn()) scope.launch { t.snapTo(0f); t.animateTo(1f, tween(GLYPH_MOTION_MS, easing = LinearEasing)); t.snapTo(0f) } }
    var last by remember { mutableStateOf(play) }
    LaunchedEffect(play) { if (play != null && play != last) go(); last = play }
    val watch = if (!tap) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) go()
        }
    }
    Canvas(watch.then(modifier).size(size)) { drawGlyphMoving(g, tint, accent, t.value) }
}

/** Draws [g] filling this scope (square). */
fun DrawScope.drawGlyph(g: Glyph, ink: Color, accent: Color) {
    val u = size.minDimension / 24f
    val sw = size.minDimension / 12.5f
    val line = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
        if (close) close()
    }
    fun stroke(path: Path, c: Color = ink) = drawPath(path, c, style = line)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f, c: Color = ink) =
        drawRoundRect(c, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = line)
    fun ring(cx: Float, cy: Float, r: Float, c: Color = ink) = drawCircle(c, r * u, p(cx, cy), style = line)
    fun dot(cx: Float, cy: Float, r: Float = 1.3f, c: Color = ink) = drawCircle(c, r * u, p(cx, cy))
    when (g) {
        Glyph.BACK -> stroke(path(15f to 5f, 8f to 12f, 15f to 19f))
        Glyph.CLOSE -> { stroke(path(6.5f to 6.5f, 17.5f to 17.5f)); stroke(path(17.5f to 6.5f, 6.5f to 17.5f)) }
        Glyph.FOLD -> stroke(path(5f to 9.5f, 12f to 15.5f, 19f to 9.5f))
        Glyph.UNFOLD -> stroke(path(5f to 14.5f, 12f to 8.5f, 19f to 14.5f))
        Glyph.NEXT -> stroke(path(9f to 5f, 16f to 12f, 9f to 19f))
        Glyph.ADD -> { stroke(path(12f to 5f, 12f to 19f)); stroke(path(5f to 12f, 19f to 12f)) }
        // three sliders, one knob in the accent: the controls you set once
        Glyph.SETTINGS -> {
            for ((x, k) in listOf(6f to 15.5f, 12f to 8.5f, 18f to 13f)) { stroke(path(x to 4f, x to k - 2.6f)); stroke(path(x to k + 2.6f, x to 20f)) }
            ring(6f, 15.5f, 2.2f); ring(12f, 8.5f, 2.2f, accent); ring(18f, 13f, 2.2f)
        }
        Glyph.HELP -> {
            ring(12f, 12f, 9f)
            stroke(Path().apply { moveTo(9.4f * u, 9.6f * u); cubicTo(9.4f * u, 6.6f * u, 14.8f * u, 6.6f * u, 14.6f * u, 9.8f * u)
                cubicTo(14.5f * u, 11.6f * u, 12f * u, 11.8f * u, 12f * u, 13.8f * u) })
            dot(12f, 16.8f, 1.15f)
        }
        Glyph.GRID -> { box(4f, 4f, 7f, 7f, 1.6f); box(13f, 4f, 7f, 7f, 1.6f); box(4f, 13f, 7f, 7f, 1.6f); box(13f, 13f, 7f, 7f, 1.6f) }
        Glyph.CAROUSEL -> { box(8f, 4f, 8f, 16f, 1.8f); stroke(path(5f to 7f, 3f to 8f, 3f to 16f, 5f to 17f)); stroke(path(19f to 7f, 21f to 8f, 21f to 16f, 19f to 17f)) }
        Glyph.PRINTS -> rotate(-8f, p(12f, 12f)) { box(5f, 3.5f, 14f, 17f, 1f); box(7.6f, 6f, 8.8f, 8.6f, 0.6f) }
        // a sheet of paper with two strips of frames on it
        Glyph.SHEET -> {
            box(3f, 3f, 18f, 18f, 1.4f)
            for (r in 0 until 2) for (c in 0 until 3) drawRect(if (r == 1 && c == 1) accent else ink, p(6f + c * 4.5f, 6.5f + r * 6f), Size(3.2f * u, 4.2f * u))
            stroke(path(6.5f to 17.5f, 13f to 17.5f))
        }
        Glyph.IMPORT -> { stroke(path(4f to 14f, 4f to 19f, 20f to 19f, 20f to 14f)); stroke(path(12f to 4f, 12f to 14f)); stroke(path(8f to 10.5f, 12f to 14.5f, 16f to 10.5f)) }
        // three filter swatches overlapping: a look is a set of choices
        Glyph.LOOK -> { ring(9.5f, 9.5f, 5f); ring(14.5f, 9.5f, 5f); ring(12f, 14.5f, 5f); dot(12f, 11.2f, 1.2f, accent) }
        Glyph.FOLDER -> stroke(path(3.5f to 7f, 3.5f to 18.5f, 20.5f to 18.5f, 20.5f to 8.5f, 11.5f to 8.5f, 9.5f to 5.5f, 3.5f to 5.5f, 3.5f to 7f))
        Glyph.DELETE -> { stroke(path(4f to 7f, 20f to 7f)); stroke(path(9.5f to 7f, 9.5f to 4.5f, 14.5f to 4.5f, 14.5f to 7f))
            stroke(path(6.5f to 7f, 7.5f to 19.5f, 16.5f to 19.5f, 17.5f to 7f)); stroke(path(10.5f to 11f, 10.5f to 16f)); stroke(path(13.5f to 11f, 13.5f to 16f)) }
        Glyph.SHARE -> { stroke(path(8f to 9f, 5f to 9f, 5f to 20f, 19f to 20f, 19f to 9f, 16f to 9f)); stroke(path(12f to 3.5f, 12f to 14f)); stroke(path(8.5f to 7f, 12f to 3.5f, 15.5f to 7f)) }
        // 100a: a sun, its disc in the accent (approved proof light-tab/proof/, scripts/Dark.kt)
        Glyph.LIGHT -> {
            ring(12f, 12f, 4.2f, accent)
            for (k in 0 until 8) {
                val a = k * PI / 4
                stroke(path((12f + 7.5f * cos(a).toFloat()) to (12f + 7.5f * sin(a).toFloat()), (12f + 10f * cos(a).toFloat()) to (12f + 10f * sin(a).toFloat())))
            }
        }
        // a 35 mm canister with its leader out
        Glyph.FILM -> {
            box(3.5f, 6f, 9f, 13f, 1.6f); stroke(path(6f to 4f, 10f to 4f))
            stroke(path(12.5f to 8f, 20.5f to 8f, 20.5f to 15.5f, 12.5f to 15.5f))
            dot(15.5f, 11.8f, 1f, accent); dot(18.4f, 11.8f, 1f, accent)
        }
        // a bright point with the red halo film adds round it
        Glyph.HALATION -> { dot(12f, 12f, 2.6f); ring(12f, 12f, 6.4f, accent); ring(12f, 12f, 9.6f, accent.copy(alpha = 0.45f)) }
        Glyph.GRAIN -> {
            val pts = listOf(5f to 6f, 10f to 4.5f, 16f to 6.5f, 19.5f to 4.5f, 7.5f to 11f, 13f to 10f, 18f to 12f, 4.5f to 16f, 10f to 15.5f,
                15f to 16.5f, 20f to 18f, 7f to 20f, 12.5f to 20.5f, 17f to 21f)
            pts.forEachIndexed { i, (x, y) -> dot(x, y, if (i % 3 == 0) 1.55f else 1.05f, if (i == 5) accent else ink) }
        }
        // a sharp disc softening outwards
        Glyph.DIFFUSION -> { dot(12f, 12f, 4f)
            for (i in 0 until 12) { val a = i * PI / 6; dot(12f + 7.4f * cos(a).toFloat(), 12f + 7.4f * sin(a).toFloat(), 1.1f, ink.copy(alpha = 0.7f)) }
            for (i in 0 until 12) { val a = i * PI / 6 + PI / 12; dot(12f + 10f * cos(a).toFloat(), 12f + 10f * sin(a).toFloat(), 0.8f, ink.copy(alpha = 0.4f)) } }
        Glyph.CAMERA -> { stroke(path(3f to 8f, 3f to 19f, 21f to 19f, 21f to 8f, 16f to 8f, 14.5f to 5.5f, 9.5f to 5.5f, 8f to 8f, 3f to 8f)); ring(12f, 13.2f, 3.6f); dot(13.2f, 12f, 0.9f, accent) }
        // the developing tray, with the print coming up in the bath
        Glyph.DEVELOP -> { stroke(path(3f to 11f, 4.5f to 19f, 19.5f to 19f, 21f to 11f))
            stroke(Path().apply { moveTo(5f * u, 14f * u); cubicTo(8f * u, 12.5f * u, 9.5f * u, 15.5f * u, 12f * u, 14f * u); cubicTo(14.5f * u, 12.5f * u, 16f * u, 15.5f * u, 19f * u, 14f * u) }, accent)
            box(8f, 4f, 8f, 7f, 0.8f) }
        Glyph.BURST -> { box(8f, 3.5f, 12.5f, 12.5f, 1.6f); stroke(path(5.5f to 7f, 5.5f to 18.5f, 17f to 18.5f)); stroke(path(3f to 10f, 3f to 21f, 14f to 21f)) }
        // a sun split dark and bright
        Glyph.HDR -> { ring(12f, 12f, 5f); drawArc(ink, -90f, 180f, true, p(7f, 7f), Size(10f * u, 10f * u))
            for (i in 0 until 8) { val a = i * PI / 4; val c = cos(a).toFloat(); val s = sin(a).toFloat(); stroke(path(12f + c * 7.8f to 12f + s * 7.8f, 12f + c * 9.6f to 12f + s * 9.6f)) } }
        // the enlarger: lamp head on its column, light falling to the easel
        Glyph.ENLARGER -> { stroke(path(5f to 21f, 5f to 3f)); box(7f, 4f, 10f, 6f, 1.4f); stroke(path(10f to 10f, 10f to 12.5f, 14f to 12.5f, 14f to 10f))
            stroke(path(9f to 15f, 7f to 19f), accent); stroke(path(15f to 15f, 17f to 19f), accent); stroke(path(3f to 21f, 21f to 21f)) }
        // a frame with the scan line across it
        Glyph.SCANNER -> { box(4f, 6f, 16f, 12f, 1.6f); stroke(path(2.5f to 12f, 21.5f to 12f), accent) }
        // a light with a long streak across it
        Glyph.GLARE -> { dot(12f, 12f, 2.4f); stroke(path(3f to 12f, 21f to 12f)); stroke(path(12f to 6.5f, 12f to 17.5f)); stroke(path(8.5f to 8.5f, 15.5f to 15.5f), accent) }
        // a colour wheel in three parts
        Glyph.COLOUR -> { ring(12f, 12f, 8.6f)
            for (i in 0 until 3) { val a = i * 2 * PI / 3 - PI / 2; stroke(path(12f to 12f, 12f + 8.6f * cos(a).toFloat() to 12f + 8.6f * sin(a).toFloat())) }
            dot(12f, 7.4f, 1.3f, accent) }
        // a prism splitting a ray: the spectral engine
        Glyph.ENGINE -> { stroke(path(12f to 4.5f, 19.5f to 18f, 4.5f to 18f, close = true)); stroke(path(2f to 12.5f, 9f to 12f))
            stroke(path(15.5f to 11f, 22f to 8f), accent); stroke(path(16f to 12.5f, 22f to 12f)); stroke(path(16.5f to 14f, 22f to 16f), ink.copy(alpha = 0.6f)) }
        Glyph.LOUPE -> { ring(10.5f, 10.5f, 6.5f); stroke(path(15.3f to 15.3f, 20f to 20f)); dot(8.2f, 8.2f, 1.1f, accent) }
    }
}
