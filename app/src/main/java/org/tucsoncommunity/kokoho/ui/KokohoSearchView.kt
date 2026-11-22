package org.tucsoncommunity.kokoho.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.tucsoncommunity.kokoho.posture.DeviceTrustManager
import org.tucsoncommunity.kokoho.posture.EvaluatedThreat
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoDirectory
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.PriorityVault
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified `Search` tab view (`Monitor | Map | Search`) across all Kokoho schemas:
 *  - `Detection` (`EvaluatedThreat`: live, durable SQLite ledger, witness, & synced Hub entities)
 *  - `Location` (`KokohoDirectory.FacilityLocation`: stations & facilities)
 *  - `Person` (`KokohoDirectory.PersonRecord`: witness personnel & packaged directory personnel)
 *  - `Device` (`DeviceRegistry.Snapshot` & constituent hardware/RF signatures)
 *
 * Supports standardized `Tab` / `Enter` / `+ chip` autocomplete filter pills (`✕` dismissible)
 * alongside live raw text search when no filter matches, automatic soft-keyboard presentation
 * on tab entry, soft-keyboard dismissal on tab exit, and smooth `RecyclerView` list scrolling
 * where tapping anywhere on a row opens its unified [DeviceDetailActivity] detail journey.
 */
class KokohoSearchView(
    private val activity: AppCompatActivity
) {

    enum class SchemaKind(val badgeLabel: String) {
        DETECTION("Detection"),
        LOCATION("Location"),
        PERSON("Person"),
        PLATE("Plate"),
        DEVICE("Device")
    }

    data class SearchResultRow(
        val stableId: String,
        val schema: SchemaKind,
        val title: String,
        val metaLine: String,
        val detailLine: String,
        val targetDetailKey: String,
        val agency: KokohoAgency?,
        val isFireLocation: Boolean = false,
        val detectionIconKind: MapIcons.BadgeIconKind = MapIcons.BadgeIconKind.CAR,
        val personAvatarSeed: String? = null
    )

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    val root: LinearLayout
    val searchInput: EditText
    private val clearInputButton: ImageButton
    private val pillsScroll: HorizontalScrollView
    private val pillsRow: LinearLayout
    private val suggestionsScroll: HorizontalScrollView
    private val suggestionsRow: LinearLayout
    val summaryCountView: TextView
    val recyclerView: RecyclerView
    private val emptyStateView: TextView

    private val activePills = ArrayList<String>()
    private var currentSuggestions: List<String> = emptyList()
    private var suppressTextWatcher = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kokoho-global-search").apply { isDaemon = true }
    }
    private val queryGeneration = AtomicLong(0L)
    private val stationedCountCache = ConcurrentHashMap<String, Int>()
    private val iconBitmapCache = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var isDestroyed: Boolean = false
    @Volatile private var lastQueryVaultRevision: Long = -1L
    @Volatile private var lastQueryIntelAccess: Boolean = false

    private val adapter = SearchAdapter()

    init {
        root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(KokohoJourneyUi.SURFACE_DARK)
            visibility = View.GONE
        }

        val topCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = KokohoJourneyUi.cardDrawable(activity, 0xFF0E1524.toInt(), KokohoJourneyUi.BORDER_SUBTLE, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Active Dismissible Pills Row
        pillsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pillsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            addView(pillsRow)
        }
        topCard.addView(pillsScroll)

        // 2. Search Input Box + Clear Button
        val inputBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = KokohoJourneyUi.cardDrawable(
                activity,
                KokohoJourneyUi.SURFACE_CARD,
                KokohoJourneyUi.BORDER_STRONG,
                12
            )
            setPadding(dp(12), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val searchGlyph = TextView(activity).apply {
            text = "⌕"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
            setPadding(0, 0, dp(8), 0)
        }

        searchInput = EditText(activity).apply {
            setHintTextColor(KokohoJourneyUi.INK_MUTED)
            setTextColor(Color.WHITE)
            textSize = 15f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        updateSearchHint()

        clearInputButton = ImageButton(activity).apply {
            contentDescription = "Clear search text"
            background = KokohoJourneyUi.pillDrawable(activity, KokohoJourneyUi.SURFACE_ELEVATED, KokohoJourneyUi.BORDER_SUBTLE)
            setImageDrawable(KokohoJourneyUi.VectorIconDrawable(activity, KokohoJourneyUi.VectorIconKind.CLOSE_X))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            setOnClickListener {
                searchInput.setText("")
                searchInput.requestFocus()
            }
        }

        inputBox.addView(searchGlyph)
        inputBox.addView(searchInput)
        inputBox.addView(clearInputButton)
        topCard.addView(inputBox)

        // 3. Autocomplete Filter Suggestions Strip (hides cleanly when raw search has no filter match)
        suggestionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestionsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            addView(suggestionsRow)
        }
        topCard.addView(suggestionsScroll)

        summaryCountView = TextView(activity).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            setPadding(dp(2), dp(8), dp(2), 0)
            text = "ALL RECORDS"
        }
        topCard.addView(summaryCountView)
        root.addView(topCard)

        // 4. Results List (RecyclerView for smooth drag-scroll + crisp tap anywhere on card)
        val listFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@KokohoSearchView.adapter
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(24))
            itemAnimator = null
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(dp(12), dp(8), dp(12), imeBottom + dp(18))
            insets
        }

        emptyStateView = TextView(activity).apply {
            text = "No matching records found."
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(36), dp(24), dp(36))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        }

        listFrame.addView(recyclerView)
        listFrame.addView(emptyStateView)
        root.addView(listFrame)

        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                commitTopFilterOrHideIme()
                true
            } else {
                false
            }
        }

        searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_TAB) {
                commitTopFilterSuggestionOrRawToken()
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                commitTopFilterOrHideIme()
                true
            } else if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DEL &&
                searchInput.text.isNullOrEmpty() &&
                activePills.isNotEmpty()
            ) {
                activePills.removeAt(activePills.lastIndex)
                renderPillsAndScheduleSearch()
                true
            } else {
                false
            }
        }

        searchInput.doAfterTextChanged { editable ->
            if (suppressTextWatcher) return@doAfterTextChanged
            val hasText = !editable.isNullOrEmpty()
            clearInputButton.visibility = if (hasText) View.VISIBLE else View.GONE
            scheduleSearch()
        }
    }

    /** Adds a filter pill programmatically (and clears matching input text). */
    fun addFilterPill(pill: String) {
        val clean = pill.trim()
        if (clean.isEmpty()) return
        if (isPersonSchemaToken(clean) && !DeviceTrustManager.hasIntelligenceAccess(activity)) {
            return
        }
        if (activePills.none { it.equals(clean, ignoreCase = true) }) {
            activePills += clean
        }
        suppressTextWatcher = true
        searchInput.setText("")
        clearInputButton.visibility = View.GONE
        suppressTextWatcher = false
        renderPillsAndScheduleSearch()
    }

    fun getActivePills(): List<String> = activePills.toList()

    fun getCurrentResults(): List<SearchResultRow> = adapter.currentList

    @VisibleForTesting
    internal fun getCurrentSuggestions(): List<String> = currentSuggestions.toList()

    private fun updateSearchHint() {
        searchInput.hint = if (DeviceTrustManager.hasIntelligenceAccess(activity)) {
            "Search detections, stations, personnel, devices…"
        } else {
            "Search detections, stations, plates, devices…"
        }
    }

    private fun isPersonSchemaToken(token: String): Boolean =
        when (token.trim().lowercase(Locale.US)) {
            "person", "persons", "personnel", "people" -> true
            else -> false
        }

    fun onTrustOrVaultUpdated() {
        if (isDestroyed || activity.isDestroyed || activity.isFinishing) return
        stationedCountCache.clear()
        updateSearchHint()
        if (!DeviceTrustManager.hasIntelligenceAccess(activity)) {
            if (activePills.removeAll { isPersonSchemaToken(it) }) {
                renderPills()
            }
        }
        if (root.visibility == View.VISIBLE && !PriorityVault.isScreenOffOrLocked(activity)) {
            scheduleSearch()
        }
    }

    private fun commitTopFilterSuggestionOrRawToken(): Boolean {
        val raw = searchInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val picked = currentSuggestions.firstOrNull() ?: raw
        if (isPersonSchemaToken(picked) && !DeviceTrustManager.hasIntelligenceAccess(activity)) {
            return false
        }
        addFilterPill(picked)
        return true
    }

    private fun commitTopFilterOrHideIme() {
        val raw = searchInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val topSuggestion = currentSuggestions.firstOrNull()
        if (topSuggestion != null) {
            addFilterPill(topSuggestion)
        } else {
            // No filter pill matched: keep raw search active and let the user inspect full list
            scheduleSearch()
        }
    }

    /** Called when `Tab.SEARCH` becomes active: refreshes index and brings up the soft keyboard immediately. */
    fun onTabSelected() {
        updateSearchHint()
        renderPills()
        scheduleSearch()
        searchInput.isFocusable = true
        searchInput.isFocusableInTouchMode = true
        searchInput.requestFocus()
        showKeyboardNow()
        searchInput.post {
            if (root.visibility == View.VISIBLE) {
                searchInput.requestFocus()
                showKeyboardNow()
            }
        }
    }

    /** Called when switching away from `Tab.SEARCH` to `Monitor` or `Map`: hides the soft keyboard cleanly. */
    fun onTabUnselected() {
        hideKeyboardNow()
        searchInput.clearFocus()
    }

    private fun showKeyboardNow() {
        runCatching {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
        runCatching {
            WindowInsetsControllerCompat(activity.window, searchInput).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun hideKeyboardNow() {
        runCatching {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
            imm?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        runCatching {
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun renderPillsAndScheduleSearch() {
        renderPills()
        scheduleSearch()
    }

    private fun renderPills() {
        pillsRow.removeAllViews()
        if (activePills.isEmpty()) {
            pillsScroll.visibility = View.GONE
            return
        }
        pillsScroll.visibility = View.VISIBLE
        for (pill in activePills.toList()) {
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = KokohoJourneyUi.pillDrawable(
                    activity,
                    KokohoJourneyUi.HIGHLIGHT_AMBER,
                    Color.WHITE,
                    radiusDp = 999
                )
                setPadding(dp(12), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
                val labelTv = TextView(activity).apply {
                    text = pill
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(KokohoJourneyUi.SURFACE_DARK)
                }
                val dismissTv = TextView(activity).apply {
                    text = "  ✕"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(KokohoJourneyUi.SURFACE_DARK)
                    contentDescription = "Remove filter $pill"
                }
                addView(labelTv)
                addView(dismissTv)
                setOnClickListener {
                    activePills.removeAll { it.equals(pill, ignoreCase = true) }
                    renderPillsAndScheduleSearch()
                }
            }
            pillsRow.addView(chip)
        }
    }

    private fun renderSuggestions(suggestions: List<String>) {
        currentSuggestions = suggestions
        suggestionsRow.removeAllViews()
        if (suggestions.isEmpty()) {
            suggestionsScroll.visibility = View.GONE
            return
        }
        suggestionsScroll.visibility = View.VISIBLE
        for (sug in suggestions) {
            val sugChip = TextView(activity).apply {
                text = "+ $sug"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(KokohoJourneyUi.INK_PRIMARY)
                background = KokohoJourneyUi.pillDrawable(
                    activity,
                    KokohoJourneyUi.SURFACE_ELEVATED,
                    0x66FFFFFF,
                    radiusDp = 999
                )
                setPadding(dp(11), dp(5), dp(11), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    addFilterPill(sug)
                }
            }
            suggestionsRow.addView(sugChip)
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        queryGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        queryExecutor.shutdownNow()
        stationedCountCache.clear()
        iconBitmapCache.clear()
    }

    @VisibleForTesting
    internal fun awaitPendingQueriesForTest(drainMainLooper: () -> Unit = {}) {
        if (!isDestroyed && !queryExecutor.isShutdown) {
            runCatching { queryExecutor.submit {}.get(5, TimeUnit.SECONDS) }
        }
        drainMainLooper()
    }

    fun scheduleSearch() {
        if (isDestroyed || activity.isDestroyed || activity.isFinishing || queryExecutor.isShutdown) return
        if (PriorityVault.isScreenOffOrLocked(activity)) return
        val pillsSnapshot = activePills.toList()
        val rawQuery = searchInput.text?.toString()?.trim().orEmpty()
        val gen = queryGeneration.incrementAndGet()
        runCatching {
            queryExecutor.execute {
                if (isDestroyed || activity.isDestroyed || activity.isFinishing ||
                    gen != queryGeneration.get() || PriorityVault.isScreenOffOrLocked(activity)
                ) {
                    return@execute
                }
                val outcome = executeQuery(pillsSnapshot, rawQuery)
                if (isDestroyed || activity.isDestroyed || activity.isFinishing ||
                    gen != queryGeneration.get() || PriorityVault.isScreenOffOrLocked(activity)
                ) {
                    return@execute
                }
                mainHandler.post {
                    if (isDestroyed || activity.isDestroyed || activity.isFinishing ||
                        gen != queryGeneration.get() || PriorityVault.isScreenOffOrLocked(activity)
                    ) {
                        return@post
                    }
                    applyQueryOutcome(outcome, pillsSnapshot, rawQuery)
                }
            }
        }
    }

    /** Synchronous execution path for deterministic unit tests. */
    fun refreshNowSynchronously() {
        val pillsSnapshot = activePills.toList()
        val rawQuery = searchInput.text?.toString()?.trim().orEmpty()
        val gen = queryGeneration.incrementAndGet()
        val outcome = executeQuery(pillsSnapshot, rawQuery)
        if (gen == queryGeneration.get()) {
            applyQueryOutcome(outcome, pillsSnapshot, rawQuery)
        }
    }

    private data class QueryOutcome(
        val suggestions: List<String>,
        val rows: List<SearchResultRow>
    )

    private fun applyQueryOutcome(outcome: QueryOutcome, pills: List<String>, rawQuery: String) {
        updateSearchHint()
        var effectivePills = pills
        if (!DeviceTrustManager.hasIntelligenceAccess(activity) && activePills.removeAll { isPersonSchemaToken(it) }) {
            renderPills()
            effectivePills = activePills.toList()
        }
        // When rawQuery is non-empty, only show filter suggestions that actually match rawQuery;
        // when no filter matches, hide suggestions cleanly while raw search displays matching rows!
        renderSuggestions(outcome.suggestions)
        adapter.submitList(outcome.rows)
        val hasQuery = effectivePills.isNotEmpty() || rawQuery.isNotEmpty()
        summaryCountView.text = if (hasQuery) {
            "${outcome.rows.size} MATCHING ${if (outcome.rows.size == 1) "RECORD" else "RECORDS"}"
        } else {
            "BROWSE RECORDS (${outcome.rows.size})"
        }
        emptyStateView.visibility = if (outcome.rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun executeQuery(pills: List<String>, rawQuery: String): QueryOutcome {
        if (isDestroyed || PriorityVault.isScreenOffOrLocked(activity)) {
            return QueryOutcome(suggestions = emptyList(), rows = emptyList())
        }
        KokohoDirectory.ensureLoaded(activity)
        val hasIntel = DeviceTrustManager.hasIntelligenceAccess(activity)
        val curRev = PriorityVault.vaultDataRevision
        if (lastQueryVaultRevision != curRev || lastQueryIntelAccess != hasIntel) {
            stationedCountCache.clear()
            lastQueryVaultRevision = curRev
            lastQueryIntelAccess = hasIntel
        }

        val allLocations = KokohoDirectory.allLocations(activity)
        val allThreats = KokohoPostureEngine.allSearchableThreats(activity)
        val nowMs = kotlin.math.max(System.currentTimeMillis(), allThreats.maxOfOrNull { it.updatedAtMs } ?: 0L)
        val liveSnapshots = DeviceRegistry.summaries(0L)

        val existingLower = pills.map { it.lowercase(Locale.US) }.toSet()
        val suggestions = buildAutocompleteSuggestions(
            rawQuery = rawQuery,
            existingLower = existingLower,
            locations = allLocations,
            threats = allThreats
        )

        // Separate schema-class pills (`Detection`, `Location`, `Person`, `Device`) from content tokens
        val requestedSchemas = HashSet<SchemaKind>()
        val contentTokens = ArrayList<String>()

        for (pill in pills) {
            if (isPersonSchemaToken(pill) && !hasIntel) continue
            val schema = parseSchemaPill(pill, allowPerson = hasIntel)
            if (schema != null) {
                requestedSchemas += schema
            } else {
                contentTokens += pill.trim()
            }
        }
        if (rawQuery.isNotEmpty()) {
            // Raw query is NEVER treated as an exclusive schema lock if it doesn't match,
            // though if the user literally typed "person" or "location", let it match schema badge text too!
            contentTokens += rawQuery
        }

        val includeDetections = requestedSchemas.isEmpty() || SchemaKind.DETECTION in requestedSchemas
        val includeLocations = requestedSchemas.isEmpty() || SchemaKind.LOCATION in requestedSchemas
        val includePersons = hasIntel && (requestedSchemas.isEmpty() || SchemaKind.PERSON in requestedSchemas)
        val includePlates = requestedSchemas.isEmpty() || SchemaKind.PLATE in requestedSchemas
        val includeDevices = requestedSchemas.isEmpty() || SchemaKind.DEVICE in requestedSchemas

        val hasAnyFilter = pills.isNotEmpty() || rawQuery.isNotEmpty()
        val rows = ArrayList<SearchResultRow>(112)

        // 0. Synced License Plates (`KokohoHubClient.SyncedPlateRecord`)
        if (includePlates) {
            val allPlates = org.tucsoncommunity.kokoho.posture.KokohoHubClient.syncedPlates(activity)
            if (allPlates.isNotEmpty()) {
                val plateTokens = contentTokens.filterNot { parseSchemaPill(it) == SchemaKind.PLATE }
                val matchedPlates = if (plateTokens.isEmpty()) {
                    allPlates.take(if (requestedSchemas.size == 1) 120 else 20)
                } else {
                    allPlates.filter { p ->
                        plateTokens.all { tok ->
                            val low = tok.trim().lowercase(Locale.US)
                            p.plateNumber.lowercase(Locale.US).contains(low) ||
                                p.stateCode.lowercase(Locale.US).contains(low) ||
                                p.suspectedAgency.lowercase(Locale.US).contains(low) ||
                                p.agencyCode.lowercase(Locale.US).contains(low) ||
                                p.color.lowercase(Locale.US).contains(low) ||
                                p.bodyType.lowercase(Locale.US).contains(low) ||
                                p.make.lowercase(Locale.US).contains(low) ||
                                p.model.lowercase(Locale.US).contains(low) ||
                                p.details.lowercase(Locale.US).contains(low)
                        }
                    }.take(80)
                }
                for (p in matchedPlates) {
                    val ag = KokohoAgency.fromCode(p.agencyCode)
                    val vehDesc = listOf(p.color, p.make, p.model, p.bodyType)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    val detStr = if (p.detected) " · ✓ Wireless" else ""
                    rows += SearchResultRow(
                        stableId = "plate:${p.plateId}",
                        schema = SchemaKind.PLATE,
                        title = "${p.plateNumber} (${p.stateCode})",
                        metaLine = "Plate · ${p.suspectedAgency.ifBlank { ag?.friendlyLabel ?: "Unknown" }} · ${p.confidencePct}%$detStr",
                        detailLine = listOf(vehDesc, p.details).filter { it.isNotBlank() }.joinToString(" · "),
                        targetDetailKey = "plate:${p.plateId}",
                        agency = ag,
                        detectionIconKind = MapIcons.BadgeIconKind.CAR
                    )
                }
            }
        }

        // 1. Detections (`EvaluatedThreat`)
        if (includeDetections) {
            val matchedThreats = if (contentTokens.isEmpty()) {
                allThreats.take(if (requestedSchemas.size == 1) 80 else 25)
            } else {
                allThreats.filter { t -> contentTokens.all { tok -> threatMatchesToken(t, tok) } }.take(60)
            }
            for (t in matchedThreats) {
                rows += toDetectionRow(t, nowMs)
            }
        }

        // 2. Facility / Station Locations (`FacilityLocation`)
        if (includeLocations) {
            val matchedLocations = if (contentTokens.isEmpty()) {
                // Prioritize federal/major stations first when browsing empty query
                allLocations.sortedWith(
                    compareBy<KokohoDirectory.FacilityLocation> {
                        if (it.locationGroup.equals("FEDERAL", true) || it.agency.isDhs) 0 else 1
                    }.thenBy { it.agency.priorityRank }.thenBy { it.title }
                ).take(if (requestedSchemas.size == 1) 120 else 25)
            } else {
                allLocations.filter { loc -> contentTokens.all { tok -> locationMatchesToken(loc, tok) } }
                    .sortedWith(
                        compareBy<KokohoDirectory.FacilityLocation> {
                            if (rawQuery.isNotEmpty() && it.title.startsWith(rawQuery, ignoreCase = true)) 0 else 1
                        }.thenBy { it.agency.priorityRank }.thenBy { it.title }
                    )
                    .take(60)
            }
            for (loc in matchedLocations) {
                rows += toLocationRow(loc)
            }
        }

        // 3. Personnel (`PersonRecord`: searches witness person records & any packaged directory records)
        if (includePersons) {
            val personTokens = contentTokens.filterNot { parseSchemaPill(it) == SchemaKind.PERSON }
            val matchedPersons = if (personTokens.isEmpty()) {
                KokohoDirectory.browsePersons(activity, limit = if (requestedSchemas.size == 1) 60 else 15)
            } else {
                KokohoDirectory.searchPersonsWithTokens(activity, personTokens, limit = 50)
            }
            for (p in matchedPersons) {
                rows += toPersonRow(p)
            }
        }

        // 4. Devices & Constituent Signatures (`DeviceRegistry.Snapshot` + Threat `EvidenceBadge`s)
        if (includeDevices) {
            val seenDeviceKeys = HashSet<String>()
            // 4a. Live observed snapshots
            val candidateSnaps = liveSnapshots.sortedWith(
                compareByDescending<DeviceRegistry.Snapshot> { it.hits.isNotEmpty() || !it.name.isNullOrBlank() }
                    .thenByDescending { it.lastSeen }
            )
            val matchedSnaps = if (contentTokens.isEmpty()) {
                candidateSnaps.filter { it.hits.isNotEmpty() || !it.name.isNullOrBlank() }
                    .take(if (requestedSchemas.size == 1) 60 else if (!hasAnyFilter) 12 else 25)
            } else {
                candidateSnaps.filter { s -> contentTokens.all { tok -> snapshotMatchesToken(s, tok) } }.take(40)
            }
            for (s in matchedSnaps) {
                val normKey = s.mac.uppercase(Locale.US)
                if (seenDeviceKeys.add(normKey)) {
                    rows += toLiveDeviceRow(s, nowMs)
                }
            }

            // 4b. Constituent hardware/RF devices attached to detections (searchable even when offline/historical!)
            if (contentTokens.isNotEmpty() || requestedSchemas.contains(SchemaKind.DEVICE)) {
                for (t in allThreats) {
                    for (b in t.evidenceSources) {
                        if (b.facetKey.isBlank() || b.facetKey.startsWith("witness:", true)) continue
                        val rawMac = b.facetKey.substringAfter("hw:mac:", "")
                            .ifEmpty { b.facetKey.substringAfter("hw:kit:", "") }
                            .uppercase(Locale.US)
                        val dedupeKey = rawMac.ifEmpty { b.facetKey.uppercase(Locale.US) }
                        if (dedupeKey in seenDeviceKeys) continue
                        val matchesAll = contentTokens.isEmpty() || contentTokens.all { tok ->
                            val low = tok.trim().lowercase(Locale.US)
                            "device".contains(low) ||
                                b.facetKey.lowercase(Locale.US).contains(low) ||
                                b.detail.lowercase(Locale.US).contains(low) ||
                                b.kind.lowercase(Locale.US).contains(low) ||
                                t.agency.friendlyLabel.lowercase(Locale.US).contains(low) ||
                                t.agency.id.lowercase(Locale.US).contains(low)
                        }
                        if (matchesAll && seenDeviceKeys.add(dedupeKey)) {
                            rows += SearchResultRow(
                                stableId = "dev-facet:$dedupeKey",
                                schema = SchemaKind.DEVICE,
                                title = b.detail.ifBlank { b.facetKey },
                                metaLine = "Device · ${t.agency.friendlyLabel} · ${KokohoPostureEngine.formatStaleness(nowMs, b.lastSeenMs.takeIf { it > 0L } ?: t.updatedAtMs)}",
                                detailLine = "${b.facetKey} · Linked to ${t.label.ifBlank { t.friendlyLabel }}",
                                targetDetailKey = "device:${b.facetKey}",
                                agency = t.agency,
                                detectionIconKind = if (b.facetKey.startsWith("rf:", true)) MapIcons.BadgeIconKind.RADIO else MapIcons.BadgeIconKind.CAR
                            )
                            if (seenDeviceKeys.size >= 60) break
                        }
                    }
                    if (seenDeviceKeys.size >= 60) break
                }
            }
        }

        // Rank exact/prefix title hits right to the top when rawQuery is active!
        val rankedRows = if (rawQuery.isNotBlank()) {
            val qLow = rawQuery.lowercase(Locale.US)
            rows.sortedWith(
                compareBy<SearchResultRow> { row ->
                    val tLow = row.title.lowercase(Locale.US)
                    when {
                        tLow == qLow -> 0
                        tLow.startsWith(qLow) -> 1
                        tLow.contains(qLow) -> 2
                        else -> 3
                    }
                }.thenBy { it.schema.ordinal }
            )
        } else {
            rows
        }

        return QueryOutcome(suggestions = suggestions, rows = rankedRows)
    }

    private fun parseSchemaPill(
        token: String,
        allowPerson: Boolean = DeviceTrustManager.hasIntelligenceAccess(activity)
    ): SchemaKind? = when (token.trim().lowercase(Locale.US)) {
        "detection", "detections", "entity", "entities" -> SchemaKind.DETECTION
        "location", "locations", "station", "stations", "facility", "facilities" -> SchemaKind.LOCATION
        "person", "persons", "personnel", "people" -> if (allowPerson) SchemaKind.PERSON else null
        "plate", "plates", "vehicle", "vehicles" -> SchemaKind.PLATE
        "device", "devices", "hardware" -> SchemaKind.DEVICE
        else -> null
    }

    private fun buildAutocompleteSuggestions(
        rawQuery: String,
        existingLower: Set<String>,
        locations: List<KokohoDirectory.FacilityLocation>,
        threats: List<EvaluatedThreat>
    ): List<String> {
        val q = rawQuery.trim().lowercase(Locale.US)
        val hasIntel = DeviceTrustManager.hasIntelligenceAccess(activity)
        val hasPersons = hasIntel && KokohoDirectory.isPersonnelDirectoryAvailable(activity)
        val hasPlates = org.tucsoncommunity.kokoho.posture.KokohoHubClient.syncedPlates(activity).isNotEmpty()

        val baseFilters = buildList {
            add("Detection")
            add("Location")
            if (hasPersons) add("Person")
            if (hasPlates) add("Plate")
            add("Device")
            add("ICE")
            add("CBP")
            add("HSI")
            add("DHS Air")
            add("FPS")
            add("Police")
            add("Sheriff")
            add("Fire")
            add("Tactical Drone")
            add("Confirmed")
            add("Likely")
            add("Suspected")
        }

        if (q.isEmpty()) {
            return baseFilters.filter { it.lowercase(Locale.US) !in existingLower }.take(10)
        }

        val prefixMatches = LinkedHashSet<String>()
        val containsMatches = LinkedHashSet<String>()

        fun consider(candidate: String) {
            val clean = candidate.trim()
            if (clean.isEmpty() || clean.length > 42) return
            val low = clean.lowercase(Locale.US)
            if (low in existingLower) return
            if (low.startsWith(q)) {
                prefixMatches += clean
            } else if (q.length >= 2 && low.contains(q)) {
                containsMatches += clean
            }
        }

        baseFilters.forEach(::consider)
        KokohoAgency.entries.forEach { consider(it.friendlyLabel) }
        for (loc in locations) {
            consider(loc.title)
        }
        for (t in threats) {
            for (cap in t.capabilities) consider(cap)
        }
        if (hasIntel) {
            for (personSug in KokohoDirectory.suggestPersonPills(activity, q, existingLower, limit = 4)) {
                consider(personSug)
            }
        }

        return (prefixMatches + containsMatches).take(8)
    }

    private fun threatMatchesToken(t: EvaluatedThreat, rawToken: String): Boolean {
        val tok = rawToken.trim().lowercase(Locale.US)
        if (tok.isEmpty()) return true
        if ("detection".contains(tok) || "entity".contains(tok)) return true
        if (t.label.lowercase(Locale.US).contains(tok)) return true
        if (t.title.lowercase(Locale.US).contains(tok)) return true
        if (t.recurrenceSummary.lowercase(Locale.US).contains(tok)) return true
        if (t.agency.friendlyLabel.lowercase(Locale.US).contains(tok)) return true
        if (t.agency.id.lowercase(Locale.US).contains(tok)) return true
        if (tok == "police" && t.agency == KokohoAgency.POLICE) return true
        if (tok == "sheriff" && t.agency == KokohoAgency.SHERIFF) return true
        if (tok == "fire" && t.agency == KokohoAgency.FIRE) return true
        if (tok == "drone" && (t.isDrone || t.agency == KokohoAgency.DRONE)) return true
        if (t.uuid.lowercase(Locale.US).contains(tok)) return true
        if (t.hardwareIdentifier?.lowercase(Locale.US)?.contains(tok) == true) return true
        if (t.capabilities.any { it.lowercase(Locale.US).contains(tok) }) return true
        if (t.evidenceSources.any {
                it.detail.lowercase(Locale.US).contains(tok) ||
                    it.facetKey.lowercase(Locale.US).contains(tok) ||
                    it.kind.lowercase(Locale.US).contains(tok)
            }
        ) return true
        return false
    }

    private fun locationMatchesToken(loc: KokohoDirectory.FacilityLocation, rawToken: String): Boolean {
        val tok = rawToken.trim().lowercase(Locale.US)
        if (tok.isEmpty()) return true
        if ("location".contains(tok) || "station".contains(tok) || "facility".contains(tok)) return true
        if (loc.title.lowercase(Locale.US).contains(tok)) return true
        if (loc.address.lowercase(Locale.US).contains(tok)) return true
        if (loc.description.lowercase(Locale.US).contains(tok)) return true
        if (loc.agency.friendlyLabel.lowercase(Locale.US).contains(tok)) return true
        if (loc.agencyCode.lowercase(Locale.US).contains(tok)) return true
        if (loc.locationGroup.lowercase(Locale.US).contains(tok)) return true
        if (tok == "police" && loc.agency == KokohoAgency.POLICE) return true
        if (tok == "sheriff" && loc.agency == KokohoAgency.SHERIFF) return true
        if (tok == "fire" && (loc.locationGroup.equals("FIRE", true) || loc.agency == KokohoAgency.FIRE)) return true
        return false
    }

    private fun snapshotMatchesToken(s: DeviceRegistry.Snapshot, rawToken: String): Boolean {
        val tok = rawToken.trim().lowercase(Locale.US)
        if (tok.isEmpty()) return true
        if ("device".contains(tok)) return true
        if (s.mac.lowercase(Locale.US).contains(tok)) return true
        if (s.name?.lowercase(Locale.US)?.contains(tok) == true) return true
        if (s.vendor?.lowercase(Locale.US)?.contains(tok) == true) return true
        if (s.deviceType.lowercase(Locale.US).contains(tok)) return true
        if (s.hits.any { it.label.lowercase(Locale.US).contains(tok) || it.evidence.lowercase(Locale.US).contains(tok) }) return true
        if (s.facts.any { it.first.lowercase(Locale.US).contains(tok) || it.second.lowercase(Locale.US).contains(tok) }) return true
        return false
    }

    private fun toDetectionRow(t: EvaluatedThreat, nowMs: Long): SearchResultRow {
        val hasVehicleOrKit = t.evidenceSources.any {
            it.facetKey.startsWith("hw:mac:", true) ||
                it.kind == "WIFI_BLE_OUI" ||
                it.kind.startsWith("COMPANION_")
        }
        val iconKind = when {
            t.isDrone -> MapIcons.BadgeIconKind.DRONE
            t.isAircraft || t.agency == KokohoAgency.DHS_AMO -> {
                if (t.isHelicopter) MapIcons.BadgeIconKind.HELICOPTER else MapIcons.BadgeIconKind.SMALL_PLANE
            }
            hasVehicleOrKit -> MapIcons.BadgeIconKind.CAR
            else -> MapIcons.BadgeIconKind.RADIO
        }
        val radStr = KokohoPostureEngine.formatAccuracyRadius(activity, t.uncertaintyRadiusM)
        val ageStr = KokohoPostureEngine.formatStaleness(nowMs, t.updatedAtMs)
        val hits = t.sightingCount.coerceAtLeast(1)
        val detailParts = ArrayList<String>(3)
        if (t.recurrenceSummary.isNotBlank() && !t.recurrenceSummary.equals(t.label, ignoreCase = true)) {
            detailParts += t.recurrenceSummary
        }
        t.evidenceSources.firstOrNull()?.detail?.takeIf { it.isNotBlank() && it !in detailParts }?.let {
            detailParts += it
        }
        if (t.capabilities.isNotEmpty()) {
            detailParts += t.capabilities.joinToString(", ")
        }
        return SearchResultRow(
            stableId = "det:${t.uuid}",
            schema = SchemaKind.DETECTION,
            title = t.label.ifBlank { t.title.ifBlank { t.friendlyLabel } },
            metaLine = "Detection · ${t.agency.friendlyLabel} · $radStr · ${hits}x · $ageStr",
            detailLine = detailParts.joinToString(" · ").ifBlank { "${t.combinedConfidence}% confidence" },
            targetDetailKey = "kokoho:${t.uuid}",
            agency = t.agency,
            detectionIconKind = iconKind
        )
    }

    private fun toLocationRow(loc: KokohoDirectory.FacilityLocation): SearchResultRow {
        val isFire = loc.locationGroup.equals("FIRE", ignoreCase = true) ||
            loc.title.contains("Fire", ignoreCase = true) ||
            loc.description.contains("Fire", ignoreCase = true)
        val count = if (DeviceTrustManager.hasIntelligenceAccess(activity)) {
            stationedCountCache.getOrPut(loc.id) {
                KokohoDirectory.stationedPersonnelCount(activity, loc.id)
            }
        } else {
            0
        }
        val countSuffix = if (count > 0) " · $count stationed" else ""
        val detail = listOf(loc.address, loc.description)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        return SearchResultRow(
            stableId = "loc:${loc.id}",
            schema = SchemaKind.LOCATION,
            title = loc.title,
            metaLine = "Location · ${loc.agency.friendlyLabel}$countSuffix",
            detailLine = detail,
            targetDetailKey = KokohoDirectory.canonicalLocationId(loc.id),
            agency = loc.agency,
            isFireLocation = isFire
        )
    }

    private fun toPersonRow(p: KokohoDirectory.PersonRecord): SearchResultRow {
        val badgePart = p.badgeNumber.takeIf { it.isNotBlank() }?.let { " · Badge $it" }.orEmpty()
        val detail = listOf(
            p.title,
            p.suite,
            p.streetAddress,
            p.email.ifBlank { p.primaryPhone.ifBlank { p.mobilePhone } }
        ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        return SearchResultRow(
            stableId = "person:${p.personId}",
            schema = SchemaKind.PERSON,
            title = p.fullName,
            metaLine = "Person · ${p.resolvedAgencyDisplay}$badgePart",
            detailLine = detail.ifBlank { p.stateCode },
            targetDetailKey = "person:${p.personId}",
            agency = p.agency,
            personAvatarSeed = "person:${p.personId}"
        )
    }

    private fun toLiveDeviceRow(s: DeviceRegistry.Snapshot, nowMs: Long): SearchResultRow {
        val title = s.name?.takeIf { it.isNotBlank() }
            ?: s.best?.label?.takeIf { it.isNotBlank() }
            ?: s.mac
        val srcLabel = s.sources.joinToString("/") { it.name }
        val ageStr = KokohoPostureEngine.formatStaleness(nowMs, s.lastSeen)
        val detail = listOfNotNull(
            s.mac.takeIf { !it.equals(title, ignoreCase = true) },
            s.vendor?.takeIf { it.isNotBlank() },
            s.deviceType.takeIf { it.isNotBlank() && it != "Device" },
            "${s.rssi} dBm (${s.sightings}x)"
        ).joinToString(" · ")
        return SearchResultRow(
            stableId = "dev:${s.mac}",
            schema = SchemaKind.DEVICE,
            title = title,
            metaLine = "Device · $srcLabel · $ageStr",
            detailLine = detail,
            targetDetailKey = "device:${s.mac}",
            agency = null,
            detectionIconKind = MapIcons.BadgeIconKind.RADIO
        )
    }

    private fun resolveRowBitmap(row: SearchResultRow): Bitmap {
        val sizePx = dp(40)
        return when (row.schema) {
            SchemaKind.PERSON -> {
                val seed = row.personAvatarSeed ?: row.stableId
                iconBitmapCache.getOrPut("avatar:$seed:$sizePx") {
                    org.tucsoncommunity.kokoho.posture.KokohoViews.renderAgentAvatarBitmap(activity, seed, null, sizePx)
                }
            }
            SchemaKind.LOCATION -> {
                val bg = if (row.isFireLocation) 0xFFD84315.toInt() else (row.agency?.mapColorArgb ?: 0xFF37474F.toInt())
                val key = "loc:$bg:${row.isFireLocation}:${density}"
                iconBitmapCache.getOrPut(key) {
                    MapIcons.facilityBadgeIcon(density * 1.35f, bg, row.isFireLocation)
                }
            }
            SchemaKind.DETECTION -> {
                val bg = row.agency?.mapColorArgb ?: 0xFF455A64.toInt()
                val key = "det:${row.detectionIconKind.id}:$bg:${density}"
                iconBitmapCache.getOrPut(key) {
                    MapIcons.classificationBadgeIcon(density * 1.35f, row.detectionIconKind, bg)
                }
            }
            SchemaKind.PLATE, SchemaKind.DEVICE -> {
                val bg = row.agency?.mapColorArgb ?: 0xFF37474F.toInt()
                val key = "dev:${row.detectionIconKind.id}:$bg:${density}"
                iconBitmapCache.getOrPut(key) {
                    MapIcons.classificationBadgeIcon(density * 1.35f, row.detectionIconKind, bg)
                }
            }
        }
    }

    private fun openRowDetails(row: SearchResultRow) {
        when {
            row.targetDetailKey.startsWith("plate:", ignoreCase = true) -> {
                val pid = row.targetDetailKey.removePrefix("plate:")
                val plate = org.tucsoncommunity.kokoho.posture.KokohoHubClient.syncedPlates(activity)
                    .firstOrNull { it.plateId == pid }
                if (plate != null) {
                    val veh = listOf(plate.color, plate.make, plate.model, plate.bodyType)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { "-" }
                    KokohoJourneyUi.showModal(
                        activity = activity,
                        title = "${plate.plateNumber} (${plate.stateCode})",
                        subtitle = "${plate.suspectedAgency} · ${plate.confidencePct}% confidence" +
                            if (plate.partialPlate) " · Partial plate" else ""
                    ) { body, dismiss ->
                        body.addView(KokohoJourneyUi.buildActionRow(
                            activity,
                            "Vehicle",
                            veh
                        ) {})
                        body.addView(KokohoJourneyUi.buildActionRow(
                            activity,
                            "Wireless Association",
                            if (plate.detected) "✓ Linked wireless entity (${plate.entityUuid ?: "active"})" else "No wireless entity linked yet"
                        ) {
                            val eu = plate.entityUuid
                            if (!eu.isNullOrBlank()) {
                                dismiss()
                                activity.startActivity(
                                    Intent(activity, DeviceDetailActivity::class.java)
                                        .putExtra(DeviceDetailActivity.EXTRA_MAC, "kokoho:$eu")
                                )
                            }
                        })
                        if (plate.observedDate.isNotBlank()) {
                            body.addView(KokohoJourneyUi.buildActionRow(activity, "Observed Date", plate.observedDate) {})
                        }
                        if (plate.details.isNotBlank()) {
                            body.addView(KokohoJourneyUi.buildActionRow(activity, "Details", plate.details) {})
                        }
                    }
                }
            }
            row.targetDetailKey.startsWith("loc:", ignoreCase = true) ->
                DeviceDetailActivity.openLocation(activity, row.targetDetailKey)
            row.targetDetailKey.startsWith("person:", ignoreCase = true) -> {
                if (DeviceTrustManager.hasIntelligenceAccess(activity)) {
                    DeviceDetailActivity.openPerson(activity, row.targetDetailKey)
                }
            }
            else ->
                activity.startActivity(
                    Intent(activity, DeviceDetailActivity::class.java)
                        .putExtra(DeviceDetailActivity.EXTRA_MAC, row.targetDetailKey)
                )
        }
    }

    private inner class SearchAdapter : RecyclerView.Adapter<SearchAdapter.RowHolder>() {
        var currentList: List<SearchResultRow> = emptyList()
            private set

        fun submitList(next: List<SearchResultRow>) {
            val prev = currentList
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = prev.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    prev[oldItemPosition].stableId == next[newItemPosition].stableId
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    prev[oldItemPosition] == next[newItemPosition]
            })
            currentList = next
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = currentList.size
        inner class RowHolder(
            val card: LinearLayout,
            val iconView: ImageView,
            val titleView: TextView,
            val metaView: TextView,
            val detailView: TextView
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val iconView = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                }
            }

            val titleView = TextView(activity).apply {
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val metaView = TextView(activity).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }

            val detailView = TextView(activity).apply {
                textSize = 12.5f
                setTextColor(KokohoJourneyUi.INK_SECONDARY)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleView)
                addView(metaView)
                addView(detailView)
            }

            val chevron = TextView(activity).apply {
                text = "›"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(KokohoJourneyUi.INK_MUTED)
                setPadding(dp(8), 0, 0, 0)
            }

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = KokohoJourneyUi.cardDrawable(
                    activity,
                    KokohoJourneyUi.SURFACE_CARD,
                    KokohoJourneyUi.BORDER_SUBTLE,
                    14
                )
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                addView(iconView)
                addView(textCol)
                addView(chevron)
            }

            return RowHolder(card, iconView, titleView, metaView, detailView)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = currentList[position]
            holder.iconView.setImageBitmap(resolveRowBitmap(row))
            holder.titleView.text = row.title
            holder.metaView.text = row.metaLine
            if (row.detailLine.isNotBlank()) {
                holder.detailView.text = row.detailLine
                holder.detailView.visibility = View.VISIBLE
            } else {
                holder.detailView.visibility = View.GONE
            }
            holder.card.setOnClickListener {
                openRowDetails(row)
            }
        }
    }
}
