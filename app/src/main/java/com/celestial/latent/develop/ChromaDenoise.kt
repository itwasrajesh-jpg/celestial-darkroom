package com.celestial.latent.develop

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Removes colour noise from a linear image before the film sees it.
 *
 * Film grain is texture and belongs in the picture; the red and green blotches a sensor
 * produces in the dark are not — film never saw them, and the engine's dye couplers push them
 * further apart, so they get worse through the simulation rather than better.
 *
 * The method is the classic one and invents nothing: separate brightness from colour, blur
 * only the colour (colour detail in any photograph is genuinely low-resolution), then put
 * brightness back untouched. Detail and grain are unaffected because brightness is never
 * filtered — which is exactly why this is not a "denoiser" in the AI sense.
 */
object ChromaDenoise {

    /** Rows worked out at once (81b): the working arrays are a band, not the photo. */
    private const val BAND = 256

    /**
     * @param strength 0 = off, 1 = strong. The blur radius scales with this.
     * Works in place on a linear RGB float buffer (width*height*3 floats).
     *
     * 81b: in bands of [BAND] rows, each read with three blur radii of rows above and below it (three
     * box passes reach that far), so the result is the whole-picture one to rounding while the working
     * arrays stay a few megabytes: whole, they were four photo-sized arrays, 800 MB at 50 MP. A band is
     * written only after the next band has read its rows, so every band reads the untouched picture.
     */
    fun apply(data: ByteBuffer, width: Int, height: Int, strength: Float) {
        if (strength <= 0.001f || width < 8 || height < 8) return
        val t0 = System.nanoTime()
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()

        // Radius grows with strength; three box passes approximate a Gaussian closely enough
        // and stay linear in cost regardless of radius.
        val radius = (1 + (strength * 6f)).toInt().coerceIn(1, 12)
        val margin = 3 * radius
        // the arrays for the largest band, made once and reused for every band
        val most = width * minOf(height, BAND + 2 * margin)
        val cb = FloatArray(most); val cr = FloatArray(most); val tmp = FloatArray(most)
        var out = FloatArray(width * minOf(height, BAND) * 3); var pending = FloatArray(out.size)
        var pendingY0 = -1; var pendingRows = 0
        fun flush() { if (pendingY0 >= 0) for (k in 0 until pendingRows * width * 3) f.put(pendingY0 * width * 3 + k, pending[k]) }
        var y0 = 0
        while (y0 < height) {
            val y1 = minOf(height, y0 + BAND)
            val a0 = maxOf(0, y0 - margin); val a1 = minOf(height, y1 + margin); val bh = a1 - a0
            val n = width * bh
            // The two colour differences of the band and its margins. Chroma is what gets blurred.
            for (k in 0 until n) {
                val i = a0 * width + k
                val r = f.get(i * 3); val g = f.get(i * 3 + 1); val b = f.get(i * 3 + 2)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                cb[k] = b - lum
                cr[k] = r - lum
            }
            repeat(3) {
                boxBlur(cb, width, bh, radius, tmp)
                boxBlur(cr, width, bh, radius, tmp)
            }
            // The band's own rows, put back together; brightness from the untouched picture.
            for (k in 0 until width * (y1 - y0)) {
                val i = y0 * width + k; val j = k + (y0 - a0) * width
                val lum = 0.2126f * f.get(i * 3) + 0.7152f * f.get(i * 3 + 1) + 0.0722f * f.get(i * 3 + 2)
                val b = cb[j] + lum
                val r = cr[j] + lum
                // Green is recovered from the others so brightness is preserved exactly.
                val g = (lum - 0.2126f * r - 0.0722f * b) / 0.7152f
                out[k * 3] = r; out[k * 3 + 1] = g; out[k * 3 + 2] = b
            }
            flush()
            val t = pending; pending = out; out = t; pendingY0 = y0; pendingRows = y1 - y0
            y0 = y1
        }
        flush()
        Log.i("Latent", "chroma denoise: strength ${"%.2f".format(strength)}, radius $radius, ${width}x$height in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    /** Separable running-sum box blur: cost does not grow with radius. [tmp] is scratch of the same size. */
    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        val span = 2 * r + 1
        // Horizontal
        for (row in 0 until h) {
            val base = row * w
            var sum = 0f
            for (x in -r..r) sum += v[base + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = sum / span
                val out = base + (x - r).coerceIn(0, w - 1)
                val into = base + (x + r + 1).coerceIn(0, w - 1)
                sum += v[into] - v[out]
            }
        }
        // Vertical
        for (x in 0 until w) {
            var sum = 0f
            for (yy in -r..r) sum += tmp[yy.coerceIn(0, h - 1) * w + x]
            for (yy in 0 until h) {
                v[yy * w + x] = sum / span
                val out = (yy - r).coerceIn(0, h - 1) * w + x
                val into = (yy + r + 1).coerceIn(0, h - 1) * w + x
                sum += tmp[into] - tmp[out]
            }
        }
    }

    /**
     * A sensible default for a shot taken at [iso]: nothing in daylight, rising through the
     * ISO range. Colour noise is an artefact of amplification, so the ISO predicts it well.
     */
    fun strengthForIso(iso: Int): Float = when {
        iso <= 200 -> 0f
        iso <= 400 -> 0.2f
        iso <= 800 -> 0.4f
        iso <= 1600 -> 0.6f
        iso <= 3200 -> 0.8f
        else -> 1f
    }
}
