package org.tucsoncommunity.kokoho.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.tucsoncommunity.kokoho.MainActivity
import org.tucsoncommunity.kokoho.R
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.util.Permissions

/** Home-screen widget: scanner status, live counts, and a start/stop button. */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (ScanForegroundService.isRunning) {
                ScanForegroundService.stop(context)
            } else if (Permissions.missingRequired(context).isEmpty()) {
                // Widget clicks are exempt from background foreground-service start limits.
                runCatching { ScanForegroundService.start(context) }.onFailure { openApp(context) }
            } else {
                openApp(context)
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "org.tucsoncommunity.kokoho.widget.TOGGLE"
        @Volatile private var devices = 0
        @Volatile private var flagged = 0
        @Volatile private var lastRenderedSig: String? = null
        @Volatile private var lastRenderAtMs: Long = 0L
        @Volatile private var cachedTogglePi: PendingIntent? = null
        @Volatile private var cachedBodyPi: PendingIntent? = null

        fun updateAll(context: Context, deviceCount: Int = devices, flaggedCount: Int = flagged) {
            devices = deviceCount
            flagged = flaggedCount
            val running = ScanForegroundService.isRunning
            val sig = "$running|$devices|$flagged"
            val now = System.currentTimeMillis()
            if (sig == lastRenderedSig && now - lastRenderAtMs < 30_000L) return
            if (now - lastRenderAtMs < 4_000L && sig == lastRenderedSig) return
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(app, StatusWidget::class.java))
            lastRenderedSig = sig
            lastRenderAtMs = now
            if (ids.isNotEmpty()) render(app, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext
            val running = ScanForegroundService.isRunning
            val views = RemoteViews(app.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widgetStatus, if (running) "Scanning" else "Idle")
            views.setTextViewText(
                R.id.widgetCounts,
                if (running) "$devices nearby · $flagged flagged" else "Tap ▶ to start"
            )
            views.setTextViewText(R.id.widgetToggle, if (running) "■" else "▶")
            views.setContentDescription(R.id.widgetToggle, if (running) "Stop scanning" else "Start scanning")
            val togglePi = cachedTogglePi ?: PendingIntent.getBroadcast(
                app, 0,
                Intent(app, StatusWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { cachedTogglePi = it }
            val bodyPi = cachedBodyPi ?: PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { cachedBodyPi = it }
            views.setOnClickPendingIntent(R.id.widgetToggle, togglePi)
            views.setOnClickPendingIntent(R.id.widgetBody, bodyPi)
            manager.updateAppWidget(ids, views)
        }

        private fun openApp(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
