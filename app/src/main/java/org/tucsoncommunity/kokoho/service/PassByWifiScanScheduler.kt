package org.tucsoncommunity.kokoho.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Mitigates Android's foreground `WifiManager.startScan()` throttling (4 active scans per 120-second rolling window)
 * during vehicle pass-bys (`iss-1795334755310-d8b2e4`).
 *
 *  - Tracks active `startScan()` invocations in a 120s RAM rolling window (`MAX_SCANS_PER_WINDOW = 4`).
 *  - Reserves [STATIONARY_RESERVE_TOKENS] (`2` tokens) while stationary (`< 4.0 m/s`) so sudden vehicle cues
 *    always have immediate scan quota ready.
 *  - Fires an immediate (`0ms` dispatch, `< 250ms`) Wi-Fi scan burst when observer speed reaches `>= 4.0 m/s`
 *    or when a low-latency BLE / SDR / TPMS cue indicates a passing vehicle, enforcing a `>= 6s` burst cooldown.
 *  - Passively ingests all OS-triggered `SCAN_RESULTS_AVAILABLE_ACTION` broadcasts at zero quota cost.
 */
object PassByWifiScanScheduler {

    const val QUOTA_WINDOW_MS = 120_000L
    const val MAX_SCANS_PER_WINDOW = 4
    const val STATIONARY_RESERVE_TOKENS = 2
    const val PASS_BY_SPEED_MPS = 4.0
    const val CUE_COOLDOWN_MS = 6_000L
    const val MAX_BURST_DISPATCH_LATENCY_MS = 250L

    private val lock = Any()
    private val activeScanTimestamps = ArrayDeque<Long>(MAX_SCANS_PER_WINDOW + 2)
    private val passiveBroadcastCounter = AtomicInteger(0)

    @Volatile
    var lastBurstAtMs: Long = -CUE_COOLDOWN_MS
        private set

    @Volatile
    var lastBurstLatencyMs: Long = 0L
        private set

    @Volatile
    var lastCueSource: String? = null
        private set

    @Volatile
    var lastPassiveBroadcastAtMs: Long = 0L
        private set

    val passiveBroadcastCount: Int
        get() = passiveBroadcastCounter.get()

    private fun pruneLocked(nowMs: Long) {
        val cutoff = nowMs - QUOTA_WINDOW_MS
        while (activeScanTimestamps.isNotEmpty() && activeScanTimestamps.first() <= cutoff) {
            activeScanTimestamps.removeFirst()
        }
    }

    /**
     * Executes a routine background poll only if it will not exhaust reserved pass-by burst tokens.
     */
    fun tryRoutinePoll(
        nowMs: Long,
        currentSpeedMps: Double,
        startScan: () -> Boolean
    ): Boolean {
        synchronized(lock) {
            pruneLocked(nowMs)
            val routineCap = if (currentSpeedMps >= PASS_BY_SPEED_MPS) {
                MAX_SCANS_PER_WINDOW
            } else {
                MAX_SCANS_PER_WINDOW - STATIONARY_RESERVE_TOKENS
            }
            if (activeScanTimestamps.size >= routineCap) {
                return false
            }
        }
        val accepted = startScan()
        if (accepted) {
            synchronized(lock) {
                pruneLocked(nowMs)
                if (activeScanTimestamps.size < MAX_SCANS_PER_WINDOW) {
                    activeScanTimestamps.addLast(nowMs)
                }
            }
        }
        return accepted
    }

    /**
     * Fires an immediate (`< 250ms`) Wi-Fi scan burst when observer kinematics reach `>= 4.0 m/s`.
     */
    fun onSpeedSample(
        speedMps: Double,
        nowMs: Long,
        startScan: () -> Boolean
    ): Boolean {
        if (!speedMps.isFinite() || speedMps < PASS_BY_SPEED_MPS) return false
        return triggerImmediateBurst(cueSource = "KINEMATIC_SPEED", nowMs = nowMs, startScan = startScan)
    }

    /**
     * Fires an immediate (`< 250ms`) Wi-Fi scan burst when a low-latency BLE, SDR, or TPMS cue
     * indicates a nearby/passing vehicle (`>= 6s` cooldown).
     */
    fun onVehicleCue(
        cueSource: String,
        nowMs: Long,
        startScan: () -> Boolean
    ): Boolean = triggerImmediateBurst(cueSource = cueSource, nowMs = nowMs, startScan = startScan)

    private fun triggerImmediateBurst(
        cueSource: String,
        nowMs: Long,
        startScan: () -> Boolean
    ): Boolean {
        val t0 = System.nanoTime()
        synchronized(lock) {
            pruneLocked(nowMs)
            if (nowMs - lastBurstAtMs < CUE_COOLDOWN_MS) return false
            if (activeScanTimestamps.size >= MAX_SCANS_PER_WINDOW) return false
        }
        val accepted = startScan()
        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
        if (accepted) {
            synchronized(lock) {
                pruneLocked(nowMs)
                if (nowMs - lastBurstAtMs < CUE_COOLDOWN_MS || activeScanTimestamps.size >= MAX_SCANS_PER_WINDOW) {
                    return false
                }
                activeScanTimestamps.addLast(nowMs)
                lastBurstAtMs = nowMs
                lastBurstLatencyMs = elapsedMs
                lastCueSource = cueSource
            }
        }
        return accepted
    }

    /**
     * Passively records an OS-delivered `SCAN_RESULTS_AVAILABLE_ACTION` broadcast at zero quota cost.
     */
    fun onPassiveBroadcastIngested(resultCount: Int, nowMs: Long = System.currentTimeMillis()) {
        if (resultCount <= 0) return
        passiveBroadcastCounter.incrementAndGet()
        lastPassiveBroadcastAtMs = nowMs
    }

    fun usedQuotaInWindow(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneLocked(nowMs)
        activeScanTimestamps.size
    }

    fun remainingQuotaInWindow(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneLocked(nowMs)
        (MAX_SCANS_PER_WINDOW - activeScanTimestamps.size).coerceAtLeast(0)
    }

    fun reset() = synchronized(lock) {
        activeScanTimestamps.clear()
        passiveBroadcastCounter.set(0)
        lastBurstAtMs = -CUE_COOLDOWN_MS
        lastBurstLatencyMs = 0L
        lastCueSource = null
        lastPassiveBroadcastAtMs = 0L
    }
}
