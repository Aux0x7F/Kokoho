package org.tucsoncommunity.kokoho

import androidx.core.view.children
import androidx.core.view.isVisible
import android.app.Application
import android.util.Log
import org.tucsoncommunity.kokoho.car.CarState
import org.tucsoncommunity.kokoho.data.WhitelistCache
import org.tucsoncommunity.kokoho.detect.VendorDb
import org.tucsoncommunity.kokoho.oui.OuiWatchlist
import org.tucsoncommunity.kokoho.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KokohoApp : Application() {
    companion object {
        /** Number of this app's activities currently started (visible). */
        @Volatile var visibleActivities = 0
            private set
        val inForeground get() = visibleActivities > 0
    }

    /** Lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Settings > General > Screen rotation, while the app is open. The status bar and Android's own
     * navigation bar or gestures turn with the display and work as usual. Phones like the Pixel never
     * auto-rotate to upside down, but honour an app that asks for it: "full user" follows auto-rotate in
     * all four directions (and stays put when rotation is locked); reverse portrait is always upside down.
     */
    fun applyOrientation(a: android.app.Activity) {
        val wanted = when (org.tucsoncommunity.kokoho.util.Prefs.rotation(a)) {
            org.tucsoncommunity.kokoho.util.Prefs.Rotation.UPSIDE_DOWN -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            org.tucsoncommunity.kokoho.util.Prefs.Rotation.ALL_WAYS -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            org.tucsoncommunity.kokoho.util.Prefs.Rotation.NORMAL -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (a.requestedOrientation != wanted) a.requestedOrientation = wanted
    }

    /** Windows with the bars hidden by the setting. Only these get them back, so the map's own full screen is left alone. */
    private val fullscreenWindows = java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.app.Activity, Boolean>())

    /** Settings > General > Fullscreen: no status or navigation bar; a swipe from the edge shows them for a moment. */
    fun applyFullscreen(a: android.app.Activity) {
        val bars = androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
        val types = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (org.tucsoncommunity.kokoho.util.Prefs.fullscreen(a)) {
            bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreenWindows.add(a)) {
                // Older Android versions bring the bars back after a dialog or the notification shade.
                a.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { if (it) applyFullscreen(a) }
                useCameraStrip(a)
            }
            bars.hide(types)
        } else if (fullscreenWindows.remove(a)) bars.show(types)
    }

    /**
     * Without this the status bar's strip stays empty: the app keeps clear of the camera. When nothing in the action bar
     * touches the hole (a short title, the buttons further right) the bar goes up around it, so this is decided again
     * whenever the bar is laid out. Screens without an action bar keep the strip.
     */
    private fun useCameraStrip(a: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val frame = a.findViewById<android.view.View>(androidx.appcompat.R.id.decor_content_parent)?.parent as? android.view.View ?: return
        val bar = a.findViewById<android.view.ViewGroup>(androidx.appcompat.R.id.action_bar) ?: return
        val iconInset = (12 * a.resources.displayMetrics.density).toInt() // only a button's icon has to stay out of the hole
        var clear = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            if (clear && org.tucsoncommunity.kokoho.util.Prefs.fullscreen(a)) {
                val cutout = androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                val now = insets.getInsets(cutout)
                androidx.core.view.WindowInsetsCompat.Builder(insets)
                    .setInsets(cutout, androidx.core.graphics.Insets.of(now.left, 0, now.right, now.bottom)).build()
            } else insets
        }
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val holes = frame.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().filter { it.top == 0 }
            val free = holes.isNotEmpty() && holes.none { h ->
                bar.children.any {
                    val inset = if (it is androidx.appcompat.widget.ActionMenuView) iconInset else 0
                    it.isVisible && it.left + inset < h.right && it.right - inset > h.left
                }
            }
            if (free != clear) {
                clear = free
                bar.post { frame.requestApplyInsets() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        org.tucsoncommunity.kokoho.util.HwLog.init(this)
        org.tucsoncommunity.kokoho.util.CrashLog.install(this)
        appScope.launch(Dispatchers.IO) { org.tucsoncommunity.kokoho.util.CrashLog.recordExits(this@KokohoApp) }
        org.tucsoncommunity.kokoho.ui.DeviceFilter.excludeAirTags = org.tucsoncommunity.kokoho.util.Prefs.excludeAirTags(this)
        org.tucsoncommunity.kokoho.ui.ThemeManager.install(this)
        org.tucsoncommunity.kokoho.ui.ScreenAwake.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visibleActivities++ }
            override fun onActivityStopped(a: android.app.Activity) { visibleActivities = (visibleActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = applyOrientation(a)
            override fun onActivityResumed(a: android.app.Activity) { applyOrientation(a); applyFullscreen(a) }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        NotificationHelper.createChannels(this)
        org.tucsoncommunity.kokoho.detect.DeviceIntel.applyUnits(this)
        // A downloaded update is done with once the app starts again (installed, or given up on).
        appScope.launch { org.tucsoncommunity.kokoho.util.UpdateInstaller.cleanUp(this@KokohoApp) }
        // A fresh process means no camera download is running: drop any leftover progress.
        NotificationHelper.clearStaleCameraDownload(this)
        // Off the main thread: the scanner loads it itself before it starts, so nothing waits on this one.
        appScope.launch(Dispatchers.IO) { OuiWatchlist.load(this@KokohoApp) }
        WhitelistCache.start(this, appScope)
        appScope.launch(Dispatchers.IO) { org.tucsoncommunity.kokoho.data.TrackerMutes.load(this@KokohoApp) }
        appScope.launch(Dispatchers.IO) { org.tucsoncommunity.kokoho.data.TrustedNetworks.load(this@KokohoApp) }
        appScope.launch(Dispatchers.IO) { org.tucsoncommunity.kokoho.data.TrustRules.load(this@KokohoApp) }
        appScope.launch(Dispatchers.IO) { org.tucsoncommunity.kokoho.alpr.IgnoredCameras.load(this@KokohoApp) }
        CarState.start(this)
        // ~60k vendor rows: load off the main thread; lookups return null until ready.
        // A trace left "recording" by a crash / force-stop gets closed.
        appScope.launch { runCatching { org.tucsoncommunity.kokoho.service.TripRecorder.closeStale(this@KokohoApp) } }
        // The CCTV cache too, at the same time, so the map has both ready when it opens.
        if (org.tucsoncommunity.kokoho.util.Prefs.showCctv(this)) appScope.launch(Dispatchers.IO) {
            runCatching { org.tucsoncommunity.kokoho.alpr.CctvStore.load(this@KokohoApp) }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { org.tucsoncommunity.kokoho.alpr.AlprStore.load(this@KokohoApp) }
            runCatching { org.tucsoncommunity.kokoho.alpr.DeflockBulk.refreshIfDue(this@KokohoApp) }
            // A scanner started before the cache loaded (e.g. at boot) must now watch for cameras.
            if (org.tucsoncommunity.kokoho.service.ScanForegroundService.isRunning && org.tucsoncommunity.kokoho.alpr.AlprStore.cameras.isNotEmpty()) {
                runCatching {
                    startService(android.content.Intent(this@KokohoApp, org.tucsoncommunity.kokoho.service.ScanForegroundService::class.java)
                        .setAction(org.tucsoncommunity.kokoho.service.ScanForegroundService.ACTION_REFRESH_LOCATION))
                }
            }
        }
        // Open the map's tile database off the main thread now, so the first map of the session does not wait for it.
        appScope.launch(Dispatchers.IO) {
            runCatching {
                org.tucsoncommunity.kokoho.ui.MapIcons.configureOsm(this@KokohoApp)
                org.osmdroid.tileprovider.modules.SqlTileWriter().onDetach()
            }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { VendorDb.load { assets.open(it) } }
                .onFailure { Log.e("KokohoApp", "Vendor database failed to load", it) }
        }
    }
}
