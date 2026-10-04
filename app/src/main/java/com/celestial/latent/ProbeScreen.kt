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
import androidx.compose.runtime.DisposableEffect
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
import com.celestial.latent.camera.Lenses
import com.celestial.latent.camera.VendorProbe
import com.celestial.latent.ui.LatentColors

@Composable
fun ProbeScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { VendorProbe(context) }
    var output by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val lens = Lenses.ALL.firstOrNull { it.physicalId == settings.defaultLensId } ?: Lenses.DEFAULT
    DisposableEffect(Unit) { onDispose { probe.destroy() } }

    fun run(block: () -> String) {
        if (running) return
        running = true; progress = "working…"; output = ""
        Thread {
            val text = try { block() } catch (t: Throwable) { "Probe failed: $t" }
            output = text; progress = ""; running = false
        }.start()
    }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Vendor probe", color = LatentColors.TextBright, fontSize = 24.sp)
        Text("Maps vendor keys per path and lens, then tests the likely ones. The live camera is closed while this runs.", color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Map keys", !running) { run { probe.enumerate() } }
            Btn("Test candidates on ${settings.cameraPath}/${lens.physicalId}", !running) {
                run {
                    val cands = probe.candidateKeys(settings.cameraPath, lens)
                    if (cands.isEmpty()) "No candidate keys by name for path ${settings.cameraPath}, lens ${lens.physicalId}. Run “Map keys” to see everything."
                    else probe.deepProbe(settings.cameraPath, lens, cands) { p -> progress = p }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Test ALL keys on ${settings.cameraPath}/${lens.physicalId} (slow)", !running) {
                run {
                    val all = probe.keysFor(settings.cameraPath, lens)?.second?.map { it.key to it.value } ?: emptyList()
                    probe.deepProbe(settings.cameraPath, lens, all) { p -> progress = p }
                }
            }
            Btn("Share", output.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, output) }
                context.startActivity(Intent.createChooser(send, "Share probe"))
            }
        }
        Spacer(Modifier.height(8.dp))
        // One per line: side by side, the A/B button was squashed into a circle and the quality
        // probe was pushed off the screen entirely.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Sensor mode sweep 0–63 on ${settings.cameraPath}/${lens.physicalId}", !running) {
                run { probe.sensorModeSweep(settings.cameraPath, lens, 0, 63) { p -> progress = p } }
            }
            Btn("A/B test: does DCG / HDR actually do anything?", !running) {
                run { probe.abTest(settings.cameraPath, lens) { p -> progress = p } }
            }
            Btn("Quality probe (DCG/HDR/routing)", !running) {
                run { probe.qualityProbe(settings.cameraPath, lens) { p -> progress = p } }
            }
        }
        if (progress.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(progress, color = LatentColors.Amber, fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        Text(output, color = LatentColors.Text, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Btn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (enabled) LatentColors.Amber else LatentColors.Surface)
            .combinedClickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
