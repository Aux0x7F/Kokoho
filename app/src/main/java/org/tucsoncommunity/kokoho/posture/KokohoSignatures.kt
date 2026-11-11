package org.tucsoncommunity.kokoho.posture

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import org.tucsoncommunity.kokoho.sdr.FreqNames
import org.tucsoncommunity.kokoho.sdr.RadioWatch
import org.tucsoncommunity.kokoho.util.Units
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * Hierarchical agency taxonomy for Kokoho:
 *  - Master group DHS is ON by default, containing [DHS_CBP], [DHS_ICE], [DHS_HSI],
 *    [DHS_AMO], [DHS_FPS], and [DHS_INTEROP].
 *  - Additional public-safety categories ([POLICE], [SHERIFF], [FIRE], [SAR]) are OFF by default
 *    and independently selectable in Settings.
 */
enum class KokohoAgency(
    val id: String,
    val shortName: String,
    val friendlyLabel: String,
    val fullName: String,
    val isDhs: Boolean,
    val defaultEnabled: Boolean
) {
    DHS_CBP("DHS_CBP", "CBP", "CBP", "CBP (Border Patrol)", isDhs = true, defaultEnabled = true),
    DHS_ICE("DHS_ICE", "ICE", "ICE", "ICE (ERO Tactical)", isDhs = true, defaultEnabled = true),
    DHS_HSI("DHS_HSI", "HSI", "HSI", "HSI (Investigations)", isDhs = true, defaultEnabled = true),
    DHS_AMO("DHS_AMO", "AMO", "DHS Air", "DHS Air & Marine (AMO)", isDhs = true, defaultEnabled = true),
    DHS_FPS("DHS_FPS", "FPS", "FPS", "FPS (Federal Protective)", isDhs = true, defaultEnabled = true),
    DHS_INTEROP("DHS_INTEROP", "Federal Interop", "Federal Interop", "Federal Interop", isDhs = true, defaultEnabled = true),

    POLICE("POLICE", "Police", "Police", "Police (TPD / Municipal)", isDhs = false, defaultEnabled = false),
    SHERIFF("SHERIFF", "Sheriff", "Sheriff", "Sheriff (PCSO / County)", isDhs = false, defaultEnabled = false),
    FIRE("FIRE", "Fire", "Fire", "Fire (TFD / Wildland)", isDhs = false, defaultEnabled = false),
    SAR("SAR", "SAR", "SAR", "SAR (Search & Rescue)", isDhs = false, defaultEnabled = false);

    companion object {
        val DHS_AGENCIES = entries.filter { it.isDhs }
        val OPTIONAL_AGENCIES = entries.filter { !it.isDhs }

        fun fromCode(code: String?): KokohoAgency? {
            if (code.isNullOrBlank()) return null
            val norm = code.trim().uppercase()
            return when (norm) {
                "CBP", "DHS_CBP", "BP" -> DHS_CBP
                "ICE", "DHS_ICE", "ERO" -> DHS_ICE
                "HSI", "DHS_HSI" -> DHS_HSI
                "AMO", "DHS_AMO", "DHS AIR", "DHS_AIR" -> DHS_AMO
                "FPS", "DHS_FPS" -> DHS_FPS
                "DHS", "DHS_INTEROP", "FEDERAL", "FEDERAL INTEROP" -> DHS_INTEROP
                "POLICE", "TPD" -> POLICE
                "SHERIFF", "PCSO", "SHERRIF" -> SHERIFF
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

object KokohoSignatures {

    private const val PREFS = "kokoho_agency_prefs"
    private const val KEY_ENABLED_AGENCIES = "enabled_agencies"
    private const val KEY_IMPERIAL_UNITS = "imperial_units"
    private const val KEY_AUXNET_ENABLED = "auxnet_enabled"
    private const val KEY_CONTRIBUTE_OBSERVATIONS = "contribute_observations"
    private const val KEY_PUSH_NOTIFICATIONS = "push_notifications"
    private const val KEY_SIGNAL_DECAY_MINUTES = "signal_decay_minutes"
    private const val KEY_ADSB_ENABLED = "adsb_enabled"
    private const val KEY_ENABLED_AIRCRAFT_AGENCIES = "enabled_aircraft_agencies"
    private const val KEY_WHITELISTED_ENTITIES = "whitelisted_entities"
    private const val KEY_WATCHED_ENTITIES = "watched_entities"
    private const val KEY_CUSTOM_ENTITY_PREFIX = "custom_entity_label_"
    private const val ASSET_FILE = "kokoho_signatures.json"

    const val DEFAULT_DECAY_MINUTES = 30
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
        val schemaVersion: Int = 1,
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
            current.addAll(KokohoAgency.DHS_AGENCIES)
        } else {
            current.removeAll(KokohoAgency.DHS_AGENCIES.toSet())
        }
        setEnabledAgencies(context, current)
        return current
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

    fun isContributeObservationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONTRIBUTE_OBSERVATIONS, true)

    fun setContributeObservationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_CONTRIBUTE_OBSERVATIONS, enabled)
        }
        if (!enabled) {
            KokohoHubClient.purgeContributedObservations(context)
        }
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

    // ---- Local-Only Entity Overrides (Custom Classification, Whitelist, Watch) ----

    fun normalizeEntityId(rawId: String): String =
        rawId.removePrefix("kokoho:").trim()

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

    // ---- Client Signal Decay Window (Default 30 min) ----

    fun signalDecayMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SIGNAL_DECAY_MINUTES, DEFAULT_DECAY_MINUTES)
            .coerceIn(5, 240)

    fun setSignalDecayMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_SIGNAL_DECAY_MINUTES, minutes.coerceIn(5, 240))
        }
    }

    fun signalDecayMs(context: Context): Long =
        signalDecayMinutes(context) * 60_000L

    // ---- ADS-B Aircraft Options (DHS on by default, TPD/Sheriff/SAR/Fire optional) ----

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
            // Default ON for all DHS aircraft; optional for Police, Sheriff, SAR, Fire
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

    private val DHS_AIR_TOKEN_REGEX = Regex("\\b(CUSTOMS|BORDER|HOMELAND|CBP|OMAHA|ICE|HSI|AMO)\\b")
    private val SHERIFF_AIR_TOKEN_REGEX = Regex("\\b(SHERIFF|PCSO|COCHISE|MARICOPA|PINAL)\\b|PIMA COUNTY")
    private val SAR_AIR_TOKEN_REGEX = Regex("\\b(RESCUE|SAR)\\b|SEARCH AND RESCUE")
    private val FIRE_AIR_TOKEN_REGEX = Regex("\\b(FIRE|USFS|FORESTRY|BLM)\\b|FOREST SERVICE")
    private val POLICE_AIR_TOKEN_REGEX =
        Regex("\\b(POLICE|TPD|TUCSON|TROOPER|DPS|FBI|DEA|MARSHALS|ATF|GOVERNMENT)\\b|HIGHWAY PATROL|STATE POLICE|PUBLIC SAFETY")

    /** Classifies an ADS-B aircraft owner/callsign/registration into a [KokohoAgency]. */
    fun classifyAircraftAgency(
        owner: String?,
        registration: String?,
        callsign: String?,
        hitLabel: String?
    ): KokohoAgency? {
        val combined = listOfNotNull(owner, registration, callsign, hitLabel).joinToString(" ").uppercase()
        if (combined.isBlank()) return null
        return when {
            DHS_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.DHS_AMO
            SHERIFF_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.SHERIFF
            SAR_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.SAR
            FIRE_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.FIRE
            POLICE_AIR_TOKEN_REGEX.containsMatchIn(combined) -> KokohoAgency.POLICE
            else -> null
        }
    }

    /** Matches an RF frequency in Hz (and optional P25 NAC) against the curated signature catalog. */
    fun matchFrequency(hz: Long, nacHex: String? = null, toleranceHz: Long = 6_250L): RfChannelSig? {
        val candidates = rfChannels.filter { abs(it.hz - hz) <= toleranceHz }
        if (candidates.isEmpty()) return null
        if (!nacHex.isNullOrBlank()) {
            val clean = nacHex.trim().removePrefix("$").removePrefix("0x").uppercase()
            candidates.firstOrNull { it.nac?.uppercase() == clean }?.let { return it }
        }
        return candidates.minByOrNull { abs(it.hz - hz) }
    }

    /** Matches a P25 talkgroup ID against the curated talkgroup ranges (PCWIN TPD/PCSO/Fire/SAR & Federal). */
    fun matchTalkgroup(tg: Int): TalkgroupSig? =
        talkgroups.firstOrNull { tg in it.minTg..it.maxTg }

    /** Matches a Wi-Fi / BLE MAC address against curated fleet router / tactical OUI prefixes. */
    fun matchOui(mac: String): FleetOuiSig? {
        val norm = mac.trim().uppercase()
        return fleetOuis.firstOrNull { norm.startsWith(it.prefix.uppercase()) }
    }

    /** Matches a Wi-Fi / BLE MAC address or SSID/vendor against curated fleet router / tactical signatures. */
    fun matchOuiOrSsid(mac: String, ssid: String?, vendor: String?): Pair<KokohoAgency, String>? {
        matchOui(mac)?.let { return it.agencyEnum to it.friendlyLabel }
        val combined = "${ssid.orEmpty()} ${vendor.orEmpty()}"
        if (combined.contains("Cradlepoint", ignoreCase = true) || combined.contains("IBR900", ignoreCase = true) || combined.contains("IBR1700", ignoreCase = true)) {
            return KokohoAgency.DHS_CBP to KokohoAgency.DHS_CBP.friendlyLabel
        }
        if (combined.contains("Sierra Wireless", ignoreCase = true) || combined.contains("AirLink", ignoreCase = true)) {
            return KokohoAgency.DHS_CBP to KokohoAgency.DHS_CBP.friendlyLabel
        }
        return null
    }

    /** Active watched targets for RTL-SDR RadioWatch based on the user's enabled agencies. */
    fun watchedTargetsForEnabled(context: Context): List<RadioWatch.Target> {
        ensureLoaded(context)
        val enabled = getEnabledAgencies(context)
        return rfChannels
            .filter { it.agencyEnum in enabled && it.role != "CONTROL_CHANNEL" }
            .distinctBy { it.hz }
            .map { RadioWatch.Target(it.hz, it.agencyEnum.friendlyLabel) }
    }

    /**
     * Imports a RadioReference TSV/CSV paste or SDRTrunk XML playlist into [FreqNames]
     * and adds any custom frequencies to the runtime [rfChannels] catalog.
     */
    fun importChannelText(context: Context, text: String, sourceName: String): Int {
        ensureLoaded(context)
        val trimmed = text.trim()
        if (trimmed.startsWith("<") && trimmed.contains("<playlist")) {
            return importSdrTrunkXml(context, trimmed, sourceName)
        }
        val addedToCatalog = parseRadioReferenceLinesIntoCatalog(trimmed, sourceName)
        val csvCount = FreqNames.importCsv(context, trimmed, sourceName)
        return maxOf(addedToCatalog, csvCount)
    }

    private fun parseRadioReferenceLinesIntoCatalog(text: String, sourceName: String): Int {
        val airRegex = Regex("""(?i)\b(AIR\s*\d+)\s*[-–:]\s*([0-9]{2,4}\.[0-9]{1,6})\s*MHz(?:.*?NAC\s*([0-9A-Fa-f]{1,3}))?""")
        val nacRegex = Regex("""(?i)\b([0-9A-Fa-f]{1,3})\s*NAC\b""")
        val freqStartRegex = Regex("""^\s*([0-9]{2,4}\.[0-9]{2,6})\b""")
        val updated = rfChannels.toMutableList()
        var count = 0

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val airMatch = airRegex.find(line)
            if (airMatch != null) {
                val alpha = airMatch.groupValues[1].uppercase().replace(Regex("\\s+"), " ")
                val mhz = airMatch.groupValues[2].toDoubleOrNull() ?: continue
                val hz = (mhz * 1_000_000.0).toLong()
                val nac = airMatch.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.uppercase()
                updated.add(
                    RfChannelSig(
                        hz = hz,
                        nac = nac,
                        mode = "P25",
                        alpha = alpha,
                        description = "$alpha ($sourceName)",
                        agency = "DHS_AMO",
                        role = "AIR_ASSET"
                    )
                )
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
            updated.add(
                RfChannelSig(
                    hz = hz,
                    nac = nac,
                    mode = "P25",
                    alpha = alpha,
                    description = desc,
                    agency = agency,
                    role = "REPEATER"
                )
            )
            count++
        }
        if (count > 0) {
            rfChannels = updated
        }
        return count
    }

    private fun importSdrTrunkXml(context: Context, xml: String, sourceName: String): Int {
        val freqBlockRegex = Regex("""(?is)<channel\b[^>]*>(.*?)</channel>""")
        val freqValRegex = Regex("""(?is)<frequency>([0-9,\s]+)</frequency>""")
        val nameRegex = Regex("""(?i)\bname="([^"]+)"""")
        val sysRegex = Regex("""(?i)\bsystem="([^"]+)"""")
        val updated = rfChannels.toMutableList()
        val csvBuilder = StringBuilder("Frequency,Alpha Tag,Description\n")
        var count = 0

        for (block in freqBlockRegex.findAll(xml)) {
            val content = block.value
            val name = nameRegex.find(content)?.groupValues?.get(1) ?: "SDRTrunk"
            val sys = sysRegex.find(content)?.groupValues?.get(1) ?: sourceName
            val freqs = freqValRegex.find(content)?.groupValues?.get(1).orEmpty()
                .split(',', ' ', '\n', '\r', '\t')
                .mapNotNull { it.trim().toLongOrNull() }
            for (hz in freqs) {
                if (hz <= 0L) continue
                val mhzStr = "%.5f".format(java.util.Locale.US, hz / 1_000_000.0)
                csvBuilder.append("$mhzStr,$name,$sys\n")
                val combined = "$name $sys".uppercase()
                val agency = when {
                    combined.contains("ICE") -> "DHS_ICE"
                    combined.contains("HSI") -> "DHS_HSI"
                    combined.contains("AMO") || combined.contains("AIR") -> "DHS_AMO"
                    combined.contains("CBP") || combined.contains("BORDER") -> "DHS_CBP"
                    combined.contains("SHERIFF") || combined.contains("PCSO") -> "SHERIFF"
                    combined.contains("FIRE") || combined.contains("TFD") -> "FIRE"
                    combined.contains("SAR") -> "SAR"
                    combined.contains("POLICE") || combined.contains("TPD") -> "POLICE"
                    else -> "DHS_INTEROP"
                }
                updated.add(
                    RfChannelSig(
                        hz = hz,
                        nac = null,
                        mode = "P25",
                        alpha = name,
                        description = sys,
                        agency = agency,
                        role = "REPEATER"
                    )
                )
                count++
            }
        }
        if (count > 0) {
            rfChannels = updated
            FreqNames.importCsv(context, csvBuilder.toString(), sourceName)
        }
        return count
    }
}
