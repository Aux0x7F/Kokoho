package org.tucsoncommunity.kokoho.sdr

import org.tucsoncommunity.kokoho.online.PoliceAircraft
import org.tucsoncommunity.kokoho.posture.KokohoReferenceTables
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import java.util.Locale

/**
 * Aircraft heard by the RTL-SDR's own ADS-B receiver ([ModeS]), put together from their
 * messages: call sign, altitude, speed and track, and position from CPR (an even and an odd
 * frame together, then each new frame against the last position). Positions more than
 * [MAX_RANGE_M] away are dropped as decoding errors.
 */
class AdsbTracker {

    private class Plane(val icao: Int) {
        var lastSeen = 0L
        var messages = 0
        var signal = 0
        var callsign: String? = null
        var altitudeFt: Int? = null
        var speedKt: Double? = null
        var trackDeg: Double? = null
        var onGround = false
        var even: Cpr? = null; var odd: Cpr? = null
        var lat = 0.0; var lon = 0.0; var positionAt = 0L
    }

    private class Cpr(val lat: Int, val lon: Int, val at: Long)

    private val planes = HashMap<Int, Plane>()

    /** Adds one message heard at [now]; [refLat] / [refLon]: where you are, when known. */
    fun add(f: ModeS.Frame, now: Long, refLat: Double?, refLon: Double?) {
        val info = ModeS.decode(f)
        val p = planes.getOrPut(info.icao) { Plane(info.icao) }
        p.lastSeen = now; p.messages++; p.signal = f.signal
        info.callsign?.let { p.callsign = it }
        info.altitudeFt?.let { p.altitudeFt = it }
        info.speedKt?.let { p.speedKt = it }
        info.trackDeg?.let { p.trackDeg = it }
        if (info.onGround) p.onGround = true else if ((info.altitudeFt ?: 0) > 100) p.onGround = false
        val odd = info.cprOdd ?: return
        val cpr = Cpr(info.cprLat, info.cprLon, now)
        if (odd) p.odd = cpr else p.even = cpr
        val e = p.even; val o = p.odd
        var pos: Pair<Double, Double>? = null
        if (e != null && o != null && kotlin.math.abs(e.at - o.at) <= PAIR_MS)
            pos = ModeS.globalPosition(e.lat, e.lon, o.lat, o.lon, oddNewer = odd)
        // One frame alone decodes against the aircraft's own position from moments ago (a reference
        // that can't be in the wrong zone), never against yours: a far aircraft would land near you.
        if (pos == null && p.positionAt > 0 && now - p.positionAt <= PAIR_MS)
            pos = ModeS.localPosition(info.cprLat, info.cprLon, odd, p.lat, p.lon)
        if (pos == null || pos.first !in -90.0..90.0) return
        if (refLat != null && refLon != null && DeviceRegistry.metersBetween(refLat, refLon, pos.first, pos.second) > MAX_RANGE_M) return
        p.lat = pos.first; p.lon = pos.second; p.positionAt = now
    }

    /** Aircraft with a recent position, enriched offline via [KokohoReferenceTables]. */
    fun located(now: Long): List<PoliceAircraft.Plane> = planes.values
        .filter { now - it.lastSeen <= STALE_MS && it.positionAt > 0 && now - it.positionAt <= STALE_MS }
        .map { p ->
            val hex = "%06x".format(Locale.US, p.icao)
            val ref = KokohoReferenceTables.lookupAircraft(hex)
            PoliceAircraft.Plane(
                hex = hex,
                callsign = p.callsign,
                registration = ref?.registration?.takeIf { it.isNotBlank() },
                type = ref?.icaoType?.takeIf { it.isNotBlank() },
                owner = ref?.owner?.takeIf { it.isNotBlank() },
                lat = p.lat,
                lon = p.lon,
                altitudeFt = p.altitudeFt,
                speedKt = p.speedKt,
                trackDeg = p.trackDeg,
                military = ref?.disposition == "MIL",
                onGround = p.onGround
            )
        }

    /** Addresses heard recently without a position (Mode S replies, or ADS-B without GPS). */
    fun unlocated(now: Long): List<String> = planes.values
        .filter { now - it.lastSeen <= STALE_MS && (it.positionAt == 0L || now - it.positionAt > STALE_MS) }
        .map { "%06x".format(Locale.US, it.icao) }

    /** Enriched metadata (including Mode-S altitude/callsign) for aircraft heard without a CPR position. */
    fun unlocatedPlanes(now: Long): List<PoliceAircraft.Plane> = planes.values
        .filter { now - it.lastSeen <= STALE_MS && (it.positionAt == 0L || now - it.positionAt > STALE_MS) }
        .map { p ->
            val hex = "%06x".format(Locale.US, p.icao)
            val ref = KokohoReferenceTables.lookupAircraft(hex)
            PoliceAircraft.Plane(
                hex = hex,
                callsign = p.callsign,
                registration = ref?.registration?.takeIf { it.isNotBlank() },
                type = ref?.icaoType?.takeIf { it.isNotBlank() },
                owner = ref?.owner?.takeIf { it.isNotBlank() },
                lat = Double.NaN,
                lon = Double.NaN,
                altitudeFt = p.altitudeFt,
                speedKt = p.speedKt,
                trackDeg = p.trackDeg,
                military = ref?.disposition == "MIL",
                onGround = p.onGround
            )
        }

    /**
     * True when a recently heard (<= [PENDING_TACTICAL_MS]) aircraft still lacks a fresh CPR position
     * lock and matches a non-suppressed LE/DHS/GOV entry in [KokohoReferenceTables] or tactical callsign.
     */
    fun hasPendingUnlocatedTacticalHex(now: Long): Boolean {
        for (p in planes.values) {
            val age = now - p.lastSeen
            if (p.lastSeen <= 0L || age < 0L || age > PENDING_TACTICAL_MS) continue
            if (p.positionAt > 0L && now - p.positionAt <= PAIR_MS) continue
            if (p.onGround || (p.altitudeFt != null && p.altitudeFt!! <= 100)) continue
            if (p.speedKt != null && p.speedKt!! < 15.0) continue
            val hex = "%06x".format(Locale.US, p.icao)
            val ref = KokohoReferenceTables.lookupAircraft(hex)
            if (ref != null && ref.isExcludedNonThreat && p.callsign.isNullOrBlank()) continue
            val probe = PoliceAircraft.Plane(
                hex = hex,
                callsign = p.callsign,
                registration = ref?.registration?.takeIf { it.isNotBlank() },
                type = ref?.icaoType?.takeIf { it.isNotBlank() },
                owner = ref?.owner?.takeIf { it.isNotBlank() },
                lat = 0.0,
                lon = 0.0,
                altitudeFt = p.altitudeFt,
                speedKt = p.speedKt,
                trackDeg = p.trackDeg,
                military = ref?.disposition == "MIL",
                onGround = p.onGround
            )
            if (PoliceAircraft.isGliderOrExcludedTrainer(probe)) continue
            val isExplicitRef = ref != null && !ref.isExcludedNonThreat && ref.isExplicitLeOrGov
            val agency = KokohoSignatures.classifyAircraftAgency(
                owner = probe.owner,
                registration = probe.registration,
                callsign = p.callsign,
                hitLabel = null,
                type = probe.type,
                hex = hex,
                speedKt = p.speedKt,
                isCircling = false
            )
            if (isExplicitRef || agency != null) return true
        }
        return false
    }

    val count get() = planes.size

    fun prune(now: Long) { planes.values.removeAll { now - it.lastSeen > STALE_MS } }

    companion object {
        /** Even and odd frames this close together decode globally. */
        const val PAIR_MS = 10_000L
        const val STALE_MS = 60_000L
        const val PENDING_TACTICAL_MS = 12_000L
        /** Further than a 1090 MHz receiver on a phone can hear: a bad decode. */
        const val MAX_RANGE_M = 450_000.0
    }
}
