package org.tucsoncommunity.kokoho.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.tucsoncommunity.kokoho.sdr.SdrWaterfall
import java.util.Locale
import kotlin.math.abs

/**
 * The radio waterfall: the latest spectrum as a line on top, and below it every row so far, newest at
 * the top, from dark (noise) to bright (a strong signal). Per-segment noise normalization keeps VHF and
 * 700/800 MHz balanced. Tap or pinch to zoom, drag to pan; a tap at full zoom (or while tuned) picks
 * that frequency ([onTap]).
 */
class WaterfallView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** A frequency tapped on the waterfall or the spectrum. */
    var onTap: ((Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = KokohoJourneyUi.HIGHLIGHT_AMBER
        textSize = 13.5f * density
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60B111E.toInt(); style = Paint.Style.FILL }
    private val spectrumBgPaint = Paint().apply { color = 0xFF090E17.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.0f * density; style = Paint.Style.STROKE }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KokohoJourneyUi.HIGHLIGHT_AMBER; alpha = 190; strokeWidth = 1.4f * density; style = Paint.Style.STROKE }
    private val gapPaint = Paint().apply { color = 0xAAFFFFFF.toInt(); strokeWidth = 1.2f * density }
    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val blackPaint = Paint().apply { color = Color.BLACK }

    private var layoutKey = ""
    private var layoutW = 0; private var layoutH = 0
    private var colSeg = IntArray(0); private var colFrom = IntArray(0); private var colTo = IntArray(0)
    private var colHz = LongArray(0)
    private var gaps = IntArray(0)
    private var tuned = false
    var focusHz: Long? = null

    private var bitmap: Bitmap? = null
    private var newest = 0
    private var rows = 0
    private var pixels = IntArray(0)
    private var spectrum: FloatArray? = null
    private var peak: FloatArray? = null
    private var floor = Float.NaN

    private var zoom = 1f
    private var panX = 0f
    private fun colAt(sx: Float) = (panX + sx / zoom).toInt().coerceIn(0, (colHz.size - 1).coerceAtLeast(0))
    private fun screenX(col: Float) = (col - panX) * zoom
    private fun clampPan() { panX = panX.coerceIn(0f, (width - width / zoom).coerceAtLeast(0f)) }
    private var focusX = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { focusX = d.focusX; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val at = panX + focusX / zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
            panX = at - d.focusX / zoom
            focusX = d.focusX
            clampPan(); invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            "zoom in on the strongest signal") { _, _ -> strongest()?.let { onTap?.invoke(it); true } ?: false }
    }

    private fun strongest(): Long? {
        val s = spectrum ?: return null
        if (colHz.size != s.size) return null
        val x = (colAt(0f)..colAt(width - 1f)).maxByOrNull { s[it] } ?: return null
        return if (s[x] > SdrWaterfall.NO_DATA) colHz[x] else null
    }

    private val spectrumH get() = (height * 0.28f).coerceAtLeast(64 * density)
    private val labelH get() = textPaint.textSize + 6 * density
    private val waterTop get() = spectrumH + labelH

    fun clear() {
        layoutKey = ""; rows = 0; spectrum = null; peak = null; floor = Float.NaN; zoom = 1f; panX = 0f; lastSweep = -1L
        bitmap?.eraseColor(Color.BLACK)
        savedSweep?.bmp?.recycle(); savedSweep = null
        invalidate()
    }

    fun add(row: SdrWaterfall.Row) {
        val w = width; if (w <= 0 || height <= waterTop + 8 * density) return
        val key = row.tuned.toString() + row.segments.joinToString { "${it.startHz}-${it.endHz}" }
        if (key != layoutKey || w != layoutW || height != layoutH) relayout(row, w, key)
        val dbs = row.segments.map { s ->
            if (row.tuned) s.db else {
                val heard = s.db.filter { it > SdrWaterfall.NO_DATA }.sorted()
                val noise = if (heard.isEmpty()) 0f else heard[heard.size / 2]
                FloatArray(s.db.size) { if (s.db[it] <= SdrWaterfall.NO_DATA) SdrWaterfall.NO_DATA else s.db[it] - noise }
            }
        }
        val values = FloatArray(w) { x ->
            val d = dbs[colSeg[x]]
            var m = -200f
            for (k in colFrom[x]..colTo[x]) if (d[k] > m) m = d[k]
            m
        }
        val again = row.sweep >= 0 && row.sweep == lastSweep && rows > 0
        lastSweep = row.sweep
        val heard = values.filter { it > SdrWaterfall.NO_DATA }.sorted()
        if (heard.isNotEmpty() && (!again || floor.isNaN())) {
            val median = heard[heard.size / 2]
            floor = if (floor.isNaN()) median else floor + (median - floor) * (if (row.tuned) 0.05f else 0.3f)
        }
        spectrum = values
        val decay = if (again) 0f else if (row.tuned) 0.15f else 1.5f
        peak = peak?.let { p -> FloatArray(w) { maxOf(values[it], p[it] - decay) } } ?: values.copyOf()
        val bmp = bitmap ?: return
        if (floor.isNaN()) return
        for (x in 0 until w) pixels[x] = if (values[x] <= SdrWaterfall.NO_DATA) PALETTE[0] else PALETTE[(((values[x] - floor + 3f) / RANGE_DB) * 255).toInt().coerceIn(0, 255)]
        if (!again) newest = (newest - 1 + bmp.height) % bmp.height
        bmp.setPixels(pixels, 0, w, 0, newest, w, 1)
        if (!again) rows = minOf(rows + 1, bmp.height)
    }

    private var lastSweep = -1L

    private class Picture(val bmp: Bitmap, val colHz: LongArray, val rows: Int, val newest: Int, val rowPx: Float, val key: String)
    private var savedSweep: Picture? = null
    private fun rowPxFor(tuned: Boolean) = if (tuned) 1f else 4 * density

    private fun relayout(row: SdrWaterfall.Row, w: Int, key: String) {
        val sizeOnly = key == layoutKey
        val modeChanged = layoutKey.isEmpty() || row.tuned != tuned
        val current = bitmap?.takeIf { rows > 0 && !it.isRecycled }?.let { Picture(it, colHz, rows, newest, rowPxFor(tuned), layoutKey) }
        var src = current
        var keepOld = false
        if (row.tuned && !tuned && current != null) { savedSweep?.bmp?.recycle(); savedSweep = current; keepOld = true }
        if (!row.tuned && tuned) savedSweep?.let { sv -> savedSweep = null; if (sv.key == key && !sv.bmp.isRecycled) src = sv else sv.bmp.recycle() }
        layoutKey = key; layoutW = w; layoutH = height; tuned = row.tuned
        val segs = row.segments
        val total = segs.sumOf { it.endHz - it.startHz }.coerceAtLeast(1)
        colSeg = IntArray(w); colFrom = IntArray(w); colTo = IntArray(w); colHz = LongArray(w)
        val gapList = ArrayList<Int>()
        var x0 = 0; var acc = 0L
        segs.forEachIndexed { i, s ->
            acc += s.endHz - s.startHz
            val x1 = if (i == segs.lastIndex) w else (acc * w / total).toInt()
            if (i > 0 && s.startHz > segs[i - 1].endHz + (s.endHz - s.startHz) / 20) gapList += x0
            val bins = s.db.size
            for (x in x0 until x1) {
                val f0 = (x - x0).toDouble() / (x1 - x0); val f1 = (x + 1 - x0).toDouble() / (x1 - x0)
                colSeg[x] = i
                colFrom[x] = (f0 * bins).toInt().coerceIn(0, bins - 1)
                colTo[x] = ((f1 * bins).toInt() - 1).coerceIn(colFrom[x], bins - 1)
                colHz[x] = s.startHz + ((s.endHz - s.startHz) * (f0 + f1) / 2).toLong()
            }
            x0 = x1
        }
        gaps = gapList.toIntArray()
        val rowPx = rowPxFor(row.tuned)
        val h = ((height - waterTop) / rowPx).toInt().coerceAtLeast(1)
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        pixels = IntArray(w); newest = 0; spectrum = null; peak = null
        rows = src?.let { carry(it, fresh, rowPx) } ?: 0
        if (!sizeOnly) {
            floor = Float.NaN; lastSweep = -1L
            if (row.tuned) {
                if (modeChanged) zoom = 1f
                val f = focusHz?.takeIf { segs.size == 1 && it in segs[0].startHz..segs[0].endHz }
                panX = if (f == null) (w - w / zoom) / 2
                else ((f - segs[0].startHz).toFloat() / (segs[0].endHz - segs[0].startHz).coerceAtLeast(1) * w) - w / zoom / 2
            } else { zoom = 1f; panX = 0f }
        }
        clampPan()
        if (src !== current && src != null) src!!.bmp.recycle()
        if (!keepOld) bitmap?.recycle()
        bitmap = fresh
    }

    private fun carry(src: Picture, fresh: Bitmap, rowPx: Float): Int {
        val w = fresh.width; val ow = src.bmp.width
        if (src.colHz.size != ow || ow == 0) return 0
        val map = IntArray(w) { x ->
            val f = colHz[x]
            var k = java.util.Arrays.binarySearch(src.colHz, f).let { if (it >= 0) it else (-it - 1) }
            if (k == ow || (k > 0 && f - src.colHz[k - 1] < src.colHz[k] - f)) k -= 1
            k = k.coerceIn(0, ow - 1)
            val step = minOf(if (k > 0) src.colHz[k] - src.colHz[k - 1] else Long.MAX_VALUE,
                if (k < ow - 1) src.colHz[k + 1] - src.colHz[k] else Long.MAX_VALUE).let { if (it == Long.MAX_VALUE) 0L else it }
            if (abs(f - src.colHz[k]) <= step) k else -1
        }
        if (map.all { it < 0 }) return 0
        val keep = minOf(fresh.height, (src.rows * src.rowPx / rowPx).toInt())
        val from = IntArray(ow); val to = IntArray(w)
        for (j in 0 until keep) {
            val r = (j * rowPx / src.rowPx).toInt().coerceAtMost(src.rows - 1)
            src.bmp.getPixels(from, 0, ow, 0, (src.newest + r) % src.bmp.height, ow, 1)
            for (x in 0 until w) to[x] = if (map[x] < 0) Color.BLACK else from[map[x]]
            fresh.setPixels(to, 0, w, 0, j, w, 1)
        }
        return keep
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle(); bitmap = null; layoutKey = ""
        savedSweep?.bmp?.recycle(); savedSweep = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = waterTop
        canvas.drawRect(0f, 0f, w, spectrumH, spectrumBgPaint)
        canvas.drawRect(0f, spectrumH, w, top, labelBgPaint)
        canvas.drawRect(0f, top, w, height.toFloat(), blackPaint)
        val bmp = bitmap
        val m = Matrix().apply { setScale(zoom, 1f); postTranslate(-panX * zoom, 0f) }
        if (bmp != null && rows > 0) {
            canvas.save(); canvas.concat(m)
            val scale = (height - top) / bmp.height
            val firstRows = minOf(rows, bmp.height - newest)
            canvas.drawBitmap(bmp, Rect(0, newest, bmp.width, newest + firstRows),
                android.graphics.RectF(0f, top, w, top + firstRows * scale), bitmapPaint)
            val rest = rows - firstRows
            if (rest > 0) canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, rest),
                android.graphics.RectF(0f, top + firstRows * scale, w, top + rows * scale), bitmapPaint)
            canvas.restore()
        }
        for (g in gaps) screenX(g.toFloat()).let { canvas.drawLine(it, 0f, it, height.toFloat(), gapPaint) }
        val s = spectrum
        if (s != null && !floor.isNaN()) {
            val h = spectrumH - 4 * density
            fun y(v: Float) = 2 * density + h * (1f - ((v - floor + 3f) / (RANGE_DB + 10f)).coerceIn(0f, 1f))
            fun line(a: FloatArray) = Path().apply {
                var pen = false
                a.forEachIndexed { x, v ->
                    if (v <= SdrWaterfall.NO_DATA || s[x] <= SdrWaterfall.NO_DATA) pen = false
                    else if (pen) lineTo(x.toFloat(), y(v)) else { moveTo(x.toFloat(), y(v)); pen = true }
                }
                transform(m)
            }
            peak?.let { canvas.drawPath(line(it), peakPaint) }
            canvas.drawPath(line(s), linePaint)
        }
        drawLabels(canvas)
        if (zoom > 1.05f) {
            val t = String.format(Locale.US, "%.0f× · pinch out", zoom)
            canvas.drawText(t, width - textPaint.measureText(t) - 4 * density, textPaint.textSize + 2 * density, textPaint)
        }
    }

    private fun drawLabels(canvas: Canvas) {
        if (colHz.isEmpty()) return
        val y = spectrumH + textPaint.textSize
        val first = colAt(0f); val last = colAt(width - 1f)
        val marks = if (tuned) listOf(first, colAt(width / 2f), last) else (listOf(first) + gaps.filter { it in first + 1..last } + last).distinct()
        var lastEnd = Float.NEGATIVE_INFINITY
        for ((i, x) in marks.withIndex()) {
            val t = mhz(colHz[x])
            val tw = textPaint.measureText(t)
            val left = when (i) { 0 -> 0f; marks.lastIndex -> width - tw; else -> (screenX(x.toFloat()) - tw / 2).coerceIn(0f, width - tw) }
            if (left < lastEnd + 8 * density) continue
            canvas.drawText(t, left, y, textPaint)
            lastEnd = left + tw
        }
    }

    private fun mhz(hz: Long) = String.format(Locale.US, if (tuned || zoom > 2f) "%.3f" else "%.1f", hz / 1e6)

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; moved = false }
            MotionEvent.ACTION_POINTER_DOWN -> { moved = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_POINTER_UP -> lastX = e.getX(if (e.actionIndex == 0) 1 else 0)
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                if (abs(e.x - downX) >= 16 * density || abs(e.y - downY) >= 16 * density) moved = true
                if (moved && zoom > 1f) { panX -= (e.x - lastX) / zoom; clampPan(); invalidate() }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> if (!moved && colHz.isNotEmpty()) {
                if (!tuned && zoom < MAX_ZOOM) zoomAt(e.x) else onTap?.invoke(colHz[colAt(e.x)])
                performClick()
            }
        }
        return true
    }

    internal fun zoomAt(sx: Float) {
        val at = panX + sx / zoom
        zoom = (zoom * TAP_ZOOM).coerceAtMost(MAX_ZOOM)
        panX = at - width / zoom / 2
        clampPan(); invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val step = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -KEY_STEP_HZ
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_STEP_HZ
            else -> return super.onKeyDown(keyCode, event)
        }
        if (!tuned || colHz.isEmpty()) return super.onKeyDown(keyCode, event)
        onTap?.invoke(colHz[colHz.size / 2] + step)
        return true
    }

    companion object {
        private const val RANGE_DB = 40f
        private const val KEY_STEP_HZ = 100_000L
        private const val MAX_ZOOM = 8f
        private const val TAP_ZOOM = 2f

        private val PALETTE = IntArray(256) { i ->
            val stops = intArrayOf(0xFF000000.toInt(), 0xFF0A1A6E.toInt(), 0xFF0096C8.toInt(), 0xFFF0E000.toInt(), 0xFFE02000.toInt(), 0xFFFFFFFF.toInt())
            val p = i / 255f * (stops.size - 1)
            val k = p.toInt().coerceAtMost(stops.size - 2); val f = p - k
            val a = stops[k]; val b = stops[k + 1]
            fun mix(sh: Int) = (((a shr sh) and 0xff) * (1 - f) + ((b shr sh) and 0xff) * f).toInt()
            Color.rgb(mix(16), mix(8), mix(0))
        }
    }
}
