package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.tucsoncommunity.kokoho.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-resolution field telemetry & multi-pass ("circling the block") signal logger for
 * debug/dev builds and field research sessions.
 *
 * Captures:
 * 1. Raw RF peaks (including uncataloged VHF/UHF/700/800 MHz peaks for offline signature discovery,
 *    without alerting or creating false-positive threats).
 * 2. Wi-Fi / BLE / P25 / ADS-B facet observations with GPS kinematics (lat, lon, speed, bearing).
 * 3. Multi-pass ("circling the block") trajectory & convergence metrics per target (pass count,
 *    angular spread around target, uncertainty radius narrowing across passes).
 * 4. Co-occurrence graph edges (pairs of facets observed together within 30s and <= 150m across passes)
 *    synced in batches to `/v1/telemetry/batch` for server-side batch association discovery.
 * 5. User interaction events (opening details page, submitting witness reports for manual review).
 */
object FieldTelemetryLogger {
    private const val PREFS_NAME = "kokoho_field_telemetry_prefs"
    private const val KEY_ENABLED = "field_telemetry_enabled"
    private const val DIR_NAME = "field_telemetry"
    private const val MAX_STORAGE_BYTES = 250L * 1024L * 1024L // 250 MB retention on debug/dev
    private const val EVAL_LOG_INTERVAL_MS = 4_000L
    private const val BATCH_UPLOAD_INTERVAL_MS = 90_000L
    private const val CO_OCCURRENCE_WINDOW_MS = 30_000L
    private const val PASS_GAP_MS = 22_000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var sessionId: String = "sess-" + UUID.randomUUID().toString().take(12)

    @Volatile
    private var sessionStartedAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastEvalLoggedAtMs: Long = 0L

    @Volatile
    private var lastBatchUploadAtMs: Long = 0L

    @Volatile
    private var totalSamplesInBatch: Int = 0

    @Volatile
    private var maxPassesInBatch: Int = 1

    private data class FacetPassTracker(
        val facetKey: String,
        var category: String,
        var modality: String,
        var ssidHint: String?,
        var isFlagged: Boolean,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var lastPassIncrementMs: Long,
        var passCount: Int,
        var peakSignalDb: Double,
        var bestLat: Double,
        var bestLon: Double,
        var initialRadiusM: Double,
        var latestRadiusM: Double,
        var minBearingDeg: Double,
        var maxBearingDeg: Double
    )

    private data class CoOccurrenceAccumulator(
        val facetA: String,
        val facetB: String,
        var categoryA: String,
        var categoryB: String,
        var sampleCount: Int,
        var passCount: Int,
        var minDistanceM: Double,
        var peakSnrA: Double,
        var peakSnrB: Double,
        var lastLat: Double?,
        var lastLon: Double?,
        var lastUpdatedMs: Long
    )

    private val passTrackers = ConcurrentHashMap<String, FacetPassTracker>()
    private val coOccurrences = ConcurrentHashMap<String, CoOccurrenceAccumulator>()

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, BuildConfig.DEBUG)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /**
     * Records a raw SDR radio peak (both cataloged and uncataloged) so field drives preserve
     * complete RF spectrum evidence without fabricating user-visible false positives.
     */
    fun recordRawRadioPeak(
        context: Context,
        freqHz: Long,
        snrDb: Int,
        confidence: Int,
        bandLabel: String,
        isRepeat: Boolean,
        isTdma: Boolean,
        matchedAgency: String?,
        matchedAlpha: String?,
        location: Location?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!isEnabled(context)) return
        val row = JSONObject().apply {
            put("type", "RAW_RF_PEAK")
            put("session_id", sessionId)
            put("ts_ms", nowMs)
            put("freq_hz", freqHz)
            put("freq_mhz", String.format(Locale.US, "%.4f", freqHz / 1_000_000.0))
            put("snr_db", snrDb)
            put("confidence", confidence)
            put("band", bandLabel)
            put("repeat", isRepeat)
            put("tdma", isTdma)
            put("matched_agency", matchedAgency ?: "UNCATALOGED")
            put("matched_alpha", matchedAlpha ?: "")
            if (location != null) {
                put("lat", location.latitude)
                put("lon", location.longitude)
                if (location.hasSpeed()) put("speed_mps", location.speed.toDouble())
                if (location.hasBearing()) put("bearing_deg", location.bearing.toDouble())
                if (location.hasAccuracy()) put("acc_m", location.accuracy.toDouble())
            }
        }
        appendJsonLineAsync(context, row)
    }

    /**
     * Records a posture evaluation pass, updates multi-pass ("circling the block") trackers,
     * accumulates co-occurring facet edges, and periodically syncs batch telemetry to the server.
     */
    fun recordEvaluationPass(
        context: Context,
        nowMs: Long,
        userLoc: Location?,
        rawObservations: List<KokohoPostureEngine.RawSensorReportCandidate>,
        activeThreats: List<EvaluatedThreat>
    ) {
        val localJsonlEnabled = isEnabled(context)
        val bulkShareEnabled = localJsonlEnabled || KokohoSignatures.isSharingActive(context)
        if (!bulkShareEnabled) return
        if (rawObservations.isEmpty() && activeThreats.isEmpty()) return
        if (nowMs - lastEvalLoggedAtMs < EVAL_LOG_INTERVAL_MS) return
        lastEvalLoggedAtMs = nowMs
        totalSamplesInBatch += rawObservations.size.coerceAtLeast(1)

        // Update per-facet multi-pass trackers ("circling the block" detection & passive observation queue)
        for (obs in rawObservations) {
            val obsRadiusM = obs.uncertaintyRadiusM ?: 450.0
            val tracker = passTrackers.compute(obs.facetKey) { _, existing ->
                val bearing = if (userLoc != null && userLoc.hasBearing()) userLoc.bearing.toDouble() else 0.0
                if (existing == null) {
                    FacetPassTracker(
                        facetKey = obs.facetKey,
                        category = obs.agency.id,
                        modality = obs.modality,
                        ssidHint = obs.ssidHint,
                        isFlagged = obs.isFlagged,
                        firstSeenMs = nowMs,
                        lastSeenMs = nowMs,
                        lastPassIncrementMs = nowMs,
                        passCount = 1,
                        peakSignalDb = obs.signalDb,
                        bestLat = obs.rawLat,
                        bestLon = obs.rawLon,
                        initialRadiusM = obsRadiusM,
                        latestRadiusM = obsRadiusM,
                        minBearingDeg = bearing,
                        maxBearingDeg = bearing
                    )
                } else {
                    val gapMs = nowMs - existing.lastSeenMs
                    val sinceLastPassMs = nowMs - existing.lastPassIncrementMs
                    val bearingDelta = angularDistanceDeg(existing.minBearingDeg, bearing)
                    val newPass = gapMs >= PASS_GAP_MS || (sinceLastPassMs >= 25_000L && bearingDelta >= 55.0)
                    if (newPass) {
                        existing.passCount += 1
                        existing.lastPassIncrementMs = nowMs
                    }
                    existing.lastSeenMs = nowMs
                    existing.category = obs.agency.id
                    existing.modality = obs.modality
                    if (!obs.ssidHint.isNullOrBlank()) existing.ssidHint = obs.ssidHint
                    existing.isFlagged = existing.isFlagged || obs.isFlagged
                    if (obs.signalDb >= existing.peakSignalDb) {
                        existing.peakSignalDb = obs.signalDb
                        existing.bestLat = obs.rawLat
                        existing.bestLon = obs.rawLon
                    }
                    existing.latestRadiusM = obsRadiusM
                    existing.minBearingDeg = minOf(existing.minBearingDeg, bearing)
                    existing.maxBearingDeg = maxOf(existing.maxBearingDeg, bearing)
                    existing
                }
            }
            if (tracker != null && tracker.passCount > maxPassesInBatch) {
                maxPassesInBatch = tracker.passCount
            }
        }

        // Update co-occurrence edges for pairs of distinct facets observed simultaneously
        if (rawObservations.size in 2..16) {
            val distinct = rawObservations.distinctBy { it.facetKey }
            for (i in distinct.indices) {
                for (j in i + 1 until distinct.size) {
                    val a = distinct[i]
                    val b = distinct[j]
                    val distM = haversineMeters(a.rawLat, a.rawLon, b.rawLat, b.rawLon)
                    if (distM > 150.0) continue
                    val (first, second) = if (a.facetKey <= b.facetKey) a to b else b to a
                    val edgeKey = "${first.facetKey}|${second.facetKey}"
                    val passA = passTrackers[first.facetKey]?.passCount ?: 1
                    val passB = passTrackers[second.facetKey]?.passCount ?: 1
                    val sharedPasses = minOf(passA, passB).coerceAtLeast(1)
                    coOccurrences.compute(edgeKey) { _, existing ->
                        if (existing == null) {
                            CoOccurrenceAccumulator(
                                facetA = first.facetKey,
                                facetB = second.facetKey,
                                categoryA = first.agency.id,
                                categoryB = second.agency.id,
                                sampleCount = 1,
                                passCount = sharedPasses,
                                minDistanceM = distM,
                                peakSnrA = first.signalDb,
                                peakSnrB = second.signalDb,
                                lastLat = first.rawLat,
                                lastLon = first.rawLon,
                                lastUpdatedMs = nowMs
                            )
                        } else {
                            existing.sampleCount += 1
                            existing.passCount = maxOf(existing.passCount, sharedPasses)
                            existing.minDistanceM = minOf(existing.minDistanceM, distM)
                            existing.peakSnrA = maxOf(existing.peakSnrA, first.signalDb)
                            existing.peakSnrB = maxOf(existing.peakSnrB, second.signalDb)
                            existing.lastLat = first.rawLat
                            existing.lastLon = first.rawLon
                            existing.lastUpdatedMs = nowMs
                            existing
                        }
                    }
                }
            }
        }

        val obsJson = JSONArray()
        for (obs in rawObservations.take(25)) {
            val tracker = passTrackers[obs.facetKey]
            obsJson.put(
                JSONObject().apply {
                    put("facet", obs.facetKey)
                    put("agency", obs.agency.id)
                    put("modality", obs.modality)
                    put("signal_db", obs.signalDb)
                    put("radius_m", (obs.uncertaintyRadiusM ?: 450.0).roundToInt())
                    put("sightings", obs.localSightings)
                    put("pass_count", tracker?.passCount ?: 1)
                    if (!obs.ssidHint.isNullOrBlank()) put("ssid", obs.ssidHint)
                }
            )
        }

        val threatsJson = JSONArray()
        for (t in activeThreats.take(15)) {
            threatsJson.put(
                JSONObject().apply {
                    put("uuid", t.uuid)
                    put("agency", t.agency.id)
                    put("label", t.label)
                    put("conf", t.combinedConfidence)
                    put("dist_m", t.estimatedDistanceM)
                    put("radius_m", t.uncertaintyRadiusM)
                    put("pinpointed", t.isPinpointed)
                    put("evidence_count", t.evidenceCount)
                    if (t.lat != null && t.lon != null) {
                        put("lat", t.lat)
                        put("lon", t.lon)
                    }
                }
            )
        }

        val evalEvent = JSONObject().apply {
            put("type", "EVAL_PASS")
            put("session_id", sessionId)
            put("ts_ms", nowMs)
            if (userLoc != null) {
                put("user_lat", userLoc.latitude)
                put("user_lon", userLoc.longitude)
                if (userLoc.hasSpeed()) put("user_speed_mps", userLoc.speed.toDouble())
                if (userLoc.hasBearing()) put("user_bearing_deg", userLoc.bearing.toDouble())
                if (userLoc.hasAccuracy()) put("user_acc_m", userLoc.accuracy.toDouble())
            }
            put("raw_observations", obsJson)
            put("active_threats", threatsJson)
        }

        if (localJsonlEnabled) {
            appendJsonLineAsync(context, evalEvent)
        }

        if (nowMs - lastBatchUploadAtMs >= BATCH_UPLOAD_INTERVAL_MS &&
            (coOccurrences.isNotEmpty() || passTrackers.isNotEmpty())
        ) {
            flushBatchToServerAsync(context, nowMs)
        }
    }

    /**
     * Logs user interaction events (e.g. tapping a detection to open Details, submitting details for review).
     */
    fun recordUserInteraction(
        context: Context,
        action: String,
        targetKey: String,
        agency: String? = null,
        detail: String? = null
    ) {
        if (!isEnabled(context)) return
        val nowMs = System.currentTimeMillis()
        val row = JSONObject().apply {
            put("type", "USER_INTERACTION")
            put("session_id", sessionId)
            put("ts_ms", nowMs)
            put("action", action)
            put("target_key", targetKey)
            if (!agency.isNullOrBlank()) put("agency", agency)
            if (!detail.isNullOrBlank()) put("detail", detail)
        }
        appendJsonLineAsync(context, row)
    }

    fun flushBatchToServerAsync(context: Context, nowMs: Long = System.currentTimeMillis()) {
        lastBatchUploadAtMs = nowMs
        val appContext = context.applicationContext
        val snapshotEdges = coOccurrences.values.filter { nowMs - it.lastUpdatedMs <= 30 * 60_000L }
        if (snapshotEdges.isEmpty() && passTrackers.isEmpty()) return

        val coArray = JSONArray()
        for (edge in snapshotEdges.take(64)) {
            coArray.put(
                JSONObject().apply {
                    put("facet_a", edge.facetA)
                    put("facet_b", edge.facetB)
                    put("category_a", edge.categoryA)
                    put("category_b", edge.categoryB)
                    put("pass_count", edge.passCount)
                    put("session_count", 1)
                    put("min_distance_m", edge.minDistanceM)
                    put("peak_snr_a", edge.peakSnrA)
                    put("peak_snr_b", edge.peakSnrB)
                    put("first_seen_ms", sessionStartedAtMs)
                    put("last_seen_ms", edge.lastUpdatedMs)
                    if (edge.lastLat != null) put("lat", edge.lastLat)
                    if (edge.lastLon != null) put("lon", edge.lastLon)
                }
            )
        }

        val multiPassTargets = JSONArray()
        val passiveObservations = JSONArray()
        var sumLat = 0.0
        var sumLon = 0.0
        var coordCount = 0
        for (tracker in passTrackers.values.sortedByDescending { it.passCount }.take(64)) {
            sumLat += tracker.bestLat
            sumLon += tracker.bestLon
            coordCount += 1
            val itemObj = JSONObject().apply {
                put("facet", tracker.facetKey)
                put("category", tracker.category)
                put("modality", tracker.modality)
                put("is_flagged", tracker.isFlagged)
                put("pass_count", tracker.passCount)
                put("peak_signal_db", tracker.peakSignalDb)
                put("initial_radius_m", tracker.initialRadiusM.roundToInt())
                put("latest_radius_m", tracker.latestRadiusM.roundToInt())
                put("best_lat", tracker.bestLat)
                put("best_lon", tracker.bestLon)
                if (!tracker.ssidHint.isNullOrBlank()) put("ssid_hint", tracker.ssidHint)
            }
            if (tracker.passCount >= 2 || tracker.isFlagged) {
                multiPassTargets.put(itemObj)
            }
            if (!tracker.isFlagged) {
                passiveObservations.put(itemObj)
            }
        }

        val summaryObj = JSONObject().apply {
            put("distinct_facets", passTrackers.size)
            put("multi_pass_targets", multiPassTargets)
            put("passive_observations", passiveObservations)
        }
        val summaryStr = summaryObj.toString()

        val batchPayload = JSONObject().apply {
            put("session_id", sessionId)
            put("build_flavor", if (BuildConfig.DEBUG) "debug" else "release")
            put("started_at_ms", sessionStartedAtMs)
            put("ended_at_ms", nowMs)
            put("pass_count", maxPassesInBatch.coerceAtLeast(1))
            put("sample_count", totalSamplesInBatch.coerceAtLeast(1))
            if (coordCount > 0) {
                put("center_lat", sumLat / coordCount)
                put("center_lon", sumLon / coordCount)
            }
            put("raw_summary_json", summaryStr)
            put("summary_json", summaryStr)
            put("co_occurrences", coArray)
        }

        KokohoHubClient.uploadFieldTelemetryBatch(appContext, batchPayload.toString())
    }

    fun getTelemetryDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun appendJsonLineAsync(context: Context, obj: JSONObject) {
        val appContext = context.applicationContext
        val line = obj.toString() + "\n"
        ioScope.launch {
            runCatching {
                val dir = getTelemetryDir(appContext)
                val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val file = File(dir, "field_telemetry_$dayStamp.jsonl")
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                }
                enforceStorageCap(dir)
            }
        }
    }

    private fun enforceStorageCap(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_STORAGE_BYTES) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (f in oldestFirst) {
            if (total <= MAX_STORAGE_BYTES) break
            val len = f.length()
            if (f.delete()) {
                total -= len
            }
        }
    }

    private fun angularDistanceDeg(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
