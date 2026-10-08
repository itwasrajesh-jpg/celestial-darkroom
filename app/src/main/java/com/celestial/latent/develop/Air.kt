package com.celestial.latent.develop

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Whole atmospheres from the photo's depth (60a): one tap fills the fog's thickness for mist,
 * morning, dusk or smog, worked out from how far each place is. Proven in Python on eight photos
 * first (`fog-atmospheres/scripts/atmos.py`), then this port against it.
 *
 * The depth model gives relative nearness (1 nearest … 0 farthest), not metres, so distance is a
 * guess: the farthest thing is taken as [RANGE] times farther than the nearest. Thickness is
 * optical depth, as everywhere in the fog: the share of a place that gets through is e^−thickness.
 *
 * Kept free of Android so it can be checked on a computer.
 */
object Air {
    val KINDS = listOf("mist", "morning", "dusk", "smog")

    /** How many times farther the farthest thing is than the nearest — a guess the depth cannot give. */
    const val RANGE = 12f

    /** The farthest share of the picture, whose brightness the air takes. */
    private const val FAR_SHARE = 0.08f

    /** Smoke absorbs as well as scatters: smog's light is this much darker than the distance's. */
    const val SMOG_DARKER = 0.7f

    /**
     * Distance, 0 nearest … 1 farthest, from nearness: nearness behaves like 1 ÷ distance, so
     * distance = 1 ÷ (nearness·(1 − 1/R) + 1/R), from 1 to R, then scaled to 0..1.
     */
    fun distance(near: Float): Pair<Float, Float> {
        val z = 1f / (near * (1f - 1f / RANGE) + 1f / RANGE)
        return Pair((z - 1f) / (RANGE - 1f), z)
    }

    /**
     * The fog's thickness on a mask grid of [w] × [h] cells, from [near] (one value per cell):
     *  - mist: even haze, growing with distance (uniform air);
     *  - morning: mist lying low — dense near the ground, thinning with height above the horizon
     *    line (a place's height is how far above that line it sits, times its distance);
     *  - dusk: a lighter haze (its light glows towards the sun: see [glow]);
     *  - smog: thick, building fast with distance.
     */
    fun thickness(kind: String, near: FloatArray, w: Int, h: Int): FloatArray = FloatArray(w * h) { k ->
        val (q, z) = distance(near[k])
        when (kind) {
            "morning" -> {
                val v = ((k / w) + 0.5f) / h
                val height = maxOf(0f, (HORIZON - v) * z)
                1.8f * q * exp(-height / (0.12f * RANGE))
            }
            "dusk" -> 1.1f * q
            "smog" -> 2.2f * q.toDouble().pow(0.7).toFloat()
            else -> 1.4f * q
        }
    }

    /** Where the horizon line is taken to be, 0 top … 1 bottom: the middle of the picture. */
    private const val HORIZON = 0.5f

    /** What the photo says about its air: its brightness (as a share of the scene's brightest light) and where the sun is. */
    data class Reading(val level: Float, val sunU: Float, val sunV: Float)

    /**
     * Reads the air from the photo: [cells] is the picture's brightness averaged over each grid
     * cell, [auto] the scene's brightest light (as Fog.autoLight measures it).
     *
     * The air takes the brightness of the farthest things: thick enough, every place fades to the
     * light of the distant air, and the farthest part of a photo is the nearest thing to it — the
     * horizon sky outdoors, the dark room behind a lamp at night. (The brightest 5%, which plain
     * fog uses, made night and indoor fog a bright grey veil.) The brighter of the far cells, so
     * open sky counts over far ground. The sun is the brightest of the farthest quarter — the sky,
     * not the brightest fur.
     */
    fun read(cells: FloatArray, auto: Float, near: FloatArray, w: Int, h: Int): Reading {
        val farCut = quantile(near, FAR_SHARE)
        val far = cells.indices.filter { near[it] <= farCut }.map { cells[it] }.toFloatArray()
        val level = if (far.isEmpty() || auto <= 1e-6f) 1f else (quantile(far, 0.75f) / auto).coerceIn(0.02f, 1.5f)
        val quarterCut = quantile(near, 0.25f)
        val quarter = cells.indices.filter { near[it] <= quarterCut }
        val bright = quantile(quarter.map { cells[it] }.toFloatArray(), 0.97f)
        var su = 0.0; var sv = 0.0; var n = 0
        for (k in quarter) if (cells[k] >= bright) { su += ((k % w) + 0.5) / w; sv += ((k / w) + 0.5) / h; n++ }
        return if (n == 0) Reading(level, 0.5f, 0.3f) else Reading(level, (su / n).toFloat(), (sv / n).toFloat())
    }

    /**
     * Dusk's glow: haze looking towards the sun is brighter, by forward scattering (the
     * Henyey–Greenstein law, g = 0.6). Angles from distances on the picture, about 70° across its
     * long edge. A multiplier on the fog's light per cell, averaging 1 over the picture.
     */
    fun glow(sunU: Float, sunV: Float, w: Int, h: Int): FloatArray {
        val long = maxOf(w, h).toFloat(); val g = 0.6f
        val p = FloatArray(w * h) { k ->
            val dx = (((k % w) + 0.5f) / w - sunU) * w / long; val dy = (((k / w) + 0.5f) / h - sunV) * h / long
            val theta = sqrt(dx * dx + dy * dy) * 1.2f
            ((1 - g * g) / (1 + g * g - 2 * g * cos(theta)).toDouble().pow(1.5)).toFloat()
        }
        val mean = p.average().toFloat()
        return FloatArray(w * h) { 0.55f + 0.45f * p[it] / mean }
    }

    /**
     * Nearness (1 nearest … 0 farthest) at the centre of each cell of a [w] × [h] mask grid, from
     * the depth model's [size] × [size] answer, bilinearly.
     */
    fun nearOn(depth: FloatArray, size: Int, w: Int, h: Int): FloatArray = FloatArray(w * h) { k ->
        val sx = (((k % w) + 0.5f) / w * size - 0.5f).coerceIn(0f, (size - 1).toFloat()); val sy = (((k / w) + 0.5f) / h * size - 0.5f).coerceIn(0f, (size - 1).toFloat())
        val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = minOf(x0 + 1, size - 1); val y1 = minOf(y0 + 1, size - 1); val fx = sx - x0; val fy = sy - y0
        (depth[y0 * size + x0] * (1 - fx) + depth[y0 * size + x1] * fx) * (1 - fy) + (depth[y1 * size + x0] * (1 - fx) + depth[y1 * size + x1] * fx) * fy
    }

    /**
     * The foreground, as a weight per cell (1 near, 0 far, soft between): the photo's depths split
     * at their natural gap — the split that keeps the near group and the far group each tightest
     * (Otsu). Tested on a flower, a cat, a chandelier and a portrait: the subject each time.
     * (Moved here from the darkroom in 60b, unchanged, so a carried atmosphere keeps the same
     * foreground clear on the Film Builder's test shot.)
     */
    fun foreground(near: FloatArray): FloatArray {
        val bins = 64; val hist = DoubleArray(bins)
        for (v in near) hist[(v.coerceIn(0f, 0.9999f) * bins).toInt()] += 1.0
        val total = near.size.toDouble(); var bestT = 0.5f; var best = -1.0; var w0 = 0.0; var s0 = 0.0
        val sAll = (0 until bins).sumOf { hist[it] * (it + 0.5) / bins }
        for (i in 0 until bins - 1) {
            w0 += hist[i] / total; s0 += hist[i] * (i + 0.5) / bins / total
            val w1 = 1 - w0; if (w0 <= 0 || w1 <= 0) continue
            val m0 = s0 / w0; val m1 = (sAll / total - s0) / w1; val between = w0 * w1 * (m0 - m1) * (m0 - m1)
            if (between > best) { best = between; bestT = (i + 1f) / bins }
        }
        val e = 0.04f
        return FloatArray(near.size) { k -> val t = ((near[k] - (bestT - e)) / (2 * e)).coerceIn(0f, 1f); t * t * (3f - 2f * t) }
    }

    /**
     * An atmosphere's thickness on a mask grid, as the AIR tab builds it: [kind]'s thickness times
     * [scale], cleared where [foreground] is (1 = fully clear), within the fog's 0…3.
     */
    fun built(kind: String, scale: Float, near: FloatArray, w: Int, h: Int, foreground: FloatArray?, max: Float = 3f): FloatArray =
        thickness(kind, near, w, h).let { t -> FloatArray(t.size) { k -> (t[k] * scale * (1f - (foreground?.get(k) ?: 0f))).coerceIn(0f, max) } }

    /** The value below which [share] of [a] lies (linear between ranks, as numpy's default). */
    fun quantile(a: FloatArray, share: Float): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf().also { it.sort() }
        val x = share * (s.size - 1); val i = x.toInt().coerceIn(0, s.size - 1); val j = minOf(i + 1, s.size - 1)
        return s[i] + (s[j] - s[i]) * (x - i)
    }
}
