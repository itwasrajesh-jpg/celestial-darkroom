package com.celestial.latent.develop

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 116a: one recipe as a QR code (approved mock recipe-share/qr/). Celestial, 5 Nov: "each saved recipe
 * can be saved as a qr code and shared, and when the other person scans it the app saves it as a preset".
 *
 * The code holds a link: [PREFIX] + the recipe packed small. Only the settings that differ from a new
 * [Recipe] go in ([Recipe.fromJson] gives every missing one its default back), deflated, then base64url.
 * A busy recipe (25 changes and a tone curve) measured 355 bytes, a link of about 500 characters, a
 * 93×93 code that reads easily off a screen or a print. A film built in the Film Builder carries its whole
 * ~200 KB profile and can't fit: it still travels as a file ([RecipeShare]).
 */
object RecipeQr {
    const val PREFIX = "celestialdarkroom://recipe/1/"
    private const val MAX_BODY = 64 * 1024

    /** The link for [item], or null for a built film (too big for a code). */
    fun link(item: RecipeShare.Item): String? {
        if (item.built) return null
        val r = JSONObject(item.recipe.toJson()); val d = JSONObject(Recipe().toJson())
        val diff = JSONObject()
        val keys = r.keys()
        while (keys.hasNext()) {
            val k = keys.next(); val v = r.get(k)
            if (!d.has(k) || d.get(k).toString() != v.toString()) diff.put(k, v)
        }
        val body = JSONObject().put("n", item.name).put("r", diff).toString().toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.encodeToString(deflate(body), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** The recipe in a scanned code, or null if the text is not one of ours. */
    fun read(text: String): RecipeShare.Item? = runCatching {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        val body = inflate(Base64.decode(t.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) ?: return null
        val o = JSONObject(String(body, Charsets.UTF_8))
        val name = Recipes.cleanName(o.optString("n"))
        val r = o.optJSONObject("r") ?: return null
        if (name.isEmpty()) null else RecipeShare.Item(name, Recipe.fromJson(r.toString()))
    }.getOrNull()

    /** A one-recipe pack for the Import page. */
    fun pack(item: RecipeShare.Item) = RecipeShare.Pack("", 0L, listOf(item))

    /** The code's modules, one cell each, no quiet zone (the card draws its own). */
    fun matrix(link: String): BitMatrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "ISO-8859-1"))

    /** The text of a QR code in [src], or null. [hard] looks longer (still pictures; not every camera frame). */
    fun find(src: LuminanceSource, hard: Boolean): String? {
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        if (hard) hints[DecodeHintType.TRY_HARDER] = true
        val binarizers = if (hard) listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src)) else listOf(HybridBinarizer(src))
        for (b in binarizers) {
            val text = runCatching { QRCodeReader().decode(BinaryBitmap(b), hints).text }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    /**
     * The text of a QR code in a picture (shared from a chat, picked from the gallery), or null. Tried at
     * about 1600 px first (fast, and a chat app's copy is about that big), then as it is.
     */
    fun find(bmp: Bitmap): String? {
        val big = maxOf(bmp.width, bmp.height)
        val sizes = if (big > 1600) listOf(1600f / big, 1f) else listOf(1f)
        for (s in sizes) {
            val b = if (s == 1f) bmp else Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            find(RGBLuminanceSource(b.width, b.height, px), hard = true)?.let { return it }
        }
        return null
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray? {
        val i = Inflater(); i.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        try {
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY) return null
            }
        } catch (_: Exception) { return null } finally { i.end() }
        return out.toByteArray()
    }
}
