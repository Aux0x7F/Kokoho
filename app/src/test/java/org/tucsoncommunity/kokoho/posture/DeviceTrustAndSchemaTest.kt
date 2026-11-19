package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.tucsoncommunity.kokoho.BuildConfig
import org.tucsoncommunity.kokoho.databinding.ActivityMainBinding
import org.tucsoncommunity.kokoho.ui.WebAuthQrDialog
import java.io.File
import java.lang.reflect.Modifier
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceTrustAndSchemaTest {

    private var activeServer: HttpServer? = null

    @After
    fun tearDown() {
        activeServer?.stop(0)
        activeServer = null
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PriorityVault.simulateDeviceLockedForTest = false
        DeviceTrustManager.resetTrustForTest(ctx)
        KokohoHubClient.clearAllOnRevocation(ctx)
        KokohoHubClient.setHubUrl(ctx, KokohoHubClient.DEFAULT_HUB_URL)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun unifiedSingleBuildVersionAndZeroBundledEncAsset() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(1421, BuildConfig.VERSION_CODE)
        assertEquals("1.3.13", BuildConfig.VERSION_NAME)
        assertEquals("/v1/update/latest", BuildConfig.UPDATE_MANIFEST_PATH)

        val refAssets = runCatching { ctx.assets.list("reference")?.toList().orEmpty() }.getOrDefault(emptyList())
        assertTrue("reference/public_locations.tsv.gz must remain bundled", refAssets.any { it.startsWith("public_locations") })
        assertFalse("Zero directory_ext* asset may exist in v1.3.12 unified APK", refAssets.any { it.contains("directory_ext", ignoreCase = true) })
    }

    @Test
    fun apkSigningBlockSeatIdentityParserExtractsCustomBlock() {
        val json = """{"seat_id":"seat-007","slug":"inv-0123456789abcdef","voucher":"deadbeef001122"}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val pairLen = 4L + jsonBytes.size
        val pairTotalBytes = 8 + 4 + jsonBytes.size
        val blockSizeField = (pairTotalBytes + 24).toLong()

        val zipPrefix = ByteArray(64) { 0x50 }
        val sigBlockStart = zipPrefix.size
        val sigBlockBuf = ByteBuffer.allocate(8 + pairTotalBytes + 24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(blockSizeField)
            putLong(pairLen)
            putInt(0x54434B56)
            put(jsonBytes)
            putLong(blockSizeField)
            put("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        }.array()

        val cdOffset = sigBlockStart + sigBlockBuf.size
        val fakeCd = ByteArray(32)
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
            putShort(0)
            putShort(0)
            putShort(0)
            putShort(0)
            putInt(fakeCd.size)
            putInt(cdOffset)
            putShort(0)
        }.array()

        val tmp = File.createTempFile("kokoho-sigblock-test", ".apk")
        try {
            tmp.outputStream().use { out ->
                out.write(zipPrefix)
                out.write(sigBlockBuf)
                out.write(fakeCd)
                out.write(eocd)
            }
            val parsed = DeviceTrustManager.readSeatIdentityFromApk(tmp)
            assertNotNull("Must parse 0x54434B56 block from APK Signing Block", parsed)
            assertEquals("seat-007", parsed!!.seatId)
            assertEquals("inv-0123456789abcdef", parsed.slug)
            assertEquals("deadbeef001122", parsed.voucher)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun witnessReportStructuredVehicleAndConfidenceFieldsRoundtrip() {
        val rec = WitnessReportRecord(
            targetKey = "witness:test-01",
            agencyCode = "DHS_ICE",
            confidencePct = 92,
            subjectKind = "VEHICLE",
            plateNumber = "ABC1234",
            plateState = "AZ",
            partialPlate = true,
            vehicleColor = "White",
            vehicleBodyType = "SUV",
            vehicleMake = "Chevrolet",
            vehicleModel = "Tahoe",
            notes = "Observed staging near west lot"
        )
        assertEquals(92, rec.confidencePct)
        assertTrue(rec.partialPlate)
        assertEquals("White", rec.vehicleColor)
        assertEquals("SUV", rec.vehicleBodyType)
        assertEquals("Chevrolet", rec.vehicleMake)
        assertEquals("Tahoe", rec.vehicleModel)
        assertEquals("Observed staging near west lot", rec.notes)
    }

    @Test
    fun grantErrorAndUnenrolledDiagnosticsAreCompleteAndNonTechnical() {
        val pendingMsg = DeviceTrustManager.formatUnenrolledGrantMessage(
            status = "PENDING_REBIND",
            enr = DeviceTrustManager.AuthResponseDto(ok = false, status = "PENDING_REBIND"),
            hasSeatRef = true
        )
        assertTrue(pendingMsg.contains("Kokoho and Kokoho Debug"))
        assertTrue(pendingMsg.contains("Hub → Permissions"))

        val offlineMsg = DeviceTrustManager.formatUnenrolledGrantMessage(
            status = "UNENROLLED",
            enr = null,
            hasSeatRef = true
        )
        assertTrue(offlineMsg.contains("VPN"))

        val noSeatMsg = DeviceTrustManager.formatUnenrolledGrantMessage(
            status = "UNENROLLED",
            enr = null,
            hasSeatRef = false
        )
        assertTrue(noSeatMsg.contains("Field Guide"))

        val revokedMsg = DeviceTrustManager.formatUnenrolledGrantMessage(
            status = "REVOKED",
            enr = DeviceTrustManager.AuthResponseDto(ok = false, status = "REVOKED"),
            hasSeatRef = true
        )
        assertEquals("This invitation seat has been revoked.", revokedMsg)

        val expiredMsg = DeviceTrustManager.formatGrantErrorMessage(400, "QR challenge expired or not found")
        assertEquals(
            "That authorization prompt expired. Switch back to your browser — it refreshes automatically — and tap or scan again.",
            expiredMsg
        )

        val jsonStripped = DeviceTrustManager.formatGrantErrorMessage(403, """{"message":"Custom seat error"}""")
        assertEquals("Custom seat error", jsonStripped)
        assertTrue(!jsonStripped.contains("{") && !jsonStripped.contains("}"))
    }

    @Test
    fun allV1312DtosHaveExplicitSerializedNameOnEverySingleField() {
        val dtoClasses = listOf(
            DeviceTrustManager.SeatIdentity::class.java to 3,
            DeviceTrustManager.AuthResponseDto::class.java to 7,
            DeviceTrustManager.SessionKeyRequestDto::class.java to 1,
            DeviceTrustManager.SessionKeyResponseDto::class.java to 4,
            DeviceTrustManager.AeadEnvelopeDto::class.java to 3,
            PriorityVault.PlatesSyncResponseDto::class.java to 3,
            PriorityVault.DirectoryTierSyncDto::class.java to 5,
            PriorityVault.SyncedLocationDto::class.java to 9,
            PriorityVault.SyncedPersonDto::class.java to 15,
            PriorityVault.SyncedEdgeDto::class.java to 2,
            PriorityVault.VaultMetaDto::class.java to 3,
            KokohoHubClient.SyncedPlateRecord::class.java to 17
        )

        for ((cls, expectedCount) in dtoClasses) {
            val fields = cls.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            assertEquals("Unexpected field count on ${cls.simpleName}", expectedCount, fields.size)
            for (f in fields) {
                assertNotNull(
                    "${cls.simpleName}.${f.name} MUST carry explicit @SerializedName",
                    f.getAnnotation(SerializedName::class.java)
                )
            }
        }

        // Confirm AuthResponseDto no longer carries dir_key_hex!
        val authFieldNames = DeviceTrustManager.AuthResponseDto::class.java.declaredFields.map { it.name }
        assertFalse("dirKeyHex must be eliminated in v1.3.12", "dirKeyHex" in authFieldNames)

        val gson = Gson()
        val parsedAuth = gson.fromJson(
            """{"ok":true,"status":"ENROLLED","seat_id":"seat-009","slug":"inv-abc","role":"OWNER","label":"Alpha","message":"ok"}""",
            DeviceTrustManager.AuthResponseDto::class.java
        )
        assertTrue(parsedAuth.ok)
        assertEquals("ENROLLED", parsedAuth.status)
        assertEquals("seat-009", parsedAuth.seatId)
        assertEquals("inv-abc", parsedAuth.slug)
        assertEquals("OWNER", parsedAuth.role)
        assertEquals("Alpha", parsedAuth.label)
        assertEquals("ok", parsedAuth.message)
    }

    @Test
    fun ecdhHkdfAes256GcmEnvelopeRoundTripAndTamperRejection() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val clientKp = kpg.generateKeyPair()
        val serverKp = kpg.generateKeyPair()

        val clientShared = DeviceTrustManager.computeEcdhSharedSecret(clientKp.private, serverKp.public.encoded)
        val serverShared = DeviceTrustManager.computeEcdhSharedSecret(serverKp.private, clientKp.public.encoded)
        val pureClientShared = DeviceTrustManager.PureP256Ecdh.sharedSecret32(
            clientKp.private as ECPrivateKey,
            serverKp.public as ECPublicKey
        )
        val pureServerShared = DeviceTrustManager.PureP256Ecdh.sharedSecret32(
            serverKp.private as ECPrivateKey,
            clientKp.public as ECPublicKey
        )

        assertNotNull(clientShared)
        assertNotNull(serverShared)
        assertNotNull(pureClientShared)
        assertNotNull(pureServerShared)
        assertEquals(32, clientShared!!.size)
        assertArrayEquals("Client and server ECDH shared secrets must match", clientShared, serverShared)
        assertArrayEquals("Pure P-256 ECDH must match JCE byte-for-byte", clientShared, pureClientShared)
        assertArrayEquals("Pure P-256 ECDH symmetry must hold", pureClientShared, pureServerShared)

        val kid = "a1b2c3d4e5f60718293a4b5c"
        val clientKey = DeviceTrustManager.deriveSessionAeadKey(clientShared, kid)
        val serverKey = DeviceTrustManager.deriveSessionAeadKey(serverShared!!, kid)
        assertEquals(32, clientKey.size)
        assertArrayEquals(clientKey, serverKey)

        // Verify exact RFC 5869 HKDF-SHA256 derivation formula:
        val expectedPrk = DeviceTrustManager.hmacSha256(kid.toByteArray(Charsets.UTF_8), clientShared)
        val expectedKey = DeviceTrustManager.hmacSha256(
            expectedPrk,
            "kokoho-app-aead-v1".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01)
        )
        assertArrayEquals(expectedKey, clientKey)

        val gson = Gson()
        val payload = """{"mode":"SNAPSHOT","server_time_ms":1795000000123,"plates":[{"plate_id":"pl-1","plate_number":"XYZ987"}]}"""
        val envDto = DeviceTrustManager.sealEnvelope(kid, clientKey, payload)
        assertNotNull(envDto)
        assertEquals(kid, envDto!!.kid)

        val envJson = gson.toJson(envDto)
        val opened = DeviceTrustManager.openEnvelope(kid, serverKey, envJson)
        assertEquals(payload, opened)

        // PureAes256Gcm direct cross-check with AAD = kid
        val rawIv = Base64.decode(envDto.iv, Base64.DEFAULT)
        val rawCt = Base64.decode(envDto.ct, Base64.DEFAULT)
        val purePlain = DeviceTrustManager.PureAes256Gcm.decrypt(
            serverKey,
            rawIv,
            rawCt,
            kid.toByteArray(Charsets.UTF_8)
        )
        assertNotNull("PureAes256Gcm must authenticate AAD = kid", purePlain)
        assertEquals(payload, String(purePlain!!, Charsets.UTF_8))

        // Tamper AAD (different kid) -> GCM tag MUST reject!
        val tamperedKidEnv = gson.toJson(envDto.copy(kid = "ffffffffffffffffffffffff"))
        assertNull("Mismatched AAD kid must fail AEAD authentication", DeviceTrustManager.openEnvelope(null, serverKey, tamperedKidEnv))

        // Tamper ciphertext byte -> GCM tag MUST reject!
        rawCt[0] = (rawCt[0].toInt() xor 0x01).toByte()
        val tamperedCtEnv = gson.toJson(envDto.copy(ct = Base64.encodeToString(rawCt, Base64.NO_WRAP)))
        assertNull("Bit-flipped ciphertext must fail AEAD authentication", DeviceTrustManager.openEnvelope(kid, serverKey, tamperedCtEnv))
    }

    @Test
    fun sessionKey419RenegotiationPriorityVaultSyncScreenOffZeroingAndRevocationWipe() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val gson = Gson()

        val handshakeCount = AtomicInteger(0)
        val forceExpireNextRequest = AtomicBoolean(false)
        val seatRevokedOnHub = AtomicBoolean(false)
        val p1Calls = CopyOnWriteArrayList<String>()
        val p2IfNoneMatchSeen = CopyOnWriteArrayList<String>()
        val p3IfNoneMatchSeen = CopyOnWriteArrayList<String>()

        var activeKid = ""
        var activeAeadKey = ByteArray(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        activeServer = server

        fun respondJson(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json", extraHeaders: Map<String, String> = emptyMap()) {
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

        fun checkSeatAndEnvelope(ex: HttpExchange): Boolean {
            val seatId = ex.requestHeaders.getFirst("x-kokoho-seat-id").orEmpty()
            val ts = ex.requestHeaders.getFirst("x-kokoho-ts").orEmpty()
            val sig = ex.requestHeaders.getFirst("x-kokoho-sig").orEmpty()
            assertTrue("Signed seat headers must be attached", seatId.isNotEmpty() && ts.isNotEmpty() && sig.isNotEmpty())

            if (seatRevokedOnHub.get()) {
                respondJson(
                    ex,
                    403,
                    """{"seat_status":"REVOKED"}""",
                    extraHeaders = mapOf("x-kokoho-seat-status" to "REVOKED")
                )
                return false
            }
            if (forceExpireNextRequest.compareAndSet(true, false)) {
                respondJson(ex, 419, """{"error":"SESSION_KEY_EXPIRED"}""")
                return false
            }
            val reqKid = ex.requestHeaders.getFirst("x-kokoho-env-kid").orEmpty()
            if (reqKid.isEmpty() || reqKid != activeKid) {
                respondJson(ex, 419, """{"error":"SESSION_KEY_EXPIRED"}""")
                return false
            }
            return true
        }

        server.createContext("/v1/auth/session-key") { ex ->
            if (seatRevokedOnHub.get()) {
                respondJson(
                    ex,
                    403,
                    """{"seat_status":"REVOKED"}""",
                    extraHeaders = mapOf("x-kokoho-seat-status" to "REVOKED")
                )
                return@createContext
            }
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

        server.createContext("/v1/sync/p1-plates") { ex ->
            if (!checkSeatAndEnvelope(ex)) return@createContext
            val q = ex.requestURI.rawQuery.orEmpty()
            p1Calls += q
            val sinceMs = q.substringAfter("since_ms=", "0").substringBefore('&').toLongOrNull() ?: 0L
            val payload = if (sinceMs <= 0L) {
                PriorityVault.PlatesSyncResponseDto(
                    mode = "SNAPSHOT",
                    serverTimeMs = 1_000L,
                    plates = listOf(
                        KokohoHubClient.SyncedPlateRecord(
                            plateId = "pl-alpha",
                            plateNumber = "TEST101",
                            stateCode = "AZ",
                            agencyCode = "DHS_ICE",
                            suspectedAgency = "ICE",
                            color = "Silver",
                            make = "Ford",
                            model = "Explorer",
                            updatedAtMs = 900L
                        ),
                        KokohoHubClient.SyncedPlateRecord(
                            plateId = "pl-beta",
                            plateNumber = "TEST202",
                            stateCode = "AZ",
                            agencyCode = "DHS_CBP",
                            suspectedAgency = "CBP",
                            color = "White",
                            make = "Tahoe",
                            updatedAtMs = 950L
                        )
                    )
                )
            } else {
                PriorityVault.PlatesSyncResponseDto(
                    mode = "DELTA",
                    serverTimeMs = 2_000L,
                    plates = listOf(
                        KokohoHubClient.SyncedPlateRecord(
                            plateId = "pl-alpha",
                            plateNumber = "TEST101",
                            stateCode = "AZ",
                            agencyCode = "DHS_ICE",
                            suspectedAgency = "ICE",
                            color = "Charcoal",
                            make = "Ford",
                            model = "Explorer",
                            detected = true,
                            sightingCount = 4,
                            updatedAtMs = 1_800L
                        ),
                        KokohoHubClient.SyncedPlateRecord(
                            plateId = "pl-gamma",
                            plateNumber = "TEST303",
                            stateCode = "AZ",
                            agencyCode = "DHS_HSI",
                            suspectedAgency = "HSI",
                            color = "Black",
                            make = "Charger",
                            updatedAtMs = 1_900L
                        )
                    )
                )
            }
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, gson.toJson(payload))!!
            respondJson(ex, 200, gson.toJson(env), contentType = DeviceTrustManager.ENV_CONTENT_TYPE)
        }

        server.createContext("/v1/sync/tier/p2_sector_dir") { ex ->
            if (!checkSeatAndEnvelope(ex)) return@createContext
            val inm = ex.requestHeaders.getFirst("If-None-Match").orEmpty()
            p2IfNoneMatchSeen += inm
            if (inm.trim('"') == "etag-p2-v1") {
                respondJson(ex, 304, "", extraHeaders = mapOf("ETag" to "\"etag-p2-v1\""))
                return@createContext
            }
            val dto = PriorityVault.DirectoryTierSyncDto(
                tier = "p2_sector_dir",
                etag = "etag-p2-v1",
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
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, gson.toJson(dto))!!
            respondJson(
                ex,
                200,
                gson.toJson(env),
                contentType = DeviceTrustManager.ENV_CONTENT_TYPE,
                extraHeaders = mapOf("ETag" to "\"etag-p2-v1\"")
            )
        }

        server.createContext("/v1/sync/tier/p3_ext_dir") { ex ->
            if (!checkSeatAndEnvelope(ex)) return@createContext
            val inm = ex.requestHeaders.getFirst("If-None-Match").orEmpty()
            p3IfNoneMatchSeen += inm
            if (inm.trim('"') == "etag-p3-v1") {
                respondJson(ex, 304, "", extraHeaders = mapOf("ETag" to "\"etag-p3-v1\""))
                return@createContext
            }
            val dto = PriorityVault.DirectoryTierSyncDto(
                tier = "p3_ext_dir",
                etag = "etag-p3-v1",
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
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, gson.toJson(dto))!!
            respondJson(
                ex,
                200,
                gson.toJson(env),
                contentType = DeviceTrustManager.ENV_CONTENT_TYPE,
                extraHeaders = mapOf("ETag" to "\"etag-p3-v1\"")
            )
        }

        server.createContext("/v1/checkin") { ex ->
            if (!checkSeatAndEnvelope(ex)) return@createContext
            val reqEnv = ex.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val openedReq = DeviceTrustManager.openEnvelope(activeKid, activeAeadKey, reqEnv)
            assertNotNull("POST /v1/checkin request body must be AEAD-enveloped", openedReq)

            val snapJson = """{"seq":42,"server_time_ms":1795000000000,"active_objects":[{"uuid":"threat-live-1","track_uuid":"threat-live-1","updated_at_ms":${System.currentTimeMillis()}}]}"""
            val env = DeviceTrustManager.sealEnvelope(activeKid, activeAeadKey, snapJson)!!
            respondJson(
                ex,
                200,
                gson.toJson(env),
                contentType = DeviceTrustManager.ENV_CONTENT_TYPE,
                extraHeaders = mapOf("x-kokoho-seat-status" to "ENROLLED")
            )
        }

        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        KokohoHubClient.setHubUrl(ctx, baseUrl)

        // 0. Unenrolled baseline: Search tab must be hidden!
        val act = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val binding = ActivityMainBinding.inflate(act.layoutInflater)
        act.setContentView(binding.root)
        val views = KokohoViews(act, binding, onSwitchPanes = {}, onFilterChanged = {})
        assertEquals(listOf("Monitor", "Map"), views.visibleTabs.map { it.title })
        assertNull(views.tabButton(KokohoViews.Tab.SEARCH))

        // 1. Enroll seat -> Search tab dynamically appears!
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-unit-01", slug = "inv-unit-test")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Monitor", "Map", "Search"), views.visibleTabs.map { it.title })
        assertNotNull(views.tabButton(KokohoViews.Tab.SEARCH))
        views.selectTab(KokohoViews.Tab.SEARCH)
        assertEquals(KokohoViews.Tab.SEARCH, views.currentTab)

        // 2. Device locked simulation: sync defers cleanly without writing partial files!
        PriorityVault.simulateDeviceLockedForTest = true
        assertFalse(PriorityVault.syncAllBlocking(ctx))
        assertTrue(PriorityVault.syncDeferredWhileLocked)
        PriorityVault.simulateDeviceLockedForTest = false

        // 3. Initial Priority-Ordered Sync (SNAPSHOT + P2 200 + P3 200)
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertFalse(PriorityVault.syncDeferredWhileLocked)
        assertEquals(1, handshakeCount.get())
        assertEquals(listOf("since_ms=0"), p1Calls.toList())
        assertEquals(listOf(""), p2IfNoneMatchSeen.toList())
        assertEquals(listOf(""), p3IfNoneMatchSeen.toList())

        // Verify zero directory.db exists on disk, while sealed .seal files exist and do NOT contain plaintext!
        assertFalse("Zero directory.db file may ever be written to disk", File(ctx.noBackupFilesDir, "directory.db").exists())
        val p2SealBytes = File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).readBytes()
        assertFalse("Sealed tier blob must not contain plaintext strings", String(p2SealBytes, Charsets.ISO_8859_1).contains("Dana Supervisor"))

        // Verify hydrated plates, locations, stationed personnel, and edges/managed_by
        val plates1 = KokohoHubClient.syncedPlates(ctx)
        assertEquals(2, plates1.size)
        assertEquals("Silver", plates1.first { it.plateId == "pl-alpha" }.color)

        val annexLoc = KokohoDirectory.locationById(ctx, "loc:sector-annex-1")
        assertNotNull("Synced p2_sector_dir location must hydrate into KokohoDirectory", annexLoc)
        assertEquals("Sector Annex Test Facility", annexLoc!!.title)
        assertEquals(2, KokohoDirectory.stationedPersonnelCount(ctx, annexLoc.id))
        assertEquals("Dana Supervisor", KokohoDirectory.managedBy(ctx, "p-analyst-2")?.fullName)
        assertEquals("Dana Supervisor", KokohoDirectory.managedBy(ctx, "p-regional-3")?.fullName)
        assertEquals(
            listOf("Alex Analyst", "Morgan Regional"),
            KokohoDirectory.manages(ctx, "p-supervisor-1").map { it.fullName }
        )

        // 4. Force 419 SESSION_KEY_EXPIRED + Second Sync -> must renegotiate session key ONCE, merge P1 DELTA, and get 304 on P2 & P3!
        forceExpireNextRequest.set(true)
        assertTrue(PriorityVault.syncAllBlocking(ctx))
        assertEquals("419 must trigger exactly one session-key renegotiation", 2, handshakeCount.get())
        assertEquals(listOf("since_ms=0", "since_ms=1000"), p1Calls.toList())
        assertEquals("\"etag-p2-v1\"", p2IfNoneMatchSeen.last())
        assertEquals("\"etag-p3-v1\"", p3IfNoneMatchSeen.last())

        val platesMerged = KokohoHubClient.syncedPlates(ctx)
        assertEquals("DELTA must update pl-alpha and append pl-gamma (total 3)", 3, platesMerged.size)
        val updatedAlpha = platesMerged.first { it.plateId == "pl-alpha" }
        assertEquals("Charcoal", updatedAlpha.color)
        assertTrue(updatedAlpha.detected)
        assertEquals(2_000L, PriorityVault.readVaultMeta(ctx).p1SinceMs)

        // 5. ACTION_SCREEN_OFF Broadcast -> must zero all decrypted RAM caches, then transparently re-unseal on next read!
        assertFalse(PriorityVault.isInMemoryCacheZeroed())
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("ACTION_SCREEN_OFF must zero in-RAM PriorityVault caches", PriorityVault.isInMemoryCacheZeroed())
        assertFalse("KokohoDirectory in-RAM hydrated state must clear on screen off", KokohoDirectory.isDirectoryAvailable)

        // Re-access while unlocked -> transparently unseals in <25ms without network!
        val t0 = System.nanoTime()
        val rehydratedPlates = KokohoHubClient.syncedPlates(ctx)
        val rehydratedPerson = KokohoDirectory.personById(ctx, "p-supervisor-1")
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        assertEquals(3, rehydratedPlates.size)
        assertEquals("Dana Supervisor", rehydratedPerson?.fullName)
        assertTrue("Transparent re-unseal took ${elapsedMs}ms", elapsedMs < 250.0)

        // 6. Enveloped POST /v1/checkin succeeds while enrolled, then seat is REVOKED (403 + x-kokoho-seat-status: REVOKED)!
        val checkinOk = DeviceTrustManager.executeEnvelopedPost(ctx, "/v1/checkin?since=0", "{}")
        assertNotNull(checkinOk)
        assertEquals(200, checkinOk!!.code)
        KokohoHubClient.ingestThreatObjectJson("""{"uuid":"threat-live-1","updated_at_ms":${System.currentTimeMillis()}}""")
        assertEquals(1, KokohoHubClient.activeObjects().size)

        // Seed legacy artifacts to verify revocation deletes them too
        File(ctx.noBackupFilesDir, "dir_key.wrap").writeBytes(byteArrayOf(1, 2, 3))
        File(ctx.noBackupFilesDir, "directory.db").writeBytes(byteArrayOf(4, 5, 6))
        File(ctx.noBackupFilesDir, "directory.db.vc").writeText("1411")
        ctx.getSharedPreferences("kokoho_hub_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("kokoho_synced_plates_json", "[{\"plate_id\":\"legacy\"}]")
            .putString("synced_plates_json", "[{\"plate_id\":\"legacy\"}]")
            .commit()

        seatRevokedOnHub.set(true)
        val revokedResp = DeviceTrustManager.executeEnvelopedPost(ctx, "/v1/checkin?since=42", "{}")
        assertNull("Revoked seat checkin must return null after handling 403 REVOKED", revokedResp)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Verify ALL 5 atomic wipe guarantees!
        assertFalse("Device must no longer be enrolled", DeviceTrustManager.isEnrolled(ctx))
        assertEquals("REVOKED", DeviceTrustManager.enrollmentStatus(ctx))
        assertFalse("Session key must be wiped", DeviceTrustManager.hasActiveSessionKey())
        assertFalse("Vault directory must be wiped", File(PriorityVault.vaultDir(ctx), PriorityVault.P1_PLATES_FILE).exists())
        assertFalse("Vault p2 seal must be wiped", File(PriorityVault.vaultDir(ctx), PriorityVault.P2_SECTOR_DIR_FILE).exists())
        assertFalse("Vault p3 seal must be wiped", File(PriorityVault.vaultDir(ctx), PriorityVault.P3_EXT_DIR_FILE).exists())
        assertFalse("Vault meta must be wiped", File(PriorityVault.vaultDir(ctx), PriorityVault.VAULT_META_FILE).exists())
        assertFalse("Legacy dir_key.wrap must be wiped", File(ctx.noBackupFilesDir, "dir_key.wrap").exists())
        assertFalse("Legacy directory.db must be wiped", File(ctx.noBackupFilesDir, "directory.db").exists())
        assertFalse("Legacy directory.db.vc must be wiped", File(ctx.noBackupFilesDir, "directory.db.vc").exists())
        assertNull(ctx.getSharedPreferences("kokoho_hub_prefs", Context.MODE_PRIVATE).getString("kokoho_synced_plates_json", null))
        assertNull(ctx.getSharedPreferences("kokoho_hub_prefs", Context.MODE_PRIVATE).getString("synced_plates_json", null))
        assertTrue("Active Hub objects must be cleared on revocation", KokohoHubClient.activeObjects().isEmpty())
        assertTrue("Synced plates must be empty after revocation", KokohoHubClient.syncedPlates(ctx).isEmpty())
        assertNull("Synced facility must be purged from KokohoDirectory", KokohoDirectory.locationById(ctx, "loc:sector-annex-1"))
        assertNull("Synced person must be purged from KokohoDirectory", KokohoDirectory.personById(ctx, "p-supervisor-1"))
        assertTrue("Public baseline locations must remain loaded", KokohoDirectory.allLocations(ctx).isNotEmpty())

        // Search tab must collapse cleanly back to Monitor!
        assertEquals(listOf("Monitor", "Map"), views.visibleTabs.map { it.title })
        assertNull(views.tabButton(KokohoViews.Tab.SEARCH))
        assertNull(views.searchView)
        assertEquals(KokohoViews.Tab.MONITOR, views.currentTab)

        // seat_identity.json MUST remain intact for future Restore in Permissions!
        val preservedSeat = DeviceTrustManager.loadOrExtractSeatIdentity(ctx)
        assertNotNull("seat_identity.json must remain intact for Restore", preservedSeat)
        assertEquals("seat-unit-01", preservedSeat!!.seatId)
    }

    @Test
    fun nonEnrolledStatusResponseWhenPreviouslyEnrolledTriggersFullDisplacementWipe() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val gson = Gson()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        activeServer = server
        server.createContext("/v1/auth/status") { ex ->
            val resp = DeviceTrustManager.AuthResponseDto(
                ok = true,
                status = "PENDING_REBIND",
                seatId = "seat-disp-02",
                slug = "inv-disp"
            )
            val bytes = gson.toJson(resp).toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        KokohoHubClient.setHubUrl(ctx, "http://127.0.0.1:${server.address.port}")

        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-disp-02", slug = "inv-disp")
        assertTrue(
            PriorityVault.sealTierForTest(
                ctx,
                PriorityVault.P1_PLATES_FILE,
                """{"mode":"SNAPSHOT","server_time_ms":500,"plates":[{"plate_id":"p1","plate_number":"DISP1"}]}"""
            )
        )
        assertEquals(1, KokohoHubClient.syncedPlates(ctx).size)

        val seat = DeviceTrustManager.loadOrExtractSeatIdentity(ctx)!!
        val statusDto = DeviceTrustManager.checkDeviceStatusBlocking(ctx, seat)
        assertNotNull(statusDto)
        assertEquals("PENDING_REBIND", DeviceTrustManager.enrollmentStatus(ctx))
        assertFalse(DeviceTrustManager.isEnrolled(ctx))
        assertFalse(PriorityVault.hasAnySyncedData(ctx))
        assertTrue(KokohoHubClient.syncedPlates(ctx).isEmpty())
        assertEquals("seat-disp-02", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
    }

    @Test
    fun webAuthQrDialogExtractsDashboardInviteDeepLinkAndLegacySlug() {
        assertEquals(
            "invite-token-alpha1",
            WebAuthQrDialog.extractInviteKey("kokoho://enroll?invite=invite-token-alpha1")
        )
        assertEquals(
            "invite-token-beta2",
            WebAuthQrDialog.extractInviteKey("https://kokoho.tucsoncommunity.online/dashboard?invite=invite-token-beta2")
        )
        assertEquals(
            "invite-token-gamma3",
            WebAuthQrDialog.extractInviteKey("https://kokoho.tucsoncommunity.online/s/invite-token-gamma3")
        )
        assertNull(WebAuthQrDialog.extractInviteKey("https://kokoho.tucsoncommunity.online/dashboard"))
        assertNull(WebAuthQrDialog.extractInviteKey("kokoho://web-auth?c=1122&s=3344"))
    }

    @Test
    fun claimSeatBySlugSwitchesCleanlyFromPriorSeatWithoutLeakingOldVoucher() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val gson = Gson()
        val capturedPayloads = CopyOnWriteArrayList<Map<*, *>>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        activeServer = server
        server.createContext("/v1/auth/enroll") { ex ->
            val raw = ex.requestBody.bufferedReader(Charsets.UTF_8).readText()
            capturedPayloads += gson.fromJson(raw, Map::class.java)
            val resp = DeviceTrustManager.AuthResponseDto(
                ok = true,
                status = "ENROLLED",
                seatId = "seat-new-02",
                slug = "inv-new-02",
                role = "MEMBER",
                label = "Invite 2"
            )
            val bytes = gson.toJson(resp).toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        KokohoHubClient.setHubUrl(ctx, "http://127.0.0.1:${server.address.port}")

        // Seed existing seat-old-01 with voucher
        File(ctx.noBackupFilesDir, "seat_identity.json").writeText(
            """{"seat_id":"seat-old-01","slug":"inv-old-01","voucher":"old-voucher-secret"}""",
            Charsets.UTF_8
        )
        DeviceTrustManager.setEnrolledForTest(ctx, seatId = "seat-old-01", slug = "inv-old-01")

        val done = java.util.concurrent.CountDownLatch(1)
        var claimOk = false
        DeviceTrustManager.claimSeatBySlug(ctx, "inv-new-02") { ok, _ ->
            claimOk = ok
            done.countDown()
        }
        for (i in 0 until 40) {
            if (done.count == 0L) break
            Thread.sleep(50)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("claimSeatBySlug must complete", done.count == 0L && claimOk)
        assertEquals(1, capturedPayloads.size)
        val sent = capturedPayloads.first()
        assertEquals("", sent["seat_id"])
        assertEquals("inv-new-02", sent["slug"])
        assertEquals("", sent["voucher"])
        assertEquals("seat-new-02", DeviceTrustManager.loadOrExtractSeatIdentity(ctx)?.seatId)
        assertEquals("inv-new-02", DeviceTrustManager.currentSeatSlug(ctx))
    }
}
