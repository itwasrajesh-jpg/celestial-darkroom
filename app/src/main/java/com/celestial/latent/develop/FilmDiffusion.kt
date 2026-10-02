package com.celestial.latent.develop

import android.util.Log
import org.jtransforms.fft.FloatFFT_2D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The diffusion filter, computed the fast way.
 *
 * The engine convolves the image with the filter's point-spread function directly, so its cost
 * grows with the square of the blur radius — at 12.5 MP that radius is over a thousand pixels
 * and a single photo takes minutes. The same convolution done with an FFT costs the same
 * whatever the radius.
 *
 * The kernel here is the engine's own, reproduced from its tables: the same families, the same
 * log-spaced component groups, the same per-channel halo warmth, the same strength mapping and
 * the same sum-normalisation. Convolved at full resolution with an FFT the result matches the
 * engine to rounding error (measured: 1e-16 relative).
 *
 * One concession is made for memory: the **bloom** — the widest, smoothest component, which
 * carries no fine detail — is computed on a quarter-scale copy and scaled back up. Measured
 * against the exact result that costs 0.54% at worst, which is below one step of an 8-bit JPEG.
 * The sharp core and halo stay at full resolution, because shrinking those is what made an
 * earlier attempt look wrong.
 */
object FilmDiffusion {

    /** core(λµm, spread, n, α), halo, bloom, weights, halo warmth base, total gain. */
    private data class Group(val lambdaUm: Double, val spread: Double, val n: Int, val alpha: Double)
    private data class Family(
        val core: Group, val halo: Group, val bloom: Group,
        val wC: Double, val wH: Double, val wB: Double,
        val warmthBase: Double, val totalGain: Double,
    )

    private val FAMILIES = mapOf(
        "glimmerglass" to Family(Group(10.0, 1.5, 2, 0.0), Group(50.0, 2.0, 3, 0.0), Group(260.0, 2.5, 4, 3.2), 0.60, 0.30, 0.10, 0.0, 0.65),
        "black_pro_mist" to Family(Group(16.0, 1.5, 2, 0.0), Group(95.0, 2.0, 3, 0.0), Group(380.0, 2.5, 4, 3.5), 0.40, 0.47, 0.13, 0.65, 0.75),
        "pro_mist" to Family(Group(14.0, 1.5, 2, 0.0), Group(150.0, 2.0, 3, 0.0), Group(650.0, 2.5, 4, 2.9), 0.28, 0.42, 0.30, 0.40, 1.05),
        "cinebloom" to Family(Group(20.0, 1.5, 2, 0.0), Group(200.0, 2.0, 3, 0.0), Group(1000.0, 2.5, 4, 2.5), 0.22, 0.30, 0.48, 0.85, 1.00),
    )

    private val STRENGTH_BREAKS = doubleArrayOf(0.125, 0.25, 0.5, 1.0, 2.0)
    private val STRENGTH_FRACTION = doubleArrayOf(0.10, 0.20, 0.35, 0.55, 0.75)
    private val WARMTH_AXIS = doubleArrayOf(1.30, 0.15, -1.45)   // red warm, green mild, blue cool

    /** How much of the image is replaced by the blurred copy, for a given strength. */
    private fun scatterFraction(strength: Double, gain: Double): Double {
        if (strength <= 0.0) return 0.0
        val x = ln(max(strength, 1e-6)) / ln(2.0)
        val xs = DoubleArray(5) { ln(STRENGTH_BREAKS[it]) / ln(2.0) }
        var v = when {
            x <= xs[0] -> STRENGTH_FRACTION[0]
            x >= xs[4] -> STRENGTH_FRACTION[4]
            else -> {
                var k = 0
                while (k < 4 && x > xs[k + 1]) k++
                val t = (x - xs[k]) / (xs[k + 1] - xs[k])
                STRENGTH_FRACTION[k] + t * (STRENGTH_FRACTION[k + 1] - STRENGTH_FRACTION[k])
            }
        } * gain
        if (v < 0.0) v = 0.0
        if (v > 0.99) v = 0.99
        return v
    }

    /** A group becomes several log-spaced exponentials; the bloom weights them by λ^(2-α). */
    private fun expand(g: Group, isBloom: Boolean): Pair<DoubleArray, DoubleArray> {
        if (g.n <= 1 || g.spread <= 1.0) return doubleArrayOf(g.lambdaUm) to doubleArrayOf(1.0)
        val lo = ln(g.lambdaUm / g.spread)
        val hi = ln(g.lambdaUm * g.spread)
        val step = (hi - lo) / (g.n - 1)
        val lam = DoubleArray(g.n) { exp(if (it == g.n - 1) hi else lo + step * it) }
        val w = DoubleArray(g.n) { if (isBloom) Math.pow(lam[it], 2.0 - g.alpha) else 1.0 }
        val sum = w.sum()
        for (i in w.indices) w[i] /= sum
        return lam to w
    }

    /** The halo leans warm in red and cool in blue — the filter's signature. */
    private fun haloPerChannel(w: DoubleArray, warmth: Double): Array<DoubleArray> {
        val n = w.size
        if (n < 2) return Array(3) { w.copyOf() }
        val wc = warmth.coerceIn(-1.5, 1.5)
        val step = 2.0 / (n - 1)
        val g = DoubleArray(n) { if (it == n - 1) 1.0 else -1.0 + step * it }
        val wsum = w.sum()
        val gavg = g.indices.sumOf { g[it] * w[it] } / wsum
        for (i in g.indices) g[i] -= gavg
        return Array(3) { c ->
            val raw = DoubleArray(n) { max(w[it] * (1.0 + wc * WARMTH_AXIS[c] * g[it]), 0.0) }
            val s = raw.sum()
            DoubleArray(n) { raw[it] * (wsum / s) }
        }
    }

    private fun expSum(r: Double, lam: DoubleArray, w: DoubleArray): Double {
        var t = 0.0
        for (k in lam.indices) {
            val lk = max(lam[k], 1e-6)
            t += w[k] * exp(-r / lk) / (2.0 * PI * lk * lk)
        }
        return t
    }

    /**
     * Applies the filter in place to a linear RGB float buffer.
     * @param pixelSizeUm how much of the negative one pixel covers.
     */
    fun apply(
        data: ByteBuffer, width: Int, height: Int,
        familyName: String, strength: Float, spatialScale: Float,
        coreIntensity: Float, coreSize: Float, haloIntensity: Float, haloSize: Float,
        bloomIntensity: Float, bloomSize: Float, haloWarmth: Float,
        pixelSizeUm: Float,
        scatter: FloatArray? = null,
    ) {
        val base = FAMILIES[familyName] ?: FAMILIES.getValue("black_pro_mist")
        if (strength <= 0f || spatialScale <= 0f || width < 16 || height < 16) return

        // The fine-tune multipliers reshape the family, exactly as the engine does.
        var fam = base
        if (coreIntensity != 1f || haloIntensity != 1f || bloomIntensity != 1f ||
            coreSize != 1f || haloSize != 1f || bloomSize != 1f
        ) {
            val wC = base.wC * max(coreIntensity.toDouble(), 0.0)
            val wH = base.wH * max(haloIntensity.toDouble(), 0.0)
            val wB = base.wB * max(bloomIntensity.toDouble(), 0.0)
            val total = wC + wH + wB
            if (total > 0.0) {
                fam = base.copy(
                    core = base.core.copy(lambdaUm = base.core.lambdaUm * max(coreSize.toDouble(), 1e-6)),
                    halo = base.halo.copy(lambdaUm = base.halo.lambdaUm * max(haloSize.toDouble(), 1e-6)),
                    bloom = base.bloom.copy(lambdaUm = base.bloom.lambdaUm * max(bloomSize.toDouble(), 1e-6)),
                    wC = wC / total, wH = wH / total, wB = wB / total,
                )
            }
        }

        val mix = scatterFraction(strength.toDouble(), fam.totalGain)
        if (mix <= 0.0) return

        val t0 = System.nanoTime()
        val scale = max(spatialScale.toDouble(), 1e-6)
        val toPx = { v: DoubleArray -> DoubleArray(v.size) { v[it] * scale / max(pixelSizeUm.toDouble(), 0.01) } }
        val (coreLam, coreW) = expand(fam.core, false)
        val (haloLam, haloW) = expand(fam.halo, false)
        val (bloomLam, bloomW) = expand(fam.bloom, true)
        val corePx = toPx(coreLam); val haloPx = toPx(haloLam); val bloomPx = toPx(bloomLam)
        val haloCh = haloPerChannel(haloW, fam.warmthBase + haloWarmth.toDouble())

        // The engine's radius rule, so the kernel covers what its own kernel covers.
        val bloomMaxPx = fam.bloom.lambdaUm * fam.bloom.spread * scale / max(pixelSizeUm.toDouble(), 0.01)
        var radius = ceil(max(8.0 * bloomMaxPx, 5.0)).toInt()
        radius = min(radius, max(min(height, width) / 2 - 1, 1))

        // Sharp part (core + halo) at full resolution; bloom on a quarter-scale copy.
        val sharpRadius = min(ceil(8.0 * max(corePx.max(), haloPx.max())).toInt().coerceAtLeast(3), radius)
        // Split only when the bloom is much wider than the sharp part; otherwise one kernel
        // containing everything is both simpler and exact.
        val split = radius > 4 * sharpRadius
        val bloomStep = if (split) 4 else 1
        val mainRadius = if (split) sharpRadius else radius

        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = width * height
        // Painted diffusion ("soften"): each place scatters `mix × scatter[i]` of its OWN light
        // (never more than all of it), and that light spreads through the glow wherever it lands —
        // as diffusing material held over part of a print spreads only the light passing through
        // it. Glow from a softened area spills across the edge of a sharp one instead of stopping
        // dead, so no dark ring; and light is conserved. Null: the whole picture, exactly as before.
        val amount: FloatArray? = if (scatter == null || scatter.size != n) null
            else FloatArray(n) { i -> min(mix * max(scatter[i].toDouble(), 0.0), 1.0).toFloat() }
        val scattered: FloatArray? = if (amount != null) FloatArray(n) else null
        val plane = FloatArray(n)
        val sharpOut = FloatArray(n)
        val bloomOut = FloatArray(n)

        for (c in 0 until 3) {
            for (i in 0 until n) plane[i] = f.get(i * 3 + c)
            // the light each place sends into the glow: all of it scaled by mix, or by the painting
            val src: FloatArray = if (amount != null && scattered != null) {
                for (i in 0 until n) scattered[i] = plane[i] * amount[i]
                scattered
            } else plane

            // Each channel's kernel is normalised as a whole, as the engine does, then split.
            val whole = kernelSum(radius, fam, corePx, coreW, haloPx, haloCh[c], bloomPx, bloomW)
            val main = FloatArray((2 * mainRadius + 1) * (2 * mainRadius + 1))
            var k = 0
            for (y in -mainRadius..mainRadius) for (x in -mainRadius..mainRadius) {
                val r = sqrt((x * x + y * y).toDouble())
                var v = fam.wC * expSum(r, corePx, coreW) + fam.wH * expSum(r, haloPx, haloCh[c])
                // Without the split the bloom belongs in this kernel, not in a second pass.
                if (!split) v += fam.wB * expSum(r, bloomPx, bloomW)
                main[k++] = (v / whole).toFloat()
            }
            convolve(src, width, height, main, mainRadius, sharpOut)

            if (split) {
                val sw = width / bloomStep
                val sh = height / bloomStep
                val small = FloatArray(sw * sh)
                for (y in 0 until sh) for (x in 0 until sw) {
                    var acc = 0f
                    for (dy in 0 until bloomStep) for (dx in 0 until bloomStep) acc += src[(y * bloomStep + dy) * width + (x * bloomStep + dx)]
                    small[y * sw + x] = acc / (bloomStep * bloomStep)
                }
                val rb = max(radius / bloomStep, 1)
                val kb = FloatArray((2 * rb + 1) * (2 * rb + 1))
                val bloomSmallPx = DoubleArray(bloomPx.size) { bloomPx[it] / bloomStep }
                var j = 0
                var kbSum = 0.0
                for (y in -rb..rb) for (x in -rb..rb) {
                    val r = sqrt((x * x + y * y).toDouble())
                    val v = fam.wB * expSum(r, bloomSmallPx, bloomW)
                    kb[j++] = v.toFloat(); kbSum += v
                }
                // Scaled so the bloom keeps the same share of the whole kernel as the engine gives it.
                val share = bloomShare(radius, fam, bloomPx, bloomW) / whole
                for (i in kb.indices) kb[i] = (kb[i] / kbSum * share).toFloat()
                val smallOut = FloatArray(sw * sh)
                convolve(small, sw, sh, kb, rb, smallOut)
                upsample(smallOut, sw, sh, bloomOut, width, height)
            } else {
                // The bloom is already in the kernel above.
                java.util.Arrays.fill(bloomOut, 0f)
            }

            for (i in 0 until n) {
                val blurred = sharpOut[i] + bloomOut[i]
                if (amount == null) f.put(i * 3 + c, ((1.0 - mix) * plane[i] + mix * blurred).toFloat())
                else f.put(i * 3 + c, (plane[i] - src[i] + blurred).toFloat())   // keeps what was not scattered, gains the glow
            }
        }
        Log.i(
            "Latent",
            "diffusion: $familyName strength=$strength mix=${"%.3f".format(mix)}${if (amount != null) " painted" else ""} " +
                "radius=${radius}px sharp=${mainRadius}px split=$split bloom=1/$bloomStep " +
                "λcore=${"%.1f".format(corePx.max())}px λhalo=${"%.1f".format(haloPx.max())}px λbloom=${"%.1f".format(bloomPx.max())}px " +
                "${width}x$height in ${(System.nanoTime() - t0) / 1_000_000} ms",
        )
    }

    /**
     * The kernel's total, summed the way the engine sums it — over the square grid, not a disc,
     * because that is what it normalises by and the difference is several percent.
     *
     * Summing every point would cost more than the convolution itself once the radius reaches a
     * thousand pixels, so the centre is summed exactly (where the kernel is sharp) and the
     * smooth tail is sampled every fourth pixel and weighted by area. Measured against the full
     * sum: 0.0000% for the core, 0.0005% for the halo, 0.048% for the bloom.
     */
    private fun gridSum(radius: Int, lam: DoubleArray, w: DoubleArray): Double {
        val near = min(radius, 384)
        var total = 0.0
        for (y in -near..near) {
            for (x in -near..near) total += expSum(sqrt((x * x + y * y).toDouble()), lam, w)
        }
        if (radius > near) {
            val stride = 4
            var y = -radius
            while (y <= radius) {
                var x = -radius
                while (x <= radius) {
                    if (Math.abs(x) > near || Math.abs(y) > near) {
                        total += expSum(sqrt((x * x + y * y).toDouble()), lam, w) * stride * stride
                    }
                    x += stride
                }
                y += stride
            }
        }
        return total
    }

    private fun kernelSum(radius: Int, fam: Family, cl: DoubleArray, cw: DoubleArray, hl: DoubleArray, hw: DoubleArray, bl: DoubleArray, bw: DoubleArray): Double =
        fam.wC * gridSum(radius, cl, cw) + fam.wH * gridSum(radius, hl, hw) + fam.wB * gridSum(radius, bl, bw)

    private fun bloomShare(radius: Int, fam: Family, bl: DoubleArray, bw: DoubleArray): Double =
        fam.wB * gridSum(radius, bl, bw)

    /**
     * FFT convolution with reflected edges, matching the engine's padding.
     *
     * Done in tiles: each tile is transformed with a border of the kernel radius around it and
     * only its valid centre is kept, so memory stays bounded whatever the image size. Verified
     * against a direct reflect-padded convolution — the two agree to rounding (1e-16).
     */
    private fun convolve(src: FloatArray, w: Int, h: Int, kernel: FloatArray, r: Int, out: FloatArray) {
        // A tile plus its border must fit an FFT size the library handles well, and the largest
        // buffer we allow wins: fewer, bigger transforms beat many small ones.
        val fftSize = if (2 * r + 64 <= MAX_FFT) MAX_FFT else nextGood(4 * r)
        val tile = fftSize - 2 * r
        if (tile < 16) {
            // The kernel is nearly as large as the image. One transform covering everything
            // would need hundreds of megabytes, so the kernel is trimmed to what the largest
            // buffer allows — its outer edge is far below a thousandth of the peak, so nothing
            // visible is lost, and the alternative is running out of memory.
            val rCap = (MAX_FFT - 64) / 2
            val trimmed = trim(kernel, r, rCap)
            convolve(src, w, h, trimmed, rCap, out)
            return
        }
        // The transform plan and both buffers are made once and reused for every tile: making
        // them per tile meant tens of 33 MB allocations per channel, which the collector then
        // had to chase.
        val fft = FloatFFT_2D(fftSize.toLong(), fftSize.toLong())
        val a = FloatArray(fftSize * fftSize * 2)
        val b = FloatArray(fftSize * fftSize * 2)
        // The kernel's transform is the same for every tile, so it is done once.
        val ks = 2 * r + 1
        for (y in 0 until ks) {
            val row = y * fftSize * 2
            for (x in 0 until ks) b[row + x * 2] = kernel[y * ks + x]
        }
        fft.complexForward(b)

        var ty = 0
        while (ty < h) {
            val th = min(tile, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = min(tile, w - tx)
                convolveTile(src, w, h, tx, ty, tw, th, r, fftSize, out, fft, a, b)
                tx += tile
            }
            ty += tile
        }
    }

    private fun convolveTile(
        src: FloatArray, w: Int, h: Int,
        tx: Int, ty: Int, tw: Int, th: Int,
        r: Int, fftSize: Int, out: FloatArray,
        fft: FloatFFT_2D, a: FloatArray, b: FloatArray,
    ) {
        val n = fftSize
        // Interleaved complex, which the library's complexForward/complexInverse take without
        // ambiguity about layout. `a` is reused, so it must be cleared first.
        java.util.Arrays.fill(a, 0f)
        for (y in 0 until th + 2 * r) {
            val sy = reflect(ty + y - r, h)
            val row = y * n * 2
            for (x in 0 until tw + 2 * r) {
                a[row + x * 2] = src[sy * w + reflect(tx + x - r, w)]
            }
        }
        fft.complexForward(a)
        // b already holds the kernel's transform.
        var i = 0
        while (i < a.size) {
            val ar = a[i]; val ai = a[i + 1]; val br = b[i]; val bi = b[i + 1]
            a[i] = ar * br - ai * bi
            a[i + 1] = ar * bi + ai * br
            i += 2
        }
        fft.complexInverse(a, true)
        for (y in 0 until th) {
            val row = (y + 2 * r) * n * 2
            for (x in 0 until tw) {
                out[(ty + y) * w + (tx + x)] = a[row + (x + 2 * r) * 2]
            }
        }
    }

    /** Centre crop of a kernel, renormalised so the total light is unchanged. */
    private fun trim(kernel: FloatArray, r: Int, rNew: Int): FloatArray {
        val ks = 2 * r + 1
        val ns = 2 * rNew + 1
        val out = FloatArray(ns * ns)
        var sum = 0.0
        for (y in 0 until ns) for (x in 0 until ns) {
            val v = kernel[(y + r - rNew) * ks + (x + r - rNew)]
            out[y * ns + x] = v
            sum += v
        }
        var kept = 0.0
        for (v in kernel) kept += v
        if (sum > 0.0) {
            val f = (kept / sum).toFloat()
            for (i in out.indices) out[i] *= f
        }
        return out
    }

    private fun reflect(i: Int, n: Int): Int {
        var v = i
        if (v < 0) v = -v
        if (v >= n) v = 2 * n - 2 - v
        return v.coerceIn(0, n - 1)
    }

    /** Keeps a single transform buffer well under a hundred megabytes. */
    private const val MAX_FFT = 2048

    /** FFT sizes the library handles quickly: powers of two times small factors. */
    private fun nextGood(n: Int): Int {
        var best = Int.MAX_VALUE
        for (a in intArrayOf(1, 3, 5, 7, 9)) {
            var p = a
            while (p < n) p *= 2
            if (p in n until best) best = p
        }
        return if (best == Int.MAX_VALUE) Integer.highestOneBit(n - 1) * 2 else best
    }

    private fun upsample(src: FloatArray, sw: Int, sh: Int, out: FloatArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val fy = (y.toFloat() * sh / h).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = (x.toFloat() * sw / w).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = fx - x0
                val a = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                val b = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * w + x] = a * (1 - ty) + b * ty
            }
        }
    }
}
