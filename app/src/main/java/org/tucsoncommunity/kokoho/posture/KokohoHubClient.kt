package org.tucsoncommunity.kokoho.posture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero-GMS client for `kokoho.tucsoncommunity.online`:
 *  1. Anonymous subscriber SSE stream (`GET /v1/stream`) + 5-minute Auxnet check-in (`POST /v1/checkin`).
 *     Never transmits subscriber GPS coordinates.
 *  2. Optional trusted Sensor Room contributor (`POST /v1/room/ingest`) for nodes with RTL-SDR / OUI sensors.
 */
object KokohoHubClient {

    const val DEFAULT_HUB_URL = "https://kokoho.tucsoncommunity.online"
    private const val PREFS = "kokoho_hub_prefs"
    private const val KEY_HUB_URL = "hub_url"
    private const val KEY_SENSOR_CONTRIBUTE = "sensor_contribute"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_ROOM_KEY = "room_key"
    private const val KEY_SENSOR_SLOT = "sensor_slot"

    private const val CHECKIN_INTERVAL_MS = 5 * 60_000L
    private const val OBJECT_TTL_MS = 3 * 3_600_000L

    data class RemoteFlightTrack(
        @SerializedName("track_uuid") val trackUuid: String = "",
        @SerializedName("entity_uuid") val entityUuid: String = "",
        val category: String = "DHS_AMO",
        val label: String = "",
        @SerializedName("started_at_ms") val startedAtMs: Long = 0L,
        @SerializedName("ended_at_ms") val endedAtMs: Long = 0L,
        @SerializedName("avg_velocity_mps") val avgVelocityMps: Double = 0.0,
        @SerializedName("simplified_polyline") val simplifiedPolyline: List<List<Double>> = emptyList()
    ) {
        val points: List<Pair<Double, Double>>
            get() = simplifiedPolyline.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }

        /**
         * Strictly filters and vertex-clips this flight track to `[nowMs - windowMs, nowMs]`.
         * Supports both `[lat, lon, timestampMs]` 3-tuples and `[lat, lon]` polylines spanned across
         * `[startedAtMs, endedAtMs]`, linearly interpolating the boundary vertex at `cutoffMs`.
         */
        fun clippedPointsForWindow(nowMs: Long, windowMs: Long): List<Pair<Double, Double>> {
            if (windowMs <= 0L || simplifiedPolyline.size < 2) return emptyList()
            val cutoffMs = nowMs - windowMs

            // 1. Per-vertex epoch-ms timestamps when present in 3-tuples `[lat, lon, tsMs]`
            val hasVertexTimestamps = simplifiedPolyline.all { it.size >= 3 && it[2] > 1_000_000_000_000.0 }
            if (hasVertexTimestamps) {
                val out = ArrayList<Pair<Double, Double>>(simplifiedPolyline.size)
                for (i in simplifiedPolyline.indices) {
                    val cur = simplifiedPolyline[i]
                    val tCur = cur[2].toLong()
                    if (i > 0) {
                        val prev = simplifiedPolyline[i - 1]
                        val tPrev = prev[2].toLong()
                        if (tPrev < cutoffMs && tCur >= cutoffMs && tCur > tPrev) {
                            val alpha = ((cutoffMs - tPrev).toDouble() / (tCur - tPrev).toDouble()).coerceIn(0.0, 1.0)
                            val lat = prev[0] + (cur[0] - prev[0]) * alpha
                            val lon = prev[1] + (cur[1] - prev[1]) * alpha
                            out += lat to lon
                        }
                    }
                    if (tCur in cutoffMs..nowMs) {
                        out += cur[0] to cur[1]
                    } else if (i > 0) {
                        val prev = simplifiedPolyline[i - 1]
                        val tPrev = prev[2].toLong()
                        if (tPrev <= nowMs && tCur > nowMs && tCur > tPrev) {
                            val alpha = ((nowMs - tPrev).toDouble() / (tCur - tPrev).toDouble()).coerceIn(0.0, 1.0)
                            val lat = prev[0] + (cur[0] - prev[0]) * alpha
                            val lon = prev[1] + (cur[1] - prev[1]) * alpha
                            out += lat to lon
                        }
                    }
                }
                return if (out.size >= 2) out else emptyList()
            }

            // 2. `[lat, lon]` polyline spanned across `[startedAtMs, endedAtMs]`
            val rawPts = points
            if (rawPts.size < 2) return emptyList()
            val effEnd = if (endedAtMs > 0L) endedAtMs else startedAtMs
            val effStart = if (startedAtMs in 1L..effEnd) startedAtMs else effEnd
            if (effEnd > 0L && effEnd < cutoffMs) return emptyList()
            if (effStart <= 0L || (effStart >= cutoffMs && effEnd <= nowMs) || effEnd <= effStart) {
                return rawPts
            }

            val startFrac = if (effStart < cutoffMs) {
                ((cutoffMs - effStart).toDouble() / (effEnd - effStart).toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val endFrac = if (effEnd > nowMs) {
                ((nowMs - effStart).toDouble() / (effEnd - effStart).toDouble()).coerceIn(0.0, 1.0)
            } else {
                1.0
            }
            if (endFrac <= startFrac) return emptyList()

            val cumDist = DoubleArray(rawPts.size)
            var totalDist = 0.0
            for (i in 1 until rawPts.size) {
                val a = rawPts[i - 1]
                val b = rawPts[i]
                totalDist += org.tucsoncommunity.kokoho.service.DeviceRegistry.metersBetween(a.first, a.second, b.first, b.second)
                cumDist[i] = totalDist
            }
            if (totalDist < 1.0) {
                val i0 = (startFrac * (rawPts.size - 1)).toInt().coerceIn(0, rawPts.size - 1)
                val i1 = kotlin.math.ceil(endFrac * (rawPts.size - 1)).toInt().coerceIn(i0, rawPts.size - 1)
                val slice = rawPts.subList(i0, i1 + 1)
                return if (slice.size >= 2) slice else emptyList()
            }

            fun pointAtDistance(dTarget: Double): Pair<Double, Double> {
                if (dTarget <= 0.0) return rawPts.first()
                if (dTarget >= totalDist) return rawPts.last()
                for (i in 1 until rawPts.size) {
                    val d0 = cumDist[i - 1]
                    val d1 = cumDist[i]
                    if (dTarget <= d1 && d1 > d0) {
                        val t = ((dTarget - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
                        val p0 = rawPts[i - 1]
                        val p1 = rawPts[i]
                        return (p0.first + (p1.first - p0.first) * t) to (p0.second + (p1.second - p0.second) * t)
                    }
                }
                return rawPts.last()
            }

            val dStart = startFrac * totalDist
            val dEnd = endFrac * totalDist
            val clipped = ArrayList<Pair<Double, Double>>(rawPts.size + 2)
            clipped += pointAtDistance(dStart)
            for (i in 1 until rawPts.size - 1) {
                val d = cumDist[i]
                if (d > dStart + 0.25 && d < dEnd - 0.25) {
                    clipped += rawPts[i]
                }
            }
            val endPt = pointAtDistance(dEnd)
            val prev = clipped.last()
            if (kotlin.math.abs(endPt.first - prev.first) > 1e-7 || kotlin.math.abs(endPt.second - prev.second) > 1e-7) {
                clipped += endPt
            }
            return if (clipped.size >= 2) clipped else emptyList()
        }
    }

    private data class RemoteWitnessDto(
        @SerializedName("target_key") val targetKey: String = "",
        @SerializedName("entity_uuid") val entityUuid: String? = null,
        @SerializedName("agency_code") val agencyCode: String = "DHS_ICE",
        val department: String = "",
        @SerializedName("badge_number") val badgeNumber: String = "",
        @SerializedName("agent_name") val agentName: String = "",
        @SerializedName("vehicle_number") val vehicleNumber: String = "",
        val notes: String = "",
        @SerializedName("photo_data_url") val photoDataUrl: String? = null,
        @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L,
        val status: String = "PENDING",
        @SerializedName("confidence_pct") val confidencePct: Int = 85,
        @SerializedName("subject_kind") val subjectKind: String = "VEHICLE",
        @SerializedName("plate_number") val plateNumber: String = "",
        @SerializedName("plate_state") val plateState: String = "AZ",
        @SerializedName("partial_plate") val partialPlate: Boolean = false,
        @SerializedName("vehicle_color") val vehicleColor: String = "",
        @SerializedName("vehicle_body_type") val vehicleBodyType: String = "",
        @SerializedName("vehicle_make") val vehicleMake: String = "",
        @SerializedName("vehicle_model") val vehicleModel: String = "",
        @SerializedName("accessory_kind") val accessoryKind: String = "",
        val lat: Double? = null,
        val lon: Double? = null
    )

    data class SyncedPlateRecord(
        @SerializedName("plate_id") val plateId: String = "",
        @SerializedName("plate_number") val plateNumber: String = "",
        @SerializedName("state_code") val stateCode: String = "AZ",
        @SerializedName("partial_plate") val partialPlate: Boolean = false,
        @SerializedName("confidence_pct") val confidencePct: Int = 80,
        @SerializedName("agency_code") val agencyCode: String = "UNKNOWN",
        @SerializedName("suspected_agency") val suspectedAgency: String = "Unknown",
        @SerializedName("color") val color: String = "",
        @SerializedName("body_type") val bodyType: String = "",
        @SerializedName("make") val make: String = "",
        @SerializedName("model") val model: String = "",
        @SerializedName("details") val details: String = "",
        @SerializedName("observed_date") val observedDate: String = "",
        @SerializedName("entity_uuid") val entityUuid: String? = null,
        @SerializedName("detected") val detected: Boolean = false,
        @SerializedName("sighting_count") val sightingCount: Int = 1,
        @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L
    )

    data class BugReportSubmission(
        @SerializedName("report_id") val reportId: String = java.util.UUID.randomUUID().toString(),
        @SerializedName("created_at_ms") val createdAtMs: Long = System.currentTimeMillis(),
        val category: String = "App / UI",
        val summary: String = "",
        @SerializedName("target_key") val targetKey: String? = null,
        @SerializedName("app_version") val appVersion: String = "",
        @SerializedName("release_channel") val releaseChannel: String = "",
        @SerializedName("device_info") val deviceInfo: String = "",
        val diagnostics: String = "",
        val status: String = "OPEN"
    )

    private data class RemoteEntityDetailsDto(
        @SerializedName("target_key") val targetKey: String = "",
        val entity: RemoteTrendEntity? = null,
        @SerializedName("approved_witness") val approvedWitness: RemoteWitnessDto? = null,
        @SerializedName("pending_review_count") val pendingReviewCount: Int = 0,
        val capabilities: List<String> = emptyList(),
        @SerializedName("recent_tracks") val recentTracks: List<RemoteFlightTrack> = emptyList()
    )

    data class FetchedEntityDetails(
        val targetKey: String,
        val approvedWitness: WitnessReportRecord?,
        val pendingReviewCount: Int,
        val sightingCount: Int,
        val corroborationCount: Int,
        val capabilities: List<String>,
        val recentTracks: List<RemoteFlightTrack>
    )

    data class RemoteConfidence(
        val overall: Double = 0.8,
        val classification: Double = 0.8,
        val spatial: Double = 0.7,
        @SerializedName("temporal_freshness") val temporalFreshness: Double = 1.0
    )

    data class RemoteLocation(
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        @SerializedName("uncertainty_radius_m") val uncertaintyRadiusM: Double = 500.0,
        @SerializedName("h3_res8") val h3Res8: Long = 0L,
        @SerializedName("bearing_deg") val bearingDeg: Double? = null,
        @SerializedName("velocity_mps") val velocityMps: Double? = null,
        @SerializedName("altitude_ft") val altitudeFt: Int? = null,
        @SerializedName("altitude_ft_agl") val altitudeFtAgl: Int? = null,
        @SerializedName("propagation_class") val propagationClass: String = "GROUND_DIRECT",
        @SerializedName("recent_trail") val recentTrail: List<List<Double>> = emptyList()
    )

    data class RemoteEvidence(
        val kind: String = "",
        @SerializedName("facet_key") val facetKey: String = "",
        val detail: String = "",
        val weight: Double = 0.5,
        @SerializedName("last_seen_ms") val lastSeenMs: Long = 0L
    )

    data class RemoteClassification(
        @SerializedName("master_group") val masterGroup: String = "DHS",
        val category: String = "DHS_CBP",
        val label: String = "",
        @SerializedName("recurrence_summary") val recurrenceSummary: String = "",
        @SerializedName("evidence_sources") val evidenceSources: List<RemoteEvidence> = emptyList()
    )

    data class RemoteThreatObject(
        val uuid: String = "",
        @SerializedName("track_uuid") val trackUuid: String = "",
        @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L,
        val confidence: RemoteConfidence = RemoteConfidence(),
        val location: RemoteLocation = RemoteLocation(),
        val classification: RemoteClassification = RemoteClassification(),
        val sightingCount: Int = 1,
        val corroborationCount: Int = 1
    )

    private data class RemoteTrendEntity(
        @SerializedName("entity_uuid") val entityUuid: String = "",
        @SerializedName("primary_facet_key") val primaryFacetKey: String = "",
        val category: String = "DHS_CBP",
        val label: String = "",
        @SerializedName("first_seen_ms") val firstSeenMs: Long = 0L,
        @SerializedName("last_seen_ms") val lastSeenMs: Long = 0L,
        @SerializedName("sighting_count") val sightingCount: Int = 1,
        @SerializedName("estimated_lat") val estimatedLat: Double = 32.2226,
        @SerializedName("estimated_lon") val estimatedLon: Double = -110.9747,
        @SerializedName("uncertainty_radius_m") val uncertaintyRadiusM: Double = 350.0,
        @SerializedName("corroboration_count") val corroborationCount: Int = 1,
        val facets: List<String> = emptyList()
    )

    data class RemoteHeatmapCell(
        @SerializedName("h3_res8") val h3Res8: Long = 0L,
        @SerializedName("center_lat") val centerLat: Double = 0.0,
        @SerializedName("center_lon") val centerLon: Double = 0.0,
        val category: String = "DHS_CBP",
        @SerializedName("hit_count") val hitCount: Int = 1,
        @SerializedName("max_confidence") val maxConfidence: Double = 0.5
    )

    private data class RemoteLearnedMacDto(
        val mac: String = "",
        @SerializedName("oui_prefix") val ouiPrefix: String = "",
        @SerializedName("nic_u24") val nicU24: Long = 0L,
        @SerializedName("inferred_category") val inferredCategory: String = "DHS_CBP",
        @SerializedName("inferred_label") val inferredLabel: String = "",
        @SerializedName("reputation_score") val reputationScore: Double = 0.5,
        @SerializedName("corroborated_hits") val corroboratedHits: Int = 0,
        @SerializedName("total_hits") val totalHits: Int = 1,
        @SerializedName("distinct_days") val distinctDays: Int = 1,
        @SerializedName("distinct_h3_res10") val distinctH3Res10: Int = 1,
        @SerializedName("association_pattern") val associationPattern: String = "TRANSIENT",
        val capabilities: List<String> = emptyList(),
        @SerializedName("ssid_hint") val ssidHint: String = "",
        @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L
    )

    private data class RemoteMacBlockDto(
        @SerializedName("oui_prefix") val ouiPrefix: String = "",
        @SerializedName("start_mac") val startMac: String = "",
        @SerializedName("end_mac") val endMac: String = "",
        @SerializedName("min_nic_u24") val minNicU24: Long = 0L,
        @SerializedName("max_nic_u24") val maxNicU24: Long = 0L,
        @SerializedName("inferred_category") val inferredCategory: String = "DHS_CBP",
        @SerializedName("inferred_label") val inferredLabel: String = "",
        @SerializedName("confirmed_count") val confirmedCount: Int = 0,
        @SerializedName("confidence_boost") val confidenceBoost: Double = 0.5
    )

    private data class TrendsSnapshot(
        val tracks: List<RemoteFlightTrack> = emptyList(),
        @SerializedName("heatmap_cells") val heatmapCells: List<RemoteHeatmapCell> = emptyList(),
        val entities: List<RemoteTrendEntity> = emptyList(),
        @SerializedName("learned_macs") val learnedMacs: List<RemoteLearnedMacDto> = emptyList(),
        @SerializedName("mac_blocks") val macBlocks: List<RemoteMacBlockDto> = emptyList()
    )

    @Volatile private var latestTrendsTracks: List<RemoteFlightTrack> = emptyList()
    @Volatile private var latestHeatmapCells: List<RemoteHeatmapCell> = emptyList()

    fun cachedFlightTracks(): List<RemoteFlightTrack> = latestTrendsTracks
    fun cachedHeatmapCells(): List<RemoteHeatmapCell> = latestHeatmapCells

    data class StateSnapshot(
        val seq: Long = 0L,
        @SerializedName("server_time_ms") val serverTimeMs: Long = 0L,
        @SerializedName("active_objects") val activeObjects: List<RemoteThreatObject> = emptyList()
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val objects = ConcurrentHashMap<String, RemoteThreatObject>()

    @Volatile
    var lastSeq: Long = 0L
        private set

    @Volatile
    var lastCheckinMs: Long = 0L
        private set

    @Volatile
    var statusLabel: String = "standby"
        private set

    private var streamJob: Job? = null

    fun hubUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HUB_URL, DEFAULT_HUB_URL)?.trim()?.trimEnd('/') ?: DEFAULT_HUB_URL

    fun setHubUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_HUB_URL, url.trim().trimEnd('/').ifEmpty { DEFAULT_HUB_URL })
        }
    }

    fun isSensorContributor(context: Context): Boolean =
        KokohoSignatures.isContributeObservationsEnabled(context)

    fun setSensorContributor(context: Context, enabled: Boolean, roomId: String, roomKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingSlot = prefs.getString(KEY_SENSOR_SLOT, null)
            ?: "owl-${java.util.UUID.randomUUID().toString().take(8)}"
        prefs.edit {
            putBoolean(KEY_SENSOR_CONTRIBUTE, enabled)
            putString(KEY_ROOM_ID, roomId.trim().ifEmpty { "tucson-sector-1" })
            putString(KEY_ROOM_KEY, roomKey.trim())
            putString(KEY_SENSOR_SLOT, existingSlot)
        }
        KokohoSignatures.setContributeObservationsEnabled(context, enabled)
    }

    fun roomId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOM_ID, "tucson-sector-1") ?: "tucson-sector-1"

    fun roomKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOM_KEY, "") ?: ""

    private fun sensorSlot(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_SENSOR_SLOT, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "owl-${java.util.UUID.randomUUID().toString().take(8)}"
        prefs.edit { putString(KEY_SENSOR_SLOT, generated) }
        return generated
    }

    /**
     * Immediately stops contributing and clears the local ephemeral session slot.
     * Server-side observations are processed exclusively in volatile memory and decay ephemerally.
     */
    fun purgeContributedObservations(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_SENSOR_SLOT) }
    }

    fun activeObjects(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = OBJECT_TTL_MS): List<RemoteThreatObject> {
        return objects.values.filter { nowMs - it.updatedAtMs <= ttlMs }
    }

    fun ingestThreatObjectJson(json: String): RemoteThreatObject? {
        val obj = gson.fromJson(json, RemoteThreatObject::class.java)
        if (obj != null && obj.uuid.isNotBlank()) {
            objects[obj.uuid] = obj
        }
        return obj
    }

    fun clearCachedObjects() {
        objects.clear()
        lastSeq = 0L
    }

    fun clearAllOnRevocation(context: Context) {
        objects.clear()
        lastSeq = 0L
        latestTrendsTracks = emptyList()
        latestHeatmapCells = emptyList()
        val app = context.applicationContext ?: context
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_SYNCED_PLATES)
            remove("synced_plates_json")
        }
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!KokohoSignatures.isAuxnetEnabled(app)) {
            stop(app)
            statusLabel = "offline-first"
            return
        }
        if (streamJob?.isActive == true) return
        schedulePeriodicCheckin(app)
        flushPendingBugReports(app)
        streamJob = scope.launch {
            checkinNow(app)
            var lastTrendsRefreshAt = System.currentTimeMillis()
            while (isActive) {
                try {
                    if (!DeviceTrustManager.isEnrolled(app)) {
                        statusLabel = "unenrolled"
                        delay(15_000L)
                        continue
                    }
                    if (VpnGeoGate.checkHubEgressAllowedBlocking(app, forceSeatedCheck = true) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
                        statusLabel = "vpn-required (Tucson IP)"
                        delay(10_000L)
                        continue
                    }
                    val nowLoop = System.currentTimeMillis()
                    if (nowLoop - lastTrendsRefreshAt >= 60_000L) {
                        lastTrendsRefreshAt = nowLoop
                        syncHistoricalEntities(app)
                        syncWitnessReports(app)
                    }
                    val base = hubUrl(app)
                    val url = URL("$base/v1/stream?since=$lastSeq&wait_ms=20000")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10_000
                        readTimeout = 28_000
                        setRequestProperty("Accept", "text/event-stream")
                        if (lastSeq > 0) setRequestProperty("Last-Event-ID", lastSeq.toString())
                        DeviceTrustManager.attachSignedHeaders(app, this)
                    }
                    try {
                        val code = conn.responseCode
                        if (code in 200..299) {
                            statusLabel = "connected"
                            lastCheckinMs = System.currentTimeMillis()
                            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                                var currentId = lastSeq
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val l = line ?: break
                                    when {
                                        l.startsWith("id:") -> {
                                            l.removePrefix("id:").trim().toLongOrNull()?.let { currentId = it }
                                        }
                                        l.startsWith("data:") -> {
                                            val data = l.removePrefix("data:").trim()
                                            runCatching {
                                                val obj = gson.fromJson(data, RemoteThreatObject::class.java)
                                                if (obj != null && obj.uuid.isNotBlank()) {
                                                    objects[obj.uuid] = obj
                                                    if (currentId > lastSeq) lastSeq = currentId
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val seatHdr = conn.getHeaderField("x-kokoho-seat-status")
                            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                            DeviceTrustManager.inspectResponseForRevocation(app, code, seatHdr, err)
                            statusLabel = "HTTP $code"
                            delay(5_000L)
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (_: Exception) {
                    statusLabel = "reconnecting"
                    delay(5_000L)
                }
            }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        streamJob?.cancel()
        streamJob = null
        statusLabel = "stopped"
        cancelPeriodicCheckin(context.applicationContext)
    }

    /** Executes an immediate Auxnet check-in (`POST /v1/checkin?since=<lastSeq>`) over ECDH AEAD envelope. */
    fun checkinNow(context: Context) {
        val app = context.applicationContext ?: context
        if (!KokohoSignatures.isAuxnetEnabled(app)) {
            statusLabel = "offline-first"
            return
        }
        scope.launch {
            runCatching {
                val resp = DeviceTrustManager.executeEnvelopedPost(app, "/v1/checkin?since=$lastSeq", "{}")
                if (resp != null && resp.code in 200..299 && !resp.body.isNullOrBlank()) {
                    val snap = gson.fromJson(resp.body, StateSnapshot::class.java)
                    if (snap != null) {
                        if (snap.seq > lastSeq) lastSeq = snap.seq
                        for (o in snap.activeObjects) {
                            if (o.uuid.isNotBlank()) objects[o.uuid] = o
                        }
                        lastCheckinMs = System.currentTimeMillis()
                        statusLabel = "live (seq ${snap.seq})"
                    }
                }
            }
            if (DeviceTrustManager.isEnrolled(app)) {
                PriorityVault.syncAllAsync(app)
                syncHistoricalEntities(app)
                syncWitnessReports(app)
            }
        }
    }

    /** Fetches current state snapshot (`GET /v1/state`) over ECDH AEAD envelope. */
    fun fetchStateSnapshot(context: Context, onComplete: ((StateSnapshot?) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        scope.launch {
            val parsed = runCatching {
                val resp = DeviceTrustManager.executeEnvelopedGet(app, "/v1/state")
                if (resp != null && resp.code in 200..299 && !resp.body.isNullOrBlank()) {
                    gson.fromJson(resp.body, StateSnapshot::class.java)?.also { snap ->
                        if (snap.seq > lastSeq) lastSeq = snap.seq
                        for (o in snap.activeObjects) {
                            if (o.uuid.isNotBlank()) objects[o.uuid] = o
                        }
                    }
                } else null
            }.getOrNull()
            if (onComplete != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onComplete(parsed) }
            }
        }
    }

    /** Fetches dynamic RF/OUI signature catalog (`GET /v1/signatures`) over ECDH AEAD envelope. */
    fun syncSignatures(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        scope.launch {
            val ok = runCatching {
                val resp = DeviceTrustManager.executeEnvelopedGet(app, "/v1/signatures")
                resp != null && resp.code in 200..299 && !resp.body.isNullOrBlank()
            }.getOrDefault(false)
            if (onComplete != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onComplete(ok) }
            }
        }
    }

    /** Synchronizes persisted derived entities from `/v1/trends` so `1wk`, `1mo`, and `Heat` map modes have full history. */
    fun syncHistoricalEntities(context: Context, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext ?: context
        if (!KokohoSignatures.isAuxnetEnabled(app)) {
            onComplete?.invoke()
            return
        }
        scope.launch {
            runCatching {
                val envResp = DeviceTrustManager.executeEnvelopedGet(app, "/v1/trends?since_ms=0&limit=2500")
                if (envResp != null && envResp.code in 200..299 && !envResp.body.isNullOrBlank()) {
                    val body = envResp.body
                    val snap = gson.fromJson(body, TrendsSnapshot::class.java)
                        if (snap != null) {
                            latestTrendsTracks = snap.tracks.filterNot { tr ->
                                KokohoSignatures.isAnomalousUnattributedAirRecord(
                                    primaryKey = "adsb:",
                                    category = tr.category,
                                    label = tr.label,
                                    facets = emptyList(),
                                    avgVelocityMps = tr.avgVelocityMps
                                )
                            }
                            latestHeatmapCells = snap.heatmapCells
                            if (snap.learnedMacs.isNotEmpty() || snap.macBlocks.isNotEmpty()) {
                                KokohoSignatures.mergeRemoteMacIntel(
                                    app,
                                    snap.learnedMacs.map {
                                        KokohoSignatures.LearnedMacIntel(
                                            mac = it.mac,
                                            ouiPrefix = it.ouiPrefix,
                                            nicU24 = it.nicU24,
                                            agencyId = it.inferredCategory,
                                            label = it.inferredLabel,
                                            reputationScore = it.reputationScore,
                                            corroboratedHits = it.corroboratedHits,
                                            totalHits = it.totalHits,
                                            distinctDays = it.distinctDays,
                                            distinctH3Res10 = it.distinctH3Res10,
                                            associationPattern = it.associationPattern,
                                            capabilities = it.capabilities,
                                            ssidHint = it.ssidHint,
                                            updatedAtMs = it.updatedAtMs
                                        )
                                    },
                                    snap.macBlocks.map {
                                        KokohoSignatures.LearnedMacBlock(
                                            ouiPrefix = it.ouiPrefix,
                                            startMac = it.startMac,
                                            endMac = it.endMac,
                                            minNicU24 = it.minNicU24,
                                            maxNicU24 = it.maxNicU24,
                                            agencyId = it.inferredCategory,
                                            label = it.inferredLabel,
                                            confirmedCount = it.confirmedCount,
                                            confidenceBoost = it.confidenceBoost
                                        )
                                    }
                                )
                            }
                        }
                        val nowMs = System.currentTimeMillis()
                        val ledgerHydrated = ArrayList<EvaluatedThreat>()
                        for (e in snap?.entities.orEmpty()) {
                            if (e.entityUuid.isBlank()) continue
                            if (KokohoEntityLedger.isSuppressedFalsePositiveRow(
                                    uuid = e.entityUuid,
                                    hwRaw = e.primaryFacetKey,
                                    summaryRaw = e.label,
                                    extraText = e.facets.joinToString(" ")
                                )
                            ) {
                                objects.remove(e.entityUuid)
                                KokohoEntityLedger.remove(app, e.entityUuid)
                                continue
                            }
                            if (KokohoSignatures.isFlockCamera(e.primaryFacetKey, e.label)) continue
                            if (KokohoSignatures.isAnomalousUnattributedAirRecord(
                                    primaryKey = e.primaryFacetKey,
                                    category = e.category,
                                    label = e.label,
                                    facets = e.facets
                                )
                            ) {
                                objects.remove(e.entityUuid)
                                continue
                            }
                            val pkClean = e.primaryFacetKey.trim().removePrefix("hw:mac:").removePrefix("OUI:").uppercase()
                            val resolvedCategory = when {
                                pkClean.startsWith("00:30:44:74:") || pkClean.startsWith("06:30:44:74:") -> "SHERIFF"
                                pkClean.startsWith("00:30:44:22:8") -> "FIRE"
                                else -> e.category
                            }
                            val isAir = resolvedCategory.equals("DHS_AMO", ignoreCase = true) ||
                                e.primaryFacetKey.startsWith("ICAO:", ignoreCase = true) ||
                                e.primaryFacetKey.startsWith("adsb:", ignoreCase = true)
                            // Only treat airborne trends entities as active aircraft objects while currently in flight (<= 5 min).
                            if (isAir && nowMs - e.lastSeenMs > 5 * 60_000L) continue
                            val existing = objects[e.entityUuid]
                            if (existing != null && existing.updatedAtMs > e.lastSeenMs) continue
                            val propClass = if (isAir) "AIRBORNE" else "GROUND_DIRECT"
                            val evidenceList = (listOf(e.primaryFacetKey) + e.facets)
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .map { fk ->
                                    RemoteEvidence(
                                        kind = if (isAir) "ADSB_AIR" else "DERIVED_ENTITY",
                                        facetKey = fk,
                                        detail = e.label.ifBlank { fk },
                                        weight = 0.85,
                                        lastSeenMs = e.lastSeenMs
                                    )
                                }
                            val confVal = when {
                                e.corroborationCount >= 3 || e.sightingCount >= 3 -> 0.92
                                e.corroborationCount == 2 || e.sightingCount == 2 -> 0.78
                                else -> 0.62
                            }
                            objects[e.entityUuid] = RemoteThreatObject(
                                uuid = e.entityUuid,
                                trackUuid = e.entityUuid,
                                updatedAtMs = e.lastSeenMs,
                                confidence = RemoteConfidence(
                                    overall = confVal,
                                    classification = confVal,
                                    spatial = confVal,
                                    temporalFreshness = 1.0
                                ),
                                location = RemoteLocation(
                                    lat = e.estimatedLat,
                                    lon = e.estimatedLon,
                                    uncertaintyRadiusM = e.uncertaintyRadiusM,
                                    propagationClass = propClass
                                ),
                                classification = RemoteClassification(
                                    masterGroup = if (resolvedCategory.startsWith("DHS")) "DHS" else "OTHER",
                                    category = resolvedCategory,
                                    label = e.label,
                                    recurrenceSummary = e.label,
                                    evidenceSources = evidenceList
                                ),
                                sightingCount = e.sightingCount.coerceAtLeast(1),
                                corroborationCount = e.corroborationCount.coerceAtLeast(1)
                            )
                            if (!isAir) {
                                val ag = KokohoAgency.fromCode(resolvedCategory)
                                if (ag != null) {
                                    val cInt = (confVal * 100.0).toInt().coerceIn(50, 96)
                                    val uncertM = kotlin.math.round(e.uncertaintyRadiusM).toInt().coerceAtLeast(15)
                                    val badges = evidenceList.map {
                                        EvidenceBadge(
                                            kind = it.kind,
                                            facetKey = it.facetKey,
                                            detail = it.detail,
                                            weight = it.weight,
                                            lastSeenMs = it.lastSeenMs
                                        )
                                    }
                                    val droneMeta = KokohoPostureEngine.extractDroneTelemetryFromFacets(badges.map { it.facetKey })
                                    val isDroneRow = ag == KokohoAgency.DRONE || droneMeta.isDroneFacet
                                    val isStandaloneOp = KokohoPostureEngine.isStandaloneDroneOperator(badges, e.primaryFacetKey)
                                    val hasRemoteIdMeta = isDroneRow && !isStandaloneOp && (droneMeta.altFtAgl != null || droneMeta.operatorLat != null)
                                    val cleanTrendLabel = if (isDroneRow || isStandaloneOp) {
                                        KokohoPostureEngine.sanitizeDroneOperatorSuffix(e.label)
                                    } else {
                                        e.label
                                    }
                                    ledgerHydrated += EvaluatedThreat(
                                        uuid = e.entityUuid,
                                        trackUuid = e.entityUuid,
                                        agency = ag,
                                        label = ag.friendlyLabel,
                                        posture = PostureColor.YELLOW,
                                        overallConfidence = cInt,
                                        classificationConfidence = cInt,
                                        spatialConfidence = cInt,
                                        estimatedDistanceM = 9_999,
                                        uncertaintyRadiusM = if (isStandaloneOp) 15 else uncertM,
                                        bearingDeg = null,
                                        closingVelocityMps = null,
                                        recurrenceSummary = cleanTrendLabel,
                                        evidenceSources = badges,
                                        updatedAtMs = e.lastSeenMs,
                                        lat = e.estimatedLat,
                                        lon = e.estimatedLon,
                                        isApproximatedPosition = isStandaloneOp || e.corroborationCount >= 2 || e.sightingCount >= 2 || uncertM <= 180,
                                        isPinpointed = isStandaloneOp || uncertM <= KokohoPostureEngine.PINPOINT_POINT_RADIUS_M || (hasRemoteIdMeta && uncertM <= 40),
                                        aircraftTrackDeg = droneMeta.headingDeg,
                                        isDrone = isDroneRow,
                                        altitudeFtAgl = droneMeta.altFtAgl,
                                        operatorLat = if (isStandaloneOp) (droneMeta.operatorLat ?: e.estimatedLat) else droneMeta.operatorLat,
                                        operatorLon = if (isStandaloneOp) (droneMeta.operatorLon ?: e.estimatedLon) else droneMeta.operatorLon,
                                        hardwareIdentifier = e.primaryFacetKey.ifBlank { null },
                                        sightingCount = maxOf(e.sightingCount, e.corroborationCount).coerceAtLeast(1),
                                        capabilities = KokohoSignatures.inferCapabilitiesFromEvidence(
                                            badges.map { it.facetKey },
                                            badges.map { it.detail } + cleanTrendLabel
                                        )
                                    )
                                }
                            }
                        }
                        if (ledgerHydrated.isNotEmpty()) {
                            KokohoEntityLedger.upsertBatch(app, ledgerHydrated, nowMs)
                        }
                }
            }
            if (onComplete != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete.invoke()
                }
            }
        }
    }

    /** Fetches recent aircraft/drone flight tracks from `/v1/flights`. */
    fun fetchFlightTracks(
        context: Context,
        target: String? = null,
        windowMs: Long? = null,
        onResult: (List<RemoteFlightTrack>) -> Unit
    ) {
        val app = context.applicationContext ?: context
        scope.launch {
            val nowMs = System.currentTimeMillis()
            val tracks = runCatching {
                val params = ArrayList<String>(2)
                if (!target.isNullOrBlank()) {
                    params += "target=" + java.net.URLEncoder.encode(target.trim(), "UTF-8")
                }
                if (windowMs != null && windowMs > 0L) {
                    val sinceMs = (nowMs - windowMs).coerceAtLeast(0L)
                    params += "since_ms=$sinceMs"
                }
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                val envResp = DeviceTrustManager.executeEnvelopedGet(app, "/v1/flights$q")
                if (envResp != null && envResp.code in 200..299 && !envResp.body.isNullOrBlank()) {
                    val type = object : com.google.gson.reflect.TypeToken<List<RemoteFlightTrack>>() {}.type
                    val parsed = (gson.fromJson<List<RemoteFlightTrack>>(envResp.body, type) ?: emptyList()).filterNot { tr ->
                        KokohoSignatures.isAnomalousUnattributedAirRecord(
                            primaryKey = "adsb:",
                            category = tr.category,
                            label = tr.label,
                            facets = emptyList(),
                            avgVelocityMps = tr.avgVelocityMps
                        )
                    }
                    if (target.isNullOrBlank() && parsed.isNotEmpty()) {
                        val merged = LinkedHashMap<String, RemoteFlightTrack>()
                        for (t in parsed) {
                            val k = t.trackUuid.ifBlank { "${t.entityUuid}:${t.startedAtMs}" }
                            merged[k] = t
                        }
                        for (t in latestTrendsTracks) {
                            val k = t.trackUuid.ifBlank { "${t.entityUuid}:${t.startedAtMs}" }
                            merged.putIfAbsent(k, t)
                        }
                        latestTrendsTracks = merged.values.toList()
                    }
                    parsed
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(tracks)
            }
        }
    }

    /** Submits a Witness Report to `/v1/witness` (queued for manual review on server). */
    fun submitWitnessReport(report: WitnessReportRecord, context: Context? = null, onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val ok = runCatching {
                val appCtx = context?.applicationContext ?: context ?: org.tucsoncommunity.kokoho.KokohoApp.appContext
                    ?: return@runCatching false
                val dto = RemoteWitnessDto(
                    targetKey = report.targetKey,
                    entityUuid = report.entityUuid,
                    agencyCode = report.agencyCode,
                    department = report.department,
                    badgeNumber = report.badgeNumber,
                    agentName = report.agentName,
                    vehicleNumber = report.vehicleNumber,
                    notes = report.notes,
                    photoDataUrl = report.photoDataUrl,
                    updatedAtMs = report.updatedAtMs,
                    status = "PENDING",
                    confidencePct = report.confidencePct,
                    subjectKind = report.subjectKind,
                    plateNumber = report.plateNumber,
                    plateState = report.plateState,
                    partialPlate = report.partialPlate,
                    vehicleColor = report.vehicleColor,
                    vehicleBodyType = report.vehicleBodyType,
                    vehicleMake = report.vehicleMake,
                    vehicleModel = report.vehicleModel,
                    accessoryKind = report.accessoryKind,
                    lat = report.lat,
                    lon = report.lon
                )
                val json = gson.toJson(dto)
                val envResp = DeviceTrustManager.executeEnvelopedPost(appCtx, "/v1/witness", json)
                envResp != null && envResp.code in 200..299
            }.getOrDefault(false)
            if (onComplete != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete(ok)
                }
            }
        }
    }

    private const val KEY_PENDING_BUGS = "kokoho_pending_bugs_json"
    private val bugQueueLock = Any()

    fun pendingBugReports(context: Context): List<BugReportSubmission> = synchronized(bugQueueLock) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_BUGS, null) ?: return emptyList()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<BugReportSubmission>>() {}.type
            gson.fromJson<List<BugReportSubmission>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun savePendingBugReports(context: Context, list: List<BugReportSubmission>) = synchronized(bugQueueLock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_BUGS, gson.toJson(list.takeLast(50)))
            .apply()
    }

    fun enqueueBugReport(context: Context, submission: BugReportSubmission) {
        synchronized(bugQueueLock) {
            val current = pendingBugReports(context).filterNot { it.reportId == submission.reportId }
            savePendingBugReports(context, current + submission)
        }
    }

    fun clearPendingBugReportsForTest(context: Context) {
        synchronized(bugQueueLock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_BUGS).apply()
        }
    }

    /**
     * Builds a diagnostic summary (app version, device model/SDK, active sensors, & recent crash log tail)
     * without attaching personal GPS coordinates.
     */
    fun buildBugDiagnostics(context: Context, includeLogs: Boolean): BugReportSubmission {
        val appVer = "${org.tucsoncommunity.kokoho.BuildConfig.VERSION_NAME} (${org.tucsoncommunity.kokoho.BuildConfig.VERSION_CODE})${if (org.tucsoncommunity.kokoho.BuildConfig.DEBUG) " debug" else ""}"
        val devInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        val diag = if (!includeLogs) {
            ""
        } else {
            buildString {
                append("scanning=").append(org.tucsoncommunity.kokoho.service.ScanForegroundService.isRunning)
                append(", sharing=").append(KokohoSignatures.isSharingActive(context))
                append(", bt=").append(org.tucsoncommunity.kokoho.util.Permissions.isBluetoothEnabled(context))
                append(", hub=").append(statusLabel)
                val crashFile = org.tucsoncommunity.kokoho.util.CrashLog.file(context)
                if (crashFile.exists() && crashFile.length() > 0L) {
                    val tail = runCatching { crashFile.readText().takeLast(3500) }.getOrNull()
                    if (!tail.isNullOrBlank()) {
                        append("\n--- recent crash_log.txt ---\n").append(tail)
                    }
                }
            }
        }
        return BugReportSubmission(
            appVersion = appVer,
            releaseChannel = org.tucsoncommunity.kokoho.BuildConfig.RELEASE_CHANNEL,
            deviceInfo = devInfo,
            diagnostics = diag
        )
    }

    private fun postSingleBugReportBlocking(app: Context, item: BugReportSubmission): Boolean {
        return runCatching {
            val json = gson.toJson(item)
            val envResp = DeviceTrustManager.executeEnvelopedPost(app, "/v1/bugs", json)
            envResp != null && envResp.code in 200..299
        }.getOrDefault(false)
    }

    fun flushPendingBugReports(context: Context) {
        val app = context.applicationContext ?: context
        scope.launch {
            val queued = pendingBugReports(app)
            if (queued.isEmpty()) return@launch
            val failed = ArrayList<BugReportSubmission>()
            for (item in queued) {
                if (!postSingleBugReportBlocking(app, item)) {
                    failed += item
                }
            }
            synchronized(bugQueueLock) {
                val latest = pendingBugReports(app)
                val sentIds = (queued.map { it.reportId }.toSet() - failed.map { it.reportId }.toSet())
                savePendingBugReports(app, latest.filterNot { it.reportId in sentIds })
            }
        }
    }

    /**
     * Durably queues a generic or contextual Bug Report locally and immediately attempts `POST /v1/bugs`
     * to `kokoho-hub` for batch developer review (independent of passive background Share toggle).
     */
    fun submitBugReport(
        context: Context,
        category: String,
        summary: String,
        targetKey: String? = null,
        includeDiagnostics: Boolean = true,
        onComplete: ((uploadedNow: Boolean) -> Unit)? = null
    ): BugReportSubmission {
        val app = context.applicationContext ?: context
        val template = buildBugDiagnostics(app, includeDiagnostics)
        val submission = template.copy(
            category = category.trim().ifEmpty { "App / UI" },
            summary = summary.trim(),
            targetKey = targetKey?.takeIf { it.isNotBlank() }
        )
        enqueueBugReport(app, submission)
        scope.launch {
            val ok = postSingleBugReportBlocking(app, submission)
            if (ok) {
                synchronized(bugQueueLock) {
                    val remaining = pendingBugReports(app).filterNot { it.reportId == submission.reportId }
                    savePendingBugReports(app, remaining)
                }
            }
            flushPendingBugReports(app)
            if (onComplete != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete(ok)
                }
            }
        }
        return submission
    }

    /**
     * Fetches the specific approved details and review metadata for an entity when the user
     * opens the Details screen (`GET /v1/details?target_key=...`).
     */
    fun fetchEntityDetails(
        context: Context,
        targetKey: String,
        onResult: (FetchedEntityDetails?) -> Unit
    ) {
        val app = context.applicationContext ?: context
        if (targetKey.isBlank()) {
            onResult(null)
            return
        }
        scope.launch {
            val result = runCatching {
                val encoded = java.net.URLEncoder.encode(targetKey.trim(), "UTF-8")
                val envResp = DeviceTrustManager.executeEnvelopedGet(app, "/v1/details?target_key=$encoded")
                if (envResp != null && envResp.code in 200..299 && !envResp.body.isNullOrBlank()) {
                    val dto = gson.fromJson(envResp.body, RemoteEntityDetailsDto::class.java)
                    if (dto != null) {
                        val approved = dto.approvedWitness?.let { w ->
                            WitnessReportRecord(
                                targetKey = w.targetKey,
                                entityUuid = w.entityUuid,
                                agencyCode = w.agencyCode,
                                department = w.department,
                                badgeNumber = w.badgeNumber,
                                agentName = w.agentName,
                                vehicleNumber = w.vehicleNumber,
                                notes = w.notes,
                                photoDataUrl = w.photoDataUrl,
                                updatedAtMs = w.updatedAtMs,
                                status = w.status.ifBlank { "APPROVED" },
                                subjectKind = w.subjectKind.ifBlank { "VEHICLE" },
                                plateNumber = w.plateNumber,
                                plateState = w.plateState.ifBlank { "AZ" },
                                partialPlate = w.partialPlate,
                                vehicleColor = w.vehicleColor,
                                vehicleBodyType = w.vehicleBodyType,
                                vehicleMake = w.vehicleMake,
                                vehicleModel = w.vehicleModel,
                                confidencePct = w.confidencePct,
                                accessoryKind = w.accessoryKind,
                                lat = w.lat,
                                lon = w.lon
                            )
                        }
                        if (approved != null) {
                            KokohoSignatures.mergeRemoteWitnessReports(app, listOf(approved))
                        }
                        FetchedEntityDetails(
                            targetKey = dto.targetKey.ifBlank { targetKey },
                            approvedWitness = approved,
                            pendingReviewCount = dto.pendingReviewCount,
                            sightingCount = dto.entity?.sightingCount ?: 1,
                            corroborationCount = dto.entity?.corroborationCount ?: 1,
                            capabilities = dto.capabilities,
                            recentTracks = dto.recentTracks
                        )
                    } else null
                } else null
            }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    /** Uploads a debug/dev multi-pass field telemetry batch to `/v1/telemetry/batch`. */
    fun uploadFieldTelemetryBatch(
        context: Context,
        payloadJson: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val app = context.applicationContext ?: context
        scope.launch {
            val ok = runCatching {
                val envResp = DeviceTrustManager.executeEnvelopedPost(app, "/v1/telemetry/batch", payloadJson)
                envResp != null && envResp.code in 200..299
            }.getOrDefault(false)
            if (onComplete != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete(ok)
                }
            }
        }
    }

    /** Synchronizes APPROVED Witness Reports from `/v1/witness`. */
    fun syncWitnessReports(context: Context) {
        val app = context.applicationContext ?: context
        if (!KokohoSignatures.isAuxnetEnabled(app)) return
        scope.launch {
            runCatching {
                val envResp = DeviceTrustManager.executeEnvelopedGet(app, "/v1/witness")
                if (envResp != null && envResp.code in 200..299 && !envResp.body.isNullOrBlank()) {
                    val type = object : com.google.gson.reflect.TypeToken<List<RemoteWitnessDto>>() {}.type
                    val list = gson.fromJson<List<RemoteWitnessDto>>(envResp.body, type) ?: emptyList()
                    val mapped = list
                        .filter { it.status.isBlank() || it.status.equals("APPROVED", ignoreCase = true) }
                        .map {
                            WitnessReportRecord(
                                targetKey = it.targetKey,
                                entityUuid = it.entityUuid,
                                agencyCode = it.agencyCode,
                                department = it.department,
                                badgeNumber = it.badgeNumber,
                                agentName = it.agentName,
                                vehicleNumber = it.vehicleNumber,
                                notes = it.notes,
                                photoDataUrl = it.photoDataUrl,
                                updatedAtMs = it.updatedAtMs,
                                status = "APPROVED",
                                subjectKind = it.subjectKind.ifBlank { "VEHICLE" },
                                plateNumber = it.plateNumber,
                                plateState = it.plateState.ifBlank { "AZ" },
                                partialPlate = it.partialPlate,
                                vehicleColor = it.vehicleColor,
                                vehicleBodyType = it.vehicleBodyType,
                                vehicleMake = it.vehicleMake,
                                vehicleModel = it.vehicleModel,
                                confidencePct = it.confidencePct,
                                accessoryKind = it.accessoryKind,
                                lat = it.lat,
                                lon = it.lon
                            )
                        }
                    KokohoSignatures.mergeRemoteWitnessReports(app, mapped)
                }
            }
        }
    }

    private const val KEY_SYNCED_PLATES = "kokoho_synced_plates_json"

    fun clearInMemoryPlatesCache() {
        // PriorityVault.inMemoryPlates is the single authoritative RAM cache.
    }

    /** Reads hardware-sealed Priority 1 plates from `PriorityVault` on demand in RAM. */
    fun syncedPlates(context: Context): List<SyncedPlateRecord> =
        PriorityVault.getPlates(context)

    fun cachedPlates(context: Context): List<SyncedPlateRecord> = syncedPlates(context)

    /** Synchronizes Priority 1 plates (`GET /v1/sync/p1-plates`) into hardware-sealed `PriorityVault`. */
    fun syncPlates(context: Context, onComplete: ((List<SyncedPlateRecord>) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        if (!DeviceTrustManager.isEnrolled(app)) {
            onComplete?.invoke(syncedPlates(app))
            return
        }
        PriorityVault.syncP1PlatesAsync(app) { plates ->
            onComplete?.invoke(plates)
        }
    }

    /**
     * Publishes a sensor observation to the trusted Detection Room (`/v1/room/ingest`)
     * when the user has opted into Sensor Room Contribution.
     */
    fun publishSensorReportIfEnabled(
        context: Context,
        lat: Double,
        lon: Double,
        snrDb: Double,
        modality: String,
        primaryFacet: String,
        additionalFacets: List<String>,
        agency: KokohoAgency,
        label: String,
        propagationClass: String,
        uncertaintyRadiusM: Double? = null,
        isDerived: Boolean = false,
        localSightings: Int = 1,
        ssidHint: String? = null
    ) {
        val app = context.applicationContext ?: context
        if (!KokohoSignatures.isAuxnetEnabled(app) || !isSensorContributor(app)) return
        val rId = roomId(app)
        val rKey = roomKey(app)
        val slot = sensorSlot(app)
        scope.launch {
            runCatching {
                val payload = mutableMapOf<String, Any>(
                    "room_id" to rId,
                    "sensor_slot" to slot,
                    "sensor_lat" to lat,
                    "sensor_lon" to lon,
                    "snr_db" to snrDb,
                    "modality" to modality,
                    "primary_facet" to primaryFacet,
                    "additional_facets" to additionalFacets,
                    "category" to agency.id,
                    "label" to label,
                    "propagation_class" to propagationClass,
                    "timestamp_ms" to System.currentTimeMillis(),
                    "is_derived" to isDerived,
                    "local_sightings" to localSightings
                )
                if (uncertaintyRadiusM != null) {
                    payload["uncertainty_radius_m"] = uncertaintyRadiusM
                }
                if (!ssidHint.isNullOrBlank()) {
                    payload["ssid_hint"] = ssidHint
                }
                val json = gson.toJson(payload)
                val headers = if (rKey.isNotEmpty()) mapOf("x-kokoho-room-key" to rKey) else emptyMap()
                val envResp = DeviceTrustManager.executeEnvelopedPost(app, "/v1/room/ingest", json, extraHeaders = headers)
                if (envResp != null && envResp.code in 200..299 && !envResp.body.isNullOrBlank()) {
                    val obj = gson.fromJson(envResp.body, RemoteThreatObject::class.java)
                    val isDerivedResponse = obj != null && (
                        obj.location.uncertaintyRadiusM <= 160.0 ||
                            obj.confidence.spatial >= 0.80 ||
                            obj.classification.evidenceSources.size >= 2 ||
                            obj.location.propagationClass.equals("AIRBORNE", ignoreCase = true)
                        )
                    val isSinglePeerRfSelfEcho = primaryFacet.startsWith("rf:chan:") &&
                        obj != null &&
                        obj.corroborationCount <= 1 &&
                        obj.classification.evidenceSources.size <= 1
                    if (isDerivedResponse && !isSinglePeerRfSelfEcho && obj.uuid.isNotBlank()) {
                        objects[obj.uuid] = obj
                    }
                }
            }
        }
    }

    @Volatile private var cachedCheckinPi: PendingIntent? = null
    @Volatile private var lastScheduledAlarmAtMs: Long = 0L

    private fun getOrCreateCheckinPendingIntent(context: Context): PendingIntent {
        cachedCheckinPi?.let { return it }
        val app = context.applicationContext
        return PendingIntent.getBroadcast(
            app,
            48222,
            Intent(app, AuxnetCheckinReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ).also { cachedCheckinPi = it }
    }

    fun schedulePeriodicCheckin(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastScheduledAlarmAtMs < 60_000L) return
        lastScheduledAlarmAtMs = now
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = getOrCreateCheckinPendingIntent(app)
        val triggerAt = now + CHECKIN_INTERVAL_MS
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelPeriodicCheckin(context: Context) {
        lastScheduledAlarmAtMs = 0L
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = cachedCheckinPi ?: return
        runCatching { am.cancel(pi) }
    }
}
