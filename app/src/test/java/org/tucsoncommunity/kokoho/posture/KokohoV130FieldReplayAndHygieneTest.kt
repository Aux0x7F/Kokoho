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

        // 5. Speedway / I-10 Field-Drive False-Positive Suppression & Legitimate Hit Preservation (2026-11-16 16:33-17:22):
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
        val nowMs = System.currentTimeMillis()
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

        // 1. All 6 UI windows exposed & legacy "ALL" / "HEAT" / "ALL_TIME" migrate cleanly to all-time ∞ window
        assertEquals(
            listOf("30m", "3h", "24h", "1wk", "1mo", "∞"),
            KokohoSignatures.MapTimeWindow.ROLLING_UI_WINDOWS.map { it.label }
        )
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("ALL"))
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("HEAT"))
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("ALL_TIME"))
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

        KokohoSignatures.setMapHeatOverlayEnabled(act, false)
        KokohoSignatures.setMapConfirmationFilter(act, KokohoSignatures.MapConfirmationFilter.ALL)
        val allTimePins = rfMap.drawLive(emptyList(), snapHeat)
        assertTrue("All-time ∞ window must render normal historical pins when Heat overlay is off", allTimePins.isNotEmpty())
        assertEquals(0, org.json.JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())

        KokohoSignatures.setMapHeatOverlayEnabled(act, true)
        rfMap.drawLive(emptyList(), snapHeat)
        val heatGeoJson = org.json.JSONObject(sourceData["rf-kokoho-heat"]!!)
        val heatFeaturesArr = heatGeoJson.getJSONArray("features")
        assertEquals("Heat overlay on ∞ window must populate all 3 historical points into rf-kokoho-heat", 3, heatFeaturesArr.length())
        for (i in 0 until heatFeaturesArr.length()) {
            assertEquals("POLICE", heatFeaturesArr.getJSONObject(i).getJSONObject("properties").getString("agency"))
        }

        // Switch to 1wk with Heat overlay off and verify individual pins render and rf-kokoho-heat clears
        KokohoSignatures.setMapHeatOverlayEnabled(act, false)
        KokohoSignatures.setMapTimeWindow(act, KokohoSignatures.MapTimeWindow.WEEK_1)
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

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun v134SearchTabHamburgerWidthAndStationClusterMerge() {
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val act = actCtrl.get()
        val ctx = act.applicationContext
        KokohoSignatures.ensureLoaded(ctx)
        KokohoDirectory.ensureLoaded(ctx)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoHubClient.clearCachedObjects()
        DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapLocationsVisible(ctx, true)
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.HOURS_24)

        val nowMs = 1_794_900_000_000L

        // 1. Fixed 90% Screen Width Hamburger Menu + "Bug Report" Entry & Generic Bug Modal
        val expectedMenuWidth = (act.resources.displayMetrics.widthPixels * 0.90f).toInt()
        val menuDialog = org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.showRootSandwichMenu(act) {}
        assertTrue("Hamburger menu dialog must be showing", menuDialog.isShowing)
        assertEquals(
            "Hamburger menu must start at fixed 90% screen width even when all submenus are collapsed",
            expectedMenuWidth,
            menuDialog.window!!.attributes.width
        )
        val menuTexts = collectAllText(menuDialog.window!!.decorView)
        assertTrue("Hamburger menu must include 'Bug Report' entry", menuTexts.contains("Bug Report"))
        menuDialog.dismiss()

        val bugDialog = org.tucsoncommunity.kokoho.ui.KokohoReportWizard.openGenericBugReportModal(act)
        assertTrue("Generic Bug Report modal must show", bugDialog.isShowing)
        assertEquals("Bug Report modal must be 90% screen width", expectedMenuWidth, bugDialog.window!!.attributes.width)
        assertTrue(collectAllText(bugDialog.window!!.decorView).contains("Submit Bug Report"))
        bugDialog.dismiss()

        KokohoHubClient.clearPendingBugReportsForTest(ctx)
        val sampleBug = KokohoHubClient.buildBugDiagnostics(ctx, includeLogs = true).copy(
            category = "RTL-SDR",
            summary = "Sample offline bug queue check"
        )
        KokohoHubClient.enqueueBugReport(ctx, sampleBug)
        assertEquals(1, KokohoHubClient.pendingBugReports(ctx).size)
        assertEquals("RTL-SDR", KokohoHubClient.pendingBugReports(ctx).single().category)
        KokohoHubClient.clearPendingBugReportsForTest(ctx)

        // 2. Station + Detection Centroid Merging (Station icon + Station first in comma list + no duplicate station pin)
        val targetStation = KokohoDirectory.visibleLocations(ctx).first { it.title == "ICE FO" }

        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "merge-ice-0001-4000-8000-000000000001",
              "track_uuid": "merge-ice-0001-4000-8000-000000000001",
              "updated_at_ms": ${nowMs - 60_000L},
              "sighting_count": 4,
              "corroboration_count": 3,
              "confidence": { "overall": 0.95, "classification": 0.96, "spatial": 0.92, "temporal_freshness": 1.0 },
              "location": { "lat": ${targetStation.lat + 0.00012}, "lon": ${targetStation.lon + 0.00010}, "uncertainty_radius_m": 45.0, "h3_res8": 614259430555648001, "propagation_class": "GROUND_DIRECT" },
              "classification": {
                "master_group": "DHS",
                "category": "DHS_ICE",
                "label": "ICE",
                "recurrence_summary": "Tucson Field Unit",
                "evidence_sources": [{ "kind": "WIFI_BLE_OUI", "facet_key": "hw:mac:00:30:44:91:AA:11", "detail": "00:30:44:91:AA:11", "weight": 0.95, "last_seen_ms": ${nowMs - 60_000L} }]
              }
            }
            """.trimIndent()
        )
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "merge-cbp-0002-4000-8000-000000000002",
              "track_uuid": "merge-cbp-0002-4000-8000-000000000002",
              "updated_at_ms": ${nowMs - 45_000L},
              "sighting_count": 4,
              "corroboration_count": 3,
              "confidence": { "overall": 0.94, "classification": 0.95, "spatial": 0.91, "temporal_freshness": 1.0 },
              "location": { "lat": ${targetStation.lat - 0.00010}, "lon": ${targetStation.lon - 0.00008}, "uncertainty_radius_m": 50.0, "h3_res8": 614259430555648001, "propagation_class": "GROUND_DIRECT" },
              "classification": {
                "master_group": "DHS",
                "category": "DHS_CBP",
                "label": "CBP",
                "recurrence_summary": "Tucson Sector Unit",
                "evidence_sources": [{ "kind": "WIFI_BLE_OUI", "facet_key": "hw:mac:00:30:44:92:BB:22", "detail": "00:30:44:92:BB:22", "weight": 0.95, "last_seen_ms": ${nowMs - 45_000L} }]
              }
            }
            """.trimIndent()
        )

        var currentZoom = 12.0
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
            override fun addLayer(json: org.json.JSONObject) {}
            override fun setPaint(layer: String, name: String, value: String) {}
            override val camera get() = org.tucsoncommunity.kokoho.ui.MapEngine.Camera(targetStation.lat, targetStation.lon, currentZoom, 0.0, 0.0)
            override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {}
            override fun zoomTo(zoom: Double) { currentZoom = zoom }
            override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {}
            override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {}
            override fun visibleBox() = org.tucsoncommunity.kokoho.ui.MapEngine.Box(32.5, -110.7, 32.0, -111.2)
            override fun toLatLon(x: Float, y: Float) = targetStation.lat to targetStation.lon
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
        val unconfirmedSnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        val unconfirmedMerged = rfMap.drawLive(emptyList(), unconfirmedSnap).single()
        assertTrue(
            "Unreported 95% sightings must cap at 'Likely' (never 'Confirmed' before a report), got '${unconfirmedMerged.label}'",
            unconfirmedMerged.label.contains("Likely ICE") &&
                unconfirmedMerged.label.contains("Likely CBP") &&
                !unconfirmedMerged.label.contains("Confirmed ")
        )
        // Submit a confirming LE report for merge-ice-0001 only -> merge-ice-0001 becomes Confirmed ICE while unreported merge-cbp-0002 stays Likely CBP!
        KokohoSignatures.saveWitnessReport(
            ctx,
            WitnessReportRecord(
                targetKey = "merge-ice-0001-4000-8000-000000000001",
                entityUuid = "merge-ice-0001-4000-8000-000000000001",
                agencyCode = "DHS_ICE",
                reportKind = "LE",
                notes = "Confirmed unmarked Tahoe outside office"
            ),
            syncRemote = false
        )
        val evalSnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        val mergedPins = rfMap.drawLive(emptyList(), evalSnap)
        assertEquals("Colocated detections + station at zoom 12.0 must merge into 1 centroid pin", 1, mergedPins.size)
        val mergedPin = mergedPins.single()
        val leadMergedStation = mergedPin.groupFacilities.first()
        assertTrue("Target station must be merged into groupFacilities", mergedPin.groupFacilities.any { it.id == targetStation.id })
        assertEquals(
            "Merged centroid must use the lead station's facility icon",
            "rf-facility-${leadMergedStation.agency.id}",
            mergedPin.customIconId
        )
        assertTrue("Merged centroid must display center icon dot", mergedPin.showDot)
        assertTrue(
            "Merged centroid label '${mergedPin.label}' must start with station title '${leadMergedStation.title}' followed by Confirmed ICE (reported) and Likely CBP (unreported)",
            mergedPin.label.startsWith("${leadMergedStation.title}, ") &&
                mergedPin.label.contains("Confirmed ICE") &&
                mergedPin.label.contains("Likely CBP")
        )
        val locGeoAtZoom12 = sourceData["rf-kokoho-locations"].orEmpty()
        assertTrue("rf-kokoho-locations GeoJSON must be populated with unmerged stations", locGeoAtZoom12.isNotEmpty())
        assertFalse(
            "Merged station '${targetStation.id}' must be excluded from rf-kokoho-locations GeoJSON so it never double-draws underneath",
            locGeoAtZoom12.contains("\"id\":\"${targetStation.id}\"")
        )

        // Zoom in to 16.0 (>= 15.5): station unmerges back onto rf-kokoho-locations and detections split into individual pucks!
        currentZoom = 16.0
        val splitPins = rfMap.drawLive(emptyList(), evalSnap)
        assertEquals("At zoom 16.0 detections must split into 2 distinct pucks", 2, splitPins.size)
        assertTrue("At zoom 16.0 pucks must have no merged stations", splitPins.all { it.groupFacilities.isEmpty() })
        val locGeoAtZoom16 = sourceData["rf-kokoho-locations"].orEmpty()
        assertTrue(
            "At zoom 16.0 unmerged station '${targetStation.id}' must return to rf-kokoho-locations GeoJSON",
            locGeoAtZoom16.contains("\"id\":\"${targetStation.id}\"")
        )

        // 3. Debug Label ("Kokoho Debug") & Inverted Notification / Launcher Icon Verification
        if (BuildConfig.DEBUG) {
            assertEquals("Debug install must be labeled 'Kokoho Debug'", "Kokoho Debug", ctx.getString(org.tucsoncommunity.kokoho.R.string.app_name))
            val notifDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, org.tucsoncommunity.kokoho.R.drawable.ic_tile_scan)
            assertNotNull("Debug notification icon ic_tile_scan must load", notifDrawable)
            val iconBmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val iconCanvas = android.graphics.Canvas(iconBmp)
            notifDrawable!!.setBounds(0, 0, 96, 96)
            notifDrawable.draw(iconCanvas)
            assertTrue(
                "Inverted debug notification badge rim at (12, 48) must be solid opaque",
                android.graphics.Color.alpha(iconBmp.getPixel(12, 48)) > 200
            )
            assertEquals(
                "Inverted debug notification Kokoho stroke at (48, 16) must be cut out (transparent negative space)",
                0,
                android.graphics.Color.alpha(iconBmp.getPixel(48, 16))
            )
        }

        // 4. Tab Gating: Public has strictly [Monitor, Map] (NO Search tab); Extended Directory builds expose [Monitor, Map, Search]
        val binding = org.tucsoncommunity.kokoho.databinding.ActivityMainBinding.inflate(act.layoutInflater)
        act.setContentView(binding.root)
        val views = KokohoViews(
            activity = act,
            binding = binding,
            onSwitchPanes = {},
            onFilterChanged = {}
        )
        // Verify Share button has NO pause icon when off, and keeps glowing pulsing red dot when Sharing ("Stop")
        KokohoSignatures.setSharingActive(ctx, false)
        views.syncSharePillState()
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertNull("Share button must have NO pause icon when not sharing", views.shareToggleButton.icon)

        KokohoSignatures.setContributionAllowedInSettings(ctx, true)
        KokohoSignatures.setSharingActive(ctx, true)
        views.syncSharePillState()
        assertEquals("Stop", views.shareToggleButton.text.toString())
        assertNotNull("Stop button must display glowing red dot icon while sharing", views.shareToggleButton.icon)
        assertTrue("Sharing indicator must be pulsing red while sharing", views.isSharingPulsingRed)

        KokohoSignatures.setSharingActive(ctx, false)
        views.syncSharePillState()
        assertNull("Returning to Share must clear icon again", views.shareToggleButton.icon)
        if (!KokohoDirectory.hasExtendedDirectoryCapability(ctx)) {
            assertEquals(
                "Public build must expose ONLY Monitor and Map tabs (no Search tab on public)",
                listOf("Monitor", "Map"),
                views.visibleTabs.map { it.title }
            )
            assertNull("Public build must not instantiate Search tab button", views.tabButton(KokohoViews.Tab.SEARCH))
            assertNull("Public build must not instantiate Search view", views.searchView)
            views.selectTab(KokohoViews.Tab.SEARCH)
            assertEquals("Selecting Search on public must remain a no-op on Monitor", KokohoViews.Tab.MONITOR, views.currentTab)
        } else {
            assertEquals(
                "Extended directory build must expose Monitor, Map, and Search tabs",
                listOf("Monitor", "Map", "Search"),
                views.visibleTabs.map { it.title }
            )
            val sv = views.searchView!!

            // Switch to Search tab -> Search root visible & keyboard input focused immediately
            views.selectTab(KokohoViews.Tab.SEARCH)
            assertEquals(android.view.View.VISIBLE, sv.root.visibility)
            assertTrue("Search input must receive focus immediately on entering Search tab", sv.searchInput.hasFocus())

            // Autocomplete pill commit via Tab key ("Loc" -> "Location" pill)
            sv.searchInput.setText("Loc")
            sv.refreshNowSynchronously()
            sv.searchInput.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_TAB))
            sv.refreshNowSynchronously()
            assertEquals(listOf("Location"), sv.getActivePills())
            assertEquals("", sv.searchInput.text.toString())
            val locOnlyRows = sv.getCurrentResults()
            assertTrue("Location filter pill must return non-empty Location rows", locOnlyRows.isNotEmpty())
            assertTrue(
                "Location filter pill must restrict results strictly to SchemaKind.LOCATION",
                locOnlyRows.all { it.schema == org.tucsoncommunity.kokoho.ui.KokohoSearchView.SchemaKind.LOCATION }
            )

            // Dismiss pill via chip click
            val pillsLayout = (sv.root.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.widget.HorizontalScrollView
            val pillsRow = pillsLayout.getChildAt(0) as android.view.ViewGroup
            while (pillsRow.childCount > 0) {
                pillsRow.getChildAt(0).performClick()
            }
            assertTrue("Clicking pill chips must dismiss all active pills", sv.getActivePills().isEmpty())

            // Raw search while NO filter pill matches (partial MAC "91:aa:11" from Confirmed ICE unit)
            sv.searchInput.setText("91:aa:11")
            sv.refreshNowSynchronously()
            val rawNoPillResults = sv.getCurrentResults()
            assertTrue(
                "Raw search '91:aa:11' with no filter match must find both the Confirmed ICE Detection and its constituent Device",
                rawNoPillResults.any { it.schema == org.tucsoncommunity.kokoho.ui.KokohoSearchView.SchemaKind.DETECTION && it.targetDetailKey.contains("merge-ice-0001") } &&
                    rawNoPillResults.any { it.schema == org.tucsoncommunity.kokoho.ui.KokohoSearchView.SchemaKind.DEVICE && it.targetDetailKey.contains("00:30:44:91:AA:11", ignoreCase = true) }
            )

            // Tapping a result row launches DeviceDetailActivity with exact target key
            val rv = sv.recyclerView
            rv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
            )
            rv.layout(0, 0, 1080, 1800)
            assertTrue("RecyclerView must lay out visible result cards", rv.childCount > 0)
            rv.getChildAt(0).performClick()
            val detailIntent = org.robolectric.Shadows.shadowOf(act).nextStartedActivity
            assertNotNull("Tapping anywhere on a search result card must open DeviceDetailActivity", detailIntent)
            assertEquals(DeviceDetailActivity::class.java.name, detailIntent.component?.className)

            // Switch away to Monitor tab -> Search hidden & focus/keyboard dismissed
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            views.selectTab(KokohoViews.Tab.MONITOR)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.view.View.GONE, sv.root.visibility)
            assertFalse("Switching away from Search tab must clear focus and dismiss keyboard", sv.searchInput.hasFocus())
        }

        actCtrl.pause().stop().destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.MIN_30)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoHubClient.clearCachedObjects()
        DeviceRegistry.clear()
    }

    @Test
    fun v137UpstreamGpsAndDecoderIntegration() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.HOURS_3)
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        RadioLog.clear()
        org.tucsoncommunity.kokoho.sdr.SdrRadio.p25Log.clear(0L)

        val nowMs = System.currentTimeMillis()
        // User's phone is Downtown Tucson (32.2217, -110.9747)
        val userLoc = android.location.Location("gps").apply {
            latitude = 32.2217
            longitude = -110.9747
            accuracy = 6f
            time = nowMs
        }
        ScanForegroundService.setLastFixForTest(userLoc)

        // 1. Real P25 LRRP Radio GPS Packet -> KokohoPostureEngine exact radio GPS pinpoint!
        //    Radio 774210 transmits its hardware GPS at East Broadway & Swan (32.2218, -110.8925, ±14m) on Federal VHF 168.3500 MHz:
        val lrrpPos = org.tucsoncommunity.kokoho.sdr.P25Tsbk.Position(
            radio = 774210,
            outbound = false,
            lat = 32.22180,
            lon = -110.89250,
            radiusM = 14.0,
            altitudeM = 760.0
        )
        KokohoPostureEngine.ingestP25RadioGps(ctx, lrrpPos, hz = 168_350_000L, nac = 0x167, nowMs = nowMs)
        val snap1 = KokohoPostureEngine.evaluate(ctx, nowMs)
        val lrrpThreat = snap1.mapThreats.firstOrNull { it.hardwareIdentifier?.contains("774210") == true }
        assertNotNull("P25 LRRP GPS fix must produce a located threat on Kokoho's Map", lrrpThreat)
        assertEquals(32.22180, lrrpThreat!!.lat!!, 1e-5)
        assertEquals(-110.89250, lrrpThreat.lon!!, 1e-5)
        assertTrue("LRRP GPS threat with ±14m radius must be pinpointed", lrrpThreat.isPinpointed)
        assertTrue("LRRP GPS radius must stay <= 15m after rolling projection (was ${lrrpThreat.uncertaintyRadiusM})", lrrpThreat.uncertaintyRadiusM in 14..15)
        assertTrue("LRRP GPS threat must carry LRRP GPS capability", lrrpThreat.capabilities.contains("LRRP GPS"))
        assertTrue(
            "LRRP GPS threat must persist into KokohoEntityLedger",
            KokohoEntityLedger.getWithinRollingWindow(ctx, nowMs, 3_600_000L).any { it.uuid == lrrpThreat.uuid }
        )

        // 2. Location-bound FixedRouter: stationary shop router stays quiet within 1 km of its learned spot,
        //    and UN-SUPPRESSES / ALERTS if that same MAC moves 3.5 km across town!
        val routerMac = "00:30:44:88:AA:10"
        org.tucsoncommunity.kokoho.detect.FixedRouter.load(
            listOf(routerMac),
            listOf(
                org.tucsoncommunity.kokoho.data.Spot(
                    mac = routerMac,
                    lat = 32.2217,
                    lon = -110.9747,
                    minLat = 32.2215,
                    minLon = -110.9749,
                    maxLat = 32.2219,
                    maxLon = -110.9745
                )
            )
        )
        assertTrue("Familiar router heard at its home spot (0 m) must be familiar", org.tucsoncommunity.kokoho.detect.FixedRouter.isFamiliarAt(routerMac, 32.2217, -110.9747))
        assertTrue("Familiar router heard 400 m from home spot must stay familiar", org.tucsoncommunity.kokoho.detect.FixedRouter.isFamiliarAt(routerMac, 32.2245, -110.9747))
        assertFalse("Familiar router heard 3.8 km away must NOT be suppressed as stationary!", org.tucsoncommunity.kokoho.detect.FixedRouter.isFamiliarAt(routerMac, 32.2217, -110.9340))
        assertTrue("Cradlepoint branch default SSID E300-4a1 must be recognized as branch router", org.tucsoncommunity.kokoho.detect.FixedRouter.isBranchDefaultOrOpenRouter("E300-4a1", "[WPA2-PSK-CCMP][ESS]"))
        assertFalse("Patrol vehicle router R920-6b5 must NEVER be suppressed as a branch router", org.tucsoncommunity.kokoho.detect.FixedRouter.isBranchDefaultOrOpenRouter("R920-6b5", "[WPA2-PSK-CCMP][ESS]"))

        // 3. 80% Path-Loss Fitted Locator.estimate tightens DeviceRegistry -> KokohoPostureEngine coordinates!
        try {
            DeviceRegistry.startSession(nowMs)
            val targetLat = 32.2300
            val targetLon = -110.9500
            val idWifi = org.tucsoncommunity.kokoho.detect.DeviceIntel.Identity("WiFi router", emptyList())
            val routerHit = listOf(org.tucsoncommunity.kokoho.detect.Hit(org.tucsoncommunity.kokoho.detect.Category.PUBLIC_SAFETY, "Vehicle router", 75, "test", "test"))
            // Simulate driving an L-shaped street corner past a parked PCSO vehicle at (targetLat, targetLon):
            // Leg 1: East from (-90m, -25m) to (+25m, -25m), Leg 2: North from (+25m, -25m) to (+25m, +85m)
            val mLat = 110_540.0
            val mLon = 111_320.0 * kotlin.math.cos(Math.toRadians(targetLat))
            val cornerWaypoints = (0..11).map { i -> (-90.0 + i * 10.0) to -25.0 } +
                (1..11).map { i -> 25.0 to (-25.0 + i * 10.0) }
            cornerWaypoints.forEachIndexed { idx, (eastM, northM) ->
                val tObs = nowMs + idx * 1_000L
                val obsLat = targetLat + northM / mLat
                val obsLon = targetLon + eastM / mLon
                val dM = KokohoPostureEngine.haversineMeters(targetLat, targetLon, obsLat, obsLon).coerceAtLeast(5.0)
                val simRssi = (-38.0 - 25.0 * kotlin.math.log10(dM)).toInt()
                val geo = DeviceRegistry.GeoSample(tObs, obsLat, obsLon)
                DeviceRegistry.report(
                    org.tucsoncommunity.kokoho.detect.Advert(
                        mac = "00:30:44:74:CC:20",
                        source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                        rssi = simRssi,
                        name = "IBR1700-c20",
                        wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                        timestamp = tObs
                    ),
                    routerHit,
                    idWifi,
                    "CradlePoint, Inc",
                    null,
                    geo,
                    tObs,
                    here = geo
                )
            }
            // Final report >= LOCATE_EVERY_MS (5 s) after last fit so relocate() runs on all 23 corner spots
            val finalT = nowMs + 30_000L
            val finalGeo = DeviceRegistry.GeoSample(finalT, targetLat + 95.0 / mLat, targetLon + 25.0 / mLon)
            val finalDm = KokohoPostureEngine.haversineMeters(targetLat, targetLon, finalGeo.lat, finalGeo.lon).coerceAtLeast(5.0)
            DeviceRegistry.report(
                org.tucsoncommunity.kokoho.detect.Advert(
                    mac = "00:30:44:74:CC:20",
                    source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                    rssi = (-38.0 - 25.0 * kotlin.math.log10(finalDm)).toInt(),
                    name = "IBR1700-c20",
                    wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                    timestamp = finalT
                ),
                routerHit,
                idWifi,
                "CradlePoint, Inc",
                null,
                finalGeo,
                finalT,
                here = finalGeo
            )
            DeviceRegistry.awaitFits()
            val locatedEst = DeviceRegistry.get("00:30:44:74:CC:20")?.located
            assertNotNull("Locator.estimate must produce an 80% path-loss fit after L-corner pass", locatedEst)
            val snapAfterLoop = KokohoPostureEngine.evaluate(ctx, finalT + 1_000L)
            val fittedVehicle = snapAfterLoop.mapThreats.firstOrNull { it.hardwareIdentifier?.startsWith("00:30:44:74:CC") == true }
            assertNotNull("Driven-around PCSO router must appear in mapThreats", fittedVehicle)
            val errM = KokohoPostureEngine.haversineMeters(targetLat, targetLon, fittedVehicle!!.lat!!, fittedVehicle.lon!!)
            assertTrue("Fitted vehicle position must land within 35 m of true target (was ${errM}m)", errM <= 35.0)
            assertTrue("Fitted vehicle must be marked isApproximatedPosition", fittedVehicle.isApproximatedPosition)

            // 4. Epistemic 'Confirmed' gate:
            //    - High-confidence unreported detections (LRRP GPS 96%, PCSO router 92%) cap at 'Likely' (never 'Confirmed'!).
            //    - Submitting a report confirming that sighting upgrades it to 'Confirmed'.
            //    - Future sightings of that EXACT hardware MAC (`00:30:44:74:CC:20`) across town automatically earn 'Confirmed',
            //      while a brand-new unconfirmed MAC (`00:30:44:74:DD:99`) remains 'Likely'.
            assertTrue("Unreported high-confidence LRRP GPS threat must start as 'Likely' (got '${lrrpThreat.label}')", lrrpThreat.label.startsWith("Likely "))
            assertTrue("Unreported high-confidence PCSO router must start as 'Likely' (got '${fittedVehicle.label}')", fittedVehicle.label.startsWith("Likely "))

            KokohoSignatures.saveWitnessReport(
                ctx,
                WitnessReportRecord(
                    targetKey = fittedVehicle.uuid,
                    entityUuid = fittedVehicle.uuid,
                    agencyCode = "SHERIFF",
                    reportKind = "LE",
                    vehicleNumber = "4412",
                    notes = "Confirmed PCSO Tahoe"
                ),
                syncRemote = false
            )
            val confirmedNow = KokohoPostureEngine.evaluate(ctx, finalT + 2_000L)
                .mapThreats.first { it.uuid == fittedVehicle.uuid }
            assertTrue("Reported sighting must become 'Confirmed' (got '${confirmedNow.label}')", confirmedNow.label.startsWith("Confirmed "))

            // Simulate a brand-new session/sighting across town with RAM + entity ledger cleared (only SharedPreferences confirmed evidence kept!):
            KokohoEntityLedger.clearAll(ctx)
            KokohoPostureEngine.clearRamOnlyForTest()
            DeviceRegistry.startSession(finalT + 60_000L)
            val acrossTown = DeviceRegistry.GeoSample(finalT + 60_000L, 32.1850, -110.9900)
            // Re-hear the previously-confirmed exact sibling radio `00:30:44:74:CC:23` AND a brand-new unconfirmed `00:30:44:74:DD:99`:
            for ((macAddr, ssidName) in listOf("00:30:44:74:CC:23" to "IBR1700-c20-5G", "00:30:44:74:DD:99" to "IBR1700-d99")) {
                DeviceRegistry.report(
                    org.tucsoncommunity.kokoho.detect.Advert(
                        mac = macAddr,
                        source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                        rssi = -62,
                        name = ssidName,
                        wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                        timestamp = finalT + 60_000L
                    ),
                    routerHit,
                    idWifi,
                    "CradlePoint, Inc",
                    null,
                    acrossTown,
                    finalT + 60_000L,
                    here = acrossTown
                )
            }
            val laterSnap = KokohoPostureEngine.evaluate(ctx, finalT + 61_000L)
            val reseenConfirmed = laterSnap.mapThreats.first { it.hardwareIdentifier?.startsWith("00:30:44:74:CC") == true }
            val brandNewUnconfirmed = laterSnap.mapThreats.first { it.hardwareIdentifier?.startsWith("00:30:44:74:DD") == true }
            assertTrue(
                "Previously confirmed exact hardware must remain 'Confirmed' on later sightings (got '${reseenConfirmed.label}')",
                reseenConfirmed.label.startsWith("Confirmed ")
            )
            assertTrue(
                "Unconfirmed new hardware must remain 'Likely' (never 'Confirmed', got '${brandNewUnconfirmed.label}')",
                brandNewUnconfirmed.label.startsWith("Likely ")
            )
        } finally {
            KokohoSignatures.setMapTimeWindow(ctx, KokohoSignatures.MapTimeWindow.MIN_30)
            KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.DHS_AGENCIES.toSet())
            KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())
            org.tucsoncommunity.kokoho.detect.FixedRouter.load(emptyList())
            org.tucsoncommunity.kokoho.sdr.SdrRadio.p25Log.clear(0L)
            KokohoEntityLedger.clearAll(ctx)
            KokohoPostureEngine.clearLocalState()
            DeviceRegistry.clear()
            ScanForegroundService.setLastFixForTest(null)
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
