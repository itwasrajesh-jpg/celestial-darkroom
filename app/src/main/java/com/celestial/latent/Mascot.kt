package com.celestial.latent

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.AppTheme
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.MascotMoves
import com.celestial.latent.ui.MascotPalette
import com.celestial.latent.ui.MascotPose
import com.celestial.latent.ui.MascotScene
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.drawMascot
import com.celestial.latent.ui.glow

/**
 * 114a: the mascot, a little photographer drawn live in the theme's colours (approved proof
 * mascot/placement/1-all-screens.jpg). It lives on the empty roll, the app help index, About, the
 * crash screen and a one-time hello. Settings → Look → Mascot turns it off everywhere.
 */
object Mascot {
    /** Settings → Look → Mascot; read before the first frame, kept in step with the settings after. */
    var on by mutableStateOf(true)

    private const val FILE = "latent"
    fun load(ctx: Context) { on = AppSettings.load(ctx).mascot }
    /** The hello is shown once, the first time the app opens with the mascot on. */
    fun helloDue(ctx: Context) = on && !ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("mascotHelloShown", false)
    fun helloDone(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("mascotHelloShown", true).apply()
}

/**
 * The mascot in a [size] square, 50 dp per drawing unit at 180 dp, feet near the bottom. It plays its
 * moment once when shown ([scene]), then breathes and blinks; a tap makes it jump. With the phone's
 * animations off it stands still in its resting pose.
 */
@Composable
fun MascotView(scene: MascotScene, modifier: Modifier = Modifier, size: Dp = 180.dp) {
    val moving = remember { motionOn() }
    var t by remember { mutableDoubleStateOf(if (moving) 0.0 else MascotMoves.STILL_T) }
    var tap by remember { mutableStateOf<Double?>(null) }
    if (moving) LaunchedEffect(Unit) {
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - t0) / 1e9 }
    }
    val theme = LatentColors.theme
    val pal = MascotPalette.of(theme)
    val clay = theme == AppTheme.CLAY
    Box(modifier.size(size).pointerInput(scene, moving) {
        if (moving) detectTapGestures { if (MascotMoves.canJump(scene, t, tap)) tap = t }
    }) {
        // the theme's glow behind it: its own outline, blurred (blur needs Android 12; older phones go without)
        if (android.os.Build.VERSION.SDK_INT >= 31) Canvas(Modifier.matchParentSize().graphicsLayer {
            val r = ((5.33.dp.toPx() - 0.5f) / 0.57735f).coerceAtLeast(1f)
            renderEffect = BlurEffect(r, r, TileMode.Decal)
            alpha = if (clay) 0.35f else 0.40f
        }) { place { drawMascot(MascotMoves.pose(scene, t, tap), pal, Color(pal.glow or 0xFF000000)) } }
        Canvas(Modifier.matchParentSize()) {
            val pose = MascotMoves.pose(scene, t, tap)
            contactShadow(pose, clay)
            place { drawMascot(pose, pal) }
        }
    }
}

/** feet at the bottom middle, one drawing unit = width / 3.6 */
private inline fun DrawScope.place(crossinline block: DrawScope.() -> Unit) {
    val unit = size.width / 3.6f
    translate(size.width / 2f, size.height - 1.33.dp.toPx()) { scale(unit, unit, Offset.Zero) { block() } }
}

/** a soft shadow under the feet that shrinks as it leaves the ground */
private fun DrawScope.contactShadow(p: MascotPose, clay: Boolean) {
    val unit = size.width / 3.6f
    val lift = maxOf(0f, -p.d("bob", 0.0).toFloat()) * unit
    val rx = unit * (0.85f - minOf(lift / unit, 0.45f)); val ry = unit * 0.14f; val blur = 2.dp.toPx()
    val c = Offset(size.width / 2f, size.height - 1.33.dp.toPx() + 0.67.dp.toPx())
    val col = if (clay) Color(0x787F93BC) else Color(0x96000000)
    val r = rx + 2 * blur
    scale(1f, ry / rx, c) {
        drawCircle(Brush.radialGradient(0f to col, ((rx - blur) / r).coerceIn(0f, 1f) to col, 1f to col.copy(alpha = 0f), center = c, radius = r), r, c)
    }
}

/**
 * The one-time hello (114a): after the opening animation, or straight away when that is off. The
 * mascot waves and jumps, with the name, one line and "Start shooting". Back also closes it.
 */
@Composable
fun HelloScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler { onDone() }
    Box(Modifier.fillMaxSize().background(LatentColors.Background).glow()
        // nothing reaches the camera underneath while this is up
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
        .statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            MascotView(MascotScene.HELLO)
            Spacer(Modifier.height(22.dp))
            Text("CELESTIAL", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 5.sp)
            Text("DARKROOM", color = LatentColors.TextBright, fontSize = 22.sp, letterSpacing = 6.sp)
            Spacer(Modifier.height(14.dp))
            Text("Hello! This is a film camera. Shoot, and every photo develops like film. Then print it your way in the darkroom.",
                color = LatentColors.Text, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            Text("Start shooting", color = LatentColors.AmberInk, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill()
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) { Haptics.tick(context); onDone() } } }
                    .padding(horizontal = 22.dp, vertical = 11.dp))
            Spacer(Modifier.weight(1f))
            Text("Shown only once. Turn the mascot off in Settings → Look.", color = LatentColors.TextDim, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 14.dp))
        }
    }
}
