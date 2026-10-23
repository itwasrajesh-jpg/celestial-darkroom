package com.celestial.latent.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.pow

/**
 * What the user asked for on the control strip. null = automatic.
 * Immutable: every change makes a copy and the controller re-applies it.
 */
data class Controls(
    val evIndex: Int = 0,             // in steps of the camera's EV step (1/6 stop here), -24..24
    val shutterNs: Long? = null,      // manual exposure time, or null for auto
    val iso: Int? = null,             // manual sensitivity, or null for auto
    val kelvin: Int? = null,          // manual white balance, or null for auto
    val focusDiopters: Float? = null, // manual focus (0 = infinity), or null for autofocus
    val locked: Boolean = false,      // AE/AF lock (long-press on the viewfinder)
    val zoom: Float = 1f,             // CONTROL_ZOOM_RATIO; 2x on the main lens is the in-sensor zoom test
) {
    val manualExposure get() = shutterNs != null || iso != null
}

/** Live values reported by the camera while previewing (what "A" currently means). */
data class LiveReadout(
    val shutterNs: Long = 0,
    val iso: Int = 0,
    val focusDiopters: Float = 0f,
    val kelvinEstimate: Int = 0,
    val afState: String = "",
    /** 79a fast shot: what picked the shutter ("MOTION 1/250", "YOUR 1/1000", "DIM", "METERING"), or "" when off. */
    val fast: String = "",
)

object ControlMath {

    fun shutterLabel(ns: Long): String {
        if (ns <= 0) return "—"
        val s = ns / 1e9
        return if (s >= 1.0) (if (s == Math.floor(s)) "${s.toInt()}\"" else String.format("%.1f\"", s)) else if (s >= 0.25) String.format("%.1f\"", s) else "1/" + Math.round(1.0 / s)
    }

    /** Standard third-stop shutter ladder (the numbers real cameras show), limited to what the lens supports. */
    private val SHUTTER_DENOMS = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
    private val SHUTTER_SECONDS = listOf(0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0)
    fun shutterPresets(minNs: Long, maxNs: Long): List<Long> {
        val out = ArrayList<Long>()
        for (d in SHUTTER_DENOMS.reversed()) { val ns = Math.round(1e9 / d); if (ns in minNs..maxNs) out += ns }
        for (sec in SHUTTER_SECONDS) { val ns = Math.round(sec * 1e9); if (ns in minNs..maxNs) out += ns }
        return out.distinct().sorted()
    }

    /** Standard third-stop ISO ladder within range. */
    private val ISO_LADDER = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
    fun isoPresets(min: Int, max: Int): List<Int> = ISO_LADDER.filter { it in min..max }

    // ---- White balance: Kelvin -> per-channel gains, using the sensor's own colour matrices ----
    // This is the same maths a DNG converter uses: find the XYZ of the illuminant, push it
    // through the camera's colour matrix (interpolated between its D65 and tungsten matrices),
    // and the result is the sensor's response to neutral grey under that light. Gains are its inverse.

    private fun xyForKelvin(t: Double): Pair<Double, Double> {
        val x = when {
            t < 4000 -> -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
            t <= 7000 -> -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
            else -> -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040
        }
        val y = when {
            t < 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t < 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> -3.0 * x * x + 2.87 * x - 0.275
        }
        return x to y
    }

    private fun matrix(m: ColorSpaceTransform?): DoubleArray? = m?.let { t -> DoubleArray(9) { i -> t.getElement(i % 3, i / 3).toDouble() } }

    private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }
    private fun mulM(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }

    fun gainsForKelvin(ch: CameraCharacteristics, kelvin: Int): RggbChannelVector {
        val cm1 = matrix(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val cm2 = matrix(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)) ?: cm1
        val cal = matrix(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)) ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        // Illuminant temperatures of the two matrices: 21 = D65 (6504 K), 17 = Standard A (2856 K).
        val t1 = 6504.0; val t2 = 2856.0
        val t = kelvin.toDouble().coerceIn(1800.0, 20000.0)
        val w2 = ((1 / t - 1 / t1) / (1 / t2 - 1 / t1)).coerceIn(0.0, 1.0)
        val cm = DoubleArray(9) { i -> cm1[i] * (1 - w2) + cm2[i] * w2 }
        val (x, y) = xyForKelvin(t)
        val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val neutral = mul(mulM(cal, cm), xyz)
        val g = neutral[1]
        val rg = (g / neutral[0]).toFloat().coerceIn(0.5f, 8f)
        val bg = (g / neutral[2]).toFloat().coerceIn(0.5f, 8f)
        return RggbChannelVector(rg, 1f, 1f, bg)
    }

    /** Rough inverse: which Kelvin best explains the gains the auto white balance chose. */
    fun kelvinForGains(ch: CameraCharacteristics, gains: RggbChannelVector): Int {
        var best = 5000; var bestErr = Double.MAX_VALUE
        var k = 2000
        while (k <= 12000) {
            val g = gainsForKelvin(ch, k)
            val err = Math.abs(Math.log((g.red / gains.red).toDouble())) + Math.abs(Math.log((g.blue / gains.blue).toDouble()))
            if (err < bestErr) { bestErr = err; best = k }
            k += 100
        }
        return best
    }
}
