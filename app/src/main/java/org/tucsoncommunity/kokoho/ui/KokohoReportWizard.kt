package org.tucsoncommunity.kokoho.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import org.tucsoncommunity.kokoho.posture.DeviceTrustManager
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoDirectory
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.posture.RecentSignalRingBuffer
import org.tucsoncommunity.kokoho.posture.WitnessReportRecord
import java.util.UUID

/**
 * Contextual Report Wizard Modal (`v1.3.x`):
 *  - Dropped Point: `"Law Enforcement"` | `"Bug Report"`
 *  - Selected Detection: `"Confirm/Refine"` | `"False Positive"` | `"Bug Report"`
 *
 * All paths save structured local/Auxnet report state and route to the Entity Details screen,
 * from which Back returns cleanly to the Map with selection & root footer intact.
 */
object KokohoReportWizard {

    enum class Mode(val headerTitle: String) {
        CONFIRM_REFINE("Confirm / Refine Detection"),
        LAW_ENFORCEMENT("Report Law Enforcement Sighting"),
        FALSE_POSITIVE("Report False Positive"),
        BUG_REPORT("Submit Bug Report")
    }

    enum class SubjectKind(val id: String, val label: String) {
        AGENT("AGENT", "Agent"),
        VEHICLE("VEHICLE", "Vehicle"),
        ACCESSORY("ACCESSORY", "Accessory")
    }

    private val ACCESSORY_TYPES = listOf(
        "Bodycam",
        "Pole Cam / Pelican Battery",
        "Fleet Router",
        "Citation Printer",
        "Portable Radio",
        "Other Tactical Kit"
    )

    private fun dp(act: AppCompatActivity, v: Int): Int =
        (v * act.resources.displayMetrics.density).toInt()

    fun open(
        activity: AppCompatActivity,
        mode: Mode,
        existingEntityUuid: String? = null,
        initialAgency: KokohoAgency? = null,
        droppedLat: Double? = null,
        droppedLon: Double? = null,
        requestPickPhoto: (((String) -> Unit) -> Unit)? = null,
        onSubmitted: (entityKey: String) -> Unit
    ) {
        val targetUuid = existingEntityUuid?.let { KokohoSignatures.normalizeEntityId(it) }
            ?: KokohoPostureEngine.deterministicUuidV5(
                "dropped:${droppedLat ?: 0.0}:${droppedLon ?: 0.0}:${System.currentTimeMillis()}"
            )
        val existing = existingEntityUuid?.let { KokohoSignatures.getWitnessReport(activity, it, approvedOnly = false) }
        var selectedAgency = existing?.agencyCode?.let { KokohoAgency.fromCode(it) }
            ?: initialAgency
            ?: KokohoAgency.DHS_ICE
        var selectedSubject = SubjectKind.entries.firstOrNull { it.id.equals(existing?.subjectKind, ignoreCase = true) }
            ?: when {
                !existing?.vehicleNumber.isNullOrBlank() || !existing?.plateNumber.isNullOrBlank() -> SubjectKind.VEHICLE
                !existing?.accessoryKind.isNullOrBlank() -> SubjectKind.ACCESSORY
                else -> SubjectKind.AGENT
            }
        var selectedPersonId: String? = existing?.personId
        var selectedAccessoryType = existing?.accessoryKind?.takeIf { it.isNotBlank() } ?: ACCESSORY_TYPES.first()
        val attachedPhotos = ArrayList<String>().apply {
            existing?.allPhotos()?.let { addAll(it) }
        }

        val dialog = KokohoJourneyUi.createCustomModal(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = KokohoJourneyUi.cardDrawable(activity, KokohoJourneyUi.SURFACE_DARK, KokohoJourneyUi.BORDER_STRONG, 18)
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
        }

        // Modal Header
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val titleTv = TextView(activity).apply {
            text = mode.headerTitle
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.INK_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Cancel"
            background = KokohoJourneyUi.pillDrawable(activity, KokohoJourneyUi.SURFACE_ELEVATED, KokohoJourneyUi.BORDER_SUBTLE)
            setImageDrawable(KokohoJourneyUi.VectorIconDrawable(activity, KokohoJourneyUi.VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        outer.addView(headerRow)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(form)
        outer.addView(scroll)

        fun styledInput(hintText: String, initialValue: String = "", multiLine: Boolean = false): EditText =
            EditText(activity).apply {
                hint = hintText
                setText(initialValue)
                textSize = 14.5f
                setTextColor(KokohoJourneyUi.INK_PRIMARY)
                setHintTextColor(KokohoJourneyUi.INK_MUTED)
                setSingleLine(!multiLine)
                if (multiLine) {
                    minLines = 3
                    gravity = Gravity.TOP or Gravity.START
                }
                setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
                background = KokohoJourneyUi.cardDrawable(activity, KokohoJourneyUi.SURFACE_CARD, KokohoJourneyUi.BORDER_SUBTLE, 10)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(activity, 8)
                }
            }

        fun sectionCaption(textStr: String): TextView = TextView(activity).apply {
            text = textStr
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
            setPadding(dp(activity, 2), dp(activity, 10), 0, dp(activity, 4))
        }

        fun buildNearbySignalsOptInSection(): Pair<LinearLayout, CheckBox> {
            val windowMin = RecentSignalRingBuffer.observedSpanMinutes(System.currentTimeMillis())
            val wrap = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 8), 0, dp(activity, 2))
            }
            val shareAllCheck = CheckBox(activity).apply {
                text = "Share all nearby signals from the last $windowMin min"
                textSize = 13.5f
                setTextColor(KokohoJourneyUi.INK_PRIMARY)
                buttonTintList = ColorStateList.valueOf(KokohoJourneyUi.HIGHLIGHT_AMBER)
                isChecked = false
                setPadding(dp(activity, 6), dp(activity, 4), 0, dp(activity, 2))
            }
            val disclosureTv = TextView(activity).apply {
                text = "Flagged and candidate vehicle signals from the last 10 min are attached automatically. Checking this box also includes ordinary nearby Wi-Fi and Bluetooth signals heard along your path over the last $windowMin min, which may reveal the blocks you traveled."
                textSize = 12f
                setTextColor(KokohoJourneyUi.INK_MUTED)
                setPadding(dp(activity, 8), 0, dp(activity, 4), dp(activity, 4))
            }
            wrap.addView(shareAllCheck)
            wrap.addView(disclosureTv)
            return wrap to shareAllCheck
        }

        // ---- Mode-Specific Form Construction ----
        if (mode == Mode.FALSE_POSITIVE) {
            val infoTv = TextView(activity).apply {
                text = "Describe the verified non-law-enforcement device or stationary source so signatures can be refined:"
                textSize = 13.5f
                setTextColor(KokohoJourneyUi.INK_SECONDARY)
            }
            val verifiedDetailsInput = styledInput(
                "Verified device details (e.g. Apartment Room 101 wall AP, retail POS router)…",
                existing?.takeIf { it.reportKind == "FALSE_POSITIVE" }?.notes.orEmpty(),
                multiLine = true
            )
            val suppressCheckbox = CheckBox(activity).apply {
                text = "Suppress / hide this detection on my map & monitor"
                textSize = 14f
                setTextColor(KokohoJourneyUi.INK_PRIMARY)
                buttonTintList = ColorStateList.valueOf(KokohoJourneyUi.HIGHLIGHT_AMBER)
                isChecked = false
                setPadding(dp(activity, 6), dp(activity, 10), 0, dp(activity, 6))
            }
            val (fpSignalsWrap, fpShareAllCheck) = buildNearbySignalsOptInSection()
            form.addView(infoTv)
            form.addView(verifiedDetailsInput)
            form.addView(suppressCheckbox)
            form.addView(fpSignalsWrap)

            val submitBtn = KokohoJourneyUi.primaryActionButton(activity, "Submit False Positive") {
                val detail = verifiedDetailsInput.text?.toString()?.trim().orEmpty()
                if (detail.isEmpty()) {
                    Toast.makeText(activity, "Please enter verified device details", Toast.LENGTH_SHORT).show()
                    return@primaryActionButton
                }
                val submitNowMs = System.currentTimeMillis()
                val attachedSignals = RecentSignalRingBuffer.snapshotDtosForReport(
                    context = activity,
                    nowMs = submitNowMs,
                    includeAmbient = fpShareAllCheck.isChecked
                )
                val rec = WitnessReportRecord(
                    targetKey = targetUuid,
                    entityUuid = targetUuid,
                    agencyCode = selectedAgency.id,
                    notes = detail,
                    reportKind = "FALSE_POSITIVE",
                    subjectKind = "DEVICE",
                    lat = droppedLat,
                    lon = droppedLon,
                    nearbySignals = attachedSignals
                )
                KokohoSignatures.saveWitnessReport(activity, rec, syncRemote = true)
                if (suppressCheckbox.isChecked) {
                    KokohoSignatures.setEntityWhitelisted(activity, targetUuid, true)
                }
                org.tucsoncommunity.kokoho.posture.FieldTelemetryLogger.recordUserInteraction(
                    context = activity,
                    action = "REPORT_FALSE_POSITIVE",
                    targetKey = targetUuid,
                    detail = detail
                )
                dialog.dismiss()
                onSubmitted("kokoho:$targetUuid")
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 14) }
            }
                outer.addView(submitBtn)
                val modalW = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
                outer.minimumWidth = modalW
                dialog.setContentView(outer, ViewGroup.LayoutParams(modalW, ViewGroup.LayoutParams.WRAP_CONTENT))
                dialog.show()
                dialog.window?.setLayout(modalW, ViewGroup.LayoutParams.WRAP_CONTENT)
                return
            }

            if (mode == Mode.BUG_REPORT) {
                val infoTv = TextView(activity).apply {
                    text = "Describe what happened or behaved unexpectedly (local telemetry context is attached automatically):"
                    textSize = 13.5f
                    setTextColor(KokohoJourneyUi.INK_SECONDARY)
                }
                val bugInput = styledInput("Bug report details…", multiLine = true)
                form.addView(infoTv)
                form.addView(bugInput)

                val submitBtn = KokohoJourneyUi.primaryActionButton(activity, "Submit Bug Report") {
                    val detail = bugInput.text?.toString()?.trim().orEmpty()
                    if (detail.isEmpty()) {
                        Toast.makeText(activity, "Please enter bug report details", Toast.LENGTH_SHORT).show()
                        return@primaryActionButton
                    }
                    org.tucsoncommunity.kokoho.posture.KokohoHubClient.submitBugReport(
                        context = activity,
                        category = "Map / Detection",
                        summary = detail,
                        targetKey = targetUuid,
                        lat = droppedLat,
                        lon = droppedLon,
                        includeDiagnostics = true
                    )
                    org.tucsoncommunity.kokoho.posture.FieldTelemetryLogger.recordUserInteraction(
                        context = activity,
                        action = "REPORT_BUG",
                        targetKey = targetUuid,
                        detail = detail
                    )
                    dialog.dismiss()
                    onSubmitted("kokoho:$targetUuid")
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(activity, 14) }
                }
                outer.addView(submitBtn)
                val modalW = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
                outer.minimumWidth = modalW
                dialog.setContentView(outer, ViewGroup.LayoutParams(modalW, ViewGroup.LayoutParams.WRAP_CONTENT))
                dialog.show()
                dialog.window?.setLayout(modalW, ViewGroup.LayoutParams.WRAP_CONTENT)
                return
            }

        // ---- LAW_ENFORCEMENT / CONFIRM_REFINE Adaptive Wizard ----
        form.addView(sectionCaption("1. Select Agency"))
        val agencyPickerBtn = KokohoJourneyUi.secondaryActionButton(
            activity,
            "${selectedAgency.friendlyLabel}: ${selectedAgency.fullName}  ▾"
        ) {}
        agencyPickerBtn.setOnClickListener {
            val agencies = KokohoAgency.entries.sortedBy { it.priorityRank }
            KokohoJourneyUi.showActionSheetModal(
                activity,
                "Select Agency",
                agencies.map { ag ->
                    KokohoJourneyUi.SubjourneyMenuItem("${ag.friendlyLabel}: ${ag.fullName}") {
                        selectedAgency = ag
                        agencyPickerBtn.text = "${ag.friendlyLabel}: ${ag.fullName}  ▾"
                    }
                }
            )
        }
        form.addView(agencyPickerBtn)

        var confidencePctVal = (existing?.confidencePct ?: 85).coerceIn(0, 100)
        val confCaption = sectionCaption("Confidence: $confidencePctVal%")
        val confSeekBar = SeekBar(activity).apply {
            max = 100
            progress = confidencePctVal
            progressTintList = ColorStateList.valueOf(KokohoJourneyUi.HIGHLIGHT_AMBER)
            thumbTintList = ColorStateList.valueOf(KokohoJourneyUi.HIGHLIGHT_AMBER)
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    confidencePctVal = progress.coerceIn(0, 100)
                    confCaption.text = "Confidence: $confidencePctVal%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        form.addView(confCaption)
        form.addView(confSeekBar)

        form.addView(sectionCaption("2. Subject Type"))
        val subjectTabsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val agentSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val vehicleSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val accessorySection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val subjectButtons = LinkedHashMap<SubjectKind, TextView>()
        fun syncSubjectTabs() {
            for ((kind, tv) in subjectButtons) {
                val active = kind == selectedSubject
                tv.background = KokohoJourneyUi.pillDrawable(
                    activity,
                    if (active) KokohoJourneyUi.HIGHLIGHT_AMBER else KokohoJourneyUi.SURFACE_CARD,
                    if (active) KokohoJourneyUi.INK_PRIMARY else KokohoJourneyUi.BORDER_SUBTLE,
                    radiusDp = 10
                )
                tv.setTextColor(if (active) KokohoJourneyUi.SURFACE_DARK else KokohoJourneyUi.INK_PRIMARY)
            }
            agentSection.visibility = if (selectedSubject == SubjectKind.AGENT) View.VISIBLE else View.GONE
            vehicleSection.visibility = if (selectedSubject == SubjectKind.VEHICLE) View.VISIBLE else View.GONE
            accessorySection.visibility = if (selectedSubject == SubjectKind.ACCESSORY) View.VISIBLE else View.GONE
        }

        for (kind in SubjectKind.entries) {
            val tab = TextView(activity).apply {
                text = kind.label
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (kind != SubjectKind.entries.last()) dp(activity, 6) else 0
                }
                setOnClickListener {
                    selectedSubject = kind
                    syncSubjectTabs()
                }
            }
            subjectButtons[kind] = tab
            subjectTabsRow.addView(tab)
        }
        form.addView(subjectTabsRow)

        // -- A. Agent / Individual Fields + Offline AZ Directory Autocomplete + "More details" Popup --
        val agentNameInput = styledInput("Full Name (e.g. John Doe)", existing?.agentName.orEmpty())
        val suggestionsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(activity, 4), 0, 0)
        }
        val agentTitleInput = styledInput("Title / Role (e.g. Deportation Officer, Special Agent)", existing?.titleOrRole.orEmpty())
        val agentBadgeInput = styledInput("Badge # (leave blank if unobserved)", existing?.badgeNumber.orEmpty())

        // Extraneous Person fields hidden by default under Google-Contacts-style "+ More details" button
        val extraEmailInput = styledInput("Work / Contact Email", existing?.email.orEmpty()).apply {
            visibility = if (existing?.email.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val extraPhoneInput = styledInput("Phone / Mobile Number", existing?.phone.orEmpty()).apply {
            visibility = if (existing?.phone.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val extraSuiteInput = styledInput("Station / Suite / Street Address", existing?.suiteOrAddress.orEmpty()).apply {
            visibility = if (existing?.suiteOrAddress.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val extraManagedByInput = styledInput("Managed By (Supervisor)", existing?.managedBy.orEmpty()).apply {
            visibility = if (existing?.managedBy.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val moreDetailsBtn = KokohoJourneyUi.secondaryActionButton(activity, "+ More details") {
            val hiddenOptions = ArrayList<KokohoJourneyUi.SubjourneyMenuItem>()
            if (extraEmailInput.visibility != View.VISIBLE) {
                hiddenOptions += KokohoJourneyUi.SubjourneyMenuItem("Email") {
                    extraEmailInput.visibility = View.VISIBLE
                    extraEmailInput.requestFocus()
                }
            }
            if (extraPhoneInput.visibility != View.VISIBLE) {
                hiddenOptions += KokohoJourneyUi.SubjourneyMenuItem("Phone / Mobile") {
                    extraPhoneInput.visibility = View.VISIBLE
                    extraPhoneInput.requestFocus()
                }
            }
            if (extraSuiteInput.visibility != View.VISIBLE) {
                hiddenOptions += KokohoJourneyUi.SubjourneyMenuItem("Suite / Street Address") {
                    extraSuiteInput.visibility = View.VISIBLE
                    extraSuiteInput.requestFocus()
                }
            }
            if (extraManagedByInput.visibility != View.VISIBLE) {
                hiddenOptions += KokohoJourneyUi.SubjourneyMenuItem("Managed By") {
                    extraManagedByInput.visibility = View.VISIBLE
                    extraManagedByInput.requestFocus()
                }
            }
            if (hiddenOptions.isEmpty()) {
                Toast.makeText(activity, "All additional person fields are already shown", Toast.LENGTH_SHORT).show()
            } else {
                KokohoJourneyUi.showActionSheetModal(activity, "Add Person Detail Field", hiddenOptions)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8) }
        }

        var suppressAutocomplete = false
        agentNameInput.doAfterTextChanged { ed ->
            if (suppressAutocomplete) return@doAfterTextChanged
            val q = ed?.toString()?.trim().orEmpty()
            suggestionsBox.removeAllViews()
            if (q.length < 2 || !DeviceTrustManager.hasIntelligenceAccess(activity)) {
                suggestionsBox.visibility = View.GONE
                return@doAfterTextChanged
            }
            val matches = KokohoDirectory.searchPersons(activity, q, agencyHint = selectedAgency, limit = 5)
            if (matches.isEmpty()) {
                suggestionsBox.visibility = View.GONE
                return@doAfterTextChanged
            }
            suggestionsBox.visibility = View.VISIBLE
            for (person in matches) {
                val chip = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = KokohoJourneyUi.cardDrawable(activity, KokohoJourneyUi.SURFACE_ELEVATED, KokohoJourneyUi.HIGHLIGHT_AMBER, 8)
                    setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(activity, 4) }
                    setOnClickListener {
                        suppressAutocomplete = true
                        selectedPersonId = person.personId
                        agentNameInput.setText(person.fullName)
                        agentNameInput.setSelection(person.fullName.length)
                        if (person.title.isNotBlank()) agentTitleInput.setText(person.title)
                        selectedAgency = person.agency
                        agencyPickerBtn.text = "${selectedAgency.friendlyLabel}: ${selectedAgency.fullName}  ▾"
                        if (person.email.isNotBlank()) {
                            extraEmailInput.setText(person.email)
                            extraEmailInput.visibility = View.VISIBLE
                        }
                        val ph = person.mobilePhone.ifBlank { person.primaryPhone }
                        if (ph.isNotBlank()) {
                            extraPhoneInput.setText(ph)
                            extraPhoneInput.visibility = View.VISIBLE
                        }
                        if (person.suite.isNotBlank() || person.streetAddress.isNotBlank()) {
                            extraSuiteInput.setText(listOf(person.suite, person.streetAddress).filter { it.isNotBlank() }.joinToString(" · "))
                            extraSuiteInput.visibility = View.VISIBLE
                        }
                        suggestionsBox.removeAllViews()
                        suggestionsBox.visibility = View.GONE
                        suppressAutocomplete = false
                    }
                }
                val line1 = TextView(activity).apply {
                    text = "${person.fullName} (${person.resolvedAgencyDisplay})"
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(KokohoJourneyUi.INK_PRIMARY)
                }
                val line2 = TextView(activity).apply {
                    text = listOf(person.title, person.suite).filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = 12f
                    setTextColor(KokohoJourneyUi.INK_SECONDARY)
                    visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
                chip.addView(line1)
                chip.addView(line2)
                suggestionsBox.addView(chip)
            }
        }

        agentSection.addView(agentNameInput)
        agentSection.addView(suggestionsBox)
        agentSection.addView(agentTitleInput)
        agentSection.addView(agentBadgeInput)
        agentSection.addView(extraEmailInput)
        agentSection.addView(extraPhoneInput)
        agentSection.addView(extraSuiteInput)
        agentSection.addView(extraManagedByInput)
        agentSection.addView(moreDetailsBtn)
        form.addView(agentSection)

        // -- B. Vehicle Fields (Separated Plate/State/Partial + Color/Body Type/Make/Model + Fleet #) --
        fun sideBySideRow(left: EditText, right: EditText, leftWeight: Float = 1f, rightWeight: Float = 1f): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 8) }
                left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, leftWeight).apply {
                    marginEnd = dp(activity, 6)
                }
                right.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, rightWeight)
                addView(left)
                addView(right)
            }

        val vehiclePlateInput = styledInput("License Plate #", existing?.plateNumber.orEmpty())
        val vehicleStateInput = styledInput("State", existing?.plateState?.ifBlank { "AZ" } ?: "AZ")
        val partialPlateCheck = CheckBox(activity).apply {
            text = "Partial plate"
            textSize = 13.5f
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            buttonTintList = ColorStateList.valueOf(KokohoJourneyUi.HIGHLIGHT_AMBER)
            isChecked = existing?.partialPlate == true
            setPadding(dp(activity, 4), dp(activity, 4), 0, dp(activity, 2))
        }
        val vehicleColorInput = styledInput("Color (e.g. White)", existing?.vehicleColor.orEmpty())
        val vehicleBodyInput = styledInput("Body Type (e.g. SUV)", existing?.vehicleBodyType.orEmpty())
        val vehicleMakeInput = styledInput("Make (e.g. Chevrolet)", existing?.vehicleMake.orEmpty())
        val vehicleModelInput = styledInput("Model (e.g. Tahoe)", existing?.vehicleModel.orEmpty())
        val vehicleNumInput = styledInput("Fleet / Bumper / Tail # (optional)", existing?.vehicleNumber.orEmpty())

        vehicleSection.addView(sideBySideRow(vehiclePlateInput, vehicleStateInput, 2.2f, 1f))
        vehicleSection.addView(partialPlateCheck)
        vehicleSection.addView(sideBySideRow(vehicleColorInput, vehicleBodyInput))
        vehicleSection.addView(sideBySideRow(vehicleMakeInput, vehicleModelInput))
        vehicleSection.addView(vehicleNumInput)
        form.addView(vehicleSection)

        // -- C. Accessory Fields --
        val accessoryPickerBtn = KokohoJourneyUi.secondaryActionButton(activity, "Type: $selectedAccessoryType  ▾") {}
        accessoryPickerBtn.setOnClickListener {
            KokohoJourneyUi.showActionSheetModal(
                activity,
                "Select Accessory / Kit Type",
                ACCESSORY_TYPES.map { kind ->
                    KokohoJourneyUi.SubjourneyMenuItem(kind) {
                        selectedAccessoryType = kind
                        accessoryPickerBtn.text = "Type: $selectedAccessoryType  ▾"
                    }
                }
            )
        }
        accessoryPickerBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 8) }
        accessorySection.addView(accessoryPickerBtn)
        form.addView(accessorySection)

        // Unified Details unstructured input box + Photos
        val notesInput = styledInput("Details…", existing?.notes.orEmpty(), multiLine = true)
        form.addView(notesInput)

        val photosRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 8), 0, 0)
        }
        val photosScroll = HorizontalScrollView(activity).apply {
            addView(photosRow)
        }

        fun rebuildPhotosRow() {
            photosRow.removeAllViews()
            for ((idx, url) in attachedPhotos.withIndex()) {
                val bmp = KokohoJourneyUi.decodeDataUrlBitmap(url, maxSidePx = 256) ?: continue
                val cell = FrameLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 64)).apply {
                        marginEnd = dp(activity, 8)
                    }
                }
                val iv = ImageView(activity).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setOnClickListener {
                        KokohoJourneyUi.showPhotoCarouselModal(activity, attachedPhotos, idx)
                    }
                }
                val del = TextView(activity).apply {
                    text = "✕"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = KokohoJourneyUi.pillDrawable(activity, 0xCC000000.toInt(), Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(dp(activity, 22), dp(activity, 22), Gravity.TOP or Gravity.END)
                    setOnClickListener {
                        attachedPhotos.removeAt(idx)
                        rebuildPhotosRow()
                    }
                }
                cell.addView(iv)
                cell.addView(del)
                photosRow.addView(cell)
            }
        }
        rebuildPhotosRow()

        if (requestPickPhoto != null) {
            val addPhotoBtn = KokohoJourneyUi.secondaryActionButton(activity, "+ Attach Photo") {
                requestPickPhoto { encoded ->
                    attachedPhotos += encoded
                    rebuildPhotosRow()
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 8) }
            }
            form.addView(addPhotoBtn)
            form.addView(photosScroll)
        }

        val (nearbySignalsWrap, shareAllNearbyCheck) = buildNearbySignalsOptInSection()
        form.addView(nearbySignalsWrap)

        syncSubjectTabs()

        // Action Footer
        val footerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val cancelBtn = KokohoJourneyUi.secondaryActionButton(activity, "Cancel") {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(activity, 8)
            }
        }
        val saveBtn = KokohoJourneyUi.primaryActionButton(activity, "Save Report") {
            val nameVal = if (selectedSubject == SubjectKind.AGENT) agentNameInput.text?.toString()?.trim().orEmpty() else ""
            val titleVal = if (selectedSubject == SubjectKind.AGENT) agentTitleInput.text?.toString()?.trim().orEmpty() else ""
            val badgeVal = if (selectedSubject == SubjectKind.AGENT) agentBadgeInput.text?.toString()?.trim().orEmpty() else ""
            val emailVal = if (selectedSubject == SubjectKind.AGENT) extraEmailInput.text?.toString()?.trim().orEmpty() else ""
            val phoneVal = if (selectedSubject == SubjectKind.AGENT) extraPhoneInput.text?.toString()?.trim().orEmpty() else ""
            val suiteVal = if (selectedSubject == SubjectKind.AGENT) extraSuiteInput.text?.toString()?.trim().orEmpty() else ""
            val mgrVal = if (selectedSubject == SubjectKind.AGENT) extraManagedByInput.text?.toString()?.trim().orEmpty() else ""

            val vehVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleNumInput.text?.toString()?.trim().orEmpty() else ""
            val plateVal = if (selectedSubject == SubjectKind.VEHICLE) vehiclePlateInput.text?.toString()?.trim()?.uppercase().orEmpty() else ""
            val plateStateVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleStateInput.text?.toString()?.trim()?.uppercase()?.ifEmpty { "AZ" } ?: "AZ" else "AZ"
            val partialVal = selectedSubject == SubjectKind.VEHICLE && partialPlateCheck.isChecked
            val colorVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleColorInput.text?.toString()?.trim().orEmpty() else ""
            val bodyVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleBodyInput.text?.toString()?.trim().orEmpty() else ""
            val makeVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleMakeInput.text?.toString()?.trim().orEmpty() else ""
            val modelVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleModelInput.text?.toString()?.trim().orEmpty() else ""
            val vehDescVal = listOf(colorVal, makeVal, modelVal, bodyVal).filter { it.isNotEmpty() }.joinToString(" ")
            val accVal = if (selectedSubject == SubjectKind.ACCESSORY) selectedAccessoryType else ""
            val notesVal = notesInput.text?.toString()?.trim().orEmpty()

            var resolvedPid: String? = null
            if (selectedSubject == SubjectKind.AGENT && nameVal.isNotEmpty() && DeviceTrustManager.hasIntelligenceAccess(activity)) {
                val pid = selectedPersonId ?: "user-${UUID.randomUUID().toString().take(8)}"
                resolvedPid = pid
                val existingPerson = KokohoDirectory.personById(activity, pid)
                KokohoDirectory.saveUserPerson(
                    activity,
                    KokohoDirectory.PersonRecord(
                        personId = pid,
                        fullName = nameVal,
                        title = titleVal.ifBlank { existingPerson?.title.orEmpty() },
                        agencyCode = selectedAgency.id,
                        agencyDisplay = selectedAgency.friendlyLabel,
                        locationId = existingPerson?.locationId.orEmpty(),
                        streetAddress = existingPerson?.streetAddress.orEmpty(),
                        suite = suiteVal.ifBlank { existingPerson?.suite.orEmpty() },
                        email = emailVal.ifBlank { existingPerson?.email.orEmpty() },
                        secondEmail = existingPerson?.secondEmail.orEmpty(),
                        primaryPhone = existingPerson?.primaryPhone.orEmpty(),
                        mobilePhone = phoneVal.ifBlank { existingPerson?.mobilePhone.orEmpty() },
                        badgeNumber = badgeVal.ifBlank { existingPerson?.badgeNumber.orEmpty() },
                        managedByNameOrId = mgrVal.ifBlank { existingPerson?.managedByNameOrId.orEmpty() }
                    )
                )
            }

            val rec = WitnessReportRecord(
                targetKey = targetUuid,
                entityUuid = targetUuid,
                agencyCode = selectedAgency.id,
                department = titleVal,
                badgeNumber = badgeVal,
                agentName = nameVal,
                vehicleNumber = vehVal,
                notes = notesVal,
                photoDataUrl = attachedPhotos.firstOrNull(),
                status = "PENDING",
                reportKind = "LE",
                subjectKind = selectedSubject.id,
                personId = resolvedPid,
                titleOrRole = titleVal,
                email = emailVal,
                phone = phoneVal,
                suiteOrAddress = suiteVal,
                managedBy = mgrVal,
                plateNumber = plateVal,
                plateState = plateStateVal,
                partialPlate = partialVal,
                vehicleColor = colorVal,
                vehicleBodyType = bodyVal,
                vehicleMake = makeVal,
                vehicleModel = modelVal,
                confidencePct = confidencePctVal,
                vehicleDescription = vehDescVal,
                accessoryKind = accVal,
                photoDataUrls = attachedPhotos.toList(),
                lat = droppedLat ?: existing?.lat,
                lon = droppedLon ?: existing?.lon,
                nearbySignals = RecentSignalRingBuffer.snapshotDtosForReport(
                    context = activity,
                    nowMs = System.currentTimeMillis(),
                    includeAmbient = shareAllNearbyCheck.isChecked
                )
            )
            KokohoSignatures.saveWitnessReport(activity, rec, syncRemote = true)
            org.tucsoncommunity.kokoho.posture.FieldTelemetryLogger.recordUserInteraction(
                context = activity,
                action = "SUBMIT_REPORT_${mode.name}",
                targetKey = targetUuid,
                detail = "${selectedAgency.id}:${selectedSubject.id}"
            )
            dialog.dismiss()
            onSubmitted("kokoho:$targetUuid")
        }
        footerRow.addView(cancelBtn)
        footerRow.addView(saveBtn)
        outer.addView(footerRow)

        val modalW = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
        outer.minimumWidth = modalW
        dialog.setContentView(outer, ViewGroup.LayoutParams(modalW, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        dialog.window?.setLayout(modalW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val BUG_CATEGORIES = listOf(
        "App / UI",
        "Map",
        "Detection",
        "RTL-SDR",
        "Crash / Freeze",
        "Other"
    )

    /**
     * Opens a standalone generic Bug Report modal from the root Hamburger Menu (`☰`),
     * queuing reports durably and uploading to `/v1/bugs` on `kokoho-hub` for batch review.
     */
    fun openGenericBugReportModal(activity: AppCompatActivity): android.app.Dialog {
        val modalWidthPx = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
        val dialog = KokohoJourneyUi.createCustomModal(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = modalWidthPx
            background = KokohoJourneyUi.cardDrawable(activity, KokohoJourneyUi.SURFACE_DARK, KokohoJourneyUi.BORDER_STRONG, 18)
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 8))
        }
        val titleTv = TextView(activity).apply {
            text = "Submit Bug Report"
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.INK_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Cancel"
            background = KokohoJourneyUi.pillDrawable(activity, KokohoJourneyUi.SURFACE_ELEVATED, KokohoJourneyUi.BORDER_SUBTLE)
            setImageDrawable(KokohoJourneyUi.VectorIconDrawable(activity, KokohoJourneyUi.VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        outer.addView(headerRow)

        val subtitleTv = TextView(activity).apply {
            text = "Pick an area and describe what happened so we can review and fix it:"
            textSize = 13.5f
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            setPadding(0, 0, 0, dp(activity, 8))
        }
        outer.addView(subtitleTv)

        var selectedCategory = BUG_CATEGORIES.first()
        val catButtons = LinkedHashMap<String, TextView>()
        val catRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val catScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8) }
            addView(catRow)
        }

        fun syncCategoryPills() {
            for ((cat, tv) in catButtons) {
                val active = cat == selectedCategory
                tv.background = KokohoJourneyUi.pillDrawable(
                    activity,
                    if (active) KokohoJourneyUi.HIGHLIGHT_AMBER else KokohoJourneyUi.SURFACE_CARD,
                    if (active) KokohoJourneyUi.INK_PRIMARY else KokohoJourneyUi.BORDER_SUBTLE,
                    radiusDp = 999
                )
                tv.setTextColor(if (active) KokohoJourneyUi.SURFACE_DARK else KokohoJourneyUi.INK_PRIMARY)
            }
        }

        for (cat in BUG_CATEGORIES) {
            val pill = TextView(activity).apply {
                text = cat
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(activity, 6) }
                setOnClickListener {
                    selectedCategory = cat
                    syncCategoryPills()
                }
            }
            catButtons[cat] = pill
            catRow.addView(pill)
        }
        syncCategoryPills()
        outer.addView(catScroll)

        val detailsInput = EditText(activity).apply {
            hint = "What happened? (e.g. screen you were on, what you tapped, or what looked wrong)…"
            textSize = 14.5f
            setTextColor(KokohoJourneyUi.INK_PRIMARY)
            setHintTextColor(KokohoJourneyUi.INK_MUTED)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
            background = KokohoJourneyUi.cardDrawable(activity, KokohoJourneyUi.SURFACE_CARD, KokohoJourneyUi.BORDER_SUBTLE, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        outer.addView(detailsInput)

        val diagCheck = CheckBox(activity).apply {
            text = "Include app version, sensor status & crash log (no GPS location)"
            textSize = 13f
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            buttonTintList = ColorStateList.valueOf(KokohoJourneyUi.HIGHLIGHT_AMBER)
            isChecked = true
            setPadding(dp(activity, 4), dp(activity, 8), 0, dp(activity, 4))
        }
        outer.addView(diagCheck)

        val footerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 10), 0, 0)
        }
        val cancelBtn = KokohoJourneyUi.secondaryActionButton(activity, "Cancel") {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(activity, 8) }
        }
        val submitBtn = KokohoJourneyUi.primaryActionButton(activity, "Submit Bug Report") {
            val summary = detailsInput.text?.toString()?.trim().orEmpty()
            if (summary.isEmpty()) {
                Toast.makeText(activity, "Please enter bug report details", Toast.LENGTH_SHORT).show()
                return@primaryActionButton
            }
            org.tucsoncommunity.kokoho.posture.KokohoHubClient.submitBugReport(
                context = activity,
                category = selectedCategory,
                summary = summary,
                targetKey = null,
                includeDiagnostics = diagCheck.isChecked
            ) { uploadedNow ->
                val msg = if (uploadedNow) {
                    "Bug report submitted. Thank you!"
                } else {
                    "Bug report saved. It will send automatically when connected."
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        footerRow.addView(cancelBtn)
        footerRow.addView(submitBtn)
        outer.addView(footerRow)

        dialog.setContentView(outer, ViewGroup.LayoutParams(modalWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        dialog.window?.setLayout(modalWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
