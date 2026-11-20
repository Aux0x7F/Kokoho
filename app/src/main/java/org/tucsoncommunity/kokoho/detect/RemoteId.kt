package org.tucsoncommunity.kokoho.detect

/**
 * Decoder for ASTM F3411 drone Remote ID broadcasts (the FAA / EU standard),
 * written from the message layout in opendroneid-core-c (Apache-2.0,
 * github.com/opendroneid/opendroneid-core-c). Remote ID is a legally required
 * public broadcast; reading it is purely passive.
 *
 * Transports handled:
 *  - BLE legacy/extended adverts: service data UUID 0xFFFA, payload
 *    [0x0D app code][counter][25-byte message or message pack]
 *  - WiFi beacons: vendor IE (221) with OUI FA:0B:BC, type 0x0D, then
 *    [counter][message pack]
 *  - The French "signalement electronique" (decree / arrêté of 27 Dec 2019, required in
 *    France since June 2020 for drones of 800 g and more): vendor IE with OUI 6A:5C:35,
 *    type 0x01, then TLVs (layout from the reference beacon github.com/khancyr/droneID_FR).
 *  - DJI DroneID Wi-Fi beacon vendor IE: OUI 26:37:12, subtype 0x58, FlightRegInfo (0x10)
 *    and FlightPurpose (0x11) records in little-endian radians x 1e7.
 */
object RemoteId {

    const val MESSAGE_SIZE = 25
    private const val APP_CODE = 0x0D
    private const val DJI_VENDOR_TYPE = 0x58
    private const val DJI_SUBCMD_FLIGHT_REG = 0x10
    private const val DJI_SUBCMD_FLIGHT_PURPOSE = 0x11
    private const val DJI_RAD_E7_TO_DEG = (180.0 / Math.PI) * 1e-7

    /** Everything decoded so far for one aircraft; messages arrive one type at a time. */
    data class Info(
        val uasId: String? = null,
        val idType: String? = null,
        val uaType: String? = null,
        val status: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitudeGeoM: Double? = null,
        val heightM: Double? = null,
        val speedMs: Double? = null,
        val verticalSpeedMs: Double? = null,
        val directionDeg: Int? = null,
        val operatorLatitude: Double? = null,
        val operatorLongitude: Double? = null,
        val selfIdDescription: String? = null,
        val operatorId: String? = null
    ) {
        val hasPosition get() = latitude != null && longitude != null
        val hasOperatorPosition get() = operatorLatitude != null && operatorLongitude != null
    }

    private val UA_TYPES = arrayOf(
        "Not declared", "Aeroplane", "Helicopter / multirotor", "Gyroplane", "Hybrid lift (VTOL)",
        "Ornithopter", "Glider", "Kite", "Free balloon", "Captive balloon", "Airship",
        "Parachute", "Rocket", "Tethered powered aircraft", "Ground obstacle", "Other"
    )
    private val ID_TYPES = arrayOf("None", "Serial number (ANSI/CTA-2063-A)", "CAA registration", "UTM UUID", "Specific session ID")
    private val STATUS = arrayOf("Undeclared", "Ground", "Airborne", "Emergency", "Remote ID system failure")

    /** Decodes BLE service data for UUID 0xFFFA, merging into [prev]. Null if not Remote ID. */
    fun decodeBle(serviceData: ByteArray, prev: Info?): Info? {
        if (serviceData.size < 2 + MESSAGE_SIZE || Bytes.u8(serviceData, 0) != APP_CODE) return null
        return decodeMessage(serviceData, 2, prev ?: Info())
    }

    /** Decodes a WiFi vendor IE payload (starting at the OUI), merging into [prev]. */
    fun decodeWifiIe(ie: ByteArray, prev: Info?): Info? {
        if (isFrenchIe(ie)) return decodeFrench(ie, prev ?: Info())
        if (isDjiDroneIdIe(ie)) return decodeDji(ie, prev)
        if (!isAstmWifiIe(ie) || ie.size < 5 + 3) return null
        return decodeMessage(ie, 5, prev ?: Info())
    }

    /** ASTM F3411, French electronic ID, or DJI DroneID vendor IE. */
    fun isWifiIe(ie: ByteArray): Boolean = isAstmWifiIe(ie) || isFrenchIe(ie) || isDjiDroneIdIe(ie)

    fun isAstmWifiIe(ie: ByteArray): Boolean =
        ie.size >= 4 && Bytes.u8(ie, 0) == 0xFA && Bytes.u8(ie, 1) == 0x0B &&
            Bytes.u8(ie, 2) == 0xBC && Bytes.u8(ie, 3) == APP_CODE

    fun isFrenchIe(ie: ByteArray): Boolean =
        ie.size >= 4 && Bytes.u8(ie, 0) == 0x6A && Bytes.u8(ie, 1) == 0x5C &&
            Bytes.u8(ie, 2) == 0x35 && Bytes.u8(ie, 3) == 0x01

    fun isDjiDroneIdIe(ie: ByteArray): Boolean = djiSubcommandOffset(ie) != null

    /**
     * Resolves (subcommand, recordStartOffset) for DJI DroneID Wi-Fi vendor IEs (OUI 26:37:12,
     * subtype 0x58). Standard beacon frames carry [26, 37, 12, 58, 62, 13, subcmd, ...]; compact
     * frames without the 2-byte preamble carry [26, 37, 12, 58, subcmd, ...].
     */
    private fun djiSubcommandOffset(ie: ByteArray): Pair<Int, Int>? {
        if (ie.size < 5 || Bytes.u8(ie, 0) != 0x26 || Bytes.u8(ie, 1) != 0x37 ||
            Bytes.u8(ie, 2) != 0x12 || Bytes.u8(ie, 3) != DJI_VENDOR_TYPE
        ) return null
        val sub4 = Bytes.u8(ie, 4)
        if (ie.size >= 7 && sub4 != DJI_SUBCMD_FLIGHT_REG && sub4 != DJI_SUBCMD_FLIGHT_PURPOSE) {
            val sub6 = Bytes.u8(ie, 6)
            if (sub6 == DJI_SUBCMD_FLIGHT_REG || sub6 == DJI_SUBCMD_FLIGHT_PURPOSE) return sub6 to 7
        }
        if (sub4 == DJI_SUBCMD_FLIGHT_REG || sub4 == DJI_SUBCMD_FLIGHT_PURPOSE) return sub4 to 5
        return null
    }

    private fun decodeDji(ie: ByteArray, prev: Info?): Info? {
        val (sub, rec) = djiSubcommandOffset(ie) ?: return null
        return when (sub) {
            DJI_SUBCMD_FLIGHT_REG -> decodeDjiFlightReg(ie, rec, prev)
            DJI_SUBCMD_FLIGHT_PURPOSE -> decodeDjiFlightPurpose(ie, rec, prev)
            else -> null
        }
    }

    private fun decodeDjiFlightReg(ie: ByteArray, rec: Int, prev: Info?): Info? {
        // Minimum header through drone lon/lat: version(1) + seq(2) + state(2) + serial(16) + lon(4) + lat(4) = 29 bytes.
        if (ie.size < rec + 29) return null
        val version = Bytes.u8(ie, rec)
        if (version != 1 && version != 2) return null
        val base = prev ?: Info()
        val stateInfo = Bytes.u16le(ie, rec + 3)
        val serialValid = stateInfo == 0 || (stateInfo and 0x0001) != 0
        val serial = if (serialValid) Bytes.ascii(ie, rec + 5, 16) else null
        val status = when {
            (stateInfo and 0x0020) != 0 -> "Airborne"
            stateInfo != 0 -> "Ground"
            else -> base.status
        }
        val rawLon = Bytes.i32le(ie, rec + 21)
        val rawLat = Bytes.i32le(ie, rec + 25)
        val dronePos = decodeDjiCoordPair(rawLat = rawLat, rawLon = rawLon)

        val altGeoM = if (ie.size >= rec + 31) {
            Bytes.i16le(ie, rec + 29).toDouble().takeIf {
                it in -500.0..10_000.0 && (it != 0.0 || dronePos != null)
            }
        } else null

        val heightM = if (ie.size >= rec + 33) {
            (Bytes.i16le(ie, rec + 31) * 0.1).takeIf {
                it in -200.0..3_000.0 && (it != 0.0 || dronePos != null)
            }
        } else null

        var speedMs = base.speedMs
        var vertSpeedMs = base.verticalSpeedMs
        if (ie.size >= rec + 39) {
            val vNorth = Bytes.i16le(ie, rec + 33) * 0.01
            val vEast = Bytes.i16le(ie, rec + 35) * 0.01
            val vUp = Bytes.i16le(ie, rec + 37) * 0.01
            kotlin.math.hypot(vNorth, vEast).takeIf { it in 0.0..120.0 }?.let { speedMs = it }
            vUp.takeIf { it in -60.0..60.0 }?.let { vertSpeedMs = it }
        }

        var yawDeg = base.directionDeg
        var opPos: Pair<Double, Double>? = null
        if (version == 1) {
            if (ie.size >= rec + 45) {
                decodeDjiYawDeg(Bytes.i16le(ie, rec + 43))?.let { yawDeg = it }
            }
            if (ie.size >= rec + 53) {
                opPos = decodeDjiCoordPair(
                    rawLat = Bytes.i32le(ie, rec + 49),
                    rawLon = Bytes.i32le(ie, rec + 45)
                )
            }
        } else {
            if (ie.size >= rec + 41) {
                decodeDjiYawDeg(Bytes.i16le(ie, rec + 39))?.let { yawDeg = it }
            }
            val appPos = if (ie.size >= rec + 57) {
                val f0 = Bytes.i32le(ie, rec + 49)
                val f1 = Bytes.i32le(ie, rec + 53)
                decodeDjiCoordPair(rawLat = f0, rawLon = f1) ?: decodeDjiLonLatIfUnambiguous(f0, f1)
            } else null
            val homePos = if (ie.size >= rec + 65) {
                decodeDjiCoordPair(
                    rawLat = Bytes.i32le(ie, rec + 61),
                    rawLon = Bytes.i32le(ie, rec + 57)
                )
            } else null
            opPos = appPos ?: homePos
        }

        return base.copy(
            uasId = serial ?: base.uasId,
            idType = if (serial != null) ID_TYPES[1] else base.idType,
            uaType = base.uaType ?: UA_TYPES[2],
            status = status,
            latitude = dronePos?.first ?: base.latitude,
            longitude = dronePos?.second ?: base.longitude,
            altitudeGeoM = altGeoM ?: base.altitudeGeoM,
            heightM = heightM ?: base.heightM,
            speedMs = speedMs,
            verticalSpeedMs = vertSpeedMs,
            directionDeg = yawDeg,
            operatorLatitude = opPos?.first ?: base.operatorLatitude,
            operatorLongitude = opPos?.second ?: base.operatorLongitude
        )
    }

    private fun decodeDjiFlightPurpose(ie: ByteArray, rec: Int, prev: Info?): Info? {
        if (ie.size < rec + 16) return null
        val base = prev ?: Info()
        val serial = Bytes.ascii(ie, rec, 16)
        var opId = base.operatorId
        var purpose = base.selfIdDescription
        if (ie.size >= rec + 17) {
            val idLen = Bytes.u8(ie, rec + 16).coerceIn(0, 10)
            if (idLen > 0 && ie.size >= rec + 17 + idLen) {
                Bytes.ascii(ie, rec + 17, idLen)?.let { if (opId == null) opId = it }
            }
            if (ie.size >= rec + 28) {
                val pLen = Bytes.u8(ie, rec + 27)
                val avail = (ie.size - (rec + 28)).coerceAtLeast(0)
                val n = minOf(pLen, avail)
                if (n > 0) Bytes.ascii(ie, rec + 28, n)?.let { purpose = it }
            }
        }
        return base.copy(
            uasId = serial ?: base.uasId,
            idType = if (serial != null) ID_TYPES[1] else base.idType,
            uaType = base.uaType ?: UA_TYPES[2],
            operatorId = opId,
            selfIdDescription = purpose
        )
    }

    private fun decodeDjiCoordPair(rawLat: Int, rawLon: Int): Pair<Double, Double>? {
        if (rawLat == 0 && rawLon == 0) return null
        if (rawLat == Int.MIN_VALUE || rawLon == Int.MIN_VALUE) return null
        val lat = rawLat * DJI_RAD_E7_TO_DEG
        val lon = rawLon * DJI_RAD_E7_TO_DEG
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (kotlin.math.abs(lat) < 1e-5 && kotlin.math.abs(lon) < 1e-5) return null
        return lat to lon
    }

    private fun decodeDjiLonLatIfUnambiguous(firstRaw: Int, secondRaw: Int): Pair<Double, Double>? {
        val firstDeg = firstRaw * DJI_RAD_E7_TO_DEG
        if (firstDeg in -90.0..90.0) return null
        return decodeDjiCoordPair(rawLat = secondRaw, rawLon = firstRaw)
    }

    private fun decodeDjiYawDeg(rawCentiDeg: Int): Int? {
        if (rawCentiDeg !in -36_000..36_000) return null
        val deg = kotlin.math.round(rawCentiDeg / 100.0).toInt()
        return ((deg % 360) + 360) % 360
    }

    /**
     * French TLVs: 2 = French ID (30 chars), 3 = ANSI/CTA-2063 serial, 4/5 = latitude /
     * longitude (int32 big-endian, degrees x 1e5), 6 = altitude MSL (int16 m), 7 = height
     * above take-off (int16 m), 8/9 = take-off point, 10 = ground speed (m/s), 11 = heading (deg).
     */
    private fun decodeFrench(b: ByteArray, info: Info): Info {
        var acc = info.copy(uaType = info.uaType ?: "Drone (French electronic ID)")
        var i = 4
        while (i + 2 <= b.size) {
            val type = Bytes.u8(b, i)
            val len = Bytes.u8(b, i + 1)
            val v = i + 2
            if (v + len > b.size) break
            fun int(): Int {
                var x = 0
                for (k in 0 until len) x = (x shl 8) or Bytes.u8(b, v + k)
                val bits = len * 8
                return if (bits in 1..31 && x and (1 shl (bits - 1)) != 0) x - (1 shl bits) else x
            }
            acc = when (type) {
                2 -> Bytes.ascii(b, v, len)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { acc.copy(uasId = it, idType = "French ID (signalement électronique)") } ?: acc
                3 -> if (acc.idType?.startsWith("French") == true) acc else Bytes.ascii(b, v, len)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { acc.copy(uasId = it, idType = ID_TYPES[1]) } ?: acc
                4 -> if (len == 4 && int() in -9_000_000..9_000_000) acc.copy(latitude = int() * 1e-5) else acc
                5 -> if (len == 4 && int() in -18_000_000..18_000_000) acc.copy(longitude = int() * 1e-5) else acc
                6 -> if (len == 2) acc.copy(altitudeGeoM = int().toDouble()) else acc
                7 -> if (len == 2) acc.copy(heightM = int().toDouble()) else acc
                8 -> if (len == 4 && int() in -9_000_000..9_000_000) acc.copy(operatorLatitude = int() * 1e-5) else acc
                9 -> if (len == 4 && int() in -18_000_000..18_000_000) acc.copy(operatorLongitude = int() * 1e-5) else acc
                10 -> if (len == 1) acc.copy(speedMs = Bytes.u8(b, v).toDouble()) else acc
                11 -> if (len == 2) acc.copy(directionDeg = int().takeIf { it in 0..359 } ?: acc.directionDeg) else acc
                else -> acc
            }
            i = v + len
        }
        // (0, 0) means no GPS fix yet.
        if (acc.latitude == 0.0 && acc.longitude == 0.0) acc = acc.copy(latitude = info.latitude, longitude = info.longitude)
        if (acc.operatorLatitude == 0.0 && acc.operatorLongitude == 0.0)
            acc = acc.copy(operatorLatitude = info.operatorLatitude, operatorLongitude = info.operatorLongitude)
        return acc
    }

    fun isBleRemoteId(serviceData: ByteArray): Boolean =
        serviceData.size >= 2 + MESSAGE_SIZE && Bytes.u8(serviceData, 0) == APP_CODE

    private fun decodeMessage(b: ByteArray, off: Int, info: Info): Info {
        if (off >= b.size) return info
        val type = Bytes.u8(b, off) ushr 4
        if (type == 0xF) return decodePack(b, off, info)
        if (off + MESSAGE_SIZE > b.size) return info
        return when (type) {
            0 -> decodeBasicId(b, off, info)
            1 -> decodeLocation(b, off, info)
            3 -> info.copy(selfIdDescription = Bytes.ascii(b, off + 2, 23) ?: info.selfIdDescription)
            4 -> decodeSystem(b, off, info)
            5 -> info.copy(operatorId = Bytes.ascii(b, off + 2, 20) ?: info.operatorId)
            else -> info // 2 = authentication: nothing user-facing
        }
    }

    private fun decodePack(b: ByteArray, off: Int, info: Info): Info {
        if (off + 3 > b.size) return info
        val size = Bytes.u8(b, off + 1)
        val count = Bytes.u8(b, off + 2)
        if (size != MESSAGE_SIZE || count == 0 || count > 9) return info
        var acc = info
        for (i in 0 until count) {
            val msgOff = off + 3 + i * MESSAGE_SIZE
            if (msgOff + MESSAGE_SIZE > b.size) break
            if (Bytes.u8(b, msgOff) ushr 4 == 0xF) continue // no nested packs
            acc = decodeMessage(b, msgOff, acc)
        }
        return acc
    }

    private fun decodeBasicId(b: ByteArray, off: Int, info: Info): Info {
        val idType = Bytes.u8(b, off + 1) ushr 4
        val uaType = Bytes.u8(b, off + 1) and 0x0F
        val id = Bytes.ascii(b, off + 2, 20)
        return info.copy(
            uasId = id ?: info.uasId,
            idType = ID_TYPES.getOrNull(idType) ?: info.idType,
            uaType = UA_TYPES.getOrNull(uaType) ?: info.uaType
        )
    }

    private fun decodeLocation(b: ByteArray, off: Int, info: Info): Info {
        val flags = Bytes.u8(b, off + 1)
        val speedMult = flags and 0x01
        val ewDirection = (flags ushr 1) and 0x01
        val status = flags ushr 4
        val dirEnc = Bytes.u8(b, off + 2)
        val speedEnc = Bytes.u8(b, off + 3)
        val vSpeedEnc = b[off + 4].toInt() // signed
        val lat = Bytes.i32le(b, off + 5)
        val lon = Bytes.i32le(b, off + 9)
        val altGeo = Bytes.u16le(b, off + 15)
        val height = Bytes.u16le(b, off + 17)

        val direction = dirEnc + if (ewDirection == 1) 180 else 0
        val speed = when {
            speedEnc == 255 -> null
            speedMult == 1 -> speedEnc * 0.75 + 255 * 0.25
            else -> speedEnc * 0.25
        }
        return info.copy(
            status = STATUS.getOrNull(status) ?: info.status,
            latitude = latOf(lat) ?: info.latitude,
            longitude = lonOf(lon) ?: info.longitude,
            altitudeGeoM = decodeAltitude(altGeo) ?: info.altitudeGeoM,
            heightM = decodeAltitude(height) ?: info.heightM,
            speedMs = speed ?: info.speedMs,
            verticalSpeedMs = if (vSpeedEnc == 63) info.verticalSpeedMs else vSpeedEnc * 0.5,
            directionDeg = if (direction in 0..359) direction else info.directionDeg
        )
    }

    private fun decodeSystem(b: ByteArray, off: Int, info: Info): Info {
        val lat = Bytes.i32le(b, off + 2)
        val lon = Bytes.i32le(b, off + 6)
        return info.copy(
            operatorLatitude = latOf(lat) ?: info.operatorLatitude,
            operatorLongitude = lonOf(lon) ?: info.operatorLongitude
        )
    }

    /** Degrees x 1e7; 0 is "no fix", and a value off the globe is a corrupt frame: both null. */
    private fun latOf(e7: Int): Double? = (e7 * 1e-7).takeIf { e7 != 0 && it in -90.0..90.0 }
    private fun lonOf(e7: Int): Double? = (e7 * 1e-7).takeIf { e7 != 0 && it in -180.0..180.0 }

    /** Encoded 0 means "unknown" (-1000 m). */
    private fun decodeAltitude(enc: Int): Double? = if (enc == 0) null else enc * 0.5 - 1000.0
}
