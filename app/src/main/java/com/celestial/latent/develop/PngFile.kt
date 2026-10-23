package com.celestial.latent.develop

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * The print saved as a PNG (78b): lossless, 8 or 16 bits a channel, RGB, written by hand.
 *
 * Android's own PNG writer only makes 8-bit files, and a 16-bit picture would have to pass through a
 * half-float bitmap to reach it; this writes the engine's float output straight into the file instead,
 * row by row into the stream, so a 50 MP print never needs its whole file in memory. Both depths use the
 * same writer so 8-bit and 16-bit files differ only in depth.
 *
 * The colour space is tagged with the very chunk Android writes for that space ([colourChunks], taken
 * from a 1×1 PNG Android encoded with the same tagged bitmap), so viewers read the file as the JPEG is
 * read. The film note (fog, light and texture, for the Film Builder) goes in a tEXt chunk.
 *
 * Proven on the JVM against the engine's float print (`export-formats/port-check/`).
 */
object PngFile {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    /** The tEXt keyword the film note is kept under. */
    const val NOTE_KEY = "Comment"

    private fun chunk(out: OutputStream, type: String, data: ByteArray, off: Int = 0, len: Int = data.size) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(int32(len))
        out.write(t)
        out.write(data, off, len)
        val crc = CRC32(); crc.update(t); crc.update(data, off, len)
        out.write(int32(crc.value.toInt()))
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /**
     * The colour chunks (iCCP, or sRGB / gAMA / cHRM) inside a PNG [made by Android][android.graphics.Bitmap.compress],
     * in their order, ready to be written again. Empty when it has none.
     */
    fun colourChunks(png: ByteArray): List<Pair<String, ByteArray>> {
        val found = ArrayList<Pair<String, ByteArray>>()
        var at = 8
        while (at + 12 <= png.size) {
            val len = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
                ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
            if (len < 0 || at + 12 + len > png.size) break
            val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "IDAT" || type == "IEND") break
            if (type in setOf("iCCP", "sRGB", "gAMA", "cHRM", "cICP")) found += type to png.copyOfRange(at + 8, at + 8 + len)
            at += 12 + len
        }
        return found
    }

    /**
     * Writes [data] ([w] × [h], three floats a pixel, display values 0..1 in the engine's output space) to [out]
     * as a PNG of [bits] (8 or 16) bits a channel. [ourSpace]: one of [OutputSpace]'s own spaces, converted here
     * from the engine's sRGB in full float precision. [colour]: the chunks from [colourChunks]. [note]: the film note.
     */
    fun write(out: OutputStream, data: ByteBuffer, w: Int, h: Int, bits: Int, ourSpace: String,
              colour: List<Pair<String, ByteArray>>, note: String?) {
        require(bits == 8 || bits == 16) { "PNG depth $bits" }
        val f = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        out.write(SIGNATURE)
        val ihdr = int32(w) + int32(h) + byteArrayOf(bits.toByte(), 2, 0, 0, 0)   // RGB, deflate, no interlace
        chunk(out, "IHDR", ihdr)
        colour.forEach { (type, body) -> chunk(out, type, body) }
        // UTF-8 (the note can hold "·" and "×"); viewers show tEXt as text either way, and only we read it back
        note?.let { chunk(out, "tEXt", (NOTE_KEY + "\u0000" + it).toByteArray(Charsets.UTF_8)) }

        // The picture: one filter byte and a row of samples, Paeth-filtered (the best on photographs), deflated
        // straight into IDAT chunks of up to 1 MB as it goes.
        val idat = object : OutputStream() {
            val buf = ByteArray(1 shl 20); var n = 0
            override fun write(b: Int) { if (n == buf.size) flush(); buf[n++] = b.toByte() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off; var left = len
                while (left > 0) { if (n == buf.size) flush(); val k = minOf(left, buf.size - n); System.arraycopy(b, o, buf, n, k); n += k; o += k; left -= k }
            }
            override fun flush() { if (n > 0) { chunk(out, "IDAT", buf, 0, n); n = 0 } }
        }
        val deflater = Deflater(6)
        val z = DeflaterOutputStream(idat, deflater, 1 shl 16)
        val bpp = 3 * bits / 8
        val stride = w * bpp
        var prev = ByteArray(stride)
        var row = ByteArray(stride)
        val filtered = ByteArray(stride + 1)
        val line = FloatArray(w * 3)
        val top = if (bits == 16) 65535f else 255f
        for (y in 0 until h) {
            f.position(y * w * 3); f.get(line)
            for (i in line.indices) line[i] = line[i].coerceIn(0f, 1f)
            if (OutputSpace.isOurs(ourSpace)) OutputSpace.convertTable(line, ourSpace)
            if (bits == 16) for (i in line.indices) {
                val v = (line[i] * top + 0.5f).toInt().coerceIn(0, 65535)
                row[2 * i] = (v ushr 8).toByte(); row[2 * i + 1] = v.toByte()
            } else for (i in line.indices) row[i] = (line[i] * top + 0.5f).toInt().coerceIn(0, 255).toByte()
            // Paeth: each byte minus the best guess from its left, upper and upper-left neighbours
            filtered[0] = 4
            for (i in 0 until stride) {
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val p = a + b - c
                val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                val guess = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                filtered[i + 1] = ((row[i].toInt() and 0xFF) - guess).toByte()
            }
            z.write(filtered)
            val t = prev; prev = row; row = t
        }
        z.finish(); deflater.end()
        idat.flush()
        chunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    /** The film note inside a PNG's opening chunks, if it has one. */
    fun readNote(head: ByteArray): String? {
        if (head.size < 8 || !head.copyOf(8).contentEquals(SIGNATURE)) return null
        var at = 8
        while (at + 12 <= head.size) {
            val len = ((head[at].toInt() and 0xFF) shl 24) or ((head[at + 1].toInt() and 0xFF) shl 16) or
                ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
            if (len < 0 || at + 12 + len > head.size) return null
            val type = String(head, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "IDAT") return null
            if (type == "tEXt") {
                val body = head.copyOfRange(at + 8, at + 8 + len)
                val zero = body.indexOf(0)
                if (zero > 0 && String(body, 0, zero, Charsets.ISO_8859_1) == NOTE_KEY) {
                    return String(body, zero + 1, body.size - zero - 1, Charsets.UTF_8)
                }
            }
            at += 12 + len
        }
        return null
    }
}
