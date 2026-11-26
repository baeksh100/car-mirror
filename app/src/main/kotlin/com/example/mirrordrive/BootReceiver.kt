package com.example.mirrordrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Car head units boot when the ignition turns on. Start the receiver right away so the iPhone can
 * find it without anyone touching the screen, and bring the mirror screen up when Android allows a
 * background activity start (apps holding the "display over other apps" permission may do so; it is
 * the same permission window mode already asks for).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (!CarSettings.autoStart(context)) return
        Log.i(TAG, "boot (${intent.action}): auto-starting CarMirror")
        runCatching {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }.onFailure { Log.e(TAG, "service start failed", it) }
        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.e(TAG, "activity start failed", it) }
        }
    }

    companion object {
        private const val TAG = "CarMirror-boot"
        // Head-unit ROMs use a variety of "powered on" broadcasts besides the standard one.
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
