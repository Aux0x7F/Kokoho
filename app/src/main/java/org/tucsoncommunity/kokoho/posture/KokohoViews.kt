package org.tucsoncommunity.kokoho.posture

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.button.MaterialButton
import org.tucsoncommunity.kokoho.R
import org.tucsoncommunity.kokoho.databinding.ActivityMainBinding
import org.tucsoncommunity.kokoho.sdr.RadioLog
import org.tucsoncommunity.kokoho.sdr.SdrRadio
import org.tucsoncommunity.kokoho.service.ScanForegroundService
import org.tucsoncommunity.kokoho.settings.SettingsActivity
import org.tucsoncommunity.kokoho.ui.AntennaCheckActivity
import org.tucsoncommunity.kokoho.ui.DetectionLogActivity
import org.tucsoncommunity.kokoho.ui.DeviceDetailActivity
import org.tucsoncommunity.kokoho.ui.P25Activity
import org.tucsoncommunity.kokoho.ui.WaterfallActivity
import org.tucsoncommunity.kokoho.util.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages Kokoho's 3 top-level activity categories and full-bleed posture UI:
 *  1. [Tab.MONITOR] (Default): Full-screen background color screen (Green / Yellow / Orange / Red)
 *     displaying each currently detected agency in strict priority order:
 *     ICE -> DHS Air -> CBP -> HSI -> Other Federal -> Sheriff / TPD / Tactical Drone.
 *     Beneath each agency headline is its inline subtext: `x2 · ±650 ft · 1m ago` (and optional `+1 (~0.5 mi)`).
 *     Tapping any agency opens the Distinct Agents List modal for that agency; tapping an agent opens Entity Details.
 *  2. [Tab.MAP]: Full-screen FOSS Map view showing merged radius circles, aircraft tracks, and drone altitude.
 *  3. [Tab.UTILITY]: P25 trunking monitor, radio waterfall, antenna check, Logs, Update App, and Settings.
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
        UTILITY("Utility")
    }

    var currentTab: Tab = Tab.MONITOR
        private set

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val topHeaderBar: LinearLayout
    private val headerLogo: ImageView
    private val headerTitle: TextView
    private val headerSubtitle: TextView
    val shareToggleButton: MaterialButton
    val scanToggleButton: MaterialButton

    private val tabRow: LinearLayout
    private val tabButtons = LinkedHashMap<Tab, TextView>()

    private val monitorContainer: LinearLayout
    private val postureBadge: TextView
    val postureHeadline: TextView
    val postureDistance: TextView
    private val agencyStackContainer: LinearLayout
    val bottomConfidenceBar: TextView

    private val utilityContainer: ScrollView
    private val logsContainer: ScrollView
    private val logsText: TextView

    private var latestSnapshot: PostureSnapshot? = null
    private var runningState: Boolean = ScanForegroundService.isRunning

    init {
        KokohoSignatures.ensureLoaded(activity)
        KokohoSignatures.ensureSharingStartsOff(activity)
        syncSdrTargets()

        // Hide legacy top controls and filter chips so Kokoho has a clean, unified layout
        binding.themedHeader.visibility = View.GONE
        binding.threatBanner.visibility = View.GONE
        binding.bannerClose.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        (binding.startStopButton.parent as? View)?.visibility = View.GONE
        binding.viewToggle.visibility = View.GONE
        binding.toolsButton.visibility = View.GONE
        (binding.filterChips.parent as? View)?.visibility = View.GONE

        val rootLinear = binding.root as LinearLayout
        rootLinear.setPadding(0, 0, 0, 0)

        // 1. Unified Top Header Bar
        headerLogo = ImageView(activity).apply {
            setImageResource(R.drawable.ic_kokoho_logo)
            contentDescription = "Kokoho"
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            }
        }

        headerTitle = TextView(activity).apply {
            text = "KOKOHO"
            textSize = 16f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.06f
            setSingleLine(true)
        }

        headerSubtitle = TextView(activity).apply {
            text = "Standby · Tap Scan to monitor"
            textSize = 11.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(headerTitle)
            addView(headerSubtitle)
        }

        shareToggleButton = MaterialButton(activity).apply {
            minHeight = dp(38)
            minimumHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(999)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                if (!KokohoSignatures.isContributionAllowedInSettings(activity)) {
                    Toast.makeText(
                        activity,
                        "Contribution is disabled. Enable it in Settings > Privacy to share observations.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val next = !KokohoSignatures.isSharingActive(activity)
                    KokohoSignatures.setSharingActive(activity, next)
                    syncSharePillState()
                    val msg = if (next) {
                        "Sharing ON (derived locations only)"
                    } else {
                        "Sharing OFF (local-only mode)"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        scanToggleButton = MaterialButton(activity).apply {
            minHeight = dp(38)
            minimumHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(999)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), 0, dp(16), 0)
            iconSize = dp(16)
            iconPadding = dp(6)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener {
                binding.startStopButton.performClick()
            }
        }

        topHeaderBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            addView(headerLogo)
            addView(titleCol)
            addView(shareToggleButton)
            addView(scanToggleButton)
        }

        // 2. 3-Category Segmented Tab Bar (Monitor | Map | Utility)
        tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }

        for (tab in Tab.entries) {
            val tv = TextView(activity).apply {
                text = tab.title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener { selectTab(tab) }
            }
            tabButtons[tab] = tv
            tabRow.addView(tv)
        }

        rootLinear.addView(topHeaderBar, 0)
        rootLinear.addView(tabRow, 1)

        // 3. Build Full-Screen MONITOR View:
        //    - Status pill ("PERIPHERY PRESENCE", "HEADING THIS WAY", "IMMINENT PROXIMITY")
        //    - Stacked agency blocks in strict priority rank order (ICE, DHS Air, CBP, HSI, Other Fed, Sheriff/TPD/Drone)
        //    - Inline subtext below each agency: `x2 · ±650 ft · 1m ago`
        //    - Tapping an agency opens the Distinct Agents List modal
        postureBadge = TextView(activity).apply {
            text = PostureColor.GREEN.badgeLabel
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0x38000000)
                setStroke(dp(1), 0x44FFFFFF)
            }
            visibility = View.GONE
        }

        postureHeadline = TextView(activity).apply {
            text = "No threats detected"
            textSize = 36f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }

        postureDistance = TextView(activity).apply {
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xEEFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(2), dp(20), dp(10))
            visibility = View.GONE
        }

        agencyStackContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        val heroInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(postureBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
            addView(postureHeadline)
            addView(postureDistance)
            addView(agencyStackContainer)
        }

        val heroScroll = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(
                FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addView(
                        heroInner,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    )
                }
            )
        }

        bottomConfidenceBar = TextView(activity).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0x3D000000)
                setStroke(dp(1), 0x55FFFFFF)
            }
            visibility = View.GONE
            setOnClickListener {
                val primaryGroup = latestSnapshot?.monitorGroups?.firstOrNull()
                if (primaryGroup != null) {
                    showDistinctAgentsDialog(primaryGroup, System.currentTimeMillis())
                } else {
                    val primary = latestSnapshot?.primaryThreat ?: return@setOnClickListener
                    activity.startActivity(
                        Intent(activity, DeviceDetailActivity::class.java)
                            .putExtra(DeviceDetailActivity.EXTRA_MAC, "kokoho:${primary.uuid}")
                    )
                }
            }
        }

        val bottomFooterWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(6), dp(16), dp(22))
            addView(
                bottomConfidenceBar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        monitorContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(heroScroll)
            addView(bottomFooterWrapper)
        }

        // 4. Build UTILITY View
        utilityContainer = buildUtilityView()

        // 5. Build LOGS View
        val logsPair = buildLogsView()
        logsContainer = logsPair.first
        logsText = logsPair.second

        binding.contentArea.addView(monitorContainer)
        binding.contentArea.addView(utilityContainer)
        binding.contentArea.addView(logsContainer)

        syncRunningState(ScanForegroundService.isRunning)
        selectTab(Tab.MONITOR)
    }

    fun syncSharePillState() {
        val allowedInSettings = KokohoSignatures.isContributionAllowedInSettings(activity)
        val sharingNow = KokohoSignatures.isSharingActive(activity)
        when {
            !allowedInSettings -> {
                shareToggleButton.text = "Share Off"
                shareToggleButton.backgroundTintList = ColorStateList.valueOf(0x33455A64)
                shareToggleButton.strokeColor = ColorStateList.valueOf(0x44FFFFFF)
                shareToggleButton.strokeWidth = dp(1)
                shareToggleButton.setTextColor(0x99FFFFFF.toInt())
                shareToggleButton.alpha = 0.50f
                TooltipCompat.setTooltipText(
                    shareToggleButton,
                    "Contribution disabled — enable in Settings > Privacy"
                )
            }
            sharingNow -> {
                shareToggleButton.text = "Sharing"
                shareToggleButton.backgroundTintList = ColorStateList.valueOf(0xFF00796B.toInt())
                shareToggleButton.strokeColor = ColorStateList.valueOf(0xCCFFFFFF.toInt())
                shareToggleButton.strokeWidth = dp(1)
                shareToggleButton.setTextColor(Color.WHITE)
                shareToggleButton.alpha = 1.0f
                TooltipCompat.setTooltipText(
                    shareToggleButton,
                    "Tap to pause sharing observations"
                )
            }
            else -> {
                shareToggleButton.text = "Share"
                shareToggleButton.backgroundTintList = ColorStateList.valueOf(0x44000000)
                shareToggleButton.strokeColor = ColorStateList.valueOf(0x88FFFFFF.toInt())
                shareToggleButton.strokeWidth = dp(1)
                shareToggleButton.setTextColor(Color.WHITE)
                shareToggleButton.alpha = 0.90f
                TooltipCompat.setTooltipText(
                    shareToggleButton,
                    "Tap to share derived observations"
                )
            }
        }
    }

    fun syncRunningState(running: Boolean) {
        runningState = running
        binding.threatBanner.visibility = View.GONE
        binding.bannerClose.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        (binding.startStopButton.parent as? View)?.visibility = View.GONE
        (binding.filterChips.parent as? View)?.visibility = View.GONE
        syncSharePillState()

        val auxStatus = if (KokohoSignatures.isAuxnetEnabled(activity)) {
            "Auxnet ${KokohoHubClient.statusLabel}"
        } else {
            "Offline mode"
        }

        if (running) {
            scanToggleButton.text = "Stop"
            scanToggleButton.setIconResource(R.drawable.ic_car_stop)
            scanToggleButton.backgroundTintList = ColorStateList.valueOf(0xFFC62828.toInt())
            scanToggleButton.strokeColor = ColorStateList.valueOf(0x88FFFFFF.toInt())
            scanToggleButton.strokeWidth = dp(1)
            scanToggleButton.setTextColor(Color.WHITE)
            scanToggleButton.iconTint = ColorStateList.valueOf(Color.WHITE)
            headerSubtitle.text = "Scanning · $auxStatus"
        } else {
            scanToggleButton.text = "Scan"
            scanToggleButton.setIconResource(R.drawable.ic_car_play)
            scanToggleButton.backgroundTintList = ColorStateList.valueOf(0xFF2E7D32.toInt())
            scanToggleButton.strokeColor = ColorStateList.valueOf(0x88FFFFFF.toInt())
            scanToggleButton.strokeWidth = dp(1)
            scanToggleButton.setTextColor(Color.WHITE)
            scanToggleButton.iconTint = ColorStateList.valueOf(Color.WHITE)
            headerSubtitle.text = "Standby · Tap Scan to monitor"
        }
        updatePosture()
    }

    fun syncSdrTargets() {
        val targets = KokohoSignatures.watchedTargetsForEnabled(activity)
        SdrRadio.config = SdrRadio.config.copy(targets = targets)
    }

    fun selectTab(tab: Tab) {
        currentTab = tab
        (binding.filterChips.parent as? View)?.visibility = View.GONE

        when (tab) {
            Tab.MONITOR -> {
                onSwitchPanes(emptySet())
                binding.mapPane.visibility = View.GONE
                binding.radarPane.visibility = View.GONE
                binding.listFrame.visibility = View.GONE
                monitorContainer.visibility = View.VISIBLE
                utilityContainer.visibility = View.GONE
                logsContainer.visibility = View.GONE
            }
            Tab.MAP -> {
                monitorContainer.visibility = View.GONE
                utilityContainer.visibility = View.GONE
                logsContainer.visibility = View.GONE
                onSwitchPanes(setOf("map"))
            }
            Tab.UTILITY -> {
                onSwitchPanes(emptySet())
                binding.mapPane.visibility = View.GONE
                binding.radarPane.visibility = View.GONE
                binding.listFrame.visibility = View.GONE
                monitorContainer.visibility = View.GONE
                utilityContainer.visibility = View.VISIBLE
                logsContainer.visibility = View.GONE
            }
        }
        applySurfaceThemeColors()
    }

    private fun applySurfaceThemeColors() {
        val snapColor = (latestSnapshot?.color ?: PostureColor.GREEN).colorArgb

        binding.root.setBackgroundColor(snapColor)
        monitorContainer.setBackgroundColor(snapColor)
        utilityContainer.setBackgroundColor(snapColor)
        logsContainer.setBackgroundColor(snapColor)
        binding.listFrame.setBackgroundColor(snapColor)
        headerLogo.imageTintList = ColorStateList.valueOf(Color.WHITE)
        headerTitle.setTextColor(Color.WHITE)
        headerSubtitle.setTextColor(0xDDFFFFFF.toInt())

        for ((t, btn) in tabButtons) {
            val selected = t == currentTab
            btn.background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (selected) {
                    setColor(Color.WHITE)
                } else {
                    setColor(0x33000000)
                    setStroke(dp(1), 0x44FFFFFF)
                }
            }
            btn.setTextColor(if (selected) snapColor else Color.WHITE)
        }
    }

    fun updatePosture(nowMs: Long = System.currentTimeMillis()) {
        syncSharePillState()
        if (ScanForegroundService.isRunning && KokohoSignatures.isAuxnetEnabled(activity)) {
            KokohoHubClient.start(activity)
        }
        val snap = KokohoPostureEngine.evaluate(activity, nowMs)
        latestSnapshot = snap

        postureHeadline.text = snap.headline
        val groups = snap.monitorGroups

        if (groups.isNotEmpty() && snap.primaryThreat != null) {
            postureBadge.text = snap.color.badgeLabel
            postureBadge.visibility = View.VISIBLE

            // Keep postureHeadline / postureDistance populated for the lead agency and render
            // all active agencies in strict priority rank order (ICE, DHS Air, CBP, HSI, Other Fed, Sheriff/TPD/Drone).
            val leadGroup = groups.first()
            postureHeadline.text = leadGroup.agency.friendlyLabel
            postureHeadline.visibility = View.VISIBLE
            postureHeadline.setOnClickListener {
                showDistinctAgentsDialog(leadGroup, nowMs)
            }

            postureDistance.text = leadGroup.formatMonitorSubtext(activity, nowMs)
            postureDistance.visibility = View.VISIBLE
            postureDistance.setOnClickListener {
                showDistinctAgentsDialog(leadGroup, nowMs)
            }

            agencyStackContainer.removeAllViews()
            if (groups.size > 1) {
                agencyStackContainer.visibility = View.VISIBLE
                for (g in groups.drop(1)) {
                    val block = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(16), dp(12), dp(16), dp(8))
                        setOnClickListener {
                            showDistinctAgentsDialog(g, nowMs)
                        }
                    }
                    val titleTv = TextView(activity).apply {
                        text = g.agency.friendlyLabel
                        textSize = 32f
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
                    val subTv = TextView(activity).apply {
                        text = g.formatMonitorSubtext(activity, nowMs)
                        textSize = 15.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(0xEEFFFFFF.toInt())
                        gravity = Gravity.CENTER
                        setPadding(0, dp(2), 0, 0)
                    }
                    block.addView(titleTv)
                    block.addView(subTv)
                    agencyStackContainer.addView(block)
                }
            } else {
                agencyStackContainer.visibility = View.GONE
            }

            bottomConfidenceBar.text = snap.bottomConfidenceText
            bottomConfidenceBar.visibility = View.VISIBLE
        } else {
            postureBadge.text = ""
            postureBadge.visibility = View.GONE
            postureHeadline.text = "No threats detected"
            postureHeadline.visibility = View.VISIBLE
            postureHeadline.setOnClickListener(null)
            postureDistance.text = ""
            postureDistance.visibility = View.GONE
            agencyStackContainer.removeAllViews()
            agencyStackContainer.visibility = View.GONE
            bottomConfidenceBar.text = ""
            bottomConfidenceBar.visibility = View.GONE
        }

        applySurfaceThemeColors()
    }

    /**
     * Opens the Distinct Agents List modal for a tapped [AgencyMonitorGroup] (#25):
     * Shows each distinct agent/unit with its photo or deterministic Gravatar/Identicon avatar,
     * verified Name/Badge/Vehicle or RID/OUI/UAS ID/ICAO Hex, accuracy radius, and staleness time.
     * Tapping an agent opens [DeviceDetailActivity].
     */
    fun showDistinctAgentsDialog(group: AgencyMonitorGroup, nowMs: Long = System.currentTimeMillis()) {
        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF121822.toInt())
        }
        scroll.addView(container)

        val headerTv = TextView(activity).apply {
            text = "${group.agency.friendlyLabel} — ${group.totalDistinctCount} Distinct ${if (group.totalDistinctCount == 1) "Agent / Unit" else "Agents / Units"}"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        container.addView(headerTv)

        var dialogRef: AlertDialog? = null

        for ((idx, unit) in group.distinctUnits.withIndex()) {
            val witness = KokohoSignatures.getWitnessReport(activity, unit.uuid)
            val customLabel = KokohoSignatures.getCustomEntityLabel(activity, unit.uuid)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFF1E2634.toInt())
                    setStroke(dp(1), 0x44FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    dialogRef?.dismiss()
                    activity.startActivity(
                        Intent(activity, DeviceDetailActivity::class.java)
                            .putExtra(DeviceDetailActivity.EXTRA_MAC, "kokoho:${unit.uuid}")
                    )
                }
            }

            val avatarView = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(12)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(renderAgentAvatarBitmap(activity, unit.uuid, witness?.photoDataUrl, dp(44)))
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val primaryTitle = when {
                witness != null && (witness.agentName.isNotBlank() || witness.badgeNumber.isNotBlank()) -> {
                    listOfNotNull(
                        witness.agentName.takeIf { it.isNotBlank() },
                        witness.badgeNumber.takeIf { it.isNotBlank() }?.let { "Badge #$it" },
                        witness.vehicleNumber.takeIf { it.isNotBlank() }?.let { "Veh $it" }
                    ).joinToString(" · ")
                }
                !customLabel.isNullOrBlank() -> customLabel
                else -> "${group.agency.friendlyLabel} Unit #${idx + 1}"
            }

            val hwLine = unit.hardwareIdentifier
                ?: unit.evidenceSources.firstOrNull()?.facetKey
                ?: unit.uuid.take(8)

            val accStr = KokohoPostureEngine.formatAccuracyOrEdge(activity, unit)
            val ageStr = KokohoPostureEngine.formatStalenessAge(nowMs - unit.updatedAtMs)
            val altExtra = if (unit.altitudeFtAgl != null) " · ${unit.altitudeFtAgl} ft AGL" else ""

            val nameTv = TextView(activity).apply {
                text = primaryTitle
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val idTv = TextView(activity).apply {
                text = hwLine
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF93C5FD.toInt())
                setPadding(0, dp(2), 0, 0)
            }
            val metaTv = TextView(activity).apply {
                text = "$accStr$altExtra · $ageStr · ${unit.combinedConfidence}% conf"
                textSize = 12.5f
                setTextColor(0xDDFFFFFF.toInt())
                setPadding(0, dp(2), 0, 0)
            }

            textCol.addView(nameTv)
            textCol.addView(idTv)
            textCol.addView(metaTv)

            row.addView(avatarView)
            row.addView(textCol)
            container.addView(row)
        }

        dialogRef = AlertDialog.Builder(activity)
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialogRef.show()
    }

    private fun buildUtilityView(): ScrollView {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(20))
        }

        fun addCard(title: String, subtitle: String, onClick: () -> Unit) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x38000000)
                    setStroke(dp(1), 0x55FFFFFF)
                }
                val outValue = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setOnClickListener { onClick() }
            }
            val t = TextView(activity).apply {
                text = title
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val s = TextView(activity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(0xDDFFFFFF.toInt())
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(t)
            card.addView(s)
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }

        addCard(
            "P25 Trunking & Talkgroup Monitor",
            "Live P25 control channel decode, talkgroups, NACs, and close mobile input checks"
        ) { activity.startActivity(Intent(activity, P25Activity::class.java)) }

        addCard(
            "Radio Waterfall & Channel Spectrum",
            "Live RTL-SDR spectrum waterfall and narrowband frequency hold"
        ) { activity.startActivity(Intent(activity, WaterfallActivity::class.java)) }

        addCard(
            "Antenna & NOAA Weather Check",
            "Verify RTL-SDR receive sensitivity against 162.400–162.550 MHz NOAA weather stations"
        ) { activity.startActivity(Intent(activity, AntennaCheckActivity::class.java)) }

        addCard(
            "Logs",
            "Chronological detection & telemetry log with archive export"
        ) { activity.startActivity(Intent(activity, DetectionLogActivity::class.java)) }

        val updateCardTitle = TextView(activity).apply {
            text = "Update App"
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val updateCardSubtitle = TextView(activity).apply {
            text = "Download and install the latest Kokoho release"
            textSize = 13f
            setTextColor(0xDDFFFFFF.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        val updateCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x44000000)
                setStroke(dp(1), Color.WHITE)
            }
            addView(updateCardTitle)
            addView(updateCardSubtitle)
            visibility = View.GONE
            setOnClickListener {
                val rel = UpdateChecker.availableUpdate
                if (rel != null) {
                    UpdateChecker.installNow(activity, rel)
                } else {
                    UpdateChecker.check(activity, silent = false)
                }
            }
        }
        list.addView(updateCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        UpdateChecker.addListener { rel ->
            if (rel != null) {
                updateCardTitle.text = "Update App (v${rel.version})"
                updateCardSubtitle.text = "Kokoho ${rel.version} is available — tap to install"
                updateCard.visibility = View.VISIBLE
            } else {
                updateCard.visibility = View.GONE
            }
        }

        addCard(
            "Settings",
            "Agencies (DHS, ICE, DHS Air, CBP, HSI, Sheriff, TPD, Drones), ADS-B aircraft, map fade, privacy, crash log export, and units"
        ) { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }

        return ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            addView(list)
        }
    }

    private fun buildLogsView(): Pair<ScrollView, TextView> {
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(16))
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
            text = "Archive / Export"
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
                setStroke(dp(1), 0x4490A4AE)
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

    companion object {
        /**
         * Renders either the Witness Report uploaded photo (if present) or a deterministic
         * 5x5 symmetric Gravatar/Identicon avatar for `entityUuid`.
         */
        fun renderAgentAvatarBitmap(
            context: Context,
            entityUuid: String,
            photoDataUrl: String?,
            sizePx: Int
        ): Bitmap {
            if (!photoDataUrl.isNullOrBlank()) {
                runCatching {
                    val base64 = photoDataUrl.substringAfter("base64,", photoDataUrl)
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) {
                        return Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
                    }
                }
            }
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val hash = entityUuid.hashCode()
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0F172A.toInt()
            }
            val r = sizePx / 8f
            canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), r, r, bgPaint)

            val hue = ((hash and 0x7FFFFFFF) % 360).toFloat()
            val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.92f))
            }
            val pad = sizePx * 0.14f
            val cell = (sizePx - pad * 2f) / 5f
            var bitIdx = 0
            for (row in 0 until 5) {
                for (col in 0..2) {
                    val on = ((hash ushr (bitIdx % 28)) and 1) == 1
                    bitIdx++
                    if (on) {
                        val x1 = pad + col * cell
                        val y1 = pad + row * cell
                        canvas.drawRect(x1, y1, x1 + cell, y1 + cell, fgPaint)
                        val mirrorCol = 4 - col
                        if (mirrorCol != col) {
                            val mx1 = pad + mirrorCol * cell
                            canvas.drawRect(mx1, y1, mx1 + cell, y1 + cell, fgPaint)
                        }
                    }
                }
            }
            return bmp
        }
    }
}
