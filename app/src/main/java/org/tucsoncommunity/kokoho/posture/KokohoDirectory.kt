package org.tucsoncommunity.kokoho.posture

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Unified civic facility & personnel directory repository for Kokoho `v1.3.12`.
 *
 *  - Loads `reference/public_locations.tsv.gz` (publicly listed federal offices,
 *    police stations/substations, sheriff district offices, and fire stations) plus any user-created
 *    local witness person records.
 *  - When enrolled on a Hub seat, hydrates `p2_sector_dir` and `p3_ext_dir` directly in RAM from
 *    hardware-sealed `PriorityVault` `.seal` blobs (zero `directory.db` ever written to `/data`),
 *    clearing decrypted RAM caches automatically whenever `Intent.ACTION_SCREEN_OFF` fires.
 */
object KokohoDirectory {

    private const val PUBLIC_LOCATIONS_ASSET = "reference/public_locations.tsv.gz"
    private const val LEGACY_EXTRACTED_DB_NAME = "directory.db"
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

        private val searchHaystackLower: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
            buildString(192) {
                append(fullName.lowercase(Locale.US)).append('\n')
                append(title.lowercase(Locale.US)).append('\n')
                append(resolvedAgencyDisplay.lowercase(Locale.US)).append('\n')
                append(agency.friendlyLabel.lowercase(Locale.US)).append('\n')
                append(agency.shortName.lowercase(Locale.US)).append('\n')
                append(agencyCode.lowercase(Locale.US)).append('\n')
                append(suite.lowercase(Locale.US)).append('\n')
                append(streetAddress.lowercase(Locale.US)).append('\n')
                append(stateCode.lowercase(Locale.US)).append('\n')
                append(email.lowercase(Locale.US)).append('\n')
                append(secondEmail.lowercase(Locale.US)).append('\n')
                append(primaryPhone.lowercase(Locale.US)).append('\n')
                append(mobilePhone.lowercase(Locale.US)).append('\n')
                append(badgeNumber.lowercase(Locale.US))
            }
        }

        fun matchesToken(rawToken: String): Boolean {
            val t = rawToken.trim().lowercase(Locale.US)
            if (t.isEmpty()) return true
            return searchHaystackLower.contains(t)
        }
    }

    @Volatile
    private var initialized = false

    @Volatile
    private var hydratedVaultDir: PriorityVault.HydratedDirectoryData? = null

    @Volatile
    private var hydratedRevision: Long = -1L

    @Volatile
    private var legacyFilesCleaned = false

    @Volatile
    private var hydratedForIntelligence = false

    @Volatile
    private var hydratedForUnlocked = false

    @Volatile
    private var hydratedWhileSuppressingP3 = false

    private var locationsMap: Map<String, FacilityLocation> = emptyMap()
    private var locationsList: List<FacilityLocation> = emptyList()
    private val userPersons = ConcurrentHashMap<String, PersonRecord>()

    val isDirectoryAvailable: Boolean
        get() = !PriorityVault.isSuppressingP3Personnel() &&
            !PriorityVault.isScreenOffOrLockedNoContext() &&
            hydratedVaultDir?.orderedPersons?.isNotEmpty() == true

    fun isPersonnelDirectoryAvailable(context: Context): Boolean {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) {
            return false
        }
        ensureLoaded(context)
        return isDirectoryAvailable || (!PriorityVault.isScreenOffOrLocked(context) && userPersons.isNotEmpty())
    }

    /**
     * Fast capability check indicating whether this install is enrolled on a Hub seat or holds
     * hardware-sealed `PriorityVault` tiers.
     */
    fun hasExtendedDirectoryCapability(context: Context): Boolean =
        DeviceTrustManager.isEnrolled(context) || PriorityVault.hasAnySyncedData(context)

    @Synchronized
    fun invalidateForReload() {
        initialized = false
        hydratedRevision = -1L
        hydratedForIntelligence = false
        hydratedForUnlocked = false
        hydratedWhileSuppressingP3 = false
        visibleCacheKey = ""
    }

    @Synchronized
    fun onVaultTierUpdated(context: Context) {
        initialized = false
        hydratedVaultDir = null
        hydratedRevision = -1L
        hydratedForIntelligence = false
        hydratedForUnlocked = false
        hydratedWhileSuppressingP3 = false
        visibleCacheKey = ""
        ensureLoaded(context)
    }

    @Synchronized
    fun clearInMemoryVaultCache() {
        hydratedVaultDir = null
        hydratedRevision = -1L
        hydratedForIntelligence = false
        hydratedForUnlocked = false
        hydratedWhileSuppressingP3 = false
        initialized = false
        locationsMap = emptyMap()
        locationsList = emptyList()
        userPersons.clear()
        visibleCacheKey = ""
        visibleCacheList = emptyList()
    }

    @Synchronized
    fun reloadAfterKeyUnlock(context: Context) {
        onVaultTierUpdated(context)
    }

    @Synchronized
    fun purgeDecryptedCache(context: Context) {
        clearInMemoryVaultCache()
        deleteLegacyDbArtifacts(context)
    }

    private fun deleteLegacyDbArtifacts(context: Context) {
        val app = context.applicationContext ?: context
        for (dir in listOfNotNull(app.noBackupFilesDir, app.filesDir)) {
            runCatching { File(dir, LEGACY_EXTRACTED_DB_NAME).delete() }
            runCatching { File(dir, "$LEGACY_EXTRACTED_DB_NAME.vc").delete() }
            runCatching { File(dir, "$LEGACY_EXTRACTED_DB_NAME.tmp").delete() }
        }
    }

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

    private val loadLock = Any()

    fun ensureLoaded(context: Context) {
        val app = context.applicationContext ?: context
        // Never block the Android Main/UI thread waiting for PriorityVault directory decryption/hydration (prevents ANR).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (!initialized || locationsList.isEmpty()) {
                preloadAsync(app)
            }
            return
        }
        synchronized(loadLock) {
            ensureLoadedLocked(app)
        }
    }

    private fun ensureLoadedLocked(app: Context) {
        PriorityVault.ensureScreenOffReceiverRegistered(app)
        if (!legacyFilesCleaned) {
            legacyFilesCleaned = true
            deleteLegacyDbArtifacts(app)
        }

        val allowIntel = DeviceTrustManager.hasIntelligenceAccess(app)
        val screenLocked = PriorityVault.isScreenOffOrLocked(app)
        val suppressingP3 = PriorityVault.isSuppressingP3Personnel()
        val currentRev = PriorityVault.vaultDataRevision
        if (initialized &&
            locationsList.isNotEmpty() &&
            hydratedForIntelligence == allowIntel &&
            hydratedForUnlocked == !screenLocked &&
            hydratedWhileSuppressingP3 == suppressingP3 &&
            !suppressingP3
        ) {
            if (hydratedVaultDir != null && hydratedRevision == currentRev) {
                return
            }
            if (hydratedVaultDir == null && !PriorityVault.hasSyncedDirectoryFiles(app)) {
                return
            }
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
                            id = canonicalLocationId(p[0].trim()),
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

        // 2. Hydrate hardware-sealed PriorityVault directory tiers in RAM on demand (zero disk SQLite files)
        val vaultHydrated = runCatching { PriorityVault.getHydratedDirectory(app) }.getOrNull()
        hydratedVaultDir = vaultHydrated
        hydratedRevision = currentRev
        if (vaultHydrated != null) {
            for (loc in vaultHydrated.locations) {
                mergedLocations[loc.id] = loc
            }
        }

        // 3. Load user-submitted local person records (strictly gated to Intelligence / Owner while unlocked)
        if (allowIntel && !screenLocked) {
            loadUserPersons(app)
        } else {
            userPersons.clear()
        }
        hydratedForIntelligence = allowIntel
        hydratedForUnlocked = !screenLocked
        hydratedWhileSuppressingP3 = suppressingP3

        locationsMap = mergedLocations
        locationsList = mergedLocations.values.toList()
        visibleCacheKey = ""
        initialized = true
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
        val key = "${locationsList.size}|$hydratedRevision|$dhsMaster|${mapVisibleAgencies.joinToString(",") { it.id }}"
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
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return 0
        ensureLoaded(context)
        val canonLoc = canonicalLocationId(locationId)
        val vaultRows = hydratedVaultDir?.personsByLocation?.get(canonLoc).orEmpty()
        val vaultIds = if (vaultRows.isEmpty()) emptySet() else vaultRows.mapTo(HashSet(vaultRows.size)) { it.personId }
        val localExtra = userPersons.values.count {
            canonicalLocationId(it.locationId) == canonLoc && it.personId !in vaultIds
        }
        return vaultRows.size + localExtra
    }

    fun stationedPersonnel(
        context: Context,
        locationId: String,
        pills: List<String> = emptyList(),
        liveQuery: String = ""
    ): List<PersonRecord> {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
        ensureLoaded(context)
        val canonLoc = canonicalLocationId(locationId)
        val rows = ArrayList<PersonRecord>()
        val seenIds = HashSet<String>()
        for (vp in hydratedVaultDir?.personsByLocation?.get(canonLoc).orEmpty()) {
            if (seenIds.add(vp.personId)) {
                rows += vp
            }
        }
        for (up in userPersons.values) {
            if (canonicalLocationId(up.locationId) == canonLoc && seenIds.add(up.personId)) {
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
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
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
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
        ensureLoaded(context)
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val results = ArrayList<PersonRecord>()
        val seenIds = HashSet<String>()

        for (up in userPersons.values) {
            if (tokens.all { up.matchesToken(it) } && seenIds.add(up.personId)) {
                results += up
            }
        }

        val scanCap = maxOf(limit * 4, 120)
        for (vp in hydratedVaultDir?.orderedPersons.orEmpty()) {
            if (vp.personId in seenIds) continue
            if (tokens.all { vp.matchesToken(it) }) {
                seenIds.add(vp.personId)
                results += vp
                if (agencyHint == null && results.size >= limit) break
                if (results.size >= scanCap) break
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

    fun browsePersons(context: Context, limit: Int = 20): List<PersonRecord> {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
        ensureLoaded(context)
        val cap = limit.coerceAtLeast(1)
        val results = ArrayList<PersonRecord>(cap)
        val seenIds = HashSet<String>()
        for (up in userPersons.values.sortedBy { it.fullName.lowercase(Locale.US) }) {
            if (seenIds.add(up.personId)) {
                results += up
                if (results.size >= cap) return results
            }
        }
        for (vp in hydratedVaultDir?.orderedPersons.orEmpty()) {
            if (seenIds.add(vp.personId)) {
                results += vp
                if (results.size >= cap) break
            }
        }
        return results
    }

    fun searchPersonsWithTokens(
        context: Context,
        tokens: List<String>,
        limit: Int = 40
    ): List<PersonRecord> {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
        ensureLoaded(context)
        val cleanTokens = tokens
            .flatMap { it.trim().lowercase(Locale.US).split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
        if (cleanTokens.isEmpty()) return browsePersons(context, limit)

        val matchedAgency = if (cleanTokens.size == 1) {
            val tok = cleanTokens.first()
            KokohoAgency.entries.firstOrNull { ag ->
                ag.friendlyLabel.equals(tok, ignoreCase = true) || ag.id.equals(tok, ignoreCase = true)
            }
        } else {
            null
        }

        val results = ArrayList<PersonRecord>(limit)
        val seenIds = HashSet<String>()

        fun personMatchesAll(p: PersonRecord): Boolean {
            if (matchedAgency != null && p.agency == matchedAgency) return true
            return cleanTokens.all { p.matchesToken(it) }
        }

        for (up in userPersons.values) {
            if (personMatchesAll(up) && seenIds.add(up.personId)) {
                results += up
                if (results.size >= limit) return results
            }
        }
        for (vp in hydratedVaultDir?.orderedPersons.orEmpty()) {
            if (vp.personId !in seenIds && personMatchesAll(vp)) {
                seenIds.add(vp.personId)
                results += vp
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun suggestPersonPills(
        context: Context,
        prefix: String,
        existingSetLower: Set<String>,
        limit: Int = 5
    ): List<String> {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
        val q = prefix.trim().lowercase(Locale.US)
        if (q.length < 2) return emptyList()
        val matches = searchPersons(context, q, limit = 24)
        val out = LinkedHashSet<String>()
        for (p in matches) {
            for (cand in listOf(p.suite, p.title, p.fullName)) {
                val clean = cand.trim()
                if (clean.isEmpty()) continue
                val low = clean.lowercase(Locale.US)
                if (low !in existingSetLower && low.contains(q)) {
                    out += clean
                    if (out.size >= limit) return out.toList()
                }
            }
        }
        return out.toList()
    }

    fun personById(context: Context, personId: String): PersonRecord? {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return null
        ensureLoaded(context)
        val clean = canonicalPersonId(personId)
        userPersons[clean]?.let { return it }
        return hydratedVaultDir?.personsById?.get(clean)
    }

    fun managedBy(context: Context, personId: String): PersonRecord? {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return null
        ensureLoaded(context)
        val clean = canonicalPersonId(personId)
        val userRec = userPersons[clean]
        if (userRec != null && userRec.managedByNameOrId.isNotBlank()) {
            personById(context, userRec.managedByNameOrId)?.let { return it }
            searchPersons(context, userRec.managedByNameOrId, limit = 1).firstOrNull()?.let { return it }
        }
        hydratedVaultDir?.managerByReportId?.get(clean)?.let { return it }
        val vaultRec = hydratedVaultDir?.personsById?.get(clean)
        if (vaultRec != null && vaultRec.managedByNameOrId.isNotBlank()) {
            personById(context, vaultRec.managedByNameOrId)?.let { return it }
            searchPersons(context, vaultRec.managedByNameOrId, limit = 1).firstOrNull()?.let { return it }
        }
        return null
    }

    fun manages(context: Context, personId: String): List<PersonRecord> {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return emptyList()
        ensureLoaded(context)
        val clean = canonicalPersonId(personId)
        val out = ArrayList<PersonRecord>()
        val seenIds = HashSet<String>()
        for (rep in hydratedVaultDir?.reportsByManagerId?.get(clean).orEmpty()) {
            if (seenIds.add(rep.personId)) {
                out += rep
            }
        }
        for (up in userPersons.values) {
            if (up.managedByNameOrId == clean && seenIds.add(up.personId)) {
                out += up
            }
        }
        return out
    }

    fun saveUserPerson(context: Context, person: PersonRecord) {
        if (!DeviceTrustManager.hasIntelligenceAccess(context) || PriorityVault.isScreenOffOrLocked(context)) return
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
}
