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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoDirectory
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
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
            form.addView(infoTv)
            form.addView(verifiedDetailsInput)
            form.addView(suppressCheckbox)

            val submitBtn = KokohoJourneyUi.primaryActionButton(activity, "Submit False Positive") {
                val detail = verifiedDetailsInput.text?.toString()?.trim().orEmpty()
                if (detail.isEmpty()) {
                    Toast.makeText(activity, "Please enter verified device details", Toast.LENGTH_SHORT).show()
                    return@primaryActionButton
                }
                val rec = WitnessReportRecord(
                    targetKey = targetUuid,
                    entityUuid = targetUuid,
                    agencyCode = selectedAgency.id,
                    notes = detail,
                    reportKind = "FALSE_POSITIVE",
                    subjectKind = "DEVICE",
                    lat = droppedLat,
                    lon = droppedLon
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
            dialog.setContentView(outer)
            dialog.show()
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
                val rec = WitnessReportRecord(
                    targetKey = targetUuid,
                    entityUuid = targetUuid,
                    agencyCode = selectedAgency.id,
                    notes = detail,
                    reportKind = "BUG",
                    subjectKind = "BUG",
                    lat = droppedLat,
                    lon = droppedLon
                )
                KokohoSignatures.saveWitnessReport(activity, rec, syncRemote = true)
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
            dialog.setContentView(outer)
            dialog.show()
            return
        }

        // ---- LAW_ENFORCEMENT / CONFIRM_REFINE Adaptive Wizard ----
        form.addView(sectionCaption("1. Select Agency"))
        val agencyPickerBtn = KokohoJourneyUi.secondaryActionButton(
            activity,
            "${selectedAgency.friendlyLabel} — ${selectedAgency.fullName}  ▾"
        ) {}
        agencyPickerBtn.setOnClickListener {
            val agencies = KokohoAgency.entries.sortedBy { it.priorityRank }
            KokohoJourneyUi.showActionSheetModal(
                activity,
                "Select Agency",
                agencies.map { ag ->
                    KokohoJourneyUi.SubjourneyMenuItem("${ag.friendlyLabel} — ${ag.fullName}") {
                        selectedAgency = ag
                        agencyPickerBtn.text = "${ag.friendlyLabel} — ${ag.fullName}  ▾"
                    }
                }
            )
        }
        form.addView(agencyPickerBtn)

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
            if (q.length < 2) {
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
                        agencyPickerBtn.text = "${selectedAgency.friendlyLabel} — ${selectedAgency.fullName}  ▾"
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

        // -- B. Vehicle Fields --
        val vehicleNumInput = styledInput("Cruiser / Fleet / Tail # (e.g. 412, N822AZ)", existing?.vehicleNumber.orEmpty())
        val vehiclePlateInput = styledInput("License Plate (optional)", existing?.plateNumber.orEmpty())
        val vehicleDescInput = styledInput("Make / Color / Upfit (e.g. Unmarked White Tahoe, Cage + ALPR)", existing?.vehicleDescription.orEmpty())
        vehicleSection.addView(vehicleNumInput)
        vehicleSection.addView(vehiclePlateInput)
        vehicleSection.addView(vehicleDescInput)
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

        // Shared Notes + Photos
        val notesInput = styledInput("Field Notes / Context…", existing?.notes.orEmpty(), multiLine = true)
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
                val bmp = KokohoJourneyUi.decodeDataUrlBitmap(url) ?: continue
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
            val plateVal = if (selectedSubject == SubjectKind.VEHICLE) vehiclePlateInput.text?.toString()?.trim().orEmpty() else ""
            val vehDescVal = if (selectedSubject == SubjectKind.VEHICLE) vehicleDescInput.text?.toString()?.trim().orEmpty() else ""
            val accVal = if (selectedSubject == SubjectKind.ACCESSORY) selectedAccessoryType else ""
            val notesVal = notesInput.text?.toString()?.trim().orEmpty()

            var resolvedPid = selectedPersonId
            if (selectedSubject == SubjectKind.AGENT && nameVal.isNotEmpty()) {
                val pid = resolvedPid ?: "user-${UUID.randomUUID().toString().take(8)}"
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
                notes = listOf(vehDescVal, notesVal).filter { it.isNotBlank() }.joinToString(" · "),
                photoDataUrl = attachedPhotos.firstOrNull(),
                status = if (KokohoSignatures.isSharingActive(activity)) "PENDING" else "APPROVED",
                reportKind = "LE",
                subjectKind = selectedSubject.id,
                personId = resolvedPid,
                titleOrRole = titleVal,
                email = emailVal,
                phone = phoneVal,
                suiteOrAddress = suiteVal,
                managedBy = mgrVal,
                plateNumber = plateVal,
                vehicleDescription = vehDescVal,
                accessoryKind = accVal,
                photoDataUrls = attachedPhotos.toList(),
                lat = droppedLat ?: existing?.lat,
                lon = droppedLon ?: existing?.lon
            )
            KokohoSignatures.saveWitnessReport(activity, rec, syncRemote = KokohoSignatures.isSharingActive(activity))
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

        dialog.setContentView(outer)
        dialog.show()
    }
}
