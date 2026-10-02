@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.celestial.latent.camera.CameraController
import com.celestial.latent.camera.ControlMath
import com.celestial.latent.camera.Controls
import com.celestial.latent.camera.Lens
import com.celestial.latent.camera.Lenses
import com.celestial.latent.camera.LiveReadout
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.DevelopQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.delay

private enum class Cell { EV, S, ISO, WB, F }

@Composable
fun CameraScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenRoll: () -> Unit = {},
    onOpenDarkroom: (Uri, Boolean) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit,
    onOpenExtension: () -> Unit = {},
    onLensChanged: (Lens) -> Unit,
    onController: (CameraController) -> Unit = {},
    onVendorEcho: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting camera…") }
    var log by remember { mutableStateOf("") }
    val startLens = remember { Lenses.ALL.firstOrNull { it.physicalId == settings.defaultLensId } ?: Lenses.DEFAULT }
    var lens by remember { mutableStateOf(startLens) }
    var readout by remember { mutableStateOf(LiveReadout()) }
    var kelvinShown by remember { mutableStateOf(0) }
    var controls by remember { mutableStateOf(Controls()) }
    var selected by remember { mutableStateOf<Cell?>(null) }
    var focusTap by remember { mutableStateOf<Offset?>(null) }
    var focusTapAt by remember { mutableStateOf(0L) }
    var drawerOpen by remember { mutableStateOf(false) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var lastRawUri by remember { mutableStateOf<Uri?>(null) }
    // Double exposure: the held first frame (it survives the app closing, like film not wound on),
    // the last single RAW ("Don't advance" holds it), and its ghost over the live view.
    var pendingFirst by remember { mutableStateOf(com.celestial.latent.develop.DoubleExposure.pending(context)) }
    var lastSingleRaw by remember { mutableStateOf<Uri?>(null) }
    var ghost by remember { mutableStateOf<Bitmap?>(null) }
    var glPreview by remember { mutableStateOf<com.celestial.latent.gl.FilmPreviewView?>(null) }
    var lookReady by remember { mutableStateOf(false) }
    var baking by remember { mutableStateOf(false) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var countdown by remember { mutableStateOf(0) }
    var developing by remember { mutableStateOf(DevelopQueue.queued) }

    fun loadThumb(uri: Uri) {
        Thread {
            val b = runCatching { context.contentResolver.loadThumbnail(uri, Size(192, 192), null) }.getOrNull()
            if (b != null) { thumb = b; lastUri = uri }
        }.start()
    }

    val controller = remember {
        CameraController(
            context,
            onStatus = { s -> status = s },
            onLog = { s -> log = (s + "\n" + log).take(2000) },
            onReadout = { r -> readout = r; if (r.kelvinEstimate > 0) kelvinShown = r.kelvinEstimate },
            onSaved = { uri ->
                loadThumb(uri)
                // Single RAW captures are developed in the background; bursts and JPEGs are not.
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull().orEmpty()
                if (name.endsWith(".dng", true)) lastRawUri = uri
                val single = name.endsWith(".dng", true) && !name.contains("BURST") && !name.contains("STACK")
                val held = pendingFirst
                if (single && held != null && held != uri) {
                    // The second exposure: its light is added to the held frame and the pair is
                    // developed as one frame — even with auto-develop off, since it was asked for.
                    com.celestial.latent.develop.DoubleExposure.record(context, held, uri)
                    com.celestial.latent.develop.DoubleExposure.setPending(context, null)
                    pendingFirst = null; ghost = null; lastSingleRaw = null
                    DevelopQueue.submit(context, DevelopQueue.Job(
                        uri,
                        com.celestial.latent.develop.Recipes.current(context).copy(autoExposure = settings.engineAutoExposure),
                        isRaw = true, first = held,
                    ))
                } else {
                    if (single) lastSingleRaw = uri
                    if (settings.autoDevelop && single) {
                        DevelopQueue.submit(context, DevelopQueue.Job(
                            uri,
                            // The camera's exposure is the exposure: the film does not re-level it.
                            com.celestial.latent.develop.Recipes.current(context).copy(autoExposure = settings.engineAutoExposure),
                            isRaw = true,
                        ))
                    }
                }
            },
        )
    }
    var surfaceRef by remember { mutableStateOf<Surface?>(null) }

    // The held frame's ghost, upright to match the portrait viewfinder.
    LaunchedEffect(pendingFirst) {
        val held = pendingFirst
        ghost = if (held == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.celestial.latent.develop.DoubleExposure.ghost(context, held)?.let { b ->
                // the RAW's own thumbnail (used when no JPEG was saved) can come sideways
                if (b.width > b.height) {
                    val m = android.graphics.Matrix().apply { postRotate(90f) }
                    Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                } else b
            }
        }
    }

    // Most recent Latent file at startup.
    LaunchedEffect(Unit) {
        val uri = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/Latent%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
        }.getOrNull()
        if (uri != null) loadThumb(uri)
    }

    fun push(c: Controls) { controls = c; controller.setControls(c) }

    fun shoot(single: Boolean) {
        Haptics.heavy(context)
        val fire = { if (single) controller.captureSingle() else controller.captureBurst(16) }
        if (settings.timerSeconds > 0) countdown = settings.timerSeconds else fire()
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown -= 1; if (countdown == 0) { if (settings.burstMode) controller.captureBurst(16) else controller.captureSingle() } }
    }

    DisposableEffect(Unit) {
        ShutterBus.onShutter = { if (settings.volumeShutter) shoot(single = !settings.burstMode) }
        onDispose { ShutterBus.onShutter = null; controller.destroy(); glPreview?.release(); glPreview = null }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glPreview?.onResume()
                    // On the GL path the camera is reopened by onSurfaceReady when the new
                    // surface exists; reopening here would use the destroyed one.
                    if (glPreview == null) controller.reopenIfNeeded()
                    controller.syncSession()
                }
                Lifecycle.Event.ON_PAUSE -> { controller.close(); glPreview?.onPause() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(settings.antibanding) { controller.setAntibanding(settings.antibanding) }
    // The film look for the viewfinder: baked off the main thread, and the previous one stays
    // on screen while the new one is prepared, so the preview never flashes.
    // Bake the look once per film choice. LookBaker caches by the colour-affecting settings,
    // so this is cheap when nothing that matters has changed.
    LaunchedEffect(settings.preset, settings.filmPreview, glPreview != null) {
        val view = glPreview
        if (!settings.filmPreview || view == null) return@LaunchedEffect
        baking = true
        withContext(Dispatchers.Default) {
            val recipe = com.celestial.latent.develop.Recipes.current(context)
                .copy(autoExposure = settings.engineAutoExposure)
            val look = com.celestial.latent.develop.LookBaker.bake(context, recipe)
            if (look != null) {
                view.setExposureGain(look.gain)
                view.setLut(look.table, look.size)
                lookReady = true
            } else {
                lookReady = false
                controller.log("viewfinder: no look table for ${settings.film}; showing the plain image")
            }
        }
        baking = false
    }

    // Scene metering only makes sense when the film is allowed to level the exposure. With the
    // camera in charge (the default) the preview keeps the look's own gain, so what you expose
    // is what you see.
    LaunchedEffect(lookReady, settings.filmPreview, settings.engineAutoExposure) {
        if (!settings.filmPreview || !settings.engineAutoExposure) return@LaunchedEffect
        var metering = false
        while (lookReady) {
            val view = glPreview
            // One at a time: if the previous round is still going, skip rather than pile up.
            if (view != null && !metering && DevelopQueue.queued == 0) {
                metering = true
                view.grabFrame(64) { frame, w, h ->
                    Thread {
                        try {
                            val recipe = com.celestial.latent.develop.Recipes.current(context)
                            val g = com.celestial.latent.develop.LookBaker.gainForFrame(context, recipe, frame, w, h)
                            // A zero means the engine was busy and the round was skipped.
                            if (g > 0.01f && glPreview === view) view.setExposureGain(g)
                        } finally { metering = false }
                    }.start()
                }
            }
            delay(3000)
        }
    }

    // Any of these changes the streams or the session tags: push them, then let the controller rebuild if needed.
    LaunchedEffect(settings.cameraPath, settings.opmode, settings.opmodeLens, settings.vendorTags, settings.saveJpeg, settings.inSensorZoomJpeg, settings.teleZoomDirect) {
        controller.cameraPath = settings.cameraPath
        controller.opmode = settings.opmode
        controller.vendorTags = settings.vendorTags.map { CameraController.VendorTagSpec(it.name, it.scope, it.type, it.value, it.lens) }
        controller.opmodeLens = settings.opmodeLens
        controller.saveJpeg = settings.saveJpeg
        controller.inSensorZoomJpeg = settings.inSensorZoomJpeg
        controller.teleZoomDirect = settings.teleZoomDirect
        controller.syncSession()
    }
    DisposableEffect(Unit) {
        DevelopQueue.onChanged = { developing = DevelopQueue.queued }
        DevelopQueue.onDeveloped = { uri -> loadThumb(uri) }
        onDispose { DevelopQueue.onChanged = {}; DevelopQueue.onDeveloped = {} }
    }
    LaunchedEffect(Unit) { controller.onVendorEcho = onVendorEcho; controller.onBurstFinished = { Haptics.click(context) }; onController(controller) }
    LaunchedEffect(settings.haptics) { Haptics.enabled = settings.haptics }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(status) {
        val s = status.lowercase()
        if (s.contains("fail") || s.contains("error") || s.contains("unavailable") || s.contains("restarting")) { toast = status; delay(3500); toast = "" }
    }

    // Session-affecting toggles need a rebuild: reopen the same lens.
    fun reopen() { surfaceRef?.let { surf -> controller.open(lens, surf) } }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()) {

        // ---- Top bar: wordmark · chevron · gear ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LATENT", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Light)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (drawerOpen) "︿" else "﹀", color = if (drawerOpen) LatentColors.Amber else LatentColors.Text, fontSize = 18.sp,
                    modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); drawerOpen = !drawerOpen }).padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.width(8.dp))
                Text("⚙", color = LatentColors.Text, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = onOpenSettings).padding(4.dp))
            }
        }

        // ---- Viewfinder ----
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(LatentColors.Surface)
                .pointerInput(drawerOpen) {
                    detectTapGestures(
                        onTap = { pos ->
                            if (drawerOpen) { drawerOpen = false; return@detectTapGestures }
                            focusTap = pos; focusTapAt = System.currentTimeMillis()
                            if (controls.locked) controller.unlock()
                            controller.tapFocus(pos.x / size.width, pos.y / size.height)
                            controls = controls.copy(focusDiopters = null, locked = false)
                        },
                        onLongPress = { pos ->
                            Haptics.double(context)
                            focusTap = pos; focusTapAt = System.currentTimeMillis()
                            controller.lockAt(pos.x / size.width, pos.y / size.height)
                            controls = controls.copy(focusDiopters = null, locked = true)
                        },
                    )
                },
        ) {
            if (settings.filmPreview) {
                // Our own preview path: camera frames are drawn by a shader, so a film look can
                // be applied to the live image. Neutral until a look table is supplied.
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    com.celestial.latent.gl.FilmPreviewView(ctx).apply {
                        val size = controller.previewSizeFor(lens)
                        setBufferSize(size.width, size.height)
                        onSurfaceReady = { surf, _ ->
                            surfaceRef = surf
                            controller.log("viewfinder: film preview path")
                            controller.open(lens, surf)
                        }
                        onUnavailable = { why ->
                            controller.log("viewfinder: $why — using the plain preview")
                            onSettingsChange(settings.copy(filmPreview = false))
                        }
                        glPreview = this
                    }
                }, update = { view ->
                    val size = controller.previewSizeFor(lens)
                    view.setBufferSize(size.width, size.height)
                })
            } else {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val size = controller.previewSizeFor(lens)
                                st.setDefaultBufferSize(size.width, size.height)
                                val surf = Surface(st); surfaceRef = surf
                                controller.log("viewfinder: plain preview path")
                                controller.open(lens, surf)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surfaceRef = null; controller.close(); return true }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                })
            }
            // Double exposure: the first frame, faintly, so the second can be framed against it.
            ghost?.let { Image(it.asImageBitmap(), contentDescription = "First exposure", contentScale = ContentScale.Crop,
                alpha = 0.38f, modifier = Modifier.fillMaxSize()) }
            if (settings.gridlines) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Color(0x66FFFFFF); val w = size.width; val h = size.height
                    drawLine(c, Offset(w / 3, 0f), Offset(w / 3, h), 1f); drawLine(c, Offset(2 * w / 3, 0f), Offset(2 * w / 3, h), 1f)
                    drawLine(c, Offset(0f, h / 3), Offset(w, h / 3), 1f); drawLine(c, Offset(0f, 2 * h / 3), Offset(w, 2 * h / 3), 1f)
                }
            }
            FocusEvOverlay(
                point = focusTap, evIndex = controls.evIndex,
                evRange = (if (controller.hasCharacteristics) controller.evRange.lower..controller.evRange.upper else -24..24),
                onEv = { n -> if (!controls.manualExposure) push(controls.copy(evIndex = n)) },
                onDismiss = { focusTap = null },
            )
            // Double exposure, top right: hold the last frame, or show that one is held (× winds on).
            val heldFrame = pendingFirst
            if (heldFrame != null || lastSingleRaw != null) {
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        if (heldFrame != null) "2ND EXPOSURE  ×" else "DON'T ADVANCE",
                        color = if (heldFrame != null) LatentColors.AmberInk else LatentColors.TextBright,
                        fontSize = 10.sp, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (heldFrame != null) LatentColors.Amber else Color(0x99000000))
                            .combinedClickable(onClick = {
                                Haptics.tick(context)
                                if (heldFrame != null) {
                                    // wind on: the held frame stays a photo of its own
                                    com.celestial.latent.develop.DoubleExposure.setPending(context, null); pendingFirst = null
                                } else {
                                    lastSingleRaw?.let { com.celestial.latent.develop.DoubleExposure.setPending(context, it); pendingFirst = it }
                                }
                            })
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    if (heldFrame != null) Text("frame the second over the first", color = LatentColors.TextBright, fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            // Quiet captions overlaid on the image.
            Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Text(if (settings.saveJpeg) "RAW + JPG · 12.5M" else "RAW · 12.5M", color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp)
                // Says plainly what the live image is and is not, without crowding the frame.
                if (settings.filmPreview) Text(
                    (com.celestial.latent.develop.Presets.byId(context, settings.preset)?.name?.substringBefore(" — ")
                        ?: Develop.FILMS.firstOrNull { it.first == settings.film }?.second ?: "FILM").uppercase() +
                        when {
                            baking -> " · PREPARING"
                            lookReady -> " · COLOUR ONLY"
                            else -> " · PLAIN IMAGE"
                        },
                    color = if (lookReady) LatentColors.TextDim else LatentColors.Line,
                    fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 3.dp),
                )
            }
            val modes = listOfNotNull(
                if (settings.inSensorZoomJpeg && Lenses.isXiaomi15Ultra && controls.zoom > 1.001f) "ISZ" else null,
                if (settings.burstMode) "BURST" else null, if (settings.timerSeconds > 0) "${settings.timerSeconds}S" else null,
            )
            val tagLabels = remember(settings.vendorTags, lens) { if (controller.hasCharacteristics) controller.activeTagLabels() else emptyList() }
            val allLabels = modes + tagLabels
            if (allLabels.isNotEmpty()) Text(allLabels.joinToString(" · "), color = LatentColors.Amber, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
            if (controls.locked) Text("AE/AF LOCK", color = LatentColors.AmberInk, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 10.dp, vertical = 4.dp))
            if (countdown > 0) Text("$countdown", color = LatentColors.TextBright, fontSize = 64.sp, modifier = Modifier.align(Alignment.Center))
            Text((if (lens.mm >= 70) "TELE" else lens.name.uppercase()) + " · ${lens.mm} MM" + (if (readout.afState.isNotEmpty()) " · AF ${readout.afState.uppercase()}" else ""),
                color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 122.dp))
            Text(if (controls.zoom == 2f) "×2 ON" else "", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 122.dp))
            // Lens row floating on the image: plain numbers, active one larger; ×2 multiplies the current lens.
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp), verticalAlignment = Alignment.CenterVertically) {
                Lenses.ALL.forEach { l ->
                    val on = l == lens
                    Text(if (on) l.label + "×" else l.label, color = if (on) LatentColors.TextBright else LatentColors.Text, fontSize = if (on) 15.sp else 12.sp,
                        modifier = Modifier.combinedClickable(onClick = {
                            if (l != lens) { Haptics.tick(context); lens = l; push(controls.copy(shutterNs = null, iso = null, focusDiopters = null)); onLensChanged(l); reopen() }
                        }).padding(horizontal = 11.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(6.dp))
                val zoomOn = controls.zoom == 2f
                val effective = String.format("%.1f", lens.label.toFloat() * 2).removeSuffix(".0")
                Text(if (zoomOn) "$effective×" else "×2", color = if (zoomOn) LatentColors.AmberInk else LatentColors.Amber, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (zoomOn) LatentColors.Amber else Color.Transparent).border(0.5.dp, LatentColors.Amber, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = { Haptics.tick(context); push(controls.copy(zoom = if (zoomOn) 1f else 2f)) }).padding(horizontal = 9.dp, vertical = 3.dp))
            }
            FilmStrip(settings.preset, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                onLongPress = { lastRawUri?.let { u -> onOpenDarkroom(u, true) } }) { id ->
                    // A film built here is a saved recipe rather than one of the engine's looks.
                    if (id.startsWith("stock:")) {
                        com.celestial.latent.develop.Recipes.load(context, id.removePrefix("stock:"))?.let { r ->
                            onSettingsChange(settings.copy(preset = id, film = r.film))
                            com.celestial.latent.develop.Recipes.setCurrent(context, r)
                            com.celestial.latent.develop.LookBaker.invalidate()
                        }
                        return@FilmStrip
                    }
                    val preset = com.celestial.latent.develop.Presets.byId(context, id)
                    if (preset != null) {
                        // A look is a whole recipe, not just a film: the per-stock grain and
                        // halation it carries are most of what makes stocks differ.
                        onSettingsChange(settings.copy(preset = id, film = preset.recipe.film))
                        com.celestial.latent.develop.Recipes.setCurrent(context, preset.recipe)
                        com.celestial.latent.develop.LookBaker.invalidate()
                    }
                }
            if (toast.isNotEmpty()) Text(toast, color = LatentColors.TextBright, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC161615)).padding(12.dp))
            // Quick-settings drawer over the lower part of the viewfinder.
            if (drawerOpen) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xEE2C2C2A)).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (Lenses.isXiaomi15Ultra) Tile("In-sensor ×2", settings.inSensorZoomJpeg, Modifier.weight(1f)) { onSettingsChange(settings.copy(inSensorZoomJpeg = it)) }
                        Tile("RAW + JPEG", settings.saveJpeg, Modifier.weight(1f)) { onSettingsChange(settings.copy(saveJpeg = it)) }
                        Tile("Gridlines", settings.gridlines, Modifier.weight(1f)) { onSettingsChange(settings.copy(gridlines = it)) }
                        Tile("Haptics", settings.haptics, Modifier.weight(1f)) { onSettingsChange(settings.copy(haptics = it)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tile("Tele direct zoom", settings.teleZoomDirect, Modifier.weight(1f)) { onSettingsChange(settings.copy(teleZoomDirect = it)) }
                        Tile(if (settings.timerSeconds == 0) "Timer off" else "Timer ${settings.timerSeconds}s", settings.timerSeconds > 0, Modifier.weight(1f)) {
                            onSettingsChange(settings.copy(timerSeconds = when (settings.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }))
                        }
                        Tile("Portrait / Night", false, Modifier.weight(1f)) { drawerOpen = false; onOpenExtension() }
                        Tile("All settings", false, Modifier.weight(1f)) { drawerOpen = false; onOpenSettings() }
                    }
                }
            }
        }

        // ---- Control strip ----
        val evStr = if (controls.manualExposure) "—" else String.format("%+.1f", controls.evIndex / 6.0).replace("+0.0", "0.0")
        val sStr = ControlMath.shutterLabel(controls.shutterNs ?: readout.shutterNs)
        val isoStr = (controls.iso ?: readout.iso).takeIf { it > 0 }?.toString() ?: "—"
        val wbStr = controls.kelvin?.toString() ?: if (kelvinShown > 0) "$kelvinShown" else "—"
        val fStr = if (controls.focusDiopters == null) "AF" else focusLabel(controls.focusDiopters!!)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            StripCell("EV", evStr, false, selected == Cell.EV, controls.manualExposure, Modifier.weight(1f)) { selected = if (selected == Cell.EV) null else Cell.EV }
            StripCell("S", sStr, controls.shutterNs == null, selected == Cell.S, false, Modifier.weight(1f)) { selected = if (selected == Cell.S) null else Cell.S }
            StripCell("ISO", isoStr, controls.iso == null, selected == Cell.ISO, false, Modifier.weight(1f)) { selected = if (selected == Cell.ISO) null else Cell.ISO }
            StripCell("WB", wbStr, controls.kelvin == null, selected == Cell.WB, false, Modifier.weight(1f)) { selected = if (selected == Cell.WB) null else Cell.WB }
            StripCell("FOCUS", fStr, false, selected == Cell.F, false, Modifier.weight(1f)) { selected = if (selected == Cell.F) null else Cell.F }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            when (selected) {
                Cell.EV -> { val r = if (controller.hasCharacteristics) controller.evRange else android.util.Range(-24, 24)
                    StripSlider(controls.evIndex.toFloat(), r.lower.toFloat(), r.upper.toFloat(), (r.upper - r.lower - 1).coerceAtLeast(0), { push(controls.copy(evIndex = Math.round(it))) }, { push(controls.copy(evIndex = 0)) }, "0", !controls.manualExposure) }
                Cell.S -> { val presets = if (controller.hasCharacteristics) ControlMath.shutterPresets(controller.shutterRange.lower, controller.shutterRange.upper) else listOf(10_000_000L)
                    val cur = controls.shutterNs ?: readout.shutterNs; val idx = presets.indices.minByOrNull { Math.abs(presets[it] - cur) } ?: 0
                    StripSlider(idx.toFloat(), 0f, (presets.size - 1).toFloat(), (presets.size - 2).coerceAtLeast(0), { push(controls.copy(shutterNs = presets[Math.round(it).coerceIn(0, presets.size - 1)])) }, { push(controls.copy(shutterNs = null)) }, "A") }
                Cell.ISO -> { val presets = if (controller.hasCharacteristics) ControlMath.isoPresets(controller.isoRange.lower, controller.isoRange.upper) else listOf(100)
                    val cur = controls.iso ?: readout.iso; val idx = presets.indices.minByOrNull { Math.abs(presets[it] - cur) } ?: 0
                    StripSlider(idx.toFloat(), 0f, (presets.size - 1).toFloat(), (presets.size - 2).coerceAtLeast(0), { push(controls.copy(iso = presets[Math.round(it).coerceIn(0, presets.size - 1)])) }, { push(controls.copy(iso = null)) }, "A") }
                Cell.WB -> { val cur = controls.kelvin ?: (if (kelvinShown > 0) kelvinShown else 5200)
                    StripSlider(cur.toFloat(), 2000f, 10000f, 79, { push(controls.copy(kelvin = Math.round(it / 100f) * 100)) }, { push(controls.copy(kelvin = null)) }, "A") }
                Cell.F -> { val maxD = if (controller.hasCharacteristics) controller.minFocusDiopters else 10f
                    StripSlider((controls.focusDiopters ?: readout.focusDiopters).coerceIn(0f, maxD), 0f, maxD, 0, { push(controls.copy(focusDiopters = it)) }, { push(controls.copy(focusDiopters = null)) }, "AF") }
                null -> Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(0.5.dp).background(LatentColors.Surface))
            }
        }

        // ---- Shutter row: thumbnail · shutter · burst toggle ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(LatentColors.Surface)
                .combinedClickable(onClick = { Haptics.tick(context); onOpenRoll() })) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (developing > 0) Text("$developing", color = LatentColors.AmberInk, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(LatentColors.Amber).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Box(
                Modifier.size(78.dp).clip(CircleShape).border(2.dp, LatentColors.TextBright, CircleShape)
                    .combinedClickable(onClick = { shoot(single = !settings.burstMode) }, onLongClick = { shoot(single = settings.burstMode) }),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(62.dp).clip(CircleShape).background(if (settings.burstMode) LatentColors.Amber else LatentColors.TextBright)) }
            Box(Modifier.size(46.dp).clip(CircleShape).border(0.5.dp, if (settings.burstMode) LatentColors.Amber else LatentColors.Line, CircleShape)
                .combinedClickable(onClick = { Haptics.tick(context); onSettingsChange(settings.copy(burstMode = !settings.burstMode)) }), contentAlignment = Alignment.Center) {
                Text("16", color = if (settings.burstMode) LatentColors.Amber else LatentColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Light)
            }
        }
        val filmLabel = Develop.FILMS.firstOrNull { it.first == settings.film }?.second?.uppercase() ?: "FILM"
        Text(filmLabel + (if (developing > 0) " · DEVELOPING $developing" else if (settings.burstMode) " · BURST" else ""), color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Tag(t: String) {
    Text(t, color = LatentColors.AmberInk, fontSize = 9.sp, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) LatentColors.Amber else LatentColors.TextBright, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Surface else Color.Transparent).combinedClickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp))
}

@Composable
private fun Tile(label: String, on: Boolean, modifier: Modifier, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(8.dp)).background(if (on) LatentColors.Amber else LatentColors.Line).combinedClickable(onClick = { Haptics.tick(ctx); onToggle(!on) }).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 10.sp, lineHeight = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

internal fun focusLabel(d: Float): String = when { d <= 0.05f -> "∞"; d < 1f -> String.format("%.1fm", 1 / d); else -> String.format("%.0fcm", 100 / d) }

@Composable
internal fun StripCell(label: String, value: String, auto: Boolean, selected: Boolean, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.combinedClickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = if (dim) LatentColors.Line else if (selected) LatentColors.Amber else LatentColors.TextBright, fontSize = 16.sp, fontWeight = FontWeight.Light)
        Text(if (auto) "$label · A" else label, color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun StripSlider(value: Float, min: Float, max: Float, steps: Int, onChange: (Float) -> Unit, onAuto: () -> Unit, autoLabel: String, enabled: Boolean = true) {
    val context = LocalContext.current
    var lastTick by remember { mutableStateOf(value) }
    val tickEvery = if (steps > 0) (max - min) / (steps + 1) else (max - min) / 24f
    val onChangeHaptic: (Float) -> Unit = { v ->
        if (Math.abs(v - lastTick) >= tickEvery * 0.999f) { Haptics.tick(context); lastTick = v }
        onChange(v)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(autoLabel, color = LatentColors.AmberInk, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = onAuto).padding(horizontal = 10.dp, vertical = 4.dp))
        Spacer(Modifier.width(12.dp))
        Slider(value = value.coerceIn(min, max), onValueChange = onChangeHaptic, valueRange = min..max, steps = steps, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line), modifier = Modifier.weight(1f))
    }
}


/**
 * Focus box at the tap point with a vertical exposure slider beside it (Xiaomi/iPhone style).
 * Drag anywhere on the overlay up/down to change EV; the box fades after a few seconds of no interaction.
 */
@Composable
internal fun FocusEvOverlay(
    point: Offset?,
    evIndex: Int,
    evRange: IntRange,
    onEv: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = point ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    var lastTick by remember(p) { mutableStateOf(evIndex) }
    var touchedAt by remember(p) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(touchedAt) { delay(2500); onDismiss() }
    val boxPx = with(density) { 72.dp.toPx() }
    val trackPx = with(density) { 120.dp.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(p) {
            detectVerticalDragGestures(
                onDragStart = { touchedAt = System.currentTimeMillis() },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    touchedAt = System.currentTimeMillis()
                    // 120dp of drag spans the whole EV range; up = brighter.
                    val perStep = trackPx / (evRange.last - evRange.first).coerceAtLeast(1)
                    val delta = -dragAmount / perStep
                    val next = Math.round(evIndex + delta).coerceIn(evRange.first, evRange.last)
                    if (next != evIndex) { if (next != lastTick) { Haptics.tick(context); lastTick = next }; onEv(next) }
                },
            )
        },
    ) {
        // Box
        Box(Modifier.offset { IntOffset((p.x - boxPx / 2).toInt(), (p.y - boxPx / 2).toInt()) }.size(72.dp).border(1.dp, LatentColors.Amber, RoundedCornerShape(4.dp)))
        // Slider track to the right of the box (or left if near the edge)
        val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        val onRight = p.x + boxPx / 2 + with(density) { 40.dp.toPx() } < screenW
        val tx = if (onRight) p.x + boxPx / 2 + with(density) { 14.dp.toPx() } else p.x - boxPx / 2 - with(density) { 14.dp.toPx() }
        val frac = (evIndex - evRange.first).toFloat() / (evRange.last - evRange.first).coerceAtLeast(1)
        Box(Modifier.offset { IntOffset(tx.toInt(), (p.y - trackPx / 2).toInt()) }.size(2.dp, 120.dp).background(LatentColors.Line))
        Box(Modifier.offset { IntOffset((tx - with(density) { 5.dp.toPx() }).toInt(), (p.y + trackPx / 2 - frac * trackPx - with(density) { 6.dp.toPx() }).toInt()) }.size(12.dp).clip(CircleShape).background(LatentColors.Amber))
        Text(String.format("%+.1f", evIndex / 6.0).replace("+0.0", "0.0"), color = LatentColors.Amber, fontSize = 11.sp,
            modifier = Modifier.offset { IntOffset((tx + with(density) { 12.dp.toPx() }).toInt(), (p.y - trackPx / 2 - with(density) { 18.dp.toPx() }).toInt()) })
    }
}
