package org.tucsoncommunity.kokoho.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import org.tucsoncommunity.kokoho.detect.Advert

/**
 * Drone Remote ID over Wi-Fi Aware (NAN), the WiFi transport many EU drones and newer DJI models
 * use, which a phone can't hear by ordinary WiFi scanning (after OpenDroneID's receiver-android,
 * Apache-2.0: written anew here). A passive subscription to the "org.opendroneid.remoteid"
 * service: listen only, nothing is published or sent to the drone.
 *
 * Hardened against Android 14-17 `HalDevMgr` concurrency rejections (`ifaceType=4` when STA +
 * AP_BRIDGE hotspot are active concurrently):
 *  - Runs all `WifiAwareManager` Binder IPC on a dedicated background [HandlerThread] (never on the UI thread).
 *  - Enforces single-flight [attaching] state + minimum attach cooldown ([MIN_ATTACH_GAP_MS]) +
 *    circuit-breaker backoff ([CONCURRENCY_BLOCKED_RETRY_MS]) so state broadcast oscillations
 *    (`ACTION_WIFI_AWARE_STATE_CHANGED`) can never leak Binder stubs or stall Map rendering.
 */
class NanRemoteId(private val context: Context, private val onAdvert: (Advert) -> Unit) {

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var session: WifiAwareSession? = null
    private var subscription: SubscribeDiscoverySession? = null
    @Volatile private var running = false
    private var receiverOn = false
    private var attaching = false
    private var consecutiveFailures = 0
    private var retryMs = INITIAL_RETRY_MS
    private var nextAllowedAttachElapsedMs = 0L

    /** Made-up addresses per peer (peer handles only mean something within a subscription). */
    private val peers = HashMap<Int, String>()

    private val reattach = Runnable {
        if (running && session == null && !attaching) {
            attach()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!running) return
            workerHandler?.post { onAwareStateChanged() }
        }
    }

    @Synchronized
    fun start() {
        if (running || !supported(context)) return
        running = true
        consecutiveFailures = 0
        retryMs = INITIAL_RETRY_MS
        nextAllowedAttachElapsedMs = 0L
        val ht = HandlerThread("kokoho-nan-rid").apply { start() }
        val h = Handler(ht.looper)
        workerThread = ht
        workerHandler = h
        runCatching {
            ContextCompat.registerReceiver(
                context,
                stateReceiver,
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverOn = true
        }
        h.post { attach() }
    }

    @Synchronized
    fun stop() {
        running = false
        if (receiverOn) {
            runCatching { context.unregisterReceiver(stateReceiver) }
            receiverOn = false
        }
        val h = workerHandler
        val ht = workerThread
        workerHandler = null
        workerThread = null
        if (h != null && ht != null) {
            h.removeCallbacksAndMessages(null)
            h.post {
                attaching = false
                close()
                runCatching { ht.quitSafely() }
            }
        } else {
            attaching = false
            close()
        }
    }

    private fun onAwareStateChanged() {
        if (!running) return
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return
        if (!mgr.isAvailable) {
            attaching = false
            close()
            return
        }
        // Only attempt attach if we do not already have an active or in-flight session;
        // attach() strictly enforces nextAllowedAttachElapsedMs so state broadcast storms never spin.
        if (session == null && !attaching) {
            attach()
        }
    }

    private fun close() {
        runCatching { subscription?.close() }
        subscription = null
        runCatching { session?.close() }
        session = null
        peers.clear()
    }

    @SuppressLint("MissingPermission")
    private fun attach() {
        val h = workerHandler ?: return
        if (!running || session != null || attaching) return
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return
        if (!mgr.isAvailable) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val waitMs = nextAllowedAttachElapsedMs - nowElapsed
        if (waitMs > 0L) {
            h.removeCallbacks(reattach)
            h.postDelayed(reattach, waitMs)
            return
        }

        attaching = true
        nextAllowedAttachElapsedMs = nowElapsed + MIN_ATTACH_GAP_MS
        try {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    attaching = false
                    if (!running) {
                        runCatching { s.close() }
                        return
                    }
                    close()
                    session = s
                    subscribe(s)
                }

                override fun onAttachFailed() {
                    attaching = false
                    close()
                    retry("attach failed")
                }

                override fun onAwareSessionTerminated() {
                    attaching = false
                    close()
                    retry("aware session ended")
                }
            }, h)
        } catch (e: SecurityException) {
            attaching = false
            Log.w(TAG, "Wi-Fi Aware not allowed: ${e.message}")
        } catch (t: Throwable) {
            attaching = false
            retry("attach error: ${t.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(s: WifiAwareSession) {
        val h = workerHandler ?: return
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        try {
            s.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                    subscription = sub
                    consecutiveFailures = 0
                    retryMs = INITIAL_RETRY_MS
                    Log.i(TAG, "Listening for drone Remote ID over Wi-Fi Aware")
                    org.tucsoncommunity.kokoho.util.HwLogs.phone.logStatus(
                        "Wi-Fi Aware",
                        "Wi-Fi Aware: listening for drone Remote ID"
                    )
                }

                override fun onSessionConfigFailed() {
                    close()
                    retry("subscription refused")
                }

                override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, filter: List<ByteArray>?) {
                    info?.let { onInfo(peer, it) }
                }

                override fun onSessionTerminated() {
                    subscription = null
                    close()
                    retry("session ended")
                }
            }, h)
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi Aware subscribe not allowed: ${e.message}")
        } catch (t: Throwable) {
            close()
            retry("subscribe error: ${t.javaClass.simpleName}")
        }
    }

    private fun retry(why: String) {
        if (!running) return
        val h = workerHandler ?: return
        consecutiveFailures++
        val delayMs = computeBackoffMs(consecutiveFailures, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        nextAllowedAttachElapsedMs = SystemClock.elapsedRealtime() + delayMs

        if (consecutiveFailures == 1) {
            Log.w(TAG, "Wi-Fi Aware: $why (retrying in ${delayMs / 1000}s)")
            org.tucsoncommunity.kokoho.util.HwLogs.phone.logStatus(
                "Wi-Fi Aware",
                "Wi-Fi Aware: $why (retrying in ${delayMs / 1000}s)"
            )
        } else if (consecutiveFailures == CIRCUIT_BREAKER_FAILURES) {
            Log.i(TAG, "Wi-Fi Aware interface busy ($why x$consecutiveFailures, e.g. Wi-Fi hotspot active); backing off ${delayMs / 60_000}m")
            org.tucsoncommunity.kokoho.util.HwLogs.phone.logStatus(
                "Wi-Fi Aware",
                "Wi-Fi Aware paused (Wi-Fi radio busy with hotspot/concurrency; re-checking every ${delayMs / 60_000}m)"
            )
        }
        h.removeCallbacks(reattach)
        h.postDelayed(reattach, delayMs)
    }

    private fun onInfo(peer: PeerHandle, info: ByteArray) {
        if (info.size < 1 + org.tucsoncommunity.kokoho.detect.RemoteId.MESSAGE_SIZE) return
        val mac = peers.getOrPut(peer.hashCode()) { madeUpAddress(peer.hashCode()) }
        onAdvert(advert(mac, info, System.currentTimeMillis()))
    }

    companion object {
        private const val TAG = "NanRemoteId"
        /** The ASTM F3411 Wi-Fi NAN service name. */
        const val SERVICE = "org.opendroneid.remoteid"
        /** Wi-Fi Aware discovery is on channel 6. */
        private const val NAN_MHZ = 2437
        /** No signal strength comes with a discovery. */
        private const val NOMINAL_RSSI = -75
        internal const val MIN_ATTACH_GAP_MS = 30_000L
        internal const val INITIAL_RETRY_MS = 30_000L
        internal const val MAX_RETRY_MS = 120_000L
        internal const val CIRCUIT_BREAKER_FAILURES = 3
        internal const val CONCURRENCY_BLOCKED_RETRY_MS = 10 * 60_000L

        @VisibleForTesting
        internal fun computeBackoffMs(consecutiveFailures: Int, currentRetryMs: Long): Long {
            return if (consecutiveFailures >= CIRCUIT_BREAKER_FAILURES) {
                CONCURRENCY_BLOCKED_RETRY_MS
            } else {
                currentRetryMs.coerceIn(MIN_ATTACH_GAP_MS, MAX_RETRY_MS)
            }
        }

        fun supported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

        /** A WiFi advert carrying [info] as a Remote ID vendor element (OUI FA:0B:BC, type 0x0D). */
        internal fun advert(mac: String, info: ByteArray, now: Long): Advert {
            val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D) + info
            return Advert(
                mac = mac,
                source = Advert.Source.WIFI,
                rssi = NOMINAL_RSSI,
                name = null,
                wifi = Advert.WifiInfo(
                    NAN_MHZ,
                    "",
                    org.tucsoncommunity.kokoho.detect.DeviceIntel.WIFI_AWARE,
                    listOf(221 to ie)
                ),
                timestamp = now
            )
        }

        /** A locally administered address for a peer, stable while its handle is. */
        internal fun madeUpAddress(id: Int): String =
            "0E:4E:" + (0 until 4).joinToString(":") { "%02X".format((id ushr (24 - 8 * it)) and 0xFF) }
    }
}
