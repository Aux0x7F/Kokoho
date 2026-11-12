package org.tucsoncommunity.kokoho.posture

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tucsoncommunity.kokoho.sdr.FreqNames
import org.tucsoncommunity.kokoho.sdr.RadioWatch
import org.tucsoncommunity.kokoho.util.Units
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * Hierarchical agency taxonomy for Kokoho, ordered by strict alerting priority:
 *  1. [DHS_ICE] ("ICE")
 *  2. [DHS_AMO] ("DHS Air" — AMO & circling unlisted spy planes)
 *  3. [DHS_CBP] ("CBP")
 *  4. [DHS_HSI] ("HSI")
 *  5. Other Federal ([DHS_FPS], [DHS_INTEROP] — OFF by default)
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
    val priorityRank: Int
) {
    DHS_ICE("DHS_ICE", "ICE", "ICE", "ICE (ERO Tactical)", isDhs = true, defaultEnabled = true, priorityRank = 1),
    DHS_AMO("DHS_AMO", "DHS Air", "DHS Air", "DHS Air (AMO & Spy Planes)", isDhs = true, defaultEnabled = true, priorityRank = 2),
    DHS_CBP("DHS_CBP", "CBP", "CBP", "CBP (Border Patrol)", isDhs = true, defaultEnabled = true, priorityRank = 3),
    DHS_HSI("DHS_HSI", "HSI", "HSI", "HSI (Investigations)", isDhs = true, defaultEnabled = true, priorityRank = 4),
    DHS_FPS("DHS_FPS", "FPS", "FPS", "FPS (Federal Protective)", isDhs = true, defaultEnabled = false, priorityRank = 5),
    DHS_INTEROP("DHS_INTEROP", "Federal Interop", "Federal Interop", "Federal Interop", isDhs = true, defaultEnabled = false, priorityRank = 5),

    SHERIFF("SHERIFF", "Sheriff", "Sheriff", "Sheriff (PCSO / County)", isDhs = false, defaultEnabled = false, priorityRank = 6),
    POLICE("POLICE", "Police", "Police", "Police (TPD / Municipal)", isDhs = false, defaultEnabled = false, priorityRank = 6),
    DRONE("DRONE", "Drone", "Tactical Drone", "Pro / Enterprise / Military Drones", isDhs = false, defaultEnabled = true, priorityRank = 6),
    FIRE("FIRE", "Fire", "Fire", "Fire (TFD / Wildland)", isDhs = false, defaultEnabled = false, priorityRank = 7),
    SAR("SAR", "SAR", "SAR", "SAR (Search & Rescue)", isDhs = false, defaultEnabled = false, priorityRank = 7);

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
 * Verified Witness Report for a specific agent/unit (`targetKey` or `entityUuid`).
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
    val updatedAtMs: Long = System.currentTimeMillis()
)

object KokohoSignatures {

    private const val PREFS = "kokoho_agency_prefs"
    private const val KEY_ENABLED_AGENCIES = "enabled_agencies_v2"
    private const val KEY_IMPERIAL_UNITS = "imperial_units"
    private const val KEY_AUXNET_ENABLED = "auxnet_enabled"
    private const val KEY_CONTRIBUTE_OBSERVATIONS = "contribute_observations"
    private const val KEY_SHARING_ACTIVE_PILL = "sharing_active_pill"
    private const val KEY_PUSH_NOTIFICATIONS = "push_notifications"
    private const val KEY_SIGNAL_DECAY_MINUTES = "signal_decay_minutes"
    private const val KEY_MAP_TIME_WINDOW = "map_time_window"
    private const val KEY_ADSB_ENABLED = "adsb_enabled"
    private const val KEY_DRONE_ENABLED = "drone_detection_enabled"
    private const val KEY_ENABLED_AIRCRAFT_AGENCIES = "enabled_aircraft_agencies"
    private const val KEY_WHITELISTED_ENTITIES = "whitelisted_entities"
    private const val KEY_WATCHED_ENTITIES = "watched_entities"
    private const val KEY_CUSTOM_ENTITY_PREFIX = "custom_entity_label_"
    private const val KEY_WITNESS_REPORTS_JSON = "witness_reports_json"
    private const val ASSET_FILE = "kokoho_signatures.json"

    /** Monitor view active alert window: 3 minutes. */
    const val MONITOR_ACTIVE_WINDOW_MS = 3 * 60_000L

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
        loaded = true
    }

    fun getEnabledAgencies(context: Context): Set<KokohoAgency> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ENABLED_AGENCIES, null)
        if (saved == null) {
            return KokohoAgency.entries.filter { it.defaultEnabled }.toSet()
        }
        return saved.mapNotNull { KokohoAgency.fromCode(it) }.toSet()
    }

    fun setEnabledAgencies(context: Context, agencies: Set<KokohoAgency>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_ENABLED_AGENCIES, agencies.map { it.id }.toSet())
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
            // Enable primary DHS operational branches by default; keep FPS and Federal Interop opt-in
            current.addAll(KokohoAgency.DHS_AGENCIES.filter { it.defaultEnabled })
        } else {
            current.removeAll(KokohoAgency.DHS_AGENCIES.toSet())
        }
        setEnabledAgencies(context, current)
        return current
    }

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
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WITNESS_REPORTS_JSON, null) ?: return
        runCatching {
            val type = object : TypeToken<List<WitnessReportRecord>>() {}.type
            val list: List<WitnessReportRecord> = Gson().fromJson(raw, type) ?: emptyList()
            val next = mutableMapOf<String, WitnessReportRecord>()
            for (r in list) {
                next[normalizeEntityId(r.targetKey)] = r
                r.entityUuid?.let { next[normalizeEntityId(it)] = r }
            }
            witnessCache = next
        }
    }

    private fun persistWitnessCache(context: Context) {
        val distinct = witnessCache.values.distinctBy { it.targetKey }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_WITNESS_REPORTS_JSON, Gson().toJson(distinct))
        }
    }

    fun getWitnessReport(context: Context, keyOrUuid: String): WitnessReportRecord? {
        ensureLoaded(context)
        val norm = normalizeEntityId(keyOrUuid)
        return witnessCache[norm]
    }

    fun getAllWitnessReports(context: Context): List<WitnessReportRecord> {
        ensureLoaded(context)
        return witnessCache.values.distinctBy { it.targetKey }.sortedByDescending { it.updatedAtMs }
    }

    fun saveWitnessReport(context: Context, report: WitnessReportRecord, syncRemote: Boolean = true) {
        ensureLoaded(context)
        val normKey = normalizeEntityId(report.targetKey)
        val cleaned = report.copy(targetKey = normKey, updatedAtMs = System.currentTimeMillis())
        witnessCache[normKey] = cleaned
        cleaned.entityUuid?.let { witnessCache[normalizeEntityId(it)] = cleaned }
        if (cleaned.agentName.isNotBlank() || cleaned.badgeNumber.isNotBlank()) {
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
        if (syncRemote && isContributeObservationsEnabled(context)) {
            KokohoHubClient.submitWitnessReport(cleaned)
        }
    }

    fun mergeRemoteWitnessReports(context: Context, reports: List<WitnessReportRecord>) {
        if (reports.isEmpty()) return
        ensureLoaded(context)
        var changed = false
        for (r in reports) {
            val norm = normalizeEntityId(r.targetKey)
            val existing = witnessCache[norm]
            if (existing == null || r.updatedAtMs > existing.updatedAtMs) {
                witnessCache[norm] = r
                r.entityUuid?.let { witnessCache[normalizeEntityId(it)] = r }
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

    // ---- Map Time Window ("30m", "24h", "All Time") & Signal Opacity ----

    enum class MapTimeWindow(val id: String, val label: String, val windowMs: Long) {
        MIN_30("30M", "30m", 30 * 60_000L),
        HOURS_24("24H", "24h", 24 * 60 * 60_000L),
        ALL_TIME("ALL", "All Time", 3650L * 24 * 60 * 60_000L);

        companion object {
            fun fromId(id: String?): MapTimeWindow =
                entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL_TIME
        }
    }

    fun getMapTimeWindow(context: Context): MapTimeWindow {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAP_TIME_WINDOW, MapTimeWindow.ALL_TIME.id)
        return MapTimeWindow.fromId(raw)
    }

    fun setMapTimeWindow(context: Context, window: MapTimeWindow) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_MAP_TIME_WINDOW, window.id)
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

    /** Total map visibility lifetime governed by the active MapTimeWindow (30m, 24h, or All Time). */
    fun mapMaxVisibilityMs(context: Context): Long =
        getMapTimeWindow(context).windowMs

    /** Computes opacity multiplier [0.18 .. 1.0] for a map detection of age [ageMs]. */
    fun mapStalenessOpacity(context: Context, ageMs: Long): Float {
        val solidMs = signalDecayMs(context)
        if (ageMs <= solidMs) return 1.0f
        val over = (ageMs - solidMs).toFloat()
        val frac = (over / MAP_FADE_SPAN_MS.toFloat()).coerceIn(0f, 1f)
        val floor = if (getMapTimeWindow(context) == MapTimeWindow.MIN_30) 0.18f else 0.42f
        return (1.0f - (1.0f - floor) * frac).coerceIn(floor, 1.0f)
    }

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

    private val DHS_AIR_TOKEN_REGEX = Regex("\\b(CUSTOMS|BORDER|HOMELAND|CBP|OMAHA|ICE|HSI|AMO|CIRCLING|MILITARY)\\b|AIRCRAFT CIRCLING OVERHEAD")
    private val SHERIFF_AIR_TOKEN_REGEX = Regex("\\b(SHERIFF|PCSO|COCHISE|MARICOPA|PINAL)\\b|PIMA COUNTY")
    private val SAR_AIR_TOKEN_REGEX = Regex("\\b(RESCUE|SAR)\\b|SEARCH AND RESCUE")
    private val FIRE_AIR_TOKEN_REGEX = Regex("\\b(FIRE|USFS|FORESTRY|BLM)\\b|FOREST SERVICE")
    private val POLICE_AIR_TOKEN_REGEX =
        Regex("\\b(POLICE|TPD|TUCSON|TROOPER|DPS|FBI|DEA|MARSHALS|ATF|GOVERNMENT)\\b|HIGHWAY PATROL|STATE POLICE|PUBLIC SAFETY")

    /**
     * Classifies an ADS-B aircraft owner/callsign/registration/behavior into a [KokohoAgency].
     * Unlisted / hex-only aircraft circling overhead (`Aircraft circling overhead`) ALWAYS classify
     * as [KokohoAgency.DHS_AMO] ("DHS Air") so hidden spy planes are never dropped.
     */
    fun classifyAircraftAgency(
        owner: String?,
        registration: String?,
        callsign: String?,
        hitLabel: String?
    ): KokohoAgency? {
        val combined = listOfNotNull(owner, registration, callsign, hitLabel).joinToString(" ").uppercase()
        if (combined.isBlank()) return KokohoAgency.DHS_AMO
        return when {
            SHERIFF_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.SHERIFF
            SAR_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.SAR
            FIRE_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.FIRE
            DHS_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.DHS_AMO
            POLICE_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.POLICE
            owner.isNullOrBlank() && registration.isNullOrBlank() -> KokohoAgency.DHS_AMO
            else -> KokohoAgency.DHS_AMO
        }
    }

    /**
     * Matches an RF frequency in Hz (and optional P25 NAC) against the curated signature catalog.
     * By default (`includeRepeaters = false`), solidly excludes `REPEATER` downlinks and `CONTROL_CHANNEL` trunks.
     */
    fun matchFrequency(
        hz: Long,
        nacHex: String? = null,
        toleranceHz: Long = 6_250L,
        includeRepeaters: Boolean = false
    ): RfChannelSig? {
        val candidates = rfChannels.filter {
            (includeRepeaters || it.isAlertableDirectOrMobile) && abs(it.hz - hz) <= toleranceHz
        }
        if (candidates.isEmpty()) return null
        if (!nacHex.isNullOrBlank()) {
            val clean = nacHex.trim().removePrefix("$").removePrefix("0x").uppercase()
            candidates.firstOrNull { it.nac?.uppercase() == clean }?.let { return it }
        }
        return candidates.minByOrNull { abs(it.hz - hz) }
    }

    /**
     * Matches close-range (`snrDb >= 32`) transmissions inside the NTIA Exclusive Federal VHF
     * (`162.025–173.9875 MHz`, excluding NOAA weather `162.390–162.560 MHz` and known repeaters)
     * or NTIA Exclusive Federal UHF (`406.100–420.000 MHz`) allocations.
     */
    fun matchCloseRangeFederalBand(hz: Long, snrDb: Int, toleranceHz: Long = 6_250L): RfChannelSig? {
        // First check if it's already in the catalog (including repeaters)
        val exactAny = rfChannels.firstOrNull { abs(it.hz - hz) <= toleranceHz }
        if (exactAny != null) {
            // If it's a known repeater or control channel, do NOT treat it as simplex
            return if (exactAny.isAlertableDirectOrMobile) exactAny else null
        }
        if (snrDb < 32) return null
        // Exclude NOAA Weather Radio (162.400 - 162.550 MHz)
        if (hz in 162_385_000L..162_565_000L) return null
        val mhzStr = "%.4f".format(java.util.Locale.US, hz / 1_000_000.0)
        return when {
            hz in 162_025_000L..173_987_500L -> RfChannelSig(
                hz = hz,
                nac = null,
                mode = "P25",
                alpha = "FED-VHF-$mhzStr",
                description = "NTIA Federal VHF Close Tactical Simplex ($mhzStr MHz)",
                agency = "DHS_ICE",
                role = "DIRECT_TACTICAL"
            )
            hz in 406_100_000L..420_000_000L -> RfChannelSig(
                hz = hz,
                nac = null,
                mode = "P25",
                alpha = "FED-UHF-$mhzStr",
                description = "NTIA Federal UHF Close Tactical Simplex ($mhzStr MHz)",
                agency = "DHS_FPS",
                role = "DIRECT_TACTICAL"
            )
            else -> null
        }
    }

    /** Matches a P25 talkgroup ID against the curated talkgroup ranges (PCWIN TPD/PCSO/Fire/SAR & Federal). */
    fun matchTalkgroup(tg: Int): TalkgroupSig? =
        talkgroups.firstOrNull { tg in it.minTg..it.maxTg }

    private val IGNORED_INFRA_MAC_PREFIXES = listOf(
        "00:30:44:55:", // City of Tucson "rmat" Regional Metropolitan Arterial Traffic signal cabinets
        "06:30:44:55:", // AMR / GMR ambulance ePCR ("GMREPCR") & arterial traffic cabinet virtual APs
        "04:F0:21:",    // Compex Systems municipal intersection V2X / traffic backhaul modules
        "24:B2:B9:"     // Flock Safety / Liteon ALPR cameras (collected in local DB, never shown on maps)
    )

    private val IGNORED_INFRA_OR_RETAIL_SSID_REGEX = Regex(
        "(?i)^\\s*(rmat|GMREPCR|SunTran|SunLink|Chill N NW|Tap4G Backup|RAI-SIGNAGE|H&R Block IoT|Saladandgo|GoEnergy.*|HCI Mobile|MWMBackUp|Tucsonmemorials|E110-.*|IBR200-.*|IBR600-.*|CBA850.*|W1850.*|R980-.*|FX3100.*|FX4100.*)\\s*$"
    )

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

    /**
     * Returns true if a MAC / SSID belongs to a known municipal traffic-signal cabinet (`"rmat"`),
     * intersection V2X bridge, ambulance ePCR, Flock camera, or fixed commercial/retail POS backup router.
     */
    fun isIgnoredInfrastructureOrRetail(mac: String, ssidOrName: String?): Boolean {
        if (isFlockCamera(mac, ssidOrName)) return true
        val normMac = mac.trim().uppercase()
        if (IGNORED_INFRA_MAC_PREFIXES.any { normMac.startsWith(it) }) return true
        val ssid = ssidOrName?.trim().orEmpty()
        if (ssid.isNotEmpty() && IGNORED_INFRA_OR_RETAIL_SSID_REGEX.matches(ssid)) return true
        return false
    }

    /** Matches a Wi-Fi / BLE MAC address against curated fleet router / tactical / drone OUI prefixes. */
    fun matchOui(mac: String, ssidOrName: String? = null): FleetOuiSig? {
        if (isIgnoredInfrastructureOrRetail(mac, ssidOrName)) return null
        val norm = mac.trim().uppercase()
        return fleetOuis.firstOrNull { norm.startsWith(it.prefix.uppercase()) }
    }

    private val TACTICAL_DRONE_OUIs = setOf(
        "38:1D:14", // Skydio (X2 / X10 Tactical)
        "D8:80:39", // Teal Drones (Golden Eagle sUAS)
        "90:03:B7", "A0:14:3D", "00:12:1C", "00:26:7E", // Parrot (Anafi USA / Gov)
        "EC:5B:73", "18:D7:93", // Autel Robotics (EVO II Dual Enterprise)
        "60:60:1F", "48:1C:B9", "34:D2:62", "04:A8:5A", // DJI Enterprise (Matrice / Mavic Enterprise)
        "FA:0B:BC"  // ASTM F3411 Wi-Fi Beacon / NAN Remote ID
    )

    private val TACTICAL_DRONE_TOKEN_REGEX =
        Regex("(?i)\\b(SKYDIO|ANAFI|BRINC|LEMUR|TEAL|AUTEL|MATRICE|MAVIC\\s*3E|MAVIC\\s*3T|REMOTE\\s*ID)\\b")

    /** Returns true if a device is a Pro / Enterprise / Military / Remote ID drone. */
    fun isTacticalOrRemoteIdDrone(
        mac: String,
        ssidOrName: String?,
        vendor: String?,
        hasRemoteId: Boolean
    ): Boolean {
        if (hasRemoteId) return true
        val prefix = mac.trim().uppercase().take(8)
        if (prefix in TACTICAL_DRONE_OUIs) return true
        val combined = "${ssidOrName.orEmpty()} ${vendor.orEmpty()}"
        return TACTICAL_DRONE_TOKEN_REGEX.containsMatchIn(combined)
    }

    private val LAW_ENFORCEMENT_SSID_REGEX =
        Regex("(?i)\\b(TUCSON_PD|TPD_|ANTI_GANG|SWAT|NARCOTICS)\\b")
    private val SHERIFF_SSID_REGEX =
        Regex("(?i)\\b(PCSO|PIMA_SHERIFF|SHERIFF_TAC)\\b")
    private val TACTICAL_BLE_BEACON_REGEX =
        Regex("^\\s*(DR|QT)\\s+[0-9A-Fa-f]{8}\\s*$")

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
            if (LAW_ENFORCEMENT_SSID_REGEX.containsMatchIn(cleanSsid)) {
                return KokohoAgency.POLICE to KokohoAgency.POLICE.friendlyLabel
            }
            if (SHERIFF_SSID_REGEX.containsMatchIn(cleanSsid)) {
                return KokohoAgency.SHERIFF to KokohoAgency.SHERIFF.friendlyLabel
            }
            if (TACTICAL_BLE_BEACON_REGEX.matches(cleanSsid) &&
                vendor?.contains("Microchip", ignoreCase = true) == true
            ) {
                return KokohoAgency.DHS_HSI to KokohoAgency.DHS_HSI.friendlyLabel
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
        if (combined.contains("Peplink", ignoreCase = true) && cleanSsid.isEmpty()) {
            return KokohoAgency.DHS_HSI to KokohoAgency.DHS_HSI.friendlyLabel
        }
        return null
    }

    /**
     * Returns true ONLY for devices Kokoho cares about processing:
     *  1. Curated agency fleet/tactical OUI or SSID (Cradlepoint, Sierra AirLink, Motorola APX, Axon)
     *  2. Pro / Enterprise / Military / Remote ID Drones (Skydio, Parrot Anafi, Autel, Teal, BRINC, DJI Enterprise)
     *  3. Lightweight BLE tracker candidates (Apple FindMy / AirTag, Samsung SmartTag, Tile) for tracker-following detection.
     * Everything else (consumer Wi-Fi routers, iPhones, AirPods, TVs, cameras, etc.) is dropped immediately.
     */
    fun isRelevantBleOrWifiCandidate(
        mac: String,
        nameOrSsid: String?,
        vendor: String?,
        hasRemoteId: Boolean,
        isBleTrackerPayload: Boolean
    ): Boolean {
        if (hasRemoteId || isBleTrackerPayload) return true
        return matchOuiOrSsid(mac, nameOrSsid, vendor, hasRemoteId = false) != null
    }

    /**
     * Active watched targets for RTL-SDR RadioWatch based on the user's enabled agencies.
     * Solidly excludes `REPEATER` downlinks and `CONTROL_CHANNEL` trunks so the SDR only sweeps
     * Direct Tactical Simplex (`DIRECT_TACTICAL`), Mobile Uplinks (`MOBILE_INPUT`), and Air Assets (`AIR_ASSET`).
     */
    fun watchedTargetsForEnabled(context: Context): List<RadioWatch.Target> {
        ensureLoaded(context)
        val enabled = getEnabledAgencies(context)
        return rfChannels
            .filter { it.agencyEnum in enabled && it.isAlertableDirectOrMobile }
            .distinctBy { it.hz }
            .map { RadioWatch.Target(it.hz, it.agencyEnum.friendlyLabel) }
    }

    /** Programmatic channel import helper for tests and regional catalog provisioning. */
    fun importChannelText(context: Context, text: String, sourceName: String): Int {
        ensureLoaded(context)
        val trimmed = text.trim()
        val airRegex = Regex("""(?i)\b(AIR\s*\d+)\s*[-–:]\s*([0-9]{2,4}\.[0-9]{1,6})\s*MHz(?:.*?NAC\s*([0-9A-Fa-f]{1,3}))?""")
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
