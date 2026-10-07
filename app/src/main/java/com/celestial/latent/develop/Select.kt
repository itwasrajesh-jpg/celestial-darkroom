package com.celestial.latent.develop

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Tap to select (step 59a): an object's outline from a tap, by **MobileSAM** (Chaoning Zhang et al.,
 * Apache-2.0; built on Meta's Segment Anything, Apache-2.0), run on the phone with ONNX Runtime.
 *
 * Two model files, both downloaded once from this project's own GitHub release and checked against
 * their SHA-256 fingerprints, like the depth model:
 *  - the **encoder** (16.6 MB) looks at the picture once and describes it (a 256 × 64 × 64 summary);
 *  - the **decoder** (8.4 MB) turns that summary and the taps into outlines, in a fraction of a second.
 * Weights are stored at half size and computed at full precision: on 18 test photos the outlines
 * agree with the full model's to 99.1% or better (proof page, 7 Oct 2026).
 *
 * One tap gives three answers of growing size (the hair, the head, the whole person); more taps add
 * to the object (or take away), and then a single answer comes back.
 */
object Select {
    private const val BASE = "https://github.com/itwasrajesh-jpg/celestial-darkroom/releases/download/models-v1/"

    /** One model file: its name in the release and in the app's storage, and its fingerprint. */
    class ModelFile(val name: String, val sha256: String, val bytes: Long)
    val ENCODER = ModelFile("mobilesam-encoder.onnx", "c7f763047088ff6290f5007f04794bda7a01220aaa20b12d811338065fe3f0ba", 16_569_199L)
    val DECODER = ModelFile("mobilesam-decoder.onnx", "7b94b7a5300b6f5458b9a9b331ae0727a90f4ceadf548457d9899c3a4b758c6a", 8_405_415L)
    private val FILES = listOf(ENCODER, DECODER)

    /** The encoder's square input, in pixels per side: the picture's long side is scaled to this. */
    const val SIZE = 1024
    /** The summary's size: 256 channels over a 64 × 64 grid. */
    const val EMBED = 256 * 64 * 64
    // the model's colour normalisation, on 0..255 values
    private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    private fun file(context: Context, m: ModelFile): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, m.name)

    /** True when both files are downloaded, complete and verified. */
    fun isReady(context: Context): Boolean = FILES.all { m -> file(context, m).let { it.exists() && it.length() == m.bytes } }

    /** Copies a stream to a model file, checking its fingerprint as it arrives; only a match is moved into place. */
    private fun store(context: Context, m: ModelFile, input: java.io.InputStream, done: Long, total: Long, onProgress: (Long, Long) -> Unit) {
        val target = file(context, m)
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var got = 0L
        part.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); digest.update(buf, 0, n); got += n
                onProgress(done + got, total)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != m.bytes || sha != m.sha256) {
            part.delete()
            error("${m.name} did not match its fingerprint ($got bytes, ${sha.take(12)}…) — not used")
        }
        if (!part.renameTo(target)) error("could not move ${m.name} into place")
    }

    /** Downloads whatever is missing, reporting (bytes so far, total) across both files. */
    @Synchronized
    fun download(context: Context, onProgress: (Long, Long) -> Unit): Result<Unit> = runCatching {
        val total = FILES.sumOf { it.bytes }
        var done = 0L
        val t0 = System.currentTimeMillis()
        for (m in FILES) {
            if (file(context, m).let { it.exists() && it.length() == m.bytes }) { done += m.bytes; continue }
            // GitHub sends release downloads on to its file storage: redirects are followed (https to https)
            val conn = (URL(BASE + m.name).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "CelestialDarkroom")
            }
            try {
                if (conn.responseCode != 200) error("the download of ${m.name} answered ${conn.responseCode}")
                conn.inputStream.use { store(context, m, it, done, total, onProgress) }
            } finally { conn.disconnect() }
            done += m.bytes
        }
        Log.i("Latent", "select: models downloaded and verified in ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    /**
     * Takes the model files the user picked (both at once, or one at a time). Each is recognised by
     * its size and checked against its fingerprint, exactly as a download is.
     */
    @Synchronized
    fun importFrom(context: Context, uris: List<android.net.Uri>, onProgress: (Long, Long) -> Unit): Result<Unit> = runCatching {
        val total = FILES.sumOf { it.bytes }
        var done = 0L
        for (uri in uris) {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val m = FILES.firstOrNull { it.bytes == size } ?: error("that file is not one of the tap-to-select models ($size bytes)")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "could not open the chosen file" }
                store(context, m, input, done, total, onProgress)
            }
            done += m.bytes
        }
        if (!isReady(context)) error("one model file is still missing — both are needed")
        Log.i("Latent", "select: models imported from files and verified")
    }

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    private fun open(context: Context, m: ModelFile): OrtSession {
        check(isReady(context)) { "the tap-to-select models are not downloaded yet" }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtEnvironment.getEnvironment().createSession(file(context, m).absolutePath, opts)
    }

    /**
     * The summary of a picture (its pixels as ARGB, row by row). The encoder is opened for this and
     * closed straight after: it is needed once per picture, and its working memory is the larger.
     */
    @Synchronized
    fun embed(context: Context, px: IntArray, w: Int, h: Int): Embedding {
        val t0 = System.currentTimeMillis()
        val s = encoder ?: open(context, ENCODER).also { encoder = it }
        try {
            return encode(s, px, w, h).also { Log.i("Latent", "select: picture read in ${System.currentTimeMillis() - t0} ms") }
        } finally {
            runCatching { s.close() }; encoder = null
        }
    }

    /** The outlines for these taps, on a grid of [outW] × [outH] cells (see [outlines]). */
    @Synchronized
    fun outlines(context: Context, e: Embedding, taps: List<Tap>, outW: Int, outH: Int): Answer {
        val s = decoder ?: open(context, DECODER).also { decoder = it }
        return decode(s, e, taps, outW, outH)
    }

    /** Frees both models' memory; they load again when next needed. */
    @Synchronized
    fun release() { runCatching { encoder?.close() }; runCatching { decoder?.close() }; encoder = null; decoder = null }

    /** A picture's summary, and the size it was read at (the long side scaled to [SIZE]). */
    class Embedding(val data: FloatArray, val scaledW: Int, val scaledH: Int)

    /** A tap at (u, v), 0..1 across and down: [add] true for "this belongs", false for "not this". */
    data class Tap(val u: Float, val v: Float, val add: Boolean = true)

    /**
     * The model's answer: outlines as weights per cell (0 outside … 1 inside; along the edge, how much
     * of the cell the object covers), each with the model's confidence. One tap: three, smallest to
     * largest. More taps: one.
     */
    class Answer(val masks: List<FloatArray>, val scores: FloatArray, val width: Int, val height: Int)

    // ---------------------------------------------------------------------------------------------
    // The arithmetic, kept free of Android so it can be checked against the tested Python on a computer.
    // ---------------------------------------------------------------------------------------------

    /**
     * The encoder's input: the picture scaled (bilinear) so its long side is [SIZE], normalised, and
     * placed at the top left of a black [SIZE] square, channel by channel.
     */
    fun prepare(px: IntArray, w: Int, h: Int): Triple<FloatBuffer, Int, Int> {
        val scale = SIZE.toFloat() / maxOf(w, h)
        val sw = minOf(SIZE, (w * scale + 0.5f).toInt()); val sh = minOf(SIZE, (h * scale + 0.5f).toInt())
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        val fx = w.toFloat() / sw; val fy = h.toFloat() / sh
        for (y in 0 until sh) {
            val syf = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = syf.toInt(); val y1 = minOf(y0 + 1, h - 1); val ty = syf - y0
            for (x in 0 until sw) {
                val sxf = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = sxf.toInt(); val x1 = minOf(x0 + 1, w - 1); val tx = sxf - x0
                val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
                for (ch in 0 until 3) {
                    val sh8 = 16 - 8 * ch
                    val top = ((a shr sh8) and 0xFF) * (1 - tx) + ((b shr sh8) and 0xFF) * tx
                    val bot = ((c shr sh8) and 0xFF) * (1 - tx) + ((d shr sh8) and 0xFF) * tx
                    out[ch * plane + y * SIZE + x] = (top * (1 - ty) + bot * ty - MEAN[ch]) / STD[ch]
                }
            }
        }
        // the padding is zero after normalisation, as the model was trained (it pads the normalised picture)
        return Triple(FloatBuffer.wrap(out), sw, sh)
    }

    /** Runs the encoder on a picture. */
    fun encode(s: OrtSession, px: IntArray, w: Int, h: Int): Embedding {
        val (input, sw, sh) = prepare(px, w, h)
        val env = OrtEnvironment.getEnvironment()
        val data = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r -> FloatArray(EMBED).also { (r[0] as OnnxTensor).floatBuffer.get(it) } }
        }
        return Embedding(data, sw, sh)
    }

    /** Each mask cell is decided from [FINE] × [FINE] finer cells: the edge comes out anti-aliased. */
    const val FINE = 4

    /**
     * Runs the decoder. Taps are given in the scaled picture's pixels, with the model's "no more
     * taps" marker after them. The decoder itself stretches its answer to [FINE] times [outW] ×
     * [outH] (which must have the picture's shape); a fine cell is inside where the model says so,
     * and a mask cell's weight is the share of its fine cells inside — a hard outline, smoothly edged.
     * (The model's own soft values were not used: where it is unsure, as in the largest answer's
     * background, they hover near a half and would paint a faint veil.)
     */
    fun decode(s: OrtSession, e: Embedding, taps: List<Tap>, outW: Int, outH: Int): Answer {
        require(taps.isNotEmpty())
        val env = OrtEnvironment.getEnvironment()
        val n = taps.size + 1
        val coords = FloatArray(n * 2); val labels = FloatArray(n)
        taps.forEachIndexed { i, t -> coords[2 * i] = t.u * e.scaledW; coords[2 * i + 1] = t.v * e.scaledH; labels[i] = if (t.add) 1f else 0f }
        labels[n - 1] = -1f
        val inputs = linkedMapOf(
            "image_embeddings" to OnnxTensor.createTensor(env, FloatBuffer.wrap(e.data), longArrayOf(1, 256, 64, 64)),
            "point_coords" to OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf((outH * FINE).toFloat(), (outW * FINE).toFloat())), longArrayOf(2)),
        )
        try {
            s.run(inputs).use { r ->
                val fw = outW * FINE; val fine = fw * outH * FINE
                val all = FloatArray(4 * fine).also { (r[0] as OnnxTensor).floatBuffer.get(it) }
                val sc = FloatArray(4).also { (r[1] as OnnxTensor).floatBuffer.get(it) }
                fun weights(k: Int): FloatArray {
                    val w = FloatArray(outW * outH)
                    for (j in 0 until outH * FINE) for (i in 0 until fw) if (all[k * fine + j * fw + i] > 0f) w[(j / FINE) * outW + i / FINE] += 1f
                    val per = 1f / (FINE * FINE)
                    for (c in w.indices) w[c] *= per
                    return w
                }
                if (taps.size > 1) return Answer(listOf(weights(0)), floatArrayOf(sc[0]), outW, outH)
                // one tap: the three answers (the first output is meant for several taps), smallest first
                val three = (1..3).map { k -> Triple(weights(k), sc[k], (0 until fine).count { i -> all[k * fine + i] > 0f }) }.sortedBy { it.third }
                return Answer(three.map { it.first }, FloatArray(3) { three[it].second }, outW, outH)
            }
        } finally { inputs.values.forEach { it.close() } }
    }
}
