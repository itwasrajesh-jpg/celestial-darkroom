@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.OnPhoto
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.floating
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * 118a: a focus stack planned by its two ends, like Xiaomi's "extended depth of field" on the tele lenses
 * but in RAW. The user focuses on the nearest part and taps "Set as nearest", then on the farthest and
 * "Set as farthest"; the app takes just enough frames, one focus step (about one depth of field, see
 * CameraController.focusStepDiopters) apart, to join the two with no soft gap. Until both ends are set
 * for the lens in use, FS shoots its old 12 frames around the current focus.
 */
object FocusPlan {
    /** most frames in one stack: a burst of 16 is what the roll has always been proven with */
    const val MAX_FRAMES = 16

    /** where the viewfinder was last tapped (0..1), so a Set can leave a mark on the picture there */
    @Volatile var lastTap: Offset? = null

    /** frames needed to join [near] and [far] (dioptres) at [step] apart, before the cap */
    fun needed(near: Float, far: Float, step: Float): Int =
        ceil((kotlin.math.abs(near - far) / step.coerceAtLeast(0.005f)) - 1e-3f).toInt() + 1

    /** focus of every frame, nearest first (largest dioptres first), evenly spaced; null if both ends are the same */
    fun frames(near: Float, far: Float, step: Float): FloatArray? {
        val a = maxOf(near, far); val b = minOf(near, far)
        if (a - b < 0.002f) return null
        val n = needed(a, b, step).coerceIn(2, MAX_FRAMES)
        return FloatArray(n) { i -> a - (a - b) * i / (n - 1) }
    }

    fun setFor(s: AppSettings, lensId: String): Boolean = s.fsLens == lensId && s.fsNearD >= 0f && s.fsFarD >= 0f

    /** the plan for this lens, or null while either end is missing (or both are the same) */
    fun plan(s: AppSettings, lensId: String, step: Float): FloatArray? =
        if (setFor(s, lensId)) frames(s.fsNearD, s.fsFarD, step) else null

    /** "52 cm", "1.4 m", "∞" */
    fun label(d: Float): String = when {
        d <= 0.02f -> "∞"
        100f / d < 100f -> "${Math.round(100f / d)} cm"
        else -> String.format(java.util.Locale.US, "%.1f m", 1f / d)
    }
}

@Composable
fun FocusStackCard(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    lensId: String,
    /** the focus the lens is at now (dioptres), or null while the camera has not said */
    current: () -> Float?,
    /** true when the current focus came from a tap on the picture (autofocus), so the tap can be marked */
    tapped: () -> Boolean,
    /** one focus step on this lens (dioptres), or null while the camera has not said */
    step: Float?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mine = settings.fsLens == lensId
    val near = if (mine) settings.fsNearD else -1f
    val far = if (mine) settings.fsFarD else -1f
    val nearSet = near >= 0f; val farSet = far >= 0f
    fun set(asNear: Boolean) {
        val d = current() ?: return
        Haptics.tick(context)
        val t = if (tapped()) FocusPlan.lastTap else null
        // ends set on another lens mean nothing here: start this lens afresh
        val base = if (mine) settings else settings.copy(fsLens = lensId, fsNearD = -1f, fsFarD = -1f, fsNearX = -1f, fsNearY = -1f, fsFarX = -1f, fsFarY = -1f)
        onSettingsChange(if (asNear) base.copy(fsLens = lensId, fsNearD = d, fsNearX = t?.x ?: -1f, fsNearY = t?.y ?: -1f)
            else base.copy(fsLens = lensId, fsFarD = d, fsFarX = t?.x ?: -1f, fsFarY = t?.y ?: -1f))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).floating(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(LatentColors.Surface.copy(alpha = 0.93f))
        // taps on the card stay on the card (they would otherwise focus the viewfinder underneath)
        .pointerInput(Unit) { detectTapGestures { } }
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FOCUS STACK", color = LatentColors.Amber, fontSize = 12.sp, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            if (nearSet || farSet) Text("Clear", color = LatentColors.Text, fontSize = 12.sp,
                modifier = Modifier.combinedClickable(onClick = {
                    Haptics.tick(context)
                    onSettingsChange(settings.copy(fsNearD = -1f, fsFarD = -1f, fsNearX = -1f, fsNearY = -1f, fsFarX = -1f, fsFarY = -1f))
                }).padding(horizontal = 8.dp, vertical = 2.dp))
            Text("×", color = LatentColors.Text, fontSize = 18.sp,
                modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); onClose() }).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndBox("NEAREST", "sharp from", near, armed = !nearSet, setLabel = if (nearSet) "Set again" else "Set as nearest", modifier = Modifier.weight(1f)) { set(true) }
            EndBox("FARTHEST", "sharp to", far, armed = nearSet && !farSet, setLabel = if (farSet) "Set again" else "Set as farthest", modifier = Modifier.weight(1f)) { set(false) }
        }
        Spacer(Modifier.height(10.dp))
        val plan = if (nearSet && farSet && step != null) FocusPlan.frames(near, far, step) else null
        if (plan != null && step != null) {
            val capped = FocusPlan.needed(near, far, step) > FocusPlan.MAX_FRAMES
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("FRAMES", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(end = 8.dp))
                Text("AUTO · ${plan.size}", color = LatentColors.AmberInk, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill().padding(horizontal = 10.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                Text(if (capped) "MOST · ${FocusPlan.MAX_FRAMES}" else "ONE SHARP ZONE APART", color = LatentColors.Amber, fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Ruler(plan, if (capped) (plan[0] - plan[1]) else step)
            Spacer(Modifier.height(2.dp))
            Text("${plan.size} frames · ${FocusPlan.label(plan.first())} to ${FocusPlan.label(plan.last())} · hold still",
                color = LatentColors.TextBright, fontSize = 12.sp)
            if (capped) Text("Too deep for ${FocusPlan.MAX_FRAMES} frames: a little soft in between.", color = LatentColors.Amber, fontSize = 11.sp, lineHeight = 14.sp)
        } else if (nearSet && farSet) Text("Both ends are at the same distance: focus farther, then Set as farthest.", color = LatentColors.Amber, fontSize = 12.sp)
        else {
            Text(if (!nearSet) "Focus on the nearest part, then Set as nearest."
                else "Now focus on the farthest part, then Set as farthest.", color = LatentColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
            Text("Until both are set, FS shoots 12 frames around your focus.", color = LatentColors.TextDim, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun EndBox(title: String, hint: String, d: Float, armed: Boolean, setLabel: String, modifier: Modifier = Modifier, onSet: () -> Unit) {
    val set = d >= 0f
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(LatentColors.Panel)
        .border(if (armed) 1.dp else 0.5.dp, if (armed) LatentColors.Amber else LatentColors.Line, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 2.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(hint, color = LatentColors.TextDim, fontSize = 9.sp, maxLines = 1)
        }
        // the distance, and the lens's own reading of it (dioptres = 1 / metres) on the same line
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(if (set) FocusPlan.label(d) else "not set", color = if (set) LatentColors.TextBright else LatentColors.TextDim,
                fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (set) Text(String.format(java.util.Locale.US, "%.2f D", d), color = LatentColors.Text, fontSize = 11.sp, maxLines = 1)
        }
        Text(setLabel, color = if (armed) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).accentFill(armed, LatentColors.Surface)
                .combinedClickable(onClick = onSet).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

/** Near to far: one slice per frame, each as wide as its sharp zone, so you can see they join. */
@Composable
private fun Ruler(plan: FloatArray, step: Float) {
    val a = plan.first(); val b = plan.last(); val span = (a - b).coerceAtLeast(1e-4f)
    val amber = LatentColors.Amber; val track = LatentColors.Panel
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val h = size.height; val w = size.width
        drawRoundRect(track, Offset(0f, h * 0.3f), Size(w, h * 0.4f), CornerRadius(h * 0.2f))
        plan.forEachIndexed { i, d ->
            val x0 = ((a - (d + step * 0.5f)) / span * w).coerceIn(0f, w)
            val x1 = ((a - (d - step * 0.5f)) / span * w).coerceIn(0f, w)
            drawRoundRect(if (i % 2 == 0) amber else amber.copy(alpha = 0.72f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), h), CornerRadius(3f))
        }
    }
    // distances under the ruler: every frame when they fit, else the ends and the middle
    val shown = if (plan.size <= 8) plan.indices.toList() else listOf(0, plan.size / 2, plan.size - 1)
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
        val wPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        shown.forEach { i ->
            val t = (a - plan[i]) / span
            val label = FocusPlan.label(plan[i]).removeSuffix(" cm")
            val half = with(density) { 14.dp.toPx() }
            val x = (t * wPx - half).coerceIn(0f, wPx - 2 * half)
            Text(label, color = LatentColors.Text, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.offset { IntOffset(x.toInt(), 0) }.width(28.dp).padding(top = 2.dp))
        }
    }
}

/** The two ends' marks on the picture while the card is open (only where they were set by a tap). */
@Composable
fun FocusStackMarks(settings: AppSettings, lensId: String) {
    if (settings.fsLens != lensId) return
    val marks = listOfNotNull(
        if (settings.fsNearD >= 0f && settings.fsNearX >= 0f) Triple(Offset(settings.fsNearX, settings.fsNearY), "NEAR " + FocusPlan.label(settings.fsNearD), settings.fsFarD < 0f) else null,
        if (settings.fsFarD >= 0f && settings.fsFarX >= 0f) Triple(Offset(settings.fsFarX, settings.fsFarY), "FAR " + FocusPlan.label(settings.fsFarD), false) else null,
    )
    if (marks.isEmpty()) return
    val ring = OnPhoto.TextBright; val amber = OnPhoto.Amber
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxSize()) {
            val r = 18.dp.toPx(); val arm = 5.dp.toPx(); val sw = 1.5.dp.toPx()
            marks.forEach { (p, _, last) ->
                val c = Offset(p.x * size.width, p.y * size.height); val col = if (last) amber else ring
                drawCircle(col, r, c, style = Stroke(sw))
                drawLine(col, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), sw); drawLine(col, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), sw)
            }
        }
        marks.forEach { (p, label, last) ->
            val below = with(density) { 22.dp.toPx() }; val half = with(density) { 40.dp.toPx() }
            val x = (p.x * w - half).coerceIn(0f, (w - 2 * half).coerceAtLeast(0f)); val y = (p.y * h + below).coerceAtMost(h - with(density) { 20.dp.toPx() })
            Box(Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.size(width = 80.dp, height = 20.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (last) amber else ring, fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(PICTURE_PILL).padding(horizontal = 7.dp, vertical = 1.dp))
            }
        }
    }
}

/**
 * A small bubble above the burst button when a tele lens is focused close: "Close up on tele? Tap for
 * focus stack". It waits 1.5 s of the close focus, stays 6 s, comes at most once per app start and five
 * times in all (pref latent_camera_ui/fs_hint_count), and never while a multi-shot mode is on.
 */
@Composable
fun TeleStackHint(show: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (!show) { visible = false; return@LaunchedEffect }
        if (TeleHintGate.shownThisStart || TeleHintGate.count(context) >= 5) return@LaunchedEffect
        delay(1500)
        TeleHintGate.shown(context); visible = true
        delay(6000); visible = false
    }
    if (!visible) return
    val up = with(LocalDensity.current) { 54.dp.roundToPx() }
    Popup(alignment = Alignment.BottomEnd, offset = IntOffset(0, -up)) {
        TeleHintBubble { Haptics.tick(context); visible = false; onTap() }
    }
}

@Composable
internal fun TeleHintBubble(onTap: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(LatentColors.Surface.copy(alpha = 0.96f))
        .border(1.dp, LatentColors.Amber, RoundedCornerShape(14.dp))
        .combinedClickable(onClick = onTap)
        .padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text("Close up on tele?", color = LatentColors.TextBright, fontSize = 12.sp, maxLines = 1)
        Text("Tap for focus stack", color = LatentColors.Amber, fontSize = 12.sp, maxLines = 1)
    }
}

private object TeleHintGate {
    var shownThisStart = false
    private fun prefs(c: Context) = c.getSharedPreferences("latent_camera_ui", Context.MODE_PRIVATE)
    fun count(c: Context): Int = prefs(c).getInt("fs_hint_count", 0)
    fun shown(c: Context) { shownThisStart = true; prefs(c).edit().putInt("fs_hint_count", count(c) + 1).apply() }
}
