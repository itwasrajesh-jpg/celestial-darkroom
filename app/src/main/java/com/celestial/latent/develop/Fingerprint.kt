package com.celestial.latent.develop

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A numerical description of a LOOK, made so it survives a change of subject.
 *
 * Comparing two photographs pixel by pixel only works when they are the same photograph. A look
 * builder cannot rely on that: the references are other people's pictures of other things. So
 * what is measured here is deliberately scene-independent — where the tones sit relative to each
 * other, which way the greys lean, how saturation behaves as things get brighter, and what
 * happens to the two hue families a film is judged on. Averaged over a set of images, the scenes
 * cancel and the look remains.
 *
 * Every figure is bounded and unitless so they can be compared and weighted against each other.
 */
data class Fingerprint(
    /** Where the darkest and brightest parts sit, and how the tones spread between them. */
    val black: Float,
    val shadow: Float,
    val mid: Float,
    val highlight: Float,
    val white: Float,
    /** How steeply the tones climb through the middle: the sense of contrast. */
    val contrast: Float,
    /** How gently the brightest tones flatten off — film's shoulder. */
    val shoulderRoll: Float,
    /**
     * How far the picture's own neutral tones sit from grey.
     *
     * The casts below compare one end of the picture with the other, so a look that drifts
     * everything warm together passes them unnoticed — which is exactly what happened: builds
     * came out peach, with cream whites, while scoring well. This asks a different question:
     * of the tones this picture treats as neutral, how far from grey are they? It is also
     * immune to brightness, which is levelled in every attempt anyway.
     */
    val neutralWarmth: Float,
    val neutralGreen: Float,
    /** Colour cast in the shadows and in the highlights: the crossover that names a film. */
    val shadowWarmth: Float,
    val shadowGreen: Float,
    val highlightWarmth: Float,
    val highlightGreen: Float,
    /** Saturation overall, and whether it rises or falls with brightness. */
    val saturation: Float,
    val saturationSlope: Float,
    /** How the two families a film is judged on come out. */
    val skinHue: Float,
    val skinSaturation: Float,
    val foliageHue: Float,
    val foliageSaturation: Float,
    /**
     * How much of the picture each figure above was actually measured from, 0 to 1.
     *
     * Not every photograph contains everything. A reference with a plant behind the subject has
     * foliage to measure; a test shot of a plain wall does not — and comparing the two on
     * foliage produces a large, meaningless difference that the search then spends its effort
     * chasing. So each figure records its own evidence, and the comparison counts only what
     * both pictures can actually show.
     *
     * Declared last, and deliberately: the values above are constructed positionally, so a
     * non-number in the middle of them silently shifts every value that follows.
     */
    val coverage: FloatArray = FloatArray(LABELS.size) { 1f },
) {

    fun asList() = listOf(
        black, shadow, mid, highlight, white, contrast, shoulderRoll,
        neutralWarmth, neutralGreen,
        shadowWarmth, shadowGreen, highlightWarmth, highlightGreen,
        saturation, saturationSlope, skinHue, skinSaturation, foliageHue, foliageSaturation,
    )

    /**
     * How far apart two looks are. Weighted, because not every figure matters equally to the
     * eye: the colour crossover and the skin behaviour carry a look more than the exact black
     * point does. 0 is identical; around 1 is "not the same look at all".
     */
    /**
     * How far this fingerprint — a CANDIDATE — is from a REFERENCE. The order matters: the
     * reference decides which figures are compared, so the two are not interchangeable.
     */
    /**
     * The individual weighted differences behind [distanceTo] — one per figure, zero where the
     * figure cannot be judged.
     *
     * The solver needs to see these separately: it learns how each control moves each one, then
     * works out the combination that pulls them all towards the reference at once. The distance
     * is built from exactly these numbers, so what the solver minimises and what the screen
     * reports as the match can never drift apart.
     *
     * Scaled first, then weighted: the figures live on very different scales, and without the
     * scaling the colour and saturation measures are drowned out however heavily weighted.
     * Only what the REFERENCE (and the undeveloped test shot) can show is compared — never the
     * candidate's own coverage, or a candidate that destroyed the skin would drop the skin
     * figures from its own comparison and be rewarded for it.
     */
    fun residuals(reference: Fingerprint, judgeable: FloatArray? = null): FloatArray {
        val a = asList(); val b = reference.asList()
        val out = FloatArray(a.size)
        for (i in a.indices) {
            val shared = judgeable?.getOrElse(i) { 1f } ?: reference.coverage.getOrElse(i) { 1f }
            if (shared <= 0.001f) continue
            out[i] = (a[i] - b[i]) * SCALE[i] * WEIGHTS[i] * sqrt(shared)
        }
        return out
    }

    /** The total weight of what can be compared — fixed by the reference, not the candidate. */
    fun comparedWeight(reference: Fingerprint, judgeable: FloatArray? = null): Float {
        var used = 0f
        for (i in WEIGHTS.indices) {
            val shared = judgeable?.getOrElse(i) { 1f } ?: reference.coverage.getOrElse(i) { 1f }
            if (shared <= 0.001f) continue
            val w = WEIGHTS[i] * sqrt(shared)
            used += w * w
        }
        return used
    }

    /**
     * How far this fingerprint — a CANDIDATE — is from a REFERENCE: the root-mean-square of
     * [residuals], divided by the weight of what was actually compared, so a pair with little in
     * common is not flattered by the figures that were skipped.
     */
    fun distanceTo(reference: Fingerprint, judgeable: FloatArray? = null): Float {
        val used = comparedWeight(reference, judgeable)
        if (used <= 0f) return 0f
        val r = residuals(reference, judgeable)
        var sum = 0f
        for (v in r) sum += v * v
        return sqrt(sum / used)
    }

    /** The readable names, in the same order as [asList], for showing the comparison. */
    companion object {
        val LABELS = listOf(
            "black point", "shadows", "midtones", "highlights", "white point",
            "contrast", "highlight roll-off",
            "neutrals: warm", "neutrals: green",
            "shadow warmth", "shadow green", "highlight warmth", "highlight green",
            "saturation", "saturation with brightness",
            "skin hue", "skin saturation", "foliage hue", "foliage saturation",
        )

        /**
         * Puts the figures on a common footing before weighting. A colour cast and a saturation
         * figure change by a few hundredths where a tone position changes by tenths, so without
         * this they contribute almost nothing whatever weight they are given.
         */
        private val SCALE = floatArrayOf(
            1f, 1f, 1f, 1f, 1f,               // tone positions: already 0..1
            1f, 1f,                            // contrast and roll-off: already comparable
            3f, 3f,                            // neutrality
            4f, 4f, 4f, 4f,                    // colour casts: small numbers, big effect
            4f, 4f,                            // saturation likewise
            2f, 4f, 2f, 4f,                    // skin and foliage
        )

        private val WEIGHTS = floatArrayOf(
            // Where the tones sit. These were weighted down when every attempt was levelled to
            // the same brightness; the search sets the print exposure now, so they matter again.
            // The black point most of all: a lifted black is what makes a print look washed.
            1.4f, 1.1f, 1.0f, 1.0f, 0.9f,
            1.5f, 1.3f,                        // contrast and roll-off carry a lot
            // Neutrality weighs heaviest: grey staying grey is what the eye checks first, and
            // nothing else in this list catches an overall drift.
            3.2f, 2.8f,
            1.8f, 1.6f, 1.8f, 1.6f,            // the crossover is the film's signature
            2.4f, 2.0f,                        // saturation behaviour
            2.0f, 2.0f, 1.2f, 1.4f,            // skin most of all, then foliage
        )

        /**
         * Each figure in words, for a film that comes out higher on it than the references, then lower.
         * Signs as measured: warmth is red over blue, green is green over magenta, roll-off rising
         * means gentler highlights, the saturation slope rising means colour strengthening into the
         * brights, skin hue rising means yellower, foliage hue rising means bluer greens.
         */
        private val WORDS = listOf(
            "blacks more lifted" to "blacks deeper", "shadows lighter" to "shadows darker", "mid-tones lighter" to "mid-tones darker",
            "highlights brighter" to "highlights duller", "whites brighter" to "whites greyer",
            "more contrast" to "less contrast", "highlights roll off more gently" to "highlights clip more abruptly",
            "greys warmer" to "greys cooler", "greys greener" to "greys more magenta",
            "shadows warmer" to "shadows cooler", "shadows greener" to "shadows more magenta",
            "highlights warmer" to "highlights cooler", "highlights greener" to "highlights more magenta",
            "more saturated" to "less saturated", "colour stronger in the brights" to "colour fading in the brights",
            "skin more yellow" to "skin more red", "skin more saturated" to "skin paler",
            "greens bluer" to "greens yellower", "greens more saturated" to "greens duller",
        )

        /**
         * What sets a film apart from the references, biggest first, in plain words: the figures the
         * search itself compares (the same weights, only what both can show), each in its direction.
         * Only the ones that matter — at least a third of the biggest difference — and at most [limit].
         */
        fun differences(candidate: Fingerprint, reference: Fingerprint, judgeable: FloatArray?, limit: Int = 3): List<String> {
            val r = candidate.residuals(reference, judgeable)
            val order = r.indices.sortedByDescending { kotlin.math.abs(r[it]) }
            val biggest = kotlin.math.abs(r[order.first()])
            if (biggest < 0.04f) return emptyList()
            return order.filter { kotlin.math.abs(r[it]) >= max(0.04f, 0.33f * biggest) }.take(limit)
                .map { if (r[it] > 0f) WORDS[it].first else WORDS[it].second }
        }

        /** What can fairly be judged between a reference and an undeveloped test shot. */
        fun judgeable(reference: Fingerprint, testShot: Fingerprint): FloatArray =
            FloatArray(LABELS.size) { i ->
                min(reference.coverage.getOrElse(i) { 1f }, testShot.coverage.getOrElse(i) { 1f })
            }

        /** Averages a set of fingerprints — how a reference set becomes one target. */
        fun average(list: List<Fingerprint>): Fingerprint {
            require(list.isNotEmpty())
            // Each figure weighted by its evidence. A reference with no skin in it records skin hue as 0 —
            // "nothing measured", not "red" — and a plain mean pulled the set's skin towards red; a hazy
            // reference's tones describe only its clear part. With full evidence everywhere (the usual
            // case) this is exactly the plain mean, as before.
            val sums = FloatArray(LABELS.size); val weights = FloatArray(LABELS.size)
            for (f in list) f.asList().forEachIndexed { i, v -> val w = f.coverage.getOrElse(i) { 1f }; sums[i] += v * w; weights[i] += w }
            val plain = FloatArray(LABELS.size).also { m -> for (f in list) f.asList().forEachIndexed { i, v -> m[i] += v / list.size } }
            val a = FloatArray(LABELS.size) { if (weights[it] > 1e-4f) sums[it] / weights[it] else plain[it] }
            return Fingerprint(
                a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10],
                a[11], a[12], a[13], a[14], a[15], a[16], a[17], a[18],
                // A set's evidence is the best any of its images had.
                coverage = FloatArray(LABELS.size) { i -> list.maxOf { it.coverage.getOrElse(i) { 1f } } },
            )
        }

        /** How much the images in a set disagree — a scattered set makes a vague target. */
        fun spread(list: List<Fingerprint>): Float {
            if (list.size < 2) return 0f
            val mean = average(list)
            // Each image against the set's mean, with the mean as the reference.
            return list.map { it.distanceTo(mean) }.average().toFloat()
        }

        /**
         * Measures a display-referred image. Brightness is normalised away first: a reference
         * that happens to be a dark photograph should not make the look darker, only the
         * relationships between its tones should count.
         */
        fun of(bmp: Bitmap, clear: BooleanArray? = null): Fingerprint {
            val step = max(1, max(bmp.width, bmp.height) / 320)
            val n = (bmp.width / step) * (bmp.height / step)
            val lum = FloatArray(n)
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            var i = 0
            // [clear] (from [Haze.read]): one flag per grid position — false where air is in front,
            // and the film is not measured there. The grid position is counted apart from the kept
            // samples, since the two part ways as soon as one is skipped.
            var at = 0
            var y = 0
            while (y < bmp.height && at < n) {
                var x = 0
                while (x < bmp.width && at < n) {
                    if (clear != null && !clear.getOrElse(at) { true }) { at++; x += step; continue }
                    at++
                    val p = bmp.getPixel(x, y)
                    val rr = ((p shr 16) and 0xFF) / 255f
                    val gg = ((p shr 8) and 0xFF) / 255f
                    val bb = (p and 0xFF) / 255f
                    r[i] = rr; g[i] = gg; b[i] = bb
                    lum[i] = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                    i++
                    x += step
                }
                y += step
            }
            val count = i
            val sorted = lum.copyOf(count).also { it.sort() }
            fun pct(p: Float) = sorted[((count - 1) * p).toInt().coerceIn(0, count - 1)]

            val p01 = pct(0.01f); val p10 = pct(0.10f); val p50 = pct(0.50f)
            val p90 = pct(0.90f); val p99 = pct(0.99f); val p75 = pct(0.75f)

            // Normalise brightness out: the look is the shape of the distribution, not its level.
            val span = max(p99 - p01, 1e-3f)
            fun norm(v: Float) = ((v - p01) / span).coerceIn(0f, 1f)

            // Colour cast, measured where it shows: the darkest and brightest fifths.
            // The picture's own neutrals: the least colourful of its mid-tones. Whatever this
            // photograph treats as grey, that is what should come out grey.
            val midLow = pct(0.25f); val midHigh = pct(0.85f)
            var neutralWarm = 0f
            var neutralGreenLean = 0f
            var foundNeutrals = false
            var neutralPixels = 0
            run {
                val sats = ArrayList<Float>(count / 4)
                for (k in 0 until count) {
                    if (lum[k] <= midLow || lum[k] >= midHigh) continue
                    val mx = max(r[k], max(g[k], b[k]))
                    val mn = min(r[k], min(g[k], b[k]))
                    sats.add(if (mx > 0.04f) (mx - mn) / mx else 0f)
                }
                if (sats.size >= 50) {
                    sats.sort()
                    val limit = sats[(sats.size * 35 / 100).coerceIn(0, sats.size - 1)]
                    var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
                    for (k in 0 until count) {
                        if (lum[k] <= midLow || lum[k] >= midHigh) continue
                        val mx = max(r[k], max(g[k], b[k]))
                        val mn = min(r[k], min(g[k], b[k]))
                        val sat = if (mx > 0.04f) (mx - mn) / mx else 0f
                        if (sat > limit) continue
                        sr += r[k].toDouble(); sg += g[k].toDouble(); sb += b[k].toDouble(); n++
                    }
                    neutralPixels = n
                    if (n >= 20) {
                        val mr = sr / n; val mg = sg / n; val mb = sb / n
                        val mean = ((mr + mg + mb) / 3.0).coerceAtLeast(1e-4)
                        neutralWarm = ((mr - mb) / mean).toFloat().coerceIn(-1.5f, 1.5f)
                        neutralGreenLean = ((mg - (mr + mb) / 2.0) / mean).toFloat().coerceIn(-1.5f, 1.5f)
                        foundNeutrals = true
                    }
                }
            }

            val darkCut = pct(0.20f); val brightCut = pct(0.80f)
            var dr = 0f; var dg = 0f; var db = 0f; var dn = 0
            var hr = 0f; var hg = 0f; var hb = 0f; var hn = 0
            var satSum = 0f; var satDark = 0f; var satBright = 0f
            var skinH = 0f; var skinS = 0f; var skinN = 0
            var folH = 0f; var folS = 0f; var folN = 0
            for (k in 0 until count) {
                val mx = max(r[k], max(g[k], b[k]))
                val mn = min(r[k], min(g[k], b[k]))
                val sat = if (mx > 0.04f) (mx - mn) / mx else 0f
                satSum += sat
                if (lum[k] <= darkCut) { dr += r[k]; dg += g[k]; db += b[k]; dn++; satDark += sat }
                if (lum[k] >= brightCut) { hr += r[k]; hg += g[k]; hb += b[k]; hn++; satBright += sat }
                // Hue in degrees, only where there is enough colour to have one.
                if (sat > 0.12f && mx > 0.08f) {
                    val h = hueOf(r[k], g[k], b[k], mx, mn)
                    if (h in 5f..50f) { skinH += h; skinS += sat; skinN++ }      // skin and warm browns
                    if (h in 60f..170f) { folH += h; folS += sat; folN++ }       // foliage and greens
                }
            }
            fun cast(a: Float, c: Float, m: Int) = if (m == 0) 0f else ((a - c) / m).coerceIn(-0.5f, 0.5f)

            // What each figure was measured from, as a fraction of the picture. Tones and
            // contrast are always measurable; the rest depend on what is actually in the frame.
            val total = count.toFloat().coerceAtLeast(1f)
            val cover = FloatArray(LABELS.size) { 1f }
            // Measured past its air: the spread of tones (shadows … roll-off) describes only the clear
            // part of the scene — mostly foliage, say, without the bright clouds — so it counts in
            // proportion. The black point and the colours are the film's, and stay fully trusted.
            if (clear != null) { val share = (count.toFloat() / at.coerceAtLeast(1)).coerceIn(0f, 1f); for (k in 1..6) cover[k] = share }
            // How much of the picture was actually neutral, not a fixed guess. This was pinned
            // at a quarter regardless, and since coverage is square-rooted before weighting, the
            // measure whose whole job is catching a colour cast was running at half strength.
            val neutralCover = if (!foundNeutrals) 0f else (neutralPixels / total * 5f).coerceIn(0.35f, 1f)
            cover[7] = neutralCover
            cover[8] = neutralCover
            val shadowCover = (dn / total).coerceIn(0f, 1f)
            val brightCover = (hn / total).coerceIn(0f, 1f)
            cover[9] = shadowCover; cover[10] = shadowCover
            cover[11] = brightCover; cover[12] = brightCover
            cover[13] = 1f                                   // overall saturation: always there
            cover[14] = min(shadowCover, brightCover)         // its slope needs both ends
            val skinCover = (skinN / total).coerceIn(0f, 1f)
            val folCover = (folN / total).coerceIn(0f, 1f)
            cover[15] = skinCover; cover[16] = skinCover
            cover[17] = folCover; cover[18] = folCover

            return Fingerprint(
                black = norm(p01),
                shadow = norm(p10),
                mid = norm(p50),
                highlight = norm(p90),
                white = norm(p99),
                // Contrast: how much of the range the middle half occupies.
                contrast = ((p75 - p10) / span).coerceIn(0f, 2f),
                // Roll-off: how much less the top decile climbs than the middle does.
                shoulderRoll = (1f - ((p99 - p90) / max(p90 - p50, 1e-3f))).coerceIn(-2f, 2f),
                neutralWarmth = neutralWarm,
                neutralGreen = neutralGreenLean,
                shadowWarmth = cast(dr, db, dn),
                shadowGreen = cast(dg, (dr + db) / 2f, dn),
                highlightWarmth = cast(hr, hb, hn),
                highlightGreen = cast(hg, (hr + hb) / 2f, hn),
                saturation = (satSum / count).coerceIn(0f, 1f),
                // Whether colour strengthens or fades as things get brighter.
                saturationSlope = (
                    (if (hn > 0) satBright / hn else 0f) - (if (dn > 0) satDark / dn else 0f)
                    ).coerceIn(-1f, 1f),
                skinHue = if (skinN == 0) 0f else ((skinH / skinN) / 60f).coerceIn(0f, 1f),
                skinSaturation = if (skinN == 0) 0f else (skinS / skinN).coerceIn(0f, 1f),
                foliageHue = if (folN == 0) 0f else (((folH / folN) - 60f) / 110f).coerceIn(0f, 1f),
                foliageSaturation = if (folN == 0) 0f else (folS / folN).coerceIn(0f, 1f),
                coverage = cover,
            )
        }

        private fun hueOf(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
            val d = mx - mn
            if (d < 1e-6f) return 0f
            val h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }
            return if (h < 0f) h + 360f else h
        }
    }
}
