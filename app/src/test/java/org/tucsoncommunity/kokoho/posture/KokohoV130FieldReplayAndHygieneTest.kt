package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tucsoncommunity.kokoho.BuildConfig
import org.tucsoncommunity.kokoho.detect.Advert
import org.tucsoncommunity.kokoho.detect.DeviceIntel
import org.tucsoncommunity.kokoho.sdr.RadioLog
import org.tucsoncommunity.kokoho.sdr.RadioWatch
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.ui.DeviceDetailActivity
import org.tucsoncommunity.kokoho.util.UpdateChecker
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-420dpi")
class KokohoV130FieldReplayAndHygieneTest {

    @Test
    fun fieldReplay20261116() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoHubClient.clearCachedObjects()
        DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())

        val t0 = 1_794_845_000_000L // 2026-11-16 field replay baseline
        val idWifi = DeviceIntel.Identity("WiFi access point", emptyList())
        val idBle = DeviceIntel.Identity("BLE peripheral", emptyList())

        // 1. TPD Cruiser Misses Fixed:
        //    809.8375 MHz @ 59 dB SNR MUST match POLICE mobile uplink and NEVER be vetoed by stale FCC IG_COMM!
        val tpd809 = KokohoSignatures.matchPublicSafetyMobileUplink(
            809_837_500L,
            59,
            setOf(KokohoAgency.POLICE)
        )
        assertNotNull("809.8375 MHz @ 59 dB must match TPD mobile uplink", tpd809)
        assertEquals(KokohoAgency.POLICE, tpd809!!.agencyEnum)

        // 2. Broadway & Kolb/Wilmot Disappearing Hit + Repeater Guard + East-of-Craycroft Removal:
        //    - Distant 851.0125 MHz repeater < 40 dB (`snrDb = 30`) is suppressed.
        //    - Close-in 851.0125 MHz `>= 40 dB` (`snrDb = 48`) at Broadway & Kolb (`32.2216, -110.8410`, east of `-110.8752`)
        //      matches POLICE, persists into KokohoEntityLedger, and survives RAM clears (> 3m / restart) and Hub sync!
        assertNull(
            "Distant 851.0125 MHz repeater < 40 dB must be suppressed",
            KokohoSignatures.matchPublicSafetyMobileUplink(851_012_500L, 30, setOf(KokohoAgency.POLICE))
        )
        val broadwayKolbLat = 32.22160
        val broadwayKolbLon = -110.84100 // East of Craycroft (-110.8752)!
        val band850 = RadioWatch.Band("800", 806_000_000L, 860_000_000L, RadioWatch.Kind.SHARED)
        RadioLog.observe(
            RadioWatch.Event(851_012_500L, 48, band850, 90),
            "8CALL90",
            3,
            t0,
            broadwayKolbLat,
            broadwayKolbLon
        )
        val snapT0 = KokohoPostureEngine.evaluate(ctx, t0)
        val broadwayHit = snapT0.mapThreats.firstOrNull { it.agency == KokohoAgency.POLICE }
        assertNotNull("Broadway & Kolb TPD detection east of Craycroft must appear on map", broadwayHit)

        // Simulate 10 minutes passing + full process restart where volatile 3m RadioLog & RAM caches cleared:
        // Durable KokohoEntityLedger MUST re-hydrate from SQLite and keep the detection visible across rolling 30m / 3h / 24h windows!
        RadioLog.clear()
        DeviceRegistry.clear()
        KokohoPostureEngine.clearRamOnlyForTest()
        KokohoEntityLedger.simulateProcessRestartForTest(ctx)
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.MIN_30)
        val snapAfter10m = KokohoPostureEngine.evaluate(ctx, t0 + 10 * 60_000L)
        assertTrue(
            "Durable KokohoEntityLedger must retain Broadway & Kolb hit after 10 minutes & process restart (err=${KokohoEntityLedger.lastDebugError}, ledger=${KokohoEntityLedger.getWithinRollingWindow(ctx, t0 + 10 * 60_000L, 30 * 60_000L)})",
            snapAfter10m.mapThreats.any { it.uuid == broadwayHit!!.uuid }
        )

        // Also verify Hub east-side object (`lon = -110.8410 > -110.8752`) is NEVER dropped by KokohoHubClient!
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "east-side-tpd-8e41-4216-9b55-1a522665c0a1",
              "track_uuid": "east-side-tpd-8e41-4216-9b55-1a522665c0a1",
              "updated_at_ms": $t0,
              "confidence": { "overall": 0.85, "classification": 0.90, "spatial": 0.80, "temporal_freshness": 1.0 },
              "location": { "lat": 32.2216, "lon": -110.8410, "uncertainty_radius_m": 95.0, "h3_res8": 614259430555648001, "propagation_class": "GROUND_DIRECT" },
              "classification": {
                "master_group": "OTHER",
                "category": "POLICE",
                "label": "Tucson PD",
                "recurrence_summary": "Broadway & Kolb",
                "evidence_sources": [{ "kind": "RF_P25", "facet_key": "rf:chan:809837500:ANY", "detail": "809.8375 MHz", "weight": 0.88, "last_seen_ms": $t0 }]
              }
            }
            """.trimIndent()
        )
        val eastHubSnap = KokohoPostureEngine.evaluate(ctx, t0)
        assertTrue(
            "East-of-Craycroft Hub entity must be retained without censorship wall",
            eastHubSnap.mapThreats.any { it.uuid == "east-side-tpd-8e41-4216-9b55-1a522665c0a1" }
        )

        // 3. N Euclid & Florita Room "101" False [Bodycam] Fixed:
        //    `00:23:68:90:40:50` (`SSID = "101"`, legacy WPA-TKIP+CCMP `[ESS]`, 20h uptime) MUST be suppressed
        //    and NEVER tagged as `[Bodycam]`!
        KokohoHubClient.clearCachedObjects()
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()

        val euclidGeo = DeviceRegistry.GeoSample(t0, 32.2389, -110.9598)
        val room101Adv = Advert(
            mac = "00:23:68:90:40:50",
            source = Advert.Source.WIFI,
            rssi = -62,
            name = "101",
            wifi = Advert.WifiInfo(
                2437,
                "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]",
                null,
                emptyList(),
                client = false,
                upSince = t0 - 20L * 3600_000L
            ),
            timestamp = t0
        )
        DeviceRegistry.report(room101Adv, emptyList(), idWifi, "Cisco Systems", null, euclidGeo, t0, here = euclidGeo)
        val euclidSnap = KokohoPostureEngine.evaluate(ctx, t0)
        assertTrue(
            "Room AP '101' (00:23:68:90:40:50, WPA-TKIP, 20h uptime) must be suppressed and never mislabeled as Bodycam",
            euclidSnap.activeThreats.none {
                it.hardwareIdentifier?.contains("00:23:68", ignoreCase = true) == true ||
                    it.capabilities.contains("Bodycam")
            }
        )

        // 4. Raw BLE Hex Company IDs & 16-bit Service UUIDs (Randomized MACs + Blank Names) & Typed hw:kit:* Facets:
        DeviceRegistry.clear()
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        ScanForegroundService.setLastFixForTest(android.location.Location("gps").apply {
            latitude = 32.2226
            longitude = -110.9747
            time = t0
        })
        val downtownGeo = DeviceRegistry.GeoSample(t0, 32.2226, -110.9747)

        // Axon BLE (Company 0x034D, Service UUID 0xFE6C, randomized MAC, blank name)
        val axonBle = Advert(
            mac = "5A:11:22:33:44:55",
            source = Advert.Source.BLE,
            rssi = -58,
            name = null,
            manufacturerData = mapOf(0x034D to byteArrayOf(0x01, 0x02)),
            serviceUuids = listOf(java.util.UUID.fromString("0000fe6c-0000-1000-8000-00805f9b34fb")),
            timestamp = t0
        )
        // Zebra Printer BLE (Company 0x01F1, Service UUID 0xFE79, randomized MAC, blank name)
        val zebraBle = Advert(
            mac = "6E:AA:BB:CC:DD:EE",
            source = Advert.Source.BLE,
            rssi = -60,
            name = null,
            manufacturerData = mapOf(0x01F1 to byteArrayOf(0x01)),
            serviceUuids = listOf(java.util.UUID.fromString("0000fe79-0000-1000-8000-00805f9b34fb")),
            timestamp = t0
        )
        DeviceRegistry.report(axonBle, emptyList(), idBle, null, null, downtownGeo, t0, here = downtownGeo)
        DeviceRegistry.report(zebraBle, emptyList(), idBle, null, null, downtownGeo, t0, here = downtownGeo)

        val bleSnap = KokohoPostureEngine.evaluate(ctx, t0)
        assertTrue(
            "Randomized-MAC BLE Axon (0x034D / 0xFE6C) must emit hw:kit:bwc:* with Bodycam capability",
            bleSnap.activeThreats.any { t ->
                t.capabilities.contains("Bodycam") &&
                    t.evidenceSources.any { it.facetKey.startsWith("hw:kit:bwc:") }
            }
        )
        val printerThreat = bleSnap.activeThreats.firstOrNull { t ->
            t.evidenceSources.any { it.facetKey.startsWith("hw:kit:printer:") }
        }
        assertNotNull("Randomized-MAC BLE Zebra (0x01F1 / 0xFE79) must emit typed hw:kit:printer:*", printerThreat)
        assertTrue(printerThreat!!.capabilities.contains("Citation Printer"))
        assertFalse("Typed hw:kit:printer:* must NEVER be misclassified as Bodycam", printerThreat.capabilities.contains("Bodycam"))

        // Strict deterministic correlation: standalone BLE Axon + standalone BLE Zebra without a vehicle router
        // must remain 2 distinct entities rather than speculatively merging!
        assertEquals(2, bleSnap.activeThreats.size)

        // 5. Speedway / I-10 Field-Drive False-Positive Suppression & Legitimate Hit Preservation (2026-11-16 16:33–17:22):
        DeviceRegistry.clear()
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()

        val speedwayGeo = DeviceRegistry.GeoSample(t0, 32.23610, -110.95850)
        // 5a. KARR dealership car alarms ("QT <8hex>" / "DR <8hex>" on Microchip 40:84:32) MUST NOT trigger HSI!
        DeviceRegistry.report(
            Advert(mac = "40:84:32:63:02:28", source = Advert.Source.BLE, rssi = -82, name = "QT 32630228", timestamp = t0),
            emptyList(), idBle, "Microchip Technology Inc.", null, speedwayGeo, t0, here = speedwayGeo
        )
        DeviceRegistry.report(
            Advert(mac = "40:84:32:5A:64:31", source = Advert.Source.BLE, rssi = -78, name = "QT 325A6431", timestamp = t0),
            emptyList(), idBle, "Microchip Technology Inc.", null, speedwayGeo, t0, here = speedwayGeo
        )
        // 5b. Consumer VeryFit "IDW26" smartwatch on BLE static random MAC colliding on 0xFF manufacturer ID 0x034D MUST NOT trigger Bodycam!
        DeviceRegistry.report(
            Advert(
                mac = "F4:05:B6:7F:85:73",
                source = Advert.Source.BLE,
                rssi = -84,
                name = "IDW26",
                manufacturerData = mapOf(0x034D to byteArrayOf(0x01, 0x02)),
                timestamp = t0
            ),
            emptyList(), idBle, null, null, speedwayGeo, t0, here = speedwayGeo
        )
        // 5c. Sun Tran / transit bus Cradlepoint ("Veh 467-5g" on 00:30:44) MUST NOT trigger CBP!
        DeviceRegistry.report(
            Advert(
                mac = "00:30:44:9C:A9:D0",
                source = Advert.Source.WIFI,
                rssi = -85,
                name = "Veh 467-5g",
                wifi = Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                timestamp = t0
            ),
            emptyList(), idWifi, "CradlePoint, Inc", null, speedwayGeo, t0, here = speedwayGeo
        )
        val fpCheckSnap = KokohoPostureEngine.evaluate(ctx, t0)
        assertTrue(
            "KARR car alarms (QT/DR), IDW26 consumer smartwatch, and Veh 467-5g transit bus must produce 0 threats, got ${fpCheckSnap.activeThreats}",
            fpCheckSnap.activeThreats.isEmpty()
        )

        // 5d. Anonymous FAA TIS-B '~26a73a' radar trackfile entries MUST be excluded from tactical posture!
        val tisbParsed = org.tucsoncommunity.kokoho.online.PoliceAircraft.parseFeed(
            """{"ac":[{"hex":"~26a73a","lat":32.16316,"lon":-110.95093,"alt_baro":4200,"gs":95.0,"track":115.0}]}"""
        )
        assertTrue(
            "Non-ICAO '~' TIS-B radar trackfile pseudo-addresses must be excluded",
            org.tucsoncommunity.kokoho.online.PoliceAircraft.isGliderOrExcludedTrainer(tisbParsed.single())
        )

        // 5e. Legitimate Speedway / I-10 hits (PCSD-Vehicle 00:30:44:74:C3:C0 & N304PD A32F77) MUST remain active!
        DeviceRegistry.report(
            Advert(
                mac = "00:30:44:74:C3:C0",
                source = Advert.Source.WIFI,
                rssi = -77,
                name = "PCSD-Vehicle",
                wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                timestamp = t0
            ),
            emptyList(), idWifi, "CradlePoint, Inc", null, speedwayGeo, t0, here = speedwayGeo
        )
        val legitSnap = KokohoPostureEngine.evaluate(ctx, t0)
        assertEquals(1, legitSnap.activeThreats.size)
        assertEquals(KokohoAgency.SHERIFF, legitSnap.activeThreats.first().agency)
        assertEquals(
            KokohoAgency.POLICE,
            KokohoSignatures.classifyAircraftAgency(
                owner = "CITY OF TUCSON POLICE DEPARTMENT",
                registration = "N304PD",
                callsign = "N304PD",
                hitLabel = "Police helicopter",
                type = "B06",
                hex = "A32F77",
                speedKt = 88.0,
                isCircling = false
            )
        )

        ScanForegroundService.setLastFixForTest(null)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        RadioLog.clear()
    }

    @Test
    fun rollingWindowsAndFilters() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        RadioLog.clear()

        // 1. Rolling radius expansion (+25% over 30m, held steady across 3h and 24h)
        assertEquals(200, KokohoSignatures.projectRollingRadiusM(KokohoSignatures.MapTimeWindow.MIN_30, 0L, 200))
        assertEquals(225, KokohoSignatures.projectRollingRadiusM(KokohoSignatures.MapTimeWindow.MIN_30, 15 * 60_000L, 200))
        assertEquals(250, KokohoSignatures.projectRollingRadiusM(KokohoSignatures.MapTimeWindow.MIN_30, 30 * 60_000L, 200))
        assertEquals(250, KokohoSignatures.projectRollingRadiusM(KokohoSignatures.MapTimeWindow.HOURS_3, 120 * 60_000L, 200))
        assertEquals(250, KokohoSignatures.projectRollingRadiusM(KokohoSignatures.MapTimeWindow.HOURS_24, 18L * 3600_000L, 200))

        // 2. Window-specific rolling opacity curves (30m continuous fade; 3h tail fade 150..180m; 24h tail fade 23..24h, ZERO midnight reset!)
        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MIN_30, 0L), 0.01f)
        assertTrue(KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MIN_30, 15 * 60_000L) in 0.50f..0.65f)
        assertEquals(0.16f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MIN_30, 30 * 60_000L), 0.02f)

        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HOURS_3, 140 * 60_000L), 0.01f)
        assertTrue(KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HOURS_3, 165 * 60_000L) in 0.50f..0.65f)

        // Detection at 11:30 PM local time evaluated at 8:30 AM next morning (age = 9h across midnight):
        // 24h rolling window MUST show 1.00 opacity with zero midnight reset!
        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HOURS_24, 9L * 3600_000L), 0.01f)
        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HOURS_24, 22L * 3600_000L), 0.01f)
        assertTrue(KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HOURS_24, (23.5 * 3600_000L).toLong()) in 0.50f..0.65f)
        assertEquals(0.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HOURS_24, 25L * 3600_000L), 0.01f)

        // 3. Alarm (Monitor) vs Eye (Map Visibility) independent filter split:
        val nowMs = 1_794_850_000_000L
        val geo = DeviceRegistry.GeoSample(nowMs, 32.2226, -110.9747)
        val idWifi = DeviceIntel.Identity("WiFi access point", emptyList())
        DeviceRegistry.report(
            Advert(
                mac = "00:30:44:74:AB:83",
                source = Advert.Source.WIFI,
                rssi = -56,
                name = "PCSD-Vehicle",
                wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false, upSince = nowMs - 1800_000L),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "Cradlepoint", null, geo, nowMs, here = geo
        )
        // Alarm OFF for SHERIFF, Map Eye ON for SHERIFF -> Monitor stays GREEN while Map shows SHERIFF!
        KokohoSignatures.setEnabledAgencies(ctx, setOf(KokohoAgency.DHS_ICE))
        KokohoSignatures.setMapVisibleAgencies(ctx, setOf(KokohoAgency.DHS_ICE, KokohoAgency.SHERIFF))
        val eyeOnlySnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertEquals("Monitor posture must stay GREEN when SHERIFF Alarm is off", PostureColor.GREEN, eyeOnlySnap.color)
        assertTrue("Map threats must still include SHERIFF when SHERIFF Eye is on", eyeOnlySnap.mapThreats.any { it.agency == KokohoAgency.SHERIFF })

        // Now turn Map Eye OFF for SHERIFF -> hidden from mapThreats too!
        KokohoSignatures.setMapVisibleAgencies(ctx, setOf(KokohoAgency.DHS_ICE))
        val hiddenSnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertFalse("Map threats must hide SHERIFF when SHERIFF Eye is off", hiddenSnap.mapThreats.any { it.agency == KokohoAgency.SHERIFF })

        // Restore defaults
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.DHS_AGENCIES.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
    }

    @Test
    fun v132WindowsHeatmapAttestedBlocksAndCruiserReplay() {
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val act = actCtrl.get()
        val ctx = act.applicationContext
        KokohoSignatures.ensureLoaded(ctx)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoHubClient.clearCachedObjects()
        DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())

        // 1. All 6 UI windows exposed & legacy "ALL" migrates cleanly to HEAT
        assertEquals(
            listOf("30m", "3h", "24h", "1wk", "1mo", "Heat"),
            KokohoSignatures.MapTimeWindow.ROLLING_UI_WINDOWS.map { it.label }
        )
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("ALL"))
        assertEquals(KokohoSignatures.MapTimeWindow.WEEK_1, KokohoSignatures.MapTimeWindow.fromId("1W"))
        assertEquals(KokohoSignatures.MapTimeWindow.MONTH_1, KokohoSignatures.MapTimeWindow.fromId("1M"))

        // 2. Exact window fade curves: 1wk fades at -1d (6d..7d), 1mo fades at -1wk (23d..30d), Heat = N/A (1.0f all time)
        val dayMs = 24L * 3600_000L
        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.WEEK_1, 5L * dayMs), 0.01f)
        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.WEEK_1, 6L * dayMs), 0.01f)
        assertTrue(KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.WEEK_1, (6.5 * dayMs).toLong()) in 0.54f..0.62f)
        assertEquals(0.16f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.WEEK_1, 7L * dayMs), 0.02f)
        assertEquals(0.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.WEEK_1, 8L * dayMs), 0.01f)

        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MONTH_1, 22L * dayMs), 0.01f)
        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MONTH_1, 23L * dayMs), 0.01f)
        assertTrue(KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MONTH_1, (26.5 * dayMs).toLong()) in 0.54f..0.62f)
        assertEquals(0.16f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MONTH_1, 30L * dayMs), 0.02f)
        assertEquals(0.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.MONTH_1, 31L * dayMs), 0.01f)

        assertEquals(1.0f, KokohoSignatures.mapWindowOpacity(KokohoSignatures.MapTimeWindow.HEAT, 120L * dayMs), 0.01f)

        // 3. Multi-week / multi-month durable persistence (no 48h ledger pruning or 24h cache eviction!)
        val nowMs = 1_794_860_000_000L
        fun mkLedgerEntity(uuid: String, ageDays: Long, lat: Double, lon: Double) = EvaluatedThreat(
            uuid = uuid,
            trackUuid = uuid,
            agency = KokohoAgency.POLICE,
            label = "Police",
            posture = PostureColor.YELLOW,
            overallConfidence = 90,
            classificationConfidence = 92,
            spatialConfidence = 88,
            estimatedDistanceM = 450,
            uncertaintyRadiusM = 110,
            bearingDeg = null,
            closingVelocityMps = null,
            recurrenceSummary = "East Broadway Historical ($ageDays d)",
            evidenceSources = listOf(EvidenceBadge("RF_P25", "rf:chan:807450000:ANY", "807.4500 MHz", 0.9, nowMs - ageDays * dayMs)),
            updatedAtMs = nowMs - ageDays * dayMs,
            lat = lat,
            lon = lon,
            isApproximatedPosition = true,
            isPinpointed = false,
            sightingCount = 8
        )
        KokohoEntityLedger.upsertBatch(
            ctx,
            listOf(
                mkLedgerEntity("hist-5d-uuid-0000-000000000005", 5L, 32.2211, -110.8197),
                mkLedgerEntity("hist-20d-uuid-000-000000000020", 20L, 32.2215, -110.8068),
                mkLedgerEntity("hist-65d-uuid-000-000000000065", 65L, 32.2152, -110.8384)
            ),
            nowMs
        )
        KokohoPostureEngine.clearRamOnlyForTest()
        KokohoEntityLedger.simulateProcessRestartForTest(ctx)

        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.HOURS_24)
        val snap24h = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertFalse(snap24h.mapThreats.any { it.uuid.startsWith("hist-") })

        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.WEEK_1)
        val snap1wk = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertTrue("5d entity must appear in 1wk window", snap1wk.mapThreats.any { it.uuid == "hist-5d-uuid-0000-000000000005" })
        assertFalse("20d entity must not appear in 1wk window", snap1wk.mapThreats.any { it.uuid == "hist-20d-uuid-000-000000000020" })

        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.MONTH_1)
        val snap1mo = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertTrue("5d entity must appear in 1mo window", snap1mo.mapThreats.any { it.uuid == "hist-5d-uuid-0000-000000000005" })
        assertTrue("20d entity must appear in 1mo window", snap1mo.mapThreats.any { it.uuid == "hist-20d-uuid-000-000000000020" })
        assertFalse("65d entity must not appear in 1mo window", snap1mo.mapThreats.any { it.uuid == "hist-65d-uuid-000-000000000065" })

        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.HEAT)
        val snapHeat = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertEquals(
            "All 3 historical entities (5d, 20d, 65d) must appear in All-Time Heat window",
            setOf("hist-5d-uuid-0000-000000000005", "hist-20d-uuid-000-000000000020", "hist-65d-uuid-000-000000000065"),
            snapHeat.mapThreats.filter { it.uuid.startsWith("hist-") }.map { it.uuid }.toSet()
        )

        // 4. Real RfMap Heat Layer & Rendering Assertions (50% opacity smooth continuous MapLibre heatmap, ZERO hexagons!)
        val addedLayers = mutableMapOf<String, org.json.JSONObject>()
        val sourceData = mutableMapOf<String, String>()
        val addedSources = mutableSetOf<String>()
        val fakeEngine = object : org.tucsoncommunity.kokoho.ui.MapEngine {
            override val provider = org.tucsoncommunity.kokoho.ui.MapEngine.Provider.OPENFREEMAP
            override val view = android.view.View(act)
            override val credit = ""
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
            override fun hasSource(id: String) = id in addedSources
            override fun addImage(id: String, bitmap: android.graphics.Bitmap) {}
            override fun addSource(id: String) { addedSources += id }
            override fun setData(source: String, geoJson: String) { sourceData[source] = geoJson }
            override fun addLayer(json: org.json.JSONObject) { addedLayers[json.getString("id")] = json }
            override fun setPaint(layer: String, name: String, value: String) {}
            override val camera = org.tucsoncommunity.kokoho.ui.MapEngine.Camera(32.2216, -110.8410, 12.0, 0.0, 0.0)
            override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {}
            override fun zoomTo(zoom: Double) {}
            override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {}
            override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {}
            override fun visibleBox() = org.tucsoncommunity.kokoho.ui.MapEngine.Box(32.35, -110.75, 32.10, -111.05)
            override fun toLatLon(x: Float, y: Float) = 32.2216 to -110.8410
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
            override fun follow(mode: org.tucsoncommunity.kokoho.ui.MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {}
            override var onFollowDismissed: (() -> Unit)? = null
            override fun placeBranding(topPx: Int, atEnd: Boolean) {}
        }
        val rfMap = org.tucsoncommunity.kokoho.ui.RfMap(act, fakeEngine)
        rfMap.loadStyle(dark = true) {}
        for (ag in KokohoAgency.entries) {
            val heatLayer = addedLayers["rf-kokoho-heat-${ag.id}"]
            assertNotNull("Per-agency heatmap layer rf-kokoho-heat-${ag.id} must be registered", heatLayer)
            assertEquals("rf-kokoho-heat-${ag.id} must be smooth MapLibre heatmap (not hexagonal fill)", "heatmap", heatLayer!!.getString("type"))
            assertEquals(0.50, heatLayer.getJSONObject("paint").getDouble("heatmap-opacity"), 0.001)
            val r = (ag.mapColorArgb ushr 16) and 0xFF
            val g = (ag.mapColorArgb ushr 8) and 0xFF
            val b = ag.mapColorArgb and 0xFF
            assertTrue(
                "Agency ${ag.id} heatmap-color must represent agency RGB ($r,$g,$b)",
                heatLayer.getJSONObject("paint").getJSONArray("heatmap-color").toString().contains("rgba($r,$g,$b,")
            )
        }

        val heatPins = rfMap.drawLive(emptyList(), snapHeat)
        assertTrue("Heat mode must suppress individual entity pins", heatPins.isEmpty())
        val heatGeoJson = org.json.JSONObject(sourceData["rf-kokoho-heat"]!!)
        val heatFeaturesArr = heatGeoJson.getJSONArray("features")
        assertEquals("Heat mode must populate all 3 historical points into rf-kokoho-heat", 3, heatFeaturesArr.length())
        for (i in 0 until heatFeaturesArr.length()) {
            assertEquals("POLICE", heatFeaturesArr.getJSONObject(i).getJSONObject("properties").getString("agency"))
        }
        assertEquals(0, org.json.JSONObject(sourceData["rf-devices"]!!).getJSONArray("features").length())
        assertEquals(0, org.json.JSONObject(sourceData["rf-precision"]!!).getJSONArray("features").length())

        // Switch to 1wk and verify individual pins render and rf-kokoho-heat clears
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.WEEK_1)
        val weekPins = rfMap.drawLive(emptyList(), snap1wk)
        assertEquals(1, weekPins.size)
        assertEquals(0, org.json.JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())

        // 5. East Broadway Cruiser Qualification & Attested NIC Blocks & False-Positive Elimination:
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.MIN_30)

        val eastGeo = DeviceRegistry.GeoSample(nowMs, 32.21520, -110.83840)
        val idWifi = DeviceIntel.Identity("WiFi access point", emptyList())

        // 5a. Cradlepoint R920-f77-5g (`00:30:44:6B:5F:79` @ -91 dBm 5GHz) MUST qualify as POLICE!
        DeviceRegistry.report(
            Advert(
                mac = "00:30:44:6B:5F:79",
                source = Advert.Source.WIFI,
                rssi = -91,
                name = "R920-f77-5g",
                wifi = Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false, upSince = nowMs - 3600_000L),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "CradlePoint, Inc", null, eastGeo, nowMs, here = eastGeo
        )
        // 5b. Standalone Panasonic Connect Toughbook MDT (`B8:20:8E:21:B2:05` @ -78 dBm) MUST qualify as POLICE with Patrol MDT capability!
        val mdtGeo = DeviceRegistry.GeoSample(nowMs, 32.20785, -110.85015)
        DeviceRegistry.report(
            Advert(
                mac = "B8:20:8E:21:B2:05",
                source = Advert.Source.WIFI,
                rssi = -78,
                name = null,
                wifi = Advert.WifiInfo(5240, "[WPA2-PSK-CCMP]", null, emptyList(), client = true),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "Panasonic Connect Co., Ltd.", null, mdtGeo, nowMs, here = mdtGeo
        )
        // 5c. Attested PCSO sub-block `00:30:44:74:AD:53` (`IBR1700-d52`) MUST stay >= 90% Confirmed SHERIFF!
        val pcsoGeo = DeviceRegistry.GeoSample(nowMs, 32.1950, -110.8900)
        DeviceRegistry.report(
            Advert(
                mac = "00:30:44:74:AD:53",
                source = Advert.Source.WIFI,
                rssi = -84,
                name = "IBR1700-d52",
                wifi = Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "CradlePoint, Inc", null, pcsoGeo, nowMs, here = pcsoGeo
        )
        // 5d. Consumer OUI collisions (`24:79:2A` Ruckus, `AC:82:47` Intel, `C8:B3:73` Linksys, `60:E3:27` TP-Link)
        //     and commercial fleet routers (`10:56:CA:D1` GoEnergy, `00:0D:67:8B` Comcast, `88:4F:59:8E` cotwifi) MUST produce 0 false positives!
        for ((badMac, badSsid, badVendor) in listOf(
            Triple("24:79:2A:62:A8:28", "AC_GUEST", "Ruckus Wireless"),
            Triple("AC:82:47:11:22:33", "", "Intel Corporate"),
            Triple("C8:B3:73:44:55:66", "Linksys04455", "Cisco-Linksys"),
            Triple("60:E3:27:ED:54:48", "TP-LINK_5448", "TP-Link Technologies"),
            Triple("10:56:CA:D1:16:A8", "GoEnergy-4862", "Peplink International"),
            Triple("00:0D:67:8B:4F:81", "OutOfService", "Ericsson"),
            Triple("88:4F:59:8E:12:34", "cotwifi", "Cisco Systems")
        )) {
            DeviceRegistry.report(
                Advert(
                    mac = badMac,
                    source = Advert.Source.WIFI,
                    rssi = -65,
                    name = badSsid,
                    wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                    timestamp = nowMs
                ),
                emptyList(), idWifi, badVendor, null, eastGeo, nowMs, here = eastGeo
            )
        }

        val cruiserSnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertEquals("Exactly the 3 legitimate units (R920 POLICE, Toughbook POLICE, IBR1700-d52 SHERIFF) must qualify, got ${cruiserSnap.mapThreats}", 3, cruiserSnap.mapThreats.size)
        assertTrue(cruiserSnap.mapThreats.any { it.agency == KokohoAgency.POLICE && it.hardwareIdentifier?.startsWith("00:30:44:6B:5F") == true })
        assertTrue(cruiserSnap.mapThreats.any { it.agency == KokohoAgency.POLICE && it.capabilities.contains("Patrol MDT") })
        val pcsoUnit = cruiserSnap.mapThreats.first { it.agency == KokohoAgency.SHERIFF }
        assertTrue("Attested 00:30:44:74 sub-block must keep >= 90% classification confidence", pcsoUnit.classificationConfidence >= 90)

        // 5e. 851.0125 MHz @ 28 dB SNR (East Broadway & Pantano f288efdd) qualifies, < 28 dB (22 dB) is suppressed;
        //     807.4500 MHz @ 33 dB & 808.1750 MHz @ 32 dB qualify; private citizen A25082 (`VAN DE WALL ALLAN G`) is excluded!
        assertNull(KokohoSignatures.matchFrequency(851_012_500L, snrDb = 22))
        assertEquals(KokohoAgency.POLICE, KokohoSignatures.matchFrequency(851_012_500L, snrDb = 28)?.agencyEnum)
        assertEquals(KokohoAgency.POLICE, KokohoSignatures.matchPublicSafetyMobileUplink(807_450_000L, 33, setOf(KokohoAgency.POLICE))?.agencyEnum)
        assertEquals(KokohoAgency.POLICE, KokohoSignatures.matchPublicSafetyMobileUplink(808_175_000L, 32, setOf(KokohoAgency.POLICE))?.agencyEnum)
        assertNull(
            "FAA Individual private citizen aircraft A25082 (VAN DE WALL ALLAN G) must not be misclassified as DHS_AMO when turning",
            KokohoSignatures.classifyAircraftAgency(
                owner = "VAN DE WALL ALLAN G",
                registration = "N2485X",
                callsign = "N2485X",
                hitLabel = null,
                type = "C182",
                hex = "A25082",
                speedKt = 110.0,
                isCircling = true
            )
        )

        actCtrl.pause().stop().destroy()
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.MIN_30)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        RadioLog.clear()
    }

    @Test
    fun channelSplitAndHygiene() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoDirectory.ensureLoaded(ctx)
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())

        val allLocs = KokohoDirectory.allLocations(ctx)
        assertTrue("Expected > 500 AZ public safety + federal locations, got ${allLocs.size}", allLocs.size > 500)

        // Common public-safe locations present in BOTH public and internal builds with exact custom titles
        val titles = allLocs.map { it.title }.toSet()
        for (expectedCommon in listOf(
            "CBP HQ",
            "ICE FO",
            "Mission Support",
            "AMO",
            "Railyard",
            "Three Points CBP",
            "CBP Port of Entry",
            "CBP Inspection Area",
            "USCIS"
        )) {
            assertTrue("Expected exact custom location title '$expectedCommon' in ${BuildConfig.RELEASE_CHANNEL}", expectedCommon in titles)
        }
        assertFalse("Old 'Main CBP HQ' title must not appear (must be 'CBP HQ' without 'Main')", "Main CBP HQ" in titles)
        // Verify publicly listed Police Stations, Substations, and Fire Departments are included
        assertTrue("Expected Police Stations / Substations in locations", allLocs.any { it.locationGroup == "POLICE" })
        assertTrue("Expected Fire Departments in locations", allLocs.any { it.locationGroup == "FIRE" })

        if (BuildConfig.RELEASE_CHANNEL == "public") {
            assertFalse("Public build must not package extended SQLite directory", KokohoDirectory.isDirectoryAvailable)
            assertTrue("Public build must label DEA publicly as 'DEA Tucson District Office'", "DEA Tucson District Office" in titles)
            val amoPublic = allLocs.first { it.title == "AMO" }
            assertFalse("Public AMO address must omit hangar building number", amoPublic.address.contains("1237"))
            assertEquals("Public build must return 0 packaged directory persons", 0, KokohoDirectory.searchPersons(ctx, "Agent").size)
        }

        // Real Production-Path Location Click Assertion (exercising DeviceDetailActivity.openLocation -> Shadow started Intent -> rendered DOM text!)
        val launcherAct = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val sampleTargets = listOf(
            allLocs.first { it.title == "AMO" },
            allLocs.first { it.title == "CBP HQ" },
            allLocs.first { it.locationGroup == "POLICE" },
            allLocs.first { it.locationGroup == "FIRE" }
        )
        for (targetLoc in sampleTargets) {
            DeviceDetailActivity.openLocation(launcherAct, targetLoc.id)
            val startedIntent = org.robolectric.Shadows.shadowOf(launcherAct).nextStartedActivity
            assertNotNull("openLocation must launch DeviceDetailActivity intent for ${targetLoc.id}", startedIntent)
            val detailCtrl = Robolectric.buildActivity(DeviceDetailActivity::class.java, startedIntent).setup()
            val detailAct = detailCtrl.get()
            assertEquals(
                "Location puck click for ${targetLoc.id} must display exact title (never 'Unknown Location')",
                targetLoc.title,
                detailAct.classificationText.text.toString()
            )
            val renderedTexts = collectAllText(detailAct.window.decorView)
            assertTrue("Facility Details header must render in DOM for ${targetLoc.title}", renderedTexts.contains("FACILITY DETAILS"))
            if (targetLoc.address.isNotBlank()) {
                assertTrue("Facility address '${targetLoc.address}' must render in DOM", renderedTexts.contains(targetLoc.address))
            }
            detailCtrl.pause().stop().destroy()
        }
        // Also verify defensive double-prefix normalization ("loc:loc:...") never shows Unknown Location
        val dblIntent = Intent(ctx, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, "loc:${sampleTargets.first().id}")
        val dblCtrl = Robolectric.buildActivity(DeviceDetailActivity::class.java, dblIntent).setup()
        assertEquals(sampleTargets.first().title, dblCtrl.get().classificationText.text.toString())
        dblCtrl.pause().stop().destroy()

        // NanRemoteId circuit-breaker backoff regression test (prevents HalDevMgr ifaceType=4 Binder storm)
        assertEquals(30_000L, org.tucsoncommunity.kokoho.service.NanRemoteId.computeBackoffMs(1, 30_000L))
        assertEquals(60_000L, org.tucsoncommunity.kokoho.service.NanRemoteId.computeBackoffMs(2, 60_000L))
        assertEquals(10 * 60_000L, org.tucsoncommunity.kokoho.service.NanRemoteId.computeBackoffMs(3, 120_000L))
        assertEquals(10 * 60_000L, org.tucsoncommunity.kokoho.service.NanRemoteId.computeBackoffMs(10, 120_000L))

        // Concerted Conflict Avoidance across release channels on shared packageId
        val publicLatest = UpdateChecker.Latest(
            version = "1.3.0",
            apkUrl = "https://kokoho.tucsoncommunity.online/v1/update/kokoho-latest.apk",
            notesUrl = "https://kokoho.tucsoncommunity.online",
            versionCode = 1302,
            channel = "public"
        )
        val altChannelLatest = UpdateChecker.Latest(
            version = "1.3.0-alt",
            apkUrl = "https://kokoho.tucsoncommunity.online/v1/update/alt/latest.apk",
            notesUrl = "https://kokoho.tucsoncommunity.online",
            versionCode = 1303,
            channel = "alt"
        )
        assertFalse(
            "Alternate channel release must NEVER prompt or overwrite a public install",
            UpdateChecker.isUpdateAvailable(altChannelLatest, currentChannel = "public", currentVersionCode = 1300, currentVersionName = "1.3.0")
        )
        assertFalse(
            "Public release must NEVER prompt or overwrite an alternate channel install",
            UpdateChecker.isUpdateAvailable(publicLatest, currentChannel = "alt", currentVersionCode = 1301, currentVersionName = "1.3.0-alt")
        )
        assertTrue(
            "Same-channel newer versionCode updates cleanly",
            UpdateChecker.isUpdateAvailable(altChannelLatest, currentChannel = "alt", currentVersionCode = 1301, currentVersionName = "1.3.0-alt")
        )

        // Git Hygiene Gate: verify zero private properties, SQLite DBs, GPX, or ZIP archives appear in git status!
        val repoRoot = File("..").canonicalFile.let { if (File(it, ".git").exists()) it else File(".").canonicalFile }
        if (File(repoRoot, ".git").exists()) {
            val trackedOut = ProcessBuilder("git", "ls-files")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText()
            val statusOut = ProcessBuilder("git", "status", "--porcelain")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText()
            for (forbidden in listOf(".sqlite3", ".gpx", "directory_ext")) {
                assertFalse("FORBIDDEN '$forbidden' found in git ls-files!", trackedOut.contains(forbidden, ignoreCase = true))
                assertFalse("FORBIDDEN unignored '$forbidden' found in git status!", statusOut.contains(forbidden, ignoreCase = true))
            }
        }
    }

    private fun collectAllText(view: android.view.View): String {
        val sb = StringBuilder()
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView && v.visibility == android.view.View.VISIBLE) {
                sb.append(v.text).append('\n')
            }
            if (v is android.view.ViewGroup && v.visibility == android.view.View.VISIBLE) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(view)
        return sb.toString()
    }
}
