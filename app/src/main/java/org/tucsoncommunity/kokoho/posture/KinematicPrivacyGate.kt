package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.location.Location
import org.tucsoncommunity.kokoho.BuildConfig

/**
 * Pedestrian+ kinematic privacy gate (`iss-1795334753017-f45d5c`).
 *
 *  - Starts in [State.STATIONARY] so home/work/parked dwell locations never write unflagged ambient
 *    local history or upload non-TPMS `Tier B` candidate sweeps on release builds.
 *  - Enters [State.MOVING] once the observer displaces `>= 85.0 m` (~1 city block) from the stationary
 *    anchor at `>= 0.95 m/s` (brisk pedestrian walk, bike, or vehicle).
 *  - Cools back to [State.STATIONARY] after remaining inside a `35.0 m` radius continuously for `>= 3 min`.
 *  - Non-own TPMS readings are ALWAYS collected whenever ANY possible ground/vehicle detection is active,
 *    even while [State.STATIONARY].
 *  - Silent hotspot acknowledgement (`collect_investigative_context = true` from `/v1/room/ingest`)
 *    unlocks a silent `Tier B` flush with zero UI indicator.
 */
object KinematicPrivacyGate {

    enum class State {
        STATIONARY,
        MOVING
    }

    const val ENTER_MOVING_DISPLACEMENT_M = 85.0
    const val ENTER_MOVING_MIN_SPEED_MPS = 0.95
    const val COOL_STATIONARY_RADIUS_M = 35.0
    const val COOL_STATIONARY_DURATION_MS = 3 * 60_000L
    const val SILENT_HOTSPOT_HOLD_MS = 90_000L

    private val lock = Any()

    @Volatile
    var state: State = State.STATIONARY
        private set

    @Volatile
    var lastSpeedMps: Double = 0.0
        private set

    private var stationaryAnchorLat: Double? = null
    private var stationaryAnchorLon: Double? = null
    private var dwellAnchorLat: Double? = null
    private var dwellAnchorLon: Double? = null
    private var dwellStartMs: Long = 0L
    private var prevFixLat: Double? = null
    private var prevFixLon: Double? = null
    private var prevFixMs: Long = 0L

    @Volatile
    private var silentHotspotUntilMs: Long = 0L

    fun onLocation(location: Location, nowMs: Long? = null): State {
        val ts = nowMs ?: location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val explicitSpeed = if (location.hasSpeed()) location.speed.toDouble() else null
        return onLocation(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = explicitSpeed,
            timestampMs = ts
        )
    }

    fun onLocation(
        lat: Double,
        lon: Double,
        speedMps: Double?,
        timestampMs: Long
    ): State = synchronized(lock) {
        if (!lat.isFinite() || !lon.isFinite()) return state

        val impliedSpeedMps = run {
            val pLat = prevFixLat
            val pLon = prevFixLon
            val pMs = prevFixMs
            if (pLat != null && pLon != null && timestampMs > pMs) {
                val dtSec = (timestampMs - pMs) / 1000.0
                if (dtSec >= 0.25) {
                    KokohoPostureEngine.haversineMeters(pLat, pLon, lat, lon) / dtSec
                } else 0.0
            } else 0.0
        }
        val effectiveSpeed = when {
            speedMps != null && speedMps.isFinite() && speedMps >= 0.0 -> speedMps
            else -> impliedSpeedMps
        }
        lastSpeedMps = effectiveSpeed
        prevFixLat = lat
        prevFixLon = lon
        prevFixMs = timestampMs

        when (state) {
            State.STATIONARY -> {
                val aLat = stationaryAnchorLat
                val aLon = stationaryAnchorLon
                if (aLat == null || aLon == null) {
                    stationaryAnchorLat = lat
                    stationaryAnchorLon = lon
                } else {
                    val displacedM = KokohoPostureEngine.haversineMeters(aLat, aLon, lat, lon)
                    if (displacedM >= ENTER_MOVING_DISPLACEMENT_M && effectiveSpeed >= ENTER_MOVING_MIN_SPEED_MPS) {
                        state = State.MOVING
                        dwellAnchorLat = lat
                        dwellAnchorLon = lon
                        dwellStartMs = timestampMs
                    }
                }
            }
            State.MOVING -> {
                val dLat = dwellAnchorLat
                val dLon = dwellAnchorLon
                if (dLat == null || dLon == null) {
                    dwellAnchorLat = lat
                    dwellAnchorLon = lon
                    dwellStartMs = timestampMs
                } else {
                    val distFromDwellM = KokohoPostureEngine.haversineMeters(dLat, dLon, lat, lon)
                    if (distFromDwellM > COOL_STATIONARY_RADIUS_M) {
                        dwellAnchorLat = lat
                        dwellAnchorLon = lon
                        dwellStartMs = timestampMs
                    } else if (timestampMs - dwellStartMs >= COOL_STATIONARY_DURATION_MS) {
                        state = State.STATIONARY
                        stationaryAnchorLat = lat
                        stationaryAnchorLon = lon
                        dwellAnchorLat = null
                        dwellAnchorLon = null
                        dwellStartMs = 0L
                    }
                }
            }
        }
        state
    }

    /** Records silent Hub hotspot acknowledgement without altering any UI state or notifications. */
    fun recordSilentHotspotAck(nowMs: Long = System.currentTimeMillis(), holdMs: Long = SILENT_HOTSPOT_HOLD_MS) {
        val until = nowMs + holdMs.coerceAtLeast(1_000L)
        if (until > silentHotspotUntilMs) {
            silentHotspotUntilMs = until
        }
    }

    fun isSilentHotspotActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        silentHotspotUntilMs > 0L && nowMs <= silentHotspotUntilMs

    /**
     * On `!BuildConfig.DEBUG`, unflagged ambient `KnownDeviceEntity` / `DeviceDetailsEntity` rows
     * are suppressed while [State.STATIONARY].
     */
    fun shouldWriteUnflaggedHistory(isDebug: Boolean = BuildConfig.DEBUG): Boolean =
        isDebug || state == State.MOVING

    /**
     * Non-TPMS `Tier B` (wide candidate MAC prefixes and investigative RF sweep peaks) is shared ONLY when:
     *  - `isDebug && isSharingActive` (continuous sharing to `kokoho-next.tucsoncommunity.online`), OR
     *  - `state == MOVING`, OR
     *  - `/v1/room/ingest` returned silent `"collect_investigative_context": true`.
     */
    fun shouldShareNonTpmsTierB(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        isDebug: Boolean = BuildConfig.DEBUG
    ): Boolean {
        if (!KokohoSignatures.isSharingActive(context)) return false
        return isDebug || state == State.MOVING || isSilentHotspotActive(nowMs)
    }

    /**
     * On `BuildConfig.DEBUG`, when `KokohoSignatures.isSharingActive(context)` is enabled, all signals
     * are collected and shared continuously to `kokoho-next.tucsoncommunity.online`.
     */
    fun shouldShareAllSignalsContinuously(
        context: Context,
        isDebug: Boolean = BuildConfig.DEBUG
    ): Boolean = isDebug && KokohoSignatures.isSharingActive(context)

    /**
     * Non-own TPMS readings (`!KokohoSignatures.isOwnVehicleTpms`) are ALWAYS collected whenever ANY
     * possible vehicle/ground detection is active (even while [State.STATIONARY]), as well as when
     * [State.MOVING], during a silent hotspot ack, or on `BuildConfig.DEBUG`.
     */
    fun shouldCollectNonOwnTpms(
        hasActiveGroundOrVehicleDetection: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        isDebug: Boolean = BuildConfig.DEBUG
    ): Boolean = hasActiveGroundOrVehicleDetection || isDebug || state == State.MOVING || isSilentHotspotActive(nowMs)

    fun reset() = synchronized(lock) {
        state = State.STATIONARY
        lastSpeedMps = 0.0
        stationaryAnchorLat = null
        stationaryAnchorLon = null
        dwellAnchorLat = null
        dwellAnchorLon = null
        dwellStartMs = 0L
        prevFixLat = null
        prevFixLon = null
        prevFixMs = 0L
        silentHotspotUntilMs = 0L
    }
}
