package org.tucsoncommunity.kokoho.ui

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Instant analytical Southern Arizona true-MSL elevation model and 3D airspace geometry math.
 *
 * MapLibre Native Android (`13.6.1`) renders on a flat `z = 0` Mercator ground plane without 3D DEM
 * vertex mesh displacement. This helper serves `Airborne3dOverlayView` with zero network tile fetches:
 *  1. Estimates local true-MSL ground elevation (~730-880 m / ~2,400-2,900 ft MSL across the Tucson
 *     basin, rising smoothly across the Catalina Foothills, Santa Catalinas, Rincons, Tucson Mtns,
 *     and Santa Ritas) so ADS-B barometric/geometric `altitudeFt` (MSL) subtracts local ground MSL
 *     to render aircraft at their true AGL height above the mobile map plane.
 *  2. Computes volumetric 3D hemisphere / capped-sphere ceilings and horizontal slice radii
 *     (`r(h) = sqrt(max(0, R^2 - h^2))`) for both unpinpointed sUAS (`<= 400 ft / 121.92 m AGL`)
 *     and unpinpointed aircraft (`<= 8,000 ft / 2,438.4 m AGL` or known Mode-S AGL altitude).
 */
internal object TerrainElevationSampler {

    const val TERRAIN_ENCODING = "terrarium"
    const val TERRAIN_TILE_SIZE = 256
    const val TERRAIN_MAX_ZOOM = 13
    const val TERRAIN_SAMPLE_ZOOM = 11

    const val MIN_AIRBORNE_AGL_M = 30.0
    const val DEFAULT_UNKNOWN_AIRCRAFT_AGL_M = 300.0
    const val MIN_DRONE_AGL_M = 15.0
    const val DEFAULT_UNKNOWN_DRONE_AGL_M = 60.0

    /** Maximum FAA Part 107 sUAS airspace ceiling (400 ft AGL = 121.92 m) for unpinpointed drone domes. */
    const val MAX_DRONE_AIRSPACE_M = 121.92

    /** Tactical airspace ceiling (8,000 ft AGL = 2,438.4 m) for unpinpointed aircraft 3D spheres. */
    const val MAX_AIRCRAFT_AIRSPACE_M = 2438.4

    private const val MAX_CACHED_TILES = 16
    private const val EARTH_METERS_PER_PX_Z0_512 = 78_271.51696

    data class TilePixelCoord(
        val zoom: Int,
        val tileX: Int,
        val tileY: Int,
        val pixelX: Double,
        val pixelY: Double
    )

    private val cacheLock = Any()
    private val tileCache = object : LinkedHashMap<String, FloatArray>(MAX_CACHED_TILES + 4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > MAX_CACHED_TILES
    }

    /** Terrarium formula: `(R * 256 + G + B / 256) - 32768` meters MSL. */
    fun decodeTerrariumMeters(r: Int, g: Int, b: Int): Double {
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        return (rc * 256.0 + gc + bc / 256.0) - 32768.0
    }

    fun decodeTerrariumArgb(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return decodeTerrariumMeters(r, g, b)
    }

    /** Web Mercator tile + fractional pixel coordinates inside `[0, tileSize)`. */
    fun tileCoords(lat: Double, lon: Double, zoom: Int, tileSize: Int = TERRAIN_TILE_SIZE): TilePixelCoord {
        val z = zoom.coerceIn(0, TERRAIN_MAX_ZOOM)
        val n = 1 shl z
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val wrappedLon = (((lon + 180.0) % 360.0) + 360.0) % 360.0 - 180.0
        val latRad = Math.toRadians(clampedLat)
        val xFloat = ((wrappedLon + 180.0) / 360.0) * n
        val yFloat = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) * 0.5) * n
        val tx = floor(xFloat).toInt().coerceIn(0, n - 1)
        val ty = floor(yFloat).toInt().coerceIn(0, n - 1)
        val px = ((xFloat - tx) * tileSize).coerceIn(0.0, (tileSize - 1).toDouble())
        val py = ((yFloat - ty) * tileSize).coerceIn(0.0, (tileSize - 1).toDouble())
        return TilePixelCoord(z, tx, ty, px, py)
    }

    /** Bilinear interpolation across a `tileSize x tileSize` elevation grid. */
    fun sampleGridBilinear(grid: FloatArray, tileSize: Int, px: Double, py: Double): Double {
        if (grid.size < tileSize * tileSize) return 0.0
        val x = px.coerceIn(0.0, (tileSize - 1).toDouble())
        val y = py.coerceIn(0.0, (tileSize - 1).toDouble())
        val x0 = floor(x).toInt().coerceIn(0, tileSize - 1)
        val y0 = floor(y).toInt().coerceIn(0, tileSize - 1)
        val x1 = min(tileSize - 1, x0 + 1)
        val y1 = min(tileSize - 1, y0 + 1)
        val fx = x - x0
        val fy = y - y0
        val v00 = grid[y0 * tileSize + x0].toDouble()
        val v10 = grid[y0 * tileSize + x1].toDouble()
        val v01 = grid[y1 * tileSize + x0].toDouble()
        val v11 = grid[y1 * tileSize + x1].toDouble()
        val top = v00 * (1.0 - fx) + v10 * fx
        val bot = v01 * (1.0 - fx) + v11 * fx
        return top * (1.0 - fy) + bot * fy
    }

    internal fun putTileForTest(zoom: Int, tileX: Int, tileY: Int, grid: FloatArray) {
        synchronized(cacheLock) {
            tileCache["$zoom/$tileX/$tileY"] = grid
        }
    }

    internal fun clearCacheForTest() {
        synchronized(cacheLock) {
            tileCache.clear()
        }
    }

    /**
     * Instant analytical Southern Arizona basin-and-range elevation model (true meters MSL).
     * Never returns negative values in Arizona; Tucson sits at ~730-880 m (~2,400-2,900 ft MSL, KTUS ~806 m).
     */
    fun estimateRegionalElevationM(lat: Double, lon: Double): Double {
        if (!lat.isFinite() || !lon.isFinite()) return 0.0
        if (lat !in 31.0..33.6 || lon !in -112.5..-109.5) return 0.0
        // Tucson basin floor slopes gently from ~840m SE (KTUS 806m / Vail 980m) down to ~620m NW (Marana).
        val baseSlope = 742.0 - (lat - 32.2226) * 420.0 + (lon - (-110.9747)) * 360.0
        val basinM = baseSlope.coerceIn(520.0, 1_080.0)

        fun peak(pLat: Double, pLon: Double, gainM: Double, sigmaLat: Double, sigmaLon: Double): Double {
            val dLat = (lat - pLat) / sigmaLat
            val dLon = (lon - pLon) / sigmaLon
            return gainM * exp(-0.5 * (dLat * dLat + dLon * dLon))
        }

        // Santa Catalinas (Mt. Lemmon ~2,791 m), Catalina Foothills apron, Rincons (Mica Mtn ~2,641 m),
        // Tucson Mountains (Wasson Peak ~1,429 m), Santa Ritas (Mt. Wrightson ~2,882 m).
        val catalinas = peak(32.4430, -110.7880, 2040.0, 0.075, 0.105)
        val foothills = peak(32.3250, -110.8850, 220.0, 0.045, 0.085)
        val rincons = peak(32.2140, -110.5440, 1840.0, 0.075, 0.085)
        val tucsonMtns = peak(32.2750, -111.1470, 680.0, 0.055, 0.048)
        val santaRitas = peak(31.6960, -110.8480, 2050.0, 0.070, 0.075)
        return (basinM + catalinas + foothills + rincons + tucsonMtns + santaRitas).coerceIn(300.0, 3_000.0)
    }

    /**
     * Returns local ground elevation (true meters MSL) at `(lat, lon)` synchronously with zero network I/O.
     * Reads from in-memory [tileCache] when populated in tests, otherwise returns [estimateRegionalElevationM].
     */
    @Suppress("UNUSED_PARAMETER")
    fun sampleElevationM(
        context: Context?,
        lat: Double,
        lon: Double,
        allowAsyncFetch: Boolean = false,
        onLoaded: (() -> Unit)? = null
    ): Double {
        if (!lat.isFinite() || !lon.isFinite()) return 0.0
        val hasCached = synchronized(cacheLock) { tileCache.isNotEmpty() }
        if (hasCached) {
            for (z in intArrayOf(TERRAIN_MAX_ZOOM, 12, TERRAIN_SAMPLE_ZOOM)) {
                val tc = tileCoords(lat, lon, z)
                val key = "${tc.zoom}/${tc.tileX}/${tc.tileY}"
                val grid = synchronized(cacheLock) { tileCache[key] }
                if (grid != null) {
                    return sampleGridBilinear(grid, TERRAIN_TILE_SIZE, tc.pixelX, tc.pixelY)
                }
            }
        }
        return estimateRegionalElevationM(lat, lon)
    }

    /** Nominal camera eye distance (meters) to the projection plane at `(lat, zoom)`. */
    fun nominalCameraDistanceM(
        lat: Double,
        zoom: Double,
        viewportHeightPx: Int,
        fovDeg: Double = 36.87
    ): Double {
        val mpp = EARTH_METERS_PER_PX_Z0_512 * cos(Math.toRadians(lat.coerceIn(-85.0, 85.0))) / 2.0.pow(zoom)
        val halfTan = tan(Math.toRadians(fovDeg.coerceIn(15.0, 75.0) * 0.5)).coerceAtLeast(0.15)
        return (mpp * (viewportHeightPx.coerceAtLeast(320) * 0.5) / halfTan).coerceAtLeast(40.0)
    }

    fun computeZoomForElevationDelta(
        lat: Double,
        currentZoom: Double,
        deltaElevM: Double,
        viewportHeightPx: Int,
        fovDeg: Double = 36.87
    ): Double {
        if (!deltaElevM.isFinite() || abs(deltaElevM) < 2.0) return currentZoom
        val curDist = nominalCameraDistanceM(lat, currentZoom, viewportHeightPx, fovDeg)
        val targetDist = (curDist + deltaElevM).coerceIn(curDist * 0.35, curDist * 3.0)
        val rawZoomDelta = -ln(targetDist / curDist) / ln(2.0)
        val clampedDelta = rawZoomDelta.coerceIn(-1.6, 1.6)
        return (currentZoom + clampedDelta).coerceIn(2.0, 20.0)
    }

    /**
     * Generalized vertical apex / cap ceiling height `hMax = min(R, maxCeilingM)`
     * for a 3D uncertainty hemisphere / capped airspace sphere.
     */
    fun airspaceDomeCeilingHeightM(
        uncertaintyRadiusM: Double,
        maxCeilingM: Double = MAX_DRONE_AIRSPACE_M
    ): Double = uncertaintyRadiusM.coerceAtLeast(0.0).coerceAtMost(maxCeilingM.coerceAtLeast(0.0))

    /**
     * Generalized horizontal slice radius `r(h) = sqrt(max(0, R^2 - h^2))` at height [heightM] (`0 <= h <= hMax`)
     * for a 3D uncertainty sphere of base radius [uncertaintyRadiusM] capped at [maxCeilingM].
     */
    fun airspaceDomeSliceRadiusM(
        uncertaintyRadiusM: Double,
        heightM: Double,
        maxCeilingM: Double = MAX_DRONE_AIRSPACE_M
    ): Double {
        val r = uncertaintyRadiusM.coerceAtLeast(0.0)
        val h = heightM.coerceIn(0.0, airspaceDomeCeilingHeightM(r, maxCeilingM))
        return sqrt((r * r - h * h).coerceAtLeast(0.0))
    }

    /**
     * Effective vertical apex / cap ceiling height (`0..121.92 m`, i.e. `<= 400 ft AGL`)
     * for an unpinpointed drone's 3D uncertainty hemisphere / capped dome.
     */
    fun droneDomeCeilingHeightM(uncertaintyRadiusM: Double): Double =
        airspaceDomeCeilingHeightM(uncertaintyRadiusM, MAX_DRONE_AIRSPACE_M)

    /**
     * Horizontal slice radius `r(h) = sqrt(max(0, R^2 - h^2))` at height [heightM] (`0 <= h <= hMax`)
     * for an unpinpointed drone's 3D uncertainty sphere of base radius [uncertaintyRadiusM].
     */
    fun droneDomeSliceRadiusM(uncertaintyRadiusM: Double, heightM: Double): Double =
        airspaceDomeSliceRadiusM(uncertaintyRadiusM, heightM, MAX_DRONE_AIRSPACE_M)

    /**
     * Effective vertical apex / cap ceiling height (`hMax = min(R, maxCeilingM)`) for an unpinpointed
     * aircraft's 3D uncertainty sphere: uses [knownAglCeilingM] when Mode-S altitude is known,
     * otherwise defaults to [MAX_AIRCRAFT_AIRSPACE_M] (`8,000 ft / 2,438.4 m AGL`).
     */
    fun aircraftDomeCeilingHeightM(
        uncertaintyRadiusM: Double,
        knownAglCeilingM: Double? = null
    ): Double {
        val maxCeilingM = knownAglCeilingM?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtLeast(MIN_AIRBORNE_AGL_M)
            ?: MAX_AIRCRAFT_AIRSPACE_M
        return airspaceDomeCeilingHeightM(uncertaintyRadiusM, maxCeilingM)
    }

    /**
     * Horizontal slice radius `r(h) = sqrt(max(0, R^2 - h^2))` at height [heightM] for an unpinpointed aircraft.
     */
    fun aircraftDomeSliceRadiusM(
        uncertaintyRadiusM: Double,
        heightM: Double,
        knownAglCeilingM: Double? = null
    ): Double {
        val maxCeilingM = knownAglCeilingM?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtLeast(MIN_AIRBORNE_AGL_M)
            ?: MAX_AIRCRAFT_AIRSPACE_M
        return airspaceDomeSliceRadiusM(uncertaintyRadiusM, heightM, maxCeilingM)
    }

    /**
     * Computes 3D height above local terrain (meters AGL) for an airborne aircraft or Remote ID drone,
     * guaranteeing a positive clearance so pucks never render underground on Foothills / mountain terrain.
     *
     *  - ADS-B / Mode-S aircraft (`isAircraft == true`): [altitudeFtRaw] is feet MSL -> subtracts [groundElevM]
     *    and enforces `>= MIN_AIRBORNE_AGL_M`.
     *  - Remote ID / tactical drones (`isAircraft == false`): [altitudeFtRaw] is feet AGL unless the
     *    transmitter sent geodetic WGS84 MSL (`> 1,800 ft` over `> 450 m` desert terrain), in which
     *    case [groundElevM] is subtracted automatically, enforcing `>= MIN_DRONE_AGL_M`.
     */
    fun computeAirborneHeightAboveGroundM(
        isAircraft: Boolean,
        altitudeFtRaw: Int?,
        groundElevM: Double
    ): Double {
        val safeGroundM = if (groundElevM.isFinite()) groundElevM.coerceAtLeast(0.0) else 0.0
        return if (isAircraft) {
            if (altitudeFtRaw == null || altitudeFtRaw <= 0) {
                DEFAULT_UNKNOWN_AIRCRAFT_AGL_M
            } else {
                val altMslM = altitudeFtRaw * 0.3048
                (altMslM - safeGroundM).coerceAtLeast(MIN_AIRBORNE_AGL_M)
            }
        } else {
            if (altitudeFtRaw == null || altitudeFtRaw <= 0) {
                DEFAULT_UNKNOWN_DRONE_AGL_M
            } else {
                val rawM = altitudeFtRaw * 0.3048
                val aglM = if (altitudeFtRaw > 1_800 && safeGroundM > 450.0) {
                    rawM - safeGroundM
                } else {
                    rawM
                }
                aglM.coerceAtLeast(MIN_DRONE_AGL_M)
            }
        }
    }
}
