package com.celestial.latent.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The viewfinder, drawn by us instead of by the system, so a film look can be applied to the
 * live image. Camera frames arrive as an external texture, are drawn through a shader, and
 * (later) looked up in a 3D table baked from the chosen film.
 *
 * This first version applies no look: with [lut] unset the shader is a straight copy, so the
 * preview must look exactly as it did before. That is the test that this path is correct.
 */
class FilmPreviewView(context: Context) : GLSurfaceView(context) {

    /** Called once the camera can start: gives the Surface to draw camera frames into. */
    var onSurfaceReady: ((Surface, SurfaceTexture) -> Unit)? = null

    /** Called if the shader could not be built, so the screen can fall back to the plain preview. */
    var onUnavailable: ((String) -> Unit)? = null

    private val renderer = FilmRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        // Draw continuously: cheap at preview size, and a dropped frame notification can never
        // leave the viewfinder frozen.
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Size the camera buffer; must match what the capture session was configured with. */
    fun setBufferSize(width: Int, height: Int) = renderer.setBufferSize(width, height)

    /** 33×33×33 RGB table, or null for a neutral pass-through. */
    fun setLut(lut: FloatArray?, size: Int = 33) {
        renderer.pendingLut = lut
        renderer.pendingLutSize = size
        requestRender()
    }

    /**
     * Linear gain applied before the table, matching the engine's own auto-exposure. Eased
     * towards the new value rather than jumped, so the viewfinder settles instead of stepping.
     */
    /**
     * A colour filter on the lens, seen in the viewfinder: per-channel gains on the camera image
     * before the film's table (1, 1, 1 = none). Used for the 85B in cinema mode.
     */
    fun setTint(r: Float, g: Float, b: Float) {
        renderer.tint = floatArrayOf(r, g, b)
        requestRender()
    }

    fun setExposureGain(gain: Float) {
        val current = renderer.exposureGain
        renderer.exposureGain = if (current <= 0f) gain else current + (gain - current) * 0.4f
        requestRender()
    }

    /**
     * Asks for a small copy of the next drawn frame, so the exposure gain can be metered from
     * the scene in front of the camera instead of a stand-in. The callback runs on the GL
     * thread; keep it short.
     */
    fun grabFrame(size: Int = 64, onFrame: (FloatArray, Int, Int) -> Unit) {
        renderer.grabSize = size
        renderer.onGrab = onFrame
        requestRender()
    }

    fun release() = renderer.release()

    private fun check(step: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) Log.e("Latent", "film preview: GL error 0x${Integer.toHexString(e)} after $step")
    }

    private inner class FilmRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var program = 0
        private var texture = 0
        private var cameraSurfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private val transform = FloatArray(16)
        private var bufW = 1920
        private var bufH = 1440
        private var viewW = 0
        private var viewH = 0

        @Volatile var pendingLut: FloatArray? = null
        @Volatile var pendingLutSize = 33
        @Volatile var exposureGain = 1f
        @Volatile var tint = floatArrayOf(1f, 1f, 1f)
        @Volatile var grabSize = 0
        @Volatile var onGrab: ((FloatArray, Int, Int) -> Unit)? = null
        private var readBuffer: java.nio.ByteBuffer? = null
        private var grabFbo = 0
        private var grabTex = 0

        /** A small off-screen target used only for metering the camera image. */
        private fun createGrabTarget() {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); grabTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, grabTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, GRAB_SIDE, GRAB_SIDE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0)
            grabFbo = fb[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, grabFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, grabTex, 0)
            val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (!ok) { Log.e("Latent", "film preview: metering target incomplete; scene metering disabled"); grabFbo = 0 }
        }
        private var lutTexture = 0
        private var lutSize = 0
        // Two samplers of different kinds must never share a texture unit: with no look table
        // yet, the look sampler still needs a real 2D texture bound to unit 1, or the driver
        // rejects every draw (GL_INVALID_OPERATION) and the preview stays black.
        private var placeholderTexture = 0
        private var reportedError = false
        private val frames = java.util.concurrent.atomic.AtomicInteger(0)

        fun setBufferSize(w: Int, h: Int) {
            bufW = w; bufH = h
            cameraSurfaceTexture?.setDefaultBufferSize(w, h)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // The GL context is recreated after a pause: drop the previous camera surface so
            // it is never handed to the camera again, and start clean.
            release()
            lutTexture = 0
            lutSize = 0
            placeholderTexture = 0
            reportedError = false
            frames.set(0)
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) { post { onUnavailable?.invoke("the film preview shader could not be built") }; return }
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            check("external texture setup")

            GLES20.glGenTextures(1, ids, 0)
            placeholderTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, placeholderTexture)
            val white = java.nio.ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).apply { rewind() }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, white)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            check("placeholder texture setup")

            val st = SurfaceTexture(texture)
            st.setDefaultBufferSize(bufW, bufH)
            st.setOnFrameAvailableListener(this)
            cameraSurfaceTexture = st
            val surf = Surface(st)
            surface = surf
            check("surface texture creation")
            Log.i("Latent", "film preview: GL surface ready (${bufW}x$bufH)")
            post { onSurfaceReady?.invoke(surf, st) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width; viewH = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            val n = frames.incrementAndGet()
            if (n == 1 || n == 30) Log.i("Latent", "film preview: camera frame $n arrived")
            requestRender()
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = cameraSurfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(transform)
            uploadPendingLut()

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, UV)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGain"), exposureGain)
            val t = tint
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uTint"), t[0], t[1], t[2])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutSize"), lutSize.toFloat())
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uHasLut"), if (lutSize > 0) 1 else 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)
            // Unit 1 always carries a 2D texture: the look table when there is one, the 1x1
            // stand-in otherwise.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (lutSize > 0) lutTexture else placeholderTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLut"), 1)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // A frame grab, when one was asked for. Metering must see the CAMERA image, not
            // our own output: reading back the screen would measure the gain and look we just
            // applied and chase its own tail. So the camera texture is drawn once into a small
            // off-screen buffer with the look switched off, and that is what gets measured.
            val want = grabSize
            val cb = onGrab
            if (want > 0 && cb != null) {
                grabSize = 0; onGrab = null
                val g = want.coerceIn(8, GRAB_SIDE)
                if (grabFbo == 0) createGrabTarget()
                if (grabFbo != 0) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, grabFbo)
                    GLES20.glViewport(0, 0, g, g)
                    GLES20.glUseProgram(program)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGain"), 1f)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uHasLut"), 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    if (readBuffer == null) readBuffer = java.nio.ByteBuffer.allocateDirect(GRAB_SIDE * GRAB_SIDE * 4).order(java.nio.ByteOrder.nativeOrder())
                    val buf = readBuffer!!
                    buf.rewind()
                    GLES20.glReadPixels(0, 0, g, g, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, viewW, viewH)
                    check("metering pass")

                    val out = FloatArray(g * g * 3)
                    for (i in 0 until g * g) {
                        out[i * 3] = SRGB_TO_LINEAR[buf.get(i * 4).toInt() and 0xFF]
                        out[i * 3 + 1] = SRGB_TO_LINEAR[buf.get(i * 4 + 1).toInt() and 0xFF]
                        out[i * 3 + 2] = SRGB_TO_LINEAR[buf.get(i * 4 + 2).toInt() and 0xFF]
                    }
                    cb(out, g, g)
                }
            }
            if (!reportedError && frames.get() > 0) {
                val e = GLES20.glGetError()
                reportedError = true
                if (e != GLES20.GL_NO_ERROR) Log.e("Latent", "film preview: GL error 0x${Integer.toHexString(e)} drawing a camera frame")
                else Log.i("Latent", "film preview: camera frame drawn cleanly (view ${viewW}x$viewH, buffer ${bufW}x$bufH)")
            }
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(uv)
        }

        /**
         * The table is uploaded as a wide 2D strip: x = blue*size + red, y = green.
         *
         * The engine emits its entries with BLUE varying fastest and RED slowest, so the entry
         * for a colour (r, g, b) sits at ((r*size + g)*size + b). Getting this order wrong
         * scrambles the colour axes while still looking like a film, so it is spelled out here.
         */
        private fun uploadPendingLut() {
            val lut = pendingLut ?: return
            pendingLut = null
            val n = pendingLutSize
            if (lut.size < n * n * n * 3) {
                Log.e("Latent", "film preview: look table has ${lut.size / 3} entries, expected ${n * n * n}")
                return
            }
            if (lutTexture == 0) {
                val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); lutTexture = ids[0]
            }
            val w = n * n
            val bytes = java.nio.ByteBuffer.allocateDirect(w * n * 4).order(java.nio.ByteOrder.nativeOrder())
            for (g in 0 until n) {              // texture row
                for (b in 0 until n) {          // slice across the row
                    for (r in 0 until n) {      // position within the slice
                        val i = ((r * n + g) * n + b) * 3   // engine order: blue fastest
                        bytes.put(((lut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte())
                        bytes.put(((lut[i + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte())
                        bytes.put(((lut[i + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte())
                        bytes.put(255.toByte())
                    }
                }
            }
            bytes.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            check("look table upload")
            lutSize = n
            Log.i("Latent", "film preview: look table live ($n³)")
        }

        fun release() {
            grabSize = 0
            onGrab = null
            readBuffer = null
            grabFbo = 0
            grabTex = 0
            surface?.release(); surface = null
            cameraSurfaceTexture?.release(); cameraSurfaceTexture = null
        }
    }

    /** Builds the program, reporting exactly why if it fails. Returns 0 on failure. */
    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String, label: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            val info = GLES20.glGetShaderInfoLog(id)
            if (ok[0] == 0) {
                Log.e("Latent", "film preview: $label shader did not compile — ${info.trim()}")
                GLES20.glDeleteShader(id)
                return 0
            }
            if (info.isNotBlank()) Log.i("Latent", "film preview: $label shader note — ${info.trim()}")
            return id
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs, "vertex")
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs, "fragment")
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e("Latent", "film preview: program did not link — ${GLES20.glGetProgramInfoLog(p).trim()}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        Log.i("Latent", "film preview: shader program ready")
        return p
    }

    companion object {
        /** Side of the off-screen square used for metering. */
        private const val GRAB_SIDE = 64

        /** The display curve undone, once, so metering sees scene-linear light. */
        private val SRGB_TO_LINEAR = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }

        private val QUAD = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val UV = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun floatBuffer(a: FloatArray) = java.nio.ByteBuffer.allocateDirect(a.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(a).apply { position(0) }

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTransform;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTransform * aUv).xy;
            }
        """

        // With uHasLut = 0 this is a straight copy of the camera image: the check that the
        // new preview path is correct before any film look is applied.
        private const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uCamera;
uniform sampler2D uLut;
uniform float uLutSize;
uniform float uGain;
uniform vec3 uTint;
uniform int uHasLut;

            vec3 lookup(vec3 c) {
                float n = uLutSize;
                vec3 q = clamp(c, 0.0, 1.0) * (n - 1.0);
                float bLo = floor(q.b);
                float bHi = min(bLo + 1.0, n - 1.0);
                float f = q.b - bLo;
                vec2 uvLo = vec2((bLo * n + q.r + 0.5) / (n * n), (q.g + 0.5) / n);
                vec2 uvHi = vec2((bHi * n + q.r + 0.5) / (n * n), (q.g + 0.5) / n);
                return mix(texture2D(uLut, uvLo).rgb, texture2D(uLut, uvHi).rgb, f);
            }

            void main() {
                vec3 c = texture2D(uCamera, vUv).rgb;
                if (uHasLut == 1) {
                    c = lookup(clamp(c * uGain * uTint, 0.0, 1.0));
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
