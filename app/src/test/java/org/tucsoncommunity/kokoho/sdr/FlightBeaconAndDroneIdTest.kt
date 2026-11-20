package org.tucsoncommunity.kokoho.sdr

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tucsoncommunity.kokoho.detect.Advert
import org.tucsoncommunity.kokoho.detect.Category
import org.tucsoncommunity.kokoho.detect.DeviceIntel
import org.tucsoncommunity.kokoho.detect.Hit
import org.tucsoncommunity.kokoho.detect.RemoteId
import org.tucsoncommunity.kokoho.online.PoliceAircraft
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoReferenceTables
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.usb.MonitorFrames
import org.tucsoncommunity.kokoho.usb.UsbWifi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightBeaconAndDroneIdTest {

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoReferenceTables.ensureLoaded(app)
        KokohoSignatures.ensureLoaded(app)
        KokohoPostureEngine.clearRamOnlyForTest()
        PoliceAircraft.clearAirborneTrailsForTest()
        PoliceAircraft.local = emptyList()
        PoliceAircraft.localAt = 0L
        PoliceAircraft.latest = emptyList()
        PoliceAircraft.latestAt = 0L
        UsbWifi.clearDroneBeaconPriorityForTest()
        DeviceRegistry.startSession(1_000_000L)
        ScanForegroundService.setLastFixForTest(null)
    }

    @After
    fun tearDown() {
        KokohoPostureEngine.clearRamOnlyForTest()
        PoliceAircraft.clearAirborneTrailsForTest()
        PoliceAircraft.local = emptyList()
        PoliceAircraft.localAt = 0L
        UsbWifi.clearDroneBeaconPriorityForTest()
        ScanForegroundService.setLastFixForTest(null)
    }

    private fun degToDjiRadE7(deg: Double): Int =
        (deg * (Math.PI / 180.0) * 1e7).roundToInt()

    private fun buildDjiFlightRegV2Ie(
        serial: String = "1581F5BKB2301001",
        stateInfo: Int = 0x0375,
        droneLonDeg: Double = -110.9747,
        droneLatDeg: Double = 32.2226,
        altitudeMslM: Int = 785,
        heightDeciM: Int = 850,
        vNorthCmps: Int = 800,
        vEastCmps: Int = 600,
        vUpCmps: Int = 150,
        yawCentiDeg: Int = -9000,
        appLatDeg: Double = 32.2205,
        appLonDeg: Double = -110.9712,
        homeLonDeg: Double = -110.9750,
        homeLatDeg: Double = 32.2230,
        rawDroneLonOverride: Int? = null,
        rawDroneLatOverride: Int? = null
    ): ByteArray {
        val buf = ByteBuffer.allocate(7 + 67).order(ByteOrder.LITTLE_ENDIAN)
        // OUI 26:37:12 + subtype 0x58 + 2-byte beacon preamble (0x62, 0x13) + subcommand 0x10
        buf.put(0x26.toByte())
        buf.put(0x37.toByte())
        buf.put(0x12.toByte())
        buf.put(0x58.toByte())
        buf.put(0x62.toByte())
        buf.put(0x13.toByte())
        buf.put(0x10.toByte())
        // Record payload (version = 2)
        buf.put(0x02.toByte())
        buf.putShort(42.toShort())
        buf.putShort(stateInfo.toShort())
        val serialBytes = ByteArray(16)
        val rawSerial = serial.toByteArray(Charsets.US_ASCII)
        System.arraycopy(rawSerial, 0, serialBytes, 0, minOf(rawSerial.size, 16))
        buf.put(serialBytes)
        buf.putInt(rawDroneLonOverride ?: degToDjiRadE7(droneLonDeg))
        buf.putInt(rawDroneLatOverride ?: degToDjiRadE7(droneLatDeg))
        buf.putShort(altitudeMslM.toShort())
        buf.putShort(heightDeciM.toShort())
        buf.putShort(vNorthCmps.toShort())
        buf.putShort(vEastCmps.toShort())
        buf.putShort(vUpCmps.toShort())
        buf.putShort(yawCentiDeg.toShort())
        buf.putLong(1_730_000_000_000L)
        buf.putInt(if (appLatDeg == 0.0 && appLonDeg == 0.0) 0 else degToDjiRadE7(appLatDeg))
        buf.putInt(if (appLatDeg == 0.0 && appLonDeg == 0.0) 0 else degToDjiRadE7(appLonDeg))
        buf.putInt(degToDjiRadE7(homeLonDeg))
        buf.putInt(degToDjiRadE7(homeLatDeg))
        buf.put(68.toByte()) // product_type
        buf.put(0.toByte())  // uuid_len
        return buf.array()
    }

    private fun buildDjiFlightRegV1Ie(
        serial: String = "0N4DF8Q0010099",
        droneLonDeg: Double = -110.9600,
        droneLatDeg: Double = 32.2310,
        altitudeMslM: Int = 810,
        heightDeciM: Int = 620,
        yawCentiDeg: Int = 13500,
        homeLonDeg: Double = -110.9615,
        homeLatDeg: Double = 32.2302
    ): ByteArray {
        val buf = ByteBuffer.allocate(7 + 55).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x26, 0x37, 0x12, 0x58, 0x62, 0x13, 0x10))
        buf.put(0x01.toByte())
        buf.putShort(7.toShort())
        buf.putShort(0x0175.toShort())
        val serialBytes = ByteArray(16)
        val rawSerial = serial.toByteArray(Charsets.US_ASCII)
        System.arraycopy(rawSerial, 0, serialBytes, 0, minOf(rawSerial.size, 16))
        buf.put(serialBytes)
        buf.putInt(degToDjiRadE7(droneLonDeg))
        buf.putInt(degToDjiRadE7(droneLatDeg))
        buf.putShort(altitudeMslM.toShort())
        buf.putShort(heightDeciM.toShort())
        buf.putShort(300.toShort())
        buf.putShort(400.toShort())
        buf.putShort((-50).toShort())
        buf.putShort(100.toShort())  // pitch
        buf.putShort((-200).toShort()) // roll
        buf.putShort(yawCentiDeg.toShort())
        buf.putInt(degToDjiRadE7(homeLonDeg))
        buf.putInt(degToDjiRadE7(homeLatDeg))
        buf.put(51.toByte())
        buf.put(0.toByte())
        return buf.array()
    }

    private fun buildDjiFlightPurposeIe(
        serial: String = "1581F5BKB2301001",
        droneId: String = "AZ-UAS-07",
        purpose: String = "Aerial survey"
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x26, 0x37, 0x12, 0x58, 0x62, 0x13, 0x11))
        val serialBytes = ByteArray(16)
        System.arraycopy(serial.toByteArray(Charsets.US_ASCII), 0, serialBytes, 0, minOf(serial.length, 16))
        out.write(serialBytes)
        val idBytes = ByteArray(10)
        val rawId = droneId.toByteArray(Charsets.US_ASCII)
        System.arraycopy(rawId, 0, idBytes, 0, minOf(rawId.size, 10))
        out.write(minOf(rawId.size, 10))
        out.write(idBytes)
        val purposeBytes = purpose.toByteArray(Charsets.US_ASCII)
        out.write(purposeBytes.size)
        out.write(purposeBytes)
        return out.toByteArray()
    }

    private fun buildBeacon80211Frame(bssid: String, channel: Int, ssid: String, vendorIe: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // 802.11 Beacon frame control (0x80, 0x00), duration, addr1=broadcast, addr2=bssid, addr3=bssid, seq=0
        out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00))
        repeat(6) { out.write(0xFF) }
        val macBytes = bssid.split(':').map { it.toInt(16).toByte() }.toByteArray()
        out.write(macBytes)
        out.write(macBytes)
        out.write(byteArrayOf(0x10, 0x00))
        // Fixed 12-byte beacon parameters (8-byte TSF + 2-byte interval + 2-byte capab)
        out.write(ByteArray(8) { 0x01 })
        out.write(byteArrayOf(0x64, 0x00, 0x01, 0x04))
        // Tag 0 SSID
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        out.write(0)
        out.write(ssidBytes.size)
        out.write(ssidBytes)
        // Tag 3 DS channel
        out.write(byteArrayOf(3, 1, channel.toByte()))
        // Tag 221 Vendor IE
        out.write(221)
        out.write(vendorIe.size)
        out.write(vendorIe)
        return out.toByteArray()
    }

    private fun modPositive(a: Double, b: Double): Double = ((a % b) + b) % b

    private fun buildDf17PositionFrame(
        icao: Int,
        lat: Double,
        lon: Double,
        altitudeFt: Int,
        odd: Boolean,
        signal: Int = 180
    ): ModeS.Frame {
        val i = if (odd) 1 else 0
        val dLat = 360.0 / (60 - i)
        val yz = floor(131072.0 * (modPositive(lat, dLat) / dLat) + 0.5).toInt() and 0x1FFFF
        val rlat = dLat * ((yz / 131072.0) + floor(lat / dLat))
        val nl = ModeS.nl(rlat)
        val dLon = 360.0 / maxOf(nl - i, 1)
        val xz = floor(131072.0 * (modPositive(lon, dLon) / dLon) + 0.5).toInt() and 0x1FFFF

        val altN = (altitudeFt + 1000) / 25
        val altCode = ((altN and 0x7F0) shl 1) or 0x10 or (altN and 0x0F)

        val bytes = ByteArray(14)
        bytes[0] = ((17 shl 3) or 5).toByte()
        bytes[1] = ((icao ushr 16) and 0xFF).toByte()
        bytes[2] = ((icao ushr 8) and 0xFF).toByte()
        bytes[3] = (icao and 0xFF).toByte()

        var meBits = 0L
        meBits = (meBits or (11L shl 51)) // TC = 11 (1-based bits 1..5)
        meBits = (meBits or ((altCode.toLong() and 0xFFF) shl 36)) // bits 9..20
        if (odd) meBits = meBits or (1L shl 34) // bit 22
        meBits = meBits or ((yz.toLong() and 0x1FFFF) shl 17) // bits 23..39
        meBits = meBits or (xz.toLong() and 0x1FFFF) // bits 40..56

        for (k in 0 until 7) {
            bytes[4 + k] = ((meBits ushr (8 * (6 - k))) and 0xFF).toByte()
        }
        val crc = ModeS.parity(bytes, 112)
        bytes[11] = ((crc ushr 16) and 0xFF).toByte()
        bytes[12] = ((crc ushr 8) and 0xFF).toByte()
        bytes[13] = (crc and 0xFF).toByte()
        assertEquals(0, ModeS.syndrome(bytes, 112))
        return ModeS.Frame(bytes, icao, signal)
    }

    @Test
    fun djiDroneIdWiFiIeDecodesV2V1AndFlightPurposePackets() {
        val v2 = buildDjiFlightRegV2Ie()
        assertTrue(RemoteId.isWifiIe(v2))
        assertTrue(RemoteId.isDjiDroneIdIe(v2))
        assertFalse(RemoteId.isAstmWifiIe(v2))
        assertFalse(RemoteId.isFrenchIe(v2))

        val decoded = RemoteId.decodeWifiIe(v2, null)
        assertNotNull(decoded)
        decoded!!
        assertEquals("1581F5BKB2301001", decoded.uasId)
        assertEquals("Airborne", decoded.status)
        assertTrue(decoded.hasPosition)
        assertTrue(decoded.hasOperatorPosition)
        assertEquals(32.2226, decoded.latitude!!, 1e-4)
        assertEquals(-110.9747, decoded.longitude!!, 1e-4)
        assertEquals(785.0, decoded.altitudeGeoM!!, 0.01)
        assertEquals(85.0, decoded.heightM!!, 0.05)
        assertEquals(10.0, decoded.speedMs!!, 0.05)
        assertEquals(1.5, decoded.verticalSpeedMs!!, 0.05)
        assertEquals(270, decoded.directionDeg)
        assertEquals(32.2205, decoded.operatorLatitude!!, 1e-4)
        assertEquals(-110.9712, decoded.operatorLongitude!!, 1e-4)

        // Merge 0x11 FlightPurpose metadata into the live FlightRegInfo state
        val merged = RemoteId.decodeWifiIe(buildDjiFlightPurposeIe(), decoded)
        assertNotNull(merged)
        assertEquals("1581F5BKB2301001", merged!!.uasId)
        assertEquals("AZ-UAS-07", merged.operatorId)
        assertEquals("Aerial survey", merged.selfIdDescription)
        assertEquals(32.2226, merged.latitude!!, 1e-4)

        // V2 fallback to home point when pilot app GPS is (0, 0)
        val homeFallback = RemoteId.decodeWifiIe(
            buildDjiFlightRegV2Ie(appLatDeg = 0.0, appLonDeg = 0.0, homeLonDeg = -110.9750, homeLatDeg = 32.2230),
            null
        )!!
        assertEquals(32.2230, homeFallback.operatorLatitude!!, 1e-4)
        assertEquals(-110.9750, homeFallback.operatorLongitude!!, 1e-4)

        // V1 packet layout with pitch/roll/yaw and home coordinates
        val v1 = RemoteId.decodeWifiIe(buildDjiFlightRegV1Ie(), null)
        assertNotNull(v1)
        assertEquals("0N4DF8Q0010099", v1!!.uasId)
        assertEquals(32.2310, v1.latitude!!, 1e-4)
        assertEquals(-110.9600, v1.longitude!!, 1e-4)
        assertEquals(810.0, v1.altitudeGeoM!!, 0.01)
        assertEquals(62.0, v1.heightM!!, 0.05)
        assertEquals(135, v1.directionDeg)
        assertEquals(32.2302, v1.operatorLatitude!!, 1e-4)
        assertEquals(-110.9615, v1.operatorLongitude!!, 1e-4)
    }

    @Test
    fun djiDroneIdRejectsNullIslandOutOfRangeAndTruncatedFrames() {
        val priorValid = RemoteId.decodeWifiIe(buildDjiFlightRegV2Ie(), null)!!

        // (0, 0) Null Island coordinates must never produce a fix or clobber a prior valid position
        val zeroCoords = buildDjiFlightRegV2Ie(
            rawDroneLonOverride = 0,
            rawDroneLatOverride = 0,
            altitudeMslM = 0,
            heightDeciM = 0,
            appLatDeg = 0.0,
            appLonDeg = 0.0,
            homeLonDeg = 0.0,
            homeLatDeg = 0.0
        )
        val firstFromZero = RemoteId.decodeWifiIe(zeroCoords, null)!!
        assertFalse(firstFromZero.hasPosition)
        assertNull(firstFromZero.latitude)
        assertNull(firstFromZero.longitude)

        val preservedAfterZero = RemoteId.decodeWifiIe(zeroCoords, priorValid)!!
        assertEquals(32.2226, preservedAfterZero.latitude!!, 1e-4)
        assertEquals(-110.9747, preservedAfterZero.longitude!!, 1e-4)
        assertEquals(85.0, preservedAfterZero.heightM!!, 0.05)

        // Bogus off-globe coordinates (> 180 deg) must be rejected
        val bogusCoords = buildDjiFlightRegV2Ie(
            rawDroneLonOverride = 500_000_000,
            rawDroneLatOverride = 350_000_000
        )
        val bogusDecoded = RemoteId.decodeWifiIe(bogusCoords, null)!!
        assertFalse(bogusDecoded.hasPosition)

        // Truncated frames and unsupported versions/subtypes must return null
        val fullV2 = buildDjiFlightRegV2Ie()
        assertNull(RemoteId.decodeWifiIe(fullV2.copyOf(18), null))
        assertNull(RemoteId.decodeWifiIe(byteArrayOf(0x26, 0x37, 0x12, 0x58, 0x62, 0x13, 0x11, 0x41, 0x42), null))
        val badVersion = fullV2.copyOf().also { it[7] = 99 }
        assertNull(RemoteId.decodeWifiIe(badVersion, null))
        assertFalse(RemoteId.isWifiIe(byteArrayOf(0x26, 0x37, 0x12, 0x59, 0x62, 0x13, 0x10)))

        // MonitorFrames must refresh DJI DroneID vendor IEs on every beacon without 10s caching lag
        val mf = MonitorFrames()
        val mac = "60:60:1f:aa:bb:cc"
        val beaconNoFix = buildBeacon80211Frame(mac, 6, "DJI-M30T", zeroCoords)
        val beaconWithFix = buildBeacon80211Frame(mac, 6, "DJI-M30T", fullV2)
        mf.frame(beaconNoFix, 0, beaconNoFix.size, 6, -58, now = 10_000L)
        assertTrue(UsbWifi.hasActiveUnpositionedDrone(10_050L))
        mf.frame(beaconWithFix, 0, beaconWithFix.size, 6, -56, now = 10_200L)
        assertFalse(UsbWifi.hasActiveUnpositionedDrone(10_250L))
        val drained = mf.drain(10_300L, 5_000L)
        val iePayload = drained.single().infoElements.first { it.first == 221 }.second
        val liveDecoded = RemoteId.decodeWifiIe(iePayload, null)!!
        assertEquals(32.2226, liveDecoded.latitude!!, 1e-4)
    }

    @Test
    fun adsbTrackerEnrichesMetadataAndTriggersSparingCprHoldOnlyForUnlocatedTacticalHex() {
        val tracker = AdsbTracker()
        val now = 500_000L
        val refLat = 32.2226
        val refLon = -110.9747

        // Excluded flight-school trainer (a25082 -> N2483K, PA38, TRN) must NEVER trigger a 1090 MHz hold
        val trainerIcao = 0xA25082
        val trainerEven = buildDf17PositionFrame(trainerIcao, 32.1422, -111.1746, 3_800, odd = false)
        tracker.add(trainerEven, now, refLat, refLon)
        assertFalse(tracker.hasPendingUnlocatedTacticalHex(now + 100L))
        assertNull(
            SdrRadio.maybeExtendAdsbForCprLock(
                tracker = tracker,
                nowMs = now + 200L,
                currentTurnEndMs = now + 100L,
                lastHoldAtMs = 0L,
                alreadyExtendedThisTurn = false,
                multiMode = true,
                sweepHeldByWaterfall = false
            )
        )

        // CBP P-3 Orion (a0aa61 -> N142CS) heard on a single Even CPR frame: unlocated -> triggers hold
        val cbpIcao = 0xA0AA61
        val targetLat = 32.1665
        val targetLon = -110.8830
        val cbpEven = buildDf17PositionFrame(cbpIcao, targetLat, targetLon, 6_500, odd = false)
        tracker.add(cbpEven, now + 300L, refLat, refLon)
        assertTrue(tracker.located(now + 400L).isEmpty())
        assertTrue(tracker.hasPendingUnlocatedTacticalHex(now + 400L))

        val turnEnd = now + 500L
        val holdGrant = SdrRadio.maybeExtendAdsbForCprLock(
            tracker = tracker,
            nowMs = turnEnd,
            currentTurnEndMs = turnEnd,
            lastHoldAtMs = 0L,
            alreadyExtendedThisTurn = false,
            multiMode = true,
            sweepHeldByWaterfall = false
        )
        assertNotNull(holdGrant)
        assertEquals(turnEnd + SdrRadio.ADSB_CPR_HOLD_EXTEND_MS, holdGrant!!.first)
        assertEquals(turnEnd, holdGrant.second)

        // Cannot extend a second time in the same turn, nor inside the 30s cooldown
        assertNull(
            SdrRadio.maybeExtendAdsbForCprLock(
                tracker = tracker,
                nowMs = holdGrant.first,
                currentTurnEndMs = holdGrant.first,
                lastHoldAtMs = holdGrant.second,
                alreadyExtendedThisTurn = true,
                multiMode = true,
                sweepHeldByWaterfall = false
            )
        )
        assertNull(
            SdrRadio.maybeExtendAdsbForCprLock(
                tracker = tracker,
                nowMs = turnEnd + 10_000L,
                currentTurnEndMs = turnEnd + 10_000L,
                lastHoldAtMs = holdGrant.second,
                alreadyExtendedThisTurn = false,
                multiMode = true,
                sweepHeldByWaterfall = false
            )
        )

        // Complementary Odd CPR frame arrives within the 1,800 ms hold -> locks 3D fix & clears pending flag
        val cbpOdd = buildDf17PositionFrame(cbpIcao, targetLat, targetLon, 6_500, odd = true)
        val lockAt = turnEnd + 700L
        tracker.add(cbpOdd, lockAt, refLat, refLon)
        assertFalse(tracker.hasPendingUnlocatedTacticalHex(lockAt))

        val located = tracker.located(lockAt)
        assertEquals(1, located.size)
        val plane = located.single()
        assertEquals("a0aa61", plane.hex)
        assertEquals(targetLat, plane.lat, 1e-3)
        assertEquals(targetLon, plane.lon, 1e-3)
        assertEquals(6_500, plane.altitudeFt)
        // Offline enrichment from KokohoReferenceTables must populate registration, type, and owner
        assertEquals("N142CS", plane.registration)
        assertNotNull(plane.type)
        assertTrue(!plane.owner.isNullOrBlank())
    }

    @Test
    fun usbWifiChannelPriorityInterleavesBeaconChannelsWithoutStarvingPlan() {
        val basePlan = UsbWifi.HOP_24.toList() + listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
        val now = 200_000L
        assertFalse(UsbWifi.hasActiveUnpositionedDrone(now))
        assertEquals(basePlan, UsbWifi.prioritizeDroneBeaconChannels(basePlan, active = false) { it })

        // Hearing an unpositioned Skydio OUI activates sparing priority on channels 1, 6, 11, 149
        UsbWifi.noteDroneActivity("38:1D:14:01:02:03", hasPosition = false, nowMs = now)
        assertTrue(UsbWifi.hasActiveUnpositionedDrone(now + 5_000L))

        val prioritized = UsbWifi.prioritizeDroneBeaconChannels(basePlan, active = true) { it }
        assertEquals(basePlan.toSet(), prioritized.toSet())
        assertEquals(listOf(6, 149, 1, 11), prioritized.take(4))
        assertTrue(prioritized.count { it == 6 } >= 2)
        assertTrue(prioritized.count { it == 149 } >= 2)
        assertEquals(1, prioritized.count { it == 36 })
        assertEquals(1, prioritized.count { it == 165 })

        // Once positioning beacon locks GPS, priority clears immediately
        UsbWifi.noteDroneActivity("38:1D:14:01:02:03", hasPosition = true, nowMs = now + 6_000L)
        assertFalse(UsbWifi.hasActiveUnpositionedDrone(now + 7_000L))
    }

    @Test
    fun pinpoint4DDroneAndAircraftTracksRetainAltitudesAndPropagateFacets() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.setDroneDetectionEnabled(app, true)
        KokohoSignatures.setAdsbEnabled(app, true)
        KokohoSignatures.setDhsAircraftEnabled(app, true)

        val userLoc = Location("gps").apply {
            latitude = 32.2226
            longitude = -110.9747
            accuracy = 4.0f
            time = 1_000_000L
        }
        ScanForegroundService.setLastFixForTest(userLoc)

        val droneMac = "60:60:1F:22:33:44"
        val droneSteps = listOf(
            Triple(32.2230, -110.9745, 400), // 40.0 m AGL, 760 m MSL at t0
            Triple(32.2233, -110.9742, 550), // 55.0 m AGL, 775 m MSL at t0 + 2s
            Triple(32.2237, -110.9738, 720)  // 72.0 m AGL, 792 m MSL at t0 + 4s
        )
        var prevRid: RemoteId.Info? = null
        var lastTs = 1_000_000L
        for ((idx, step) in droneSteps.withIndex()) {
            lastTs = 1_000_000L + idx * 2_000L
            val altMsl = 720 + (step.third / 10)
            val ie = buildDjiFlightRegV2Ie(
                droneLatDeg = step.first,
                droneLonDeg = step.second,
                altitudeMslM = altMsl,
                heightDeciM = step.third
            )
            prevRid = RemoteId.decodeWifiIe(ie, prevRid)
            val advert = Advert(
                mac = droneMac,
                name = "DJI-M30T",
                rssi = -59,
                timestamp = lastTs,
                source = Advert.Source.WIFI
            )
            val hit = Hit(Category.DRONE, "DJI drone broadcasting DroneID (WiFi)", 95, "OUI 26:37:12", "test")
            DeviceRegistry.report(
                a = advert,
                hits = listOf(hit),
                identity = DeviceIntel.Identity("Drone", emptyList()),
                vendor = "DJI",
                remoteId = prevRid,
                location = DeviceRegistry.GeoSample(lastTs, userLoc.latitude, userLoc.longitude),
                now = lastTs
            )
            KokohoPostureEngine.evaluate(app, lastTs)
        }

        // Also feed a 2-step airborne CBP aircraft track (4,800 ft -> 5,200 ft MSL)
        val planeHex = "a0aa61"
        val airT1 = lastTs + 1_000L
        val airT2 = lastTs + 6_000L
        val plane1 = PoliceAircraft.Plane(
            hex = planeHex,
            callsign = "OMAHA1",
            registration = "N142CS",
            type = "P3",
            owner = "US Customs and Border Protection",
            lat = 32.2250,
            lon = -110.9700,
            altitudeFt = 4_800,
            speedKt = 145.0,
            trackDeg = 90.0,
            military = false
        )
        PoliceAircraft.local = listOf(
            PoliceAircraft.Seen(
                plane1,
                Hit(Category.AIRCRAFT, "Police aircraft overhead: US Customs", 90, "test", PoliceAircraft.VIA_LOCAL)
            )
        )
        PoliceAircraft.localAt = airT1
        KokohoPostureEngine.evaluate(app, airT1)

        val plane2 = plane1.copy(lat = 32.2250, lon = -110.9675, altitudeFt = 5_200)
        PoliceAircraft.local = listOf(
            PoliceAircraft.Seen(
                plane2,
                Hit(Category.AIRCRAFT, "Police aircraft overhead: US Customs", 90, "test", PoliceAircraft.VIA_LOCAL)
            )
        )
        PoliceAircraft.localAt = airT2
        val snap = KokohoPostureEngine.evaluate(app, airT2)

        val droneThreat = snap.mapThreats.firstOrNull { it.isDrone }
        assertNotNull(droneThreat)
        assertEquals(236, droneThreat!!.altitudeFtAgl) // 72.0 m * 3.28084 = 236 ft AGL

        // Verify 4D drone flight path retained in memory by MAC, facet key, and entity UUID
        val droneFixesByMac = KokohoPostureEngine.recentPinpointFlightTrack4D(droneMac, airT2)
        val droneFixesByUuid = KokohoPostureEngine.recentPinpointFlightTrack4D(droneThreat.uuid, airT2)
        assertEquals(3, droneFixesByMac.size)
        assertEquals(droneFixesByMac, droneFixesByUuid)
        assertEquals(40.0, droneFixesByMac[0].altM!!, 0.1)
        assertEquals(55.0, droneFixesByMac[1].altM!!, 0.1)
        assertEquals(72.0, droneFixesByMac[2].altM!!, 0.1)
        assertTrue(droneFixesByMac[0].timeMs < droneFixesByMac[1].timeMs)
        assertTrue(droneFixesByMac[1].timeMs < droneFixesByMac[2].timeMs)

        // Verify 4D aircraft flight path retained in memory with MSL altitude converted to meters
        val airFixes = KokohoPostureEngine.recentPinpointFlightTrack4D(planeHex, airT2)
        assertEquals(2, airFixes.size)
        assertEquals(4_800 * 0.3048, airFixes[0].altM!!, 0.5)
        assertEquals(5_200 * 0.3048, airFixes[1].altM!!, 0.5)

        // Verify raw observations carry uas:alt_ft_agl:<N> and air:alt_ft_msl:<N>
        val rawObs = KokohoPostureEngine.latestRawObservationsForTest()
        val droneObs = rawObs.firstOrNull { it.facetKey == "uas:mac:60:60:1F:22:33:44" }
        assertNotNull(droneObs)
        assertTrue(droneObs!!.additionalFacets.contains("uas:alt_ft_agl:236"))
        assertTrue(droneObs.additionalFacets.contains("air:alt_ft_msl:2598"))

        val airObs = rawObs.firstOrNull { it.facetKey == "adsb:icao:A0AA61" }
        assertNotNull(airObs)
        assertTrue(airObs!!.additionalFacets.contains("air:alt_ft_msl:5200"))
    }
}
