package com.celestial.latent.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.graphics.ImageFormat
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One rear lens as the app uses it.
 *
 * @param physicalId the camera ID to put the stream on (a physical lens behind the logical camera,
 *   or a camera of its own when [standalone]).
 * @param label the zoom number shown on the lens row ("0.6", "1", "3" …), relative to [isMain].
 * @param mm 35 mm-equivalent focal length.
 * The last three are new, and last on purpose, so nothing built by position can shift.
 */
data class Lens(
    val physicalId: String,
    val label: String,
    val name: String,
    val mm: Int = 0,
    val raw: Boolean = true,
    val standalone: Boolean = false,
    val isMain: Boolean = false,
)

/**
 * The phone's rear lenses — discovered from what Android reports about each camera, rather than
 * typed in for one phone.
 *
 * The Xiaomi 15 Ultra keeps exactly the list it has always had. It is recognised by its cameras
 * (the same hidden lens numbers behind camera 0, with focal lengths that match), not by its name,
 * and when recognised its proven list is used unchanged: worked out from focal lengths alone, its
 * periscope would come out as 4.2× rather than the 4.3× everyone knows it by, and its filenames
 * and in-sensor ×2 labels would change with it. Every other phone gets the discovered list.
 */
object Lenses {
    /** The 15 Ultra's lenses, as first written by hand and proven on the phone. */
    private val XIAOMI_15_ULTRA = listOf(
        Lens("3", "0.6", "Ultrawide", 14),
        Lens("2", "1", "Main", 23, isMain = true),
        Lens("4", "3", "70 mm", 70),
        Lens("5", "4.3", "100 mm", 100),
    )

    @Volatile var ALL: List<Lens> = XIAOMI_15_ULTRA; private set
    @Volatile var LOGICAL_ID: String = "0"; private set
    /**
     * True only on the phone the Xiaomi-only features were proven on: the in-sensor ×2 zoom, the
     * camera-path choice and the Qualcomm keys behind them. Elsewhere they are hidden and never sent —
     * a vendor key a phone does not know can make it refuse the whole camera session.
     */
    @Volatile var isXiaomi15Ultra: Boolean = true; private set
    /** What discovery found and decided, in words — for the camera report and the log. */
    @Volatile var report: String = "lens discovery has not run"; private set

    /** The lens the app opens on: a short tele if the phone has one (the portrait lens), else the main. */
    val DEFAULT: Lens get() = ALL.firstOrNull { it.mm in 50..90 } ?: ALL.firstOrNull { it.isMain } ?: ALL.first()

    private class Found(val id: String, val eqMm: Float, val raw: Boolean, val standalone: Boolean)

    /** Reads the cameras once, at start-up, before anything uses the lens list. Never throws. */
    fun discover(context: Context) {
        val sb = StringBuilder()
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            fun chars(id: String) = runCatching { cm.getCameraCharacteristics(id) }.getOrNull()
            fun caps(ch: CameraCharacteristics) = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            /** 35 mm-equivalent focal length, or null if the camera does not say. */
            fun eqOf(ch: CameraCharacteristics): Float? {
                val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
                val d = hypot(size.width, size.height)
                return if (d > 0f) f * 43.27f / d else null
            }
            val back = cm.cameraIdList.mapNotNull { id ->
                chars(id)?.takeIf { it.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }?.let { id to it }
            }
            sb.appendLine("rear cameras Android lists: ${back.map { it.first }}")
            // The logical camera that bundles the most lenses is the one the app routes through.
            val logical = back.filter { CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps(it.second) }
                .maxByOrNull { it.second.physicalCameraIds.size }
            val behindLogical = logical?.second?.physicalCameraIds?.toList().orEmpty()
            sb.appendLine("logical camera: ${logical?.first ?: "none"}" + (if (logical != null) " with lenses $behindLogical" else ""))

            val candidates = behindLogical.map { it to false } +
                back.filter { (id, ch) -> CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in caps(ch) && id !in behindLogical }
                    .map { it.first to true }
            val found = candidates.mapNotNull { (id, standalone) ->
                val ch = chars(id) ?: return@mapNotNull null
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@mapNotNull null
                val eq = eqOf(ch) ?: return@mapNotNull null                // 35 mm-equivalent focal length
                val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val diagonal = if (size != null) hypot(size.width, size.height) else 0f
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps(ch) &&
                    map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
                sb.appendLine("  camera $id: ${"%.2f".format(f)} mm on a ${"%.1f".format(diagonal)} mm sensor ≈ ${"%.0f".format(eq)} mm equivalent · " +
                    (if (raw) "RAW" else "no RAW") + (if (standalone) " · its own camera" else " · behind ${logical?.first}"))
                Found(id, eq, raw, standalone)
            }

            // Several IDs can be one lens (a sensor in different modes). Keep one per focal length,
            // preferring one that shoots RAW and sits behind the logical camera.
            val distinct = ArrayList<Found>()
            for (x in found.sortedWith(compareBy<Found> { it.eqMm }.thenBy { !it.raw }.thenBy { it.standalone }.thenBy { it.id })) {
                val twin = distinct.firstOrNull { abs(it.eqMm - x.eqMm) / it.eqMm < 0.06f }
                if (twin == null) distinct += x
            }

            // Is this the 15 Ultra? Camera 0 itself — not whichever logical camera bundles the most
            // lenses, since this phone has several — must carry its four lens numbers, each at the
            // focal length we know.
            val zeroLenses = chars("0")?.physicalCameraIds.orEmpty()
            val recognised = XIAOMI_15_ULTRA.all { known ->
                val eq = if (known.physicalId in zeroLenses) chars(known.physicalId)?.let { eqOf(it) } else null
                eq != null && abs(eq - known.mm) / known.mm < 0.15f
            }
            if (recognised) {
                ALL = XIAOMI_15_ULTRA; LOGICAL_ID = "0"; isXiaomi15Ultra = true
                sb.appendLine("recognised as the Xiaomi 15 Ultra: using its proven lens list unchanged")
            } else {
                val usable = distinct.filter { it.raw }
                if (usable.isEmpty()) sb.appendLine("no rear lens offers RAW: Latent's camera needs RAW, so the camera will not work yet")
                val pool = usable.ifEmpty { distinct }
                val main = pool.minByOrNull { abs(it.eqMm - 24f) }
                ALL = if (main == null) XIAOMI_15_ULTRA else pool.map { x ->
                    val ratio = x.eqMm / main.eqMm
                    val label = when {
                        x === main -> "1"
                        ratio < 1f -> String.format(Locale.US, "%.1f", ratio)
                        abs(ratio - Math.round(ratio)) < 0.08f -> Math.round(ratio).toString()
                        else -> String.format(Locale.US, "%.1f", ratio)
                    }.removeSuffix(".0")
                    val mm = Math.round(x.eqMm)
                    val name = when { x === main -> "Main"; ratio < 0.8f -> "Ultrawide"; else -> "$mm mm" }
                    Lens(x.id, label, name, mm, raw = x.raw, standalone = x.standalone, isMain = x === main)
                }
                LOGICAL_ID = logical?.first ?: back.firstOrNull()?.first ?: "0"
                isXiaomi15Ultra = false
                val skipped = distinct.filter { !it.raw }
                if (skipped.isNotEmpty() && usable.isNotEmpty()) sb.appendLine("left out for now (no RAW): ${skipped.map { it.id }}")
            }
            sb.appendLine("lens row: " + ALL.joinToString { "${it.label}× ${it.name} (camera ${it.physicalId}${if (it.standalone) ", own" else ""})" })
            sb.appendLine("opens through camera $LOGICAL_ID · opens on ${DEFAULT.label}× · Xiaomi-only features ${if (isXiaomi15Ultra) "on" else "off"}")
        } catch (t: Throwable) {
            // Anything unexpected: keep the proven list rather than leave the app with no camera.
            ALL = XIAOMI_15_ULTRA; LOGICAL_ID = "0"; isXiaomi15Ultra = Build.MANUFACTURER.equals("Xiaomi", true)
            sb.appendLine("lens discovery failed (${t.javaClass.simpleName}: ${t.message}); using the original list")
        }
        report = sb.toString().trimEnd()
        Log.i("Latent", "lens discovery:\n$report")
    }
}
