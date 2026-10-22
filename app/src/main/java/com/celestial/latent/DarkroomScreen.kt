@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.ExposureMap
import com.celestial.latent.develop.ExposureMaps
import com.celestial.latent.develop.Recipe
import com.celestial.latent.develop.Recipes
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.delay
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween

private const val COARSE_EDGE = 420     // while a control is moving
private const val FINE_EDGE = 640       // the size the engine's own editor uses
private const val DECODE_EDGE = 1200    // the RAW is decoded once at this size for the darkroom
private const val DETAIL_EDGE = 2400    // ...and at this size, the first time you zoom in, for real detail

private val TABS = listOf(
    "film" to "FILM", "halation" to "HALATION", "grain" to "GRAIN", "diffusion" to "DIFFUSION",
    "camera" to "CAMERA", "enlarger" to "ENLARGER", "scanner" to "SCANNER", "glare" to "GLARE",
    "colour" to "COLOUR", "engine" to "ENGINE",
    // 75a: print size, Live, the develop's summary, and reset / clear / save, out of the bottom bar
    "develop" to "DEVELOP",
)

/** The four optical diffusion filters the engine models. */
private val DIFFUSION_FAMILIES = listOf("glimmerglass", "black_pro_mist", "pro_mist", "cinebloom", "fog")

/**
 * The darkroom: a developed preview of one capture plus the controls that shape it.
 * Simple shows eight; Full opens every group the engine exposes.
 */
@Composable
fun DarkroomScreen(source: Uri, isRaw: Boolean, initial: Recipe, onRecipeChanged: (Recipe) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // Opened at the brightness it was shot: the film does not re-level it here either.
    // A photo with its own recipe (a cinema shot) opens with it, and keeps its edits to itself:
    // they must not become the recipe every new shot from the camera develops with.
    val ownRecipe = remember { PhotoRecipes.load(context, source) }
    var recipe by remember { mutableStateOf((ownRecipe ?: initial).copy(autoExposure = false)) }
    // An export choice, not part of the look — see DarkroomPrefs for why it lives apart.
    var printSize by remember { mutableStateOf(com.celestial.latent.develop.DarkroomPrefs.printSize(context)) }
    var full by remember { mutableStateOf(false) }
    // PRINT mode: test strips (and, later, the ring-around and dodge & burn) instead of sliders.
    var printing by remember { mutableStateOf(false) }
    // Why the last full-size develop failed, in plain words — shown until the next one starts.
    // (A failure used to show nothing: the counter just stopped and no file appeared.)
    var fullError by remember { mutableStateOf<String?>(null) }
    // This photo's dodge & burn — its own, never part of the shared recipe.
    var exposureMap by remember { mutableStateOf(ExposureMaps.load(context, source)) }
    // ...and where its diffusion goes, if painted (null = the plain setting: everywhere when on).
    var softenMap by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.SOFTEN)) }
    // ...and the fog painted into its air, with the fog's colour.
    var fogMap by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.FOG)) }
    var fogLook by remember { mutableStateOf(com.celestial.latent.develop.FogLooks.load(context, source)) }
    // ...and god rays: where the light comes from, how bright and how far, and where they may fall.
    var raysMap by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.RAYS)) }
    var raysLook by remember { mutableStateOf(com.celestial.latent.develop.RaysLooks.load(context, source)) }
    // lights 2, 3 and 4: the same kind of light as light 1, kept beside it
    var extraLights by remember { mutableStateOf(com.celestial.latent.develop.ExtraLights.load(context, source)) }
    // the photo's foreground and background (from its depth, with the user's fixes), kept with it
    var depthSplit by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.DEPTH_SPLIT)) }
    var depthFix by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.DEPTH_FIX)) }
    // ...and where its colour is painted richer or paler (62a, DODGE & BURN's saturation)
    var saturationMap by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.SATURATION)) }
    // ...and whether it is printed on lith paper, and how (63a, PRINT's LITH step)
    var lithLook by remember { mutableStateOf(com.celestial.latent.develop.LithLooks.load(context, source)) }
    // ...and how far its brightest lights glow on HDR screens (78a): Settings' starting value until it is moved
    var hdrGlow by remember { mutableStateOf(com.celestial.latent.develop.HdrGlows.load(context, source)) }
    var hdrGlowTouched by remember { mutableStateOf(false) }
    // 78a: the window switched to HDR, so the preview shows the glow (only while asked, only in the darkroom)
    var hdrView by remember { mutableStateOf(false) }
    // ...and where its ground is wet, and how (64a, PRINT's WET step)
    var wetMap by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.WET)) }
    var wetLook by remember { mutableStateOf(com.celestial.latent.develop.WetLooks.load(context, source)) }
    // ...and where the rain on the window is wiped away or added (65a, WET → WINDOW; how is in wetLook.window)
    var rainMap by remember { mutableStateOf(ExposureMaps.load(context, source, ExposureMaps.RAIN)) }
    /** Everything painted on this photo, as one value, so no render can mix them up. */
    fun currentMasks() = Masks(exposureMap, softenMap, fogMap, raysMap, raysOn = true, saturation = saturationMap, lith = lithLook, wet = wetMap, wetLook = wetLook, rain = rainMap)
    // A double exposure: this frame was made onto an earlier one, and is shown and printed as both.
    val pairFirst = remember { if (isRaw) com.celestial.latent.develop.DoubleExposure.firstFor(context, source) else null }
    // How the photo is framed — turned, flipped, straightened. The photo's own, like its masks.
    var framing by remember { mutableStateOf(com.celestial.latent.develop.Framings.load(context, source)) }
    var frameOpen by remember { mutableStateOf(false) }
    var liveStraighten by remember { mutableStateOf<Float?>(null) }
    // Zoom on the photo, the photo area's size, the sharp tile developed for the zoomed-in part,
    // and whether two fingers are down (so press-and-hold "compare" stays out of a pinch).
    val zoom = remember { ZoomState() }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var detail by remember { mutableStateOf<Pair<com.celestial.latent.develop.Region, Bitmap>?>(null) }
    var pinching by remember { mutableStateOf(false) }
    var sharpening by remember { mutableStateOf(false) }
    val zoomScope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    // 72a: the unframed "before", and which framing [original] was framed for
    var originalPlain by remember { mutableStateOf<Bitmap?>(null) }
    var originalFraming by remember { mutableStateOf("") }
    var comparing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var rendering by remember { mutableStateOf(false) }
    var pendingAt by remember { mutableStateOf(0L) }
    var src by remember { mutableStateOf<Develop.Source?>(null) }
    var coarse by remember { mutableStateOf(false) }
    var fullRunning by remember { mutableStateOf(false) }
    // the living photo panel (66a)
    var liveOpen by remember { mutableStateOf(false) }
    var previewIsPartial by remember { mutableStateOf(false) }
    // 75b: the last whole-frame preview, under the quick pass's centre so the picture never jumps in size
    var lastFull by remember { mutableStateOf<Bitmap?>(null) }
    var gpuTest by remember { mutableStateOf("") }
    var lookId by remember { mutableStateOf("") }
    var levelling by remember { mutableStateOf(false) }
    var fullStarted by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0) }
    var lastRenderMs by remember { mutableStateOf(0) }
    var fullJob by remember { mutableStateOf<com.celestial.latent.develop.DevelopQueue.Running?>(null) }
    var tab by remember { mutableStateOf("film") }
    // 74a: App help, drawn over the darkroom at the page for what is on screen
    var helpTopic by remember { mutableStateOf<String?>(null) }
    // 75b: the photo's share of the room above the controls, set by dragging the handle freely
    // (it used to snap between three heights) and remembered for next time
    // the photo ↔ controls split, shared with PRINT's steps (75c)
    val panelSplit = remember { PanelSplit(com.celestial.latent.develop.DarkroomPrefs.photoShare(context)) }
    val photoShare = panelSplit.share
    var roomHeight by remember { mutableStateOf(1) }
    var saveName by remember { mutableStateOf("") }
    // 71a: the recipe before "Reset to film", and the painted effects before "Clear effects", for one Undo each
    var beforeReset by remember { mutableStateOf<Recipe?>(null) }
    var resetTo by remember { mutableStateOf<Recipe?>(null) }
    var clearAsk by remember { mutableStateOf(false) }
    var beforeClear by remember { mutableStateOf<PaintedEffects?>(null) }
    // 72a: naming a recipe as it is saved, and renaming or deleting one (long-press on its chip)
    var naming by remember { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf<String?>(null) }
    var manageName by remember { mutableStateOf("") }
    var deleteAsk by remember { mutableStateOf(false) }
    var recipesRevision by remember { mutableStateOf(0) }

    fun render(fast: Boolean) {
        if (fullRunning) return                      // never compete with a full-size develop
        if (rendering) { pendingAt = System.currentTimeMillis(); return }
        rendering = true
        // While a control moves: smaller, centred, and without the costly spatial stages —
        // except the one being edited, which has to stay visible.
        val base = recipe.copy(previewMaxSize = if (fast) COARSE_EDGE else FINE_EDGE)
        val r = if (fast) Develop.withoutSpatial(base, keep = tab) else base
        val cropFraction = if (fast) 0.7f else 1f
        Thread {
            val q = com.celestial.latent.develop.DevelopQueue
            val holdsLane = if (q.engineLane.tryAcquire()) true else {
                status = "waiting for the background develop…"
                q.engineLane.acquire(); true          // wait for our turn; never run two engines at once
            }
            var cropped: Develop.Source? = null
            var glowUsed = 0f
            try {
                // The cache key covers the stages that alter the decoded pixels, so a change to
                // colour noise or the fast diffusion re-decodes instead of being ignored.
                val iso = Develop.isoOf(context, source)
                // The working buffer belongs to the cache and is reused; never closed here.
                src = Develop.openCached(context, source, isRaw, DECODE_EDGE, r, iso, softenMask = softenMap, pairFirst = pairFirst, framing = framing,
                    fogMask = fogMap, fogLook = fogLook, raysMask = raysMap, raysLook = raysLook, extraLights = extraLights, depthSplit = depthSplit,
                    wetMask = wetMap, wetLook = wetLook, rainMask = rainMap) { m -> status = m }
                // Middle of the frame first on the quick pass: it appears sooner and reads the same.
                val target = if (cropFraction < 1f) Develop.centreCrop(src!!, cropFraction).also { cropped = it } else src!!
                val t0 = System.nanoTime()
                val map = exposureMap?.let { if (cropFraction < 1f) it.centreCrop(cropFraction) else it }
                val sat = saturationMap?.let { if (cropFraction < 1f) it.centreCrop(cropFraction) else it }
                val lithFrame = if (cropFraction < 1f) (1f - cropFraction) / 2f else 0f
                val (bytes, _) = Develop.render(context, target, r, preview = true, exposureMap = map, softenMask = softenMap, saturationMap = sat,
                    lith = lithLook, lithFrame = if (lithFrame > 0f) com.celestial.latent.develop.Region(lithFrame, lithFrame, 1f - lithFrame, 1f - lithFrame) else null,
                    hdrGlow = if (hdrView) hdrGlow.also { glowUsed = it } else 0f) { m -> status = m }
                lastRenderMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
                // 78a: the glow slider may have moved while this developed: shown at its strength now
                val got = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).let { b ->
                    val g = hdrGlow
                    if (hdrView && g != glowUsed && com.celestial.latent.develop.HdrGlow.has(b)) com.celestial.latent.develop.HdrGlow.retuned(b, g) else b
                }
                // The quick pass is only the centre: shown at its real size and place over the last
                // whole preview, not stretched to fill the box (which read as a zoom in and back out).
                preview = if (cropFraction < 1f) inPlace(got, lastFull ?: preview?.takeIf { !previewIsPartial }, cropFraction)
                    else got.also { lastFull = it }
                previewIsPartial = cropFraction < 1f
            } catch (t: Throwable) { status = "failed: ${t.message}" }
            finally {
                if (cropped !== null && cropped !== src) cropped?.close()
                if (holdsLane) q.engineLane.release()
            }
            rendering = false
            if (pendingAt > 0) { pendingAt = 0; render(fast) }
        }.start()
    }

    /**
     * One preview at a given recipe, for the print panel's strips. Blocks, so it is only called off
     * the main thread; queues for the engine lane like every other render, so two engines never run
     * at once. Never called while the lane is already held.
     */
    fun renderStill(r: Recipe, edge: Int, masks: Masks = Masks()): Bitmap? {
        val q = com.celestial.latent.develop.DevelopQueue
        q.engineLane.acquire()
        return try {
            val iso = Develop.isoOf(context, source)
            val s0 = Develop.openCached(context, source, isRaw, DECODE_EDGE, r, iso, softenMask = masks.soften, pairFirst = pairFirst, framing = framing,
                fogMask = masks.fog, fogLook = fogLook,
                raysMask = masks.rays, raysLook = if (masks.raysOn) raysLook else com.celestial.latent.develop.RaysLook(),
                extraLights = if (masks.raysOn) extraLights else emptyList(), depthSplit = depthSplit,
                wetMask = masks.wet, wetLook = masks.wetLook, rainMask = masks.rain) { }
            val (bytes, _) = Develop.render(context, s0, r.copy(previewMaxSize = edge), preview = true, exposureMap = masks.dodge, softenMask = masks.soften,
                saturationMap = masks.saturation, lith = masks.lith)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            android.util.Log.w("Latent", "print strip failed: ${t.message}"); null
        } finally { q.engineLane.release() }
    }

    /**
     * The zoomed-in part of the print, developed sharp from the larger decode at the screen's own
     * resolution. Queues for the engine lane like every render; only called off the main thread.
     */
    fun renderDetail(r: Recipe, region: com.celestial.latent.develop.Region, edge: Int, masks: Masks = currentMasks()): Bitmap? {
        val q = com.celestial.latent.develop.DevelopQueue
        q.engineLane.acquire()
        return try {
            val iso = Develop.isoOf(context, source)
            val whole = Develop.openCached(context, source, isRaw, DETAIL_EDGE, r, iso, softenMask = masks.soften, pairFirst = pairFirst, framing = framing,
                fogMask = masks.fog, fogLook = fogLook,
                raysMask = masks.rays, raysLook = if (masks.raysOn) raysLook else com.celestial.latent.develop.RaysLook(),
                extraLights = if (masks.raysOn) extraLights else emptyList(), depthSplit = depthSplit,
                wetMask = masks.wet, wetLook = masks.wetLook, rainMask = masks.rain) { }
            Develop.cropRegion(whole, region).use { part ->
                val (bytes, _) = Develop.render(context, part, r.copy(previewMaxSize = edge), preview = true,
                    exposureMap = masks.dodge?.crop(region), softenMask = masks.soften, saturationMap = masks.saturation?.crop(region),
                    lith = masks.lith, lithFrame = region, hdrGlow = if (hdrView) hdrGlow else 0f)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (t: Throwable) {
            android.util.Log.w("Latent", "zoom detail failed: ${t.message}"); null
        } finally { q.engineLane.release() }
    }

    // First render, then re-render shortly after the last control change.
    LaunchedEffect(fullRunning) {
        while (fullRunning) { elapsed = ((System.currentTimeMillis() - fullStarted) / 1000).toInt(); delay(500) }
        elapsed = 0
    }

    // If this capture was already developed, show that straight away instead of a blank wait.
    LaunchedEffect(source) {
        if (preview == null) {
            val existing = runCatching { Develop.developedFor(context, source) }.getOrNull()
            if (existing != null) {
                val b = runCatching { context.contentResolver.loadThumbnail(existing, android.util.Size(1200, 1200), null) }.getOrNull()
                if (b != null && preview == null) { preview = b; status = "already developed · change anything to re-render" }
            }
        }
    }

    // A quick coarse pass while a control is moving, then a fine one when it settles.
    LaunchedEffect(recipe) {
        coarse = true
        if (!printing) render(fast = true)
        delay(450)
        coarse = false
        if (ownRecipe != null) PhotoRecipes.save(context, source, recipe)
        else { onRecipeChanged(recipe); Recipes.setCurrent(context, recipe) }
        if (!printing) render(fast = false)
        // The viewfinder's look is baked from the saved recipe: drop the cached one so the
        // camera picks up these edits next time it is shown.
        com.celestial.latent.develop.LookBaker.invalidate()
    }
    // Save the mask beside its photo (a moment after the last change), and show it in the preview.
    LaunchedEffect(exposureMap) {
        delay(400)
        val m = exposureMap
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ExposureMaps.save(context, source, m) }
        if (!printing && src != null) render(fast = false)
    }
    // The same for the painted saturation (62a).
    LaunchedEffect(saturationMap) {
        delay(400)
        val m = saturationMap
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ExposureMaps.save(context, source, m, ExposureMaps.SATURATION) }
        if (!printing && src != null) render(fast = false)
    }
    // The wet ground and how it shines (64a), kept with the photo; a change re-develops the preview.
    LaunchedEffect(wetMap, wetLook, rainMap) {
        delay(400)
        val m = wetMap; val l = wetLook; val rm = rainMap
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ExposureMaps.save(context, source, m, ExposureMaps.WET)
            ExposureMaps.save(context, source, rm, ExposureMaps.RAIN)
            com.celestial.latent.develop.WetLooks.save(context, source, l)
        }
        if (!printing && src != null) render(fast = false)
    }
    // The lith settings (63a), kept with the photo; a change re-develops the preview.
    LaunchedEffect(lithLook) {
        delay(400)
        val l = lithLook
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.celestial.latent.develop.LithLooks.save(context, source, l) }
        if (!printing && src != null) render(fast = false)
    }
    // The glow (78a), kept with the photo once moved, so a photo never touched keeps following Settings. The slider
    // retunes the glow on screen at once (its map's shape does not depend on the strength); a develop is only
    // needed when the picture on screen has no glow to retune.
    LaunchedEffect(hdrGlow) {
        delay(400)
        val g = hdrGlow
        if (hdrGlowTouched) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.celestial.latent.develop.HdrGlows.save(context, source, g) }
        if (hdrView && !printing && src != null && g >= 0.125f && !com.celestial.latent.develop.HdrGlow.has(preview)) render(fast = false)
    }
    // HDR view on or off: the window's mode, and a preview with (or without) its glow
    LaunchedEffect(hdrView) {
        com.celestial.latent.develop.HdrGlow.setWindowHdr(context, hdrView)
        if (!printing && src != null) render(fast = false)
    }
    // leaving the darkroom always gives the app its ordinary window back
    DisposableEffect(Unit) { onDispose { com.celestial.latent.develop.HdrGlow.setWindowHdr(context, false) } }
    // The same for the soften mask. Kept even when all sharp: that is a choice, not "untouched".
    LaunchedEffect(softenMap) {
        delay(400)
        val m = softenMap
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ExposureMaps.save(context, source, m, ExposureMaps.SOFTEN, keepBlank = true)
        }
        if (!printing && src != null) render(fast = false)
    }
    /**
     * A new framing: the masks are carried across so every mark stays over the same part of the
     * photo, the framing is kept with the photo, and the print re-develops framed.
     */
    fun commitFrame(next: com.celestial.latent.develop.Framing) {
        if (next == framing) return
        val s0 = src
        if (s0 != null) {
            // the working copy is the framed picture; its shape gives the unframed photo's
            val shown = s0.width.toFloat() / s0.height
            val srcAspect = if (framing.quarter % 2 == 1) 1f / shown else shown
            exposureMap = exposureMap?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
            softenMap = softenMap?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
            saturationMap = saturationMap?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
            wetMap = wetMap?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
            rainMap = rainMap?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
            depthSplit = depthSplit?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
            depthFix = depthFix?.let { com.celestial.latent.develop.Framings.carry(it, framing, next, srcAspect) }
        }
        framing = next
        com.celestial.latent.develop.Framings.save(context, source, next)
    }
    LaunchedEffect(framing) { zoom.reset(); detail = null; if (src != null) render(fast = false) }
    LaunchedEffect(frameOpen) { if (frameOpen) { zoom.reset(); detail = null } }
    // When zoomed in and still for a moment, develop the visible part sharp. A new print (any
    // change to recipe, masks or framing) makes the old tile stale.
    LaunchedEffect(preview) { detail = null }
    LaunchedEffect(zoom.scale, zoom.focusU, zoom.focusV, preview, frameOpen) {
        val b = preview ?: return@LaunchedEffect
        if (!zoom.zoomed || frameOpen || viewSize.width == 0) return@LaunchedEffect
        delay(320)
        val fit = fitRect(viewSize.width.toFloat(), viewSize.height.toFloat(), b.width.toFloat() / b.height)
        val region = zoom.visible(fit, viewSize.width.toFloat(), viewSize.height.toFloat())
        if (detail?.first == region) return@LaunchedEffect
        val edge = maxOf(viewSize.width, viewSize.height).coerceAtMost(1600)
        val r = recipe; val mk = currentMasks()
        sharpening = true
        try {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { renderDetail(r, region, edge, mk) }
            if (bmp != null) detail = region to bmp
        } finally { sharpening = false }
    }
    // The fog and its colour, kept with the photo; a change re-develops the preview.
    LaunchedEffect(fogMap, fogLook) {
        delay(400)
        val m = fogMap; val l = fogLook
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ExposureMaps.save(context, source, m, ExposureMaps.FOG)
            com.celestial.latent.develop.FogLooks.save(context, source, l)
        }
        if (!printing && src != null) render(fast = false)
    }
    // The foreground split and its fixes, kept with the photo; a change re-develops the preview.
    LaunchedEffect(depthSplit, depthFix) {
        delay(400)
        val sp = depthSplit; val fx = depthFix
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ExposureMaps.save(context, source, sp, ExposureMaps.DEPTH_SPLIT, keepBlank = true)
            ExposureMaps.save(context, source, fx, ExposureMaps.DEPTH_FIX)
        }
        if (!printing && src != null) render(fast = false)
    }
    // The rays and where they may fall, kept with the photo. A blank coverage means "nowhere": kept.
    LaunchedEffect(raysMap, raysLook) {
        delay(400)
        val m = raysMap; val l = raysLook
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ExposureMaps.save(context, source, m, ExposureMaps.RAYS, keepBlank = true)
            com.celestial.latent.develop.RaysLooks.save(context, source, l)
        }
        if (!printing && src != null) render(fast = false)
    }
    // The extra lights, kept with the photo after a moment's pause, like light 1.
    LaunchedEffect(extraLights) {
        delay(400)
        val l = extraLights
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.celestial.latent.develop.ExtraLights.save(context, source, l) }
        if (!printing && src != null) render(fast = false)
    }
    // Back from PRINT: the strips may have changed the exposure while the preview slept.
    LaunchedEffect(printing) { if (!printing && src != null) render(fast = false) }
    // The decoded copy is kept by Develop.Cache so coming back is instant; nothing to free here.

    fun set(block: Recipe.() -> Recipe) { recipe = recipe.block() }

    /** What a full-size develop will include (it used to sit in the bottom bar, before 75a). */
    fun developSummary(): String {
        val heavy = recipe.diffusion || recipe.printDiffusion
        return (if (isRaw) "FROM RAW" else "FILM OVER JPEG") + (if (heavy) " · WITH DIFFUSION, SLOW" else "") +
            (if (printSize > 1f) " · ${com.celestial.latent.develop.DarkroomPrefs.label(printSize)} PRINT" else "") +
            (if (exposureMap?.isBlank == false) " · DODGED & BURNED" else "") +
            (if (com.celestial.latent.develop.Saturation.wanted(saturationMap)) " · COLOUR PAINTED" else "") +
            (if (softenMap != null && recipe.diffusion) " · SOFTENED IN PLACES" else "") +
            (if (pairFirst != null) " · DOUBLE EXPOSURE" else "") +
            (if (!framing.isIdentity) " · FRAMED" else "") +
            (if (fogMap?.isBlank == false) " · FOGGED" else "") +
            (if (raysLook.placed) " · LIGHT" else "") +
            (if (com.celestial.latent.develop.Wet.wanted(wetMap)) " · WET" else "") +
            (if (com.celestial.latent.develop.Rain.wanted(wetLook.window)) " · RAIN ON GLASS" else "") +
            (if (lithLook.on) " · LITH" else "") +
            (if (com.celestial.latent.develop.HdrGlow.wanted(hdrGlow, lithLook)) " · ULTRA HDR ${com.celestial.latent.develop.HdrGlow.label(hdrGlow)}" else "")
    }

    /** The full-size print (75b: started from the DEVELOP tab, in SIMPLE/FULL and in PRINT alike). */
    fun startFullDevelop() {
        if (fullRunning) return
        fullRunning = true; fullStarted = System.currentTimeMillis(); status = "full size: queued"; fullError = null
        fullJob = com.celestial.latent.develop.DevelopQueue.submitFull(
            context, source, isRaw, recipe, upscale = printSize, exposureMap = exposureMap, softenMask = softenMap, pairFirst = pairFirst, framing = framing,
            fogMask = fogMap, fogLook = fogLook, raysMask = raysMap, raysLook = raysLook, extraLights = extraLights, depthSplit = depthSplit,
            saturationMap = saturationMap, lith = lithLook, wetMask = wetMap, wetLook = wetLook, rainMask = rainMap,
            hdrGlow = hdrGlow,
            onStatus = { m ->
                status = "full size: $m"
                if (m.startsWith("failed")) fullError =
                    if (m.contains("OutOfMemory", ignoreCase = true) || m.contains("allocate", ignoreCase = true))
                        "Not developed: the phone ran out of memory. Try a smaller print size."
                    else "Not developed: " + m.removePrefix("failed: ").take(120)
            },
            onDone = { out ->
                fullRunning = false; fullJob = null
                if (out != null) {
                    fullError = null
                    status = "saved to DCIM/Latent"
                    // 78a: in HDR view, the saved file itself (a thumbnail would drop its glow)
                    val b = (if (hdrView) com.celestial.latent.develop.HdrGlow.decodeSmall(context, out, 1600) else null)
                        ?: runCatching { context.contentResolver.loadThumbnail(out, android.util.Size(1600, 1600), null) }.getOrNull()
                    if (b != null) { preview = b; previewIsPartial = false; lastFull = b }
                }
            },
        )
    }

    /** The DEVELOP page (75a/75b): the Develop button first, then print size, the summary, Live, reset / clear / save. */
    val developPage: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (fullRunning) "Developing… ${elapsed}s" else "Develop", color = LatentColors.AmberInk, fontSize = 13.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                    .combinedClickable(onClick = { if (!fullRunning) { Haptics.click(context); startFullDevelop() } }).padding(horizontal = 22.dp, vertical = 10.dp))
            Text("Full size, saved to DCIM/Latent.", color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        Head("PRINT SIZE", null) {}
        // Print size: the film's grain is generated at the larger size, so it stays fine
        // rather than being blown up. Costs time in proportion to the pixels.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.celestial.latent.develop.DarkroomPrefs.PRINT_SIZES.forEach { size ->
                val on = printSize == size
                Text(com.celestial.latent.develop.DarkroomPrefs.label(size),
                    color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                        .combinedClickable(onClick = {
                            if (!fullRunning) {
                                Haptics.tick(context); printSize = size
                                com.celestial.latent.develop.DarkroomPrefs.setPrintSize(context, size)
                            }
                        }).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Note(if (printSize > 1f) "Finer grain at the larger size, no new detail; about ${"%.1f".format(printSize * printSize)}× as long to develop."
            else "The photo's own size. Larger sizes keep the grain fine instead of enlarging it.")
        // 78a: Ultra HDR glow, this photo's own; the saved JPEG is the same print with a hidden glow map behind it
        Head("ULTRA HDR GLOW", null) {}
        if (!com.celestial.latent.develop.HdrGlow.supported) Note("Needs Android 14 or newer. Prints are saved as plain JPEGs.")
        else {
            val glowOn = !lithLook.on
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Glow in the brightest lights", color = if (glowOn) LatentColors.Text else LatentColors.Line, fontSize = 11.sp)
                Text(if (!glowOn) "Off · lith" else com.celestial.latent.develop.HdrGlow.label(hdrGlow) + (if (hdrGlow >= 0.125f) " stops" else ""),
                    color = if (glowOn) LatentColors.TextBright else LatentColors.Line, fontSize = 11.sp)
            }
            Slider(
                value = hdrGlow.coerceIn(0f, com.celestial.latent.develop.HdrGlow.MAX_STOPS),
                onValueChange = { v ->
                    val snapped = Math.round(v * 4) / 4f
                    if (snapped != hdrGlow) {
                        Haptics.tick(context); hdrGlow = snapped; hdrGlowTouched = true
                        // in HDR view the glow on screen follows the finger, without a new develop
                        if (hdrView) {
                            val p = preview
                            val np = p?.let { com.celestial.latent.develop.HdrGlow.retuned(it, snapped) }
                            lastFull = if (lastFull === p) np else lastFull?.let { com.celestial.latent.develop.HdrGlow.retuned(it, snapped) }
                            preview = np
                        }
                    }
                },
                valueRange = 0f..com.celestial.latent.develop.HdrGlow.MAX_STOPS, steps = 7, enabled = glowOn,
                colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line),
            )
            Note(when {
                !glowOn -> "Lith prints never glow: matte paper has no light of its own."
                hdrGlow < 0.125f -> "Off: a plain JPEG, exactly the print."
                else -> "Lamps and bright sky shine up to ${com.celestial.latent.develop.HdrGlow.times(hdrGlow)}× brighter than white on HDR screens; the print itself is unchanged. Tap HDR on the photo to see it. Settings sets where new photos start."
            })
        }
        Head("THIS DEVELOP", null) {}
        // An honest estimate before you commit, rather than an explanation mid-wait.
        Text(developSummary(), color = LatentColors.Text, fontSize = 10.sp, letterSpacing = 1.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        Head("LIVING PHOTO", null) {}
        // the living photo (66a): this edit as a short loop, only what the darkroom added moving
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live", color = LatentColors.TextBright, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.click(context); liveOpen = true }).padding(horizontal = 16.dp, vertical = 8.dp))
            Text("A short clip of this edit: rain running, fog drifting, lamps flickering.", color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        Head("START OVER OR KEEP IT", null) {}
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 71a: back to this film's own look (its grain, glow and the rest), not the engine's bare
            // defaults, which gave every film Portra 400's grain. One Undo brings the edit back.
            Text("Reset to film", color = LatentColors.Text, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = {
                        Haptics.tick(context)
                        val fresh = filmLook(context, recipe, lookId, ownRecipe != null)
                        if (fresh != recipe) { beforeReset = recipe; resetTo = fresh; recipe = fresh }
                    }).padding(horizontal = 12.dp, vertical = 7.dp))
            Text("Clear effects", color = LatentColors.Text, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); clearAsk = true }).padding(horizontal = 12.dp, vertical = 7.dp))
            Text("Save recipe", color = LatentColors.Text, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = {
                        // 72a: asks for a name first, offering the film's
                        Haptics.tick(context); saveName = ""
                        naming = (Develop.FILMS.firstOrNull { it.first == recipe.film }?.second ?: "My recipe") + " – "
                    }).padding(horizontal = 12.dp, vertical = 7.dp))
        }
        val draft = naming
        if (draft != null) {
            val clean = Recipes.cleanName(draft)
            val taken = clean.isNotEmpty() && Recipes.exists(context, clean)
            NameField(draft, "name this recipe") { naming = it }
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (taken) "Replace" else "Save", color = if (clean.isNotEmpty()) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (clean.isNotEmpty()) LatentColors.Amber else LatentColors.Surface)
                        .combinedClickable(onClick = {
                            if (clean.isEmpty()) return@combinedClickable
                            Haptics.tick(context)
                            Recipes.save(context, clean, recipe); saveName = clean; naming = null
                            lookId = "stock:" + clean; recipesRevision++
                        }).padding(horizontal = 12.dp, vertical = 7.dp))
                Text("Cancel", color = LatentColors.Text, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(onClick = { naming = null }).padding(horizontal = 12.dp, vertical = 7.dp))
                if (taken) Text("a recipe has this name", color = LatentColors.TextDim, fontSize = 10.sp)
            }
        }
        if (saveName.isNotEmpty()) Text("saved “$saveName” · find it under YOUR RECIPES in the looks above and in the camera's film strip",
            color = LatentColors.Amber, fontSize = 10.sp)
        // Undo for the reset, while nothing has been changed since
        val undoReset = beforeReset
        if (undoReset != null && resetTo == recipe) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Back to the film's own look.", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Undo", color = LatentColors.AmberInk, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                        .combinedClickable(onClick = { Haptics.tick(context); recipe = undoReset; beforeReset = null; resetTo = null })
                        .padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        // "Clear effects" asks first, in place (no dialog: those misplace on the Xiaomi)
        if (clearAsk) {
            Text("Clear this photo's fog, lights, god rays, wet ground, rain, lith print, dodge & burn and painted colour? The film settings and the framing stay.",
                color = LatentColors.Text, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Clear", color = LatentColors.AmberInk, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                        .combinedClickable(onClick = {
                            Haptics.tick(context)
                            beforeClear = PaintedEffects(exposureMap, softenMap, fogMap, fogLook, raysMap, raysLook, extraLights,
                                saturationMap, lithLook, wetMap, wetLook, rainMap)
                            val none = PaintedEffects.NONE
                            exposureMap = none.exposure; softenMap = none.soften; fogMap = none.fog; fogLook = none.fogLook
                            raysMap = none.rays; raysLook = none.raysLook; extraLights = none.extraLights
                            saturationMap = none.saturation; lithLook = none.lith; wetMap = none.wet; wetLook = none.wetLook; rainMap = none.rain
                            clearAsk = false
                        }).padding(horizontal = 12.dp, vertical = 7.dp))
                Text("Keep them", color = LatentColors.Text, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(onClick = { clearAsk = false }).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        // ...and its Undo, while the photo is still open
        val undoClear = beforeClear
        if (undoClear != null && !clearAsk) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Effects cleared.", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Undo", color = LatentColors.AmberInk, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                        .combinedClickable(onClick = {
                            Haptics.tick(context)
                            exposureMap = undoClear.exposure; softenMap = undoClear.soften; fogMap = undoClear.fog; fogLook = undoClear.fogLook
                            raysMap = undoClear.rays; raysLook = undoClear.raysLook; extraLights = undoClear.extraLights
                            saturationMap = undoClear.saturation; lithLook = undoClear.lith; wetMap = undoClear.wet; wetLook = undoClear.wetLook; rainMap = undoClear.rain
                            beforeClear = null
                        }).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        }
    }

    // a Box only so the living photo panel (66a) can lie over the darkroom, in the app's own window
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = LatentColors.Text, fontSize = 22.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 6.dp))
            Text("DARKROOM", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 4.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SIMPLE", "FULL", "PRINT").forEach { label ->
                    val on = when (label) { "PRINT" -> printing; "FULL" -> !printing && full; else -> !printing && !full }
                    Text(label, color = if (on) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else Color.Transparent)
                            .combinedClickable(onClick = {
                                if (!on) Haptics.tick(context)
                                when (label) { "PRINT" -> printing = true; "FULL" -> { printing = false; full = true }; else -> { printing = false; full = false } }
                            }).padding(horizontal = 9.dp, vertical = 3.dp))
                }
                // 74a: what the controls on screen do
                Text("?", color = LatentColors.Amber, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(0.5.dp, LatentColors.Line, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = { Haptics.tick(context); helpTopic = if (printing) "print" else tab }).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }

        // PRINT swaps the photo and controls for the enlarger; the header, print size and develop
        // bar stay. A crossfade, so it reads as one room changing, not a new screen.
        Crossfade(targetState = printing, animationSpec = tween(320), modifier = Modifier.fillMaxWidth().weight(1f), label = "darkroom mode") { inPrint ->
            if (inPrint) androidx.compose.runtime.CompositionLocalProvider(LocalPanelSplit provides panelSplit) {
                PrintPanel(
                    recipe = recipe,
                    developPage = developPage,
                    developPreview = preview,
                    renderAt = { r, edge -> renderStill(r, edge) },
                    onExposure = { e -> set { copy(printExposure = e) } },
                    onFilters = { y, m -> set { copy(yFilterShift = y, mFilterShift = m) } },
                    onGrain = { on, size -> set { copy(grain = on, grainSizeUm2 = size) } },
                    onGrainAmount = { a -> set { copy(grainAmount = a) } },
                    exposureMap = exposureMap,
                    onExposureMap = { exposureMap = it },
                    saturationMap = saturationMap,
                    onSaturationMap = { saturationMap = it },
                    onDirAmount = { a -> set { copy(dirAmount = a, dir = true) } },
                    softenMap = softenMap,
                    onSoftenMap = { softenMap = it },
                    onDiffusionOn = { if (!recipe.diffusion) set { copy(diffusion = true) } },
                    fogMap = fogMap,
                    onFogMap = { fogMap = it },
                    fogLook = fogLook,
                    onFogLook = { fogLook = it },
                    lithLook = lithLook,
                    onLithLook = { lithLook = it },
                    wetMap = wetMap,
                    onWetMap = { wetMap = it },
                    wetLook = wetLook,
                    onWetLook = { wetLook = it },
                    rainMap = rainMap,
                    onRainMap = { rainMap = it },
                    sampleScene = { u, v ->
                        val q = com.celestial.latent.develop.DevelopQueue
                        q.engineLane.acquire()
                        try { Develop.sampleScene(context, source, isRaw, DECODE_EDGE, pairFirst, framing, u, v) }
                        catch (t: Throwable) { null } finally { q.engineLane.release() }
                    },
                    readAir = { near, w, h ->
                        val q = com.celestial.latent.develop.DevelopQueue
                        q.engineLane.acquire()
                        try { Develop.readAir(context, source, isRaw, DECODE_EDGE, pairFirst, framing, near, w, h) }
                        catch (t: Throwable) { android.util.Log.e("Latent", "air: could not read the photo", t); null }
                        finally { q.engineLane.release() }
                    },
                    raysMap = raysMap,
                    onRaysMap = { raysMap = it },
                    raysLook = raysLook,
                    onRaysLook = { raysLook = it },
                    extraLights = extraLights,
                    onExtraLights = { extraLights = it },
                    depthSplit = depthSplit, onDepthSplit = { depthSplit = it },
                    depthFix = depthFix, onDepthFix = { depthFix = it },
                    renderWithMasks = { r, edge, mk -> renderStill(r, edge, mk) },
                    renderRegionWithMasks = { r, edge, mk, reg -> renderDetail(r, reg, edge, mk) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else Column(Modifier.fillMaxSize().onSizeChanged { roomHeight = it.height.coerceAtLeast(1) }) {
                // Photo shrinks as the sheet is dragged up; it never disappears entirely.
                // While framing, the photo gets at least its usual share, so the crop is easy to see.
                val photoWeight = if (frameOpen) maxOf(photoShare, com.celestial.latent.develop.DarkroomPrefs.PHOTO_SHARE_DEFAULT) else photoShare
                Box(Modifier.fillMaxWidth().weight(photoWeight).background(LatentColors.Surface)
                    .onSizeChanged { viewSize = it }
                    // Two fingers zoom and move; one finger slides the picture when zoomed in.
                    .pointerInput(frameOpen, preview != null) {
                        if (frameOpen) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var travelled = 0f
                            do {
                                val e = awaitPointerEvent()
                                val down = e.changes.count { it.pressed }
                                val b = preview
                                if (b != null) {
                                    val vw = size.width.toFloat(); val vh = size.height.toFloat()
                                    val fit = fitRect(vw, vh, b.width.toFloat() / b.height)
                                    if (down >= 2) {
                                        pinching = true; comparing = false
                                        zoom.pinch(e.calculateCentroid(), e.calculatePan(), e.calculateZoom(), fit, vw, vh)
                                        e.changes.forEach { it.consume() }
                                    } else if (down == 1 && zoom.zoomed) {
                                        val d = e.changes.first { it.pressed }.positionChange()
                                        travelled += d.getDistance()
                                        if (travelled > viewConfiguration.touchSlop) {
                                            comparing = false
                                            zoom.panBy(d.x, d.y, fit, vw, vh)
                                            e.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            } while (e.changes.any { it.pressed })
                            pinching = false
                        }
                    }
                    .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { Haptics.tick(context); zoom.reset(); detail = null },
                        onPress = {
                        // a moment's wait, so the start of a pinch or slide never flashes the original
                        val show = zoomScope.launch { delay(140); if (!pinching) comparing = true }
                        // the before, framed as the print is (72a: it used to stay sideways for a turned shot)
                        val f = framing
                        if (originalFraming != f.key()) Thread {
                            val plain = originalPlain ?: plainBefore(context, source, isRaw)
                                ?.let { uprightBefore(context, it, source, isRaw, Develop.openedSize(source, isRaw, DECODE_EDGE, pairFirst)) }
                                ?.also { originalPlain = it }
                            if (plain != null) {
                                original = runCatching { com.celestial.latent.develop.Framings.frameBitmap(plain, f) }.getOrNull()
                                originalFraming = f.key()
                            }
                        }.start()
                        tryAwaitRelease(); show.cancel(); comparing = false
                    })
                }) {
                    val shown = if (comparing && originalFraming == framing.key()) (original ?: preview) else preview
                    // While straightening, the print turns live under the finger, scaled so no blank
                    // corner shows — the real framed print develops when the finger lifts.
                    val delta = (liveStraighten ?: framing.straighten) - framing.straighten
                    val liveScale = shown?.let { b ->
                        1f / com.celestial.latent.develop.Framing(straighten = delta).cropScale(b.width, b.height)
                    } ?: 1f
                    shown?.let { bmp ->
                        Image(bmp.asImageBitmap(), contentDescription = "Developed", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                if (frameOpen) { rotationZ = delta; scaleX = liveScale; scaleY = liveScale }
                                else if (zoom.zoomed) {
                                    // the zoom: scaled about the view's middle, then moved so the
                                    // focused point of the picture sits there
                                    val fit = fitRect(size.width, size.height, bmp.width.toFloat() / bmp.height)
                                    scaleX = zoom.scale; scaleY = zoom.scale
                                    translationX = (0.5f - zoom.focusU) * fit.width * zoom.scale
                                    translationY = (0.5f - zoom.focusV) * fit.height * zoom.scale
                                }
                            })
                    }
                    // film-still bars, shown around the picture as they will be printed
                    if (framing.letterbox && !zoom.zoomed && !frameOpen && !comparing && preview != null) {
                        val pb = preview!!
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val fit = fitRect(size.width, size.height, pb.width.toFloat() / pb.height)
                            if (fit.width >= fit.height) {
                                val bar = (fit.width * 9f / 16f - fit.height) / 2f
                                if (bar > 0f) {
                                    drawRect(Color.Black, androidx.compose.ui.geometry.Offset(fit.left, fit.top - bar), androidx.compose.ui.geometry.Size(fit.width, bar))
                                    drawRect(Color.Black, androidx.compose.ui.geometry.Offset(fit.left, fit.top + fit.height), androidx.compose.ui.geometry.Size(fit.width, bar))
                                }
                            } else {
                                val bar = (fit.height * 9f / 16f - fit.width) / 2f
                                if (bar > 0f) {
                                    drawRect(Color.Black, androidx.compose.ui.geometry.Offset(fit.left - bar, fit.top), androidx.compose.ui.geometry.Size(bar, fit.height))
                                    drawRect(Color.Black, androidx.compose.ui.geometry.Offset(fit.left + fit.width, fit.top), androidx.compose.ui.geometry.Size(bar, fit.height))
                                }
                            }
                        }
                    }
                    // the sharp tile for the zoomed-in part, laid exactly over it
                    val tile = detail
                    if (tile != null && zoom.zoomed && !comparing && !frameOpen && preview != null) {
                        val pb = preview!!
                        // each new tile fades in over the enlarged print beneath it
                        val tileFade = remember(tile) { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(tile) { tileFade.animateTo(1f, androidx.compose.animation.core.tween(260)) }
                        val tileAlpha = tileFade.value
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val fit = fitRect(size.width, size.height, pb.width.toFloat() / pb.height)
                            val a = zoom.toScreen(tile.first.u0, tile.first.v0, fit, size.width, size.height)
                            val c = zoom.toScreen(tile.first.u1, tile.first.v1, fit, size.width, size.height)
                            drawImage(tile.second.asImageBitmap(),
                                dstOffset = androidx.compose.ui.unit.IntOffset(a.x.toInt(), a.y.toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize((c.x - a.x).toInt(), (c.y - a.y).toInt()), alpha = tileAlpha)
                        }
                    }
                    if (zoom.zoomed && !frameOpen) Text("${String.format(java.util.Locale.US, "%.1f", zoom.scale)}×  · " +
                        (if (sharpening) "sharpening…" else "double-tap for the whole print"),
                        color = Color(0xE6FFFFFF), fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(999.dp))
                            .background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 3.dp))
                    // a grid to line the horizon up against, while framing
                    if (frameOpen && preview != null) {
                        val b = preview!!
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val a = b.width.toFloat() / b.height
                            var w = size.width; var h = w / a
                            if (h > size.height) { h = size.height; w = h * a }
                            val l = (size.width - w) / 2f; val t = (size.height - h) / 2f
                            val line = Color(0x66FFFFFF); val fine = Color(0x26FFFFFF)
                            for (i in 1 until 6) {
                                val c = if (i % 2 == 0) line else fine
                                drawLine(c, androidx.compose.ui.geometry.Offset(l + w * i / 6f, t), androidx.compose.ui.geometry.Offset(l + w * i / 6f, t + h), 1f)
                                drawLine(c, androidx.compose.ui.geometry.Offset(l, t + h * i / 6f), androidx.compose.ui.geometry.Offset(l + w, t + h * i / 6f), 1f)
                            }
                        }
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 78a: see the Ultra HDR glow while editing (Android 14+): the window turns HDR only while this is on
                        if (com.celestial.latent.develop.HdrGlow.supported && !frameOpen) Text("HDR", color = if (hdrView) LatentColors.AmberInk else Color(0xE6FFFFFF), fontSize = 10.sp, letterSpacing = 1.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (hdrView) LatentColors.Amber else Color(0x66000000))
                                .combinedClickable(onClick = { Haptics.tick(context); hdrView = !hdrView })
                                .padding(horizontal = 10.dp, vertical = 4.dp))
                        Text(if (frameOpen) "FRAMING" else "FRAME", color = if (frameOpen) LatentColors.AmberInk else Color(0xE6FFFFFF), fontSize = 10.sp, letterSpacing = 1.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (frameOpen) LatentColors.Amber else Color(0x66000000))
                                .combinedClickable(onClick = { Haptics.tick(context); frameOpen = !frameOpen })
                                .padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    // why nothing glows, when HDR view is on and something stops it
                    if (hdrView && !frameOpen) {
                        val why = when {
                            lithLook.on -> "lith prints never glow"
                            hdrGlow < 0.125f -> "glow is Off for this photo · DEVELOP tab"
                            !com.celestial.latent.develop.HdrGlow.screenCan(context) -> "this screen does not report HDR"
                            else -> null
                        }
                        if (why != null) Text(why, color = Color(0xE6FFFFFF), fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 10.dp).clip(RoundedCornerShape(999.dp))
                                .background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 3.dp))
                    }

                    if (!rendering && !fullRunning && preview == null) Text(if (status.isEmpty()) "no preview yet" else status, color = LatentColors.Text, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    Text(if (comparing) "ORIGINAL" else (Develop.FILMS.firstOrNull { it.first == recipe.film }?.second?.uppercase() ?: recipe.film),
                        color = Color(0xE6FFFFFF), fontSize = 10.sp, letterSpacing = 1.5.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                }

                // The sheet: drag the handle to give the controls more room. While framing, the frame
                // panel takes its place, so the photo stays as large as it can be.
                if (frameOpen) FramePanel(
                    framing = framing, live = liveStraighten,
                    onLive = { liveStraighten = it },
                    onCommit = { commitFrame(it) },
                    onDone = { frameOpen = false; liveStraighten = null },
                    modifier = Modifier.fillMaxWidth().weight(1f - photoWeight),
                ) else Column(
                    Modifier.fillMaxWidth().weight(1f - photoWeight).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF1D1D1B))
                ) {
                    // the handle (75b): the panel follows the finger; a double tap puts the usual height back
                    PanelHandle(panelSplit, roomHeight)
                    // One line of state, off the photograph: what is happening, how long, and a way out.
                    if (fullRunning || rendering || previewIsPartial) {
                        Column(Modifier.fillMaxWidth()) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(1.5.dp),
                                color = LatentColors.Amber, trackColor = LatentColors.Surface,
                            )
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        fullRunning -> "FULL SIZE"
                                        rendering -> "DEVELOPING"
                                        else -> "QUICK PASS · CENTRE"
                                    },
                                    color = LatentColors.Amber, fontSize = 9.sp, letterSpacing = 2.sp,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (fullRunning) Text("${elapsed}s", color = LatentColors.Text, fontSize = 10.sp)
                                    else if (lastRenderMs > 0) Text("${lastRenderMs} ms", color = LatentColors.TextDim, fontSize = 10.sp)
                                    if (fullRunning) Text("✕", color = LatentColors.Text, fontSize = 14.sp,
                                        modifier = Modifier.combinedClickable(onClick = {
                                            Haptics.tick(context); fullJob?.cancel(); fullJob = null; fullRunning = false; status = "cancelled"
                                        }).padding(start = 12.dp, end = 4.dp))
                                }
                            }
                        }
                    }
                    // The engine's authored looks: each one is a full starting recipe, including the
                    // per-stock grain and halation that make stocks differ.
                    val looks = remember { com.celestial.latent.develop.Presets.all(context) }
                    // Films built here belong in the darkroom too, or they can be shot with but not edited.
                    val ourStocks = remember(com.celestial.latent.develop.Recipes.names(context).size) {
                        com.celestial.latent.develop.Recipes.stocks(context)
                    }
                    // 72a: recipes saved here, which nothing used to show
                    val savedRecipes = remember(recipesRevision, com.celestial.latent.develop.Recipes.names(context).size) {
                        com.celestial.latent.develop.Recipes.saved(context)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedRecipes.forEach { (name, saved) ->
                            val on = lookId == "stock:" + name
                            Column(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                                    .combinedClickable(
                                        onClick = { Haptics.tick(context); lookId = "stock:" + name; recipe = saved },
                                        onLongClick = { Haptics.click(context); managing = name; manageName = name; deleteAsk = false },
                                    )
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                            ) {
                                Text(name.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp)
                                Text("your recipe", color = if (on) LatentColors.AmberInk.copy(alpha = 0.7f) else LatentColors.Text, fontSize = 8.sp)
                            }
                        }
                        ourStocks.forEach { (name, saved) ->
                            val on = lookId == "stock:" + name
                            Column(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                                    .combinedClickable(onClick = { Haptics.tick(context); lookId = "stock:" + name; recipe = saved })
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                            ) {
                                Text(name.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp)
                                Text("built here", color = if (on) LatentColors.AmberInk.copy(alpha = 0.7f) else LatentColors.Text, fontSize = 8.sp)
                            }
                        }
                        looks.forEach { preset ->
                            val on = preset.id == lookId
                            Column(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                                    .combinedClickable(onClick = { Haptics.tick(context); lookId = preset.id; recipe = preset.recipe })
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                            ) {
                                Text(preset.name.substringBefore(" — ").uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp)
                                Text(preset.name.substringAfter(" — ", preset.group), color = if (on) LatentColors.AmberInk.copy(alpha = 0.7f) else LatentColors.Text, fontSize = 8.sp)
                            }
                        }
                    }
                    // 72a: rename or delete a saved recipe (long-press its chip)
                    val held = managing
                    if (held != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                            NameField(manageName, "rename “$held”") { manageName = it }
                            val clean = Recipes.cleanName(manageName)
                            val canRename = clean.isNotEmpty() && clean != held && !Recipes.exists(context, clean)
                            fun cameraFollows(to: String?) {
                                // the camera may be shooting with this recipe: keep it pointing at it, or back to a look
                                val st = AppSettings.load(context)
                                if (st.preset == "stock:" + held) AppSettings.save(context, st.copy(preset = if (to != null) "stock:" + to else "portra400_endura_wedding"))
                            }
                            if (!deleteAsk) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Rename", color = if (canRename) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (canRename) LatentColors.Amber else LatentColors.Surface)
                                        .combinedClickable(onClick = {
                                            if (canRename && Recipes.rename(context, held, clean)) {
                                                Haptics.tick(context); cameraFollows(clean)
                                                if (lookId == "stock:" + held) lookId = "stock:" + clean
                                                managing = null; recipesRevision++
                                            }
                                        }).padding(horizontal = 12.dp, vertical = 7.dp))
                                Text("Delete", color = LatentColors.Text, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                                        .combinedClickable(onClick = { Haptics.tick(context); deleteAsk = true }).padding(horizontal = 12.dp, vertical = 7.dp))
                                Text("Done", color = LatentColors.Text, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                                        .combinedClickable(onClick = { managing = null }).padding(horizontal = 12.dp, vertical = 7.dp))
                            } else {
                                Text("Delete the recipe “$held”? Photos already developed with it keep their look.", color = LatentColors.Text, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Delete", color = LatentColors.AmberInk, fontSize = 11.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                                            .combinedClickable(onClick = {
                                                Haptics.tick(context); Recipes.delete(context, held); cameraFollows(null)
                                                if (lookId == "stock:" + held) lookId = ""
                                                managing = null; deleteAsk = false; recipesRevision++
                                            }).padding(horizontal = 12.dp, vertical = 7.dp))
                                    Text("Keep it", color = LatentColors.Text, fontSize = 11.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                                            .combinedClickable(onClick = { deleteAsk = false }).padding(horizontal = 12.dp, vertical = 7.dp))
                                }
                            }
                        }
                    }
                    // Tabs
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TABS.forEach { (id, label) ->
                            val on = id == tab
                            Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 9.sp, letterSpacing = 1.sp,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                    .background(if (on) LatentColors.Amber else Color.Transparent)
                                    .then(if (on) Modifier else Modifier.border(0.5.dp, LatentColors.Line, RoundedCornerShape(999.dp)))
                                    .combinedClickable(onClick = { Haptics.tick(context); tab = id }).padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                        when (tab) {
                            "film" -> {
                                com.celestial.latent.develop.Presets.byId(context, lookId)?.let { p -> Note(p.description) }
                                // Brightness, meaning what you expect it to mean.
                                //
                                // On the print route the enlarger recomputes its exposure from the film
                                // exposure — a printer compensating, which is faithful but means the film
                                // exposure changes contrast and colour, not brightness. So brightness is
                                // the print exposure here, and the film exposure lives in Full mode where
                                // it is labelled for what it does. Slide films have no print stage, so
                                // there the film exposure is the brightness.
                                val onPrintRoute = !recipe.scanFilm && !Develop.isSlideFilm(recipe.film)
                                if (onPrintRoute) {
                                    // Print exposure is the light reaching the paper, and paper darkens
                                    // with light — longer exposure, darker print, exactly as in a
                                    // darkroom. So the slider shows brightness and inverts it, or
                                    // dragging right would make the picture darker.
                                    S("Brightness", 1f / recipe.printExposure.coerceAtLeast(0.01f), 0.45f, 2.5f, "%.2f×") {
                                        set { copy(printExposure = (1f / it).coerceIn(com.celestial.latent.develop.PRINT_EXPOSURE_MIN, com.celestial.latent.develop.PRINT_EXPOSURE_MAX)) }
                                    }
                                } else {
                                    S("Brightness", recipe.exposureEv, -3f, 3f, "%+.1f EV") { set { copy(exposureEv = it) } }
                                }
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // The photo opens at the brightness it was shot at. This asks the film
                                    // what it would have chosen — a starting point, not a correction.
                                    Text(if (levelling) "levelling…" else "Auto level", color = LatentColors.AmberInk, fontSize = 11.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                                            .combinedClickable(onClick = {
                                                if (!levelling && !rendering) {
                                                    Haptics.tick(context); levelling = true
                                                    Thread {
                                                        try {
                                                        val s0 = src
                                                        val suggested = if (s0 != null) com.celestial.latent.develop.LookBaker.gainForSource(context, recipe, s0) else 0f
                                                        if (suggested > 0.01f) {
                                                            val ev = (Math.log(suggested.toDouble()) / Math.log(2.0)).toFloat()
                                                            // Applied where brightness actually lives on this route.
                                                            recipe = if (onPrintRoute)
                                                                // "Needs more light" means a brighter print,
                                                                // which means a SHORTER enlarger exposure.
                                                                recipe.copy(printExposure = (recipe.printExposure / suggested).coerceIn(com.celestial.latent.develop.PRINT_EXPOSURE_MIN, com.celestial.latent.develop.PRINT_EXPOSURE_MAX))
                                                            else
                                                                recipe.copy(exposureEv = (recipe.exposureEv + ev).coerceIn(-3f, 3f))
                                                        }
                                                        } finally { levelling = false }
                                                    }.start()
                                                }
                                            }).padding(horizontal = 12.dp, vertical = 7.dp))
                                    Text("Reset to shot", color = LatentColors.Text, fontSize = 11.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                                            .combinedClickable(onClick = {
                                                Haptics.tick(context)
                                                recipe = if (onPrintRoute) recipe.copy(printExposure = 1f) else recipe.copy(exposureEv = 0f)
                                            }).padding(horizontal = 12.dp, vertical = 7.dp))
                                }
                                Note(if (onPrintRoute)
                                    "Brightness sets how long the print is exposed under the enlarger — the darkroom's own way of controlling it. (In the ENLARGER tab the same control appears as print exposure, which runs the other way: more light on the paper means a darker print.) Film exposure is in Full mode: with the print compensating, it shapes contrast and colour rather than brightness."
                                else
                                    "This film is scanned directly, with no print stage, so brightness is the film exposure itself.")
                                S("Push / pull", recipe.pushStops, -2f, 3f, "%+.1f stop") { set { copy(pushStops = it) } }
                                S("Film contrast", recipe.filmContrast, 0.6f, 1.6f, "%.2f") { set { copy(filmContrast = it) } }
                                if (full) {
                                    S("Film exposure (contrast & colour)", recipe.exposureEv, -3f, 3f, "%+.1f EV") { set { copy(exposureEv = it) } }
                                    Toggle("Let the print compensate", recipe.printExposureCompensation) { set { copy(printExposureCompensation = it) } }
                                    Note("Turn this off and film exposure changes brightness directly, as it would if you printed every negative for the same time.")
                                }
                                Head("COLOUR NOISE", null) {}
                                val auto = recipe.chromaDenoise < 0f
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (auto) "AUTO · FROM ISO" else "MANUAL", color = if (auto) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (auto) LatentColors.Amber else LatentColors.Surface)
                                            .combinedClickable(onClick = { Haptics.tick(context); set { copy(chromaDenoise = if (auto) 0.4f else -1f) } })
                                            .padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                                if (!auto) S("Amount", recipe.chromaDenoise, 0f, 1f, "%.2f") { set { copy(chromaDenoise = it) } }
                                Note("Sensor blotches in dark shots are not film grain, and the dye couplers make them worse. This cleans the colour only — brightness, detail and grain are untouched.")
                                Head("BRIGHTNESS NOISE", null) {}
                                val lumaAuto = recipe.lumaDenoise < 0f
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (lumaAuto) "AUTO · FROM ISO" else "MANUAL", color = if (lumaAuto) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (lumaAuto) LatentColors.Amber else LatentColors.Surface)
                                            .combinedClickable(onClick = { Haptics.tick(context); set { copy(lumaDenoise = if (lumaAuto) 0.4f else -1f) } })
                                            .padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                                if (!lumaAuto) S("Amount", recipe.lumaDenoise, 0f, 1f, "%.2f") { set { copy(lumaDenoise = it) } }
                                Note("The sensor's grey speckle in dim shots is digital noise, not film grain. This smooths it while keeping edges, before the film — so the film's own grain is the texture. Lights and fog no longer make it worse.")
                                Head("DIR COUPLERS", recipe.dir) { set { copy(dir = it) } }
                                S("Amount", recipe.dirAmount, 0f, 2f, "%.2f", recipe.dir) { set { copy(dirAmount = it) } }
                                S("Same-layer inhibition", recipe.dirSameLayer, 0f, 2f, "%.2f", recipe.dir) { set { copy(dirSameLayer = it) } }
                                S("Inter-layer inhibition", recipe.dirInterLayer, 0f, 2f, "%.2f", recipe.dir) { set { copy(dirInterLayer = it) } }
                                S("Diffusion size", recipe.dirDiffusionUm, 2f, 80f, "%.0f µm", recipe.dir) { set { copy(dirDiffusionUm = it) } }
                            }
                            "halation" -> {
                                Head("HALATION", recipe.halation) { set { copy(halation = it) } }
                                S("Amount", recipe.halationAmount, 0f, 3f, "%.2f", recipe.halation) { set { copy(halationAmount = it) } }
                                S("Spread", recipe.halationScale, 0.2f, 3f, "%.2f", recipe.halation) { set { copy(halationScale = it) } }
                                S("Scatter", recipe.scatterAmount, 0f, 3f, "%.2f", recipe.halation) { set { copy(scatterAmount = it) } }
                                S("Highlight boost", recipe.halationBoostEv, -2f, 4f, "%+.1f EV", recipe.halation) { set { copy(halationBoostEv = it) } }
                                S("Protect highlights", recipe.halationProtectEv, 0f, 8f, "%.1f EV", recipe.halation) { set { copy(halationProtectEv = it) } }
                                S("Bounces", recipe.halationBounces.toFloat(), 1f, 6f, "%.0f", recipe.halation) { set { copy(halationBounces = Math.round(it)) } }
                                S("Bounce decay", recipe.halationDecay, 0.1f, 0.9f, "%.2f", recipe.halation) { set { copy(halationDecay = it) } }
                            }
                            "grain" -> {
                                Head("GRAIN", recipe.grain) { set { copy(grain = it) } }
                                // the whole picture's grain, stronger or weaker without changing its look (61c)
                                S("Amount", recipe.grainAmount * 100f, 0f, 150f, "%.0f%%", recipe.grain) { set { copy(grainAmount = Math.round(it) / 100f) } }
                                S("Particle size", recipe.grainSizeUm2, 0.05f, 1.2f, "%.2f µm²", recipe.grain) { set { copy(grainSizeUm2 = it) } }
                                S("Softness", recipe.grainBlur, 0f, 2f, "%.2f", recipe.grain) { set { copy(grainBlur = it) } }
                                S("Dye-cloud blur", recipe.grainDyeCloudUm, 0f, 4f, "%.2f µm", recipe.grain) { set { copy(grainDyeCloudUm = it) } }
                                S("Micro-structure", recipe.grainMicroAmount, 0f, 1f, "%.2f", recipe.grain) { set { copy(grainMicroAmount = it) } }
                                S("Micro scale", recipe.grainMicroScale, 5f, 80f, "%.0f", recipe.grain) { set { copy(grainMicroScale = it) } }
                                Toggle("Sublayers", recipe.grainSublayers) { set { copy(grainSublayers = it) } }
                                S("Sublayer count", recipe.grainSublayerCount.toFloat(), 1f, 4f, "%.0f", recipe.grainSublayers) { set { copy(grainSublayerCount = Math.round(it)) } }
                            }
                            "diffusion" -> {
                                Head("LENS FILTER", recipe.diffusion) { set { copy(diffusion = it) } }
                                Toggle("Fast diffusion on export", recipe.fastDiffusion) { set { copy(fastDiffusion = it) } }
                                Note(if (recipe.fastDiffusion)
                                    "The same kernel as the engine's, computed with an FFT: seconds rather than minutes. It runs earlier in the chain than the engine's own, so at the same strength it can read a little softer — raise Strength to match if you are comparing them."
                                else
                                    "The engine's own filter, computed directly. Identical by definition, and the slowest stage by far: minutes at full size.")
                                // Picking a filter turns it on: choosing a family and then finding every
                                // control greyed out was a dead end.
                                Chips(DIFFUSION_FAMILIES, recipe.diffusionFamily) { set { copy(diffusionFamily = it, diffusion = true) } }
                                if (!recipe.diffusion) Note("The lens filter is off — turn on LENS FILTER above, or tap a filter name, to adjust it.")
                                S("Strength", recipe.diffusionStrength, 0f, 1f, "%.2f", recipe.diffusion) { set { copy(diffusionStrength = it) } }
                                S("Spatial scale", recipe.diffusionScale, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionScale = it) } }
                                S("Core intensity", recipe.diffusionCore, 0f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionCore = it) } }
                                S("Core size", recipe.diffusionCoreSize, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionCoreSize = it) } }
                                S("Halo intensity", recipe.diffusionHalo, 0f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionHalo = it) } }
                                S("Halo size", recipe.diffusionHaloSize, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionHaloSize = it) } }
                                S("Bloom intensity", recipe.diffusionBloom, 0f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionBloom = it) } }
                                S("Bloom size", recipe.diffusionBloomSize, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionBloomSize = it) } }
                                S("Halo warmth", recipe.diffusionWarmth, -1f, 1f, "%+.2f", recipe.diffusion) { set { copy(diffusionWarmth = it) } }
                                Head("ENLARGER FILTER", recipe.printDiffusion) { set { copy(printDiffusion = it) } }
                                Note("A diffusion filter under the enlarger rather than on the lens. It blooms the negative, so the softening comes out of the print's shadows instead of glowing around its highlights — quieter, and it takes contrast out of the dark end. Emulated before the engine while fast diffusion is on, which makes it stronger for a given strength than the engine's own: start around 0.2. Turn fast diffusion off for the engine's exact version, at minutes per photo.")
                                Chips(DIFFUSION_FAMILIES, recipe.printDiffusionFamily) { set { copy(printDiffusionFamily = it, printDiffusion = true) } }
                                S("Strength", recipe.printDiffusionStrength, 0f, 1f, "%.2f", recipe.printDiffusion) { set { copy(printDiffusionStrength = it) } }
                            }
                            "camera" -> {
                                Head("UV FILTER", recipe.filterUvAmount > 0.001f) { set { copy(filterUvAmount = if (it) 1f else 0f) } }
                                S("Amount", recipe.filterUvAmount, 0f, 1f, "%.2f") { set { copy(filterUvAmount = it) } }
                                S("Cut point", recipe.filterUvNm, 360f, 460f, "%.0f nm", recipe.filterUvAmount > 0.001f) { set { copy(filterUvNm = it) } }
                                S("Softness", recipe.filterUvWidth, 2f, 40f, "%.0f nm", recipe.filterUvAmount > 0.001f) { set { copy(filterUvWidth = it) } }
                                Head("INFRARED FILTER", recipe.filterIrAmount > 0.001f) { set { copy(filterIrAmount = if (it) 1f else 0f) } }
                                S("Amount", recipe.filterIrAmount, 0f, 1f, "%.2f") { set { copy(filterIrAmount = it) } }
                                S("Cut point", recipe.filterIrNm, 600f, 760f, "%.0f nm", recipe.filterIrAmount > 0.001f) { set { copy(filterIrNm = it) } }
                                S("Softness", recipe.filterIrWidth, 2f, 60f, "%.0f nm", recipe.filterIrAmount > 0.001f) { set { copy(filterIrWidth = it) } }
                                Note("Film sees a little beyond human sight at both ends, so without these filters reds and blues overshoot. This is the same correction the engine's author uses to tame the filming stage.")
                                Head("METERING", null) {}
                                Chips(listOf("center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted"), recipe.meteringMethod) { set { copy(meteringMethod = it) } }
                                Note("How the engine judges the scene's brightness when setting its own exposure.")
                                S("Lens blur", recipe.lensBlurUm, 0f, 40f, "%.0f µm") { set { copy(lensBlurUm = it) } }
                                Head("FILM FORMAT", null) {}
                                Chips(listOf("25" to "Super 35", "35" to "35 mm", "60" to "120 / 6×6", "100" to "Large format").map { it.first }, recipe.filmFormatMm.toInt().toString()) { set { copy(filmFormatMm = it.toFloat()) } }
                                Note("Format changes how big grain and halation look, because they are measured in micrometres on the negative. 25 is Super 35, the cinema frame.")
                                Toggle("85B filter", recipe.lens85b) { set { copy(lens85b = it) } }
                                Note("The orange filter cinematographers use to shoot tungsten film (Vision3 200T, 500T) in daylight.")
                            }
                            "enlarger" -> {
                                if (Develop.isSlideFilm(recipe.film)) {
                                    Note("${Develop.FILMS.firstOrNull { it.first == recipe.film }?.second ?: recipe.film} is a slide film: there is no print stage, so it is scanned directly as a positive. The controls below do nothing for it.")
                                } else {
                                    val target = Develop.targetPrint(recipe.film)
                                    if (target != null) Note("This film was made for " + (Develop.PAPERS.firstOrNull { it.first == target }?.second ?: target) + ", which is selected automatically. Change it if you want a different pairing.")
                                }
                                Head("PAPER / PRINT STOCK", null) {}
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Develop.PAPERS.forEach { (id, label) ->
                                        val on = id == recipe.paper
                                        Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                                                .combinedClickable(onClick = { Haptics.tick(context); set { copy(paper = id) } }).padding(horizontal = 10.dp, vertical = 5.dp))
                                    }
                                }
                                Toggle("Compensate for film exposure", recipe.printExposureCompensation) { set { copy(printExposureCompensation = it) } }
                                Toggle("Normalise print exposure", recipe.normalizePrintExposure) { set { copy(normalizePrintExposure = it) } }
                                Note("With these on — as a darkroom printer would work — the enlarger cancels out changes in film exposure, so the Exposure slider changes contrast and colour rather than brightness. Turn the first off to let film exposure change brightness directly.")
                                S("Print exposure (higher = darker print)", recipe.printExposure, com.celestial.latent.develop.PRINT_EXPOSURE_MIN, com.celestial.latent.develop.PRINT_EXPOSURE_MAX, "%.2f×") { set { copy(printExposure = it) } }
                                S("Paper contrast", recipe.printContrast, 0.6f, 1.6f, "%.2f") { set { copy(printContrast = it) } }
                                S("Yellow filter", recipe.yFilterShift, -20f, 20f, "%+.0f") { set { copy(yFilterShift = it) } }
                                S("Magenta filter", recipe.mFilterShift, -20f, 20f, "%+.0f") { set { copy(mFilterShift = it) } }
                                S("Yellow neutral", recipe.yFilterNeutral, 0f, 120f, "%.0f") { set { copy(yFilterNeutral = it) } }
                                S("Magenta neutral", recipe.mFilterNeutral, 0f, 120f, "%.0f") { set { copy(mFilterNeutral = it) } }
                                S("Pre-flash", recipe.preflash, 0f, 0.5f, "%.2f") { set { copy(preflash = it) } }
                                S("Enlarger lens blur", recipe.enlargerLensBlur, 0f, 3f, "%.2f") { set { copy(enlargerLensBlur = it) } }
                            }
                            "scanner" -> {
                                Toggle("Scan the negative (skip the print)", recipe.scanFilm) { set { copy(scanFilm = it) } }
                                S("Sharpening amount", recipe.unsharpAmount, 0f, 2f, "%.2f") { set { copy(unsharpAmount = it) } }
                                S("Sharpening radius", recipe.unsharpRadius, 0.2f, 2f, "%.2f") { set { copy(unsharpRadius = it) } }
                                S("Scanner lens blur", recipe.scannerLensBlur, 0f, 3f, "%.2f") { set { copy(scannerLensBlur = it) } }
                                Toggle("White correction", recipe.whiteCorrection) { set { copy(whiteCorrection = it) } }
                                S("White level", recipe.scannerWhiteLevel, 0.8f, 1f, "%.3f", recipe.whiteCorrection) { set { copy(scannerWhiteLevel = it) } }
                                Toggle("Black correction", recipe.blackCorrection) { set { copy(blackCorrection = it) } }
                                S("Black level", recipe.scannerBlackLevel, 0f, 0.1f, "%.3f", recipe.blackCorrection) { set { copy(scannerBlackLevel = it) } }
                            }
                            "glare" -> {
                                Head("GLARE", recipe.glare) { set { copy(glare = it) } }
                                S("Amount", recipe.glarePercent, 0f, 0.2f, "%.3f", recipe.glare) { set { copy(glarePercent = it) } }
                                S("Roughness", recipe.glareRoughness, 0f, 1f, "%.2f", recipe.glare) { set { copy(glareRoughness = it) } }
                                S("Blur", recipe.glareBlur, 0f, 2f, "%.2f", recipe.glare) { set { copy(glareBlur = it) } }
                            }
                            "colour" -> {
                                Head("OUTPUT COLOUR SPACE", null) {}
                                Chips(listOf("SRGB", "DISPLAY_P3", "REC709_24", "ADOBE_RGB", "PROPHOTO", "REC2020", "ACES2065_1", "LINEAR_SRGB"), recipe.outputColorSpace) { set { copy(outputColorSpace = it) } }
                                Note(when (recipe.outputColorSpace) {
                                    "SRGB" -> "Sharing, the web, messaging. The safe default."
                                    "DISPLAY_P3" -> "What this phone's screen actually shows, so the file looks its best on the device. Converted by Celestial Darkroom from the engine's sRGB."
                                    "REC709_24" -> "The video standard: sRGB's colours with a 2.4 gamma for a dark room. Pairs with the cine stocks. Converted by Celestial Darkroom."
                                    "ADOBE_RGB" -> "Print work — more greens and cyans than sRGB, still safe in an 8-bit file."
                                    "PROPHOTO" -> "Keeps everything for editing elsewhere. Can band in an 8-bit JPEG."
                                    "REC2020" -> "Very wide, for HDR video pipelines."
                                    "ACES2065_1" -> "For grading in another tool. Looks washed out if viewed directly."
                                    else -> "Unencoded linear data, for compositing. Not for viewing."
                                })
                                if (recipe.outputColorSpace in listOf("PROPHOTO", "REC2020", "ACES2065_1", "LINEAR_SRGB"))
                                    Note("A JPEG has only 256 steps per channel. Spread over a space this wide it can band in skies and skin.")
                                Head("OUT-OF-GAMUT COLOURS", null) {}
                                Chips(listOf("LEGACY_CLIP", "OFF", "ACES_RGC", "OKLCH", "OKLRAB"), recipe.outputGamutCompress) { set { copy(outputGamutCompress = it) } }
                                Head("FILMING-SIDE COMPRESSION", null) {}
                                Chips(listOf("OFF", "XY"), recipe.inputGamutCompress) { set { copy(inputGamutCompress = it) } }
                                Note("Gamut compression decides what happens to colours too saturated for the output space — clipping them, or folding them in gently.")
                            }
                            "engine" -> {
                                Head("RGB → SPECTRUM", null) {}
                                Chips(listOf("HANATOS2025", "MALLETT2019"), recipe.rgbToRaw) { set { copy(rgbToRaw = it) } }
                                Note("How a colour is turned into a light spectrum before the film sees it. Hanatos 2025 is the engine's default and still the current method.")
                                Toggle("Adaptation window", recipe.hanatosWindow) { set { copy(hanatosWindow = it) } }
                                Toggle("Adaptation surface", recipe.hanatosSurface) { set { copy(hanatosSurface = it) } }
                                S("Spectral blur", recipe.spectralBlur, 0f, 20f, "%.1f") { set { copy(spectralBlur = it) } }
                                S("Preview size", recipe.previewMaxSize.toFloat(), 300f, 1600f, "%.0f px") { set { copy(previewMaxSize = Math.round(it)) } }
                                Toggle("GPU preview (experimental)", recipe.gpuPreview) { set { copy(gpuPreview = it) } }
                                Note("GPU is for previews only, by the engine's design: its maths is not identical across chip makers, so exports stay on the CPU. It covers the scan stage, and self-checks against the CPU on this device before it is used.")
                                Text(if (gpuTest.isEmpty()) "Measure GPU vs CPU" else gpuTest, color = LatentColors.AmberInk, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = {
                                        val busy = com.celestial.latent.develop.DevelopQueue.queued > 0
                                        if (rendering || fullRunning || busy) {
                                            gpuTest = "wait until nothing is developing, and give the phone a minute to cool"
                                        } else {
                                            Haptics.tick(context); gpuTest = "measuring…"
                                            val r = recipe.copy(previewMaxSize = 800)
                                            Thread {
                                                val q = com.celestial.latent.develop.DevelopQueue
                                                val holds = q.acquireLane(30)
                                                try {
                                                    val s0 = src ?: Develop.openCached(context, source, isRaw, DECODE_EDGE, recipe, Develop.isoOf(context, source))
                                                    var cpu = 0L; var gpu = 0L
                                                    run { val t = System.nanoTime(); Develop.render(context, s0, r.copy(gpuPreview = false), preview = true); cpu = (System.nanoTime() - t) / 1_000_000 }
                                                    run { val t = System.nanoTime(); Develop.render(context, s0, r.copy(gpuPreview = true), preview = true); gpu = (System.nanoTime() - t) / 1_000_000 }
                                                    gpuTest = "CPU ${cpu} ms · GPU ${gpu} ms" + if (gpu < cpu * 0.9) " — GPU is faster" else if (gpu > cpu * 1.1) " — GPU is slower" else " — no difference"
                                                    android.util.Log.i("Latent", "gpu comparison: cpu=${cpu}ms gpu=${gpu}ms")
                                                } catch (t: Throwable) { gpuTest = "failed: ${t.message}" }
                                                finally { if (holds) q.engineLane.release() }
                                            }.start()
                                        }
                                    }).padding(horizontal = 12.dp, vertical = 7.dp))
                                Note("A hot phone downclocks hard, so measure when it is cool and idle — otherwise both numbers are just throttling.")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(status + (if (lastRenderMs > 0) " · last ${lastRenderMs} ms" else ""), color = LatentColors.TextDim, fontSize = 10.sp)
                        if (tab == "develop") developPage()
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // A failed full-size develop says so, here, until it is tapped away or the next one starts.
        fullError?.let { e ->
            Text(e, color = LatentColors.AmberInk, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(LatentColors.Amber).combinedClickable(onClick = { fullError = null })
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    if (liveOpen) LiveScreen(
        com.celestial.latent.develop.LiveEdit(source, isRaw, recipe, exposureMap, softenMap, pairFirst, framing, fogMap, fogLook,
            raysMap, raysLook, extraLights, depthSplit, saturationMap, lithLook, wetMap, wetLook, rainMap),
    ) { liveOpen = false }
    helpTopic?.let { t -> HelpScreen(start = t) { helpTopic = null } }
    }
}

@Composable
private fun S(label: String, value: Float, min: Float, max: Float, fmt: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val context = LocalContext.current
    var last by remember { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) LatentColors.Text else LatentColors.Line, fontSize = 11.sp)
            Text(String.format(fmt, value), color = if (enabled) LatentColors.TextBright else LatentColors.Line, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(min, max), onValueChange = { v ->
                if (Math.abs(v - last) > (max - min) / 40f) { Haptics.tick(context); last = v }
                onChange(v)
            },
            valueRange = min..max, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line),
        )
    }
}

@Composable
private fun Head(title: String, active: Boolean?, onActive: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.5.sp)
        if (active != null) Text(if (active) "ON" else "OFF", color = if (active) LatentColors.Amber else LatentColors.Line, fontSize = 10.sp,
            modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); onActive(!active) }).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Note(text: String) {
    // 75a: one line, tap for the rest (the full explanation is also in App help, behind "?")
    var open by remember(text) { mutableStateOf(false) }
    Text(text, color = LatentColors.TextDim, fontSize = 10.sp, lineHeight = 13.sp,
        maxLines = if (open) Int.MAX_VALUE else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { open = !open }).padding(vertical = 6.dp))
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Haptics.tick(context); onChange(!value) }).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LatentColors.Text, fontSize = 11.sp)
        Text(if (value) "ON" else "OFF", color = if (value) LatentColors.Amber else LatentColors.Line, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(o.replace('_', ' '), color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); onSelect(o) }).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

/**
 * Everything "Clear effects" takes off a photo (71a), kept so one Undo can put it all back. The
 * depth split and its fixes stay: they describe the photo, they are not an effect on it.
 */
private data class PaintedEffects(
    val exposure: ExposureMap?, val soften: ExposureMap?,
    val fog: ExposureMap?, val fogLook: com.celestial.latent.develop.FogLook,
    val rays: ExposureMap?, val raysLook: com.celestial.latent.develop.RaysLook,
    val extraLights: List<com.celestial.latent.develop.RaysLook>,
    val saturation: ExposureMap?, val lith: com.celestial.latent.develop.LithLook,
    val wet: ExposureMap?, val wetLook: com.celestial.latent.develop.WetLook, val rain: ExposureMap?,
) {
    companion object {
        /** A photo nothing was ever done to: exactly what loading it with nothing saved gives. */
        val NONE = PaintedEffects(null, null, null, com.celestial.latent.develop.FogLook.parse(null),
            null, com.celestial.latent.develop.RaysLook.parse(null), emptyList(),
            null, com.celestial.latent.develop.LithLook.parse(null), null, com.celestial.latent.develop.WetLook.parse(null), null)
    }
}

/**
 * The film's own look for "Reset to film" (71a): the look chosen in the darkroom, else the camera's
 * look if it is this film, else this film's first look, else a film built here, else the plain film.
 * Opened as every photo is, at the brightness it was shot. A cinema shot keeps its cinema choices
 * (print stock, Super 35 frame, 85B), which come from the camera, not from a look.
 */
private fun filmLook(context: android.content.Context, current: Recipe, lookId: String, cinema: Boolean): Recipe {
    val film = current.film
    val presets = com.celestial.latent.develop.Presets
    val camera = AppSettings.load(context).preset
    val base = when {
        lookId.startsWith("stock:") -> Recipes.load(context, lookId.removePrefix("stock:"))
        lookId.isNotEmpty() -> presets.byId(context, lookId)?.recipe
        else -> null
    }?.takeIf { it.film == film }
        ?: (if (camera.startsWith("stock:")) Recipes.load(context, camera.removePrefix("stock:")) else presets.byId(context, camera)?.recipe)?.takeIf { it.film == film }
        ?: presets.defaultFor(context, film)?.recipe
        ?: Recipes.stocks(context).firstOrNull { it.second.film == film }?.second
        ?: Recipe(film = film, paper = current.paper)
    val fresh = base.copy(autoExposure = false)
    return if (cinema) fresh.copy(paper = current.paper, scanFilm = current.scanFilm, filmFormatMm = current.filmFormatMm, lens85b = current.lens85b) else fresh
}

/**
 * The photo before the film, unframed, about 1200 px (72a). Read the way the darkroom reads the photo
 * itself, so its framing lines up: a RAW through the system's thumbnail (whose orientation agrees
 * with the decoder's), any other picture through BitmapFactory, which — like [Develop.openImage] —
 * ignores an EXIF turn; the system thumbnail would have applied it, and then been turned twice.
 */
private fun plainBefore(context: android.content.Context, source: Uri, isRaw: Boolean): Bitmap? = runCatching {
    if (isRaw) context.contentResolver.loadThumbnail(source, android.util.Size(1200, 1200), null)
    else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }
}.getOrNull()

/**
 * The before stood the way the print stands (75d). The print is opened by LibRaw, which turns a RAW
 * upright from the file's orientation; Android's picture of the same file often is not turned (our
 * DNGs carry no preview of their own, and many phones ignore the orientation when drawing one). So
 * the picture the darkroom opened is the judge: when the before's shape disagrees with it (or, before
 * it is opened, when the file says it lies on its side and the before is still landscape), the
 * before is turned the file's way. A before that already agrees is left as it is.
 */
private fun uprightBefore(context: android.content.Context, plain: Bitmap, source: Uri, isRaw: Boolean, opened: Pair<Int, Int>?): Bitmap {
    if (!isRaw) return plain
    val o = Develop.orientationOf(context, source)
    if (o !in 5..8) return plain
    val plainTall = plain.height > plain.width
    val wantTall = opened?.let { (w, h) -> h > w } ?: true
    if (plainTall == wantTall || plain.width == plain.height) return plain
    val m = android.graphics.Matrix()
    // 6: a quarter clockwise; 8: anticlockwise; 5 and 7 are those with a mirror
    when (o) {
        6 -> m.postRotate(90f)
        8 -> m.postRotate(270f)
        5 -> { m.postScale(-1f, 1f); m.postRotate(270f) }
        7 -> { m.postScale(-1f, 1f); m.postRotate(90f) }
    }
    return runCatching { Bitmap.createBitmap(plain, 0, 0, plain.width, plain.height, m, true) }.getOrDefault(plain)
}

/** A one-line name box in the darkroom's colours (72a), like the Film Builder's. */
@Composable
private fun NameField(value: String, label: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(28)) },
        singleLine = true,
        label = { Text(label, color = LatentColors.TextDim, fontSize = 12.sp) },
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = LatentColors.TextBright,
            unfocusedTextColor = LatentColors.TextBright,
            focusedBorderColor = LatentColors.Amber,
            unfocusedBorderColor = LatentColors.Line,
            cursorColor = LatentColors.Amber,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/**
 * A centre crop ([part], [fraction] of each side) laid at its true place in a whole-frame picture,
 * over [under] (the last whole preview, stretched to the frame's size) — or black when there is none
 * of the same shape yet (75b).
 */
private fun inPlace(part: Bitmap, under: Bitmap?, fraction: Float): Bitmap {
    val w = Math.round(part.width / fraction); val h = Math.round(part.height / fraction)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    if (under != null && Math.abs(under.width.toFloat() / under.height - w.toFloat() / h) < 0.02f)
        c.drawBitmap(under, null, android.graphics.Rect(0, 0, w, h), paint)
    else c.drawColor(android.graphics.Color.BLACK)
    val left = (w - part.width) / 2; val top = (h - part.height) / 2
    c.drawBitmap(part, null, android.graphics.Rect(left, top, left + part.width, top + part.height), paint)
    // 78a: and their glows laid the same way, so HDR view does not flicker while a control moves
    runCatching { com.celestial.latent.develop.HdrGlow.inPlace(out, part, under, fraction) }
    return out
}
