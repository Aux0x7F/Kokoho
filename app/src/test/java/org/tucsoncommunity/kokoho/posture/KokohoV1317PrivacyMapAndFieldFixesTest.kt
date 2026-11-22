package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.tucsoncommunity.kokoho.R
import org.tucsoncommunity.kokoho.detect.Advert
import org.tucsoncommunity.kokoho.detect.Category
import org.tucsoncommunity.kokoho.detect.DeviceIntel
import org.tucsoncommunity.kokoho.detect.SignatureEngine
import org.tucsoncommunity.kokoho.service.DeviceRegistry
import org.tucsoncommunity.kokoho.ui.DeviceDetailActivity
import org.tucsoncommunity.kokoho.ui.KokohoReportWizard
import org.tucsoncommunity.kokoho.ui.MapEngine
import org.tucsoncommunity.kokoho.ui.RfMap
import org.tucsoncommunity.kokoho.ui.WitnessPhotoCodec
import org.tucsoncommunity.kokoho.util.CrashLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KokohoV1317PrivacyMapAndFieldFixesTest {

    private var activeServer: HttpServer? = null
    private val createdActivities = ArrayList<ActivityController<*>>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.clearTrustListenersForTest()
        DeviceTrustManager.resetTrustForTest(ctx)
        VpnGeoGate.resetForTest()
        KokohoHubClient.clearPendingBugReportsForTest(ctx)
        KokohoHubClient.clearCachedObjects()
        KokohoSignatures.clearWitnessAndConfirmedCacheInMemory()
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        CrashLog.file(ctx).delete()
        ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        KokohoSignatures.ensureLoaded(ctx)
    }

    @After
    fun tearDown() {
        for (ctrl in createdActivities) {
            runCatching { ctrl.pause().stop().destroy() }
        }
        createdActivities.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        activeServer?.stop(0)
        activeServer = null
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.resetTrustForTest(ctx)
        VpnGeoGate.resetForTest()
        KokohoHubClient.clearPendingBugReportsForTest(ctx)
        KokohoHubClient.clearCachedObjects()
        KokohoHubClient.setHubUrl(ctx, KokohoHubClient.DEFAULT_HUB_URL)
        KokohoSignatures.clearWitnessAndConfirmedCacheInMemory()
        KokohoEntityLedger.clearAll(ctx)
        KokohoPostureEngine.clearLocalState()
        DeviceRegistry.clear()
        CrashLog.file(ctx).delete()
        ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        DeviceTrustManager.clearTrustListenersForTest()
    }

    private data class CapturedHttpRequest(
        val path: String,
        val contentType: String,
        val seatIdHeader: String?,
        val sigHeader: String?,
        val envKidHeader: String?,
        val decodedBody: String
    )

    private fun startTestHub(ctx: Context, captured: MutableList<CapturedHttpRequest>): HttpServer {
        val gson = Gson()
        var activeKid = ""
        var activeAeadKey = ByteArray(0)

        fun respondJson(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", contentType)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        activeServer = server

        server.createContext("/v1/auth/session-key") { ex ->
            val raw = ex.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val req = gson.fromJson(raw, DeviceTrustManager.SessionKeyRequestDto::class.java)
            val clientSpki = Base64.decode(req.clientEphemeralSpkiB64, Base64.DEFAULT)
            val serverKp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val shared = DeviceTrustManager.computeEcdhSharedSecret(serverKp.private, clientSpki)!!
            activeKid = "%024x".format(System.nanoTime() and 0xFFFFFF)
            activeAeadKey = DeviceTrustManager.deriveSessionAeadKey(shared, activeKid)
            val resp = DeviceTrustManager.SessionKeyResponseDto(
                ok = true,
                kid = activeKid,
                serverEphemeralSpkiB64 = Base64.encodeToString(serverKp.public.encoded, Base64.NO_WRAP),
                expiresAtMs = System.currentTimeMillis() + 600_000L
            )
            respondJson(ex, 200, gson.toJson(resp))
        }

        server.createContext("/v1/auth/enroll") { ex ->
            val raw = ex.requestBody.bufferedReader(Charsets.UTF_8).readText()
            captured += CapturedHttpRequest(
                path = "/v1/auth/enroll",
                contentType = ex.requestHeaders.getFirst("Content-Type").orEmpty(),
                seatIdHeader = ex.requestHeaders.getFirst("x-kokoho-seat-id"),
                sigHeader = ex.requestHeaders.getFirst("x-kokoho-sig"),
                envKidHeader = ex.requestHeaders.getFirst("x-kokoho-env-kid"),
                decodedBody = raw
            )
            val resp = DeviceTrustManager.AuthResponseDto(
                ok = true,
                status = "ENROLLED",
                seatId = "seat-priv-01",
                slug = "inv-priv-01",
                role = "RESPONDER",
                roles = listOf("RESPONDER"),
                label = "Field Responder Alpha"
            )
            respondJson(ex, 200, gson.toJson(resp))
        }

        for (endpoint in listOf("/v1/bugs", "/v1/witness")) {
            server.createContext(endpoint) { ex ->
                val raw = ex.requestBody.bufferedReader(Charsets.UTF_8).readText()
                val cType = ex.requestHeaders.getFirst("Content-Type").orEmpty()
                val envKid = ex.requestHeaders.getFirst("x-kokoho-env-kid")
                val plain = if (cType.contains("vnd.kokoho.env+json", ignoreCase = true) && !envKid.isNullOrBlank()) {
                    DeviceTrustManager.openEnvelope(envKid, activeAeadKey, raw) ?: raw
                } else {
                    raw
                }
                captured += CapturedHttpRequest(
                    path = endpoint,
                    contentType = cType,
                    seatIdHeader = ex.requestHeaders.getFirst("x-kokoho-seat-id"),
                    sigHeader = ex.requestHeaders.getFirst("x-kokoho-sig"),
                    envKidHeader = envKid,
                    decodedBody = plain
                )
                respondJson(ex, 200, """{"ok":true}""")
            }
        }

        server.start()
        KokohoHubClient.setHubUrl(ctx, "http://127.0.0.1:${server.address.port}")
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED
        return server
    }

    private fun <T : View> findViewsByType(root: View, cls: Class<T>): List<T> {
        val out = ArrayList<T>()
        fun walk(v: View) {
            if (cls.isInstance(v)) out.add(cls.cast(v)!!)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))
                }
            }
        }
        walk(root)
        return out
    }

    private fun waitUntil(timeoutMs: Long = 2_500L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(30)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun enrollAndBugReportsStripHardwareDeviceStringsIncludeLatLonAndNeverPolluteWitnessQueue() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val captured = CopyOnWriteArrayList<CapturedHttpRequest>()
        startTestHub(ctx, captured)

        // 1. /v1/auth/enroll sends empty device_info and zero Build hardware strings
        val enrollDone = CountDownLatch(1)
        var enrollOk = false
        DeviceTrustManager.claimSeatBySlug(ctx, "inv-priv-01") { ok, _ ->
            enrollOk = ok
            enrollDone.countDown()
        }
        waitUntil { enrollDone.count == 0L }
        assertTrue("Enrollment must succeed", enrollOk)

        val enrollReq = captured.first { it.path == "/v1/auth/enroll" }
        val enrollJson = JSONObject(enrollReq.decodedBody)
        assertEquals("", enrollJson.optString("device_info", "NON_EMPTY"))
        val forbiddenHwTokens = listOfNotNull(Build.MANUFACTURER, Build.MODEL, Build.BRAND, Build.DEVICE)
            .map { it.trim() }
            .filter { it.length >= 2 }
        for (tok in forbiddenHwTokens) {
            assertFalse("Enroll payload must not leak hardware token '$tok'", enrollReq.decodedBody.contains(tok, ignoreCase = true))
        }

        // 2. Seed crash_log.txt with hardware line and verify buildBugDiagnostics strips hardware info completely
        CrashLog.file(ctx).writeText(
            "==== 2026-11-22 04:00:00 +0000 ====\n" +
                "Kokoho 1.3.17 (1317)\n" +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android 14 (API 34)\n" +
                "java.lang.IllegalStateException: synthetic crash\n",
            Charsets.UTF_8
        )
        val diagTemplate = KokohoHubClient.buildBugDiagnostics(ctx, includeLogs = true)
        assertEquals("", diagTemplate.deviceInfo)
        assertTrue(diagTemplate.appVersion.isNotBlank())
        assertTrue(diagTemplate.diagnostics.contains("IllegalStateException: synthetic crash"))
        assertFalse(diagTemplate.diagnostics.contains(", Android 14 (API 34)"))
        for (tok in forbiddenHwTokens) {
            assertFalse("Sanitized diagnostics must not leak hardware token '$tok'", diagTemplate.diagnostics.contains(tok, ignoreCase = true))
        }

        // 3. KokohoReportWizard Mode.BUG_REPORT includes lat/lon on POST /v1/bugs and NEVER writes witness_reports
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        createdActivities += actCtrl
        val act = actCtrl.get()
        act.setTheme(R.style.Theme_Kokoho)

        var submittedBugKey: String? = null
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.BUG_REPORT,
            existingEntityUuid = null,
            initialAgency = KokohoAgency.DHS_ICE,
            droppedLat = 32.22194,
            droppedLon = -110.97412
        ) { key ->
            submittedBugKey = key
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val decor = dialog.window!!.decorView
        val bugInput = findViewsByType(decor, EditText::class.java).single()
        bugInput.setText("Dropped pin false echo near intersection")
        val submitBtn = findViewsByType(decor, TextView::class.java)
            .first { it.text?.toString() == "Submit Bug Report" && it.isClickable }
        submitBtn.performClick()

        waitUntil { captured.any { it.path == "/v1/bugs" } }
        assertNotNull(submittedBugKey)

        // Zero witness_reports locally and zero POST /v1/witness calls!
        assertTrue(
            "Mode.BUG_REPORT must never pollute local witness_reports",
            KokohoSignatures.getAllWitnessReports(ctx).isEmpty()
        )
        assertFalse(
            "Mode.BUG_REPORT must never post to /v1/witness",
            captured.any { it.path == "/v1/witness" }
        )

        val bugReq = captured.single { it.path == "/v1/bugs" }
        assertTrue("Enrolled bug report must use ECDH AEAD envelope", bugReq.contentType.contains("vnd.kokoho.env+json"))
        assertEquals("seat-priv-01", bugReq.seatIdHeader)
        assertNotNull(bugReq.sigHeader)
        val bugJson = JSONObject(bugReq.decodedBody)
        assertEquals("", bugJson.optString("device_info", "NON_EMPTY"))
        assertEquals(32.22194, bugJson.getDouble("lat"), 1e-5)
        assertEquals(-110.97412, bugJson.getDouble("lon"), 1e-5)
        assertTrue(bugJson.optString("app_version").isNotBlank())
        assertEquals("Dropped pin false echo near intersection", bugJson.getString("summary"))
    }

    @Test
    fun unenrolledPublicUserCanUseAllFourReportWizardFlowsAndUploadPlainJsonToWitnessAndBugs() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val captured = CopyOnWriteArrayList<CapturedHttpRequest>()
        startTestHub(ctx, captured)

        DeviceTrustManager.resetTrustForTest(ctx)
        assertFalse("Precondition: app must be unenrolled", DeviceTrustManager.isEnrolled(ctx))

        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        createdActivities += actCtrl
        val act = actCtrl.get()
        act.setTheme(R.style.Theme_Kokoho)

        // 1. Mode.LAW_ENFORCEMENT while unenrolled -> plain JSON POST /v1/witness (report_kind = LE)
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.LAW_ENFORCEMENT,
            droppedLat = 32.2155,
            droppedLon = -110.9611
        ) {}
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        run {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            val notes = findViewsByType(decor, EditText::class.java).first { it.hint?.toString()?.contains("Details") == true }
            notes.setText("Public LE sighting")
            findViewsByType(decor, TextView::class.java).first { it.text?.toString() == "Save Report" }.performClick()
        }
        waitUntil { captured.count { it.path == "/v1/witness" } >= 1 }

        // 2. Mode.CONFIRM_REFINE while unenrolled -> plain JSON POST /v1/witness (report_kind = LE)
        val detUuid = KokohoPostureEngine.deterministicUuidV5("hw:mac:00:30:44:99:88:77")
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.CONFIRM_REFINE,
            existingEntityUuid = detUuid,
            initialAgency = KokohoAgency.DHS_CBP,
            droppedLat = 32.2160,
            droppedLon = -110.9620
        ) {}
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        run {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            val notes = findViewsByType(decor, EditText::class.java).first { it.hint?.toString()?.contains("Details") == true }
            notes.setText("Confirmed marked Tahoe")
            findViewsByType(decor, TextView::class.java).first { it.text?.toString() == "Save Report" }.performClick()
        }
        waitUntil { captured.count { it.path == "/v1/witness" } >= 2 }

        // 3. Mode.FALSE_POSITIVE while unenrolled -> plain JSON POST /v1/witness (report_kind = FALSE_POSITIVE)
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.FALSE_POSITIVE,
            existingEntityUuid = detUuid,
            initialAgency = KokohoAgency.DHS_CBP,
            droppedLat = 32.2160,
            droppedLon = -110.9620
        ) {}
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        run {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            val fpInput = findViewsByType(decor, EditText::class.java).single()
            fpInput.setText("Retail coffee shop router")
            findViewsByType(decor, TextView::class.java)
                .first { it.text?.toString() == "Submit False Positive" && it.isClickable }
                .performClick()
        }
        waitUntil { captured.count { it.path == "/v1/witness" } >= 3 }

        // 4. Mode.BUG_REPORT while unenrolled -> plain JSON POST /v1/bugs with lat/lon and zero witness_reports write
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.BUG_REPORT,
            droppedLat = 32.2199,
            droppedLon = -110.9699
        ) {}
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        run {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            findViewsByType(decor, EditText::class.java).single().setText("Unenrolled map glitch")
            findViewsByType(decor, TextView::class.java)
                .first { it.text?.toString() == "Submit Bug Report" && it.isClickable }
                .performClick()
        }
        waitUntil { captured.count { it.path == "/v1/bugs" } >= 1 }

        val witnessReqs = captured.filter { it.path == "/v1/witness" }
        assertEquals(3, witnessReqs.size)
        for (req in witnessReqs) {
            assertTrue("Unenrolled witness POST must send plain application/json", req.contentType.startsWith("application/json"))
            assertNull("Unenrolled witness POST must not carry seat header", req.seatIdHeader)
            assertNull("Unenrolled witness POST must not carry envelope kid", req.envKidHeader)
        }
        assertEquals("LE", JSONObject(witnessReqs[0].decodedBody).getString("report_kind"))
        assertEquals("LE", JSONObject(witnessReqs[1].decodedBody).getString("report_kind"))
        assertEquals("FALSE_POSITIVE", JSONObject(witnessReqs[2].decodedBody).getString("report_kind"))

        val bugReq = captured.single { it.path == "/v1/bugs" }
        assertTrue(bugReq.contentType.startsWith("application/json"))
        assertNull(bugReq.seatIdHeader)
        val bugJson = JSONObject(bugReq.decodedBody)
        assertEquals("", bugJson.optString("device_info", "NON_EMPTY"))
        assertEquals(32.2199, bugJson.getDouble("lat"), 1e-5)
        assertEquals(-110.9699, bugJson.getDouble("lon"), 1e-5)

        // 5. DeviceDetailActivity exposes "Submit Report" button even when unenrolled
        val detailIntent = android.content.Intent(ctx, DeviceDetailActivity::class.java).apply {
            putExtra(DeviceDetailActivity.EXTRA_MAC, "kokoho:$detUuid")
        }
        val detailCtrl = Robolectric.buildActivity(DeviceDetailActivity::class.java, detailIntent).setup()
        createdActivities += detailCtrl
        val detailTexts = findViewsByType(detailCtrl.get().window.decorView, TextView::class.java).mapNotNull { it.text?.toString() }
        assertTrue("Unenrolled DeviceDetailActivity must show 'Submit Report' button", detailTexts.contains("Submit Report"))
    }

    @Test
    fun mapInfinityWindowHeatOverlayAndConfirmationFilterWorkAcrossDetectionsPointReportsAndTracks() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        createdActivities += actCtrl
        val act = actCtrl.get()
        act.setTheme(R.style.Theme_Kokoho)

        // 1. Window labels, legacy SharedPreferences compatibility, and filter defaults
        assertEquals(listOf("30m", "3h", "24h", "1wk", "1mo", "∞"), KokohoSignatures.MapTimeWindow.ROLLING_UI_WINDOWS.map { it.label })
        assertEquals(36_500L * 24 * 60 * 60_000L, KokohoSignatures.MapTimeWindow.HEAT.windowMs)
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("HEAT"))
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("ALL_TIME"))
        assertEquals(KokohoSignatures.MapTimeWindow.HEAT, KokohoSignatures.MapTimeWindow.fromId("∞"))
        assertFalse(KokohoSignatures.isMapHeatOverlayEnabled(act))
        assertEquals(KokohoSignatures.MapConfirmationFilter.ALL, KokohoSignatures.getMapConfirmationFilter(act))

        KokohoSignatures.setMapHeatOverlayEnabled(act, true)
        assertTrue(KokohoSignatures.isMapHeatOverlayEnabled(act))
        KokohoSignatures.setMapHeatOverlayEnabled(act, false)

        KokohoSignatures.setMapConfirmationFilter(act, KokohoSignatures.MapConfirmationFilter.CONFIRMED)
        assertEquals(KokohoSignatures.MapConfirmationFilter.CONFIRMED, KokohoSignatures.getMapConfirmationFilter(act))
        KokohoSignatures.setMapConfirmationFilter(act, KokohoSignatures.MapConfirmationFilter.ALL)

        // 2. Seed approved witness reports (both wireless detection + standalone visual witness point) vs unconfirmed
        val nowMs = 1_795_000_000_000L
        val dayMs = 24L * 3_600_000L
        val confirmedWirelessUuid = "conf-wireless-0001"
        val confirmedPointUuid = "conf-point-0002"
        val unconfirmedWirelessUuid = "unconf-wireless-0003"
        val pendingPointUuid = "pend-point-0004"
        val hist65dUuid = "unconf-hist65d-0005"

        KokohoSignatures.mergeRemoteWitnessReports(
            act,
            listOf(
                WitnessReportRecord(
                    targetKey = confirmedWirelessUuid,
                    entityUuid = confirmedWirelessUuid,
                    agencyCode = "DHS_ICE",
                    agentName = "Validator Confirmed Unit",
                    status = "APPROVED",
                    reportKind = "LE",
                    updatedAtMs = nowMs - 5 * 60_000L
                ),
                WitnessReportRecord(
                    targetKey = confirmedPointUuid,
                    entityUuid = confirmedPointUuid,
                    agencyCode = "DHS_HSI",
                    notes = "Validator Confirmed Visual Sighting",
                    status = "APPROVED",
                    reportKind = "LE",
                    lat = 32.2300,
                    lon = -110.9500,
                    updatedAtMs = nowMs - 10 * 60_000L
                )
            )
        )
        KokohoSignatures.saveWitnessReport(
            act,
            WitnessReportRecord(
                targetKey = pendingPointUuid,
                entityUuid = pendingPointUuid,
                agencyCode = "DHS_CBP",
                notes = "Unreviewed pending point",
                status = "PENDING",
                reportKind = "LE",
                lat = 32.2400,
                lon = -110.9400,
                updatedAtMs = nowMs - 4 * 60_000L
            ),
            syncRemote = false // override status explicitly below
        )
        // Store pendingPointUuid with PENDING status by saving with syncRemote = true against offline URL
        KokohoSignatures.saveWitnessReport(
            act,
            WitnessReportRecord(
                targetKey = pendingPointUuid,
                entityUuid = pendingPointUuid,
                agencyCode = "DHS_CBP",
                notes = "Unreviewed pending point",
                status = "PENDING",
                reportKind = "LE",
                lat = 32.2400,
                lon = -110.9400,
                updatedAtMs = nowMs - 4 * 60_000L
            ),
            syncRemote = true
        )
        assertFalse(KokohoSignatures.getWitnessReport(act, pendingPointUuid, approvedOnly = false)!!.isApproved)

        fun mkGroundThreat(uuid: String, agency: KokohoAgency, lat: Double, lon: Double, updatedAt: Long, witnessPointOnly: Boolean = false) =
            EvaluatedThreat(
                uuid = uuid,
                trackUuid = uuid,
                agency = agency,
                label = agency.friendlyLabel,
                posture = PostureColor.ORANGE,
                overallConfidence = 90,
                classificationConfidence = 90,
                spatialConfidence = 88,
                estimatedDistanceM = 300,
                uncertaintyRadiusM = 60,
                bearingDeg = null,
                closingVelocityMps = null,
                recurrenceSummary = uuid,
                evidenceSources = listOf(
                    if (witnessPointOnly) {
                        EvidenceBadge("WITNESS_REPORT", "witness:point:$uuid", "Visual report", 0.95, updatedAt)
                    } else {
                        EvidenceBadge("WIFI_BLE_OUI", "hw:mac:00:30:44:11:22:33", "Cradlepoint", 0.90, updatedAt)
                    }
                ),
                updatedAtMs = updatedAt,
                lat = lat,
                lon = lon,
                isApproximatedPosition = true,
                isPinpointed = false,
                sightingCount = 4
            )

        val threats = listOf(
            mkGroundThreat(confirmedWirelessUuid, KokohoAgency.DHS_ICE, 32.2100, -110.9800, nowMs - 5 * 60_000L),
            mkGroundThreat(confirmedPointUuid, KokohoAgency.DHS_HSI, 32.2300, -110.9500, nowMs - 10 * 60_000L, witnessPointOnly = true),
            mkGroundThreat(unconfirmedWirelessUuid, KokohoAgency.DHS_CBP, 32.2500, -110.9200, nowMs - 8 * 60_000L),
            mkGroundThreat(pendingPointUuid, KokohoAgency.DHS_CBP, 32.2400, -110.9400, nowMs - 4 * 60_000L, witnessPointOnly = true),
            mkGroundThreat(hist65dUuid, KokohoAgency.POLICE, 32.2700, -110.8900, nowMs - 65L * dayMs)
        )
        KokohoSignatures.setEnabledAgencies(act, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(act, KokohoAgency.entries.toSet())

        val snap = PostureSnapshot(
            color = PostureColor.ORANGE,
            headline = "ICE",
            subheadline = "",
            distanceAndBearingText = "",
            bottomConfidenceText = "",
            primaryThreat = threats.first(),
            activeThreats = threats.take(4),
            monitorGroups = emptyList(),
            mapThreats = threats,
            enabledScopeText = "",
            evaluatedAtMs = nowMs
        )

        val sourceData = mutableMapOf<String, String>()
        val addedSources = mutableSetOf<String>()
        val fakeEngine = object : MapEngine {
            override val provider = MapEngine.Provider.OPENFREEMAP
            override val view = View(act)
            override val credit = ""
            override val labelFont = listOf("Noto Sans Regular")
            override fun onCreate(state: android.os.Bundle?) {}
            override fun onStart() {}
            override fun onResume() {}
            override fun onPause() {}
            override fun onStop() {}
            override fun onLowMemory() {}
            override fun onSaveInstanceState(out: android.os.Bundle) {}
            override fun onDestroy() {}
            override fun whenReady(ready: () -> Unit) { ready() }
            override fun setStyle(dark: Boolean, loaded: (Boolean) -> Unit) { loaded(true) }
            override val styleLoaded = true
            override fun hasSource(id: String) = id in addedSources
            override fun addImage(id: String, bitmap: Bitmap) {}
            override fun addSource(id: String) { addedSources += id }
            override fun setData(source: String, geoJson: String) { sourceData[source] = geoJson }
            override fun addLayer(json: JSONObject) {}
            override fun setPaint(layer: String, name: String, value: String) {}
            override val camera = MapEngine.Camera(32.23, -110.95, 15.6, 0.0, 0.0) // zoom >= 15.5 -> zero cluster merging
            override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {}
            override fun zoomTo(zoom: Double) {}
            override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {}
            override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {}
            override fun visibleBox() = MapEngine.Box(32.35, -110.75, 32.10, -111.05)
            override fun toLatLon(x: Float, y: Float) = 32.23 to -110.95
            override fun setMinZoom(zoom: Double) {}
            override var onCameraMove: (() -> Unit)? = null
            override var onIdle: (() -> Unit)? = null
            override var onClick: ((Float, Float) -> Unit)? = null
            override var onLongClick: ((Double, Double) -> Unit)? = null
            override var onUserGesture: (() -> Unit)? = null
            override fun query(box: android.graphics.RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit) {
                result(layers.map { emptyList() })
            }
            override fun setMarker(bitmap: Bitmap?) {}
            override val draws3dMarker = false
            override fun setMarker3d(model: File?, size: Float) {}
            override fun startPuck() = true
            override val puckOn = true
            override fun pushFix(fix: android.location.Location) {}
            override fun follow(mode: MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {}
            override var onFollowDismissed: (() -> Unit)? = null
            override fun placeBranding(topPx: Int, atEnd: Boolean) {}
        }

        val rfMap = RfMap(act, fakeEngine)
        rfMap.loadStyle(dark = true) {}

        // A) ∞ window + ALL + Heat OFF -> all 5 normal pins, 0 heat features
        KokohoSignatures.setMapTimeWindow(act, KokohoSignatures.MapTimeWindow.HEAT)
        KokohoSignatures.setMapHeatOverlayEnabled(act, false)
        KokohoSignatures.setMapConfirmationFilter(act, KokohoSignatures.MapConfirmationFilter.ALL)
        val pinsAllInf = rfMap.drawLive(emptyList(), snap)
        assertEquals(5, pinsAllInf.size)
        assertEquals(0, JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())

        // B) ∞ window + ALL + Heat ON -> all 5 normal pins AND 5 heat features
        KokohoSignatures.setMapHeatOverlayEnabled(act, true)
        val pinsAllInfHeat = rfMap.drawLive(emptyList(), snap)
        assertEquals(5, pinsAllInfHeat.size)
        assertEquals(5, JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())

        // C) ∞ window + CONFIRMED + Heat ON -> ONLY confirmedWirelessUuid + confirmedPointUuid (2 pins, 2 heat features)
        KokohoSignatures.setMapConfirmationFilter(act, KokohoSignatures.MapConfirmationFilter.CONFIRMED)
        val pinsConfirmed = rfMap.drawLive(emptyList(), snap)
        assertEquals(
            setOf("kokoho:$confirmedWirelessUuid", "kokoho:$confirmedPointUuid"),
            pinsConfirmed.map { it.mac }.toSet()
        )
        assertEquals(2, JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())

        // D) ∞ window + UNCONFIRMED + Heat ON -> ONLY passive unconfirmed detections (unconfirmedWirelessUuid + hist65dUuid)
        KokohoSignatures.setMapConfirmationFilter(act, KokohoSignatures.MapConfirmationFilter.UNCONFIRMED)
        val pinsUnconfirmedInf = rfMap.drawLive(emptyList(), snap)
        assertEquals(
            setOf("kokoho:$unconfirmedWirelessUuid", "kokoho:$hist65dUuid"),
            pinsUnconfirmedInf.map { it.mac }.toSet()
        )
        assertEquals(2, JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())

        // E) 30m window + UNCONFIRMED + Heat ON -> hist65dUuid filtered out of both pins and heat!
        KokohoSignatures.setMapTimeWindow(act, KokohoSignatures.MapTimeWindow.MIN_30)
        val pinsUnconfirmed30m = rfMap.drawLive(emptyList(), snap)
        assertEquals(setOf("kokoho:$unconfirmedWirelessUuid"), pinsUnconfirmed30m.map { it.mac }.toSet())
        assertEquals(1, JSONObject(sourceData["rf-kokoho-heat"]!!).getJSONArray("features").length())
    }

    @Test
    fun djiConsumerHandheldCamerasGimbalsAndMicsAreExcludedAndPurgeEarlierOuiOnlyDroneHits() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoSignatures.setDroneDetectionEnabled(ctx, true)
        KokohoSignatures.setEnabledAgencies(ctx, KokohoAgency.entries.toSet())
        KokohoSignatures.setMapVisibleAgencies(ctx, KokohoAgency.entries.toSet())

        val handheldSamples = listOf(
            "0C:9A:E6:79:4E:8B" to "OsmoAction6-4E8A",
            "0C:9A:E6:11:22:33" to "OsmoPocket3-9F12",
            "8C:58:23:11:22:33" to "OsmoMobile6-AB12",
            "4C:43:F6:11:22:33" to "OM 6-1234",
            "60:60:1F:11:22:33" to "RS 4 Pro-1A2B",
            "60:60:1F:22:33:44" to "Ronin-SC-8821",
            "60:60:1F:33:44:55" to "DJI Mic 2-4410",
            "20:1F:55:AA:BB:CC" to null
        )

        for ((mac, name) in handheldSamples) {
            assertTrue("Expected isIgnoredInfrastructureOrRetail=true for $mac / $name", KokohoSignatures.isIgnoredInfrastructureOrRetail(mac, name))
            assertFalse("Expected isTacticalOrRemoteIdDrone=false for $mac / $name", KokohoSignatures.isTacticalOrRemoteIdDrone(mac, name, "DJI", hasRemoteId = false))
            val classified = SignatureEngine.classify(Advert(mac, Advert.Source.WIFI, -64, name))
            assertTrue("SignatureEngine must never emit Category.DRONE for $mac / $name", classified.none { it.category == Category.DRONE })
        }

        // Field bug replay:
        // 1) Neighbor's OsmoAction6-4E8A on 0C:9A:E6:79:4E:8B heard first nameless before SSID is decoded:
        val now = 1_795_100_000_000L
        val geo = DeviceRegistry.GeoSample(now, 32.2226, -110.9747)
        val idWifi = DeviceIntel.Identity("WiFi access point", emptyList())

        val nameless0c = Advert("0C:9A:E6:79:4E:8B", Advert.Source.WIFI, -65, null, timestamp = now)
        val initialHits0c = SignatureEngine.classify(nameless0c)
        DeviceRegistry.report(nameless0c, initialHits0c, idWifi, "DJI", null, geo, now, here = geo)

        // Even before SSID decoding, 0C:9A:E6 consumer DJI block must NOT surface as Tactical Drone in KokohoPostureEngine!
        val snapBeforeSsid = KokohoPostureEngine.evaluate(ctx, now)
        assertTrue(
            "Consumer DJI OUI 0C:9A:E6 must not surface as Tactical Drone before SSID decode",
            snapBeforeSsid.activeThreats.none { it.isDrone || it.agency == KokohoAgency.DRONE }
        )

        // Also test Enterprise DJI OUI (60:60:1F:79:4E:8B) heard nameless first (provisional DRONE), then learning OsmoAction6-4E8A:
        val entMac = "60:60:1F:79:4E:8B"
        val namelessEnt = Advert(entMac, Advert.Source.WIFI, -63, null, timestamp = now + 500L)
        val initialEntHits = SignatureEngine.classify(namelessEnt)
        assertTrue(initialEntHits.any { it.category == Category.DRONE })
        DeviceRegistry.report(namelessEnt, initialEntHits, idWifi, "DJI", null, geo, now + 500L, here = geo)
        val snapProvisionalEnt = KokohoPostureEngine.evaluate(ctx, now + 500L)
        assertTrue(snapProvisionalEnt.activeThreats.any { it.hardwareIdentifier == entMac && it.isDrone })

        // 2) Beacon arrives with SSID "OsmoAction6-4E8A" on sibling/same MAC:
        val named0cSibling = Advert("0C:9A:E6:79:4E:8A", Advert.Source.WIFI, -64, "OsmoAction6-4E8A", timestamp = now + 1_500L)
        DeviceRegistry.report(named0cSibling, SignatureEngine.classify(named0cSibling), idWifi, "DJI", null, geo, now + 1_500L, here = geo)

        val namedEnt = Advert(entMac, Advert.Source.WIFI, -62, "OsmoAction6-4E8A", timestamp = now + 1_600L)
        DeviceRegistry.report(namedEnt, SignatureEngine.classify(namedEnt), idWifi, "DJI", null, geo, now + 1_600L, here = geo)

        // Held OUI-only Category.DRONE hits must be immediately cleared on BOTH 0C:9A:E6:79:4E:8B (sibling AP) and 60:60:1F:79:4E:8B!
        assertTrue(DeviceRegistry.get("0C:9A:E6:79:4E:8B")!!.hits.none { it.category == Category.DRONE })
        assertTrue(DeviceRegistry.get(entMac)!!.hits.none { it.category == Category.DRONE })

        // Subsequent nameless data frame must NOT re-introduce Category.DRONE!
        val laterNameless = Advert(entMac, Advert.Source.WIFI, -64, null, timestamp = now + 3_000L)
        assertTrue(SignatureEngine.classify(laterNameless).none { it.category == Category.DRONE })
        DeviceRegistry.report(laterNameless, SignatureEngine.classify(laterNameless), idWifi, "DJI", null, geo, now + 3_000L, here = geo)
        assertTrue(DeviceRegistry.get(entMac)!!.hits.none { it.category == Category.DRONE })

        // And KokohoPostureEngine + KokohoEntityLedger must purge any earlier provisional DRONE entity!
        val snapAfterSsid = KokohoPostureEngine.evaluate(ctx, now + 3_000L)
        assertTrue("All OsmoAction6 DRONE threats must be purged", snapAfterSsid.activeThreats.none { it.isDrone || it.agency == KokohoAgency.DRONE })
        assertTrue("Map threats must not retain OsmoAction6 as DRONE", snapAfterSsid.mapThreats.none { it.isDrone || it.agency == KokohoAgency.DRONE })
        assertNull(KokohoEntityLedger.findByUuid(ctx, KokohoPostureEngine.deterministicUuidV5("uas:mac:$entMac")))
    }

    @Test
    fun stationaryRfCentroidRemainsLockedWhenDrivingAwayAndUnlocksOnlyOnStrongerDisplacedSignal() {
        val uuid = KokohoPostureEngine.deterministicUuidV5("rf:chan:163162500:169")
        val t0 = 1_795_200_000_000L
        val decayMs = 45 * 60_000L

        // 4 samples around a stationary transmitter near (32.2226, -110.9747)
        KokohoPostureEngine.recordAndTriangulate(uuid, 32.2222, -110.9752, -62, 70, t0, decayMs)
        KokohoPostureEngine.recordAndTriangulate(uuid, 32.2226, -110.9747, -50, 25, t0 + 5_000L, decayMs)
        KokohoPostureEngine.recordAndTriangulate(uuid, 32.2230, -110.9744, -56, 42, t0 + 10_000L, decayMs)
        val converged = KokohoPostureEngine.recordAndTriangulate(uuid, 32.2228, -110.9751, -54, 34, t0 + 15_000L, decayMs)

        assertTrue(converged.isApproximatedPosition)
        assertTrue("Converged stationary radius must be <= 120m, got ${converged.uncertaintyRadiusM}", converged.uncertaintyRadiusM <= 120)
        val lockedLat = converged.centerLat!!
        val lockedLon = converged.centerLon!!
        val lockedRadius = converged.uncertaintyRadiusM

        // Observer drives north away from the stationary source with weaker receding signals (<= best - 6 dB):
        val recede1 = KokohoPostureEngine.recordAndTriangulate(uuid, 32.2242, -110.9747, -60, 62, t0 + 25_000L, decayMs)
        val recede2 = KokohoPostureEngine.recordAndTriangulate(uuid, 32.2258, -110.9747, -64, 85, t0 + 38_000L, decayMs) // > 30s & > 250m!
        val recede3 = KokohoPostureEngine.recordAndTriangulate(uuid, 32.2280, -110.9747, -76, 180, t0 + 50_000L, decayMs)

        for (r in listOf(recede1, recede2, recede3)) {
            assertEquals("Center lat must stay locked during receding drive-away", lockedLat, r.centerLat!!, 1e-6)
            assertEquals("Center lon must stay locked during receding drive-away", lockedLon, r.centerLon!!, 1e-6)
            assertEquals("Uncertainty radius must not widen during receding drive-away", lockedRadius, r.uncertaintyRadiusM)
        }
        assertTrue("User distance must grow as observer drives ~600m away", recede3.estimatedDistanceM >= 500)

        // Now a stronger signal (> best - 3 dB, i.e. -49 dBm > -50 - 3) appears at a genuinely displaced location ~450m north:
        val displacedLat = 32.2268
        val displacedLon = -110.9747
        val unlocked = KokohoPostureEngine.recordAndTriangulate(uuid, displacedLat, displacedLon, -49, 23, t0 + 62_000L, decayMs)
        val distToNewSpotM = DeviceRegistry.metersBetween(unlocked.centerLat!!, unlocked.centerLon!!, displacedLat, displacedLon)
        assertTrue("Stronger displaced signal must unlock and re-anchor near new spot (< 60m), got ${distToNewSpotM}m", distToNewSpotM < 60.0)
    }

    @Test
    fun witnessPhotoCodecPreservesFullResolutionUnder8MbAndStripsPhoneMakeModelExifLosslessly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. Create a sharp 1600x1200 JPEG (< 8 MB) and inject a real APP1 Exif\0\0 header containing Make, Model, and Orientation = 6
        val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply {
            color = Color.YELLOW
            textSize = 42f
        }
        canvas.drawText("PLATE AZ-7731-K9", 400f, 600f, paint)
        val rawJpegOut = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 94, rawJpegOut))
        val cleanJpeg = rawJpegOut.toByteArray()
        assertTrue(cleanJpeg.size < WitnessPhotoCodec.MAX_WITNESS_PHOTO_BYTES)

        val exifSegmentWithMakeModel = buildExifSegmentWithMakeModelAndOrientation(
            make = "AcmePhoneCorp",
            model = "SuperZoomDevice 99",
            orientation = 6
        )
        val jpegWithExif = ByteArrayOutputStream().apply {
            write(cleanJpeg, 0, 2) // SOI FF D8
            write(exifSegmentWithMakeModel)
            write(cleanJpeg, 2, cleanJpeg.size - 2)
        }.toByteArray()

        assertTrue(String(jpegWithExif, Charsets.ISO_8859_1).contains("AcmePhoneCorp"))
        assertTrue(String(jpegWithExif, Charsets.ISO_8859_1).contains("SuperZoomDevice 99"))

        val tempFile = File(ctx.cacheDir, "witness_zoom_test.jpg")
        tempFile.writeBytes(jpegWithExif)

        val encodedDataUrl = WitnessPhotoCodec.encodePreservingZoomDetail(ctx, Uri.fromFile(tempFile))
        assertNotNull(encodedDataUrl)
        assertTrue(encodedDataUrl!!.startsWith("data:image/jpeg;base64,"))

        val outputBytes = Base64.decode(encodedDataUrl.substringAfter("base64,"), Base64.DEFAULT)
        val outputAscii = String(outputBytes, Charsets.ISO_8859_1)
        assertFalse("Make EXIF string must be stripped", outputAscii.contains("AcmePhoneCorp"))
        assertFalse("Model EXIF string must be stripped", outputAscii.contains("SuperZoomDevice 99"))

        // Verify 100% full 1600x1200 sensor resolution is preserved (no 960/1080 downsampling!)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size, bounds)
        assertEquals(1600, bounds.outWidth)
        assertEquals(1200, bounds.outHeight)

        // Verify SOS scan stream bytes are 100% bit-for-bit identical (zero JPEG recompression loss!)
        val origSosIdx = findSosMarkerIndex(jpegWithExif)
        val outSosIdx = findSosMarkerIndex(outputBytes)
        assertTrue(origSosIdx > 0 && outSosIdx > 0)
        assertArrayEquals(
            "JPEG SOS compressed scan stream must remain 100% bit-identical",
            jpegWithExif.copyOfRange(origSosIdx, jpegWithExif.size),
            outputBytes.copyOfRange(outSosIdx, outputBytes.size)
        )

        // Verify non-1 Orientation = 6 was preserved in the minimal orientation-only APP1 header
        val (_, parsedOrient) = WitnessPhotoCodec.stripJpegExifLossless(outputBytes)
        assertEquals(6, parsedOrient)
    }

    @Test
    fun publicLocationsAssetIncludesUnknownCbpFacilityWithExactCoordinatesAndR6CleanDescriptions() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        KokohoDirectory.purgeDecryptedCache(ctx)
        KokohoDirectory.ensureLoaded(ctx)

        val unknownCbp = KokohoDirectory.locationById(ctx, "loc:dhs:unknown-cbp")
        assertNotNull("Expected bundled public_locations.tsv.gz to contain loc:dhs:unknown-cbp", unknownCbp)
        assertEquals("loc:dhs:unknown-cbp", unknownCbp!!.id)
        assertEquals("Unknown CBP", unknownCbp.title)
        assertEquals("DHS_CBP", unknownCbp.agencyCode)
        assertEquals(KokohoAgency.DHS_CBP, unknownCbp.agency)
        assertEquals(32.163639, unknownCbp.lat, 1e-6)
        assertEquals(-110.895639, unknownCbp.lon, 1e-6)
        assertEquals("FEDERAL", unknownCbp.locationGroup)

        for (loc in KokohoDirectory.allLocations(ctx)) {
            val hay = "${loc.title}\n${loc.address}\n${loc.description}"
            assertFalse("Location ${loc.id} contains em-dash", hay.contains('\u2014'))
            assertFalse("Location ${loc.id} contains en-dash", hay.contains('\u2013'))
        }
    }

    private fun findSosMarkerIndex(jpeg: ByteArray): Int {
        var pos = 2
        while (pos + 2 < jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != 0xFF) return -1
            while (pos < jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) pos++
            if (pos >= jpeg.size) return -1
            val marker = jpeg[pos].toInt() and 0xFF
            if (marker == 0xDA) return pos - 1
            if (marker == 0xD9) return -1
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                pos++
                continue
            }
            pos++
            if (pos + 2 > jpeg.size) return -1
            val len = ((jpeg[pos].toInt() and 0xFF) shl 8) or (jpeg[pos + 1].toInt() and 0xFF)
            pos += len
        }
        return -1
    }

    private fun buildExifSegmentWithMakeModelAndOrientation(make: String, model: String, orientation: Int): ByteArray {
        val makeBytes = (make + "\u0000").toByteArray(Charsets.US_ASCII)
        val modelBytes = (model + "\u0000").toByteArray(Charsets.US_ASCII)
        // Big-endian TIFF: 8-byte header + 2-byte count (3 entries) + 3*12 entries + 4-byte nextIFD = 50 bytes before string heap
        val makeOffset = 50
        val modelOffset = makeOffset + makeBytes.size
        val tiff = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
            write(byteArrayOf(0x00, 0x03)) // 3 IFD0 entries
            // Entry 1: Make (0x010F, ASCII=2)
            write(byteArrayOf(0x01, 0x0F, 0x00, 0x02))
            writeIntBe(makeBytes.size)
            writeIntBe(makeOffset)
            // Entry 2: Model (0x0110, ASCII=2)
            write(byteArrayOf(0x01, 0x10, 0x00, 0x02))
            writeIntBe(modelBytes.size)
            writeIntBe(modelOffset)
            // Entry 3: Orientation (0x0112, SHORT=3, count=1)
            write(byteArrayOf(0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01))
            write(byteArrayOf(((orientation ushr 8) and 0xFF).toByte(), (orientation and 0xFF).toByte(), 0x00, 0x00))
            // Next IFD = 0
            writeIntBe(0)
            write(makeBytes)
            write(modelBytes)
        }.toByteArray()

        val segLen = 2 + 6 + tiff.size
        return ByteArrayOutputStream().apply {
            write(0xFF)
            write(0xE1)
            write((segLen ushr 8) and 0xFF)
            write(segLen and 0xFF)
            write("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII))
            write(tiff)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntBe(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }
}
