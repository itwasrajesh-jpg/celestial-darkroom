@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.celestial.latent.develop.Presets
import com.celestial.latent.ui.LatentColors

/**
 * The look selector along the bottom of the viewfinder.
 *
 * These are the engine's own authored looks, not bare film names: each carries the per-stock
 * grain, halation and coupler settings, without which every film renders with Portra 400's grain.
 */
@Composable
fun FilmStrip(selected: String, modifier: Modifier = Modifier, onLongPress: () -> Unit = {}, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val looks = remember { Presets.all(context) }
    // Films built in the film builder come first: they are yours, and they were invisible
    // before this — saved into the recipe store, which nothing on this screen ever read.
    // Keyed on how many recipes are stored, so a film built in the builder appears here
    // without waiting for the app to be restarted.
    val ours = remember(com.celestial.latent.develop.Recipes.names(context).size) {
        // films built here, then recipes saved in the darkroom (72a: those were never listed anywhere)
        com.celestial.latent.develop.Recipes.stocks(context).map { Triple(it.first, it.second, "built here") } +
            com.celestial.latent.develop.Recipes.saved(context).map { Triple(it.first, it.second, "your recipe") }
    }
    Row(
        modifier
            // A soft fade behind the strip so the names stay readable over a bright scene
            // without putting a hard bar across the photograph.
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0x66000000), Color(0x99000000)),
                ),
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ours.forEach { (name, _, kind) ->
            val id = "stock:" + name
            val on = id == selected
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (on) LatentColors.Amber else Color(0x73000000))
                    .combinedClickable(
                        onClick = { if (!on) { Haptics.tick(context); onSelect(id) } },
                        onLongClick = { Haptics.click(context); onLongPress() },
                    )
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(name.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(kind, color = if (on) LatentColors.AmberInk.copy(alpha = 0.7f) else LatentColors.Text, fontSize = 8.sp, letterSpacing = 0.5.sp)
            }
        }
        looks.forEach { preset ->
            val id = preset.id
            // "Portra 400 — Wedding Warm" reads as two lines: the stock, then the look.
            val label = preset.name.substringBefore(" — ")
            val on = id == selected
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (on) LatentColors.Amber else Color(0x73000000))
                    .combinedClickable(
                        onClick = { if (!on) { Haptics.tick(context); onSelect(id) } },
                        onLongClick = { Haptics.click(context); onLongPress() },
                    )
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(label.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(
                    preset.name.substringAfter(" — ", preset.group),
                    color = if (on) LatentColors.AmberInk.copy(alpha = 0.7f) else LatentColors.Text,
                    fontSize = 8.sp, letterSpacing = 0.5.sp,
                )
            }
        }
    }
}
