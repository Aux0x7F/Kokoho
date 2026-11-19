package org.tucsoncommunity.kokoho.settings

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.tucsoncommunity.kokoho.posture.KokohoHubClient
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.ui.KokohoJourneyUi
import org.tucsoncommunity.kokoho.util.Permissions
import org.tucsoncommunity.kokoho.util.Prefs
import org.tucsoncommunity.kokoho.util.UpdateChecker
import org.tucsoncommunity.kokoho.util.applySystemBarInsets

/**
 * Kokoho `v1.3.x` Settings Activity.
 *
 * Agency & Location filters now live cleanly in the root `☰ -> Filters` menu (with independent
 * Alarm + Map Eye toggles). Settings focuses on scan rate permissions, privacy, units, alerts,
 * live updates, and diagnostic exports using the unified Subjourney header and WCAG AAA contrast.
 */
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
        val rows: MutableList<SearchableRow> = mutableListOf()
    )

    private val sections = mutableListOf<SearchableSection>()
    private var bgPermSubView: TextView? = null
    private var batterySubView: TextView? = null

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bgPermSubView?.text = if (granted || Permissions.hasBackgroundLocation(this)) {
            "Granted: unthrottled background Wi-Fi & BLE scanning active"
        } else {
            "Not granted: allow 'All the time' in Android Location permissions for background scanning"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KokohoSignatures.ensureLoaded(this)

        val bgColor = KokohoPostureEngine.currentPostureColorArgb(this)
        val cardBg = 0x44000000
        val cardStroke = 0x66FFFFFF
        val primaryInk = Color.WHITE
        val secondaryInk = KokohoJourneyUi.INK_SECONDARY

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.applySystemBarInsets()

        val header = KokohoJourneyUi.createSubjourneyHeader(
            activity = this,
            initialTitle = "Settings",
            showCloseAll = false,
            menuItemsProvider = {
                listOf(
                    KokohoJourneyUi.SubjourneyMenuItem("Check for App Updates") {
                        UpdateChecker.check(this, silent = false)
                    },
                    KokohoJourneyUi.SubjourneyMenuItem("Export Diagnostic Logs") {
                        exportCrashAndDebugLogs()
                    }
                )
            }
        )
        root.addView(header.root)

        val searchBox = EditText(this).apply {
            hint = "Search settings (e.g. background, battery, privacy, units, logs)…"
            setSingleLine(true)
            textSize = 14.5f
            setTextColor(primaryInk)
            setHintTextColor(KokohoJourneyUi.INK_MUTED)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(cardBg)
                setStroke(dp(1), cardStroke)
            }
        }
        val searchWrapper = FrameLayout(this).apply {
            setPadding(dp(14), dp(10), dp(14), dp(8))
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

        fun createSection(titleText: String, subtitleText: String? = null): SearchableSection {
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
                    textSize = 13f
                    setTextColor(secondaryInk)
                    setPadding(0, dp(2), 0, 0)
                }
                headerBox.addView(subTv)
            }
            secLayout.addView(headerBox)
            scrollContent.addView(secLayout)
            return SearchableSection(secLayout).also { sections += it }
        }

        fun addSwitchRow(
            section: SearchableSection,
            titleText: String,
            subtitleText: String?,
            checked: Boolean,
            keywords: String = "",
            onChanged: (Boolean) -> Unit
        ): SwitchMaterial {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val sw = SwitchMaterial(this).apply {
                text = titleText
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primaryInk)
                isChecked = checked
                setOnCheckedChangeListener { _, isOn -> onChanged(isOn) }
            }
            row.addView(sw)
            if (!subtitleText.isNullOrBlank()) {
                val sub = TextView(this).apply {
                    text = subtitleText
                    textSize = 13f
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
                textSize = 13f
                setTextColor(secondaryInk)
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(t)
            textCol.addView(s)

            val btn = MaterialButton(this).apply {
                text = buttonLabel
                isAllCaps = false
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                cornerRadius = dp(10)
                backgroundTintList = android.content.res.ColorStateList.valueOf(KokohoJourneyUi.SURFACE_ELEVATED)
                setTextColor(Color.WHITE)
                strokeColor = android.content.res.ColorStateList.valueOf(KokohoJourneyUi.BORDER_STRONG)
                strokeWidth = dp(1)
                setOnClickListener { onClick(s) }
            }
            row.addView(textCol)
            row.addView(btn)
            section.container.addView(row)
            section.rows += SearchableRow(row, "$titleText $subtitleText $buttonLabel $keywords".lowercase())
            return s
        }

        // ---- SECTION 1: High-Rate Background Scanning & Permissions ----
        val secPermissions = createSection(
            "Scan Rate & Background Permissions",
            "Grant background location and unrestricted battery so BLE, Wi-Fi, and USB SDR never throttle while driving with the screen off."
        )

        bgPermSubView = addActionRow(
            section = secPermissions,
            titleText = "Background Location ('Allow all the time')",
            subtitleText = if (Permissions.hasBackgroundLocation(this)) {
                "Granted: unthrottled background Wi-Fi & BLE scanning active"
            } else {
                "Recommended: prevents Android from pausing Wi-Fi & BLE scans when Kokoho is in the background"
            },
            buttonLabel = if (Permissions.hasBackgroundLocation(this)) "Granted" else "Allow",
            keywords = "background location scan rate permissions wifi ble"
        ) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Permissions.hasBackgroundLocation(this)) {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
            }
        }

        batterySubView = addActionRow(
            section = secPermissions,
            titleText = "Unrestricted Battery Usage",
            subtitleText = if (Permissions.isIgnoringBatteryOptimizations(this)) {
                "Active: Android Doze will not throttle sensor sweeps"
            } else {
                "Recommended: exempt Kokoho from battery throttling for continuous RTL-SDR & BLE sweeps"
            },
            buttonLabel = if (Permissions.isIgnoringBatteryOptimizations(this)) "Active" else "Exempt",
            keywords = "battery optimization unrestricted doze scan rate"
        ) { _ ->
            runCatching { startActivity(Permissions.batteryOptimizationExemptionIntent(this)) }
        }

        // ---- SECTION 2: Privacy ----
        val secPrivacy = createSection(
            "Privacy",
            "Sharing defaults to OFF on every startup. Use the top-bar Share / Stop button when you choose to contribute."
        )

        addSwitchRow(
            section = secPrivacy,
            titleText = "Allow Observation Sharing (Share Button)",
            subtitleText = "Master switch for the top-bar Share / Stop button. Turning this off purges contributed observations immediately.",
            checked = KokohoSignatures.isContributionAllowedInSettings(this),
            keywords = "privacy contribute observations share stop server purge"
        ) { isOn ->
            KokohoSignatures.setContributionAllowedInSettings(this, isOn)
            Toast.makeText(
                this,
                if (isOn) "Sharing capability enabled (currently stopped until you tap Share)" else "Sharing disabled & observations purged",
                Toast.LENGTH_SHORT
            ).show()
        }

        addSwitchRow(
            section = secPrivacy,
            titleText = "Self-Hosted Push Alerts",
            subtitleText = "Uses zero Google Play Services. Receives real-time Auxnet sector updates.",
            checked = KokohoSignatures.isPushNotificationsEnabled(this),
            keywords = "privacy push notifications alerts self hosted"
        ) { isOn ->
            KokohoSignatures.setPushNotificationsEnabled(this, isOn)
        }

        // ---- SECTION 3: Units, Alerts & Diagnostics ----
        val secDisplay = createSection(
            "Units, Alerts & Diagnostics",
            "Distance formatting, alert sound/vibration, live updates, and diagnostic export."
        )

        addActionRow(
            section = secDisplay,
            titleText = "Distance Units",
            subtitleText = if (KokohoSignatures.isImperialUnits(this)) "Imperial (ft / mi), Default" else "Metric (m / km)",
            buttonLabel = "Change",
            keywords = "units imperial metric miles feet kilometers meters distance"
        ) { subTv ->
            KokohoJourneyUi.showActionSheetModal(
                this,
                "Distance Units",
                listOf(
                    KokohoJourneyUi.SubjourneyMenuItem("Imperial (ft / mi), Default") {
                        KokohoSignatures.setImperialUnits(this, true)
                        subTv.text = "Imperial (ft / mi), Default"
                    },
                    KokohoJourneyUi.SubjourneyMenuItem("Metric (m / km)") {
                        KokohoSignatures.setImperialUnits(this, false)
                        subTv.text = "Metric (m / km)"
                    }
                )
            )
        }

        addSwitchRow(
            section = secDisplay,
            titleText = "Alert Sound",
            subtitleText = "Play audible alert tone when an Alarm-enabled category is detected",
            checked = Prefs.soundEnabled(this),
            keywords = "alert sound audio tone beep"
        ) { isOn ->
            Prefs.setSoundEnabled(this, isOn)
        }

        addSwitchRow(
            section = secDisplay,
            titleText = "Alert Vibration",
            subtitleText = "Vibrate when Monitor posture escalates",
            checked = Prefs.vibrateEnabled(this),
            keywords = "alert vibrate vibration haptic"
        ) { isOn ->
            Prefs.setVibrateEnabled(this, isOn)
        }

        addActionRow(
            section = secDisplay,
            titleText = "Live App Updates",
            subtitleText = "Check Kokoho Hub (${KokohoHubClient.hubUrl(this)}) for newer releases on your channel (${org.tucsoncommunity.kokoho.BuildConfig.RELEASE_CHANNEL})",
            buttonLabel = "Check Now",
            keywords = "update live apk version upgrade"
        ) { _ ->
            UpdateChecker.check(this, silent = false)
        }

        addActionRow(
            section = secDisplay,
            titleText = "Export Crash & Debug Logs",
            subtitleText = "Share crash_log.txt, SDR telemetry, and process logcat for diagnostics",
            buttonLabel = "Export",
            keywords = "crash log debug export diagnostics share bug report"
        ) { _ ->
            exportCrashAndDebugLogs()
        }

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

    override fun onResume() {
        super.onResume()
        bgPermSubView?.text = if (Permissions.hasBackgroundLocation(this)) {
            "Granted: unthrottled background Wi-Fi & BLE scanning active"
        } else {
            "Recommended: prevents Android from pausing Wi-Fi & BLE scans when Kokoho is in the background"
        }
        batterySubView?.text = if (Permissions.isIgnoringBatteryOptimizations(this)) {
            "Active: Android Doze will not throttle sensor sweeps"
        } else {
            "Recommended: exempt Kokoho from battery throttling for continuous RTL-SDR & BLE sweeps"
        }
    }

    private fun exportCrashAndDebugLogs() {
        val sb = StringBuilder()
        sb.appendLine("=== KOKOHO DIAGNOSTIC & CRASH LOG EXPORT ===")
        sb.appendLine("Version: ${org.tucsoncommunity.kokoho.BuildConfig.VERSION_NAME} (${org.tucsoncommunity.kokoho.BuildConfig.VERSION_CODE})")
        sb.appendLine("Timestamp: ${java.util.Date()}")
        sb.appendLine()

        val crashFile = java.io.File(filesDir, "crash_log.txt")
        sb.appendLine("--- crash_log.txt ---")
        if (crashFile.exists()) {
            sb.appendLine(runCatching { crashFile.readText().takeLast(32_000) }.getOrDefault("(read error)"))
        } else {
            sb.appendLine("(No crash_log.txt recorded)")
        }
        sb.appendLine()

        sb.appendLine("--- Recent SDR RadioLog ---")
        for (r in org.tucsoncommunity.kokoho.sdr.RadioLog.current().take(30)) {
            sb.appendLine("${r.mhz} MHz | name=${r.name ?: "none"} | snr=${r.peakSnrDb} dB | last=${r.lastSeen}")
        }
        sb.appendLine()

        sb.appendLine("--- Recent Process Logcat ---")
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "250"))
            val logText = proc.inputStream.bufferedReader().use { it.readText() }
            sb.appendLine(logText.takeLast(32_000))
        }

        val fullReport = sb.toString()
        KokohoJourneyUi.showActionSheetModal(
            this,
            "Crash & Debug Logs",
            listOf(
                KokohoJourneyUi.SubjourneyMenuItem("Share / Export Diagnostic Bundle") {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Kokoho Crash & Debug Logs")
                        putExtra(Intent.EXTRA_TEXT, fullReport)
                    }
                    startActivity(Intent.createChooser(share, "Export Crash & Debug Logs"))
                },
                KokohoJourneyUi.SubjourneyMenuItem("Clear Crash Log") {
                    runCatching { if (crashFile.exists()) crashFile.delete() }
                    Toast.makeText(this, "Crash log cleared", Toast.LENGTH_SHORT).show()
                }
            )
        )
    }
}
