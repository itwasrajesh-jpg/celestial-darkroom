@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors

/** Reads this app's own logcat (no permission needed) so background camera events can be shared. */
object AppLog {
    fun capture(maxLines: Int = 1500, onlyLatent: Boolean = false): String = try {
        val pid = android.os.Process.myPid()
        val cmd = if (onlyLatent) arrayOf("logcat", "-d", "-v", "time", "--pid=$pid", "Latent:V", "*:S")
                  else arrayOf("logcat", "-d", "-v", "time", "--pid=$pid")
        val p = Runtime.getRuntime().exec(cmd)
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        val tail = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        "Celestial Darkroom v${BuildConfig.VERSION_NAME} · ${tail.size} lines (pid $pid)\n" + tail.joinToString("\n")
    } catch (t: Throwable) { "logcat capture failed: $t" }

    fun clear() { try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() } catch (_: Throwable) {} }
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var onlyLatent by remember { mutableStateOf(false) }
    LaunchedEffect(onlyLatent) { text = AppLog.capture(onlyLatent = onlyLatent) }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Logs", color = LatentColors.TextBright, fontSize = 24.sp)
        Text("This app's own logcat: camera events, driver replies, vendor echoes. System-side camera logs are not visible to apps.", color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Refresh") { text = AppLog.capture(onlyLatent = onlyLatent) }
            Btn(if (onlyLatent) "Showing: this app only" else "Showing: everything") { onlyLatent = !onlyLatent }
            Btn("Share") {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                context.startActivity(Intent.createChooser(send, "Share log"))
            }
            Btn("Clear") { AppLog.clear(); text = AppLog.capture(onlyLatent = onlyLatent) }
        }
        Spacer(Modifier.height(10.dp))
        Text(text, color = LatentColors.Text, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun Btn(label: String, onClick: () -> Unit) {
    Text(label, color = LatentColors.AmberInk, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}
