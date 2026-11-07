package com.celestial.latent

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Predefined haptic effects; respects the app's own Haptics setting. */
object Haptics {
    @Volatile var enabled = true

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    private fun play(ctx: Context, effect: Int) {
        if (!enabled) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    fun click(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK)
    fun heavy(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK)
    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK)
    fun double(ctx: Context) = play(ctx, VibrationEffect.EFFECT_DOUBLE_CLICK)

    /** 121a, the opening's gravity: [kind] 0 a low tick, 1 a click, 2 a thud, at [strength] 0..1. Android 12+
     *  plays the matching primitive where the motor has it; otherwise a short pulse at that strength, or, without
     *  strength control, the nearest predefined effect (the faintest ticks are skipped there). */
    fun gravity(ctx: Context, kind: Int, strength: Float) {
        if (!enabled) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        val s = strength.coerceIn(0.05f, 1f)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val prim = when (kind) {
                    0 -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                    1 -> VibrationEffect.Composition.PRIMITIVE_CLICK
                    else -> VibrationEffect.Composition.PRIMITIVE_THUD
                }
                if (v.arePrimitivesSupported(prim).all { it }) {
                    v.vibrate(VibrationEffect.startComposition().addPrimitive(prim, s).compose()); return
                }
            }
            if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(if (kind == 2) 45L else if (kind == 1) 14L else 9L, (s * 255).toInt().coerceIn(1, 255)))
            } else if (kind != 0 || s > 0.35f) {
                v.vibrate(VibrationEffect.createPredefined(when (kind) { 2 -> VibrationEffect.EFFECT_HEAVY_CLICK; 1 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_TICK }))
            }
        }
    }
}
