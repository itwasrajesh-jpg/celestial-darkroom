@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.RecipeQr
import com.celestial.latent.develop.RecipeShare
import com.celestial.latent.ui.Glyph
import com.celestial.latent.ui.GlyphIcon
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.glow
import com.celestial.latent.ui.raised
import com.google.zxing.PlanarYUVLuminanceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 116a: recipes as QR codes (approved mocks recipe-share/qr/proof/, Celestial 5 Nov).
 *  - [RecipeCardScreen]: one recipe as a picture to post or send: a photo, the name, the film, the code.
 *  - [RecipeScanScreen]: the camera reads a code by itself; Pick a photo reads one from a saved picture.
 *  - A picture shared to the app from any chat lands on the Import page ([readSharedPicture]).
 * Whatever the way in, the recipe is added on the same Import page as a file (Keep both / Replace / Skip).
 */

/** The screens' frame, as [RecipesScreen] draws it. */
@Composable
private fun QrFrame(title: String, onBack: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).glow().statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.BACK, LatentColors.Text, size = 18.dp)
            Text("back", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = LatentColors.TextBright, fontSize = 24.sp)
        body()
    }
}

// ---------------------------------------------------------------- the card

object RecipeCard {
    const val W = 1080
    const val H = 1350

    /**
     * The card: [photo] (or a plain mat) on top, then the name, the film and the code. Its colours are
     * fixed, not the theme's: it is a picture that leaves the app.
     */
    fun draw(item: RecipeShare.Item, link: String, photo: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF15171D.toInt())
        val pad = 42f
        val pr = RectF(pad, pad, W - pad, pad + (W - 2 * pad) * 3f / 4f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.save(); c.clipRect(pr)
        if (photo != null) {
            // centre crop to 4:3
            val want = pr.width() / pr.height()
            val have = photo.width.toFloat() / photo.height
            val src = if (have > want) { val w = (photo.height * want).toInt(); Rect((photo.width - w) / 2, 0, (photo.width + w) / 2, photo.height) }
                else { val h = (photo.width / want).toInt(); Rect(0, (photo.height - h) / 2, photo.width, (photo.height + h) / 2) }
            c.drawBitmap(photo, src, pr, p)
        } else {
            p.color = 0xFF22252D.toInt(); c.drawRect(pr, p)
        }
        c.restore()
        // the code: white tile, 4-cell quiet zone, whole pixels per cell so it stays crisp
        val m = RecipeQr.matrix(link)
        val n = m.width
        // 420 px: still read after a chat app shrinks the card to half size and JPEGs it (tested at 540 px, quality 60)
        val tile = 420
        val cell = tile / (n + 8)
        val side = cell * (n + 8)
        val tx = (W - pad).toInt() - side
        val textTop = pr.bottom + pad
        val ty = (textTop + (H - pad - textTop - side) / 2).toInt()
        p.color = 0xFFFFFFFF.toInt(); c.drawRoundRect(RectF(tx.toFloat(), ty.toFloat(), (tx + side).toFloat(), (ty + side).toFloat()), 14f, 14f, p)
        p.isAntiAlias = false; p.color = 0xFF111522.toInt()
        for (y in 0 until n) for (x in 0 until n) if (m.get(x, y)) {
            val l = tx + (x + 4) * cell; val t = ty + (y + 4) * cell
            c.drawRect(l.toFloat(), t.toFloat(), (l + cell).toFloat(), (t + cell).toFloat(), p)
        }
        // the words, left of the code
        val maxW = tx - pad - 36f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tp.color = 0xFFE8A64A.toInt(); tp.textSize = 24f; tp.letterSpacing = 0.18f
        var y = ty + 40f
        c.drawText("CELESTIAL DARKROOM RECIPE", pad, y, tp)
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        tp.color = 0xFFF2EEE6.toInt(); tp.letterSpacing = 0.08f; tp.textSize = 66f
        val name = item.name.uppercase()
        while (tp.textSize > 30f && tp.measureText(name) > maxW) tp.textSize -= 2f
        y += 30f + tp.textSize
        c.drawText(name, pad, y, tp)
        tp.color = 0xFFB8B2A7.toInt(); tp.letterSpacing = 0f; tp.textSize = 30f
        y += 52f
        c.drawText(ellipsize(filmLine(item), tp, maxW), pad, y, tp)
        tp.color = 0xFF8E897F.toInt(); tp.textSize = 24f
        y += 76f
        c.drawText("Scan with Celestial Darkroom", pad, y, tp)
        c.drawText("to add this look to your camera", pad, y + 32f, tp)
        return out
    }

    /** "Kodak Gold 200 · CineBloom": the film, and the lens filter if one is on. */
    fun filmLine(item: RecipeShare.Item): String {
        val r = item.recipe
        val film = Develop.FILMS.firstOrNull { it.first == r.film }?.second ?: r.film
        val filter = if (r.diffusion) r.diffusionFamily.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } } else null
        return listOfNotNull(film, filter).joinToString(" · ")
    }

    private fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    /** The newest print the darkroom saved (a JPEG in DCIM/Latent), or null. */
    fun newestPrint(ctx: Context): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?", arrayOf("DCIM/Latent%", "image/jpeg"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
            }
        }.getOrNull()
    }
}

/** A picture, upright and no bigger than about [max] px on its long side. */
fun loadPicture(ctx: Context, uri: Uri, max: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > max) { val s = max.toFloat() / big; d.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt())) }
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}.getOrNull()

/** What a shared or picked picture holds, ready for the Import page. */
fun readSharedPicture(ctx: Context, uri: Uri, label: String): RecipeIncoming {
    val bmp = loadPicture(ctx, uri, 3000) ?: return RecipeIncoming(label, null, "Couldn't open this picture.")
    val text = RecipeQr.find(bmp)
    val item = text?.let { RecipeQr.read(it) }
    return when {
        item != null -> RecipeIncoming(label, RecipeQr.pack(item))
        text != null -> RecipeIncoming(label, null, "This picture has a QR code, but it isn't a Celestial Darkroom recipe.")
        else -> RecipeIncoming(label, null, "No recipe code in this picture. Try the scanner, or a sharper copy of the picture.")
    }
}

@Composable
fun RecipeCardScreen(item: RecipeShare.Item, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link = remember(item) { RecipeQr.link(item) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var picked by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (!picked) photoUri = withContext(Dispatchers.IO) { RecipeCard.newestPrint(context) } }
    LaunchedEffect(photoUri, link) {
        if (link == null) return@LaunchedEffect
        val u = photoUri
        card = withContext(Dispatchers.Default) { RecipeCard.draw(item, link, u?.let { loadPicture(context, it, 1600) }) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { picked = true; photoUri = uri } }
    QrFrame("QR card", onBack) {
        Text("Post it or send it. Anyone with Celestial Darkroom can scan it and get ${item.name.uppercase()}.",
            color = LatentColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            val b = card
            if (link == null) RecipeNote("A film built here carries its whole film profile, too big for a QR code. Send it with Export → Share file.")
            else if (b == null) Text("Making the card…", color = LatentColors.TextDim, fontSize = 12.sp)
            else Image(b.asImageBitmap(), "QR card for ${item.name}", Modifier.fillMaxHeight().aspectRatio(RecipeCard.W.toFloat() / RecipeCard.H)
                .raised(RoundedCornerShape(8.dp), 8.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { pick.launch("image/*") }))
        }
        Text(if (picked) "Photo: the one you picked · tap the card to pick another" else if (photoUri != null) "Photo: your newest print · tap the card to pick another"
            else "Tap the card to put a photo on it", color = LatentColors.TextDim, fontSize = 11.sp)
        if (note.isNotEmpty()) Text(note, color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecipePill("Share card", true, Glyph.SHARE, Modifier.weight(1f), enabled = card != null) {
                val b = card ?: return@RecipePill
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(context.cacheDir, "share").apply { mkdirs() }
                            val f = File(dir, "celestial-recipe.png").apply { outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        }.getOrNull()
                    }
                    if (uri == null) { note = "Couldn't make the picture."; return@launch }
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, "${item.name}: a Celestial Darkroom recipe. Share this picture to Celestial Darkroom, or scan it, to add it.")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(send, "Share recipe card").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            RecipePill("Save to Photos", false, Glyph.FOLDER, Modifier.weight(1f), enabled = card != null && Build.VERSION.SDK_INT >= 29) {
                val b = card ?: return@RecipePill
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { savePicture(context, b, "recipe-" + item.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()) }
                    note = if (ok) "Saved to Pictures/Celestial Darkroom." else "Couldn't save the picture."
                }
            }
        }
    }
}

/** Saves the card to Pictures/Celestial Darkroom (not DCIM/Latent, which is the roll). Android 10+. */
private fun savePicture(ctx: Context, b: Bitmap, name: String): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return runCatching {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Celestial Darkroom"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        r.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)
}

// ---------------------------------------------------------------- scanning

/**
 * A small back-camera preview that looks for a QR code in every frame it can (one at a time, on its own
 * thread). [onText] runs once, on the main thread, with the first code it reads.
 */
private class QrCamera(private val ctx: Context, private val onText: (String) -> Unit, private val onError: (String) -> Unit) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val busy = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    var size: Size = Size(1280, 720); private set

    /** The back camera and the frame size to use, picked before the preview's surface exists. */
    private val chosen: Pair<String, Size>? = runCatching {
        val m = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: return@runCatching null
        val map = m.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // about 1280×720 is plenty for a code, and cheap to read every frame
        val s = sizes.filter { it.width <= 1920 && it.height <= 1080 }.minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) } ?: sizes.firstOrNull()
            ?: return@runCatching null
        id to s
    }.getOrNull()

    init { chosen?.let { size = it.second } }

    @android.annotation.SuppressLint("MissingPermission")
    fun start(st: SurfaceTexture) {
        val (id, s) = chosen ?: run { onError("No back camera to scan with."); return }
        val t = HandlerThread("qr-scan").apply { start() }; thread = t
        val h = Handler(t.looper); handler = h
        st.setDefaultBufferSize(s.width, s.height)
        val preview = Surface(st)
        val r = ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 2); reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (done.get() || !busy.compareAndSet(false, true)) { img.close(); return@setOnImageAvailableListener }
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                val stride = plane.rowStride
                val src = PlanarYUVLuminanceSource(bytes, stride, img.height, 0, 0, img.width, img.height, false)
                val text = RecipeQr.find(src, hard = false)
                if (text != null && done.compareAndSet(false, true)) main.post { onText(text) }
            } catch (_: Exception) {
            } finally { img.close(); busy.set(false) }
        }, h)
        val m = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            m.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    runCatching {
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                session = cs
                                runCatching {
                                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(preview); addTarget(r.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    cs.setRepeatingRequest(req.build(), null, h)
                                }
                            }
                            override fun onConfigureFailed(cs: CameraCaptureSession) { main.post { onError("The camera couldn't start.") } }
                        }, h)
                    }.onFailure { main.post { onError("The camera couldn't start.") } }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; main.post { onError("The camera is busy (error $error).") } }
            }, h)
        }.onFailure { onError("The camera couldn't open.") }
    }

    fun stop() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null; handler = null
    }
}

@Composable
fun RecipeScanScreen(onBack: () -> Unit, onFound: (RecipeIncoming) -> Unit, onOpenFile: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    val found by rememberUpdatedState(onFound)
    val cam = remember {
        QrCamera(context, onText = { text ->
            val item = RecipeQr.read(text)
            if (item != null) { Haptics.click(context); found(RecipeIncoming("from a QR code", RecipeQr.pack(item))) }
            else problem = "That QR code isn't a Celestial Darkroom recipe."
        }, onError = { problem = it })
    }
    DisposableEffect(Unit) { onDispose { cam.stop() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            val got = withContext(Dispatchers.Default) { readSharedPicture(context, uri, "from a picture") }
            reading = false
            if (got.pack != null) found(got) else problem = got.problem
        }
    }
    QrFrame("Scan a recipe", onBack) {
        Text("Point the camera at a recipe QR code, or pick a picture you saved.", color = LatentColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            // the frame comes in landscape; on an upright phone it shows turned, so the view is tall
            val s = cam.size
            AndroidView(modifier = Modifier.fillMaxHeight().aspectRatio(s.height.toFloat() / s.width), factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { cam.start(st) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { cam.stop(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            })
            if (problem.isNotEmpty() || reading) Text(if (reading) "Reading the picture…" else problem, color = Color(0xFFF2EEE6), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xCC111522))
                    .padding(horizontal = 14.dp, vertical = 7.dp))
        }
        RecipePill("Pick a photo", true, Glyph.IMPORT, Modifier.fillMaxWidth(), enabled = !reading) { problem = ""; pick.launch("image/*") }
        RecipePill("Open a recipes file", false, Glyph.FOLDER, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), onClick = onOpenFile)
    }
}
