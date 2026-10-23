package com.celestial.latent.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.media.ExifInterface
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Camera2 controller for step 2b/2c:
 *  - opens logical camera 0 and routes preview + RAW to one physical lens,
 *  - single-frame DNG capture (Android's DngCreator, full metadata),
 *  - 16-frame RAW burst: every frame into one burst roll (68a), plus the aligned average.
 *
 * All camera callbacks run on one background thread; UI gets plain strings via [onStatus].
 */
class CameraController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onReadout: (LiveReadout) -> Unit = {},
    private val onSaved: (android.net.Uri) -> Unit = {},
) {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    @Volatile var saveJpeg = false
    private val baseNames = HashMap<Long, String>()      // sensor timestamp -> file base name
    private val pendingJpegs = HashMap<Long, ByteArray>() // JPEGs that arrived before their RAW was named
    private var oisAvailable = false
    private var previewSurface: Surface? = null
    private var lens: Lens = Lenses.DEFAULT
    private var directOpen = false
    /** Camera path: logical camera ID to route through ("0", "6", "7"...) or "direct". */
    @Volatile var cameraPath: String = Lenses.LOGICAL_ID
    private var fallbackDirect = false
    private var logicalId: String = Lenses.LOGICAL_ID
    /** Vendor overrides (MotionCam-style). Applied at session creation / in every request. */
    @Volatile var opmode: Int = 0
    @Volatile var opmodeLens: String = "all"
    private val activeOpmode get() = if (dcgOn()) DCG_OPMODE else if (opmode != 0 && (opmodeLens == "all" || opmodeLens == lens.physicalId)) opmode else 0
    @Volatile var vendorTags: List<VendorTagSpec> = emptyList()
    /** Built-in feature: Qualcomm in-sensor zoom for the JPEG path. */
    @Volatile var inSensorZoomJpeg = false
    /**
     * DCG (dual conversion gain) on the main lens, as MotionCam found it on the 15 Ultra and shared in its
     * device profile: camera 2 opened directly, session opmode 37379 (0x9203), sensor mode 24 as an i32
     * request tag, 30 fps. The sensor then delivers 14-bit RAW (white 16383, black ~1024) of which the
     * bottom 512 rows are not picture. Measured 19 Oct (67d): about 2.4x less noise in the deepest shadows.
     * Not at ×2, which is a sensor mode of its own (3).
     */
    @Volatile var dcgTest = false
    /**
     * 79a Fast shot. While on: every preview frame also goes to the RAW stream and the last few are kept
     * ([zsl]), so the shutter saves the frame of the moment it was pressed; exposure is our own
     * ([FastShot.Exposure]) so the shutter can come from the motion ([FastShot.MotionMeter]) or from you,
     * with ISO following; [fastCrop] / [fastFocal] are the focal length's crop, recorded with each shot.
     */
    @Volatile var fastShot = false
    @Volatile var fastCrop = 1f
    @Volatile var fastFocal = 0
    private val zsl = ArrayDeque<Image>()
    private val zslResults = LinkedHashMap<Long, TotalCaptureResult>()
    /** While a burst runs, the RAW stream leaves the preview and the burst has it to itself. */
    private var zslPaused = false
    private val motion = FastShot.MotionMeter()
    private val fastExposure = FastShot.Exposure()
    private val fastSample = FloatArray(FastShot.W * FastShot.H)
    private var fastFrames = 0
    /** Shutter and ISO our exposure asks for now (0 while the phone's own exposure is still being learned). */
    private var fastShutterNs = 0L
    private var fastIso = 0
    private var fastWhy = ""
    /** Pixels being written for a fast shot: the saved file's crop, read by the screen when the file appears. */
    private val zslWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
    /**
     * Which way of running DCG works. After a driver crash the next way is tried by itself, and the one
     * that works is kept for as long as the app runs (so at most one crash per launch):
     *  0 = auto exposure, held at 30 fps;
     *  1 = manual exposure at 30 fps — exactly MotionCam's profile, proven on the phone in 67d;
     *  2 = manual, with white balance and focus also fixed, and the plainest requests.
     */
    @Volatile private var dcgAttempt = 0
    private val DCG_OPMODE = 37379
    private val DCG_MODE = 24
    private val DCG_FRAME_NS = 16_666_666L
    private val dcgFrameNs = 33_333_333L
    private val dcgFps = 30
    /** DCG levels: 14-bit, black as the driver reports it (1024 measured; MotionCam uses 1023), top 2560 rows. */
    private val DCG_BLACK = 1024
    private val DCG_WHITE = 16383
    private val DCG_SKIP_BOTTOM = 512
    private var lastFocusD = 0f
    private fun dcgOn(c: Controls = controls) = dcgTest && !fastShot && Lenses.isXiaomi15Ultra && lens.physicalId == Lenses.MAIN_ID && sensorMode(c) == null
    /** The RAW stream runs with the preview: fast shot, and no burst in progress. */
    private val zslRunning get() = fastShot && !zslPaused
    /** Open a tele lens directly while zoomed (stops the logical camera switching sensors). */
    @Volatile var teleZoomDirect = true
    private var jpegIsUltraHdr = false   // kept false: JPEG_R cannot share a session with RAW on tested devices
    /** Set once a JPEG_R session has failed on this device: RAW + Ultra HDR + preview is not a supported combination. */
    private var ultraHdrUnsupported = false
    /** Everything that requires a new session if it changes. */
    private fun sessionSignature() = listOf(
        lens.physicalId, cameraPath, activeOpmode.toString(), wantJpeg.toString(),
        allTags().joinToString { it.name + it.scope + it.type + it.value },
        zoomedTele().toString(), fastShot.toString(),
    ).joinToString("|")
    private var openSignature: String? = null
    @Volatile private var opening = false
    /**
     * Which open is current. Every open takes a new number, and closing (the app going to the
     * background) moves it on — so a camera that finishes opening late, after the app was
     * minimised or another open began, can tell it is out of date. Its viewfinder surface may
     * already be gone; connecting to it crashed the app ("Surface was abandoned"). Only touched
     * on the camera thread.
     */
    private var openGeneration = 0
    private val wantJpeg get() = saveJpeg

    /** Rebuilds the session if any stream- or tag-affecting setting changed since it was opened. */
    fun syncSession() = handler.post {
        val surf = previewSurface ?: return@post
        if (opening) return@post                      // an open is already in flight; it will use the current settings
        if (openSignature != sessionSignature()) { log("settings changed → rebuilding session"); open(lens, surf) }
    }
    /** Android's JPEG_R format (Ultra HDR, base JPEG + gain map). Constant kept literal for older compile targets. */
    private val FORMAT_JPEG_R = 4101
    private val ISZ_KEY = "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom"
    /** This lens's in-sensor mode at these controls (periscope 38 / 39, main 3), or null. See [Lenses.sensorZoomMode]. */
    private fun sensorMode(c: Controls = controls): Int? = Lenses.sensorZoomMode(lens, c.zoom)
    /** A zoomed telephoto opened directly — but never for a sensor mode, which was proven through the logical camera. */
    private fun zoomedTele(c: Controls = controls) = teleZoomDirect && c.zoom > 1.001f && !lens.isMain && sensorMode(c) == null
    /** User tags plus, only while ×2 is actually on, the in-sensor-zoom hint. Nothing else is ever sent. */
    private fun allTags(): List<VendorTagSpec> {
        // Only the tags meant for this lens: a sensor mode valid on one sensor breaks the others.
        val out = ArrayList(vendorTags.filter { it.name.isNotBlank() && (it.lens == "all" || it.lens == lens.physicalId) })
        val mode = sensorMode()
        if (mode != null) {
            // The periscope's own sensor crop: the RAW itself becomes the close-up. Sent on every
            // request, exactly as the Vendor codes test that proved it — and instead of the
            // JPEG-only hint below, never with it.
            if (out.none { it.name == Lenses.SENSOR_MODE_KEY }) out += VendorTagSpec(Lenses.SENSOR_MODE_KEY, "request", "i32", mode.toString())
        } else if (inSensorZoomJpeg && Lenses.isXiaomi15Ultra && controls.zoom > 1.001f && out.none { it.name == ISZ_KEY }) out += VendorTagSpec(ISZ_KEY, "session", "i32", "1")
        if (dcgOn() && out.none { it.name == Lenses.SENSOR_MODE_KEY }) out += VendorTagSpec(Lenses.SENSOR_MODE_KEY, "request", "i32", DCG_MODE.toString())
        return out
    }
    @Volatile var onVendorEcho: (String) -> Unit = {}
    @Volatile var onBurstFinished: () -> Unit = {}
    /** A burst roll was saved: its file and the sharpest frame's preview JPEG (for the last-shot thumbnail). */
    @Volatile var onRollSaved: (android.net.Uri, ByteArray?) -> Unit = { _, _ -> }
    private lateinit var physChars: CameraCharacteristics
    private var rawSize = Size(4096, 3072)

    // Pairing of RAW images with their metadata (matched on sensor timestamp).
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val pendingImages = HashMap<Long, Image>()

    // Burst state
    private var burst: BurstJob? = null
    private var roll: RollJob? = null

    // Control strip state
    @Volatile var controls: Controls = Controls(); private set
    private var lastAutoShutterNs = 10_000_000L
    private var lastAutoIso = 100
    private var lastTransform: ColorSpaceTransform? = null
    private var lastGains: RggbChannelVector? = null
    private var afRegion: MeteringRectangle? = null
    private var afTriggerPending = false
    private var frameCounter = 0
    @Volatile var antibanding: Int = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO

    /** Ranges of the current lens, for the sliders. */
    val shutterRange: Range<Long> get() = physChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
    val isoRange: Range<Int> get() = physChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
    val minFocusDiopters: Float get() = physChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
    val evRange: Range<Int> get() = physChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
    val hasCharacteristics get() = this::physChars.isInitialized

    val currentLens get() = lens

    fun previewSizeFor(lens: Lens): Size {
        val ch = cm.getCameraCharacteristics(lens.physicalId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    /** Open the chosen lens. Preview surface must already exist. */
    @SuppressLint("MissingPermission")
    fun open(lens: Lens, surface: Surface, attempt: Int = 0) {
        handler.post {
            closeInternal()
            val gen = ++openGeneration
            opening = true
            openSignature = sessionSignature()        // claim these settings now, so a sync during the open does not loop
            this.lens = lens
            this.previewSurface = surface
            // A zoom ratio on a logical multi-camera lets the driver hand the frame to another sensor
            // (visible switch + refocus). Opening the lens directly keeps it on the sensor we chose.
            val openZoomedTele = zoomedTele()
            // The camera-path choice is a 15 Ultra setting; elsewhere, the discovered logical camera.
            val path = if (Lenses.isXiaomi15Ultra) cameraPath else Lenses.LOGICAL_ID
            // A lens that is a camera of its own cannot be reached through the logical camera.
            directOpen = path == "direct" || fallbackDirect || openZoomedTele || lens.standalone || dcgOn()
            if (openZoomedTele) log("zoom on ${lens.name}: opening the lens directly to stop the logical camera switching sensors")
            logicalId = if (path == "direct") Lenses.LOGICAL_ID else path
            try {
                physChars = cm.getCameraCharacteristics(lens.physicalId)
                afRegion = null; afTriggerPending = false; lastTransform = null; lastGains = null
                val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
                rawSize = (if (dcgOn()) dcgRawSize(map, rawSizes.toList()) else rawSizes.maxByOrNull { it.width.toLong() * it.height }) ?: rawSize
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 6).also {
                    it.setOnImageAvailableListener({ r -> onRawImage(r) }, handler)
                }
                jpegIsUltraHdr = false
            jpegReader = if (wantJpeg && !dcgOn()) {
                val fmt = if (jpegIsUltraHdr) FORMAT_JPEG_R else ImageFormat.JPEG
                val allSizes = map.getOutputSizes(fmt)?.toList().orEmpty()
                val sameAspect = allSizes.filter { Math.abs(it.width.toFloat() / it.height - rawSize.width.toFloat() / rawSize.height) < 0.02f }
                val jpegSize = (sameAspect.ifEmpty { allSizes }).maxByOrNull { it.width.toLong() * it.height } ?: rawSize
                log("JPEG stream: JPEG ${jpegSize.width}x${jpegSize.height}")
                ImageReader.newInstance(jpegSize.width, jpegSize.height, fmt, 4).also {
                    it.setOnImageAvailableListener({ r -> onJpegImage(r) }, handler)
                }
            } else null
                oisAvailable = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                status("Opening ${lens.name} (${lens.label}) · RAW ${rawSize.width}x${rawSize.height}")
                val idToOpen = if (directOpen) lens.physicalId else logicalId
                cm.openCamera(idToOpen, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        // Out of date (closed or superseded while opening), or its surface already
                        // gone: step aside quietly. The fresh open from the new surface takes over.
                        if (gen != openGeneration || !surface.isValid) {
                            log("a camera finished opening after the app moved on; closing it")
                            try { cam.close() } catch (_: Exception) {}
                            if (gen == openGeneration) opening = false
                            return
                        }
                        log("opened camera $idToOpen for lens ${lens.physicalId}${if (directOpen) " (direct)" else ""}"); device = cam; createSession()
                    }
                    override fun onDisconnected(cam: CameraDevice) { log("camera disconnected (background or another app took it)"); try { session?.close() } catch (_: Exception) {}; session = null; cam.close(); device = null }
                    override fun onError(cam: CameraDevice, error: Int) {
                        cam.close(); device = null
                        opening = false
                        if (jpegIsUltraHdr) {
                            // RAW + JPEG_R + preview is not a supported stream combination here.
                            ultraHdrUnsupported = true
                            log("camera error $error with a JPEG_R stream; Ultra HDR cannot run alongside RAW on this camera — falling back to plain JPEG")
                            status("Ultra HDR needs to be off while shooting RAW on this phone — using plain JPEG")
                            handler.postDelayed({ previewSurface?.let { open(lens, it) } }, 600)
                        } else if (dcgOn() && error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE && dcgAttempt < 2) {
                            dcgAttempt++
                            log("DCG: camera error $error on way ${dcgAttempt - 1}; trying way $dcgAttempt")
                            status("DCG: one moment, trying again…")
                            handler.postDelayed({ previewSurface?.let { open(lens, it) } }, 800)
                        } else status(if (dcgOn()) "Camera error $error with DCG on (all 3 ways tried); switch DCG off" else "Camera error $error")
                    }
                }, handler)
            } catch (e: Exception) {
                opening = false
                // Typically the camera service restarting after a driver crash: IDs vanish for a moment.
                if (attempt < 10) {
                    status("Camera service restarting… (${attempt + 1}/10)")
                    log("open failed: ${e.javaClass.simpleName} ${e.message}")
                    handler.postDelayed({ open(lens, surface, attempt + 1) }, 1500)
                } else {
                    status("Camera unavailable: ${e.message}. Remove any vendor tag the lens rejects and switch lenses again.")
                }
            }
        }
    }

    private fun createSession() {
        val dev = device ?: return
        val prev = previewSurface ?: return
        val reader = rawReader ?: return
        val outputs = ArrayList<OutputConfiguration>()
        try {
            outputs += OutputConfiguration(prev)
            outputs += OutputConfiguration(reader.surface)
            jpegReader?.let { outputs += OutputConfiguration(it.surface) }
        } catch (e: IllegalArgumentException) {
            // A surface went away between the checks and here (Android: "Surface was abandoned").
            // Close cleanly rather than crash; the next open, from a fresh surface, recovers.
            log("a camera surface was gone before the session could start (${e.message}); closing")
            try { dev.close() } catch (_: Exception) {}
            device = null; opening = false
            return
        }
        if (!directOpen) outputs.forEach { it.setPhysicalCameraId(lens.physicalId) }
        val sessionType = if (activeOpmode != 0) activeOpmode else SessionConfiguration.SESSION_REGULAR
        val config = SessionConfiguration(sessionType, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { opening = false; log("session configured: ${outputs.size} streams, opmode=${if (activeOpmode != 0) "0x" + Integer.toHexString(activeOpmode) else "regular"}, tags=${allTags().map { it.name.substringAfterLast('.') + "=" + it.value }}"); session = s; startPreview() }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                opening = false
                if (!directOpen) {
                    log("session via logical camera failed; retrying with direct open of ${lens.physicalId}")
                    fallbackDirect = true
                    val surf = previewSurface!!
                    handler.post { open(lens, surf) }
                } else status(if (dcgOn()) "The ${lens.name} refused the DCG session; switch DCG off" else "Session configuration failed for ${lens.name}")
            }
        })
        try {
            val sessionTags = allTags().filter { it.scope == "session" }
            if (sessionTags.isNotEmpty() || dcgOn()) {
                val sp = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                sessionTags.forEach { applyVendorTag(sp, it) }
                // The frame rate is a session setting on many drivers: ask for 60 fps from the start.
                if (dcgOn()) sp.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(dcgFps, dcgFps))
                config.sessionParameters = sp.build()
            }
            if (dcgOn()) {
                val minNs = runCatching { physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize) }.getOrDefault(0L)
                log("DCG way $dcgAttempt (${if (directOpen) "direct" else "via $logicalId"}${if (dcgAttempt == 2) ", fixed white balance and focus, plain requests" else ""}): opmode $DCG_OPMODE (0x${Integer.toHexString(DCG_OPMODE)}) + sensor mode $DCG_MODE, ${if (dcgAttempt == 0) "auto" else "manual"} exposure, on the ${lens.name}, $dcgFps fps · RAW ${rawSize.width}x${rawSize.height}, " +
                    "fastest RAW frame the lens reports ${"%.1f".format(minNs / 1e6)} ms (60 fps needs 16.7 ms or less)")
            }
            if (activeOpmode != 0) log("session opmode 0x${Integer.toHexString(activeOpmode)} (lens ${lens.physicalId})")
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            opening = false
            val disconnected = e.message?.contains("DISCONNECTED", ignoreCase = true) == true
            if (!directOpen && !disconnected) {
                log("createCaptureSession threw (${e.message}); retrying with direct open of ${lens.physicalId}")
                fallbackDirect = true
                val surf = previewSurface!!
                handler.post { open(lens, surf) }
            } else if (disconnected) {
                log("createCaptureSession: device disconnected mid-configure; retrying the same path")
                handler.postDelayed({ previewSurface?.let { open(lens, it) } }, 400)
            } else status("createCaptureSession: ${e.message}")
        }
    }

    private fun startPreview() {
        val dev = device ?: return; val s = session ?: return; val prev = previewSurface ?: return
        frameCounter = 0      // so the DCG check reads a settled frame of this session
        // 79a: a new session learns the phone's exposure again (another lens meters differently)
        fastExposure.forget(); motion.reset(); fastShutterNs = 0L; fastIso = 0; fastFrames = 0; zslPaused = false
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(prev)
            if (zslRunning) rawReader?.let { addTarget(it.surface) }
            applyControls(this)
        }
        try {
            s.setRepeatingRequest(req.build(), previewCallback, handler)
            status("${lens.name} · ${lens.label} · ${if (directOpen) "direct" else "via $logicalId"} · ${if (oisAvailable) "OIS on" else "no OIS"} · streams: preview+RAW${if (jpegReader != null) "+JPEG" else ""} · tap = RAW, hold = burst")
        } catch (e: Exception) { status("preview failed: ${e.message}") }
    }

    /** Re-issue the repeating preview request with the current controls. */
    private fun updatePreview() {
        val dev = device ?: return; val s = session ?: return; val prev = previewSurface ?: return
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(prev)
                if (zslRunning) rawReader?.let { addTarget(it.surface) }
                applyControls(this)
            }
            if (afTriggerPending) {
                // One-shot AF trigger, then the repeating request continues without it.
                afTriggerPending = false
                val trig = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(prev); applyControls(this)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                s.capture(trig.build(), previewCallback, handler)
            }
            s.setRepeatingRequest(req.build(), previewCallback, handler)
        } catch (e: Exception) { status("update failed: ${e.message}") }
    }

    fun setControls(c: Controls) = handler.post {
        val wasZoomedTele = zoomedTele()
        val crossedZoom = (controls.zoom > 1.001f) != (c.zoom > 1.001f)
        // The periscope's sensor modes were proven set from the session's start; changing one
        // mid-session is untested, so off / ×2 / ×4 each gets a fresh session.
        val modeChanged = sensorMode() != sensorMode(c) || dcgOn() != dcgOn(c)
        controls = c
        val isZoomedTele = zoomedTele()
        if (wasZoomedTele != isZoomedTele || modeChanged || (crossedZoom && inSensorZoomJpeg)) previewSurface?.let { open(lens, it) } else updatePreview()
    }
    fun setAntibanding(mode: Int) = handler.post { antibanding = mode; updatePreview() }
    /** A new lens and its controls together, in one open (79a: a focal length can change both at once). */
    fun openWith(lens: Lens, surface: Surface, c: Controls) = handler.post { controls = c; open(lens, surface) }

    /** Viewfinder point (u right, v down, both 0..1) -> metering rectangle in sensor coordinates. */
    private fun regionFor(u: Float, v: Float): MeteringRectangle? {
        val active = physChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val orientation = physChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Undo the sensor->display rotation. 90: display (u,v) came from sensor (x=v, y=1-u).
        val (sx, sy) = when (orientation) {
            90 -> v to (1f - u)
            270 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            else -> u to v
        }
        // With a zoom ratio active, the visible field is the centre 1/zoom of the sensor: map the tap into it.
        val z = Lenses.sensorZoomFactor(lens, controls.zoom) ?: controls.zoom.coerceAtLeast(1f)
        val vx = 0.5f + (sx - 0.5f) / z
        val vy = 0.5f + (sy - 0.5f) / z
        val half = 0.06f / z
        val l = ((vx - half) * active.width()).toInt().coerceIn(0, active.width() - 2)
        val t = ((vy - half) * active.height()).toInt().coerceIn(0, active.height() - 2)
        val w = (2 * half * active.width()).toInt().coerceAtLeast(2).coerceAtMost(active.width() - l)
        val h = (2 * half * active.height()).toInt().coerceAtLeast(2).coerceAtMost(active.height() - t)
        val r = MeteringRectangle(l, t, w, h, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        log("AF region for tap (%.2f, %.2f) -> sensor [%d,%d %dx%d] of %dx%d, orientation %d".format(u, v, l, t, w, h, active.width(), active.height(), orientation))
        return r
    }

    /** Tap-to-focus. Also clears any AE/AF lock. */
    fun tapFocus(u: Float, v: Float) = handler.post {
        afRegion = regionFor(u, v) ?: return@post
        afTriggerPending = true
        controls = controls.copy(focusDiopters = null, locked = false)
        updatePreview()
    }

    /** Long-press: focus at the point, then lock exposure and hold focus there. */
    fun lockAt(u: Float, v: Float) = handler.post {
        afRegion = regionFor(u, v) ?: return@post
        afTriggerPending = true
        controls = controls.copy(focusDiopters = null, locked = true)
        updatePreview()
    }

    fun unlock() = handler.post {
        afRegion = null
        controls = controls.copy(locked = false)
        updatePreview()
    }

    /** Applies the control strip to any request (preview or still). */
    private fun applyControls(b: CaptureRequest.Builder) {
        val c = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        // A ratio of exactly 1.0 still puts the driver on its zoom path (softer output, shifted AF regions),
        // so only send it when the user actually zoomed.
        if (c.zoom > 1.001f && sensorMode(c) == null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom)
        // Exposure
        val dcg = dcgOn(c)
        // DCG: a fixed 30 fps. Way 0 lets auto exposure work inside it (so no shutter longer than 1/30 s);
        // ways 1–2 are MotionCam's manual exposure (the shutter and ISO you set, or the last auto values).
        if (dcg) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(dcgFps, dcgFps))
        val ownExposure = fastShot && fastExposure.learned && fastShutterNs > 0 && !(c.shutterNs != null && c.iso != null)
        if (ownExposure) {
            // 79a: our own exposure: the shutter from the motion (or yours), ISO to match
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, fastShutterNs.coerceIn(shutterRange.lower, shutterRange.upper))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, fastIso.coerceIn(isoRange.lower, isoRange.upper))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(33_333_333L, fastShutterNs))
        } else if (c.manualExposure || (dcg && dcgAttempt >= 1)) {
            val exp = (c.shutterNs ?: lastAutoShutterNs).let { if (dcg) minOf(it, dcgFrameNs) else it }
            val iso = c.iso ?: lastAutoIso
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp.coerceIn(shutterRange.lower, shutterRange.upper))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (dcg) dcgFrameNs else maxOf(33_333_333L, exp))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evIndex.coerceIn(evRange.lower, evRange.upper))
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding)
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.locked)
        }
        // Keep the preview and still pipelines identical so a capture does not visibly re-configure anything.
        val plain = dcg && dcgAttempt == 2
        if (!plain) b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        // Image quality: optical stabilisation on where the lens has it; best hot-pixel correction; no EIS (crops, stills don't need it).
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (oisAvailable) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (!plain) physChars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.let { modes ->
            if (modes.contains(CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)) b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
        }
        // Vendor tags: session-scoped ones are sent again in requests too (harmless, and some HALs read them there).
        allTags().filter { it.name.isNotBlank() }.forEach { applyVendorTag(b, it) }
        // White balance
        val k = c.kelvin
        val fixedWb = plain && lastGains != null && lastTransform != null
        if (fixedWb) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, lastGains)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastTransform)
        } else if (k != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, ControlMath.gainsForKelvin(physChars, k))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastTransform ?: ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, c.locked)
        }
        // Focus
        val f = c.focusDiopters ?: if (plain) lastFocusD else null
        if (f != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, f.coerceIn(0f, minFocusDiopters))
        } else {
            val region = afRegion
            if (region != null) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
    }

    /** Reads what the camera actually did, so "A" can show real numbers and manual modes start from them. */
    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            val r = metaFor(result)
            val c = controls
            // DCG with manual exposure, or fast shot's own exposure: keep the phone's values from before
            val dcg = (dcgOn(c) && dcgAttempt >= 1) || (fastShot && fastExposure.learned)
            r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { if (!c.manualExposure && !dcg) lastAutoShutterNs = it }
            r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { if (!c.manualExposure && !dcg) lastAutoIso = it }
            if (fastShot) result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                zslResults[ts] = result
                while (zslResults.size > 10) zslResults.remove(zslResults.keys.first())
                zsl.firstOrNull { it.timestamp == ts }?.let { measureFast(it) }
            }
            if (!dcg) r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusD = it }
            if (c.kelvin == null && !dcg) {
                r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastTransform = it }
                r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastGains = it }
            }
            if (++frameCounter == 45 && Lenses.isXiaomi15Ultra && lens.isMain) logDcgFrame(if (dcgOn()) "preview, DCG on" else "preview, DCG off", r)
            if (frameCounter % 6 == 0) {
                val gains = lastGains
                val af = when (r.get(CaptureResult.CONTROL_AF_STATE)) {
                    CameraMetadata.CONTROL_AF_STATE_INACTIVE -> "inactive"
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN -> "scanning"
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "focused"
                    CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "scanning*"
                    CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "locked ✓"
                    CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "locked ✗"
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "unfocused"
                    else -> "?"
                }
                onReadout(LiveReadout(
                    shutterNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                    iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                    kelvinEstimate = if (gains != null && frameCounter % 30 == 0) ControlMath.kelvinForGains(physChars, gains) else -1,
                    afState = af,
                    fast = if (fastShot) fastWhy else "",
                ))
            }
        }
    }

    private fun stillRequest(withJpeg: Boolean = false): CaptureRequest.Builder {
        val dev = device!!
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            if (withJpeg) jpegReader?.let { addTarget(it.surface) }
            previewSurface?.let { addTarget(it) }
            applyControls(this)
            set(CaptureRequest.JPEG_ORIENTATION, physChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())

        }
    }

    @Volatile private var busy = false

    fun captureSingle() = handler.post {
        val s = session ?: return@post
        if (busy) { log("shutter ignored: still saving the previous shot"); return@post }
        if (zslRunning) { zslCapture(); return@post }
        try {
            busy = true
            val t0 = System.nanoTime()
            s.capture(stillRequest(withJpeg = wantJpeg && !hdrJpegSequential).build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    onResult(result, t0)
                    echoVendorTags(result)
                }
                override fun onCaptureFailed(sess: CameraCaptureSession, req: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    busy = false; status("capture failed (reason ${failure.reason})")
                }
            }, handler)
            status("Capturing…")
        } catch (e: Exception) { busy = false; status("capture: ${e.message}") }
    }

    /**
     * A burst. With [focus] (69a) it is a focus bracket instead: autofocus off, each frame focused a
     * little farther away, centred on where the focus is now, so the frames can later be stacked into
     * one picture that is sharp from front to back. A focus bracket goes into the roll only (no STACK).
     */
    fun captureBurst(frames: Int = 16, focus: Boolean = false) = handler.post {
        val s = session ?: return@post
        if (roll != null) { status("Still saving the last burst…"); return@post }
        if (burst != null || busy) { status("busy"); return@post }
        // 79a: the burst has the RAW stream to itself; the kept frames are let go
        if (fastShot) { zslPaused = true; while (zsl.isNotEmpty()) zsl.removeFirst().close(); updatePreview() }
        val dcg = dcgOn()
        var rj: RollJob? = null
        try {
            val focusAt = if (focus) focusBracket(frames) else null
            // Every frame goes into one burst roll (68a). Without DCG the frames are also stacked into
            // the cleaner STACK DNG as before; DCG frames (14-bit) are only kept, their merge is 68b.
            // A spool left behind by a burst the app was closed during is no use now.
            context.cacheDir.listFiles { f -> f.name.endsWith(".spool") }?.forEach { it.delete() }
            val job = if (dcg || focus) null else BurstJob(frames, rawSize.width, rawSize.height, System.nanoTime())
            rj = RollJob(frames, fileBase(if (focus) "FOCUS" else "BURST"), dcg, System.nanoTime(),
                kind = if (focus) BurstRoll.KIND_FOCUS else BurstRoll.KIND_BURST, rollOnly = job == null)
            roll = rj
            burst = job
            // Exposure is locked for the burst so every frame matches (DCG way 0 is the only auto path here).
            // A focus bracket also locks white balance, and sets each frame's focus by hand.
            val reqs = List(frames) { i -> stillRequest(withJpeg = job != null && wantJpeg && !hdrJpegSequential && i == 0).apply {
                if ((dcg && dcgAttempt == 0) || focus) set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (focusAt != null) {
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusAt[i])
                }
            }.build() }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) { onResult(result, null) }
                override fun onCaptureFailed(sess: CameraCaptureSession, req: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    log("burst frame failed reason=${failure.reason}")
                    rj.failed.incrementAndGet(); rj.checkDone()
                    if (job != null) { job.failed++; if (job.received + job.failed >= job.frames) finishBurst(job) }
                }
            }, handler)
            status(if (focus) "Focus stack of $frames… hold still" else "Burst of $frames… hold still")
            // A frame whose result never pairs up would leave the roll open; count the missing ones as failed.
            handler.postDelayed({
                val missing = frames - rj.received.get() - rj.failed.get()
                if (missing > 0) { log("burst: $missing frames never arrived"); rj.failed.addAndGet(missing); rj.checkDone() }
            }, 8000)
        } catch (e: Exception) { burst = null; roll = null; rj?.abandon(); status("burst: ${e.message}"); resumeZsl() }
    }

    /**
     * One burst roll being saved (68a): every full-size frame, lossless, in one file under
     * Documents/Latent/Bursts (see [BurstRoll]). Frames are copied off the camera and written to a plain
     * spool file at once, so the camera never waits and sixteen frames never sit in memory together; two
     * background threads then compress them (plus a small preview each) and append them to the roll in
     * order. About 21 MB per DCG frame before packing, about 10 MB after.
     */
    private inner class RollJob(val frames: Int, val base: String, val dcg: Boolean, val startNs: Long,
                                val kind: Int = BurstRoll.KIND_BURST, val rollOnly: Boolean = dcg) {
        val received = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        private val metas = arrayOfNulls<BurstRoll.Frame>(frames)
        private val previews = arrayOfNulls<ByteArray>(frames)
        private var common: BurstRoll.Common? = null
        private var w = 0; private var h = 0; private var firstTs = 0L; private var gain = 1f
        private val spoolFile = java.io.File(context.cacheDir, "$base.spool")
        private val spool = java.io.RandomAccessFile(spoolFile, "rw").channel
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        private val lock = Object()
        private val ready = HashMap<Int, Pair<BurstRoll.Encoded, ByteArray?>?>()
        private var next = 0; private var written = 0
        private var uri: android.net.Uri? = null
        private var writer: BurstRoll.StreamWriter? = null
        private var finished = false

        /** On the camera thread: copy the frame out, spool it, queue its compression. */
        fun accept(img: Image, result: TotalCaptureResult) {
            val k = received.get()
            if (k >= frames) return
            val (px, pw, ph) = if (dcg) dcgPixels(img) else plainPixels(img)
            val r = metaFor(result)
            val black: Int; val white: Int
            if (dcg) {
                black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()?.let { Math.round(it).toInt() }?.takeIf { it in 512..2048 } ?: DCG_BLACK
                white = DCG_WHITE
            } else {
                black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()?.let { Math.round(it).toInt() }
                    ?: physChars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 64
                white = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: physChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            }
            val m = DngWriter.metaFrom(physChars, r, pw, ph, black = black, white = white, orientation = 6, description = "")
            if (k == 0) {
                w = pw; h = ph; firstTs = img.timestamp
                common = BurstRoll.Common(pw, ph, black, white, m.cfaPattern, m.colorMatrix1, m.colorMatrix2, m.forwardMatrix1,
                    m.cameraCalibration1, m.illuminant1, m.illuminant2, 6, m.uniqueModel, lens.name, dcg, kind)
                gain = BurstRoll.previewGain(px, black, white)
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.${BurstRoll.EXT}")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, BurstRoll.FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val u = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: throw IllegalStateException("could not create the burst roll file")
                uri = u
                writer = BurstRoll.StreamWriter(java.io.BufferedOutputStream(context.contentResolver.openOutputStream(u)!!, 1 shl 20))
            }
            if (pw != w || ph != h) { log("burst: frame ${k + 1} has a different size, left out"); failed.incrementAndGet(); return }
            metas[k] = BurstRoll.Frame(img.timestamp - firstTs, m.exposureNs, m.iso, m.asShotNeutral,
                focusD = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: -1f,
                lensMoving = r.get(CaptureResult.LENS_STATE) == CameraMetadata.LENS_STATE_MOVING)
            val bb = java.nio.ByteBuffer.allocate(px.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(px)
            var pos = k.toLong() * w * h * 2
            while (bb.hasRemaining()) pos += spool.write(bb, pos)
            received.incrementAndGet()
            pool.execute { encode(k) }
            status("Burst roll: frame ${k + 1}/$frames")
        }

        private fun encode(k: Int) {
            var item: Pair<BurstRoll.Encoded, ByteArray?>? = null
            try {
                val bb = java.nio.ByteBuffer.allocate(w * h * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var pos = k.toLong() * w * h * 2
                while (bb.hasRemaining()) { val n = spool.read(bb, pos); if (n < 0) break; pos += n }
                bb.flip()
                val px = ShortArray(w * h).also { bb.asShortBuffer().get(it) }
                val f = metas[k]!!; val c = common!!
                f.sharpness = BurstRoll.sharpness(px, w, h, c.cfa, c.black)
                val (argb, pw, ph) = BurstRoll.previewArgb(px, c, f.neutral, gain)
                val jpeg = java.io.ByteArrayOutputStream().also { o ->
                    android.graphics.Bitmap.createBitmap(argb, pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                        .compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o)
                }.toByteArray()
                previews[k] = jpeg
                item = BurstRoll.encode(px, w, h) to jpeg
            } catch (t: Throwable) { log("burst: frame ${k + 1} not saved: ${t.message}") }
            synchronized(lock) {
                ready[k] = item
                try {
                    while (ready.containsKey(next)) {
                        val it = ready.remove(next)
                        if (it != null) { writer!!.addFrame(metas[next]!!, it.first, it.second); written++ } else metas[next] = null
                        next++
                    }
                } catch (e: Exception) { log("burst roll write failed: ${e.message}") }
                status("Burst roll: saved $written/${received.get()}")
            }
            checkDone()
        }

        /** Done when every frame has arrived (or failed) and every arrived frame is in the file. */
        fun checkDone() = synchronized(lock) {
            if (finished || received.get() + failed.get() < frames || next < received.get()) return@synchronized
            finished = true
            pool.shutdown()
            if (roll === this) handler.post { if (roll === this) roll = null; resumeZsl() }
            try {
                val u = uri
                val wr = writer
                if (u == null || wr == null || written == 0) { abandon(); status("Burst: no frames saved"); return@synchronized }
                val kept = metas.filterNotNull()
                val size = wr.finish(BurstRoll.Index(common!!, kept))
                context.contentResolver.update(u, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val best = kept.indices.maxByOrNull { kept[it].sharpness } ?: 0
                val wall = (System.nanoTime() - startNs) / 1_000_000
                status("Burst roll saved · ${kept.size} frames · ${"%.0f".format(size / 1e6)} MB · sharpest #${best + 1} · ${wall / 1000.0} s")
                log("burst roll $base: ${kept.size} frames ${w}x$h${if (dcg) " DCG" else ""}, ${"%.1f".format(size / 1e6)} MB " +
                    "(${"%.0f".format(100.0 * size / (kept.size.toLong() * w * h * 2))}% of plain), sharpness " + kept.joinToString(" ") { "%.0f".format(it.sharpness) })
                if (kind == BurstRoll.KIND_FOCUS) log("focus stack $base: focus (dioptres, * = lens moving) " +
                    kept.joinToString(" ") { "%.2f".format(it.focusD) + if (it.lensMoving) "*" else "" })
                onRollSaved(u, previews.filterNotNull().getOrNull(best))
                if (rollOnly) onBurstFinished()
            } catch (e: Exception) { status("burst roll save failed: ${e.message}"); Log.e("Latent", "roll", e) }
            finally { runCatching { spool.close() }; spoolFile.delete() }
        }

        /** Nothing worth keeping: remove the half-written file and the spool. */
        fun abandon() {
            pool.shutdownNow()
            runCatching { spool.close() }; spoolFile.delete()
            uri?.let { u -> runCatching { context.contentResolver.delete(u, null, null) } }
        }
    }

    /**
     * Focus distances (dioptres, 1 / metres) for a focus bracket of [n] frames, nearest first, centred on
     * the current focus. The step is about one depth of field (2·N·c / f², with c = two pixels), so the
     * sharp zones of neighbouring frames overlap a little. On the main lens that is about 0.23 dioptres,
     * so 12 frames cover roughly 16-27 cm when focused at 20 cm. If the lens does not report real
     * dioptres (calibration "uncalibrated"), it falls back to 1/40 of the focus range.
     */
    private fun focusBracket(n: Int): FloatArray {
        val maxD = minFocusDiopters
        val cal = physChars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        val f = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val aperture = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val sensorMm = physChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        val step = if (cal != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED && f != null && aperture != null && sensorMm != null) {
            val c = 2f * sensorMm / rawSize.width                       // mm
            (0.8f * 2f * aperture * c / (f * f) * 1000f).coerceIn(0.02f, 1f)
        } else (maxD / 40f).coerceAtLeast(0.05f)
        val centre = controls.focusDiopters ?: lastFocusD
        val span = step * (n - 1)
        val near = (centre + span / 2).coerceAtMost(maxD).coerceAtLeast(span.coerceAtMost(maxD))
        val at = FloatArray(n) { i -> (near - i * step).coerceIn(0f, maxD) }
        log("focus stack: ${lens.name}, calibration $cal, f=$f mm, f/$aperture, sensor $sensorMm mm, step ${"%.3f".format(step)} D, " +
            "centre ${"%.2f".format(centre)} D, frames " + at.joinToString(" ") { "%.2f".format(it) } + " D (closest focus ${"%.2f".format(maxD)} D)")
        return at
    }

    /** A plain (non-DCG) RAW frame, copied so the camera buffer can go back at once. */
    private fun plainPixels(img: Image): Triple<ShortArray, Int, Int> {
        val plane = img.planes[0]
        val sb = plane.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val w = img.width; val h = img.height; val stride = plane.rowStride / 2
        val px = ShortArray(w * h)
        for (y in 0 until h) { sb.position(y * stride); sb.get(px, y * w, w) }
        return Triple(px, w, h)
    }

    // ---- 79a fast shot: the kept frames, the meters, the moment ----------------------------

    /** A frame of the running RAW stream: kept (the last three), and measured when its result is known. */
    private fun zslAccept(img: Image) {
        zsl.addLast(img)
        while (zsl.size > 3) zsl.removeFirst().close()
        if (zslResults.containsKey(img.timestamp)) measureFast(img)
    }

    private var lastMeasuredTs = 0L

    /**
     * Every other frame: how bright (inside the frame lines) and how fast the picture moves, then the
     * shutter and ISO for the next frames. Until the phone's own exposure has settled, it is only learned.
     */
    private fun measureFast(img: Image) {
        val ts = img.timestamp
        if (ts <= lastMeasuredTs) return
        lastMeasuredTs = ts
        if (++fastFrames % 2 != 0) return
        val r = metaFor(zslResults[ts] ?: return)
        try {
            val plane = img.planes[0]
            val sb = plane.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            val black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()?.let { Math.round(it).toInt() }
                ?: physChars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 64
            val white = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: physChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val level = FastShot.sample(sb, img.width, img.height, plane.rowStride / 2, black, white, fastCrop, fastSample)
            motion.feed(fastSample, level, ts)
            val t = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val c = controls
            val evStops = c.evIndex * (physChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 6f))
            if (!fastExposure.learned) {
                val ae = r.get(CaptureResult.CONTROL_AE_STATE)
                val settled = ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED || ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                if ((settled && frameCounter > 8) || frameCounter > 45) {
                    fastExposure.learn(level, t, iso, evStops)
                    log("fast shot: learned the phone's exposure: level ${"%.3f".format(level)} at 1/${Math.round(1e9 / t)} s ISO $iso (AE ${if (settled) "settled" else "still moving"}) on the ${lens.name}")
                } else { fastWhy = "METERING"; return }
            } else if (!c.locked) fastExposure.update(level, t, iso, evStops)
            val lo = shutterRange.lower; val isoLo = isoRange.lower; val isoHi = isoRange.upper
            val (ns, isoNew) = when {
                c.shutterNs != null -> fastExposure.settings(c.shutterNs, isoLo, isoHi, lo).also { fastWhy = "YOUR " + ControlMath.shutterLabel(c.shutterNs) }
                c.iso != null -> (fastExposure.shutterForIso(c.iso, lo, minOf(shutterRange.upper, 250_000_000L)) to c.iso).also { fastWhy = "ISO ${c.iso}" }
                else -> {
                    val want = motion.shutterNs(fastCrop).coerceAtLeast(lo)
                    fastExposure.settings(want, isoLo, isoHi, lo).also { (tt, _) -> fastWhy = "MOTION " + ControlMath.shutterLabel(tt) }
                }
            }
            if (fastExposure.dim) fastWhy += " · DIM"
            val moved = fastShutterNs == 0L || Math.abs(Math.log(ns.toDouble() * isoNew / (fastShutterNs.toDouble() * fastIso))) > 0.03 ||
                Math.abs(Math.log(ns.toDouble() / fastShutterNs)) > 0.03
            if (moved) { fastShutterNs = ns; fastIso = isoNew; updatePreview() }
        } catch (e: Exception) { log("fast shot meter: ${e.message}") }
    }

    /**
     * The shutter in fast shot: the kept frame nearest the moment you pressed — what the viewfinder showed
     * you then, about 80 ms before the press reaches the camera — copied off the camera at once and written
     * on its own thread, so the stream and the viewfinder never stop.
     */
    private fun zslCapture() {
        val pressNs = android.os.SystemClock.elapsedRealtimeNanos()
        val realtime = physChars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val ready = zsl.filter { zslResults.containsKey(it.timestamp) }
        if (ready.isEmpty()) { status("One moment: the camera is still starting"); return }
        val pick = if (realtime) ready.minByOrNull { Math.abs(it.timestamp - (pressNs - 80_000_000L)) }!! else ready.last()
        zsl.remove(pick)
        // read everything off the frame before it goes back to the camera: an Image is unusable once closed
        val pickTs = pick.timestamp
        val meta = metaFor(zslResults[pickTs]!!)
        val w = pick.width; val h = pick.height
        val bb = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(java.nio.ByteOrder.nativeOrder())
        try {
            val plane = pick.planes[0]
            val src = plane.buffer.duplicate(); val stride = plane.rowStride
            for (y in 0 until h) { src.clear(); src.position(y * stride); src.limit(y * stride + w * 2); bb.put(src) }
            bb.flip()
        } catch (e: Exception) { status("save failed: ${e.message}"); return } finally { pick.close() }
        busy = true
        val name = fileBase() + ".dng"
        val chars = physChars; val lensNow = lens; val crop = fastCrop; val focal = fastFocal
        val lagMs = if (realtime) (pressNs - pickTs) / 1_000_000 else -1
        zslWriter.execute {
            try {
                val t = System.nanoTime()
                val creator = DngCreator(chars, meta)
                creator.setOrientation(ExifInterface.ORIENTATION_ROTATE_90)
                creator.setDescription("Celestial Darkroom fast shot - $focal mm (${lensNow.name}" +
                    (if (crop > 1.001f) ", crop ${"%.2f".format(Locale.US, crop)}" else "") + ")")
                saveTo(name, crop to focal) { creator.writeByteBuffer(it, Size(w, h), bb, 0) }
                creator.close()
                val exp = meta.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                status("Saved $name · $focal mm · ${ControlMath.shutterLabel(exp)} ISO ${meta.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0}" +
                    (if (lagMs >= 0) " · the frame from ${lagMs} ms before the press" else "") + " · write ${(System.nanoTime() - t) / 1_000_000} ms")
                log("fast shot $name: frame of ${lagMs} ms before the press, ${lensNow.name} ×${controls.zoom}, crop ${"%.3f".format(crop)} for $focal mm, $fastWhy")
            } catch (e: Exception) { status("save failed: ${e.message}"); Log.e("Latent", "fast shot save", e) }
            finally { handler.post { busy = false } }
        }
    }

    /** After a burst: the RAW stream joins the preview again. */
    private fun resumeZsl() {
        if (fastShot && zslPaused && burst == null && roll == null) { zslPaused = false; updatePreview() }
    }

    // ---- pairing ----------------------------------------------------------------

    private fun onResult(result: TotalCaptureResult, t0: Long?) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val img = pendingImages.remove(ts)
        if (img != null) handle(img, result, t0) else pendingResults[ts] = result
    }

    private fun onRawImage(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        if (zslRunning && burst == null && roll == null) { zslAccept(img); return }
        val ts = img.timestamp
        val res = pendingResults.remove(ts)
        if (res != null) handle(img, res, null) else {
            pendingImages[ts] = img
            if (pendingImages.size > 4) { // never let the reader starve
                val oldest = pendingImages.keys.minOrNull()!!
                pendingImages.remove(oldest)?.close(); log("dropped unmatched frame $oldest")
            }
        }
    }

    /** Metadata for the physical lens if the logical camera reports it, else the logical result. */
    private fun metaFor(result: TotalCaptureResult): CaptureResult =
        if (directOpen) result else (result.physicalCameraResults[lens.physicalId] ?: result)

    private fun handle(img: Image, result: TotalCaptureResult, t0: Long?) {
        val job = burst
        val rj = roll
        try {
            // A burst frame goes into the roll first (a copy), then into the stack.
            if (rj != null) try { rj.accept(img, result) } catch (e: Exception) { rj.failed.incrementAndGet(); rj.checkDone(); log("burst roll: ${e.message}") }
            if (job == null && rj != null) {
                // DCG burst: the roll is all there is
            } else if (job == null) {
                val base = fileBase()
                baseNames[img.timestamp] = base
                flushPendingJpeg(img.timestamp, base)
                if (baseNames.size > 8) baseNames.remove(baseNames.keys.minOrNull()!!)
                val name = "$base.dng"
                logRawStats(img, metaFor(result))
                val ms = if (dcgOn()) writeDcgDng(img, result, name) else writeDngCreator(img, result, name)
                sensorMode()?.let { m -> log("in-sensor ×${if (controls.zoom > 2.5f) 4 else 2} on the ${lens.name} (mode $m): the RAW itself is the ${Lenses.effectiveLabel(lens, controls.zoom)}× close-up") }
                    ?: run { if (controls.zoom != 1f) log("2x: JPEG ${if (inSensorZoomJpeg) "in-sensor crop" else "digital crop"} · RAW is the full 1x frame") }
                val took = t0?.let { (System.nanoTime() - it) / 1_000_000 } ?: -1
                status("Saved $name (${img.width}x${img.height}) · shutter→file ${took} ms · write $ms ms")
                if (hdrJpegSequential) captureHdrJpeg(base) else busy = false
            } else {
                job.accept(img, result)
                if (job.received + job.failed >= job.frames) finishBurst(job)
            }
        } catch (e: Exception) {
            status("save failed: ${e.message}"); Log.e("Latent", "save", e)
        } finally { img.close() }
    }

    private fun finishBurst(job: BurstJob) {
        burst = null
        handler.post {
            try {
                val sensorMs = if (job.lastTs > 0 && job.firstTs > 0) (job.lastTs - job.firstTs) / 1_000_000 else -1
                val wallMs = (System.nanoTime() - job.startNs) / 1_000_000
                val avgName = fileName("STACK${job.received}")
                val meta = DngWriter.metaFrom(physChars, job.firstResult?.let { metaFor(it) }, job.w, job.h,
                    black = 64 * 16, white = 1023 * 16, orientation = 6,
                    description = "Celestial Darkroom aligned burst of ${job.received} frames, sensor span $sensorMs ms, ${job.tilesAccepted}/${job.tilesTotal} tiles")
                val pixels = job.result()
                val t = System.nanoTime()
                saveTo(avgName) { DngWriter.write(it, meta, pixels) }
                val wms = (System.nanoTime() - t) / 1_000_000
                status("Burst: ${job.received}/${job.frames} frames (${job.failed} failed) · sensor span $sensorMs ms · total $wallMs ms · saved $avgName (write $wms ms) · every frame goes into the burst roll")
                onBurstFinished()
                log("burst frame timestamps ms from first: " + job.tsList.joinToString { ((it - job.firstTs) / 1_000_000).toString() })
                log("alignment shifts px (accepted tiles): " + job.shifts.joinToString(" ") + " · overall ${job.tilesAccepted}/${job.tilesTotal} tiles used")
            } catch (e: Exception) { status("burst save failed: ${e.message}"); Log.e("Latent", "burst", e) }
            resumeZsl()
        }
    }

    // ---- DCG test (67a): what the camera really did ---------------------------------

    /**
     * The RAW size for the DCG test. A 60 fps-only mode is most likely a video mode, and video on this
     * sensor is 16:9, while the app normally asks for the full 4:3 frame (which the lens lists at 30 fps
     * at best). Prefer a size the lens says it can deliver at 60 fps; failing that, the largest 16:9.
     */
    private fun dcgRawSize(map: android.hardware.camera2.params.StreamConfigurationMap, sizes: List<Size>): Size? {
        val timed = sizes.map { it to runCatching { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, it) }.getOrDefault(0L) }
        log("DCG test: RAW sizes the main lens lists: " + timed.joinToString { (sz, ns) -> "${sz.width}x${sz.height} (${"%.1f".format(ns / 1e6)} ms)" })
        val area = { sz: Size -> sz.width.toLong() * sz.height }
        val fast = timed.filter { it.second in 1..DCG_FRAME_NS }.map { it.first }.maxByOrNull(area)
        val wide = sizes.filter { Math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f }.maxByOrNull(area)
        val pick = fast ?: wide ?: sizes.maxByOrNull(area)
        log("DCG test: using RAW ${pick?.width}x${pick?.height} (${if (fast != null) "fast enough for 60 fps" else if (wide != null) "largest 16:9; none listed at 60 fps" else "largest"})")
        return pick
    }

    /** One line on the frame rate, shutter and sensor mode the driver reports for a frame. */
    private fun logDcgFrame(what: String, r: CaptureResult) {
        val fd = r.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
        val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val mode = runCatching { r.get(CaptureResult.Key(Lenses.SENSOR_MODE_KEY, Int::class.javaObjectType)) }.getOrNull()
        log("DCG check ($what): frame ${"%.1f".format(fd / 1e6)} ms (${if (fd > 0) "%.0f".format(1e9 / fd) else "?"} fps) · " +
            "shutter 1/${if (exp > 0) "%.0f".format(1e9 / exp) else "?"} s · ISO ${r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0} · " +
            "sensor mode reported ${mode ?: "not reported"} (MotionCam's DCG shows $DCG_MODE)")
    }

    /**
     * The brightest raw value in a shot and how many pixels sit at it, against the white level the
     * DNG will carry. If the DCG mode delivers more than 10-bit values, the brightest value will
     * be above that white level and the DNG writer would clip them.
     */
    private fun logRawStats(img: Image, r: CaptureResult) {
        try {
            val plane = img.planes[0]
            val sb = plane.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            val w = img.width; val h = img.height; val stride = plane.rowStride / 2
            val hist = IntArray(65536)
            for (y in 0 until h) { val o = y * stride; for (x in 0 until w) hist[sb.get(o + x).toInt() and 0xFFFF]++ }
            var max = 65535; while (max > 0 && hist[max] == 0) max--
            val n = w.toLong() * h
            var lo = 0; var accLo = 0L
            for (v in 0..65535) { accLo += hist[v]; if (accLo >= n / 1000) { lo = v; break } }
            var acc = 0L; var p999 = max
            for (v in max downTo 0) { acc += hist[v]; if (acc >= n / 1000) { p999 = v; break } }
            val white = if (dcgOn()) DCG_WHITE else physChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0
            var over = 0L; for (v in maxOf(white, 0)..65535) over += hist[v]
            val black = physChars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: -1
            val dynWhite = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            val dynBlack = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.firstOrNull()
            log("raw stats ${if (dcgOn()) "[DCG on]" else "[DCG off]"} ${lens.name} ${w}x$h: darkest 0.1% at $lo, brightest $max, 99.9% below $p999 · " +
                "white level ${white}${if (dcgOn()) " (DCG, as MotionCam)" else ""} (frame says ${dynWhite ?: "-"}), black ${black} (frame says ${dynBlack?.let { "%.0f".format(it) } ?: "-"}) · " +
                "${"%.3f".format(100.0 * over / n)}% of pixels at or above white" + (if (max > white) " · ABOVE WHITE: the DNG would clip these" else ""))
            if (dcgOn()) logDcgFrame("shot", r)
        } catch (e: Exception) { log("raw stats failed: ${e.message}") }
    }

    // ---- writing ------------------------------------------------------------------

    /**
     * A DCG shot: Android's DngCreator would label it 10-bit (white 1023, black 64) and every value above
     * 1023 would read as clipped. Write it ourselves with MotionCam's levels for this mode, and leave out
     * the bottom rows that are not picture.
     */
    private fun writeDcgDng(img: Image, result: TotalCaptureResult, name: String): Long {
        val (px, w, h) = dcgPixels(img)
        return writeDcgPixels(px, w, h, result, name)
    }

    /** The picture part of a DCG frame (the bottom rows left out), copied so the camera buffer can go back at once. */
    private fun dcgPixels(img: Image): Triple<ShortArray, Int, Int> {
        val plane = img.planes[0]
        val sb = plane.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val w = img.width; val stride = plane.rowStride / 2
        val h = (img.height - DCG_SKIP_BOTTOM).coerceAtLeast(2) and 1.inv()
        val px = ShortArray(w * h)
        for (y in 0 until h) { sb.position(y * stride); sb.get(px, y * w, w) }
        return Triple(px, w, h)
    }

    private fun writeDcgPixels(px: ShortArray, w: Int, h: Int, result: TotalCaptureResult, name: String): Long {
        val r = metaFor(result)
        // The driver's own black level for this frame (it said 1024); its white level still reads 10-bit, so not that.
        val black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()?.let { Math.round(it).toInt() }?.takeIf { it in 512..2048 } ?: DCG_BLACK
        val meta = DngWriter.metaFrom(physChars, r, w, h, black = black, white = DCG_WHITE, orientation = 6,
            description = "Celestial Darkroom DCG RAW - ${lens.name} (opmode $DCG_OPMODE, sensor mode $DCG_MODE, way $dcgAttempt)")
        val t = System.nanoTime()
        saveTo(name) { DngWriter.write(it, meta, px) }
        log("DCG: saved $name as ${w}x$h, black $black, white $DCG_WHITE, 1/${"%.0f".format(1e9 / (meta.exposureNs ?: 1L))} s ISO ${meta.iso ?: "?"} (the bottom $DCG_SKIP_BOTTOM rows left out)")
        return (System.nanoTime() - t) / 1_000_000
    }

    private fun writeDngCreator(img: Image, result: TotalCaptureResult, name: String): Long {
        val creator = DngCreator(physChars, metaFor(result))
        creator.setOrientation(ExifInterface.ORIENTATION_ROTATE_90)
        creator.setDescription("Celestial Darkroom single RAW - ${lens.name} ${Lenses.effectiveLabel(lens, controls.zoom)}" +
            (sensorMode()?.let { " (in-sensor mode $it)" } ?: ""))
        val t = System.nanoTime()
        saveTo(name) { creator.writeImage(it, img) }
        creator.close()
        return (System.nanoTime() - t) / 1_000_000
    }

    private fun saveTo(name: String, fastCropFocal: Pair<Float, Int>? = null, block: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use(block)
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // 79a: the screen records a fast shot's crop with the photo as soon as it hears of it
        fastCropFocal?.let { FastShot.savedCrops[uri.toString()] = it }
        onSaved(uri)
    }

    private fun fileName(kind: String): String = fileBase(kind) + ".dng"

    private fun fileBase(kind: String = "RAW"): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // 79a: a fast shot is named by its focal length (LATENT_..._35mm_RAW)
        val label = if (fastShot && fastFocal > 0) "${fastFocal}mm" else Lenses.effectiveLabel(lens, controls.zoom).replace(".", "_") + "x"
        return "LATENT_${stamp}_${label}_$kind"
    }

    /** Nearest known base name within 100 ms of a timestamp (JPEG and RAW stamps can differ slightly). */
    private fun nearestBase(ts: Long): String? {
        val hit = baseNames.entries.minByOrNull { Math.abs(it.key - ts) } ?: return null
        return if (Math.abs(hit.key - ts) <= 100_000_000L) hit.value else null
    }

    private fun onJpegImage(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            val base = nearestBase(img.timestamp)
            log("jpeg arrived ts=${img.timestamp} ${bytes.size / 1024} KB → ${base ?: "waiting for RAW name"}")
            if (base != null) saveJpegBytes(base, bytes) else pendingJpegs[img.timestamp] = bytes
        } catch (e: Exception) { log("jpeg: ${e.message}") } finally { img.close() }
    }

    /** Called once a RAW has been named: save any JPEG that arrived earlier for (about) the same frame. */
    private fun flushPendingJpeg(ts: Long, base: String) {
        val key = pendingJpegs.keys.minByOrNull { Math.abs(it - ts) } ?: return
        if (Math.abs(key - ts) <= 100_000_000L) pendingJpegs.remove(key)?.let { saveJpegBytes(base, it) }
        // Drop anything older than 5 s that never found its RAW.
        pendingJpegs.keys.filter { ts - it > 5_000_000_000L }.forEach { pendingJpegs.remove(it); log("jpeg ts=$it never matched a RAW; dropped") }
    }

    /** Does this JPEG carry an Ultra HDR gain map? Looks for the MPF multi-picture marker and the hdrgm XMP. */
    private fun hasGainMap(b: ByteArray): Boolean {
        fun find(needle: ByteArray): Boolean {
            outer@ for (i in 0..b.size - needle.size) {
                for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
        val mpf = find("MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        val hdrgm = find("hdrgm:".toByteArray(Charsets.ISO_8859_1)) || find("GainMap".toByteArray(Charsets.ISO_8859_1))
        return mpf && hdrgm
    }

    private fun saveJpegBytes(base: String, bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$base.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val gm = hasGainMap(bytes)
            log("saved $base.jpg (${bytes.size / 1024} KB) · Ultra HDR: " + (if (gm) "GAIN MAP PRESENT — real HDR" else "no gain map — plain JPEG"))
            onSaved(uri)
        } catch (e: Exception) { log("jpeg save: ${e.message}") }
    }

    /** True when Ultra HDR is wanted but cannot share a session with RAW: capture it as a second step. */
    private val hdrJpegSequential get() = false

    private var hdrReader: ImageReader? = null

    /** Second stage of one shutter press: rebuild as preview + JPEG_R, take the HDR JPEG, rebuild back. */
    private fun captureHdrJpeg(base: String) {
        val dev0 = device; val prev = previewSurface
        if (dev0 == null || prev == null || Build.VERSION.SDK_INT < 34) { busy = false; return }
        status("HDR JPEG…")
        try {
            val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(FORMAT_JPEG_R)?.toList().orEmpty()
            val same = sizes.filter { Math.abs(it.width.toFloat() / it.height - rawSize.width.toFloat() / rawSize.height) < 0.02f }
            val size = (same.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height }
            if (size == null) { busy = false; return }
            hdrReader?.close()
            hdrReader = ImageReader.newInstance(size.width, size.height, FORMAT_JPEG_R, 2).also { r ->
                r.setOnImageAvailableListener({ rr ->
                    rr.acquireNextImage()?.let { img ->
                        try {
                            val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                            saveJpegBytes(base + "_HDR", bytes)
                            log("HDR JPEG ${img.width}x${img.height} ${bytes.size / 1024} KB")
                        } catch (e: Exception) { log("hdr jpeg: ${e.message}") } finally { img.close() }
                    }
                    // Back to the normal RAW session.
                    handler.postDelayed({ busy = false; previewSurface?.let { open(lens, it) } }, 150)
                }, handler)
            }
            try { session?.close() } catch (_: Exception) {}
            session = null
            val outs = ArrayList<OutputConfiguration>()
            outs += OutputConfiguration(prev); outs += OutputConfiguration(hdrReader!!.surface)
            if (!directOpen) outs.forEach { it.setPhysicalCameraId(lens.physicalId) }
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s2: CameraCaptureSession) {
                    try {
                        val req = dev0.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(hdrReader!!.surface); applyControls(this)
                            set(CaptureRequest.JPEG_ORIENTATION, physChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
                            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                        }
                        s2.capture(req.build(), null, handler)
                    } catch (e: Exception) { log("hdr capture: ${e.message}"); busy = false; previewSurface?.let { open(lens, it) } }
                }
                override fun onConfigureFailed(s2: CameraCaptureSession) {
                    log("HDR JPEG session refused; Ultra HDR unavailable on this lens")
                    busy = false; previewSurface?.let { open(lens, it) }
                }
            })
            dev0.createCaptureSession(cfg)
        } catch (e: Exception) { log("hdr stage: ${e.message}"); busy = false; previewSurface?.let { open(lens, it) } }
    }

    // ---- vendor tags ----------------------------------------------------------------

    data class VendorTagSpec(val name: String, val scope: String, val type: String, val value: String, val lens: String = "all")

    private fun parseNums(v: String): List<String> = v.split(',', '/', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** Sets one vendor key on a request builder; logs if the driver rejects the key or type. */
    private fun applyVendorTag(b: CaptureRequest.Builder, t: VendorTagSpec) {
        try {
            val parts = parseNums(t.value)
            val isArray = parts.size > 1
            when (t.type) {
                "i32" -> if (isArray) b.set(CaptureRequest.Key(t.name, IntArray::class.java), parts.map { it.toInt() }.toIntArray())
                         else b.set(CaptureRequest.Key(t.name, Int::class.javaObjectType), parts[0].toInt())
                "i64" -> if (isArray) b.set(CaptureRequest.Key(t.name, LongArray::class.java), parts.map { it.toLong() }.toLongArray())
                         else b.set(CaptureRequest.Key(t.name, Long::class.javaObjectType), parts[0].toLong())
                "f32" -> if (isArray) b.set(CaptureRequest.Key(t.name, FloatArray::class.java), parts.map { it.toFloat() }.toFloatArray())
                         else b.set(CaptureRequest.Key(t.name, Float::class.javaObjectType), parts[0].toFloat())
                "f64" -> if (isArray) b.set(CaptureRequest.Key(t.name, DoubleArray::class.java), parts.map { it.toDouble() }.toDoubleArray())
                         else b.set(CaptureRequest.Key(t.name, Double::class.javaObjectType), parts[0].toDouble())
                "u8" -> if (isArray) b.set(CaptureRequest.Key(t.name, ByteArray::class.java), parts.map { it.toInt().toByte() }.toByteArray())
                        else b.set(CaptureRequest.Key(t.name, Byte::class.javaObjectType), parts[0].toInt().toByte())
            }
        } catch (e: Exception) { log("vendor tag ${t.name} rejected: ${e.javaClass.simpleName} ${e.message}") }
    }

    /** Reads back every configured tag from the capture result so the user can see what the driver actually did. */
    private fun echoVendorTags(result: TotalCaptureResult) {
        val tags = allTags().filter { it.name.isNotBlank() }
        if (tags.isEmpty() && activeOpmode == 0) return
        val sb = StringBuilder()
        if (activeOpmode != 0) sb.appendLine("opmode 0x${Integer.toHexString(activeOpmode)} · session ${if (directOpen) "direct" else "via $logicalId"}")
        for (t in tags) {
            val v: Any? = try {
                val r = metaFor(result)
                when (t.type) {
                    "i32" -> r.get(CaptureResult.Key(t.name, IntArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Int::class.javaObjectType))
                    "i64" -> r.get(CaptureResult.Key(t.name, LongArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Long::class.javaObjectType))
                    "f32" -> r.get(CaptureResult.Key(t.name, FloatArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Float::class.javaObjectType))
                    "f64" -> r.get(CaptureResult.Key(t.name, DoubleArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Double::class.javaObjectType))
                    else -> r.get(CaptureResult.Key(t.name, ByteArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Byte::class.javaObjectType))
                }
            } catch (e: Exception) { "<${e.javaClass.simpleName}>" }
            sb.appendLine("${t.name.substringAfterLast('.')} (${t.scope}/${t.type}/lens ${t.lens}) sent=${t.value} → result=${v ?: "not reported"}")
        }
        val text = sb.toString().trim()
        log(text); onVendorEcho(text)
    }

    /** Vendor keys the driver advertises for the current lens/path: name -> session/request/both. */
    /** Short names of the vendor tags actually being sent for the current lens (for the viewfinder caption). */
    fun activeTagLabels(): List<String> = allTags().map { it.name.substringAfterLast('.') + "=" + it.value }

    fun exposedVendorKeys(): List<Pair<String, String>> {
        if (!hasCharacteristics) return emptyList()
        val chars = listOfNotNull(physChars, if (!directOpen) runCatching { cm.getCameraCharacteristics(logicalId) }.getOrNull() else null)
        val out = LinkedHashMap<String, String>()
        for (ch in chars) {
            val sess = runCatching { ch.availableSessionKeys?.map { it.name } }.getOrNull().orEmpty().toSet()
            val req = runCatching { ch.availableCaptureRequestKeys?.map { it.name } }.getOrNull().orEmpty()
            for (k in req) if (!k.startsWith("android.")) out[k] = if (k in sess) "session" else "request"
            for (k in sess) if (!k.startsWith("android.") && k !in out) out[k] = "session"
        }
        return out.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    // ---- lifecycle ----------------------------------------------------------------

    fun close() = handler.post { closeInternal() }

    /** Reopen the last lens on the last surface (used on resume after Android took the camera away). */
    fun reopenIfNeeded() = handler.post {
        val surf = previewSurface
        if (device == null && surf != null && surf.isValid) { log("reopening after resume"); open(lens, surf) }
    }

    private fun closeInternal() {
        openGeneration++            // any open still in flight is now out of date
        opening = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        while (zsl.isNotEmpty()) zsl.removeFirst().close()
        zslResults.clear()
        rawReader?.close(); rawReader = null
        jpegReader?.close(); jpegReader = null
        hdrReader?.close(); hdrReader = null
        baseNames.clear(); pendingJpegs.clear()
        pendingImages.values.forEach { it.close() }; pendingImages.clear(); pendingResults.clear()
        burst = null
    }

    fun destroy() { close(); handler.post { zslWriter.shutdown(); thread.quitSafely() } }

    private fun status(s: String) { Log.i("Latent", s); onStatus(s) }
    internal fun log(s: String) { Log.i("Latent", s); onLog(s) }

    /** Accumulates a burst with alignment: frame 1 is the reference, later frames are shifted and tile-checked. */
    private inner class BurstJob(val frames: Int, val w: Int, val h: Int, val startNs: Long) {
        var received = 0; var failed = 0
        var firstTs = 0L; var lastTs = 0L
        var firstResult: TotalCaptureResult? = null
        var firstName = ""
        val tsList = ArrayList<Long>()
        val shifts = ArrayList<String>()
        private val sum = IntArray(w * h)
        private val count = ShortArray((w / 64) * (h / 64))
        private var ref: ShortArray? = null
        private var refPyr: Align.Pyramid? = null
        private val cur = ShortArray(w * h)
        var tilesAccepted = 0; var tilesTotal = 0

        private fun readInto(img: Image, dst: ShortArray) {
            val plane = img.planes[0]
            val sb = plane.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            val rowStride = plane.rowStride
            for (y in 0 until h) { sb.position(y * rowStride / 2); sb.get(dst, y * w, w) }
        }

        fun accept(img: Image, result: TotalCaptureResult) {
            val ts = img.timestamp
            tsList += ts
            if (received == 0) {
                firstTs = ts; firstResult = result
                val base = fileBase("BURST1")
                firstName = "$base.dng"
                baseNames[ts] = base
                flushPendingJpeg(ts, base)   // a JPEG of the first frame, if JPEGs are on (the RAW is in the burst roll)
                val r = ShortArray(w * h); readInto(img, r); ref = r
                refPyr = Align.pyramid(r, w, h)
                // Reference counts as the first accepted sample everywhere.
                for (i in sum.indices) sum[i] = r[i].toInt() and 0xFFFF
                for (i in count.indices) count[i] = 1
                tilesAccepted += count.size; tilesTotal += count.size
                shifts += "0,0"
            } else {
                readInto(img, cur)
                val pyr = Align.pyramid(cur, w, h)
                val (dx0, dy0) = Align.findShift(refPyr!!, pyr)
                val sx = dx0 * 2; val sy = dy0 * 2   // level-0 units are 2x2 cells
                val (acc, tot) = Align.accumulate(ref!!, cur, w, h, sx, sy, sum, count)
                tilesAccepted += acc; tilesTotal += tot
                shifts += "$sx,$sy(${acc}/${tot})"
            }
            lastTs = ts
            received++
            status("Burst: frame $received/$frames")
        }

        fun result(): ShortArray = Align.finish(ref!!, sum, count, w, h)
    }
}
