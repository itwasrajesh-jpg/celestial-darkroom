package com.celestial.latent

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.ExposureMap
import com.celestial.latent.develop.Layer
import com.celestial.latent.develop.LithLook
import com.celestial.latent.develop.Recipe
import com.celestial.latent.develop.Region
import com.celestial.latent.ui.Glyph
import com.celestial.latent.ui.GlyphIcon
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.pressIn

/**
 * 113a: LAYERS in NEGATIVE (approved proof design-critique/layers-in-negative/proof/). The photo is cut
 * into layers first, before any editing, and then each layer gets its own film in APPLY TO. It is the
 * same LAYERS screen PRINT had (LayersStep), developed the same way, with the darkroom's own masks.
 *
 * With NO FILM it still works: you cut the layers on the plain photo, as shot. Their exposure, warmth
 * and colour wait until there is a film, because those are made by developing it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NegativeLayers(
    recipe: Recipe,
    layers: List<Layer>,
    onLayers: (List<Layer>) -> Unit,
    memory: PrintMemory,
    noFilm: Boolean,
    /** The photo as shot, for NO FILM (null while it is still being read). */
    plain: Bitmap?,
    lith: LithLook,
    masks: () -> Masks,
    depthSplit: ExposureMap?, onDepthSplit: (ExposureMap?) -> Unit,
    depthFix: ExposureMap?, onDepthFix: (ExposureMap?) -> Unit,
    renderWithMasks: (Recipe, Int, Masks) -> Pair<Bitmap, String>?,
    paintedKey: (Masks) -> String,
    renderRegionWithMasks: (Recipe, Int, Masks, Region) -> Bitmap?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = memory.cache
    // the depth and the tap-to-select picture are the photo's, shared with PRINT (read once per photo)
    val depthRanks = remember { memory.depthRanks ?: (if (com.celestial.latent.develop.Depth.isReady(context)) DepthRanks() else null).also { memory.depthRanks = it } }
    DisposableEffect(Unit) { onDispose { com.celestial.latent.develop.Select.release() } }
    // as PRINT renders it: through the shared cache, keyed by everything painted on it (107a)
    val render: (Recipe, Int, List<Layer>) -> Bitmap? = { r, e, ls ->
        if (noFilm) plain
        else masks().copy(layers = ls).let { mk -> cache.peek(r, e, paintedKey(mk)) ?: renderWithMasks(r, e, mk)?.let { (b, k) -> cache.put(r, k, b); b } }
    }
    Column(modifier.background(LatentColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.pressIn().clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                .combinedClickable(onClick = { Haptics.tick(context); onClose() }).padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.BACK, LatentColors.Text, size = 16.dp, tap = false)
                Text("NEGATIVE", color = LatentColors.Text, fontSize = 10.sp, letterSpacing = 1.2.sp)
            }
            GlyphIcon(Glyph.LAYERS, LatentColors.TextBright, size = 16.dp, tap = false)
            Text("LAYERS", color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
        LayersStep(recipe, layers, onLayers, depthRanks = depthRanks, selectPicture = memory.selectPicture,
            depthSplit = depthSplit, onDepthSplit = onDepthSplit, depthFix = depthFix, onDepthFix = onDepthFix,
            render = render,
            renderRegion = if (noFilm) null else { r, e, ls, reg -> renderRegionWithMasks(r, e, masks().copy(layers = ls), reg) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            usedBy = { id -> if (lith.on && lith.layer == id) listOf("lith") else emptyList() },
            canAdjust = !noFilm,
            hint = if (noFilm) "Cut your layers first, then pick a film for each in APPLY TO." else null)
    }
}
