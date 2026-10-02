package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * How a photo is framed on the paper: turned a quarter at a time, flipped, and straightened by a
 * small angle. Straightening crops to the largest rectangle of the same shape that fits inside the
 * turned picture, so no blank corner ever shows.
 *
 * Applied to the light BEFORE the film, so grain, halation and glow form on the framed picture.
 * It belongs to the photo, never to the shared recipe: straightening one photo must not tilt every
 * new shot from the camera.
 *
 * Coordinates are continuous pixel positions (a pixel's centre is at +0.5). Order: flip, then
 * quarter turns clockwise, then the straightening turn (positive = clockwise on screen) about the
 * centre, then the centred crop.
 */
data class Framing(val turns: Int = 0, val flip: Boolean = false, val straighten: Float = 0f) {
    /** Quarter turns clockwise, 0..3. */
    val quarter: Int get() = ((turns % 4) + 4) % 4
    private val k get() = quarter
    private val rad get() = Math.toRadians(straighten.toDouble())

    val isIdentity: Boolean get() = k == 0 && !flip && abs(straighten) < 0.005f

    /** Size after the quarter turns, before straightening. */
    fun turned(w: Int, h: Int): Pair<Int, Int> = if (k % 2 == 1) Pair(h, w) else Pair(w, h)

    /**
     * How much of the turned picture survives straightening, along each edge (1 = all of it): the
     * largest same-shaped rectangle whose turned outline still fits inside the picture.
     */
    fun cropScale(w: Int, h: Int): Float {
        if (abs(straighten) < 0.005f) return 1f
        val (tw, th) = turned(w, h)
        val c = abs(cos(rad)); val s = abs(sin(rad))
        return minOf(tw / (tw * c + th * s), th / (tw * s + th * c)).toFloat()
    }

    /** The framed picture's size in pixels. */
    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        val (tw, th) = turned(w, h)
        val sc = cropScale(w, h)
        return Pair(maxOf(8, floor(tw * sc).toInt()), maxOf(8, floor(th * sc).toInt()))
    }

    /** Where an output position comes from in the source picture (w × h). */
    fun toSource(xo: Double, yo: Double, w: Int, h: Int): Pair<Double, Double> {
        val (tw, th) = turned(w, h)
        val (wo, ho) = outputSize(w, h)
        // undo the straightening turn about the centre (the crop is centred, so only the
        // distance from the middle matters)
        val dx = xo - wo / 2.0; val dy = yo - ho / 2.0
        val c = cos(rad); val s = sin(rad)
        var x = tw / 2.0 + dx * c + dy * s
        var y = th / 2.0 - dx * s + dy * c
        // undo the quarter turns, one at a time (each was clockwise: (x, y) in W×H → (H − y, x))
        var cw = tw; var ch = th
        repeat(k) {
            val px = y; val py = cw - x          // the picture before this turn was ch × cw
            x = px; y = py
            val t = cw; cw = ch; ch = t
        }
        // undo the flip
        if (flip) x = w - x
        return Pair(x, y)
    }

    /** Where a source position lands in the output (the inverse of [toSource]). */
    fun toOutput(xs: Double, ys: Double, w: Int, h: Int): Pair<Double, Double> {
        var x = if (flip) w - xs else xs
        var y = ys
        var cw = w; var ch = h
        repeat(k) {
            val nx = ch - y; val ny = x
            x = nx; y = ny
            val t = cw; cw = ch; ch = t
        }
        val (wo, ho) = outputSize(w, h)
        val dx = x - cw / 2.0; val dy = y - ch / 2.0
        val c = cos(rad); val s = sin(rad)
        return Pair(wo / 2.0 + dx * c - dy * s, ho / 2.0 + dx * s + dy * c)
    }

    /** For the record, and for telling one framing from another. */
    fun key(): String = "$k,${if (flip) 1 else 0},${"%.2f".format(java.util.Locale.US, straighten)}"
}

/**
 * Each photo's framing, kept beside it like its masks. Masks are painted on the framed picture, so
 * when the framing changes they are carried across ([carry]) and stay on the same part of the
 * subject.
 */
object Framings {
    private const val FILE = "latent_frame"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context, photo: Uri): Framing {
        val v = prefs(context).getString(photo.toString(), null) ?: return Framing()
        return runCatching {
            val p = v.split(",")
            Framing(p[0].toInt(), p[1] == "1", p[2].toFloat())
        }.getOrDefault(Framing())
    }

    fun save(context: Context, photo: Uri, f: Framing) {
        prefs(context).edit().apply { if (f.isIdentity) remove(photo.toString()) else putString(photo.toString(), f.key()) }.apply()
    }

    /**
     * A mask painted under [old] framing, carried to [new] so each mark stays over the same part of
     * the photo. [srcAspect] is the unframed photo's width ÷ height. Places the new framing shows
     * that the old one cropped away take the nearest painted value.
     */
    fun carry(mask: ExposureMap, old: Framing, new: Framing, srcAspect: Float): ExposureMap {
        if (old == new) return mask
        // work in a source frame of this aspect; the absolute size cancels out
        val sw = 1000; val sh = maxOf(1, Math.round(1000 / srcAspect))
        val (nw, nh) = new.outputSize(sw, sh)
        val (ow, oh) = old.outputSize(sw, sh)
        val out = ExposureMap.blank(nw.toFloat() / nh)
        for (j in 0 until out.height) for (i in 0 until out.width) {
            val (xs, ys) = new.toSource((i + 0.5) / out.width * nw, (j + 0.5) / out.height * nh, sw, sh)
            val (xo, yo) = old.toOutput(xs, ys, sw, sh)
            out.stops[j * out.width + i] = mask.sample((xo / ow).toFloat(), (yo / oh).toFloat())
        }
        return out
    }
}
