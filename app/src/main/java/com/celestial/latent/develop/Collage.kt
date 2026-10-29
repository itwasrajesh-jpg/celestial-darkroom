package com.celestial.latent.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Collages (step 98a): the picked photos laid out on one page, in six layouts — Grid, Hexagon, Film strip, Scrapbook,
 * Polaroid and Cut-out — one from each family of the approved pages (calendar/proof, the month block left out; the
 * mock in collage/mock). The first photo is the HERO, the one the page is built round.
 *
 * Everything is drawn in page units: the page is [W] wide (an A4 page at 150 dpi, as the proofs were), its height
 * depends on the layout and on how many photos there are, and [render] scales the whole page to any size, so the
 * preview and the saved collage are the same page. Text is drawn in three OFL fonts kept in assets/collage
 * (Dancing Script, Caveat, Playfair Display italic) and the system sans (Roboto).
 *
 * The pages hold any number of photos from 2 up to [MAX_PHOTOS]: each layout grows or shrinks its pattern to fit
 * (a grid adds rows, the honeycomb grows its cells, the film strip its frames), and a few cap how many they use.
 */
object Collage {
    const val W = 1240
    const val MAX_PHOTOS = 13
    val LAYOUTS = listOf("grid", "hexagon", "strip", "scrapbook", "polaroid", "cutout")
    val LABELS = mapOf("grid" to "Grid", "hexagon" to "Hexagon", "strip" to "Film strip", "scrapbook" to "Scrapbook",
        "polaroid" to "Polaroid", "cutout" to "Cut-out")
    /** How many photos a layout uses at most (the hero included). */
    val CAP = mapOf("grid" to 13, "hexagon" to 13, "strip" to 7, "scrapbook" to 5, "polaroid" to 12, "cutout" to 10)
    /** The layouts with a caption under each print. */
    val CAPTIONED = setOf("scrapbook", "polaroid")

    class Fonts(val script: Typeface, val hand: Typeface, val serifItalic: Typeface, val sans: Typeface)
    private var fontCache: Fonts? = null
    fun fonts(context: Context): Fonts = fontCache ?: Fonts(
        Typeface.createFromAsset(context.assets, "collage/dancing_script.ttf"),
        Typeface.createFromAsset(context.assets, "collage/caveat.ttf"),
        Typeface.createFromAsset(context.assets, "collage/playfair_italic.ttf"),
        Typeface.create("sans-serif-medium", Typeface.NORMAL),
    ).also { fontCache = it }

    /** The words on the page: a title, a line under it, a caption per photo (in the photos' order) and a note. */
    data class Words(val title: String, val sub: String, val captions: List<String>, val note: String = "")

    /** Where a piece of text sits on the page (in page units), so a tap there can edit it: "title", "sub", "note", "cap:<i>". */
    class TextSpot(val id: String, val box: RectF)

    class Page(val bitmap: Bitmap, val width: Int, val height: Int, val spots: List<TextSpot>)

    /**
     * The page for [layout] with [photos] (the hero first), drawn [scale] pixels per page unit.
     * [seed] 0 keeps the photos in the order picked; Shuffle passes another, which reorders the others and moves the
     * loose prints. [cut] is the hero's subject cut out (its alpha the outline), for Cut-out; without it the hero
     * stands in front as a print.
     */
    fun render(layout: String, photos: List<Bitmap>, words: Words, seed: Long, scale: Float, fonts: Fonts, cut: Bitmap? = null): Page {
        val rng = Random(seed * 7919 + 17)
        val order = photos.indices.toMutableList()
        if (seed != 0L) { val rest = order.drop(1).toMutableList(); java.util.Collections.shuffle(rest, rng); order.clear(); order += 0; order += rest }
        val used = order.take(CAP.getValue(layout))
        val d = Draw(photos, used, words, rng, fonts)
        val h = d.height(layout)
        val bw = max(1, (W * scale).roundToInt()); val bh = max(1, (h * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bw.toFloat() / W, bh.toFloat() / h)
        d.scale = bw.toFloat() / W
        when (layout) {
            "hexagon" -> d.hexagon(c, h)
            "strip" -> d.strip(c, h, bmp)
            "scrapbook" -> d.scrapbook(c, h)
            "polaroid" -> d.polaroid(c, h)
            "cutout" -> d.cutout(c, h, cut)
            else -> d.grid(c, h)
        }
        return Page(bmp, bw, bh, d.spots)
    }

    // -------------------------------------------------------------------------------------------------------------
    // The pages
    // -------------------------------------------------------------------------------------------------------------

    private class Draw(val photos: List<Bitmap>, val used: List<Int>, val words: Words, val rng: Random, val f: Fonts) {
        val spots = ArrayList<TextSpot>()
        var scale = 1f
        val hero get() = photos[used[0]]
        val others get() = used.drop(1)

        fun height(layout: String): Int = when (layout) {
            "grid" -> gridPlan().let { it.bottom + 50 + 290 }
            "hexagon" -> 1290
            "strip" -> 1520
            "scrapbook" -> 1620
            "polaroid" -> { val rows = ceil(used.size / 3.0).toInt(); 260 + (rows - 1) * 370 + 205 + 270 }
            "cutout" -> 1310
            else -> 1600
        }

        // ---- 1) GRIDS: clean mat, a big hero tile among small ones, script title ----------------------------
        class Cell(val x: Float, val y: Float, val w: Float, val h: Float)
        class GridPlan(val hero: Cell, val cells: List<Cell>, val bottom: Int)
        fun gridPlan(): GridPlan {
            val m = 70f; val g = 14f; val o = others.size
            val cols = when { o <= 2 -> 2; o <= 5 -> 3; else -> 4 }
            val cw = (W - 2 * m - (cols - 1) * g) / cols
            fun at(col: Int, row: Int) = (m + col * (cw + g)) to (m + row * (cw + g))
            // the hero's 2 × 2 block: in the middle of a full 4 × 4 grid (the proof), else at the top
            val (hc, hr) = when { cols == 4 && o == 12 -> 1 to 1; cols == 4 -> 1 to 0; else -> 0 to 0 }
            val free = ArrayList<Pair<Int, Int>>()
            var row = 0
            while (free.size < o) {
                for (col in 0 until cols) if (!(col in hc..hc + 1 && row in hr..hr + 1)) free += col to row
                row++
            }
            val cells = ArrayList<Cell>()
            val placed = free.take(o)
            // a last row that isn't full is spread across the page, its tiles widened
            val lastRow = placed.maxOfOrNull { it.second } ?: 0
            val lastRowHasHero = lastRow in hr..hr + 1
            val inLast = placed.filter { it.second == lastRow }
            val full = cols - (if (lastRowHasHero) 2 else 0)
            for ((col, r) in placed) {
                val (x, y) = at(col, r)
                if (r == lastRow && !lastRowHasHero && inLast.size < full) {
                    val k = inLast.size; val i = inLast.indexOf(col to r)
                    val tw = (W - 2 * m - (k - 1) * g) / k
                    cells += Cell(m + i * (tw + g), y, tw, cw)
                } else cells += Cell(x, y, cw, cw)
            }
            val (hx, hy) = at(hc, hr)
            val rows = max(row, hr + 2)
            return GridPlan(Cell(hx, hy, 2 * cw + g, 2 * cw + g), cells, (m + rows * (cw + g) - g).roundToInt())
        }
        fun grid(c: Canvas, h: Int) {
            val mat = rgb(246, 242, 234)
            paper(c, W, h, mat, 0.025f, 1)
            val p = gridPlan()
            others.forEachIndexed { i, k -> val cl = p.cells[i]; photo(c, photos[k], RectF(cl.x, cl.y, cl.x + cl.w, cl.y + cl.h)) }
            val hr = RectF(p.hero.x, p.hero.y, p.hero.x + p.hero.w, p.hero.y + p.hero.h)
            photo(c, hero, hr, fy = 0.45f)
            c.drawRect(RectF(hr.left - 1, hr.top - 1, hr.right + 1, hr.bottom + 1), stroke(mat, 6f))
            val y0 = p.bottom + 50f
            text(c, "title", words.title, 70f, y0 + 10, f.script, 150f, rgb(46, 40, 34), Anchor.LA, maxW = W - 140f)
            text(c, "sub", words.sub, 78f, y0 + 205, f.sans, 26f, rgb(150, 136, 118), Anchor.LA, spacing = 0.08f)
        }

        // ---- 2) SHAPES: a honeycomb down the left, the hero bleeding off the right, dark ------------------------
        fun hexagon(c: Canvas, h: Int) {
            paper(c, W, h, rgb(16, 16, 18), 0.25f, 2)
            // the hero fades in from the left and out towards the bottom
            val hw = 860f
            c.saveLayer(RectF(W - hw, 0f, W.toFloat(), h.toFloat()), null)
            photo(c, hero, RectF(W - hw, 0f, W.toFloat(), h.toFloat()), fx = 0.55f)
            val fadeX = Paint().apply {
                shader = LinearGradient(W - hw + hw * 0.111f, 0f, W - hw + hw * 0.667f, 0f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            c.drawRect(W - hw, 0f, W.toFloat(), h.toFloat(), fadeX)
            val stops = FloatArray(9) { it / 8f }
            val cols = IntArray(9) { i -> val t = stops[i]; val a = (1.6f - 1.7f * t).coerceIn(0f, 1f).toDouble().pow(0.6); ((a * 255).roundToInt() shl 24) }
            c.drawRect(W - hw, 0f, W.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), cols, stops, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            c.restore()
            val n = others.size
            val rows = max(1, ceil(n / 2.0).toInt())
            // the cells grow to fill the column when there are fewer photos (the proof: 10 cells of radius 112)
            val avail = h - 2 * 70f
            val r = min(220f, (avail / ((if (n > 1) rows + 0.5f else rows.toFloat()) * 1.0f) - 10f) / sqrt(3f))
            val hh = sqrt(3f) * r
            val colH = rows * (hh + 10) + (if (n > 1) (hh + 10) / 2 else 0f) - 10
            val top = (h - colH) / 2 - 10
            val gold = rgb(232, 196, 120)
            others.forEachIndexed { i, k ->
                val col = i % 2; val row = i / 2
                val cx = 38 + r + col * (1.5f * r + 8); val cy = top + hh / 2 + row * (hh + 10) + col * (hh + 10) / 2
                val path = Path()
                for (a in 0 until 6) {
                    val t = a * PI / 3; val x = cx + (r * cos(t)).toFloat(); val y = cy + (r * sin(t)).toFloat()
                    if (a == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                shadow(c, path, 10f, 6f, 9f, 0.6f)
                c.drawPath(path, stroke(gold, 10f))
                c.save(); c.clipPath(path); photo(c, photos[k], RectF(cx - r, cy - hh / 2, cx + r, cy + hh / 2)); c.restore()
            }
            text(c, "title", words.title, W - 70f, h - 140f, f.script, 150f, gold, Anchor.RS, maxW = W - 520f)
            text(c, "sub", words.sub.uppercase(), W - 76f, h - 100f, f.sans, 24f, rgb(200, 190, 170), Anchor.RS, spacing = 0.08f)
        }

        // ---- 3) HERO + SIDE: a full-bleed hero, a real film strip of the others down the edge, a frosted band ---
        fun strip(c: Canvas, h: Int, page: Bitmap) {
            photo(c, hero, RectF(0f, 0f, W.toFloat(), h.toFloat()), fy = 0.5f)
            val n = max(1, others.size)
            val gap = 34f
            // five frames of 300 × 210 fill the proof's strip; more frames are shorter, so the strip keeps its length,
            // and fewer are bigger (a wider strip), so one or two frames don't look lost on the hero
            val k = if (n < 5) min(1.6f, sqrt(5f / n)) else 1f
            val fw = 300f * k
            val fh = min(210f * k, (5 * (210f + gap)) / n - gap)
            val sw = fw + 120; val sh = n * (fh + gap) + gap
            val amber = rgb(222, 150, 70)
            put(c, W - 250f - (sw - 420f) / 2, 640f, sw, sh, 5f, 0.55f, 18f, 14f, 16f, Path().apply { addRect(0f, 0f, sw, sh, Path.Direction.CW) }) { s ->
                // the strip's base, its sprocket holes cut through
                s.saveLayer(RectF(0f, 0f, sw, sh), null)
                s.drawRect(0f, 0f, sw, sh, fill(argb(238, 24, 18, 14)))
                val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                var y = 10f
                while (y < sh - 20) { for (x in floatArrayOf(10f, sw - 32f)) s.drawRoundRect(RectF(x, y, x + 22, y + 16), 3f, 3f, clear); y += 30f }
                s.restore()
                others.forEachIndexed { i, k ->
                    val fy = gap + i * (fh + gap)
                    photo(s, photos[k], RectF(60f, fy, 60f + fw, fy + fh))
                    text(s, null, "${14 + 2 * i}", sw - 46f, fy + fh / 2, f.sans, 18f, amber, Anchor.MM)
                }
                // the film's edge printing, reading up the strip
                s.save(); s.rotate(-90f); s.translate(-sh, 0f)
                val edge = "CELESTIAL 400   ▸ " + others.indices.joinToString("   ▸ ") { "${14 + 2 * it}A" }
                text(s, null, edge, 20f, 36f + 8f, f.sans, 18f, (230 shl 24) or (amber and 0xFFFFFF), Anchor.LA)
                s.restore()
            }
            // the frosted band at the bottom, for the words
            val bandTop = h - 300f
            frost(c, page, bandTop, h.toFloat())
            c.drawRect(0f, bandTop, W.toFloat(), h.toFloat(), fill(argb(140, 20, 16, 12)))
            c.drawLine(70f, bandTop, W - 70f, bandTop, stroke(rgb(240, 220, 180), 1f))
            text(c, "title", words.title, 70f, bandTop + 30, f.serifItalic, 120f, rgb(250, 244, 232), Anchor.LA, maxW = W - 140f)
            text(c, "sub", words.sub, 76f, bandTop + 190, f.hand, 44f, rgb(240, 210, 160), Anchor.LA, maxW = W - 140f)
        }

        // ---- 4) SCRAPBOOK: kraft paper, torn prints, tape, captions in pen, a note on notebook paper ------------
        fun scrapbook(c: Canvas, h: Int) {
            paper(c, W, h, rgb(190, 158, 118), 0.07f, 4)
            val ink = rgb(70, 46, 30)
            text(c, "title", words.title, 80f, 70f, f.script, 120f, ink, Anchor.LA, maxW = W - 160f)
            text(c, "sub", words.sub, 90f, 215f, f.hand, 44f, rgb(96, 66, 44), Anchor.LA, maxW = W - 180f)
            val cream = rgb(250, 248, 242)
            // the slots of the approved page, in order (the hero first); a shuffle moves them a little
            class Slot(val cx: Float, val cy: Float, val w: Float, val h: Float, val b: Float, val bottom: Float, val angle: Float, val tear: String, val tapes: List<Triple<Float, Float, Float>>)
            val slots = listOf(
                Slot(430f, 540f, 600f, 400f, 22f, 22f, -4f, "tblr", listOf(Triple(0f, -232f, 4f))),
                Slot(930f, 660f, 430f, 560f, 18f, 18f, 6f, "tb", listOf(Triple(80f, -295f, -10f))),
                Slot(360f, 1010f, 420f, 300f, 20f, 70f, 7f, "", listOf(Triple(-100f, -170f, 38f), Triple(180f, -140f, -35f))),
                Slot(1000f, 1050f, 340f, 340f, 16f, 16f, -12f, "", listOf(Triple(0f, -190f, 3f))),
                Slot(370f, 1370f, 380f, 260f, 18f, 64f, -5f, "", listOf(Triple(-150f, -150f, 30f))),
            )
            used.forEachIndexed { i, k ->
                val s = slots[i]
                val jx = if (i == 0) 0f else (rng.nextFloat() - 0.5f) * 40f; val jy = (rng.nextFloat() - 0.5f) * 30f
                val ja = (rng.nextFloat() - 0.5f) * 4f
                val pw = s.w + 2 * s.b; val ph = s.h + s.b + s.bottom
                val outline = if (s.tear.isEmpty()) Path().apply { addRect(0f, 0f, pw, ph, Path.Direction.CW) } else torn(pw, ph, 9f, i * 31L + rng.nextInt(1000), s.tear)
                val cx = s.cx + jx; val cy = s.cy + jy; val ang = s.angle + ja
                put(c, cx, cy, pw, ph, ang, 0.5f, 14f, 8f, 12f, outline) { p ->
                    p.save(); p.clipPath(outline)
                    p.drawRect(0f, 0f, pw, ph, fill(cream))
                    photo(p, photos[k], RectF(s.b, s.b, s.b + s.w, s.b + s.h))
                    // white paper fibres where it tore
                    if (s.tear.isNotEmpty()) p.drawPath(outline, stroke(rgb(238, 234, 224), 10f))
                    p.restore()
                    if (s.bottom > s.b + 20) text(p, "cap:$k", words.captions.getOrElse(k) { "" }, pw / 2, s.b + s.h + s.bottom / 2, f.hand, 36f, rgb(60, 50, 44), Anchor.MM, maxW = pw - 30f)
                }
                for ((tx, ty, ta) in s.tapes) {
                    val rad = Math.toRadians(ang.toDouble())
                    val px = cx + (tx * cos(rad) - ty * sin(rad)).toFloat(); val py = cy + (tx * sin(rad) + ty * cos(rad)).toFloat()
                    tape(c, px, py, 170f, 50f, ta, rgb(232, 220, 186), 170, i * 7L + 3)
                }
            }
            // a note on a torn page of a notebook
            val nw = 600f; val nh = 300f; val nx = 890f; val ny = 1440f
            val nOut = torn(nw, nh, 12f, 7, "t")
            put(c, nx, ny, nw, nh, -2f, 0.4f, 14f, 8f, 12f, nOut) { p ->
                p.save(); p.clipPath(nOut)
                p.drawRect(0f, 0f, nw, nh, fill(rgb(250, 248, 240)))
                var y = 40f; while (y < nh) { p.drawLine(0f, y, nw, y, stroke(rgb(150, 180, 210), 1f)); y += 36f }
                p.drawLine(60f, 0f, 60f, nh, stroke(rgb(220, 120, 120), 2f))
                p.restore()
                textBlock(p, "note", words.note, 80f, 44f, nw - 110f, 36f, f.hand, 40f, rgb(40, 60, 120))
            }
            tape(c, nx, ny - nh / 2 + 6, 200f, 54f, 3f, rgb(214, 168, 170), 170, 9)
        }

        // ---- 5) SCATTER: polaroids on black, captions in pen, a gold title --------------------------------------
        fun polaroid(c: Canvas, h: Int) {
            paper(c, W, h, rgb(14, 13, 13), 0.3f, 5)
            val n = used.size; val rows = ceil(n / 3.0).toInt()
            // the others first, the hero last so it lies on top
            val drawOrder = used.indices.drop(1) + 0
            val centres = used.indices.map { i ->
                val row = i / 3; val inRow = min(3, n - row * 3); val col = i % 3
                val x0 = W / 2f - (inRow - 1) * 370f / 2
                (x0 + col * 370f + (rng.nextFloat() - 0.5f) * 80f) to (260f + row * 370f + (rng.nextFloat() - 0.5f) * 60f)
            }
            val angles = used.indices.map { (rng.nextFloat() * 2 - 1) * 14f }
            for (i in drawOrder) {
                val k = used[i]
                val big = if (i == 0) 1.15f else 1f
                val pw = 340f * big; val ph = 410f * big
                val (cx, cy) = centres[i]
                val outline = Path().apply { addRect(0f, 0f, pw, ph, Path.Direction.CW) }
                put(c, cx, cy, pw, ph, -angles[i], 0.75f, 16f, 8f, 14f, outline) { p ->
                    p.drawRect(0f, 0f, pw, ph, fill(rgb(250, 248, 242)))
                    photo(p, photos[k], RectF(20f * big, 20f * big, 320f * big, 320f * big))
                    text(p, "cap:$k", words.captions.getOrElse(k) { "" }, 170f * big, 365f * big, f.hand, 36f * big, rgb(40, 40, 50), Anchor.MM, maxW = pw - 30f)
                }
            }
            val gold = rgb(226, 190, 120)
            val ty = 260f + (rows - 1) * 370f + 205 + 150
            text(c, "title", words.title, W / 2f, ty, f.script, 160f, gold, Anchor.MS, maxW = W - 120f)
            text(c, "sub", words.sub, W / 2f, ty + 40, f.sans, 28f, rgb(200, 180, 140), Anchor.MM, spacing = 0.05f)
        }

        // ---- 6) CUT-OUT: a black and white grid in a frame; the hero's subject steps out over it in colour -----
        fun cutout(c: Canvas, h: Int, cut: Bitmap?) {
            paper(c, W, h, rgb(226, 220, 210), 0.04f, 6)
            val fx0 = 90f; val fy0 = 300f; val fx1 = W - 90f; val fy1 = 1250f
            c.drawRect(fx0, fy0, fx1, fy1, fill(rgb(18, 18, 18)))
            val inner = RectF(fx0 + 24, fy0 + 24, fx1 - 24, fy1 - 24)
            val n = others.size
            val (cols, rows) = when { n <= 1 -> 1 to 1; n == 2 -> 2 to 1; n <= 4 -> 2 to 2; n <= 6 -> 3 to 2; else -> 3 to 3 }
            val g = 8f; val cw = (inner.width() - (cols - 1) * g) / cols; val ch = (inner.height() - (rows - 1) * g) / rows
            val grey = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            others.forEachIndexed { i, k ->
                val x = inner.left + (i % cols) * (cw + g); val y = inner.top + (i / cols) * (ch + g)
                photo(c, photos[k], RectF(x, y, x + cw, y + ch), paint = grey)
            }
            c.drawRect(inner, fill(argb(64, 0, 0, 0)))
            if (cut != null) {
                // the subject stands on the frame's bottom edge and rises out past its top
                val s = min(1010f / cut.height, (W - 140f) / cut.width)
                val w = cut.width * s; val hgt = cut.height * s
                val x = ((W - w) / 2 + 120).coerceIn(30f, W - 30f - w); val y = fy1 - 18 - hgt
                val dst = RectF(x, y, x + w, y + hgt)
                val alpha = cut.extractAlpha()
                c.drawBitmap(alpha, null, RectF(dst.left + 18, dst.top + 10, dst.right + 18, dst.bottom + 10),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { color = argb(153, 0, 0, 0); maskFilter = BlurMaskFilter(22f * 1.7f * scale, BlurMaskFilter.Blur.NORMAL) })
                alpha.recycle()
                c.drawBitmap(cut, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            } else {
                // no outline yet: the hero stands in front as a print
                val pw = 560f; val ph = 700f
                put(c, W / 2f + 120, (fy0 + fy1) / 2, pw, ph, 3f, 0.6f, 22f, 18f, 10f, Path().apply { addRect(0f, 0f, pw, ph, Path.Direction.CW) }) { p ->
                    p.drawRect(0f, 0f, pw, ph, fill(rgb(250, 248, 242)))
                    photo(p, hero, RectF(18f, 18f, pw - 18, ph - 18))
                }
            }
            text(c, "title", words.title, W / 2f - 210, 150f, f.script, 120f, rgb(40, 34, 30), Anchor.MM, maxW = W - 520f)
            text(c, "sub", words.sub.uppercase(), W / 2f - 210, 245f, f.sans, 22f, rgb(130, 118, 104), Anchor.MM, spacing = 0.08f)
        }

        // ---------------------------------------------------------------------------------------------------------
        // The kit: photos, shadows, paper, torn edges, tape, frosting and words
        // ---------------------------------------------------------------------------------------------------------

        /** Photo [b] cover-cropped into [dst], keeping the point (fx, fy) of it in view. */
        fun photo(c: Canvas, b: Bitmap, dst: RectF, fx: Float = 0.5f, fy: Float = 0.5f, paint: Paint? = null) {
            val s = max(dst.width() / b.width, dst.height() / b.height)
            val sw = min(b.width.toFloat(), dst.width() / s); val sh = min(b.height.toFloat(), dst.height() / s)
            val x = (fx * b.width - sw / 2).coerceIn(0f, b.width - sw); val y = (fy * b.height - sh / 2).coerceIn(0f, b.height - sh)
            val src = Rect(x.roundToInt(), y.roundToInt(), (x + sw).roundToInt().coerceAtMost(b.width), (y + sh).roundToInt().coerceAtMost(b.height))
            c.drawBitmap(b, src, dst, paint ?: Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }

        /** A soft shadow of [path], moved by (dx, dy). [blur] is the proofs' Gaussian radius. */
        fun shadow(c: Canvas, path: Path, blur: Float, dx: Float, dy: Float, op: Float) {
            c.save(); c.translate(dx, dy)
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb((op * 255).roundToInt(), 0, 0, 0); maskFilter = BlurMaskFilter(blur * 1.7f * scale, BlurMaskFilter.Blur.NORMAL) })
            c.restore()
        }

        /**
         * A thing [w] × [h] drawn by [content] in its own units, laid on the page centred at (cx, cy) and turned
         * [angle] degrees clockwise, with a shadow of its [outline] that falls the same way for every print.
         */
        fun put(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, angle: Float, op: Float, blur: Float, dx: Float, dy: Float, outline: Path, content: (Canvas) -> Unit) {
            if (op > 0) {
                c.save(); c.translate(cx + dx, cy + dy); c.rotate(angle); c.translate(-w / 2, -h / 2)
                c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb((op * 255).roundToInt(), 0, 0, 0); maskFilter = BlurMaskFilter(blur * 1.7f * scale, BlurMaskFilter.Blur.NORMAL) })
                c.restore()
            }
            c.save(); c.translate(cx, cy); c.rotate(angle); c.translate(-w / 2, -h / 2)
            local = floatArrayOf(cx, cy, w, h, angle)
            content(c)
            local = null
            c.restore()
        }

        /** A sheet of paper: its colour with a soft mottle, drawn from a small map stretched smooth. */
        fun paper(c: Canvas, w: Int, h: Int, col: Int, grain: Float, seed: Long) {
            val r = Random(seed)
            val mw = w / 4; val mh = h / 4
            val bw = w / 60 + 2; val bh = h / 60 + 2
            val big = FloatArray(bw * bh) { (r.nextGaussian() * 0.157).toFloat() }
            val px = IntArray(mw * mh)
            for (y in 0 until mh) for (x in 0 until mw) {
                val gx = x * (bw - 1f) / mw; val gy = y * (bh - 1f) / mh
                val x0 = gx.toInt().coerceAtMost(bw - 2); val y0 = gy.toInt().coerceAtMost(bh - 2); val tx = gx - x0; val ty = gy - y0
                val b = (big[y0 * bw + x0] * (1 - tx) + big[y0 * bw + x0 + 1] * tx) * (1 - ty) + (big[(y0 + 1) * bw + x0] * (1 - tx) + big[(y0 + 1) * bw + x0 + 1] * tx) * ty
                val t = 1 + grain * (r.nextGaussian().toFloat() * 0.157f) + grain * 1.6f * b
                px[y * mw + x] = (0xFF shl 24) or (ch(col shr 16, t) shl 16) or (ch(col shr 8, t) shl 8) or ch(col, t)
            }
            val m = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)
            c.drawBitmap(m, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            m.recycle()
        }
        private fun ch(v: Int, t: Float) = ((v and 255) * t).roundToInt().coerceIn(0, 255)

        /** A torn outline round a [w] × [h] sheet: ragged on the [sides] named (t, b, l, r), straight elsewhere. */
        fun torn(w: Float, h: Float, amount: Float, seed: Long, sides: String): Path {
            val r = Random(seed)
            fun edge(n: Int): FloatArray {
                val e = FloatArray(n); var acc = 0f
                for (i in 0 until n) { acc += r.nextGaussian().toFloat(); e[i] = acc }
                val a = e[0]; val b = e[n - 1]
                for (i in 0 until n) e[i] -= a + (b - a) * i / (n - 1)
                val lo = e.min(); val hi = e.max()
                for (i in 0 until n) e[i] = (e[i] - lo) / (hi - lo + 1e-6f) * amount + r.nextFloat() * amount * 0.35f
                return e
            }
            val step = 4f
            val nx = (w / step).toInt() + 1; val ny = (h / step).toInt() + 1
            val top = if ('t' in sides) edge(nx) else FloatArray(nx)
            val right = if ('r' in sides) edge(ny) else FloatArray(ny)
            val bottom = if ('b' in sides) edge(nx) else FloatArray(nx)
            val left = if ('l' in sides) edge(ny) else FloatArray(ny)
            return Path().apply {
                moveTo(0f + left[0], top[0])
                for (i in 0 until nx) lineTo(min(w, i * step), top[i])
                for (i in 0 until ny) lineTo(w - right[i], min(h, i * step))
                for (i in nx - 1 downTo 0) lineTo(min(w, i * step), h - bottom[i])
                for (i in ny - 1 downTo 0) lineTo(left[i], min(h, i * step))
                close()
            }
        }

        /** A strip of paper tape with torn ends. */
        fun tape(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, angle: Float, col: Int, alpha: Int, seed: Long) {
            val r = Random(seed)
            val p = Path().apply {
                moveTo(0f, 0f)
                var y = 0f; while (y < h) { lineTo(r.nextFloat() * 6f, y); y += 5f }
                lineTo(0f, h); lineTo(w, h)
                y = h; while (y > 0) { lineTo(w - r.nextFloat() * 6f, y); y -= 5f }
                lineTo(w, 0f); close()
            }
            c.save(); c.translate(cx, cy); c.rotate(angle); c.translate(-w / 2, -h / 2)
            shadow(c, p, 3f, 2f, 3f, 0.15f)
            c.drawPath(p, fill((alpha shl 24) or (col and 0xFFFFFF)))
            c.restore()
        }

        /**
         * The band [top]..[bottom] of the page so far, blurred (the proof's Gaussian of 26): halved step by step and
         * doubled back the same way, each step smooth, so no blocks show.
         */
        fun frost(c: Canvas, page: Bitmap, top: Float, bottom: Float) {
            val y0 = (top * scale).roundToInt().coerceIn(0, page.height - 1); val y1 = (bottom * scale).roundToInt().coerceIn(y0 + 1, page.height)
            val band = Bitmap.createBitmap(page, 0, y0, page.width, y1 - y0)
            val steps = max(2, (kotlin.math.ln(26.0 * scale) / kotlin.math.ln(2.0)).roundToInt())
            val sizes = ArrayList<Pair<Int, Int>>()
            var b = band
            repeat(steps) {
                sizes += b.width to b.height
                val n = Bitmap.createScaledBitmap(b, max(1, b.width / 2), max(1, b.height / 2), true)
                if (b !== band) b.recycle(); b = n
            }
            for ((w, h) in sizes.reversed()) { val n = Bitmap.createScaledBitmap(b, w, h, true); b.recycle(); b = n }
            band.recycle()
            c.drawBitmap(b, null, RectF(0f, top, W.toFloat(), bottom), Paint(Paint.FILTER_BITMAP_FLAG))
            b.recycle()
        }

        /**
         * One line of words. [anchor] says which point (x, y) is, as the proofs' PIL anchors do. A line wider than
         * [maxW] is drawn smaller to fit. With an [id], where it lies on the page is kept so a tap there can edit it;
         * Inside a print being laid by [put], the place is turned with the print.
         */
        fun text(c: Canvas, id: String?, s: String, x: Float, y: Float, tf: Typeface, size: Float, col: Int, anchor: Anchor,
                 maxW: Float = 0f, spacing: Float = 0f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = tf; textSize = size; color = col; letterSpacing = spacing }
            val wide = p.measureText(s)
            if (maxW > 0 && wide > maxW) p.textSize = size * maxW / wide
            val fm = p.fontMetrics
            val w = p.measureText(s)
            val left = when (anchor) { Anchor.LA -> x; Anchor.RS -> x - w; Anchor.MS, Anchor.MM -> x - w / 2 }
            val base = when (anchor) { Anchor.LA -> y - fm.ascent; Anchor.RS, Anchor.MS -> y; Anchor.MM -> y - (fm.ascent + fm.descent) / 2 }
            if (s.isNotEmpty()) c.drawText(s, left, base, p)
            if (id != null) {
                // an empty caption still has a place to tap
                val bw = max(w, size * 3); val bl = if (w < bw) left - (bw - w) / 2 else left
                val box = RectF(bl - 10, base + fm.ascent - 6, bl + bw + 10, base + fm.descent + 6)
                spots += TextSpot(id, onPage(box))
            }
        }

        /** Words wrapped into lines [lineH] apart inside [maxW], from (x, top). */
        fun textBlock(c: Canvas, id: String, s: String, x: Float, top: Float, maxW: Float, lineH: Float, tf: Typeface, size: Float, col: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; color = col }
            val lines = ArrayList<String>(); var cur = ""
            for (wd in s.split(' ')) { val t = if (cur.isEmpty()) wd else "$cur $wd"; if (p.measureText(t) > maxW && cur.isNotEmpty()) { lines += cur; cur = wd } else cur = t }
            if (cur.isNotEmpty()) lines += cur
            val fm = p.fontMetrics
            lines.take(6).forEachIndexed { i, l -> c.drawText(l, x, top + i * lineH + lineH - fm.descent, p) }
            spots += TextSpot(id, onPage(RectF(x - 10, top - 6, x + maxW + 10, top + max(1, lines.size).coerceAtMost(6) * lineH + 10)))
        }

        /** The print [put] is drawing (centre x, y, width, height, angle), while it draws; null on the page itself. */
        var local: FloatArray? = null

        /** [box], in the units of whatever is being drawn, as the page box round it: a turned print's corners turned with it. */
        fun onPage(box: RectF): RectF {
            val l = local ?: return box
            val rad = Math.toRadians(l[4].toDouble()); val cs = cos(rad).toFloat(); val sn = sin(rad).toFloat()
            val xs = FloatArray(4); val ys = FloatArray(4)
            listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom).forEachIndexed { i, (px, py) ->
                val dx = px - l[2] / 2; val dy = py - l[3] / 2
                xs[i] = l[0] + dx * cs - dy * sn; ys[i] = l[1] + dx * sn + dy * cs
            }
            return RectF(xs.min(), ys.min(), xs.max(), ys.max())
        }
    }

    private enum class Anchor { LA, RS, MS, MM }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun fill(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
    private fun stroke(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = w }
}
