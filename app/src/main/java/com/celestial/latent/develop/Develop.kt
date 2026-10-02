package com.celestial.latent.develop

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.libraw.RawDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Develops a captured DNG through the spektrafilm engine and saves a JPEG beside it.
 *
 * Engine and RAW decoder are GPLv3 / LGPL modules fetched at build time from the pinned
 * mirror — see NOTICE.md. This file is only the glue: decode → simulate → encode → save.
 */
object Develop {

    /**
     * A decoded image held in memory while the darkroom is open, so each edit re-renders
     * without decoding the RAW again. close() frees the native buffer.
     */
    class Source(val image: LinearImage, val width: Int, val height: Int) : AutoCloseable {
        /** Colour noise is cleaned once per decode, not once per render. */
        @Volatile var denoised = false
        @Volatile var diffused = false
        @Volatile var printDiffused = false

        /** Refills this image from [from], so one working buffer can be reused. */
        fun refillFrom(from: Source) {
            val src = from.image.data
            val dst = image.data
            src.rewind(); dst.rewind()
            dst.put(src)
            src.rewind(); dst.rewind()
            denoised = false
            diffused = false
            printDiffused = false
        }

        /** An empty copy of the same shape, to be refilled. */
        fun blankLike(): Source {
            val dup = java.nio.ByteBuffer.allocateDirect(image.data.capacity()).order(java.nio.ByteOrder.nativeOrder())
            return Source(LinearImage(dup, width, height, colorSpace = image.colorSpace), width, height)
        }
        override fun close() = image.close()
    }

    /**
     * Keeps the last couple of decoded images so returning to a photo is instant. Keyed by
     * source and cap; evicted oldest-first, and every evicted buffer is freed.
     */
    object Cache {
        // Two photos' worth: each keeps a pristine decode and its working copy.
        private const val MAX = 4
        private val entries = LinkedHashMap<String, Source>()

        @Synchronized fun get(key: String): Source? = entries[key]

        @Synchronized fun put(key: String, src: Source) {
            entries[key] = src
            while (entries.size > MAX) {
                val oldest = entries.keys.first()
                entries.remove(oldest)?.close()
                Log.i("Latent", "source cache: evicted $oldest")
            }
        }

        @Synchronized fun clear() { entries.values.forEach { it.close() }; entries.clear() }
    }

    /**
     * The ISO a capture was taken at, or 0 when unknown. Read straight from the file's TIFF
     * header (tag 34855) rather than pulling in an EXIF library for one number.
     */
    fun isoOf(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64 * 1024)
            val n = input.read(head)
            if (n < 16) 0 else readIsoTag(head, n)
        } ?: 0
    } catch (t: Throwable) { 0 }

    private fun readIsoTag(b: ByteArray, len: Int): Int {
        val little = b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte()
        val big = b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte()
        if (!little && !big) return 0
        fun u16(o: Int) = if (o + 1 >= len) 0 else
            if (little) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = if (o + 3 >= len) 0 else
            if (little) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        // Walk the first IFD, then the Exif IFD it points to, looking for tag 34855 (ISO).
        fun scan(off: Int, depth: Int): Int {
            if (off <= 0 || off + 2 > len || depth > 2) return 0
            val count = u16(off)
            var exifOff = 0
            for (i in 0 until count) {
                val e = off + 2 + i * 12
                if (e + 12 > len) break
                when (u16(e)) {
                    34855 -> return u16(e + 8)          // ISO, SHORT in place
                    34665 -> exifOff = u32(e + 8)       // Exif IFD pointer
                }
            }
            return if (exifOff > 0) scan(exifOff, depth + 1) else 0
        }
        return scan(u32(4), 0)
    }

    /**
     * A cached decode. Colour-noise cleanup and Latent's own diffusion both modify the decoded
     * pixels in place, so their settings are part of the key: changing either must re-decode,
     * otherwise the edit would silently do nothing on an already-processed copy.
     */
    fun openCached(context: Context, source: Uri, isRaw: Boolean, maxEdge: Int, recipe: Recipe, iso: Int,
                   softenMask: ExposureMap? = null, log: (String) -> Unit = {}): Source {
        // The pristine decode is cached on its own, so changing a pre-engine setting costs a
        // copy rather than a fresh decode of the file (which was over a second every time).
        val key = "$source@$maxEdge"
        val pristine = Cache.get(key) ?: run {
            val decoded = if (isRaw) openRaw(context, source, maxEdge, log) else openImage(context, source, maxEdge)
            Cache.put(key, decoded)
            decoded
        }
        // One working buffer per photo and size, reused: no allocation churn while a slider
        // moves, and nothing is freed while a render might still be reading it.
        val workKey = "$key|work"
        val working = Cache.get(workKey)?.takeIf { it.width == pristine.width && it.height == pristine.height }
            ?: pristine.blankLike().also { Cache.put(workKey, it) }
        working.refillFrom(pristine)
        denoiseSource(working, recipe, iso, log)
        fastDiffusionSource(working, recipe, preview = true, softenMask = softenMask, log = log)
        fastPrintDiffusionSource(working, recipe, preview = true, log = log)
        return working
    }

    /**
     * Decode a RAW once, capped to [maxEdge] (0 = full size). Some DNGs ignore the decoder's
     * own cap, so the result is box-downsampled here when it comes back too large — otherwise
     * every later render silently does full-resolution work.
     */
    fun openRaw(context: Context, dng: Uri, maxEdge: Int = 0, log: (String) -> Unit = {}): Source {
        val t0 = System.nanoTime()
        val decoded = context.contentResolver.openFileDescriptor(dng, "r")?.use {
            RawDecoder.decodeToLinear(it.fd, RawDecoder.Settings(maxLongEdge = maxEdge))
        } ?: error("could not open $dng")
        val w = decoded.width; val h = decoded.height
        Log.i("Latent", "decode: ${w}x$h in ${(System.nanoTime() - t0) / 1_000_000} ms (asked for max $maxEdge)")
        log("decoded ${w}×$h")
        val longest = maxOf(w, h)
        if (maxEdge <= 0 || longest <= maxEdge) {
            return Source(LinearImage(decoded.data, w, h, colorSpace = decoded.colorSpace, onClose = { RawDecoder.freeOffHeap(it) }), w, h)
        }
        // The cap was ignored: shrink it ourselves, then free the big native buffer.
        var step = 1
        while (longest / step > maxEdge) step++
        val outW = (w + step - 1) / step; val outH = (h + step - 1) / step
        Log.i("Latent", "decoder ignored the cap; downsampling 1/$step to ${outW}x$outH")
        log("downsampling to ${outW}×$outH")
        val src = decoded.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(outW * outH * 3 * 4).order(ByteOrder.nativeOrder())
        val of = out.asFloatBuffer()
        val acc = FloatArray(3)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                acc[0] = 0f; acc[1] = 0f; acc[2] = 0f
                var n = 0
                for (dy in 0 until step) {
                    val sy = y * step + dy
                    if (sy >= h) break
                    for (dx in 0 until step) {
                        val sx = x * step + dx
                        if (sx >= w) break
                        val i = (sy * w + sx) * 3
                        acc[0] += src.get(i); acc[1] += src.get(i + 1); acc[2] += src.get(i + 2); n++
                    }
                }
                val inv = if (n > 0) 1f / n else 0f
                of.put(acc[0] * inv); of.put(acc[1] * inv); of.put(acc[2] * inv)
            }
        }
        RawDecoder.freeOffHeap(decoded.data)
        return Source(LinearImage(out, outW, outH, colorSpace = decoded.colorSpace), outW, outH)
    }

    /** Decode an already-processed image once, linearised for the engine. */
    fun openImage(context: Context, image: Uri, maxEdge: Int = 0): Source {
        val src = context.contentResolver.openInputStream(image)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: error("could not open $image")
        val scale = if (maxEdge > 0) minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height)) else 1f
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val w = bmp.width; val h = bmp.height
        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        val row = IntArray(w)
        // sRGB's curve removed, then sRGB primaries → ProPhoto primaries. The engine always
        // reads incoming pixels as linear ProPhoto regardless of the label, so this conversion
        // has to happen here; without it a JPEG source develops muted and slightly off-hue.
        val lut = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = lut[(p shr 16) and 0xFF]; val g = lut[(p shr 8) and 0xFF]; val b = lut[p and 0xFF]
                f.put(0.5294f * r + 0.3300f * g + 0.1406f * b)
                f.put(0.0983f * r + 0.8735f * g + 0.0282f * b)
                f.put(0.0168f * r + 0.1178f * g + 0.8654f * b)
            }
        }
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return Source(LinearImage(buf, w, h, colorSpace = "ProPhoto RGB"), w, h)
    }

    /**
     * Render an already-decoded source. [preview] uses the engine's own downscaled fast path,
     * which is also the only path that honours the GPU preview flag.
     */
    @Volatile var cancelRequested = false

    /**
     * The per-pixel spatial stages: the costly ones. Dropped for the quick pass while a control
     * is moving, unless that control is one of them.
     */
    fun withoutSpatial(r: Recipe, keep: String?): Recipe = r.copy(
        grain = if (keep == "grain") r.grain else false,
        halation = if (keep == "halation") r.halation else false,
        diffusion = if (keep == "diffusion") r.diffusion else false,
        printDiffusion = if (keep == "diffusion") r.printDiffusion else false,
        glare = if (keep == "glare") r.glare else false,
    )

    /**
     * Cleans colour noise in place before a render. Applied once per decoded source: the film
     * should never see sensor blotches, because its dye couplers make them worse.
     */
    fun denoiseSource(source: Source, recipe: Recipe, iso: Int, log: (String) -> Unit = {}) {
        if (source.denoised) return
        val strength = if (recipe.chromaDenoise >= 0f) recipe.chromaDenoise else ChromaDenoise.strengthForIso(iso)
        if (strength > 0.001f) {
            log("cleaning colour noise")
            ChromaDenoise.apply(source.image.data, source.width, source.height, strength)
        }
        source.denoised = true
    }

    /**
     * Latent's own diffusion, applied before the film stage when chosen. The engine works in
     * film dimensions, so the blur is scaled by how much of the negative one pixel covers.
     */
    /**
     * The enlarger's diffusion filter, computed fast.
     *
     * The engine applies it inside the print stage, where nothing outside can reach. But that
     * stage blooms the NEGATIVE, and a negative is the image in density — the inverse of light.
     * So the same filter applied to the density form of the image, before the engine, spreads
     * light from the same places: out of what will become the print's shadows rather than its
     * highlights, which is the softer, lifted look the enlarger filter gives.
     *
     * This is an emulation of where the stage sits, not a replica of it. The alternative is the
     * engine's own, which is faithful and takes minutes at full size.
     */
    fun fastPrintDiffusionSource(source: Source, recipe: Recipe, preview: Boolean = false, log: (String) -> Unit = {}) {
        if (!((recipe.fastDiffusion || preview) && recipe.printDiffusion)) return
        if (source.printDiffused) return
        log("enlarger filter (in density)")
        val buf = source.image.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val n = source.width * source.height * 3

        // Into density: d = -log10(light). Bounded so pure black cannot become infinite.
        for (i in 0 until n) {
            val v = buf.get(i).coerceAtLeast(1e-5f)
            buf.put(i, (-Math.log10(v.toDouble())).toFloat().coerceIn(-1f, 5f))
        }
        val longest = maxOf(source.width, source.height)
        FilmDiffusion.apply(
            source.image.data, source.width, source.height,
            recipe.printDiffusionFamily, recipe.printDiffusionStrength, recipe.diffusionScale,
            1f, 1f, 1f, 1f, 1f, 1f, 0f,
            recipe.filmFormatMm * 1000f / longest,
        )
        // And back to light.
        for (i in 0 until n) {
            buf.put(i, Math.pow(10.0, -buf.get(i).toDouble()).toFloat().coerceIn(0f, 1e5f))
        }
        source.printDiffused = true
    }

    fun fastDiffusionSource(source: Source, recipe: Recipe, preview: Boolean = false,
                            softenMask: ExposureMap? = null, log: (String) -> Unit = {}) {
        // Previews always take the fast path; only the export honours the setting — except with a
        // painted soften mask, which only this path can follow (the engine's own cannot be painted).
        // The FULL switch stays the master: diffusion off is off everywhere, mask or not.
        if (!((recipe.fastDiffusion || preview || softenMask != null) && recipe.diffusion)) return
        if (source.diffused) return
        log(if (softenMask != null) "diffusion filter, where painted" else "diffusion filter")
        val longest = maxOf(source.width, source.height)
        val pixelSizeUm = recipe.filmFormatMm * 1000f / longest
        // Painted: keep the sharp picture, diffuse, then blend the two by the mask.
        val sharp = if (softenMask != null) scratchCopy(source) else null
        FilmDiffusion.apply(
            source.image.data, source.width, source.height,
            recipe.diffusionFamily, recipe.diffusionStrength, recipe.diffusionScale,
            recipe.diffusionCore, recipe.diffusionCoreSize,
            recipe.diffusionHalo, recipe.diffusionHaloSize,
            recipe.diffusionBloom, recipe.diffusionBloomSize,
            recipe.diffusionWarmth, pixelSizeUm,
        )
        if (sharp != null && softenMask != null) blendByMask(source, sharp, softenMask)
        source.diffused = true
    }

    /**
     * One reusable buffer for the sharp copy, so painting does not set aside a new one on every
     * repaint. Renders never overlap (they queue for the engine lane), so one is enough. A very
     * large one (a full-size develop) is let go afterwards rather than held.
     */
    @Volatile private var scratch: java.nio.ByteBuffer? = null

    private fun scratchCopy(source: Source): java.nio.ByteBuffer {
        val n = source.width * source.height * 3 * 4
        val buf = scratch?.takeIf { it.capacity() >= n }
            ?: java.nio.ByteBuffer.allocateDirect(n).order(java.nio.ByteOrder.nativeOrder()).also { scratch = it }
        buf.clear()
        val src = source.image.data.duplicate().order(java.nio.ByteOrder.nativeOrder())
        src.clear(); src.limit(n)
        buf.put(src); buf.flip()
        return buf
    }

    /**
     * out = diffused × mask + sharp × (1 − mask), the mask sampled at each pixel's place. Written
     * this way it is exact at both ends — a mask of 1 gives the diffused picture bit for bit, so
     * "softened everywhere" painted is the same as the plain setting (the other form,
     * sharp + (diffused − sharp) × mask, leaves a rounding residue at 1).
     */
    private fun blendByMask(source: Source, sharp: java.nio.ByteBuffer, mask: ExposureMap) {
        val w = source.width; val h = source.height
        val out = source.image.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val sh = sharp.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val m = mask.sample((x + 0.5f) / w, v).coerceIn(0f, 1f)
                val i = (y * w + x) * 3
                for (c in 0 until 3) {
                    out.put(i + c, out.get(i + c) * m + sh.get(i + c) * (1f - m))
                }
            }
        }
        if (sharp.capacity() > 64 * 1024 * 1024) scratch = null   // a full-size copy is not kept
    }

    /**
     * The engine to develop with. Once Latent has copied the engine's assets into its own
     * storage — which it does the first time a film is built — everything runs from that copy,
     * so films Latent has made are available everywhere a measured one is. The copy is
     * byte-for-byte, so nothing else changes.
     */
    fun engineFor(context: Context): SpektraEngine {
        val dir = EngineAssets.directory
        return if (dir != null) SpektraEngine(dir) else SpektraEngine.fromAssets(context.assets)
    }

    /** Render with an engine the caller supplies — used while building a film, when the profile
     *  changes between attempts and a fresh engine is needed each time. */
    fun renderWith(engine: SpektraEngine, context: Context, source: Source, recipe: Recipe, preview: Boolean): Pair<ByteArray, Pair<Int, Int>> {
        var dims = 0 to 0
        val params = sanitised(recipe).toParams()
        val jpeg = run {
            val result = if (preview) engine.simulatePreview(source.image, params) else engine.simulate(source.image, params)
            result.use { r -> dims = r.width to r.height; toJpeg(r.data, r.width, r.height, r.colorSpace) }
        }
        return jpeg to dims
    }

    /**
     * @param upscale print size for a full develop. The engine enlarges the photo BEFORE the film
     *   is exposed and works out a smaller pixel on the virtual negative to match, so grain,
     *   halation and diffusion are generated at the new size — fine grain native to the bigger
     *   picture, not small grain blown up. No new detail is invented. Ignored for previews, which
     *   would only get slower.
     */
    fun render(context: Context, source: Source, recipe: Recipe, preview: Boolean, upscale: Float = 1f,
               exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null, log: (String) -> Unit = {}): Pair<ByteArray, Pair<Int, Int>> {
        val t = System.nanoTime()
        Log.i("Latent", "render start: source ${source.width}x${source.height}, preview=$preview, cap=${recipe.previewMaxSize}")
        Log.i("Latent", "recipe: " + sanitised(recipe).summary())
        var dims = 0 to 0
        // GPU is preview-only: a full render always goes through the CPU engine.
        val base = sanitised(if (preview) recipe else recipe.copy(gpuPreview = false))
            // Our own filter has already run on the pixels, so the engine's LENS filter stays
            // off — but the enlarger's is a different stage, later in the chain, and is left to
            // the engine. Switching both off was dropping half the glow.
            .let { if (it.diffusion && (it.fastDiffusion || preview || softenMask != null)) it.copy(diffusion = false) else it }
            .let { if (it.printDiffusion && (it.fastDiffusion || preview)) it.copy(printDiffusion = false) else it }
        // Our own spaces are built from the engine's sRGB output, so ask it for sRGB.
        val ourSpace = if (OutputSpace.isOurs(base.outputColorSpace)) base.outputColorSpace else ""
        val params = (if (ourSpace.isEmpty()) base else base.copy(outputColorSpace = OutputSpace.ENGINE_SRGB)).toParams()
            .let { if (!preview && upscale > 1.001f) it.copy(io = it.io.copy(upscaleFactor = upscale)) else it }
            // Dodge & burn: the engine multiplies the enlarger light by the map, place by place.
            .let { p -> if (exposureMap == null || exposureMap.isBlank) p else p.copy(enlarger = p.enlarger.copy(
                printExposureMap = exposureMap.multipliers(),
                printExposureMapWidth = exposureMap.width, printExposureMapHeight = exposureMap.height)) }
        if (!preview && upscale > 1.001f) {
            Log.i("Latent", "print size ${upscale}×: ${source.width}x${source.height} -> about " +
                "${(source.width * upscale).toInt()}x${(source.height * upscale).toInt()}")
        }
        // Full develops log their memory peak: it is what decides how large a print the phone can make.
        val watch = if (!preview) MemoryWatch(context) else null
        val jpeg = try {
            engineFor(context).use { engine ->
                val result = if (preview) engine.simulatePreview(source.image, params) else engine.simulate(source.image, params)
                result.use { r -> dims = r.width to r.height; toJpeg(r.data, r.width, r.height, r.colorSpace, ourSpace) }
            }
        } finally {
            watch?.finish("full develop ${dims.first}x${dims.second}" + (if (upscale > 1.001f) " at $upscale×" else ""))
        }
        Log.i("Latent", "render done: ${dims.first}x${dims.second} in ${(System.nanoTime() - t) / 1_000_000} ms")
        log((if (preview) "preview" else "full") + " ${dims.first}×${dims.second} in ${(System.nanoTime() - t) / 1_000_000} ms" +
            (if (preview && recipe.gpuPreview) " · GPU preview requested" else ""))
        return jpeg to dims
    }


    /**
     * Watches this app's memory during a full develop, on a background thread, and logs the peak.
     *
     * Large prints are limited by memory, not time: the engine works in double precision, so a
     * 50-megapixel image is over a gigabyte per working copy. If the phone runs short, Android simply
     * ends the app, with no error to show. This measures how close a develop came, so the next size
     * up is decided on numbers. Reads /proc/self/status (resident memory) every 100 ms.
     */
    private class MemoryWatch(context: Context) {
        private fun residentMb(): Long = runCatching {
            File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
                .trim().split(Regex("\\s+"))[1].toLong() / 1024
        }.getOrDefault(-1L)
        private val beforeMb = residentMb()
        private val phone = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        @Volatile private var peakMb = beforeMb
        @Volatile private var watching = true
        private val sampler = Thread {
            while (watching) {
                val now = residentMb(); if (now > peakMb) peakMb = now
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }

        fun finish(what: String) {
            watching = false
            sampler.interrupt(); runCatching { sampler.join(300) }
            val end = residentMb(); if (end > peakMb) peakMb = end
            Log.i("Latent", "memory for $what: ${beforeMb} MB before, peak ${peakMb} MB (+${peakMb - beforeMb} MB); " +
                "the phone had ${phone.availMem / 1048576} MB free of ${phone.totalMem / 1048576} MB when it started" +
                (if (phone.lowMemory) " (already low)" else ""))
        }
    }

    /**
     * The engine returns display-referred FLOAT RGB (three floats per pixel, 0..1) in its output
     * colour space — not bytes. Clamp, quantise to 8 bit, and tag the bitmap with that space so
     * the system colour-manages it and embeds the right profile on export.
     */
    private fun toJpeg(data: ByteBuffer, w: Int, h: Int, colorSpace: com.spectrafilm.engine.ColorSpace, ourSpace: String = ""): ByteArray {
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val bmp = taggedBitmap(w, h, colorSpace, ourSpace)
        val bandRows = (1024 * 1024 / w).coerceIn(1, h)
        val strip = IntArray(w * bandRows)
        var y = 0
        while (y < h) {
            val rows = minOf(bandRows, h - y)
            var k = 0
            var i = y * w * 3
            repeat(w * rows) {
                val r = (f.get(i).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (f.get(i + 1).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (f.get(i + 2).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                strip[k++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            // Display P3 and Rec.709 are not in the engine's list: converted here, from its
            // sRGB output, and the bitmap is tagged so viewers read the file correctly.
            if (OutputSpace.isOurs(ourSpace)) OutputSpace.convert(strip, ourSpace)
            bmp.setPixels(strip, 0, w, 0, y, w, rows)
            y += rows
        }
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Bitmap tagged with the engine's output colour space, falling back to sRGB. */
    private fun taggedBitmap(w: Int, h: Int, cs: com.spectrafilm.engine.ColorSpace, ourSpace: String = ""): android.graphics.Bitmap {
        OutputSpace.androidSpace(ourSpace)?.let { space ->
            return runCatching {
                android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888, false, space)
            }.getOrElse { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
        }
        val named = when (cs) {
            com.spectrafilm.engine.ColorSpace.SRGB -> android.graphics.ColorSpace.Named.SRGB
            com.spectrafilm.engine.ColorSpace.ADOBE_RGB -> android.graphics.ColorSpace.Named.ADOBE_RGB
            com.spectrafilm.engine.ColorSpace.PROPHOTO -> android.graphics.ColorSpace.Named.PRO_PHOTO_RGB
            com.spectrafilm.engine.ColorSpace.REC2020 -> android.graphics.ColorSpace.Named.BT2020
            com.spectrafilm.engine.ColorSpace.LINEAR_SRGB -> android.graphics.ColorSpace.Named.LINEAR_SRGB
            else -> android.graphics.ColorSpace.Named.SRGB
        }
        return runCatching {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888, false, android.graphics.ColorSpace.get(named))
        }.getOrElse { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
    }

    /**
     * Every FILMING profile the engine bundles. A printing profile (2383, Endura…) in the film
     * slot makes the engine fail with "internal error": each profile declares its stage.
     */
    val FILMS = listOf(
        "kodak_portra_400" to "Portra 400",
        "kodak_portra_160" to "Portra 160",
        "kodak_portra_800" to "Portra 800",
        "kodak_portra_800_push1" to "Portra 800 +1",
        "kodak_portra_800_push2" to "Portra 800 +2",
        "kodak_gold_200" to "Gold 200",
        "kodak_ultramax_400" to "Ultramax 400",
        "kodak_ektar_100" to "Ektar 100",
        "kodak_ektachrome_100" to "Ektachrome 100",
        "kodak_kodachrome_64" to "Kodachrome 64",
        "fujifilm_c200" to "C200",
        "fujifilm_xtra_400" to "X-Tra 400",
        "fujifilm_pro_400h" to "Pro 400H",
        "fujifilm_provia_100f" to "Provia 100F",
        "fujifilm_velvia_100" to "Velvia 100",
        "kodak_vision3_50d" to "Vision3 50D",
        "kodak_vision3_250d" to "Vision3 250D",
        "kodak_vision3_200t" to "Vision3 200T",
        "kodak_vision3_500t" to "Vision3 500T",
        "kodak_verita_200d" to "Verita 200D",
    )

    /** Every PRINTING profile: papers for stills, print stocks for the cine films. */
    val PAPERS = listOf(
        "kodak_portra_endura" to "Portra Endura",
        "kodak_supra_endura" to "Supra Endura",
        "kodak_ultra_endura" to "Ultra Endura",
        "kodak_endura_premier" to "Endura Premier",
        "kodak_ektacolor_edge" to "Ektacolor Edge",
        "fujifilm_crystal_archive_typeii" to "Crystal Archive II",
        "kodak_2383" to "Vision 2383 (cine print)",
        "kodak_2393" to "Vision Premier 2393",
    )

    const val DEFAULT_PAPER = "kodak_portra_endura"

    /**
     * The print stock each film was designed for, taken from the profiles' own `target_print`.
     * Slide films (null) have no print stage at all — they are scanned as positives.
     */
    private val TARGET_PRINT = mapOf(
        "kodak_portra_160" to "kodak_portra_endura",
        "kodak_portra_400" to "kodak_portra_endura",
        "kodak_portra_800" to "kodak_portra_endura",
        "kodak_portra_800_push1" to "kodak_portra_endura",
        "kodak_portra_800_push2" to "kodak_portra_endura",
        "kodak_gold_200" to "kodak_portra_endura",
        "kodak_ultramax_400" to "kodak_portra_endura",
        "kodak_ektar_100" to "kodak_portra_endura",
        "fujifilm_c200" to "fujifilm_crystal_archive_typeii",
        "fujifilm_pro_400h" to "fujifilm_crystal_archive_typeii",
        "fujifilm_xtra_400" to "fujifilm_crystal_archive_typeii",
        "kodak_vision3_50d" to "kodak_2383",
        "kodak_vision3_250d" to "kodak_2383",
        "kodak_vision3_200t" to "kodak_2383",
        "kodak_vision3_500t" to "kodak_2383",
        "kodak_verita_200d" to "kodak_2383",
        // Slide films: no print. Scanned directly as positives.
        "fujifilm_provia_100f" to null,
        "fujifilm_velvia_100" to null,
        "kodak_ektachrome_100" to null,
        "kodak_kodachrome_64" to null,
    )

    /** True for a slide film: there is no print stage, so the negative is scanned directly. */
    fun isSlideFilm(film: String) = TARGET_PRINT.containsKey(film) && TARGET_PRINT[film] == null

    /** The paper this film was meant to be printed on. */
    fun targetPrint(film: String): String? = TARGET_PRINT[film]

    /**
     * Sets the paper (and the direct-scan switch) to what the chosen film was designed for.
     * Called when the film changes, so the default pairing is always the authentic one.
     */
    fun pairedWithFilm(r: Recipe): Recipe {
        val target = TARGET_PRINT[r.film]
        return if (target == null && TARGET_PRINT.containsKey(r.film)) r.copy(scanFilm = true)
               else r.copy(paper = target ?: r.paper, scanFilm = false)
    }

    /** Guard against a saved recipe pointing at a profile in the wrong slot. */
    /** True for a film Latent has made: not in the bundled list, but real on disk. */
    fun isOurStock(id: String) = id.startsWith("celestial_") && EngineAssets.profileFile(id)?.exists() == true

    fun sanitised(r: Recipe): Recipe {
        // A generated emulsion is not in the bundled list but is a real profile in the engine's
        // folder. Without this it would be swapped for a bundled film and every film Latent
        // built would develop identically.
        val film = if (FILMS.any { it.first == r.film } || isOurStock(r.film)) r.film else FILMS.first().first
        val paper = if (PAPERS.any { it.first == r.paper }) r.paper else DEFAULT_PAPER
        val fixed = if (film == r.film && paper == r.paper) r else r.copy(film = film, paper = paper)
        // A slide film has no print stage; a negative must not be scanned directly by accident.
        return if (isSlideFilm(fixed.film) && !fixed.scanFilm) fixed.copy(scanFilm = true) else fixed
    }

    fun availableProfiles(context: Context): List<String> =
        Develop.engineFor(context).use { it.listProfiles() }

    /**
     * Develop a RAW file.
     * @param maxEdge longest edge to decode; a small value for a quick look, 0 for full size.
     * Returns the saved image's uri. A new file is written every time; nothing is replaced.
     */
    /**
     * Develop a RAW file and save the result. One path for every caller — auto-develop, the
     * roll, and the darkroom's full-size button — so a fix here reaches all of them.
     */
    fun developDng(context: Context, dng: Uri, recipe: Recipe, maxEdge: Int = 0, log: (String) -> Unit = {}): Uri =
        developFull(context, dng, isRaw = true, recipe = recipe, maxEdge = maxEdge, log = log)

    /**
     * Develop an already-processed image (a JPEG from the Xiaomi modes, or an imported photo).
     * The engine expects linear light, so the file is decoded and linearised first. This is film
     * applied over someone else's rendering — a look rather than a simulation.
     */
    /** Film over an already-processed image (the Xiaomi modes, or an import). */
    fun developJpeg(context: Context, image: Uri, recipe: Recipe, maxEdge: Int = 0, log: (String) -> Unit = {}): Uri =
        developFull(context, image, isRaw = false, recipe = recipe, maxEdge = maxEdge, log = log)

    /**
     * The source file's name, for naming the developed copy. An imported file comes from the
     * document picker rather than the gallery, and such a provider need not answer a MediaStore
     * column — asking can throw. Falls back to the standard document name, then to "LATENT".
     */
    private fun baseNameOf(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "LATENT"
        return name.substringBeforeLast('.').ifBlank { "LATENT" }
    }

    /** Developing again never replaces an earlier result: _2, _3 … are appended as needed. */
    private fun uniqueName(context: Context, name: String): String {
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.')
        var candidate = name; var n = 1
        while (exists(context, candidate)) { n++; candidate = "${stem}_$n.$ext" }
        return candidate
    }

    private fun exists(context: Context, name: String): Boolean =
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("DCIM/Latent%", name), null,
        )?.use { it.count > 0 } ?: false

    /** Full-resolution develop with progress, logging and a new file each time. */
    fun developFull(context: Context, source: Uri, isRaw: Boolean, recipe: Recipe, maxEdge: Int = 0, upscale: Float = 1f,
                    exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null, log: (String) -> Unit = {}): Uri {
        log(if (maxEdge > 0) "decoding…" else "decoding at full size…")
        val src = if (isRaw) openRaw(context, source, maxEdge, log) else openImage(context, source, maxEdge)
        return src.use { s ->
            denoiseSource(s, recipe, isoOf(context, source), log)
            fastDiffusionSource(s, recipe, preview = false, softenMask = softenMask, log = log)
            fastPrintDiffusionSource(s, recipe, preview = false, log = log)
            log("developing ${s.width}×${s.height}…")
            val (bytes, dims) = render(context, s, recipe, preview = false, upscale = upscale, exposureMap = exposureMap, softenMask = softenMask, log = log)
            log("saving ${dims.first}×${dims.second}, ${bytes.size / 1024} KB")
            saveDeveloped(context, bytes, source, recipe.film)
        }
    }

    /** A centre crop of the source, for showing the middle of the frame first. */
    fun centreCrop(src: Source, fraction: Float): Source {
        val f = fraction.coerceIn(0.2f, 1f)
        if (f >= 0.999f) return src
        val cw = (src.width * f).toInt().coerceAtLeast(8)
        val ch = (src.height * f).toInt().coerceAtLeast(8)
        val x0 = (src.width - cw) / 2
        val y0 = (src.height - ch) / 2
        val inBuf = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(cw * ch * 3 * 4).order(ByteOrder.nativeOrder())
        val of = out.asFloatBuffer()
        for (y in 0 until ch) {
            var i = ((y0 + y) * src.width + x0) * 3
            for (x in 0 until cw * 3) { of.put(inBuf.get(i)); i++ }
        }
        return Source(LinearImage(out, cw, ch, colorSpace = src.image.colorSpace), cw, ch)
    }

    /** The most recent developed JPEG for a capture, if there is one. */
    fun developedFor(context: Context, source: Uri): Uri? {
        val stem = baseNameOf(context, source)
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("DCIM/Latent%", "$stem!_%.jpg"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }

    fun saveDeveloped(context: Context, bytes: ByteArray, source: Uri, film: String): Uri =
        save(context, bytes, baseNameOf(context, source) + "_" + film.substringAfterLast('_') + ".jpg")

    private fun save(context: Context, bytes: ByteArray, name: String): Uri {
        val unique = uniqueName(context, name)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, unique)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.i("Latent", "developed file saved: $unique")
        return uri
    }
}
