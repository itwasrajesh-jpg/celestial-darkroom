@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import com.celestial.latent.ui.OnPhoto
import com.celestial.latent.ui.floating

/**
 * The look selector along the bottom of the viewfinder.
 *
 * These are the engine's own authored looks, not bare film names: each carries the per-stock
 * grain, halation and coupler settings, without which every film renders with Portra 400's grain.
 */
@Composable
fun FilmStrip(
    selected: String, modifier: Modifier = Modifier, onLongPress: () -> Unit = {},
    /** 77a: folded, the cards are one pill naming the film in use; a swipe down folds, a swipe up (or a tap) opens. */
    folded: Boolean = false, onFold: (Boolean) -> Unit = {},
    onSelect: (String) -> Unit,
) {
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
    // Only an up or down swipe on the strip itself folds or opens it: sideways still scrolls the
    // cards, and the picture's own taps and slides are untouched.
    val swipe = Modifier.pointerInput(folded) {
        val need = 24.dp.toPx()
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (!folded && total > need) { Haptics.tick(context); onFold(true) }
                else if (folded && total < -need) { Haptics.tick(context); onFold(false) }
            },
        ) { change, dy -> change.consume(); total += dy }
    }
    if (folded) {
        val current = if (selected.startsWith("stock:")) selected.removePrefix("stock:")
            else looks.firstOrNull { it.id == selected }?.name?.substringBefore(" — ") ?: "FILM"
        Box(modifier.then(swipe), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x8C000000))
                    .combinedClickable(
                        onClick = { Haptics.tick(context); onFold(false) },
                        onLongClick = { Haptics.click(context); onLongPress() },
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(current.uppercase(), color = OnPhoto.TextBright, fontSize = 11.sp, letterSpacing = 1.sp)
                // a small up-chevron: there is more above
                Canvas(Modifier.size(10.dp, 6.dp)) {
                    val w = size.width; val h = size.height
                    drawPath(Path().apply { moveTo(0f, h); lineTo(w / 2f, 0f); lineTo(w, h) }, OnPhoto.TextBright,
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        return
    }
    Column(
        modifier
            .then(swipe)
            // A soft fade behind the strip so the names stay readable over a bright scene
            // without putting a hard bar across the photograph.
            // 87a: not under Clay's pale cards, which carry their own dark words
            .then(if (LatentColors.Light) Modifier else Modifier.background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0x66000000), Color(0x99000000)),
                ),
            )),
    ) {
    // the grabber: these cards can be swiped down out of the way (77a)
    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp).size(30.dp, 3.dp).clip(RoundedCornerShape(2.dp))
        .background(OnPhoto.TextBright.copy(alpha = 0.8f)))
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ours.forEach { (name, _, kind) ->
            val id = "stock:" + name
            val on = id == selected
            androidx.compose.foundation.layout.Column(
                // 87a: frosted cards in Midnight and Clay (the theme's tint, a lit edge, a soft shadow)
                modifier = Modifier.floating(RoundedCornerShape(6.dp), 4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) LatentColors.Amber else LatentColors.Frost)
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
                // 87a: frosted cards in Midnight and Clay (the theme's tint, a lit edge, a soft shadow)
                modifier = Modifier.floating(RoundedCornerShape(6.dp), 4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) LatentColors.Amber else LatentColors.Frost)
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
}
