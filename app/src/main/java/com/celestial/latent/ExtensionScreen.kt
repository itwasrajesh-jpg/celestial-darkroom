@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.celestial.latent.ui.LatentColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

private fun extName(ext: Int) = when (ext) {
    CameraExtensionCharacteristics.EXTENSION_BOKEH -> "PORTRAIT"
    CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
    else -> "AUTO"
}

/** Minimal Camera Extensions client: Xiaomi's own BOKEH / NIGHT / AUTOMATIC processing, JPEG out. API 31+. */
@RequiresApi(31)
class ExtensionCamera(private val context: android.content.Context, private val onStatus: (String) -> Unit) {
    private val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-ext").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }
    private var device: CameraDevice? = null
    private var session: CameraExtensionSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    var extension = CameraExtensionCharacteristics.EXTENSION_BOKEH
    private val cameraId = "0"
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile var zoom = 1f
    var onZoomSupport: (Boolean) -> Unit = {}
    private val reopenToken = Any()

    /** What this extension lets us set. Filled on open, reported via onCaps. */
    data class Caps(val keys: List<String>, val zoom: Boolean, val ev: Boolean, val manual: Boolean, val afRegions: Boolean, val isz: Boolean, val evRange: android.util.Range<Int>?, val expRange: android.util.Range<Long>?, val isoRange: android.util.Range<Int>?)
    var caps: Caps? = null
    var onCaps: (Caps) -> Unit = {}
    var onSaved: (android.net.Uri) -> Unit = {}
    @Volatile var evIndex = 0
    @Volatile var shutterNs: Long? = null
    @Volatile var iso: Int? = null
    @Volatile var isz = false
    private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null

    private fun discover(): Caps {
        val names = if (Build.VERSION.SDK_INT >= 33) runCatching { cm.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(extension).map { it.name } }.getOrDefault(emptyList()) else emptyList()
        val ch = cm.getCameraCharacteristics(cameraId)
        val c = Caps(
            keys = names,
            zoom = names.contains(CaptureRequest.CONTROL_ZOOM_RATIO.name),
            ev = names.contains(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION.name),
            // Xiaomi's extensions list the manual keys but skip white balance when AE is off (green cast) — manual stays off in all of them.
            manual = false,
            afRegions = names.contains(CaptureRequest.CONTROL_AF_REGIONS.name),
            isz = names.contains("org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom"),
            evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
        )
        android.util.Log.i("Latent", "extension ${extName(extension)} accepts ${names.size} keys: ${names.joinToString()}")
        caps = c; onCaps(c)
        return c
    }

    /** Applies whatever this extension allows to a request. */
    private fun applyAllowed(b: CaptureRequest.Builder) {
        val c = caps ?: return
        if (c.zoom && zoom != 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        if (c.ev && !(c.manual && shutterNs != null)) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        if (c.manual && (shutterNs != null || iso != null)) {
            b.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
            shutterNs?.let { b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            iso?.let { b.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        }
        if (c.afRegions) afRegion?.let { r -> b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r)); b.set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO); b.set(CaptureRequest.CONTROL_AF_TRIGGER, android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START) }
        if (c.isz && isz) try { b.set(CaptureRequest.Key("org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom", Int::class.javaObjectType), 1) } catch (_: Throwable) {}
    }

    fun refresh() = handler.post {
        val s = session ?: return@post; val dev = device ?: return@post; val surf = previewSurface ?: return@post
        try { s.setRepeatingRequest(dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf); applyAllowed(this) }.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {}) } catch (e: Exception) { onStatus("update: ${e.message}") }
        afRegion = null
    }

    fun tapFocus(u: Float, v: Float) {
        val active = cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val sx = v.coerceIn(0f, 1f); val sy = (1f - u).coerceIn(0f, 1f); val half = 0.06f
        val l = ((sx - half) * active.width()).toInt().coerceIn(0, active.width() - 2); val t = ((sy - half) * active.height()).toInt().coerceIn(0, active.height() - 2)
        afRegion = android.hardware.camera2.params.MeteringRectangle(l, t, (2 * half * active.width()).toInt(), (2 * half * active.height()).toInt(), 999)
        refresh()
    }

    private fun zoomSupported(): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        cm.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(extension).any { it.name == CaptureRequest.CONTROL_ZOOM_RATIO.name }
    }.getOrDefault(false)

    fun previewSize(): android.util.Size {
        if (Build.VERSION.SDK_INT < 31) return android.util.Size(1440, 1080)
        val ec = cm.getCameraExtensionCharacteristics(cameraId)
        val sizes = ec.getExtensionSupportedSizes(extension, SurfaceTexture::class.java)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: sizes.firstOrNull() ?: android.util.Size(1440, 1080)
    }

    fun attach(st: SurfaceTexture) { surfaceTexture = st; openOnTexture() }

    /** Sizes the preview for the current extension, then opens. */
    private fun openOnTexture() {
        val st = surfaceTexture ?: return
        val size = previewSize()
        st.setDefaultBufferSize(size.width, size.height)
        open(Surface(st))
    }

    @SuppressLint("MissingPermission")
    fun open(surface: Surface) = handler.post {
        if (Build.VERSION.SDK_INT < 31) { onStatus("Extensions need Android 12+"); return@post }
        closeInternal()
        Thread.sleep(250) // let the previous extension session fully release before the next one
        previewSurface = surface
        onZoomSupport(zoomSupported())
        discover()
        try {
            val ec = cm.getCameraExtensionCharacteristics(cameraId)
            if (extension !in ec.supportedExtensions) { onStatus("Extension not supported: $extension"); return@post }
            val jpegSize = ec.getExtensionSupportedSizes(extension, ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height } ?: return@post
            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ rr ->
                    rr.acquireNextImage()?.let { img ->
                        try {
                            val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                            val name = "LATENT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + extName(extension) + ".jpg"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent"); put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            val resolver = context.contentResolver
                            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
                            onStatus("Saved $name (${bytes.size / 1024} KB, ${img.width}x${img.height})")
                            android.util.Log.i("Latent", "extension saved $name ${img.width}x${img.height} ${bytes.size / 1024} KB zoom=$zoom")
                            onSaved(uri)
                        } catch (e: Exception) { onStatus("save failed: ${e.message}") } finally { img.close() }
                    }
                }, handler)
            }
            onStatus("Opening camera 0 with ${extName(extension)}…")
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    // The camera can open after something already closed us (fast mode switch, pause,
                    // another app taking the camera): give it back instead of using a cleared reader.
                    val r = reader
                    if (r == null || !surface.isValid) { onStatus("camera opened after close; releasing"); cam.close(); device = null; return }
                    device = cam
                    val cfg = ExtensionSessionConfiguration(extension, listOf(OutputConfiguration(surface), OutputConfiguration(r.surface)), executor,
                        object : CameraExtensionSession.StateCallback() {
                            override fun onConfigured(s: CameraExtensionSession) {
                                // What this extension will honour. If a key is not in this
                                // list, setting it is silently ignored — so this is the direct
                                // answer to whether the in-sensor zoom can be engaged here.
                                if (Build.VERSION.SDK_INT >= 33) runCatching {
                                    val chars = cm.getCameraExtensionCharacteristics(cameraId)
                                    val reqKeys = chars.getAvailableCaptureRequestKeys(extension).map { it.name }
                                    val resKeys = chars.getAvailableCaptureResultKeys(extension).map { it.name }
                                    android.util.Log.i("Latent", "extension ${extName(extension)} accepts ${reqKeys.size} request keys: " + reqKeys.joinToString(", "))
                                    android.util.Log.i("Latent", "extension ${extName(extension)} reports ${resKeys.size} result keys: " + resKeys.joinToString(", "))
                                    val vendor = reqKeys.filter { it.contains("xiaomi", true) || it.contains("codeaurora", true) || it.contains("sensor", true) }
                                    android.util.Log.i("Latent", "extension vendor/sensor request keys: " + (vendor.ifEmpty { listOf("NONE — vendor tags are not honoured in this mode") }).joinToString(", "))
                                }.onFailure { android.util.Log.w("Latent", "could not list extension keys: ${it.message}") }
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface); applyAllowed(this) }
                                    s.setRepeatingRequest(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                                    onStatus("Ready · ${extName(extension)} · tap the shutter")
                                } catch (e: Exception) { onStatus("preview: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraExtensionSession) { onStatus("Extension session refused") }
                        })
                    try { cam.createExtensionSession(cfg) } catch (e: Exception) { onStatus("createExtensionSession: ${e.message}") }
                }
                override fun onDisconnected(cam: CameraDevice) { try { session?.close() } catch (_: Exception) {}; session = null; cam.close(); device = null }
                override fun onError(cam: CameraDevice, error: Int) { onStatus("camera error $error"); cam.close(); device = null }
            }, handler)
        } catch (e: Exception) { onStatus("open: ${e.message}") }
    }

    fun capture() = handler.post {
        val s = session ?: return@post; val dev = device ?: return@post; val r = reader ?: return@post
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(r.surface); set(CaptureRequest.JPEG_ORIENTATION, 90); set(CaptureRequest.JPEG_QUALITY, 100.toByte()); applyAllowed(this) }
            android.util.Log.i("Latent", "extension capture: ${extName(extension)} zoom=$zoom ev=$evIndex isz=$isz")
            s.capture(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(sess: CameraExtensionSession, request: CaptureRequest) { onStatus("capture failed") }
                override fun onCaptureProcessStarted(sess: CameraExtensionSession, request: CaptureRequest) { onStatus("Processing…") }
                // What was actually applied, as the driver reports it. Compare the crop region
                // and focal length here with a normal RAW-path ×2 shot: if the in-sensor mode
                // engaged, the sensor crop region shrinks; a digital crop leaves it full-size.
                override fun onCaptureResultAvailable(sess: CameraExtensionSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    runCatching {
                        val crop = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
                        val focal = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                        val zoomApplied = result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
                        val physical = runCatching { result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) }.getOrNull()
                        android.util.Log.i("Latent", "extension result: crop=$crop focal=$focal zoomApplied=$zoomApplied physical=$physical")
                        val vendorInResult = result.keys.map { it.name }.filter { it.contains("xiaomi", true) || it.contains("codeaurora", true) || it.contains("sensor", true) }
                        vendorInResult.forEach { name ->
                            val v = result.keys.firstOrNull { it.name == name }?.let { k -> runCatching { result.get(k) }.getOrNull() }
                            android.util.Log.i("Latent", "extension result vendor: $name = ${v?.let { if (it is IntArray) it.joinToString(",") else if (it is ByteArray) it.joinToString(",") else it.toString() }}")
                        }
                    }.onFailure { android.util.Log.w("Latent", "could not read extension result: ${it.message}") }
                }
            })
        } catch (e: Exception) { onStatus("capture: ${e.message}") }
    }

    fun switchTo(ext: Int) { handler.removeCallbacksAndMessages(reopenToken); extension = ext; openOnTexture() }
    /** After Android took the camera away (background / gallery), reopen on the same texture. */
    fun reopenIfNeeded() = handler.post { if (device == null && surfaceTexture != null) { android.util.Log.i("Latent", "extension: reopening after resume"); openOnTexture() } }
    fun applyZoom(z: Float) {
        zoom = z
        // Night honours a live zoom change; Auto/Portrait only read zoom when the session is created.
        if (extension == CameraExtensionCharacteristics.EXTENSION_NIGHT) { refresh(); return }
        // 83a: each reopen restarts Xiaomi's camera (~0.7 s). Fast taps replace the one still
        // waiting, so the camera restarts once, for the last lens tapped.
        handler.removeCallbacksAndMessages(reopenToken)
        handler.postAtTime({ openOnTexture() }, reopenToken, android.os.SystemClock.uptimeMillis() + 150)
    }
    // a lens change still waiting must not reopen the camera after we were closed (pause, screen gone)
    fun close() { handler.removeCallbacksAndMessages(reopenToken); handler.post { closeInternal() } }
    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}; session = null
        try { device?.close() } catch (_: Exception) {}; device = null
        reader?.close(); reader = null
    }
    fun destroy() { close(); handler.post { thread.quitSafely() } }
}

@RequiresApi(31)
@Composable
fun ExtensionScreen(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit, onOpenRoll: () -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting…") }
    // 84a: opens in the Xiaomi mode picked in the mode row (Night unless Auto was picked)
    val startMode = remember { if (CameraModes.xiaomi(context) == CameraModes.AUTO) CameraExtensionCharacteristics.EXTENSION_AUTOMATIC else CameraExtensionCharacteristics.EXTENSION_NIGHT }
    var mode by remember { mutableStateOf(startMode) }
    var zoomOk by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var caps by remember { mutableStateOf<ExtensionCamera.Caps?>(null) }
    var ev by remember { mutableStateOf(0) }
    var shutter by remember { mutableStateOf<Long?>(null) }
    var isoV by remember { mutableStateOf<Int?>(null) }
    var iszOn by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    var lastUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var toast by remember { mutableStateOf("") }
    var focusTap by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // 83a: the film cards fold here too, sharing the main screen's setting
    val uiPrefs = remember { context.getSharedPreferences("latent_camera_ui", android.content.Context.MODE_PRIVATE) }
    var filmsFolded by remember { mutableStateOf(runCatching { uiPrefs.getBoolean("films_folded", false) }.getOrDefault(false)) }
    val cam = remember {
        ExtensionCamera(context) { s -> status = s }.also {
            it.extension = startMode
            it.onZoomSupport = { ok -> zoomOk = ok }; it.onCaps = { c -> caps = c }
            it.onSaved = { uri ->
                Thread { val b = runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(192, 192), null) }.getOrNull(); if (b != null) { thumb = b; lastUri = uri } }.start()
                // Xiaomi's modes hand us a finished JPEG: film goes over their rendering, not over RAW.
                if (settings.autoDevelop) com.celestial.latent.develop.DevelopQueue.submit(context, com.celestial.latent.develop.DevelopQueue.Job(uri, com.celestial.latent.develop.Recipes.current(context), isRaw = false))
            }
        }
    }
    DisposableEffect(Unit) { onDispose { cam.destroy() } }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> cam.reopenIfNeeded()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> cam.close()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 84b: a new status cancels the old one's timer, so "Processing…" followed within 2.5 s by
    // "Saved …" (not a toast) used to stay up for good. Any other status now clears it.
    androidx.compose.runtime.LaunchedEffect(status) { val l = status.lowercase(); if (l.contains("fail") || l.contains("refused") || l.contains("error") || l.contains("processing")) { toast = status; kotlinx.coroutines.delay(2500); toast = "" } else toast = "" }
    fun resetManual() { ev = 0; shutter = null; isoV = null; cam.evIndex = 0; cam.shutterNs = null; cam.iso = null; selected = "" }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()) {
        // Top bar: back (84a: the modes moved to the row above the shutter)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = LatentColors.Text, fontSize = 22.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 6.dp))
            Spacer(Modifier.size(22.dp))
        }
        // Viewfinder with overlays
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(LatentColors.Surface).pointerInput(Unit) {
            detectTapGestures { pos ->
                val c = caps ?: return@detectTapGestures
                if (c.afRegions) cam.tapFocus(pos.x / size.width, pos.y / size.height)
                if (c.afRegions || c.ev) { Haptics.tick(context); focusTap = pos }
            }
        }) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { cam.attach(st) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { cam.close(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            })
            // 83a: the focus box sits under the lens row and film cards, as on the main screen.
            // Drawn last it covered them, and a lens tap became a new focus tap.
            caps?.let { c ->
                FocusEvOverlay(
                    point = focusTap, evIndex = ev,
                    evRange = (c.evRange?.let { it.lower..it.upper } ?: 0..0),
                    onEv = { n -> if (c.ev) { ev = n; cam.evIndex = n; cam.refresh() } },
                    onDismiss = { focusTap = null },
                )
            }
            Text("XIAOMI " + extName(mode) + " · JPG", color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            caps?.let { c -> Text(listOfNotNull(if (c.zoom) "ZOOM" else null, if (c.ev) "EV" else null, if (c.manual) "MANUAL" else null, if (c.afRegions) "TAP-AF" else null, if (c.isz) "ISZ" else null).ifEmpty { listOf("AUTO ONLY") }.joinToString(" · "),
                color = LatentColors.Amber, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) }
            // 83a: the main screen's lens row (76a): one see-through dark pill, the chosen lens in a light
            // circle, the phone's own lens list. Here a lens is a zoom ratio on Xiaomi's camera, and ×2
            // doubles it (no in-sensor ×4: these modes don't take that key). It follows the film cards down.
            val filmsLift by androidx.compose.animation.core.animateDpAsState(if (filmsFolded) 0.dp else 32.dp, label = "films")
            var lensSel by remember { mutableStateOf(com.celestial.latent.camera.Lenses.ALL.firstOrNull { it.isMain } ?: com.celestial.latent.camera.Lenses.ALL.first()) }
            var x2 by remember { mutableStateOf(false) }
            fun send() { zoom = lensSel.label.toFloat() * (if (x2) 2f else 1f); cam.applyZoom(zoom) }
            if (zoomOk) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp + filmsLift).clip(RoundedCornerShape(999.dp)).background(PICTURE_PILL)
                .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.celestial.latent.camera.Lenses.ALL.forEach { l ->
                    val on = l == lensSel
                    Text(if (on) l.label + "×" else l.label, color = if (on) LatentColors.Background else LatentColors.TextBright, fontSize = if (on) 14.sp else 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.then(if (on) Modifier.clip(CircleShape).background(LatentColors.TextBright).size(38.dp).wrapContentHeight(Alignment.CenterVertically) else Modifier)
                            .combinedClickable(onClick = { if (!on) { Haptics.tick(context); lensSel = l; send() } })
                            .then(if (on) Modifier else Modifier.padding(horizontal = 11.dp, vertical = 6.dp)))
                }
                Spacer(Modifier.width(6.dp))
                val z2 = lensSel.label.toFloat() * 2f
                val effective = if (z2 >= 10f) String.format(Locale.US, "%.0f", z2) else String.format(Locale.US, "%.1f", z2).removeSuffix(".0")
                Text(if (x2) "$effective×" else "×2", color = if (x2) LatentColors.AmberInk else LatentColors.Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(999.dp)).background(if (x2) LatentColors.Amber else androidx.compose.ui.graphics.Color.Transparent).border(0.5.dp, LatentColors.Amber, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = { Haptics.tick(context); x2 = !x2; send() }).padding(horizontal = 9.dp, vertical = 3.dp))
                if (caps?.isz == true) Text(if (iszOn) "ISZ" else "isz", color = if (iszOn) LatentColors.AmberInk else LatentColors.Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(999.dp)).background(if (iszOn) LatentColors.Amber else androidx.compose.ui.graphics.Color.Transparent).border(0.5.dp, LatentColors.Amber, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = { Haptics.tick(context); iszOn = !iszOn; cam.isz = iszOn; cam.refresh() }).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Text((if (zoomOk) "LENS VIA ZOOM" else "LENS BY XIAOMI") + " · FILM OVER JPEG", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 90.dp + filmsLift))
            FilmStrip(settings.preset, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                folded = filmsFolded,
                onFold = { filmsFolded = it; runCatching { uiPrefs.edit().putBoolean("films_folded", it).apply() } }) { id ->
                if (id.startsWith("stock:")) {
                    com.celestial.latent.develop.Recipes.load(context, id.removePrefix("stock:"))?.let { r ->
                        onSettingsChange(settings.copy(preset = id, film = r.film))
                        com.celestial.latent.develop.Recipes.setCurrent(context, r)
                    }
                    return@FilmStrip
                }
                com.celestial.latent.develop.Presets.byId(context, id)?.let { preset ->
                    onSettingsChange(settings.copy(preset = id, film = preset.recipe.film))
                    com.celestial.latent.develop.Recipes.setCurrent(context, preset.recipe)
                }
            }
            if (toast.isNotEmpty()) Text(toast, color = LatentColors.TextBright, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color(0xCC161615)).padding(12.dp))
        }
        // Control strip: only what this extension accepts
        val c = caps
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            StripCell("EV", if (c?.ev == true) String.format("%+.1f", ev / 6.0).replace("+0.0", "0.0") else "—", false, selected == "EV", c?.ev != true || shutter != null, Modifier.weight(1f)) { if (c?.ev == true) selected = if (selected == "EV") "" else "EV" }
            StripCell("S", if (c?.manual == true) (shutter?.let { com.celestial.latent.camera.ControlMath.shutterLabel(it) } ?: "auto") else "—", c?.manual == true && shutter == null, selected == "S", c?.manual != true, Modifier.weight(1f)) { if (c?.manual == true) selected = if (selected == "S") "" else "S" }
            StripCell("ISO", if (c?.manual == true) (isoV?.toString() ?: "auto") else "—", c?.manual == true && isoV == null, selected == "ISO", c?.manual != true, Modifier.weight(1f)) { if (c?.manual == true) selected = if (selected == "ISO") "" else "ISO" }
            StripCell("WB", "auto", true, false, true, Modifier.weight(1f)) {}
            StripCell("FOCUS", if (c?.afRegions == true) "TAP" else "auto", c?.afRegions != true, false, c?.afRegions != true, Modifier.weight(1f)) {}
        }
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            when (selected) {
                "EV" -> c?.evRange?.let { r -> StripSlider(ev.toFloat(), r.lower.toFloat(), r.upper.toFloat(), (r.upper - r.lower - 1).coerceAtLeast(0), { v -> ev = Math.round(v); cam.evIndex = ev; cam.refresh() }, { ev = 0; cam.evIndex = 0; cam.refresh() }, "0") }
                "S" -> c?.expRange?.let { r -> val p = com.celestial.latent.camera.ControlMath.shutterPresets(r.lower, r.upper); val idx = shutter?.let { sh -> p.indexOfFirst { it >= sh }.coerceAtLeast(0) } ?: 0
                    StripSlider(idx.toFloat(), 0f, (p.size - 1).toFloat(), (p.size - 2).coerceAtLeast(0), { v -> shutter = p[Math.round(v).coerceIn(0, p.size - 1)]; cam.shutterNs = shutter; cam.refresh() }, { shutter = null; cam.shutterNs = null; cam.refresh() }, "A") }
                "ISO" -> c?.isoRange?.let { r -> val p = com.celestial.latent.camera.ControlMath.isoPresets(r.lower, r.upper); val idx = isoV?.let { i -> p.indexOfFirst { it >= i }.coerceAtLeast(0) } ?: 0
                    StripSlider(idx.toFloat(), 0f, (p.size - 1).toFloat(), (p.size - 2).coerceAtLeast(0), { v -> isoV = p[Math.round(v).coerceIn(0, p.size - 1)]; cam.iso = isoV; cam.refresh() }, { isoV = null; cam.iso = null; cam.refresh() }, "A") }
                // 84a: the same mode row as the camera screen; PRO, FAST and CINEMA go back to it
                else -> ModeRow(if (mode == CameraExtensionCharacteristics.EXTENSION_AUTOMATIC) CameraModes.AUTO else CameraModes.NIGHT, { m ->
                    val ext = when (m) { CameraModes.NIGHT -> CameraExtensionCharacteristics.EXTENSION_NIGHT; CameraModes.AUTO -> CameraExtensionCharacteristics.EXTENSION_AUTOMATIC; else -> null }
                    if (ext != null) { CameraModes.setXiaomi(context, m); mode = ext; resetManual(); cam.switchTo(ext) }
                    else { onSettingsChange(CameraModes.apply(settings, m)); onBack() }
                })
            }
        }
        // Shutter row: thumbnail · shutter · spacer
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(LatentColors.Surface).combinedClickable(onClick = {
                Haptics.tick(context); onOpenRoll()
            })) { thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "Last photo", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
            Box(Modifier.size(78.dp).clip(CircleShape).border(2.dp, LatentColors.TextBright, CircleShape).combinedClickable(onClick = { Haptics.heavy(context); cam.capture() }), contentAlignment = Alignment.Center) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(LatentColors.TextBright))
            }
            Spacer(Modifier.size(46.dp))
        }
        Text(extName(mode) + " · XIAOMI PROCESSING", color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
