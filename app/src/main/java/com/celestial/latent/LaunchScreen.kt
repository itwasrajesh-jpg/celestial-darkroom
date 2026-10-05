package com.celestial.latent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The opening: a latent image coming up.
 *
 * A latent image is the invisible one on exposed film, there before development makes it
 * visible. So the name does exactly that — it is already on the screen at no density, and
 * over three quarters of a second it rises out of black, shadows first, warming as it comes,
 * the way a print comes up in the tray.
 *
 * It is free: the camera opens behind it, so this covers the startup rather than adding to it.
 * A tap anywhere skips it, and it can be turned off altogether in settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LaunchOverlay(onDone: () -> Unit) {
    val safelight = remember { Animatable(0f) }
    val density = remember { Animatable(0f) }
    val scale = remember { Animatable(0.985f) }
    val byline = remember { Animatable(0f) }
    val whole = remember { Animatable(1f) }
    val finished = remember { booleanArrayOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun finish() {
        if (finished[0]) return
        finished[0] = true
        whole.animateTo(0f, tween(360, easing = LinearEasing))
        onDone()
    }

    LaunchedEffect(Unit) {
        // A breath of safelight before anything appears.
        launch { delay(120); safelight.animateTo(0.05f, tween(260)); delay(300); safelight.animateTo(0f, tween(500)) }
        delay(300)
        // The image comes up: not a fade, a rise.
        launch { scale.animateTo(1f, tween(900)) }
        density.animateTo(1f, tween(760))
        byline.animateTo(1f, tween(420))
        delay(620)
        finish()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0D0C0A)).alpha(whole.value)
            // A tap anywhere skips it. An animation you cannot escape stops being pleasant
            // by the third day.
            .combinedClickable(onClick = { scope.launch { finish() } }),
        contentAlignment = Alignment.Center,
    ) {
        // The safelight, barely there.
        Box(Modifier.fillMaxSize().background(LatentColors.Amber).alpha(safelight.value))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Two lines: the full name at the old size would be far wider than a phone.
            Text(
                "CELESTIAL",
                color = LatentColors.TextDim,
                fontSize = 11.sp,
                letterSpacing = 8.sp,
                modifier = Modifier.padding(bottom = 10.dp).alpha(byline.value),
            )
            Text(
                "DARKROOM",
                color = developing(density.value),
                fontSize = 30.sp,
                letterSpacing = 13.sp,
                modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            )
            Text(
                "A FILM CAMERA",
                color = LatentColors.TextDim,
                fontSize = 10.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp).alpha(byline.value),
            )
        }
    }

}

/**
 * The colour of a print coming up: the shadows arrive first and the warmth last, so the word
 * passes through a cool grey before settling at the app's own paper white. A plain fade would
 * lighten every channel together and look like a dissolve rather than a development.
 */
internal fun developing(t: Float): Color {
    val p = t.coerceIn(0f, 1f)
    // Shadows rise faster than highlights, as density does.
    val level = Math.pow(p.toDouble(), 0.62).toFloat()
    val warmth = Math.pow(p.toDouble(), 2.2).toFloat()
    val r = 0.28f + 0.67f * level + 0.05f * warmth
    val g = 0.28f + 0.63f * level + 0.01f * warmth
    val b = 0.28f + 0.58f * level - 0.02f * warmth
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha = level)
}
