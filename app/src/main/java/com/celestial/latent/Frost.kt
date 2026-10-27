package com.celestial.latent

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import kotlinx.coroutines.delay

/**
 * 88c: real frosted glass over the viewfinder (the approved proof, visual-2/proof/1-frosted-glass.jpg).
 * The camera picture is a SurfaceView or TextureView, which Compose cannot see through, so the picture is
 * copied small (an eighth of its size, about 15 times a second, only while frosted cards are showing),
 * blurred, and each card draws the part of it that lies behind it, under a lighter tint of the theme.
 */
internal object FrostBackdrop {
    var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Where the camera picture is on the screen (window pixels) and its size. */
    var at = Offset.Zero; private set
    var size = IntSize.Zero; private set
    private var small: Bitmap? = null
    private var copying = false

    internal fun capture(view: View) {
        if (copying || view.width <= 0 || view.height <= 0 || !view.isShown) return
        val w = (view.width / 8).coerceAtLeast(8); val h = (view.height / 8).coerceAtLeast(8)
        val b = small?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { small = it }
        val loc = IntArray(2); view.getLocationInWindow(loc)
        val place = Offset(loc[0].toFloat(), loc[1].toFloat()); val full = IntSize(view.width, view.height)
        val done = { ok: Boolean -> copying = false; if (ok) { blur(b, 3); at = place; size = full; picture = b.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap() } }
        when (view) {
            is TextureView -> done(runCatching { view.getBitmap(b); true }.getOrDefault(false))
            is SurfaceView -> {
                copying = true
                runCatching { PixelCopy.request(view, b, { r -> done(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper())) }
                    .onFailure { copying = false }
            }
        }
    }

    fun clear() { picture = null }

    /** Two passes of a box blur, in place: at an eighth of the size, radius 3 reads as soft glass. */
    private fun blur(b: Bitmap, r: Int) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(2) {
            boxPass(px, tmp, w, h, r, horizontal = true)
            boxPass(tmp, px, w, h, r, horizontal = false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w; val len = if (horizontal) w else h
        val n = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) src[l * w + c] else src[c * w + l] }
            for (i in -r..r) { val c = at(i); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                val o = if (horizontal) l * w + i else i * w + l
                dst[o] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = at(i - r); val d = at(i + r + 1)
                sr += ((d shr 16) and 255) - ((a shr 16) and 255); sg += ((d shr 8) and 255) - ((a shr 8) and 255); sb += (d and 255) - (a and 255)
            }
        }
    }
}

/** Keeps [FrostBackdrop] fresh from the camera picture while [active] and the app is on screen. */
@Composable
internal fun FrostCapture(view: View?, active: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { FrostBackdrop.clear() } }
    LaunchedEffect(view, active) {
        if (view == null || !active) { FrostBackdrop.clear(); return@LaunchedEffect }
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) FrostBackdrop.capture(view)
            delay(66)
        }
    }
}

/**
 * A film card's glass: the blurred camera picture behind it under the theme's tint at about half
 * strength. The chosen card keeps its accent fill; Darkroom (no depth) and a missing picture keep the tint.
 */
internal fun Modifier.frost(on: Boolean): Modifier = composed {
    var me by remember { mutableStateOf(Offset.Zero) }
    val pic = FrostBackdrop.picture
    if (on || pic == null || !LatentColors.palette.depth) return@composed accentFill(on, LatentColors.Frost)
    onGloballyPositioned { me = it.positionInWindow() }
        .drawBehind {
            val s = FrostBackdrop.size
            drawImage(pic, dstOffset = IntOffset((FrostBackdrop.at.x - me.x).toInt(), (FrostBackdrop.at.y - me.y).toInt()), dstSize = s)
        }
        .background(LatentColors.Frost.copy(alpha = if (LatentColors.Light) 0.55f else 0.42f))
}
