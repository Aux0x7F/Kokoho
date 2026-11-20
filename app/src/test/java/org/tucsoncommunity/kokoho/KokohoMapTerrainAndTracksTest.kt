package org.tucsoncommunity.kokoho

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tucsoncommunity.kokoho.online.PoliceAircraft
import org.tucsoncommunity.kokoho.posture.KokohoHubClient
import org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapTimeWindow
import org.tucsoncommunity.kokoho.ui.Airborne3dOverlayView
import org.tucsoncommunity.kokoho.ui.TerrainElevationSampler
import org.tucsoncommunity.kokoho.util.Prefs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class KokohoMapTerrainAndTracksTest {

    @After
    fun tearDown() {
        TerrainElevationSampler.clearCacheForTest()
        PoliceAircraft.clearTrailsForTest()
    }

    @Test
    fun terrariumDecodingAndTrueMslTucsonElevationNeverReturnNegative() {
        // Sea level in Terrarium: (128 * 256 + 0 + 0) - 32768 = 0.0 m MSL
        assertEquals(0.0, TerrainElevationSampler.decodeTerrariumMeters(128, 0, 0), 0.001)
        // ~736 m MSL (Downtown Tucson): 32768 + 736 = 33504 -> R=130, G=224, B=0
        assertEquals(736.0, TerrainElevationSampler.decodeTerrariumMeters(130, 224, 0), 0.001)
        // ~2,791 m MSL (Mt. Lemmon): 32768 + 2791 = 35559 -> R=138, G=231, B=0
        assertEquals(2791.0, TerrainElevationSampler.decodeTerrariumMeters(138, 231, 0), 0.001)

        // Regional true-MSL fallback across Tucson, KTUS (~805 m), Foothills (~850+ m), and Mt. Lemmon (> 2,500 m)
        val downtownM = TerrainElevationSampler.estimateRegionalElevationM(32.2226, -110.9747)
        val ktusM = TerrainElevationSampler.estimateRegionalElevationM(32.1161, -110.9410)
        val foothillsM = TerrainElevationSampler.estimateRegionalElevationM(32.3200, -110.8900)
        val lemmonM = TerrainElevationSampler.estimateRegionalElevationM(32.4430, -110.7880)
        val maranaM = TerrainElevationSampler.estimateRegionalElevationM(32.4367, -111.2224)

        assertTrue("Downtown Tucson should be ~720-780 m MSL, got $downtownM", downtownM in 700.0..790.0)
        assertTrue("KTUS should be ~770-850 m MSL, got $ktusM", ktusM in 760.0..860.0)
        assertTrue("Catalina Foothills should exceed Downtown Tucson, got $foothillsM", foothillsM > downtownM + 60.0)
        assertTrue("Mt. Lemmon should exceed 2,500 m MSL, got $lemmonM", lemmonM > 2500.0)
        assertTrue("Marana basin must be positive true MSL, got $maranaM", maranaM > 500.0)

        // Cached Terrarium tile overrides regional estimate with exact bilinear interpolation
        val tc = TerrainElevationSampler.tileCoords(32.2226, -110.9747, TerrainElevationSampler.TERRAIN_SAMPLE_ZOOM)
        val grid = FloatArray(256 * 256) { 812.5f }
        TerrainElevationSampler.putTileForTest(tc.zoom, tc.tileX, tc.tileY, grid)
        val sampled = TerrainElevationSampler.sampleElevationM(null, 32.2226, -110.9747, allowAsyncFetch = false)
        assertEquals(812.5, sampled, 0.05)
    }

    @Test
    fun cameraOrbitElevationCompensationRaisesCameraSmoothlyWithoutSlammingOrWildJumps() {
        val baseZoom = 14.5
        val zoomUpMountain = TerrainElevationSampler.computeZoomForElevationDelta(
            lat = 32.4430,
            currentZoom = baseZoom,
            deltaElevM = 1950.0,
            viewportHeightPx = 1920
        )
        val zoomDownValley = TerrainElevationSampler.computeZoomForElevationDelta(
            lat = 32.2226,
            currentZoom = zoomUpMountain,
            deltaElevM = -1950.0,
            viewportHeightPx = 1920
        )
        // Positive ground shift raises camera eye distance (lower zoom number), bounded smoothly within 1.6 levels
        assertTrue("Climbing 1,950 m should pull zoom back smoothly, got $zoomUpMountain", zoomUpMountain < baseZoom)
        assertTrue("Zoom compensation must be clamped against wild jumps, got $zoomUpMountain", baseZoom - zoomUpMountain <= 1.61)
        assertTrue("Descending back to valley should zoom back in, got $zoomDownValley", zoomDownValley > zoomUpMountain)
    }

    @Test
    fun airborne3dPuckHeightAndProjectionNeverRenderUndergroundAndRespectPitchThreshold() {
        val foothillsGroundM = 920.0
        val lemmonGroundM = 2790.0

        // ADS-B helicopter at 4,200 ft MSL (1,280.16 m) over Foothills (920 m) -> ~360 m AGL
        val heliAglM = TerrainElevationSampler.computeAirborneHeightAboveGroundM(
            isAircraft = true,
            altitudeFtRaw = 4200,
            groundElevM = foothillsGroundM
        )
        assertEquals(1280.16 - 920.0, heliAglM, 0.5)

        // ADS-B aircraft whose barometric MSL is below Mt. Lemmon summit still clamps >= MIN_AIRBORNE_AGL_M
        val mountainSafeAglM = TerrainElevationSampler.computeAirborneHeightAboveGroundM(
            isAircraft = true,
            altitudeFtRaw = 8500,
            groundElevM = lemmonGroundM
        )
        assertTrue("Aircraft must never render underground on mountain terrain", mountainSafeAglM >= TerrainElevationSampler.MIN_AIRBORNE_AGL_M)

        // Remote ID drone at 220 ft AGL -> ~67 m AGL; WGS84 2,750 ft MSL over Tucson (740 m) -> ~98 m AGL
        val droneAglM = TerrainElevationSampler.computeAirborneHeightAboveGroundM(
            isAircraft = false,
            altitudeFtRaw = 220,
            groundElevM = 740.0
        )
        assertEquals(220 * 0.3048, droneAglM, 0.2)
        val droneGeoMslConverted = TerrainElevationSampler.computeAirborneHeightAboveGroundM(
            isAircraft = false,
            altitudeFtRaw = 2750,
            groundElevM = 740.0
        )
        assertEquals(2750 * 0.3048 - 740.0, droneGeoMslConverted, 0.5)

        // Screen projection stays flat at pitch <= 6 deg and lifts upward at pitch > 6 deg
        val flatProj = Airborne3dOverlayView.projectElevatedPoint3d(
            groundX = 540f,
            groundY = 960f,
            viewportWidthPx = 1080,
            viewportHeightPx = 1920,
            topPaddingPx = 0.0,
            cameraTiltDeg = 5.0,
            metersPerPixelAtTarget = 4.0,
            heightAboveGroundM = 350.0
        )
        assertEquals(0f, flatProj.liftPx, 0.001f)
        assertEquals(960f, flatProj.airY, 0.001f)

        val pitchedProj = Airborne3dOverlayView.projectElevatedPoint3d(
            groundX = 540f,
            groundY = 960f,
            viewportWidthPx = 1080,
            viewportHeightPx = 1920,
            topPaddingPx = 0.0,
            cameraTiltDeg = 48.0,
            metersPerPixelAtTarget = 4.0,
            heightAboveGroundM = 350.0
        )
        assertTrue("Pitched 3D airborne puck must lift above ground shadow, got airY=${pitchedProj.airY}", pitchedProj.airY < 920f)
        assertTrue(pitchedProj.liftPx > 40f)
    }

    @Test
    fun flightTracksAndLocalTrailsStrictlyFilterAndVertexClipToSelectedMapTimeWindow() {
        val nowMs = 1_770_000_000_000L
        val minMs = 60_000L

        // 1. Flight from 2 hours ago ([now - 135m, now - 105m]): hidden in 30m, shown in 3h / 24h / 1wk / 1mo
        val twoHoursAgoTrack = KokohoHubClient.RemoteFlightTrack(
            trackUuid = "tr-2h",
            entityUuid = "ent-amo-1",
            category = "DHS_AMO",
            label = "CBP Air Helicopter",
            startedAtMs = nowMs - 135 * minMs,
            endedAtMs = nowMs - 105 * minMs,
            simplifiedPolyline = listOf(
                listOf(32.2000, -110.9800),
                listOf(32.2200, -110.9600),
                listOf(32.2400, -110.9400)
            )
        )
        assertTrue(twoHoursAgoTrack.clippedPointsForWindow(nowMs, MapTimeWindow.MIN_30.windowMs).isEmpty())
        assertEquals(3, twoHoursAgoTrack.clippedPointsForWindow(nowMs, MapTimeWindow.HOURS_3.windowMs).size)
        assertEquals(3, twoHoursAgoTrack.clippedPointsForWindow(nowMs, MapTimeWindow.HOURS_24.windowMs).size)
        assertEquals(3, twoHoursAgoTrack.clippedPointsForWindow(nowMs, MapTimeWindow.WEEK_1.windowMs).size)
        assertEquals(3, twoHoursAgoTrack.clippedPointsForWindow(nowMs, MapTimeWindow.MONTH_1.windowMs).size)

        // 2. Active 60-minute flight ([now - 60m, now]): vertex-clipped to recent half in 30m, full in 3h
        val straddlingTrack = KokohoHubClient.RemoteFlightTrack(
            trackUuid = "tr-straddle",
            entityUuid = "ent-tpd-1",
            category = "POLICE",
            label = "TPD Air Support",
            startedAtMs = nowMs - 60 * minMs,
            endedAtMs = nowMs,
            simplifiedPolyline = listOf(
                listOf(32.1000, -110.9500),
                listOf(32.2000, -110.9500),
                listOf(32.3000, -110.9500)
            )
        )
        val clipped30m = straddlingTrack.clippedPointsForWindow(nowMs, MapTimeWindow.MIN_30.windowMs)
        assertTrue(clipped30m.size >= 2)
        // First vertex must be clipped near midpoint lat ~32.2000, not start lat 32.1000
        assertEquals(32.2000, clipped30m.first().first, 0.002)
        assertEquals(32.3000, clipped30m.last().first, 0.0001)
        val full3h = straddlingTrack.clippedPointsForWindow(nowMs, MapTimeWindow.HOURS_3.windowMs)
        assertEquals(32.1000, full3h.first().first, 0.0001)
        assertEquals(32.3000, full3h.last().first, 0.0001)

        // 3. Local ADS-B & pinpoint drone trails retain 24h non-destructively when toggling 30m <-> 3h <-> 24h
        PoliceAircraft.recordAirborneFix("A1B2C3", 32.2100, -110.9700, nowMs - 130 * minMs)
        PoliceAircraft.recordAirborneFix("A1B2C3", 32.2250, -110.9550, nowMs - 120 * minMs)
        PoliceAircraft.recordPinpointDroneFix("uas-1", "DRONE", "Autel Drone", 32.2200, -110.9700, nowMs - 125 * minMs)
        PoliceAircraft.recordPinpointDroneFix("uas-1", "DRONE", "Autel Drone", 32.2220, -110.9680, nowMs - 118 * minMs)

        assertTrue(PoliceAircraft.recentAirborneTrail("A1B2C3", nowMs, MapTimeWindow.MIN_30.windowMs, includeLandedWithinWindow = true).isEmpty())
        assertTrue(PoliceAircraft.recentDroneTrailsForWindow(nowMs, MapTimeWindow.MIN_30.windowMs).isEmpty())

        // Switching back up to 3h must still have the 2h-old fixes intact
        assertEquals(2, PoliceAircraft.recentAirborneTrail("A1B2C3", nowMs, MapTimeWindow.HOURS_3.windowMs, includeLandedWithinWindow = true).size)
        assertEquals(1, PoliceAircraft.recentDroneTrailsForWindow(nowMs, MapTimeWindow.HOURS_3.windowMs).size)
    }

    @Test
    fun mapBuildings3dAndTerrainPrefsPersistCleanly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(Prefs.mapBuildings3d(ctx))
        assertFalse(Prefs.mapTerrain(ctx))
        Prefs.setMapBuildings3d(ctx, true)
        Prefs.setMapTerrain(ctx, true)
        assertTrue(Prefs.mapBuildings3d(ctx))
        assertTrue(Prefs.mapTerrain(ctx))
        Prefs.setMapBuildings3d(ctx, false)
        Prefs.setMapTerrain(ctx, false)
        assertFalse(Prefs.mapBuildings3d(ctx))
        assertFalse(Prefs.mapTerrain(ctx))
    }

    @Test
    fun rfMapTapOnFlightTrackSelectsAircraftAndWindowSwitchUpdatesFlightTracksImmediately() {
        val act = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).setup().get()
        val sourceData = mutableMapOf<String, String>()
        val addedSources = mutableSetOf<String>()
        var simulatedTrackHitUuid: String? = null
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
            override val camera = org.tucsoncommunity.kokoho.ui.MapEngine.Camera(32.2226, -110.9747, 12.5, 0.0, 0.0)
            override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {}
            override fun zoomTo(zoom: Double) {}
            override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {}
            override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {}
            override fun visibleBox() = org.tucsoncommunity.kokoho.ui.MapEngine.Box(32.35, -110.75, 32.10, -111.05)
            override fun toLatLon(x: Float, y: Float) = 32.2226 to -110.9747
            override fun setMinZoom(zoom: Double) {}
            override var onCameraMove: (() -> Unit)? = null
            override var onIdle: (() -> Unit)? = null
            override var onClick: ((Float, Float) -> Unit)? = null
            override var onLongClick: ((Double, Double) -> Unit)? = null
            override var onUserGesture: (() -> Unit)? = null
            override fun query(box: android.graphics.RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit) {
                result(layers.map { (layer, _) ->
                    if (layer == "rf-flight-tracks" && simulatedTrackHitUuid != null) listOf(simulatedTrackHitUuid!!) else emptyList()
                })
            }
            override fun setMarker(bitmap: android.graphics.Bitmap?) {}
            override val draws3dMarker = false
            override fun setMarker3d(model: java.io.File?, size: Float) {}
            override fun startPuck() = true
            override val puckOn = true
            override fun pushFix(fix: android.location.Location) {}
            override fun follow(mode: org.tucsoncommunity.kokoho.ui.MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {}
            override var onFollowDismissed: (() -> Unit)? = null
            override fun placeBranding(topPx: Int, atEnd: Boolean) {}
        }

        val nowMs = System.currentTimeMillis()
        PoliceAircraft.recordAirborneFix("AC9911", 32.2100, -110.9800, nowMs - 20_000L)
        PoliceAircraft.recordAirborneFix("AC9911", 32.2300, -110.9600, nowMs - 5_000L)

        val airThreat = org.tucsoncommunity.kokoho.posture.EvaluatedThreat(
            uuid = "air-uuid-9911",
            trackUuid = "air-uuid-9911",
            agency = org.tucsoncommunity.kokoho.posture.KokohoAgency.DHS_AMO,
            label = "CBP Air",
            posture = org.tucsoncommunity.kokoho.posture.PostureColor.ORANGE,
            overallConfidence = 95,
            classificationConfidence = 96,
            spatialConfidence = 94,
            estimatedDistanceM = 1200,
            uncertaintyRadiusM = 25,
            bearingDeg = 45,
            closingVelocityMps = null,
            recurrenceSummary = "CBP Air AS350 (N123AB)",
            evidenceSources = emptyList(),
            updatedAtMs = nowMs - 5_000L,
            lat = 32.2300,
            lon = -110.9600,
            isApproximatedPosition = true,
            isPinpointed = true,
            isAircraft = true,
            isHelicopter = true,
            aircraftHex = "AC9911",
            altitudeFtAgl = 3800
        )
        val snap = org.tucsoncommunity.kokoho.posture.PostureSnapshot(
            color = org.tucsoncommunity.kokoho.posture.PostureColor.ORANGE,
            headline = "Elevated",
            subheadline = "",
            distanceAndBearingText = "",
            bottomConfidenceText = "",
            primaryThreat = airThreat,
            activeThreats = listOf(airThreat),
            monitorGroups = emptyList(),
            mapThreats = listOf(airThreat),
            enabledScopeText = ""
        )

        val rfMap = org.tucsoncommunity.kokoho.ui.RfMap(act, fakeEngine)
        var selected: org.tucsoncommunity.kokoho.ui.RfMap.MapSelection? = null
        rfMap.onSelectTarget = { selected = it }
        rfMap.loadStyle(dark = true) {}

        org.tucsoncommunity.kokoho.posture.KokohoSignatures.setMapTimeWindow(act, MapTimeWindow.MIN_30)
        rfMap.drawLive(emptyList(), snap)
        val tracksJson = sourceData["rf-flight-tracks"].orEmpty()
        assertTrue("rf-flight-tracks must encode entity hex property for hit testing", tracksJson.contains("\"hex\":\"air-uuid-9911\""))

        // Tapping the flight track line selects the aircraft for details
        simulatedTrackHitUuid = "air-uuid-9911"
        rfMap.onTap(200f, 200f, null)
        val det = selected as? org.tucsoncommunity.kokoho.ui.RfMap.MapSelection.Detection
        assertEquals("air-uuid-9911", det?.entityUuid)
        assertEquals("DHS_AMO", det?.agencyCode)

        // Switching to Heat immediately clears flight tracks
        org.tucsoncommunity.kokoho.posture.KokohoSignatures.setMapTimeWindow(act, MapTimeWindow.HEAT)
        rfMap.onTimeWindowChanged(MapTimeWindow.HEAT)
        rfMap.drawLive(emptyList(), snap)
        assertTrue("Heat window must clear rf-flight-tracks", sourceData["rf-flight-tracks"].orEmpty().contains("\"features\":[]"))
        org.tucsoncommunity.kokoho.posture.KokohoSignatures.setMapTimeWindow(act, MapTimeWindow.MIN_30)
    }

    @Test
    fun unpinpointedDroneAirspaceDomeCappedAt400FtRendersAndHitTestsIn3dAndCollapsesAtLowPitch() {
        // 1. Physics: R <= 121.92 m -> full hemisphere peaking at R; R > 121.92 m -> capped at 121.92 m (400 ft AGL)
        assertEquals(80.0, TerrainElevationSampler.droneDomeCeilingHeightM(80.0), 0.001)
        assertEquals(80.0, TerrainElevationSampler.droneDomeSliceRadiusM(80.0, 0.0), 0.001)
        assertEquals(0.0, TerrainElevationSampler.droneDomeSliceRadiusM(80.0, 80.0), 0.001)

        assertEquals(121.92, TerrainElevationSampler.droneDomeCeilingHeightM(250.0), 0.001)
        assertEquals(250.0, TerrainElevationSampler.droneDomeSliceRadiusM(250.0, 0.0), 0.001)
        val expectedCapR = kotlin.math.sqrt(250.0 * 250.0 - 121.92 * 121.92)
        assertEquals(expectedCapR, TerrainElevationSampler.droneDomeSliceRadiusM(250.0, 121.92), 0.01)

        // 2. End-to-end RfMap + Airborne3dOverlayView across pitch <= 4 deg and pitch > 4 deg
        val act = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).setup().get()
        val layerVisibility = mutableMapOf<String, Boolean>()
        val addedSources = mutableSetOf<String>()
        var currentTilt = 0.0
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
            override fun setData(source: String, geoJson: String) {}
            override fun addLayer(json: org.json.JSONObject) {}
            override fun setPaint(layer: String, name: String, value: String) {}
            override fun setLayerVisible(layer: String, visible: Boolean) { layerVisibility[layer] = visible }
            override val camera get() = org.tucsoncommunity.kokoho.ui.MapEngine.Camera(32.2226, -110.9747, 15.0, 0.0, currentTilt)
            override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {}
            override fun zoomTo(zoom: Double) {}
            override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {}
            override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {}
            override fun visibleBox() = org.tucsoncommunity.kokoho.ui.MapEngine.Box(32.25, -110.94, 32.20, -111.00)
            override fun toLatLon(x: Float, y: Float) = 32.2226 to -110.9747
            override fun projectScreen(lat: Double, lon: Double): android.graphics.PointF {
                val dx = ((lon - (-110.9747)) * 93000.0 / 2.5).toFloat()
                val dy = -((lat - 32.2226) * 111000.0 / 2.5).toFloat()
                return android.graphics.PointF(540f + dx, 960f + dy)
            }
            override fun metersPerPixelAtLat(lat: Double): Double = 2.5
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
            override fun setMarker3d(model: java.io.File?, size: Float) {}
            override fun startPuck() = true
            override val puckOn = true
            override fun pushFix(fix: android.location.Location) {}
            override fun follow(mode: org.tucsoncommunity.kokoho.ui.MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {}
            override var onFollowDismissed: (() -> Unit)? = null
            override fun placeBranding(topPx: Int, atEnd: Boolean) {}
        }

        val nowMs = System.currentTimeMillis()
        val unpinpointedDrone = org.tucsoncommunity.kokoho.posture.EvaluatedThreat(
            uuid = "uas-oui-dome-1",
            trackUuid = "uas-oui-dome-1",
            agency = org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE,
            label = "Drone",
            posture = org.tucsoncommunity.kokoho.posture.PostureColor.ORANGE,
            overallConfidence = 88,
            classificationConfidence = 92,
            spatialConfidence = 70,
            estimatedDistanceM = 200,
            uncertaintyRadiusM = 200,
            bearingDeg = null,
            closingVelocityMps = null,
            recurrenceSummary = "Tactical Drone Beacon",
            evidenceSources = listOf(
                org.tucsoncommunity.kokoho.posture.EvidenceBadge("TACTICAL_DRONE_OUI", "uas:mac:60:60:1F:AA:BB:CC", "DJI sUAS", 0.9, nowMs)
            ),
            updatedAtMs = nowMs,
            lat = 32.2226,
            lon = -110.9747,
            isApproximatedPosition = false,
            isPinpointed = false,
            isDrone = true
        )
        val snap = org.tucsoncommunity.kokoho.posture.PostureSnapshot(
            color = org.tucsoncommunity.kokoho.posture.PostureColor.ORANGE,
            headline = "Elevated",
            subheadline = "",
            distanceAndBearingText = "",
            bottomConfidenceText = "",
            primaryThreat = unpinpointedDrone,
            activeThreats = listOf(unpinpointedDrone),
            monitorGroups = emptyList(),
            mapThreats = listOf(unpinpointedDrone),
            enabledScopeText = ""
        )

        val overlay = Airborne3dOverlayView(act)
        overlay.layout(0, 0, 1080, 1920)
        val rfMap = org.tucsoncommunity.kokoho.ui.RfMap(act, fakeEngine)
        var selected: org.tucsoncommunity.kokoho.ui.RfMap.MapSelection? = null
        rfMap.onSelectTarget = { selected = it }
        rfMap.attachAirborneOverlay(overlay)
        rfMap.loadStyle(dark = true) {}

        // At pitch = 3.0 deg (<= 4 deg), 2D circle stays visible and 3D dome overlay stays hidden
        currentTilt = 3.0
        rfMap.drawLive(emptyList(), snap)
        assertEquals(android.view.View.GONE, overlay.visibility)
        assertEquals(true, layerVisibility["rf-drone-dome-2d-fill"])

        // At pitch = 42.0 deg (> 4 deg), 2D circle hides and 3D capped dome renders + hit-tests cleanly
        currentTilt = 42.0
        rfMap.syncAirbornePitchState(0.0)
        assertEquals(android.view.View.VISIBLE, overlay.visibility)
        assertEquals(false, layerVisibility["rf-drone-dome-2d-fill"])

        val canvasBmp = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
        overlay.draw(android.graphics.Canvas(canvasBmp))

        // Tapping inside the 3D airspace dome selects the unpinpointed drone detection
        rfMap.onTap(540f, 945f, null)
        val det = selected as? org.tucsoncommunity.kokoho.ui.RfMap.MapSelection.Detection
        assertEquals("uas-oui-dome-1", det?.entityUuid)
        assertEquals("DRONE", det?.agencyCode)
    }
}
