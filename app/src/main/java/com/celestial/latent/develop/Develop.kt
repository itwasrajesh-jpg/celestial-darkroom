package com.celestial.latent.develop

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlin.math.roundToLong
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
        /**
         * The share of the film's width this picture shows (1 = the whole frame). Straightening
         * crops, so the framed picture covers less film; grain, halation and glow are sized from
         * "film width ÷ picture width", so they read this to stay at their true size.
         */
        @Volatile var filmScale = 1f
        /**
         * 95a: this picture's pixels per pixel of the photo's own file (1 = full size, 0.3 = the darkroom's
         * small copy). Noise shrinks as a picture is made smaller, so Night Clean cleans less by this much
         * and the preview shows what the full-size develop will.
         */
        @Volatile var nativeScale = 1f
        /**
         * What this working copy was last prepared for (framing, noise cleaning, diffusion). A
         * render never writes into its source — the engine reads it as const, and Latent's own
         * render step only reads it — so a copy prepared for the same things can be used again
         * without preparing it twice. Cleared whenever the pixels are refilled.
         */
        @Volatile var preparedFor: String? = null
        /** Film-still bars go around this picture when it is printed at full size. */
        @Volatile var letterbox = false

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
            filmScale = from.filmScale
            nativeScale = from.nativeScale
            preparedFor = null
            letterbox = from.letterbox
        }

        /**
         * Refills this image from [from] as framed by [f] (this buffer must already be the framed
         * size). An untouched framing is a plain copy; quarter turns and flips move pixels exactly;
         * only straightening resamples, smoothly, from inside the photo.
         */
        fun frameFrom(from: Source, f: Framing) {
            if (f.isIdentity) { refillFrom(from); return }
            val sw = from.width; val sh = from.height
            val (ow, oh) = f.outputSize(sw, sh)
            require(ow == width && oh == height) { "frame buffer is ${width}x$height, framing needs ${ow}x$oh" }
            val src = from.image.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val dst = image.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val exact = kotlin.math.abs(f.straighten) < 0.005f
            for (y in 0 until oh) for (x in 0 until ow) {
                val (xs, ys) = f.toSource(x + 0.5, y + 0.5, sw, sh)
                val o = (y * ow + x) * 3
                if (exact) {
                    // a quarter turn or flip lands each pixel exactly on another: no blending
                    val ix = (xs - 0.5).roundToLong().toInt().coerceIn(0, sw - 1)
                    val iy = (ys - 0.5).roundToLong().toInt().coerceIn(0, sh - 1)
                    val i = (iy * sw + ix) * 3
                    dst.put(o, src.get(i)); dst.put(o + 1, src.get(i + 1)); dst.put(o + 2, src.get(i + 2))
                } else {
                    val fx = (xs - 0.5).coerceIn(0.0, (sw - 1).toDouble())
                    val fy = (ys - 0.5).coerceIn(0.0, (sh - 1).toDouble())
                    val x0 = fx.toInt(); val y0 = fy.toInt()
                    val x1 = minOf(x0 + 1, sw - 1); val y1 = minOf(y0 + 1, sh - 1)
                    val ax = (fx - x0).toFloat(); val ay = (fy - y0).toFloat()
                    for (c in 0 until 3) {
                        val top = src.get((y0 * sw + x0) * 3 + c) * (1 - ax) + src.get((y0 * sw + x1) * 3 + c) * ax
                        val bot = src.get((y1 * sw + x0) * 3 + c) * (1 - ax) + src.get((y1 * sw + x1) * 3 + c) * ax
                        dst.put(o + c, top * (1 - ay) + bot * ay)
                    }
                }
            }
            denoised = false; diffused = false; printDiffused = false
            // straightening and a widescreen crop can both shorten the long edge: the share of
            // the film the picture shows follows whatever survives
            filmScale = from.filmScale * f.filmShare(sw, sh)
            nativeScale = from.nativeScale
            preparedFor = null
            letterbox = f.letterbox
        }

        /** An empty image of a given size, to be framed into. */
        fun blankSized(w: Int, h: Int): Source = pictureSource(w, h, image.colorSpace)

        /** An empty copy of the same shape, to be refilled. */
        fun blankLike(): Source = pictureSource(width, height, image.colorSpace)
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
            if (n < 16) 0 else readTag(head, n, 34855)
        } ?: 0
    } catch (t: Throwable) { 0 }

    /**
     * The file's TIFF orientation (tag 274: 1 upright, 6 turned a quarter clockwise, 8 anticlockwise…),
     * or 0 when unknown. Our camera writes 6 into every DNG, as its sensor lies on its side (75d).
     */
    fun orientationOf(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64 * 1024)
            val n = input.read(head)
            if (n < 16) 0 else readTag(head, n, 274)
        } ?: 0
    } catch (t: Throwable) { 0 }

    /**
     * 86a: stops to brighten one of OUR DNGs by (its BaselineExposure, tag 50730), else 0. An HDR stack is
     * shot dark so the sky keeps its detail; this puts its middle tones back where the preview had them,
     * and the sky lands above 1.0 for the film's shoulder to roll off. Only files our own DngWriter made
     * (Software "Celestial Darkroom") are trusted: other phones' DNGs develop exactly as before.
     */
    fun brightenOf(context: Context, uri: Uri): Float = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64 * 1024)
            var n = 0
            while (n < head.size) { val k = input.read(head, n, head.size - n); if (k < 0) break; n += k }
            if (n < 16) 0f else ourBaselineEv(head, n)
        } ?: 0f
    } catch (t: Throwable) { 0f }

    internal fun ourBaselineEv(b: ByteArray, len: Int): Float {
        if (b[0] != 'M'.code.toByte() || b[1] != 'M'.code.toByte()) return 0f   // our writer is big-endian
        fun u16(o: Int) = if (o + 1 >= len) -1 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun s32(o: Int) = if (o + 3 >= len) 0 else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        val ifd = s32(4)
        val count = u16(ifd)
        if (ifd <= 0 || count <= 0) return 0f
        var ours = false; var ev = 0f
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            if (e + 12 > len) break
            val tag = u16(e); val type = u16(e + 2); val cnt = s32(e + 4)
            if (tag == 305 && type == 2) {
                val at = if (cnt <= 4) e + 8 else s32(e + 8)
                val want = "Celestial Darkroom"
                ours = at >= 0 && at + want.length <= len && String(b, at, want.length, Charsets.US_ASCII) == want
            } else if (tag == 50730 && type == 10 && cnt == 1) {
                val at = s32(e + 8); val num = s32(at); val den = s32(at + 4)
                if (den != 0) ev = num.toFloat() / den
            }
        }
        return if (ours && ev.isFinite()) ev.coerceIn(0f, 4f) else 0f
    }

    /** The size of the picture the darkroom opened for [source] (upright, as the decoder stood it), when still cached (75d). */
    fun openedSize(source: Uri, isRaw: Boolean, maxEdge: Int, pairFirst: Uri? = null): Pair<Int, Int>? {
        val key = if (pairFirst != null && isRaw) "$pairFirst+$source@$maxEdge" else "$source@$maxEdge"
        return Cache.get(key)?.let { it.width to it.height }
    }

    private fun readTag(b: ByteArray, len: Int, want: Int): Int {
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

        // Walk the first IFD, then the Exif IFD it points to, looking for the tag (a SHORT: ISO 34855, orientation 274).
        fun scan(off: Int, depth: Int): Int {
            if (off <= 0 || off + 2 > len || depth > 2) return 0
            val count = u16(off)
            var exifOff = 0
            for (i in 0 until count) {
                val e = off + 2 + i * 12
                if (e + 12 > len) break
                when (u16(e)) {
                    want -> return u16(e + 8)           // SHORT in place
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
    /** The photo's depth, when any light lights surfaces; never stops a develop. */
    private fun sunInput(context: Context, source: Uri, framing: Framing, src: Source, lights: List<RaysLook>, log: (String) -> Unit,
                         fogLit: Boolean = false, wet: Boolean = false): Sun.Input? =
        if (!wet && lights.none { Sun.wanted(it) || Beams.wanted(it) || GodRays.wanted(it) || (fogLit && GlowMist.wanted(it)) }) null else runCatching { Sun.prepare(context, source, framing, src, log) }
            .getOrElse { t -> Log.e("Latent", "light: could not prepare the depth", t); null }

    /**
     * Every light on surfaces, together, before the film; a failure is logged and the photo
     * develops without it. The scene's size is light 1's setting: it describes the scene, not a light.
     */
    /**
     * The recipe settings the preparation reads (noise cleaning, the 85B filter, the two diffusion
     * filters, the film format) — and nothing else, so a change made for the film stage reuses it.
     */
    private fun preparedKey(r: Recipe): String = listOf(
        r.chromaDenoise, r.lumaDenoise, r.nightClean, r.nightCleanStrength, r.nightCleanFollow, r.lens85b,
        r.diffusion, r.diffusionFamily, r.diffusionStrength, r.diffusionScale, r.diffusionCore, r.diffusionCoreSize,
        r.diffusionHalo, r.diffusionHaloSize, r.diffusionBloom, r.diffusionBloomSize, r.diffusionWarmth,
        r.fastDiffusion, r.filmFormatMm, r.printDiffusion, r.printDiffusionFamily, r.printDiffusionStrength,
    ).joinToString(",")

    /** The foreground split for lights limited to a side: the one given, or the photo's saved split; null when no light needs it. */
    private fun splitFor(context: Context, source: Uri, lights: List<RaysLook>, given: ExposureMap?): ExposureMap? =
        if (lights.none { it.placed && it.side > 0.5f }) null else given ?: ExposureMaps.load(context, source, ExposureMaps.DEPTH_SPLIT)

    private fun sunOnSurfaces(src: Source, lights: List<RaysLook>, mask: ExposureMap?, input: Sun.Input?, log: (String) -> Unit, cleanKey: String? = null,
                              split: ExposureMap? = null) {
        if (input == null || lights.isEmpty()) return
        runCatching {
            val scale = lights.first().scale
            val maps = lights.mapNotNull { l -> Sun.lightMap(input, l, scale, log)?.let { l to it } }
            // the lights' bounce off what they hit — a scene setting, like its size (light 1's)
            val bounceAmount = lights.first().bounce
            // a sun through a window bounces from where it lands on the floor (in its own map), not from the frame
            val byEye = maps.filter { it.second.measured <= 0f }
            val bounce = if (bounceAmount > 0f && byEye.any { it.first.surface > 0f }) Sun.bounce(input, byEye, scale, log) else null
            Sun.apply(src, maps, mask, input, log, bounce, bounceAmount, cleanKey, split)
        }.onFailure { t -> Log.e("Latent", "light: could not light the surfaces", t) }
    }

    /** The lights' glow in the air, in 3D, in front of everything; a failure is logged and the photo develops without it. */
    private fun beamsInAir(src: Source, lights: List<RaysLook>, mask: ExposureMap?, fogMask: ExposureMap?, input: Sun.Input?, log: (String) -> Unit,
                           split: ExposureMap? = null) {
        if (input == null || lights.none { Beams.wanted(it) }) return
        runCatching {
            val scale = lights.first().scale
            val airs = lights.mapNotNull { l -> Beams.air(input, l, scale, fogMask, log)?.let { l to it } }
            Beams.apply(src, airs, mask, input, log, split)
        }.onFailure { t -> Log.e("Latent", "light: could not light the air", t) }
    }

    /** A sun's god rays, worked out on the lit picture before the fog (60f); a failure is logged and the photo develops without them. */
    private fun godRaysFor(src: Source, lights: List<RaysLook>, input: Sun.Input?, log: (String) -> Unit): List<GodRays.Map> =
        if (input == null || lights.none { GodRays.wanted(it) }) emptyList()
        else runCatching { GodRays.prepare(src, lights, input, log) }
            .getOrElse { t -> Log.e("Latent", "light: could not work out the god rays", t); emptyList() }

    /** Lays the god rays over the picture, in front of the fog and the light in the air. */
    private fun godRaysOver(src: Source, maps: List<GodRays.Map>, fogMask: ExposureMap?, fogAmount: Float, log: (String) -> Unit) {
        if (maps.isEmpty()) return
        runCatching { GodRays.apply(src, maps, fogMask, fogAmount, log) }
            .onFailure { t -> Log.e("Latent", "light: could not lay the god rays", t) }
    }

    /** Rain on the window (65a), in front of the lens; a failure is logged and the photo develops without it. */
    private fun windowRain(src: Source, look: RainLook, wipe: ExposureMap?, log: (String) -> Unit) {
        if (!Rain.wanted(look)) return
        runCatching { Rain.apply(src, look, wipe, log) }
            .onFailure { t -> Log.e("Latent", "rain: could not put the rain on the window", t) }
    }

    /** The painted ground made wet (64a), after the lights on surfaces and before the air; a failure is logged and the photo develops without it. */
    private fun wetGround(src: Source, mask: ExposureMap?, look: WetLook, input: Sun.Input?, log: (String) -> Unit) {
        if (!Wet.wanted(mask)) return
        if (input == null) { log("wet ground needs the depth model (Settings → Depth test)"); return }
        runCatching { Wet.apply(src, mask!!, look, input, log) }
            .onFailure { t -> Log.e("Latent", "wet: could not wet the ground", t) }
    }

    fun openCached(context: Context, source: Uri, isRaw: Boolean, maxEdge: Int, recipe: Recipe, iso: Int,
                   softenMask: ExposureMap? = null, pairFirst: Uri? = null, framing: Framing = Framing(),
                   fogMask: ExposureMap? = null, fogLook: FogLook = FogLook(),
                   raysMask: ExposureMap? = null, raysLook: RaysLook = RaysLook(),
                   extraLights: List<RaysLook> = emptyList(),
                   depthSplit: ExposureMap? = null,
                   wetMask: ExposureMap? = null, wetLook: WetLook = WetLook(), rainMask: ExposureMap? = null,
                   log: (String) -> Unit = {}): Source {
        // The pristine decode is cached on its own, so changing a pre-engine setting costs a
        // copy rather than a fresh decode of the file (which was over a second every time).
        // A double exposure is cached as its combined light, under a key naming both frames.
        val pair = pairFirst != null && isRaw
        val key = if (pair) "$pairFirst+$source@$maxEdge" else "$source@$maxEdge"
        val pristine = Cache.get(key) ?: run {
            val decoded = if (pair) openPair(context, pairFirst!!, source, maxEdge, log)
                else if (isRaw) openRaw(context, source, maxEdge, log) else openImage(context, source, maxEdge)
            Cache.put(key, decoded)
            decoded
        }
        // One working buffer per photo and size, reused: no allocation churn while a slider
        // moves, and nothing is freed while a render might still be reading it.
        // The framing is applied as the working copy is filled, so the untouched decode stays
        // cached as it is and a turned or straightened picture costs no extra memory.
        val workKey = "$key|work"
        val (fw, fh) = framing.outputSize(pristine.width, pristine.height)
        val working = Cache.get(workKey)?.takeIf { it.width == fw && it.height == fh }
            ?: pristine.blankSized(fw, fh).also { Cache.put(workKey, it) }
        // Prepared for exactly this already? Then it is ready: the print exposure, the filters
        // and the preview size are used only later, by the engine, so test strips and the
        // ring-around — which change nothing else — no longer clean and diffuse every print again.
        val isoUsed = if (pair) maxOf(iso, isoOf(context, pairFirst!!)) else iso
        // a light limited to the foreground or background needs the photo's split (the darkroom's own, or saved)
        val split = splitFor(context, source, listOf(raysLook) + extraLights, depthSplit)
        // glowing mist (62b) lights the fog from the lights, so it needs the depth even when no light asks for it
        val glowing = fogMask != null && fogLook.lit > 0f
        val wet = Wet.wanted(wetMask)
        val prep = listOf(
            framing.key(), isoUsed,
            // only the settings the preparation below reads — grain, halation, saturation, the film and
            // the rest act later, in the film stage, and once forced every stage here to run again
            preparedKey(recipe),
            softenMask?.let { "${it.width}x${it.height}:${it.stops.contentHashCode()}" } ?: "-",
            fogMask?.let { "${it.width}x${it.height}:${it.stops.contentHashCode()}:${fogLook.key()}" } ?: "-",
            if (raysLook.placed) "${raysLook.key()}:${raysMask?.let { "${it.width}x${it.height}:${it.stops.contentHashCode()}" } ?: "all"}" else "-",
            ExtraLights.key(extraLights),
            split?.let { "${it.width}x${it.height}:${it.stops.contentHashCode()}" } ?: "-",
            // light on surfaces needs the depth model: once it is installed, prepare again
            if (wet || (listOf(raysLook) + extraLights).any { Sun.wanted(it) || Beams.wanted(it) || GodRays.wanted(it) || (glowing && GlowMist.wanted(it)) })
                "sun:${Depth.isReady(context)}" else "-",
            // wet ground (64a): where it is painted and how
            if (wet) "${wetMask!!.width}x${wetMask.height}:${wetMask.stops.contentHashCode()}:${wetLook.groundKey()}" else "-",
            // rain on the window (65a): how, and where it is wiped
            if (Rain.wanted(wetLook.window)) "${wetLook.window.key()}:${rainMask?.stops?.contentHashCode() ?: 0}" else "-",
        ).joinToString("|")
        if (working.preparedFor == prep) return working
        working.frameFrom(pristine, framing)
        // the depth comes from the framed picture as it was, before fog or light touch it
        val sunIn = sunInput(context, source, framing, working, listOf(raysLook) + extraLights, log, glowing, wet)
        // the sensor's noise is cleaned first, so fog and light never work with it
        nightCleanSource(context, working, recipe, log)
        denoiseSource(working, recipe, isoUsed, log)
        sensorNoiseSource(working, recipe, isoUsed, log)
        // what lies under the lights: while it is unchanged, the noise-free copy they land on is reused
        // (the photo — both frames of a pair — and its size, by the decode's own key, then everything applied before the lights)
        val underLights = listOf(key, framing.key(), isoUsed, recipe.chromaDenoise, recipe.lumaDenoise,
            recipe.nightClean, recipe.nightCleanStrength, recipe.nightCleanFollow,
            raysMask?.stops?.contentHashCode() ?: 0, split?.stops?.contentHashCode() ?: 0, working.width, working.height).joinToString("|")
        // the lights on what they meet first, then the air in front of them (60e): fog laid before the
        // light was lit by it as if it were a wall, in patches where the depth said shadow or sun
        sunOnSurfaces(working, listOf(raysLook) + extraLights, raysMask, sunIn, log, underLights, split)
        // wet ground (64a): a lamp's pool of light shows in the water; the air comes after, in front of it
        wetGround(working, wetMask, wetLook, sunIn, log)
        // god rays read the sky's gaps from the lit picture, before the fog veils them (60f)
        val godRays = godRaysFor(working, listOf(raysLook) + extraLights, sunIn, log)
        // the air — it is in front of the lens — then the lens filter
        fogMask?.let { Fog.apply(working, it, fogLook, log, listOf(raysLook) + extraLights, sunIn) }
        // the flat 2D glow only for "through gaps", or when there is no depth to work in 3D
        for (light in listOf(raysLook) + extraLights) if (sunIn == null || !Beams.wanted(light)) Rays.apply(working, light, raysMask, fogMask, fogLook.amount, log, split)
        beamsInAir(working, listOf(raysLook) + extraLights, raysMask, fogMask, sunIn, log, split) // and in the air, in front of it all
        godRaysOver(working, godRays, fogMask, fogLook.amount, log)
        // rain on the window (65a): the glass is the last thing in front of the lens
        windowRain(working, wetLook.window, rainMask, log)
        lensFilterSource(working, recipe, log)
        fastDiffusionSource(working, recipe, preview = true, softenMask = softenMask, log = log)
        fastPrintDiffusionSource(working, recipe, preview = true, log = log)
        working.preparedFor = prep
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
        val ev = brightenOf(context, dng)
        if (ev > 0f) { Log.i("Latent", "hdr stack: brightened by +${"%.2f".format(ev)} EV (the file's BaselineExposure)"); log("HDR stack +${"%.1f".format(ev)} EV") }
        val longest = maxOf(w, h)
        // the file's own size, for Night Clean (95a); a capped decode is a smaller copy of it
        val native = storedSize(context, dng, true)?.let { maxOf(it.first, it.second) } ?: 0
        if (maxEdge <= 0 || longest <= maxEdge) {
            return Source(LinearImage(decoded.data, w, h, colorSpace = decoded.colorSpace, onClose = { RawDecoder.freeOffHeap(it) }), w, h)
                .also { if (ev > 0f) brighten(it, ev); if (native > 0) it.nativeScale = minOf(1f, longest.toFloat() / native) }
        }
        // The cap was ignored: shrink it ourselves, then free the big native buffer.
        var step = 1
        while (longest / step > maxEdge) step++
        val outW = (w + step - 1) / step; val outH = (h + step - 1) / step
        Log.i("Latent", "decoder ignored the cap; downsampling 1/$step to ${outW}x$outH")
        log("downsampling to ${outW}×$outH")
        val src = decoded.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val small = pictureSource(outW, outH, decoded.colorSpace)
        val of = small.image.data.asFloatBuffer()
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
        if (ev > 0f) brighten(small, ev)
        if (native > 0) small.nativeScale = minOf(1f, maxOf(outW, outH).toFloat() / native)
        return small
    }

    /** Every value times 2^[ev], in place: linear light, so values may now pass 1.0 (the sky of an HDR stack). */
    private fun brighten(src: Source, ev: Float) {
        val k = Math.pow(2.0, ev.toDouble()).toFloat()
        val f = src.image.data.duplicate().apply { clear() }.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = src.width * src.height * 3
        for (i in 0 until n) f.put(i, f.get(i) * k)
    }

    /** Decode an already-processed image once, linearised for the engine. */
    fun openImage(context: Context, image: Uri, maxEdge: Int = 0): Source {
        val src = context.contentResolver.openInputStream(image)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: error("could not open $image")
        val scale = if (maxEdge > 0) minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height)) else 1f
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val w = bmp.width; val h = bmp.height
        val out = pictureSource(w, h, "ProPhoto RGB").also { it.nativeScale = scale }
        val f = out.image.data.asFloatBuffer()
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
        return out
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
    /**
     * The sensor's brightness noise, cleaned before the film with an edge-keeping filter: film
     * never saw it, and the film's own grain becomes the texture. Automatic from the ISO
     * (none up to ISO 200), or the recipe's amount.
     */
    fun sensorNoiseSource(source: Source, recipe: Recipe, iso: Int, log: (String) -> Unit = {}) {
        // Night Clean has already cleaned it: the automatic amount would only soften it twice (a set amount still applies)
        val amount = if (recipe.lumaDenoise >= 0f) recipe.lumaDenoise else if (recipe.nightClean) 0f else GuidedClean.strengthForIso(iso)
        if (amount <= 0.001f) return
        log("cleaning sensor noise")
        GuidedClean.applyInPlace(source.image.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer(), source.width, source.height, amount)
    }

    /**
     * 95a: Night Clean, the first thing done to the picture — before the colour and brightness noise
     * cleaning, which it replaces when they are automatic. Once per filled buffer, like them (it runs
     * only before [denoiseSource] marks the buffer). A failure is logged and the photo develops without it.
     */
    fun nightCleanSource(context: Context, source: Source, recipe: Recipe, log: (String) -> Unit = {}) {
        if (!recipe.nightClean || source.denoised) return
        runCatching { NightClean.apply(context, source, recipe.nightCleanStrength, recipe.nightCleanFollow, source.nativeScale, log) }
            .onFailure { t -> Log.e("Latent", "night clean failed; developing without it", t) }
    }

    fun denoiseSource(source: Source, recipe: Recipe, iso: Int, log: (String) -> Unit = {}) {
        if (source.denoised) return
        // as for brightness noise: after Night Clean, only a set amount
        val strength = if (recipe.chromaDenoise >= 0f) recipe.chromaDenoise else if (recipe.nightClean) 0f else ChromaDenoise.strengthForIso(iso)
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
        // the engine would quietly swap a family it does not know (fog) for Black Pro-Mist
        if (!((recipe.fastDiffusion || preview || !FilmDiffusion.engineHas(recipe.printDiffusionFamily)) && recipe.printDiffusion)) return
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
            recipe.filmFormatMm * source.filmScale * 1000f / longest,
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
        if (!((recipe.fastDiffusion || preview || softenMask != null || !FilmDiffusion.engineHas(recipe.diffusionFamily)) && recipe.diffusion)) return
        if (source.diffused) return
        log(if (softenMask != null) "diffusion filter, where painted" else "diffusion filter")
        val longest = maxOf(source.width, source.height)
        val pixelSizeUm = recipe.filmFormatMm * source.filmScale * 1000f / longest
        // Painted: how much of each place's own light scatters, as a multiple of the setting
        // (0 sharp, 1 the filter as set, 2 twice as much). A mask of exactly 1 everywhere is the
        // plain setting, so it takes the plain path and stays identical to it.
        val scatter: ((Int) -> Float)? = softenMask?.takeUnless { m -> m.stops.all { it == 1f } }?.let { m ->
            val w = source.width; val h = source.height
            { i: Int -> m.sample((i % w + 0.5f) / w, (i / w + 0.5f) / h) }
        }
        FilmDiffusion.apply(
            source.image.data, source.width, source.height,
            recipe.diffusionFamily, recipe.diffusionStrength, recipe.diffusionScale,
            recipe.diffusionCore, recipe.diffusionCoreSize,
            recipe.diffusionHalo, recipe.diffusionHaloSize,
            recipe.diffusionBloom, recipe.diffusionBloomSize,
            recipe.diffusionWarmth, pixelSizeUm,
            scatter = scatter,
        )
        source.diffused = true
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
               exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null, saturationMap: ExposureMap? = null,
               lith: LithLook = LithLook(), lithFrame: Region? = null, hdrGlow: Float = 0f,
               png: PngTarget? = null, filmEdge: Int = 0,
               log: (String) -> Unit = {}): Pair<ByteArray, Pair<Int, Int>> {
        val t = System.nanoTime()
        Log.i("Latent", "render start: source ${source.width}x${source.height}, preview=$preview, cap=${recipe.previewMaxSize}")
        Log.i("Latent", "recipe: " + sanitised(recipe).summary())
        var dims = 0 to 0
        val (params, ourSpace) = filmParams(source, recipe, preview, upscale, exposureMap, softenMask)
        if (!preview && upscale > 1.001f) {
            Log.i("Latent", "print size ${upscale}×: ${source.width}x${source.height} -> about " +
                "${(source.width * upscale).toInt()}x${(source.height * upscale).toInt()}")
        }
        // Full develops log their memory peak: it is what decides how large a print the phone can make.
        val watch = if (!preview) MemoryWatch(context) else null
        val jpeg = try {
            engineFor(context).use { engine ->
                // 78a: the exposure the film is given, for the glow map's "how bright was the scene really"
                // (the engine's own metering when it levels the shot, plus the EV set by hand)
                val glow = HdrGlow.wanted(hdrGlow, lith)
                val sceneEv = if (glow) recipe.exposureEv + runCatching { engine.meterExposureEv(source.image, params) }.getOrDefault(0.0) else 0.0
                val result = if (preview) engine.simulatePreview(source.image, params) else engine.simulate(source.image, params)
                result.use { r ->
                    // painted saturation (62a): on the finished print, before any bars
                    if (Saturation.wanted(saturationMap)) {
                        val ts = System.nanoTime()
                        Saturation.apply(r.data, r.width, r.height, r.colorSpace, saturationMap)
                        if (!preview) Log.i("Latent", "saturation painted in ${(System.nanoTime() - ts) / 1_000_000} ms")
                    }
                    // lith (63a): the finished print made again on lith paper, last of all.
                    // [lithFrame]: which part of the whole picture this is (a zoom, the quick centre pass)
                    if (lith.on) {
                        val tl = System.nanoTime()
                        Lith.apply(r.data, r.width, r.height, r.colorSpace, lith, lithFrame)
                        if (!preview) Log.i("Latent", "lith (${lith.paper}, pulled ${lith.snatch}) in ${(System.nanoTime() - tl) / 1_000_000} ms")
                    }
                    // Ultra HDR glow (78a): where the finished print may shine brighter than white, read from the
                    // scene the film saw (never on lith paper, so the lith border below never needs a place in it)
                    val glowMap = if (!glow) null else runCatching {
                        val tg = System.nanoTime()
                        HdrGlow.shape(source, sceneEv, r.data, r.width, r.height).also { m ->
                            Log.i("Latent", "hdr glow ${HdrGlow.label(hdrGlow)} stops: map ${m.width}x${m.height}, " +
                                "${(m.cover() * 100).toInt()}% of the picture glows, scene at ${"%.2f".format(sceneEv)} EV, in ${(System.nanoTime() - tg) / 1_000_000} ms")
                        }
                    }.getOrElse { t -> Log.e("Latent", "hdr glow: could not make the map", t); null }
                    // the film's edge (92c), film-still bars and the lith paper's border, around the full-size print only:
                    // previews stay the bare picture, so painting, zoom and masks all line up with what they draw on.
                    // The edge is the film itself, so the paper goes round it, and it takes the place of the bars.
                    val (ed, ew, eh) = if (!preview && filmEdge > 0) {
                        val te = System.nanoTime()
                        FilmEdge.apply(context, r.data, r.width, r.height, r.colorSpace, recipe.film, filmEdge).also { (_, a, b) ->
                            Log.i("Latent", "film edge (${recipe.film}, frame $filmEdge): ${r.width}x${r.height} -> ${a}x$b in ${(System.nanoTime() - te) / 1_000_000} ms")
                        }
                    } else Triple(r.data, r.width, r.height)
                    val (bd, bw, bh) = if (!preview) Lith.border(ed, ew, eh, r.colorSpace, lith) else Triple(ed, ew, eh)
                    val (data, w, h) = if (!preview && source.letterbox && filmEdge <= 0) letterbox(bd, bw, bh) else Triple(bd, bw, bh)
                    dims = w to h
                    try {
                    // 78b: a PNG print streams into its file (a 50 MP 16-bit print is too big to hold as bytes)
                    if (!preview && png != null) {
                        val tp = System.nanoTime()
                        writePng(data, w, h, r.colorSpace, ourSpace, png)
                        Log.i("Latent", "png ${png.bits}-bit ${w}x$h: ${png.file.length() / 1024} KB in ${(System.nanoTime() - tp) / 1_000_000} ms")
                        ByteArray(0)
                    } else toJpeg(data, w, h, r.colorSpace, ourSpace, glowMap?.let { if (w == r.width && h == r.height) it to hdrGlow else HdrGlow.framed(it, r.width, r.height, w, h) to hdrGlow })
                    } finally {
                        // 81b: the bars, the paper border and the film edge are copies; a big one is native and goes back now
                        if (data !== bd) freeNative(data)
                        if (bd !== ed) freeNative(bd)
                        if (ed !== r.data) freeNative(ed)
                    }
                }
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
     * The engine's settings for developing [source] with [recipe], and the output space Latent makes
     * itself afterwards ("" when the engine makes it). Shared by [render] and the living photo (66a).
     */
    internal fun filmParams(source: Source, recipe: Recipe, preview: Boolean, upscale: Float = 1f,
                            exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null): Pair<com.spectrafilm.engine.SpektraParams, String> {
        // GPU is preview-only: a full render always goes through the CPU engine.
        val base = sanitised(if (preview) recipe else recipe.copy(gpuPreview = false))
            // A straightened picture shows less of the film: tell the engine the film it sees is
            // that much narrower, so grain and halation keep their true size.
            .let { if (source.filmScale != 1f) it.copy(filmFormatMm = it.filmFormatMm * source.filmScale) else it }
            // Our own filter has already run on the pixels, so the engine's LENS filter stays
            // off — but the enlarger's is a different stage, later in the chain, and is left to
            // the engine. Switching both off was dropping half the glow.
            .let { if (it.diffusion && (it.fastDiffusion || preview || softenMask != null || !FilmDiffusion.engineHas(it.diffusionFamily))) it.copy(diffusion = false) else it }
            .let { if (it.printDiffusion && (it.fastDiffusion || preview || !FilmDiffusion.engineHas(it.printDiffusionFamily))) it.copy(printDiffusion = false) else it }
        // Our own spaces are built from the engine's sRGB output, so ask it for sRGB.
        val ourSpace = if (OutputSpace.isOurs(base.outputColorSpace)) base.outputColorSpace else ""
        val params = (if (ourSpace.isEmpty()) base else base.copy(outputColorSpace = OutputSpace.ENGINE_SRGB)).toParams()
            .let { if (!preview && upscale > 1.001f) it.copy(io = it.io.copy(upscaleFactor = upscale)) else it }
            // Dodge & burn: the engine multiplies the enlarger light by the map, place by place.
            .let { p -> if (exposureMap == null || exposureMap.isBlank) p else p.copy(enlarger = p.enlarger.copy(
                printExposureMap = exposureMap.multipliers(),
                printExposureMapWidth = exposureMap.width, printExposureMapHeight = exposureMap.height)) }
        return params to ourSpace
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
     * The picture set in a 16:9 frame of black — bars above and below a wide picture, beside a
     * tall one. The picture itself is untouched, centred.
     */
    private fun letterbox(data: ByteBuffer, w: Int, h: Int): Triple<ByteBuffer, Int, Int> {
        val landscape = w >= h
        val fw = if (landscape) w else maxOf(w, Math.round(h * 9f / 16f))
        val fh = if (landscape) maxOf(h, Math.round(w * 9f / 16f)) else h
        if (fw == w && fh == h) return Triple(data, w, h)
        val out = pictureBuffer(fw * fh * 12L, zero = true)   // zeroed: black
        val src = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val dst = out.asFloatBuffer()
        val x0 = (fw - w) / 2; val y0 = (fh - h) / 2
        val row = FloatArray(w * 3)
        for (y in 0 until h) {
            src.position(y * w * 3); src.get(row)
            dst.position(((y0 + y) * fw + x0) * 3); dst.put(row)
        }
        return Triple(out, fw, fh)
    }

    /**
     * The engine returns display-referred FLOAT RGB (three floats per pixel, 0..1) in its output
     * colour space — not bytes. Clamp, quantise to 8 bit, and tag the bitmap with that space so
     * the system colour-manages it and embeds the right profile on export.
     */
    private fun toJpeg(data: ByteBuffer, w: Int, h: Int, colorSpace: com.spectrafilm.engine.ColorSpace, ourSpace: String = "",
                       glow: Pair<HdrGlow.Map, Float>? = null): ByteArray {
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
        // 78a: with a glow map behind it, the same compress writes an Ultra HDR file (Android 14+)
        glow?.let { (m, stops) -> runCatching { HdrGlow.attach(bmp, m, stops) }.onFailure { t -> Log.e("Latent", "hdr glow: could not attach the map", t) } }
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Where and how a full develop writes a PNG print (78b): [bits] 8 or 16, the film [note] inside it. */
    class PngTarget(val file: File, val bits: Int, val note: String?)

    /**
     * The print as a PNG ([PngFile]), tagged with the colour chunks Android itself writes for this space: a
     * 1×1 bitmap tagged as the JPEG would be is encoded by Android and its chunks are copied.
     */
    private fun writePng(data: ByteBuffer, w: Int, h: Int, colorSpace: com.spectrafilm.engine.ColorSpace, ourSpace: String, png: PngTarget) {
        val colour = runCatching {
            val one = taggedBitmap(1, 1, colorSpace, ourSpace)
            val tiny = ByteArrayOutputStream().also { one.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            one.recycle()
            PngFile.colourChunks(tiny)
        }.getOrElse { emptyList() }.ifEmpty { listOf("sRGB" to byteArrayOf(0)) }
        java.io.BufferedOutputStream(java.io.FileOutputStream(png.file), 1 shl 16).use { out ->
            PngFile.write(out, data, w, h, png.bits, ourSpace, colour, png.note)
        }
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
    internal fun baseNameOf(context: Context, uri: Uri): String {
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

    /**
     * 81a: the longest edge a full develop decodes at. The darkroom's steps before the film work in
     * strips since 81b, so what limits a big photo now is the film engine: about 100 bytes a pixel of
     * native memory (81c on the 15 Ultra: +2580 MB for 4344x5792, with 3682 MB free). 5792 is about
     * 25 MP at 4:3, the largest size measured to fit. [fullEdgeFor] steps down from it when the phone
     * has less memory free at the time.
     */
    const val FULL_EDGE_CAP = 5792

    /** 81d: the sizes a full develop tries, largest first, when the phone is short of memory. */
    private val FULL_EDGES = intArrayOf(FULL_EDGE_CAP, 5120, 4608, 4096, 3072)

    /** 81d: native memory the film engine needs per pixel, with some room over the 81c measurement (~103). */
    private const val ENGINE_BYTES_PER_PIXEL = 115L

    /** 81d: memory left for the rest of the phone (and the framing, the 16:9 letterbox, the file) after a develop. */
    private const val RESERVE_BYTES = 600L shl 20

    /**
     * 81d: the longest edge to develop a [sw]x[sh] photo at, the largest of [FULL_EDGES] whose film
     * fits in what the phone has free now, [upscale] (print size) included; the smallest if none does.
     */
    private fun fullEdgeFor(context: Context, sw: Int, sh: Int, upscale: Float): Int {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi) ?: return FULL_EDGE_CAP
        val room = mi.availMem - RESERVE_BYTES
        val long = maxOf(sw, sh)
        for (edge in FULL_EDGES) {
            val k = minOf(1.0, edge.toDouble() / long)
            val pixels = sw * k * sh * k * upscale * upscale
            if ((pixels * ENGINE_BYTES_PER_PIXEL).toLong() <= room) {
                Log.i("Latent", "full develop size: ${sw}x$sh at edge $edge needs about ${(pixels * ENGINE_BYTES_PER_PIXEL).toLong() shr 20} MB, " +
                    "phone has ${mi.availMem shr 20} MB free")
                return edge
            }
        }
        Log.i("Latent", "full develop size: only ${mi.availMem shr 20} MB free, using the smallest edge ${FULL_EDGES.last()}")
        return FULL_EDGES.last()
    }

    /**
     * 81c: watches memory while a full develop runs and logs the highest it saw: the app's Java memory
     * (limit 512 MB) and native memory (the film engine and 81b's big buffers), with what the phone had free.
     */
    private class MemoryPeaks(private val context: Context) {
        @Volatile private var running = true
        @Volatile var javaPeak = 0L; @Volatile var nativePeak = 0L
        private fun sample() {
            val rt = Runtime.getRuntime()
            javaPeak = maxOf(javaPeak, rt.totalMemory() - rt.freeMemory())
            nativePeak = maxOf(nativePeak, android.os.Debug.getNativeHeapAllocatedSize())
        }
        private val thread = Thread({ while (running) { sample(); try { Thread.sleep(100) } catch (_: InterruptedException) { break } } }, "memory peaks")
            .apply { isDaemon = true; start() }
        fun stop(w: Int, h: Int) {
            running = false; thread.interrupt(); sample()
            val mi = android.app.ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mi)
            Log.i("Latent", "memory peaks for ${w}x$h: Java ${javaPeak shr 20} MB of ${Runtime.getRuntime().maxMemory() shr 20} MB, " +
                "native ${nativePeak shr 20} MB, phone free now ${mi.availMem shr 20} MB of ${mi.totalMem shr 20} MB")
        }
    }

    /** What the last full develop had to give up, for the message after it is saved ("" = nothing). */
    @Volatile var lastFullNote = ""

    /** A photo's own size without decoding it, or null when the file does not say. */
    private fun storedSize(context: Context, uri: Uri, isRaw: Boolean): Pair<Int, Int>? = runCatching {
        if (isRaw) context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it) }?.let { e ->
            val w = e.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = e.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) w to h else null
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }.getOrNull()

    // ---- 81b: big picture buffers live in native memory ----------------------------------------

    /**
     * From this size a picture buffer is kept out of the app's 512 MB of Java memory (64 MB: a picture
     * of about 2.3 MP). ByteBuffer.allocateDirect is NOT native on Android: it is a byte array on that
     * same 512 MB heap, so a 50 MP photo's three copies (decoded, framed, a step's working copy) alone
     * were most of it. Small ones (every preview) stay where they were, freed by the collector as before.
     */
    private const val NATIVE_FROM = 64L shl 20
    private val nativeOnes: MutableSet<ByteBuffer> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** A buffer in native memory, outside the app's 512 MB; zeroed when [zero]. Give it back with [freeNative]. */
    internal fun nativeBuffer(bytes: Long, zero: Boolean = false): ByteBuffer {
        val b = (com.spectrafilm.engine.SimResult.allocDirectBuffer(bytes) ?: throw OutOfMemoryError("no room in the phone for ${bytes shr 20} MB"))
            .order(ByteOrder.nativeOrder())
        if (zero) { val z = ByteArray(1 shl 16); while (b.hasRemaining()) b.put(z, 0, minOf(z.size, b.remaining())); b.clear() }
        synchronized(nativeOnes) { nativeOnes += b }
        return b
    }

    /** Frees a buffer from [nativeBuffer] (or [pictureBuffer]); anything else is left to the collector. */
    internal fun freeNative(b: ByteBuffer) {
        if (synchronized(nativeOnes) { nativeOnes.remove(b) }) com.spectrafilm.engine.SimResult.freeDirectBuffer(b)
    }

    /** A picture-sized buffer: native when big ([NATIVE_FROM]), else the app's own as before. Give it back with [freeNative]. */
    internal fun pictureBuffer(bytes: Long, zero: Boolean = false): ByteBuffer =
        if (bytes >= NATIVE_FROM) nativeBuffer(bytes, zero) else ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())

    /** An empty picture (linear RGB floats) that frees its buffer when closed. */
    internal fun pictureSource(w: Int, h: Int, colorSpace: String): Source =
        Source(LinearImage(pictureBuffer(w * h * 12L), w, h, colorSpace = colorSpace, onClose = { freeNative(it) }), w, h)

    /** Full-resolution develop with progress, logging and a new file each time. */
    fun developFull(context: Context, source: Uri, isRaw: Boolean, recipe: Recipe, maxEdge: Int = 0, upscale: Float = 1f,
                    exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null, pairFirst: Uri? = null,
                    framing: Framing = Framing(), fogMask: ExposureMap? = null, fogLook: FogLook = FogLook(),
                    raysMask: ExposureMap? = null, raysLook: RaysLook = RaysLook(),
                    extraLights: List<RaysLook> = emptyList(),
                    depthSplit: ExposureMap? = null, saturationMap: ExposureMap? = null, lith: LithLook = LithLook(),
                    wetMask: ExposureMap? = null, wetLook: WetLook = WetLook(), rainMask: ExposureMap? = null,
                    hdrGlow: Float = 0f, format: String = DarkroomPrefs.FILE_JPEG, log: (String) -> Unit = {}): Uri {
        log(if (maxEdge > 0) "decoding…" else "decoding at full size…")
        lastFullNote = ""
        val stored = storedSize(context, source, isRaw)
        val fullEdge = stored?.let { (sw, sh) -> fullEdgeFor(context, sw, sh, upscale) } ?: FULL_EDGE_CAP
        val cap = if (maxEdge > 0) minOf(maxEdge, fullEdge) else fullEdge
        val peaks = MemoryPeaks(context)
        var developedW = 0; var developedH = 0
        try {
        val pair = pairFirst != null && isRaw
        val opened = if (pair) openPair(context, pairFirst!!, source, cap, log)
            else if (isRaw) openRaw(context, source, cap, log) else openImage(context, source, cap)
        stored?.let { (sw, sh) ->
            if (maxOf(sw, sh) > cap && maxEdge <= 0) {
                val mp = "%.1f".format(opened.width * opened.height / 1e6)
                lastFullNote = "developed at ${opened.width}×${opened.height} ($mp MP) of ${sw}×$sh: " +
                    if (cap < FULL_EDGE_CAP) "the phone was short of memory, close other apps for a bigger size" else "the largest size that fits in memory"
                Log.i("Latent", "full develop: ${sw}x$sh is over the ${cap}px cap, decoded at ${opened.width}x${opened.height}")
                log("big photo: developing at ${opened.width}×${opened.height}")
            }
        }
        // Framed before anything else touches the light, so the film forms on the framed picture.
        val src = if (framing.isIdentity) opened else {
            log("framing…")
            val (fw, fh) = framing.outputSize(opened.width, opened.height)
            try { opened.blankSized(fw, fh).also { it.frameFrom(opened, framing) } } finally { opened.close() }
        }
        developedW = src.width; developedH = src.height
        return src.use { s ->
            // A pair carries the noise of both frames: clean it for the noisier of the two.
            val iso = if (pair) maxOf(isoOf(context, source), isoOf(context, pairFirst!!)) else isoOf(context, source)
            prepareStages(context, source, s, iso, recipe, framing, softenMask, fogMask, fogLook, raysMask, raysLook, extraLights,
                depthSplit, wetMask, wetLook, rainMask, log)
            log("developing ${s.width}×${s.height}…")
            // The fog and light travel inside the file, so the Film Builder can read them exactly
            // later — even if the file is renamed or the original's fog is changed afterwards.
            val atmo = Atmosphere(
                fog = fogLook,
                fogCover = fogMask?.stops?.average()?.toFloat()?.coerceAtLeast(0f) ?: 0f,
                rays = raysLook,
                originalStem = baseNameOf(context, source),
            )
            // Written on every full develop: even without fog or light, the texture it used —
            // grain, halation, the diffusion filter, glare — is worth knowing exactly later.
            val note = atmo.note(Texture.noteOf(recipe))
            val tag = if (pair) "DX" else null
            // 78b: the kind of file — a JPEG (with the Ultra HDR glow only when that is the choice) or a PNG
            val bits = when (format) { DarkroomPrefs.FILE_PNG16 -> 16; DarkroomPrefs.FILE_PNG8 -> 8; else -> 0 }
            if (bits == 0) {
                val (bytes, dims) = render(context, s, recipe, preview = false, upscale = upscale, exposureMap = exposureMap, softenMask = softenMask, saturationMap = saturationMap, lith = lith,
                    hdrGlow = if (format == DarkroomPrefs.FILE_ULTRA_HDR) hdrGlow else 0f, filmEdge = framing.edgeFrame, log = log)
                log("saving ${dims.first}×${dims.second}, ${bytes.size / 1024} KB")
                saveDeveloped(context, bytes, source, recipe.film, tag = tag, note = note)
            } else {
                val tmp = File(context.cacheDir, "develop-${System.nanoTime()}.png")
                try {
                    val (_, dims) = render(context, s, recipe, preview = false, upscale = upscale, exposureMap = exposureMap, softenMask = softenMask, saturationMap = saturationMap, lith = lith,
                        png = PngTarget(tmp, bits, note), filmEdge = framing.edgeFrame, log = log)
                    log("saving ${dims.first}×${dims.second}, PNG $bits-bit, ${tmp.length() / 1024} KB")
                    saveDevelopedFile(context, tmp, source, recipe.film, tag = tag)
                } finally { tmp.delete() }
            }
        }
        } finally { peaks.stop(developedW, developedH) }
    }

    /**
     * Everything a full develop does to the framed picture before the film, in order, in place:
     * noise, the lights on surfaces, wet ground, the fog, the light in the air, god rays, the rain
     * on the window, the lens filter and the diffusion filters. Shared by [developFull] and the
     * living photo (66a), which develops the same edit several times with one thing changed.
     * [fogScale] thickens the fog alone (not the lights' reading of it); [beforeRain] is shown the
     * picture just before the rain goes on the glass.
     */
    internal fun prepareStages(context: Context, source: Uri, s: Source, iso: Int, recipe: Recipe, framing: Framing,
                               softenMask: ExposureMap?, fogMask: ExposureMap?, fogLook: FogLook,
                               raysMask: ExposureMap?, raysLook: RaysLook, extraLights: List<RaysLook>,
                               depthSplit: ExposureMap?, wetMask: ExposureMap?, wetLook: WetLook, rainMask: ExposureMap?,
                               log: (String) -> Unit, fogScale: Float = 1f, beforeRain: ((Source) -> Unit)? = null) {
        val sunIn = sunInput(context, source, framing, s, listOf(raysLook) + extraLights, log, fogMask != null && fogLook.lit > 0f, Wet.wanted(wetMask))
        nightCleanSource(context, s, recipe, log)
        denoiseSource(s, recipe, iso, log)
        sensorNoiseSource(s, recipe, iso, log)
        val split = splitFor(context, source, listOf(raysLook) + extraLights, depthSplit)
        // the lights on surfaces, then the air in front of them (60e), as in the preview
        sunOnSurfaces(s, listOf(raysLook) + extraLights, raysMask, sunIn, log, null, split)
        wetGround(s, wetMask, wetLook, sunIn, log)
        val godRays = godRaysFor(s, listOf(raysLook) + extraLights, sunIn, log)
        fogMask?.let { m ->
            val fm = if (fogScale == 1f) m else ExposureMap(m.width, m.height, FloatArray(m.stops.size) { m.stops[it] * fogScale })
            Fog.apply(s, fm, fogLook, log, listOf(raysLook) + extraLights, sunIn)
        }
        for (light in listOf(raysLook) + extraLights) if (sunIn == null || !Beams.wanted(light)) Rays.apply(s, light, raysMask, fogMask, fogLook.amount, log, split)
        beamsInAir(s, listOf(raysLook) + extraLights, raysMask, fogMask, sunIn, log, split)
        godRaysOver(s, godRays, fogMask, fogLook.amount, log)
        beforeRain?.invoke(s)
        windowRain(s, wetLook.window, rainMask, log)
        lensFilterSource(s, recipe, log)
        fastDiffusionSource(s, recipe, preview = false, softenMask = softenMask, log = log)
        fastPrintDiffusionSource(s, recipe, preview = false, log = log)
    }

    /**
     * The scene's light at a spot of the framed picture (0..1 across and down): read from the
     * untouched decode — before fog, filters or film — and averaged over a small patch, so the
     * eyedropper takes the light that was really there, not the print's colour of it.
     */
    fun sampleScene(context: Context, source: Uri, isRaw: Boolean, maxEdge: Int, pairFirst: Uri?,
                    framing: Framing, u: Float, v: Float): FloatArray? {
        val pair = pairFirst != null && isRaw
        val key = if (pair) "$pairFirst+$source@$maxEdge" else "$source@$maxEdge"
        val pristine = Cache.get(key) ?: run {
            val decoded = if (pair) openPair(context, pairFirst!!, source, maxEdge)
                else if (isRaw) openRaw(context, source, maxEdge) else openImage(context, source, maxEdge)
            Cache.put(key, decoded); decoded
        }
        val (ow, oh) = framing.outputSize(pristine.width, pristine.height)
        val (xs, ys) = framing.toSource((u * ow).toDouble(), (v * oh).toDouble(), pristine.width, pristine.height)
        val cx = xs.toInt().coerceIn(0, pristine.width - 1); val cy = ys.toInt().coerceIn(0, pristine.height - 1)
        val f = pristine.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (dy in -3..3) for (dx in -3..3) {
            val x = cx + dx; val y = cy + dy
            if (x !in 0 until pristine.width || y !in 0 until pristine.height) continue
            val o = (y * pristine.width + x) * 3
            r += f.get(o); g += f.get(o + 1); b += f.get(o + 2); n++
        }
        return if (n == 0) null else floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    /**
     * The photo's air, for an atmosphere (60a): the framed picture's brightness averaged over
     * each cell of a [w] × [h] mask grid, read against the scene's brightest light as
     * [Fog.autoLight] measures it, then [Air.read] with the depth's [near] (one value per cell).
     */
    fun readAir(context: Context, source: Uri, isRaw: Boolean, maxEdge: Int, pairFirst: Uri?,
                framing: Framing, near: FloatArray, w: Int, h: Int): Air.Reading {
        val pair = pairFirst != null && isRaw
        val key = if (pair) "$pairFirst+$source@$maxEdge" else "$source@$maxEdge"
        val pristine = Cache.get(key) ?: run {
            val decoded = if (pair) openPair(context, pairFirst!!, source, maxEdge)
                else if (isRaw) openRaw(context, source, maxEdge) else openImage(context, source, maxEdge)
            Cache.put(key, decoded); decoded
        }
        return readAirOf(pristine, framing, near, w, h)
    }

    /** [readAir] on a picture already decoded: [pristine] as framed by [framing] (60b: also the Film Builder's test shot). */
    fun readAirOf(pristine: Source, framing: Framing, near: FloatArray, w: Int, h: Int): Air.Reading {
        val (ow, oh) = framing.outputSize(pristine.width, pristine.height)
        val f = pristine.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        // 6 × 6 samples in every cell: enough for its average, and for the brightest 5% overall
        val per = 6
        val cells = FloatArray(w * h)
        val all = FloatArray(w * h * per * per); var n = 0
        for (j in 0 until h) for (i in 0 until w) {
            var sum = 0.0
            for (sj in 0 until per) for (si in 0 until per) {
                val u = (i + (si + 0.5f) / per) / w; val v = (j + (sj + 0.5f) / per) / h
                val (xs, ys) = framing.toSource((u * ow).toDouble(), (v * oh).toDouble(), pristine.width, pristine.height)
                val x = xs.toInt().coerceIn(0, pristine.width - 1); val y = ys.toInt().coerceIn(0, pristine.height - 1)
                val o = (y * pristine.width + x) * 3
                val l = Fog.luminance(f.get(o), f.get(o + 1), f.get(o + 2))
                sum += l; all[n++] = l
            }
            cells[j * w + i] = (sum / (per * per)).toFloat()
        }
        // the brightest 5%, averaged: as Fog.autoLight finds the plain fog's brightness
        val cut = Air.quantile(all, 0.95f)
        var s = 0.0; var m = 0
        for (l in all) if (l >= cut) { s += l; m++ }
        val auto = if (m == 0) cut else (s / m).toFloat()
        return Air.read(cells, auto, near, w, h)
    }

    /**
     * A region of the picture (0..1 across and down), for the zoomed view. It shows less of the
     * film than the whole picture, so its film scale shrinks to match: grain, halation and glow
     * keep their true size instead of growing as you zoom.
     */
    fun cropRegion(src: Source, r: Region): Source {
        val x0 = (r.u0 * src.width).toInt().coerceIn(0, src.width - 8)
        val y0 = (r.v0 * src.height).toInt().coerceIn(0, src.height - 8)
        val cw = ((r.u1 - r.u0) * src.width).toInt().coerceIn(8, src.width - x0)
        val ch = ((r.v1 - r.v0) * src.height).toInt().coerceIn(8, src.height - y0)
        val inBuf = src.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(cw * ch * 3 * 4).order(ByteOrder.nativeOrder())
        val of = out.asFloatBuffer()
        for (y in 0 until ch) {
            var i = ((y0 + y) * src.width + x0) * 3
            for (x in 0 until cw * 3) { of.put(inBuf.get(i)); i++ }
        }
        return Source(LinearImage(out, cw, ch, colorSpace = src.image.colorSpace), cw, ch).also {
            it.filmScale = src.filmScale * maxOf(cw, ch).toFloat() / maxOf(src.width, src.height)
            it.nativeScale = src.nativeScale
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
        return Source(LinearImage(out, cw, ch, colorSpace = src.image.colorSpace), cw, ch).also { it.filmScale = src.filmScale; it.nativeScale = src.nativeScale }
    }

    /** The most recent developed JPEG for a capture, if there is one. */
    fun developedFor(context: Context, source: Uri): Uri? {
        val stem = baseNameOf(context, source)
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND (${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)",
            arrayOf("DCIM/Latent%", "$stem!_%.jpg", "$stem!_%.png"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }

    /**
     * Named after its photo, so the roll pairs them: <photo>_<film>.jpg. A double exposure is named
     * after its SECOND frame with a DX tag — <second>_DX_<film>.jpg — so that frame shows the double.
     */
    fun saveDeveloped(context: Context, bytes: ByteArray, source: Uri, film: String, tag: String? = null, note: String? = null): Uri =
        save(context, note?.let { Atmosphere.withNote(bytes, it) } ?: bytes, baseNameOf(context, source) + (if (tag != null) "_$tag" else "") + "_" + film.substringAfterLast('_') + ".jpg")

    /** The same name for a PNG print (78b), streamed from [file] (its note is already inside). */
    fun saveDevelopedFile(context: Context, file: File, source: Uri, film: String, tag: String? = null): Uri =
        save(context, null, baseNameOf(context, source) + (if (tag != null) "_$tag" else "") + "_" + film.substringAfterLast('_') + ".png", from = file)

    /**
     * An 85B filter on the lens, as gains on the linear ProPhoto light: the ratio of 3200K to
     * 5500K blackbody light as ProPhoto sees it (computed from the CIE 1931 observer), green held
     * at 1 as if the meter read through the filter. +131 mireds — exactly the 85B, which converts
     * daylight for 3200K tungsten film such as Vision3 200T and 500T.
     */
    internal val GAINS_85B = floatArrayOf(1.2044f, 1f, 0.4923f)

    fun lensFilterSource(source: Source, recipe: Recipe, log: (String) -> Unit = {}) {
        if (!recipe.lens85b) return
        log("85B filter")
        val f = source.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until source.width * source.height) {
            val o = i * 3
            f.put(o, f.get(o) * GAINS_85B[0]); f.put(o + 2, f.get(o + 2) * GAINS_85B[2])
        }
    }

    /**
     * Two exposures on one frame: both RAWs decoded, and the second's light ADDED to the first's
     * before the film sees it — as on film, where the frame simply receives both lots of light.
     * So a black sky in one adds nothing and the other shows through; bright parts burn through
     * and blend; and the film's own response decides how the overlap rolls off. The result takes
     * the first frame's place in the shape of the picture.
     */
    fun openPair(context: Context, first: Uri, second: Uri, maxEdge: Int = 0, log: (String) -> Unit = {}): Source {
        log("decoding both exposures…")
        val a = openRaw(context, first, maxEdge, log)
        try {
            openRaw(context, second, maxEdge, log).use { b -> addLight(a, b) }
        } catch (t: Throwable) { a.close(); throw t }
        Log.i("Latent", "double exposure: ${a.width}x${a.height} with the second frame's light added")
        return a
    }

    /**
     * Adds [add]'s light into [dst]. Frames of the same size add pixel for pixel; otherwise (another
     * lens, or the in-sensor crop) the second is fitted to cover the first's frame, centred, and
     * sampled smoothly — the whole of what the viewfinder showed lands on the whole frame.
     */
    internal fun addLight(dst: Source, add: Source) {
        val dw = dst.width; val dh = dst.height; val aw = add.width; val ah = add.height
        val d = dst.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val a = add.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (dw == aw && dh == ah) {
            for (i in 0 until dw * dh * 3) d.put(i, d.get(i) + a.get(i))
            return
        }
        val scale = maxOf(dw.toFloat() / aw, dh.toFloat() / ah)   // cover the frame
        for (y in 0 until dh) {
            val sy = ((y + 0.5f - dh / 2f) / scale + ah / 2f - 0.5f).coerceIn(0f, (ah - 1).toFloat())
            val y0 = sy.toInt(); val y1 = minOf(y0 + 1, ah - 1); val fy = sy - y0
            for (x in 0 until dw) {
                val sx = ((x + 0.5f - dw / 2f) / scale + aw / 2f - 0.5f).coerceIn(0f, (aw - 1).toFloat())
                val x0 = sx.toInt(); val x1 = minOf(x0 + 1, aw - 1); val fx = sx - x0
                val o = (y * dw + x) * 3
                for (c in 0 until 3) {
                    val top = a.get((y0 * aw + x0) * 3 + c) * (1 - fx) + a.get((y0 * aw + x1) * 3 + c) * fx
                    val bot = a.get((y1 * aw + x0) * 3 + c) * (1 - fx) + a.get((y1 * aw + x1) * 3 + c) * fx
                    d.put(o + c, d.get(o + c) + top * (1 - fy) + bot * fy)
                }
            }
        }
    }

    private fun save(context: Context, bytes: ByteArray?, name: String, from: File? = null): Uri {
        val unique = uniqueName(context, name)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, unique)
            put(MediaStore.Images.Media.MIME_TYPE, if (name.endsWith(".png", true)) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out -> if (from != null) from.inputStream().use { it.copyTo(out, 1 shl 16) } else out.write(bytes!!) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.i("Latent", "developed file saved: $unique")
        return uri
    }
}
