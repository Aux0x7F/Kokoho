package org.tucsoncommunity.kokoho.online

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tucsoncommunity.kokoho.detect.Category
import org.tucsoncommunity.kokoho.detect.Hit
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import kotlin.math.abs

/**
 * Police and government aircraft overhead, from community ADS-B networks (adsb.fi,
 * adsb.lol: no key, and unlike the commercial flight trackers they don't hide
 * law-enforcement flights). An aircraft counts when its ICAO address is in the bundled
 * registry of law-enforcement airframes (FAA + Transport Canada), when the feed names a
 * police owner, or when an unlisted aircraft keeps circling low over the same spot.
 */
object PoliceAircraft {

    /** One aircraft from the feed. Altitude in feet, speed in knots, track in degrees. */
    data class Plane(
        val hex: String,
        val callsign: String?,
        val registration: String?,
        val type: String?,
        val owner: String?,
        val lat: Double,
        val lon: Double,
        val altitudeFt: Int?,
        val speedKt: Double?,
        val trackDeg: Double?,
        val military: Boolean,
        val onGround: Boolean = false,
        val category: String? = null,
        val nonIcaoTisb: Boolean = false
    )

    data class Entry(val registration: String, val owner: String, val model: String)

    /** Matches police / federal law-enforcement owner names (also in the feed's owner field). */
    private val LE_OWNER = Regex(
        "POLICE|SHERIFF|HIGHWAY PATROL|STATE PATROL|TROOPER|PUBLIC SAFETY|CONSTABLE|MARSHALS?\\b|" +
            "CUSTOMS|BORDER (PROTECTION|SERVICES)|HOMELAND SECURITY|FEDERAL BUREAU OF INVESTIGATION|" +
            "DRUG ENFORCEMENT|LAW ENFORCEMENT|GENDARMERIE",
        RegexOption.IGNORE_CASE
    )

    /** Government owners (agencies, states, counties, cities, the Crown): not proof of police, but a public-service flight. */
    private val GOV_OWNER = Regex(
        "GOVERNMENT|DEPARTMENT OF|DEPT OF|MINISTRY|STATE OF|COMMONWEALTH OF|PROVINCE OF|COUNTY OF|CITY OF|" +
            "HIS MAJESTY|HER MAJESTY|COAST GUARD",
        RegexOption.IGNORE_CASE
    )

    private val GLIDER_OR_TRAINER_REGEX = Regex(
        "(MOTOR)?GLIDER|\\b(GLID|SAILPLANE|ASK-?21|DG-?1000|TG-?1[0-9]|G-?10[23]|L-?13|L-?23|BLANIK|SGS ?2-33|GROB ?103|CIRRUS ?STD|PA-?38|PA38|TOMAHAWK|PA-?44|PA44|SEMINOLE|PA-?24|PA24|COMANCHE|P28[A-Z]|PA-?28|PA28|CHEROKEE|ARCHER|WARRIOR|DAKOTA|C140|C150|C152|C170|C172|C177|CARDINAL|CESSNA 15[02]|CESSNA 17[027]|SKYHAWK|CUTLASS|DA20|DA40|SR20|AA-?5|CHEETAH|TIGER|M20[A-Z]?|MOONEY|RV-[0-9]{1,2})\\b",
        RegexOption.IGNORE_CASE
    )

    private data class AirfieldPattern(val code: String, val lat: Double, val lon: Double, val fieldElevFt: Int)

    private val REGIONAL_AIRFIELDS = listOf(
        AirfieldPattern("KTUS", 32.1161, -110.9410, 2643),
        AirfieldPattern("KRYN", 32.1422, -111.1746, 2419),
        AirfieldPattern("KAVQ", 32.4095, -111.2184, 2031),
        AirfieldPattern("KDMA", 32.1665, -110.8830, 2704),
        AirfieldPattern("E77", 32.6366, -110.6409, 3275),
        AirfieldPattern("KOLS", 31.4177, -110.8479, 3955),
        AirfieldPattern("KFHU", 31.5885, -110.3444, 4719),
        AirfieldPattern("57AZ", 32.4339, -111.0061, 2940),
        AirfieldPattern("E95", 31.9993, -110.3582, 3829)
    )

    fun isAirportTrafficPattern(
        centerLat: Double,
        centerLon: Double,
        altitudeFt: Int?,
        isExplicitLeOrGov: Boolean = false
    ): Boolean {
        if (isExplicitLeOrGov) return false
        org.tucsoncommunity.kokoho.posture.KokohoReferenceTables
            .findMatchingAirfieldPattern(centerLat, centerLon, altitudeFt, isExplicitLeOrGov = false)
            ?.let { return true }
        for (af in REGIONAL_AIRFIELDS) {
            val d = DeviceRegistry.metersBetween(centerLat, centerLon, af.lat, af.lon)
            if (d <= 3_800.0 && (altitudeFt == null || altitudeFt <= af.fieldElevFt + 2_200)) {
                return true
            }
        }
        return false
    }

    private val MILITARY_NON_LE_AIRFRAME_REGEX = Regex(
        "\\b(UH-?72|LUH-?72|LAKOTA|UH-?60|MH-?60|HH-?60|S-?70|BLACK ?HAWK|CH-?47|AH-?64|EC-?145|BK-?117|T-?38|T-?6|KC-?135|KC-?46|C-?130|C-?20|C-?12|C-?17|C-?5|E-?3|B-?52|V-?22|A-?10|F-?35|F-?16|USAF|AIR FORCE|ARMY|NAVY|MARINES|NATIONAL GUARD)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isGroundedOrParked(p: Plane): Boolean {
        if (p.onGround) return true
        val alt = p.altitudeFt
        if (alt != null && alt <= 100) return true
        val spd = p.speedKt
        // Landed/idling helicopters or taxiing aircraft in the Tucson basin (alt < 3,500 ft MSL, speed < 15 kt).
        if (spd != null && spd < 15.0 && (alt == null || alt < 3_500)) return true
        return false
    }

    fun isGliderOrExcludedTrainer(p: Plane): Boolean {
        if (p.category.equals("B1", ignoreCase = true)) return true
        val combined = listOfNotNull(p.type, p.callsign, p.owner, p.registration).joinToString(" ")
        if (GLIDER_OR_TRAINER_REGEX.containsMatchIn(combined)) return true
        val isExplicitLeOrDhs = LE_OWNER.containsMatchIn(combined)
        if (!isExplicitLeOrDhs && p.nonIcaoTisb) return true
        val ref = org.tucsoncommunity.kokoho.posture.KokohoReferenceTables.lookupAircraft(p.hex)
        if (!isExplicitLeOrDhs && ref != null && (ref.disposition == "TRN" || ref.disposition == "MED" || ref.disposition == "MIL" || ref.airframeKind == 'G')) {
            return true
        }
        // Exclude anonymous FAA TIS-B primary-radar pseudo-addresses (`200000..27FFFF` without registration/owner/catalog entry)
        if (!isExplicitLeOrDhs && ref == null && combined.isBlank()) {
            val hexInt = p.hex.trim().removePrefix("~").toIntOrNull(16)
            if (hexInt != null && hexInt in 0x200000..0x27FFFF) return true
        }
        // Exclude pure US military / National Guard airframes (e.g. AE* ICAO hex, Blackhawk/Lakota) unless explicitly LE/DHS.
        if (!isExplicitLeOrDhs && (p.hex.startsWith("ae", ignoreCase = true) || MILITARY_NON_LE_AIRFRAME_REGEX.containsMatchIn(combined))) {
            return true
        }
        return false
    }

    /** Enriches a raw ADS-B plane (especially offline RTL-SDR 1090 MHz packets) from `KokohoReferenceTables`. */
    fun enrichPlane(p: Plane): Plane {
        val ref = org.tucsoncommunity.kokoho.posture.KokohoReferenceTables.lookupAircraft(p.hex) ?: return p
        val reg = p.registration?.takeIf { it.isNotBlank() } ?: ref.registration.takeIf { it.isNotBlank() }
        val tp = p.type?.takeIf { it.isNotBlank() } ?: ref.icaoType.takeIf { it.isNotBlank() }
        val own = p.owner?.takeIf { it.isNotBlank() } ?: ref.owner.takeIf { it.isNotBlank() }
        return if (reg === p.registration && tp === p.type && own === p.owner) {
            p
        } else {
            p.copy(registration = reg, type = tp, owner = own)
        }
    }

    /** The label of an unlisted aircraft circling overhead ([classify]). */
    const val CIRCLING = "Aircraft circling overhead"

    @Volatile private var registry: Map<String, Entry>? = null

    fun load(context: Context): Map<String, Entry> {
        org.tucsoncommunity.kokoho.posture.KokohoReferenceTables.ensureLoaded(context)
        return registry ?: synchronized(this) {
            registry ?: parseRegistry(
                context.assets.open("aircraft/law_enforcement.tsv").bufferedReader().use { it.readText() }
            ).also { registry = it }
        }
    }

    internal fun parseRegistry(text: String): Map<String, Entry> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3) null else f[0].lowercase() to Entry(f[1], f[2], f.getOrElse(3) { "" })
        }
        .toMap()

    /** Aircraft from an adsb.fi ("aircraft") or adsb.lol ("ac") response. */
    internal fun parseFeed(json: String): List<Plane> {
        val root = JsonParser.parseString(json).asJsonObject
        val list = (root.getAsJsonArray("aircraft") ?: root.getAsJsonArray("ac")) ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e.asJsonObject
            val lat = o.num("lat") ?: return@mapNotNull null
            val lon = o.num("lon") ?: return@mapNotNull null
            val rawHex = o.str("hex")?.lowercase() ?: return@mapNotNull null
            val nonIcao = rawHex.startsWith("~") || o.str("type")?.startsWith("tisb", ignoreCase = true) == true
            val hex = rawHex.removePrefix("~")
            val baroStr = o.str("alt_baro")
            val onGround = baroStr.equals("ground", ignoreCase = true)
            val altFt = if (onGround) 0 else (o.num("alt_baro")?.toInt() ?: o.num("alt_geom")?.toInt())
            Plane(
                hex = hex,
                callsign = o.str("flight")?.trim()?.ifEmpty { null },
                registration = o.str("r"),
                type = o.str("desc") ?: o.str("t"),
                owner = o.str("ownOp"),
                lat = lat, lon = lon,
                // alt_baro is "ground" for aircraft on the ground (never overwrite 0 with alt_geom).
                altitudeFt = altFt,
                speedKt = o.num("gs"),
                trackDeg = o.num("track"),
                military = ((o.num("dbFlags")?.toInt() ?: 0) and 1) != 0,
                onGround = onGround,
                category = o.str("category"),
                nonIcaoTisb = nonIcao
            )
        }
    }

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }

    private fun JsonObject.num(k: String): Double? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    /**
     * What an aircraft is, or null when it's ordinary traffic. [circling] comes from
     * [LoiterTracker]; [distanceM] is the ground distance from you.
     */
    fun classify(p: Plane, registry: Map<String, Entry>, circling: Boolean, distanceM: Double?, via: String = VIA_ONLINE, alertM: Double = ALERT_M): Hit? {
        if (isGliderOrExcludedTrainer(p)) return null
        val where = describe(p, distanceM)
        // One 40 km away is listed, but it isn't overhead.
        val over = if (distanceM != null && distanceM <= alertM) " overhead" else ""
        registry[p.hex]?.let { e ->
            return Hit(Category.AIRCRAFT, "Police aircraft$over: ${titleCase(e.owner)}", if (circling) 90 else 85,
                "${e.registration} (${e.model.ifBlank { p.type ?: "aircraft" }}), registered to ${titleCase(e.owner)}. $where" +
                    if (circling) " It is circling." else "",
                "FAA / Transport Canada aircraft registry; $via")
        }
        p.owner?.takeIf { LE_OWNER.containsMatchIn(it) }?.let { owner ->
            return Hit(Category.AIRCRAFT, "Police aircraft$over: ${titleCase(owner)}", if (circling) 85 else 80,
                "${p.registration ?: p.hex.uppercase()} (${p.type ?: "aircraft"}), operated by ${titleCase(owner)}. $where" +
                    if (circling) " It is circling." else "",
                "ADS-B owner / operator (adsb.fi)")
        }
        p.owner?.takeIf { GOV_OWNER.containsMatchIn(it) }?.let { owner ->
            return Hit(Category.AIRCRAFT, "Government aircraft$over: ${titleCase(owner)}", if (circling) 85 else 80,
                "${p.registration ?: p.hex.uppercase()} (${p.type ?: "aircraft"}), operated by ${titleCase(owner)}. $where" +
                    (if (circling) " It is circling." else "") + " A government owner isn't always police - verify.",
                "ADS-B owner / operator (adsb.fi)")
        }
        if (circling) {
            val low = (p.altitudeFt ?: 0) in 1..3_000
            return Hit(Category.AIRCRAFT, CIRCLING, if (low) 55 else 45,
                "${p.registration ?: p.callsign ?: p.hex.uppercase()} (${p.type ?: "aircraft"}) keeps circling over the same area. $where " +
                    "Police and surveillance flights orbit like this, but so do news, training and survey flights - verify.",
                "$via track (loiter detection)")
        }
        if (p.military) {
            return Hit(Category.AIRCRAFT, "Military / government aircraft nearby", 40,
                "${p.registration ?: p.callsign ?: p.hex.uppercase()} (${p.type ?: "aircraft"}). $where",
                "ADS-B database flag (military)")
        }
        return null
    }

    /**
     * A law-enforcement aircraft the RTL-SDR heard (its transponder answering radar, or ADS-B
     * without a position): in radio range - tens of km - but where exactly isn't known.
     */
    fun heardNearby(hex: String, registry: Map<String, Entry>): Hit? = registry[hex]?.let { e ->
        Hit(Category.AIRCRAFT, "Police aircraft in radio range: ${titleCase(e.owner)}", 60,
            "${e.registration} (${e.model.ifBlank { "aircraft" }}), registered to ${titleCase(e.owner)}. Its transponder was " +
                "heard by your RTL-SDR, so it is within radio range (usually under 100 km), but it sent no position.",
            "FAA / Transport Canada aircraft registry; $VIA_LOCAL")
    }

    /** The default alert distance (ground distance, Settings): closer, a strong match alerts; farther, or weaker, it's only logged and listed. */
    const val ALERT_M = 5_000.0

    /**
     * Whether a hit alerts (sound, voice, notification): a strong match (police, government) within [alertM], or with
     * [circlingOn] (Settings) any aircraft circling within it; otherwise it's only logged.
     */
    fun alerts(hit: Hit, distanceM: Double?, circlingOn: Boolean = false, alertM: Double = ALERT_M): Boolean =
        distanceM != null && distanceM <= alertM &&
            (hit.confidence >= org.tucsoncommunity.kokoho.detect.Tier.STRONG.min || (circlingOn && hit.label == CIRCLING))

    const val VIA_ONLINE = "ADS-B (adsb.fi / adsb.lol)"
    const val VIA_LOCAL = "ADS-B heard by your RTL-SDR"

    private fun describe(p: Plane, distanceM: Double?): String =
        listOfNotNull(
            distanceM?.let { "About ${org.tucsoncommunity.kokoho.util.Units.distance(it)} from you" },
            p.altitudeFt?.let { if (it <= 0) "on the ground" else "at ${it} ft" },
            p.speedKt?.let { "${it.toInt()} kt" }
        ).joinToString(", ") + "."

    internal fun titleCase(s: String): String = s.lowercase().split(' ').joinToString(" ") { w ->
        if (w.length <= 3 && w in setOf("us", "fbi", "dea", "cbp", "dps", "rcmp", "opp")) w.uppercase()
        else w.replaceFirstChar { it.uppercase() }
    }

    /**
     * Spots aircraft orbiting over one area: in the last 12 minutes it turned through at least one and a half
     * full circles while staying within 4 km of its own average position, below 8,000 ft and slower than 180 kt
     * (airliners and passing traffic don't). Its positions must also go all the way around that average spot:
     * the reported heading alone can't be trusted (a taxiing plane, a hovering helicopter or a jumpy heading
     * adds up turns while flying straight). Fixes on the ground, slower than 30 kt or without a new position
     * don't count.
     */
    class LoiterTracker {
        private data class Fix(val time: Long, val lat: Double, val lon: Double, val track: Double)
        private val tracks = HashMap<String, ArrayDeque<Fix>>()

        fun update(p: Plane, now: Long): Boolean {
            val q = tracks.getOrPut(p.hex) { ArrayDeque() }
            val airborne = !isGroundedOrParked(p) && !isGliderOrExcludedTrainer(p) && (p.speedKt ?: MIN_SPEED_KT) >= MIN_SPEED_KT
            val last = q.lastOrNull()
            // The RTL-SDR's list repeats the last position every couple of seconds until a new one is decoded.
            val fresh = last == null || (now - last.time >= MIN_GAP_MS && (last.lat != p.lat || last.lon != p.lon))
            if (airborne && fresh) p.trackDeg?.let { q.addLast(Fix(now, p.lat, p.lon, it)) }
            while (q.isNotEmpty() && now - q.first().time > WINDOW_MS) q.removeFirst()
            if (!airborne || q.size < 6 || now - q.first().time < 5 * 60_000L) return false
            if ((p.altitudeFt ?: 0) > 8_000 || (p.speedKt ?: 0.0) > 180) return false
            var turned = 0.0
            for (i in 1 until q.size) turned += wrap(q[i].track - q[i - 1].track)
            val cLat = q.sumOf { it.lat } / q.size
            val cLon = q.sumOf { it.lon } / q.size
            val ownerTxt = listOfNotNull(p.owner, p.callsign, p.type).joinToString(" ")
            val explicitLeOrGov = LE_OWNER.containsMatchIn(ownerTxt) || GOV_OWNER.containsMatchIn(ownerTxt)
            if (isAirportTrafficPattern(cLat, cLon, p.altitudeFt, explicitLeOrGov)) return false
            val dist = q.map { DeviceRegistry.metersBetween(cLat, cLon, it.lat, it.lon) }
            if (dist.max() > 4_000 || dist.average() < MIN_RADIUS_M) return false
            // How far the positions went around the average spot (a straight pass sweeps under 180 degrees).
            var around = 0.0
            for (i in 1 until q.size) around += wrap(bearing(cLat, cLon, q[i]) - bearing(cLat, cLon, q[i - 1]))
            return abs(turned) >= 540 && abs(around) >= 360 && turned * around > 0
        }

        private fun wrap(d: Double): Double = when { d > 180 -> d - 360; d < -180 -> d + 360; else -> d }

        private fun bearing(lat: Double, lon: Double, f: Fix): Double =
            Math.toDegrees(kotlin.math.atan2((f.lon - lon) * kotlin.math.cos(Math.toRadians(lat)), f.lat - lat))

        /** Forgets aircraft not heard for a while. */
        fun prune(now: Long) {
            tracks.entries.removeAll { (_, q) -> q.isEmpty() || now - q.last().time > WINDOW_MS }
        }

        companion object {
            private const val WINDOW_MS = 12 * 60_000L
            private const val MIN_GAP_MS = 5_000L
            private const val MIN_SPEED_KT = 30.0
            /** Average distance from the middle of the orbit: a real one is wider than this. */
            private const val MIN_RADIUS_M = 150.0
        }
    }

    /** Ground distance in metres. */
    fun distance(p: Plane, lat: Double, lon: Double): Double = DeviceRegistry.metersBetween(lat, lon, p.lat, p.lon)

    /** One aircraft as last heard, with what it matched (null: ordinary traffic). */
    data class Seen(val plane: Plane, val hit: Hit?)

    data class FlightFix(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val altM: Double? = null
    )

    data class LandedSpot(
        val hex: String,
        val agencyId: String,
        val lat: Double,
        val lon: Double,
        val hitCount: Int,
        val updatedAtMs: Long
    )

    private const val LANDED_PREFS = "kokoho_landed_air"
    private const val KEY_LANDED_JSON = "landed_spots_json"
    private const val TRAIL_MAX_AGE_MS = 24 * 60 * 60_000L
    private const val GROUNDED_RECENT_MS = 15 * 60_000L
    private const val FLIGHT_GAP_BREAK_MS = 20 * 60_000L

    data class LocalDroneTrail(
        val entityUuid: String,
        val agencyId: String,
        val label: String,
        val points: List<Pair<Double, Double>>,
        val endedAtMs: Long
    )

    private data class DroneTrailState(
        var agencyId: String,
        var label: String,
        val fixes: ArrayDeque<FlightFix> = ArrayDeque()
    )

    private val airborneTrails = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<FlightFix>>()
    private val droneTrails = java.util.concurrent.ConcurrentHashMap<String, DroneTrailState>()
    private val trailAliases = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val groundedAtByHex = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val landedSpotsByKey = java.util.concurrent.ConcurrentHashMap<String, LandedSpot>()
    @Volatile private var landedSpotsLoaded = false

    private fun normalizeTrailKey(raw: String): String {
        val stripped = raw.trim()
            .replace(Regex("^(?i)(adsb:icao:|uas:mac:|hw:mac:)"), "")
            .uppercase()
        return trailAliases[stripped] ?: stripped
    }

    /**
     * Non-destructively clips a timestamped [FlightFix] sequence to `[nowMs - windowMs, nowMs]`,
     * interpolating the entry boundary vertex at `cutoffMs` when a continuous leg crosses the cutoff.
     */
    fun clipFixesToWindow(
        fixes: List<FlightFix>,
        nowMs: Long,
        windowMs: Long
    ): List<Pair<Double, Double>> {
        if (windowMs <= 0L || fixes.size < 2) return emptyList()
        val cutoffMs = nowMs - windowMs
        val out = ArrayList<Pair<Double, Double>>(fixes.size)
        for (i in fixes.indices) {
            val cur = fixes[i]
            if (i > 0) {
                val prev = fixes[i - 1]
                val gap = cur.timeMs - prev.timeMs
                if (prev.timeMs < cutoffMs && cur.timeMs >= cutoffMs && gap in 1L..FLIGHT_GAP_BREAK_MS) {
                    val alpha = ((cutoffMs - prev.timeMs).toDouble() / gap.toDouble()).coerceIn(0.0, 1.0)
                    val lat = prev.lat + (cur.lat - prev.lat) * alpha
                    val lon = prev.lon + (cur.lon - prev.lon) * alpha
                    out += lat to lon
                }
            }
            if (cur.timeMs in cutoffMs..nowMs) {
                if (out.isNotEmpty() && i > 0 && cur.timeMs - fixes[i - 1].timeMs > FLIGHT_GAP_BREAK_MS) {
                    // Start fresh on the most recent contiguous flight leg within the window
                    out.clear()
                }
                out += cur.lat to cur.lon
            }
        }
        return if (out.size >= 2) out else emptyList()
    }

    @JvmOverloads
    fun recordAirborneFix(
        hex: String,
        lat: Double,
        lon: Double,
        nowMs: Long = System.currentTimeMillis(),
        altM: Double? = null,
        entityUuid: String? = null
    ) {
        recordPinpoint4DFix(
            rawKey = hex,
            lat = lat,
            lon = lon,
            altM = altM,
            nowMs = nowMs,
            minGapMs = 4_000L,
            minDistM = 75.0,
            minAltDeltaM = 15.0,
            entityUuid = entityUuid
        )
    }

    fun recordDroneFlightFix(
        droneKey: String,
        lat: Double,
        lon: Double,
        altM: Double?,
        nowMs: Long = System.currentTimeMillis(),
        entityUuid: String? = null
    ) {
        recordPinpoint4DFix(
            rawKey = droneKey,
            lat = lat,
            lon = lon,
            altM = altM,
            nowMs = nowMs,
            minGapMs = 1_000L,
            minDistM = 10.0,
            minAltDeltaM = 5.0,
            entityUuid = entityUuid
        )
    }

    private fun recordPinpoint4DFix(
        rawKey: String,
        lat: Double,
        lon: Double,
        altM: Double?,
        nowMs: Long,
        minGapMs: Long,
        minDistM: Double,
        minAltDeltaM: Double,
        entityUuid: String?
    ) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (abs(lat) < 1e-5 && abs(lon) < 1e-5) return
        val normKey = normalizeTrailKey(rawKey)
        if (normKey.isEmpty()) return
        if (!entityUuid.isNullOrBlank()) {
            trailAliases[entityUuid.trim().uppercase()] = normKey
        }
        val validAltM = altM?.takeIf { it.isFinite() && it in -500.0..20_000.0 }
        groundedAtByHex.remove(normKey)
        val q = airborneTrails.getOrPut(normKey) { ArrayDeque() }
        synchronized(q) {
            val last = q.lastOrNull()
            val movedHoriz = last != null && DeviceRegistry.metersBetween(last.lat, last.lon, lat, lon) >= minDistM
            val movedVert = last != null && validAltM != null && last.altM != null && abs(validAltM - last.altM) >= minAltDeltaM
            if (last == null || (nowMs - last.timeMs >= minGapMs && (movedHoriz || movedVert))) {
                q.addLast(FlightFix(nowMs, lat, lon, validAltM ?: last?.altM))
            } else if (nowMs >= last.timeMs && last.altM == null && validAltM != null) {
                q.removeLast()
                q.addLast(last.copy(altM = validAltM))
            }
            while (q.isNotEmpty() && nowMs - q.first().timeMs > TRAIL_MAX_AGE_MS) q.removeFirst()
            while (q.size > 360) q.removeFirst()
        }
    }

    fun recentAirborneTrail(
        hex: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = TRAIL_MAX_AGE_MS,
        includeLandedWithinWindow: Boolean = false
    ): List<Pair<Double, Double>> {
        val normHex = normalizeTrailKey(hex)
        if (normHex.isEmpty() || (!includeLandedWithinWindow && normHex in currentlyGroundedHexes(nowMs))) return emptyList()
        val q = airborneTrails[normHex] ?: return emptyList()
        val snapshot = synchronized(q) {
            while (q.isNotEmpty() && nowMs - q.first().timeMs > TRAIL_MAX_AGE_MS) q.removeFirst()
            val newest = q.lastOrNull() ?: return emptyList()
            if (!includeLandedWithinWindow && nowMs - newest.timeMs > 5 * 60_000L) return emptyList()
            if (nowMs - newest.timeMs > maxAgeMs) return emptyList()
            q.toList()
        }
        return clipFixesToWindow(snapshot, nowMs, maxAgeMs)
    }

    fun recordPinpointDroneFix(
        entityUuid: String,
        agencyId: String,
        label: String,
        lat: Double,
        lon: Double,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = entityUuid.trim()
        if (key.isEmpty()) return
        val state = droneTrails.computeIfAbsent(key) { DroneTrailState(agencyId, label) }
        synchronized(state) {
            state.agencyId = agencyId
            if (label.isNotBlank()) state.label = label
            val last = state.fixes.lastOrNull()
            if (last == null || (nowMs - last.timeMs >= 2_500L && DeviceRegistry.metersBetween(last.lat, last.lon, lat, lon) >= 12.0)) {
                state.fixes.addLast(FlightFix(nowMs, lat, lon))
            }
            while (state.fixes.isNotEmpty() && nowMs - state.fixes.first().timeMs > TRAIL_MAX_AGE_MS) {
                state.fixes.removeFirst()
            }
            while (state.fixes.size > 300) state.fixes.removeFirst()
        }
    }

    fun recentDroneTrailsForWindow(nowMs: Long, windowMs: Long): List<LocalDroneTrail> {
        if (windowMs <= 0L) return emptyList()
        val out = ArrayList<LocalDroneTrail>()
        for ((uuid, state) in droneTrails) {
            val (agId, lbl, snap, endMs) = synchronized(state) {
                while (state.fixes.isNotEmpty() && nowMs - state.fixes.first().timeMs > TRAIL_MAX_AGE_MS) {
                    state.fixes.removeFirst()
                }
                val newest = state.fixes.lastOrNull() ?: continue
                if (nowMs - newest.timeMs > windowMs) continue
                listOf(state.agencyId, state.label, state.fixes.toList(), newest.timeMs)
            }
            @Suppress("UNCHECKED_CAST")
            val clipped = clipFixesToWindow(snap as List<FlightFix>, nowMs, windowMs)
            if (clipped.size >= 2) {
                out += LocalDroneTrail(
                    entityUuid = uuid,
                    agencyId = agId as String,
                    label = lbl as String,
                    points = clipped,
                    endedAtMs = endMs as Long
                )
            }
        }
        return out
    }

    internal fun clearTrailsForTest() {
        airborneTrails.clear()
        droneTrails.clear()
        trailAliases.clear()
        groundedAtByHex.clear()
    }

    fun recentFlightFixes4D(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = TRAIL_MAX_AGE_MS
    ): List<FlightFix> {
        val normKey = normalizeTrailKey(key)
        if (normKey.isEmpty() || normKey in currentlyGroundedHexes(nowMs)) return emptyList()
        val q = airborneTrails[normKey] ?: return emptyList()
        synchronized(q) {
            while (q.isNotEmpty() && nowMs - q.first().timeMs > maxAgeMs) q.removeFirst()
            return q.toList()
        }
    }

    fun clearAirborneTrailsForTest() {
        airborneTrails.clear()
        droneTrails.clear()
        trailAliases.clear()
        groundedAtByHex.clear()
    }

    private fun ensureLandedLoaded(context: Context?) {
        if (landedSpotsLoaded || context == null) return
        synchronized(this) {
            if (landedSpotsLoaded) return
            runCatching {
                val raw = context.getSharedPreferences(LANDED_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LANDED_JSON, null)
                if (!raw.isNullOrBlank()) {
                    val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
                    for (el in arr) {
                        val o = el.asJsonObject
                        val key = o.get("key")?.asString ?: continue
                        val spot = LandedSpot(
                            hex = o.get("hex")?.asString ?: "",
                            agencyId = o.get("agencyId")?.asString ?: "DHS_AMO",
                            lat = o.get("lat")?.asDouble ?: continue,
                            lon = o.get("lon")?.asDouble ?: continue,
                            hitCount = o.get("hitCount")?.asInt ?: 1,
                            updatedAtMs = o.get("updatedAtMs")?.asLong ?: 0L
                        )
                        landedSpotsByKey[key] = spot
                    }
                }
            }
            landedSpotsLoaded = true
        }
    }

    private fun persistLandedSpots(context: Context) {
        val arr = com.google.gson.JsonArray()
        val sorted = landedSpotsByKey.entries
            .sortedByDescending { it.value.updatedAtMs }
            .take(64)
        for ((k, s) in sorted) {
            val o = JsonObject().apply {
                addProperty("key", k)
                addProperty("hex", s.hex)
                addProperty("agencyId", s.agencyId)
                addProperty("lat", s.lat)
                addProperty("lon", s.lon)
                addProperty("hitCount", s.hitCount)
                addProperty("updatedAtMs", s.updatedAtMs)
            }
            arr.add(o)
        }
        context.getSharedPreferences(LANDED_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANDED_JSON, arr.toString()).apply()
    }

    fun markGroundedAndRecordLanding(
        context: Context?,
        hex: String,
        agencyId: String,
        lat: Double,
        lon: Double,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val normHex = hex.trim().uppercase()
        if (normHex.isEmpty()) return
        groundedAtByHex[normHex] = nowMs
        airborneTrails.remove(normHex)
        ensureLandedLoaded(context)
        val existingEntry = landedSpotsByKey.entries.firstOrNull { (_, s) ->
            s.hex.equals(normHex, ignoreCase = true) && DeviceRegistry.metersBetween(s.lat, s.lon, lat, lon) <= 800.0
        }
        if (existingEntry != null) {
            val prev = existingEntry.value
            val n = prev.hitCount + 1
            // Throttle updates within 2 minutes at the exact same landing pad so hitCount doesn't inflate on every poll
            if (nowMs - prev.updatedAtMs >= 120_000L) {
                landedSpotsByKey[existingEntry.key] = prev.copy(
                    lat = ((prev.lat * prev.hitCount) + lat) / n,
                    lon = ((prev.lon * prev.hitCount) + lon) / n,
                    hitCount = n,
                    updatedAtMs = nowMs
                )
                context?.let { persistLandedSpots(it) }
            }
        } else {
            val key = "$normHex@${Math.round(lat * 100.0)}:${Math.round(lon * 100.0)}"
            landedSpotsByKey[key] = LandedSpot(normHex, agencyId, lat, lon, 1, nowMs)
            context?.let { persistLandedSpots(it) }
        }
    }

    fun currentlyGroundedHexes(nowMs: Long = System.currentTimeMillis()): Set<String> {
        groundedAtByHex.entries.removeIf { nowMs - it.value > GROUNDED_RECENT_MS }
        val out = HashSet<String>(groundedAtByHex.keys)
        for (seen in all(nowMs)) {
            if (isGroundedOrParked(seen.plane)) {
                out.add(seen.plane.hex.uppercase())
            }
        }
        return out
    }

    fun landedSpots(context: Context?): List<LandedSpot> {
        ensureLandedLoaded(context)
        return landedSpotsByKey.values.toList()
    }

    /** 2-pass Chaikin corner cutting smoothing preserving start and end coordinates. */
    fun chaikinSmooth(points: List<Pair<Double, Double>>, iterations: Int = 2): List<Pair<Double, Double>> {
        if (points.size < 3 || iterations <= 0) return points
        var cur = points
        repeat(iterations) {
            if (cur.size < 3) return cur
            val next = ArrayList<Pair<Double, Double>>(cur.size * 2)
            next.add(cur.first())
            for (i in 0 until cur.size - 1) {
                val p0 = cur[i]
                val p1 = cur[i + 1]
                next.add((0.75 * p0.first + 0.25 * p1.first) to (0.75 * p0.second + 0.25 * p1.second))
                next.add((0.25 * p0.first + 0.75 * p1.first) to (0.25 * p0.second + 0.75 * p1.second))
            }
            next.add(cur.last())
            cur = next
        }
        return cur
    }

    /** Every aircraft from the latest poll, for the map; empty when the source is off. */
    @Volatile var latest: List<Seen> = emptyList()
    @Volatile var latestAt = 0L
    /** Every aircraft the RTL-SDR's ADS-B receiver has a position for. */
    @Volatile var local: List<Seen> = emptyList()
    @Volatile var localAt = 0L

    /** Aircraft the other Sentinels sent: ICAO address to (when it arrived, as checked here). */
    val shared = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Seen>>()
    const val SHARED_MS = 5 * 60_000L

    /** This phone's own aircraft, heard by the RTL-SDR and from the online feeds, while fresh; the RTL-SDR's are newer. */
    fun own(now: Long = System.currentTimeMillis()): List<Seen> {
        val mine = if (now - localAt <= 30_000L) local else emptyList()
        val online = if (now - latestAt <= 3 * 60_000L) latest else emptyList()
        if (mine.isEmpty()) return online
        val heard = mine.mapTo(HashSet()) { it.plane.hex }
        return mine + online.filter { it.plane.hex !in heard }
    }

    /** The aircraft to draw: this phone's own, then those the other Sentinels sent that it doesn't have. */
    fun all(now: Long = System.currentTimeMillis()): List<Seen> {
        val own = own(now)
        if (shared.isEmpty()) return own
        shared.entries.removeIf { now - it.value.first > SHARED_MS }
        val heard = own.mapTo(HashSet()) { it.plane.hex }
        return own + shared.values.map { it.second }.filter { it.plane.hex !in heard }
    }

    /** Aircraft [hex] (ICAO address) as the map draws it, or null. */
    fun current(hex: String, now: Long = System.currentTimeMillis()): Seen? =
        all(now).firstOrNull { it.plane.hex.equals(hex, ignoreCase = true) }

    fun feedUrls(lat: Double, lon: Double, radiusKm: Int): List<String> {
        val nm = Math.round(radiusKm / 1.852).toInt().coerceIn(1, 250)
        // Rounded to ~1 km: the feeds don't need your exact position.
        val la = String.format(java.util.Locale.US, "%.2f", lat)
        val lo = String.format(java.util.Locale.US, "%.2f", lon)
        return listOf(
            "https://opendata.adsb.fi/api/v2/lat/$la/lon/$lo/dist/$nm",
            "https://api.adsb.lol/v2/point/$la/$lo/$nm"
        )
    }

}
