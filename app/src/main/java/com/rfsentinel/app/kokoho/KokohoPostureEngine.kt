package com.rfsentinel.app.kokoho

import android.content.Context
import android.location.Location
import com.rfsentinel.app.sdr.RadioLog
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
    val lon: Double?
)

data class PostureSnapshot(
    val color: PostureColor,
    val headline: String,
    val subheadline: String,
    val distanceAndBearingText: String,
    val primaryThreat: EvaluatedThreat?,
    val activeThreats: List<EvaluatedThreat>,
    val enabledScopeText: String
)

object KokohoPostureEngine {

    private const val ACTIVE_WINDOW_MS = 120_000L
    private const val COLOCATION_WINDOW_MS = 45_000L

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

    fun evaluate(context: Context, nowMs: Long = System.currentTimeMillis()): PostureSnapshot {
        KokohoSignatures.ensureLoaded(context)
        val enabled = KokohoSignatures.getEnabledAgencies(context)
        val enabledScopeText = if (enabled.isEmpty()) {
            "No agencies selected"
        } else {
            val dhsCount = enabled.count { it.isDhs }
            val dhsPart = if (dhsCount == KokohoAgency.DHS_AGENCIES.size) {
                "DHS (All: CBP, ICE, HSI, AMO, FPS)"
            } else if (dhsCount > 0) {
                "DHS (" + enabled.filter { it.isDhs }.joinToString(", ") { it.shortName } + ")"
            } else null
            val otherPart = enabled.filter { !it.isDhs }.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.shortName }
            listOfNotNull(dhsPart, otherPart).joinToString(" + ")
        }

        val userLoc = ScanForegroundService.lastFix
        val evaluated = mutableMapOf<String, EvaluatedThreat>()

        // 1. Evaluate remote sanitized ThreatObjects from Auxnet kokoho-hub
        for (remote in KokohoHubClient.activeObjects(nowMs)) {
            val agency = KokohoAgency.fromCode(remote.classification.category) ?: continue
            if (agency !in enabled) continue

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

            evaluated[remote.uuid] = EvaluatedThreat(
                uuid = remote.uuid,
                trackUuid = remote.trackUuid,
                agency = agency,
                label = remote.classification.label,
                posture = posture,
                overallConfidence = (remote.confidence.overall * 100).roundToInt().coerceIn(1, 99),
                classificationConfidence = (remote.confidence.classification * 100).roundToInt().coerceIn(1, 99),
                spatialConfidence = (remote.confidence.spatial * 100).roundToInt().coerceIn(1, 99),
                estimatedDistanceM = distM,
                uncertaintyRadiusM = remote.location.uncertaintyRadiusM.roundToInt(),
                bearingDeg = bearingFromUser,
                closingVelocityMps = remote.location.velocityMps,
                recurrenceSummary = remote.classification.recurrenceSummary,
                evidenceSources = badges,
                updatedAtMs = remote.updatedAtMs,
                lat = remote.location.lat,
                lon = remote.location.lon
            )
        }

        // 2. Evaluate local RTL-SDR RF hits & P25 talkgroups, plus local watchlisted OUI devices
        val localRfHits = RadioLog.current(nowMs).filter { nowMs - it.lastSeen <= ACTIVE_WINDOW_MS }
        val localDevices = DeviceRegistry.snapshot(nowMs).filter { nowMs - it.lastSeen <= ACTIVE_WINDOW_MS }

        // Check for colocated Fleet OUI devices first so they can fuse with local RF hits
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
                    KokohoSignatures.FleetOuiSig(snap.mac.take(8), ag.id, best.label, best.confidence)
                }
            val sig = matchedSig ?: return@mapNotNull null
            if (sig.agencyEnum !in enabled) return@mapNotNull null
            LocalOuiCandidate(
                mac = snap.mac,
                sig = sig,
                rssi = snap.rssi,
                rising = snap.rssi >= snap.bestRssi - 3 && snap.sightings >= 3 && snap.rssi > -76,
                lastSeen = snap.lastSeen,
                lat = snap.located?.lat ?: userLoc?.latitude,
                lon = snap.located?.lon ?: userLoc?.longitude
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
            val estDistM = estimateRfDistanceMeters(rf.peakSnrDb, sig?.role ?: "DIRECT_TACTICAL")
            val uncertaintyM = (estDistM * 0.45).roundToInt().coerceAtLeast(45)

            val badges = mutableListOf(
                EvidenceBadge(
                    kind = "RF_${sig?.mode ?: "SDR"}",
                    facetKey = facetKey,
                    detail = "${sig?.alpha ?: "%.4f MHz".format(Locale.US, rf.freqHz / 1e6)} (${rf.peakSnrDb} dB SNR, ${rf.trend.name})",
                    weight = 0.65,
                    lastSeenMs = rf.lastSeen
                )
            )
            if (colocatedOui != null) {
                badges += EvidenceBadge(
                    kind = "WIFI_BLE_OUI",
                    facetKey = "hw:mac:${colocatedOui.mac}",
                    detail = "${colocatedOui.sig.label} (${colocatedOui.mac}, ${colocatedOui.rssi} dBm)",
                    weight = 0.35,
                    lastSeenMs = colocatedOui.lastSeen
                )
            }

            val isApproaching = rf.trend == RadioLog.Trend.CLOSER || colocatedOui?.rising == true
            val posture = when {
                estDistM <= 250 || (colocatedOui != null && colocatedOui.rssi >= -65) -> PostureColor.RED
                isApproaching -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            val combinedLabel = if (colocatedOui != null) {
                "${sig?.alpha ?: agency.shortName} + ${colocatedOui.sig.label} (Colocated)"
            } else {
                sig?.let { "${it.alpha} · ${it.description}" } ?: "${agency.fullName} (${"%.4f".format(Locale.US, rf.freqHz / 1e6)} MHz)"
            }

            evaluated[uuid] = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = combinedLabel,
                posture = posture,
                overallConfidence = if (colocatedOui != null) 94 else 82,
                classificationConfidence = if (colocatedOui != null) 96 else 85,
                spatialConfidence = if (colocatedOui != null) 85 else 65,
                estimatedDistanceM = estDistM,
                uncertaintyRadiusM = uncertaintyM,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "RF channel active (${rf.peakSnrDb} dB peak SNR)",
                evidenceSources = badges,
                updatedAtMs = rf.lastSeen,
                lat = userLoc?.latitude,
                lon = userLoc?.longitude
            )
        }

        // 3. Remaining un-fused OUI candidates
        for (oui in ouiCandidates) {
            if (oui.mac in consumedOuiMacs) continue
            val facetKey = "hw:mac:${oui.mac}"
            val uuid = deterministicUuidV5(facetKey)
            if (uuid in evaluated) continue

            val estDistM = estimateRssiDistanceMeters(oui.rssi)
            val posture = when {
                estDistM <= 60 || oui.rssi >= -65 -> PostureColor.RED
                oui.rising -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            evaluated[uuid] = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = oui.sig.agencyEnum,
                label = "${oui.sig.agencyEnum.shortName} · ${oui.sig.label}",
                posture = posture,
                overallConfidence = oui.sig.confidence,
                classificationConfidence = oui.sig.confidence,
                spatialConfidence = 70,
                estimatedDistanceM = estDistM,
                uncertaintyRadiusM = (estDistM * 0.4).roundToInt().coerceAtLeast(20),
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "Local OUI beacon (${oui.rssi} dBm)",
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "WIFI_BLE_OUI",
                        facetKey = facetKey,
                        detail = "${oui.sig.label} (${oui.mac}, ${oui.rssi} dBm)",
                        weight = 0.75,
                        lastSeenMs = oui.lastSeen
                    )
                ),
                updatedAtMs = oui.lastSeen,
                lat = oui.lat,
                lon = oui.lon
            )
        }

        // 4. Active P25 talkgroups matching enabled agencies
        for (tgRow in SdrRadio.p25Log.rows()) {
            val tgId = tgRow.tg ?: continue
            if (nowMs - tgRow.lastAt > ACTIVE_WINDOW_MS) continue
            val tgSig = KokohoSignatures.matchTalkgroup(tgId) ?: continue
            val agency = tgSig.agencyEnum
            if (agency !in enabled) continue

            val facetKey = "rf:tg:$tgId"
            val uuid = deterministicUuidV5(facetKey)
            val inputHeardRecently = tgRow.inputAt > 0L && (nowMs - tgRow.inputAt <= ACTIVE_WINDOW_MS)
            val estDistM = if (inputHeardRecently) {
                estimateRfDistanceMeters(if (tgRow.inputSnr > 0) tgRow.inputSnr else 25, "MOBILE_INPUT")
            } else {
                3500
            }
            val posture = when {
                inputHeardRecently && estDistM <= 300 -> PostureColor.RED
                inputHeardRecently -> PostureColor.ORANGE
                else -> PostureColor.YELLOW
            }

            evaluated[uuid] = EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = "${agency.shortName} · ${tgSig.label} (TG $tgId)",
                posture = posture,
                overallConfidence = if (inputHeardRecently) 90 else 78,
                classificationConfidence = 90,
                spatialConfidence = if (inputHeardRecently) 80 else 40,
                estimatedDistanceM = estDistM,
                uncertaintyRadiusM = (estDistM * 0.5).roundToInt(),
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = "${tgRow.calls} P25 call${if (tgRow.calls == 1) "" else "s"} decoded",
                evidenceSources = listOf(
                    EvidenceBadge(
                        kind = "P25_TALKGROUP",
                        facetKey = facetKey,
                        detail = "Talkgroup $tgId: ${tgSig.label}${if (inputHeardRecently) " (Close mobile input ${tgRow.inputSnr} dB)" else ""}",
                        weight = 0.80,
                        lastSeenMs = tgRow.lastAt
                    )
                ),
                updatedAtMs = tgRow.lastAt,
                lat = userLoc?.latitude,
                lon = userLoc?.longitude
            )
        }

        val sorted = evaluated.values.sortedWith(
            compareByDescending<EvaluatedThreat> { it.posture.ordinal }
                .thenBy { it.estimatedDistanceM }
        )

        val primary = sorted.firstOrNull()
        if (primary == null) {
            return PostureSnapshot(
                color = PostureColor.GREEN,
                headline = "No threats detected",
                subheadline = "Monitoring: $enabledScopeText",
                distanceAndBearingText = if (ScanForegroundService.isRunning) {
                    "Sensors active · Auxnet stream ${KokohoHubClient.statusLabel}"
                } else {
                    "Tap Start to begin passive RF + OUI monitoring"
                },
                primaryThreat = null,
                activeThreats = emptyList(),
                enabledScopeText = enabledScopeText
            )
        }

        val distFormatted = formatDistance(primary.estimatedDistanceM, primary.uncertaintyRadiusM)
        val brgFormatted = primary.bearingDeg?.let { " · Bearing $it° (${compassDir(it)})" } ?: ""
        val velFormatted = primary.closingVelocityMps?.takeIf { it > 1.0 }
            ?.let { " · %.1f m/s".format(Locale.US, it) } ?: ""

        return PostureSnapshot(
            color = primary.posture,
            headline = primary.label,
            subheadline = "${primary.posture.badgeLabel} · ${primary.agency.fullName}",
            distanceAndBearingText = "Est. distance $distFormatted$brgFormatted$velFormatted",
            primaryThreat = primary,
            activeThreats = sorted,
            enabledScopeText = enabledScopeText
        )
    }

    private fun estimateRfDistanceMeters(snrDb: Int, role: String): Int {
        val base = when (role) {
            "MOBILE_INPUT", "DIRECT_TACTICAL" -> (1600.0 * 10.0.pow(-snrDb / 28.0)).roundToInt().coerceIn(35, 2500)
            "AIR_ASSET" -> (8000.0 * 10.0.pow(-snrDb / 35.0)).roundToInt().coerceIn(400, 12000)
            else -> (4500.0 * 10.0.pow(-snrDb / 32.0)).roundToInt().coerceIn(250, 8000)
        }
        return base
    }

    private fun estimateRssiDistanceMeters(rssiDbm: Int): Int {
        val txPower = -55.0
        val n = 2.4
        val d = 10.0.pow((txPower - rssiDbm) / (10.0 * n))
        return d.roundToInt().coerceIn(5, 600)
    }

    private fun isHeadingTowardUser(
        userLat: Double?,
        userLon: Double?,
        objLat: Double,
        objLon: Double,
        objBearingDeg: Double?,
        objVelocityMps: Double?
    ): Boolean {
        if (userLat == null || userLon == null || objBearingDeg == null || objVelocityMps == null) return false
        if (objVelocityMps < 1.8) return false
        val bearingToUser = bearingDeg(objLat, objLon, userLat, userLon)
        val diff = abs(((objBearingDeg - bearingToUser + 540.0) % 360.0) - 180.0)
        return diff <= 55.0
    }

    private fun formatDistance(distM: Int, uncertaintyM: Int): String =
        if (distM >= 1000) {
            "~%.1f km (±%dm)".format(Locale.US, distM / 1000.0, uncertaintyM)
        } else {
            "~%d m (±%dm)".format(Locale.US, distM, uncertaintyM)
        }

    private fun compassDir(deg: Int): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((deg + 22) % 360) / 45]
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
