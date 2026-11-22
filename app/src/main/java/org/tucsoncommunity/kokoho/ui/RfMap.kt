package org.tucsoncommunity.kokoho.ui

import android.graphics.RectF
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import org.tucsoncommunity.kokoho.alpr.AlprStore
import org.tucsoncommunity.kokoho.alpr.Cctv
import org.tucsoncommunity.kokoho.alpr.CctvStore
import org.tucsoncommunity.kokoho.alpr.KnownCamera
import org.tucsoncommunity.kokoho.data.ProbeLog
import org.tucsoncommunity.kokoho.online.PoliceAircraft
import org.tucsoncommunity.kokoho.online.WazePolice
import org.tucsoncommunity.kokoho.online.WiglePins
import org.tucsoncommunity.kokoho.service.CellTowerStore
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.service.TripRecorder
import org.tucsoncommunity.kokoho.util.Prefs
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kokoho's map on its [MapEngine] (OpenFreeMap or Mapbox): the light or night style, and every
 * layer drawn on it - devices and their pinpoint circles, plate / speed / red-light cameras, CCTV
 * with their view, cell towers, drones, police aircraft, Waze reports and the trace - with the same
 * details on a tap as before. The screen around it ([MapFragment]) owns the buttons, the camera
 * and your position; this owns what is drawn.
 */
internal class RfMap(private val activity: AppCompatActivity, val map: MapEngine) {

    companion object {
        const val TRACE_COLOR = "#E0622D"
        const val PAST_TRACE_COLOR = "#1F5FBF"
        private const val DRONE_COLOR = "#1F5FBF"
        /** Vector zoom levels sit ~1 below the old raster map's for the same scale (512-pixel tiles). */
        const val CAMERAS_MIN_ZOOM = 8.0
        private const val CCTV_MIN_ZOOM = 11.0
        private const val CCTV_CONE_ZOOM = 14.0
        /** Plate, speed and red-light cameras' facing cones show from here (a street's scale). */
        private const val CAMERA_CONE_ZOOM = 13.0
        /** Drawn view of a road camera: about how far a plate reader reads, and its usual width. */
        private const val CAMERA_CONE_M = 60.0
        private const val CAMERA_CONE_DEG = 40.0

        private const val PRECISION = "rf-precision"; private const val CONES = "rf-cctv-cones"
        private const val DRONE_DOME_2D = "rf-drone-dome-2d"
        private const val CAM_CONES = "rf-camera-cones"
        private const val HEAT = "rf-kokoho-heat"
        private const val FLIGHT_TRACKS = "rf-flight-tracks"
        private const val TRACE = "rf-trace"; private const val DRONE_LINES = "rf-drone-lines"
        private const val TOWERS = "rf-towers"; private const val CCTV = "rf-cctv"; private const val CAMERAS = "rf-cameras"
        private const val DEVICES = "rf-devices"; private const val DOTS = "rf-device-dots"; private const val LABELS = "rf-device-labels"
        private const val LOCATIONS = "rf-kokoho-locations"; private const val LOCATION_LABELS = "rf-kokoho-location-labels"
        private const val DROPPED_POINT = "rf-dropped-point"; private const val SELECTED_PIN = "rf-selected-pin"
        private const val WAZE = "rf-waze"; private const val DRONE_OPS = "rf-drone-ops"; private const val DRONES = "rf-drones"
        private const val AIRCRAFT = "rf-aircraft"
        private const val PINGS = "rf-pings"
        private const val WIGLE = "rf-wigle"; private const val WIGLE_COLOR = "#8E24AA"
        private const val ASKED = "rf-asked"; private const val ASKED_COLOR = "#00897B"
        private const val NAV_ROUTES = "rf-nav-routes"; private const val NAV_PLACES = "rf-nav-places"
        private const val NAV_ROUTE_COLOR = "#1A73E8"; private const val NAV_ALT_COLOR = "#7C8DA6"

        /**
         * North, east, south, west of a box grown by [fraction] on every side, clamped to valid
         * latitudes and longitudes (a view of the whole world would otherwise pass the poles).
         */
        internal fun grownBox(north: Double, east: Double, south: Double, west: Double, fraction: Double): List<Double> {
            val dLat = (north - south) * fraction; val dLon = (east - west) * fraction
            return listOf((north + dLat).coerceIn(-85.0, 85.0), (east + dLon).coerceIn(-180.0, 180.0),
                (south - dLat).coerceIn(-85.0, 85.0), (west - dLon).coerceIn(-180.0, 180.0))
        }

        private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

        /** Map style JSON: an object (null values left out) and an array / expression. */
        internal fun o(vararg kv: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in kv) if (v != null) put(k, v) }
        internal fun a(vararg v: Any) = JSONArray().apply { v.forEach { put(it) } }
    }

    /** A saved trace is shown (no live layers; its own pins and route). */
    var tripMode = false
    var filter = DeviceFilter.ALL
    var isDark = false; private set
    /** What the CCTV layer is doing, for the status line (null while the layer is off). */
    var cctvLine: String? = null; private set
    /** Cell towers with a position (the Cells chip's count). */
    var towerCount = 0; private set
    sealed class MapSelection {
        data class Detection(
            val entityUuid: String,
            val title: String,
            val subtitle: String,
            val agencyCode: String,
            val lat: Double,
            val lon: Double,
            val radiusM: Int?
        ) : MapSelection()

        data class Facility(
            val location: org.tucsoncommunity.kokoho.posture.KokohoDirectory.FacilityLocation
        ) : MapSelection()

        data class DroppedPoint(
            val lat: Double,
            val lon: Double
        ) : MapSelection()
    }

    /** Invoked on live map when the user taps a detection, facility location, or picks an entity from a cluster. */
    var onSelectTarget: ((MapSelection) -> Unit)? = null
    /** Invoked on live map when the user taps empty map space. */
    var onTapEmpty: (() -> Unit)? = null

    /** After a camera download or an ignore changed what's on the map. */
    var onStaticChanged: (() -> Unit)? = null

    // What's on the map now, to find what a tap hit.
    private var pins: Map<String, MapPin> = emptyMap()
    private var locationsById: Map<String, org.tucsoncommunity.kokoho.posture.KokohoDirectory.FacilityLocation> = emptyMap()
    private var activeSelection: MapSelection? = null
    private var wazeShown: Map<String, WazePolice.Shown> = emptyMap()
    private var planes: Map<String, PoliceAircraft.Seen> = emptyMap()
    private var drones: Map<String, DeviceRegistry.Snapshot> = emptyMap()
    private var airThreatsByUuid: Map<String, org.tucsoncommunity.kokoho.posture.EvaluatedThreat> = emptyMap()
    private var droneThreatsByUuid: Map<String, org.tucsoncommunity.kokoho.posture.EvaluatedThreat> = emptyMap()
    private var unpinpointedAirspaceUuids: Set<String> = emptySet()
    private var standaloneOperatorUuids: Set<String> = emptySet()
    private var flightTracksByEntityUuid: Map<String, org.tucsoncommunity.kokoho.posture.KokohoHubClient.RemoteFlightTrack> = emptyMap()
    private var localDroneTrailsByUuid: Map<String, PoliceAircraft.LocalDroneTrail> = emptyMap()
    private var selectedFlightTrackEntityUuid: String? = null
    private var selectedFlightTrackRecord: org.tucsoncommunity.kokoho.posture.KokohoHubClient.RemoteFlightTrack? = null
    private var selectedFlightTrackColorHex: String = "#EC4899"
    private var airborneOverlay: Airborne3dOverlayView? = null
    private var cameraTopPaddingPx: Double = 0.0
    private var lastAirborne2dHidden: Boolean? = null
    private var lastDroneDome2dHidden: Boolean? = null
    private val badgeBitmapsById = HashMap<String, android.graphics.Bitmap>()
    private var selectionRingBmp: android.graphics.Bitmap? = null
    private var cameras: Map<String, KnownCamera> = emptyMap()
    private var cctvs: Map<String, org.tucsoncommunity.kokoho.alpr.CctvCamera> = emptyMap()
    private var towers: Map<String, CellTowerStore.Tower> = emptyMap()
    private var wigle: Map<String, WiglePins.Pin> = emptyMap()
    /** What the WiGLE layer was drawn for (pins' version, filter, trace mode): redrawn only when it changes. */
    private var wigleDrawn: String? = null
    /** The places the shown requested network was asked for, by id, and what they were drawn for. */
    private var asked: Map<String, org.tucsoncommunity.kokoho.data.ProbeBook.Spot> = emptyMap()
    private var askedDrawn: String? = null
    /** The area the camera, CCTV and tower layers were drawn for (redrawn when the view leaves it). */
    private var staticBox: MapEngine.Box? = null
    private var staticAt = 0L
    /** Whether the CCTV were drawn (zoomed in enough): zooming back in past that level redraws them at once. */
    private var staticCctv = false
    private var lastCameraFetch = 0L
    private var lastCctvFetch = 0L
    private val sourceJsonCache = HashMap<String, String>()
    private var locationsDrawnKey: String? = null
    private var staticCleared = false

    /** Loads the light or night style; [onLoaded] once its layers are in. */
    fun loadStyle(dark: Boolean, onLoaded: () -> Unit) {
        isDark = dark
        map.setStyle(dark) { fresh ->
            if (activity.isFinishing || activity.isDestroyed) return@setStyle
            if (fresh || !map.hasSource(DEVICES)) safe("style") {
                sourceJsonCache.clear()
                locationsDrawnKey = null
                staticCleared = false
                addImages()
                addLayers(dark)
            } else safe("style") {
                // The same map turned night (Mapbox): the labels' colours follow.
                val (ink, halo) = labelColors(dark)
                map.setPaint(LABELS, "text-color", ink); map.setPaint(LABELS, "text-halo-color", halo)
                map.setPaint("$WIGLE-labels", "text-color", ink); map.setPaint("$WIGLE-labels", "text-halo-color", halo)
                map.setPaint("$ASKED-labels", "text-color", ink); map.setPaint("$ASKED-labels", "text-halo-color", halo)
            }
            staticBox = null
            wigleDrawn = null
            askedDrawn = null
            locationsDrawnKey = null
            staticCleared = false
            lastAirborne2dHidden = null
            lastDroneDome2dHidden = null
            airborneOverlay?.bind(map, selectionRingBmp)
            safe("loaded") { onLoaded() }
        }
    }

    fun attachAirborneOverlay(overlay: Airborne3dOverlayView?) {
        airborneOverlay = overlay
        if (overlay != null) {
            if (selectionRingBmp == null) {
                selectionRingBmp = MapIcons.selectionRingIcon(activity.resources.displayMetrics.density)
            }
            overlay.bind(map, selectionRingBmp)
        }
        lastAirborne2dHidden = null
        lastDroneDome2dHidden = null
        syncAirbornePitchState(cameraTopPaddingPx)
    }

    fun syncAirbornePitchState(topPadPx: Double = cameraTopPaddingPx) {
        cameraTopPaddingPx = topPadPx
        val overlay = airborneOverlay
        val (point3d, dome3d) = if (!tripMode && overlay != null) {
            overlay.onCameraMoved(topPadPx)
        } else {
            false to false
        }
        var changed = false
        if (ready() && lastAirborne2dHidden != point3d) {
            if (lastAirborne2dHidden != null) changed = true
            lastAirborne2dHidden = point3d
            val show2d = !point3d
            map.setLayerVisible(AIRCRAFT, show2d)
            map.setLayerVisible(DRONES, show2d)
            map.setLayerVisible(DRONE_OPS, show2d)
            map.setLayerVisible(DRONE_LINES, show2d)
        }
        if (ready() && lastDroneDome2dHidden != dome3d) {
            if (lastDroneDome2dHidden != null) changed = true
            lastDroneDome2dHidden = dome3d
            val showDome2d = !dome3d
            map.setLayerVisible("$DRONE_DOME_2D-fill", showDome2d)
            map.setLayerVisible("$DRONE_DOME_2D-line", showDome2d)
            map.setLayerVisible("$DRONE_DOME_2D-dots", showDome2d)
            map.setLayerVisible("$DRONE_DOME_2D-labels", showDome2d)
        }
        if (changed && activeSelection is MapSelection.Detection) {
            drawSelection(activeSelection)
        }
    }

    fun onTimeWindowChanged(newWindow: org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapTimeWindow) {
        if (!ready() || TripRecorder.isRecording) return
        val selUuid = selectedFlightTrackEntityUuid ?: return
        val selRecord = selectedFlightTrackRecord ?: flightTracksByEntityUuid[selUuid] ?: return
        val clipped = selRecord.clippedPointsForWindow(System.currentTimeMillis(), newWindow.windowMs)
        if (clipped.size >= 2) {
            drawTrace(PoliceAircraft.chaikinSmooth(clipped, 2), selectedFlightTrackColorHex, selUuid)
        } else {
            drawTrace(emptyList(), TRACE_COLOR)
        }
    }

    /**
     * Runs [block] and keeps whatever it throws here: map callbacks run inside the map engine,
     * where an exception aborts the whole app instead of failing one drawing.
     */
    inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { android.util.Log.w("RfMap", "$what failed", e) }
    }

    private val registeredBadgeIcons = HashSet<String>()

    /** The same icons as before, plus agency classification badge bitmaps. */
    private fun addImages() {
        registeredBadgeIcons.clear()
        badgeBitmapsById.clear()
        val dp = activity.resources.displayMetrics.density
        KnownCamera.Kind.entries.forEach { map.addImage("rf-cam-${it.name}", MapIcons.cameraIcon(dp, it)) }
        map.addImage("rf-cctv-public", MapIcons.cctvIcon(dp, false))
        map.addImage("rf-cctv-private", MapIcons.cctvIcon(dp, true))
        map.addImage("rf-plane-police", MapIcons.planeIcon(dp, true))
        map.addImage("rf-plane", MapIcons.planeIcon(dp, false))
        map.addImage("rf-plane-clear", MapIcons.planeIcon(dp, false, clear = true))
        map.addImage("rf-tower", MapIcons.towerIcon(dp))
        map.addImage("rf-drone", droneArrow(dp))
        map.addImage("rf-pilot-badge", MapIcons.classificationBadgeIcon(dp, MapIcons.BadgeIconKind.PILOT, 0xFFD97706.toInt()))
        map.addImage("rf-dropped-pin", MapIcons.droppedPinIcon(dp))
        val ringBmp = MapIcons.selectionRingIcon(dp)
        selectionRingBmp = ringBmp
        map.addImage("rf-selection-ring", ringBmp)
        map.addImage("rf-facility-fire", MapIcons.facilityBadgeIcon(dp, 0xFFDC2626.toInt(), isFire = true))
        for (agency in org.tucsoncommunity.kokoho.posture.KokohoAgency.entries) {
            val facId = "rf-facility-${agency.id}"
            map.addImage(facId, MapIcons.facilityBadgeIcon(dp, agency.mapColorArgb, isFire = false))
            registeredBadgeIcons += facId
        }
        for (kind in MapIcons.BadgeIconKind.entries) {
            for (agency in org.tucsoncommunity.kokoho.posture.KokohoAgency.entries) {
                val id = "rf-badge-${kind.id}-${agency.id}"
                val bmp = MapIcons.classificationBadgeIcon(dp, kind, agency.mapColorArgb)
                map.addImage(id, bmp)
                badgeBitmapsById[id] = bmp
                registeredBadgeIcons += id
            }
        }
    }

    private fun ensureBadgeIcon(kind: MapIcons.BadgeIconKind, colorArgb: Int, agencyId: String): String {
        val hexPart = String.format("%06X", colorArgb and 0xFFFFFF)
        val agency = org.tucsoncommunity.kokoho.posture.KokohoAgency.fromCode(agencyId)
        val iconId = if (agency != null && (agency.mapColorArgb and 0xFFFFFF) == (colorArgb and 0xFFFFFF)) {
            "rf-badge-${kind.id}-${agency.id}"
        } else {
            "rf-badge-${kind.id}-$hexPart"
        }
        if (registeredBadgeIcons.add(iconId)) {
            val dp = activity.resources.displayMetrics.density
            val bmp = MapIcons.classificationBadgeIcon(dp, kind, colorArgb)
            map.addImage(iconId, bmp)
            badgeBitmapsById[iconId] = bmp
        }
        return iconId
    }

    /** Every layer, as map style JSON (the same on both engines). */
    private fun addLayers(dark: Boolean) {
        listOf(NAV_ROUTES, NAV_PLACES, HEAT, PRECISION, CONES, CAM_CONES, FLIGHT_TRACKS, TRACE, DRONE_LINES, TOWERS, CCTV, CAMERAS, LOCATIONS, PINGS, DEVICES, WIGLE, ASKED, WAZE, DRONE_OPS, DRONES, AIRCRAFT, DROPPED_POINT, SELECTED_PIN)
            .forEach { map.addSource(it) }
        fun get(k: String) = a("get", k)
        val color = a("to-color", get("color"))
        val flagged = a("==", get("flagged"), true)
        val font = JSONArray(map.labelFont)
        val (ink, halo) = labelColors(dark)
        val overlap = arrayOf<Pair<String, Any?>>("icon-allow-overlap" to true, "icon-ignore-placement" to true)
        // Mapbox's night look is lit like a real night: a layer that gives off no light of its own goes almost
        // black on it (the route showed as dark navy, the Waze dots as black). Every overlay is given full
        // brightness there, whatever kind of layer it is; only Mapbox knows these properties, so the other
        // engine never sees them.
        val lit = map.provider == MapEngine.Provider.MAPBOX
        fun glow(type: String): List<String> = when (type) {
            "line" -> listOf("line-emissive-strength")
            "circle" -> listOf("circle-emissive-strength")
            "fill" -> listOf("fill-emissive-strength")
            "symbol" -> listOf("icon-emissive-strength", "text-emissive-strength")
            else -> emptyList()
        }
        fun layer(id: String, type: String, source: String = id, minzoom: Double? = null, filter: JSONArray? = null,
                  layout: JSONObject? = null, paint: JSONObject? = null): JSONObject {
            val lightedPaint = if (lit && glow(type).isNotEmpty()) (paint ?: JSONObject()).also { p -> glow(type).forEach { p.put(it, 1.0) } } else paint
            return o("id" to id, "type" to type, "source" to source, "minzoom" to minzoom, "filter" to filter, "layout" to layout, "paint" to lightedPaint)
        }
        val routeLine = o("line-cap" to "round", "line-join" to "round")
        val agencyHeatLayers = org.tucsoncommunity.kokoho.posture.KokohoAgency.entries
            .sortedByDescending { it.priorityRank }
            .map { ag ->
                val c = ag.mapColorArgb
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val rHi = kotlin.math.min(255, r + 38)
                val gHi = kotlin.math.min(255, g + 38)
                val bHi = kotlin.math.min(255, b + 38)
                layer(
                    id = "$HEAT-${ag.id}",
                    type = "heatmap",
                    source = HEAT,
                    filter = a("==", get("agency"), ag.id),
                    paint = o(
                        "heatmap-weight" to a("coalesce", get("weight"), 1.0),
                        "heatmap-intensity" to 1.15,
                        "heatmap-radius" to a("interpolate", a("linear"), a("zoom"), 8, 16.0, 11, 28.0, 14, 42.0, 16, 54.0),
                        "heatmap-opacity" to 0.50,
                        "heatmap-color" to a(
                            "interpolate", a("linear"), a("heatmap-density"),
                            0.0, "rgba($r,$g,$b,0)",
                            0.16, "rgba($r,$g,$b,0.38)",
                            0.45, "rgba($r,$g,$b,0.78)",
                            0.78, "rgba($r,$g,$b,0.96)",
                            1.0, "rgba($rHi,$gHi,$bHi,1.0)"
                        )
                    )
                )
            }
        (listOf(
            // A route to follow: the other routes offered under it, in grey, then the chosen one with a white edge.
            layer("$NAV_ROUTES-alt", "line", NAV_ROUTES, filter = a("==", get("sel"), false), layout = routeLine,
                paint = o("line-color" to NAV_ALT_COLOR, "line-width" to 6.0, "line-opacity" to 0.85)),
            layer("$NAV_ROUTES-edge", "line", NAV_ROUTES, filter = a("==", get("sel"), true), layout = routeLine,
                paint = o("line-color" to "#FFFFFF", "line-width" to 11.0)),
            layer("$NAV_ROUTES-line", "line", NAV_ROUTES, filter = a("==", get("sel"), true), layout = routeLine,
                paint = o("line-color" to NAV_ROUTE_COLOR, "line-width" to 7.0))
        ) + agencyHeatLayers + listOf(
            // Flagged signal radius / centroid bubbles (50% transparent fill so the basemap reads cleanly through).
            layer("$PRECISION-fill", "fill", PRECISION, filter = a("!=", get("droneDome"), true),
                paint = o("fill-color" to color, "fill-opacity" to a("*", 0.50, get("opacity")))),
            layer("$PRECISION-line", "line", PRECISION, filter = a("!=", get("droneDome"), true),
                paint = o("line-color" to color, "line-width" to 2.5, "line-opacity" to a("*", 0.92, get("opacity")))),
            // Unpinpointed drone 2D uncertainty circles (visible when pitch <= 4 deg; lifts into 3D dome when pitch > 4 deg).
            layer("$DRONE_DOME_2D-fill", "fill", PRECISION, filter = a("==", get("droneDome"), true),
                paint = o("fill-color" to color, "fill-opacity" to a("*", 0.36, get("opacity")))),
            layer("$DRONE_DOME_2D-line", "line", PRECISION, filter = a("==", get("droneDome"), true),
                paint = o("line-color" to color, "line-width" to 2.3, "line-opacity" to a("*", 0.92, get("opacity")))),
            // What each CCTV camera watches (zoomed in).
            layer("$CONES-fill", "fill", CONES, minzoom = CCTV_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            // Which way each plate, speed and red-light camera faces (when mapped), in its icon's colour.
            layer("$CAM_CONES-fill", "fill", CAM_CONES, minzoom = CAMERA_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            layer("$CAM_CONES-line", "line", CAM_CONES, minzoom = CAMERA_CONE_ZOOM,
                paint = o("line-color" to color, "line-width" to 1.2, "line-opacity" to a("*", get("opacity"), 2.5))),
            // Thin Chaikin-smoothed flight tracks, filtered and vertex-clipped to the active map time window.
            layer(FLIGHT_TRACKS, "line", layout = o("line-cap" to "round", "line-join" to "round"),
                paint = o("line-color" to color, "line-width" to 2.5, "line-opacity" to a("coalesce", get("opacity"), 0.84))),
            layer(TRACE, "line", layout = o("line-cap" to "round", "line-join" to "round"),
                paint = o("line-color" to color, "line-width" to 5.0)),
            layer(DRONE_LINES, "line", layout = o("line-cap" to "round"),
                paint = o("line-color" to "#38BDF8", "line-width" to 2.5, "line-dasharray" to a(3.5, 2.2), "line-opacity" to 0.90)),
            layer(TOWERS, "symbol", layout = o("icon-image" to "rf-tower", *overlap)),
            layer(CCTV, "symbol", minzoom = CCTV_MIN_ZOOM, layout = o("icon-image" to get("icon"), *overlap)),
            // Plate, speed and red-light cameras; the ones whose alerts you turned off, faded.
            layer(CAMERAS, "symbol", minzoom = CAMERAS_MIN_ZOOM, layout = o("icon-image" to get("icon"), *overlap),
                paint = o("icon-opacity" to get("opacity"))),
            // Directory Locations (DHS facilities visible from city/state zoom >= 7, municipal police/sheriff/fire from >= 10.5).
            layer("$LOCATIONS-fed", "symbol", LOCATIONS, minzoom = 7.0, filter = a("==", get("federal"), true),
                layout = o("icon-image" to get("icon"), "icon-size" to 0.96, *overlap)),
            layer("$LOCATIONS-local", "symbol", LOCATIONS, minzoom = 10.5, filter = a("==", get("federal"), false),
                layout = o("icon-image" to get("icon"), "icon-size" to 0.86, *overlap)),
            layer("$LOCATION_LABELS-fed", "symbol", LOCATIONS, minzoom = 11.0, filter = a("==", get("federal"), true),
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 12.5, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.15), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 2.0)),
            layer("$LOCATION_LABELS-local", "symbol", LOCATIONS, minzoom = 13.0, filter = a("==", get("federal"), false),
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 11.5, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.1), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 1.8)),
            // A saved trace's every sighting of each device (Settings › Log every sighting in traces): small, under the devices.
            layer(PINGS, "circle", paint = o("circle-color" to color, "circle-radius" to 3.0, "circle-opacity" to 0.8)),
            // Devices: circular agency-colored icon badge when narrow enough to show a point (showDot == true).
            layer(DOTS, "symbol", DEVICES, filter = a("all", a("==", get("showDot"), true), a("!=", get("droneDome"), true)),
                layout = o("icon-image" to get("icon"), "icon-size" to 0.92, *overlap,
                    "symbol-sort-key" to a("case", flagged, 1.0, 0.0)),
                paint = o("icon-opacity" to get("opacity"))),
            layer(LABELS, "symbol", DEVICES, filter = a("all", flagged, a("!=", get("droneDome"), true)),
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 13.0, "text-anchor" to "top",
                    "text-max-width" to 16.0, "text-offset" to a(0.0, 1.15), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 2.0, "text-opacity" to get("opacity"))),
            layer("$DRONE_DOME_2D-dots", "symbol", DEVICES, filter = a("all", a("==", get("showDot"), true), a("==", get("droneDome"), true)),
                layout = o("icon-image" to get("icon"), "icon-size" to 0.92, *overlap, "symbol-sort-key" to 1.0),
                paint = o("icon-opacity" to get("opacity"))),
            layer("$DRONE_DOME_2D-labels", "symbol", DEVICES, filter = a("all", flagged, a("==", get("droneDome"), true)),
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 13.0, "text-anchor" to "top",
                    "text-max-width" to 16.0, "text-offset" to a(0.0, 1.15), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 2.0, "text-opacity" to get("opacity"))),
            // Networks pinned from a WiGLE search: purple, with their name.
            layer(WIGLE, "circle", paint = o("circle-color" to WIGLE_COLOR, "circle-radius" to 7.0,
                "circle-stroke-color" to "#FFFFFF", "circle-stroke-width" to 2.0)),
            layer("$WIGLE-labels", "symbol", WIGLE,
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.0), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 1.5)),
            // Where a requested network was asked for ("Show on map" in the list): teal, with when.
            layer(ASKED, "circle", paint = o("circle-color" to ASKED_COLOR, "circle-radius" to 7.0,
                "circle-stroke-color" to "#FFFFFF", "circle-stroke-width" to 2.0)),
            layer("$ASKED-labels", "symbol", ASKED,
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.0), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 1.5)),
            // Waze reports: blue police, red accident, orange hazard, brown closure, purple jam.
            layer(WAZE, "circle", paint = o("circle-color" to color, "circle-radius" to 9.0,
                "circle-stroke-color" to "#FFFFFF", "circle-stroke-width" to 2.0)),
            // A drone's operator / ground controller position, and the airborne drone badge.
            layer(DRONE_OPS, "symbol",
                layout = o("icon-image" to "rf-pilot-badge", "icon-size" to 0.88,
                    "icon-rotation-alignment" to "viewport", *overlap,
                    "text-field" to get("label"), "text-font" to font, "text-size" to 11.5, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.35), "text-optional" to true),
                paint = o("text-color" to "#FDE68A", "text-halo-color" to halo, "text-halo-width" to 2.0)),
            layer(DRONES, "symbol",
                layout = o("icon-image" to get("icon"), "icon-size" to 0.92,
                    "icon-rotation-alignment" to "viewport", *overlap,
                    "text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.4), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 2.0)),
            // Where the route ends (red) and the stops on the way (white with a blue edge), above everything else.
            layer("$NAV_PLACES-pin", "circle", NAV_PLACES, paint = o(
                "circle-color" to a("case", a("==", get("end"), true), "#D93025", "#FFFFFF"), "circle-radius" to 9.0,
                "circle-stroke-color" to a("case", a("==", get("end"), true), "#FFFFFF", NAV_ROUTE_COLOR), "circle-stroke-width" to 3.0)),
            layer(AIRCRAFT, "symbol", layout = o("icon-image" to get("icon"), "icon-rotate" to get("track"),
                "icon-rotation-alignment" to "map",
                "icon-size" to a("interpolate", a("linear"), a("zoom"), 9, 0.82, 12, 0.98, 16, 1.2),
                *overlap, "symbol-sort-key" to a("case", flagged, 1.0, 0.0),
                "text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                "text-offset" to a(0.0, 1.5), "text-optional" to true),
                paint = o("text-color" to "#FFFFFF", "text-halo-color" to a("coalesce", get("color"), hex(MapIcons.POLICE_AIRCRAFT_COLOR)),
                    "text-halo-width" to 3.0)),
            // User-dropped map point & active map selection halo ring.
            layer(DROPPED_POINT, "symbol", layout = o("icon-image" to "rf-dropped-pin", "icon-size" to 1.0, *overlap)),
            layer(SELECTED_PIN, "symbol", layout = o("icon-image" to "rf-selection-ring", "icon-size" to 1.0, *overlap))
        )).forEach { map.addLayer(it) }
    }

    /** Device labels: text and halo colours on the light or night map. */
    private fun labelColors(dark: Boolean) = if (dark) "#FFFFFF" to "#1B1F27" else "#1B1F27" to "#FFFFFF"

    private fun ready() = map.styleLoaded

    private fun set(source: String, features: List<JSONObject>) {
        val json = GeoJson.collection(features)
        if (sourceJsonCache[source] == json) return
        sourceJsonCache[source] = json
        map.setData(source, json)
    }

    /** Devices (and their radius circles); returns what was drawn. */
    fun drawPins(shown: List<MapPin>) {
        if (!ready()) return
        pins = shown.associateBy { it.mac }
        set(DEVICES, shown.map { p ->
            // Place the text label on the north rim of the circle when rendered as a circle (!showDot), or at the point when pinpointed
            val (labelLat, labelLon) = if (!p.showDot && p.radiusM != null && p.radiusM > 0.0) {
                MapGeo.offset(p.lat, p.lon, p.radiusM, 0.0)
            } else {
                p.lat to p.lon
            }
            val iconId = p.customIconId ?: ensureBadgeIcon(p.iconKind, p.color, p.agencyId)
            GeoJson.feature(
                GeoJson.point(labelLat, labelLon),
                "mac" to p.mac,
                "label" to p.label.take(110),
                "color" to hex(p.color),
                "icon" to iconId,
                "flagged" to p.flagged,
                "showDot" to p.showDot,
                "opacity" to p.opacity,
                "droneDome" to p.isDroneDome
            )
        })
        set(PRECISION, shown.filter { it.radiusM != null }.map { p ->
            GeoJson.feature(
                GeoJson.polygon(MapGeo.circle(p.lat, p.lon, p.radiusM!!, n = 32)),
                "color" to hex(p.color),
                "mac" to p.mac,
                "opacity" to p.opacity,
                "droneDome" to p.isDroneDome
            )
        })
    }

    /** Draws a single drone's operator pin and tether line on detail views (clears when opLat/opLon are null). */
    fun drawSingleDroneOperator(uuid: String, droneLat: Double, droneLon: Double, opLat: Double?, opLon: Double?) {
        if (!ready()) return
        if (opLat == null || opLon == null) {
            set(DRONE_OPS, emptyList())
            set(DRONE_LINES, emptyList())
            return
        }
        set(DRONE_OPS, listOf(GeoJson.feature(GeoJson.point(opLat, opLon), "mac" to uuid, "label" to "Drone Pilot")))
        set(DRONE_LINES, listOf(GeoJson.feature(GeoJson.line(listOf(opLat to opLon, droneLat to droneLon)), "mac" to uuid)))
    }

    /** Every sighting of the drawn devices in a saved trace, in each device's colour (draw after [drawPins]). */
    fun drawPings(pings: List<org.tucsoncommunity.kokoho.data.TripPingEntity>) {
        if (!ready()) return
        set(PINGS, pings.mapNotNull { p ->
            pins[p.mac]?.let { GeoJson.feature(GeoJson.point(p.lat, p.lon), "mac" to p.mac, "color" to hex(it.color)) }
        })
    }

    /**
     * Routes to follow: [lines] as (lat, lon) lists, the one at [selected] on top in blue and the
     * others grey. Empty clears them.
     */
    fun drawRoutes(lines: List<List<Pair<Double, Double>>>, selected: Int) {
        if (!ready()) return
        set(NAV_ROUTES, lines.mapIndexedNotNull { i, pts ->
            if (pts.size < 2) null else GeoJson.feature(GeoJson.line(pts), "sel" to (i == selected), "idx" to i)
        })
    }

    /** The destination and the stops on the way ([stops] white, [end] red). Null clears them. */
    fun drawNavPlaces(stops: List<Pair<Double, Double>>, end: Pair<Double, Double>?) {
        if (!ready()) return
        set(NAV_PLACES, stops.map { GeoJson.feature(GeoJson.point(it.first, it.second), "end" to false) } +
            listOfNotNull(end?.let { GeoJson.feature(GeoJson.point(it.first, it.second), "end" to true) }))
    }

    /** Draws or clears the active selection ring and optional dropped-point pin. */
    fun drawSelection(selection: MapSelection?) {
        activeSelection = selection
        val selectedUuid = (selection as? MapSelection.Detection)?.entityUuid
        airborneOverlay?.updateSelection(selectedUuid)
        if (!ready()) return
        when (selection) {
            null -> {
                set(DROPPED_POINT, emptyList())
                set(SELECTED_PIN, emptyList())
            }
            is MapSelection.DroppedPoint -> {
                set(DROPPED_POINT, listOf(GeoJson.feature(GeoJson.point(selection.lat, selection.lon))))
                set(SELECTED_PIN, listOf(GeoJson.feature(GeoJson.point(selection.lat, selection.lon))))
            }
            is MapSelection.Detection -> {
                set(DROPPED_POINT, emptyList())
                val is3dPointTarget = airborneOverlay?.is3dActive() == true &&
                    ((selection.entityUuid in airThreatsByUuid ||
                        selection.entityUuid in droneThreatsByUuid ||
                        selection.entityUuid in standaloneOperatorUuids) &&
                        selection.entityUuid !in unpinpointedAirspaceUuids)
                val is3dDomeTarget = airborneOverlay?.isDome3dActive() == true &&
                    selection.entityUuid in unpinpointedAirspaceUuids
                if (is3dPointTarget || is3dDomeTarget) {
                    // Selection halo is drawn around the elevated 3D puck, crisp operator dot, or 3D airspace dome in Airborne3dOverlayView
                    set(SELECTED_PIN, emptyList())
                } else {
                    set(SELECTED_PIN, listOf(GeoJson.feature(GeoJson.point(selection.lat, selection.lon))))
                }
            }
            is MapSelection.Facility -> {
                set(DROPPED_POINT, emptyList())
                set(SELECTED_PIN, listOf(GeoJson.feature(GeoJson.point(selection.location.lat, selection.location.lon))))
            }
        }
    }

    /** Draws civic/facility Location pins, skipping GeoJSON rebuilds whenever visibility filters and merged exclusions are unchanged. */
    fun drawLocations(excludedIds: Set<String> = emptySet()) {
        if (!ready()) return
        if (tripMode) {
            if (locationsDrawnKey == "trip") return
            locationsDrawnKey = "trip"
            locationsById = emptyMap()
            set(LOCATIONS, emptyList())
            return
        }
        val visible = org.tucsoncommunity.kokoho.posture.KokohoDirectory.visibleLocations(activity)
        locationsById = visible.associateBy { it.id }
        val excludedKey = if (excludedIds.isEmpty()) "" else excludedIds.sorted().joinToString(",")
        val key = "${visible.size}|${visible.firstOrNull()?.id.orEmpty()}|${visible.lastOrNull()?.id.orEmpty()}|$excludedKey"
        if (key == locationsDrawnKey) return
        locationsDrawnKey = key
        val drawn = if (excludedIds.isEmpty()) visible else visible.filter { it.id !in excludedIds }
        set(LOCATIONS, drawn.map { loc ->
            val isFire = loc.locationGroup.equals("FIRE", ignoreCase = true) ||
                loc.title.contains("Fire", ignoreCase = true) ||
                loc.description.contains("Fire", ignoreCase = true)
            val ag = loc.agency
            val iconId = if (isFire) "rf-facility-fire" else "rf-facility-${ag.id}"
            val isFed = loc.locationGroup.equals("FEDERAL", ignoreCase = true) || ag.isDhs
            GeoJson.feature(
                GeoJson.point(loc.lat, loc.lon),
                "id" to loc.id,
                "label" to loc.title.take(44),
                "icon" to iconId,
                "federal" to isFed
            )
        })
    }

    /** A route: the trace being recorded (orange), a highlighted flight track, or a saved one (blue). */
    fun drawTrace(points: List<Pair<Double, Double>>, color: String, entityUuid: String? = null) {
        if (!ready()) return
        set(
            TRACE,
            if (points.size < 2) {
                emptyList()
            } else {
                listOf(GeoJson.feature(GeoJson.line(points), "color" to color, "hex" to entityUuid))
            }
        )
    }

    internal fun isStandaloneDroneOperator(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean =
        !t.isAircraft &&
            org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.isStandaloneDroneOperator(
                t.evidenceSources,
                t.hardwareIdentifier
            )

    internal fun isDroneDetection(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean =
        !t.isAircraft &&
            !isStandaloneDroneOperator(t) && (
                t.isDrone ||
                    t.agency == org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE ||
                    t.evidenceSources.any {
                        it.facetKey.startsWith("uas:", ignoreCase = true) ||
                            it.facetKey.startsWith("DRONE:", ignoreCase = true)
                    }
                )

    internal fun hasRemoteIdDroneEvidence(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean =
        t.altitudeFtAgl != null ||
            t.operatorLat != null ||
            t.evidenceSources.any {
                it.facetKey.startsWith("uas:alt_ft_agl:", ignoreCase = true) ||
                    it.facetKey.startsWith("uas:op:", ignoreCase = true)
            }

    internal fun isPinpointedDrone(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean =
        isDroneDetection(t) && (
            (t.isPinpointed && t.uncertaintyRadiusM <= 22) ||
                (hasRemoteIdDroneEvidence(t) && t.uncertaintyRadiusM <= 40)
            )

    internal fun isUnpinpointedDrone(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean =
        isDroneDetection(t) && !isPinpointedDrone(t)

    internal fun isPinpointedAircraft(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean =
        t.isAircraft && t.isPinpointed && t.uncertaintyRadiusM <= 65

    internal fun isUnpinpointedAircraft(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat): Boolean {
        if (isDroneDetection(t) || isStandaloneDroneOperator(t)) return false
        if (t.isAircraft) return !isPinpointedAircraft(t)
        return t.agency == org.tucsoncommunity.kokoho.posture.KokohoAgency.DHS_AMO &&
            (!t.isPinpointed || t.uncertaintyRadiusM > 65) &&
            t.evidenceSources.none { it.kind.equals("WITNESS_REPORT", ignoreCase = true) }
    }

    /**
     * Fast-changing live layers: draws ONLY Kokoho's active flagged agency detections
     * (with overlapping radii collapsed into single circles labeled with comma-separated agencies,
     * rolling window radius/staleness fade, unpinpointed drone 3D airspace domes, pinpointed
     * Pro/Enterprise/RemoteID drones with altitude, and ADS-B aircraft with tap-to-view flight tracks).
     */
    fun drawLive(
        @Suppress("UNUSED_PARAMETER") devices: List<DeviceRegistry.Snapshot>,
        precomputedSnap: org.tucsoncommunity.kokoho.posture.PostureSnapshot? = null
    ): List<MapPin> {
        if (!ready()) return emptyList()
        val snap = precomputedSnap ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.evaluate(activity)
        val nowMs = snap.evaluatedAtMs
        val fallbackLat = ScanForegroundService.lastFix?.latitude ?: map.camera.lat.takeIf { kotlin.math.abs(it) > 1e-4 } ?: 32.2226
        val fallbackLon = ScanForegroundService.lastFix?.longitude ?: map.camera.lon.takeIf { kotlin.math.abs(it) > 1e-4 } ?: -110.9747
        val timeWindow = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getMapTimeWindow(activity)
        val confirmFilter = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getMapConfirmationFilter(activity)
        val heatOverlayEnabled = org.tucsoncommunity.kokoho.posture.KokohoSignatures.isMapHeatOverlayEnabled(activity)

        val windowFilteredThreats = snap.mapThreats.filter { t ->
            val ageMs = (nowMs - t.lastSeenMs).coerceAtLeast(0L)
            ageMs <= timeWindow.windowMs &&
                org.tucsoncommunity.kokoho.posture.KokohoSignatures.matchesMapConfirmationFilter(activity, t, confirmFilter)
        }

        val groundThreats = windowFilteredThreats.filter {
            !it.isAircraft &&
                !isUnpinpointedAircraft(it) &&
                !isDroneDetection(it) &&
                !isStandaloneDroneOperator(it) &&
                !org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(it.hardwareIdentifier, it.title)
        }
        val standaloneOperators = windowFilteredThreats.filter {
            isStandaloneDroneOperator(it) &&
                !org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(it.hardwareIdentifier, it.title)
        }
        val unpinpointedDrones = windowFilteredThreats.filter {
            isUnpinpointedDrone(it) &&
                !org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(it.hardwareIdentifier, it.title)
        }
        val pinpointedAirThreats = windowFilteredThreats.filter { isPinpointedAircraft(it) }
        val unpinpointedAirThreats = windowFilteredThreats.filter {
            isUnpinpointedAircraft(it) &&
                !org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(it.hardwareIdentifier, it.title)
        }
        val zoom = map.camera.zoom
        val zoomMergeM = org.tucsoncommunity.kokoho.posture.KokohoSignatures.zoomGravityMergeRadiusM(zoom)

        data class ThreatNode(
            val threat: org.tucsoncommunity.kokoho.posture.EvaluatedThreat,
            val lat: Double,
            val lon: Double,
            val radiusM: Double,
            val mass: Double
        )

        data class MergedCluster(
            val nodes: MutableList<ThreatNode>,
            var lat: Double,
            var lon: Double,
            var radiusM: Double,
            var totalMass: Double,
            val stations: MutableList<org.tucsoncommunity.kokoho.posture.KokohoDirectory.FacilityLocation> = mutableListOf()
        )

        val clusters = mutableListOf<MergedCluster>()
        for (t in groundThreats) {
            val rawLat = t.lat ?: fallbackLat
            val rawLon = t.lon ?: fallbackLon
            val lat = rawLat
            val lon = rawLon
            val r = if (t.isPinpointed) 0.0 else t.mapCircleRadiusM.toDouble()
            val mass = (t.sightingCount.coerceAtLeast(1) * t.evidenceCount.coerceAtLeast(1)).toDouble()
            val node = ThreatNode(t, lat, lon, r, mass)
            val match = if (zoomMergeM <= 0.0) {
                null
            } else {
                clusters.firstOrNull { c ->
                    val d = DeviceRegistry.metersBetween(lat, lon, c.lat, c.lon)
                    d <= zoomMergeM
                }
            }
            if (match != null) {
                val nextMass = match.totalMass + mass
                match.lat = ((match.lat * match.totalMass) + (lat * mass)) / nextMass
                match.lon = ((match.lon * match.totalMass) + (lon * mass)) / nextMass
                match.totalMass = nextMass
                match.nodes += node
                if (r > 0.0 && (match.radiusM == 0.0 || r < match.radiusM)) {
                    match.radiusM = r
                }
            } else {
                clusters += MergedCluster(mutableListOf(node), lat, lon, r, mass)
            }
        }

        // Second-pass gravity convergence: merge any clusters whose gravity centers shifted within zoom proximity
        if (zoomMergeM > 0.0) {
            var i = 0
            while (i < clusters.size) {
                var j = i + 1
                while (j < clusters.size) {
                    val c1 = clusters[i]
                    val c2 = clusters[j]
                    val d = DeviceRegistry.metersBetween(c1.lat, c1.lon, c2.lat, c2.lon)
                    if (d <= zoomMergeM) {
                        val nextMass = c1.totalMass + c2.totalMass
                        c1.lat = ((c1.lat * c1.totalMass) + (c2.lat * c2.totalMass)) / nextMass
                        c1.lon = ((c1.lon * c1.totalMass) + (c2.lon * c2.totalMass)) / nextMass
                        c1.totalMass = nextMass
                        c1.nodes.addAll(c2.nodes)
                        if (c2.radiusM > 0.0 && (c1.radiusM == 0.0 || c2.radiusM < c1.radiusM)) {
                            c1.radiusM = c2.radiusM
                        }
                        clusters.removeAt(j)
                    } else {
                        j++
                    }
                }
                i++
            }
        } else {
            // When zoomed way in (zoom >= 15.5, zoomMergeM == 0.0), split co-located pucks (< 4.5m apart)
            // onto a small 5.5m radial ring so every probable agent has its own visible, tappable puck.
            val visited = BooleanArray(clusters.size)
            for (idx in clusters.indices) {
                if (visited[idx]) continue
                val coloc = mutableListOf(idx)
                visited[idx] = true
                for (jdx in idx + 1 until clusters.size) {
                    if (visited[jdx]) continue
                    val d = DeviceRegistry.metersBetween(
                        clusters[idx].lat, clusters[idx].lon,
                        clusters[jdx].lat, clusters[jdx].lon
                    )
                    if (d < 4.5) {
                        coloc += jdx
                        visited[jdx] = true
                    }
                }
                if (coloc.size > 1) {
                    val baseLat = coloc.sumOf { clusters[it].lat } / coloc.size
                    val baseLon = coloc.sumOf { clusters[it].lon } / coloc.size
                    val ringR = 5.5
                    for ((k, cIdx) in coloc.withIndex()) {
                        val bearing = 360.0 * k / coloc.size
                        val (offLat, offLon) = MapGeo.offset(baseLat, baseLon, ringR, bearing)
                        clusters[cIdx].lat = offLat
                        clusters[cIdx].lon = offLon
                    }
                }
            }
        }

        // Merge nearby visible Facility Locations (Stations) into overlapping/adjacent detection centroids
        // so the centroid shows the Station's icon above and lists the Station(s) FIRST in the comma-separated label!
        val mergedStationIds = HashSet<String>()
        if (!tripMode && zoomMergeM > 0.0 && clusters.isNotEmpty() &&
            org.tucsoncommunity.kokoho.posture.KokohoSignatures.isMapLocationsVisible(activity)
        ) {
            val candidateStations = org.tucsoncommunity.kokoho.posture.KokohoDirectory.visibleLocations(activity).filter { loc ->
                val isFed = loc.locationGroup.equals("FEDERAL", ignoreCase = true) || loc.agency.isDhs
                if (isFed) zoom >= 7.0 else zoom >= 10.5
            }
            for (loc in candidateStations) {
                var bestCluster: MergedCluster? = null
                var bestDist = Double.MAX_VALUE
                for (c in clusters) {
                    val thresholdM = kotlin.math.max(zoomMergeM, c.radiusM.coerceAtMost(150.0))
                    val d = DeviceRegistry.metersBetween(loc.lat, loc.lon, c.lat, c.lon)
                    if (d <= thresholdM && d < bestDist) {
                        bestDist = d
                        bestCluster = c
                    }
                }
                if (bestCluster != null) {
                    val stMass = kotlin.math.max(4.0, bestCluster.totalMass * 0.35)
                    val nextMass = bestCluster.totalMass + stMass
                    bestCluster.lat = ((bestCluster.lat * bestCluster.totalMass) + (loc.lat * stMass)) / nextMass
                    bestCluster.lon = ((bestCluster.lon * bestCluster.totalMass) + (loc.lon * stMass)) / nextMass
                    bestCluster.totalMass = nextMass
                    bestCluster.stations += loc
                    mergedStationIds += loc.id
                }
            }
        }

        if (heatOverlayEnabled) {
            val heatFeatures = windowFilteredThreats.mapNotNull { t ->
                if (t.isAircraft) return@mapNotNull null
                if (org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(t.hardwareIdentifier, t.title)) return@mapNotNull null
                val lat = t.lat ?: return@mapNotNull null
                val lon = t.lon ?: return@mapNotNull null
                val mass = (t.sightingCount.coerceAtLeast(1) * t.evidenceCount.coerceAtLeast(1)).toDouble()
                val weight = (0.65 + 0.35 * kotlin.math.ln(1.0 + mass)).coerceIn(0.65, 2.8)
                GeoJson.feature(GeoJson.point(lat, lon), "weight" to weight, "agency" to t.agency.id)
            }
            set(HEAT, heatFeatures)
        } else {
            set(HEAT, emptyList())
        }
        drawLocations(excludedIds = mergedStationIds)

        val shown = clusters.map { c ->
            val sortedStations = c.stations
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<org.tucsoncommunity.kokoho.posture.KokohoDirectory.FacilityLocation> { it.agency.priorityRank }
                        .thenBy { it.title }
                )
            val leadStation = sortedStations.firstOrNull()
            val stationIconId = leadStation?.let { st ->
                val isFire = st.locationGroup.equals("FIRE", ignoreCase = true) ||
                    st.title.contains("Fire", ignoreCase = true) ||
                    st.description.contains("Fire", ignoreCase = true)
                if (isFire) "rf-facility-fire" else "rf-facility-${st.agency.id}"
            }

            val threats = c.nodes.map { it.threat }
            val sorted = threats.sortedWith(
                compareBy<org.tucsoncommunity.kokoho.posture.EvaluatedThreat> { it.agency.priorityRank }
                    .thenBy { it.nearestEdgeDistanceM }
            )
            val lead = sorted.first()
            val agenciesInline = when {
                sortedStations.isNotEmpty() -> {
                    val detectionLabels = sorted.map { t ->
                        if (t.label != t.agency.friendlyLabel && t.label.isNotBlank()) t.label else t.friendlyLabel
                    }
                    (sortedStations.map { it.title } + detectionLabels).distinct().joinToString(", ")
                }
                sorted.size == 1 -> lead.title.ifBlank { lead.friendlyLabel }
                else -> {
                    sorted.map { t ->
                        if (t.label != t.agency.friendlyLabel && t.label.isNotBlank()) t.label else t.friendlyLabel
                    }.distinct().joinToString(", ")
                }
            }
            val newestMs = sorted.maxOf { it.lastSeenMs }
            val ageMs = (nowMs - newestMs).coerceAtLeast(0L)
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, newestMs)
            val totalSightings = sorted.sumOf { it.sightingCount.coerceAtLeast(1) }
            val distinctSignals = sorted.sumOf { it.evidenceCount.coerceAtLeast(1) }
            val effRadiusM = if (c.radiusM > 0.0) c.radiusM.toInt() else lead.uncertaintyRadiusM
            val opacity = org.tucsoncommunity.kokoho.posture.KokohoSignatures.mapCorroborationOpacity(
                activity,
                ageMs,
                totalSightings,
                distinctSignals,
                effRadiusM
            ).toDouble()
            val windowExpandedRadiusM = org.tucsoncommunity.kokoho.posture.KokohoSignatures.projectRollingRadiusM(
                timeWindow,
                ageMs,
                effRadiusM,
                isPinpointed = false
            )
            val accText = "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, windowExpandedRadiusM)}"
            val isPinpointed = sorted.all { it.isPinpointed }
            val radius = if (isPinpointed) {
                null
            } else {
                org.tucsoncommunity.kokoho.posture.KokohoSignatures.projectRollingRadiusM(
                    timeWindow,
                    ageMs,
                    c.radiusM.coerceAtLeast(35.0).toInt(),
                    isPinpointed = false
                ).toDouble()
            }
            val fullDist = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatUserDistance(activity, lead.nearestEdgeDistanceM, lead.uncertaintyRadiusM)
            val note = when {
                !lead.isApproximatedPosition ->
                    "Confidence radius ($accText) · Last detected $staleness."
                !isPinpointed ->
                    "Triangulated area ($accText) · Last detected $staleness."
                else ->
                    "Pinpointed position · Last detected $staleness."
            }
            val stationLines = sortedStations.joinToString("\n") { st ->
                "• Station: ${st.title} (${st.agency.friendlyLabel})"
            }
            val detectionLines = sorted.joinToString("\n") { t ->
                val st = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
                val nameStr = if (t.label != t.agency.friendlyLabel && t.label.isNotBlank()) "${t.friendlyLabel} (${t.label})" else t.friendlyLabel
                "• $nameStr: ${t.title} ($st)"
            }
            val unitLines = listOf(stationLines, detectionLines).filter { it.isNotBlank() }.joinToString("\n")
            val hasVehicleOuiOrKit = sorted.any { u ->
                u.evidenceSources.any {
                    it.facetKey.startsWith("hw:mac:", ignoreCase = true) ||
                        it.kind == "WIFI_BLE_OUI" ||
                        it.kind.startsWith("COMPANION_")
                }
            }
            val badgeKind = when {
                lead.isDrone -> MapIcons.BadgeIconKind.DRONE
                lead.agency == org.tucsoncommunity.kokoho.posture.KokohoAgency.DHS_AMO -> {
                    if (org.tucsoncommunity.kokoho.posture.KokohoSignatures.isHelicopterAirframe(null, lead.agency.id, lead.title, lead.agency)) {
                        MapIcons.BadgeIconKind.HELICOPTER
                    } else {
                        MapIcons.BadgeIconKind.SMALL_PLANE
                    }
                }
                hasVehicleOuiOrKit -> MapIcons.BadgeIconKind.CAR
                else -> MapIcons.BadgeIconKind.RADIO
            }
            // Compute colocated agency gravity weights relative to cluster's center of gravity (c.lat, c.lon)
            val colocationScaleM = kotlin.math.max(100.0, kotlin.math.max(zoomMergeM * 0.75, c.radiusM * 0.85))
            val agencyGravity = mutableMapOf<org.tucsoncommunity.kokoho.posture.KokohoAgency, Double>()
            for (st in sortedStations) {
                agencyGravity[st.agency] = (agencyGravity[st.agency] ?: 0.0) + 3.0
            }
            for (n in c.nodes) {
                val distToCog = DeviceRegistry.metersBetween(n.lat, n.lon, c.lat, c.lon)
                val colocWeight = n.mass * kotlin.math.exp(-(distToCog * distToCog) / (2.0 * colocationScaleM * colocationScaleM))
                agencyGravity[n.threat.agency] = (agencyGravity[n.threat.agency] ?: 0.0) + colocWeight
            }
            val mixedColorArgb = org.tucsoncommunity.kokoho.posture.KokohoSignatures.mixAgencyColorsArgb(agencyGravity)
            // Always render center icon when a Station is merged into the centroid, when zoomed in (>= 15.5), or when approximated/pinpointed
            val showCenterDot = stationIconId != null || zoomMergeM <= 0.0 || isPinpointed || sorted.any { it.isApproximatedPosition }
            val countTag = if (sorted.size > 1) " (${sorted.size} agents)" else ""
            MapPin(
                mac = "kokoho:${lead.uuid}",
                lat = c.lat,
                lon = c.lon,
                label = "$agenciesInline$countTag · $accText · $staleness",
                flagged = true,
                color = mixedColorArgb,
                details = "${lead.agency.friendlyLabel} · ${lead.posture.badgeLabel}\n$fullDist · Last seen $staleness\n$unitLines",
                radiusM = radius,
                whereNote = note,
                showDot = showCenterDot,
                opacity = opacity,
                iconKind = badgeKind,
                agencyId = (leadStation?.agency ?: lead.agency).id,
                customIconId = stationIconId,
                groupFacilities = sortedStations,
                groupMembers = sorted
            )
        }
        val dp = activity.resources.displayMetrics.density
        val droneDomePins = unpinpointedDrones.map { t ->
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val ageMs = (nowMs - t.lastSeenMs).coerceAtLeast(0L)
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
            val baseR = t.mapCircleRadiusM.coerceAtLeast(30)
            val expandedR = org.tucsoncommunity.kokoho.posture.KokohoSignatures.projectRollingRadiusM(
                timeWindow,
                ageMs,
                baseR,
                isPinpointed = false
            )
            val opacity = org.tucsoncommunity.kokoho.posture.KokohoSignatures.mapCorroborationOpacity(
                activity,
                ageMs,
                t.sightingCount.coerceAtLeast(1),
                t.evidenceCount.coerceAtLeast(1),
                baseR
            ).toDouble()
            val accText = "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, expandedR)}"
            val fullDist = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatUserDistance(activity, t.nearestEdgeDistanceM, t.uncertaintyRadiusM)
            val titleInline = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
                .sanitizeDroneOperatorSuffix(t.title.ifBlank { t.friendlyLabel })
            MapPin(
                mac = "kokoho:${t.uuid}",
                lat = lat,
                lon = lon,
                label = "$titleInline · $accText · $staleness",
                flagged = true,
                color = t.agency.mapColorArgb,
                details = "${t.agency.friendlyLabel} · ${t.posture.badgeLabel}\n$fullDist · Last seen $staleness",
                radiusM = expandedR.toDouble(),
                whereNote = "Unpinpointed sUAS airspace volume ($accText, <=400 ft AGL) · Last detected $staleness.",
                showDot = zoomMergeM <= 0.0 || t.isApproximatedPosition,
                opacity = opacity,
                iconKind = MapIcons.BadgeIconKind.DRONE,
                agencyId = t.agency.id,
                groupMembers = listOf(t),
                isDroneDome = true
            )
        }
        val aircraftDomePins = unpinpointedAirThreats.map { t ->
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val ageMs = (nowMs - t.lastSeenMs).coerceAtLeast(0L)
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
            val baseR = t.mapCircleRadiusM.coerceAtLeast(100)
            val expandedR = org.tucsoncommunity.kokoho.posture.KokohoSignatures.projectRollingRadiusM(
                timeWindow,
                ageMs,
                baseR,
                isPinpointed = false
            )
            val opacity = org.tucsoncommunity.kokoho.posture.KokohoSignatures.mapCorroborationOpacity(
                activity,
                ageMs,
                t.sightingCount.coerceAtLeast(1),
                t.evidenceCount.coerceAtLeast(1),
                baseR
            ).toDouble()
            val accText = "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, expandedR)}"
            val fullDist = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatUserDistance(activity, t.nearestEdgeDistanceM, t.uncertaintyRadiusM)
            val isHeli = t.isHelicopter || org.tucsoncommunity.kokoho.posture.KokohoSignatures.isHelicopterAirframe(
                null,
                t.agency.id,
                t.title,
                t.agency,
                t.aircraftHex
            )
            val kind = if (isHeli) MapIcons.BadgeIconKind.HELICOPTER else MapIcons.BadgeIconKind.SMALL_PLANE
            val cirTag = if (t.isCircling) " · CIRCLING" else ""
            val altTag = t.altitudeFtAgl?.takeIf { it > 0 }?.let { " · ${it} ft" }.orEmpty()
            val titleInline = t.label.ifBlank { t.title.ifBlank { t.friendlyLabel } }
            val ceilingDesc = t.altitudeFtAgl?.takeIf { it > 0 }?.let { "${it} ft MSL" } ?: "<=8,000 ft AGL"
            MapPin(
                mac = "kokoho:${t.uuid}",
                lat = lat,
                lon = lon,
                label = "$titleInline$cirTag$altTag · $accText · $staleness",
                flagged = true,
                color = t.agency.mapColorArgb,
                details = "${t.agency.friendlyLabel} · ${t.posture.badgeLabel}\n$fullDist · Last seen $staleness",
                radiusM = expandedR.toDouble(),
                whereNote = "Unpinpointed aircraft airspace volume ($accText, $ceilingDesc) · Last detected $staleness.",
                showDot = true,
                opacity = opacity,
                iconKind = kind,
                agencyId = t.agency.id,
                groupMembers = listOf(t),
                isDroneDome = true
            )
        }
        val allShownPins = shown + droneDomePins + aircraftDomePins
        drawPins(allShownPins)

        wazeShown = emptyMap()
        set(WAZE, emptyList())
        set(WIGLE, emptyList())
        set(ASKED, emptyList())

        // Pinpointed Pro/Enterprise/RemoteID Drones with Altitude and Operator Tether
        val droneThreats = windowFilteredThreats.filter { isPinpointedDrone(it) }
        unpinpointedAirspaceUuids = (unpinpointedDrones.map { it.uuid } + unpinpointedAirThreats.map { it.uuid }).toSet()
        standaloneOperatorUuids = standaloneOperators.map { it.uuid }.toSet()
        val droneThreatsMap = LinkedHashMap<String, org.tucsoncommunity.kokoho.posture.EvaluatedThreat>()
        for (dtItem in (droneThreats + unpinpointedDrones + standaloneOperators)) {
            droneThreatsMap[dtItem.uuid] = dtItem
        }
        drones = emptyMap()
        val droneFeatures = ArrayList<JSONObject>(droneThreats.size + 4)
        for (t in droneThreats) {
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val altPart = t.altitudeFtAgl?.let { "${it} ft AGL" }
                ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.nearestEdgeDistanceM)
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
            val cleanLabel = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.sanitizeDroneOperatorSuffix(t.label)
                .ifBlank { "Tactical Drone" }
            val droneTitlePrefix = if (cleanLabel.contains("drone", ignoreCase = true)) cleanLabel else "Drone · $cleanLabel"
            droneFeatures += GeoJson.feature(
                GeoJson.point(lat, lon),
                "mac" to t.uuid,
                "icon" to "rf-badge-${MapIcons.BadgeIconKind.DRONE.id}-${t.agency.id}",
                "dir" to (t.aircraftTrackDeg ?: 0.0),
                "label" to "$droneTitlePrefix · $altPart · $staleness"
            )
        }
        // Operators NEVER have a bubble: render crisp pilot badges + dashed tethers for pinpointed drones,
        // unpinpointed drones with pilot GPS, standalone `uas:op:*` operator entities, and historical drone tracks.
        val operatorFeatures = ArrayList<JSONObject>(droneThreats.size + standaloneOperators.size + 4)
        val emittedOpUuids = HashSet<String>()
        for (t in (droneThreats + unpinpointedDrones)) {
            val opLat = t.operatorLat ?: continue
            val opLon = t.operatorLon ?: continue
            if (emittedOpUuids.add(t.uuid)) {
                operatorFeatures += GeoJson.feature(GeoJson.point(opLat, opLon), "mac" to t.uuid, "label" to "Drone Pilot")
            }
        }
        for (op in standaloneOperators) {
            val opLat = op.operatorLat ?: op.lat ?: continue
            val opLon = op.operatorLon ?: op.lon ?: continue
            if (emittedOpUuids.add(op.uuid)) {
                operatorFeatures += GeoJson.feature(GeoJson.point(opLat, opLon), "mac" to op.uuid, "label" to "Drone Pilot")
            }
        }
        val droneLineFeatures = ArrayList<JSONObject>(droneThreats.size + 4)
        for (t in (droneThreats + unpinpointedDrones)) {
            val lat = t.lat ?: continue
            val lon = t.lon ?: continue
            val opLat = t.operatorLat ?: continue
            val opLon = t.operatorLon ?: continue
            droneLineFeatures += GeoJson.feature(GeoJson.line(listOf(opLat to opLon, lat to lon)), "mac" to t.uuid)
        }

        // Pinpointed ADS-B & Circling Spy Planes (unpinpointed aircraft render in DRONE_DOME_2D / 3D spheres)
        airThreatsByUuid = (pinpointedAirThreats + unpinpointedAirThreats).associateBy { it.uuid }
        set(AIRCRAFT, pinpointedAirThreats.map { t ->
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val altOrDist = t.altitudeFtAgl?.let { "${it} ft" }
                ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.nearestEdgeDistanceM)
            val cirTag = if (t.isCircling) " · CIRCLING" else ""
            val kind = if (t.isHelicopter) MapIcons.BadgeIconKind.HELICOPTER else MapIcons.BadgeIconKind.SMALL_PLANE
            GeoJson.feature(
                GeoJson.point(lat, lon),
                "hex" to t.uuid,
                "icon" to "rf-badge-${kind.id}-${t.agency.id}",
                "track" to (if (t.isHelicopter) 0.0 else (t.aircraftTrackDeg ?: 0.0)),
                "color" to t.agency.mapColorHex,
                "flagged" to true,
                "clear" to true,
                "label" to "${t.label}$cirTag · $altOrDist"
            )
        })

        // Build 3D airborne overlay scene (pinpointed aircraft/drone pucks + unpinpointed 3D drone & aircraft spheres)
        val airborneTargets = ArrayList<Airborne3dOverlayView.AirborneTarget>(pinpointedAirThreats.size + droneThreats.size)
        for (t in pinpointedAirThreats) {
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val altOrDist = t.altitudeFtAgl?.let { "${it} ft" }
                ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.nearestEdgeDistanceM)
            val cirTag = if (t.isCircling) " · CIRCLING" else ""
            val kind = if (t.isHelicopter) MapIcons.BadgeIconKind.HELICOPTER else MapIcons.BadgeIconKind.SMALL_PLANE
            val iconId = ensureBadgeIcon(kind, t.agency.mapColorArgb, t.agency.id)
            val bmp = badgeBitmapsById[iconId] ?: MapIcons.classificationBadgeIcon(dp, kind, t.agency.mapColorArgb).also {
                badgeBitmapsById[iconId] = it
            }
            airborneTargets += Airborne3dOverlayView.AirborneTarget(
                entityUuid = t.uuid,
                lat = lat,
                lon = lon,
                isAircraft = true,
                isDrone = false,
                rotateWithMap = !t.isHelicopter,
                trackDeg = if (t.isHelicopter) 0.0 else (t.aircraftTrackDeg ?: 0.0),
                altitudeFtRaw = t.altitudeFtAgl,
                colorArgb = t.agency.mapColorArgb,
                badgeBitmap = bmp,
                label = "${t.label}$cirTag · $altOrDist"
            )
        }
        for (t in droneThreats) {
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val altPart = t.altitudeFtAgl?.let { "${it} ft AGL" }
                ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.nearestEdgeDistanceM)
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
            val cleanLabel = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.sanitizeDroneOperatorSuffix(t.label)
            val iconId = ensureBadgeIcon(MapIcons.BadgeIconKind.DRONE, t.agency.mapColorArgb, t.agency.id)
            val bmp = badgeBitmapsById[iconId] ?: MapIcons.classificationBadgeIcon(dp, MapIcons.BadgeIconKind.DRONE, t.agency.mapColorArgb).also {
                badgeBitmapsById[iconId] = it
            }
            airborneTargets += Airborne3dOverlayView.AirborneTarget(
                entityUuid = t.uuid,
                lat = lat,
                lon = lon,
                isAircraft = false,
                isDrone = true,
                rotateWithMap = false,
                trackDeg = t.aircraftTrackDeg ?: 0.0,
                altitudeFtRaw = t.altitudeFtAgl,
                colorArgb = t.agency.mapColorArgb,
                badgeBitmap = bmp,
                label = "$cleanLabel · $altPart · $staleness",
                operatorLat = t.operatorLat,
                operatorLon = t.operatorLon
            )
        }
        val unpinpointedDronesByUuid = unpinpointedDrones.associateBy { it.uuid }
        val droneDomes = droneDomePins.map { dpPin ->
            val rawUuid = dpPin.mac.removePrefix("kokoho:")
            val srcDrone = unpinpointedDronesByUuid[rawUuid]
            val iconId = ensureBadgeIcon(MapIcons.BadgeIconKind.DRONE, dpPin.color, dpPin.agencyId)
            val bmp = badgeBitmapsById[iconId] ?: MapIcons.classificationBadgeIcon(dp, MapIcons.BadgeIconKind.DRONE, dpPin.color).also {
                badgeBitmapsById[iconId] = it
            }
            Airborne3dOverlayView.DroneAirspaceDome(
                entityUuid = rawUuid,
                lat = dpPin.lat,
                lon = dpPin.lon,
                radiusM = (dpPin.radiusM ?: 60.0).coerceAtLeast(25.0),
                colorArgb = dpPin.color,
                opacity = dpPin.opacity.toFloat(),
                badgeBitmap = bmp,
                label = dpPin.label,
                maxCeilingM = TerrainElevationSampler.MAX_DRONE_AIRSPACE_M,
                isAircraft = false,
                operatorLat = srcDrone?.operatorLat,
                operatorLon = srcDrone?.operatorLon
            )
        }
        val unpinpointedAirByUuid = unpinpointedAirThreats.associateBy { it.uuid }
        val aircraftDomes = aircraftDomePins.map { airPin ->
            val rawUuid = airPin.mac.removePrefix("kokoho:")
            val srcAir = unpinpointedAirByUuid[rawUuid]
            val iconId = ensureBadgeIcon(airPin.iconKind, airPin.color, airPin.agencyId)
            val bmp = badgeBitmapsById[iconId] ?: MapIcons.classificationBadgeIcon(dp, airPin.iconKind, airPin.color).also {
                badgeBitmapsById[iconId] = it
            }
            val maxCeilingM = if (srcAir?.altitudeFtAgl != null && srcAir.altitudeFtAgl > 0) {
                val groundElevM = TerrainElevationSampler.sampleElevationM(activity, airPin.lat, airPin.lon)
                TerrainElevationSampler.computeAirborneHeightAboveGroundM(
                    isAircraft = true,
                    altitudeFtRaw = srcAir.altitudeFtAgl,
                    groundElevM = groundElevM
                )
            } else {
                TerrainElevationSampler.MAX_AIRCRAFT_AIRSPACE_M
            }
            Airborne3dOverlayView.DroneAirspaceDome(
                entityUuid = rawUuid,
                lat = airPin.lat,
                lon = airPin.lon,
                radiusM = (airPin.radiusM ?: 800.0).coerceAtLeast(65.0),
                colorArgb = airPin.color,
                opacity = airPin.opacity.toFloat(),
                badgeBitmap = bmp,
                label = airPin.label,
                maxCeilingM = maxCeilingM,
                isAircraft = true
            )
        }
        val standaloneOpPins = standaloneOperators.mapNotNull { op ->
            val opLat = op.operatorLat ?: op.lat ?: return@mapNotNull null
            val opLon = op.operatorLon ?: op.lon ?: return@mapNotNull null
            Airborne3dOverlayView.StandaloneOperatorPin(op.uuid, opLat, opLon)
        }
        val selectedEntityId = (activeSelection as? MapSelection.Detection)?.entityUuid
        airborneOverlay?.updateScene(
            airborneTargets,
            droneDomes + aircraftDomes,
            selectedEntityId,
            cameraTopPaddingPx,
            standaloneOpPins
        )
        syncAirbornePitchState(cameraTopPaddingPx)

        // Chaikin-smoothed aircraft & pinpoint drone flight tracks strictly filtered and vertex-clipped
        // to `[nowMs - windowMs, nowMs]` across all rolling map time windows (`30m`, `3h`, `24h`, `1wk`, `1mo`).
        val trackFeatures = mutableListOf<JSONObject>()
        val trackWindowMs = timeWindow.windowMs
        val cachedTracks = org.tucsoncommunity.kokoho.posture.KokohoHubClient.cachedFlightTracks()
        val enabledAirAgencies = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getEnabledAircraftAgencies(activity)
        val enabledGroundAgencies = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getEnabledAgencies(activity)
        val liveAirAndDroneEntities = (pinpointedAirThreats.map { it.uuid } + droneThreats.map { it.uuid }).toSet()
        val coveredEntities = HashSet<String>()
        val activeTracksByEntity = LinkedHashMap<String, org.tucsoncommunity.kokoho.posture.KokohoHubClient.RemoteFlightTrack>()

        fun trackMatchesConfirmFilter(entityUuid: String): Boolean {
            return when (confirmFilter) {
                org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapConfirmationFilter.ALL -> true
                org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapConfirmationFilter.CONFIRMED -> {
                    if (entityUuid.isBlank()) false
                    else {
                        val matched = snap.mapThreats.firstOrNull { it.uuid == entityUuid }
                            ?: org.tucsoncommunity.kokoho.posture.KokohoEntityLedger.findByUuid(activity, entityUuid)
                        if (matched != null) {
                            org.tucsoncommunity.kokoho.posture.KokohoSignatures.hasApprovedWitnessReport(activity, matched)
                        } else {
                            org.tucsoncommunity.kokoho.posture.KokohoSignatures.hasApprovedWitnessReportForUuid(activity, entityUuid)
                        }
                    }
                }
                org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapConfirmationFilter.UNCONFIRMED -> {
                    if (entityUuid.isBlank()) true
                    else {
                        val matched = snap.mapThreats.firstOrNull { it.uuid == entityUuid }
                            ?: org.tucsoncommunity.kokoho.posture.KokohoEntityLedger.findByUuid(activity, entityUuid)
                        val confirmed = if (matched != null) {
                            org.tucsoncommunity.kokoho.posture.KokohoSignatures.hasApprovedWitnessReport(activity, matched)
                        } else {
                            org.tucsoncommunity.kokoho.posture.KokohoSignatures.hasApprovedWitnessReportForUuid(activity, entityUuid)
                        }
                        !confirmed
                    }
                }
            }
        }

        for (tr in cachedTracks) {
            if (!trackMatchesConfirmFilter(tr.entityUuid)) continue
            val ag = org.tucsoncommunity.kokoho.posture.KokohoAgency.fromCode(tr.category)
                ?: org.tucsoncommunity.kokoho.posture.KokohoAgency.DHS_AMO
            val allowed = when {
                ag == org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE -> ag in enabledGroundAgencies
                ag.isDhs -> enabledAirAgencies.any { it.isDhs }
                else -> ag in enabledAirAgencies
            }
            if (!allowed) continue
            val clippedPts = tr.clippedPointsForWindow(nowMs, trackWindowMs)
            if (clippedPts.size < 2) continue
            val smoothed = PoliceAircraft.chaikinSmooth(clippedPts, 2)
            val isLive = tr.entityUuid in liveAirAndDroneEntities
            if (tr.entityUuid.isNotBlank()) {
                coveredEntities += tr.entityUuid
                val prev = activeTracksByEntity[tr.entityUuid]
                if (prev == null || tr.endedAtMs >= prev.endedAtMs) {
                    activeTracksByEntity[tr.entityUuid] = tr
                }
            }
            trackFeatures += GeoJson.feature(
                GeoJson.line(smoothed),
                "hex" to tr.entityUuid,
                "color" to ag.mapColorHex,
                "opacity" to if (isLive) 0.88 else 0.58
            )
            // Always emit a Drone badge at the latest clipped vertex for historical drone tracks so drone vs. pilot is clear
            if (ag == org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE &&
                tr.entityUuid.isNotBlank() &&
                tr.entityUuid !in liveAirAndDroneEntities &&
                tr.entityUuid !in unpinpointedAirspaceUuids
            ) {
                val endPt = clippedPts.last()
                val cleanTrkLbl = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
                    .sanitizeDroneOperatorSuffix(tr.label)
                    .ifBlank { "Tactical Drone" }
                val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, tr.endedAtMs.takeIf { it > 0L } ?: nowMs)
                val prefix = if (cleanTrkLbl.contains("drone", ignoreCase = true)) cleanTrkLbl else "Drone · $cleanTrkLbl"
                droneFeatures += GeoJson.feature(
                    GeoJson.point(endPt.first, endPt.second),
                    "mac" to tr.entityUuid,
                    "icon" to "rf-badge-${MapIcons.BadgeIconKind.DRONE.id}-${ag.id}",
                    "dir" to 0.0,
                    "label" to "$prefix · $ageStr"
                )
                val histLedgerDrone = org.tucsoncommunity.kokoho.posture.KokohoEntityLedger.findByUuid(activity, tr.entityUuid)
                if (histLedgerDrone != null) {
                    droneThreatsMap.putIfAbsent(tr.entityUuid, histLedgerDrone)
                    val opLat = histLedgerDrone.operatorLat
                    val opLon = histLedgerDrone.operatorLon
                    if (opLat != null && opLon != null && emittedOpUuids.add(tr.entityUuid)) {
                        operatorFeatures += GeoJson.feature(
                            GeoJson.point(opLat, opLon),
                            "mac" to tr.entityUuid,
                            "label" to "Drone Pilot"
                        )
                        droneLineFeatures += GeoJson.feature(
                            GeoJson.line(listOf(opLat to opLon, endPt.first to endPt.second)),
                            "mac" to tr.entityUuid
                        )
                    }
                }
            }
        }
        flightTracksByEntityUuid = activeTracksByEntity

        // Local ADS-B trails within `[nowMs - trackWindowMs, nowMs]` for pinpointed aircraft not already covered by Hub tracks
        for (t in pinpointedAirThreats) {
            if (t.uuid in coveredEntities) continue
            val hexKey = t.aircraftHex ?: continue
            val localPts = PoliceAircraft.recentAirborneTrail(
                hex = hexKey,
                nowMs = nowMs,
                maxAgeMs = trackWindowMs,
                includeLandedWithinWindow = true
            )
            if (localPts.size >= 2) {
                val smoothed = PoliceAircraft.chaikinSmooth(localPts, 2)
                coveredEntities += t.uuid
                trackFeatures += GeoJson.feature(
                    GeoJson.line(smoothed),
                    "hex" to t.uuid,
                    "color" to t.agency.mapColorHex,
                    "opacity" to 0.86
                )
            }
        }

        // Local pinpoint drone trails within `[nowMs - trackWindowMs, nowMs]`
        val localDroneMap = LinkedHashMap<String, PoliceAircraft.LocalDroneTrail>()
        if (org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE in enabledGroundAgencies) {
            for (dt in PoliceAircraft.recentDroneTrailsForWindow(nowMs, trackWindowMs)) {
                if (!trackMatchesConfirmFilter(dt.entityUuid)) continue
                localDroneMap[dt.entityUuid] = dt
                if (dt.entityUuid in coveredEntities) continue
                val smoothed = PoliceAircraft.chaikinSmooth(dt.points, 2)
                val isLive = dt.entityUuid in liveAirAndDroneEntities
                trackFeatures += GeoJson.feature(
                    GeoJson.line(smoothed),
                    "hex" to dt.entityUuid,
                    "color" to org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE.mapColorHex,
                    "opacity" to if (isLive) 0.86 else 0.56
                )
                if (!isLive && dt.entityUuid !in unpinpointedAirspaceUuids && dt.points.isNotEmpty()) {
                    val endPt = dt.points.last()
                    val cleanDtLbl = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
                        .sanitizeDroneOperatorSuffix(dt.label)
                        .ifBlank { "Tactical Drone" }
                    val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, dt.endedAtMs)
                    val prefix = if (cleanDtLbl.contains("drone", ignoreCase = true)) cleanDtLbl else "Drone · $cleanDtLbl"
                    droneFeatures += GeoJson.feature(
                        GeoJson.point(endPt.first, endPt.second),
                        "mac" to dt.entityUuid,
                        "icon" to "rf-badge-${MapIcons.BadgeIconKind.DRONE.id}-${org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE.id}",
                        "dir" to 0.0,
                        "label" to "$prefix · $ageStr"
                    )
                }
            }
        }
        droneThreatsByUuid = droneThreatsMap
        set(DRONES, droneFeatures)
        set(DRONE_OPS, operatorFeatures)
        set(DRONE_LINES, droneLineFeatures)
        localDroneTrailsByUuid = localDroneMap
        set(FLIGHT_TRACKS, trackFeatures)

        if (TripRecorder.isRecording) {
            val rawLive = TripRecorder.livePoints()
            val clippedTrace = PoliceAircraft.clipFixesToWindow(
                rawLive.map { PoliceAircraft.FlightFix(it.time, it.lat, it.lon) },
                nowMs,
                trackWindowMs
            )
            drawTrace(clippedTrace, TRACE_COLOR)
        } else {
            val selUuid = selectedFlightTrackEntityUuid
            val selRecord = selectedFlightTrackRecord
            val selClipped = when {
                selUuid != null && !trackMatchesConfirmFilter(selUuid) -> emptyList()
                selRecord != null -> selRecord.clippedPointsForWindow(nowMs, trackWindowMs)
                selUuid != null -> {
                    val liveAir = airThreatsByUuid[selUuid]
                    val hubTr = flightTracksByEntityUuid[selUuid]
                    when {
                        hubTr != null -> hubTr.clippedPointsForWindow(nowMs, trackWindowMs)
                        liveAir?.aircraftHex != null -> PoliceAircraft.recentAirborneTrail(
                            hex = liveAir.aircraftHex,
                            nowMs = nowMs,
                            maxAgeMs = trackWindowMs,
                            includeLandedWithinWindow = true
                        )
                        localDroneMap[selUuid] != null -> localDroneMap[selUuid]!!.points
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
            if (selClipped.size >= 2) {
                drawTrace(PoliceAircraft.chaikinSmooth(selClipped, 2), selectedFlightTrackColorHex, selUuid)
            } else {
                drawTrace(emptyList(), TRACE_COLOR)
            }
        }
        // Keep selection ring synced with moved entity coordinates if an entity is currently selected
        val curSel = activeSelection
        if (curSel is MapSelection.Detection) {
            val updated = snap.mapThreats.firstOrNull { it.uuid.equals(curSel.entityUuid, ignoreCase = true) }
            if (updated?.lat != null && updated.lon != null) {
                val isPilotSelection = isStandaloneDroneOperator(updated) || curSel.title.startsWith("Drone Pilot", ignoreCase = true)
                val baseTitle = if (updated.isDrone || isPilotSelection) {
                    org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.sanitizeDroneOperatorSuffix(
                        updated.title.ifBlank { curSel.title }
                    )
                } else {
                    updated.title.ifBlank { curSel.title }
                }
                val cleanTitle = if (curSel.title.startsWith("Drone Pilot ·", ignoreCase = true) && !baseTitle.startsWith("Drone Pilot", ignoreCase = true)) {
                    "Drone Pilot · ${baseTitle.ifBlank { "Tactical Drone" }}"
                } else {
                    baseTitle
                }
                activeSelection = curSel.copy(
                    title = cleanTitle,
                    lat = (if (isPilotSelection) (updated.operatorLat ?: updated.lat) else updated.lat) ?: curSel.lat,
                    lon = (if (isPilotSelection) (updated.operatorLon ?: updated.lon) else updated.lon) ?: curSel.lon,
                    radiusM = if (isPilotSelection) null else updated.uncertaintyRadiusM
                )
            }
        }
        drawSelection(activeSelection)
        return allShownPins
    }

    /** Networks pinned from WiGLE searches (the All and WiFi views), when they changed. */
    fun drawWigle() {
        if (!ready()) return
        val key = "${WiglePins.version}|$filter|$tripMode"
        if (key == wigleDrawn) return
        wigleDrawn = key
        val show = !tripMode && (filter == DeviceFilter.ALL || filter == DeviceFilter.WIFI)
        val list = if (show) WiglePins.all(activity) else emptyList()
        wigle = list.associateBy { it.net.bssid }
        set(WIGLE, list.map { p ->
            GeoJson.feature(GeoJson.point(p.net.lat, p.net.lon), "id" to p.net.bssid, "label" to (p.net.ssid ?: p.net.bssid).take(32))
        })
    }

    /** Where the network picked in the requested-networks list was asked for, when it changed. */
    fun drawAsked() {
        if (!ready()) return
        val ssid = ProbeLog.onMap
        val key = "${ProbeLog.version}|$ssid|$tripMode"
        if (key == askedDrawn) return
        askedDrawn = key
        val spots = if (ssid == null || tripMode) emptyList() else ProbeLog.network(activity, ssid)?.spots.orEmpty()
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        asked = spots.withIndex().associate { (i, s) -> "asked-$i" to s }
        set(ASKED, asked.map { (id, s) ->
            GeoJson.feature(GeoJson.point(s.lat, s.lon), "id" to id, "label" to df.format(java.util.Date(s.at)))
        })
    }

    /**
     * Cameras, CCTV and cell towers: many, and they don't move - drawn for the area in view (and
     * around it), again once the view leaves that area or every 15 s for new downloads.
     */
    fun maybeRedrawStatic(force: Boolean = false) {
        if (!ready()) return
        if (!staticCleared || force) {
            clearStatic()
            staticCleared = true
        }
    }

    private fun clearStatic() {
        listOf(CAMERAS, CAM_CONES, CCTV, CONES, TOWERS).forEach { set(it, emptyList()) }
        cameras = emptyMap(); cctvs = emptyMap(); towers = emptyMap(); staticBox = null
    }

    /** The CCTV layer's state for the status line, as before; [view] null = the layer is off. */
    private fun updateCctvLine(view: MapEngine.Box?) {
        if (!Prefs.showCctv(activity) || tripMode) { cctvLine = null; return }
        val store = CctvStore
        if (view == null) {
            cctvLine = store.progress?.let { "CCTV: downloading around you, $it" }
            return
        }
        val private = Prefs.cctvPrivate(activity)
        fun inView(c: org.tucsoncommunity.kokoho.alpr.CctvCamera) = view.contains(c.lat, c.lon)
        val shown = cctvs.values.count { inView(it) }
        val hiddenPrivate = if (private) 0 else store.cameras.count { it.isPrivate && inView(it) }
        cctvLine = when {
            map.camera.zoom < CCTV_MIN_ZOOM -> store.progress?.let { "CCTV: downloading around you, $it" }
            store.progress != null -> "CCTV: downloading around you, ${store.progress}"
            store.isBusy -> "CCTV: downloading..."
            System.currentTimeMillis() - store.lastFailureAt < 60_000 -> "CCTV: map server busy - retrying"
            store.missingAreas > 0 && shown > 0 -> "CCTV: $shown in view - ${store.missingAreas} area(s) around you still missing (servers busy), retried next time"
            shown == 0 && store.needsDownload(view.south, view.west, view.north, view.east) -> "CCTV: downloading..."
            shown == 0 && hiddenPrivate > 0 -> "CCTV: only private ones here ($hiddenPrivate) - Settings > Known cameras > Include private"
            shown == 0 -> null // nothing to say: no banner
            else -> "CCTV: $shown in view" + if (hiddenPrivate > 0) " (+$hiddenPrivate private hidden)" else ""
        }
    }

    /**
     * Fetches the plate / speed cameras of the area in view when they aren't cached yet, and the CCTV
     * around you and in view; at most every 20 s so following you at speed doesn't hammer the servers.
     */
    private fun maybeFetchCameras(view: MapEngine.Box) {
        val now = System.currentTimeMillis()
        val span = AlprStore.MAX_SPAN_DEG
        val small = view.north - view.south <= span && view.east - view.west <= span
        if (Prefs.showCctv(activity) && now - lastCctvFetch > 20_000 && !CctvStore.isBusy) {
            lastCctvFetch = now
            // What's on screen first; the area around you (many tiles, minutes) only once that is there.
            // (Unless the servers just refused it: then the next tile around you is worth a try meanwhile.)
            if (small && map.camera.zoom >= CCTV_MIN_ZOOM && now - CctvStore.lastFailureAt > 60_000 &&
                CctvStore.needsDownload(view.south, view.west, view.north, view.east)) {
                activity.lifecycleScope.launch {
                    val changed = runCatching { CctvStore.autoDownload(activity, view.south, view.west, view.north, view.east) }.getOrDefault(false)
                    if (changed) lastCctvFetch = 0L // straight on to the area around you
                    safe("static") { maybeRedrawStatic(force = true); onStaticChanged?.invoke() }
                }
            } else {
                val here = ScanForegroundService.lastFix
                if (here != null) CctvStore.ensureAround(activity, here.latitude, here.longitude, Prefs.cameraRadiusKm(activity).toDouble())
            }
        }
        if (now - lastCameraFetch < 20_000) return
        if (!Prefs.autoCameras(activity) || !Prefs.showKnownAlpr(activity) || AlprStore.isBusy || !small) return
        lastCameraFetch = now
        activity.lifecycleScope.launch {
            val changed = runCatching {
                AlprStore.autoDownload(activity, view.south, view.west, view.north, view.east)
            }.getOrDefault(false)
            if (changed) safe("static") { maybeRedrawStatic(force = true); onStaticChanged?.invoke() }
        }
    }

    /** What's under the finger: the topmost kind of thing wins; several devices at one spot give a list. */
    fun onTap(x: Float, y: Float, me: Pair<Double, Double>?) {
        val r = 22f * activity.resources.displayMetrics.density
        // When 3D airborne projection is active (`pitch > 6 deg`), hit-test elevated 3D aircraft & drone pucks first!
        val hit3d = airborneOverlay?.hitTest(x, y, r)
        if (hit3d != null) {
            safe("tap3d") {
                if (hit3d.isPilotHit) {
                    selectOrOpenPilotUuid(hit3d.entityUuid, hit3d.lat, hit3d.lon)
                } else {
                    selectOrOpenAirborneUuid(hit3d.entityUuid, hit3d.lat, hit3d.lon, me)
                }
            }
            return
        }
        val tapCoord = map.toLatLon(x, y)
        val box = RectF(x - r, y - r, x + r, y + r)
        val asked = listOf(
            AIRCRAFT to "hex",
            WAZE to "id",
            DRONES to "mac",
            DRONE_OPS to "mac",
            DOTS to "mac",
            "$DRONE_DOME_2D-dots" to "mac",
            "$LOCATIONS-fed" to "id",
            "$LOCATIONS-local" to "id",
            FLIGHT_TRACKS to "hex",
            TRACE to "hex",
            CAMERAS to "id",
            CCTV to "id",
            TOWERS to "id",
            WIGLE to "id",
            ASKED to "id",
            "$DRONE_DOME_2D-fill" to "mac",
            "$PRECISION-fill" to "mac"
        )
        map.query(box, asked) { found ->
            if (activity.isFinishing || activity.isDestroyed) return@query
            safe("tap") { showTapped(found, me, tapCoord) }
        }
    }

    private fun nearestPointOnTrack(
        pts: List<Pair<Double, Double>>,
        tapLat: Double?,
        tapLon: Double?
    ): Pair<Double, Double> {
        if (pts.isEmpty()) return map.camera.lat to map.camera.lon
        if (tapLat == null || tapLon == null) return pts.last()
        return pts.minByOrNull { DeviceRegistry.metersBetween(tapLat, tapLon, it.first, it.second) } ?: pts.last()
    }

    private fun highlightTrackForEntity(entityUuid: String, targetQuery: String, colorHex: String) {
        selectedFlightTrackEntityUuid = entityUuid
        selectedFlightTrackColorHex = colorHex
        val activeWin = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getMapTimeWindow(activity)
        val winMs = activeWin.windowMs
        val nowMs = System.currentTimeMillis()
        flightTracksByEntityUuid[entityUuid]?.let { cached ->
            selectedFlightTrackRecord = cached
            val clipped = cached.clippedPointsForWindow(nowMs, winMs)
            if (clipped.size >= 2 && !TripRecorder.isRecording) {
                drawTrace(PoliceAircraft.chaikinSmooth(clipped, 2), colorHex, entityUuid)
            }
        }
        org.tucsoncommunity.kokoho.posture.KokohoHubClient.fetchFlightTracks(
            context = activity,
            target = targetQuery,
            windowMs = winMs
        ) { tracks ->
            if (activity.isFinishing || activity.isDestroyed) return@fetchFlightTracks
            if (selectedFlightTrackEntityUuid != entityUuid) return@fetchFlightTracks
            val curWin = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getMapTimeWindow(activity)
            val best = tracks.maxByOrNull { it.endedAtMs } ?: return@fetchFlightTracks
            selectedFlightTrackRecord = best
            val clipped = best.clippedPointsForWindow(System.currentTimeMillis(), curWin.windowMs)
            if (!TripRecorder.isRecording) {
                if (clipped.size >= 2) {
                    drawTrace(PoliceAircraft.chaikinSmooth(clipped, 2), colorHex, entityUuid)
                } else {
                    drawTrace(emptyList(), TRACE_COLOR)
                }
            }
        }
    }

    private fun selectOrOpenPilotUuid(
        entityUuid: String,
        tapLat: Double?,
        tapLon: Double?
    ): Boolean {
        val selectCb = onSelectTarget
        val t = droneThreatsByUuid[entityUuid]
            ?: org.tucsoncommunity.kokoho.posture.KokohoEntityLedger.findByUuid(activity, entityUuid)
        if (t != null) {
            highlightTrackForEntity(t.uuid, t.uuid, t.agency.mapColorHex)
            val opLat = t.operatorLat ?: tapLat ?: t.lat ?: map.camera.lat
            val opLon = t.operatorLon ?: tapLon ?: t.lon ?: map.camera.lon
            val droneLat = t.lat
            val droneLon = t.lon
            val distTxt = if (droneLat != null && droneLon != null && !isStandaloneDroneOperator(t)) {
                val distM = DeviceRegistry.metersBetween(opLat, opLon, droneLat, droneLon).toInt()
                " · ${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, distM)} from drone"
            } else {
                ""
            }
            val cleanDroneLbl = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
                .sanitizeDroneOperatorSuffix(t.title.ifBlank { t.label.ifBlank { "Tactical Drone" } })
                .ifBlank { "Tactical Drone" }
            val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(System.currentTimeMillis(), t.lastSeenMs)
            if (selectCb != null) {
                selectCb(
                    MapSelection.Detection(
                        entityUuid = t.uuid,
                        title = "Drone Pilot · $cleanDroneLbl",
                        subtitle = "Ground Controller$distTxt · $ageStr",
                        agencyCode = t.agency.id,
                        lat = opLat,
                        lon = opLon,
                        radiusM = null
                    )
                )
            } else {
                DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
            }
            return true
        }
        return false
    }

    private fun selectOrOpenAirborneUuid(
        entityUuid: String,
        tapLat: Double?,
        tapLon: Double?,
        me: Pair<Double, Double>?
    ): Boolean {
        val selectCb = onSelectTarget
        airThreatsByUuid[entityUuid]?.let { t ->
            val targetHex = t.aircraftHex ?: t.uuid
            highlightTrackForEntity(t.uuid, targetHex, t.agency.mapColorHex)
            if (selectCb != null) {
                selectCb(toDetectionSelection(t, tapLat ?: map.camera.lat, tapLon ?: map.camera.lon))
            } else {
                DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
            }
            return true
        }
        droneThreatsByUuid[entityUuid]?.let { t ->
            if (isStandaloneDroneOperator(t)) {
                return selectOrOpenPilotUuid(entityUuid, tapLat, tapLon)
            }
            highlightTrackForEntity(t.uuid, t.uuid, t.agency.mapColorHex)
            if (selectCb != null) {
                selectCb(toDetectionSelection(t, tapLat ?: map.camera.lat, tapLon ?: map.camera.lon))
            } else {
                DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
            }
            return true
        }
        planes[entityUuid]?.let { s ->
            MapDialogs.showAircraft(activity, s.plane, s.hit, me)
            return true
        }
        flightTracksByEntityUuid[entityUuid]?.let { tr ->
            val ag = org.tucsoncommunity.kokoho.posture.KokohoAgency.fromCode(tr.category)
                ?: org.tucsoncommunity.kokoho.posture.KokohoAgency.DHS_AMO
            highlightTrackForEntity(tr.entityUuid, tr.entityUuid, ag.mapColorHex)
            val ledgerHit = org.tucsoncommunity.kokoho.posture.KokohoEntityLedger.findByUuid(activity, tr.entityUuid)
            if (ledgerHit != null) {
                if (selectCb != null) {
                    val anchor = nearestPointOnTrack(tr.points, tapLat, tapLon)
                    selectCb(toDetectionSelection(ledgerHit, anchor.first, anchor.second))
                } else {
                    DeviceActions.openDetails(activity, "kokoho:${tr.entityUuid}")
                }
                return true
            }
            val nowMs = System.currentTimeMillis()
            val endRef = tr.endedAtMs.takeIf { it > 0L } ?: nowMs
            val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, endRef)
            val anchor = nearestPointOnTrack(tr.points, tapLat, tapLon)
            val cleanRawLbl = if (ag == org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE) {
                org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.sanitizeDroneOperatorSuffix(tr.label)
            } else {
                tr.label
            }
            val displayTitle = cleanRawLbl.ifBlank {
                if (ag == org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE) "Tactical Drone" else "${ag.friendlyLabel} Aircraft"
            }
            val pathSubtitle = if (ag == org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE) {
                "Drone Flight Path · $ageStr"
            } else {
                "${ag.friendlyLabel} · Flight path · $ageStr"
            }
            if (selectCb != null) {
                selectCb(
                    MapSelection.Detection(
                        entityUuid = tr.entityUuid,
                        title = displayTitle,
                        subtitle = pathSubtitle,
                        agencyCode = ag.id,
                        lat = anchor.first,
                        lon = anchor.second,
                        radiusM = 25
                    )
                )
            } else {
                DeviceActions.openDetails(activity, "kokoho:${tr.entityUuid}")
            }
            return true
        }
        localDroneTrailsByUuid[entityUuid]?.let { dt ->
            val ag = org.tucsoncommunity.kokoho.posture.KokohoAgency.fromCode(dt.agencyId)
                ?: org.tucsoncommunity.kokoho.posture.KokohoAgency.DRONE
            selectedFlightTrackEntityUuid = dt.entityUuid
            selectedFlightTrackRecord = null
            selectedFlightTrackColorHex = ag.mapColorHex
            if (!TripRecorder.isRecording && dt.points.size >= 2) {
                drawTrace(PoliceAircraft.chaikinSmooth(dt.points, 2), ag.mapColorHex, dt.entityUuid)
            }
            val anchor = nearestPointOnTrack(dt.points, tapLat, tapLon)
            val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(System.currentTimeMillis(), dt.endedAtMs)
            val cleanDtTitle = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
                .sanitizeDroneOperatorSuffix(dt.label)
                .ifBlank { "Tactical Drone" }
            if (selectCb != null) {
                selectCb(
                    MapSelection.Detection(
                        entityUuid = dt.entityUuid,
                        title = cleanDtTitle,
                        subtitle = "Drone Flight Path · $ageStr",
                        agencyCode = ag.id,
                        lat = anchor.first,
                        lon = anchor.second,
                        radiusM = 20
                    )
                )
            } else {
                DeviceActions.openDetails(activity, "kokoho:${dt.entityUuid}")
            }
            return true
        }
        return false
    }

    private fun toDetectionSelection(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat, fallbackLat: Double, fallbackLon: Double): MapSelection.Detection {
        val nowMs = System.currentTimeMillis()
        val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
        val extra = when {
            isPinpointedAircraft(t) -> t.altitudeFtAgl?.let { "${it} ft" } ?: "ADS-B"
            isUnpinpointedAircraft(t) -> {
                val rStr = "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.uncertaintyRadiusM)}"
                t.altitudeFtAgl?.takeIf { it > 0 }?.let { "$rStr · ${it} ft" } ?: rStr
            }
            isPinpointedDrone(t) -> t.altitudeFtAgl?.let { "${it} ft AGL" } ?: "Remote ID"
            isStandaloneDroneOperator(t) -> "Pilot / Operator GPS"
            else -> "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.uncertaintyRadiusM)}"
        }
        val cleanTitle = if (t.isDrone || isStandaloneDroneOperator(t)) {
            org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.sanitizeDroneOperatorSuffix(t.title.ifBlank { t.friendlyLabel })
        } else {
            t.title.ifBlank { t.friendlyLabel }
        }
        return MapSelection.Detection(
            entityUuid = t.uuid,
            title = cleanTitle,
            subtitle = "${t.agency.friendlyLabel} · $extra · $ageStr",
            agencyCode = t.agency.id,
            lat = (if (isStandaloneDroneOperator(t)) (t.operatorLat ?: t.lat) else t.lat) ?: fallbackLat,
            lon = (if (isStandaloneDroneOperator(t)) (t.operatorLon ?: t.lon) else t.lon) ?: fallbackLon,
            radiusM = if (isStandaloneDroneOperator(t)) null else t.uncertaintyRadiusM
        )
    }

    private fun showTapped(found: List<List<String>>, me: Pair<Double, Double>?, tapCoord: Pair<Double, Double>? = null) {
        val air = found[0]
        val waze = found[1]
        val drone = found[2]
        val op = found[3]
        val dots = (found[4] + found[5]).distinct()
        val locHits = (found[6] + found[7]).distinct()
        val trackHits = (found[8] + found[9]).filter { it.isNotBlank() }.distinct()
        val cams = found[10]
        val cctv = found[11]
        val tower = found[12]
        val wigleHits = found[13]
        val askedHits = found[14]
        val precisionHits = (found[15] + found[16]).distinct()

        val selectCb = onSelectTarget
        val tapLat = tapCoord?.first
        val tapLon = tapCoord?.second

        for (id in air) {
            if (selectOrOpenAirborneUuid(id, tapLat, tapLon, me)) return
        }
        waze.firstNotNullOfOrNull { wazeShown[it] }?.let { MapDialogs.showWaze(activity, it); return }
        wigleHits.firstNotNullOfOrNull { wigle[it] }?.let { p ->
            MapDialogs.showWigle(activity, p, me) { WiglePins.remove(activity, p.net.bssid); drawWigle() }
            return
        }
        askedHits.firstNotNullOfOrNull { asked[it] }?.let { s ->
            val ssid = ProbeLog.onMap ?: return
            MapDialogs.showAsked(activity, ssid, s, me) { ProbeLog.showOnMap(null); drawAsked() }
            return
        }
        for (id in drone) {
            if (selectOrOpenAirborneUuid(id, tapLat, tapLon, me)) return
        }
        for (id in op) {
            if (selectOrOpenPilotUuid(id, tapLat, tapLon)) return
        }
        (drone + op).firstNotNullOfOrNull { drones[it] }?.let { s ->
            DeviceActions.openDetails(activity, s.mac)
            return
        }
        // Check direct puck dots BEFORE facility locations, facility locations BEFORE flight tracks, and flight tracks BEFORE wide precision bubbles!
        val dotPins = dots.distinct().mapNotNull { pins[it] }
        if (dotPins.isNotEmpty()) {
            val allFacilities = dotPins.flatMap { it.groupFacilities }.distinctBy { it.id }
            val allMembers = dotPins.flatMap { it.groupMembers }.distinctBy { it.uuid }
            if (allFacilities.isNotEmpty() || allMembers.size > 1) {
                MapDialogs.showGroupClusterModal(
                    a = activity,
                    facilities = allFacilities,
                    members = allMembers,
                    onPickFacility = { loc ->
                        if (selectCb != null) selectCb(MapSelection.Facility(loc))
                        else DeviceDetailActivity.openLocation(activity, loc.id)
                    },
                    onPickDetection = { chosen ->
                        if (selectCb != null) selectCb(toDetectionSelection(chosen, dotPins.first().lat, dotPins.first().lon))
                        else DeviceActions.openDetails(activity, "kokoho:${chosen.uuid}")
                    }
                )
            } else if (selectCb != null && allMembers.size == 1) {
                selectCb(toDetectionSelection(allMembers.first(), dotPins.first().lat, dotPins.first().lon))
            } else if (dotPins.size > 1) {
                MapDialogs.showPins(activity, dotPins)
            } else {
                DeviceActions.openDetails(activity, dotPins.first().mac)
            }
            return
        }
        locHits.firstNotNullOfOrNull { locationsById[it] }?.let { loc ->
            if (selectCb != null) {
                selectCb(MapSelection.Facility(loc))
            } else {
                DeviceDetailActivity.openLocation(activity, loc.id)
            }
            return
        }
        for (trackEntityUuid in trackHits) {
            if (selectOrOpenAirborneUuid(trackEntityUuid, tapLat, tapLon, me)) return
        }
        val bubblePins = precisionHits.distinct().mapNotNull { pins[it] }
        if (bubblePins.isNotEmpty()) {
            val allFacilities = bubblePins.flatMap { it.groupFacilities }.distinctBy { it.id }
            val allMembers = bubblePins.flatMap { it.groupMembers }.distinctBy { it.uuid }
            if (allFacilities.isNotEmpty() || allMembers.size > 1) {
                MapDialogs.showGroupClusterModal(
                    a = activity,
                    facilities = allFacilities,
                    members = allMembers,
                    onPickFacility = { loc ->
                        if (selectCb != null) selectCb(MapSelection.Facility(loc))
                        else DeviceDetailActivity.openLocation(activity, loc.id)
                    },
                    onPickDetection = { chosen ->
                        if (selectCb != null) selectCb(toDetectionSelection(chosen, bubblePins.first().lat, bubblePins.first().lon))
                        else DeviceActions.openDetails(activity, "kokoho:${chosen.uuid}")
                    }
                )
            } else if (selectCb != null && allMembers.size == 1) {
                selectCb(toDetectionSelection(allMembers.first(), bubblePins.first().lat, bubblePins.first().lon))
            } else if (bubblePins.size > 1) {
                MapDialogs.showPins(activity, bubblePins)
            } else {
                DeviceActions.openDetails(activity, bubblePins.first().mac)
            }
            return
        }
        cams.firstNotNullOfOrNull { cameras[it] }?.let { c -> MapDialogs.showKnownCamera(activity, c) { maybeRedrawStatic(force = true) }; return }
        cctv.firstNotNullOfOrNull { cctvs[it] }?.let { MapDialogs.showCctv(activity, it); return }
        tower.firstNotNullOfOrNull { towers[it] }?.let { MapDialogs.showTower(activity, it); return }
        onTapEmpty?.invoke()
    }

    /** Opens what an aircraft / Waze link points at, when it's on the map now. */
    fun showAircraft(seen: PoliceAircraft.Seen, me: Pair<Double, Double>?) = MapDialogs.showAircraft(activity, seen.plane, seen.hit, me)

    /** A blue arrow pointing north, turned to the drone's heading by the layer. */
    private fun droneArrow(dp: Float): android.graphics.Bitmap {
        val size = (30 * dp).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply {
            moveTo(size / 2f, 2 * dp); lineTo(size - 5 * dp, size - 4 * dp)
            lineTo(size / 2f, size * 0.68f); lineTo(5 * dp, size - 4 * dp); close()
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawPath(path, paint.apply { color = android.graphics.Color.parseColor(DRONE_COLOR); style = android.graphics.Paint.Style.FILL })
        c.drawPath(path, paint.apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2 * dp })
        return bmp
    }
}
