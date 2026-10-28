package com.celestial.latent.develop

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a living photo moves (66a). Set in the LIVE screen each time a clip is made; not saved.
 * [flicker]: per light (its place in the photo's list of lights) one of [Live.FLICKERS]; a light
 * left out is steady, as most real lamps are.
 */
data class LiveLook(
    /** How many drops run down and land: 0 … 2 (1 = the proof's six runners on a 1600 × 1200 pane). */
    val drops: Float = 1f,
    /** How fast the drops slide: 0.5 … 2. */
    val speed: Float = 1f,
    /** How much the fog drifts and breathes: 0 … 1. */
    val drift: Float = 0.5f,
    /** How much the god rays shimmer: 0 … 1. */
    val shimmer: Float = 0.4f,
    val flicker: Map<Int, String> = emptyMap(),
) {
    fun flickerOf(light: Int): String = flicker[light] ?: "steady"
}

/** Everything the darkroom develops a photo from, held together for the living photo. */
class LiveEdit(
    val source: Uri, val isRaw: Boolean, val recipe: Recipe,
    val exposureMap: ExposureMap?, val softenMask: ExposureMap?, val pairFirst: Uri?, val framing: Framing,
    val fogMask: ExposureMap?, val fogLook: FogLook, val raysMask: ExposureMap?, val raysLook: RaysLook,
    val extraLights: List<RaysLook>, val depthSplit: ExposureMap?, val saturationMap: ExposureMap?, val lith: LithLook,
    val wetMask: ExposureMap?, val wetLook: WetLook, val rainMask: ExposureMap?,
) {
    val lights: List<RaysLook> get() = listOf(raysLook) + extraLights
}

/**
 * The living photo (66a): a 4 second seamless loop made from one darkroom edit, in which only what
 * the darkroom added moves — rain runs down the window, the fog drifts and breathes, lamps flicker
 * and god rays shimmer. The proof is `living-photo/scripts/live.py` (approved by Celestial).
 *
 * Developing every frame would take minutes per second of clip, so the still is developed ONCE and
 * each frame adds only its change, put through the film as a colour table (the engine's own, the
 * one the viewfinder uses): frame = film(still) + table(still + change) − table(still). Proven
 * against true develops of moving frames (`living-photo/speed-check`): 0.02 … 0.44 / 255 on average.
 * Grain, halation and the diffusion filters stay as the still has them.
 *
 * The change is built from a few extra runs of the darkroom's own stages ([Develop.prepareStages]),
 * once per clip settings:
 *  - flicker: the photo with that light taken away; its share is scaled up and down;
 *  - fog: the photo without fog and with the fog twice as thick, which gives the fog's colour and
 *    how much it lets through, place by place, so any thickness can be worked out exactly;
 *  - shimmer: the photo without god rays; their share is rippled;
 *  - rain: the picture just before the rain goes on the glass, kept, and new water drawn on it per frame.
 */
object Live {
    const val FPS = 30
    const val SECONDS = 4f
    const val FRAMES = 120                 // SECONDS × FPS
    private const val FADE = 24            // the rain's last 0.8 s faded into its first, for the loop
    const val FULL_EDGE = 1440
    const val QUICK_EDGE = 720
    val FLICKERS = listOf("steady", "gas", "candle", "tube")
    private const val LUT_SIZE = 33

    /** What would move in this edit. [lamps]: the lights (by place in the list) that can flicker. */
    class Moves(val rain: Boolean, val fog: Boolean, val lamps: List<Int>, val rays: Boolean) {
        val any: Boolean get() = rain || fog || lamps.isNotEmpty() || rays
    }

    fun moves(e: LiveEdit): Moves = Moves(
        rain = Rain.wanted(e.wetLook.window),
        fog = e.fogMask?.isBlank == false && e.fogLook.amount > 0f,
        lamps = e.lights.indices.filter { e.lights[it].placed && e.lights[it].type != "sun" },
        rays = e.lights.any { GodRays.wanted(it) },
    )

    class Cancelled : RuntimeException("cancelled")

    // ------------------------------------------------------------------ planes in native memory

    /** [n] floats outside the Java heap: a clip holds several pictures at once. */
    internal class Plane(val n: Int) : AutoCloseable {
        val buf: ByteBuffer = (SimResult.allocDirectBuffer(n * 4L) ?: throw OutOfMemoryError("living photo: no room for a ${n / 3} pixel picture"))
            .order(ByteOrder.nativeOrder())
        val f: FloatBuffer = buf.asFloatBuffer()
        private var closed = false
        fun copyFrom(src: ByteBuffer) {
            val s = src.duplicate().also { it.rewind() }.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val d = buf.asFloatBuffer(); d.put(s)
        }
        override fun close() { if (!closed) { closed = true; SimResult.freeDirectBuffer(buf) } }
    }

    /** A Source on native memory, freed when the Source is closed. */
    private fun nativeSource(w: Int, h: Int, colorSpace: String): Pair<Develop.Source, ByteBuffer> {
        val buf = (SimResult.allocDirectBuffer(w * h * 3 * 4L) ?: throw OutOfMemoryError("living photo: no room for the picture"))
            .order(ByteOrder.nativeOrder())
        return Develop.Source(LinearImage(buf, w, h, colorSpace = colorSpace, onClose = { SimResult.freeDirectBuffer(it) }), w, h) to buf
    }

    // ------------------------------------------------------------------ preparing an edit

    /** One edit worked out for clips: everything the frames are made from. Close it to free the memory. */
    class Prep internal constructor(
        val key: String, val w: Int, val h: Int, val letterbox: Boolean,
        internal val still: Plane, internal val film: Plane, internal val cs: ColorSpace,
        internal val lut: Lut, internal val gain: Float,
        internal val flick: Map<Int, Plane>,
        internal val fogD: Plane?, internal val fogLn: FloatArray?,
        internal val raysR: Plane?, internal val sunX: Float, internal val sunY: Float,
        internal val rainPre: Plane?, internal val rainStill: Plane?, internal val rainScene: Rain.Scene?,
        internal val rainCaps: List<Rain.Cap>, internal val rainGains: FloatArray,
    ) : AutoCloseable {
        override fun close() {
            listOfNotNull(still, film, fogD, raysR, rainPre, rainStill).forEach { it.close() }
            flick.values.forEach { it.close() }
        }
    }

    /** What a [Prep] depends on beyond the edit itself: the size and which lights flicker. */
    fun prepKey(look: LiveLook, maxEdge: Int): String =
        "$maxEdge|" + look.flicker.filterValues { it != "steady" }.keys.sorted().joinToString(",")

    /**
     * Develops the still at clip size and works out every moving part. Holds the engine for its
     * whole run (the caller takes the engine lane). [progress]: a line to show and 0 … 1.
     */
    fun prepare(context: Context, e: LiveEdit, look: LiveLook, maxEdge: Int,
                progress: (String, Float) -> Unit, cancelled: () -> Boolean): Prep {
        val moves = moves(e)
        val flickering = moves.lamps.filter { look.flickerOf(it) != "steady" }
        val runs = 1 + flickering.size + (if (moves.fog) 2 else 0) + (if (moves.rays) 1 else 0)
        var done = 0
        fun step(what: String) { if (cancelled()) throw Cancelled(); progress(what, 0.9f * done / runs); done++ }

        progress("decoding…", 0f)
        val pair = e.pairFirst != null && e.isRaw
        val opened = if (pair) Develop.openPair(context, e.pairFirst!!, e.source, maxEdge)
            else if (e.isRaw) Develop.openRaw(context, e.source, maxEdge) else Develop.openImage(context, e.source, maxEdge)
        val framed = if (e.framing.isIdentity) opened else {
            val (fw, fh) = e.framing.outputSize(opened.width, opened.height)
            try { opened.blankSized(fw, fh).also { it.frameFrom(opened, e.framing) } } finally { opened.close() }
        }
        val w = framed.width; val h = framed.height; val n = w * h * 3
        // the untouched framed picture, and a working copy each run starts from
        val (pristine, _) = framed.use { f ->
            nativeSource(w, h, f.image.colorSpace).also { (s, b) ->
                f.image.data.rewind(); b.rewind(); b.put(f.image.data); f.image.data.rewind(); b.rewind()
                s.filmScale = f.filmScale; s.nativeScale = f.nativeScale; s.letterbox = f.letterbox
            }
        }
        val opened2 = ArrayList<AutoCloseable>()
        try {
            val (work, _) = nativeSource(w, h, pristine.image.colorSpace)
            opened2 += work
            val iso = if (pair) max(Develop.isoOf(context, e.source), Develop.isoOf(context, e.pairFirst!!)) else Develop.isoOf(context, e.source)
            fun run(lights: List<RaysLook>, fogScale: Float = 1f, beforeRain: ((Develop.Source) -> Unit)? = null) {
                work.refillFrom(pristine)
                Develop.prepareStages(context, e.source, work, iso, e.recipe, e.framing, e.softenMask, e.fogMask, e.fogLook,
                    e.raysMask, lights.first(), lights.drop(1), e.depthSplit, e.wetMask, e.wetLook, e.rainMask, {}, fogScale, beforeRain)
            }
            fun keep(): Plane = Plane(n).also { opened2 += it; it.copyFrom(work.image.data) }

            // ---- the still, as a full develop makes it (and the picture just before the rain)
            step("the still…")
            val rainPre = if (moves.rain) Plane(n).also { opened2 += it } else null
            run(e.lights, beforeRain = rainPre?.let { p -> { s: Develop.Source -> p.copyFrom(s.image.data) } })
            val still = keep()

            // ---- each flickering light taken away: its share of the picture
            val flick = LinkedHashMap<Int, Plane>()
            for (j in flickering) {
                step("light ${j + 1}…")
                run(e.lights.mapIndexed { i, l -> if (i == j) l.copy(u = Float.NaN, v = Float.NaN) else l })
                flick[j] = keep().also { minus(still, it) }       // still − without
            }

            // ---- the fog: without it, and twice as thick
            var fogD: Plane? = null; var fogLn: FloatArray? = null
            if (moves.fog) {
                step("the fog…")
                run(e.lights, fogScale = 0f)
                val bare = keep()
                step("the fog, thicker…")
                run(e.lights, fogScale = 2f)
                val wf = work.image.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                val ln = FloatArray(w * h)
                for (p in 0 until w * h) {
                    val o = p * 3
                    var sb = 0f; var s1 = 0f; var s2 = 0f
                    for (c in 0..2) { val b = bare.f.get(o + c); sb += b; s1 += still.f.get(o + c); s2 += wf.get(o + c) }
                    val one = sb - s1
                    // τ: how much the fog lets through; thickness × 2 lets through τ² (B − out₂ = (B − out₁)(1 + τ))
                    val t = if (kotlin.math.abs(one) < 1e-6f) 1f else ((sb - s2) / one - 1f).coerceIn(1e-4f, 1f)
                    ln[p] = ln(t)
                    for (c in 0..2) bare.f.put(o + c, if (kotlin.math.abs(one) < 1e-6f) 0f else bare.f.get(o + c) - still.f.get(o + c))
                }
                fogD = bare; fogLn = ln                            // D = without − still
            }

            // ---- god rays: their share, from the photo with them all but off
            var raysR: Plane? = null; var sunX = 0f; var sunY = 0f
            if (moves.rays) {
                step("the god rays…")
                run(e.lights.map { if (GodRays.wanted(it)) it.copy(godRays = 1e-4f) else it })
                raysR = keep().also { minus(still, it) }
                val sun = e.lights.first { GodRays.wanted(it) }
                sunX = sun.u * w; sunY = sun.v * h
            }

            // ---- the rain: its view of the street, once, and the glass as the still has it
            var scene: Rain.Scene? = null; var caps: List<Rain.Cap> = emptyList(); var rainStill: Plane? = null
            if (rainPre != null) {
                val rl = e.wetLook.window
                scene = Rain.scene(rainPre.f, w, h, rl)
                caps = Rain.glass(w, h, scene.u, rl, e.rainMask)
                rainStill = Plane(n).also { opened2 += it; it.copyFrom(rainPre.buf); Rain.draw(it.f, scene, caps) }
            }

            // ---- the film: the still developed, and the film's colour table with its exposure
            if (cancelled()) throw Cancelled()
            progress("developing the still…", 0.9f)
            work.refillFrom(pristine)
            work.image.data.rewind(); still.buf.rewind(); work.image.data.put(still.buf); work.image.data.rewind(); still.buf.rewind()
            // the clip is always sRGB: video players expect it
            val recipe = e.recipe.copy(outputColorSpace = OutputSpace.ENGINE_SRGB)
            val (params, _) = Develop.filmParams(work, recipe, preview = false, exposureMap = e.exposureMap, softenMask = e.softenMask)
            val (bakeParams, _) = Develop.filmParams(work, recipe, preview = false)
            var cs: ColorSpace = ColorSpace.SRGB
            val film = Plane(n).also { opened2 += it }
            val lut: Lut; val gain: Float
            Develop.engineFor(context).use { engine ->
                engine.simulate(work.image, params).use { r ->
                    require(r.width == w && r.height == h) { "the engine returned ${r.width}×${r.height}, not ${w}×$h" }
                    cs = r.colorSpace
                    film.copyFrom(r.data)
                }
                progress("the film's colour table…", 0.97f)
                val text = engine.bakeCubeLut(bakeParams, LUT_SIZE, SpektraEngine.SHAPER_SRGB)
                lut = Lut(LookBaker.parseCube(text, LUT_SIZE) ?: error("the film's colour table could not be read"), LUT_SIZE)
                // the exposure the engine gave the still (1 when the film's levelling is off), held for every frame
                gain = engine.exposureGain(work.image, params)
            }
            work.close(); opened2.remove(work)
            Log.i("Latent", "live: prepared ${w}×$h, gain ${"%.3f".format(gain)}, lights ${flick.keys}, fog ${fogD != null}, rays ${raysR != null}, rain ${scene != null}")
            val gains = if (e.recipe.lens85b) Develop.GAINS_85B.copyOf() else floatArrayOf(1f, 1f, 1f)
            return Prep(prepKey(look, maxEdge), w, h, pristine.letterbox, still, film, cs, lut, gain, flick, fogD, fogLn,
                raysR, sunX, sunY, rainPre, rainStill, scene, caps, gains)
        } catch (t: Throwable) {
            opened2.forEach { runCatching { it.close() } }
            throw t
        } finally {
            pristine.close()
        }
    }

    /** [b] = [a] − [b], in place. */
    private fun minus(a: Plane, b: Plane) { for (i in 0 until a.n) b.f.put(i, a.f.get(i) - b.f.get(i)) }

    // ------------------------------------------------------------------ the film's colour table

    /** The engine's 33³ table on an sRGB-spaced lattice over linear 0 … 1, entries red slowest, blue fastest. */
    internal class Lut(val t: FloatArray, val n: Int) {
        private val enc = FloatArray(4097) { srgbEncode(it / 4096f) * (n - 1) }
        private fun coord(v: Float): Float {
            val x = v.coerceIn(0f, 1f) * 4096f
            val i = min(x.toInt(), 4095); val f = x - i
            return enc[i] + (enc[i + 1] - enc[i]) * f
        }
        /** The table at linear ([r], [g], [b]), trilinear, added to [o] × [sign]. */
        fun add(r: Float, g: Float, b: Float, sign: Float, o: FloatArray) {
            val cr = coord(r); val cg = coord(g); val cb = coord(b)
            val ir = min(cr.toInt(), n - 2); val ig = min(cg.toInt(), n - 2); val ib = min(cb.toInt(), n - 2)
            val fr = cr - ir; val fg = cg - ig; val fb = cb - ib
            for (dr in 0..1) for (dg in 0..1) {
                val wrg = (if (dr == 1) fr else 1f - fr) * (if (dg == 1) fg else 1f - fg) * sign
                val base = (((ir + dr) * n + ig + dg) * n + ib) * 3
                val w0 = wrg * (1f - fb); val w1 = wrg * fb
                o[0] += t[base] * w0 + t[base + 3] * w1
                o[1] += t[base + 1] * w0 + t[base + 4] * w1
                o[2] += t[base + 2] * w0 + t[base + 5] * w1
            }
        }
        private fun srgbEncode(v: Float): Float = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    // ------------------------------------------------------------------ what moves, over time

    /** A lamp's brightness k(t), repeating every [period] seconds (live.py's flicker). */
    private class Flicker(kind: String, period: Float, seed: Int) {
        private val T = period
        private val ks: IntArray; private val amp: Float; private val ph: FloatArray; private val wt: FloatArray
        private var sd = 1f
        private val dipT: FloatArray; private val dipW: FloatArray
        private val offT: FloatArray; private val offW: FloatArray
        init {
            val rng = Random(seed.toLong())
            when (kind) {
                "gas" -> { ks = IntArray(11) { it + 2 }; amp = 0.05f }       // a gas mantle breathes, ~5 %
                "candle" -> { ks = IntArray(33) { it + 8 }; amp = 0.12f }    // 2 … 10 flutters a second, ~12 %
                "tube" -> { ks = intArrayOf(1, 2); amp = 0.01f }             // a failing tube: drop-outs
                else -> { ks = IntArray(0); amp = 0f }
            }
            ph = FloatArray(ks.size) { rng.nextFloat() * 2f * PI.toFloat() }
            wt = FloatArray(ks.size) { 1f / sqrt(ks[it].toFloat()) }
            if (ks.isNotEmpty()) {
                var s = 0.0; var s2 = 0.0
                for (i in 0 until 2000) { val v = base(T * i / 2000f).toDouble(); s += v; s2 += v * v }
                sd = sqrt(max(s2 / 2000 - (s / 2000) * (s / 2000), 1e-12)).toFloat()
            }
            val dips = if (kind == "candle") 2 else 0
            dipT = FloatArray(dips) { rng.nextFloat() * T }; dipW = FloatArray(dips) { 0.08f + rng.nextFloat() * 0.12f }
            val offs = if (kind == "tube") 3 else 0
            offT = FloatArray(offs); offW = FloatArray(offs)
            for (i in 0 until offs) { offT[i] = rng.nextFloat() * T; offW[i] = 0.05f + rng.nextFloat() * 0.2f }
        }
        private fun base(t: Float): Float { var v = 0f; for (i in ks.indices) v += wt[i] * sin(2f * PI.toFloat() * ks[i] * t / T + ph[i]); return v }
        fun at(t: Float): Float {
            if (ks.isEmpty()) return 1f
            var v = 1f + amp * base(t) / sd
            for (i in dipT.indices) { val d = ((t - dipT[i] + T / 2f) % T + T) % T - T / 2f; v -= 0.3f * exp(-0.5f * (d / dipW[i]) * (d / dipW[i])) }
            for (i in offT.indices) if (((t - offT[i]) % T + T) % T < offW[i]) v *= 0.15f
            return max(v, 0f)
        }
    }

    /**
     * Soft fog clumps: low waves on a torus (whole cycles across the picture), so shifting by whole
     * periods per loop repeats exactly. On a grid 96 along the long side. Two layers, the second
     * finer and twice as fast.
     */
    private class Clumps(w: Int, h: Int) {
        val gw = if (w >= h) 96 else max(2, (96f * w / h).roundToInt())
        val gh = if (w >= h) max(2, (96f * h / w).roundToInt()) else 96
        private class Layer(seed: Int, fmax: Int, val drift: Int) {
            val kx = IntArray(24); val ky = IntArray(24); val ph = FloatArray(24); val a = FloatArray(24)
            val bt = IntArray(24); val bph = FloatArray(24); val norm: Float
            init {
                val rng = Random(seed.toLong())
                var s = 0f
                for (i in 0 until 24) {
                    kx[i] = rng.nextInt(2 * fmax + 1) - fmax; ky[i] = rng.nextInt(2 * fmax + 1) - fmax
                    if (kx[i] == 0 && ky[i] == 0) kx[i] = 1
                    ph[i] = rng.nextFloat() * 2f * PI.toFloat(); a[i] = 1f / hypot(kx[i].toFloat(), ky[i].toFloat())
                    bt[i] = 1 + rng.nextInt(2); bph[i] = rng.nextFloat() * 2f * PI.toFloat()
                    s += a[i] * a[i]
                }
                norm = sqrt(s / 2f)
            }
        }
        private val layers = listOf(Layer(5, 4, 1), Layer(6, 6, 2))
        /** The clumps at [frac] of the loop (0 … 1), each layer about −1 … 1, mixed evenly. */
        fun at(frac: Float): FloatArray {
            val out = FloatArray(gw * gh)
            val tau = 2f * PI.toFloat()
            for (L in layers) for (i in 0 until 24) {
                val shift = tau * L.kx[i] * L.drift * frac
                val breathe = 0.75f + 0.25f * cos(tau * L.bt[i] * frac + L.bph[i])
                val amp = 0.5f * L.a[i] * breathe / L.norm
                for (y in 0 until gh) {
                    val py = L.ky[i] * y.toFloat() / gh
                    for (x in 0 until gw) out[y * gw + x] += amp * cos(tau * (L.kx[i] * x.toFloat() / gw + py) - shift + L.ph[i])
                }
            }
            return out
        }
    }

    /** Ripples round the sun that travel outwards, on a quarter grid: dust drifting through the shafts. */
    private class Shimmer(w: Int, h: Int, sx: Float, sy: Float) {
        val gw = max(2, (w + 3) / 4); val gh = max(2, (h + 3) / 4)
        private val th = FloatArray(gw * gh); private val r = FloatArray(gw * gh)
        private val m = IntArray(14); private val nr = IntArray(14); private val c = IntArray(14); private val ph = FloatArray(14)
        init {
            val long = max(w, h).toFloat()
            for (y in 0 until gh) for (x in 0 until gw) {
                val px = (x + 0.5f) * w / gw; val py = (y + 0.5f) * h / gh
                th[y * gw + x] = atan2(py - sy, px - sx); r[y * gw + x] = hypot(py - sy, px - sx) / long
            }
            val rng = Random(21)
            for (i in 0 until 14) { m[i] = 3 + rng.nextInt(12); nr[i] = 1 + rng.nextInt(4); c[i] = 1 + rng.nextInt(2); ph[i] = rng.nextFloat() * 2f * PI.toFloat() }
        }
        fun at(frac: Float): FloatArray {
            val tau = 2f * PI.toFloat(); val k = 1f / sqrt(7f)
            return FloatArray(gw * gh) { g ->
                var v = 0f
                for (i in 0 until 14) v += cos(m[i] * th[g] + tau * (nr[i] * r[g] - c[i] * frac) + ph[i])
                v * k
            }
        }
    }

    /**
     * The water on the glass over time (live.py's Glass, on the app's own pane): the still's drops
     * stay; heavy drops run down in stick-slip jumps, swallow the drops in their way and leave a thin
     * trail with beads; new drops land. Sizes are the proof's, for a pane 1600 across.
     */
    private class Motion(val w: Int, val h: Int, val u: Float, rain: RainLook, live: LiveLook, val wipe: ExposureMap?,
                         val still: List<Rain.Cap>, val T: Float, val F: Float) {
        private class Runner(val r: Float, val y0: Float, val xs: FloatArray, val ev: List<FloatArray>,
                             val beads: List<FloatArray>, val gap: BooleanArray)
        private val runners = ArrayList<Runner>()
        private val landings = ArrayList<FloatArray>()       // time, x, y, radius
        private val step = u                                  // one proof pixel

        init {
            val rng = Random(rain.seed.toLong() * 104729L + 31L)
            val area = (w / u) * (h / u) / (1600f * 1200f)
            fun wiped(x: Float, y: Float): Float = wipe?.sample(x / w, y / h)?.coerceIn(-1f, 1f) ?: 0f
            val sz = rain.size
            repeat((6f * area * live.drops * sqrt(rain.amount)).roundToInt()) {
                val r = (12f + rng.nextFloat() * 8f) * u * sz
                var cx = 0f; var y0 = 0f
                for (tries in 0 until 20) {
                    cx = (0.05f + rng.nextFloat() * 0.9f) * w; y0 = rng.nextFloat() * 0.55f * h
                    if (wiped(cx, y0) > -0.5f) break
                }
                val n = ((h + 60f * u) / step).toInt() + 1
                val period = (30f + rng.nextFloat() * 60f) * u; val amp = (3f + rng.nextFloat() * 7f) * u
                val wob = FloatArray(n); var acc = 0f
                for (i in 0 until n) { acc += (rng.nextGaussian() * 0.6).toFloat() * u; wob[i] = acc + sin(i * step / period) * amp }
                val lean = (rng.nextGaussian() * 0.05).toFloat()
                val i0 = (y0 / step).toInt().coerceIn(0, n - 1)
                val xs = FloatArray(n) { cx + wob[it] - wob[i0] + (it * step - y0) * lean }
                // a timetable: wait, slide, wait, slide … (a drop moves only once it is heavy enough)
                val ev = ArrayList<FloatArray>()
                var t = rng.nextFloat() * 0.6f * T
                while (t < T + F) {
                    val d = 0.15f + rng.nextFloat() * 0.35f; val v = (90f + rng.nextFloat() * 170f) * u * live.speed
                    ev.add(floatArrayOf(t, d, v)); t += d + (0.3f + rng.nextFloat() * 0.9f) / live.speed
                }
                val beads = List(40) { floatArrayOf(rng.nextFloat(), (1.5f + rng.nextFloat() * 2.5f) * u, (rng.nextGaussian() * 1.5).toFloat() * u) }
                val gap = BooleanArray(n) { rng.nextFloat() < 0.004f }
                runners.add(Runner(r, y0, xs, ev, beads, gap))
            }
            repeat((30f * area * live.drops).roundToInt()) {
                val tl = 0.1f + rng.nextFloat() * (T + F - 0.2f)
                var x = 0f; var y = 0f
                for (tries in 0 until 50) {
                    x = rng.nextFloat() * w; y = rng.nextFloat() * h
                    if (rng.nextFloat() * 2f < 1f + wiped(x, y)) break
                }
                val r = exp(ln(4.5) + 0.6 * rng.nextGaussian()).toFloat().coerceIn(2f, 14f) * u * sz
                if (wiped(x, y) > -0.5f) landings.add(floatArrayOf(tl, x, y, r))
            }
        }

        val moving: Boolean get() = runners.isNotEmpty() || landings.isNotEmpty()

        private fun xAt(R: Runner, y: Float): Float {
            val p = (y / step).coerceIn(0f, R.xs.size - 1f)
            val i = min(p.toInt(), R.xs.size - 2).coerceAtLeast(0); val f = p - i
            return if (R.xs.size < 2) R.xs[0] else R.xs[i] * (1f - f) + R.xs[i + 1] * f
        }

        /** Where a runner is at [t], and whether it is sliding (smoothstep within each slide). */
        private fun yAt(R: Runner, t: Float): Pair<Float, Boolean> {
            var y = R.y0; var moving = false
            for (e in R.ev) {
                val s = e[0]; val d = e[1]; val v = e[2]
                if (t <= s) break
                val p = min(t - s, d) / d
                y += v * d * p * p * (3f - 2f * p)
                moving = moving || t - s < d
            }
            return y to moving
        }

        /** The water on the glass at time [t] seconds. */
        fun at(t: Float): List<Rain.Cap> {
            val pos = runners.map { yAt(it, t) }
            val out = ArrayList<Rain.Cap>(still.size + 400)
            // the still's drops, but those a runner swept up are gone
            for (c in still) {
                var eaten = false
                if (!c.trail) for ((k, R) in runners.withIndex()) {
                    val y = pos[k].first
                    if (y > R.y0 + 1f && c.cy > R.y0 - R.r && c.cy < y + R.r &&
                        kotlin.math.abs(c.cx - xAt(R, c.cy)) < R.r * 0.9f + c.rx) { eaten = true; break }
                }
                if (!eaten) out.add(c)
            }
            for ((k, R) in runners.withIndex()) {
                val (y, moving) = pos[k]; val r = R.r
                if (y > R.y0 + 1f) {
                    val tw = r * 0.17f; val len = y - R.y0
                    var ky = R.y0
                    val end = min(y - r * 0.8f, h + 10f)
                    while (ky < end) {
                        val gi = (ky / step).toInt().coerceIn(0, R.gap.size - 1)
                        val f = (ky - R.y0) / max(len, 1f)
                        if (ky >= 0f && !(R.gap[gi] && f < 0.85f) && (wipe == null || wipe.sample(xAt(R, ky) / w, ky / h) > -0.5f))
                            out.add(Rain.Cap(xAt(R, ky), ky, tw * (0.3f + 0.7f * f), tw * 1.5f, 0f, 0.35f, true))
                        ky += 2f * step
                    }
                    for (b in R.beads) {
                        val yb = R.y0 + b[0] * (len - r)
                        if (b[0] * len < len - r * 1.5f) out.add(Rain.Cap(xAt(R, yb) + b[2], yb, b[1], b[1], 0f, 1f, false))
                    }
                }
                val grow = 1f + 0.25f * min(1f, max(0f, y - R.y0) / (0.4f * h))
                val sag = 0.45f + if (moving) 0.35f else 0f          // stretched while it slides
                out.add(Rain.Cap(xAt(R, y), y, r * grow, r * 1.1f * grow, sag, 1f, false))
            }
            for (l in landings) if (t >= l[0]) {
                val s = min(1f, 0.6f + 0.4f * (t - l[0]) / 0.1f)       // lands and spreads in a tenth of a second
                out.add(Rain.Cap(l[1], l[2], l[3] * s, l[3] * s, 0f, 1f, false))
            }
            return out
        }
    }

    // ------------------------------------------------------------------ making the clip

    /**
     * Makes the clip into [out] (an MP4) from [prep], as [look] says. Does not need the engine.
     * [progress]: 0 … 1. Returns the clip's size.
     */
    fun make(prep: Prep, e: LiveEdit, look: LiveLook, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Pair<Int, Int> {
        val w = prep.w; val h = prep.h; val n = w * h * 3
        val T = SECONDS; val F = FADE.toFloat() / FPS
        val flick = prep.flick.entries.mapNotNull { (j, p) -> if (look.flickerOf(j) == "steady") null else Triple(Flicker(look.flickerOf(j), T, 11 + j), p, j) }
        val clumps = if (prep.fogD != null && look.drift > 0f) Clumps(w, h) else null
        val fogAmp = 0.7f * look.drift
        val shim = if (prep.raysR != null && look.shimmer > 0f) Shimmer(w, h, prep.sunX, prep.sunY) else null
        val shimAmp = 0.6f * look.shimmer
        val motion = if (prep.rainScene != null && look.drops > 0f) Motion(w, h, prep.rainScene.u, prep.rainScene.look, look, e.rainMask,
            prep.rainCaps, T, F).takeIf { it.moving } else null

        // the frame's border: the lith paper around it, then film-still bars, as the developed print has them
        val m = if (e.lith.on && e.lith.border) (0.045f * max(w, h)).roundToInt() else 0
        val paper = if (m > 0) paperColour(prep.cs, e.lith) else floatArrayOf(0f, 0f, 0f)
        val bw = w + 2 * m; val bh = h + 2 * m
        val (fw, fh) = if (!prep.letterbox) bw to bh else if (bw >= bh) bw to max(bh, (bw * 9f / 16f).roundToInt())
            else max(bw, (bh * 9f / 16f).roundToInt()) to bh
        // the encoder wants whole 16-pixel blocks: trim the edges evenly
        val ew = fw / 16 * 16; val eh = fh / 16 * 16
        val ox = (fw - bw) / 2 + m + (ew - fw) / 2; val oy = (fh - bh) / 2 + m + (eh - fh) / 2   // the picture's corner in the clip
        val bx0 = ox - m; val by0 = oy - m                                                      // the paper's corner

        val rainA = if (motion != null) Plane(n) else null
        val rainB = if (motion != null) Plane(n) else null
        val frame = Plane(n)
        try {
            Encoder(out, ew, eh).use { enc ->
                for (i in 0 until FRAMES) {
                    if (cancelled()) throw Cancelled()
                    val t = i.toFloat() / FPS
                    val frac = i.toFloat() / FRAMES
                    // the rain: this moment's water, faded with the moment one loop later near the start
                    val fade = if (motion != null && i < FADE) i.toFloat() / FADE else 1f
                    if (motion != null) {
                        rainA!!.copyFrom(prep.rainPre!!.buf); Rain.draw(rainA.f, prep.rainScene!!, motion.at(t))
                        if (fade < 1f) { rainB!!.copyFrom(prep.rainPre.buf); Rain.draw(rainB.f, prep.rainScene, motion.at(t + T)) }
                    }
                    val coef = FloatArray(flick.size) { flick[it].first.at(t) - 1f }
                    val cl = clumps?.at(frac)
                    val sh = shim?.at(frac)
                    assemble(prep, frame, coef, flick.map { it.second }, cl, clumps, fogAmp, sh, shim, shimAmp,
                        if (motion != null) rainA else null, if (fade < 1f) rainB else null, fade)
                    // the print's own finishing, as on the still: painted saturation, then lith
                    frame.buf.rewind()
                    if (Saturation.wanted(e.saturationMap)) Saturation.apply(frame.buf, w, h, prep.cs, e.saturationMap)
                    if (e.lith.on) Lith.apply(frame.buf, w, h, prep.cs, e.lith, null)
                    enc.frame(i * 1_000_000L / FPS) { img -> toYuv(img, frame.f, w, h, ew, eh, ox, oy, bx0, by0, bw, bh, paper) }
                    progress((i + 1f) / FRAMES)
                }
                enc.finish()
            }
        } finally {
            frame.close(); rainA?.close(); rainB?.close()
        }
        return ew to eh
    }

    /** The lith paper's colour as [Lith.border] lays it, read from a tiny bordered picture. */
    private fun paperColour(cs: ColorSpace, lith: LithLook): FloatArray {
        val buf = ByteBuffer.allocateDirect(64 * 64 * 3 * 4).order(ByteOrder.nativeOrder())
        val (b, _, _) = Lith.border(buf, 64, 64, cs, lith)
        val f = b.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        return floatArrayOf(f.get(0), f.get(1), f.get(2))
    }

    /** One frame into [out]: the developed still plus the frame's change put through the film's table. */
    private fun assemble(prep: Prep, out: Plane, coef: FloatArray, flick: List<Plane>,
                         cl: FloatArray?, clumps: Clumps?, fogAmp: Float,
                         sh: FloatArray?, shim: Shimmer?, shimAmp: Float,
                         rainA: Plane?, rainB: Plane?, fade: Float) {
        val w = prep.w; val h = prep.h
        val still = prep.still.f; val film = prep.film.f; val g = prep.gain
        val fogD = prep.fogD?.f; val fogLn = prep.fogLn; val raysR = prep.raysR?.f
        val r0 = prep.rainStill?.f; val ra = rainA?.f; val rb = rainB?.f; val rg = prep.rainGains
        val active = coef.indices.filter { kotlin.math.abs(coef[it]) > 1e-6f }
        parallel(h) { y0, y1 ->
            val o3 = FloatArray(3); val d = FloatArray(3)
            for (y in y0 until y1) {
                val cy = if (clumps != null) ((y + 0.5f) * clumps.gh / h - 0.5f) else 0f
                val sy = if (shim != null) ((y + 0.5f) * shim.gh / h - 0.5f) else 0f
                for (x in 0 until w) {
                    val p = y * w + x; val o = p * 3
                    d[0] = 0f; d[1] = 0f; d[2] = 0f
                    for (k in active) { val pl = flick[k].f; val a = coef[k]; for (c in 0..2) d[c] += a * pl.get(o + c) }
                    if (cl != null && fogD != null && fogLn != null) {
                        val mth = max(0f, 1f + fogAmp * bilinear(cl, clumps!!.gw, clumps.gh, (x + 0.5f) * clumps.gw / w - 0.5f, cy))
                        val l = fogLn[p]
                        val fac = if (l > -1e-3f) 1f - mth else { val tt = exp(l); (exp(mth * l) - tt) / (1f - tt) }
                        if (fac != 0f) for (c in 0..2) d[c] += fac * fogD.get(o + c)
                    }
                    if (sh != null && raysR != null) {
                        val s = max(0f, 1f + shimAmp * bilinear(sh, shim!!.gw, shim.gh, (x + 0.5f) * shim.gw / w - 0.5f, sy)) - 1f
                        for (c in 0..2) d[c] += s * raysR.get(o + c)
                    }
                    if (ra != null && r0 != null) for (c in 0..2) {
                        val now = if (rb != null) fade * ra.get(o + c) + (1f - fade) * rb.get(o + c) else ra.get(o + c)
                        d[c] += (now - r0.get(o + c)) * rg[c]
                    }
                    if (d[0] == 0f && d[1] == 0f && d[2] == 0f) {
                        for (c in 0..2) out.f.put(o + c, film.get(o + c))
                    } else {
                        val x0 = still.get(o); val x1 = still.get(o + 1); val x2 = still.get(o + 2)
                        o3[0] = film.get(o); o3[1] = film.get(o + 1); o3[2] = film.get(o + 2)
                        prep.lut.add(g * (x0 + d[0]), g * (x1 + d[1]), g * (x2 + d[2]), 1f, o3)
                        prep.lut.add(g * x0, g * x1, g * x2, -1f, o3)
                        for (c in 0..2) out.f.put(o + c, o3[c])
                    }
                }
            }
        }
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0; val fy = yc - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }

    /**
     * The frame into the encoder's picture as BT.709 limited-range YUV 4:2:0: the print at
     * ([ox], [oy]), the paper ([paper]) round it inside [bw] × [bh] from ([bx0], [by0]), black beyond.
     */
    private fun toYuv(img: Image, f: FloatBuffer, w: Int, h: Int, ew: Int, eh: Int, ox: Int, oy: Int,
                      bx0: Int, by0: Int, bw: Int, bh: Int, paper: FloatArray) {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride; val vRow = vP.rowStride; val vPix = vP.pixelStride
        fun rgb(X: Int, Y: Int, o: FloatArray) {
            val px = X - ox; val py = Y - oy
            if (px in 0 until w && py in 0 until h) {
                val i = (py * w + px) * 3
                o[0] = f.get(i).coerceIn(0f, 1f); o[1] = f.get(i + 1).coerceIn(0f, 1f); o[2] = f.get(i + 2).coerceIn(0f, 1f)
            } else if (X - bx0 in 0 until bw && Y - by0 in 0 until bh) { o[0] = paper[0]; o[1] = paper[1]; o[2] = paper[2] }
            else { o[0] = 0f; o[1] = 0f; o[2] = 0f }
        }
        parallel(eh / 2) { b0, b1 ->
            val c = FloatArray(3)
            for (by in b0 until b1) for (bx in 0 until ew / 2) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val X = bx * 2 + dx; val Y = by * 2 + dy
                    rgb(X, Y, c)
                    val yy = 16f + 219f * (0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2])
                    yB.put(Y * yRow + X * yPix, (yy + 0.5f).toInt().coerceIn(0, 255).toByte())
                    sr += c[0]; sg += c[1]; sb += c[2]
                }
                sr *= 0.25f; sg *= 0.25f; sb *= 0.25f
                val cb = 128f + 224f * (-0.1146f * sr - 0.3854f * sg + 0.5f * sb)
                val cr = 128f + 224f * (0.5f * sr - 0.4542f * sg - 0.0458f * sb)
                uB.put(by * uRow + bx * uPix, (cb + 0.5f).toInt().coerceIn(0, 255).toByte())
                vB.put(by * vRow + bx * vPix, (cr + 0.5f).toInt().coerceIn(0, 255).toByte())
            }
        }
    }

    /** Rows [0, rows) in bands, one per core; each band's work is its own, so the split never changes the result. */
    private fun parallel(rows: Int, body: (Int, Int) -> Unit) {
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(rows)
        if (bands <= 1) { body(0, rows); return }
        var failure: Throwable? = null
        val workers = (0 until bands).map { b ->
            Thread { runCatching { body(rows * b / bands, rows * (b + 1) / bands) }.onFailure { failure = it } }.apply { start() }
        }
        workers.forEach { it.join() }
        failure?.let { throw it }
    }

    // ------------------------------------------------------------------ the phone's H.264 encoder

    private class Encoder(file: File, val w: Int, val h: Int) : AutoCloseable {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private var muxing = false
        private val info = MediaCodec.BufferInfo()

        init {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * FPS / 4).toInt().coerceIn(2_000_000, 40_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            try {
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (t: Throwable) { codec.release(); muxer.release(); throw t }
        }

        fun frame(ptsUs: Long, fill: (Image) -> Unit) {
            var guard = 0
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val img = codec.getInputImage(i) ?: error("this phone's video encoder gave no picture to fill")
                    fill(img)
                    codec.queueInputBuffer(i, 0, w * h * 3 / 2, ptsUs, 0)
                    break
                }
                drain(false)
                if (++guard > 1000) error("the video encoder stopped taking frames")
            }
            drain(false)
        }

        fun finish() {
            var guard = 0
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                drain(false)
                if (++guard > 1000) error("the video encoder did not finish")
            }
            drain(true)
        }

        private fun drain(toEnd: Boolean) {
            var idle = 0
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!toEnd || ++idle > 500) return }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxing) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        override fun close() {
            runCatching { codec.stop() }; runCatching { codec.release() }
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // ------------------------------------------------------------------ saving

    /** Copies a finished clip into DCIM/Latent as "<photo>_<film>_live.mp4". */
    fun save(context: Context, clip: File, e: LiveEdit): Uri {
        val name = Develop.baseNameOf(context, e.source) + "_" + e.recipe.film.substringAfterLast('_') + "_live.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("could not make the video file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> clip.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw t }
        Log.i("Latent", "living photo saved: $name")
        return uri
    }
}
