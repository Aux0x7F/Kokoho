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
}
