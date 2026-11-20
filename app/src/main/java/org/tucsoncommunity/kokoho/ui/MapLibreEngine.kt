package org.tucsoncommunity.kokoho.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import org.tucsoncommunity.kokoho.util.Permissions
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.ColorReliefLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LayoutPropertyValue
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet
import org.tucsoncommunity.kokoho.posture.VpnGeoGate
import org.tucsoncommunity.kokoho.util.Prefs

/** [MapEngine] on MapLibre with OpenFreeMap's vector maps (free, no account, no key). */
internal class MapLibreEngine(private val context: Context) : MapEngine {

    companion object {
        /** Passive stationary hold duration for dropping a map point (longer than stock 400ms to prevent accidental drops while panning). */
        const val POINT_DROP_HOLD_MS = 750L
        /** OpenFreeMap: OpenStreetMap vector tiles, free, no account, no key, no cookies. */
        const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        /** A navy night map (like Waze's), lifted further in [nightTweaks]: OpenFreeMap's "dark" is near black. */
        const val DARK_STYLE = "https://tiles.openfreemap.org/styles/fiord"
        const val CREDIT = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
        private const val BUILDINGS = "rf-buildings-3d"
        private const val TERRAIN_SOURCE = "rf-terrain-dem"
        private const val TERRAIN_RELIEF = "rf-terrain-relief"
        private const val TERRAIN_HILLSHADE = "rf-terrain-hillshade"
        /** A clear picture, for the puck layers that must show nothing. */
        private const val BLANK = "rf-puck-blank"
        private const val GRASS = "rf-night-grass"
        /** Style properties whose value is a plain array, not an expression. */
        private val ARRAY_PROPERTIES = setOf("text-font", "text-offset", "icon-offset", "line-dasharray", "text-variable-anchor", "text-translate", "icon-translate", "circle-translate", "fill-translate", "line-translate")

        /** MapLibre's one-time set-up: always force OpenGL since MapLibre Vulkan surface recreation crashes on app switch. */
        internal fun start(context: Context) {
            MapLibre.getInstance(context.applicationContext, null, WellKnownTileServer.MapLibre, org.maplibre.android.RenderingEngine.Type.OPENGL)
        }

        /** A style JSON value as MapLibre wants it: expressions, float numbers, typed arrays. */
        internal fun value(name: String, v: Any?): Any? = when {
            v is JSONArray && name in ARRAY_PROPERTIES ->
                if (v.length() > 0 && v.get(0) is String) Array(v.length()) { v.getString(it) }
                else Array(v.length()) { v.getDouble(it).toFloat() }
            v is JSONArray -> Expression.Converter.convert(v.toString())
            v is Number -> v.toFloat()
            else -> v
        }
    }

    override val provider = MapEngine.Provider.OPENFREEMAP
    override val credit = CREDIT
    override val labelFont = listOf("Noto Sans Regular")

    init { start(context) }

    override val view = MapView(context, MapLibreMapOptions.createFromAttributes(context))
    private var map: MapLibreMap? = null
    private var isDarkStyle: Boolean = true
    private var buildings3dEnabled: Boolean = Prefs.mapBuildings3d(context)
    private var terrainEnabled: Boolean = Prefs.mapTerrain(context)
    private var nativeTerrainMeshActive: Boolean = false
    private var lastOrbitElevM: Double = 0.0
    private var gestureInProgress: Boolean = false
    private val removeVpnListener: () -> Unit = VpnGeoGate.addListener { blocked ->
        if (!blocked && terrainEnabled) {
            style()?.let { s -> runCatching { syncTerrain(s, isDarkStyle, true) } }
        } else if (blocked) {
            style()?.let { s -> runCatching { syncTerrain(s, isDarkStyle, false) } }
        }
    }

    override var onCameraMove: (() -> Unit)? = null
    override var onIdle: (() -> Unit)? = null
    override var onClick: ((Float, Float) -> Unit)? = null
    override var onLongClick: ((Double, Double) -> Unit)? = null
    override var onUserGesture: (() -> Unit)? = null
    override var onFollowDismissed: (() -> Unit)? = null

    override fun onCreate(state: Bundle?) = view.onCreate(state)
    override fun onStart() = view.onStart()
    override fun onResume() = view.onResume()
    override fun onPause() = view.onPause()
    override fun onStop() = view.onStop()
    override fun onLowMemory() = view.onLowMemory()
    override fun onSaveInstanceState(out: Bundle) = view.onSaveInstanceState(out)
    override fun onDestroy() {
        runCatching { removeVpnListener() }
        map = null
        view.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun whenReady(ready: () -> Unit) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // the credit line on the map instead
            m.uiSettings.isRotateGesturesEnabled = true // two-finger pinch rotation
            m.uiSettings.isTiltGesturesEnabled = true
            m.addOnCameraMoveStartedListener { reason ->
                gestureInProgress = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                if (gestureInProgress) onUserGesture?.invoke()
            }
            m.addOnCameraMoveListener { onCameraMove?.invoke() }
            m.addOnCameraIdleListener {
                val wasGesture = gestureInProgress
                gestureInProgress = false
                if (terrainEnabled) trackOrbitElevationOnIdle(wasGesture)
                onIdle?.invoke()
            }
            m.addOnMapClickListener { at ->
                val p = m.projection.toScreenLocation(at)
                onClick?.invoke(p.x, p.y)
                false
            }
            // Passive ~750ms stationary single-finger hold detector for dropping a point without interfering with MapLibre gestures.
            val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            var downX = 0f
            var downY = 0f
            var holdPending = false
            val holdRunnable = Runnable {
                if (!holdPending) return@Runnable
                holdPending = false
                val handler = onLongClick ?: return@Runnable
                val latLng = runCatching { m.projection.fromScreenLocation(PointF(downX, downY)) }.getOrNull() ?: return@Runnable
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                handler.invoke(latLng.latitude, latLng.longitude)
            }
            view.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        holdPending = true
                        view.removeCallbacks(holdRunnable)
                        view.postDelayed(holdRunnable, POINT_DROP_HOLD_MS)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (holdPending && (kotlin.math.abs(ev.x - downX) > touchSlop || kotlin.math.abs(ev.y - downY) > touchSlop)) {
                            holdPending = false
                            view.removeCallbacks(holdRunnable)
                        }
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN,
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        holdPending = false
                        view.removeCallbacks(holdRunnable)
                    }
                }
                false // always pass event through to MapLibre
            }
            ready()
        }
    }

    // ---- style ----

    override fun setStyle(dark: Boolean, loaded: (Boolean) -> Unit) {
        val m = map ?: return
        isDarkStyle = dark
        buildings3dEnabled = Prefs.mapBuildings3d(context)
        terrainEnabled = Prefs.mapTerrain(context)
        m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { style ->
            if (dark) runCatching { nightTweaks(style) }
            runCatching { syncTerrain(style, dark, terrainEnabled) }
            runCatching { syncBuildings3d(style, dark, buildings3dEnabled) }
            if (terrainEnabled) {
                val t = m.cameraPosition.target
                if (t != null) {
                    lastOrbitElevM = TerrainElevationSampler.sampleElevationM(context, t.latitude, t.longitude)
                }
            } else {
                lastOrbitElevM = 0.0
            }
            addMarkerImage(style)
            loaded(true)
        }
    }

    /**
     * High-contrast dark tactical basemap tweaks on OpenFreeMap's vector tiles:
     *  - Deep navy-slate ground (`#121824`) so semi-transparent agency bubbles & labels stand out cleanly.
     *  - Distinctly lighter street hierarchy (`#3A4B6E` minor roads, `#64748B` major arterials,
     *    `#94A3B8` motorways with `#1E293B` casings) so streets are effortlessly readable through 50% bubbles.
     *  - Crisp bright street & place labels (`#F1F5F9` / `#FFFFFF` with `#0B0F17` halo).
     */
    private fun nightTweaks(style: Style) {
        style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#121824"))
        style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#0A192F"))
        style.getLayer("waterway")?.setProperties(PropertyFactory.lineColor("#112240"), PropertyFactory.lineOpacity(0.85f))
        style.getLayer("landuse_residential")?.setProperties(PropertyFactory.fillColor("#182132"), PropertyFactory.fillOpacity(0.65f))
        style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#132A23"), PropertyFactory.fillOpacity(0.75f))
        style.getLayer("park_outline")?.setProperties(PropertyFactory.lineColor("#1F4538"))
        style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#132A23"),
            PropertyFactory.fillOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(8, 0.35f), Expression.stop(12, 0.65f))))
        style.getLayer("building")?.setProperties(PropertyFactory.fillColor("#1E293B"), PropertyFactory.fillOpacity(0.75f))
        addPlaceLabels(style)
        if (style.getLayer("night-oneway") == null) NightLabels.layers.forEach { runCatching { addLayerTo(style, JSONObject(it)) } }
        // High-contrast labels: bright white/slate on a strong dark halo so street names never wash out
        for (layer in style.layers) {
            if (layer !is SymbolLayer || layer.id.startsWith("rf-") || layer.id.startsWith("night-shield")) continue
            val road = layer.id.contains("highway") || layer.id.contains("water") || layer.id.contains("path")
            layer.setProperties(
                PropertyFactory.textColor(if (road) "#E2E8F0" else "#F8FAFC"),
                PropertyFactory.textHaloColor("#090D16"),
                PropertyFactory.textHaloWidth(2.0f),
                PropertyFactory.textHaloBlur(0.25f),
                PropertyFactory.textOpacity(1f))
        }
        if (style.getLayer(GRASS) == null) {
            val grass = FillLayer(GRASS, "openmaptiles").withSourceLayer("landcover").withProperties(
                PropertyFactory.fillColor("#152C24"), PropertyFactory.fillOpacity(0.45f)
            ).withFilter(Expression.Converter.convert("""["match", ["get", "class"], ["grass", "farmland"], true, false]"""))
            if (style.getLayer("landcover_wood") != null) style.addLayerBelow(grass, "landcover_wood") else style.addLayerAbove(grass, "water")
        }
        style.getLayer("highway_path")?.setProperties(PropertyFactory.lineColor("#334155"), PropertyFactory.lineOpacity(0.85f))
        style.getLayer("highway_minor")?.setProperties(PropertyFactory.lineColor("#3B4D6B"), PropertyFactory.lineOpacity(0.95f))
        style.getLayer("highway_major_casing")?.setProperties(PropertyFactory.lineColor("#0B0F17"))
        style.getLayer("highway_major_inner")?.setProperties(PropertyFactory.lineColor("#647899"))
        style.getLayer("highway_major_subtle")?.setProperties(PropertyFactory.lineColor("#526484"), PropertyFactory.lineOpacity(0.9f))
        style.getLayer("highway_motorway_casing")?.setProperties(PropertyFactory.lineColor("#0F172A"))
        style.getLayer("highway_motorway_inner")?.setProperties(PropertyFactory.lineColor("#8BA0C2"))
        style.getLayer("highway_motorway_subtle")?.setProperties(PropertyFactory.lineColor("#7589AB"))
    }

    private fun syncBuildings3d(style: Style, dark: Boolean, enabled: Boolean) {
        // Hide any built-in base-style 3D extrusion layers (such as OpenFreeMap Liberty's "building-3d")
        // so the 3D Buildings button controls extrusions uniformly in both light and dark styles.
        for (layer in style.layers) {
            if (layer is FillExtrusionLayer && layer.id != BUILDINGS) {
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
            }
        }
        val heightExpr = Expression.Converter.convert("""["coalesce", ["get", "render_height"], 0]""")
        val baseExpr = Expression.Converter.convert("""["coalesce", ["get", "render_min_height"], 0]""")
        val colorExpr = if (dark) {
            Expression.Converter.convert(
                """["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 8], 0, "#1E293B", 18, "#293850", 60, "#3E5475"]"""
            )
        } else {
            Expression.Converter.convert(
                """["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 8], 0, "#CBD5E1", 18, "#B3C2D4", 60, "#94A3B8"]"""
            )
        }
        val vis = if (enabled) Property.VISIBLE else Property.NONE
        val opacity = if (dark) 0.78f else 0.74f
        val existing = style.getLayer(BUILDINGS)
        if (existing != null) {
            existing.setProperties(
                PropertyFactory.fillExtrusionColor(colorExpr),
                PropertyFactory.fillExtrusionHeight(heightExpr),
                PropertyFactory.fillExtrusionBase(baseExpr),
                PropertyFactory.fillExtrusionOpacity(opacity),
                PropertyFactory.visibility(vis)
            )
            return
        }
        if (style.getSource("openmaptiles") == null) return
        val buildings = FillExtrusionLayer(BUILDINGS, "openmaptiles").withSourceLayer("building").withProperties(
            PropertyFactory.fillExtrusionColor(colorExpr),
            PropertyFactory.fillExtrusionHeight(heightExpr),
            PropertyFactory.fillExtrusionBase(baseExpr),
            PropertyFactory.fillExtrusionOpacity(opacity),
            PropertyFactory.visibility(vis)
        ).apply { minZoom = 13.5f }
        val anchorId = style.layers.firstOrNull { l ->
            (l.id.startsWith("rf-") && l.id != TERRAIN_RELIEF && l.id != TERRAIN_HILLSHADE && l.id != GRASS) ||
                l is SymbolLayer
        }?.id
        if (anchorId != null) style.addLayerBelow(buildings, anchorId) else style.addLayer(buildings)
    }

    private fun syncTerrain(style: Style, dark: Boolean, enabled: Boolean) {
        val allowDem = enabled && !VpnGeoGate.isBlockedTucson()
        if (!allowDem) {
            style.getLayer(TERRAIN_RELIEF)?.setProperties(PropertyFactory.visibility(Property.NONE))
            style.getLayer(TERRAIN_HILLSHADE)?.setProperties(PropertyFactory.visibility(Property.NONE))
            if (nativeTerrainMeshActive) {
                tryApplyNativeStyleTerrain(style, enabled = false)
                nativeTerrainMeshActive = false
            }
            return
        }
        if (style.getSource(TERRAIN_SOURCE) == null) {
            val tileSet = TileSet("2.2.0", TerrainElevationSampler.tileUrlTemplate(context)).apply {
                encoding = TerrainElevationSampler.TERRAIN_ENCODING
                minZoom = 0f
                maxZoom = TerrainElevationSampler.TERRAIN_MAX_ZOOM.toFloat()
            }
            style.addSource(RasterDemSource(TERRAIN_SOURCE, tileSet, TerrainElevationSampler.TERRAIN_TILE_SIZE))
        }
        val reliefExpr = if (dark) {
            Expression.Converter.convert(
                """["interpolate", ["linear"], ["elevation"], 600, "rgba(18,24,36,0.0)", 950, "rgba(32,48,64,0.20)", 1700, "rgba(56,78,94,0.34)", 2800, "rgba(96,118,134,0.46)"]"""
            )
        } else {
            Expression.Converter.convert(
                """["interpolate", ["linear"], ["elevation"], 600, "rgba(255,255,255,0.0)", 950, "rgba(214,205,186,0.18)", 1700, "rgba(186,172,150,0.28)", 2800, "rgba(158,142,122,0.38)"]"""
            )
        }
        val existingRelief = style.getLayer(TERRAIN_RELIEF)
        if (existingRelief != null) {
            existingRelief.setProperties(
                PropertyFactory.colorReliefColor(reliefExpr),
                PropertyFactory.colorReliefOpacity(1.0f),
                PropertyFactory.visibility(Property.VISIBLE)
            )
        } else {
            val relief = ColorReliefLayer(TERRAIN_RELIEF, TERRAIN_SOURCE).withProperties(
                PropertyFactory.colorReliefColor(reliefExpr),
                PropertyFactory.colorReliefOpacity(1.0f),
                PropertyFactory.visibility(Property.VISIBLE)
            )
            when {
                style.getLayer("background") != null -> style.addLayerAbove(relief, "background")
                style.getLayer("water") != null -> style.addLayerBelow(relief, "water")
                else -> style.addLayerAt(relief, 0)
            }
        }

        val shadowCol = if (dark) "rgba(4, 8, 15, 0.72)" else "rgba(15, 23, 42, 0.38)"
        val highlightCol = if (dark) "rgba(148, 163, 184, 0.42)" else "rgba(255, 255, 255, 0.56)"
        val accentCol = if (dark) "#334155" else "#64748B"
        val existingHill = style.getLayer(TERRAIN_HILLSHADE)
        if (existingHill != null) {
            existingHill.setProperties(
                PropertyFactory.hillshadeExaggeration(TerrainElevationSampler.TERRAIN_EXAGGERATION),
                PropertyFactory.hillshadeIlluminationDirection(arrayOf(315f)),
                PropertyFactory.hillshadeIlluminationAnchor("viewport"),
                PropertyFactory.hillshadeShadowColor(arrayOf(shadowCol)),
                PropertyFactory.hillshadeHighlightColor(arrayOf(highlightCol)),
                PropertyFactory.hillshadeAccentColor(accentCol),
                PropertyFactory.visibility(Property.VISIBLE)
            )
        } else {
            val hillshade = HillshadeLayer(TERRAIN_HILLSHADE, TERRAIN_SOURCE).withProperties(
                PropertyFactory.hillshadeExaggeration(TerrainElevationSampler.TERRAIN_EXAGGERATION),
                PropertyFactory.hillshadeIlluminationDirection(arrayOf(315f)),
                PropertyFactory.hillshadeIlluminationAnchor("viewport"),
                PropertyFactory.hillshadeShadowColor(arrayOf(shadowCol)),
                PropertyFactory.hillshadeHighlightColor(arrayOf(highlightCol)),
                PropertyFactory.hillshadeAccentColor(accentCol),
                PropertyFactory.visibility(Property.VISIBLE)
            )
            val roadAnchor = listOf("building", "highway_path", "highway_minor", "highway_major_casing")
                .firstOrNull { style.getLayer(it) != null }
                ?: style.layers.firstOrNull { it is SymbolLayer }?.id
            if (roadAnchor != null) style.addLayerBelow(hillshade, roadAnchor) else style.addLayer(hillshade)
        }
        nativeTerrainMeshActive = tryApplyNativeStyleTerrain(style, enabled = true)
    }

    /**
     * Optional reflection hook if a MapLibre Android SDK build exposes `Style.setTerrain(...)`.
     * Returns true only if native 3D vertex mesh displacement was activated on [style].
     */
    private fun tryApplyNativeStyleTerrain(style: Style, enabled: Boolean): Boolean = runCatching {
        val methods = style.javaClass.methods.filter { it.name == "setTerrain" && it.parameterTypes.size == 1 }
        if (methods.isEmpty()) return false
        val m = methods.first()
        if (!enabled) {
            m.invoke(style, null)
            return false
        }
        val paramType = m.parameterTypes[0]
        val ctor = paramType.constructors.firstOrNull { c ->
            c.parameterTypes.size == 2 && c.parameterTypes[0] == String::class.java
        } ?: paramType.constructors.firstOrNull { c ->
            c.parameterTypes.size == 1 && c.parameterTypes[0] == String::class.java
        } ?: return false
        val terrainObj = if (ctor.parameterTypes.size == 2) {
            ctor.newInstance(TERRAIN_SOURCE, TerrainElevationSampler.TERRAIN_EXAGGERATION)
        } else {
            ctor.newInstance(TERRAIN_SOURCE)
        }
        m.invoke(style, terrainObj)
        true
    }.getOrDefault(false)

    private fun compensateCameraForTerrain(wasEnabled: Boolean, nowEnabled: Boolean, animate: Boolean) {
        val m = map ?: return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        val groundElevM = TerrainElevationSampler.sampleElevationM(
            context = context,
            lat = target.latitude,
            lon = target.longitude,
            allowAsyncFetch = nowEnabled,
            onLoaded = {
                if (terrainEnabled) trackOrbitElevationOnIdle(wasUserGesture = false)
            }
        ) * TerrainElevationSampler.TERRAIN_EXAGGERATION
        val prevElevM = if (wasEnabled) lastOrbitElevM else 0.0
        val newElevM = if (nowEnabled) groundElevM else 0.0
        val deltaElevM = newElevM - prevElevM
        lastOrbitElevM = newElevM

        val builder = CameraPosition.Builder(pos).centerAltitude(newElevM)
        if (nativeTerrainMeshActive || (wasEnabled && !nowEnabled && kotlin.math.abs(deltaElevM) > 5.0)) {
            if (nativeTerrainMeshActive || wasEnabled) {
                val meshDelta = if (nativeTerrainMeshActive) deltaElevM else 0.0
                if (kotlin.math.abs(meshDelta) > 2.0) {
                    val fov = pos.fov.takeIf { it > 0.0 } ?: 36.87
                    val nextZoom = TerrainElevationSampler.computeZoomForElevationDelta(
                        lat = target.latitude,
                        currentZoom = pos.zoom,
                        deltaElevM = meshDelta,
                        viewportHeightPx = view.height.coerceAtLeast(320),
                        fovDeg = fov
                    )
                    builder.zoom(nextZoom)
                }
            }
        }
        if (animate && nowEnabled && pos.tilt < 15.0) {
            builder.tilt(36.0)
        }
        val updated = builder.build()
        if (animate) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(updated), 450)
        } else {
            m.cameraPosition = updated
        }
    }

    /**
     * Raises or lowers the camera and its orbit point (`centerAltitude` + gentle elevation clearance zoom)
     * relative to `deltaElevM` as the locked/orbit target moves across Foothills or mountain terrain.
     */
    private fun trackOrbitElevationOnIdle(wasUserGesture: Boolean) {
        val m = map ?: return
        if (!terrainEnabled) return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        val curElevM = TerrainElevationSampler.sampleElevationM(
            context = context,
            lat = target.latitude,
            lon = target.longitude,
            allowAsyncFetch = true
        ) * TerrainElevationSampler.TERRAIN_EXAGGERATION
        val deltaElevM = curElevM - lastOrbitElevM
        lastOrbitElevM = curElevM
        if (kotlin.math.abs(deltaElevM) < 45.0) {
            if (kotlin.math.abs(pos.centerAltitude - curElevM) > 10.0) {
                m.cameraPosition = CameraPosition.Builder(pos).centerAltitude(curElevM).build()
            }
            return
        }
        val fov = pos.fov.takeIf { it > 0.0 } ?: 36.87
        val effDelta = if (nativeTerrainMeshActive) deltaElevM else deltaElevM * 0.45
        val nextZoom = TerrainElevationSampler.computeZoomForElevationDelta(
            lat = target.latitude,
            currentZoom = pos.zoom,
            deltaElevM = effDelta,
            viewportHeightPx = view.height.coerceAtLeast(320),
            fovDeg = fov
        )
        val b = CameraPosition.Builder(pos).centerAltitude(curElevM)
        if (kotlin.math.abs(nextZoom - pos.zoom) >= 0.03) {
            b.zoom(nextZoom)
        }
        if (wasUserGesture) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 260)
        } else {
            m.cameraPosition = b.build()
        }
    }

    /**
     * Shops, businesses, landmarks and transit stops, which the night style doesn't draw at all (the
     * light map does): the same layers, icons and zoom levels as the light map, from the same tiles.
     */
    private fun addPlaceLabels(style: Style) {
        if (style.getLayer("night-poi-r1") != null) return
        fun ex(json: String) = Expression.Converter.convert(json)
        val name = ex("""["coalesce", ["get", "name_en"], ["get", "name"]]""")
        val icon = ex("""["match", ["get", "subclass"], ["florist", "furniture"], ["get", "subclass"], ["get", "class"]]""")
        fun poi(id: String, minZoom: Float, filter: String) = SymbolLayer(id, "openmaptiles").withSourceLayer("poi").withFilter(ex(filter))
            .withProperties(PropertyFactory.iconImage(icon), PropertyFactory.textField(name),
                PropertyFactory.textFont(arrayOf("Noto Sans Italic")), PropertyFactory.textSize(12f),
                PropertyFactory.textAnchor("top"), PropertyFactory.textOffset(arrayOf(0f, 0.6f)), PropertyFactory.textMaxWidth(9f))
            .also { it.minZoom = minZoom }
        val point = """["match", ["geometry-type"], ["MultiPoint", "Point"], true, false]"""
        val layers = listOf(
            poi("night-poi-r20", 17f, """["all", $point, [">=", ["get", "rank"], 20]]"""),
            poi("night-poi-r7", 16f, """["all", $point, [">=", ["get", "rank"], 7], ["<", ["get", "rank"], 20]]"""),
            poi("night-poi-r1", 15f, """["all", $point, [">=", ["get", "rank"], 1], ["<", ["get", "rank"], 7]]"""),
            SymbolLayer("night-poi-transit", "openmaptiles").withSourceLayer("poi")
                .withFilter(ex("""["match", ["get", "class"], ["airport", "bus", "rail"], true, false]"""))
                .withProperties(PropertyFactory.iconImage(ex("""["to-string", ["get", "class"]]""")), PropertyFactory.iconSize(0.7f),
                    PropertyFactory.textField(name), PropertyFactory.textFont(arrayOf("Noto Sans Italic")), PropertyFactory.textSize(12f),
                    PropertyFactory.textAnchor("left"), PropertyFactory.textOffset(arrayOf(0.9f, 0f)), PropertyFactory.textMaxWidth(9f))
        )
        layers.forEach { style.addLayer(it) }
    }

    private fun style(): Style? = map?.style?.takeIf { it.isFullyLoaded }
    override val styleLoaded get() = style() != null
    override fun hasSource(id: String) = style()?.getSource(id) != null
    override fun addImage(id: String, bitmap: Bitmap) { style()?.addImage(id, bitmap) }
    override fun addSource(id: String) { style()?.addSource(GeoJsonSource(id)) }
    override fun setData(source: String, geoJson: String) { style()?.getSourceAs<GeoJsonSource>(source)?.setGeoJson(geoJson) }

    override fun addLayer(json: JSONObject) { style()?.let { addLayerTo(it, json) } }

    private fun addLayerTo(style: Style, json: JSONObject) {
        val id = json.getString("id"); val src = json.getString("source")
        val layer: Layer = when (val type = json.getString("type")) {
            "circle" -> CircleLayer(id, src)
            "fill" -> FillLayer(id, src)
            "line" -> LineLayer(id, src)
            "symbol" -> SymbolLayer(id, src)
            "heatmap" -> HeatmapLayer(id, src)
            "fill-extrusion" -> FillExtrusionLayer(id, src)
            else -> throw IllegalArgumentException("layer type $type")
        }
        if (json.has("source-layer")) when (layer) {
            is CircleLayer -> layer.sourceLayer = json.getString("source-layer")
            is FillLayer -> layer.sourceLayer = json.getString("source-layer")
            is LineLayer -> layer.sourceLayer = json.getString("source-layer")
            is SymbolLayer -> layer.sourceLayer = json.getString("source-layer")
            else -> {}
        }
        val props = ArrayList<PropertyValue<*>>()
        json.optJSONObject("layout")?.let { o -> o.keys().forEach { k -> props += LayoutPropertyValue(k, value(k, o.get(k))) } }
        json.optJSONObject("paint")?.let { o -> o.keys().forEach { k -> props += PaintPropertyValue(k, value(k, o.get(k))) } }
        layer.setProperties(*props.toTypedArray())
        json.optJSONArray("filter")?.let { f ->
            val e = Expression.Converter.convert(f.toString())
            when (layer) {
                is CircleLayer -> layer.setFilter(e); is FillLayer -> layer.setFilter(e); is LineLayer -> layer.setFilter(e)
                is SymbolLayer -> layer.setFilter(e); is HeatmapLayer -> layer.setFilter(e); is FillExtrusionLayer -> layer.setFilter(e)
            }
        }
        if (json.has("minzoom")) layer.minZoom = json.getDouble("minzoom").toFloat()
        style.addLayer(layer)
    }

    override fun setPaint(layer: String, name: String, value: String) {
        style()?.getLayer(layer)?.setProperties(PaintPropertyValue(name, value))
    }

    override fun setLayerVisible(layer: String, visible: Boolean) {
        style()?.getLayer(layer)?.setProperties(
            PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
        )
    }

    override fun setBuildings3d(enabled: Boolean) {
        buildings3dEnabled = enabled
        style()?.let { s -> runCatching { syncBuildings3d(s, isDarkStyle, enabled) } }
    }

    override fun setTerrain(enabled: Boolean, animateCamera: Boolean) {
        val wasEnabled = terrainEnabled
        terrainEnabled = enabled
        style()?.let { s -> runCatching { syncTerrain(s, isDarkStyle, enabled) } }
        runCatching { compensateCameraForTerrain(wasEnabled, enabled, animateCamera) }
    }

    // ---- camera ----

    override val camera: MapEngine.Camera get() {
        val c = map?.cameraPosition ?: return MapEngine.Camera(0.0, 0.0, 0.0, 0.0, 0.0)
        return MapEngine.Camera(c.target?.latitude ?: 0.0, c.target?.longitude ?: 0.0, c.zoom, c.bearing, c.tilt)
    }

    override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {
        val m = map ?: return
        m.cameraPosition = CameraPosition.Builder(m.cameraPosition).target(LatLng(lat, lon)).apply { zoom?.let { zoom(it) } }.build()
    }

    override fun zoomTo(zoom: Double) { map?.moveCamera(CameraUpdateFactory.zoomTo(zoom)) }

    override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {
        val m = map ?: return
        val b = CameraPosition.Builder(m.cameraPosition)
        if (lat != null && lon != null) b.target(LatLng(lat, lon))
        zoom?.let { b.zoom(it) }; bearing?.let { b.bearing(it) }; tilt?.let { b.tilt(it) }
        if (flat) b.padding(0.0, 0.0, 0.0, 0.0)
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), ms.toInt().coerceAtLeast(1))
    }

    override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {
        val m = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) { jumpTo(points[0].first, points[0].second); return }
        val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, paddingPx, paddingPx, paddingPx, bottomPx))
    }

    override fun visibleBox(): MapEngine.Box? {
        val v = runCatching { map?.projection?.visibleRegion?.latLngBounds }.getOrNull() ?: return null
        return MapEngine.Box(v.latitudeNorth, v.longitudeEast, v.latitudeSouth, v.longitudeWest)
    }

    override fun toLatLon(x: Float, y: Float): Pair<Double, Double>? =
        map?.projection?.fromScreenLocation(PointF(x, y))?.let { it.latitude to it.longitude }

    override fun projectScreen(lat: Double, lon: Double): PointF? =
        runCatching { map?.projection?.toScreenLocation(LatLng(lat, lon)) }.getOrNull()

    override fun metersPerPixelAtLat(lat: Double): Double =
        runCatching { map?.projection?.getMetersPerPixelAtLatitude(lat) ?: Double.NaN }.getOrDefault(Double.NaN)

    override fun setMinZoom(zoom: Double) { map?.setMinZoomPreference(zoom) }

    override fun query(box: RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit) {
        val m = map ?: return result(layers.map { emptyList() })
        result(layers.map { (layer, key) ->
            runCatching { m.queryRenderedFeatures(box, layer).mapNotNull { f -> if (f.hasProperty(key)) f.getStringProperty(key) else null } }
                .getOrDefault(emptyList())
        })
    }

    // ---- your position ----

    private fun component(): LocationComponent? =
        map?.let { runCatching { it.locationComponent }.getOrNull() }?.takeIf { it.isLocationComponentActivated }

    override val puckOn get() = component() != null

    private var marker: Bitmap? = null
    private var markerName: String? = null
    private var markerVersion = 0

    private fun puckOptions(name: String?) = LocationComponentOptions.builder(context)
        .pulseEnabled(false)
        .accuracyAnimationEnabled(false)
        .trackingGesturesManagement(true) // a one-finger pan stops following; a pinch keeps it
        .apply {
            if (name != null) {
                // The marker keeps its size at every zoom (it is drawn at the size chosen in Settings).
                gpsName(name).foregroundName(name).foregroundStaleName(name).bearingName(name)
                    // The engine's own arrow, ring and shadow behind it are replaced by nothing.
                    .backgroundName(BLANK).backgroundStaleName(BLANK).elevation(0f)
                    .minZoomIconScale(1f).maxZoomIconScale(1f)
            }
        }.build()

    /** The marker's picture in the current style (a style that loads later needs it again). */
    private fun addMarkerImage(style: Style) {
        marker?.let { b -> markerName?.let { style.addImage(it, b); style.addImage(BLANK, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)) } }
    }

    // No 3D models on MapLibre: the flat picture.
    override val draws3dMarker = false
    override fun setMarker3d(model: java.io.File?, size: Float) {}

    override fun setMarker(bitmap: Bitmap?) {
        marker = bitmap
        markerName = bitmap?.let { "rf-puck-${++markerVersion}" }
        val style = style()
        if (style != null) addMarkerImage(style)
        // A running puck changes picture at once (the name is new, so the old one is not reused).
        component()?.applyStyle(puckOptions(markerName))
    }

    @SuppressLint("MissingPermission") // checked first
    override fun startPuck(): Boolean {
        val m = map ?: return false
        val style = style() ?: return false
        if (!Permissions.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return false
        val lc = m.locationComponent
        // Light / dark switch: the component moves to the new style by itself.
        if (!lc.isLocationComponentActivated) {
            addMarkerImage(style)
            val options = puckOptions(markerName)
            // Fixes come from the screen, so slow ones can keep the last real heading before the map sees them.
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(options)
                .build())
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { onFollowDismissed?.invoke() }
                override fun onCameraTrackingChanged(mode: Int) {}
            })
        }
        return true
    }

    override fun pushFix(fix: Location) { component()?.forceLocationUpdate(fix) }

    private var reapply: Runnable? = null

    override fun follow(mode: MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) =
        follow(mode, topPaddingPx, tilt as Double?, zoom, ms)

    override fun follow(mode: MapEngine.Follow, topPaddingPx: Double?, tilt: Double?, zoom: Double?, ms: Long) {
        val lc = component() ?: return
        reapply?.let { view.removeCallbacks(it) }; reapply = null
        if (mode == MapEngine.Follow.NONE) { lc.cameraMode = CameraMode.NONE; return }
        // The component ignores padding / tilt / zoom while its camera mode is changing, so they go first,
        // and once more when the change is over (a mode switch no longer leaves the old angle or zoom).
        fun look(duration: Long) {
            topPaddingPx?.let { lc.paddingWhileTracking(doubleArrayOf(0.0, it, 0.0, 0.0), duration) }
            tilt?.let { lc.tiltWhileTracking(it, duration) }
            zoom?.let { lc.zoomWhileTracking(it, duration) }
        }
        look(ms)
        val wantMode = if (mode == MapEngine.Follow.HEADING) CameraMode.TRACKING_GPS else CameraMode.TRACKING
        // Never 0 ms: the component only stops "transitioning" when an animated move ends, and a 0 ms move
        // is not one, so it would never follow again (not even after the follow button: same mode, ignored).
        if (lc.cameraMode != wantMode) {
            lc.setCameraMode(wantMode, ms.coerceAtLeast(1), null, null, null, null)
        }
        // Also after an instant switch (the map opening already in 3D): the angle set above was dropped.
        Runnable { if (component() != null) look(if (ms > 0) 400 else 0) }.also { reapply = it; view.postDelayed(it, ms + 250) }
    }

    override fun placeBranding(topPx: Int, atEnd: Boolean) {}
}
