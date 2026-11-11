package org.tucsoncommunity.kokoho.posture

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 *     displaying the status pill ("PERIPHERY PRESENCE", "HEADING THIS WAY", "IMMINENT PROXIMITY", hidden when clear),
 *     the concise friendly label ("No threats detected" or "CBP", "ICE", "DHS Air", "Federal Interop", etc.),
 *     the estimated distance ("Distance: ~1.2 mi", default imperial, no bearing), and the combined
 *     confidence + evidence count pinned at the bottom of the screen (clicking opens Entity Details).
 *  2. [Tab.MAP]: Full-screen FOSS Map view showing radius circles for flagged signals (stable status background color).
 *  3. [Tab.UTILITY]: P25 trunking monitor, radio waterfall, antenna check, Logs, Update App (when available), and Settings (last item).
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
    val scanToggleButton: MaterialButton

    private val tabRow: LinearLayout
    private val tabButtons = LinkedHashMap<Tab, TextView>()

    private val monitorContainer: LinearLayout
    private val postureBadge: TextView
    private val postureHeadline: TextView
    private val postureDistance: TextView
    private val bottomConfidenceBar: TextView

    private val utilityContainer: ScrollView
    private val logsContainer: ScrollView
    private val logsText: TextView

    private var latestSnapshot: PostureSnapshot? = null
    private var runningState: Boolean = ScanForegroundService.isRunning

    init {
        KokohoSignatures.ensureLoaded(activity)
        syncSdrTargets()

        // Hide legacy Kokoho top controls and filter chips so Kokoho has a clean, unified layout
        binding.themedHeader.visibility = View.GONE
        binding.threatBanner.visibility = View.GONE
        binding.bannerClose.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        (binding.startStopButton.parent as? View)?.visibility = View.GONE
        binding.viewToggle.visibility = View.GONE
        binding.toolsButton.visibility = View.GONE
        (binding.filterChips.parent as? View)?.visibility = View.GONE

        // Remove tight side paddings on root so full-screen background color is edge-to-edge
        val rootLinear = binding.root as LinearLayout
        rootLinear.setPadding(0, 0, 0, 0)

        // 1. Unified Top Header Bar (Theme-aware Kokoho icon + Title/Status + Scan/Stop Toggle Button)
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
            addView(scanToggleButton)
        }

        // 2. Clean 5-Category Segmented Tab Bar (Monitor | Map | List | Utility | Logs)
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
        //    - No repeated logo in the body
        //    - Status pill ("CLEAR", "PERIPHERY PRESENCE", "HEADING THIS WAY", "IMMINENT PROXIMITY")
        //    - Friendly title ("No threats detected" or "CBP", "ICE", "Federal Interop", etc.)
        //    - Distance line ("Distance: ~1.2 mi", hidden when CLEAR)
        //    - Bottom combined confidence & evidence count ("82% confidence · 2 signals", hidden when CLEAR)
        postureBadge = TextView(activity).apply {
            text = PostureColor.GREEN.badgeLabel
            textSize = 12.5f
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
        }

        postureHeadline = TextView(activity).apply {
            text = "No threats detected"
            textSize = 38f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        postureDistance = TextView(activity).apply {
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xF2FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(4), dp(20), dp(4))
            visibility = View.GONE
        }

        val heroZone = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(postureBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(postureHeadline)
            addView(postureDistance)
        }

        bottomConfidenceBar = TextView(activity).apply {
            textSize = 14f
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
                val primary = latestSnapshot?.primaryThreat ?: return@setOnClickListener
                activity.startActivity(
                    Intent(activity, DeviceDetailActivity::class.java)
                        .putExtra(DeviceDetailActivity.EXTRA_MAC, "kokoho:${primary.uuid}")
                )
            }
        }

        val bottomFooterWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(8), dp(16), dp(24))
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
            addView(heroZone)
            addView(bottomFooterWrapper)
        }

        // 4. Build UTILITY View (with Settings as the last item)
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

    fun syncRunningState(running: Boolean) {
        runningState = running
        // Hide legacy Kokoho status bars & filter chips that MainActivity might re-show
        binding.threatBanner.visibility = View.GONE
        binding.bannerClose.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        (binding.startStopButton.parent as? View)?.visibility = View.GONE
        (binding.filterChips.parent as? View)?.visibility = View.GONE

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
        // Never show legacy filter chips in any tab
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

        // Stable status color background across Monitor, Map, List, and Utility
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
        if (ScanForegroundService.isRunning && KokohoSignatures.isAuxnetEnabled(activity)) {
            KokohoHubClient.start(activity)
        }
        val snap = KokohoPostureEngine.evaluate(activity, nowMs)
        latestSnapshot = snap

        postureHeadline.text = snap.headline

        val primary = snap.primaryThreat
        if (primary != null) {
            postureBadge.text = snap.color.badgeLabel
            postureBadge.visibility = View.VISIBLE
            postureDistance.text = snap.distanceAndBearingText
            postureDistance.visibility = View.VISIBLE
            bottomConfidenceBar.text = snap.bottomConfidenceText
            bottomConfidenceBar.visibility = View.VISIBLE
        } else {
            postureBadge.text = ""
            postureBadge.visibility = View.GONE
            postureDistance.text = ""
            postureDistance.visibility = View.GONE
            bottomConfidenceBar.text = ""
            bottomConfidenceBar.visibility = View.GONE
        }

        applySurfaceThemeColors()
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

        // Dynamic "Update App" item — visible in Utility whenever an update is available
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
            "Agencies (DHS, ICE, CBP, Police, Sheriff, SAR, Fire), ADS-B aircraft, decay time, privacy, channel import, and units"
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

    private fun refreshLogsText() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val lines = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val snap = latestSnapshot ?: KokohoPostureEngine.evaluate(activity, now)

        for (threat in snap.activeThreats) {
            val t = fmt.format(Date(threat.updatedAtMs))
            val dist = KokohoPostureEngine.formatShortDistance(activity, threat.estimatedDistanceM)
            val mode = if (threat.isApproximatedPosition) "triangulated" else "radius"
            lines += "[$t] [${threat.friendlyLabel}] $dist · ${threat.combinedConfidence}% conf (${threat.evidenceCount} signals, $mode)"
        }
        for (rf in RadioLog.current(now)) {
            val t = fmt.format(Date(rf.lastSeen))
            val sig = KokohoSignatures.matchFrequency(rf.freqHz)
            val tag = sig?.friendlyLabel ?: "%.4f MHz".format(Locale.US, rf.freqHz / 1e6)
            lines += "[$t] [RF] $tag · peak ${rf.peakSnrDb} dB SNR"
        }
        for (tg in SdrRadio.p25Log.rows().take(25)) {
            val tgId = tg.tg ?: continue
            val t = fmt.format(Date(tg.lastAt))
            val sig = KokohoSignatures.matchTalkgroup(tgId)
            val tag = sig?.friendlyLabel ?: "TG $tgId"
            lines += "[$t] [P25] $tag (TG $tgId) · ${tg.calls} calls"
        }

        logsText.text = if (lines.isEmpty()) {
            "No active detections in current window.\nMonitoring: ${snap.enabledScopeText}"
        } else {
            lines.joinToString("\n")
        }
    }
}
