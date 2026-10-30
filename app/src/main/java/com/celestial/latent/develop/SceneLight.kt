package com.celestial.latent.develop

import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 100a: the LIGHT tab's Highlights, Shadows, Whites and Blacks — on the scene's own light, BEFORE
 * the film, so the film still draws the result (approved proof light-tab/proof/, scripts/light.py).
 *
 * Each pixel is lifted or lowered by a number of stops that depends on the brightness of its
 * neighbourhood, not on its own: a smoothed brightness map that keeps edges (a bilateral filter in
 * stops, 1 EV wide), so the sky and the cliff in front of it are judged apart and no halo appears
 * at the horizon, while texture inside each keeps its contrast.
 *
 * The map is worked out on a small grid (about [GRID_EDGE] across, the reach 1.2% of the long edge
 * as in the proof) and brought back to full size by joint bilateral upsampling: each pixel takes the
 * grid's map from the neighbours that are as bright as it is, so an edge stays sharp at any size.
 * Memory: two small grids, whatever the photo's size; the picture is changed in place.
 */
object SceneLight {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    private const val GREY = 0.18f
    const val GRID_EDGE = 320
    private const val REACH = 0.012f      // the smoothing's reach, of the long edge
    private const val SIGMA_EV = 1f       // how different (in stops) two places may be and still be one area
    private const val LN2 = 0.6931472f

    fun wanted(r: Recipe): Boolean =
        abs(r.lightHighlights) + abs(r.lightShadows) + abs(r.lightWhites) + abs(r.lightBlacks) > 1e-4f

    /**
     * The exposure the user has dialled, in stops — the LIGHT tab's Exposure slider, which is the
     * print exposure on the print route and the film exposure on a slide film. Highlights and the
     * rest follow it, so after brightening, more of the picture counts as highlights (as in the proof).
     */
    fun exposureStops(r: Recipe): Float {
        return if (onPrintRoute(r)) log2(1f / r.printExposure.coerceAtLeast(0.01f)) + (if (r.printExposureCompensation) 0f else r.exposureEv)
        else r.exposureEv
    }

    private fun onPrintRoute(r: Recipe) = !r.scanFilm && !Develop.isSlideFilm(r.film)

    /**
     * 100b: the LIGHT tab belongs to the photo, not to the film, so it survives a change of look.
     * [to] (a look just tapped, or the film's own look on Reset to film) gets [from]'s four tones,
     * and its exposure moved by as much as [from] had moved from [base], the look it started from —
     * so a look that sets its own print exposure keeps it, plus your change. Across print film ↔
     * slide film the exposure means a different thing (enlarger time vs film exposure): there the
     * new look keeps its own.
     */
    fun keepLight(from: Recipe, base: Recipe, to: Recipe): Recipe {
        val toned = to.copy(lightHighlights = from.lightHighlights, lightShadows = from.lightShadows,
            lightWhites = from.lightWhites, lightBlacks = from.lightBlacks)
        val print = onPrintRoute(from)
        if (print != onPrintRoute(to) || print != onPrintRoute(base)) return toned
        return if (print) toned.copy(printExposure = (to.printExposure * from.printExposure / base.printExposure.coerceAtLeast(0.01f))
            .coerceIn(PRINT_EXPOSURE_MIN, PRINT_EXPOSURE_MAX))
        else toned.copy(exposureEv = (to.exposureEv + from.exposureEv - base.exposureEv).coerceIn(-3f, 3f))
    }

    /** What the preparation reads from the recipe; "-" when the tab is untouched (and the exposure then never re-prepares). */
    fun key(r: Recipe): String = if (!wanted(r)) "-" else
        listOf(r.lightHighlights, r.lightShadows, r.lightWhites, r.lightBlacks, exposureStops(r)).joinToString(",")

    fun apply(src: Develop.Source, r: Recipe, log: (String) -> Unit = {}) {
        if (!wanted(r)) return
        log("light")
        val f = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val m = smoothed(f, src.width, src.height)
        // 102a: the map the tones read, kept for the glow over the photo ([reachOf])
        lastReach = Kept(src.image.data, src.width, src.height, Reach(m.gw, m.gh, m.base.copyOf()))
        gains(m, f, src.width, src.height, r.lightHighlights, r.lightShadows, r.lightWhites, r.lightBlacks, exposureStops(r))
    }

    private class Kept(val buffer: java.nio.ByteBuffer, val w: Int, val h: Int, val reach: Reach)
    @Volatile private var lastReach: Kept? = null

    private fun log2(x: Float) = ln(x) / LN2
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** The change in stops at local brightness [e] (stops from mid-grey, the exposure included). */
    fun stops(e: Float, highlights: Float, shadows: Float, whites: Float, blacks: Float): Float =
        highlights * 1.6f * smooth(0f, 2.5f, e) +
            shadows * 1.6f * (1 - smooth(-3.5f, 0f, e)) * smooth(-7f, -4.5f, e) +
            whites * 1.0f * smooth(1.5f, 4f, e) +
            blacks * 1.0f * (1 - smooth(-7f, -4f, e))

    /** The smoothed brightness map: the grid's cells, each in stops from mid-grey ([ev]), and [base], the same after the edge-kept smoothing. */
    private class Smoothed(val step: Int, val gw: Int, val gh: Int, val ev: FloatArray, val base: FloatArray, val inC: Float)

    private fun smoothed(f: FloatBuffer, w: Int, h: Int): Smoothed {
        val step = max(1, (max(w, h) + GRID_EDGE - 1) / GRID_EDGE)
        val gw = (w + step - 1) / step; val gh = (h + step - 1) / step
        // 1. the grid: each cell's mean brightness, in stops from mid-grey
        val sum = FloatArray(gw * gh); val cnt = IntArray(gw * gh)
        for (y in 0 until h) {
            val row = (y / step) * gw; var o = y * w * 3
            for (x in 0 until w) {
                val c = row + x / step
                sum[c] += LUM[0] * f.get(o) + LUM[1] * f.get(o + 1) + LUM[2] * f.get(o + 2); cnt[c]++; o += 3
            }
        }
        val ev = FloatArray(gw * gh) { log2(max(sum[it] / cnt[it], 1e-6f) / GREY) }
        // 2. the edge-kept smoothing on the grid
        val sigmaS = max(1f, REACH * max(w, h) / step)
        val rad = max(1, (sigmaS * 1.5f).roundToInt())
        val inS = -0.5f / (sigmaS * sigmaS); val inC = -0.5f / (SIGMA_EV * SIGMA_EV)
        val sw = FloatArray((2 * rad + 1) * (2 * rad + 1)) { i ->
            val dy = i / (2 * rad + 1) - rad; val dx = i % (2 * rad + 1) - rad
            if (dx * dx + dy * dy > rad * rad) 0f else exp(inS * (dx * dx + dy * dy))
        }
        val base = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val e0 = ev[gy * gw + gx]; var num = 0f; var den = 0f
            for (dy in -rad..rad) {
                val yy = gy + dy; if (yy < 0 || yy >= gh) continue
                for (dx in -rad..rad) {
                    val xx = gx + dx; if (xx < 0 || xx >= gw) continue
                    val s = sw[(dy + rad) * (2 * rad + 1) + dx + rad]; if (s == 0f) continue
                    val e = ev[yy * gw + xx]; val d = e - e0
                    val wt = s * exp(inC * d * d)
                    num += wt * e; den += wt
                }
            }
            base[gy * gw + gx] = num / den
        }
        return Smoothed(step, gw, gh, ev, base, inC)
    }

    /**
     * 102a: where each LIGHT slider acts, for the glow over the photo while one is dragged — the
     * smoothed brightness the tones read, [gw] × [gh] cells over the picture, in stops from mid-grey
     * before the four tones (the exposure not included: [reach] adds it).
     */
    class Reach(val gw: Int, val gh: Int, val local: FloatArray)

    /**
     * For a prepared picture: the very map its tones were worked out from when it was prepared with
     * [r]'s tones (kept by [apply]), or, untouched, read from the picture itself — the same thing.
     */
    fun reachOf(src: Develop.Source, r: Recipe): Reach {
        val kept = lastReach
        if (wanted(r) && kept != null && kept.buffer === src.image.data && kept.w == src.width && kept.h == src.height) return kept.reach
        val m = smoothed(src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer(), src.width, src.height)
        return Reach(m.gw, m.gh, m.base)
    }

    /** How much the slider [which] ("highlights", "shadows", "whites" or "blacks") acts at local brightness [e] (stops, the exposure included), 0 … 1. */
    fun reach(which: String, e: Float): Float = when (which) {
        "highlights" -> smooth(0f, 2.5f, e)
        "shadows" -> (1 - smooth(-3.5f, 0f, e)) * smooth(-7f, -4.5f, e)
        "whites" -> smooth(1.5f, 4f, e)
        "blacks" -> 1 - smooth(-7f, -4f, e)
        else -> 0f
    }

    /** In place on interleaved linear RGB. [shift] = [exposureStops]. */
    fun applyTo(f: FloatBuffer, w: Int, h: Int, highlights: Float, shadows: Float, whites: Float, blacks: Float, shift: Float) =
        gains(smoothed(f, w, h), f, w, h, highlights, shadows, whites, blacks, shift)

    private fun gains(m: Smoothed, f: FloatBuffer, w: Int, h: Int, highlights: Float, shadows: Float, whites: Float, blacks: Float, shift: Float) {
        val step = m.step; val gw = m.gw; val gh = m.gh; val ev = m.ev; val base = m.base; val inC = m.inC
        // 3. back to full size, each pixel from the grid neighbours as bright as it, then its gain
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / step - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = floor(fy).toInt(); val y1 = min(y0 + 1, gh - 1); val ay = fy - y0
            var o = y * w * 3
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / step - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = floor(fx).toInt(); val x1 = min(x0 + 1, gw - 1); val ax = fx - x0
                val r = f.get(o); val g = f.get(o + 1); val b = f.get(o + 2)
                val ep = log2(max(LUM[0] * r + LUM[1] * g + LUM[2] * b, 1e-6f) / GREY)
                val i00 = y0 * gw + x0; val i01 = y0 * gw + x1; val i10 = y1 * gw + x0; val i11 = y1 * gw + x1
                val b00 = (1 - ax) * (1 - ay); val b01 = ax * (1 - ay); val b10 = (1 - ax) * ay; val b11 = ax * ay
                var d: Float
                d = ep - ev[i00]; val w00 = b00 * exp(inC * d * d)
                d = ep - ev[i01]; val w01 = b01 * exp(inC * d * d)
                d = ep - ev[i10]; val w10 = b10 * exp(inC * d * d)
                d = ep - ev[i11]; val w11 = b11 * exp(inC * d * d)
                val den = w00 + w01 + w10 + w11
                val local = if (den > 1e-12f) (w00 * base[i00] + w01 * base[i01] + w10 * base[i10] + w11 * base[i11]) / den
                    else b00 * base[i00] + b01 * base[i01] + b10 * base[i10] + b11 * base[i11]
                val k = exp(stops(local + shift, highlights, shadows, whites, blacks) * LN2)
                f.put(o, r * k); f.put(o + 1, g * k); f.put(o + 2, b * k)
                o += 3
            }
        }
    }
}
