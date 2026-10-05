@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Depth
import com.celestial.latent.ui.LatentColors

/**
 * A temporary test of the depth model on this phone (step 50a): download it, run it on a photo,
 * see the depth beside the photo, and read the timings. The sun step (50b) is built on what this
 * proves — and this screen goes once that works.
 */
@Composable
fun DepthTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(Depth.isReady(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (Depth.isReady(context)) "the model is downloaded and verified" else "the model is not downloaded yet (about 50 MB, once)") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var depth by remember { mutableStateOf<Bitmap?>(null) }

    fun runOn(uri: Uri) {
        busy = true; status = "reading the photo…"; depth = null
        Thread {
            runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE     // the pixels are read, so not a hardware bitmap
                    val long = maxOf(info.size.width, info.size.height)
                    if (long > 1024) decoder.setTargetSampleSize(maxOf(1, long / 1024))
                }
                photo = bmp
                status = "estimating depth…"
                val t0 = System.currentTimeMillis()
                val d = Depth.estimate(context, bmp)
                val ms = System.currentTimeMillis() - t0
                val px = IntArray(d.size) { i -> val g = (d[i] * 255f).toInt().coerceIn(0, 255); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                depth = Bitmap.createBitmap(px, Depth.SIZE, Depth.SIZE, Bitmap.Config.ARGB_8888)
                status = "depth in $ms ms (the first run includes loading the model) · ${bmp.width}×${bmp.height} photo · white = near"
            }.onFailure { t -> status = "failed: ${t.message}"; android.util.Log.e("Latent", "depth test failed", t) }
            busy = false
        }.start()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runOn(it) } }
    // the model from a file on the phone: saved from wherever, imported once, checked like a download
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            Thread {
                Depth.importFrom(context, uri) { got, total -> status = "importing… ${got * 100 / total}%" }
                    .onSuccess { ready = true; status = "imported and verified — choose a photo" }
                    .onFailure { t -> status = "import failed: ${t.message}"; android.util.Log.e("Latent", "depth import failed", t) }
                busy = false
            }.start()
        }
    }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Depth test", color = LatentColors.TextBright, fontSize = 24.sp)
        Text("Estimates how far away each part of a photo is, on this phone, with Depth Anything V2 Small (Apache-2.0). The sun builds on this.",
            color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        // One per line: two long labels side by side would squash, as on the probe screen.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ready) TestBtn("Import the model file", !busy) { modelPicker.launch(arrayOf("*/*")) }
            if (!ready) TestBtn("Download the model (50 MB)", !busy) {
                busy = true
                Thread {
                    Depth.download(context) { got, total -> status = "downloading… ${got * 100 / total}% (${got / 1_000_000} of ${total / 1_000_000} MB)" }
                        .onSuccess { ready = true; status = "downloaded and verified — choose a photo" }
                        .onFailure { t -> status = "download failed: ${t.message}"; android.util.Log.e("Latent", "depth download failed", t) }
                    busy = false
                }.start()
            }
            if (ready) TestBtn("Choose a photo", !busy) { picker.launch(arrayOf("image/*", "image/x-adobe-dng")) }
        }
        Spacer(Modifier.height(10.dp))
        Text(status, color = LatentColors.Amber, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        photo?.let { p ->
            val aspect = p.width.toFloat() / p.height
            Image(p.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.height(8.dp))
            depth?.let { d ->
                Image(d.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun TestBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (enabled) LatentColors.Amber else LatentColors.Surface)
            .combinedClickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
