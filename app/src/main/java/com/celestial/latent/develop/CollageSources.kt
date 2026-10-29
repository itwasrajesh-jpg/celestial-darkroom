package com.celestial.latent.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a collage's pictures come from (98a): each picked frame's developed print, at the size the page needs,
 * aged when the FADE and WEAR dials ask for it, and the hero's subject cut out for the Cut-out page.
 */
object CollageSources {
    /**
     * The picture a picked frame stands for: a photo is itself; a RAW capture is its newest developed print — the clean
     * one, never the _AGED_ copy, since the collage ages the prints itself. Null when there is none (not developed yet).
     */
    fun pictureOf(context: Context, frame: Uri, isRaw: Boolean): Uri? {
        if (!isRaw) return frame
        val stem = Develop.baseNameOf(context, frame)
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND (${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)",
            arrayOf("DCIM/Latent%", "$stem!_%.jpg", "$stem!_%.png"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            var any: Uri? = null
            while (c.moveToNext()) {
                val u = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                if (!c.getString(1).contains("_AGED_")) return u
                if (any == null) any = u
            }
            any
        }
    }

    /** [picture] decoded upright with its long edge at most [longEdge] (a software bitmap, so its pixels can be read). */
    fun load(context: Context, picture: Uri, longEdge: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, picture)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = min(1f, longEdge / max(info.size.width, info.size.height).toFloat())
            if (s < 1f) d.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }.onFailure { android.util.Log.e("Latent", "collage: could not read $picture", it) }.getOrNull()

    /**
     * [picture] aged as a print kept in [story] at the dials' [fade] and [wear], without its margin of paper (the page
     * gives each print its own border). The photo's own story is used when it has one; [seed] keeps its marks.
     */
    fun aged(context: Context, picture: Bitmap, story: String, fade: Float, wear: Float, seed: Long): Bitmap {
        if (fade <= 0f && wear <= 0f) return picture
        val look = AgeLook(story, fade, wear)
        val out = AgedPrint.sample(context, picture, look, seed, emptyList(), max(picture.width, picture.height)) ?: return picture
        val b = AgedPrint.border(picture.width, picture.height, story)
        return Bitmap.createBitmap(out, b, b, (out.width - 2 * b).coerceAtLeast(1), (out.height - 2 * b).coerceAtLeast(1)).also { if (it !== out) out.recycle() }
    }

    /** The story a frame's prints age in: its own AGE look's, else the album's (kept in the dark: they warm). */
    fun storyOf(context: Context, frame: Uri): String = AgeLooks.load(context, frame).takeIf { it.on }?.story ?: "album"

    /**
     * The hero's outline from one tap at (u, v), on a grid with a long side of [grid]: the model's most confident of its
     * three answers, as tap to select starts from. (On the approved cut-out's cat that is the whole cat; the largest
     * answer it was also sure of took in the curtain behind.)
     */
    fun outline(context: Context, e: Select.Embedding, heroW: Int, heroH: Int, u: Float, v: Float, grid: Int = 320): Select.Answer? = runCatching {
        val s = grid / max(heroW, heroH).toFloat()
        val w = max(8, (heroW * s).roundToInt()); val h = max(8, (heroH * s).roundToInt())
        val a = Select.outlines(context, e, listOf(Select.Tap(u, v)), w, h)
        val best = a.scores.indices.maxByOrNull { a.scores[it] } ?: 0
        Select.Answer(listOf(a.masks[best]), floatArrayOf(a.scores[best]), a.width, a.height)
    }.onFailure { android.util.Log.e("Latent", "collage: outline failed", it) }.getOrNull()

    /** [hero] with its alpha set from the outline (stretched smoothly to the hero's size), cropped to the subject. */
    fun cut(hero: Bitmap, a: Select.Answer): Bitmap? {
        val m = a.masks[0]; val mw = a.width; val mh = a.height
        val w = hero.width; val h = hero.height
        val px = IntArray(w * h); hero.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val gy = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, mh - 1f); val j0 = gy.toInt(); val j1 = min(j0 + 1, mh - 1); val ty = gy - j0
            for (x in 0 until w) {
                val gx = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, mw - 1f); val i0 = gx.toInt(); val i1 = min(i0 + 1, mw - 1); val tx = gx - i0
                val a8 = ((m[j0 * mw + i0] * (1 - tx) + m[j0 * mw + i1] * tx) * (1 - ty) + (m[j1 * mw + i0] * (1 - tx) + m[j1 * mw + i1] * tx) * ty)
                val al = (a8 * 255f).roundToInt().coerceIn(0, 255).let { if (it < 10) 0 else it }
                px[y * w + x] = (al shl 24) or (px[y * w + x] and 0xFFFFFF)
                if (al > 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
            }
        }
        if (x1 < 0 || (x1 - x0) * (y1 - y0) < w * h / 200) return null
        val out = Bitmap.createBitmap(x1 - x0 + 1, y1 - y0 + 1, Bitmap.Config.ARGB_8888)
        out.setPixels(px, y0 * w + x0, w, 0, 0, x1 - x0 + 1, y1 - y0 + 1)
        return out
    }

    /**
     * The days of [month] ([year]) the roll has photos from, for the calendar's dots (99a): when each was taken, or
     * when it reached the roll if it doesn't say. Collages and calendars saved to the roll don't count.
     */
    fun shotDays(context: Context, year: Int, month: Int): Set<Int> {
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.of(year, month, 1).atStartOfDay(zone)
        val from = start.toInstant().toEpochMilli(); val to = start.plusMonths(1).toInstant().toEpochMilli()
        val days = HashSet<Int>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ? AND " +
                    "((${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
                    "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0) AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?))",
                arrayOf("DCIM/Latent%", "LATENT_COLLAGE-%", "$from", "$to", "${from / 1000}", "${to / 1000}"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val ms = c.getLong(0).takeIf { it > 0 } ?: (c.getLong(1) * 1000)
                    val d = java.time.Instant.ofEpochMilli(ms).atZone(zone)
                    if (d.year == year && d.monthValue == month) days += d.dayOfMonth
                }
            }
        }.onFailure { android.util.Log.e("Latent", "calendar: could not read the roll's dates", it) }
        return days
    }

    /** Saves the collage to the roll as a new photo. */
    fun save(context: Context, page: Bitmap): Uri {
        val bytes = java.io.ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        return Develop.saveCollage(context, bytes)
    }
}
