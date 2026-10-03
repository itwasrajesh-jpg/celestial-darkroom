package com.celestial.latent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.celestial.latent.develop.Region

/** How far the darkroom zooms in. */
const val ZOOM_MAX = 4f

/** Where a picture sits in a viewport at 1×: as large as fits, centred (like ContentScale.Fit). */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun fitRect(viewW: Float, viewH: Float, aspect: Float, pad: Float = 0f): FitRect {
    var w = viewW - 2 * pad; var h = w / aspect
    if (h > viewH - 2 * pad) { h = viewH - 2 * pad; w = h * aspect }
    return FitRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
}

/**
 * Zoom on a picture: how far in ([scale], 1 = the whole picture) and which point of the picture
 * (0..1 across and down) sits at the middle of the view ([focusU], [focusV]). The picture is
 * centred in its view at 1×, so the view's middle is the picture's middle there.
 *
 * Pinching keeps the point under your fingers under your fingers; the picture never slides off
 * the view, and at 1× it is always centred.
 */
class ZoomState {
    var scale by mutableFloatStateOf(1f); private set
    var focusU by mutableFloatStateOf(0.5f); private set
    var focusV by mutableFloatStateOf(0.5f); private set

    val zoomed: Boolean get() = scale > 1.02f

    fun reset() { scale = 1f; focusU = 0.5f; focusV = 0.5f }

    /** A point of the picture → where it is on screen. */
    fun toScreen(u: Float, v: Float, fit: FitRect, viewW: Float, viewH: Float): Offset =
        Offset(viewW / 2f + (u - focusU) * fit.width * scale, viewH / 2f + (v - focusV) * fit.height * scale)

    /** A point on screen → which point of the picture is there (may be outside 0..1). */
    fun toPicture(x: Float, y: Float, fit: FitRect, viewW: Float, viewH: Float): Pair<Float, Float> =
        Pair(focusU + (x - viewW / 2f) / (fit.width * scale), focusV + (y - viewH / 2f) / (fit.height * scale))

    /** Two fingers: zoom by [zoom] about [centroid], and move by [pan] — all in screen pixels. */
    fun pinch(centroid: Offset, pan: Offset, zoom: Float, fit: FitRect, viewW: Float, viewH: Float) {
        val (uc, vc) = toPicture(centroid.x, centroid.y, fit, viewW, viewH)
        scale = (scale * zoom).coerceIn(1f, ZOOM_MAX)
        // the point that was under the fingers is under them again, wherever they moved
        focusU = uc - (centroid.x + pan.x - viewW / 2f) / (fit.width * scale)
        focusV = vc - (centroid.y + pan.y - viewH / 2f) / (fit.height * scale)
        clamp(fit, viewW, viewH)
    }

    /** One finger, when zoomed: slide the picture by [dx], [dy] screen pixels. */
    fun panBy(dx: Float, dy: Float, fit: FitRect, viewW: Float, viewH: Float) {
        focusU -= dx / (fit.width * scale)
        focusV -= dy / (fit.height * scale)
        clamp(fit, viewW, viewH)
    }

    /** Keep the picture covering the view where it can; centred along an edge it cannot fill. */
    private fun clamp(fit: FitRect, viewW: Float, viewH: Float) {
        val hu = (viewW / 2f) / (fit.width * scale)
        val hv = (viewH / 2f) / (fit.height * scale)
        focusU = if (hu >= 0.5f) 0.5f else focusU.coerceIn(hu, 1f - hu)
        focusV = if (hv >= 0.5f) 0.5f else focusV.coerceIn(hv, 1f - hv)
    }

    /** The part of the picture on screen. */
    fun visible(fit: FitRect, viewW: Float, viewH: Float): Region {
        val (u0, v0) = toPicture(0f, 0f, fit, viewW, viewH)
        val (u1, v1) = toPicture(viewW, viewH, fit, viewW, viewH)
        return Region(u0.coerceIn(0f, 1f), v0.coerceIn(0f, 1f), u1.coerceIn(0f, 1f), v1.coerceIn(0f, 1f))
    }
}
