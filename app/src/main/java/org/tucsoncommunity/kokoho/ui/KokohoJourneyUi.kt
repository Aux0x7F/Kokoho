package org.tucsoncommunity.kokoho.ui

import android.app.Dialog
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
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.settings.SettingsActivity
import org.tucsoncommunity.kokoho.util.UpdateChecker

/**
 * Unified Kokoho `v1.3.x` Journey & Accessibility UI Kit.
 *
 * Enforces WCAG AAA color-deficiency-safe contrast across all screens, modals, menus, and subjourneys:
 *  - Zero blue text on dark surfaces (`#0F172A` / `#182234` surfaces with crisp `#FFFFFF` primary ink,
 *    `#E2E8F0` secondary ink, and `#FDE68A` high-luminance amber active highlights + explicit borders/icons).
 *  - Unified Subjourney Top Bar (`[ ← Back ]  [ Title ]  [ ⋯ Menu ]  [ ✕ Dismiss All ]`) replacing stock
 *    Android ActionBars so fullscreen subjourneys share one consistent navigation grammar.
 */
object KokohoJourneyUi {

    const val SURFACE_DARK = 0xFF0F172A.toInt()
    const val SURFACE_CARD = 0xFF1B263B.toInt()
    const val SURFACE_ELEVATED = 0xFF24324B.toInt()
    const val BORDER_SUBTLE = 0x55FFFFFF
    const val BORDER_STRONG = 0xAAFFFFFF.toInt()
    const val INK_PRIMARY = 0xFFFFFFFF.toInt()
    const val INK_SECONDARY = 0xFFE2E8F0.toInt()
    const val INK_MUTED = 0xFFCBD5E1.toInt()
    const val HIGHLIGHT_AMBER = 0xFFFDE68A.toInt()
    const val HIGHLIGHT_AMBER_BG = 0xFF423310.toInt()
    const val DANGER_RED_BG = 0xFF7F1D1D.toInt()

    // Semantic aliases used across Map footer & group modals
    const val COLOR_SURFACE = SURFACE_DARK
    const val COLOR_SURFACE_ELEVATED = SURFACE_ELEVATED
    const val COLOR_BORDER_STRONG = BORDER_STRONG
    const val COLOR_TEXT_PRIMARY = INK_PRIMARY
    const val COLOR_TEXT_SECONDARY = INK_SECONDARY
    const val COLOR_ACCENT_AMBER = 0xFFF59E0B.toInt()

    data class SubjourneyMenuItem(
        val label: String,
        val onClick: () -> Unit
    )

    class SubjourneyHeaderHandle(
        val root: LinearLayout,
        val titleView: TextView,
        val closeAllButton: ImageButton,
        val moreButton: ImageButton
    )

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /**
     * Hides the legacy Android ActionBar and returns a unified Kokoho Subjourney Header view:
     * `[ ← ]  [ Subjourney Title ]  [ ⋯ ]  [ ✕ ]`
     */
    fun createSubjourneyHeader(
        activity: AppCompatActivity,
        initialTitle: String,
        showCloseAll: Boolean = false,
        onBack: () -> Unit = { activity.onBackPressedDispatcher.onBackPressed() },
        onCloseAll: (() -> Unit)? = null,
        menuItemsProvider: (() -> List<SubjourneyMenuItem>)? = null
    ): SubjourneyHeaderHandle {
        activity.supportActionBar?.hide()

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            background = GradientDrawable().apply {
                setColor(0x44000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = ImageButton(activity).apply {
            contentDescription = "Back"
            background = pillDrawable(activity, 0x33000000, BORDER_SUBTLE)
            setImageDrawable(VectorIconDrawable(activity, VectorIconKind.BACK_ARROW))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)).apply {
                marginEnd = dp(activity, 10)
            }
            setOnClickListener { onBack() }
        }

        val titleTv = TextView(activity).apply {
            text = initialTitle
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(INK_PRIMARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val moreBtn = ImageButton(activity).apply {
            contentDescription = "More options"
            background = pillDrawable(activity, 0x33000000, BORDER_SUBTLE)
            setImageDrawable(VectorIconDrawable(activity, VectorIconKind.MORE_DOTS))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)).apply {
                marginStart = dp(activity, 6)
            }
            visibility = if (menuItemsProvider != null) View.VISIBLE else View.GONE
            setOnClickListener {
                val items = menuItemsProvider?.invoke().orEmpty()
                if (items.isNotEmpty()) {
                    showActionSheetModal(activity, titleTv.text.toString(), items)
                }
            }
        }

        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Dismiss to Map"
            background = pillDrawable(activity, 0x33000000, BORDER_SUBTLE)
            setImageDrawable(VectorIconDrawable(activity, VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)).apply {
                marginStart = dp(activity, 6)
            }
            visibility = if (showCloseAll && onCloseAll != null) View.VISIBLE else View.GONE
            setOnClickListener { onCloseAll?.invoke() }
        }

        bar.addView(backBtn)
        bar.addView(titleTv)
        bar.addView(moreBtn)
        bar.addView(closeBtn)

        return SubjourneyHeaderHandle(bar, titleTv, closeBtn, moreBtn)
    }

    /**
     * Wraps an existing XML-inflated Activity content view in a vertical shell with the unified
     * Kokoho Subjourney Header on top (used for Waterfall, P25, Logs, Antenna Check, etc.).
     */
    fun wrapActivityWithSubjourneyHeader(
        activity: AppCompatActivity,
        contentView: View,
        title: String,
        bgColorArgb: Int,
        menuItemsProvider: (() -> List<SubjourneyMenuItem>)? = null
    ): SubjourneyHeaderHandle {
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColorArgb)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val handle = createSubjourneyHeader(
            activity = activity,
            initialTitle = title,
            showCloseAll = false,
            menuItemsProvider = menuItemsProvider
        )
        shell.addView(handle.root)
        (contentView.parent as? ViewGroup)?.removeView(contentView)
        shell.addView(
            contentView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return handle
    }

    fun pillDrawable(context: Context, fillArgb: Int, strokeArgb: Int, radiusDp: Int = 999, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillArgb)
            if (strokeDp > 0) setStroke(dp(context, strokeDp).coerceAtLeast(1), strokeArgb)
        }

    fun cardDrawable(context: Context, fillArgb: Int = SURFACE_CARD, strokeArgb: Int = BORDER_SUBTLE, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillArgb)
            setStroke(dp(context, 1).coerceAtLeast(1), strokeArgb)
        }

    fun primaryActionButton(context: Context, textLabel: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = textLabel
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(context, 44)
            minimumHeight = dp(context, 44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(context, 12)
            backgroundTintList = ColorStateList.valueOf(HIGHLIGHT_AMBER)
            setTextColor(SURFACE_DARK)
            strokeColor = ColorStateList.valueOf(INK_PRIMARY)
            strokeWidth = dp(context, 1)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            setOnClickListener { onClick() }
        }

    fun secondaryActionButton(context: Context, textLabel: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = textLabel
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(context, 44)
            minimumHeight = dp(context, 44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(context, 12)
            backgroundTintList = ColorStateList.valueOf(SURFACE_ELEVATED)
            setTextColor(INK_PRIMARY)
            strokeColor = ColorStateList.valueOf(BORDER_STRONG)
            strokeWidth = dp(context, 1)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            setOnClickListener { onClick() }
        }

    /**
     * Creates a custom WCAG AAA Kokoho modal dialog (zero SDK blue button text).
     */
    fun createCustomModal(
        context: Context,
        fullscreen: Boolean = false
    ): Dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.let { w ->
            val dm = context.resources.displayMetrics
            w.setLayout(
                if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else (dm.widthPixels * 0.94f).toInt(),
                if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun showActionSheetModal(
        activity: AppCompatActivity,
        title: String,
        items: List<SubjourneyMenuItem>
    ) {
        val dialog = createCustomModal(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(activity, SURFACE_DARK, BORDER_STRONG, 18)
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val titleTv = TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Close"
            background = pillDrawable(activity, SURFACE_ELEVATED, BORDER_SUBTLE)
            setImageDrawable(VectorIconDrawable(activity, VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        box.addView(headerRow)

        for (item in items) {
            val btn = secondaryActionButton(activity, item.label) {
                dialog.dismiss()
                item.onClick()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(activity, 8)
                }
            }
            box.addView(btn)
        }
        val modalWidthPx = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
        box.minimumWidth = modalWidthPx
        dialog.setContentView(box, ViewGroup.LayoutParams(modalWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        dialog.window?.setLayout(modalWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Opens the Root Sandwich Menu (`☰`) with 3 top-level entries:
     *  1. `Filters` (with filter icon — default collapsed; expands inline with per-row Alarm `🔔` & Eye `👁` buttons)
     *  2. `Utilities` (default collapsed; expands inline submenu of utilities)
     *  3. `Settings` (navigates directly to `SettingsActivity`)
     */
    fun showRootSandwichMenu(
        activity: AppCompatActivity,
        onFiltersChanged: () -> Unit
    ): Dialog {
        val dm = activity.resources.displayMetrics
        val menuWidthPx = (dm.widthPixels * 0.90f).toInt()
        val maxScrollHeightPx = (dm.heightPixels * 0.72f).toInt()
        val dialog = createCustomModal(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = menuWidthPx
            layoutParams = ViewGroup.LayoutParams(menuWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            background = cardDrawable(activity, SURFACE_DARK, BORDER_STRONG, 18)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 16))
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), 0, 0, dp(activity, 10))
        }
        val menuTitle = TextView(activity).apply {
            text = "KOKOHO MENU"
            textSize = 16f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(INK_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Close menu"
            background = pillDrawable(activity, SURFACE_ELEVATED, BORDER_SUBTLE)
            setImageDrawable(VectorIconDrawable(activity, VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(menuTitle)
        headerRow.addView(closeBtn)
        outer.addView(headerRow)

        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxScrollHeightPx, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body)
        outer.addView(scroll)

        fun buildMenuSectionButton(
            iconKind: VectorIconKind,
            titleText: String,
            chevronView: TextView?
        ): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(activity, SURFACE_CARD, BORDER_SUBTLE, 14)
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(activity, 8)
            }
            val icon = ImageView(activity).apply {
                setImageDrawable(VectorIconDrawable(activity, iconKind))
                layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22)).apply {
                    marginEnd = dp(activity, 12)
                }
            }
            val label = TextView(activity).apply {
                text = titleText
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(icon)
            addView(label)
            if (chevronView != null) addView(chevronView)
        }

        // ---- Entry 1: FILTERS (Default Collapsed, Alarm + Eye Toggles) ----
        val filtersChevron = TextView(activity).apply {
            text = "▸"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(HIGHLIGHT_AMBER)
        }
        val filtersHeader = buildMenuSectionButton(VectorIconKind.FILTER_FUNNEL, "Filters", filtersChevron)
        val filtersSubmenu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(activity, 0xFF131D2E.toInt(), BORDER_SUBTLE, 12)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(activity, 10)
            }
        }

        val legendRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 8))
        }
        val legendText = TextView(activity).apply {
            text = "Category"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val legendRight = TextView(activity).apply {
            text = "Alert  ·  Map"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(HIGHLIGHT_AMBER)
        }
        legendRow.addView(legendText)
        legendRow.addView(legendRight)
        filtersSubmenu.addView(legendRow)

        val refreshCallbacks = ArrayList<() -> Unit>()
        fun notifyAllFilterRows() {
            refreshCallbacks.forEach { it.invoke() }
            onFiltersChanged()
        }

        fun addFilterToggleRow(
            label: String,
            indentDp: Int = 0,
            isAlarmOn: () -> Boolean,
            isEyeOn: () -> Boolean,
            onToggleAlarm: () -> Unit,
            onToggleEye: () -> Unit
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, indentDp), dp(activity, 5), 0, dp(activity, 5))
            }
            val tv = TextView(activity).apply {
                text = label
                textSize = if (indentDp == 0) 14.5f else 14f
                typeface = if (indentDp == 0) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(INK_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val alarmBtn = ImageButton(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 36)).apply {
                    marginEnd = dp(activity, 8)
                }
            }
            val eyeBtn = ImageButton(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 36))
            }

            fun styleFilterBtn(btn: ImageButton, kind: VectorIconKind, active: Boolean, desc: String) {
                btn.contentDescription = desc
                btn.background = pillDrawable(
                    activity,
                    if (active) HIGHLIGHT_AMBER else SURFACE_ELEVATED,
                    if (active) INK_PRIMARY else 0x44FFFFFF,
                    radiusDp = 10
                )
                btn.setImageDrawable(
                    VectorIconDrawable(
                        activity,
                        kind,
                        strokeColor = if (active) SURFACE_DARK else INK_MUTED
                    )
                )
            }

            val syncRow = {
                val aOn = isAlarmOn()
                val eOn = isEyeOn()
                styleFilterBtn(alarmBtn, VectorIconKind.BELL_ALARM, aOn, "$label monitor alert ${if (aOn) "on" else "off"}")
                styleFilterBtn(eyeBtn, VectorIconKind.EYE_VISIBILITY, eOn, "$label map visibility ${if (eOn) "on" else "off"}")
            }
            alarmBtn.setOnClickListener {
                onToggleAlarm()
                notifyAllFilterRows()
            }
            eyeBtn.setOnClickListener {
                onToggleEye()
                notifyAllFilterRows()
            }
            syncRow()
            refreshCallbacks += syncRow

            row.addView(tv)
            row.addView(alarmBtn)
            row.addView(eyeBtn)
            filtersSubmenu.addView(row)
        }

        // Master DHS row
        addFilterToggleRow(
            label = "DHS (All Sub-Agencies)",
            indentDp = 0,
            isAlarmOn = { KokohoSignatures.isMasterDhsEnabled(activity) },
            isEyeOn = { KokohoSignatures.isDhsMasterMapVisible(activity) },
            onToggleAlarm = { KokohoSignatures.setMasterDhs(activity, !KokohoSignatures.isMasterDhsEnabled(activity)) },
            onToggleEye = { KokohoSignatures.setDhsMasterMapVisible(activity, !KokohoSignatures.isDhsMasterMapVisible(activity)) }
        )

        val orderedAgencies = listOf(
            KokohoAgency.DHS_ICE to 12,
            KokohoAgency.DHS_AMO to 12,
            KokohoAgency.DHS_CBP to 12,
            KokohoAgency.DHS_HSI to 12,
            KokohoAgency.DHS_FPS to 12,
            KokohoAgency.DHS_INTEROP to 12,
            KokohoAgency.SHERIFF to 0,
            KokohoAgency.POLICE to 0,
            KokohoAgency.DRONE to 0,
            KokohoAgency.FIRE to 0,
            KokohoAgency.SAR to 0
        )
        for ((ag, indent) in orderedAgencies) {
            addFilterToggleRow(
                label = ag.friendlyLabel,
                indentDp = indent,
                isAlarmOn = { ag in KokohoSignatures.getEnabledAgencies(activity) },
                isEyeOn = { ag in KokohoSignatures.getMapVisibleAgencies(activity) },
                onToggleAlarm = { KokohoSignatures.toggleAgency(activity, ag) },
                onToggleEye = { KokohoSignatures.toggleAgencyMapVisible(activity, ag) }
            )
        }

        // Facility / Station Locations filter row
        addFilterToggleRow(
            label = "Locations (Stations & Facilities)",
            indentDp = 0,
            isAlarmOn = { KokohoSignatures.isLocationsAlarmEnabled(activity) },
            isEyeOn = { KokohoSignatures.isMapLocationsVisible(activity) },
            onToggleAlarm = { KokohoSignatures.setLocationsAlarmEnabled(activity, !KokohoSignatures.isLocationsAlarmEnabled(activity)) },
            onToggleEye = { KokohoSignatures.setMapLocationsVisible(activity, !KokohoSignatures.isMapLocationsVisible(activity)) }
        )

        filtersHeader.setOnClickListener {
            val expand = filtersSubmenu.visibility != View.VISIBLE
            filtersSubmenu.visibility = if (expand) View.VISIBLE else View.GONE
            filtersChevron.text = if (expand) "▾" else "▸"
        }
        body.addView(filtersHeader)
        body.addView(filtersSubmenu)

        // ---- Entry 2: UTILITIES (Default Collapsed Submenu) ----
        val utilsChevron = TextView(activity).apply {
            text = "▸"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(HIGHLIGHT_AMBER)
        }
        val utilsHeader = buildMenuSectionButton(VectorIconKind.TOOLBOX, "Utilities", utilsChevron)
        val utilsSubmenu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(activity, 0xFF131D2E.toInt(), BORDER_SUBTLE, 12)
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(activity, 10)
            }
        }

        fun addUtilEntry(title: String, subtitle: String, highlight: Boolean = false, onOpen: () -> Unit) {
            val itemCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(
                    activity,
                    if (highlight) HIGHLIGHT_AMBER_BG else SURFACE_CARD,
                    if (highlight) HIGHLIGHT_AMBER else 0x44FFFFFF,
                    10
                )
                setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(activity, 6)
                }
                setOnClickListener {
                    dialog.dismiss()
                    onOpen()
                }
            }
            val t = TextView(activity).apply {
                text = title
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (highlight) HIGHLIGHT_AMBER else INK_PRIMARY)
            }
            val s = TextView(activity).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(INK_SECONDARY)
                setPadding(0, dp(activity, 2), 0, 0)
            }
            itemCard.addView(t)
            itemCard.addView(s)
            utilsSubmenu.addView(itemCard)
        }

        UpdateChecker.availableUpdate?.let { rel ->
            addUtilEntry(
                title = "Update App (v${rel.version})",
                subtitle = "New release ready on Kokoho Hub — tap to install",
                highlight = true
            ) { UpdateChecker.installNow(activity, rel) }
        }
        addUtilEntry("P25 Talkgroups & Trunking", "Live P25 control channel decode, talkgroups, & mobile inputs") {
            activity.startActivity(Intent(activity, P25Activity::class.java))
        }
        addUtilEntry("Radio Waterfall", "Live RTL-SDR spectrum waterfall & narrowband hold") {
            activity.startActivity(Intent(activity, WaterfallActivity::class.java))
        }
        addUtilEntry("Antenna & NOAA Check", "Verify RTL-SDR receive gain on 162.400–162.550 MHz") {
            activity.startActivity(Intent(activity, AntennaCheckActivity::class.java))
        }
        addUtilEntry("Aircraft Monitor", "Active ADS-B & orbiting aircraft feed") {
            activity.startActivity(Intent(activity, AircraftActivity::class.java))
        }
        addUtilEntry("Cell Towers", "Observed LTE/NR serving & neighbor cells") {
            activity.startActivity(Intent(activity, CellTowersActivity::class.java))
        }
        addUtilEntry("Wi-Fi Spectrum", "2.4 / 5 / 6 GHz channel occupancy") {
            activity.startActivity(Intent(activity, WifiAnalyzerActivity::class.java))
        }
        addUtilEntry("Satellites (GNSS)", "Constellation skyplot & fix quality") {
            activity.startActivity(Intent(activity, GnssActivity::class.java))
        }
        addUtilEntry("Logs & Archive", "Chronological detection & telemetry log") {
            activity.startActivity(Intent(activity, DetectionLogActivity::class.java))
        }

        utilsHeader.setOnClickListener {
            val expand = utilsSubmenu.visibility != View.VISIBLE
            utilsSubmenu.visibility = if (expand) View.VISIBLE else View.GONE
            utilsChevron.text = if (expand) "▾" else "▸"
        }
        body.addView(utilsHeader)
        body.addView(utilsSubmenu)

        // ---- Entry 3: SETTINGS ----
        val settingsHeader = buildMenuSectionButton(VectorIconKind.GEAR_SETTINGS, "Settings", null).apply {
            setOnClickListener {
                dialog.dismiss()
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
        }
        body.addView(settingsHeader)

        dialog.setContentView(outer, ViewGroup.LayoutParams(menuWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        dialog.window?.setLayout(menuWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    /**
     * Expands one or more base64 `data:image/...` photos in a sleek thin-frame fullscreen modal,
     * with a horizontal swipeable `ViewPager2` carousel + `1 / N` counter when multiple photos exist.
     */
    fun showPhotoCarouselModal(
        context: Context,
        photoDataUrls: List<String>,
        initialIndex: Int = 0
    ) {
        val decodedBitmaps = photoDataUrls.mapNotNull { decodeDataUrlBitmap(it) }
        if (decodedBitmaps.isEmpty()) return

        val dialog = createCustomModal(context, fullscreen = true)
        val root = FrameLayout(context).apply {
            setBackgroundColor(0xEE070B12.toInt())
            setPadding(dp(context, 8), dp(context, 14), dp(context, 8), dp(context, 14))
        }

        val frame = FrameLayout(context).apply {
            background = cardDrawable(context, 0xFF0B111E.toInt(), BORDER_STRONG, 14)
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val pager = ViewPager2(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val iv = ImageView(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(context, 6), dp(context, 36), dp(context, 6), dp(context, 36))
                    }
                    return object : RecyclerView.ViewHolder(iv) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as ImageView).setImageBitmap(decodedBitmaps[position])
                }

                override fun getItemCount(): Int = decodedBitmaps.size
            }
        }
        frame.addView(pager)

        val counterPill = TextView(context).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK_PRIMARY)
            background = pillDrawable(context, 0xCC0F172A.toInt(), BORDER_SUBTLE)
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            visibility = if (decodedBitmaps.size > 1) View.VISIBLE else View.GONE
            text = "${initialIndex + 1} / ${decodedBitmaps.size}"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(context, 14)
            }
        }
        frame.addView(counterPill)

        if (decodedBitmaps.size > 1) {
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    counterPill.text = "${position + 1} / ${decodedBitmaps.size}"
                }
            })
            pager.setCurrentItem(initialIndex.coerceIn(0, decodedBitmaps.lastIndex), false)
        }

        val closeBtn = ImageButton(context).apply {
            contentDescription = "Close photo"
            background = pillDrawable(context, 0xDD0F172A.toInt(), BORDER_STRONG)
            setImageDrawable(VectorIconDrawable(context, VectorIconKind.CLOSE_X))
            layoutParams = FrameLayout.LayoutParams(dp(context, 42), dp(context, 42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(context, 10)
                marginEnd = dp(context, 10)
            }
            setOnClickListener { dialog.dismiss() }
        }
        frame.addView(closeBtn)

        root.addView(frame)
        dialog.setContentView(root)
        dialog.show()
    }

    fun decodeDataUrlBitmap(dataUrl: String?): Bitmap? {
        if (dataUrl.isNullOrBlank()) return null
        return runCatching {
            val b64 = dataUrl.substringAfter("base64,", dataUrl)
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    enum class VectorIconKind {
        SANDWICH_MENU,
        BACK_ARROW,
        CLOSE_X,
        MORE_DOTS,
        FILTER_FUNNEL,
        BELL_ALARM,
        EYE_VISIBILITY,
        TOOLBOX,
        GEAR_SETTINGS
    }

    class VectorIconDrawable(
        context: Context,
        private val kind: VectorIconKind,
        private val strokeColor: Int = INK_PRIMARY
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.FILL
        }

        override fun getIntrinsicWidth(): Int = (22 * density).toInt()
        override fun getIntrinsicHeight(): Int = (22 * density).toInt()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val l = b.left.toFloat()
            val t = b.top.toFloat()
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()

            when (kind) {
                VectorIconKind.SANDWICH_MENU -> {
                    val x1 = l + w * 0.22f
                    val x2 = l + w * 0.78f
                    canvas.drawLine(x1, t + h * 0.31f, x2, t + h * 0.31f, paint)
                    canvas.drawLine(x1, cy, x2, cy, paint)
                    canvas.drawLine(x1, t + h * 0.69f, x2, t + h * 0.69f, paint)
                }
                VectorIconKind.BACK_ARROW -> {
                    val p = Path().apply {
                        moveTo(l + w * 0.58f, t + h * 0.26f)
                        lineTo(l + w * 0.32f, cy)
                        lineTo(l + w * 0.58f, t + h * 0.74f)
                    }
                    canvas.drawPath(p, paint)
                    canvas.drawLine(l + w * 0.34f, cy, l + w * 0.74f, cy, paint)
                }
                VectorIconKind.CLOSE_X -> {
                    canvas.drawLine(l + w * 0.30f, t + h * 0.30f, l + w * 0.70f, t + h * 0.70f, paint)
                    canvas.drawLine(l + w * 0.70f, t + h * 0.30f, l + w * 0.30f, t + h * 0.70f, paint)
                }
                VectorIconKind.MORE_DOTS -> {
                    val r = w * 0.065f
                    canvas.drawCircle(l + w * 0.30f, cy, r, fillPaint)
                    canvas.drawCircle(cx, cy, r, fillPaint)
                    canvas.drawCircle(l + w * 0.70f, cy, r, fillPaint)
                }
                VectorIconKind.FILTER_FUNNEL -> {
                    val p = Path().apply {
                        moveTo(l + w * 0.16f, t + h * 0.22f)
                        lineTo(l + w * 0.84f, t + h * 0.22f)
                        lineTo(l + w * 0.58f, t + h * 0.52f)
                        lineTo(l + w * 0.58f, t + h * 0.78f)
                        lineTo(l + w * 0.42f, t + h * 0.72f)
                        lineTo(l + w * 0.42f, t + h * 0.52f)
                        close()
                    }
                    canvas.drawPath(p, paint)
                }
                VectorIconKind.BELL_ALARM -> {
                    val p = Path().apply {
                        moveTo(cx, t + h * 0.20f)
                        quadTo(l + w * 0.72f, t + h * 0.24f, l + w * 0.72f, t + h * 0.58f)
                        lineTo(l + w * 0.78f, t + h * 0.68f)
                        lineTo(l + w * 0.22f, t + h * 0.68f)
                        lineTo(l + w * 0.28f, t + h * 0.58f)
                        quadTo(l + w * 0.28f, t + h * 0.24f, cx, t + h * 0.20f)
                    }
                    canvas.drawPath(p, paint)
                    canvas.drawArc(RectF(cx - w * 0.10f, t + h * 0.68f, cx + w * 0.10f, t + h * 0.82f), 0f, 180f, false, paint)
                }
                VectorIconKind.EYE_VISIBILITY -> {
                    val p = Path().apply {
                        moveTo(l + w * 0.16f, cy)
                        quadTo(cx, t + h * 0.22f, l + w * 0.84f, cy)
                        quadTo(cx, t + h * 0.78f, l + w * 0.16f, cy)
                    }
                    canvas.drawPath(p, paint)
                    canvas.drawCircle(cx, cy, w * 0.11f, fillPaint)
                }
                VectorIconKind.TOOLBOX -> {
                    canvas.drawRoundRect(RectF(l + w * 0.18f, t + h * 0.34f, l + w * 0.82f, t + h * 0.78f), 3f * density, 3f * density, paint)
                    canvas.drawRect(RectF(l + w * 0.36f, t + h * 0.22f, l + w * 0.64f, t + h * 0.34f), paint)
                    canvas.drawLine(l + w * 0.18f, t + h * 0.54f, l + w * 0.82f, t + h * 0.54f, paint)
                }
                VectorIconKind.GEAR_SETTINGS -> {
                    canvas.drawCircle(cx, cy, w * 0.15f, paint)
                    canvas.drawCircle(cx, cy, w * 0.30f, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Bridges an Activity's legacy `onCreateOptionsMenu` / `onOptionsItemSelected` into
     * high-contrast `SubjourneyMenuItem`s for the `[ ⋯ ]` Subjourney Header button.
     */
    fun optionsMenuBridge(activity: AppCompatActivity): (() -> List<SubjourneyMenuItem>) = {
        val popup = android.widget.PopupMenu(activity, View(activity))
        val menu = popup.menu
        runCatching { activity.onCreateOptionsMenu(menu) }
        val items = ArrayList<SubjourneyMenuItem>()
        for (i in 0 until menu.size()) {
            val mi = menu.getItem(i)
            if (mi.isVisible && mi.isEnabled && !mi.title.isNullOrBlank()) {
                items += SubjourneyMenuItem(mi.title.toString()) {
                    activity.onOptionsItemSelected(mi)
                }
            }
        }
        items
    }

    /**
     * Scrollable WCAG AAA custom modal dialog with title, optional subtitle, `✕` close button,
     * and caller-populated body container.
     */
    fun showModal(
        activity: AppCompatActivity,
        title: String,
        subtitle: String? = null,
        populate: (body: LinearLayout, dismiss: () -> Unit) -> Unit
    ) {
        val dialog = createCustomModal(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(activity, SURFACE_DARK, BORDER_STRONG, 18)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 16))
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK_PRIMARY)
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(activity).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(INK_SECONDARY)
            })
        }
        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Close"
            background = pillDrawable(activity, SURFACE_ELEVATED, BORDER_SUBTLE)
            setImageDrawable(VectorIconDrawable(activity, VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        outer.addView(headerRow)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        populate(body) { dialog.dismiss() }
        scroll.addView(body)
        outer.addView(scroll)
        val modalWidthPx = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
        outer.minimumWidth = modalWidthPx
        dialog.setContentView(outer, ViewGroup.LayoutParams(modalWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        dialog.window?.setLayout(modalWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** High-contrast tappable row card for custom modals & lists. */
    fun buildActionRow(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardDrawable(ctx, SURFACE_CARD, BORDER_SUBTLE, 12)
        setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(ctx, 8)
        }
        addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK_PRIMARY)
        })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(ctx).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(INK_SECONDARY)
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        setOnClickListener { onClick() }
    }
}
