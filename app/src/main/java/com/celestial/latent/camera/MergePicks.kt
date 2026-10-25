package com.celestial.latent.camera

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The merge editor's picks (86d, 86f): areas of an HDR bracket that each look as bright as their
 * chosen frame, blended into one picture along its own edges ([exposureMap]). Everywhere else the
 * merge decides.
 *
 * An area is a soft mask on a small grid ([GRID] cells across the long side) in the picture's upright
 * coordinates, the same as the previews in the roll. It is made of two layers: the tap-to-select
 * outline and the brush (+ paints in, - erases), added and kept within 0..1, then feathered so the
 * hand-over has a soft edge. Where areas overlap, the later one is on top.
 *
 * Kept with the roll in the app's own storage (`files/merge-picks/<roll name>.picks`), so reopening
 * the editor shows them again; deleting the roll deletes them. Proven in Python first
 * (exposure-stack/86f/scripts).
 */
object MergePicks {
    /** Cells across the picture's long side. */
    const val GRID = 256
    /** Feather of the hand-over, in cells (about 0.6% of the long side, plus the bilinear spread). */
    const val FEATHER = 1.5f

    /** One tap of tap-to-select, kept so more taps can be added later. */
    class Tap(val u: Float, val v: Float, val add: Boolean)

    /** An area: which frame it comes from (-1 = AUTO, the merge decides), and its two mask layers. */
    class Area(var frame: Int, val gw: Int, val gh: Int,
               val select: FloatArray = FloatArray(gw * gh), val paint: FloatArray = FloatArray(gw * gh),
               var taps: List<Tap> = emptyList(), var level: Int = 0) {
        fun copy() = Area(frame, gw, gh, select.copyOf(), paint.copyOf(), taps, level)
        /** Both layers together, 0..1, not yet feathered. */
        fun hard() = FloatArray(gw * gh) { (select[it] + paint[it]).coerceIn(0f, 1f) }
        /** The mask used: [hard], feathered. */
        fun mask(): FloatArray = BurstMerge.blur(hard(), gw, gh, FEATHER).also { m -> for (i in m.indices) m[i] = m[i].coerceIn(0f, 1f) }
        val isEmpty: Boolean get() = select.all { it <= 0f } && paint.all { it <= 0f }
    }

    /** One area as the merge uses it: frame and feathered mask. */
    class Pick(val frame: Int, val mask: FloatArray, val gw: Int, val gh: Int)

    /** The grid for a picture of this upright shape. */
    fun grid(width: Int, height: Int): Pair<Int, Int> =
        if (width >= height) Pair(GRID, maxOf(2, Math.round(GRID.toFloat() * height / width)))
        else Pair(maxOf(2, Math.round(GRID.toFloat() * width / height)), GRID)

    /** The areas that take part in a merge: a frame chosen and something selected. */
    fun picks(areas: List<Area>, frames: Int): List<Pick> =
        areas.filter { it.frame in 0 until frames && !it.isEmpty }.map { Pick(it.frame, it.mask(), it.gw, it.gh) }

    /**
     * Later areas sit on top: each keeps only what no later area covers. Returns the per-area shares
     * and how much of each cell is picked at all (P); the merge's own result fills the rest (1 - P).
     */
    fun shares(masks: List<FloatArray>): Pair<List<FloatArray>, FloatArray> {
        val n = masks.firstOrNull()?.size ?: 0
        val rem = FloatArray(n) { 1f }
        val out = arrayOfNulls<FloatArray>(masks.size)
        for (a in masks.indices.reversed()) {
            val m = masks[a]
            out[a] = FloatArray(n) { i -> (m[i] * rem[i]).also { rem[i] -= it } }
        }
        return Pair(out.map { it!! }, FloatArray(n) { 1f - rem[it] })
    }

    /**
     * A grid (upright picture coordinates) sampled at every pixel of a picture [pw] x [ph] as stored:
     * the sensor's own way round, which for orientation 6 is a quarter turn from upright.
     */
    class Field(private val grid: FloatArray, private val gw: Int, private val gh: Int, private val pw: Int, private val ph: Int, private val upright6: Boolean) {
        fun at(x: Int, y: Int): Float {
            val us = (x + 0.5f) / pw; val vs = (y + 0.5f) / ph
            val ud = if (upright6) 1f - vs else us; val vd = if (upright6) us else vs
            val gx = (ud * gw - 0.5f).coerceIn(0f, gw - 1.001f); val gy = (vd * gh - 0.5f).coerceIn(0f, gh - 1.001f)
            val ix = gx.toInt(); val iy = gy.toInt(); val tx = gx - ix; val ty = gy - iy
            val i = iy * gw + ix
            val top = grid[i] + (grid[i + 1] - grid[i]) * tx
            val bot = grid[i + gw] + (grid[i + gw + 1] - grid[i + gw]) * tx
            return top + (bot - top) * ty
        }
    }

    // ---- the live preview ---------------------------------------------------------------------

    private val LIN = FloatArray(256) { val v = it / 255f; if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() }
    private val SRGB = IntArray(4096) { i -> val v = i / 4095f; (255f * (if (v <= 0.0031308f) 12.92f * v else 1.055f * v.toDouble().pow(1 / 2.4).toFloat() - 0.055f) + 0.5f).toInt().coerceIn(0, 255) }

    /** Display: straight to 0.6, then a smooth roll-off to 1 (the merge's highlights sit far above white). */
    fun shoulder(v: Float) = if (v <= 0.6f) v else 0.6f + 0.4f * (1f - exp(-(v - 0.6f) / 0.4f))

    /**
     * The merge from the roll's 8-bit previews (all the same size, ARGB, gain 1 so true exposure), at
     * the brightest frame's level, as the darkroom develops a merged bracket. Same weights as
     * [BurstMerge.bracket]: light gathered x a clip hat, the darkest frame always a little, and per 16 px
     * tile a frame that disagrees with the middle one (something moved) fades out. Not lined up (the
     * full merge lines up): a bracket shot by hand may look a little soft here. Linear RGB out, 3 per pixel.
     */
    fun previewAuto(frames: List<IntArray>, w: Int, h: Int, exposure: DoubleArray): FloatArray {
        val n = frames.size
        val eD = exposure.minOrNull()!!; val eB = exposure.maxOrNull()!!
        val dark = exposure.indices.minByOrNull { exposure[it] }!!
        val mid = sqrt(eB * eD); val ref = exposure.indices.minByOrNull { abs(ln(exposure[it] / mid)) }!!
        val sum = FloatArray(w * h * 3); val wsum = FloatArray(w * h)
        val t = 16; val tx = w / t; val ty = h / t
        for (i in 0 until n) {
            val px = frames[i]
            val toB = (eB / exposure[i]).toFloat(); val light = (exposure[i] / eD).toFloat()
            var tw: FloatArray? = null
            if (i != ref && tx >= 2 && ty >= 2) {
                val toRef = (exposure[ref] / exposure[i]).toFloat(); val cap = minOf(1f, toRef)
                val rp = frames[ref]
                val d = FloatArray(tx * ty)
                for (j in 0 until ty) for (k in 0 until tx) {
                    var s = 0f
                    for (y in j * t until (j + 1) * t) for (x in k * t until (k + 1) * t) {
                        val p = y * w + x
                        val a = sqrt(LIN[(rp[p] shr 8) and 0xFF].coerceIn(0f, cap)); val b = sqrt((LIN[(px[p] shr 8) and 0xFF] * toRef).coerceIn(0f, cap))
                        s += abs(a - b)
                    }
                    d[j * tx + k] = s / (t * t)
                }
                val typical = d.sorted()[d.size / 2]
                val tws = FloatArray(tx * ty) { (2f - (d[it] / (2.5f * typical + 1e-6f) - 1f).coerceIn(0f, 1f) * 2f).coerceIn(0f, 2f) / 2f }
                if (i != dark && tws.count { it > 0.5f } < tws.size / 4) tws.fill(0f)
                tw = tws
            }
            for (y in 0 until h) {
                val fy = ((y + 0.5f) / t - 0.5f).coerceIn(0f, ty - 1f); val j0 = fy.toInt().coerceAtMost(ty - 2).coerceAtLeast(0); val vy = fy - j0
                for (x in 0 until w) {
                    val p = y * w + x; val c = px[p]
                    val r = LIN[(c shr 16) and 0xFF]; val g = LIN[(c shr 8) and 0xFF]; val b = LIN[c and 0xFF]
                    val hat = if (i == dark) 1f else ((0.95f - maxOf(r, maxOf(g, b))) / 0.15f).coerceIn(0f, 1f)
                    var wt = hat * light
                    if (tw != null) {
                        val fx = ((x + 0.5f) / t - 0.5f).coerceIn(0f, tx - 1f); val k0 = fx.toInt().coerceAtMost(tx - 2).coerceAtLeast(0); val vx = fx - k0
                        val i0 = j0 * tx + k0
                        val top = tw[i0] + (tw[i0 + 1] - tw[i0]) * vx; val bot = tw[i0 + tx] + (tw[i0 + tx + 1] - tw[i0 + tx]) * vx
                        wt *= top + (bot - top) * vy
                    }
                    if (i == dark) wt = maxOf(wt, 0.01f)
                    if (wt > 0f) { wsum[p] += wt; val q = 3 * p; sum[q] += wt * r * toB; sum[q + 1] += wt * g * toB; sum[q + 2] += wt * b * toB }
                }
            }
        }
        for (p in 0 until w * h) { val k = 1f / wsum[p].coerceAtLeast(1e-6f); val q = 3 * p; sum[q] *= k; sum[q + 1] *= k; sum[q + 2] *= k }
        return sum
    }

    /** [auto] with the picks applied ([exposureMap] on the previews, as the full merge does), ready to show (ARGB). */
    fun previewWithPicks(auto: FloatArray, w: Int, h: Int, picks: List<Pick>, exposure: DoubleArray): IntArray {
        val x = if (picks.isEmpty()) null else exposureMap(FloatArray(w * h) { auto[3 * it + 1] }, w, h, picks, exposure, false)
        val img = IntArray(w * h)
        for (p in 0 until w * h) {
            val q = 3 * p; val g = if (x == null) 1f else exp(x[p])
            img[p] = (0xFF shl 24) or (SRGB[(shoulder(auto[q] * g).coerceIn(0f, 1f) * 4095).toInt()] shl 16) or
                (SRGB[(shoulder(auto[q + 1] * g).coerceIn(0f, 1f) * 4095).toInt()] shl 8) or SRGB[(shoulder(auto[q + 2] * g).coerceIn(0f, 1f) * 4095).toInt()]
        }
        return img
    }

    // ---- the exposure map (86f) ---------------------------------------------------------------

    /** How far the blend reaches either side of an edge, as a share of the long side. */
    const val REACH = 0.03f
    /** How strong an edge must be to stop the blend (on log brightness): larger = smoother, less edge-bound. */
    const val EDGE_EPS = 0.25f

    /**
     * Each area as the brightness of its frame, for the whole picture: per pixel, the log of the gain
     * to put on the merge (which is at the brightest frame's level, so an area of frame f is ln(its light
     * / the brightest's), the rest 0). Rough areas are fine: the map is smoothed by a guided filter (He,
     * Sun & Tang 2010) led by the picture's own log brightness [lum] (w x h, at the merge's develop
     * level), so a change of brightness happens at the picture's real edges (horizon, a building's
     * outline) and fades smoothly across flat parts. Worked out at about 256 px across, as in the
     * "fast guided filter", then a light blur. Proven in Python first (exposure-stack/86f/scripts):
     * sky, building, ground and a lamp from four frames, no patches, no halos.
     */
    fun exposureMap(lum: FloatArray, w: Int, h: Int, picks: List<Pick>, exposure: DoubleArray, upright6: Boolean): FloatArray {
        val eB = exposure.maxOrNull()!!
        val gw = picks[0].gw; val gh = picks[0].gh
        val inside = shares(picks.map { it.mask }).first
        // the target, sharp: each area's own level where it is, the merge's (0) elsewhere
        val grid = FloatArray(gw * gh)
        for ((a, pk) in picks.withIndex()) { val l = ln((exposure[pk.frame] / eB).toFloat()); val s = inside[a]; for (c in grid.indices) grid[c] += s[c] * l }
        var lo = 0f; for (v in grid) lo = minOf(lo, v)
        val target = Field(grid, gw, gh, w, h, upright6)
        val lg = FloatArray(w * h) { ln(maxOf(lum[it], 1e-4f)) }
        // small: box averages of f x f blocks
        val f = maxOf(1, maxOf(w, h) / 256); val sw = maxOf(2, w / f); val sh = maxOf(2, h / f)
        val iS = FloatArray(sw * sh); val pS = FloatArray(sw * sh)
        for (j in 0 until sh) for (i in 0 until sw) {
            var a = 0f; var b = 0f; var n = 0
            for (y in j * f until minOf(h, (j + 1) * f)) for (x in i * f until minOf(w, (i + 1) * f)) { a += lg[y * w + x]; b += target.at(x, y); n++ }
            iS[j * sw + i] = a / maxOf(n, 1); pS[j * sw + i] = b / maxOf(n, 1)
        }
        val r = maxOf(1, Math.round(REACH * maxOf(sw, sh)))
        val mI = box(iS, sw, sh, r); val mP = box(pS, sw, sh, r)
        val cIP = box(FloatArray(iS.size) { iS[it] * pS[it] }, sw, sh, r); val vI = box(FloatArray(iS.size) { iS[it] * iS[it] }, sw, sh, r)
        val ca = FloatArray(iS.size) { (cIP[it] - mI[it] * mP[it]) / (vI[it] - mI[it] * mI[it] + EDGE_EPS) }
        val cb = FloatArray(iS.size) { mP[it] - ca[it] * mI[it] }
        val fa = Field(box(ca, sw, sh, r), sw, sh, w, h, false); val fb = Field(box(cb, sw, sh, r), sw, sh, w, h, false)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { val p = y * w + x; out[p] = (fa.at(x, y) * lg[p] + fb.at(x, y)).coerceIn(lo, 0f) }
        return BurstMerge.blur(out, w, h, 1.5f)
    }

    /** Mean over a (2r+1)^2 square, edges repeated. */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h); val n = 2 * r + 1
        for (y in 0 until h) { var s = 0f; val row = y * w
            for (k in -r..r) s += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[row + x] = s / n; s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)] } }
        for (x in 0 until w) { var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { out[y * w + x] = s / n; s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x] } }
        return out
    }

    // ---- kept with the roll -----------------------------------------------------------------

    private val MAGIC = "LPICKS01".toByteArray(Charsets.US_ASCII)

    fun file(filesDir: File, rollName: String) = File(File(filesDir, "merge-picks").apply { mkdirs() }, "${rollName.substringBeforeLast('.')}.picks")

    fun save(filesDir: File, rollName: String, areas: List<Area>) {
        val f = file(filesDir, rollName)
        if (areas.isEmpty()) { f.delete(); return }
        val tmp = File(f.parentFile, f.name + ".part")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.write(MAGIC); out.writeInt(areas.size)
            for (a in areas) {
                out.writeInt(a.frame); out.writeInt(a.gw); out.writeInt(a.gh); out.writeInt(a.level)
                out.writeInt(a.taps.size); for (t in a.taps) { out.writeFloat(t.u); out.writeFloat(t.v); out.writeBoolean(t.add) }
                // both layers to 1/127 steps: plenty for a mask that is feathered anyway
                out.write(ByteArray(a.select.size) { (a.select[it].coerceIn(0f, 1f) * 127f).let { v -> Math.round(v) }.toByte() })
                out.write(ByteArray(a.paint.size) { (a.paint[it].coerceIn(-1f, 1f) * 127f).let { v -> Math.round(v) }.toByte() })
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The areas kept for this roll, or none. Areas made for another picture shape are dropped. */
    fun load(filesDir: File, rollName: String, gw: Int, gh: Int): List<Area> = runCatching {
        val f = file(filesDir, rollName); if (!f.exists()) return emptyList()
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val m = ByteArray(8); inp.readFully(m); require(m.contentEquals(MAGIC))
            List(inp.readInt()) {
                val frame = inp.readInt(); val w = inp.readInt(); val h = inp.readInt(); val level = inp.readInt()
                val taps = List(inp.readInt()) { Tap(inp.readFloat(), inp.readFloat(), inp.readBoolean()) }
                val s = ByteArray(w * h).also { inp.readFully(it) }; val p = ByteArray(w * h).also { inp.readFully(it) }
                Area(frame, w, h, FloatArray(w * h) { s[it] / 127f }, FloatArray(w * h) { p[it] / 127f }, taps, level)
            }.filter { it.gw == gw && it.gh == gh }
        }
    }.getOrDefault(emptyList())

    fun delete(filesDir: File, rollName: String) { runCatching { file(filesDir, rollName).delete() } }
}
