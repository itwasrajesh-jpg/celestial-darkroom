package com.celestial.latent.develop

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * 95a Night Clean: a small AI model (FFDNet colour, 0.85 M parameters, KAIR, MIT licence — see
 * assets/models/ffdnet_color.LICENSE) cleans the sensor's noise from the picture before the film, so the
 * film's own grain is the only texture. Approved proofs: ai-models/proof/4-small-model.jpg (the look)
 * and ai-models/ui-proof/ (where it sits). The model ships inside the app (3.4 MB): nothing to download.
 * The arithmetic is in [NightCleanMath].
 */
object NightClean {
    const val DEFAULT_STRENGTH = 25f
    private const val MODEL = "models/ffdnet_color.onnx"
    private var session: OrtSession? = null

    private fun open(context: Context): OrtSession {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtEnvironment.getEnvironment().createSession(bytes, opts)
    }

    /** Cleans [source]'s pixels in place. */
    @Synchronized
    fun apply(context: Context, source: Develop.Source, strength: Float, follow: Boolean, nativeScale: Float, log: (String) -> Unit = {}) {
        val t0 = System.currentTimeMillis()
        log("night clean · cleaning the RAW")
        val s = session ?: open(context).also { session = it }
        val env = OrtEnvironment.getEnvironment()
        val buf = source.image.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val r = NightCleanMath.clean(buf, source.width, source.height, strength, follow, nativeScale) { tile, map, th, tw ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tile), longArrayOf(1, 3, th.toLong(), tw.toLong())).use { image ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(map), longArrayOf(1, 1, (th / 2).toLong(), (tw / 2).toLong())).use { noise ->
                    s.run(mapOf("image" to image, "noise" to noise)).use { out ->
                        val fb = (out[0] as OnnxTensor).floatBuffer
                        FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                }
            }
        }
        Log.i("Latent", String.format(java.util.Locale.US,
            "night clean: %dx%d (%.2f of the file) strength %.0f follow %s in %d ms (%d tiles, white %.3f, level %.1f/255%s)",
            source.width, source.height, nativeScale, strength, follow, System.currentTimeMillis() - t0, r.tiles, r.white, r.level * 255f,
            if (follow) r.noise.bands.joinToString(",", " · measured ") { String.format(java.util.Locale.US, "%.1f", it * 255f) } else ""))
    }

    /** Frees the model's memory; it loads again when next needed. */
    @Synchronized
    fun release() { runCatching { session?.close() }; session = null }
}
