package com.celestial.latent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Layer
import com.celestial.latent.develop.Layers
import com.celestial.latent.ui.LatentColors

// ---------------------------------------------------------------------------------------------
// 109g: layers in the other PRINT steps. A painting step (DODGE & BURN, SOFTEN, FOG, LIGHT, WET)
// gets an IN row: ALL, or one of the photo's layers. With a layer chosen, every stroke, fill and
// gradient is cut to that layer's area as it is painted, the way the depth bar's foreground /
// background already cuts it. What is painted stays painted: pick another layer and paint again to
// mix (heavy fog IN SKY, light fog IN BACKGROUND). Proof: layers/109g-layers-in-print/.
// ---------------------------------------------------------------------------------------------

/** The APPLY TO row (109g's IN row): ALL · each layer with its colour, and a line saying where painting lands. */
@Composable
internal fun PaintInRow(
    layers: List<Layer>, chosen: Int?,
    everywhere: String = "Painting lands everywhere. Pick a layer to paint only inside it.",
    inside: (String) -> String = { "Painting only in $it: strokes stop at its edges." },
    onPick: (Int?) -> Unit,
) {
    val on = layers.firstOrNull { it.id == chosen }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 110a: the same word as NEGATIVE's row (it said IN)
            Text("APPLY TO", color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(end = 2.dp))
            TargetChip("ALL", null, on = on == null, enabled = true, own = false) { onPick(null) }
            for (l in layers) TargetChip(l.name, Layers.COLOURS[Math.floorMod(l.tint, Layers.COLOURS.size)], on = l.id == on?.id, enabled = true, own = false) {
                onPick(if (l.id == on?.id) null else l.id)
            }
        }
        Text(
            if (on == null) everywhere else inside(on.name),
            color = LatentColors.TextDim, fontSize = 9.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 3.dp),
        )
    }
}

internal object PaintIn {
    /** The layers a step can paint in: any with an area (a hidden one too: its area is still there). */
    fun usable(layers: List<Layer>?): List<Layer> = layers?.filter { !it.mask.isBlank } ?: emptyList()

    /** The layer's area on a painting's grid (w × h), 0 outside … 1 inside; a mask on another grid is sampled. */
    fun weights(l: Layer, w: Int, h: Int): FloatArray {
        val m = l.mask
        val same = m.width == w && m.height == h
        return FloatArray(w * h) { k ->
            val v = if (same) m.stops[k] else m.sample((k % w + 0.5f) / w, (k / w + 0.5f) / h)
            if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
        }
    }
}
