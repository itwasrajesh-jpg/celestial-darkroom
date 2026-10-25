package com.celestial.latent.camera

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Merging a burst roll into one RAW (68b), pure Kotlin. Two kinds:
 *
 *  - [burst]: every frame lined up on the sharpest one and averaged, so noise drops (about 4x
 *    cleaner for 16 frames) while the picture stays as sharp as that frame. Tiles where something
 *    moved are left out per frame. The same merge on in-sensor x2/x4 frames is "super zoom": real
 *    sensor pixels from the crop, and the burst cleans their noise.
 *  - [focus]: a focus bracket stacked into one picture that is sharp front to back. Each spot is
 *    taken from the frames where it is sharpest, with soft blends so there are no seams.
 *
 * Everything stays RAW (Bayer, four colour planes at half size), so the result develops with the
 * film like any other RAW. Lining up: a coarse shift from [Align]'s pyramid, then the shift of
 * many small tiles to a fraction of a pixel, fitted with an affine (shift, size change from focus
 * breathing, a little rotation). Proven against the Python proof (focus-stack/scripts/fstack.py)
 * and on known answers before it was used (68b).
 */
object BurstMerge {

    /** [baseline] (86b): stops the result is to be brightened by when developed (a bracket is kept at its darkest frame's level). */
    class Result(val pixels: ShortArray, val scale: Int, val ref: Int, val used: Int, val note: String, val baseline: Double = 0.0)

    // ---- planes and green ---------------------------------------------------------------------

    /** Bayer (w x h) -> four planes (w/2 x h/2), plane k = (y % 2) * 2 + x % 2. */
    fun planes(px: ShortArray, w: Int, h: Int): Array<FloatArray> {
        val pw = w / 2; val ph = h / 2
        val out = Array(4) { FloatArray(pw * ph) }
        par(ph) { y ->
            for (k in 0 until 4) {
                val src = (2 * y + k / 2) * w + (k % 2); val o = out[k]; val row = y * pw
                for (x in 0 until pw) o[row + x] = (px[src + 2 * x].toInt() and 0xFFFF).toFloat()
            }
        }
        return out
    }

    /** Green as sqrt of 0..1 (noise roughly even from shadows to highlights), from the two green planes. */
    fun green(pl: Array<FloatArray>, cfa: ByteArray, black: Int, white: Int): FloatArray {
        val gi = (0 until 4).filter { cfa[it].toInt() == 1 }
        val a = pl[gi[0]]; val b = pl[gi.getOrElse(1) { gi[0] }]
        val inv = 1f / (white - black)
        return FloatArray(a.size) { i -> sqrt(((0.5f * (a[i] + b[i]) - black) * inv).coerceIn(0f, 1f)) }
    }

    // ---- affine maps: reference (x, y) -> frame (a0 x + a1 y + a2, a3 x + a4 y + a5) -----------

    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

    /** a after b: first b, then a. */
    fun compose(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] + a[1] * b[3], a[0] * b[1] + a[1] * b[4], a[0] * b[2] + a[1] * b[5] + a[2],
        a[3] * b[0] + a[4] * b[3], a[3] * b[1] + a[4] * b[4], a[3] * b[2] + a[4] * b[5] + a[5])

    /** Bilinear sample of [src] at A(x, y) for every output pixel (edges repeat). */
    fun warp(src: FloatArray, w: Int, h: Int, a: DoubleArray, dst: FloatArray = FloatArray(w * h)): FloatArray {
        par(h) { y ->
            var sx = a[1] * y + a[2]; var sy = a[4] * y + a[5]
            val row = y * w
            for (x in 0 until w) {
                val fx = sx.coerceIn(0.0, w - 1.001); val fy = sy.coerceIn(0.0, h - 1.001)
                val ix = fx.toInt(); val iy = fy.toInt(); val tx = (fx - ix).toFloat(); val ty = (fy - iy).toFloat()
                val i = iy * w + ix
                val top = src[i] + (src[i + 1] - src[i]) * tx
                val bot = src[i + w] + (src[i + w + 1] - src[i + w]) * tx
                dst[row + x] = top + (bot - top) * ty
                sx += a[0]; sy += a[3]
            }
        }
        return dst
    }

    /**
     * Like [warp] but with a Catmull-Rom (cubic) kernel: resampling a frame softens it less, which
     * matters for the colour planes that end up in the picture. Bilinear stays for green used only to
     * measure.
     */
    fun warpCubic(src: FloatArray, w: Int, h: Int, a: DoubleArray, dst: FloatArray = FloatArray(w * h)): FloatArray {
        par(h) { y ->
            var sx = a[1] * y + a[2]; var sy = a[4] * y + a[5]
            val row = y * w
            val kx = FloatArray(4); val ky = FloatArray(4)
            for (x in 0 until w) {
                val fx = sx.coerceIn(0.0, w - 1.001); val fy = sy.coerceIn(0.0, h - 1.001)
                val ix = fx.toInt(); val iy = fy.toInt()
                cubic((fx - ix).toFloat(), kx); cubic((fy - iy).toFloat(), ky)
                var v = 0f
                for (j in 0 until 4) {
                    val r = (iy - 1 + j).coerceIn(0, h - 1) * w
                    var s = 0f
                    for (i in 0 until 4) s += kx[i] * src[r + (ix - 1 + i).coerceIn(0, w - 1)]
                    v += ky[j] * s
                }
                dst[row + x] = v
                sx += a[0]; sy += a[3]
            }
        }
        return dst
    }

    private fun cubic(t: Float, k: FloatArray) {
        val t2 = t * t; val t3 = t2 * t
        k[0] = 0.5f * (-t3 + 2 * t2 - t); k[1] = 0.5f * (3 * t3 - 5 * t2 + 2)
        k[2] = 0.5f * (-3 * t3 + 4 * t2 + t); k[3] = 0.5f * (t3 - t2)
    }

    /**
     * The affine that maps [ref] onto [mov] (both green, w x h). [prior] is a first guess (shift in
     * pixels). Tiles of 48 px are matched to a fraction of a pixel; the affine is fitted to them with
     * outliers dropped. Too few good tiles: the plain shift.
     */
    fun align(ref: FloatArray, mov: FloatArray, w: Int, h: Int, prior: DoubleArray): DoubleArray = align(Prepared(ref, w, h), mov, prior)

    /** A green image ready to be aligned to, kept so a reference is prepared once, not per frame. */
    class Prepared(val g: FloatArray, val w: Int, val h: Int) {
        val w2 = w / 2; val h2 = h / 2
        val blurred: FloatArray = blur(g, w, h, 1f)
        val halfBlurred: FloatArray = blur(half(g, w, h).first, w2, h2, 1f)
    }

    fun align(ref: Prepared, mov: FloatArray, prior: DoubleArray): DoubleArray {
        val w = ref.w; val h = ref.h
        val first = matchTiles(ref.g, mov, w, h, prior)
        // Then the whole picture at once (Lucas-Kanade on the affine): first at half this size, then here.
        val a2 = refine(ref.halfBlurred, blur(half(mov, w, h).first, ref.w2, ref.h2, 1f), ref.w2, ref.h2, scaleMap(first, 0.5), 10)
        return refine(ref.blurred, blur(mov, w, h, 1f), w, h, scaleMap(a2, 2.0), 3)
    }

    /** The same affine for an image [k] times the size. */
    private fun scaleMap(a: DoubleArray, k: Double) = doubleArrayOf(a[0], a[1], a[2] * k, a[3], a[4], a[5] * k)

    internal fun half(src: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val nw = w / 2; val nh = h / 2
        val out = FloatArray(nw * nh)
        par(nh) { y -> for (x in 0 until nw) { val i = 2 * y * w + 2 * x; out[y * nw + x] = 0.25f * (src[i] + src[i + 1] + src[i + w] + src[i + w + 1]) } }
        return Triple(out, nw, nh)
    }

    /**
     * Gauss-Newton on the six affine numbers: warp [mov] by the current guess, compare with [ref], and
     * step to make the difference smaller. Steps are taken in centred, scaled coordinates so the six
     * numbers are of similar size.
     */
    fun refine(ref: FloatArray, mov: FloatArray, w: Int, h: Int, start: DoubleArray, iters: Int): DoubleArray {
        val a = start.copyOf()
        val cx = w / 2.0; val cy = h / 2.0; val sc = w / 2.0
        val wm = FloatArray(w * h)
        val m = 8   // margin
        repeat(iters) {
            warp(mov, w, h, a, wm)
            val hh = Array(6) { DoubleArray(6) }; val b = DoubleArray(6); val j = DoubleArray(6)
            var y = m
            while (y < h - m) {
                val v = (y - cy) / sc
                var x = m
                while (x < w - m) {
                    val sx = a[0] * x + a[1] * y + a[2]; val sy = a[3] * x + a[4] * y + a[5]
                    if (sx >= 1 && sy >= 1 && sx < w - 2 && sy < h - 2) {
                        val i = y * w + x
                        val gx = 0.5 * (wm[i + 1] - wm[i - 1]); val gy = 0.5 * (wm[i + w] - wm[i - w])
                        val r = (wm[i] - ref[i]).toDouble()
                        val u = (x - cx) / sc
                        j[0] = gx * u; j[1] = gx * v; j[2] = gx; j[3] = gy * u; j[4] = gy * v; j[5] = gy
                        for (p in 0 until 6) { b[p] += j[p] * r; val jp = j[p]; val row = hh[p]; for (q in p until 6) row[q] += jp * j[q] }
                    }
                    x += 2
                }
                y += 2
            }
            for (p in 0 until 6) for (q in 0 until p) hh[p][q] = hh[q][p]
            val d = solve(hh, b) ?: return a
            // X_new = X + q0 u + q1 v + q2 (and the same for Y), back to plain affine numbers
            for (k in 0..1) {
                val q0 = -d[3 * k]; val q1 = -d[3 * k + 1]; val q2 = -d[3 * k + 2]
                a[3 * k] += q0 / sc; a[3 * k + 1] += q1 / sc; a[3 * k + 2] += q2 - q0 * cx / sc - q1 * cy / sc
            }
            if (abs(d[2]) + abs(d[5]) < 1e-3 && abs(d[0]) + abs(d[1]) + abs(d[3]) + abs(d[4]) < 1e-3) return a
        }
        return a
    }

    /** Gaussian elimination with pivoting, n x n. */
    private fun solve(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size; val a = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) m[r][c] else b[r] } }
        for (c in 0 until n) {
            var p = c; for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[p]; a[p] = a[c]; a[c] = t
            for (r in 0 until n) if (r != c) { val f = a[r][c] / a[c][c]; for (k in c..n) a[r][k] -= f * a[c][k] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /** Tile matching (whole pixels, then a parabola for the fraction), fitted with an affine. */
    private fun matchTiles(ref: FloatArray, mov: FloatArray, w: Int, h: Int, prior: DoubleArray): DoubleArray {
        val t = 48; val step = 80; val r = 4
        val cx = ArrayList<Double>(); val cy = ArrayList<Double>(); val dx = ArrayList<Double>(); val dy = ArrayList<Double>(); val texture = ArrayList<Double>()
        val tiles = ArrayList<IntArray>()
        var y0 = step / 2; while (y0 + t < h - step / 2) { var x0 = step / 2; while (x0 + t < w - step / 2) { tiles += intArrayOf(x0, y0); x0 += step }; y0 += step }
        val res = arrayOfNulls<DoubleArray>(tiles.size)
        par(tiles.size) { k ->
            val (x0, y0) = tiles[k].let { it[0] to it[1] }
            val ccx = x0 + t / 2.0; val ccy = y0 + t / 2.0
            val px = prior[0] * ccx + prior[1] * ccy + prior[2] - ccx; val py = prior[3] * ccx + prior[4] * ccy + prior[5] - ccy
            val bx = px.roundToInt(); val by = py.roundToInt()
            if (x0 + bx - r < 0 || y0 + by - r < 0 || x0 + t + bx + r >= w || y0 + t + by + r >= h) return@par
            // texture: mean abs horizontal + vertical difference
            var tex = 0.0
            for (y in y0 until y0 + t step 2) for (x in x0 until x0 + t step 2) { val i = y * w + x; tex += abs(ref[i + 1] - ref[i]) + abs(ref[i + w] - ref[i]) }
            val n = 2 * r + 1
            val sad = DoubleArray(n * n)
            for (oy in -r..r) for (ox in -r..r) {
                var s = 0.0
                for (y in y0 until y0 + t step 2) {
                    val ra = y * w; val rb = (y + by + oy) * w + bx + ox
                    for (x in x0 until x0 + t step 2) s += abs(ref[ra + x] - mov[rb + x])
                }
                sad[(oy + r) * n + ox + r] = s
            }
            var bi = 0; for (i in sad.indices) if (sad[i] < sad[bi]) bi = i
            val iy = bi / n; val ix = bi % n
            if (ix == 0 || iy == 0 || ix == n - 1 || iy == n - 1) return@par   // minimum on the edge: not trustworthy
            fun para(a: Double, b: Double, c: Double): Double { val d = a - 2 * b + c; return if (d > 1e-12) 0.5 * (a - c) / d else 0.0 }
            val sx = para(sad[iy * n + ix - 1], sad[bi], sad[iy * n + ix + 1])
            val sy = para(sad[(iy - 1) * n + ix], sad[bi], sad[(iy + 1) * n + ix])
            res[k] = doubleArrayOf(ccx, ccy, bx + ix - r + sx, by + iy - r + sy, tex)
        }
        res.filterNotNull().forEach { cx += it[0]; cy += it[1]; dx += it[2]; dy += it[3]; texture += it[4] }
        if (cx.isEmpty()) return prior
        // Drop the flattest third (sky, walls: their match is noise).
        val texCut = texture.sorted()[texture.size / 3]
        var keep = cx.indices.filter { texture[it] >= texCut }.toMutableList()
        var a: DoubleArray? = null
        repeat(3) {
            if (keep.size < 8) return@repeat
            a = fitAffine(keep.map { cx[it] }, keep.map { cy[it] }, keep.map { cx[it] + dx[it] }, keep.map { cy[it] + dy[it] }) ?: return@repeat
            val m = a!!
            val resid = keep.map { i -> val ex = m[0] * cx[i] + m[1] * cy[i] + m[2] - cx[i] - dx[i]; val ey = m[3] * cx[i] + m[4] * cy[i] + m[5] - cy[i] - dy[i]; sqrt(ex * ex + ey * ey) }
            val med = resid.sorted()[resid.size / 2]
            val lim = max(0.5, 2.5 * med)
            keep = keep.filterIndexed { j, _ -> resid[j] <= lim }.toMutableList()
        }
        a?.let { return it }
        // plain shift: median of the tile shifts
        val mx = dx.sorted()[dx.size / 2]; val my = dy.sorted()[dy.size / 2]
        return doubleArrayOf(1.0, 0.0, mx, 0.0, 1.0, my)
    }

    /** Least squares (x, y) -> (u, v). */
    fun fitAffine(x: List<Double>, y: List<Double>, u: List<Double>, v: List<Double>): DoubleArray? {
        // normal equations on [x y 1], solved separately for u and v
        val m = Array(3) { DoubleArray(3) }; val bu = DoubleArray(3); val bv = DoubleArray(3)
        for (i in x.indices) {
            val r = doubleArrayOf(x[i], y[i], 1.0)
            for (p in 0 until 3) { for (q in 0 until 3) m[p][q] += r[p] * r[q]; bu[p] += r[p] * u[i]; bv[p] += r[p] * v[i] }
        }
        val su = solve3(m, bu) ?: return null; val sv = solve3(m, bv) ?: return null
        return doubleArrayOf(su[0], su[1], su[2], sv[0], sv[1], sv[2])
    }

    private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m); if (abs(d) < 1e-9) return null
        return DoubleArray(3) { c -> det(Array(3) { r -> DoubleArray(3) { k -> if (k == c) b[r] else m[r][k] } }) / d }
    }

    /** First guess of the shift between two raw frames, from [Align]'s pyramid (in half-size pixels). */
    private fun coarse(refPyr: Align.Pyramid, px: ShortArray, w: Int, h: Int): DoubleArray {
        val (dx, dy) = Align.findShift(refPyr, Align.pyramid(px, w, h))
        return doubleArrayOf(1.0, 0.0, dx.toDouble(), 0.0, 1.0, dy.toDouble())
    }

    // ---- small helpers -------------------------------------------------------------------------

    private fun par(n: Int, body: (Int) -> Unit) = IntStream.range(0, n).parallel().forEach { body(it) }

    /** Separable Gaussian blur (in place result returned as a new array). */
    fun blur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val rad = max(1, (sigma * 3).toInt())
        val k = FloatArray(2 * rad + 1) { exp(-((it - rad) * (it - rad)) / (2f * sigma * sigma)) }.let { kk -> val s = kk.sum(); FloatArray(kk.size) { kk[it] / s } }
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        par(h) { y -> val row = y * w; for (x in 0 until w) { var s = 0f; for (j in -rad..rad) s += k[j + rad] * src[row + (x + j).coerceIn(0, w - 1)]; tmp[row + x] = s } }
        par(h) { y -> val row = y * w; for (x in 0 until w) { var s = 0f; for (j in -rad..rad) s += k[j + rad] * tmp[(y + j).coerceIn(0, h - 1) * w + x]; out[row + x] = s } }
        return out
    }

    /** Bayer from four planes, times [scale] (black and white are scaled the same in the DNG). */
    fun bayer(pl: Array<FloatArray>, w: Int, h: Int, scale: Int): ShortArray {
        val pw = w / 2; val out = ShortArray(w * h)
        par(h / 2) { y ->
            for (k in 0 until 4) {
                val dst = (2 * y + k / 2) * w + (k % 2); val p = pl[k]
                for (x in 0 until pw) out[dst + 2 * x] = (p[y * pw + x] * scale).roundToInt().coerceIn(0, 65535).toShort()
            }
        }
        return out
    }

    /** How much the merged values are multiplied by: as much as 16-bit allows, at most 16 (10-bit x16, 14-bit x4). */
    fun scaleFor(white: Int) = (65535 / white).coerceIn(1, 16)

    // ---- burst / super zoom --------------------------------------------------------------------

    /**
     * Every frame lined up on frame [ref] and averaged. Per 32 px tile, a frame counts fully while it
     * agrees with the reference within 2.5x the typical difference, fades out by 5x (moved subject,
     * failed alignment). Tile weights are blended smoothly so tile edges never show.
     */
    fun burst(frames: Int, read: (Int) -> ShortArray, w: Int, h: Int, black: Int, white: Int, cfa: ByteArray, ref: Int,
              progress: (String) -> Unit = {}): Result {
        val pw = w / 2; val ph = h / 2
        progress("Reading frame ${ref + 1}…")
        val refPyr: Align.Pyramid; val sum: Array<FloatArray>
        run { val refPx = read(ref); refPyr = Align.pyramid(refPx, w, h); sum = planes(refPx, w, h) }
        val refG = green(sum, cfa, black, white)
        val prep = Prepared(refG, pw, ph)
        val wsum = FloatArray(pw * ph) { 1f }
        val t = 32; val tx = pw / t; val ty = ph / t
        var used = 1; val notes = ArrayList<String>()
        val warped = Array(4) { FloatArray(pw * ph) }
        for (i in 0 until frames) {
            if (i == ref) continue
            progress("Lining up frame ${i + 1} of $frames…")
            val px = runCatching { read(i) }.getOrNull() ?: continue
            val pl = planes(px, w, h)
            val g = green(pl, cfa, black, white)
            val a = align(prep, g, coarse(refPyr, px, w, h))
            val wg = warp(g, pw, ph, a)
            // per-tile disagreement with the reference
            val d = FloatArray(tx * ty)
            par(ty) { j -> for (k in 0 until tx) {
                var s = 0f
                for (y in j * t until (j + 1) * t step 2) for (x in k * t until (k + 1) * t step 2) s += abs(wg[y * pw + x] - refG[y * pw + x])
                d[j * tx + k] = s / (t * t / 4)
            } }
            val typical = d.sorted()[d.size / 2]
            val tw = FloatArray(tx * ty) { (2f - (d[it] / (2.5f * typical + 1e-6f) - 1f).coerceIn(0f, 1f) * 2f).coerceIn(0f, 2f) / 2f }
            val good = tw.count { it > 0.5f }
            if (good < tw.size / 4) { notes += "#${i + 1} left out"; continue }
            used++
            for (k in 0 until 4) warpCubic(pl[k], pw, ph, a, warped[k])
            par(ph) { y ->
                val fy = ((y + 0.5f) / t - 0.5f).coerceIn(0f, ty - 1f); val j0 = fy.toInt().coerceAtMost(ty - 2).coerceAtLeast(0); val vy = fy - j0
                val row = y * pw
                for (x in 0 until pw) {
                    val fx = ((x + 0.5f) / t - 0.5f).coerceIn(0f, tx - 1f); val k0 = fx.toInt().coerceAtMost(tx - 2).coerceAtLeast(0); val vx = fx - k0
                    val i0 = j0 * tx + k0
                    val top = tw[i0] + (tw[i0 + 1] - tw[i0]) * vx; val bot = tw[i0 + tx] + (tw[i0 + tx + 1] - tw[i0 + tx]) * vx
                    val wt = top + (bot - top) * vy
                    if (wt > 0f) { val p = row + x; wsum[p] += wt; for (k in 0 until 4) sum[k][p] += wt * warped[k][p] }
                }
            }
        }
        progress("Finishing…")
        val scale = scaleFor(white)
        for (k in 0 until 4) { val s = sum[k]; for (p in s.indices) s[p] /= wsum[p] }
        return Result(bayer(sum, w, h, scale), scale, ref, used, notes.joinToString(", "))
    }

    // ---- HDR bracket (86b) -----------------------------------------------------------------------

    /**
     * Green like [green], but of a frame brought to the reference's exposure ([gain] = reference
     * exposure / this frame's) and cut off at [cap], so a pair of frames clip at the same level and the
     * clipped sky of one does not look like a difference from the other.
     */
    private fun greenAt(pl: Array<FloatArray>, cfa: ByteArray, black: Int, white: Int, gain: Float, cap: Float): FloatArray {
        val gi = (0 until 4).filter { cfa[it].toInt() == 1 }
        val a = pl[gi[0]]; val b = pl[gi.getOrElse(1) { gi[0] }]
        val k = gain / (white - black)
        return FloatArray(a.size) { i -> sqrt(((0.5f * (a[i] + b[i]) - black) * k).coerceIn(0f, cap)) }
    }

    /** A raw frame at the reference's exposure, clipped at [cap] of white: for [Align]'s coarse shift only. */
    private fun leveled(px: ShortArray, black: Int, white: Int, gain: Float, cap: Float): ShortArray {
        val top = black + cap * (white - black)
        val out = ShortArray(px.size)
        par(px.size / 4096 + 1) { c ->
            val end = minOf(px.size, (c + 1) * 4096)
            for (i in c * 4096 until end) out[i] = (black + ((px[i].toInt() and 0xFFFF) - black) * gain).coerceIn(0f, top).roundToInt().toShort()
        }
        return out
    }

    /**
     * Frames of different exposure ([exposure] = shutter x ISO of each, as the camera reported it)
     * merged into one RAW with the darkest frame's highlights and the brighter frames' clean shadows.
     *
     * Every frame is brought to the darkest frame's level (divided by how much more light it got) and
     * lined up on the middle frame. Per pixel a frame counts by how much light it got (more light, less
     * noise), and not at all where any of its colours is near clipping; the darkest frame always counts
     * a little, so a spot clipped everywhere else still has its value. Per 32 px tile, a frame that
     * disagrees with the middle one (something moved) fades out, as in [burst]. The result stays at the
     * darkest frame's level, so nothing clips; [Result.baseline] says how many stops brighter the
     * brightest frame was (86c: the one the user set so the shadows look right), and the darkroom develops
     * it that bright (the DNG's BaselineExposure); the highlights then sit above white for the film's shoulder.
     *
     * [picks] (86d, the merge editor; 86e): areas whose BRIGHT parts take a darker frame's look. See
     * [applyPicks]. [upright6]: the masks are upright and the frames are stored a quarter turn round
     * (orientation 6).
     */
    fun bracket(frames: Int, read: (Int) -> ShortArray, w: Int, h: Int, black: Int, white: Int, cfa: ByteArray, exposure: DoubleArray,
                progress: (String) -> Unit = {}, picks: List<MergePicks.Pick> = emptyList(), upright6: Boolean = false): Result {
        val pw = w / 2; val ph = h / 2
        val dark = exposure.indices.minByOrNull { exposure[it] }!!
        val eDark = exposure[dark]
        // the reference: the frame nearest the middle of the bracket, in stops
        val mid = Math.sqrt(exposure.maxOrNull()!! * eDark)
        val ref = exposure.indices.minByOrNull { abs(Math.log(exposure[it] / mid)) }!!
        progress("Reading frame ${ref + 1}…")
        val refPx = read(ref)
        val refPl = planes(refPx, w, h)
        val range = (white - black).toFloat()
        val sum = Array(4) { FloatArray(pw * ph) }; val wsum = FloatArray(pw * ph)
        val t = 32; val tx = pw / t; val ty = ph / t
        val warped = Array(4) { FloatArray(pw * ph) }
        var used = 0; val notes = ArrayList<String>()
        val maps = arrayOfNulls<DoubleArray>(frames)               // each frame's lining up, for the picks (86e)
        // the darkest frame last, so its small fallback weight can see what the others left uncovered
        val order = (0 until frames).filter { it != dark } + dark
        for (i in order) {
            progress("Lining up frame ${i + 1} of $frames…")
            val px = if (i == ref) refPx else runCatching { read(i) }.getOrNull() ?: continue
            val pl = if (i == ref) refPl else planes(px, w, h)
            val toRef = (exposure[ref] / exposure[i]).toFloat()       // this frame at the reference's exposure
            val toDark = (eDark / exposure[i]).toFloat()              // this frame at the darkest frame's exposure
            val tw: FloatArray?
            val a: DoubleArray
            if (i == ref) { a = IDENTITY; tw = null }
            else {
                val cap = minOf(1f, toRef)                            // the brighter frame clips here, in reference units
                val refG = greenAt(refPl, cfa, black, white, 1f, cap)
                val g = greenAt(pl, cfa, black, white, toRef, cap)
                val prior = run {
                    val (dx, dy) = Align.findShift(Align.pyramid(leveled(refPx, black, white, 1f, cap), w, h), Align.pyramid(leveled(px, black, white, toRef, cap), w, h))
                    doubleArrayOf(1.0, 0.0, dx.toDouble(), 0.0, 1.0, dy.toDouble())
                }
                a = align(Prepared(refG, pw, ph), g, prior)
                val wg = warp(g, pw, ph, a)
                val d = FloatArray(tx * ty)
                par(ty) { j -> for (k in 0 until tx) {
                    var s = 0f
                    for (y in j * t until (j + 1) * t step 2) for (x in k * t until (k + 1) * t step 2) s += abs(wg[y * pw + x] - refG[y * pw + x])
                    d[j * tx + k] = s / (t * t / 4)
                } }
                val typical = d.sorted()[d.size / 2]
                tw = FloatArray(tx * ty) { (2f - (d[it] / (2.5f * typical + 1e-6f) - 1f).coerceIn(0f, 1f) * 2f).coerceIn(0f, 2f) / 2f }
                maps[i] = a
                if (i != dark && tw.count { it > 0.5f } < tw.size / 4) { notes += "#${i + 1} left out (moved)"; continue }
            }
            if (i == ref) maps[i] = IDENTITY
            for (k in 0 until 4) if (i == ref) System.arraycopy(pl[k], 0, warped[k], 0, pw * ph) else warpCubic(pl[k], pw, ph, a, warped[k])
            // how near clipping each spot is (its brightest colour), as a weight: 1 below 80% of white, 0 from 95%
            val hat = FloatArray(pw * ph)
            if (i == dark) hat.fill(1f) else par(ph) { y ->
                val row = y * pw
                for (x in 0 until pw) {
                    val p = row + x
                    val m = (maxOf(maxOf(warped[0][p], warped[1][p]), maxOf(warped[2][p], warped[3][p])) - black) / range
                    hat[p] = ((0.95f - m) / 0.15f).coerceIn(0f, 1f)
                }
            }
            // soften the hand-over between frames, never letting a clipped spot back in
            val soft = if (i == dark) hat else blur(hat, pw, ph, 2f).also { b -> for (p in b.indices) b[p] = minOf(b[p], hat[p]) }
            val light = 1f / toDark                                     // 1 for the darkest frame, 2 for one stop brighter, ...
            used++
            par(ph) { y ->
                val row = y * pw
                val fy = ((y + 0.5f) / t - 0.5f).coerceIn(0f, ty - 1f); val j0 = fy.toInt().coerceAtMost(ty - 2).coerceAtLeast(0); val vy = fy - j0
                for (x in 0 until pw) {
                    val p = row + x
                    var wt = soft[p] * light
                    if (tw != null) {
                        val fx = ((x + 0.5f) / t - 0.5f).coerceIn(0f, tx - 1f); val k0 = fx.toInt().coerceAtMost(tx - 2).coerceAtLeast(0); val vx = fx - k0
                        val i0 = j0 * tx + k0
                        val top = tw[i0] + (tw[i0 + 1] - tw[i0]) * vx; val bot = tw[i0 + tx] + (tw[i0 + tx + 1] - tw[i0 + tx]) * vx
                        wt *= top + (bot - top) * vy
                    }
                    if (i == dark) wt = maxOf(wt, 0.01f)
                    if (wt > 0f) { wsum[p] += wt; for (k in 0 until 4) sum[k][p] += wt * (warped[k][p] - black) * toDark }
                }
            }
        }
        progress("Finishing…")
        val scale = scaleFor(white)
        for (k in 0 until 4) { val s = sum[k]; for (p in s.indices) s[p] = s[p] / wsum[p].coerceAtLeast(1e-6f) }
        if (picks.isNotEmpty()) {
            applyPicks(sum, picks, read, maps, w, h, black, white, cfa, exposure, upright6, warped, progress)
            notes += "${picks.size} area(s) picked by hand"
        }
        for (k in 0 until 4) { val s = sum[k]; for (p in s.indices) s[p] += black }
        val span = Math.log(exposure.maxOrNull()!! / eDark) / Math.log(2.0)
        val baseline = span
        notes.add(0, "%.1f stops, kept at frame %d's level, develops +%.2f EV".format(java.util.Locale.US, span, dark + 1, baseline))
        return Result(bayer(sum, w, h, scale), scale, ref, used, notes.joinToString(", "), baseline)
    }

    /**
     * The merge editor's picks (86e), on the finished merge [m] (four planes, black taken off, at the
     * darkest frame's level). Inside an area, its bright parts are toned down toward how the picked frame
     * looked, by a gain that follows the light: a blurred brightness key ([MergePicks.Key]) of the merge
     * as the darkroom develops it (at the brightest frame's level). Where that key is at mid-grey or
     * darker nothing changes; at the picked frame's own white it is fully that frame's look
     * ([MergePicks.tone]). The toning fades over a wide soft edge round the area ([MergePicks.wide]), and
     * where it acts the pixels come from the picked frame itself (where it did not clip), so a lamp
     * shows the dark frame's own detail. 86d took the whole area at the frame's brightness, which cut a
     * dark disc into everything brushed round a lamp (phone, 25 Oct).
     */
    private fun applyPicks(m: Array<FloatArray>, picks: List<MergePicks.Pick>, read: (Int) -> ShortArray, maps: Array<DoubleArray?>,
                           w: Int, h: Int, black: Int, white: Int, cfa: ByteArray, exposure: DoubleArray, upright6: Boolean,
                           warped: Array<FloatArray>, progress: (String) -> Unit) {
        val pw = w / 2; val ph = h / 2
        val eD = exposure.minOrNull()!!; val eB = exposure.maxOrNull()!!
        val range = (white - black).toFloat()
        val gi = (0 until 4).filter { cfa[it].toInt() == 1 }; val g0 = m[gi[0]]; val g1 = m[gi.getOrElse(1) { gi[0] }]
        val toB = (eB / eD).toFloat() / range
        val key = MergePicks.Key(FloatArray(pw * ph) { 0.5f * (g0[it] + g1[it]) * toB }, pw, ph)
        val gw = picks[0].gw; val gh = picks[0].gh
        val inside = MergePicks.shares(picks.map { it.mask }).first
        val edge = MergePicks.shares(picks.map { MergePicks.wide(it.mask, gw, gh) }).first
        val logGain = FloatArray(pw * ph)
        for ((f, idx) in picks.indices.groupBy { picks[it].frame }) {
            val a = maps.getOrNull(f) ?: continue
            progress("Your picks: frame ${f + 1}…")
            val pl = runCatching { planes(read(f), w, h) }.getOrNull() ?: continue
            for (k in 0 until 4) warpCubic(pl[k], pw, ph, a, warped[k])
            val toDark = (eD / exposure[f]).toFloat()
            val r = (exposure[f] / eB).toFloat(); val lnr = kotlin.math.ln(r)
            for (ai in idx) {
                val fi = MergePicks.Field(inside[ai], gw, gh, pw, ph, upright6); val fe = MergePicks.Field(edge[ai], gw, gh, pw, ph, upright6)
                par(ph) { y -> for (x in 0 until pw) {
                    val p = y * pw + x
                    val t = MergePicks.tone(key.at(x, y), r); if (t <= 0f) continue
                    val wv = fe.at(x, y) * t; if (wv <= 0f) continue
                    logGain[p] += wv * lnr
                    val mx = (maxOf(maxOf(warped[0][p], warped[1][p]), maxOf(warped[2][p], warped[3][p])) - black) / range
                    val c = fi.at(x, y) * ((0.95f - mx) / 0.15f).coerceIn(0f, 1f) * wv
                    if (c > 0f) for (k in 0 until 4) m[k][p] += c * ((warped[k][p] - black) * toDark - m[k][p])
                } }
            }
        }
        par(ph) { y -> for (x in 0 until pw) { val p = y * pw + x; if (logGain[p] != 0f) { val g = exp(logGain[p]); for (k in 0 until 4) m[k][p] *= g } } }
    }

    // ---- focus stack ---------------------------------------------------------------------------

    /**
     * Frames (near to far) lined up on the middle one, each neighbour onto the next so every step is a
     * small change. Sharpness per spot = energy of the Laplacian of green, at quarter size; weights =
     * sharpness^6 (the sharpest frame wins clearly), smoothed so the hand-over between frames is soft.
     */
    fun focus(frames: Int, read: (Int) -> ShortArray, w: Int, h: Int, black: Int, white: Int, cfa: ByteArray,
              progress: (String) -> Unit = {}): Result {
        val pw = w / 2; val ph = h / 2; val qw = pw / 2; val qh = ph / 2
        val m = frames / 2
        val maps = arrayOfNulls<DoubleArray>(frames); maps[m] = IDENTITY
        val energy = arrayOfNulls<FloatArray>(frames)
        var mid: FloatArray? = null; var midPyr: Align.Pyramid? = null
        fun energyOf(g: FloatArray, a: DoubleArray): FloatArray {
            val wg = warp(g, pw, ph, a)
            val e = FloatArray(qw * qh)
            par(qh) { qy -> for (qx in 0 until qw) {
                var s = 0f
                for (oy in 0..1) for (ox in 0..1) {
                    val x = (2 * qx + ox).coerceIn(1, pw - 2); val y = (2 * qy + oy).coerceIn(1, ph - 2); val i = y * pw + x
                    val l = wg[i - 1] + wg[i + 1] + wg[i - pw] + wg[i + pw] - 4 * wg[i]
                    s += l * l
                }
                e[qy * qw + qx] = s * 0.25f
            } }
            return blur(e, qw, qh, 1.5f)
        }
        // pass 1: line up and measure sharpness, from the middle outwards
        for (dir in intArrayOf(-1, 1)) {
            var prevG: FloatArray? = null; var prevPyr: Align.Pyramid? = null
            var i = if (dir < 0) m else m + 1
            while (i in 0 until frames) {
                progress("Lining up frame ${i + 1} of $frames…")
                val px = read(i)
                val g = green(planes(px, w, h), cfa, black, white)
                if (i == m) { mid = g; energy[m] = energyOf(g, IDENTITY); prevG = g; prevPyr = Align.pyramid(px, w, h); midPyr = prevPyr }
                else {
                    if (prevG == null) { prevG = mid; prevPyr = midPyr }   // the far side starts again from the middle
                    val prior = prevPyr?.let { coarse(it, px, w, h) } ?: IDENTITY
                    val step = align(prevG!!, g, pw, ph, prior)          // neighbour (nearer the middle) -> this frame
                    val a = compose(step, maps[i - dir]!!)               // middle -> neighbour -> this frame
                    maps[i] = a
                    energy[i] = energyOf(g, a)
                    prevG = g; prevPyr = Align.pyramid(px, w, h)
                }
                i += dir
            }
        }
        // weights
        progress("Finding the sharp parts…")
        val n = frames
        val wts = Array(n) { FloatArray(qw * qh) }
        par(qh) { y -> for (x in 0 until qw) {
            val p = y * qw + x
            var mx = 0f; for (f in 0 until n) mx = max(mx, energy[f]!![p])
            for (f in 0 until n) { val r = energy[f]!![p] / (mx + 1e-12f); val r2 = r * r; wts[f][p] = r2 * r2 * r2 }
        } }
        for (f in 0 until n) wts[f] = blur(wts[f], qw, qh, 3f)
        val total = FloatArray(qw * qh); for (f in 0 until n) { val a = wts[f]; for (p in total.indices) total[p] += a[p] }
        for (f in 0 until n) { val a = wts[f]; for (p in a.indices) a[p] /= (total[p] + 1e-12f) }
        // pass 2: blend
        val sum = Array(4) { FloatArray(pw * ph) }
        val warped = FloatArray(pw * ph)
        for (f in 0 until n) {
            progress("Blending frame ${f + 1} of $frames…")
            val pl = planes(read(f), w, h)
            val wq = wts[f]
            for (k in 0 until 4) {
                warpCubic(pl[k], pw, ph, maps[f]!!, warped)
                val s = sum[k]
                par(ph) { y ->
                    val fy = ((y + 0.5f) / 2 - 0.5f).coerceIn(0f, qh - 1.001f); val j = fy.toInt(); val vy = fy - j
                    for (x in 0 until pw) {
                        val fx = ((x + 0.5f) / 2 - 0.5f).coerceIn(0f, qw - 1.001f); val i = fx.toInt(); val vx = fx - i
                        val i0 = j * qw + i
                        val top = wq[i0] + (wq[i0 + 1] - wq[i0]) * vx; val bot = wq[i0 + qw] + (wq[i0 + qw + 1] - wq[i0 + qw]) * vx
                        s[y * pw + x] += (top + (bot - top) * vy) * warped[y * pw + x]
                    }
                }
            }
        }
        val scale = scaleFor(white)
        val sizes = maps.map { a -> sqrt(abs(a!![0] * a[4] - a[1] * a[3])) }
        return Result(bayer(sum, w, h, scale), scale, m, n,
            "size change across the stack ${"%.2f".format((sizes.maxOrNull()!! / sizes.minOrNull()!! - 1) * 100)}%")
    }
}
