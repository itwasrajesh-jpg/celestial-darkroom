@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.camera.Lenses
import com.celestial.latent.ui.AppTheme
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.glow
import com.celestial.latent.ui.raised

@Composable
fun SettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit, onOpenReport: () -> Unit, onOpenVendor: () -> Unit, onOpenProbe: () -> Unit, onOpenDepth: () -> Unit, onOpenLogs: () -> Unit, onOpenExtension: () -> Unit, onOpenAbout: () -> Unit, onBack: () -> Unit, onOpenHelp: () -> Unit = {}, onOpenRecipeExport: () -> Unit = {}, onOpenRecipeImport: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).glow().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ back", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Settings", color = LatentColors.TextBright, fontSize = 24.sp)
        Spacer(Modifier.height(20.dp))

        // 74a: what every darkroom control does
        Section("App help")
        Text("Every control explained, tab by tab ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenHelp).padding(vertical = 10.dp))
        Spacer(Modifier.height(12.dp))

        // 87a: the app's colours. Changes at once; photos and the darkroom's photo mat are never tinted.
        val uiContext = LocalContext.current
        Section("Look")
        // 102c: where each theme's chip is, so the new colours spread out from the one tapped
        val themeChips = remember { HashMap<AppTheme, androidx.compose.ui.geometry.Offset>() }
        OptionRow(
            title = "Theme",
            subtitle = "Darkroom: warm black with amber, as always. Midnight: layered navy with soft shadows and lit edges. Clay: pale blue and white, soft and raised. Photos always sit on a dark mat.",
            options = AppTheme.entries.map { it.label to it },
            selected = LatentColors.theme,
            places = themeChips,
        ) { ThemeReveal.start(uiContext, themeChips[it], it) }
        // 105c
        ToggleRow("Breathing shutter", "In manual, when the meter reads 0 the shutter wakes, glows in the theme's colours and breathes, with small lights circling it. Turn off for a plain shutter.", settings.breathingShutter) { onChange(settings.copy(breathingShutter = it)) }

        Section("Exposure")
        OptionRow(
            title = "Anti-banding",
            subtitle = "Avoids flicker from mains-powered lights. India is 50 Hz. Only acts while shutter is automatic; with a manual shutter, pick 1/50, 1/100 or slower.",
            options = listOf("Auto" to AppSettings.ANTIBANDING_AUTO, "50 Hz" to AppSettings.ANTIBANDING_50HZ, "60 Hz" to AppSettings.ANTIBANDING_60HZ, "Off" to AppSettings.ANTIBANDING_OFF),
            selected = settings.antibanding,
        ) { onChange(settings.copy(antibanding = it)) }

        Section("Viewfinder")
        ToggleRow("Gridlines", "Rule-of-thirds lines over the preview", settings.gridlines) { onChange(settings.copy(gridlines = it)) }
        // 106a
        ToggleRow("Horizon lock", "Keeps the viewfinder level however the phone is tilted, and each photo comes out level. Straightening crops the edges a little (5° keeps about 81%). Up to 15°; past that, or pointing at the sky or floor, photos are kept as shot. The RAW is never changed: undo it with Straighten in FRAME.", settings.horizonLock) { onChange(settings.copy(horizonLock = it)) }

        Section("Files")
        OptionRow(
            title = "Format",
            subtitle = "RAW is always saved. The JPEG is the camera driver's own processed copy, handy for sharing; it is not the film-developed result.",
            options = listOf("RAW" to false, "RAW + JPEG" to true),
            selected = settings.saveJpeg,
        ) { onChange(settings.copy(saveJpeg = it)) }

        Section("Shooting")
        if (Lenses.isXiaomi15Ultra) ToggleRow("In-sensor zoom at ×2", "Asks the driver for the sensor's native centre crop while ×2 is on (JPEG path; the RAW stays the full frame). Nothing is sent at normal zoom.", settings.inSensorZoomJpeg) { onChange(settings.copy(inSensorZoomJpeg = it)) }
        if (Lenses.isXiaomi15Ultra) ToggleRow("DCG on the main lens", "Dual conversion gain: the main sensor reads 14-bit with cleaner shadows (about 2.4× less noise in the deepest shadows on this phone). Runs at 30 fps, so no shutter is longer than 1/30 s. No JPEGs while it is on. The burst button keeps all 16 frames in one burst roll (see BURSTS in the roll). Not used at ×2. Found by the MotionCam team.", settings.dcgTest) { onChange(settings.copy(dcgTest = it)) }
        ToggleRow("Open tele lenses directly when zoomed", "Stops the logical camera handing the frame to another sensor mid-zoom (the visible switch and refocus).", settings.teleZoomDirect) { onChange(settings.copy(teleZoomDirect = it)) }
        ToggleRow("Volume buttons take the photo", "Either volume key acts as the shutter", settings.volumeShutter) { onChange(settings.copy(volumeShutter = it)) }
        ToggleRow("Haptics", "Click on the shutter, ticks on slider steps and lens changes", settings.haptics) { onChange(settings.copy(haptics = it)) }
        ToggleRow("Remember last lens", "Open on the lens you used last time", settings.rememberLens) { onChange(settings.copy(rememberLens = it)) }
        OptionRow(
            title = "Default lens",
            subtitle = "Used when the app opens (if not remembering the last one)",
            options = Lenses.ALL.map { it.label to it.physicalId },
            selected = settings.defaultLensId,
        ) { onChange(settings.copy(defaultLensId = it)) }

        // 91a: sounds made from a model of a real camera's parts; off until turned on, quiet when the phone is
        Section("Sounds")
        ToggleRow("Shutter sound", "The click of a real shutter, with your shutter speed between opening and closing: 1/500 is one click, 1/2 s is click … click. Plays at the media volume, only while the ringer is on; silent and vibrate stay quiet.", settings.soundShutter) {
            onChange(settings.copy(soundShutter = it)); if (it) Sounds.preview(Sounds.Camera.of(settings.soundCamera), false)
        }
        ToggleRow("Film wind", "The film winds on after each shot, and once after a burst or bracket.", settings.soundWind) {
            onChange(settings.copy(soundWind = it)); if (it) Sounds.preview(Sounds.Camera.of(settings.soundCamera), true)
        }
        val soundCtx = LocalContext.current
        if (settings.soundShutter || settings.soundWind || settings.soundDial) Sounds.mutedReason(soundCtx)?.let {
            Text(it, color = LatentColors.Amber, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        ToggleRow("Dial ticks", "A soft click-stop as the S and ISO dials turn.", settings.soundDial) { onChange(settings.copy(soundDial = it)) }
        if (settings.soundShutter || settings.soundWind) OptionRow(
            title = "Camera",
            subtitle = "Rangefinder: quiet cloth shutter and a smooth lever. SLR: the mirror slap and a ratcheting lever. Compact: leaf blades in the lens and a little motor wind. Tap to hear it.",
            options = Sounds.Camera.entries.map { it.label to it.key },
            selected = settings.soundCamera,
        ) { onChange(settings.copy(soundCamera = it)); Sounds.preview(Sounds.Camera.of(it), settings.soundWind) }

        Section("Film")
        UpdateRow()
        ToggleRow("Opening animation", "A black hole, traced live, as the name develops in. Drag and pinch to explore it; tap to close.", settings.openingAnimation) { onChange(settings.copy(openingAnimation = it)) }
        ToggleRow("Film in the viewfinder", "Draws the preview through the film look. Turn off to use the plain camera preview.", settings.filmPreview) { onChange(settings.copy(filmPreview = it)) }
        ToggleRow("Let the film level the exposure", "Off: the camera decides brightness — what you expose is what develops. On: the engine brightens or darkens every shot to its own target, which cancels out the EV dial.", settings.engineAutoExposure) { onChange(settings.copy(engineAutoExposure = it)) }
        ToggleRow("Develop every photo", "Single shots are developed with the selected film in the background. Bursts are never auto-developed.", settings.autoDevelop) { onChange(settings.copy(autoDevelop = it)) }
        val context = LocalContext.current
        OptionRow(
            title = "Cinema glow",
            subtitle = "How much the cinema films (Vision3 250D, 200T, 500T, Verita 200D) glow around lights. Classic: as they always have. True to film: less, because the real films carry a backing that stops most of the glow. Photos already developed keep theirs.",
            options = listOf("Classic" to false, "True to film" to true),
            selected = settings.trueCinemaGlow,
        ) {
            if (it != settings.trueCinemaGlow) {
                onChange(settings.copy(trueCinemaGlow = it))
                com.celestial.latent.develop.Presets.cinemaGlowChanged(context, settings.preset, it)
            }
        }
        // 78a: where a new photo's Ultra HDR glow starts; each photo keeps its own once moved in the darkroom
        if (com.celestial.latent.develop.HdrGlow.supported) {
            var hdrStart by remember { mutableStateOf(com.celestial.latent.develop.HdrGlows.defaultStops(context)) }
            OptionRow(
                title = "Ultra HDR glow for new photos",
                subtitle = "Developed JPEGs carry a hidden glow map: on HDR screens the brightest lights shine brighter than white, and every other screen shows the normal print. Each photo can change its own in the darkroom's DEVELOP tab.",
                options = com.celestial.latent.develop.HdrGlow.CHOICES.map { com.celestial.latent.develop.HdrGlow.label(it) to it },
                selected = hdrStart,
            ) { hdrStart = it; com.celestial.latent.develop.HdrGlows.setDefault(context, it) }
        }
        Text("Open the roll to reach the darkroom: tap the last-photo thumbnail on the camera screen, or long-press the film strip.", color = LatentColors.TextDim, fontSize = 12.sp)

        // 90a: recipes travel to another phone as one small file
        Section("Recipes")
        var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(Unit) {
            counts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.celestial.latent.develop.EngineAssets.attach(context)
                    com.celestial.latent.develop.Recipes.saved(context).size to com.celestial.latent.develop.Recipes.stocks(context).size
                }.getOrNull()
            }
        }
        counts?.let { (own, built) ->
            Text("$own of your ${if (own == 1) "recipe" else "recipes"} · $built ${if (built == 1) "film" else "films"} built here", color = LatentColors.Text, fontSize = 13.sp)
        }
        Text("Export recipes ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenRecipeExport).padding(vertical = 10.dp))
        Text("Import recipes ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenRecipeImport).padding(vertical = 10.dp))
        Text("Sends your darkroom recipes and the films you built, with their air and light, as one small file. Open it on the other phone with Import. Photos and per-photo edits stay here.", color = LatentColors.TextDim, fontSize = 12.sp)

        Section("Xiaomi processing (official extensions)")
        Text("Portrait / Night test ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenExtension).padding(vertical = 10.dp))
        Text("Xiaomi's own bokeh and night modes via Android's Camera Extensions. JPEG only, main camera.", color = LatentColors.TextDim, fontSize = 12.sp)

        Section("Diagnostics")
        if (Lenses.isXiaomi15Ultra) OptionRow(
            title = "Camera path",
            subtitle = "Which logical camera the lens is reached through, or open the lens directly. Vendor keys differ per path. Takes effect on next lens switch.",
            options = listOf("0" to "0", "6" to "6", "7" to "7", "Direct" to "direct"),
            selected = settings.cameraPath,
        ) { onChange(settings.copy(cameraPath = it)) }
        Text("Vendor tags & opmode ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenVendor).padding(vertical = 10.dp))
        Text("Vendor probe (find & test keys) ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenProbe).padding(vertical = 10.dp))
        Text("Depth test (the depth model) ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenDepth).padding(vertical = 10.dp))
        Text("Logs (app logcat) ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenLogs).padding(vertical = 10.dp))
        Text("Camera report ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenReport).padding(vertical = 10.dp))

        Text("About & credits ›", color = LatentColors.Amber, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onOpenAbout).padding(vertical = 10.dp))
        Spacer(Modifier.height(24.dp))
        Text("Celestial Darkroom v" + BuildConfig.VERSION_NAME + " · film modeling powered by spektrafilm", color = LatentColors.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title.uppercase(), color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
}

/**
 * Checking for and installing a new build, without leaving the app.
 *
 * Latent is not on a store, so this goes to the project's own releases: it compares the newest
 * version with the one running, downloads the APK, and hands it to Android's installer — which
 * asks before it does anything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<Updater.State>(Updater.State.Idle) }
    val version = remember { Updater.currentVersion(context) }

    // Android's installer answers through UpdateInstallReceiver; listen while this row is shown.
    DisposableEffect(Unit) {
        Updater.onInstallStatus = { s -> state = s }
        onDispose { Updater.onInstallStatus = null }
    }

    /** Downloads, then installs — or asks for the one-time permission first if it is missing. */
    fun fetchAndInstall(release: Updater.Release) {
        state = Updater.State.Downloading(0)
        Thread {
            val apk = Updater.download(context, release) { p -> state = Updater.State.Downloading(p) }
            state = when {
                apk == null -> Updater.State.Failed("the download did not finish")
                !Updater.canInstall(context) -> Updater.State.NeedsPermission(apk)
                else -> Updater.install(context, apk)
            }
        }.start()
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for an update", color = LatentColors.TextBright, fontSize = 15.sp)
                Text(
                    when (val s = state) {
                        is Updater.State.Idle -> "version $version"
                        is Updater.State.Checking -> "checking…"
                        is Updater.State.UpToDate -> "version ${s.version} — the newest there is"
                        is Updater.State.Unclear -> "latest release is tagged ${s.tag}; this build is ${s.current} — cannot tell which is newer"
                        is Updater.State.Available -> "version ${s.release.version} is available" +
                            (if (s.release.sizeBytes > 0) " · ${s.release.sizeBytes / 1024 / 1024} MB" else "")
                        is Updater.State.Downloading -> "downloading… ${s.percent}%"
                        is Updater.State.Installing -> s.note
                        is Updater.State.NeedsPermission -> "allow Celestial Darkroom to install apps, then tap install"
                        is Updater.State.Failed -> s.reason
                    },
                    color = if (state is Updater.State.Failed) LatentColors.Text else LatentColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val label = when (state) {
                is Updater.State.Available -> "update"
                is Updater.State.NeedsPermission -> "install"
                is Updater.State.Checking, is Updater.State.Downloading, is Updater.State.Installing -> "…"
                else -> "check"
            }
            Text(
                label, color = LatentColors.AmberInk, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill()
                    .combinedClickable(onClick = {
                        Haptics.tick(context)
                        when (val s = state) {
                            is Updater.State.Available -> fetchAndInstall(s.release)
                            is Updater.State.NeedsPermission -> {
                                // Granted on Android's own page; back here, the same tap installs.
                                state = if (Updater.canInstall(context)) Updater.install(context, s.apk)
                                else { Updater.requestInstallPermission(context); s }
                            }
                            is Updater.State.Checking, is Updater.State.Downloading, is Updater.State.Installing -> {}
                            else -> {
                                state = Updater.State.Checking
                                Thread { state = Updater.check(context) }.start()
                            }
                        }
                    }).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (state is Updater.State.NeedsPermission) {
            Text(
                "Android asks this once. After that, updates install from here.",
                color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LatentColors.TextBright, fontSize = 15.sp)
            Text(subtitle, color = LatentColors.TextDim, fontSize = 12.sp)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = LatentColors.AmberInk, checkedTrackColor = LatentColors.Amber, uncheckedThumbColor = LatentColors.Text, uncheckedTrackColor = LatentColors.Surface),
        )
    }
}

@Composable
private fun <T> OptionRow(title: String, subtitle: String, options: List<Pair<String, T>>, selected: T,
                          places: MutableMap<T, androidx.compose.ui.geometry.Offset>? = null, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, color = LatentColors.TextBright, fontSize = 15.sp)
        Text(subtitle, color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, v) ->
                val on = v == selected
                Text(
                    label,
                    color = if (on) LatentColors.AmberInk else LatentColors.Text,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .then(if (places != null) Modifier.onGloballyPositioned { places[v] = it.boundsInRoot().center } else Modifier)
                        .raised(RoundedCornerShape(999.dp), 3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .accentFill(on, LatentColors.Surface)
                        .combinedClickable(onClick = { onSelect(v) })
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}
