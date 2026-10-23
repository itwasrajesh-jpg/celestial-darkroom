package com.celestial.latent.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Finds vendor keys per camera path and lens, and actively tests candidate keys to see what they change.
 * Runs on its own thread; the live camera must be closed while a deep probe runs.
 */
class VendorProbe(private val context: Context) {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    companion object {
        val PATHS = listOf("0", "6", "7", "direct")
        /** Keys known to crash the camera service on this device; skipped unless "all" is forced. */
        val DANGEROUS = setOf("org.codeaurora.qcamera3.sessionParameters.enableQLL")
        private val ZOOMY = Regex("zoom|crop|remosaic|qcfa|insensor|in_sensor", RegexOption.IGNORE_CASE)
        private val INTERESTING = Regex("zoom|remosaic|qcfa|quadra|insensor|in_sensor|crop|hdr|bit|dcg|superres|super_res|highres|high_res|fullsize|full_size|binning|sensor.?mode|opmode|raw|resolution|native", RegexOption.IGNORE_CASE)
    }

    /** Passive: the full key matrix, MotionCam notation. */
    fun enumerate(): String {
        val sb = StringBuilder("CELESTIAL DARKROOM VENDOR KEY MAP\n")
        val candidates = LinkedHashSet<String>()
        for (path in PATHS) {
            for (lens in Lenses.ALL) {
                val (label, keys) = keysFor(path, lens) ?: continue
                sb.appendLine("== $label (${lens.name})")
                sb.appendLine("  session keys (${keys.count { it.value == "session" }}), request keys (${keys.count { it.value == "request" }})")
                keys.forEach { (k, scope) ->
                    val star = if (INTERESTING.containsMatchIn(k)) " ★" else ""
                    if (star.isNotEmpty()) candidates += k
                    sb.appendLine("    $k · $scope$star")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("★ candidates by name (${candidates.size}):")
        candidates.forEach { sb.appendLine("  $it") }
        return sb.toString()
    }

    /** Keys for one path+lens: name -> "session"/"request". Null if the path does not exist. */
    fun keysFor(path: String, lens: Lens): Pair<String, Map<String, String>>? {
        val direct = path == "direct"
        val chars = ArrayList<CameraCharacteristics>()
        try {
            if (direct) chars += cm.getCameraCharacteristics(lens.physicalId)
            else {
                val lc = cm.getCameraCharacteristics(path)
                if (lens.physicalId !in lc.physicalCameraIds) return null
                chars += lc; chars += cm.getCameraCharacteristics(lens.physicalId)
            }
        } catch (t: Throwable) { return null }
        val out = LinkedHashMap<String, String>()
        for (ch in chars) {
            val sess = runCatching { ch.availableSessionKeys?.map { it.name } }.getOrNull().orEmpty().toSet()
            val req = runCatching { ch.availableCaptureRequestKeys?.map { it.name } }.getOrNull().orEmpty()
            for (k in req) if (!k.startsWith("android.")) out[k] = if (k in sess) "session" else "request"
            for (k in sess) if (!k.startsWith("android.") && k !in out) out[k] = "session"
        }
        val label = if (direct) lens.physicalId else "$path/${lens.physicalId}"
        return label to out.toSortedMap()
    }

    fun candidateKeys(path: String, lens: Lens): List<Pair<String, String>> =
        keysFor(path, lens)?.second?.filter { INTERESTING.containsMatchIn(it.key) }?.map { it.key to it.value } ?: emptyList()

    /**
     * Active: for each candidate, open the camera, set key=1 (i32) as a session parameter and in the request,
     * grab one RAW frame, record what changed against a baseline run with no key.
     */
    @SuppressLint("MissingPermission")
    fun deepProbe(path: String, lens: Lens, keys: List<Pair<String, String>>, progress: (String) -> Unit): String {
        val sb = StringBuilder("CELESTIAL DARKROOM DEEP PROBE · path=$path lens=${lens.physicalId} (${lens.name})\n")
        progress("baseline…")
        val base = runOneRetry(path, lens, null, 1f)
        sb.appendLine("baseline @1x: $base")
        val base2 = runOneRetry(path, lens, null, 2f)
        sb.appendLine("baseline @2x (digital): $base2")
        keys.forEachIndexed { i, (k, scope) ->
            if (k in DANGEROUS) { sb.appendLine("$k ($scope): skipped (crashes camera service)"); return@forEachIndexed }
            val zoomy = ZOOMY.containsMatchIn(k)
            progress("${i + 1}/${keys.size} $k" + if (zoomy) " @2x" else "")
            val ref = if (zoomy) base2 else base
            val r = runOneRetry(path, lens, k, if (zoomy) 2f else 1f)
            val changed = if (r.ok && ref.ok && (r.rawW != ref.rawW || r.rawH != ref.rawH || r.crop != ref.crop || r.focal != ref.focal)) " ← CHANGED vs baseline" else ""
            sb.appendLine("$k ($scope)${if (zoomy) " @2x" else ""}: $r$changed")
        }
        return sb.toString()
    }

    data class Outcome(
        val ok: Boolean, val note: String, val rawW: Int = 0, val rawH: Int = 0, val crop: String = "", val focal: String = "", val echo: String = "",
        val whiteLevel: String = "", val blackLevel: String = "", val exposure: String = "", val frameNs: String = "", val thumb: FloatArray? = null,
        val shadowNoise: Float = 0f, val clipped: Float = 0f, val meanLevel: Float = 0f, val exposureNs: Long = 0, val iso: Int = 0,
    ) {
        override fun toString() = if (ok) "ok raw=${rawW}x$rawH echo=$echo white=$whiteLevel black=$blackLevel exp=$exposure iso=$iso · shadowNoise=%.2f clipped=%.3f%% mean=%.0f".format(shadowNoise, clipped * 100, meanLevel) else "FAILED: $note"
    }

    /** Shadow noise (mean std of the darkest flat blocks, one CFA channel), clipped fraction, mean level. */
    private fun metrics(img: android.media.Image, white: Int): Triple<Float, Float, Float> {
        val w = img.width; val h = img.height
        val plane = img.planes[0]; val stride = plane.rowStride
        val sb = plane.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val bs = 32
        val means = ArrayList<Float>(); val stds = ArrayList<Float>()
        var clipped = 0L; var total = 0L; var sumAll = 0.0
        val row = ShortArray(w)
        val bx = w / bs; val by = h / bs
        val acc = DoubleArray(bx); val acc2 = DoubleArray(bx); val n = IntArray(bx)
        for (y in 0 until by * bs step 2) {
            sb.position(y * stride / 2); sb.get(row, 0, w)
            for (x in 0 until bx * bs step 2) {
                val v = (row[x].toInt() and 0xFFFF); val b = x / bs
                acc[b] += v.toDouble(); acc2[b] += v.toDouble() * v; n[b]++
                if (v >= white - 2) clipped++
                total++; sumAll += v
            }
            if ((y + 2) % bs == 0) {
                for (b in 0 until bx) if (n[b] > 8) {
                    val m = acc[b] / n[b]; val v = acc2[b] / n[b] - m * m
                    means += m.toFloat(); stds += Math.sqrt(Math.max(v, 0.0)).toFloat()
                    acc[b] = 0.0; acc2[b] = 0.0; n[b] = 0
                }
            }
        }
        if (means.isEmpty()) return Triple(0f, 0f, 0f)
        // darkest 15% of blocks by mean, then the flattest half of those (lowest std) to avoid texture
        val idx = means.indices.sortedBy { means[it] }
        val dark = idx.take(Math.max(4, idx.size * 15 / 100)).sortedBy { stds[it] }
        val flat = dark.take(Math.max(2, dark.size / 2))
        val shadow = flat.map { stds[it] }.average().toFloat()
        return Triple(shadow, clipped.toFloat() / total, (sumAll / total).toFloat())
    }

    /** 64x48 grey thumbnail of a RAW frame (mean of each 2x2 CFA cell, then box-downsampled). */
    private fun thumbnail(img: android.media.Image): FloatArray {
        val w = img.width; val h = img.height
        val plane = img.planes[0]; val stride = plane.rowStride
        val sb = plane.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val tw = 64; val th = 48
        val out = FloatArray(tw * th)
        val cw = w / tw; val chh = h / th
        val row = ShortArray(w)
        val acc = FloatArray(tw); val cnt = IntArray(tw)
        for (y in 0 until h step 4) {
            sb.position(y * stride / 2); sb.get(row, 0, w)
            val ty = (y / chh).coerceAtMost(th - 1)
            for (x in 0 until w step 4) { val tx = (x / cw).coerceAtMost(tw - 1); acc[tx] += (row[x].toInt() and 0xFFFF).toFloat(); cnt[tx]++ }
            if ((y + 4) / chh != ty || y + 4 >= h) { for (tx in 0 until tw) { out[ty * tw + tx] += if (cnt[tx] > 0) acc[tx] / cnt[tx] else 0f; acc[tx] = 0f; cnt[tx] = 0 } }
        }
        return out
    }

    /**
     * Compare a thumbnail to the baseline: is it the same view, the baseline's centre 2x crop, or
     * something else? Compared by *pattern*, not level: each thumbnail has its own mean taken off
     * and is divided by its own spread, so a mode that only changes brightness, black level or
     * contrast still matches its own view. (Dividing by the mean alone left the RAW black level
     * in, so a darker same-view frame scored badly and once passed for a crop — the 3× sweep's
     * mode 4, which on the phone only changed brightness.) A crop must also beat "same view" by
     * a clear margin, not by a hair.
     */
    private fun classify(t: FloatArray?, base: FloatArray?): String {
        if (t == null || base == null) return "?"
        fun z(a: FloatArray): FloatArray {
            val m = a.average().toFloat()
            val sd = Math.sqrt(a.fold(0.0) { acc, v -> acc + (v - m) * (v - m) } / a.size).toFloat().coerceAtLeast(1e-6f)
            return FloatArray(a.size) { (a[it] - m) / sd }
        }
        fun match(p: FloatArray, q: FloatArray): Float { var s = 0f; for (i in p.indices) s += p[i] * q[i]; return s / p.size }
        val a = z(t)
        // centre 2x crop of baseline, upsampled to 64x48
        val c = FloatArray(64 * 48) { i -> val x = i % 64; val y = i / 64; base[(12 + y / 2) * 64 + (16 + x / 2)] }
        val mFull = match(a, z(base)); val mCrop = match(a, z(c))
        return when {
            mFull > 0.85f && mFull >= mCrop -> "same view as baseline (match %.2f)".format(mFull)
            mCrop > 0.75f && mCrop > mFull + 0.25f -> "≈ centre 2x crop of baseline (match %.2f vs %.2f) ← IN-SENSOR CROP".format(mCrop, mFull)
            else -> "different from both (view %.2f, crop %.2f)".format(mFull, mCrop)
        }
    }

    /** Runs a test; if the camera service is restarting (no characteristics), waits and retries once. */
    private fun runOneRetry(path: String, lens: Lens, key: String?, zoom: Float, value: Int = 1): Outcome {
        var r = runOne(path, lens, key, zoom, value)
        if (!r.ok && (r.note.startsWith("no characteristics") || r.note.startsWith("open") || r.note == "disconnected")) {
            Thread.sleep(4000)
            r = runOne(path, lens, key, zoom, value)
            if (!r.ok) Thread.sleep(3000)
        }
        return r
    }

    /**
     * Quality probe: locked exposure, same scene, measure shadow noise / clipping / white level with each key;
     * retry timed-out keys with other stream layouts; test RAW routing keys with in-sensor zoom at 2x.
     */
    fun qualityProbe(path: String, lens: Lens, progress: (String) -> Unit): String {
        val P = "org.codeaurora.qcamera3.sessionParameters."
        val quality = listOf(P + "EnableHDRDCGMode", P + "inSensorSHDRMode", P + "numHDRexposure", P + "EnableMFHDR", P + "EnableQHDR", P + "EnableXHDR", P + "SnapshotHDRMode", P + "HDRModePreference")
        val timedOut = listOf(P + "EnableIdealRAW", P + "EnableSHDR", P + "HDRMode", P + "EnableAutoHDR")
        val routing = listOf(P + "RawCbSourceType" to listOf(0, 1, 2), P + "McxRawCallbackInfo" to listOf(1))
        val sb = StringBuilder("CELESTIAL DARKROOM QUALITY PROBE · path=$path lens=${lens.physicalId} (${lens.name})\n")
        progress("metering…")
        val auto = runOneRetry(path, lens, null, 1f)
        if (!auto.ok || auto.exposureNs == 0L) return sb.appendLine("could not meter: $auto").toString()
        val lock = auto.exposureNs to auto.iso
        sb.appendLine("locked exposure: ${auto.exposure} ISO ${auto.iso}")
        progress("baseline…")
        val base = runOne(path, lens, null, 1f, 1, lock)
        sb.appendLine("baseline: $base")
        fun verdict(r: Outcome): String {
            if (!r.ok || !base.ok) return ""
            val parts = ArrayList<String>()
            if (r.whiteLevel != base.whiteLevel || r.blackLevel != base.blackLevel) parts += "LEVELS CHANGED"
            val nr = if (base.shadowNoise > 0f) r.shadowNoise / base.shadowNoise else 1f
            if (nr < 0.8f) parts += "shadows %.0f%% cleaner".format((1 - nr) * 100) else if (nr > 1.25f) parts += "shadows %.0f%% noisier".format((nr - 1) * 100)
            if (base.clipped > 0.001f && r.clipped < base.clipped * 0.5f) parts += "clipping halved"
            if (Math.abs(r.meanLevel - base.meanLevel) > base.meanLevel * 0.15f) parts += "mean level shifted"
            return if (parts.isEmpty()) "no measurable change" else "← " + parts.joinToString(", ")
        }
        sb.appendLine("\n-- quality keys (value 1, session+request, locked exposure)")
        for (k in quality) {
            progress(k.substringAfterLast('.'))
            val r = runOneRetry2(path, lens, k, 1f, 1, lock)
            sb.appendLine("${k.substringAfterLast('.')}: $r ${verdict(r)}")
        }
        sb.appendLine("\n-- keys that timed out before, with other stream layouts")
        for (k in timedOut) for (v in listOf("raw", "raw10", "raw+yuv")) {
            progress("${k.substringAfterLast('.')} [$v]")
            val r = runOneRetry2(path, lens, k, 1f, 1, lock, v)
            sb.appendLine("${k.substringAfterLast('.')} [$v]: $r ${if (r.ok) verdict(r) else ""}")
        }
        sb.appendLine("\n-- RAW routing with in-sensor zoom flag at 2x (view test)")
        val isz = P + "EnableInsensorZoom"
        val base2 = runOne(path, lens, null, 2f, 1, lock)
        val iszOnly = runOne(path, lens, isz, 2f, 1, lock)
        sb.appendLine("ISZ only @2x: ${classify(iszOnly.thumb, base.thumb)}")
        for ((k, vals) in routing) for (v in vals) {
            progress("${k.substringAfterLast('.')}=$v @2x")
            val r = runOne(path, lens, isz, 2f, 1, lock, "raw", listOf(k to v))
            sb.appendLine("ISZ + ${k.substringAfterLast('.')}=$v @2x: ${if (r.ok) classify(r.thumb, base.thumb) else r.toString()}")
        }
        return sb.toString()
    }

    private fun runOneRetry2(path: String, lens: Lens, key: String?, zoom: Float, value: Int, lock: Pair<Long, Int>?, variant: String = "raw"): Outcome {
        var r = runOne(path, lens, key, zoom, value, lock, variant)
        if (!r.ok && (r.note.startsWith("no characteristics") || r.note.startsWith("open") || r.note == "disconnected")) {
            Thread.sleep(4000); r = runOne(path, lens, key, zoom, value, lock, variant)
        }
        return r
    }

    /**
     * A/B test: same scene, locked exposure, one frame with the key and one without, repeated,
     * and the RAW measured each time. This is the only honest evidence that a mode does anything.
     */
    fun abTest(path: String, lens: Lens, progress: (String) -> Unit): String {
        val P = "org.codeaurora.qcamera3.sessionParameters."
        val modes = listOf(
            "DCG" to (P + "EnableHDRDCGMode"),
            "Staggered HDR" to (P + "inSensorSHDRMode"),
            "Multi-frame HDR" to (P + "EnableMFHDR"),
            "Snapshot HDR" to (P + "SnapshotHDRMode"),
            "Multi-frame NR" to (P + "enableMFNR"),
            "Ideal RAW" to (P + "EnableIdealRAW"),
        )
        val sb = StringBuilder("CELESTIAL DARKROOM A/B TEST · path=$path lens=${lens.physicalId} (${lens.name})\nKeep the phone still and the scene unchanged.\n")
        progress("metering…")
        val auto = runOneRetry(path, lens, null, 1f)
        if (!auto.ok || auto.exposureNs == 0L) return sb.appendLine("could not meter: $auto").toString()
        val lock = auto.exposureNs to auto.iso
        sb.appendLine("locked at ${auto.exposure}, ISO ${auto.iso}\n")
        for ((label, key) in modes) {
            progress(label)
            val off1 = runOne(path, lens, null, 1f, 1, lock)
            val on = runOneRetry2(path, lens, key, 1f, 1, lock)
            val off2 = runOne(path, lens, null, 1f, 1, lock)
            if (!on.ok) { sb.appendLine("$label: FAILED (${on.note})"); continue }
            if (!off1.ok || !off2.ok) { sb.appendLine("$label: baseline failed"); continue }
            // Two "off" frames give us the natural variation, so we only call a change real if it exceeds it.
            val offNoise = (off1.shadowNoise + off2.shadowNoise) / 2
            val drift = Math.abs(off1.shadowNoise - off2.shadowNoise)
            val offClip = (off1.clipped + off2.clipped) / 2
            val noiseChange = if (offNoise > 0) (on.shadowNoise - offNoise) / offNoise * 100 else 0f
            val real = Math.abs(on.shadowNoise - offNoise) > Math.max(drift * 1.5f, offNoise * 0.12f)
            val levels = if (on.whiteLevel != off1.whiteLevel || on.blackLevel != off1.blackLevel) " · LEVELS CHANGED (${off1.whiteLevel}→${on.whiteLevel})" else ""
            val clipTxt = if (offClip > 0.0005f) " · clipping %.2f%%→%.2f%%".format(offClip * 100, on.clipped * 100) else ""
            sb.appendLine("$label: shadow noise %.2f (off, ±%.2f) → %.2f (on) = %+.0f%% · %s%s%s".format(
                offNoise, drift, on.shadowNoise, noiseChange, if (real) (if (noiseChange < 0) "REAL IMPROVEMENT" else "REAL CHANGE (worse)") else "no measurable effect", levels, clipTxt))
        }
        sb.appendLine("\nA mode with 'no measurable effect' is being ignored by the driver for third-party apps.")
        return sb.toString()
    }

    /**
     * 80a proof: zero lag in normal mode. On one lens at a locked exposure: how fast the viewfinder runs
     * without and with the RAW stream, then five presses about a second apart. Each press saves two DNGs of
     * the same moment to Pictures/Latent probe — the kept frame nearest the press (_ZSL, zero lag) and a
     * fresh still capture (_STILL, today's way) — and logs how far each is from the press by the sensor's
     * own clock, and whether the two RAWs match. Normal shooting is not changed by any of it.
     */
    @SuppressLint("MissingPermission")
    fun zeroLagTest(path: String, lens: Lens, zoom: Int, progress: (String) -> Unit): String {
        val mode = if (zoom > 1) Lenses.sensorZoomMode(lens, zoom.toFloat()) else null
        val label = lens.name + if (mode != null) " · in-sensor ×$zoom (mode $mode)" else ""
        val sb = StringBuilder("CELESTIAL DARKROOM ZERO LAG A/B · path=$path lens=${lens.physicalId} ($label)\n" +
            "Keep the phone still, pointed at something with detail and some shadow.\n")
        if (zoom > 1 && mode == null) return sb.appendLine("This lens has no in-sensor ×$zoom.").toString()
        val direct = path == "direct"
        val chars = try { cm.getCameraCharacteristics(lens.physicalId) } catch (t: Throwable) { return sb.appendLine("no characteristics: ${t.message}").toString() }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return sb.appendLine("no stream map").toString()
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return sb.appendLine("this lens has no RAW").toString()
        // a stand-in for the viewfinder, drained like a screen would: 4:3, at most 1440 wide
        val viewSize = map.getOutputSizes(ImageFormat.PRIVATE)?.filter { it.width <= 1440 && Math.abs(it.width * 3 - it.height * 4) < 8 }
            ?.maxByOrNull { it.width.toLong() * it.height } ?: android.util.Size(1280, 960)
        val realtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        sb.appendLine("RAW ${rawSize.width}x${rawSize.height} · viewfinder stand-in ${viewSize.width}x${viewSize.height} · sensor clock ${if (realtime) "realtime (lags are exact)" else "NOT realtime (lags cannot be measured)"}")

        val view = ImageReader.newInstance(viewSize.width, viewSize.height, ImageFormat.PRIVATE, 4)
        view.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 10)
        val lock = Object()
        val images = java.util.TreeMap<Long, android.media.Image>()     // the kept stream frames, and stills on their way
        val results = java.util.TreeMap<Long, TotalCaptureResult>()
        val keep = HashSet<Long>()                                        // frames reserved for saving
        val frameTs = ArrayList<Long>()                                   // viewfinder frame times, for the frame rate
        raw.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            synchronized(lock) {
                images[img.timestamp] = img
                while (images.size > 5) { val k = images.keys.firstOrNull { it !in keep } ?: break; images.remove(k)?.close() }
            }
        }, handler)
        val streamCb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                synchronized(lock) {
                    results[ts] = res; frameTs += ts
                    while (results.size > 40) { val k = results.keys.firstOrNull { it !in keep } ?: break; results.remove(k) }
                }
            }
        }
        fun phys(r: CaptureResult): CaptureResult = if (direct) r else ((r as? TotalCaptureResult)?.physicalCameraResults?.get(lens.physicalId) ?: r)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var failure: String? = null
        val ready = CountDownLatch(1)
        fun cleanup() {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            synchronized(lock) { images.values.forEach { it.close() }; images.clear() }
            Thread.sleep(300); raw.close(); view.close(); Thread.sleep(400)
        }
        progress("opening the ${lens.name}…")
        try {
            cm.openCamera(if (direct) lens.physicalId else path, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val outs = listOf(OutputConfiguration(view.surface), OutputConfiguration(raw.surface))
                        if (!direct) outs.forEach { it.setPhysicalCameraId(lens.physicalId) }
                        cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
                            override fun onConfigureFailed(s: CameraCaptureSession) { failure = "session refused"; ready.countDown() }
                        }))
                    } catch (t: Throwable) { failure = "session: ${t.message}"; ready.countDown() }
                }
                override fun onDisconnected(cam: CameraDevice) { failure = "disconnected"; ready.countDown() }
                override fun onError(cam: CameraDevice, error: Int) { failure = "device error $error"; ready.countDown() }
            }, handler)
        } catch (t: Throwable) { return sb.appendLine("open: ${t.message}").toString() }
        if (!ready.await(8, TimeUnit.SECONDS) || failure != null || session == null) { cleanup(); return sb.appendLine("could not start: ${failure ?: "timeout"}").toString() }
        val cam = device!!; val s = session!!

        fun request(template: Int, withRaw: Boolean, exp: Pair<Long, Int>?): CaptureRequest.Builder = cam.createCaptureRequest(template).apply {
            addTarget(view.surface); if (withRaw) addTarget(raw.surface)
            if (mode != null) set(CaptureRequest.Key(Lenses.SENSOR_MODE_KEY, Int::class.javaObjectType), mode)
            if (exp != null) {
                set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp.first); set(CaptureRequest.SENSOR_SENSITIVITY, exp.second)
                set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(33_333_333L, exp.first))
            }
        }
        /** Viewfinder frames per second over 2.5 s, after half a second to settle. */
        fun fps(): Float {
            Thread.sleep(500); synchronized(lock) { frameTs.clear() }
            Thread.sleep(2500)
            synchronized(lock) { return if (frameTs.size < 2) 0f else (frameTs.size - 1) * 1e9f / (frameTs.last() - frameTs.first()) }
        }
        try {
            // 1. let the phone meter, then lock what it chose so every frame below is comparable
            progress("metering…")
            s.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW, false, null).build(), streamCb, handler)
            Thread.sleep(2000)
            val metered = synchronized(lock) { results.lastEntry()?.value }?.let { phys(it) }
            val exp = metered?.let { (it.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) to (it.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) }
            if (exp == null || exp.first == 0L) { cleanup(); return sb.appendLine("could not meter").toString() }
            sb.appendLine("locked at ${"%.2f".format(exp.first / 1e6)} ms, ISO ${exp.second}\n")

            // 2. the viewfinder without and with the RAW stream
            progress("viewfinder speed without RAW…")
            s.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW, false, exp).build(), streamCb, handler)
            val fpsOff = fps()
            progress("viewfinder speed with RAW…")
            s.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW, true, exp).build(), streamCb, handler)
            val fpsOn = fps()
            val slower = if (fpsOff > 0f) (1 - fpsOn / fpsOff) * 100 else 0f
            sb.appendLine("VIEWFINDER: %.1f fps without RAW · %.1f fps with RAW (%s)".format(fpsOff, fpsOn,
                if (slower > 1f) "%.0f%% slower".format(slower) else "no slower"))

            // 3. five presses
            sb.appendLine("\nPRESSES (ms from the press, by the sensor clock; ZSL is before the press, STILL after it)")
            val rnd = java.util.Random()
            val zslLags = ArrayList<Long>(); val stillLags = ArrayList<Long>()
            val noiseRatios = ArrayList<Float>(); val levelRatios = ArrayList<Float>()
            val diffs = LinkedHashSet<String>()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            for (i in 1..5) {
                progress("press $i of 5…")
                Thread.sleep(700L + rnd.nextInt(600))
                val pressNs = android.os.SystemClock.elapsedRealtimeNanos()
                val zslTs = synchronized(lock) {
                    images.keys.filter { it in results && (!realtime || it <= pressNs) }.maxOrNull()?.also { keep += it }
                }
                var stillTs = 0L
                var stillRes: TotalCaptureResult? = null
                val stillDone = CountDownLatch(1)
                s.capture(request(CameraDevice.TEMPLATE_STILL_CAPTURE, true, exp).build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(ss: CameraCaptureSession, rq: CaptureRequest, timestamp: Long, frame: Long) { synchronized(lock) { keep += timestamp } }
                    override fun onCaptureCompleted(ss: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                        stillTs = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L; stillRes = res; stillDone.countDown()
                    }
                    override fun onCaptureFailed(ss: CameraCaptureSession, rq: CaptureRequest, f: android.hardware.camera2.CaptureFailure) { stillDone.countDown() }
                }, handler)
                stillDone.await(4, TimeUnit.SECONDS)
                // the still's RAW can land a moment after its result
                var zslImg: android.media.Image? = null; var stillImg: android.media.Image? = null; var zslRes: TotalCaptureResult? = null
                for (t in 0 until 20) {
                    synchronized(lock) {
                        if (zslImg == null && zslTs != null) { zslImg = images.remove(zslTs); zslRes = results[zslTs] }
                        if (stillImg == null && stillTs != 0L) stillImg = images.remove(stillTs)
                    }
                    if ((zslImg != null || zslTs == null) && (stillImg != null || stillTs == 0L)) break
                    Thread.sleep(50)
                }
                synchronized(lock) { keep.clear() }
                val zi = zslImg; val si = stillImg; val zr = zslRes; val sr = stillRes
                if (zi == null || si == null || zr == null || sr == null) {
                    sb.appendLine("press $i: missing ${if (zi == null || zr == null) "the kept frame" else "the still"}"); zi?.close(); si?.close(); continue
                }
                val zLag = (pressNs - zslTs!!) / 1_000_000; val sLag = (stillTs - pressNs) / 1_000_000
                if (realtime) { zslLags += zLag; stillLags += sLag }
                val zp = phys(zr); val sp = phys(sr)
                val base = "LATENT_PROBE_${stamp}_${lens.physicalId}${if (mode != null) "_x$zoom" else ""}_$i"
                val savedZ = saveProbeDng("${base}_ZSL.dng", chars, zp, zi); val savedS = saveProbeDng("${base}_STILL.dng", chars, sp, si)
                val (zNoise, _, zMean) = runCatching { metrics(zi, white) }.getOrDefault(Triple(0f, 0f, 0f))
                val (sNoise, _, sMean) = runCatching { metrics(si, white) }.getOrDefault(Triple(0f, 0f, 0f))
                zi.close(); si.close()
                if (sNoise > 0f) noiseRatios += zNoise / sNoise
                if (sMean > 0f) levelRatios += zMean / sMean
                fun <T> cmp(name: String, k: CaptureResult.Key<T>, show: (T?) -> String = { it.toString() }) {
                    val a = show(zp.get(k)); val b = show(sp.get(k)); if (a != b) diffs += "$name: ZSL $a · STILL $b"
                }
                cmp("black level", CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) { it?.joinToString() ?: "-" }
                cmp("white level", CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                cmp("exposure", CaptureResult.SENSOR_EXPOSURE_TIME)
                cmp("ISO", CaptureResult.SENSOR_SENSITIVITY)
                cmp("lens shading", CaptureResult.SHADING_MODE)
                cmp("shading map in DNG", CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE)
                cmp("hot pixel", CaptureResult.HOT_PIXEL_MODE)
                cmp("noise reduction", CaptureResult.NOISE_REDUCTION_MODE)
                cmp("stabiliser (OIS)", CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                cmp("sensor mode", CaptureResult.Key(Lenses.SENSOR_MODE_KEY, Int::class.javaObjectType)) { it?.toString() ?: "not reported" }
                sb.appendLine("press $i: ZSL ${if (realtime) "$zLag ms before" else "?"} · STILL ${if (realtime) "$sLag ms after" else "?"} · " +
                    "shadow noise %.2f vs %.2f · mean %.0f vs %.0f · %s".format(zNoise, sNoise, zMean, sMean,
                        if (savedZ && savedS) "both DNGs saved" else "SAVE FAILED"))
            }

            // 4. summary
            fun median(v: List<Long>) = if (v.isEmpty()) "?" else v.sorted()[v.size / 2].toString()
            sb.appendLine("\nSUMMARY")
            sb.appendLine("today's way (STILL): the frame is taken ${median(stillLags)} ms after the press (median)")
            sb.appendLine("zero lag (ZSL): the frame is from ${median(zslLags)} ms before the press (median)")
            sb.appendLine("the JPEG in normal mode is the still capture, so it lands at the STILL time")
            if (noiseRatios.isNotEmpty()) sb.appendLine("ZSL vs STILL: shadow noise %+.1f%%, level %+.1f%%".format((noiseRatios.average() - 1) * 100, (levelRatios.average() - 1) * 100))
            sb.appendLine(if (diffs.isEmpty()) "settings: identical in every press" else "settings that differ:\n  " + diffs.joinToString("\n  "))
            sb.appendLine("\nDNGs are in Pictures/Latent probe (names end _ZSL and _STILL). Send me those with this text.")
        } catch (t: Throwable) {
            sb.appendLine("stopped: ${t.javaClass.simpleName} ${t.message}")
        } finally { cleanup() }
        return sb.toString()
    }

    /** One probe DNG into Pictures/Latent probe (kept out of the roll, which reads DCIM/Latent). */
    private fun saveProbeDng(name: String, chars: CameraCharacteristics, meta: CaptureResult, img: android.media.Image): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Latent probe")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        android.hardware.camera2.DngCreator(chars, meta).use { dng ->
            dng.setOrientation(android.media.ExifInterface.ORIENTATION_ROTATE_90)
            resolver.openOutputStream(uri)!!.use { dng.writeImage(it, img) }
        }
        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (t: Throwable) { android.util.Log.e("Latent", "probe DNG $name", t); false }

    /** Sweep sensor mode indices via sensor_meta_data.current_mode, at a locked exposure. */
    @SuppressLint("MissingPermission")
    fun sensorModeSweep(path: String, lens: Lens, from: Int, to: Int, progress: (String) -> Unit): String {
        val key = "org.codeaurora.qcamera3.sensor_meta_data.current_mode"
        val sb = StringBuilder("CELESTIAL DARKROOM SENSOR MODE SWEEP · path=$path lens=${lens.physicalId} (${lens.name}) · $key\n")
        progress("metering…")
        val auto = runOneRetry(path, lens, null, 1f)
        if (!auto.ok || auto.exposureNs == 0L) return sb.appendLine("could not meter: $auto").toString()
        val lock = auto.exposureNs to auto.iso
        sb.appendLine("locked at ${auto.exposure}, ISO ${auto.iso} — frames are comparable\n")
        val base = runOne(path, lens, null, 1f, 1, lock)
        sb.appendLine("baseline: $base")
        var consecutiveFails = 0
        val crashed = ArrayList<Int>()
        for (m in from..to) {
            if (consecutiveFails >= 5) { sb.appendLine("\nstopped at mode $m after 5 failures in a row — the camera service is unhappy; rerun from here if you want the rest"); break }
            progress("mode $m / $to")
            var r = runOne(path, lens, key, 1f, m, lock)
            if (!r.ok && (r.note.contains("disconnect", true) || r.note.contains("characteristics", true) || r.note.contains("error", true))) {
                crashed += m
                if (waitForCameraService(progress)) r = runOne(path, lens, key, 1f, m, lock)
            }
            if (r.ok) {
                consecutiveFails = 0
                val cls = classify(r.thumb, base.thumb)
                val levels = if (r.whiteLevel != base.whiteLevel) " ← LEVELS CHANGED (${base.whiteLevel}→${r.whiteLevel})" else ""
                val noise = if (base.shadowNoise > 0) " · shadow noise %.2f vs %.2f".format(r.shadowNoise, base.shadowNoise) else ""
                sb.appendLine("mode $m: ok ${r.rawW}x${r.rawH} echo=${r.echo} · $cls$noise$levels")
            } else { consecutiveFails++; sb.appendLine("mode $m: ${r.note}") }
        }
        if (crashed.isNotEmpty()) sb.appendLine("\nmodes that upset the camera service: ${crashed.joinToString()}")
        return sb.toString()
    }

    /** Blocks until the camera service answers again after a driver crash (up to ~20 s). */
    private fun waitForCameraService(progress: (String) -> Unit): Boolean {
        for (i in 1..20) {
            Thread.sleep(1000)
            progress("camera service restarting… ($i)")
            val ok = runCatching { cm.getCameraCharacteristics(Lenses.LOGICAL_ID); true }.getOrDefault(false)
            if (ok) { Thread.sleep(1500); return true }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun runOne(path: String, lens: Lens, key: String?, zoom: Float, value: Int = 1, lock: Pair<Long, Int>? = null, variant: String = "raw", extra: List<Pair<String, Int>> = emptyList()): Outcome {
        val direct = path == "direct"
        val physChars = try { cm.getCameraCharacteristics(lens.physicalId) } catch (t: Throwable) { return Outcome(false, "no characteristics") }
        val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Outcome(false, "no stream map")
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height } ?: return Outcome(false, "no RAW")
        val st = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 960) }
        val previewSurface = Surface(st)
        val rawFormat = if (variant == "raw10") ImageFormat.RAW10 else ImageFormat.RAW_SENSOR
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, rawFormat, 2)
        val yuvReader = if (variant == "raw+yuv") ImageReader.newInstance(1280, 960, ImageFormat.YUV_420_888, 2).also { it.setOnImageAvailableListener({ r -> r.acquireNextImage()?.close() }, handler) } else null
        val white = physChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        var met = Triple(0f, 0f, 0f)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var outcome = Outcome(false, "timeout")
        val done = CountDownLatch(1)
        var rawW = 0; var rawH = 0
        var thumb: FloatArray? = null
        var result: TotalCaptureResult? = null
        val gotImage = CountDownLatch(1); val gotResult = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { img -> rawW = img.width; rawH = img.height
            if (rawFormat == ImageFormat.RAW_SENSOR) { thumb = runCatching { thumbnail(img) }.getOrNull(); met = runCatching { metrics(img, white) }.getOrDefault(met) }
            img.close() }; gotImage.countDown() }, handler)

        fun finish(o: Outcome) { outcome = o; done.countDown() }
        fun setKey(b: CaptureRequest.Builder) {
            if (zoom != 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            if (lock != null) {
                b.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lock.first); b.set(CaptureRequest.SENSOR_SENSITIVITY, lock.second)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(33_333_333L, lock.first))
            }
            for ((k, v) in extra) try { b.set(CaptureRequest.Key(k, Int::class.javaObjectType), v) } catch (_: Throwable) {}
            if (key != null) try { b.set(CaptureRequest.Key(key, Int::class.javaObjectType), value) } catch (t: Throwable) { try { b.set(CaptureRequest.Key(key, Byte::class.javaObjectType), value.toByte()) } catch (_: Throwable) {} } }

        try {
            cm.openCamera(if (direct) lens.physicalId else path, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val outs = ArrayList<OutputConfiguration>()
                        outs += OutputConfiguration(previewSurface); outs += OutputConfiguration(reader.surface)
                        yuvReader?.let { outs += OutputConfiguration(it.surface) }
                        if (!direct) outs.forEach { it.setPhysicalCameraId(lens.physicalId) }
                        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val prev = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface); setKey(this) }
                                    s.setRepeatingRequest(prev.build(), null, handler)
                                    val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface); addTarget(previewSurface); yuvReader?.let { addTarget(it.surface) }; setKey(this) }
                                    handler.postDelayed({
                                        try {
                                            s.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(ss: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) { result = res; gotResult.countDown() }
                                                override fun onCaptureFailed(ss: CameraCaptureSession, rq: CaptureRequest, f: android.hardware.camera2.CaptureFailure) { finish(Outcome(false, "capture failed ${f.reason}")) }
                                            }, handler)
                                        } catch (t: Throwable) { finish(Outcome(false, "capture: ${t.message}")) }
                                    }, 600)
                                } catch (t: Throwable) { finish(Outcome(false, "request: ${t.message}")) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { finish(Outcome(false, "session refused")) }
                        })
                        val sp = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); setKey(sp); cfg.sessionParameters = sp.build()
                        cam.createCaptureSession(cfg)
                    } catch (t: Throwable) { finish(Outcome(false, "session: ${t.javaClass.simpleName} ${t.message}")) }
                }
                override fun onDisconnected(cam: CameraDevice) { finish(Outcome(false, "disconnected")) }
                override fun onError(cam: CameraDevice, error: Int) { finish(Outcome(false, "device error $error")) }
            }, handler)
        } catch (t: Throwable) { return Outcome(false, "open: ${t.message}") }

        // Wait for image + result (or a failure), max 6 s.
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline && done.count > 0) {
            if (gotImage.count == 0L && gotResult.count == 0L) {
                val r = result!!
                val pr = if (!direct) (r.physicalCameraResults[lens.physicalId] ?: r) else r
                val crop = pr.get(CaptureResult.SCALER_CROP_REGION)?.let { "${it.width()}x${it.height()}@${it.left},${it.top}" } ?: "?"
                val focal = pr.get(CaptureResult.LENS_FOCAL_LENGTH)?.toString() ?: "?"
                val echo = if (key == null) "" else (runCatching { pr.get(CaptureResult.Key(key, IntArray::class.java))?.joinToString() }.getOrNull()
                    ?: runCatching { pr.get(CaptureResult.Key(key, Int::class.javaObjectType))?.toString() }.getOrNull()
                    ?: runCatching { pr.get(CaptureResult.Key(key, ByteArray::class.java))?.joinToString() }.getOrNull() ?: "not reported")
                val whiteS = pr.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toString() ?: "?"
                val black = pr.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.joinToString() ?: "?"
                val exp = pr.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { "%.1fms".format(it / 1e6) } ?: "?"
                val fd = pr.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { "%.1fms".format(it / 1e6) } ?: "?"
                val expNs = pr.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                val isoV = pr.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                finish(Outcome(true, "", rawW, rawH, crop, focal, echo, whiteS, black, exp, fd, thumb, met.first, met.second, met.third, expNs, isoV))
            }
            Thread.sleep(50)
        }
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        reader.close(); yuvReader?.close(); previewSurface.release(); st.release()
        // Give the HAL a moment to fully release before the next open.
        Thread.sleep(400)
        return outcome
    }

    fun destroy() { thread.quitSafely() }
}
