package org.tucsoncommunity.kokoho.posture

import android.content.Context
import org.tucsoncommunity.kokoho.online.PoliceAircraft
import org.tucsoncommunity.kokoho.sdr.RadioLog
import org.tucsoncommunity.kokoho.sdr.SdrRadio
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class PostureColor(val colorArgb: Int, val badgeLabel: String) {
    GREEN(0xFF1B5E20.toInt(), "CLEAR"),
    YELLOW(0xFFB26A00.toInt(), "PERIPHERY PRESENCE"),
    ORANGE(0xFFD84315.toInt(), "HEADING THIS WAY"),
    RED(0xFFB71C1C.toInt(), "IMMINENT PROXIMITY")
}

data class EvidenceBadge(
    val kind: String,
    val facetKey: String,
    val detail: String,
    val weight: Double,
    val lastSeenMs: Long
)

data class EvaluatedThreat(
    val uuid: String,
    val trackUuid: String,
    val agency: KokohoAgency,
    val label: String,
    val posture: PostureColor,
    val overallConfidence: Int,
    val classificationConfidence: Int,
    val spatialConfidence: Int,
    val estimatedDistanceM: Int,
    val uncertaintyRadiusM: Int,
    val bearingDeg: Int?,
    val closingVelocityMps: Double?,
    val recurrenceSummary: String,
    val evidenceSources: List<EvidenceBadge>,
    val updatedAtMs: Long,
    val lat: Double?,
    val lon: Double?,
    /** True once multi-GPS 2D client triangulation or Auxnet room correlation approximates the source position. */
    val isApproximatedPosition: Boolean = false,
    /** True only when the approximated position uncertainty is narrow enough to render as a point (<= 25m with 2D spread) or exact ADS-B/RemoteID GPS. */
    val isPinpointed: Boolean = false,
    /** True if this threat is an airborne ADS-B aircraft. */
    val isAircraft: Boolean = false,
    val isHelicopter: Boolean = false,
    val aircraftTrackDeg: Double? = null,
    val aircraftHex: String? = null,
    val isCircling: Boolean = false,
    /** True if this threat is a Pro / Enterprise / Military / Remote ID drone. */
    val isDrone: Boolean = false,
    val altitudeFtAgl: Int? = null,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val hardwareIdentifier: String? = null,
    val sightingCount: Int = 1,
    val capabilities: List<String> = emptyList()
) {
    val friendlyLabel: String get() = label.ifBlank { agency.friendlyLabel }
    val lastSeenMs: Long get() = updatedAtMs
    val title: String get() = recurrenceSummary.ifBlank { friendlyLabel }
    val evidenceSummary: String get() = evidenceSources.firstOrNull()?.detail ?: recurrenceSummary
    val combinedConfidence: Int
        get() = ((classificationConfidence + spatialConfidence) / 2.0).roundToInt().coerceIn(1, 99)
    val evidenceCount: Int
        get() = evidenceSources.size.coerceAtLeast(1)

    /**
     * Distance (in meters) from the user to the NEAREST edge of the confidence circle.
     *  - When the user is inside the confidence circle (`!isApproximatedPosition` or `estimatedDistanceM <= uncertaintyRadiusM`), returns 0.
     *  - When the circle is displaced from the user (`estimatedDistanceM > uncertaintyRadiusM`), returns `estimatedDistanceM - uncertaintyRadiusM`.
     */
    val nearestEdgeDistanceM: Int
        get() = if (!isApproximatedPosition) {
            0
        } else {
            max(0, estimatedDistanceM - uncertaintyRadiusM)
        }

    /**
     * Effective proximity metric (meters) used for Monitor sorting and headline distance:
     *  - Known centroid (`lat != null && lon != null`): true distance to center centroid `estimatedDistanceM`
     *  - Unlocated signal (`lat == null || lon == null`): `uncertaintyRadiusM`
     */
    val effectiveProximityM: Int
        get() = if (lat != null && lon != null) {
            estimatedDistanceM.coerceAtLeast(0)
        } else {
            uncertaintyRadiusM.coerceAtLeast(20)
        }

    /**
     * Radius (in meters) to draw on the Map:
     *  - Whenever an observation has a recorded coordinate (`lat != null && lon != null`), always draw its
     *    intrinsic `uncertaintyRadiusM` so driving away NEVER balloons a stationary circle to `estimatedDistanceM`.
     *  - Only fall back to `estimatedDistanceM` when no coordinate was recorded (`lat == null || lon == null`).
     */
    val mapCircleRadiusM: Int
        get() = if (lat != null && lon != null) {
            uncertaintyRadiusM.coerceAtLeast(20)
        } else {
            estimatedDistanceM.coerceAtLeast(35)
        }
}

/**
 * Grouped active detections for a single [KokohoAgency] on the Monitor tab,
 * ordered strictly by [KokohoAgency.priorityRank] regardless of detection time or distance.
 */
data class AgencyMonitorGroup(
    val agency: KokohoAgency,
    val posture: PostureColor,
    val distinctUnits: List<EvaluatedThreat>,
    val primaryCount: Int,
    val primaryRadiusM: Int,
    val primaryEdgeDistanceM: Int,
    val widerCount: Int,
    val widerRadiusM: Int?,
    val lastSeenMs: Long
) {
    val totalDistinctCount: Int get() = distinctUnits.size.coerceAtLeast(1)
    val leadThreat: EvaluatedThreat get() = distinctUnits.first()

    /**
     * Formats the inline subtext shown directly beneath the agency headline on the Monitor page:
     * e.g. `x2 · ~400 ft (±0.3 mi) · +1 (~1.1 mi) · 1m ago`
     */
    fun formatMonitorSubtext(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        val countPrefix = "x$primaryCount"
        val leadUnit = distinctUnits.firstOrNull()
        val accuracyPart = when {
            leadUnit?.isDrone == true && leadUnit.altitudeFtAgl != null -> {
                val distStr = KokohoPostureEngine.formatAccuracyOrEdge(context, leadUnit)
                "$distStr · ${leadUnit.altitudeFtAgl} ft AGL"
            }
            leadUnit?.isAircraft == true -> {
                val distStr = KokohoPostureEngine.formatAccuracyOrEdge(context, leadUnit)
                if (leadUnit.isCircling) "$distStr · Circling" else distStr
            }
            leadUnit != null -> {
                KokohoPostureEngine.formatAccuracyOrEdge(context, leadUnit)
            }
            else -> KokohoPostureEngine.formatAccuracyRadius(context, primaryRadiusM)
        }
        val widerPart = if (widerCount > 0 && widerRadiusM != null) {
            " · +$widerCount (${KokohoPostureEngine.formatShortDistance(context, widerRadiusM)})"
        } else {
            ""
        }
        val agePart = KokohoPostureEngine.formatStalenessAge(nowMs - lastSeenMs)
        return "$countPrefix · $accuracyPart$widerPart · $agePart"
    }
}

data class PostureSnapshot(
    val color: PostureColor,
    val headline: String,
    val subheadline: String,
    val distanceAndBearingText: String,
    val bottomConfidenceText: String,
    val primaryThreat: EvaluatedThreat?,
    /** Active proximate threats within the 3-hour Monitor window, sorted by priorityRank. */
    val activeThreats: List<EvaluatedThreat>,
    /** Grouped proximate agencies within the 3-hour Monitor window, sorted strictly by priorityRank. */
    val monitorGroups: List<AgencyMonitorGroup>,
    /** Retained threats within the Map visibility window (30m solid + 2h fade = 2.5h). */
    val mapThreats: List<EvaluatedThreat>,
    val enabledScopeText: String,
    val sectorTempoText: String? = null,
    val evaluatedAtMs: Long = System.currentTimeMillis()
)

object KokohoPostureEngine {

    private const val COLOCATION_WINDOW_MS = 15_000L
    private const val COLOCATION_MAX_DIST_M = 100.0
    private const val KIT_FUSION_MAX_DIST_M = 80.0
    /** Minimum GPS spread (meters) across client observations before transitioning from a user-centered distance circle to an approximated source circle. */
    const val MIN_TRIANGULATION_BASELINE_M = 60.0
    /** Minimum 2D cross-track spread (meters) perpendicular to the travel line before collapsing a 1D road drive into a 2D approximated position. */
    private const val MIN_2D_CROSS_TRACK_M = 25.0
    /** Maximum uncertainty radius (meters) at which an approximated circle is considered narrow enough to show a point dot. */
    const val PINPOINT_POINT_RADIUS_M = 25

    /** Strips legacy `"+ Operator"` / `"[Drone Operator]"` suffixes from drone display titles and summaries. */
    fun sanitizeDroneOperatorSuffix(raw: String): String =
        raw.replace(Regex("""\s*\[Drone Operator\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\+\s*Operators?\b""", RegexOption.IGNORE_CASE), "")
            .trim()

    data class ExtractedDroneTelemetry(
        val operatorLat: Double?,
        val operatorLon: Double?,
        val altFtAgl: Int?,
        val headingDeg: Double?,
        val isDroneFacet: Boolean
    )

    fun extractDroneTelemetryFromFacets(facets: Iterable<String>): ExtractedDroneTelemetry {
        var opLat: Double? = null
        var opLon: Double? = null
        var altFt: Int? = null
        var hdgDeg: Double? = null
        var isDrn = false
        for (raw in facets) {
            val fk = raw.trim()
            if (fk.startsWith("uas:", ignoreCase = true) || fk.startsWith("drone:", ignoreCase = true)) {
                isDrn = true
            }
            if (fk.startsWith("uas:op:", ignoreCase = true)) {
                val parts = fk.substringAfter("uas:op:", fk.substringAfter("UAS:OP:")).split(',')
                if (parts.size == 2) {
                    val olat = parts[0].trim().toDoubleOrNull()
                    val olon = parts[1].trim().toDoubleOrNull()
                    if (olat != null && olon != null && olat in -90.0..90.0 && olon in -180.0..180.0 &&
                        (abs(olat) > 0.01 || abs(olon) > 0.01)
                    ) {
                        opLat = olat
                        opLon = olon
                    }
                }
            } else if (altFt == null && fk.startsWith("uas:alt_ft_agl:", ignoreCase = true)) {
                altFt = fk.substringAfterLast(':').trim().toIntOrNull()
            } else if (hdgDeg == null && fk.startsWith("uas:hdg:", ignoreCase = true)) {
                hdgDeg = fk.substringAfterLast(':').trim().toDoubleOrNull()
            }
        }
        return ExtractedDroneTelemetry(opLat, opLon, altFt, hdgDeg, isDrn)
    }

    /**
     * True when an entity represents a standalone `uas:op:*` pilot/operator coordinate rather than
     * a flying `uas:mac:*` / `DRONE:*` airframe. Operators never receive an uncertainty bubble.
     */
    fun isStandaloneDroneOperator(
        badges: List<EvidenceBadge>,
        hardwareIdentifier: String? = null
    ): Boolean {
        if (hardwareIdentifier?.trim()?.startsWith("uas:op:", ignoreCase = true) == true) return true
        val primary = badges.firstOrNull()?.facetKey?.trim()
        if (primary?.startsWith("uas:op:", ignoreCase = true) == true) return true
        return badges.isNotEmpty() && badges.all { it.facetKey.trim().startsWith("uas:op:", ignoreCase = true) }
    }

    data class SpatialSample(
        var lat: Double,
        var lon: Double,
        var signalDb: Int,
        var estDistanceM: Int,
        var timestampMs: Long
    )

    data class TriangulationResult(
        val centerLat: Double?,
        val centerLon: Double?,
        val estimatedDistanceM: Int,
        val uncertaintyRadiusM: Int,
        val spatialConfidence: Int,
        val isApproximatedPosition: Boolean,
        val isPinpointed: Boolean,
        val isClosing: Boolean,
        val sampleCount: Int = 1
    )

    private data class CachedTriangulation(
        val sampleCount: Int,
        val lastLat: Double,
        val lastLon: Double,
        val lastSignalDb: Int,
        val result: TriangulationResult,
        val peakSignalDb: Int = lastSignalDb
    )

    data class RawSensorReportCandidate(
        val facetKey: String,
        val rawLat: Double,
        val rawLon: Double,
        val signalDb: Double,
        val modality: String,
        val additionalFacets: List<String>,
        val agency: KokohoAgency,
        val label: String,
        val propClass: String,
        val uncertaintyRadiusM: Double?,
        val isDerived: Boolean,
        val localSightings: Int,
        val ssidHint: String?,
        val isFlagged: Boolean = true,
        val obsTimestampMs: Long = 0L,
        val tier: KokohoSignatures.WatchlistTier = KokohoSignatures.WatchlistTier.TIER_A,
        val rssiCorr: Double? = null,
        val tpmsDisambig: String? = null
    )

    private data class LastRawReportState(
        val atMs: Long,
        val lat: Double,
        val lon: Double,
        val signalDb: Double,
        val lastObsTsMs: Long = 0L
    )

    private data class CoMovingTrackState(
        val lastLat: Double,
        val lastLon: Double,
        val lastHeadingDeg: Double,
        val lastTrailDistM: Double,
        val updatedAtMs: Long
    )

    private data class LrrpRadioFix(
        val pos: org.tucsoncommunity.kokoho.sdr.P25Tsbk.Position,
        val hz: Long?,
        val nac: Int?,
        val atMs: Long
    )

    private val spatialHistory = ConcurrentHashMap<String, MutableList<SpatialSample>>()
    private val triangulationCache = ConcurrentHashMap<String, CachedTriangulation>()
    private val coMovingState = ConcurrentHashMap<String, CoMovingTrackState>()
    private val localThreatCache = ConcurrentHashMap<String, EvaluatedThreat>()
    private val lastHeardByLocalHardwareAtMs = ConcurrentHashMap<String, Long>()
    private val lrrpRadioFixes = ConcurrentHashMap<Int, LrrpRadioFix>()
    private val lastSharedAtByUuid = ConcurrentHashMap<String, Long>()
    private val lastRawReportByFacet = ConcurrentHashMap<String, LastRawReportState>()
    @Volatile
    private var lastPostureColor: PostureColor = PostureColor.GREEN
    @Volatile
    private var lastEvaluateNowMs: Long = 0L
    @Volatile
    private var latestSnapshot: PostureSnapshot? = null
    @Volatile
    private var latestRawObservationsSnapshot: List<RawSensorReportCandidate> = emptyList()
    @Volatile
    private var cachedFallbackLocation: android.location.Location? = null
    @Volatile
    private var cachedFallbackLocationAtMs: Long = 0L

    private const val LOCAL_RF_ONLY_CACHE_TTL_MS = 24 * 3_600_000L
    private const val LOCAL_SENSOR_CACHE_TTL_MS = 24 * 3_600_000L
    private const val LOCAL_AIRCRAFT_CACHE_TTL_MS = 5 * 60_000L
    const val LIVE_AIRCRAFT_MAX_AGE_MS = 5 * 60_000L
    private const val LOCAL_TRIANGULATION_DECAY_MS = 45 * 60_000L
    private const val KIT_FUSION_WINDOW_MS = 15_000L

    /** Minimum uncertainty radius (meters): 200 ft ≈ 61 m: before a wide uncorroborated circle is jittered. */
    const val WIDE_CIRCLE_JITTER_MIN_M = 61

    /**
     * Spatial jitter has been deprecated in favor of exact accuracy; privacy is governed explicitly by
     * toggling Sharing ON / paused in the top header. Always returns exact `(lat, lon)`.
     */
    @Suppress("UNUSED_PARAMETER")
    fun deriveJitteredCenter(uuid: String, lat: Double, lon: Double, radiusM: Int): Pair<Double, Double> =
        lat to lon

    /** Deterministic UUIDv5 matching kokoho-hub's namespace. */
    private val KOKOHO_NS = byteArrayOf(
        0x6b, 0x6f, 0x6b, 0x6f, 0x68, 0x6f, 0x2d, 0x74,
        0x63, 0x6f, 0x2d, 0x61, 0x75, 0x78, 0x6e, 0x74
    )

    fun deterministicUuidV5(canonicalFacetKey: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(KOKOHO_NS)
        md.update(canonicalFacetKey.toByteArray(StandardCharsets.UTF_8))
        val hash = md.digest()
        val b = hash.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80.toInt()).toByte()
        return "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x".format(
            b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
            b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]
        )
    }

    fun clearLocalState() {
        spatialHistory.clear()
        triangulationCache.clear()
        coMovingState.clear()
        localThreatCache.clear()
        lastHeardByLocalHardwareAtMs.clear()
        lrrpRadioFixes.clear()
        org.tucsoncommunity.kokoho.sdr.TpmsLog.clear()
        CoMovingTpmsTracker.clear()
        RecentSignalRingBuffer.clear()
        KinematicPrivacyGate.reset()
        KokohoEntityLedger.clearAll()
        KokohoSignatures.clearWitnessAndConfirmedCacheInMemory()
        lastSharedAtByUuid.clear()
        lastRawReportByFacet.clear()
        lastPostureColor = PostureColor.GREEN
        lastEvaluateNowMs = 0L
        latestSnapshot = null
        latestRawObservationsSnapshot = emptyList()
        PoliceAircraft.clearAirborneTrailsForTest()
        cachedFallbackLocation = null
        cachedFallbackLocationAtMs = 0L
    }

    fun recentPinpointFlightTrack4D(
        keyOrUuid: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 90 * 60_000L
    ): List<PoliceAircraft.FlightFix> = PoliceAircraft.recentFlightFixes4D(keyOrUuid, nowMs, maxAgeMs)

    internal fun latestRawObservationsForTest(): List<RawSensorReportCandidate> = latestRawObservationsSnapshot

    /** Non-recursive lookup of an entity's evidence facets and hardwareIdentifier when a report is saved on it. */
    fun peekThreatFacets(context: Context?, keyOrUuid: String): Pair<List<String>, String?> {
        val norm = KokohoSignatures.normalizeEntityId(keyOrUuid)
        val local = localThreatCache[norm] ?: (if (context != null) KokohoEntityLedger.findByUuid(context, norm) else null)
        if (local != null) {
            return local.evidenceSources.map { it.facetKey } to local.hardwareIdentifier
        }
        val remote = KokohoHubClient.activeObjects(System.currentTimeMillis(), KokohoSignatures.MapTimeWindow.HEAT.windowMs)
            .firstOrNull { it.uuid.equals(norm, ignoreCase = true) }
        if (remote != null) {
            val facets = remote.classification.evidenceSources.map { it.facetKey }
            return facets to facets.firstOrNull()
        }
        return emptyList<String>() to null
    }

    fun clearRamOnlyForTest() {
        spatialHistory.clear()
        triangulationCache.clear()
        coMovingState.clear()
        localThreatCache.clear()
        lastHeardByLocalHardwareAtMs.clear()
        lrrpRadioFixes.clear()
        org.tucsoncommunity.kokoho.sdr.TpmsLog.clear()
        CoMovingTpmsTracker.clear()
        RecentSignalRingBuffer.clear()
        KinematicPrivacyGate.reset()
        lastEvaluateNowMs = 0L
        latestSnapshot = null
        latestRawObservationsSnapshot = emptyList()
        PoliceAircraft.clearAirborneTrailsForTest()
        cachedFallbackLocation = null
        cachedFallbackLocationAtMs = 0L
    }

    /**
     * Ingests an actual hardware GPS coordinate transmitted by a P25 radio over Motorola LRRP (`P25Pdu`).
     * Validated by CRC-9, OP25 packet CRC-32, and IPv4 header checksum before reaching here.
     */
    fun ingestP25RadioGps(
        context: Context?,
        pos: org.tucsoncommunity.kokoho.sdr.P25Tsbk.Position,
        hz: Long? = null,
        nac: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (pos.radio <= 0) return
        if (pos.lat !in -90.0..90.0 || pos.lon !in -180.0..180.0) return
        if (abs(pos.lat) < 0.001 && abs(pos.lon) < 0.001) return
        lrrpRadioFixes[pos.radio] = LrrpRadioFix(pos, hz, nac, nowMs)
        lastEvaluateNowMs = 0L
        if (context != null) {
            runCatching { evaluate(context, nowMs) }
        }
    }

    /** Returns the cached snapshot if evaluated within [maxAgeMs], otherwise evaluates fresh. */
    fun latestOrEvaluate(context: Context, maxAgeMs: Long = 3_000L): PostureSnapshot {
        val now = System.currentTimeMillis()
        val snap = latestSnapshot
        if (snap != null && abs(now - lastEvaluateNowMs) <= maxAgeMs) {
            return snap
        }
        return evaluate(context, now)
    }

    /**
     * Returns true whenever ANY possible ground or vehicle detection is active locally
     * (any active Tier A or candidate ground OUI / P25 / VHF-UHF / fleet router / camera emitter),
     * enabling always-on non-own TPMS collection & co-moving RSSI narrowing even while stationary.
     */
    fun hasActiveGroundOrVehicleDetection(
        context: Context? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val activeCachedGround = localThreatCache.values.any { t ->
            !t.isAircraft &&
                !t.isDrone &&
                t.agency != KokohoAgency.DRONE &&
                nowMs - t.updatedAtMs <= 45_000L
        }
        if (activeCachedGround) return true

        val liveRf = RadioLog.current(nowMs).any { nowMs - it.lastSeen <= 30_000L }
        if (liveRf) return true

        val liveP25Input = SdrRadio.p25Log.rows().any { it.inputAt > 0L && nowMs - it.inputAt <= 30_000L }
        if (liveP25Input) return true

        val liveDevs = DeviceRegistry.snapshot(nowMs).filter { nowMs - it.lastSeen <= 30_000L }
        for (snap in liveDevs) {
            if (snap.remoteId != null || KokohoSignatures.isTacticalOrRemoteIdDrone(snap.mac, snap.name, snap.vendor, false)) {
                continue
            }
            if (KokohoSignatures.isFlockCamera(snap.mac, snap.name)) return true
            val tier = KokohoSignatures.classifySignalTier(
                context = context,
                macOrFacet = snap.mac,
                ssidOrName = snap.name,
                vendor = snap.vendor,
                advert = snap.advert,
                hasRemoteId = false
            )
            if (tier == KokohoSignatures.WatchlistTier.TIER_A || tier == KokohoSignatures.WatchlistTier.TIER_B) {
                return true
            }
        }
        return false
    }

    /** Clears transient local RF/P25 threats when the RTL-SDR hardware stops or is unplugged. */
    fun onSdrDisconnected() {
        org.tucsoncommunity.kokoho.sdr.TpmsLog.clear()
        localThreatCache.entries.removeIf { (_, t) ->
            t.evidenceSources.all { it.kind.startsWith("RF_") || it.kind == "P25_UNIT" || it.kind == "TPMS_CHASSIS" }
        }
        lastEvaluateNowMs = 0L
        latestSnapshot = null
        cachedFallbackLocation = null
        cachedFallbackLocationAtMs = 0L
    }

    private fun resolveUserLocation(context: Context): android.location.Location? {
        val now = System.currentTimeMillis()
        org.tucsoncommunity.kokoho.ui.MapGps.best(
            listOf(
                ScanForegroundService.lastFix,
                org.tucsoncommunity.kokoho.nav.Navigator.lastFix
            ),
            now
        )?.takeIf { abs(now - it.time) <= 15 * 60_000L }?.let { return it }
        ScanForegroundService.lastFix?.let { return it }
        if (cachedFallbackLocation != null && now - cachedFallbackLocationAtMs < 10_000L) {
            return cachedFallbackLocation
        }
        cachedFallbackLocationAtMs = now
        val lm = runCatching {
            context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        }.getOrNull() ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty().ifEmpty {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            )
        }
        val candidates = providers
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .filter { abs(now - it.time) <= 15 * 60_000L }
        val resolved = org.tucsoncommunity.kokoho.ui.MapGps.pick(candidates, now)
        cachedFallbackLocation = resolved
        return resolved
    }

    /**
     * Fuses a newly evaluated local or fused threat into [evaluated] and [localThreatCache].
     * When a Hub ThreatObject already exists for the same UUID, server-derived coordinates, tighter
     * uncertainty radii, and additional witness/peer evidence are preserved non-recurrently rather
     * than skipped or overwritten blindly. Never conflates local LE (`POLICE`/`SHERIFF`) with `DHS_*`.
     */
    private fun upsertAndFuseThreat(
        evaluated: MutableMap<String, EvaluatedThreat>,
        incoming: EvaluatedThreat
    ) {
        val prevLocalMs = lastHeardByLocalHardwareAtMs[incoming.uuid] ?: 0L
        if (incoming.updatedAtMs > prevLocalMs) {
            lastHeardByLocalHardwareAtMs[incoming.uuid] = incoming.updatedAtMs
        }
        val existing = evaluated[incoming.uuid]
        if (existing == null) {
            evaluated[incoming.uuid] = incoming
            localThreatCache[incoming.uuid] = incoming
            return
        }
        val mergedBadges = (existing.evidenceSources + incoming.evidenceSources).distinctBy { it.facetKey }
        val preferIncomingPos = incoming.isPinpointed ||
            (!existing.isPinpointed && incoming.uncertaintyRadiusM <= existing.uncertaintyRadiusM) ||
            incoming.updatedAtMs - existing.updatedAtMs > 120_000L
        val chosenAgency = when {
            existing.agency != KokohoAgency.DHS_INTEROP && incoming.agency == KokohoAgency.DHS_INTEROP -> existing.agency
            existing.agency == KokohoAgency.DHS_INTEROP && incoming.agency != KokohoAgency.DHS_INTEROP -> incoming.agency
            !existing.agency.isDhs && incoming.agency.isDhs -> existing.agency
            existing.agency.isDhs && !incoming.agency.isDhs -> incoming.agency
            incoming.agency.priorityRank <= existing.agency.priorityRank -> incoming.agency
            else -> existing.agency
        }
        val classConf = max(existing.classificationConfidence, incoming.classificationConfidence)
        val spatConf = max(existing.spatialConfidence, incoming.spatialConfidence)
        val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
        val fused = incoming.copy(
            agency = chosenAgency,
            label = existing.label.ifBlank { incoming.label },
            posture = if (incoming.posture.ordinal >= existing.posture.ordinal) incoming.posture else existing.posture,
            overallConfidence = combinedConf,
            classificationConfidence = classConf,
            spatialConfidence = spatConf,
            estimatedDistanceM = if (preferIncomingPos) incoming.estimatedDistanceM else existing.estimatedDistanceM,
            uncertaintyRadiusM = min(existing.uncertaintyRadiusM, incoming.uncertaintyRadiusM),
            bearingDeg = incoming.bearingDeg ?: existing.bearingDeg,
            closingVelocityMps = incoming.closingVelocityMps ?: existing.closingVelocityMps,
            recurrenceSummary = if (existing.recurrenceSummary.length >= incoming.recurrenceSummary.length) existing.recurrenceSummary else incoming.recurrenceSummary,
            evidenceSources = mergedBadges,
            updatedAtMs = max(existing.updatedAtMs, incoming.updatedAtMs),
            lat = if (preferIncomingPos) (incoming.lat ?: existing.lat) else (existing.lat ?: incoming.lat),
            lon = if (preferIncomingPos) (incoming.lon ?: existing.lon) else (existing.lon ?: incoming.lon),
            isApproximatedPosition = existing.isApproximatedPosition || incoming.isApproximatedPosition,
            isPinpointed = existing.isPinpointed || incoming.isPinpointed,
            isAircraft = existing.isAircraft || incoming.isAircraft,
            isHelicopter = existing.isHelicopter || incoming.isHelicopter,
            aircraftTrackDeg = incoming.aircraftTrackDeg ?: existing.aircraftTrackDeg,
            aircraftHex = incoming.aircraftHex ?: existing.aircraftHex,
            isCircling = existing.isCircling || incoming.isCircling,
            isDrone = existing.isDrone || incoming.isDrone,
            altitudeFtAgl = incoming.altitudeFtAgl ?: existing.altitudeFtAgl,
            operatorLat = incoming.operatorLat ?: existing.operatorLat,
            operatorLon = incoming.operatorLon ?: existing.operatorLon,
            hardwareIdentifier = incoming.hardwareIdentifier ?: existing.hardwareIdentifier,
            sightingCount = max(existing.sightingCount, incoming.sightingCount),
            capabilities = (existing.capabilities + incoming.capabilities).distinct()
        )
        evaluated[incoming.uuid] = fused
        localThreatCache[incoming.uuid] = fused
    }

    private const val UNLOCATED_LOCAL_CACHE_TTL_MS = 3 * 60_000L

    private fun shouldEvictLocalCache(
        t: EvaluatedThreat,
        nowMs: Long,
        retentionWindowMs: Long,
        groundedHexes: Set<String> = emptySet()
    ): Boolean {
        val ageMs = nowMs - t.updatedAtMs
        if (ageMs > retentionWindowMs) return true
        if (t.isAircraft) {
            if (t.aircraftHex?.uppercase() in groundedHexes) return true
            return ageMs > min(retentionWindowMs, LIVE_AIRCRAFT_MAX_AGE_MS)
        }
        // Located ground/drone detections remain available for both the active rolling Map window
        // and the 3-hour Monitor window.
        if (t.lat != null && t.lon != null) {
            return false
        }
        // Unlocated local detections (no GPS fix when heard) expire after 3 minutes so they never trail a moving user.
        return ageMs > min(retentionWindowMs, UNLOCATED_LOCAL_CACHE_TTL_MS)
    }

    /**
     * Records a local observation at `(gpsLat, gpsLon)` with estimated range `estDistanceM`
     * and solves for either:
     *  - A user-centered radius circle (`radius = estDistanceM`) when GPS spread < 60m or 1D collinear road drive (`crossTrackM < 25m`), OR
     *  - An approximated source position circle (`(approxLat, approxLon)`, `radius = uncertaintyRadiusM`)
     *    when multiple GPS fixes span >= 60m with >= 25m 2D cross-track diversity.
     */
    fun recordAndTriangulate(
        uuid: String,
        gpsLat: Double?,
        gpsLon: Double?,
        signalDb: Int,
        estDistanceM: Int,
        nowMs: Long,
        decayMs: Long,
        userLat: Double? = gpsLat,
        userLon: Double? = gpsLon,
        evalNowMs: Long = nowMs
    ): TriangulationResult {
        val refClockMs = max(nowMs, evalNowMs)
        val list = spatialHistory.getOrPut(uuid) { mutableListOf() }
        synchronized(list) {
            list.removeAll { refClockMs - it.timestampMs > decayMs }
            val prevBest = list.maxByOrNull { it.signalDb }
            var geometryChanged = false
            val last = list.lastOrNull()
            // Strictly require advancing observation timestamp (`nowMs > last.timestampMs`) so silent cached devices
            // NEVER accumulate synthetic samples along the user's driving route.
            if (gpsLat != null && gpsLon != null && (last == null || nowMs > last.timestampMs)) {
                if (last != null && haversineMeters(last.lat, last.lon, gpsLat, gpsLon) < 12.0) {
                    if (abs(last.signalDb - signalDb) >= 2) geometryChanged = true
                    if (signalDb >= last.signalDb) {
                        last.lat = gpsLat
                        last.lon = gpsLon
                    }
                    last.signalDb = signalDb
                    last.estDistanceM = estDistanceM
                    last.timestampMs = nowMs
                } else {
                    if (last != null &&
                        nowMs - last.timestampMs >= 60_000L &&
                        estDistanceM <= 350 &&
                        haversineMeters(last.lat, last.lon, gpsLat, gpsLon) > 600.0
                    ) {
                        // Short-range mobile emitter relocated to a new scene (> 600m away after >= 60s): start fresh anchor.
                        list.clear()
                        triangulationCache.remove(uuid)
                    }
                    list.add(SpatialSample(gpsLat, gpsLon, signalDb, estDistanceM, nowMs))
                    if (list.size > 32) {
                        val peakSample = list.maxBy { it.signalDb }
                        val dropIdx = if (list.first() === peakSample && list.size > 1) 1 else 0
                        list.removeAt(dropIdx)
                    }
                    geometryChanged = true
                }
            }

            if (list.isEmpty()) {
                val cLat = gpsLat
                val cLon = gpsLon
                val effDist = if (cLat != null && cLon != null && userLat != null && userLon != null) {
                    max(haversineMeters(userLat, userLon, cLat, cLon).roundToInt(), estDistanceM)
                } else {
                    estDistanceM
                }
                return TriangulationResult(
                    centerLat = cLat,
                    centerLon = cLon,
                    estimatedDistanceM = effDist,
                    uncertaintyRadiusM = estDistanceM,
                    spatialConfidence = 40,
                    isApproximatedPosition = false,
                    isPinpointed = false,
                    isClosing = false
                )
            }

            val first = list.first()
            val latest = list.last()
            val best = list.maxBy { it.signalDb }
            val peakEstDistM = list.minOf { it.estDistanceM }
            val convergedPeakRadiusM = (peakEstDistM.toDouble() / sqrt(list.size.coerceIn(1, 6).toDouble()))
                .roundToInt()
                .coerceIn(min(18, peakEstDistM), peakEstDistM)
            val curUserLat = userLat ?: gpsLat ?: latest.lat
            val curUserLon = userLon ?: gpsLon ?: latest.lon
            val obsIsFresh = evalNowMs - latest.timestampMs <= 8_000L

            // Return cached triangulation if no new observation arrived:
            // Keep centerLat/centerLon strictly anchored at the emitter's cached position (NEVER teleported onto curUserLat/Lon!)
            val cached = triangulationCache[uuid]
            if (!geometryChanged && cached != null && cached.sampleCount == list.size) {
                val cLat = cached.result.centerLat ?: best.lat
                val cLon = cached.result.centerLon ?: best.lon
                val distFromUser = haversineMeters(curUserLat, curUserLon, cLat, cLon).roundToInt()
                val effDist = if (obsIsFresh && !cached.result.isApproximatedPosition && list.size == 1 && distFromUser < 10) {
                    latest.estDistanceM
                } else {
                    distFromUser
                }
                return cached.result.copy(
                    centerLat = cLat,
                    centerLon = cLon,
                    estimatedDistanceM = effDist,
                    isClosing = if (obsIsFresh) cached.result.isClosing else false
                )
            }

            val isClosing = if (obsIsFresh && list.size >= 2) {
                val prev = list[max(0, list.size - 2)]
                latest.estDistanceM < (prev.estDistanceM * 0.88).toInt() || (latest.signalDb - prev.signalDb >= 4)
            } else false

            // Compute total travel baseline across all recorded samples to detect genuine co-moving vehicles
            var totalSpreadM = 0.0
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val d = haversineMeters(list[i].lat, list[i].lon, list[j].lat, list[j].lon)
                    if (d > totalSpreadM) totalSpreadM = d
                }
            }
            val obsSpanMs = latest.timestampMs - first.timestampMs
            val isLiveCoMoving = (evalNowMs - latest.timestampMs <= 20_000L) &&
                list.size >= 3 &&
                obsSpanMs >= 30_000L &&
                totalSpreadM >= 250.0 &&
                (best.signalDb - latest.signalDb) <= 14 &&
                latest.estDistanceM <= 220

            // Stationary RF centroid lock when driving away:
            // When >= 4 samples have localized a stationary source (isApproximatedPosition && uncertaintyRadiusM <= 120)
            // and a new sample arrives further from best (> uncertaintyRadiusM) with weaker signal (<= best.signalDb - 6),
            // keep centerLat/centerLon and uncertaintyRadiusM strictly locked unless a stronger signal (> best.signalDb - 3)
            // appears at a genuinely displaced location.
            val referenceBest = prevBest ?: best
            val hasConvergedStationaryAnchor = list.size >= 4 &&
                cached != null &&
                cached.result.isApproximatedPosition &&
                cached.result.uncertaintyRadiusM <= 120 &&
                cached.result.centerLat != null &&
                cached.result.centerLon != null
            if (hasConvergedStationaryAnchor) {
                val anchorLat = cached!!.result.centerLat!!
                val anchorLon = cached.result.centerLon!!
                val distFromBest = haversineMeters(referenceBest.lat, referenceBest.lon, latest.lat, latest.lon)
                if (distFromBest > cached.result.uncertaintyRadiusM && latest.signalDb <= referenceBest.signalDb - 6) {
                    val keptDistM = haversineMeters(curUserLat, curUserLon, anchorLat, anchorLon).roundToInt()
                    val lockedRes = cached.result.copy(
                        centerLat = anchorLat,
                        centerLon = anchorLon,
                        estimatedDistanceM = keptDistM,
                        isClosing = false,
                        sampleCount = list.size
                    )
                    triangulationCache[uuid] = CachedTriangulation(
                        list.size,
                        curUserLat,
                        curUserLon,
                        signalDb,
                        lockedRes,
                        max(cached.peakSignalDb, best.signalDb)
                    )
                    return lockedRes
                }
                val displacedThresholdM = max(220.0, (referenceBest.estDistanceM + latest.estDistanceM) * 1.45)
                if (distFromBest > displacedThresholdM && latest.signalDb > referenceBest.signalDb - 3 && !isLiveCoMoving) {
                    val recentDisplaced = list.filter {
                        haversineMeters(latest.lat, latest.lon, it.lat, it.lon) <= 120.0 &&
                            latest.timestampMs - it.timestampMs <= 20_000L
                    }.ifEmpty { listOf(latest) }
                    var wSum = 0.0
                    var latW = 0.0
                    var lonW = 0.0
                    for (s in recentDisplaced) {
                        val w = 10.0.pow(s.signalDb / 8.0).coerceIn(1e-12, 1e6)
                        wSum += w
                        latW += s.lat * w
                        lonW += s.lon * w
                    }
                    val dispLat = if (wSum > 0.0) latW / wSum else latest.lat
                    val dispLon = if (wSum > 0.0) lonW / wSum else latest.lon
                    val dispRadM = latest.estDistanceM.coerceIn(20, 150)
                    val dispDistM = haversineMeters(curUserLat, curUserLon, dispLat, dispLon).roundToInt()
                    val dispRes = TriangulationResult(
                        centerLat = dispLat,
                        centerLon = dispLon,
                        estimatedDistanceM = dispDistM,
                        uncertaintyRadiusM = dispRadM,
                        spatialConfidence = 68,
                        isApproximatedPosition = recentDisplaced.size >= 2 || dispRadM <= 35,
                        isPinpointed = false,
                        isClosing = isClosing,
                        sampleCount = list.size
                    )
                    triangulationCache[uuid] = CachedTriangulation(
                        list.size,
                        curUserLat,
                        curUserLon,
                        signalDb,
                        dispRes,
                        max(cached.peakSignalDb, best.signalDb)
                    )
                    return dispRes
                }
            }

            if (isLiveCoMoving) {
                // Genuinely co-moving emitter travelling with the observer: anchor at the live recent window
                // (never lagged hundreds of meters behind at the historical path midpoint).
                val recentWindow = list.filter { latest.timestampMs - it.timestampMs <= 15_000L }.ifEmpty { listOf(latest) }
                var wSum = 0.0
                var latWeighted = 0.0
                var lonWeighted = 0.0
                for (s in recentWindow) {
                    val recencyWeight = kotlin.math.exp(-(latest.timestampMs - s.timestampMs).coerceAtLeast(0L) / 3500.0)
                    val w = 10.0.pow(s.signalDb / 10.0).coerceIn(1e-9, 1e6) * recencyWeight
                    wSum += w
                    latWeighted += s.lat * w
                    lonWeighted += s.lon * w
                }
                val liveLat = if (wSum > 0.0) latWeighted / wSum else latest.lat
                val liveLon = if (wSum > 0.0) lonWeighted / wSum else latest.lon
                val liveRadiusM = latest.estDistanceM.coerceIn(20, 150)
                val rawLiveDist = haversineMeters(curUserLat, curUserLon, liveLat, liveLon).roundToInt()
                val distFromUser = if (list.size == 1 && rawLiveDist < 8) latest.estDistanceM else rawLiveDist
                val res = TriangulationResult(
                    centerLat = liveLat,
                    centerLon = liveLon,
                    estimatedDistanceM = distFromUser,
                    uncertaintyRadiusM = liveRadiusM,
                    spatialConfidence = 74,
                    isApproximatedPosition = true,
                    isPinpointed = false,
                    isClosing = isClosing,
                    sampleCount = list.size
                )
                triangulationCache[uuid] = CachedTriangulation(list.size, curUserLat, curUserLon, signalDb, res)
                return res
            }

            // Stationary emitter: restrict triangulation to the local neighborhood (<= 450m) around peak reception
            // and exclude weak receding tail samples so departing drives never drag or bloom a settled source.
            val recedingGuardRadiusM = (cached?.result?.uncertaintyRadiusM ?: 120).coerceAtLeast(45).toDouble()
            val localSamples = list.filter {
                val distM = haversineMeters(best.lat, best.lon, it.lat, it.lon)
                distM <= 450.0 && !(distM > recedingGuardRadiusM && it.signalDb <= best.signalDb - 6)
            }.ifEmpty { listOf(best) }
            var maxSpreadM = 0.0
            var farA = localSamples.first()
            var farB = localSamples.last()
            for (i in localSamples.indices) {
                for (j in i + 1 until localSamples.size) {
                    val d = haversineMeters(localSamples[i].lat, localSamples[i].lon, localSamples[j].lat, localSamples[j].lon)
                    if (d > maxSpreadM) {
                        maxSpreadM = d
                        farA = localSamples[i]
                        farB = localSamples[j]
                    }
                }
            }

            val crossTrackM = if (localSamples.size >= 3 && maxSpreadM >= 45.0) {
                computeMaxCrossTrackMeters(localSamples, farA, farB)
            } else {
                0.0
            }

            val hasTwoPassGeometry = localSamples.size >= 3 && maxSpreadM >= 45.0 && crossTrackM >= 18.0

            if (!hasTwoPassGeometry) {
                // Single sighting or straight road pass-by:
                // Peak-dominant weighting (`10^(dB/8)`) locks the circle tightly to the closest-approach point
                // so driving past an emitter NEVER drags it behind the user's vehicle.
                var wSum = 0.0
                var latWeighted = 0.0
                var lonWeighted = 0.0
                for (s in localSamples) {
                    val w = 10.0.pow(s.signalDb / 8.0).coerceIn(1e-12, 1e6)
                    wSum += w
                    latWeighted += s.lat * w
                    lonWeighted += s.lon * w
                }
                val anchorCandidateLat = if (wSum > 0.0) latWeighted / wSum else best.lat
                val anchorCandidateLon = if (wSum > 0.0) lonWeighted / wSum else best.lon
                // Favor retaining settled stationary positions unless a strictly stronger peak (+3 dB) arrived:
                val prevCenterLat = cached?.result?.centerLat
                val prevCenterLon = cached?.result?.centerLon
                val prevRad = cached?.result?.uncertaintyRadiusM ?: convergedPeakRadiusM
                val strongerPeak = cached == null || best.signalDb > cached.peakSignalDb + 2
                val (anchorLat, anchorLon, settledRadiusM) = if (!strongerPeak && prevCenterLat != null && prevCenterLon != null) {
                    val shiftM = haversineMeters(prevCenterLat, prevCenterLon, anchorCandidateLat, anchorCandidateLon)
                    if (shiftM > 450.0) {
                        Triple(anchorCandidateLat, anchorCandidateLon, convergedPeakRadiusM)
                    } else {
                        val widenedRad = if (shiftM > max(40.0, prevRad * 0.70)) {
                            max(convergedPeakRadiusM, min(prevRad + 25, (shiftM * 0.8).roundToInt()))
                        } else {
                            min(prevRad, convergedPeakRadiusM)
                        }
                        Triple(prevCenterLat, prevCenterLon, widenedRad)
                    }
                } else {
                    Triple(anchorCandidateLat, anchorCandidateLon, convergedPeakRadiusM)
                }
                val distFromUserToAnchor = haversineMeters(curUserLat, curUserLon, anchorLat, anchorLon).roundToInt()
                val effDistM = if (obsIsFresh && localSamples.size == 1 && distFromUserToAnchor < 10) {
                    latest.estDistanceM
                } else {
                    distFromUserToAnchor
                }
                val baseSpatialConf = (38 + min(26, (maxSpreadM / 3.0).toInt())).coerceIn(38, 64)
                val res = TriangulationResult(
                    centerLat = anchorLat,
                    centerLon = anchorLon,
                    estimatedDistanceM = effDistM,
                    uncertaintyRadiusM = settledRadiusM,
                    spatialConfidence = baseSpatialConf,
                    isApproximatedPosition = localSamples.size >= 2 && (maxSpreadM >= 25.0 || settledRadiusM <= 35),
                    isPinpointed = false,
                    isClosing = isClosing,
                    sampleCount = list.size
                )
                triangulationCache[uuid] = CachedTriangulation(list.size, curUserLat, curUserLon, signalDb, res, best.signalDb)
                return res
            }

            // Genuine 2D local baseline (>= 45m spread AND >= 18m cross-track turn around a stationary target): solve 2D range-annulus intersection
            val refLat = best.lat
            val refLon = best.lon
            val mPerDegLat = 111_132.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.1)

            val searchSpanM = maxOf(maxSpreadM * 1.5, peakEstDistM * 1.25).coerceIn(80.0, 1200.0)
            val steps = 11
            var bestX = 0.0
            var bestY = 0.0
            var bestScore = Double.MAX_VALUE

            val sampleXY = localSamples.map { s ->
                val x = (s.lon - refLon) * mPerDegLon
                val y = (s.lat - refLat) * mPerDegLat
                val w = 1.0 / max(s.estDistanceM.toDouble(), 25.0).pow(1.5)
                Triple(x to y, s.estDistanceM.toDouble(), w)
            }
            val totalWeight = sampleXY.sumOf { it.third }.coerceAtLeast(1e-9)
            val meanX = sampleXY.sumOf { it.first.first * it.third } / totalWeight
            val meanY = sampleXY.sumOf { it.first.second * it.third } / totalWeight

            for (ix in -steps..steps) {
                val cx = (ix.toDouble() / steps) * searchSpanM
                for (iy in -steps..steps) {
                    val cy = (iy.toDouble() / steps) * searchSpanM
                    var err = 0.0
                    for ((pt, r, w) in sampleXY) {
                        val d = sqrt((cx - pt.first).pow(2) + (cy - pt.second).pow(2))
                        val diff = d - r
                        err += w * diff * diff
                    }
                    // Symmetry-breaking regularizer toward weighted sample centroid so N-S road travel does not bias Westward
                    err += 0.015 * totalWeight * ((cx - meanX).pow(2) + (cy - meanY).pow(2))
                    if (err < bestScore) {
                        bestScore = err
                        bestX = cx
                        bestY = cy
                    }
                }
            }

            val approxLat = refLat + (bestY / mPerDegLat)
            val approxLon = refLon + (bestX / mPerDegLon)
            val rmsResidualM = sqrt(bestScore / totalWeight)
            val distFromUserM = haversineMeters(curUserLat, curUserLon, approxLat, approxLon).roundToInt()

            // Safe range bounds: prevent IllegalArgumentException when distFromUserM * 0.85 < 15.0
            val maxDilution = max(15.0, convergedPeakRadiusM * 0.85)
            val dilutionM = (convergedPeakRadiusM * 36.0 / maxSpreadM.coerceAtLeast(1.0)).coerceIn(15.0, maxDilution)
            val minReasonableRadius = max(18, (peakEstDistM * 0.32).roundToInt())
            val maxReasonableRadius = max(minReasonableRadius, convergedPeakRadiusM)
            val approxRadiusM = maxOf(rmsResidualM * 0.65, dilutionM, minReasonableRadius.toDouble())
                .roundToInt()
                .coerceIn(minReasonableRadius, maxReasonableRadius)

            // Only collapse to a pinpointed point if 2D cross-track spread is wide (>= 45m) AND uncertainty <= 25m
            val isPinpointed = crossTrackM >= 45.0 && approxRadiusM <= PINPOINT_POINT_RADIUS_M
            val spatialConf = (88 - (approxRadiusM * 30 / max(distFromUserM, 80))).coerceIn(55, 92)

            // Stationary centroid prior (30m current-investigation invariant):
            // Never bloom an already-settled uncertainty radius or drift its centroid unless >= 3 recent
            // strong observations (> peak - 3 dB) genuinely relocate (> max(50m, 1.2 * r_settled)).
            val prevSettled = cached?.result
            if (prevSettled != null && prevSettled.centerLat != null && prevSettled.centerLon != null && approxRadiusM > prevSettled.uncertaintyRadiusM) {
                val shiftThresholdM = max(50.0, prevSettled.uncertaintyRadiusM * 1.2)
                val recentTail = list.takeLast(3)
                val sustainedDeparture = recentTail.size >= 3 && recentTail.all { s ->
                    s.signalDb > cached.peakSignalDb - 3 &&
                        haversineMeters(s.lat, s.lon, prevSettled.centerLat, prevSettled.centerLon) > shiftThresholdM
                }
                if (!sustainedDeparture) {
                    val keptDistM = haversineMeters(curUserLat, curUserLon, prevSettled.centerLat, prevSettled.centerLon).roundToInt()
                    val stableRes = prevSettled.copy(
                        estimatedDistanceM = keptDistM,
                        isClosing = isClosing,
                        sampleCount = list.size
                    )
                    triangulationCache[uuid] = CachedTriangulation(list.size, curUserLat, curUserLon, signalDb, stableRes, max(cached.peakSignalDb, best.signalDb))
                    return stableRes
                }
            }

            val res = TriangulationResult(
                centerLat = approxLat,
                centerLon = approxLon,
                estimatedDistanceM = distFromUserM,
                uncertaintyRadiusM = approxRadiusM,
                spatialConfidence = spatialConf,
                isApproximatedPosition = true,
                isPinpointed = isPinpointed,
                isClosing = isClosing,
                sampleCount = list.size
            )
            triangulationCache[uuid] = CachedTriangulation(list.size, curUserLat, curUserLon, signalDb, res, best.signalDb)
            return res
        }
    }

    private fun computeMaxCrossTrackMeters(
        samples: List<SpatialSample>,
        a: SpatialSample,
        b: SpatialSample
    ): Double {
        val mPerDegLat = 111_132.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.lat)).coerceAtLeast(0.1)
        val bx = (b.lon - a.lon) * mPerDegLon
        val by = (b.lat - a.lat) * mPerDegLat
        val len = sqrt(bx * bx + by * by)
        if (len < 1.0) return 0.0
        var maxPerp = 0.0
        for (s in samples) {
            val px = (s.lon - a.lon) * mPerDegLon
            val py = (s.lat - a.lat) * mPerDegLat
            val perp = abs(bx * py - by * px) / len
            if (perp > maxPerp) maxPerp = perp
        }
        return maxPerp
    }

    /** Direct/transiting aircraft only count as Monitor ("nearby") within 1 mile (1,609 m). */
    const val AIRCRAFT_NEARBY_DIRECT_MAX_M = 1_609

    /** Circling/orbiting aircraft count as Monitor ("nearby") within 3 miles (4,828 m). */
    const val AIRCRAFT_NEARBY_CIRCLING_MAX_M = 4_828

    fun isAircraftNearbyForMonitor(distM: Int, isCircling: Boolean, hasUserLoc: Boolean): Boolean {
        if (!hasUserLoc) return false
        return distM <= AIRCRAFT_NEARBY_DIRECT_MAX_M ||
            (isCircling && distM <= AIRCRAFT_NEARBY_CIRCLING_MAX_M)
    }

    /** Ground and drone detections alert Monitor within ~0.75 mi (1,200 m) when fresh (<= 15m), and <= 450 m (~0.28 mi) once > 15m old. */
    const val MONITOR_GROUND_NEARBY_MAX_EDGE_M = 1_050.0
    const val MONITOR_GROUND_NEARBY_MAX_CENTER_M = 1_200
    const val MONITOR_STALE_GROUND_NEARBY_MAX_CENTER_M = 450
    private const val MONITOR_LIVE_LOCAL_HARDWARE_MAX_CENTER_M = 2_414

    /** Wide-area air-to-ground RF detections alert Monitor and Notifications within 1.5 mi (2,414 m) nearest edge and 2.5 mi (4,023 m) center. */
    const val MONITOR_AIR_RF_NEARBY_MAX_EDGE_M = 2_414.0
    const val MONITOR_AIR_RF_NEARBY_MAX_CENTER_M = 4_023

    fun isGroundOrDroneNearbyForMonitor(
        threat: EvaluatedThreat,
        hasUserLoc: Boolean,
        nowMs: Long,
        heardLocallyLive: Boolean
    ): Boolean {
        val ageMs = (nowMs - threat.updatedAtMs).coerceAtLeast(0L)
        if (!hasUserLoc || threat.lat == null || threat.lon == null) {
            // Without user GPS (or when unlocated), only live signals physically heard by this phone's own radios
            // within the last 90 seconds can alert Monitor/Notifications - never remote Hub or historical entities.
            return heardLocallyLive &&
                ageMs <= 90_000L &&
                threat.estimatedDistanceM <= MONITOR_GROUND_NEARBY_MAX_CENTER_M
        }
        val isAirRf = threat.agency == KokohoAgency.DHS_AMO
        val isLiveLocalDirect = heardLocallyLive && ageMs <= 90_000L && threat.uncertaintyRadiusM <= 600
        val maxEdge = when {
            isAirRf || isLiveLocalDirect -> MONITOR_AIR_RF_NEARBY_MAX_EDGE_M
            else -> MONITOR_GROUND_NEARBY_MAX_EDGE_M
        }
        val maxCenter = when {
            isAirRf -> MONITOR_AIR_RF_NEARBY_MAX_CENTER_M
            isLiveLocalDirect -> MONITOR_LIVE_LOCAL_HARDWARE_MAX_CENTER_M
            !heardLocallyLive && ageMs > 15 * 60_000L -> MONITOR_STALE_GROUND_NEARBY_MAX_CENTER_M
            else -> MONITOR_GROUND_NEARBY_MAX_CENTER_M
        }
        return threat.nearestEdgeDistanceM <= maxEdge && threat.estimatedDistanceM <= maxCenter
    }

    private fun spatiallyCompatible(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?, maxDistM: Double): Boolean {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return true
        return haversineMeters(lat1, lon1, lat2, lon2) <= maxDistM
    }

    private fun resolveRfCoords(
        rf: RadioLog.Entry,
        userLoc: android.location.Location?,
        nowMs: Long
    ): Pair<Double?, Double?> {
        val freshUserLoc = userLoc?.takeIf { abs(nowMs - rf.lastSeen) <= 5_000L }
        val rLat = rf.lastLat ?: rf.peakLat ?: freshUserLoc?.latitude
        val rLon = rf.lastLon ?: rf.peakLon ?: freshUserLoc?.longitude
        return rLat to rLon
    }

    private fun deduplicateRemoteRfEchoes(
        evaluated: MutableMap<String, EvaluatedThreat>,
        keepUuid: String,
        freqHz: Long,
        refLat: Double?,
        refLon: Double?,
        refTsMs: Long
    ) {
        val rfPrefix = "rf:chan:$freqHz:"
        val dupKeys = evaluated.entries
            .filter { (otherUuid, other) ->
                otherUuid != keepUuid &&
                    !other.isAircraft &&
                    !other.isDrone &&
                    other.evidenceSources.isNotEmpty() &&
                    other.evidenceSources.all { it.facetKey.startsWith("rf:") } &&
                    other.evidenceSources.any { it.facetKey.startsWith(rfPrefix) } &&
                    abs(other.updatedAtMs - refTsMs) <= 10 * 60_000L &&
                    spatiallyCompatible(refLat, refLon, other.lat, other.lon, 450.0)
            }
            .map { it.key }
        for (dk in dupKeys) {
            val dup = evaluated.remove(dk) ?: continue
            val kept = evaluated[keepUuid] ?: continue
            val tighterRadius = if (dup.sightingCount >= 2 && dup.uncertaintyRadiusM < kept.uncertaintyRadiusM) {
                dup.uncertaintyRadiusM
            } else {
                kept.uncertaintyRadiusM
            }
            val updated = kept.copy(
                uncertaintyRadiusM = tighterRadius,
                sightingCount = max(kept.sightingCount, dup.sightingCount),
                isApproximatedPosition = kept.isApproximatedPosition || (dup.sightingCount >= 2 && dup.isApproximatedPosition)
            )
            evaluated[keepUuid] = updated
            if (localThreatCache.containsKey(keepUuid)) {
                localThreatCache[keepUuid] = updated
            }
        }
    }

    fun evaluate(context: Context, nowMs: Long = System.currentTimeMillis()): PostureSnapshot {
        KokohoSignatures.ensureLoaded(context)
        val alarmEnabled = KokohoSignatures.getEnabledAgencies(context)
        val mapVisible = KokohoSignatures.getMapVisibleAgencies(context)
        val enabled = alarmEnabled + mapVisible
        val activeMapWindow = KokohoSignatures.getMapTimeWindow(context)
        val mapVisibilityMs = activeMapWindow.windowMs
        val monitorWindowMs = KokohoSignatures.MONITOR_ACTIVE_WINDOW_MS
        val retentionWindowMs = max(mapVisibilityMs, monitorWindowMs)
        val auxnetOn = KokohoSignatures.isAuxnetEnabled(context)

        val enabledScopeText = if (alarmEnabled.isEmpty()) {
            "No monitor alerts selected"
        } else {
            alarmEnabled.sortedBy { it.priorityRank }.joinToString(" · ") { it.friendlyLabel }
        }

        val userLoc = resolveUserLocation(context)
        val evaluated = mutableMapOf<String, EvaluatedThreat>()
        val localRawObservations = mutableListOf<RawSensorReportCandidate>()

        // Pre-scan ADS-B feed to evict landed helicopters / grounded aircraft immediately and record landing pads
        if (KokohoSignatures.isAdsbEnabled(context)) {
            for (seen in org.tucsoncommunity.kokoho.online.PoliceAircraft.all(nowMs)) {
                val p = seen.plane
                if (!org.tucsoncommunity.kokoho.online.PoliceAircraft.isGroundedOrParked(p)) continue
                if (org.tucsoncommunity.kokoho.online.PoliceAircraft.isGliderOrExcludedTrainer(p)) continue
                val agency = KokohoSignatures.classifyAircraftAgency(
                    owner = p.owner,
                    registration = p.registration,
                    callsign = p.callsign,
                    hitLabel = seen.hit?.label,
                    type = p.type,
                    hex = p.hex,
                    speedKt = null,
                    isCircling = false
                )
                if (agency != null) {
                    org.tucsoncommunity.kokoho.online.PoliceAircraft.markGroundedAndRecordLanding(
                        context = context,
                        hex = p.hex,
                        agencyId = agency.id,
                        lat = p.lat,
                        lon = p.lon,
                        nowMs = nowMs
                    )
                    localThreatCache.remove(deterministicUuidV5("adsb:icao:${p.hex.uppercase()}"))
                }
            }
        }
        val groundedHexes = org.tucsoncommunity.kokoho.online.PoliceAircraft.currentlyGroundedHexes(nowMs)

        // Prune expired local threat cache entries using bounded local TTLs (never let All Time pin transient RF on the user)
        localThreatCache.entries.removeIf {
            KokohoEntityLedger.isSuppressedFalsePositiveThreat(it.value) ||
                shouldEvictLocalCache(it.value, nowMs, retentionWindowMs, groundedHexes)
        }

        // 1. Evaluate remote sanitized ThreatObjects from Auxnet kokoho-hub
        if (auxnetOn) {
            evaluateRemoteHubObjects(
                context = context,
                nowMs = nowMs,
                mapVisibilityMs = retentionWindowMs,
                enabled = enabled,
                groundedHexes = groundedHexes,
                userLoc = userLoc,
                evaluated = evaluated
            )
        }

        // 2. Evaluate local RTL-SDR RF hits, Pro/Enterprise/RemoteID Drones, and close-range Fleet OUI devices
        val rfWindowMs = min(retentionWindowMs, LOCAL_RF_ONLY_CACHE_TTL_MS)
        val devWindowMs = min(retentionWindowMs, LOCAL_SENSOR_CACHE_TTL_MS)
        val triDecayMs = min(retentionWindowMs, LOCAL_TRIANGULATION_DECAY_MS)
        val localRfHits = RadioLog.current(nowMs).filter { nowMs - it.lastSeen <= rfWindowMs }
        val localDevices = DeviceRegistry.snapshot(nowMs).filter { nowMs - it.lastSeen <= devWindowMs }
        for (snap in localDevices) {
            if (snap.remoteId == null && KokohoSignatures.isDjiConsumerHandheld(snap.mac, snap.name, snap.vendor)) {
                val normMac = KokohoSignatures.normalizeMac(snap.mac)
                val groupKey = KokohoSignatures.routerRadioGroupKey(normMac)
                val staleIds = setOf(
                    deterministicUuidV5("uas:mac:$normMac"),
                    deterministicUuidV5("hw:mac:$normMac"),
                    deterministicUuidV5("hw:mac:$groupKey")
                )
                for (sid in staleIds) {
                    if (evaluated[sid]?.isDrone == true || evaluated[sid]?.agency == KokohoAgency.DRONE) {
                        evaluated.remove(sid)
                    }
                    if (localThreatCache[sid]?.isDrone == true || localThreatCache[sid]?.agency == KokohoAgency.DRONE) {
                        localThreatCache.remove(sid)
                    }
                    val persisted = KokohoEntityLedger.findByUuid(context, sid)
                    if (persisted != null && (persisted.isDrone || persisted.agency == KokohoAgency.DRONE)) {
                        KokohoEntityLedger.remove(context, sid)
                    }
                }
            }
        }

        // 2a. Pro / Enterprise / Military / Remote ID Drones (#24)
        if (KokohoSignatures.isDroneDetectionEnabled(context)) {
            evaluateLocalDrones(
                userLoc = userLoc,
                localDevices = localDevices,
                evaluated = evaluated,
                localRawObservations = localRawObservations
            )
        }

        // 2b. Curated Fleet OUI / Tactical candidates (collapse dual-band BSSIDs, weight unit vs factory SSIDs)
        data class LocalOuiCandidate(
            val mac: String,
            val groupKey: String,
            val agency: KokohoAgency,
            val label: String,
            val ssid: String?,
            val confidence: Int,
            val rssi: Int,
            val sightings: Int,
            val rising: Boolean,
            val firstSeen: Long,
            val lastSeen: Long,
            val lat: Double?,
            val lon: Double?,
            val obsLat: Double?,
            val obsLon: Double?,
            val isGenericRouter: Boolean = false,
            val broadcastPos: DeviceRegistry.GeoSample? = null,
            val locatedFit: org.tucsoncommunity.kokoho.detect.Locator.Estimate? = null
        )

        fun snapshotObsCoords(snap: DeviceRegistry.Snapshot): Pair<Double?, Double?> {
            val freshUserLoc = userLoc?.takeIf { abs(nowMs - snap.lastSeen) <= 5_000L }
            val oLat = snap.lastPosition?.lat ?: snap.place?.lat ?: freshUserLoc?.latitude
            val oLon = snap.lastPosition?.lon ?: snap.place?.lon ?: freshUserLoc?.longitude
            return oLat to oLon
        }

        val snapshotsByRouterGroup = localDevices.groupBy { KokohoSignatures.routerRadioGroupKey(it.mac) }

        val rawOuiCandidates = localDevices.mapNotNull { snap ->
            if (snap.remoteId != null || KokohoSignatures.isTacticalOrRemoteIdDrone(snap.mac, snap.name, snap.vendor, false)) {
                return@mapNotNull null
            }
            if (KokohoSignatures.isIgnoredInfrastructureOrRetail(snap.mac, snap.name)) {
                return@mapNotNull null
            }
            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            val groupKey = KokohoSignatures.routerRadioGroupKey(normMac)
            val groupUuid = deterministicUuidV5("hw:mac:$groupKey")
            val isWatchedGroup = KokohoSignatures.isEntityWatched(context, groupUuid)
            val groupSnaps = snapshotsByRouterGroup[groupKey].orEmpty()
            val (snapObsLat, snapObsLon) = snapshotObsCoords(snap)
            // Suppress traffic cabinets, >48h TSF uptime fixed routers, and Open [ESS] commercial guest VAP groups
            // while still queuing them as passive observations for bulk telemetry batch upload.
            if (!isWatchedGroup && KokohoSignatures.isStationaryOrCommercialRouterGroup(groupSnaps, context)) {
                val passiveLat = snap.place?.lat ?: snapObsLat
                val passiveLon = snap.place?.lon ?: snapObsLon
                if (passiveLat != null && passiveLon != null) {
                    localRawObservations += RawSensorReportCandidate(
                        facetKey = "hw:mac:$groupKey",
                        rawLat = passiveLat,
                        rawLon = passiveLon,
                        signalDb = snap.rssi.toDouble(),
                        modality = "WIFI_PASSIVE_INFRA",
                        additionalFacets = emptyList(),
                        agency = KokohoAgency.DHS_INTEROP,
                        label = "Suppressed Stationary/Cabinet Router",
                        propClass = "GROUND_DIRECT",
                        uncertaintyRadiusM = estimateRssiDistanceMeters(snap.rssi).toDouble(),
                        isDerived = false,
                        localSightings = snap.sightings.coerceAtLeast(1),
                        ssidHint = snap.name,
                        isFlagged = false,
                        obsTimestampMs = snap.lastSeen
                    )
                }
                return@mapNotNull null
            }
            val learnedMac = KokohoSignatures.lookupMacIntel(context, normMac)
            val ouiSig = KokohoSignatures.matchOui(normMac, snap.name)
            val bySsid = KokohoSignatures.matchOuiOrSsid(normMac, snap.name, snap.vendor, hasRemoteId = false)
            val isGenericRouter = KokohoSignatures.isGenericUnmarkedFleetRouter(normMac, snap.name)
            // Explicit SSID agency (e.g. TPD, PCSO, PCSD, Sheriff, AZDPS, Fire) always takes precedence over generic OUI defaults!
            val matchedPair = if (bySsid != null && !isGenericRouter) {
                val baseLabel = ouiSig?.label ?: bySsid.second
                Triple(bySsid.first, baseLabel, max(ouiSig?.confidence ?: 78, 86))
            } else if (ouiSig != null) {
                Triple(ouiSig.agencyEnum, ouiSig.label, ouiSig.confidence)
            } else if (bySsid != null) {
                val desc = if (!snap.name.isNullOrBlank()) "${bySsid.second} (${snap.name})" else bySsid.second
                Triple(bySsid.first, desc, 78)
            } else if (learnedMac != null && learnedMac.corroboratedHits >= 3 && learnedMac.distinctDays >= 2 && learnedMac.reputationScore >= 0.88) {
                Triple(
                    learnedMac.agencyEnum,
                    learnedMac.label.ifBlank { "${learnedMac.agencyEnum.friendlyLabel} Learned Unit" },
                    (learnedMac.reputationScore * 100).roundToInt().coerceIn(60, 96)
                )
            } else {
                return@mapNotNull null
            }
            var agencyResolved = matchedPair.first
            var conf = matchedPair.third
            // Only allow multi-day corroborated learnedMac to refine a generic DHS router; NEVER overwrite Police/Sheriff/Fire/SAR
            if (learnedMac != null &&
                learnedMac.corroboratedHits >= 3 &&
                learnedMac.distinctDays >= 2 &&
                learnedMac.reputationScore >= 0.88 &&
                isGenericRouter &&
                agencyResolved.isDhs &&
                learnedMac.agencyEnum.isDhs
            ) {
                if (learnedMac.agencyEnum.priorityRank < agencyResolved.priorityRank || agencyResolved == KokohoAgency.DHS_INTEROP) {
                    agencyResolved = learnedMac.agencyEnum
                }
                conf = max(conf, (learnedMac.reputationScore * 100).roundToInt().coerceIn(70, 96))
            }
            val isStaticBaseline = KokohoSignatures.isStaticBaselineRouter(context, normMac, snapObsLat, snapObsLon) && !isWatchedGroup
            val upSince = snap.upSince ?: snap.advert?.wifi?.upSince
            val isIgnitionFreshMobile = upSince != null &&
                snap.lastSeen > upSince &&
                (snap.lastSeen - upSince) <= 12L * 3600_000L &&
                !KokohoSignatures.isOpenPublicWifiCapabilities(snap.advert?.wifi?.capabilities)
            val isMobileRouterModel = KokohoSignatures.isMobileVehicleRouterModelSsid(snap.name)
            if (isStaticBaseline) {
                conf = min(conf, 35)
            } else if (KokohoSignatures.isHighValueUnitSsid(snap.name)) {
                conf = min(96, conf + 18)
            } else if (!isGenericRouter) {
                // Confidently attested NIC sub-block or explicit agency SSID: never cap down to default-router floor!
                conf = max(conf, matchedPair.third)
            } else if (isIgnitionFreshMobile) {
                // Shift-fresh 802.11 TSF boot epoch (<= 12h) on WPA2/WPA3 fleet router qualifies as Likely mobile unit
                conf = max(min(conf, 68), 66)
            } else if (KokohoSignatures.isLowConfidenceDefaultRouterSsid(snap.name)) {
                conf = min(conf, 54)
            } else if (isGenericRouter && learnedMac == null) {
                // Uncorroborated generic fleet router: stay soft <= 58% YELLOW until corroborated
                conf = min(conf, 58)
            }
            val is5Ghz = (snap.advert?.wifi?.frequencyMhz ?: 0) >= 5000
            val minRssiGate = if (!isGenericRouter || isMobileRouterModel || isIgnitionFreshMobile) -95 else -86
            val strongEnough = snap.rssi >= minRssiGate ||
                snap.bestRssi >= minRssiGate ||
                (snap.sightings >= 2 && snap.bestRssi >= -89) ||
                (is5Ghz && snap.bestRssi >= -91)
            if (!strongEnough) return@mapNotNull null
            LocalOuiCandidate(
                mac = normMac,
                groupKey = groupKey,
                agency = agencyResolved,
                label = if (!snap.name.isNullOrBlank() && !matchedPair.second.contains(snap.name!!)) {
                    "${matchedPair.second} · ${snap.name}"
                } else {
                    matchedPair.second
                },
                ssid = snap.name,
                confidence = conf,
                rssi = max(snap.rssi, snap.bestRssi - 4),
                sightings = snap.sightings.coerceAtLeast(1),
                rising = (nowMs - snap.lastSeen <= 15_000L) && snap.rssi >= snap.bestRssi - 3 && snap.sightings >= 2 && snap.rssi >= -74,
                firstSeen = snap.firstSeen,
                lastSeen = snap.lastSeen,
                lat = snap.place?.lat ?: snapObsLat,
                lon = snap.place?.lon ?: snapObsLon,
                obsLat = snapObsLat,
                obsLon = snapObsLon,
                isGenericRouter = isGenericRouter,
                broadcastPos = snap.broadcast,
                locatedFit = snap.located
            )
        }

        // Collapse dual-band and sibling VAP BSSIDs (e.g. `00:30:44:74:AB:83` + `06:30:44:74:AB:83` PCSD-Vehicle) into one distinct unit
        val groupedOuis = rawOuiCandidates
            .groupBy { it.groupKey }
            .values
            .map { group ->
                val strongest = group.maxBy { it.rssi }
                val latestInGroup = group.maxBy { it.lastSeen }
                val explicitAgencySibling = group.filter { !it.isGenericRouter && !it.ssid.isNullOrBlank() }.maxByOrNull { it.confidence }
                val bestNamed = explicitAgencySibling ?: group.filter { !it.ssid.isNullOrBlank() }.maxByOrNull { it.confidence }
                val primary = bestNamed ?: group.maxBy { it.confidence }
                val radiosNote = if (group.size > 1) " [${group.size} radios]" else ""
                primary.copy(
                    mac = strongest.groupKey,
                    label = "${primary.label}$radiosNote",
                    confidence = group.maxOf { it.confidence },
                    rssi = strongest.rssi,
                    sightings = group.maxOf { it.sightings },
                    rising = group.any { it.rising },
                    firstSeen = group.minOf { it.firstSeen },
                    lastSeen = latestInGroup.lastSeen,
                    lat = strongest.lat ?: primary.lat,
                    lon = strongest.lon ?: primary.lon,
                    obsLat = latestInGroup.obsLat ?: strongest.obsLat ?: strongest.lat,
                    obsLon = latestInGroup.obsLon ?: strongest.obsLon ?: strongest.lon,
                    isGenericRouter = group.all { it.isGenericRouter },
                    broadcastPos = group.mapNotNull { it.broadcastPos }.maxByOrNull { it.time },
                    locatedFit = group.mapNotNull { it.locatedFit }.minByOrNull { it.radiusM }
                )
            }

        data class AttestedSignalRef(
            val agency: KokohoAgency,
            val ts: Long,
            val lat: Double?,
            val lon: Double?
        )

        // Pre-scan explicit cataloged RF hits (with true sighting coordinates) to know whether DHS is the attested primary presence at this scene
        val explicitCatalogRfByTime = localRfHits.mapNotNull { rf ->
            KokohoSignatures.matchFrequency(rf.freqHz, snrDb = rf.peakSnrDb)?.takeIf { it.isAlertableDirectOrMobile }?.let { sig ->
                val (rLat, rLon) = resolveRfCoords(rf, userLoc, nowMs)
                AttestedSignalRef(sig.agencyEnum, rf.lastSeen, rLat, rLon)
            }
        }
        val hasConcurrentExplicitDhsRf = explicitCatalogRfByTime.any { it.agency.isDhs && it.agency != KokohoAgency.DHS_INTEROP }

        // Pre-scan local LE citation printer kits (excluding agency-agnostic Axon bodycams/APX accessories, which
        // are used by DHS/CBP/ICE/HSI as well as PD/Sheriff and must NEVER force a router to POLICE)
        val concurrentLeKitTimestamps = localDevices.mapNotNull { snap ->
            if (snap.rssi < -88) return@mapNotNull null
            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            if (!KokohoSignatures.isCompanionKitDevice(normMac, snap.name, snap.vendor)) return@mapNotNull null
            if (KokohoSignatures.isAxonOrAgencyAgnosticAccessory(normMac, snap.name, snap.vendor)) return@mapNotNull null
            val nameUp = (snap.name ?: "").uppercase()
            val explicitSheriff = nameUp.contains("SHERIFF") || nameUp.contains("PCSO") || nameUp.contains("PCSD")
            val (sLat, sLon) = snapshotObsCoords(snap)
            val localDhsScene = explicitCatalogRfByTime.any {
                it.agency.isDhs && it.agency != KokohoAgency.DHS_INTEROP &&
                    abs(it.ts - snap.lastSeen) <= 5 * 60_000L &&
                    spatiallyCompatible(sLat, sLon, it.lat, it.lon, 500.0)
            }
            if (!explicitSheriff && localDhsScene) return@mapNotNull null
            val kitAgency = if (explicitSheriff) KokohoAgency.SHERIFF else KokohoAgency.POLICE
            AttestedSignalRef(kitAgency, snap.lastSeen, sLat, sLon)
        }
        val concurrentLeRfSignals = localRfHits.mapNotNull { rf ->
            val explicitSig = KokohoSignatures.matchFrequency(rf.freqHz, snrDb = rf.peakSnrDb)
            val rfSig = explicitSig
                ?: if (!hasConcurrentExplicitDhsRf) {
                    KokohoSignatures.matchPublicSafetyMobileUplink(rf.freqHz, rf.peakSnrDb, enabled)
                } else {
                    null
                }
                ?: return@mapNotNull null
            if (rfSig.agencyEnum == KokohoAgency.POLICE || rfSig.agencyEnum == KokohoAgency.SHERIFF) {
                val (rLat, rLon) = resolveRfCoords(rf, userLoc, nowMs)
                AttestedSignalRef(rfSig.agencyEnum, rf.lastSeen, rLat, rLon)
            } else {
                null
            }
        }
        val concurrentLeAll = concurrentLeKitTimestamps + concurrentLeRfSignals

        // 2c. Emit individuated OUI units and fuse bidirectionally with Hub ThreatObjects
        val ouiThreatUuids = mutableListOf<String>()
        val ouiLatestObsCoords = mutableMapOf<String, Pair<Double, Double>>()
        val emittedOuiGroupKeys = mutableSetOf<String>()
        for (ouiRaw in groupedOuis) {
            var oui = ouiRaw
            var corroboratedByLe = false
            if (oui.isGenericRouter && (oui.agency.isDhs || oui.agency == KokohoAgency.DHS_INTEROP)) {
                val matchedLe = concurrentLeAll.filter { ref ->
                    abs(oui.lastSeen - ref.ts) <= COLOCATION_WINDOW_MS &&
                        spatiallyCompatible(oui.obsLat ?: oui.lat, oui.obsLon ?: oui.lon, ref.lat, ref.lon, COLOCATION_MAX_DIST_M)
                }
                if (matchedLe.isNotEmpty()) {
                    val targetLeAgency = when {
                        matchedLe.any { it.agency == KokohoAgency.SHERIFF } &&
                            KokohoAgency.SHERIFF in enabled &&
                            KokohoAgency.POLICE !in enabled -> KokohoAgency.SHERIFF
                        matchedLe.any { it.agency == KokohoAgency.POLICE } -> KokohoAgency.POLICE
                        KokohoAgency.POLICE in enabled -> KokohoAgency.POLICE
                        KokohoAgency.SHERIFF in enabled -> KokohoAgency.SHERIFF
                        else -> KokohoAgency.POLICE
                    }
                    oui = oui.copy(
                        agency = targetLeAgency,
                        label = "${targetLeAgency.friendlyLabel} (Fleet LE Kit · ${oui.mac})",
                        confidence = max(oui.confidence, 84)
                    )
                    corroboratedByLe = true
                } else if (oui.agency !in enabled && (KokohoAgency.POLICE in enabled || KokohoAgency.SHERIFF in enabled)) {
                    val fallbackLe = if (KokohoAgency.POLICE in enabled) KokohoAgency.POLICE else KokohoAgency.SHERIFF
                    oui = oui.copy(
                        agency = fallbackLe,
                        label = "${fallbackLe.friendlyLabel} Unmarked Fleet Router (${oui.mac})"
                    )
                }
            }
            if (oui.agency !in enabled) continue
            emittedOuiGroupKeys += oui.groupKey
            val facetKey = "hw:mac:${oui.mac}"
            val uuid = deterministicUuidV5(facetKey)

            val rawEstDistM = estimateRssiDistanceMeters(oui.rssi)
            val obsLat = oui.obsLat ?: oui.lat
            val obsLon = oui.obsLon ?: oui.lon
            val rawTri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = obsLat,
                gpsLon = obsLon,
                signalDb = oui.rssi,
                estDistanceM = rawEstDistM,
                nowMs = oui.lastSeen,
                decayMs = triDecayMs,
                userLat = userLoc?.latitude,
                userLon = userLoc?.longitude,
                evalNowMs = nowMs
            )
            val tri = when {
                oui.broadcastPos != null -> {
                    val bDist = if (userLoc != null) {
                        haversineMeters(userLoc.latitude, userLoc.longitude, oui.broadcastPos.lat, oui.broadcastPos.lon).roundToInt().coerceAtLeast(10)
                    } else rawTri.estimatedDistanceM
                    rawTri.copy(
                        centerLat = oui.broadcastPos.lat,
                        centerLon = oui.broadcastPos.lon,
                        estimatedDistanceM = bDist,
                        uncertaintyRadiusM = 15,
                        spatialConfidence = 96,
                        isApproximatedPosition = true,
                        isPinpointed = true
                    )
                }
                oui.locatedFit != null && (!rawTri.isApproximatedPosition || oui.locatedFit.radiusM <= rawTri.uncertaintyRadiusM) -> {
                    val fitRadM = oui.locatedFit.radiusM.roundToInt().coerceAtLeast(12)
                    val fitDist = if (userLoc != null) {
                        haversineMeters(userLoc.latitude, userLoc.longitude, oui.locatedFit.lat, oui.locatedFit.lon).roundToInt()
                    } else rawTri.estimatedDistanceM
                    val fitConf = max(rawTri.spatialConfidence, (90 - oui.locatedFit.radiusM * 0.35).roundToInt().coerceIn(68, 92))
                    rawTri.copy(
                        centerLat = oui.locatedFit.lat,
                        centerLon = oui.locatedFit.lon,
                        estimatedDistanceM = fitDist,
                        uncertaintyRadiusM = fitRadM,
                        spatialConfidence = fitConf,
                        isApproximatedPosition = true,
                        isPinpointed = fitRadM <= PINPOINT_POINT_RADIUS_M
                    )
                }
                else -> rawTri
            }

            val isLiveSighting = nowMs - oui.lastSeen <= 15_000L
            val posture = when {
                oui.confidence <= 58 && !corroboratedByLe -> PostureColor.YELLOW
                tri.estimatedDistanceM <= 60 || (isLiveSighting && tri.estimatedDistanceM <= 120 && oui.rssi >= -65) -> PostureColor.RED
                (isLiveSighting && oui.rising && tri.estimatedDistanceM <= 250) || (isLiveSighting && tri.isClosing && tri.estimatedDistanceM <= 300) -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = oui.confidence
            val spatConf = tri.spatialConfidence
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
            val badge = EvidenceBadge(
                kind = "WIFI_BLE_OUI",
                facetKey = facetKey,
                detail = "${oui.label} (${oui.mac} · ${oui.rssi} dBm)",
                weight = if (oui.confidence <= 40) 0.35 else 0.75,
                lastSeenMs = oui.lastSeen
            )

            val threat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = oui.agency,
                label = oui.agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = tri.estimatedDistanceM,
                uncertaintyRadiusM = tri.uncertaintyRadiusM,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = oui.label,
                evidenceSources = listOf(badge),
                updatedAtMs = oui.lastSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed,
                hardwareIdentifier = oui.mac,
                sightingCount = max(oui.sightings, tri.sampleCount)
            )
            upsertAndFuseThreat(evaluated, threat)
            ouiThreatUuids += uuid
            CoMovingTpmsTracker.recordEmitterSample(
                emitterKey = uuid,
                timestampMs = oui.lastSeen,
                signalDb = oui.rssi.toDouble(),
                obsLat = obsLat,
                obsLon = obsLon
            )
            if (obsLat != null && obsLon != null) {
                ouiLatestObsCoords[uuid] = obsLat to obsLon
            }

            // Stream genuine RAW local sensor position & RSSI (strictly anchored to the sighting's own GPS, never moving userLoc!):
            val staticUnconfirmed = KokohoSignatures.isStaticBaselineRouter(context, oui.mac, obsLat, obsLon) && oui.confidence <= 40
            val isFlaggedOui = !staticUnconfirmed && (
                oui.confidence >= 70 ||
                    corroboratedByLe ||
                    !oui.isGenericRouter ||
                    (tri.isApproximatedPosition && tri.sampleCount >= 2)
                )
            if (obsLat != null && obsLon != null) {
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = obsLat,
                    rawLon = obsLon,
                    signalDb = oui.rssi.toDouble(),
                    modality = "WIFI_BLE_OUI",
                    additionalFacets = emptyList(),
                    agency = oui.agency,
                    label = oui.agency.friendlyLabel,
                    propClass = "GROUND_DIRECT",
                    uncertaintyRadiusM = rawEstDistM.toDouble(),
                    isDerived = false,
                    localSightings = max(oui.sightings, tri.sampleCount),
                    ssidHint = oui.ssid,
                    isFlagged = isFlaggedOui,
                    obsTimestampMs = oui.lastSeen
                )
            }
        }

        // 2c-bis. Non-alerting Tier B investigative MAC candidates (`investigative_watchlists.mac_prefixes`):
        // Never emit Monitor/Map alerts on their own; feed CoMovingTpmsTracker and queue as TIER_B observations.
        for (snap in localDevices) {
            if (snap.remoteId != null || KokohoSignatures.isTacticalOrRemoteIdDrone(snap.mac, snap.name, snap.vendor, false)) continue
            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            val groupKey = KokohoSignatures.routerRadioGroupKey(normMac)
            if (groupKey in emittedOuiGroupKeys) continue
            if (!KokohoSignatures.isTierBInvestigativeCandidate(context, normMac, snap.name, snap.vendor, snap.advert)) continue
            val facetKey = "hw:mac:$groupKey"
            val tierBUuid = deterministicUuidV5(facetKey)
            val (sLat, sLon) = snapshotObsCoords(snap)
            CoMovingTpmsTracker.recordEmitterSample(
                emitterKey = tierBUuid,
                timestampMs = snap.lastSeen,
                signalDb = snap.rssi.toDouble(),
                obsLat = sLat,
                obsLon = sLon
            )
            if (sLat != null && sLon != null) {
                val invPrefix = KokohoSignatures.matchInvestigativeMac(context, normMac, snap.name)
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = sLat,
                    rawLon = sLon,
                    signalDb = snap.rssi.toDouble(),
                    modality = "WIFI_BLE_TIER_B",
                    additionalFacets = emptyList(),
                    agency = KokohoAgency.DHS_INTEROP,
                    label = invPrefix?.vendor ?: "Investigative Candidate",
                    propClass = "GROUND_DIRECT",
                    uncertaintyRadiusM = estimateRssiDistanceMeters(snap.rssi).toDouble(),
                    isDerived = false,
                    localSightings = snap.sightings.coerceAtLeast(1),
                    ssidHint = snap.name,
                    isFlagged = false,
                    obsTimestampMs = snap.lastSeen,
                    tier = KokohoSignatures.WatchlistTier.TIER_B
                )
            }
        }

        // 2d. Deterministic Companion Kit Evaluation (`hw:kit:bwc:*`, `hw:kit:printer:*`, `hw:kit:radio:*`):
        //     - Suppresses fixed `[ESS]` room/WPA-TKIP/long-uptime infrastructure APs (e.g. "101" room APs).
        //     - Matches raw BLE Company IDs (`0x034D` Axon/TASER, `0x04EC` Motorola, `0x01F1` Zebra) & 16-bit Service UUIDs.
        //     - Strict deterministic entity correlation: merges into a router entity ONLY on exact MAC, exact non-generic SSID,
        //       or matching adjusted 802.11 TSF boot epoch (`|ΔupSince| <= 2000ms`): never speculative spatial assumptions!
        data class KitEntry(
            val capLabel: String,
            val isBodycam: Boolean,
            val isPrinter: Boolean,
            val facetPrefix: String,
            val badge: EvidenceBadge,
            val lastSeen: Long,
            val normMac: String?,
            val obsLat: Double?,
            val obsLon: Double?,
            val snap: DeviceRegistry.Snapshot?
        )
        val kitEntries = mutableListOf<KitEntry>()
        for (snap in localDevices) {
            if (snap.rssi < -91 && snap.bestRssi < -91) continue
            if (KokohoSignatures.isFixedRoomOrBuildingWifiAp(snap)) continue
            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            if (KokohoSignatures.isAxonNetworksConsumerRouter(normMac, snap.name, snap.vendor)) continue
            val bleKind = KokohoSignatures.classifyBleTacticalPayload(snap.advert)
            if (bleKind == null && !KokohoSignatures.isCompanionKitDevice(normMac, snap.name, snap.vendor, snap.advert)) continue
            val isPrinter = bleKind == KokohoSignatures.BleTacticalKind.ZEBRA_PRINTER ||
                KokohoSignatures.isZebraCitationPrinter(normMac, snap.name, snap.vendor)
            val isPortableRadio = bleKind == KokohoSignatures.BleTacticalKind.MOTOROLA_RADIO ||
                (!isPrinter && Regex("(?i)\\b(MOTOROLA\\s+SOLUTIONS|APX|XL-?200P|XL-?185P)\\b").containsMatchIn("${snap.name.orEmpty()} ${snap.vendor.orEmpty()}"))
            val isPatrolMdt = !isPrinter && !isPortableRadio &&
                KokohoSignatures.isPatrolMdtDevice(normMac, snap.name, snap.vendor)
            val isBodycam = !isPrinter && !isPortableRadio && !isPatrolMdt && (
                bleKind == KokohoSignatures.BleTacticalKind.AXON_BODYCAM ||
                    normMac.startsWith("00:25:DF") ||
                    normMac.startsWith("D8:1F:65") ||
                    normMac.startsWith("D8:96:E0") ||
                    normMac.startsWith("FC:01:9E") ||
                    normMac.startsWith("00:1D:96") ||
                    normMac.startsWith("00:23:BD") ||
                    normMac.startsWith("48:46:8D") ||
                    normMac.startsWith("00:09:BC") ||
                    normMac.startsWith("00:16:ED") ||
                    (snap.name ?: "").contains("BWC", ignoreCase = true) ||
                    (snap.name ?: "").contains("BodyWorn", ignoreCase = true) ||
                    Regex("\\b(axon|taser)\\b(?!\\s+networks)", RegexOption.IGNORE_CASE).containsMatchIn("${snap.name.orEmpty()} ${snap.vendor.orEmpty()}")
                )
            val facetPrefix = when {
                isBodycam -> "hw:kit:bwc"
                isPrinter -> "hw:kit:printer"
                isPortableRadio -> "hw:kit:radio"
                isPatrolMdt -> "hw:kit:mdt"
                else -> "hw:kit:acc"
            }
            val capLabel = when {
                isBodycam -> "Bodycam"
                isPrinter -> "Citation Printer"
                isPortableRadio -> "Portable Radio"
                isPatrolMdt -> "Patrol MDT"
                else -> "Tactical Kit"
            }
            val kindTag = when {
                isBodycam -> "COMPANION_BODY_CAM"
                isPrinter -> "COMPANION_PRINTER"
                isPortableRadio -> "COMPANION_RADIO"
                isPatrolMdt -> "COMPANION_MDT"
                else -> "COMPANION_KIT"
            }
            val defaultName = bleKind?.defaultSummary ?: capLabel
            val (kLat, kLon) = snapshotObsCoords(snap)
            val badge = EvidenceBadge(
                kind = kindTag,
                facetKey = "$facetPrefix:$normMac",
                detail = "${snap.name ?: snap.vendor ?: defaultName} ($normMac · ${snap.rssi} dBm)",
                weight = 0.76,
                lastSeenMs = snap.lastSeen
            )
            kitEntries += KitEntry(
                capLabel = capLabel,
                isBodycam = isBodycam,
                isPrinter = isPrinter,
                facetPrefix = facetPrefix,
                badge = badge,
                lastSeen = snap.lastSeen,
                normMac = normMac,
                obsLat = kLat,
                obsLon = kLon,
                snap = snap
            )
        }
        val cellAnom = org.tucsoncommunity.kokoho.service.CellMonitor.lastAnomaly
        if (cellAnom != null && nowMs - cellAnom.first <= KIT_FUSION_WINDOW_MS) {
            kitEntries += KitEntry(
                capLabel = "Cell Anomaly",
                isBodycam = false,
                isPrinter = false,
                facetPrefix = "cell:anom",
                badge = EvidenceBadge(
                    kind = "CELL_ANOMALY",
                    facetKey = "cell:anom:${cellAnom.second.key}",
                    detail = "IMSI/Cell Anomaly: ${cellAnom.second.title} (${cellAnom.second.confidence}%)",
                    weight = 0.75,
                    lastSeenMs = cellAnom.first
                ),
                lastSeen = cellAnom.first,
                normMac = null,
                obsLat = userLoc?.latitude,
                obsLon = userLoc?.longitude,
                snap = null
            )
        }
        if (kitEntries.isNotEmpty()) {
            val attachedKitMacs = mutableSetOf<String>()
            val kitsByVehicleUuid = linkedMapOf<String, MutableList<KitEntry>>()
            for (entry in kitEntries) {
                val entryMac = entry.normMac
                val entryGroup = entryMac?.let { KokohoSignatures.routerRadioGroupKey(it) }
                val entrySsid = entry.snap?.name?.trim()?.takeIf { it.isNotEmpty() && !KokohoSignatures.isLowConfidenceDefaultRouterSsid(it) }
                val entryUpSince = entry.snap?.upSince ?: entry.snap?.advert?.wifi?.upSince
                val isBleCompanion = entry.snap?.source == org.tucsoncommunity.kokoho.detect.Advert.Source.BLE
                val deterministicAnchor = ouiThreatUuids
                    .mapNotNull { evaluated[it] }
                    .firstOrNull { anchor ->
                        val anchorMac = anchor.hardwareIdentifier
                        val anchorGroup = anchorMac?.let { KokohoSignatures.routerRadioGroupKey(it) }
                        val sameHardwareGroup = entryGroup != null && anchorGroup != null && entryGroup == anchorGroup
                        val sameExplicitSsid = entrySsid != null && anchor.recurrenceSummary.contains(entrySsid, ignoreCase = true)
                        val anchorUpSince = anchorGroup?.let { gk ->
                            snapshotsByRouterGroup[gk]?.mapNotNull { it.upSince ?: it.advert?.wifi?.upSince }?.firstOrNull()
                        }
                        val sameAdjustedBootEpoch = entryUpSince != null && anchorUpSince != null && abs(entryUpSince - anchorUpSince) <= 2_000L &&
                            spatiallyCompatible(entry.obsLat, entry.obsLon, anchor.lat, anchor.lon, 35.0)
                        sameHardwareGroup || sameExplicitSsid || sameAdjustedBootEpoch
                    }
                    ?: if (isBleCompanion) {
                        // BLE tactical kits (Axon BWC, Zebra printer, Motorola APX) have no 802.11 TSF field;
                        // attach ONLY when a single unambiguous vehicle router is heard simultaneously (`<= 10s`) at the same spot (`<= 45m` raw obs or `<= 110m` co-moving centroid).
                        val nearbyVehicles = ouiThreatUuids.mapNotNull { evaluated[it] }.filter { anchor ->
                            val anchorGroup = anchor.hardwareIdentifier?.let { KokohoSignatures.routerRadioGroupKey(it) }
                            val anchorSnap = anchorGroup?.let { snapshotsByRouterGroup[it]?.firstOrNull() }
                            val (anchorObsLat, anchorObsLon) = if (anchorSnap != null) snapshotObsCoords(anchorSnap) else (anchor.lat to anchor.lon)
                            abs(entry.lastSeen - anchor.updatedAtMs) <= 10_000L && (
                                spatiallyCompatible(entry.obsLat, entry.obsLon, anchorObsLat, anchorObsLon, 45.0) ||
                                    spatiallyCompatible(entry.obsLat, entry.obsLon, anchor.lat, anchor.lon, 110.0)
                                )
                        }
                        nearbyVehicles.singleOrNull()
                    } else null
                if (deterministicAnchor != null) {
                    kitsByVehicleUuid.getOrPut(deterministicAnchor.uuid) { mutableListOf() }.add(entry)
                }
            }
            for ((vehUuid, concurrentKits) in kitsByVehicleUuid) {
                val anchorUnit = evaluated[vehUuid] ?: continue
                val merged = (anchorUnit.evidenceSources + concurrentKits.map { it.badge }).distinctBy { it.facetKey }
                val newCaps = (anchorUnit.capabilities + concurrentKits.map { it.capLabel }).distinct()
                val bumpedClass = min(98, max(anchorUnit.classificationConfidence + 10, 88))
                val updated = anchorUnit.copy(
                    classificationConfidence = bumpedClass,
                    overallConfidence = ((bumpedClass + anchorUnit.spatialConfidence) / 2.0).roundToInt().coerceIn(1, 99),
                    evidenceSources = merged,
                    capabilities = newCaps
                )
                evaluated[anchorUnit.uuid] = updated
                localThreatCache[anchorUnit.uuid] = updated
                for (kit in concurrentKits) {
                    val kMac = kit.normMac ?: continue
                    attachedKitMacs += kMac
                    val staleKitUuid = deterministicUuidV5("${kit.facetPrefix}:$kMac")
                    if (staleKitUuid != anchorUnit.uuid) {
                        evaluated.remove(staleKitUuid)
                        localThreatCache.remove(staleKitUuid)
                        KokohoEntityLedger.remove(context, staleKitUuid)
                    }
                }
                anchorUnit.hardwareIdentifier?.let { macId ->
                    val anchorFacet = "hw:mac:$macId"
                    for (idx in localRawObservations.indices) {
                        if (localRawObservations[idx].facetKey == anchorFacet && !localRawObservations[idx].isFlagged) {
                            localRawObservations[idx] = localRawObservations[idx].copy(isFlagged = true)
                        }
                    }
                    KokohoSignatures.recordLocalMacCorroboration(
                        context = context,
                        mac = macId,
                        agency = updated.agency,
                        label = updated.recurrenceSummary,
                        isCorroborated = true,
                        capabilities = newCaps,
                        nowMs = nowMs
                    )
                }
            }

            fun resolvePrimaryAgencyPresence(refTsMs: Long, refLat: Double?, refLon: Double?): KokohoAgency? {
                val weights = mutableMapOf<KokohoAgency, Double>()
                for (t in evaluated.values) {
                    if (t.isAircraft || t.isDrone || t.agency == KokohoAgency.DRONE) continue
                    if (abs(t.updatedAtMs - refTsMs) > 5 * 60_000L) continue
                    if (!spatiallyCompatible(refLat, refLon, t.lat, t.lon, 500.0)) continue
                    val w = if (t.agency == KokohoAgency.DHS_INTEROP) 0.35 else (t.overallConfidence / 100.0).coerceIn(0.4, 1.0)
                    weights[t.agency] = (weights[t.agency] ?: 0.0) + w
                }
                for (ref in explicitCatalogRfByTime) {
                    if (abs(ref.ts - refTsMs) <= 5 * 60_000L && spatiallyCompatible(refLat, refLon, ref.lat, ref.lon, 500.0)) {
                        val w = if (ref.agency == KokohoAgency.DHS_INTEROP) 0.35 else 0.85
                        weights[ref.agency] = (weights[ref.agency] ?: 0.0) + w
                    }
                }
                val specific = weights.filterKeys { it != KokohoAgency.DHS_INTEROP }
                if (specific.isNotEmpty()) {
                    return specific.entries
                        .sortedWith(compareByDescending<Map.Entry<KokohoAgency, Double>> { it.value }.thenBy { it.key.priorityRank })
                        .first()
                        .key
                }
                return if (KokohoAgency.DHS_INTEROP in weights) KokohoAgency.DHS_INTEROP else null
            }

            // Emit standalone officer kits (`hw:kit:*` is 1:1 per bodycam; two bodycams NEVER merge into one entity!)
            val standaloneBodycamUuids = mutableListOf<String>()
            val unattachedKits = kitEntries.filter { entry ->
                val kitMac = entry.normMac ?: return@filter false
                val kitGroup = KokohoSignatures.routerRadioGroupKey(kitMac)
                kitMac !in attachedKitMacs && kitGroup !in emittedOuiGroupKeys
            }.sortedByDescending { it.isBodycam }

            for (entry in unattachedKits) {
                val kitMac = entry.normMac ?: continue
                val kitSnap = entry.snap ?: continue
                if (entry.isPrinter) {
                    // Unnamed Wi-Fi Zebra OUI devices (`C8:1C:FE`, `94:FB:29`, etc.) with no explicit printer/DIRECT SSID
                    // are retail/warehouse barcode terminals, never standalone patrol units.
                    if (kitSnap.source == org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI &&
                        (kitSnap.name.isNullOrBlank() || (!kitSnap.name.startsWith("DIRECT-", ignoreCase = true) && !KokohoSignatures.isCompanionKitDevice("00:00:00:00:00:00", kitSnap.name, null)))
                    ) {
                        continue
                    }
                }
                val nameUp = (kitSnap.name ?: "").uppercase()
                val isAgnostic = KokohoSignatures.isAxonOrAgencyAgnosticAccessory(kitMac, kitSnap.name, kitSnap.vendor)
                val primaryPresence = resolvePrimaryAgencyPresence(kitSnap.lastSeen, entry.obsLat, entry.obsLon)
                val kitAgency = when {
                    nameUp.contains("SHERIFF") || nameUp.contains("PCSO") || nameUp.contains("PCSD") -> KokohoAgency.SHERIFF
                    nameUp.contains("TPD") || nameUp.contains("POLICE") -> KokohoAgency.POLICE
                    primaryPresence != null && primaryPresence in enabled -> primaryPresence
                    entry.facetPrefix == "hw:kit:mdt" && KokohoAgency.POLICE in enabled -> KokohoAgency.POLICE
                    entry.facetPrefix == "hw:kit:mdt" && KokohoAgency.SHERIFF in enabled -> KokohoAgency.SHERIFF
                    isAgnostic && KokohoAgency.POLICE in enabled && KokohoAgency.DHS_INTEROP !in KokohoSignatures.getEnabledAgencies(context) -> KokohoAgency.POLICE
                    isAgnostic && KokohoAgency.DHS_INTEROP in enabled -> KokohoAgency.DHS_INTEROP
                    KokohoAgency.POLICE in enabled -> KokohoAgency.POLICE
                    KokohoAgency.SHERIFF in enabled -> KokohoAgency.SHERIFF
                    else -> continue
                }
                if (kitAgency !in enabled) continue
                val kitFacetKey = "${entry.facetPrefix}:$kitMac"
                val kitUuid = deterministicUuidV5(kitFacetKey)
                val kitEstDistM = estimateRssiDistanceMeters(kitSnap.rssi)
                val obsLat = entry.obsLat ?: kitSnap.place?.lat
                val obsLon = entry.obsLon ?: kitSnap.place?.lon
                val rawKitTri = recordAndTriangulate(
                    uuid = kitUuid,
                    gpsLat = obsLat,
                    gpsLon = obsLon,
                    signalDb = kitSnap.rssi,
                    estDistanceM = kitEstDistM,
                    nowMs = kitSnap.lastSeen,
                    decayMs = triDecayMs,
                    userLat = userLoc?.latitude,
                    userLon = userLoc?.longitude,
                    evalNowMs = nowMs
                )
                val tri = when {
                    kitSnap.broadcast != null -> {
                        val bDist = if (userLoc != null) {
                            haversineMeters(userLoc.latitude, userLoc.longitude, kitSnap.broadcast.lat, kitSnap.broadcast.lon).roundToInt().coerceAtLeast(10)
                        } else rawKitTri.estimatedDistanceM
                        rawKitTri.copy(
                            centerLat = kitSnap.broadcast.lat,
                            centerLon = kitSnap.broadcast.lon,
                            estimatedDistanceM = bDist,
                            uncertaintyRadiusM = 15,
                            spatialConfidence = 96,
                            isApproximatedPosition = true,
                            isPinpointed = true
                        )
                    }
                    kitSnap.located != null && (!rawKitTri.isApproximatedPosition || kitSnap.located.radiusM <= rawKitTri.uncertaintyRadiusM) -> {
                        val fitRadM = kitSnap.located.radiusM.roundToInt().coerceAtLeast(12)
                        val fitDist = if (userLoc != null) {
                            haversineMeters(userLoc.latitude, userLoc.longitude, kitSnap.located.lat, kitSnap.located.lon).roundToInt()
                        } else rawKitTri.estimatedDistanceM
                        val fitConf = max(rawKitTri.spatialConfidence, (90 - kitSnap.located.radiusM * 0.35).roundToInt().coerceIn(68, 92))
                        rawKitTri.copy(
                            centerLat = kitSnap.located.lat,
                            centerLon = kitSnap.located.lon,
                            estimatedDistanceM = fitDist,
                            uncertaintyRadiusM = fitRadM,
                            spatialConfidence = fitConf,
                            isApproximatedPosition = true,
                            isPinpointed = fitRadM <= PINPOINT_POINT_RADIUS_M
                        )
                    }
                    else -> rawKitTri
                }
                val isLiveKit = nowMs - kitSnap.lastSeen <= 15_000L
                val posture = when {
                    tri.estimatedDistanceM <= 45 || (isLiveKit && tri.estimatedDistanceM <= 90 && kitSnap.rssi >= -62) -> PostureColor.RED
                    (isLiveKit && tri.estimatedDistanceM <= 200 && kitSnap.rssi >= -74) || (isLiveKit && tri.isClosing && tri.estimatedDistanceM <= 250) -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }
                val classConf = 82
                val spatConf = tri.spatialConfidence
                val kitThreat = EvaluatedThreat(
                    uuid = kitUuid,
                    trackUuid = kitUuid,
                    agency = kitAgency,
                    label = kitAgency.friendlyLabel,
                    posture = posture,
                    overallConfidence = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99),
                    classificationConfidence = classConf,
                    spatialConfidence = spatConf,
                    estimatedDistanceM = tri.estimatedDistanceM,
                    uncertaintyRadiusM = tri.uncertaintyRadiusM,
                    bearingDeg = null,
                    closingVelocityMps = null,
                    recurrenceSummary = kitSnap.name ?: kitSnap.vendor ?: "${kitAgency.friendlyLabel} ${entry.capLabel}",
                    evidenceSources = listOf(entry.badge),
                    updatedAtMs = kitSnap.lastSeen,
                    lat = tri.centerLat,
                    lon = tri.centerLon,
                    isApproximatedPosition = tri.isApproximatedPosition,
                    isPinpointed = tri.isPinpointed,
                    hardwareIdentifier = kitMac,
                    sightingCount = max(kitSnap.sightings, tri.sampleCount),
                    capabilities = listOf(entry.capLabel)
                )
                upsertAndFuseThreat(evaluated, kitThreat)
                CoMovingTpmsTracker.recordEmitterSample(
                    emitterKey = kitUuid,
                    timestampMs = kitSnap.lastSeen,
                    signalDb = kitSnap.rssi.toDouble(),
                    obsLat = obsLat,
                    obsLon = obsLon
                )
                if (entry.isBodycam) standaloneBodycamUuids += kitUuid
                if (obsLat != null && obsLon != null) {
                    localRawObservations += RawSensorReportCandidate(
                        facetKey = kitFacetKey,
                        rawLat = obsLat,
                        rawLon = obsLon,
                        signalDb = kitSnap.rssi.toDouble(),
                        modality = entry.badge.kind,
                        additionalFacets = emptyList(),
                        agency = kitAgency,
                        label = kitAgency.friendlyLabel,
                        propClass = "GROUND_DIRECT",
                        uncertaintyRadiusM = kitEstDistM.toDouble(),
                        isDerived = false,
                        localSightings = max(kitSnap.sightings, tri.sampleCount),
                        ssidHint = kitSnap.name,
                        obsTimestampMs = kitSnap.lastSeen
                    )
                }
            }
        }
        // Attach nearby drone operator GPS facets within 15s & <= 250m onto closest ground vehicle unit
        val nearbyDroneOps = evaluated.values.filter { it.isDrone && it.operatorLat != null && it.operatorLon != null }
        for (droneUnit in nearbyDroneOps) {
            val opBadge = droneUnit.evidenceSources.firstOrNull { it.facetKey.startsWith("uas:op:", ignoreCase = true) } ?: continue
            val groundPeer = ouiThreatUuids
                .mapNotNull { evaluated[it] }
                .filter {
                    abs(it.updatedAtMs - droneUnit.updatedAtMs) <= KIT_FUSION_WINDOW_MS &&
                        spatiallyCompatible(droneUnit.operatorLat, droneUnit.operatorLon, it.lat, it.lon, 250.0)
                }
                .minByOrNull { it.effectiveProximityM } ?: continue
            val updated = groundPeer.copy(
                evidenceSources = (groundPeer.evidenceSources + opBadge).distinctBy { it.facetKey },
                capabilities = (groundPeer.capabilities + "Drone Operator").distinct()
            )
            evaluated[groundPeer.uuid] = updated
            localThreatCache[groundPeer.uuid] = updated
        }

        val pendingInteropBadges = mutableListOf<EvidenceBadge>()
        val consumedRfUnitUuids = mutableSetOf<String>()
        val primaryRfPresence = run {
            val weights = mutableMapOf<KokohoAgency, Double>()
            for (t in evaluated.values) {
                if (t.isAircraft || t.isDrone || t.agency == KokohoAgency.DRONE || t.agency == KokohoAgency.DHS_INTEROP) continue
                if (userLoc != null && t.lat != null && t.lon != null && haversineMeters(userLoc.latitude, userLoc.longitude, t.lat, t.lon) > 500.0) continue
                weights[t.agency] = (weights[t.agency] ?: 0.0) + (t.overallConfidence / 100.0).coerceIn(0.4, 1.0)
            }
            for (ref in explicitCatalogRfByTime) {
                if (ref.agency != KokohoAgency.DHS_INTEROP &&
                    (userLoc == null || ref.lat == null || ref.lon == null || haversineMeters(userLoc.latitude, userLoc.longitude, ref.lat, ref.lon) <= 500.0)
                ) {
                    weights[ref.agency] = (weights[ref.agency] ?: 0.0) + 0.85
                }
            }
            weights.entries
                .sortedWith(compareByDescending<Map.Entry<KokohoAgency, Double>> { it.value }.thenBy { it.key.priorityRank })
                .firstOrNull()
                ?.key
        }

        evaluateLocalRfAndP25(
            context = context,
            nowMs = nowMs,
            mapVisibilityMs = mapVisibilityMs,
            rfWindowMs = rfWindowMs,
            triDecayMs = triDecayMs,
            enabled = enabled,
            primaryRfPresence = primaryRfPresence,
            userLoc = userLoc,
            localRfHits = localRfHits,
            ouiThreatUuids = ouiThreatUuids,
            pendingInteropBadges = pendingInteropBadges,
            consumedRfUnitUuids = consumedRfUnitUuids,
            evaluated = evaluated,
            localRawObservations = localRawObservations
        )

        evaluateLocalTpmsAgentCorroboration(
            context = context,
            nowMs = nowMs,
            triDecayMs = triDecayMs,
            enabled = enabled,
            userLoc = userLoc,
            evaluated = evaluated,
            localRawObservations = localRawObservations
        )

        if (KokohoSignatures.isAdsbEnabled(context)) {
            evaluateAdsbAircraft(
                context = context,
                nowMs = nowMs,
                userLoc = userLoc,
                evaluated = evaluated,
                localRawObservations = localRawObservations
            )
        }

        return finalizeSnapshotAndPublish(
            context = context,
            nowMs = nowMs,
            mapVisibilityMs = mapVisibilityMs,
            monitorWindowMs = monitorWindowMs,
            activeMapWindow = activeMapWindow,
            alarmEnabled = alarmEnabled,
            mapVisible = mapVisible,
            enabled = enabled,
            enabledScopeText = enabledScopeText,
            groundedHexes = groundedHexes,
            userLoc = userLoc,
            evaluated = evaluated,
            localRawObservations = localRawObservations
        )
    }

    private fun evaluateRemoteHubObjects(
        context: Context,
        nowMs: Long,
        mapVisibilityMs: Long,
        enabled: Set<KokohoAgency>,
        groundedHexes: Set<String>,
        userLoc: android.location.Location?,
        evaluated: MutableMap<String, EvaluatedThreat>
    ) {
        for (remote in KokohoHubClient.activeObjects(nowMs, mapVisibilityMs)) {
            if (KokohoSignatures.isFlockCamera(remote.uuid, remote.classification.label) ||
                remote.classification.evidenceSources.any { KokohoSignatures.isFlockCamera(it.facetKey, it.detail) }
            ) {
                continue
            }
            val isAir = !remote.classification.category.equals("DRONE", ignoreCase = true) &&
                remote.classification.evidenceSources.none { it.facetKey.startsWith("uas:", ignoreCase = true) } && (
                    remote.location.propagationClass.equals("AIRBORNE", ignoreCase = true) ||
                        remote.classification.evidenceSources.any {
                            it.kind.contains("ADSB", ignoreCase = true) ||
                                it.facetKey.startsWith("adsb:", ignoreCase = true) ||
                                it.facetKey.startsWith("ICAO:", ignoreCase = true)
                        }
                    )
            if (isAir && KokohoSignatures.isAnomalousUnattributedAirRecord(
                    primaryKey = remote.classification.evidenceSources.firstOrNull()?.facetKey ?: "adsb:",
                    category = remote.classification.category,
                    label = remote.classification.label,
                    facets = remote.classification.evidenceSources.map { it.facetKey },
                    avgVelocityMps = remote.location.velocityMps
                )
            ) {
                continue
            }
            val remoteHex = remote.classification.evidenceSources
                .firstOrNull { it.facetKey.startsWith("adsb:icao:", ignoreCase = true) || it.facetKey.startsWith("ICAO:", ignoreCase = true) }
                ?.facetKey?.substringAfterLast(':')?.uppercase()
            if (isAir) {
                if (nowMs - remote.updatedAtMs > LIVE_AIRCRAFT_MAX_AGE_MS) continue
                if (remoteHex != null && remoteHex in groundedHexes) continue
            }
            val agency = KokohoAgency.fromCode(remote.classification.category) ?: continue
            if (isAir) {
                if (!KokohoSignatures.isAdsbEnabled(context)) continue
                val airEnabled = KokohoSignatures.getEnabledAircraftAgencies(context)
                val allowed = if (agency.isDhs) airEnabled.any { it.isDhs } else agency in airEnabled
                if (!allowed) continue
            } else {
                if (agency !in enabled) continue
            }

            if (remote.location.propagationClass.equals("REPEATER_INFRASTRUCTURE", ignoreCase = true)) {
                continue
            }

            val uncertM = remote.location.uncertaintyRadiusM.roundToInt().coerceAtLeast(15)
            val hasUserLoc = userLoc != null
            val centerDistM = if (userLoc != null) {
                haversineMeters(userLoc.latitude, userLoc.longitude, remote.location.lat, remote.location.lon).roundToInt()
            } else {
                9_999
            }
            val edgeDistM = if (hasUserLoc) max(0, centerDistM - uncertM) else max(0, 9_999 - uncertM)

            val bearingFromUser = if (userLoc != null) {
                bearingDeg(userLoc.latitude, userLoc.longitude, remote.location.lat, remote.location.lon).roundToInt()
            } else {
                remote.location.bearingDeg?.roundToInt()
            }

            val headingTowardUser = isHeadingTowardUser(
                userLat = userLoc?.latitude,
                userLon = userLoc?.longitude,
                objLat = remote.location.lat,
                objLon = remote.location.lon,
                objBearingDeg = remote.location.bearingDeg,
                objVelocityMps = remote.location.velocityMps
            )

            val badges = remote.classification.evidenceSources.map {
                EvidenceBadge(
                    kind = it.kind,
                    facetKey = it.facetKey,
                    detail = it.detail,
                    weight = it.weight,
                    lastSeenMs = it.lastSeenMs
                )
            }
            val remoteIsCircling = isAir && (
                remote.classification.label.contains("circling", ignoreCase = true) ||
                    remote.classification.label.contains("orbit", ignoreCase = true) ||
                    remote.classification.recurrenceSummary.contains("circling", ignoreCase = true) ||
                    remote.classification.recurrenceSummary.contains("orbit", ignoreCase = true) ||
                    badges.any {
                        it.detail.contains("circling", ignoreCase = true) ||
                            it.facetKey.contains("ORBIT", ignoreCase = true)
                    }
                )

            val posture = if (isAir) {
                when {
                    !hasUserLoc -> PostureColor.YELLOW
                    centerDistM <= 805 || (remoteIsCircling && centerDistM <= AIRCRAFT_NEARBY_DIRECT_MAX_M) -> PostureColor.RED
                    centerDistM <= AIRCRAFT_NEARBY_DIRECT_MAX_M || (remoteIsCircling && centerDistM <= 3_219) -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }
            } else {
                when {
                    !hasUserLoc -> PostureColor.YELLOW
                    centerDistM <= 300 -> PostureColor.RED
                    headingTowardUser || centerDistM <= 850 -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }
            }

            var remoteOpLat: Double? = null
            var remoteOpLon: Double? = null
            var remoteIsDrone = agency == KokohoAgency.DRONE
            var remoteAltFt: Int? = remote.location.altitudeFtAgl ?: remote.location.altitudeFt
            var remoteFacetHdgDeg: Double? = null
            for (b in badges) {
                val fk = b.facetKey.trim()
                if (fk.startsWith("uas:mac:", ignoreCase = true)) remoteIsDrone = true
                if (fk.startsWith("uas:op:", ignoreCase = true)) {
                    remoteIsDrone = true
                    val parts = fk.substringAfter("uas:op:", "").split(',')
                    if (parts.size == 2) {
                        val olat = parts[0].trim().toDoubleOrNull()
                        val olon = parts[1].trim().toDoubleOrNull()
                        if (olat != null && olon != null && olat in -90.0..90.0 && olon in -180.0..180.0) {
                            remoteOpLat = olat
                            remoteOpLon = olon
                        }
                    }
                }
                if (remoteAltFt == null && fk.startsWith("air:alt_ft_msl:", ignoreCase = true)) {
                    remoteAltFt = fk.substringAfterLast(':').trim().toIntOrNull()
                }
                if (remoteAltFt == null && fk.startsWith("uas:alt_ft_agl:", ignoreCase = true)) {
                    remoteIsDrone = true
                    remoteAltFt = fk.substringAfterLast(':').trim().toIntOrNull()
                }
                if (remoteFacetHdgDeg == null && fk.startsWith("air:hdg:", ignoreCase = true)) {
                    remoteFacetHdgDeg = fk.substringAfterLast(':').trim().toDoubleOrNull()
                }
            }

            val isStandaloneOp = !isAir && isStandaloneDroneOperator(badges, badges.firstOrNull()?.facetKey)
            val hasRemoteIdDroneEvidence = remoteIsDrone && !isStandaloneOp && (
                remoteAltFt != null ||
                    remoteOpLat != null ||
                    badges.any {
                        it.facetKey.startsWith("uas:alt_ft_agl:", ignoreCase = true) ||
                            it.facetKey.startsWith("uas:op:", ignoreCase = true)
                    }
                )
            val effectiveUncertM = if (isStandaloneOp) 15 else uncertM
            val effectiveIsPinpointed = when {
                isStandaloneOp -> true
                isAir -> effectiveUncertM <= 65
                hasRemoteIdDroneEvidence && effectiveUncertM <= 40 -> true
                else -> effectiveUncertM <= PINPOINT_POINT_RADIUS_M
            }
            val cleanedRecurrence = if (remoteIsDrone || isStandaloneOp) {
                sanitizeDroneOperatorSuffix(remote.classification.recurrenceSummary)
            } else {
                remote.classification.recurrenceSummary
            }
            val cleanedRemoteLabel = if (remoteIsDrone || isStandaloneOp) {
                sanitizeDroneOperatorSuffix(remote.classification.label)
            } else {
                remote.classification.label
            }

            val classConf = (remote.confidence.classification * 100).roundToInt().coerceIn(1, 99)
            val spatConf = (remote.confidence.spatial * 100).roundToInt().coerceIn(1, 99)
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

            val remoteSightings = max(remote.sightingCount, remote.corroborationCount).coerceAtLeast(1)
            val isRemoteDerived = isStandaloneOp ||
                isAir ||
                remote.corroborationCount >= 2 ||
                remote.sightingCount >= 2 ||
                effectiveUncertM <= WIDE_CIRCLE_JITTER_MIN_M

            val remoteHeli = isAir && KokohoSignatures.isHelicopterAirframe(
                null,
                remote.classification.category,
                "$cleanedRemoteLabel ${badges.joinToString(" ") { it.detail }}",
                agency = agency,
                hex = remoteHex
            )
            evaluated[remote.uuid] = EvaluatedThreat(
                uuid = remote.uuid,
                trackUuid = remote.trackUuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = centerDistM,
                uncertaintyRadiusM = effectiveUncertM,
                bearingDeg = bearingFromUser,
                closingVelocityMps = remote.location.velocityMps,
                recurrenceSummary = cleanedRecurrence,
                evidenceSources = badges,
                updatedAtMs = remote.updatedAtMs,
                lat = remote.location.lat,
                lon = remote.location.lon,
                isApproximatedPosition = isRemoteDerived,
                isPinpointed = effectiveIsPinpointed,
                isAircraft = isAir,
                isHelicopter = remoteHeli,
                aircraftTrackDeg = remote.location.bearingDeg ?: remoteFacetHdgDeg,
                aircraftHex = remoteHex,
                isCircling = remoteIsCircling,
                isDrone = remoteIsDrone,
                altitudeFtAgl = remoteAltFt,
                operatorLat = if (isStandaloneOp) (remoteOpLat ?: remote.location.lat) else remoteOpLat,
                operatorLon = if (isStandaloneOp) (remoteOpLon ?: remote.location.lon) else remoteOpLon,
                hardwareIdentifier = badges.firstOrNull()?.facetKey,
                sightingCount = remoteSightings,
                capabilities = KokohoSignatures.inferCapabilitiesFromEvidence(
                    badges.map { it.facetKey },
                    badges.map { it.detail } + cleanedRemoteLabel
                )
            )
            if (remoteIsDrone && !isStandaloneOp && effectiveIsPinpointed) {
                org.tucsoncommunity.kokoho.online.PoliceAircraft.recordPinpointDroneFix(
                    entityUuid = remote.uuid,
                    agencyId = agency.id,
                    label = cleanedRemoteLabel.ifBlank { agency.friendlyLabel },
                    lat = remote.location.lat,
                    lon = remote.location.lon,
                    nowMs = remote.updatedAtMs
                )
            }
        }
    }

    private fun evaluateLocalDrones(
        userLoc: android.location.Location?,
        localDevices: List<DeviceRegistry.Snapshot>,
        evaluated: MutableMap<String, EvaluatedThreat>,
        localRawObservations: MutableList<RawSensorReportCandidate>
    ) {
        for (snap in localDevices) {
            val rid = snap.remoteId
            val isDrone = KokohoSignatures.isTacticalOrRemoteIdDrone(
                mac = snap.mac,
                ssidOrName = snap.name,
                vendor = snap.vendor,
                hasRemoteId = rid != null
            )
            if (!isDrone) continue

            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            val facetKey = "uas:mac:$normMac"
            val uuid = deterministicUuidV5(facetKey)
            val droneLat = rid?.latitude ?: snap.place?.lat ?: userLoc?.latitude
            val droneLon = rid?.longitude ?: snap.place?.lon ?: userLoc?.longitude
            val hasExactDroneGps = rid?.latitude != null && rid.longitude != null
            val altMeters = rid?.heightM ?: rid?.altitudeGeoM
            val altFtAgl = rid?.heightM?.let { (it * 3.28084).roundToInt().coerceAtLeast(0) }
                ?: rid?.altitudeGeoM?.let { (it * 3.28084).roundToInt().coerceAtLeast(0) }
            val altFtMsl = rid?.altitudeGeoM?.let { (it * 3.28084).roundToInt() }
            if (hasExactDroneGps) {
                PoliceAircraft.recordDroneFlightFix(
                    droneKey = normMac,
                    lat = droneLat!!,
                    lon = droneLon!!,
                    altM = altMeters,
                    nowMs = snap.lastSeen,
                    entityUuid = uuid
                )
            }

            val distM = if (hasExactDroneGps && userLoc != null) {
                haversineMeters(userLoc.latitude, userLoc.longitude, droneLat!!, droneLon!!).roundToInt()
            } else {
                estimateRssiDistanceMeters(snap.rssi)
            }
            val uncertM = if (hasExactDroneGps) 20 else distM

            val posture = when {
                distM <= 350 || snap.rssi >= -68 -> PostureColor.RED
                distM <= 900 || snap.rssi >= -78 -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val uasDetail = buildString {
                append(snap.vendor ?: "Tactical sUAS")
                if (!rid?.uasId.isNullOrBlank()) append(" · ID ${rid?.uasId}")
                if (altFtAgl != null) append(" · $altFtAgl ft AGL")
                append(" (${snap.rssi} dBm)")
            }

            val badges = mutableListOf(
                EvidenceBadge(
                    kind = if (rid != null) "REMOTE_ID_UAS" else "TACTICAL_DRONE_OUI",
                    facetKey = facetKey,
                    detail = uasDetail,
                    weight = 0.92,
                    lastSeenMs = snap.lastSeen
                )
            )
            if (rid?.operatorLatitude != null && rid.operatorLongitude != null) {
                val opLatStr = "%.5f".format(Locale.US, rid.operatorLatitude)
                val opLonStr = "%.5f".format(Locale.US, rid.operatorLongitude)
                badges += EvidenceBadge(
                    kind = "REMOTE_ID_OP",
                    facetKey = "uas:op:$opLatStr,$opLonStr",
                    detail = "Pilot GPS: $opLatStr, $opLonStr",
                    weight = 0.80,
                    lastSeenMs = snap.lastSeen
                )
            }

            val threat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = KokohoAgency.DRONE,
                label = KokohoAgency.DRONE.friendlyLabel,
                posture = posture,
                overallConfidence = 94,
                classificationConfidence = 95,
                spatialConfidence = if (hasExactDroneGps) 96 else 72,
                estimatedDistanceM = distM,
                uncertaintyRadiusM = uncertM,
                bearingDeg = rid?.directionDeg,
                closingVelocityMps = rid?.speedMs,
                recurrenceSummary = if (altFtAgl != null) "Airborne at $altFtAgl ft AGL" else "Tactical Drone Beacon",
                evidenceSources = badges,
                updatedAtMs = snap.lastSeen,
                lat = droneLat,
                lon = droneLon,
                isApproximatedPosition = hasExactDroneGps,
                isPinpointed = hasExactDroneGps,
                isDrone = true,
                altitudeFtAgl = altFtAgl,
                operatorLat = rid?.operatorLatitude,
                operatorLon = rid?.operatorLongitude,
                hardwareIdentifier = rid?.uasId?.takeIf { it.isNotBlank() } ?: normMac
            )
            upsertAndFuseThreat(evaluated, threat)
            if (hasExactDroneGps && droneLat != null && droneLon != null) {
                org.tucsoncommunity.kokoho.online.PoliceAircraft.recordPinpointDroneFix(
                    entityUuid = uuid,
                    agencyId = KokohoAgency.DRONE.id,
                    label = threat.friendlyLabel,
                    lat = droneLat,
                    lon = droneLon,
                    nowMs = snap.lastSeen
                )
            }
            if (droneLat != null && droneLon != null) {
                val droneAltFacets = buildList {
                    altFtAgl?.let { add("uas:alt_ft_agl:$it") }
                    altFtMsl?.let { add("air:alt_ft_msl:$it") }
                    rid?.directionDeg?.let { add("uas:hdg:$it") }
                }
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = droneLat,
                    rawLon = droneLon,
                    signalDb = snap.rssi.toDouble(),
                    modality = if (rid != null) "REMOTE_ID" else "TACTICAL_DRONE_OUI",
                    additionalFacets = (badges.drop(1).map { it.facetKey } + droneAltFacets).distinct(),
                    agency = KokohoAgency.DRONE,
                    label = threat.friendlyLabel,
                    propClass = "GROUND_DIRECT",
                    uncertaintyRadiusM = if (hasExactDroneGps) 20.0 else uncertM.toDouble(),
                    isDerived = hasExactDroneGps,
                    localSightings = snap.sightings.coerceAtLeast(1),
                    ssidHint = snap.name,
                    obsTimestampMs = snap.lastSeen
                )
            }
        }
    }

    private fun evaluateLocalRfAndP25(
        context: Context,
        nowMs: Long,
        mapVisibilityMs: Long,
        rfWindowMs: Long,
        triDecayMs: Long,
        enabled: Set<KokohoAgency>,
        primaryRfPresence: KokohoAgency?,
        userLoc: android.location.Location?,
        localRfHits: List<RadioLog.Entry>,
        ouiThreatUuids: List<String>,
        pendingInteropBadges: MutableList<EvidenceBadge>,
        consumedRfUnitUuids: MutableSet<String>,
        evaluated: MutableMap<String, EvaluatedThreat>,
        localRawObservations: MutableList<RawSensorReportCandidate>
    ) {
        val dodTrunkActive = RadioLog.activeDodTrunkChannelCount(nowMs) >= 3

        // 3. Evaluate local RTL-SDR RF Hits: suppress wide-area non-localizing emissions along user travel,
        // corroborate colocated OUI units of the SAME agency (<= 15s & <= 100m, at most 1 RF channel per OUI unit),
        // and anchor standalone RF entities to their initial episode coordinates (zero moving-userLoc grid clones).
        for (rf in localRfHits) {
            val isDodTrunkFreq = dodTrunkActive && rf.freqHz in 406_100_000L..411_000_000L
            val sig = KokohoSignatures.matchFrequency(rf.freqHz, snrDb = rf.peakSnrDb)
                ?: KokohoSignatures.matchPublicSafetyMobileUplink(
                    rf.freqHz,
                    rf.peakSnrDb,
                    enabledAgencies = enabled,
                    primaryPresenceAgency = primaryRfPresence
                )
                ?: (if (!isDodTrunkFreq) KokohoSignatures.matchCloseRangeFederalBand(rf.freqHz, rf.peakSnrDb, enabledAgencies = enabled) else null)
            if (sig == null) {
                val invRf = KokohoSignatures.matchInvestigativeRfRange(context, rf.freqHz)
                if (invRf != null) {
                    val (invLat, invLon) = resolveRfCoords(rf, userLoc, nowMs)
                    val invFacet = "rf:inv:${rf.freqHz}:${invRf.mode}"
                    val invUuid = deterministicUuidV5(invFacet)
                    CoMovingTpmsTracker.recordEmitterSample(
                        emitterKey = invUuid,
                        timestampMs = rf.lastSeen,
                        signalDb = rf.peakSnrDb.toDouble(),
                        obsLat = invLat,
                        obsLon = invLon
                    )
                    if (invLat != null && invLon != null) {
                        localRawObservations += RawSensorReportCandidate(
                            facetKey = invFacet,
                            rawLat = invLat,
                            rawLon = invLon,
                            signalDb = rf.peakSnrDb.toDouble(),
                            modality = "RF_TIER_B",
                            additionalFacets = emptyList(),
                            agency = KokohoAgency.DHS_INTEROP,
                            label = invRf.mode,
                            propClass = "GROUND_DIRECT",
                            uncertaintyRadiusM = estimateRfDistanceMeters(rf.peakSnrDb, "DIRECT_TACTICAL").toDouble(),
                            isDerived = false,
                            localSightings = 1,
                            ssidHint = invRf.mode,
                            isFlagged = false,
                            obsTimestampMs = rf.lastSeen,
                            tier = KokohoSignatures.WatchlistTier.TIER_B
                        )
                    }
                }
                continue
            }
            if (!sig.isAlertableDirectOrMobile) continue

            val (rfObsLat, rfObsLon) = resolveRfCoords(rf, userLoc, nowMs)
            val agency = sig.agencyEnum

            // Along-track wide-area / non-localizing RF rejection:
            // Ground channels heard across >= 600m of observer travel without a sharp close-range SNR peak
            // (>= 38 dB and >= 10 dB contrast) or colocated co-moving fleet router are hilltop repeaters / wide-area carriers.
            if (sig.role != "AIR_ASSET" && rf.travelSpanM >= 600.0) {
                val snrContrast = rf.peakSnrDb - rf.minSnrDb
                val hasSharpClosePeak = rf.peakSnrDb >= 38 && snrContrast >= 10
                val hasColocatedAgencyRouter = ouiThreatUuids
                    .mapNotNull { evaluated[it] }
                    .any { target ->
                        target.agency == agency &&
                            abs(target.updatedAtMs - rf.lastSeen) <= COLOCATION_WINDOW_MS &&
                            spatiallyCompatible(rfObsLat, rfObsLon, target.lat, target.lon, COLOCATION_MAX_DIST_M)
                    }
                if (!hasSharpClosePeak && !hasColocatedAgencyRouter) {
                    RadioLog.recordRepeaterBurst(rf.lastSeen)
                    continue
                }
            }

            val facetKey = "rf:chan:${rf.freqHz}:${sig.nac ?: "ANY"}"
            val digitalSuffix = rf.digital?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val rfBadge = EvidenceBadge(
                kind = "RF_${sig.mode}",
                facetKey = facetKey,
                detail = "${sig.alpha} (${"%.4f".format(Locale.US, rf.freqHz / 1e6)} MHz · ${rf.peakSnrDb} dB SNR$digitalSuffix)",
                weight = if (agency == KokohoAgency.DHS_INTEROP) 0.45 else 0.70,
                lastSeenMs = rf.lastSeen
            )

            if (agency == KokohoAgency.DHS_INTEROP && agency !in enabled) {
                pendingInteropBadges += rfBadge.copy(detail = "${sig.alpha} Interop (${rf.peakSnrDb} dB SNR)")
                continue
            }
            if (agency !in enabled) continue

            // Keep RF detections as distinct RF entities unless deterministically tied by exact P25 RID / user report
            val colocatedUuid: String? = null

            if (colocatedUuid != null) {
                consumedRfUnitUuids += colocatedUuid
                val target = evaluated[colocatedUuid]!!
                val mergedBadges = (listOf(rfBadge) + target.evidenceSources).distinctBy { it.facetKey }
                val mergedCaps = (target.capabilities + KokohoSignatures.inferCapabilitiesFromEvidence(
                    mergedBadges.map { it.facetKey },
                    mergedBadges.map { it.detail } + target.recurrenceSummary
                )).distinct()
                val bumpedPosture = when {
                    (nowMs - rf.lastSeen <= 15_000L) && rf.peakSnrDb >= 38 && target.posture == PostureColor.YELLOW -> PostureColor.ORANGE
                    else -> target.posture
                }
                val updated = target.copy(
                    posture = bumpedPosture,
                    classificationConfidence = min(98, max(target.classificationConfidence + 8, 92)),
                    overallConfidence = ((min(98, max(target.classificationConfidence + 8, 92)) + target.spatialConfidence) / 2.0)
                        .roundToInt().coerceIn(1, 99),
                    evidenceSources = mergedBadges,
                    updatedAtMs = max(target.updatedAtMs, rf.lastSeen),
                    sightingCount = target.sightingCount + 1,
                    capabilities = mergedCaps
                )
                upsertAndFuseThreat(evaluated, updated)
                deduplicateRemoteRfEchoes(evaluated, colocatedUuid, rf.freqHz, rfObsLat, rfObsLon, rf.lastSeen)
                target.hardwareIdentifier?.let { macId ->
                    val targetFacet = "hw:mac:$macId"
                    for (idx in localRawObservations.indices) {
                        if (localRawObservations[idx].facetKey == targetFacet && !localRawObservations[idx].isFlagged) {
                            localRawObservations[idx] = localRawObservations[idx].copy(isFlagged = true)
                        }
                    }
                    KokohoSignatures.recordLocalMacCorroboration(
                        context = context,
                        mac = macId,
                        agency = updated.agency,
                        label = updated.recurrenceSummary,
                        isCorroborated = true,
                        capabilities = mergedCaps,
                        nowMs = nowMs
                    )
                }
                if (rfObsLat != null && rfObsLon != null) {
                    localRawObservations += RawSensorReportCandidate(
                        facetKey = facetKey,
                        rawLat = rfObsLat,
                        rawLon = rfObsLon,
                        signalDb = rf.peakSnrDb.toDouble(),
                        modality = "RF_${sig.mode}",
                        // Never bind shared RF channels into hardware MAC facets on the Hub across space/time!
                        additionalFacets = emptyList(),
                        agency = agency,
                        label = agency.friendlyLabel,
                        propClass = if (sig.role == "AIR_ASSET") "AIRBORNE" else "GROUND_DIRECT",
                        uncertaintyRadiusM = target.uncertaintyRadiusM.toDouble(),
                        isDerived = false,
                        localSightings = updated.sightingCount,
                        ssidHint = sig.alpha,
                        obsTimestampMs = rf.lastSeen
                    )
                }
                continue
            }

            // Standalone RF entity scoped to fixed initial episode coordinates (NEVER moving userLoc, preventing grid-boundary clones!)
            val rfAnchorLat = rf.firstLat ?: rfObsLat
            val rfAnchorLon = rf.firstLon ?: rfObsLon
            val gridSuffix = if (rfAnchorLat != null && rfAnchorLon != null) {
                "@${(rfAnchorLat * 100.0).roundToInt()},${(rfAnchorLon * 100.0).roundToInt()}"
            } else {
                ""
            }
            val uuid = deterministicUuidV5(facetKey + gridSuffix)
            val rawEstDistM = estimateRfDistanceMeters(rf.peakSnrDb, sig.role)

            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = rfObsLat,
                gpsLon = rfObsLon,
                signalDb = rf.peakSnrDb,
                estDistanceM = rawEstDistM,
                nowMs = rf.lastSeen,
                decayMs = triDecayMs,
                userLat = userLoc?.latitude,
                userLon = userLoc?.longitude,
                evalNowMs = nowMs
            )

            val isAirAssetRf = sig.role.equals("AIR_ASSET", ignoreCase = true)
            val isLiveRf = nowMs - rf.lastSeen <= 15_000L
            val isApproaching = !isAirAssetRf && isLiveRf && (rf.trend == RadioLog.Trend.CLOSER || tri.isClosing)
            val effectiveRfDistM = if (isAirAssetRf) max(tri.estimatedDistanceM, max(rawEstDistM, 1500)) else tri.estimatedDistanceM
            val effectiveRfRadiusM = if (isAirAssetRf) max(tri.uncertaintyRadiusM, max(rawEstDistM, 1500)) else tri.uncertaintyRadiusM
            val posture = when {
                !isAirAssetRf && effectiveRfDistM <= 250 -> PostureColor.RED
                isApproaching && effectiveRfDistM <= 600 -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = 88
            val spatConf = if (isAirAssetRf) min(tri.spatialConfidence, 40) else tri.spatialConfidence
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

            val threat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = effectiveRfDistM,
                uncertaintyRadiusM = effectiveRfRadiusM,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "${sig.alpha} (${sig.description})",
                evidenceSources = listOf(rfBadge),
                updatedAtMs = rf.lastSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = !isAirAssetRf && tri.isApproximatedPosition,
                isPinpointed = !isAirAssetRf && tri.isPinpointed,
                hardwareIdentifier = "${sig.alpha} · ${"%.4f".format(Locale.US, rf.freqHz / 1e6)} MHz",
                sightingCount = tri.sampleCount,
                capabilities = KokohoSignatures.inferCapabilitiesFromEvidence(listOf(facetKey), listOf(rfBadge.detail, sig.description))
            )
            upsertAndFuseThreat(evaluated, threat)
            if (!isAirAssetRf) {
                localThreatCache[uuid] = evaluated[uuid] ?: threat
                CoMovingTpmsTracker.recordEmitterSample(
                    emitterKey = uuid,
                    timestampMs = rf.lastSeen,
                    signalDb = rf.peakSnrDb.toDouble(),
                    obsLat = rfObsLat,
                    obsLon = rfObsLon
                )
            }
            deduplicateRemoteRfEchoes(evaluated, uuid, rf.freqHz, rfAnchorLat, rfAnchorLon, rf.lastSeen)
            if (rfObsLat != null && rfObsLon != null) {
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = rfObsLat,
                    rawLon = rfObsLon,
                    signalDb = rf.peakSnrDb.toDouble(),
                    modality = "RF_${sig.mode}",
                    additionalFacets = emptyList(),
                    agency = agency,
                    label = agency.friendlyLabel,
                    propClass = if (sig.role == "AIR_ASSET") "AIRBORNE" else "GROUND_DIRECT",
                    uncertaintyRadiusM = tri.uncertaintyRadiusM.toDouble(),
                    isDerived = tri.isApproximatedPosition,
                    localSightings = tri.sampleCount.coerceAtLeast(1),
                    ssidHint = sig.alpha,
                    obsTimestampMs = rf.lastSeen
                )
            }
        }

        // Fold any concurrent Federal Interop evidence into active specific DHS agency threats at the SAME scene (<= 300m & <= 60s)
        if (pendingInteropBadges.isNotEmpty()) {
            val dhsTargets = evaluated.values.filter {
                it.agency.isDhs &&
                    it.agency != KokohoAgency.DHS_INTEROP &&
                    !it.isAircraft &&
                    it.effectiveProximityM <= 400
            }
            for (target in dhsTargets) {
                val relevantInterop = pendingInteropBadges.filter { abs(target.updatedAtMs - it.lastSeenMs) <= 60_000L }
                if (relevantInterop.isEmpty()) continue
                val mergedBadges = (target.evidenceSources + relevantInterop).distinctBy { it.facetKey }
                val updated = target.copy(
                    classificationConfidence = min(99, target.classificationConfidence + 4),
                    evidenceSources = mergedBadges,
                    capabilities = (target.capabilities + KokohoSignatures.inferCapabilitiesFromEvidence(
                        mergedBadges.map { it.facetKey },
                        mergedBadges.map { it.detail }
                    )).distinct()
                )
                evaluated[target.uuid] = updated
                localThreatCache[target.uuid] = updated
            }
        }

        // 4. Active P25 talkgroups with direct mobile input heard recently (excluding repeater-only trunk traffic)
        for (tgRow in SdrRadio.p25Log.rows()) {
            val tgId = tgRow.tg ?: continue
            val inputHeardRecently = tgRow.inputAt > 0L && (nowMs - tgRow.inputAt <= rfWindowMs)
            if (!inputHeardRecently) continue
            val tgSig = KokohoSignatures.matchTalkgroup(tgId) ?: continue
            val agency = tgSig.agencyEnum
            if (agency !in enabled) continue

            val srcUnit = tgRow.lastSource ?: tgId
            val facetKey = "rf:rid:$srcUnit"
            val uuid = deterministicUuidV5(facetKey)
            val rawEstDistM = estimateRfDistanceMeters(if (tgRow.inputSnr > 0) tgRow.inputSnr else 25, "MOBILE_INPUT")
            val p25ObsLat = userLoc?.takeIf { abs(nowMs - tgRow.inputAt) <= 5_000L }?.latitude
            val p25ObsLon = userLoc?.takeIf { abs(nowMs - tgRow.inputAt) <= 5_000L }?.longitude
            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = p25ObsLat,
                gpsLon = p25ObsLon,
                signalDb = if (tgRow.inputSnr > 0) tgRow.inputSnr else 25,
                estDistanceM = rawEstDistM,
                nowMs = tgRow.inputAt,
                decayMs = triDecayMs,
                userLat = userLoc?.latitude,
                userLon = userLoc?.longitude,
                evalNowMs = nowMs
            )

            val posture = when {
                tri.estimatedDistanceM <= 300 -> PostureColor.RED
                (nowMs - tgRow.inputAt <= 15_000L && tri.isClosing) || tri.estimatedDistanceM <= 800 -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = 92
            val spatConf = max(tri.spatialConfidence, 72)
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

            val threat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = tri.estimatedDistanceM,
                uncertaintyRadiusM = tri.uncertaintyRadiusM,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "${tgSig.label} (RID $srcUnit)",
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "P25_UNIT",
                        facetKey = facetKey,
                        detail = "${agency.friendlyLabel} TG $tgId · RID $srcUnit (${tgRow.inputSnr} dB SNR)",
                        weight = 0.85,
                        lastSeenMs = tgRow.inputAt
                    )
                ),
                updatedAtMs = tgRow.inputAt,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed,
                hardwareIdentifier = "RID $srcUnit (TG $tgId)",
                capabilities = listOf("Tactical RF")
            )
            upsertAndFuseThreat(evaluated, threat)
            if (p25ObsLat != null && p25ObsLon != null) {
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = p25ObsLat,
                    rawLon = p25ObsLon,
                    signalDb = (if (tgRow.inputSnr > 0) tgRow.inputSnr else 25).toDouble(),
                    modality = "P25_UNIT",
                    additionalFacets = listOf("p25:tg:$tgId"),
                    agency = agency,
                    label = agency.friendlyLabel,
                    propClass = "GROUND_DIRECT",
                    uncertaintyRadiusM = tri.uncertaintyRadiusM.toDouble(),
                    isDerived = tri.isApproximatedPosition,
                    localSightings = tri.sampleCount.coerceAtLeast(1),
                    ssidHint = tgSig.label,
                    obsTimestampMs = tgRow.inputAt
                )
            }
        }

        // 4B. Actual hardware GPS coordinates broadcast by P25 radios via Motorola LRRP PDU (`P25Pdu`)
        for (logFix in SdrRadio.p25Log.fixes()) {
            val pos = logFix.position
            if (pos.radio > 0 && pos.lat in -90.0..90.0 && pos.lon in -180.0..180.0 && !(abs(pos.lat) < 0.001 && abs(pos.lon) < 0.001)) {
                val existingFix = lrrpRadioFixes[pos.radio]
                if (existingFix == null || logFix.at >= existingFix.atMs) {
                    lrrpRadioFixes[pos.radio] = LrrpRadioFix(pos, logFix.hz ?: existingFix?.hz, logFix.nac ?: existingFix?.nac, logFix.at)
                }
            }
        }
        val retentionWindowMs = max(mapVisibilityMs, KokohoSignatures.MONITOR_ACTIVE_WINDOW_MS)
        lrrpRadioFixes.entries.removeIf { nowMs - it.value.atMs > retentionWindowMs }
        for ((radioId, fix) in lrrpRadioFixes) {
            if (nowMs - fix.atMs > retentionWindowMs) continue
            val pos = fix.pos
            val tg = SdrRadio.p25Log.talkgroupOf(radioId)
            if (tg != null && KokohoReferenceTables.lookupTalkgroup(tg)?.agencyCode == "IGNORE") continue
            val tgSig = tg?.let { KokohoSignatures.matchTalkgroup(it) }
            val freqSig = fix.hz?.let { KokohoSignatures.matchFrequency(it, includeRepeaters = true) }
            val resolvedPair: Pair<KokohoAgency, String> = when {
                tgSig != null -> tgSig.agencyEnum to "${tgSig.label} · LRRP GPS (RID $radioId)"
                freqSig != null -> freqSig.agencyEnum to "${freqSig.alpha} (${freqSig.description}) · LRRP GPS (RID $radioId)"
                fix.hz != null && fix.hz in 162_000_000L..174_000_000L -> {
                    val fedAgency = listOf(KokohoAgency.DHS_CBP, KokohoAgency.DHS_ICE, KokohoAgency.DHS_HSI, KokohoAgency.DHS_FPS, KokohoAgency.DHS_INTEROP)
                        .firstOrNull { it in enabled } ?: KokohoAgency.DHS_CBP
                    fedAgency to "Federal VHF P25 LRRP GPS (RID $radioId · ${"%.4f".format(Locale.US, fix.hz / 1e6)} MHz)"
                }
                fix.hz != null && fix.hz in 406_100_000L..420_000_000L -> {
                    val fedAgency = listOf(KokohoAgency.DHS_FPS, KokohoAgency.DHS_ICE, KokohoAgency.DHS_CBP, KokohoAgency.DHS_INTEROP)
                        .firstOrNull { it in enabled } ?: KokohoAgency.DHS_FPS
                    fedAgency to "Federal UHF P25 LRRP GPS (RID $radioId · ${"%.4f".format(Locale.US, fix.hz / 1e6)} MHz)"
                }
                fix.hz != null && fix.hz in 769_000_000L..861_000_000L -> {
                    val leAgency = if (KokohoAgency.POLICE in enabled) KokohoAgency.POLICE else KokohoAgency.SHERIFF
                    leAgency to "P25 LRRP Radio GPS (RID $radioId · ${"%.4f".format(Locale.US, fix.hz / 1e6)} MHz)"
                }
                else -> {
                    val fallbackAgency = listOf(KokohoAgency.POLICE, KokohoAgency.SHERIFF, KokohoAgency.DHS_CBP, KokohoAgency.DHS_ICE)
                        .firstOrNull { it in enabled } ?: continue
                    fallbackAgency to "P25 LRRP Radio GPS (RID $radioId)"
                }
            }
            val agency = resolvedPair.first
            if (agency !in enabled) continue
            val summaryLabel = resolvedPair.second
            val radiusM = (pos.radiusM?.roundToInt() ?: 18).coerceIn(10, 150)
            val isPinpointed = radiusM <= 35
            val distM = if (userLoc != null) {
                haversineMeters(userLoc.latitude, userLoc.longitude, pos.lat, pos.lon).roundToInt().coerceAtLeast(10)
            } else {
                250
            }
            val bearingFromUser = if (userLoc != null) {
                bearingDeg(userLoc.latitude, userLoc.longitude, pos.lat, pos.lon).roundToInt()
            } else {
                null
            }
            val isLiveFix = nowMs - fix.atMs <= 30_000L
            val posture = when {
                isLiveFix && distM <= 300 -> PostureColor.RED
                distM <= 180 || (isLiveFix && distM <= 850) -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }
            val classConf = if (tgSig != null || freqSig != null) 96 else 86
            val spatConf = if (isPinpointed) 97 else 88
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
            val altFt = pos.altitudeM?.let { (it * 3.28084).roundToInt() }
            val facetKey = "rf:rid:$radioId"
            val uuid = deterministicUuidV5(facetKey)
            val hzNote = fix.hz?.let { " · ${"%.4f".format(Locale.US, it / 1e6)} MHz" }.orEmpty()
            val tgNote = tg?.let { " · TG $it" }.orEmpty()
            val badgeDetail = "${agency.friendlyLabel} RID $radioId$tgNote$hzNote · GPS ${"%.5f".format(Locale.US, pos.lat)}, ${"%.5f".format(Locale.US, pos.lon)} (±${radiusM}m)"
            val lrrpThreat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = distM,
                uncertaintyRadiusM = radiusM,
                bearingDeg = bearingFromUser,
                closingVelocityMps = null,
                recurrenceSummary = summaryLabel,
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "P25_LRRP_GPS",
                        facetKey = facetKey,
                        detail = badgeDetail,
                        weight = 0.96,
                        lastSeenMs = fix.atMs
                    )
                ),
                updatedAtMs = fix.atMs,
                lat = pos.lat,
                lon = pos.lon,
                isApproximatedPosition = true,
                isPinpointed = isPinpointed,
                altitudeFtAgl = altFt,
                hardwareIdentifier = "RID $radioId${tg?.let { " (TG $it)" }.orEmpty()}",
                sightingCount = 1,
                capabilities = listOf("Tactical RF", "LRRP GPS")
            )
            upsertAndFuseThreat(evaluated, lrrpThreat)
            localRawObservations += RawSensorReportCandidate(
                facetKey = facetKey,
                rawLat = pos.lat,
                rawLon = pos.lon,
                signalDb = 45.0,
                modality = "P25_LRRP_GPS",
                additionalFacets = listOfNotNull(
                    tg?.let { "p25:tg:$it" },
                    fix.hz?.let { "rf:chan:$it:${fix.nac ?: "ANY"}" }
                ),
                agency = agency,
                label = agency.friendlyLabel,
                propClass = "GROUND_DIRECT",
                uncertaintyRadiusM = radiusM.toDouble(),
                isDerived = true,
                localSightings = 1,
                ssidHint = summaryLabel,
                obsTimestampMs = fix.atMs
            )
        }

    }

    private fun evaluateAdsbAircraft(
        context: Context,
        nowMs: Long,
        userLoc: android.location.Location?,
        evaluated: MutableMap<String, EvaluatedThreat>,
        localRawObservations: MutableList<RawSensorReportCandidate>
    ) {
        val enabledAirAgencies = KokohoSignatures.getEnabledAircraftAgencies(context)
        val locatedHexes = HashSet<String>()
        for (seen in PoliceAircraft.all(nowMs)) {
            val p = seen.plane
            val hit = seen.hit
            if (PoliceAircraft.isGroundedOrParked(p) || PoliceAircraft.isGliderOrExcludedTrainer(p)) continue
            val hasExactGps = p.lat.isFinite() &&
                p.lon.isFinite() &&
                !(abs(p.lat) < 1e-4 && abs(p.lon) < 1e-4) &&
                hit?.label?.startsWith("Police aircraft in radio range", ignoreCase = true) != true
            val isCircling = hasExactGps && (
                hit?.label == PoliceAircraft.CIRCLING ||
                    hit?.evidence?.contains("circling", ignoreCase = true) == true
                )
            val agency = KokohoSignatures.classifyAircraftAgency(
                owner = p.owner,
                registration = p.registration,
                callsign = p.callsign,
                hitLabel = hit?.label,
                type = p.type,
                hex = p.hex,
                speedKt = p.speedKt,
                isCircling = isCircling
            ) ?: continue
            val allowed = isCircling ||
                (if (agency.isDhs) enabledAirAgencies.any { it.isDhs } else agency in enabledAirAgencies)
            if (!allowed) continue
            if (!hasExactGps && userLoc == null) continue

            val hexUpper = p.hex.uppercase()
            if (hasExactGps) locatedHexes += hexUpper
            val facetKey = "adsb:icao:$hexUpper"
            val uuid = deterministicUuidV5(facetKey)
            val distM = when {
                hasExactGps && userLoc != null -> haversineMeters(userLoc.latitude, userLoc.longitude, p.lat, p.lon).roundToInt()
                !hasExactGps -> 2_400
                else -> 9_999
            }
            val uncertM = if (hasExactGps) 25 else 2_400
            val velMps = p.speedKt?.let { it * 0.514444 }
            val isUnattributedCircling = isCircling && hit?.label == PoliceAircraft.CIRCLING
            val posture = when {
                !hasExactGps || userLoc == null -> PostureColor.YELLOW
                isUnattributedCircling && distM <= AIRCRAFT_NEARBY_DIRECT_MAX_M -> PostureColor.RED
                isUnattributedCircling && distM <= 3_219 -> PostureColor.ORANGE
                isUnattributedCircling -> PostureColor.YELLOW
                isCircling && distM <= AIRCRAFT_NEARBY_DIRECT_MAX_M -> PostureColor.RED
                distM <= 805 -> PostureColor.RED
                (isCircling && distM <= 3_219) || distM <= AIRCRAFT_NEARBY_DIRECT_MAX_M -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }
            val classConf = if (isUnattributedCircling) {
                (hit?.confidence ?: 55).coerceIn(45, 68)
            } else {
                hit?.confidence?.coerceIn(80, 99) ?: 92
            }
            val spatConf = when {
                !hasExactGps -> 42
                isUnattributedCircling -> 68
                else -> 98
            }
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
            val identStr = listOfNotNull(
                p.registration?.takeIf { it.isNotBlank() },
                p.callsign?.takeIf { it.isNotBlank() },
                "ICAO $hexUpper"
            ).distinct().joinToString(" · ")
            val seenAtMs = max(PoliceAircraft.localAt, PoliceAircraft.latestAt).takeIf { it > 0L } ?: nowMs
            if (hasExactGps) {
                org.tucsoncommunity.kokoho.online.PoliceAircraft.recordAirborneFix(
                    hex = p.hex,
                    lat = p.lat,
                    lon = p.lon,
                    nowMs = seenAtMs,
                    altM = p.altitudeFt?.let { it * 0.3048 },
                    entityUuid = uuid
                )
            }
            val isHeli = KokohoSignatures.isHelicopterAirframe(
                p.type,
                p.category,
                "${hit?.label.orEmpty()} ${p.owner.orEmpty()} ${p.registration.orEmpty()}",
                agency = if (isUnattributedCircling) null else agency,
                hex = p.hex
            )
            val altSuffix = p.altitudeFt?.let { " · $it ft" }.orEmpty()
            val airSummary = when {
                !hasExactGps -> "Mode-S Transponder in Radio Range ($identStr)"
                isUnattributedCircling -> "Unattributed Circling Aircraft: Verify ($identStr)"
                isCircling -> "Spy Plane / Orbiting Overhead ($identStr)"
                else -> "ADS-B Aircraft ($identStr)"
            }

            val threat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = distM,
                uncertaintyRadiusM = uncertM,
                bearingDeg = null,
                closingVelocityMps = velMps,
                recurrenceSummary = airSummary,
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = if (hasExactGps) "ADSB_AIR" else "MODE_S_AIR",
                        facetKey = facetKey,
                        detail = "${hit?.label ?: agency.friendlyLabel} ($identStr$altSuffix)",
                        weight = if (isUnattributedCircling) 0.55 else if (hasExactGps) 0.95 else 0.78,
                        lastSeenMs = seenAtMs
                    )
                ),
                updatedAtMs = seenAtMs,
                lat = if (hasExactGps) p.lat else userLoc?.latitude,
                lon = if (hasExactGps) p.lon else userLoc?.longitude,
                isApproximatedPosition = hasExactGps,
                isPinpointed = hasExactGps,
                isAircraft = true,
                isHelicopter = isHeli,
                aircraftTrackDeg = p.trackDeg,
                aircraftHex = hexUpper,
                isCircling = isCircling,
                altitudeFtAgl = p.altitudeFt,
                hardwareIdentifier = identStr
            )
            upsertAndFuseThreat(evaluated, threat)
            if (hasExactGps) {
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = p.lat,
                    rawLon = p.lon,
                    signalDb = 42.0,
                    modality = "ADSB",
                    additionalFacets = listOfNotNull(
                        p.altitudeFt?.let { "air:alt_ft_msl:$it" },
                        p.trackDeg?.roundToInt()?.let { "air:hdg:$it" }
                    ),
                    agency = agency,
                    label = agency.friendlyLabel,
                    propClass = "AIRBORNE",
                    uncertaintyRadiusM = 25.0,
                    isDerived = true,
                    localSightings = 1,
                    ssidHint = p.callsign ?: p.registration,
                    isFlagged = !isUnattributedCircling,
                    obsTimestampMs = seenAtMs
                )
            }
        }

        // Unlocated Mode-S transponders heard locally by RTL-SDR without CPR GPS (`heardNearby`)
        if (userLoc != null && PoliceAircraft.localAt > 0L && nowMs - PoliceAircraft.localAt <= 30_000L) {
            val grounded = PoliceAircraft.currentlyGroundedHexes(nowMs)
            for (unloc in PoliceAircraft.localUnlocated) {
                val p = unloc.plane
                val hit = unloc.hit
                val hexUpper = p.hex.uppercase()
                if (hexUpper in locatedHexes || hexUpper in grounded) continue
                if (PoliceAircraft.isGroundedOrParked(p) || PoliceAircraft.isGliderOrExcludedTrainer(p)) continue
                val agency = KokohoSignatures.classifyAircraftAgency(
                    owner = p.owner,
                    registration = p.registration,
                    callsign = p.callsign,
                    hitLabel = hit?.label,
                    type = p.type,
                    hex = p.hex,
                    speedKt = p.speedKt,
                    isCircling = false
                ) ?: continue
                val allowed = if (agency.isDhs) enabledAirAgencies.any { it.isDhs } else agency in enabledAirAgencies
                if (!allowed) continue

                val facetKey = "adsb:icao:$hexUpper"
                val uuid = deterministicUuidV5(facetKey)
                if (uuid in evaluated) continue
                val identStr = listOfNotNull(
                    p.registration?.takeIf { it.isNotBlank() },
                    p.callsign?.takeIf { it.isNotBlank() },
                    "ICAO $hexUpper"
                ).distinct().joinToString(" · ")
                val isHeli = KokohoSignatures.isHelicopterAirframe(
                    p.type,
                    p.category,
                    "${hit?.label.orEmpty()} ${p.owner.orEmpty()} ${p.registration.orEmpty()}",
                    agency = agency,
                    hex = p.hex
                )
                val altSuffix = p.altitudeFt?.let { " · $it ft" }.orEmpty()
                val seenAtMs = PoliceAircraft.localAt
                val classConf = hit?.confidence?.coerceIn(72, 92) ?: 82
                val spatConf = 42
                val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
                val unlocThreat = EvaluatedThreat(
                    uuid = uuid,
                    trackUuid = uuid,
                    agency = agency,
                    label = agency.friendlyLabel,
                    posture = PostureColor.YELLOW,
                    overallConfidence = combinedConf,
                    classificationConfidence = classConf,
                    spatialConfidence = spatConf,
                    estimatedDistanceM = 2_400,
                    uncertaintyRadiusM = 2_400,
                    bearingDeg = null,
                    closingVelocityMps = p.speedKt?.let { it * 0.514444 },
                    recurrenceSummary = "Mode-S Transponder in Radio Range ($identStr)",
                    evidenceSources = listOf(
                        EvidenceBadge(
                            kind = "MODE_S_AIR",
                            facetKey = facetKey,
                            detail = "${hit?.label ?: agency.friendlyLabel} ($identStr$altSuffix)",
                            weight = 0.78,
                            lastSeenMs = seenAtMs
                        )
                    ),
                    updatedAtMs = seenAtMs,
                    lat = userLoc.latitude,
                    lon = userLoc.longitude,
                    isApproximatedPosition = false,
                    isPinpointed = false,
                    isAircraft = true,
                    isHelicopter = isHeli,
                    aircraftTrackDeg = p.trackDeg,
                    aircraftHex = hexUpper,
                    isCircling = false,
                    altitudeFtAgl = p.altitudeFt,
                    hardwareIdentifier = identStr
                )
                upsertAndFuseThreat(evaluated, unlocThreat)
            }
        }
    }

    private fun evaluateLocalTpmsAgentCorroboration(
        context: Context,
        nowMs: Long,
        triDecayMs: Long,
        enabled: Set<KokohoAgency>,
        userLoc: android.location.Location?,
        evaluated: MutableMap<String, EvaluatedThreat>,
        localRawObservations: MutableList<RawSensorReportCandidate>
    ) {
        val allGroundThreats = evaluated.values.filter { t ->
            !t.isAircraft &&
                !t.isDrone &&
                t.agency != KokohoAgency.DRONE
        }
        val activeLocalGroundEmitters = allGroundThreats.filter { t ->
            (lastHeardByLocalHardwareAtMs[t.uuid] ?: 0L).let { it > 0L && (nowMs - it) <= 45_000L } &&
                t.estimatedDistanceM <= 350
        }
        val hasAnyPossibleVehicleOrGroundDetection = activeLocalGroundEmitters.isNotEmpty() ||
            localRawObservations.any {
                it.propClass == "GROUND_DIRECT" &&
                    !it.facetKey.startsWith("uas:", ignoreCase = true) &&
                    !it.facetKey.startsWith("adsb:", ignoreCase = true) &&
                    (nowMs - it.obsTimestampMs) <= 45_000L
            }
        val closeGroundAgents = activeLocalGroundEmitters.filter { t ->
            t.agency != KokohoAgency.DHS_INTEROP &&
                (lastHeardByLocalHardwareAtMs[t.uuid] ?: 0L).let { it > 0L && (nowMs - it) <= 25_000L } &&
                t.estimatedDistanceM <= 180
        }
        if (hasAnyPossibleVehicleOrGroundDetection) {
            org.tucsoncommunity.kokoho.sdr.SdrRadio.requestTpmsHarvest(nowMs)
        }

        val liveTpms = org.tucsoncommunity.kokoho.sdr.TpmsLog.current(nowMs)
            .filter { nowMs - it.lastSeen <= 45_000L }
        val collectNonOwnTpmsNow = KinematicPrivacyGate.shouldCollectNonOwnTpms(
            hasActiveGroundOrVehicleDetection = hasAnyPossibleVehicleOrGroundDetection,
            nowMs = nowMs
        )

        val standaloneVerifiedByEntity = LinkedHashMap<String, MutableList<Pair<org.tucsoncommunity.kokoho.sdr.TpmsLog.Entry, KokohoSignatures.LearnedTpmsIntel>>>()

        for (entry in liveTpms) {
            val freshUserLoc = userLoc?.takeIf { abs(nowMs - entry.lastSeen) <= 5_000L }
            val obsLat = entry.lastLat ?: entry.firstLat ?: freshUserLoc?.latitude
            val obsLon = entry.lastLon ?: entry.firstLon ?: freshUserLoc?.longitude

            KokohoSignatures.observeTpmsAmbient(
                context = context,
                rawFacetKey = entry.facetKey,
                lat = obsLat,
                lon = obsLon,
                hasNearbyAgent = hasAnyPossibleVehicleOrGroundDetection,
                nowMs = entry.lastSeen
            )
            if (KokohoSignatures.isOwnVehicleTpms(context, entry.facetKey)) continue

            if (collectNonOwnTpmsNow) {
                CoMovingTpmsTracker.recordTpmsSample(
                    context = context,
                    rawTpmsFacetKey = entry.facetKey,
                    timestampMs = entry.lastSeen,
                    rssiDb = entry.peakSnrDb.toDouble()
                )
                val tpmsTier = KokohoSignatures.classifySignalTier(context, entry.facetKey)
                RecentSignalRingBuffer.record(
                    context = context,
                    facetKey = entry.facetKey,
                    tier = tpmsTier,
                    modality = "TPMS",
                    signalDb = entry.peakSnrDb.toDouble(),
                    label = "${entry.reading.protocol} ${entry.reading.id}",
                    lat = obsLat,
                    lon = obsLon,
                    timestampMs = entry.lastSeen
                )
                if (tpmsTier == KokohoSignatures.WatchlistTier.TIER_B && obsLat != null && obsLon != null) {
                    localRawObservations += RawSensorReportCandidate(
                        facetKey = entry.facetKey,
                        rawLat = obsLat,
                        rawLon = obsLon,
                        signalDb = entry.peakSnrDb.toDouble(),
                        modality = "TPMS_CANDIDATE",
                        additionalFacets = emptyList(),
                        agency = KokohoAgency.DHS_INTEROP,
                        label = "Vehicle TPMS (${entry.reading.protocol})",
                        propClass = "GROUND_DIRECT",
                        uncertaintyRadiusM = 65.0,
                        isDerived = false,
                        localSightings = entry.sightings.coerceAtLeast(1),
                        ssidHint = "${entry.reading.protocol} ${entry.reading.id}",
                        isFlagged = false,
                        obsTimestampMs = entry.lastSeen,
                        tier = KokohoSignatures.WatchlistTier.TIER_B
                    )
                }
            }

            val colocatedCandidates = if (nowMs - entry.lastSeen <= 30_000L) {
                closeGroundAgents.filter { t ->
                    t.estimatedDistanceM <= 120 &&
                        spatiallyCompatible(obsLat, obsLon, t.lat, t.lon, 120.0)
                }
            } else {
                emptyList()
            }

            if (colocatedCandidates.size == 1) {
                val target = evaluated[colocatedCandidates.single().uuid] ?: colocatedCandidates.single()
                val wr = KokohoSignatures.getWitnessReport(context, target.uuid, approvedOnly = false)
                val intel = KokohoSignatures.recordTpmsAgentCorroboration(
                    context = context,
                    reading = entry.reading,
                    entityUuid = target.uuid,
                    agency = target.agency,
                    label = target.label,
                    lat = obsLat ?: target.lat,
                    lon = obsLon ?: target.lon,
                    witnessVerified = KokohoSignatures.hasConfirmingReport(wr),
                    nowMs = entry.lastSeen
                )
                if (intel != null && KokohoSignatures.isTpmsVerifiedAgentChassis(context, intel)) {
                    val psiPart = entry.reading.psi?.let { " · ${it.roundToInt()} psi" }.orEmpty()
                    val badge = EvidenceBadge(
                        kind = "TPMS_CHASSIS",
                        facetKey = entry.facetKey,
                        detail = "Vehicle TPMS ${entry.reading.protocol} ${entry.reading.id}$psiPart",
                        weight = 0.65,
                        lastSeenMs = entry.lastSeen
                    )
                    val mergedBadges = (target.evidenceSources + badge).distinctBy { it.facetKey }
                    val mergedCaps = (target.capabilities + "Vehicle TPMS").distinct()
                    val updatedTarget = target.copy(
                        classificationConfidence = min(99, max(target.classificationConfidence + 4, 92)),
                        overallConfidence = ((min(99, max(target.classificationConfidence + 4, 92)) + target.spatialConfidence) / 2.0)
                            .roundToInt().coerceIn(1, 99),
                        evidenceSources = mergedBadges,
                        updatedAtMs = max(target.updatedAtMs, entry.lastSeen),
                        capabilities = mergedCaps
                    )
                    upsertAndFuseThreat(evaluated, updatedTarget)
                    val targetPrimaryKey = target.hardwareIdentifier?.let { "hw:mac:$it" }
                        ?: target.evidenceSources.firstOrNull()?.facetKey
                    if (targetPrimaryKey != null) {
                        for (idx in localRawObservations.indices) {
                            if (localRawObservations[idx].facetKey == targetPrimaryKey) {
                                val prevAdd = localRawObservations[idx].additionalFacets
                                if (entry.facetKey !in prevAdd) {
                                    localRawObservations[idx] = localRawObservations[idx].copy(
                                        additionalFacets = prevAdd + entry.facetKey
                                    )
                                }
                            }
                        }
                    }
                }
                continue
            }

            // No unambiguous active router/kit/RF signal right now: ONLY surface standalone alert if this TPMS sensor
            // has already graduated to verified agent chassis status (`learned_tpms_v1` Tier A).
            val verified = KokohoSignatures.lookupVerifiedAgentTpms(context, entry.facetKey) ?: continue
            if (verified.agencyEnum !in enabled) continue
            standaloneVerifiedByEntity.getOrPut(verified.boundEntityUuid) { mutableListOf() }.add(entry to verified)
        }

        // Apply Co-Moving TPMS RSSI narrowing & Mobile Vehicle vs Fixed Infrastructure disambiguation
        for (groundUuid in evaluated.keys.toList()) {
            val target = evaluated[groundUuid] ?: continue
            if (target.isAircraft || target.isDrone || target.agency == KokohoAgency.DRONE) continue
            val disambig = CoMovingTpmsTracker.evaluateEmitter(
                context = context,
                emitterKey = target.uuid,
                nowMs = nowMs,
                stationaryCentroidLocked = target.isApproximatedPosition && target.uncertaintyRadiusM <= 120
            )
            when (disambig.posture) {
                CoMovingTpmsTracker.ChassisPosture.MOBILE_VEHICLE -> {
                    val tpmsBadges = disambig.coMovingMatches.take(4).map { m ->
                        EvidenceBadge(
                            kind = "TPMS_CHASSIS",
                            facetKey = m.tpmsFacetKey,
                            detail = String.format(
                                Locale.US,
                                "Co-Moving TPMS %s (score=%.2f · r=%.2f)",
                                m.tpmsFacetKey.removePrefix("hw:kit:tpms:"),
                                m.coMovingScore,
                                m.rssiCorrelation
                            ),
                            weight = 0.80,
                            lastSeenMs = target.updatedAtMs
                        )
                    }
                    val mergedBadges = (target.evidenceSources + tpmsBadges).distinctBy { it.facetKey }
                    val cleanedCaps = target.capabilities.filterNot { it == CoMovingTpmsTracker.ChassisPosture.FIXED_INFRA.label }
                    val mergedCaps = (cleanedCaps + listOf("Vehicle TPMS", CoMovingTpmsTracker.ChassisPosture.MOBILE_VEHICLE.label)).distinct()
                    val bumpedClass = min(99, max(target.classificationConfidence + 6, 86))
                    val updated = target.copy(
                        classificationConfidence = bumpedClass,
                        overallConfidence = ((bumpedClass + target.spatialConfidence) / 2.0).roundToInt().coerceIn(1, 99),
                        evidenceSources = mergedBadges,
                        capabilities = mergedCaps
                    )
                    evaluated[target.uuid] = updated
                    localThreatCache[target.uuid] = updated
                }
                CoMovingTpmsTracker.ChassisPosture.FIXED_INFRA -> {
                    if (CoMovingTpmsTracker.ChassisPosture.MOBILE_VEHICLE.label !in target.capabilities &&
                        "Vehicle TPMS" !in target.capabilities
                    ) {
                        val mergedCaps = (target.capabilities + CoMovingTpmsTracker.ChassisPosture.FIXED_INFRA.label).distinct()
                        val updated = target.copy(capabilities = mergedCaps)
                        evaluated[target.uuid] = updated
                        localThreatCache[target.uuid] = updated
                    }
                }
                CoMovingTpmsTracker.ChassisPosture.UNKNOWN -> Unit
            }

            if (disambig.posture != CoMovingTpmsTracker.ChassisPosture.UNKNOWN) {
                val extraFacets = disambig.toAdditionalFacets()
                val topCorr = disambig.bestRssiCorrelation
                val targetFacetSet = buildSet {
                    target.hardwareIdentifier?.let { add("hw:mac:$it") }
                    target.evidenceSources.forEach { add(it.facetKey) }
                }
                for (idx in localRawObservations.indices) {
                    val obs = localRawObservations[idx]
                    if (obs.facetKey in targetFacetSet || deterministicUuidV5(obs.facetKey) == target.uuid) {
                        localRawObservations[idx] = obs.copy(
                            additionalFacets = (obs.additionalFacets + extraFacets).distinct(),
                            rssiCorr = topCorr ?: obs.rssiCorr,
                            tpmsDisambig = disambig.posture.code
                        )
                    }
                }
            }
        }

        // Also enrich non-alerting Tier B ground observations with any co-moving TPMS or fixed-infra disambiguation
        for (idx in localRawObservations.indices) {
            val obs = localRawObservations[idx]
            if (obs.tier != KokohoSignatures.WatchlistTier.TIER_B || obs.facetKey.startsWith("hw:kit:tpms:")) continue
            val obsUuid = deterministicUuidV5(obs.facetKey)
            val disambig = CoMovingTpmsTracker.evaluateEmitter(context, obsUuid, nowMs)
            if (disambig.posture != CoMovingTpmsTracker.ChassisPosture.UNKNOWN) {
                localRawObservations[idx] = obs.copy(
                    additionalFacets = (obs.additionalFacets + disambig.toAdditionalFacets()).distinct(),
                    rssiCorr = disambig.bestRssiCorrelation ?: obs.rssiCorr,
                    tpmsDisambig = disambig.posture.code
                )
            }
        }

        for ((entityUuid, wheelItems) in standaloneVerifiedByEntity) {
            val leadIntel = wheelItems.first().second
            val agency = leadIntel.agencyEnum
            val newestSeen = wheelItems.maxOf { it.first.lastSeen }
            val peakSnr = wheelItems.maxOf { it.first.peakSnrDb }
            val sampleWithLoc = wheelItems.firstOrNull { (it.first.lastLat ?: it.first.firstLat) != null }?.first
                ?: wheelItems.first().first
            val freshUserLoc = userLoc?.takeIf { abs(nowMs - newestSeen) <= 5_000L }
            val obsLat = sampleWithLoc.lastLat ?: sampleWithLoc.firstLat ?: freshUserLoc?.latitude
            val obsLon = sampleWithLoc.lastLon ?: sampleWithLoc.firstLon ?: freshUserLoc?.longitude

            val rawEstDistM = when {
                peakSnr >= 26 -> 30
                peakSnr >= 18 -> 55
                else -> 85
            }
            val tri = recordAndTriangulate(
                uuid = entityUuid,
                gpsLat = obsLat,
                gpsLon = obsLon,
                signalDb = peakSnr,
                estDistanceM = rawEstDistM,
                nowMs = newestSeen,
                decayMs = triDecayMs,
                userLat = userLoc?.latitude,
                userLon = userLoc?.longitude,
                evalNowMs = nowMs
            )

            val badges = wheelItems.map { (e, intel) ->
                val psiVal = e.reading.psi?.roundToInt() ?: intel.lastPsi
                val psiPart = psiVal?.let { " · $it psi" }.orEmpty()
                EvidenceBadge(
                    kind = "TPMS_CHASSIS",
                    facetKey = e.facetKey,
                    detail = "Corroborated ${agency.friendlyLabel} vehicle TPMS (${e.reading.protocol} ${e.reading.id}$psiPart)",
                    weight = 0.85,
                    lastSeenMs = e.lastSeen
                )
            }
            val posture = when {
                tri.estimatedDistanceM <= 250 -> PostureColor.RED
                tri.estimatedDistanceM <= 600 -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }
            val classConf = if (wheelItems.any { it.second.witnessVerified } || wheelItems.size >= 2) 94 else 88
            val spatConf = max(tri.spatialConfidence, 68)
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
            val wheelSummary = wheelItems.joinToString(", ") { "${it.first.reading.protocol} ${it.first.reading.id}" }

            val threat = EvaluatedThreat(
                uuid = entityUuid,
                trackUuid = entityUuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = tri.estimatedDistanceM,
                uncertaintyRadiusM = tri.uncertaintyRadiusM.coerceAtMost(120),
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "${agency.friendlyLabel} Vehicle (TPMS $wheelSummary)",
                evidenceSources = badges,
                updatedAtMs = newestSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed,
                hardwareIdentifier = badges.first().facetKey,
                sightingCount = max(tri.sampleCount, wheelItems.maxOf { it.first.sightings }),
                capabilities = listOf("Vehicle TPMS")
            )
            upsertAndFuseThreat(evaluated, threat)
            if (obsLat != null && obsLon != null) {
                localRawObservations += RawSensorReportCandidate(
                    facetKey = badges.first().facetKey,
                    rawLat = obsLat,
                    rawLon = obsLon,
                    signalDb = peakSnr.toDouble(),
                    modality = "TPMS_CHASSIS",
                    additionalFacets = badges.drop(1).map { it.facetKey },
                    agency = agency,
                    label = agency.friendlyLabel,
                    propClass = "GROUND_DIRECT",
                    uncertaintyRadiusM = tri.uncertaintyRadiusM.coerceAtMost(120).toDouble(),
                    isDerived = tri.isApproximatedPosition,
                    localSightings = threat.sightingCount,
                    ssidHint = "${wheelItems.first().first.reading.protocol} TPMS",
                    isFlagged = true,
                    obsTimestampMs = newestSeen
                )
            }
        }
    }

    private fun finalizeSnapshotAndPublish(
        context: Context,
        nowMs: Long,
        mapVisibilityMs: Long,
        monitorWindowMs: Long,
        activeMapWindow: KokohoSignatures.MapTimeWindow,
        alarmEnabled: Set<KokohoAgency>,
        mapVisible: Set<KokohoAgency>,
        enabled: Set<KokohoAgency>,
        enabledScopeText: String,
        groundedHexes: Set<String>,
        userLoc: android.location.Location?,
        evaluated: MutableMap<String, EvaluatedThreat>,
        localRawObservations: List<RawSensorReportCandidate>
    ): PostureSnapshot {
        val retentionWindowMs = max(mapVisibilityMs, monitorWindowMs)
        for (fresh in evaluated.values) {
            if (!fresh.isAircraft && fresh.lat != null && fresh.lon != null && localThreatCache.containsKey(fresh.uuid)) {
                KokohoEntityLedger.upsert(context, fresh, nowMs)
            }
        }
        for (persisted in KokohoEntityLedger.getWithinRollingWindow(context, nowMs, retentionWindowMs)) {
            if (KokohoEntityLedger.isSuppressedFalsePositiveThreat(persisted)) continue
            val persistedAllowed = if (persisted.isDrone) {
                KokohoSignatures.isDroneDetectionEnabled(context) || KokohoAgency.DRONE in mapVisible
            } else {
                persisted.agency in enabled
            }
            if (persisted.uuid !in evaluated && persistedAllowed) {
                val existingCached = localThreatCache[persisted.uuid]
                val heardLocally = (lastHeardByLocalHardwareAtMs[persisted.uuid] ?: 0L) > 0L
                if (existingCached == null || (!heardLocally && persisted.updatedAtMs >= existingCached.updatedAtMs)) {
                    localThreatCache[persisted.uuid] = persisted
                }
            }
        }
        for (wr in KokohoSignatures.getAllWitnessReports(context)) {
            if (wr.reportKind != "LE" || wr.lat == null || wr.lon == null) continue
            if (nowMs - wr.updatedAtMs > retentionWindowMs) continue
            val wrUuid = KokohoSignatures.normalizeEntityId(wr.entityUuid ?: wr.targetKey)
            if (wrUuid in evaluated) continue
            val wrAgency = KokohoAgency.fromCode(wr.agencyCode) ?: KokohoAgency.DHS_ICE
            if (wrAgency !in enabled) continue
            val distM = if (userLoc != null) {
                haversineMeters(userLoc.latitude, userLoc.longitude, wr.lat, wr.lon).roundToInt()
            } else 9_999
            val wrAgeMs = (nowMs - wr.updatedAtMs).coerceAtLeast(0L)
            val rawWrPosture = when {
                userLoc == null -> PostureColor.YELLOW
                distM <= 300 -> PostureColor.RED
                distM <= 850 -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }
            val pointEntity = EvaluatedThreat(
                uuid = wrUuid,
                trackUuid = wrUuid,
                agency = wrAgency,
                label = wrAgency.friendlyLabel,
                posture = if (wrAgeMs <= monitorWindowMs) KokohoSignatures.capMonitorPostureByAge(rawWrPosture, wrAgeMs) else PostureColor.YELLOW,
                overallConfidence = 92,
                classificationConfidence = 94,
                spatialConfidence = 90,
                estimatedDistanceM = distM,
                uncertaintyRadiusM = 35,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = wr.agentName.ifBlank { wr.vehicleNumber.ifBlank { wr.accessoryKind.ifBlank { "${wrAgency.friendlyLabel} Sighting" } } },
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "WITNESS_REPORT",
                        facetKey = "witness:point:$wrUuid",
                        detail = wr.notes.ifBlank { "Visual witness point report" },
                        weight = 0.95,
                        lastSeenMs = wr.updatedAtMs
                    )
                ),
                updatedAtMs = wr.updatedAtMs,
                lat = wr.lat,
                lon = wr.lon,
                isApproximatedPosition = true,
                isPinpointed = false,
                sightingCount = 1
            )
            evaluated[wrUuid] = pointEntity
            KokohoEntityLedger.upsert(context, pointEntity, nowMs)
        }

        localThreatCache.entries.removeIf {
            KokohoEntityLedger.isSuppressedFalsePositiveThreat(it.value) ||
                shouldEvictLocalCache(it.value, nowMs, retentionWindowMs, groundedHexes)
        }
        for ((uuid, cached) in localThreatCache) {
            if (uuid !in evaluated && !shouldEvictLocalCache(cached, nowMs, retentionWindowMs, groundedHexes)) {
                val allowed = when {
                    cached.isAircraft -> {
                        val airEnabled = KokohoSignatures.getEnabledAircraftAgencies(context)
                        KokohoSignatures.isAdsbEnabled(context) &&
                            (cached.isCircling || if (cached.agency.isDhs) airEnabled.any { it.isDhs } else cached.agency in airEnabled)
                    }
                    cached.isDrone -> KokohoSignatures.isDroneDetectionEnabled(context) || KokohoAgency.DRONE in mapVisible
                    else -> cached.agency in enabled
                }
                if (allowed) {
                    val refreshed = if (userLoc != null && cached.lat != null && cached.lon != null) {
                        val rawLiveDistM = haversineMeters(userLoc.latitude, userLoc.longitude, cached.lat, cached.lon).roundToInt()
                        if (cached.isAircraft) {
                            cached.copy(estimatedDistanceM = rawLiveDistM)
                        } else {
                            val liveDistM = rawLiveDistM
                            val ageMs = (nowMs - cached.updatedAtMs).coerceAtLeast(0L)
                            val isSoftUncorroborated = cached.classificationConfidence <= 58 &&
                                cached.evidenceSources.size == 1 &&
                                cached.evidenceSources.first().kind == "WIFI_BLE_OUI"
                            val centroidPosture = when {
                                isSoftUncorroborated -> PostureColor.YELLOW
                                rawLiveDistM <= 300 -> PostureColor.RED
                                rawLiveDistM <= 850 -> PostureColor.ORANGE
                                else -> PostureColor.YELLOW
                            }
                            val rawProximityPosture = if (ageMs <= 20_000L && cached.posture.ordinal > centroidPosture.ordinal) {
                                cached.posture
                            } else {
                                centroidPosture
                            }
                            val refreshedPosture = if (ageMs <= monitorWindowMs) {
                                KokohoSignatures.capMonitorPostureByAge(rawProximityPosture, ageMs)
                            } else {
                                PostureColor.YELLOW
                            }
                            cached.copy(estimatedDistanceM = liveDistM, posture = refreshedPosture)
                        }
                    } else if (userLoc == null && cached.lat != null && cached.lon != null) {
                        val ageMs = (nowMs - cached.updatedAtMs).coerceAtLeast(0L)
                        if (ageMs > 90_000L) {
                            cached.copy(estimatedDistanceM = 9_999, posture = PostureColor.YELLOW)
                        } else {
                            cached
                        }
                    } else {
                        cached
                    }
                    evaluated[uuid] = refreshed
                    val cachedRfChan = refreshed.evidenceSources
                        .firstOrNull { it.facetKey.startsWith("rf:chan:") }
                        ?.facetKey
                        ?.removePrefix("rf:chan:")
                        ?.substringBefore(':')
                        ?.toLongOrNull()
                    if (cachedRfChan != null) {
                        deduplicateRemoteRfEchoes(evaluated, uuid, cachedRfChan, refreshed.lat, refreshed.lon, refreshed.updatedAtMs)
                    }
                }
            }
        }

        val allRetained = evaluated.values
            .filter { !KokohoSignatures.isEntityWhitelisted(context, it.uuid) }
            .map { t ->
                val witness = KokohoSignatures.getWitnessReport(context, t.uuid, approvedOnly = false)
                val effectiveAgency = witness?.takeIf { it.reportKind == "LE" }?.agencyCode?.let { KokohoAgency.fromCode(it) } ?: t.agency
                val inferredCaps = (t.capabilities + KokohoSignatures.inferCapabilitiesFromEvidence(
                    t.evidenceSources.map { it.facetKey },
                    t.evidenceSources.map { it.detail } + listOf(t.recurrenceSummary, t.label)
                )).distinct()
                val rawHighestTitle = KokohoSignatures.formatHighestOrderTitle(t.copy(agency = effectiveAgency, capabilities = inferredCaps), witness)
                val isStandaloneOp = !t.isAircraft && isStandaloneDroneOperator(t.evidenceSources, t.hardwareIdentifier)
                val highestOrderTitle = if (t.isDrone || isStandaloneOp) sanitizeDroneOperatorSuffix(rawHighestTitle) else rawHighestTitle
                val rawSummary = if (t.recurrenceSummary.isBlank()) highestOrderTitle else t.recurrenceSummary
                val cleanedSummary = if (t.isDrone || isStandaloneOp) sanitizeDroneOperatorSuffix(rawSummary) else rawSummary
                val ageMs = (nowMs - t.updatedAtMs).coerceAtLeast(0L)
                val cappedPosture = if (ageMs <= monitorWindowMs) {
                    KokohoSignatures.capMonitorPostureByAge(t.posture, ageMs)
                } else {
                    PostureColor.YELLOW
                }
                val updated = t.copy(
                    agency = effectiveAgency,
                    label = highestOrderTitle,
                    posture = cappedPosture,
                    recurrenceSummary = cleanedSummary,
                    uncertaintyRadiusM = if (isStandaloneOp) 15 else t.uncertaintyRadiusM,
                    isPinpointed = if (isStandaloneOp) true else t.isPinpointed,
                    capabilities = inferredCaps
                )
                if (localThreatCache.containsKey(t.uuid)) {
                    localThreatCache[t.uuid] = updated
                }
                updated
            }

        val mapThreats = allRetained
            .filter { t ->
                val ageMs = (nowMs - t.updatedAtMs).coerceAtLeast(0L)
                val withinMapWindow = if (t.isAircraft) {
                    ageMs <= min(mapVisibilityMs, LIVE_AIRCRAFT_MAX_AGE_MS)
                } else {
                    ageMs <= mapVisibilityMs
                }
                withinMapWindow && when {
                    t.isAircraft -> true
                    t.isDrone -> KokohoAgency.DRONE in mapVisible
                    else -> t.agency in mapVisible
                }
            }
            .map { t ->
                val ageMs = (nowMs - t.updatedAtMs).coerceAtLeast(0L)
                val projectedRadiusM = KokohoSignatures.projectRollingRadiusM(
                    window = activeMapWindow,
                    ageMs = ageMs,
                    baseRadiusM = t.uncertaintyRadiusM,
                    isPinpointed = t.isPinpointed
                )
                t.copy(uncertaintyRadiusM = projectedRadiusM)
            }
            .sortedWith(
                compareBy<EvaluatedThreat> { it.agency.priorityRank }
                    .thenBy { it.effectiveProximityM }
                    .thenByDescending { it.updatedAtMs }
            )

        // Monitor threats: strictly filtered by Alarm (`alarmEnabled`), user-relative proximity, and the 3-hour Monitor window.
        // Posture is RED only within 30 minutes and capped at ORANGE from 30 minutes to 3 hours.
        val monitorThreats = allRetained
            .filter { t ->
                if (nowMs - t.updatedAtMs > monitorWindowMs) return@filter false
                if (t.isAircraft) {
                    val airAlarmAllowed = t.isCircling ||
                        (if (t.agency.isDhs) alarmEnabled.any { it.isDhs } else t.agency in alarmEnabled)
                    airAlarmAllowed && isAircraftNearbyForMonitor(
                        distM = t.estimatedDistanceM,
                        isCircling = t.isCircling,
                        hasUserLoc = userLoc != null
                    )
                } else {
                    val heardLocallyLive = (lastHeardByLocalHardwareAtMs[t.uuid] ?: 0L).let {
                        it > 0L && (nowMs - it) <= 90_000L
                    }
                    t.agency in alarmEnabled && isGroundOrDroneNearbyForMonitor(
                        threat = t,
                        hasUserLoc = userLoc != null,
                        nowMs = nowMs,
                        heardLocallyLive = heardLocallyLive
                    )
                }
            }
            .sortedWith(
                compareBy<EvaluatedThreat> { it.agency.priorityRank }
                    .thenByDescending { KokohoSignatures.isEntityWatched(context, it.uuid) }
                    .thenBy { it.effectiveProximityM }
            )

        val monitorGroups = buildMonitorGroups(monitorThreats)

        if (ScanForegroundService.isRunning) {
            FieldTelemetryLogger.recordEvaluationPass(
                context = context,
                nowMs = nowMs,
                userLoc = userLoc,
                rawObservations = localRawObservations,
                activeThreats = monitorThreats
            )
        }

        if (ScanForegroundService.isRunning && KokohoSignatures.isSharingActive(context) && localRawObservations.isNotEmpty()) {
            val continuousDebugShare = KinematicPrivacyGate.shouldShareAllSignalsContinuously(context)
            val shareNonTpmsTierB = KinematicPrivacyGate.shouldShareNonTpmsTierB(context, nowMs)
            val hasActiveGroundDetection = evaluated.values.any {
                !it.isAircraft && !it.isDrone && it.agency != KokohoAgency.DRONE && (nowMs - it.updatedAtMs) <= 45_000L
            } || localRawObservations.any {
                it.propClass == "GROUND_DIRECT" && !it.facetKey.startsWith("uas:", ignoreCase = true) && !it.facetKey.startsWith("hw:kit:tpms:", ignoreCase = true)
            }
            val collectNonOwnTpms = KinematicPrivacyGate.shouldCollectNonOwnTpms(hasActiveGroundDetection, nowMs)

            val bestByFacet = linkedMapOf<String, RawSensorReportCandidate>()
            for (cand in localRawObservations) {
                val isTpmsCand = KokohoSignatures.canonicalTpmsFacetKey(cand.facetKey) != null
                val allowedToShare = when {
                    isTpmsCand && KokohoSignatures.isOwnVehicleTpms(context, cand.facetKey) -> false
                    cand.isFlagged && cand.tier == KokohoSignatures.WatchlistTier.TIER_A -> true
                    continuousDebugShare -> true
                    isTpmsCand -> collectNonOwnTpms
                    cand.tier == KokohoSignatures.WatchlistTier.TIER_B -> shareNonTpmsTierB
                    else -> false
                }
                if (!allowedToShare) continue
                val existing = bestByFacet[cand.facetKey]
                if (existing == null || cand.signalDb > existing.signalDb) {
                    bestByFacet[cand.facetKey] = cand
                }
            }
            for (cand in bestByFacet.values) {
                val prev = lastRawReportByFacet[cand.facetKey]
                if (prev != null && cand.obsTimestampMs > 0L && cand.obsTimestampMs <= prev.lastObsTsMs) continue
                val elapsed = if (prev != null) nowMs - prev.atMs else Long.MAX_VALUE
                val movedDistM = if (prev != null) haversineMeters(prev.lat, prev.lon, cand.rawLat, cand.rawLon) else Double.MAX_VALUE
                val movedEnough = prev != null && movedDistM >= 25.0
                val pinpointDroneMoved = prev != null && cand.modality == "REMOTE_ID" && cand.isDerived && elapsed >= 4_000L && movedDistM >= 15.0
                val signalChanged = prev != null && abs(cand.signalDb - prev.signalDb) >= 6.0
                val shouldSend = prev == null || elapsed >= 30_000L || pinpointDroneMoved || (elapsed >= 8_000L && (movedEnough || signalChanged))
                if (!shouldSend) continue
                lastRawReportByFacet[cand.facetKey] = LastRawReportState(nowMs, cand.rawLat, cand.rawLon, cand.signalDb, cand.obsTimestampMs)
                KokohoHubClient.publishSensorReportIfEnabled(
                    context = context,
                    lat = cand.rawLat,
                    lon = cand.rawLon,
                    snrDb = cand.signalDb,
                    modality = cand.modality,
                    primaryFacet = cand.facetKey,
                    additionalFacets = cand.additionalFacets,
                    agency = cand.agency,
                    label = cand.label,
                    propagationClass = cand.propClass,
                    uncertaintyRadiusM = cand.uncertaintyRadiusM,
                    isDerived = cand.isDerived,
                    localSightings = cand.localSightings,
                    ssidHint = cand.ssidHint
                )
            }
        }

        val sectorTempo = org.tucsoncommunity.kokoho.sdr.RadioLog.sectorTempoLabel(nowMs)
        val primary = monitorThreats.firstOrNull()
        lastEvaluateNowMs = nowMs
        latestRawObservationsSnapshot = localRawObservations.toList()
        if (primary == null) {
            lastPostureColor = PostureColor.GREEN
            return PostureSnapshot(
                color = PostureColor.GREEN,
                headline = "No threats detected",
                subheadline = "",
                distanceAndBearingText = "",
                bottomConfidenceText = "",
                primaryThreat = null,
                activeThreats = emptyList(),
                monitorGroups = emptyList(),
                mapThreats = mapThreats,
                enabledScopeText = enabledScopeText,
                sectorTempoText = sectorTempo,
                evaluatedAtMs = nowMs
            ).also { latestSnapshot = it }
        }

        val highestPosture = monitorThreats.maxByOrNull { it.posture.ordinal }?.posture ?: primary.posture
        lastPostureColor = highestPosture
        val distFormatted = formatUserDistance(context, primary.effectiveProximityM)
        val bottomConfidenceText =
            "${primary.combinedConfidence}% confidence · ${primary.evidenceCount} ${if (primary.evidenceCount == 1) "signal" else "signals"}"

        return PostureSnapshot(
            color = highestPosture,
            headline = primary.agency.friendlyLabel,
            subheadline = monitorGroups.firstOrNull()?.formatMonitorSubtext(context, nowMs).orEmpty(),
            distanceAndBearingText = distFormatted,
            bottomConfidenceText = bottomConfidenceText,
            primaryThreat = primary,
            activeThreats = monitorThreats,
            monitorGroups = monitorGroups,
            mapThreats = mapThreats,
            enabledScopeText = enabledScopeText,
            sectorTempoText = sectorTempo,
            evaluatedAtMs = nowMs
        ).also { latestSnapshot = it }
    }

    private fun buildMonitorGroups(activeThreats: List<EvaluatedThreat>): List<AgencyMonitorGroup> {
        if (activeThreats.isEmpty()) return emptyList()
        return activeThreats
            .groupBy { it.agency }
            .entries
            .sortedBy { it.key.priorityRank }
            .map { (agency, units) ->
                val ordered = units.sortedBy { it.effectiveProximityM }
                val lead = ordered.first()
                // Split units into primary proximity cluster vs incrementally farther units by center-centroid distance
                val clusterCutoffM = max((lead.estimatedDistanceM * 1.8).roundToInt(), lead.estimatedDistanceM + 400)
                val primaryCluster = ordered.filter {
                    it.estimatedDistanceM <= clusterCutoffM
                }
                val widerCluster = ordered.drop(primaryCluster.size)
                val highestPosture = ordered.maxByOrNull { it.posture.ordinal }?.posture ?: lead.posture
                val newestMs = ordered.maxOf { it.updatedAtMs }
                AgencyMonitorGroup(
                    agency = agency,
                    posture = highestPosture,
                    distinctUnits = ordered,
                    primaryCount = primaryCluster.size.coerceAtLeast(1),
                    primaryRadiusM = lead.uncertaintyRadiusM,
                    primaryEdgeDistanceM = lead.nearestEdgeDistanceM,
                    widerCount = widerCluster.size,
                    widerRadiusM = widerCluster.firstOrNull()?.estimatedDistanceM,
                    lastSeenMs = newestMs
                )
            }
    }

    private fun effectiveNowMs(explicitNowMs: Long?): Long {
        if (explicitNowMs != null) return explicitNowMs
        val sys = System.currentTimeMillis()
        return if (lastEvaluateNowMs > 0L && abs(sys - lastEvaluateNowMs) > 60_000L) {
            lastEvaluateNowMs
        } else {
            sys
        }
    }

    /** Returns the active posture background color ARGB so all activities maintain a stable status background color. */
    fun currentPostureColorArgb(context: Context, nowMs: Long? = null): Int {
        return evaluate(context, effectiveNowMs(nowMs)).color.colorArgb
    }

    /**
     * Collects all known non-whitelisted [EvaluatedThreat] entities across live evaluation,
     * the all-time SQLite [KokohoEntityLedger], local LE Witness Reports, and synced Auxnet Hub objects,
     * regardless of the currently selected Map rolling window.
     */
    fun allSearchableThreats(context: Context, nowMs: Long? = null): List<EvaluatedThreat> {
        val effNow = effectiveNowMs(nowMs)
        val snap = evaluate(context, effNow)
        val byUuid = LinkedHashMap<String, EvaluatedThreat>()
        for (t in snap.activeThreats) byUuid[t.uuid] = t
        for (t in snap.mapThreats) byUuid.putIfAbsent(t.uuid, t)

        val allTimeWindowMs = KokohoSignatures.MapTimeWindow.HEAT.windowMs
        for (persisted in KokohoEntityLedger.getWithinRollingWindow(context, effNow, allTimeWindowMs)) {
            if (persisted.uuid in byUuid) continue
            if (KokohoSignatures.isEntityWhitelisted(context, persisted.uuid)) continue
            val witness = KokohoSignatures.getWitnessReport(context, persisted.uuid, approvedOnly = false)
            val effectiveAgency = witness?.takeIf { it.reportKind == "LE" }?.agencyCode?.let { KokohoAgency.fromCode(it) } ?: persisted.agency
            val title = KokohoSignatures.formatHighestOrderTitle(persisted.copy(agency = effectiveAgency), witness)
            byUuid[persisted.uuid] = persisted.copy(agency = effectiveAgency, label = title)
        }

        if (KokohoSignatures.isAuxnetEnabled(context)) {
            val userLoc = resolveUserLocation(context)
            for (remote in KokohoHubClient.activeObjects(effNow, allTimeWindowMs)) {
                if (remote.uuid in byUuid) continue
                if (KokohoSignatures.isEntityWhitelisted(context, remote.uuid)) continue
                if (KokohoSignatures.isFlockCamera(remote.uuid, remote.classification.label) ||
                    remote.classification.evidenceSources.any { KokohoSignatures.isFlockCamera(it.facetKey, it.detail) }
                ) continue
                if (remote.location.propagationClass.equals("REPEATER_INFRASTRUCTURE", ignoreCase = true)) continue
                val agency = KokohoAgency.fromCode(remote.classification.category) ?: continue
                val uncertM = remote.location.uncertaintyRadiusM.roundToInt().coerceAtLeast(15)
                val centerDistM = if (userLoc != null) {
                    haversineMeters(userLoc.latitude, userLoc.longitude, remote.location.lat, remote.location.lon).roundToInt()
                } else 9_999
                val classConf = (remote.confidence.classification * 100).roundToInt().coerceIn(1, 99)
                val spatConf = (remote.confidence.spatial * 100).roundToInt().coerceIn(1, 99)
                val badges = remote.classification.evidenceSources.map {
                    EvidenceBadge(it.kind, it.facetKey, it.detail, it.weight, it.lastSeenMs)
                }
                val caps = KokohoSignatures.inferCapabilitiesFromEvidence(
                    badges.map { it.facetKey },
                    badges.map { it.detail } + remote.classification.label
                )
                val isStandaloneOp = isStandaloneDroneOperator(badges, badges.firstOrNull()?.facetKey)
                val droneMeta = extractDroneTelemetryFromFacets(badges.map { it.facetKey })
                val hasRemoteIdMeta = droneMeta.operatorLat != null || droneMeta.altFtAgl != null
                val isDroneObj = agency == KokohoAgency.DRONE || badges.any { it.facetKey.startsWith("uas:", true) }
                val rawThreat = EvaluatedThreat(
                    uuid = remote.uuid,
                    trackUuid = remote.trackUuid,
                    agency = agency,
                    label = agency.friendlyLabel,
                    posture = PostureColor.YELLOW,
                    overallConfidence = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99),
                    classificationConfidence = classConf,
                    spatialConfidence = spatConf,
                    estimatedDistanceM = centerDistM,
                    uncertaintyRadiusM = if (isStandaloneOp) 15 else uncertM,
                    bearingDeg = remote.location.bearingDeg?.roundToInt(),
                    closingVelocityMps = remote.location.velocityMps,
                    recurrenceSummary = if (isDroneObj || isStandaloneOp) {
                        sanitizeDroneOperatorSuffix(remote.classification.recurrenceSummary)
                    } else {
                        remote.classification.recurrenceSummary
                    },
                    evidenceSources = badges,
                    updatedAtMs = remote.updatedAtMs,
                    lat = remote.location.lat,
                    lon = remote.location.lon,
                    isApproximatedPosition = true,
                    isPinpointed = isStandaloneOp || uncertM <= PINPOINT_POINT_RADIUS_M || (isDroneObj && hasRemoteIdMeta && uncertM <= 40),
                    isDrone = isDroneObj,
                    altitudeFtAgl = droneMeta.altFtAgl,
                    operatorLat = droneMeta.operatorLat,
                    operatorLon = droneMeta.operatorLon,
                    aircraftTrackDeg = droneMeta.headingDeg,
                    hardwareIdentifier = badges.firstOrNull()?.facetKey,
                    sightingCount = max(remote.sightingCount, remote.corroborationCount).coerceAtLeast(1),
                    capabilities = caps
                )
                val witness = KokohoSignatures.getWitnessReport(context, rawThreat.uuid, approvedOnly = false)
                val effectiveAgency = witness?.takeIf { it.reportKind == "LE" }?.agencyCode?.let { KokohoAgency.fromCode(it) } ?: rawThreat.agency
                val rawHighestTitle = KokohoSignatures.formatHighestOrderTitle(rawThreat.copy(agency = effectiveAgency), witness)
                val highestTitle = if (isDroneObj || isStandaloneOp) sanitizeDroneOperatorSuffix(rawHighestTitle) else rawHighestTitle
                byUuid[rawThreat.uuid] = rawThreat.copy(agency = effectiveAgency, label = highestTitle)
            }
        }

        for (wr in KokohoSignatures.getAllWitnessReports(context)) {
            if (wr.reportKind != "LE" || wr.lat == null || wr.lon == null) continue
            val wrUuid = KokohoSignatures.normalizeEntityId(wr.entityUuid ?: wr.targetKey)
            if (wrUuid in byUuid || KokohoSignatures.isEntityWhitelisted(context, wrUuid)) continue
            val wrAgency = KokohoAgency.fromCode(wr.agencyCode) ?: KokohoAgency.DHS_ICE
            val pointEntity = EvaluatedThreat(
                uuid = wrUuid,
                trackUuid = wrUuid,
                agency = wrAgency,
                label = wrAgency.friendlyLabel,
                posture = PostureColor.YELLOW,
                overallConfidence = 92,
                classificationConfidence = 94,
                spatialConfidence = 90,
                estimatedDistanceM = 120,
                uncertaintyRadiusM = 35,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = wr.notes.ifBlank { "Witness Report (${wrAgency.friendlyLabel})" },
                evidenceSources = listOf(
                    EvidenceBadge("WITNESS_REPORT", "witness:$wrUuid", wr.notes.ifBlank { "Field Witness Report" }, 0.95, wr.updatedAtMs)
                ),
                updatedAtMs = wr.updatedAtMs,
                lat = wr.lat,
                lon = wr.lon,
                isApproximatedPosition = true,
                isPinpointed = true,
                sightingCount = 1
            )
            val highestTitle = KokohoSignatures.formatHighestOrderTitle(pointEntity, wr)
            byUuid[wrUuid] = pointEntity.copy(label = highestTitle)
        }

        return byUuid.values.sortedWith(
            compareByDescending<EvaluatedThreat> { effNow - it.updatedAtMs <= KokohoSignatures.MONITOR_ACTIVE_WINDOW_MS }
                .thenBy { it.agency.priorityRank }
                .thenByDescending { it.updatedAtMs }
        )
    }

    /** Finds an active, historical ledger, or synced Hub [EvaluatedThreat] by its UUID (accepts `"kokoho:<uuid>"` or `<uuid>`). */
    fun findThreatByUuid(context: Context, rawId: String, nowMs: Long? = null): EvaluatedThreat? {
        val id = KokohoSignatures.normalizeEntityId(rawId)
        val snap = evaluate(context, effectiveNowMs(nowMs))
        val active = snap.activeThreats.firstOrNull { it.uuid.equals(id, ignoreCase = true) }
            ?: snap.mapThreats.firstOrNull { it.uuid.equals(id, ignoreCase = true) }
        if (active != null) return active
        val cached = localThreatCache[id] ?: KokohoEntityLedger.findByUuid(context, id)
        if (cached != null) {
            val witness = KokohoSignatures.getWitnessReport(context, cached.uuid, approvedOnly = false)
            return cached.copy(label = KokohoSignatures.formatHighestOrderTitle(cached, witness))
        }
        return allSearchableThreats(context, nowMs).firstOrNull { it.uuid.equals(id, ignoreCase = true) }
    }

    /** Returns all active/map threats for a given [KokohoAgency]. */
    fun findThreatsForAgency(context: Context, agency: KokohoAgency, nowMs: Long? = null): List<EvaluatedThreat> {
        val snap = evaluate(context, effectiveNowMs(nowMs))
        val active = snap.monitorGroups.firstOrNull { it.agency == agency }?.distinctUnits
        if (!active.isNullOrEmpty()) return active
        return snap.mapThreats.filter { it.agency == agency }
    }

    /** Formats staleness age concisely: e.g. `just now`, `25s ago`, `2m ago`, `1h 15m ago`, `5d ago`. */
    fun formatStalenessAge(ageMs: Long): String {
        val sec = max(0L, ageMs / 1000L)
        return when {
            sec < 10L -> "just now"
            sec < 60L -> "${sec}s ago"
            sec < 3600L -> "${sec / 60L}m ago"
            sec < 2L * 86_400L -> {
                val h = sec / 3600L
                val m = (sec % 3600L) / 60L
                if (m > 0) "${h}h ${m}m ago" else "${h}h ago"
            }
            else -> {
                val d = sec / 86_400L
                val h = (sec % 86_400L) / 3600L
                if (d < 7L && h > 0L) "${d}d ${h}h ago" else "${d}d ago"
            }
        }
    }

    fun formatStaleness(nowMs: Long, lastSeenMs: Long): String =
        formatStalenessAge(max(0L, nowMs - lastSeenMs))

    /**
     * Formats center-centroid distance + `(±accuracy)` in parentheses whenever a detection has
     * a known centroid (`lat != null && lon != null`), or exact distance for pinpointed targets,
     * falling back to bare `±accuracy` only when no GPS centroid exists.
     */
    fun formatAccuracyOrEdge(context: Context, threat: EvaluatedThreat): String {
        if (threat.isPinpointed) {
            return formatShortDistance(context, threat.estimatedDistanceM)
        }
        val radStr = formatAccuracyRadius(context, threat.uncertaintyRadiusM)
        return if (threat.lat != null && threat.lon != null) {
            "${formatShortDistance(context, threat.estimatedDistanceM)} ($radStr)"
        } else {
            radStr
        }
    }

    /**
     * Formats distance cleanly without bearing or internal math:
     *  - Imperial (default): "Distance: ~1.2 mi" or "Distance: ~650 ft"
     *  - Metric: "Distance: ~16.8 km" or "Distance: ~250 m"
     */
    fun formatUserDistance(context: Context, distM: Int): String {
        val imperial = KokohoSignatures.isImperialUnits(context)
        return formatDistanceValue(distM, imperial, withPrefix = true)
    }

    fun formatUserDistance(context: Context, nearestEdgeDistM: Number, uncertaintyRadiusM: Int): String {
        val edgeM = nearestEdgeDistM.toInt()
        val radStr = formatAccuracyRadius(context, uncertaintyRadiusM)
        return if (edgeM <= 0) {
            "Accuracy: $radStr"
        } else {
            "${formatUserDistance(context, edgeM)} ($radStr)"
        }
    }

    fun formatShortDistance(context: Context, distM: Int): String {
        val imperial = KokohoSignatures.isImperialUnits(context)
        return formatDistanceValue(distM, imperial, withPrefix = false, symbol = "~")
    }

    /** Formats accuracy radius using the ± symbol (e.g. "±0.6 mi" or "±500 ft"). */
    fun formatAccuracyRadius(context: Context, radiusM: Int): String {
        val imperial = KokohoSignatures.isImperialUnits(context)
        return formatDistanceValue(radiusM, imperial, withPrefix = false, symbol = "±")
    }

    fun formatDistanceValue(distM: Int, imperial: Boolean, withPrefix: Boolean, symbol: String = "~"): String {
        val prefix = if (withPrefix) "Distance: " else ""
        return if (imperial) {
            val miles = distM / 1609.344
            if (miles >= 0.25) {
                "${prefix}${symbol}${"%.1f".format(Locale.US, miles)} mi"
            } else {
                val feet = ((distM * 3.28084 / 50.0).roundToInt() * 50).coerceAtLeast(50)
                "${prefix}${symbol}$feet ft"
            }
        } else {
            if (distM >= 1000) {
                "${prefix}${symbol}${"%.1f".format(Locale.US, distM / 1000.0)} km"
            } else {
                val meters = ((distM / 10.0).roundToInt() * 10).coerceAtLeast(10)
                "${prefix}${symbol}$meters m"
            }
        }
    }

    private fun estimateRfDistanceMeters(snrDb: Int, role: String): Int {
        val baseM = when (role.uppercase()) {
            "AIR_ASSET" -> 12000.0 * 10.0.pow(-(snrDb - 10) / 30.0)
            "MOBILE_INPUT" -> 1200.0 * 10.0.pow(-(snrDb - 10) / 22.0)
            else -> 1600.0 * 10.0.pow(-(snrDb - 10) / 22.0)
        }
        return baseM.roundToInt().coerceIn(35, 18_000)
    }

    private fun estimateRssiDistanceMeters(rssiDbm: Int): Int {
        val txPowerAt1m = -42.0
        val pathLossExp = 2.7
        val d = 10.0.pow((txPowerAt1m - rssiDbm) / (10.0 * pathLossExp))
        return d.roundToInt().coerceIn(10, 800)
    }

    private fun isHeadingTowardUser(
        userLat: Double?,
        userLon: Double?,
        objLat: Double,
        objLon: Double,
        objBearingDeg: Double?,
        objVelocityMps: Double?
    ): Boolean {
        if ((objVelocityMps ?: 0.0) < 1.2) return false
        if (userLat == null || userLon == null || objBearingDeg == null) {
            return (objVelocityMps ?: 0.0) >= 2.0
        }
        val bearingObjToUser = bearingDeg(objLat, objLon, userLat, userLon)
        val diff = angularDiffDeg(objBearingDeg, bearingObjToUser)
        return diff <= 45.0
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angularDiffDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
