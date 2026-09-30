package com.celestial.latent.develop

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A film emulsion Latent can make.
 *
 * The engine describes a film's response with three S-curves per colour layer — each with a
 * centre, a height and a width — nine numbers a layer, twenty-seven in all. Those, plus how
 * sensitive each layer is across the spectrum, are what make one stock differ from another.
 * Rebuilding the engine's own curves from those numbers matches its stored ones to 0.004
 * density, so they are a complete description rather than an approximation of one.
 *
 * This writes a profile in the engine's own format from a set of such numbers, so a film that
 * never existed can be developed exactly like a measured one.
 */
object Emulsion {

    /**
     * What the search moves. Everything is a change from a starting emulsion rather than an
     * absolute, so a fit stays in the neighbourhood of something that behaves like film.
     */
    data class Shape(
        /** Where each layer's response sits: negative is more sensitive. */
        val centre: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** How much density each layer builds: contrast, per layer. */
        val height: FloatArray = floatArrayOf(1f, 1f, 1f),
        /** How gradually it builds: soft or abrupt. */
        val width: FloatArray = floatArrayOf(1f, 1f, 1f),
        /** A shift of each layer's spectral sensitivity, in nanometres. */
        val spectralShift: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** How sensitive each layer is overall, in stops. */
        val speed: FloatArray = floatArrayOf(0f, 0f, 0f),
        /**
         * How the print is made. These are not properties of the emulsion, but they decide the
         * colour balance of the result: the enlarger's yellow and magenta filters are how a
         * darkroom sets white balance, and no amount of moving the film's curves can stand in
         * for them. Leaving them fixed was why the colour never came right.
         */
        val yFilter: Float = 0f,
        val mFilter: Float = 0f,
        val printExposure: Float = 1f,
        val printContrast: Float = 1f,
        /**
         * The DIR couplers: the chemistry by which one colour layer holds its neighbours back
         * as it develops. It is how film makes colour stronger or softer, so it is the honest
         * control for saturation — the search had nothing that moved saturation directly.
         * Last in the list on purpose, after every other number, so nothing built by position
         * can shift.
         */
        val couplers: Float = 1f,
    ) {
        fun asArray() = centre + height + width + spectralShift + speed +
            floatArrayOf(yFilter, mFilter, printExposure, printContrast, couplers)

        /**
         * Carries the print settings into a recipe. The emulsion lives in the profile, but how
         * it is printed lives here — so a saved stock must take these with it, or it will not
         * look like what the search arrived at.
         */
        fun applyPrintTo(r: Recipe): Recipe = r.copy(
            yFilterShift = yFilter,
            mFilterShift = mFilter,
            printExposure = printExposure,
            printContrast = printContrast,
            dirAmount = couplers,
            // The fit set the brightness deliberately through the print exposure, so the stock
            // must not level it away — that would make the saved film look nothing like the
            // result that was approved.
            autoExposure = false,
        )

        companion object {
            const val COUNT = 20

            fun from(v: FloatArray) = Shape(
                centre = floatArrayOf(v[0], v[1], v[2]),
                height = floatArrayOf(v[3], v[4], v[5]),
                width = floatArrayOf(v[6], v[7], v[8]),
                spectralShift = floatArrayOf(v[9], v[10], v[11]),
                speed = floatArrayOf(v[12], v[13], v[14]),
                yFilter = v[15].coerceIn(-20f, 20f), mFilter = v[16].coerceIn(-20f, 20f),
                printExposure = v[17].coerceIn(0.4f, 2.2f),
                printContrast = v[18].coerceIn(0.6f, 1.6f),
                couplers = v[19].coerceIn(0.2f, 2.0f),
            )

            /** How far each number is allowed to move in one step of the search. */
            val STEP = floatArrayOf(
                0.12f, 0.12f, 0.12f,      // centre, in stops of log exposure
                0.06f, 0.06f, 0.06f,      // height
                0.06f, 0.06f, 0.06f,      // width
                6f, 6f, 6f,               // spectral shift, nm
                0.10f, 0.10f, 0.10f,      // speed, stops
                0.8f, 0.8f,               // enlarger filters — the white balance. The engine's own
                                          // presets use shifts of one or two, so the steps are
                                          // small: a jump of six would swing the balance wildly
                                          // and almost every attempt would be thrown away.
                0.05f, 0.04f,             // print exposure and paper contrast
                0.08f,                    // couplers — saturation
            )
        }
    }

    /** Reads a bundled profile to start from. */
    fun baseProfile(context: Context, stock: String): JSONObject? = try {
        val dir = EngineAssets.directory
        val text = if (dir != null) File(File(dir, "profiles"), "$stock.json").readText()
        else context.assets.open("spektra/profiles/$stock.json").bufferedReader().use { it.readText() }
        JSONObject(text)
    } catch (t: Throwable) {
        Log.e("Latent", "could not read the profile $stock", t); null
    }

    /**
     * Writes a new profile shaped by [shape], based on [base].
     *
     * @return the stock id the engine will know it by, or null if it could not be written.
     */
    /** Never let a value that is not a real number reach the file. */
    private fun safe(v: Double, fallback: Double = 0.0) = if (v.isFinite()) v else fallback

    fun write(base: JSONObject, shape: Shape, stockId: String, displayName: String): String? {
        val file = EngineAssets.profileFile(stockId) ?: return null
        if (shape.asArray().any { !it.isFinite() }) return null
        return try {
            val out = JSONObject(base.toString())
            val info = out.getJSONObject("info")
            info.put("stock", stockId)
            info.put("name", displayName)
            out.getJSONObject("metadata").put(
                "datasource",
                "Generated by Latent from a reference set, using the curve form of " +
                    base.getJSONObject("info").optString("stock") + ". Not a measurement of any real film.",
            )

            val data = out.getJSONObject("data")
            val model = data.getJSONObject("density_curves_model")
            val centres = model.getJSONArray("centers")
            val amps = model.getJSONArray("amplitudes")
            val sigmas = model.getJSONArray("sigmas")

            // Move the three S-curves of each layer.
            for (c in 0 until 3) {
                val ce = centres.getJSONArray(c); val am = amps.getJSONArray(c); val si = sigmas.getJSONArray(c)
                for (k in 0 until ce.length()) {
                    ce.put(k, safe(ce.getDouble(k) + shape.centre[c] - shape.speed[c]))
                    am.put(k, safe(am.getDouble(k) * shape.height[c], 0.001).coerceAtLeast(0.001))
                    si.put(k, safe(si.getDouble(k) * shape.width[c], 0.02).coerceAtLeast(0.02))
                }
            }

            // Rebuild the sampled curves from the moved model — this is what the engine reads.
            val logE = data.getJSONArray("log_exposure")
            val curves = JSONArray()
            val layers = JSONArray()
            val oldLayers = data.optJSONArray("density_curves_layers")
            for (i in 0 until logE.length()) {
                val x = logE.getDouble(i)
                val row = JSONArray()
                val layerRow = JSONArray()
                for (c in 0 until 3) {
                    val ce = centres.getJSONArray(c); val am = amps.getJSONArray(c); val si = sigmas.getJSONArray(c)
                    var v = 0.0
                    for (k in 0 until ce.length()) v += am.getDouble(k) * normalCdf(x, ce.getDouble(k), si.getDouble(k))
                    row.put(safe(v))
                }
                curves.put(row)

                // The per-layer curves are nested one level deeper than the totals: each row
                // holds one entry per SUBLAYER, and each of those holds the three channels.
                // Treating them as three plain numbers produced NaN — which JSON's "optional
                // double" returns silently rather than refusing — and every profile written
                // afterwards was rejected.
                oldLayers?.optJSONArray(i)?.let { sublayers ->
                    for (sub in 0 until sublayers.length()) {
                        val channels = sublayers.optJSONArray(sub) ?: continue
                        val scaled = JSONArray()
                        for (c in 0 until channels.length()) {
                            scaled.put(safe(channels.optDouble(c, 0.0) * shape.height[c.coerceAtMost(2)]))
                        }
                        layerRow.put(scaled)
                    }
                }
            }
            data.put("density_curves", curves)
            if (oldLayers != null) data.put("density_curves_layers", layers)

            // Shift and scale each layer's spectral sensitivity.
            val wl = data.getJSONArray("wavelengths")
            val sens = data.getJSONArray("log_sensitivity")
            val shifted = JSONArray()
            for (i in 0 until wl.length()) {
                val row = JSONArray()
                for (c in 0 until 3) {
                    val target = wl.getDouble(i) - shape.spectralShift[c]
                    row.put(safe(sampleAt(wl, sens, target, c) + shape.speed[c] * 0.301, -9.0))  // stops → log10
                }
                shifted.put(row)
            }
            data.put("log_sensitivity", shifted)

            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            Log.i("Latent", "emulsion written: $stockId")
            stockId
        } catch (t: Throwable) {
            Log.e("Latent", "could not write the emulsion", t); null
        }
    }

    /** Linear interpolation of a sensitivity curve at a wavelength that falls between samples. */
    private fun sampleAt(wl: JSONArray, sens: JSONArray, target: Double, channel: Int): Double {
        val n = wl.length()
        if (target <= wl.getDouble(0)) return sens.getJSONArray(0).getDouble(channel)
        if (target >= wl.getDouble(n - 1)) return sens.getJSONArray(n - 1).getDouble(channel)
        var i = 0
        while (i < n - 2 && wl.getDouble(i + 1) < target) i++
        val x0 = wl.getDouble(i); val x1 = wl.getDouble(i + 1)
        val y0 = sens.getJSONArray(i).getDouble(channel); val y1 = sens.getJSONArray(i + 1).getDouble(channel)
        val t = if (x1 > x0) (target - x0) / (x1 - x0) else 0.0
        return y0 + t * (y1 - y0)
    }

    /** The S-curve the engine's model is built from. */
    private fun normalCdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / (sigma.coerceAtLeast(1e-6) * sqrt(2.0))
        return 0.5 * (1.0 + erf(z))
    }

    /** Abramowitz and Stegun 7.1.26 — accurate to about 1.5e-7, far beyond what a curve needs. */
    private fun erf(x: Double): Double {
        val s = if (x < 0) -1.0 else 1.0
        val a = Math.abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * a)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-a * a)
        return s * y
    }
}
