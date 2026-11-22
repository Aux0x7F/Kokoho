package org.tucsoncommunity.kokoho.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import org.tucsoncommunity.kokoho.posture.DeviceTrustManager
import org.tucsoncommunity.kokoho.posture.EvaluatedThreat
import org.tucsoncommunity.kokoho.posture.EvidenceBadge
import org.tucsoncommunity.kokoho.posture.FieldTelemetryLogger
import org.tucsoncommunity.kokoho.posture.KokohoAgency
import org.tucsoncommunity.kokoho.posture.KokohoDirectory
import org.tucsoncommunity.kokoho.posture.KokohoHubClient
import org.tucsoncommunity.kokoho.posture.KokohoPostureEngine
import org.tucsoncommunity.kokoho.posture.KokohoSignatures
import org.tucsoncommunity.kokoho.posture.KokohoViews
import org.tucsoncommunity.kokoho.posture.PostureColor
import org.tucsoncommunity.kokoho.sdr.RadioLog
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.usb.ProbeIntel
import org.tucsoncommunity.kokoho.util.applySystemBarInsets
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Unified Breadcrumb Details Activity (`v1.3.x`):
 *
 * Supports four navigable detail node types in one seamless breadcrumb stack:
 *  1. `Entity` (`kokoho:<uuid>`): Read-only highest-order dynamic title (`Suspected` / `Likely` / `Confirmed`),
 *     tap-to-expand photo carousel, 1/4-screen Map, coordinates, Witness/Report summary, and Constituent Devices list.
 *  2. `Device` (`device:<macOrFacet>`): Full constituent device telemetry (MAC/Frequency, OUI/Sub-block,
 *     802.11 TSF uptime `upSince`, capabilities, BLE company/service UUIDs, RSSI/SNR, probes).
 *  3. `Location` (`loc:<locationId>`): Title (type `Location`), address, unstructured description, and
 *     (when stationed personnel exist) a filterable table (`Full Name | Title | Agency | Suite`) with
 *     `Tab` / `Enter` autocomplete into independently dismissible `(✕)` filter pills.
 *  4. `Person` (`person:<personId>`): Structured person fields (blank when empty) plus tappable
 *     `Managed by` and `Manages` links when present.
 *
 * Navigation invariants:
 *  - `←` (or system Back) steps backward one breadcrumb; at root, returns to Map preserving selection.
 *  - `✕` dismisses the entire breadcrumb stack straight back to Map preserving selection.
 */
class DeviceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAC = "mac"
        private val RADIO_KEY = Regex("""^RADIO (\d+(?:\.\d+)?) MHz$""")

        fun radioHz(key: String): Long? =
            RADIO_KEY.find(key)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it * 1_000_000) }

        fun openLocation(activity: Context, locationId: String) {
            val key = KokohoDirectory.canonicalLocationId(locationId)
            activity.startActivity(
                Intent(activity, DeviceDetailActivity::class.java).putExtra(EXTRA_MAC, key)
            )
        }

        fun openPerson(activity: Context, personId: String) {
            val key = "person:" + KokohoDirectory.canonicalPersonId(personId)
            activity.startActivity(
                Intent(activity, DeviceDetailActivity::class.java).putExtra(EXTRA_MAC, key)
            )
        }
    }

    sealed class DetailNode {
        data class Entity(val uuid: String) : DetailNode()
        data class ConstituentDevice(val facetOrMac: String, val badge: EvidenceBadge? = null) : DetailNode()
        data class Location(val locationId: String) : DetailNode()
        data class Person(val personId: String) : DetailNode()
    }

    private val breadcrumbStack = ArrayDeque<DetailNode>()

    private lateinit var rootLayout: LinearLayout
    private lateinit var headerHandle: KokohoJourneyUi.SubjourneyHeaderHandle
    lateinit var classificationText: TextView
        private set
    lateinit var avatarImageView: ImageView
        private set
    lateinit var mapContainer: FrameLayout
        private set
    private lateinit var bodyScroll: ScrollView
    private lateinit var bodyContent: LinearLayout

    private var mapEngine: MapEngine? = null
    private var rfMap: RfMap? = null
    private var mapTargetLat: Double = 32.2226
    private var mapTargetLon: Double = -110.9747
    private var mapTargetRadiusM: Int = 250
    private var mapTargetColorArgb: Int = KokohoAgency.DHS_CBP.mapColorArgb
    private var mapTargetAgencyId: String = KokohoAgency.DHS_CBP.id
    private var mapTargetPinpointed: Boolean = false
    private var mapTargetIsFacility: Boolean = false
    private var mapTargetLabel: String = ""
    private var mapTargetIconKind: MapIcons.BadgeIconKind = MapIcons.BadgeIconKind.CAR
    private var mapTargetTrackPoints: List<Pair<Double, Double>> = emptyList()
    private var unregisterTrustListener: (() -> Unit)? = null
    private var lastIntelAccess: Boolean = false

    private var pendingPhotoCallback: ((String) -> Unit)? = null
    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            encodeUriToDataUrl(uri)?.let { encoded ->
                pendingPhotoCallback?.invoke(encoded)
            }
        }
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KokohoSignatures.ensureLoaded(this)
        KokohoDirectory.ensureLoaded(this)

        val rawKey = intent.getStringExtra(EXTRA_MAC)?.trim()
        if (rawKey.isNullOrEmpty()) {
            finish()
            return
        }

        FieldTelemetryLogger.recordUserInteraction(
            context = this,
            action = "OPEN_DETAILS",
            targetKey = rawKey
        )

        val initialNode = parseInitialNode(rawKey)
        breadcrumbStack.push(initialNode)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBreadcrumbBack()
            }
        })

        val statusBgColor = KokohoPostureEngine.currentPostureColorArgb(this)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(statusBgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.applySystemBarInsets()

        headerHandle = KokohoJourneyUi.createSubjourneyHeader(
            activity = this,
            initialTitle = "Details",
            showCloseAll = true,
            onBack = { navigateBreadcrumbBack() },
            onCloseAll = { finish() },
            menuItemsProvider = { buildContextMenuForCurrentNode() }
        )
        rootLayout.addView(headerHandle.root)

        // Shared Top Identity Banner (Read-Only Highest-Order Title + Optional Tappable Photo/Avatar)
        val identityBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        avatarImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        classificationText = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        identityBanner.addView(avatarImageView)
        identityBanner.addView(classificationText)
        rootLayout.addView(identityBanner)

        // Shared Quarter-Screen Map Preview (Shown for Entity & Location nodes; hidden on Person/Device nodes)
        val quarterScreenPx = (resources.displayMetrics.heightPixels / 4).coerceAtLeast(dp(165))
        mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                quarterScreenPx
            )
            setBackgroundColor(0xFF0F172A.toInt())
        }
        rootLayout.addView(mapContainer)

        runCatching {
            val engine = MapEngine.create(this)
            mapContainer.addView(
                engine.view,
                0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            engine.onCreate(savedInstanceState)
            mapEngine = engine
            engine.whenReady {
                if (isFinishing || isDestroyed || mapEngine !== engine) return@whenReady
                engine.setMinZoom(2.0)
                val map = RfMap(this, engine).also { rfMap = it }
                engine.jumpTo(mapTargetLat, mapTargetLon, zoomForRadius(mapTargetRadiusM))
                map.loadStyle(dark = true) {
                    if (!isFinishing && !isDestroyed) redrawMiniMap()
                }
            }
        }

        bodyScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        bodyContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }
        bodyScroll.addView(bodyContent)
        rootLayout.addView(bodyScroll)

        setContentView(rootLayout)
        lastIntelAccess = DeviceTrustManager.hasIntelligenceAccess(this)
        val selfRef = java.lang.ref.WeakReference(this)
        unregisterTrustListener = DeviceTrustManager.addTrustStateListener {
            val act = selfRef.get()
            if (act == null || act.isDestroyed || act.isFinishing) {
                unregisterTrustListener?.invoke()
                unregisterTrustListener = null
            } else {
                val nowIntel = DeviceTrustManager.hasIntelligenceAccess(act)
                if (nowIntel != act.lastIntelAccess) {
                    act.lastIntelAccess = nowIntel
                    val top = act.breadcrumbStack.peek()
                    if (top is DetailNode.Person || top is DetailNode.Location || top is DetailNode.Entity) {
                        act.renderCurrentNode()
                    }
                }
            }
        }
        renderCurrentNode()
    }

    override fun onStart() {
        super.onStart()
        runCatching { mapEngine?.onStart() }
    }

    override fun onResume() {
        super.onResume()
        runCatching { mapEngine?.onResume() }
    }

    override fun onPause() {
        runCatching { mapEngine?.onPause() }
        super.onPause()
    }

    override fun onStop() {
        runCatching { mapEngine?.onStop() }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterTrustListener?.invoke()
        unregisterTrustListener = null
        runCatching { mapEngine?.onDestroy() }
        mapEngine = null
        rfMap = null
        super.onDestroy()
    }

    private fun parseInitialNode(rawKey: String): DetailNode = when {
        rawKey.startsWith("loc:", ignoreCase = true) -> DetailNode.Location(KokohoDirectory.canonicalLocationId(rawKey))
        rawKey.startsWith("person:", ignoreCase = true) -> DetailNode.Person(KokohoDirectory.canonicalPersonId(rawKey))
        rawKey.startsWith("device:", ignoreCase = true) -> DetailNode.ConstituentDevice(rawKey.substring(7).trim())
        rawKey.startsWith("kokoho:", ignoreCase = true) -> DetailNode.Entity(KokohoSignatures.normalizeEntityId(rawKey))
        KokohoDirectory.locationById(this, rawKey) != null -> DetailNode.Location(KokohoDirectory.canonicalLocationId(rawKey))
        else -> {
            // Direct MAC or radio key: check if an entity exists, otherwise open ConstituentDevice
            val threat = KokohoPostureEngine.findThreatByUuid(this, rawKey)
            if (threat != null) DetailNode.Entity(threat.uuid)
            else DetailNode.ConstituentDevice(rawKey)
        }
    }

    fun pushNode(node: DetailNode) {
        if (breadcrumbStack.peek() == node) return
        breadcrumbStack.push(node)
        bodyScroll.scrollTo(0, 0)
        renderCurrentNode()
    }

    fun navigateBreadcrumbBack() {
        if (breadcrumbStack.size > 1) {
            breadcrumbStack.pop()
            bodyScroll.scrollTo(0, 0)
            renderCurrentNode()
        } else {
            finish()
        }
    }

    private fun renderCurrentNode() {
        val current = breadcrumbStack.peek() ?: run {
            finish()
            return
        }
        headerHandle.closeAllButton.visibility = View.VISIBLE
        bodyContent.removeAllViews()

        when (current) {
            is DetailNode.Entity -> renderEntityNode(current.uuid)
            is DetailNode.ConstituentDevice -> renderDeviceNode(current.facetOrMac, current.badge)
            is DetailNode.Location -> renderLocationNode(current.locationId)
            is DetailNode.Person -> renderPersonNode(current.personId)
        }
    }

    // -------------------------------------------------------------------------
    // 1. ENTITY DETAILS VIEW
    // -------------------------------------------------------------------------
    private fun renderEntityNode(uuid: String) {
        headerHandle.titleView.text = if (breadcrumbStack.size > 1) "Entity Details (${breadcrumbStack.size})" else "Entity Details"
        val nowMs = System.currentTimeMillis()
        val threat = KokohoPostureEngine.findThreatByUuid(this, uuid, nowMs)
        val cachedTrack = org.tucsoncommunity.kokoho.posture.KokohoHubClient.cachedFlightTracks()
            .filter { it.entityUuid.equals(uuid, ignoreCase = true) }
            .maxByOrNull { it.endedAtMs }
        val witness = KokohoSignatures.getWitnessReport(this, uuid, approvedOnly = false)
        val photos = witness?.allPhotos().orEmpty()

        val agency = witness?.takeIf { it.reportKind == "LE" }?.agencyCode?.let { KokohoAgency.fromCode(it) }
            ?: threat?.agency
            ?: cachedTrack?.category?.let { KokohoAgency.fromCode(it) }
            ?: KokohoAgency.DHS_CBP
        val highestTitle = when {
            threat != null -> KokohoSignatures.formatHighestOrderTitle(threat, witness)
            witness != null && KokohoSignatures.hasConfirmingReport(witness) -> {
                val sub = witness.agentName.ifBlank { witness.vehicleNumber.ifBlank { witness.accessoryKind.ifBlank { agency.friendlyLabel } } }
                "Confirmed ${agency.friendlyLabel}: $sub"
            }
            cachedTrack != null && cachedTrack.label.isNotBlank() -> cachedTrack.label
            cachedTrack != null -> "${agency.friendlyLabel} Aircraft"
            else -> "Suspected ${agency.friendlyLabel}"
        }

        classificationText.text = highestTitle
        avatarImageView.visibility = View.VISIBLE
        avatarImageView.setImageBitmap(KokohoViews.renderAgentAvatarBitmap(this, uuid, photos.firstOrNull(), dp(52)))
        avatarImageView.setOnClickListener {
            if (photos.isNotEmpty()) {
                KokohoJourneyUi.showPhotoCarouselModal(this, photos, 0)
            } else {
                Toast.makeText(this, "No photos attached yet. Use Submit Report to attach photos.", Toast.LENGTH_SHORT).show()
            }
        }

        val trackAnchor = cachedTrack?.points?.lastOrNull()
        val lat = threat?.lat ?: witness?.lat ?: trackAnchor?.first
        val lon = threat?.lon ?: witness?.lon ?: trackAnchor?.second
        val isAirOrTrack = threat?.isAircraft == true || (threat == null && cachedTrack != null && agency != KokohoAgency.DRONE)
        val isDroneEntity = threat?.isDrone == true || agency == KokohoAgency.DRONE
        mapTargetIconKind = when {
            isDroneEntity -> MapIcons.BadgeIconKind.DRONE
            isAirOrTrack && (threat?.isHelicopter == true || KokohoSignatures.isHelicopterAirframe(null, agency.id, highestTitle, agency)) ->
                MapIcons.BadgeIconKind.HELICOPTER
            isAirOrTrack -> MapIcons.BadgeIconKind.SMALL_PLANE
            else -> MapIcons.BadgeIconKind.CAR
        }
        mapTargetTrackPoints = cachedTrack?.points?.takeIf { it.size >= 2 }
            ?.let { org.tucsoncommunity.kokoho.online.PoliceAircraft.chaikinSmooth(it, 2) }
            .orEmpty()
        if (lat != null && lon != null) {
            mapContainer.visibility = View.VISIBLE
            mapTargetLat = lat
            mapTargetLon = lon
            mapTargetRadiusM = threat?.uncertaintyRadiusM ?: if (cachedTrack != null) 25 else 40
            mapTargetColorArgb = agency.mapColorArgb
            mapTargetAgencyId = agency.id
            mapTargetPinpointed = threat?.isPinpointed ?: (cachedTrack != null)
            mapTargetIsFacility = false
            mapTargetLabel = highestTitle
            redrawMiniMap()
        } else {
            mapContainer.visibility = View.GONE
        }

        // Photo Carousel Strip (if photos exist, show tappable count hint)
        if (photos.isNotEmpty()) {
            val photoHint = buildInfoBadgePill("${photos.size} ${if (photos.size == 1) "Photo" else "Photos"}: Tap thumbnail above to expand") {
                KokohoJourneyUi.showPhotoCarouselModal(this, photos, 0)
            }
            bodyContent.addView(photoHint)
        }

        // Spatial & Epistemic Summary Card
        val metaCard = buildCard()
        addCardHeader(metaCard, "POSITION & CONFIDENCE")
        val coordLine = if (lat != null && lon != null) {
            String.format(Locale.US, "%.5f, %.5f  (Tap to copy)", lat, lon)
        } else {
            "Proximity only (awaiting GPS fix)"
        }
        val coordTv = TextView(this).apply {
            text = coordLine
            textSize = 14.5f
            typeface = Typeface.MONOSPACE
            setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
            setOnClickListener {
                if (lat != null && lon != null) copyToClipboard("Coordinates", String.format(Locale.US, "%.5f, %.5f", lat, lon))
            }
        }
        metaCard.addView(coordTv)

        val updatedMs = threat?.updatedAtMs ?: witness?.updatedAtMs ?: cachedTrack?.endedAtMs?.takeIf { it > 0L } ?: nowMs
        val ageText = KokohoPostureEngine.formatStalenessAge(nowMs - updatedMs)
        val accText = KokohoPostureEngine.formatAccuracyRadius(this, threat?.uncertaintyRadiusM ?: if (cachedTrack != null) 25 else 40)
        val confPct = threat?.combinedConfidence ?: 90
        addKeyValueRow(metaCard, "Agency", agency.fullName)
        threat?.altitudeFtAgl?.let { altFt ->
            addKeyValueRow(metaCard, "Altitude", if (isDroneEntity) "$altFt ft AGL" else "$altFt ft MSL")
        }
        addKeyValueRow(metaCard, "Accuracy", accText)
        addKeyValueRow(metaCard, "Last Detected", ageText)
        val confirmedEvidence = threat?.let { KokohoSignatures.isExactEvidenceConfirmed(it, witness) } == true
        addKeyValueRow(metaCard, "Confidence", "$confPct% (${KokohoSignatures.epistemicPrefix(confPct, witness, evidenceConfirmedBefore = confirmedEvidence)})")
        if (!threat?.capabilities.isNullOrEmpty()) {
            addKeyValueRow(metaCard, "Capabilities", threat!!.capabilities.joinToString(" · "))
        }
        bodyContent.addView(metaCard)

        // Witness / Report Card + Linked Person Record
        if (witness != null) {
            val repCard = buildCard()
            val headerLabel = when (witness.reportKind) {
                "FALSE_POSITIVE" -> "FALSE POSITIVE REPORT"
                "BUG" -> "BUG REPORT"
                else -> if (witness.isApproved) "CONFIRMED FIELD REPORT" else "FIELD REPORT (PENDING SYNC)"
            }
            addCardHeader(repCard, headerLabel)
            if (witness.agentName.isNotBlank()) addKeyValueRow(repCard, "Agent", witness.agentName)
            if (witness.titleOrRole.isNotBlank()) addKeyValueRow(repCard, "Title / Role", witness.titleOrRole)
            if (witness.badgeNumber.isNotBlank()) addKeyValueRow(repCard, "Badge #", witness.badgeNumber)
            if (witness.vehicleNumber.isNotBlank()) addKeyValueRow(repCard, "Vehicle / Fleet #", witness.vehicleNumber)
            if (witness.plateNumber.isNotBlank()) addKeyValueRow(repCard, "Plate / Tail #", witness.plateNumber)
            if (witness.accessoryKind.isNotBlank()) addKeyValueRow(repCard, "Accessory", witness.accessoryKind)
            if (witness.notes.isNotBlank()) addKeyValueRow(repCard, "Notes", witness.notes)

            val linkedPid = witness.personId
            val linkedPerson = if (DeviceTrustManager.hasIntelligenceAccess(this)) {
                linkedPid?.let { KokohoDirectory.personById(this, it) }
            } else {
                null
            }
            if (linkedPerson != null) {
                val personBtn = KokohoJourneyUi.secondaryActionButton(
                    this,
                    "Open Person Profile: ${linkedPerson.fullName}  ▸"
                ) {
                    pushNode(DetailNode.Person(linkedPerson.personId))
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                repCard.addView(personBtn)
            }
            bodyContent.addView(repCard)
        }

        // Submit / Refine Report Action Button
        val submitReportBtn = KokohoJourneyUi.primaryActionButton(this, "Submit Report") {
            KokohoJourneyUi.showActionSheetModal(
                this,
                "Submit Report",
                listOf(
                    KokohoJourneyUi.SubjourneyMenuItem("Confirm/Refine") {
                        openWizardFromDetails(KokohoReportWizard.Mode.CONFIRM_REFINE, uuid, agency, lat, lon)
                    },
                    KokohoJourneyUi.SubjourneyMenuItem("False Positive") {
                        openWizardFromDetails(KokohoReportWizard.Mode.FALSE_POSITIVE, uuid, agency, lat, lon)
                    },
                    KokohoJourneyUi.SubjourneyMenuItem("Bug Report") {
                        openWizardFromDetails(KokohoReportWizard.Mode.BUG_REPORT, uuid, agency, lat, lon)
                    }
                )
            )
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        bodyContent.addView(submitReportBtn)

        // Constituent Devices Section (Every device informing this entity opens its own Device Details page!)
        val devicesCard = buildCard()
        addCardHeader(devicesCard, "CONSTITUENT DEVICES & SIGNALS")
        val badges = threat?.evidenceSources.orEmpty()
        if (badges.isEmpty()) {
            devicesCard.addView(
                TextView(this).apply {
                    text = "Point report (no passive RF/BLE/Wi-Fi constituent devices attached)."
                    textSize = 13.5f
                    setTextColor(KokohoJourneyUi.INK_SECONDARY)
                }
            )
        } else {
            for (badge in badges) {
                val devRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = KokohoJourneyUi.cardDrawable(this@DeviceDetailActivity, KokohoJourneyUi.SURFACE_ELEVATED, 0x55FFFFFF, 10)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                    setOnClickListener {
                        pushNode(DetailNode.ConstituentDevice(badge.facetKey, badge))
                    }
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val dTitle = TextView(this).apply {
                    text = badge.detail.ifBlank { badge.facetKey }
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                val dSub = TextView(this).apply {
                    val age = KokohoPostureEngine.formatStalenessAge(nowMs - badge.lastSeenMs)
                    text = "${badge.kind} · ${badge.facetKey} · $age"
                    textSize = 12.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
                    setPadding(0, dp(2), 0, 0)
                }
                val chev = TextView(this).apply {
                    text = "▸"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
                    setPadding(dp(8), 0, 0, 0)
                }
                col.addView(dTitle)
                col.addView(dSub)
                devRow.addView(col)
                devRow.addView(chev)
                devicesCard.addView(devRow)
            }
        }
        bodyContent.addView(devicesCard)
    }

    // -------------------------------------------------------------------------
    // 2. CONSTITUENT DEVICE DETAILS VIEW
    // -------------------------------------------------------------------------
    private fun renderDeviceNode(facetOrMac: String, badge: EvidenceBadge?) {
        headerHandle.titleView.text = "Device Details"
        avatarImageView.visibility = View.GONE
        mapContainer.visibility = View.GONE

        val cleanMac = facetOrMac
            .removePrefix("hw:mac:")
            .removePrefix("hw:kit:bwc:")
            .removePrefix("hw:kit:printer:")
            .removePrefix("hw:kit:radio:")
            .removePrefix("hw:kit:acc:")
            .removePrefix("hw:kit:")
            .removePrefix("uas:mac:")
            .trim()

        val snap = DeviceRegistry.get(cleanMac)
            ?: DeviceRegistry.snapshot().firstOrNull { KokohoSignatures.normalizeMac(it.mac) == KokohoSignatures.normalizeMac(cleanMac) }

        val rfHz = if (facetOrMac.startsWith("rf:chan:")) {
            facetOrMac.removePrefix("rf:chan:").substringBefore(':').toLongOrNull()
        } else {
            radioHz(facetOrMac)
        }
        val rfEntry = rfHz?.let { hz -> RadioLog.current().firstOrNull { kotlin.math.abs(it.freqHz - hz) <= 6_250L } }

        val displayTitle = when {
            snap != null -> {
                val n = snap.name?.takeIf { it.isNotBlank() }
                val v = snap.vendor?.takeIf { it.isNotBlank() }
                listOfNotNull(n, v).joinToString(" · ").ifBlank { cleanMac }
            }
            rfHz != null -> String.format(Locale.US, "%.4f MHz Radio Signal", rfHz / 1_000_000.0)
            badge != null && badge.detail.isNotBlank() -> badge.detail
            else -> facetOrMac
        }
        classificationText.text = displayTitle

        val card = buildCard()
        addCardHeader(card, "CONSTITUENT DEVICE TELEMETRY")
        addKeyValueRow(card, "Facet / Key", facetOrMac)

        if (snap != null) {
            val normMac = KokohoSignatures.normalizeMac(snap.mac)
            val subblock = org.tucsoncommunity.kokoho.posture.KokohoReferenceTables.lookupMacSubblock(normMac)
            addKeyValueRow(card, "MAC / BSSID", normMac)
            addKeyValueRow(card, "Radio Source", snap.source.name)
            addKeyValueRow(card, "SSID / BLE Name", snap.name ?: "-")
            addKeyValueRow(card, "IEEE Vendor", subblock?.vendor ?: snap.vendor ?: "-")
            if (subblock != null) {
                addKeyValueRow(card, "IEEE Sub-Block", "${subblock.prefixHex} (/${subblock.bitLen} · ${subblock.role})")
            }
            addKeyValueRow(card, "Signal Strength", "${snap.rssi} dBm (peak ${snap.bestRssi} dBm)")
            addKeyValueRow(card, "Sightings", "${snap.sightings}")

            val wifi = snap.advert?.wifi
            if (wifi != null) {
                addKeyValueRow(card, "Wi-Fi Channel", "${wifi.frequencyMhz} MHz (${if (wifi.client) "Client Station" else "Access Point"})")
                addKeyValueRow(card, "Capabilities", wifi.capabilities.ifBlank { "-" })
                val upSince = snap.upSince ?: wifi.upSince
                if (upSince != null && snap.lastSeen > upSince) {
                    val upHrs = (snap.lastSeen - upSince) / 3_600_000.0
                    addKeyValueRow(card, "Beacon TSF Uptime", String.format(Locale.US, "%.1f hours", upHrs))
                }
            }
            val adv = snap.advert
            if (adv != null && adv.isBle) {
                addKeyValueRow(card, "BLE Address Type", adv.addressType.label)
                if (adv.manufacturerData.isNotEmpty()) {
                    val mfgIds = adv.manufacturerData.keys.joinToString(", ") { String.format("0x%04X", it) }
                    addKeyValueRow(card, "BLE Company IDs", mfgIds)
                }
                if (adv.serviceUuids.isNotEmpty()) {
                    val uuids = adv.serviceUuids.joinToString(", ") { u ->
                        org.tucsoncommunity.kokoho.detect.Advert.shortOf(u)?.let { String.format("0x%04X", it) } ?: u.toString()
                    }
                    addKeyValueRow(card, "BLE Service UUIDs", uuids)
                }
            }
            val probes = ProbeIntel.of(snap.mac)?.probed.orEmpty()
            if (probes.isNotEmpty()) {
                addKeyValueRow(card, "Probed Networks", probes.joinToString(", "))
            }
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            addKeyValueRow(card, "First Seen", fmt.format(Date(snap.firstSeen)))
            addKeyValueRow(card, "Last Seen", fmt.format(Date(snap.lastSeen)))
        } else if (rfHz != null) {
            val sig = KokohoSignatures.matchFrequency(rfHz, includeRepeaters = true)
            addKeyValueRow(card, "Frequency", String.format(Locale.US, "%.4f MHz", rfHz / 1_000_000.0))
            if (sig != null) {
                addKeyValueRow(card, "Channel Alpha", sig.alpha)
                addKeyValueRow(card, "Description", sig.description)
                addKeyValueRow(card, "Role", sig.role)
                addKeyValueRow(card, "Mode / NAC", "${sig.mode}${sig.nac?.let { " · NAC $it" } ?: ""}")
            }
            if (rfEntry != null) {
                addKeyValueRow(card, "Peak SNR", "${rfEntry.peakSnrDb} dB")
                addKeyValueRow(card, "Along-Track Span", "${rfEntry.travelSpanM.toInt()} m")
            }
        } else if (badge != null) {
            addKeyValueRow(card, "Signal Type", badge.kind)
            addKeyValueRow(card, "Summary", badge.detail)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            if (badge.lastSeenMs > 0L) addKeyValueRow(card, "Last Seen", fmt.format(Date(badge.lastSeenMs)))
        }
        bodyContent.addView(card)
    }

    // -------------------------------------------------------------------------
    // 3. LOCATION DETAILS VIEW + FILTERABLE STATIONED PERSONNEL TABLE
    // -------------------------------------------------------------------------
    private fun renderLocationNode(locationId: String) {
        headerHandle.titleView.text = "Location Details"
        val loc = KokohoDirectory.locationById(this, locationId) ?: run {
            avatarImageView.visibility = View.GONE
            mapContainer.visibility = View.GONE
            classificationText.text = "Unknown Location"
            return
        }

        avatarImageView.visibility = View.GONE
        classificationText.text = loc.title

        mapContainer.visibility = View.VISIBLE
        mapTargetLat = loc.lat
        mapTargetLon = loc.lon
        mapTargetRadiusM = 45
        mapTargetColorArgb = loc.agency.mapColorArgb
        mapTargetAgencyId = loc.agency.id
        mapTargetPinpointed = true
        mapTargetIsFacility = true
        mapTargetLabel = loc.title
        redrawMiniMap()

        // Type Badge ("Location" + Agency)
        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        badgeRow.addView(buildTypeTag("Location", KokohoJourneyUi.HIGHLIGHT_AMBER, KokohoJourneyUi.SURFACE_DARK))
        badgeRow.addView(
            buildTypeTag(loc.agency.friendlyLabel, KokohoJourneyUi.SURFACE_ELEVATED, Color.WHITE).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
        )
        bodyContent.addView(badgeRow)

        // Address & Unstructured Description Card
        val infoCard = buildCard()
        addCardHeader(infoCard, "FACILITY DETAILS")
        addKeyValueRow(infoCard, "Address", loc.address.ifBlank { "-" })
        val coordStr = String.format(Locale.US, "%.5f, %.5f", loc.lat, loc.lon)
        val coordRow = TextView(this).apply {
            text = "Coordinates: $coordStr  (Tap to copy)"
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
            setPadding(0, dp(4), 0, dp(6))
            setOnClickListener { copyToClipboard("Coordinates", coordStr) }
        }
        infoCard.addView(coordRow)

        if (loc.description.isNotBlank()) {
            val descTv = TextView(this).apply {
                text = loc.description
                textSize = 14f
                setTextColor(KokohoJourneyUi.INK_PRIMARY)
                setPadding(0, dp(4), 0, 0)
            }
            infoCard.addView(descTv)
        }
        bodyContent.addView(infoCard)

        // Stationed Personnel Filterable Table (Rendered when Intelligence access is held and personnel exist)
        val totalStationed = if (DeviceTrustManager.hasIntelligenceAccess(this)) {
            KokohoDirectory.stationedPersonnelCount(this, loc.id)
        } else {
            0
        }
        if (totalStationed > 0) {
            val tableCard = buildCard()
            val tableHeaderTv = addCardHeader(tableCard, "STATIONED PERSONNEL ($totalStationed)")

            val activePills = ArrayList<String>()
            val pillsScroll = HorizontalScrollView(this).apply {
                visibility = View.GONE
                setPadding(0, 0, 0, dp(6))
            }
            val pillsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pillsScroll.addView(pillsRow)

            val filterInput = EditText(this).apply {
                hint = "Filter Name, Title, Agency, Suite (Tab / Enter adds pill)…"
                textSize = 14f
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(Color.WHITE)
                setHintTextColor(KokohoJourneyUi.INK_MUTED)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = KokohoJourneyUi.cardDrawable(this@DeviceDetailActivity, KokohoJourneyUi.SURFACE_DARK, KokohoJourneyUi.BORDER_SUBTLE, 10)
            }

            val suggestionsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(4), 0, dp(4))
            }

            val rowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            fun rebuildTableAndSuggestions() {
                // 1. Render active dismissible (✕) pills
                pillsRow.removeAllViews()
                pillsScroll.visibility = if (activePills.isEmpty()) View.GONE else View.VISIBLE
                for (pillText in activePills.toList()) {
                    val pill = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = KokohoJourneyUi.pillDrawable(
                            this@DeviceDetailActivity,
                            KokohoJourneyUi.HIGHLIGHT_AMBER,
                            Color.WHITE
                        )
                        setPadding(dp(10), dp(5), dp(8), dp(5))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(6) }
                    }
                    val pLabel = TextView(this).apply {
                        text = pillText
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(KokohoJourneyUi.SURFACE_DARK)
                    }
                    val pDismiss = TextView(this).apply {
                        text = "  ✕"
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(KokohoJourneyUi.SURFACE_DARK)
                        setOnClickListener {
                            activePills.remove(pillText)
                            rebuildTableAndSuggestions()
                        }
                    }
                    pill.addView(pLabel)
                    pill.addView(pDismiss)
                    pillsRow.addView(pill)
                }

                // 2. Render autocomplete suggestions
                val liveQ = filterInput.text?.toString()?.trim().orEmpty()
                suggestionsContainer.removeAllViews()
                val suggestions = if (liveQ.isNotEmpty()) {
                    KokohoDirectory.suggestTableFilterTokens(this, loc.id, liveQ, activePills, limit = 5)
                } else emptyList()

                if (suggestions.isNotEmpty()) {
                    suggestionsContainer.visibility = View.VISIBLE
                    val sugWrap = HorizontalScrollView(this)
                    val sugRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for (sug in suggestions) {
                        val chip = TextView(this).apply {
                            text = "+ $sug"
                            textSize = 12.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
                            background = KokohoJourneyUi.pillDrawable(
                                this@DeviceDetailActivity,
                                KokohoJourneyUi.SURFACE_ELEVATED,
                                KokohoJourneyUi.HIGHLIGHT_AMBER
                            )
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(6) }
                            setOnClickListener {
                                if (activePills.none { it.equals(sug, ignoreCase = true) }) {
                                    activePills += sug
                                }
                                filterInput.setText("")
                                rebuildTableAndSuggestions()
                            }
                        }
                        sugRow.addView(chip)
                    }
                    sugWrap.addView(sugRow)
                    suggestionsContainer.addView(sugWrap)
                } else {
                    suggestionsContainer.visibility = View.GONE
                }

                // 3. Render filtered table rows (`Full Name | Title | Agency | Suite`: City/State strictly excluded)
                val filtered = KokohoDirectory.stationedPersonnel(this, loc.id, activePills, liveQ)
                tableHeaderTv.text = "STATIONED PERSONNEL (${filtered.size} / $totalStationed)"
                rowsContainer.removeAllViews()

                // Column Header Row
                val colHead = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = KokohoJourneyUi.cardDrawable(this@DeviceDetailActivity, KokohoJourneyUi.SURFACE_DARK, 0x44FFFFFF, 8)
                }
                fun headCell(label: String, weight: Float): TextView = TextView(this).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                        marginEnd = dp(4)
                    }
                }
                colHead.addView(headCell("Full Name", 1.35f))
                colHead.addView(headCell("Title", 1.35f))
                colHead.addView(headCell("Agency", 0.65f))
                colHead.addView(headCell("Suite", 0.75f))
                rowsContainer.addView(colHead)

                if (filtered.isEmpty()) {
                    rowsContainer.addView(
                        TextView(this).apply {
                            text = "No personnel match active filters."
                            textSize = 13.5f
                            setTextColor(KokohoJourneyUi.INK_SECONDARY)
                            setPadding(dp(8), dp(12), dp(8), dp(8))
                        }
                    )
                } else {
                    for ((idx, p) in filtered.withIndex()) {
                        val tr = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(8), dp(9), dp(8), dp(9))
                            setBackgroundColor(if (idx % 2 == 0) 0x22000000 else 0x44000000)
                            setOnClickListener {
                                pushNode(DetailNode.Person(p.personId))
                            }
                        }
                        fun bodyCell(value: String, weight: Float, bold: Boolean = false): TextView = TextView(this).apply {
                            text = value.ifBlank { "-" }
                            textSize = 12.5f
                            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            setTextColor(if (bold) Color.WHITE else KokohoJourneyUi.INK_SECONDARY)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                                marginEnd = dp(4)
                            }
                        }
                        tr.addView(bodyCell(p.fullName, 1.35f, bold = true))
                        tr.addView(bodyCell(p.title, 1.35f))
                        tr.addView(bodyCell(p.resolvedAgencyDisplay, 0.65f))
                        tr.addView(bodyCell(p.suite, 0.75f))
                        rowsContainer.addView(tr)
                    }
                }
            }

            fun commitCurrentTokenOrFirstSuggestion(): Boolean {
                val raw = filterInput.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) return false
                val best = KokohoDirectory.suggestTableFilterTokens(this, loc.id, raw, activePills, limit = 1)
                    .firstOrNull() ?: raw
                if (activePills.none { it.equals(best, ignoreCase = true) }) {
                    activePills += best
                }
                filterInput.setText("")
                rebuildTableAndSuggestions()
                return true
            }

            filterInput.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    commitCurrentTokenOrFirstSuggestion()
                    true
                } else {
                    false
                }
            }
            filterInput.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                    commitCurrentTokenOrFirstSuggestion()
                } else {
                    false
                }
            }
            filterInput.doAfterTextChanged { rebuildTableAndSuggestions() }

            tableCard.addView(pillsScroll)
            tableCard.addView(filterInput)
            tableCard.addView(suggestionsContainer)
            tableCard.addView(
                FrameLayout(this).apply {
                    setPadding(0, dp(8), 0, 0)
                    addView(rowsContainer)
                }
            )
            rebuildTableAndSuggestions()
            bodyContent.addView(tableCard)
        }
    }

    // -------------------------------------------------------------------------
    // 4. PERSON DETAILS VIEW + MANAGED BY / MANAGES ORG NAVIGATION
    // -------------------------------------------------------------------------
    private fun renderPersonNode(personId: String) {
        headerHandle.titleView.text = "Person Details"
        avatarImageView.visibility = View.VISIBLE
        mapContainer.visibility = View.GONE

        if (!DeviceTrustManager.hasIntelligenceAccess(this)) {
            avatarImageView.visibility = View.GONE
            classificationText.text = "Access Restricted"
            return
        }

        val person = KokohoDirectory.personById(this, personId) ?: run {
            classificationText.text = "Person Not Found"
            return
        }

        classificationText.text = person.fullName
        avatarImageView.setImageBitmap(KokohoViews.renderAgentAvatarBitmap(this, person.personId, null, dp(52)))
        avatarImageView.setOnClickListener(null)

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        badgeRow.addView(buildTypeTag("Person", KokohoJourneyUi.HIGHLIGHT_AMBER, KokohoJourneyUi.SURFACE_DARK))
        badgeRow.addView(
            buildTypeTag(person.resolvedAgencyDisplay, KokohoJourneyUi.SURFACE_ELEVATED, Color.WHITE).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
        )
        bodyContent.addView(badgeRow)

        val detailsCard = buildCard()
        addCardHeader(detailsCard, "PERSONNEL RECORD")
        addBlankSafePersonField(detailsCard, "Full Name", person.fullName)
        addBlankSafePersonField(detailsCard, "Title", person.title)
        addBlankSafePersonField(detailsCard, "Agency", person.resolvedAgencyDisplay)
        addBlankSafePersonField(detailsCard, "Badge #", person.badgeNumber)
        addBlankSafePersonField(detailsCard, "Suite", person.suite)
        addBlankSafePersonField(detailsCard, "Street Address", person.streetAddress)
        addBlankSafePersonField(detailsCard, "Email", person.email, copyable = true)
        if (person.secondEmail.isNotBlank()) {
            addBlankSafePersonField(detailsCard, "Secondary Email", person.secondEmail, copyable = true)
        }
        addBlankSafePersonField(detailsCard, "Work Phone", person.primaryPhone, copyable = true)
        addBlankSafePersonField(detailsCard, "Mobile Phone", person.mobilePhone, copyable = true)

        val stationedLoc = person.locationId.takeIf { it.isNotBlank() }?.let { KokohoDirectory.locationById(this, it) }
        if (stationedLoc != null) {
            val locBtn = KokohoJourneyUi.secondaryActionButton(
                this,
                "Stationed Location: ${stationedLoc.title}  ▸"
            ) {
                pushNode(DetailNode.Location(stationedLoc.id))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            detailsCard.addView(locBtn)
        }
        bodyContent.addView(detailsCard)

        // Managed By & Manages (Displayed ONLY when non-empty!)
        val manager = KokohoDirectory.managedBy(this, person.personId)
        val directReports = KokohoDirectory.manages(this, person.personId)

        if (manager != null) {
            val mgrCard = buildCard()
            addCardHeader(mgrCard, "MANAGED BY")
            mgrCard.addView(buildPersonLinkRow(manager))
            bodyContent.addView(mgrCard)
        }

        if (directReports.isNotEmpty()) {
            val repCard = buildCard()
            addCardHeader(repCard, "MANAGES (${directReports.size})")
            for (rep in directReports) {
                repCard.addView(buildPersonLinkRow(rep))
            }
            bodyContent.addView(repCard)
        }

        // Associated Field Reports / Detections for this Person
        val linkedReports = KokohoSignatures.getAllWitnessReports(this).filter {
            it.personId == person.personId || it.agentName.equals(person.fullName, ignoreCase = true)
        }
        if (linkedReports.isNotEmpty()) {
            val assocCard = buildCard()
            addCardHeader(assocCard, "ASSOCIATED DETECTIONS (${linkedReports.size})")
            for (wr in linkedReports) {
                val entId = wr.entityUuid ?: wr.targetKey
                val btn = KokohoJourneyUi.secondaryActionButton(
                    this,
                    "Detection ${entId.take(8)} · ${KokohoPostureEngine.formatStalenessAge(System.currentTimeMillis() - wr.updatedAtMs)}  ▸"
                ) {
                    pushNode(DetailNode.Entity(entId))
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                assocCard.addView(btn)
            }
            bodyContent.addView(assocCard)
        }
    }

    private fun buildPersonLinkRow(p: KokohoDirectory.PersonRecord): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = KokohoJourneyUi.cardDrawable(this@DeviceDetailActivity, KokohoJourneyUi.SURFACE_ELEVATED, 0x55FFFFFF, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnClickListener { pushNode(DetailNode.Person(p.personId)) }

        val col = LinearLayout(this@DeviceDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameTv = TextView(this@DeviceDetailActivity).apply {
            text = p.fullName
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val subTv = TextView(this@DeviceDetailActivity).apply {
            text = listOf(p.title, p.resolvedAgencyDisplay, p.suite).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12.5f
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
        }
        val chev = TextView(this@DeviceDetailActivity).apply {
            text = "▸"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
        }
        col.addView(nameTv)
        if (subTv.text.isNotBlank()) col.addView(subTv)
        addView(col)
        addView(chev)
    }

    private fun buildContextMenuForCurrentNode(): List<KokohoJourneyUi.SubjourneyMenuItem> {
        val node = breadcrumbStack.peek() ?: return emptyList()
        return when (node) {
            is DetailNode.Entity -> {
                val uuid = node.uuid
                val isWatched = KokohoSignatures.isEntityWatched(this, uuid)
                val isWhitelisted = KokohoSignatures.isEntityWhitelisted(this, uuid)
                listOf(
                    KokohoJourneyUi.SubjourneyMenuItem(if (isWatched) "Remove Watch Priority" else "Pin / Watch Entity") {
                        KokohoSignatures.setEntityWatched(this, uuid, !isWatched)
                        Toast.makeText(this, if (!isWatched) "Entity pinned" else "Watch removed", Toast.LENGTH_SHORT).show()
                        renderCurrentNode()
                    },
                    KokohoJourneyUi.SubjourneyMenuItem(if (isWhitelisted) "Restore Suppressed Entity" else "Suppress / Hide Entity") {
                        KokohoSignatures.setEntityWhitelisted(this, uuid, !isWhitelisted)
                        Toast.makeText(this, if (!isWhitelisted) "Entity suppressed" else "Entity restored", Toast.LENGTH_SHORT).show()
                        if (!isWhitelisted) finish() else renderCurrentNode()
                    },
                    KokohoJourneyUi.SubjourneyMenuItem("Copy Coordinates") {
                        copyToClipboard("Coordinates", String.format(Locale.US, "%.5f, %.5f", mapTargetLat, mapTargetLon))
                    }
                )
            }
            is DetailNode.Location -> {
                val loc = KokohoDirectory.locationById(this, node.locationId)
                listOfNotNull(
                    loc?.let {
                        KokohoJourneyUi.SubjourneyMenuItem("Copy Facility Address") {
                            copyToClipboard("Address", "${it.title}: ${it.address}")
                        }
                    },
                    loc?.let {
                        KokohoJourneyUi.SubjourneyMenuItem("Submit Sighting Report Here") {
                            openWizardFromDetails(KokohoReportWizard.Mode.LAW_ENFORCEMENT, null, it.agency, it.lat, it.lon)
                        }
                    },
                    loc?.let {
                        KokohoJourneyUi.SubjourneyMenuItem("Submit Bug Report Here") {
                            openWizardFromDetails(KokohoReportWizard.Mode.BUG_REPORT, null, it.agency, it.lat, it.lon)
                        }
                    }
                )
            }
            is DetailNode.Person -> {
                val p = if (DeviceTrustManager.hasIntelligenceAccess(this)) {
                    KokohoDirectory.personById(this, node.personId)
                } else {
                    null
                }
                listOfNotNull(
                    p?.takeIf { it.email.isNotBlank() }?.let {
                        KokohoJourneyUi.SubjourneyMenuItem("Copy Email") { copyToClipboard("Email", it.email) }
                    },
                    p?.let {
                        KokohoJourneyUi.SubjourneyMenuItem("Copy Name & Title") {
                            copyToClipboard("Person", "${it.fullName}: ${it.title}")
                        }
                    }
                )
            }
            is DetailNode.ConstituentDevice -> listOf(
                KokohoJourneyUi.SubjourneyMenuItem("Copy Device Key") {
                    copyToClipboard("Device Key", node.facetOrMac)
                }
            )
        }
    }

    private fun openWizardFromDetails(
        mode: KokohoReportWizard.Mode,
        existingUuid: String?,
        agency: KokohoAgency,
        lat: Double?,
        lon: Double?
    ) {
        KokohoReportWizard.open(
            activity = this,
            mode = mode,
            existingEntityUuid = existingUuid,
            initialAgency = agency,
            droppedLat = lat,
            droppedLon = lon,
            requestPickPhoto = { cb ->
                pendingPhotoCallback = cb
                pickPhotoLauncher.launch("image/*")
            },
            onSubmitted = { entityKey ->
                if (mode == KokohoReportWizard.Mode.BUG_REPORT && existingUuid == null) {
                    Toast.makeText(this, "Bug report submitted. Thank you!", Toast.LENGTH_SHORT).show()
                    renderCurrentNode()
                    return@open
                }
                val newUuid = KokohoSignatures.normalizeEntityId(entityKey)
                if (breadcrumbStack.peek() is DetailNode.Entity && (breadcrumbStack.peek() as DetailNode.Entity).uuid == newUuid) {
                    renderCurrentNode()
                } else {
                    pushNode(DetailNode.Entity(newUuid))
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // UI & Map Helpers
    // -------------------------------------------------------------------------
    private fun buildCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = KokohoJourneyUi.cardDrawable(this@DeviceDetailActivity, 0x480B111E, 0x66FFFFFF, 14)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun addCardHeader(card: LinearLayout, title: String): TextView = TextView(this).apply {
        text = title
        textSize = 12.5f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
        setPadding(0, 0, 0, dp(6))
        card.addView(this)
    }

    private fun addKeyValueRow(card: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val k = TextView(this).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val v = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(k)
        row.addView(v)
        card.addView(row)
    }

    /** Shows structured Person fields cleanly, leaving the value blank when empty per spec. */
    private fun addBlankSafePersonField(card: LinearLayout, label: String, value: String, copyable: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            if (copyable && value.isNotBlank()) {
                setOnClickListener { copyToClipboard(label, value) }
            }
        }
        val k = TextView(this).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val v = TextView(this).apply {
            text = value // Intentionally blank ("") when empty
            textSize = 14f
            setTextColor(if (copyable && value.isNotBlank()) KokohoJourneyUi.HIGHLIGHT_AMBER else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(k)
        row.addView(v)
        card.addView(row)
    }

    private fun buildTypeTag(textStr: String, bgArgb: Int, inkArgb: Int): TextView = TextView(this).apply {
        text = textStr
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(inkArgb)
        background = KokohoJourneyUi.pillDrawable(this@DeviceDetailActivity, bgArgb, Color.WHITE)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun buildInfoBadgePill(textStr: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textStr
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(KokohoJourneyUi.HIGHLIGHT_AMBER)
        gravity = Gravity.CENTER
        background = KokohoJourneyUi.pillDrawable(this@DeviceDetailActivity, 0x550F172A, KokohoJourneyUi.HIGHLIGHT_AMBER)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun redrawMiniMap() {
        val map = rfMap ?: return
        val engine = mapEngine ?: return
        if (mapTargetTrackPoints.size >= 2) {
            engine.fit(mapTargetTrackPoints, dp(24))
        } else {
            engine.jumpTo(mapTargetLat, mapTargetLon, zoomForRadius(mapTargetRadiusM))
        }
        map.safe("detailMap") {
            if (mapTargetIsFacility) {
                map.drawPins(emptyList())
                map.drawTrace(emptyList(), RfMap.TRACE_COLOR)
                map.drawLocations()
            } else {
                val hexCol = String.format("#%06X", mapTargetColorArgb and 0xFFFFFF)
                map.drawTrace(mapTargetTrackPoints, hexCol)
                val pin = MapPin(
                    mac = "detail-preview",
                    lat = mapTargetLat,
                    lon = mapTargetLon,
                    label = mapTargetLabel,
                    flagged = true,
                    color = mapTargetColorArgb,
                    details = mapTargetLabel,
                    radiusM = if (mapTargetPinpointed) null else mapTargetRadiusM.toDouble(),
                    whereNote = "",
                    showDot = true,
                    opacity = 1.0,
                    iconKind = mapTargetIconKind,
                    agencyId = mapTargetAgencyId
                )
                map.drawPins(listOf(pin))
            }
        }
    }

    private fun zoomForRadius(radiusM: Int): Double = when {
        radiusM <= 50 -> 16.2
        radiusM <= 150 -> 15.2
        radiusM <= 350 -> 14.3
        radiusM <= 800 -> 13.3
        radiusM <= 2000 -> 12.1
        else -> 11.0
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun encodeUriToDataUrl(uri: Uri): String? =
        WitnessPhotoCodec.encodePreservingZoomDetail(this, uri)
}
