package com.celestial.latent.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 92c: the film's own edge around the developed print (approved proof visual-4/3, scripts/border.py):
 * sprocket holes, the edge print, frame numbers and the DX code, as the film the photo was shot on has them.
 * Kept per photo in its framing ([Framing.edgeFrame]) and put on the full-size print only, after lith and
 * before anything else, like the film-still bars: previews stay the bare picture, so painting lines up.
 *
 * Measured in millimetres of film, so it scales with the picture:
 *  - 35 mm stills: the picture's short side is the 24 mm frame height and its long side runs ALONG the film;
 *    frames 2 mm apart. Perforations 2.0 mm along × 2.8 mm across, 4.75 mm apart, 2.0 mm in from each edge.
 *    The stock's name on one edge; frame number ▸, the half-frame "A" number and the DX code on the other.
 *  - motion picture (Vision3): the picture's long side lies ACROSS the film (24.9 mm, Super 35), so the strip
 *    runs the other way; four perforations a frame; the stock number and a key number with its barcode.
 * How each part prints (what light does through it): on a print from a negative the clear base prints a
 * warm brown-black, the holes pass all the light and print deeper black, and the edge print (dense on the
 * negative) prints LIGHT, a little glowing. A slide's unexposed rebate is its darkest black and its holes are
 * clear, so they show white. The neighbouring frames show as unexposed film.
 */
object FilmEdge {
    private const val FILM_MM = 35f
    private const val PERF_MM = 4.75f
    private const val SIDE_MM = 4.2f          // strip shown beyond each end of the frame
    private const val EDGE_IN = 2.0f          // film edge to the perforations
    private const val HOLE_ALONG = 2.0f; private const val HOLE_ACROSS = 2.8f; private const val HOLE_R = 0.5f

    private enum class Kind(val rebate: IntArray, val hole: IntArray, val text: IntArray, val glow: Float) {
        NEGATIVE(intArrayOf(22, 17, 13), intArrayOf(6, 5, 5), intArrayOf(236, 214, 170), 0.55f),
        SLIDE(intArrayOf(8, 8, 10), intArrayOf(246, 244, 236), intArrayOf(214, 222, 205), 0.35f),
        CINE(intArrayOf(18, 15, 12), intArrayOf(5, 5, 5), intArrayOf(232, 206, 160), 0.5f),
    }

    private fun kindOf(film: String) = when {
        film.startsWith("kodak_vision3") -> Kind.CINE
        film in setOf("kodak_ektachrome_100", "kodak_kodachrome_64", "fujifilm_provia_100f", "fujifilm_velvia_100") -> Kind.SLIDE
        else -> Kind.NEGATIVE
    }

    /** What the film carries along its edge. */
    internal fun edgeText(film: String): String = when (film) {
        "kodak_portra_160" -> "KODAK PORTRA 160"
        "kodak_portra_400" -> "KODAK PORTRA 400"
        "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2" -> "KODAK PORTRA 800"
        "kodak_gold_200" -> "KODAK GOLD 200"
        "kodak_ultramax_400" -> "KODAK ULTRAMAX 400"
        "kodak_ektar_100" -> "KODAK EKTAR 100"
        "kodak_ektachrome_100" -> "KODAK EKTACHROME E100"
        "kodak_kodachrome_64" -> "KODAK KODACHROME 64"
        "kodak_verita_200d" -> "KODAK VERITA 200D"
        "fujifilm_c200" -> "FUJIFILM C200"
        "fujifilm_xtra_400" -> "FUJIFILM X-TRA 400"
        "fujifilm_pro_400h" -> "FUJIFILM PRO 400H"
        "fujifilm_provia_100f" -> "FUJIFILM RDP III"
        "fujifilm_velvia_100" -> "FUJIFILM RVP 100"
        "kodak_vision3_50d" -> "KODAK 5203"
        "kodak_vision3_250d" -> "KODAK 5207"
        "kodak_vision3_200t" -> "KODAK 5213"
        "kodak_vision3_500t" -> "KODAK 5219"
        else -> (Develop.FILMS.firstOrNull { it.first == film }?.second ?: "FILM").uppercase()
    }

    /** The size of the edged print of a [w] × [h] picture. */
    fun outputSize(w: Int, h: Int, film: String): Pair<Int, Int> = layout(w, h, kindOf(film)).let { it.outW to it.outH }

    private class Layout(val px: Float, val along: Int, val across: Int, val horizontal: Boolean,
                         val stripLen: Int, val filmW: Int, val u0: Int, val v0: Int, val outW: Int, val outH: Int)

    private fun layout(w: Int, h: Int, kind: Kind): Layout {
        val long = max(w, h); val short = min(w, h)
        val cine = kind == Kind.CINE
        // stills: the short side is the 24 mm frame height; cine: the long side is the 24.9 mm frame width
        val px = if (cine) long / 24.9f else short / 24f
        val along = if (cine) short else long
        val across = if (cine) long else short
        // the strip runs along the picture's x when the along side is its width
        val horizontal = along == w
        val side = (SIDE_MM * px).roundToInt()
        val stripLen = along + 2 * side
        val filmW = max((FILM_MM * px).roundToInt(), across + 2)
        val v0 = (filmW - across) / 2
        return Layout(px, along, across, horizontal, stripLen, filmW, side, v0,
            if (horizontal) stripLen else filmW, if (horizontal) filmW else stripLen)
    }

    /**
     * The picture in [data] ([w] × [h], three floats a pixel in [cs]) set in its film's edge. Returns a new
     * buffer (native when big, as [Develop.pictureBuffer] gives) and its size.
     */
    fun apply(context: Context, data: ByteBuffer, w: Int, h: Int, cs: ColorSpace, film: String, frame: Int): Triple<ByteBuffer, Int, Int> {
        val kind = kindOf(film)
        val L = layout(w, h, kind)
        val ow = L.outW; val oh = L.outH
        // the marks, drawn in film millimetres at half size (they need no more), then laid on
        val ms = 2
        val mw = (ow + ms - 1) / ms; val mh = (oh + ms - 1) / ms
        val holes = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        val text = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        try {
            drawMarks(context, holes, text, L, kind, film, frame, 1f / ms)
            val holeA = alphaOf(holes); val textA = alphaOf(text)
            // the glow: the letters' light spread about a third of a millimetre, at a quarter of the marks' size
            val glow = glowOf(textA, mw, mh, max(1, (0.35f * L.px / ms / 4f).roundToInt()))
            val rebate = inSpace(cs, kind.rebate); val hole = inSpace(cs, kind.hole); val ink = inSpace(cs, kind.text)
            val out = Develop.pictureBuffer(ow.toLong() * oh * 12L)
            val dst = out.asFloatBuffer()
            val src = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            // the picture's corner in the output
            val px0 = if (L.horizontal) L.u0 else ow - L.v0 - L.across
            val py0 = if (L.horizontal) L.v0 else L.u0
            val corner = 0.35f * L.px
            val row = FloatArray(ow * 3)
            val pic = FloatArray(w * 3)
            val gq = 4 * ms; val gw = (mw + 3) / 4
            for (y in 0 until oh) {
                val sy = y - py0
                if (sy in 0 until h) { src.position(sy * w * 3); src.get(pic) }
                val my = min(y / ms, mh - 1)
                for (x in 0 until ow) {
                    val o = x * 3
                    val sx = x - px0
                    if (sy in 0 until h && sx in 0 until w) {
                        // the frame: the gate's corners a little round
                        val a = cornerAlpha(sx, sy, w, h, corner)
                        val i = sx * 3
                        if (a >= 1f) { row[o] = pic[i]; row[o + 1] = pic[i + 1]; row[o + 2] = pic[i + 2]; continue }
                        for (c in 0..2) row[o + c] = rebate[c] + (pic[i + c] - rebate[c]) * a
                        continue
                    }
                    val mi = my * mw + min(x / ms, mw - 1)
                    val hA = holeA[mi]
                    val g = glow[min(y / gq, glow.size / gw - 1) * gw + min(x / gq, gw - 1)]
                    val tA = min(1f, textA[mi] + kind.glow * g)
                    for (c in 0..2) {
                        var v = rebate[c] + (hole[c] - rebate[c]) * hA
                        v += (ink[c] - v) * tA
                        row[o + c] = v
                    }
                }
                dst.position(y * ow * 3); dst.put(row)
            }
            return Triple(out, ow, oh)
        } finally { holes.recycle(); text.recycle() }
    }

    private fun cornerAlpha(x: Int, y: Int, w: Int, h: Int, r: Float): Float {
        val dx = when { x < r -> r - x - 0.5f; x > w - 1 - r -> x + 0.5f - (w - r); else -> return 1f }
        val dy = when { y < r -> r - y - 0.5f; y > h - 1 - r -> y + 0.5f - (h - r); else -> return 1f }
        return (r - sqrt(dx * dx + dy * dy) + 0.5f).coerceIn(0f, 1f)
    }

    private fun alphaOf(b: Bitmap): FloatArray {
        val bytes = ByteBuffer.allocate(b.rowBytes * b.height)
        b.copyPixelsToBuffer(bytes)
        val a = bytes.array()
        return FloatArray(b.width * b.height) { i -> (a[(i / b.width) * b.rowBytes + i % b.width].toInt() and 255) / 255f }
    }

    /** The marks averaged down by four, then two box passes each way: a soft glow, cheap at any size. */
    private fun glowOf(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val gw = (w + 3) / 4; val gh = (h + 3) / 4
        val g = FloatArray(gw * gh)
        for (y in 0 until h) for (x in 0 until w) g[(y / 4) * gw + x / 4] += a[y * w + x] / 16f
        val t = FloatArray(g.size)
        repeat(2) { box(g, t, gw, gh, r, true); box(t, g, gw, gh, r, false) }
        return g
    }

    private fun box(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w; val len = if (horizontal) w else h
        val n = 2f * r + 1f
        for (l in 0 until lines) {
            fun at(i: Int): Float { val c = i.coerceIn(0, len - 1); return if (horizontal) src[l * w + c] else src[c * w + l] }
            var s = 0f
            for (i in -r..r) s += at(i)
            for (i in 0 until len) {
                dst[if (horizontal) l * w + i else i * w + l] = s / n
                s += at(i + r + 1) - at(i - r)
            }
        }
    }

    private fun font(context: Context, bold: Boolean): Typeface = runCatching {
        context.resources.getFont(if (bold) com.celestial.latent.R.font.roboto_bold else com.celestial.latent.R.font.roboto_medium)
    }.getOrDefault(if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)

    /** The holes and the edge print, in film millimetres (u along the film, v across), scaled by [s]. */
    private fun drawMarks(context: Context, holes: Bitmap, text: Bitmap, L: Layout, kind: Kind, film: String, frame: Int, s: Float) {
        val mm = L.px * s
        fun canvasFor(b: Bitmap) = Canvas(b).apply {
            // film space → the output: the strip along x, or turned a quarter so it runs down the picture
            if (!L.horizontal) { translate(L.outW * s, 0f); rotate(90f) }
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        val u0 = L.u0 * s; val along = L.along * s
        val filmW = L.filmW * s
        val mid = u0 + along / 2f
        // the perforations, both edges, lined up on the frame's middle
        run {
            val c = canvasFor(holes)
            val len = L.stripLen * s
            var k = -40
            while (true) {
                val cu = mid + (k + 0.5f) * PERF_MM * mm
                k++
                if (cu < -HOLE_ALONG * mm) continue
                if (cu > len + HOLE_ALONG * mm) break
                for (top in listOf(true, false)) {
                    val v1 = if (top) EDGE_IN * mm else filmW - (EDGE_IN + HOLE_ACROSS) * mm
                    c.drawRoundRect(RectF(cu - HOLE_ALONG / 2 * mm, v1, cu + HOLE_ALONG / 2 * mm, v1 + HOLE_ACROSS * mm), HOLE_R * mm, HOLE_R * mm, fill)
                }
            }
        }
        val c = canvasFor(text)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = font(context, true); textSize = 1.25f * mm }
        val num = Paint(name).apply { textSize = 1.45f * mm }
        val small = Paint(name).apply { typeface = font(context, false); textSize = 0.9f * mm }
        // text centred on a line [v] mm in from the top edge
        fun write(p: Paint, t: String, u: Float, v: Float) {
            val fm = p.fontMetrics
            c.drawText(t, u, v - (fm.ascent + fm.descent) / 2f, p)
        }
        val vTop = EDGE_IN * mm / 2f
        val vBot = filmW - EDGE_IN * mm / 2f
        val label = edgeText(film)
        val rnd = java.util.Random(frame * 7919L + film.hashCode())
        if (kind == Kind.CINE) {
            write(name, label, u0 + 0.5f * mm, vTop)
            // the key number: a short barcode, and its line for people on the other edge
            var x = u0 + along * 0.55f
            repeat(26) {
                val bw = (if (rnd.nextFloat() < 0.6f) 0.12f else 0.26f) * mm
                c.drawRect(x, vTop - 0.55f * mm, x + bw, vTop + 0.55f * mm, fill)
                x += bw + 0.14f * mm
            }
            val key = "KM %02d %04d %04d+%02d".format(java.util.Locale.US, 10 + rnd.nextInt(80), rnd.nextInt(10000), rnd.nextInt(10000), frame % 16)
            write(small, key, u0 + 0.5f * mm, vBot)
            write(small, "$frame", u0 + along - 3f * mm, vBot)
        } else {
            val pitch = along + 2f * mm
            write(name, label, u0 + along * 0.08f, vTop)
            write(name, label, u0 + along * 0.08f - pitch, vTop)
            val n = frame
            write(num, "$n", u0 + along * 0.06f, vBot)
            // the arrow after the number points the way the film winds
            val ax = u0 + along * 0.06f + num.measureText("$n") + 0.5f * mm
            c.drawPath(Path().apply { moveTo(ax, vBot - 0.42f * mm); lineTo(ax + 0.7f * mm, vBot); lineTo(ax, vBot + 0.42f * mm); close() }, fill)
            write(num, "${n}A", u0 + along * 0.56f, vBot)
            if (n > 1) write(num, "${n - 1}A", u0 + along * 0.06f - pitch * 0.5f, vBot)
            write(num, "${n + 1}", u0 + along + 2f * mm + along * 0.06f, vBot)
            // DX: a clock track and a data track, right at the edge, between the numbers
            var x = u0 + along * 0.20f
            while (x < u0 + along * 0.50f) {
                c.drawRect(x, filmW - 0.95f * mm, x + 0.26f * mm, filmW - 0.62f * mm, fill)
                if (rnd.nextFloat() < 0.55f) c.drawRect(x, filmW - 0.55f * mm, x + 0.26f * mm, filmW - 0.22f * mm, fill)
                x += 0.52f * mm
            }
        }
    }

    // ---- colour: the edge's sRGB colours in the print's own space ----
    private fun srgbDecode(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }
    private fun inSpace(cs: ColorSpace, rgb: IntArray): FloatArray {
        val m = mul(Saturation.fromLms(cs), Saturation.toLms(ColorSpace.SRGB))
        val l = FloatArray(3) { srgbDecode(rgb[it] / 255f) }
        return FloatArray(3) { c -> Saturation.encode(cs, (m[c * 3] * l[0] + m[c * 3 + 1] * l[1] + m[c * 3 + 2] * l[2]).coerceIn(0f, 1f)) }
    }

    /**
     * The frame number a photo gets: its place among the RAWs shot that day in DCIM/Latent, as a roll of
     * 36 counts (37 starts again at 1). 1 when the photo is not one of the camera's own.
     */
    fun frameNumber(context: Context, photo: Uri): Int = runCatching {
        val cr = context.contentResolver
        val taken = cr.query(photo, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return 1
        val day = java.util.Calendar.getInstance().apply { timeInMillis = taken; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val before = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ? AND ${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
            arrayOf("DCIM/Latent%", "image/x-adobe-dng", day.toString(), taken.toString()), null)?.use { it.count } ?: 0
        before % 36 + 1
    }.getOrDefault(1)

    /** A small edged copy of [picture] (an sRGB bitmap), for the FRAME panel to show what the print will get. */
    fun sample(context: Context, picture: Bitmap, film: String, frame: Int, longEdge: Int = 720): Bitmap? = runCatching {
        val sc = min(1f, longEdge / max(picture.width, picture.height).toFloat())
        val sw = max(8, (picture.width * sc).roundToInt()); val sh = max(8, (picture.height * sc).roundToInt())
        val small = Bitmap.createScaledBitmap(picture, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== picture) small.recycle()
        val buf = ByteBuffer.allocateDirect(sw * sh * 12).order(ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        for (p in px) { f.put(((p shr 16) and 255) / 255f); f.put(((p shr 8) and 255) / 255f); f.put((p and 255) / 255f) }
        val (out, ow, oh) = apply(context, buf, sw, sh, ColorSpace.SRGB, film, frame)
        val o = out.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val pix = IntArray(ow * oh) { i ->
            val r = (o.get(i * 3).coerceIn(0f, 1f) * 255f + 0.5f).toInt(); val g = (o.get(i * 3 + 1).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val b = (o.get(i * 3 + 2).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        Develop.freeNative(out)
        Bitmap.createBitmap(pix, ow, oh, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
