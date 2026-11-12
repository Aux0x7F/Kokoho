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
        @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L
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
        val classification: RemoteClassification = RemoteClassification()
    )

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
        objects.entries.removeIf { nowMs - it.value.updatedAtMs > ttlMs }
        return objects.values.toList()
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
        schedulePeriodicCheckin(app)
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            checkinNow(app)
            while (isActive) {
                try {
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
            syncWitnessReports(app)
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
                        gson.fromJson<List<RemoteFlightTrack>>(body, type) ?: emptyList()
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

    /** Submits a verified Witness Report to `/v1/witness`. */
    fun submitWitnessReport(report: WitnessReportRecord, context: Context? = null) {
        scope.launch {
            runCatching {
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
                    updatedAtMs = report.updatedAtMs
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
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    /** Synchronizes verified Witness Reports from `/v1/witness`. */
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
                        val mapped = list.map {
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
                                updatedAtMs = it.updatedAtMs
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
        propagationClass: String
    ) {
        val app = context.applicationContext
        if (!KokohoSignatures.isAuxnetEnabled(app) || !isSensorContributor(app)) return
        val rId = roomId(app)
        val rKey = roomKey(app)
        val slot = sensorSlot(app)
        scope.launch {
            runCatching {
                val payload = mapOf(
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
                    "timestamp_ms" to System.currentTimeMillis()
                )
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
                        if (obj != null && obj.uuid.isNotBlank()) {
                            objects[obj.uuid] = obj
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    fun schedulePeriodicCheckin(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            48222,
            Intent(context, AuxnetCheckinReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + CHECKIN_INTERVAL_MS
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelPeriodicCheckin(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            48222,
            Intent(context, AuxnetCheckinReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.cancel(pi) }
    }
}
