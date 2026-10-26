package com.celestial.latent.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestial.latent.R

/**
 * 87a: one theme's colours. The first eight are the names the whole app has always used; the rest
 * are the layers Midnight and Clay add (in Darkroom they are the colours those places already had,
 * so Darkroom looks exactly as before).
 */
class Palette(
    val background: Color, val surface: Color, val line: Color,
    val textDim: Color, val text: Color, val textBright: Color,
    val amber: Color, val amberInk: Color,
    /** the camera's settings row (EV · S · ISO · WB · FOCUS) */
    val bar: Color,
    /** the shutter dock at the bottom of the camera */
    val dock: Color,
    /** the darkroom's control sheet under the photo */
    val panel: Color,
    /** behind the photo in the darkroom: always dark and near neutral, so a print is judged fairly */
    val mat: Color,
    /** the film cards over the viewfinder */
    val frost: Color,
    val shadow: Color, val rim: Color, val glow: Color,
    /** shadows, lit edges and the glow are drawn */
    val depth: Boolean,
    /** a light theme: dark status-bar icons, and words over a photo keep their own dark set ([OnPhoto]) */
    val light: Boolean,
)

enum class AppTheme(val id: String, val label: String) {
    DARKROOM("darkroom", "Darkroom"), MIDNIGHT("midnight", "Midnight"), CLAY("clay", "Clay");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: DARKROOM }
}

private val DARKROOM = Palette(
    background = Color(0xFF161615), surface = Color(0xFF2C2C2A), line = Color(0xFF5F5E5A),
    textDim = Color(0xFF888780), text = Color(0xFFB4B2A9), textBright = Color(0xFFF1EFE8),
    amber = Color(0xFFFAC775), amberInk = Color(0xFF412402),
    bar = Color(0xFF161615), dock = Color(0xFF161615), panel = Color(0xFF1D1D1B), mat = Color(0xFF2C2C2A),
    frost = Color(0x73000000),
    shadow = Color.Transparent, rim = Color.Transparent, glow = Color.Transparent,
    depth = false, light = false,
)

// Celestial's navy palette (26 Oct): #111522 #1E263B #303D59 #536684 #98A9C4 #DCE3EF, layered and with
// depth as in the approved "Midnight premium" proof. The faint words sit a step above #536684, which
// was too dim for help text on the phone screen. The accent is the palest tone, a touch above the
// words, so a chosen value still stands out from the rest.
private val MIDNIGHT = Palette(
    background = Color(0xFF111522), surface = Color(0xFF303D59), line = Color(0xFF536684),
    textDim = Color(0xFF7B8CAA), text = Color(0xFF98A9C4), textBright = Color(0xFFCFD8E7),
    amber = Color(0xFFEDF1F8), amberInk = Color(0xFF111522),
    bar = Color(0xFF1E263B), dock = Color(0xFF303D59), panel = Color(0xFF1E263B), mat = Color(0xFF0E1119),
    frost = Color(0xB8303D59),
    shadow = Color(0xFF05070D), rim = Color(0xFF536684), glow = Color(0xFF98A9C4),
    depth = true, light = false,
)

// "Clay" (26 Oct proof): pale blue, white clay tiles, a blue accent. The darkroom photo stays on a dark mat.
private val CLAY = Palette(
    background = Color(0xFFD9E3F1), surface = Color(0xFFFBFCFE), line = Color(0xFFB3C1D9),
    textDim = Color(0xFF6A7898), text = Color(0xFF45536F), textBright = Color(0xFF1E263B),
    amber = Color(0xFF5B78E6), amberInk = Color(0xFFFFFFFF),
    bar = Color(0xFFFBFCFE), dock = Color(0xFFD9E3F1), panel = Color(0xFFE7EDF6), mat = Color(0xFF16181D),
    frost = Color(0xD9F3F6FB),
    shadow = Color(0xFF7F93BC), rim = Color(0xFFFFFFFF), glow = Color(0xFFFFFFFF),
    depth = true, light = true,
)

// Words over a photo in Clay: Midnight's light words, with Clay's paler blue as the accent.
private val CLAY_ON_PHOTO = Palette(
    background = Color(0xFF111522), surface = Color(0xFF303D59), line = Color(0xFF8A9AB8),
    textDim = Color(0xFFA9B6CC), text = Color(0xFFD3DCEB), textBright = Color(0xFFF4F7FC),
    amber = Color(0xFF8EA6F7), amberInk = Color(0xFF111522),
    bar = Color(0xFF1E263B), dock = Color(0xFF303D59), panel = Color(0xFF1E263B), mat = Color(0xFF16181D),
    frost = Color(0x99111522),
    shadow = Color(0xFF05070D), rim = Color(0xFF536684), glow = Color.Transparent,
    depth = false, light = false,
)

private fun paletteOf(t: AppTheme) = when (t) { AppTheme.DARKROOM -> DARKROOM; AppTheme.MIDNIGHT -> MIDNIGHT; AppTheme.CLAY -> CLAY }

// Latent palette: dark, warm-neutral, one amber accent (matches the mockups).
// 87a: the colours now follow the chosen theme. Each is read from snapshot state, so a screen redraws
// the moment the theme changes in Settings; "Amber" is the theme's accent whatever its hue.
object LatentColors {
    var theme by mutableStateOf(AppTheme.DARKROOM)
        private set
    val palette: Palette get() = paletteOf(theme)

    val Background get() = palette.background
    val Surface get() = palette.surface
    val Line get() = palette.line
    val TextDim get() = palette.textDim
    val Text get() = palette.text
    val TextBright get() = palette.textBright
    val Amber get() = palette.amber
    val AmberInk get() = palette.amberInk
    val Bar get() = palette.bar
    val Dock get() = palette.dock
    val Panel get() = palette.panel
    val Mat get() = palette.mat
    val Frost get() = palette.frost
    val Light get() = palette.light

    private const val PREFS = "latent_ui_theme"

    /** Read once at start, before the first frame, so the app never flashes the wrong colours. */
    fun load(ctx: Context) {
        theme = AppTheme.of(runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", null) }.getOrNull())
    }

    fun set(ctx: Context, t: AppTheme) {
        theme = t
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", t.id).apply() }
    }
}

/**
 * 87a: colours for words and pills drawn over a photo or the live viewfinder. In the dark themes they
 * are the theme's own; in Clay they stay light, because dark words on a dark pill over a photo can't be read.
 */
object OnPhoto {
    private val palette: Palette get() = LatentColors.palette.let { if (it.light) CLAY_ON_PHOTO else it }
    val Background get() = palette.background
    val Surface get() = palette.surface
    val Line get() = palette.line
    val TextDim get() = palette.textDim
    val Text get() = palette.text
    val TextBright get() = palette.textBright
    val Amber get() = palette.amber
    val AmberInk get() = palette.amberInk
}

// ---- 87a depth: shadows, lit edges, glow. Each does nothing in Darkroom. ----

/** A soft shadow in the theme's shadow colour (blue-black in Midnight, soft blue in Clay): the thing floats. */
fun Modifier.raised(shape: Shape = RoundedCornerShape(12.dp), lift: Dp = 6.dp): Modifier {
    val p = LatentColors.palette
    return if (!p.depth) this else shadow(lift, shape, clip = false, ambientColor = p.shadow, spotColor = p.shadow)
}

/** A 1 px edge lit from above: bright along the top, fading out down the sides. */
fun Modifier.litEdge(shape: Shape = RoundedCornerShape(12.dp), strength: Float = 1f): Modifier {
    val p = LatentColors.palette
    if (!p.depth) return this
    val rim = p.rim.copy(alpha = (if (p.light) 0.9f else 0.75f) * strength)
    return border(1.dp, Brush.verticalGradient(0f to rim, 0.45f to rim.copy(alpha = 0f), 1f to Color.Transparent), shape)
}

/** A floating thing: shadow under it, lit edge on top. */
fun Modifier.floating(shape: Shape = RoundedCornerShape(12.dp), lift: Dp = 6.dp): Modifier = raised(shape, lift).litEdge(shape)

/** Light falling from the top-left corner, as in the proofs. Draw it over a background. */
fun Modifier.glow(strength: Float = 1f): Modifier {
    val p = LatentColors.palette
    if (!p.depth) return this
    val g = p.glow.copy(alpha = (if (p.light) 0.55f else 0.22f) * strength)
    return drawBehind {
        drawRect(Brush.radialGradient(listOf(g, g.copy(alpha = 0f)), center = Offset.Zero, radius = size.maxDimension.coerceAtLeast(1f) * 0.85f))
    }
}

/**
 * The top edge of a panel that sits higher than what is above it (the shutter dock, the darkroom sheet):
 * a shadow cast upward over the thing above, and a lit line along the edge (leave it out for a rounded
 * panel and give that a [litEdge] instead).
 */
fun Modifier.liftedTop(height: Dp = 14.dp, rim: Boolean = true): Modifier {
    val p = LatentColors.palette
    if (!p.depth) return this
    val shade = p.shadow.copy(alpha = if (p.light) 0.35f else 0.55f)
    val edge = p.rim.copy(alpha = if (p.light) 0.9f else 0.6f)
    return drawBehind {
        val h = height.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, shade), startY = -h, endY = 0f), Offset(0f, -h), Size(size.width, h))
        if (rim) drawRect(edge, Offset.Zero, Size(size.width, 1.dp.toPx()))
    }
}

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
    val p = LatentColors.palette
    val scheme = if (p.light) lightColorScheme(
        primary = p.amber, onPrimary = p.amberInk, background = p.background, onBackground = p.textBright,
        surface = p.surface, onSurface = p.textBright,
    ) else darkColorScheme(
        primary = p.amber, onPrimary = p.amberInk, background = p.background, onBackground = p.textBright,
        surface = p.surface, onSurface = p.textBright,
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
