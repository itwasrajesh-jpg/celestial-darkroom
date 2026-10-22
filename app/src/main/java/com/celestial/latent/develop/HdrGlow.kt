package com.celestial.latent.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ultra HDR glow (78a): the developed JPEG saved as an Ultra HDR file.
 *
 * The file is two pictures in one. The normal JPEG is the print exactly as it is today; every app and
 * every screen shows it. Behind it sits a small hidden map (the "gain map", a quarter of the size) that
 * tells an HDR screen how much brighter than paper white each place may shine, up to 2^stops. Android 14
 * and newer write it ([Bitmap.setGainmap] + compress) and show it in a window switched to HDR.
 *
 * Where it glows comes from the RAW, not from the print: a place glows only if the scene there was
 * really bright (0.3 to 2.8 stops above middle grey, at the exposure the film was given) AND the print
 * shows it bright (print brightness 0.28 to 0.7), both eased in smoothly. So a lamp glows and a white
 * wall that was only grey light does not; skin, shadows and mid-tones stay exactly as printed.
 * Proven on Celestial's plants RAW (`ultra-hdr/proof/proof.py`, approved 22 Oct): the same rule, the
 * map averaged to a quarter size and softened by a 1.2-pixel blur.
 *
 * Lith prints never glow (matte paper; its border is not in the map).
 */
object HdrGlow {
    /** Android 14 is the first that can write and show a gain map. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 34

    const val MAX_STOPS = 2f
    /** The chips in Settings, and the slider's steps (quarter stops). */
    val CHOICES = listOf(0f, 0.5f, 1f, 1.5f, 2f)

    /** "Off", "+½", "+1", "+1½", "+2", with quarters as ¼ and ¾. */
    fun label(stops: Float): String {
        if (stops < 0.125f) return "Off"
        val q = Math.round(stops * 4)
        val whole = q / 4
        val part = when (q % 4) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
        return "+" + (if (whole > 0) "$whole" else "") + part
    }

    /** How much brighter than white the brightest places may go, as a plain number (2^stops). */
    fun times(stops: Float): String = String.format(java.util.Locale.US, "%.1f", Math.pow(2.0, stops.toDouble())).removeSuffix(".0")

    fun wanted(stops: Float, lith: LithLook): Boolean = supported && stops >= 0.125f && !lith.on

    /** Where the print may glow, a quarter of its size: 0 none … 255 the full glow. */
    class Map(val width: Int, val height: Int, val values: ByteArray) {
        /** The share of the picture that glows at all, for the log. */
        fun cover(): Float = values.count { it.toInt() != 0 }.toFloat() / values.size.coerceAtLeast(1)
    }

    private fun smooth(x: Float, a: Float, b: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The glow map for a print: [print] is the engine's output ([pw] × [ph], three floats a pixel, display
     * values 0..1), made from [scene] (linear ProPhoto, the same picture at its own size) exposed at
     * [sceneEv] stops. Each print pixel reads the scene at the same place in the picture.
     */
    fun shape(scene: Develop.Source, sceneEv: Double, print: ByteBuffer, pw: Int, ph: Int): Map =
        shape(scene.image.data, scene.width, scene.height, sceneEv, print, pw, ph)

    fun shape(sceneData: ByteBuffer, sw: Int, sh: Int, sceneEv: Double, print: ByteBuffer, pw: Int, ph: Int): Map {
        val s = sceneData.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val p = print.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val qw = (pw / 4).coerceAtLeast(1); val qh = (ph / 4).coerceAtLeast(1)
        val gain = Math.pow(2.0, sceneEv).toFloat()
        val ln2 = Math.log(2.0).toFloat()
        // where each print column and row reads the scene, and which quarter-size cell it falls in
        val sx = IntArray(pw) { x -> ((x + 0.5f) * sw / pw).toInt().coerceIn(0, sw - 1) }
        val cx = IntArray(pw) { x -> (x.toLong() * qw / pw).toInt().coerceAtMost(qw - 1) }
        // a 2× print is 50 MP: the cells' pixel counts come from their rows and columns, not one count per cell
        val perCol = IntArray(qw); for (x in 0 until pw) perCol[cx[x]]++
        val perRow = IntArray(qh); for (y in 0 until ph) perRow[(y.toLong() * qh / ph).toInt().coerceAtMost(qh - 1)]++
        val sum = FloatArray(qw * qh)
        for (y in 0 until ph) {
            val sy = ((y + 0.5f) * sh / ph).toInt().coerceIn(0, sh - 1)
            val row = ((y.toLong() * qh / ph).toInt().coerceAtMost(qh - 1)) * qw
            var o = y * pw * 3
            for (x in 0 until pw) {
                val cell = row + cx[x]
                val r = p.get(o).coerceIn(0f, 1f); val g = p.get(o + 1).coerceIn(0f, 1f); val b = p.get(o + 2).coerceIn(0f, 1f)
                o += 3
                // the print's own brightness first: most of a picture is too dark to glow, and needs no scene
                val printed = smooth(0.2126f * r + 0.7152f * g + 0.0722f * b, 0.28f, 0.7f)
                if (printed <= 0f) continue
                val i = (sy * sw + sx[x]) * 3
                val lum = (0.2880f * s.get(i) + 0.7119f * s.get(i + 1) + 0.0001f * s.get(i + 2)) * gain
                val stops = Math.log((maxOf(lum, 1e-6f) / 0.18f).toDouble()).toFloat() / ln2
                sum[cell] += smooth(stops, 0.3f, 2.8f) * printed
            }
        }
        for (j in 0 until qh) for (i in 0 until qw) sum[j * qw + i] /= (perCol[i] * perRow[j]).coerceAtLeast(1)
        val soft = blur(sum, qw, qh, 1.2f)
        return Map(qw, qh, ByteArray(qw * qh) { (soft[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() })
    }

    /** A gaussian blur of [sigma] cells, edges held, written over [v]. */
    private fun blur(v: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = Math.ceil(3.0 * sigma).toInt()
        val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); Math.exp((-d * d / (2 * sigma * sigma)).toDouble()).toFloat() }
        val total = k.sum(); for (i in k.indices) k[i] /= total
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (j in -r..r) a += k[j + r] * v[y * w + (x + j).coerceIn(0, w - 1)]
            tmp[y * w + x] = a
        }
        // back into [v]: its values are no longer needed, and a 2× print's map is 12 MB a copy
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (j in -r..r) a += k[j + r] * tmp[(y + j).coerceIn(0, h - 1) * w + x]
            v[y * w + x] = a
        }
        return v
    }

    /**
     * The map for a picture of [w] × [h] set in a frame of [fw] × [fh] (the film-still bars): the bars
     * never glow. Placed with the same arithmetic as the bars themselves.
     */
    fun framed(m: Map, w: Int, h: Int, fw: Int, fh: Int): Map {
        if (fw == w && fh == h) return m
        val qw = (fw / 4).coerceAtLeast(1); val qh = (fh / 4).coerceAtLeast(1)
        val out = ByteArray(qw * qh)
        val x0 = (fw - w) / 2; val y0 = (fh - h) / 2
        for (y in 0 until qh) for (x in 0 until qw) {
            // the frame cell's middle, in the picture's own 0..1
            val u = ((x + 0.5f) * fw / qw - x0) / w; val v = ((y + 0.5f) * fh / qh - y0) / h
            if (u < 0f || u >= 1f || v < 0f || v >= 1f) continue
            out[y * qw + x] = m.values[(v * m.height).toInt().coerceAtMost(m.height - 1) * m.width + (u * m.width).toInt().coerceAtMost(m.width - 1)]
        }
        return Map(qw, qh, out)
    }

    /** One channel, kept as it is. */
    private fun contents(m: Map): Bitmap {
        val g = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ALPHA_8)
        // a bitmap's rows may be padded: copy row by row at its own stride
        val stride = g.rowBytes
        val buf = ByteArray(stride * m.height)
        for (y in 0 until m.height) System.arraycopy(m.values, y * m.width, buf, y * stride, m.width)
        g.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
        return g
    }

    /**
     * The glow's strength: the map's white lifts that place by 2^[stops] (its grey by part of that), and a
     * screen with less spare brightness than that shows as much of it as it can.
     */
    private fun gainmapOf(contents: Bitmap, stops: Float): android.graphics.Gainmap {
        val boost = Math.pow(2.0, stops.toDouble()).toFloat()
        return android.graphics.Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(boost, boost, boost)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(boost)
        }
    }

    /** Puts the glow behind [bmp]: compressing it as a JPEG then writes an Ultra HDR file. */
    fun attach(bmp: Bitmap, m: Map, stops: Float) {
        if (Build.VERSION.SDK_INT < 34) return
        bmp.setGainmap(gainmapOf(contents(m), stops))
    }

    /** True when [b] carries a glow. */
    fun has(b: Bitmap?): Boolean = b != null && Build.VERSION.SDK_INT >= 34 && b.hasGainmap()

    /**
     * [b] with its glow set to [stops] (Off removes it), as a new bitmap so the screen redraws. The map's
     * shape does not depend on the strength, so the slider needs no new develop while it has a map to retune.
     */
    fun retuned(b: Bitmap, stops: Float): Bitmap {
        if (Build.VERSION.SDK_INT < 34) return b
        val old = b.gainmap ?: return b
        val copy = b.copy(b.config ?: Bitmap.Config.ARGB_8888, false) ?: return b
        copy.setGainmap(if (stops < 0.125f) null else gainmapOf(old.gainmapContents, stops))
        return copy
    }

    /**
     * The darkroom's quick pass (75b) lays the centre over the last whole preview; this lays their glows
     * the same way onto [out], so the picture does not lose its glow while a control moves.
     */
    fun inPlace(out: Bitmap, part: Bitmap, under: Bitmap?, fraction: Float) {
        if (Build.VERSION.SDK_INT < 34) return
        val pg = part.gainmap ?: return
        val pc = pg.gainmapContents
        val gw = Math.round(pc.width / fraction).coerceAtLeast(1); val gh = Math.round(pc.height / fraction).coerceAtLeast(1)
        val c = Bitmap.createBitmap(gw, gh, pc.config ?: Bitmap.Config.ALPHA_8)
        val canvas = android.graphics.Canvas(c)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        }
        val uc = under?.takeIf { it.hasGainmap() && Math.abs(it.width.toFloat() / it.height - out.width.toFloat() / out.height) < 0.02f }?.gainmap?.gainmapContents
        if (uc != null) canvas.drawBitmap(uc, null, android.graphics.Rect(0, 0, gw, gh), paint)
        val left = (gw - pc.width) / 2; val top = (gh - pc.height) / 2
        canvas.drawBitmap(pc, null, android.graphics.Rect(left, top, left + pc.width, top + pc.height), paint)
        out.setGainmap(android.graphics.Gainmap(c).apply {
            val mx = pg.ratioMax; setRatioMin(1f, 1f, 1f); setRatioMax(mx[0], mx[1], mx[2])
            setGamma(1f, 1f, 1f); setEpsilonSdr(0f, 0f, 0f); setEpsilonHdr(0f, 0f, 0f)
            setMinDisplayRatioForHdrTransition(1f); setDisplayRatioForFullHdr(pg.displayRatioForFullHdr)
        })
    }

    /**
     * A saved print, small enough for the screen, with its glow (a thumbnail would drop it). Null when it
     * cannot be read; the caller falls back to the thumbnail.
     */
    fun decodeSmall(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /**
     * Switches this screen's window to HDR (only while the darkroom asks for it): without it Android draws
     * every picture at paper white and the glow stays hidden. Off puts the ordinary mode back.
     */
    fun setWindowHdr(context: Context, on: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val window = (c as? android.app.Activity)?.window ?: return
        window.colorMode = if (on) android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        Log.i("Latent", "hdr view " + (if (on) "on" else "off") + "; screen can show HDR: " +
            (runCatching { (c as android.app.Activity).display?.isHdr }.getOrNull() ?: "unknown"))
    }

    /** True when this phone's screen says it can show HDR. */
    fun screenCan(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 && runCatching {
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.display?.isHdr == true
    }.getOrDefault(false)
}

/**
 * Each photo's own glow (78a), kept beside it; a photo never set takes the starting value from Settings
 * (about +1½, as the approved proof). Removed with the photo by [PhotoData.forget].
 */
object HdrGlows {
    private const val FILE = "latent_hdr"
    /** Not a photo's address, so it can never clash with one. */
    private const val DEFAULT_KEY = "default"
    const val START = 1.5f
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun defaultStops(context: Context): Float = prefs(context).getFloat(DEFAULT_KEY, START)
    fun setDefault(context: Context, stops: Float) { prefs(context).edit().putFloat(DEFAULT_KEY, stops.coerceIn(0f, HdrGlow.MAX_STOPS)).apply() }

    fun load(context: Context, photo: Uri): Float = prefs(context).getFloat(photo.toString(), defaultStops(context))
    fun save(context: Context, photo: Uri, stops: Float) { prefs(context).edit().putFloat(photo.toString(), stops.coerceIn(0f, HdrGlow.MAX_STOPS)).apply() }
}
