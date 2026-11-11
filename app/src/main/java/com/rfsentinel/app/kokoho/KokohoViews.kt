package com.rfsentinel.app.kokoho

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.databinding.ActivityMainBinding
import com.rfsentinel.app.sdr.RadioLog
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.AntennaCheckActivity
import com.rfsentinel.app.ui.CellTowersActivity
import com.rfsentinel.app.ui.DetectionLogActivity
import com.rfsentinel.app.ui.GnssActivity
import com.rfsentinel.app.ui.P25Activity
import com.rfsentinel.app.ui.WaterfallActivity
import com.rfsentinel.app.ui.WifiAnalyzerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages Kokoho's 5 top-level activity categories:
 *  1. [Tab.MONITOR] (Default): Full-screen Green/Yellow/Orange/Red posture card ("No threats detected"
 *     when clear; agency classification, estimated distance/bearing, and hover/tap evidence sources when active),
 *     plus the Master DHS radio toggle (CBP, ICE, HSI, AMO, FPS) and optional Police, Sheriff, Fire, SAR toggles.
 *  2. [Tab.MAP]: Full-screen FOSS Map view.
 *  3. [Tab.LIST]: Integrated filterable raw list (including P25 talkgroups, RF channels, and OUI devices).
 *  4. [Tab.UTILITY]: Channel graph / waterfall, P25 talkgroup monitor, WiFi spectrum, Antenna check,
 *     Auxnet Room Link, and RadioReference / SDRTrunk channel importer.
 *  5. [Tab.LOGS]: Dedicated chronological detection & radio log view (relocated out of the map).
 */
class KokohoViews(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onSwitchPanes: (Set<String>) -> Unit,
    private val onFilterChanged: () -> Unit
) {

    enum class Tab(val title: String) {
        MONITOR("Monitor"),
        MAP("Map"),
        LIST("Raw List"),
        UTILITY("Utility"),
        LOGS("Logs")
    }

    var currentTab: Tab = Tab.MONITOR
        private set

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val navBar: MaterialButtonToggleGroup
    private val monitorContainer: ScrollView
    private val postureCard: LinearLayout
    private val postureBadge: TextView
    private val postureHeadline: TextView
    private val postureSubheadline: TextView
    private val postureDistance: TextView
    private val confidencePill: TextView
    private val evidenceBox: LinearLayout
    private val masterDhsButton: MaterialButton
    private val agencyChipViews = LinkedHashMap<KokohoAgency, TextView>()
    private val utilityContainer: ScrollView
    private val logsContainer: ScrollView
    private val logsText: TextView

    private var latestSnapshot: PostureSnapshot? = null

    init {
        KokohoSignatures.ensureLoaded(activity)
        syncSdrTargets()

        // 1. Build 5-category navigation bar at the top of contentArea's parent
        navBar = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }

        val tabIds = LinkedHashMap<Tab, Int>()
        for (tab in Tab.entries) {
            val btnId = View.generateViewId()
            tabIds[tab] = btnId
            val btn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = btnId
                text = tab.title
                textSize = 12f
                minHeight = dp(38)
                minimumHeight = dp(38)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            navBar.addView(btn)
        }

        // Hide legacy 3-icon pane toggle and tools button (relocated to the 5-tab bar and Utility view)
        binding.viewToggle.visibility = View.GONE
        binding.toolsButton.visibility = View.GONE

        val rootLinear = binding.root as LinearLayout
        val statusIdx = rootLinear.indexOfChild(binding.statusText)
        rootLinear.addView(navBar, (statusIdx + 2).coerceAtMost(rootLinear.childCount))

        // 2. Build Default MONITOR View (Full-screen background color posture + DHS/Agency controls)
        postureBadge = TextView(activity).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0x33000000)
            }
        }

        postureHeadline = TextView(activity).apply {
            text = "No threats detected"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(6))
        }

        postureSubheadline = TextView(activity).apply {
            text = "Monitoring: DHS (All: CBP, ICE, HSI, AMO, FPS)"
            textSize = 15f
            setTextColor(0xFFE0E6ED.toInt())
            gravity = Gravity.CENTER
        }

        postureDistance = TextView(activity).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        confidencePill = TextView(activity).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x44000000)
                setStroke(dp(1), 0x66FFFFFF)
            }
            visibility = View.GONE
            setOnClickListener { showEvidenceDialog() }
            setOnHoverListener { _, _ ->
                evidenceBox.visibility = View.VISIBLE
                false
            }
        }

        evidenceBox = LinearLayout(activity).apply {
            orientation = Vertical
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x55000000)
            }
            visibility = View.GONE
        }

        postureCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(290)
            setPadding(dp(20), dp(28), dp(20), dp(28))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(PostureColor.GREEN.colorArgb)
            }
            addView(postureBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(postureHeadline)
            addView(postureSubheadline)
            addView(postureDistance)
            addView(confidencePill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(evidenceBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }

        // Agency Filter Panel (Master DHS + sub-items CBP, ICE, HSI, AMO, FPS + optional Police, Sheriff, Fire, SAR)
        val agencyPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        val dhsHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dhsTitle = TextView(activity).apply {
            text = "Master Radio Filter: DHS"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        masterDhsButton = MaterialButton(activity).apply {
            text = "DHS Master: ON"
            textSize = 12f
            minHeight = dp(36)
            minimumHeight = dp(36)
            setOnClickListener {
                val next = !KokohoSignatures.isMasterDhsEnabled(activity)
                KokohoSignatures.setMasterDhs(activity, next)
                syncSdrTargets()
                refreshAgencyChips()
                onFilterChanged()
            }
        }

        dhsHeaderRow.addView(dhsTitle)
        dhsHeaderRow.addView(masterDhsButton)
        agencyPanel.addView(dhsHeaderRow)

        val dhsChipsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, dp(8))
        }
        val dhsChipsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (ag in KokohoAgency.DHS_AGENCIES) {
            val chip = createAgencyChip(ag)
            agencyChipViews[ag] = chip
            dhsChipsRow.addView(chip)
        }
        dhsChipsScroll.addView(dhsChipsRow)
        agencyPanel.addView(dhsChipsScroll)

        val optTitle = TextView(activity).apply {
            text = "Additional Agencies (Default Ignored / Off):"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }
        agencyPanel.addView(optTitle)

        val optChipsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
        }
        val optChipsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (ag in KokohoAgency.OPTIONAL_AGENCIES) {
            val chip = createAgencyChip(ag)
            agencyChipViews[ag] = chip
            optChipsRow.addView(chip)
        }
        optChipsScroll.addView(optChipsRow)
        agencyPanel.addView(optChipsScroll)

        val monitorInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(16))
            addView(postureCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
            addView(agencyPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        monitorContainer = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(monitorInner)
        }

        // 3. Build UTILITY View
        utilityContainer = buildUtilityView()

        // 4. Build LOGS View
        val logsPair = buildLogsView()
        logsContainer = logsPair.first
        logsText = logsPair.second

        binding.contentArea.addView(monitorContainer)
        binding.contentArea.addView(utilityContainer)
        binding.contentArea.addView(logsContainer)

        refreshAgencyChips()

        navBar.check(tabIds[Tab.MONITOR]!!)
        navBar.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val chosen = tabIds.entries.firstOrNull { it.value == checkedId }?.key ?: Tab.MONITOR
            selectTab(chosen)
        }

        selectTab(Tab.MONITOR)
    }

    private fun createAgencyChip(agency: KokohoAgency): TextView {
        return TextView(activity).apply {
            text = agency.shortName
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                KokohoSignatures.toggleAgency(activity, agency)
                syncSdrTargets()
                refreshAgencyChips()
                onFilterChanged()
            }
        }
    }

    private fun refreshAgencyChips() {
        val enabled = KokohoSignatures.getEnabledAgencies(activity)
        val masterOn = enabled.any { it.isDhs }
        masterDhsButton.text = if (masterOn) "DHS Master: ON" else "DHS Master: OFF"
        masterDhsButton.backgroundTintList = ColorStateList.valueOf(
            if (masterOn) 0xFF2E7D32.toInt() else 0xFF455A64.toInt()
        )
        masterDhsButton.setTextColor(Color.WHITE)

        for ((ag, view) in agencyChipViews) {
            val isOn = ag in enabled
            view.background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(if (isOn) 0xFF2E7D32.toInt() else 0x33455A64)
                setStroke(dp(1), if (isOn) 0xFF81C784.toInt() else 0x55FFFFFF)
            }
            view.setTextColor(if (isOn) Color.WHITE else 0xFFB0BEC5.toInt())
        }
    }

    fun syncSdrTargets() {
        val targets = KokohoSignatures.watchedTargetsForEnabled(activity)
        SdrRadio.config = SdrRadio.config.copy(targets = targets)
    }

    fun selectTab(tab: Tab) {
        currentTab = tab
        when (tab) {
            Tab.MONITOR -> {
                binding.filterChips.parent.let { (it as? View)?.visibility = View.GONE }
                onSwitchPanes(emptySet())
                binding.mapPane.visibility = View.GONE
                binding.radarPane.visibility = View.GONE
                binding.listFrame.visibility = View.GONE
                monitorContainer.visibility = View.VISIBLE
                utilityContainer.visibility = View.GONE
                logsContainer.visibility = View.GONE
            }
            Tab.MAP -> {
                binding.filterChips.parent.let { (it as? View)?.visibility = View.VISIBLE }
                monitorContainer.visibility = View.GONE
                utilityContainer.visibility = View.GONE
                logsContainer.visibility = View.GONE
                onSwitchPanes(setOf("map"))
            }
            Tab.LIST -> {
                binding.filterChips.parent.let { (it as? View)?.visibility = View.VISIBLE }
                monitorContainer.visibility = View.GONE
                utilityContainer.visibility = View.GONE
                logsContainer.visibility = View.GONE
                onSwitchPanes(setOf("list"))
            }
            Tab.UTILITY -> {
                binding.filterChips.parent.let { (it as? View)?.visibility = View.GONE }
                onSwitchPanes(emptySet())
                binding.mapPane.visibility = View.GONE
                binding.radarPane.visibility = View.GONE
                binding.listFrame.visibility = View.GONE
                monitorContainer.visibility = View.GONE
                utilityContainer.visibility = View.VISIBLE
                logsContainer.visibility = View.GONE
            }
            Tab.LOGS -> {
                binding.filterChips.parent.let { (it as? View)?.visibility = View.GONE }
                onSwitchPanes(emptySet())
                binding.mapPane.visibility = View.GONE
                binding.radarPane.visibility = View.GONE
                binding.listFrame.visibility = View.GONE
                monitorContainer.visibility = View.GONE
                utilityContainer.visibility = View.GONE
                logsContainer.visibility = View.VISIBLE
                refreshLogsText()
            }
        }
    }

    fun updatePosture(nowMs: Long = System.currentTimeMillis()) {
        if (ScanForegroundService.isRunning) {
            KokohoHubClient.start(activity)
        }
        val snap = KokohoPostureEngine.evaluate(activity, nowMs)
        latestSnapshot = snap

        (postureCard.background as? GradientDrawable)?.setColor(snap.color.colorArgb)
        postureBadge.text = snap.color.badgeLabel
        postureHeadline.text = snap.headline
        postureSubheadline.text = snap.subheadline
        postureDistance.text = snap.distanceAndBearingText

        val primary = snap.primaryThreat
        if (primary != null) {
            confidencePill.visibility = View.VISIBLE
            confidencePill.text =
                "Confidence ${primary.overallConfidence}% (Class ${primary.classificationConfidence}% / Spatial ${primary.spatialConfidence}%) · Tap for Evidence (${primary.evidenceSources.size})"
            evidenceBox.removeAllViews()
            evidenceBox.visibility = View.VISIBLE
            for (ev in primary.evidenceSources) {
                val tv = TextView(activity).apply {
                    text = "• [${ev.kind}] ${ev.detail}"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(2), 0, dp(2))
                }
                evidenceBox.addView(tv)
            }
        } else {
            confidencePill.visibility = View.GONE
            evidenceBox.visibility = View.GONE
        }

        if (currentTab == Tab.LOGS) {
            refreshLogsText()
        }
    }

    private fun showEvidenceDialog() {
        val primary = latestSnapshot?.primaryThreat ?: return
        val lines = primary.evidenceSources.joinToString("\n\n") { ev ->
            "${ev.kind} (${(ev.weight * 100).toInt()}% weight)\nFacet: ${ev.facetKey}\n${ev.detail}"
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("${primary.label} — Evidence Sources")
            .setMessage(
                "Entity UUID: ${primary.uuid}\n" +
                    "Track UUID: ${primary.trackUuid}\n" +
                    "Overall Confidence: ${primary.overallConfidence}%\n" +
                    "Classification Confidence: ${primary.classificationConfidence}%\n" +
                    "Spatial Confidence: ${primary.spatialConfidence}%\n" +
                    "Recurrence: ${primary.recurrenceSummary}\n\n" +
                    lines
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildUtilityView(): ScrollView {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(20))
        }

        fun addCard(title: String, subtitle: String, onClick: () -> Unit) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x22FFFFFF)
                    setStroke(dp(1), 0x33FFFFFF)
                }
                val outValue = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setOnClickListener { onClick() }
            }
            val t = TextView(activity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            val s = TextView(activity).apply {
                text = subtitle
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(t)
            card.addView(s)
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }

        addCard(
            "Auxnet Hub & Detection Room Link",
            "Configure kokoho.tucsoncommunity.online stream, 5-min background check-in, and trusted Sensor Room triangulation"
        ) { showAuxnetConfigDialog() }

        addCard(
            "Import Channel List (RadioReference / SDRTrunk)",
            "Import RadioReference TSV/CSV frequency tables or SDRTrunk XML playlists into Kokoho"
        ) { showChannelImportDialog() }

        addCard(
            "P25 Trunking & Talkgroup Monitor",
            "Live P25 control channel decode, talkgroups, NACs, and close mobile input checks"
        ) { activity.startActivity(Intent(activity, P25Activity::class.java)) }

        addCard(
            "Radio Waterfall & Channel Spectrum",
            "Live RTL-SDR spectrum waterfall and narrowband frequency hold"
        ) { activity.startActivity(Intent(activity, WaterfallActivity::class.java)) }

        addCard(
            "WiFi Channel Graph & Spectrum",
            "2.4 / 5 / 6 GHz channel occupancy and signal graph"
        ) { activity.startActivity(Intent(activity, WifiAnalyzerActivity::class.java)) }

        addCard(
            "Antenna & NOAA Weather Check",
            "Verify RTL-SDR receive sensitivity against 162.400–162.550 MHz NOAA weather stations"
        ) { activity.startActivity(Intent(activity, AntennaCheckActivity::class.java)) }

        addCard(
            "Cell Towers & GNSS Diagnostics",
            "Inspect nearby cellular base stations and satellite navigation integrity"
        ) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Diagnostics")
                .setItems(arrayOf("Cell Towers", "GNSS Satellites")) { _, which ->
                    if (which == 0) activity.startActivity(Intent(activity, CellTowersActivity::class.java))
                    else activity.startActivity(Intent(activity, GnssActivity::class.java))
                }
                .show()
        }

        return ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            addView(list)
        }
    }

    private fun buildLogsView(): Pair<ScrollView, TextView> {
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(activity).apply {
            text = "Chronological Detection & Telemetry Log"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val fullLogBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Full Archive / Export"
            textSize = 12f
            setOnClickListener {
                activity.startActivity(Intent(activity, DetectionLogActivity::class.java))
            }
        }

        headerRow.addView(title)
        headerRow.addView(fullLogBtn)
        wrapper.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val logTv = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x22000000)
                setStroke(dp(1), 0x33FFFFFF)
            }
            text = "No events logged in current session."
        }
        wrapper.addView(logTv)

        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            addView(wrapper)
        }
        return scroll to logTv
    }

    private fun refreshLogsText() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val lines = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (obj in KokohoHubClient.activeObjects(now)) {
            val t = fmt.format(Date(obj.updatedAtMs))
            lines += "[$t] [AUXNET] [${obj.classification.category}] ${obj.classification.label} (±${obj.location.uncertaintyRadiusM.toInt()}m, UUID ${obj.uuid.take(8)})"
        }
        for (rf in RadioLog.current(now)) {
            val t = fmt.format(Date(rf.lastSeen))
            val sig = KokohoSignatures.matchFrequency(rf.freqHz)
            val tag = sig?.let { "[${it.agency}] ${it.alpha}" } ?: "%.4f MHz".format(Locale.US, rf.freqHz / 1e6)
            lines += "[$t] [RF-SDR] $tag · peak ${rf.peakSnrDb} dB SNR (${rf.trend.name})"
        }
        for (tg in SdrRadio.p25Log.rows().take(25)) {
            val tgId = tg.tg ?: continue
            val t = fmt.format(Date(tg.lastAt))
            val sig = KokohoSignatures.matchTalkgroup(tgId)
            val tag = sig?.let { "[${it.agency}] ${it.label}" } ?: "Unmapped Talkgroup"
            lines += "[$t] [P25-TG] TG $tgId ($tag) · ${tg.calls} calls"
        }

        logsText.text = if (lines.isEmpty()) {
            "No RF, P25, OUI, or Auxnet detections logged yet in this session.\nActive filter scope: ${latestSnapshot?.enabledScopeText ?: "DHS"}"
        } else {
            lines.joinToString("\n")
        }
    }

    private fun showAuxnetConfigDialog() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val urlInput = EditText(activity).apply {
            hint = "Auxnet Hub URL"
            setText(KokohoHubClient.hubUrl(activity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val contributeCheck = CheckBox(activity).apply {
            text = "Contribute detections to trusted Sensor Room"
            isChecked = KokohoHubClient.isSensorContributor(activity)
        }
        val roomInput = EditText(activity).apply {
            hint = "Detection Room ID (e.g. tucson-sector-1)"
            setText(KokohoHubClient.roomId(activity))
        }
        val keyInput = EditText(activity).apply {
            hint = "Room Capability Key (optional)"
            setText(KokohoHubClient.roomKey(activity))
        }
        layout.addView(urlInput)
        layout.addView(contributeCheck)
        layout.addView(roomInput)
        layout.addView(keyInput)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Auxnet Hub & Room Link")
            .setView(layout)
            .setPositiveButton("Save & Check In") { _, _ ->
                KokohoHubClient.setHubUrl(activity, urlInput.text.toString())
                KokohoHubClient.setSensorContributor(
                    activity,
                    contributeCheck.isChecked,
                    roomInput.text.toString(),
                    keyInput.text.toString()
                )
                KokohoHubClient.checkinNow(activity)
                Toast.makeText(activity, "Auxnet Hub configuration saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChannelImportDialog() {
        val input = EditText(activity).apply {
            hint = "Paste RadioReference TSV/CSV lines or SDRTrunk <playlist> XML…"
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = dp(18)
        val container = FrameLayout(activity).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Import Channels (RadioReference / SDRTrunk)")
            .setMessage("Bundled catalog already includes 51 AZ CBP/ICE/AMO channels + PCWIN talkgroups. Paste additional RadioReference CSV/TSV or SDRTrunk XML below:")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val text = input.text?.toString().orEmpty()
                val count = KokohoSignatures.importChannelText(activity, text, "User Import")
                Toast.makeText(activity, "Imported $count channel entries", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val Vertical = LinearLayout.VERTICAL
    }
}
