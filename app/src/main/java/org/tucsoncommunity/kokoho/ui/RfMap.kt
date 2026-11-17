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
    private var selectedFlightTrackPoints: List<Pair<Double, Double>> = emptyList()
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
            safe("loaded") { onLoaded() }
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
        val dp = activity.resources.displayMetrics.density
        KnownCamera.Kind.entries.forEach { map.addImage("rf-cam-${it.name}", MapIcons.cameraIcon(dp, it)) }
        map.addImage("rf-cctv-public", MapIcons.cctvIcon(dp, false))
        map.addImage("rf-cctv-private", MapIcons.cctvIcon(dp, true))
        map.addImage("rf-plane-police", MapIcons.planeIcon(dp, true))
        map.addImage("rf-plane", MapIcons.planeIcon(dp, false))
        map.addImage("rf-plane-clear", MapIcons.planeIcon(dp, false, clear = true))
        map.addImage("rf-tower", MapIcons.towerIcon(dp))
        map.addImage("rf-drone", droneArrow(dp))
        map.addImage("rf-dropped-pin", MapIcons.droppedPinIcon(dp))
        map.addImage("rf-selection-ring", MapIcons.selectionRingIcon(dp))
        map.addImage("rf-facility-fire", MapIcons.facilityBadgeIcon(dp, 0xFFDC2626.toInt(), isFire = true))
        for (agency in org.tucsoncommunity.kokoho.posture.KokohoAgency.entries) {
            val facId = "rf-facility-${agency.id}"
            map.addImage(facId, MapIcons.facilityBadgeIcon(dp, agency.mapColorArgb, isFire = false))
            registeredBadgeIcons += facId
        }
        for (kind in MapIcons.BadgeIconKind.entries) {
            for (agency in org.tucsoncommunity.kokoho.posture.KokohoAgency.entries) {
                val id = "rf-badge-${kind.id}-${agency.id}"
                map.addImage(
                    id,
                    MapIcons.classificationBadgeIcon(dp, kind, agency.mapColorArgb)
                )
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
            map.addImage(iconId, MapIcons.classificationBadgeIcon(dp, kind, colorArgb))
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
            layer("$PRECISION-fill", "fill", PRECISION, paint = o("fill-color" to color, "fill-opacity" to a("*", 0.50, get("opacity")))),
            layer("$PRECISION-line", "line", PRECISION, paint = o("line-color" to color, "line-width" to 2.5, "line-opacity" to a("*", 0.92, get("opacity")))),
            // What each CCTV camera watches (zoomed in).
            layer("$CONES-fill", "fill", CONES, minzoom = CCTV_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            // Which way each plate, speed and red-light camera faces (when mapped), in its icon's colour.
            layer("$CAM_CONES-fill", "fill", CAM_CONES, minzoom = CAMERA_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            layer("$CAM_CONES-line", "line", CAM_CONES, minzoom = CAMERA_CONE_ZOOM,
                paint = o("line-color" to color, "line-width" to 1.2, "line-opacity" to a("*", get("opacity"), 2.5))),
            // Thin Chaikin-smoothed flight tracks (current in-flight for <=3h, all 24h tracks for 24h).
            layer(FLIGHT_TRACKS, "line", layout = o("line-cap" to "round", "line-join" to "round"),
                paint = o("line-color" to color, "line-width" to 2.0, "line-opacity" to a("coalesce", get("opacity"), 0.82))),
            layer(TRACE, "line", layout = o("line-cap" to "round", "line-join" to "round"),
                paint = o("line-color" to color, "line-width" to 5.0)),
            layer(DRONE_LINES, "line", paint = o("line-color" to DRONE_COLOR, "line-width" to 2.5, "line-dasharray" to a(4.0, 2.5))),
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
            layer(DOTS, "symbol", DEVICES, filter = a("==", get("showDot"), true),
                layout = o("icon-image" to get("icon"), "icon-size" to 0.92, *overlap,
                    "symbol-sort-key" to a("case", flagged, 1.0, 0.0)),
                paint = o("icon-opacity" to get("opacity"))),
            layer(LABELS, "symbol", DEVICES, filter = flagged,
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
            // A drone's operator / take-off point, and the drone badge.
            layer(DRONE_OPS, "circle", paint = o("circle-color" to "#F5E100", "circle-radius" to 7.0,
                "circle-stroke-color" to DRONE_COLOR, "circle-stroke-width" to 2.0)),
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
                "opacity" to p.opacity
            )
        })
        set(PRECISION, shown.filter { it.radiusM != null }.map { p ->
            GeoJson.feature(
                GeoJson.polygon(MapGeo.circle(p.lat, p.lon, p.radiusM!!, n = 32)),
                "color" to hex(p.color),
                "mac" to p.mac,
                "opacity" to p.opacity
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
        set(DRONE_OPS, listOf(GeoJson.feature(GeoJson.point(opLat, opLon), "mac" to uuid)))
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
                set(SELECTED_PIN, listOf(GeoJson.feature(GeoJson.point(selection.lat, selection.lon))))
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

    /** A route: the trace being recorded (orange) or a saved one (blue). */
    fun drawTrace(points: List<Pair<Double, Double>>, color: String) {
        if (!ready()) return
        set(TRACE, if (points.size < 2) emptyList() else listOf(GeoJson.feature(GeoJson.line(points), "color" to color)))
    }

    /**
     * Fast-changing live layers: draws ONLY Kokoho's active flagged agency detections
     * (with overlapping radii collapsed into single circles labeled with comma-separated agencies,
     * rolling window radius/staleness fade, pinpointed Pro/Enterprise/RemoteID drones with altitude,
     * and ADS-B aircraft with tap-to-view flight tracks).
     */
    fun drawLive(
        @Suppress("UNUSED_PARAMETER") devices: List<DeviceRegistry.Snapshot>,
        precomputedSnap: org.tucsoncommunity.kokoho.posture.PostureSnapshot? = null
    ): List<MapPin> {
        if (!ready()) return emptyList()
        val snap = precomputedSnap ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.evaluate(activity)
        val nowMs = System.currentTimeMillis()
        val fallbackLat = ScanForegroundService.lastFix?.latitude ?: map.camera.lat.takeIf { kotlin.math.abs(it) > 1e-4 } ?: 32.2226
        val fallbackLon = ScanForegroundService.lastFix?.longitude ?: map.camera.lon.takeIf { kotlin.math.abs(it) > 1e-4 } ?: -110.9747
        val timeWindow = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getMapTimeWindow(activity)

        if (timeWindow == org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapTimeWindow.HEAT) {
            val heatFeatures = snap.mapThreats.mapNotNull { t ->
                if (t.isAircraft) return@mapNotNull null
                if (org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(t.hardwareIdentifier, t.title)) return@mapNotNull null
                val lat = t.lat ?: return@mapNotNull null
                val lon = t.lon ?: return@mapNotNull null
                val mass = (t.sightingCount.coerceAtLeast(1) * t.evidenceCount.coerceAtLeast(1)).toDouble()
                val weight = (0.65 + 0.35 * kotlin.math.ln(1.0 + mass)).coerceIn(0.65, 2.8)
                GeoJson.feature(GeoJson.point(lat, lon), "weight" to weight, "agency" to t.agency.id)
            }
            set(HEAT, heatFeatures)
            drawPins(emptyList())
            wazeShown = emptyMap()
            set(WAZE, emptyList())
            set(WIGLE, emptyList())
            set(ASKED, emptyList())
            droneThreatsByUuid = emptyMap()
            drones = emptyMap()
            set(DRONES, emptyList())
            set(DRONE_OPS, emptyList())
            set(DRONE_LINES, emptyList())
            airThreatsByUuid = emptyMap()
            set(AIRCRAFT, emptyList())
            set(FLIGHT_TRACKS, emptyList())
            drawLocations()
            return emptyList()
        }

        val groundThreats = snap.mapThreats.filter {
            !it.isAircraft &&
                !(it.isDrone && it.isPinpointed) &&
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

        // Pure gravity-centered centroid bubbles (no blurry heatmap layer overlay)
        set(HEAT, emptyList())
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
        drawPins(shown)

        wazeShown = emptyMap()
        set(WAZE, emptyList())
        set(WIGLE, emptyList())
        set(ASKED, emptyList())

        // Pinpointed Pro/Enterprise/RemoteID Drones with Altitude and Operator Tether
        val droneThreats = snap.mapThreats.filter { it.isDrone && it.isPinpointed }
        droneThreatsByUuid = droneThreats.associateBy { it.uuid }
        drones = emptyMap()
        set(DRONES, droneThreats.map { t ->
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val altPart = t.altitudeFtAgl?.let { "${it} ft AGL" }
                ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.nearestEdgeDistanceM)
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
            GeoJson.feature(
                GeoJson.point(lat, lon),
                "mac" to t.uuid,
                "icon" to "rf-badge-${MapIcons.BadgeIconKind.DRONE.id}-${t.agency.id}",
                "dir" to (t.aircraftTrackDeg ?: 0.0),
                "label" to "${t.label} · $altPart · $staleness"
            )
        })
        set(DRONE_OPS, droneThreats.mapNotNull { t ->
            val opLat = t.operatorLat ?: return@mapNotNull null
            val opLon = t.operatorLon ?: return@mapNotNull null
            GeoJson.feature(GeoJson.point(opLat, opLon), "mac" to t.uuid)
        })
        set(DRONE_LINES, droneThreats.mapNotNull { t ->
            val lat = t.lat ?: return@mapNotNull null
            val lon = t.lon ?: return@mapNotNull null
            val opLat = t.operatorLat ?: return@mapNotNull null
            val opLon = t.operatorLon ?: return@mapNotNull null
            GeoJson.feature(GeoJson.line(listOf(opLat to opLon, lat to lon)), "mac" to t.uuid)
        })

        // ADS-B & Circling Spy Planes (helicopters get rotorcraft badge; fixed-wing rotate along track)
        val airThreats = snap.mapThreats.filter { it.isAircraft }
        airThreatsByUuid = airThreats.associateBy { it.uuid }
        set(AIRCRAFT, airThreats.map { t ->
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

        // Thin Chaikin-smoothed flight tracks:
        //  - 30m / 3h windows: ONLY currently in-flight aircraft
        //  - 24h / 1wk / 1mo windows: all flights within the active window
        val trackFeatures = mutableListOf<JSONObject>()
        val cachedTracks = org.tucsoncommunity.kokoho.posture.KokohoHubClient.cachedFlightTracks()
        if (timeWindow == org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapTimeWindow.HOURS_24 ||
            timeWindow == org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapTimeWindow.WEEK_1 ||
            timeWindow == org.tucsoncommunity.kokoho.posture.KokohoSignatures.MapTimeWindow.MONTH_1
        ) {
            val enabledAirAgencies = org.tucsoncommunity.kokoho.posture.KokohoSignatures.getEnabledAircraftAgencies(activity)
            val liveEntities = airThreats.map { it.uuid }.toSet()
            val coveredHexes = HashSet<String>()
            val trackWindowMs = timeWindow.windowMs
            for (tr in cachedTracks) {
                if (nowMs - tr.endedAtMs > trackWindowMs) continue
                val ag = org.tucsoncommunity.kokoho.posture.KokohoAgency.fromCode(tr.category) ?: org.tucsoncommunity.kokoho.posture.KokohoAgency.DHS_AMO
                val allowed = if (ag.isDhs) enabledAirAgencies.any { it.isDhs } else ag in enabledAirAgencies
                if (!allowed) continue
                val pts = tr.points
                if (pts.size < 2) continue
                val smoothed = PoliceAircraft.chaikinSmooth(pts, 2)
                val isInFlight = tr.entityUuid in liveEntities
                coveredHexes += tr.entityUuid
                trackFeatures += GeoJson.feature(
                    GeoJson.line(smoothed),
                    "color" to ag.mapColorHex,
                    "opacity" to if (isInFlight) 0.88 else 0.58
                )
            }
            for (t in airThreats) {
                if (t.uuid in coveredHexes) continue
                val hexKey = t.aircraftHex ?: continue
                val localPts = PoliceAircraft.recentAirborneTrail(hexKey, nowMs, 3 * 3600_000L)
                if (localPts.size >= 2) {
                    val smoothed = PoliceAircraft.chaikinSmooth(localPts, 2)
                    trackFeatures += GeoJson.feature(
                        GeoJson.line(smoothed),
                        "color" to t.agency.mapColorHex,
                        "opacity" to 0.88
                    )
                }
            }
        } else {
            val tracksByEntity = cachedTracks.associateBy { it.entityUuid }
            for (t in airThreats) {
                val serverPts = tracksByEntity[t.uuid]?.points.orEmpty()
                val pts = if (serverPts.size >= 2) {
                    serverPts
                } else {
                    PoliceAircraft.recentAirborneTrail(t.aircraftHex ?: "", nowMs, 3 * 3600_000L)
                }
                if (pts.size >= 2) {
                    val smoothed = PoliceAircraft.chaikinSmooth(pts, 2)
                    trackFeatures += GeoJson.feature(
                        GeoJson.line(smoothed),
                        "color" to t.agency.mapColorHex,
                        "opacity" to 0.85
                    )
                }
            }
        }
        set(FLIGHT_TRACKS, trackFeatures)

        if (TripRecorder.isRecording) {
            val trace = TripRecorder.livePoints()
            drawTrace(trace.map { it.lat to it.lon }, TRACE_COLOR)
        } else if (selectedFlightTrackPoints.size >= 2) {
            drawTrace(selectedFlightTrackPoints, airThreatsByUuid.values.firstOrNull()?.agency?.mapColorHex ?: "#EC4899")
        } else {
            drawTrace(emptyList(), TRACE_COLOR)
        }
        // Keep selection ring synced with moved entity coordinates if an entity is currently selected
        val curSel = activeSelection
        if (curSel is MapSelection.Detection) {
            val updated = snap.mapThreats.firstOrNull { it.uuid.equals(curSel.entityUuid, ignoreCase = true) }
            if (updated?.lat != null && updated.lon != null) {
                activeSelection = curSel.copy(
                    title = updated.title.ifBlank { curSel.title },
                    lat = updated.lat,
                    lon = updated.lon,
                    radiusM = updated.uncertaintyRadiusM
                )
            }
        }
        drawSelection(activeSelection)
        return shown
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
        val box = RectF(x - r, y - r, x + r, y + r)
        val asked = listOf(
            AIRCRAFT to "hex",
            WAZE to "id",
            DRONES to "mac",
            DRONE_OPS to "mac",
            DOTS to "mac",
            "$LOCATIONS-fed" to "id",
            "$LOCATIONS-local" to "id",
            CAMERAS to "id",
            CCTV to "id",
            TOWERS to "id",
            WIGLE to "id",
            ASKED to "id",
            "$PRECISION-fill" to "mac"
        )
        map.query(box, asked) { found ->
            if (activity.isFinishing || activity.isDestroyed) return@query
            safe("tap") { showTapped(found, me) }
        }
    }

    private fun toDetectionSelection(t: org.tucsoncommunity.kokoho.posture.EvaluatedThreat, fallbackLat: Double, fallbackLon: Double): MapSelection.Detection {
        val nowMs = System.currentTimeMillis()
        val ageStr = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
        val extra = when {
            t.isAircraft -> t.altitudeFtAgl?.let { "${it} ft" } ?: "ADS-B"
            t.isDrone -> t.altitudeFtAgl?.let { "${it} ft AGL" } ?: "Remote ID"
            else -> "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.uncertaintyRadiusM)}"
        }
        return MapSelection.Detection(
            entityUuid = t.uuid,
            title = t.title.ifBlank { t.friendlyLabel },
            subtitle = "${t.agency.friendlyLabel} · $extra · $ageStr",
            agencyCode = t.agency.id,
            lat = t.lat ?: fallbackLat,
            lon = t.lon ?: fallbackLon,
            radiusM = t.uncertaintyRadiusM
        )
    }

    private fun showTapped(found: List<List<String>>, me: Pair<Double, Double>?) {
        val air = found[0]
        val waze = found[1]
        val drone = found[2]
        val op = found[3]
        val dots = found[4]
        val locHits = (found[5] + found[6]).distinct()
        val cams = found[7]
        val cctv = found[8]
        val tower = found[9]
        val wigleHits = found[10]
        val askedHits = found[11]
        val precisionHits = found[12]

        val selectCb = onSelectTarget

        air.firstNotNullOfOrNull { airThreatsByUuid[it] }?.let { t ->
            val targetHex = t.aircraftHex ?: t.uuid
            org.tucsoncommunity.kokoho.posture.KokohoHubClient.fetchFlightTracks(activity, targetHex) { tracks ->
                val rawPts = tracks.firstOrNull()?.points.orEmpty()
                val smoothed = PoliceAircraft.chaikinSmooth(rawPts, 2)
                selectedFlightTrackPoints = smoothed
                drawTrace(smoothed, t.agency.mapColorHex)
            }
            if (selectCb != null) {
                selectCb(toDetectionSelection(t, map.camera.lat, map.camera.lon))
            } else {
                DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
            }
            return
        }
        air.firstNotNullOfOrNull { planes[it] }?.let { s -> MapDialogs.showAircraft(activity, s.plane, s.hit, me); return }
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
        (drone + op).firstNotNullOfOrNull { droneThreatsByUuid[it] }?.let { t ->
            if (selectCb != null) {
                selectCb(toDetectionSelection(t, map.camera.lat, map.camera.lon))
            } else {
                DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
            }
            return
        }
        (drone + op).firstNotNullOfOrNull { drones[it] }?.let { s ->
            DeviceActions.openDetails(activity, s.mac)
            return
        }
        // Check direct puck dots BEFORE facility locations, and facility locations BEFORE wide precision bubbles!
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
