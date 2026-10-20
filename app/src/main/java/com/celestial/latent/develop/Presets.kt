package com.celestial.latent.develop

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The 28 authored looks the engine ships in `spektra/presets.json`.
 *
 * These matter more than they sound: the engine gives every stock Portra 400's grain unless a
 * preset supplies the per-stock values, which is why films otherwise look alike. Each preset
 * also carries its own halation, couplers, density gamma and a deliberate exposure.
 */
object Presets {

    data class Preset(val id: String, val name: String, val group: String, val description: String, val recipe: Recipe)

    @Volatile private var loaded: List<Preset>? = null

    fun all(context: Context): List<Preset> {
        loaded?.let { return it }
        val list = try {
            val text = context.assets.open("spektra/presets.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val presets = root.optJSONArray("presets") ?: JSONArray()
            (0 until presets.length()).mapNotNull { i ->
                val p = presets.optJSONObject(i) ?: return@mapNotNull null
                val params = p.optJSONObject("params") ?: return@mapNotNull null
                Preset(
                    id = p.optString("id"),
                    name = p.optString("name"),
                    group = p.optString("group", "Other"),
                    description = p.optString("description"),
                    recipe = apply(params, Recipe()),
                )
            }
        } catch (t: Throwable) {
            Log.e("Latent", "could not read the built-in presets", t)
            emptyList()
        }
        Log.i("Latent", "presets: ${list.size} looks loaded")
        loaded = list
        return list
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
