package com.celestial.latent

import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import com.celestial.latent.ui.LatentColors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The opening: a black hole, traced live on the GPU — light bending around it pixel by pixel,
 * the near side of its disc brighter and whiter, as relativity has it — while the camera glides
 * in, a spark of light rises out of the dark and the name develops in underneath. A darkroom is
 * where light is held; a black hole is the darkroom of the universe.
 *
 * Made in the Celestial Light Puzzle chat (shaders and driver tested there as WebGL 2 on the
 * 15 Ultra); this is the native port of its driver. The three shaders are copied unchanged except one line (the sky band, 61e).
 *
 * Shown over the camera, which opens behind it, so the camera never waits. Tap skips it. 3.6 s on
 * the very first launch, 2.2 s after. Safeguards kept from the original: adaptive resolution,
 * an early end on slow phones, a hard time limit, a fallback (the old "name develops in"
 * animation) if the shaders can't run, and the GPU freed when done. Settings → Opening animation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlackHoleIntro(onDone: () -> Unit) {
    val context = LocalContext.current
    // 3.6 s the very first time, 2.2 s after
    val duration = remember {
        val p = context.getSharedPreferences("latent_intro", android.content.Context.MODE_PRIVATE)
        val seen = p.getBoolean("blackHoleSeen", false)
        p.edit().putBoolean("blackHoleSeen", true).apply()
        if (seen) 2.2f else 3.6f
    }
    var failed by remember { mutableStateOf(false) }
    // no graphics: the old opening, the name developing in
    if (failed) LaunchOverlay(onDone) else BlackHoleScene(duration, onDone, onFail = { failed = true })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
/**
 * It glides in, then it is yours to explore, as the live demo is: drag to orbit (with momentum),
 * pinch to fall closer or pull away, and after 2.5 s untouched it turns slowly by itself. Touching
 * during the glide takes over from wherever the camera is. A tap — no drag — closes it, fading
 * to the camera beneath. It never closes by itself (Settings → Opening animation turns it off).
 */
private fun BlackHoleScene(duration: Float, onDone: () -> Unit, onFail: () -> Unit) {
    val fade = remember { Animatable(1f) }
    var finishing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0f) }
    var explored by remember { mutableStateOf(false) }          // the hint goes at the first touch
    val main = remember { Handler(Looper.getMainLooper()) }
    val density = LocalDensity.current.density
    val context = LocalContext.current
    // Android's "remove animations" (the page's reduced motion): no idle drift
    val reduced = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var shownDist by remember { mutableStateOf(27f) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    // black until the first frame is drawn: the surface is see-through before that, and the camera is behind it
    var drawn by remember { mutableStateOf(false) }
    val renderer = remember { BlackHoleRenderer(duration, density, reduced, onFail = { main.post { onFail() } },
        onFirstFrame = { main.post { drawn = true } }) }
    LaunchedEffect(finishing) {
        if (!finishing) return@LaunchedEffect
        // fade out over 0.65 s, the camera showing through, then hand over
        fade.animateTo(0f, tween(650)) { renderer.fade = value }
        onDone()
    }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (!finishing) {
            val now = withFrameNanos { it }
            elapsed = (now - start) / 1e9f
            shownDist = renderer.distNow
        }
    }
    // the back gesture closes it too
    BackHandler { finishing = true }
    // stop drawing in the background, start again on return (the page's advice for an app)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) glView?.onPause() else if (e == Lifecycle.Event.ON_RESUME) glView?.onResume()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    // translucent, and above the viewfinder's own surface, so the camera shows through as it fades
                    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                    setZOrderMediaOverlay(true)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    keepScreenOn = true                                    // awake while it is open
                }.also { glView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // the spark of light rising out of the black hole, in the second half of the glide
        val k = (elapsed / duration).coerceIn(0f, 1f)
        if (k > 0.5f) {
            val q = (k - 0.5f) / 0.5f
            Box(
                Modifier.align(Alignment.Center)
                    .offset(y = -maxHeight * (q * q * 0.34f))
                    .graphicsLayer { val s = 0.4f + q * 0.9f; scaleX = s; scaleY = s; alpha = minOf(1f, q * 2f) * fade.value }
                    .size(70.dp).clip(CircleShape)
                    .background(Brush.radialGradient(
                        0f to Color(255, 246, 225, 255), 0.18f to Color(255, 216, 154, 191),
                        0.45f to Color(255, 216, 154, 51), 0.70f to Color(255, 216, 154, 0), 1f to Color(255, 216, 154, 0),
                    )),
            )
        }
        // the name, under the black hole, developing in as a print does
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.15f).alpha(fade.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dev = (elapsed / (duration * 0.8f)).coerceIn(0f, 1f)
            Text("CELESTIAL", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 8.sp,
                modifier = Modifier.padding(bottom = 10.dp).alpha(dev))
            Text("DARKROOM", color = developing(dev), fontSize = 30.sp, letterSpacing = 13.sp)
            Text("A FILM CAMERA", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 4.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp).alpha(dev))
        }
        // Once it is yours (arrived, or touched): the live page's text. Title and line at the top left;
        // the hint at the bottom left, fading over 1.2 s at the first touch; the distance at the bottom right.
        val ink = Color(0xFFEFE6D6); val dim = Color(0xFF9C917F); val warm = Color(0xFFFFB46B)
        val hud = (if (explored) 1f else ((elapsed - duration) / 0.6f).coerceIn(0f, 1f)) * fade.value
        val hintFade by androidx.compose.animation.core.animateFloatAsState(if (explored) 0f else 1f, tween(1200), label = "hint")
        if (hud > 0f) {
            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 20.dp, top = 18.dp).alpha(hud)) {
                Text("Black hole", color = ink, fontSize = 21.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                Text("Light paths traced live, pixel by pixel", color = dim, fontSize = 13.sp, fontFamily = FontFamily.Serif,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).alpha(hud),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Drag to look around. Pinch to fall closer. Tap to close.", color = ink, fontSize = 15.sp, lineHeight = 20.sp,
                    fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f).widthIn(max = 240.dp).alpha(hintFade))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 16.dp)) {
                    Text("%.1f".format(java.util.Locale.US, shownDist), color = warm, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                    Text("radii away", color = dim, fontSize = 13.sp, fontFamily = FontFamily.Serif)
                }
            }
        }
        if (!drawn) Box(Modifier.fillMaxSize().background(Color.Black))
        // Touch, caught on top (the GPU surface below may keep touches to itself): one finger orbits,
        // two pinch, a quick still tap closes. Moves are in density-independent pixels, as the demo's are.
        val slop = 10f * density
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val t0 = down.uptimeMillis
                var moved = 0f; var pinching = false; var everPinched = false; var startSpread = 0f; var lastTime = t0
                renderer.touchDown(); explored = true
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    ev.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        val spread = (pressed[0].position - pressed[1].position).getDistance()
                        if (!pinching) { pinching = true; everPinched = true; startSpread = spread; renderer.pinchBegin() } else renderer.pinch(startSpread, spread)
                    } else {
                        pinching = false                                   // a finger lifted: the pinch ends, the other drags on
                        val c = pressed[0]; val d = c.position - c.previousPosition
                        moved += d.getDistance()
                        renderer.drag(d.x / density, d.y / density)        // every move counts, from the first
                    }
                    ev.changes.forEach { it.consume() }
                }
                renderer.touchUp()
                // a tap closes it: one finger, barely moved, let go within 0.4 s — never a drag, a pinch or a hold
                if (!everPinched && moved <= slop && lastTime - t0 < 400L) finishing = true
            }
        })
    }
}

/**
 * The driver, ported from the web version line for line: two passes (the black hole drawn small
 * into a texture, then stretched to the screen with glow from its mipmaps, a vignette and grain),
 * the same camera path, and the same safeguards.
 */
private class BlackHoleRenderer(
    private val duration: Float,
    private val density: Float,
    /** Android's "remove animations": the idle drift is switched off, as the page does for reduced motion. */
    private val reducedMotion: Boolean,
    private val onFail: () -> Unit,
    private val onFirstFrame: () -> Unit,
) : GLSurfaceView.Renderer {
    private var first = true
    /** 1 → 0 as the intro fades out: the last pass is scaled by it, so the camera shows through. */
    @Volatile var fade = 1f
    private var scene = 0; private var post = 0; private var accum = 0
    private val us = IntArray(8); private val up = IntArray(4); private val ua = IntArray(6)
    // the finer picture, built up over time: two at the screen's size, read one and write the other
    private val hist = IntArray(2); private val histFbo = IntArray(2); private var histNow = 0
    private var frameNo = 0; private var prevYaw = Float.NaN; private var prevPitch = 0f; private var prevDist = 0f
    private val tex = IntArray(1); private val fbo = IntArray(1)
    private var rw = 0; private var rh = 0; private var scale = 0.7f; private var vw = 2; private var vh = 2
    private var t0 = 0L; private var last = 0L; private var el = 0f
    private var acc = 0f; private var frames = 0; private var failed = false

    // The explorable camera — the live demo's, number for number — taking over from the glide when
    // it ends, or at the first touch. Gestures arrive on the main thread; frames read them here.
    private var exploring = false
    private var yaw = 0.6f; private var pitch = 0.11f; private var dist = 27f
    @Volatile private var held = false
    @Volatile private var takeOver = false
    @Volatile private var vyaw = 0f; @Volatile private var vpitch = 0f
    @Volatile private var distTarget = 27f
    @Volatile private var lastTouch = 0L
    /** The current (smoothed) distance, for the read-out. */
    @Volatile var distNow = 27f
    private var pinchDist = 27f
    private val lock = Any(); private var dYaw = 0f; private var dPitch = 0f      // drags since the last frame

    fun touchDown() { held = true; takeOver = true; lastTouch = System.nanoTime() }
    fun touchUp() { held = false; lastTouch = System.nanoTime() }
    /** One finger moved (dx, dy) density-independent pixels: orbit, as the demo does (0.0055 rad each). */
    fun drag(dx: Float, dy: Float) {
        vyaw = -dx * 0.0055f; vpitch = dy * 0.0055f
        synchronized(lock) { dYaw += vyaw; dPitch += vpitch }
        lastTouch = System.nanoTime()
    }
    fun pinchBegin() { pinchDist = distTarget }
    /** Two fingers: spreading them falls closer, pinching pulls away. */
    fun pinch(startSpread: Float, spread: Float) { distTarget = pinchDist * startSpread / maxOf(spread, 1f); lastTouch = System.nanoTime() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            scene = program(FS_SCENE); post = program(FS_POST); accum = program(FS_ACC)
            listOf("uRes", "uTime", "uCam", "uRight", "uUp", "uFwd", "uFar", "uJitter").forEachIndexed { i, n -> us[i] = GLES30.glGetUniformLocation(scene, n) }
            listOf("uTex", "uOut", "uTime", "uHist").forEachIndexed { i, n -> up[i] = GLES30.glGetUniformLocation(post, n) }
            listOf("uCur", "uHist", "uCurRes", "uOut", "uJitter", "uKeep").forEachIndexed { i, n -> ua[i] = GLES30.glGetUniformLocation(accum, n) }
            GLES30.glGenTextures(2, hist, 0); GLES30.glGenFramebuffers(2, histFbo, 0)
            // one big triangle that covers the screen
            val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
            val buf = IntArray(1); GLES30.glGenBuffers(1, buf, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0])
            val tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 4, tri, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glGenTextures(1, tex, 0); GLES30.glGenFramebuffers(1, fbo, 0)
            rw = 0; rh = 0
        } catch (t: Throwable) {
            Log.e("Latent", "black hole intro: shaders could not start — showing the plain opening", t)
            failed = true; onFail()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = maxOf(2, width); vh = maxOf(2, height)
        if (failed) return
        // the finer pictures, at the screen's own size, empty (nothing gathered yet)
        for (i in 0 until 2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, vw, vh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, hist[i], 0)
            GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        prevYaw = Float.NaN
    }

    override fun onDrawFrame(gl: GL10?) {
        if (failed) return
        val now = System.nanoTime()
        if (t0 == 0L) { t0 = now; last = now }
        val dtMs = minOf((now - last) / 1e6f, 120f); last = now; el += dtMs / 1000f
        // sharpness: during the glide, the intro's rule (every 12 frames, smaller if over 30 ms);
        // while exploring, the demo's (every 24 frames: smaller if over 26 ms, sharper if under 14 ms)
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
        alloc()
        // camera: glide in from 27 to 18 radii while turning slightly; tilted 0.3 rad for a film look
        val k = minOf(1f, el / duration); val e = k * k * (3 - 2 * k)
        val roll = 0.3f
        if (!exploring) {
            yaw = 0.6f + 0.5f * e; dist = 27f - 9f * e; pitch = 0.11f
            // arrived, or touched: from here it is yours, starting exactly where the glide is
            if (k >= 1f || takeOver) { exploring = true; distTarget = dist; acc = 0f; frames = 0; scale = 0.85f }   // the page starts at 0.85
        }
        if (exploring) {
            val dt = minOf(dtMs, 100f) / 1000f                       // the page caps a frame at 100 ms
            synchronized(lock) { yaw += dYaw; pitch += dPitch; dYaw = 0f; dPitch = 0f }
            if (!held) {
                // let go: it glides on, slowing, like a spun globe. The page's numbers are per frame at
                // 60 a second; scaled by dt x 60 so it feels the same at 120 Hz (the handoff's advice).
                val f = dt * 60f
                yaw += vyaw * f; pitch += vpitch * f
                val decay = Math.pow(0.93, f.toDouble()).toFloat(); vyaw *= decay; vpitch *= decay
                // untouched for 2.5 s, it drifts slowly round by itself — unless animations are removed
                if (!reducedMotion && now - lastTouch > 2_500_000_000L) yaw += 0.035f * dt
            }
            pitch = pitch.coerceIn(-1.45f, 1.45f)
            distTarget = distTarget.coerceIn(5f, 55f)
            dist += (distTarget - dist) * minOf(1f, dt * 8f)
        }
        distNow = dist
        val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
        val cam = floatArrayOf(dist * cp * kotlin.math.cos(yaw), dist * sp, dist * cp * kotlin.math.sin(yaw))
        val f = floatArrayOf(-cam[0] / dist, -cam[1] / dist, -cam[2] / dist)
        val rx = -f[2]; val rz = f[0]; val rl = kotlin.math.hypot(rx, rz).let { if (it == 0f) 1f else it }
        val r = floatArrayOf(rx / rl, 0f, rz / rl)
        val u = floatArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        val cr = kotlin.math.cos(roll); val sr = kotlin.math.sin(roll)
        val r2 = FloatArray(3) { r[it] * cr + u[it] * sr }
        val u2 = FloatArray(3) { u[it] * cr - r[it] * sr }
        // pass 1: the black hole, into the small texture
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]); GLES30.glViewport(0, 0, rw, rh); GLES30.glUseProgram(scene)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUniform2f(us[0], rw.toFloat(), rh.toFloat()); GLES30.glUniform1f(us[1], el)
        GLES30.glUniform3f(us[2], cam[0], cam[1], cam[2]); GLES30.glUniform3f(us[3], r2[0], r2[1], r2[2])
        GLES30.glUniform3f(us[4], u2[0], u2[1], u2[2]); GLES30.glUniform3f(us[5], f[0], f[1], f[2])
        GLES30.glUniform1f(us[6], dist + 30f)
        // this frame's rays start from a slightly different spot in their pixels: a 16-step pattern (Halton 2, 3)
        frameNo++
        val jx = halton(frameNo % 16 + 1, 2) - 0.5f; val jy = halton(frameNo % 16 + 1, 3) - 0.5f
        GLES30.glUniform2f(us[7], jx, jy)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        // How much of the past to keep follows how far the view moved this frame, in small-frame pixels:
        // still, 95% (sharp in about a quarter second); drifting, ~75%; dragging, almost none.
        val side = minOf(rw, rh).toFloat()
        val moved = if (prevYaw.isNaN()) Float.MAX_VALUE else
            (kotlin.math.abs(yaw - prevYaw) + kotlin.math.abs(pitch - prevPitch)) * side + kotlin.math.abs(dist - prevDist) / dist * side * 0.5f
        val keep = if (moved == Float.MAX_VALUE) 0f else 0.95f * kotlin.math.exp(-1.2f * moved)
        prevYaw = yaw; prevPitch = pitch; prevDist = dist
        // the build-up pass: into the other finer picture, reading this one
        val next = 1 - histNow
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo[next]); GLES30.glViewport(0, 0, vw, vh); GLES30.glUseProgram(accum)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist[histNow])
        GLES30.glUniform1i(ua[0], 0); GLES30.glUniform1i(ua[1], 1)
        GLES30.glUniform2f(ua[2], rw.toFloat(), rh.toFloat()); GLES30.glUniform2f(ua[3], vw.toFloat(), vh.toFloat())
        GLES30.glUniform2f(ua[4], jx, jy); GLES30.glUniform1f(ua[5], keep)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        histNow = next
        // pass 2: stretch to the screen, add glow (from the texture's mipmaps), vignette and grain —
        // scaled by the fade (colour and alpha alike), so the camera shows through as it goes
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, vw, vh)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendColor(0f, 0f, 0f, fade)
        GLES30.glBlendFuncSeparate(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ZERO, GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ZERO)
        GLES30.glUseProgram(post)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])          // the glow: the small frame's mipmaps
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist[histNow])   // the picture: built up
        GLES30.glUniform1i(up[0], 0); GLES30.glUniform1i(up[3], 1); GLES30.glUniform2f(up[1], vw.toFloat(), vh.toFloat()); GLES30.glUniform1f(up[2], el)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        if (first) { first = false; onFirstFrame() }
    }

    /** Pass 1's size: the screen in density-independent pixels times the scale — the pixel count tested on the web. */
    private fun alloc() {
        val w = maxOf(2, Math.round(vw / density * scale)); val h = maxOf(2, Math.round(vh / density * scale))
        if (w == rw && h == rh) return
        rw = w; rh = h
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, rw, rh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** The Halton sequence: an evenly spread pattern of points within a pixel, never repeating a spot within 16. */
    private fun halton(i: Int, b: Int): Float { var f = 1f; var r = 0f; var n = i; while (n > 0) { f /= b; r += f * (n % b); n /= b }; return r }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
    private fun program(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, VS)); GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos"); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }
}

// The three shaders, copied from the black hole intro's handoff (GLSL ES 3.00); one change: the sky band squares with x*x (61e).
private const val VS = """#version 300 es
in vec2 aPos;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }"""

private const val FS_SCENE = """#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uCam;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec3 uFwd;
uniform float uFar;
uniform vec2 uJitter;
out vec4 outColor;

const float RIN = 2.6;
const float ROUT = 11.5;

float hash(vec3 p){
  p = fract(p * 0.3183099 + 0.1);
  p *= 17.0;
  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float noise(vec3 x){
  vec3 i = floor(x);
  vec3 f = fract(x);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
        mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
    mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
        mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y),
    f.z);
}
float fbm(vec3 p){
  float a = 0.5;
  float s = 0.0;
  for(int i = 0; i < 4; i++){
    s += a * noise(p);
    p = p * 2.07 + vec3(7.1, 3.3, 5.7);
    a *= 0.5;
  }
  return s;
}

vec3 stars(vec3 d){
  vec3 c = vec3(0.0);
  for(int l = 0; l < 3; l++){
    float sc = 38.0 * pow(1.9, float(l));
    vec3 p = d * sc;
    vec3 id = floor(p);
    vec3 f = fract(p) - 0.5;
    float h = hash(id + float(l) * 13.7);
    vec3 off = vec3(hash(id + 1.7), hash(id + 5.3), hash(id + 9.1)) - 0.5;
    float dd = length(f - off * 0.6);
    float b = smoothstep(0.11, 0.0, dd) * pow(h, 16.0) * 5.0;
    vec3 tint = mix(vec3(1.0, 0.74, 0.52), vec3(0.62, 0.78, 1.0), hash(id + 3.3));
    c += b * tint;
  }
  // squared as x*x: GLSL pow() is undefined for a negative base, and this is negative on half the sky
  // (Adreno returned junk there, cutting the haze off in a hard seam; Mali happened to square it)
  float bx = dot(d, normalize(vec3(0.35, 1.0, 0.2))) * 2.6;
  float band = exp(-bx * bx);
  float n = fbm(d * 2.4 + 11.0);
  float n2 = fbm(d * 7.0 + 3.0);
  c += band * (0.012 + 0.11 * n * n) * vec3(0.42, 0.5, 0.95);
  c += band * 0.06 * n2 * n2 * n * vec3(1.0, 0.6, 0.45);
  return c;
}

vec4 disk(vec3 p, vec3 rd){
  float r = length(p.xz);
  if(r < RIN || r > ROUT) return vec4(0.0);
  float om = 1.0 / pow(r, 1.5);
  float n = 0.0;
  for(int k = 0; k < 2; k++){
    float ph = fract(uTime / 14.0 + float(k) * 0.5);
    float w = 1.0 - abs(2.0 * ph - 1.0);
    float a = om * 6.0 * (ph * 14.0 + 7.0);
    float ca = cos(a);
    float sa = sin(a);
    vec2 q = vec2(ca * p.x + sa * p.z, -sa * p.x + ca * p.z);
    n += w * fbm(vec3(q * 1.25, float(k) * 5.0 + r * 0.35));
  }
  float x = r / RIN;
  float prof = pow(x, -2.1) * (1.0 - 0.9 / sqrt(x)) * 3.2;
  float edge = smoothstep(ROUT, ROUT * 0.55, r) * smoothstep(RIN, RIN * 1.12, r);
  float streak = 0.25 + 1.9 * n * n * n * 2.2;
  float dens = prof * edge * streak;

  vec3 tang = normalize(vec3(-p.z, 0.0, p.x));
  float v = sqrt(0.5 / r);
  float gam = 1.0 / sqrt(1.0 - v * v);
  float dop = 1.0 / (gam * (1.0 - v * dot(tang, -rd)));
  float g = dop * sqrt(max(1.0 - 1.0 / r, 0.02));
  float temp = pow(x, -0.75) * g * 1.25;

  vec3 col = mix(vec3(1.0, 0.2, 0.03), vec3(1.0, 0.56, 0.18), smoothstep(0.25, 0.65, temp));
  col = mix(col, vec3(1.0, 0.9, 0.76), smoothstep(0.65, 1.15, temp));
  col = mix(col, vec3(0.78, 0.88, 1.0), smoothstep(1.2, 1.9, temp));

  float beam = min(pow(g, 2.2), 6.0);
  float alpha = clamp(dens * 1.4, 0.0, 1.0) * edge;
  return vec4(col * dens * beam * 3.0, alpha);
}

void main(){
  vec2 uv = (gl_FragCoord.xy + uJitter - 0.5 * uRes) / min(uRes.x, uRes.y);
  vec3 rd = normalize(uRight * uv.x + uUp * uv.y + uFwd * 1.0);
  vec3 p = uCam;
  vec3 v = rd;
  vec3 hv = cross(p, v);
  float h2 = dot(hv, hv);

  vec3 col = vec3(0.0);
  float tr = 1.0;
  bool hit = false;

  for(int i = 0; i < 190; i++){
    float r2 = dot(p, p);
    float r = sqrt(r2);
    if(r < 1.0){ hit = true; break; }
    if(r > uFar && dot(p, v) > 0.0) break;
    float dt = clamp(0.09 * (r - 0.7), 0.03, 3.0);
    vec3 a = -1.5 * h2 * p / (r2 * r2 * r);
    vec3 v2 = v + a * dt;
    vec3 p2 = p + v2 * dt;
    if(p.y * p2.y < 0.0){
      float f = p.y / (p.y - p2.y);
      vec3 cp = mix(p, p2, f);
      vec4 d = disk(cp, normalize(v2));
      col += tr * d.rgb;
      tr *= 1.0 - d.a;
    }
    p = p2;
    v = v2;
    if(tr < 0.01) break;
  }
  if(!hit) col += tr * stars(normalize(v));

  col = 1.0 - exp(-col * 1.15);
  col = pow(col, vec3(1.0 / 2.2));
  outColor = vec4(col, 1.0);
}"""

// Built up over time (the anti-aliasing): each small frame's samples placed where their rays landed,
// in a finer picture at the screen's size. Proven in Python on the photon ring: after 32 still frames,
// 2.99% from full resolution (today's stretch: 4.82%; the ideal: 2.96%).
private const val FS_ACC = """#version 300 es
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

private const val FS_POST = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uHist;
uniform vec2 uOut;
uniform float uTime;
out vec4 outColor;

float h21(vec2 p){
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}
void main(){
  vec2 uv = gl_FragCoord.xy / uOut;
  vec3 c = texture(uHist, uv).rgb;
  vec3 b = textureLod(uTex, uv, 2.0).rgb * 0.45
         + textureLod(uTex, uv, 3.5).rgb * 0.4
         + textureLod(uTex, uv, 5.0).rgb * 0.35
         + textureLod(uTex, uv, 6.5).rgb * 0.3;
  c += b * b * 0.75;
  vec2 q = uv - 0.5;
  c *= 1.0 - 0.55 * dot(q, q);
  c += (h21(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) * 0.022;
  outColor = vec4(c, 1.0);
}"""
