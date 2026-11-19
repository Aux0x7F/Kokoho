package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Durable all-time SQLite entity & constituent-device ledger (`Layer 3`).
 *
 * Ensures that local-only detections (when Share is OFF, the default), synced historical matches,
 * and dropped-point reports survive across `30m`, `3h`, `24h`, `1wk`, `1mo`, and `Heat` rolling
 * view windows as well as app restarts: never evaporating after 48 hours and never resetting at midnight.
 *
 * Performance invariant:
 *  - [memoryMirror] serves all reads in O(1) RAM without touching disk after initial one-time open.
 *  - Unchanged entities are skipped without SQLite writes.
 *  - All SQLite `INSERT`/`DELETE` `fdatasync` operations run asynchronously on [ioExecutor], never blocking the UI thread.
 */
object KokohoEntityLedger {

    private const val DB_FILE_NAME = "ledger.db"
    const val MAX_LEDGER_ROWS = 50_000

    @Volatile
    private var dbHelper: android.database.sqlite.SQLiteOpenHelper? = null
    @Volatile
    private var db: SQLiteDatabase? = null
    @Volatile
    private var dbOwnerDir: String? = null
    @Volatile
    var lastDebugError: String? = null
    private val memoryMirror = ConcurrentHashMap<String, EvaluatedThreat>()
    private val persistedStampByUuid = ConcurrentHashMap<String, String>()
    private var lastPruneAtMs = 0L

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kokoho-ledger-io").apply { isDaemon = true }
    }

    @Synchronized
    private fun ensureDb(context: Context): SQLiteDatabase? {
        val app = context.applicationContext ?: context
        val ownerDir = app.filesDir.absolutePath
        db?.let { existing ->
            if (existing.isOpen && ownerDir == dbOwnerDir) {
                return existing
            }
            runCatching { existing.close() }
            runCatching { dbHelper?.close() }
            db = null
            dbHelper = null
            dbOwnerDir = null
            memoryMirror.clear()
            persistedStampByUuid.clear()
        }
        return runCatching {
            app.getDatabasePath(DB_FILE_NAME).parentFile?.mkdirs()
            val helper = object : android.database.sqlite.SQLiteOpenHelper(app, DB_FILE_NAME, null, 1) {
                override fun onCreate(sqLiteDatabase: SQLiteDatabase) {
                    createSchema(sqLiteDatabase)
                }

                override fun onUpgrade(sqLiteDatabase: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    createSchema(sqLiteDatabase)
                }
            }
            val opened = helper.writableDatabase
            createSchema(opened)
            dbHelper = helper
            db = opened
            dbOwnerDir = ownerDir
            hydrateMemoryMirror(opened)
            opened
        }.onFailure { lastDebugError = "ensureDb: $it" }.getOrNull()
    }

    private fun createSchema(opened: SQLiteDatabase) {
        opened.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_entities (
                uuid                TEXT PRIMARY KEY,
                agency_code         TEXT NOT NULL,
                label               TEXT NOT NULL,
                posture             TEXT NOT NULL,
                class_conf          INTEGER NOT NULL,
                spatial_conf        INTEGER NOT NULL,
                est_dist_m          INTEGER NOT NULL,
                base_radius_m       INTEGER NOT NULL,
                summary             TEXT NOT NULL,
                first_seen_ms       INTEGER NOT NULL,
                updated_at_ms       INTEGER NOT NULL,
                lat                 REAL,
                lon                 REAL,
                is_approx           INTEGER NOT NULL DEFAULT 0,
                is_pinpointed       INTEGER NOT NULL DEFAULT 0,
                is_aircraft         INTEGER NOT NULL DEFAULT 0,
                is_helicopter       INTEGER NOT NULL DEFAULT 0,
                is_circling         INTEGER NOT NULL DEFAULT 0,
                is_drone            INTEGER NOT NULL DEFAULT 0,
                alt_ft              INTEGER,
                hardware_id         TEXT,
                sighting_count      INTEGER NOT NULL DEFAULT 1,
                capabilities_json   TEXT NOT NULL DEFAULT '[]',
                evidence_json       TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        opened.execSQL("CREATE INDEX IF NOT EXISTS idx_local_entities_updated ON local_entities(updated_at_ms DESC)")
    }

    private fun stampOf(t: EvaluatedThreat): String =
        "${t.updatedAtMs}|${t.agency.id}|${t.label}|${t.sightingCount}|${t.uncertaintyRadiusM}|${t.lat}|${t.lon}|${t.evidenceSources.size}|${t.capabilities.size}"

    private fun hydrateMemoryMirror(database: SQLiteDatabase) {
        runCatching {
            val stalePoisonUuids = ArrayList<String>()
            database.rawQuery(
                "SELECT uuid, agency_code, label, posture, class_conf, spatial_conf, est_dist_m, base_radius_m, summary, updated_at_ms, lat, lon, is_approx, is_pinpointed, is_aircraft, is_helicopter, is_circling, is_drone, alt_ft, hardware_id, sighting_count, capabilities_json, evidence_json " +
                    "FROM local_entities",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val uuid = c.getString(0) ?: continue
                    val summaryRaw = c.getString(8) ?: ""
                    val hwRaw = if (c.isNull(19)) "" else (c.getString(19) ?: "")
                    // Evict legacy false-positive rows (KARR dealership car alarms QT/DR, consumer IDW wearables, transit Veh ###-5g, removed consumer OUIs)
                    val hwClean = hwRaw.removePrefix("hw:mac:").uppercase()
                    if (uuid.startsWith("3f7147ac", ignoreCase = true) ||
                        hwClean.startsWith("40:84:32:") ||
                        hwClean.startsWith("24:79:2A:") ||
                        hwClean.startsWith("AC:82:47:") ||
                        hwClean.startsWith("C8:B3:73:") ||
                        hwClean.startsWith("00:11:AB:") ||
                        hwClean.startsWith("D8:12:65:") ||
                        hwClean.startsWith("60:E3:27:") ||
                        Regex("(?i)(?:\\b(?:QT|DR)[_\\s]+[0-9A-F]{8}\\b|\\bIDW\\d+\\b|\\bVeh\\s+\\d{2,4}(?:[-_ ]?(?:2\\.?4|5)g?)?\\b)").containsMatchIn("$summaryRaw $hwRaw")
                    ) {
                        stalePoisonUuids += uuid
                        continue
                    }
                    val rawAgency = KokohoAgency.fromCode(c.getString(1)) ?: KokohoAgency.DHS_INTEROP
                    val agency = when {
                        (hwClean.startsWith("00:30:44:74:") || hwClean.startsWith("06:30:44:74:")) -> KokohoAgency.SHERIFF
                        hwClean.startsWith("00:30:44:22:8") -> KokohoAgency.FIRE
                        else -> rawAgency
                    }
                    val posture = runCatching { PostureColor.valueOf(c.getString(3)) }.getOrDefault(PostureColor.YELLOW)
                    val classConf = c.getInt(4)
                    val spatConf = c.getInt(5)
                    val caps = parseStringList(c.getString(21))
                    val badges = parseBadges(c.getString(22))
                    val threat = EvaluatedThreat(
                        uuid = uuid,
                        trackUuid = uuid,
                        agency = agency,
                        label = c.getString(2) ?: agency.friendlyLabel,
                        posture = posture,
                        overallConfidence = ((classConf + spatConf) / 2.0).roundToInt().coerceIn(1, 99),
                        classificationConfidence = classConf,
                        spatialConfidence = spatConf,
                        estimatedDistanceM = c.getInt(6),
                        uncertaintyRadiusM = c.getInt(7),
                        bearingDeg = null,
                        closingVelocityMps = null,
                        recurrenceSummary = c.getString(8) ?: "",
                        evidenceSources = badges,
                        updatedAtMs = c.getLong(9),
                        lat = if (c.isNull(10)) null else c.getDouble(10),
                        lon = if (c.isNull(11)) null else c.getDouble(11),
                        isApproximatedPosition = c.getInt(12) != 0,
                        isPinpointed = c.getInt(13) != 0,
                        isAircraft = c.getInt(14) != 0,
                        isHelicopter = c.getInt(15) != 0,
                        aircraftHex = if (c.getInt(14) != 0) badges.firstOrNull()?.facetKey?.substringAfterLast(':') else null,
                        isCircling = c.getInt(16) != 0,
                        isDrone = c.getInt(17) != 0,
                        altitudeFtAgl = if (c.isNull(18)) null else c.getInt(18),
                        hardwareIdentifier = if (c.isNull(19)) null else c.getString(19),
                        sightingCount = c.getInt(20).coerceAtLeast(1),
                        capabilities = caps
                    )
                    memoryMirror[uuid] = threat
                    persistedStampByUuid[uuid] = stampOf(threat)
                }
            }
            for (badUuid in stalePoisonUuids) {
                runCatching { database.execSQL("DELETE FROM local_entities WHERE uuid = ?", arrayOf(badUuid)) }
            }
        }.onFailure { lastDebugError = "hydrate: $it" }
    }

    fun upsert(context: Context?, threat: EvaluatedThreat, nowMs: Long = System.currentTimeMillis()) {
        if (threat.isAircraft) return
        if (threat.lat == null || threat.lon == null) return
        val appCtx = context?.applicationContext ?: context
        if (appCtx != null && db == null) {
            ensureDb(appCtx)
        }
        val prev = memoryMirror[threat.uuid]
        val merged = if (prev != null && prev.updatedAtMs > threat.updatedAtMs) {
            prev
        } else {
            threat
        }
        memoryMirror[threat.uuid] = merged
        if (appCtx == null) return

        val stamp = stampOf(merged)
        if (persistedStampByUuid[merged.uuid] == stamp) {
            return
        }
        persistedStampByUuid[merged.uuid] = stamp

        ioExecutor.execute {
            val database = ensureDb(appCtx) ?: return@execute
            runCatching {
                val capsArr = JSONArray().apply { merged.capabilities.forEach { put(it) } }.toString()
                val evArr = JSONArray().apply {
                    merged.evidenceSources.forEach { b ->
                        put(
                            JSONObject()
                                .put("kind", b.kind)
                                .put("facetKey", b.facetKey)
                                .put("detail", b.detail)
                                .put("weight", b.weight)
                                .put("lastSeenMs", b.lastSeenMs)
                        )
                    }
                }.toString()
                val cv = android.content.ContentValues().apply {
                    put("uuid", merged.uuid)
                    put("agency_code", merged.agency.id)
                    put("label", merged.label)
                    put("posture", merged.posture.name)
                    put("class_conf", merged.classificationConfidence)
                    put("spatial_conf", merged.spatialConfidence)
                    put("est_dist_m", merged.estimatedDistanceM)
                    put("base_radius_m", merged.uncertaintyRadiusM)
                    put("summary", merged.recurrenceSummary)
                    put("first_seen_ms", merged.updatedAtMs)
                    put("updated_at_ms", merged.updatedAtMs)
                    if (merged.lat != null) put("lat", merged.lat) else putNull("lat")
                    if (merged.lon != null) put("lon", merged.lon) else putNull("lon")
                    put("is_approx", if (merged.isApproximatedPosition) 1 else 0)
                    put("is_pinpointed", if (merged.isPinpointed) 1 else 0)
                    put("is_aircraft", if (merged.isAircraft) 1 else 0)
                    put("is_helicopter", if (merged.isHelicopter) 1 else 0)
                    put("is_circling", if (merged.isCircling) 1 else 0)
                    put("is_drone", if (merged.isDrone) 1 else 0)
                    if (merged.altitudeFtAgl != null) put("alt_ft", merged.altitudeFtAgl) else putNull("alt_ft")
                    if (merged.hardwareIdentifier != null) put("hardware_id", merged.hardwareIdentifier) else putNull("hardware_id")
                    put("sighting_count", merged.sightingCount)
                    put("capabilities_json", capsArr)
                    put("evidence_json", evArr)
                }
                database.insertWithOnConflict("local_entities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                if (nowMs - lastPruneAtMs > 15 * 60_000L) {
                    lastPruneAtMs = nowMs
                    pruneOverflowIfNeeded(database)
                }
            }.onFailure { lastDebugError = "upsert: $it" }
        }
    }

    fun upsertBatch(context: Context?, threats: Collection<EvaluatedThreat>, nowMs: Long = System.currentTimeMillis()) {
        if (threats.isEmpty()) return
        val appCtx = context?.applicationContext ?: context
        if (appCtx != null && db == null) {
            ensureDb(appCtx)
        }
        val toPersist = ArrayList<EvaluatedThreat>()
        for (threat in threats) {
            if (threat.isAircraft || threat.lat == null || threat.lon == null) continue
            val prev = memoryMirror[threat.uuid]
            val merged = if (prev != null && prev.updatedAtMs > threat.updatedAtMs) prev else threat
            memoryMirror[threat.uuid] = merged
            val stamp = stampOf(merged)
            if (persistedStampByUuid[merged.uuid] != stamp) {
                persistedStampByUuid[merged.uuid] = stamp
                toPersist += merged
            }
        }
        if (appCtx == null || toPersist.isEmpty()) return
        ioExecutor.execute {
            val database = ensureDb(appCtx) ?: return@execute
            runCatching {
                database.beginTransaction()
                try {
                    for (merged in toPersist) {
                        val capsArr = JSONArray().apply { merged.capabilities.forEach { put(it) } }.toString()
                        val evArr = JSONArray().apply {
                            merged.evidenceSources.forEach { b ->
                                put(
                                    JSONObject()
                                        .put("kind", b.kind)
                                        .put("facetKey", b.facetKey)
                                        .put("detail", b.detail)
                                        .put("weight", b.weight)
                                        .put("lastSeenMs", b.lastSeenMs)
                                )
                            }
                        }.toString()
                        val cv = android.content.ContentValues().apply {
                            put("uuid", merged.uuid)
                            put("agency_code", merged.agency.id)
                            put("label", merged.label)
                            put("posture", merged.posture.name)
                            put("class_conf", merged.classificationConfidence)
                            put("spatial_conf", merged.spatialConfidence)
                            put("est_dist_m", merged.estimatedDistanceM)
                            put("base_radius_m", merged.uncertaintyRadiusM)
                            put("summary", merged.recurrenceSummary)
                            put("first_seen_ms", merged.updatedAtMs)
                            put("updated_at_ms", merged.updatedAtMs)
                            if (merged.lat != null) put("lat", merged.lat) else putNull("lat")
                            if (merged.lon != null) put("lon", merged.lon) else putNull("lon")
                            put("is_approx", if (merged.isApproximatedPosition) 1 else 0)
                            put("is_pinpointed", if (merged.isPinpointed) 1 else 0)
                            put("is_aircraft", if (merged.isAircraft) 1 else 0)
                            put("is_helicopter", if (merged.isHelicopter) 1 else 0)
                            put("is_circling", if (merged.isCircling) 1 else 0)
                            put("is_drone", if (merged.isDrone) 1 else 0)
                            if (merged.altitudeFtAgl != null) put("alt_ft", merged.altitudeFtAgl) else putNull("alt_ft")
                            if (merged.hardwareIdentifier != null) put("hardware_id", merged.hardwareIdentifier) else putNull("hardware_id")
                            put("sighting_count", merged.sightingCount)
                            put("capabilities_json", capsArr)
                            put("evidence_json", evArr)
                        }
                        database.insertWithOnConflict("local_entities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                if (nowMs - lastPruneAtMs > 15 * 60_000L) {
                    lastPruneAtMs = nowMs
                    pruneOverflowIfNeeded(database)
                }
            }.onFailure { lastDebugError = "upsertBatch: $it" }
        }
    }

    private fun pruneOverflowIfNeeded(database: SQLiteDatabase) {
        if (memoryMirror.size <= MAX_LEDGER_ROWS) return
        database.execSQL(
            """
            DELETE FROM local_entities
            WHERE uuid NOT IN (
                SELECT uuid FROM local_entities ORDER BY updated_at_ms DESC LIMIT $MAX_LEDGER_ROWS
            )
            """.trimIndent()
        )
        val sorted = memoryMirror.values.sortedByDescending { it.updatedAtMs }
        for (old in sorted.drop(MAX_LEDGER_ROWS)) {
            memoryMirror.remove(old.uuid)
            persistedStampByUuid.remove(old.uuid)
        }
    }

    fun getWithinRollingWindow(context: Context?, nowMs: Long, windowMs: Long): List<EvaluatedThreat> {
        if (context != null && db == null) ensureDb(context)
        val cutoff = nowMs - max(0L, windowMs)
        return memoryMirror.values.filter { t ->
            !t.isAircraft && t.updatedAtMs in cutoff..(nowMs + 60_000L)
        }
    }

    fun findByUuid(context: Context?, uuid: String): EvaluatedThreat? {
        if (context != null && db == null) ensureDb(context)
        val clean = KokohoSignatures.normalizeEntityId(uuid)
        return memoryMirror[clean]
    }

    fun remove(context: Context?, uuid: String) {
        val clean = KokohoSignatures.normalizeEntityId(uuid)
        memoryMirror.remove(clean)
        persistedStampByUuid.remove(clean)
        val appCtx = context?.applicationContext ?: context
        if (appCtx != null) {
            ioExecutor.execute {
                runCatching { ensureDb(appCtx)?.execSQL("DELETE FROM local_entities WHERE uuid = ?", arrayOf(clean)) }
            }
        }
    }

    fun clearAll(context: Context? = null) {
        memoryMirror.clear()
        persistedStampByUuid.clear()
        val appCtx = context?.applicationContext ?: context
        ioExecutor.execute {
            val targetDb = if (appCtx != null) ensureDb(appCtx) else db
            runCatching { targetDb?.execSQL("DELETE FROM local_entities") }
        }
    }

    /** Drains queued async disk writes, wipes RAM mirror, and re-hydrates strictly from SQLite `local_entities`. */
    fun simulateProcessRestartForTest(context: Context? = null) {
        runCatching { ioExecutor.submit {}.get(5, TimeUnit.SECONDS) }
        synchronized(this) {
            memoryMirror.clear()
            persistedStampByUuid.clear()
            val opened = if (context != null) ensureDb(context) else db
            if (opened != null && opened.isOpen) {
                hydrateMemoryMirror(opened)
            }
        }
    }

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun parseBadges(raw: String?): List<EvidenceBadge> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                EvidenceBadge(
                    kind = o.optString("kind", "LOCAL"),
                    facetKey = o.optString("facetKey", ""),
                    detail = o.optString("detail", ""),
                    weight = o.optDouble("weight", 0.75),
                    lastSeenMs = o.optLong("lastSeenMs", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }
}
