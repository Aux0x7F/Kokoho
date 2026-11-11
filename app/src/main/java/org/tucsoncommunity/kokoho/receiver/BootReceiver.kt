package org.tucsoncommunity.kokoho.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.util.Permissions
import org.tucsoncommunity.kokoho.util.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Prefs.autoStartOnBoot(context)) return
        if (Permissions.missingRequired(context).isNotEmpty()) return
        try {
            // BOOT_COMPLETED is exempt from background-start limits for the
            // connectedDevice type. The location type is usually refused here
            // (no while-in-use access at boot); the service falls back on its own.
            ScanForegroundService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not auto-start scanner", e)
        }
    }
}
