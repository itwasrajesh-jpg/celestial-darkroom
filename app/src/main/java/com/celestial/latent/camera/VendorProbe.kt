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

    /** Compare a thumbnail to the baseline: is it the same view, the baseline's centre 2x crop, or something else? */
    private fun classify(t: FloatArray?, base: FloatArray?): String {
        if (t == null || base == null) return "?"
        fun norm(a: FloatArray): FloatArray { val m = a.average().toFloat().coerceAtLeast(1f); return FloatArray(a.size) { a[it] / m } }
        val a = norm(t); val b = norm(base)
        // centre 2x crop of baseline, upsampled to 64x48
        val c = FloatArray(64 * 48) { i -> val x = i % 64; val y = i / 64; base[(12 + y / 2) * 64 + (16 + x / 2)] }
        val cn = norm(c)
        fun err(p: FloatArray, q: FloatArray): Float { var e = 0f; for (i in p.indices) e += Math.abs(p[i] - q[i]); return e / p.size }
        val eFull = err(a, b); val eCrop = err(a, cn)
        return when {
            eFull < 0.08f && eFull <= eCrop -> "same view as baseline (%.3f)".format(eFull)
            eCrop < 0.12f && eCrop < eFull -> "≈ centre 2x crop of baseline (%.3f vs %.3f) ← IN-SENSOR CROP".format(eCrop, eFull)
            else -> "different from both (full %.3f, crop %.3f)".format(eFull, eCrop)
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
