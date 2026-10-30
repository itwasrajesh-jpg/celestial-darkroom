package com.celestial.latent

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.celestial.latent.develop.SceneLight
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * 102a: the darkroom's editing motion, as in the approved proofs (visual-5/1-4-editing.mp4):
 * where a LIGHT slider acts glows on the photo while it is dragged, values roll like a film
 * counter, and a red safelight glows at the photo's edges while a print develops. The zero snap
 * lives in the darkroom's slider. All of it stands still when the phone's animations are off.
 */

/** The glow's colour, a cool light, and how strongly each part is drawn (the proof's numbers). */
private val REACH_TINT = Color(0xFF96B9FF)
private const val REACH_DIM = 0.40f        // the parts the slider leaves alone dim back by this much
private const val REACH_TINT_ALPHA = 0.22f
private const val REACH_STRIPES = 0.10f

private class ReachMaps(val tint: ImageBitmap, val dim: ImageBitmap)

/** The two small pictures the glow is drawn from: the slider's reach as the tint's alpha, and its opposite as a dimming black. */
private fun reachMaps(r: SceneLight.Reach, which: String, shift: Float): ReachMaps {
    val tint = IntArray(r.gw * r.gh); val dim = IntArray(r.gw * r.gh)
    val tr = (REACH_TINT.red * 255).roundToInt(); val tg = (REACH_TINT.green * 255).roundToInt(); val tb = (REACH_TINT.blue * 255).roundToInt()
    for (i in tint.indices) {
        val b = SceneLight.reach(which, r.local[i] + shift).coerceIn(0f, 1f)
        tint[i] = ((b * 255).roundToInt() shl 24) or (tr shl 16) or (tg shl 8) or tb
        dim[i] = (((1 - b) * REACH_DIM * 255).roundToInt() shl 24)
    }
    fun bmp(px: IntArray) = Bitmap.createBitmap(px, r.gw, r.gh, Bitmap.Config.ARGB_8888).asImageBitmap()
    return ReachMaps(bmp(tint), bmp(dim))
}

/**
 * While a LIGHT tone is dragged ([which]: "highlights", "shadows", "whites", "blacks"; null when no
 * finger is on one), the parts of the photo it changes glow softly and the rest dims back; it fades
 * out a moment after the finger lifts. [reach] is the photo's brightness map ([SceneLight.reachOf]),
 * [shift] the exposure in stops, [aspect] the shown picture's — the glow lies exactly over it.
 */
@Composable
internal fun ReachGlow(reach: SceneLight.Reach?, which: String?, shift: Float, aspect: Float, modifier: Modifier = Modifier) {
    // the last slider held, so the glow it had can fade out after the finger lifts
    var shown by remember { mutableStateOf(which) }
    if (which != null && which != shown) shown = which
    val on = which != null && reach != null
    val fade by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 180 else 700), label = "reach")
    val s = shown
    if (reach == null || s == null || fade <= 0.001f) return
    val maps = remember(reach, s, shift) { reachMaps(reach, s, shift) }
    val moving = motionOn()
    val t = rememberInfiniteTransition(label = "reach")
    val pulse by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1250, easing = LinearEasing)), label = "phase")
    Canvas(modifier.fillMaxSize()) {
        val fit = fitRect(size.width, size.height, aspect)
        val a = fade * (if (moving) pulse else 1f)
        val off = IntOffset(fit.left.roundToInt(), fit.top.roundToInt())
        val sz = IntSize(fit.width.roundToInt(), fit.height.roundToInt())
        drawImage(maps.dim, dstOffset = off, dstSize = sz, alpha = a, filterQuality = FilterQuality.Low)
        drawImage(maps.tint, dstOffset = off, dstSize = sz, alpha = REACH_TINT_ALPHA * a, filterQuality = FilterQuality.Low)
        // fine diagonal stripes drifting across the glow only, so it reads as a mark, not as a change of light
        val period = 8.dp.toPx(); val step = period / 1.4142135f
        val o = (if (moving) phase else 0f) * period / 1.4142135f
        drawIntoCanvas { c ->
            c.saveLayer(Rect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height), Paint())
            drawImage(maps.tint, dstOffset = off, dstSize = sz, filterQuality = FilterQuality.Low)
            drawRect(
                Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = REACH_STRIPES * a), Color.Transparent),
                    start = Offset(o, o), end = Offset(o + step, o + step), tileMode = TileMode.Repeated),
                topLeft = Offset(fit.left, fit.top), size = Size(fit.width, fit.height), blendMode = BlendMode.SrcIn)
            c.restore()
        }
    }
}

/** The value's number, for the direction it rolls: up when it grows. */
private fun numberIn(s: String): Float? = s.filter { it.isDigit() || it == '.' || it == '-' || it == '+' }.toFloatOrNull()

/**
 * A slider's value that rolls like a film counter: each character that changes slides in from below
 * when the value grows and from above when it falls, the rest stand still. Characters line up from
 * the right, so "9.9" to "10.0" keeps the decimals in place.
 */
@Composable
internal fun RollingText(text: String, color: Color, fontSize: TextUnit) {
    if (!motionOn()) { Text(text, color = color, fontSize = fontSize); return }
    var before by remember { mutableStateOf(text) }
    val up = (numberIn(text) ?: 0f) >= (numberIn(before) ?: 0f)
    SideEffect { before = text }
    Row {
        val n = text.length
        text.forEachIndexed { i, ch ->
            key(n - i) {
                AnimatedContent(ch, transitionSpec = {
                    (slideInVertically(tween(160)) { h -> if (up) h else -h } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(160)) { h -> if (up) -h else h } + fadeOut(tween(120))) using SizeTransform(clip = true)
                }, label = "digit") { c -> Text(c.toString(), color = color, fontSize = fontSize) }
            }
        }
    }
}

/** The safelight's red, a darkroom's sodium-free red lamp. */
private val SAFELIGHT = Color(0xFFD72D1E)

/**
 * While a print develops ([on]) for more than a moment, a faint red safelight glows in from the
 * photo's edges and the picture dims a touch, breathing slowly; it fades when the new print is in.
 * Quick previews under a moving finger never light it.
 */
@Composable
internal fun Safelight(on: Boolean, aspect: Float, modifier: Modifier = Modifier) {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(on) { if (on) { delay(400); lit = true } else lit = false }
    val fade by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 300 else 500), label = "safelight")
    if (fade <= 0.001f) return
    val moving = motionOn()
    val t = rememberInfiniteTransition(label = "safelight")
    val breath by t.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breath")
    Canvas(modifier.fillMaxSize()) {
        val fit = fitRect(size.width, size.height, aspect)
        val a = fade * (if (moving) breath else 0.75f)
        drawRect(Color.Black.copy(alpha = 0.10f * fade), Offset(fit.left, fit.top), Size(fit.width, fit.height))
        safelightEdges(fit, a)
    }
}

/** The red, strongest at the edge and fading over a quarter of the width (the proof's exp(-d / 60) on a 720 px photo). */
private fun DrawScope.safelightEdges(fit: FitRect, a: Float) {
    val reach = fit.width * 0.25f
    val stops = listOf(SAFELIGHT.copy(alpha = 0.55f * a), SAFELIGHT.copy(alpha = 0.20f * a), SAFELIGHT.copy(alpha = 0.075f * a), Color.Transparent)
    val l = fit.left; val t = fit.top; val r = fit.left + fit.width; val b = fit.top + fit.height
    val d = minOf(reach, fit.height / 2f)
    drawRect(Brush.verticalGradient(stops, t, t + d), Offset(l, t), Size(fit.width, d))
    drawRect(Brush.verticalGradient(stops, b, b - d), Offset(l, b - d), Size(fit.width, d))
    drawRect(Brush.horizontalGradient(stops, l, l + d), Offset(l, t), Size(d, fit.height))
    drawRect(Brush.horizontalGradient(stops, r, r - d), Offset(r - d, t), Size(d, fit.height))
}
