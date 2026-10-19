package com.celestial.latent.camera

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A burst roll (68a): every full-size RAW frame of one burst in a single file, so a burst is one
 * thing in the roll instead of sixteen DNGs, and any frame can be picked and exported later.
 *
 * Layout (big-endian):
 *   "LBURST01"
 *   blocks, written as they are ready: per frame its compressed high bytes, low bytes, and a preview JPEG
 *   index (see [Index.write])
 *   offset of the index (8 bytes), then "LBRSTEND"
 * The index sits at the end so the file can be written as one stream while frames still arrive.
 *
 * Frames are lossless: each pixel is stored as its difference from the same-colour pixel two to the
 * left (zig-zag, so small either way), split into a high-byte and a low-byte plane, each deflated at
 * level 1. Measured on a real 14-bit DCG burst (19 Oct): 47% of the plain size, exact round trip.
 * No new library: java.util.zip is part of Android.
 */
object BurstRoll {
    const val EXT = "lburst"
    const val FOLDER = "Documents/Latent/Bursts"
    private val MAGIC = "LBURST01".toByteArray(Charsets.US_ASCII)
    private val END = "LBRSTEND".toByteArray(Charsets.US_ASCII)

    /** What every frame shares: enough to write any frame back out as a DNG. */
    class Common(
        val width: Int, val height: Int, val black: Int, val white: Int,
        val cfa: ByteArray, val colorMatrix1: DoubleArray, val colorMatrix2: DoubleArray?,
        val forwardMatrix1: DoubleArray?, val calibration1: DoubleArray?,
        val illuminant1: Int, val illuminant2: Int?, val orientation: Int,
        val model: String, val lens: String, val dcg: Boolean,
        val kind: Int = KIND_BURST,
    )

    /** One frame: its own exposure and white balance, where its data sits, and how sharp it is. */
    class Frame(
        val tOffsetNs: Long, val exposureNs: Long?, val iso: Int?, val neutral: DoubleArray,
        var sharpness: Float = 0f, var mode: Int = MODE_DELTA,
        var hiOff: Long = 0, var hiLen: Int = 0, var loOff: Long = 0, var loLen: Int = 0,
        var prevOff: Long = 0, var prevLen: Int = 0,
        val focusD: Float = -1f, val lensMoving: Boolean = false,
    )

    const val MODE_DELTA = 1   // same-colour left difference, zig-zag, byte planes
    const val MODE_PLAIN = 0   // plain values, byte planes (only if a difference would not fit 16 bits)

    const val KIND_BURST = 0   // every frame at the same focus (68a)
    const val KIND_FOCUS = 1   // a focus bracket: each frame focused a little farther away (69a)

    class Index(val common: Common, val frames: List<Frame>) {
        fun write(out: DataOutputStream) {
            val c = common
            // Version 2 (69a) adds the kind of roll, and each frame's focus distance and whether the lens was moving.
            out.writeInt(2)
            out.writeUTF(c.model); out.writeUTF(c.lens); out.writeBoolean(c.dcg); out.writeInt(c.kind)
            out.writeInt(c.width); out.writeInt(c.height); out.writeInt(c.black); out.writeInt(c.white); out.writeInt(c.orientation)
            out.write(c.cfa, 0, 4)
            fun mat(m: DoubleArray?) { out.writeBoolean(m != null); m?.forEach { out.writeDouble(it) } }
            mat(c.colorMatrix1); mat(c.colorMatrix2); mat(c.forwardMatrix1); mat(c.calibration1)
            out.writeInt(c.illuminant1); out.writeInt(c.illuminant2 ?: -1)
            out.writeInt(frames.size)
            for (f in frames) {
                out.writeLong(f.tOffsetNs); out.writeLong(f.exposureNs ?: -1); out.writeInt(f.iso ?: -1)
                f.neutral.forEach { out.writeDouble(it) }
                out.writeFloat(f.sharpness); out.writeInt(f.mode)
                out.writeLong(f.hiOff); out.writeInt(f.hiLen); out.writeLong(f.loOff); out.writeInt(f.loLen)
                out.writeLong(f.prevOff); out.writeInt(f.prevLen)
                out.writeFloat(f.focusD); out.writeBoolean(f.lensMoving)
            }
        }

        companion object {
            fun read(inp: DataInputStream): Index {
                val version = inp.readInt()
                require(version == 1 || version == 2) { "unknown burst roll version" }
                val model = inp.readUTF(); val lens = inp.readUTF(); val dcg = inp.readBoolean()
                val kind = if (version >= 2) inp.readInt() else KIND_BURST
                val w = inp.readInt(); val h = inp.readInt(); val black = inp.readInt(); val white = inp.readInt(); val orientation = inp.readInt()
                val cfa = ByteArray(4).also { inp.readFully(it) }
                fun mat(): DoubleArray? = if (inp.readBoolean()) DoubleArray(9) { inp.readDouble() } else null
                val cm1 = mat()!!; val cm2 = mat(); val fm1 = mat(); val cal1 = mat()
                val ill1 = inp.readInt(); val ill2 = inp.readInt().takeIf { it >= 0 }
                val n = inp.readInt()
                val frames = List(n) {
                    val t = inp.readLong(); val exp = inp.readLong().takeIf { it >= 0 }; val iso = inp.readInt().takeIf { it >= 0 }
                    val neutral = DoubleArray(3) { inp.readDouble() }
                    val sharp = inp.readFloat(); val mode = inp.readInt()
                    val hiOff = inp.readLong(); val hiLen = inp.readInt(); val loOff = inp.readLong(); val loLen = inp.readInt()
                    val prevOff = inp.readLong(); val prevLen = inp.readInt()
                    val focusD = if (version >= 2) inp.readFloat() else -1f; val moving = if (version >= 2) inp.readBoolean() else false
                    Frame(t, exp, iso, neutral, sharp, mode, hiOff, hiLen, loOff, loLen, prevOff, prevLen, focusD, moving)
                }
                return Index(Common(w, h, black, white, cfa, cm1, cm2, fm1, cal1, ill1, ill2, orientation, model, lens, dcg, kind), frames)
            }
        }
    }

    // ---- codec ---------------------------------------------------------------------------------

    class Encoded(val mode: Int, val hi: ByteArray, val lo: ByteArray)

    fun encode(px: ShortArray, w: Int, h: Int): Encoded {
        val n = w * h
        val hi = ByteArray(n); val lo = ByteArray(n)
        var mode = MODE_DELTA
        run delta@{
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val v = px[row + x].toInt() and 0xFFFF
                    val d = if (x < 2) v else v - (px[row + x - 2].toInt() and 0xFFFF)
                    val z = (d shl 1) xor (d shr 31)
                    if (z > 0xFFFF) { mode = MODE_PLAIN; return@delta }
                    hi[row + x] = (z ushr 8).toByte(); lo[row + x] = z.toByte()
                }
            }
        }
        if (mode == MODE_PLAIN) for (i in 0 until n) { val v = px[i].toInt() and 0xFFFF; hi[i] = (v ushr 8).toByte(); lo[i] = v.toByte() }
        return Encoded(mode, deflate(hi), deflate(lo))
    }

    fun decode(mode: Int, hiZ: ByteArray, loZ: ByteArray, w: Int, h: Int): ShortArray {
        val n = w * h
        val hi = inflate(hiZ, n); val lo = inflate(loZ, n)
        val px = ShortArray(n)
        if (mode == MODE_PLAIN) { for (i in 0 until n) px[i] = (((hi[i].toInt() and 0xFF) shl 8) or (lo[i].toInt() and 0xFF)).toShort(); return px }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val z = ((hi[row + x].toInt() and 0xFF) shl 8) or (lo[row + x].toInt() and 0xFF)
                val d = (z ushr 1) xor -(z and 1)
                val v = if (x < 2) d else d + (px[row + x - 2].toInt() and 0xFFFF)
                px[row + x] = v.toShort()
            }
        }
        return px
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(1)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(b.size / 2 + 1024)
        val buf = ByteArray(1 shl 16)
        while (!d.finished()) { val k = d.deflate(buf); out.write(buf, 0, k) }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray, size: Int): ByteArray {
        val inf = Inflater(); inf.setInput(b)
        val out = ByteArray(size); var pos = 0
        while (pos < size && !inf.finished()) {
            val k = inf.inflate(out, pos, size - pos)
            if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
            pos += k
        }
        inf.end()
        require(pos == size) { "burst frame is damaged ($pos of $size bytes)" }
        return out
    }

    // ---- measurements --------------------------------------------------------------------------

    /** Index of the green samples in the 2x2 colour pattern (CFA bytes: 0 R, 1 G, 2 B). */
    private fun greens(cfa: ByteArray) = (0 until 4).filter { cfa[it].toInt() == 1 }

    /**
     * How sharp a frame is: the spread of a Laplacian over the middle of a quarter-size green image.
     * Shake and missed focus both lower it. Quarter size so sensor noise counts for little, and only
     * the ranking within one burst matters (all frames share exposure and noise).
     */
    fun sharpness(px: ShortArray, w: Int, h: Int, cfa: ByteArray, black: Int): Float {
        val gIdx = greens(cfa)
        val qw = w / 4; val qh = h / 4
        val g = FloatArray(qw * qh)
        for (qy in 0 until qh) for (qx in 0 until qw) {
            var s = 0f
            for (by in 0..1) for (bx in 0..1) {
                val y0 = qy * 4 + by * 2; val x0 = qx * 4 + bx * 2
                for (i in gIdx) s += (px[(y0 + i / 2) * w + x0 + i % 2].toInt() and 0xFFFF) - black
            }
            g[qy * qw + qx] = s
        }
        var sum = 0.0; var sum2 = 0.0; var cnt = 0
        for (y in qh / 5 until qh - qh / 5) for (x in qw / 5 until qw - qw / 5) {
            val i = y * qw + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - qw] - g[i + qw]
            sum += l; sum2 += l.toDouble() * l; cnt++
        }
        if (cnt == 0) return 0f
        val mean = sum / cnt
        return Math.sqrt(maxOf(0.0, sum2 / cnt - mean * mean)).toFloat()
    }

    /** A brightness gain that puts the middle grey of a frame near 18% in the previews (shared by all frames of a burst). */
    fun previewGain(px: ShortArray, black: Int, white: Int): Float {
        val step = maxOf(1, px.size / 20000)
        val s = FloatArray((px.size + step - 1) / step) { ((px[it * step].toInt() and 0xFFFF) - black).toFloat() }
        s.sort()
        val med = s[s.size / 2].coerceAtLeast(1f) / (white - black).toFloat()
        return (0.18f / med).coerceIn(1f, 64f)
    }

    /**
     * Preview pixels at quarter size (one RGB value per 4x4 block), white balanced, gamma encoded,
     * already turned upright for orientation 6. ARGB ints, ready for a Bitmap. Returns (argb, width, height).
     */
    fun previewArgb(px: ShortArray, c: Common, neutral: DoubleArray, gain: Float): Triple<IntArray, Int, Int> {
        val qw = c.width / 4; val qh = c.height / 4
        val range = (c.white - c.black).toFloat()
        val wb = FloatArray(3) { (1.0 / neutral[it].coerceAtLeast(1e-3)).toFloat() }
        val lut = IntArray(4096) { i -> val v = i / 4095f; (255f * (if (v <= 0.0031308f) 12.92f * v else 1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f) + 0.5f).toInt().coerceIn(0, 255) }
        val rot = c.orientation == 6
        val ow = if (rot) qh else qw; val oh = if (rot) qw else qh
        val out = IntArray(ow * oh)
        val acc = FloatArray(3); val cnt = IntArray(3)
        for (qy in 0 until qh) for (qx in 0 until qw) {
            acc.fill(0f); cnt.fill(0)
            for (dy in 0 until 4) for (dx in 0 until 4) {
                val y = qy * 4 + dy; val x = qx * 4 + dx
                val ch = c.cfa[(y and 1) * 2 + (x and 1)].toInt()
                acc[ch] += (px[y * c.width + x].toInt() and 0xFFFF) - c.black; cnt[ch]++
            }
            var argb = 0xFF shl 24
            for (ch in 0..2) {
                val v = (acc[ch] / maxOf(cnt[ch], 1) / range * wb[ch] * gain).coerceIn(0f, 1f)
                argb = argb or (lut[(v * 4095).toInt()] shl (16 - 8 * ch))
            }
            // orientation 6: rotate 90 degrees clockwise
            val (ox, oy) = if (rot) (qh - 1 - qy) to qx else qx to qy
            out[oy * ow + ox] = argb
        }
        return Triple(out, ow, oh)
    }

    // ---- writing -------------------------------------------------------------------------------

    /**
     * Writes one burst file to [out] as frames become ready. Blocks must be added in frame order
     * (the caller keeps the order); [finish] writes the index and closes the stream.
     */
    class StreamWriter(private val out: OutputStream) {
        private var pos = 0L
        init { put(MAGIC) }
        private fun put(b: ByteArray) { out.write(b); pos += b.size }
        fun addFrame(f: Frame, e: Encoded, previewJpeg: ByteArray?) {
            f.mode = e.mode
            f.hiOff = pos; f.hiLen = e.hi.size; put(e.hi)
            f.loOff = pos; f.loLen = e.lo.size; put(e.lo)
            if (previewJpeg != null) { f.prevOff = pos; f.prevLen = previewJpeg.size; put(previewJpeg) }
        }
        fun finish(index: Index): Long {
            val at = pos
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { index.write(it) }
            put(bos.toByteArray())
            put(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(at).array())
            put(END)
            out.flush(); out.close()
            return pos
        }
    }

    // ---- reading -------------------------------------------------------------------------------

    /** Reads a burst file through a channel (a file descriptor from the content resolver, or a plain file). */
    class Reader(private val ch: FileChannel) : AutoCloseable {
        val index: Index

        init {
            val size = ch.size()
            require(size > 32) { "not a burst roll" }
            val head = read(0, 8); require(head.contentEquals(MAGIC)) { "not a burst roll" }
            val tail = read(size - 16, 16)
            require(tail.copyOfRange(8, 16).contentEquals(END)) { "this burst was not finished saving" }
            val at = ByteBuffer.wrap(tail, 0, 8).order(ByteOrder.BIG_ENDIAN).long
            index = Index.read(DataInputStream(read(at, (size - 16 - at).toInt()).inputStream()))
        }

        private fun read(at: Long, len: Int): ByteArray {
            val b = ByteBuffer.allocate(len); var p = at
            while (b.hasRemaining()) { val k = ch.read(b, p); if (k < 0) break; p += k }
            require(!b.hasRemaining()) { "burst roll is cut short" }
            return b.array()
        }

        fun preview(i: Int): ByteArray? = index.frames[i].let { if (it.prevLen > 0) read(it.prevOff, it.prevLen) else null }

        fun pixels(i: Int): ShortArray {
            val f = index.frames[i]; val c = index.common
            return decode(f.mode, read(f.hiOff, f.hiLen), read(f.loOff, f.loLen), c.width, c.height)
        }

        /** The frame as DNG metadata, so [DngWriter] can write it out exactly as it was shot. */
        fun dngMeta(i: Int, description: String): DngWriter.Meta {
            val c = index.common; val f = index.frames[i]
            return DngWriter.Meta(c.width, c.height, c.black, c.white, c.cfa, c.colorMatrix1, c.colorMatrix2, c.forwardMatrix1,
                c.calibration1, c.illuminant1, c.illuminant2, f.neutral, c.orientation, c.model, description, f.exposureNs, f.iso)
        }

        val sharpest: Int get() = index.frames.indices.maxByOrNull { index.frames[it].sharpness } ?: 0

        override fun close() { ch.close() }
    }
}
