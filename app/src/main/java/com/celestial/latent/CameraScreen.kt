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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.celestial.latent.camera.CameraController
import com.celestial.latent.camera.ControlMath
import com.celestial.latent.camera.Controls
import com.celestial.latent.camera.FastShot
import com.celestial.latent.camera.Lens
import com.celestial.latent.camera.Lenses
import com.celestial.latent.camera.LiveReadout
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.DevelopQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.OnPhoto
import com.celestial.latent.ui.floating
import com.celestial.latent.ui.glow
import com.celestial.latent.ui.liftedTop
import com.celestial.latent.ui.raised
import com.celestial.latent.ui.shutterRing
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
    // 77a: the film cards folded away or open, kept between visits
    val uiPrefs = remember { context.getSharedPreferences("latent_camera_ui", android.content.Context.MODE_PRIVATE) }
    var filmsFolded by remember { mutableStateOf(runCatching { uiPrefs.getBoolean("films_folded", false) }.getOrDefault(false)) }
    var lens by remember { mutableStateOf(startLens) }
    var readout by remember { mutableStateOf(LiveReadout()) }
    var kelvinShown by remember { mutableStateOf(0) }
    var controls by remember { mutableStateOf(Controls()) }
    var selected by remember { mutableStateOf<Cell?>(null) }
    var focusTap by remember { mutableStateOf<Offset?>(null) }
    var focusTapAt by remember { mutableStateOf(0L) }
    var drawerOpen by remember { mutableStateOf(false) }
    // 86b: the HDR BRACKET card (hold the HDR button)
    var bracketOpen by remember { mutableStateOf(false) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var lastRawUri by remember { mutableStateOf<Uri?>(null) }
    // Double exposure: the held first frame (it survives the app closing, like film not wound on),
    // the last single RAW ("Don't advance" holds it), and its ghost over the live view.
    var pendingFirst by remember { mutableStateOf(com.celestial.latent.develop.DoubleExposure.pending(context)) }
    var lastSingleRaw by remember { mutableStateOf<Uri?>(null) }
    var ghost by remember { mutableStateOf<Bitmap?>(null) }
    // The save handler below is built once, so it reads the settings through this, which always
    // holds the current ones — a switch flipped in the camera takes effect on the next shot.
    val liveSettings by rememberUpdatedState(settings)
    // Which way the phone is held, in quarter turns clockwise from upright (0..3).
    var deviceTurns by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceTurns = ((o + 45) / 90) % 4
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    var glPreview by remember { mutableStateOf<com.celestial.latent.gl.FilmPreviewView?>(null) }
    // 85a: the plain path's view, for the freeze-frame when switching to Night/Auto
    var plainPreview by remember { mutableStateOf<TextureView?>(null) }
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
                // The phone's turn — and in cinema mode the widescreen crop and bars — kept in the
                // photo's framing BEFORE any develop starts, so the develop (which reads the
                // framing) already comes out the right way up and the right shape.
                val cine = single && liveSettings.cinema
                val turnNow = if (liveSettings.autoRotate) deviceTurns else 0
                // 79a: a fast shot's crop (35 mm from the 23 mm main...) goes into its framing the same way
                val fast = FastShot.savedCrops.remove(uri.toString())
                val lensCrop = if (single) fast?.first ?: 1f else 1f
                if (single && (turnNow != 0 || cine || lensCrop > 1.001f)) {
                    com.celestial.latent.develop.Framings.save(context, uri, com.celestial.latent.develop.Framing(
                        turns = turnNow, aspect = if (cine) liveSettings.cinemaAspect else 0f, bars = cine && liveSettings.cinemaBars,
                        zoom = lensCrop))
                }
                if (single && fast != null && fast.first > 1.001f) com.celestial.latent.develop.Framings.saveShotCrop(context, uri, fast.first, fast.second)
                // The recipe this shot develops with. A cinema shot keeps its own, so the darkroom
                // and the roll develop it as it was shot, and the shared recipe never turns Vision3.
                val shotRecipe = com.celestial.latent.develop.Recipes.current(context)
                    .copy(autoExposure = liveSettings.engineAutoExposure)
                    .let { if (cine) Cinema.recipe(it, liveSettings) else it }
                if (cine) PhotoRecipes.save(context, uri, shotRecipe)
                val held = pendingFirst
                if (single && held != null && held != uri) {
                    // The second exposure: its light is added to the held frame and the pair is
                    // developed as one frame — even with auto-develop off, since it was asked for.
                    com.celestial.latent.develop.DoubleExposure.record(context, held, uri)
                    com.celestial.latent.develop.DoubleExposure.setPending(context, null)
                    pendingFirst = null; ghost = null; lastSingleRaw = null
                    DevelopQueue.submit(context, DevelopQueue.Job(
                        uri,
                        shotRecipe,
                        isRaw = true, first = held,
                    ))
                } else {
                    if (single) lastSingleRaw = uri
                    if (liveSettings.autoDevelop && single) {
                        DevelopQueue.submit(context, DevelopQueue.Job(
                            uri,
                            // The camera's exposure is the exposure: the film does not re-level it.
                            shotRecipe,
                            isRaw = true,
                        ))
                    }
                }
            },
        ).also {
            pushSessionSettings(it, settings)
            // 85a: the first picture of a new session ends a mode switch's freeze-frame
            it.onFirstFrame = { ModeSwitch.firstFrame() }
        }
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

    // 86b: HDR is the user's bracket; until both ends are set the shutter opens the card instead
    fun fireMulti() {
        if (settings.focusStack) controller.captureBurst(FOCUS_FRAMES, focus = true)
        else if (settings.hdrStack) bracketPlan(settings)?.let { controller.captureBurst(bracket = it) } ?: run { bracketOpen = true }
        else controller.captureBurst(16)
    }
    // 87c: the iris closes over the viewfinder as each shot is taken
    val iris = remember { IrisState() }
    val motionScope = rememberCoroutineScope()
    fun shoot(single: Boolean) {
        if (!single && settings.hdrStack && bracketPlan(settings) == null) { Haptics.tick(context); bracketOpen = true; return }
        Haptics.heavy(context)
        val fire = { iris.fire(motionScope); if (single) controller.captureSingle() else fireMulti() }
        if (settings.timerSeconds > 0) countdown = settings.timerSeconds else fire()
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown -= 1; if (countdown == 0) { iris.fire(motionScope); if (settings.focusStack || settings.hdrStack || settings.burstMode) fireMulti() else controller.captureSingle() } }
    }

    DisposableEffect(Unit) {
        ShutterBus.onShutter = { if (settings.volumeShutter) shoot(single = !settings.burstMode && !settings.focusStack && !settings.hdrStack) }
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
    LaunchedEffect(settings.preset, settings.filmPreview, glPreview != null,
                   settings.cinema, settings.cinemaFilm, settings.cinemaPaper, settings.cinema85b) {
        val view = glPreview
        if (!settings.filmPreview || view == null) return@LaunchedEffect
        // the 85B, seen through the viewfinder as it will be on the film
        val t = if (settings.cinema && settings.cinema85b && Cinema.tungsten(settings.cinemaFilm)) Cinema.PREVIEW_TINT_85B else floatArrayOf(1f, 1f, 1f)
        view.setTint(t[0], t[1], t[2])
        baking = true
        withContext(Dispatchers.Default) {
            val recipe = com.celestial.latent.develop.Recipes.current(context)
                .copy(autoExposure = settings.engineAutoExposure)
                .let { if (settings.cinema) Cinema.recipe(it, settings) else it }
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
    // 85a: they are also pushed once when the controller is made (pushSessionSettings below), before the
    // first open, so it no longer opens with defaults and then again with these.
    LaunchedEffect(settings.cameraPath, settings.opmode, settings.opmodeLens, settings.vendorTags, settings.saveJpeg, settings.inSensorZoomJpeg, settings.teleZoomDirect, settings.dcgTest, settings.fastShot) {
        pushSessionSettings(controller, settings)
        controller.syncSession()
    }
    DisposableEffect(Unit) {
        DevelopQueue.onChanged = { developing = DevelopQueue.queued }
        DevelopQueue.onDeveloped = { uri -> loadThumb(uri) }
        onDispose { DevelopQueue.onChanged = {}; DevelopQueue.onDeveloped = {} }
    }
    LaunchedEffect(Unit) {
        controller.onVendorEcho = onVendorEcho; controller.onBurstFinished = { Haptics.click(context) }
        // A burst roll is not in the gallery, so its thumbnail is the sharpest frame's preview from the roll.
        controller.onRollSaved = { _, jpeg -> jpeg?.let { j -> android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size)?.let { thumb = it } } }
        onController(controller)
    }
    LaunchedEffect(settings.haptics) { Haptics.enabled = settings.haptics }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(status) {
        val s = status.lowercase()
        if (s.contains("fail") || s.contains("error") || s.contains("unavailable") || s.contains("restarting")) { toast = status; delay(3500); toast = "" }
    }

    // Session-affecting toggles need a rebuild: reopen the same lens.
    fun reopen() { surfaceRef?.let { surf -> controller.open(lens, surf) } }

    // 79a Fast shot: a focal length is a lens (switched to if need be), maybe its in-sensor ×2, a crop
    // recorded with each shot, and the snap focus. Your shutter survives the change; ISO goes back to auto.
    fun goFocal(f: Int, snap: Float = settings.fastSnap) {
        val plan = FastShot.plan(f) ?: return
        controller.fastCrop = plan.crop; controller.fastFocal = f
        val c = controls.copy(iso = null, zoom = plan.zoom, focusDiopters = FastShot.snapDiopters(snap), locked = false)
        if (plan.lens != lens) {
            lens = plan.lens; controls = c
            val surf = surfaceRef
            if (surf != null) controller.openWith(plan.lens, surf, c) else controller.setControls(c)
            onLensChanged(plan.lens)
        } else push(c)
    }
    var wasFast by remember { mutableStateOf(false) }
    LaunchedEffect(settings.fastShot) {
        if (settings.fastShot) {
            val f = settings.fastFocal.takeIf { FastShot.plan(it) != null } ?: FastShot.available().firstOrNull()
            if (f != null) goFocal(f)
        } else if (wasFast) {
            // back to the camera as it was: the lens's own view, autofocus
            controller.fastCrop = 1f; controller.fastFocal = 0
            push(controls.copy(zoom = 1f, focusDiopters = null))
        }
        wasFast = settings.fastShot
    }

    // 87a: the dock at the bottom keeps clear of the navigation bar itself, so its colour runs to the screen's edge
    Column(Modifier.fillMaxSize().background(LatentColors.Background).glow().statusBarsPadding()) {

        // ---- Top bar: wordmark · chevron · gear ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CELESTIAL DARKROOM", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (drawerOpen) "︿" else "﹀", color = if (drawerOpen) LatentColors.Amber else LatentColors.Text, fontSize = 18.sp,
                    modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); drawerOpen = !drawerOpen }).padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.width(8.dp))
                Text("⚙", color = LatentColors.Text, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = onOpenSettings).padding(4.dp))
            }
        }

        // ---- Viewfinder ----
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(OnPhoto.Surface)
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
                        plainPreview = this
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
            // 85a: coming from Night/Auto, their last picture, blurred, until this camera's first frame
            FreezeFrame()
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
            // Cinema: the widescreen frame. The picture's long edge runs down the viewfinder, and the
            // crop keeps it and trims the sides — held sideways, it reads as a wide frame.
            if (settings.cinema) {
                val a = settings.cinemaAspect
                Canvas(Modifier.fillMaxSize()) {
                    val band = minOf(size.width, size.height / a)
                    val x0 = (size.width - band) / 2f
                    val shade = Color(0xA6000000)
                    drawRect(shade, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(x0, size.height))
                    drawRect(shade, androidx.compose.ui.geometry.Offset(x0 + band, 0f), androidx.compose.ui.geometry.Size(x0, size.height))
                    val line = Color(0x99FFFFFF)
                    drawLine(line, androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Offset(x0, size.height), 1.5f)
                    drawLine(line, androidx.compose.ui.geometry.Offset(x0 + band, 0f), androidx.compose.ui.geometry.Offset(x0 + band, size.height), 1.5f)
                }
            }
            // 79a Fast shot: the focal length's frame, rangefinder style: thin corner lines, the view outside
            // dimmed, so what is about to walk in is already in sight.
            if (settings.fastShot) FastShot.plan(settings.fastFocal)?.let { plan ->
                Canvas(Modifier.fillMaxSize()) {
                    val fw = size.width / plan.crop; val fh = size.height / plan.crop
                    val x0 = (size.width - fw) / 2f; val y0 = (size.height - fh) / 2f
                    if (plan.crop > 1.001f) {
                        val shade = Color(0x8C000000)
                        drawRect(shade, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, y0))
                        drawRect(shade, Offset(0f, y0 + fh), androidx.compose.ui.geometry.Size(size.width, size.height - y0 - fh))
                        drawRect(shade, Offset(0f, y0), androidx.compose.ui.geometry.Size(x0, fh))
                        drawRect(shade, Offset(x0 + fw, y0), androidx.compose.ui.geometry.Size(size.width - x0 - fw, fh))
                    }
                    val line = Color(0xEEF1EFE8); val arm = 22.dp.toPx(); val sw = 1.5.dp.toPx()
                    val inset = if (plan.crop > 1.001f) 0f else 6.dp.toPx()
                    val l = x0 + inset; val t = y0 + inset; val r = x0 + fw - inset; val b = y0 + fh - inset
                    for ((cx, cy, dx, dy) in listOf(listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f))) {
                        drawLine(line, Offset(cx, cy), Offset(cx + dx * arm, cy), sw)
                        drawLine(line, Offset(cx, cy), Offset(cx, cy + dy * arm), sw)
                    }
                }
            }
            // Double exposure, top right: hold the last frame, or show that one is held (× winds on).
            val heldFrame = pendingFirst
            if (heldFrame != null || lastSingleRaw != null) {
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        if (heldFrame != null) "2ND EXPOSURE  ×" else "DON'T ADVANCE",
                        color = if (heldFrame != null) OnPhoto.AmberInk else OnPhoto.TextBright,
                        fontSize = 10.sp, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (heldFrame != null) OnPhoto.Amber else Color(0x99000000))
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
                    if (heldFrame != null) Text("frame the second over the first", color = OnPhoto.TextBright, fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            // Quiet captions overlaid on the image.
            // 77a: the film cards can be folded into one pill; the lens row and its labels follow them down
            val filmsLift by androidx.compose.animation.core.animateDpAsState(if (filmsFolded) 0.dp else 32.dp, label = "films")
            // 76a: each on a small see-through dark pill, so it reads over a bright scene as well as a dark one
            Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val fastPlan = if (settings.fastShot) FastShot.plan(settings.fastFocal) else null
                Text(if (fastPlan != null) "RAW · ${fastPlan.focal} MM · ${String.format(java.util.Locale.US, "%.1f", fastPlan.megapixels())}M"
                    else if (settings.saveJpeg) "RAW + JPG · 12.5M" else "RAW · 12.5M", color = OnPhoto.TextBright, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.onPicture())
                if (settings.cinema) Text(Cinema.label(settings), color = OnPhoto.Amber, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.onPicture())
                // Says plainly what the live image is and is not, without crowding the frame.
                if (settings.filmPreview) Text(
                    (com.celestial.latent.develop.Presets.byId(context, settings.preset)?.name?.substringBefore(" — ")
                        ?: Develop.FILMS.firstOrNull { it.first == settings.film }?.second ?: "FILM").uppercase() +
                        when {
                            baking -> " · PREPARING"
                            lookReady -> " · COLOUR ONLY"
                            else -> " · PLAIN IMAGE"
                        },
                    color = if (lookReady) OnPhoto.Text else OnPhoto.TextDim,
                    fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.onPicture(),
                )
            }
            val modes = listOfNotNull(
                if (settings.fastShot) "FAST SHOT" else null,
                if (settings.inSensorZoomJpeg && Lenses.isXiaomi15Ultra && controls.zoom > 1.001f && !settings.fastShot) "ISZ" else null,
                if (settings.dcgTest && !settings.fastShot && Lenses.isXiaomi15Ultra && lens.physicalId == Lenses.MAIN_ID && controls.zoom <= 1.001f) "DCG" else null,
                if (settings.burstMode) "BURST" else null, if (settings.focusStack) "FOCUS STACK" else null, if (settings.hdrStack) (bracketPlan(settings)?.let { "HDR · ${it.size}" } ?: "HDR BRACKET") else null, if (settings.timerSeconds > 0) "${settings.timerSeconds}S" else null,
            )
            val tagLabels = remember(settings.vendorTags, lens) { if (controller.hasCharacteristics) controller.activeTagLabels() else emptyList() }
            val allLabels = modes + tagLabels
            if (allLabels.isNotEmpty()) Text(allLabels.joinToString(" · "), color = OnPhoto.Amber, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).onPicture())
            if (controls.locked) Text("AE/AF LOCK", color = OnPhoto.AmberInk, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(OnPhoto.Amber).padding(horizontal = 10.dp, vertical = 4.dp))
            if (countdown > 0) Text("$countdown", color = OnPhoto.TextBright, fontSize = 64.sp, modifier = Modifier.align(Alignment.Center))
            Iris(iris)
            Text(if (settings.fastShot) "${settings.fastFocal} MM FROM ${lens.name.uppercase()}" + (if (readout.fast.isNotEmpty()) " · ${readout.fast}" else "")
                else (if (lens.mm >= 70) "TELE" else lens.name.uppercase()) + " · ${lens.mm} MM" + (if (readout.afState.isNotEmpty()) " · AF ${readout.afState.uppercase()}" else ""),
                color = OnPhoto.TextBright, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 100.dp + filmsLift).onPicture())
            val zoomNote = when {
                settings.fastShot -> ""
                controls.zoom == 4f -> "×4 ON"
                controls.zoom == 2f -> "×2 ON"
                else -> ""
            }
            if (zoomNote.isNotEmpty()) Text(zoomNote, color = OnPhoto.TextBright, fontSize = 10.sp, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 100.dp + filmsLift).onPicture())
            // ×4 reads the sensor's tiny pixels one by one: noisy in dim light, and a burst cleans it up.
            // Its own line, centred above the labels — beside them it would run into the lens label.
            if (controls.zoom == 4f && !settings.fastShot) Text("best in bright light · hold the shutter for a burst", color = OnPhoto.Amber, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp + filmsLift).onPicture())
            // Lens row floating on the image: plain numbers, active one larger; ×2 multiplies the current lens.
            // 76a: on one see-through dark pill, the chosen lens in a light circle, so it reads over any scene
            if (settings.fastShot) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp + filmsLift).clip(RoundedCornerShape(999.dp)).background(PICTURE_PILL)
                .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // 79a: the focal lengths instead of the lenses, the chosen one in a light circle, then snap focus
                FastShot.available().forEach { f ->
                    val on = f == settings.fastFocal
                    Text("$f", color = if (on) OnPhoto.Background else OnPhoto.TextBright, fontSize = if (on) 13.sp else 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.then(if (on) Modifier.clip(CircleShape).background(OnPhoto.TextBright).size(36.dp).wrapContentHeight(Alignment.CenterVertically) else Modifier)
                            .combinedClickable(onClick = {
                                if (!on) { Haptics.tick(context); onSettingsChange(settings.copy(fastFocal = f)); goFocal(f) }
                            }).then(if (on) Modifier else Modifier.padding(horizontal = 8.dp, vertical = 6.dp)))
                }
                Spacer(Modifier.width(6.dp))
                val snapOn = controls.focusDiopters != null
                val snapD = FastShot.snapDiopters(settings.fastSnap)
                // the snap distance, or, after the focus slider moved it, where it really is
                Text(if (!snapOn) "AF" else if (snapD != null && kotlin.math.abs(controls.focusDiopters!! - snapD) < 0.001f) FastShot.snapLabel(settings.fastSnap) else focusLabel(controls.focusDiopters!!), color = if (snapOn) OnPhoto.AmberInk else OnPhoto.Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(999.dp)).background(if (snapOn) OnPhoto.Amber else Color.Transparent).border(0.5.dp, OnPhoto.Amber, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = {
                            Haptics.tick(context)
                            // from autofocus (or a tap that went back to it) the cycle starts at 1 m
                            val next = if (!snapOn) FastShot.SNAPS[1] else FastShot.nextSnap(settings.fastSnap)
                            onSettingsChange(settings.copy(fastSnap = next))
                            push(controls.copy(focusDiopters = FastShot.snapDiopters(next)))
                        }).padding(horizontal = 9.dp, vertical = 3.dp))
            } else Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp + filmsLift).clip(RoundedCornerShape(999.dp)).background(PICTURE_PILL)
                .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Lenses.ALL.forEach { l ->
                    val on = l == lens
                    Text(if (on) l.label + "×" else l.label, color = if (on) OnPhoto.Background else OnPhoto.TextBright, fontSize = if (on) 14.sp else 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.then(if (on) Modifier.clip(CircleShape).background(OnPhoto.TextBright).size(38.dp).wrapContentHeight(Alignment.CenterVertically) else Modifier)
                            .combinedClickable(onClick = {
                            // ×4 exists only on the periscope: leaving it turns ×4 off rather than becoming a digital ×4
                            if (l != lens) { Haptics.tick(context); lens = l; push(controls.copy(shutterNs = null, iso = null, focusDiopters = null, zoom = if (controls.zoom > 2.5f) 1f else controls.zoom)); onLensChanged(l); reopen() }
                        }).then(if (on) Modifier else Modifier.padding(horizontal = 11.dp, vertical = 6.dp)))
                }
                Spacer(Modifier.width(6.dp))
                // ×2 multiplies the current lens. Where the sensor has its own crop (the 15 Ultra's
                // main and periscope) it is that, and the periscope adds ×4: each button shows the
                // real zoom it gives when on (2×, 8.4×, 16×). Elsewhere ×2 is as it always was.
                val steps = com.celestial.latent.camera.Lenses.sensorZoomSteps(lens)
                (if (4 in steps) listOf(2f, 4f) else listOf(2f)).forEach { factor ->
                    val zoomOn = controls.zoom == factor
                    val effective = if (steps.isNotEmpty()) com.celestial.latent.camera.Lenses.effectiveLabel(lens, factor)
                        else String.format("%.1f", lens.label.toFloat() * factor).removeSuffix(".0")
                    Text(if (zoomOn) "$effective×" else "×${factor.toInt()}", color = if (zoomOn) OnPhoto.AmberInk else OnPhoto.Amber, fontSize = 11.sp,
                        modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(999.dp)).background(if (zoomOn) OnPhoto.Amber else Color.Transparent).border(0.5.dp, OnPhoto.Amber, RoundedCornerShape(999.dp))
                            .combinedClickable(onClick = { Haptics.tick(context); push(controls.copy(zoom = if (zoomOn) 1f else factor)) }).padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
            FilmStrip(settings.preset, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                onLongPress = { lastRawUri?.let { u -> onOpenDarkroom(u, true) } },
                folded = filmsFolded,
                onFold = { filmsFolded = it; runCatching { uiPrefs.edit().putBoolean("films_folded", it).apply() } }) { id ->
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
            // 86b: the bracket card, above the lens row; the strip below stays free for dialling S and ISO
            if (bracketOpen && settings.hdrStack) BracketCard(settings, onSettingsChange,
                current = {
                    val ns = controls.shutterNs ?: readout.shutterNs; val iso = controls.iso ?: readout.iso
                    if (ns > 0 && iso > 0) ns to iso else null
                },
                onClose = { bracketOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp + filmsLift))
            if (toast.isNotEmpty()) Text(toast, color = OnPhoto.TextBright, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(8.dp)).background(OnPhoto.Background.copy(alpha = 0.8f)).padding(12.dp))
            // Quick-settings drawer over the lower part of the viewfinder.
            if (drawerOpen) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).floating(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(LatentColors.Surface.copy(alpha = 0.93f)).padding(8.dp)) {
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
                        Tile("All settings", false, Modifier.weight(1f)) { drawerOpen = false; onOpenSettings() }
                    }
                    // DCG: the main lens's cleaner-shadow sensor mode (Xiaomi 15 Ultra only).
                    // 84a: Fast shot and Cinema are modes in the row under the picture now, not tiles here.
                    if (Lenses.isXiaomi15Ultra) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tile("DCG · main lens" + if (settings.fastShot) "\n(off in fast shot)" else "", settings.dcgTest, Modifier.weight(1f)) { onSettingsChange(settings.copy(dcgTest = it)) }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    // Cinema: a motion-picture film and print, a Super 35 frame, a widescreen crop.
                    if (settings.cinema) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tile("Film-still bars", settings.cinemaBars, Modifier.weight(1f)) { onSettingsChange(settings.copy(cinemaBars = it)) }
                            // the 85B is only for tungsten film, so it is only offered with one
                            if (Cinema.tungsten(settings.cinemaFilm))
                                Tile("85B filter\n(daylight)", settings.cinema85b, Modifier.weight(1f)) { onSettingsChange(settings.copy(cinema85b = it)) }
                            else Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Cinema.FILMS.forEach { (id, label) ->
                                Tile("Vision3\n$label", settings.cinemaFilm == id, Modifier.weight(1f)) { onSettingsChange(settings.copy(cinemaFilm = id)) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Cinema.PRINTS.forEach { (id, label) ->
                                Tile("Print\n$label", settings.cinemaPaper == id, Modifier.weight(1f)) { onSettingsChange(settings.copy(cinemaPaper = id)) }
                            }
                            Cinema.ASPECTS.forEach { (a, label) ->
                                Tile(label, kotlin.math.abs(settings.cinemaAspect - a) < 0.01f, Modifier.weight(1f)) { onSettingsChange(settings.copy(cinemaAspect = a)) }
                            }
                        }
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
        // 87a: the settings row and its slider on their own layer (a raised white tile in Clay)
        Column(Modifier.fillMaxWidth().controlBar()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            StripCell("EV", evStr, false, selected == Cell.EV, controls.manualExposure, Modifier.weight(1f)) { selected = if (selected == Cell.EV) null else Cell.EV }
            // 79a: in fast shot an untouched shutter is the motion's choice, not the phone's
            val fastAuto = settings.fastShot && controls.shutterNs == null && readout.fast.startsWith("MOTION")
            StripCell(if (fastAuto) "S · MOTION" else "S", sStr, controls.shutterNs == null && !fastAuto, selected == Cell.S, false, Modifier.weight(1f)) { selected = if (selected == Cell.S) null else Cell.S }
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
                    // 87c: shutter and ISO on the click-stop dial
                    ClickDial(remember(presets) { presets.map { ControlMath.shutterLabel(it) } }, idx, { push(controls.copy(shutterNs = presets[it])) }, { push(controls.copy(shutterNs = null)) }, "A") }
                Cell.ISO -> { val presets = if (controller.hasCharacteristics) ControlMath.isoPresets(controller.isoRange.lower, controller.isoRange.upper) else listOf(100)
                    val cur = controls.iso ?: readout.iso; val idx = presets.indices.minByOrNull { Math.abs(presets[it] - cur) } ?: 0
                    ClickDial(remember(presets) { presets.map { it.toString() } }, idx, { push(controls.copy(iso = presets[it])) }, { push(controls.copy(iso = null)) }, "A") }
                Cell.WB -> { val cur = controls.kelvin ?: (if (kelvinShown > 0) kelvinShown else 5200)
                    StripSlider(cur.toFloat(), 2000f, 10000f, 79, { push(controls.copy(kelvin = Math.round(it / 100f) * 100)) }, { push(controls.copy(kelvin = null)) }, "A") }
                Cell.F -> { val maxD = if (controller.hasCharacteristics) controller.minFocusDiopters else 10f
                    StripSlider((controls.focusDiopters ?: readout.focusDiopters).coerceIn(0f, maxD), 0f, maxD, 0, { push(controls.copy(focusDiopters = it)) }, { push(controls.copy(focusDiopters = null)) }, "AF") }
                // 84a: with no slider open, the modes sit here (a slider takes the spot while it is open)
                null -> ModeRow(CameraModes.of(settings), { m ->
                    // 85a: the picture stays, blurred, while Xiaomi's camera starts
                    if (m == CameraModes.NIGHT || m == CameraModes.AUTO) ModeSwitch.begin(if (settings.filmPreview) glPreview else plainPreview, "${CameraModes.of(settings).uppercase()} → ${m.uppercase()}") { CameraModes.setXiaomi(context, m); onOpenExtension() }
                    else onSettingsChange(CameraModes.apply(settings, m))
                })
            }
        }
        }

        // ---- Shutter row: thumbnail · shutter · burst toggle ----
        val multi = settings.burstMode || settings.focusStack || settings.hdrStack
        // 87a: the dock, lifted above the settings row in Midnight and Clay
        Column(Modifier.fillMaxWidth().weight(1f).shutterDock()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).floating(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(LatentColors.Surface)
                .combinedClickable(onClick = { Haptics.tick(context); onOpenRoll() })) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                // 87c: the count rolls like a film counter as each shot joins the queue
                if (developing > 0) Box(Modifier.align(Alignment.TopEnd).clip(CircleShape).accentFill().padding(horizontal = 5.dp, vertical = 1.dp)) {
                    androidx.compose.animation.AnimatedContent(developing, transitionSpec = {
                        val up = targetState > initialState
                        (androidx.compose.animation.slideInVertically { h -> if (up) h else -h } togetherWith androidx.compose.animation.slideOutVertically { h -> if (up) -h else h })
                            .using(androidx.compose.animation.SizeTransform(clip = true))
                    }, label = "counter") { n -> Text("$n", color = LatentColors.AmberInk, fontSize = 9.sp) }
                }
            }
            // 87c: the button sinks a little under the finger and springs back
            val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val sink by androidx.compose.animation.core.animateFloatAsState(if (pressed && motionOn()) 0.92f else 1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 900f), label = "sink")
            Box(
                Modifier.size(78.dp).graphicsLayer { scaleX = sink; scaleY = sink }.shutterRing(CircleShape)
                    .combinedClickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = { shoot(single = !multi) }, onLongClick = { shoot(single = multi) }),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(62.dp).clip(CircleShape).accentFill(multi, shutterFace())) }
            // Tap cycles: off → 16 (burst) → FS (focus stack, 69a) → HDR (bracket, 86b) → off.
            // Hold: HDR with its bracket card open (86b).
            Box(Modifier.size(46.dp).then(if (LatentColors.palette.depth) Modifier.floating(CircleShape, 5.dp).background(LatentColors.Surface, CircleShape) else Modifier)
                .clip(CircleShape).border(if (settings.hdrStack) 1.dp else 0.5.dp, if (multi) LatentColors.Amber else LatentColors.Line, CircleShape)
                .combinedClickable(onClick = {
                    Haptics.tick(context)
                    val next = when {
                        settings.hdrStack -> settings.copy(burstMode = false, focusStack = false, hdrStack = false)
                        settings.focusStack -> settings.copy(burstMode = false, focusStack = false, hdrStack = true)
                        settings.burstMode -> settings.copy(burstMode = false, focusStack = true, hdrStack = false)
                        else -> settings.copy(burstMode = true, focusStack = false, hdrStack = false)
                    }
                    bracketOpen = next.hdrStack && bracketPlan(next) == null
                    onSettingsChange(next)
                }, onLongClick = {
                    Haptics.tick(context)
                    if (!settings.hdrStack) onSettingsChange(settings.copy(burstMode = false, focusStack = false, hdrStack = true))
                    bracketOpen = true
                }), contentAlignment = Alignment.Center) {
                Text(if (settings.hdrStack) "HDR" else if (settings.focusStack) "FS" else "16", color = if (multi) LatentColors.Amber else LatentColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Light)
            }
        }
        val filmLabel = Develop.FILMS.firstOrNull { it.first == settings.film }?.second?.uppercase() ?: "FILM"
        Text(filmLabel + (if (developing > 0) " · DEVELOPING $developing" else if (settings.focusStack) " · FOCUS STACK" else if (settings.hdrStack) " · HDR BRACKET" else if (settings.burstMode) " · BURST" else ""), color = dockLabel(), fontSize = 9.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** 87a: the layer under the settings row: full width in the dark themes, a raised tile in Clay. */
internal fun Modifier.controlBar(): Modifier =
    if (LatentColors.Light) padding(horizontal = 10.dp, vertical = 6.dp).floating(RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(LatentColors.Bar)
    else background(LatentColors.Bar)

/** 87a: the shutter dock: its own colour down to the screen's edge, lifted (shadow cast up, lit edge). */
internal fun Modifier.shutterDock(): Modifier = background(LatentColors.Dock).liftedTop().navigationBarsPadding()

/** 87a: Clay's shutter is a white face in a blue ring (ui/Theme.kt shutterRing); the dark themes keep the light face. */
internal fun shutterFace() = if (LatentColors.Light) LatentColors.Surface else if (LatentColors.palette.depth) LatentColors.Amber else LatentColors.TextBright
/** 87a: the film line under the shutter; on Midnight's lighter dock the outline tone was too faint to read. */
internal fun dockLabel() = if (LatentColors.palette.depth) LatentColors.TextDim else LatentColors.Line

@Composable
private fun Tag(t: String) {
    Text(t, color = LatentColors.AmberInk, fontSize = 9.sp, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill().padding(horizontal = 6.dp, vertical = 2.dp))
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
        modifier.height(52.dp).clip(RoundedCornerShape(8.dp)).accentFill(on, LatentColors.Line).combinedClickable(onClick = { Haptics.tick(ctx); onToggle(!on) }).padding(4.dp),
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
        Text(autoLabel, color = LatentColors.AmberInk, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill().combinedClickable(onClick = onAuto).padding(horizontal = 10.dp, vertical = 4.dp))
        Spacer(Modifier.width(12.dp))
        Slider(value = value.coerceIn(min, max), onValueChange = onChangeHaptic, valueRange = min..max, steps = steps, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.SliderFill, inactiveTrackColor = LatentColors.SliderTrack), modifier = Modifier.weight(1f))
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
        Box(Modifier.offset { IntOffset((p.x - boxPx / 2).toInt(), (p.y - boxPx / 2).toInt()) }.size(72.dp).border(1.dp, OnPhoto.Amber, RoundedCornerShape(4.dp)))
        // Slider track to the right of the box (or left if near the edge)
        val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        val onRight = p.x + boxPx / 2 + with(density) { 40.dp.toPx() } < screenW
        val tx = if (onRight) p.x + boxPx / 2 + with(density) { 14.dp.toPx() } else p.x - boxPx / 2 - with(density) { 14.dp.toPx() }
        val frac = (evIndex - evRange.first).toFloat() / (evRange.last - evRange.first).coerceAtLeast(1)
        Box(Modifier.offset { IntOffset(tx.toInt(), (p.y - trackPx / 2).toInt()) }.size(2.dp, 120.dp).background(OnPhoto.Line))
        Box(Modifier.offset { IntOffset((tx - with(density) { 5.dp.toPx() }).toInt(), (p.y + trackPx / 2 - frac * trackPx - with(density) { 6.dp.toPx() }).toInt()) }.size(12.dp).clip(CircleShape).background(OnPhoto.Amber))
        Text(String.format("%+.1f", evIndex / 6.0).replace("+0.0", "0.0"), color = OnPhoto.Amber, fontSize = 11.sp,
            modifier = Modifier.offset { IntOffset((tx + with(density) { 12.dp.toPx() }).toInt(), (p.y - trackPx / 2 - with(density) { 18.dp.toPx() }).toInt()) })
    }
}

/** Frames in a focus stack (69a): about 2.5 dioptres of depth on the main lens, e.g. 16-27 cm when focused at 20 cm. */
private const val FOCUS_FRAMES = 12

/** A see-through dark backing for words drawn over the live picture (76a). */
internal val PICTURE_PILL = Color(0x73000000)

/** Words over the live picture sit on a small dark pill, readable over a bright scene as well as a dark one (76a). */
private fun Modifier.onPicture(): Modifier =
    clip(RoundedCornerShape(999.dp)).background(PICTURE_PILL).padding(horizontal = 8.dp, vertical = 3.dp)

/** The settings that shape the camera session (85a: pushed before the first open as well as on change). */
private fun pushSessionSettings(controller: CameraController, settings: AppSettings) {
    controller.fastShot = settings.fastShot
    controller.cameraPath = settings.cameraPath
    controller.opmode = settings.opmode
    controller.vendorTags = settings.vendorTags.map { CameraController.VendorTagSpec(it.name, it.scope, it.type, it.value, it.lens) }
    controller.opmodeLens = settings.opmodeLens
    controller.saveJpeg = settings.saveJpeg
    controller.inSensorZoomJpeg = settings.inSensorZoomJpeg
    controller.teleZoomDirect = settings.teleZoomDirect
    controller.dcgTest = settings.dcgTest
}
