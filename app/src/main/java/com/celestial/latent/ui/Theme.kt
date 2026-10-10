package com.celestial.latent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.celestial.latent.R

// Latent palette: dark, warm-neutral, one amber accent (matches the mockups).
object LatentColors {
    val Background = Color(0xFF161615)
    val Surface = Color(0xFF2C2C2A)
    val Line = Color(0xFF5F5E5A)
    val TextDim = Color(0xFF888780)
    val Text = Color(0xFFB4B2A9)
    val TextBright = Color(0xFFF1EFE8)
    val Amber = Color(0xFFFAC775)
    val AmberInk = Color(0xFF412402)
}

private val scheme = darkColorScheme(
    primary = LatentColors.Amber,
    onPrimary = LatentColors.AmberInk,
    background = LatentColors.Background,
    onBackground = LatentColors.TextBright,
    surface = LatentColors.Surface,
    onSurface = LatentColors.TextBright,
)

/**
 * The app's own font (61d): Roboto, bundled, so every phone draws the same letters. Without it each
 * phone used its own (MiSans on the Xiaomi, thinner and wider), and widths measured on one phone
 * did not hold on another. Roboto is what the rows were measured with. SIL OFL 1.1, see NOTICE.md.
 */
val Roboto = FontFamily(
    Font(R.font.roboto_light, FontWeight.Light),
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold),
)

private fun TextStyle.roboto() = copy(fontFamily = Roboto)

/** Material's type scale with every style in [Roboto], so plain Text and the components agree. */
private val type = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.roboto(), displayMedium = t.displayMedium.roboto(), displaySmall = t.displaySmall.roboto(),
        headlineLarge = t.headlineLarge.roboto(), headlineMedium = t.headlineMedium.roboto(), headlineSmall = t.headlineSmall.roboto(),
        titleLarge = t.titleLarge.roboto(), titleMedium = t.titleMedium.roboto(), titleSmall = t.titleSmall.roboto(),
        bodyLarge = t.bodyLarge.roboto(), bodyMedium = t.bodyMedium.roboto(), bodySmall = t.bodySmall.roboto(),
        labelLarge = t.labelLarge.roboto(), labelMedium = t.labelMedium.roboto(), labelSmall = t.labelSmall.roboto(),
    )
}

@Composable
fun LatentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
