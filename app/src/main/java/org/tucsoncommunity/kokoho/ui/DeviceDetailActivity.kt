package org.tucsoncommunity.kokoho.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tucsoncommunity.kokoho.data.DeviceNames
import org.tucsoncommunity.kokoho.data.Favorites
import org.tucsoncommunity.kokoho.data.WhitelistCache
import org.tucsoncommunity.kokoho.posture.EvaluatedThreat
import org.tucsoncommunity.kokoho.posture.EvidenceBadge
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.posture.KokohoViews
import org.tucsoncommunity.kokoho.posture.PostureColor
import org.tucsoncommunity.kokoho.posture.WitnessReportRecord
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.util.applySystemBarInsets
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kokoho Entity Details Activity — opened by tapping an agent in the Distinct Agents List or Map:
 *  1. Agent photo / Gravatar identicon + classification row with inline edit pencil/checkmark.
 *  2. Full-width 1/4-screen Map of the last-seen radius position (or aircraft/drone position).
 *  3. Center radius coordinates (click to copy), accuracy radius, staleness time, and altitude (if airborne).
 *  4. Verified Witness Report card + "Witness Report" button to submit/update verified agency,
 *     department, badge number, agent name, vehicle number, notes, and optional photo upload (#23, #25).
 *  5. Evidence Details list.
 */
class DeviceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAC = "mac"
        private val RADIO_KEY = Regex("""^RADIO (\d+(?:\.\d+)?) MHz$""")

        private const val MENU_WHITELIST = 101
        private const val MENU_WATCH = 102
        private const val MENU_SHARE = 103
        private const val MENU_WITNESS = 104

        /** The frequency in a radio transmission's log key ("RADIO 154.4300 MHz"), else null. */
        fun radioHz(key: String): Long? =
            RADIO_KEY.find(key)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it * 1_000_000) }
    }

    private lateinit var rawKey: String
    private lateinit var entityUuid: String

    private lateinit var rootLayout: LinearLayout
    lateinit var avatarImageView: ImageView
        private set
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
    lateinit var stalenessAndMetaText: TextView
        private set
    lateinit var accuracyRadiusText: TextView
        private set
    lateinit var witnessSummaryCard: LinearLayout
        private set
    lateinit var witnessSummaryText: TextView
        private set
    lateinit var witnessReportButton: MaterialButton
        private set
    lateinit var evidenceContainer: LinearLayout
        private set

    var isEditingClassification: Boolean = false
        private set

    private var mapEngine: MapEngine? = null
    private var rfMap: RfMap? = null

    private var currentAgency: KokohoAgency = KokohoAgency.DHS_CBP
    private var currentClassification: String = "CBP"
    private var currentLat: Double = 32.2226
    private var currentLon: Double = -110.9747
    private var currentRadiusM: Int = 450
    private var currentIsPinpointed: Boolean = false
    private var currentPostureColor: Int = PostureColor.YELLOW.colorArgb
    private var currentUpdatedAtMs: Long = System.currentTimeMillis()
    private var currentAltitudeFt: Int? = null
    private var currentHardwareId: String? = null
    private var currentEvidence: List<EvidenceBadge> = emptyList()

    private var pendingPhotoCallback: ((String) -> Unit)? = null
    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            encodeUriToDataUrl(uri)?.let { dataUrl ->
                pendingPhotoCallback?.invoke(dataUrl)
            }
        }
    }

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

        title = "Agent / Unit Details"
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

        // 1. Entity Header Row (Avatar/Photo + Classification label / inline EditText + Pencil/Checkmark)
        val classificationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }

        avatarImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnClickListener { showWitnessReportDialog() }
        }

        classificationText = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        classificationEdit = EditText(this).apply {
            textSize = 22f
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

        classificationRow.addView(avatarImageView)
        classificationRow.addView(classificationText)
        classificationRow.addView(classificationEdit)
        classificationRow.addView(editCheckButton)
        rootLayout.addView(classificationRow)

        // 2. Full-Width 1/4-Screen Last-Seen Map
        val quarterScreenPx = (resources.displayMetrics.heightPixels / 4).coerceAtLeast(dp(175))
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

        // 3. Scrollable lower pane: Coordinates + Staleness + Witness Report + Evidence Details
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
                bottomMargin = dp(12)
            }
            setOnClickListener {
                copyCoordsToClipboard()
            }
        }

        coordsText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setOnClickListener {
                copyCoordsToClipboard()
            }
        }

        stalenessAndMetaText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xDDFFFFFF.toInt())
            setPadding(0, dp(4), 0, 0)
        }

        accuracyRadiusText = TextView(this).apply {
            visibility = View.GONE
        }

        coordsCard.addView(coordsText)
        coordsCard.addView(stalenessAndMetaText)
        coordsCard.addView(accuracyRadiusText)
        lowerContent.addView(coordsCard)

        // 4. Witness Report Summary Card & Action Button (#23, #25)
        witnessSummaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x44000000)
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            visibility = View.GONE
        }

        val witnessCardHeader = TextView(this).apply {
            text = "VERIFIED WITNESS REPORT"
            textSize = 11.5f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF93C5FD.toInt())
        }

        witnessSummaryText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
        }

        witnessSummaryCard.addView(witnessCardHeader)
        witnessSummaryCard.addView(witnessSummaryText)
        lowerContent.addView(witnessSummaryCard)

        witnessReportButton = MaterialButton(this).apply {
            text = "Submit / Update Witness Report"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(0x3D000000)
            strokeColor = ColorStateList.valueOf(Color.WHITE)
            strokeWidth = dp(1)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
            setOnClickListener {
                showWitnessReportDialog()
            }
        }
        lowerContent.addView(witnessReportButton)

        // 5. Section for Details of All Evidence
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
        val nowMs = System.currentTimeMillis()
        val statusColor = KokohoPostureEngine.currentPostureColorArgb(this)
        rootLayout.setBackgroundColor(statusColor)

        val threat: EvaluatedThreat? = KokohoPostureEngine.findThreatByUuid(this, entityUuid)
        val userFix = ScanForegroundService.lastFix

        if (threat != null) {
            currentAgency = threat.agency
            currentClassification = KokohoSignatures.resolveEntityLabel(this, entityUuid, threat.friendlyLabel)
            currentLat = threat.lat ?: userFix?.latitude ?: 32.2226
            currentLon = threat.lon ?: userFix?.longitude ?: -110.9747
            currentRadiusM = threat.mapCircleRadiusM
            currentIsPinpointed = threat.isPinpointed
            currentPostureColor = threat.posture.colorArgb
            currentUpdatedAtMs = threat.updatedAtMs
            currentAltitudeFt = threat.altitudeFtAgl
            currentHardwareId = threat.hardwareIdentifier
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
            currentUpdatedAtMs = snap?.lastSeen ?: nowMs
            currentAltitudeFt = (snap?.remoteId?.heightM ?: snap?.remoteId?.altitudeGeoM)?.let { (it * 3.28084).toInt() }
            currentHardwareId = snap?.mac ?: entityUuid.take(12)
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

        val witness = KokohoSignatures.getWitnessReport(this, entityUuid)
        avatarImageView.setImageBitmap(
            KokohoViews.renderAgentAvatarBitmap(this, entityUuid, witness?.photoDataUrl, dp(48))
        )

        if (!isEditingClassification) {
            classificationText.text = currentClassification
        }

        val coordStr = String.format(Locale.US, "%.5f, %.5f", currentLat, currentLon)
        val radiusStr = KokohoPostureEngine.formatAccuracyRadius(this, currentRadiusM)
        coordsText.text = "$coordStr ($radiusStr)"
        accuracyRadiusText.text = radiusStr

        val ageStr = KokohoPostureEngine.formatStalenessAge(nowMs - currentUpdatedAtMs)
        val extraParts = mutableListOf("Last seen: $ageStr")
        currentHardwareId?.takeIf { it.isNotBlank() }?.let { extraParts += it }
        currentAltitudeFt?.let { extraParts += "$it ft AGL" }
        stalenessAndMetaText.text = extraParts.joinToString(" · ")

        if (witness != null) {
            val lines = mutableListOf<String>()
            val agLabel = KokohoAgency.fromCode(witness.agencyCode)?.friendlyLabel ?: witness.agencyCode
            lines += "Agency: $agLabel${if (witness.department.isNotBlank()) " (${witness.department})" else ""}"
            if (witness.agentName.isNotBlank()) lines += "Name: ${witness.agentName}"
            if (witness.badgeNumber.isNotBlank()) lines += "Badge #: ${witness.badgeNumber}"
            if (witness.vehicleNumber.isNotBlank()) lines += "Vehicle / Tail #: ${witness.vehicleNumber}"
            if (witness.notes.isNotBlank()) lines += "Notes: ${witness.notes}"
            witnessSummaryText.text = lines.joinToString("\n")
            witnessSummaryCard.visibility = View.VISIBLE
            witnessReportButton.text = "Update Witness Report"
        } else {
            witnessSummaryCard.visibility = View.GONE
            witnessReportButton.text = "Submit Witness Report"
        }

        renderEvidenceList()
        renderMapPosition()
    }

    /**
     * Opens the Witness Report dialog (#23, #25) to submit/update verified agency, department,
     * badge number, agent name, vehicle number, notes, and optional photo upload.
     */
    fun showWitnessReportDialog() {
        val existing = KokohoSignatures.getWitnessReport(this, entityUuid)
        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        scroll.addView(form)

        val agencies = KokohoAgency.entries.sortedBy { it.priorityRank }
        val agencyLabels = agencies.map { "${it.friendlyLabel} — ${it.fullName}" }
        val agencySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@DeviceDetailActivity,
                android.R.layout.simple_spinner_dropdown_item,
                agencyLabels
            )
        }
        val initialAgency = existing?.agencyCode?.let { KokohoAgency.fromCode(it) } ?: currentAgency
        val initialIdx = agencies.indexOf(initialAgency).coerceAtLeast(0)
        agencySpinner.setSelection(initialIdx)

        fun field(hintText: String, initialValue: String): EditText = EditText(this).apply {
            hint = hintText
            setText(initialValue)
            textSize = 14.5f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val deptInput = field("Department / Station (e.g. ERO Tucson, PCSO)", existing?.department.orEmpty())
        val badgeInput = field("Badge Number", existing?.badgeNumber.orEmpty())
        val nameInput = field("Agent / Officer Name", existing?.agentName.orEmpty())
        val vehicleInput = field("Vehicle / License / Tail Number", existing?.vehicleNumber.orEmpty())
        val notesInput = field("Notes / Uniform / Activity", existing?.notes.orEmpty()).apply {
            setSingleLine(false)
            minLines = 2
        }

        var selectedPhotoDataUrl: String? = existing?.photoDataUrl
        val photoStatusTv = TextView(this).apply {
            text = if (selectedPhotoDataUrl != null) "Photo attached ✓" else "No photo attached"
            textSize = 12.5f
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }

        val attachPhotoBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Attach / Replace Photo"
            textSize = 12.5f
            setOnClickListener {
                pendingPhotoCallback = { dataUrl ->
                    selectedPhotoDataUrl = dataUrl
                    photoStatusTv.text = "Photo attached ✓"
                }
                runCatching { pickPhotoLauncher.launch("image/*") }
            }
        }

        form.addView(TextView(this).apply {
            text = "Verified Agency"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        form.addView(agencySpinner)
        form.addView(deptInput)
        form.addView(badgeInput)
        form.addView(nameInput)
        form.addView(vehicleInput)
        form.addView(notesInput)
        form.addView(photoStatusTv)
        form.addView(attachPhotoBtn)

        AlertDialog.Builder(this)
            .setTitle("Witness Report")
            .setView(scroll)
            .setPositiveButton("Save & Sync") { _, _ ->
                val chosenAgency = agencies.getOrElse(agencySpinner.selectedItemPosition) { currentAgency }
                val primaryFacet = currentEvidence.firstOrNull()?.facetKey ?: entityUuid
                val report = WitnessReportRecord(
                    targetKey = primaryFacet,
                    entityUuid = entityUuid,
                    agencyCode = chosenAgency.id,
                    department = deptInput.text?.toString()?.trim().orEmpty(),
                    badgeNumber = badgeInput.text?.toString()?.trim().orEmpty(),
                    agentName = nameInput.text?.toString()?.trim().orEmpty(),
                    vehicleNumber = vehicleInput.text?.toString()?.trim().orEmpty(),
                    notes = notesInput.text?.toString()?.trim().orEmpty(),
                    photoDataUrl = selectedPhotoDataUrl,
                    updatedAtMs = System.currentTimeMillis()
                )
                KokohoSignatures.saveWitnessReport(this, report, syncRemote = true)
                Toast.makeText(this, "Witness report saved", Toast.LENGTH_SHORT).show()
                refreshDetails()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun encodeUriToDataUrl(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return@use null
            val maxDim = 256
            val scale = minOf(maxDim.toFloat() / raw.width.coerceAtLeast(1), maxDim.toFloat() / raw.height.coerceAtLeast(1), 1f)
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 78, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }
    }.getOrNull()

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
        menu.clear()
        val whitelisted = KokohoSignatures.isEntityWhitelisted(this, entityUuid) || WhitelistCache.contains(rawKey)
        val watched = KokohoSignatures.isEntityWatched(this, entityUuid) || Favorites.contains(rawKey)

        menu.add(0, MENU_WITNESS, 0, "Witness Report")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_WHITELIST, 1, if (whitelisted) "Remove from Whitelist" else "Whitelist")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_WATCH, 2, if (watched) "Unwatch" else "Watch")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SHARE, 3, "Share")
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
            MENU_WITNESS -> {
                showWitnessReportDialog()
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
                moveTo(w * 0.26f, h * 0.74f)
                lineTo(w * 0.30f, h * 0.58f)
                lineTo(w * 0.64f, h * 0.24f)
                lineTo(w * 0.76f, h * 0.36f)
                lineTo(w * 0.42f, h * 0.70f)
                close()
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
