package com.celestial.latent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.Fingerprint
import com.celestial.latent.ui.LatentColors
import kotlin.math.abs

/**
 * The look builder's first half: measuring.
 *
 * Before anything can be searched for, the measurement has to agree with the eye. This screen
 * shows a look's fingerprint for a set of reference images and, beside it, the same figures for
 * the app's own films — developed from one of your photos. If the films separate here the way
 * they separate on screen, the search is worth building. If they do not, no amount of searching
 * would help, and better to know now.
 */
/** The emulsion every build starts from: a neutral, well-behaved colour negative. */
private const val BASE_STOCK = "kodak_portra_400"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LookScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    var references by remember { mutableStateOf(com.celestial.latent.develop.LookSession.references) }
    var textures by remember { mutableStateOf(com.celestial.latent.develop.LookSession.textures) }
    var thumbs by remember { mutableStateOf(com.celestial.latent.develop.LookSession.thumbs) }
    var testShot by remember { mutableStateOf(com.celestial.latent.develop.LookSession.testShot) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /**
     * A small copy for measuring and for the strip. Kept deliberately modest: these live in the
     * session until it is cleared, and a dozen full-size thumbnails would be hundreds of
     * megabytes held for the life of the app.
     */
    fun thumbnailOf(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 900) sample *= 2
            input.close()
            context.contentResolver.openInputStream(uri)?.use { fresh ->
                BitmapFactory.decodeStream(fresh, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    /**
     * A piece of the reference at its original resolution. Grain is fine detail and does not
     * survive the downscaling used for everything else, so it has to be measured here.
     */
    fun grainCropOf(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // newInstance can return null for a file it cannot open in regions.
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(input, false) ?: return@use null
            // Film grain is a few thousandths of a millimetre. On a 35 mm frame scanned to 3000 px
            // along its long side, one pixel is about twelve of those — the grain is already at
            // the edge of visibility. Smaller than that, what looks like grain is JPEG noise, so it
            // is not measured at all and the film keeps its own.
            if (maxOf(decoder.width, decoder.height) < 3000) { decoder.recycle(); return@use null }
            val side = minOf(decoder.width, decoder.height, 512)
            val left = (decoder.width - side) / 2
            val top = (decoder.height - side) / 2
            val crop = decoder.decodeRegion(android.graphics.Rect(left, top, left + side, top + side), null)
            decoder.recycle()
            crop
        }
    }.getOrNull()

    val pickRefs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        busy = true
        Thread {
            val added = ArrayList<Pair<Uri, Fingerprint>>()
            val addedTextures = ArrayList<com.celestial.latent.develop.Texture>()
            val maps = HashMap<Uri, Bitmap>()
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                thumbnailOf(uri)?.let { bmp ->
                    added += uri to Fingerprint.of(bmp)
                    // Grain, halation, bloom and glare are read off the picture rather than
                    // searched for: they leave signatures a measurement can find directly.
                    val crop = grainCropOf(uri)
                    addedTextures += com.celestial.latent.develop.Texture.of(bmp, crop)
                    crop?.recycle()
                    maps[uri] = bmp
                }
            }
            references = references + added
            textures = textures + addedTextures
            thumbs = thumbs + maps
            status = "${references.size} references"
            busy = false
        }.start()
    }

    var testIsRaw by remember { mutableStateOf(com.celestial.latent.develop.LookSession.testIsRaw) }
    // The test shot is measured as soon as it is chosen, so the screen can say what it and the
    // references have in common before anything is built.
    var testFingerprint by remember { mutableStateOf<Fingerprint?>(null) }
    val pickTest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // A JPEG works as a test shot too; it just has to be decoded differently.
        val name = (runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment).orEmpty()
        testIsRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true) || name.endsWith(".arw", true)
        testShot = uri
        status = if (testIsRaw) "test shot chosen (RAW)" else "test shot chosen (JPEG)"
        if (!testIsRaw) Thread { thumbnailOf(uri)?.let { testFingerprint = Fingerprint.of(it) } }.start()
    }

    var progress by remember { mutableStateOf(com.celestial.latent.develop.LookSession.progress) }
    var result by remember { mutableStateOf(com.celestial.latent.develop.LookSession.result) }
    var resultBitmap by remember { mutableStateOf(com.celestial.latent.develop.LookSession.resultBitmap) }
    var saved by remember { mutableStateOf(com.celestial.latent.develop.LookSession.saved) }
    var expanded by remember { mutableStateOf<Bitmap?>(null) }
    // Adjustments made to the fit's answer by hand. These are the same controls the search
    // uses, so a nudge here refines its result rather than layering something on top of it.
    var tweak by remember { mutableStateOf(com.celestial.latent.develop.LookSession.tweak) }
    var tweaking by remember { mutableStateOf(false) }
    // A change that arrives while a develop is running is not thrown away: it runs again when
    // that one finishes, so the picture always ends up matching the controls.
    var rerenderPending by remember { mutableStateOf(false) }
    // Naming is the user's, not the app's: a stock called "Celestial 17" tells you nothing and
    // was invented without asking.
    var stockName by remember { mutableStateOf("") }
    // Bumped on save and delete so the list of built films re-reads.
    var stocksRevision by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    /** Re-develops the test shot with the fit's emulsion plus whatever has been adjusted. */
    // What the references say about grain, halation, bloom and glare. Declared here rather than
    // further down in the layout, because the re-render and the save both need it.
    val texture = if (textures.isEmpty()) null else com.celestial.latent.develop.Texture.average(textures)

    fun rerender() {
        val best = result ?: return
        val src = testShot ?: return
        if (tweaking) { rerenderPending = true; return }
        tweaking = true
        Thread {
            do {
                rerenderPending = false
                val current = tweak
                runCatching {
                    com.celestial.latent.develop.Reconstruct.render(
                        context, best, src, testIsRaw, BASE_STOCK, current, texture,
                    )?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { resultBitmap = it }
                    }
                }
                // If the controls moved while that was developing, develop once more.
            } while (rerenderPending)
            tweaking = false
        }.start()
    }

    /** Builds an emulsion to match the references, on the test shot. */
    fun reconstruct(target: Fingerprint) {
        val src = testShot ?: return
        // Taken BEFORE the result is cleared below — read afterwards it would always be empty,
        // and every build would quietly start from scratch.
        val previous = result?.shape
        busy = true
        result = null
        resultBitmap = null
        saved = ""
        Thread {
            val best = com.celestial.latent.develop.Reconstruct.run(
                context = context,
                target = target,
                testShot = src,
                isRaw = testIsRaw,
                baseStock = BASE_STOCK,
                // A second build continues from the first rather than starting over; "start
                // over" clears the result, and with it this starting point.
                startFrom = previous,
            ) { p ->
                progress = p
                p.best?.jpeg?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { resultBitmap = it }
                }
            }
            result = best
            busy = false
        }.start()
    }

    val target = if (references.isEmpty()) null else Fingerprint.average(references.map { it.second })
    val spread = if (references.size > 1) Fingerprint.spread(references.map { it.second }) else 0f

    expanded?.let { bmp ->
        Box(
            Modifier.fillMaxSize().background(Color(0xF2000000))
                .combinedClickable(onClick = { expanded = null }),
            contentAlignment = Alignment.Center,
        ) {
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
            Text(
                "tap to close",
                color = LatentColors.TextDim, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("FILM BUILDER", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 3.sp)
            if (!com.celestial.latent.develop.LookSession.isEmpty) {
                Text(
                    "start over", color = LatentColors.TextDim, fontSize = 11.sp,
                    modifier = Modifier.combinedClickable(onClick = {
                        Haptics.tick(context)
                        com.celestial.latent.develop.LookSession.clear()
                        references = emptyList(); textures = emptyList(); thumbs = emptyMap()
                        testShot = null; result = null; resultBitmap = null; progress = null; saved = ""
                        tweak = com.celestial.latent.develop.Tweak()
                    }).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text("‹", color = LatentColors.Text, fontSize = 20.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 8.dp))
        }

        // The films built so far, with a way to remove one. Deleting is two taps, so a slip
        // cannot lose a film it took minutes to build.
        val stocks = remember(stocksRevision) { com.celestial.latent.develop.Recipes.stocks(context) }
        if (stocks.isNotEmpty()) {
            Section("YOUR FILMS")
            stocks.forEach { (name, _) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = LatentColors.Text, fontSize = 12.sp)
                    if (confirmDelete == name) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("delete", color = LatentColors.Amber, fontSize = 11.sp,
                                modifier = Modifier.combinedClickable(onClick = {
                                    Haptics.click(context)
                                    com.celestial.latent.develop.Recipes.deleteStock(context, name)
                                    confirmDelete = null
                                    stocksRevision++
                                    saved = "deleted $name"
                                }).padding(4.dp))
                            Text("keep", color = LatentColors.TextDim, fontSize = 11.sp,
                                modifier = Modifier.combinedClickable(onClick = { confirmDelete = null }).padding(4.dp))
                        }
                    } else {
                        Text("remove", color = LatentColors.TextDim, fontSize = 11.sp,
                            modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); confirmDelete = name }).padding(4.dp))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        Section("1 · REFERENCES")
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            references.take(3).forEach { (uri, _) ->
                thumbs[uri]?.let { bmp ->
                    Image(
                        bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            repeat((3 - references.size).coerceAtLeast(0)) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); pickRefs.launch(arrayOf("image/*")) }),
                contentAlignment = Alignment.Center,
            ) { Text("+", color = LatentColors.TextDim, fontSize = 18.sp) }
        }
        Text(
            if (references.isEmpty()) "images in the look you want — a dozen is plenty"
            else "${references.size} images · they disagree by ${"%.2f".format(spread)}" +
                // The distance scale changed when the figures were put on a common footing, so this
                // threshold moved with it: a set of different scenes sharing one look sits well
                // below this, while a set with no look in common goes above it.
                (if (spread > 0.9f) " — quite a scattered set" else ""),
            color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 18.dp),
        )

        // Everything the screen holds is mirrored into the session, so a back gesture does not
    // throw away a set of references and a fit that took minutes.
    LaunchedEffect(references, textures, thumbs, testShot, testIsRaw, result, resultBitmap, progress, saved, tweak) {
        com.celestial.latent.develop.LookSession.let { s ->
            s.references = references; s.textures = textures; s.thumbs = thumbs
            s.testShot = testShot; s.testIsRaw = testIsRaw
            s.result = result; s.resultBitmap = resultBitmap; s.progress = progress; s.saved = saved
            s.tweak = tweak
        }
    }

        texture?.let { t ->
            Section("READ FROM THEM")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LatentColors.Surface).padding(13.dp)) {
                Reading("grain", if (!t.grainMeasured) "left to the film — references too small to show it" else (if (t.grainFineness > 0.55f) "fine · " else "coarse · ") + "%.2f".format(t.grainAmount))
                Reading("halation", (if (t.halationAmount > 0.8f) "strong · " else "gentle · ") + "%.2f".format(t.halationAmount))
                Reading("bloom", t.bloomFamily.replace('_', ' ') + " · " + "%.2f".format(t.bloomAmount))
                Reading("veiling glare", "%.1f%%".format(t.glarePercent))
                Text(
                    "measured from the references, not guessed — the fit below changes only the emulsion's colour and tone",
                    color = LatentColors.Line, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // What the two sets share decides what can be matched at all, so it is worked out and
        // shown before a fit is started rather than left to be discovered in the result.
        val shared = remember(references, testFingerprint) {
            val ref = if (references.isEmpty()) null else Fingerprint.average(references.map { it.second })
            val shot = testFingerprint
            if (ref == null || shot == null) emptyList() else {
                val j = Fingerprint.judgeable(ref, shot)
                Fingerprint.LABELS.indices.mapNotNull { i -> if (j[i] > 0.01f) Fingerprint.LABELS[i] else null }
            }
        }

        Section("2 · TEST SHOT")
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (testShot == null) "choose a photo of yours" else "chosen") { pickTest.launch(arrayOf("image/*", "image/x-adobe-dng", "application/octet-stream")) }
        }
        Text(
            "RAW or JPEG — a blank sheet to try each emulsion on. Nothing is written to it.",
            color = LatentColors.TextDim, fontSize = 11.sp,
        )
        // The fit can only judge what the test shot can show: a picture with no greens and
        // nothing neutral never tells it what the look does to foliage or to grey.
        Text(
            "Choose one with a face, something green and something neutral in it. A shot with none of those leaves the fit guessing.",
            color = LatentColors.Line, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (shared.isNotEmpty()) {
            Text(
                "both sets show: " + shared.joinToString(", "),
                color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            val missing = Fingerprint.LABELS.filterNot { it in shared }
            if (missing.isNotEmpty()) Text(
                "not comparable here: " + missing.joinToString(", ") + " — these are left out of the match rather than guessed at",
                color = LatentColors.Line, fontSize = 11.sp, modifier = Modifier.padding(bottom = 18.dp),
            ) else Spacer(Modifier.height(14.dp))
        } else Spacer(Modifier.height(10.dp))

        if (status.isNotEmpty()) Text(status, color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 14.dp))

        val canRun = target != null && testShot != null && !busy
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                Pill("stop") { com.celestial.latent.develop.Reconstruct.cancelled = true }
            } else {
                Pill(if (result == null) "build the film" else "refine", accent = canRun) {
                    if (canRun) reconstruct(target!!)
                }
            }
        }

        progress?.let { p ->
            Section("3 · BUILDING")
            Box(Modifier.fillMaxWidth().height(2.dp).background(LatentColors.Surface)) {
                Box(
                    Modifier.fillMaxWidth(if (p.total == 0) 0f else (p.tried.toFloat() / p.total).coerceIn(0f, 1f))
                        .height(2.dp).background(LatentColors.Amber),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.note, color = LatentColors.TextDim, fontSize = 11.sp)
                Text("${p.tried} of ${p.total}", color = LatentColors.Amber, fontSize = 11.sp)
            }

            // Reference beside the closest attempt, so the eye can judge as it goes.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ref = references.firstOrNull()?.let { thumbs[it.first] }
                if (ref != null) {
                    Image(ref.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp))
                            .combinedClickable(onClick = { expanded = ref }))
                } else Box(Modifier.weight(1f).aspectRatio(3f / 4f))
                val made = resultBitmap
                if (made != null) {
                    // Tap to see it properly: a thumbnail is no way to judge a film.
                    Image(made.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp))
                            .background(LatentColors.Surface)
                            .combinedClickable(onClick = { expanded = made }))
                } else Box(Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)).background(LatentColors.Surface))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("REFERENCE · TAP", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    "CLOSEST · " + com.celestial.latent.develop.Reconstruct.percent(p.best?.distance),
                    color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp,
                )
            }
        }

        result?.let { best ->
            Section("THE EMULSION")
            val s = best.shape
            listOf(
                "layer speed" to s.speed,
                "where each layer responds" to s.centre,
                "density built" to s.height,
                "how gradually" to s.width,
                "spectral shift (nm)" to s.spectralShift,
                "print balance (Y M)" to floatArrayOf(s.yFilter, s.mFilter),
                "print exposure, contrast" to floatArrayOf(s.printExposure, s.printContrast),
            ).forEach { (label, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = LatentColors.TextDim, fontSize = 11.sp)
                    Text(
                        v.joinToString("  ") { "%+.2f".format(it) },
                        color = LatentColors.Text, fontSize = 11.sp,
                    )
                }
            }
            Text(
                "red  green  blue — a film that did not exist until now, built from " + BASE_STOCK.replace('_', ' ') + "'s form",
                color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )

            // Adjust the fit's answer by eye. These are the same controls the search uses, so a
            // nudge refines its result rather than layering something over it — and what is
            // saved is what is shown.
            Section("ADJUST")
            Tweaker("Brightness", tweak.brightness, -1.5f, 1.5f, "%+.2f EV",
                onChange = { tweak = tweak.copy(brightness = it) }, onRelease = { rerender() })
            Tweaker("Warm / cool", tweak.warmCool, -12f, 12f, "%+.1f",
                onChange = { tweak = tweak.copy(warmCool = it) }, onRelease = { rerender() })
            Tweaker("Green / magenta", tweak.greenMagenta, -12f, 12f, "%+.1f",
                onChange = { tweak = tweak.copy(greenMagenta = it) }, onRelease = { rerender() })
            Tweaker("Diffusion", tweak.diffusion, 0f, 1f, "%.2f",
                onChange = { tweak = tweak.copy(diffusion = it) }, onRelease = { rerender() })
            if (tweak.diffusion > 0.001f) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("glimmerglass", "black pro mist", "pro mist", "cinebloom").forEach { name ->
                        val id = name.replace(' ', '_')
                        val on = tweak.diffusionFamily == id
                        Text(
                            name, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (on) LatentColors.Amber else LatentColors.Surface)
                                .combinedClickable(onClick = { tweak = tweak.copy(diffusionFamily = id); rerender() })
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Text("Output", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SRGB" to "sRGB", "DISPLAY_P3" to "Display P3", "REC709_24" to "Rec.709", "ADOBE_RGB" to "Adobe RGB").forEach { (id, label) ->
                    val on = tweak.outputSpace == id
                    Text(
                        label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (on) LatentColors.Amber else LatentColors.Surface)
                            .combinedClickable(onClick = { tweak = tweak.copy(outputSpace = id); rerender() })
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (tweaking) Text("re-developing…", color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(18.dp))

            androidx.compose.material3.OutlinedTextField(
                value = stockName,
                onValueChange = { stockName = it.take(28) },
                singleLine = true,
                label = { Text("name this film", color = LatentColors.TextDim, fontSize = 12.sp) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LatentColors.TextBright,
                    unfocusedTextColor = LatentColors.TextBright,
                    focusedBorderColor = LatentColors.Amber,
                    unfocusedBorderColor = LatentColors.Line,
                    cursorColor = LatentColors.Amber,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("save as a stock", accent = stockName.isNotBlank()) {
                    if (stockName.isBlank()) saved = "give it a name first" else {
                    val name = stockName.trim()
                    val id = com.celestial.latent.develop.Reconstruct.save(context, best.shape, BASE_STOCK, name)
                    saved = if (id == null) "could not save" else {
                        // The stock carries what was read from the references: its own grain,
                        // halation, bloom and glare, not the defaults.
                        // The emulsion is in the profile; how it is printed is in the recipe.
                        // Both are needed, or the saved stock will not match what was built.
                        // One builder for the preview, the adjustments and the stock, so what
                        // is saved is exactly what is on screen.
                        val paper = com.celestial.latent.develop.Emulsion.baseProfile(context, BASE_STOCK)
                            ?.optJSONObject("info")?.optString("target_print")?.takeIf { it.isNotBlank() }
                        val recipe = com.celestial.latent.develop.Reconstruct.recipeFor(
                            id, best, paper, tweak, texture,
                        )
                        com.celestial.latent.develop.Recipes.save(context, name, recipe)
                        Haptics.click(context)
                        stockName = ""
                        stocksRevision++
                        "saved as $name — it is at the start of the film strip"
                    }
                    }
                }
            }
            if (saved.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (saved.startsWith("saved")) LatentColors.Amber else LatentColors.Surface)
                        .padding(13.dp),
                ) {
                    Text(
                        saved,
                        color = if (saved.startsWith("saved")) LatentColors.AmberInk else LatentColors.Text,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** A slider that reports as it moves, for adjusting a finished fit. */
@Composable
private fun Tweaker(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    format: String,
    onChange: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = LatentColors.TextDim, fontSize = 11.sp)
            Text(format.format(value), color = LatentColors.Text, fontSize = 11.sp)
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(from, to),
            // The number follows the finger; the develop happens when it is let go. Developing
            // during the drag would start dozens of renders and waste all but one.
            onValueChange = onChange,
            onValueChangeFinished = onRelease,
            valueRange = from..to,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = LatentColors.Amber,
                activeTrackColor = LatentColors.Amber,
                inactiveTrackColor = LatentColors.Surface,
            ),
        )
    }
}

@Composable
private fun Reading(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LatentColors.TextDim, fontSize = 11.sp)
        Text(value, color = LatentColors.Text, fontSize = 11.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    Text(
        label,
        color = if (accent) LatentColors.AmberInk else LatentColors.Text, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (accent) LatentColors.Amber else LatentColors.Surface)
            .combinedClickable(onClick = { Haptics.tick(context); onClick() })
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
