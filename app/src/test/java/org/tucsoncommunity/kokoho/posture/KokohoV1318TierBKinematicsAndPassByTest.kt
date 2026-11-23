package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.tucsoncommunity.kokoho.BuildConfig
import org.tucsoncommunity.kokoho.data.DeviceDetailsEntity
import org.tucsoncommunity.kokoho.detect.Advert
import org.tucsoncommunity.kokoho.detect.Category
import org.tucsoncommunity.kokoho.detect.DeviceIntel
import org.tucsoncommunity.kokoho.detect.Hit
import org.tucsoncommunity.kokoho.online.PoliceAircraft
import org.tucsoncommunity.kokoho.sdr.RadioLog
import org.tucsoncommunity.kokoho.sdr.RadioWatch
import org.tucsoncommunity.kokoho.sdr.Tpms
import org.tucsoncommunity.kokoho.sdr.TpmsLog
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.service.PassByWifiScanScheduler
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.ui.KokohoReportWizard
import org.tucsoncommunity.kokoho.ui.MapEngine
import org.tucsoncommunity.kokoho.ui.MapFragment
import org.tucsoncommunity.kokoho.ui.RfMap
import org.tucsoncommunity.kokoho.util.Prefs
import java.io.File
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KokohoV1318TierBKinematicsAndPassByTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.clearTrustListenersForTest()
        DeviceTrustManager.resetTrustForTest(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoSignatures.clearTpmsStateForTest(ctx)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.lastSubmittedWitnessJsonForTest = null
        KokohoHubClient.lastSilentTierBFlushForTest = emptyList()
        PassByWifiScanScheduler.reset()
        DeviceRegistry.clear()
        RadioLog.clear()
        TpmsLog.clear()
        ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("kokoho_hub_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        KokohoSignatures.ensureLoaded(ctx)
    }

    @After
    fun tearDown() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ScanForegroundService.isRunning = false
        ScanForegroundService.setLastFixForTest(null)
        DeviceTrustManager.clearTrustListenersForTest()
        DeviceTrustManager.resetTrustForTest(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoSignatures.clearTpmsStateForTest(ctx)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.setHubUrl(ctx, KokohoHubClient.DEFAULT_HUB_URL)
        PassByWifiScanScheduler.reset()
        DeviceRegistry.clear()
        RadioLog.clear()
        TpmsLog.clear()
        ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("kokoho_hub_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun debugDefaultHubUrlTargetsKokohoNextAndPreservesExplicitCustomOverridesWhileReleaseTargetsProd() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        assertTrue("Unit test runs against debug BuildConfig", BuildConfig.DEBUG)
        assertEquals("https://kokoho-next.tucsoncommunity.online", BuildConfig.DEFAULT_HUB_URL)
        assertEquals(BuildConfig.DEFAULT_HUB_URL, KokohoHubClient.DEFAULT_HUB_URL)
        assertEquals("https://kokoho-next.tucsoncommunity.online", KokohoHubClient.hubUrl(ctx))

        // Legacy un-customized prod default in SharedPreferences on .debug upgrades cleanly to kokoho-next
        ctx.getSharedPreferences("kokoho_hub_prefs", Context.MODE_PRIVATE).edit()
            .putString("hub_url", "https://kokoho.tucsoncommunity.online")
            .putBoolean("hub_url_custom", false)
            .commit()
        assertEquals("https://kokoho-next.tucsoncommunity.online", KokohoHubClient.hubUrl(ctx))

        // Release builds default cleanly to https://kokoho.tucsoncommunity.online
        assertEquals(
            "https://kokoho.tucsoncommunity.online",
            KokohoHubClient.resolveHubUrl(savedUrl = null, hasCustomOverride = false, isDebugBuild = false)
        )
        assertEquals(
            "https://kokoho.tucsoncommunity.online",
            KokohoHubClient.resolveHubUrl(
                savedUrl = "https://kokoho-next.tucsoncommunity.online",
                hasCustomOverride = false,
                isDebugBuild = false
            )
        )

        // Explicit custom non-default URL override is honored on both debug and release
        KokohoHubClient.setHubUrl(ctx, "https://custom-relay.tucsoncommunity.online/")
        assertEquals("https://custom-relay.tucsoncommunity.online", KokohoHubClient.hubUrl(ctx))
        assertEquals(
            "https://custom-relay.tucsoncommunity.online",
            KokohoHubClient.resolveHubUrl(
                savedUrl = "https://custom-relay.tucsoncommunity.online",
                hasCustomOverride = true,
                isDebugBuild = false
            )
        )

        // Resetting to DEFAULT_HUB_URL clears custom override
        KokohoHubClient.setHubUrl(ctx, KokohoHubClient.DEFAULT_HUB_URL)
        assertEquals("https://kokoho-next.tucsoncommunity.online", KokohoHubClient.hubUrl(ctx))
    }

    @Test
    fun twoTierSignatureSplitSeparatesAlertingTierAFromNonAlertingTierBAndDropsOwnVehicleTpms() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())

        val inv = KokohoSignatures.investigativeWatchlists
        assertTrue(inv.includeAllTpms)
        assertTrue(inv.excludeOwnVehicleTpms)
        val invPrefixes = inv.macPrefixes.map { it.prefix }.toSet()
        assertEquals(
            setOf("00:18:0A", "00:1E:42", "E4:3E:D7", "00:0C:42", "74:AC:B9", "B4:FB:E4", "DC:A6:32"),
            invPrefixes
        )
        assertTrue(inv.rfRangesHz.size >= 7)

        // Tier A: confirmed high-specificity OUI/SSID, companion kit, tactical RF, & corroborated agency TPMS
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_A,
            KokohoSignatures.classifySignalTier(ctx, "00:30:44:74:AB:83", ssidOrName = "PCSD-Vehicle")
        )
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_A,
            KokohoSignatures.classifySignalTier(ctx, "00:25:DF:11:22:33", ssidOrName = "BWCDEVICE")
        )
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_A,
            KokohoSignatures.classifySignalTier(ctx, "rf:chan:163162500:169", freqHz = 163_162_500L, snrDb = 32)
        )

        val verifiedTire = Tpms.Reading("Ford", "AA11BB22", 240.0, 28.0)
        KokohoSignatures.recordTpmsAgentCorroboration(
            context = ctx,
            reading = verifiedTire,
            entityUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:00:30:44:74:AB:80"),
            agency = KokohoAgency.DHS_ICE,
            label = "ICE",
            lat = 32.2226,
            lon = -110.9747,
            witnessVerified = true,
            nowMs = 1_795_400_000_000L
        )
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_A,
            KokohoSignatures.classifySignalTier(ctx, verifiedTire.canonicalFacetKey)
        )

        // Own-vehicle TPMS (`own_tpms_v1`) -> excluded (`AMBIENT`, never Tier A or Tier B)
        val ownTireKey = "hw:kit:tpms:TOYOTA:99887766"
        KokohoSignatures.markOwnVehicleTpms(ctx, ownTireKey, nowMs = 1_795_400_000_000L)
        assertTrue(KokohoSignatures.isOwnVehicleTpms(ctx, ownTireKey))
        assertEquals(
            KokohoSignatures.WatchlistTier.AMBIENT,
            KokohoSignatures.classifySignalTier(ctx, ownTireKey)
        )

        // Tier B: wide candidate MAC prefixes, investigative RF bands, & non-own unverified TPMS (SDR + BLE)
        for (mac in listOf("00:0C:42:11:22:33", "74:AC:B9:44:55:66", "DC:A6:32:77:88:99", "00:1E:42:AA:BB:CC")) {
            assertEquals(
                "Expected $mac to classify as TIER_B",
                KokohoSignatures.WatchlistTier.TIER_B,
                KokohoSignatures.classifySignalTier(ctx, mac, ssidOrName = "link-node")
            )
        }
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_B,
            KokohoSignatures.classifySignalTier(ctx, "rf:inv:243500000:UHF_MIL_AIR", freqHz = 243_500_000L, snrDb = 24)
        )
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_B,
            KokohoSignatures.classifySignalTier(ctx, "hw:kit:tpms:SCHRADER:CAFE1234")
        )

        // BLE TPMS decoding (`Tpms.decodeBle`) + Tier B classification
        val blePayload = byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(),
            0xA0.toByte(), 0xAA.toByte(), 0x03, 0x00, // 240,288 Pa (~240.3 kPa)
            0xC4.toByte(), 0x09, 0x00, 0x00,          // 25.00 C
            0x00, 0x00
        )
        val bleTpmsAdvert = Advert(
            mac = "12:34:56:78:9A:BC",
            source = Advert.Source.BLE,
            rssi = -64,
            name = "TPMS1_789ABC",
            serviceUuids = listOf(Advert.uuid16(0xFBB0)),
            manufacturerData = mapOf(0x0100 to blePayload),
            timestamp = 1_795_400_005_000L
        )
        val decodedBleTpms = Tpms.decodeBle(bleTpmsAdvert)
        assertNotNull(decodedBleTpms)
        assertEquals("BLE", decodedBleTpms!!.protocol)
        assertEquals("hw:kit:tpms:BLE:123456789ABC", decodedBleTpms.canonicalFacetKey)
        assertEquals(
            KokohoSignatures.WatchlistTier.TIER_B,
            KokohoSignatures.classifySignalTier(ctx, decodedBleTpms.canonicalFacetKey)
        )

        // Verify Tier B candidates alone are strictly NON-ALERTING (`GREEN`, 0 activeThreats)
        val now = 1_795_400_010_000L
        val geo = DeviceRegistry.GeoSample(now, 32.2226, -110.9747)
        val wifiId = DeviceIntel.Identity("WiFi access point", emptyList())
        DeviceRegistry.report(
            Advert("00:0C:42:11:22:33", Advert.Source.WIFI, -63, "MikroTik-Bridge", timestamp = now),
            emptyList(),
            wifiId,
            "MikroTik",
            null,
            geo,
            now,
            here = geo
        )
        TpmsLog.observe(Tpms.Reading("Schrader", "CAFE1234", 230.0, 26.0), 22.0, now, 32.2226, -110.9747)
        val snap = KokohoPostureEngine.evaluate(ctx, now)
        assertEquals(PostureColor.GREEN, snap.color)
        assertTrue("Tier B signals must never emit Monitor alerts on their own", snap.activeThreats.isEmpty())
    }

    @Test
    fun alwaysOnNonOwnTpmsDuringActiveGroundDetectionsNarrowsCoMovingVehicleAndDisambiguatesFixedInfra() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())

        // Observer is STATIONARY on a release build (`isDebug = false`):
        KinematicPrivacyGate.reset()
        assertEquals(KinematicPrivacyGate.State.STATIONARY, KinematicPrivacyGate.state)
        assertFalse(
            "Without an active ground/vehicle detection while stationary on release, non-own TPMS stays gated",
            KinematicPrivacyGate.shouldCollectNonOwnTpms(hasActiveGroundOrVehicleDetection = false, isDebug = false)
        )
        assertTrue(
            "With an active ground/vehicle detection, non-own TPMS is ALWAYS collected even when stationary on release!",
            KinematicPrivacyGate.shouldCollectNonOwnTpms(hasActiveGroundOrVehicleDetection = true, isDebug = false)
        )

        val ownWheel = "hw:kit:tpms:FORD:00001111"
        KokohoSignatures.markOwnVehicleTpms(ctx, ownWheel)

        // --- Part A: Mobile Vehicle Pass-By (Co-Moving RSSI Correlation >= 0.70) ---
        val t0 = 1_795_500_000_000L
        val mobileMac = "00:30:44:74:22:10"
        val mobileUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:$mobileMac")
        val coTireFl = Tpms.Reading("Ford", "A1B2C3D1", 245.0, 31.0)
        val coTireFr = Tpms.Reading("Ford", "A1B2C3D2", 244.0, 31.0)
        val uncorrTire = Tpms.Reading("Toyota", "DEAD9999", 220.0, 27.0)

        // Pass-by trajectory: rises from weak -> sharp peak at +4s -> recedes at +9s
        val passSteps = listOf(
            Triple(0L, -82, 14.0),
            Triple(4_000L, -53, 32.0),
            Triple(9_000L, -79, 15.0)
        )
        val wifiId = DeviceIntel.Identity("WiFi access point", emptyList())
        for ((dt, routerRssi, tireSnr) in passSteps) {
            val ts = t0 + dt
            val geo = DeviceRegistry.GeoSample(ts, 32.2226, -110.9747)
            DeviceRegistry.report(
                Advert(mobileMac, Advert.Source.WIFI, routerRssi, "PCSD-Vehicle", timestamp = ts),
                emptyList(),
                wifiId,
                "Cradlepoint",
                null,
                geo,
                ts,
                here = geo
            )
            // Co-moving tires within |dt_first| <= 8s and |dt_peak| <= 4s
            TpmsLog.observe(coTireFl, tireSnr, ts + 500L, 32.2226, -110.9747)
            TpmsLog.observe(coTireFr, tireSnr - 1.0, ts + 700L, 32.2226, -110.9747)
            CoMovingTpmsTracker.recordTpmsSample(ctx, coTireFl.canonicalFacetKey, ts + 500L, tireSnr)
            CoMovingTpmsTracker.recordTpmsSample(ctx, coTireFr.canonicalFacetKey, ts + 700L, tireSnr - 1.0)
            // Own-vehicle wheel is ignored even if present at identical timestamps!
            CoMovingTpmsTracker.recordTpmsSample(ctx, ownWheel, ts + 500L, tireSnr)
            // Uncorrelated tire moving in opposite RSSI direction (fading while target peaks)
            val oppSnr = 40.0 - tireSnr
            CoMovingTpmsTracker.recordTpmsSample(ctx, uncorrTire.canonicalFacetKey, ts + 500L, oppSnr)

            KokohoPostureEngine.evaluate(ctx, ts + 800L)
        }

        val mobileDisambig = CoMovingTpmsTracker.evaluateEmitter(ctx, mobileUuid, t0 + 9_800L)
        assertEquals(CoMovingTpmsTracker.ChassisPosture.MOBILE_VEHICLE, mobileDisambig.posture)
        val matchedKeys = mobileDisambig.coMovingMatches.map { it.tpmsFacetKey }.toSet()
        assertTrue(coTireFl.canonicalFacetKey in matchedKeys)
        assertTrue(coTireFr.canonicalFacetKey in matchedKeys)
        assertFalse("Own-vehicle TPMS must never match", ownWheel in matchedKeys)
        assertFalse("Anti-correlated TPMS must never match", uncorrTire.canonicalFacetKey in matchedKeys)

        val snapMobile = KokohoPostureEngine.evaluate(ctx, t0 + 9_800L)
        val mobileThreat = snapMobile.mapThreats.firstOrNull { it.uuid == mobileUuid }
        assertNotNull(mobileThreat)
        assertTrue(
            "Expected Mobile Vehicle (Co-Moving TPMS) capability, got ${mobileThreat!!.capabilities}",
            "Mobile Vehicle (Co-Moving TPMS)" in mobileThreat.capabilities
        )
        val rawMobileObs = KokohoPostureEngine.latestRawObservationsForTest().first { it.facetKey == "hw:mac:$mobileMac" }
        assertTrue(coTireFl.canonicalFacetKey in rawMobileObs.additionalFacets)
        assertTrue(rawMobileObs.additionalFacets.any { it.startsWith("tpms:rssi_corr:") })
        assertTrue("tpms:disambig:MOBILE_VEHICLE" in rawMobileObs.additionalFacets)
        assertNotNull(rawMobileObs.rssiCorr)
        assertTrue(rawMobileObs.rssiCorr!! >= 0.70)

        // --- Part B: Fixed / Pole Infrastructure Disambiguation (>= 90s, >= 4 samples, variance <= 5 dB, 0 co-moving TPMS) ---
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        TpmsLog.clear()
        KinematicPrivacyGate.reset() // Observer stationary

        val poleMac = "00:30:44:B3:E7:08"
        val poleUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:$poleMac")
        val poleSamples = listOf(
            0L to -60,
            32_000L to -61,
            64_000L to -59,
            96_000L to -60
        )
        for ((dt, rssi) in poleSamples) {
            val ts = t0 + 200_000L + dt
            val geo = DeviceRegistry.GeoSample(ts, 32.2310, -110.9510)
            DeviceRegistry.report(
                Advert(poleMac, Advert.Source.WIFI, rssi, "", timestamp = ts),
                emptyList(),
                wifiId,
                "Cradlepoint",
                null,
                geo,
                ts,
                here = geo
            )
            // Random civilian car drives past briefly in the middle while pole emitter stays rock-steady at -60 dBm:
            if (dt == 32_000L) {
                CoMovingTpmsTracker.recordTpmsSample(ctx, "hw:kit:tpms:GM:77778888", ts - 2_000L, 12.0)
                CoMovingTpmsTracker.recordTpmsSample(ctx, "hw:kit:tpms:GM:77778888", ts, 29.0)
                CoMovingTpmsTracker.recordTpmsSample(ctx, "hw:kit:tpms:GM:77778888", ts + 3_000L, 13.0)
            }
            KokohoPostureEngine.evaluate(ctx, ts + 200L)
        }

        val poleDisambig = CoMovingTpmsTracker.evaluateEmitter(ctx, poleUuid, t0 + 296_500L)
        assertEquals(CoMovingTpmsTracker.ChassisPosture.FIXED_INFRA, poleDisambig.posture)
        assertTrue(poleDisambig.coMovingMatches.isEmpty())
        assertTrue(poleDisambig.rssiVarianceDb <= 5.0)

        val snapPole = KokohoPostureEngine.evaluate(ctx, t0 + 296_500L)
        val poleThreat = snapPole.mapThreats.firstOrNull { it.uuid == poleUuid }
        assertNotNull(poleThreat)
        assertTrue(
            "Expected Fixed / Pole Infrastructure capability, got ${poleThreat!!.capabilities}",
            "Fixed / Pole Infrastructure" in poleThreat.capabilities
        )
        val rawPoleObs = KokohoPostureEngine.latestRawObservationsForTest().first { it.facetKey == "hw:mac:$poleMac" }
        assertTrue("tpms:disambig:FIXED_INFRA" in rawPoleObs.additionalFacets)
    }

    @Test
    fun pedestrianKinematicGateAndSilentHotspotAckEnforceStationaryPrivacyAndDebugContinuousSharing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.setContributionAllowedInSettings(ctx, true)
        KokohoSignatures.setSharingActive(ctx, true)
        KinematicPrivacyGate.reset()

        val t0 = 1_795_600_000_000L
        val anchorLat = 32.222600
        val anchorLon = -110.974700

        // 1. Anchor at home/parked spot -> STATIONARY
        assertEquals(KinematicPrivacyGate.State.STATIONARY, KinematicPrivacyGate.onLocation(anchorLat, anchorLon, 0.0, t0))
        // Move only 45m (< 85m) -> still STATIONARY
        assertEquals(
            KinematicPrivacyGate.State.STATIONARY,
            KinematicPrivacyGate.onLocation(anchorLat + 0.00040, anchorLon, 1.4, t0 + 35_000L)
        )
        // Displace ~98m (>= 85m) at too-slow drift speed (0.4 m/s < 0.95 m/s) -> still STATIONARY
        assertEquals(
            KinematicPrivacyGate.State.STATIONARY,
            KinematicPrivacyGate.onLocation(anchorLat + 0.00088, anchorLon, 0.40, t0 + 65_000L)
        )

        // While STATIONARY on release (`isDebug = false`):
        assertFalse(KinematicPrivacyGate.shouldWriteUnflaggedHistory(isDebug = false))
        assertFalse(KinematicPrivacyGate.shouldShareNonTpmsTierB(ctx, nowMs = t0 + 65_000L, isDebug = false))
        // While on debug (`isDebug = true`) with Sharing active: continuous collection & sharing is ON!
        assertTrue(KinematicPrivacyGate.shouldWriteUnflaggedHistory(isDebug = true))
        assertTrue(KinematicPrivacyGate.shouldShareNonTpmsTierB(ctx, nowMs = t0 + 65_000L, isDebug = true))
        assertTrue(KinematicPrivacyGate.shouldShareAllSignalsContinuously(ctx, isDebug = true))

        // Verify ScanForegroundService history gate skips unflagged ambient rows while STATIONARY (`!allowUnflaggedHistory`)
        DeviceRegistry.clear()
        val idWifi = DeviceIntel.Identity("WiFi access point", emptyList())
        DeviceRegistry.report(
            Advert("AA:BB:CC:11:22:33", Advert.Source.WIFI, -68, "HomeWiFi", timestamp = t0),
            emptyList(),
            idWifi,
            "Netgear",
            null,
            null,
            t0
        )
        DeviceRegistry.report(
            Advert("00:25:DF:44:55:66", Advert.Source.BLE, -60, "BWCDEVICE", timestamp = t0),
            listOf(Hit(Category.BODY_CAM, "Axon Bodycam", 90, "Bodycam", "OUI")),
            DeviceIntel.Identity("BLE device", emptyList()),
            "Axon",
            null,
            null,
            t0
        )
        val allSnaps = DeviceRegistry.snapshot(t0)
        val allDetails = allSnaps.map { DeviceDetailsEntity(it.mac, t0, "{}") }
        val (keptStationary, keptDetailsStationary) = ScanForegroundService.filterHistoryForKinematicGate(
            seen = allSnaps,
            details = allDetails,
            allowUnflaggedHistory = KinematicPrivacyGate.shouldWriteUnflaggedHistory(isDebug = false)
        )
        assertEquals(listOf("00:25:DF:44:55:66"), keptStationary.map { it.mac })
        assertEquals(listOf("00:25:DF:44:55:66"), keptDetailsStationary.map { it.mac })

        // 2. Displace ~100m (>= 85m) at pedestrian walk speed (1.35 m/s >= 0.95 m/s) -> enters MOVING!
        val movingLat = anchorLat + 0.00090
        assertEquals(
            KinematicPrivacyGate.State.MOVING,
            KinematicPrivacyGate.onLocation(movingLat, anchorLon, 1.35, t0 + 80_000L)
        )
        assertTrue(KinematicPrivacyGate.shouldWriteUnflaggedHistory(isDebug = false))
        assertTrue(KinematicPrivacyGate.shouldShareNonTpmsTierB(ctx, nowMs = t0 + 80_000L, isDebug = false))

        // 3. Dwell within 35m for 2 minutes (< 3 min) -> stays MOVING; after >= 3 min (185s) -> cools back to STATIONARY!
        assertEquals(
            KinematicPrivacyGate.State.MOVING,
            KinematicPrivacyGate.onLocation(movingLat + 0.00010, anchorLon, 0.0, t0 + 200_000L)
        )
        assertEquals(
            KinematicPrivacyGate.State.STATIONARY,
            KinematicPrivacyGate.onLocation(movingLat + 0.00012, anchorLon, 0.0, t0 + 265_000L)
        )
        assertFalse(KinematicPrivacyGate.shouldShareNonTpmsTierB(ctx, nowMs = t0 + 265_000L, isDebug = false))

        // 4. Silent `"collect_investigative_context": true` ack flushes ONLY last 90s of Tier B with ZERO UI indicator!
        RecentSignalRingBuffer.clear()
        val ackNow = t0 + 300_000L
        RecentSignalRingBuffer.record(
            ctx, "hw:mac:00:0C:42:AA:BB:01", KokohoSignatures.WatchlistTier.TIER_B, "WIFI", -66.0,
            label = "Older120s", lat = 32.22, lon = -110.97, timestampMs = ackNow - 120_000L
        )
        RecentSignalRingBuffer.record(
            ctx, "hw:mac:74:AC:B9:AA:BB:02", KokohoSignatures.WatchlistTier.TIER_B, "WIFI", -61.0,
            label = "Recent45s", lat = 32.22, lon = -110.97, timestampMs = ackNow - 45_000L
        )
        RecentSignalRingBuffer.record(
            ctx, "hw:mac:AA:BB:CC:DD:EE:FF", KokohoSignatures.WatchlistTier.AMBIENT, "WIFI", -58.0,
            label = "NeighborAmbient", lat = 32.22, lon = -110.97, timestampMs = ackNow - 15_000L
        )
        ShadowToast.reset()
        val statusBefore = KokohoHubClient.statusLabel
        val flushed = KokohoHubClient.handleRoomIngestResponse(
            context = ctx,
            responseJson = """{"uuid":"11111111-2222-3333-4444-555555555555","collect_investigative_context":true}""",
            primaryFacet = "hw:mac:00:30:44:74:AB:80",
            nowMs = ackNow
        )
        assertEquals(listOf("hw:mac:74:AC:B9:AA:BB:02"), flushed.map { it.facetKey })
        assertTrue(KinematicPrivacyGate.shouldShareNonTpmsTierB(ctx, nowMs = ackNow + 10_000L, isDebug = false))
        assertEquals("Silent hotspot ack must not alter statusLabel", statusBefore, KokohoHubClient.statusLabel)
        assertNull("Silent hotspot ack must emit zero Toast / UI indicator", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun volatileTenMinuteRamRingBufferAndReportWizardOptInCheckboxDefaultToTierATierBOnly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        RecentSignalRingBuffer.clear()
        val now = System.currentTimeMillis()

        // 1. Verify 2048 circular cap and 10-minute TTL eviction (strictly in RAM, zero disk writes)
        RecentSignalRingBuffer.record(
            ctx, "hw:mac:00:30:44:00:00:01", KokohoSignatures.WatchlistTier.TIER_A, "WIFI", -60.0,
            timestampMs = now - 11 * 60_000L // > 10m old -> pruned!
        )
        for (i in 0 until 2300) {
            val mac = String.format("AA:BB:CC:%02X:%02X:%02X", (i ushr 16) and 0xFF, (i ushr 8) and 0xFF, i and 0xFF)
            RecentSignalRingBuffer.record(
                ctx, "hw:mac:$mac", KokohoSignatures.WatchlistTier.AMBIENT, "WIFI", -75.0,
                timestampMs = now - 5 * 60_000L + i
            )
        }
        assertEquals(RecentSignalRingBuffer.MAX_ENTRIES, RecentSignalRingBuffer.size(now))

        // 2. Populate fresh TIER_A, TIER_B (non-own TPMS + candidate MAC), own-vehicle TPMS, and AMBIENT signals
        RecentSignalRingBuffer.clear()
        val ownTpms = "hw:kit:tpms:FORD:11112222"
        KokohoSignatures.markOwnVehicleTpms(ctx, ownTpms, now)

        RecentSignalRingBuffer.record(
            ctx, "hw:mac:00:30:44:74:AB:80", KokohoSignatures.WatchlistTier.TIER_A, "WIFI", -58.0,
            label = "PCSD-Vehicle", lat = 32.2226, lon = -110.9747, timestampMs = now - 4 * 60_000L
        )
        RecentSignalRingBuffer.record(
            ctx, "hw:kit:tpms:SCHRADER:ABCD1234", KokohoSignatures.WatchlistTier.TIER_B, "SDR_TPMS", 24.0,
            label = "Schrader ABCD1234", lat = 32.2226, lon = -110.9747, timestampMs = now - 2 * 60_000L
        )
        RecentSignalRingBuffer.record(
            ctx, ownTpms, KokohoSignatures.WatchlistTier.AMBIENT, "SDR_TPMS", 28.0,
            timestampMs = now - 60_000L
        )
        RecentSignalRingBuffer.record(
            ctx, "hw:mac:DE:AD:BE:EF:12:34", KokohoSignatures.WatchlistTier.AMBIENT, "WIFI", -70.0,
            label = "CoffeeShopWiFi", lat = 32.2220, lon = -110.9740, timestampMs = now - 30_000L
        )

        // Default snapshot (`includeAmbient = false`): TIER_A + TIER_B only; zero own-TPMS and zero AMBIENT!
        val defaultDtos = RecentSignalRingBuffer.snapshotDtosForReport(ctx, now, includeAmbient = false)
        assertEquals(
            setOf("hw:mac:00:30:44:74:AB:80", "hw:kit:tpms:SCHRADER:ABCD1234"),
            defaultDtos.map { it.facetKey }.toSet()
        )

        // 3. Verify KokohoReportWizard UI: unchecked-by-default checkbox + plain-language path disclosure
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val act = actCtrl.get()
        try {
            KokohoReportWizard.open(
                activity = act,
                mode = KokohoReportWizard.Mode.LAW_ENFORCEMENT,
                droppedLat = 32.2226,
                droppedLon = -110.9747
            ) {}
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            val root = dialog.window!!.decorView
            val checkBoxes = findViewsByType<CheckBox>(root)
            val shareAllBox = checkBoxes.firstOrNull {
                it.text.toString().startsWith("Share all nearby signals from the last ")
            }
            assertNotNull("Expected 'Share all nearby signals from the last X min' CheckBox", shareAllBox)
            assertFalse("Share all nearby signals checkbox MUST be unchecked by default", shareAllBox!!.isChecked)

            val allTexts = findViewsByType<TextView>(root).map { it.text?.toString().orEmpty() }
            assertTrue(
                "Expected plain-language path disclosure underneath checkbox",
                allTexts.any { it.contains("along your path", ignoreCase = true) && it.contains("blocks you traveled", ignoreCase = true) }
            )

            // Fill notes & click "Save Report" with checkbox UNCHECKED -> AMBIENT excluded!
            val edits = findViewsByType<EditText>(root)
            edits.last().setText("Observed unmarked Tahoe")
            val saveBtn = findViewsByType<TextView>(root).first { it.text?.toString() == "Save Report" }
            saveBtn.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val uncheckedJson = JSONObject(KokohoHubClient.lastSubmittedWitnessJsonForTest!!)
            val uncheckedSignals = uncheckedJson.getJSONArray("nearby_signals")
            val uncheckedKeys = (0 until uncheckedSignals.length())
                .map { uncheckedSignals.getJSONObject(it).getString("facet_key") }
                .toSet()
            assertEquals(setOf("hw:mac:00:30:44:74:AB:80", "hw:kit:tpms:SCHRADER:ABCD1234"), uncheckedKeys)
            assertFalse("AMBIENT BSSID must NOT be attached when unchecked", "hw:mac:DE:AD:BE:EF:12:34" in uncheckedKeys)

            // Re-open wizard, CHECK the opt-in box, and submit -> AMBIENT included!
            KokohoReportWizard.open(
                activity = act,
                mode = KokohoReportWizard.Mode.LAW_ENFORCEMENT,
                droppedLat = 32.2226,
                droppedLon = -110.9747
            ) {}
            val dialog2 = ShadowDialog.getLatestDialog()
            val root2 = dialog2.window!!.decorView
            val shareAllBox2 = findViewsByType<CheckBox>(root2).first {
                it.text.toString().startsWith("Share all nearby signals from the last ")
            }
            shareAllBox2.isChecked = true
            findViewsByType<EditText>(root2).last().setText("Opted in to full path signals")
            findViewsByType<TextView>(root2).first { it.text?.toString() == "Save Report" }.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val checkedJson = JSONObject(KokohoHubClient.lastSubmittedWitnessJsonForTest!!)
            val checkedSignals = checkedJson.getJSONArray("nearby_signals")
            val checkedKeys = (0 until checkedSignals.length())
                .map { checkedSignals.getJSONObject(it).getString("facet_key") }
                .toSet()
            assertTrue("AMBIENT BSSID must be attached when checked", "hw:mac:DE:AD:BE:EF:12:34" in checkedKeys)
            assertFalse("Own-vehicle TPMS must still be excluded", ownTpms in checkedKeys)
        } finally {
            actCtrl.pause().stop().destroy()
        }
    }

    @Test
    fun passByWifiScanSchedulerReservesStationaryTokensFiresImmediateBurstsAndIngestsPassiveBroadcastsFree() {
        PassByWifiScanScheduler.reset()
        val t0 = 1_795_700_000_000L
        var hardwareScanCalls = 0

        // 1. Stationary routine polling (< 4.0 m/s) uses at most 2 of 4 tokens, reserving 2 burst tokens!
        assertTrue(PassByWifiScanScheduler.tryRoutinePoll(t0, currentSpeedMps = 0.0) { hardwareScanCalls++; true })
        assertTrue(PassByWifiScanScheduler.tryRoutinePoll(t0 + 15_000L, currentSpeedMps = 0.5) { hardwareScanCalls++; true })
        assertFalse(
            "Stationary routine poll must reserve 2 burst tokens in the 120s window",
            PassByWifiScanScheduler.tryRoutinePoll(t0 + 30_000L, currentSpeedMps = 0.0) { hardwareScanCalls++; true }
        )
        assertEquals(2, PassByWifiScanScheduler.usedQuotaInWindow(t0 + 30_000L))
        assertEquals(2, PassByWifiScanScheduler.remainingQuotaInWindow(t0 + 30_000L))

        // 2. Passive OS-triggered SCAN_RESULTS_AVAILABLE_ACTION broadcasts cost ZERO quota!
        PassByWifiScanScheduler.onPassiveBroadcastIngested(resultCount = 14, nowMs = t0 + 32_000L)
        PassByWifiScanScheduler.onPassiveBroadcastIngested(resultCount = 9, nowMs = t0 + 35_000L)
        assertEquals(2, PassByWifiScanScheduler.passiveBroadcastCount)
        assertEquals("Passive broadcasts must consume 0 startScan quota", 2, PassByWifiScanScheduler.usedQuotaInWindow(t0 + 35_000L))

        // 3. Low-latency BLE vehicle cue fires immediate (< 250ms) burst using reserved token #3!
        assertTrue(PassByWifiScanScheduler.onVehicleCue("BLE_VEHICLE_CUE", t0 + 40_000L) { hardwareScanCalls++; true })
        assertTrue(PassByWifiScanScheduler.lastBurstLatencyMs < PassByWifiScanScheduler.MAX_BURST_DISPATCH_LATENCY_MS)
        assertEquals(3, PassByWifiScanScheduler.usedQuotaInWindow(t0 + 40_000L))

        // 4. Cooldown (< 6s) blocks duplicate burst at +3s, then kinematic speed >= 4.0 m/s at +7s uses token #4!
        assertFalse(PassByWifiScanScheduler.onVehicleCue("TPMS_PASS_BY_CUE", t0 + 43_000L) { hardwareScanCalls++; true })
        assertTrue(PassByWifiScanScheduler.onSpeedSample(speedMps = 6.2, nowMs = t0 + 47_000L) { hardwareScanCalls++; true })
        assertEquals(4, PassByWifiScanScheduler.usedQuotaInWindow(t0 + 47_000L))
        assertEquals(0, PassByWifiScanScheduler.remainingQuotaInWindow(t0 + 47_000L))

        // 5. 5th scan within 120s is gated; once t > t0 + 120s rolls past, quota frees up automatically!
        assertFalse(PassByWifiScanScheduler.onVehicleCue("SDR_RF_CUE", t0 + 55_000L) { hardwareScanCalls++; true })
        assertTrue(PassByWifiScanScheduler.onVehicleCue("SDR_RF_CUE", t0 + 121_000L) { hardwareScanCalls++; true })
        assertEquals(5, hardwareScanCalls)
    }

    @Test
    fun exclusiveHeatToggleMaranaAvwLocationMilitaryPillGateAndCodebaseHygiene() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // --- A. Verify loc:dhs:avw-staging (`Marana Staging (AVW)`, `DHS_CBP`, `32.409500`, `-111.218400`, `mtime=0`) ---
        val gzAssetFile = File("src/main/assets/reference/public_locations.tsv.gz").let {
            if (it.exists()) it else File("app/src/main/assets/reference/public_locations.tsv.gz")
        }
        assertTrue("public_locations.tsv.gz must exist", gzAssetFile.exists())
        val gzBytes = gzAssetFile.readBytes()
        assertEquals("Gzip mtime bytes [4..7] must be 0", listOf<Byte>(0, 0, 0, 0), gzBytes.slice(4..7))
        val rawTsv = GZIPInputStream(gzBytes.inputStream()).bufferedReader(Charsets.UTF_8).readText()
        assertFalse(rawTsv.contains(Char(0x2014)))
        assertFalse(rawTsv.contains(Char(0x2013)))

        KokohoDirectory.purgeDecryptedCache(ctx)
        KokohoDirectory.ensureLoaded(ctx)
        val avw = KokohoDirectory.locationById(ctx, "loc:dhs:avw-staging")
        assertNotNull("Expected loc:dhs:avw-staging in public_locations.tsv.gz", avw)
        assertEquals("Marana Staging (AVW)", avw!!.title)
        assertEquals("DHS_CBP", avw.agencyCode)
        assertEquals(32.409500, avw.lat, 1e-6)
        assertEquals(-111.218400, avw.lon, 1e-6)
        assertEquals("FEDERAL", avw.locationGroup)

        // --- B. Verify Exclusive `Heat` Toggle (`Heat` OFF vs `Heat` ON) ---
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val act = actCtrl.get()
        try {
            KokohoSignatures.setEnabledAgencies(act, KokohoAgency.entries.toSet())
            KokohoSignatures.setMapVisibleAgencies(act, KokohoAgency.entries.toSet())
            KokohoSignatures.setMapLocationsVisible(act, true)
            Prefs.setMapBuildings3d(act, true)

            val nowMs = 1_795_800_000_000L
            val groundUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:00:30:44:74:AB:80")
            val airHex = "AE1234"
            val airUuid = KokohoPostureEngine.deterministicUuidV5("adsb:icao:$airHex")
            PoliceAircraft.recordAirborneFix(airHex, 32.220, -110.970, nowMs - 20_000L, 1200.0, airUuid)
            PoliceAircraft.recordAirborneFix(airHex, 32.230, -110.960, nowMs - 5_000L, 1220.0, airUuid)

            val snap = PostureSnapshot(
                color = PostureColor.RED,
                headline = "ICE",
                subheadline = "",
                distanceAndBearingText = "",
                bottomConfidenceText = "",
                primaryThreat = null,
                activeThreats = emptyList(),
                monitorGroups = emptyList(),
                mapThreats = listOf(
                    EvaluatedThreat(
                        uuid = groundUuid,
                        trackUuid = groundUuid,
                        agency = KokohoAgency.DHS_ICE,
                        label = "Likely ICE Fleet Router",
                        posture = PostureColor.RED,
                        overallConfidence = 86,
                        classificationConfidence = 88,
                        spatialConfidence = 84,
                        estimatedDistanceM = 120,
                        uncertaintyRadiusM = 55,
                        bearingDeg = null,
                        closingVelocityMps = null,
                        recurrenceSummary = "ICE Router",
                        evidenceSources = listOf(EvidenceBadge("WIFI_BLE_OUI", "hw:mac:00:30:44:74:AB:80", "ICE", 0.9, nowMs - 2_000L)),
                        updatedAtMs = nowMs - 2_000L,
                        lat = 32.2226,
                        lon = -110.9747,
                        isApproximatedPosition = true,
                        isPinpointed = false
                    ),
                    EvaluatedThreat(
                        uuid = airUuid,
                        trackUuid = airUuid,
                        agency = KokohoAgency.DHS_AMO,
                        label = "DHS Air Helicopter",
                        posture = PostureColor.ORANGE,
                        overallConfidence = 92,
                        classificationConfidence = 92,
                        spatialConfidence = 95,
                        estimatedDistanceM = 800,
                        uncertaintyRadiusM = 25,
                        bearingDeg = null,
                        closingVelocityMps = 45.0,
                        recurrenceSummary = "ADS-B Aircraft (N123BP)",
                        evidenceSources = listOf(EvidenceBadge("ADSB_AIR", "adsb:icao:$airHex", "DHS Air", 0.95, nowMs - 2_000L)),
                        updatedAtMs = nowMs - 2_000L,
                        lat = 32.230,
                        lon = -110.960,
                        isApproximatedPosition = true,
                        isPinpointed = true,
                        isAircraft = true,
                        isHelicopter = true,
                        aircraftHex = airHex,
                        altitudeFtAgl = 4000
                    )
                ),
                enabledScopeText = "All",
                evaluatedAtMs = nowMs
            )

            val addedSources = HashSet<String>()
            val sourceData = HashMap<String, String>()
            var lastBuildings3d = false
            val fakeEngine = object : MapEngine {
                override val provider = MapEngine.Provider.OPENFREEMAP
                override val view = View(act)
                override val credit = "test"
                override val labelFont = listOf("Noto Sans Regular")
                override fun onCreate(state: android.os.Bundle?) {}
                override fun onStart() {}
                override fun onResume() {}
                override fun onPause() {}
                override fun onStop() {}
                override fun onLowMemory() {}
                override fun onSaveInstanceState(out: android.os.Bundle) {}
                override fun onDestroy() {}
                override fun whenReady(ready: () -> Unit) { ready() }
                override fun setStyle(dark: Boolean, loaded: (Boolean) -> Unit) { loaded(true) }
                override val styleLoaded = true
                override fun setBuildings3d(enabled: Boolean) { lastBuildings3d = enabled }
                override fun hasSource(id: String) = id in addedSources
                override fun addImage(id: String, bitmap: android.graphics.Bitmap) {}
                override fun addSource(id: String) {
                    addedSources += id
                    sourceData[id] = """{"type":"FeatureCollection","features":[]}"""
                }
                override fun setData(source: String, geoJson: String) { sourceData[source] = geoJson }
                override fun addLayer(json: JSONObject) {}
                override fun setPaint(layer: String, name: String, value: String) {}
                override val camera = MapEngine.Camera(32.2226, -110.9747, 15.6, 0.0, 0.0)
                override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {}
                override fun zoomTo(zoom: Double) {}
                override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {}
                override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {}
                override fun visibleBox() = MapEngine.Box(32.5, -110.7, 32.0, -111.3)
                override fun toLatLon(x: Float, y: Float) = 32.2226 to -110.9747
                override fun setMinZoom(zoom: Double) {}
                override var onCameraMove: (() -> Unit)? = null
                override var onIdle: (() -> Unit)? = null
                override var onClick: ((Float, Float) -> Unit)? = null
                override var onLongClick: ((Double, Double) -> Unit)? = null
                override var onUserGesture: (() -> Unit)? = null
                override fun query(box: android.graphics.RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit) {
                    result(layers.map { emptyList() })
                }
                override fun setMarker(bitmap: android.graphics.Bitmap?) {}
                override val draws3dMarker = false
                override fun setMarker3d(model: File?, size: Float) {}
                override fun startPuck() = true
                override val puckOn = true
                override fun pushFix(fix: android.location.Location) {}
                override fun follow(mode: MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {}
                override var onFollowDismissed: (() -> Unit)? = null
                override fun placeBranding(topPx: Int, atEnd: Boolean) {}
            }

            val rfMap = RfMap(act, fakeEngine)
            rfMap.loadStyle(dark = true) {}

            // 1. Heat OFF -> centroids/circles, aircraft, flight tracks, 3D buildings, and locations visible; heatmap hidden!
            KokohoSignatures.setMapHeatOverlayEnabled(act, false)
            val pinsHeatOff = rfMap.drawLive(emptyList(), snap)
            assertEquals(1, pinsHeatOff.size)
            assertEquals(0, featureCount(sourceData, "rf-kokoho-heat"))
            assertTrue(featureCount(sourceData, "rf-devices") >= 1)
            assertTrue(featureCount(sourceData, "rf-precision") >= 1)
            assertEquals(1, featureCount(sourceData, "rf-aircraft"))
            assertEquals(1, featureCount(sourceData, "rf-flight-tracks"))
            assertTrue(featureCount(sourceData, "rf-kokoho-locations") >= 1)
            assertTrue("3D buildings should be enabled when Heat is OFF", lastBuildings3d)

            // 2. Heat ON -> heatmap + facility locations ONLY; centroids/circles/icons, flight tracks, & 3D buildings ALL hidden!
            KokohoSignatures.setMapHeatOverlayEnabled(act, true)
            val pinsHeatOn = rfMap.drawLive(emptyList(), snap)
            assertTrue("Heat ON must return empty detection pins", pinsHeatOn.isEmpty())
            assertEquals(1, featureCount(sourceData, "rf-kokoho-heat"))
            assertTrue("Facility locations must remain visible when Heat is ON", featureCount(sourceData, "rf-kokoho-locations") >= 1)
            assertEquals(0, featureCount(sourceData, "rf-devices"))
            assertEquals(0, featureCount(sourceData, "rf-precision"))
            assertEquals(0, featureCount(sourceData, "rf-aircraft"))
            assertEquals(0, featureCount(sourceData, "rf-drones"))
            assertEquals(0, featureCount(sourceData, "rf-drone-ops"))
            assertEquals(0, featureCount(sourceData, "rf-drone-lines"))
            assertEquals(0, featureCount(sourceData, "rf-flight-tracks"))
            assertEquals(0, featureCount(sourceData, "rf-trace"))
            assertFalse("3D buildings MUST be hidden when Heat is ON", lastBuildings3d)

            // --- C. Verify Military (Aircraft) is default OFF, opt-in for public via Filters, and standalone Mil pill stays GONE ---
            DeviceTrustManager.resetTrustForTest(act)
            assertFalse(DeviceTrustManager.hasIntelligenceAccess(act))
            assertFalse("Military aircraft must default OFF", KokohoSignatures.isMapMilitaryEnabled(act))
            assertFalse(KokohoHubClient.buildFlightsRequestPath(act, null, 1_800_000L, nowMs).contains("include_military=1"))
            KokohoSignatures.setMapMilitaryEnabled(act, true)
            assertTrue("Public user can opt in to Military (Aircraft) via Filters", KokohoSignatures.isMapMilitaryEnabled(act))
            assertTrue(KokohoHubClient.buildFlightsRequestPath(act, null, 1_800_000L, nowMs).contains("include_military=1"))
            KokohoSignatures.setMapMilitaryEnabled(act, false)

            DeviceTrustManager.setEnrolledForTest(act, seatId = "seat-resp", slug = "resp", role = "RESPONDER", roles = listOf("RESPONDER"))
            assertFalse(DeviceTrustManager.hasIntelligenceAccess(act))

            // Mount MapFragment in an activity container to verify the actual `Mil` pill view visibility across roles!
            val container = android.widget.FrameLayout(act).apply { id = android.view.View.generateViewId() }
            act.setContentView(container)
            val mapFrag = MapFragment()
            act.supportFragmentManager.beginTransaction().replace(container.id, mapFrag).commitNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            fun findMilPillParent(): View? {
                val tv = findViewsByType<TextView>(container).firstOrNull { it.text?.toString() == "Mil" } ?: return null
                return tv.parent as? View
            }
            val milWrap = findMilPillParent()
            assertNotNull("Mil pill view must be wired into MapFragment secondary bar", milWrap)
            assertEquals("Standalone Mil map pill MUST stay GONE (controlled in Filters -> Federal -> Military)", View.GONE, milWrap!!.visibility)

            // Military aircraft opt-in works for public/responder and intelligence/owner via Filters -> Federal -> Military
            KokohoSignatures.setMapMilitaryEnabled(act, true)
            assertTrue(KokohoSignatures.isMapMilitaryEnabled(act))
            assertTrue(KokohoHubClient.buildFlightsRequestPath(act, null, 1_800_000L, nowMs).contains("include_military=1"))

            DeviceTrustManager.setEnrolledForTest(
                act,
                seatId = "seat-intel",
                slug = "intel",
                role = "INTELLIGENCE",
                roles = listOf("INTELLIGENCE")
            )
            DeviceTrustManager.notifyTrustStateListeners(act)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Standalone Mil map pill MUST stay GONE even for INTELLIGENCE", View.GONE, milWrap.visibility)
            assertTrue(KokohoHubClient.buildFlightsRequestPath(act, null, 1_800_000L, nowMs).contains("include_military=1"))

            // OWNER also retains military filter access without standalone map pill
            DeviceTrustManager.setEnrolledForTest(
                act,
                seatId = "seat-owner",
                slug = "owner",
                role = "OWNER",
                roles = listOf("OWNER")
            )
            DeviceTrustManager.notifyTrustStateListeners(act)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Standalone Mil map pill MUST stay GONE even for OWNER", View.GONE, milWrap.visibility)
            assertTrue(KokohoHubClient.buildFlightsRequestPath(act, null, 1_800_000L, nowMs).contains("include_military=1"))
        } finally {
            actCtrl.pause().stop().destroy()
        }

        // --- D. Codebase Hygiene Gate: 0 em-dashes, 0 en-dashes, 0 device model leaks, 0 prohibited VPN brand strings ---
        val bugSubmission = KokohoHubClient.buildBugDiagnostics(ctx, includeLogs = true)
        assertEquals("Bug diagnostics must never attach deviceInfo hardware strings", "", bugSubmission.deviceInfo)

        val srcRoot = File("src").let { if (it.exists()) it else File("app/src") }
        assertTrue(srcRoot.exists())
        val emDash = Char(0x2014)
        val enDash = Char(0x2013)
        // Build prohibited VPN brand token dynamically so this test file itself contains zero prohibited brand literals!
        val prohibitedVpnBrand = listOf("Pro", "ton", "VPN").joinToString("")
        val prohibitedVpnBrandSpaced = listOf("Pro", "ton", " VPN").joinToString("")

        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json") }
            .forEach { f ->
                val content = f.readText(Charsets.UTF_8)
                assertFalse("Prohibited em-dash found in ${f.path}", content.contains(emDash))
                assertFalse("Prohibited en-dash found in ${f.path}", content.contains(enDash))
                assertFalse("Prohibited VPN brand found in ${f.path}", content.contains(prohibitedVpnBrand, ignoreCase = true))
                assertFalse("Prohibited VPN brand found in ${f.path}", content.contains(prohibitedVpnBrandSpaced, ignoreCase = true))
            }
    }

    private fun featureCount(sourceData: Map<String, String>, key: String): Int {
        val raw = sourceData[key] ?: return 0
        return JSONObject(raw).getJSONArray("features").length()
    }

    private inline fun <reified T : View> findViewsByType(root: View): List<T> {
        val out = ArrayList<T>()
        val queue = ArrayDeque<View>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur is T) out += cur
            if (cur is ViewGroup) {
                for (i in 0 until cur.childCount) {
                    queue.addLast(cur.getChildAt(i))
                }
            }
        }
        return out
    }
}
