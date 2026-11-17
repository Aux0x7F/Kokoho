package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.tucsoncommunity.kokoho.BuildConfig
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Unified civic facility & personnel directory repository for Kokoho `v1.3.x`.
 *
 *  - Loads `reference/public_locations.tsv.gz` (publicly listed federal offices,
 *    police stations/substations, sheriff district offices, and fire stations) plus any user-created
 *    local witness person records.
 *  - When an optional digested `reference/directory_ext.sqlite3.gz` asset is packaged in the build,
 *    seamlessly hydrates additional facility locations, stationed personnel tables
 *    (`Full Name | Title | Agency | Suite`), unassociated person search/autocomplete, and
 *    organizational `Managed by` / `Manages` links.
 */
object KokohoDirectory {

    private const val PUBLIC_LOCATIONS_ASSET = "reference/public_locations.tsv.gz"
    private const val OPTIONAL_DIRECTORY_DB_ASSET = "reference/directory_ext.sqlite3.gz"
    private const val EXTRACTED_DB_NAME = "directory.db"
    private const val USER_PERSONS_PREF = "kokoho_user_persons_json"

    data class FacilityLocation(
        val id: String,
        val title: String,
        val agencyCode: String,
        val lat: Double,
        val lon: Double,
        val address: String,
        val description: String,
        /** "FEDERAL", "POLICE", or "FIRE" */
        val locationGroup: String = "FEDERAL",
        val stateCode: String = "AZ"
    ) {
        val agency: KokohoAgency
            get() = KokohoAgency.fromCode(agencyCode) ?: KokohoAgency.DHS_INTEROP
    }

    data class PersonRecord(
        val personId: String,
        val fullName: String,
        val title: String = "",
        val agencyCode: String = "DHS_ICE",
        val agencyDisplay: String = "",
        val locationId: String = "",
        val streetAddress: String = "",
        val suite: String = "",
        val stateCode: String = "AZ",
        val email: String = "",
        val secondEmail: String = "",
        val primaryPhone: String = "",
        val mobilePhone: String = "",
        val badgeNumber: String = "",
        val managedByNameOrId: String = ""
    ) {
        val agency: KokohoAgency
            get() = KokohoAgency.fromCode(agencyCode) ?: KokohoAgency.DHS_INTEROP

        val resolvedAgencyDisplay: String
            get() = agencyDisplay.ifBlank { agency.friendlyLabel }

        fun matchesToken(rawToken: String): Boolean {
            val t = rawToken.trim().lowercase(Locale.US)
            if (t.isEmpty()) return true
            return fullName.lowercase(Locale.US).contains(t) ||
                title.lowercase(Locale.US).contains(t) ||
                resolvedAgencyDisplay.lowercase(Locale.US).contains(t) ||
                suite.lowercase(Locale.US).contains(t) ||
                email.lowercase(Locale.US).contains(t) ||
                badgeNumber.lowercase(Locale.US).contains(t)
        }
    }

    @Volatile
    private var initialized = false

    @Volatile
    private var hasPackagedDirectoryDb = false

    private var locationsMap: Map<String, FacilityLocation> = emptyMap()
    private var locationsList: List<FacilityLocation> = emptyList()
    private var sqliteDb: SQLiteDatabase? = null
    private val userPersons = ConcurrentHashMap<String, PersonRecord>()

    val isDirectoryAvailable: Boolean
        get() = hasPackagedDirectoryDb

    private fun openAssetStreamSmart(context: Context, pathWithGz: String): java.io.InputStream? {
        val raw = runCatching { context.assets.open(pathWithGz) }.getOrElse {
            runCatching { context.assets.open(pathWithGz.removeSuffix(".gz")) }.getOrNull()
        } ?: return null
        val pb = BufferedInputStream(raw, 16 * 1024)
        pb.mark(4)
        val b0 = pb.read()
        val b1 = pb.read()
        pb.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(pb) else pb
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext ?: context
        if (initialized && locationsList.isNotEmpty() &&
            (sqliteDb == null || (sqliteDb!!.isOpen && File(sqliteDb!!.path).exists()))
        ) {
            return
        }
        val mergedLocations = LinkedHashMap<String, FacilityLocation>()

        // 1. Load baseline public facility locations (transparently handles both .tsv.gz and AAPT2-decompressed .tsv)
        runCatching {
            openAssetStreamSmart(app, PUBLIC_LOCATIONS_ASSET)?.use { decoded ->
                BufferedReader(InputStreamReader(decoded, Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank() || line.startsWith("#")) continue
                        val p = line.split('\t')
                        if (p.size < 7) continue
                        val lat = p[3].toDoubleOrNull() ?: continue
                        val lon = p[4].toDoubleOrNull() ?: continue
                        val loc = FacilityLocation(
                            id = p[0].trim(),
                            title = p[1].trim(),
                            agencyCode = p[2].trim(),
                            lat = lat,
                            lon = lon,
                            address = p[5].trim(),
                            description = p[6].trim(),
                            locationGroup = p.getOrElse(7) { "FEDERAL" }.trim().ifEmpty { "FEDERAL" }
                        )
                        mergedLocations[loc.id] = loc
                    }
                }
            }
        }

        // 2. Optionally hydrate packaged SQLite directory when present in build assets
        runCatching {
            sqliteDb?.takeIf { !it.isOpen || !File(it.path).exists() }?.let {
                runCatching { it.close() }
                sqliteDb = null
                hasPackagedDirectoryDb = false
            }
            val dbFile = extractAssetDbIfPresent(app)
            if (dbFile != null && dbFile.exists()) {
                val db = sqliteDb ?: SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                sqliteDb = db
                hasPackagedDirectoryDb = true
                db.rawQuery(
                    "SELECT location_id, title, agency_code, lat, lon, address, description, location_group, state_code FROM facility_locations",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val loc = FacilityLocation(
                            id = c.getString(0),
                            title = c.getString(1),
                            agencyCode = c.getString(2),
                            lat = c.getDouble(3),
                            lon = c.getDouble(4),
                            address = c.getString(5),
                            description = c.getString(6),
                            locationGroup = c.getString(7),
                            stateCode = c.getString(8)
                        )
                        mergedLocations[loc.id] = loc
                    }
                }
            }
        }

        // 3. Load user-submitted local person records
        loadUserPersons(app)

        locationsMap = mergedLocations
        locationsList = mergedLocations.values.toList()
        initialized = true
    }

    private fun extractAssetDbIfPresent(context: Context): File? {
        val decodedStream = openAssetStreamSmart(context, OPTIONAL_DIRECTORY_DB_ASSET) ?: return null
        val outFile = context.getDatabasePath(EXTRACTED_DB_NAME)
        val dir = outFile.parentFile ?: context.filesDir
        dir.mkdirs()
        val stampFile = File(dir, "$EXTRACTED_DB_NAME.vc")
        val currentStamp = "${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}"
        if (outFile.exists() && outFile.length() > 4096L && stampFile.exists() &&
            runCatching { stampFile.readText().trim() }.getOrNull() == currentStamp
        ) {
            runCatching { decodedStream.close() }
            return outFile
        }
        val tmp = File(dir, "$EXTRACTED_DB_NAME.tmp")
        decodedStream.use { input ->
            FileOutputStream(tmp).use { out ->
                input.copyTo(out, bufferSize = 32 * 1024)
            }
        }
        if (outFile.exists()) outFile.delete()
        tmp.renameTo(outFile)
        runCatching { stampFile.writeText(currentStamp) }
        return outFile
    }

    @Volatile
    private var visibleCacheKey: String = ""
    @Volatile
    private var visibleCacheList: List<FacilityLocation> = emptyList()

    fun preloadAsync(context: Context) {
        val app = context.applicationContext ?: context
        if (initialized && locationsList.isNotEmpty()) return
        Thread({ runCatching { ensureLoaded(app) } }, "kokoho-dir-preload").apply { isDaemon = true }.start()
    }

    fun allLocations(context: Context): List<FacilityLocation> {
        ensureLoaded(context)
        return locationsList
    }

    fun visibleLocations(context: Context): List<FacilityLocation> {
        ensureLoaded(context)
        if (!KokohoSignatures.isMapLocationsVisible(context)) return emptyList()
        val mapVisibleAgencies = KokohoSignatures.getMapVisibleAgencies(context)
        val dhsMaster = KokohoSignatures.isDhsMasterMapVisible(context)
        val key = "${locationsList.size}|$dhsMaster|${mapVisibleAgencies.joinToString(",") { it.id }}"
        if (key == visibleCacheKey) return visibleCacheList
        val filtered = locationsList.filter { loc ->
            when (loc.locationGroup) {
                "FEDERAL" -> loc.agency in mapVisibleAgencies || dhsMaster
                "POLICE" -> loc.agency in mapVisibleAgencies
                "FIRE" -> KokohoAgency.FIRE in mapVisibleAgencies
                else -> loc.agency in mapVisibleAgencies
            }
        }
        visibleCacheList = filtered
        visibleCacheKey = key
        return filtered
    }

    fun canonicalLocationId(raw: String): String {
        var clean = raw.trim()
        while (clean.startsWith("loc:", ignoreCase = true)) {
            clean = clean.substring(4).trim()
        }
        return "loc:$clean"
    }

    fun canonicalPersonId(raw: String): String {
        var clean = raw.trim()
        while (clean.startsWith("person:", ignoreCase = true)) {
            clean = clean.substring(7).trim()
        }
        return clean
    }

    fun locationById(context: Context, id: String): FacilityLocation? {
        ensureLoaded(context)
        val canon = canonicalLocationId(id)
        val bare = canon.removePrefix("loc:")
        return locationsMap[canon] ?: locationsMap[id.trim()] ?: locationsMap[bare]
    }

    fun stationedPersonnelCount(context: Context, locationId: String): Int {
        ensureLoaded(context)
        val canonLoc = canonicalLocationId(locationId)
        val localCount = userPersons.values.count { canonicalLocationId(it.locationId) == canonLoc }
        val db = sqliteDb ?: return localCount
        val dbCount = runCatching {
            db.rawQuery("SELECT COUNT(*) FROM directory_persons WHERE location_id = ?", arrayOf(canonLoc)).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.getOrDefault(0)
        return dbCount + localCount
    }

    fun stationedPersonnel(
        context: Context,
        locationId: String,
        pills: List<String> = emptyList(),
        liveQuery: String = ""
    ): List<PersonRecord> {
        ensureLoaded(context)
        val canonLoc = canonicalLocationId(locationId)
        val rows = ArrayList<PersonRecord>()
        sqliteDb?.let { db ->
            runCatching {
                db.rawQuery(
                    "SELECT person_id, full_name, title, agency_code, agency_display, location_id, street_address, suite, state_code, email, second_email, primary_phone, mobile_phone, badge_number " +
                        "FROM directory_persons WHERE location_id = ? ORDER BY full_name COLLATE NOCASE ASC",
                    arrayOf(canonLoc)
                ).use { c ->
                    while (c.moveToNext()) {
                        rows += cursorToPerson(c)
                    }
                }
            }
        }
        for (up in userPersons.values) {
            if (canonicalLocationId(up.locationId) == canonLoc && rows.none { it.personId == up.personId }) {
                rows += up
            }
        }
        val activeTokens = (pills + liveQuery).map { it.trim() }.filter { it.isNotEmpty() }
        if (activeTokens.isEmpty()) return rows
        return rows.filter { person -> activeTokens.all { tok -> person.matchesToken(tok) } }
    }

    /**
     * Standardized `Tab` / `Enter` autocomplete suggestions for a location's stationed personnel table,
     * ranking matching Full Names, Suites, Agencies, and Titles not already in [existingPills].
     */
    fun suggestTableFilterTokens(
        context: Context,
        locationId: String,
        prefix: String,
        existingPills: List<String>,
        limit: Int = 8
    ): List<String> {
        val q = prefix.trim().lowercase(Locale.US)
        if (q.isEmpty()) return emptyList()
        val existingSet = existingPills.map { it.trim().lowercase(Locale.US) }.toSet()
        val pool = stationedPersonnel(context, locationId, existingPills, "")
        val candidates = LinkedHashSet<String>()
        for (p in pool) {
            for (field in listOf(p.resolvedAgencyDisplay, p.suite, p.title, p.fullName)) {
                val clean = field.trim()
                if (clean.isEmpty()) continue
                val low = clean.lowercase(Locale.US)
                if (low !in existingSet && low.startsWith(q)) {
                    candidates += clean
                }
            }
        }
        if (candidates.size < limit) {
            for (p in pool) {
                for (field in listOf(p.fullName, p.title, p.suite, p.resolvedAgencyDisplay)) {
                    val clean = field.trim()
                    if (clean.isEmpty()) continue
                    val low = clean.lowercase(Locale.US)
                    if (low !in existingSet && low.contains(q)) {
                        candidates += clean
                    }
                    if (candidates.size >= limit) break
                }
                if (candidates.size >= limit) break
            }
        }
        return candidates.take(limit)
    }

    fun searchPersons(
        context: Context,
        query: String,
        agencyHint: KokohoAgency? = null,
        limit: Int = 20
    ): List<PersonRecord> {
        ensureLoaded(context)
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val results = ArrayList<PersonRecord>()

        for (up in userPersons.values) {
            if (tokens.all { up.matchesToken(it) }) results += up
        }

        sqliteDb?.let { db ->
            val firstTok = "%${tokens.first()}%"
            runCatching {
                db.rawQuery(
                    "SELECT person_id, full_name, title, agency_code, agency_display, location_id, street_address, suite, state_code, email, second_email, primary_phone, mobile_phone, badge_number " +
                        "FROM directory_persons WHERE search_tokens LIKE ? ORDER BY CASE WHEN state_code='AZ' THEN 0 ELSE 1 END, full_name COLLATE NOCASE ASC LIMIT 120",
                    arrayOf(firstTok)
                ).use { c ->
                    while (c.moveToNext()) {
                        val p = cursorToPerson(c)
                        if (tokens.all { p.matchesToken(it) } && results.none { it.personId == p.personId }) {
                            results += p
                        }
                    }
                }
            }
        }

        if (agencyHint != null) {
            results.sortWith(
                compareBy<PersonRecord> { if (it.agency == agencyHint) 0 else 1 }
                    .thenBy { if (it.stateCode == "AZ") 0 else 1 }
                    .thenBy { it.fullName.lowercase(Locale.US) }
            )
        }
        return results.take(limit)
    }

    fun personById(context: Context, personId: String): PersonRecord? {
        ensureLoaded(context)
        val clean = canonicalPersonId(personId)
        userPersons[clean]?.let { return it }
        val db = sqliteDb ?: return null
        return runCatching {
            db.rawQuery(
                "SELECT person_id, full_name, title, agency_code, agency_display, location_id, street_address, suite, state_code, email, second_email, primary_phone, mobile_phone, badge_number " +
                    "FROM directory_persons WHERE person_id = ? LIMIT 1",
                arrayOf(clean)
            ).use { c ->
                if (c.moveToFirst()) cursorToPerson(c) else null
            }
        }.getOrNull()
    }

    fun managedBy(context: Context, personId: String): PersonRecord? {
        ensureLoaded(context)
        val clean = canonicalPersonId(personId)
        val userRec = userPersons[clean]
        if (userRec != null && userRec.managedByNameOrId.isNotBlank()) {
            personById(context, userRec.managedByNameOrId)?.let { return it }
            searchPersons(context, userRec.managedByNameOrId, limit = 1).firstOrNull()?.let { return it }
        }
        val db = sqliteDb ?: return null
        return runCatching {
            db.rawQuery(
                "SELECT p.person_id, p.full_name, p.title, p.agency_code, p.agency_display, p.location_id, p.street_address, p.suite, p.state_code, p.email, p.second_email, p.primary_phone, p.mobile_phone, p.badge_number " +
                    "FROM directory_reporting_edges e JOIN directory_persons p ON p.person_id = e.manager_person_id " +
                    "WHERE e.report_person_id = ? LIMIT 1",
                arrayOf(clean)
            ).use { c ->
                if (c.moveToFirst()) cursorToPerson(c) else null
            }
        }.getOrNull()
    }

    fun manages(context: Context, personId: String): List<PersonRecord> {
        ensureLoaded(context)
        val clean = canonicalPersonId(personId)
        val out = ArrayList<PersonRecord>()
        sqliteDb?.let { db ->
            runCatching {
                db.rawQuery(
                    "SELECT p.person_id, p.full_name, p.title, p.agency_code, p.agency_display, p.location_id, p.street_address, p.suite, p.state_code, p.email, p.second_email, p.primary_phone, p.mobile_phone, p.badge_number " +
                        "FROM directory_reporting_edges e JOIN directory_persons p ON p.person_id = e.report_person_id " +
                        "WHERE e.manager_person_id = ? ORDER BY p.full_name COLLATE NOCASE ASC",
                    arrayOf(clean)
                ).use { c ->
                    while (c.moveToNext()) out += cursorToPerson(c)
                }
            }
        }
        for (up in userPersons.values) {
            if (up.managedByNameOrId == clean && out.none { it.personId == up.personId }) {
                out += up
            }
        }
        return out
    }

    fun saveUserPerson(context: Context, person: PersonRecord) {
        ensureLoaded(context)
        userPersons[person.personId] = person
        persistUserPersons(context)
    }

    private fun loadUserPersons(context: Context) {
        val prefs = context.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(USER_PERSONS_PREF, null) ?: return
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = PersonRecord(
                    personId = o.optString("personId"),
                    fullName = o.optString("fullName"),
                    title = o.optString("title"),
                    agencyCode = o.optString("agencyCode", "DHS_ICE"),
                    agencyDisplay = o.optString("agencyDisplay"),
                    locationId = o.optString("locationId"),
                    streetAddress = o.optString("streetAddress"),
                    suite = o.optString("suite"),
                    stateCode = o.optString("stateCode", "AZ"),
                    email = o.optString("email"),
                    secondEmail = o.optString("secondEmail"),
                    primaryPhone = o.optString("primaryPhone"),
                    mobilePhone = o.optString("mobilePhone"),
                    badgeNumber = o.optString("badgeNumber"),
                    managedByNameOrId = o.optString("managedByNameOrId")
                )
                if (p.personId.isNotBlank() && p.fullName.isNotBlank()) {
                    userPersons[p.personId] = p
                }
            }
        }
    }

    private fun persistUserPersons(context: Context) {
        val arr = org.json.JSONArray()
        for (p in userPersons.values) {
            arr.put(
                org.json.JSONObject()
                    .put("personId", p.personId)
                    .put("fullName", p.fullName)
                    .put("title", p.title)
                    .put("agencyCode", p.agencyCode)
                    .put("agencyDisplay", p.agencyDisplay)
                    .put("locationId", p.locationId)
                    .put("streetAddress", p.streetAddress)
                    .put("suite", p.suite)
                    .put("stateCode", p.stateCode)
                    .put("email", p.email)
                    .put("secondEmail", p.secondEmail)
                    .put("primaryPhone", p.primaryPhone)
                    .put("mobilePhone", p.mobilePhone)
                    .put("badgeNumber", p.badgeNumber)
                    .put("managedByNameOrId", p.managedByNameOrId)
            )
        }
        context.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(USER_PERSONS_PREF, arr.toString())
            .apply()
    }

    private fun cursorToPerson(c: android.database.Cursor): PersonRecord = PersonRecord(
        personId = c.getString(0) ?: "",
        fullName = c.getString(1) ?: "",
        title = c.getString(2) ?: "",
        agencyCode = c.getString(3) ?: "DHS_INTEROP",
        agencyDisplay = c.getString(4) ?: "",
        locationId = c.getString(5) ?: "",
        streetAddress = c.getString(6) ?: "",
        suite = c.getString(7) ?: "",
        stateCode = c.getString(8) ?: "AZ",
        email = c.getString(9) ?: "",
        secondEmail = c.getString(10) ?: "",
        primaryPhone = c.getString(11) ?: "",
        mobilePhone = c.getString(12) ?: "",
        badgeNumber = c.getString(13) ?: ""
    )
}
