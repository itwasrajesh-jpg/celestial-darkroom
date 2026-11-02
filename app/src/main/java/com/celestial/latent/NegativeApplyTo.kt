package com.celestial.latent

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Layer
import com.celestial.latent.develop.LayerLooks
import com.celestial.latent.develop.Layers
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.raised

// ---------------------------------------------------------------------------------------------
// 109c: NEGATIVE per layer. Once the photo has layers (PRINT → LAYERS), a row above the looks says
// where NEGATIVE's changes go: ALL, the whole photo with its layers, or one layer only. Every control
// in NEGATIVE (the looks and every tab) then shows and changes that one ([LayerRecipes]).
// ---------------------------------------------------------------------------------------------

/**
 * APPLY TO: ALL · each layer, with its colour. A layer with settings of its own is marked; once
 * [LayerLooks.MAX_EXTRA] layers have their own, another can only be chosen when it already has some
 * (each costs one more develop of the photo).
 */
@Composable
internal fun ApplyToRow(layers: List<Layer>, applyTo: Int?, onPick: (Int?) -> Unit) {
    val chosen = layers.firstOrNull { it.id == applyTo }
    // 109e: hidden ones too: showing one again must not go past the most there can be
    val owning = layers.count { it.recipe != null }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("APPLY TO", color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(end = 2.dp))
            TargetChip("ALL", null, on = chosen == null, enabled = true, own = false) { onPick(null) }
            for (l in layers) {
                val can = l.recipe != null || owning < LayerLooks.MAX_EXTRA
                TargetChip(l.name, Layers.COLOURS[Math.floorMod(l.tint, Layers.COLOURS.size)], on = l.id == chosen?.id, enabled = can, own = l.recipe != null) {
                    if (can) onPick(l.id)
                }
            }
        }
        Text(
            when {
                chosen != null && !chosen.visible -> "${chosen.name} is hidden in LAYERS: its changes show once it is shown again."
                chosen != null -> "Changes here go to ${chosen.name} only. ALL changes the whole photo, ${chosen.name} too."
                owning >= LayerLooks.MAX_EXTRA -> "Changes here go to the whole photo, layers too. ${LayerLooks.MAX_EXTRA} layers have their own settings, the most there can be."
                else -> "Changes here go to the whole photo, layers too. Pick a layer to change only that part."
            },
            color = LatentColors.TextDim, fontSize = 9.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun TargetChip(label: String, tint: Int?, on: Boolean, enabled: Boolean, own: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val tapNow by rememberUpdatedState(onTap)
    Row(
        Modifier.raised(RoundedCornerShape(999.dp), 2.dp).clip(RoundedCornerShape(999.dp)).accentFill(on, LatentColors.Surface)
            .pointerInput(label, on, enabled) { detectTapGestures(onTap = { if (enabled) { Haptics.tick(context); tapNow() } }) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tint != null) Box(Modifier.padding(end = 5.dp).size(7.dp).clip(CircleShape).background(Color(tint)))
        Text(label, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1,
            color = when { on -> LatentColors.AmberInk; enabled -> LatentColors.TextBright; else -> LatentColors.Line })
        // a layer with settings of its own
        if (own) Box(Modifier.padding(start = 5.dp).size(4.dp).clip(CircleShape).background(if (on) LatentColors.AmberInk else LatentColors.Amber))
    }
}
