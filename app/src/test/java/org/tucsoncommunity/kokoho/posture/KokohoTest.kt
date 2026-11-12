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
        assertFalse(KokohoAgency.DHS_INTEROP.defaultEnabled)
        assertTrue(KokohoAgency.DRONE.defaultEnabled)

        // Verify strict priority order: ICE (1) < DHS Air (2) < CBP (3) < HSI (4) < FPS/Interop (5) < Sheriff/TPD/Drone (6)
        assertTrue(KokohoAgency.DHS_ICE.priorityRank < KokohoAgency.DHS_AMO.priorityRank)
        assertTrue(KokohoAgency.DHS_AMO.priorityRank < KokohoAgency.DHS_CBP.priorityRank)
        assertTrue(KokohoAgency.DHS_CBP.priorityRank < KokohoAgency.DHS_HSI.priorityRank)
        assertTrue(KokohoAgency.DHS_HSI.priorityRank < KokohoAgency.DHS_INTEROP.priorityRank)
        assertTrue(KokohoAgency.DHS_INTEROP.priorityRank < KokohoAgency.SHERIFF.priorityRank)

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

        // Verify top-level tabs are Monitor, Map, Utility (no List tab)
        assertEquals(listOf("Monitor", "Map", "Utility"), KokohoViews.Tab.values().map { it.title })

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

        // Verify Scan / Stop toggle button transitions properly
        views.syncRunningState(false)
        assertEquals("Scan", views.scanToggleButton.text.toString())
        views.syncRunningState(true)
        assertEquals("Stop", views.scanToggleButton.text.toString())

        // Verify Share binary pill starts OFF ("Share"), toggles to "Sharing" on tap, and greys out when disabled in Settings
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertFalse(KokohoSignatures.isContributeObservationsEnabled(activity))
        views.shareToggleButton.performClick()
        assertEquals("Sharing", views.shareToggleButton.text.toString())
        assertTrue(KokohoSignatures.isContributeObservationsEnabled(activity))
        views.shareToggleButton.performClick()
        assertEquals("Share", views.shareToggleButton.text.toString())
        assertFalse(KokohoSignatures.isContributeObservationsEnabled(activity))
        KokohoSignatures.setContributionAllowedInSettings(activity, false)
        views.syncSharePillState()
        assertEquals("Share Off", views.shareToggleButton.text.toString())
        assertEquals(0.50f, views.shareToggleButton.alpha, 0.01f)
        KokohoSignatures.setContributionAllowedInSettings(activity, true)
        views.syncSharePillState()
        assertEquals("Share", views.shareToggleButton.text.toString())

        // Verify intersection "rmat" traffic cabinets and retail routers are excluded while 7400 Oracle signals match
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:55:00:06", "rmat", "Cradlepoint"))
        assertNull(KokohoSignatures.matchOuiOrSsid("00:30:44:9B:BE:48", "Chill N NW", "Cradlepoint"))
        assertNotNull(KokohoSignatures.matchOuiOrSsid("D4:13:F8:7F:C6:49", null, "Peplink"))
        assertNotNull(KokohoSignatures.matchCloseRangeFederalBand(167_862_500L, 42))

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

        // 5. UTILITY tab screenshot (Logs inside Utility, stable status color background)
        views.selectTab(KokohoViews.Tab.UTILITY)
        renderViewToPng(binding.root, 1080, 2160, PostureColor.RED.colorArgb, "kokoho_sandbox_utility_tab.png")

        // 6. Entity Details Activity (pencil -> checkmark inline classification edit, 1/4 screen map, coords copy, evidence)
        val detailIntent = android.content.Intent(activity, org.tucsoncommunity.kokoho.ui.DeviceDetailActivity::class.java)
            .putExtra(org.tucsoncommunity.kokoho.ui.DeviceDetailActivity.EXTRA_MAC, "kokoho:bf38689d-a910-50a6-93d4-e4c17a90a214")
        val detailController = Robolectric.buildActivity(org.tucsoncommunity.kokoho.ui.DeviceDetailActivity::class.java, detailIntent).setup()
        val detailAct = detailController.get()
        assertEquals("CBP", detailAct.classificationText.text.toString())
        assertFalse(detailAct.isEditingClassification)
        renderViewToPng(
            detailAct.findViewById(android.R.id.content),
            1080,
            2160,
            PostureColor.RED.colorArgb,
            "kokoho_sandbox_entity_details.png"
        )
        // Click pencil -> switches to inline edit mode with checkmark
        detailAct.editCheckButton.performClick()
        assertTrue(detailAct.isEditingClassification)
        detailAct.classificationEdit.setText("CBP Sector Unit 4")
        // Click checkmark -> saves locally and returns to display mode
        detailAct.editCheckButton.performClick()
        assertFalse(detailAct.isEditingClassification)
        assertEquals("CBP Sector Unit 4", detailAct.classificationText.text.toString())
        assertEquals("CBP Sector Unit 4", KokohoSignatures.getCustomEntityLabel(activity, "bf38689d-a910-50a6-93d4-e4c17a90a214"))
        // Reset custom label
        KokohoSignatures.setCustomEntityLabel(activity, "bf38689d-a910-50a6-93d4-e4c17a90a214", null)
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

        // Strict priority rank order: ICE < DHS Air < CBP < HSI < Other Fed < Sheriff/TPD
        assertTrue(KokohoAgency.DHS_ICE.priorityRank < KokohoAgency.DHS_AMO.priorityRank)
        assertTrue(KokohoAgency.DHS_AMO.priorityRank < KokohoAgency.DHS_CBP.priorityRank)
        assertTrue(KokohoAgency.DHS_CBP.priorityRank < KokohoAgency.DHS_HSI.priorityRank)
        assertTrue(KokohoAgency.DHS_HSI.priorityRank < KokohoAgency.DHS_INTEROP.priorityRank)
        assertTrue(KokohoAgency.DHS_INTEROP.priorityRank < KokohoAgency.SHERIFF.priorityRank)
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
