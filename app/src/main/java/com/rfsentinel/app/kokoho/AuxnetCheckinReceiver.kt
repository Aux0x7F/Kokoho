package com.rfsentinel.app.kokoho

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rfsentinel.app.service.ScanForegroundService

/**
 * Periodic non-GMS Auxnet background check-in receiver.
 * Wakes every 5 minutes via [android.app.AlarmManager.setAndAllowWhileIdle] while monitoring is active,
 * executes `POST /v1/checkin`, and re-arms the next check-in.
 */
class AuxnetCheckinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (ScanForegroundService.isRunning) {
            KokohoHubClient.checkinNow(context)
            KokohoHubClient.schedulePeriodicCheckin(context)
        }
    }
}
