package org.tucsoncommunity.kokoho.posture

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import org.tucsoncommunity.kokoho.sdr.FreqNames
import org.tucsoncommunity.kokoho.sdr.RadioWatch
import org.tucsoncommunity.kokoho.util.Units
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hierarchical agency taxonomy for Kokoho, ordered by strict alerting priority:
 *  1. [DHS_ICE] ("ICE")
 *  2. [DHS_AMO] ("DHS Air": AMO & circling unlisted spy planes)
 *  3. [DHS_CBP] ("CBP")
 *  4. [DHS_HSI] ("HSI")
 *  5. Other Federal ([DHS_FPS], [DHS_INTEROP]: OFF by default)
 *  6. Local & Tactical ([SHERIFF], [POLICE], [DRONE])
 *  7. Emergency ([FIRE], [SAR])
 */
enum class KokohoAgency(
    val id: String,
    val shortName: String,
    val friendlyLabel: String,
    val fullName: String,
    val isDhs: Boolean,
    val defaultEnabled: Boolean,
    val priorityRank: Int,
    val mapColorArgb: Int,
    val mapColorHex: String
) {
    DHS_ICE("DHS_ICE", "ICE", "ICE", "ICE (ERO Tactical)", isDhs = true, defaultEnabled = true, priorityRank = 1, mapColorArgb = 0xFFEF4444.toInt(), mapColorHex = "#EF4444"),
    DHS_AMO("DHS_AMO", "DHS Air", "DHS Air", "DHS Air (AMO & Spy Planes)", isDhs = true, defaultEnabled = true, priorityRank = 2, mapColorArgb = 0xFFEC4899.toInt(), mapColorHex = "#EC4899"),
    DHS_CBP("DHS_CBP", "CBP", "CBP", "CBP (Border Patrol)", isDhs = true, defaultEnabled = true, priorityRank = 3, mapColorArgb = 0xFFF97316.toInt(), mapColorHex = "#F97316"),
    DHS_HSI("DHS_HSI", "HSI", "HSI", "HSI (Investigations)", isDhs = true, defaultEnabled = true, priorityRank = 4, mapColorArgb = 0xFFEAB308.toInt(), mapColorHex = "#EAB308"),
    DHS_FPS("DHS_FPS", "FPS", "FPS", "FPS (Federal Protective)", isDhs = true, defaultEnabled = false, priorityRank = 5, mapColorArgb = 0xFFA855F7.toInt(), mapColorHex = "#A855F7"),
    DHS_INTEROP("DHS_INTEROP", "Federal Interop", "Federal Interop", "Federal Interop", isDhs = true, defaultEnabled = true, priorityRank = 8, mapColorArgb = 0xFFFF7043.toInt(), mapColorHex = "#FF7043"),

    SHERIFF("SHERIFF", "Sheriff", "Sheriff", "Sheriff (PCSO / County)", isDhs = false, defaultEnabled = false, priorityRank = 6, mapColorArgb = 0xFF38BDF8.toInt(), mapColorHex = "#38BDF8"),
    POLICE("POLICE", "Police", "Police", "Police (TPD / Municipal)", isDhs = false, defaultEnabled = false, priorityRank = 6, mapColorArgb = 0xFF38BDF8.toInt(), mapColorHex = "#38BDF8"),
    DRONE("DRONE", "Drone", "Tactical Drone", "Pro / Enterprise / Military Drones", isDhs = false, defaultEnabled = true, priorityRank = 6, mapColorArgb = 0xFF06B6D4.toInt(), mapColorHex = "#06B6D4"),
    FIRE("FIRE", "Fire", "Fire", "Fire (TFD / Wildland)", isDhs = false, defaultEnabled = false, priorityRank = 7, mapColorArgb = 0xFF10B981.toInt(), mapColorHex = "#10B981"),
    SAR("SAR", "SAR", "SAR", "SAR (Search & Rescue)", isDhs = false, defaultEnabled = false, priorityRank = 7, mapColorArgb = 0xFF10B981.toInt(), mapColorHex = "#10B981");

    companion object {
        val DHS_AGENCIES = entries.filter { it.isDhs }
        val OPTIONAL_AGENCIES = entries.filter { !it.isDhs }

        fun fromCode(code: String?): KokohoAgency? {
            if (code.isNullOrBlank()) return null
            val norm = code.trim().uppercase()
            return when (norm) {
                "ICE", "DHS_ICE", "ERO" -> DHS_ICE
                "AMO", "DHS_AMO", "DHS AIR", "DHS_AIR", "SPY_PLANE" -> DHS_AMO
                "CBP", "DHS_CBP", "BP" -> DHS_CBP
                "HSI", "DHS_HSI" -> DHS_HSI
                "FPS", "DHS_FPS" -> DHS_FPS
                "DHS", "DHS_INTEROP", "FEDERAL", "FEDERAL INTEROP" -> DHS_INTEROP
                "SHERIFF", "PCSO", "SHERRIF" -> SHERIFF
                "POLICE", "TPD" -> POLICE
                "DRONE", "UAS", "TACTICAL_DRONE", "TACTICAL DRONE" -> DRONE
                "FIRE", "TFD" -> FIRE
                "SAR" -> SAR
                else -> entries.firstOrNull {
                    it.id == norm || it.shortName.uppercase() == norm || it.friendlyLabel.uppercase() == norm
                }
            }
        }

        fun fromId(code: String?): KokohoAgency? = fromCode(code)
    }
}

/**
 * Witness Report for a specific agent/unit (`targetKey` or `entityUuid`).
 * Submissions start as `PENDING` manual review and become `APPROVED` after server moderation.
 */
data class WitnessReportRecord(
    val targetKey: String,
    val entityUuid: String? = null,
    val agencyCode: String = "DHS_ICE",
    val department: String = "",
    val badgeNumber: String = "",
    val agentName: String = "",
    val vehicleNumber: String = "",
    val notes: String = "",
    val photoDataUrl: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val status: String = "APPROVED",
    /** "LE", "FALSE_POSITIVE", or "BUG" */
    val reportKind: String = "LE",
    /** "AGENT", "VEHICLE", "ACCESSORY", "DEVICE", or "BUG" */
    val subjectKind: String = "AGENT",
    val personId: String? = null,
    val titleOrRole: String = "",
    val email: String = "",
    val phone: String = "",
    val suiteOrAddress: String = "",
    val managedBy: String = "",
    val plateNumber: String = "",
    val plateState: String = "AZ",
    val partialPlate: Boolean = false,
    val vehicleColor: String = "",
    val vehicleBodyType: String = "",
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val confidencePct: Int = 85,
    val vehicleDescription: String = "",
    val accessoryKind: String = "",
    val photoDataUrls: List<String>? = null,
    val lat: Double? = null,
    val lon: Double? = null
) {
    val isApproved: Boolean
        get() = status.isBlank() || status.equals("APPROVED", ignoreCase = true)

    fun allPhotos(): List<String> {
        val list = ArrayList<String>()
        photoDataUrls?.filter { it.isNotBlank() }?.let { list.addAll(it) }
        if (!photoDataUrl.isNullOrBlank() && photoDataUrl !in list) {
            list.add(0, photoDataUrl)
        }
        return list
    }
}

object KokohoSignatures {

    private const val PREFS = "kokoho_agency_prefs"
    private const val KEY_ENABLED_AGENCIES = "enabled_agencies_v2"
    private const val KEY_MAP_VISIBLE_AGENCIES = "map_visible_agencies_v1"
    private const val KEY_MAP_LOCATIONS_VISIBLE = "map_locations_visible_v1"
    private const val KEY_LOCATIONS_ALARM_ENABLED = "locations_alarm_enabled_v1"
    private const val KEY_IMPERIAL_UNITS = "imperial_units"
    private const val KEY_AUXNET_ENABLED = "auxnet_enabled"
    private const val KEY_CONTRIBUTE_OBSERVATIONS = "contribute_observations"
    private const val KEY_SHARING_ACTIVE_PILL = "sharing_active_pill"
    private const val KEY_PUSH_NOTIFICATIONS = "push_notifications"
    private const val KEY_SIGNAL_DECAY_MINUTES = "signal_decay_minutes"
    private const val KEY_MAP_TIME_WINDOW = "map_time_window_v2"
    private const val KEY_MAP_HEAT_OVERLAY_ENABLED = "map_heat_overlay_enabled_v1"
    private const val KEY_MAP_CONFIRMATION_FILTER = "map_confirmation_filter_v1"
    private const val KEY_ADSB_ENABLED = "adsb_enabled"
    private const val KEY_DRONE_ENABLED = "drone_detection_enabled"
    private const val KEY_ENABLED_AIRCRAFT_AGENCIES = "enabled_aircraft_agencies"
    private const val KEY_WHITELISTED_ENTITIES = "whitelisted_entities"
    private const val KEY_WATCHED_ENTITIES = "watched_entities"
    private const val KEY_CUSTOM_ENTITY_PREFIX = "custom_entity_label_"
    private const val KEY_WITNESS_REPORTS_JSON = "witness_reports_json"
    private const val ASSET_FILE = "kokoho_signatures.json"

    /** Monitor and notification active alert window: 3 hours. */
    const val MONITOR_ACTIVE_WINDOW_MS = 3 * 3_600_000L

    /** Maximum detection age at which Monitor/Notification posture can reach RED (30 minutes); 30m..3h is capped at ORANGE. */
    const val MONITOR_RED_MAX_AGE_MS = 30 * 60_000L

    fun capMonitorPostureByAge(posture: PostureColor, ageMs: Long): PostureColor {
        if (ageMs > MONITOR_ACTIVE_WINDOW_MS) return PostureColor.GREEN
        if (ageMs > MONITOR_RED_MAX_AGE_MS && posture == PostureColor.RED) return PostureColor.ORANGE
        return posture
    }

    /** Map view solid opacity window (default 30 min) and linear fade window (+2 hours = 120 min). */
    const val DEFAULT_DECAY_MINUTES = 30
    const val MAP_FADE_SPAN_MS = 120 * 60_000L
    val DECAY_MINUTE_CHOICES = listOf(15, 30, 60, 120)

    data class RfChannelSig(
        val hz: Long = 0L,
        val nac: String? = null,
        val mode: String = "P25",
        val alpha: String = "",
        val description: String = "",
        val agency: String = "DHS_CBP",
        val role: String = "REPEATER"
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agency) ?: KokohoAgency.DHS_CBP
        val friendlyLabel: String get() = agencyEnum.friendlyLabel
        /** Solidly exclude Repeaters and Control Channels from proximity alerting. */
        val isAlertableDirectOrMobile: Boolean
            get() = role != "REPEATER" && role != "CONTROL_CHANNEL"
    }

    data class TalkgroupSig(
        val minTg: Int = 0,
        val maxTg: Int = 0,
        val agency: String = "POLICE",
        val label: String = ""
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agency) ?: KokohoAgency.POLICE
        val friendlyLabel: String get() = agencyEnum.friendlyLabel
    }

    data class FleetOuiSig(
        val prefix: String = "",
        val agency: String = "DHS_CBP",
        val label: String = "",
        val confidence: Int = 75
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agency) ?: KokohoAgency.DHS_CBP
        val friendlyLabel: String get() = agencyEnum.friendlyLabel
    }

    private data class CatalogJson(
        val schemaVersion: Int = 2,
        val rfChannels: List<RfChannelSig> = emptyList(),
        val p25Talkgroups: List<TalkgroupSig> = emptyList(),
        val fleetOuis: List<FleetOuiSig> = emptyList()
    )

    @Volatile
    private var loaded = false

    @Volatile
    var rfChannels: List<RfChannelSig> = emptyList()
        private set

    @Volatile
    var talkgroups: List<TalkgroupSig> = emptyList()
        private set

    @Volatile
    var fleetOuis: List<FleetOuiSig> = emptyList()
        private set

    @Volatile
    private var witnessCache: MutableMap<String, WitnessReportRecord> = mutableMapOf()

    private const val KEY_CONFIRMED_EVIDENCE_FACETS = "confirmed_evidence_facets_v1"
    private val confirmedEvidenceFacets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun clearWitnessAndConfirmedCacheInMemory() {
        witnessCache.clear()
        confirmedEvidenceFacets.clear()
        ownTpmsCache.clear()
        learnedTpmsCache.clear()
        tpmsIntelLoaded = false
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        Units.imperial = isImperialUnits(context)
        if (loaded) return
        runCatching {
            context.assets.open(ASSET_FILE).use { stream ->
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                val parsed = Gson().fromJson(text, CatalogJson::class.java)
                if (parsed != null) {
                    rfChannels = parsed.rfChannels
                    talkgroups = parsed.p25Talkgroups
                    fleetOuis = parsed.fleetOuis
                }
            }
        }
        loadWitnessCache(context)
        KokohoReferenceTables.ensureLoaded(context)
        loaded = true
        runRetroactiveCleanupV116IfNeeded(context)
    }

    private fun runRetroactiveCleanupV116IfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("retroactive_cleanup_v116_done", false)) return
        prefs.edit { putBoolean("retroactive_cleanup_v116_done", true) }
        val appCtx = context.applicationContext
        runCatching {
            appCtx.getSharedPreferences("map_safety", Context.MODE_PRIVATE).edit { clear() }
            val panes = org.tucsoncommunity.kokoho.util.Prefs.viewPanes(appCtx)
            if ("map" !in panes) org.tucsoncommunity.kokoho.util.Prefs.setViewPanes(appCtx, panes + "map")
        }
        runCatching { org.tucsoncommunity.kokoho.data.AlertLog.purgeErroneousV116(appCtx) }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                org.tucsoncommunity.kokoho.data.AppDatabase.getInstance(appCtx).detectionDao().purgeErroneousRecordsV116()
            }
        }
    }

    fun getEnabledAgencies(context: Context): Set<KokohoAgency> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ENABLED_AGENCIES, null)
        if (saved == null) {
            return KokohoAgency.entries.filter { it.defaultEnabled }.toSet()
        }
        val parsed = saved.mapNotNull { KokohoAgency.fromCode(it) }.toMutableSet()
        if (!prefs.getBoolean("fed_interop_restored_v109", false)) {
            if (parsed.any { it.isDhs }) {
                parsed.add(KokohoAgency.DHS_INTEROP)
            }
            prefs.edit {
                putBoolean("fed_interop_restored_v109", true)
                putStringSet(KEY_ENABLED_AGENCIES, parsed.map { it.id }.toSet())
            }
        }
        return parsed
    }

    fun setEnabledAgencies(context: Context, agencies: Set<KokohoAgency>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_ENABLED_AGENCIES, agencies.map { it.id }.toSet())
        }
        runCatching {
            if (org.tucsoncommunity.kokoho.sdr.SdrRadio.isRunning()) {
                org.tucsoncommunity.kokoho.sdr.SdrRadio.config =
                    org.tucsoncommunity.kokoho.sdr.SdrRadio.config.copy(targets = watchedTargetsForEnabled(context))
            }
        }
    }

    fun toggleAgency(context: Context, agency: KokohoAgency): Set<KokohoAgency> {
        val current = getEnabledAgencies(context).toMutableSet()
        if (agency in current) current.remove(agency) else current.add(agency)
        setEnabledAgencies(context, current)
        return current
    }

    fun isMasterDhsEnabled(context: Context): Boolean =
        getEnabledAgencies(context).any { it.isDhs }

    fun setMasterDhs(context: Context, enabled: Boolean): Set<KokohoAgency> {
        val current = getEnabledAgencies(context).toMutableSet()
        if (enabled) {
            // Enable primary DHS operational branches by default; keep FPS opt-in
            current.addAll(KokohoAgency.DHS_AGENCIES.filter { it.defaultEnabled })
        } else {
            current.removeAll(KokohoAgency.DHS_AGENCIES.toSet())
        }
        setEnabledAgencies(context, current)
        return current
    }

    // ---- Independent Map Eye (Visibility) vs Monitor Alarm Filters ----

    private val DEFAULT_MAP_VISIBLE_AGENCIES: Set<KokohoAgency> = setOf(
        KokohoAgency.DHS_ICE,
        KokohoAgency.DHS_AMO,
        KokohoAgency.DHS_CBP,
        KokohoAgency.DHS_HSI,
        KokohoAgency.DHS_INTEROP,
        KokohoAgency.POLICE,
        KokohoAgency.SHERIFF,
        KokohoAgency.DRONE
    )

    fun getMapVisibleAgencies(context: Context): Set<KokohoAgency> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_MAP_VISIBLE_AGENCIES, null)
        if (saved == null) {
            return (getEnabledAgencies(context) + DEFAULT_MAP_VISIBLE_AGENCIES).toSet()
        }
        return saved.mapNotNull { KokohoAgency.fromCode(it) }.toSet()
    }

    fun setMapVisibleAgencies(context: Context, agencies: Set<KokohoAgency>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_MAP_VISIBLE_AGENCIES, agencies.map { it.id }.toSet())
        }
        runCatching {
            if (org.tucsoncommunity.kokoho.sdr.SdrRadio.isRunning()) {
                org.tucsoncommunity.kokoho.sdr.SdrRadio.config =
                    org.tucsoncommunity.kokoho.sdr.SdrRadio.config.copy(targets = watchedTargetsForEnabled(context))
            }
        }
    }

    fun toggleAgencyMapVisible(context: Context, agency: KokohoAgency): Set<KokohoAgency> {
        val current = getMapVisibleAgencies(context).toMutableSet()
        if (agency in current) current.remove(agency) else current.add(agency)
        setMapVisibleAgencies(context, current)
        return current
    }

    fun isDhsMasterMapVisible(context: Context): Boolean =
        getMapVisibleAgencies(context).any { it.isDhs }

    fun setDhsMasterMapVisible(context: Context, visible: Boolean): Set<KokohoAgency> {
        val current = getMapVisibleAgencies(context).toMutableSet()
        if (visible) {
            current.addAll(KokohoAgency.DHS_AGENCIES.filter { it.defaultEnabled })
        } else {
            current.removeAll(KokohoAgency.DHS_AGENCIES.toSet())
        }
        setMapVisibleAgencies(context, current)
        return current
    }

    fun isMapLocationsVisible(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MAP_LOCATIONS_VISIBLE, true)

    fun setMapLocationsVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_MAP_LOCATIONS_VISIBLE, visible)
        }
    }

    fun isLocationsAlarmEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCATIONS_ALARM_ENABLED, false)

    fun setLocationsAlarmEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LOCATIONS_ALARM_ENABLED, enabled)
        }
    }

    /** Union of Alarm-enabled and Map-visible agencies so sensors evaluate anything either alerted or drawn on the map. */
    fun getEvaluatedAgencies(context: Context): Set<KokohoAgency> =
        getEnabledAgencies(context) + getMapVisibleAgencies(context)

    // ---- Pro / Enterprise / Military Drone Detection Toggle ----

    fun isDroneDetectionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRONE_ENABLED, true) &&
            KokohoAgency.DRONE in getEnabledAgencies(context)

    fun setDroneDetectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DRONE_ENABLED, enabled)
        }
        val current = getEnabledAgencies(context).toMutableSet()
        if (enabled) current.add(KokohoAgency.DRONE) else current.remove(KokohoAgency.DRONE)
        setEnabledAgencies(context, current)
    }

    // ---- Units (Imperial default: ft / mi) ----

    fun isImperialUnits(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IMPERIAL_UNITS, true)

    fun setImperialUnits(context: Context, imperial: Boolean) {
        Units.imperial = imperial
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_IMPERIAL_UNITS, imperial)
        }
    }

    // ---- Privacy & Connected Features (Offline-first with automatic room & opt-out privacy) ----

    @Volatile
    private var sessionSharingResetDone = false

    /**
     * Ensures live sharing starts OFF ("Share") whenever the app process starts,
     * requiring an explicit tap on the Share pill to begin sharing during a session.
     */
    fun ensureSharingStartsOff(context: Context) {
        if (!sessionSharingResetDone) {
            sessionSharingResetDone = true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_SHARING_ACTIVE_PILL, false)
            }
        }
    }

    /**
     * Master capability toggle in Settings > Privacy: controls whether Contribution is permitted at all.
     * When false, the top-bar Share pill is greyed out and tapping it prompts the user to enable in Settings.
     */
    fun isContributionAllowedInSettings(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONTRIBUTE_OBSERVATIONS, true)

    fun setContributionAllowedInSettings(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_CONTRIBUTE_OBSERVATIONS, allowed)
            putBoolean(KEY_SHARING_ACTIVE_PILL, false)
        }
        if (!allowed) {
            KokohoHubClient.purgeContributedObservations(context)
        }
    }

    /**
     * Quick binary Share pill state (left of Scan button). Starts OFF by default and only becomes
     * active when Contribution is allowed in Settings and the user taps the Share pill.
     */
    fun isSharingActive(context: Context): Boolean =
        isContributionAllowedInSettings(context) &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHARING_ACTIVE_PILL, false)

    fun setSharingActive(context: Context, active: Boolean) {
        if (!isContributionAllowedInSettings(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHARING_ACTIVE_PILL, active)
        }
        if (!active) {
            KokohoHubClient.purgeContributedObservations(context)
        }
    }

    fun isContributeObservationsEnabled(context: Context): Boolean =
        isSharingActive(context)

    fun setContributeObservationsEnabled(context: Context, enabled: Boolean) {
        setContributionAllowedInSettings(context, enabled)
    }

    fun isPushNotificationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PUSH_NOTIFICATIONS, true)

    fun setPushNotificationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PUSH_NOTIFICATIONS, enabled)
        }
        if (enabled) {
            KokohoHubClient.start(context)
        } else {
            KokohoHubClient.stop(context)
        }
    }

    fun isAuxnetEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUXNET_ENABLED, true) &&
            (isPushNotificationsEnabled(context) || isContributeObservationsEnabled(context))

    fun setAuxnetEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUXNET_ENABLED, enabled)
            putBoolean(KEY_CONTRIBUTE_OBSERVATIONS, enabled)
            putBoolean(KEY_PUSH_NOTIFICATIONS, enabled)
        }
        if (enabled) {
            KokohoHubClient.start(context)
        } else {
            KokohoHubClient.purgeContributedObservations(context)
            KokohoHubClient.stop(context)
            KokohoHubClient.clearCachedObjects()
        }
    }

    // ---- Local-Only Entity Overrides & Witness Reports ----

    fun normalizeEntityId(rawId: String): String =
        rawId.removePrefix("kokoho:").trim()

    private fun loadWitnessCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_CONFIRMED_EVIDENCE_FACETS, null)?.forEach { f ->
            if (f.isNotBlank()) confirmedEvidenceFacets.add(f.trim())
        }
        val raw = prefs.getString(KEY_WITNESS_REPORTS_JSON, null) ?: return
        runCatching {
            val type = object : TypeToken<List<WitnessReportRecord>>() {}.type
            val list: List<WitnessReportRecord> = Gson().fromJson(raw, type) ?: emptyList()
            val next = mutableMapOf<String, WitnessReportRecord>()
            for (r in list) {
                val normTarget = normalizeEntityId(r.targetKey)
                next[normTarget] = r
                r.entityUuid?.let { next[normalizeEntityId(it)] = r }
                if (hasConfirmingReport(r)) {
                    confirmedEvidenceFacets.add("entity:$normTarget")
                    r.entityUuid?.let { confirmedEvidenceFacets.add("entity:${normalizeEntityId(it)}") }
                    confirmedEvidenceFacets.addAll(canonicalConfirmableFacetKeys(listOfNotNull(r.targetKey, r.entityUuid), null))
                }
            }
            witnessCache = next
        }
    }

    private fun persistConfirmedEvidence(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_CONFIRMED_EVIDENCE_FACETS, confirmedEvidenceFacets.toSet())
        }
    }

    /**
     * Extracts canonical unit-specific evidence keys (`hw:mac:*`, `hw:kit:*`, `kitmac:*`, `uas:mac:*`,
     * `adsb:icao:*`, `rf:rid:*`) that uniquely identify hardware/radios across time.
     * Deliberately excludes shared channels (`rf:chan:*`) and shared talkgroups (`p25:tg:*`).
     */
    fun canonicalConfirmableFacetKeys(facets: Collection<String>, hardwareIdentifier: String? = null): Set<String> {
        val out = LinkedHashSet<String>()
        fun absorbToken(raw: String) {
            val t = raw.trim()
            if (t.isEmpty()) return
            when {
                t.startsWith("hw:mac:", ignoreCase = true) -> {
                    val mac = normalizeMac(t.substringAfter("hw:mac:"))
                    if (mac.length == 17) {
                        out += "hw:mac:$mac"
                        out += "hw:mac:${routerRadioGroupKey(mac)}"
                    }
                }
                t.startsWith("hw:kit:tpms:", ignoreCase = true) -> {
                    canonicalTpmsFacetKey(t)?.let { out += it }
                }
                t.startsWith("hw:kit:", ignoreCase = true) -> {
                    val afterKit = t.substringAfter("hw:kit:")
                    val kind = afterKit.substringBefore(':').lowercase()
                    val mac = normalizeMac(afterKit.substringAfter(':', ""))
                    if (mac.length == 17) {
                        out += "hw:kit:$kind:$mac"
                        out += "kitmac:$mac"
                        out += "hw:mac:${routerRadioGroupKey(mac)}"
                    }
                }
                t.startsWith("uas:mac:", ignoreCase = true) -> {
                    val mac = normalizeMac(t.substringAfter("uas:mac:"))
                    if (mac.length == 17) out += "uas:mac:$mac"
                }
                t.startsWith("adsb:icao:", ignoreCase = true) -> {
                    val hex = t.substringAfter("adsb:icao:").trim().uppercase()
                    if (hex.isNotEmpty()) out += "adsb:icao:$hex"
                }
                t.startsWith("rf:rid:", ignoreCase = true) -> {
                    val rid = t.substringAfter("rf:rid:").trim()
                    if (rid.isNotEmpty() && rid.all { it.isDigit() }) out += "rf:rid:$rid"
                }
                else -> {
                    val maybeMac = normalizeMac(t)
                    if (maybeMac.length == 17 && maybeMac.count { it == ':' } == 5) {
                        out += "hw:mac:$maybeMac"
                        out += "hw:mac:${routerRadioGroupKey(maybeMac)}"
                        out += "kitmac:$maybeMac"
                    } else if (t.startsWith("RID ", ignoreCase = true)) {
                        val rid = t.removePrefix("RID ").removePrefix("rid ").trim().takeWhile { it.isDigit() }
                        if (rid.isNotEmpty()) out += "rf:rid:$rid"
                    } else if (t.contains("ICAO ", ignoreCase = true)) {
                        val hex = t.substringAfter("ICAO ").trim().takeWhile { it.isLetterOrDigit() }.uppercase()
                        if (hex.length == 6) out += "adsb:icao:$hex"
                    }
                }
            }
        }
        facets.forEach(::absorbToken)
        hardwareIdentifier?.let(::absorbToken)
        return out
    }

    /** Marks one or more exact unit facets/MACs/RIDs as having been confirmed before. */
    fun recordConfirmedEvidence(context: Context?, vararg rawFacetsOrMacs: String) {
        val keys = canonicalConfirmableFacetKeys(rawFacetsOrMacs.toList(), null)
        if (keys.isEmpty()) return
        confirmedEvidenceFacets.addAll(keys)
        if (context != null) persistConfirmedEvidence(context)
    }

    private fun harvestConfirmedEvidenceFromReport(context: Context, report: WitnessReportRecord) {
        val normKey = normalizeEntityId(report.targetKey)
        val normUuid = report.entityUuid?.let { normalizeEntityId(it) }
        if (report.reportKind.equals("FALSE_POSITIVE", ignoreCase = true)) {
            confirmedEvidenceFacets.remove("entity:$normKey")
            normUuid?.let { confirmedEvidenceFacets.remove("entity:$it") }
            revokeTpmsForEntity(context, normKey, normUuid)
            persistConfirmedEvidence(context)
            return
        }
        if (!hasConfirmingReport(report)) return
        confirmedEvidenceFacets.add("entity:$normKey")
        normUuid?.let { confirmedEvidenceFacets.add("entity:$it") }
        val (facets, hwId) = KokohoPostureEngine.peekThreatFacets(context, normKey)
        confirmedEvidenceFacets.addAll(
            canonicalConfirmableFacetKeys(facets + listOfNotNull(report.targetKey, report.entityUuid), hwId)
        )
        val wrAgency = KokohoAgency.fromCode(report.agencyCode) ?: KokohoAgency.DHS_ICE
        val entityId = normUuid ?: normKey
        promoteWitnessTpmsForReport(context, entityId, wrAgency, report)
        persistConfirmedEvidence(context)
    }

    private fun persistWitnessCache(context: Context) {
        val distinct = witnessCache.values.distinctBy { it.targetKey }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_WITNESS_REPORTS_JSON, Gson().toJson(distinct))
        }
    }

    fun getWitnessReport(context: Context, keyOrUuid: String, approvedOnly: Boolean = false): WitnessReportRecord? {
        ensureLoaded(context)
        val norm = normalizeEntityId(keyOrUuid)
        val rec = witnessCache[norm] ?: return null
        if (approvedOnly && !rec.isApproved) return null
        return rec
    }

    fun getAllWitnessReports(context: Context): List<WitnessReportRecord> {
        ensureLoaded(context)
        return witnessCache.values.distinctBy { it.targetKey }.sortedByDescending { it.updatedAtMs }
    }

    fun saveWitnessReport(context: Context, report: WitnessReportRecord, syncRemote: Boolean = true) {
        ensureLoaded(context)
        val normKey = normalizeEntityId(report.targetKey)
        val newStatus = if (syncRemote) "PENDING" else report.status.ifBlank { "APPROVED" }
        val cleaned = report.copy(
            targetKey = normKey,
            updatedAtMs = System.currentTimeMillis(),
            status = newStatus
        )
        witnessCache[normKey] = cleaned
        cleaned.entityUuid?.let { witnessCache[normalizeEntityId(it)] = cleaned }
        if (cleaned.reportKind.equals("LE", ignoreCase = true)) {
            org.tucsoncommunity.kokoho.sdr.SdrRadio.requestTpmsHarvest(cleaned.updatedAtMs)
        }
        harvestConfirmedEvidenceFromReport(context, cleaned)
        if (cleaned.isApproved && (cleaned.agentName.isNotBlank() || cleaned.badgeNumber.isNotBlank())) {
            val summary = listOfNotNull(
                cleaned.agentName.takeIf { it.isNotBlank() },
                cleaned.badgeNumber.takeIf { it.isNotBlank() }?.let { "Badge #$it" },
                cleaned.vehicleNumber.takeIf { it.isNotBlank() }?.let { "Veh $it" }
            ).joinToString(" · ")
            if (summary.isNotBlank()) {
                setCustomEntityLabel(context, normKey, summary)
            }
        }
        persistWitnessCache(context)
        if (syncRemote) {
            KokohoHubClient.submitWitnessReport(cleaned, context)
        }
    }

    fun mergeRemoteWitnessReports(context: Context, reports: List<WitnessReportRecord>) {
        if (reports.isEmpty()) return
        ensureLoaded(context)
        var changed = false
        for (r in reports) {
            val norm = normalizeEntityId(r.targetKey)
            val existing = witnessCache[norm]
            val approvedIncoming = r.copy(targetKey = norm, status = r.status.ifBlank { "APPROVED" })
            if (existing == null || approvedIncoming.isApproved || approvedIncoming.updatedAtMs >= existing.updatedAtMs) {
                witnessCache[norm] = approvedIncoming
                approvedIncoming.entityUuid?.let { witnessCache[normalizeEntityId(it)] = approvedIncoming }
                harvestConfirmedEvidenceFromReport(context, approvedIncoming)
                changed = true
            }
        }
        if (changed) persistWitnessCache(context)
    }

    fun getCustomEntityLabel(context: Context, entityUuid: String): String? {
        val id = normalizeEntityId(entityUuid)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$KEY_CUSTOM_ENTITY_PREFIX$id", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setCustomEntityLabel(context: Context, entityUuid: String, label: String?) {
        val id = normalizeEntityId(entityUuid)
        val cleaned = label?.trim().orEmpty()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (cleaned.isEmpty()) {
                remove("$KEY_CUSTOM_ENTITY_PREFIX$id")
            } else {
                putString("$KEY_CUSTOM_ENTITY_PREFIX$id", cleaned)
            }
        }
    }

    fun resolveEntityLabel(context: Context, entityUuid: String, defaultLabel: String): String =
        getCustomEntityLabel(context, entityUuid) ?: defaultLabel

    fun isEntityWhitelisted(context: Context, entityUuid: String): Boolean {
        val id = normalizeEntityId(entityUuid)
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_WHITELISTED_ENTITIES, emptySet())
            .orEmpty()
        return id in set
    }

    fun setEntityWhitelisted(context: Context, entityUuid: String, whitelisted: Boolean) {
        val id = normalizeEntityId(entityUuid)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_WHITELISTED_ENTITIES, emptySet()).orEmpty().toMutableSet()
        if (whitelisted) next.add(id) else next.remove(id)
        prefs.edit { putStringSet(KEY_WHITELISTED_ENTITIES, next) }
    }

    fun isEntityWatched(context: Context, entityUuid: String): Boolean {
        val id = normalizeEntityId(entityUuid)
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_WATCHED_ENTITIES, emptySet())
            .orEmpty()
        return id in set
    }

    fun setEntityWatched(context: Context, entityUuid: String, watched: Boolean) {
        val id = normalizeEntityId(entityUuid)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_WATCHED_ENTITIES, emptySet()).orEmpty().toMutableSet()
        if (watched) next.add(id) else next.remove(id)
        prefs.edit { putStringSet(KEY_WATCHED_ENTITIES, next) }
    }

    // ---- Map Time Window ("30m", "3h", "24h", "1wk", "1mo", "∞"), Heat Overlay, Confirmation Filter, Gravity Clustering & Color Mixing ----

    enum class MapTimeWindow(val id: String, val label: String, val windowMs: Long) {
        MIN_30("30M", "30m", 30 * 60_000L),
        HOURS_3("3H", "3h", 3 * 60 * 60_000L),
        HOURS_24("24H", "24h", 24 * 60 * 60_000L),
        WEEK_1("1W", "1wk", 7L * 24 * 60 * 60_000L),
        MONTH_1("1M", "1mo", 30L * 24 * 60 * 60_000L),
        HEAT("ALL_TIME", "∞", 36_500L * 24 * 60 * 60_000L);

        companion object {
            val ALL_TIME: MapTimeWindow get() = HEAT

            /** Rolling duration windows exposed in the Map UI (`30m`, `3h`, `24h`, `1wk`, `1mo`, `∞`). */
            val ROLLING_UI_WINDOWS = listOf(MIN_30, HOURS_3, HOURS_24, WEEK_1, MONTH_1, HEAT)

            fun fromId(id: String?): MapTimeWindow {
                if (id.isNullOrBlank()) return MIN_30
                val trimmed = id.trim()
                val up = trimmed.uppercase()
                if (up == "ALL" || up == "ALL_TIME" || up == "HEAT" || up == "INF" || up == "INFINITY" || trimmed == "∞") {
                    return HEAT
                }
                return entries.firstOrNull { it.id.equals(up, ignoreCase = true) || it.label.equals(trimmed, ignoreCase = true) } ?: MIN_30
            }
        }
    }

    enum class MapConfirmationFilter(val id: String, val label: String) {
        CONFIRMED("CONFIRMED", "Confirmed"),
        UNCONFIRMED("UNCONFIRMED", "Unconfirmed"),
        ALL("ALL", "All");

        companion object {
            val UI_OPTIONS = listOf(CONFIRMED, UNCONFIRMED, ALL)

            fun fromId(id: String?): MapConfirmationFilter {
                if (id.isNullOrBlank()) return ALL
                val up = id.trim().uppercase()
                return entries.firstOrNull {
                    it.id.equals(up, ignoreCase = true) || it.label.equals(up, ignoreCase = true)
                } ?: ALL
            }
        }
    }

    /**
     * Zoom-adaptive proximity merge threshold (meters) for collapsing nearby centroids into
     * a single group-gravity bubble. Ungroups more aggressively at street/building breakpoints,
     * and returns `0.0` at `zoom >= 15.5` so zooming way in splits all detections into their
     * respective pucks at each center, regardless of actual precision.
     */
    fun zoomGravityMergeRadiusM(zoom: Double): Double = when {
        zoom < 10.0 -> 650.0
        zoom < 11.0 -> 360.0
        zoom < 12.0 -> 180.0
        zoom < 13.0 -> 85.0
        zoom < 14.0 -> 38.0
        zoom < 15.0 -> 16.0
        zoom < 15.5 -> 6.0
        else -> 0.0
    }

    /**
     * Blends agency map colors weighted by each agency's colocated gravity mass [agencyWeights]
     * relative to a cluster's center of gravity.
     * Note: `DHS_INTEROP` (`Federal Interop`) is never used as the primary attested agency color
     * when any specific agency (`DHS_ICE`, `DHS_CBP`, `DHS_HSI`, `SHERIFF`, `POLICE`, etc.) is present.
     */
    fun mixAgencyColorsArgb(agencyWeights: Map<KokohoAgency, Double>): Int {
        val allPositive = agencyWeights.filter { it.value > 1e-6 }
        if (allPositive.isEmpty()) return KokohoAgency.DHS_CBP.mapColorArgb
        val specificPositive = allPositive.filterKeys { it != KokohoAgency.DHS_INTEROP }
        val positive = if (specificPositive.isNotEmpty()) specificPositive else allPositive
        if (positive.size == 1) return positive.keys.first().mapColorArgb

        var totalWeight = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var maxOrigChannel = 0.0

        for ((agency, w) in positive) {
            val c = agency.mapColorArgb
            val r = ((c ushr 16) and 0xFF).toDouble()
            val g = ((c ushr 8) and 0xFF).toDouble()
            val b = (c and 0xFF).toDouble()
            sumR += r * w
            sumG += g * w
            sumB += b * w
            totalWeight += w
            val peak = maxOf(r, g, b)
            if (peak > maxOrigChannel) maxOrigChannel = peak
        }
        if (totalWeight <= 0.0) return positive.keys.first().mapColorArgb

        var rMix = sumR / totalWeight
        var gMix = sumG / totalWeight
        var bMix = sumB / totalWeight

        // Preserve vibrant brightness so complementary agency blends don't wash out into muddy grey
        val mixPeak = maxOf(rMix, gMix, bMix)
        if (mixPeak in 1.0..(maxOrigChannel - 1.0)) {
            val boost = (0.35 + 0.65 * (maxOrigChannel / mixPeak)).coerceIn(1.0, 1.35)
            rMix = (rMix * boost).coerceIn(0.0, 255.0)
            gMix = (gMix * boost).coerceIn(0.0, 255.0)
            bMix = (bMix * boost).coerceIn(0.0, 255.0)
        }

        val ri = (rMix + 0.5).toInt().coerceIn(0, 255)
        val gi = (gMix + 0.5).toInt().coerceIn(0, 255)
        val bi = (bMix + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun getMapTimeWindow(context: Context): MapTimeWindow {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAP_TIME_WINDOW, MapTimeWindow.MIN_30.id)
        return MapTimeWindow.fromId(raw)
    }

    fun getActiveMapTimeWindow(context: Context): MapTimeWindow = getMapTimeWindow(context)

    fun setMapTimeWindow(context: Context, window: MapTimeWindow) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_MAP_TIME_WINDOW, window.id)
        }
    }

    fun isMapHeatOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MAP_HEAT_OVERLAY_ENABLED, false)

    fun setMapHeatOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_MAP_HEAT_OVERLAY_ENABLED, enabled)
        }
    }

    fun getMapConfirmationFilter(context: Context): MapConfirmationFilter {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAP_CONFIRMATION_FILTER, MapConfirmationFilter.ALL.id)
        return MapConfirmationFilter.fromId(raw)
    }

    fun setMapConfirmationFilter(context: Context, filter: MapConfirmationFilter) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_MAP_CONFIRMATION_FILTER, filter.id)
        }
    }

    fun hasApprovedWitnessReportForUuid(context: Context, keyOrUuid: String): Boolean {
        if (keyOrUuid.isBlank()) return false
        val rec = getWitnessReport(context, keyOrUuid, approvedOnly = true) ?: return false
        return rec.reportKind.equals("LE", ignoreCase = true)
    }

    fun hasApprovedWitnessReport(context: Context, threat: EvaluatedThreat): Boolean {
        if (hasApprovedWitnessReportForUuid(context, threat.uuid)) return true
        if (threat.trackUuid.isNotBlank() && threat.trackUuid != threat.uuid &&
            hasApprovedWitnessReportForUuid(context, threat.trackUuid)
        ) {
            return true
        }
        val hw = threat.hardwareIdentifier?.takeIf { it.isNotBlank() }
        if (hw != null && hasApprovedWitnessReportForUuid(context, hw)) return true
        for (ev in threat.evidenceSources) {
            if (hasApprovedWitnessReportForUuid(context, ev.facetKey)) return true
        }
        return false
    }

    fun matchesMapConfirmationFilter(
        context: Context,
        threat: EvaluatedThreat,
        filter: MapConfirmationFilter = getMapConfirmationFilter(context)
    ): Boolean {
        return when (filter) {
            MapConfirmationFilter.ALL -> true
            MapConfirmationFilter.CONFIRMED -> hasApprovedWitnessReport(context, threat)
            MapConfirmationFilter.UNCONFIRMED -> {
                val confirmed = hasApprovedWitnessReport(context, threat)
                val standaloneWitnessPoint = threat.evidenceSources.isNotEmpty() &&
                    threat.evidenceSources.all { it.kind.equals("WITNESS_REPORT", ignoreCase = true) }
                !confirmed && !standaloneWitnessPoint
            }
        }
    }

    fun signalDecayMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SIGNAL_DECAY_MINUTES, DEFAULT_DECAY_MINUTES)
            .coerceIn(5, 240)

    fun setSignalDecayMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_SIGNAL_DECAY_MINUTES, minutes.coerceIn(5, 240))
        }
    }

    /** Duration (ms) a signal stays at 100% solid opacity on the map (default 30 min). */
    fun signalDecayMs(context: Context): Long =
        signalDecayMinutes(context) * 60_000L

    /** Total map visibility lifetime governed by the active rolling MapTimeWindow (`30m`, `3h`, or `24h`). */
    fun mapMaxVisibilityMs(context: Context): Long =
        getMapTimeWindow(context).windowMs

    /**
     * Rolling confidence-centroid radius projection across `30m`, `3h`, and `24h`:
     *  - Expands linearly from `1.00x` to `1.25x` (`+25%`) over the first `30m` without observation.
     *  - Holds that `1.25x` expanded centroid radius across `3h` and `24h`.
     */
    fun projectRollingRadiusM(
        window: MapTimeWindow,
        ageMs: Long,
        baseRadiusM: Int,
        isPinpointed: Boolean = false
    ): Int {
        if (isPinpointed) return baseRadiusM.coerceAtLeast(15)
        val clampedBase = baseRadiusM.coerceAtLeast(20)
        val thirtyMinMs = 30 * 60_000.0
        val expandFrac = (ageMs.coerceAtLeast(0L).toDouble() / thirtyMinMs).coerceIn(0.0, 1.0)
        return kotlin.math.round(clampedBase * (1.0 + 0.25 * expandFrac)).toInt().coerceAtLeast(20)
    }

    /**
     * Window-specific rolling opacity curve (`30m` | `3h` | `24h` | `1wk` | `1mo` | `Heat`):
     *  - `30m`: fades continuously across `0..30m` (`1.00 -> 0.16`).
     *  - `3h`: stays full (`1.00`) across `0..150m` (`2h 30m`), fading over `-30m` (`150m..180m`).
     *  - `24h`: stays full (`1.00`) across `0..23h`, fading over `-1h` (`23h..24h`).
     *  - `1wk`: stays full (`1.00`) across `0..6d`, fading at `-1d` (`6d..7d`).
     *  - `1mo`: stays full (`1.00`) across `0..23d`, fading at `-1wk` (`23d..30d`).
     *  - `Heat`: `N/A` fade (`1.00` across all-time; rendered as 50% opacity smooth non-hexagonal heatmap).
     */
    fun mapWindowOpacity(window: MapTimeWindow, ageMs: Long): Float {
        val age = ageMs.coerceAtLeast(0L)
        if (age > window.windowMs) return 0f
        val floor = 0.16f
        return when (window) {
            MapTimeWindow.MIN_30 -> {
                val frac = (age.toFloat() / (30 * 60_000f)).coerceIn(0f, 1f)
                (1.0f - (1.0f - floor) * frac).coerceIn(floor, 1.0f)
            }
            MapTimeWindow.HOURS_3 -> {
                val fadeStart = 150 * 60_000L
                val fadeSpan = 30 * 60_000f
                if (age <= fadeStart) 1.0f
                else {
                    val frac = ((age - fadeStart).toFloat() / fadeSpan).coerceIn(0f, 1f)
                    (1.0f - (1.0f - floor) * frac).coerceIn(floor, 1.0f)
                }
            }
            MapTimeWindow.HOURS_24 -> {
                val fadeStart = 23L * 60 * 60_000L
                val fadeSpan = 60 * 60_000f
                if (age <= fadeStart) 1.0f
                else {
                    val frac = ((age - fadeStart).toFloat() / fadeSpan).coerceIn(0f, 1f)
                    (1.0f - (1.0f - floor) * frac).coerceIn(floor, 1.0f)
                }
            }
            MapTimeWindow.WEEK_1 -> {
                val dayMs = 24L * 60 * 60_000L
                val fadeStart = 6L * dayMs
                val fadeSpan = dayMs.toFloat()
                if (age <= fadeStart) 1.0f
                else {
                    val frac = ((age - fadeStart).toFloat() / fadeSpan).coerceIn(0f, 1f)
                    (1.0f - (1.0f - floor) * frac).coerceIn(floor, 1.0f)
                }
            }
            MapTimeWindow.MONTH_1 -> {
                val dayMs = 24L * 60 * 60_000L
                val fadeStart = 23L * dayMs
                val fadeSpan = (7L * dayMs).toFloat()
                if (age <= fadeStart) 1.0f
                else {
                    val frac = ((age - fadeStart).toFloat() / fadeSpan).coerceIn(0f, 1f)
                    (1.0f - (1.0f - floor) * frac).coerceIn(floor, 1.0f)
                }
            }
            MapTimeWindow.HEAT -> 1.0f
        }
    }

    /** Computes opacity multiplier [0.16 .. 1.0] for a map detection of age [ageMs] in the active rolling window. */
    fun mapStalenessOpacity(context: Context, ageMs: Long): Float =
        mapWindowOpacity(getMapTimeWindow(context), ageMs)

    /**
     * Computes map opacity combining temporal staleness with multi-detection corroboration (1 -> 3+ detections)
     * and radius tightness:
     *  - Single isolated detection (`totalSightings == 1` and `distinctSignals == 1`) with wide radius is
     *    heavily underweighted (`~0.20 - 0.28`) so floor noise is subtle without dominating the map.
     *  - 2 detections step up (`~0.55`).
     *  - 3+ detections or multi-signal clusters (`distinctSignals >= 2`) with tighter derived radii (`<= 250m`)
     *    reach full vibrant opacity (`1.0`).
     */
    fun mapCorroborationOpacity(
        context: Context,
        ageMs: Long,
        totalSightings: Int,
        distinctSignals: Int,
        radiusM: Int
    ): Float {
        val staleness = mapStalenessOpacity(context, ageMs)
        val effectiveHits = if (distinctSignals >= 2) maxOf(totalSightings, distinctSignals + 1) else totalSightings
        val hitFactor = when {
            effectiveHits <= 1 -> 0.24f
            effectiveHits == 2 -> 0.56f
            effectiveHits == 3 -> 0.85f
            else -> 1.0f
        }
        val radiusFactor = when {
            radiusM <= 120 -> 1.18f
            radiusM <= 260 -> 1.05f
            radiusM <= 550 -> 0.90f
            radiusM <= 1000 -> 0.72f
            else -> 0.55f
        }
        return (staleness * hitFactor * radiusFactor).coerceIn(0.14f, 1.0f)
    }

    // ---- ADS-B Aircraft Options (DHS & Circling Spy Planes ON by default) ----

    fun isAdsbEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADSB_ENABLED, true)

    fun setAdsbEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ADSB_ENABLED, enabled)
        }
    }

    fun getEnabledAircraftAgencies(context: Context): Set<KokohoAgency> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ENABLED_AIRCRAFT_AGENCIES, null)
        if (saved == null) {
            return setOf(KokohoAgency.DHS_AMO, KokohoAgency.DHS_CBP, KokohoAgency.DHS_ICE)
        }
        return saved.mapNotNull { KokohoAgency.fromCode(it) }.toSet()
    }

    fun setEnabledAircraftAgencies(context: Context, agencies: Set<KokohoAgency>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_ENABLED_AIRCRAFT_AGENCIES, agencies.map { it.id }.toSet())
        }
    }

    fun isDhsAircraftEnabled(context: Context): Boolean =
        getEnabledAircraftAgencies(context).any { it.isDhs }

    fun setDhsAircraftEnabled(context: Context, enabled: Boolean): Set<KokohoAgency> {
        val current = getEnabledAircraftAgencies(context).toMutableSet()
        if (enabled) {
            current.add(KokohoAgency.DHS_AMO)
            current.add(KokohoAgency.DHS_CBP)
            current.add(KokohoAgency.DHS_ICE)
        } else {
            current.removeAll { it.isDhs }
        }
        setEnabledAircraftAgencies(context, current)
        return current
    }

    fun toggleAircraftAgency(context: Context, agency: KokohoAgency): Set<KokohoAgency> {
        val current = getEnabledAircraftAgencies(context).toMutableSet()
        if (agency in current) current.remove(agency) else current.add(agency)
        setEnabledAircraftAgencies(context, current)
        return current
    }

    private val HELICOPTER_AIRFRAME_REGEX = Regex(
        "\\b(AS50|AS350|H125|EC120|EC130|EC135|EC45|H145|B06|B206|B407|B412|B429|MD500|MD520|MD600|R22|R44|R66|S76|AW139|AW119|UH-?1|HELI|HELICOPTER|ROTORCRAFT|CHOPPER|EUROCPTR|AIRBUS HELI|BELL 206|BELL 407)\\b",
        RegexOption.IGNORE_CASE
    )
    private val FIXED_WING_AIRFRAME_REGEX = Regex(
        "\\b(PC-?12|PILATUS|C140|C150|C152|C170|C172|C177|C182|C206|C208|C210|CESSNA|SKYHAWK|SKYLANE|STATIONAIR|CENTURION|CARAVAN|PIPER|PA-?[0-9]{2}|P28[A-Z]|TOMAHAWK|CHEROKEE|ARCHER|WARRIOR|SEMINOLE|SENECA|COMANCHE|NAVAJO|CHEYENNE|BEECH|BONANZA|BARON|B350|BE20|BE30|KING ?AIR|MOONEY|M20[A-Z]?|CIRRUS|SR20|SR22|DIAMOND|DA20|DA40|DA42|DA62|VULCANAIR|PARTENAVIA|P68|DASH-?8|DHC-?8|P-?3|ORION|MQ-?9|SPY PLANE|FIXED[- ]WING)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isHelicopterAirframe(
        type: String?,
        category: String?,
        labelOrDetail: String?,
        agency: KokohoAgency? = null,
        hex: String? = null
    ): Boolean {
        if (category.equals("A7", ignoreCase = true)) return true
        KokohoReferenceTables.lookupAircraft(hex)?.let { ref ->
            if (ref.airframeKind == 'H') return true
            if (ref.airframeKind == 'F' || ref.airframeKind == 'G') return false
        }
        val combined = listOfNotNull(type, labelOrDetail).joinToString(" ")
        if (combined.isNotBlank() && HELICOPTER_AIRFRAME_REGEX.containsMatchIn(combined)) return true
        // DHS Air (DHS_AMO) defaults to helicopter classification & symbol unless explicitly fixed-wing
        if (agency == KokohoAgency.DHS_AMO || category.equals("DHS_AMO", ignoreCase = true)) {
            if (combined.isBlank() || !FIXED_WING_AIRFRAME_REGEX.containsMatchIn(combined)) {
                return true
            }
        }
        return false
    }

    /**
     * Heuristic filter for the misidentified "all-flights-at-the-same-time" ADS-B session and any
     * downstream records (`entities`, `tracks`, `active_objects`):
     * Genuine tactical air detections carry specific attribution (tail number `N...`, aircraft model,
     * circling turn metric, or `CALL:` / `ALERT:ORBITING` / `OUI:AIR` / `RF:CHAN:` facets).
     * Unattributed `adsb:icao:*` / `DHS_AMO` records with a bare generic label (`"DHS Air"`, `"ADS-B Aircraft"`)
     * or stationary `avgVelocityMps < 2.0` are filtered out.
     */
    fun isAnomalousUnattributedAirRecord(
        primaryKey: String?,
        category: String?,
        label: String?,
        facets: List<String> = emptyList(),
        avgVelocityMps: Double? = null
    ): Boolean {
        val pkLow = primaryKey?.trim()?.lowercase().orEmpty()
        val catUp = category?.trim()?.uppercase().orEmpty()
        val isAir = catUp == "DHS_AMO" || pkLow.startsWith("adsb:") || pkLow.startsWith("icao:") || pkLow.startsWith("air:")
        if (!isAir) return false
        if (avgVelocityMps != null && catUp == "DHS_AMO" && avgVelocityMps in 0.0001..1.99) return true
        val hasSpecificFacet = facets.any { f ->
            val u = f.trim().uppercase()
            u.startsWith("CALL:") || u.startsWith("ALERT:ORBITING") || u.startsWith("OUI:AIR") || u.startsWith("RF:CHAN:")
        }
        if (hasSpecificFacet) return false
        val lblLow = label?.trim()?.lowercase().orEmpty()
        val isBareLabel = lblLow.isEmpty() ||
            lblLow == "dhs air" ||
            lblLow == "dhs_amo" ||
            lblLow == "ads-b aircraft" ||
            lblLow == "aircraft"
        return isBareLabel
    }

    private val DHS_AIR_TOKEN_REGEX = Regex("\\b(CUSTOMS|BORDER|HOMELAND|CBP|OMAHA|ICE|HSI|AMO)\\b")
    private val CIRCLING_AIR_TOKEN_REGEX = Regex("\\bCIRCLING\\b|AIRCRAFT CIRCLING OVERHEAD")
    private val COMMERCIAL_AIRLINE_REGEX = Regex(
        "\\b(SOUTHWEST|AMERICAN|UNITED|DELTA|JETBLUE|ALASKA|FRONTIER|SPIRIT|ALLEGIANT|FEDEX|UPS|SKYWEST|ENVOY|MESA|REPUBLIC|AIRWAYS|AIRLINES|EXPRESSJET|HORIZON|SUN COUNTRY)\\b"
    )
    private val EXCLUDED_MILITARY_OR_GLIDER_AIR_REGEX = Regex(
        "(MOTOR)?GLIDER|\\b(GLID|SAILPLANE|ASK-?21|DG-?1000|TG-?1[0-9]|G-?10[23]|L-?13|L-?23|BLANIK|PA-?38|PA38|TOMAHAWK|PA-?44|PA44|SEMINOLE|PA-?24|PA24|COMANCHE|P28[A-Z]|PA-?28|PA28|CHEROKEE|ARCHER|WARRIOR|DAKOTA|C140|C150|C152|C170|C172|C177|CARDINAL|CESSNA 15[02]|CESSNA 17[027]|SKYHAWK|CUTLASS|DA20|DA40|SR20|AA-?5|CHEETAH|TIGER|M20[A-Z]?|MOONEY|RV-[0-9]{1,2}|UH-?72|LUH-?72|LAKOTA|UH-?60|MH-?60|HH-?60|S-?70|BLACK ?HAWK|CH-?47|AH-?64|EC-?145|BK-?117|T-?38|T-?6|KC-?135|KC-?46|C-?130|C-?20|C-?12|C-?17|C-?5|E-?3|B-?52|V-?22|A-?10|F-?35|F-?16|USAF|AIR FORCE|ARMY|NAVY|MARINES|NATIONAL GUARD)\\b"
    )
    private val SHELL_OR_SURVEILLANCE_AIR_OWNER_REGEX = Regex(
        "\\b(LLC|INC|CORP|CORPORATION|TRUST|TRUSTEE|LEASING|HOLDINGS|AVIATION|AERIAL|RESEARCH|SYSTEMS|SOLUTIONS|SERVICES|CONTRACTING|OBRERO|PXW|FVX|DYNAMIC|SIERRA NEVADA|LASAI|TENAX|STRAIGHT FLIGHT)\\b"
    )
    private val SURVEILLANCE_AIRFRAME_MODEL_REGEX = Regex(
        "\\b(PC-?12|PILATUS|C182|C206|C208|C210|SKYLANE|STATIONAIR|CARAVAN|B350|BE20|BE30|KING ?AIR|DASH-?8|DHC-?8|P68|DA42|DA62|P-?3|ORION|MQ-?9)\\b"
    )
    private val SHERIFF_AIR_TOKEN_REGEX = Regex("\\b(SHERIFF|PCSO|COCHISE|MARICOPA|PINAL)\\b|PIMA COUNTY")
    private val SAR_AIR_TOKEN_REGEX = Regex("\\b(RESCUE|SAR)\\b|SEARCH AND RESCUE")
    private val FIRE_AIR_TOKEN_REGEX = Regex("\\b(FIRE|USFS|FORESTRY|BLM|AT-?802|TANKER)\\b|FOREST SERVICE|AIR TRACTOR")
    private val POLICE_AIR_TOKEN_REGEX =
        Regex("\\b(POLICE|TPD|TUCSON|TROOPER|DPS|FBI|DEA|MARSHALS|ATF)\\b|HIGHWAY PATROL|STATE POLICE|PUBLIC SAFETY")

    /**
     * Classifies an ADS-B aircraft owner/callsign/registration/behavior into a [KokohoAgency].
     * Ordinary commercial, civil, glider, and non-LE US military flights return null so only genuine
     * LE/DHS/SAR/Fire or verified circling surveillance aircraft appear on the tactical radar.
     */
    fun classifyAircraftAgency(
        owner: String?,
        registration: String?,
        callsign: String?,
        hitLabel: String?,
        type: String? = null,
        hex: String? = null,
        speedKt: Double? = null,
        isCircling: Boolean = false
    ): KokohoAgency? {
        if (speedKt != null && speedKt < 15.0) return null
        val ref = KokohoReferenceTables.lookupAircraft(hex)
        val combined = listOfNotNull(
            owner,
            ref?.owner?.takeIf { owner.isNullOrBlank() },
            registration,
            ref?.registration?.takeIf { registration.isNullOrBlank() },
            callsign,
            hitLabel,
            type,
            ref?.icaoType?.takeIf { type.isNullOrBlank() }
        ).joinToString(" ").uppercase()
        if (combined.isBlank() && ref == null) return null
        val explicitPolice = POLICE_AIR_TOKEN_REGEX.containsMatchIn(combined)
        val explicitSheriff = SHERIFF_AIR_TOKEN_REGEX.containsMatchIn(combined)
        val explicitSar = SAR_AIR_TOKEN_REGEX.containsMatchIn(combined)
        val explicitFire = FIRE_AIR_TOKEN_REGEX.containsMatchIn(combined)
        val explicitDhs = DHS_AIR_TOKEN_REGEX.containsMatchIn(combined)
        val explicitLeOrCivic = explicitPolice || explicitSheriff || explicitSar || explicitFire || explicitDhs

        // Authoritative FAA + plane-alert-db reference lookup:
        //  - Immediately exclude Medevac (`MED`), Flight Schools / Primary Trainers (`TRN`), Airlines (`AIR`), & Non-LE Military (`MIL`)
        //  - Immediately resolve verified LE / DHS / Covert FBI-DHS Shell / PIA aircraft (`LE`, `GOV`) even when offline!
        if (!explicitLeOrCivic && ref != null) {
            if (ref.isExcludedNonThreat) return null
            if (ref.registrantKind == 'I' && ref.disposition == "CIV") return null
            if (ref.isExplicitLeOrGov && ref.agencyHint != null) return ref.agencyHint
        }

        // Exclude USAF pilot training gliders, Army/Guard Blackhawk & Lakota, commercial airliners,
        // and uncorroborated military hexes (AE*) unless explicitly an LE/DHS/Fire/SAR airframe.
        if (!explicitLeOrCivic) {
            if (COMMERCIAL_AIRLINE_REGEX.containsMatchIn(combined)) return null
            if (EXCLUDED_MILITARY_OR_GLIDER_AIR_REGEX.containsMatchIn(combined)) return null
            if (!hex.isNullOrBlank() && hex.trim().uppercase().startsWith("AE")) return null
            if (hitLabel?.contains("Military", ignoreCase = true) == true) return null
        }

        val circlingFlag = isCircling || (!hitLabel.isNullOrBlank() && CIRCLING_AIR_TOKEN_REGEX.containsMatchIn(combined))
        return when {
            explicitSheriff -> KokohoAgency.SHERIFF
            explicitSar -> KokohoAgency.SAR
            explicitFire -> KokohoAgency.FIRE
            explicitDhs -> KokohoAgency.DHS_AMO
            explicitPolice -> KokohoAgency.POLICE
            combined.contains("GOVERNMENT") -> KokohoAgency.POLICE
            circlingFlag -> {
                val ownerClean = (owner ?: ref?.owner).orEmpty().trim().uppercase()
                val isMaskedOrShell = ownerClean.isEmpty() ||
                    (registration.isNullOrBlank() && ref?.registration.isNullOrBlank()) ||
                    ref?.registrantKind == 'P' ||
                    ownerClean.contains("BLOCKED") ||
                    ownerClean.contains("PRIV") ||
                    ownerClean.contains("LADD") ||
                    ownerClean.contains("PIA") ||
                    SHELL_OR_SURVEILLANCE_AIR_OWNER_REGEX.containsMatchIn(ownerClean) ||
                    SURVEILLANCE_AIRFRAME_MODEL_REGEX.containsMatchIn(combined) ||
                    HELICOPTER_AIRFRAME_REGEX.containsMatchIn(combined) ||
                    ref?.airframeKind == 'H'
                if (isMaskedOrShell) KokohoAgency.DHS_AMO else KokohoAgency.POLICE
            }
            else -> null
        }
    }

    /** Dual-use national calling repeater-output / direct-simplex frequencies (`8CALL90/D`, `7CALL50/D`) that require >= 28 dB SNR to reject weak distant repeaters. */
    private val DUAL_USE_CALLING_FREQS_HZ = longArrayOf(851_012_500L, 769_243_750L)

    fun isDualUseRepeaterSimplexHz(hz: Long): Boolean =
        DUAL_USE_CALLING_FREQS_HZ.any { abs(it - hz) <= 6_250L }

    /**
     * Matches an RF frequency in Hz (and optional P25 NAC) against the curated signature catalog.
     * By default (`includeRepeaters = false`), solidly excludes `REPEATER` downlinks and `CONTROL_CHANNEL` trunks,
     * and enforces `snrDb >= 28` on dual-use repeater/simplex calling channels (`851.0125 MHz`) when `snrDb` is supplied.
     */
    fun matchFrequency(
        hz: Long,
        nacHex: String? = null,
        toleranceHz: Long = 6_250L,
        includeRepeaters: Boolean = false,
        snrDb: Int? = null
    ): RfChannelSig? {
        if (!includeRepeaters && snrDb != null && snrDb < 28 && isDualUseRepeaterSimplexHz(hz)) {
            return null
        }
        val candidates = rfChannels.filter {
            (includeRepeaters || it.isAlertableDirectOrMobile) && abs(it.hz - hz) <= toleranceHz
        }
        if (candidates.isNotEmpty()) {
            if (!nacHex.isNullOrBlank()) {
                val clean = nacHex.trim().removePrefix("$").removePrefix("0x").uppercase()
                candidates.firstOrNull { it.nac?.uppercase() == clean }?.let { return it }
            }
            return candidates.minByOrNull { abs(it.hz - hz) }
        }
        // Fallback to DHS NIFOG & FCC ULS Arizona reference table (`az_fcc_rf.tsv.gz`, ±1.2 kHz VHF 2.5 kHz splinter half-step)
        val azRef = KokohoReferenceTables.lookupAzRf(hz, minOf(toleranceHz, 1_200L)) ?: return null
        return when {
            azRef.cls == "NIFOG_TAC" -> RfChannelSig(
                hz = azRef.freqHz, nac = null, mode = azRef.mode,
                alpha = azRef.callsign, description = azRef.licensee,
                agency = azRef.agency, role = "DIRECT_TACTICAL"
            )
            azRef.cls == "NIFOG_IN" || azRef.cls == "PS_MOB" -> RfChannelSig(
                hz = azRef.freqHz, nac = null, mode = azRef.mode,
                alpha = azRef.callsign, description = azRef.licensee,
                agency = azRef.agency, role = "MOBILE_INPUT"
            )
            includeRepeaters && (azRef.cls == "PS_REP" || azRef.cls == "NIFOG_REP") -> RfChannelSig(
                hz = azRef.freqHz, nac = null, mode = azRef.mode,
                alpha = azRef.callsign, description = azRef.licensee,
                agency = azRef.agency, role = "REPEATER"
            )
            else -> null
        }
    }

    /**
     * Matches close-range Federal / Tactical Interop radio transmissions (`DHS_INTEROP`) when not already
     * matched to a specific agency channel in `rfChannels`:
     *  - NTIA Federal VHF (`162.025-173.9875 MHz`, excluding NOAA Weather `162.400-162.550 MHz` and
     *    Southern Arizona VA hospital paging `167.8625 MHz`) or
     *    NTIA Federal UHF (`411.0125-419.9875 MHz`, excluding `406.1-411.0 MHz` DoD base trunking) with `snrDb >= 36`
     * Never matches known repeaters or control channels in `rfChannels`, and never outranks specific agencies.
     */
    fun matchCloseRangeFederalBand(
        hz: Long,
        snrDb: Int,
        toleranceHz: Long = 6_250L,
        enabledAgencies: Set<KokohoAgency>? = null
    ): RfChannelSig? {
        if (enabledAgencies == null || KokohoAgency.DHS_INTEROP !in enabledAgencies) return null
        // Exclude NOAA Weather Radio (162.400 - 162.550 MHz), VA Medical Center paging (167.8625 MHz),
        // DoD base trunking (406.100 - 411.000 MHz), and FRS consumer walkie-talkies
        if (hz in 162_395_000L..162_555_000L) return null
        if (abs(hz - 167_862_500L) <= toleranceHz) return null
        if (hz in 406_100_000L..411_000_000L) return null
        if (hz in 462_545_000L..462_730_000L || hz in 467_545_000L..467_730_000L) return null
        // If this frequency is already in rfChannels (e.g., as a suppressed REPEATER or CONTROL_CHANNEL), do not synthesize
        val existingCatalog = rfChannels.firstOrNull { abs(it.hz - hz) <= toleranceHz }
        if (existingCatalog != null) {
            return if (existingCatalog.isAlertableDirectOrMobile) existingCatalog else null
        }
        // Suppress any frequency licensed as AZ commercial/business (`IG_COMM`) or fixed repeater (`PS_REP`) in FCC ULS
        val azRef = KokohoReferenceTables.lookupAzRf(hz, minOf(toleranceHz, 1_200L))
        if (azRef != null && azRef.isSuppressedCommercialOrRepeater) return null
        val inNtiaFedBand = (hz in 162_025_000L..173_987_500L) || (hz in 411_012_500L..419_987_500L)
        if (!inNtiaFedBand || snrDb < 36) return null
        val mhzStr = "%.4f".format(java.util.Locale.US, hz / 1_000_000.0)
        return RfChannelSig(
            hz = hz,
            nac = null,
            mode = "P25",
            alpha = "FED-NTIA-$mhzStr",
            description = "Federal Interop Tactical ($mhzStr MHz)",
            agency = "DHS_INTEROP",
            role = "DIRECT_TACTICAL"
        )
    }

    /**
     * Matches close-range (`snrDb >= 30`) public-safety mobile radio uplinks in the
     * 700 MHz (`799-805 MHz`) and 800 MHz rebanded (`806-813.9 MHz`) bands.
     * When the frequency is not explicitly cataloged in `rfChannels`, biases attribution toward
     * [primaryPresenceAgency] if a dominant agency presence is already attested in the colocated
     * environment (e.g. a colocated federal scene) rather than blindly assuming Police/Sheriff.
     */
    fun matchPublicSafetyMobileUplink(
        hz: Long,
        snrDb: Int,
        enabledAgencies: Set<KokohoAgency>? = null,
        primaryPresenceAgency: KokohoAgency? = null
    ): RfChannelSig? {
        if (snrDb < 27) return null
        val inBand = (hz in 799_000_000L..805_000_000L) || (hz in 806_000_000L..813_900_000L)
        if (!inBand) return null
        val exactAny = rfChannels.firstOrNull { abs(it.hz - hz) <= 6_250L }
        if (exactAny != null) {
            return if (exactAny.isAlertableDirectOrMobile) exactAny else null
        }
        val azRef = KokohoReferenceTables.lookupAzRf(hz, 1_200L)
        // Check paired trunked repeater downlink (+45 MHz in 800 MHz rebanded band, -30 MHz in 700 MHz band)
        val pairedDownlinkHz = if (hz >= 806_000_000L) hz + 45_000_000L else hz - 30_000_000L
        val pairedDownlinkRef = KokohoReferenceTables.lookupAzRf(pairedDownlinkHz, 2_500L)
        val hasPublicSafetyTrunkPair = pairedDownlinkRef != null &&
            (pairedDownlinkRef.cls == "PS_REP" || pairedDownlinkRef.cls == "NIFOG_REP" || pairedDownlinkRef.cls == "PS_MOB")
        // Never allow legacy pre-rebanding commercial rows (`IG_COMM`, e.g. WPGY693 at 809.8375 MHz) to veto
        // close-range (`snrDb >= 32`) public-safety mobile uplinks or active trunked mobile inputs!
        if (azRef != null && azRef.isSuppressedCommercialOrRepeater) {
            val isLegacyCommercialOnUplink = azRef.cls == "IG_COMM" && (snrDb >= 32 || hasPublicSafetyTrunkPair)
            if (!isLegacyCommercialOnUplink) {
                return null
            }
        }
        val refAgency = azRef?.takeIf { it.isAlertableMobileOrTactical }?.let { KokohoAgency.fromCode(it.agency) }
            ?: pairedDownlinkRef?.takeIf { hasPublicSafetyTrunkPair }?.let { KokohoAgency.fromCode(it.agency) }
        val targetAgency = when {
            primaryPresenceAgency != null && (enabledAgencies == null || primaryPresenceAgency in enabledAgencies) ->
                primaryPresenceAgency.id
            refAgency != null && (enabledAgencies == null || refAgency in enabledAgencies) ->
                refAgency.id
            enabledAgencies == null -> "POLICE"
            KokohoAgency.POLICE in enabledAgencies -> "POLICE"
            KokohoAgency.SHERIFF in enabledAgencies -> "SHERIFF"
            KokohoAgency.DHS_INTEROP in enabledAgencies -> "DHS_INTEROP"
            else -> "POLICE"
        }
        val mhzStr = "%.4f".format(java.util.Locale.US, hz / 1_000_000.0)
        val bandLabel = if (hz < 806_000_000L) "700" else "800"
        val alpha = azRef?.callsign?.takeIf { it.isNotBlank() && azRef.isAlertableMobileOrTactical } ?: "PS-$bandLabel-IN"
        val desc = if (azRef != null && azRef.isAlertableMobileOrTactical && azRef.licensee.isNotBlank()) {
            "${azRef.licensee} Mobile Uplink ($mhzStr MHz)"
        } else {
            "Public Safety $bandLabel MHz Mobile Uplink ($mhzStr MHz)"
        }
        return RfChannelSig(
            hz = hz,
            nac = null,
            mode = azRef?.mode?.takeIf { it == "P25" || it == "DMR" } ?: "P25",
            alpha = alpha,
            description = desc,
            agency = targetAgency,
            role = "MOBILE_INPUT"
        )
    }

    /**
     * Matches a P25 talkgroup ID against the compiled RadioReference Arizona P25 talkgroup table
     * (`az_talkgroups.tsv.gz`: PCWIN, AZWINS, Pinal, Marana, FCC Tucson, RWC, TRWC) first: suppressing
     * municipal non-LE (`IGNORE`: Public Works, Water, Transit, Schools, Hospitals, ADOT): and falling
     * back to the curated range table when unlisted.
     */
    fun matchTalkgroup(tg: Int, systemHint: String? = null): TalkgroupSig? {
        KokohoReferenceTables.lookupTalkgroup(tg, systemHint)?.let { ref ->
            if (ref.agencyCode == "IGNORE") return null
            val ag = KokohoAgency.fromCode(ref.agencyCode) ?: return null
            val lbl = if (ref.description.isNotBlank() && !ref.description.equals(ref.alphaTag, ignoreCase = true)) {
                "${ref.alphaTag}: ${ref.description}"
            } else {
                ref.alphaTag.ifBlank { "${ref.system} TG $tg" }
            }
            return TalkgroupSig(minTg = tg, maxTg = tg, agency = ag.id, label = lbl)
        }
        return talkgroups.firstOrNull { tg in it.minTg..it.maxTg }
    }

    /**
     * Maximum 802.11 Beacon TSF continuous uptime (16 hours) expected for a mobile patrol router or
     * temporary battery/Pelican pole deployment before classifying as fixed building infrastructure.
     */
    const val MAX_MOBILE_BEACON_UPTIME_MS = 16L * 3600_000L

    private val ROOM_OR_BUILDING_SSID_REGEX = Regex(
        "(?i)^\\s*(\\d{1,4}[A-Z]?|room\\s*\\d+.*|apt\\s*\\d+.*|unit\\s*\\d+A|suite\\s*\\d+.*|guest.*|lobby.*|staff.*)\\s*$"
    )

    private val IGNORED_INFRA_MAC_PREFIXES = listOf(
        "00:30:44:55:", // City of Tucson "rmat" Regional Metropolitan Arterial Traffic signal cabinets
        "06:30:44:55:", // AMR / GMR ambulance ePCR ("GMREPCR") & arterial traffic cabinet virtual APs
        "10:56:CA:D1:", // GoEnergy commercial gas station fixed Peplink POS routers
        "10:56:CA:D4:", // GoEnergy commercial gas station fixed Peplink POS routers
        "10:56:CA:E5:", // GoEnergy commercial gas station fixed Peplink POS routers
        "10:56:CA:F5:", // GoEnergy commercial gas station fixed Peplink POS routers
        "10:56:CA:F6:", // GoEnergy commercial gas station fixed Peplink POS routers
        "00:0D:67:8B:", // Comcast Xfinity aerial strand-mounted Wi-Fi APs ("OutOfService" / "XFINITY")
        "88:4F:59:8E:", // City of Tucson fixed municipal building Cisco APs ("cotwifi" / "cotpublic")
        "88:4F:59:BB:", // City of Tucson fixed municipal building Cisco APs ("cotwifi" / "cotpublic")
        "88:9C:AD:B9:", // City of Tucson fixed municipal building Cisco APs ("cotwifi" / "cotpublic")
        "88:9C:AD:BD:", // City of Tucson fixed municipal building Cisco APs ("cotwifi" / "cotpublic")
        "AC:2A:A1:9D:", // City of Tucson fixed municipal building Cisco APs ("cotpublic" / "cot-iot")
        "04:F0:21:",    // Compex Systems highway light-pole / intersection V2X backhaul modules
        "00:40:29:",    // Compex Systems fixed wireless backhaul modules
        "00:80:48:",    // Compex Incorporated fixed wireless backhaul modules
        "24:B2:B9:"     // Flock Safety / Liteon ALPR cameras (collected in local DB, never shown on maps)
    )

    private val MUNICIPAL_TRAFFIC_OR_TRANSIT_SSID_REGEX = Regex(
        "(?i)^\\s*(rmat|cotwifi|cotpublic|cot-iot|GMREPCR|SunTran.*|SunLink.*|CatTran.*|Veh\\s+\\d{2,4}(?:[-_ ]?(?:2\\.?4|5)g?)?|MimoSeries.*|SUSD-BUS.*|TUSD[-_ ].*|MUSD[-_ ].*|VAIL[-_ ].*|.*-BUS.*)\\s*$"
    )

    /** Fixed indoor branch/failover router SSIDs (NEVER R920/R980/R1900/IBR900/IBR1700, which are ruggedized IN-VEHICLE patrol routers!). */
    private val FIXED_BRANCH_ROUTER_MODEL_SSID_REGEX = Regex(
        "(?i)^\\s*(E10[0-9][A-Z]?-.*|E11[0-9][A-Z]?-.*|E30[0-9][A-Z]?-.*|E3000[A-Z]?-.*|IBR[236][05]0[A-Z]?-.*|CBA[78]50.*|W185[05].*|FX3100.*|FX4100.*|FX4200.*|Verizon-M3100.*|Verizon-MiFi.*|Verizon Jetpack.*|AISMobile.*|MimoSeries.*)\\s*$"
    )

    /** KARR Auto Security (Directed Electronics) aftermarket dealership lot-management/car-alarm BLE modules (`QT <8hex>` / `DR <8hex>`). */
    private val KARR_DEALER_BLE_ALARM_REGEX = Regex("^\\s*(DR|QT)\\s+[0-9A-Fa-f]{8}\\s*$")

    private val IGNORED_INFRA_OR_RETAIL_SSID_REGEX = Regex(
        "(?i)^\\s*(rmat|cotwifi|cotpublic|cot-iot|GMREPCR|SunTran.*|SunLink.*|CatTran.*|Veh\\s+\\d{2,4}(?:[-_ ]?(?:2\\.?4|5)g?)?|MimoSeries.*|Chill N NW|Tap4G.*|RAI-SIGNAGE.*|H&R Block.*|Saladandgo.*|McDonalds.*|McD[-_ ].*|Waystation.*|eBOS|CrystalMT|GoEnergy.*|HCI Mobile.*|MWMBackUp.*|AISMobile.*|Phone|Guys Win|Tucsonmemorials|Club Demonstration.*|TeslaWiFi.*|sscw_Store_WIFI.*|SBXL.*|Aloha_POS.*|Toast_POS.*|sc-RD.*-Maint|TrakkaStream.*|PEPLINK_[0-9A-Fa-f]{4}|E10[0-9][A-Z]?-.*|E11[0-9][A-Z]?-.*|E30[0-9][A-Z]?-.*|E3000[A-Z]?-.*|IBR[236][05]0[A-Z]?-.*|CBA[78]50.*|W185[05].*|FX3100.*|FX4100.*|FX4200.*|Verizon-M3100.*|Verizon-MiFi.*|Verizon Jetpack.*|Young Donut.*|ArmMan WiFi.*|NWAF.*|SUSD-BUS.*|TUSD[-_ ].*|MUSD[-_ ].*|VAIL[-_ ].*|.*-BUS.*|PioneerStore.*|WRANGLERS.*|OutOfService|XFINITY|xfinitywifi|CableWiFi)\\s*$"
    )

    /** Inseego / Novatel 5G FWA & MiFi OUIs widely deployed as Verizon/T-Mobile business broadband gateways. */
    private val INSEEGO_FWA_OR_MIFI_OUIS = setOf(
        "00:15:FF", "18:EE:86", "28:80:A2", "78:0C:71", "E0:86:14"
    )

    /** Peplink / Pepwave OUIs: unmarked HSI/federal fleet deployments hide SSID (`""`), whereas commercial/RV/SOHO units broadcast cleartext SSIDs. */
    private val PEPLINK_OUIS = setOf(
        "D4:13:F8", "10:56:CA", "00:11:6E", "6C:A3:D3", "00:1A:DD", "A8:C0:EA"
    )

    private val FIRE_SSID_REGEX = Regex("(?i)^(TFDCP.*|KNOX|KNOX[-_ ]?(BOX|MED|WIFI|EKEY|KEY).*|\\s*(TFD_|TFD\\d+|TUCSON_?FIRE|NWFD.*|GRFD.*|GVFD.*|AVFD.*|OVFD.*|RMFD.*|PCFD.*|SVFD.*|RVFD.*|MTFD.*|PTFD.*))$")
    private val MOBILE_VEHICLE_ROUTER_SSID_REGEX = Regex("(?i)^\\s*(R1900|R920|R980|IBR900|IBR1700)-[0-9A-Za-z_-]+\\s*$")
    private val DEFAULT_ROUTER_MODEL_SSID_REGEX = Regex("(?i)^\\s*(IBR900|IBR1700|R920|R980|R1900)-[0-9A-Za-z_-]+\\s*$")
    private val FLEET_UNIT_SSID_REGEX = Regex("(?i)^\\s*(Unit|Car|SQD|K9|TAHOE|RAM|SUP)[\\s_-]*\\d+.*$")
    private val MULTI_RADIO_ROUTER_OUIs = setOf(
        "00:30:44", "00:E0:1C",                                     // Cradlepoint / Ericsson Enterprise Wireless
        "D4:13:F8", "10:56:CA", "00:11:6E", "6C:A3:D3", "00:1A:DD", "A8:C0:EA", // Peplink / Pepwave
        "00:14:3E", "00:A0:D5", "28:A3:31", "50:13:9D", "64:CE:6E", // Sierra Wireless AirLink
        "84:DB:2F", "CC:93:4A", "00:16:C0",                         // Sierra Wireless / Semtech
        "00:15:FF", "18:EE:86", "28:80:A2", "78:0C:71", "E0:86:14", // Inseego / Novatel MiFi & Vehicle
        "00:40:9D", "00:1E:42", "00:18:0A", "E4:3E:D7"              // Digi International / Teltonika / Meraki
    )
    private val GENERIC_FLEET_ROUTER_OUIs = MULTI_RADIO_ROUTER_OUIs
    private val EXPLICIT_FEDERAL_SSID_REGEX = Regex("(?i)\\b(CBP|BP[_-]?\\d*|USBP|ICE|ERO|HSI|FPS|DHS|FED)\\b")

    /** Confidently field-attested NIC sub-blocks (> 24-bit) derived from multi-session Tucson telemetry. */
    private val ATTESTED_AGENCY_SUBBLOCKS = listOf(
        "00:30:44:74:",
        "00:30:44:6B:5F:",
        "9C:05:D6:37:6D:",
        "D8:B3:70:69:07:",
        "8C:1F:64:DF:0",
        "00:30:44:22:8",
        "00:30:44:22:9",
        "00:30:44:A2:E8:",
        "00:30:44:1D:46:",
        "40:ED:98:3",
        "D4:13:F8:52:4B:",
        "D4:13:F8:86:A2:",
        "D4:13:F8:61:29:",
        "D4:13:F8:0A:41:",
        "E4:55:A8:05:D2:",
        "E4:55:A8:05:F6:",
        "E2:55:A8:05:D2:",
        "E2:55:A8:05:F6:",
        "E4:55:A8:51:EF:",
        "E4:55:A8:52:2C:",
        "E6:55:B8:52:2C:",
        "F6:55:A8:52:2C:",
        "F6:55:B8:52:2C:",
        "00:30:44:B3:E7:",
        "00:30:44:7B:F7:",
        "00:30:44:9C:36:"
    )

    fun isAttestedAgencySubblock(mac: String): Boolean {
        val rawNorm = normalizeMac(mac)
        val canon = canonicalHardwareOuiMac(rawNorm)
        return ATTESTED_AGENCY_SUBBLOCKS.any { rawNorm.startsWith(it) || canon.startsWith(it) }
    }

    fun isMobileVehicleRouterModelSsid(ssid: String?): Boolean =
        !ssid.isNullOrBlank() && MOBILE_VEHICLE_ROUTER_SSID_REGEX.matches(ssid.trim())

    /**
     * Maps locally-administered virtual BSSIDs (e.g. `06:30:44:74:AB:83` `PCSD-Vehicle` or
     * `D6:13:F8:7F:C6:48` `Guys Win`) back to their physical hardware OUI (`00:30:44` or `D4:13:F8`)
     * so secondary/5GHz/guest VAPs group with their primary physical router radio.
     */
    fun canonicalHardwareOuiMac(raw: String): String {
        val norm = normalizeMac(raw)
        if (norm.length != 17) return norm
        val tail = norm.substring(2) // ":BB:CC:DD:EE:FF"
        val prefix8 = norm.take(8)
        return when (prefix8) {
            "02:30:44", "06:30:44", "0A:30:44", "0E:30:44" -> "00$tail"
            "D2:13:F8", "D6:13:F8" -> "D4$tail"
            "12:56:CA", "16:56:CA" -> "10$tail"
            "02:11:6E", "06:11:6E" -> "00$tail"
            "6E:A3:D3", "6A:A3:D3" -> "6C$tail"
            "02:14:3E", "06:14:3E" -> "00$tail"
            "02:A0:D5", "06:A0:D5" -> "00$tail"
            "02:18:0A", "06:18:0A" -> "00$tail"
            "02:1E:42", "06:1E:42" -> "00$tail"
            "02:40:9D", "06:40:9D" -> "00$tail"
            "E2:3E:D7", "E6:3E:D7" -> "E4$tail"
            "02:E0:1C", "06:E0:1C" -> "00$tail"
            else -> norm
        }
    }

    /**
     * Returns true when [ssid] explicitly identifies a Law Enforcement, Sheriff, Fire, Federal, or Fleet Unit
     * vehicle/tactical asset (which always overrides generic router suppression).
     */
    fun isExplicitAgencyOrTacticalSsid(ssid: String?): Boolean {
        val clean = ssid?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return EXPLICIT_FEDERAL_SSID_REGEX.containsMatchIn(clean) ||
            LAW_ENFORCEMENT_SSID_REGEX.containsMatchIn(clean) ||
            SHERIFF_SSID_REGEX.containsMatchIn(clean) ||
            FIRE_SSID_REGEX.matches(clean) ||
            FLEET_UNIT_SSID_REGEX.matches(clean)
    }

    /**
     * Returns true when 802.11 beacon capabilities advertise an unencrypted Open `[ESS]` network
     * (typical of commercial guest portals like `Guys Win`, municipal `cotwifi`, etc., and never used on tactical vehicle routers).
     */
    fun isOpenPublicWifiCapabilities(capabilities: String?): Boolean {
        val cap = capabilities?.uppercase().orEmpty()
        if (!cap.contains("[ESS]")) return false
        return !cap.contains("WPA") && !cap.contains("RSN") && !cap.contains("WEP") && !cap.contains("SAE") && !cap.contains("OWE")
    }

    fun isMunicipalTrafficCabinetOrTransit(mac: String, ssidOrName: String?): Boolean {
        val normMac = normalizeMac(mac)
        if (IGNORED_INFRA_MAC_PREFIXES.any { normMac.startsWith(it) }) return true
        val ssid = ssidOrName?.trim().orEmpty()
        return ssid.isNotEmpty() && MUNICIPAL_TRAFFIC_OR_TRANSIT_SSID_REGEX.matches(ssid)
    }

    fun isFixedBranchRouterModelSsid(ssidOrName: String?): Boolean {
        val ssid = ssidOrName?.trim().orEmpty()
        return ssid.isNotEmpty() && FIXED_BRANCH_ROUTER_MODEL_SSID_REGEX.matches(ssid)
    }

    /**
     * Principled multi-BSSID hardware-group filter derived from field telemetry (`known_devices` + `device_details`):
     * Evaluates all sibling VAPs belonging to the same physical router (`routerRadioGroupKey(mac)`) together.
     * - If ANY sibling broadcasts an explicit agency/tactical SSID (e.g. `06:30:44:74:AB:83` `PCSD-Vehicle` alongside
     *   `00:30:44:74:AB:83` `IBR1700-b82`), returns `false` (never suppress).
     * - Otherwise returns `true` (suppress from threats/alerts while still collecting in DB) if ANY sibling exhibits:
     *   1. Municipal traffic cabinet / transit / fixed branch router model / retail SSID (`isIgnoredInfrastructureOrRetail`)
     *   2. Continuous 802.11 Beacon TSF uptime > 48 hours (`MAX_MOBILE_BEACON_UPTIME_MS`)
     *   3. Unencrypted Open `[ESS]` public/guest Wi-Fi VAP (`isOpenPublicWifiCapabilities`)
     *   4. Multi-session stationary router familiarity (`isStaticBaselineRouter`)
     */
    fun isFixedRoomOrBuildingWifiAp(snap: org.tucsoncommunity.kokoho.service.DeviceRegistry.Snapshot): Boolean {
        val wifi = snap.advert?.wifi ?: return false
        if (wifi.client) return false
        val ssid = snap.name?.trim().orEmpty()
        if (isExplicitAgencyOrTacticalSsid(ssid)) return false
        val upSince = snap.upSince ?: wifi.upSince
        if (upSince != null && snap.lastSeen > upSince && (snap.lastSeen - upSince) > MAX_MOBILE_BEACON_UPTIME_MS) {
            return true
        }
        val capUp = wifi.capabilities.uppercase()
        if (capUp.contains("[ESS]")) {
            if (ssid.isNotEmpty() && isOpenPublicWifiCapabilities(capUp)) return true
            if (capUp.contains("TKIP")) return true
            if (ssid.isNotEmpty() && ROOM_OR_BUILDING_SSID_REGEX.matches(ssid)) return true
            val normMac = normalizeMac(snap.mac)
            if (CITATION_PRINTER_OUIs.any { normMac.startsWith(it) } &&
                !ssid.startsWith("DIRECT-", ignoreCase = true) &&
                !ZEBRA_PRINTER_REGEX.containsMatchIn(ssid)
            ) {
                return true
            }
        }
        return false
    }

    fun isStationaryOrCommercialRouterGroup(
        groupSnapshots: List<org.tucsoncommunity.kokoho.service.DeviceRegistry.Snapshot>,
        context: Context? = null
    ): Boolean {
        if (groupSnapshots.isEmpty()) return false
        if (groupSnapshots.any { isExplicitAgencyOrTacticalSsid(it.name) }) return false
        for (s in groupSnapshots) {
            if (isIgnoredInfrastructureOrRetail(s.mac, s.name)) return true
            if (isFixedRoomOrBuildingWifiAp(s)) return true
            val upSince = s.upSince ?: s.advert?.wifi?.upSince
            if (upSince != null && s.lastSeen > upSince && (s.lastSeen - upSince) > MAX_MOBILE_BEACON_UPTIME_MS) {
                return true
            }
            val wifi = s.advert?.wifi
            if (wifi != null && !wifi.client && (
                (!s.name.isNullOrBlank() && isOpenPublicWifiCapabilities(wifi.capabilities)) ||
                    org.tucsoncommunity.kokoho.detect.FixedRouter.isBranchDefaultOrOpenRouter(s.name, wifi.capabilities)
                )
            ) {
                return true
            }
            val pos = s.lastPosition ?: s.place
            if (isStaticBaselineRouter(context, s.mac, pos?.lat, pos?.lon)) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true when [mac] matches a multi-agency cellular router OUI (Cradlepoint/Sierra/Peplink/Digi/Meraki)
     * without an explicit federal/agency SSID or explicit field-attested agency NIC sub-block (e.g. `00:30:44:74` PCSD-Vehicle).
     */
    fun isGenericUnmarkedFleetRouter(mac: String, ssid: String?): Boolean {
        if (isAttestedAgencySubblock(mac)) return false
        val canon = canonicalHardwareOuiMac(mac)
        val prefix = canon.take(8)
        if (prefix !in GENERIC_FLEET_ROUTER_OUIs) return false
        val clean = ssid?.trim().orEmpty()
        if (clean.isEmpty()) return true
        if (EXPLICIT_FEDERAL_SSID_REGEX.containsMatchIn(clean)) return false
        if (LAW_ENFORCEMENT_SSID_REGEX.containsMatchIn(clean) ||
            SHERIFF_SSID_REGEX.containsMatchIn(clean) ||
            FIRE_SSID_REGEX.matches(clean)
        ) {
            return false
        }
        return true
    }

    /** Canonical upper-case colon-separated MAC (`AA:BB:CC:DD:EE:FF`) when given 12 hex digits. */
    fun normalizeMac(raw: String): String {
        val hex = raw.trim().uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else raw.trim().uppercase()
    }

    /**
     * Collapses dual-band and locally-administered virtual BSSIDs belonging to the same physical fleet router radio
     * (e.g., `00:30:44:74:AB:83` + `06:30:44:74:AB:83`, or `D4:13:F8:7F:C6:49` + `D6:13:F8:7F:C6:48`) into one
     * 8-BSSID aligned hardware radio group (`AA:BB:CC:DD:EE:X0/8`).
     */
    fun routerRadioGroupKey(mac: String): String {
        val norm = canonicalHardwareOuiMac(mac)
        val parts = norm.split(':')
        if (parts.size == 6 && parts.take(3).joinToString(":") in MULTI_RADIO_ROUTER_OUIs) {
            val last = parts[5].toIntOrNull(16)
            if (last != null) {
                val base = (last and 0xF8).toString(16).uppercase().padStart(2, '0')
                return "${parts.take(5).joinToString(":")}:$base"
            }
        }
        return norm
    }

    fun isLowConfidenceDefaultRouterSsid(ssid: String?): Boolean =
        !ssid.isNullOrBlank() && DEFAULT_ROUTER_MODEL_SSID_REGEX.matches(ssid.trim())

    fun isHighValueUnitSsid(ssid: String?): Boolean =
        !ssid.isNullOrBlank() && FLEET_UNIT_SSID_REGEX.matches(ssid.trim())

    /**
     * Returns true if a device/entity is a Flock / ALPR camera (which may be collected in the
     * local database to improve third-party services, but must never appear on any map).
     */
    fun isFlockCamera(macOrKey: String?, labelOrName: String?): Boolean {
        val m = macOrKey?.trim()?.uppercase().orEmpty()
        if (m.startsWith("24:B2:B9") || m.contains("24:B2:B9")) return true
        val txt = labelOrName?.trim().orEmpty()
        if (txt.contains("Flock", ignoreCase = true) ||
            txt.contains("ALPR", ignoreCase = true) ||
            txt.contains("LPR Optical", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private val DJI_ALL_OUIS = setOf(
        "20:1F:55", "60:60:1F", "34:D2:62", "48:1C:B9", "E4:7A:2C", "58:B8:58", "04:A8:5A",
        "8C:58:23", "0C:9A:E6", "88:29:85", "4C:43:F6", "9C:5A:8A", "EC:72:F7", "34:91:F0"
    )

    private val DJI_CONSUMER_NON_ENTERPRISE_24BIT_OUIS = setOf(
        "20:1F:55", "0C:9A:E6", "8C:58:23", "E4:7A:2C", "88:29:85", "58:B8:58", "4C:43:F6"
    )

    private val DJI_CONSUMER_HANDHELD_NAME_REGEX = Regex(
        "(?i)(?:^|\\b)(?:dji[-_ ]?)?(?:osmo(?:[-_ ]?(?:action|pocket|mobile|nano|360|\\d)\\w*|\\b)|om[-_ ]?(?:\\d|se)\\b\\w*|rs[-_ ]?\\d\\w*|ronin\\w*|dji[-_ ]?mic\\w*|mic[-_ ]?(?:mini|[123])\\w*)(?:\\b|$)"
    )

    private val DJI_SHORT_MIC_NAME_REGEX = Regex("(?i)^mic(?:[-_ ]|[0-9]|$)")

    /**
     * Returns true when a MAC / SSID / BLE name belongs to DJI consumer handheld cameras, gimbals,
     * or wireless microphones (`OsmoAction*`, `OsmoPocket*`, `OsmoMobile*`, `OM *`, `RS *`, `Ronin*`,
     * `DJI Mic*`, or DJI Osmo Technology OUI `20:1F:55`).
     */
    fun isDjiConsumerHandheld(mac: String?, ssidOrName: String?, vendor: String? = null): Boolean {
        val rawNorm = mac?.let { normalizeMac(it) }.orEmpty()
        val canonNorm = if (rawNorm.isNotEmpty()) canonicalHardwareOuiMac(rawNorm) else ""
        if (rawNorm.startsWith("20:1F:55") || canonNorm.startsWith("20:1F:55")) return true
        val vend = vendor?.trim().orEmpty()
        if (vend.contains("DJI Osmo", ignoreCase = true)) return true
        val name = ssidOrName?.trim().orEmpty()
        if (name.isEmpty()) return false
        if (DJI_CONSUMER_HANDHELD_NAME_REGEX.containsMatchIn(name)) return true
        val isDjiOui = (rawNorm.length >= 8 && rawNorm.take(8) in DJI_ALL_OUIS) ||
            (canonNorm.length >= 8 && canonNorm.take(8) in DJI_ALL_OUIS) ||
            vend.contains("DJI", ignoreCase = true)
        if (isDjiOui && DJI_SHORT_MIC_NAME_REGEX.containsMatchIn(name)) return true
        return false
    }

    /**
     * Returns true if a MAC / SSID belongs to fixed non-Flock highway/traffic infrastructure,
     * Compex light-pole backhaul (`MimoSeries*`), Axon Networks consumer ISP home routers (`00:58:28`),
     * fixed Cradlepoint/Inseego branch routers (`IBR600C-*`, `E100-*`), DJI consumer handhelds, or known retail POS backup Wi-Fi.
     */
    fun isNonFlockIgnoredInfraOrRetail(mac: String, ssidOrName: String?, vendor: String? = null): Boolean {
        val effectiveName = ssidOrName?.trim()?.takeIf { it.isNotEmpty() }
            ?: org.tucsoncommunity.kokoho.service.DeviceRegistry.knownNameOf(mac)
        if (isExplicitAgencyOrTacticalSsid(effectiveName)) return false
        if (isDjiConsumerHandheld(mac, effectiveName, vendor)) return true
        if (isAxonNetworksConsumerRouter(mac, effectiveName, vendor)) return true
        val normMac = normalizeMac(mac)
        if (IGNORED_INFRA_MAC_PREFIXES.any { it != "24:B2:B9:" && normMac.startsWith(it) }) return true
        val ssid = effectiveName?.trim().orEmpty()
        if (isAttestedAgencySubblock(normMac) && ssid.startsWith("PEPLINK_", ignoreCase = true)) {
            return false
        }
        if (ssid.isNotEmpty() && (KARR_DEALER_BLE_ALARM_REGEX.matches(ssid) || IGNORED_INFRA_OR_RETAIL_SSID_REGEX.matches(ssid))) return true
        // Custom-named Inseego 5G FWA business gateways (e.g. "CrystalMT") are commercial internet modems unless explicitly tactical/fleet-named.
        val canonPrefix = canonicalHardwareOuiMac(normMac).take(8)
        if (canonPrefix in INSEEGO_FWA_OR_MIFI_OUIS &&
            ssid.isNotEmpty() &&
            !isMobileVehicleRouterModelSsid(ssid)
        ) {
            return true
        }
        // Commercial/RV/SOHO/utility Peplink routers with cleartext non-tactical SSIDs (e.g. "TrakkaStream", "sc-RD3147-Maint", "PEPLINK_3BE0")
        // are suppressed unless on an attested agency subblock or broadcasting an explicit tactical/mobile-router SSID.
        if (canonPrefix in PEPLINK_OUIS &&
            ssid.isNotEmpty() &&
            !isAttestedAgencySubblock(normMac) &&
            !isMobileVehicleRouterModelSsid(ssid)
        ) {
            return true
        }
        return false
    }

    /**
     * Returns true if a MAC / SSID belongs to a known municipal traffic-signal cabinet (`"rmat"`),
     * intersection V2X bridge, ambulance ePCR, Flock camera, DJI consumer handheld, or fixed commercial/retail POS backup router.
     */
    fun isIgnoredInfrastructureOrRetail(mac: String, ssidOrName: String?): Boolean {
        if (isFlockCamera(mac, ssidOrName)) return true
        return isNonFlockIgnoredInfraOrRetail(mac, ssidOrName)
    }

    /** Matches a Wi-Fi / BLE MAC address against curated fleet router / tactical / drone OUI prefixes & IEEE 28/36-bit sub-blocks. */
    fun matchOui(mac: String, ssidOrName: String? = null): FleetOuiSig? {
        if (isIgnoredInfrastructureOrRetail(mac, ssidOrName)) return null
        val rawNorm = normalizeMac(mac)
        val norm = canonicalHardwareOuiMac(rawNorm)
        // Check explicit curated >24-bit sub-blocks first (longest-prefix match) so tactical 28/36/40-bit blocks
        // (e.g. Grayshift 70:B3:D5:71:F, Cyberkar 8C:1F:64:DF:0, HSI Meraki E2:55:A8:05:D2) are never vetoed by shared 24-bit roots!
        val specificCurated = fleetOuis
            .filter { it.prefix.length > 8 && (rawNorm.startsWith(it.prefix.uppercase()) || norm.startsWith(it.prefix.uppercase())) }
            .maxByOrNull { it.prefix.length }
        if (specificCurated != null) return specificCurated

        val sub = KokohoReferenceTables.lookupMacSubblock(norm)
        // Exclude non-tactical IEEE 28/36-bit sub-blocks sharing a 24-bit IEEE root (e.g. 70:B3:D5 / 8C:1F:64 / 00:50:C2)
        if (sub != null && (sub.role == "CIVIL_SUBBLOCK" || sub.role == "CONSUMER_ISP" || sub.role == "INFRA_BACKHAUL" || sub.role == "FLOCK_ALPR")) {
            return null
        }
        fleetOuis
            .filter { rawNorm.startsWith(it.prefix.uppercase()) || norm.startsWith(it.prefix.uppercase()) }
            .maxByOrNull { it.prefix.length }
            ?.let { return it }
        if (sub != null && sub.agency != null && sub.role != "BODY_CAM" && sub.role != "LMR_RADIO") {
            // Consumer DJI 24-bit blocks (0C:9A:E6, 8C:58:23, etc. used by Osmo Action/Pocket/Mobile/Mic)
            // must NEVER match as Tactical Drones on bare OUI before SSID decoding.
            if (sub.role == "DRONE" && norm.length >= 8 && norm.take(8) in DJI_CONSUMER_NON_ENTERPRISE_24BIT_OUIS) {
                return null
            }
            return FleetOuiSig(
                prefix = sub.prefixHex.chunked(2).joinToString(":"),
                agency = sub.agency.id,
                label = sub.vendor,
                confidence = if (sub.bitLen >= 28) 88 else 78
            )
        }
        return null
    }

    private val TACTICAL_DRONE_OUIs = setOf(
        "38:1D:14",                                     // Skydio (X2 / X10 Tactical)
        "D8:80:39", "B0:30:C8",                         // Teal Drones / Red Cat (Golden Eagle sUAS)
        "90:03:B7", "A0:14:3D", "00:12:1C", "00:26:7E", "90:3A:E6", // Parrot (Anafi USA / Gov)
        "00:1A:F9", "8C:1F:64:B0:7",                    // AeroVironment (Puma / Raven)
        "14:DD:48",                                     // Shield AI (Nova / V-BAT)
        "E8:B4:70:C",                                   // Anduril Industries (Ghost / Bolt)
        "AC:86:D1:7",                                   // Quantum Systems (Vector / Trinity)
        "34:B5:F3:2",                                   // Inspired Flight (IF800 / IF1200)
        "EC:71:5E",                                     // Freefly Systems (Astro)
        "04:71:4B:1", "54:6F:71",                       // uAvionix (UAS Remote ID / Avionics)
        "EC:5B:73", "18:D7:93",                         // Autel Robotics (EVO II Dual Enterprise)
        "60:60:1F", "48:1C:B9", "34:D2:62", "04:A8:5A", // DJI Enterprise (Matrice / Mavic Enterprise)
        "26:37:12",                                     // DJI DroneID Wi-Fi Beacon OUI
        "FA:0B:BC"                                      // ASTM F3411 Wi-Fi Beacon / NAN Remote ID
    )

    private val TACTICAL_DRONE_TOKEN_REGEX =
        Regex("(?i)\\b(SKYDIO|ANAFI|BRINC|LEMUR|TEAL|AUTEL|MATRICE|MAVIC\\s*3E|MAVIC\\s*3T|AEROVIRONMENT|SHIELD\\s*AI|ANDURIL|FREEFLY|INSPIRED\\s*FLIGHT|REMOTE\\s*ID)\\b")

    /** Returns true if a device is a Pro / Enterprise / Military / Remote ID drone. */
    fun isTacticalOrRemoteIdDrone(
        mac: String,
        ssidOrName: String?,
        vendor: String?,
        hasRemoteId: Boolean
    ): Boolean {
        val effectiveName = ssidOrName?.trim()?.takeIf { it.isNotEmpty() }
            ?: org.tucsoncommunity.kokoho.service.DeviceRegistry.knownNameOf(mac)
        if (!hasRemoteId && isDjiConsumerHandheld(mac, effectiveName, vendor)) return false
        if (hasRemoteId) return true
        val norm = normalizeMac(mac)
        if (TACTICAL_DRONE_OUIs.any { norm.startsWith(it) }) return true
        val combined = "${effectiveName.orEmpty()} ${vendor.orEmpty()}"
        return TACTICAL_DRONE_TOKEN_REGEX.containsMatchIn(combined)
    }

    private val LAW_ENFORCEMENT_SSID_REGEX =
        Regex("(?i)\\b(TUCSON_?PD|TPD_?[A-Z0-9]*|AZDPS|DPS_?PATROL|TROOPER|STATE_?PATROL|ANTI_?GANG|SWAT|NARCOTICS|OVPD|ORO_?VALLEY_?PD|MARANA_?PD|SAHUARITA_?PD|UAPD)\\b")
    private val SHERIFF_SSID_REGEX =
        Regex("(?i)\\b(PCSO|PCSD|PIMA_?SHERIFF|SHERIFF_?TAC|SHERIFF)\\b")

    /** Matches a Wi-Fi / BLE MAC address or SSID/vendor against curated fleet router / tactical / drone signatures. */
    fun matchOuiOrSsid(
        mac: String,
        ssid: String?,
        vendor: String?,
        hasRemoteId: Boolean = false
    ): Pair<KokohoAgency, String>? {
        if (isIgnoredInfrastructureOrRetail(mac, ssid)) return null
        if (isTacticalOrRemoteIdDrone(mac, ssid, vendor, hasRemoteId)) {
            return KokohoAgency.DRONE to KokohoAgency.DRONE.friendlyLabel
        }
        val cleanSsid = ssid?.trim().orEmpty()
        if (cleanSsid.isNotEmpty()) {
            if (FIRE_SSID_REGEX.matches(cleanSsid)) {
                return KokohoAgency.FIRE to KokohoAgency.FIRE.friendlyLabel
            }
            if (LAW_ENFORCEMENT_SSID_REGEX.containsMatchIn(cleanSsid)) {
                return KokohoAgency.POLICE to KokohoAgency.POLICE.friendlyLabel
            }
            if (SHERIFF_SSID_REGEX.containsMatchIn(cleanSsid)) {
                return KokohoAgency.SHERIFF to KokohoAgency.SHERIFF.friendlyLabel
            }
        }
        matchOui(mac, ssid)?.let { return it.agencyEnum to it.friendlyLabel }
        val combined = "$cleanSsid ${vendor.orEmpty()}"
        if (combined.contains("Cradlepoint", ignoreCase = true) ||
            combined.contains("IBR900", ignoreCase = true) ||
            combined.contains("IBR1700", ignoreCase = true)
        ) {
            return KokohoAgency.DHS_CBP to KokohoAgency.DHS_CBP.friendlyLabel
        }
        if (combined.contains("Sierra Wireless", ignoreCase = true) ||
            combined.contains("AirLink", ignoreCase = true)
        ) {
            return KokohoAgency.DHS_CBP to KokohoAgency.DHS_CBP.friendlyLabel
        }
        if (combined.contains("Peplink", ignoreCase = true) && (cleanSsid.isEmpty() || isAttestedAgencySubblock(mac))) {
            return KokohoAgency.DHS_HSI to KokohoAgency.DHS_HSI.friendlyLabel
        }
        return null
    }

    data class LearnedMacIntel(
        val mac: String,
        val ouiPrefix: String,
        val nicU24: Long,
        val agencyId: String,
        val label: String,
        val reputationScore: Double,
        val corroboratedHits: Int,
        val totalHits: Int,
        val distinctDays: Int = 1,
        val distinctH3Res10: Int = 1,
        val associationPattern: String = "TRANSIENT",
        val capabilities: List<String> = emptyList(),
        val ssidHint: String = "",
        val updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agencyId) ?: KokohoAgency.DHS_CBP
    }

    data class LearnedMacBlock(
        val ouiPrefix: String,
        val startMac: String,
        val endMac: String,
        val minNicU24: Long,
        val maxNicU24: Long,
        val agencyId: String,
        val label: String,
        val confirmedCount: Int,
        val confidenceBoost: Double
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agencyId) ?: KokohoAgency.DHS_CBP
    }

    private const val KEY_LEARNED_MACS_JSON = "learned_macs_v2"
    private const val KEY_LEARNED_BLOCKS_JSON = "learned_blocks_v2"
    private val learnedMacCache = java.util.concurrent.ConcurrentHashMap<String, LearnedMacIntel>()
    @Volatile private var learnedBlockCache: List<LearnedMacBlock> = emptyList()
    @Volatile private var learnedIntelLoaded = false

    fun parseMacOuiAndNic(rawMacOrFacet: String): Triple<String, Long, String>? {
        val cleaned = rawMacOrFacet.trim().removePrefix("hw:mac:").uppercase()
        val hex = cleaned.filter { it in '0'..'9' || it in 'A'..'F' }
        if (hex.length != 12) return null
        val oui = "${hex.substring(0, 2)}:${hex.substring(2, 4)}:${hex.substring(4, 6)}"
        val nic = hex.substring(6, 12).toLongOrNull(16) ?: return null
        val canonical = hex.chunked(2).joinToString(":")
        return Triple(oui, nic, canonical)
    }

    private fun ensureLearnedIntelLoaded(context: Context?) {
        if (learnedIntelLoaded || context == null) return
        synchronized(this) {
            if (learnedIntelLoaded) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains("learned_macs_v1") || prefs.contains("learned_blocks_v1")) {
                prefs.edit {
                    remove("learned_macs_v1")
                    remove("learned_blocks_v1")
                }
            }
            runCatching {
                val mRaw = prefs.getString(KEY_LEARNED_MACS_JSON, null)
                if (!mRaw.isNullOrBlank()) {
                    val mType = object : TypeToken<List<LearnedMacIntel>>() {}.type
                    val list: List<LearnedMacIntel> = Gson().fromJson(mRaw, mType) ?: emptyList()
                    for (m in list) {
                        val upMac = m.mac.uppercase()
                        if (upMac.startsWith("40:84:32:") ||
                            upMac.startsWith("24:79:2A:") ||
                            upMac.startsWith("AC:82:47:") ||
                            upMac.startsWith("C8:B3:73:") ||
                            upMac.startsWith("00:11:AB:") ||
                            upMac.startsWith("D8:12:65:") ||
                            upMac.startsWith("60:E3:27:") ||
                            upMac.startsWith("18:EE:86:41:88:") ||
                            isIgnoredInfrastructureOrRetail(upMac, m.ssidHint) ||
                            KARR_DEALER_BLE_ALARM_REGEX.containsMatchIn(m.label) ||
                            KARR_DEALER_BLE_ALARM_REGEX.matches(m.ssidHint)
                        ) {
                            continue
                        }
                        val fixed = when {
                            (upMac.startsWith("00:30:44:74:") || upMac.startsWith("06:30:44:74:")) && m.agencyId != "SHERIFF" ->
                                m.copy(agencyId = "SHERIFF", label = "Pima County Sheriff: PCSD-Vehicle (IBR1700)")
                            upMac.startsWith("00:30:44:22:8") && m.agencyId != "FIRE" ->
                                m.copy(agencyId = "FIRE", label = "Fire")
                            else -> m
                        }
                        learnedMacCache[upMac] = fixed
                    }
                }
                val bRaw = prefs.getString(KEY_LEARNED_BLOCKS_JSON, null)
                if (!bRaw.isNullOrBlank()) {
                    val bType = object : TypeToken<List<LearnedMacBlock>>() {}.type
                    learnedBlockCache = Gson().fromJson(bRaw, bType) ?: emptyList()
                }
            }
            learnedIntelLoaded = true
        }
    }

    private fun persistLearnedIntel(context: Context) {
        val macs = learnedMacCache.values.sortedByDescending { it.updatedAtMs }.take(250)
        val blocks = learnedBlockCache.take(60)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LEARNED_MACS_JSON, Gson().toJson(macs))
            putString(KEY_LEARNED_BLOCKS_JSON, Gson().toJson(blocks))
        }
    }

    private val NON_BLOCK_LEARNING_OUIs = setOf(
        "9C:05:D6", "D8:B3:70", "00:58:28", "04:F0:21", "00:40:29", "00:80:48", "24:B2:B9", "40:84:32"
    )

    fun mergeRemoteMacIntel(
        context: Context,
        macs: List<LearnedMacIntel>,
        blocks: List<LearnedMacBlock>
    ) {
        ensureLearnedIntelLoaded(context)
        var changed = false
        for (m in macs) {
            val k = normalizeMac(m.mac)
            if (k.take(8) in NON_BLOCK_LEARNING_OUIs ||
                k.startsWith("18:EE:86:41:88:") ||
                isIgnoredInfrastructureOrRetail(k, m.ssidHint)
            ) continue
            val prev = learnedMacCache[k]
            if (prev == null || m.updatedAtMs >= prev.updatedAtMs || m.corroboratedHits >= prev.corroboratedHits) {
                learnedMacCache[k] = m.copy(mac = k)
                changed = true
            }
        }
        val validBlocks = blocks.filter { it.ouiPrefix.uppercase() !in NON_BLOCK_LEARNING_OUIs && it.confirmedCount >= 2 }
        if (validBlocks.isNotEmpty() || blocks.isEmpty()) {
            learnedBlockCache = validBlocks
            changed = true
        }
        if (changed) persistLearnedIntel(context)
    }

    fun recordLocalMacCorroboration(
        context: Context?,
        mac: String,
        agency: KokohoAgency,
        label: String,
        isCorroborated: Boolean,
        capabilities: List<String> = emptyList(),
        ssidHint: String = "",
        nowMs: Long = System.currentTimeMillis()
    ) {
        ensureLearnedIntelLoaded(context)
        val (oui, nic, canonical) = parseMacOuiAndNic(mac) ?: return
        if (oui in NON_BLOCK_LEARNING_OUIs ||
            canonical.startsWith("18:EE:86:41:88:") ||
            isIgnoredInfrastructureOrRetail(canonical, ssidHint)
        ) return
        val prev = learnedMacCache[canonical]
        val mergedCaps = ((prev?.capabilities.orEmpty()) + capabilities).distinct()
        val delta = when {
            isCorroborated -> 0.14
            isHighValueUnitSsid(ssidHint) -> 0.08
            else -> 0.02
        }
        val nextScore = if (prev == null) {
            if (isCorroborated) 0.80 else 0.45
        } else {
            (prev.reputationScore + delta).coerceIn(0.15, 0.99)
        }
        val preferNew = prev == null || isCorroborated && (agency.priorityRank < prev.agencyEnum.priorityRank || prev.corroboratedHits == 0)
        val nextDays = if (prev == null) {
            1
        } else if ((nowMs / 86_400_000L) != (prev.updatedAtMs / 86_400_000L)) {
            prev.distinctDays + 1
        } else {
            prev.distinctDays
        }
        learnedMacCache[canonical] = LearnedMacIntel(
            mac = canonical,
            ouiPrefix = oui,
            nicU24 = nic,
            agencyId = if (preferNew) agency.id else prev!!.agencyId,
            label = if (preferNew && label.isNotBlank()) label else prev?.label.orEmpty().ifBlank { agency.friendlyLabel },
            reputationScore = nextScore,
            corroboratedHits = (prev?.corroboratedHits ?: 0) + if (isCorroborated) 1 else 0,
            totalHits = (prev?.totalHits ?: 0) + 1,
            distinctDays = nextDays,
            distinctH3Res10 = prev?.distinctH3Res10 ?: 1,
            associationPattern = prev?.associationPattern ?: "TRANSIENT",
            capabilities = mergedCaps,
            ssidHint = ssidHint.ifBlank { prev?.ssidHint.orEmpty() },
            updatedAtMs = nowMs
        )
        // Recompute local contiguous OUI blocks only when we have >= 2 distinct physical routers (`routerRadioGroupKey`)
        // with multi-day corroborated hits in the same OUI+agency (so dual-band `:08`/`:0C` on 1 router never self-forms a block!)
        val grouped = learnedMacCache.values
            .filter {
                it.ouiPrefix !in NON_BLOCK_LEARNING_OUIs &&
                    it.corroboratedHits >= 3 &&
                    it.distinctDays >= 2 &&
                    it.reputationScore >= 0.88
            }
            .groupBy { it.ouiPrefix to it.agencyId }
        val newBlocks = ArrayList<LearnedMacBlock>()
        for ((key, members) in grouped) {
            val sorted = members.sortedBy { it.nicU24 }
            val cluster = mutableListOf<LearnedMacIntel>()
            fun maybeAddCluster() {
                val distinctRouters = cluster.map { routerRadioGroupKey(it.mac) }.distinct().size
                if (distinctRouters >= 2) {
                    newBlocks.add(buildLocalBlock(key.first, key.second, cluster, distinctRouters))
                }
            }
            for (m in sorted) {
                val last = cluster.lastOrNull()
                if (last != null && m.nicU24 - last.nicU24 > 0x001000L) {
                    maybeAddCluster()
                    cluster.clear()
                }
                cluster.add(m)
            }
            maybeAddCluster()
        }
        if (newBlocks.isNotEmpty()) {
            val merged = (newBlocks + learnedBlockCache).distinctBy { "${it.ouiPrefix}|${it.startMac}|${it.endMac}" }
            learnedBlockCache = merged.sortedByDescending { it.confirmedCount }
        }
        if (context != null) persistLearnedIntel(context)
    }

    private fun buildLocalBlock(oui: String, agencyId: String, cluster: List<LearnedMacIntel>, distinctRouters: Int): LearnedMacBlock {
        val minNic = cluster.first().nicU24
        val maxNic = cluster.last().nicU24
        val mean = cluster.sumOf { it.reputationScore } / cluster.size.toDouble()
        val bonus = (distinctRouters.toDouble() / 6.0).coerceAtMost(0.18)
        return LearnedMacBlock(
            ouiPrefix = oui,
            startMac = cluster.first().mac,
            endMac = cluster.last().mac,
            minNicU24 = minNic,
            maxNicU24 = maxNic,
            agencyId = agencyId,
            label = cluster.first().label,
            confirmedCount = distinctRouters,
            confidenceBoost = (mean + bonus).coerceIn(0.40, 0.99)
        )
    }

    fun lookupMacIntel(context: Context?, mac: String): LearnedMacIntel? {
        ensureLearnedIntelLoaded(context)
        return learnedMacCache[normalizeMac(mac)]
    }

    // ---- TPMS Own-Vehicle Exclusion & Corroborated Agent Chassis Store ----

    data class OwnTpmsEntry(
        val facetKey: String,
        val soloGridCells: Set<String> = emptySet(),
        val pairedEntityIds: Set<String> = emptySet(),
        val isOwnVehicle: Boolean = false,
        val updatedAtMs: Long = 0L
    )

    data class LearnedTpmsIntel(
        val facetKey: String,
        val protocol: String,
        val sensorId: String,
        val boundEntityUuid: String,
        val agencyId: String,
        val label: String,
        val lastPsi: Int? = null,
        val distinctGridCells: Set<String> = emptySet(),
        val encounterCount: Int = 1,
        val colocatedHits: Int = 1,
        val witnessVerified: Boolean = false,
        val updatedAtMs: Long = 0L
    ) {
        val agencyEnum: KokohoAgency
            get() = KokohoAgency.fromCode(agencyId) ?: KokohoAgency.DHS_CBP
    }

    private const val KEY_OWN_TPMS_JSON = "own_tpms_v1"
    private const val KEY_LEARNED_TPMS_JSON = "learned_tpms_v1"
    private const val TPMS_ENCOUNTER_GAP_MS = 15 * 60_000L
    private const val OWN_TPMS_MIN_SOLO_CELLS = 4
    private const val OWN_TPMS_MAX_PAIRED_ENTITIES = 3
    private val ownTpmsCache = java.util.concurrent.ConcurrentHashMap<String, OwnTpmsEntry>()
    private val learnedTpmsCache = java.util.concurrent.ConcurrentHashMap<String, LearnedTpmsIntel>()
    @Volatile private var tpmsIntelLoaded = false

    fun canonicalTpmsFacetKey(raw: String): String? {
        val trimmed = raw.trim()
        val after = when {
            trimmed.startsWith("hw:kit:tpms:", ignoreCase = true) -> trimmed.substringAfter("hw:kit:tpms:")
            trimmed.startsWith("tpms:", ignoreCase = true) -> trimmed.substringAfter("tpms:")
            else -> return null
        }
        val parts = after.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val proto = parts[0].uppercase(java.util.Locale.US).replace(Regex("[^A-Z0-9]+"), "_")
        val id = parts[1].uppercase(java.util.Locale.US).filter { it.isLetterOrDigit() }
        if (proto.isEmpty() || id.length < 4) return null
        return "hw:kit:tpms:$proto:$id"
    }

    private fun ensureTpmsIntelLoaded(context: Context?) {
        if (tpmsIntelLoaded || context == null) return
        synchronized(this) {
            if (tpmsIntelLoaded) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            runCatching {
                prefs.getString(KEY_OWN_TPMS_JSON, null)?.takeIf { it.isNotBlank() }?.let { raw ->
                    val t = object : TypeToken<List<OwnTpmsEntry>>() {}.type
                    val list: List<OwnTpmsEntry> = Gson().fromJson(raw, t) ?: emptyList()
                    for (e in list) {
                        canonicalTpmsFacetKey(e.facetKey)?.let { k -> ownTpmsCache[k] = e.copy(facetKey = k) }
                    }
                }
                prefs.getString(KEY_LEARNED_TPMS_JSON, null)?.takeIf { it.isNotBlank() }?.let { raw ->
                    val t = object : TypeToken<List<LearnedTpmsIntel>>() {}.type
                    val list: List<LearnedTpmsIntel> = Gson().fromJson(raw, t) ?: emptyList()
                    for (m in list) {
                        val k = canonicalTpmsFacetKey(m.facetKey) ?: continue
                        if (ownTpmsCache[k]?.isOwnVehicle == true) continue
                        learnedTpmsCache[k] = m.copy(facetKey = k)
                    }
                }
            }
            tpmsIntelLoaded = true
        }
    }

    private fun persistTpmsIntel(context: Context?) {
        if (context == null) return
        val ownList = ownTpmsCache.values.sortedByDescending { it.updatedAtMs }.take(120)
        val learnedList = learnedTpmsCache.values.sortedByDescending { it.updatedAtMs }.take(250)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_OWN_TPMS_JSON, Gson().toJson(ownList))
            putString(KEY_LEARNED_TPMS_JSON, Gson().toJson(learnedList))
        }
    }

    fun clearTpmsStateForTest(context: Context?) {
        ownTpmsCache.clear()
        learnedTpmsCache.clear()
        tpmsIntelLoaded = false
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit {
            remove(KEY_OWN_TPMS_JSON)
            remove(KEY_LEARNED_TPMS_JSON)
        }
    }

    private fun tpmsGridCell500m(lat: Double, lon: Double): String =
        "${(lat * 200.0).roundToInt()},${(lon * 200.0).roundToInt()}"

    private fun tpmsGridCell400m(lat: Double, lon: Double): String =
        "${(lat * 250.0).roundToInt()},${(lon * 250.0).roundToInt()}"

    fun isOwnOrAmbientTpms(context: Context?, rawFacetKey: String): Boolean {
        ensureTpmsIntelLoaded(context)
        val k = canonicalTpmsFacetKey(rawFacetKey) ?: return false
        val own = ownTpmsCache[k] ?: return false
        return own.isOwnVehicle ||
            own.soloGridCells.size >= OWN_TPMS_MIN_SOLO_CELLS ||
            own.pairedEntityIds.size > OWN_TPMS_MAX_PAIRED_ENTITIES
    }

    /**
     * Tracks background TPMS observations so the observer's own vehicle wheels (heard across >= 4 distinct ~500m grid
     * cells when no nearby agent is present) are permanently excluded from agent attribution and Hub sharing.
     */
    fun observeTpmsAmbient(
        context: Context?,
        rawFacetKey: String,
        lat: Double?,
        lon: Double?,
        hasNearbyAgent: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ensureTpmsIntelLoaded(context)
        val k = canonicalTpmsFacetKey(rawFacetKey) ?: return
        if (hasNearbyAgent || lat == null || lon == null) return
        val cell = tpmsGridCell500m(lat, lon)
        val prev = ownTpmsCache[k]
        val nextCells = ((prev?.soloGridCells.orEmpty()) + cell)
        if (prev != null && nextCells.size == prev.soloGridCells.size && prev.isOwnVehicle) return
        val becameOwn = prev?.isOwnVehicle == true ||
            nextCells.size >= OWN_TPMS_MIN_SOLO_CELLS ||
            (prev?.pairedEntityIds?.size ?: 0) > OWN_TPMS_MAX_PAIRED_ENTITIES
        ownTpmsCache[k] = OwnTpmsEntry(
            facetKey = k,
            soloGridCells = nextCells,
            pairedEntityIds = prev?.pairedEntityIds.orEmpty(),
            isOwnVehicle = becameOwn,
            updatedAtMs = nowMs
        )
        if (becameOwn) {
            learnedTpmsCache.remove(k)
            confirmedEvidenceFacets.remove(k)
        }
        persistTpmsIntel(context)
    }

    fun recordTpmsAgentCorroboration(
        context: Context?,
        reading: org.tucsoncommunity.kokoho.sdr.Tpms.Reading,
        entityUuid: String,
        agency: KokohoAgency,
        label: String,
        lat: Double?,
        lon: Double?,
        witnessVerified: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): LearnedTpmsIntel? {
        ensureTpmsIntelLoaded(context)
        val k = reading.canonicalFacetKey
        if (isOwnOrAmbientTpms(context, k)) return null
        val normEntity = normalizeEntityId(entityUuid)

        val prevOwn = ownTpmsCache[k]
        val nextPaired = (prevOwn?.pairedEntityIds.orEmpty()) + normEntity
        if (nextPaired.size > OWN_TPMS_MAX_PAIRED_ENTITIES) {
            ownTpmsCache[k] = OwnTpmsEntry(
                facetKey = k,
                soloGridCells = prevOwn?.soloGridCells.orEmpty(),
                pairedEntityIds = nextPaired,
                isOwnVehicle = true,
                updatedAtMs = nowMs
            )
            learnedTpmsCache.remove(k)
            confirmedEvidenceFacets.remove(k)
            persistTpmsIntel(context)
            return null
        }
        ownTpmsCache[k] = OwnTpmsEntry(
            facetKey = k,
            soloGridCells = prevOwn?.soloGridCells.orEmpty(),
            pairedEntityIds = nextPaired,
            isOwnVehicle = false,
            updatedAtMs = nowMs
        )

        val prev = learnedTpmsCache[k]
        val cell400 = if (lat != null && lon != null) setOf(tpmsGridCell400m(lat, lon)) else emptySet()
        val sameEntity = prev == null || prev.boundEntityUuid == normEntity || witnessVerified
        val nextCells = if (sameEntity) (prev?.distinctGridCells.orEmpty()) + cell400 else cell400
        val nextEncounters = when {
            prev == null -> 1
            !sameEntity -> 1
            nowMs - prev.updatedAtMs >= TPMS_ENCOUNTER_GAP_MS -> prev.encounterCount + 1
            else -> prev.encounterCount
        }
        val psiInt = reading.psi?.roundToInt() ?: prev?.lastPsi
        val updated = LearnedTpmsIntel(
            facetKey = k,
            protocol = reading.protocol,
            sensorId = reading.id.uppercase(java.util.Locale.US),
            boundEntityUuid = if (sameEntity) (prev?.boundEntityUuid ?: normEntity) else normEntity,
            agencyId = if (witnessVerified || prev == null) agency.id else prev.agencyId,
            label = label.ifBlank { prev?.label.orEmpty().ifBlank { agency.friendlyLabel } },
            lastPsi = psiInt,
            distinctGridCells = nextCells,
            encounterCount = nextEncounters,
            colocatedHits = (prev?.colocatedHits ?: 0) + 1,
            witnessVerified = witnessVerified || prev?.witnessVerified == true || (k in confirmedEvidenceFacets),
            updatedAtMs = nowMs
        )
        learnedTpmsCache[k] = updated
        if (witnessVerified) {
            confirmedEvidenceFacets.add(k)
        }
        persistTpmsIntel(context)
        return updated
    }

    fun isTpmsVerifiedAgentChassis(context: Context?, intel: LearnedTpmsIntel): Boolean {
        if (isOwnOrAmbientTpms(context, intel.facetKey)) return false
        return intel.witnessVerified ||
            intel.encounterCount >= 2 ||
            intel.distinctGridCells.size >= 2 ||
            (intel.facetKey in confirmedEvidenceFacets)
    }

    fun lookupVerifiedAgentTpms(context: Context?, rawFacetKey: String): LearnedTpmsIntel? {
        ensureTpmsIntelLoaded(context)
        val k = canonicalTpmsFacetKey(rawFacetKey) ?: return null
        if (isOwnOrAmbientTpms(context, k)) return null
        val intel = learnedTpmsCache[k] ?: return null
        return intel.takeIf { isTpmsVerifiedAgentChassis(context, it) }
    }

    fun hasVerifiedAgentTpms(context: Context?): Boolean {
        ensureTpmsIntelLoaded(context)
        return learnedTpmsCache.values.any { isTpmsVerifiedAgentChassis(context, it) }
    }

    private fun promoteWitnessTpmsForReport(
        context: Context,
        entityId: String,
        agency: KokohoAgency,
        report: WitnessReportRecord
    ) {
        ensureTpmsIntelLoaded(context)
        var changed = false
        for ((k, existing) in learnedTpmsCache) {
            if (existing.boundEntityUuid == entityId && !isOwnOrAmbientTpms(context, k)) {
                learnedTpmsCache[k] = existing.copy(
                    agencyId = agency.id,
                    witnessVerified = true,
                    updatedAtMs = report.updatedAtMs
                )
                confirmedEvidenceFacets.add(k)
                changed = true
            }
        }
        val rLat = report.lat
        val rLon = report.lon
        for (entry in org.tucsoncommunity.kokoho.sdr.TpmsLog.current(report.updatedAtMs)) {
            if (report.updatedAtMs - entry.lastSeen > 45_000L) continue
            if (isOwnOrAmbientTpms(context, entry.facetKey)) continue
            val obsLat = entry.lastLat ?: entry.firstLat
            val obsLon = entry.lastLon ?: entry.firstLon
            val closeEnough = if (rLat != null && rLon != null && obsLat != null && obsLon != null) {
                KokohoPostureEngine.haversineMeters(rLat, rLon, obsLat, obsLon) <= 100.0
            } else {
                true
            }
            if (!closeEnough) continue
            recordTpmsAgentCorroboration(
                context = context,
                reading = entry.reading,
                entityUuid = entityId,
                agency = agency,
                label = agency.friendlyLabel,
                lat = rLat ?: obsLat,
                lon = rLon ?: obsLon,
                witnessVerified = true,
                nowMs = report.updatedAtMs
            )
            changed = true
        }
        if (changed) persistTpmsIntel(context)
    }

    private fun revokeTpmsForEntity(context: Context, normKey: String, normUuid: String?) {
        ensureTpmsIntelLoaded(context)
        val targets = setOfNotNull(normKey, normUuid)
        val toRemove = learnedTpmsCache.values
            .filter { it.boundEntityUuid in targets }
            .map { it.facetKey }
        for (k in toRemove) {
            learnedTpmsCache.remove(k)
            confirmedEvidenceFacets.remove(k)
        }
        if (toRemove.isNotEmpty()) persistTpmsIntel(context)
    }

    fun lookupAssignedMacBlock(context: Context?, mac: String): LearnedMacBlock? {
        ensureLearnedIntelLoaded(context)
        val (oui, nic, _) = parseMacOuiAndNic(mac) ?: return null
        return learnedBlockCache.firstOrNull {
            it.ouiPrefix == oui && nic >= (it.minNicU24 - 0x40L).coerceAtLeast(0L) && nic <= it.maxNicU24 + 0x40L
        }
    }

    fun isStaticBaselineRouter(context: Context?, mac: String, lat: Double? = null, lon: Double? = null): Boolean {
        if (org.tucsoncommunity.kokoho.detect.FixedRouter.isFamiliarAt(mac, lat, lon)) return true
        val lm = lookupMacIntel(context, mac) ?: return false
        return lm.associationPattern == "STATIC_LOCALE" && lm.corroboratedHits == 0
    }

    /**
     * Excludes "Axon Networks Inc." (`00:58:28`, `00:C0:D4`, `84:70:03`), a consumer fiber-ISP Wi-Fi 7 home router ODM
     * (Quantum Fiber / CenturyLink residential gateways), which is unrelated to Axon Enterprise (`00:25:DF`).
     */
    fun isAxonNetworksConsumerRouter(mac: String, nameOrSsid: String?, vendor: String?): Boolean {
        val norm = normalizeMac(mac)
        if (norm.startsWith("00:58:28") || norm.startsWith("00:C0:D4") || norm.startsWith("84:70:03")) return true
        if ((vendor ?: "").contains("Axon Networks", ignoreCase = true)) return true
        if ((nameOrSsid ?: "").contains("Axon Networks", ignoreCase = true)) return true
        return false
    }

    private val PATROL_MDT_OUIs = setOf("4C:36:4E", "B8:20:8E", "BC:3E:0B", "8C:1F:64:DF:0")
    private val PATROL_MDT_REGEX = Regex("(?i)\\b(TOUGHBOOK|PANASONIC\\s+CONNECT|GETAC|CYBERKAR)\\b")

    fun isPatrolMdtDevice(mac: String, nameOrSsid: String?, vendor: String?): Boolean {
        val norm = normalizeMac(mac)
        if (PATROL_MDT_OUIs.any { norm.startsWith(it) }) return true
        val combined = "${nameOrSsid.orEmpty()} ${vendor.orEmpty()}"
        return PATROL_MDT_REGEX.containsMatchIn(combined)
    }

    private val COMPANION_KIT_REGEX = Regex(
        "(?i)(?:\\b(BWCDEVICE|AXON(?!\\s+NETWORKS)|TASER|VIEVU|WATCHGUARD|BODYWORN|DIGITAL\\s*ALLY|ZEPCAM|ARBITRATOR|MOTOROLA\\s+SOLUTIONS|APX|XL-?200P|XL-?185P|ZEBRA|POCKETJET|PJ-?7|PJ-?8|RJ-?4|WHELEN|CENCOM|HAVIS|GAMBER|TOUGHBOOK)\\b|ZQ[35][0-9]{2}|PJ7[0-9]{2}|PJ8[0-9]{2})"
    )

    private val AXON_OR_APX_ACCESSORY_REGEX = Regex(
        "(?i)\\b(BWCDEVICE|AXON(?!\\s+NETWORKS)|TASER|VIEVU|WATCHGUARD|BODYWORN|DIGITAL\\s*ALLY|ZEPCAM|ARBITRATOR|BODY_?CAM|BODYCAM|MOTOROLA\\s+SOLUTIONS|APX|XL-?200P|XL-?185P|WHELEN|CENCOM|HAVIS|GAMBER|TOUGHBOOK)\\b"
    )

    /** Raw Bluetooth SIG Company IDs & 16-bit Service UUIDs for Axon/TASER, Motorola Solutions APX, and Zebra kits. */
    private const val BLE_COMPANY_TASER_AXON = 0x034D
    private const val BLE_COMPANY_MOTOROLA_SOLUTIONS = 0x04EC
    private const val BLE_COMPANY_ZEBRA = 0x01F1
    private val BLE_AXON_SERVICE_UUID16 = setOf(0xFC81, 0xFE6B, 0xFE6C)
    private val BLE_MOTOROLA_SERVICE_UUID16 = setOf(0xFD8E, 0xFE04)
    private val BLE_ZEBRA_SERVICE_UUID16 = setOf(0xFD66, 0xFE79)

    private val CONSUMER_BLE_WEARABLE_OR_AUDIO_REGEX = Regex(
        "(?i)^\\s*(?:IDW\\d+[A-Z]?|ID20\\d+[A-Z]?|VERYFIT.*|FITBIT.*|GARMIN.*|AMAZFIT.*|MI\\s*BAND.*|XIAOMI.*|REDMI.*|GALAXY\\s*WATCH.*|APPLE\\s*WATCH.*|AIRPODS.*|HBAND.*|DA\\s*FIT.*|ORAIMO.*|SOUNDCORE.*|JBL\\b.*|BOSE\\b.*|WHOOP.*|OURA.*|TILE\\b.*|CHIPOLO.*)\\s*$"
    )
    private val PLAUSIBLE_TACTICAL_BLE_NAME_REGEX = Regex(
        "(?i)(?:AXON|TASER|BWC|X8[0-9A-Z]|SIGNAL|BODY|VIEVU|WATCHGUARD|MOTOROLA|APX|MOTO|ZEBRA|ZQ\\d|QLN|PJ-?[78]|RJ-?4|DIRECT-)"
    )

    enum class BleTacticalKind(val capabilityLabel: String, val facetPrefix: String, val defaultSummary: String) {
        AXON_BODYCAM("Bodycam", "hw:kit:bwc", "Axon Bodycam"),
        MOTOROLA_RADIO("Portable Radio", "hw:kit:radio", "Motorola APX Radio"),
        ZEBRA_PRINTER("Citation Printer", "hw:kit:printer", "Citation Printer")
    }

    fun classifyBleTacticalPayload(advert: org.tucsoncommunity.kokoho.detect.Advert?): BleTacticalKind? {
        if (advert == null || !advert.isBle) return null
        val cleanName = advert.name?.trim().orEmpty()
        if (cleanName.isNotEmpty() && (CONSUMER_BLE_WEARABLE_OR_AUDIO_REGEX.matches(cleanName) || KARR_DEALER_BLE_ALARM_REGEX.matches(cleanName))) {
            return null
        }
        val shorts = (advert.serviceUuids + advert.serviceData.keys)
            .mapNotNull { org.tucsoncommunity.kokoho.detect.Advert.shortOf(it) }
        val hasAxonUuid = shorts.any { it in BLE_AXON_SERVICE_UUID16 }
        val hasMotoUuid = shorts.any { it in BLE_MOTOROLA_SERVICE_UUID16 }
        val hasZebraUuid = shorts.any { it in BLE_ZEBRA_SERVICE_UUID16 }

        // Guard against cheap consumer wearables (e.g. IDO "IDW26") using BLE random addresses that stuff
        // unregistered model/version bytes into 0xFF Manufacturer Data without matching tactical naming or SIG Service UUIDs.
        val allowMfgOnly = !advert.randomBle || cleanName.isEmpty() || PLAUSIBLE_TACTICAL_BLE_NAME_REGEX.containsMatchIn(cleanName)

        if (hasAxonUuid || (allowMfgOnly && BLE_COMPANY_TASER_AXON in advert.manufacturerData.keys)) {
            return BleTacticalKind.AXON_BODYCAM
        }
        if (hasMotoUuid || (allowMfgOnly && BLE_COMPANY_MOTOROLA_SOLUTIONS in advert.manufacturerData.keys)) {
            return BleTacticalKind.MOTOROLA_RADIO
        }
        if (hasZebraUuid || (allowMfgOnly && BLE_COMPANY_ZEBRA in advert.manufacturerData.keys)) {
            return BleTacticalKind.ZEBRA_PRINTER
        }
        return null
    }

    fun matchesBleTacticalPayload(advert: org.tucsoncommunity.kokoho.detect.Advert?): Boolean =
        classifyBleTacticalPayload(advert) != null

    private val ZEBRA_PRINTER_REGEX = Regex(
        "(?i)(?:\\b(ZEBRA|POCKETJET|PJ-?7|PJ-?8|RJ-?4)\\b|ZQ[35][0-9]{2}|PJ7[0-9]{2}|PJ8[0-9]{2})"
    )

    /**
     * Curated IEEE MA-L / MA-M / MA-S prefixes for Zebra Technologies (ZQ300/ZQ500/QLn) and
     * Brother Mobile Solutions (PocketJet PJ-7/PJ-8 / RuggedJet RJ-4) mobile citation printers.
     */
    private val CITATION_PRINTER_OUIs = setOf(
        // Zebra Technologies (17 IEEE blocks)
        "00:05:12", "00:07:4D", "00:15:70", "00:23:68", "00:A0:F8", "40:83:DE",
        "48:8E:B7", "60:95:32", "74:93:A4", "78:B8:D6", "84:24:8D", "88:BC:AC",
        "90:75:DE", "94:FB:29", "C4:7D:CC", "C8:1C:FE", "FC:59:7A",
        // Brother Mobile Solutions (7 IEEE blocks)
        "00:1B:A9", "00:80:77", "30:05:5C", "3C:2A:F4", "94:DD:F8", "B0:7C:8E", "B4:22:00"
    )

    /**
     * Curated IEEE MA-L / MA-M / MA-S and field-attributed OUI prefixes for agency-agnostic
     * officer/vehicle accessories (from `all-cameras-are-beacons`, `SignalSweep`, `flock-you`,
     * `oui-spy`, `wardrive-go`, `Fieldwatch`, and IEEE registries):
     *  - Body-Worn & In-Car Video: Axon (`00:25:DF`), field-attributed Axon `BWCDEVICE` block (`D8:1F:65`),
     *    Vievu/Axon (`FC:01:9E`), WatchGuard/Motorola V300/V700 (`00:1D:96`), Digital Ally (`00:23:BD`),
     *    Utility `BodyWorn` (`00:09:BC`, `00:16:ED`), Zepcam (`48:46:8D`), i-PRO/Arbitrator (`D4:2D:C5`),
     *    Pro-Vision (`9C:83:BF`), IPT VisionHawk (`00:1C:3F`), ICOP (`00:1B:BE`), L-3 Mobile-Vision (`00:50:C2:6D:C`, `00:50:C2:A4:B`, `38:73:EA:0`)
     *  - P25 / LMR Tactical Radios: Motorola Solutions APX (`00:1F:92`, `4C:CC:34`, `00:18:85`, `00:04:7D`, `10:74:6F`, `B8:E2:8C`, `9C:86:2B`),
     *    L3Harris XG/XL (`00:00:C3`, `00:02:68`, `00:06:EC`, `00:17:F3`), E.F. Johnson (`00:08:B8`),
     *    Codan/Daniels (`00:14:91`), Thales (`00:26:B3`, `00:50:C2:8A:D`), Tait (`00:0D:CA`, `8C:1F:64:A7:8`)
     *  - Upfit Consoles, Siren Telemetry & Toughbooks: Havis (`00:1F:9C`, `70:B3:D5:98:3`),
     *    Gamber-Johnson (`00:50:C2:84:F`, `00:50:C2:A7:2`, `00:50:C2:D9:C`, `68:DA:73:B`, `70:B3:D5:17:8`, `70:B3:D5:DB:C`, `70:B3:D5:E3:4`, `8C:1F:64:04:C`, `8C:1F:64:42:B`, `8C:1F:64:CB:5`),
     *    Federal Signal (`08:3C:03:0`), Patrol Products (`38:43:69`), Panasonic Toughbook (`4C:36:4E`, `B8:20:8E`, `BC:3E:0B`)
     */
    private val AGENCY_AGNOSTIC_ACCESSORY_OUIs = setOf(
        // Bodycams & In-Car Video
        "00:25:DF", "D8:1F:65", "D8:96:E0", "FC:01:9E", "00:1D:96", "00:23:BD",
        "00:09:BC", "00:16:ED", "48:46:8D", "D4:2D:C5", "9C:83:BF", "00:1C:3F",
        "00:1B:BE", "00:50:C2:6D:C", "00:50:C2:A4:B", "38:73:EA:0",
        // Motorola, L3Harris, EFJohnson, Codan, Thales, Tait P25 Radios
        "00:1F:92", "4C:CC:34", "00:18:85", "00:04:7D", "10:74:6F", "B8:E2:8C", "9C:86:2B",
        "00:00:C3", "00:02:68", "00:06:EC", "00:17:F3", "00:08:B8", "00:14:91",
        "00:26:B3", "00:50:C2:8A:D", "00:0D:CA", "8C:1F:64:A7:8",
        // Vehicle Upfit Consoles, Siren Telemetry & Toughbooks
        "00:1F:9C", "70:B3:D5:98:3", "00:50:C2:84:F", "00:50:C2:A7:2", "00:50:C2:D9:C",
        "68:DA:73:B", "70:B3:D5:17:8", "70:B3:D5:DB:C", "70:B3:D5:E3:4", "8C:1F:64:04:C",
        "8C:1F:64:42:B", "8C:1F:64:CB:5", "08:3C:03:0", "38:43:69",
        "4C:36:4E", "B8:20:8E", "BC:3E:0B"
    )

    fun isZebraCitationPrinter(mac: String, nameOrSsid: String?, vendor: String?): Boolean {
        val norm = normalizeMac(mac)
        if (CITATION_PRINTER_OUIs.any { norm.startsWith(it) }) return true
        val combined = "${nameOrSsid.orEmpty()} ${vendor.orEmpty()}"
        return ZEBRA_PRINTER_REGEX.containsMatchIn(combined)
    }

    /**
     * Returns true when a companion accessory is an agency-agnostic officer/vehicle accessory
     * (Axon Body 3/4 camera `00:25:DF`, field-attributed Axon `BWCDEVICE` `D8:1F:65`, Vievu `FC:01:9E`,
     * WatchGuard `00:1D:96`, Digital Ally `00:23:BD`, Utility `BodyWorn` `00:09:BC`/`00:16:ED`,
     * Motorola APX `00:1F:92`/`4C:CC:34`/`00:18:85`/`00:04:7D`, L3Harris XG/XL `00:00:C3`/`00:17:F3`,
     * Havis/Gamber-Johnson vehicle docks, Federal Signal/Whelen siren telemetry, or Panasonic Toughbook).
     * These are deployed across CBP, ICE, HSI, USMS, Police, and Sheriff and must NEVER be
     * conflated as PD (`POLICE`) by default; instead they bias toward the colocated primary agency presence.
     */
    fun isAxonOrAgencyAgnosticAccessory(mac: String, nameOrSsid: String?, vendor: String?): Boolean {
        if (isAxonNetworksConsumerRouter(mac, nameOrSsid, vendor)) return false
        val norm = normalizeMac(mac)
        if (AGENCY_AGNOSTIC_ACCESSORY_OUIs.any { norm.startsWith(it) }) return true
        val combined = "${nameOrSsid.orEmpty()} ${vendor.orEmpty()}"
        return AXON_OR_APX_ACCESSORY_REGEX.containsMatchIn(combined)
    }

    fun isCompanionKitDevice(
        mac: String,
        nameOrSsid: String?,
        vendor: String?,
        advert: org.tucsoncommunity.kokoho.detect.Advert? = null
    ): Boolean {
        if (isIgnoredInfrastructureOrRetail(mac, nameOrSsid)) return false
        if (isAxonNetworksConsumerRouter(mac, nameOrSsid, vendor)) return false
        val cleanName = nameOrSsid?.trim().orEmpty()
        if (cleanName.isNotEmpty() && (CONSUMER_BLE_WEARABLE_OR_AUDIO_REGEX.matches(cleanName) || KARR_DEALER_BLE_ALARM_REGEX.matches(cleanName))) {
            return false
        }
        if (matchesBleTacticalPayload(advert)) return true
        val norm = normalizeMac(mac)
        if (AGENCY_AGNOSTIC_ACCESSORY_OUIs.any { norm.startsWith(it) } ||
            CITATION_PRINTER_OUIs.any { norm.startsWith(it) }
        ) return true
        val combined = "${nameOrSsid.orEmpty()} ${vendor.orEmpty()}"
        return COMPANION_KIT_REGEX.containsMatchIn(combined)
    }

    fun inferCapabilitiesFromEvidence(facets: List<String>, details: List<String>): List<String> {
        val caps = ArrayList<String>()
        val isDroneAirframe = facets.any { f ->
            f.startsWith("uas:mac:", ignoreCase = true) ||
                f.startsWith("uas:id:", ignoreCase = true) ||
                f.startsWith("uas:alt_", ignoreCase = true) ||
                f.startsWith("drone:", ignoreCase = true)
        } || details.any { d ->
            d.contains("Tactical Drone", ignoreCase = true) ||
                d.contains("Skydio", ignoreCase = true) ||
                d.contains("Remote ID", ignoreCase = true) ||
                d.contains("sUAS", ignoreCase = true)
        }
        if (!isDroneAirframe && facets.any { it.startsWith("uas:op:", ignoreCase = true) }) {
            caps.add("Drone Operator")
        }
        if (facets.any { it.startsWith("hw:kit:bwc:", ignoreCase = true) }) {
            caps.add("Bodycam")
        }
        if (facets.any { it.startsWith("hw:kit:printer:", ignoreCase = true) }) {
            caps.add("Citation Printer")
        }
        if (facets.any { it.startsWith("hw:kit:radio:", ignoreCase = true) }) {
            caps.add("Portable Radio")
        }
        if (facets.any { it.startsWith("hw:kit:mdt:", ignoreCase = true) }) {
            caps.add("Patrol MDT")
        }
        if (facets.any { it.startsWith("hw:kit:tpms:", ignoreCase = true) }) {
            caps.add("Vehicle TPMS")
        }
        val combined = (facets + details).joinToString(" ").uppercase()
        val sanitizedBodycam = combined
            .replace("AXON NETWORKS", "")
            .replace("00:58:28", "")
            .replace("00:C0:D4", "")
            .replace("84:70:03", "")
            .replace("HW:KIT:PRINTER:", "")
            .replace("HW:KIT:RADIO:", "")
            .replace("HW:KIT:MDT:", "")
            .replace("HW:KIT:TPMS:", "")
        if ("Bodycam" !in caps && ("00:25:DF" in sanitizedBodycam || "D8:1F:65" in sanitizedBodycam || "FC:01:9E" in sanitizedBodycam || "00:1D:96" in sanitizedBodycam || "00:23:BD" in sanitizedBodycam || "BWCDEVICE" in sanitizedBodycam || "BODY_CAM" in sanitizedBodycam || "AXON" in sanitizedBodycam || "TASER" in sanitizedBodycam || "BODYCAM" in sanitizedBodycam || "WATCHGUARD" in sanitizedBodycam || "BODYWORN" in sanitizedBodycam)) {
            caps.add("Bodycam")
        }
        if (facets.any { it.startsWith("rf:chan:", ignoreCase = true) || it.startsWith("rf:rid:", ignoreCase = true) || it.startsWith("rf:tg:", ignoreCase = true) }) {
            caps.add("Tactical RF")
        }
        if ("Citation Printer" !in caps && ("ZEBRA" in combined || "POCKETJET" in combined || "RJ-4" in combined || "PJ-7" in combined || "PJ-8" in combined || "PRINTER" in combined)) {
            caps.add("Citation Printer")
        }
        if ("Portable Radio" !in caps && ("MOTOROLA SOLUTIONS" in combined || "APX" in combined || "XL-200P" in combined)) {
            caps.add("Portable Radio")
        }
        if ("Patrol MDT" !in caps && ("TOUGHBOOK" in combined || "PANASONIC CONNECT" in combined || "B8:20:8E" in combined || "4C:36:4E" in combined || "BC:3E:0B" in combined || "CYBERKAR" in combined)) {
            caps.add("Patrol MDT")
        }
        if (facets.any { it.startsWith("cell:anom:", ignoreCase = true) } || "CELL_ANOMALY" in combined || "00:50:C2:36:0" in combined || "DRTBOX" in combined) {
            caps.add("Cell Anomaly")
        }
        return caps.distinct()
    }

    /** True when a user or peer has submitted a report confirming this sighting as law enforcement (`reportKind == "LE"`). */
    fun hasConfirmingReport(witness: WitnessReportRecord?): Boolean =
        witness != null && witness.reportKind.equals("LE", ignoreCase = true)

    /**
     * True when either:
     *  1. Someone submitted a report confirming this particular sighting (`hasConfirmingReport(witness)` or `WITNESS_REPORT` badge), OR
     *  2. Any exact unit-level evidence on [threat] (`hw:mac:*`, `hw:kit:*`, `uas:mac:*`, `adsb:icao:*`, `rf:rid:*`)
     *     has been confirmed in a report before.
     */
    fun isExactEvidenceConfirmed(
        threat: EvaluatedThreat,
        witness: WitnessReportRecord? = null
    ): Boolean {
        if (hasConfirmingReport(witness)) return true
        if (witness != null && witness.reportKind.equals("FALSE_POSITIVE", ignoreCase = true)) return false
        if (threat.evidenceSources.any { it.kind == "WITNESS_REPORT" }) return true
        val normUuid = normalizeEntityId(threat.uuid)
        if ("entity:$normUuid" in confirmedEvidenceFacets) return true
        if (hasConfirmingReport(witnessCache[normUuid])) return true
        val exactKeys = canonicalConfirmableFacetKeys(
            threat.evidenceSources.map { it.facetKey },
            threat.hardwareIdentifier
        )
        for (key in exactKeys) {
            if (key in confirmedEvidenceFacets) return true
            if (hasConfirmingReport(witnessCache[key])) return true
            val derivedUuid = KokohoPostureEngine.deterministicUuidV5(key)
            if ("entity:$derivedUuid" in confirmedEvidenceFacets || hasConfirmingReport(witnessCache[derivedUuid])) {
                return true
            }
        }
        return false
    }

    /**
     * Epistemic prefix policy:
     *  - `"Confirmed"` is strictly reserved for sightings with a confirming report OR exact unit evidence confirmed before.
     *  - Unconfirmed high-confidence / explicit-SSID sightings cap at `"Likely"`.
     *  - Lower-confidence sightings render as `"Suspected"`.
     */
    fun epistemicPrefix(
        confidence: Int,
        witness: WitnessReportRecord? = null,
        hasExplicitAgencySsid: Boolean = false,
        evidenceConfirmedBefore: Boolean = false
    ): String {
        val isFalsePos = witness != null && witness.reportKind.equals("FALSE_POSITIVE", ignoreCase = true)
        val confirmed = !isFalsePos && (hasConfirmingReport(witness) || evidenceConfirmedBefore)
        return when {
            confirmed -> "Confirmed"
            hasExplicitAgencySsid || confidence >= 65 -> "Likely"
            else -> "Suspected"
        }
    }

    fun formatHighestOrderTitle(
        threat: EvaluatedThreat,
        witness: WitnessReportRecord? = null
    ): String {
        if (hasConfirmingReport(witness) && witness != null) {
            val ag = (KokohoAgency.fromCode(witness.agencyCode) ?: threat.agency).friendlyLabel
            val subjectText = when {
                witness.agentName.isNotBlank() && witness.badgeNumber.isNotBlank() -> "${witness.agentName} (#${witness.badgeNumber.removePrefix("#")})"
                witness.agentName.isNotBlank() -> witness.agentName
                witness.vehicleNumber.isNotBlank() -> "Veh #${witness.vehicleNumber.removePrefix("#")}"
                witness.plateNumber.isNotBlank() -> "Plate ${witness.plateNumber}"
                witness.accessoryKind.isNotBlank() -> witness.accessoryKind
                witness.badgeNumber.isNotBlank() -> "Badge #${witness.badgeNumber.removePrefix("#")}"
                else -> ""
            }
            if (subjectText.isNotBlank()) {
                return "Confirmed $ag: $subjectText"
            }
        }
        val ssidCandidate = threat.evidenceSources.firstOrNull()?.detail
        val explicitSsid = isExplicitAgencyOrTacticalSsid(ssidCandidate) || isExplicitAgencyOrTacticalSsid(threat.recurrenceSummary)
        val confirmedEvidence = isExactEvidenceConfirmed(threat, witness)
        val tier = epistemicPrefix(threat.combinedConfidence, witness, explicitSsid, confirmedEvidence)
        val summary = threat.recurrenceSummary.trim()
        val subject = when {
            threat.isDrone -> "Tactical Drone"
            threat.isAircraft && threat.isHelicopter -> "${threat.agency.friendlyLabel} Helicopter${threat.aircraftHex?.let { " ($it)" } ?: ""}"
            threat.isAircraft -> "${threat.agency.friendlyLabel} Aircraft${threat.aircraftHex?.let { " ($it)" } ?: ""}"
            "Bodycam" in threat.capabilities && threat.evidenceSources.size == 1 ->
                if (summary.contains("Axon", ignoreCase = true) || summary.contains("TASER", ignoreCase = true)) "Axon Bodycam" else "${threat.agency.friendlyLabel} Bodycam"
            "Citation Printer" in threat.capabilities && threat.evidenceSources.size == 1 -> "Citation Printer"
            "Portable Radio" in threat.capabilities && threat.evidenceSources.size == 1 -> "Motorola APX Radio"
            "Patrol MDT" in threat.capabilities && threat.evidenceSources.size == 1 -> "${threat.agency.friendlyLabel} Patrol MDT"
            summary.contains("Fleet Router", ignoreCase = true) || summary.contains("IBR", ignoreCase = true) || summary.contains("R920", ignoreCase = true) || summary.contains("R1900", ignoreCase = true) || summary.contains("Cradlepoint", ignoreCase = true) || summary.contains("Sierra", ignoreCase = true) || summary.contains("Peplink", ignoreCase = true) ->
                if (tier == "Suspected") "Fleet Router" else "${threat.agency.friendlyLabel} Fleet Router"
            threat.evidenceSources.all { it.kind.startsWith("RF_") || it.kind == "P25_UNIT" } ->
                "${threat.agency.friendlyLabel} Tactical RF"
            else -> threat.agency.friendlyLabel
        }
        return if (subject.startsWith("Confirmed ") || subject.startsWith("Likely ") || subject.startsWith("Suspected ")) {
            subject
        } else {
            "$tier $subject"
        }
    }

    fun formatIdentityWithCapabilities(
        baseLabel: String,
        witness: WitnessReportRecord?,
        capabilities: List<String>
    ): String {
        val agentName = witness?.agentName?.trim().orEmpty()
        val badge = witness?.badgeNumber?.trim()?.removePrefix("#").orEmpty()
        val veh = witness?.vehicleNumber?.trim().orEmpty()
        var core = when {
            agentName.isNotEmpty() && badge.isNotEmpty() -> "$agentName #$badge"
            agentName.isNotEmpty() -> agentName
            badge.isNotEmpty() -> "Badge #$badge"
            else -> baseLabel.trim()
        }
        if (veh.isNotEmpty() && (agentName.isNotEmpty() || badge.isNotEmpty()) && !core.contains(veh)) {
            core = "$core · Veh $veh"
        }
        val missing = capabilities.filter { it.isNotBlank() && !core.contains(it, ignoreCase = true) }
        if (missing.isEmpty()) return core
        val closeIdx = core.lastIndexOf(']')
        return if (closeIdx > 0 && core.substring(0, closeIdx).contains('[')) {
            "${core.substring(0, closeIdx)} · ${missing.joinToString(" · ")}]"
        } else {
            "$core [${missing.joinToString(" · ")}]"
        }
    }

    fun isRelevantBleOrWifiCandidate(
        mac: String,
        nameOrSsid: String?,
        vendor: String?,
        hasRemoteId: Boolean,
        isBleTrackerPayload: Boolean,
        advert: org.tucsoncommunity.kokoho.detect.Advert? = null
    ): Boolean {
        if (hasRemoteId || isBleTrackerPayload) return true
        if (isAxonNetworksConsumerRouter(mac, nameOrSsid, vendor)) return false
        if (matchesBleTacticalPayload(advert)) return true
        val normMac = normalizeMac(mac)
        if (normMac.startsWith("04:F0:21:") || normMac.startsWith("00:40:29:") || normMac.startsWith("00:80:48:")) return false
        if (!nameOrSsid.isNullOrBlank() && nameOrSsid.trim().startsWith("MimoSeries", ignoreCase = true)) return false
        if (isCompanionKitDevice(mac, nameOrSsid, vendor, advert)) return true
        val canonMac = canonicalHardwareOuiMac(mac)
        if (canonMac.take(8) in GENERIC_FLEET_ROUTER_OUIs) return true
        if (isMunicipalTrafficCabinetOrTransit(mac, nameOrSsid) || isFlockCamera(mac, nameOrSsid)) return true
        if (lookupMacIntel(null, mac) != null || lookupAssignedMacBlock(null, mac) != null) return true
        return matchOuiOrSsid(canonMac, nameOrSsid, vendor, hasRemoteId = false) != null
    }

    fun watchedTargetsForEnabled(context: Context): List<RadioWatch.Target> {
        ensureLoaded(context)
        val evaluated = getEvaluatedAgencies(context)
        return rfChannels
            .filter { it.agencyEnum in evaluated && it.isAlertableDirectOrMobile }
            .distinctBy { it.hz }
            .map { RadioWatch.Target(it.hz, it.agencyEnum.friendlyLabel) }
    }

    /** Programmatic channel import helper for tests and regional catalog provisioning. */
    fun importChannelText(context: Context, text: String, sourceName: String): Int {
        ensureLoaded(context)
        val trimmed = text.trim()
        val airRegex = Regex("""(?i)\b(AIR\s*\d+)\s*[-\u2013:]\s*([0-9]{2,4}\.[0-9]{1,6})\s*MHz(?:.*?NAC\s*([0-9A-Fa-f]{1,3}))?""")
        val nacRegex = Regex("""(?i)\b([0-9A-Fa-f]{1,3})\s*NAC\b""")
        val freqStartRegex = Regex("""^\s*([0-9]{2,4}\.[0-9]{2,6})\b""")
        val updated = rfChannels.toMutableList()
        var count = 0

        for (rawLine in trimmed.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val airMatch = airRegex.find(line)
            if (airMatch != null) {
                val alpha = airMatch.groupValues[1].uppercase().replace(Regex("\\s+"), " ")
                val mhz = airMatch.groupValues[2].toDoubleOrNull() ?: continue
                val hz = (mhz * 1_000_000.0).toLong()
                val nac = airMatch.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.uppercase()
                updated.add(RfChannelSig(hz = hz, nac = nac, mode = "P25", alpha = alpha, description = "$alpha ($sourceName)", agency = "DHS_AMO", role = "AIR_ASSET"))
                count++
                continue
            }
            val freqMatch = freqStartRegex.find(line) ?: continue
            val mhz = freqMatch.groupValues[1].toDoubleOrNull() ?: continue
            val hz = (mhz * 1_000_000.0).toLong()
            val parts = if (line.contains('\t')) line.split('\t').map { it.trim() } else line.split(',').map { it.trim().trim('"') }
            val nac = nacRegex.find(line)?.groupValues?.get(1)?.uppercase()?.padStart(3, '0')
            val alpha = parts.firstOrNull { it.length in 3..24 && !it.matches(Regex("[0-9.]+")) && it !in setOf("BM", "RM", "M", "P25", "P25e", "FMN") && !it.endsWith("NAC", ignoreCase = true) }
                ?: "%.4f MHz".format(java.util.Locale.US, mhz)
            val desc = parts.getOrNull(parts.indexOf(alpha) + 1) ?: sourceName
            val combined = "$alpha $desc $line".uppercase()
            val agency = when {
                combined.contains("ICE") || combined.contains("ERO") -> "DHS_ICE"
                combined.contains("HSI") -> "DHS_HSI"
                combined.contains("AMO") || combined.contains("AIR") -> "DHS_AMO"
                combined.contains("FPS") -> "DHS_FPS"
                combined.contains("CBP") || combined.contains("BP ") -> "DHS_CBP"
                combined.contains("SHERIFF") || combined.contains("PCSO") -> "SHERIFF"
                combined.contains("FIRE") || combined.contains("TFD") -> "FIRE"
                combined.contains("SAR") || combined.contains("RESCUE") -> "SAR"
                combined.contains("POLICE") || combined.contains("TPD") -> "POLICE"
                else -> "DHS_INTEROP"
            }
            updated.add(RfChannelSig(hz = hz, nac = nac, mode = "P25", alpha = alpha, description = desc, agency = agency, role = "DIRECT_TACTICAL"))
            count++
        }
        if (count > 0) {
            rfChannels = updated
            FreqNames.importCsv(context, trimmed, sourceName)
        }
        return count
    }
}
