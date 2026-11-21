package org.tucsoncommunity.kokoho.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Hardware-accelerated 3D airborne overlay synchronized with MapLibre camera gestures.
 *
 * Renders two 3D airspace layers in perspective:
 *  1. **Unpinpointed Drone & Aircraft 3D Airspace Domes (`pitch > 4.0 deg`)**:
 *     Translucent 3D spheres / capped hemispheres (`hMax = min(R, maxCeilingM)`,
 *     horizontal slice `r(h) = sqrt(R^2 - h^2)`) capped at `400 ft / 121.92 m AGL` for sUAS
 *     and `8,000 ft / 2,438.4 m AGL` (or known Mode-S AGL) for unpinpointed aircraft, with soft
 *     translucent fill (`alpha ~ 0.16` so 3D buildings inside remain visible), base ring,
 *     mid-latitude ring, ceiling cap ring, N-S / E-W spherical meridian arches, and optional
 *     crisp ground operator dot + 3D tether (never a bubble on operators).
 *  2. **Pinpointed ADS-B Aircraft & Remote ID Drone Pucks (`pitch > 6.0 deg`)**:
 *     Projects altitude (`altitudeFt` MSL / `altFtAgl` AGL) above local Southern AZ true-MSL ground
 *     elevation so pucks render at true AGL height above the map plane, with ground shadow ellipse,
 *     dashed vertical nadir plumb line, pilot-to-drone 3D tether, badge icon, and altitude label.
 */
internal class Airborne3dOverlayView(context: Context) : View(context) {

    companion object {
        const val DOME_PITCH_THRESHOLD_DEG = 4.0
        const val PITCH_THRESHOLD_DEG = 6.0
        private const val PITCH_FULL_BLEND_SPAN_DEG = 8.0
        private const val DOME_BLEND_SPAN_DEG = 6.0
        private const val DEFAULT_FOV_DEG = 36.87
        private const val RING_SEGMENTS = 32
        private const val MERIDIAN_STEPS_PER_SIDE = 7

        data class Projected3dPoint(
            val groundX: Float,
            val groundY: Float,
            val airX: Float,
            val airY: Float,
            val perspectiveScale: Float,
            val liftPx: Float
        )

        /**
         * Projects a point at [heightAboveGroundM] meters directly above ground screen pixel
         * `(groundX, groundY)` under a perspective camera tilted by [cameraTiltDeg].
         */
        fun projectElevatedPoint3d(
            groundX: Float,
            groundY: Float,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            topPaddingPx: Double,
            cameraTiltDeg: Double,
            metersPerPixelAtTarget: Double,
            heightAboveGroundM: Double,
            fovDeg: Double = DEFAULT_FOV_DEG,
            minPitchDeg: Double = PITCH_THRESHOLD_DEG,
            blendSpanDeg: Double = PITCH_FULL_BLEND_SPAN_DEG
        ): Projected3dPoint {
            if (cameraTiltDeg <= minPitchDeg ||
                heightAboveGroundM <= 0.0 ||
                viewportWidthPx <= 0 ||
                viewportHeightPx <= 0 ||
                !metersPerPixelAtTarget.isFinite() ||
                metersPerPixelAtTarget <= 0.0
            ) {
                return Projected3dPoint(groundX, groundY, groundX, groundY, 1f, 0f)
            }
            val blend = ((cameraTiltDeg - minPitchDeg) / blendSpanDeg.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val cx = viewportWidthPx * 0.5
            val cy = (viewportHeightPx + topPaddingPx.coerceIn(0.0, viewportHeightPx * 0.65)) * 0.5
            val halfTanFov = tan(Math.toRadians(fovDeg.coerceIn(15.0, 75.0) * 0.5)).coerceAtLeast(0.15)
            val focalPx = (viewportHeightPx * 0.5) / halfTanFov
            val dCenter = (focalPx * metersPerPixelAtTarget).coerceAtLeast(50.0)
            val theta = Math.toRadians(cameraTiltDeg.coerceIn(0.0, 65.0))
            val cosTheta = cos(theta).coerceAtLeast(0.35)
            val sinTheta = sin(theta)

            val dy0 = cy - groundY
            val depthDenom = (1.0 + (dy0 / focalPx) * tan(theta)).coerceIn(0.28, 3.5)
            val d0 = dCenter / depthDenom

            val hEff = (heightAboveGroundM * blend).coerceAtMost(d0 * 0.58 / cosTheta)
            val d1 = (d0 - hEff * cosTheta).coerceAtLeast(d0 * 0.40)
            val s = (d0 / d1).coerceIn(1.0, 1.35)

            var dx = (cx + (groundX - cx) * s) - groundX
            var dy = (cy + (groundY - cy) * s - (focalPx * hEff * sinTheta) / d1) - groundY

            val distPx = hypot(dx, dy)
            val maxLiftPx = max(48.0, viewportHeightPx * 0.42)
            if (distPx > maxLiftPx && distPx > 1e-3) {
                val clampScale = maxLiftPx / distPx
                dx *= clampScale
                dy *= clampScale
            }
            val airX = (groundX + dx).toFloat()
            val airY = (groundY + dy).toFloat()
            val finalLift = hypot((airX - groundX).toDouble(), (airY - groundY).toDouble()).toFloat()
            return Projected3dPoint(
                groundX = groundX,
                groundY = groundY,
                airX = airX,
                airY = airY,
                perspectiveScale = s.toFloat().coerceIn(0.95f, 1.18f),
                liftPx = finalLift
            )
        }

        /** Andrew's monotone chain 2D convex hull over screen sample points for exact 3D dome silhouette. */
        internal fun computeConvexHull(points: List<PointF>): List<PointF> {
            if (points.size <= 3) return points
            val sorted = points.sortedWith(compareBy<PointF> { it.x }.thenBy { it.y })
            fun cross(o: PointF, a: PointF, b: PointF): Float =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = ArrayList<PointF>(sorted.size)
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                    lower.removeAt(lower.size - 1)
                }
                lower += p
            }
            val upper = ArrayList<PointF>(sorted.size)
            for (i in sorted.indices.reversed()) {
                val p = sorted[i]
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                    upper.removeAt(upper.size - 1)
                }
                upper += p
            }
            if (lower.isNotEmpty()) lower.removeAt(lower.size - 1)
            if (upper.isNotEmpty()) upper.removeAt(upper.size - 1)
            return lower + upper
        }

        internal fun isPointInPolygon(x: Float, y: Float, poly: List<PointF>): Boolean {
            if (poly.size < 3) return false
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val xi = poly[i].x
                val yi = poly[i].y
                val xj = poly[j].x
                val yj = poly[j].y
                val intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / ((yj - yi).let { if (kotlin.math.abs(it) < 1e-5f) 1e-5f else it }) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }
    }

    data class AirborneTarget(
        val entityUuid: String,
        val lat: Double,
        val lon: Double,
        val isAircraft: Boolean,
        val isDrone: Boolean,
        val rotateWithMap: Boolean,
        val trackDeg: Double,
        val altitudeFtRaw: Int?,
        val colorArgb: Int,
        val badgeBitmap: Bitmap,
        val label: String,
        val operatorLat: Double? = null,
        val operatorLon: Double? = null
    )

    data class DroneAirspaceDome(
        val entityUuid: String,
        val lat: Double,
        val lon: Double,
        val radiusM: Double,
        val colorArgb: Int,
        val opacity: Float,
        val badgeBitmap: Bitmap,
        val label: String,
        val maxCeilingM: Double = TerrainElevationSampler.MAX_DRONE_AIRSPACE_M,
        val isAircraft: Boolean = false,
        val operatorLat: Double? = null,
        val operatorLon: Double? = null
    )

    data class StandaloneOperatorPin(
        val entityUuid: String,
        val lat: Double,
        val lon: Double
    )

    data class OverlayHit(
        val entityUuid: String,
        val lat: Double,
        val lon: Double,
        val isPilotHit: Boolean = false
    )

    private data class RenderedHit(
        val target: AirborneTarget,
        val airX: Float,
        val airY: Float,
        val groundX: Float,
        val groundY: Float,
        val opScreen: PointF? = null
    )

    private data class RenderedDomeHit(
        val dome: DroneAirspaceDome,
        val hull: List<PointF>,
        val badgeX: Float,
        val badgeY: Float,
        val opScreen: PointF? = null
    )

    private data class RenderedOperatorHit(
        val pin: StandaloneOperatorPin,
        val screenX: Float,
        val screenY: Float
    )

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private val density = resources.displayMetrics.density
    private var mapEngine: MapEngine? = null
    private var targets: List<AirborneTarget> = emptyList()
    private var domes: List<DroneAirspaceDome> = emptyList()
    private var standaloneOperators: List<StandaloneOperatorPin> = emptyList()
    private var selectedEntityUuid: String? = null
    private var topPaddingPx: Double = 0.0
    private var selectionRingBitmap: Bitmap? = null
    private val renderedHits = ArrayList<RenderedHit>()
    private val renderedDomes = ArrayList<RenderedDomeHit>()
    private val renderedOperators = ArrayList<RenderedOperatorHit>()

    private val domeSilhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val domeCapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val domeBaseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
    }
    private val domeMidRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val domeCapRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.65f * density
    }
    private val domeMeridianPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x8805080F.toInt()
    }
    private val nadirDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val plumbCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xB8090D16.toInt()
    }
    private val plumbLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(5.5f * density, 3.8f * density), 0f)
    }
    private val tetherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        color = 0xDD38BDF8.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val opFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF5E100.toInt()
    }
    private val opStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        color = 0xFF1F5FBF.toInt()
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.6f * density
        textSize = 12.2f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = 0xF0090D16.toInt()
    }
    private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 12.2f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }
    private val shadowRect = RectF()
    private val workPath = Path()

    private val pilotBadgeBitmap: Bitmap by lazy {
        MapIcons.classificationBadgeIcon(density, MapIcons.BadgeIconKind.PILOT, 0xFFD97706.toInt())
    }
    private val pilotTextFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 11.4f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = 0xFFFDE68A.toInt()
    }

    private fun drawPilotPin(canvas: Canvas, x: Float, y: Float) {
        val bmp = pilotBadgeBitmap
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(0.88f, 0.88f)
        canvas.drawBitmap(bmp, -bmp.width * 0.5f, -bmp.height * 0.5f, bitmapPaint)
        canvas.restore()
        val labelY = y + (bmp.height * 0.44f) + 12f * density
        textHaloPaint.color = 0xF0090D16.toInt()
        canvas.drawText("Drone Pilot", x, labelY, textHaloPaint)
        canvas.drawText("Drone Pilot", x, labelY, pilotTextFillPaint)
    }

    fun bind(engine: MapEngine, ringBitmap: Bitmap?) {
        mapEngine = engine
        selectionRingBitmap = ringBitmap
    }

    fun updateScene(
        newTargets: List<AirborneTarget>,
        newDomes: List<DroneAirspaceDome>,
        selectedUuid: String?,
        cameraTopPaddingPx: Double = topPaddingPx,
        newStandaloneOperators: List<StandaloneOperatorPin> = emptyList()
    ) {
        targets = newTargets
        domes = newDomes
        standaloneOperators = newStandaloneOperators
        selectedEntityUuid = selectedUuid
        topPaddingPx = cameraTopPaddingPx
        syncVisibilityAndRedraw()
    }

    fun updateTargets(
        newTargets: List<AirborneTarget>,
        selectedUuid: String?,
        cameraTopPaddingPx: Double = topPaddingPx
    ) = updateScene(newTargets, emptyList(), selectedUuid, cameraTopPaddingPx)

    fun updateSelection(selectedUuid: String?) {
        if (selectedEntityUuid == selectedUuid) return
        selectedEntityUuid = selectedUuid
        if (visibility == VISIBLE) invalidate()
    }

    fun onCameraMoved(cameraTopPaddingPx: Double = topPaddingPx): Pair<Boolean, Boolean> {
        topPaddingPx = cameraTopPaddingPx
        return syncVisibilityAndRedraw()
    }

    fun is3dActive(): Boolean {
        val tilt = mapEngine?.camera?.tilt ?: 0.0
        return tilt > PITCH_THRESHOLD_DEG
    }

    fun isDome3dActive(): Boolean {
        val tilt = mapEngine?.camera?.tilt ?: 0.0
        return tilt > DOME_PITCH_THRESHOLD_DEG
    }

    private fun syncVisibilityAndRedraw(): Pair<Boolean, Boolean> {
        val point3d = is3dActive()
        val dome3d = isDome3dActive()
        val wantVisible = (point3d && (targets.isNotEmpty() || standaloneOperators.isNotEmpty())) ||
            (dome3d && domes.isNotEmpty())
        if (wantVisible) {
            if (visibility != VISIBLE) visibility = VISIBLE
            invalidate()
        } else {
            renderedHits.clear()
            renderedDomes.clear()
            renderedOperators.clear()
            if (visibility != GONE) visibility = GONE
        }
        return point3d to dome3d
    }

    /** Hit-tests elevated 3D pucks first, then ground shadows/operators, then 3D unpinpointed drone/aircraft airspace domes. */
    fun hitTest(x: Float, y: Float, radiusPx: Float): OverlayHit? {
        if (visibility != VISIBLE) return null
        var bestTarget: AirborneTarget? = null
        var bestDist = radiusPx * 1.15f
        for (i in renderedHits.indices.reversed()) {
            val h = renderedHits[i]
            val dAir = hypot((x - h.airX).toDouble(), (y - h.airY).toDouble()).toFloat()
            if (dAir <= bestDist) {
                bestDist = dAir
                bestTarget = h.target
            }
        }
        if (bestTarget != null) return OverlayHit(bestTarget.entityUuid, bestTarget.lat, bestTarget.lon)

        for (i in renderedHits.indices.reversed()) {
            val h = renderedHits[i]
            val dGround = hypot((x - h.groundX).toDouble(), (y - h.groundY).toDouble()).toFloat()
            if (dGround <= radiusPx) return OverlayHit(h.target.entityUuid, h.target.lat, h.target.lon)
            h.opScreen?.let { op ->
                val dOp = hypot((x - op.x).toDouble(), (y - op.y).toDouble()).toFloat()
                if (dOp <= radiusPx * 1.15f) {
                    return OverlayHit(
                        entityUuid = h.target.entityUuid,
                        lat = h.target.operatorLat ?: h.target.lat,
                        lon = h.target.operatorLon ?: h.target.lon,
                        isPilotHit = true
                    )
                }
            }
        }

        for (i in renderedOperators.indices.reversed()) {
            val oh = renderedOperators[i]
            val dOp = hypot((x - oh.screenX).toDouble(), (y - oh.screenY).toDouble()).toFloat()
            if (dOp <= radiusPx * 1.15f) return OverlayHit(oh.pin.entityUuid, oh.pin.lat, oh.pin.lon, isPilotHit = true)
        }

        // Check dome apex badges and crisp operator pins first, then interior of 3D dome silhouette (front-to-back)
        for (i in renderedDomes.indices.reversed()) {
            val dh = renderedDomes[i]
            val dBadge = hypot((x - dh.badgeX).toDouble(), (y - dh.badgeY).toDouble()).toFloat()
            if (dBadge <= radiusPx * 1.15f) {
                return OverlayHit(dh.dome.entityUuid, dh.dome.lat, dh.dome.lon)
            }
            dh.opScreen?.let { op ->
                val dOp = hypot((x - op.x).toDouble(), (y - op.y).toDouble()).toFloat()
                if (dOp <= radiusPx * 1.15f) {
                    return OverlayHit(
                        entityUuid = dh.dome.entityUuid,
                        lat = dh.dome.operatorLat ?: dh.dome.lat,
                        lon = dh.dome.operatorLon ?: dh.dome.lon,
                        isPilotHit = true
                    )
                }
            }
        }
        for (i in renderedDomes.indices.reversed()) {
            val dh = renderedDomes[i]
            if (isPointInPolygon(x, y, dh.hull)) {
                return OverlayHit(dh.dome.entityUuid, dh.dome.lat, dh.dome.lon)
            }
        }
        return null
    }

    private fun buildClosedRingPath(pts: List<PointF>): Path {
        workPath.reset()
        if (pts.isEmpty()) return workPath
        workPath.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            workPath.lineTo(pts[i].x, pts[i].y)
        }
        workPath.close()
        return workPath
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderedHits.clear()
        renderedDomes.clear()
        renderedOperators.clear()
        val engine = mapEngine ?: return
        val cam = engine.camera
        if (width <= 0 || height <= 0) return
        val dome3d = cam.tilt > DOME_PITCH_THRESHOLD_DEG && domes.isNotEmpty()
        val point3d = cam.tilt > PITCH_THRESHOLD_DEG && (targets.isNotEmpty() || standaloneOperators.isNotEmpty())
        if (!dome3d && !point3d) return

        val mpp = engine.metersPerPixelAtLat(cam.lat).takeIf { it.isFinite() && it > 0.0 } ?: return
        val marginPx = 220f * density

        // ---- 1. Translucent 3D Unpinpointed Drone & Aircraft Airspace Domes (`pitch > 4 deg`) ----
        if (dome3d) {
            drawAirspaceDomes3d(canvas, engine, cam, mpp, marginPx)
        }

        // ---- 2. Pinpointed 3D ADS-B Aircraft, Remote ID Drone Pucks & Crisp Operator Dots (`pitch > 6 deg`) ----
        if (point3d) {
            drawStandaloneOperators3d(canvas, engine, marginPx)
            if (targets.isNotEmpty()) {
                drawPinpointedAirborneTargets3d(canvas, engine, cam, mpp, marginPx)
            }
        }
    }

    private fun drawStandaloneOperators3d(
        canvas: Canvas,
        engine: MapEngine,
        marginPx: Float
    ) {
        if (standaloneOperators.isEmpty()) return
        for (opPin in standaloneOperators) {
            val pt = engine.projectScreen(opPin.lat, opPin.lon) ?: continue
            if (pt.x < -marginPx || pt.x > width + marginPx || pt.y < -marginPx || pt.y > height + marginPx) {
                continue
            }
            if (selectedEntityUuid != null && selectedEntityUuid.equals(opPin.entityUuid, ignoreCase = true)) {
                selectionRingBitmap?.let { ring ->
                    canvas.drawBitmap(ring, pt.x - ring.width * 0.5f, pt.y - ring.height * 0.5f, bitmapPaint)
                }
            }
            drawPilotPin(canvas, pt.x, pt.y)
            renderedOperators += RenderedOperatorHit(opPin, pt.x, pt.y)
        }
    }

    private fun drawAirspaceDomes3d(
        canvas: Canvas,
        engine: MapEngine,
        cam: MapEngine.Camera,
        mpp: Double,
        marginPx: Float
    ) {
        fun projectDomeVertex(lat: Double, lon: Double, heightM: Double): PointF? {
            val g = engine.projectScreen(lat, lon) ?: return null
            val p = projectElevatedPoint3d(
                groundX = g.x,
                groundY = g.y,
                viewportWidthPx = width,
                viewportHeightPx = height,
                topPaddingPx = topPaddingPx,
                cameraTiltDeg = cam.tilt,
                metersPerPixelAtTarget = mpp,
                heightAboveGroundM = heightM,
                minPitchDeg = DOME_PITCH_THRESHOLD_DEG,
                blendSpanDeg = DOME_BLEND_SPAN_DEG
            )
            return PointF(p.airX, p.airY)
        }

        fun sampleHorizontalRing(cLat: Double, cLon: Double, sliceRadiusM: Double, heightM: Double, segments: Int): List<PointF> {
            if (sliceRadiusM <= 0.5) {
                return listOfNotNull(projectDomeVertex(cLat, cLon, heightM))
            }
            val pts = ArrayList<PointF>(segments)
            val stepDeg = 360.0 / segments
            for (i in 0 until segments) {
                val (pLat, pLon) = MapGeo.offset(cLat, cLon, sliceRadiusM, i * stepDeg)
                val sp = projectDomeVertex(pLat, pLon, heightM) ?: return emptyList()
                pts += sp
            }
            return pts
        }

        data class PreparedDome(
            val dome: DroneAirspaceDome,
            val centerGroundY: Float,
            val baseRing: List<PointF>,
            val midRing: List<PointF>,
            val capRing: List<PointF>,
            val nsArch: List<PointF>,
            val ewArch: List<PointF>,
            val hull: List<PointF>,
            val topCenter: PointF,
            val isCappedAtCeiling: Boolean,
            val opScreen: PointF?
        )

        val preparedDomes = ArrayList<PreparedDome>(domes.size)
        for (d in domes) {
            val gCenter = engine.projectScreen(d.lat, d.lon) ?: continue
            val rPxApprox = (d.radiusM / mpp).toFloat()
            if (gCenter.x + rPxApprox < -marginPx || gCenter.x - rPxApprox > width + marginPx ||
                gCenter.y + rPxApprox < -marginPx || gCenter.y - rPxApprox > height + marginPx * 2f
            ) {
                continue
            }
            val radiusM = d.radiusM.coerceAtLeast(25.0)
            val maxCeilingM = d.maxCeilingM.coerceAtLeast(TerrainElevationSampler.MIN_DRONE_AGL_M)
            val hMax = TerrainElevationSampler.airspaceDomeCeilingHeightM(radiusM, maxCeilingM)
            val rMid = TerrainElevationSampler.airspaceDomeSliceRadiusM(radiusM, hMax * 0.54, maxCeilingM)
            val rCap = TerrainElevationSampler.airspaceDomeSliceRadiusM(radiusM, hMax, maxCeilingM)
            val isCapped = rCap > 1.5

            val baseRing = sampleHorizontalRing(d.lat, d.lon, radiusM, 0.0, RING_SEGMENTS)
            if (baseRing.size < 8) continue
            val midRing = sampleHorizontalRing(d.lat, d.lon, rMid, hMax * 0.54, RING_SEGMENTS)
            val capRing = if (isCapped) {
                sampleHorizontalRing(d.lat, d.lon, rCap, hMax, RING_SEGMENTS)
            } else {
                emptyList()
            }
            val shellLower = sampleHorizontalRing(
                d.lat, d.lon,
                TerrainElevationSampler.airspaceDomeSliceRadiusM(radiusM, hMax * 0.30, maxCeilingM),
                hMax * 0.30,
                16
            )
            val shellUpper = sampleHorizontalRing(
                d.lat, d.lon,
                TerrainElevationSampler.airspaceDomeSliceRadiusM(radiusM, hMax * 0.80, maxCeilingM),
                hMax * 0.80,
                16
            )
            val topCenter = projectDomeVertex(d.lat, d.lon, hMax) ?: continue

            fun buildMeridianArch(bearingA: Double, bearingB: Double): List<PointF> {
                val arch = ArrayList<PointF>(MERIDIAN_STEPS_PER_SIDE * 2 + 3)
                for (k in 0..MERIDIAN_STEPS_PER_SIDE) {
                    val hk = hMax * (k.toDouble() / MERIDIAN_STEPS_PER_SIDE)
                    val rk = TerrainElevationSampler.airspaceDomeSliceRadiusM(radiusM, hk, maxCeilingM)
                    val (la, lo) = if (rk > 0.25) MapGeo.offset(d.lat, d.lon, rk, bearingA) else (d.lat to d.lon)
                    projectDomeVertex(la, lo, hk)?.let { arch += it }
                }
                if (isCapped) {
                    arch += topCenter
                }
                for (k in MERIDIAN_STEPS_PER_SIDE downTo 0) {
                    val hk = hMax * (k.toDouble() / MERIDIAN_STEPS_PER_SIDE)
                    val rk = TerrainElevationSampler.airspaceDomeSliceRadiusM(radiusM, hk, maxCeilingM)
                    val (la, lo) = if (rk > 0.25) MapGeo.offset(d.lat, d.lon, rk, bearingB) else (d.lat to d.lon)
                    projectDomeVertex(la, lo, hk)?.let { arch += it }
                }
                return arch
            }

            val nsArch = buildMeridianArch(0.0, 180.0)
            val ewArch = buildMeridianArch(90.0, 270.0)

            val allSamples = ArrayList<PointF>(baseRing.size + midRing.size + capRing.size + shellLower.size + shellUpper.size + 1)
            allSamples.addAll(baseRing)
            allSamples.addAll(shellLower)
            allSamples.addAll(midRing)
            allSamples.addAll(shellUpper)
            allSamples.addAll(capRing)
            allSamples += topCenter
            val hull = computeConvexHull(allSamples)
            if (hull.size < 3) continue

            val opPt = if (!d.isAircraft && d.operatorLat != null && d.operatorLon != null) {
                engine.projectScreen(d.operatorLat, d.operatorLon)
            } else {
                null
            }

            preparedDomes += PreparedDome(
                dome = d,
                centerGroundY = gCenter.y,
                baseRing = baseRing,
                midRing = midRing,
                capRing = capRing,
                nsArch = nsArch,
                ewArch = ewArch,
                hull = hull,
                topCenter = topCenter,
                isCappedAtCeiling = isCapped,
                opScreen = opPt
            )
        }

        preparedDomes.sortBy { it.centerGroundY }
        val opR = 6.5f * density

        for (pd in preparedDomes) {
            val d = pd.dome
            val rgb = d.colorArgb and 0x00FFFFFF
            val solidColor = rgb or 0xFF000000.toInt()
            val opFactor = d.opacity.coerceIn(0.35f, 1.0f)
            val isSelected = selectedEntityUuid != null && selectedEntityUuid.equals(d.entityUuid, ignoreCase = true)

            // 1. Soft translucent 3D volumetric silhouette fill (`alpha ~ 0.14..0.19` so 3D buildings inside stay legible)
            val fillAlpha = (0.17f * opFactor * 255f).toInt().coerceIn(22, 50)
            domeSilhouettePaint.color = (fillAlpha shl 24) or rgb
            canvas.drawPath(buildClosedRingPath(pd.hull), domeSilhouettePaint)

            // 2. Subtle ceiling disk tint when capped at max airspace ceiling (`R > maxCeilingM`)
            if (pd.isCappedAtCeiling && pd.capRing.size >= 3) {
                val capFillAlpha = (0.08f * opFactor * 255f).toInt().coerceIn(12, 26)
                domeCapFillPaint.color = (capFillAlpha shl 24) or rgb
                canvas.drawPath(buildClosedRingPath(pd.capRing), domeCapFillPaint)
            }

            // 3. Base footprint ring (`h = 0`)
            val baseAlpha = (0.80f * opFactor * 255f).toInt().coerceIn(70, 225)
            domeBaseRingPaint.color = if (isSelected) 0xFFFDE68A.toInt() else ((baseAlpha shl 24) or rgb)
            canvas.drawPath(buildClosedRingPath(pd.baseRing), domeBaseRingPaint)

            // 4. Mid-latitude parallel ring
            if (pd.midRing.size >= 3) {
                val midAlpha = (0.40f * opFactor * 255f).toInt().coerceIn(36, 120)
                domeMidRingPaint.color = (midAlpha shl 24) or rgb
                canvas.drawPath(buildClosedRingPath(pd.midRing), domeMidRingPaint)
            }

            // 5. N-S and E-W spherical meridian arches
            val merAlpha = (0.52f * opFactor * 255f).toInt().coerceIn(48, 150)
            domeMeridianPaint.color = (merAlpha shl 24) or rgb
            for (arch in listOf(pd.nsArch, pd.ewArch)) {
                if (arch.size >= 3) {
                    workPath.reset()
                    workPath.moveTo(arch[0].x, arch[0].y)
                    for (i in 1 until arch.size) workPath.lineTo(arch[i].x, arch[i].y)
                    canvas.drawPath(workPath, domeMeridianPaint)
                }
            }

            // 6. Ceiling cap ring (`<= 400 ft AGL` for drones, `<= 8,000 ft AGL` or Mode-S AGL for aircraft)
            if (pd.isCappedAtCeiling && pd.capRing.size >= 3) {
                val capStrokeAlpha = (0.88f * opFactor * 255f).toInt().coerceIn(85, 240)
                domeCapRingPaint.color = if (isSelected) 0xFFFDE68A.toInt() else ((capStrokeAlpha shl 24) or rgb)
                canvas.drawPath(buildClosedRingPath(pd.capRing), domeCapRingPaint)
            }

            val bx = pd.topCenter.x
            val by = pd.topCenter.y

            // 7. Optional crisp ground pilot badge + dashed 3D tether (never a bubble around the operator!)
            pd.opScreen?.let { op ->
                canvas.drawLine(op.x, op.y, bx, by, tetherPaint)
                drawPilotPin(canvas, op.x, op.y)
            }

            // 8. Selection halo + drone/aircraft badge + label at dome apex / ceiling center
            if (isSelected) {
                selectionRingBitmap?.let { ring ->
                    canvas.drawBitmap(ring, bx - ring.width * 0.5f, by - ring.height * 0.5f, bitmapPaint)
                }
            }
            val bmp = d.badgeBitmap
            val prevAlpha = bitmapPaint.alpha
            bitmapPaint.alpha = (opFactor * 255f).toInt().coerceIn(90, 255)
            canvas.save()
            canvas.translate(bx, by)
            canvas.scale(0.92f, 0.92f)
            canvas.drawBitmap(bmp, -bmp.width * 0.5f, -bmp.height * 0.5f, bitmapPaint)
            canvas.restore()
            bitmapPaint.alpha = prevAlpha

            if (d.label.isNotBlank()) {
                val labelY = by + (bmp.height * 0.46f) + 13f * density
                textHaloPaint.color = if (d.isAircraft) solidColor else 0xEE090D16.toInt()
                val prevTextAlpha = textFillPaint.alpha
                textFillPaint.alpha = (opFactor * 255f).toInt().coerceIn(110, 255)
                canvas.drawText(d.label, bx, labelY, textHaloPaint)
                canvas.drawText(d.label, bx, labelY, textFillPaint)
                textFillPaint.alpha = prevTextAlpha
            }

            renderedDomes += RenderedDomeHit(d, pd.hull, bx, by, pd.opScreen)
        }
    }

    private fun drawPinpointedAirborneTargets3d(
        canvas: Canvas,
        engine: MapEngine,
        cam: MapEngine.Camera,
        mpp: Double,
        marginPx: Float
    ) {
        data class Prepared(
            val target: AirborneTarget,
            val proj: Projected3dPoint,
            val opScreen: PointF?
        )

        val prepared = ArrayList<Prepared>(targets.size)
        for (t in targets) {
            val gPt = engine.projectScreen(t.lat, t.lon) ?: continue
            if (gPt.x < -marginPx || gPt.x > width + marginPx || gPt.y < -marginPx || gPt.y > height + marginPx * 2f) {
                continue
            }
            val groundElevM = TerrainElevationSampler.sampleElevationM(
                context = context,
                lat = t.lat,
                lon = t.lon
            )
            val hAglM = TerrainElevationSampler.computeAirborneHeightAboveGroundM(
                isAircraft = t.isAircraft,
                altitudeFtRaw = t.altitudeFtRaw,
                groundElevM = groundElevM
            )
            val proj = projectElevatedPoint3d(
                groundX = gPt.x,
                groundY = gPt.y,
                viewportWidthPx = width,
                viewportHeightPx = height,
                topPaddingPx = topPaddingPx,
                cameraTiltDeg = cam.tilt,
                metersPerPixelAtTarget = mpp,
                heightAboveGroundM = hAglM
            )
            val opPt = if (t.operatorLat != null && t.operatorLon != null) {
                engine.projectScreen(t.operatorLat, t.operatorLon)
            } else {
                null
            }
            prepared += Prepared(t, proj, opPt)
        }

        // Back-to-front painter's sort along screen Y
        prepared.sortBy { it.proj.groundY }

        val rx = 9.5f * density
        val ry = 5.2f * density
        val nadirR = 3.3f * density
        val opR = 6.5f * density

        for (item in prepared) {
            val t = item.target
            val p = item.proj
            val solidColor = (t.colorArgb and 0x00FFFFFF) or 0xFF000000.toInt()

            // 1. Ground shadow ellipse + nadir dot
            shadowRect.set(p.groundX - rx, p.groundY - ry, p.groundX + rx, p.groundY + ry)
            canvas.drawOval(shadowRect, shadowFillPaint)
            nadirDotPaint.color = solidColor
            canvas.drawCircle(p.groundX, p.groundY, nadirR, nadirDotPaint)

            // 2. Optional Remote ID pilot ground badge + 3D tether up to airborne drone
            item.opScreen?.let { op ->
                canvas.drawLine(op.x, op.y, p.airX, p.airY, tetherPaint)
                drawPilotPin(canvas, op.x, op.y)
            }

            // 3. Vertical 3D nadir plumb line from ground shadow to airborne puck
            if (p.liftPx > 3f * density) {
                canvas.drawLine(p.groundX, p.groundY, p.airX, p.airY, plumbCasingPaint)
                plumbLinePaint.color = solidColor
                canvas.drawLine(p.groundX, p.groundY, p.airX, p.airY, plumbLinePaint)
            }

            // 4. Selection halo ring around elevated 3D puck when selected
            if (selectedEntityUuid != null && selectedEntityUuid.equals(t.entityUuid, ignoreCase = true)) {
                selectionRingBitmap?.let { ring ->
                    canvas.drawBitmap(ring, p.airX - ring.width * 0.5f, p.airY - ring.height * 0.5f, bitmapPaint)
                }
            }

            // 5. Elevated 3D airborne badge puck
            val bmp = t.badgeBitmap
            canvas.save()
            canvas.translate(p.airX, p.airY)
            canvas.scale(p.perspectiveScale, p.perspectiveScale)
            if (t.rotateWithMap) {
                val screenAngle = ((t.trackDeg - cam.bearing) % 360.0).toFloat()
                canvas.rotate(screenAngle)
            }
            canvas.drawBitmap(bmp, -bmp.width * 0.5f, -bmp.height * 0.5f, bitmapPaint)
            canvas.restore()

            // 6. Crisp altitude/agency label beneath the elevated puck
            if (t.label.isNotBlank()) {
                val labelY = p.airY + (bmp.height * 0.5f * p.perspectiveScale) + 13.5f * density
                textHaloPaint.color = if (t.isAircraft) solidColor else 0xF0090D16.toInt()
                canvas.drawText(t.label, p.airX, labelY, textHaloPaint)
                canvas.drawText(t.label, p.airX, labelY, textFillPaint)
            }

            renderedHits += RenderedHit(t, p.airX, p.airY, p.groundX, p.groundY, item.opScreen)
        }
    }
}

internal typealias AirspaceDome = Airborne3dOverlayView.DroneAirspaceDome
