package com.celestial.latent.develop

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What a set of reference images says about grain, halation, bloom and glare.
 *
 * These four are not guessed at by the search: each leaves a signature that can be read straight
 * off a photograph, which is both faster and more truthful than trying thousands of renders
 * until the texture happens to match.
 *
 *  - **Grain** is fine noise that survives in flat areas, where the picture has no detail of its
 *    own. Measured by how much a pixel differs from its neighbours where the neighbourhood is
 *    otherwise smooth.
 *  - **Halation** is a red-orange bleed that appears only around bright edges. Measured by how
 *    much redder the surroundings of a highlight are than the picture as a whole.
 *  - **Bloom** is a wider lift around bright areas without the red bias — the diffusion filter.
 *    Measured at a larger radius than halation, and separated from it by colour.
 *  - **Glare** is veiling flare: the black point rising when the frame is bright overall.
 *    Measured as how far the darkest tones sit above zero in a bright picture.
 */
data class Texture(
    val grainAmount: Float,       // 0..1, how much fine noise survives in flat areas
    val grainFineness: Float,     // 0 = coarse, 1 = fine
    val halationAmount: Float,    // 0..2.5, the engine's own scale
    val halationReach: Float,     // relative spread, 0.5..3
    val bloomAmount: Float,       // 0..1, diffusion strength
    val bloomReach: Float,        // relative, picks the filter family
    val glarePercent: Float,      // 0..3, veiling flare
    /**
     * Whether grain could actually be measured. Grain is a few thousandths of a millimetre
     * across; in a reference shrunk for the web it has been averaged away, and what survives is
     * JPEG noise. So below a size where it can survive, nothing is read — and the film keeps
     * its own grain, rather than having it switched off because it could not be seen.
     * Last, after the numbers, so nothing built by position can shift.
     */
    val grainMeasured: Boolean = true,
) {

    /** The suggested filter family, from how far the bloom reaches. */
    val bloomFamily: String
        get() = when {
            bloomReach < 0.8f -> "glimmerglass"
            bloomReach < 1.3f -> "black_pro_mist"
            bloomReach < 1.9f -> "pro_mist"
            else -> "cinebloom"
        }

    /** Applies what was read to a recipe, leaving the colour alone. */
    fun applyTo(r: Recipe): Recipe = (
        // Grain only when it was actually measured; otherwise the film's own grain stands.
        if (grainMeasured) r.copy(
            grain = grainAmount > 0.02f,
            grainSizeUm2 = (0.08f + (1f - grainFineness) * 0.9f).coerceIn(0.03f, 3.2f),
            grainBlur = (0.3f + (1f - grainFineness) * 0.6f).coerceIn(0.1f, 1.5f),
        ) else r
    ).copy(
        halation = halationAmount > 0.05f,
        halationAmount = halationAmount,
        halationScale = halationReach,
        diffusion = bloomAmount > 0.03f,
        diffusionFamily = bloomFamily,
        diffusionStrength = bloomAmount,
        glare = glarePercent > 0.05f,
        glarePercent = glarePercent,
    )

    companion object {
        /** Averages what several references say, which is how a set becomes one answer. */
        fun average(list: List<Texture>): Texture {
            require(list.isNotEmpty())
            val n = list.size.toFloat()
            // Grain is averaged over the references that could show it — a small image's zero
            // would otherwise drag the set's grain down towards none.
            val withGrain = list.filter { it.grainMeasured }
            val g = withGrain.size.toFloat()
            return Texture(
                if (g > 0f) withGrain.sumOf { it.grainAmount.toDouble() }.toFloat() / g else 0f,
                if (g > 0f) withGrain.sumOf { it.grainFineness.toDouble() }.toFloat() / g else 0.5f,
                list.sumOf { it.halationAmount.toDouble() }.toFloat() / n,
                list.sumOf { it.halationReach.toDouble() }.toFloat() / n,
                list.sumOf { it.bloomAmount.toDouble() }.toFloat() / n,
                list.sumOf { it.bloomReach.toDouble() }.toFloat() / n,
                list.sumOf { it.glarePercent.toDouble() }.toFloat() / n,
                grainMeasured = withGrain.isNotEmpty(),
            )
        }

        /**
         * @param wide the reference at a modest size — enough for halation, bloom and glare,
         *   which are all broad effects.
         * @param grainCrop a piece of the SAME reference at its original resolution. Grain is
         *   fine detail and does not survive downscaling: measured on a shrunken copy, every
         *   reference reads as grainless. When it is absent the grain figures are left at zero
         *   rather than being guessed at.
         */
        fun of(wide: Bitmap, grainCrop: Bitmap? = null): Texture {
            val broad = measureBroad(wide)
            val g = grainCrop?.let { measureGrain(it) } ?: (0f to 0.5f)
            return broad.copy(grainAmount = g.first, grainFineness = g.second, grainMeasured = grainCrop != null)
        }

        /** Grain, at the resolution it actually exists at. */
        private fun measureGrain(bmp: Bitmap): Pair<Float, Float> {
            val w = min(bmp.width, 512)
            val h = min(bmp.height, 512)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h)
            val lum = FloatArray(w * h) {
                val p = px[it]
                0.2126f * (((p shr 16) and 0xFF) / 255f) +
                    0.7152f * (((p shr 8) and 0xFF) / 255f) +
                    0.0722f * ((p and 0xFF) / 255f)
            }
            var sum = 0.0; var fine = 0.0; var count = 0
            for (y in 2 until h - 2) {
                for (x in 2 until w - 2) {
                    val i = y * w + x
                    val left = (lum[i - 2] + lum[i - 1]) / 2f
                    val right = (lum[i + 1] + lum[i + 2]) / 2f
                    val up = (lum[i - 2 * w] + lum[i - w]) / 2f
                    val down = (lum[i + w] + lum[i + 2 * w]) / 2f
                    if (abs(left - right) + abs(up - down) > 0.06f) continue
                    if (lum[i] < 0.06f || lum[i] > 0.95f) continue
                    val around = (left + right + up + down) / 4f
                    val d = abs(lum[i] - around)
                    val wider = (lum[i - 2] + lum[i + 2] + lum[i - 2 * w] + lum[i + 2 * w]) / 4f
                    val dWide = abs(lum[i] - wider)
                    sum += d.toDouble()
                    fine += (if (dWide > 1e-5f) (d / dWide).toDouble() else 1.0).coerceIn(0.0, 2.0)
                    count++
                }
            }
            if (count == 0) return 0f to 0.5f
            return ((sum / count).toFloat() * 26f).coerceIn(0f, 1f) to ((fine / count) / 2.0).toFloat().coerceIn(0f, 1f)
        }

        private fun measureBroad(bmp: Bitmap): Texture {
            // Work at a known size so the measurements mean the same thing for every reference.
            val w = 360
            val h = max(1, bmp.height * w / max(bmp.width, 1))
            val small = Bitmap.createScaledBitmap(bmp, w, h, true)
            val n = w * h
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val lum = FloatArray(n)
            val px = IntArray(n)
            small.getPixels(px, 0, w, 0, 0, w, h)
            for (i in 0 until n) {
                val p = px[i]
                r[i] = ((p shr 16) and 0xFF) / 255f
                g[i] = ((p shr 8) and 0xFF) / 255f
                b[i] = (p and 0xFF) / 255f
                lum[i] = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
            }
            if (small != bmp) small.recycle()

            // --- around highlights: halation is red and close, bloom is neutral and wide ---
            //
            // A highlight has to be bright in absolute terms, not merely in the top few percent:
            // in a picture that is mostly one tone, a percentile lands inside the glow itself and
            // the measurement collapses. Verified against synthetic images — with this, a red
            // close bleed reads as halation and a wide neutral lift reads as bloom, and neither
            // is mistaken for the other.
            val frameMean = lum.average()
            val sorted = lum.copyOf(); sorted.sort()
            val maxLum = sorted[n - 1]
            val bright = max(sorted[((n - 1) * 0.992f).toInt().coerceIn(0, n - 1)], 0.62f * maxLum)
            val isBright = BooleanArray(n) { lum[it] >= bright }
            var brightCount = 0
            for (i in 0 until n) if (isBright[i]) brightCount++

            var halation = 0f
            var bloom = 0f
            var reach = 1f
            if (brightCount >= 8) {
                val near4 = dilate(isBright, w, h, 4)
                val far10 = dilate(isBright, w, h, 10)
                val far16 = dilate(isBright, w, h, 16)
                var nearR = 0.0; var nearB = 0.0; var nearL = 0.0; var nearN = 0
                var farL = 0.0; var farN = 0
                for (i in 0 until n) {
                    if (near4[i] && !isBright[i]) { nearR += r[i].toDouble(); nearB += b[i].toDouble(); nearL += lum[i].toDouble(); nearN++ }
                    if (far16[i] && !far10[i]) { farL += lum[i].toDouble(); farN++ }
                }
                val frameRed = r.average() - b.average()
                val nearRed = if (nearN == 0) 0.0 else (nearR - nearB) / nearN
                val nearLift = if (nearN == 0) 0.0 else nearL / nearN - frameMean
                val farLift = if (farN == 0) 0.0 else farL / farN - frameMean
                halation = ((nearRed - frameRed) * 6.0).toFloat().coerceIn(0f, 2.5f)
                bloom = (farLift * 18.0).toFloat().coerceIn(0f, 1f)
                reach = if (nearLift > 1e-4) (farLift / nearLift * 2.0).toFloat().coerceIn(0.4f, 2.6f) else 1f
            }

            // --- glare: the black point rising in a bright frame ---
            val black = percentile(lum, 0.005f)
            val glare = (black * frameMean.toFloat() * 12f).coerceIn(0f, 3f)

            return Texture(
                grainAmount = 0f,        // filled in by measureGrain, at full resolution
                grainFineness = 0.5f,
                halationAmount = halation,
                halationReach = reach.coerceIn(0.5f, 3f),
                bloomAmount = bloom,
                bloomReach = reach,
                glarePercent = glare,
            )
        }

        /** Grows a mask outwards by [radius] pixels, so rings can be taken around highlights. */
        private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
            var current = mask.copyOf()
            repeat(radius) {
                val next = current.copyOf()
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        if (current[row + x]) continue
                        val up = if (y > 0) current[row - w + x] else false
                        val down = if (y < h - 1) current[row + w + x] else false
                        val left = if (x > 0) current[row + x - 1] else false
                        val right = if (x < w - 1) current[row + x + 1] else false
                        if (up || down || left || right) next[row + x] = true
                    }
                }
                current = next
            }
            return current
        }

        private fun percentile(v: FloatArray, p: Float): Float {
            val s = v.copyOf(); s.sort()
            return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
        }
    }
}
