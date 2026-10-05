package com.celestial.latent.develop

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Depth from a single photo: how far away each part of the picture is, estimated by
 * Depth Anything V2 Small (Lihe Yang et al., Apache-2.0; ONNX export by fabio-sim, Apache-2.0),
 * run on the phone with ONNX Runtime. It turns a flat photo into a rough 3D scene, which is what
 * physically based light needs: which way each surface faces, and what can cast a shadow on what.
 *
 * The model is not in the app: it is downloaded once (~50 MB) from this project's own GitHub
 * release and checked against its SHA-256 fingerprint before use, so a damaged or altered file is
 * never run. Its weights are stored at half size and computed at full precision — measured on five
 * test photos against the full model: agreement 1.000000, largest difference 0.43% of the depth range.
 *
 * The answer is *relative* inverse depth: bigger means nearer, with no units. Real distances come
 * from a scene scale the user sets.
 */
object Depth {
    const val MODEL_URL =
        "https://github.com/itwasrajesh-jpg/celestial-darkroom/releases/download/models-v1/depth-anything-v2-small.onnx"
    const val MODEL_SHA256 = "a8f3626ac441c492af0747dad2bce7ead473afa8e061f7054351e95be752f2bf"
    const val MODEL_BYTES = 49_936_537L
    /** The model's input and output size, in pixels per side. */
    const val SIZE = 518

    private fun modelFile(context: Context): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, "depth-anything-v2-small.onnx")

    /** True when the model is downloaded, complete and verified. */
    fun isReady(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() == MODEL_BYTES }

    /**
     * Downloads the model, reporting (bytes so far, total). Written to a temporary file, checked
     * against [MODEL_SHA256] as it arrives, and only then moved into place.
     */
    @Synchronized
    fun download(context: Context, onProgress: (Long, Long) -> Unit): Result<Unit> = runCatching {
        if (isReady(context)) return@runCatching
        val target = modelFile(context)
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val t0 = System.currentTimeMillis()
        // GitHub sends release downloads on to its file storage: redirects are followed (https to https)
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "CelestialDarkroom")
        }
        try {
            if (conn.responseCode != 200) error("the model download answered ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
            var got = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); got += n
                        onProgress(got, total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != MODEL_BYTES || sha != MODEL_SHA256) {
                part.delete()
                error("the downloaded model did not match its fingerprint ($got bytes, ${sha.take(12)}…) — not used")
            }
            if (!part.renameTo(target)) error("could not move the model into place")
            Log.i("Latent", "depth: model downloaded and verified in ${(System.currentTimeMillis() - t0) / 1000} s")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Takes the model from a file the user picked — no download needed. Copied into the app's own
     * storage and checked against the same [MODEL_SHA256] as a download, so a wrong or damaged
     * file is refused; only then moved into place.
     */
    @Synchronized
    fun importFrom(context: Context, uri: android.net.Uri, onProgress: (Long, Long) -> Unit): Result<Unit> = runCatching {
        if (isReady(context)) return@runCatching
        val target = modelFile(context)
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var got = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open the chosen file" }
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n); got += n
                    onProgress(got, MODEL_BYTES)
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != MODEL_BYTES || sha != MODEL_SHA256) {
            part.delete()
            error("that file is not the depth model this app expects ($got bytes, ${sha.take(12)}…) — not used")
        }
        if (!part.renameTo(target)) error("could not move the model into place")
        Log.i("Latent", "depth: model imported from a file and verified")
    }

    private var session: OrtSession? = null

    /** The model, loaded once and kept: loading takes a moment, running it again is cheaper. */
    @Synchronized
    private fun session(context: Context): OrtSession {
        session?.let { return it }
        check(isReady(context)) { "the depth model is not downloaded yet" }
        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val s = OrtEnvironment.getEnvironment().createSession(modelFile(context).absolutePath, opts)
        Log.i("Latent", "depth: model loaded in ${System.currentTimeMillis() - t0} ms")
        session = s
        return s
    }

    /** Frees the model's memory (about 100 MB once loaded); it loads again when next needed. */
    @Synchronized
    fun release() { runCatching { session?.close() }; session = null }

    /**
     * Relative inverse depth for a picture: [SIZE] x [SIZE] values, 0 = farthest … 1 = nearest.
     * The picture is an ordinary sRGB bitmap, as the model was trained on, squeezed to a square
     * (the answer is stretched back to the picture's shape by whoever uses it).
     */
    fun estimate(context: Context, picture: Bitmap): FloatArray {
        val s = session(context)
        val t0 = System.currentTimeMillis()
        val sq = Bitmap.createScaledBitmap(picture, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE); sq.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (sq !== picture) sq.recycle()
        // ImageNet normalisation, in the model's channel-first layout
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * SIZE * SIZE)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in px.indices) input.put((((px[i] shr shift) and 0xFF) / 255f - mean[c]) / std[c])
        }
        input.rewind()
        val env = OrtEnvironment.getEnvironment()
        val out = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                val fb = (result[0] as OnnxTensor).floatBuffer
                FloatArray(SIZE * SIZE).also { fb.get(it) }
            }
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in out) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] = (out[i] - lo) / span
        Log.i("Latent", "depth: estimated in ${System.currentTimeMillis() - t0} ms")
        return out
    }
}
