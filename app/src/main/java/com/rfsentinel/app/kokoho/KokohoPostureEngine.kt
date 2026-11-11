package com.rfsentinel.app.kokoho

import android.content.Context
import com.rfsentinel.app.online.PoliceAircraft
import com.rfsentinel.app.sdr.RadioLog
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
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
    /** True once multi-GPS client triangulation (>= 60m spread) or Auxnet room correlation approximates the source position. */
    val isApproximatedPosition: Boolean = false,
    /** True only when the approximated position uncertainty is narrow enough to render as a point (<= 35m) or exact ADS-B GPS. */
    val isPinpointed: Boolean = false,
    /** True if this threat is an airborne ADS-B aircraft. */
    val isAircraft: Boolean = false,
    val aircraftTrackDeg: Double? = null
) {
    val friendlyLabel: String get() = label.ifBlank { agency.friendlyLabel }
    val combinedConfidence: Int
        get() = ((classificationConfidence + spatialConfidence) / 2.0).roundToInt().coerceIn(1, 99)
    val evidenceCount: Int
        get() = evidenceSources.size.coerceAtLeast(1)
    /**
     * Radius (in meters) to draw on the Map:
     *  - Before wide-baseline triangulation (`!isApproximatedPosition`): circle centered at user GPS with `radius = estimatedDistanceM`.
     *  - After triangulation (`isApproximatedPosition`): circle centered at `(lat, lon)` with `radius = uncertaintyRadiusM`.
     */
    val mapCircleRadiusM: Int
        get() = if (isApproximatedPosition) uncertaintyRadiusM.coerceAtLeast(20) else estimatedDistanceM.coerceAtLeast(35)
}

data class PostureSnapshot(
    val color: PostureColor,
    val headline: String,
    val subheadline: String,
    val distanceAndBearingText: String,
    val bottomConfidenceText: String,
    val primaryThreat: EvaluatedThreat?,
    val activeThreats: List<EvaluatedThreat>,
    val enabledScopeText: String
)

object KokohoPostureEngine {

    private const val COLOCATION_WINDOW_MS = 90_000L
    /** Minimum GPS spread (meters) across client observations before transitioning from a user-centered distance circle to an approximated source circle. */
    const val MIN_TRIANGULATION_BASELINE_M = 60.0
    /** Maximum uncertainty radius (meters) at which an approximated circle is considered narrow enough to show a point dot. */
    const val PINPOINT_POINT_RADIUS_M = 35

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
        val isClosing: Boolean
    )

    private val spatialHistory = ConcurrentHashMap<String, MutableList<SpatialSample>>()
    private val localThreatCache = ConcurrentHashMap<String, EvaluatedThreat>()
    @Volatile
    private var lastPostureColor: PostureColor = PostureColor.GREEN
    @Volatile
    private var lastEvaluateNowMs: Long = 0L

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
        localThreatCache.clear()
        lastPostureColor = PostureColor.GREEN
        lastEvaluateNowMs = 0L
    }

    /**
     * Records a local observation at `(gpsLat, gpsLon)` with estimated range `estDistanceM`
     * and solves for either:
     *  - A user-centered radius circle (`radius = estDistanceM`) when GPS spread < 60m, OR
     *  - An approximated source position circle (`(approxLat, approxLon)`, `radius = uncertaintyRadiusM`)
     *    when multiple GPS fixes span >= 60m.
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
            if (gpsLat != null && gpsLon != null) {
                val last = list.lastOrNull()
                if (last != null && haversineMeters(last.lat, last.lon, gpsLat, gpsLon) < 8.0) {
                    last.signalDb = signalDb
                    last.estDistanceM = estDistanceM
                    last.timestampMs = nowMs
                } else {
                    list.add(SpatialSample(gpsLat, gpsLon, signalDb, estDistanceM, nowMs))
                    if (list.size > 64) list.removeAt(0)
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
            val isClosing = if (list.size >= 2) {
                val prev = list[max(0, list.size - 2)]
                latest.estDistanceM < (prev.estDistanceM * 0.88).toInt() || (latest.signalDb - prev.signalDb >= 4)
            } else false

            // Compute maximum pairwise GPS baseline across recorded samples
            var maxSpreadM = 0.0
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val d = haversineMeters(list[i].lat, list[i].lon, list[j].lat, list[j].lon)
                    if (d > maxSpreadM) maxSpreadM = d
                }
            }

            if (list.size < 3 || maxSpreadM < MIN_TRIANGULATION_BASELINE_M) {
                // Not enough spatial diversity yet: show radius circle centered on user's GPS at estimatedDistanceM
                val baseSpatialConf = (35 + min(20, (maxSpreadM / 3.0).toInt())).coerceIn(35, 55)
                return TriangulationResult(
                    centerLat = gpsLat ?: latest.lat,
                    centerLon = gpsLon ?: latest.lon,
                    estimatedDistanceM = latest.estDistanceM,
                    uncertaintyRadiusM = latest.estDistanceM,
                    spatialConfidence = baseSpatialConf,
                    isApproximatedPosition = false,
                    isPinpointed = false,
                    isClosing = isClosing
                )
            }

            // Wide baseline (>= 60m across >= 3 GPS fixes): solve 2D range-annulus intersection around strongest samples
            val refLat = list.maxByOrNull { it.signalDb }?.lat ?: latest.lat
            val refLon = list.maxByOrNull { it.signalDb }?.lon ?: latest.lon
            val mPerDegLat = 111_132.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.1)

            val searchSpanM = maxOf(maxSpreadM * 1.5, latest.estDistanceM * 1.25).coerceIn(80.0, 4000.0)
            val steps = 16
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
            val curUserLat = gpsLat ?: latest.lat
            val curUserLon = gpsLon ?: latest.lon
            val distFromUserM = haversineMeters(curUserLat, curUserLon, approxLat, approxLon).roundToInt().coerceAtLeast(15)

            // Uncertainty shrinks as GPS baseline widens relative to distance and as RMS residual tightens
            val dilutionM = (distFromUserM * 42.0 / maxSpreadM).coerceIn(15.0, distFromUserM * 0.85)
            val approxRadiusM = maxOf(rmsResidualM * 0.75, dilutionM).roundToInt().coerceIn(20, max(distFromUserM, 60))
            val isPinpointed = approxRadiusM <= PINPOINT_POINT_RADIUS_M
            val spatialConf = (92 - (approxRadiusM * 35 / max(distFromUserM, 80))).coerceIn(60, 95)

            return TriangulationResult(
                centerLat = approxLat,
                centerLon = approxLon,
                estimatedDistanceM = distFromUserM,
                uncertaintyRadiusM = approxRadiusM,
                spatialConfidence = spatialConf,
                isApproximatedPosition = true,
                isPinpointed = isPinpointed,
                isClosing = isClosing
            )
        }
    }

    fun evaluate(context: Context, nowMs: Long = System.currentTimeMillis()): PostureSnapshot {
        KokohoSignatures.ensureLoaded(context)
        val enabled = KokohoSignatures.getEnabledAgencies(context)
        val decayMs = KokohoSignatures.signalDecayMs(context)
        val auxnetOn = KokohoSignatures.isAuxnetEnabled(context)

        val enabledScopeText = if (enabled.isEmpty()) {
            "No agencies selected"
        } else {
            val dhsCount = enabled.count { it.isDhs }
            val dhsPart = if (dhsCount == KokohoAgency.DHS_AGENCIES.size) {
                "DHS"
            } else if (dhsCount > 0) {
                enabled.filter { it.isDhs }.joinToString(", ") { it.friendlyLabel }
            } else null
            val otherPart = enabled.filter { !it.isDhs }.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.friendlyLabel }
            listOfNotNull(dhsPart, otherPart).joinToString(" · ")
        }

        val userLoc = ScanForegroundService.lastFix
        val evaluated = mutableMapOf<String, EvaluatedThreat>()

        // Prune expired local threat cache entries beyond the configured decay window
        localThreatCache.entries.removeIf { nowMs - it.value.updatedAtMs > decayMs }

        // 1. Evaluate remote sanitized ThreatObjects from Auxnet kokoho-hub (when Auxnet is enabled)
        if (auxnetOn) {
            for (remote in KokohoHubClient.activeObjects(nowMs, decayMs)) {
                val isAir = remote.location.propagationClass.equals("AIR_TO_GROUND", ignoreCase = true) ||
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

                val distM = if (userLoc != null) {
                    haversineMeters(userLoc.latitude, userLoc.longitude, remote.location.lat, remote.location.lon).roundToInt()
                } else {
                    remote.location.uncertaintyRadiusM.roundToInt()
                }

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

                val posture = when {
                    distM <= 300 || (remote.location.uncertaintyRadiusM <= 180 && distM <= 500) -> PostureColor.RED
                    headingTowardUser -> PostureColor.ORANGE
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

                val uncertM = remote.location.uncertaintyRadiusM.roundToInt().coerceAtLeast(15)
                val classConf = (remote.confidence.classification * 100).roundToInt().coerceIn(1, 99)
                val spatConf = (remote.confidence.spatial * 100).roundToInt().coerceIn(1, 99)
                val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

                evaluated[remote.uuid] = EvaluatedThreat(
                    uuid = remote.uuid,
                    trackUuid = remote.trackUuid,
                    agency = agency,
                    label = agency.friendlyLabel,
                    posture = posture,
                    overallConfidence = combinedConf,
                    classificationConfidence = classConf,
                    spatialConfidence = spatConf,
                    estimatedDistanceM = distM,
                    uncertaintyRadiusM = uncertM,
                    bearingDeg = bearingFromUser,
                    closingVelocityMps = remote.location.velocityMps,
                    recurrenceSummary = remote.classification.recurrenceSummary,
                    evidenceSources = badges,
                    updatedAtMs = remote.updatedAtMs,
                    lat = remote.location.lat,
                    lon = remote.location.lon,
                    isApproximatedPosition = true,
                    isPinpointed = isAir || uncertM <= PINPOINT_POINT_RADIUS_M,
                    isAircraft = isAir,
                    aircraftTrackDeg = remote.location.bearingDeg
                )
            }
        }

        // 2. Evaluate local RTL-SDR RF hits & P25 talkgroups, plus local watchlisted OUI devices within decayMs
        val localRfHits = RadioLog.current(nowMs).filter { nowMs - it.lastSeen <= decayMs }
        val localDevices = DeviceRegistry.snapshot(nowMs).filter { nowMs - it.lastSeen <= decayMs }

        data class LocalOuiCandidate(
            val mac: String,
            val sig: KokohoSignatures.FleetOuiSig,
            val rssi: Int,
            val rising: Boolean,
            val lastSeen: Long,
            val lat: Double?,
            val lon: Double?
        )

        val ouiCandidates = localDevices.mapNotNull { snap ->
            val matchedSig: KokohoSignatures.FleetOuiSig? = KokohoSignatures.matchOui(snap.mac)
                ?: snap.best?.let { best ->
                    val ag = if (best.label.contains("Cradlepoint", true) || best.label.contains("Sierra", true)) {
                        KokohoAgency.DHS_CBP
                    } else {
                        KokohoAgency.POLICE
                    }
                    KokohoSignatures.FleetOuiSig(snap.mac.take(8), ag.id, ag.friendlyLabel, best.confidence)
                }
            val sig = matchedSig ?: return@mapNotNull null
            if (sig.agencyEnum !in enabled) return@mapNotNull null
            LocalOuiCandidate(
                mac = snap.mac,
                sig = sig,
                rssi = snap.rssi,
                rising = snap.rssi >= snap.bestRssi - 3 && snap.sightings >= 3 && snap.rssi > -76,
                lastSeen = snap.lastSeen,
                lat = snap.place?.lat ?: userLoc?.latitude,
                lon = snap.place?.lon ?: userLoc?.longitude
            )
        }

        val consumedOuiMacs = mutableSetOf<String>()

        for (rf in localRfHits) {
            val sig = KokohoSignatures.matchFrequency(rf.freqHz)
            val agency = sig?.agencyEnum ?: KokohoAgency.DHS_INTEROP
            if (agency !in enabled) continue

            val facetKey = "rf:chan:${rf.freqHz}:${sig?.nac ?: "ANY"}"
            val colocatedOui = ouiCandidates.firstOrNull { abs(it.lastSeen - rf.lastSeen) <= COLOCATION_WINDOW_MS }
            if (colocatedOui != null) {
                consumedOuiMacs += colocatedOui.mac
            }

            val primaryFacet = if (colocatedOui != null) "hw:mac:${colocatedOui.mac}" else facetKey
            val uuid = deterministicUuidV5(primaryFacet)
            val rawEstDistM = estimateRfDistanceMeters(rf.peakSnrDb, sig?.role ?: "DIRECT_TACTICAL")

            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = userLoc?.latitude ?: colocatedOui?.lat,
                gpsLon = userLoc?.longitude ?: colocatedOui?.lon,
                signalDb = rf.peakSnrDb,
                estDistanceM = rawEstDistM,
                nowMs = rf.lastSeen,
                decayMs = decayMs
            )

            val badges = mutableListOf(
                EvidenceBadge(
                    kind = "RF_${sig?.mode ?: "SDR"}",
                    facetKey = facetKey,
                    detail = "${sig?.alpha ?: "%.4f MHz".format(Locale.US, rf.freqHz / 1e6)} (${rf.peakSnrDb} dB SNR)",
                    weight = 0.65,
                    lastSeenMs = rf.lastSeen
                )
            )
            if (colocatedOui != null) {
                badges += EvidenceBadge(
                    kind = "WIFI_BLE_OUI",
                    facetKey = "hw:mac:${colocatedOui.mac}",
                    detail = "${colocatedOui.sig.friendlyLabel} (${colocatedOui.rssi} dBm)",
                    weight = 0.35,
                    lastSeenMs = colocatedOui.lastSeen
                )
            }

            val isApproaching = rf.trend == RadioLog.Trend.CLOSER || colocatedOui?.rising == true || tri.isClosing
            val posture = when {
                tri.estimatedDistanceM <= 250 || (colocatedOui != null && colocatedOui.rssi >= -65) -> PostureColor.RED
                isApproaching -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = if (colocatedOui != null) 96 else 86
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
                recurrenceSummary = "${agency.friendlyLabel} RF activity",
                evidenceSources = badges,
                updatedAtMs = rf.lastSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed
            )
            evaluated[uuid] = threat
            localThreatCache[uuid] = threat
        }

        // 3. Remaining un-fused OUI candidates
        for (oui in ouiCandidates) {
            if (oui.mac in consumedOuiMacs) continue
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
                decayMs = decayMs
            )

            val posture = when {
                tri.estimatedDistanceM <= 60 || oui.rssi >= -65 -> PostureColor.RED
                oui.rising || tri.isClosing -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = oui.sig.confidence
            val spatConf = tri.spatialConfidence
            val combinedConf = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99)

            val threat = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = oui.sig.agencyEnum,
                label = oui.sig.agencyEnum.friendlyLabel,
                posture = posture,
                overallConfidence = combinedConf,
                classificationConfidence = classConf,
                spatialConfidence = spatConf,
                estimatedDistanceM = tri.estimatedDistanceM,
                uncertaintyRadiusM = tri.uncertaintyRadiusM,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "${oui.sig.agencyEnum.friendlyLabel} beacon",
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "WIFI_BLE_OUI",
                        facetKey = facetKey,
                        detail = "${oui.sig.friendlyLabel} (${oui.rssi} dBm)",
                        weight = 0.75,
                        lastSeenMs = oui.lastSeen
                    )
                ),
                updatedAtMs = oui.lastSeen,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed
            )
            evaluated[uuid] = threat
            localThreatCache[uuid] = threat
        }

        // 4. Active P25 talkgroups matching enabled agencies
        for (tgRow in SdrRadio.p25Log.rows()) {
            val tgId = tgRow.tg ?: continue
            if (nowMs - tgRow.lastAt > decayMs) continue
            val tgSig = KokohoSignatures.matchTalkgroup(tgId) ?: continue
            val agency = tgSig.agencyEnum
            if (agency !in enabled) continue

            val facetKey = "rf:tg:$tgId"
            val uuid = deterministicUuidV5(facetKey)
            val inputHeardRecently = tgRow.inputAt > 0L && (nowMs - tgRow.inputAt <= decayMs)
            val rawEstDistM = if (inputHeardRecently) {
                estimateRfDistanceMeters(if (tgRow.inputSnr > 0) tgRow.inputSnr else 25, "MOBILE_INPUT")
            } else {
                3500
            }
            val tri = recordAndTriangulate(
                uuid = uuid,
                gpsLat = userLoc?.latitude,
                gpsLon = userLoc?.longitude,
                signalDb = if (inputHeardRecently) tgRow.inputSnr else 15,
                estDistanceM = rawEstDistM,
                nowMs = tgRow.lastAt,
                decayMs = decayMs
            )

            val posture = when {
                inputHeardRecently && tri.estimatedDistanceM <= 300 -> PostureColor.RED
                inputHeardRecently || tri.isClosing -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val classConf = 90
            val spatConf = if (inputHeardRecently) max(tri.spatialConfidence, 70) else 40
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
                recurrenceSummary = "${tgRow.calls} P25 call${if (tgRow.calls == 1) "" else "s"}",
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "P25_TALKGROUP",
                        facetKey = facetKey,
                        detail = "${agency.friendlyLabel} TG $tgId",
                        weight = 0.80,
                        lastSeenMs = tgRow.lastAt
                    )
                ),
                updatedAtMs = tgRow.lastAt,
                lat = tri.centerLat,
                lon = tri.centerLon,
                isApproximatedPosition = tri.isApproximatedPosition,
                isPinpointed = tri.isPinpointed
            )
            evaluated[uuid] = threat
            localThreatCache[uuid] = threat
        }

        // 5. Evaluate ADS-B Aircraft (on by default for DHS aircraft, with options for Police/TPD, Sheriff, SAR, Fire)
        if (KokohoSignatures.isAdsbEnabled(context)) {
            val enabledAirAgencies = KokohoSignatures.getEnabledAircraftAgencies(context)
            for (seen in PoliceAircraft.all(nowMs)) {
                val p = seen.plane
                val hit = seen.hit
                val agency = KokohoSignatures.classifyAircraftAgency(p.owner, p.registration, p.callsign, hit?.label)
                    ?: continue
                val allowed = if (agency.isDhs) enabledAirAgencies.any { it.isDhs } else agency in enabledAirAgencies
                if (!allowed) continue

                val facetKey = "adsb:icao:${p.hex.uppercase()}"
                val uuid = deterministicUuidV5(facetKey)
                val distM = if (userLoc != null) {
                    haversineMeters(userLoc.latitude, userLoc.longitude, p.lat, p.lon).roundToInt()
                } else {
                    5000
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
                    distM <= 1500 -> PostureColor.RED
                    headingToward || distM <= 4500 -> PostureColor.ORANGE
                    else -> PostureColor.YELLOW
                }
                val classConf = hit?.confidence?.coerceIn(75, 98) ?: 90
                val spatConf = 96
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
                    estimatedDistanceM = distM,
                    uncertaintyRadiusM = 25,
                    bearingDeg = null,
                    closingVelocityMps = velMps,
                    recurrenceSummary = "ADS-B Aircraft",
                    evidenceSources = listOf(
                        EvidenceBadge(
                            kind = "ADSB_AIR",
                            facetKey = facetKey,
                            detail = "${agency.friendlyLabel} (${p.registration ?: p.callsign ?: p.hex.uppercase()})",
                            weight = 0.90,
                            lastSeenMs = nowMs
                        )
                    ),
                    updatedAtMs = nowMs,
                    lat = p.lat,
                    lon = p.lon,
                    isApproximatedPosition = true,
                    isPinpointed = true,
                    isAircraft = true,
                    aircraftTrackDeg = p.trackDeg
                )
                evaluated[uuid] = threat
                localThreatCache[uuid] = threat
            }
        }

        // 6. Include retained local detections still within the user's configured decay window
        localThreatCache.entries.removeIf { nowMs - it.value.updatedAtMs > decayMs }
        for ((uuid, cached) in localThreatCache) {
            if (uuid !in evaluated && nowMs - cached.updatedAtMs <= decayMs) {
                val allowed = if (cached.isAircraft) {
                    val airEnabled = KokohoSignatures.getEnabledAircraftAgencies(context)
                    KokohoSignatures.isAdsbEnabled(context) &&
                        (if (cached.agency.isDhs) airEnabled.any { it.isDhs } else cached.agency in airEnabled)
                } else {
                    cached.agency in enabled
                }
                if (allowed) {
                    evaluated[uuid] = cached
                }
            }
        }

        // Apply local custom classification labels and filter out locally whitelisted entities
        val filtered = evaluated.values
            .filter { !KokohoSignatures.isEntityWhitelisted(context, it.uuid) }
            .map { t ->
                val resolved = KokohoSignatures.resolveEntityLabel(context, t.uuid, t.agency.friendlyLabel)
                val updated = if (resolved != t.label) t.copy(label = resolved) else t
                if (localThreatCache.containsKey(t.uuid)) {
                    localThreatCache[t.uuid] = updated
                }
                updated
            }

        val sorted = filtered.sortedWith(
            compareByDescending<EvaluatedThreat> { KokohoSignatures.isEntityWatched(context, it.uuid) }
                .thenByDescending { it.posture.ordinal }
                .thenBy { it.estimatedDistanceM }
        )

        val primary = sorted.firstOrNull()
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
                enabledScopeText = enabledScopeText
            )
        }

        lastPostureColor = primary.posture
        val distFormatted = formatUserDistance(context, primary.estimatedDistanceM)
        val bottomConfidenceText =
            "${primary.combinedConfidence}% confidence · ${primary.evidenceCount} ${if (primary.evidenceCount == 1) "signal" else "signals"}"

        return PostureSnapshot(
            color = primary.posture,
            headline = primary.friendlyLabel,
            subheadline = "",
            distanceAndBearingText = distFormatted,
            bottomConfidenceText = bottomConfidenceText,
            primaryThreat = primary,
            activeThreats = sorted,
            enabledScopeText = enabledScopeText
        )
    }

    private fun effectiveNowMs(explicitNowMs: Long?): Long {
        if (explicitNowMs != null) return explicitNowMs
        val sys = System.currentTimeMillis()
        return if (lastEvaluateNowMs > 0L && kotlin.math.abs(sys - lastEvaluateNowMs) > 60_000L) {
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
        if (active != null) return active
        val cached = localThreatCache[id]
        if (cached != null) {
            val resolved = KokohoSignatures.resolveEntityLabel(context, cached.uuid, cached.agency.friendlyLabel)
            return if (resolved != cached.label) cached.copy(label = resolved) else cached
        }
        return null
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
            "REPEATER" -> 12000.0 * 10.0.pow(-(snrDb - 10) / 28.0)
            "AIR_ASSET" -> 18000.0 * 10.0.pow(-(snrDb - 10) / 30.0)
            else -> 1800.0 * 10.0.pow(-(snrDb - 10) / 22.0)
        }
        return baseM.roundToInt().coerceIn(35, 35_000)
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
