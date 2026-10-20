package com.celestial.latent.develop

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import com.celestial.latent.AppSettings

/**
 * The 28 authored looks the engine ships in `spektra/presets.json`.
 *
 * These matter more than they sound: the engine gives every stock Portra 400's grain unless a
 * preset supplies the per-stock values, which is why films otherwise look alike. Each preset
 * also carries its own halation, couplers, density gamma and a deliberate exposure.
 */
object Presets {

    data class Preset(val id: String, val name: String, val group: String, val description: String, val recipe: Recipe)

    /** The looks as last read, and which cinema glow they were read with. */
    @Volatile private var loaded: Pair<Boolean, List<Preset>>? = null

    fun all(context: Context): List<Preset> {
        val trueGlow = AppSettings.load(context).trueCinemaGlow
        loaded?.let { if (it.first == trueGlow) return it.second }
        val list = try {
            val text = context.assets.open("spektra/presets.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val presets = withHalationGlow(root.optJSONArray("presets") ?: JSONArray())
            (0 until presets.length()).mapNotNull { i ->
                val p = presets.optJSONObject(i) ?: return@mapNotNull null
                val id = p.optString("id")
                val params = corrected(id, p.optJSONObject("params") ?: return@mapNotNull null, trueGlow)
                Preset(
                    id = id,
                    name = NAMES[id] ?: p.optString("name"),
                    group = p.optString("group", "Other"),
                    description = (if (trueGlow) TRUE_GLOW_TEXT[id] else null) ?: TEXTS[id] ?: p.optString("description"),
                    recipe = apply(params, Recipe()),
                )
            }
        } catch (t: Throwable) {
            Log.e("Latent", "could not read the built-in presets", t)
            emptyList()
        }
        Log.i("Latent", "presets: ${list.size} looks loaded" + if (trueGlow) " (cinema glow true to film)" else "")
        loaded = trueGlow to list
        return list
    }

    // ---- Akshay's corrections (Spektrafilm-android 1542b1f, 11 Sep 2026), read against the makers' datasheets ----
    //
    // Written out whole rather than taken from the engine's file, so that moving the engine pin later
    // (whose file carries them) changes nothing here, and the classic cinema glow stays available.

    /** Grain: Gold 200 is grainier than Portra 400 by Kodak's own index; Provia matches Velvia and E100; both Superia looks share their film's grain. */
    private val GRAIN = mapOf(
        "gold200_ektacolor_goldenhour" to """{"agxParticleScale": [0.96, 1.2, 2.3]}""",
        "provia100f_chrome_natural" to """{"agxParticleScale": [0.6, 0.8, 1.6]}""",
        "superia400_crystalarchive_cool" to """{"blur": 0.72, "agxParticleScale": [0.85, 1.05, 2.1]}""",
    )

    /**
     * The cinema glow, both ways. Vision3 and Verita carry rem-jet, a backing that stops most light
     * bouncing back through the film, so "true to film" puts them with the other professional
     * negatives (0.9). "Classic" is how these looks glowed before 70b, and stays the default.
     */
    private val CLASSIC_GLOW = mapOf(
        "vision3_250d_2383_daycinema" to """{"active": true, "halationAmount": 1.1, "scatterAmount": 1.0}""",
        "vision3_200t_2383_interior" to """{"active": true, "halationAmount": 1.15, "scatterAmount": 1.05, "boostEv": 0.3}""",
        "verita200d_2383_warmcine" to """{"active": true, "halationAmount": 1.2, "scatterAmount": 1.1, "boostEv": 0.4}""",
        "vision3_500t_2383_night" to """{"active": true, "halationAmount": 1.6, "scatterAmount": 1.3, "scatterCoreUm": [2.6, 2.3, 1.9], "boostEv": 1.0, "boostRange": 0.35, "protectEv": 3.5}""",
    )
    private val TRUE_GLOW = mapOf(
        "vision3_250d_2383_daycinema" to """{"active": true, "halationAmount": 0.9, "scatterAmount": 1.0}""",
        "vision3_200t_2383_interior" to """{"active": true, "halationAmount": 0.9, "scatterAmount": 0.9, "boostEv": 0.0}""",
        "verita200d_2383_warmcine" to """{"active": true, "halationAmount": 0.9, "scatterAmount": 1.0, "boostEv": 0.0}""",
        "vision3_500t_2383_night" to """{"active": true, "halationAmount": 0.9, "scatterAmount": 1.1, "scatterCoreUm": [2.6, 2.3, 1.9], "boostEv": 0.4, "boostRange": 0.35, "protectEv": 3.5}""",
    )
    private val TRUE_GLOW_TEXT = mapOf(
        "vision3_500t_2383_night" to "The fast tungsten workhorse on 2383: low-light latitude, neutral tungsten balance, and clean rem-jet-suppressed highlights around practicals.",
    )
    private val NAMES = mapOf("c200_crystalarchive_budget" to "Fujicolor C200 — Everyday Budget")
    private val TEXTS = mapOf(
        "c200_crystalarchive_budget" to "The Japan-made Fujicolor C200, now discontinued: economical, crisp, slightly cool color on Crystal Archive. Not today's \"Fujifilm 200\", which is rebadged Kodak Gold 200.",
        "portra800_push1_endura_night" to "Portra 800 rated EI 1600, pushed a stop: more contrast and a coarser, livelier grain, with thinner shadows.",
        "superia400_crystalarchive_cool" to "Fuji's cooler consumer 400, with clean mixed-light greens and blues on Crystal Archive.",
    )

    /** The 500T Halation Glow look, which newer engine files drop; kept as ours, exactly as it was. */
    private const val HALATION_GLOW_ID = "vision3_500t_halation_glow"
    private const val HALATION_GLOW = """{"id": "vision3_500t_halation_glow", "name": "Vision3 500T — Halation Glow", "group": "Low Light", "description": "A neon-night look on 500T: halation pushed hard around highlights with a warm scatter bloom and lifted print.", "params": {"filmProfile": "kodak_vision3_500t", "printProfile": "kodak_2383", "io": {"scanFilm": false, "outputColorSpace": "SRGB"}, "camera": {"exposureCompensationEv": 0.0}, "enlarger": {"preflashExposure": 0.04}, "filmRender": {"densityCurveGamma": 1.0, "grain": {"active": true, "sublayersActive": true, "blur": 0.75, "agxParticleScale": [0.95, 1.15, 2.3], "uniformity": [0.96, 0.96, 0.98], "microStructure": [0.28, 30]}, "halation": {"active": true, "halationAmount": 2.4, "scatterAmount": 1.5, "scatterCoreUm": [3.0, 2.6, 2.1], "scatterTailUm": [11.0, 10.5, 9.8], "boostEv": 1.2, "boostRange": 0.45, "protectEv": 3.0, "halationBounceDecay": 0.55}, "dirCouplers": {"active": true, "amount": 1.05}}, "scanner": {"unsharpMask": [0.5, 0.7]}}}"""

    private fun withHalationGlow(presets: JSONArray): JSONArray {
        if ((0 until presets.length()).any { presets.optJSONObject(it)?.optString("id") == HALATION_GLOW_ID }) return presets
        // where it used to sit: after the pushed Portra 800s
        val after = (0 until presets.length()).lastOrNull { presets.optJSONObject(it)?.optString("id")?.startsWith("portra800_push") == true }
        val out = JSONArray()
        for (i in 0 until presets.length()) {
            out.put(presets.get(i))
            if (i == after) out.put(JSONObject(HALATION_GLOW))
        }
        if (after == null) out.put(JSONObject(HALATION_GLOW))
        return out
    }

    /** [params] with the corrections above laid over it. */
    private fun corrected(id: String, params: JSONObject, trueGlow: Boolean): JSONObject {
        val grain = GRAIN[id]
        val glow = (if (trueGlow) TRUE_GLOW else CLASSIC_GLOW)[id]
        if (grain == null && glow == null) return params
        val p = JSONObject(params.toString())
        val film = p.optJSONObject("filmRender") ?: JSONObject().also { p.put("filmRender", it) }
        if (grain != null) {
            val g = film.optJSONObject("grain") ?: JSONObject().also { film.put("grain", it) }
            val fix = JSONObject(grain)
            fix.keys().forEach { k -> g.put(k, fix.get(k)) }
        }
        if (glow != null) film.put("halation", JSONObject(glow))
        return p
    }

    /**
     * After the cinema glow switch changes: if the film in use is one of those looks and still has
     * the glow it was given, it takes the other one. A glow changed by hand is left alone.
     */
    fun cinemaGlowChanged(context: Context, presetId: String, trueGlow: Boolean) {
        val from = (if (trueGlow) CLASSIC_GLOW else TRUE_GLOW)[presetId] ?: return
        val to = (if (trueGlow) TRUE_GLOW else CLASSIC_GLOW)[presetId] ?: return
        fun halation(h: String) = apply(JSONObject().put("filmRender", JSONObject().put("halation", JSONObject(h))), Recipe())
        val before = halation(from); val after = halation(to)
        val r = Recipes.current(context)
        val same = r.halationAmount == before.halationAmount && r.scatterAmount == before.scatterAmount &&
            r.halationBoostEv == before.halationBoostEv && r.halationBoostRange == before.halationBoostRange &&
            r.halationProtectEv == before.halationProtectEv && r.scatterCoreUm == before.scatterCoreUm
        if (!same) return
        Recipes.setCurrent(context, r.copy(halationAmount = after.halationAmount, scatterAmount = after.scatterAmount,
            halationBoostEv = after.halationBoostEv, halationBoostRange = after.halationBoostRange,
            halationProtectEv = after.halationProtectEv, scatterCoreUm = after.scatterCoreUm))
        LookBaker.invalidate()
    }

    /** The presets for one film, if any. */
    fun forFilm(context: Context, film: String) = all(context).filter { it.recipe.film == film }

    /** The first preset that uses [film] — a better starting point than bare defaults. */
    fun defaultFor(context: Context, film: String): Preset? = forFilm(context, film).firstOrNull()

    fun byId(context: Context, id: String): Preset? = all(context).firstOrNull { it.id == id }

    /** Maps the engine's nested preset schema onto our flat recipe. */
    private fun apply(p: JSONObject, base: Recipe): Recipe {
        var r = base
        r = r.copy(film = p.optString("filmProfile", r.film), paper = p.optString("printProfile", r.paper))

        p.optJSONObject("io")?.let { io ->
            r = r.copy(
                scanFilm = io.optBoolean("scanFilm", r.scanFilm),
                outputColorSpace = io.optString("outputColorSpace", r.outputColorSpace),
                outputGamutCompress = io.optString("outputGamutCompress", r.outputGamutCompress),
                inputGamutCompress = io.optString("inputGamutCompress", r.inputGamutCompress),
            )
        }
        p.optJSONObject("settings")?.let { st ->
            r = r.copy(
                rgbToRaw = st.optString("rgbToRawMethod", r.rgbToRaw),
                hanatosWindow = st.optBoolean("applyHanatos2025AdaptationWindow", r.hanatosWindow),
                hanatosSurface = st.optBoolean("applyHanatos2025AdaptationSurface", r.hanatosSurface),
                spectralBlur = st.f("spectralGaussianBlur", r.spectralBlur),
            )
        }
        p.optJSONObject("camera")?.let { c ->
            r = r.copy(
                exposureEv = c.f("exposureCompensationEv", r.exposureEv),
                autoExposure = c.optBoolean("autoExposure", r.autoExposure),
                meteringMethod = c.optString("autoExposureMethod", r.meteringMethod),
                lensBlurUm = c.f("lensBlurUm", r.lensBlurUm),
                filmFormatMm = c.f("filmFormatMm", r.filmFormatMm),
            )
            c.optJSONArray("filterUv")?.let { a ->
                r = r.copy(filterUvAmount = a.f(0, r.filterUvAmount), filterUvNm = a.f(1, r.filterUvNm), filterUvWidth = a.f(2, r.filterUvWidth))
            }
            c.optJSONArray("filterIr")?.let { a ->
                r = r.copy(filterIrAmount = a.f(0, r.filterIrAmount), filterIrNm = a.f(1, r.filterIrNm), filterIrWidth = a.f(2, r.filterIrWidth))
            }
            c.optJSONObject("diffusionFilter")?.let { d ->
                r = r.copy(
                    diffusion = d.optBoolean("active", r.diffusion),
                    diffusionFamily = d.optString("filterFamily", r.diffusionFamily),
                    diffusionStrength = d.f("strength", r.diffusionStrength),
                    diffusionScale = d.f("spatialScale", r.diffusionScale),
                    diffusionCore = d.f("coreIntensity", r.diffusionCore),
                    diffusionCoreSize = d.f("coreSize", r.diffusionCoreSize),
                    diffusionHalo = d.f("haloIntensity", r.diffusionHalo),
                    diffusionHaloSize = d.f("haloSize", r.diffusionHaloSize),
                    diffusionBloom = d.f("bloomIntensity", r.diffusionBloom),
                    diffusionBloomSize = d.f("bloomSize", r.diffusionBloomSize),
                    diffusionWarmth = d.f("haloWarmth", r.diffusionWarmth),
                )
            }
        }
        p.optJSONObject("filmRender")?.let { fr ->
            r = r.copy(filmContrast = fr.f("densityCurveGamma", r.filmContrast))
            fr.optJSONObject("grain")?.let { g ->
                r = r.copy(
                    grain = g.optBoolean("active", r.grain),
                    grainSublayers = g.optBoolean("sublayersActive", r.grainSublayers),
                    grainSizeUm2 = g.f("agxParticleAreaUm2", r.grainSizeUm2),
                    grainBlur = g.f("blur", r.grainBlur),
                    grainDyeCloudUm = g.f("blurDyeCloudsUm", r.grainDyeCloudUm),
                    grainSublayerCount = g.optInt("nSubLayers", r.grainSublayerCount),
                    grainParticleScale = g.floats("agxParticleScale", r.grainParticleScale),
                    grainParticleScaleLayers = g.floats("agxParticleScaleLayers", r.grainParticleScaleLayers),
                    grainUniformity = g.floats("uniformity", r.grainUniformity),
                    grainDensityMin = g.floats("densityMin", r.grainDensityMin),
                )
                g.optJSONArray("microStructure")?.let { a ->
                    r = r.copy(grainMicroAmount = a.f(0, r.grainMicroAmount), grainMicroScale = a.f(1, r.grainMicroScale))
                }
            }
            fr.optJSONObject("halation")?.let { h ->
                r = r.copy(
                    halation = h.optBoolean("active", r.halation),
                    halationAmount = h.f("halationAmount", r.halationAmount),
                    halationScale = h.f("halationSpatialScale", r.halationScale),
                    scatterAmount = h.f("scatterAmount", r.scatterAmount),
                    halationBoostEv = h.f("boostEv", r.halationBoostEv),
                    halationProtectEv = h.f("protectEv", r.halationProtectEv),
                    halationBounces = h.optInt("halationNBounces", r.halationBounces),
                    halationDecay = h.f("halationBounceDecay", r.halationDecay),
                    halationBoostRange = h.f("boostRange", r.halationBoostRange),
                    scatterCoreUm = h.floats("scatterCoreUm", r.scatterCoreUm),
                    scatterTailUm = h.floats("scatterTailUm", r.scatterTailUm),
                    scatterTailWeight = h.floats("scatterTailWeight", r.scatterTailWeight),
                )
            }
            fr.optJSONObject("dirCouplers")?.let { d ->
                r = r.copy(
                    dir = d.optBoolean("active", r.dir),
                    dirAmount = d.f("amount", r.dirAmount),
                    dirSameLayer = d.f("inhibitionSamelayer", r.dirSameLayer),
                    dirInterLayer = d.f("inhibitionInterlayer", r.dirInterLayer),
                    dirDiffusionUm = d.f("diffusionSizeUm", r.dirDiffusionUm),
                    dirDiffusionTailUm = d.f("diffusionTailUm", r.dirDiffusionTailUm),
                    dirDiffusionTailWeight = d.f("diffusionTailWeight", r.dirDiffusionTailWeight),
                )
            }
            fr.optJSONObject("glare")?.let { g ->
                r = r.copy(
                    glare = g.optBoolean("active", r.glare),
                    glarePercent = g.f("percent", r.glarePercent),
                    glareRoughness = g.f("roughness", r.glareRoughness),
                    glareBlur = g.f("blur", r.glareBlur),
                )
            }
        }
        p.optJSONObject("printRender")?.let { pr ->
            r = r.copy(printContrast = pr.f("densityCurveGamma", r.printContrast))
            // The print's glare: we have one glare for film and print, as Akshay's app does, so a look
            // that sets it on the print sets it for both (70a; Portra 800 Wide Gamut Glow's 6%).
            pr.optJSONObject("glare")?.let { g ->
                r = r.copy(
                    glare = g.optBoolean("active", r.glare),
                    glarePercent = g.f("percent", r.glarePercent),
                    glareRoughness = g.f("roughness", r.glareRoughness),
                    glareBlur = g.f("blur", r.glareBlur),
                )
            }
        }
        // The look's tone curve (70a): the master curve only, which is all the engine's looks use.
        p.optJSONObject("toneCurve")?.let { tc ->
            val pts = tc.optJSONArray("master")
            if (tc.optBoolean("active", false) && pts != null && pts.length() >= 2) {
                r = r.copy(toneCurve = (0 until pts.length()).flatMap { i ->
                    val pt = pts.optJSONArray(i) ?: return@flatMap emptyList<Float>()
                    listOf(pt.f(0, 0f), pt.f(1, 0f))
                })
            }
        }
        p.optJSONObject("enlarger")?.let { e ->
            r = r.copy(
                printExposure = e.f("printExposure", r.printExposure),
                printExposureCompensation = e.optBoolean("printExposureCompensation", r.printExposureCompensation),
                yFilterShift = e.f("yFilterShift", r.yFilterShift),
                mFilterShift = e.f("mFilterShift", r.mFilterShift),
                preflash = e.f("preflashExposure", r.preflash),
                enlargerLensBlur = e.f("lensBlur", r.enlargerLensBlur),
            )
            e.optJSONObject("diffusionFilter")?.let { d ->
                r = r.copy(
                    printDiffusion = d.optBoolean("active", r.printDiffusion),
                    printDiffusionFamily = d.optString("filterFamily", r.printDiffusionFamily),
                    printDiffusionStrength = d.f("strength", r.printDiffusionStrength),
                )
            }
        }
        p.optJSONObject("scanner")?.let { sc ->
            r = r.copy(
                scannerLensBlur = sc.f("lensBlur", r.scannerLensBlur),
                whiteCorrection = sc.optBoolean("whiteCorrection", r.whiteCorrection),
                blackCorrection = sc.optBoolean("blackCorrection", r.blackCorrection),
                scannerWhiteLevel = sc.f("whiteLevel", r.scannerWhiteLevel),
                scannerBlackLevel = sc.f("blackLevel", r.scannerBlackLevel),
            )
            sc.optJSONArray("unsharpMask")?.let { a ->
                r = r.copy(unsharpAmount = a.f(0, r.unsharpAmount), unsharpRadius = a.f(1, r.unsharpRadius))
            }
        }
        return r
    }

    private fun JSONObject.f(key: String, fallback: Float) = optDouble(key, fallback.toDouble()).toFloat()
    private fun JSONArray.f(i: Int, fallback: Float) = optDouble(i, fallback.toDouble()).toFloat()
    private fun JSONObject.floats(key: String, fallback: List<Float>): List<Float> {
        val a = optJSONArray(key) ?: return fallback
        return (0 until a.length()).map { a.optDouble(it, 0.0).toFloat() }
    }
}
