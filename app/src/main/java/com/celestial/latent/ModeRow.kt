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
import androidx.compose.runtime.Composable
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
