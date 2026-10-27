package com.celestial.latent

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 88a: a frame tapped in the roll grows into the darkroom, and shrinks back into its place in the roll
 * on the way out (the approved proof, visual-2/proof/2-photo-opens.mp4). Screens report where their
 * pictures are; [PhotoFlightLayer], drawn over every screen, flies the picture between the two.
 * Opening, the flown picture waits on the darkroom's mat until the print is ready, then fades into it.
 */
internal object PhotoFlight {
    class Trip(val uri: Uri, val picture: ImageBitmap, val aspect: Float, val from: Rect, val back: Boolean)
    var trip by mutableStateOf<Trip?>(null)
        private set
    /** The picture being flown is hidden on the screens while this is its uri (the flight draws it instead). */
    var covering by mutableStateOf<Uri?>(null)
        private set
    // Reported by the screens as they lay out; plain fields, read by the flight frame by frame.
    private val tiles = HashMap<Uri, Rect>()
    private var room: Pair<Uri, Rect>? = null
    private var shown: Bitmap? = null
    /** True once the darkroom shows its own print, so the flown picture can fade into it. */
    var printReady by mutableStateOf(false)

    fun tile(uri: Uri, at: Rect) { tiles[uri] = at }
    fun room(uri: Uri, at: Rect) { room = uri to at }
    fun print(print: Bitmap?) { shown = print }

    /** From the roll: [picture] is the tile's own picture. */
    fun open(uri: Uri, picture: Bitmap?) {
        val from = tiles[uri]
        if (picture == null || from == null || !motionOn()) return
        printReady = false; room = null
        trip = Trip(uri, picture.asImageBitmap(), picture.width.toFloat() / picture.height, from, back = false); covering = uri
    }

    /** Leaving the darkroom for the roll: the print shrinks back into its tile. */
    fun back(uri: Uri) {
        val (u, r) = room ?: return
        val print = shown
        if (u != uri || print == null || !motionOn()) return
        val a = print.width.toFloat() / print.height
        tiles.remove(uri)
        trip = Trip(uri, print.asImageBitmap(), a, fit(r, a), back = true); covering = uri
    }

    /** Where the flight lands, once the screen it goes to has laid out. */
    internal fun landing(t: Trip): Rect? =
        if (t.back) tiles[t.uri] else room?.takeIf { it.first == t.uri }?.second?.let { fit(it, t.aspect) }

    internal fun reveal() { covering = null }
    internal fun done() { trip = null; covering = null }

    private fun fit(r: Rect, aspect: Float): Rect {
        val f = fitRect(r.width, r.height, aspect)
        return Rect(Offset(r.left + f.left, r.top + f.top), Size(f.width, f.height))
    }
}

/** Fast out, gentle landing. */
private val Land = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

@Composable
internal fun PhotoFlightLayer() {
    val t = PhotoFlight.trip ?: return
    val p = remember(t) { Animatable(0f) }
    val fade = remember(t) { Animatable(1f) }
    var to by remember(t) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(t) {
        // the screen it flies to lays out within a few frames (the roll may first fill in and scroll to the
        // frame, 88d); if it never shows the picture, let go
        var frames = 0
        while (to == null && frames < 45) { withFrameNanos { }; to = PhotoFlight.landing(t); frames++ }
        if (to == null) { PhotoFlight.done(); return@LaunchedEffect }
        p.animateTo(1f, tween(if (t.back) 400 else 450, easing = Land))
        if (!t.back) {
            // wait on the mat for the darkroom's own print (at most 1.5 s), then fade into it
            var waited = 0L; val start = withFrameNanos { it }
            while (!PhotoFlight.printReady && waited < 1_500_000_000L) waited = withFrameNanos { it } - start
            PhotoFlight.reveal()
            fade.animateTo(0f, tween(250))
        }
        PhotoFlight.done()
    }
    // flying back, follow the tile as the roll settles under it (it may still be scrolling into place)
    val end = (if (t.back) PhotoFlight.landing(t) else null) ?: to ?: return
    Canvas(Modifier.fillMaxSize()) {
        val k = p.value
        val r = Rect(lerp(t.from.left, end.left, k), lerp(t.from.top, end.top, k), lerp(t.from.right, end.right, k), lerp(t.from.bottom, end.bottom, k))
        // a soft shadow while it is in the air
        val lift = sin(PI * k).toFloat()
        if (lift > 0.01f) drawRoundRect(Color.Black.copy(alpha = 0.28f * lift * fade.value), Offset(r.left + 3.dp.toPx(), r.top + 10.dp.toPx()), r.size, CornerRadius(12.dp.toPx()))
        val corner = 4.dp.toPx() * (if (t.back) k else 1f - k)
        clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(corner))) }) {
            // the picture fills its rectangle (cropped like the roll's tiles), so the two ends match
            val iw = t.picture.width.toFloat(); val ih = t.picture.height.toFloat()
            val s = maxOf(r.width / iw, r.height / ih)
            val w = iw * s; val h = ih * s
            drawImage(t.picture, dstOffset = IntOffset((r.left + (r.width - w) / 2f).toInt(), (r.top + (r.height - h) / 2f).toInt()),
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)), alpha = fade.value)
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
