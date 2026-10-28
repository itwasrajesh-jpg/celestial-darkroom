package com.celestial.latent.develop

import java.nio.FloatBuffer

/**
 * 95a: Night Clean's arithmetic, free of Android so it can be checked against the tested Python
 * (ai-models/night-clean/proto.py) on a computer. [NightClean] runs the model; this does the rest.
 *
 * The model (FFDNet colour, KAIR, MIT) learnt on sRGB pictures, so it is shown the photo the way it
 * learnt: in sRGB's colours, the brightest 1% at white, BT.709-encoded. It cleans in 512 px tiles —
 * it sees 24 px around each pixel, so a 32 px margin on every side is thrown away and the tiles join
 * exactly — and only the CHANGE goes back into the linear picture, so whatever was past white (a lamp)
 * keeps its value.
 *
 * Its noise level is set per 2×2 block. Strength 25 is the approved proof's amount on the 27 Oct night
 * DNG (ai-models/proof/4-small-model.jpg). A smaller copy of the photo has less noise — making it a
 * third the size averages most of it away — so the level scales with the copy's size, and the darkroom's
 * small preview shows what the full-size develop will (checked on that DNG at 1/2, 1/3 and 1/4: the best
 * level for each was within a step of this rule). "Follow the phone's noise" spreads the same overall
 * amount by the photo's own measured noise at each brightness.
 */
internal object NightCleanMath {
    const val TILE = 512
    const val MARGIN = 32
    const val BANDS = 16
    private const val HIST = 256
    private const val HIST_MAX = 0.125f
    /**
     * The model's level per point of strength at full size. The proof ran at 25/255 on LibRaw's own sRGB
     * develop, which reads 3.98/255 by [measure]; the app's sRGB view of the same DNG reads 3.52/255,
     * so the same clean needs 3.52/3.98 of the level here.
     */
    const val PER_STRENGTH = 3.516f / 3.98f / 255f

    private const val LUT = 65536
    private val encLut = FloatArray(LUT) { i -> enc709(i / (LUT - 1.0)).toFloat() }
    private val decLut = FloatArray(LUT) { i -> dec709(i / (LUT - 1.0)).toFloat() }
    private fun enc709(x: Double) = if (x < 0.018) 4.5 * x else 1.099 * Math.pow(x, 0.45) - 0.099
    private fun dec709(y: Double) = if (y < 0.081) y / 4.5 else Math.pow((y + 0.099) / 1.099, 1 / 0.45)
    private fun lerp(lut: FloatArray, v: Float): Float {
        val f = v.coerceIn(0f, 1f) * (LUT - 1)
        val i = minOf(f.toInt(), LUT - 2)
        val u = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * u
    }
    fun enc(v: Float) = lerp(encLut, v)
    fun dec(v: Float) = lerp(decLut, v)

    // the app's linear ProPhoto to linear sRGB, and back (Develop's own sRGB → ProPhoto matrix)
    private val TO_SRGB = floatArrayOf(2.03364f, -0.72693f, -0.30671f, -0.22859f, 1.23158f, -0.00299f, -0.00836f, -0.15353f, 1.1619f)
    private val TO_PRO = floatArrayOf(0.5294f, 0.3300f, 0.1406f, 0.0983f, 0.8735f, 0.0282f, 0.0168f, 0.1178f, 0.8654f)

    /** The level the brightest 1% of the picture sits at, in sRGB light (every 4th pixel each way). */
    fun white(buf: FloatBuffer, w: Int, h: Int): Float {
        val n = ((h + 3) / 4) * ((w + 3) / 4)
        val m = FloatArray(n); var k = 0
        for (y in 0 until h step 4) for (x in 0 until w step 4) {
            val i = (y * w + x) * 3
            val r = buf.get(i); val g = buf.get(i + 1); val b = buf.get(i + 2)
            val sr = TO_SRGB[0] * r + TO_SRGB[1] * g + TO_SRGB[2] * b
            val sg = TO_SRGB[3] * r + TO_SRGB[4] * g + TO_SRGB[5] * b
            val sb = TO_SRGB[6] * r + TO_SRGB[7] * g + TO_SRGB[8] * b
            m[k++] = maxOf(sr, sg, sb)
        }
        m.sort()
        return maxOf(m[((n - 1) * 0.99).toInt()], 1e-4f)
    }

    /** One row, as the model sees it: sRGB colours over [white], encoded. */
    private fun encodeRow(buf: FloatBuffer, w: Int, y: Int, white: Float, out: FloatArray, at: Int = 0) {
        val inv = 1f / white
        for (x in 0 until w) {
            val i = (y * w + x) * 3; val o = at + x * 3
            val r = buf.get(i); val g = buf.get(i + 1); val b = buf.get(i + 2)
            out[o] = enc((TO_SRGB[0] * r + TO_SRGB[1] * g + TO_SRGB[2] * b) * inv)
            out[o + 1] = enc((TO_SRGB[3] * r + TO_SRGB[4] * g + TO_SRGB[5] * b) * inv)
            out[o + 2] = enc((TO_SRGB[6] * r + TO_SRGB[7] * g + TO_SRGB[8] * b) * inv)
        }
    }

    /** The noise in each brightness band and the picture's overall level (0..1 encoded units). */
    class Noise(val bands: FloatArray, val level: Float)

    /**
     * The photo's noise per brightness: at every other pixel each way, a Laplacian (its weights' squares
     * sum to 36) on the three channels, and per band the median — which edges barely move — read as a
     * sigma. Bands with too few pixels borrow their nearest measured neighbour.
     */
    fun measure(buf: FloatBuffer, w: Int, h: Int, white: Float): Noise {
        val hist = Array(BANDS) { IntArray(HIST) }
        val rows = Array(3) { FloatArray(w * 3) }
        for (y in 1 until h - 1 step 2) {
            for (d in 0..2) encodeRow(buf, w, y - 1 + d, white, rows[d])
            val a = rows[0]; val b = rows[1]; val c = rows[2]
            for (x in 1 until w - 1 step 2) {
                var sum = 0f; var lum = 0f
                for (ch in 0..2) {
                    val l = (x - 1) * 3 + ch; val m = x * 3 + ch; val r = (x + 1) * 3 + ch
                    val k = a[l] - 2 * a[m] + a[r] - 2 * b[l] + 4 * b[m] - 2 * b[r] + c[l] - 2 * c[m] + c[r]
                    sum += Math.abs(k); lum += b[m]
                }
                val band = (lum / 3f * BANDS).toInt().coerceIn(0, BANDS - 1)
                hist[band][(sum / 3f / HIST_MAX * HIST).toInt().coerceIn(0, HIST - 1)]++
            }
        }
        return sigmas(hist)
    }

    fun sigmas(hist: Array<IntArray>, minCount: Int = 500): Noise {
        val n = IntArray(BANDS) { hist[it].sum() }
        val s = FloatArray(BANDS) { -1f }
        for (i in 0 until BANDS) if (n[i] >= minCount) {
            var cs = 0; var j = 0
            while (j < HIST) { cs += hist[i][j]; if (cs * 2 >= n[i]) break; j++ }
            s[i] = 1.4826f * (j + 0.5f) / HIST * HIST_MAX / 6f
        }
        val good = (0 until BANDS).filter { s[it] >= 0f }
        if (good.isEmpty()) return Noise(FloatArray(BANDS) { 0.01f }, 0.01f)
        for (i in 0 until BANDS) if (s[i] < 0f) s[i] = s[good.minByOrNull { Math.abs(it - i) }!!]
        var sum = 0.0; var count = 0L
        for (i in 0 until BANDS) { sum += s[i].toDouble() * n[i]; count += n[i] }
        return Noise(s, (sum / count).toFloat())
    }

    /** Where tiles of [t] start along [n] pixels (both even, so every start is): [MARGIN] thrown away where two meet, the last flush with the edge. */
    fun starts(n: Int, t: Int): List<Int> {
        if (n <= t) return listOf(0)
        val p = (0 until n - t step t - 2 * MARGIN).toMutableList()
        p.add(n - t)
        return p
    }

    class Result(val white: Float, val noise: Noise, val level: Float, val tiles: Int)

    /**
     * Cleans the linear ProPhoto picture in [buf] (interleaved RGB) in place. [nativeScale] is the picture's
     * size against the photo's own (see Develop.Source.nativeScale). [model] takes a tile
     * (3 × th × tw, channel by channel) and its noise map (th/2 × tw/2) and returns the cleaned tile.
     */
    fun clean(buf: FloatBuffer, w: Int, h: Int, strength: Float, follow: Boolean, nativeScale: Float,
              model: (FloatArray, FloatArray, Int, Int) -> FloatArray): Result {
        val white = white(buf, w, h)
        val level = strength * PER_STRENGTH * nativeScale.coerceIn(0.05f, 1f)
        val noise = if (follow) measure(buf, w, h, white) else Noise(FloatArray(BANDS) { 1f }, 1f)
        val scale = level / noise.level
        val th = minOf(TILE, h + h % 2); val tw = minOf(TILE, w + w % 2)
        // every tile starts on an even row and column: the model reads the picture in 2×2 blocks, and a
        // tile shifted by one pixel sees different blocks — at the join that showed as a faint seam.
        // An odd edge repeats its last row or column once.
        val ys = starts(h + h % 2, th); val xs = starts(w + w % 2, tw)
        val band = FloatArray(w * th * 3)
        val tile = FloatArray(3 * th * tw)
        val map = FloatArray((th / 2) * (tw / 2))
        // rows a later band reads that this band is about to change: their original, kept aside
        var saved = FloatArray(0); var savedFrom = 0; var savedTo = 0
        var doneRows = 0; var tiles = 0
        for ((bi, y0) in ys.withIndex()) {
            // this band's rows, as the model sees them (read from the original, never a cleaned row)
            for (r in 0 until th) {
                val y = minOf(y0 + r, h - 1)
                if (y in savedFrom until savedTo) {
                    val inv = 1f / white
                    for (x in 0 until w) {
                        val i = ((y - savedFrom) * w + x) * 3; val o = (r * w + x) * 3
                        val rr = saved[i]; val gg = saved[i + 1]; val bb = saved[i + 2]
                        band[o] = enc((TO_SRGB[0] * rr + TO_SRGB[1] * gg + TO_SRGB[2] * bb) * inv)
                        band[o + 1] = enc((TO_SRGB[3] * rr + TO_SRGB[4] * gg + TO_SRGB[5] * bb) * inv)
                        band[o + 2] = enc((TO_SRGB[6] * rr + TO_SRGB[7] * gg + TO_SRGB[8] * bb) * inv)
                    }
                } else encodeRow(buf, w, y, white, band, r * w * 3)
            }
            val top = if (y0 == 0) 0 else MARGIN
            val bot = if (y0 + th >= h) minOf(th, h - y0) else th - MARGIN
            val rowFrom = maxOf(y0 + top, doneRows); val rowTo = y0 + bot
            ys.getOrNull(bi + 1)?.let { next ->
                if (next < rowTo) {
                    savedFrom = next; savedTo = rowTo
                    saved = FloatArray((savedTo - savedFrom) * w * 3)
                    for (i in saved.indices) saved[i] = buf.get(savedFrom * w * 3 + i)
                }
            }
            var doneCols = 0
            for (x0 in xs) {
                // the tile, channel by channel (columns past the edge repeat the last one)
                for (r in 0 until th) for (c in 0 until tw) {
                    val o = (r * w + minOf(x0 + c, w - 1)) * 3
                    for (ch in 0..2) tile[(ch * th + r) * tw + c] = band[o + ch]
                }
                // its noise map: per 2×2 block, by the block's brightness
                for (by in 0 until th / 2) for (bx in 0 until tw / 2) {
                    var lum = 0f
                    for (ch in 0..2) for (dy in 0..1) for (dx in 0..1) lum += tile[(ch * th + by * 2 + dy) * tw + bx * 2 + dx]
                    lum /= 12f
                    map[by * (tw / 2) + bx] = if (follow) {
                        val f = (lum * BANDS - 0.5f).coerceIn(0f, (BANDS - 1).toFloat())
                        val i0 = f.toInt(); val i1 = minOf(i0 + 1, BANDS - 1); val u = f - i0
                        (noise.bands[i0] * (1 - u) + noise.bands[i1] * u) * scale
                    } else noise.level * scale
                }
                val out = model(tile, map, th, tw); tiles++
                val left = if (x0 == 0) 0 else MARGIN
                val right = if (x0 + tw >= w) minOf(tw, w - x0) else tw - MARGIN
                val colFrom = maxOf(x0 + left, doneCols); val colTo = x0 + right
                // only the change goes back, in linear ProPhoto light
                for (y in rowFrom until rowTo) for (x in colFrom until colTo) {
                    val r = y - y0; val c = x - x0
                    val i = (y * w + x) * 3
                    val dr = (dec(out[(0 * th + r) * tw + c]) - dec(tile[(0 * th + r) * tw + c])) * white
                    val dg = (dec(out[(1 * th + r) * tw + c]) - dec(tile[(1 * th + r) * tw + c])) * white
                    val db = (dec(out[(2 * th + r) * tw + c]) - dec(tile[(2 * th + r) * tw + c])) * white
                    buf.put(i, buf.get(i) + TO_PRO[0] * dr + TO_PRO[1] * dg + TO_PRO[2] * db)
                    buf.put(i + 1, buf.get(i + 1) + TO_PRO[3] * dr + TO_PRO[4] * dg + TO_PRO[5] * db)
                    buf.put(i + 2, buf.get(i + 2) + TO_PRO[6] * dr + TO_PRO[7] * dg + TO_PRO[8] * db)
                }
                doneCols = colTo
            }
            doneRows = rowTo
        }
        return Result(white, noise, level, tiles)
    }
}
