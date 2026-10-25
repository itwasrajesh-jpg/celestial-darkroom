package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A lith print's settings (63a), kept with each photo like its fog colour.
 *
 * [paper] "" is no lith (an ordinary print). [snatch] is when the print is pulled from the
 * developer: 0 early (soft, mostly the warm slow tones) … 1 late (the shadows have raced to black).
 */
data class LithLook(
    val paper: String = "",
    val snatch: Float = 0.5f,
    /** Grain: 0 none … 1 heavy (0.5 is the approved proof). */
    val grit: Float = 0.5f,
    /** Edges burned in: 0 none … 1 strong. */
    val burn: Float = 0.5f,
    /** The soft smoky halo round the highlights: 0 none … 1 strong. */
    val glow: Float = 0.5f,
    /** A margin of bare paper round the picture, on the full-size print. */
    val border: Boolean = true,
) {
    val on: Boolean get() = paper in Lith.PAPERS

    fun key(): String = listOf("v1", paper, "%.3f".format(Locale.US, snatch), "%.3f".format(Locale.US, grit),
        "%.3f".format(Locale.US, burn), "%.3f".format(Locale.US, glow), if (border) "1" else "0").joinToString("|")

    companion object {
        fun parse(s: String?): LithLook {
            if (s.isNullOrEmpty()) return LithLook()
            return runCatching {
                val p = s.split("|")
                require(p[0] == "v1")
                LithLook(p[1], p[2].toFloat().coerceIn(0f, 1f), p[3].toFloat().coerceIn(0f, 1f),
                    p[4].toFloat().coerceIn(0f, 1f), p[5].toFloat().coerceIn(0f, 1f), p[6] == "1")
            }.getOrDefault(LithLook())
        }
    }
}

/** Each photo's lith settings, kept beside it like its masks. */
object LithLooks {
    private const val FILE = "latent_lith"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun photos(context: Context): Set<String> = prefs(context).all.keys
    fun load(context: Context, photo: Uri): LithLook = LithLook.parse(prefs(context).getString(photo.toString(), null))
    fun save(context: Context, photo: Uri, look: LithLook) {
        prefs(context).edit().apply { if (look == LithLook()) remove(photo.toString()) else putString(photo.toString(), look.key()) }.apply()
    }
}

/**
 * Lith printing (63a): the finished print made again on lith paper, after everything else.
 *
 * A lith print is ordinary paper given 2–3 stops too much light, developed in very weak lith
 * developer and pulled out ("snatched") as the shadows race to black. Two developments at once:
 *  - slow: fine silver, soft and low in contrast, coloured by the paper (cream highlights, the
 *    colour in the mid-tones: muted tan, salmon or olive);
 *  - infectious: once a place starts going black it races and spreads into its neighbours —
 *    hard, deep, near-neutral blacks (colder the later the pull), with clumpy grain where it began.
 * Plus what the printer does by hand: the edges burned in, and the highlights' soft smoky glow.
 *
 * Tuned against real lith prints (the user's Google screenshots: Lomography, Gavin Lyons, JLA
 * Analog, MorseBlog; and the Unblinking Eye / Fotospeed LD20 descriptions) and approved as proof
 * v3, `lith-print/scripts/lith.py`. This is that proof made to work on a print of any size in one
 * pass per pixel: the fields that need the neighbours (the spreading darkness, the halo) are worked
 * out on a coarse grid over the whole picture, and the grain is hashed value noise instead of
 * blurred white noise — `lith-print/scripts/lith_port.py` is this file in Python, checked against
 * the proof and against this code.
 *
 * Everything is measured against the WHOLE picture (its long edge, its centre), so a zoomed part
 * or the quick centre pass is the same print, cut — see [apply]'s frame.
 */
object Lith {
    private val LUM = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    /** The coarse grid's long edge, in cells, over the whole picture. */
    private const val GRID = 512
    private const val RAMP = 1024
    private const val LUT = 4096
    /** The value noise's own spread (measured over 4 M samples), so the grain has unit spread. */
    private const val NOISE_STD = 0.4296f

    /** Paper tones by density (0 paper white … 1 the deepest slow-developed tone), sRGB 0..255. */
    val PAPERS: Map<String, Array<FloatArray>> = mapOf(
        "tan" to arrayOf(floatArrayOf(0.00f, 244f, 238f, 226f), floatArrayOf(0.12f, 232f, 222f, 204f), floatArrayOf(0.30f, 206f, 190f, 166f),
            floatArrayOf(0.48f, 168f, 150f, 126f), floatArrayOf(0.65f, 124f, 109f, 92f), floatArrayOf(0.82f, 82f, 72f, 62f), floatArrayOf(1.00f, 50f, 44f, 39f)),
        "salmon" to arrayOf(floatArrayOf(0.00f, 246f, 238f, 230f), floatArrayOf(0.12f, 238f, 220f, 206f), floatArrayOf(0.30f, 216f, 184f, 164f),
            floatArrayOf(0.48f, 180f, 140f, 120f), floatArrayOf(0.65f, 134f, 98f, 84f), floatArrayOf(0.82f, 88f, 64f, 56f), floatArrayOf(1.00f, 52f, 40f, 37f)),
        "olive" to arrayOf(floatArrayOf(0.00f, 240f, 238f, 228f), floatArrayOf(0.12f, 226f, 224f, 210f), floatArrayOf(0.30f, 196f, 194f, 178f),
            floatArrayOf(0.48f, 154f, 153f, 138f), floatArrayOf(0.65f, 110f, 110f, 100f), floatArrayOf(0.82f, 72f, 73f, 68f), floatArrayOf(1.00f, 44f, 46f, 44f)),
    )
    /** The raced black: a touch warm where it is just starting, near-neutral where it has fully run. */
    private val BLACK_WARM = mapOf("tan" to floatArrayOf(36f, 31f, 27f), "salmon" to floatArrayOf(40f, 30f, 28f), "olive" to floatArrayOf(30f, 32f, 30f))
    private val BLACK_COLD = mapOf("tan" to floatArrayOf(22f, 21f, 20f), "salmon" to floatArrayOf(24f, 21f, 20f), "olive" to floatArrayOf(20f, 22f, 24f))

    /**
     * The paper's white as measured on the last whole print: a part of the picture (a zoom, the
     * quick centre pass) is printed to the same white, not to its own brightest light.
     */
    @Volatile var lastWhite = 0.6f

    /** The paper colour for the border and the step's frame, sRGB 0..255. */
    fun paperWhite(paper: String): FloatArray = (PAPERS[paper] ?: PAPERS.getValue("tan"))[0].copyOfRange(1, 4)

    private fun srgbDecode(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }

    /**
     * Makes the print a lith print, in place. [data]: the engine's result, three floats per pixel,
     * [w] × [h], encoded in [cs]. [frame]: which part of the whole picture this is (null: all of
     * it — the paper's white is then measured, and remembered for the parts).
     */
    fun apply(data: ByteBuffer, w: Int, h: Int, cs: ColorSpace, look: LithLook, frame: Region? = null) {
        if (!look.on || w <= 0 || h <= 0) return
        val fr = frame ?: Region(0f, 0f, 1f, 1f)
        val ww = w / (fr.u1 - fr.u0); val wh = h / (fr.v1 - fr.v0)   // the whole picture, in these pixels
        val long = max(ww, wh)
        val dec = FloatArray(LUT + 1) { Saturation.decode(cs, it.toFloat() / LUT) }
        val enc = FloatArray(LUT + 1) { val s = it.toFloat() / LUT; Saturation.encode(cs, s * s) }
        // the space's linear RGB to linear sRGB, where the paper's colours are, and back
        val toS = mul(Saturation.fromLms(ColorSpace.SRGB), Saturation.toLms(cs))
        val fromS = mul(Saturation.fromLms(cs), Saturation.toLms(ColorSpace.SRGB))
        val lumW = FloatArray(3) { c -> LUM[0] * toS[c] + LUM[1] * toS[3 + c] + LUM[2] * toS[6 + c] }
        val buf = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun lin(v: Float): Float { val x = v.coerceIn(0f, 1f) * LUT; val i = min(x.toInt(), LUT - 1); val t = x - i; return dec[i] + (dec[i + 1] - dec[i]) * t }

        // 1. the coarse grid: the picture's light, cell by cell
        val cell = long / GRID
        val gw = max(1, ceil(w / cell).toInt()); val gh = max(1, ceil(h / cell).toInt())
        val gsum = DoubleArray(gw * gh); val gcnt = IntArray(gw * gh)
        val row = FloatArray(w * 3)
        for (y in 0 until h) {
            buf.position(y * w * 3); buf.get(row)
            val gy = min((y / cell).toInt(), gh - 1) * gw
            for (x in 0 until w) {
                val o = x * 3
                val l = lumW[0] * lin(row[o]) + lumW[1] * lin(row[o + 1]) + lumW[2] * lin(row[o + 2])
                val k = gy + min((x / cell).toInt(), gw - 1)
                gsum[k] += l; gcnt[k]++
            }
        }
        val gl = FloatArray(gw * gh) { (gsum[it] / max(gcnt[it], 1)).toFloat() }
        val white = if (frame == null) quantile(gl, 0.995f).coerceIn(0.6f, 1f).also { lastWhite = it } else lastWhite
        // 2. the grid's print tones, the halo round the highlights, and the darkness that spreads
        val gs = FloatArray(gw * gh) { k ->
            val u = fr.u0 + min(((k % gw) + 0.5f) * cell, w.toFloat()) / ww
            val v = fr.v0 + min(((k / gw) + 0.5f) * cell, h.toFloat()) / wh
            tone(gl[k], white) * burnAt(u, v, look.burn)
        }
        val halo = gauss(FloatArray(gw * gh) { ((gs[it] - 0.55f) / 0.45f).coerceIn(0f, 1f).toDouble().pow(1.5).toFloat() }, gw, gh, 0.62f * 0.012f * GRID)
        val near = gauss(FloatArray(gw * gh) { 1f - (gs[it] + look.glow * 0.22f * halo[it] * (1f - gs[it])).coerceIn(0f, 1f) }, gw, gh, 0.62f * 0.004f * GRID)

        // 3. the paper's colours, as linear sRGB tables
        val table = PAPERS.getValue(look.paper)
        val ramp = FloatArray((RAMP + 1) * 3)
        for (i in 0..RAMP) {
            val d = i.toFloat() / RAMP
            var j = 0; while (j < table.size - 2 && d > table[j + 1][0]) j++
            val t = ((d - table[j][0]) / (table[j + 1][0] - table[j][0])).coerceIn(0f, 1f)
            for (c in 0 until 3) ramp[i * 3 + c] = srgbDecode((table[j][c + 1] + (table[j + 1][c + 1] - table[j][c + 1]) * t) / 255f)
        }
        val bw = FloatArray(3) { srgbDecode(BLACK_WARM.getValue(look.paper)[it] / 255f) }
        val bc = FloatArray(3) { srgbDecode(BLACK_COLD.getValue(look.paper)[it] / 255f) }

        // 4. every pixel on its own, rows in bands, one per core: the same result whatever the split
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(h)
        val workers = (0 until bands).map { b ->
            Thread {
                rows(data, w, h * b / bands, h * (b + 1) / bands, fr, ww, wh, long, cell, white, look,
                    halo, near, gw, gh, ramp, bw, bc, dec, enc, lumW, fromS)
            }.apply { start() }
        }
        workers.forEach { it.join() }
    }

    private fun rows(data: ByteBuffer, w: Int, y0: Int, y1: Int, fr: Region, ww: Float, wh: Float, long: Float, cell: Float,
                     white: Float, look: LithLook, halo: FloatArray, near: FloatArray, gw: Int, gh: Int,
                     ramp: FloatArray, bw: FloatArray, bc: FloatArray, dec: FloatArray, enc: FloatArray,
                     lumW: FloatArray, fromS: FloatArray) {
        val f = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun lin(v: Float): Float { val x = v.coerceIn(0f, 1f) * LUT; val i = min(x.toInt(), LUT - 1); val t = x - i; return dec[i] + (dec[i + 1] - dec[i]) * t }
        fun gam(l: Float): Float { val x = sqrt(l.coerceIn(0f, 1f)) * LUT; val i = min(x.toInt(), LUT - 1); val t = x - i; return enc[i] + (enc[i + 1] - enc[i]) * t }
        val c1 = 2.3f * 0.003f * long; val c2 = max(2.3f * 0.0012f * long, 1f)
        val t0 = 0.80f - 0.25f * look.snatch
        val coldK = 0.55f + 0.45f * look.snatch
        val px = FloatArray(w * 3)
        for (y in y0 until y1) {
            f.position(y * w * 3); f.get(px)
            val py = y + 0.5f
            val v = fr.v0 + py / wh
            val gyy = py / cell
            for (x in 0 until w) {
                val o = x * 3
                val pxx = x + 0.5f
                val u = fr.u0 + pxx / ww
                val gx = pxx / cell
                val l = lumW[0] * lin(px[o]) + lumW[1] * lin(px[o + 1]) + lumW[2] * lin(px[o + 2])
                var s = tone(l, white) * burnAt(u, v, look.burn)
                s = (s + look.glow * 0.22f * bilinear(halo, gw, gh, gx, gyy) * (1f - s)).coerceIn(0f, 1f)
                val shadow = 1f - s
                val dSlow = shadow.toDouble().pow(1.15).toFloat()
                // the grain, fixed to the whole picture so a zoomed part shows the same grain
                val wx = u * ww; val wy = v * wh
                val g = 0.6f * vnoise(wx, wy, c1, 11) / NOISE_STD + 0.4f * vnoise(wx, wy, c2, 23) / NOISE_STD
                val drive = max(shadow, 0.6f * shadow + 0.4f * bilinear(near, gw, gh, gx, gyy)) + look.grit * 0.035f * g
                var inf = ((drive - t0) / 0.22f + 0.5f).coerceIn(0f, 1f); inf = inf * inf * (3f - 2f * inf)
                val ri = (dSlow * RAMP).roundToInt().coerceIn(0, RAMP) * 3
                val cold = (inf * inf * coldK).coerceIn(0f, 1f)
                var r = ramp[ri] * (1f - inf) + (bw[0] * (1f - cold) + bc[0] * cold) * inf
                var gg = ramp[ri + 1] * (1f - inf) + (bw[1] * (1f - cold) + bc[1] * cold) * inf
                var b = ramp[ri + 2] * (1f - inf) + (bw[2] * (1f - cold) + bc[2] * cold) * inf
                // visible grain: strongest in the mid-tones and shadows, faint in the clean highlights and solid blacks
                val lo = (LUM[0] * r + LUM[1] * gg + LUM[2] * b).coerceIn(0f, 1f).toDouble().pow(1 / 2.2).toFloat()
                val wt = (lo * 3f).coerceIn(0f, 1f) * ((1f - lo) * 1.6f).coerceIn(0f, 1f)
                val k = exp(look.grit * 0.42f * wt * g)
                r *= k; gg *= k; b *= k
                px[o] = gam(fromS[0] * r + fromS[1] * gg + fromS[2] * b)
                px[o + 1] = gam(fromS[3] * r + fromS[4] * gg + fromS[5] * b)
                px[o + 2] = gam(fromS[6] * r + fromS[7] * gg + fromS[8] * b)
            }
            f.position(y * w * 3); f.put(px)
        }
    }

    /** The paper sees the picture's tones, printed for its own range: 0 black … 1 paper white. */
    private fun tone(l: Float, white: Float): Float = (l / white).coerceIn(0f, 1f).toDouble().pow(1 / 2.2).toFloat()

    /** Burning in the edges: the printer gives the edges more light, so they print darker. */
    private fun burnAt(u: Float, v: Float, burn: Float): Float {
        val r = hypot(u - 0.5f, v - 0.5f) / 0.70710678f
        val e = ((r - 0.35f) / 0.65f).coerceIn(0f, 1f)
        return 1f - 0.55f * burn * e.toDouble().pow(1.6).toFloat()
    }

    private fun quantile(a: FloatArray, q: Float): Float {
        val s = a.copyOf(); s.sort()
        val x = q * (s.size - 1); val i = min(x.toInt(), s.size - 1); val j = min(i + 1, s.size - 1)
        return s[i] + (s[j] - s[i]) * (x - i)
    }

    /** A separable gaussian on the grid, edges held. */
    private fun gauss(a: FloatArray, gw: Int, gh: Int, sigma: Float): FloatArray {
        if (sigma < 0.3f) return a
        val r = ceil(3 * sigma).toInt()
        val k = FloatArray(2 * r + 1) { exp(-0.5f * ((it - r) / sigma).let { t -> t * t }) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(gw * gh); val out = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0f; for (i in -r..r) s += k[i + r] * a[y * gw + (x + i).coerceIn(0, gw - 1)]; tmp[y * gw + x] = s
        }
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0f; for (i in -r..r) s += k[i + r] * tmp[(y + i).coerceIn(0, gh - 1) * gw + x]; out[y * gw + x] = s
        }
        return out
    }

    /** The grid at grid position ([gx], [gy]) (cell units, cell centres at +0.5), smoothly. */
    private fun bilinear(g: FloatArray, gw: Int, gh: Int, gx: Float, gy: Float): Float {
        val x = (gx - 0.5f).coerceIn(0f, (gw - 1).toFloat()); val y = (gy - 0.5f).coerceIn(0f, (gh - 1).toFloat())
        val x0 = min(x.toInt(), max(gw - 2, 0)); val y0 = min(y.toInt(), max(gh - 2, 0))
        val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
        val fx = x - x0; val fy = y - y0
        val a = g[y0 * gw + x0] * (1 - fx) + g[y0 * gw + x1] * fx
        val b = g[y1 * gw + x0] * (1 - fx) + g[y1 * gw + x1] * fx
        return a * (1 - fy) + b * fy
    }

    /** −1..1 from a lattice point: a small integer hash, the same on every phone. */
    private fun hash(ix: Int, iy: Int, seed: Int): Float {
        var h = (ix * -0x72594cbd) xor (iy * -0x27e9c7bf) xor (seed * -0x34e54ce1)
        h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
        return (h and 0xFFFFFF).toFloat() / 16777215f * 2f - 1f
    }

    /** Value noise: lattice values every [cell] pixels, smoothly between. */
    private fun vnoise(px: Float, py: Float, cell: Float, seed: Int): Float {
        val x = px / cell; val y = py / cell
        val xf = floor(x); val yf = floor(y)
        var fx = x - xf; var fy = y - yf
        fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy)
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
        return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy
    }

    /**
     * The paper border (full-size prints only: previews stay the bare picture, so painting lines up):
     * a margin of bare paper, 4.5% of the long edge, in [cs]. Returns the new buffer and its size.
     */
    fun border(data: ByteBuffer, w: Int, h: Int, cs: ColorSpace, look: LithLook): Triple<ByteBuffer, Int, Int> {
        if (!look.on || !look.border) return Triple(data, w, h)
        val m = (0.045f * max(w, h)).roundToInt()
        val fw = w + 2 * m; val fh = h + 2 * m
        val pw = paperWhite(look.paper)
        val fromS = mul(Saturation.fromLms(cs), Saturation.toLms(ColorSpace.SRGB))
        val ls = FloatArray(3) { srgbDecode(pw[it] / 255f) }
        val paper = FloatArray(3) { c -> Saturation.encode(cs, (fromS[c * 3] * ls[0] + fromS[c * 3 + 1] * ls[1] + fromS[c * 3 + 2] * ls[2]).coerceIn(0f, 1f)) }
        val out = Develop.pictureBuffer(fw * fh * 12L)   // 81b: native when big; the render gives it back
        val src = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val dst = out.asFloatBuffer()
        val line = FloatArray(fw * 3) { paper[it % 3] }
        val row = FloatArray(w * 3)
        for (y in 0 until fh) {
            dst.position(y * fw * 3); dst.put(line)
            val sy = y - m
            if (sy in 0 until h) { src.position(sy * w * 3); src.get(row); dst.position((y * fw + m) * 3); dst.put(row) }
        }
        return Triple(out, fw, fh)
    }
}
