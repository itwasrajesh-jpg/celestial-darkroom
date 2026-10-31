package com.celestial.latent

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.gl.FilmPreviewView
import com.celestial.latent.ui.OnPhoto
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 106a: horizon lock. While it is on (Settings → Viewfinder), the viewfinder is turned against the
 * phone's tilt and cropped so it stays level, and each single shot saves that tilt as the photo's
 * Straighten (FRAME), so it develops level. The RAW is never changed; FRAME can undo it.
 *
 * Approved proof: /mnt/project-files/horizon-lock/proof/horizon-lock.mp4.
 */
object HorizonLock {
    /** Past this the crop costs too much (15° keeps 58% of a 3:4 frame): the photo is kept as shot. */
    const val MAX_DEG = 15f

    /**
     * The phone's tilt away from the nearest quarter turn, in degrees, positive = turned clockwise as
     * seen from the front, and that quarter (0..3, clockwise, as [android.view.OrientationEventListener]
     * counts it). [gravity] is the gravity sensor's (x, y); null when the phone points at the sky or the
     * floor, where level means nothing.
     */
    fun tilt(gravity: Offset?): Pair<Float, Int>? {
        val g = gravity ?: return null
        // gravity's (x, y) turns the other way to the phone: a phone turned clockwise by θ reads x = −g·sin θ
        val cw = -Math.toDegrees(atan2(g.x.toDouble(), g.y.toDouble())).toFloat()
        val q = (cw / 90f).roundToInt()
        return (cw - q * 90f) to ((q % 4) + 4) % 4
    }

    /** The tilt the viewfinder corrects: null when there is none to correct or it is past [MAX_DEG]. */
    fun lockDeg(gravity: Offset?): Float? = tilt(gravity)?.first?.takeIf { abs(it) <= MAX_DEG }

    /**
     * How much of each edge survives straightening a [w] × [h] picture by [deg]: the same rule as
     * [com.celestial.latent.develop.Framing.cropScale], so the viewfinder shows what the photo will.
     */
    fun keep(deg: Float, w: Float = 3f, h: Float = 4f): Float {
        val r = Math.toRadians(abs(deg).toDouble()); val c = cos(r); val s = sin(r)
        return minOf(w / (w * c + h * s), h / (w * s + h * c)).toFloat()
    }

    /** The gravity sensor's latest (x, y) while the lock is on (null = off, or pointing up or down). */
    @Volatile var gravity: Offset? = null
    @Volatile private var atShot: Float? = null
    @Volatile private var atShotTurns = 0

    /** Called as the shutter fires: keeps the tilt of this moment for the shot being saved. */
    fun mark(gravity: Offset?) {
        val t = tilt(gravity)
        atShot = t?.first?.takeIf { abs(it) <= MAX_DEG }; atShotTurns = t?.second ?: 0
    }

    /**
     * The straighten for the shot just saved, once: only when its quarter turn is the one the photo is
     * framed with ([turns]), so a sideways shot with auto-rotate off is never "levelled" by 90°.
     */
    fun takeShot(turns: Int): Float? {
        val d = atShot; atShot = null
        return if (d != null && atShotTurns == turns && abs(d) >= 0.05f) d else null
    }

    /** The plain viewfinder: turned clockwise by [deg] and enlarged so no corner shows; null = as it comes. */
    fun apply(view: TextureView, deg: Float?) {
        val w = view.width.toFloat(); val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (deg == null) { view.setTransform(null); return }
        val k = 1f / keep(deg, w, h)
        view.setTransform(Matrix().apply { postScale(k, k, w / 2f, h / 2f); postRotate(deg, w / 2f, h / 2f) })
    }
}

/**
 * 106a: while horizon lock is on, under the film's name: the tilt being corrected, and the viewfinder
 * kept level. Only this small piece follows the gravity sensor, so the camera screen is not rebuilt at
 * the sensor's rate. Leaving it (lock turned off) puts the viewfinder back as it comes.
 */
@Composable
internal fun HorizonLockChip(gl: FilmPreviewView?, plain: TextureView?) {
    val g = rememberHorizon()
    val tilt = HorizonLock.tilt(g)?.first
    val deg = tilt?.takeIf { kotlin.math.abs(it) <= HorizonLock.MAX_DEG }
    SideEffect {
        HorizonLock.gravity = g
        gl?.setLevel(deg, if (deg == null) 1f else 1f / HorizonLock.keep(deg))
        plain?.let { HorizonLock.apply(it, deg) }
    }
    DisposableEffect(gl, plain) {
        onDispose { HorizonLock.gravity = null; gl?.setLevel(null); plain?.let { HorizonLock.apply(it, null) } }
    }
    val label = when {
        tilt == null -> "HORIZON LOCK · —"
        deg == null -> "HORIZON LOCK · TOO TILTED"
        else -> "HORIZON LOCK · ${kotlin.math.abs(deg).roundToInt()}°"
    }
    Text(label, color = if (deg != null) OnPhoto.Amber else OnPhoto.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(PICTURE_PILL).padding(horizontal = 8.dp, vertical = 3.dp))
}
