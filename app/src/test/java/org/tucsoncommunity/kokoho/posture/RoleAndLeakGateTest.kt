package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
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
import org.tucsoncommunity.kokoho.databinding.ActivityMainBinding
import org.tucsoncommunity.kokoho.ui.DeviceDetailActivity
import org.tucsoncommunity.kokoho.ui.KokohoReportWizard
import org.tucsoncommunity.kokoho.ui.KokohoSearchView
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoleAndLeakGateTest {

    private var activeServer: HttpServer? = null
    private val createdViews = ArrayList<KokohoViews>()
    private val createdActivities = ArrayList<ActivityController<AppCompatActivity>>()

    @Before
    fun setUp() {
        DeviceTrustManager.clearTrustListenersForTest()
    }

    @After
    fun tearDown() {
        for (v in createdViews) {
            v.destroy()
        }
        createdViews.clear()
        for (ctrl in createdActivities) {
            runCatching { ctrl.pause().stop().destroy() }
        }
        createdActivities.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activeServer?.stop(0)
        activeServer = null
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PriorityVault.simulateDeviceLockedForTest = false
        DeviceTrustManager.resetTrustForTest(ctx)
        KokohoHubClient.clearAllOnRevocation(ctx)
        KokohoHubClient.setHubUrl(ctx, KokohoHubClient.DEFAULT_HUB_URL)
        KokohoSignatures.clearWitnessAndConfirmedCacheInMemory()
        ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        DeviceTrustManager.clearTrustListenersForTest()
    }

    private fun createTrackedViews(): KokohoViews {
        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        createdActivities += actCtrl
        val act = actCtrl.get()
        val binding = ActivityMainBinding.inflate(act.layoutInflater)
        act.setContentView(binding.root)
        val views = KokohoViews(act, binding, onSwitchPanes = {}, onFilterChanged = {})
        createdViews += views
        return views
    }

    private fun awaitViewsReady(
        views: KokohoViews,
        statusCalls: AtomicInteger? = null,
        expectedMinStatusCalls: Int? = null
    ) {
        assertTrue(
            "Async trust/vault work did not settle in time",
            DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
        )
        views.searchView?.awaitPendingQueriesForTest {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("KokohoViews initial enrollment sync must complete", views.initialSyncCompleted.get())
        if (statusCalls != null && expectedMinStatusCalls != null) {
            assertTrue(
                "Expected at least $expectedMinStatusCalls /v1/auth/status calls, got ${statusCalls.get()}",
                statusCalls.get() >= expectedMinStatusCalls
            )
        }
    }

    private inline fun <reified T : View> findViewsByType(root: View): List<T> {
        val out = ArrayList<T>()
        val queue = ArrayDeque<View>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur is T) out += cur
            if (cur is ViewGroup) {
                for (i in 0 until cur.childCount) {
                    queue.addLast(cur.getChildAt(i))
                }
            }
        }
        return out
    }

    private fun collectAllTexts(root: View): List<String> {
        val out = ArrayList<String>()
        if (root is TextView) {
            val txt = root.text?.toString().orEmpty()
            if (txt.isNotBlank()) out += txt
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                out += collectAllTexts(root.getChildAt(i))
            }
        }
        return out
    }

    private fun startMockHub(
        statusResponseSupplier: () -> String,
        p2DtoSupplier: () -> PriorityVault.DirectoryTierSyncDto,
        p3DtoSupplier: () -> PriorityVault.DirectoryTierSyncDto,
        p2IfNoneMatchSeen: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(),
        p3IfNoneMatchSeen: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(),
        statusCalls: AtomicInteger = AtomicInteger(0),
        handshakeCount: AtomicInteger = AtomicInteger(0),
        onAuthStatusEnter: (() -> Unit)? = null,
        onP2Enter: (() -> Unit)? = null,
        onP3Enter: (() -> Unit)? = null,
        p3ForceErrorCode: () -> Int? = { null },
        p1ResponseSupplier: (() -> PriorityVault.PlatesSyncResponseDto)? = null,
        p1ForceErrorCode: () -> Int? = { null },
        statusCodeSupplier: () -> Int = { 200 },
        statusExtraHeadersSupplier: () -> Map<String, String> = { emptyMap() },
        enrollCalls: AtomicInteger = AtomicInteger(0),
        enrollResponseSupplier: (() -> String)? = null,
        onAuthEnrollEnter: (() -> Unit)? = null,
        onStateEnter: (() -> Unit)? = null,
        stateForcedError: () -> Triple<Int, Map<String, String>, String>? = { null },
        onCheckinEnter: (() -> Unit)? = null,
        checkinForcedError: () -> Triple<Int, Map<String, String>, String>? = { null }
    ): String {
        val gson = Gson()
        var activeKid = ""
        var activeAeadKey = ByteArray(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "mock-hub-http").apply { isDaemon = true }
        }
        activeServer = server

        fun respondJson(
            ex: HttpExchange,
            code: Int,
            body: String,
            contentType: String = "application/json",
            extraHeaders: Map<String, String> = emptyMap()
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", contentType)
            for ((k, v) in extraHeaders) ex.responseHeaders.set(k, v)
            ex.sendResponseHeaders(code, if (code == 304) -1L else bytes.size.toLong())
            if (code != 304) {
                ex.responseBody.use { it.write(bytes) }
            } else {
                ex.responseBody.close()
            }
        }

        fun checkEnvelope(ex: HttpExchange): Boolean {
            val reqKid = ex.requestHeaders.getFirst("x-kokoho-env-kid").orEmpty()
            if (reqKid.isEmpty() || reqKid != activeKid) {
                respondJson(ex, 419, """{"error":"SESSION_KEY_EXPIRED"}""")
                return false
            }
            return true
        }

        server.createContext("/v1/auth/session-key") { ex ->
            val body = ex.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val req = gson.fromJson(body, DeviceTrustManager.SessionKeyRequestDto::class.java)
            val clientSpki = Base64.decode(req.clientEphemeralSpkiB64, Base64.DEFAULT)
            val serverKp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val shared = DeviceTrustManager.computeEcdhSharedSecret(serverKp.private, clientSpki)!!
            val n = handshakeCount.incrementAndGet()
            activeKid = "%024x".format(n)
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
            enrollCalls.incrementAndGet()
            onAuthEnrollEnter?.invoke()
            respondJson(ex, 200, (enrollResponseSupplier ?: statusResponseSupplier)())
        }

        server.createContext("/v1/auth/status") { ex ->
            statusCalls.incrementAndGet()
            onAuthStatusEnter?.invoke()
            respondJson(ex, statusCodeSupplier(), statusResponseSupplier(), extraHeaders = statusExtraHeadersSupplier())
        }

        server.createContext("/v1/witness") { ex ->
            respondJson(ex, 200, """{"ok":true}""")
        }

        server.createContext("/v1/state") { ex ->
            if (!checkEnvelope(ex)) return@createContext
            onStateEnter?.invoke()
            val forced = stateForcedError()
            if (forced != null) {
                respondJson(ex, forced.first, forced.third, extraHeaders = forced.second)
                return@createContext
            }
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, """{"ok":true,"objects":[]}""")!!
            respondJson(ex, 200, gson.toJson(env), contentType = DeviceTrustManager.ENV_CONTENT_TYPE)
        }

        server.createContext("/v1/checkin") { ex ->
            if (!checkEnvelope(ex)) return@createContext
            onCheckinEnter?.invoke()
            val forced = checkinForcedError()
            if (forced != null) {
                respondJson(ex, forced.first, forced.third, extraHeaders = forced.second)
                return@createContext
            }
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, """{"ok":true,"seq":1}""")!!
            respondJson(ex, 200, gson.toJson(env), contentType = DeviceTrustManager.ENV_CONTENT_TYPE)
        }

        server.createContext("/v1/sync/p1-plates") { ex ->
            if (!checkEnvelope(ex)) return@createContext
            val forcedErr = p1ForceErrorCode()
            if (forcedErr != null) {
                respondJson(ex, forcedErr, """{"error":"injected_p1_failure"}""")
                return@createContext
            }
            val payload = p1ResponseSupplier?.invoke() ?: PriorityVault.PlatesSyncResponseDto(
                mode = "SNAPSHOT",
                serverTimeMs = 1_500L,
                plates = listOf(
                    KokohoHubClient.SyncedPlateRecord(
                        plateId = "pl-1",
                        plateNumber = "TEST101",
                        stateCode = "AZ",
                        agencyCode = "DHS_ICE",
                        suspectedAgency = "ICE",
                        color = "White",
                        make = "Ford",
                        model = "Explorer",
                        updatedAtMs = 1_400L
                    )
                )
            )
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, gson.toJson(payload))!!
            respondJson(ex, 200, gson.toJson(env), contentType = DeviceTrustManager.ENV_CONTENT_TYPE)
        }

        server.createContext("/v1/sync/tier/p2_sector_dir") { ex ->
            if (!checkEnvelope(ex)) return@createContext
            onP2Enter?.invoke()
            val inm = ex.requestHeaders.getFirst("If-None-Match").orEmpty()
            p2IfNoneMatchSeen += inm
            val dto = p2DtoSupplier()
            if (inm.trim('"') == dto.etag && dto.etag.isNotBlank()) {
                respondJson(ex, 304, "", extraHeaders = mapOf("ETag" to "\"${dto.etag}\""))
                return@createContext
            }
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, gson.toJson(dto))!!
            respondJson(
                ex,
                200,
                gson.toJson(env),
                contentType = DeviceTrustManager.ENV_CONTENT_TYPE,
                extraHeaders = mapOf("ETag" to "\"${dto.etag}\"")
            )
        }

        server.createContext("/v1/sync/tier/p3_ext_dir") { ex ->
            if (!checkEnvelope(ex)) return@createContext
            onP3Enter?.invoke()
            val forcedErr = p3ForceErrorCode()
            if (forcedErr != null) {
                respondJson(ex, forcedErr, """{"error":"injected_tier_failure"}""")
                return@createContext
            }
            val inm = ex.requestHeaders.getFirst("If-None-Match").orEmpty()
            p3IfNoneMatchSeen += inm
            val dto = p3DtoSupplier()
            if (inm.trim('"') == dto.etag && dto.etag.isNotBlank()) {
                respondJson(ex, 304, "", extraHeaders = mapOf("ETag" to "\"${dto.etag}\""))
                return@createContext
            }
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, gson.toJson(dto))!!
            respondJson(
                ex,
                200,
                gson.toJson(env),
                contentType = DeviceTrustManager.ENV_CONTENT_TYPE,
                extraHeaders = mapOf("ETag" to "\"${dto.etag}\"")
            )
        }

        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun fullP2Dto(): PriorityVault.DirectoryTierSyncDto = PriorityVault.DirectoryTierSyncDto(
        tier = "p2_sector_dir",
        etag = "etag-p2-full",
        locations = listOf(
            PriorityVault.SyncedLocationDto(
                locationId = "loc:sector-annex-1",
                title = "Sector Annex Test Facility",
                agencyCode = "DHS_HSI",
                lat = 32.215,
                lon = -110.965,
                address = "100 Civic Plaza",
                description = "Test synced sector facility",
                locationGroup = "FEDERAL",
                stateCode = "AZ"
            )
        ),
        persons = listOf(
            PriorityVault.SyncedPersonDto(
                personId = "p-supervisor-1",
                fullName = "Dana Supervisor",
                title = "Supervisory Coordinator",
                agencyCode = "DHS_HSI",
                agencyDisplay = "HSI",
                locationId = "loc:sector-annex-1",
                suite = "Suite 400",
                stateCode = "AZ"
            ),
            PriorityVault.SyncedPersonDto(
                personId = "p-analyst-2",
                fullName = "Alex Analyst",
                title = "Operations Specialist",
                agencyCode = "DHS_HSI",
                agencyDisplay = "HSI",
                locationId = "loc:sector-annex-1",
                suite = "Suite 410",
                stateCode = "AZ",
                managedBy = "p-supervisor-1"
            )
        )
    )

    private fun locationsOnlyP2Dto(): PriorityVault.DirectoryTierSyncDto = PriorityVault.DirectoryTierSyncDto(
        tier = "p2_sector_dir",
        etag = "etag-p2-loc-only",
        locations = fullP2Dto().locations,
        persons = emptyList()
    )

    private fun fullP3Dto(): PriorityVault.DirectoryTierSyncDto = PriorityVault.DirectoryTierSyncDto(
        tier = "p3_ext_dir",
        etag = "etag-p3-full",
        persons = listOf(
            PriorityVault.SyncedPersonDto(
                personId = "p-regional-3",
                fullName = "Morgan Regional",
                title = "Regional Liaison",
                agencyCode = "DHS_CBP",
                agencyDisplay = "CBP",
                stateCode = "CA"
            )
        ),
        edges = listOf(
            PriorityVault.SyncedEdgeDto(
                personId = "p-regional-3",
                managerId = "p-supervisor-1"
            )
        )
    )

    private fun emptyP3Dto(): PriorityVault.DirectoryTierSyncDto = PriorityVault.DirectoryTierSyncDto(
        tier = "p3_ext_dir",
        etag = "etag-p3-empty",
        persons = emptyList(),
        edges = emptyList()
    )

    @Test
    fun authRolesAffinityPersistenceAndLegacyMemberMigration() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val gson = Gson()

        // 1. Gson null-roles resilience
        val nullRolesDto = gson.fromJson(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-null","slug":"inv-null","role":"member","roles":null,"affinity_group":null,"label":"Legacy"}""",
            DeviceTrustManager.AuthResponseDto::class.java
        )
        assertEquals("member", nullRolesDto.role)

        // 2. Legacy v1.3.16 prefs upgrade (only trust_role = "MEMBER", no trust_roles)
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-legacy-16", slug = "inv-legacy")
        ctx.getSharedPreferences("kokoho_trust_prefs", Context.MODE_PRIVATE).edit()
            .putString("trust_status", "ENROLLED")
            .putString("trust_role", "MEMBER")
            .remove("trust_roles")
            .remove("trust_affinity_group")
            .commit()

        assertEquals("RESPONDER", DeviceTrustManager.enrolledRole(ctx))
        assertEquals(listOf("RESPONDER"), DeviceTrustManager.enrolledRoles(ctx))
        assertNull(DeviceTrustManager.enrolledAffinityGroup(ctx))
        assertTrue(DeviceTrustManager.hasRole(ctx, "RESPONDER"))
        assertTrue(DeviceTrustManager.hasRole(ctx, "member"))
        assertFalse(DeviceTrustManager.hasIntelligenceAccess(ctx))

        // 3. Live /v1/auth/enroll & /v1/auth/status parsing with MEMBER -> RESPONDER, multi-roles, and affinity_group clearing
        val nextStatusJson = AtomicReference(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-m4-1","slug":"inv-m4-1","role":"MEMBER","label":"Seat M4"}"""
        )
        val hubUrl = startMockHub(
            statusResponseSupplier = { nextStatusJson.get() },
            p2DtoSupplier = { locationsOnlyP2Dto() },
            p3DtoSupplier = { emptyP3Dto() }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        val enrollResp = DeviceTrustManager.enrollBlocking(
            ctx,
            DeviceTrustManager.SeatIdentity(seatId = "", slug = "inv-m4-1", voucher = "v-m4")
        )
        assertNotNull(enrollResp)
        assertEquals("RESPONDER", DeviceTrustManager.enrolledRole(ctx))
        assertEquals(listOf("RESPONDER"), DeviceTrustManager.enrolledRoles(ctx))
        assertNull(DeviceTrustManager.enrolledAffinityGroup(ctx))
        assertFalse(DeviceTrustManager.hasIntelligenceAccess(ctx))

        // Update with multi-role + affinity group Alpha
        val seat = DeviceTrustManager.loadOrExtractSeatIdentity(ctx)!!
        nextStatusJson.set(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-m4-1","slug":"inv-m4-1","role":"INTELLIGENCE","roles":["RESPONDER","validator","intelligence"],"affinity_group":"Alpha","label":"Seat M4"}"""
        )
        DeviceTrustManager.checkDeviceStatusBlocking(ctx, seat)
        assertEquals("INTELLIGENCE", DeviceTrustManager.enrolledRole(ctx))
        assertEquals(listOf("RESPONDER", "VALIDATOR", "INTELLIGENCE"), DeviceTrustManager.enrolledRoles(ctx))
        assertEquals("Alpha", DeviceTrustManager.enrolledAffinityGroup(ctx))
        assertTrue(DeviceTrustManager.hasRole(ctx, "VALIDATOR"))
        assertTrue(DeviceTrustManager.hasRole(ctx, "INTELLIGENCE"))
        assertTrue(DeviceTrustManager.hasIntelligenceAccess(ctx))

        // Clear affinity_group when null, and check ADMIN does NOT imply Intelligence
        nextStatusJson.set(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-m4-1","slug":"inv-m4-1","role":"ADMIN","roles":["ADMIN","MANAGER","VALIDATOR","DEV"],"affinity_group":null,"label":"Seat M4"}"""
        )
        DeviceTrustManager.checkDeviceStatusBlocking(ctx, seat)
        assertEquals(listOf("ADMIN", "MANAGER", "VALIDATOR", "DEV"), DeviceTrustManager.enrolledRoles(ctx))
        assertNull(DeviceTrustManager.enrolledAffinityGroup(ctx))
        assertFalse("Legacy ADMIN without INTELLIGENCE/OWNER must not unlock Intelligence", DeviceTrustManager.hasIntelligenceAccess(ctx))

        // OWNER unlocks Intelligence
        nextStatusJson.set(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-m4-1","slug":"inv-m4-1","role":"OWNER","roles":["OWNER"],"affinity_group":"Core","label":"Owner"}"""
        )
        DeviceTrustManager.checkDeviceStatusBlocking(ctx, seat)
        assertTrue(DeviceTrustManager.hasIntelligenceAccess(ctx))
        assertEquals("Core", DeviceTrustManager.enrolledAffinityGroup(ctx))

        // Conflicting role vs roles must never elevate KEY_ROLE or Intelligence access
        nextStatusJson.set(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-m4-1","slug":"inv-m4-1","role":"INTELLIGENCE","roles":["RESPONDER"],"affinity_group":null,"label":"Seat M4"}"""
        )
        DeviceTrustManager.checkDeviceStatusBlocking(ctx, seat)
        assertEquals("RESPONDER", DeviceTrustManager.enrolledRole(ctx))
        assertEquals(listOf("RESPONDER"), DeviceTrustManager.enrolledRoles(ctx))
        assertFalse(DeviceTrustManager.hasIntelligenceAccess(ctx))

        // Revocation clears role/roles/affinity_group
        DeviceTrustManager.handleSeatRevokedOrDisplaced(ctx, "REVOKED")
        assertFalse(DeviceTrustManager.isEnrolled(ctx))
        assertFalse(DeviceTrustManager.hasIntelligenceAccess(ctx))
        assertNull(DeviceTrustManager.enrolledAffinityGroup(ctx))
        assertEquals(listOf("RESPONDER"), DeviceTrustManager.enrolledRoles(ctx))

        // Null status JSON field from Gson must not throw NPE
        nextStatusJson.set("""{"ok":false,"status":null,"role":null,"roles":null,"seat_id":null,"slug":null,"label":null}""")
        val nullStatusResp = DeviceTrustManager.enrollBlocking(
            ctx,
            DeviceTrustManager.SeatIdentity(seatId = "", slug = "inv-null-status", voucher = "")
        )
        assertNotNull(nullStatusResp)
        assertFalse(DeviceTrustManager.isEnrolled(ctx))
    }

    @Test
    fun claimSeatBySlugSwitchesSeatRenegotiatesSessionKeyAndFormatsPrimaryDisplayRole() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handshakeCount = AtomicInteger(0)
        val activeInviteJson = AtomicReference(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-a","slug":"inv-a","role":"INTELLIGENCE","roles":["RESPONDER","INTELLIGENCE"],"label":"Seat A"}"""
        )
        val hubUrl = startMockHub(
            statusResponseSupplier = { activeInviteJson.get() },
            p2DtoSupplier = { fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            handshakeCount = handshakeCount
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-a",
            slug = "inv-a",
            role = "INTELLIGENCE",
            roles = listOf("RESPONDER", "INTELLIGENCE")
        )
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertTrue(DeviceTrustManager.hasActiveSessionKey())
        assertEquals(1, handshakeCount.get())

        activeInviteJson.set(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-b","slug":"inv-b","role":"INTELLIGENCE","roles":["RESPONDER","INTELLIGENCE"],"label":"Seat B"}"""
        )
        val done = CountDownLatch(1)
        var claimOk = false
        var claimMsg = ""
        DeviceTrustManager.claimSeatBySlug(ctx, "inv-b") { ok, msg ->
            claimOk = ok
            claimMsg = msg
            done.countDown()
        }
        assertTrue(
            DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
        )
        assertEquals(0L, done.count)
        assertTrue(claimOk)
        assertEquals("This phone is enrolled (Intelligence).", claimMsg)
        assertEquals("inv-b", DeviceTrustManager.currentSeatSlug(ctx))
        assertEquals("seat-b", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
        assertEquals("INTELLIGENCE", DeviceTrustManager.enrolledRole(ctx))
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertEquals("Slug switch must renegotiate ECDH session key", 2, handshakeCount.get())
    }

    @Test
    fun responderVsIntelligenceVsOwnerSearchAndDetailLeakGate() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val useFullDirectory = AtomicBoolean(false)
        val activeRoleRef = AtomicReference("RESPONDER")
        val statusCalls = AtomicInteger(0)
        val hubUrl = startMockHub(
            statusResponseSupplier = {
                val r = activeRoleRef.get()
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-gate-1","slug":"inv-gate-1","role":"$r","roles":["$r"]}"""
            },
            p2DtoSupplier = { if (useFullDirectory.get()) fullP2Dto() else locationsOnlyP2Dto() },
            p3DtoSupplier = { if (useFullDirectory.get()) fullP3Dto() else emptyP3Dto() },
            statusCalls = statusCalls
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        // Seed a local userPersons entry in kokoho_agency_prefs to verify it cannot bypass the leak gate
        ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).edit()
            .putString(
                "kokoho_user_persons_json",
                """[{"personId":"user-leak-1","fullName":"Local Leak Agent","title":"Agent","agencyCode":"DHS_HSI","locationId":"loc:sector-annex-1"}]"""
            )
            .commit()

        // 1. RESPONDER with facility-only p2_sector_dir (locations present, persons empty)
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-gate-1", slug = "inv-gate-1", roles = listOf("RESPONDER"))
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        KokohoDirectory.ensureLoaded(ctx)

        assertFalse("Facility-only p2_sector_dir must NOT mark isDirectoryAvailable true", KokohoDirectory.isDirectoryAvailable)
        assertFalse(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
        assertNotNull("Synced location must remain accessible to RESPONDER", KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))
        assertEquals(0, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))
        assertTrue(KokohoDirectory.stationedPersonnel(ctx, "loc:sector-annex-1").isEmpty())
        assertTrue(KokohoDirectory.suggestTableFilterTokens(ctx, "loc:sector-annex-1", "Da", emptyList()).isEmpty())
        assertTrue(KokohoDirectory.searchPersons(ctx, "Dana").isEmpty())
        assertTrue(KokohoDirectory.searchPersons(ctx, "Local Leak").isEmpty())
        assertTrue(KokohoDirectory.browsePersons(ctx).isEmpty())
        assertTrue(KokohoDirectory.searchPersonsWithTokens(ctx, listOf("HSI")).isEmpty())
        assertTrue(KokohoDirectory.suggestPersonPills(ctx, "Da", emptySet()).isEmpty())
        assertNull(KokohoDirectory.personById(ctx, "user-leak-1"))
        assertNull(KokohoDirectory.managedBy(ctx, "p-analyst-2"))
        assertTrue(KokohoDirectory.manages(ctx, "p-supervisor-1").isEmpty())

        // Attempt to save local user person as RESPONDER must be ignored
        KokohoDirectory.saveUserPerson(
            ctx,
            KokohoDirectory.PersonRecord(
                personId = "user-blocked-1",
                fullName = "Blocked Agent",
                title = "Agent",
                agencyCode = "DHS_ICE",
                agencyDisplay = "ICE",
                locationId = "loc:sector-annex-1"
            )
        )
        assertNull(KokohoDirectory.personById(ctx, "user-blocked-1"))

        val views = createTrackedViews()
        awaitViewsReady(views, statusCalls = statusCalls, expectedMinStatusCalls = 1)
        views.selectTab(KokohoViews.Tab.SEARCH)
        val sv = views.searchView!!
        sv.refreshNowSynchronously()

        assertFalse(
            "RESPONDER search hint must omit personnel",
            sv.searchInput.hint.toString().contains("personnel", ignoreCase = true)
        )
        assertFalse(
            "RESPONDER must NEVER see + Person suggestion pill",
            sv.getCurrentSuggestions().any { it.equals("Person", ignoreCase = true) }
        )
        val responderRows = sv.getCurrentResults()
        assertTrue(responderRows.none { it.schema == KokohoSearchView.SchemaKind.PERSON })
        val annexRow = responderRows.firstOrNull {
            it.schema == KokohoSearchView.SchemaKind.LOCATION && it.targetDetailKey == "loc:sector-annex-1"
        }
        assertNotNull("Sector Annex location row must be visible to RESPONDER", annexRow)
        assertFalse(annexRow!!.metaLine.contains("stationed", ignoreCase = true))

        // Manual "Person" pill or Tab commit as RESPONDER must not inject pill or wipe location results
        sv.addFilterPill("Person")
        sv.refreshNowSynchronously()
        assertFalse(sv.getActivePills().any { it.equals("Person", ignoreCase = true) })
        assertTrue(sv.getCurrentResults().none { it.schema == KokohoSearchView.SchemaKind.PERSON })
        assertTrue(sv.getCurrentResults().any { it.schema == KokohoSearchView.SchemaKind.LOCATION })
        sv.searchInput.setText("person")
        sv.refreshNowSynchronously()
        sv.searchInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        sv.searchInput.setText("")
        sv.refreshNowSynchronously()
        assertFalse(sv.getActivePills().any { it.equals("Person", ignoreCase = true) })
        assertTrue(sv.getCurrentResults().any { it.schema == KokohoSearchView.SchemaKind.LOCATION })

        // DeviceDetailActivity location & person views for RESPONDER
        val locDetailCtrl = Robolectric.buildActivity(
            DeviceDetailActivity::class.java,
            Intent(ctx, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, "loc:sector-annex-1")
        ).setup()
        val locDetailAct = locDetailCtrl.get()
        val locTexts = collectAllTexts(locDetailAct.window.decorView)
        assertTrue(locTexts.any { it.contains("FACILITY DETAILS") })
        assertFalse(locTexts.any { it.contains("STATIONED PERSONNEL") })
        locDetailCtrl.pause().stop().destroy()

        val personDetailCtrl = Robolectric.buildActivity(
            DeviceDetailActivity::class.java,
            Intent(ctx, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, "person:user-leak-1")
        ).setup()
        val personDetailAct = personDetailCtrl.get()
        val personTexts = collectAllTexts(personDetailAct.window.decorView)
        assertEquals("Access Restricted", personDetailAct.classificationText.text.toString())
        assertFalse(personTexts.any { it.contains("PERSONNEL RECORD") })
        assertFalse(personTexts.any { it.contains("Local Leak Agent") })
        personDetailCtrl.pause().stop().destroy()

        // 2. Unlock via INTELLIGENCE (and then verify OWNER too)
        useFullDirectory.set(true)
        for (roleName in listOf("INTELLIGENCE", "OWNER")) {
            activeRoleRef.set(roleName)
            DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-gate-1", slug = "inv-gate-1", roles = listOf(roleName))
            assertTrue(PriorityVault.syncAllBlocking(ctx))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(KokohoDirectory.isDirectoryAvailable)
            assertTrue(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
            assertEquals(3, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))

            sv.refreshNowSynchronously()
            assertTrue(
                "$roleName search hint must mention personnel",
                sv.searchInput.hint.toString().contains("personnel", ignoreCase = true)
            )
            assertTrue(
                "$roleName must see Person suggestion pill",
                sv.getCurrentSuggestions().any { it.equals("Person", ignoreCase = true) }
            )
            val intelRows = sv.getCurrentResults()
            assertTrue(
                "$roleName must see PERSON search rows",
                intelRows.any { it.schema == KokohoSearchView.SchemaKind.PERSON && it.title == "Dana Supervisor" }
            )
            val intelAnnex = intelRows.firstOrNull {
                it.schema == KokohoSearchView.SchemaKind.LOCATION && it.targetDetailKey == "loc:sector-annex-1"
            }
            assertNotNull(intelAnnex)
            assertTrue(intelAnnex!!.metaLine.contains("3 stationed"))
        }

        // DeviceDetailActivity unlocks for INTELLIGENCE/OWNER
        val intelLocCtrl = Robolectric.buildActivity(
            DeviceDetailActivity::class.java,
            Intent(ctx, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, "loc:sector-annex-1")
        ).setup()
        val intelLocAct = intelLocCtrl.get()
        val intelLocTexts = collectAllTexts(intelLocAct.window.decorView)
        assertTrue(intelLocTexts.any { it.contains("STATIONED PERSONNEL (3 / 3)") })
        intelLocCtrl.pause().stop().destroy()

        val intelPersonCtrl = Robolectric.buildActivity(
            DeviceDetailActivity::class.java,
            Intent(ctx, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, "person:p-supervisor-1")
        ).setup()
        val intelPersonAct = intelPersonCtrl.get()
        val intelPersonTexts = collectAllTexts(intelPersonAct.window.decorView)
        assertTrue(intelPersonTexts.any { it.contains("PERSONNEL RECORD") })
        assertTrue(intelPersonTexts.any { it.contains("Dana Supervisor") })
        intelPersonCtrl.pause().stop().destroy()

        views.destroy()
        assertEquals(0, DeviceTrustManager.trustListenerCountForTest())
    }

    @Test
    fun liveRoleDowngradeImmediatelyPurgesLeakCacheAndRefreshesWithEmptyPersonsEtag() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val isDowngraded = AtomicBoolean(false)
        val p2Inm = CopyOnWriteArrayList<String>()
        val p3Inm = CopyOnWriteArrayList<String>()
        val statusCalls = AtomicInteger(0)

        val hubUrl = startMockHub(
            statusResponseSupplier = {
                if (!isDowngraded.get()) {
                    """{"ok":true,"status":"ENROLLED","seat_id":"seat-dg-1","slug":"inv-dg-1","role":"INTELLIGENCE","roles":["RESPONDER","INTELLIGENCE"],"affinity_group":"Alpha"}"""
                } else {
                    """{"ok":true,"status":"ENROLLED","seat_id":"seat-dg-1","slug":"inv-dg-1","role":"RESPONDER","roles":["RESPONDER"],"affinity_group":null}"""
                }
            },
            p2DtoSupplier = { if (isDowngraded.get()) locationsOnlyP2Dto() else fullP2Dto() },
            p3DtoSupplier = { if (isDowngraded.get()) emptyP3Dto() else fullP3Dto() },
            p2IfNoneMatchSeen = p2Inm,
            p3IfNoneMatchSeen = p3Inm,
            statusCalls = statusCalls
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-dg-1",
            slug = "inv-dg-1",
            roles = listOf("RESPONDER", "INTELLIGENCE"),
            affinityGroup = "Alpha"
        )
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val views = createTrackedViews()
        awaitViewsReady(views, statusCalls = statusCalls, expectedMinStatusCalls = 1)
        views.selectTab(KokohoViews.Tab.SEARCH)
        val sv = views.searchView!!
        sv.addFilterPill("Person")
        sv.refreshNowSynchronously()

        assertTrue(sv.getCurrentResults().any { it.schema == KokohoSearchView.SchemaKind.PERSON })
        assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P1_PLATES_FILE).exists())
        assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).exists())
        assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())

        // Live downgrade on Hub -> checkDeviceStatusBlocking (deferring async sync while locked so we verify immediate pre-sync purge)
        isDowngraded.set(true)
        PriorityVault.simulateDeviceLockedForTest = true
        val seat = DeviceTrustManager.loadOrExtractSeatIdentity(ctx)!!
        val statusDto = DeviceTrustManager.checkDeviceStatusBlocking(ctx, seat)
        assertNotNull(statusDto)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Immediate purge guarantees (before network p2/p3 resync)
        assertFalse(DeviceTrustManager.hasIntelligenceAccess(ctx))
        assertNull(DeviceTrustManager.enrolledAffinityGroup(ctx))
        assertTrue("p1_plates.seal must remain intact on role downgrade", File(PriorityVault.vaultDir(ctx), PriorityVault.P1_PLATES_FILE).exists())
        assertFalse("p2_sector_dir.seal must be deleted immediately on downgrade", File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).exists())
        assertFalse("p3_ext_dir.seal must be deleted immediately on downgrade", File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
        val purgedMeta = PriorityVault.readVaultMeta(ctx)
        assertEquals("", purgedMeta.p2Etag)
        assertEquals("", purgedMeta.p3Etag)
        assertTrue(purgedMeta.p1SinceMs > 0L)
        assertNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
        assertEquals(0, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))

        sv.refreshNowSynchronously()
        assertFalse("Person pill must be pruned on downgrade", sv.getActivePills().any { it.equals("Person", ignoreCase = true) })
        assertFalse(sv.searchInput.hint.toString().contains("personnel", ignoreCase = true))
        assertTrue(sv.getCurrentResults().none { it.schema == KokohoSearchView.SchemaKind.PERSON })

        // Subsequent PriorityVault.syncAllBlocking (unlocked) fetches new ETag facility-only p2 + empty p3
        PriorityVault.simulateDeviceLockedForTest = false
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("etag-p2-loc-only", PriorityVault.readVaultMeta(ctx).p2Etag)
        assertEquals("etag-p3-empty", PriorityVault.readVaultMeta(ctx).p3Etag)
        val p2ResyncedBytes = File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).readBytes()
        assertFalse("Resynced p2_sector_dir.seal must not contain plaintext leak names", String(p2ResyncedBytes, Charsets.ISO_8859_1).contains("Dana Supervisor"))
        assertNotNull("Facility locations must survive downgrade resync", KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))
        assertFalse("isDirectoryAvailable must remain false with empty persons", KokohoDirectory.isDirectoryAvailable)
        assertEquals(1, KokohoHubClient.syncedPlates(ctx).size)

        sv.refreshNowSynchronously()
        assertFalse(sv.getCurrentSuggestions().any { it.equals("Person", ignoreCase = true) })
        val postSyncAnnex = sv.getCurrentResults().firstOrNull {
            it.schema == KokohoSearchView.SchemaKind.LOCATION && it.targetDetailKey == "loc:sector-annex-1"
        }
        assertNotNull(postSyncAnnex)
        assertFalse(postSyncAnnex!!.metaLine.contains("stationed", ignoreCase = true))

        views.destroy()
        assertEquals(0, DeviceTrustManager.trustListenerCountForTest())
    }

    @Test
    fun staleEnsureEnrolledResponseDoesNotClobberNewerIntelligenceEnrollment() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val statusEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val useFullDir = AtomicBoolean(false)
        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-race-1","slug":"inv-race-1","role":"RESPONDER","roles":["RESPONDER"]}"""
            },
            p2DtoSupplier = { if (useFullDir.get()) fullP2Dto() else locationsOnlyP2Dto() },
            p3DtoSupplier = { if (useFullDir.get()) fullP3Dto() else emptyP3Dto() },
            onAuthStatusEnter = {
                statusEntered.countDown()
                releaseStatus.await(5, TimeUnit.SECONDS)
            }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-race-1", slug = "inv-race-1", roles = listOf("RESPONDER"))

        val views = createTrackedViews()
        try {
            assertTrue("/v1/auth/status handler must be entered", statusEntered.await(5, TimeUnit.SECONDS))

            useFullDir.set(true)
            DeviceTrustManager.setEnrolledForTest(
                ctx,
                seatId = "seat-race-1",
                slug = "inv-race-1",
                role = "INTELLIGENCE",
                roles = listOf("INTELLIGENCE")
            )
            assertTrue(PriorityVault.syncAllBlocking(ctx))
            releaseStatus.countDown()

            assertTrue(
                DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            )
            assertTrue("Newer INTELLIGENCE role must not be clobbered by stale RESPONDER response", DeviceTrustManager.hasIntelligenceAccess(ctx))
            assertTrue("Synced personnel directory must remain available", KokohoDirectory.isDirectoryAvailable)
        } finally {
            releaseStatus.countDown()
            views.destroy()
        }
    }

    @Test
    fun p2LocationsOnlyWithP3FailurePurgesStaleP3PersonnelImmediately() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p2LocOnlyPhase = AtomicBoolean(false)
        val p3ErrorInjected = AtomicReference<Int?>(null)
        val observedRegionalDuringP3 = AtomicReference<KokohoDirectory.PersonRecord?>(null)

        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-p3-purge","slug":"inv-p3-purge","role":"INTELLIGENCE","roles":["INTELLIGENCE"]}"""
            },
            p2DtoSupplier = { if (p2LocOnlyPhase.get()) locationsOnlyP2Dto() else fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            onP3Enter = {
                if (p2LocOnlyPhase.get()) {
                    observedRegionalDuringP3.set(KokohoDirectory.personById(ctx, "p-regional-3"))
                }
            },
            p3ForceErrorCode = { p3ErrorInjected.get() }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-p3-purge",
            slug = "inv-p3-purge",
            role = "INTELLIGENCE",
            roles = listOf("INTELLIGENCE")
        )
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertNotNull(KokohoDirectory.personById(ctx, "p-regional-3"))
        assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())

        // Now Hub returns locations-only p2 and HTTP 500 on p3 before client status poll runs
        p2LocOnlyPhase.set(true)
        p3ErrorInjected.set(500)
        assertTrue(PriorityVault.syncAllBlocking(ctx))

        assertNull("p3 personnel must never be exposed during p2->p3 window", observedRegionalDuringP3.get())
        assertFalse(
            "Stale p3_ext_dir.seal must be purged when p2 is locations-only and p3 fails",
            File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists()
        )
        assertEquals("", PriorityVault.readVaultMeta(ctx).p3Etag)
        assertNotNull(KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))
        assertNull(KokohoDirectory.personById(ctx, "p-regional-3"))
        assertNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
        assertFalse(KokohoDirectory.isDirectoryAvailable)
    }

    @Test
    fun inFlightFullDirectoryResponseIsNotSealedAfterLiveDowngrade() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p2Entered = CountDownLatch(1)
        val releaseP2 = CountDownLatch(1)
        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-inflight-dg","slug":"inv-inflight-dg","role":"INTELLIGENCE","roles":["INTELLIGENCE"]}"""
            },
            p2DtoSupplier = { fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            onP2Enter = {
                p2Entered.countDown()
                releaseP2.await(5, TimeUnit.SECONDS)
            }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-inflight-dg",
            slug = "inv-inflight-dg",
            role = "INTELLIGENCE",
            roles = listOf("INTELLIGENCE")
        )

        val syncDone = CountDownLatch(1)
        val bgThread = Thread {
            try {
                PriorityVault.syncAllBlocking(ctx)
            } finally {
                syncDone.countDown()
            }
        }
        bgThread.start()

        try {
            assertTrue("p2_sector_dir handler must be entered", p2Entered.await(5, TimeUnit.SECONDS))
            DeviceTrustManager.setEnrolledForTest(
                ctx,
                seatId = "seat-inflight-dg",
                slug = "inv-inflight-dg",
                role = "RESPONDER",
                roles = listOf("RESPONDER")
            )
            releaseP2.countDown()
            assertTrue(syncDone.await(5, TimeUnit.SECONDS))

            val p2File = File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE)
            val p3File = File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE)
            assertFalse("In-flight full p2 payload must NOT be sealed after live downgrade", p2File.exists())
            assertFalse("In-flight full p3 payload must NOT be sealed after live downgrade", p3File.exists())
            assertFalse(KokohoDirectory.isDirectoryAvailable)
            assertNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
        } finally {
            releaseP2.countDown()
        }
    }

    @Test
    fun reportWizardAutocompleteAndWitnessSubmissionGate() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-wiz-1","slug":"inv-wiz-1","role":"RESPONDER","roles":["RESPONDER"]}"""
            },
            p2DtoSupplier = { fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        val actCtrl = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        createdActivities += actCtrl
        val act = actCtrl.get()
        act.setTheme(R.style.Theme_Kokoho)

        // 1. RESPONDER: zero agent autocomplete, saves WitnessReportRecord without personId or userPersons leak
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-wiz-1", slug = "inv-wiz-1", roles = listOf("RESPONDER"))
        var responderSubmittedKey: String? = null
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.LAW_ENFORCEMENT,
            droppedLat = 32.21,
            droppedLon = -110.96
        ) { entityKey ->
            responderSubmittedKey = entityKey
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val respDialog = ShadowDialog.getLatestDialog()
        assertNotNull(respDialog)
        val respDecor = respDialog.window!!.decorView
        val respEdits = findViewsByType<EditText>(respDecor)
        val respNameInput = respEdits.first { it.hint?.toString()?.contains("Full Name", ignoreCase = true) == true }
        val respBadgeInput = respEdits.first { it.hint?.toString()?.contains("Badge #", ignoreCase = true) == true }

        respNameInput.setText("Dana Supervisor")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val respTexts = collectAllTexts(respDecor)
        assertFalse(
            "RESPONDER must NOT receive personnel autocomplete suggestions",
            respTexts.any { it.contains("Dana Supervisor (HSI)") }
        )
        respBadgeInput.setText("B-404")
        val respSaveBtn = findViewsByType<TextView>(respDecor).first { it.text?.toString() == "Save Report" }
        respSaveBtn.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(responderSubmittedKey)
        val respUuid = responderSubmittedKey!!.removePrefix("kokoho:")
        val respReport = KokohoSignatures.getWitnessReport(ctx, respUuid, approvedOnly = false)
        assertNotNull(respReport)
        assertEquals("Dana Supervisor", respReport!!.agentName)
        assertEquals("B-404", respReport.badgeNumber)
        assertNull("RESPONDER witness report must not resolve personId", respReport.personId)
        assertEquals("PENDING", respReport.status)
        assertNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
        assertTrue(ctx.getSharedPreferences("kokoho_agency_prefs", Context.MODE_PRIVATE).getString("kokoho_user_persons_json", null).isNullOrBlank())

        // 2. INTELLIGENCE: syncs directory, shows autocomplete chip, binds personId
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-wiz-1", slug = "inv-wiz-1", role = "INTELLIGENCE", roles = listOf("INTELLIGENCE"))
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        var intelSubmittedKey: String? = null
        KokohoReportWizard.open(
            activity = act,
            mode = KokohoReportWizard.Mode.LAW_ENFORCEMENT,
            droppedLat = 32.22,
            droppedLon = -110.97
        ) { entityKey ->
            intelSubmittedKey = entityKey
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val intelDialog = ShadowDialog.getLatestDialog()
        assertNotNull(intelDialog)
        val intelDecor = intelDialog.window!!.decorView
        val intelNameInput = findViewsByType<EditText>(intelDecor)
            .first { it.hint?.toString()?.contains("Full Name", ignoreCase = true) == true }
        intelNameInput.setText("Dana")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val suggestionLabel = findViewsByType<TextView>(intelDecor)
            .firstOrNull { it.text?.toString() == "Dana Supervisor (HSI)" }
        assertNotNull("INTELLIGENCE must receive personnel autocomplete suggestion chip", suggestionLabel)
        (suggestionLabel!!.parent as View).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val intelSaveBtn = findViewsByType<TextView>(intelDecor).first { it.text?.toString() == "Save Report" }
        intelSaveBtn.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(intelSubmittedKey)
        val intelUuid = intelSubmittedKey!!.removePrefix("kokoho:")
        val intelReport = KokohoSignatures.getWitnessReport(ctx, intelUuid, approvedOnly = false)
        assertNotNull(intelReport)
        assertEquals("p-supervisor-1", intelReport!!.personId)
        assertNotNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
    }

    @Test
    fun p2LocationsOnlyWithEmptyDeltaP1AndP3FailurePurgesStaleP3PersonnelDuringWindowAndAfterFailure() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val phase2 = AtomicBoolean(false)
        val p3ErrorInjected = AtomicReference<Int?>(null)
        val p3Entered = CountDownLatch(1)
        val releaseP3 = CountDownLatch(1)

        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-b1a","slug":"inv-b1a","role":"INTELLIGENCE","roles":["INTELLIGENCE"]}"""
            },
            p1ResponseSupplier = {
                if (!phase2.get()) {
                    PriorityVault.PlatesSyncResponseDto(
                        mode = "SNAPSHOT",
                        serverTimeMs = 1_500L,
                        plates = listOf(
                            KokohoHubClient.SyncedPlateRecord(
                                plateId = "pl-1",
                                plateNumber = "TEST101",
                                stateCode = "AZ",
                                agencyCode = "DHS_ICE",
                                suspectedAgency = "ICE",
                                color = "White",
                                make = "Ford",
                                model = "Explorer",
                                updatedAtMs = 1_400L
                            )
                        )
                    )
                } else {
                    PriorityVault.PlatesSyncResponseDto(
                        mode = "DELTA",
                        serverTimeMs = 2_000L,
                        plates = emptyList()
                    )
                }
            },
            p2DtoSupplier = { if (phase2.get()) locationsOnlyP2Dto() else fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            onP3Enter = {
                if (phase2.get()) {
                    p3Entered.countDown()
                    releaseP3.await(5, TimeUnit.SECONDS)
                }
            },
            p3ForceErrorCode = { p3ErrorInjected.get() }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-b1a",
            slug = "inv-b1a",
            role = "INTELLIGENCE",
            roles = listOf("INTELLIGENCE")
        )
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertNotNull(KokohoDirectory.personById(ctx, "p-regional-3"))
        assertTrue(KokohoDirectory.isDirectoryAvailable)
        assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())

        phase2.set(true)
        p3ErrorInjected.set(500)
        val syncOk = AtomicBoolean(false)
        val syncDone = CountDownLatch(1)
        val bgThread = Thread {
            try {
                syncOk.set(PriorityVault.syncAllBlocking(ctx))
            } finally {
                syncDone.countDown()
            }
        }
        bgThread.start()

        try {
            assertTrue("p3_ext_dir handler must be entered", p3Entered.await(5, TimeUnit.SECONDS))
            assertFalse(
                "isDirectoryAvailable must be false during p2->p3 window even with empty DELTA p1",
                KokohoDirectory.isDirectoryAvailable
            )
            assertNull(
                "p-regional-3 must not leak during p2->p3 window with empty DELTA p1",
                KokohoDirectory.personById(ctx, "p-regional-3")
            )
            assertNull(
                "p-supervisor-1 must not leak during p2->p3 window with empty DELTA p1",
                KokohoDirectory.personById(ctx, "p-supervisor-1")
            )
            assertNotNull(
                "Synced facility location must remain accessible during p2->p3 window",
                KokohoDirectory.locationById(ctx, "loc:sector-annex-1")
            )

            releaseP3.countDown()
            assertTrue(syncDone.await(5, TimeUnit.SECONDS))
            assertTrue(syncOk.get())
            assertFalse(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
            assertEquals("", PriorityVault.readVaultMeta(ctx).p3Etag)
            assertFalse(KokohoDirectory.isDirectoryAvailable)
            assertNull(KokohoDirectory.personById(ctx, "p-regional-3"))
            assertNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
            assertNotNull(KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))
        } finally {
            releaseP3.countDown()
        }
    }

    @Test
    fun p2LocationsOnly304WithFailedP1SuppressesStaleP3DuringWindowAndAfterFailure() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val phase2 = AtomicBoolean(false)
        val p1Err = AtomicReference<Int?>(null)
        val p3Err = AtomicReference<Int?>(null)
        val p3Entered = CountDownLatch(1)
        val releaseP3 = CountDownLatch(1)

        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-b1b","slug":"inv-b1b","role":"INTELLIGENCE","roles":["INTELLIGENCE"]}"""
            },
            p1ForceErrorCode = { p1Err.get() },
            p2DtoSupplier = { locationsOnlyP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            onP3Enter = {
                if (phase2.get()) {
                    p3Entered.countDown()
                    releaseP3.await(5, TimeUnit.SECONDS)
                }
            },
            p3ForceErrorCode = { p3Err.get() }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-b1b",
            slug = "inv-b1b",
            role = "INTELLIGENCE",
            roles = listOf("INTELLIGENCE")
        )
        // Phase 1: seed locations-only p2 (etag-p2-loc-only) + full p3 (etag-p3-full)
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertNotNull(KokohoDirectory.personById(ctx, "p-regional-3"))
        assertTrue(KokohoDirectory.isDirectoryAvailable)

        // Phase 2: p1 fails (500), p2 returns 304 (etag-p2-loc-only), p3 blocks then returns 500
        phase2.set(true)
        p1Err.set(500)
        p3Err.set(500)
        val syncDone = CountDownLatch(1)
        val bgThread = Thread {
            try {
                PriorityVault.syncAllBlocking(ctx)
            } finally {
                syncDone.countDown()
            }
        }
        bgThread.start()

        try {
            assertTrue("p3_ext_dir handler must be entered", p3Entered.await(5, TimeUnit.SECONDS))
            assertFalse(
                "isDirectoryAvailable must be false during 304 p2->p3 window",
                KokohoDirectory.isDirectoryAvailable
            )
            assertNull(
                "p-regional-3 must not leak during 304 p2->p3 window with failed p1",
                KokohoDirectory.personById(ctx, "p-regional-3")
            )
            assertNotNull(KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))

            releaseP3.countDown()
            assertTrue(syncDone.await(5, TimeUnit.SECONDS))
            assertFalse(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
            assertEquals("", PriorityVault.readVaultMeta(ctx).p3Etag)
            assertFalse(KokohoDirectory.isDirectoryAvailable)
            assertNull(KokohoDirectory.personById(ctx, "p-regional-3"))
            assertNotNull(KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))
        } finally {
            releaseP3.countDown()
        }
    }

    @Test
    fun userPersonsAndDirectoryAreWithheldWhileScreenOffOrLockedAndRestoreOnScreenOn() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-up-screen",
            slug = "inv-up-screen",
            role = "INTELLIGENCE",
            roles = listOf("INTELLIGENCE")
        )
        // Intentionally zero .seal files exist to exercise userPersons screen-on recovery without vault tiers
        assertFalse(PriorityVault.hasSyncedDirectoryFiles(ctx))

        KokohoDirectory.saveUserPerson(
            ctx,
            KokohoDirectory.PersonRecord(
                personId = "user-local-1",
                fullName = "Local Field Witness",
                title = "Field Liaison",
                agencyCode = "DHS_HSI",
                agencyDisplay = "HSI",
                locationId = "loc:sector-annex-1"
            )
        )
        assertEquals("Local Field Witness", KokohoDirectory.personById(ctx, "user-local-1")?.fullName)
        assertTrue(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
        assertEquals(1, KokohoDirectory.searchPersons(ctx, "Local Field").size)
        assertEquals(1, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))

        // Screen off clears RAM and withholds userPersons even if ensureLoaded runs while screen is off
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(PriorityVault.isInMemoryCacheZeroed())
        assertTrue(KokohoDirectory.allLocations(ctx).isNotEmpty())
        assertNull(KokohoDirectory.personById(ctx, "user-local-1"))
        assertFalse(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
        assertTrue(KokohoDirectory.searchPersons(ctx, "Local Field").isEmpty())
        assertTrue(KokohoDirectory.browsePersons(ctx).isEmpty())
        assertEquals(0, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))
        assertTrue(KokohoDirectory.stationedPersonnel(ctx, "loc:sector-annex-1").isEmpty())
        assertFalse(KokohoDirectory.isDirectoryAvailable)

        // Screen on alone restores userPersons cleanly even with no .seal files on disk
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Local Field Witness", KokohoDirectory.personById(ctx, "user-local-1")?.fullName)
        assertTrue(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
        assertEquals(1, KokohoDirectory.searchPersons(ctx, "Local Field").size)
        assertEquals(1, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))

        // Locked without ACTION_SCREEN_OFF also withholds userPersons and restores upon unlock
        PriorityVault.simulateDeviceLockedForTest = true
        assertNull(KokohoDirectory.personById(ctx, "user-local-1"))
        assertFalse(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
        assertEquals(0, KokohoDirectory.stationedPersonnelCount(ctx, "loc:sector-annex-1"))

        PriorityVault.simulateDeviceLockedForTest = false
        assertEquals("Local Field Witness", KokohoDirectory.personById(ctx, "user-local-1")?.fullName)
        assertTrue(KokohoDirectory.isPersonnelDirectoryAvailable(ctx))
    }

    @Test
    fun staleEnsureEnrolledFallbackAfter500DoesNotClobberNewerSeat() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val statusEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val enrollCalls = AtomicInteger(0)

        File(ctx.noBackupFilesDir, "seat_identity.json").writeText(
            """{"seat_id":"seat-OLD-A","slug":"inv-OLD-A","voucher":"v-old"}""",
            Charsets.UTF_8
        )

        val hubUrl = startMockHub(
            statusCodeSupplier = { 500 },
            statusResponseSupplier = { """{"error":"status_unavailable"}""" },
            enrollCalls = enrollCalls,
            enrollResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-OLD-A","slug":"inv-OLD-A","role":"RESPONDER","roles":["RESPONDER"]}"""
            },
            p2DtoSupplier = { fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            onAuthStatusEnter = {
                statusEntered.countDown()
                releaseStatus.await(5, TimeUnit.SECONDS)
            }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        try {
            DeviceTrustManager.ensureEnrolledAsync(ctx)
            assertTrue("/v1/auth/status handler must be entered", statusEntered.await(5, TimeUnit.SECONDS))

            DeviceTrustManager.setEnrolledForTest(
                ctx,
                seatId = "seat-NEW-B",
                slug = "inv-NEW-B",
                role = "INTELLIGENCE",
                roles = listOf("INTELLIGENCE"),
                affinityGroup = "Core"
            )
            assertTrue(PriorityVault.syncAllBlocking(ctx))

            releaseStatus.countDown()
            assertTrue(
                DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            )

            assertTrue(DeviceTrustManager.isEnrolled(ctx))
            assertEquals("seat-NEW-B", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
            assertEquals("inv-NEW-B", DeviceTrustManager.currentSeatSlug(ctx))
            assertTrue(DeviceTrustManager.hasIntelligenceAccess(ctx))
            assertEquals("Core", DeviceTrustManager.enrolledAffinityGroup(ctx))
            assertEquals(0, enrollCalls.get())
        } finally {
            releaseStatus.countDown()
        }
    }

    @Test
    fun staleEnsureEnrolled403RevokedDoesNotWipeNewerSeat() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val statusEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val statusCalls = AtomicInteger(0)
        val useFullDir = AtomicBoolean(false)

        val hubUrl = startMockHub(
            statusCalls = statusCalls,
            statusCodeSupplier = { 403 },
            statusExtraHeadersSupplier = { mapOf("x-kokoho-seat-status" to "REVOKED") },
            statusResponseSupplier = { """{"ok":false,"status":"REVOKED","message":"Seat revoked"}""" },
            p2DtoSupplier = { if (useFullDir.get()) fullP2Dto() else locationsOnlyP2Dto() },
            p3DtoSupplier = { if (useFullDir.get()) fullP3Dto() else emptyP3Dto() },
            onAuthStatusEnter = {
                if (statusCalls.get() == 1) {
                    statusEntered.countDown()
                    releaseStatus.await(5, TimeUnit.SECONDS)
                }
            }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-OLD-A",
            slug = "inv-OLD-A",
            role = "RESPONDER",
            roles = listOf("RESPONDER")
        )

        try {
            DeviceTrustManager.ensureEnrolledAsync(ctx)
            assertTrue("/v1/auth/status handler must be entered", statusEntered.await(5, TimeUnit.SECONDS))

            useFullDir.set(true)
            DeviceTrustManager.setEnrolledForTest(
                ctx,
                seatId = "seat-NEW-B",
                slug = "inv-NEW-B",
                role = "INTELLIGENCE",
                roles = listOf("INTELLIGENCE"),
                affinityGroup = "Core"
            )
            assertTrue(PriorityVault.syncAllBlocking(ctx))

            releaseStatus.countDown()
            assertTrue(
                DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            )

            assertTrue("Newer seat-NEW-B must remain enrolled", DeviceTrustManager.isEnrolled(ctx))
            assertEquals("ENROLLED", DeviceTrustManager.enrollmentStatus(ctx))
            assertEquals("seat-NEW-B", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
            assertTrue(DeviceTrustManager.hasIntelligenceAccess(ctx))
            assertEquals("Core", DeviceTrustManager.enrolledAffinityGroup(ctx))
            assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).exists())
            assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
            assertTrue(KokohoDirectory.isDirectoryAvailable)
            assertNotNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))

            // Genuine non-stale 403 REVOKED on seat-NEW-B at current epoch must still wipe immediately
            val currentSeat = DeviceTrustManager.loadOrExtractSeatIdentity(ctx)!!
            DeviceTrustManager.checkDeviceStatusBlocking(ctx, currentSeat)
            assertFalse(DeviceTrustManager.isEnrolled(ctx))
            assertEquals("REVOKED", DeviceTrustManager.enrollmentStatus(ctx))
            assertFalse(File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).exists())
            assertFalse(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
            assertNull(KokohoDirectory.personById(ctx, "p-supervisor-1"))
        } finally {
            releaseStatus.countDown()
        }
    }

    @Test
    fun claimSeatBySlugIsNotInvalidatedByInFlightStartupStatusPoll() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val statusEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val enrollEntered = CountDownLatch(1)
        val releaseEnroll = CountDownLatch(1)

        val hubUrl = startMockHub(
            statusResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-OLD-A","slug":"inv-OLD-A","role":"RESPONDER","roles":["RESPONDER"]}"""
            },
            enrollResponseSupplier = {
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-NEW-B","slug":"inv-NEW-B","role":"INTELLIGENCE","roles":["INTELLIGENCE"]}"""
            },
            p2DtoSupplier = { fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            onAuthStatusEnter = {
                statusEntered.countDown()
                releaseStatus.await(5, TimeUnit.SECONDS)
            },
            onAuthEnrollEnter = {
                enrollEntered.countDown()
                releaseEnroll.await(5, TimeUnit.SECONDS)
            }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-OLD-A",
            slug = "inv-OLD-A",
            role = "RESPONDER",
            roles = listOf("RESPONDER")
        )
        val oldSeat = DeviceTrustManager.loadOrExtractSeatIdentity(ctx)!!
        val statusDone = CountDownLatch(1)
        val statusThread = Thread {
            try {
                DeviceTrustManager.checkDeviceStatusBlocking(ctx, oldSeat)
            } finally {
                statusDone.countDown()
            }
        }
        statusThread.start()

        try {
            assertTrue("Status poll must enter handler", statusEntered.await(5, TimeUnit.SECONDS))

            val claimDone = CountDownLatch(1)
            var claimOk = false
            var claimMsg = ""
            DeviceTrustManager.claimSeatBySlug(ctx, "inv-NEW-B") { ok, msg ->
                claimOk = ok
                claimMsg = msg
                claimDone.countDown()
            }
            assertTrue("Enroll handler must be entered", enrollEntered.await(5, TimeUnit.SECONDS))

            releaseStatus.countDown()
            assertTrue("In-flight status poll must finish", statusDone.await(5, TimeUnit.SECONDS))

            releaseEnroll.countDown()
            assertTrue(
                DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            )
            assertTrue("claimSeatBySlug callback must fire", claimDone.await(5, TimeUnit.SECONDS))
            assertTrue(claimOk)
            assertEquals("This phone is enrolled (Intelligence).", claimMsg)
            assertEquals("seat-NEW-B", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
            assertTrue(DeviceTrustManager.hasIntelligenceAccess(ctx))
        } finally {
            releaseStatus.countDown()
            releaseEnroll.countDown()
        }
    }

    @Test
    fun staleEnvelopedRequest403RevokedAnd419AfterSeatSwitchDoesNotWipeOrInvalidateNewerSeat() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handshakeCount = AtomicInteger(0)
        val stateEntered = CountDownLatch(1)
        val releaseState = CountDownLatch(1)
        val checkinEntered = CountDownLatch(1)
        val releaseCheckin = CountDownLatch(1)
        val checkinBlockFirst = AtomicBoolean(true)
        val stateErr = AtomicReference<Triple<Int, Map<String, String>, String>?>(null)
        val checkinErr = AtomicReference<Triple<Int, Map<String, String>, String>?>(null)
        val enrollPayload = AtomicReference(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-NEW-B","slug":"inv-NEW-B","role":"INTELLIGENCE","roles":["INTELLIGENCE"],"affinity_group":"Core"}"""
        )

        val hubUrl = startMockHub(
            statusResponseSupplier = { enrollPayload.get() },
            p2DtoSupplier = { fullP2Dto() },
            p3DtoSupplier = { fullP3Dto() },
            handshakeCount = handshakeCount,
            onStateEnter = {
                stateEntered.countDown()
                releaseState.await(5, TimeUnit.SECONDS)
            },
            stateForcedError = { stateErr.get() },
            onCheckinEnter = {
                if (checkinBlockFirst.compareAndSet(true, false)) {
                    checkinEntered.countDown()
                    releaseCheckin.await(5, TimeUnit.SECONDS)
                }
            },
            checkinForcedError = { checkinErr.get() }
        )
        KokohoHubClient.setHubUrl(ctx, hubUrl)
        VpnGeoGate.testOverrideVerdict = VpnGeoGate.Verdict.ALLOWED

        // Phase A: Stale /v1/state 403 REVOKED after seat switch via claimSeatBySlug
        DeviceTrustManager.setEnrolledForTest(
            ctx,
            seatId = "seat-OLD-A",
            slug = "inv-OLD-A",
            role = "RESPONDER",
            roles = listOf("RESPONDER")
        )
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertEquals(1, handshakeCount.get())

        val stateResp = AtomicReference<DeviceTrustManager.EnvelopedHttpResponse?>(
            DeviceTrustManager.EnvelopedHttpResponse(200, "sentinel")
        )
        val stateDone = CountDownLatch(1)
        val stateThread = Thread {
            try {
                stateResp.set(DeviceTrustManager.executeEnvelopedGet(ctx, "/v1/state"))
            } finally {
                stateDone.countDown()
            }
        }
        stateThread.start()

        try {
            assertTrue("/v1/state handler must be entered", stateEntered.await(5, TimeUnit.SECONDS))

            val claimLatch = CountDownLatch(1)
            var claimOk = false
            DeviceTrustManager.claimSeatBySlug(ctx, "inv-NEW-B") { ok, _ ->
                claimOk = ok
                claimLatch.countDown()
            }
            assertTrue(
                DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            )
            assertTrue(claimLatch.await(5, TimeUnit.SECONDS) && claimOk)
            assertTrue(PriorityVault.syncAllBlocking(ctx))
            assertEquals(2, handshakeCount.get())
            assertTrue(DeviceTrustManager.hasActiveSessionKey())

            stateErr.set(Triple(403, mapOf("x-kokoho-seat-status" to "REVOKED"), """{"ok":false,"status":"REVOKED"}"""))
            releaseState.countDown()
            assertTrue(stateDone.await(5, TimeUnit.SECONDS))
            assertNull("Stale /v1/state must return null", stateResp.get())

            assertTrue("Newer seat-NEW-B must remain enrolled", DeviceTrustManager.isEnrolled(ctx))
            assertEquals("seat-NEW-B", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
            assertTrue(DeviceTrustManager.hasActiveSessionKey())
            assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).exists())
            assertTrue(File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
            assertTrue(KokohoDirectory.isDirectoryAvailable)

            // Phase B: Stale /v1/checkin?since=0 HTTP 419 after seat switch to seat-NEW-C
            enrollPayload.set(
                """{"ok":true,"status":"ENROLLED","seat_id":"seat-NEW-C","slug":"inv-NEW-C","role":"INTELLIGENCE","roles":["INTELLIGENCE"],"affinity_group":"Core"}"""
            )
            val checkinResp = AtomicReference<DeviceTrustManager.EnvelopedHttpResponse?>(
                DeviceTrustManager.EnvelopedHttpResponse(200, "sentinel")
            )
            val checkinDone = CountDownLatch(1)
            val checkinThread = Thread {
                try {
                    checkinResp.set(DeviceTrustManager.executeEnvelopedPost(ctx, "/v1/checkin?since=0", "{}"))
                } finally {
                    checkinDone.countDown()
                }
            }
            checkinThread.start()

            assertTrue("/v1/checkin handler must be entered", checkinEntered.await(5, TimeUnit.SECONDS))

            val claimLatch2 = CountDownLatch(1)
            var claimOk2 = false
            DeviceTrustManager.claimSeatBySlug(ctx, "inv-NEW-C") { ok, _ ->
                claimOk2 = ok
                claimLatch2.countDown()
            }
            assertTrue(
                DeviceTrustManager.awaitAsyncIdleForTest(5_000L) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            )
            assertTrue(claimLatch2.await(5, TimeUnit.SECONDS) && claimOk2)
            assertTrue(PriorityVault.syncAllBlocking(ctx))
            val handshakesBefore419 = handshakeCount.get()
            assertEquals(3, handshakesBefore419)
            assertTrue(DeviceTrustManager.hasActiveSessionKey())

            checkinErr.set(Triple(419, emptyMap(), """{"error":"SESSION_KEY_EXPIRED"}"""))
            releaseCheckin.countDown()
            assertTrue(checkinDone.await(5, TimeUnit.SECONDS))
            assertNull("Stale 419 request must return null without retrying", checkinResp.get())
            assertEquals("Stale 419 must not trigger renegotiation", handshakesBefore419, handshakeCount.get())
            assertTrue("Newer seat session key must remain active", DeviceTrustManager.hasActiveSessionKey())

            // Verify newer seat-NEW-C still succeeds on /v1/checkin using existing sk3 (no extra handshake)
            checkinErr.set(null)
            val freshCheckin = DeviceTrustManager.executeEnvelopedPost(ctx, "/v1/checkin?since=0", "{}")
            assertNotNull(freshCheckin)
            assertEquals(200, freshCheckin!!.code)
            assertEquals(handshakesBefore419, handshakeCount.get())
        } finally {
            releaseState.countDown()
            releaseCheckin.countDown()
        }
    }
}
