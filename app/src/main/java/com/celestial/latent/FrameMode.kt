package com.celestial.latent

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Framing
import com.celestial.latent.ui.LatentColors
import java.util.Locale
import kotlin.math.roundToInt

/** How far straightening goes either way, in degrees. */
const val STRAIGHTEN_LIMIT = 10f

/**
 * FRAME: turning, flipping and straightening a photo, in place of the control sheet so the photo
 * stays as large as it can be while you line up a horizon. Turns and flips apply at once;
 * straightening turns the picture live under the finger ([onLive]) and is kept when you let go
 * ([onCommit]).
 */
@Composable
fun FramePanel(
    framing: Framing,
    live: Float?,
    onLive: (Float?) -> Unit,
    onCommit: (Framing) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val angle = live ?: framing.straighten
    Column(modifier.background(LatentColors.Background).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("FRAME", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp)
            FrameChip("done", on = true) { onDone() }
        }
        // turn, flip, start again
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FrameChip("↺ left") { onCommit(framing.copy(turns = framing.turns - 1)) }
            FrameChip("↻ right") { onCommit(framing.copy(turns = framing.turns + 1)) }
            FrameChip("flip", on = framing.flip) { onCommit(framing.copy(flip = !framing.flip)) }
            FrameChip("reset", enabled = !framing.isIdentity) { onCommit(Framing()) }
        }
        // straighten
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("straighten  ${String.format(Locale.US, "%+.1f", angle)}°", color = LatentColors.Text, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // a tenth of a degree at a time, for the last little bit
                FrameChip("−0.1") { onCommit(framing.copy(straighten = step(framing.straighten - 0.1f))) }
                FrameChip("+0.1") { onCommit(framing.copy(straighten = step(framing.straighten + 0.1f))) }
                FrameChip("level", enabled = framing.straighten != 0f) { onCommit(framing.copy(straighten = 0f)) }
            }
        }
        Slider(
            value = angle,
            onValueChange = { onLive(it) },
            onValueChangeFinished = {
                val v = step(live ?: framing.straighten)
                onLive(null)
                if (v != framing.straighten) { Haptics.tick(context); onCommit(framing.copy(straighten = v)) }
            },
            valueRange = -STRAIGHTEN_LIMIT..STRAIGHTEN_LIMIT,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Surface),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Kept with this photo. Straightening crops a little, so no corner is blank — the print size can win it back. Painting turns with the picture.",
            color = LatentColors.TextDim, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

/** Straightening in tenths of a degree, within the limit. */
private fun step(v: Float): Float = ((v * 10f).roundToInt() / 10f).coerceIn(-STRAIGHTEN_LIMIT, STRAIGHTEN_LIMIT)

@Composable
private fun FrameChip(label: String, on: Boolean = false, enabled: Boolean = true, onTap: () -> Unit) {
    val context = LocalContext.current
    Text(
        label, fontSize = 11.sp,
        color = when { on -> LatentColors.AmberInk; enabled -> LatentColors.Text; else -> LatentColors.Line },
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
            .pointerInput(label, on, enabled) { detectTapGestures(onTap = { if (enabled) { Haptics.tick(context); onTap() } }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
