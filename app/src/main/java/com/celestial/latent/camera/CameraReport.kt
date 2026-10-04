package com.celestial.latent.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Rational

/**
 * Reads what the phone's camera driver actually exposes to a third-party app and
 * formats it as plain text. This is step 2's first deliverable: facts before code.
 *
 * Everything here is read-only. No camera is opened.
 */
object CameraReport {

    fun build(context: Context): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.appendLine("CELESTIAL DARKROOM CAMERA REPORT")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        sb.appendLine()
        sb.appendLine("LENSES CELESTIAL DARKROOM FOUND")
        sb.appendLine(Lenses.report)
        sb.appendLine()

        val ids = try { cm.cameraIdList.toList() } catch (t: Throwable) { sb.appendLine("cameraIdList failed: $t"); emptyList() }
        sb.appendLine("Public camera IDs: $ids")
        // Camera Extensions (OEM bokeh/night/HDR exposed to third parties via the official API).
        if (Build.VERSION.SDK_INT >= 31) {
            for (id in ids) {
                val ext = try { cm.getCameraExtensionCharacteristics(id).supportedExtensions } catch (t: Throwable) { null }
                val names = ext?.map { when (it) { 0 -> "AUTOMATIC"; 1 -> "FACE_RETOUCH"; 2 -> "BOKEH"; 3 -> "HDR"; 4 -> "NIGHT"; else -> "ext$it" } }
                sb.appendLine("Extensions on camera $id: ${names ?: "none / unsupported"}")
            }
        }
        sb.appendLine()

        for (id in ids) {
            val ch = try { cm.getCameraCharacteristics(id) } catch (t: Throwable) { sb.appendLine("== Camera $id: characteristics failed: $t"); continue }
            describe(sb, "Camera $id", ch, isPhysical = false)
            val physicalIds = ch.physicalCameraIds
            if (physicalIds.isNotEmpty()) {
                sb.appendLine("  Physical cameras behind $id: $physicalIds")
                for (pid in physicalIds.sorted()) {
                    val pch = try { cm.getCameraCharacteristics(pid) } catch (t: Throwable) { sb.appendLine("  == Physical $pid: characteristics failed: $t"); continue }
                    describe(sb, "Physical $pid (via $id)", pch, isPhysical = true)
                }
            }
            sb.appendLine()
        }

        // Some OEMs hide extra cameras from cameraIdList but still allow them by ID.
        sb.appendLine("Probing hidden IDs 2..9 (not in the public list):")
        for (n in 2..9) {
            val id = n.toString()
            if (id in ids) continue
            val ch = try { cm.getCameraCharacteristics(id) } catch (t: Throwable) { null }
            if (ch == null) sb.appendLine("  $id: not accessible") else describe(sb, "Hidden $id", ch, isPhysical = true)
        }
        return sb.toString()
    }

    private fun describe(sb: StringBuilder, title: String, ch: CameraCharacteristics, isPhysical: Boolean) {
        sb.appendLine("== $title")
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "BACK"; CameraMetadata.LENS_FACING_FRONT -> "FRONT"; else -> "EXTERNAL/unknown"
        }
        val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            else -> "?"
        }
        sb.appendLine("  facing=$facing level=$level")

        // Lens
        val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
        val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString()
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val hyper = ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        val focusCal = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString()
        sb.appendLine("  lens: focal=${focals}mm aperture=f/$apertures minFocus=${minFocus} (1/m) hyperfocal=$hyper calib=$focusCal OIS=[$ois]")

        // Sensor
        val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val physSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val cfa = when (ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            0 -> "RGGB"; 1 -> "GRBG"; 2 -> "GBRG"; 3 -> "BGGR"; 4 -> "RGB"; 5 -> "MONO"; 6 -> "NIR"; else -> "?"
        }
        val white = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val black = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> IntArray(4) { i -> p.getOffsetForIndex(i % 2, i / 2) }.joinToString() }
        val isoRange: Range<Int>? = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange: Range<Long>? = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxAnalog = ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val ts = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        sb.appendLine("  sensor: pixelArray=$pixelArray active=$active physical=${physSize}mm CFA=$cfa white=$white black=[$black] orientation=$orientation ts=$ts")
        sb.appendLine("  ISO range=$isoRange maxAnalog=$maxAnalog | exposure ns range=$expRange (${expRange?.let { fmtShutter(it.lower) }} .. ${expRange?.let { fmtShutter(it.upper) }})")

        // Colour science tags the DNG writer uses
        val ref1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
        val ref2 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
        sb.appendLine("  reference illuminants: $ref1, $ref2")
        sb.appendLine("  colorTransform1=${mat(ch, CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)}")
        sb.appendLine("  colorTransform2=${mat(ch, CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)}")
        sb.appendLine("  calibration1=${mat(ch, CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)}")
        sb.appendLine("  forwardMatrix1=${mat(ch, CameraCharacteristics.SENSOR_FORWARD_MATRIX1)}")

        // Capabilities
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map { capName(it) }
        sb.appendLine("  capabilities: $caps")
        val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.joinToString()
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString()
        val awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.joinToString()
        val evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep: Rational? = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val aeLock = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
        val awbLock = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)
        val zoomRange = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        sb.appendLine("  AE modes=[$aeModes] AF modes=[$afModes] AWB modes=[$awbModes] EV=$evRange step=$evStep aeLock=$aeLock awbLock=$awbLock")
        sb.appendLine("  zoomRatioRange=$zoomRange maxDigitalZoom=$maxZoom")
        val maxRaw = ch.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW)
        val maxProc = ch.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC)
        val maxProcStall = ch.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING)
        sb.appendLine("  max outputs: raw=$maxRaw proc=$maxProc procStalling=$maxProcStall")

        // Stream sizes
        val map: StreamConfigurationMap? = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) { sb.appendLine("  (no stream configuration map)"); return }
        fun sizes(fmt: Int, name: String) {
            val s = map.getOutputSizes(fmt)
            if (s == null || s.isEmpty()) { sb.appendLine("  $name: none"); return }
            val sorted = s.sortedByDescending { it.width.toLong() * it.height }
            sb.appendLine("  $name (${s.size}): " + sorted.take(6).joinToString { "${it.width}x${it.height} (${mp(it.width, it.height)})" } + if (s.size > 6) " …" else "")
            if (fmt == ImageFormat.RAW_SENSOR) {
                val stall = sorted.firstOrNull()?.let { map.getOutputStallDuration(fmt, it) }
                val minFrame = sorted.firstOrNull()?.let { map.getOutputMinFrameDuration(fmt, it) }
                sb.appendLine("    RAW largest: stall=${stall}ns minFrame=${minFrame}ns")
            }
        }
        // Every output format the driver offers, so we can see if JPEG_R (Ultra HDR) is among them.
        val fmtNames = mapOf(
            ImageFormat.RAW_SENSOR to "RAW_SENSOR", ImageFormat.RAW10 to "RAW10", ImageFormat.RAW12 to "RAW12", ImageFormat.RAW_PRIVATE to "RAW_PRIVATE",
            ImageFormat.JPEG to "JPEG", 4101 to "JPEG_R (Ultra HDR)", ImageFormat.YUV_420_888 to "YUV_420_888", ImageFormat.PRIVATE to "PRIVATE",
            ImageFormat.YCBCR_P010 to "YCBCR_P010", ImageFormat.HEIC to "HEIC", ImageFormat.DEPTH16 to "DEPTH16", ImageFormat.DEPTH_JPEG to "DEPTH_JPEG",
        )
        val formats = map.outputFormats
        sb.appendLine("  output formats: " + formats.joinToString { f -> fmtNames[f] ?: "0x%x".format(f) })
        sizes(ImageFormat.RAW_SENSOR, "RAW_SENSOR")
        sizes(ImageFormat.RAW10, "RAW10")
        sizes(ImageFormat.RAW12, "RAW12")
        sizes(ImageFormat.JPEG, "JPEG")
        if (Build.VERSION.SDK_INT >= 34) sizes(4101, "JPEG_R (Ultra HDR)")
        sizes(ImageFormat.YUV_420_888, "YUV_420_888")
        sizes(ImageFormat.PRIVATE, "PRIVATE(preview)")
        if (Build.VERSION.SDK_INT >= 33) {
            val dr = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            sb.appendLine("  dynamic range profiles: ${dr?.supportedProfiles}")
        }
        // Vendor tags: anything the OEM or chipset added beyond Android's own keys.
        fun vendor(names: List<String>?): List<String> = names.orEmpty().filter { !it.startsWith("android.") }.sorted()
        val vChar = vendor(ch.keys.map { it.name })
        val vReq = vendor(ch.availableCaptureRequestKeys?.map { it.name })
        val vRes = vendor(ch.availableCaptureResultKeys?.map { it.name })
        val vSess = vendor(if (Build.VERSION.SDK_INT >= 28) ch.availableSessionKeys?.map { it.name } else null)
        val vPhys = vendor(if (Build.VERSION.SDK_INT >= 28) ch.availablePhysicalCameraRequestKeys?.map { it.name } else null)
        sb.appendLine("  vendor characteristic keys (${vChar.size}): " + vChar.joinToString())
        for (k in vChar) {
            val key = ch.keys.firstOrNull { it.name == k } ?: continue
            val v = try { ch.get(key) } catch (t: Throwable) { "<err>" }
            val text = when (v) {
                null -> "null"
                is IntArray -> v.joinToString(prefix = "[", postfix = "]")
                is ByteArray -> v.joinToString(prefix = "[", postfix = "]", limit = 32)
                is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
                is LongArray -> v.joinToString(prefix = "[", postfix = "]")
                is Array<*> -> v.joinToString(prefix = "[", postfix = "]", limit = 16)
                else -> v.toString()
            }
            sb.appendLine("    $k = ${text.take(300)}")
        }
        sb.appendLine("  vendor request keys (${vReq.size}): " + vReq.joinToString())
        sb.appendLine("  vendor result keys (${vRes.size}): " + vRes.joinToString())
        sb.appendLine("  vendor session keys (${vSess.size}): " + vSess.joinToString())
        sb.appendLine("  vendor physical-request keys (${vPhys.size}): " + vPhys.joinToString())
        if (isPhysical) sb.appendLine("  (physical)")
    }

    private fun mat(ch: CameraCharacteristics, key: CameraCharacteristics.Key<android.hardware.camera2.params.ColorSpaceTransform>): String {
        val m = ch.get(key) ?: return "null"
        val vals = (0 until 3).joinToString(" | ") { r -> (0 until 3).joinToString(",") { c -> "%.4f".format(m.getElement(c, r).toDouble()) } }
        return "[$vals]"
    }

    private fun capName(c: Int): String = when (c) {
        0 -> "BACKWARD_COMPATIBLE"; 1 -> "MANUAL_SENSOR"; 2 -> "MANUAL_POST_PROCESSING"; 3 -> "RAW"
        4 -> "PRIVATE_REPROCESSING"; 5 -> "READ_SENSOR_SETTINGS"; 6 -> "BURST_CAPTURE"; 7 -> "YUV_REPROCESSING"
        8 -> "DEPTH_OUTPUT"; 9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"; 10 -> "MOTION_TRACKING"; 11 -> "LOGICAL_MULTI_CAMERA"
        12 -> "MONOCHROME"; 13 -> "SECURE_IMAGE_DATA"; 14 -> "SYSTEM_CAMERA"; 15 -> "OFFLINE_PROCESSING"
        16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"; 17 -> "REMOSAIC_REPROCESSING"; 18 -> "DYNAMIC_RANGE_TEN_BIT"
        19 -> "STREAM_USE_CASE"; 20 -> "COLOR_SPACE_PROFILES"; else -> "cap$c"
    }

    private fun mp(w: Int, h: Int) = "%.1fMP".format(w.toLong() * h / 1_000_000.0)

    private fun fmtShutter(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 1) "%.1fs".format(s) else "1/%.0f".format(1 / s)
    }
}
