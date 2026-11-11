package org.tucsoncommunity.kokoho.settings

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import org.tucsoncommunity.kokoho.detect.Category
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoHubClient
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.sdr.SdrRadio
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.ui.ThemeManager
import org.tucsoncommunity.kokoho.util.UpdateChecker
import org.tucsoncommunity.kokoho.util.Prefs
import org.tucsoncommunity.kokoho.util.applySystemBarInsets

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECTION = "open_section"
        const val SECTION_MAP = "map"
        const val SECTION_NAV = "navigation"
        const val SECTION_DATA = "data"
        const val WAZE_WARNING_SHORT =
            "Use at your own risk. Reports come from third-party services."
        const val WAZE_WARNING =
            "Waze police reports are read through OpenWeb Ninja's Waze API with your own API key."
        const val WAZE_DIRECT_WARNING =
            "Direct reads Waze police reports from Waze itself."
    }

    private data class SearchableRow(
        val view: View,
        val keywords: String
    )

    private data class SearchableSection(
        val container: LinearLayout,
        val headerView: View,
        val rows: MutableList<SearchableRow> = mutableListOf()
    )

    private val sections = mutableListOf<SearchableSection>()
    private val agencySwitches = LinkedHashMap<KokohoAgency, SwitchMaterial>()
    private lateinit var masterDhsSwitch: SwitchMaterial
    private var updatingAgencySwitches = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        KokohoSignatures.ensureLoaded(this)

        val bgColor = KokohoPostureEngine.currentPostureColorArgb(this)
        val cardBg = 0x38000000
        val cardStroke = 0x55FFFFFF
        val primaryInk = Color.WHITE
        val secondaryInk = 0xDDFFFFFF.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.applySystemBarInsets()

        // Search Bar (upstream-style live settings search)
        val searchBox = EditText(this).apply {
            hint = "Search settings (e.g. DHS, CBP, ADS-B, decay, privacy, units)…"
            setSingleLine(true)
            textSize = 14.5f
            setTextColor(primaryInk)
            setHintTextColor(secondaryInk)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(cardBg)
                setStroke(dp(1), cardStroke)
            }
        }

        val searchWrapper = FrameLayout(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(8))
            addView(searchBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(searchWrapper)

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(28))
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scrollContent)
        }
        root.addView(scrollView)

        fun createSection(titleText: String? = null, subtitleText: String? = null): SearchableSection {
            val secLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(cardBg)
                    setStroke(dp(1), cardStroke)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }
            val headerBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            if (!titleText.isNullOrBlank()) {
                val titleTv = TextView(this).apply {
                    text = titleText
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(primaryInk)
                }
                headerBox.addView(titleTv)
                if (!subtitleText.isNullOrBlank()) {
                    val subTv = TextView(this).apply {
                        text = subtitleText
                        textSize = 12.5f
                        setTextColor(secondaryInk)
                        setPadding(0, dp(2), 0, 0)
                    }
                    headerBox.addView(subTv)
                }
                secLayout.addView(headerBox)
            }
            scrollContent.addView(secLayout)
            return SearchableSection(secLayout, headerBox).also { sections += it }
        }

        fun addSwitchRow(
            section: SearchableSection,
            titleText: String,
            subtitleText: String?,
            checked: Boolean,
            keywords: String = "",
            indentDp: Int = 0,
            onChanged: (Boolean) -> Unit
        ): SwitchMaterial {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(indentDp), dp(6), 0, dp(6))
            }
            val sw = SwitchMaterial(this).apply {
                text = titleText
                textSize = 14.5f
                setTextColor(primaryInk)
                isChecked = checked
                setOnCheckedChangeListener { _, isOn -> onChanged(isOn) }
            }
            row.addView(sw)
            if (!subtitleText.isNullOrBlank()) {
                val sub = TextView(this).apply {
                    text = subtitleText
                    textSize = 12f
                    setTextColor(secondaryInk)
                    setPadding(dp(4), 0, dp(48), dp(2))
                }
                row.addView(sub)
            }
            section.container.addView(row)
            section.rows += SearchableRow(row, "$titleText ${subtitleText.orEmpty()} $keywords".lowercase())
            return sw
        }

        fun addActionRow(
            section: SearchableSection,
            titleText: String,
            subtitleText: String,
            buttonLabel: String,
            keywords: String = "",
            onClick: (TextView) -> Unit
        ): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
            }
            val t = TextView(this).apply {
                text = titleText
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primaryInk)
            }
            val s = TextView(this).apply {
                text = subtitleText
                textSize = 12.5f
                setTextColor(secondaryInk)
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(t)
            textCol.addView(s)

            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = buttonLabel
                textSize = 12.5f
                setTextColor(Color.WHITE)
                strokeColor = android.content.res.ColorStateList.valueOf(0x88FFFFFF.toInt())
                setOnClickListener { onClick(s) }
            }
            row.addView(textCol)
            row.addView(btn)
            section.container.addView(row)
            section.rows += SearchableRow(row, "$titleText $subtitleText $buttonLabel $keywords".lowercase())
            return s
        }

        // ---- SECTION 1: Monitored Agencies (Radio, P25 & Fleet OUI) ----
        val secAgencies = createSection(
            "Monitored Agencies",
            "Controls which agencies appear in Monitor, Map, and List. DHS is enabled by default."
        )

        masterDhsSwitch = addSwitchRow(
            section = secAgencies,
            titleText = "DHS (Master Radio)",
            subtitleText = "Master toggle for all Department of Homeland Security sub-agencies",
            checked = KokohoSignatures.isMasterDhsEnabled(this),
            keywords = "dhs master radio homeland security border patrol ice cbp hsi amo fps interop"
        ) { isOn ->
            if (!updatingAgencySwitches) {
                KokohoSignatures.setMasterDhs(this, isOn)
                refreshAgencySwitchStates()
                syncRuntimeTargets()
            }
        }

        val dhsOrdered = listOf(
            KokohoAgency.DHS_ICE to "ICE (ERO Tactical channels & fleet)",
            KokohoAgency.DHS_CBP to "CBP (Border Patrol repeaters, tactical & fleet)",
            KokohoAgency.DHS_HSI to "HSI (Homeland Security Investigations)",
            KokohoAgency.DHS_AMO to "DHS Air (Air & Marine Operations air-to-ground)",
            KokohoAgency.DHS_FPS to "FPS (Federal Protective Service)",
            KokohoAgency.DHS_INTEROP to "Federal Interop ( National / AZ federal interop channels)"
        )
        val enabledNow = KokohoSignatures.getEnabledAgencies(this)
        for ((ag, desc) in dhsOrdered) {
            val sw = addSwitchRow(
                section = secAgencies,
                titleText = ag.friendlyLabel,
                subtitleText = desc,
                checked = ag in enabledNow,
                keywords = "dhs ${ag.friendlyLabel} ${ag.fullName}",
                indentDp = 14
            ) { _ ->
                if (!updatingAgencySwitches) {
                    KokohoSignatures.toggleAgency(this, ag)
                    refreshAgencySwitchStates()
                    syncRuntimeTargets()
                }
            }
            agencySwitches[ag] = sw
        }

        val optionalOrdered = listOf(
            KokohoAgency.FIRE to "Fire (TFD / Regional Fire & Wildland)",
            KokohoAgency.POLICE to "Police (TPD / Municipal dispatch & tactical)",
            KokohoAgency.SAR to "SAR (Search & Rescue / Mountain & Desert SAR)",
            KokohoAgency.SHERIFF to "Sheriff (PCSO / County Sheriff)"
        )
        for ((ag, desc) in optionalOrdered) {
            val sw = addSwitchRow(
                section = secAgencies,
                titleText = ag.friendlyLabel,
                subtitleText = desc,
                checked = ag in enabledNow,
                keywords = "optional ${ag.friendlyLabel} ${ag.fullName} tpd pcso tfd"
            ) { _ ->
                if (!updatingAgencySwitches) {
                    KokohoSignatures.toggleAgency(this, ag)
                    refreshAgencySwitchStates()
                    syncRuntimeTargets()
                }
            }
            agencySwitches[ag] = sw
        }

        // ---- SECTION 2: Aircraft (ADS-B) ----
        val secAircraft = createSection(
            "Aircraft (ADS-B)",
            "Server-side and local ADS-B aircraft tracking. On by default for DHS aircraft."
        )

        addSwitchRow(
            section = secAircraft,
            titleText = "Enable ADS-B Aircraft",
            subtitleText = "Monitor airborne transponders via Auxnet server ingestion, online ADS-B feeds, and local 1090 MHz SDR",
            checked = KokohoSignatures.isAdsbEnabled(this),
            keywords = "adsb aircraft plane helicopter aviation"
        ) { isOn ->
            KokohoSignatures.setAdsbEnabled(this, isOn)
            Prefs.setCategoryEnabled(this, Category.AIRCRAFT, isOn)
            Prefs.setAircraftOnline(this, isOn)
            syncRuntimeTargets()
        }

        val enabledAir = KokohoSignatures.getEnabledAircraftAgencies(this)
        addSwitchRow(
            section = secAircraft,
            titleText = "DHS Aircraft (CBP / AMO / ICE)",
            subtitleText = "Default ON — CBP Air & Marine Blackhawks, AS350s, PC-12s, and ICE airframes",
            checked = KokohoSignatures.isDhsAircraftEnabled(this),
            keywords = "dhs aircraft cbp amo ice helicopter plane omaha",
            indentDp = 10
        ) { isOn ->
            KokohoSignatures.setDhsAircraftEnabled(this, isOn)
        }

        val optionalAir = listOf(
            KokohoAgency.POLICE to "Police Aircraft (TPD / State Police)",
            KokohoAgency.SHERIFF to "Sheriff Aircraft (PCSO / County aviation)",
            KokohoAgency.SAR to "SAR Aircraft (Search & Rescue air assets)",
            KokohoAgency.FIRE to "Fire Aircraft (Aerial firefighting & spotters)"
        )
        for ((ag, label) in optionalAir) {
            addSwitchRow(
                section = secAircraft,
                titleText = label,
                subtitleText = "Default OFF — Show ${ag.friendlyLabel} aircraft when airborne nearby",
                checked = ag in enabledAir,
                keywords = "aircraft adsb ${ag.friendlyLabel} tpd sheriff pcso sar fire",
                indentDp = 10
            ) { _ ->
                KokohoSignatures.toggleAircraftAgency(this, ag)
            }
        }

        // ---- SECTION 3: Signal Decay Time (Separate section, no title) ----
        val secDecay = createSection(titleText = null)

        addActionRow(
            section = secDecay,
            titleText = "Signal Decay Time",
            subtitleText = "Retain flagged signals and local triangulation samples for ${KokohoSignatures.signalDecayMinutes(this)} minutes",
            buttonLabel = "Change",
            keywords = "decay time window retention triangulation"
        ) { subTv ->
            val choices = KokohoSignatures.DECAY_MINUTE_CHOICES
            val labels = choices.map { mins ->
                when (mins) {
                    30 -> "30 minutes (Default)"
                    60 -> "1 hour (60 minutes)"
                    120 -> "2 hours (120 minutes)"
                    else -> "$mins minutes"
                }
            }.toTypedArray()
            val curIdx = choices.indexOf(KokohoSignatures.signalDecayMinutes(this)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Signal Decay Time")
                .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                    val chosen = choices[which]
                    KokohoSignatures.setSignalDecayMinutes(this, chosen)
                    subTv.text = "Retain flagged signals and local triangulation samples for $chosen minutes"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---- SECTION 4: Privacy (Opt-out connected features) ----
        val secPrivacy = createSection(
            "Privacy",
            "Opt-out controls for connected features."
        )

        addSwitchRow(
            section = secPrivacy,
            titleText = "Contribute observations",
            subtitleText = "Trusts a hardened server and works without a VPN with minimal privacy concern. Observations are ephemeral in volatile memory; turning this off triggers an immediate and automatic purge of your contributed data.",
            checked = KokohoSignatures.isContributeObservationsEnabled(this),
            keywords = "privacy contribute observations server vpn ephemeral purge opt out"
        ) { isOn ->
            KokohoSignatures.setContributeObservationsEnabled(this, isOn)
            if (!isOn) {
                Toast.makeText(this, "Contributed observations purged", Toast.LENGTH_SHORT).show()
            }
        }

        addSwitchRow(
            section = secPrivacy,
            titleText = "Push notifications",
            subtitleText = "Uses no Google Services. Kokoho delivers real-time alerts using its own self-hosted push notification system.",
            checked = KokohoSignatures.isPushNotificationsEnabled(this),
            keywords = "privacy push notifications alerts google services self hosted"
        ) { isOn ->
            KokohoSignatures.setPushNotificationsEnabled(this, isOn)
        }

        // ---- SECTION 4: Channel Lists ----
        val secChannels = createSection(
            "Channel Lists",
            "Bundled catalog includes 51 AZ DHS/CBP/ICE/AMO channels + PCWIN talkgroups."
        )

        addActionRow(
            section = secChannels,
            titleText = "Import Channel List",
            subtitleText = "Paste RadioReference CSV/TSV tables or SDRTrunk XML playlists",
            buttonLabel = "Import",
            keywords = "import channel list radioreference sdrtrunk csv tsv xml frequencies"
        ) { _ ->
            showChannelImportDialog()
        }

        // ---- SECTION 5: Units, Alerts & Appearance ----
        val secDisplay = createSection(
            "Units, Alerts & Appearance",
            "Distance formatting, alert sound/vibration, and light/dark theme."
        )

        addActionRow(
            section = secDisplay,
            titleText = "Distance Units",
            subtitleText = if (KokohoSignatures.isImperialUnits(this)) "Imperial (ft / mi) — Default" else "Metric (m / km)",
            buttonLabel = "Change",
            keywords = "units imperial metric miles feet kilometers meters distance"
        ) { subTv ->
            val items = arrayOf("Imperial (ft / mi) — Default", "Metric (m / km)")
            val checked = if (KokohoSignatures.isImperialUnits(this)) 0 else 1
            MaterialAlertDialogBuilder(this)
                .setTitle("Distance Units")
                .setSingleChoiceItems(items, checked) { dialog, which ->
                    val imp = (which == 0)
                    KokohoSignatures.setImperialUnits(this, imp)
                    subTv.text = if (imp) "Imperial (ft / mi) — Default" else "Metric (m / km)"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        addSwitchRow(
            section = secDisplay,
            titleText = "Alert Sound",
            subtitleText = "Play audible alert tone when a monitored agency is detected",
            checked = Prefs.soundEnabled(this),
            keywords = "alert sound audio tone beep"
        ) { isOn ->
            Prefs.setSoundEnabled(this, isOn)
        }

        addSwitchRow(
            section = secDisplay,
            titleText = "Alert Vibration",
            subtitleText = "Vibrate when posture escalates",
            checked = Prefs.vibrateEnabled(this),
            keywords = "alert vibrate vibration haptic"
        ) { isOn ->
            Prefs.setVibrateEnabled(this, isOn)
        }

        addActionRow(
            section = secDisplay,
            titleText = "Theme",
            subtitleText = "Current: ${ThemeManager.current(this).title}",
            buttonLabel = "Change",
            keywords = "theme dark light appearance mode"
        ) { subTv ->
            val themes = listOf(
                ThemeManager.AppTheme.SYSTEM,
                ThemeManager.AppTheme.DARK,
                ThemeManager.AppTheme.LIGHT
            )
            val labels = themes.map { it.title }.toTypedArray()
            val cur = themes.indexOf(ThemeManager.current(this)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(labels, cur) { dialog, which ->
                    val picked = themes[which]
                    ThemeManager.set(this, picked)
                    subTv.text = "Current: ${picked.title}"
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        addActionRow(
            section = secDisplay,
            titleText = "Live App Updates",
            subtitleText = "Check Auxnet Hub (${KokohoHubClient.hubUrl(this)}) for newer Kokoho builds",
            buttonLabel = "Check Now",
            keywords = "update live apk version upgrade"
        ) { _ ->
            UpdateChecker.check(this, silent = false)
        }

        // Live search filtering
        searchBox.doAfterTextChanged { editable ->
            val q = editable?.toString()?.trim()?.lowercase().orEmpty()
            for (sec in sections) {
                if (q.isEmpty()) {
                    sec.container.visibility = View.VISIBLE
                    for (r in sec.rows) r.view.visibility = View.VISIBLE
                } else {
                    var anyVisible = false
                    for (r in sec.rows) {
                        val match = r.keywords.contains(q)
                        r.view.visibility = if (match) View.VISIBLE else View.GONE
                        if (match) anyVisible = true
                    }
                    sec.container.visibility = if (anyVisible) View.VISIBLE else View.GONE
                }
            }
        }

        setContentView(root)
    }

    private fun refreshAgencySwitchStates() {
        updatingAgencySwitches = true
        try {
            val enabled = KokohoSignatures.getEnabledAgencies(this)
            masterDhsSwitch.isChecked = enabled.any { it.isDhs }
            for ((ag, sw) in agencySwitches) {
                sw.isChecked = ag in enabled
            }
        } finally {
            updatingAgencySwitches = false
        }
    }

    private fun syncRuntimeTargets() {
        val targets = KokohoSignatures.watchedTargetsForEnabled(this)
        SdrRadio.config = SdrRadio.config.copy(targets = targets)
        if (ScanForegroundService.isRunning) {
            runCatching { ScanForegroundService.start(this) }
        }
    }

    private fun showAuxnetConfigDialog(subTv: TextView) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val urlInput = EditText(this).apply {
            hint = "Auxnet Hub URL"
            setText(KokohoHubClient.hubUrl(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val contributeCheck = CheckBox(this).apply {
            text = "Contribute detections to trusted Sensor Room"
            isChecked = KokohoHubClient.isSensorContributor(this@SettingsActivity)
        }
        val roomInput = EditText(this).apply {
            hint = "Detection Room ID (e.g. tucson-sector-1)"
            setText(KokohoHubClient.roomId(this@SettingsActivity))
        }
        val keyInput = EditText(this).apply {
            hint = "Room Capability Key (optional)"
            setText(KokohoHubClient.roomKey(this@SettingsActivity))
        }
        layout.addView(urlInput)
        layout.addView(contributeCheck)
        layout.addView(roomInput)
        layout.addView(keyInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("Auxnet Hub & Detection Room")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                KokohoHubClient.setHubUrl(this, urlInput.text.toString())
                KokohoHubClient.setSensorContributor(
                    this,
                    contributeCheck.isChecked,
                    roomInput.text.toString(),
                    keyInput.text.toString()
                )
                subTv.text =
                    "Endpoint: ${KokohoHubClient.hubUrl(this)} · Room contributor: ${if (KokohoHubClient.isSensorContributor(this)) "ON" else "OFF"}"
                if (KokohoSignatures.isAuxnetEnabled(this)) {
                    KokohoHubClient.checkinNow(this)
                }
                Toast.makeText(this, "Auxnet configuration saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChannelImportDialog() {
        val input = EditText(this).apply {
            hint = "Paste RadioReference TSV/CSV lines or SDRTrunk <playlist> XML…"
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = dp(18)
        val container = FrameLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Import Channels (RadioReference / SDRTrunk)")
            .setMessage("Paste RadioReference CSV/TSV frequency tables or SDRTrunk XML playlists below:")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val text = input.text?.toString().orEmpty()
                val count = KokohoSignatures.importChannelText(this, text, "User Import")
                syncRuntimeTargets()
                Toast.makeText(this, "Imported $count channel entries", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
