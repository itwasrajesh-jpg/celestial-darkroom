package com.celestial.latent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 91a: camera sounds. Every sound is made in code from a model of a camera's parts (sounds/v2/scripts in the
 * project files: struck parts that bounce, springs, cloth and film friction, the body's own ring), baked as
 * six slightly different takes so no two shots sound copied. A shot is the shutter's "open" half, then the
 * real exposure time, then the "close" half, so 1/500 is one click and 1/2 s is click ... click.
 *
 * Plays at the phone's media volume (91b: the system-sounds volume is off on many phones), and only while
 * the ringer is on (silent and vibrate stay quiet). Where the phone says a shutter
 * sound is required by law (Japan, Korea), a shutter click always plays, ours or the system's.
 */
object Sounds {
    enum class Camera(val key: String, val label: String) {
        RANGEFINDER("rangefinder", "Rangefinder"), SLR("slr", "SLR"), COMPACT("compact", "Compact");
        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: RANGEFINDER }
    }

    @Volatile var shutterOn = false
    @Volatile var windOn = false
    @Volatile var dialOn = false
    @Volatile var camera = Camera.RANGEFINDER

    private const val TAKES = 6
    private const val MAX_GAP_MS = 1000L
    private var pool: SoundPool? = null
    private var app: Context? = null
    private val ids = HashMap<String, IntArray>()
    private val main = Handler(Looper.getMainLooper())
    private val last = HashMap<String, Int>()
    private var legal: MediaActionSound? = null
    @Volatile private var loaded = 0

    /** Every baked take by name, written out so the release build's resource shrinker keeps them. */
    private val RAW = mapOf(
        "rangefinder_open" to intArrayOf(R.raw.snd_rangefinder_open_1, R.raw.snd_rangefinder_open_2, R.raw.snd_rangefinder_open_3, R.raw.snd_rangefinder_open_4, R.raw.snd_rangefinder_open_5, R.raw.snd_rangefinder_open_6),
        "rangefinder_close" to intArrayOf(R.raw.snd_rangefinder_close_1, R.raw.snd_rangefinder_close_2, R.raw.snd_rangefinder_close_3, R.raw.snd_rangefinder_close_4, R.raw.snd_rangefinder_close_5, R.raw.snd_rangefinder_close_6),
        "rangefinder_wind" to intArrayOf(R.raw.snd_rangefinder_wind_1, R.raw.snd_rangefinder_wind_2, R.raw.snd_rangefinder_wind_3, R.raw.snd_rangefinder_wind_4, R.raw.snd_rangefinder_wind_5, R.raw.snd_rangefinder_wind_6),
        "slr_open" to intArrayOf(R.raw.snd_slr_open_1, R.raw.snd_slr_open_2, R.raw.snd_slr_open_3, R.raw.snd_slr_open_4, R.raw.snd_slr_open_5, R.raw.snd_slr_open_6),
        "slr_close" to intArrayOf(R.raw.snd_slr_close_1, R.raw.snd_slr_close_2, R.raw.snd_slr_close_3, R.raw.snd_slr_close_4, R.raw.snd_slr_close_5, R.raw.snd_slr_close_6),
        "slr_wind" to intArrayOf(R.raw.snd_slr_wind_1, R.raw.snd_slr_wind_2, R.raw.snd_slr_wind_3, R.raw.snd_slr_wind_4, R.raw.snd_slr_wind_5, R.raw.snd_slr_wind_6),
        "compact_open" to intArrayOf(R.raw.snd_compact_open_1, R.raw.snd_compact_open_2, R.raw.snd_compact_open_3, R.raw.snd_compact_open_4, R.raw.snd_compact_open_5, R.raw.snd_compact_open_6),
        "compact_close" to intArrayOf(R.raw.snd_compact_close_1, R.raw.snd_compact_close_2, R.raw.snd_compact_close_3, R.raw.snd_compact_close_4, R.raw.snd_compact_close_5, R.raw.snd_compact_close_6),
        "compact_wind" to intArrayOf(R.raw.snd_compact_wind_1, R.raw.snd_compact_wind_2, R.raw.snd_compact_wind_3, R.raw.snd_compact_wind_4, R.raw.snd_compact_wind_5, R.raw.snd_compact_wind_6),
        "dial" to intArrayOf(R.raw.snd_dial_1, R.raw.snd_dial_2, R.raw.snd_dial_3, R.raw.snd_dial_4, R.raw.snd_dial_5, R.raw.snd_dial_6),
    )

    /** Loads the sounds once (about 400 KB of small files); safe to call from every screen. */
    fun init(ctx: Context) {
        if (pool != null) return
        val c = ctx.applicationContext; app = c
        val p = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        p.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded++ else android.util.Log.w("Latent", "sounds: a take failed to load ($status)") }
        for ((n, raws) in RAW) ids[n] = IntArray(TAKES) { t -> p.load(c, raws[t], 1) }
    }

    /** The phone requires a shutter sound here (Android 13+ can tell us). */
    private fun mustPlay(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        return runCatching { MediaActionSound.mustPlayShutterSound() }.getOrDefault(false)
    }

    /** Why a sound would be silent right now, or null when it can play; for Settings and the log. */
    fun mutedReason(ctx: Context): String? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return when {
            am.ringerMode == AudioManager.RINGER_MODE_SILENT -> "The phone is on silent, so sounds are muted."
            am.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> "The phone is on vibrate, so sounds are muted."
            am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 -> "Media volume is at zero. Sounds play at the media volume."
            else -> null
        }
    }

    private fun report(what: String) {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        android.util.Log.i("Latent", "sound $what: shutter=$shutterOn wind=$windOn dial=$dialOn camera=${camera.key} loaded=$loaded/${TAKES * RAW.size} " +
            "ringer=${am?.ringerMode} media=${am?.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} must=${mustPlay()}")
    }

    private fun ringerOn(): Boolean {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    /** One of the takes, never the same one twice in a row. */
    @Synchronized private fun play(name: String, volume: Float = 1f) {
        val p = pool ?: return
        val takes = ids[name] ?: return
        var t = (0 until TAKES).random()
        if (t == last[name]) t = (t + 1 + (0 until TAKES - 1).random()) % TAKES
        last[name] = t
        if (takes[t] != 0) p.play(takes[t], volume, volume, 1, 0, 1f)
    }

    private fun playShutter(exposureNs: Long, volume: Float = 1f) {
        val k = camera.key
        play("${k}_open", volume)
        val gap = (exposureNs / 1_000_000L).coerceIn(1L, MAX_GAP_MS)
        main.postAtTime({ play("${k}_close", volume) }, SystemClock.uptimeMillis() + gap)
    }

    /** The shutter of one photo, with the real exposure between open and close; the film winds on after. */
    fun shot(exposureNs: Long) {
        report("shot ${exposureNs / 1000} µs")
        if (shutterOn && (ringerOn() || mustPlay())) playShutter(exposureNs)
        else if (mustPlay()) systemClick()
        if (windOn && ringerOn()) {
            val gap = (exposureNs / 1_000_000L).coerceIn(1L, MAX_GAP_MS)
            main.postDelayed({ play("${camera.key}_wind", 0.8f) }, gap + 360)
        }
    }

    /** One frame of a burst or bracket: the close half only, quick and lighter, so sixteen do not clatter. */
    fun burstFrame() {
        if (shutterOn && (ringerOn() || mustPlay())) play("${camera.key}_close", 0.7f)
        else if (mustPlay()) systemClick()
    }

    /** The last frame of a burst or bracket has been taken: the film winds on once. */
    fun burstDone() {
        if (windOn && ringerOn()) main.postDelayed({ play("${camera.key}_wind", 0.8f) }, 250)
    }

    /** A click-stop on the S or ISO dial. */
    fun dial() { if (dialOn && ringerOn()) play("dial", 0.9f) }

    /** Settings preview: plays whatever is asked even with the switch off, but still not on a silent phone. */
    fun preview(cam: Camera, wind: Boolean) {
        report("preview ${cam.key}")
        if (!ringerOn()) return
        val k = cam.key
        play("${k}_open")
        main.postDelayed({ play("${k}_close") }, 8)
        if (wind) main.postDelayed({ play("${k}_wind", 0.8f) }, 380)
    }

    @Synchronized private fun systemClick() {
        val s = legal ?: MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK); legal = it }
        s.play(MediaActionSound.SHUTTER_CLICK)
    }
}
