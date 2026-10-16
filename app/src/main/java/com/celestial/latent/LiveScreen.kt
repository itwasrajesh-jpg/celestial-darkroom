@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import com.celestial.latent.develop.DevelopQueue
import com.celestial.latent.develop.Live
import com.celestial.latent.develop.LiveEdit
import com.celestial.latent.develop.LiveLook
import com.celestial.latent.ui.LatentColors
import java.io.File
import java.util.Locale

/**
 * The living photo (66a): a full-screen panel over the darkroom. Only the things this edit has
 * get a control (rain, fog, each lamp, god rays), each with its value shown. "Quick look" makes a
 * small clip fast to judge the movement; "Make clip" makes the 1440 one, which plays in a loop and
 * can then be saved next to the still in DCIM/Latent.
 */
@Composable
fun LiveScreen(edit: LiveEdit, onClose: () -> Unit) {
    val context = LocalContext.current
    val moves = remember(edit) { Live.moves(edit) }
    var look by remember { mutableStateOf(LiveLook()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<File?>(null) }
    var clipIsFull by remember { mutableStateOf(false) }
    var clipSize by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val work = remember { Worker() }
    DisposableEffect(Unit) { onDispose { work.dispose() } }

    fun start(full: Boolean) {
        if (running) return
        Haptics.click(context)
        running = true; progress = 0f; saved = false; status = "starting…"
        val snapshot = look
        work.run(context.applicationContext, edit, snapshot, if (full) Live.FULL_EDGE else Live.QUICK_EDGE,
            onStatus = { s, p -> status = s; progress = p },
            onDone = { file, size, error ->
                running = false
                if (file != null) { clip = file; clipIsFull = full; clipSize = size; status = if (full) "made, $size" else "quick look, $size" }
                else status = error ?: ""
            })
    }

    // Drawn over the darkroom in the app's own window, not as a dialog: a dialog window on the Xiaomi
    // (HyperOS) did not get the screen's bars, and its bottom row ended up below the screen.
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(LatentColors.Background)
        // the darkroom underneath must not take taps meant for this panel
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LIVING PHOTO", color = LatentColors.TextBright, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Text("Close", color = LatentColors.Text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).combinedClickable(onClick = { onClose() }).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // the clip, looping; or what to do
            Box(Modifier.fillMaxWidth().weight(1f).background(LatentColors.Surface.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                val c = clip
                if (c != null && !running) {
                    key(c.path) {
                        AndroidView(factory = { ctx ->
                            VideoView(ctx).apply {
                                setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
                                setVideoURI(Uri.fromFile(c))
                            }
                        }, onRelease = { it.stopPlayback() })
                    }
                } else {
                    Text(
                        when {
                            running -> "$status\n${(progress * 100).toInt()} %"
                            !moves.any -> "Nothing in this photo moves yet. Add rain on the window (WET), fog, a lamp or god rays on a sun, and they come alive here."
                            status.isNotEmpty() -> status
                            else -> "Set how things move, then Quick look."
                        },
                        color = LatentColors.Text, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
            // only the things this edit has
            // at most a third of the screen, so the clip keeps its room on a phone that draws everything larger (the Xiaomi)
            Column(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 3).dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                if (moves.rain) {
                    Section("RAIN ON THE WINDOW")
                    LiveSlider("Drops running and landing", look.drops, 0f, 2f, "%.1f", !running) { look = look.copy(drops = it) }
                    LiveSlider("How fast they slide", look.speed, 0.5f, 2f, "%.1f×", !running) { look = look.copy(speed = it) }
                }
                if (moves.fog) {
                    Section("FOG")
                    LiveSlider("Drift and breathing", look.drift * 100f, 0f, 100f, "%.0f %%", !running) { look = look.copy(drift = it / 100f) }
                }
                if (moves.lamps.isNotEmpty()) {
                    Section("LIGHTS")
                    for (j in moves.lamps) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Light ${j + 1}", color = LatentColors.Text, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (kind in Live.FLICKERS) {
                                    val on = look.flickerOf(j) == kind
                                    Text(kind.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, maxLines = 1,
                                        color = if (on) LatentColors.AmberInk else LatentColors.Text,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                            .background(if (on) LatentColors.Amber else LatentColors.Surface)
                                            .combinedClickable(onClick = {
                                                if (!running) { Haptics.tick(context); look = look.copy(flicker = look.flicker + (j to kind)) }
                                            })
                                            .padding(horizontal = 10.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
                if (moves.rays) {
                    Section("GOD RAYS")
                    LiveSlider("Shimmer", look.shimmer * 100f, 0f, 100f, "%.0f %%", !running) { look = look.copy(shimmer = it / 100f) }
                }
            }
            if (running) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)) {
                    Spacer(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).padding(vertical = 2.dp).background(LatentColors.Amber))
                }
            } else if (clip != null && status.isNotEmpty()) {
                Text(status, color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 18.dp).padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    Pill("Cancel", strong = false) { work.cancel(); status = "cancelling…" }
                } else if (moves.any) {
                    Pill("Quick look", strong = false) { start(full = false) }
                    Pill("Make clip", strong = !clipIsFull || saved) { start(full = true) }
                    val c = clip
                    if (c != null && clipIsFull && !saved) Pill("Save clip", strong = true) {
                        Haptics.click(context)
                        status = runCatching { Live.save(context, c, edit); saved = true; "saved to DCIM/Latent" }
                            .getOrElse { "not saved: ${it.message}" }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun LiveSlider(label: String, value: Float, min: Float, max: Float, fmt: String, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) LatentColors.Text else LatentColors.Line, fontSize = 11.sp)
            Text(String.format(Locale.US, fmt, value), color = if (enabled) LatentColors.TextBright else LatentColors.Line, fontSize = 11.sp)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line))
    }
}

@Composable
private fun Pill(text: String, strong: Boolean, onClick: () -> Unit) {
    Text(text, color = if (strong) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 12.sp, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (strong) LatentColors.Amber else LatentColors.Surface)
            .combinedClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

/**
 * Makes clips on a thread of its own and keeps the last preparation, so changing only how things
 * move does not develop the photo again. The memory is freed when the panel closes.
 */
private class Worker {
    private val lock = Any()
    private var prep: Live.Prep? = null
    private var thread: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var disposed = false
    private var count = 0

    fun cancel() { cancelled = true }

    fun dispose() {
        disposed = true; cancelled = true
        synchronized(lock) { if (thread == null) { prep?.close(); prep = null } }
    }

    fun run(context: android.content.Context, edit: LiveEdit, look: LiveLook, edge: Int,
            onStatus: (String, Float) -> Unit, onDone: (File?, String, String?) -> Unit) {
        cancelled = false
        val dir = File(context.cacheDir, "live").apply { mkdirs() }
        val out = File(dir, "clip_${++count}.mp4")
        val t = Thread({
            var file: File? = null; var size = ""; var error: String? = null
            // one engine and one heavy job at a time, as every develop
            val holdsLane = DevelopQueue.acquireLane(120)
            try {
                val key = Live.prepKey(look, edge)
                val p = synchronized(lock) { prep?.takeIf { it.key == key } } ?: run {
                    synchronized(lock) { prep?.close(); prep = null }
                    Live.prepare(context, edit, look, edge, { s, f -> onStatus(s, f * 0.5f) }, { cancelled }).also { synchronized(lock) { prep = it } }
                }
                onStatus("making the frames…", 0.5f)
                val (w, h) = Live.make(p, edit, look, out, { f -> onStatus("making the frames…", 0.5f + 0.5f * f) }, { cancelled })
                file = out; size = "${w}×$h, ${Live.SECONDS.toInt()} s loop"
                dir.listFiles()?.forEach { if (it != out) it.delete() }
            } catch (c: Live.Cancelled) {
                error = "cancelled"; out.delete()
            } catch (o: OutOfMemoryError) {
                android.util.Log.e("Latent", "living photo: out of memory", o)
                error = "The phone ran out of memory. Try Quick look."; out.delete()
                synchronized(lock) { prep?.close(); prep = null }
            } catch (x: Throwable) {
                android.util.Log.e("Latent", "living photo failed", x)
                error = "Not made: " + (x.message ?: x.javaClass.simpleName).take(120); out.delete()
            } finally {
                if (holdsLane) DevelopQueue.engineLane.release()
                synchronized(lock) {
                    thread = null
                    if (disposed) { prep?.close(); prep = null }
                }
            }
            if (!disposed) onDone(file, size, error)
        }, "latent-live")
        synchronized(lock) { thread = t }
        t.start()
    }
}
