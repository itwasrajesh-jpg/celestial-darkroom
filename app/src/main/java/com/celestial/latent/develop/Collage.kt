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
 *
 * 103a adds ten more, from the approved pages in calendar/designs-2 (Celestial's own Google references): Heart,
 * Diamonds, Circles, Side column, Slices, Mosaic, Number, Letter, Paint splash and Photo dump. The ones made of many
 * small tiles (Heart, Mosaic, Number, Letter) fill every tile, repeating photos when there are fewer than tiles,
 * as the approved pages do.
 */
object Collage {
    const val W = 1240
    const val MAX_PHOTOS = 13
    val LAYOUTS = listOf("grid", "hexagon", "strip", "scrapbook", "polaroid", "cutout",
        "heart", "diamond", "circles", "side", "slices", "mosaic", "number", "letter", "splash", "dump")
    val LABELS = mapOf("grid" to "Grid", "hexagon" to "Hexagon", "strip" to "Film strip", "scrapbook" to "Scrapbook",
        "polaroid" to "Polaroid", "cutout" to "Cut-out", "heart" to "Heart", "diamond" to "Diamonds", "circles" to "Circles",
        "side" to "Side column", "slices" to "Slices", "mosaic" to "Mosaic", "number" to "Number", "letter" to "Letter",
        "splash" to "Paint splash", "dump" to "Photo dump")
    /** How many photos a layout uses at most (the hero included). Slices cuts the hero alone into strips, as approved. */
    val CAP = mapOf("grid" to 13, "hexagon" to 13, "strip" to 7, "scrapbook" to 5, "polaroid" to 12, "cutout" to 10,
        "heart" to 13, "diamond" to 11, "circles" to 13, "side" to 6, "slices" to 1, "mosaic" to 13, "number" to 13,
        "letter" to 13, "splash" to 4, "dump" to 6)
    /** The layouts with a caption under each print. */
    val CAPTIONED = setOf("scrapbook", "polaroid", "circles")
    /** The layouts drawn round a big number or letter, typed by tapping it ([Words.big]). */
    val BIG = setOf("number", "letter")
    /** On a calendar page: the scrapbook's notebook holds the month, and its fifth print would lie under the pen note. */
    val CALENDAR_CAP = CAP + ("scrapbook" to 4)

    class Fonts(val script: Typeface, val hand: Typeface, val serifItalic: Typeface, val sans: Typeface, val serif: Typeface)
    private var fontCache: Fonts? = null
    fun fonts(context: Context): Fonts = fontCache ?: Fonts(
        Typeface.createFromAsset(context.assets, "collage/dancing_script.ttf"),
        Typeface.createFromAsset(context.assets, "collage/caveat.ttf"),
        Typeface.createFromAsset(context.assets, "collage/playfair_italic.ttf"),
        Typeface.create("sans-serif-medium", Typeface.NORMAL),
        Typeface.createFromAsset(context.assets, "collage/playfair.ttf"),
    ).also { fontCache = it }

    /**
     * The words on the page: a title, a line under it, a caption per photo (in the photos' order) and a note. [big] is
     * the number or letter that Number and Letter build out of photos (blank: "1", and the title's first letter).
     */
    data class Words(val title: String, val sub: String, val captions: List<String>, val note: String = "", val big: String = "")

    /** Where a piece of text sits on the page (in page units), so a tap there can edit it: "title", "sub", "note", "cap:<i>". */
    class TextSpot(val id: String, val box: RectF)

    class Page(val bitmap: Bitmap, val width: Int, val height: Int, val spots: List<TextSpot>)

    /**
     * A month to print on the page, which makes it a calendar page (99a). [month] is 1..12. [marks] are the days the
     * roll has photos from (a dot under the number); [circle] is the day ringed in pen, and [note] what is written
     * beside it. Each day's place is kept as a [TextSpot] "day:<n>", so a tap on it can circle it.
     */
    data class Month(val year: Int, val month: Int, val marks: Set<Int> = emptySet(), val circle: Int? = null, val note: String = "")

    /** The month's name, "October". */
    fun monthName(month: Int): String = java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

    /** 12 → "12th". */
    fun ordinal(d: Int): String = "$d" + if (d % 100 in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    /** The month's weeks, Monday first, 0 for the days of the months either side. */
    fun weeks(year: Int, month: Int): List<IntArray> {
        val first = java.time.LocalDate.of(year, month, 1)
        val lead = first.dayOfWeek.value - 1
        val days = first.lengthOfMonth()
        val out = ArrayList<IntArray>()
        var d = 1 - lead
        while (d <= days) { out += IntArray(7) { i -> (d + i).takeIf { it in 1..days } ?: 0 }; d += 7 }
        return out
    }

    /**
     * The page for [layout] with [photos] (the hero first), drawn [scale] pixels per page unit.
     * [seed] 0 keeps the photos in the order picked; Shuffle passes another, which reorders the others and moves the
     * loose prints. [cut] is the hero's subject cut out (its alpha the outline), for Cut-out; without it the hero
     * stands in front as a print. With a [month] the page is a calendar page: taller, with the month block in the
     * place the approved calendar pages (calendar/proof) give it.
     */
    fun render(layout: String, photos: List<Bitmap>, words: Words, seed: Long, scale: Float, fonts: Fonts, cut: Bitmap? = null, month: Month? = null): Page {
        val rng = Random(seed * 7919 + 17)
        val order = photos.indices.toMutableList()
        if (seed != 0L) { val rest = order.drop(1).toMutableList(); java.util.Collections.shuffle(rest, rng); order.clear(); order += 0; order += rest }
        val used = order.take(if (month != null) CALENDAR_CAP.getValue(layout) else CAP.getValue(layout))
        val d = Draw(photos, used, words, rng, fonts, month)
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
            "heart" -> d.heart(c, h)
            "diamond" -> d.diamonds(c, h)
            "circles" -> d.circles(c, h)
            "side" -> d.side(c, h)
            "slices" -> d.slices(c, h)
            "mosaic" -> d.mosaic(c, h)
            "number", "letter" -> d.big(c, h, layout == "number")
            "splash" -> d.splash(c, h)
            "dump" -> d.dump(c, h)
            else -> d.grid(c, h)
        }
        return Page(bmp, bw, bh, d.spots)
    }

    // -------------------------------------------------------------------------------------------------------------
    // The pages
    // -------------------------------------------------------------------------------------------------------------

    private class Draw(val photos: List<Bitmap>, val used: List<Int>, val words: Words, val rng: Random, val f: Fonts, val month: Month?) {
        val spots = ArrayList<TextSpot>()
        var scale = 1f
        val hero get() = photos[used[0]]
        val others get() = used.drop(1)

        fun height(layout: String): Int = if (month != null) when (layout) {
            // calendar pages: A4 (1754) as the proofs, except where the page grows with its photos
            "grid" -> gridPlan().let { it.bottom + 50 + 536 }
            "polaroid" -> { val rows = ceil(used.size / 3.0).toInt(); 260 + (rows - 1) * 370 + 355 + 414 }
            "mosaic" -> mosaicPlan().bottom + 12 + 480
            else -> 1754
        } else when (layout) {
            "grid" -> gridPlan().let { it.bottom + 50 + 290 }
            "hexagon" -> 1290
            "strip" -> 1520
            "scrapbook" -> 1620
            "polaroid" -> { val rows = ceil(used.size / 3.0).toInt(); 260 + (rows - 1) * 370 + 205 + 270 }
            "cutout" -> 1310
            "heart", "side" -> 1754
            "diamond", "splash" -> 1360
            "circles" -> 1310
            "slices" -> 1420
            "mosaic" -> mosaicPlan().bottom + 210
            "number" -> 1230
            "letter" -> 1310
            "dump" -> 1420
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
            text(c, "title", words.title, 70f, y0 + 10, f.script, 150f, rgb(46, 40, 34), Anchor.LA, maxW = if (month != null) 545f else W - 140f)
            text(c, "sub", words.sub, 78f, y0 + 205, f.sans, 26f, rgb(150, 136, 118), Anchor.LA, spacing = 0.08f)
            if (month != null) monthBlock(c, RectF(640f, y0, W - 70f, y0 + 466), rgb(46, 40, 34), rgb(196, 92, 58), noteX = 615f, noteSize = 40f, dated = true)
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
            // on a calendar page the honeycomb and the title keep the collage's 1290 and the month goes below
            val area = if (month != null) 1290 else h
            val avail = area - 2 * 70f
            val r = min(220f, (avail / ((if (n > 1) rows + 0.5f else rows.toFloat()) * 1.0f) - 10f) / sqrt(3f))
            val hh = sqrt(3f) * r
            val colH = rows * (hh + 10) + (if (n > 1) (hh + 10) / 2 else 0f) - 10
            val top = (area - colH) / 2 - 10
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
            text(c, "title", words.title, W - 70f, area - 140f, f.script, 150f, gold, Anchor.RS, maxW = W - 520f)
            text(c, "sub", words.sub.uppercase(), W - 76f, area - 100f, f.sans, 24f, rgb(200, 190, 170), Anchor.RS, spacing = 0.08f)
            if (month != null) monthBlock(c, RectF(560f, 1250f, W - 70f, h - 80f), rgb(236, 232, 222), gold, noteX = 545f, noteSize = 38f)
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
            val bandTop = if (month != null) 1220f else h - 300f
            frost(c, page, bandTop, h.toFloat())
            c.drawRect(0f, bandTop, W.toFloat(), h.toFloat(), fill(argb(140, 20, 16, 12)))
            c.drawLine(70f, bandTop, W - 70f, bandTop, stroke(rgb(240, 220, 180), 1f))
            text(c, "title", words.title, 70f, bandTop + 30, f.serifItalic, 120f, rgb(250, 244, 232), Anchor.LA, maxW = W - 140f)
            if (month != null) {
                text(c, "sub", words.sub, 76f, bandTop + 190, f.hand, 44f, rgb(240, 210, 160), Anchor.LA, maxW = 540f)
                monthBlock(c, RectF(640f, 1260f, W - 70f, h - 60f), rgb(250, 244, 232), rgb(240, 180, 90), noteX = 620f, noteSize = 40f)
            } else text(c, "sub", words.sub, 76f, bandTop + 190, f.hand, 44f, rgb(240, 210, 160), Anchor.LA, maxW = W - 140f)
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
            if (month != null) { calendarNotebook(c); return }
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

        /** The scrapbook's calendar: the month written on a bigger torn page of the notebook. */
        fun calendarNotebook(c: Canvas) {
            val nw = 620f; val nh = 560f; val nx = 840f; val ny = 1440f; val ang = -2f
            val nOut = torn(nw, nh, 12f, 7, "t")
            put(c, nx, ny, nw, nh, ang, 0.4f, 14f, 8f, 12f, nOut) { p ->
                p.save(); p.clipPath(nOut)
                p.drawRect(0f, 0f, nw, nh, fill(rgb(250, 248, 240)))
                var y = 40f; while (y < nh) { p.drawLine(0f, y, nw, y, stroke(rgb(150, 180, 210), 1f)); y += 36f }
                p.drawLine(60f, 0f, 60f, nh, stroke(rgb(220, 120, 120), 2f))
                p.restore()
            }
            // the month is written on the page as it lies (the box round the turned page, as the proof)
            val rad = Math.toRadians(2.0)
            val ww = (nw * cos(rad) + nh * sin(rad)).toFloat(); val hh = (nh * cos(rad) + nw * sin(rad)).toFloat()
            val x = nx - ww / 2; val y = ny - hh / 2
            val m = month!!
            text(c, null, "${monthName(m.month)} ${m.year}", x + 80, y + 18, f.hand, 50f, rgb(40, 60, 120), Anchor.LA)
            monthBlock(c, RectF(x + 70, y + 90, x + ww - 30, y + hh - 20), rgb(40, 60, 120), rgb(200, 40, 40), noteX = x - 14, noteSize = 42f,
                num = f.hand, headSize = 34f)
            tape(c, x + ww / 2, y + 6, 200f, 54f, 3f, rgb(214, 168, 170), 170, 9)
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
            if (month != null) monthBlock(c, RectF(340f, ty + 70, W - 170f, ty + 364), rgb(236, 228, 214), gold, noteX = 325f, noteSize = 36f)
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
            if (month != null) monthBlock(c, RectF(330f, 1300f, W - 330f, h - 60f), rgb(40, 34, 30), rgb(190, 60, 50), noteX = 315f, noteSize = 38f, num = f.serif)
        }

        // ---- 103a: the ten pages of calendar/designs-2 --------------------------------------------------------------

        /** The other photos, tile [i] of many: they repeat in turn when there are more tiles than photos. */
        fun tile(i: Int): Bitmap = if (others.isEmpty()) hero else photos[others[i % others.size]]

        /** How much of [r] (page units) lies inside [path]: drawn once into a small mask, a quarter of the page's size. */
        inner class Cover(path: Path, w: Int, h: Int) {
            private val mw = max(1, w / 4); private val mh = max(1, h / 4)
            private val px = IntArray(mw * mh)
            init {
                val b = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                Canvas(b).apply { scale(0.25f, 0.25f); drawPath(path, fill(rgb(255, 255, 255))) }
                b.getPixels(px, 0, mw, 0, 0, mw, mh); b.recycle()
            }
            fun of(r: RectF): Float {
                val x0 = (r.left / 4).toInt().coerceIn(0, mw - 1); val x1 = (r.right / 4).toInt().coerceIn(x0 + 1, mw)
                val y0 = (r.top / 4).toInt().coerceIn(0, mh - 1); val y1 = (r.bottom / 4).toInt().coerceIn(y0 + 1, mh)
                var n = 0
                for (y in y0 until y1) for (x in x0 until x1) if ((px[y * mw + x] ushr 24) > 128) n++
                return n.toFloat() / ((x1 - x0) * (y1 - y0))
            }
        }

        /** A photo in a white-edged window of [path]: the edge [border] wide, a soft shadow under it all. */
        fun windowed(c: Canvas, path: Path, edge: Path?, b: Bitmap, box: RectF, op: Float, blur: Float, dx: Float, dy: Float, fx: Float = 0.5f, fy: Float = 0.5f) {
            shadow(c, edge ?: path, blur, dx, dy, op)
            if (edge != null) c.drawPath(edge, fill(rgb(255, 255, 255)))
            c.save(); c.clipPath(path); photo(c, b, box, fx, fy); c.restore()
        }

        /** A print with a white border [b] round a [w] × [h] photo, laid at (cx, cy) turned [angle] degrees clockwise. */
        fun print(c: Canvas, b: Bitmap, cx: Float, cy: Float, w: Float, h: Float, border: Float, angle: Float, op: Float, blur: Float, dx: Float, dy: Float,
                  col: Int = rgb(252, 250, 246), paint: Paint? = null) {
            val pw = w + 2 * border; val ph = h + 2 * border
            put(c, cx, cy, pw, ph, angle, op, blur, dx, dy, Path().apply { addRect(0f, 0f, pw, ph, Path.Direction.CW) }) { p ->
                p.drawRect(0f, 0f, pw, ph, fill(col)); photo(p, b, RectF(border, border, border + w, border + h), paint = paint)
            }
        }

        // ---- 1) HEART: a grid of photos in a black frame, the hero in a big heart window, wishes in script --------
        fun heart(c: Canvas, h: Int) {
            paper(c, W, h, rgb(252, 250, 246), 0.02f, 21)
            val m = 46f; val gx0 = m + 40; val gy0 = m + 40
            val cols = 5; val rows = 6; val gap = 12f
            val tw = (W - 2 * gx0 - gap * (cols - 1)) / cols
            val gy1 = gy0 + rows * tw + (rows - 1) * gap
            // the heart over the middle 3 × 4 tiles; a tile mostly under it is left out, one it cuts keeps its part outside
            val hw = 3 * tw + 2 * gap + 40; val hh = 4 * tw + 3 * gap - 40
            val hx = gx0 + tw + gap - 20; val hy = gy0 + tw + gap + 30
            val hole = heartPath(hx - 20, hy - 20, hw + 40, hh + 40)
            val cover = Cover(hole, W, h)
            var k = 0
            for (r in 0 until rows) for (col in 0 until cols) {
                val x = gx0 + col * (tw + gap); val y = gy0 + r * (tw + gap)
                val cell = RectF(x, y, x + tw, y + tw)
                if (cover.of(cell) > 0.55f) continue
                c.save(); c.clipOutPath(hole); photo(c, tile(k++), cell); c.restore()
            }
            windowed(c, heartPath(hx, hy, hw, hh), heartPath(hx - 10, hy - 10, hw + 20, hh + 20), hero, RectF(hx, hy, hx + hw, hy + hh), 0.45f, 16f, 5f, 10f, fx = 0.42f)
            // the bottom: the month to the left with a heart on its day, the wishes to the right (centred without a month)
            val by = gy1 + 40; val ink = rgb(60, 50, 50); val red = rgb(190, 40, 60)
            val mo = month
            val rx = if (mo != null) W - m - 60 else W / 2f
            val a = if (mo != null) Anchor.RM else Anchor.MM
            text(c, "sub", words.sub, rx, by + 20, f.script, 60f, ink, a, maxW = 600f)
            text(c, "title", words.title, rx, by + 115, f.script, 150f, red, a, maxW = 600f)
            text(c, "note", words.note, rx, by + 220, f.hand, 36f, rgb(110, 96, 90), a, maxW = 600f)
            if (mo != null) {
                text(c, null, "${monthName(mo.month).uppercase()} ${mo.year}", gx0 + 4, by - 34, f.sans, 26f, ink, Anchor.LA)
                monthBlock(c, RectF(gx0, by, gx0 + 470, h - m - 50), ink, red, noteX = gx0 + 490, noteSize = 34f, heart = rgb(200, 30, 55), noteRight = true)
            }
            // the frame
            val black = fill(rgb(18, 16, 16))
            c.drawRect(0f, 0f, W.toFloat(), m, black); c.drawRect(0f, h - m, W.toFloat(), h.toFloat(), black)
            c.drawRect(0f, 0f, m, h.toFloat(), black); c.drawRect(W - m, 0f, W.toFloat(), h.toFloat(), black)
        }

        // ---- 2) DIAMONDS: the hero a big diamond in the middle of a diamond lattice, two plain ones as accents -------
        fun diamonds(c: Canvas, h: Int) {
            paper(c, W, h, rgb(246, 246, 244), 0.015f, 51)
            val cx = W / 2f; val cy = 640f; val s = 285f
            windowed(c, diamondPath(cx, cy, 2 * s - 10), diamondPath(cx, cy, 2 * s + 6), hero, RectF(cx - s, cy - s, cx + s, cy + s), 0.35f, 14f, 5f, 9f, fx = 0.4f)
            // the ring of 12 round the hero's 2 × 2: the top and bottom ones plain, the rest photos, nearest first
            val ring = ArrayList<Pair<Float, Float>>()
            for (i in listOf(-1.5f, -0.5f, 0.5f, 1.5f)) for (j in listOf(-1.5f, -0.5f, 0.5f, 1.5f)) if (max(kotlin.math.abs(i), kotlin.math.abs(j)) == 1.5f) ring += i to j
            val plain = mapOf((-1.5f to 1.5f) to rgb(120, 140, 110), (1.5f to -1.5f) to rgb(60, 60, 62))
            for ((ij, col) in plain) {
                val x = cx + (ij.first + ij.second) * s / 2; val y = cy + (ij.second - ij.first) * s / 2
                val p = diamondPath(x, y, s - 40); shadow(c, p, 8f, 3f, 6f, 0.2f); c.drawPath(p, fill(col))
            }
            val slots = ring.filter { it !in plain }.sortedBy { kotlin.math.abs(it.first) + kotlin.math.abs(it.second) }
            others.take(slots.size).forEachIndexed { n, k ->
                val (i, j) = slots[n]; val x = cx + (i + j) * s / 2; val y = cy + (j - i) * s / 2
                windowed(c, diamondPath(x, y, s - 16), diamondPath(x, y, s - 2), photos[k], RectF(x - s / 2, y - s / 2, x + s / 2, y + s / 2), 0.3f, 10f, 4f, 7f)
            }
            val sage = rgb(120, 140, 110)
            text(c, "title", words.title, W / 2f, 1235f, f.serifItalic, 86f, rgb(50, 56, 50), Anchor.MM, maxW = W - 200f)
            text(c, "sub", words.sub.uppercase(), W / 2f, 1300f, f.sans, 22f, sage, Anchor.MM, spacing = 0.08f)
            if (month != null) monthBlock(c, RectF(300f, 1330f, W - 300f, h - 60f), rgb(60, 60, 62), sage, noteX = 285f, noteSize = 38f)
        }

        // ---- 3) CIRCLES: a round hero inside a ring of round photos, each captioned ---------------------------------
        fun circles(c: Canvas, h: Int) {
            paper(c, W, h, rgb(250, 236, 232), 0.02f, 52)
            val cx = W / 2f; val cy = 600f; val r = 400f
            c.drawCircle(cx, cy, r, stroke(rgb(214, 170, 160), 3f))
            windowed(c, Path().apply { addCircle(cx, cy, 260f, Path.Direction.CW) }, Path().apply { addCircle(cx, cy, 272f, Path.Direction.CW) },
                hero, RectF(cx - 260, cy - 260, cx + 260, cy + 260), 0.3f, 16f, 5f, 10f)
            val n = others.size
            // twelve fit round the ring at 150; fewer are bigger, up to 200
            val d = min(200f, (2 * PI * r / max(1, n)).toFloat() * 0.62f)
            others.forEachIndexed { i, k ->
                val a = Math.toRadians(i * 360.0 / n - 90); val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
                windowed(c, Path().apply { addCircle(x, y, d / 2, Path.Direction.CW) }, Path().apply { addCircle(x, y, d / 2 + 7, Path.Direction.CW) },
                    photos[k], RectF(x - d / 2, y - d / 2, x + d / 2, y + d / 2), 0.3f, 8f, 3f, 6f)
                val lr = r + d / 2 + 37
                text(c, "cap:$k", words.captions.getOrElse(k) { "" }, cx + (lr * cos(a)).toFloat(), cy + (lr * sin(a)).toFloat(), f.hand, 34f, rgb(170, 90, 90), Anchor.MM, maxW = 180f)
            }
            text(c, "title", words.title, W / 2f, 1200f, f.script, 96f, rgb(160, 60, 70), Anchor.MM, maxW = W - 160f)
            text(c, "sub", words.sub.uppercase(), W / 2f, 1262f, f.sans, 22f, rgb(176, 126, 120), Anchor.MM, spacing = 0.08f)
            if (month != null) monthBlock(c, RectF(300f, 1290f, W - 300f, h - 60f), rgb(90, 60, 60), rgb(200, 70, 80), noteX = 285f, noteSize = 38f)
        }

        // ---- 4) SIDE COLUMN: the hero fills a black frame, small tilted prints down its side, words over the dark foot
        fun side(c: Canvas, h: Int) {
            c.drawRect(0f, 0f, W.toFloat(), h.toFloat(), fill(rgb(16, 16, 18)))
            val m = 46f; val inner = RectF(m, m, W - m, h - m)
            photo(c, hero, inner, fx = 0.45f, fy = 0.4f)
            c.drawRect(inner, Paint().apply {
                shader = LinearGradient(0f, m + inner.height() * 0.55f, 0f, h - m, 0x00000000, argb(191, 0, 0, 0), Shader.TileMode.CLAMP) })
            others.take(5).forEachIndexed { i, k ->
                val x = W - m - 190 + (if (i % 2 == 1) 18f else -10f); val y = m + 150 + i * 250f
                print(c, photos[k], x, y, 220f, 165f, 9f, if (i % 2 == 1) 5f else -6f, 0.6f, 10f, 5f, 8f)
            }
            val gold = rgb(226, 190, 120)
            val mo = month
            val ty = if (mo != null) h - m - 470 else h - m - 260
            text(c, "sub", words.sub.uppercase(), m + 70, ty, f.sans, 30f, gold, Anchor.LM, maxW = 700f)
            text(c, "title", words.title, m + 60, ty + 90, f.script, 150f, rgb(255, 250, 240), Anchor.LM, maxW = 760f)
            text(c, "note", words.note, W - m - 70, h - m - 60, f.hand, 44f, gold, Anchor.RM, maxW = 500f)
            if (mo != null) monthBlock(c, RectF(m + 70, h - m - 300, m + 560, h - m - 90), rgb(240, 236, 228), gold, noteX = m + 580, noteSize = 38f, heart = rgb(210, 40, 60), noteRight = true)
            c.drawRect(RectF(m - 1.5f, m - 1.5f, W - m + 1.5f, h - m + 1.5f), stroke(rgb(60, 54, 44), 3f))
        }

        // ---- 5) SLICES: the hero cut into slanted strips that step a little up and down ----------------------------
        fun slices(c: Canvas, h: Int) {
            paper(c, W, h, rgb(240, 238, 232), 0.015f, 55)
            val x0 = 80f; val y0 = 80f; val x1 = W - 80f; val y1 = 1200f; val n = 5; val gap = 14f; val sk = 160f
            for (i in 0 until n) {
                val a = x0 + (x1 - x0 + sk) * i / n - sk; val b = x0 + (x1 - x0 + sk) * (i + 1) / n - sk
                val off = (i - 2) * 22f
                // the strip, cut square at the page's margins
                val pts = clipX(listOf(a + sk + gap / 2 to y0, b + sk - gap / 2 to y0, b - gap / 2 to y1, a + gap / 2 to y1), x0, x1)
                val p = Path().apply { pts.forEachIndexed { j, (x, y) -> if (j == 0) moveTo(x, y + off) else lineTo(x, y + off) }; close() }
                shadow(c, p, 10f, 4f, 8f, 0.3f)
                c.save(); c.clipPath(p); photo(c, hero, RectF(x0 - sk, y0 + off, x1 + sk, y1 + off)); c.restore()
            }
            text(c, "title", words.title, 80f, 1290f, f.serifItalic, 100f, rgb(50, 40, 34), Anchor.LM, maxW = if (month != null) 540f else W - 160f)
            text(c, "sub", words.sub.uppercase(), 86f, 1365f, f.sans, 24f, rgb(170, 100, 50), Anchor.LM, spacing = 0.06f, maxW = if (month != null) 530f else W - 170f)
            if (month != null) monthBlock(c, RectF(640f, 1240f, W - 80f, h - 60f), rgb(60, 52, 46), rgb(190, 110, 50), noteX = 625f, noteSize = 36f)
        }

        /** The convex polygon [pts] with what lies left of [lo] or right of [hi] cut away. */
        fun clipX(pts: List<Pair<Float, Float>>, lo: Float, hi: Float): List<Pair<Float, Float>> {
            fun cut(ps: List<Pair<Float, Float>>, inside: (Float) -> Boolean, at: Float): List<Pair<Float, Float>> {
                val out = ArrayList<Pair<Float, Float>>()
                for (i in ps.indices) {
                    val p = ps[i]; val q = ps[(i + 1) % ps.size]
                    if (inside(p.first)) out += p
                    if (inside(p.first) != inside(q.first)) { val t = (at - p.first) / (q.first - p.first); out += at to p.second + t * (q.second - p.second) }
                }
                return out
            }
            return cut(cut(pts, { it >= lo }, lo), { it <= hi }, hi)
        }

        // ---- 6) MOSAIC: a few big photos with small ones packed round them, on white ------------------------------
        class Placed(val r: Int, val c: Int, val s: Int)
        class MosaicPlan(val u: Float, val tiles: List<Placed>, val rows: Int, val bottom: Int)
        fun mosaicPlan(): MosaicPlan {
            val m = 50f; val g = 8f; val cols = 6; val u = (W - 2 * m + g) / cols
            val n = used.size
            val sizes = used.indices.map { when (it) { 0 -> 3; 1, 2 -> 2; else -> 1 } }
            val rows = max(3, ceil(sizes.sumOf { it * it } / cols.toDouble()).toInt())
            val taken = Array(rows) { BooleanArray(cols) }
            val tiles = ArrayList<Placed>()
            fun fits(r: Int, c: Int, s: Int) = r + s <= rows && c + s <= cols && (r until r + s).all { rr -> (c until c + s).none { taken[rr][it] } }
            fun take(r: Int, c: Int, s: Int) { for (rr in r until r + s) for (cc in c until c + s) taken[rr][cc] = true; tiles += Placed(r, c, s) }
            // the hero top middle, the next two big ones lower down either side, the rest packed in reading order
            if (fits(0, 2, 3)) take(0, 2, 3)
            for ((i, s) in sizes.withIndex()) if (i > 0) {
                val want = if (i == 1) 3 to 0 else if (i == 2) 3 to 4 else -1 to -1
                if (want.first >= 0 && fits(want.first, want.second, s)) { take(want.first, want.second, s); continue }
                var done = false
                for (r in 0 until rows) { for (cc in 0 until cols) if (!done && fits(r, cc, s)) { take(r, cc, s); done = true }; if (done) break }
                if (!done) for (r in 0 until rows) for (cc in 0 until cols) if (!done && fits(r, cc, 1)) { take(r, cc, 1); done = true }
            }
            // the rest of the grid filled with the photos again
            for (r in 0 until rows) for (cc in 0 until cols) if (!taken[r][cc]) take(r, cc, 1)
            if (n == 0) return MosaicPlan(u, tiles, rows, (m + rows * u).roundToInt())
            return MosaicPlan(u, tiles, rows, (m + rows * u - g).roundToInt())
        }
        fun mosaic(c: Canvas, h: Int) {
            c.drawRect(0f, 0f, W.toFloat(), h.toFloat(), fill(rgb(255, 255, 255)))
            val m = 50f; val g = 8f
            val p = mosaicPlan()
            p.tiles.forEachIndexed { i, t ->
                val x = m + t.c * p.u; val y = m + t.r * p.u; val w = t.s * p.u - g
                photo(c, photos[used[i % used.size]], RectF(x, y, x + w, y + w))
            }
            val top = p.bottom + 12f
            text(c, "title", words.title, m, top + 60, f.script, 110f, rgb(30, 30, 30), Anchor.LM, maxW = if (month != null) 560f else W - 2 * m)
            text(c, "sub", words.sub.uppercase(), m + 6, top + 140, f.sans, 24f, rgb(140, 140, 140), Anchor.LM, maxW = if (month != null) 550f else W - 2 * m)
            if (month != null) monthBlock(c, RectF(640f, top, W - m, h - 40f), rgb(40, 40, 40), rgb(200, 60, 60), noteX = 625f, noteSize = 34f)
        }

        // ---- 7) NUMBER and LETTER: an age or an initial made of small square photos --------------------------------
        fun big(c: Canvas, h: Int, number: Boolean) {
            val glyph = words.big.trim().ifEmpty { if (number) "1" else words.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A" }.take(if (number) 3 else 2)
            if (number) paper(c, W, h, rgb(236, 230, 222), 0.03f, 31) else paper(c, W, h, rgb(24, 26, 34), 0.12f, 32)
            // the figure: as big as fits a box round its place on the approved pages
            val tf = if (number) Typeface.create("sans-serif", Typeface.BOLD) else f.serif
            val box = if (number) RectF(160f, 190f, 1040f, 1110f) else RectF(230f, 260f, W - 230f, 1000f)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 1000f }
            val raw = Path(); tp.getTextPath(glyph, 0, glyph.length, 0f, 0f, raw)
            val b = RectF(); raw.computeBounds(b, true)
            val k = min(box.width() / max(1f, b.width()), box.height() / max(1f, b.height()))
            tp.textSize = 1000f * k
            val path = Path(); tp.getTextPath(glyph, 0, glyph.length, 0f, 0f, path)
            path.computeBounds(b, true)
            path.offset(box.centerX() - b.centerX(), box.centerY() - b.centerY()); path.computeBounds(b, true)
            // under the tiles the figure is white, so the gaps between them and an edge round it read as one shape
            shadow(c, path, if (number) 18f else 20f, 8f, 14f, if (number) 0.35f else 0.6f)
            c.drawPath(path, stroke(rgb(255, 255, 255), 8f)); c.drawPath(path, fill(rgb(255, 255, 255)))
            val t = if (number) 64f else 52f; val gap = if (number) 6f else 5f
            val cover = Cover(path, W, h)
            c.save(); c.clipPath(path)
            var n = 0
            var y = b.top - 10
            while (y < b.bottom) {
                var x = b.left - 10
                while (x < b.right) {
                    val cell = RectF(x, y, x + t, y + t)
                    if (cover.of(cell) >= 0.08f) {
                        val pick = used[(n * 7 + rng.nextInt(3)) % used.size]; n++
                        photo(c, photos[pick], cell, 0.3f + rng.nextFloat() * 0.4f, 0.3f + rng.nextFloat() * 0.4f)
                    }
                    x += t + gap
                }
                y += t + gap
            }
            c.restore()
            spots += TextSpot("big", onPage(RectF(b)))
            val mo = month
            if (number) {
                text(c, "title", words.title, W - 110f, 230f, f.script, 130f, rgb(120, 60, 50), Anchor.RM, maxW = 520f)
                text(c, "sub", words.sub.uppercase(), W - 116f, 330f, f.sans, 30f, rgb(150, 120, 100), Anchor.RM, maxW = 520f)
                if (mo != null) {
                    if (words.title != monthName(mo.month)) text(c, null, monthName(mo.month), W / 2f, 1255f, f.serifItalic, 70f, rgb(80, 60, 50), Anchor.MM)
                    monthBlock(c, RectF(300f, 1290f, W - 300f, h - 60f), rgb(80, 64, 56), rgb(170, 70, 60), noteX = W - 282f, noteSize = 40f, noteRight = true)
                }
            } else {
                text(c, "title", words.title, W / 2f, 1180f, f.hand, 56f, rgb(236, 210, 150), Anchor.MM, maxW = W - 200f)
                if (mo != null) {
                    text(c, null, "${monthName(mo.month)} ${mo.year}".uppercase(), W / 2f, 1255f, f.sans, 24f, rgb(180, 170, 150), Anchor.MM, spacing = 0.3f)
                    monthBlock(c, RectF(300f, 1290f, W - 300f, h - 60f), rgb(230, 224, 210), rgb(236, 190, 100), noteX = W - 282f, noteSize = 40f, noteRight = true)
                } else text(c, "sub", words.sub.uppercase(), W / 2f, 1255f, f.sans, 24f, rgb(180, 170, 150), Anchor.MM, spacing = 0.3f, maxW = W - 200f)
            }
        }

        // ---- 9) PAINT SPLASH: the hero painted on in a brush splash, prints laid over its edge ---------------------
        fun splash(c: Canvas, h: Int) {
            paper(c, W, h, rgb(243, 236, 224), 0.03f, 41)
            val sw = 1120f; val sh = 900f; val sx = (W - sw) / 2 + 30; val sy = 150f
            val mask = splashMask(560, 450, 7)
            val dst = RectF(sx, sy, sx + sw, sy + sh)
            val alpha = mask.extractAlpha()
            c.drawBitmap(alpha, null, RectF(dst.left + 4, dst.top + 6, dst.right + 4, dst.bottom + 6),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { color = argb(56, 0, 0, 0); maskFilter = BlurMaskFilter(10f * 1.7f * scale, BlurMaskFilter.Blur.NORMAL) })
            alpha.recycle()
            c.saveLayer(dst, null)
            photo(c, hero, dst, fy = 0.55f)
            c.drawBitmap(mask, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            c.restore()
            mask.recycle()
            val spots3 = listOf(Triple(250f, 330f, -8f), Triple(300f, 900f, 6f), Triple(960f, 1000f, -5f))
            others.take(3).forEachIndexed { i, k -> val (x, y, a) = spots3[i]; print(c, photos[k], x, y, 300f, 240f, 14f, a, 0.45f, 12f, 6f, 10f) }
            val red = rgb(190, 60, 60)
            text(c, "title", words.title, W - 110f, 1170f, f.script, 96f, red, Anchor.RM, maxW = 760f)
            text(c, "sub", words.sub.uppercase(), W - 110f, 1290f, f.serif, 76f, rgb(40, 34, 30), Anchor.RM, maxW = 900f)
            val mo = month ?: return
            text(c, null, "${monthName(mo.month)} ${mo.year}".uppercase(), 110f, 1395f, f.sans, 24f, rgb(120, 104, 92), Anchor.LM)
            monthBlock(c, RectF(110f, 1420f, 600f, h - 80f), rgb(60, 52, 46), red, noteX = 620f, noteSize = 44f, noteRight = true)
        }

        /**
         * The splash's shape, [w] × [h], white where the paint is: a soft blob with a wavy edge, dry-brush strokes
         * reaching out with bristly ends, and drops flung off (calendar/designs-2/scripts/designs.py, at half size).
         */
        fun splashMask(w: Int, h: Int, seed: Long): Bitmap {
            val r = Random(seed)
            val cx = w / 2f; val cy = h / 2f
            val ks = (2 until 30).map { k -> Triple(k, (r.nextGaussian() * 0.14 / k.toDouble().pow(0.55)).toFloat(), (r.nextFloat() * 6.3f)) }
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val ang = kotlin.math.atan2(dy, dx); val rad = sqrt((dx / (w * 0.36f)).pow(2) + (dy / (h * 0.34f)).pow(2))
                var e = 0f; for ((k, amp, ph) in ks) e += amp * cos(k * ang + ph)
                if (rad < 1 + e) px[y * w + x] = -1
            }
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out); val white = fill(rgb(255, 255, 255))
            repeat(11) {
                // a stroke: rows of bristles, each starting and stopping at its own place, a few dry ones missing
                val len = (0.55f + 0.40f * r.nextFloat()) * w; val rows = (20 + 35 * r.nextFloat()).toInt()
                val a = (r.nextGaussian() * 14).toFloat()
                val ox = cx + (r.nextGaussian() * w * 0.05).toFloat(); val oy = cy + (r.nextGaussian() * h * 0.10).toFloat()
                val raw = FloatArray(rows) { r.nextFloat() }
                c.save(); c.translate(ox, oy); c.rotate(a); c.translate(-len / 2, -rows / 2f)
                for (i in 0 until rows) {
                    val sm = (raw[max(0, i - 1)] + raw[i] + raw[min(rows - 1, i + 1)]) / 3
                    val st = (0.08f + 0.10f * r.nextFloat()) * len; val en = (0.70f + 0.30f * sm) * len
                    if (r.nextFloat() > 0.12f) c.drawRect(st, i.toFloat(), en, i + 1f, white)
                }
                c.restore()
            }
            repeat(7) {
                // flung drops: a line of shrinking round drops
                val a = r.nextFloat() * 2 * PI.toFloat(); val r0 = 0.95f + 0.2f * r.nextFloat()
                for (j in 0 until 3 + r.nextInt(4)) {
                    val rr = r0 + j * 0.09f; val s = max(1f, (14 - j * 2.5f) * (0.6f + 0.6f * r.nextFloat()) / 2)
                    c.drawCircle(cx + cos(a) * w * 0.33f * rr + (r.nextGaussian() * 2).toFloat(), cy + sin(a) * h * 0.33f * rr + (r.nextGaussian() * 2).toFloat(), s, white)
                }
            }
            repeat(70) {
                val a = r.nextFloat() * 2 * PI.toFloat(); val rr = 1f + 0.45f * r.nextFloat(); val s = (1.2f + 3.8f * r.nextFloat()) / 2
                c.drawCircle(cx + cos(a) * w * 0.36f * rr, cy + sin(a) * h * 0.36f * rr, s, white)
            }
            return out
        }

        // ---- 8) PHOTO DUMP: black and white torn prints on a dark board, tape and chalk doodles -------------------
        fun dump(c: Canvas, h: Int) {
            paper(c, W, h, rgb(28, 28, 30), 0.25f, 58)
            val grey = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            val slots = listOf(Triple(330f, 260f, 5f), Triple(880f, 300f, -6f), Triple(420f, 620f, -4f), Triple(900f, 680f, 7f), Triple(300f, 980f, 3f), Triple(860f, 1030f, -5f))
            used.take(slots.size).forEachIndexed { i, k ->
                val (x, y, a) = slots[i]
                val w = if (i % 2 == 0) 470f else 400f; val hh = if (i % 2 == 0) 340f else 300f
                val pw = w + 28; val ph = hh + 28
                val outline = torn(pw, ph, 18f, i * 3L + 1, "tblr")
                put(c, x, y, pw, ph, a, 0.7f, 14f, 6f, 10f, outline) { p ->
                    p.save(); p.clipPath(outline)
                    p.drawRect(0f, 0f, pw, ph, fill(rgb(240, 238, 232)))
                    photo(p, photos[k], RectF(14f, 14f, 14 + w, 14 + hh), paint = grey)
                    p.drawPath(outline, stroke(rgb(238, 234, 224), 10f))
                    p.restore()
                }
                if (i == 0 || i == 3 || i == 4) tape(c, x - 60 + (i * 37) % 120 + 75, y - hh / 2 - 8, 150f, 44f, (rng.nextFloat() - 0.5f) * 60f, rgb(232, 220, 186), 170, i * 5L)
            }
            // chalk: a heart and a few stars, in white pen
            val chalk = stroke(rgb(235, 232, 225), 4f)
            c.drawPath(heartPath(1030f - 48, 430f, 96f, 88f), chalk)
            for ((sx, sy) in listOf(150f to 470f, 620f to 860f, 1100f to 870f)) for (a in 0 until 360 step 72) {
                val t = Math.toRadians(a.toDouble()); c.drawLine(sx, sy, sx + 22 * cos(t).toFloat(), sy + 22 * sin(t).toFloat(), stroke(rgb(235, 232, 225), 3f))
            }
            put(c, 900f, 1250f, 620f, 170f, -8f, 0f, 0f, 0f, 0f, Path()) { p ->
                text(p, "title", words.title, 310f, 85f, f.script, 110f, rgb(245, 242, 235), Anchor.MM, maxW = 600f)
            }
            val mo = month
            if (mo == null) { text(c, "note", words.note, 900f, 1350f, f.hand, 48f, rgb(230, 228, 220), Anchor.MM, maxW = 640f); return }
            text(c, null, "${monthName(mo.month).take(3)} ${mo.year}".uppercase(), 80f, 1260f, f.sans, 26f, rgb(170, 168, 160), Anchor.LM)
            monthBlock(c, RectF(80f, 1300f, 600f, h - 60f), rgb(230, 228, 220), rgb(240, 200, 110), noteX = 620f, noteSize = 40f, noteRight = true)
            if (mo.note.isBlank() || mo.circle == null) text(c, "note", words.note, 900f, 1590f, f.hand, 48f, rgb(230, 228, 220), Anchor.MM, maxW = 560f)
        }

        /** A heart filling the box (x, y, w, h), its point at the bottom. */
        fun heartPath(x: Float, y: Float, w: Float, h: Float): Path {
            val n = 120
            val xs = FloatArray(n); val ys = FloatArray(n)
            for (i in 0 until n) {
                val t = 2 * PI * i / n
                xs[i] = (16 * sin(t).pow(3)).toFloat(); ys[i] = (-(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))).toFloat()
            }
            val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
            return Path().apply {
                for (i in 0 until n) { val px = x + (xs[i] - x0) / (x1 - x0) * w; val py = y + (ys[i] - y0) / (y1 - y0) * h; if (i == 0) moveTo(px, py) else lineTo(px, py) }
                close()
            }
        }

        /** A diamond (a square on its point) centred at (cx, cy), [diag] corner to corner. */
        fun diamondPath(cx: Float, cy: Float, diag: Float) = Path().apply {
            moveTo(cx, cy - diag / 2); lineTo(cx + diag / 2, cy); lineTo(cx, cy + diag / 2); lineTo(cx - diag / 2, cy); close()
        }

        /**
         * The month in [box]: the weekday letters in [accent], then the days in [ink], a dot under each day with
         * photos, a loose pen ring round the circled day and its note written to the left of the block on the same
         * row, its right end at [noteX] ([dated] puts the day in front: "12th: …"). [num] is the face of the numbers
         * and letters; [headSize] the letters' size when it isn't 0.8 of the numbers'. [heart]: the circled day sits in a
         * filled heart of that colour instead of the ring (103a, Heart and Side column); [noteRight]: the note is written to the right of
         * the block instead, from [noteX].
         */
        fun monthBlock(c: Canvas, box: RectF, ink: Int, accent: Int, noteX: Float, noteSize: Float, dated: Boolean = false,
                       num: Typeface = f.sans, headSize: Float = 0f, heart: Int? = null, noteRight: Boolean = false) {
            val m = month ?: return
            val rows = weeks(m.year, m.month)
            val cw = box.width() / 7; val rh = box.height() / (rows.size + 1)
            val fs = (min(cw, rh) * 0.42f).toInt().toFloat()
            "MTWTFSS".forEachIndexed { i, d ->
                text(c, null, d.toString(), box.left + cw * (i + 0.5f), box.top + rh * 0.5f, num, if (headSize > 0) headSize else (fs * 0.8f).toInt().toFloat(), accent, Anchor.MM)
            }
            var circled: Pair<Float, Float>? = null
            rows.forEachIndexed { r, wk ->
                wk.forEachIndexed { col, d ->
                    if (d == 0) return@forEachIndexed
                    val cx = box.left + cw * (col + 0.5f); val cy = box.top + rh * (r + 1.5f)
                    if (heart != null && d == m.circle) {
                        val s = min(cw, rh) * 0.95f
                        c.drawPath(heartPath(cx - s / 2, cy + 1 - s / 2, s, s * 0.92f), fill(heart))
                        text(c, null, "$d", cx, cy + 1, num, min(cw, rh) * 0.42f, rgb(255, 255, 255), Anchor.MM)
                        circled = cx to cy
                        spots += TextSpot("day:$d", onPage(RectF(cx - cw / 2, cy - rh / 2, cx + cw / 2, cy + rh / 2)))
                        return@forEachIndexed
                    }
                    text(c, null, "$d", cx, cy, num, fs, ink, Anchor.MM)
                    if (d in m.marks) c.drawCircle(cx, cy + fs * 0.62f, 3.5f, fill(accent))
                    if (d == m.circle) {
                        // a loose hand-drawn ring: two passes that don't quite close
                        val rr = fs * 0.95f
                        for ((a0, a1, e) in listOf(Triple(-60f, 300f, 1f), Triple(-20f, 250f, 1.08f))) {
                            c.drawArc(RectF(cx - rr * e * 1.15f, cy - rr * e, cx + rr * e * 1.15f, cy + rr * e * 0.95f), a0, a1 - a0, false,
                                stroke(accent, max(2f, (fs / 9).toInt().toFloat())))
                        }
                        circled = cx to cy
                    }
                    spots += TextSpot("day:$d", onPage(RectF(cx - cw / 2, cy - rh / 2, cx + cw / 2, cy + rh / 2)))
                }
            }
            val at = circled
            if (at != null && m.note.isNotBlank()) {
                val s = if (dated) "${ordinal(m.circle!!)}: ${m.note}" else m.note
                if (noteRight) text(c, "day:${m.circle}", s, noteX, at.second, f.hand, noteSize, accent, Anchor.LM, maxW = W - 40f - noteX)
                else text(c, "day:${m.circle}", s, noteX, at.second, f.hand, noteSize, accent, Anchor.RM, maxW = noteX - 40f)
            }
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
            val left = when (anchor) { Anchor.LA, Anchor.LM -> x; Anchor.RS, Anchor.RM -> x - w; Anchor.MS, Anchor.MM -> x - w / 2 }
            val base = when (anchor) { Anchor.LA -> y - fm.ascent; Anchor.RS, Anchor.MS -> y; Anchor.MM, Anchor.RM, Anchor.LM -> y - (fm.ascent + fm.descent) / 2 }
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

    private enum class Anchor { LA, RS, MS, MM, RM, LM }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun fill(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
    private fun stroke(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = w }
}
