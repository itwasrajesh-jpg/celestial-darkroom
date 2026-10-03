package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow

/** A region of a picture, 0..1 across (u) and down (v). */
data class Region(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

/**
 * Dodge & burn for one photo: how much more or less enlarger light each part of the print gets,
 * in stops. +1 is a stop more light (burned in, darker); −1 a stop less (dodged, lighter); 0 is
 * untouched. A small grid over the whole picture — the engine samples it smoothly, so a soft brush
 * on 128 cells looks as smooth as a card waved under the enlarger.
 *
 * Stored in stops because that is how painting adds up: two passes of half a stop make a stop.
 * The engine wants multipliers, which [multipliers] gives (2^stops).
 */
class ExposureMap(val width: Int, val height: Int, val stops: FloatArray) {
    init { require(width > 0 && height > 0 && stops.size == width * height) }

    val isBlank: Boolean get() = stops.all { abs(it) < 0.001f }

    fun copy() = ExposureMap(width, height, stops.copyOf())

    /** What the engine multiplies the light by, place by place. */
    fun multipliers(): FloatArray = FloatArray(stops.size) { 2f.pow(stops[it]) }

    /** The most burned and the most dodged anywhere, in stops (both ≥ 0). */
    fun extremes(): Pair<Float, Float> = Pair(maxOf(0f, stops.maxOrNull() ?: 0f), maxOf(0f, -(stops.minOrNull() ?: 0f)))

    /** The value at a position in the picture (0..1 across and down), as the engine samples it. */
    fun sample(u: Float, v: Float): Float {
        val x = (u * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val y = (v * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
        val fx = x - x0; val fy = y - y0
        val top = stops[y0 * width + x0] * (1 - fx) + stops[y0 * width + x1] * fx
        val bot = stops[y1 * width + x0] * (1 - fx) + stops[y1 * width + x1] * fx
        return top * (1 - fy) + bot * fy
    }

    /**
     * The mask for the middle [fraction] of the picture, matching Develop.centreCrop — the quick
     * preview the darkroom shows while a control moves. Without this, a mask laid over the whole
     * frame would land in the wrong place on the cropped preview.
     */
    fun centreCrop(fraction: Float): ExposureMap {
        val f = fraction.coerceIn(0.2f, 1f)
        if (f >= 0.999f) return this
        val lo = (1f - f) / 2f
        val out = FloatArray(width * height)
        for (j in 0 until height) for (i in 0 until width) {
            out[j * width + i] = sample(lo + f * (i + 0.5f) / width, lo + f * (j + 0.5f) / height)
        }
        return ExposureMap(width, height, out)
    }

    /** The mask for a region of the picture (0..1), matching Develop.cropRegion for the zoomed view. */
    fun crop(r: Region): ExposureMap {
        val out = FloatArray(width * height)
        for (j in 0 until height) for (i in 0 until width) {
            out[j * width + i] = sample(r.u0 + (r.u1 - r.u0) * (i + 0.5f) / width, r.v0 + (r.v1 - r.v0) * (j + 0.5f) / height)
        }
        return ExposureMap(width, height, out)
    }

    companion object {
        /** Cells along the long edge. */
        const val LONG_EDGE = 128
        /** No place goes more than this many stops either way. */
        const val LIMIT = 2f

        /** An untouched mask shaped like a picture of this aspect (width / height). */
        fun blank(aspect: Float): ExposureMap {
            val a = aspect.coerceIn(0.2f, 5f)
            val w = if (a >= 1f) LONG_EDGE else maxOf(8, Math.round(LONG_EDGE * a))
            val h = if (a >= 1f) maxOf(8, Math.round(LONG_EDGE / a)) else LONG_EDGE
            return ExposureMap(w, h, FloatArray(w * h))
        }
    }
}

/**
 * Each photo's dodge & burn, kept beside the app's other data and named after the photo. Kept
 * apart from the recipe on purpose: the recipe is shared, and the camera develops every new shot
 * with it, so a mask painted to darken one sky must never travel with it.
 */
object ExposureMaps {
    private const val MAGIC = 0x4C444231   // "LDB1"

    /** Kinds of mask, each in its own folder. */
    const val DODGE_BURN = "dodgeburn"
    const val SOFTEN = "soften"

    private fun file(context: Context, photo: Uri, kind: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(photo.toString().toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, kind).apply { mkdirs() }, "$name.bin")
    }

    fun load(context: Context, photo: Uri, kind: String = DODGE_BURN): ExposureMap? = runCatching {
        val f = file(context, photo, kind)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return null
            val w = inp.readInt(); val h = inp.readInt()
            if (w !in 1..1024 || h !in 1..1024) return null
            ExposureMap(w, h, FloatArray(w * h) { inp.readFloat() })
        }
    }.onFailure { Log.w("Latent", "could not read a dodge & burn mask: ${it.message}") }.getOrNull()

    /**
     * Saves the mask; an absent one removes the file. A blank dodge & burn mask is also removed (it
     * changes nothing) — but a blank soften mask means "sharp everywhere", which is a choice, so
     * [keepBlank] keeps it.
     */
    fun save(context: Context, photo: Uri, map: ExposureMap?, kind: String = DODGE_BURN, keepBlank: Boolean = false) {
        runCatching {
            val f = file(context, photo, kind)
            if (map == null || (map.isBlank && !keepBlank)) { f.delete(); return }
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC); out.writeInt(map.width); out.writeInt(map.height)
                for (s in map.stops) out.writeFloat(s)
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w("Latent", "could not save a dodge & burn mask: ${it.message}") }
    }
}
