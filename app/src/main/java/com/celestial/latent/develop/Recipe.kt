package com.celestial.latent.develop

import android.content.Context
import com.spectrafilm.engine.CameraParams
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.DirCouplersParams
import com.spectrafilm.engine.InputGamutCompress
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.OutputGamutCompress
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.engine.SettingsParams
import com.spectrafilm.engine.DiffusionFilterParams
import com.spectrafilm.engine.EnlargerParams
import com.spectrafilm.engine.FilmRenderingParams
import com.spectrafilm.engine.GlareParams
import com.spectrafilm.engine.GrainParams
import com.spectrafilm.engine.HalationParams
import com.spectrafilm.engine.PrintRenderingParams
import com.spectrafilm.engine.ScannerParams
import com.spectrafilm.engine.SpektraParams
import org.json.JSONObject

/**
 * The settings a photo is developed with. A flat, saveable set of the engine parameters that
 * actually change the picture — everything else keeps the film profile's own defaults.
 */
/**
 * The print exposure the darkroom offers, everywhere it is set. Wide enough for a very thin
 * negative (a short print) or a very dense one (a long print): about 1 to 24 seconds on PRINT
 * mode's timer. The engine itself has no limit; it simply multiplies the light. These used to be
 * 0.4–2.2, written separately in several places — which left an underexposed shot printing too
 * dark even on its lightest test strip.
 */
const val PRINT_EXPOSURE_MIN = 0.15f
const val PRINT_EXPOSURE_MAX = 3.0f

data class Recipe(
    val film: String = "kodak_portra_400",
    val paper: String = Develop.DEFAULT_PAPER,
    // Film & exposure
    val exposureEv: Float = 0f,
    val pushStops: Float = 0f,          // push/pull, applied as film density gamma
    val filmContrast: Float = 1.0f,     // film density curve gamma
    // Halation.
    // Note: the engine defaults grain, halation, couplers and glare to OFF, because it is a
    // library. Latent is a film camera, so they default ON — the authored looks turn them on
    // anyway, and a photo with no grain is not what anyone opens this app for.
    val halation: Boolean = true,
    val halationAmount: Float = 1.0f,
    val halationScale: Float = 1.0f,
    val scatterAmount: Float = 1.0f,
    val halationBoostEv: Float = 0f,
    // Grain
    val grain: Boolean = true,
    val grainSizeUm2: Float = 0.2f,
    val grainBlur: Float = 0.5f,      // the engine's own default
    val grainSublayers: Boolean = true,
    // Halation, continued
    val halationProtectEv: Float = 4.0f,
    val halationBounces: Int = 3,
    val halationDecay: Float = 0.5f,
    /** Per-layer scatter geometry, which the presets vary per stock. */
    val scatterCoreUm: List<Float> = listOf(2.2f, 2.0f, 1.6f),
    val scatterTailUm: List<Float> = listOf(9.3f, 9.7f, 9.1f),
    val scatterTailWeight: List<Float> = listOf(0.78f, 0.65f, 0.67f),
    val halationBoostRange: Float = 0.3f,
    // Grain, continued
    val grainMicroAmount: Float = 0.2f,
    val grainMicroScale: Float = 30f,
    val grainDyeCloudUm: Float = 1.0f,
    val grainSublayerCount: Int = 1,
    /**
     * Per-layer grain, which is most of why one stock looks different from another: without
     * these the engine gives every film Portra 400's grain. Three values, one per colour layer.
     */
    // These match the engine's own defaults exactly: a recipe that does not set them must
    // render identically to the engine's defaults, not to numbers of ours.
    val grainParticleScale: List<Float> = listOf(0.8f, 1.0f, 2.0f),
    val grainParticleScaleLayers: List<Float> = listOf(2.5f, 1.0f, 0.5f),
    val grainUniformity: List<Float> = listOf(0.97f, 0.97f, 0.99f),
    val grainDensityMin: List<Float> = listOf(0.07f, 0.08f, 0.12f),
    /** How strong the grain is, over the whole picture (61c): 0 none, 1 the film's own, 1.5 stronger. */
    val grainAmount: Float = 1f,
    /** Some presets set a deliberate exposure instead of letting the engine level every shot. */
    val autoExposure: Boolean = true,
    // DIR couplers (chemistry: saturation and edge contrast)
    val dir: Boolean = true,
    val dirAmount: Float = 1.0f,
    val dirSameLayer: Float = 1.0f,
    val dirInterLayer: Float = 1.0f,
    val dirDiffusionUm: Float = 20f,
    val dirDiffusionTailUm: Float = 200f,
    val dirDiffusionTailWeight: Float = 0.06f,
    // Camera
    /** Colour-noise cleanup before the film sees the image. -1 = choose from the shot's ISO. */
    val chromaDenoise: Float = -1f,
    val lensBlurUm: Float = 0f,
    /**
     * The camera's UV and infrared cut filters. Film sees a little beyond human vision at both
     * ends, and the spectral upsampling was built for human vision — so without these, reds and
     * blues overshoot. Each is (amount, wavelength in nm, softness in nm); amount 0 = no filter.
     */
    val filterUvAmount: Float = 0f,
    val filterUvNm: Float = 410f,
    val filterUvWidth: Float = 8f,
    val filterIrAmount: Float = 0f,
    val filterIrNm: Float = 675f,
    val filterIrWidth: Float = 15f,
    /** How the engine meters the scene when working out its own exposure. */
    val meteringMethod: String = "center_weighted",
    /** Hanatos 2025 adaptation, the other half of the same taming work. */
    val hanatosWindow: Boolean = true,
    val hanatosSurface: Boolean = false,
    val filmFormatMm: Float = 35f,
    /**
     * An 85B filter on the lens: the orange filter cinematographers put on to shoot tungsten
     * (3200K) film in daylight. Applied to the light before it reaches the film.
     */
    val lens85b: Boolean = false,
    // Diffusion filter on the lens.
    /**
     * How the diffusion filter is computed for the EXPORT.
     *
     * true (the default) = the same kernel, convolved with an FFT — seconds instead of minutes,
     *   with the widest part of the glow worked out on a quarter-size copy (0.5% from exact).
     * false = the engine's own direct convolution: identical by definition, and so slow at full
     *   resolution that a single photo can take longer than you will wait.
     *
     * Previews always use the fast path whatever this says — the engine's own guidance is
     * "approximate while editing, exact on export", and a 640 px preview took 88 seconds the
     * other way.
     */
    val fastDiffusion: Boolean = true,
    val diffusion: Boolean = false,
    val diffusionFamily: String = "black_pro_mist",
    val diffusionStrength: Float = 0.5f,
    val diffusionScale: Float = 1.0f,
    val diffusionCore: Float = 1.0f,
    val diffusionCoreSize: Float = 1.0f,
    val diffusionHalo: Float = 1.0f,
    val diffusionHaloSize: Float = 1.0f,
    val diffusionBloom: Float = 1.0f,
    val diffusionBloomSize: Float = 1.0f,
    val diffusionWarmth: Float = 0f,
    // Diffusion filter on the enlarger (printing through a filter)
    val printDiffusion: Boolean = false,
    val printDiffusionFamily: String = "black_pro_mist",
    val printDiffusionStrength: Float = 0.5f,
    // Enlarger / paper
    val printExposure: Float = 1.0f,
    val yFilterShift: Float = 0f,
    val mFilterShift: Float = 0f,
    val preflash: Float = 0f,
    val printContrast: Float = 1.0f,
    /**
     * On by default, as in the engine: the enlarger recomputes its exposure from the film
     * exposure, exactly as a printer would — which is why film exposure changes contrast and
     * colour rather than print brightness. Turn off to let film exposure change brightness.
     */
    val printExposureCompensation: Boolean = true,
    val normalizePrintExposure: Boolean = true,
    val enlargerLensBlur: Float = 0f,
    val yFilterNeutral: Float = 55f,
    val mFilterNeutral: Float = 65f,
    // Scanner
    val unsharpAmount: Float = 0.7f,
    val unsharpRadius: Float = 0.7f,
    val whiteCorrection: Boolean = false,
    val blackCorrection: Boolean = false,
    val scannerWhiteLevel: Float = 0.98f,
    val scannerBlackLevel: Float = 0.01f,
    val scannerLensBlur: Float = 0f,
    val scanFilm: Boolean = false,          // skip the print stage: the scanned-negative look
    // Output & colour
    val glare: Boolean = true,
    val glarePercent: Float = 0.03f,
    val glareRoughness: Float = 0.7f,
    val glareBlur: Float = 0.5f,
    val outputColorSpace: String = "SRGB",
    val outputGamutCompress: String = "LEGACY_CLIP",
    val inputGamutCompress: String = "OFF",
    // Engine
    val rgbToRaw: String = "HANATOS2025",
    val spectralBlur: Float = 0f,
    // GPU is preview-only by the engine's own rule: its float maths is not bit-reproducible
    // across vendors, so export and the parity path stay on the CPU.
    val gpuPreview: Boolean = false,
    val previewMaxSize: Int = 640,   // the size the engine's own editor uses interactively
    /**
     * Brightness noise cleaned before the film: −1 automatic from the ISO, else 0 … 1. The
     * sensor's digital speckle, which film never sees; the film's own grain is the texture.
     */
    val lumaDenoise: Float = -1f,
) {
    private fun triple(v: List<Float>, fallback: Float) =
        Triple(v.getOrElse(0) { fallback }, v.getOrElse(1) { fallback }, v.getOrElse(2) { fallback })

    /** Build the engine's parameter tree from this recipe. */
    fun toParams(): SpektraParams = SpektraParams(
        filmProfile = film,
        printProfile = paper,
        camera = CameraParams(
            exposureCompensationEv = exposureEv,
            autoExposure = autoExposure,
            autoExposureMethod = meteringMethod,
            lensBlurUm = lensBlurUm,
            filmFormatMm = filmFormatMm,
            filterUv = Triple(filterUvAmount, filterUvNm, filterUvWidth),
            filterIr = Triple(filterIrAmount, filterIrNm, filterIrWidth),
            diffusionFilter = DiffusionFilterParams(
                // Ours runs before the engine, so the engine's own stays off in that case.
                active = diffusion && !fastDiffusion,
                filterFamily = diffusionFamily, strength = diffusionStrength, spatialScale = diffusionScale,
                coreIntensity = diffusionCore, coreSize = diffusionCoreSize,
                haloIntensity = diffusionHalo, haloSize = diffusionHaloSize,
                bloomIntensity = diffusionBloom, bloomSize = diffusionBloomSize, haloWarmth = diffusionWarmth,
            ),
        ),
        filmRender = FilmRenderingParams(
            // Pushing film raises contrast: fold the push into the density gamma.
            densityCurveGamma = filmContrast * Math.pow(1.12, pushStops.toDouble()).toFloat(),
            grain = GrainParams(
                active = grain, sublayersActive = grainSublayers, agxParticleAreaUm2 = grainSizeUm2, blur = grainBlur,
                blurDyeCloudsUm = grainDyeCloudUm, microStructure = grainMicroAmount to grainMicroScale, nSubLayers = grainSublayerCount,
                agxParticleScale = triple(grainParticleScale, 1f),
                agxParticleScaleLayers = triple(grainParticleScaleLayers, 1f),
                uniformity = triple(grainUniformity, 0.97f),
                densityMin = triple(grainDensityMin, 0.07f),
                amount = grainAmount.coerceIn(0f, 1.5f),
            ),
            halation = HalationParams(active = halation, halationAmount = halationAmount, halationSpatialScale = halationScale,
                scatterAmount = scatterAmount, boostEv = halationBoostEv, protectEv = halationProtectEv,
                halationNBounces = halationBounces, halationBounceDecay = halationDecay,
                scatterCoreUm = triple(scatterCoreUm, 2f), scatterTailUm = triple(scatterTailUm, 9f),
                scatterTailWeight = triple(scatterTailWeight, 0.7f), boostRange = halationBoostRange),
            dirCouplers = DirCouplersParams(active = dir, amount = dirAmount, inhibitionSamelayer = dirSameLayer,
                inhibitionInterlayer = dirInterLayer, diffusionSizeUm = dirDiffusionUm,
                diffusionTailUm = dirDiffusionTailUm, diffusionTailWeight = dirDiffusionTailWeight),
            glare = GlareParams(active = glare, percent = glarePercent, roughness = glareRoughness, blur = glareBlur),
        ),
        enlarger = EnlargerParams(
            printExposure = printExposure, yFilterShift = yFilterShift, mFilterShift = mFilterShift,
            yFilterNeutral = yFilterNeutral, mFilterNeutral = mFilterNeutral,
            preflashExposure = preflash, lensBlur = enlargerLensBlur,
            printExposureCompensation = printExposureCompensation,
            normalizePrintExposure = normalizePrintExposure,
            diffusionFilter = DiffusionFilterParams(active = printDiffusion, filterFamily = printDiffusionFamily, strength = printDiffusionStrength),
        ),
        printRender = PrintRenderingParams(densityCurveGamma = printContrast, glare = GlareParams(active = glare, percent = glarePercent)),
        scanner = ScannerParams(
            lensBlur = scannerLensBlur, unsharpMask = unsharpAmount to unsharpRadius,
            whiteCorrection = whiteCorrection, blackCorrection = blackCorrection,
            whiteLevel = scannerWhiteLevel, blackLevel = scannerBlackLevel,
        ),
        io = IoParams(
            // Latent's own spaces (Display P3, Rec.709) are applied after the engine, so the
            // engine itself is asked for sRGB in those cases — see Develop.render.
            outputColorSpace = runCatching { ColorSpace.valueOf(outputColorSpace) }.getOrDefault(ColorSpace.SRGB),
            outputGamutCompress = runCatching { OutputGamutCompress.valueOf(outputGamutCompress) }.getOrDefault(OutputGamutCompress.LEGACY_CLIP),
            inputGamutCompress = runCatching { InputGamutCompress.valueOf(inputGamutCompress) }.getOrDefault(InputGamutCompress.OFF),
            scanFilm = scanFilm,
        ),
        settings = SettingsParams(
            rgbToRawMethod = runCatching { Rgb2Raw.valueOf(rgbToRaw) }.getOrDefault(Rgb2Raw.HANATOS2025),
            applyHanatos2025AdaptationWindow = hanatosWindow,
            applyHanatos2025AdaptationSurface = hanatosSurface,
            spectralGaussianBlur = spectralBlur,
            gpuPreview = gpuPreview, gpuExport = false,
            previewMaxSize = previewMaxSize,
        ),
    )

    /** A one-line summary of the settings that shape the look, for the log. */
    fun summary(): String = "film=$film paper=$paper scanFilm=$scanFilm ev=$exposureEv autoExp=$autoExposure " +
        "contrast=$filmContrast printExp=$printExposure printComp=$printExposureCompensation " +
        "grain=$grain(${grainSizeUm2}, scale=$grainParticleScale, amount=$grainAmount) halation=$halation($halationAmount) " +
        "dir=$dir($dirAmount) glare=$glare " +
        "lensFilter=$diffusion${if (diffusion) "/$diffusionFamily/s=$diffusionStrength/scale=$diffusionScale/core=$diffusionCore/halo=$diffusionHalo/bloom=$diffusionBloom" else ""} " +
        "enlargerFilter=$printDiffusion${if (printDiffusion) "/$printDiffusionFamily/s=$printDiffusionStrength" else ""} " +
        "out=$outputColorSpace/$outputGamutCompress format=${filmFormatMm}mm${if (lens85b) " 85B" else ""}"

    fun toJson(): String = JSONObject().apply {
        put("film", film); put("paper", paper)
        put("exposureEv", exposureEv.toDouble()); put("pushStops", pushStops.toDouble()); put("filmContrast", filmContrast.toDouble())
        put("halation", halation); put("halationAmount", halationAmount.toDouble()); put("halationScale", halationScale.toDouble())
        put("scatterAmount", scatterAmount.toDouble()); put("halationBoostEv", halationBoostEv.toDouble())
        put("halationProtectEv", halationProtectEv.toDouble()); put("halationBounces", halationBounces); put("halationDecay", halationDecay.toDouble())
        put("grain", grain); put("grainSizeUm2", grainSizeUm2.toDouble()); put("grainBlur", grainBlur.toDouble()); put("grainSublayers", grainSublayers)
        put("grainMicroAmount", grainMicroAmount.toDouble()); put("grainMicroScale", grainMicroScale.toDouble())
        put("grainDyeCloudUm", grainDyeCloudUm.toDouble()); put("grainSublayerCount", grainSublayerCount)
        put("grainParticleScale", org.json.JSONArray(grainParticleScale.map { it.toDouble() }))
        put("grainParticleScaleLayers", org.json.JSONArray(grainParticleScaleLayers.map { it.toDouble() }))
        put("grainUniformity", org.json.JSONArray(grainUniformity.map { it.toDouble() }))
        put("grainDensityMin", org.json.JSONArray(grainDensityMin.map { it.toDouble() }))
        put("grainAmount", grainAmount.toDouble())
        put("autoExposure", autoExposure)
        put("dir", dir); put("dirAmount", dirAmount.toDouble()); put("dirSameLayer", dirSameLayer.toDouble())
        put("dirInterLayer", dirInterLayer.toDouble()); put("dirDiffusionUm", dirDiffusionUm.toDouble())
        put("dirDiffusionTailUm", dirDiffusionTailUm.toDouble()); put("dirDiffusionTailWeight", dirDiffusionTailWeight.toDouble())
        put("scatterCoreUm", org.json.JSONArray(scatterCoreUm.map { it.toDouble() }))
        put("scatterTailUm", org.json.JSONArray(scatterTailUm.map { it.toDouble() }))
        put("scatterTailWeight", org.json.JSONArray(scatterTailWeight.map { it.toDouble() }))
        put("halationBoostRange", halationBoostRange.toDouble())
        put("chromaDenoise", chromaDenoise.toDouble())
        put("lumaDenoise", lumaDenoise.toDouble())
        put("filterUvAmount", filterUvAmount.toDouble()); put("filterUvNm", filterUvNm.toDouble()); put("filterUvWidth", filterUvWidth.toDouble())
        put("filterIrAmount", filterIrAmount.toDouble()); put("filterIrNm", filterIrNm.toDouble()); put("filterIrWidth", filterIrWidth.toDouble())
        put("meteringMethod", meteringMethod)
        put("hanatosWindow", hanatosWindow); put("hanatosSurface", hanatosSurface)
        put("printExposureCompensation", printExposureCompensation); put("normalizePrintExposure", normalizePrintExposure)
        put("lensBlurUm", lensBlurUm.toDouble()); put("filmFormatMm", filmFormatMm.toDouble()); put("lens85b", lens85b)
        put("fastDiffusion", fastDiffusion)
        put("diffusion", diffusion); put("diffusionFamily", diffusionFamily); put("diffusionStrength", diffusionStrength.toDouble())
        put("diffusionScale", diffusionScale.toDouble()); put("diffusionCore", diffusionCore.toDouble()); put("diffusionCoreSize", diffusionCoreSize.toDouble())
        put("diffusionHalo", diffusionHalo.toDouble()); put("diffusionHaloSize", diffusionHaloSize.toDouble())
        put("diffusionBloom", diffusionBloom.toDouble()); put("diffusionBloomSize", diffusionBloomSize.toDouble()); put("diffusionWarmth", diffusionWarmth.toDouble())
        put("printDiffusion", printDiffusion); put("printDiffusionFamily", printDiffusionFamily); put("printDiffusionStrength", printDiffusionStrength.toDouble())
        put("printExposure", printExposure.toDouble()); put("yFilterShift", yFilterShift.toDouble()); put("mFilterShift", mFilterShift.toDouble())
        put("preflash", preflash.toDouble()); put("printContrast", printContrast.toDouble()); put("enlargerLensBlur", enlargerLensBlur.toDouble())
        put("yFilterNeutral", yFilterNeutral.toDouble()); put("mFilterNeutral", mFilterNeutral.toDouble())
        put("unsharpAmount", unsharpAmount.toDouble()); put("unsharpRadius", unsharpRadius.toDouble())
        put("whiteCorrection", whiteCorrection); put("blackCorrection", blackCorrection)
        put("scannerWhiteLevel", scannerWhiteLevel.toDouble()); put("scannerBlackLevel", scannerBlackLevel.toDouble())
        put("scannerLensBlur", scannerLensBlur.toDouble()); put("scanFilm", scanFilm)
        put("glare", glare); put("glarePercent", glarePercent.toDouble()); put("glareRoughness", glareRoughness.toDouble()); put("glareBlur", glareBlur.toDouble())
        put("outputColorSpace", outputColorSpace); put("outputGamutCompress", outputGamutCompress); put("inputGamutCompress", inputGamutCompress)
        put("rgbToRaw", rgbToRaw); put("spectralBlur", spectralBlur.toDouble()); put("gpuPreview", gpuPreview); put("previewMaxSize", previewMaxSize)
    }.toString()

    companion object {
        fun fromJson(s: String): Recipe = try {
            val o = JSONObject(s); val d = Recipe()
            fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat()
            fun floats(obj: JSONObject, k: String, fallback: List<Float>): List<Float> {
                val a = obj.optJSONArray(k) ?: return fallback
                return (0 until a.length()).map { a.optDouble(it, 0.0).toFloat() }
            }
            Recipe(
                film = o.optString("film", d.film), paper = o.optString("paper", d.paper),
                exposureEv = f("exposureEv", d.exposureEv), pushStops = f("pushStops", d.pushStops), filmContrast = f("filmContrast", d.filmContrast),
                halation = o.optBoolean("halation", d.halation), halationAmount = f("halationAmount", d.halationAmount),
                halationScale = f("halationScale", d.halationScale), scatterAmount = f("scatterAmount", d.scatterAmount),
                halationBoostEv = f("halationBoostEv", d.halationBoostEv), halationProtectEv = f("halationProtectEv", d.halationProtectEv),
                halationBounces = o.optInt("halationBounces", d.halationBounces), halationDecay = f("halationDecay", d.halationDecay),
                grain = o.optBoolean("grain", d.grain), grainSizeUm2 = f("grainSizeUm2", d.grainSizeUm2), grainBlur = f("grainBlur", d.grainBlur),
                grainSublayers = o.optBoolean("grainSublayers", d.grainSublayers), grainMicroAmount = f("grainMicroAmount", d.grainMicroAmount),
                grainMicroScale = f("grainMicroScale", d.grainMicroScale), grainDyeCloudUm = f("grainDyeCloudUm", d.grainDyeCloudUm),
                grainSublayerCount = o.optInt("grainSublayerCount", d.grainSublayerCount),
                grainParticleScale = floats(o, "grainParticleScale", d.grainParticleScale),
                grainParticleScaleLayers = floats(o, "grainParticleScaleLayers", d.grainParticleScaleLayers),
                grainUniformity = floats(o, "grainUniformity", d.grainUniformity),
                grainDensityMin = floats(o, "grainDensityMin", d.grainDensityMin),
                grainAmount = f("grainAmount", d.grainAmount),
                autoExposure = o.optBoolean("autoExposure", d.autoExposure),
                dir = o.optBoolean("dir", d.dir), dirAmount = f("dirAmount", d.dirAmount), dirSameLayer = f("dirSameLayer", d.dirSameLayer),
                dirInterLayer = f("dirInterLayer", d.dirInterLayer), dirDiffusionUm = f("dirDiffusionUm", d.dirDiffusionUm),
                dirDiffusionTailUm = f("dirDiffusionTailUm", d.dirDiffusionTailUm),
                dirDiffusionTailWeight = f("dirDiffusionTailWeight", d.dirDiffusionTailWeight),
                scatterCoreUm = floats(o, "scatterCoreUm", d.scatterCoreUm),
                scatterTailUm = floats(o, "scatterTailUm", d.scatterTailUm),
                scatterTailWeight = floats(o, "scatterTailWeight", d.scatterTailWeight),
                halationBoostRange = f("halationBoostRange", d.halationBoostRange),
                chromaDenoise = f("chromaDenoise", d.chromaDenoise),
                lumaDenoise = f("lumaDenoise", d.lumaDenoise),
                filterUvAmount = f("filterUvAmount", d.filterUvAmount), filterUvNm = f("filterUvNm", d.filterUvNm),
                filterUvWidth = f("filterUvWidth", d.filterUvWidth),
                filterIrAmount = f("filterIrAmount", d.filterIrAmount), filterIrNm = f("filterIrNm", d.filterIrNm),
                filterIrWidth = f("filterIrWidth", d.filterIrWidth),
                meteringMethod = o.optString("meteringMethod", d.meteringMethod),
                hanatosWindow = o.optBoolean("hanatosWindow", d.hanatosWindow),
                hanatosSurface = o.optBoolean("hanatosSurface", d.hanatosSurface),
                printExposureCompensation = o.optBoolean("printExposureCompensation", d.printExposureCompensation),
                normalizePrintExposure = o.optBoolean("normalizePrintExposure", d.normalizePrintExposure),
                lensBlurUm = f("lensBlurUm", d.lensBlurUm), filmFormatMm = f("filmFormatMm", d.filmFormatMm),
                lens85b = o.optBoolean("lens85b", d.lens85b),
                fastDiffusion = o.optBoolean("fastDiffusion", d.fastDiffusion),
                diffusion = o.optBoolean("diffusion", d.diffusion), diffusionFamily = o.optString("diffusionFamily", d.diffusionFamily),
                diffusionStrength = f("diffusionStrength", d.diffusionStrength), diffusionScale = f("diffusionScale", d.diffusionScale),
                diffusionCore = f("diffusionCore", d.diffusionCore), diffusionCoreSize = f("diffusionCoreSize", d.diffusionCoreSize),
                diffusionHalo = f("diffusionHalo", d.diffusionHalo), diffusionHaloSize = f("diffusionHaloSize", d.diffusionHaloSize),
                diffusionBloom = f("diffusionBloom", d.diffusionBloom), diffusionBloomSize = f("diffusionBloomSize", d.diffusionBloomSize),
                diffusionWarmth = f("diffusionWarmth", d.diffusionWarmth),
                printDiffusion = o.optBoolean("printDiffusion", d.printDiffusion), printDiffusionFamily = o.optString("printDiffusionFamily", d.printDiffusionFamily),
                printDiffusionStrength = f("printDiffusionStrength", d.printDiffusionStrength),
                printExposure = f("printExposure", d.printExposure), yFilterShift = f("yFilterShift", d.yFilterShift),
                mFilterShift = f("mFilterShift", d.mFilterShift), preflash = f("preflash", d.preflash), printContrast = f("printContrast", d.printContrast),
                enlargerLensBlur = f("enlargerLensBlur", d.enlargerLensBlur), yFilterNeutral = f("yFilterNeutral", d.yFilterNeutral),
                mFilterNeutral = f("mFilterNeutral", d.mFilterNeutral),
                unsharpAmount = f("unsharpAmount", d.unsharpAmount), unsharpRadius = f("unsharpRadius", d.unsharpRadius),
                whiteCorrection = o.optBoolean("whiteCorrection", d.whiteCorrection), blackCorrection = o.optBoolean("blackCorrection", d.blackCorrection),
                scannerWhiteLevel = f("scannerWhiteLevel", d.scannerWhiteLevel), scannerBlackLevel = f("scannerBlackLevel", d.scannerBlackLevel),
                scannerLensBlur = f("scannerLensBlur", d.scannerLensBlur), scanFilm = o.optBoolean("scanFilm", d.scanFilm),
                glare = o.optBoolean("glare", d.glare), glarePercent = f("glarePercent", d.glarePercent),
                glareRoughness = f("glareRoughness", d.glareRoughness), glareBlur = f("glareBlur", d.glareBlur),
                outputColorSpace = o.optString("outputColorSpace", d.outputColorSpace),
                outputGamutCompress = o.optString("outputGamutCompress", d.outputGamutCompress),
                inputGamutCompress = o.optString("inputGamutCompress", d.inputGamutCompress),
                rgbToRaw = o.optString("rgbToRaw", d.rgbToRaw), spectralBlur = f("spectralBlur", d.spectralBlur),
                gpuPreview = o.optBoolean("gpuPreview", d.gpuPreview),
                previewMaxSize = o.optInt("previewMaxSize", d.previewMaxSize),
            )
        } catch (t: Throwable) { Recipe() }
    }
}

/** Named recipes, stored in SharedPreferences. */
object Recipes {
    private const val FILE = "latent_recipes"

    fun current(ctx: Context): Recipe = Develop.sanitised(Recipe.fromJson(prefs(ctx).getString("__current", "{}") ?: "{}"))
    fun setCurrent(ctx: Context, r: Recipe) = prefs(ctx).edit().putString("__current", r.toJson()).apply()

    fun names(ctx: Context): List<String> = prefs(ctx).all.keys.filter { !it.startsWith("__") }.sorted()
    fun save(ctx: Context, name: String, r: Recipe) = prefs(ctx).edit().putString(name, r.toJson()).apply()
    fun load(ctx: Context, name: String): Recipe? = prefs(ctx).getString(name, null)?.let { Recipe.fromJson(it) }
    fun delete(ctx: Context, name: String) = prefs(ctx).edit().remove(name).apply()

    /** Removes a built film entirely: its recipe and the profile the engine reads. */
    fun deleteStock(ctx: Context, name: String) {
        val r = load(ctx, name)
        delete(ctx, name)
        r?.let { EngineAssets.profileFile(it.film)?.delete(); StockAirs.delete(ctx, it.film) }
    }

    /**
     * The films Latent has built, newest first.
     *
     * A built stock is a saved recipe whose film is a generated profile — so it can be offered
     * alongside the engine's own looks instead of being saved somewhere nothing looks at.
     */
    fun stocks(ctx: Context): List<Pair<String, Recipe>> =
        names(ctx).mapNotNull { name ->
            val r = load(ctx, name) ?: return@mapNotNull null
            if (Develop.isOurStock(r.film)) name to r else null
        }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
