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
     * Effective proximity metric (meters) used for sorting and display:
     *  - Pinpointed aircraft / drone: exact distance `estimatedDistanceM`
     *  - Displaced circle outside user: nearest edge distance `nearestEdgeDistanceM` (or `uncertaintyRadiusM` if user is inside)
     *  - User-centered circle: `uncertaintyRadiusM`
     */
    val effectiveProximityM: Int
        get() = when {
            isPinpointed -> estimatedDistanceM.coerceAtLeast(10)
            nearestEdgeDistanceM > 0 -> nearestEdgeDistanceM
            else -> uncertaintyRadiusM.coerceAtLeast(20)
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
     * e.g. `x2 · ±650 ft · +1 (~0.5 mi) · 1m ago`
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
                val distStr = KokohoPostureEngine.formatShortDistance(context, leadUnit.estimatedDistanceM)
                if (leadUnit.isCircling) "$distStr · Circling" else distStr
            }
            primaryEdgeDistanceM > 0 -> {
                "${KokohoPostureEngine.formatShortDistance(context, primaryEdgeDistanceM)} (${KokohoPostureEngine.formatAccuracyRadius(context, primaryRadiusM)})"
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
    /** Active threats within the 3-minute Monitor window, sorted by priorityRank. */
    val activeThreats: List<EvaluatedThreat>,
    /** Grouped agencies within the 3-minute Monitor window, sorted strictly by priorityRank. */
    val monitorGroups: List<AgencyMonitorGroup>,
    /** Retained threats within the Map visibility window (30m solid + 2h fade = 2.5h). */
    val mapThreats: List<EvaluatedThreat>,
    val enabledScopeText: String,
    val sectorTempoText: String? = null
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
        val obsTimestampMs: Long = 0L
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

    private val spatialHistory = ConcurrentHashMap<String, MutableList<SpatialSample>>()
    private val triangulationCache = ConcurrentHashMap<String, CachedTriangulation>()
    private val coMovingState = ConcurrentHashMap<String, CoMovingTrackState>()
    private val localThreatCache = ConcurrentHashMap<String, EvaluatedThreat>()
    private val lastSharedAtByUuid = ConcurrentHashMap<String, Long>()
    private val lastRawReportByFacet = ConcurrentHashMap<String, LastRawReportState>()
    @Volatile
    private var lastPostureColor: PostureColor = PostureColor.GREEN
    @Volatile
    private var lastEvaluateNowMs: Long = 0L
    @Volatile
    private var latestSnapshot: PostureSnapshot? = null
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

    /** Minimum uncertainty radius (meters) — 200 ft ≈ 61 m — before a wide uncorroborated circle is jittered. */
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
        KokohoEntityLedger.clearAll()
        lastSharedAtByUuid.clear()
        lastRawReportByFacet.clear()
        lastPostureColor = PostureColor.GREEN
        lastEvaluateNowMs = 0L
        latestSnapshot = null
    }

    fun clearRamOnlyForTest() {
        spatialHistory.clear()
        triangulationCache.clear()
        coMovingState.clear()
        localThreatCache.clear()
        lastEvaluateNowMs = 0L
        latestSnapshot = null
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

    /** Clears transient local RF/P25 threats when the RTL-SDR hardware stops or is unplugged. */
    fun onSdrDisconnected() {
        localThreatCache.entries.removeIf { (_, t) ->
            t.evidenceSources.all { it.kind.startsWith("RF_") || it.kind == "P25_UNIT" }
        }
        lastEvaluateNowMs = 0L
        latestSnapshot = null
    }

    private fun resolveUserLocation(context: Context): android.location.Location? {
        ScanForegroundService.lastFix?.let { return it }
        val now = System.currentTimeMillis()
        if (now - cachedFallbackLocationAtMs < 10_000L) {
            return cachedFallbackLocation
        }
        cachedFallbackLocationAtMs = now
        val lm = runCatching {
            context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        }.getOrNull() ?: return null
        val resolved = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER
        ).mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .filter { now - it.time <= 15 * 60_000L }
            .maxByOrNull { it.time }
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

    private fun shouldEvictLocalCache(
        t: EvaluatedThreat,
        nowMs: Long,
        mapVisibilityMs: Long,
        groundedHexes: Set<String> = emptySet()
    ): Boolean {
        val ageMs = nowMs - t.updatedAtMs
        if (ageMs > mapVisibilityMs) return true
        if (t.isAircraft) {
            if (t.aircraftHex?.uppercase() in groundedHexes) return true
            return ageMs > min(mapVisibilityMs, LIVE_AIRCRAFT_MAX_AGE_MS)
        }
        val rfOnly = t.evidenceSources.isNotEmpty() &&
            t.evidenceSources.all { it.kind.startsWith("RF_") || it.kind == "P25_UNIT" }
        if (rfOnly && !t.isApproximatedPosition) {
            return ageMs > min(mapVisibilityMs, LOCAL_RF_ONLY_CACHE_TTL_MS)
        }
        val ttl = if (t.isApproximatedPosition) min(mapVisibilityMs, 24 * 3600_000L) else min(mapVisibilityMs, LOCAL_SENSOR_CACHE_TTL_MS)
        return ageMs > ttl
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
                    list.add(SpatialSample(gpsLat, gpsLon, signalDb, estDistanceM, nowMs))
                    if (list.size > 32) list.removeAt(0)
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
                val effDist = if (obsIsFresh && distFromUser < cached.result.estimatedDistanceM) {
                    cached.result.estimatedDistanceM
                } else {
                    max(distFromUser, cached.result.uncertaintyRadiusM.coerceAtLeast(15))
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
                val distFromUser = max(haversineMeters(curUserLat, curUserLon, liveLat, liveLon).roundToInt(), liveRadiusM)
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
            // so distant tail samples never create synthetic multi-kilometer 2D intersections.
            val localSamples = list.filter { haversineMeters(best.lat, best.lon, it.lat, it.lon) <= 450.0 }.ifEmpty { listOf(best) }
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
                    val widenedRad = if (shiftM > max(40.0, prevRad * 0.70)) {
                        max(convergedPeakRadiusM, min(prevRad + 25, (shiftM * 0.8).roundToInt()))
                    } else {
                        min(prevRad, convergedPeakRadiusM)
                    }
                    Triple(prevCenterLat, prevCenterLon, widenedRad)
                } else {
                    Triple(anchorCandidateLat, anchorCandidateLon, convergedPeakRadiusM)
                }
                val distFromUserToAnchor = haversineMeters(curUserLat, curUserLon, anchorLat, anchorLon).roundToInt()
                val effDistM = if (obsIsFresh && distFromUserToAnchor < latest.estDistanceM) {
                    latest.estDistanceM
                } else {
                    max(distFromUserToAnchor, settledRadiusM)
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
            val distFromUserM = haversineMeters(curUserLat, curUserLon, approxLat, approxLon).roundToInt().coerceAtLeast(15)

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
            // observations depart sharply in strength (>= 9 dB) or derived shift (> max(50m, 1.2 * r_settled)).
            val prevSettled = cached?.result
            if (prevSettled != null && prevSettled.centerLat != null && prevSettled.centerLon != null && approxRadiusM > prevSettled.uncertaintyRadiusM) {
                val shiftThresholdM = max(50.0, prevSettled.uncertaintyRadiusM * 1.2)
                val recentTail = list.takeLast(3)
                val sustainedDeparture = recentTail.size >= 3 && recentTail.all { s ->
                    abs(s.signalDb - cached.peakSignalDb) >= 9 ||
                        haversineMeters(s.lat, s.lon, prevSettled.centerLat, prevSettled.centerLon) > shiftThresholdM
                }
                if (!sustainedDeparture) {
                    val keptDistM = max(
                        haversineMeters(curUserLat, curUserLon, prevSettled.centerLat, prevSettled.centerLon).roundToInt(),
                        prevSettled.uncertaintyRadiusM.coerceAtLeast(15)
                    )
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

    fun evaluate(context: Context, nowMs: Long = System.currentTimeMillis()): PostureSnapshot {
        KokohoSignatures.ensureLoaded(context)
        val alarmEnabled = KokohoSignatures.getEnabledAgencies(context)
        val mapVisible = KokohoSignatures.getMapVisibleAgencies(context)
        val enabled = alarmEnabled + mapVisible
        val activeMapWindow = KokohoSignatures.getMapTimeWindow(context)
        val mapVisibilityMs = activeMapWindow.windowMs
        val monitorWindowMs = KokohoSignatures.MONITOR_ACTIVE_WINDOW_MS
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
        localThreatCache.entries.removeIf { shouldEvictLocalCache(it.value, nowMs, mapVisibilityMs, groundedHexes) }

        // 1. Evaluate remote sanitized ThreatObjects from Auxnet kokoho-hub
        if (auxnetOn) {
            for (remote in KokohoHubClient.activeObjects(nowMs, mapVisibilityMs)) {
                if (KokohoSignatures.isFlockCamera(remote.uuid, remote.classification.label) ||
                    remote.classification.evidenceSources.any { KokohoSignatures.isFlockCamera(it.facetKey, it.detail) }
                ) {
                    continue
                }
                val isAir = remote.location.propagationClass.equals("AIR_TO_GROUND", ignoreCase = true) ||
                    remote.location.propagationClass.equals("AIRBORNE", ignoreCase = true) ||
                    remote.classification.evidenceSources.any { it.kind.contains("ADSB", ignoreCase = true) }
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

                // Skip remote repeater-only artifacts
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

                val effectiveDistM = if (hasUserLoc) max(edgeDistM, uncertM) else 9_999
                val posture = when {
                    !hasUserLoc -> PostureColor.YELLOW
                    effectiveDistM <= 300 -> PostureColor.RED
                    headingTowardUser || effectiveDistM <= 850 -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }

                val badges = remote.classification.evidenceSources.map {
                    EvidenceBadge(
                        kind = it.kind,
                        facetKey = it.facetKey,
                        detail = it.detail,
                        weight = it.weight,
                        lastSeenMs = it.lastSeenMs
                    )
                }

                var remoteOpLat: Double? = null
                var remoteOpLon: Double? = null
                var remoteIsDrone = agency == KokohoAgency.DRONE
                for (b in badges) {
                    if (b.facetKey.startsWith("uas:mac:", ignoreCase = true)) remoteIsDrone = true
                    if (b.facetKey.startsWith("uas:op:", ignoreCase = true)) {
                        remoteIsDrone = true
                        val parts = b.facetKey.substringAfter("uas:op:", "").split(',')
                        if (parts.size == 2) {
                            val olat = parts[0].trim().toDoubleOrNull()
                            val olon = parts[1].trim().toDoubleOrNull()
                            if (olat != null && olon != null && olat in -90.0..90.0 && olon in -180.0..180.0) {
                                remoteOpLat = olat
                                remoteOpLon = olon
                            }
                        }
                    }
                }

                val classConf = (remote.confidence.classification * 100).roundToInt().coerceIn(1, 99)
                val spatConf = (remote.confidence.spatial * 100).roundToInt().coerceIn(1, 99)
                val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

                val remoteSightings = max(remote.sightingCount, remote.corroborationCount).coerceAtLeast(1)
                val isRemoteDerived = isAir ||
                    remote.corroborationCount >= 2 ||
                    remote.sightingCount >= 2 ||
                    uncertM <= WIDE_CIRCLE_JITTER_MIN_M

                val remoteHeli = isAir && KokohoSignatures.isHelicopterAirframe(
                    null,
                    remote.classification.category,
                    "${remote.classification.label} ${badges.joinToString(" ") { it.detail }}",
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
                    uncertaintyRadiusM = uncertM,
                    bearingDeg = bearingFromUser,
                    closingVelocityMps = remote.location.velocityMps,
                    recurrenceSummary = remote.classification.recurrenceSummary,
                    evidenceSources = badges,
                    updatedAtMs = remote.updatedAtMs,
                    lat = remote.location.lat,
                    lon = remote.location.lon,
                    isApproximatedPosition = isRemoteDerived,
                    isPinpointed = isAir || uncertM <= PINPOINT_POINT_RADIUS_M,
                    isAircraft = isAir,
                    isHelicopter = remoteHeli,
                    aircraftTrackDeg = remote.location.bearingDeg,
                    aircraftHex = remoteHex,
                    isDrone = remoteIsDrone,
                    operatorLat = remoteOpLat,
                    operatorLon = remoteOpLon,
                    hardwareIdentifier = badges.firstOrNull()?.facetKey,
                    sightingCount = remoteSightings,
                    capabilities = KokohoSignatures.inferCapabilitiesFromEvidence(
                        badges.map { it.facetKey },
                        badges.map { it.detail } + remote.classification.label
                    )
                )
            }
        }

        // 2. Evaluate local RTL-SDR RF hits, Pro/Enterprise/RemoteID Drones, and close-range Fleet OUI devices
        val rfWindowMs = min(mapVisibilityMs, LOCAL_RF_ONLY_CACHE_TTL_MS)
        val devWindowMs = min(mapVisibilityMs, LOCAL_SENSOR_CACHE_TTL_MS)
        val triDecayMs = min(mapVisibilityMs, LOCAL_TRIANGULATION_DECAY_MS)
        val localRfHits = RadioLog.current(nowMs).filter { nowMs - it.lastSeen <= rfWindowMs }
        val localDevices = DeviceRegistry.snapshot(nowMs).filter { nowMs - it.lastSeen <= devWindowMs }

        // 2a. Pro / Enterprise / Military / Remote ID Drones (#24)
        if (KokohoSignatures.isDroneDetectionEnabled(context)) {
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
                val altFtAgl = altMeters?.let { (it * 3.28084).roundToInt().coerceAtLeast(0) }

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
                if (droneLat != null && droneLon != null) {
                    localRawObservations += RawSensorReportCandidate(
                        facetKey = facetKey,
                        rawLat = droneLat,
                        rawLon = droneLon,
                        signalDb = snap.rssi.toDouble(),
                        modality = if (rid != null) "REMOTE_ID" else "TACTICAL_DRONE_OUI",
                        additionalFacets = badges.drop(1).map { it.facetKey },
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
            val isGenericRouter: Boolean = false
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
            val isStaticBaseline = KokohoSignatures.isStaticBaselineRouter(context, normMac) && !isWatchedGroup
            if (isStaticBaseline) {
                conf = min(conf, 35)
            } else if (KokohoSignatures.isLowConfidenceDefaultRouterSsid(snap.name)) {
                conf = min(conf, 45)
            } else if (KokohoSignatures.isHighValueUnitSsid(snap.name)) {
                conf = min(96, conf + 18)
            } else if (isGenericRouter && learnedMac == null) {
                // Uncorroborated generic fleet router (e.g., commercial/trooper Cradlepoint): stay soft <= 58% YELLOW until corroborated
                conf = min(conf, 58)
            }
            val is5Ghz = (snap.advert?.wifi?.frequencyMhz ?: 0) >= 5000
            val strongEnough = snap.rssi >= -86 ||
                (snap.sightings >= 2 && snap.bestRssi >= -89) ||
                (is5Ghz && snap.bestRssi >= -89)
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
                isGenericRouter = isGenericRouter
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
                    isGenericRouter = group.all { it.isGenericRouter }
                )
            }

        data class AttestedSignalRef(
            val agency: KokohoAgency,
            val ts: Long,
            val lat: Double?,
            val lon: Double?
        )

        fun resolveRfCoords(rf: RadioLog.Entry): Pair<Double?, Double?> {
            val freshUserLoc = userLoc?.takeIf { abs(nowMs - rf.lastSeen) <= 5_000L }
            val rLat = rf.lastLat ?: rf.peakLat ?: freshUserLoc?.latitude
            val rLon = rf.lastLon ?: rf.peakLon ?: freshUserLoc?.longitude
            return rLat to rLon
        }

        fun spatiallyCompatible(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?, maxDistM: Double): Boolean {
            if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return true
            return haversineMeters(lat1, lon1, lat2, lon2) <= maxDistM
        }

        // Pre-scan explicit cataloged RF hits (with true sighting coordinates) to know whether DHS is the attested primary presence at this scene
        val explicitCatalogRfByTime = localRfHits.mapNotNull { rf ->
            KokohoSignatures.matchFrequency(rf.freqHz, snrDb = rf.peakSnrDb)?.takeIf { it.isAlertableDirectOrMobile }?.let { sig ->
                val (rLat, rLon) = resolveRfCoords(rf)
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
                val (rLat, rLon) = resolveRfCoords(rf)
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
            val tri = recordAndTriangulate(
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
            if (obsLat != null && obsLon != null) {
                ouiLatestObsCoords[uuid] = obsLat to obsLon
            }

            // Stream genuine RAW local sensor position & RSSI (strictly anchored to the sighting's own GPS, never moving userLoc!):
            val staticUnconfirmed = KokohoSignatures.isStaticBaselineRouter(context, oui.mac) && oui.confidence <= 40
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

        // 2d. Deterministic Companion Kit Evaluation (`hw:kit:bwc:*`, `hw:kit:printer:*`, `hw:kit:radio:*`):
        //     - Suppresses fixed `[ESS]` room/WPA-TKIP/long-uptime infrastructure APs (e.g. "101" room APs).
        //     - Matches raw BLE Company IDs (`0x034D` Axon/TASER, `0x04EC` Motorola, `0x01F1` Zebra) & 16-bit Service UUIDs.
        //     - Strict deterministic entity correlation: merges into a router entity ONLY on exact MAC, exact non-generic SSID,
        //       or matching adjusted 802.11 TSF boot epoch (`|ΔupSince| <= 2000ms`) — never speculative spatial assumptions!
        data class KitEntry(
            val capLabel: String,
            val isBodycam: Boolean,
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
            if (snap.rssi < -88) continue
            if (KokohoSignatures.isFixedRoomOrBuildingWifiAp(snap)) continue
            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            if (KokohoSignatures.isAxonNetworksConsumerRouter(normMac, snap.name, snap.vendor)) continue
            val bleKind = KokohoSignatures.classifyBleTacticalPayload(snap.advert)
            if (bleKind == null && !KokohoSignatures.isCompanionKitDevice(normMac, snap.name, snap.vendor, snap.advert)) continue
            val isPrinter = bleKind == KokohoSignatures.BleTacticalKind.ZEBRA_PRINTER ||
                KokohoSignatures.isZebraCitationPrinter(normMac, snap.name, snap.vendor)
            val isPortableRadio = bleKind == KokohoSignatures.BleTacticalKind.MOTOROLA_RADIO ||
                (!isPrinter && Regex("(?i)\\b(MOTOROLA\\s+SOLUTIONS|APX|XL-?200P|XL-?185P)\\b").containsMatchIn("${snap.name.orEmpty()} ${snap.vendor.orEmpty()}"))
            val isBodycam = !isPrinter && !isPortableRadio && (
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
                else -> "hw:kit:acc"
            }
            val capLabel = when {
                isBodycam -> "Bodycam"
                isPrinter -> "Citation Printer"
                isPortableRadio -> "Portable Radio"
                else -> "Tactical Kit"
            }
            val kindTag = when {
                isBodycam -> "COMPANION_BODY_CAM"
                isPrinter -> "COMPANION_PRINTER"
                isPortableRadio -> "COMPANION_RADIO"
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
                if (!entry.isBodycam) {
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
                val tri = recordAndTriangulate(
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

        fun deduplicateRemoteRfEchoes(
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

        val dodTrunkActive = RadioLog.activeDodTrunkChannelCount(nowMs) >= 3

        // 3. Evaluate local RTL-SDR RF Hits — suppress wide-area non-localizing emissions along user travel,
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
                ?: continue
            if (!sig.isAlertableDirectOrMobile) continue

            val (rfObsLat, rfObsLon) = resolveRfCoords(rf)
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
            val rfBadge = EvidenceBadge(
                kind = "RF_${sig.mode}",
                facetKey = facetKey,
                detail = "${sig.alpha} (${"%.4f".format(Locale.US, rf.freqHz / 1e6)} MHz · ${rf.peakSnrDb} dB SNR)",
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
                deduplicateRemoteRfEchoes(colocatedUuid, rf.freqHz, rfObsLat, rfObsLon, rf.lastSeen)
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
            }
            deduplicateRemoteRfEchoes(uuid, rf.freqHz, rfAnchorLat, rfAnchorLon, rf.lastSeen)
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

        // 5. Evaluate ADS-B Aircraft & Unlisted Circling Spy Planes (grounded, gliders, and military excluded)
        if (KokohoSignatures.isAdsbEnabled(context)) {
            val enabledAirAgencies = KokohoSignatures.getEnabledAircraftAgencies(context)
            for (seen in PoliceAircraft.all(nowMs)) {
                val p = seen.plane
                val hit = seen.hit
                if (PoliceAircraft.isGroundedOrParked(p) || PoliceAircraft.isGliderOrExcludedTrainer(p)) continue
                val isCircling = hit?.label == PoliceAircraft.CIRCLING ||
                    hit?.evidence?.contains("circling", ignoreCase = true) == true
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

                val facetKey = "adsb:icao:${p.hex.uppercase()}"
                val uuid = deterministicUuidV5(facetKey)
                val distM = if (userLoc != null) {
                    haversineMeters(userLoc.latitude, userLoc.longitude, p.lat, p.lon).roundToInt()
                } else {
                    4000
                }
                val velMps = p.speedKt?.let { it * 0.514444 }
                val headingToward = isHeadingTowardUser(
                    userLat = userLoc?.latitude,
                    userLon = userLoc?.longitude,
                    objLat = p.lat,
                    objLon = p.lon,
                    objBearingDeg = p.trackDeg,
                    objVelocityMps = velMps
                )
                val isUnattributedCircling = isCircling && hit?.label == PoliceAircraft.CIRCLING
                val posture = when {
                    isUnattributedCircling && distM <= 3_500 -> PostureColor.RED
                    isUnattributedCircling && distM <= 7_000 -> PostureColor.ORANGE
                    isUnattributedCircling -> PostureColor.YELLOW
                    isCircling && distM <= 8_000 -> PostureColor.RED
                    distM <= 2_000 -> PostureColor.RED
                    isCircling || headingToward || distM <= 6_000 -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }
                val classConf = if (isUnattributedCircling) {
                    (hit?.confidence ?: 55).coerceIn(45, 68)
                } else {
                    hit?.confidence?.coerceIn(80, 99) ?: 92
                }
                val spatConf = if (isUnattributedCircling) 68 else 98
                val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
                val identStr = listOfNotNull(
                    p.registration?.takeIf { it.isNotBlank() },
                    p.callsign?.takeIf { it.isNotBlank() },
                    "ICAO ${p.hex.uppercase()}"
                ).distinct().joinToString(" · ")
                val seenAtMs = max(PoliceAircraft.localAt, PoliceAircraft.latestAt).takeIf { it > 0L } ?: nowMs
                org.tucsoncommunity.kokoho.online.PoliceAircraft.recordAirborneFix(p.hex, p.lat, p.lon, seenAtMs)
                val isHeli = KokohoSignatures.isHelicopterAirframe(
                    p.type,
                    p.category,
                    "${hit?.label.orEmpty()} ${p.owner.orEmpty()} ${p.registration.orEmpty()}",
                    agency = if (isUnattributedCircling && !p.type.isNullOrBlank()) null else agency,
                    hex = p.hex
                )
                val altSuffix = p.altitudeFt?.let { " · $it ft" }.orEmpty()
                val airSummary = when {
                    isUnattributedCircling -> "Unattributed Circling Aircraft — Verify ($identStr)"
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
                    uncertaintyRadiusM = 25,
                    bearingDeg = null,
                    closingVelocityMps = velMps,
                    recurrenceSummary = airSummary,
                    evidenceSources = listOf(
                        EvidenceBadge(
                            kind = "ADSB_AIR",
                            facetKey = facetKey,
                            detail = "${hit?.label ?: agency.friendlyLabel} ($identStr$altSuffix)",
                            weight = if (isUnattributedCircling) 0.55 else 0.95,
                            lastSeenMs = seenAtMs
                        )
                    ),
                    updatedAtMs = seenAtMs,
                    lat = p.lat,
                    lon = p.lon,
                    isApproximatedPosition = true,
                    isPinpointed = true,
                    isAircraft = true,
                    isHelicopter = isHeli,
                    aircraftTrackDeg = p.trackDeg,
                    aircraftHex = p.hex.uppercase(),
                    isCircling = isCircling,
                    altitudeFtAgl = p.altitudeFt,
                    hardwareIdentifier = identStr
                )
                upsertAndFuseThreat(evaluated, threat)
                localRawObservations += RawSensorReportCandidate(
                    facetKey = facetKey,
                    rawLat = p.lat,
                    rawLon = p.lon,
                    signalDb = 42.0,
                    modality = "ADSB",
                    additionalFacets = emptyList(),
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

        // 6a. Persist fresh LOCAL located entities into the durable 48h SQLite ledger and hydrate rolling-window entities
        for (fresh in evaluated.values) {
            if (!fresh.isAircraft && fresh.lat != null && fresh.lon != null && localThreatCache.containsKey(fresh.uuid)) {
                KokohoEntityLedger.upsert(context, fresh, nowMs)
            }
        }
        for (persisted in KokohoEntityLedger.getWithinRollingWindow(context, nowMs, mapVisibilityMs)) {
            if (persisted.uuid !in evaluated && persisted.agency in enabled) {
                localThreatCache.putIfAbsent(persisted.uuid, persisted)
            }
        }
        // Hydrate dropped-point Witness Reports within the rolling window
        for (wr in KokohoSignatures.getAllWitnessReports(context)) {
            if (wr.reportKind != "LE" || wr.lat == null || wr.lon == null) continue
            if (nowMs - wr.updatedAtMs > mapVisibilityMs) continue
            val wrUuid = KokohoSignatures.normalizeEntityId(wr.entityUuid ?: wr.targetKey)
            if (wrUuid in evaluated) continue
            val wrAgency = KokohoAgency.fromCode(wr.agencyCode) ?: KokohoAgency.DHS_ICE
            if (wrAgency !in enabled) continue
            val distM = if (userLoc != null) {
                haversineMeters(userLoc.latitude, userLoc.longitude, wr.lat, wr.lon).roundToInt()
            } else 120
            val pointEntity = EvaluatedThreat(
                uuid = wrUuid,
                trackUuid = wrUuid,
                agency = wrAgency,
                label = wrAgency.friendlyLabel,
                posture = if (nowMs - wr.updatedAtMs <= monitorWindowMs && distM <= 300) PostureColor.RED else PostureColor.YELLOW,
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

        // 6b. Include retained local detections within the rolling window (recomputing true distance from current userLoc!)
        localThreatCache.entries.removeIf { shouldEvictLocalCache(it.value, nowMs, mapVisibilityMs, groundedHexes) }
        for ((uuid, cached) in localThreatCache) {
            if (uuid !in evaluated && !shouldEvictLocalCache(cached, nowMs, mapVisibilityMs, groundedHexes)) {
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
                    val refreshed = if (!cached.isAircraft && userLoc != null && cached.lat != null && cached.lon != null) {
                        val liveDistM = max(
                            haversineMeters(userLoc.latitude, userLoc.longitude, cached.lat, cached.lon).roundToInt(),
                            cached.uncertaintyRadiusM.coerceAtLeast(15)
                        )
                        val refreshedPosture = if (nowMs - cached.updatedAtMs > 20_000L && liveDistM > 120) {
                            PostureColor.YELLOW
                        } else {
                            cached.posture
                        }
                        cached.copy(estimatedDistanceM = liveDistM, posture = refreshedPosture)
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
                        deduplicateRemoteRfEchoes(uuid, cachedRfChan, refreshed.lat, refreshed.lon, refreshed.updatedAtMs)
                    }
                }
            }
        }

        // Apply Witness Reports + capabilities + dynamic epistemic titles (`Suspected` / `Likely` / `Confirmed`), excluding locally whitelisted entities
        val allRetained = evaluated.values
            .filter { !KokohoSignatures.isEntityWhitelisted(context, it.uuid) }
            .map { t ->
                val witness = KokohoSignatures.getWitnessReport(context, t.uuid, approvedOnly = false)
                val effectiveAgency = witness?.takeIf { it.reportKind == "LE" }?.agencyCode?.let { KokohoAgency.fromCode(it) } ?: t.agency
                val inferredCaps = (t.capabilities + KokohoSignatures.inferCapabilitiesFromEvidence(
                    t.evidenceSources.map { it.facetKey },
                    t.evidenceSources.map { it.detail } + listOf(t.recurrenceSummary, t.label)
                )).distinct()
                val highestOrderTitle = KokohoSignatures.formatHighestOrderTitle(t.copy(agency = effectiveAgency, capabilities = inferredCaps), witness)
                val updated = t.copy(
                    agency = effectiveAgency,
                    label = highestOrderTitle,
                    recurrenceSummary = if (t.recurrenceSummary.isBlank()) highestOrderTitle else t.recurrenceSummary,
                    capabilities = inferredCaps
                )
                if (localThreatCache.containsKey(t.uuid)) {
                    localThreatCache[t.uuid] = updated
                }
                updated
            }

        // Map threats: filtered by Eye (`mapVisible`) and projected onto the active rolling window (`30m` | `3h` | `24h`)
        val mapThreats = allRetained
            .filter { t ->
                when {
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

        // Monitor threats: strictly filtered by Alarm (`alarmEnabled`) within the 3-minute Monitor window,
        // sorted strictly by agency priorityRank (ICE, DHS Air, CBP, HSI, Other Fed, Sheriff/TPD/Drone)
        val monitorThreats = allRetained
            .filter { t ->
                (nowMs - t.updatedAtMs <= monitorWindowMs) && (t.isAircraft || t.agency in alarmEnabled)
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

        // Real-time upload of FLAGGED local sensor observations to volatile RoomEngine for quick inference & recomputed SSE broadcast.
        // Passive detections (isFlagged == false) are never posted individually; they are bulk-posted via FieldTelemetryLogger (/v1/telemetry/batch).
        if (ScanForegroundService.isRunning && KokohoSignatures.isSharingActive(context) && localRawObservations.isNotEmpty()) {
            val bestByFacet = linkedMapOf<String, RawSensorReportCandidate>()
            for (cand in localRawObservations) {
                if (!cand.isFlagged) continue
                val existing = bestByFacet[cand.facetKey]
                if (existing == null || cand.signalDb > existing.signalDb) {
                    bestByFacet[cand.facetKey] = cand
                }
            }
            for (cand in bestByFacet.values) {
                val prev = lastRawReportByFacet[cand.facetKey]
                // Never re-upload a silent cached facet along the user's moving GPS track: require a genuinely newer observation timestamp!
                if (prev != null && cand.obsTimestampMs > 0L && cand.obsTimestampMs <= prev.lastObsTsMs) continue
                val elapsed = if (prev != null) nowMs - prev.atMs else Long.MAX_VALUE
                val movedEnough = prev != null && haversineMeters(prev.lat, prev.lon, cand.rawLat, cand.rawLon) >= 25.0
                val signalChanged = prev != null && abs(cand.signalDb - prev.signalDb) >= 6.0
                val shouldSend = prev == null || elapsed >= 30_000L || (elapsed >= 8_000L && (movedEnough || signalChanged))
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
                sectorTempoText = sectorTempo
            ).also { latestSnapshot = it }
        }

        // Overall screen posture color reflects the highest severity among active 3-minute monitor threats
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
            sectorTempoText = sectorTempo
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
                val baseRadius = lead.uncertaintyRadiusM.coerceAtLeast(25)
                // Split units into primary accuracy cluster vs incrementally wider accuracy radii (> 1.8x wider and >= +300m)
                val primaryCluster = ordered.filter {
                    it.uncertaintyRadiusM <= max(baseRadius * 1.8, (baseRadius + 300).toDouble()).roundToInt()
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
                    widerRadiusM = widerCluster.firstOrNull()?.uncertaintyRadiusM,
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

    /** Finds an active or recently cached [EvaluatedThreat] by its UUID (accepts `"kokoho:<uuid>"` or `<uuid>`). */
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
        return null
    }

    /** Returns all active/map threats for a given [KokohoAgency]. */
    fun findThreatsForAgency(context: Context, agency: KokohoAgency, nowMs: Long? = null): List<EvaluatedThreat> {
        val snap = evaluate(context, effectiveNowMs(nowMs))
        val active = snap.monitorGroups.firstOrNull { it.agency == agency }?.distinctUnits
        if (!active.isNullOrEmpty()) return active
        return snap.mapThreats.filter { it.agency == agency }
    }

    /** Formats staleness age concisely: e.g. `just now`, `25s ago`, `2m ago`, `1h 15m ago`. */
    fun formatStalenessAge(ageMs: Long): String {
        val sec = max(0L, ageMs / 1000L)
        return when {
            sec < 10L -> "just now"
            sec < 60L -> "${sec}s ago"
            sec < 3600L -> "${sec / 60L}m ago"
            else -> {
                val h = sec / 3600L
                val m = (sec % 3600L) / 60L
                if (m > 0) "${h}h ${m}m ago" else "${h}h ago"
            }
        }
    }

    fun formatStaleness(nowMs: Long, lastSeenMs: Long): String =
        formatStalenessAge(max(0L, nowMs - lastSeenMs))

    /** Formats either nearest-edge distance + ±radius or ±radius when inside the confidence circle. */
    fun formatAccuracyOrEdge(context: Context, threat: EvaluatedThreat): String {
        if (threat.isPinpointed) {
            return formatShortDistance(context, threat.estimatedDistanceM)
        }
        val edgeM = threat.nearestEdgeDistanceM
        val radStr = formatAccuracyRadius(context, threat.uncertaintyRadiusM)
        return if (edgeM > 0) {
            "${formatShortDistance(context, edgeM)} ($radStr)"
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
