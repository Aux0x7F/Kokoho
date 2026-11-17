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
    private const val OBJECT_TTL_MS = 150 * 60_000L

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
        val status: String = "PENDING"
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
                    }
                    try {
                        if (conn.responseCode in 200..299) {
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
                            statusLabel = "HTTP ${conn.responseCode}"
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

    /** Executes an immediate Auxnet check-in (`POST /v1/checkin?since=<lastSeq>`). */
    fun checkinNow(context: Context) {
        val app = context.applicationContext
        if (!KokohoSignatures.isAuxnetEnabled(app)) {
            statusLabel = "offline-first"
            return
        }
        scope.launch {
            runCatching {
                val base = hubUrl(app)
                val conn = (URL("$base/v1/checkin?since=$lastSeq").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val snap = gson.fromJson(body, StateSnapshot::class.java)
                        if (snap != null) {
                            if (snap.seq > lastSeq) lastSeq = snap.seq
                            for (o in snap.activeObjects) {
                                if (o.uuid.isNotBlank()) objects[o.uuid] = o
                            }
                            lastCheckinMs = System.currentTimeMillis()
                            statusLabel = "live (seq ${snap.seq})"
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            syncHistoricalEntities(app)
            syncWitnessReports(app)
        }
    }

    /** Synchronizes persisted derived entities from `/v1/trends` so `1wk`, `1mo`, and `Heat` map modes have full history. */
    fun syncHistoricalEntities(context: Context, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!KokohoSignatures.isAuxnetEnabled(app)) {
            onComplete?.invoke()
            return
        }
        scope.launch {
            runCatching {
                val base = hubUrl(app)
                val conn = (URL("$base/v1/trends?since_ms=0&limit=2500").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
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
                            if (e.entityUuid.startsWith("3f7147ac", ignoreCase = true)) {
                                objects.remove(e.entityUuid)
                                continue
                            }
                            val pkClean = e.primaryFacetKey.trim().removePrefix("hw:mac:").uppercase()
                            if (pkClean.startsWith("40:84:32:") ||
                                pkClean.startsWith("24:79:2A:") ||
                                pkClean.startsWith("AC:82:47:") ||
                                pkClean.startsWith("C8:B3:73:") ||
                                pkClean.startsWith("00:11:AB:") ||
                                pkClean.startsWith("D8:12:65:") ||
                                pkClean.startsWith("60:E3:27:") ||
                                Regex("(?i)(?:\\b(?:QT|DR)[_\\s]+[0-9A-F]{8}\\b|\\bIDW\\d+\\b|\\bVeh\\s+\\d{2,4}(?:[-_ ]?(?:2\\.?4|5)g?)?\\b)").containsMatchIn("${e.label} ${e.primaryFacetKey}")
                            ) {
                                objects.remove(e.entityUuid)
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
                                    val uncertM = e.uncertaintyRadiusM.toInt().coerceAtLeast(20)
                                    val badges = evidenceList.map {
                                        EvidenceBadge(
                                            kind = it.kind,
                                            facetKey = it.facetKey,
                                            detail = it.detail,
                                            weight = it.weight,
                                            lastSeenMs = it.lastSeenMs
                                        )
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
                                        uncertaintyRadiusM = uncertM,
                                        bearingDeg = null,
                                        closingVelocityMps = null,
                                        recurrenceSummary = e.label,
                                        evidenceSources = badges,
                                        updatedAtMs = e.lastSeenMs,
                                        lat = e.estimatedLat,
                                        lon = e.estimatedLon,
                                        isApproximatedPosition = e.corroborationCount >= 2 || e.sightingCount >= 2 || uncertM <= 180,
                                        isPinpointed = uncertM <= 15,
                                        isDrone = ag == KokohoAgency.DRONE || e.primaryFacetKey.startsWith("uas:", ignoreCase = true),
                                        hardwareIdentifier = e.primaryFacetKey.ifBlank { null },
                                        sightingCount = maxOf(e.sightingCount, e.corroborationCount).coerceAtLeast(1),
                                        capabilities = KokohoSignatures.inferCapabilitiesFromEvidence(
                                            badges.map { it.facetKey },
                                            badges.map { it.detail } + e.label
                                        )
                                    )
                                }
                            }
                        }
                        if (ledgerHydrated.isNotEmpty()) {
                            KokohoEntityLedger.upsertBatch(app, ledgerHydrated, nowMs)
                        }
                    }
                } finally {
                    conn.disconnect()
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
        onResult: (List<RemoteFlightTrack>) -> Unit
    ) {
        val app = context.applicationContext
        scope.launch {
            val tracks = runCatching {
                val base = hubUrl(app)
                val q = if (!target.isNullOrBlank()) {
                    "?target=" + java.net.URLEncoder.encode(target.trim(), "UTF-8")
                } else {
                    ""
                }
                val conn = (URL("$base/v1/flights$q").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val type = object : com.google.gson.reflect.TypeToken<List<RemoteFlightTrack>>() {}.type
                        (gson.fromJson<List<RemoteFlightTrack>>(body, type) ?: emptyList()).filterNot { tr ->
                            KokohoSignatures.isAnomalousUnattributedAirRecord(
                                primaryKey = "adsb:",
                                category = tr.category,
                                label = tr.label,
                                facets = emptyList(),
                                avgVelocityMps = tr.avgVelocityMps
                            )
                        }
                    } else {
                        emptyList()
                    }
                } finally {
                    conn.disconnect()
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
                val base = if (context != null) hubUrl(context) else DEFAULT_HUB_URL
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
                    status = "PENDING"
                )
                val json = gson.toJson(dto)
                val conn = (URL("$base/v1/witness").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
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

    private fun postSingleBugReportBlocking(base: String, item: BugReportSubmission): Boolean {
        return runCatching {
            val json = gson.toJson(item)
            val conn = (URL("$base/v1/bugs").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    fun flushPendingBugReports(context: Context) {
        val app = context.applicationContext ?: context
        scope.launch {
            val queued = pendingBugReports(app)
            if (queued.isEmpty()) return@launch
            val base = hubUrl(app)
            val failed = ArrayList<BugReportSubmission>()
            for (item in queued) {
                if (!postSingleBugReportBlocking(base, item)) {
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
            val base = hubUrl(app)
            val ok = postSingleBugReportBlocking(base, submission)
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
        val app = context.applicationContext
        if (targetKey.isBlank()) {
            onResult(null)
            return
        }
        scope.launch {
            val result = runCatching {
                val base = hubUrl(app)
                val encoded = java.net.URLEncoder.encode(targetKey.trim(), "UTF-8")
                val conn = (URL("$base/v1/details?target_key=$encoded").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val dto = gson.fromJson(body, RemoteEntityDetailsDto::class.java)
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
                                    status = w.status.ifBlank { "APPROVED" }
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
                } finally {
                    conn.disconnect()
                }
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
        val app = context.applicationContext
        scope.launch {
            val ok = runCatching {
                val base = hubUrl(app)
                val conn = (URL("$base/v1/telemetry/batch").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
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
        val app = context.applicationContext
        if (!KokohoSignatures.isAuxnetEnabled(app)) return
        scope.launch {
            runCatching {
                val base = hubUrl(app)
                val conn = (URL("$base/v1/witness").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val type = object : com.google.gson.reflect.TypeToken<List<RemoteWitnessDto>>() {}.type
                        val list = gson.fromJson<List<RemoteWitnessDto>>(body, type) ?: emptyList()
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
                                    status = "APPROVED"
                                )
                            }
                        KokohoSignatures.mergeRemoteWitnessReports(app, mapped)
                    }
                } finally {
                    conn.disconnect()
                }
            }
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
        val app = context.applicationContext
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
                val conn = (URL("${hubUrl(app)}/v1/room/ingest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (rKey.isNotEmpty()) setRequestProperty("x-kokoho-room-key", rKey)
                }
                try {
                    conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode in 200..299) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val obj = gson.fromJson(resp, RemoteThreatObject::class.java)
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
                } finally {
                    conn.disconnect()
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
