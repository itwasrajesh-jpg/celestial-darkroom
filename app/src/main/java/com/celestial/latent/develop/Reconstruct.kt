package com.celestial.latent.develop

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.spectrafilm.engine.SpektraEngine
import kotlin.math.abs
import kotlin.random.Random

/**
 * Builds an emulsion to match a set of reference images.
 *
 * Nothing here chooses between the bundled films. It moves the numbers that define a film —
 * where each layer's response sits, how much density it builds, how gradually, how sensitive it
 * is across the spectrum — writes them out as a profile, develops the test photo through the
 * engine with it, measures the result, and keeps what gets closer.
 *
 * The search is a simple hill climb with restarts: at this size each attempt costs a fraction of
 * a second, so hundreds are affordable, and a simple method that can be watched and stopped is
 * better than a clever one that cannot.
 */
object Reconstruct {

    /** The paper a film was designed for, or none for a slide film. */
    private fun printFor(base: org.json.JSONObject): String? =
        base.optJSONObject("info")?.optString("target_print")?.takeIf { it.isNotBlank() && it != "null" }

    /** @param baseEv the starting film exposure the fit worked from; the stock needs it too. */
    data class Attempt(val shape: Emulsion.Shape, val distance: Float, val jpeg: ByteArray?, val baseEv: Float = 0f)

    data class Progress(val tried: Int, val total: Int, val best: Attempt?, val note: String)

    @Volatile var cancelled = false

    /**
     * @param target what the references averaged to.
     * @param testShot one of the user's own photos: the blank sheet every attempt is developed on.
     */
    fun run(
        context: Context,
        target: Fingerprint,
        testShot: Uri,
        isRaw: Boolean,
        baseStock: String,
        rounds: Int = 240,
        /**
         * Where to begin. A previous result for the same references is a far better start than
         * the base film's defaults, so a second build refines the first instead of re-deriving
         * it. Null starts from scratch.
         */
        startFrom: Emulsion.Shape? = null,
        onProgress: (Progress) -> Unit,
    ): Attempt? {
        cancelled = false
        val dir = EngineAssets.prepare(context) { onProgress(Progress(0, rounds, null, it)) } ?: return null
        val base = Emulsion.baseProfile(context, baseStock) ?: return null
        val stockId = "celestial_working"
        // Read once: this walks the profile's JSON, and the search runs hundreds of attempts.
        val paper = printFor(base)

        // The photo is decoded once and reused: only the film changes between attempts.
        val source = runCatching {
            if (isRaw) Develop.openRaw(context, testShot, 320) else Develop.openImage(context, testShot, 320)
        }.getOrNull() ?: return null

        val holdsLaneEarly = DevelopQueue.engineLane.tryAcquire()
        // One alignment before the search, not a levelling during it.
        //
        // The only brightness control in the search is the print exposure, which spans about
        // two and a half stops. If the test shot sits further from the references than that —
        // an underexposed frame, say — the fit would run out of range and stop at its limit.
        // So the film exposure is set once, from what the engine would meter, and the search
        // moves the print exposure around that. The result is still a deliberate brightness
        // rather than one levelled away on every attempt.
        // Metered here rather than through LookBaker, which competes for the same engine lane
        // this search already holds — it would have found it busy, returned nothing, and the
        // alignment would have silently done nothing at all.
        val baseEv = runCatching {
            SpektraEngine(dir).use { engine ->
                val g = engine.exposureGain(
                    source.image,
                    Develop.sanitised(Recipe(film = baseStock, autoExposure = true)).toParams(),
                )
                if (g > 0.01f) (Math.log(g.toDouble()) / Math.log(2.0)).toFloat().coerceIn(-4f, 4f) else 0f
            }
        }.getOrDefault(0f)
        onProgress(Progress(0, rounds, null, "starting exposure ${"%+.1f".format(baseEv)} EV"))

        var best: Attempt? = null
        var current = startFrom ?: Emulsion.Shape()
        var currentDistance = Float.MAX_VALUE
        var temperature = 1f
        val random = Random(1)

        // Filled in once the helpers below exist; read by every evaluation.
        var judgeable: FloatArray? = null

        /**
         * The one recipe every attempt, probe and baseline is developed with.
         *
         * There used to be two: the search built its own with grain, halation and glare off,
         * while the probe and the baseline went through recipeFor and kept the app's defaults —
         * all three on. Halation is a red glow, so the probe deciding which way the yellow filter
         * warms the picture was measuring a haloed image while the search ran on a clean one.
         * The spatial effects are measured from the references separately; here they stay off.
         */
        fun fitRecipe(shape: Emulsion.Shape): Recipe =
            recipeFor(stockId, Attempt(shape, 0f, null, baseEv), paper, previewSize = 320)
                .copy(grain = false, halation = false, glare = false, diffusion = false)

        /** Develops one candidate and returns what it measures, without scoring it. */
        fun evaluateFingerprint(shape: Emulsion.Shape): Fingerprint? {
            Emulsion.write(base, shape, stockId, "Working") ?: return null
            return runCatching {
                SpektraEngine(dir).use { engine ->
                    val (bytes, _) = Develop.renderWith(engine, context, source, fitRecipe(shape), preview = true)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Fingerprint.of(it) }
                }
            }.getOrNull()
        }

        fun evaluate(shape: Emulsion.Shape, keepImage: Boolean): Attempt? {
            Emulsion.write(base, shape, stockId, "Working") ?: return null
            return runCatching {
                val recipe = fitRecipe(shape)
                // A fresh engine each attempt: the profile changes between them, and an engine
                // that had already read the old one would keep using it. Creating one reads the
                // profile folder, which is why attempts are capped and the work stays small.
                SpektraEngine(dir).use { engine ->
                    val (bytes, _) = Develop.renderWith(engine, context, source, recipe, preview = true)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                    val fp = Fingerprint.of(bmp)
                    Attempt(shape, fp.distanceTo(target, judgeable), if (keepImage) bytes else null, baseEv)
                }
            }.getOrNull()
        }

        // Which way do the enlarger's filters push the colour?
        //
        // They are subtractive and the engine's sign convention is not something to assume:
        // more yellow filtration means less blue exposure on the paper, which can read as a
        // warmer or a cooler print depending on how the stage is written. So it is measured —
        // one nudge each, and the direction that comes back is what the search then trusts.
        // Guessing here is how a fit ends up chasing a colour cast instead of correcting it.
        var yellowDirection = 1f
        var magentaDirection = 1f
        var contrastDirection = 1f
        var couplersDirection = 1f
        runCatching {
            if (cancelled) return@runCatching
            val plain = evaluateFingerprint(Emulsion.Shape())
            val warmer = evaluateFingerprint(Emulsion.Shape(yFilter = 6f))
            val greener = evaluateFingerprint(Emulsion.Shape(mFilter = 6f))
            // Contrast and saturation are probed the same way rather than assumed: a steeper
            // paper should mean more contrast and more coupler action more saturation, but it is
            // the engine's answer that counts, not the textbook's.
            val steeper = evaluateFingerprint(Emulsion.Shape(printContrast = 1.3f))
            val richer = evaluateFingerprint(Emulsion.Shape(couplers = 1.5f))
            if (plain != null && warmer != null) {
                yellowDirection = if (warmer.neutralWarmth >= plain.neutralWarmth) 1f else -1f
            }
            if (plain != null && greener != null) {
                magentaDirection = if (greener.neutralGreen >= plain.neutralGreen) 1f else -1f
            }
            if (plain != null && steeper != null) {
                contrastDirection = if (steeper.contrast >= plain.contrast) 1f else -1f
            }
            if (plain != null && richer != null) {
                couplersDirection = if (richer.saturation >= plain.saturation) 1f else -1f
            }
            Log.i("Latent", "probe directions: yellow $yellowDirection, magenta $magentaDirection, " +
                "contrast $contrastDirection, couplers $couplersDirection")
            onProgress(Progress(0, rounds, null, "measured how each control moves the picture"))
        }

        // What can be judged: what the references show AND what the undeveloped test shot can
        // show, fixed now. Measured on the test shot developed as plainly as possible, so
        // regions that exist in it count, and ones that do not are not held against the fit.
        judgeable = evaluateFingerprint(Emulsion.Shape())?.let { Fingerprint.judgeable(target, it) }

        val start = evaluate(current, keepImage = true)
        if (start == null) {
            // If the very first attempt fails, every other one will fail the same way: stop and
            // say so rather than repeating the same error hundreds of times.
            onProgress(Progress(0, rounds, null, "could not build a film from ${baseStock.replace('_', ' ')}"))
            if (holdsLaneEarly) DevelopQueue.engineLane.release()
            source.close()
            return null
        }
        best = start; currentDistance = start.distance
        onProgress(Progress(0, rounds, best, "starting from ${baseStock.replace('_', ' ')}"))

        var tried = 0
        val holdsLane = holdsLaneEarly

        /**
         * Aim the two filters at the target's neutrals rather than searching for them.
         *
         * Brightness and colour balance each have one control with a direct effect, so the
         * error can be measured and corrected — like focusing a lens rather than guessing where
         * focus lies. Done every so often during the search, so a drift cannot settle in.
         */
        /** Tries one aimed change; keeps it only if the match genuinely improves. */
        fun tryAimed(v: FloatArray): Boolean {
            val aimed = Emulsion.Shape.from(v)
            val attempt = evaluate(aimed, keepImage = false) ?: return false
            if (attempt.distance >= currentDistance) return false
            current = aimed
            currentDistance = attempt.distance
            if (best == null || attempt.distance < best!!.distance) best = evaluate(aimed, keepImage = true) ?: attempt
            return true
        }

        /**
         * Aim the controls that have one direct effect, instead of waiting for the search to
         * stumble on them: the filters for colour balance, the paper contrast for contrast, the
         * couplers for saturation. Each is measured, moved by the amount that should close the
         * gap, and kept only if the match improves — so a badly judged step costs one develop
         * and is thrown away.
         */
        fun correctAim() {
            val fp = evaluateFingerprint(current) ?: return
            // Colour — only when the references actually showed something neutral. Without that
            // their neutral figures read zero, which means "perfectly grey", not "unknown", and
            // aiming at it would drag the picture grey for no reason.
            val neutralsJudgeable = (judgeable?.getOrNull(7) ?: target.coverage.getOrElse(7) { 0f }) > 0.01f
            if (neutralsJudgeable) {
                val warmError = fp.neutralWarmth - target.neutralWarmth
                val greenError = fp.neutralGreen - target.neutralGreen
                if (kotlin.math.abs(warmError) >= 0.02f || kotlin.math.abs(greenError) >= 0.02f) {
                    val v = current.asArray().copyOf()
                    // A rough gain: the filters run to twenty, the neutral figures to about one.
                    v[15] = (v[15] - yellowDirection * warmError * 14f).coerceIn(-20f, 20f)
                    v[16] = (v[16] - magentaDirection * greenError * 14f).coerceIn(-20f, 20f)
                    tryAimed(v)
                }
            }
            // Contrast, through the paper grade.
            val contrastError = fp.contrast - target.contrast
            if (kotlin.math.abs(contrastError) >= 0.01f) {
                val v = current.asArray().copyOf()
                v[18] = v[18] - contrastDirection * contrastError * 1.5f
                tryAimed(v)
            }
            // Saturation, through the couplers.
            val saturationError = fp.saturation - target.saturation
            if (kotlin.math.abs(saturationError) >= 0.005f) {
                val v = current.asArray().copyOf()
                v[19] = v[19] - couplersDirection * saturationError * 5f
                tryAimed(v)
            }
        }
        while (tried < rounds && !cancelled) {
            // One number at a time, by a step that shrinks as the search settles.
            val v = current.asArray().copyOf()
            // The print controls set the colour balance and were fixed until now, so they are
            // tried more often early on, when there is most to gain from them.
            // The print controls — balance, exposure and contrast — set the colour and the
            // brightness, and are worth far more attempts than any single curve parameter.
            val which = if (random.nextInt(100) < 50) 15 + random.nextInt(5)
            else random.nextInt(Emulsion.Shape.COUNT)
            val step = Emulsion.Shape.STEP[which] * temperature
            v[which] += if (random.nextBoolean()) step else -step
            val candidate = Emulsion.Shape.from(v)

            val attempt = evaluate(candidate, keepImage = false)
            tried++
            if (attempt != null && attempt.distance < currentDistance) {
                current = candidate
                currentDistance = attempt.distance
                if (best == null || attempt.distance < best!!.distance) {
                    // Re-run the winner keeping its picture, so the screen can show it.
                    best = evaluate(candidate, keepImage = true) ?: attempt
                }
            }
            // Settle gradually; a few larger jumps early, finer adjustments later.
            temperature = (1f - tried.toFloat() / rounds).coerceAtLeast(0.15f)
            // Correct the balance at intervals, so the curve search cannot drift the colour.
            if (tried % 40 == 0) correctAim()
            if (tried % 8 == 0) {
                onProgress(Progress(tried, rounds, best, "closest so far: ${percent(best?.distance)}"))
            }
        }
        if (holdsLane) DevelopQueue.engineLane.release()
        source.close()
        // The working profile is scratch: saving writes its own file under a chosen name.
        runCatching { EngineAssets.profileFile(stockId)?.delete() }
        onProgress(Progress(tried, rounds, best, if (cancelled) "stopped" else "finished"))
        Log.i("Latent", "reconstruction finished after $tried attempts, distance ${best?.distance}, " +
            "test shot ${source.width}x${source.height}, printed on ${paper ?: "no print — scanned directly"}")
        return best
    }

    /**
     * A readable score. The distance is no longer bounded at one — the figures are now scaled
     * so the measure discriminates properly — so subtracting it from 100 would peg anything
     * genuinely different at zero and hide the search's progress. This eases off instead, and
     * never quite reaches either end.
     */
    /**
     * The recipe a finished fit develops with: the emulsion it built, the print settings it
     * chose, whatever was adjusted by hand afterwards, and the texture read from the references.
     * One place, so the preview, the adjustments and the saved stock cannot drift apart.
     */
    fun recipeFor(
        stockId: String,
        attempt: Attempt,
        paper: String?,
        tweak: Tweak = Tweak(),
        texture: Texture? = null,
        previewSize: Int = 0,
    ): Recipe {
        var r = attempt.shape.applyPrintTo(
            Recipe(film = stockId, exposureEv = attempt.baseEv, previewMaxSize = previewSize),
        ).copy(
            paper = paper ?: Develop.DEFAULT_PAPER,
            scanFilm = paper == null,
            // Brightness in stops, applied where brightness actually lives: the enlarger's
            // exposure, which runs the other way — more light on the paper, a darker print.
            printExposure = (attempt.shape.printExposure / Math.pow(2.0, tweak.brightness.toDouble()).toFloat())
                .coerceIn(0.4f, 2.2f),
            yFilterShift = (attempt.shape.yFilter + tweak.warmCool).coerceIn(-20f, 20f),
            mFilterShift = (attempt.shape.mFilter + tweak.greenMagenta).coerceIn(-20f, 20f),
            outputColorSpace = tweak.outputSpace,
        )
        texture?.let { r = it.applyTo(r) }
        if (tweak.diffusion > 0.001f) {
            r = r.copy(diffusion = true, diffusionFamily = tweak.diffusionFamily, diffusionStrength = tweak.diffusion)
        }
        return r
    }

    /** Develops the test shot again with the fit's answer plus any adjustments. */
    fun render(
        context: Context,
        attempt: Attempt,
        testShot: Uri,
        isRaw: Boolean,
        baseStock: String,
        tweak: Tweak,
        texture: Texture?,
    ): ByteArray? {
        val dir = EngineAssets.directory ?: return null
        val base = Emulsion.baseProfile(context, baseStock) ?: return null
        val stockId = "celestial_working"
        Emulsion.write(base, attempt.shape, stockId, "Working") ?: return null
        if (!DevelopQueue.engineLane.tryAcquire()) return null
        return try {
            val source = if (isRaw) Develop.openRaw(context, testShot, 640) else Develop.openImage(context, testShot, 640)
            source.use { src ->
                val recipe = recipeFor(stockId, attempt, printFor(base), tweak, texture, previewSize = 560)
                Develop.denoiseSource(src, recipe, Develop.isoOf(context, testShot))
                Develop.fastDiffusionSource(src, recipe, preview = true)
                SpektraEngine(dir).use { engine -> Develop.renderWith(engine, context, src, recipe, preview = true).first }
            }
        } catch (t: Throwable) {
            Log.e("Latent", "could not re-render the result", t); null
        } finally {
            DevelopQueue.engineLane.release()
        }
    }

    fun percent(distance: Float?): String =
        if (distance == null) "—"
        else "${(100.0 * Math.exp(-1.2 * distance)).toInt().coerceIn(0, 99)}%"

    /** Saves the working emulsion under a name of the user's choosing. */
    fun save(context: Context, shape: Emulsion.Shape, baseStock: String, name: String): String? {
        val base = Emulsion.baseProfile(context, baseStock) ?: return null
        val id = "celestial_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifBlank { "stock_" + System.currentTimeMillis() / 1000 }
        return Emulsion.write(base, shape, id, name)
    }
}
