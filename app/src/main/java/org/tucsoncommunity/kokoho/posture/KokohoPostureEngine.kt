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
    val aircraftTrackDeg: Double? = null,
    val aircraftHex: String? = null,
    val isCircling: Boolean = false,
    /** True if this threat is a Pro / Enterprise / Military / Remote ID drone. */
    val isDrone: Boolean = false,
    val altitudeFtAgl: Int? = null,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val hardwareIdentifier: String? = null,
    val sightingCount: Int = 1
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
     *  - Before 2D wide-baseline triangulation (`!isApproximatedPosition`): circle centered at user GPS with `radius = estimatedDistanceM`.
     *  - After triangulation (`isApproximatedPosition`): circle centered at `(lat, lon)` with `radius = uncertaintyRadiusM`.
     */
    val mapCircleRadiusM: Int
        get() = if (isApproximatedPosition) uncertaintyRadiusM.coerceAtLeast(20) else estimatedDistanceM.coerceAtLeast(35)
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
    val enabledScopeText: String
)

object KokohoPostureEngine {

    private const val COLOCATION_WINDOW_MS = 60_000L
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
        val result: TriangulationResult
    )

    private val spatialHistory = ConcurrentHashMap<String, MutableList<SpatialSample>>()
    private val triangulationCache = ConcurrentHashMap<String, CachedTriangulation>()
    private val localThreatCache = ConcurrentHashMap<String, EvaluatedThreat>()
    private val lastSharedAtByUuid = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastPostureColor: PostureColor = PostureColor.GREEN
    @Volatile
    private var lastEvaluateNowMs: Long = 0L

    /** Minimum uncertainty radius (meters) — 200 ft ≈ 61 m — before a wide uncorroborated circle is jittered. */
    const val WIDE_CIRCLE_JITTER_MIN_M = 61

    /**
     * Deterministically offsets a wide (> 200 ft / 61 m) uncorroborated observation by 45% of its
     * estimated radius along a stable azimuth derived from [uuid] so raw unjittered user device
     * coordinates are never plotted or shared for wide single-hit circles.
     * Circles <= 200 ft (61 m) are returned untouched.
     */
    fun deriveJitteredCenter(uuid: String, lat: Double, lon: Double, radiusM: Int): Pair<Double, Double> {
        if (radiusM <= WIDE_CIRCLE_JITTER_MIN_M) return lat to lon
        val h = uuid.hashCode()
        val angleRad = Math.toRadians(((h and 0x7FFFFFFF) % 360).toDouble())
        val offsetM = (radiusM * 0.45).coerceIn(65.0, 320.0)
        val dLat = (offsetM * cos(angleRad)) / 111_132.0
        val dLon = (offsetM * sin(angleRad)) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return (lat + dLat) to (lon + dLon)
    }

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
        localThreatCache.clear()
        lastPostureColor = PostureColor.GREEN
        lastEvaluateNowMs = 0L
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
        decayMs: Long
    ): TriangulationResult {
        val list = spatialHistory.getOrPut(uuid) { mutableListOf() }
        synchronized(list) {
            list.removeAll { nowMs - it.timestampMs > decayMs }
            var geometryChanged = false
            if (gpsLat != null && gpsLon != null) {
                val last = list.lastOrNull()
                if (last != null && haversineMeters(last.lat, last.lon, gpsLat, gpsLon) < 12.0) {
                    if (abs(last.signalDb - signalDb) >= 2) geometryChanged = true
                    last.lat = gpsLat
                    last.lon = gpsLon
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
                return TriangulationResult(
                    centerLat = gpsLat,
                    centerLon = gpsLon,
                    estimatedDistanceM = estDistanceM,
                    uncertaintyRadiusM = estDistanceM,
                    spatialConfidence = 40,
                    isApproximatedPosition = false,
                    isPinpointed = false,
                    isClosing = false
                )
            }

            val latest = list.last()
            val curUserLat = gpsLat ?: latest.lat
            val curUserLon = gpsLon ?: latest.lon

            // Return cached triangulation if no new GPS baseline or signal jump occurred
            val cached = triangulationCache[uuid]
            if (!geometryChanged && cached != null && cached.sampleCount == list.size) {
                if (cached.result.isApproximatedPosition && cached.result.centerLat != null && cached.result.centerLon != null) {
                    val newDist = haversineMeters(curUserLat, curUserLon, cached.result.centerLat, cached.result.centerLon).roundToInt().coerceAtLeast(15)
                    return cached.result.copy(estimatedDistanceM = newDist)
                } else {
                    return cached.result.copy(
                        centerLat = curUserLat,
                        centerLon = curUserLon,
                        estimatedDistanceM = latest.estDistanceM,
                        uncertaintyRadiusM = latest.estDistanceM
                    )
                }
            }

            val isClosing = if (list.size >= 2) {
                val prev = list[max(0, list.size - 2)]
                latest.estDistanceM < (prev.estDistanceM * 0.88).toInt() || (latest.signalDb - prev.signalDb >= 4)
            } else false

            // Compute maximum pairwise GPS baseline across recorded samples
            var maxSpreadM = 0.0
            var farA = list.first()
            var farB = list.last()
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val d = haversineMeters(list[i].lat, list[i].lon, list[j].lat, list[j].lon)
                    if (d > maxSpreadM) {
                        maxSpreadM = d
                        farA = list[i]
                        farB = list[j]
                    }
                }
            }

            // Compute 2D cross-track spread perpendicular to the primary travel axis (farA -> farB).
            // Driving straight down a road has crossTrackM ~ 0m and cannot resolve left/right ambiguity.
            val crossTrackM = if (list.size >= 3 && maxSpreadM >= MIN_TRIANGULATION_BASELINE_M) {
                computeMaxCrossTrackMeters(list, farA, farB)
            } else {
                0.0
            }

            if (list.size < 3 || maxSpreadM < MIN_TRIANGULATION_BASELINE_M || crossTrackM < MIN_2D_CROSS_TRACK_M) {
                // Single-dimension road drive or short baseline:
                // When >= 2 samples exist, anchor at the signal-strength weighted centroid so driving
                // past an emitter keeps the circle at the derived peak location rather than dragging behind the car.
                var wSum = 0.0
                var latWeighted = 0.0
                var lonWeighted = 0.0
                for (s in list) {
                    val w = 10.0.pow(s.signalDb / 16.0).coerceIn(0.001, 10_000.0)
                    wSum += w
                    latWeighted += s.lat * w
                    lonWeighted += s.lon * w
                }
                val anchorLat = if (list.size >= 2 && wSum > 0.0) latWeighted / wSum else curUserLat
                val anchorLon = if (list.size >= 2 && wSum > 0.0) lonWeighted / wSum else curUserLon
                val baseSpatialConf = (35 + min(20, (maxSpreadM / 4.0).toInt())).coerceIn(35, 55)
                val res = TriangulationResult(
                    centerLat = anchorLat,
                    centerLon = anchorLon,
                    estimatedDistanceM = latest.estDistanceM,
                    uncertaintyRadiusM = latest.estDistanceM,
                    spatialConfidence = baseSpatialConf,
                    isApproximatedPosition = false,
                    isPinpointed = false,
                    isClosing = isClosing,
                    sampleCount = list.size
                )
                triangulationCache[uuid] = CachedTriangulation(list.size, curUserLat, curUserLon, signalDb, res)
                return res
            }

            // Genuine 2D baseline (>= 60m spread AND >= 25m cross-track turn): solve 2D range-annulus intersection
            val refLat = list.maxByOrNull { it.signalDb }?.lat ?: latest.lat
            val refLon = list.maxByOrNull { it.signalDb }?.lon ?: latest.lon
            val mPerDegLat = 111_132.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.1)

            val searchSpanM = maxOf(maxSpreadM * 1.5, latest.estDistanceM * 1.25).coerceIn(80.0, 4000.0)
            val steps = 11
            var bestX = 0.0
            var bestY = 0.0
            var bestScore = Double.MAX_VALUE

            val sampleXY = list.map { s ->
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
            val maxDilution = max(15.0, distFromUserM * 0.85)
            val dilutionM = (distFromUserM * 48.0 / maxSpreadM.coerceAtLeast(1.0)).coerceIn(15.0, maxDilution)
            val minReasonableRadius = max(25, (latest.estDistanceM * 0.35).roundToInt())
            val maxReasonableRadius = max(minReasonableRadius, max(distFromUserM, latest.estDistanceM))
            val approxRadiusM = maxOf(rmsResidualM * 0.85, dilutionM, minReasonableRadius.toDouble())
                .roundToInt()
                .coerceIn(minReasonableRadius, maxReasonableRadius)

            // Only collapse to a pinpointed point if 2D cross-track spread is wide (>= 45m) AND uncertainty <= 25m
            val isPinpointed = crossTrackM >= 45.0 && approxRadiusM <= PINPOINT_POINT_RADIUS_M
            val spatialConf = (88 - (approxRadiusM * 30 / max(distFromUserM, 80))).coerceIn(55, 92)

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
            triangulationCache[uuid] = CachedTriangulation(list.size, curUserLat, curUserLon, signalDb, res)
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
        val enabled = KokohoSignatures.getEnabledAgencies(context)
        val mapVisibilityMs = KokohoSignatures.mapMaxVisibilityMs(context)
        val monitorWindowMs = KokohoSignatures.MONITOR_ACTIVE_WINDOW_MS
        val auxnetOn = KokohoSignatures.isAuxnetEnabled(context)

        val enabledScopeText = if (enabled.isEmpty()) {
            "No agencies selected"
        } else {
            enabled.sortedBy { it.priorityRank }.joinToString(" · ") { it.friendlyLabel }
        }

        val userLoc = ScanForegroundService.lastFix
        val evaluated = mutableMapOf<String, EvaluatedThreat>()

        // Prune expired local threat cache entries beyond the Map's total visibility window (solid + 2h fade)
        localThreatCache.entries.removeIf { nowMs - it.value.updatedAtMs > mapVisibilityMs }

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
                val centerDistM = if (userLoc != null) {
                    haversineMeters(userLoc.latitude, userLoc.longitude, remote.location.lat, remote.location.lon).roundToInt()
                } else {
                    uncertM
                }
                val edgeDistM = max(0, centerDistM - uncertM)

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

                val effectiveDistM = max(edgeDistM, uncertM)
                val posture = when {
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

                val classConf = (remote.confidence.classification * 100).roundToInt().coerceIn(1, 99)
                val spatConf = (remote.confidence.spatial * 100).roundToInt().coerceIn(1, 99)
                val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

                val remoteSightings = max(remote.sightingCount, remote.corroborationCount).coerceAtLeast(1)
                val isRemoteDerived = isAir ||
                    remote.corroborationCount >= 2 ||
                    remote.sightingCount >= 2 ||
                    uncertM <= WIDE_CIRCLE_JITTER_MIN_M

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
                    aircraftTrackDeg = remote.location.bearingDeg,
                    hardwareIdentifier = badges.firstOrNull()?.facetKey,
                    sightingCount = remoteSightings
                )
            }
        }

        // 2. Evaluate local RTL-SDR RF hits, Pro/Enterprise/RemoteID Drones, and close-range Fleet OUI devices
        val localRfHits = RadioLog.current(nowMs).filter { nowMs - it.lastSeen <= mapVisibilityMs }
        val localDevices = DeviceRegistry.snapshot(nowMs).filter { nowMs - it.lastSeen <= mapVisibilityMs }

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

                val facetKey = "uas:mac:${snap.mac}"
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
                    evidenceSources = listOf(
                        EvidenceBadge(
                            kind = if (rid != null) "REMOTE_ID_UAS" else "TACTICAL_DRONE_OUI",
                            facetKey = facetKey,
                            detail = uasDetail,
                            weight = 0.92,
                            lastSeenMs = snap.lastSeen
                        )
                    ),
                    updatedAtMs = snap.lastSeen,
                    lat = droneLat,
                    lon = droneLon,
                    isApproximatedPosition = hasExactDroneGps,
                    isPinpointed = hasExactDroneGps,
                    isDrone = true,
                    altitudeFtAgl = altFtAgl,
                    operatorLat = rid?.operatorLatitude,
                    operatorLon = rid?.operatorLongitude,
                    hardwareIdentifier = rid?.uasId?.takeIf { it.isNotBlank() } ?: snap.mac
                )
                evaluated[uuid] = threat
                localThreatCache[uuid] = threat
            }
        }

        // 2b. Curated Fleet OUI / Tactical candidates (excluding "rmat" traffic signal cabinets, transit & retail routers)
        data class LocalOuiCandidate(
            val mac: String,
            val agency: KokohoAgency,
            val label: String,
            val confidence: Int,
            val rssi: Int,
            val sightings: Int,
            val rising: Boolean,
            val lastSeen: Long,
            val lat: Double?,
            val lon: Double?
        )

        val ouiCandidates = localDevices.mapNotNull { snap ->
            if (snap.remoteId != null || KokohoSignatures.isTacticalOrRemoteIdDrone(snap.mac, snap.name, snap.vendor, false)) {
                return@mapNotNull null
            }
            if (KokohoSignatures.isIgnoredInfrastructureOrRetail(snap.mac, snap.name)) {
                return@mapNotNull null
            }
            val ouiSig = KokohoSignatures.matchOui(snap.mac, snap.name)
            val matchedPair = if (ouiSig != null) {
                Triple(ouiSig.agencyEnum, ouiSig.label, ouiSig.confidence)
            } else {
                val bySsid = KokohoSignatures.matchOuiOrSsid(snap.mac, snap.name, snap.vendor, hasRemoteId = false)
                    ?: return@mapNotNull null
                val desc = if (!snap.name.isNullOrBlank()) "${bySsid.second} (${snap.name})" else bySsid.second
                Triple(bySsid.first, desc, 78)
            }
            // Require close-range signal (>= -78 dBm) or repeated corroborated sightings (>= 3 sightings at >= -82 dBm)
            val strongEnough = snap.rssi >= -78 || (snap.sightings >= 3 && snap.bestRssi >= -82)
            if (!strongEnough) return@mapNotNull null
            LocalOuiCandidate(
                mac = snap.mac,
                agency = matchedPair.first,
                label = matchedPair.second,
                confidence = matchedPair.third,
                rssi = max(snap.rssi, snap.bestRssi - 4),
                sightings = snap.sightings.coerceAtLeast(1),
                rising = snap.rssi >= snap.bestRssi - 3 && snap.sightings >= 2 && snap.rssi >= -74,
                lastSeen = snap.lastSeen,
                lat = snap.place?.lat ?: userLoc?.latitude,
                lon = snap.place?.lon ?: userLoc?.longitude
            )
        }

        val consumedOuiMacs = mutableSetOf<String>()
        val pendingInteropBadges = mutableListOf<EvidenceBadge>()

        // 2c. Local RTL-SDR RF Hits — solidly exclude repeaters and unknown commercial/municipal VHF/UHF,
        // while matching curated channels and close-range (>= 32 dB SNR) NTIA Exclusive Federal VHF/UHF simplex!
        for (rf in localRfHits) {
            val sig = KokohoSignatures.matchFrequency(rf.freqHz)
                ?: KokohoSignatures.matchCloseRangeFederalBand(rf.freqHz, rf.peakSnrDb)
                ?: continue
            // Solidly exclude Repeater Downlinks and Trunk Control Channels!
            if (!sig.isAlertableDirectOrMobile) continue

            val agency = sig.agencyEnum
            val facetKey = "rf:chan:${rf.freqHz}:${sig.nac ?: "ANY"}"

            // If this is a Federal Interop channel and another DHS agency is active, hold it as
            // corroborating evidence so one radio scanning interop doesn't count as a second agent (#17).
            if (agency == KokohoAgency.DHS_INTEROP && agency !in enabled) {
                pendingInteropBadges += EvidenceBadge(
                    kind = "RF_${sig.mode}",
                    facetKey = facetKey,
                    detail = "${sig.alpha} Interop (${rf.peakSnrDb} dB SNR)",
                    weight = 0.35,
                    lastSeenMs = rf.lastSeen
                )
                continue
            }
            if (agency !in enabled) continue

            val colocatedOui = ouiCandidates.firstOrNull { abs(it.lastSeen - rf.lastSeen) <= COLOCATION_WINDOW_MS }
            if (colocatedOui != null) {
                consumedOuiMacs += colocatedOui.mac
            }

            // Object permanence (#18): always derive the RF entity UUID from its stable RF channel facet,
            // never re-keying it to a transient Wi-Fi MAC passed along the road.
            val uuid = deterministicUuidV5(facetKey)
            val rawEstDistM = estimateRfDistanceMeters(rf.peakSnrDb, sig.role)

            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = userLoc?.latitude ?: colocatedOui?.lat,
                gpsLon = userLoc?.longitude ?: colocatedOui?.lon,
                signalDb = rf.peakSnrDb,
                estDistanceM = rawEstDistM,
                nowMs = rf.lastSeen,
                decayMs = mapVisibilityMs
            )

            val badges = mutableListOf(
                EvidenceBadge(
                    kind = "RF_${sig.mode}",
                    facetKey = facetKey,
                    detail = "${sig.alpha} (${"%.4f".format(Locale.US, rf.freqHz / 1e6)} MHz · ${rf.peakSnrDb} dB SNR)",
                    weight = 0.70,
                    lastSeenMs = rf.lastSeen
                )
            )
            if (colocatedOui != null) {
                badges += EvidenceBadge(
                    kind = "WIFI_BLE_OUI",
                    facetKey = "hw:mac:${colocatedOui.mac}",
                    detail = "${colocatedOui.label} (${colocatedOui.rssi} dBm)",
                    weight = 0.30,
                    lastSeenMs = colocatedOui.lastSeen
                )
            }

            val isApproaching = rf.trend == RadioLog.Trend.CLOSER || colocatedOui?.rising == true || tri.isClosing
            val posture = when {
                tri.estimatedDistanceM <= 250 || (colocatedOui != null && colocatedOui.rssi >= -65) -> PostureColor.RED
                isApproaching -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = if (colocatedOui != null) 96 else 88
            val spatConf = if (colocatedOui != null) max(tri.spatialConfidence, 76) else tri.spatialConfidence
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
                recurrenceSummary = "${sig.alpha} (${sig.description})",
                evidenceSources = badges,
                updatedAtMs = rf.lastSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed,
                hardwareIdentifier = "${sig.alpha} · ${"%.4f".format(Locale.US, rf.freqHz / 1e6)} MHz",
                sightingCount = max(tri.sampleCount, colocatedOui?.sightings ?: 1)
            )
            evaluated[uuid] = threat
            localThreatCache[uuid] = threat
        }

        // Fold any concurrent Federal Interop evidence into active specific DHS agency threats (#17)
        if (pendingInteropBadges.isNotEmpty()) {
            val dhsTargets = evaluated.values.filter { it.agency.isDhs && it.agency != KokohoAgency.DHS_INTEROP }
            for (target in dhsTargets) {
                val mergedBadges = (target.evidenceSources + pendingInteropBadges).distinctBy { it.facetKey }
                val updated = target.copy(
                    classificationConfidence = min(99, target.classificationConfidence + 4),
                    evidenceSources = mergedBadges
                )
                evaluated[target.uuid] = updated
                localThreatCache[target.uuid] = updated
            }
        }

        // 3. Remaining un-fused close-range Fleet OUI / Tactical candidates
        for (oui in ouiCandidates) {
            if (oui.mac in consumedOuiMacs) continue
            if (oui.agency !in enabled) continue
            val facetKey = "hw:mac:${oui.mac}"
            val uuid = deterministicUuidV5(facetKey)
            if (uuid in evaluated) continue

            val rawEstDistM = estimateRssiDistanceMeters(oui.rssi)
            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = userLoc?.latitude ?: oui.lat,
                gpsLon = userLoc?.longitude ?: oui.lon,
                signalDb = oui.rssi,
                estDistanceM = rawEstDistM,
                nowMs = oui.lastSeen,
                decayMs = mapVisibilityMs
            )

            val posture = when {
                tri.estimatedDistanceM <= 60 || oui.rssi >= -65 -> PostureColor.RED
                oui.rising || tri.isClosing -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = oui.confidence
            val spatConf = tri.spatialConfidence
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

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
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "WIFI_BLE_OUI",
                        facetKey = facetKey,
                        detail = "${oui.label} (${oui.mac} · ${oui.rssi} dBm)",
                        weight = 0.75,
                        lastSeenMs = oui.lastSeen
                    )
                ),
                updatedAtMs = oui.lastSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed,
                hardwareIdentifier = oui.mac,
                sightingCount = max(oui.sightings, tri.sampleCount)
            )
            evaluated[uuid] = threat
            localThreatCache[uuid] = threat
        }

        // 4. Active P25 talkgroups with direct mobile input heard recently (excluding repeater-only trunk traffic)
        for (tgRow in SdrRadio.p25Log.rows()) {
            val tgId = tgRow.tg ?: continue
            val inputHeardRecently = tgRow.inputAt > 0L && (nowMs - tgRow.inputAt <= mapVisibilityMs)
            if (!inputHeardRecently) continue
            val tgSig = KokohoSignatures.matchTalkgroup(tgId) ?: continue
            val agency = tgSig.agencyEnum
            if (agency !in enabled) continue

            val srcUnit = tgRow.lastSource ?: tgId
            val facetKey = "rf:rid:$srcUnit"
            val uuid = deterministicUuidV5(facetKey)
            val rawEstDistM = estimateRfDistanceMeters(if (tgRow.inputSnr > 0) tgRow.inputSnr else 25, "MOBILE_INPUT")
            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = userLoc?.latitude,
                gpsLon = userLoc?.longitude,
                signalDb = if (tgRow.inputSnr > 0) tgRow.inputSnr else 25,
                estDistanceM = rawEstDistM,
                nowMs = tgRow.inputAt,
                decayMs = mapVisibilityMs
            )

            val posture = when {
                tri.estimatedDistanceM <= 300 -> PostureColor.RED
                tri.isClosing || tri.estimatedDistanceM <= 800 -> PostureColor.ORANGE
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
                hardwareIdentifier = "RID $srcUnit (TG $tgId)"
            )
            evaluated[uuid] = threat
            localThreatCache[uuid] = threat
        }

        // 5. Evaluate ADS-B Aircraft & Unlisted Circling Spy Planes (#20, Review #4)
        if (KokohoSignatures.isAdsbEnabled(context)) {
            val enabledAirAgencies = KokohoSignatures.getEnabledAircraftAgencies(context)
            for (seen in PoliceAircraft.all(nowMs)) {
                val p = seen.plane
                val hit = seen.hit
                val isCircling = hit?.label == PoliceAircraft.CIRCLING ||
                    hit?.evidence?.contains("circling", ignoreCase = true) == true
                val agency = KokohoSignatures.classifyAircraftAgency(p.owner, p.registration, p.callsign, hit?.label)
                    ?: KokohoAgency.DHS_AMO
                // Always alert on circling unlisted/spy aircraft when DHS Air or ADS-B is enabled
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
                val posture = when {
                    isCircling && distM <= 8_000 -> PostureColor.RED
                    distM <= 2_000 -> PostureColor.RED
                    isCircling || headingToward || distM <= 6_000 -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }
                val classConf = hit?.confidence?.coerceIn(80, 99) ?: 92
                val spatConf = 98
                val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)
                val identStr = listOfNotNull(
                    p.registration?.takeIf { it.isNotBlank() },
                    p.callsign?.takeIf { it.isNotBlank() },
                    "ICAO ${p.hex.uppercase()}"
                ).distinct().joinToString(" · ")
                val seenAtMs = max(PoliceAircraft.localAt, PoliceAircraft.latestAt).takeIf { it > 0L } ?: nowMs
                val altSuffix = p.altitudeFt?.let { " · $it ft" }.orEmpty()

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
                    recurrenceSummary = if (isCircling) "Spy Plane / Orbiting Overhead ($identStr)" else "ADS-B Aircraft ($identStr)",
                    evidenceSources = listOf(
                        EvidenceBadge(
                            kind = "ADSB_AIR",
                            facetKey = facetKey,
                            detail = "${hit?.label ?: agency.friendlyLabel} ($identStr$altSuffix)",
                            weight = 0.95,
                            lastSeenMs = seenAtMs
                        )
                    ),
                    updatedAtMs = seenAtMs,
                    lat = p.lat,
                    lon = p.lon,
                    isApproximatedPosition = true,
                    isPinpointed = true,
                    isAircraft = true,
                    aircraftTrackDeg = p.trackDeg,
                    aircraftHex = p.hex.uppercase(),
                    isCircling = isCircling,
                    altitudeFtAgl = p.altitudeFt,
                    hardwareIdentifier = identStr
                )
                evaluated[uuid] = threat
                localThreatCache[uuid] = threat
            }
        }

        // 6. Include retained local detections still within the Map visibility window (30m solid + 2h fade)
        localThreatCache.entries.removeIf { nowMs - it.value.updatedAtMs > mapVisibilityMs }
        for ((uuid, cached) in localThreatCache) {
            if (uuid !in evaluated && nowMs - cached.updatedAtMs <= mapVisibilityMs) {
                val allowed = when {
                    cached.isAircraft -> {
                        val airEnabled = KokohoSignatures.getEnabledAircraftAgencies(context)
                        KokohoSignatures.isAdsbEnabled(context) &&
                            (cached.isCircling || if (cached.agency.isDhs) airEnabled.any { it.isDhs } else cached.agency in airEnabled)
                    }
                    cached.isDrone -> KokohoSignatures.isDroneDetectionEnabled(context)
                    else -> cached.agency in enabled
                }
                if (allowed) {
                    evaluated[uuid] = cached
                }
            }
        }

        // Apply local custom classification labels / Witness Reports and filter out locally whitelisted entities
        val allRetained = evaluated.values
            .filter { !KokohoSignatures.isEntityWhitelisted(context, it.uuid) }
            .map { t ->
                val resolved = KokohoSignatures.resolveEntityLabel(context, t.uuid, t.agency.friendlyLabel)
                val updated = if (resolved != t.label) t.copy(label = resolved) else t
                if (localThreatCache.containsKey(t.uuid)) {
                    localThreatCache[t.uuid] = updated
                }
                updated
            }

        // Map threats: all retained detections within the 2.5-hour map visibility window, sorted by priorityRank then proximity
        val mapThreats = allRetained.sortedWith(
            compareBy<EvaluatedThreat> { it.agency.priorityRank }
                .thenBy { it.effectiveProximityM }
                .thenByDescending { it.updatedAtMs }
        )

        // Monitor threats: only detections active within the 3-minute Monitor window (#10, Question 1),
        // sorted strictly by agency priorityRank (#11: ICE, DHS Air, CBP, HSI, Other Fed, Sheriff/TPD/Drone)
        // regardless of detection time or distance!
        val monitorThreats = allRetained
            .filter { nowMs - it.updatedAtMs <= monitorWindowMs }
            .sortedWith(
                compareBy<EvaluatedThreat> { it.agency.priorityRank }
                    .thenByDescending { KokohoSignatures.isEntityWatched(context, it.uuid) }
                    .thenBy { it.effectiveProximityM }
            )

        val monitorGroups = buildMonitorGroups(monitorThreats)

        // Publish derived/corroborated detections when live Sharing is active (never raw 1-sample user GPS)
        if (ScanForegroundService.isRunning && KokohoSignatures.isSharingActive(context)) {
            for (t in monitorThreats) {
                if (!localThreatCache.containsKey(t.uuid)) continue
                val lastSent = lastSharedAtByUuid[t.uuid] ?: 0L
                if (nowMs - lastSent < 45_000L) continue
                val baseLat = t.lat ?: continue
                val baseLon = t.lon ?: continue
                // Never share isolated 1-sample ground hits; require emitter GPS, 2D triangulation, >= 2 sightings, or >= 2 signals
                val isEmitterGps = t.isAircraft || (t.isDrone && t.isPinpointed)
                val isDerived = isEmitterGps || t.isApproximatedPosition || t.sightingCount >= 2 || t.uncertaintyRadiusM <= WIDE_CIRCLE_JITTER_MIN_M
                val isCorroborated = isDerived || t.evidenceCount >= 2
                if (!isCorroborated) continue
                val (shareLat, shareLon) = if (isDerived) {
                    baseLat to baseLon
                } else {
                    deriveJitteredCenter(t.uuid, baseLat, baseLon, t.uncertaintyRadiusM)
                }
                val primaryBadge = t.evidenceSources.firstOrNull() ?: continue
                val extraFacets = t.evidenceSources.drop(1).map { it.facetKey }
                val propClass = if (t.isAircraft) "AIRBORNE" else "GROUND_DIRECT"
                lastSharedAtByUuid[t.uuid] = nowMs
                KokohoHubClient.publishSensorReportIfEnabled(
                    context = context,
                    lat = shareLat,
                    lon = shareLon,
                    snrDb = (t.combinedConfidence / 2.5).coerceIn(12.0, 42.0),
                    modality = primaryBadge.kind,
                    primaryFacet = primaryBadge.facetKey,
                    additionalFacets = extraFacets,
                    agency = t.agency,
                    label = t.friendlyLabel,
                    propagationClass = propClass,
                    uncertaintyRadiusM = t.uncertaintyRadiusM.toDouble(),
                    isDerived = isDerived
                )
            }
        }

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
                enabledScopeText = enabledScopeText
            )
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
            enabledScopeText = enabledScopeText
        )
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
        val cached = localThreatCache[id]
        if (cached != null) {
            val resolved = KokohoSignatures.resolveEntityLabel(context, cached.uuid, cached.agency.friendlyLabel)
            return if (resolved != cached.label) cached.copy(label = resolved) else cached
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
