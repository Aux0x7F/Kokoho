package com.rfsentinel.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.kokoho.EvaluatedThreat
import com.rfsentinel.app.kokoho.EvidenceBadge
import com.rfsentinel.app.kokoho.KokohoAgency
import com.rfsentinel.app.kokoho.KokohoPostureEngine
import com.rfsentinel.app.kokoho.KokohoSignatures
import com.rfsentinel.app.kokoho.PostureColor
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kokoho Entity Details Activity — opened by tapping an item in List or tapping the confidence pill
 * at the bottom of Monitor:
 *  1. Entity classification row with a right-aligned pencil icon that edits in-place (turning into a
 *     checkmark symbol while editing; saved locally only).
 *  2. Full-width 1/4-screen Map of the current supposed radius position.
 *  3. Center radius coordinates (clicking copies coords to clipboard) and accuracy radius.
 *  4. Section for details of all evidence.
 *  5. Top-right '...' overflow menu (replacing the chart icon and action pills) with items limited to:
 *     Whitelist (local only), Watch (equivalent to Favorite), and Share.
 */
class DeviceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAC = "mac"
        private val RADIO_KEY = Regex("""^RADIO (\d+(?:\.\d+)?) MHz$""")

        private const val MENU_WHITELIST = 101
        private const val MENU_WATCH = 102
        private const val MENU_SHARE = 103

        /** The frequency in a radio transmission's log key ("RADIO 154.4300 MHz"), else null. */
        fun radioHz(key: String): Long? =
            RADIO_KEY.find(key)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it * 1_000_000) }
    }

    private lateinit var rawKey: String
    private lateinit var entityUuid: String

    private lateinit var rootLayout: LinearLayout
    lateinit var classificationText: TextView
        private set
    lateinit var classificationEdit: EditText
        private set
    lateinit var editCheckButton: ImageButton
        private set
    lateinit var mapContainer: FrameLayout
        private set
    lateinit var coordsText: TextView
        private set
    lateinit var accuracyRadiusText: TextView
        private set
    lateinit var evidenceContainer: LinearLayout
        private set

    var isEditingClassification: Boolean = false
        private set

    private var mapEngine: MapEngine? = null
    private var rfMap: RfMap? = null

    private var currentClassification: String = "CBP"
    private var currentLat: Double = 32.2226
    private var currentLon: Double = -110.9747
    private var currentRadiusM: Int = 450
    private var currentIsPinpointed: Boolean = false
    private var currentPostureColor: Int = PostureColor.YELLOW.colorArgb
    private var currentEvidence: List<EvidenceBadge> = emptyList()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KokohoSignatures.ensureLoaded(this)

        rawKey = intent.getStringExtra(EXTRA_MAC) ?: run {
            finish()
            return
        }
        entityUuid = KokohoSignatures.normalizeEntityId(rawKey)

        title = "Details"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val statusBgColor = KokohoPostureEngine.currentPostureColorArgb(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(statusBgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.applySystemBarInsets()

        // 1. Entity Classification Row (Left: Classification label / inline EditText; Right: Pencil <-> Checkmark icon)
        val classificationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(12))
        }

        classificationText = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        classificationEdit = EditText(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setHintTextColor(0xAAFFFFFF.toInt())
            hint = "Entity classification"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x44000000)
                setStroke(dp(1), Color.WHITE)
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitClassificationEdit()
                    true
                } else {
                    false
                }
            }
        }

        editCheckButton = ImageButton(this).apply {
            contentDescription = "Edit classification"
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0x38000000)
                setStroke(dp(1), 0x66FFFFFF)
            }
            setImageDrawable(createPencilDrawable())
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener {
                if (isEditingClassification) {
                    commitClassificationEdit()
                } else {
                    beginClassificationEdit()
                }
            }
        }

        classificationRow.addView(classificationText)
        classificationRow.addView(classificationEdit)
        classificationRow.addView(editCheckButton)
        rootLayout.addView(classificationRow)

        // 2. Full-Width 1/4-Screen Map of Current Supposed Radius Position
        val quarterScreenPx = (resources.displayMetrics.heightPixels / 4).coerceAtLeast(dp(180))
        mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                quarterScreenPx
            )
            setBackgroundColor(0xFF101820.toInt())
        }
        rootLayout.addView(mapContainer)

        runCatching {
            val engine = MapEngine.create(this)
            mapContainer.addView(
                engine.view,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            engine.onCreate(savedInstanceState)
            mapEngine = engine
            engine.whenReady {
                if (isFinishing || isDestroyed || mapEngine !== engine) return@whenReady
                engine.setMinZoom(2.0)
                val map = RfMap(this, engine).also { rfMap = it }
                engine.jumpTo(currentLat, currentLon, zoomForRadius(currentRadiusM))
                map.loadStyle(dark = true) {
                    if (!isFinishing && !isDestroyed) {
                        renderMapPosition()
                    }
                }
            }
        }

        // 3. Scrollable lower pane: Center Radius Coordinates (click to copy) + Accuracy Radius + Evidence Details
        val lowerScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val lowerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val coordsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x38000000)
                setStroke(dp(1), 0x55FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
            setOnClickListener {
                copyCoordsToClipboard()
            }
        }

        coordsText = TextView(this).apply {
            textSize = 15.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setOnClickListener {
                copyCoordsToClipboard()
            }
        }

        accuracyRadiusText = TextView(this).apply {
            visibility = View.GONE
        }

        coordsCard.addView(coordsText)
        coordsCard.addView(accuracyRadiusText)
        lowerContent.addView(coordsCard)

        // 4. Section for Details of All Evidence
        val evidenceHeader = TextView(this).apply {
            text = "Evidence Details"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        lowerContent.addView(evidenceHeader)

        evidenceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        lowerContent.addView(evidenceContainer)

        lowerScroll.addView(lowerContent)
        rootLayout.addView(lowerScroll)

        setContentView(rootLayout)
        refreshDetails()
    }

    private fun beginClassificationEdit() {
        isEditingClassification = true
        classificationEdit.setText(currentClassification)
        classificationEdit.setSelection(classificationEdit.text.length)
        classificationText.visibility = View.GONE
        classificationEdit.visibility = View.VISIBLE
        editCheckButton.setImageDrawable(createCheckmarkDrawable())
        editCheckButton.contentDescription = "Save classification"
        classificationEdit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(classificationEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    fun commitClassificationEdit() {
        val edited = classificationEdit.text?.toString()?.trim().orEmpty()
        if (edited.isNotEmpty()) {
            KokohoSignatures.setCustomEntityLabel(this, entityUuid, edited)
            CoroutineScope(Dispatchers.Main).launch {
                runCatching { DeviceNames.set(this@DeviceDetailActivity, rawKey, edited, null, null, null) }
            }
            currentClassification = edited
        }
        isEditingClassification = false
        classificationEdit.visibility = View.GONE
        classificationText.text = currentClassification
        classificationText.visibility = View.VISIBLE
        editCheckButton.setImageDrawable(createPencilDrawable())
        editCheckButton.contentDescription = "Edit classification"
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(classificationEdit.windowToken, 0)
    }

    private fun copyCoordsToClipboard() {
        val formatted = String.format(Locale.US, "%.5f, %.5f", currentLat, currentLon)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Center coordinates", formatted))
        Toast.makeText(this, "Coordinates copied: $formatted", Toast.LENGTH_SHORT).show()
    }

    private fun refreshDetails() {
        val statusColor = KokohoPostureEngine.currentPostureColorArgb(this)
        rootLayout.setBackgroundColor(statusColor)

        val threat: EvaluatedThreat? = KokohoPostureEngine.findThreatByUuid(this, entityUuid)
        val userFix = ScanForegroundService.lastFix

        if (threat != null) {
            currentClassification = KokohoSignatures.resolveEntityLabel(this, entityUuid, threat.friendlyLabel)
            currentLat = threat.lat ?: userFix?.latitude ?: 32.2226
            currentLon = threat.lon ?: userFix?.longitude ?: -110.9747
            currentRadiusM = threat.mapCircleRadiusM
            currentIsPinpointed = threat.isPinpointed
            currentPostureColor = threat.posture.colorArgb
            currentEvidence = threat.evidenceSources
        } else {
            val snap = DeviceRegistry.get(rawKey)
            val fallbackLabel = snap?.best?.label ?: snap?.name ?: "CBP"
            currentClassification = KokohoSignatures.resolveEntityLabel(
                this,
                entityUuid,
                DeviceNames.of(rawKey) ?: fallbackLabel
            )
            val place = snap?.place
            currentLat = place?.lat ?: userFix?.latitude ?: 32.2226
            currentLon = place?.lon ?: userFix?.longitude ?: -110.9747
            currentRadiusM = 400
            currentIsPinpointed = false
            currentPostureColor = statusColor
            currentEvidence = if (snap != null) {
                snap.hits.map { h ->
                    EvidenceBadge(
                        kind = h.category.shortTag,
                        facetKey = snap.mac,
                        detail = "${h.label} · ${h.evidence} (${snap.rssi} dBm)",
                        weight = h.confidence / 100.0,
                        lastSeenMs = snap.lastSeen
                    )
                }
            } else {
                emptyList()
            }
        }

        if (!isEditingClassification) {
            classificationText.text = currentClassification
        }

        val coordStr = String.format(Locale.US, "%.5f, %.5f", currentLat, currentLon)
        val radiusStr = KokohoPostureEngine.formatAccuracyRadius(this, currentRadiusM)
        coordsText.text = "$coordStr ($radiusStr)"
        accuracyRadiusText.text = radiusStr

        renderEvidenceList()
        renderMapPosition()
    }

    private fun renderEvidenceList() {
        evidenceContainer.removeAllViews()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        if (currentEvidence.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No raw evidence frames retained."
                textSize = 14f
                setTextColor(0xDDFFFFFF.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x28000000)
                    setStroke(dp(1), 0x44FFFFFF)
                }
            }
            evidenceContainer.addView(emptyTv)
            return
        }

        for (ev in currentEvidence) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x33000000)
                    setStroke(dp(1), 0x55FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val timeStr = if (ev.lastSeenMs > 0) fmt.format(Date(ev.lastSeenMs)) else "live"
            val weightPct = (ev.weight * 100).toInt().coerceIn(1, 99)

            val topLine = TextView(this).apply {
                text = "${ev.kind} · ${ev.facetKey} ($weightPct% weight · $timeStr)"
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setTextColor(0xDDFFFFFF.toInt())
            }

            val detailLine = TextView(this).apply {
                text = ev.detail
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, 0)
            }

            card.addView(topLine)
            card.addView(detailLine)
            evidenceContainer.addView(card)
        }
    }

    private fun zoomForRadius(radiusM: Int): Double = when {
        radiusM >= 5000 -> 9.8
        radiusM >= 2000 -> 11.0
        radiusM >= 800 -> 12.0
        radiusM >= 300 -> 13.2
        else -> 14.2
    }

    private fun renderMapPosition() {
        val map = rfMap ?: return
        val pin = MapPin(
            mac = "kokoho:$entityUuid",
            lat = currentLat,
            lon = currentLon,
            label = currentClassification,
            flagged = true,
            color = currentPostureColor,
            details = "Accuracy radius: ${KokohoPostureEngine.formatShortDistance(this, currentRadiusM)}",
            radiusM = if (currentIsPinpointed) null else currentRadiusM.toDouble().coerceAtLeast(35.0),
            showDot = currentIsPinpointed
        )
        map.drawPins(listOf(pin))
        map.map.jumpTo(currentLat, currentLon, zoomForRadius(currentRadiusM))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Only '...' overflow items: Whitelist (local only), Watch (Favorite), and Share. No chart icon.
        menu.clear()
        val whitelisted = KokohoSignatures.isEntityWhitelisted(this, entityUuid) || WhitelistCache.contains(rawKey)
        val watched = KokohoSignatures.isEntityWatched(this, entityUuid) || Favorites.contains(rawKey)

        menu.add(0, MENU_WHITELIST, 0, if (whitelisted) "Remove from Whitelist" else "Whitelist")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_WATCH, 1, if (watched) "Unwatch" else "Watch")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SHARE, 2, "Share")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val whitelisted = KokohoSignatures.isEntityWhitelisted(this, entityUuid) || WhitelistCache.contains(rawKey)
        val watched = KokohoSignatures.isEntityWatched(this, entityUuid) || Favorites.contains(rawKey)
        menu.findItem(MENU_WHITELIST)?.title = if (whitelisted) "Remove from Whitelist" else "Whitelist"
        menu.findItem(MENU_WATCH)?.title = if (watched) "Unwatch" else "Watch"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_WHITELIST -> {
                val nowWhitelisted = !KokohoSignatures.isEntityWhitelisted(this, entityUuid)
                KokohoSignatures.setEntityWhitelisted(this, entityUuid, nowWhitelisted)
                Toast.makeText(
                    this,
                    if (nowWhitelisted) "Whitelisted locally" else "Removed from local whitelist",
                    Toast.LENGTH_SHORT
                ).show()
                invalidateOptionsMenu()
                refreshDetails()
                true
            }
            MENU_WATCH -> {
                val nowWatched = !KokohoSignatures.isEntityWatched(this, entityUuid)
                KokohoSignatures.setEntityWatched(this, entityUuid, nowWatched)
                CoroutineScope(Dispatchers.Main).launch {
                    runCatching { Favorites.set(this@DeviceDetailActivity, rawKey, nowWatched, currentClassification, null, null) }
                }
                Toast.makeText(
                    this,
                    if (nowWatched) "Watching $currentClassification" else "Stopped watching $currentClassification",
                    Toast.LENGTH_SHORT
                ).show()
                invalidateOptionsMenu()
                true
            }
            MENU_SHARE -> {
                val coordStr = String.format(Locale.US, "%.5f, %.5f", currentLat, currentLon)
                val radiusStr = KokohoPostureEngine.formatAccuracyRadius(this, currentRadiusM)
                val evLines = currentEvidence.joinToString("\n") { "- [${it.kind}] ${it.detail}" }
                val shareBody = buildString {
                    appendLine("Kokoho Detection: $currentClassification")
                    appendLine("$coordStr ($radiusStr)")
                    if (evLines.isNotBlank()) {
                        appendLine("Evidence:")
                        append(evLines)
                    }
                }
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareBody)
                        },
                        "Share detection"
                    )
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        mapEngine?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapEngine?.onResume()
        refreshDetails()
    }

    override fun onPause() {
        mapEngine?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapEngine?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapEngine?.onDestroy()
        super.onDestroy()
    }

    private fun createPencilDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.1f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val path = Path().apply {
                // Classic angled edit pencil icon (bottom-left tip to top-right eraser)
                moveTo(w * 0.26f, h * 0.74f)
                lineTo(w * 0.30f, h * 0.58f)
                lineTo(w * 0.64f, h * 0.24f)
                lineTo(w * 0.76f, h * 0.36f)
                lineTo(w * 0.42f, h * 0.70f)
                close()
                // Ferrule collar line near eraser
                moveTo(w * 0.57f, h * 0.31f)
                lineTo(w * 0.69f, h * 0.43f)
            }
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun createCheckmarkDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            canvas.drawLine(w * 0.26f, h * 0.52f, w * 0.44f, h * 0.70f, paint)
            canvas.drawLine(w * 0.44f, h * 0.70f, w * 0.75f, h * 0.32f, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
