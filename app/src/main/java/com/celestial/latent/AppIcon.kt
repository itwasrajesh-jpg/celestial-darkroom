package com.celestial.latent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.celestial.latent.ui.AppTheme

/**
 * 88b: the app's screen as the manifest knows it. The launcher icons are aliases of this activity, so the
 * icon can follow the theme; MainActivity holds all the code and keeps its old name as the Darkroom icon.
 */
class CameraActivity : MainActivity()

/** 88b: the launcher icon follows the theme (manifest: .MainActivity = Darkroom, .IconMidnight, .IconClay). */
internal object AppIcon {
    private fun alias(t: AppTheme) = when (t) { AppTheme.DARKROOM -> ".MainActivity"; AppTheme.MIDNIGHT -> ".IconMidnight"; AppTheme.CLAY -> ".IconClay" }
    private val DEFAULT_ON = ".MainActivity"

    /**
     * Shows the icon for [theme] and hides the others. Called as the app goes into the background: some
     * launchers close an app whose icon changes, and nothing is lost then. The new icon is shown before the
     * old one is hidden, so there is never a moment with no icon at all.
     */
    fun sync(ctx: Context, theme: AppTheme) = runCatching {
        val pm = ctx.packageManager
        fun on(name: String): Boolean = when (pm.getComponentEnabledSetting(ComponentName(ctx, ctx.packageName + name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == DEFAULT_ON
            else -> false
        }
        fun set(name: String, enabled: Boolean) = pm.setComponentEnabledSetting(ComponentName(ctx, ctx.packageName + name),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        val want = alias(theme)
        if (!on(want)) set(want, true)
        AppTheme.entries.map { alias(it) }.filter { it != want && on(it) }.forEach { set(it, false) }
    }
}
