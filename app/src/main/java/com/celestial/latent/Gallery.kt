@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.celestial.latent.ui.Glyph
import com.celestial.latent.ui.GlyphIcon
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/**
 * 87b: the roll's three ways of showing its frames. GRID is the roll as it always was; CAROUSEL is one
 * big frame with its neighbours turned away in 3D; PRINTS lays them out as prints on a table, each a
 * little crooked, straightening as it reaches the middle of the screen. The choice is remembered.
 */
object RollViews {
    const val GRID = "grid"; const val CAROUSEL = "carousel"; const val PRINTS = "prints"
    /** 89a: the roll as a contact sheet. */
    const val SHEET = "sheet"
    val ALL = listOf(GRID, CAROUSEL, PRINTS, SHEET)
    private fun prefs(context: android.content.Context) = context.getSharedPreferences("latent_camera_ui", android.content.Context.MODE_PRIVATE)
    fun get(context: android.content.Context): String = runCatching { prefs(context).getString("roll_view", GRID) ?: GRID }.getOrDefault(GRID).takeIf { it in ALL } ?: GRID
    fun set(context: android.content.Context, view: String) { runCatching { prefs(context).edit().putString("roll_view", view).apply() } }
}

/** The shadow under a card or print: the theme's own in Midnight and Clay, a plain dark one in Darkroom (it has no depth of its own). */
private fun Modifier.lifted(shape: Shape, lift: Dp): Modifier {
    val p = LatentColors.palette
    val c = if (p.depth) p.shadow else Color.Black
    return shadow(lift, shape, clip = false, ambientColor = c, spotColor = c)
}

/** The view buttons for the roll's header (89c: the app's glyphs), the chosen one in the accent. */
@Composable
internal fun RollViewToggle(view: String, onView: (String) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RollViews.ALL.forEach { v ->
            val on = v == view
            val g = when (v) { RollViews.CAROUSEL -> Glyph.CAROUSEL; RollViews.PRINTS -> Glyph.PRINTS; RollViews.SHEET -> Glyph.SHEET; else -> Glyph.GRID }
            GlyphIcon(g, if (on) LatentColors.Amber else LatentColors.TextDim,
                Modifier.clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = { if (!on) { Haptics.tick(context); onView(v) } }).padding(5.dp), 18.dp)
        }
    }
}

/**
 * The tilted carousel: the frame in the middle big and flat, its neighbours turned 42° away and tucked
 * behind it, as in the approved proof. Swipe to move; a tap on a neighbour brings it to the middle, a tap
 * on the middle frame opens it, and a long press picks it (as in the grid). [card] draws the picture.
 */
@Composable
internal fun <T> RollCarousel(
    items: List<T>, key: (T) -> String, caption: (T) -> String,
    onOpen: (T) -> Unit, onHold: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 88d: the key of a frame flying back from the darkroom; the carousel jumps to it so it has a place to land. */
    home: String? = null,
    card: @Composable BoxScope.(T) -> Unit,
) {
    val context = LocalContext.current
    // The pager reads the list through one state, so its page count and its keys always see the same
    // list: the roll fills in after it opens, and a stale empty list here crashed the carousel (#242).
    val list by rememberUpdatedState(items)
    val pager = rememberPagerState(pageCount = { list.size })
    val scope = rememberCoroutineScope()
    // the screen is rebuilt on the way back and would open at the first frame; the roll may still be filling in
    LaunchedEffect(home, items.size) {
        val i = if (home == null) -1 else items.indexOfFirst { key(it) == home }
        if (i >= 0 && pager.currentPage != i) pager.scrollToPage(i)
    }
    // a tick as each frame settles in the middle, like a click-stop
    LaunchedEffect(pager.settledPage) { if (items.isNotEmpty()) Haptics.tick(context) }
    BoxWithConstraints(modifier) {
        val full = maxWidth
        val cardW = full * 0.72f
        val shape = RoundedCornerShape(22.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            HorizontalPager(
                state = pager, key = { i -> list.getOrNull(i)?.let(key) ?: "page$i" },
                contentPadding = PaddingValues(horizontal = (full - cardW) / 2),
                beyondViewportPageCount = 2,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val item = list.getOrNull(page) ?: return@HorizontalPager
                // the middle frame is drawn over its neighbours
                Box(Modifier.zIndex(-abs(pager.currentPage - page + pager.currentPageOffsetFraction)).fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                // how far this page is from the middle, in pages: negative to the right
                                val o = (pager.currentPage - page) + pager.currentPageOffsetFraction
                                val a = abs(o)
                                rotationY = o.coerceIn(-1f, 1f) * 42f
                                val s = 1f - 0.16f * minOf(a, 1.6f)
                                scaleX = s; scaleY = s
                                // tucked in behind the middle frame rather than a full page away
                                translationX = o * size.width * 0.36f
                                cameraDistance = 14f * density
                            }
                            .width(cardW).aspectRatio(3f / 4f)
                            .lifted(shape, 18.dp).clip(shape).background(LatentColors.Surface)
                            .combinedClickable(
                                onClick = { if (page == pager.currentPage) onOpen(item) else scope.launch { pager.animateScrollToPage(page) } },
                                onLongClick = { Haptics.click(context); onHold(item) },
                            )
                            // the neighbours sit a little in shadow
                            .drawWithContent {
                                drawContent()
                                val o = abs((pager.currentPage - page) + pager.currentPageOffsetFraction)
                                if (o > 0.01f) drawRect(Color.Black.copy(alpha = 0.35f * minOf(o, 1.5f) / 1.5f))
                            },
                    ) { card(item) }
                }
            }
            val now = items.getOrNull(pager.currentPage)
            if (now != null) {
                Text(caption(now), color = LatentColors.TextBright, fontSize = 13.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp))
                // page dots as in the proof; a long roll shows its count instead
                if (items.size <= 12) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    items.indices.forEach { i ->
                        val on = i == pager.currentPage
                        Box(Modifier.size(if (on) 7.dp else 5.dp).clip(CircleShape).background(if (on) LatentColors.TextBright else LatentColors.TextDim))
                    }
                } else Text("${pager.currentPage + 1} / ${items.size}", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Prints on a table: cream paper border round each picture. The paper is the print's own, the same in every theme. */
private val PAPER = Color(0xFFF4F1EA)

/**
 * Prints on a table: one big bordered print after another down the screen, each tilted up to 3° its own
 * way, straightening (and coming up a touch) as it reaches the middle of the screen. Tap opens, long press
 * picks. [aspect] is the picture's width over height once known (3:4 until then); [card] draws the picture.
 */
@Composable
internal fun <T> RollPrints(
    items: List<T>, key: (T) -> String, aspect: (T) -> Float?,
    onOpen: (T) -> Unit, onHold: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 88d: the key of a frame flying back from the darkroom; the table scrolls it to the middle so it has a place to land. */
    home: String? = null,
    card: @Composable BoxScope.(T) -> Unit,
) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(home, items.size) {
        val i = if (home == null) -1 else items.indexOfFirst { key(it) == home }
        if (i < 0) return@LaunchedEffect
        if (list.layoutInfo.visibleItemsInfo.none { it.index == i }) list.scrollToItem(i)
        // then into the middle, where it straightens
        withFrameNanos { }
        val info = list.layoutInfo
        val me = info.visibleItemsInfo.firstOrNull { it.index == i } ?: return@LaunchedEffect
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        list.scrollBy(me.offset + me.size / 2f - mid)
    }
    val shape = RoundedCornerShape(3.dp)
    LazyColumn(modifier, state = list, contentPadding = PaddingValues(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        items(items, key = { key(it) }) { item ->
            val k = key(item)
            // each print's own lean, the same every time it is shown
            val tilt = (Math.floorMod(k.hashCode(), 641) - 320) / 100f
            val a = (aspect(item) ?: 0.75f).coerceIn(0.45f, 2.2f)
            Box(
                Modifier
                    .fillMaxWidth(if (a >= 1f) 0.9f else 0.8f)
                    .graphicsLayer {
                        val info = list.layoutInfo
                        val me = info.visibleItemsInfo.firstOrNull { it.key == k }
                        val near = if (me == null) 0f else {
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val d = (me.offset + me.size / 2f - mid) / ((info.viewportEndOffset - info.viewportStartOffset) * 0.32f)
                            exp(-d * d)
                        }
                        rotationZ = tilt * (1f - 0.85f * near)
                        val s = 1f + 0.03f * near
                        scaleX = s; scaleY = s
                    }
                    .lifted(shape, 12.dp).clip(shape).background(PAPER)
                    .combinedClickable(onClick = { onOpen(item) }, onLongClick = { Haptics.click(context); onHold(item) })
                    .padding(10.dp),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(a).background(Color(0xFF2A2A28))) { card(item) }
            }
        }
    }
}
