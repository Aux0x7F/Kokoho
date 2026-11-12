package org.tucsoncommunity.kokoho.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.tucsoncommunity.kokoho.car.KokohoCarAppService
import org.tucsoncommunity.kokoho.MainActivity
import org.tucsoncommunity.kokoho.R
import org.tucsoncommunity.kokoho.detect.Hit
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.ui.DeviceDetailActivity

object NotificationHelper {
    const val CHANNEL_SERVICE = "rf_sentinel_service"
    const val CHANNEL_ALERTS = "rf_sentinel_alerts"
    const val CHANNEL_NAV = "rf_sentinel_navigation"
    private const val ALERT_NOTIFICATION_ID_BASE = 1000
    const val SERVICE_NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE, "Scanner status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Ongoing passive-scan service notification" }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS, "Equipment alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when watchlisted equipment is detected nearby"
            // Sound/vibration come from AlertPlayer (tier-coded patterns), not the channel.
            setSound(null, null)
            enableVibration(false)
        }

        val downloadChannel = NotificationChannel(
            CHANNEL_DOWNLOADS, "Camera downloads", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progress while known cameras are downloaded from OpenStreetMap" }

        val navChannel = NotificationChannel(
            CHANNEL_NAV, "Navigation", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "The next turn and your arrival time while you are being guided" }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
        nm.createNotificationChannel(navChannel)
        nm.createNotificationChannel(downloadChannel)
    }

    const val CHANNEL_DOWNLOADS = "rf_sentinel_downloads"
    private const val DOWNLOAD_NOTIFICATION_ID = 2

    /**
     * Progress of a camera download: a bar while [ongoing] (silent, can't be
     * swiped away), then the result, which can. Tapping opens the map.
     */
    fun showCameraDownload(context: Context, title: String, text: String, done: Int, total: Int, ongoing: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, DOWNLOAD_NOTIFICATION_ID,
            Intent(context, org.tucsoncommunity.kokoho.ui.MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (ongoing) b.setProgress(total, done, total == 0)
        runCatching { nm.notify(DOWNLOAD_NOTIFICATION_ID, b.build()) } // no-op without the notification permission
    }

    private const val MESH_PAIR_NOTIFICATION_ID = 3

    /** The Meshtastic node isn't paired: tapping opens Sentinel Link, which asks Android to pair (its PIN dialog). */
    fun showMeshPairing(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, MESH_PAIR_NOTIFICATION_ID,
            Intent(context, org.tucsoncommunity.kokoho.ui.SentinelLinkActivity::class.java)
                .putExtra(org.tucsoncommunity.kokoho.ui.SentinelLinkActivity.EXTRA_PAIR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "Tap to pair. Android then asks for the PIN: it's on the node's screen, or 123456."
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setContentTitle("Pair your Meshtastic node")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        runCatching { nm.notify(MESH_PAIR_NOTIFICATION_ID, n) } // no-op without the notification permission
    }

    fun cancelMeshPairing(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(MESH_PAIR_NOTIFICATION_ID)
    }

    /** Clears a progress notification left behind when the app was killed mid-download. */
    fun clearStaleCameraDownload(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val stale = runCatching { nm.activeNotifications.any { it.id == DOWNLOAD_NOTIFICATION_ID && it.isOngoing } }.getOrDefault(false)
        if (stale) nm.cancel(DOWNLOAD_NOTIFICATION_ID)
    }

    /**
     * Brings the app back exactly like tapping its launcher icon: the existing
     * task comes to the front on whatever screen you left, or MainActivity
     * starts fresh if the app isn't open.
     */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Evaluates the current Kokoho posture and returns the single persistent notification
     * summary (either "No threats detected" or "[comma separated agencies] [nearest current detection]").
     * Never includes verbose help text or spawns multiple notification entries (#13).
     */
    fun currentPostureSummary(context: Context): Pair<String, String?> {
        val snap = runCatching {
            org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.evaluate(context)
        }.getOrNull()
        val groups = snap?.monitorGroups.orEmpty()
        if (groups.isEmpty()) {
            return "No threats detected" to null
        }
        val agencies = groups.joinToString(", ") { it.agency.friendlyLabel }
        val nearest = groups.flatMap { it.distinctUnits }.minByOrNull { it.nearestEdgeDistanceM }
            ?: groups.first().leadThreat
        val nowMs = System.currentTimeMillis()
        val distPart = if (nearest.nearestEdgeDistanceM <= 15.0) {
            "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(context, nearest.uncertaintyRadiusM)}"
        } else {
            val edge = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(context, nearest.nearestEdgeDistanceM)
            val rad = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(context, nearest.uncertaintyRadiusM)
            "$edge (±$rad)"
        }
        val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, nearest.lastSeenMs)
        return "$agencies · $distPart" to staleness
    }

    /**
     * The single ongoing persistent notification (#13):
     * "No threats detected" or "[comma separated agencies] [nearest current detection]".
     * Zero help text, zero duplicate notification spam.
     */
    fun buildServiceNotification(context: Context, @Suppress("UNUSED_PARAMETER") text: String = ""): Notification {
        val discreet = Prefs.discreetMode(context)
        val (titleLine, subLine) = currentPostureSummary(context)
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(if (discreet) "Kokoho" else titleLine)
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Stop", stop)
        if (!discreet && !subLine.isNullOrBlank()) {
            builder.setContentText(subLine)
        }
        return builder.build()
    }

    fun updateServiceNotification(context: Context, text: String = "") {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // Cancel any legacy multi-entry alert notifications so only the single persistent notification exists
            runCatching {
                nm.activeNotifications.filter { it.id >= ALERT_NOTIFICATION_ID_BASE }.forEach { nm.cancel(it.id) }
            }
            nm.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(context, text))
        } catch (e: SecurityException) {
            // Notifications denied.
        }
    }

    /**
     * Updates the single persistent notification rather than spawning extra notification entries (#13).
     */
    fun sendMapAlert(
        context: Context, key: String, hit: Hit,
        @Suppress("UNUSED_PARAMETER") target: Class<out android.app.Activity> = org.tucsoncommunity.kokoho.ui.MapActivity::class.java,
        lat: Double? = null, lon: Double? = null,
        @Suppress("UNUSED_PARAMETER") online: String? = null,
        banner: Boolean = true,
        @Suppress("UNUSED_PARAMETER") shown: String? = null
    ) {
        recordAlert(context, key, null, hit, null, false, lat, lon, card = banner)
        updateServiceNotification(context)
        if (!banner) return
        val discreet = Prefs.discreetMode(context)
        toCar(context, if (discreet) "New alert" else hit.label, null)
    }

    /**
     * Adds the alert to the recent-alerts list (Android Auto). Positions are kept only
     * with Settings > GPS tagging on, like the match history.
     */
    private fun recordAlert(
        context: Context, key: String, mac: String?, hit: Hit, rssi: Int?, following: Boolean,
        lat: Double?, lon: Double?, card: Boolean = true
    ) {
        runCatching {
            val tag = Prefs.gpsTaggingEnabled(context)
            val here = ScanForegroundService.lastFix
            org.tucsoncommunity.kokoho.data.AlertLog.add(
                context, key, mac, hit, rssi, following,
                if (tag) lat ?: here?.latitude else null, if (tag) lon ?: here?.longitude else null
            )
        }
        // The full screen map shows it as a card (nothing is stored there: it is gone when the card is).
        if (card) runCatching {
            org.tucsoncommunity.kokoho.ui.AlertCards.publish(org.tucsoncommunity.kokoho.ui.AlertCards.Card(
                key, mac, hit.label, hit.evidence, hit.category, hit.confidence, following, rssi, lat, lon, System.currentTimeMillis()))
        }
    }

    /**
     * On Android Auto the alert goes into the car's message conversation ([CarMessages])
     * instead of a phone notification: that's what reaches a real car from the GitHub
     * APK. Nothing is posted while alerts are muted. True when handled.
     */
    private fun toCar(context: Context, text: String, mac: String?): Boolean {
        if (!org.tucsoncommunity.kokoho.car.CarState.connected) return false
        if (System.currentTimeMillis() >= Prefs.alertsSnoozedUntil(context)) CarMessages.post(context, text, mac)
        return true
    }

    /** One spoken-friendly line, e.g. "Nearby: Axon body camera. Strong match, about 40 m, signal -58 dBm." */
    internal fun carMessageText(title: String, hit: Hit, rssi: Int, distanceM: Double?): String =
        "$title. " + listOfNotNull(
            hit.tier.label.substringBefore(" -").replaceFirstChar { it.uppercase() } + " match",
            distanceM?.let { "about " + org.tucsoncommunity.kokoho.detect.DeviceIntel.formatDistance(it).removePrefix("~") },
            "signal $rssi dBm"
        ).joinToString(", ") + "."

    /** Posts the match notification; sound/vibration/voice are played by the caller. */
    /**
     * The car heads-up's line, readable at a glance: what it is, how sure, about how far,
     * signal, and how many other devices are flagged around you.
     * e.g. "BODY CAM · strong · ~40 m · near (-58 dBm) · +2 more flagged"
     */
    internal fun carAlertText(hit: Hit, rssi: Int, distanceM: Double?, othersFlagged: Int): String = listOfNotNull(
        hit.category.shortTag,
        hit.tier.label.substringBefore(" -"),
        distanceM?.let { org.tucsoncommunity.kokoho.detect.DeviceIntel.formatDistance(it) },
        "${ProximityUtil.band(rssi).lowercase()} ($rssi dBm)",
        othersFlagged.takeIf { it > 0 }?.let { "+$it more flagged" }
    ).joinToString(" · ")

    /** [via]: the linked Sentinel that heard it (Sentinel Link); [rssi] is then that phone's. */
    /** [banner] false: the alert is only logged (its watchlist entry turned the banner off). */
    fun sendAlert(context: Context, mac: String, hit: Hit, rssi: Int, following: Boolean = false, via: String? = null, banner: Boolean = true) {
        val rid = org.tucsoncommunity.kokoho.service.DeviceRegistry.get(mac)?.remoteId?.takeIf { it.hasPosition }
        recordAlert(context, mac, mac, hit, rssi, following, rid?.latitude, rid?.longitude, card = banner)
        updateServiceNotification(context)
        if (!banner) return
        val discreet = Prefs.discreetMode(context)
        val title = when {
            following -> "May be following you: ${hit.label}"
            via != null -> "Near $via: ${hit.label}"
            else -> "Nearby: ${hit.label}"
        }
        val snap = org.tucsoncommunity.kokoho.service.DeviceRegistry.get(mac)
        toCar(context, if (discreet) "New alert" else carMessageText(title, hit, rssi, snap?.distanceM?.takeUnless { it.isNaN() }), mac)
    }
}
