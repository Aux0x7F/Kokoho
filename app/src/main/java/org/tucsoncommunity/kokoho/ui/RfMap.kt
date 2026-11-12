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
        private const val TRACE = "rf-trace"; private const val DRONE_LINES = "rf-drone-lines"
        private const val TOWERS = "rf-towers"; private const val CCTV = "rf-cctv"; private const val CAMERAS = "rf-cameras"
        private const val DEVICES = "rf-devices"; private const val DOTS = "rf-device-dots"; private const val LABELS = "rf-device-labels"
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
    /** After a camera download or an ignore changed what's on the map. */
    var onStaticChanged: (() -> Unit)? = null

    // What's on the map now, to find what a tap hit.
    private var pins: Map<String, MapPin> = emptyMap()
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

    /** Loads the light or night style; [onLoaded] once its layers are in. */
    fun loadStyle(dark: Boolean, onLoaded: () -> Unit) {
        isDark = dark
        map.setStyle(dark) { fresh ->
            if (activity.isFinishing || activity.isDestroyed) return@setStyle
            if (fresh || !map.hasSource(DEVICES)) safe("style") {
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

    /** The same icons as before. */
    private fun addImages() {
        val dp = activity.resources.displayMetrics.density
        KnownCamera.Kind.entries.forEach { map.addImage("rf-cam-${it.name}", MapIcons.cameraIcon(dp, it)) }
        map.addImage("rf-cctv-public", MapIcons.cctvIcon(dp, false))
        map.addImage("rf-cctv-private", MapIcons.cctvIcon(dp, true))
        map.addImage("rf-plane-police", MapIcons.planeIcon(dp, true))
        map.addImage("rf-plane", MapIcons.planeIcon(dp, false))
        map.addImage("rf-plane-clear", MapIcons.planeIcon(dp, false, clear = true))
        map.addImage("rf-tower", MapIcons.towerIcon(dp))
        map.addImage("rf-drone", droneArrow(dp))
    }

    /** Every layer, as map style JSON (the same on both engines). */
    private fun addLayers(dark: Boolean) {
        listOf(NAV_ROUTES, NAV_PLACES, PRECISION, CONES, CAM_CONES, TRACE, DRONE_LINES, TOWERS, CCTV, CAMERAS, PINGS, DEVICES, WIGLE, ASKED, WAZE, DRONE_OPS, DRONES, AIRCRAFT)
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
        listOf(
            // A route to follow: the other routes offered under it, in grey, then the chosen one with a white edge.
            layer("$NAV_ROUTES-alt", "line", NAV_ROUTES, filter = a("==", get("sel"), false), layout = routeLine,
                paint = o("line-color" to NAV_ALT_COLOR, "line-width" to 6.0, "line-opacity" to 0.85)),
            layer("$NAV_ROUTES-edge", "line", NAV_ROUTES, filter = a("==", get("sel"), true), layout = routeLine,
                paint = o("line-color" to "#FFFFFF", "line-width" to 11.0)),
            layer("$NAV_ROUTES-line", "line", NAV_ROUTES, filter = a("==", get("sel"), true), layout = routeLine,
                paint = o("line-color" to NAV_ROUTE_COLOR, "line-width" to 7.0)),
            // Flagged signal radius circles (distance radius circle or triangulated position circle).
            layer("$PRECISION-fill", "fill", PRECISION, paint = o("fill-color" to color, "fill-opacity" to a("*", 0.34, get("opacity")))),
            layer("$PRECISION-line", "line", PRECISION, paint = o("line-color" to color, "line-width" to 2.5, "line-opacity" to a("*", 0.95, get("opacity")))),
            // What each CCTV camera watches (zoomed in).
            layer("$CONES-fill", "fill", CONES, minzoom = CCTV_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            // Which way each plate, speed and red-light camera faces (when mapped), in its icon's colour.
            layer("$CAM_CONES-fill", "fill", CAM_CONES, minzoom = CAMERA_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            layer("$CAM_CONES-line", "line", CAM_CONES, minzoom = CAMERA_CONE_ZOOM,
                paint = o("line-color" to color, "line-width" to 1.2, "line-opacity" to a("*", get("opacity"), 2.5))),
            layer(TRACE, "line", layout = o("line-cap" to "round", "line-join" to "round"),
                paint = o("line-color" to color, "line-width" to 5.0)),
            layer(DRONE_LINES, "line", paint = o("line-color" to DRONE_COLOR, "line-width" to 2.5, "line-dasharray" to a(4.0, 2.5))),
            layer(TOWERS, "symbol", layout = o("icon-image" to "rf-tower", *overlap)),
            layer(CCTV, "symbol", minzoom = CCTV_MIN_ZOOM, layout = o("icon-image" to get("icon"), *overlap)),
            // Plate, speed and red-light cameras; the ones whose alerts you turned off, faded.
            layer(CAMERAS, "symbol", minzoom = CAMERAS_MIN_ZOOM, layout = o("icon-image" to get("icon"), *overlap),
                paint = o("icon-opacity" to get("opacity"))),
            // A saved trace's every sighting of each device (Settings › Log every sighting in traces): small, under the devices.
            layer(PINGS, "circle", paint = o("circle-color" to color, "circle-radius" to 3.0, "circle-opacity" to 0.8)),
            // Devices: only drawn as a center dot when narrow enough to be a point (showDot == true).
            layer(DOTS, "circle", DEVICES, filter = a("==", get("showDot"), true),
                layout = o("circle-sort-key" to a("case", flagged, 1.0, 0.0)),
                paint = o("circle-color" to color, "circle-radius" to a("case", flagged, 8.0, 5.0),
                    "circle-stroke-color" to "#FFFFFF", "circle-stroke-width" to 1.5, "circle-opacity" to get("opacity"))),
            layer(LABELS, "symbol", DEVICES, filter = flagged,
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 13.0, "text-anchor" to "top",
                    "text-offset" to a(0.0, 0.6), "text-optional" to true),
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
            // A drone's operator / take-off point, and the drone pointing its way.
            layer(DRONE_OPS, "circle", paint = o("circle-color" to "#F5E100", "circle-radius" to 7.0,
                "circle-stroke-color" to DRONE_COLOR, "circle-stroke-width" to 2.0)),
            layer(DRONES, "symbol",
                layout = o("icon-image" to "rf-drone", "icon-rotate" to get("dir"),
                    "icon-rotation-alignment" to "map", *overlap,
                    "text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.4), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 2.0)),
            // Aircraft: police / government ones bold purple with their agency and altitude, others small and grey
            // (under the Aircraft filter: every one large and labelled).
            // Where the route ends (red) and the stops on the way (white with a blue edge), above everything else.
            layer("$NAV_PLACES-pin", "circle", NAV_PLACES, paint = o(
                "circle-color" to a("case", a("==", get("end"), true), "#D93025", "#FFFFFF"), "circle-radius" to 9.0,
                "circle-stroke-color" to a("case", a("==", get("end"), true), "#FFFFFF", NAV_ROUTE_COLOR), "circle-stroke-width" to 3.0)),
            layer(AIRCRAFT, "symbol", layout = o("icon-image" to get("icon"), "icon-rotate" to get("track"),
                "icon-rotation-alignment" to "map",
                "icon-size" to a("interpolate", a("linear"), a("zoom"), 9, a("case", a("==", get("clear"), true), 1.0, 0.5), 12, 1.0, 16, 2.0),
                *overlap, "symbol-sort-key" to a("case", flagged, 1.0, 0.0),
                "text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                "text-offset" to a(0.0, 1.6), "text-optional" to true),
                paint = o("text-color" to "#FFFFFF", "text-halo-color" to a("case", flagged, hex(MapIcons.POLICE_AIRCRAFT_COLOR),
                    hex(MapIcons.CLEAR_AIRCRAFT_COLOR)), "text-halo-width" to 3.0))
        ).forEach { map.addLayer(it) }
    }

    /** Device labels: text and halo colours on the light or night map. */
    private fun labelColors(dark: Boolean) = if (dark) "#FFFFFF" to "#1B1F27" else "#1B1F27" to "#FFFFFF"

    private fun ready() = map.styleLoaded

    private fun set(source: String, features: List<JSONObject>) {
        map.setData(source, GeoJson.collection(features))
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
            GeoJson.feature(
                GeoJson.point(labelLat, labelLon),
                "mac" to p.mac,
                "label" to p.label.take(48),
                "color" to hex(p.color),
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

    /** A route: the trace being recorded (orange) or a saved one (blue). */
    fun drawTrace(points: List<Pair<Double, Double>>, color: String) {
        if (!ready()) return
        set(TRACE, if (points.size < 2) emptyList() else listOf(GeoJson.feature(GeoJson.line(points), "color" to color)))
    }

    /**
     * Fast-changing live layers: draws ONLY Kokoho's active flagged agency detections
     * (with overlapping radii collapsed into single circles labeled with comma-separated agencies,
     * 30m solid + 2h staleness fade, pinpointed Pro/Enterprise/RemoteID drones with altitude,
     * and ADS-B aircraft with tap-to-view flight tracks).
     */
    fun drawLive(@Suppress("UNUSED_PARAMETER") devices: List<DeviceRegistry.Snapshot>): List<MapPin> {
        if (!ready()) return emptyList()
        val snap = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.evaluate(activity)
        val nowMs = System.currentTimeMillis()
        val fallbackLat = ScanForegroundService.lastFix?.latitude ?: map.camera.lat.takeIf { kotlin.math.abs(it) > 1e-4 } ?: 32.2226
        val fallbackLon = ScanForegroundService.lastFix?.longitude ?: map.camera.lon.takeIf { kotlin.math.abs(it) > 1e-4 } ?: -110.9747

        val groundThreats = snap.mapThreats.filter {
            !it.isAircraft &&
                !(it.isDrone && it.isPinpointed) &&
                !org.tucsoncommunity.kokoho.posture.KokohoSignatures.isFlockCamera(it.hardwareIdentifier, it.title)
        }
        val zoom = map.camera.zoom

        data class MergedCluster(
            val threats: MutableList<org.tucsoncommunity.kokoho.posture.EvaluatedThreat>,
            var lat: Double,
            var lon: Double,
            var radiusM: Double
        )

        val clusters = mutableListOf<MergedCluster>()
        for (t in groundThreats) {
            val rawLat = t.lat ?: fallbackLat
            val rawLon = t.lon ?: fallbackLon
            // Display derived positions and narrow (<= 200 ft / 61 m) circles as derived;
            // only jitter wide (200ft+) uncorroborated single-sample circles.
            val isDerivedOrNarrow = t.isApproximatedPosition ||
                t.sightingCount >= 2 ||
                t.uncertaintyRadiusM <= org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.WIDE_CIRCLE_JITTER_MIN_M
            val (lat, lon) = if (isDerivedOrNarrow) {
                rawLat to rawLon
            } else {
                org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.deriveJitteredCenter(t.uuid, rawLat, rawLon, t.uncertaintyRadiusM)
            }
            val r = if (t.isPinpointed) 0.0 else t.mapCircleRadiusM.toDouble()
            val match = clusters.firstOrNull { c ->
                val d = DeviceRegistry.metersBetween(lat, lon, c.lat, c.lon)
                // Multiple colocated derived dots collapse into one aggregate centroid until closer zoom levels (>= 15.5)
                val bothDerivedDots = (isDerivedOrNarrow && r <= 180.0) && c.radiusM <= 180.0
                val overlapThreshold = if (bothDerivedDots && zoom >= 15.5) {
                    14.0
                } else {
                    kotlin.math.max(95.0, kotlin.math.min(r, c.radiusM) * 0.65)
                }
                d <= overlapThreshold
            }
            if (match != null) {
                match.threats += t
                val n = match.threats.size.toDouble()
                match.lat = ((match.lat * (n - 1.0)) + lat) / n
                match.lon = ((match.lon * (n - 1.0)) + lon) / n
                if (r > 0.0 && (match.radiusM == 0.0 || r < match.radiusM)) {
                    match.radiusM = r
                }
            } else {
                clusters += MergedCluster(mutableListOf(t), lat, lon, r)
            }
        }

        val shown = clusters.map { c ->
            val sorted = c.threats.sortedWith(
                compareBy<org.tucsoncommunity.kokoho.posture.EvaluatedThreat> { it.agency.priorityRank }
                    .thenBy { it.nearestEdgeDistanceM }
            )
            val lead = sorted.first()
            val agenciesInline = sorted.map { it.friendlyLabel }.distinct().joinToString(", ")
            val newestMs = sorted.maxOf { it.lastSeenMs }
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, newestMs)
            val totalSightings = sorted.sumOf { it.sightingCount.coerceAtLeast(1) }
            val distinctSignals = sorted.sumOf { it.evidenceCount.coerceAtLeast(1) }
            val effRadiusM = if (c.radiusM > 0.0) c.radiusM.toInt() else lead.uncertaintyRadiusM
            val opacity = org.tucsoncommunity.kokoho.posture.KokohoSignatures.mapCorroborationOpacity(
                activity,
                nowMs - newestMs,
                totalSightings,
                distinctSignals,
                effRadiusM
            ).toDouble()
            val accText = "±${org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, lead.uncertaintyRadiusM)}"
            val isPinpointed = sorted.all { it.isPinpointed }
            val radius = if (isPinpointed) null else c.radiusM.coerceAtLeast(35.0)
            val fullDist = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatUserDistance(activity, lead.nearestEdgeDistanceM, lead.uncertaintyRadiusM)
            val note = when {
                !lead.isApproximatedPosition ->
                    "Confidence radius ($accText) · Last detected $staleness."
                !isPinpointed ->
                    "Triangulated area ($accText) · Last detected $staleness."
                else ->
                    "Pinpointed position · Last detected $staleness."
            }
            val unitLines = sorted.joinToString("\n") { t ->
                val st = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
                "• ${t.friendlyLabel}: ${t.title} ($st)"
            }
            val showCenterDot = isPinpointed || sorted.size >= 2 || totalSightings >= 2 || effRadiusM <= 180
            MapPin(
                mac = "kokoho:${lead.uuid}",
                lat = c.lat,
                lon = c.lon,
                label = "$agenciesInline · $accText · $staleness",
                flagged = true,
                color = lead.posture.colorArgb,
                details = "${lead.posture.badgeLabel}\n$fullDist · Last seen $staleness\n$unitLines",
                radiusM = radius,
                whereNote = note,
                showDot = showCenterDot,
                opacity = opacity
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
                "dir" to (t.aircraftTrackDeg ?: 0.0),
                "label" to "${t.friendlyLabel} · $altPart · $staleness"
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

        // ADS-B & Circling Spy Planes
        val airThreats = snap.mapThreats.filter { it.isAircraft }
        airThreatsByUuid = airThreats.associateBy { it.uuid }
        set(AIRCRAFT, airThreats.map { t ->
            val lat = t.lat ?: fallbackLat
            val lon = t.lon ?: fallbackLon
            val altOrDist = t.altitudeFtAgl?.let { "${it} ft" }
                ?: org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatShortDistance(activity, t.nearestEdgeDistanceM)
            val cirTag = if (t.isCircling) " · CIRCLING" else ""
            GeoJson.feature(
                GeoJson.point(lat, lon),
                "hex" to t.uuid,
                "icon" to "rf-plane-police",
                "track" to (t.aircraftTrackDeg ?: 0.0),
                "flagged" to true,
                "clear" to true,
                "label" to "${t.friendlyLabel}$cirTag · $altOrDist"
            )
        })

        if (TripRecorder.isRecording) {
            val trace = TripRecorder.livePoints()
            drawTrace(trace.map { it.lat to it.lon }, TRACE_COLOR)
        } else if (selectedFlightTrackPoints.size >= 2) {
            drawTrace(selectedFlightTrackPoints, "#EC4899")
        } else {
            drawTrace(emptyList(), TRACE_COLOR)
        }
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
    fun maybeRedrawStatic(@Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        if (!ready()) return
        clearStatic()
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
        val asked = listOf(AIRCRAFT to "hex", WAZE to "id", DRONES to "mac", DRONE_OPS to "mac", DOTS to "mac",
            CAMERAS to "id", CCTV to "id", TOWERS to "id", WIGLE to "id", ASKED to "id", "$PRECISION-fill" to "mac")
        map.query(box, asked) { found ->
            if (activity.isFinishing || activity.isDestroyed) return@query
            safe("tap") { showTapped(found, me) }
        }
    }

    private fun showTapped(found: List<List<String>>, me: Pair<Double, Double>?) {
        val (air, waze, drone, op, dots, cams, cctv, tower) = found
        air.firstNotNullOfOrNull { airThreatsByUuid[it] }?.let { t ->
            val targetHex = t.aircraftHex ?: t.uuid
            org.tucsoncommunity.kokoho.posture.KokohoHubClient.fetchFlightTracks(activity, targetHex) { tracks ->
                val serverPts = tracks.firstOrNull()?.points.orEmpty()
                selectedFlightTrackPoints = serverPts
                drawTrace(serverPts, "#EC4899")
            }
            val nowMs = System.currentTimeMillis()
            val staleness = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.formatStaleness(nowMs, t.lastSeenMs)
            val altLine = t.altitudeFtAgl?.let { "Altitude: $it ft\n" }.orEmpty()
            val cirLine = if (t.isCircling) "Pattern: CIRCLING OVERHEAD\n" else ""
            val msg = "${t.title}\n$cirLine${altLine}Last seen: $staleness\n${t.evidenceSummary}\n\nFlight track polyline highlighted on map."
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(t.friendlyLabel)
                .setMessage(msg)
                .setPositiveButton("Details / Witness Report") { _, _ ->
                    DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
                }
                .setNeutralButton("Clear Track") { _, _ ->
                    selectedFlightTrackPoints = emptyList()
                    drawTrace(emptyList(), TRACE_COLOR)
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        air.firstNotNullOfOrNull { planes[it] }?.let { s -> MapDialogs.showAircraft(activity, s.plane, s.hit, me); return }
        waze.firstNotNullOfOrNull { wazeShown[it] }?.let { MapDialogs.showWaze(activity, it); return }
        found[8].firstNotNullOfOrNull { wigle[it] }?.let { p ->
            MapDialogs.showWigle(activity, p, me) { WiglePins.remove(activity, p.net.bssid); drawWigle() }
            return
        }
        found[9].firstNotNullOfOrNull { asked[it] }?.let { s ->
            val ssid = ProbeLog.onMap ?: return
            MapDialogs.showAsked(activity, ssid, s, me) { ProbeLog.showOnMap(null); drawAsked() }
            return
        }
        (drone + op).firstNotNullOfOrNull { droneThreatsByUuid[it] }?.let { t ->
            DeviceActions.openDetails(activity, "kokoho:${t.uuid}")
            return
        }
        (drone + op).firstNotNullOfOrNull { drones[it] }?.let { s ->
            MapDialogs.showDrone(activity, s.mac, MapDialogs.droneDetails(s, s.remoteId!!)); return
        }
        val here = (dots + found[10]).distinct().mapNotNull { pins[it] }
        if (here.isNotEmpty()) { MapDialogs.showPins(activity, here); return }
        cams.firstNotNullOfOrNull { cameras[it] }?.let { c -> MapDialogs.showKnownCamera(activity, c) { maybeRedrawStatic(force = true) }; return }
        cctv.firstNotNullOfOrNull { cctvs[it] }?.let { MapDialogs.showCctv(activity, it); return }
        tower.firstNotNullOfOrNull { towers[it] }?.let { MapDialogs.showTower(activity, it) }
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]
    private operator fun <T> List<T>.component8() = this[7]

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
