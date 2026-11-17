package org.tucsoncommunity.kokoho.posture

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.tucsoncommunity.kokoho.R
import org.tucsoncommunity.kokoho.databinding.ActivityMainBinding
import org.tucsoncommunity.kokoho.sdr.RadioLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class KokohoTest {

    private val artifactDir = File(System.getProperty("java.io.tmpdir"), "kokoho-test-artifacts")

    @Test
    fun dhsAgenciesDefaultOnAndOptionalAgenciesDefaultOff() {
        val dhs = KokohoAgency.DHS_AGENCIES
        assertTrue(dhs.contains(KokohoAgency.DHS_CBP))
        assertTrue(dhs.contains(KokohoAgency.DHS_ICE))
        assertTrue(dhs.contains(KokohoAgency.DHS_HSI))
        assertTrue(dhs.contains(KokohoAgency.DHS_AMO))
        assertTrue(dhs.contains(KokohoAgency.DHS_FPS))
        assertTrue(dhs.contains(KokohoAgency.DHS_INTEROP))
        assertTrue(KokohoAgency.DHS_ICE.defaultEnabled)
        assertTrue(KokohoAgency.DHS_AMO.defaultEnabled)
        assertTrue(KokohoAgency.DHS_CBP.defaultEnabled)
        assertTrue(KokohoAgency.DHS_HSI.defaultEnabled)
        assertFalse(KokohoAgency.DHS_FPS.defaultEnabled)
        assertTrue(KokohoAgency.DHS_INTEROP.defaultEnabled)
        assertTrue(KokohoAgency.DRONE.defaultEnabled)

        // Verify strict priority order: ICE (1) < DHS Air (2) < CBP (3) < HSI (4) < FPS (5) < Sheriff/TPD/Drone (6) < Fire/SAR (7) < Federal Interop (8)
        assertTrue(KokohoAgency.DHS_ICE.priorityRank < KokohoAgency.DHS_AMO.priorityRank)
        assertTrue(KokohoAgency.DHS_AMO.priorityRank < KokohoAgency.DHS_CBP.priorityRank)
        assertTrue(KokohoAgency.DHS_CBP.priorityRank < KokohoAgency.DHS_HSI.priorityRank)
        assertTrue(KokohoAgency.DHS_HSI.priorityRank < KokohoAgency.DHS_INTEROP.priorityRank)
        assertTrue(KokohoAgency.SHERIFF.priorityRank < KokohoAgency.DHS_INTEROP.priorityRank)

        val opt = KokohoAgency.OPTIONAL_AGENCIES
        assertTrue(opt.contains(KokohoAgency.POLICE))
        assertTrue(opt.contains(KokohoAgency.SHERIFF))
        assertTrue(opt.contains(KokohoAgency.FIRE))
        assertTrue(opt.contains(KokohoAgency.SAR))
    }

    @Test
    fun postureColorPaletteMatchesSpecification() {
        assertEquals(0xFF1B5E20.toInt(), PostureColor.GREEN.colorArgb)
        assertEquals("CLEAR", PostureColor.GREEN.badgeLabel)
        assertEquals(0xFFB26A00.toInt(), PostureColor.YELLOW.colorArgb)
        assertEquals(0xFFD84315.toInt(), PostureColor.ORANGE.colorArgb)
        assertEquals(0xFFB71C1C.toInt(), PostureColor.RED.colorArgb)
    }

    @Test
    fun agencyFromIdHandlesAliasesAndChannelImportWorks() {
        assertEquals(KokohoAgency.DHS_CBP, KokohoAgency.fromId("CBP"))
        assertEquals(KokohoAgency.DHS_ICE, KokohoAgency.fromId("DHS_ICE"))
        assertEquals(KokohoAgency.POLICE, KokohoAgency.fromId("TPD"))
        assertEquals(KokohoAgency.SHERIFF, KokohoAgency.fromId("PCSO"))
        assertFalse(KokohoAgency.FIRE.isDhs)

        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()
        KokohoSignatures.ensureLoaded(activity)

        // Verify bundled CBP & Air frequencies from user specification
        val air1 = KokohoSignatures.matchFrequency(168_837_500L)
        assertNotNull(air1)
        assertEquals(KokohoAgency.DHS_AMO, air1!!.agencyEnum)
        assertEquals("293", air1.nac)

        // CBP VS2 (173.7375 MHz) is a repeater downlink and must be excluded; CBP VS3A (173.8000 MHz) is Direct Tactical
        assertNull(KokohoSignatures.matchFrequency(173_737_500L))
        val cbpVs3a = KokohoSignatures.matchFrequency(173_800_000L)
        assertNotNull(cbpVs3a)
        assertEquals(KokohoAgency.DHS_CBP, cbpVs3a!!.agencyEnum)

        // Verify RadioReference TSV/CSV & header import
        val imported = KokohoSignatures.importChannelText(
            activity,
            "AIR 9 - 169.4125 MHz, NAC 293\n167.7125\tRM\t653 NAC\tICE-TUC-OPS\tTucson ICE Tactical\tP25e\tFederal",
            "UnitTestImport"
        )
        assertEquals(2, imported)
        val iceMatch = KokohoSignatures.matchFrequency(167_712_500L)
        assertNotNull(iceMatch)
        assertEquals(KokohoAgency.DHS_ICE, iceMatch!!.agencyEnum)
        assertEquals("ICE", iceMatch.friendlyLabel)
    }

    @Test
    fun offlineTriangulationAndImperialDistanceFormattingWork() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()
        KokohoSignatures.ensureLoaded(activity)
        KokohoSignatures.setImperialUnits(activity, true)
        KokohoPostureEngine.clearLocalState()

        // Default is Imperial (ft / mi), nearest-edge distance, and ± for accuracy radius
        val distMi = KokohoPostureEngine.formatUserDistance(activity, 0.0, 1609)
        assertEquals("Accuracy: ±1.0 mi", distMi)
        assertEquals("±1.0 mi", KokohoPostureEngine.formatAccuracyRadius(activity, 1609))
        val distFt = KokohoPostureEngine.formatUserDistance(activity, 150.0, 150)
        assertTrue(distFt.startsWith("Distance: ~") && distFt.contains("±500 ft"))
        assertEquals("±500 ft", KokohoPostureEngine.formatAccuracyRadius(activity, 150))

        // Verify top-level tabs are Monitor and Map (Utilities moved to Sandwich menu in v1.3.0)
        assertEquals(listOf("Monitor", "Map"), KokohoViews.Tab.values().map { it.title })

        // Metric option in Settings
        KokohoSignatures.setImperialUnits(activity, false)
        assertEquals("Accuracy: ±16.8 km", KokohoPostureEngine.formatUserDistance(activity, 0.0, 16800))
        KokohoSignatures.setImperialUnits(activity, true)

        val now = 1794373000000L
        val decayMs = 30 * 60_000L
        val uuid = KokohoPostureEngine.deterministicUuidV5("rf:chan:173737500:130")

        // 1. Single GPS point (< 80m spread) -> user-centered radius circle at estimatedDistanceM
        val tri1 = KokohoPostureEngine.recordAndTriangulate(uuid, 32.2226, -110.9747, 22, 600, now, decayMs)
        assertFalse(tri1.isApproximatedPosition)
        assertFalse(tri1.isPinpointed)
        assertEquals(600, tri1.uncertaintyRadiusM)

        // 2. Wide 2D GPS baseline (>= 80m spread and >= 25m cross-track spread across 3 points) -> approximated source position circle
        KokohoPostureEngine.recordAndTriangulate(uuid, 32.2236, -110.9747, 25, 500, now + 10_000L, decayMs)
        val tri3 = KokohoPostureEngine.recordAndTriangulate(uuid, 32.2236, -110.9735, 28, 420, now + 20_000L, decayMs)
        assertTrue(tri3.isApproximatedPosition)
        assertNotNull(tri3.centerLat)
        assertNotNull(tri3.centerLon)

        // 3. ADS-B aircraft classification (including unlisted circling spy planes -> DHS_AMO)
        assertEquals(
            KokohoAgency.DHS_AMO,
            KokohoSignatures.classifyAircraftAgency("US CUSTOMS AND BORDER PROTECTION", "N123BP", "OMAHA1", null)
        )
        assertEquals(
            KokohoAgency.DHS_AMO,
            KokohoSignatures.classifyAircraftAgency(null, null, null, org.tucsoncommunity.kokoho.online.PoliceAircraft.CIRCLING)
        )
        assertEquals(
            KokohoAgency.POLICE,
            KokohoSignatures.classifyAircraftAgency("CITY OF TUCSON POLICE DEPT", "N911TP", null, null)
        )
        assertEquals(
            KokohoAgency.SHERIFF,
            KokohoSignatures.classifyAircraftAgency("PIMA COUNTY SHERIFF", "N500SD", null, null)
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-420dpi")
    fun renderDarkThemeSandboxStatesAndTabs() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)

        KokohoHubClient.clearCachedObjects()
        RadioLog.clear()
        KokohoSignatures.ensureLoaded(activity)
        KokohoSignatures.setMasterDhs(activity, true)

        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        val views = KokohoViews(
            activity = activity,
            binding = binding,
            onSwitchPanes = {},
            onFilterChanged = {}
        )

        // Verify always-on scanning header & Sandwich menu button (`☰`) replacing Scan button
        views.syncRunningState(false)
        views.syncRunningState(true)
        assertNotNull(views.menuButton)

        // Verify Share / Stop action-verb binary pill: shows pause bars + "Share" when paused,
        // slowly pulsing red dot + "Stop" when active, and dims ("Share") when disabled in Settings > Privacy.
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertTrue(views.isSharingShowingPause)
        assertFalse(views.isSharingPulsingRed)
        assertFalse(KokohoSignatures.isContributeObservationsEnabled(activity))
        renderViewToPng(views.shareToggleButton, 360, 120, Color.parseColor("#1B5E20"), "kokoho_v1_2_1_sharing_paused.png")

        views.shareToggleButton.performClick()
        assertEquals("Stop", views.shareToggleButton.text.toString())
        assertTrue(views.isSharingPulsingRed)
        assertFalse(views.isSharingShowingPause)
        assertTrue(KokohoSignatures.isContributeObservationsEnabled(activity))
        renderViewToPng(views.shareToggleButton, 360, 120, Color.parseColor("#B71C1C"), "kokoho_v1_2_1_sharing_pulsing_red.png")

        views.shareToggleButton.performClick()
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertTrue(views.isSharingShowingPause)
        assertFalse(views.isSharingPulsingRed)
        assertFalse(KokohoSignatures.isContributeObservationsEnabled(activity))

        KokohoSignatures.setContributionAllowedInSettings(activity, false)
        views.syncSharePillState()
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertTrue(views.isSharingShowingPause)
        assertFalse(views.isSharingPulsingRed)
        assertEquals(0.55f, views.shareToggleButton.alpha, 0.02f)
        KokohoSignatures.setContributionAllowedInSettings(activity, true)
        views.syncSharePillState()
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertTrue(views.isSharingShowingPause)

        // Verify intersection "rmat" traffic cabinets and retail routers are excluded while 7400 Oracle signals match
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:55:00:06", "rmat", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:9B:BE:48", "Chill N NW", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:AA:BB:CC", "Club Demonstration Services 2.4", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:AA:BB:CD", "TeslaWiFi_Mercury", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:AA:BB:CE", "sscw_Store_WIFI", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:AA:BB:CF", "SBXL 2", "Cradlepoint"))
        assertEquals(KokohoAgency.FIRE, KokohoSignatures.matchOuiOrSsid("D4:13:F8:0A:41:08", "TFDCP5G", "Cradlepoint")?.first)
        assertEquals(
            KokohoSignatures.routerRadioGroupKey("D4:13:F8:61:29:68"),
            KokohoSignatures.routerRadioGroupKey("d413f861296c")
        )
        assertEquals(
            KokohoSignatures.routerRadioGroupKey("10:56:CA:B5:4A:28"),
            KokohoSignatures.routerRadioGroupKey("10:56:CA:B5:4A:2C")
        )
        assertNotNull(KokohoSignatures.matchOuiOrSsid("D4:13:F8:7F:C6:49", null, "Peplink"))
        // Uncataloged wideband RF noise must NEVER fabricate synthetic ICE-UHF-* / FED-VHF-* hits
        assertNull(KokohoSignatures.matchCloseRangeFederalBand(167_862_500L, 42))

        // Verify userLoc == null does not coerce remote Tucson Hub ICE entity into <= 500 ft RED
        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(null)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "550e8400-e29b-41d4-a716-446655440099",
              "track_uuid": "b2222222-3333-4444-8555-666666666699",
              "updated_at_ms": 1794373000000,
              "confidence": { "overall": 0.90, "classification": 0.92, "spatial": 0.85, "temporal_freshness": 1.0 },
              "location": { "lat": 32.3400, "lon": -110.9750, "uncertainty_radius_m": 120.0, "h3_res8": 614259430555648001, "propagation_class": "GROUND_DIRECT" },
              "classification": { "master_group": "DHS", "category": "DHS_ICE", "label": "ICE ERO", "recurrence_summary": "Remote hit", "evidence_sources": [{ "kind": "WIFI_BLE_OUI", "facet_key": "hw:mac:AA:BB:CC:DD:EE:00", "detail": "Remote router", "weight": 0.85, "last_seen_ms": 1794373000000 }] }
            }
            """.trimIndent()
        )
        val noFixSnap = KokohoPostureEngine.evaluate(activity, 1794373000000L)
        assertEquals(PostureColor.YELLOW, noFixSnap.color)
        assertTrue(noFixSnap.primaryThreat!!.estimatedDistanceM >= 5000)
        KokohoHubClient.clearCachedObjects()
        KokohoPostureEngine.clearLocalState()

        // Provide Downtown Tucson fix for sandbox posture distance states below
        val downtownFix = android.location.Location("test").apply {
            latitude = 32.2226
            longitude = -110.9747
        }
        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(downtownFix)

        // Verify multi-detection corroboration opacity scaling (1 -> 2 -> 3+ detections & radius tightness)
        val op1 = KokohoSignatures.mapCorroborationOpacity(activity, 60_000L, totalSightings = 1, distinctSignals = 1, radiusM = 900)
        val op2 = KokohoSignatures.mapCorroborationOpacity(activity, 60_000L, totalSightings = 2, distinctSignals = 1, radiusM = 400)
        val op3 = KokohoSignatures.mapCorroborationOpacity(activity, 60_000L, totalSightings = 5, distinctSignals = 3, radiusM = 120)
        assertTrue("1 isolated wide hit must be subtle (< 0.30)", op1 < 0.30f)
        assertTrue("2 hits must be stronger than 1 hit", op2 > op1)
        assertTrue("3+ hits with tight radius must reach full opacity", op3 > op2 && op3 >= 0.95f)

        val nowMs = 1794373000000L

        // 1. Default GREEN state ("No threats detected")
        views.selectTab(KokohoViews.Tab.MONITOR)
        views.updatePosture(nowMs)
        val greenSnap = KokohoPostureEngine.evaluate(activity, nowMs)
        assertEquals(PostureColor.GREEN, greenSnap.color)
        assertEquals("No threats detected", greenSnap.headline)
        renderViewToPng(binding.root, 1080, 2160, Color.parseColor("#0B0F14"), "kokoho_sandbox_monitor_green_dark.png")

        // 2. YELLOW state (Periphery Presence — AMO Air 1 at ±1850m)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
              "track_uuid": "a1111111-2222-4333-8444-555555555555",
              "updated_at_ms": $nowMs,
              "confidence": { "overall": 0.78, "classification": 0.90, "spatial": 0.62, "temporal_freshness": 1.0 },
              "location": {
                "lat": 32.1950,
                "lon": -110.9500,
                "uncertainty_radius_m": 1850.0,
                "h3_res8": 614259430555648000,
                "propagation_class": "AIR_TO_GROUND"
              },
              "classification": {
                "master_group": "DHS",
                "category": "DHS_AMO",
                "label": "AMO Air 1 (168.8375 MHz · NAC 293)",
                "recurrence_summary": "Recurrent in sector (4 compacted sightings)",
                "evidence_sources": [
                  {
                    "kind": "RF_P25",
                    "facet_key": "freq:168837500:nac:293",
                    "detail": "AMO Air 1 (168.8375 MHz, NAC 293) · 1 sensor room node · peak 16.5 dB SNR",
                    "weight": 0.90,
                    "last_seen_ms": $nowMs
                  }
                ]
              }
            }
            """.trimIndent()
        )
        views.updatePosture(nowMs)
        val yellowSnap = KokohoPostureEngine.evaluate(activity, nowMs)
        assertEquals(PostureColor.YELLOW, yellowSnap.color)
        renderViewToPng(binding.root, 1080, 2160, Color.parseColor("#0B0F14"), "kokoho_sandbox_monitor_yellow.png")

        // 3. ORANGE state (Heading This Way — ICE Tactical moving at 14.5 m/s)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "550e8400-e29b-41d4-a716-446655440000",
              "track_uuid": "b2222222-3333-4444-8555-666666666666",
              "updated_at_ms": $nowMs,
              "confidence": { "overall": 0.86, "classification": 0.92, "spatial": 0.78, "temporal_freshness": 1.0 },
              "location": {
                "lat": 32.2180,
                "lon": -110.9710,
                "uncertainty_radius_m": 620.0,
                "h3_res8": 614259430555648001,
                "bearing_deg": 18.0,
                "velocity_mps": 14.5,
                "propagation_class": "GROUND_DIRECT"
              },
              "classification": {
                "master_group": "DHS",
                "category": "DHS_ICE",
                "label": "ICE ERO / HSI Tactical (167.7125 MHz)",
                "recurrence_summary": "Active mobile track (6 compacted sightings)",
                "evidence_sources": [
                  {
                    "kind": "RF_P25",
                    "facet_key": "freq:167712500:nac:653",
                    "detail": "ICE ERO Tactical (167.7125 MHz, NAC 653) · 2 triangulated room sensors · approaching at 14.5 m/s",
                    "weight": 0.92,
                    "last_seen_ms": $nowMs
                  }
                ]
              }
            }
            """.trimIndent()
        )
        views.updatePosture(nowMs)
        val orangeSnap = KokohoPostureEngine.evaluate(activity, nowMs)
        assertEquals(PostureColor.ORANGE, orangeSnap.color)
        renderViewToPng(binding.root, 1080, 2160, Color.parseColor("#0B0F14"), "kokoho_sandbox_monitor_orange.png")

        // 4. RED state (Imminent Proximity — Colocated CBP VS2 173.7375 MHz + Cradlepoint OUI 00:30:44 at ±150m)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "bf38689d-a910-50a6-93d4-e4c17a90a214",
              "track_uuid": "ef0351f4-8c28-5541-b982-e2efdb55ef05",
              "updated_at_ms": $nowMs,
              "confidence": { "overall": 0.95, "classification": 0.99, "spatial": 0.88, "temporal_freshness": 1.0 },
              "location": {
                "lat": 32.2221,
                "lon": -110.9742,
                "uncertainty_radius_m": 150.0,
                "h3_res8": 614259430555648002,
                "bearing_deg": 348.0,
                "velocity_mps": 6.2,
                "propagation_class": "GROUND_DIRECT"
              },
              "classification": {
                "master_group": "DHS",
                "category": "DHS_CBP",
                "label": "CBP VS2 (Tucson Sector · 173.7375 MHz)",
                "recurrence_summary": "Recurrent in sector (9 compacted sightings)",
                "evidence_sources": [
                  {
                    "kind": "RF_P25",
                    "facet_key": "freq:173737500:nac:130",
                    "detail": "CBP VS2 (173.7375 MHz, NAC 130) · 2 sensor room nodes · peak 24.5 dB SNR",
                    "weight": 0.91,
                    "last_seen_ms": $nowMs
                  },
                  {
                    "kind": "WIFI_BLE_OUI",
                    "facet_key": "oui:00:30:44",
                    "detail": "Colocated Cradlepoint Fleet Router OUI (00:30:44:1A:2B:3C) within 150m",
                    "weight": 0.88,
                    "last_seen_ms": $nowMs
                  }
                ]
              }
            }
            """.trimIndent()
        )
        views.updatePosture(nowMs)
        val redSnap = KokohoPostureEngine.evaluate(activity, nowMs)
        assertEquals(PostureColor.RED, redSnap.color)
        assertEquals("CBP", redSnap.headline)
        assertTrue(redSnap.subheadline.startsWith("x1 · "))
        assertTrue(redSnap.subheadline.contains("±500 ft"))
        assertEquals("Distance: ~500 ft", redSnap.distanceAndBearingText)
        assertEquals("94% confidence · 2 signals", redSnap.bottomConfidenceText)
        assertEquals(2, redSnap.primaryThreat!!.evidenceSources.size)
        renderViewToPng(binding.root, 1080, 2160, Color.parseColor("#0B0F14"), "kokoho_sandbox_monitor_red.png")

        // 5. MAP tab selection (Utilities moved to Sandwich menu)
        views.selectTab(KokohoViews.Tab.MAP)
        renderViewToPng(binding.root, 1080, 2160, PostureColor.RED.colorArgb, "kokoho_sandbox_utility_tab.png")

        // 6. Entity Details Activity (read-only highest-order dynamic title, Subjourney header, constituent devices)
        val detailIntent = android.content.Intent(activity, org.tucsoncommunity.kokoho.ui.DeviceDetailActivity::class.java)
            .putExtra(org.tucsoncommunity.kokoho.ui.DeviceDetailActivity.EXTRA_MAC, "kokoho:bf38689d-a910-50a6-93d4-e4c17a90a214")
        val detailController = Robolectric.buildActivity(org.tucsoncommunity.kokoho.ui.DeviceDetailActivity::class.java, detailIntent).setup()
        val detailAct = detailController.get()
        val readOnlyTitle = detailAct.classificationText.text.toString()
        assertTrue("Expected highest-order dynamic CBP title, got '$readOnlyTitle'", readOnlyTitle.contains("CBP"))
        renderViewToPng(
            detailAct.findViewById(android.R.id.content),
            1080,
            2160,
            PostureColor.RED.colorArgb,
            "kokoho_sandbox_entity_details.png"
        )
        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(null)
        detailController.pause().stop().destroy()
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
    fun renderLightThemeSandboxAndThemeIconsProof() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)

        KokohoHubClient.clearCachedObjects()
        RadioLog.clear()
        KokohoSignatures.ensureLoaded(activity)
        KokohoSignatures.setMasterDhs(activity, true)

        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        val views = KokohoViews(
            activity = activity,
            binding = binding,
            onSwitchPanes = {},
            onFilterChanged = {}
        )
        views.syncRunningState(false)
        val nowMs = 1794373000000L
        views.selectTab(KokohoViews.Tab.MONITOR)
        views.updatePosture(nowMs)

        renderViewToPng(binding.root, 1080, 2160, Color.parseColor("#1B5E20"), "kokoho_sandbox_monitor_green_light.png")

        // Render side-by-side comparison sheet of Light Theme (#000000 black/transparent) vs Dark Theme (#FFFFFF white/transparent) icons
        val sheet = Bitmap.createBitmap(1080, 560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Left half: Light theme surface (#FFFFFF) with light-mode ic_kokoho_logo (#000000 on transparent)
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, 540f, 560f, paint)
        val lightLogo = ContextCompat.getDrawable(activity, R.drawable.ic_kokoho_logo)!!
        lightLogo.setBounds(110, 80, 430, 400)
        lightLogo.draw(canvas)
        paint.color = Color.BLACK
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Light Theme: #000000 / Transparent", 270f, 490f, paint)

        // Right half: Dark theme surface (#0B0F14) with night-mode ic_kokoho_logo (#FFFFFF on transparent)
        paint.color = Color.parseColor("#0B0F14")
        canvas.drawRect(540f, 0f, 1080f, 560f, paint)
        val nightConfig = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val nightCtx = activity.createConfigurationContext(nightConfig)
        val darkLogo = ContextCompat.getDrawable(nightCtx, R.drawable.ic_kokoho_logo)!!
        darkLogo.setBounds(650, 80, 970, 400)
        darkLogo.draw(canvas)
        paint.color = Color.WHITE
        canvas.drawText("Dark Theme: #FFFFFF / Transparent", 810f, 490f, paint)

        saveBitmap(sheet, "kokoho_sandbox_theme_icons_proof.png")
    }

    @Test
    fun rankedAgencyPriorityAndRepeaterExclusionAndWitnessReportWork() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val ctx = controller.get()
        KokohoSignatures.ensureLoaded(ctx)

        // Strict priority rank order: ICE < DHS Air < CBP < HSI < FPS < Sheriff/TPD < Federal Interop
        assertTrue(KokohoAgency.DHS_ICE.priorityRank < KokohoAgency.DHS_AMO.priorityRank)
        assertTrue(KokohoAgency.DHS_AMO.priorityRank < KokohoAgency.DHS_CBP.priorityRank)
        assertTrue(KokohoAgency.DHS_CBP.priorityRank < KokohoAgency.DHS_HSI.priorityRank)
        assertTrue(KokohoAgency.DHS_HSI.priorityRank < KokohoAgency.DHS_INTEROP.priorityRank)
        assertTrue(KokohoAgency.SHERIFF.priorityRank < KokohoAgency.DHS_INTEROP.priorityRank)
        assertEquals("DHS Air", KokohoAgency.DHS_AMO.friendlyLabel)

        // Repeaters (e.g., 172.900 MHz ICE repeater downlink) and unknown commercial VHF are solidly excluded
        assertNull(KokohoSignatures.matchFrequency(172_900_000L))
        assertNull(KokohoSignatures.matchFrequency(154_600_000L))

        // Direct tactical & mobile input uplink frequencies match cleanly
        val iceTac = KokohoSignatures.matchFrequency(167_712_500L)
        assertNotNull(iceTac)
        assertEquals(KokohoAgency.DHS_ICE, iceTac!!.agencyEnum)

        val cbpMobileInput = KokohoSignatures.matchFrequency(162_850_000L)
        assertNotNull(cbpMobileInput)
        assertEquals(KokohoAgency.DHS_CBP, cbpMobileInput!!.agencyEnum)

        // Pro/Enterprise/Military drone OUI matching works
        val skydio = KokohoSignatures.matchOui("38:1D:14:AA:BB:CC")
        assertNotNull(skydio)
        assertEquals(KokohoAgency.DRONE, skydio!!.agencyEnum)

        // Witness Report round-trip works
        val report = WitnessReportRecord(
            targetKey = "unit-test-entity-1",
            agencyCode = "DHS_ICE",
            department = "ERO Tucson",
            badgeNumber = "4412",
            agentName = "J. Doe",
            vehicleNumber = "G62-1194A",
            notes = "Unmarked white Tahoe at Grant & Oracle"
        )
        KokohoSignatures.saveWitnessReport(ctx, report, syncRemote = false)
        val loaded = KokohoSignatures.getWitnessReport(ctx, "unit-test-entity-1")
        assertNotNull(loaded)
        assertEquals("4412", loaded!!.badgeNumber)
        assertEquals("G62-1194A", loaded.vehicleNumber)
        controller.pause().stop().destroy()
    }

    @Test
    fun v114MapDefaultsLearnedMacBlocksCapabilitiesAndFlightSmoothingWork() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val ctx = controller.get()
        KokohoSignatures.ensureLoaded(ctx)

        // 1. Default map window is 30m (current investigation) and all 6 rolling duration / heatmap pills are exposed
        assertEquals(KokohoSignatures.MapTimeWindow.MIN_30, KokohoSignatures.getMapTimeWindow(ctx))
        assertEquals(
            listOf("30m", "3h", "24h", "1wk", "1mo", "Heat"),
            KokohoSignatures.MapTimeWindow.ROLLING_UI_WINDOWS.map { it.label }
        )
        assertTrue(KokohoSignatures.zoomGravityMergeRadiusM(11.5) > KokohoSignatures.zoomGravityMergeRadiusM(15.2))
        val mixedColor = KokohoSignatures.mixAgencyColorsArgb(
            mapOf(
                KokohoAgency.DHS_ICE to 10.0,
                KokohoAgency.DHS_HSI to 10.0
            )
        )
        assertNotEquals(KokohoAgency.DHS_ICE.mapColorArgb, mixedColor)
        assertNotEquals(KokohoAgency.DHS_HSI.mapColorArgb, mixedColor)

        // 2. Share starts OFF on cold start
        assertFalse(KokohoSignatures.isSharingActive(ctx))

        // 3. Agency classification map colors match Hub palette
        assertEquals("#EF4444", KokohoAgency.DHS_ICE.mapColorHex)
        assertEquals("#EC4899", KokohoAgency.DHS_AMO.mapColorHex)
        assertEquals("#F97316", KokohoAgency.DHS_CBP.mapColorHex)
        assertEquals("#38BDF8", KokohoAgency.SHERIFF.mapColorHex)
        assertEquals("#06B6D4", KokohoAgency.DRONE.mapColorHex)

        // 4. Catalog schemaVersion == 6 & new MOBILE_INPUT uplink channels match
        val assetJson = ctx.assets.open("kokoho_signatures.json").bufferedReader().use { it.readText() }
        assertTrue(assetJson.contains("\"schemaVersion\": 6"))
        assertEquals(KokohoAgency.DHS_CBP, KokohoSignatures.matchFrequency(162_662_500L)?.agencyEnum)
        assertEquals(KokohoAgency.DHS_ICE, KokohoSignatures.matchFrequency(163_162_500L)?.agencyEnum)
        assertEquals(KokohoAgency.DHS_HSI, KokohoSignatures.matchFrequency(165_662_500L)?.agencyEnum)
        assertEquals(KokohoAgency.DHS_FPS, KokohoSignatures.matchFrequency(417_200_000L)?.agencyEnum)

        // 5. Helicopter vs fixed-wing airframe detection
        assertTrue(KokohoSignatures.isHelicopterAirframe("AS50", "A7", "CBP Air"))
        assertTrue(KokohoSignatures.isHelicopterAirframe("EC120", null, "Eurocopter"))
        assertFalse(KokohoSignatures.isHelicopterAirframe("PC12", "A1", "Pilatus"))

        // 6. Contiguous OUI-NIC MAC block learning requires multi-day corroboration (>= 3 hits across >= 2 days)
        val day1Ms = 86_400_000L * 10
        val day2Ms = 86_400_000L * 11
        KokohoSignatures.recordLocalMacCorroboration(
            context = ctx,
            mac = "00:30:44:10:00:01",
            agency = KokohoAgency.DHS_ICE,
            label = "Cradlepoint ERO",
            isCorroborated = true,
            capabilities = listOf("Bodycam"),
            nowMs = day1Ms
        )
        // Single-day / single-hit must NOT poison block learning
        assertNull(KokohoSignatures.lookupAssignedMacBlock(ctx, "00:30:44:10:00:15"))
        for (ts in listOf(day1Ms + 60_000L, day2Ms)) {
            KokohoSignatures.recordLocalMacCorroboration(
                context = ctx,
                mac = "00:30:44:10:00:01",
                agency = KokohoAgency.DHS_ICE,
                label = "Cradlepoint ERO",
                isCorroborated = true,
                capabilities = listOf("Bodycam"),
                nowMs = ts
            )
        }
        for (ts in listOf(day1Ms, day1Ms + 60_000L, day2Ms)) {
            KokohoSignatures.recordLocalMacCorroboration(
                context = ctx,
                mac = "00:30:44:10:00:40",
                agency = KokohoAgency.DHS_ICE,
                label = "Cradlepoint ERO",
                isCorroborated = true,
                capabilities = listOf("Drone Operator"),
                nowMs = ts
            )
        }
        val blockHit = KokohoSignatures.lookupAssignedMacBlock(ctx, "00:30:44:10:00:15")
        assertNotNull(blockHit)
        assertEquals(KokohoAgency.DHS_ICE, blockHit!!.agencyEnum)

        // 7. Agent identity + capability formatting ("Jim Crowe #48392 [Drone Operator]")
        val formatted = KokohoSignatures.formatIdentityWithCapabilities(
            baseLabel = "ICE",
            witness = WitnessReportRecord(
                targetKey = "u-crowe",
                agencyCode = "DHS_ICE",
                agentName = "Jim Crowe",
                badgeNumber = "48392"
            ),
            capabilities = listOf("Drone Operator")
        )
        assertEquals("Jim Crowe #48392 [Drone Operator]", formatted)

        // 8. Chaikin flight smoothing & grounded aircraft landing pad recording
        val rawPts = listOf(32.1000 to -110.9500, 32.1500 to -110.9200, 32.2000 to -110.9600)
        val smoothed = org.tucsoncommunity.kokoho.online.PoliceAircraft.chaikinSmooth(rawPts, 2)
        assertTrue(smoothed.size > rawPts.size)
        assertEquals(rawPts.first(), smoothed.first())
        assertEquals(rawPts.last(), smoothed.last())

        org.tucsoncommunity.kokoho.online.PoliceAircraft.markGroundedAndRecordLanding(
            ctx,
            "AE1234",
            "DHS_AMO",
            32.1161,
            -110.9410,
            300_000L
        )
        assertTrue("AE1234" in org.tucsoncommunity.kokoho.online.PoliceAircraft.currentlyGroundedHexes(300_000L))
        assertTrue(org.tucsoncommunity.kokoho.online.PoliceAircraft.landedSpots(ctx).any { it.hex == "AE1234" })

        // 9. Repeater burst sector tempo label works
        org.tucsoncommunity.kokoho.sdr.RadioLog.clear()
        repeat(6) { idx ->
            org.tucsoncommunity.kokoho.sdr.RadioLog.recordRepeaterBurst(500_000L + idx * 1_000L)
        }
        val tempo = org.tucsoncommunity.kokoho.sdr.RadioLog.sectorTempoLabel(510_000L)
        assertNotNull(tempo)
        assertTrue(tempo!!.contains("Sector Tempo"))

        // 10. Visual proof sheet of circular agency badge icons across agencies & icon types
        val grid = Bitmap.createBitmap(920, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grid)
        canvas.drawColor(Color.parseColor("#1B2230"))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        val kinds = org.tucsoncommunity.kokoho.ui.MapIcons.BadgeIconKind.entries
        val showcaseAgencies = listOf(
            KokohoAgency.DHS_ICE,
            KokohoAgency.DHS_AMO,
            KokohoAgency.DHS_CBP,
            KokohoAgency.DHS_HSI,
            KokohoAgency.DHS_FPS,
            KokohoAgency.SHERIFF,
            KokohoAgency.DRONE,
            KokohoAgency.FIRE
        )
        for ((rowIdx, kind) in kinds.withIndex()) {
            val y = 42f + rowIdx * 62f
            p.textAlign = Paint.Align.LEFT
            canvas.drawText(kind.name, 20f, y + 32f, p)
            p.textAlign = Paint.Align.CENTER
            for ((colIdx, ag) in showcaseAgencies.withIndex()) {
                val bmp = org.tucsoncommunity.kokoho.ui.MapIcons.classificationBadgeIcon(1.8f, kind, ag.mapColorArgb)
                assertEquals(54, bmp.width)
                assertEquals(54, bmp.height)
                val x = 185f + colIdx * 90f
                canvas.drawBitmap(bmp, x - bmp.width / 2f, y, null)
                if (rowIdx == 0) {
                    canvas.drawText(ag.friendlyLabel, x, 24f, p)
                }
            }
        }
        saveBitmap(grid, "kokoho_v1_1_4_badges_proof.png")

        controller.pause().stop().destroy()
    }

    @Test
    fun v115TrailingVehicleIceFoAndLocalPoliceDisambiguationWorks() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()

        // 1. Map renderer unconditionally uses OpenGL to avoid Vulkan app-switch crashes
        assertEquals(
            org.maplibre.android.RenderingEngine.Type.OPENGL,
            org.tucsoncommunity.kokoho.ui.MapSafety.renderer(ctx)
        )

        // 2. 2-pass ("circling the block") triangulation converges cleanly without phantom trailing projection
        val circleUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:00:30:44:DE:AD:01")
        val t0 = System.currentTimeMillis()
        val decayMs = 45 * 60_000L
        // Pass 1 (South side of block heading East)
        KokohoPostureEngine.recordAndTriangulate(circleUuid, 32.1332, -110.9780, -82, 220, t0, decayMs)
        KokohoPostureEngine.recordAndTriangulate(circleUuid, 32.1332, -110.9765, -66, 95, t0 + 8_000L, decayMs)
        // Pass 2 (East/North side of block circling back)
        KokohoPostureEngine.recordAndTriangulate(circleUuid, 32.1340, -110.9758, -68, 105, t0 + 28_000L, decayMs)
        val circleRes = KokohoPostureEngine.recordAndTriangulate(circleUuid, 32.1342, -110.9766, -64, 85, t0 + 40_000L, decayMs)
        assertTrue(circleRes.isApproximatedPosition)
        assertNotNull(circleRes.centerLat)
        assertNotNull(circleRes.centerLon)
        assertTrue("2-pass circling the block must tighten spatial confidence", circleRes.spatialConfidence >= 55)

        // 3. Cataloged ICE Field Office frequency matches while uncataloged wideband noise never fabricates ICE-UHF-*
        val iceOnly = setOf(KokohoAgency.DHS_ICE)
        assertEquals(KokohoAgency.DHS_ICE, KokohoSignatures.matchFrequency(168_362_500L)?.agencyEnum)
        assertNull(KokohoSignatures.matchCloseRangeFederalBand(412_625_000L, 42, enabledAgencies = iceOnly))
        assertNull(KokohoSignatures.matchCloseRangeFederalBand(413_450_000L, 42, enabledAgencies = iceOnly))
        assertNull(KokohoSignatures.matchCloseRangeFederalBand(167_862_500L, 42, enabledAgencies = iceOnly))

        // 4. School bus & retail SSID exclusions
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:AA:BB:11", "SUSD-BUS-14", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:AA:BB:12", "PioneerStore", "Cradlepoint"))

        // 5. State Trooper / TPD disambiguation (Cradlepoint + Zebra kit reclassifies & respects POLICE toggle)
        val nowMs = System.currentTimeMillis()
        val geo = org.tucsoncommunity.kokoho.service.DeviceRegistry.GeoSample(nowMs, 32.1341, -110.9734)
        val cradleAdvert = org.tucsoncommunity.kokoho.detect.Advert(
            mac = "00:30:44:77:88:99",
            source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
            rssi = -68,
            name = null,
            wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "", null, emptyList(), client = false),
            timestamp = nowMs
        )
        val zebraAdvert = org.tucsoncommunity.kokoho.detect.Advert(
            mac = "94:FB:29:11:22:33",
            source = org.tucsoncommunity.kokoho.detect.Advert.Source.BLE,
            rssi = -70,
            name = "ZEBRA ZQ521",
            timestamp = nowMs
        )
        val idWifi = org.tucsoncommunity.kokoho.detect.DeviceIntel.Identity("WiFi access point", emptyList())
        val idBle = org.tucsoncommunity.kokoho.detect.DeviceIntel.Identity("BLE peripheral", emptyList())
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            cradleAdvert, emptyList(), idWifi, "Cradlepoint", null, geo, nowMs, here = geo
        )
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            zebraAdvert, emptyList(), idBle, "Zebra Technologies", null, geo, nowMs, here = geo
        )

        KokohoSignatures.setEnabledAgencies(ctx, setOf(KokohoAgency.DHS_CBP, KokohoAgency.DHS_ICE))
        val suppressedSnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertTrue(
            "Trooper Cradlepoint+Zebra must NOT false-alert as CBP when POLICE is off",
            suppressedSnap.activeThreats.none { it.agency == KokohoAgency.DHS_CBP }
        )

        KokohoSignatures.setEnabledAgencies(ctx, setOf(KokohoAgency.DHS_CBP, KokohoAgency.DHS_ICE, KokohoAgency.POLICE))
        val policeSnap = KokohoPostureEngine.evaluate(ctx, nowMs)
        val trooperThreat = policeSnap.activeThreats.firstOrNull { it.agency == KokohoAgency.POLICE }
        assertNotNull("Expected Cradlepoint+Zebra unit to classify as POLICE", trooperThreat)
        assertTrue("Citation Printer capability should be attached", trooperThreat!!.capabilities.contains("Citation Printer"))

        // 6. 700/800 MHz public safety mobile uplink respects enabled POLICE/SHERIFF agency
        assertEquals(
            KokohoAgency.POLICE,
            KokohoSignatures.matchPublicSafetyMobileUplink(808_250_000L, 35, setOf(KokohoAgency.POLICE))?.agencyEnum
        )
        assertEquals(
            KokohoAgency.SHERIFF,
            KokohoSignatures.matchPublicSafetyMobileUplink(808_250_000L, 35, setOf(KokohoAgency.SHERIFF))?.agencyEnum
        )

        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.DHS_AGENCIES.toSet())
    }

    @Test
    fun v117PrincipledSiblingVapAndTrafficCabinetSuppressionWorks() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()

        val nowMs = System.currentTimeMillis()
        val geo = org.tucsoncommunity.kokoho.service.DeviceRegistry.GeoSample(nowMs, 32.3408, -110.9776)
        val idWifi = org.tucsoncommunity.kokoho.detect.DeviceIntel.Identity("WiFi access point", emptyList())

        // 1. Sibling VAP hardware-group resolution:
        //    `00:30:44:74:AB:83` (`IBR1700-b82`) + `06:30:44:74:AB:83` (`PCSD-Vehicle`)
        //    both resolve to `00:30:44:74:AB:80`, are collected, and classify the physical unit as SHERIFF!
        assertEquals(
            "00:30:44:74:AB:80",
            KokohoSignatures.routerRadioGroupKey("00:30:44:74:AB:83")
        )
        assertEquals(
            "00:30:44:74:AB:80",
            KokohoSignatures.routerRadioGroupKey("06:30:44:74:AB:83")
        )
        assertTrue(KokohoSignatures.isRelevantBleOrWifiCandidate("06:30:44:74:AB:83", "PCSD-Vehicle", null, false, false))
        assertEquals(KokohoAgency.SHERIFF, KokohoSignatures.matchOuiOrSsid("06:30:44:74:AB:83", "PCSD-Vehicle", null)?.first)

        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "00:30:44:74:AB:83",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -67,
                name = "IBR1700-b82",
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false, upSince = nowMs - 3600_000L),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "Cradlepoint", null, geo, nowMs, here = geo
        )
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "06:30:44:74:AB:83",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -69,
                name = "PCSD-Vehicle",
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false, upSince = nowMs - 3600_000L),
                timestamp = nowMs
            ),
            emptyList(), idWifi, null, null, geo, nowMs, here = geo
        )

        // 2. Stationary commercial router with hidden BSSIDs (`D4:13:F8:7F:C6:49`) + Open [ESS] guest VAP (`D6:13:F8:7F:C6:48` `Guys Win`, 215d uptime):
        //    Collected in DeviceRegistry (`isRelevantBleOrWifiCandidate == true`), but suppressed from active threats!
        assertTrue(KokohoSignatures.isRelevantBleOrWifiCandidate("D4:13:F8:7F:C6:49", null, "Peplink", false, false))
        assertTrue(KokohoSignatures.isRelevantBleOrWifiCandidate("D6:13:F8:7F:C6:48", "Guys Win", null, false, false))
        assertEquals(
            KokohoSignatures.routerRadioGroupKey("D4:13:F8:7F:C6:49"),
            KokohoSignatures.routerRadioGroupKey("D6:13:F8:7F:C6:48")
        )
        val uptime215DaysAgo = nowMs - 215L * 86_400_000L
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "D4:13:F8:7F:C6:49",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -65,
                name = null,
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2462, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false, upSince = uptime215DaysAgo),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "Peplink", null, geo, nowMs, here = geo
        )
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "D6:13:F8:7F:C6:48",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -66,
                name = "Guys Win",
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2462, "[ESS]", null, emptyList(), client = false, upSince = uptime215DaysAgo),
                timestamp = nowMs
            ),
            emptyList(), idWifi, null, null, geo, nowMs, here = geo
        )

        // 3. Municipal traffic cabinet (`00:30:44:55:B2:BC` `rmat`, 53d uptime):
        //    Collected in DeviceRegistry (`isRelevantBleOrWifiCandidate == true`), suppressed from threats!
        assertTrue(KokohoSignatures.isRelevantBleOrWifiCandidate("00:30:44:55:B2:BC", "rmat", "Cradlepoint", false, false))
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "00:30:44:55:B2:BC",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -64,
                name = "rmat",
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2412, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false, upSince = nowMs - 53L * 86_400_000L),
                timestamp = nowMs
            ),
            emptyList(), idWifi, "Cradlepoint", null, geo, nowMs, here = geo
        )

        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        val snap = KokohoPostureEngine.evaluate(ctx, nowMs)
        assertEquals(
            "Only the PCSD-Vehicle Sheriff unit should emit an active threat; Guys Win and rmat traffic cabinet must be suppressed",
            1,
            snap.activeThreats.size
        )
        val sheriffUnit = snap.activeThreats.single()
        assertEquals(KokohoAgency.SHERIFF, sheriffUnit.agency)
        assertEquals("00:30:44:74:AB:80", sheriffUnit.hardwareIdentifier)
        assertTrue(sheriffUnit.recurrenceSummary.contains("PCSD-Vehicle"))

        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.DHS_AGENCIES.toSet())
    }

    @Test
    fun v119ZoomUngroupingGroupModalFederalInteropFloorAndAirHeuristicWork() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()

        // 1. Zoom ungrouping: splits completely (0.0m) at zoom >= 15.5
        assertEquals(0.0, KokohoSignatures.zoomGravityMergeRadiusM(15.5), 1e-6)
        assertEquals(0.0, KokohoSignatures.zoomGravityMergeRadiusM(17.0), 1e-6)
        assertEquals(6.0, KokohoSignatures.zoomGravityMergeRadiusM(15.2), 1e-6)

        // 2. DHS_INTEROP never overrides a specific agency's color in mixAgencyColorsArgb
        val iceWithInterop = KokohoSignatures.mixAgencyColorsArgb(
            mapOf(
                KokohoAgency.DHS_ICE to 4.0,
                KokohoAgency.DHS_INTEROP to 25.0
            )
        )
        assertEquals(KokohoAgency.DHS_ICE.mapColorArgb, iceWithInterop)

        // 3. DHS Air (DHS_AMO) defaults to helicopter classification & symbol unless explicitly fixed-wing
        assertTrue(KokohoSignatures.isHelicopterAirframe(null, "DHS_AMO", "DHS Air", KokohoAgency.DHS_AMO))
        assertTrue(KokohoSignatures.isHelicopterAirframe(null, "DHS_AMO", "CBP Air & Marine AS350 (N63LU)", KokohoAgency.DHS_AMO))
        assertFalse(KokohoSignatures.isHelicopterAirframe("PC12", "DHS_AMO", "Pilatus PC-12", KokohoAgency.DHS_AMO))

        // 4. Heuristic filter for misidentified all-flights-at-the-same-time session & downstream records
        assertTrue(
            KokohoSignatures.isAnomalousUnattributedAirRecord(
                primaryKey = "adsb:icao:ACD217",
                category = "DHS_AMO",
                label = "DHS Air",
                facets = emptyList()
            )
        )
        assertTrue(
            KokohoSignatures.isAnomalousUnattributedAirRecord(
                primaryKey = "adsb:icao:AE01C0",
                category = "DHS_AMO",
                label = "Circling Aircraft",
                facets = emptyList(),
                avgVelocityMps = 0.01
            )
        )
        assertFalse(
            KokohoSignatures.isAnomalousUnattributedAirRecord(
                primaryKey = "adsb:icao:A83E12",
                category = "DHS_AMO",
                label = "CBP Air & Marine AS350 (N63LU)",
                facets = listOf("CALL:N63LU", "ICAO:A83E12"),
                avgVelocityMps = 45.0
            )
        )
        assertFalse(
            KokohoSignatures.isAnomalousUnattributedAirRecord(
                primaryKey = "adsb:icao:26919C",
                category = "DHS_AMO",
                label = "Circling Masked Hex Aircraft (26919C · 472 deg turn)",
                facets = listOf("ALERT:ORBITING_OVERHEAD", "ICAO:26919C"),
                avgVelocityMps = 46.0
            )
        )

        // 5. Building-interior convergence preserves tight peak-SNR radius even after driving away
        val bldgUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:D4:13:F8:61:29:68")
        val now = 1794580000000L
        val decay = 45 * 60_000L
        KokohoPostureEngine.recordAndTriangulate(bldgUuid, 32.34124, -110.97654, -52, 26, now, decay)
        KokohoPostureEngine.recordAndTriangulate(bldgUuid, 32.34131, -110.97648, -50, 24, now + 5_000L, decay)
        val afterDrivingAway = KokohoPostureEngine.recordAndTriangulate(bldgUuid, 32.33850, -110.97650, -85, 290, now + 25_000L, decay)
        assertTrue("Building pass must preserve tight converged peak radius <= 28m, got ${afterDrivingAway.uncertaintyRadiusM}", afterDrivingAway.uncertaintyRadiusM <= 28)

        // 6. Axon bodycams & Signal hubs (`00:25:DF`, `D8:1F:65`, `D8:96:E0`) are NEVER conflated as standalone POLICE in matchOui,
        //    and both Axon accessories and uncataloged 800 MHz uplinks (`807.875 MHz`) bias toward the likely primary agency presence!
        assertNull("Axon OUI must not be hardcoded as POLICE in matchOui", KokohoSignatures.matchOui("00:25:DF:F5:5B:81"))
        assertNull("Field-attributed Axon BWCDEVICE OUI D8:1F:65 must not be hardcoded as POLICE in matchOui", KokohoSignatures.matchOui("D8:1F:65:AA:BB:CC"))
        assertNull("Axon Signal OUI must not be hardcoded as POLICE in matchOui", KokohoSignatures.matchOui("D8:96:E0:11:22:33"))
        assertTrue(KokohoSignatures.isAxonOrAgencyAgnosticAccessory("00:25:DF:F5:5B:81", null, "Axon Enterprise"))
        assertTrue(KokohoSignatures.isAxonOrAgencyAgnosticAccessory("D8:1F:65:12:34:56", null, null))
        assertTrue(KokohoSignatures.isAxonOrAgencyAgnosticAccessory("00:1D:96:11:22:33", null, "WatchGuard Video"))
        assertEquals(KokohoAgency.DHS_HSI, KokohoSignatures.matchOui("00:50:C2:36:01:23")?.agencyEnum) // DRTbox Federal Cell-Site Simulator
        assertEquals(KokohoAgency.DHS_CBP, KokohoSignatures.matchOui("00:18:A6:11:22:33")?.agencyEnum) // Persistent Systems MPU5
        assertEquals(KokohoAgency.DRONE, KokohoSignatures.matchOui("00:1A:F9:11:22:33")?.agencyEnum)   // AeroVironment Tactical UAS
        assertEquals(KokohoAgency.POLICE, KokohoSignatures.matchOui("1C:82:59:D1:22:33")?.agencyEnum)  // Stalker Patrol Speed Radar
        assertEquals(
            KokohoAgency.DHS_ICE,
            KokohoSignatures.matchPublicSafetyMobileUplink(
                807_875_000L,
                52,
                enabledAgencies = KokohoAgency.entries.toSet(),
                primaryPresenceAgency = KokohoAgency.DHS_ICE
            )?.agencyEnum
        )
        assertEquals(
            KokohoAgency.SHERIFF,
            KokohoSignatures.matchOui("00:30:44:74:AB:83")?.agencyEnum
        )
    }

    @Test
    fun v120MorningHighwayGhostEliminationCardinalityAndPositiveControlsWork() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())

        val t0 = 1_794_672_228_000L // 2026-11-14 morning highway baseline
        val idWifi = org.tucsoncommunity.kokoho.detect.DeviceIntel.Identity("WiFi access point", emptyList())
        val idBle = org.tucsoncommunity.kokoho.detect.DeviceIntel.Identity("BLE peripheral", emptyList())

        // 1. False OUI, retail, highway light-pole & commercial/repeater RF suppression:
        //    - 00:58:28 ("Axon Networks Inc." Quantum Fiber home router)
        //    - 04:F0:21 / 00:40:29 / 00:80:48 (Compex "MimoSeries1" highway light-pole backhaul)
        //    - Fixed branch/retail SSIDs (IBR600C-50e, E100-697, E110-58b, rmat, Saladandgo, sscw_Store_WIFI, SBXL 2)
        //    - Unnamed Wi-Fi Zebra client (C8:1C:FE:8F:9A:39)
        //    - Commercial Part 22/90 channels (152.8875, 154.375, 452.725, 462.425, 464.4125 MHz) & AZDPS repeaters (460.425 MHz)
        assertFalse(KokohoSignatures.isAxonOrAgencyAgnosticAccessory("00:58:28:2C:DF:52", "QF-9A2B", "Axon Networks Inc."))
        assertFalse(KokohoSignatures.isRelevantBleOrWifiCandidate("00:58:28:2C:DF:52", "QF-9A2B", "Axon Networks Inc.", false, false))
        assertNull(KokohoSignatures.matchOuiOrSsid("04:F0:21:6C:47:CB", "MimoSeries1", "Compex Systems"))
        assertFalse(KokohoSignatures.isRelevantBleOrWifiCandidate("04:F0:21:6C:47:CB", "MimoSeries1", "Compex Systems", false, false))
        for (hz in listOf(152_887_500L, 154_375_000L, 452_725_000L, 462_425_000L, 464_412_500L)) {
            assertNull("Commercial frequency $hz must be purged from catalog", KokohoSignatures.matchFrequency(hz, includeRepeaters = true))
        }
        assertNull("AZDPS hilltop repeater 460.425 MHz must be excluded from spatial alerts", KokohoSignatures.matchFrequency(460_425_000L))
        assertEquals("REPEATER", KokohoSignatures.matchFrequency(460_425_000L, includeRepeaters = true)?.role)

        val grantGeo = org.tucsoncommunity.kokoho.service.DeviceRegistry.GeoSample(t0, 32.2504, -110.9440)
        for ((mac, ssid, vendor) in listOf(
            Triple("00:30:44:21:10:01", "IBR600C-50e", "Cradlepoint"),
            Triple("00:30:44:21:10:02", "E100-697", "Cradlepoint"),
            Triple("00:30:44:21:10:03", "E110-58b", "Cradlepoint"),
            Triple("00:30:44:21:10:04", "Saladandgo", "Cradlepoint"),
            Triple("00:30:44:21:10:05", "sscw_Store_WIFI", "Cradlepoint"),
            Triple("00:30:44:21:10:06", "SBXL 2", "Cradlepoint")
        )) {
            org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
                org.tucsoncommunity.kokoho.detect.Advert(
                    mac = mac,
                    source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                    rssi = -64,
                    name = ssid,
                    wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                    timestamp = t0
                ),
                emptyList(), idWifi, vendor, null, grantGeo, t0, here = grantGeo
            )
        }
        // Unnamed retail Wi-Fi Zebra client (no BLE, no printer name, no colocated patrol router)
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "C8:1C:FE:8F:9A:39",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -66,
                name = null,
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "", null, emptyList(), client = true),
                timestamp = t0
            ),
            emptyList(), idWifi, "Zebra Technologies", null, grantGeo, t0, here = grantGeo
        )
        val cleanSnap = KokohoPostureEngine.evaluate(ctx, t0)
        assertTrue("Expected 0 false threats from Grant Rd retail/branch/unnamed-Zebra Wi-Fi, got ${cleanSnap.activeThreats}", cleanSnap.activeThreats.isEmpty())

        // 2. GPS scan-latency compensation at 35.0 m/s (78.3 mph):
        //    A 15-second-old cached Wi-Fi ScanResult must interpolate ~525m back to where the packet actually arrived!
        fun makeGpsLoc(lat: Double, lon: Double, speedMps: Float, bearingDeg: Float, timeMs: Long) =
            android.location.Location("gps").apply {
                latitude = lat
                longitude = lon
                speed = speedMps
                bearing = bearingDeg
                time = timeMs
                accuracy = 4.5f
            }
        val (trueStartLat, trueStartLon) = 32.23600 to -110.98370
        val (curHighwayLat, curHighwayLon) = org.tucsoncommunity.kokoho.service.ScanForegroundService.projectAlongBearing(trueStartLat, trueStartLon, 35.0, 315.0, 20.0)
        val trail = (0..20).map { sec ->
            val (lt, ln) = org.tucsoncommunity.kokoho.service.ScanForegroundService.projectAlongBearing(trueStartLat, trueStartLon, 35.0, 315.0, sec.toDouble())
            (t0 + sec * 1_000L) to makeGpsLoc(lt, ln, 35.0f, 315.0f, t0 + sec * 1_000L)
        }
        val packetWallMs = t0 + 5_000L // arrived 15s before t0 + 20s!
        val expectedAt5s = trail[5].second
        val compensated = org.tucsoncommunity.kokoho.service.ScanForegroundService.interpolatePositionAt(
            targetWallMs = packetWallMs,
            snapshot = trail,
            fallback = trail.last().second,
            sysNow = t0 + 20_000L
        )
        val fallbackCompensated = org.tucsoncommunity.kokoho.service.ScanForegroundService.interpolatePositionAt(
            targetWallMs = packetWallMs,
            snapshot = emptyList(),
            fallback = trail.last().second,
            sysNow = t0 + 20_000L
        )
        val errM = KokohoPostureEngine.haversineMeters(expectedAt5s.latitude, expectedAt5s.longitude, compensated.lat, compensated.lon)
        val fallbackErrM = KokohoPostureEngine.haversineMeters(expectedAt5s.latitude, expectedAt5s.longitude, fallbackCompensated.lat, fallbackCompensated.lon)
        val uncompErrM = KokohoPostureEngine.haversineMeters(expectedAt5s.latitude, expectedAt5s.longitude, curHighwayLat, curHighwayLon)
        assertTrue("Uncompensated highway lag should be > 500m, got $uncompErrM", uncompErrM > 500.0)
        assertTrue("Latency-compensated trail placement error must be < 5m, got $errM", errM < 5.0)
        assertTrue("Latency-compensated dead-reckoning fallback error must be < 10m, got $fallbackErrM", fallbackErrM < 10.0)

        // 3. Passed stationary roadside unit stays locked at its pass point as car drives 3.15 km NW along I-10 at 35 m/s,
        //    while wide-area non-localizing RF along the track is suppressed and 169.1625 MHz AIR 4 stays wide-area!
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        KokohoPostureEngine.clearLocalState()
        RadioLog.clear()

        val passLat = 32.24000
        val passLon = -110.98800
        val passGeo = org.tucsoncommunity.kokoho.service.DeviceRegistry.GeoSample(t0 + 10_000L, passLat, passLon)
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "00:30:44:74:AB:83",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -54,
                name = "PCSD-Vehicle",
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                timestamp = t0 + 10_000L
            ),
            emptyList(), idWifi, "Cradlepoint", null, passGeo, t0 + 10_000L, here = passGeo
        )
        // Drive 90 seconds NW along I-10 at 35 m/s (3,150 m away) without hearing the stationary unit again
        val (farLat, farLon) = org.tucsoncommunity.kokoho.service.ScanForegroundService.projectAlongBearing(passLat, passLon, 35.0, 315.0, 90.0)
        val farTime = t0 + 100_000L
        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(makeGpsLoc(farLat, farLon, 35.0f, 315.0f, farTime))

        // Also feed wide-area non-localizing ground RF (163.700 MHz, flat 18 dB SNR across 1.2 km) -> must be suppressed!
        val sharedBand = org.tucsoncommunity.kokoho.sdr.RadioWatch.Band("VHF", 162_000_000L, 174_000_000L, org.tucsoncommunity.kokoho.sdr.RadioWatch.Kind.SHARED)
        RadioLog.observe(org.tucsoncommunity.kokoho.sdr.RadioWatch.Event(163_700_000L, 18, sharedBand, 70), "VTAC", 3, t0 + 20_000L, passLat, passLon)
        RadioLog.observe(org.tucsoncommunity.kokoho.sdr.RadioWatch.Event(163_700_000L, 19, sharedBand, 70), "VTAC", 3, farTime - 5_000L, farLat, farLon)

        // Feed DHS_AMO Airband RF (169.1625 MHz AIR 4) -> must stay wide-area (>= 1500m), non-pinpointed, YELLOW!
        RadioLog.observe(org.tucsoncommunity.kokoho.sdr.RadioWatch.Event(169_162_500L, 26, sharedBand, 80), "AIR 4", 3, farTime - 2_000L, farLat, farLon)

        val highwaySnap = KokohoPostureEngine.evaluate(ctx, farTime)
        val passedSheriff = highwaySnap.activeThreats.firstOrNull { it.agency == KokohoAgency.SHERIFF }
        assertNotNull("Passed stationary Sheriff unit should remain known on map behind car", passedSheriff)
        val driftFromPassM = KokohoPostureEngine.haversineMeters(passLat, passLon, passedSheriff!!.lat!!, passedSheriff.lon!!)
        assertTrue("Passed stationary unit must stay locked within 15m of pass point, drifted $driftFromPassM m", driftFromPassM <= 15.0)
        assertTrue("Distance to passed stationary unit must grow > 2800m behind car, got ${passedSheriff.estimatedDistanceM}", passedSheriff.estimatedDistanceM > 2800)
        assertNotEquals("Passed unit 3km behind car must drop out of RED posture", PostureColor.RED, passedSheriff.posture)
        assertTrue(
            "Wide-area non-localizing ground RF (flat 18-19 dB over >1km) must be suppressed",
            highwaySnap.activeThreats.none { it.evidenceSources.any { b -> b.facetKey.contains("163700000") } }
        )
        val airRf = highwaySnap.activeThreats.firstOrNull { it.agency == KokohoAgency.DHS_AMO }
        assertNotNull("DHS_AMO 169.1625 MHz should surface as wide-area airband", airRf)
        assertTrue("Airband SDR RF must stay wide-area (>= 1500m), got ${airRf!!.uncertaintyRadiusM}", airRf.uncertaintyRadiusM >= 1500)
        assertFalse("Airband SDR RF must never claim pinpointed car GPS", airRf.isPinpointed)
        assertEquals(PostureColor.YELLOW, airRf.posture)

        // 4. Positive Control A: True co-moving vehicle (Cradlepoint + Axon Body 4) tailing 35m behind at 35 m/s for 40s
        //    fuses into 1 RED vehicle threat with Bodycam capability and stays within <= 110m (no 170m lag)!
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        KokohoPostureEngine.clearLocalState()
        RadioLog.clear()
        for (step in 0..8) {
            val stepTs = t0 + step * 5_000L
            val (carLat, carLon) = org.tucsoncommunity.kokoho.service.ScanForegroundService.projectAlongBearing(trueStartLat, trueStartLon, 35.0, 315.0, step * 5.0)
            val (tailLat, tailLon) = org.tucsoncommunity.kokoho.service.ScanForegroundService.projectAlongBearing(carLat, carLon, 35.0, 135.0, 1.0) // 35m behind car
            val stepGeo = org.tucsoncommunity.kokoho.service.DeviceRegistry.GeoSample(stepTs, tailLat, tailLon)
            org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(makeGpsLoc(carLat, carLon, 35.0f, 315.0f, stepTs))
            org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
                org.tucsoncommunity.kokoho.detect.Advert(
                    mac = "00:30:44:AA:BB:10",
                    source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                    rssi = -56,
                    name = null,
                    wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                    timestamp = stepTs
                ),
                emptyList(), idWifi, "Cradlepoint", null, stepGeo, stepTs, here = stepGeo
            )
            org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
                org.tucsoncommunity.kokoho.detect.Advert(
                    mac = "00:25:DF:AA:BB:11",
                    source = org.tucsoncommunity.kokoho.detect.Advert.Source.BLE,
                    rssi = -58,
                    name = "Axon Body 4",
                    timestamp = stepTs
                ),
                emptyList(), idBle, "Axon Enterprise", null, stepGeo, stepTs, here = stepGeo
            )
            KokohoPostureEngine.evaluate(ctx, stepTs)
        }
        val tailSnap = KokohoPostureEngine.evaluate(ctx, t0 + 40_000L)
        assertEquals("Co-moving vehicle router + Axon bodycam must fuse into 1 vehicle threat", 1, tailSnap.activeThreats.size)
        val tailVehicle = tailSnap.activeThreats.single()
        assertEquals(PostureColor.RED, tailVehicle.posture)
        assertTrue("Vehicle must carry Bodycam capability", tailVehicle.capabilities.contains("Bodycam"))
        assertTrue("Exponential recency weighting must keep 78 mph co-moving tail within <= 110m, got ${tailVehicle.estimatedDistanceM}m", tailVehicle.estimatedDistanceM <= 110)

        // 5. Positive Control B (Physical Cardinality):
        //    - 1 vehicle router + 2 colocated officer bodycams -> 1 vehicle threat holding both hw:kit:* badges (1:N)
        //    - 2 standalone officer bodycams on foot (no vehicle router) -> 2 distinct hw:kit:* threats (1:1 per officer)
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        KokohoPostureEngine.clearLocalState()
        val footTs = t0 + 200_000L
        val footGeo = org.tucsoncommunity.kokoho.service.DeviceRegistry.GeoSample(footTs, 32.2218, -110.9712)
        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(makeGpsLoc(32.2218, -110.9712, 0f, 0f, footTs))

        // First: 2 standalone bodycams on foot -> MUST stay 2 distinct threats (1:1 per officer)
        for (mac in listOf("00:25:DF:11:00:01", "00:25:DF:22:00:02")) {
            org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
                org.tucsoncommunity.kokoho.detect.Advert(
                    mac = mac,
                    source = org.tucsoncommunity.kokoho.detect.Advert.Source.BLE,
                    rssi = -59,
                    name = "Axon Body 4",
                    timestamp = footTs
                ),
                emptyList(), idBle, "Axon Enterprise", null, footGeo, footTs, here = footGeo
            )
        }
        val standaloneFootSnap = KokohoPostureEngine.evaluate(ctx, footTs)
        assertEquals(
            "Two standalone officer bodycams without a vehicle router must remain 2 distinct 1:1 officer threats",
            2,
            standaloneFootSnap.activeThreats.size
        )

        // Second: Add 1 colocated patrol vehicle router -> both officer bodycams attach (1:N) into the 1 vehicle threat!
        org.tucsoncommunity.kokoho.service.DeviceRegistry.report(
            org.tucsoncommunity.kokoho.detect.Advert(
                mac = "00:30:44:99:88:70",
                source = org.tucsoncommunity.kokoho.detect.Advert.Source.WIFI,
                rssi = -55,
                name = "PCSD-Vehicle",
                wifi = org.tucsoncommunity.kokoho.detect.Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = false),
                timestamp = footTs
            ),
            emptyList(), idWifi, "Cradlepoint", null, footGeo, footTs, here = footGeo
        )
        val vehicleWithTwoOfficersSnap = KokohoPostureEngine.evaluate(ctx, footTs)
        assertEquals(
            "One vehicle router with two colocated officer bodycams must fuse into 1 vehicle threat (1:N)",
            1,
            vehicleWithTwoOfficersSnap.activeThreats.size
        )
        val squadCar = vehicleWithTwoOfficersSnap.activeThreats.single()
        val kitFacets = squadCar.evidenceSources.map { it.facetKey }.filter { it.startsWith("hw:kit:") }.toSet()
        assertEquals("Vehicle threat must hold both distinct officer kit facets", 2, kitFacets.size)

        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(null)
        KokohoPostureEngine.clearLocalState()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.DHS_AGENCIES.toSet())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun v121ZeroJitterSharingPulseEveningTripHomeAndBlockHygieneWork() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoPostureEngine.clearLocalState()
        KokohoHubClient.clearCachedObjects()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())

        val t0 = 1_794_790_000_000L // 2026-11-15 evening trip home baseline

        // 1. Spatial jitter is completely removed: exact coordinates are preserved regardless of radius
        val (noJitLat, noJitLon) = KokohoPostureEngine.deriveJitteredCenter(
            "ac6af89c-1111-5222-8333-444455556666",
            32.22164,
            -110.97182,
            950
        )
        assertEquals(32.22164, noJitLat, 1e-7)
        assertEquals(-110.97182, noJitLon, 1e-7)

        // 2. Stationary RF circle radius stability & Local-vs-Hub echo deduplication:
        //    807.4500 MHz heard at (32.2216, -110.9718), plus Hub `/v1/room/ingest` echo `55c8bdea`,
        //    followed by driving 3.5 km north -> 1 single deduplicated puck whose `mapCircleRadiusM` stays <= 120m!
        val band800 = org.tucsoncommunity.kokoho.sdr.RadioWatch.Band(
            "800",
            806_000_000L,
            814_000_000L,
            org.tucsoncommunity.kokoho.sdr.RadioWatch.Kind.SHARED
        )
        RadioLog.observe(
            org.tucsoncommunity.kokoho.sdr.RadioWatch.Event(807_450_000L, 42, band800, 88),
            "PS-800-IN",
            3,
            t0,
            32.22160,
            -110.97180
        )
        KokohoHubClient.ingestThreatObjectJson(
            """
            {
              "uuid": "55c8bdea-4d58-51c9-943f-5a8b7ca93533",
              "track_uuid": "55c8bdea-4d58-51c9-943f-5a8b7ca93533",
              "updated_at_ms": $t0,
              "confidence": { "overall": 0.72, "classification": 0.88, "spatial": 0.56, "temporal_freshness": 1.0 },
              "location": { "lat": 32.22160, "lon": -110.97180, "uncertainty_radius_m": 108.0, "h3_res8": 614259430555648001, "propagation_class": "GROUND_DIRECT" },
              "classification": {
                "master_group": "OTHER",
                "category": "POLICE",
                "label": "Tucson PD / State",
                "recurrence_summary": "PS-800-IN",
                "evidence_sources": [{ "kind": "RF_P25", "facet_key": "rf:chan:807450000:ANY", "detail": "807.4500 MHz", "weight": 0.70, "last_seen_ms": $t0 }]
              }
            }
            """.trimIndent()
        )
        val homeLoc = android.location.Location("gps").apply {
            latitude = 32.25310 // ~3.5 km North of Broadway sighting
            longitude = -110.97180
            time = t0 + 90_000L
        }
        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(homeLoc)
        val driveHomeSnap = KokohoPostureEngine.evaluate(ctx, t0 + 90_000L)
        val rf807Threats = driveHomeSnap.mapThreats.filter {
            it.evidenceSources.any { b -> b.facetKey.startsWith("rf:chan:807450000:") }
        }
        assertEquals("Local RF + Hub echo for 807.4500 MHz must deduplicate to 1 puck", 1, rf807Threats.size)
        val rf807 = rf807Threats.single()
        assertTrue("True distance from home must reflect ~3.5 km (>3200m), got ${rf807.estimatedDistanceM}", rf807.estimatedDistanceM > 3200)
        assertTrue("Anchored mapCircleRadiusM must stay fixed at uncertaintyRadiusM (<= 120m), got ${rf807.mapCircleRadiusM}", rf807.mapCircleRadiusM <= 120)

        // 3. DoD 406.1–411.0 MHz multi-channel base trunk suppression (Davis-Monthan AFB)
        RadioLog.clear()
        KokohoHubClient.clearCachedObjects()
        KokohoPostureEngine.clearLocalState()
        val bandUhfFed = org.tucsoncommunity.kokoho.sdr.RadioWatch.Band(
            "UHF-Fed",
            406_100_000L,
            420_000_000L,
            org.tucsoncommunity.kokoho.sdr.RadioWatch.Kind.SHARED
        )
        for (hz in listOf(407_125_000L, 408_575_000L, 409_375_000L, 410_175_000L)) {
            RadioLog.observe(
                org.tucsoncommunity.kokoho.sdr.RadioWatch.Event(hz, 40, bandUhfFed, 85),
                null,
                3,
                t0 + 100_000L,
                32.2216,
                -110.9500
            )
        }
        val dodSnap = KokohoPostureEngine.evaluate(ctx, t0 + 101_000L)
        assertTrue("Expected DoD UHF Trunk in sectorTempoText, got ${dodSnap.sectorTempoText}", dodSnap.sectorTempoText?.contains("DoD UHF Trunk (4 ch)") == true)
        assertTrue("Uncataloged 406.1-411 MHz DoD base trunk channels must not spawn DHS_INTEROP pucks", dodSnap.activeThreats.isEmpty())

        // 4. Aircraft precision: PA-38 Tomahawk trainer (`N2483K`) & KTUS runway pattern touch-and-go suppression
        val n2483k = org.tucsoncommunity.kokoho.online.PoliceAircraft.Plane(
            hex = "a25082",
            callsign = "N2483K",
            registration = "N2483K",
            type = "PA38",
            owner = "SMITH FLIGHT TRAINING",
            lat = 32.1180,
            lon = -110.9420,
            altitudeFt = 3500,
            speedKt = 88.0,
            trackDeg = 120.0,
            military = false,
            onGround = false,
            category = "A1"
        )
        assertTrue("PA-38 Tomahawk trainer must be excluded", org.tucsoncommunity.kokoho.online.PoliceAircraft.isGliderOrExcludedTrainer(n2483k))
        assertNull(
            "PA-38 Tomahawk must never classify as DHS_AMO",
            KokohoSignatures.classifyAircraftAgency(
                owner = n2483k.owner,
                registration = n2483k.registration,
                callsign = n2483k.callsign,
                hitLabel = org.tucsoncommunity.kokoho.online.PoliceAircraft.CIRCLING,
                type = "PA-38 Tomahawk",
                hex = "a25082",
                speedKt = 88.0,
                isCircling = true
            )
        )
        assertTrue("Unlisted GA pattern orbit within 3.8 km of KTUS at 3,600 ft MSL must be suppressed",
            org.tucsoncommunity.kokoho.online.PoliceAircraft.isAirportTrafficPattern(32.1180, -110.9420, 3600, false))
        assertFalse("Explicit LE/Sheriff orbit near KTUS must NEVER be suppressed",
            org.tucsoncommunity.kokoho.online.PoliceAircraft.isAirportTrafficPattern(32.1180, -110.9420, 3600, true))
        // Shell-owned circling PC-12 away from airports still classifies as DHS_AMO fixed-wing (not helicopter)
        assertEquals(
            KokohoAgency.DHS_AMO,
            KokohoSignatures.classifyAircraftAgency(
                owner = "OBRERO LEASING LLC",
                registration = "N404KR",
                callsign = "N404KR",
                hitLabel = org.tucsoncommunity.kokoho.online.PoliceAircraft.CIRCLING,
                type = "PC12",
                hex = "a4bc12",
                speedKt = 135.0,
                isCircling = true
            )
        )
        assertFalse(KokohoSignatures.isHelicopterAirframe("PA28", "DHS_AMO", "Piper Archer", KokohoAgency.DHS_AMO))

        // 5. KNOX / TFDCP Fire classification & dual-band `:08`/`:0C` block hygiene
        assertEquals(KokohoAgency.FIRE, KokohoSignatures.matchOuiOrSsid("00:30:44:22:8D:88", "KNOX", "Cradlepoint")?.first)
        assertEquals(KokohoAgency.FIRE, KokohoSignatures.matchOui("00:30:44:22:8D:88", "KNOX")?.agencyEnum)
        assertEquals(KokohoAgency.FIRE, KokohoSignatures.matchOui("00:30:44:22:8D:98", "TFDCP")?.agencyEnum)
        assertFalse(KokohoSignatures.isGenericUnmarkedFleetRouter("00:30:44:22:8D:88", "KNOX"))

        // Commercial / FWA / Fixed-Branch SSID exclusions in both KokohoSignatures and PatrolCluster (while rugged mobile R920/R980 remain enabled!)
        for (ssid in listOf("GoEnergy", "H&R Block IoT", "RAI-SIGNAGE", "AISMobile-04", "E100-a1b2", "Verizon-M3100-77")) {
            assertNull("Expected $ssid to be suppressed", KokohoSignatures.matchOuiOrSsid("00:30:44:33:44:55", ssid, "Cradlepoint"))
            assertNull("Expected $ssid to be excluded from PatrolCluster roles",
                org.tucsoncommunity.kokoho.detect.PatrolCluster.roleOf(emptyList(), "Cradlepoint", ssid))
        }
        assertNotNull(
            "Cradlepoint R920 mobile patrol router must match POLICE/CBP",
            KokohoSignatures.matchOuiOrSsid("00:30:44:6B:5F:79", "R920-f77-5g", "Cradlepoint")
        )
        assertNotNull(
            "Cradlepoint R920 mobile patrol router must retain PatrolCluster vehicle router role",
            org.tucsoncommunity.kokoho.detect.PatrolCluster.roleOf(emptyList(), "Cradlepoint", "R920-f77-5g")
        )

        // Dual-band BSSIDs on ONE physical Peplink router (`:08` and `:0C` share group `D4:13:F8:0A:41:08`)
        // must NOT self-corroborate into a synthetic procurement block!
        val dayA = 86_400_000L * 30
        val dayB = 86_400_000L * 31
        for (mac in listOf("D4:13:F8:0A:41:08", "D4:13:F8:0A:41:0C")) {
            for (ts in listOf(dayA, dayA + 60_000L, dayB)) {
                KokohoSignatures.recordLocalMacCorroboration(
                    context = ctx,
                    mac = mac,
                    agency = KokohoAgency.DHS_HSI,
                    label = "Peplink Router",
                    isCorroborated = true,
                    nowMs = ts
                )
            }
        }
        assertNull(
            "Dual-band :08 and :0C on a single physical router must NOT synthesize a MAC block",
            KokohoSignatures.lookupAssignedMacBlock(ctx, "D4:13:F8:0A:41:10")
        )

        org.tucsoncommunity.kokoho.service.ScanForegroundService.setLastFixForTest(null)
        KokohoPostureEngine.clearLocalState()
        KokohoHubClient.clearCachedObjects()
        org.tucsoncommunity.kokoho.service.DeviceRegistry.clear()
        RadioLog.clear()
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.DHS_AGENCIES.toSet())
    }

    @Test
    fun v122IngestedReferenceLookupTablesWork() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KokohoSignatures.ensureLoaded(ctx)
        KokohoReferenceTables.ensureLoaded(ctx)
        val leRegistry = org.tucsoncommunity.kokoho.online.PoliceAircraft.load(ctx)

        // 1. Verify all compiled offline reference tables loaded cleanly with full production counts
        assertTrue("Expected > 200k aircraft in aircraft_meta.tsv.gz", KokohoReferenceTables.aircraftMetaCount() > 200_000)
        assertTrue("Expected > 900 airfields in az_airfields.tsv", KokohoReferenceTables.airfieldCount() > 900)
        assertTrue("Expected > 5,000 AZ FCC + NTIA + RR + NIFOG channels in az_fcc_rf.tsv.gz", KokohoReferenceTables.azRfCount() > 5_000)
        assertTrue("Expected > 160,000 AZ & Sonora cells in az_cells.tsv.gz", KokohoReferenceTables.azCellCount() > 160_000)
        assertTrue("Expected > 1,500 AZ P25 talkgroups in az_talkgroups.tsv.gz", KokohoReferenceTables.azTalkgroupCount() > 1_500)
        assertTrue("Expected > 100 surveyed AZ cells in fieldSurveyCells", KokohoReferenceTables.fieldSurveyCells().size > 100)
        assertTrue("Expected > 2,500 verified LE/DHS/shell aircraft in law_enforcement.tsv", leRegistry.size > 2_500)

        // 2. Offline Mode-S enrichment (`a25082` -> `N2483K`, `PA38`, `'F'`, `"TRN"`)
        val n2483kRef = KokohoReferenceTables.lookupAircraft("a25082")
        assertNotNull("Expected FAA Mode-S hex a25082 in aircraft_meta.tsv.gz", n2483kRef)
        assertEquals("N2483K", n2483kRef!!.registration)
        assertEquals("PA38", n2483kRef.icaoType)
        assertEquals('F', n2483kRef.airframeKind)
        assertEquals("TRN", n2483kRef.disposition)

        // Unenriched local RTL-SDR ADS-B frame with only `hex = "a25082"` is offline-enriched and suppressed
        val rawLocalAdsb = org.tucsoncommunity.kokoho.online.PoliceAircraft.Plane(
            hex = "a25082",
            callsign = null,
            registration = null,
            type = null,
            owner = null,
            lat = 32.1600,
            lon = -110.8800,
            altitudeFt = 4200,
            speedKt = 88.0,
            trackDeg = 180.0,
            military = false
        )
        val enriched = org.tucsoncommunity.kokoho.online.PoliceAircraft.enrichPlane(rawLocalAdsb)
        assertEquals("N2483K", enriched.registration)
        assertEquals("PA38", enriched.type)
        assertTrue(org.tucsoncommunity.kokoho.online.PoliceAircraft.isGliderOrExcludedTrainer(enriched))
        assertNull(
            KokohoSignatures.classifyAircraftAgency(
                owner = enriched.owner,
                registration = enriched.registration,
                callsign = enriched.callsign,
                hitLabel = org.tucsoncommunity.kokoho.online.PoliceAircraft.CIRCLING,
                type = enriched.type,
                hex = enriched.hex,
                speedKt = 88.0,
                isCircling = true
            )
        )

        // 3. OurAirports hospital helipad (`0AZ6` St Joseph's Hospital Heliport) & runway pattern suppression
        val hospMatch = KokohoReferenceTables.findMatchingAirfieldPattern(33.4830, -112.0793, 1800, isExplicitLeOrGov = false)
        assertNotNull("Hospital helipad orbit must match OurAirports hospital heliport", hospMatch)
        assertTrue(hospMatch!!.isHospitalHelipad)
        assertNull(
            "Explicit LE helicopter near a hospital helipad must NEVER be suppressed",
            KokohoReferenceTables.findMatchingAirfieldPattern(33.4830, -112.0793, 1800, isExplicitLeOrGov = true)
        )

        // 4. FCC ULS Arizona (`PS_REP` / `IG_COMM` suppression) & DHS NIFOG tactical/mobile matching
        val dpsRep = KokohoReferenceTables.lookupAzRf(460_425_000L)
        assertNotNull("Expected 460.425 MHz AZ DPS repeater in az_fcc_rf.tsv.gz", dpsRep)
        assertEquals("PS_REP", dpsRep!!.cls)
        assertTrue(dpsRep.isSuppressedCommercialOrRepeater)
        assertNull("Fixed repeater 460.425 MHz must not match proximity alerts by default", KokohoSignatures.matchFrequency(460_425_000L))
        assertNotNull("includeRepeaters=true exposes FCC ULS repeater metadata", KokohoSignatures.matchFrequency(460_425_000L, includeRepeaters = true))

        val nifogIn = KokohoSignatures.matchFrequency(800_143_750L)
        assertNotNull("Expected DHS NIFOG 7LAW61-IN mobile uplink to match", nifogIn)
        assertEquals("MOBILE_INPUT", nifogIn!!.role)
        assertEquals(KokohoAgency.POLICE, nifogIn.agencyEnum)

        val nifogFed = KokohoSignatures.matchFrequency(167_250_000L)
        assertNotNull("Expected DHS NIFOG FEDTAC-1 simplex to match", nifogFed)
        assertEquals(KokohoAgency.DHS_INTEROP, nifogFed!!.agencyEnum)

        // 5. IEEE 28-bit MA-M & 36-bit MA-S sub-block resolution
        val knoxSub = KokohoReferenceTables.lookupMacSubblock("40:ED:98:30:12:34")
        assertNotNull("Expected IEEE 28-bit MA-M 40:ED:98:3* Knox Company sub-block", knoxSub)
        assertEquals(28, knoxSub!!.bitLen)
        assertEquals(KokohoAgency.FIRE, knoxSub.agency)
        assertEquals(KokohoAgency.FIRE, KokohoSignatures.matchOui("40:ED:98:30:12:34")?.agencyEnum)
        assertTrue(KokohoSignatures.isRelevantBleOrWifiCandidate("40:ED:98:30:12:34", null, null, false, false))

        // Non-tactical 36-bit MA-S block sharing 8C:1F:64 (`8C:1F:64:AF:A` Data Electronic Devices) is cleanly excluded
        val civilSub = KokohoReferenceTables.lookupMacSubblock("8C:1F:64:AF:A0:01")
        assertNotNull(civilSub)
        assertEquals("CIVIL_SUBBLOCK", civilSub!!.role)
        assertNull(KokohoSignatures.matchOui("8C:1F:64:AF:A0:01"))

        // 6. RadioReference Arizona P25 Talkgroups (PCWIN + AZWINS) & NTIA Arizona Federal Channels
        assertEquals(KokohoAgency.SHERIFF, KokohoSignatures.matchTalkgroup(5002)?.agencyEnum) // PCWIN Interop PCSD
        assertEquals(KokohoAgency.DHS_INTEROP, KokohoSignatures.matchTalkgroup(65047)?.agencyEnum) // PCWIN USMS 65047
        assertNull("PCWIN Public Works talkgroup 100 must be suppressed", KokohoSignatures.matchTalkgroup(100))

        val cbpIn = KokohoSignatures.matchFrequency(165_337_500L)
        assertNotNull("Expected NTIA CBP VS14 mobile input 165.3375 MHz to match", cbpIn)
        assertEquals(KokohoAgency.DHS_CBP, cbpIn!!.agencyEnum)
        assertEquals("MOBILE_INPUT", cbpIn.role)

        assertNull("Fixed NTIA FPS Tucson repeater 409.400 MHz must not trigger spatial proximity alerts", KokohoSignatures.matchFrequency(409_400_000L))
        val fpsRep = KokohoSignatures.matchFrequency(409_400_000L, includeRepeaters = true)
        assertEquals("REPEATER", fpsRep?.role)
        assertEquals(KokohoAgency.DHS_FPS, fpsRep?.agencyEnum)

        // 7. OpenCelliD (310-314, 334) + MLS 162,000+ cell O(log N) lookup (FirstNet 313 & Mexico Sonora 334)
        val firstNetCell = KokohoReferenceTables.lookupAzCell("WCDMA", "313", "100", 6687427L)
        assertNotNull("Expected FirstNet 313-100 cell in az_cells.tsv.gz", firstNetCell)
        val sonoraCell = KokohoReferenceTables.lookupAzCell("GSM", "334", "00", 453L)
        assertNotNull("Expected Sonora Mexico 334 cell in az_cells.tsv.gz", sonoraCell)
    }

    private fun renderViewToPng(view: View, widthPx: Int, heightPx: Int, bgColor: Int, fileName: String) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)
        view.draw(canvas)
        saveBitmap(bitmap, fileName)
    }

    private fun saveBitmap(bitmap: Bitmap, fileName: String) {
        if (!artifactDir.exists()) artifactDir.mkdirs()
        val out = File(artifactDir, fileName)
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        assertTrue("Expected non-empty PNG at ${out.absolutePath}", out.exists() && out.length() > 1000)
    }
}
