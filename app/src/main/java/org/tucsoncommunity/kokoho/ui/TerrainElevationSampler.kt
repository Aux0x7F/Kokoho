package org.tucsoncommunity.kokoho.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.tucsoncommunity.kokoho.posture.KokohoHubClient
import org.tucsoncommunity.kokoho.posture.VpnGeoGate
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Self-hosted Terrarium DEM sampler and 3D camera / airborne elevation math.
 *
 * Serves two roles on MapLibre Android 13.6.1:
 *  1. Samples local ground elevation (meters MSL) from 256x256 Terrarium PNG tiles
 *     (`/terrain/{z}/{x}/{y}.png`, encoding = "terrarium", maxzoom = 13) with an
 *     analytical Southern Arizona topographic fallback while tiles are in flight or offline.
 *  2. Computes smooth camera zoom / orbit-point elevation compensation (`deltaElevM`)
 *     and 3D airborne height-above-ground (`hAglM`) so neither the camera nor ADS-B /
 *     Remote ID pucks ever slam into or render beneath Foothills / mountain terrain.
 */
internal object TerrainElevationSampler {

    const val TERRAIN_TILE_URL = "https://kokoho.tucsoncommunity.online/terrain/{z}/{x}/{y}.png"
    const val TERRAIN_ENCODING = "terrarium"
    const val TERRAIN_TILE_SIZE = 256
    const val TERRAIN_MAX_ZOOM = 13
    const val TERRAIN_SAMPLE_ZOOM = 11
    const val TERRAIN_EXAGGERATION = 1.0f

    const val MIN_AIRBORNE_AGL_M = 30.0
    const val DEFAULT_UNKNOWN_AIRCRAFT_AGL_M = 300.0
    const val MIN_DRONE_AGL_M = 15.0
    const val DEFAULT_UNKNOWN_DRONE_AGL_M = 60.0
    /** Maximum FAA Part 107 sUAS airspace ceiling (400 ft AGL = 121.92 m) for unpinpointed drone domes. */
    const val MAX_DRONE_AIRSPACE_M = 121.92

    private const val MAX_CACHED_TILES = 32
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_500
    private const val EARTH_METERS_PER_PX_Z0_512 = 78_271.51696

    data class TilePixelCoord(
        val zoom: Int,
        val tileX: Int,
        val tileY: Int,
        val pixelX: Double,
        val pixelY: Double
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Any()
    private val tileCache = object : LinkedHashMap<String, FloatArray>(MAX_CACHED_TILES + 4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > MAX_CACHED_TILES
    }
    private val inFlightKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun tileUrlTemplate(context: Context?): String {
        val base = context?.let { runCatching { KokohoHubClient.hubUrl(it).trim().trimEnd('/') }.getOrNull() }
        return if (!base.isNullOrBlank()) "$base/terrain/{z}/{x}/{y}.png" else TERRAIN_TILE_URL
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
        inFlightKeys.clear()
    }

    /**
     * Smooth analytical Southern Arizona basin-and-range elevation fallback (meters MSL)
     * used while a Terrarium tile is still loading or when network egress is unavailable.
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
     * Returns local ground elevation (meters MSL) at `(lat, lon)`.
     * Reads synchronously from cached Terrarium tiles if loaded; otherwise kicks off an async
     * tile fetch (when permitted by [VpnGeoGate]) and returns [estimateRegionalElevationM].
     */
    fun sampleElevationM(
        context: Context?,
        lat: Double,
        lon: Double,
        allowAsyncFetch: Boolean = true,
        onLoaded: (() -> Unit)? = null
    ): Double {
        if (!lat.isFinite() || !lon.isFinite()) return 0.0
        for (z in intArrayOf(TERRAIN_MAX_ZOOM, 12, TERRAIN_SAMPLE_ZOOM)) {
            val tc = tileCoords(lat, lon, z)
            val key = "${tc.zoom}/${tc.tileX}/${tc.tileY}"
            val grid = synchronized(cacheLock) { tileCache[key] }
            if (grid != null) {
                return sampleGridBilinear(grid, TERRAIN_TILE_SIZE, tc.pixelX, tc.pixelY)
            }
        }
        if (allowAsyncFetch && context != null && !VpnGeoGate.isBlockedTucson()) {
            val tc = tileCoords(lat, lon, TERRAIN_SAMPLE_ZOOM)
            fetchTileAsync(context.applicationContext ?: context, tc.zoom, tc.tileX, tc.tileY, onLoaded)
        }
        return estimateRegionalElevationM(lat, lon)
    }

    private fun fetchTileAsync(
        appContext: Context,
        zoom: Int,
        tileX: Int,
        tileY: Int,
        onLoaded: (() -> Unit)?
    ) {
        val key = "$zoom/$tileX/$tileY"
        if (!inFlightKeys.add(key)) return
        scope.launch {
            try {
                if (VpnGeoGate.checkHubEgressAllowedBlocking(appContext, forceSeatedCheck = false) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
                    return@launch
                }
                val urlStr = tileUrlTemplate(appContext)
                    .replace("{z}", zoom.toString())
                    .replace("{x}", tileX.toString())
                    .replace("{y}", tileY.toString())
                val bytes = downloadTileBytes(urlStr) ?: return@launch
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                try {
                    val w = bmp.width
                    val h = bmp.height
                    if (w <= 0 || h <= 0) return@launch
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    val grid = if (w == TERRAIN_TILE_SIZE && h == TERRAIN_TILE_SIZE) {
                        FloatArray(TERRAIN_TILE_SIZE * TERRAIN_TILE_SIZE) { idx ->
                            decodeTerrariumArgb(pixels[idx]).toFloat()
                        }
                    } else {
                        FloatArray(TERRAIN_TILE_SIZE * TERRAIN_TILE_SIZE) { idx ->
                            val sx = ((idx % TERRAIN_TILE_SIZE) * w / TERRAIN_TILE_SIZE).coerceIn(0, w - 1)
                            val sy = ((idx / TERRAIN_TILE_SIZE) * h / TERRAIN_TILE_SIZE).coerceIn(0, h - 1)
                            decodeTerrariumArgb(pixels[sy * w + sx]).toFloat()
                        }
                    }
                    synchronized(cacheLock) {
                        tileCache[key] = grid
                    }
                    if (onLoaded != null) {
                        Handler(Looper.getMainLooper()).post { runCatching { onLoaded() } }
                    }
                } finally {
                    bmp.recycle()
                }
            } catch (_: Throwable) {
            } finally {
                inFlightKeys.remove(key)
            }
        }
    }

    private fun downloadTileBytes(urlStr: String): ByteArray? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = true
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

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

    /**
     * Computes the compensated zoom level when the camera's locked/orbit ground surface shifts
     * by [deltaElevM] meters (`> 0` = ground rose; `< 0` = ground dropped).
     *
     * Raising the camera eye distance by `+deltaElevM` (`newDist = curDist + deltaElevM`) keeps
     * camera height above the orbit point constant so the view neither slams into high terrain
     * (e.g. Mt. Lemmon ~2,700 m) nor jumps wildly.
     */
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
     * Ground-plane pivot offset `(lat, lon)` for orbiting a 3D point sitting [elevationAbovePlaneM]
     * meters above the Mercator `z = 0` plane at camera pitch [tiltDeg] and [bearingDeg].
     */
    fun computeOrbitGroundOffsetLatLon(
        orbitLat: Double,
        orbitLon: Double,
        elevationAbovePlaneM: Double,
        tiltDeg: Double,
        bearingDeg: Double
    ): Pair<Double, Double> {
        if (elevationAbovePlaneM <= 0.5 || tiltDeg <= 1.0) return orbitLat to orbitLon
        val groundShiftM = (elevationAbovePlaneM * tan(Math.toRadians(tiltDeg.coerceIn(0.0, 62.0))))
            .coerceIn(0.0, 6_000.0)
        return MapGeo.offset(orbitLat, orbitLon, groundShiftM, bearingDeg)
    }

    /**
     * Effective vertical apex / cap ceiling height (`0..121.92 m`, i.e. `<= 400 ft AGL`)
     * for an unpinpointed drone's 3D uncertainty hemisphere / capped dome.
     */
    fun droneDomeCeilingHeightM(uncertaintyRadiusM: Double): Double =
        uncertaintyRadiusM.coerceAtLeast(0.0).coerceAtMost(MAX_DRONE_AIRSPACE_M)

    /**
     * Horizontal slice radius `r(h) = sqrt(max(0, R^2 - h^2))` at height [heightM] (`0 <= h <= hMax`)
     * for an unpinpointed drone's 3D uncertainty sphere of base radius [uncertaintyRadiusM].
     */
    fun droneDomeSliceRadiusM(uncertaintyRadiusM: Double, heightM: Double): Double {
        val r = uncertaintyRadiusM.coerceAtLeast(0.0)
        val h = heightM.coerceIn(0.0, droneDomeCeilingHeightM(r))
        return kotlin.math.sqrt((r * r - h * h).coerceAtLeast(0.0))
    }

    /**
     * Computes 3D height above local terrain (meters AGL) for an airborne aircraft or Remote ID drone,
     * guaranteeing a positive clearance so pucks never render underground on Foothills / mountain terrain.
     *
     *  - ADS-B aircraft (`isAircraft == true`): [altitudeFtRaw] is feet MSL -> subtracts [groundElevM]
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
