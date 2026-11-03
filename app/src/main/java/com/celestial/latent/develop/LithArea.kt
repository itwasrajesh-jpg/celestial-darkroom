package com.celestial.latent.develop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 109h: lith IN a layer. The lith is worked out on the WHOLE print as always (so its raced blacks,
 * grit and glow are what they would be, and nothing changes at the layer's edge), then blended over
 * the print as it was by the layer's area, snapped to the print's own edges with the same guided
 * filter the layers use ([Layers.fit]). Approved proof: layers/109h-lith-in-layer/ (lith_in_layer.py).
 */
internal object LithArea {
    /**
     * [lith] makes [data] (w × h, three floats a pixel) a lith print in place; afterwards each pixel
     * is the lith only as much as it is inside [area] (a whole-picture mask; [frame] is which part of
     * the picture this is), the print as it was elsewhere.
     */
    fun apply(data: ByteBuffer, w: Int, h: Int, frame: Region?, area: ExposureMap, lith: () -> Unit) {
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = w * h * 3
        val before = FloatArray(n); for (i in 0 until n) before[i] = f.get(i)
        lith()
        val m = if (frame != null) area.crop(frame) else area
        val (sw, sh) = Layers.smallSize(w, h)
        val guide = Layers.smallGuide(FloatBuffer.wrap(before), w, h, sw, sh)
        val (A, B) = Layers.fit(guide, m, sw, sh)
        val fx = 1f * sw / w; val fy = 1f * sh / h
        val bands = max(1, min(Runtime.getRuntime().availableProcessors(), h / 64))
        fun band(y0b: Int, y1b: Int) { for (y in y0b until y1b) {
            val sy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = sy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = sy - y0
            var p = y * w * 3
            for (x in 0 until w) {
                val sx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = sx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = sx - x0
                val k00 = y0 * sw + x0; val k01 = y0 * sw + x1; val k10 = y1 * sw + x0; val k11 = y1 * sw + x1
                val aa = (A[k00] * (1 - tx) + A[k01] * tx) * (1 - ty) + (A[k10] * (1 - tx) + A[k11] * tx) * ty
                val bb = (B[k00] * (1 - tx) + B[k01] * tx) * (1 - ty) + (B[k10] * (1 - tx) + B[k11] * tx) * ty
                val wt = (aa * Layers.guideOf(before[p], before[p + 1], before[p + 2]) + bb).let { v -> if (v.isNaN()) 0f else v.coerceIn(0f, 1f) }
                if (wt < 1f) for (c in 0..2) {
                    val o = before[p + c]
                    f.put(p + c, o + (f.get(p + c) - o) * wt)
                }
                p += 3
            }
        } }
        if (bands == 1) band(0, h) else {
            val step = (h + bands - 1) / bands
            (0 until bands).map { k -> Thread { band(k * step, min(h, (k + 1) * step)) }.apply { start() } }.forEach { it.join() }
        }
    }
}
