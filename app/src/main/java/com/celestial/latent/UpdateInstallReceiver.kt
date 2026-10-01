package com.celestial.latent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Where Android's installer reports back on an update.
 *
 * Android does not answer an install session directly; it sends this receiver a message. Either
 * it wants the user to confirm — in which case it supplies its own confirmation screen, which is
 * shown here — or the install has succeeded or failed. Success usually goes unheard: Latent is
 * replaced and restarted before the message would arrive.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("Latent", "installer status $status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.e("Latent", "could not show the install confirmation", it) }
                }
                Updater.onInstallStatus?.invoke(Updater.State.Installing("confirm the install to finish"))
            }
            PackageInstaller.STATUS_SUCCESS ->
                Updater.onInstallStatus?.invoke(Updater.State.Installing("installed — restarting"))
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Updater.onInstallStatus?.invoke(Updater.State.Failed("install cancelled"))
            else ->
                Updater.onInstallStatus?.invoke(
                    Updater.State.Failed("install failed" + (message?.let { ": $it" } ?: "")),
                )
        }
    }
}
