package com.celestial.latent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.celestial.latent.camera.CameraReport
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.LatentTheme

open class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        // Before anything reads the lens list or the saved default lens.
        com.celestial.latent.camera.Lenses.discover(this)
        enableEdgeToEdge()
        // 87a: the chosen theme, read before the first frame
        LatentColors.load(this)
        // 88b: the window behind the first frame in the theme's own colour
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(LatentColors.Background.toArgb()))
        setContent {
            // Clay is light: dark status and navigation icons, set again whenever the theme changes
            val light = LatentColors.Light
            SideEffect { systemBars(light) }
            LatentTheme { Root() }
        }
    }

    // 88b: the launcher icon follows the theme, changed as the app leaves the screen
    override fun onStop() {
        super.onStop()
        AppIcon.sync(this, LatentColors.theme)
    }

    private fun systemBars(light: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            c.setSystemBarsAppearance(if (light) m else 0, m)
        } else {
            @Suppress("DEPRECATION")
            val f = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) window.decorView.systemUiVisibility or f else window.decorView.systemUiVisibility and f.inv()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val f = ShutterBus.onShutter
            if (f != null) { if (event?.repeatCount == 0) f(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    var darkroomSource by remember { mutableStateOf<android.net.Uri?>(null) }
    var darkroomIsRaw by remember { mutableStateOf(true) }
    var burstRoll by remember { mutableStateOf<android.net.Uri?>(null) }
    var darkroomBack by remember { mutableStateOf("roll") }   // where Back leaves the darkroom to
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    // 84a: the camera screen in use is the mode row's: Xiaomi's for Night/Auto, ours otherwise
    fun cameraHome() = if (android.os.Build.VERSION.SDK_INT >= 31 && CameraModes.xiaomi(context).isNotEmpty()) "extension" else "camera"
    // back in Night or Auto if that is the mode the app was left in
    var screen by remember { mutableStateOf(cameraHome()) }
    crash?.let { text ->
        Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Text("Celestial Darkroom crashed last time", color = LatentColors.TextBright, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                    context.startActivity(Intent.createChooser(send, "Share crash"))
                }, colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Amber, contentColor = LatentColors.AmberInk)) { Text("Share") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { CrashLog.clear(context); crash = null },
                    colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Surface, contentColor = LatentColors.TextBright)) { Text("Dismiss") }
            }
            Spacer(Modifier.height(12.dp))
            Text(text, color = LatentColors.Text, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
        return
    }
    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    // Pick up a previously copied engine folder, so films built earlier are visible straight
    // away rather than appearing only after something happens to look for them.
    LaunchedEffect(Unit) { com.celestial.latent.develop.EngineAssets.attach(context) }
    // Shown once per launch, over the camera, which opens behind it. Declared after settings,
    // which it reads.
    var opening by remember { mutableStateOf(settings.openingAnimation) }
    var controllerRef by remember { mutableStateOf<com.celestial.latent.camera.CameraController?>(null) }
    var vendorEcho by remember { mutableStateOf("") }

    if (!granted) {
        Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().padding(24.dp)) {
            Text("CELESTIAL DARKROOM", color = LatentColors.TextBright, fontSize = 16.sp, letterSpacing = 4.sp)
            Spacer(Modifier.height(16.dp))
            Text("Celestial Darkroom needs the camera to work. Photos are saved to DCIM/Latent as DNG.", color = LatentColors.Text, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Amber, contentColor = LatentColors.AmberInk)) { Text("Allow camera") }
        }
        return
    }
    // System back gesture: always one step up, never out of the app while inside a sub-screen.
    BackHandler(enabled = screen != "camera") {
        if (screen == "extension") CameraModes.setXiaomi(context, "")
        if (screen == "darkroom" && darkroomBack == "roll") darkroomSource?.let { PhotoFlight.back(it) }
        screen = when (screen) { "settings", "extension" -> "camera"; "roll" -> cameraHome(); "darkroom" -> darkroomBack; "burst" -> "roll"; else -> "settings" }
    }

    // The overlay is drawn after the screens, so it covers them while the camera starts behind.
    Box(Modifier.fillMaxSize()) {
        when (screen) {
        "report" -> ReportScreen(onBack = { screen = "settings" })
        "settings" -> SettingsScreen(
            settings = settings,
            onChange = { s -> settings = s; AppSettings.save(context, s) },
            onOpenReport = { screen = "report" },
            onOpenVendor = { screen = "vendor" },
            onOpenProbe = { screen = "probe" },
            onOpenDepth = { screen = "depth" },
            onOpenLogs = { screen = "logs" },
            onOpenExtension = { screen = "extension" },
            onOpenAbout = { screen = "about" },
            onOpenHelp = { screen = "help" },
            onBack = { screen = "camera" },
        )
        "look" -> LookScreen(settings = settings, onBack = { screen = "roll" })
        "roll" -> RollScreen(
            settings = settings, onSettingsChange = { s -> settings = s; AppSettings.save(context, s) },
            onOpenDarkroom = { uri, isRaw -> darkroomSource = uri; darkroomIsRaw = isRaw; darkroomBack = "roll"; screen = "darkroom" },
            onOpenLookBuilder = { screen = "look" },
            onOpenBurst = { uri -> burstRoll = uri; screen = "burst" },
            onBack = { screen = cameraHome() },
        )
        "darkroom" -> darkroomSource?.let { src ->
            DarkroomScreen(
                source = src, isRaw = darkroomIsRaw,
                initial = com.celestial.latent.develop.Recipes.current(context).copy(film = settings.film),
                onRecipeChanged = { r -> if (r.film != settings.film) { settings = settings.copy(film = r.film); AppSettings.save(context, settings) } },
                onBack = { if (darkroomBack == "roll") PhotoFlight.back(src); screen = darkroomBack },
            )
        } ?: run { screen = "roll" }
        "burst" -> burstRoll?.let { b ->
            BurstScreen(
                roll = b,
                onOpenDarkroom = { uri -> darkroomSource = uri; darkroomIsRaw = true; darkroomBack = "burst"; screen = "darkroom" },
                onBack = { screen = "roll" },
            )
        } ?: run { screen = "roll" }
        "extension" -> if (android.os.Build.VERSION.SDK_INT >= 31) ExtensionScreen(
            settings = settings,
            onSettingsChange = { s -> settings = s; AppSettings.save(context, s) },
            onOpenRoll = { screen = "roll" },
            // leaving Xiaomi's screen goes back to the camera's own modes, and the app reopens there
            onBack = { CameraModes.setXiaomi(context, ""); screen = "camera" },
        ) else { screen = "camera" }
        "logs" -> LogScreen(onBack = { screen = "settings" })
        "about" -> AboutScreen(onBack = { screen = "settings" })
        "help" -> HelpScreen(onBack = { screen = "settings" })
        "probe" -> ProbeScreen(settings = settings, onBack = { screen = "settings" })
        "depth" -> DepthTestScreen(onBack = { screen = "settings" })
        "vendor" -> VendorScreen(
            settings = settings,
            exposedKeys = controllerRef?.exposedVendorKeys() ?: emptyList(),
            lastEcho = vendorEcho,
            onChange = { s -> settings = s; AppSettings.save(context, s) },
            onBack = { screen = "settings" },
        )
        else -> CameraScreen(
            settings = settings,
            onSettingsChange = { s -> settings = s; AppSettings.save(context, s) },
            onOpenRoll = { screen = "roll" },
            onOpenDarkroom = { uri, isRaw -> darkroomSource = uri; darkroomIsRaw = isRaw; darkroomBack = "roll"; screen = "darkroom" },
            onOpenSettings = { screen = "settings" },
            onOpenExtension = { screen = "extension" },
            onLensChanged = { l -> if (settings.rememberLens) { settings = settings.copy(defaultLensId = l.physicalId); AppSettings.save(context, settings) } },
            onController = { c -> controllerRef = c },
            onVendorEcho = { e -> vendorEcho = e },
        )
    }

        // 88a: a photo flying between the roll and the darkroom, over both
        PhotoFlightLayer()
        if (opening) BlackHoleIntro(onDone = { opening = false })
    }
}

@Composable
private fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Surface, contentColor = LatentColors.TextBright)) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { report = runCatching { CameraReport.build(context) }.getOrElse { "Report failed:\n$it" } },
                colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Amber, contentColor = LatentColors.AmberInk)) { Text("Read cameras") }
            Spacer(Modifier.width(12.dp))
            Button(enabled = report != null, onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report ?: "") }
                context.startActivity(Intent.createChooser(send, "Share report"))
            }, colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Surface, contentColor = LatentColors.TextBright)) { Text("Share") }
        }
        Spacer(Modifier.height(12.dp))
        Text(report ?: "Tap “Read cameras”.", color = LatentColors.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
    }
}
