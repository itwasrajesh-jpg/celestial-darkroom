package com.celestial.latent

import android.content.Context
import com.celestial.latent.camera.Lenses

/** One vendor tag override, MotionCam-style. scope = "session" or "request"; type = i32/i64/f32/f64/u8. Arrays as comma/slash separated. */
data class VendorTag(val name: String, val scope: String, val type: String, val value: String, val lens: String = "all") {
    fun encode() = listOf(name, scope, type, value, lens).joinToString("\t")
    companion object {
        fun decode(s: String): VendorTag? {
            val p = s.split("\t")
            return when (p.size) {
                5 -> VendorTag(p[0], p[1], p[2], p[3], p[4])
                4 -> VendorTag(p[0], p[1], p[2], p[3], "all")   // entries saved before codes were per-lens
                else -> null
            }
        }
    }
}

/** User settings, persisted in SharedPreferences. Small on purpose. */
data class AppSettings(
    val antibanding: Int = ANTIBANDING_AUTO,   // Camera2 CONTROL_AE_ANTIBANDING_MODE values
    val gridlines: Boolean = true,
    val volumeShutter: Boolean = true,
    val defaultLensId: String = Lenses.DEFAULT.physicalId,
    val rememberLens: Boolean = true,
    val directOpen: Boolean = false,
    val saveJpeg: Boolean = false,      // RAW only, or RAW + JPEG side by side
    val cameraPath: String = "0",       // logical camera ID to route through ("0", "6", "7"...) or "direct"
    val opmode: Int = 0,                // vendor session operating mode; 0 = regular (applies to opmodeLens)
    val opmodeLens: String = "all",     // which lens the opmode applies to
    val vendorTags: List<VendorTag> = emptyList(),
    val inSensorZoomJpeg: Boolean = true,   // EnableInsensorZoom, sent only while ×2 is on (JPEG path; RAW stays 1x)
    val teleZoomDirect: Boolean = true,     // open a tele lens directly while zoomed, so the logical camera cannot switch sensors
    val timerSeconds: Int = 0,              // 0 / 3 / 10
    val burstMode: Boolean = false,         // tap = 16-frame burst (hold = single)
    val haptics: Boolean = true,
    val film: String = "kodak_portra_400",   // film used for the live preview and auto-develop
    val preset: String = "portra400_endura_wedding",  // the authored look; carries per-stock grain etc.
    val autoDevelop: Boolean = true,         // develop single shots in the background (never bursts)
    val filmPreview: Boolean = true,         // draw the viewfinder ourselves so the film look can be shown
    /**
     * When false (the default), the camera decides brightness: what you expose is what you see
     * and what develops. When true, the engine levels every shot to its own target, which
     * cancels out the EV dial.
     */
    val engineAutoExposure: Boolean = false,
    val openingAnimation: Boolean = true,
    /**
     * Turn each photo the way the phone was held. Latent saves every RAW upright-portrait, so a
     * shot taken sideways used to come out on its side; this records a quarter turn in the
     * photo's own framing (the RAW is untouched, and FRAME can still change it).
     */
    val autoRotate: Boolean = true,
    // Cinema mode: a motion-picture film printed on a cinema print stock, a Super 35 frame, a
    // widescreen crop, and optionally an 85B filter and film-still bars.
    val cinema: Boolean = false,
    val cinemaFilm: String = "kodak_vision3_250d",
    val cinemaPaper: String = "kodak_2383",
    val cinemaAspect: Float = 2.39f,
    val cinemaBars: Boolean = false,
    val cinema85b: Boolean = false,
) {
    companion object {
        const val ANTIBANDING_OFF = 0
        const val ANTIBANDING_50HZ = 1
        const val ANTIBANDING_60HZ = 2
        const val ANTIBANDING_AUTO = 3

        private const val FILE = "latent"

        fun load(ctx: Context): AppSettings {
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return AppSettings(
                antibanding = p.getInt("antibanding", ANTIBANDING_AUTO),
                gridlines = p.getBoolean("gridlines", true),
                volumeShutter = p.getBoolean("volumeShutter", true),
                defaultLensId = p.getString("defaultLensId", Lenses.DEFAULT.physicalId) ?: Lenses.DEFAULT.physicalId,
                rememberLens = p.getBoolean("rememberLens", true),
                directOpen = p.getBoolean("directOpen", false),
                saveJpeg = p.getBoolean("saveJpeg", false),
                cameraPath = p.getString("cameraPath", if (p.getBoolean("directOpen", false)) "direct" else "0") ?: "0",
                opmode = p.getInt("opmode", 0),
                opmodeLens = p.getString("opmodeLens", "all") ?: "all",
                vendorTags = (p.getString("vendorTags", "") ?: "").split("\n").mapNotNull { VendorTag.decode(it) },
                inSensorZoomJpeg = p.getBoolean("inSensorZoomJpeg", true),
                teleZoomDirect = p.getBoolean("teleZoomDirect", true),
                timerSeconds = p.getInt("timerSeconds", 0),
                burstMode = p.getBoolean("burstMode", false),
                haptics = p.getBoolean("haptics", true),
                film = p.getString("film", "kodak_portra_400") ?: "kodak_portra_400",
                preset = p.getString("preset", "portra400_endura_wedding") ?: "portra400_endura_wedding",
                autoDevelop = p.getBoolean("autoDevelop", true),
                filmPreview = p.getBoolean("filmPreview", true),
                engineAutoExposure = p.getBoolean("engineAutoExposure", false),
                openingAnimation = p.getBoolean("openingAnimation", true),
                autoRotate = p.getBoolean("autoRotate", true),
                cinema = p.getBoolean("cinema", false),
                cinemaFilm = p.getString("cinemaFilm", "kodak_vision3_250d") ?: "kodak_vision3_250d",
                cinemaPaper = p.getString("cinemaPaper", "kodak_2383") ?: "kodak_2383",
                cinemaAspect = p.getFloat("cinemaAspect", 2.39f),
                cinemaBars = p.getBoolean("cinemaBars", false),
                cinema85b = p.getBoolean("cinema85b", false),
            )
        }

        fun save(ctx: Context, s: AppSettings) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("antibanding", s.antibanding)
                .putBoolean("gridlines", s.gridlines)
                .putBoolean("volumeShutter", s.volumeShutter)
                .putString("defaultLensId", s.defaultLensId)
                .putBoolean("rememberLens", s.rememberLens)
                .putBoolean("directOpen", s.directOpen)
                .putBoolean("saveJpeg", s.saveJpeg)
                .putString("cameraPath", s.cameraPath)
                .putInt("opmode", s.opmode)
                .putString("opmodeLens", s.opmodeLens)
                .putString("vendorTags", s.vendorTags.joinToString("\n") { it.encode() })
                .putBoolean("inSensorZoomJpeg", s.inSensorZoomJpeg)
                .putBoolean("teleZoomDirect", s.teleZoomDirect)
                .putInt("timerSeconds", s.timerSeconds)
                .putBoolean("burstMode", s.burstMode)
                .putBoolean("haptics", s.haptics)
                .putString("film", s.film)
                .putString("preset", s.preset)
                .putBoolean("autoDevelop", s.autoDevelop)
                .putBoolean("filmPreview", s.filmPreview)
                .putBoolean("engineAutoExposure", s.engineAutoExposure)
                .putBoolean("openingAnimation", s.openingAnimation)
                .putBoolean("autoRotate", s.autoRotate)
                .putBoolean("cinema", s.cinema)
                .putString("cinemaFilm", s.cinemaFilm)
                .putString("cinemaPaper", s.cinemaPaper)
                .putFloat("cinemaAspect", s.cinemaAspect)
                .putBoolean("cinemaBars", s.cinemaBars)
                .putBoolean("cinema85b", s.cinema85b)
                .apply()
        }
    }
}

/** Lets the Activity forward hardware volume keys to whatever screen owns the shutter. */
object ShutterBus {
    @Volatile var onShutter: (() -> Unit)? = null
}
