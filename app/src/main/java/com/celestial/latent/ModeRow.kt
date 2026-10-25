@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors

/**
 * 84a: the camera's modes in one row, Xiaomi style. PRO (RAW, the camera as it always was), FAST and
 * CINEMA live on the camera screen; NIGHT and AUTO are Xiaomi's own JPEG processing on the extension
 * screen. Portrait is left out: Xiaomi's Portrait ignores the zoom we send, so only 1× ever works.
 */
object CameraModes {
    const val NIGHT = "night"; const val AUTO = "auto"; const val PRO = "pro"; const val FAST = "fast"; const val CINEMA = "cinema"
    val ALL = listOf(NIGHT to "NIGHT", AUTO to "AUTO", PRO to "PRO", FAST to "FAST", CINEMA to "CINEMA")

    private fun prefs(context: android.content.Context) = context.getSharedPreferences("latent_camera_ui", android.content.Context.MODE_PRIVATE)
    /** The Xiaomi mode the app was last left in ("night" / "auto"), or "" for the camera screen. */
    fun xiaomi(context: android.content.Context): String = runCatching { prefs(context).getString("xiaomi_mode", "") ?: "" }.getOrDefault("")
    fun setXiaomi(context: android.content.Context, mode: String) { runCatching { prefs(context).edit().putString("xiaomi_mode", mode).apply() } }
    /** The camera screen's mode, read from the settings that hold it. */
    fun of(settings: AppSettings): String = when { settings.fastShot -> FAST; settings.cinema -> CINEMA; else -> PRO }
    /** PRO, FAST and CINEMA are one at a time. */
    fun apply(settings: AppSettings, mode: String): AppSettings = settings.copy(fastShot = mode == FAST, cinema = mode == CINEMA)
}

/** The chosen mode sits in the centre, the others run off either side. Tap a name, or swipe across the row. */
@Composable
fun ModeRow(current: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val i = CameraModes.ALL.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val swipe = Modifier.pointerInput(current) {
        val need = 40.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                // a swipe to the left brings the next mode on the right into the centre
                val next = if (total < -need) i + 1 else if (total > need) i - 1 else i
                if (next != i && next in CameraModes.ALL.indices) { Haptics.tick(context); onPick(CameraModes.ALL[next].first) }
            },
        ) { change, dx -> change.consume(); total += dx }
    }
    Layout(
        content = {
            CameraModes.ALL.forEach { (id, label) ->
                val on = id == current
                Column(
                    Modifier.combinedClickable(onClick = { if (!on) { Haptics.tick(context); onPick(id) } }).padding(horizontal = 13.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, color = if (on) LatentColors.Amber else LatentColors.Text, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                    Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(if (on) LatentColors.Amber else Color.Transparent))
                }
            }
        },
        modifier = modifier.fillMaxWidth().clipToBounds().then(swipe),
    ) { measurables, c ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val h = placeables.maxOf { it.height }
        layout(c.maxWidth, h) {
            var x = c.maxWidth / 2 - placeables[i].width / 2 - placeables.take(i).sumOf { it.width }
            placeables.forEach { p -> p.place(x, 0); x += p.width }
        }
    }
}

/**
 * 85a: while the camera restarts for another mode, the last picture stays on screen, softly blurred,
 * instead of black, and fades out on the new mode's first frame (as Xiaomi's camera does).
 * Each switch also logs how long it took from the tap to that first frame.
 */
object ModeSwitch {
    var frozen by mutableStateOf<ImageBitmap?>(null)
        private set
    @Volatile private var startedAt = 0L
    @Volatile private var what = ""

    /** Takes the picture now on [view] (a TextureView, or the film preview's SurfaceView), then runs [then]. */
    fun begin(view: android.view.View?, what: String, then: () -> Unit) {
        startedAt = android.os.SystemClock.elapsedRealtime(); this.what = what
        val w = ((view?.width ?: 0) / 6).coerceAtLeast(1); val h = ((view?.height ?: 0) / 6).coerceAtLeast(1)
        when {
            view is android.view.TextureView && view.isAvailable -> {
                frozen = runCatching { view.getBitmap(w, h)?.asImageBitmap() }.getOrNull(); then()
            }
            view is android.view.SurfaceView && view.holder.surface?.isValid == true -> {
                // the film preview draws with GL into a SurfaceView: copy its pixels, then switch
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                try {
                    android.view.PixelCopy.request(view, bmp, { r -> if (r == android.view.PixelCopy.SUCCESS) frozen = bmp.asImageBitmap(); then() }, main)
                } catch (_: Exception) { then() }
            }
            else -> then()
        }
    }

    /** The new mode's first frame: log the time and let the picture show. Any thread. */
    fun firstFrame() {
        val t0 = startedAt
        if (t0 == 0L) return
        startedAt = 0L
        android.util.Log.i("Latent", "mode switch: $what in ${android.os.SystemClock.elapsedRealtime() - t0} ms (tap to first picture)")
        frozen = null
    }

    internal fun giveUp() { if (startedAt != 0L) android.util.Log.i("Latent", "mode switch: $what — no first picture after 3 s"); startedAt = 0L; frozen = null }
}

/** Draws [ModeSwitch.frozen] over a viewfinder: blurred, fading out once the new picture arrives. */
@Composable
fun FreezeFrame() {
    val f = ModeSwitch.frozen
    var held by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(f) {
        if (f != null) {
            held = f
            // never leave the picture frozen if the camera does not come back (an error, the app paused)
            kotlinx.coroutines.delay(3000)
            if (ModeSwitch.frozen === f) ModeSwitch.giveUp()
        }
    }
    val alpha by animateFloatAsState(if (f != null) 1f else 0f, tween(if (f != null) 0 else 220), label = "freeze",
        finishedListener = { if (it == 0f) held = null })
    (f ?: held)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, alpha = alpha, modifier = Modifier.fillMaxSize().blur(16.dp)) }
}
