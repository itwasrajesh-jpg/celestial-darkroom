package com.celestial.latent.ui

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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val GLYPH_MOTION_MS = 350

private fun bump(t: Float) = sin(PI * t.coerceIn(0f, 1f)).toFloat()                 // 0 → 1 → 0
private fun ease(t: Float) = t.coerceIn(0f, 1f).let { 1 - (1 - it) * (1 - it) * (1 - it) }  // fast out, soft landing
private fun bounce(t: Float): Float { val x = t.coerceIn(0f, 1f); return (sin(PI * x) * (1 - x) * 1.6).toFloat() } // up, settle

/**
 * 101a: each glyph's one-shot motion when tapped (approved proof icon-motion/proof/). [t] runs 0 → 1 over
 * [GLYPH_MOTION_MS]; at t = 0 and t = 1 every glyph is exactly its resting drawing ([drawGlyph]), so
 * nothing jumps when the motion ends. The geometry is Glyphs.kt's own, with the moving part parameterised.
 */
fun DrawScope.drawGlyphMoving(g: Glyph, ink: Color, accent: Color, t: Float) {
    if (t <= 0f || t >= 1f) { drawGlyph(g, ink, accent); return }
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
    val b = bump(t); val e = ease(t)
    when (g) {
        // the sun's rays reach out and turn one step (8 rays: a 45° turn lands on the rest pose)
        Glyph.LIGHT -> {
            ring(12f, 12f, 4.2f * (1 + 0.18f * b), accent)
            for (k in 0 until 8) {
                val a = k * PI / 4 + e * PI / 4
                val r0 = 7.5f + 0.6f * b; val r1 = 10f + 1.6f * b
                stroke(path((12f + r0 * cos(a).toFloat()) to (12f + r0 * sin(a).toFloat()), (12f + r1 * cos(a).toFloat()) to (12f + r1 * sin(a).toFloat())))
            }
        }
        // the leader winds into the canister and pulls back out
        Glyph.FILM -> {
            val pull = 6.5f * b
            box(3.5f, 6f, 9f, 13f, 1.6f); stroke(path(6f to 4f, 10f to 4f))
            stroke(path(12.5f to 8f, 20.5f - pull to 8f, 20.5f - pull to 15.5f, 12.5f to 15.5f))
            if (15.5f - pull > 13.4f) dot(15.5f - pull, 11.8f, 1f, accent)
            dot(18.4f - pull, 11.8f, 1f, accent)
        }
        // the halo ripples outwards from the bright point
        Glyph.HALATION -> {
            dot(12f, 12f, 2.6f * (1 + 0.25f * b))
            ring(12f, 12f, 6.4f + 1.2f * b, accent); ring(12f, 12f, 9.6f + 0.8f * b, accent.copy(alpha = 0.45f))
            val r = 2.6f + 9f * e; ring(12f, 12f, r, accent.copy(alpha = 0.8f * (1 - t)))
        }
        // the grain sparkles: each dot flickers on its own beat
        Glyph.GRAIN -> {
            val pts = listOf(5f to 6f, 10f to 4.5f, 16f to 6.5f, 19.5f to 4.5f, 7.5f to 11f, 13f to 10f, 18f to 12f, 4.5f to 16f, 10f to 15.5f,
                15f to 16.5f, 20f to 18f, 7f to 20f, 12.5f to 20.5f, 17f to 21f)
            pts.forEachIndexed { i, (x, y) ->
                val f = 1 + 0.55f * b * sin(t * 9 + i * 2.39f).toFloat()
                dot(x, y, (if (i % 3 == 0) 1.55f else 1.05f) * f, if (i == 5) accent else ink)
            }
        }
        // the glow spreads out, then gathers back
        Glyph.DIFFUSION -> {
            dot(12f, 12f, 4f + 0.7f * b)
            for (i in 0 until 12) { val a = i * PI / 6; val r = 7.4f + 1.4f * b; dot(12f + r * cos(a).toFloat(), 12f + r * sin(a).toFloat(), 1.1f, ink.copy(alpha = 0.7f)) }
            for (i in 0 until 12) { val a = i * PI / 6 + PI / 12; val r = 10f + 1.6f * b; dot(12f + r * cos(a).toFloat(), 12f + r * sin(a).toFloat(), 0.8f, ink.copy(alpha = 0.4f * (1 - 0.5f * b))) }
        }
        // the shutter: the lens closes to a point and opens again, the glint flashes
        Glyph.CAMERA -> {
            stroke(path(3f to 8f, 3f to 19f, 21f to 19f, 21f to 8f, 16f to 8f, 14.5f to 5.5f, 9.5f to 5.5f, 8f to 8f, 3f to 8f))
            ring(12f, 13.2f, 3.6f * (1 - 0.7f * b)); dot(13.2f, 12f, 0.9f * (1 + b), accent)
        }
        // the bath sloshes and the print dips into it
        Glyph.DEVELOP -> {
            stroke(path(3f to 11f, 4.5f to 19f, 19.5f to 19f, 21f to 11f))
            val s = sin(2 * PI * t).toFloat() * b * 1.8f
            stroke(Path().apply { moveTo(5f * u, (14f + s) * u); cubicTo(8f * u, (12.5f - s) * u, 9.5f * u, (15.5f - s) * u, 12f * u, 14f * u)
                cubicTo(14.5f * u, (12.5f + s) * u, 16f * u, (15.5f + s) * u, 19f * u, (14f - s) * u) }, accent)
            box(8f, 4f + 1.6f * b, 8f, 7f, 0.8f)
        }
        // the lamp head presses down and the light falls brighter
        Glyph.ENLARGER -> {
            val d = 1.2f * b
            stroke(path(5f to 21f, 5f to 3f)); box(7f, 4f + d, 10f, 6f, 1.4f); stroke(path(10f to 10f + d, 10f to 12.5f, 14f to 12.5f, 14f to 10f + d))
            val beam = accent.copy(alpha = 1f)
            stroke(path(9f to 15f, 7f - 1.2f * b to 19f), beam); stroke(path(15f to 15f, 17f + 1.2f * b to 19f), beam); stroke(path(3f to 21f, 21f to 21f))
            if (b > 0.05f) stroke(path(12f to 14.5f, 12f to 19.5f), accent.copy(alpha = b))
        }
        // the scan line sweeps down and back
        Glyph.SCANNER -> { box(4f, 6f, 16f, 12f, 1.6f); val y = 12f + 4.6f * sin(2 * PI * t).toFloat(); stroke(path(2.5f to y, 21.5f to y), accent) }
        // the streak flares and the diagonal turns
        Glyph.GLARE -> {
            dot(12f, 12f, 2.4f * (1 + 0.3f * b)); val l = 9f * (1 + 0.15f * b); stroke(path(12f - l to 12f, 12f + l to 12f))
            stroke(path(12f to 6.5f - b, 12f to 17.5f + b))
            rotate(90f * e, p(12f, 12f)) { stroke(path(8.5f to 8.5f, 15.5f to 15.5f), accent) }
        }
        // the colour wheel spins once
        Glyph.COLOUR -> rotate(360f * e, p(12f, 12f)) { drawGlyph(g, ink, accent) }
        // the prism splits the light: the three rays shoot out in turn
        Glyph.ENGINE -> {
            stroke(path(12f to 4.5f, 19.5f to 18f, 4.5f to 18f, close = true)); stroke(path(2f to 12.5f, 9f to 12f))
            fun ray(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, k: Float) {
                val f = ((t - k) / 0.5f).coerceIn(0f, 1f).let { if (it <= 0f) 1f else ease(it) }
                stroke(path(x0 to y0, x0 + (x1 - x0) * f to y0 + (y1 - y0) * f), c)
            }
            ray(15.5f, 11f, 22f, 8f, accent, 0f); ray(16f, 12.5f, 22f, 12f, ink, 0.15f); ray(16.5f, 14f, 22f, 16f, ink.copy(alpha = 0.6f), 0.3f)
        }
        // the three knobs slide and settle
        Glyph.SETTINGS -> {
            val m = listOf(6f to 15.5f + 3.5f * b, 12f to 8.5f + 5f * b, 18f to 13f - 4f * b)
            for ((x, k) in m) { stroke(path(x to 4f, x to k - 2.6f)); stroke(path(x to k + 2.6f, x to 20f)) }
            ring(m[0].first, m[0].second, 2.2f); ring(m[1].first, m[1].second, 2.2f, accent); ring(m[2].first, m[2].second, 2.2f)
        }
        // the question's dot bounces
        Glyph.HELP -> {
            ring(12f, 12f, 9f)
            stroke(Path().apply { moveTo(9.4f * u, 9.6f * u); cubicTo(9.4f * u, 6.6f * u, 14.8f * u, 6.6f * u, 14.6f * u, 9.8f * u)
                cubicTo(14.5f * u, 11.6f * u, 12f * u, 11.8f * u, 12f * u, 13.8f * u) })
            dot(12f, 16.8f - 2.6f * bounce(t), 1.15f)
        }
        // the four tiles pop in turn
        Glyph.GRID -> {
            listOf(4f to 4f, 13f to 4f, 13f to 13f, 4f to 13f).forEachIndexed { i, (x, y) ->
                val k = 1 + 0.22f * bump(((t - i * 0.12f) / 0.64f)); scale(k, k, p(x + 3.5f, y + 3.5f)) { box(x, y, 7f, 7f, 1.6f) }
            }
        }
        // the cards slide along, one card's width, and settle
        Glyph.CAROUSEL -> { translate(-3f * u * sin(2 * PI * t).toFloat() * b, 0f) { box(8f, 4f, 8f, 16f, 1.8f) }
            stroke(path(5f to 7f, 3f to 8f, 3f to 16f, 5f to 17f)); stroke(path(19f to 7f, 21f to 8f, 21f to 16f, 19f to 17f)) }
        // the print is tossed: a wobble on the table
        Glyph.PRINTS -> rotate(-8f + 14f * b * cos(PI * t).toFloat(), p(12f, 12f)) { box(5f, 3.5f, 14f, 17f, 1f); box(7.6f, 6f, 8.8f, 8.6f, 0.6f) }
        // the picked frame runs across the sheet and comes home
        Glyph.SHEET -> {
            box(3f, 3f, 18f, 18f, 1.4f)
            val at = ((t * 6).toInt() + 4) % 6
            for (r in 0 until 2) for (c in 0 until 3) drawRect(if (r * 3 + c == at) accent else ink, p(6f + c * 4.5f, 6.5f + r * 6f), Size(3.2f * u, 4.2f * u))
            stroke(path(6.5f to 17.5f, 13f to 17.5f))
        }
        // the bin's lid lifts and tips
        Glyph.DELETE -> {
            val l = 2.6f * b
            rotate(-14f * b, p(4f, 7f)) { translate(0f, -l * u) { stroke(path(4f to 7f, 20f to 7f)); stroke(path(9.5f to 7f, 9.5f to 4.5f, 14.5f to 4.5f, 14.5f to 7f)) } }
            stroke(path(6.5f to 7f, 7.5f to 19.5f, 16.5f to 19.5f, 17.5f to 7f)); stroke(path(10.5f to 11f, 10.5f to 16f)); stroke(path(13.5f to 11f, 13.5f to 16f))
        }
        // the arrow pops up out of the tray
        Glyph.SHARE -> { stroke(path(8f to 9f, 5f to 9f, 5f to 20f, 19f to 20f, 19f to 9f, 16f to 9f))
            translate(0f, -2.8f * u * bounce(t)) { stroke(path(12f to 3.5f, 12f to 14f)); stroke(path(8.5f to 7f, 12f to 3.5f, 15.5f to 7f)) } }
        // the arrow drops into the tray
        Glyph.IMPORT -> { stroke(path(4f to 14f, 4f to 19f, 20f to 19f, 20f to 14f))
            translate(0f, 2.4f * u * b) { stroke(path(12f to 4f, 12f to 14f)); stroke(path(8f to 10.5f, 12f to 14.5f, 16f to 10.5f)) } }
        // the three filters spread apart and close again
        Glyph.LOOK -> { val s = 1.8f * b
            ring(9.5f - s * 0.7f, 9.5f - s * 0.5f, 5f); ring(14.5f + s * 0.7f, 9.5f - s * 0.5f, 5f); ring(12f, 14.5f + s, 5f); dot(12f, 11.2f, 1.2f * (1 + b), accent) }
        // the frames fan out like a burst
        Glyph.BURST -> { val f = 1.6f * b
            box(8f + f, 3.5f - f, 12.5f, 12.5f, 1.6f); stroke(path(5.5f to 7f, 5.5f to 18.5f, 17f to 18.5f)); translate(-f * u, f * u) { stroke(path(3f to 10f, 3f to 21f, 14f to 21f)) } }
        // the dark half sweeps round, the rays turn one step
        Glyph.HDR -> {
            ring(12f, 12f, 5f); drawArc(ink, -90f + 360f * e, 180f, true, p(7f, 7f), Size(10f * u, 10f * u))
            for (i in 0 until 8) { val a = i * PI / 4 + e * PI / 4; val c = cos(a).toFloat(); val s = sin(a).toFloat(); stroke(path(12f + c * 7.8f to 12f + s * 7.8f, 12f + c * 9.6f to 12f + s * 9.6f)) }
        }
        // the loupe leans in
        Glyph.LOUPE -> scale(1 + 0.18f * b, 1 + 0.18f * b, p(10.5f, 10.5f)) { drawGlyph(g, ink, accent) }
        Glyph.FOLDER -> scale(1f, 1 - 0.12f * b, p(12f, 18.5f)) { drawGlyph(g, ink, accent) }
        // arrows nudge the way they point; close and add turn a quarter
        Glyph.BACK -> translate(-2.6f * u * bounce(t), 0f) { drawGlyph(g, ink, accent) }
        Glyph.NEXT -> translate(2.6f * u * bounce(t), 0f) { drawGlyph(g, ink, accent) }
        Glyph.FOLD -> translate(0f, 2.2f * u * bounce(t)) { drawGlyph(g, ink, accent) }
        Glyph.UNFOLD -> translate(0f, -2.2f * u * bounce(t)) { drawGlyph(g, ink, accent) }
        Glyph.CLOSE, Glyph.ADD -> rotate(90f * e, p(12f, 12f)) { drawGlyph(g, ink, accent) }
    }
}
