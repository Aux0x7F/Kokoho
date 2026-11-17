package org.tucsoncommunity.kokoho.posture

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
        MAP("Map")
    }

    var currentTab: Tab = Tab.MONITOR
        private set

    enum class SharingIndicatorMode {
        PULSING_RED_DOT,
        PAUSED_BARS,
        DISABLED_BARS
    }

    private class SharingIndicatorDrawable(private val density: Float) : Drawable() {
        var mode: SharingIndicatorMode = SharingIndicatorMode.PAUSED_BARS
        var pulseFraction: Float = 1.0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barRect = RectF()
        private val sizePx = (15f * density).toInt().coerceAtLeast(12)

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val half = minOf(b.width(), b.height()) / 2f
            when (mode) {
                SharingIndicatorMode.PULSING_RED_DOT -> {
                    val f = pulseFraction.coerceIn(0f, 1f)
                    // Soft breathing red halo
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb((32 + (110 * f).toInt()).coerceIn(0, 255), 0xFF, 0x17, 0x44)
                    canvas.drawCircle(cx, cy, half * (0.68f + 0.30f * f), paint)
                    // Crisp bright red core dot
                    paint.color = Color.argb((95 + (160 * f).toInt()).coerceIn(0, 255), 0xFF, 0x2B, 0x4E)
                    canvas.drawCircle(cx, cy, half * 0.54f, paint)
                }
                SharingIndicatorMode.PAUSED_BARS, SharingIndicatorMode.DISABLED_BARS -> {
                    paint.style = Paint.Style.FILL
                    paint.color = if (mode == SharingIndicatorMode.PAUSED_BARS) {
                        0xEEFFFFFF.toInt()
                    } else {
                        0x88FFFFFF.toInt()
                    }
                    val barW = (half * 0.34f).coerceAtLeast(1.8f)
                    val barH = half * 1.18f
                    val gap = (half * 0.24f).coerceAtLeast(1.5f)
                    val r = barW / 2f
                    barRect.set(cx - gap - barW, cy - barH / 2f, cx - gap, cy + barH / 2f)
                    canvas.drawRoundRect(barRect, r, r, paint)
                    barRect.set(cx + gap, cy - barH / 2f, cx + gap + barW, cy + barH / 2f)
                    canvas.drawRoundRect(barRect, r, r, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private val sharingIndicatorDrawable = SharingIndicatorDrawable(density)
    private var sharePulseAnimator: ValueAnimator? = null

    val isSharingPulsingRed: Boolean
        get() = sharingIndicatorDrawable.mode == SharingIndicatorMode.PULSING_RED_DOT &&
            (sharePulseAnimator?.isStarted == true)

    val isSharingShowingPause: Boolean
        get() = sharingIndicatorDrawable.mode == SharingIndicatorMode.PAUSED_BARS ||
            sharingIndicatorDrawable.mode == SharingIndicatorMode.DISABLED_BARS

    private val topHeaderBar: LinearLayout
    private val headerLogo: ImageView
    private val headerTitle: TextView
    private val headerSubtitle: TextView
    val shareToggleButton: MaterialButton
    val menuButton: MaterialButton

    private val tabRow: LinearLayout
    private val tabButtons = LinkedHashMap<Tab, TextView>()

    private val monitorContainer: LinearLayout
    private val bluetoothNoticePill: MaterialButton
    private val postureBadge: TextView
    val postureHeadline: TextView
    val postureDistance: TextView
    private val agencyStackContainer: LinearLayout
    val bottomConfidenceBar: TextView

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
            setPadding(dp(11), 0, dp(13), 0)
            icon = sharingIndicatorDrawable
            iconTint = null
            iconSize = dp(15)
            iconPadding = dp(6)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    syncSharePillState()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    stopSharePulse()
                }
            })
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
                        "Sharing ON"
                    } else {
                        "Sharing paused (local-only mode)"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        menuButton = MaterialButton(activity).apply {
            minHeight = dp(38)
            minimumHeight = dp(38)
            minWidth = dp(42)
            minimumWidth = dp(42)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(999)
            text = ""
            contentDescription = "Menu"
            setPadding(dp(11), 0, dp(11), 0)
            icon = org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.VectorIconDrawable(
                activity,
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.VectorIconKind.SANDWICH_MENU
            )
            iconTint = null
            iconSize = dp(20)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            backgroundTintList = ColorStateList.valueOf(0x7712161F)
            strokeColor = ColorStateList.valueOf(0x99FFFFFF.toInt())
            strokeWidth = dp(1)
            setOnClickListener {
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.showRootSandwichMenu(activity) {
                    syncSdrTargets()
                    updatePosture()
                    onFilterChanged()
                }
            }
        }

        topHeaderBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            addView(headerLogo)
            addView(titleCol)
            addView(shareToggleButton)
            addView(menuButton)
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

        bluetoothNoticePill = MaterialButton(activity).apply {
            text = "Turn On Bluetooth for Bodycam & Cruiser BLE"
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = dp(999)
            backgroundTintList = ColorStateList.valueOf(org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.HIGHLIGHT_AMBER)
            setTextColor(org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.SURFACE_DARK)
            strokeColor = ColorStateList.valueOf(Color.WHITE)
            strokeWidth = dp(1)
            visibility = View.GONE
            setOnClickListener {
                runCatching {
                    activity.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.onFailure {
                    runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            }
        }

        monitorContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                FrameLayout(activity).apply {
                    setPadding(dp(16), dp(6), dp(16), 0)
                    addView(
                        bluetoothNoticePill,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER_HORIZONTAL
                        )
                    )
                }
            )
            addView(heroScroll)
            addView(bottomFooterWrapper)
        }

        binding.contentArea.addView(monitorContainer)

        syncRunningState(ScanForegroundService.isRunning)
        selectTab(Tab.MONITOR)
    }

    private fun startSharePulse() {
        if (sharePulseAnimator?.isStarted == true) return
        sharePulseAnimator = ValueAnimator.ofFloat(0.18f, 1.0f).apply {
            duration = 1_150L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                sharingIndicatorDrawable.pulseFraction = (anim.animatedValue as? Float) ?: 1.0f
                sharingIndicatorDrawable.invalidateSelf()
                shareToggleButton.invalidate()
            }
            start()
        }
    }

    private fun stopSharePulse() {
        sharePulseAnimator?.cancel()
        sharePulseAnimator = null
    }

    private var lastShareVisualKey = ""

    fun syncSharePillState() {
        val allowedInSettings = KokohoSignatures.isContributionAllowedInSettings(activity)
        val sharingNow = KokohoSignatures.isSharingActive(activity)
        val shareKey = "$allowedInSettings|$sharingNow|$runningState"
        if (shareKey == lastShareVisualKey) return
        lastShareVisualKey = shareKey
        when {
            !allowedInSettings -> {
                stopSharePulse()
                sharingIndicatorDrawable.mode = SharingIndicatorMode.DISABLED_BARS
                sharingIndicatorDrawable.invalidateSelf()
                shareToggleButton.text = "Share"
                shareToggleButton.backgroundTintList = ColorStateList.valueOf(0x33455A64)
                shareToggleButton.strokeColor = ColorStateList.valueOf(0x55FFFFFF)
                shareToggleButton.strokeWidth = dp(1)
                shareToggleButton.setTextColor(0xAAFFFFFF.toInt())
                shareToggleButton.alpha = 0.55f
                shareToggleButton.invalidate()
                TooltipCompat.setTooltipText(
                    shareToggleButton,
                    "Contribution disabled — enable in Settings > Privacy"
                )
            }
            sharingNow -> {
                sharingIndicatorDrawable.mode = SharingIndicatorMode.PULSING_RED_DOT
                startSharePulse()
                sharingIndicatorDrawable.invalidateSelf()
                shareToggleButton.text = "Stop"
                shareToggleButton.backgroundTintList = ColorStateList.valueOf(0xD912161F.toInt())
                shareToggleButton.strokeColor = ColorStateList.valueOf(0xFFFF6B6B.toInt())
                shareToggleButton.strokeWidth = dp(1)
                shareToggleButton.setTextColor(Color.WHITE)
                shareToggleButton.alpha = 1.0f
                shareToggleButton.invalidate()
                TooltipCompat.setTooltipText(
                    shareToggleButton,
                    "Sharing active — tap Stop to pause sharing"
                )
            }
            else -> {
                stopSharePulse()
                sharingIndicatorDrawable.mode = SharingIndicatorMode.PAUSED_BARS
                sharingIndicatorDrawable.invalidateSelf()
                shareToggleButton.text = "Share"
                shareToggleButton.backgroundTintList = ColorStateList.valueOf(0x7712161F)
                shareToggleButton.strokeColor = ColorStateList.valueOf(0x99FFFFFF.toInt())
                shareToggleButton.strokeWidth = dp(1)
                shareToggleButton.setTextColor(Color.WHITE)
                shareToggleButton.alpha = 0.96f
                shareToggleButton.invalidate()
                TooltipCompat.setTooltipText(
                    shareToggleButton,
                    "Sharing off — tap Share to contribute observations"
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

        val btOn = org.tucsoncommunity.kokoho.util.Permissions.isBluetoothEnabled(activity)
        bluetoothNoticePill.visibility = if (!btOn) View.VISIBLE else View.GONE

        val auxStatus = if (KokohoSignatures.isSharingActive(activity)) {
            "Sharing live"
        } else {
            "Local private"
        }

        val nextSubtitle = if (running) {
            val tempoSuffix = latestSnapshot?.sectorTempoText?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "Monitoring · $auxStatus$tempoSuffix"
        } else {
            "Starting sensors · $auxStatus"
        }
        if (headerSubtitle.text?.toString() != nextSubtitle) {
            headerSubtitle.text = nextSubtitle
        }
        applySurfaceThemeColors()
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
                latestSnapshot?.let { updatePosture(System.currentTimeMillis(), it) }
            }
            Tab.MAP -> {
                monitorContainer.visibility = View.GONE
                onSwitchPanes(setOf("map"))
            }
        }
        applySurfaceThemeColors()
    }

    private var lastAppliedThemeKey = ""

    private fun applySurfaceThemeColors() {
        val snapColor = (latestSnapshot?.color ?: PostureColor.GREEN).colorArgb
        val themeKey = "$snapColor|${currentTab.name}"
        if (themeKey == lastAppliedThemeKey) return
        lastAppliedThemeKey = themeKey

        binding.root.setBackgroundColor(snapColor)
        monitorContainer.setBackgroundColor(snapColor)
        binding.listFrame.setBackgroundColor(snapColor)
        headerLogo.imageTintList = ColorStateList.valueOf(Color.WHITE)
        headerTitle.setTextColor(Color.WHITE)
        headerSubtitle.setTextColor(0xEEFFFFFF.toInt())

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

    fun updatePosture(nowMs: Long = System.currentTimeMillis(), precomputed: PostureSnapshot? = null) {
        syncSharePillState()
        if (ScanForegroundService.isRunning && KokohoSignatures.isAuxnetEnabled(activity)) {
            KokohoHubClient.start(activity)
        }
        val snap = precomputed ?: KokohoPostureEngine.evaluate(activity, nowMs)
        latestSnapshot = snap
        if (ScanForegroundService.isRunning && !snap.sectorTempoText.isNullOrBlank()) {
            val baseSub = headerSubtitle.text?.toString()?.substringBefore(" · Sector Tempo").orEmpty()
            if (baseSub.startsWith("Scanning")) {
                val nextSub = "$baseSub · ${snap.sectorTempoText}"
                if (headerSubtitle.text?.toString() != nextSub) {
                    headerSubtitle.text = nextSub
                }
            }
        }

        // While the Map tab is active, skip hidden Monitor view inflation/layout churn so Map panning stays at 120 FPS.
        if (currentTab == Tab.MAP) {
            applySurfaceThemeColors()
            return
        }

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
        if (group.distinctUnits.size == 1) {
            val single = group.distinctUnits.first()
            activity.startActivity(
                Intent(activity, DeviceDetailActivity::class.java)
                    .putExtra(DeviceDetailActivity.EXTRA_MAC, "kokoho:${single.uuid}")
            )
            return
        }
        val dialog = org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.createCustomModal(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.cardDrawable(
                activity,
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.SURFACE_DARK,
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.BORDER_STRONG,
                18
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(10))
        }
        val headerTv = TextView(activity).apply {
            text = "${group.agency.friendlyLabel} — ${group.totalDistinctCount} Distinct ${if (group.totalDistinctCount == 1) "Detection" else "Detections"}"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = android.widget.ImageButton(activity).apply {
            contentDescription = "Close"
            background = org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.pillDrawable(
                activity,
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.SURFACE_ELEVATED,
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.BORDER_SUBTLE
            )
            setImageDrawable(
                org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.VectorIconDrawable(
                    activity,
                    org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.VectorIconKind.CLOSE_X
                )
            )
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(headerTv)
        headerRow.addView(closeBtn)
        outer.addView(headerRow)

        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(container)
        outer.addView(scroll)

        for (unit in group.distinctUnits) {
            val witness = KokohoSignatures.getWitnessReport(activity, unit.uuid, approvedOnly = false)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.SURFACE_CARD)
                    setStroke(dp(1), 0x66FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    dialog.dismiss()
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

            val primaryTitle = KokohoSignatures.formatHighestOrderTitle(unit, witness)
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
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.HIGHLIGHT_AMBER)
                setPadding(0, dp(2), 0, 0)
            }
            val metaTv = TextView(activity).apply {
                text = "$accStr$altExtra · $ageStr · ${unit.combinedConfidence}% conf"
                textSize = 13f
                setTextColor(org.tucsoncommunity.kokoho.ui.KokohoJourneyUi.INK_SECONDARY)
                setPadding(0, dp(2), 0, 0)
            }

            textCol.addView(nameTv)
            textCol.addView(idTv)
            textCol.addView(metaTv)

            row.addView(avatarView)
            row.addView(textCol)
            container.addView(row)
        }

        dialog.setContentView(outer)
        dialog.show()
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
