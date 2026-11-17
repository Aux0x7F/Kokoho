package org.tucsoncommunity.kokoho.posture

import android.content.Context
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Arrays
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * High-speed, low-memory offline reference lookup tables compiled by `tools/compile_reference_tables.py`
 * from official public-domain & open-source registries:
 *  1. `reference/aircraft_meta.tsv.gz` — 220k+ Mode-S hexes (FAA MASTER + ACFTREF + plane-alert-db + tar1090-db)
 *  2. `reference/az_airfields.tsv` — 968 AZ/Sonora airports, runways, gliderports, & hospital helipads (OurAirports)
 *  3. `reference/az_fcc_rf.tsv.gz` — 4,540 Active AZ FCC ULS Land-Mobile (`l_LMpriv` + `l_LMcomm`) + DHS NIFOG channels
 *  4. `reference/ieee_subblocks.tsv.gz` — 7,207 IEEE 36-bit (MA-S), 28-bit (MA-M), & tactical 24-bit (MA-L) blocks
 *  5. `reference/az_cells.tsv` — Surveyed Southern AZ LTE/NR carrier cell ground truth
 */
object KokohoReferenceTables {

    data class AircraftRef(
        val hex: String,
        val registration: String,
        val icaoType: String,
        /** 'H' = Helicopter/Rotorcraft, 'F' = Fixed-Wing, 'G' = Glider, 'L' = Lighter-than-air, 'U' = UAS/Ultralight */
        val airframeKind: Char,
        /** 'G' = Government, 'C' = Corp, 'L' = LLC, 'I' = Individual, 'P' = Privacy ICAO Address (PIA) */
        val registrantKind: Char,
        /** "LE", "GOV", "MED", "TRN", "AIR", "MIL", or "CIV" */
        val disposition: String,
        val agencyHint: KokohoAgency?,
        val owner: String
    ) {
        val isExcludedNonThreat: Boolean
            get() = disposition == "MED" || disposition == "TRN" || disposition == "AIR" || disposition == "MIL"
        val isExplicitLeOrGov: Boolean
            get() = disposition == "LE" || disposition == "GOV"
    }

    data class AirfieldRef(
        val ident: String,
        val kind: String,
        val lat: Double,
        val lon: Double,
        val elevFt: Int,
        val maxRunwayFt: Int,
        val isHospitalHelipad: Boolean,
        val name: String
    )

    data class AzRfRef(
        val freqHz: Long,
        /** "IG_COMM", "PS_REP", "PS_MOB", "NIFOG_TAC", "NIFOG_IN", "NIFOG_REP" */
        val cls: String,
        val agency: String,
        val mode: String,
        val callsign: String,
        val licensee: String,
        val lat: Double?,
        val lon: Double?
    ) {
        val isSuppressedCommercialOrRepeater: Boolean
            get() = cls == "IG_COMM" || cls == "PS_REP" || cls == "NIFOG_REP"
        val isAlertableMobileOrTactical: Boolean
            get() = cls == "PS_MOB" || cls == "NIFOG_TAC" || cls == "NIFOG_IN"
    }

    data class MacSubblockRef(
        val prefixHex: String,
        val bitLen: Int,
        val role: String,
        val agency: KokohoAgency?,
        val vendor: String
    )

    data class AzCellRef(
        val idKey: String,
        val area: Int,
        val lat: Double?,
        val lon: Double?,
        val isFieldSurvey: Boolean
    )

    data class TalkgroupRef(
        val system: String,
        val tgDec: Int,
        /** "POLICE", "SHERIFF", "FIRE", "SAR", "DHS_ICE", "DHS_HSI", "DHS_CBP", "DHS_FPS", "DHS_INTEROP", or "IGNORE" */
        val agencyCode: String,
        val mode: String,
        val alphaTag: String,
        val description: String
    )

    @Volatile
    private var loaded = false

    private var aircraftHexSorted = IntArray(0)
    private var aircraftPacked = IntArray(0)
    private var aircraftStrings = emptyArray<String>()

    private var airfieldsByGrid = emptyMap<Long, List<AirfieldRef>>()
    private var allAirfields = emptyList<AirfieldRef>()

    private var rfFreqSorted = LongArray(0)
    private var rfEntries = emptyArray<AzRfRef>()

    private var macSubblocks = emptyMap<String, MacSubblockRef>()

    private val CELL_RAT_NAMES = arrayOf("GSM", "CDMA", "WCDMA", "TDSCDMA", "LTE", "NR")
    private var cellKeySorted = LongArray(0)
    private var cellAreaAndSurvey = IntArray(0)
    private var cellLatE4 = IntArray(0)
    private var cellLonE4 = IntArray(0)
    private var surveyCellsMap = emptyMap<String, AzCellRef>()

    private var talkgroupsByDec = emptyMap<Int, List<TalkgroupRef>>()
    private var totalTalkgroupRows = 0

    private val DISP_TABLE = arrayOf("CIV", "LE", "GOV", "MED", "TRN", "AIR", "MIL")
    private val KIND_CHARS = charArrayOf('F', 'H', 'G', 'L', 'U')
    private val REG_CHARS = charArrayOf('I', 'C', 'L', 'G', 'P')
    private val AGENCY_TABLE: Array<KokohoAgency?> = arrayOf(
        null,
        KokohoAgency.DHS_ICE,
        KokohoAgency.DHS_AMO,
        KokohoAgency.DHS_CBP,
        KokohoAgency.DHS_HSI,
        KokohoAgency.DHS_FPS,
        KokohoAgency.DHS_INTEROP,
        KokohoAgency.POLICE,
        KokohoAgency.SHERIFF,
        KokohoAgency.DRONE,
        KokohoAgency.FIRE,
        KokohoAgency.SAR
    )

    val isLoaded: Boolean get() = loaded

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext ?: context
        runCatching { loadAirfields(app) }
        runCatching { loadAzRf(app) }
        runCatching { loadAzTalkgroups(app) }
        runCatching { loadIeeeSubblocks(app) }
        runCatching { loadAzCells(app) }
        runCatching { loadAircraftMeta(app) }
        loaded = true
    }

    private fun packAircraftFlags(kind: Char, regKind: Char, disp: String, agency: String): Int {
        val kIdx = KIND_CHARS.indexOf(kind).let { if (it >= 0) it else 0 }
        val rIdx = REG_CHARS.indexOf(regKind).let { if (it >= 0) it else 0 }
        val dIdx = DISP_TABLE.indexOf(disp).let { if (it >= 0) it else 0 }
        val ag = KokohoAgency.fromCode(agency)
        val aIdx = if (ag == null) 0 else AGENCY_TABLE.indexOf(ag).coerceAtLeast(0)
        return (kIdx and 0x7) or ((rIdx and 0x7) shl 3) or ((dIdx and 0xF) shl 6) or ((aIdx and 0xF) shl 10)
    }

    private fun openReferenceReader(context: Context, pathWithGz: String, bufferBytes: Int = 32768): BufferedReader {
        val rawStream: InputStream = runCatching { context.assets.open(pathWithGz) }.getOrElse {
            context.assets.open(pathWithGz.removeSuffix(".gz"))
        }
        val pb = BufferedInputStream(rawStream, 8192)
        pb.mark(4)
        val b0 = pb.read()
        val b1 = pb.read()
        pb.reset()
        val decoded: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(pb) else pb
        return BufferedReader(InputStreamReader(decoded, Charsets.UTF_8), bufferBytes)
    }

    private fun loadAircraftMeta(context: Context) {
        val hexList = IntArray(240_000)
        val packList = IntArray(240_000)
        val strList = arrayOfNulls<String>(240_000)
        var n = 0
        openReferenceReader(context, "reference/aircraft_meta.tsv.gz", 65536).use { br ->
            var line = br.readLine()
            while (line != null) {
                if (line.isNotEmpty() && line[0] != '#') {
                    val t1 = line.indexOf('\t')
                    if (t1 == 6) {
                        val hxInt = line.substring(0, 6).toIntOrNull(16)
                        if (hxInt != null && n < hexList.size) {
                            val parts = line.split('\t')
                            if (parts.size >= 8) {
                                val kind = parts[3].firstOrNull() ?: 'F'
                                val regKind = parts[4].firstOrNull() ?: 'I'
                                val disp = parts[5]
                                val agStr = parts[6]
                                hexList[n] = hxInt
                                packList[n] = packAircraftFlags(kind, regKind, disp, agStr)
                                strList[n] = "${parts[1]}\t${parts[2]}\t${parts[7]}"
                                n++
                            }
                        }
                    }
                }
                line = br.readLine()
            }
        }
        // File is emitted sorted by hex string ("000001" .. "ffffff"), which matches unsigned 24-bit int order!
        aircraftHexSorted = hexList.copyOf(n)
        aircraftPacked = packList.copyOf(n)
        @Suppress("UNCHECKED_CAST")
        aircraftStrings = strList.copyOf(n) as Array<String>
    }

    private fun gridKey(latBin: Int, lonBin: Int): Long =
        (latBin.toLong() shl 32) or (lonBin.toLong() and 0xFFFFFFFFL)

    private fun loadAirfields(context: Context) {
        val list = ArrayList<AirfieldRef>(1024)
        val grid = HashMap<Long, MutableList<AirfieldRef>>()
        context.assets.open("reference/az_airfields.tsv").bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 8) continue
                val lat = p[2].toDoubleOrNull() ?: continue
                val lon = p[3].toDoubleOrNull() ?: continue
                val af = AirfieldRef(
                    ident = p[0],
                    kind = p[1],
                    lat = lat,
                    lon = lon,
                    elevFt = p[4].toIntOrNull() ?: 2500,
                    maxRunwayFt = p[5].toIntOrNull() ?: 0,
                    isHospitalHelipad = p[6] == "1",
                    name = p[7]
                )
                list += af
                val gk = gridKey(kotlin.math.floor(lat).toInt(), kotlin.math.floor(lon).toInt())
                grid.getOrPut(gk) { ArrayList() }.add(af)
            }
        }
        allAirfields = list
        airfieldsByGrid = grid
    }

    private fun loadAzRf(context: Context) {
        val list = ArrayList<AzRfRef>(5000)
        openReferenceReader(context, "reference/az_fcc_rf.tsv.gz", 32768).useLines { seq ->
            for (line in seq) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 6) continue
                val hz = p[0].toLongOrNull() ?: continue
                list += AzRfRef(
                    freqHz = hz,
                    cls = p[1],
                    agency = p[2],
                    mode = p[3],
                    callsign = p[4],
                    licensee = p[5],
                    lat = p.getOrNull(6)?.toDoubleOrNull(),
                    lon = p.getOrNull(7)?.toDoubleOrNull()
                )
            }
        }
        list.sortBy { it.freqHz }
        rfFreqSorted = LongArray(list.size) { list[it].freqHz }
        rfEntries = list.toTypedArray()
    }

    private fun loadIeeeSubblocks(context: Context) {
        val map = HashMap<String, MacSubblockRef>(8192)
        openReferenceReader(context, "reference/ieee_subblocks.tsv.gz", 32768).useLines { seq ->
            for (line in seq) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 5) continue
                val pref = p[0].trim().uppercase()
                val bits = p[1].toIntOrNull() ?: 24
                val role = p[2].trim()
                val ag = p[3].trim().takeIf { it.isNotEmpty() }?.let { KokohoAgency.fromCode(it) }
                map[pref] = MacSubblockRef(pref, bits, role, ag, p[4])
            }
        }
        macSubblocks = map
    }

    private fun packCellKey(ratIdx: Int, mccInt: Int, mncInt: Int, cellId: Long): Long =
        ((ratIdx.toLong() and 0x7L) shl 58) or
            ((mccInt.toLong() and 0x3FFL) shl 48) or
            ((mncInt.toLong() and 0x3FFL) shl 38) or
            (cellId and 0x3FFFFFFFFFL)

    private fun ratNameToIndex(ratName: String?): Int = when (ratName?.trim()?.uppercase()) {
        "GSM" -> 0
        "CDMA" -> 1
        "WCDMA", "UMTS" -> 2
        "TDSCDMA" -> 3
        "LTE" -> 4
        "NR", "5G" -> 5
        else -> -1
    }

    private fun loadAzCells(context: Context) {
        val cap = 180_000
        var keys = LongArray(cap)
        var areas = IntArray(cap)
        var lats = IntArray(cap)
        var lons = IntArray(cap)
        val surv = LinkedHashMap<String, AzCellRef>(256)
        var n = 0
        openReferenceReader(context, "reference/az_cells.tsv.gz", 65536).use { br ->
            var line = br.readLine()
            while (line != null) {
                if (line.isNotEmpty() && line[0] != '#') {
                    val p = line.split('\t')
                    if (p.size >= 8) {
                        val rIdx = p[0].toIntOrNull() ?: -1
                        val mccI = p[1].toIntOrNull() ?: -1
                        val mncI = p[2].toIntOrNull() ?: -1
                        val cid = p[3].toLongOrNull() ?: -1L
                        val area = p[4].toIntOrNull() ?: 0
                        val latI = p[5].toIntOrNull() ?: 0
                        val lonI = p[6].toIntOrNull() ?: 0
                        val isSurv = p[7] == "1"
                        if (rIdx in 0..5 && mccI >= 0 && mncI >= 0 && cid > 0L) {
                            if (n >= keys.size) {
                                val nextCap = keys.size + 65_536
                                keys = keys.copyOf(nextCap)
                                areas = areas.copyOf(nextCap)
                                lats = lats.copyOf(nextCap)
                                lons = lons.copyOf(nextCap)
                            }
                            keys[n] = packCellKey(rIdx, mccI, mncI, cid)
                            areas[n] = (area and 0x3FFFFFFF) or (if (isSurv) (1 shl 30) else 0)
                            lats[n] = latI
                            lons[n] = lonI
                            if (isSurv) {
                                val rName = CELL_RAT_NAMES[rIdx]
                                val idKey = "$rName ${"%03d".format(java.util.Locale.US, mccI)}-$mncI $cid"
                                surv[idKey] = AzCellRef(
                                    idKey = idKey,
                                    area = area,
                                    lat = if (latI != 0) latI / 10_000.0 else null,
                                    lon = if (lonI != 0) lonI / 10_000.0 else null,
                                    isFieldSurvey = true
                                )
                            }
                            n++
                        }
                    }
                }
                line = br.readLine()
            }
        }
        cellKeySorted = keys.copyOf(n)
        cellAreaAndSurvey = areas.copyOf(n)
        cellLatE4 = lats.copyOf(n)
        cellLonE4 = lons.copyOf(n)
        surveyCellsMap = surv
    }

    private val TG_SYS_PRIORITY = listOf(
        "PCWIN", "AZWINS", "PINAL_P25", "MARANA_P25", "FCC_TUCSON", "RWC", "TRWC", "GILA_RIVER_P25", "SRPMIC_P25"
    )

    private fun loadAzTalkgroups(context: Context) {
        val map = HashMap<Int, MutableList<TalkgroupRef>>(2048)
        var count = 0
        openReferenceReader(context, "reference/az_talkgroups.tsv.gz", 32768).useLines { seq ->
            for (line in seq) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 6) continue
                val dec = p[1].toIntOrNull() ?: continue
                val ref = TalkgroupRef(
                    system = p[0],
                    tgDec = dec,
                    agencyCode = p[2],
                    mode = p[3],
                    alphaTag = p[4],
                    description = p[5]
                )
                map.getOrPut(dec) { ArrayList(2) }.add(ref)
                count++
            }
        }
        for (list in map.values) {
            if (list.size > 1) {
                list.sortBy { ref ->
                    val idx = TG_SYS_PRIORITY.indexOf(ref.system)
                    if (idx >= 0) idx else 99
                }
            }
        }
        talkgroupsByDec = map
        totalTalkgroupRows = count
    }

    /** O(log N) lookup of Mode-S 24-bit ICAO hex (e.g. `"a25082"` -> `N2483K`, `PA38`, `'F'`, `"TRN"`). */
    fun lookupAircraft(hex: String?): AircraftRef? {
        if (!loaded || hex.isNullOrBlank()) return null
        val clean = hex.trim().removePrefix("~").lowercase()
        if (clean.length != 6) return null
        val hxInt = clean.toIntOrNull(16) ?: return null
        val idx = Arrays.binarySearch(aircraftHexSorted, hxInt)
        if (idx < 0) return null
        val pk = aircraftPacked[idx]
        val kind = KIND_CHARS[pk and 0x7]
        val regKind = REG_CHARS[(pk ushr 3) and 0x7]
        val disp = DISP_TABLE[(pk ushr 6) and 0xF]
        val ag = AGENCY_TABLE[(pk ushr 10) and 0xF]
        val meta = aircraftStrings[idx].split('\t')
        return AircraftRef(
            hex = clean,
            registration = meta.getOrElse(0) { "" },
            icaoType = meta.getOrElse(1) { "" },
            airframeKind = kind,
            registrantKind = regKind,
            disposition = disp,
            agencyHint = ag,
            owner = meta.getOrElse(2) { "" }
        )
    }

    /** Nearest matching airfield / hospital helipad when orbiting within runway pattern or landing zone. */
    fun findMatchingAirfieldPattern(
        lat: Double,
        lon: Double,
        altitudeFt: Int?,
        isExplicitLeOrGov: Boolean
    ): AirfieldRef? {
        if (!loaded || isExplicitLeOrGov) return null
        val baseLat = kotlin.math.floor(lat).toInt()
        val baseLon = kotlin.math.floor(lon).toInt()
        for (dLat in -1..1) {
            for (dLon in -1..1) {
                val bucket = airfieldsByGrid[gridKey(baseLat + dLat, baseLon + dLon)] ?: continue
                for (af in bucket) {
                    val distM = DeviceRegistry.metersBetween(lat, lon, af.lat, af.lon)
                    if (af.isHospitalHelipad) {
                        // Medevac / hospital helipad orbit or approach (<= 950m & <= +1,500 ft AGL)
                        if (distM <= 950.0 && (altitudeFt == null || altitudeFt <= af.elevFt + 1_500)) {
                            return af
                        }
                    } else if (af.kind != "heliport") {
                        // Runway airport / airstrip / gliderport traffic pattern
                        val patternRadiusM = if (af.kind == "large_airport" || af.kind == "medium_airport" || af.maxRunwayFt >= 4500) {
                            3_800.0
                        } else {
                            2_400.0
                        }
                        if (distM <= patternRadiusM && (altitudeFt == null || altitudeFt <= af.elevFt + 2_200)) {
                            return af
                        }
                    }
                }
            }
        }
        return null
    }

    /** O(log N) lookup of AZ FCC ULS (`l_LMpriv` + `l_LMcomm`) & DHS NIFOG channel within [toleranceHz]. */
    fun lookupAzRf(hz: Long, toleranceHz: Long = 6_250L): AzRfRef? {
        if (!loaded || rfFreqSorted.isEmpty()) return null
        val idx = Arrays.binarySearch(rfFreqSorted, hz)
        if (idx >= 0) return rfEntries[idx]
        val ins = -idx - 1
        var best: AzRfRef? = null
        var bestDiff = Long.MAX_VALUE
        for (candIdx in (ins - 1)..(ins + 1)) {
            if (candIdx in rfEntries.indices) {
                val diff = abs(rfEntries[candIdx].freqHz - hz)
                if (diff <= toleranceHz && diff < bestDiff) {
                    bestDiff = diff
                    best = rfEntries[candIdx]
                }
            }
        }
        return best
    }

    /** Longest-prefix IEEE MAC lookup: 36-bit MA-S (9 hex) -> 28-bit MA-M (7 hex) -> 24-bit MA-L (6 hex). */
    fun lookupMacSubblock(rawMac: String?): MacSubblockRef? {
        if (!loaded || rawMac.isNullOrBlank()) return null
        val hex = rawMac.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        if (hex.length < 6) return null
        if (hex.length >= 9) {
            macSubblocks[hex.substring(0, 9)]?.let { return it }
        }
        if (hex.length >= 7) {
            macSubblocks[hex.substring(0, 7)]?.let { return it }
        }
        return macSubblocks[hex.substring(0, 6)]
    }

    /** Zero-allocation O(log N) lookup across 162,000+ Arizona & Sonora OpenCelliD / MLS / Surveyed cells. */
    fun lookupAzCell(ratName: String?, mcc: String?, mnc: String?, cellId: Long?): AzCellRef? {
        if (!loaded || cellKeySorted.isEmpty() || cellId == null || cellId <= 0L) return null
        val rIdx = ratNameToIndex(ratName)
        if (rIdx < 0) return null
        val mccI = mcc?.trim()?.toIntOrNull() ?: return null
        val mncI = mnc?.trim()?.toIntOrNull() ?: return null
        val pk = packCellKey(rIdx, mccI, mncI, cellId)
        val idx = Arrays.binarySearch(cellKeySorted, pk)
        if (idx < 0) return null
        val packedArea = cellAreaAndSurvey[idx]
        val area = packedArea and 0x3FFFFFFF
        val isSurv = (packedArea and (1 shl 30)) != 0
        val latI = cellLatE4[idx]
        val lonI = cellLonE4[idx]
        return AzCellRef(
            idKey = "${CELL_RAT_NAMES[rIdx]} $mcc-$mnc $cellId",
            area = area,
            lat = if (latI != 0) latI / 10_000.0 else null,
            lon = if (lonI != 0) lonI / 10_000.0 else null,
            isFieldSurvey = isSurv
        )
    }

    /** O(1) lookup of P25 Talkgroup ID across PCWIN, AZWINS, Pinal, Marana, FCC Tucson, RWC, & TRWC. */
    fun lookupTalkgroup(tgDec: Int, systemHint: String? = null): TalkgroupRef? {
        if (!loaded || talkgroupsByDec.isEmpty()) return null
        val list = talkgroupsByDec[tgDec] ?: return null
        if (!systemHint.isNullOrBlank()) {
            list.firstOrNull { it.system.equals(systemHint.trim(), ignoreCase = true) }?.let { return it }
        }
        return list.firstOrNull()
    }

    fun fieldSurveyCells(): Map<String, AzCellRef> = surveyCellsMap

    fun airfieldCount(): Int = allAirfields.size
    fun aircraftMetaCount(): Int = aircraftHexSorted.size
    fun azRfCount(): Int = rfEntries.size
    fun azCellCount(): Int = cellKeySorted.size
    fun azTalkgroupCount(): Int = totalTalkgroupRows
}
