package com.celestial.latent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.ui.floating

/**
 * 92a: the end flap of the film box, as photographers tucked it into the camera's back to remember what
 * was loaded (approved proof visual-4/1). Our own printed colours per stock, no maker's artwork: a band,
 * the name, and the small print a box carries. The colours belong to the box, so every theme shows the same.
 */
data class FilmBox(val name: String, val small: String, val paper: Color, val band: Color, val ink: Color)

object FilmBoxes {
    private val cream = Color(0xFFEFE6D2)
    private val ink = Color(0xFF2A2420)
    private val white = Color(0xFFF4F1EA)
    private val cine = Color(0xFF26272B)

    /** The box for an engine film id ([Develop.FILMS]); [name] replaces the stock's name (a film built here). */
    fun of(film: String, name: String? = null): FilmBox {
        val b = when (film) {
            "kodak_portra_160" -> FilmBox("PORTRA 160", "36 EXP · ISO 160 · C-41", cream, Color(0xFFE08A3A), ink)
            "kodak_portra_400" -> FilmBox("PORTRA 400", "36 EXP · ISO 400 · C-41", cream, Color(0xFFD9692B), ink)
            "kodak_portra_800" -> FilmBox("PORTRA 800", "36 EXP · ISO 800 · C-41", cream, Color(0xFFB8452A), ink)
            "kodak_portra_800_push1" -> FilmBox("PORTRA 800", "36 EXP · PUSHED +1 · C-41", cream, Color(0xFFB8452A), ink)
            "kodak_portra_800_push2" -> FilmBox("PORTRA 800", "36 EXP · PUSHED +2 · C-41", cream, Color(0xFFB8452A), ink)
            "kodak_gold_200" -> FilmBox("GOLD 200", "36 EXP · ISO 200 · C-41", Color(0xFFF2C53D), Color(0xFFC0392B), ink)
            "kodak_ultramax_400" -> FilmBox("ULTRAMAX 400", "36 EXP · ISO 400 · C-41", Color(0xFFF2C53D), Color(0xFF2E5FA8), ink)
            "kodak_ektar_100" -> FilmBox("EKTAR 100", "36 EXP · ISO 100 · C-41", white, Color(0xFFB3262B), ink)
            "kodak_ektachrome_100" -> FilmBox("EKTACHROME 100", "36 EXP · ISO 100 · E-6", Color(0xFF1E2A44), Color(0xFFE2B53E), white)
            "kodak_kodachrome_64" -> FilmBox("KODACHROME 64", "36 EXP · ISO 64 · K-14", Color(0xFFF0C64A), Color(0xFF8E2A2A), ink)
            "fujifilm_c200" -> FilmBox("C200", "36 EXP · ISO 200 · C-41", white, Color(0xFF2E8B57), ink)
            "fujifilm_xtra_400" -> FilmBox("X-TRA 400", "36 EXP · ISO 400 · C-41", white, Color(0xFF1F7A4A), ink)
            "fujifilm_pro_400h" -> FilmBox("PRO 400H", "36 EXP · ISO 400 · C-41", Color(0xFFE6EEE9), Color(0xFF5E9C83), ink)
            "fujifilm_provia_100f" -> FilmBox("PROVIA 100F", "36 EXP · ISO 100 · E-6", white, Color(0xFF3557A8), ink)
            "fujifilm_velvia_100" -> FilmBox("VELVIA 100", "36 EXP · ISO 100 · E-6", white, Color(0xFF6A3D9A), ink)
            "kodak_vision3_50d" -> FilmBox("VISION3 50D", "5203 · DAYLIGHT · ECN-2", cine, Color(0xFFE0A43A), white)
            "kodak_vision3_250d" -> FilmBox("VISION3 250D", "5207 · DAYLIGHT · ECN-2", cine, Color(0xFFE07B2E), white)
            "kodak_vision3_200t" -> FilmBox("VISION3 200T", "5213 · TUNGSTEN · ECN-2", cine, Color(0xFF3F7FC4), white)
            "kodak_vision3_500t" -> FilmBox("VISION3 500T", "5219 · TUNGSTEN · ECN-2", cine, Color(0xFF2F5FA0), white)
            "kodak_verita_200d" -> FilmBox("VERITA 200D", "36 EXP · ISO 200 · C-41", cream, Color(0xFF8A6E4B), ink)
            else -> FilmBox((Develop.FILMS.firstOrNull { it.first == film }?.second ?: "FILM").uppercase(), "36 EXP", cream, Color(0xFF8A6E4B), ink)
        }
        // a film built here keeps its base stock's box, with its own name and the stock it came from
        return if (name == null) b else b.copy(name = name.uppercase(), small = b.name + " · " + b.small.substringAfterLast(" · "))
    }
}

/** The flap: a card with the tuck tab cut into its right end (the part that slid into the holder). */
private val FlapShape = GenericShape { size, _ ->
    val r = size.height * 0.1f
    val tuck = size.height * 0.55f
    moveTo(r, 0f); lineTo(size.width - tuck, 0f)
    lineTo(size.width, size.height * 0.22f); lineTo(size.width, size.height * 0.78f)
    lineTo(size.width - tuck, size.height); lineTo(r, size.height)
    quadraticBezierTo(0f, size.height, 0f, size.height - r); lineTo(0f, r); quadraticBezierTo(0f, 0f, r, 0f)
    close()
}

/** [modifier] goes first, so a caller's clicks cover the whole flap. */
@Composable
fun FilmBoxTab(box: FilmBox, modifier: Modifier = Modifier) {
    val shade = Color(box.paper.red * 0.92f, box.paper.green * 0.92f, box.paper.blue * 0.92f, 1f)
    Row(
        Modifier.floating(FlapShape, 4.dp).clip(FlapShape).then(modifier)
            .background(Brush.verticalGradient(listOf(box.paper, shade)))
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the band, with the fold's crease beside it
        Box(Modifier.fillMaxHeight().width(9.dp).background(box.band))
        Box(Modifier.fillMaxHeight().width(1.dp).background(Color.Black.copy(alpha = 0.12f)))
        // a long name of a film built here ends in … rather than running off the screen
        Column(Modifier.padding(start = 8.dp, end = 22.dp).widthIn(max = 220.dp), verticalArrangement = Arrangement.Center) {
            Text(box.name, color = box.ink, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            Text(box.small, color = box.ink.copy(alpha = 0.7f), fontSize = 7.sp, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 9.sp)
        }
    }
}
