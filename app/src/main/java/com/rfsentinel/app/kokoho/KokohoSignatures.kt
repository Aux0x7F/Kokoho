package com.rfsentinel.app.kokoho

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.rfsentinel.app.sdr.FreqNames
import com.rfsentinel.app.sdr.RadioWatch
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * Hierarchical agency taxonomy for Kokoho:
 *  - Master group [MasterGroup.DHS] is ON by default, containing [DHS_CBP], [DHS_ICE], [DHS_HSI],
 *    [DHS_AMO], [DHS_FPS], and [DHS_INTEROP].
 *  - Additional public-safety categories ([POLICE], [SHERIFF], [FIRE], [SAR]) are OFF by default
 *    and independently selectable.
 */
enum class KokohoAgency(
    val id: String,
    val shortName: String,
    val fullName: String,
    val isDhs: Boolean,
    val defaultEnabled: Boolean
) {
    DHS_CBP("DHS_CBP", "CBP", "DHS · CBP (Border Patrol)", isDhs = true, defaultEnabled = true),
    DHS_ICE("DHS_ICE", "ICE", "DHS · ICE (ERO Tactical)", isDhs = true, defaultEnabled = true),
    DHS_HSI("DHS_HSI", "HSI", "DHS · HSI (Investigations)", isDhs = true, defaultEnabled = true),
    DHS_AMO("DHS_AMO", "AMO", "DHS · AMO (Air & Marine)", isDhs = true, defaultEnabled = true),
    DHS_FPS("DHS_FPS", "FPS", "DHS · FPS (Federal Protective)", isDhs = true, defaultEnabled = true),
    DHS_INTEROP("DHS_INTEROP", "DHS Interop", "DHS · Federal Interop", isDhs = true, defaultEnabled = true),

    POLICE("POLICE", "Police", "Police (TPD / Municipal)", isDhs = false, defaultEnabled = false),
    SHERIFF("SHERIFF", "Sheriff", "Sheriff (PCSO / County)", isDhs = false, defaultEnabled = false),
    FIRE("FIRE", "Fire", "Fire (TFD / Wildland)", isDhs = false, defaultEnabled = false),
    SAR("SAR", "SAR", "SAR (Search & Rescue)", isDhs = false, defaultEnabled = false);

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
                "AMO", "DHS_AMO" -> DHS_AMO
                "FPS", "DHS_FPS" -> DHS_FPS
                "DHS", "DHS_INTEROP", "FEDERAL" -> DHS_INTEROP
                "POLICE", "TPD" -> POLICE
                "SHERIFF", "PCSO", "SHERRIF" -> SHERIFF
                "FIRE", "TFD" -> FIRE
                "SAR" -> SAR
                else -> entries.firstOrNull { it.id == norm || it.shortName.uppercase() == norm }
            }
        }

        fun fromId(code: String?): KokohoAgency? = fromCode(code)
    }
}

object KokohoSignatures {

    private const val PREFS = "kokoho_agency_prefs"
    private const val KEY_ENABLED_AGENCIES = "enabled_agencies"
    private const val ASSET_FILE = "kokoho_signatures.json"

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
    }

    data class TalkgroupSig(
        val minTg: Int = 0,
        val maxTg: Int = 0,
        val agency: String = "POLICE",
        val label: String = ""
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agency) ?: KokohoAgency.POLICE
    }

    data class FleetOuiSig(
        val prefix: String = "",
        val agency: String = "DHS_CBP",
        val label: String = "",
        val confidence: Int = 75
    ) {
        val agencyEnum: KokohoAgency get() = KokohoAgency.fromCode(agency) ?: KokohoAgency.DHS_CBP
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
        matchOui(mac)?.let { return it.agencyEnum to it.label }
        val combined = "${ssid.orEmpty()} ${vendor.orEmpty()}"
        if (combined.contains("Cradlepoint", ignoreCase = true) || combined.contains("IBR900", ignoreCase = true) || combined.contains("IBR1700", ignoreCase = true)) {
            return KokohoAgency.DHS_CBP to "Cradlepoint Fleet Router (${ssid ?: mac})"
        }
        if (combined.contains("Sierra Wireless", ignoreCase = true) || combined.contains("AirLink", ignoreCase = true)) {
            return KokohoAgency.DHS_CBP to "Sierra Wireless AirLink (${ssid ?: mac})"
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
            .map { RadioWatch.Target(it.hz, "${it.alpha} (${it.agencyEnum.shortName})") }
    }

    /**
     * Imports a RadioReference TSV/CSV paste or SDRTrunk XML playlist into [FreqNames]
     * and adds any custom frequencies to the runtime catalog.
     */
    fun importChannelText(context: Context, text: String, sourceName: String): Int {
        val trimmed = text.trim()
        if (trimmed.startsWith("<") && trimmed.contains("<playlist")) {
            return importSdrTrunkXml(context, trimmed, sourceName)
        }
        return FreqNames.importCsv(context, trimmed, sourceName)
    }

    private fun importSdrTrunkXml(context: Context, xml: String, sourceName: String): Int {
        val freqRegex = Regex("""<frequency[^>]*>([0-9.]+)</frequency>""")
        val systemRegex = Regex("""system="([^"]+)"""")
        val nameRegex = Regex("""name="([^"]+)"""")
        val channelBlocks = Regex("""<channel\b[^>]*>(.*?)</channel>""", RegexOption.DOT_MATCHES_ALL)
        val csvLines = mutableListOf("Frequency,Alpha Tag,Description")
        var count = 0
        for (m in channelBlocks.findAll(xml)) {
            val block = m.value
            val sys = systemRegex.find(block)?.groupValues?.get(1) ?: sourceName
            val name = nameRegex.find(block)?.groupValues?.get(1) ?: "Channel"
            for (f in freqRegex.findAll(block)) {
                val hz = f.groupValues[1].toLongOrNull() ?: continue
                val mhz = "%.4f".format(java.util.Locale.US, hz / 1e6)
                csvLines += "$mhz,\"$name\",\"$sys\""
                count++
            }
        }
        if (count > 0) {
            FreqNames.importCsv(context, csvLines.joinToString("\n"), sourceName)
        }
        return count
    }
}
