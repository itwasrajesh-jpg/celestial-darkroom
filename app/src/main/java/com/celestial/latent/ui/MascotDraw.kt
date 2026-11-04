package com.celestial.latent.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The darkroom mascot, drawn live (114a). A rule-for-rule port of the approved drawing
 * (mascot/vector3/scripts/mascot3.py, pycairo): every shape is a path, then "furry" paints it with fur
 * tufts on the outline, a lit gradient, a felt texture, soft shade at the bottom, a back-light rim
 * away from the key light and a soft highlight on the lit side. One unit is about the head's half
 * width; the feet stand at (0, 0). The poses that move it are in MascotPose.kt.
 *
 * Kept from the Python on purpose: the random numbers (Python's own Mersenne Twister, same seeds),
 * the 0.011 outline sampling the tufts grow from, and the drawing order. Changed for speed only:
 * tufts and felt curls are batched into a few paths per shape (colours to 1/20 of their range),
 * the finished pieces of each shape are kept in a small cache, and petal sway steps by 0.004 rad.
 */

/** The mascot's own colours per theme (mascot3.py THEMES). */
class MascotPalette(
    val id: String, val accent: Long, val body: Long, val bodyHi: Long, val bodyLo: Long, val rim: Long, val glow: Long,
    val vest: Long, val vestHi: Long, val cap: Long, val capHi: Long, val strap: Long, val stitch: Long,
) {
    companion object {
        val DARKROOM = MascotPalette("darkroom", 0xFAC775, 0xF2711E, 0xFFA05A, 0xB9480C, 0xFFC98A, 0xFF8A3A,
            0x2A231F, 0x4A3E36, 0x2B2521, 0x4C4039, 0x7A4826, 0xC9A27C)
        val MIDNIGHT = MascotPalette("midnight", 0xEDF1F8, 0xF2711E, 0xFFA05A, 0xB9480C, 0xB7CBFF, 0x9FB8FF,
            0x1E263B, 0x3A4866, 0x273248, 0x46557A, 0x536684, 0x98A9C4)
        val CLAY = MascotPalette("clay", 0x5B78E6, 0xF4752A, 0xFFA668, 0xC9531A, 0xFFFFFF, 0x8EA6F7,
            0x45536F, 0x6A7A9C, 0x303D59, 0x55648A, 0x8EA6F7, 0xC9D3EA)
        fun of(t: AppTheme) = when (t) { AppTheme.DARKROOM -> DARKROOM; AppTheme.MIDNIGHT -> MIDNIGHT; AppTheme.CLAY -> CLAY }
    }
}

/* ------------------------------------------------ colour helpers ------------------------------------------------ */

private class Rgb(val r: Double, val g: Double, val b: Double) {
    fun color(a: Double = 1.0) = Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f), a.toFloat().coerceIn(0f, 1f))
}
private fun hexc(h: Long) = Rgb(((h shr 16) and 0xff) / 255.0, ((h shr 8) and 0xff) / 255.0, (h and 0xff) / 255.0)
private fun mix(a: Rgb, b: Rgb, k: Double) = Rgb(a.r + (b.r - a.r) * k, a.g + (b.g - a.g) * k, a.b + (b.b - a.b) * k)
private fun rgba(r: Double, g: Double, b: Double, a: Double) = Rgb(r, g, b).color(a)
private fun Double.f() = toFloat()
private fun o(x: Double, y: Double) = Offset(x.toFloat(), y.toFloat())

private const val LX = -0.55   // the direction the key light comes FROM (up-left)
private const val LY = -0.83

/* ------------------------------------------------ Python's random ------------------------------------------------ */

/** Python's random.Random(seed) for a small int seed: MT19937 seeded by init_by_array, random() with 53 bits. */
internal class PyRandom(seed: Int) {
    private val mt = LongArray(624)
    private var mti = 624
    init {
        mt[0] = 19650218L
        for (i in 1 until 624) mt[i] = (1812433253L * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i) and M
        val key = abs(seed.toLong()) and M
        var i = 1; var k = 624
        while (k > 0) {
            mt[i] = ((mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525L)) + key) and M
            i++; if (i >= 624) { mt[0] = mt[623]; i = 1 }
            k--
        }
        k = 623
        while (k > 0) {
            mt[i] = ((mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941L)) - i) and M
            i++; if (i >= 624) { mt[0] = mt[623]; i = 1 }
            k--
        }
        mt[0] = 0x80000000L
    }
    private fun next(): Long {
        if (mti >= 624) {
            for (kk in 0 until 624) {
                val y = (mt[kk] and 0x80000000L) or (mt[(kk + 1) % 624] and 0x7fffffffL)
                mt[kk] = mt[(kk + 397) % 624] xor (y ushr 1) xor (if ((y and 1L) != 0L) 0x9908b0dfL else 0L)
            }
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680L)
        y = y xor ((y shl 15) and 0xefc60000L)
        y = y xor (y ushr 18)
        return y and M
    }
    fun random(): Double { val a = next() ushr 5; val b = next() ushr 6; return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0) }
    private companion object { const val M = 0xffffffffL }
}

/* ------------------------------------------------ paths ------------------------------------------------ */

/** A cairo-like path, flattened finely as it is built. Closed sub-paths repeat their first point at the end. */
internal class Geo {
    class Sub {
        var n = 0; var x = DoubleArray(32); var y = DoubleArray(32); var closed = false
        fun add(px: Double, py: Double) { if (n == x.size) { x = x.copyOf(n * 2); y = y.copyOf(n * 2) }; x[n] = px; y[n] = py; n++ }
    }
    val subs = ArrayList<Sub>(4)
    private var cur: Sub? = null

    fun newSub() { cur = null }
    fun moveTo(x: Double, y: Double) { val s = Sub(); subs.add(s); s.add(x, y); cur = s }
    fun lineTo(x: Double, y: Double) { val s = cur; if (s == null) moveTo(x, y) else s.add(x, y) }
    fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        val s = cur ?: return
        val x0 = s.x[s.n - 1]; val y0 = s.y[s.n - 1]
        for (i in 1..CURVE_STEPS) {
            val t = i.toDouble() / CURVE_STEPS; val u = 1 - t
            s.add(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3)
        }
    }
    /** cairo_arc: from a0 increasing to a1 (a1 below a0 wraps up by 2 pi); joins the current point with a line. */
    fun arc(cx: Double, cy: Double, r: Double, a0: Double, a1In: Double) {
        var a1 = a1In; while (a1 < a0) a1 += 2 * PI
        val span = a1 - a0; val n = max(4, ceil(span * r / CHORD).toInt())
        for (i in 0..n) { val a = a0 + span * i / n; lineTo(cx + r * cos(a), cy + r * sin(a)) }
    }
    fun close() { val s = cur ?: return; s.closed = true; s.add(s.x[0], s.y[0]); cur = null }
    fun rect(x: Double, y: Double, w: Double, h: Double) { moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close() }
    fun rrect(x: Double, y: Double, w: Double, h: Double, r0: Double) {
        val r = min(r0, min(w / 2, h / 2))
        newSub()
        arc(x + w - r, y + r, r, -PI / 2, 0.0); arc(x + w - r, y + h - r, r, 0.0, PI / 2)
        arc(x + r, y + h - r, r, PI / 2, PI); arc(x + r, y + r, r, PI, 1.5 * PI)
        close()
    }
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, ang: Double = 0.0) {
        newSub()
        val n = max(24, ceil(2 * PI * max(rx, ry) / CHORD).toInt()); val ca = cos(ang); val sa = sin(ang)
        for (i in 0..n) {
            val th = 2 * PI * i / n; val u = rx * cos(th); val v = ry * sin(th)
            lineTo(cx + u * ca - v * sa, cy + u * sa + v * ca)
        }
        close()
    }
    fun circle(cx: Double, cy: Double, r: Double) { newSub(); arc(cx, cy, r, 0.0, 2 * PI); close() }

    fun path(): Path = Path().also { p ->
        for (s in subs) {
            if (s.n < 2) continue
            p.moveTo(s.x[0].f(), s.y[0].f())
            for (i in 1 until s.n) p.lineTo(s.x[i].f(), s.y[i].f())
            if (s.closed) p.close()
        }
    }
    /** cairo_in_fill with the non-zero rule, on the fine outline. */
    fun inFill(px: Double, py: Double): Boolean {
        var w = 0
        for (s in subs) {
            if (!s.closed) continue
            for (i in 0 until s.n - 1) {
                val x0 = s.x[i]; val y0 = s.y[i]; val x1 = s.x[i + 1]; val y1 = s.y[i + 1]
                if (y0 <= py) { if (y1 > py && (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0) > 0) w++ }
                else if (y1 <= py && (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0) < 0) w--
            }
        }
        return w != 0
    }
    /** mascot3.flat_points: each closed outline, resampled every [step] units. */
    fun resampled(step: Double = 0.011): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (s in subs) {
            if (!s.closed || s.n <= 2) continue
            val res = ArrayList<Double>(); res.add(s.x[0]); res.add(s.y[0]); var acc = 0.0
            for (i in 1 until s.n) {
                var x0 = s.x[i - 1]; var y0 = s.y[i - 1]; val x1 = s.x[i]; val y1 = s.y[i]; var d = hypot(x1 - x0, y1 - y0)
                while (acc + d >= step && d > 0) {
                    val k = (step - acc) / d; x0 += (x1 - x0) * k; y0 += (y1 - y0) * k; d = hypot(x1 - x0, y1 - y0); acc = 0.0
                    res.add(x0); res.add(y0)
                }
                acc += d
            }
            out.add(res.toDoubleArray())
        }
        return out
    }
    fun key(extra: Long): Long {
        var h = extra * 0x9E3779B97F4A7C15uL.toLong()
        for (s in subs) { h = h * 31 + s.n; for (i in 0 until s.n) { h = h * 1000003 + (s.x[i] * 1e5).roundToLong(); h = h * 1000003 + (s.y[i] * 1e5).roundToLong() } }
        return h
    }
    companion object { const val CHORD = 0.006; const val CURVE_STEPS = 24 }
}

private fun bbox(subs: List<DoubleArray>): DoubleArray {
    var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
    for (s in subs) for (i in s.indices step 2) { x0 = min(x0, s[i]); x1 = max(x1, s[i]); y0 = min(y0, s[i + 1]); y1 = max(y1, s[i + 1]) }
    return doubleArrayOf(x0, y0, x1, y1)
}

/* ------------------------------------------------ the cache ------------------------------------------------ */

/** Finished pieces of shapes, keyed by their exact outline: idle frames reuse almost everything. */
internal object MascotCache {
    private val map = object : LinkedHashMap<Long, Any>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?) = size > 320
    }
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: Long, make: () -> T): T = synchronized(map) { map[key] as T? } ?: make().also { v -> synchronized(map) { map[key] = v } }
}

/* ------------------------------------------------ the fur painter ------------------------------------------------ */

private class Fur(
    val path: Path, val x0: Double, val y0: Double, val w: Double, val h: Double,
    val tuftColors: List<Color>, val tuftWidths: List<Float>, val tuftPaths: List<Path>,
    val texDark: Path?, val texLight: Path?,
)

private fun makeFur(g: Geo, t: MascotPalette, seed: Int, tuft: Double, tex: Boolean): Fur {
    val rnd = PyRandom(seed)
    val body = hexc(t.body); val hi = hexc(t.bodyHi); val lo = hexc(t.bodyLo); val rim = hexc(t.rim)
    val subs = g.resampled(); val bb = bbox(subs); val x0 = bb[0]; val y0 = bb[1]; val w = bb[2] - x0; val h = bb[3] - y0
    // 1. tufts: short hairs poking out along the outline (only the outside ones survive the fill)
    val groups = HashMap<Int, Path>(); val colors = HashMap<Int, Color>(); val widths = HashMap<Int, Float>()
    for (s in subs) {
        val n = s.size / 2
        for (i in 0 until n) {
            val px = s[2 * i]; val py = s[2 * i + 1]
            val qi = (i + 1) % n; val ri = (i - 1 + n) % n
            val tx = s[2 * qi] - s[2 * ri]; val ty = s[2 * qi + 1] - s[2 * ri + 1]; val tl = hypot(tx, ty).let { if (it == 0.0) 1e-6 else it }
            var nx = ty / tl; var ny = -tx / tl
            if (g.inFill(px + nx * 0.012, py + ny * 0.012)) { nx = -nx; ny = -ny }
            if (g.inFill(px + nx * 0.012, py + ny * 0.012)) continue
            val lit = -(nx * LX + ny * LY)
            // the colour, to 1/20 of its range, so the tufts batch into a few paths
            val litQ = Math.round(lit * 10).toInt()
            val lq = litQ / 10.0
            for (k in 0 until 3) {
                val len = tuft * (0.3 + 0.7 * rnd.random()); val j = (rnd.random() - 0.5) * 1.0
                val ex = px + (nx + -ny * j) * len; val ey = py + (ny + nx * j) * len
                val wr = rnd.random(); val wq = if (wr < 1.0 / 3) 0 else if (wr < 2.0 / 3) 1 else 2
                val key = (litQ + 20) * 4 + wq + (if (lit > -0.35) 1000 else 0)
                val p = groups.getOrPut(key) {
                    colors[key] = (if (lit > -0.35) mix(mix(lo, body, 0.6), mix(body, hi, 0.5), 0.5 + 0.5 * lq.coerceIn(-0.35, 1.0))
                        else mix(body, rim, min(0.8, -lq * 0.8))).color(0.9)
                    widths[key] = (0.007 + 0.006 * (wq + 0.5) / 3).f()
                    Path()
                }
                p.moveTo((px - nx * 0.01).f(), (py - ny * 0.01).f()); p.lineTo(ex.f(), ey.f())
            }
        }
    }
    // 3. felt texture: little curls, darker and lighter, like swirled felt
    var dark: Path? = null; var light: Path? = null
    if (tex) {
        dark = Path(); light = Path()
        val count = (w * h * 1400).toInt()
        for (i in 0 until count) {
            val x = x0 + rnd.random() * w; val y = y0 + rnd.random() * h; val r = 0.006 + 0.01 * rnd.random(); val a0 = rnd.random() * 6.28
            val p = if (rnd.random() < 0.6) dark else light
            p.arcTo(Rect((x - r).f(), (y - r).f(), (x + r).f(), (y + r).f()), Math.toDegrees(a0).f(), Math.toDegrees(3.6).f(), true)
        }
    }
    val keys = groups.keys.sorted()
    return Fur(g.path(), x0, y0, w, h, keys.map { colors[it]!! }, keys.map { widths[it]!! }, keys.map { groups[it]!! }, dark, light)
}

/** Paint the union of [g]'s sub-paths as a fuzzy, lit, back-lit felt shape (mascot3.furry). */
private fun DrawScope.furry(t: MascotPalette, g: Geo, seed: Int, sil: Color?, tuft: Double = 0.03, tex: Boolean = true, ao: Double = 0.32) {
    if (sil != null) { drawPath(g.path(), sil); return }
    val f = MascotCache.get(g.key(seed * 7919L + (tuft * 1e4).toLong() * 31 + (if (tex) 1 else 0) + t.id.hashCode() * 131L)) { makeFur(g, t, seed, tuft, tex) }
    val body = hexc(t.body); val hi = hexc(t.bodyHi); val lo = hexc(t.bodyLo)
    for (i in f.tuftPaths.indices) drawPath(f.tuftPaths[i], f.tuftColors[i], style = Stroke(f.tuftWidths[i], cap = StrokeCap.Round))
    // 2. the lit fill: brightest toward the light, darkest away from it
    val cx = f.x0 + f.w * 0.32; val cy = f.y0 + f.h * 0.26; val rr = hypot(f.w, f.h) * 0.85
    drawPath(f.path, Brush.radialGradient(0f to hi.color(), 0.5f to body.color(), 1f to lo.color(), center = o(cx, cy), radius = rr.f()))
    val big = Rect((f.x0 - 0.3).f(), (f.y0 - 0.3).f(), (f.x0 + f.w + 0.3).f(), (f.y0 + f.h + 0.3).f())
    clipPath(f.path) {
        if (f.texDark != null) drawPath(f.texDark, lo.color(0.09), style = Stroke(0.005f, cap = StrokeCap.Round))
        if (f.texLight != null) drawPath(f.texLight, hi.color(0.08), style = Stroke(0.005f, cap = StrokeCap.Round))
        // 4. soft shade at the bottom (it sits in its own shadow)
        if (ao > 0) drawRect(Brush.linearGradient(0f to Color(0f, 0f, 0f, 0f), 1f to rgba(0.12, 0.02, 0.0, ao),
            start = o(0.0, f.y0 + f.h * 0.45), end = o(0.0, f.y0 + f.h)), big.topLeft, big.size)
        // 5. back-light rim: the part of the shape NOT covered by itself shifted toward the light
        val rim = hexc(t.rim)
        for ((off, al) in RIMS) translate((LX * off).f(), (LY * off).f()) { clipPath(f.path, ClipOp.Difference) { drawRect(rim.color(al), big.topLeft, big.size) } }
        // 6. soft highlight on the lit edge
        translate((-LX * 0.05).f(), (-LY * 0.05).f()) { clipPath(f.path, ClipOp.Difference) { drawRect(rgba(1.0, 0.93, 0.82, 0.32), big.topLeft, big.size) } }
    }
}
private val RIMS = listOf(0.05 to 0.85, 0.022 to 0.6)

private class Cloth(val path: Path, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val white: Path, val black: Path)

/** Shaded fabric (vest, cap): gradient, coarse weave, rim light (mascot3.cloth). */
private fun DrawScope.cloth(t: MascotPalette, g: Geo, base: Long, hiCol: Long, sil: Color?, rimA: Double = 0.45) {
    if (sil != null) { drawPath(g.path(), sil); return }
    val c = MascotCache.get(g.key(base * 17 + hiCol + 5)) {
        val bb = bbox(g.resampled()); val x0 = bb[0]; val y0 = bb[1]; val w = bb[2] - x0; val h = bb[3] - y0
        val rnd = PyRandom((x0 * 1000).toInt() and 0xffff)
        val white = Path(); val black = Path()
        for (i in 0 until (w * h * 1400).toInt()) {   // weave speckle
            val x = x0 + rnd.random() * w; val y = y0 + rnd.random() * h
            val p = if (rnd.random() < 0.5) white else black
            p.moveTo(x.f(), y.f()); p.lineTo((x + 0.012).f(), (y + 0.004).f())
        }
        Cloth(g.path(), x0, y0, bb[2], bb[3], white, black)
    }
    val w = c.x1 - c.x0
    drawPath(c.path, Brush.linearGradient(0f to hexc(hiCol).color(), 0.55f to hexc(base).color(), 1f to mix(hexc(base), Rgb(0.0, 0.0, 0.0), 0.45).color(),
        start = o(c.x0, c.y0), end = o(c.x0 + w * 0.6, c.y1)))
    val big = Rect((c.x0 - 0.2).f(), (c.y0 - 0.2).f(), (c.x1 + 0.2).f(), (c.y1 + 0.2).f())
    clipPath(c.path) {
        drawPath(c.white, Color(1f, 1f, 1f, 0.05f), style = Stroke(0.004f, cap = StrokeCap.Round))
        drawPath(c.black, Color(0f, 0f, 0f, 0.12f), style = Stroke(0.004f, cap = StrokeCap.Round))
        translate((LX * 0.03).f(), (LY * 0.03).f()) { clipPath(c.path, ClipOp.Difference) { drawRect(hexc(t.rim).color(rimA), big.topLeft, big.size) } }
    }
}

private val DASH = PathEffect.dashPathEffect(floatArrayOf(0.028f, 0.02f), 0f)
private fun DrawScope.stitches(t: MascotPalette, g: Geo, a: Double = 0.55, w: Double = 0.011) =
    drawPath(g.path(), hexc(t.stitch).color(a), style = Stroke(w.f(), cap = StrokeCap.Round, pathEffect = DASH))

private fun DrawScope.aperture(cx: Double, cy: Double, r: Double, col: Color, ang: Double = 0.0, open: Double = 0.45) {
    drawCircle(col, r.f(), o(cx, cy), style = Stroke((r * 0.16).f()))
    val hole = r * open
    val pts = (0 until 6).map { k -> o(cx + hole * cos(ang + k * PI / 3), cy + hole * sin(ang + k * PI / 3)) }
    val st = Stroke((r * 0.11).f(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val blades = Path()
    for (k in 0 until 6) {
        val a = ang + k * PI / 3 + 1.25
        blades.moveTo(pts[k].x, pts[k].y); blades.lineTo((cx + r * 0.95 * cos(a)).f(), (cy + r * 0.95 * sin(a)).f())
    }
    drawPath(blades, col, style = st)
    drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) lineTo(q.x, q.y); close() }, col, style = st)
}

/* ------------------------------------------------ hands and arms ------------------------------------------------ */

/** A tapered tube from a to b, bowed sideways by [bend], with round ends. */
private fun Geo.tube(ax: Double, ay: Double, bx: Double, by: Double, w0: Double, w1: Double, bend: Double = 0.0, n: Int = 14) {
    val mx = (ax + bx) / 2; val my = (ay + by) / 2; val dx = bx - ax; val dy = by - ay; val l = hypot(dx, dy).let { if (it == 0.0) 1e-6 else it }
    val cx = mx - dy / l * bend; val cy = my + dx / l * bend
    fun bz(s: Double, out: DoubleArray) { val u = 1 - s; out[0] = u * u * ax + 2 * u * s * cx + s * s * bx; out[1] = u * u * ay + 2 * u * s * cy + s * s * by }
    val lx = DoubleArray(n + 1); val ly = DoubleArray(n + 1); val rx = DoubleArray(n + 1); val ry = DoubleArray(n + 1)
    val p = DoubleArray(2); val q = DoubleArray(2)
    for (i in 0..n) {
        val s = i.toDouble() / n; bz(s, p)
        val tx: Double; val ty: Double
        if (s >= 1) { bz(0.99, q); tx = p[0] - q[0]; ty = p[1] - q[1] } else { bz(min(1.0, s + 0.01), q); tx = q[0] - p[0]; ty = q[1] - p[1] }
        val tl = hypot(tx, ty).let { if (it == 0.0) 1e-6 else it }; val nx = -ty / tl; val ny = tx / tl; val w = (w0 + (w1 - w0) * s) / 2
        lx[i] = p[0] + nx * w; ly[i] = p[1] + ny * w; rx[i] = p[0] - nx * w; ry[i] = p[1] - ny * w
    }
    val px = DoubleArray(2 * n + 2); val py = DoubleArray(2 * n + 2)
    for (i in 0..n) { px[i] = lx[i]; py[i] = ly[i]; px[2 * n + 1 - i] = rx[i]; py[2 * n + 1 - i] = ry[i] }
    var area = 0.0
    for (i in px.indices) { val j = (i + 1) % px.size; area += px[i] * py[j] - px[j] * py[i] }
    if (area < 0) { px.reverse(); py.reverse() }   // the same turning as the arcs, so the union has no holes
    newSub(); moveTo(px[0], py[0]); for (i in 1 until px.size) lineTo(px[i], py[i]); close()
    circle(ax, ay, w0 / 2); circle(bx, by, w1 / 2)
}

/** ang: where the fingers point (radians, 0 = right, -pi/2 = up). */
private fun Geo.hand(x: Double, y: Double, ang: Double, kind: String, s: Double, wig: Double) {
    val ca = cos(ang); val sa = sin(ang)
    fun atX(u: Double, v: Double) = x + (u * ca - v * sa) * s
    fun atY(u: Double, v: Double) = y + (u * sa + v * ca) * s
    when (kind) {
        "open" -> {   // palm and four spread fingers plus a thumb: a wave
            ellipse(atX(0.0, 0.0), atY(0.0, 0.0), 0.17 * s, 0.16 * s, ang)
            val fingers = doubleArrayOf(-0.34, 0.12, -0.11, 0.145, 0.11, 0.145, 0.34, 0.12)
            for (k in 0 until 4) {
                val spread = fingers[2 * k]; val l = fingers[2 * k + 1]
                val a2 = spread * 0.8 + wig * (0.5 - k / 3.0) * 0.3
                val bx = atX(0.1, (k - 1.5) * 0.075); val by = atY(0.1, (k - 1.5) * 0.075)
                tube(bx, by, bx + cos(ang + a2) * l * s, by + sin(ang + a2) * l * s, 0.09 * s, 0.085 * s)
            }
            val tx = atX(-0.03, -0.14); val ty = atY(-0.03, -0.14)
            tube(tx, ty, tx + cos(ang - 1.25) * 0.11 * s, ty + sin(ang - 1.25) * 0.11 * s, 0.095 * s, 0.085 * s)
        }
        "grip" -> {
            ellipse(atX(0.0, 0.0), atY(0.0, 0.0), 0.14 * s, 0.15 * s, ang)
            for (k in 0 until 3) ellipse(atX(0.1, (k - 1) * 0.085), atY(0.1, (k - 1) * 0.085), 0.06 * s, 0.045 * s, ang)
            ellipse(atX(0.06, -0.15), atY(0.06, -0.15), 0.08 * s, 0.05 * s, ang - 0.6)
        }
        else -> {     // soft mitten
            ellipse(atX(0.02, 0.0), atY(0.02, 0.0), 0.165 * s, 0.15 * s, ang)
            ellipse(atX(-0.02, -0.12), atY(-0.02, -0.12), 0.07 * s, 0.05 * s, ang - 0.9)
        }
    }
}

internal val ARM = doubleArrayOf(0.34, 0.32)   // upper arm, forearm: the elbow bends, the bones keep their length

/** Two-bone IK in 2D: (elbow x, y, hand x, y). */
internal fun elbowOf(shx: Double, shy: Double, hx: Double, hy: Double, px0: Double, py0: Double): DoubleArray {
    val l1 = ARM[0]; val l2 = ARM[1]; val dx = hx - shx; val dy = hy - shy; val d = hypot(dx, dy).let { if (it == 0.0) 1e-6 else it }
    val d2 = min(max(d, abs(l1 - l2) + 1e-3), l1 + l2 - 1e-3)
    val ux = dx / d; val uy = dy / d
    val a = (l1 * l1 - l2 * l2 + d2 * d2) / (2 * d2); val h = sqrt(max(0.0, l1 * l1 - a * a))
    var px = -uy; var py = ux
    if (px * px0 + py * py0 < 0) { px = -px; py = -py }
    return doubleArrayOf(shx + ux * a + px * h, shy + uy * a + py * h, shx + ux * d2, shy + uy * d2)
}

/** Two-bone IK in 3D seen from the front (mascot3.elbow3): (elbow x, y, hand x, y, elbow z, hand z). */
internal fun elbow3(shx: Double, shy: Double, hx: Double, hy: Double, z: Double, pole: DoubleArray, l1: Double = ARM[0], l2: Double = ARM[1]): DoubleArray {
    val d0 = hx - shx; val d1 = hy - shy; val n = sqrt(d0 * d0 + d1 * d1 + z * z).let { if (it == 0.0) 1e-6 else it }
    val n2 = min(max(n, abs(l1 - l2) + 1e-3), l1 + l2 - 1e-3)
    val u0 = d0 / n; val u1 = d1 / n; val u2 = z / n
    val a = (l1 * l1 - l2 * l2 + n2 * n2) / (2 * n2); val h = sqrt(max(0.0, l1 * l1 - a * a))
    val k = pole[0] * u0 + pole[1] * u1 + pole[2] * u2
    var q0 = pole[0] - k * u0; var q1 = pole[1] - k * u1; var q2 = pole[2] - k * u2
    val m = sqrt(q0 * q0 + q1 * q1 + q2 * q2).let { if (it == 0.0) 1e-6 else it }; q0 /= m; q1 /= m; q2 /= m
    return doubleArrayOf(shx + u0 * a + q0 * h, shy + u1 * a + q1 * h, shx + u0 * n2, shy + u1 * n2, u2 * a + q2 * h, u2 * n2)
}

/** The elbow and hand of each arm as last drawn, by seed (21 right, 22 left): for the frame-by-frame check. */
internal val lastElbows = HashMap<Int, DoubleArray>()

private fun DrawScope.limb(t: MascotPalette, shx: Double, shy: Double, hand: DoubleArray, kind: String, ang: Double, seed: Int, pole: DoubleArray,
                           wig: Double, z: Double?, fk: DoubleArray?, stretch: Double, sil: Color?) {
    val s = if (kind == "open") 1.25 else 1.0
    val ex: Double; val ey: Double; val hx: Double; val hy: Double
    if (fk != null) {   // forward: (upper-arm angle, forearm angle[, bone lengths]); nothing to solve, nothing to flip
        val l1 = if (fk.size > 2) fk[2] else ARM[0]; val l2 = if (fk.size > 2) fk[3] else ARM[1]
        ex = shx + l1 * cos(fk[0]); ey = shy + l1 * sin(fk[0]); hx = ex + l2 * cos(fk[1]); hy = ey + l2 * sin(fk[1])
    } else if (z == null) {
        val r = elbowOf(shx, shy, hand[0], hand[1], pole[0], pole[1]); ex = r[0]; ey = r[1]; hx = r[2]; hy = r[3]
    } else {
        val p3 = if (pole.size == 3) pole else doubleArrayOf(pole[0], pole[1], 0.0)
        val r = elbow3(shx, shy, hand[0], hand[1], z, p3, ARM[0], ARM[1] * stretch)   // cartoon stretch while jumping (the forearm)
        ex = r[0]; ey = r[1]; hx = r[2]; hy = r[3]
    }
    lastElbows[seed] = doubleArrayOf(ex, ey, hx, hy)
    val g = Geo()
    val dx = hx - ex; val dy = hy - ey; val l = hypot(dx, dy).let { if (it == 0.0) 1e-6 else it }
    val wx = hx - dx / l * 0.1 * s; val wy = hy - dy / l * 0.1 * s
    val fat = 1 + 0.35 * (stretch - 1)   // a stretched arm also gets a little fuller
    g.tube(shx, shy, ex, ey, 0.29 * fat, 0.25 * fat)
    g.tube(ex, ey, wx, wy, 0.25 * fat, 0.21 * fat)
    g.hand(hx, hy, ang, kind, s, wig)
    furry(t, g, seed, sil, tuft = 0.028)
}

/* ------------------------------------------------ camera ------------------------------------------------ */

private class CameraParts(val body: Path, val dots: Path, val plate: Path, val knobs: List<Pair<Path, DoubleArray>>, val finder: Path, val finderHi: Path,
                          val reflection: Path)

private val cameraParts by lazy {
    val body = Geo().apply { rrect(-0.36, -0.2, 0.72, 0.42, 0.07) }.path()
    val rnd = PyRandom(7); val dots = Path()
    for (i in 0 until 260) { val x = -0.36 + rnd.random() * 0.72; val y = -0.1 + rnd.random() * 0.32; dots.addOval(Rect((x - 0.004).f(), (y - 0.004).f(), (x + 0.004).f(), (y + 0.004).f())) }
    val plate = Geo().apply { rrect(-0.36, -0.22, 0.72, 0.12, 0.05) }.path()
    val knobs = listOf(doubleArrayOf(0.13, 0.13, 0.07), doubleArrayOf(-0.22, 0.09, 0.05)).map { (x, w, h) -> Geo().apply { rrect(x, -0.22 - h, w, h + 0.01, 0.015) }.path() to doubleArrayOf(x, w) }
    CameraParts(body, dots, plate, knobs, Geo().apply { rrect(-0.31, -0.19, 0.15, 0.065, 0.015) }.path(), Geo().apply { rrect(-0.29, -0.18, 0.05, 0.02, 0.008) }.path(),
        Geo().apply { ellipse(-0.045, -0.005, 0.03, 0.016, -0.6) }.path())
}

private fun DrawScope.camera(cx: Double, cy: Double, ang: Double, s: Double, flash: Double, sil: Color?) = translate(cx.f(), cy.f()) {
    rotate(Math.toDegrees(ang).f(), Offset.Zero) { scale(s.f(), s.f(), Offset.Zero) {
        val c = cameraParts
        if (sil != null) { drawPath(c.body, sil); drawPath(c.plate, sil); drawCircle(sil, 0.17f, Offset(0f, 0.04f)); return@scale }
        // body: black leatherette with a soft top-left light
        drawPath(c.body, Brush.linearGradient(0f to hexc(0x3A3633).color(), 1f to hexc(0x121110).color(), start = o(-0.36, -0.2), end = o(0.2, 0.22)))
        clipPath(c.body) { drawPath(c.dots, Color(1f, 1f, 1f, 0.06f)) }
        // silver top plate with a bright edge
        drawPath(c.plate, Brush.linearGradient(0f to hexc(0xECE8E0).color(), 0.5f to hexc(0xB8B2A8).color(), 1f to hexc(0x7C766D).color(), start = o(0.0, -0.22), end = o(0.0, -0.1)))
        for ((p, xw) in c.knobs)   // shutter dial, rewind knob
            drawPath(p, Brush.linearGradient(0f to hexc(0x8C867D).color(), 0.4f to hexc(0xF2EEE6).color(), 1f to hexc(0x77716A).color(), start = o(xw[0], 0.0), end = o(xw[0] + xw[1], 0.0)))
        drawPath(c.finder, hexc(0x1E2228).color()); drawPath(c.finderHi, Color(1f, 1f, 1f, 0.35f))
        // lens: chrome barrel, black ring, deep glass with coloured coating and a sharp reflection
        for ((r, c0, c1) in listOf(Triple(0.17, 0xF0ECE4L, 0x6E685FL), Triple(0.14, 0x2A2826L, 0x0C0B0BL)))
            drawCircle(Brush.linearGradient(0f to hexc(c0).color(), 1f to hexc(c1).color(), start = o(-r, -r + 0.03), end = o(r, r + 0.03)), r.f(), Offset(0f, 0.04f))
        // (cairo's two-circle gradient, focus at (-0.03, 0.01), drawn as one circle around the focus)
        drawCircle(Brush.radialGradient(0f to rgba(0.55, 0.85, 1.0, 1.0), 0.3f to rgba(0.3, 0.2, 0.6, 1.0), 0.75f to rgba(0.12, 0.05, 0.08, 1.0), 1f to rgba(0.03, 0.03, 0.04, 1.0),
            center = o(-0.03, 0.01), radius = 0.13f), 0.115f, Offset(0f, 0.04f))
        drawArc(rgba(1.0, 0.75, 0.4, 0.5), Math.toDegrees(3.6).f(), Math.toDegrees(1.0).f(), false, Offset(-0.085f, 0.04f - 0.085f), Size(0.17f, 0.17f), style = Stroke(0.008f, cap = StrokeCap.Round))
        drawPath(c.reflection, Color(1f, 1f, 1f, 0.85f))
        for (x in doubleArrayOf(-0.36, 0.36)) drawCircle(hexc(0xC8C2B8).color(), 0.022f, o(x, -0.06))   // strap lugs
        if (flash > 0) drawCircle(Brush.radialGradient(0f to rgba(1.0, 1.0, 0.95, 0.95 * flash), 1f to rgba(1.0, 1.0, 0.9, 0.0), center = Offset(0f, 0.04f), radius = 0.9f), 0.9f, Offset(0f, 0.04f))
    } }
}

/* ------------------------------------------------ the whole character ------------------------------------------------ */

/** Draws the mascot with its feet at (0, 0), one unit per unit (scale the DrawScope first). [sil] = one flat colour, for the glow. */
internal fun DrawScope.drawMascot(p: MascotPose, t: MascotPalette, sil: Color? = null) {
    val bob = p.d("bob", 0.0); val sq = p.d("squash", 0.0)
    scale((1 + sq * 0.5).f(), (1 - sq).f(), Offset.Zero) {
        // legs: short, soft, a little wider at the foot; in a jump they leave the ground and dangle apart
        for ((i, x) in doubleArrayOf(-0.37, 0.07).withIndex()) translate(0f, p.d("legY", 0.0).f()) {
            val hx = x + 0.15
            translate(hx.f(), -0.5f) { rotate(Math.toDegrees((if (i == 1) -1 else 1) * p.d("legSpread", 0.0)).f(), Offset.Zero) { translate(-hx.f(), 0.5f) {
                furry(t, Geo().apply { rrect(x, -0.56, 0.3, 0.56, 0.15); ellipse(x + 0.15, -0.07, 0.17, 0.08) }, 10 + i, sil, tuft = 0.026)
            } } }
        }
        translate(0f, bob.f()) { drawBody(p, t, sil) }
    }
}

private fun DrawScope.drawBody(p: MascotPose, t: MascotPalette, sil: Color?) {
    val shL = doubleArrayOf(-0.5 - p.d("shrugL", 0.0), -1.1 - 0.7 * p.d("shrugL", 0.0)); val shR = doubleArrayOf(0.5 + p.d("shrugR", 0.0), -1.1 - 0.7 * p.d("shrugR", 0.0))
    val hL = p.v("handL")!!; val hR = p.v("handR")!!
    val kL = p.s("kindL", "mitt"); val kR = p.s("kindR", "mitt")
    val aL = p.d("angL", PI / 2 + 0.3); val aR = p.d("angR", PI / 2 - 0.3)
    val wig = p.d("wiggle", 0.0)
    fun right() = limb(t, shR[0], shR[1], hR, kR, aR, 21, p.v("poleR") ?: doubleArrayOf(1.0, 0.7), wig, p.dn("zR"), p.v("fkR"), p.d("stretchR", 1.0), sil)
    fun left() = limb(t, shL[0], shL[1], hL, kL, aL, 22, p.v("poleL") ?: doubleArrayOf(-1.0, 0.7), 0.0, p.dn("zL"), p.v("fkL"), p.d("stretchL", 1.0), sil)
    if (!p.b("frontR")) right()
    if (!p.b("frontL")) left()
    // torso: a soft round belly
    furry(t, Geo().apply { rrect(-0.58, -1.32, 1.16, 1.0, 0.36) }, 30, sil)
    // vest: two front panels, a V of fur at the neck, belly peeking out below
    for (sgn in intArrayOf(-1, 1)) {
        val sg = sgn.toDouble()
        cloth(t, Geo().apply {
            newSub(); moveTo(sg * 0.6, -1.0); curveTo(sg * 0.6, -1.24, sg * 0.44, -1.31, sg * 0.27, -1.31)
            lineTo(sg * 0.035, -0.92); lineTo(sg * 0.035, -0.44); lineTo(sg * 0.48, -0.44)
            curveTo(sg * 0.57, -0.46, sg * 0.6, -0.52, sg * 0.6, -0.62); close()
        }, t.vest, t.vestHi, sil)
        if (sil != null) continue
        stitches(t, Geo().apply { moveTo(sg * 0.07, -0.9); lineTo(sg * 0.07, -0.48); lineTo(sg * 0.5, -0.48) })
        stitches(t, Geo().apply { moveTo(sg * 0.3, -1.26); lineTo(sg * 0.075, -0.93) })
        // pocket with a flap and a press stud
        val px = if (sgn < 0) -0.5 else 0.14
        cloth(t, Geo().apply { rrect(px, -0.74, 0.34, 0.26, 0.045) }, t.vest, t.vestHi, null, rimA = 0.25)
        stitches(t, Geo().apply { rrect(px + 0.025, -0.715, 0.29, 0.21, 0.03) }, a = 0.45, w = 0.009)
        cloth(t, Geo().apply { moveTo(px - 0.01, -0.77); lineTo(px + 0.35, -0.77); lineTo(px + 0.35, -0.66); curveTo(px + 0.25, -0.62, px + 0.09, -0.62, px - 0.01, -0.66); close() },
            t.vest, t.vestHi, null, rimA = 0.35)
        drawCircle(Brush.radialGradient(0f to rgba(1.0, 0.97, 0.9, 1.0), 1f to rgba(0.45, 0.4, 0.33, 1.0), center = o(px + 0.16, -0.665), radius = 0.028f), 0.022f, o(px + 0.17, -0.66))
        // chest pocket
        val cx = if (sgn > 0) 0.2 else -0.42
        stitches(t, Geo().apply { rrect(cx, -1.06, 0.22, 0.18, 0.03) }, a = 0.4, w = 0.009)
    }
    if (sil == null) {
        // zip: brass teeth down the middle
        drawPath(Geo().apply { for (k in 0 until 20) rect(-0.018 + (k % 2) * 0.012, -0.92 + k * 0.025, 0.024, 0.012) }.path(), hexc(0xC9A46A).color())
        drawRect(hexc(0xE9D3A0).color(), Offset(-0.022f, -0.95f), Size(0.044f, 0.05f))
        // camera strap across the body: leather with stitching
        if (p.b("strap", true)) {
            val e = p.v("strapEnd") ?: doubleArrayOf(-0.5, -0.45)
            val strap = Geo().apply { moveTo(0.42, -1.27); curveTo(0.15, -0.95, -0.25, -0.72, e[0], e[1]) }
            val sp = strap.path()
            drawPath(sp, Color(0f, 0f, 0f, 0.35f), style = Stroke(0.085f, cap = StrokeCap.Round))
            drawPath(sp, hexc(t.strap).color(), style = Stroke(0.065f, cap = StrokeCap.Round))
            translate(-0.008f, -0.01f) { drawPath(sp, rgba(1.0, 0.9, 0.75, 0.22), style = Stroke(0.018f, cap = StrokeCap.Round)) }
            stitches(t, strap, a = 0.5, w = 0.007)
        }
    }
    if (p.b("cameraHip")) camera(-0.55, -0.42, 0.15, 1.0, 0.0, sil)
    p.v("cameraFloor")?.let { camera(it[0], it[1], p.d("cameraFloorAng", 0.5), 1.0, 0.0, sil) }
    // ---- head (tilts about the neck) ----
    translate(0f, -1.22f) { rotate(Math.toDegrees(p.d("tilt", 0.0)).f(), Offset.Zero) { translate(0f, 1.22f) { drawHead(p, t, sil) } } }
    // ---- in front: raised arms, the camera in hand ----
    if (p.b("frontR")) right()
    if (p.b("frontL")) left()
    p.v("cameraHand")?.let { ch ->
        camera(ch[0], ch[1], p.d("cameraAng", 0.0), p.d("cameraScale", 1.0), p.d("flash", 0.0), sil)
        for (f in p.thumbs()) furry(t, Geo().apply { ellipse(f[0], f[1], 0.06, 0.045, if (f.size > 2) f[2] else 0.0) }, 60, sil, tuft = 0.015, tex = false, ao = 0.0)
    }
}

private fun DrawScope.drawHead(p: MascotPose, t: MascotPalette, sil: Color?) {
    val ax = -0.42; val ay = -2.05
    // petal sway in steps of 0.004 rad (0.002 units at the tip), so idle frames come from the cache
    val sway = Math.round(p.d("petal", 0.0) / SWAY_STEP) * SWAY_STEP
    for ((k, deg) in listOf(0 to 165.0, 2 to 259.0, 1 to 212.0)) {   // the middle petal last: each has its own lit edge
        val a = Math.toRadians(deg) + sway * (0.6 + 0.4 * k)
        furry(t, Geo().apply { ellipse(ax + 0.52 * cos(a), ay + 0.52 * sin(a), 0.42, 0.31, a) }, 40 + k, sil, tuft = 0.03)
        if (sil == null) drawPath(Geo().apply { ellipse(ax + 0.45 * cos(a), ay + 0.45 * sin(a), 0.22, 0.03, a) }.path(), hexc(t.bodyLo).color(0.25))   // its soft crease
    }
    val head = Geo().apply { rrect(-0.66, -2.3, 1.32, 1.14, 0.46) }
    furry(t, head, 41, sil, tuft = 0.036)
    if (sil == null) {
        // shadow of the cap brim on the forehead
        clipPath(head.path()) {
            drawRect(Brush.linearGradient(0f to rgba(0.1, 0.02, 0.0, 0.55), 1f to rgba(0.1, 0.02, 0.0, 0.0), start = Offset(0f, -2.02f), end = Offset(0f, -1.84f)),
                Offset(-0.7f, -2.1f), Size(1.4f, 0.3f))
        }
        drawFace(p)
    }
    // cap: brim first (it sits under the dome's edge), then the dome with panel seams and a button
    cloth(t, Geo().apply { moveTo(0.3, -2.08); curveTo(0.8, -2.16, 1.2, -2.1, 1.3, -1.96); curveTo(1.1, -1.86, 0.62, -1.86, 0.25, -1.94); close() }, t.cap, t.capHi, sil, rimA = 0.5)
    if (sil == null) {
        drawPath(Geo().apply { moveTo(0.28, -1.94); curveTo(0.62, -1.88, 1.1, -1.88, 1.29, -1.97) }.path(), Color(0f, 0f, 0f, 0.5f), style = Stroke(0.03f, cap = StrokeCap.Round))
        stitches(t, Geo().apply { moveTo(0.4, -2.06); curveTo(0.8, -2.12, 1.15, -2.07, 1.24, -1.98) }, a = 0.4, w = 0.008)
    }
    val dome = Geo().apply { moveTo(-0.7, -1.98); curveTo(-0.74, -2.62, 0.66, -2.66, 0.64, -2.02); curveTo(0.2, -2.08, -0.3, -2.06, -0.7, -1.98); close() }
    cloth(t, dome, t.cap, t.capHi, sil, rimA = 0.55)
    if (sil != null) return
    clipPath(dome.path()) {
        for (sx in doubleArrayOf(-0.42, 0.0, 0.4))   // panel seams fanning from the button
            drawPath(Geo().apply { moveTo(-0.03, -2.5); curveTo(sx * 0.8 - 0.03, -2.4, sx * 1.1, -2.2, sx * 1.15, -2.0) }.path(), Color(0f, 0f, 0f, 0.45f), style = Stroke(0.012f, cap = StrokeCap.Round))
    }
    stitches(t, Geo().apply { moveTo(-0.66, -2.03); curveTo(-0.3, -2.1, 0.2, -2.12, 0.6, -2.06) }, a = 0.45, w = 0.009)
    drawPath(Geo().apply { ellipse(-0.03, -2.505, 0.06, 0.03) }.path(), Brush.radialGradient(0f to hexc(t.capHi).color(), 1f to hexc(t.cap).color(), center = o(-0.045, -2.52), radius = 0.06f))
    aperture(-0.04, -2.27, 0.11, hexc(t.accent).color(), p.d("markSpin", 0.0))
    if (p.d("sweat", 0.0) > 0) {
        val a = p.d("sweat", 0.0)
        drawPath(Geo().apply { moveTo(0.74, -1.98); curveTo(0.68, -1.84, 0.68, -1.78, 0.74, -1.76); curveTo(0.8, -1.78, 0.8, -1.84, 0.74, -1.98) }.path(), rgba(0.6, 0.85, 1.0, 0.85 * a))
    }
}

private const val SWAY_STEP = 0.004

private fun DrawScope.drawFace(p: MascotPose) {
    // eyes: glossy rounded bars with a small catch-light; they squint to a slit, then become happy arcs
    val bl = p.d("blink", 0.0); val lx = p.d("look", 0.0); val hp = p.d("happyEyes", 0.0)
    val eh = 0.27 * (1 - 0.88 * max(bl, min(1.0, hp / 0.45)))
    for (x in doubleArrayOf(-0.25, 0.14)) {
        if (hp >= 0.5) continue   // closed happy eyes are drawn as arcs below, never on top of open eyes
        if (p.b("sheepish")) {
            val d = if (x < 0) 1 else -1; val x0 = x + lx + (if (x < 0) 0.0 else 0.1)
            drawPath(Path().apply { moveTo(x0.f(), -1.74f); lineTo((x0 + d * 0.1).f(), -1.66f); lineTo(x0.f(), -1.58f) }, hexc(0x141210).color(),
                style = Stroke(0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawPath(Geo().apply { rrect(x + lx - 0.008, -1.64 - eh / 2 + 0.012, 0.116, eh, 0.055) }.path(), rgba(0.35, 0.08, 0.0, 0.35))   // soft socket
            drawPath(Geo().apply { rrect(x + lx, -1.66 - eh / 2, 0.1, eh, 0.05) }.path(),
                Brush.linearGradient(0f to hexc(0x2A2624).color(), 1f to hexc(0x0A0908).color(), start = o(0.0, -1.66 - eh / 2), end = o(0.0, -1.66 + eh / 2)))
            if (eh > 0.08) drawPath(Geo().apply { ellipse(x + lx + 0.035, -1.66 - eh / 2 + 0.05, 0.018, 0.03) }.path(), Color(1f, 1f, 1f, 0.75f))
        }
    }
    if (hp >= 0.5) {   // happy closed eyes: thin flat upside-down arcs
        for (x in doubleArrayOf(-0.25, 0.14)) {
            val ex = x + lx + 0.05
            drawPath(Path().apply { moveTo((ex - 0.065).f(), -1.63f); cubicTo((ex - 0.04).f(), -1.685f, (ex + 0.04).f(), -1.685f, (ex + 0.065).f(), -1.63f) },
                rgba(0.08, 0.06, 0.05, 1.0), style = Stroke(0.034f, cap = StrokeCap.Round))
        }
    }
    val mo = p.d("mouthOpen", 0.0)
    if (mo > 0) {   // an open happy mouth: dark D shape with a pink tongue
        val cx = -0.005 + lx; val w = 0.12; val hgt = 0.11 * mo
        val m = Path().apply { moveTo((cx - w).f(), -1.45f); cubicTo((cx - w * 0.6).f(), (-1.45 + hgt * 1.3).f(), (cx + w * 0.6).f(), (-1.45 + hgt * 1.3).f(), (cx + w).f(), -1.45f); close() }
        drawPath(m, rgba(0.24, 0.07, 0.05, 1.0))
        clipPath(m) { drawPath(Geo().apply { ellipse(cx, -1.45 + hgt * 1.05, w * 0.55, hgt * 0.45) }.path(), rgba(0.93, 0.45, 0.42, 1.0)) }
    }
    val sm = p.d("smile", 0.0)
    if (sm > 0 && mo < 0.3) {   // a small happy mouth under the eyes: a thin reddish line
        val cx = -0.005 + lx
        drawPath(Path().apply { moveTo((cx - 0.09).f(), -1.43f); cubicTo((cx - 0.05).f(), (-1.43 + 0.07 * sm).f(), (cx + 0.05).f(), (-1.43 + 0.07 * sm).f(), (cx + 0.09).f(), -1.43f) },
            rgba(0.36, 0.1, 0.07, min(1.0, 2.5 * sm)), style = Stroke(0.026f, cap = StrokeCap.Round))
    }
    val bs = p.d("blush", 0.0)
    if (bs > 0) for (x in doubleArrayOf(-0.44, 0.36))
        drawCircle(Brush.radialGradient(0f to rgba(1.0, 0.4, 0.35, 0.45 * bs), 1f to rgba(1.0, 0.4, 0.35, 0.0), center = o(x, -1.42), radius = 0.11f), 0.11f, o(x, -1.42))
}
