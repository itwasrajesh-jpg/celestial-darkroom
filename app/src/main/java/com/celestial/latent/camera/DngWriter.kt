package com.celestial.latent.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DNG writer for frames we assembled ourselves (the burst average).
 * Android's DngCreator can only write a DNG at the sensor's own white level (1023 here),
 * so it cannot carry the extra precision that averaging gives. This writer stores
 * 16-bit CFA data with our own black/white levels and copies the colour tags from the
 * camera characteristics, so Lightroom and the film engine read it like any DNG.
 *
 * Uncompressed, single strip, big-endian TIFF. Deliberately boring.
 */
object DngWriter {

    class Meta(
        val width: Int,
        val height: Int,
        val blackLevel: Int,
        val whiteLevel: Int,
        val cfaPattern: ByteArray,       // 4 bytes, e.g. 0,1,1,2 for RGGB
        val colorMatrix1: DoubleArray,   // 9
        val colorMatrix2: DoubleArray?,
        val forwardMatrix1: DoubleArray?,
        val cameraCalibration1: DoubleArray?,
        val illuminant1: Int,
        val illuminant2: Int?,
        val asShotNeutral: DoubleArray,  // 3
        val orientation: Int,            // TIFF orientation (6 = rotate 90 CW)
        val uniqueModel: String,
        val description: String,
    )

    fun metaFrom(ch: CameraCharacteristics, result: CaptureResult?, width: Int, height: Int, black: Int, white: Int, orientation: Int, description: String): Meta {
        val cfa = when (ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> byteArrayOf(0, 1, 1, 2)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> byteArrayOf(1, 0, 2, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> byteArrayOf(1, 2, 0, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> byteArrayOf(2, 1, 1, 0)
            else -> byteArrayOf(0, 1, 1, 2)
        }
        fun mat(key: CameraCharacteristics.Key<android.hardware.camera2.params.ColorSpaceTransform>): DoubleArray? {
            val m = ch.get(key) ?: return null
            return DoubleArray(9) { i -> m.getElement(i % 3, i / 3).toDouble() }
        }
        // AsShotNeutral = 1 / white-balance gain per channel, normalised so green = 1.
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val neutral = if (gains != null && gains.red > 0f && gains.blue > 0f) {
            val g = (gains.greenEven + gains.greenOdd) / 2f
            doubleArrayOf((g / gains.red).toDouble(), 1.0, (g / gains.blue).toDouble())
        } else doubleArrayOf(1.0, 1.0, 1.0)
        return Meta(
            width, height, black, white, cfa,
            mat(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            mat(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            mat(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
            mat(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),
            ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21,
            ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            neutral, orientation,
            "${Build.MANUFACTURER} ${Build.MODEL}", description,
        )
    }

    /** pixels: width*height 16-bit samples, row-major, values already in [0, whiteLevel]. */
    fun write(out: OutputStream, meta: Meta, pixels: ShortArray) {
        val w = meta.width; val h = meta.height
        require(pixels.size == w * h)

        // Build tags. Each: (tag, type, count, valueBytes). Types: 1 BYTE, 2 ASCII, 3 SHORT, 4 LONG, 5 RATIONAL, 10 SRATIONAL.
        class Tag(val id: Int, val type: Int, val count: Int, val data: ByteArray)
        val tags = ArrayList<Tag>()
        fun shorts(id: Int, vararg v: Int) { val b = ByteBuffer.allocate(2 * v.size).order(ByteOrder.BIG_ENDIAN); v.forEach { b.putShort(it.toShort()) }; tags += Tag(id, 3, v.size, b.array()) }
        fun longs(id: Int, vararg v: Int) { val b = ByteBuffer.allocate(4 * v.size).order(ByteOrder.BIG_ENDIAN); v.forEach { b.putInt(it) }; tags += Tag(id, 4, v.size, b.array()) }
        fun bytes(id: Int, v: ByteArray) { tags += Tag(id, 1, v.size, v) }
        fun ascii(id: Int, s: String) { val v = (s + "\u0000").toByteArray(Charsets.US_ASCII); tags += Tag(id, 2, v.size, v) }
        fun rationals(id: Int, v: DoubleArray, signed: Boolean) {
            val b = ByteBuffer.allocate(8 * v.size).order(ByteOrder.BIG_ENDIAN)
            v.forEach { d -> val den = 10000; b.putInt(Math.round(d * den).toInt()); b.putInt(den) }
            tags += Tag(id, if (signed) 10 else 5, v.size, b.array())
        }

        val imageBytes = w * h * 2
        // Placeholder for StripOffsets; patched after layout.
        longs(254, 0)                        // NewSubfileType: main image
        longs(256, w); longs(257, h)
        shorts(258, 16)
        shorts(259, 1)                       // uncompressed
        shorts(262, 32803)                   // CFA
        ascii(271, Build.MANUFACTURER)
        ascii(272, Build.MODEL)
        longs(273, 0)                        // StripOffsets (patched)
        shorts(274, meta.orientation)
        shorts(277, 1)
        longs(278, h)
        longs(279, imageBytes)
        shorts(284, 1)
        ascii(305, "Celestial Darkroom")
        ascii(270, meta.description)
        shorts(33421, 2, 2)
        bytes(33422, meta.cfaPattern)
        bytes(50706, byteArrayOf(1, 4, 0, 0))
        bytes(50707, byteArrayOf(1, 1, 0, 0))
        ascii(50708, meta.uniqueModel)
        bytes(50710, byteArrayOf(0, 1, 2))
        shorts(50711, 1)
        longs(50714, meta.blackLevel)
        longs(50717, meta.whiteLevel)
        rationals(50721, meta.colorMatrix1, true)
        meta.colorMatrix2?.let { rationals(50722, it, true) }
        meta.cameraCalibration1?.let { rationals(50723, it, true) }
        rationals(50728, meta.asShotNeutral, false)
        shorts(50778, meta.illuminant1)
        meta.illuminant2?.let { shorts(50779, it) }
        meta.forwardMatrix1?.let { rationals(50964, it, true) }
        tags.sortBy { it.id }

        // Layout: header(8) + IFD(2 + 12n + 4) + out-of-line values + image data.
        val ifdSize = 2 + 12 * tags.size + 4
        var extraOffset = 8 + ifdSize
        val extra = ByteArrayOutputStream()
        val entries = ByteBuffer.allocate(ifdSize).order(ByteOrder.BIG_ENDIAN)
        entries.putShort(tags.size.toShort())
        val imageOffset = run {
            var total = extraOffset
            tags.forEach { if (it.data.size > 4) total += it.data.size + (it.data.size and 1) }
            total
        }
        for (t in tags) {
            entries.putShort(t.id.toShort()); entries.putShort(t.type.toShort()); entries.putInt(t.count)
            val data = if (t.id == 273) ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(imageOffset).array() else t.data
            if (data.size <= 4) {
                entries.put(data); repeat(4 - data.size) { entries.put(0.toByte()) }
            } else {
                entries.putInt(extraOffset)
                extra.write(data); extraOffset += data.size
                if (data.size and 1 == 1) { extra.write(0); extraOffset += 1 }
            }
        }
        entries.putInt(0)

        val dos = DataOutputStream(out.buffered(1 shl 20))
        dos.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)); dos.writeInt(8)
        dos.write(entries.array())
        dos.write(extra.toByteArray())
        // Image data, big-endian 16-bit, row-major.
        val row = ByteBuffer.allocate(w * 2).order(ByteOrder.BIG_ENDIAN)
        for (y in 0 until h) {
            row.clear()
            val base = y * w
            for (x in 0 until w) row.putShort(pixels[base + x])
            dos.write(row.array())
        }
        dos.flush()
    }
}
