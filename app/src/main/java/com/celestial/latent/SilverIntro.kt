package com.celestial.latent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 119a: the Silver opening, the look Celestial approved on 6 Nov 2026 (proofs in blackhole-silver/v5,
 * draft "1b-combined-intro-draft-no-pause"). The same ray tracer as the classic intro (light bent round
 * the hole step by step), with:
 *  - a silver palette, the steep diagonal view (roll -1.0) and the proof's camera path;
 *  - a thick fluffy disc: seven sheets at bumpy heights, read from maps baked once in Python and shipped
 *    as assets/intro/ (fib.webp threads, low.webp dust lanes, shape.webp height and timing, sky.webp clouds);
 *  - the disc turning (inner faster) and living: its threads break up and re-form, patch by patch;
 *  - the name painted on the sky far behind the hole, so the hole really bends it: it starts torn into a
 *    ring of letters and pulls out to rest above the hole;
 *  - a soft halo of the disc's own light and a wide bloom, done in linear light (half-float targets).
 * Numbers are the proof's (video5.py / render4.py); comments name them where they differ.
 * Settings → Look → Opening look switches back to the classic one.
 */
object IntroStyle {
    private const val PREFS = "latent_intro"
    /** Silver unless Classic was chosen. */
    fun silver(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("style", "silver") != "classic"
    fun set(ctx: Context, silver: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("style", if (silver) "silver" else "classic").apply()
    }
}

/** What the intro screen asks of either renderer (the classic one and Silver). */
internal interface IntroRenderer : GLSurfaceView.Renderer {
    var fade: Float
    fun touchDown()
    fun touchUp()
    fun drag(dx: Float, dy: Float)
    fun pinchBegin()
    fun pinch(startSpread: Float, spread: Float)
}

internal class SilverRenderer(
    context: Context,
    private val duration: Float,
    private val density: Float,
    private val reducedMotion: Boolean,
    private val onFail: () -> Unit,
    private val onFirstFrame: () -> Unit,
) : IntroRenderer {
    private val app = context.applicationContext
    // the maps are decoded off the GL thread as soon as the intro starts; the GL thread only uploads them
    @Volatile private var maps: Map<String, Bitmap>? = null
    @Volatile private var mapsError: Throwable? = null
    private val loader = Thread({
        try { maps = decodeMaps() } catch (t: Throwable) { mapsError = t }
    }, "silver-maps").apply { start() }

    private fun decodeMaps(): Map<String, Bitmap> {
        val t = System.nanoTime()
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val m = HashMap<String, Bitmap>()
        for (n in listOf("fib", "low", "shape", "sky")) {
            m[n] = app.assets.open("intro/$n.webp").use { BitmapFactory.decodeStream(it, null, opts) } ?: error("could not decode intro/$n.webp")
        }
        m["name"] = nameBitmap()
        Log.i("Latent", "silver intro: maps decoded in %d ms".format((System.nanoTime() - t) / 1_000_000))
        return m
    }

    private var first = true
    @Volatile override var fade = 1f
    private var scene = 0; private var post = 0; private var accum = 0; private var glow = 0; private var bloom = 0
    private val us = IntArray(16); private val up = IntArray(4); private val ua = IntArray(6); private val ug = IntArray(2); private val ub = IntArray(4)
    private val maptex = IntArray(5)                  // fib, low, shape, sky, name
    private val hist = IntArray(2); private val histFbo = IntArray(2); private var histNow = 0
    private val tex = IntArray(4); private val fbo = IntArray(4)        // the frame, its hot parts (bloom), its light (halo), the glow
    private var frameNo = 0; private var prevYaw = Float.NaN; private var prevPitch = 0f; private var prevDist = 0f
    private var rw = 0; private var rh = 0; private var scale = 0.7f; private var vw = 2; private var vh = 2
    private var t0 = 0L; private var last = 0L; private var el = 0f
    private var acc = 0f; private var frames = 0; private var failed = false
    private var logMs = 0f; private var logN = 0

    // the explorable camera, as the classic intro's (drag to orbit, pinch to fall closer, idle drift)
    private var exploring = false
    private var yaw = 0.6f; private var pitch = 0.04f; private var dist = 27f
    // the name rides with the glide and the idle drift, not with your fingers: drag and the hole moves past it
    private var nameYaw = 0.6f; private var namePitch = 0.04f
    @Volatile private var held = false
    @Volatile private var takeOver = false
    @Volatile private var vyaw = 0f; @Volatile private var vpitch = 0f
    @Volatile private var distTarget = 27f
    @Volatile private var lastTouch = 0L
    private var pinchDist = 27f
    private val lock = Any(); private var dYaw = 0f; private var dPitch = 0f

    override fun touchDown() { held = true; takeOver = true; lastTouch = System.nanoTime() }
    override fun touchUp() { held = false; lastTouch = System.nanoTime() }
    override fun drag(dx: Float, dy: Float) {
        vyaw = -dx * 0.0055f; vpitch = dy * 0.0055f
        synchronized(lock) { dYaw += vyaw; dPitch += vpitch }
        lastTouch = System.nanoTime()
    }
    override fun pinchBegin() { pinchDist = distTarget }
    override fun pinch(startSpread: Float, spread: Float) { distTarget = pinchDist * startSpread / maxOf(spread, 1f); lastTouch = System.nanoTime() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // linear light needs half-float targets (core in GLES 3.2; an extension before), and the maps need 4096 px
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            val ver = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
            if (!ver.contains("ES 3.2") && !ext.contains("GL_EXT_color_buffer_half_float") && !ext.contains("GL_EXT_color_buffer_float"))
                error("no half-float render targets ($ver)")
            val mx = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, mx, 0)
            if (mx[0] < 4096) error("max texture size ${mx[0]}")
            scene = program(FS_SILVER); post = program(FS_SILVER_POST); accum = program(FS_SILVER_ACC); glow = program(FS_SILVER_GLOW); bloom = program(FS_SILVER_BLOOM)
            listOf("uRes", "uTime", "uCam", "uRight", "uUp", "uFwd", "uFar", "uJitter", "uShift", "uDown",
                "uNF", "uNR", "uNU", "uNameV", "uNameAspect").forEachIndexed { i, n -> us[i] = GLES30.glGetUniformLocation(scene, n) }
            listOf("uHist", "uGlow", "uOut", "uTime").forEachIndexed { i, n -> up[i] = GLES30.glGetUniformLocation(post, n) }
            listOf("uHot", "uLit", "uRes", "uMaxLev").forEachIndexed { i, n -> ub[i] = GLES30.glGetUniformLocation(bloom, n) }
            listOf("uCur", "uHist", "uCurRes", "uOut", "uJitter", "uKeep").forEachIndexed { i, n -> ua[i] = GLES30.glGetUniformLocation(accum, n) }
            listOf("uScene", "uMode").forEachIndexed { i, n -> ug[i] = GLES30.glGetUniformLocation(glow, n) }
            GLES30.glUseProgram(scene)
            listOf("uFib", "uLow", "uShape", "uSky", "uName").forEachIndexed { i, n -> GLES30.glUniform1i(GLES30.glGetUniformLocation(scene, n), i) }
            GLES30.glGenTextures(2, hist, 0); GLES30.glGenFramebuffers(2, histFbo, 0)
            val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
            val buf = IntArray(1); GLES30.glGenBuffers(1, buf, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0])
            val tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 4, tri, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glGenTextures(4, tex, 0); GLES30.glGenFramebuffers(4, fbo, 0)
            uploadMaps()
            rw = 0; rh = 0
        } catch (t: Throwable) {
            Log.e("Latent", "silver intro could not start — showing the classic one", t)
            failed = true; onFail()
        }
    }

    /** Waits for the decoder (it started with the intro), then hands the maps to the GPU and frees them. If the
     *  GPU context is made again (back from the background), they are decoded again here. */
    private fun uploadMaps() {
        loader.join(4000)
        mapsError?.let { throw it }
        val m = maps ?: if (loader.isAlive) error("maps not ready in 4 s") else decodeMaps()
        GLES30.glGenTextures(5, maptex, 0)
        listOf("fib", "low", "shape", "sky", "name").forEachIndexed { i, n ->
            val b = m.getValue(n)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maptex[i])
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            // round the orbit and round the sky the maps wrap; across the radius, the sky's poles and the name they stop
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, if (n == "name") GLES30.GL_CLAMP_TO_EDGE else GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            b.recycle()
        }
        maps = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = maxOf(2, width); vh = maxOf(2, height)
        if (failed) return
        for (i in 0 until 2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, vw, vh, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            texParams(GLES30.GL_NEAREST, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, hist[i], 0)
            checkFbo()
            GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        prevYaw = Float.NaN
    }

    private fun checkFbo() {
        val s = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (s != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("Latent", "silver intro: half-float target not usable (status $s) — showing the classic one")
            failed = true; onFail()
        }
    }

    private fun texParams(min: Int, mag: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mag)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (failed) return
        val now = System.nanoTime()
        if (t0 == 0L) { t0 = now; last = now }
        val dtMs = minOf((now - last) / 1e6f, 120f); last = now; el += dtMs / 1000f
        // the classic intro's sharpness rules: smaller when slow, sharper when there is time to spare
        acc += dtMs; frames++
        if (!exploring && frames >= 12) {
            val avg = acc / frames
            if (avg > 30f && scale > 0.35f) scale = maxOf(0.35f, scale * 0.8f)
            acc = 0f; frames = 0
        } else if (exploring && frames >= 24) {
            val avg = acc / frames
            if (avg > 26f && scale > 0.4f) scale = maxOf(0.4f, scale * 0.85f)
            else if (avg < 14f && scale < 1.4f) scale = minOf(1.4f, scale * 1.08f)
            acc = 0f; frames = 0
        }
        // the frame-time line, every 60 frames, to size this on each phone
        logMs += dtMs; logN++
        if (logN >= 60) {
            Log.i("Latent", "silver intro: %.1f ms/frame, scene %dx%d (scale %.2f) of %dx%d".format(java.util.Locale.US, logMs / logN, rw, rh, scale, vw, vh))
            logMs = 0f; logN = 0
        }
        alloc()
        if (failed) return
        // the proof's path: 3.6 s glide (yaw 0.6 → 1.1, 27 → 18 radii, tilt 0.04 → 0.12), seen at roll -1.0
        val k = minOf(1f, el / duration); val e = k * k * (3 - 2 * k)
        val roll = -1.0f
        val dt = minOf(dtMs, 100f) / 1000f
        if (!exploring) {
            yaw = 0.6f + 0.5f * e; dist = 27f - 9f * e; pitch = 0.04f + 0.08f * e
            nameYaw = yaw; namePitch = pitch
            if (k >= 1f || takeOver) { exploring = true; distTarget = dist; acc = 0f; frames = 0; scale = 0.85f }
        }
        if (exploring) {
            synchronized(lock) { yaw += dYaw; pitch += dPitch; dYaw = 0f; dPitch = 0f }
            if (!held) {
                val f = dt * 60f
                yaw += vyaw * f; pitch += vpitch * f
                val decay = Math.pow(0.93, f.toDouble()).toFloat(); vyaw *= decay; vpitch *= decay
                if (!reducedMotion && now - lastTouch > 2_500_000_000L) { yaw += 0.035f * dt; nameYaw += 0.035f * dt }
            }
            pitch = pitch.coerceIn(-1.45f, 1.45f)
            distTarget = distTarget.coerceIn(5f, 55f)
            dist += (distTarget - dist) * minOf(1f, dt * 8f)
        }
        val b = basis(yaw, pitch, roll); val cam = b[0]; val r2 = b[1]; val u2 = b[2]; val f = b[3]
        val nb = basis(nameYaw, namePitch, roll)
        // the name: from torn round the hole to resting above it, already moving on the first frame (Celestial,
        // 6 Nov); the proof's timing is for a 3.6 s glide, so it is stretched to this glide's length
        val tn = el * 3.6f / duration
        val nameV = -0.05f + 0.55f * smooth(-1.2f, 4.2f, tn)

        // pass 1: the black hole in linear light, into the small frame
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]); GLES30.glViewport(0, 0, rw, rh); GLES30.glUseProgram(scene)
        GLES30.glDisable(GLES30.GL_BLEND)
        for (i in 0 until 5) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maptex[i]) }
        GLES30.glUniform2f(us[0], rw.toFloat(), rh.toFloat()); GLES30.glUniform1f(us[1], el + 20f)   // the proof's clock starts at 20 s
        GLES30.glUniform3f(us[2], cam[0], cam[1], cam[2]); GLES30.glUniform3f(us[3], r2[0], r2[1], r2[2])
        GLES30.glUniform3f(us[4], u2[0], u2[1], u2[2]); GLES30.glUniform3f(us[5], f[0], f[1], f[2])
        GLES30.glUniform1f(us[6], dist + 30f)
        frameNo++
        val jx = halton(frameNo % 16 + 1, 2) - 0.5f; val jy = halton(frameNo % 16 + 1, 3) - 0.5f
        GLES30.glUniform2f(us[7], jx, jy)
        GLES30.glUniform2f(us[8], -0.1f, 0f)                                    // the hole a little right of centre
        GLES30.glUniform1f(us[9], if (cam[1] >= 0f) 1f else 0f)
        GLES30.glUniform3f(us[10], nb[3][0], nb[3][1], nb[3][2]); GLES30.glUniform3f(us[11], nb[1][0], nb[1][1], nb[1][2])
        GLES30.glUniform3f(us[12], nb[2][0], nb[2][1], nb[2][2]); GLES30.glUniform1f(us[13], nameV)
        GLES30.glUniform1f(us[14], NAME_W.toFloat() / NAME_H)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // its hot parts (bloom) and its light (halo), each with mipmaps for the glow
        GLES30.glUseProgram(glow)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]); GLES30.glUniform1i(ug[0], 0)
        for (i in 1..2) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i]); GLES30.glUniform1f(ug[1], (i - 1).toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        for (i in 1..2) { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i]); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D) }
        // the glow itself, at the small frame's size
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[3]); GLES30.glUseProgram(bloom)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[1])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[2])
        GLES30.glUniform1i(ub[0], 0); GLES30.glUniform1i(ub[1], 1); GLES30.glUniform2f(ub[2], rw.toFloat(), rh.toFloat())
        GLES30.glUniform1f(ub[3], kotlin.math.floor(kotlin.math.log2(maxOf(rw, rh).toFloat())))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // build-up over frames (the classic intro's): keep more of the past the stiller the view
        val side = minOf(rw, rh).toFloat()
        val moved = if (prevYaw.isNaN()) Float.MAX_VALUE else
            (kotlin.math.abs(yaw - prevYaw) + kotlin.math.abs(pitch - prevPitch)) * side + kotlin.math.abs(dist - prevDist) / dist * side * 0.5f
        val keep = if (moved == Float.MAX_VALUE) 0f else 0.95f * kotlin.math.exp(-1.2f * moved)
        prevYaw = yaw; prevPitch = pitch; prevDist = dist
        val next = 1 - histNow
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo[next]); GLES30.glViewport(0, 0, vw, vh); GLES30.glUseProgram(accum)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist[histNow])
        GLES30.glUniform1i(ua[0], 0); GLES30.glUniform1i(ua[1], 1)
        GLES30.glUniform2f(ua[2], rw.toFloat(), rh.toFloat()); GLES30.glUniform2f(ua[3], vw.toFloat(), vh.toFloat())
        GLES30.glUniform2f(ua[4], jx, jy); GLES30.glUniform1f(ua[5], keep)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        histNow = next
        // pass 2: glow, the proof's finish (tone, muted colour, cream tint, vignette, grain), faded as it closes
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, vw, vh)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendColor(0f, 0f, 0f, fade)
        GLES30.glBlendFuncSeparate(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ZERO, GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ZERO)
        GLES30.glUseProgram(post)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist[histNow])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[3])
        GLES30.glUniform1i(up[0], 0); GLES30.glUniform1i(up[1], 1)
        GLES30.glUniform2f(up[2], vw.toFloat(), vh.toFloat()); GLES30.glUniform1f(up[3], el)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        if (first) { first = false; onFirstFrame() }
    }

    /** Camera position and its right/up/forward, rolled — the proof's scene3 and the classic driver alike. */
    private fun basis(yaw: Float, pitch: Float, roll: Float): Array<FloatArray> {
        val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
        val cam = floatArrayOf(dist * cp * kotlin.math.cos(yaw), dist * sp, dist * cp * kotlin.math.sin(yaw))
        val f = floatArrayOf(-cp * kotlin.math.cos(yaw), -sp, -cp * kotlin.math.sin(yaw))
        val rx = -f[2]; val rz = f[0]; val rl = kotlin.math.hypot(rx, rz).let { if (it == 0f) 1f else it }
        val r = floatArrayOf(rx / rl, 0f, rz / rl)
        val u = floatArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        val cr = kotlin.math.cos(roll); val sr = kotlin.math.sin(roll)
        return arrayOf(cam, FloatArray(3) { r[it] * cr + u[it] * sr }, FloatArray(3) { u[it] * cr - r[it] * sr }, f)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** The small frame, its two glow sources and the glow, half-float, at the adaptive size. */
    private fun alloc() {
        val w = maxOf(2, Math.round(vw / density * scale)); val h = maxOf(2, Math.round(vh / density * scale))
        if (w == rw && h == rh) return
        rw = w; rh = h
        for (i in 0 until 4) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, rw, rh, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            if (i == 0 || i == 3) texParams(GLES30.GL_LINEAR, GLES30.GL_LINEAR) else texParams(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
            checkFbo()
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun halton(i: Int, b: Int): Float { var f = 1f; var r = 0f; var n = i; while (n > 0) { f /= b; r += f * (n % b); n /= b }; return r }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
    private fun program(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, SILVER_VS)); GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos"); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        const val NAME_W = 2000; const val NAME_H = 330
        /** The intro's own two lines, as the proof drew them: CELESTIAL small over DARKROOM big, widely spaced, white on black. */
        fun nameBitmap(): Bitmap {
            val bmp = Bitmap.createBitmap(NAME_W, NAME_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(android.graphics.Color.BLACK)
            fun spaced(top: Float, text: String, size: Float, gap: Float, face: Typeface) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = size; typeface = face }
                val widths = text.map { p.measureText(it.toString()) }
                var x = NAME_W / 2f - (widths.sum() + gap * (text.length - 1)) / 2f
                val base = top - p.fontMetrics.ascent
                text.forEachIndexed { i, ch -> c.drawText(ch.toString(), x, base, p); x += widths[i] + gap }
            }
            spaced(20f, "CELESTIAL", 55f, 40f, Typeface.create("sans-serif", Typeface.NORMAL))
            spaced(110f, "DARKROOM", 150f, 62f, Typeface.create("sans-serif-light", Typeface.NORMAL))
            return bmp
        }
    }
}

private const val SILVER_VS = """#version 300 es
in vec2 aPos;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }"""

// The proof's renderer (render4.py + video5.py), rule for rule. Its smoothstep runs either way round (edge
// fades are written high-to-low), which GLSL's does not promise, so ss() is spelled out.
private const val FS_SILVER = """#version 300 es
precision highp float;
precision highp int;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uCam;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec3 uFwd;
uniform float uFar;
uniform vec2 uJitter;
uniform vec2 uShift;
uniform float uDown;
uniform vec3 uNF;
uniform vec3 uNR;
uniform vec3 uNU;
uniform float uNameV;
uniform float uNameAspect;
uniform sampler2D uFib;
uniform sampler2D uLow;
uniform sampler2D uShape;
uniform sampler2D uSky;
uniform sampler2D uName;
out vec4 outColor;

const float TAU = 6.2831853;
const float RIN = 2.6;
const float RMAX = 13.225;     // 11.5 x 1.15
const float RSOFT = 4.761;     // RMAX x 0.36
const float NR = 768.0;
const float NA = 4096.0;
const float THICK = 0.2;
const float ALPHA0 = 0.3;
const float PROFK = 2.2;
const float GAIN = 10.0;
const float SKYG = 2.5;
const float SPIN = 0.55;
const float LIVEP = 8.0;
const float SKYROT = 0.04;

float ss(float a, float b, float x){ float t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
float kep(float r){ float q = max(r, RIN) / RIN; return SPIN / (q * sqrt(q)); }

// where p falls on the baked disc maps: round the orbit (turning, inner faster) and across the radius
vec2 polar(vec3 p, out float r){
  r = length(p.xz);
  float a = atan(p.z, p.x);
  float u = fract(a / TAU - kep(r) * uTime / TAU);
  float ir = (r - RIN) / (RMAX - RIN) * (NR - 1.0);
  return vec2(u + 0.5 / NA, (ir + 0.5) / NR);
}
// the disc's local half-thickness: the sheets sit at -1..1 times this
float thick(vec3 p){
  float r;
  vec2 t = polar(p, r);
  float h = (textureLod(uShape, t, 0.0).r - 0.5) * 12.0;
  return THICK * (RIN + 0.35 * (r - RIN)) * (1.0 + 0.45 * h) * ss(RIN * 0.9, RIN * 2.2, r);
}
vec3 silverCol(float t){
  vec3 c = mix(vec3(0.30, 0.28, 0.27), vec3(0.78, 0.74, 0.68), ss(0.25, 0.65, t));
  c = mix(c, vec3(1.0, 0.97, 0.92), ss(0.65, 1.15, t));
  return mix(c, vec3(0.88, 0.93, 1.0), ss(1.3, 2.0, t));
}
vec4 disk(vec3 p, vec3 rd, float si){
  float r;
  vec2 t = polar(p, r);
  if(r < RIN || r > RMAX) return vec4(0.0);
  t += vec2(si * 311.0 / NA, si * 2.7 / NR);
  vec2 fb = textureLod(uFib, t, 0.0).rg;
  fb = fb * fb * 1.7;
  vec3 lw = textureLod(uLow, t, 0.0).rgb;
  float ph = (textureLod(uShape, t, 0.0).g - 0.5) * 8.0;
  float fn = (lw.b - 0.5) * 10.0;
  // living: two thread maps, each patch swapping on its own clock, segment by segment
  float w = 0.5 + 0.5 * sin(TAU * uTime / (2.0 * LIVEP) + 2.2 * ph);
  float m = w - 0.5 + 0.32 * fn;
  float k = ss(-0.12, 0.12, m);
  float fib = mix(fb.r, fb.g, k) * (1.0 - 0.55 * exp(-(m / 0.1) * (m / 0.1)));
  float dust = mix(lw.r, lw.g, k);
  float x = r / RIN;
  float prof = pow(x, -PROFK) * (1.0 - 0.9 / sqrt(x)) * 3.2;
  float edge = ss(RMAX * 0.95, RSOFT, r) * ss(RIN, RIN * 1.12, r);
  vec3 tang = normalize(vec3(-p.z, 0.0, p.x));
  float v = sqrt(0.5 / r);
  float gam = 1.0 / sqrt(1.0 - v * v);
  float dop = 1.0 / (gam * (1.0 - v * dot(tang, -rd)));
  float g = dop * sqrt(max(1.0 - 1.0 / r, 0.02));
  float temp = pow(x, -0.75) * g * 1.25;
  float beam = min(pow(g, 1.6), 4.0);
  float em = prof * edge * (0.015 + 2.4 * pow(fib, 1.3)) * beam * GAIN;
  float alpha = edge * clamp(ALPHA0 + 0.7 * dust * (0.4 + fib), 0.0, 0.92);
  return vec4(silverCol(temp) * em, alpha);
}
float hash(vec3 p){
  p = fract(p * 0.3183099 + 0.1);
  p *= 17.0;
  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
// the name on the sky: x, y in the resting view's screen units, so it reads as it does there
float nameLight(vec3 d){
  float z = dot(d, uNF);
  if(z <= 0.2) return 0.0;
  float x = dot(d, uNR) / z;            // + 0.1 (the view's shift) - 0.1 (the name's) cancel
  float y = dot(d, uNU) / z - uNameV;
  vec2 t = vec2(x / 0.64 + 0.5, 0.5 - y * uNameAspect / 0.64);
  if(t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) return 0.0;
  return textureLod(uName, t, 0.0).r;
}
vec3 sky(vec3 d0){
  float cr = cos(SKYROT);
  float sr = sin(SKYROT);
  vec3 d = vec3(cr * d0.x - sr * d0.z, d0.y, sr * d0.x + cr * d0.z);   // turned a hair, so no star sits dead behind the hole
  float lat = asin(clamp(d.y, -1.0, 1.0));
  float lon = atan(d.z, d.x);
  vec3 s = textureLod(uSky, vec2(lon / TAU, lat / 3.14159265 + 0.5), 0.0).rgb;
  vec3 c = pow(s, vec3(2.2)) * 4.0 * SKYG;
  for(int l = 0; l < 3; l++){
    float sc = 38.0 * pow(1.9, float(l));
    vec3 p = d * sc;
    vec3 id = floor(p);
    vec3 f = fract(p) - 0.5;
    float h = hash(id + float(l) * 13.7);
    vec3 off = vec3(hash(id + 1.7), hash(id + 5.3), hash(id + 9.1)) - 0.5;
    float b = ss(0.09, 0.0, length(f - off * 0.6)) * pow(h, 14.0) * 5.0;
    c += b * vec3(1.0, 0.96, 0.9);
  }
  c += 1.6 * nameLight(d) * vec3(1.0, 0.97, 0.92);
  return c;
}
void main(){
  vec2 uv = (gl_FragCoord.xy + uJitter - 0.5 * uRes) / min(uRes.x, uRes.y) + uShift;
  vec3 rd = normalize(uRight * uv.x + uUp * uv.y + uFwd);
  vec3 p = uCam;
  vec3 v = rd;
  vec3 hv = cross(p, v);
  float h2 = dot(hv, hv);
  vec3 col = vec3(0.0);
  float tr = 1.0;
  bool hit = false;
  bool cached = false;
  float q0 = 0.0;
  for(int i = 0; i < 220; i++){
    float r2 = dot(p, p);
    float r = sqrt(r2);
    if(r < 1.0){ hit = true; break; }
    if(r > uFar && dot(p, v) > 0.0) break;
    float dt = clamp(0.08 * (r - 0.7), 0.025, 3.0);
    vec3 a = -1.5 * h2 * p / (r2 * r2 * r);
    vec3 v2 = v + a * dt;
    vec3 p2 = p + v2 * dt;
    if(min(abs(p.y), abs(p2.y)) < 0.25 * length(p.xz) + 0.5){
      // seven sheets at heights -1..1 x the local thickness, weighted to the middle
      float qa = cached ? q0 : thick(p);
      float qb = thick(p2);
      vec3 nv = normalize(v2);
      for(int j = 0; j < 7; j++){
        int idx = uDown > 0.5 ? j : 6 - j;
        float hgt = -1.0 + float(idx) / 3.0;
        float wgt = exp(-(hgt / 0.85) * (hgt / 0.85));
        float s0 = p.y - hgt * qa;
        float s1 = p2.y - hgt * qb;
        if(s0 * s1 < 0.0){
          vec3 cp = mix(p, p2, s0 / (s0 - s1));
          vec4 d = disk(cp, nv, float(idx));
          col += tr * d.rgb * wgt;
          tr *= 1.0 - d.a * wgt;
        }
      }
      q0 = qb; cached = true;
    } else {
      cached = false;
    }
    p = p2;
    v = v2;
    if(tr < 0.01) break;
  }
  if(!hit) col += tr * sky(normalize(v));
  outColor = vec4(max(col, vec3(0.0)), 1.0);
}"""

// the classic intro's build-up pass, unchanged (it reads and writes linear light here)
private const val FS_SILVER_ACC = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uHist;
uniform vec2 uCurRes;
uniform vec2 uOut;
uniform vec2 uJitter;
uniform float uKeep;
out vec4 outColor;
void main(){
  vec2 uv = gl_FragCoord.xy / uOut;
  vec2 p = uv * uCurRes - 0.5 - uJitter;
  vec2 n = clamp(floor(p + 0.5), vec2(0.0), uCurRes - 1.0);
  vec3 s = texelFetch(uCur, ivec2(n), 0).rgb;
  vec2 d = ((n + 0.5 + uJitter) / uCurRes - uv) * uOut;
  float w = exp(-dot(d, d) / (2.0 * 0.55 * 0.55));
  vec3 mn = vec3(1e9);
  vec3 mx = vec3(-1e9);
  for(int j = -2; j <= 2; j++){
    for(int i = -2; i <= 2; i++){
      vec3 c = texelFetch(uCur, ivec2(clamp(n + vec2(float(i), float(j)), vec2(0.0), uCurRes - 1.0)), 0).rgb;
      mn = min(mn, c);
      mx = max(mx, c);
    }
  }
  vec4 h = texelFetch(uHist, ivec2(gl_FragCoord.xy), 0);
  vec3 hc = clamp(h.rgb, mn, mx);
  float hw = h.a * 16.0 * uKeep;
  vec3 bil = textureLod(uCur, uv, 0.0).rgb;
  float e = 0.005;
  vec3 c = (hc * hw + s * w + bil * e) / (hw + w + e);
  outColor = vec4(c, min(hw + w, 16.0) / 16.0);
}"""

// the glow's two sources (the proof's finish_glow): mode 0 the hot parts over 0.35 (bloom), mode 1 the disc's
// light over 0.25, capped at 3 (the halo, so faint clouds do not glow)
private const val FS_SILVER_GLOW = """#version 300 es
precision highp float;
uniform sampler2D uScene;
uniform float uMode;
out vec4 outColor;
void main(){
  vec3 c = texelFetch(uScene, ivec2(gl_FragCoord.xy), 0).rgb;
  vec3 g = uMode < 0.5 ? max(c - 0.35, 0.0) : min(max(c - 0.25, 0.0), vec3(3.0));
  outColor = vec4(g, 1.0);
}"""

// The glow (the proof's finish_glow): each width read from the sources' mipmaps with a smooth B-spline lookup
// (4 bilinear taps per level, two levels blended), so the coarse levels stay round instead of blocky. A level
// of size s, box-built then B-spline read, blurs about 0.65 s; the widths are the proof's, as fractions of the
// frame's width. Drawn at the small frame's size; the finish stretches it.
private const val FS_SILVER_BLOOM = """#version 300 es
precision highp float;
uniform sampler2D uHot;
uniform sampler2D uLit;
uniform vec2 uRes;
uniform float uMaxLev;
out vec4 outColor;
vec3 bsLevel(sampler2D t, vec2 uv, int l){
  vec2 sz = vec2(textureSize(t, l));
  vec2 p = uv * sz - 0.5;
  vec2 i = floor(p);
  vec2 f = p - i;
  vec2 f2 = f * f;
  vec2 f3 = f2 * f;
  vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
  vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
  vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
  vec2 w3 = f3 / 6.0;
  vec2 g0 = w0 + w1;
  vec2 g1 = w2 + w3;
  vec2 h0 = (i - 0.5 + w1 / g0) / sz;
  vec2 h1 = (i + 1.5 + w3 / g1) / sz;
  float lf = float(l);
  return (textureLod(t, vec2(h0.x, h0.y), lf).rgb * g0.x + textureLod(t, vec2(h1.x, h0.y), lf).rgb * g1.x) * g0.y
       + (textureLod(t, vec2(h0.x, h1.y), lf).rgb * g0.x + textureLod(t, vec2(h1.x, h1.y), lf).rgb * g1.x) * g1.y;
}
vec3 blurAt(sampler2D t, vec2 uv, float frac){
  float lev = clamp(log2(frac * uRes.x / 0.65), 0.0, uMaxLev - 1.0);
  int l0 = int(floor(lev));
  return mix(bsLevel(t, uv, l0), bsLevel(t, uv, l0 + 1), lev - floor(lev));
}
void main(){
  vec2 uv = gl_FragCoord.xy / uRes;
  vec3 bloom = blurAt(uHot, uv, 0.004) * 0.6 + blurAt(uHot, uv, 0.015) * 0.6 + blurAt(uHot, uv, 0.05) * 0.5 + blurAt(uHot, uv, 0.14) * 0.45;
  vec3 halo = blurAt(uLit, uv, 0.02) * 0.5 + blurAt(uLit, uv, 0.07) * 0.35 + blurAt(uLit, uv, 0.2) * 0.25;
  outColor = vec4(1.6 * (bloom + 0.6 * halo), 1.0);
}"""

// The proof's finish: the picture plus its glow, tone, colour muted to 35%, power 1.4, cream tint, vignette, grain.
private const val FS_SILVER_POST = """#version 300 es
precision highp float;
uniform sampler2D uHist;
uniform sampler2D uGlow;
uniform vec2 uOut;
uniform float uTime;
out vec4 outColor;
float h21(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main(){
  vec2 uv = gl_FragCoord.xy / uOut;
  vec3 g = max(texture(uHist, uv).rgb + texture(uGlow, uv).rgb, 0.0);
  vec3 t = pow(1.0 - exp(-g * 1.15), vec3(1.0 / 2.2));
  float l = (t.r + t.g + t.b) / 3.0;
  t = l + (t - l) * 0.35;
  t = clamp(pow(t, vec3(1.4)) * vec3(1.0, 0.985, 0.955), 0.0, 1.0);
  vec2 q = uv - 0.5;
  t *= 1.0 - 0.5 * dot(q, q);
  t += (h21(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) * 0.018;
  outColor = vec4(t, 1.0);
}"""
