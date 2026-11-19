package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware trust, ephemeral ECDH AEAD transport, web-session grant, and revocation wipe manager (`v1.3.12`).
 *
 *  - Extracts per-seat voucher metadata (`0x54434B56`) from the APK Signing Block on first launch
 *    and persists it inside `noBackupFilesDir` so subsequent universal OTA updates retain seat trust.
 *  - Generates a non-exportable `EC P-256` (`SHA256withECDSA`) keypair in `AndroidKeyStore`
 *    (StrongBox when available, TEE fallback) and binds it to the Hub via `/v1/auth/enroll`.
 *  - Negotiates ephemeral `secp256r1` ECDH session keys (`/v1/auth/session-key`), derives 32-byte
 *    `AES-256-GCM` keys via RFC 5869 `HKDF-SHA256`, and wraps/unwraps `application/vnd.kokoho.env+json`
 *    envelopes (`AAD = kid.encode("utf-8")`), auto-renegotiating once on HTTP `419`.
 *  - Atomically destroys hardware vault keys, `.seal` files, and RAM caches on seat revocation or displacement.
 */
object DeviceTrustManager {

    private const val APK_SEAT_BLOCK_ID = 0x54434B56
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val EC_KEY_ALIAS = "kokoho_seat_ec_v1"
    private const val LEGACY_AES_WRAP_ALIAS = "kokoho_dir_wrap_v1"

    private const val PREFS = "kokoho_trust_prefs"
    private const val KEY_STATUS = "trust_status"
    private const val KEY_ROLE = "trust_role"
    private const val KEY_LABEL = "trust_label"
    private const val KEY_IMPORTED_APK_STAMP = "imported_apk_stamp"

    private const val SEAT_FILE_NAME = "seat_identity.json"
    private const val LEGACY_WRAPPED_DIR_KEY_FILE = "dir_key.wrap"

    const val ENV_CONTENT_TYPE = "application/vnd.kokoho.env+json"
    private val HKDF_INFO_BYTES = "kokoho-app-aead-v1".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01)

    data class SeatIdentity(
        @SerializedName("seat_id") val seatId: String = "",
        @SerializedName("slug") val slug: String = "",
        @SerializedName("voucher") val voucher: String = ""
    )

    data class AuthResponseDto(
        @SerializedName("ok") val ok: Boolean = false,
        @SerializedName("status") val status: String = "",
        @SerializedName("seat_id") val seatId: String = "",
        @SerializedName("slug") val slug: String = "",
        @SerializedName("role") val role: String = "MEMBER",
        @SerializedName("label") val label: String = "",
        @SerializedName("message") val message: String? = null
    )

    data class SessionKeyRequestDto(
        @SerializedName("client_ephemeral_spki_b64") val clientEphemeralSpkiB64: String = ""
    )

    data class SessionKeyResponseDto(
        @SerializedName("ok") val ok: Boolean = false,
        @SerializedName("kid") val kid: String = "",
        @SerializedName("server_ephemeral_spki_b64") val serverEphemeralSpkiB64: String = "",
        @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L
    )

    data class AeadEnvelopeDto(
        @SerializedName("kid") val kid: String = "",
        @SerializedName("iv") val iv: String = "",
        @SerializedName("ct") val ct: String = ""
    )

    data class EnvelopedHttpResponse(
        val code: Int,
        val body: String?,
        val etag: String? = null
    )

    private class ActiveSessionKey(
        val kid: String,
        val aeadKey: ByteArray,
        val expiresAtMs: Long
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyLock = Any()
    private val sessionLock = Any()
    private val trustListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var currentSessionKey: ActiveSessionKey? = null

    @Volatile
    private var fallbackJvmKeyPair: KeyPair? = null

    fun addTrustStateListener(listener: () -> Unit): () -> Unit {
        trustListeners.add(listener)
        return { trustListeners.remove(listener) }
    }

    fun notifyTrustStateListeners(context: Context? = null) {
        if (trustListeners.isEmpty()) return
        val dispatch = Runnable {
            for (l in trustListeners) {
                runCatching { l() }
            }
        }
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (mainLooper != null && Looper.myLooper() != mainLooper) {
            Handler(mainLooper).post(dispatch)
        } else {
            dispatch.run()
        }
    }

    fun enrollmentStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "UNENROLLED") ?: "UNENROLLED"

    fun enrolledRole(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "MEMBER") ?: "MEMBER"

    fun enrolledLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LABEL, "") ?: ""

    fun currentSeatSlug(context: Context): String? =
        loadOrExtractSeatIdentity(context)?.slug?.takeIf { it.isNotBlank() }

    fun isEnrolled(context: Context): Boolean =
        enrollmentStatus(context).equals("ENROLLED", ignoreCase = true) &&
            loadOrExtractSeatIdentity(context)?.seatId?.isNotBlank() == true

    /**
     * Reads `{ seat_id, slug, voucher }` from `noBackupFilesDir/seat_identity.json`, or extracts it
     * from the installed APK's Signing Block (`0x54434B56`) on first launch / newly stamped APK upgrade.
     * Once a given APK stamp has been imported, `seat_identity.json` remains authoritative so claiming
     * a replacement seat via `kokoho://enroll?invite=...` is never overwritten by the older APK stamp.
     */
    fun loadOrExtractSeatIdentity(context: Context): SeatIdentity? = synchronized(keyLock) {
        val app = context.applicationContext ?: context
        val seatFile = File(app.noBackupFilesDir, SEAT_FILE_NAME)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val apkPath = runCatching { app.applicationInfo?.sourceDir }.getOrNull()
        if (!apkPath.isNullOrBlank()) {
            val apkFile = File(apkPath)
            val fromApk = runCatching { readSeatIdentityFromApk(apkFile) }.getOrNull()
            if (fromApk != null && (fromApk.seatId.isNotBlank() || fromApk.slug.isNotBlank())) {
                val stamp = "$apkPath:${apkFile.lastModified()}:${fromApk.seatId}:${fromApk.slug}"
                val lastImported = prefs.getString(KEY_IMPORTED_APK_STAMP, null)
                if (!seatFile.exists() || lastImported != stamp) {
                    runCatching {
                        app.noBackupFilesDir.mkdirs()
                        seatFile.writeText(gson.toJson(fromApk), Charsets.UTF_8)
                    }
                    prefs.edit { putString(KEY_IMPORTED_APK_STAMP, stamp) }
                    return fromApk
                }
            }
        }

        if (seatFile.exists()) {
            return runCatching {
                gson.fromJson(seatFile.readText(Charsets.UTF_8), SeatIdentity::class.java)
            }.getOrNull()?.takeIf { it.seatId.isNotBlank() || it.slug.isNotBlank() }
        }
        return null
    }

    private fun saveSeatIdentity(context: Context, identity: SeatIdentity) = synchronized(keyLock) {
        val app = context.applicationContext ?: context
        runCatching {
            app.noBackupFilesDir.mkdirs()
            File(app.noBackupFilesDir, SEAT_FILE_NAME).writeText(gson.toJson(identity), Charsets.UTF_8)
        }
    }

    /**
     * Parses the Android APK Signing Block v2/v3 (`APK Sig Block 42`) without reading the entire APK
     * into RAM, returning the custom `0x54434B56` JSON voucher if present.
     */
    fun readSeatIdentityFromApk(apkFile: File): SeatIdentity? {
        if (!apkFile.exists() || apkFile.length() < 64L) return null
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLen = raf.length()
            val tailLen = minOf(fileLen, 65_558L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(fileLen - tailLen)
            raf.readFully(tail)

            var eocdOffsetInTail = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() &&
                    tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() &&
                    tail[i + 3] == 0x06.toByte()
                ) {
                    eocdOffsetInTail = i
                    break
                }
            }
            if (eocdOffsetInTail < 0) return null
            val cdOffset = ByteBuffer.wrap(tail, eocdOffsetInTail + 16, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xFFFF_FFFFL
            if (cdOffset < 32L || cdOffset > fileLen) return null

            val footer = ByteArray(24)
            raf.seek(cdOffset - 24L)
            raf.readFully(footer)
            val magic = String(footer, 8, 16, Charsets.US_ASCII)
            if (magic != "APK Sig Block 42") return null

            val blockSizeFooter = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (blockSizeFooter < 24L || blockSizeFooter > 16L * 1024L * 1024L) return null
            val sigBlockStart = cdOffset - 8L - blockSizeFooter
            if (sigBlockStart < 0L) return null

            val headerBuf = ByteArray(8)
            raf.seek(sigBlockStart)
            raf.readFully(headerBuf)
            val blockSizeHeader = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN).long
            if (blockSizeHeader != blockSizeFooter) return null

            val pairsLen = (blockSizeFooter - 24L).toInt()
            val pairsBytes = ByteArray(pairsLen)
            raf.seek(sigBlockStart + 8L)
            raf.readFully(pairsBytes)

            val buf = ByteBuffer.wrap(pairsBytes).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.remaining() >= 12) {
                val pairLen = buf.long
                if (pairLen < 4L || pairLen > buf.remaining().toLong()) break
                val id = buf.int
                val valLen = (pairLen - 4L).toInt()
                if (id == APK_SEAT_BLOCK_ID) {
                    val valBytes = ByteArray(valLen)
                    buf.get(valBytes)
                    return gson.fromJson(String(valBytes, Charsets.UTF_8), SeatIdentity::class.java)
                } else {
                    buf.position(buf.position() + valLen)
                }
            }
        }
        return null
    }

    private fun getOrCreateSigningKeys(): Pair<PrivateKey, String>? = synchronized(keyLock) {
        val isRobolectric = Build.FINGERPRINT == null || Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        val hasAndroidKeyStore = Security.getProvider(KEYSTORE_PROVIDER) != null

        // 1. Real Android hardware: strictly use AndroidKeyStore (never fall back to an ephemeral RAM key!)
        if (!isRobolectric && hasAndroidKeyStore) {
            return runCatching {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (!ks.containsAlias(EC_KEY_ALIAS)) {
                    val generated = generateEcKeyInKeystore(useStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    if (!generated) {
                        generateEcKeyInKeystore(useStrongBox = false)
                    }
                }
                val entry = runCatching { ks.getEntry(EC_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
                if (entry != null) {
                    val spkiB64 = Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
                    return@runCatching entry.privateKey to spkiB64
                }
                val privKey = runCatching { ks.getKey(EC_KEY_ALIAS, null) as? PrivateKey }.getOrNull()
                val pubKey = runCatching { ks.getCertificate(EC_KEY_ALIAS)?.publicKey }.getOrNull()
                if (privKey != null && pubKey != null) {
                    val spkiB64 = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
                    return@runCatching privKey to spkiB64
                }
                null
            }.getOrNull()
        }

        if (hasAndroidKeyStore && !isRobolectric) {
            return null
        }

        // 2. Standard JCE fallback ONLY when AndroidKeyStore provider does not exist (JVM/Robolectric unit tests)
        val kp = fallbackJvmKeyPair ?: runCatching {
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        }.getOrNull()?.also { fallbackJvmKeyPair = it } ?: return null

        val spkiB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        return kp.private to spkiB64
    }

    private fun generateEcKeyInKeystore(useStrongBox: Boolean): Boolean = runCatching {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        val specBuilder = KeyGenParameterSpec.Builder(
            EC_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }
        kpg.initialize(specBuilder.build())
        kpg.generateKeyPair()
        true
    }.getOrDefault(false)

    private fun signMessageBase64(privateKey: PrivateKey, message: String): String? = runCatching {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(message.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Attaches signed hardware-key seat headers (`x-kokoho-seat-id`, `x-kokoho-ts`, `x-kokoho-sig`)
     * to outgoing Hub HTTP connections when enrolled or holding a seat identity.
     */
    fun attachSignedHeaders(context: Context, conn: HttpURLConnection) {
        val seat = loadOrExtractSeatIdentity(context) ?: return
        if (seat.seatId.isBlank()) return
        val (privKey, _) = getOrCreateSigningKeys() ?: return
        val ts = System.currentTimeMillis()
        val sig = signMessageBase64(privKey, "req|${seat.seatId}|$ts") ?: return
        conn.setRequestProperty("x-kokoho-seat-id", seat.seatId)
        conn.setRequestProperty("x-kokoho-ts", ts.toString())
        conn.setRequestProperty("x-kokoho-sig", sig)
    }

    // ---- Ephemeral ECDH + HKDF-SHA256 + AES-256-GCM Envelope Transport -------------------------

    fun clearSessionKey() = synchronized(sessionLock) {
        currentSessionKey?.aeadKey?.fill(0)
        currentSessionKey = null
    }

    fun hasActiveSessionKey(): Boolean = synchronized(sessionLock) {
        val sk = currentSessionKey ?: return false
        System.currentTimeMillis() < sk.expiresAtMs
    }

    /**
     * Computes the 32-byte `secp256r1` ECDH shared secret X-coordinate using JCE `KeyAgreement`,
     * falling back to pure-Kotlin NIST P-256 scalar multiplication under Robolectric sandboxed JVMs.
     */
    fun computeEcdhSharedSecret(clientPrivateKey: PrivateKey, serverSpkiBytes: ByteArray): ByteArray? {
        val serverPub = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(serverSpkiBytes))
        }.getOrNull() ?: return null

        val jceSecret = runCatching {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(clientPrivateKey)
            ka.doPhase(serverPub, true)
            normalizeFixed32(ka.generateSecret())
        }.getOrNull()
        if (jceSecret != null && jceSecret.size == 32) {
            return jceSecret
        }

        if (clientPrivateKey is ECPrivateKey && serverPub is ECPublicKey) {
            return PureP256Ecdh.sharedSecret32(clientPrivateKey, serverPub)
        }
        return null
    }

    /**
     * RFC 5869 `HKDF-SHA256` exact derivation per `v1312_wire_spec.md`:
     *  - `prk = HMAC-SHA256(key = kid.encode("utf-8"), msg = shared_secret)`
     *  - `aead_key = HMAC-SHA256(key = prk, msg = b"kokoho-app-aead-v1\x01")` (32 bytes)
     */
    fun deriveSessionAeadKey(sharedSecret32: ByteArray, kid: String): ByteArray {
        val prk = hmacSha256(kid.toByteArray(Charsets.UTF_8), sharedSecret32)
        val aeadKey = hmacSha256(prk, HKDF_INFO_BYTES)
        prk.fill(0)
        return aeadKey
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
            if (key.isNotEmpty()) {
                return mac.doFinal(message)
            }
        } catch (_: Throwable) {
            // Fall through to RFC 2104 SHA-256 implementation under sandboxed Robolectric JceSecurity
        }
        return pureHmacSha256(key, message)
    }

    private fun pureHmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val normalizedKey = if (key.size > 64) md.digest(key) else key
        val ipad = ByteArray(64)
        val opad = ByteArray(64)
        for (i in 0 until 64) {
            val kb = if (i < normalizedKey.size) normalizedKey[i].toInt() and 0xFF else 0
            ipad[i] = (kb xor 0x36).toByte()
            opad[i] = (kb xor 0x5c).toByte()
        }
        md.reset()
        md.update(ipad)
        md.update(message)
        val inner = md.digest()
        md.reset()
        md.update(opad)
        md.update(inner)
        return md.digest()
    }

    fun sealEnvelope(kid: String, aeadKey: ByteArray, plaintextUtf8: String): AeadEnvelopeDto? {
        if (aeadKey.size != 32 || kid.isEmpty()) return null
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aad = kid.toByteArray(Charsets.UTF_8)
        val ctAndTag = aes256GcmEncrypt(aeadKey, iv, plaintextUtf8.toByteArray(Charsets.UTF_8), aad)
            ?: return null
        return AeadEnvelopeDto(
            kid = kid,
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ct = Base64.encodeToString(ctAndTag, Base64.NO_WRAP)
        )
    }

    fun openEnvelope(expectedKid: String?, aeadKey: ByteArray, envelopeJson: String): String? {
        if (aeadKey.size != 32 || envelopeJson.isBlank()) return null
        val dto = runCatching { gson.fromJson(envelopeJson, AeadEnvelopeDto::class.java) }.getOrNull()
            ?: return null
        if (dto.kid.isBlank() || dto.iv.isBlank() || dto.ct.isBlank()) return null
        if (!expectedKid.isNullOrBlank() && dto.kid != expectedKid) return null
        val iv = runCatching { Base64.decode(dto.iv, Base64.DEFAULT) }.getOrNull() ?: return null
        val ctAndTag = runCatching { Base64.decode(dto.ct, Base64.DEFAULT) }.getOrNull() ?: return null
        if (iv.size != 12 || ctAndTag.size < 16) return null
        val aad = dto.kid.toByteArray(Charsets.UTF_8)
        val plain = aes256GcmDecrypt(aeadKey, iv, ctAndTag, aad) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    private fun getOrNegotiateSessionKeyBlocking(context: Context, forceRefresh: Boolean = false): ActiveSessionKey? =
        synchronized(sessionLock) {
            val app = context.applicationContext ?: context
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                currentSessionKey?.takeIf { now < it.expiresAtMs - 15_000L }?.let { return it }
            }
            currentSessionKey?.aeadKey?.fill(0)
            currentSessionKey = null

            val seat = loadOrExtractSeatIdentity(app) ?: return null
            if (seat.seatId.isBlank()) return null
            if (VpnGeoGate.checkHubEgressAllowedBlocking(app, forceSeatedCheck = true) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
                return null
            }

            val ephemKp = runCatching {
                KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
            }.getOrNull() ?: return null

            val clientSpkiB64 = Base64.encodeToString(ephemKp.public.encoded, Base64.NO_WRAP)
            val reqJson = gson.toJson(SessionKeyRequestDto(clientEphemeralSpkiB64 = clientSpkiB64))
            val base = KokohoHubClient.hubUrl(app)

            return runCatching {
                val conn = (URL("$base/v1/auth/session-key").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    attachSignedHeaders(app, this)
                }
                try {
                    conn.outputStream.use { it.write(reqJson.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val seatHdr = conn.getHeaderField("x-kokoho-seat-status")
                    if (code !in 200..299) {
                        val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        inspectResponseForRevocation(app, code, seatHdr, err)
                        return@runCatching null
                    }
                    val respText = conn.inputStream.bufferedReader().use { it.readText() }
                    val dto = gson.fromJson(respText, SessionKeyResponseDto::class.java)
                    if (dto == null || !dto.ok || dto.kid.isBlank() || dto.serverEphemeralSpkiB64.isBlank()) {
                        return@runCatching null
                    }
                    val serverSpkiBytes = Base64.decode(dto.serverEphemeralSpkiB64, Base64.DEFAULT)
                    val sharedSecret = computeEcdhSharedSecret(ephemKp.private, serverSpkiBytes)
                        ?: return@runCatching null
                    val aeadKey = deriveSessionAeadKey(sharedSecret, dto.kid)
                    sharedSecret.fill(0)
                    val expiresAt = dto.expiresAtMs.takeIf { it > now } ?: (now + 15 * 60_000L)
                    ActiveSessionKey(dto.kid, aeadKey, expiresAt).also { currentSessionKey = it }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    fun executeEnvelopedGet(
        context: Context,
        pathAndQuery: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): EnvelopedHttpResponse? = executeEnvelopedInternal(
        context = context,
        method = "GET",
        pathAndQuery = pathAndQuery,
        plainJsonBody = null,
        extraHeaders = extraHeaders
    )

    fun executeEnvelopedPost(
        context: Context,
        pathAndQuery: String,
        plainJsonBody: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): EnvelopedHttpResponse? = executeEnvelopedInternal(
        context = context,
        method = "POST",
        pathAndQuery = pathAndQuery,
        plainJsonBody = plainJsonBody,
        extraHeaders = extraHeaders
    )

    private fun executeEnvelopedInternal(
        context: Context,
        method: String,
        pathAndQuery: String,
        plainJsonBody: String?,
        extraHeaders: Map<String, String>
    ): EnvelopedHttpResponse? {
        val app = context.applicationContext ?: context
        if (VpnGeoGate.checkHubEgressAllowedBlocking(app, forceSeatedCheck = true) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
            return null
        }
        val cleanPath = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        val base = KokohoHubClient.hubUrl(app)

        for (attempt in 0..1) {
            val sk = getOrNegotiateSessionKeyBlocking(app, forceRefresh = attempt > 0) ?: return null
            val wireBodyBytes: ByteArray? = if (method == "POST") {
                val env = sealEnvelope(sk.kid, sk.aeadKey, plainJsonBody ?: "{}") ?: return null
                gson.toJson(env).toByteArray(Charsets.UTF_8)
            } else {
                null
            }

            val result = runCatching {
                val conn = (URL("$base$cleanPath").openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    setRequestProperty("Accept", "$ENV_CONTENT_TYPE, application/json")
                    setRequestProperty("x-kokoho-env-kid", sk.kid)
                    attachSignedHeaders(app, this)
                    for ((hk, hv) in extraHeaders) {
                        setRequestProperty(hk, hv)
                    }
                    if (wireBodyBytes != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", ENV_CONTENT_TYPE)
                    }
                }
                try {
                    if (wireBodyBytes != null) {
                        conn.outputStream.use { it.write(wireBodyBytes) }
                    }
                    val code = conn.responseCode
                    val seatHdr = conn.getHeaderField("x-kokoho-seat-status")
                    val etagHdr = conn.getHeaderField("ETag")

                    if (code == 419) {
                        clearSessionKey()
                        return@runCatching EnvelopedHttpResponse(419, null, etagHdr)
                    }
                    if (code == 304) {
                        return@runCatching EnvelopedHttpResponse(304, null, etagHdr)
                    }
                    if (code !in 200..299) {
                        val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        inspectResponseForRevocation(app, code, seatHdr, err)
                        return@runCatching EnvelopedHttpResponse(code, null, etagHdr)
                    }

                    val rawResp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val cType = conn.contentType.orEmpty()
                    val looksEnveloped = cType.contains("vnd.kokoho.env+json", ignoreCase = true) ||
                        (rawResp.trimStart().startsWith("{") &&
                            rawResp.contains("\"kid\"") &&
                            rawResp.contains("\"iv\"") &&
                            rawResp.contains("\"ct\""))
                    val plainBody = if (looksEnveloped) {
                        openEnvelope(sk.kid, sk.aeadKey, rawResp) ?: return@runCatching null
                    } else {
                        rawResp
                    }
                    EnvelopedHttpResponse(code, plainBody, etagHdr)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull() ?: return null

            if (result.code == 419 && attempt == 0) {
                continue
            }
            return if (result.code == 304 || result.code in 200..299) result else null
        }
        return null
    }

    // ---- Complete Revocation & Displacement Wipe -----------------------------------------------

    /**
     * Inspects Hub HTTP error responses and triggers [handleSeatRevokedOrDisplaced] whenever
     * HTTP `403` carries `x-kokoho-seat-status: REVOKED` or a `"REVOKED"` payload.
     */
    fun inspectResponseForRevocation(
        context: Context,
        httpCode: Int,
        seatStatusHeader: String?,
        errorBody: String?
    ): Boolean {
        if (httpCode != 403) return false
        val headerRevoked = seatStatusHeader?.trim()?.equals("REVOKED", ignoreCase = true) == true
        val bodyRevoked = errorBody?.contains("REVOKED", ignoreCase = true) == true
        if (headerRevoked || bodyRevoked) {
            handleSeatRevokedOrDisplaced(context, newStatus = "REVOKED")
            return true
        }
        return false
    }

    /**
     * Atomically wipes all hardware vault/wrap keys, sealed disk tiers, legacy files, and RAM caches
     * upon seat revocation or displacement, while preserving `seat_identity.json` and `kokoho_seat_ec_v1`
     * so `Restore` in `Permissions` can cleanly re-authorize the phone later.
     */
    fun handleSeatRevokedOrDisplaced(context: Context, newStatus: String = "REVOKED") {
        val app = context.applicationContext ?: context

        // 1. Destroy hardware vault key (`kokoho_vault_aes_v1`), legacy wrap key (`kokoho_dir_wrap_v1`),
        //    and all files in `noBackupFilesDir/vault/` (`p1_plates.seal`, `p2_sector_dir.seal`, `p3_ext_dir.seal`, `vault_meta.json`).
        PriorityVault.destroyVaultKeyAndFiles(app)
        synchronized(keyLock) {
            runCatching {
                if (Security.getProvider(KEYSTORE_PROVIDER) != null) {
                    val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                    if (ks.containsAlias(PriorityVault.VAULT_KEY_ALIAS)) {
                        ks.deleteEntry(PriorityVault.VAULT_KEY_ALIAS)
                    }
                    if (ks.containsAlias(LEGACY_AES_WRAP_ALIAS)) {
                        ks.deleteEntry(LEGACY_AES_WRAP_ALIAS)
                    }
                }
            }
            for (dir in listOfNotNull(app.noBackupFilesDir, app.filesDir)) {
                runCatching { File(dir, LEGACY_WRAPPED_DIR_KEY_FILE).delete() }
                runCatching { File(dir, "directory.db").delete() }
                runCatching { File(dir, "directory.db.vc").delete() }
                runCatching { File(dir, "directory.db.tmp").delete() }
            }
        }

        // 2. Clear ECDH session key and update enrollment status (leaving seat_identity.json & kokoho_seat_ec_v1 intact).
        clearSessionKey()
        val resolvedStatus = newStatus.trim().ifEmpty { "REVOKED" }.let {
            if (it.equals("ENROLLED", ignoreCase = true)) "REVOKED" else it
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_STATUS, resolvedStatus)
        }

        // 3. Clear legacy SharedPreferences synced plates & KokohoHubClient in-RAM active objects/plates.
        KokohoHubClient.clearAllOnRevocation(app)

        // 4. Clear PriorityVault & KokohoDirectory in-RAM caches and reload down to public-only baseline stations.
        PriorityVault.clearInMemoryCaches(app)
        KokohoDirectory.purgeDecryptedCache(app)
        KokohoDirectory.ensureLoaded(app)

        notifyTrustStateListeners(app)
    }

    // ---- Enrollment & Single-Session Web QR Grant ----------------------------------------------

    /**
     * Automatically performs hardware status verification (or initial enrollment) in the background
     * when a seat voucher is bundled or previously claimed, and kicks off priority vault sync.
     */
    fun ensureEnrolledAsync(context: Context, onComplete: ((AuthResponseDto?) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        PriorityVault.ensureScreenOffReceiverRegistered(app)
        VpnGeoGate.ensureNetworkCallbackRegistered(app)
        val seat = loadOrExtractSeatIdentity(app)
        if (seat == null) {
            onComplete?.invoke(null)
            return
        }
        scope.launch {
            val wasEnrolled = isEnrolled(app)
            val resp = if (seat.seatId.isNotBlank()) {
                val statusResp = checkDeviceStatusBlocking(app, seat)
                when {
                    statusResp != null && statusResp.status.equals(VpnGeoGate.STATUS_VPN_REQUIRED_TUCSON, ignoreCase = true) -> statusResp
                    statusResp != null && statusResp.status.equals("ENROLLED", ignoreCase = true) -> statusResp
                    statusResp != null && wasEnrolled -> statusResp // Displaced/revoked/agreed already wiped in applyAuthOutcome
                    statusResp != null && statusResp.status.uppercase() in setOf("REVOKED", "PENDING_REBIND", "DISPLACED") -> statusResp
                    !wasEnrolled && (seat.slug.isNotBlank() || seat.voucher.isNotBlank()) -> enrollBlocking(app, seat)
                    else -> statusResp
                }
            } else {
                enrollBlocking(app, seat)
            }
            if ((resp == null || resp.status.equals(VpnGeoGate.STATUS_VPN_REQUIRED_TUCSON, ignoreCase = true)) && isEnrolled(app)) {
                // Offline or paused on Tucson VPN gate while enrolled: load locally sealed directory
                KokohoDirectory.ensureLoaded(app)
            }
            if (onComplete != null) {
                withContext(Dispatchers.Main) { onComplete(resp) }
            }
        }
    }

    fun claimSeatBySlug(
        context: Context,
        slug: String,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        val app = context.applicationContext ?: context
        val cleanSlug = slug.trim().substringAfterLast('/').trim()
        if (cleanSlug.isEmpty()) {
            onResult(false, "Invalid invitation QR code.")
            return
        }
        scope.launch {
            val existing = loadOrExtractSeatIdentity(app)
            val sameSlug = existing != null && existing.slug.equals(cleanSlug, ignoreCase = true)
            if (!sameSlug) {
                clearSessionKey()
            }
            val tempIdentity = if (sameSlug && existing != null) {
                SeatIdentity(
                    seatId = existing.seatId,
                    slug = cleanSlug,
                    voucher = existing.voucher
                )
            } else {
                SeatIdentity(
                    seatId = "",
                    slug = cleanSlug,
                    voucher = ""
                )
            }
            val resp = enrollBlocking(app, tempIdentity)
            withContext(Dispatchers.Main) {
                when {
                    resp == null -> onResult(
                        false,
                        "Could not reach Kokoho Hub to verify device enrollment. Make sure your VPN (such as ProtonVPN) and internet connection are active."
                    )
                    resp.status.equals(VpnGeoGate.STATUS_VPN_REQUIRED_TUCSON, ignoreCase = true) -> onResult(
                        false,
                        resp.message ?: VpnGeoGate.TUCSON_VPN_ADVISORY_MESSAGE
                    )
                    resp.status.equals("ENROLLED", ignoreCase = true) -> onResult(
                        true,
                        "This phone is enrolled (${resp.role.lowercase().replaceFirstChar { it.uppercase() }})."
                    )
                    resp.status.equals("PENDING_REBIND", ignoreCase = true) -> onResult(
                        false,
                        formatUnenrolledGrantMessage("PENDING_REBIND", resp, hasSeatRef = true)
                    )
                    resp.status.equals("REVOKED", ignoreCase = true) -> onResult(
                        false,
                        "This invitation seat has been revoked."
                    )
                    else -> onResult(false, resp.message ?: "Enrollment could not be completed.")
                }
            }
        }
    }

    fun formatUnenrolledGrantMessage(
        status: String?,
        enr: AuthResponseDto?,
        hasSeatRef: Boolean
    ): String {
        val norm = status?.trim()?.uppercase().orEmpty()
        return when {
            norm == VpnGeoGate.STATUS_VPN_REQUIRED_TUCSON ->
                VpnGeoGate.TUCSON_VPN_ADVISORY_MESSAGE
            norm == "PENDING_REBIND" ->
                "This app build's hardware key is pending Owner approval on this seat (for example, if both Kokoho and Kokoho Debug are installed, or if switching phones). Approve it in Hub → Permissions, or open the originally enrolled Kokoho app."
            norm == "REVOKED" ->
                "This invitation seat has been revoked."
            enr == null && hasSeatRef ->
                "Could not reach Kokoho Hub to verify device enrollment. Make sure your VPN (such as ProtonVPN) and internet connection are active."
            else ->
                "This app is not yet enrolled on a Hub seat. Install your seat APK from the Field Guide first."
        }
    }

    fun formatGrantErrorMessage(httpCode: Int, rawError: String): String {
        val cleaned = rawError
            .trim()
            .let { raw ->
                if (raw.startsWith("{") && raw.endsWith("}")) {
                    runCatching {
                        val map = gson.fromJson(raw, Map::class.java)
                        (map["message"] ?: map["error"] ?: "").toString().trim()
                    }.getOrDefault("")
                        .ifEmpty { raw.trim('{', '}', ' ', '"', '\n', '\r') }
                } else {
                    raw.trim()
                }
            }
        val low = cleaned.lowercase()
        return when {
            httpCode == 0 ->
                "Could not reach Kokoho Hub. Make sure your VPN (such as ProtonVPN) and internet connection are active."
            low.contains("expired") || low.contains("not found") && httpCode == 400 ->
                "That authorization prompt expired. Switch back to your browser (it refreshes automatically) and tap or scan again."
            low.contains("mismatch") ->
                "That authorization prompt belongs to a different invitation link than the seat enrolled in this app."
            low.contains("clock skew") ->
                "Your phone's clock is out of sync with the server. Enable automatic date & time in Android Settings and try again."
            httpCode == 401 || low.contains("signature") ->
                "This app build's hardware key does not match the key bound to this seat. Approve Trust Both or Approve Rebind in Hub → Permissions, or open the originally enrolled Kokoho app."
            low.contains("not active") || low.contains("revoked") ->
                "This invitation seat is not currently active on the Hub."
            cleaned.isNotBlank() -> cleaned
            else ->
                "That authorization prompt expired. Switch back to your browser (it refreshes automatically) and tap or scan again."
        }
    }

    private fun postGrantWebOnce(
        app: Context,
        seatId: String,
        challengeId: String,
        secret: String,
        privateKey: PrivateKey
    ): Triple<Boolean, Int, String> {
        val ts = System.currentTimeMillis()
        val msg = "grant|$seatId|${challengeId.trim()}|${secret.trim()}|$ts"
        val sig = signMessageBase64(privateKey, msg)
            ?: return Triple(false, -1, "Hardware key signature failed on this device.")
        val payload = mapOf(
            "seat_id" to seatId,
            "challenge_id" to challengeId.trim(),
            "secret" to secret.trim(),
            "timestamp_ms" to ts,
            "signature_b64" to sig
        )
        val base = KokohoHubClient.hubUrl(app)
        return runCatching {
            val conn = (URL("$base/v1/auth/grant-web").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(gson.toJson(payload).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val seatHdr = conn.getHeaderField("x-kokoho-seat-status")
                if (code in 200..299) {
                    Triple(
                        true,
                        code,
                        "Single-session browser access is now unlocked. Switch back to your browser to use Kokoho Hub."
                    )
                } else {
                    val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
                        .getOrNull()
                        .orEmpty()
                    inspectResponseForRevocation(app, code, seatHdr, err)
                    Triple(false, code, err)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Triple(false, 0, "") }
    }

    fun grantWebSession(
        context: Context,
        challengeId: String,
        secret: String,
        slugHint: String? = null,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        val app = context.applicationContext ?: context
        scope.launch {
            if (VpnGeoGate.checkHubEgressAllowedBlocking(app, forceSeatedCheck = true) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
                withContext(Dispatchers.Main) {
                    onResult(false, VpnGeoGate.TUCSON_VPN_ADVISORY_MESSAGE)
                }
                return@launch
            }
            var seat = loadOrExtractSeatIdentity(app)
            var enr: AuthResponseDto? = null

            if (!isEnrolled(app)) {
                if (seat != null && seat.seatId.isNotBlank()) {
                    enr = checkDeviceStatusBlocking(app, seat)
                    seat = loadOrExtractSeatIdentity(app)
                }
                if (!isEnrolled(app)) {
                    val blockedStatus = enr?.status?.uppercase().orEmpty()
                    enr = if (blockedStatus in setOf("REVOKED", "PENDING_REBIND", "DISPLACED")) {
                        enr
                    } else if (!slugHint.isNullOrBlank()) {
                        enrollBlocking(
                            app,
                            SeatIdentity(
                                seatId = seat?.seatId.orEmpty(),
                                slug = slugHint.trim(),
                                voucher = seat?.voucher.orEmpty()
                            )
                        )
                    } else if (seat != null && (seat.voucher.isNotBlank() || seat.slug.isNotBlank())) {
                        enrollBlocking(app, seat)
                    } else {
                        enr
                    }
                    seat = loadOrExtractSeatIdentity(app)
                }
                if (seat == null || seat.seatId.isBlank() || !isEnrolled(app)) {
                    val hasSeatRef = (seat != null && (seat.seatId.isNotBlank() || seat.slug.isNotBlank())) ||
                        !slugHint.isNullOrBlank()
                    val status = enr?.status?.takeIf { it.isNotBlank() } ?: enrollmentStatus(app)
                    val msg = formatUnenrolledGrantMessage(status, enr, hasSeatRef)
                    withContext(Dispatchers.Main) { onResult(false, msg) }
                    return@launch
                }
            }

            val currentSeat = seat ?: run {
                val msg = formatUnenrolledGrantMessage(enrollmentStatus(app), enr, !slugHint.isNullOrBlank())
                withContext(Dispatchers.Main) { onResult(false, msg) }
                return@launch
            }

            val keys = getOrCreateSigningKeys()
            if (keys == null) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Android hardware keystore is unavailable on this device.")
                }
                return@launch
            }

            var attempt = postGrantWebOnce(app, currentSeat.seatId, challengeId, secret, keys.first)
            if (!attempt.first && (attempt.second == 403 || attempt.second == 401)) {
                var recResp = checkDeviceStatusBlocking(app, currentSeat)
                val recStatus = recResp?.status?.uppercase().orEmpty()
                if (!isEnrolled(app) &&
                    recStatus !in setOf("REVOKED", "PENDING_REBIND", "DISPLACED") &&
                    (!slugHint.isNullOrBlank() || currentSeat.voucher.isNotBlank() || currentSeat.slug.isNotBlank())
                ) {
                    recResp = enrollBlocking(
                        app,
                        SeatIdentity(
                            seatId = currentSeat.seatId,
                            slug = slugHint?.trim()?.takeIf { it.isNotEmpty() } ?: currentSeat.slug,
                            voucher = currentSeat.voucher
                        )
                    )
                }
                val refreshedSeat = loadOrExtractSeatIdentity(app)
                if (refreshedSeat != null && refreshedSeat.seatId.isNotBlank() && isEnrolled(app)) {
                    attempt = postGrantWebOnce(app, refreshedSeat.seatId, challengeId, secret, keys.first)
                } else if (recResp != null && recStatus in setOf("PENDING_REBIND", "REVOKED", "DISPLACED")) {
                    val msg = formatUnenrolledGrantMessage(
                        if (recStatus == "DISPLACED") "REVOKED" else recResp.status,
                        recResp,
                        hasSeatRef = true
                    )
                    withContext(Dispatchers.Main) { onResult(false, msg) }
                    return@launch
                }
            }

            val finalOk = attempt.first
            val finalMsg = if (finalOk) {
                attempt.third
            } else if (attempt.second == -1) {
                attempt.third
            } else {
                formatGrantErrorMessage(attempt.second, attempt.third)
            }

            withContext(Dispatchers.Main) {
                onResult(finalOk, finalMsg)
            }
        }
    }

    internal fun enrollBlocking(context: Context, seat: SeatIdentity): AuthResponseDto? {
        if (VpnGeoGate.checkHubEgressAllowedBlocking(context, forceSeatedCheck = true) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
            return AuthResponseDto(
                ok = false,
                status = VpnGeoGate.STATUS_VPN_REQUIRED_TUCSON,
                message = VpnGeoGate.TUCSON_VPN_ADVISORY_MESSAGE
            )
        }
        val (privKey, pubSpkiB64) = getOrCreateSigningKeys() ?: return null
        val idOrSlug = seat.seatId.ifBlank { seat.slug }
        if (idOrSlug.isBlank()) return null
        val ts = System.currentTimeMillis()
        val msg = "enroll|$idOrSlug|${seat.voucher}|$ts"
        val sig = signMessageBase64(privKey, msg) ?: return null
        val devInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

        val bodyMap = mapOf(
            "seat_id" to seat.seatId,
            "slug" to seat.slug,
            "voucher" to seat.voucher,
            "pubkey_spki_b64" to pubSpkiB64,
            "device_info" to devInfo,
            "timestamp_ms" to ts,
            "signature_b64" to sig
        )
        val dto = postAuthJson(context, "/v1/auth/enroll", bodyMap) ?: return null
        applyAuthOutcome(context, seat, dto, isStatusPoll = false)
        return dto
    }

    internal fun checkDeviceStatusBlocking(context: Context, seat: SeatIdentity): AuthResponseDto? {
        if (seat.seatId.isBlank()) return null
        if (VpnGeoGate.checkHubEgressAllowedBlocking(context, forceSeatedCheck = true) == VpnGeoGate.Verdict.BLOCKED_TUCSON) {
            return AuthResponseDto(
                ok = false,
                status = VpnGeoGate.STATUS_VPN_REQUIRED_TUCSON,
                message = VpnGeoGate.TUCSON_VPN_ADVISORY_MESSAGE
            )
        }
        val (privKey, pubSpkiB64) = getOrCreateSigningKeys() ?: return null
        val ts = System.currentTimeMillis()
        val msg = "status|${seat.seatId}|$ts"
        val sig = signMessageBase64(privKey, msg) ?: return null
        val bodyMap = mapOf(
            "seat_id" to seat.seatId,
            "pubkey_spki_b64" to pubSpkiB64,
            "timestamp_ms" to ts,
            "signature_b64" to sig
        )
        val dto = postAuthJson(context, "/v1/auth/status", bodyMap) ?: return null
        applyAuthOutcome(context, seat, dto, isStatusPoll = true)
        return dto
    }

    private fun postAuthJson(context: Context, path: String, payload: Map<String, Any>): AuthResponseDto? =
        runCatching {
            val base = KokohoHubClient.hubUrl(context)
            val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(gson.toJson(payload).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val seatHdr = conn.getHeaderField("x-kokoho-seat-status")
                if (code in 200..299) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    gson.fromJson(txt, AuthResponseDto::class.java)
                } else {
                    val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                    if (inspectResponseForRevocation(context, code, seatHdr, err)) {
                        AuthResponseDto(ok = false, status = "REVOKED", message = "This invitation seat has been revoked.")
                    } else {
                        runCatching { gson.fromJson(err, AuthResponseDto::class.java) }
                            .getOrNull()
                            ?.takeIf { it.status.isNotBlank() }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    private fun applyAuthOutcome(
        context: Context,
        prevSeat: SeatIdentity,
        dto: AuthResponseDto,
        isStatusPoll: Boolean
    ) {
        val app = context.applicationContext ?: context
        val wasEnrolled = enrollmentStatus(app).equals("ENROLLED", ignoreCase = true)

        if (dto.seatId.isNotBlank() || dto.slug.isNotBlank()) {
            saveSeatIdentity(
                app,
                SeatIdentity(
                    seatId = dto.seatId.ifBlank { prevSeat.seatId },
                    slug = dto.slug.ifBlank { prevSeat.slug },
                    voucher = prevSeat.voucher
                )
            )
        }

        val normStatus = dto.status.trim().uppercase()
        if (normStatus == "ENROLLED") {
            if (prevSeat.seatId.isNotBlank() && dto.seatId.isNotBlank() && dto.seatId != prevSeat.seatId) {
                clearSessionKey()
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_STATUS, "ENROLLED")
                if (dto.role.isNotBlank()) putString(KEY_ROLE, dto.role)
                if (dto.label.isNotBlank()) putString(KEY_LABEL, dto.label)
            }
            for (dir in listOfNotNull(app.noBackupFilesDir, app.filesDir)) {
                runCatching { File(dir, LEGACY_WRAPPED_DIR_KEY_FILE).delete() }
                runCatching { File(dir, "directory.db").delete() }
                runCatching { File(dir, "directory.db.vc").delete() }
            }
            notifyTrustStateListeners(app)
            PriorityVault.syncAllAsync(app)
            return
        }

        if (normStatus == "REVOKED" ||
            normStatus == "DISPLACED" ||
            (wasEnrolled && (isStatusPoll || normStatus.isNotEmpty()))
        ) {
            handleSeatRevokedOrDisplaced(app, newStatus = normStatus.ifEmpty { "REVOKED" })
            return
        }

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_STATUS, dto.status.ifBlank { "UNENROLLED" })
            if (dto.role.isNotBlank()) putString(KEY_ROLE, dto.role)
            if (dto.label.isNotBlank()) putString(KEY_LABEL, dto.label)
        }
        notifyTrustStateListeners(app)
    }

    /** Test-only helper for configuring an enrolled seat in Robolectric JVM unit tests. */
    internal fun setEnrolledForTest(
        context: Context,
        seatId: String,
        slug: String = "inv-test",
        voucher: String = "vouch-test",
        role: String = "MEMBER"
    ) {
        val app = context.applicationContext ?: context
        getOrCreateSigningKeys()
        saveSeatIdentity(app, SeatIdentity(seatId = seatId, slug = slug, voucher = voucher))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_STATUS, "ENROLLED")
            putString(KEY_ROLE, role)
        }
        notifyTrustStateListeners(app)
    }

    internal fun resetTrustForTest(context: Context) {
        val app = context.applicationContext ?: context
        VpnGeoGate.resetForTest()
        clearSessionKey()
        PriorityVault.destroyVaultKeyAndFiles(app)
        runCatching { File(app.noBackupFilesDir, SEAT_FILE_NAME).delete() }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        KokohoDirectory.purgeDecryptedCache(app)
        notifyTrustStateListeners(app)
    }

    private fun normalizeFixed32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        if (raw.size > 32) {
            return raw.copyOfRange(raw.size - 32, raw.size)
        }
        val out = ByteArray(32)
        System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        return out
    }

    // ---- AES-256-GCM (JCE primary + Pure-Kotlin NIST SP 800-38D fallback with AAD) --------------

    fun aes256GcmEncrypt(
        keyBytes: ByteArray,
        iv12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray? {
        if (keyBytes.size != 32 || iv12.size != 12) return null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, iv12)
            )
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            return cipher.doFinal(plaintext)
        } catch (_: Throwable) {
            return PureAes256Gcm.encrypt(keyBytes, iv12, plaintext, aad)
        }
    }

    fun aes256GcmDecrypt(
        keyBytes: ByteArray,
        iv12: ByteArray,
        ctAndTag: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray? {
        if (keyBytes.size != 32 || iv12.size != 12 || ctAndTag.size < 16) return null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, iv12)
            )
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            return cipher.doFinal(ctAndTag)
        } catch (_: Throwable) {
            return PureAes256Gcm.decrypt(keyBytes, iv12, ctAndTag, aad)
        }
    }

    internal object PureP256Ecdh {
        private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        private val A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
        private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
        private val TWO = BigInteger.valueOf(2L)
        private val THREE = BigInteger.valueOf(3L)

        private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
            if (x < BigInteger.ZERO || x >= P || y < BigInteger.ZERO || y >= P) return false
            val lhs = y.multiply(y).mod(P)
            val rhs = x.modPow(THREE, P).add(A.multiply(x)).add(B).mod(P)
            return lhs == rhs
        }

        private fun pointDouble(pt: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
            val (x1, y1) = pt ?: return null
            if (y1.signum() == 0) return null
            val num = THREE.multiply(x1.multiply(x1)).add(A).mod(P)
            val den = TWO.multiply(y1).mod(P).modInverse(P)
            val lam = num.multiply(den).mod(P)
            val x3 = lam.multiply(lam).subtract(TWO.multiply(x1)).mod(P)
            val y3 = lam.multiply(x1.subtract(x3)).subtract(y1).mod(P)
            return x3 to y3
        }

        private fun pointAdd(
            p1: Pair<BigInteger, BigInteger>?,
            p2: Pair<BigInteger, BigInteger>?
        ): Pair<BigInteger, BigInteger>? {
            if (p1 == null) return p2
            if (p2 == null) return p1
            val (x1, y1) = p1
            val (x2, y2) = p2
            if (x1 == x2) {
                return if (y1.add(y2).mod(P).signum() == 0) null else pointDouble(p1)
            }
            val num = y2.subtract(y1).mod(P)
            val den = x2.subtract(x1).mod(P).modInverse(P)
            val lam = num.multiply(den).mod(P)
            val x3 = lam.multiply(lam).subtract(x1).subtract(x2).mod(P)
            val y3 = lam.multiply(x1.subtract(x3)).subtract(y1).mod(P)
            return x3 to y3
        }

        fun sharedSecret32(priv: ECPrivateKey, pub: ECPublicKey): ByteArray? {
            val k = priv.s
            val qx = pub.w.affineX
            val qy = pub.w.affineY
            if (k <= BigInteger.ZERO || !isOnCurve(qx, qy)) return null
            var acc: Pair<BigInteger, BigInteger>? = null
            val base = qx to qy
            for (bit in k.bitLength() - 1 downTo 0) {
                acc = pointDouble(acc)
                if (k.testBit(bit)) {
                    acc = pointAdd(acc, base)
                }
            }
            val rx = acc?.first ?: return null
            return normalizeFixed32(rx.toByteArray())
        }
    }

    internal object PureAes256Gcm {
        private val SBOX = IntArray(256)
        private val RCON = intArrayOf(0, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)

        init {
            val p = IntArray(256)
            val q = IntArray(256)
            var x = 1
            for (i in 0 until 256) {
                p[i] = x
                q[x] = i
                x = x xor ((x shl 1) xor (if ((x and 0x80) != 0) 0x11b else 0))
                x = x and 0xff
            }
            SBOX[0] = 0x63
            for (i in 1 until 256) {
                val inv = p[255 - q[i]]
                var s = inv
                for (r in 1..4) {
                    s = s xor (((inv shl r) or (inv ushr (8 - r))) and 0xff)
                }
                SBOX[i] = s xor 0x63
            }
        }

        private fun subWord(w: Int): Int =
            (SBOX[(w ushr 24) and 0xff] shl 24) or
                (SBOX[(w ushr 16) and 0xff] shl 16) or
                (SBOX[(w ushr 8) and 0xff] shl 8) or
                SBOX[w and 0xff]

        private fun rotWord(w: Int): Int = (w shl 8) or (w ushr 24)

        private fun expandKey256(key: ByteArray): IntArray {
            val w = IntArray(60)
            for (i in 0 until 8) {
                w[i] = ((key[i * 4].toInt() and 0xff) shl 24) or
                    ((key[i * 4 + 1].toInt() and 0xff) shl 16) or
                    ((key[i * 4 + 2].toInt() and 0xff) shl 8) or
                    (key[i * 4 + 3].toInt() and 0xff)
            }
            for (i in 8 until 60) {
                var temp = w[i - 1]
                if (i % 8 == 0) {
                    temp = subWord(rotWord(temp)) xor (RCON[i / 8] shl 24)
                } else if (i % 8 == 4) {
                    temp = subWord(temp)
                }
                w[i] = w[i - 8] xor temp
            }
            return w
        }

        private fun xtime(a: Int): Int = ((a shl 1) xor (if ((a and 0x80) != 0) 0x1b else 0)) and 0xff

        private fun encryptBlock(rk: IntArray, inp: ByteArray, out: ByteArray) {
            val s = IntArray(16)
            for (i in 0 until 4) {
                val kw = rk[i]
                s[i * 4] = (inp[i * 4].toInt() and 0xff) xor ((kw ushr 24) and 0xff)
                s[i * 4 + 1] = (inp[i * 4 + 1].toInt() and 0xff) xor ((kw ushr 16) and 0xff)
                s[i * 4 + 2] = (inp[i * 4 + 2].toInt() and 0xff) xor ((kw ushr 8) and 0xff)
                s[i * 4 + 3] = (inp[i * 4 + 3].toInt() and 0xff) xor (kw and 0xff)
            }
            val t = IntArray(16)
            for (round in 1..13) {
                for (i in 0 until 16) t[i] = SBOX[s[i]]
                s[0] = t[0]; s[4] = t[4]; s[8] = t[8]; s[12] = t[12]
                s[1] = t[5]; s[5] = t[9]; s[9] = t[13]; s[13] = t[1]
                s[2] = t[10]; s[6] = t[14]; s[10] = t[2]; s[14] = t[6]
                s[3] = t[15]; s[7] = t[3]; s[11] = t[7]; s[15] = t[11]
                val base = round * 4
                for (c in 0 until 4) {
                    val idx = c * 4
                    val a0 = s[idx]; val a1 = s[idx + 1]; val a2 = s[idx + 2]; val a3 = s[idx + 3]
                    val all = a0 xor a1 xor a2 xor a3
                    val kw = rk[base + c]
                    s[idx] = a0 xor all xor xtime(a0 xor a1) xor ((kw ushr 24) and 0xff)
                    s[idx + 1] = a1 xor all xor xtime(a1 xor a2) xor ((kw ushr 16) and 0xff)
                    s[idx + 2] = a2 xor all xor xtime(a2 xor a3) xor ((kw ushr 8) and 0xff)
                    s[idx + 3] = a3 xor all xor xtime(a3 xor a0) xor (kw and 0xff)
                }
            }
            for (i in 0 until 16) t[i] = SBOX[s[i]]
            val sr = intArrayOf(
                t[0], t[5], t[10], t[15],
                t[4], t[9], t[14], t[3],
                t[8], t[13], t[2], t[7],
                t[12], t[1], t[6], t[11]
            )
            for (c in 0 until 4) {
                val kw = rk[56 + c]
                out[c * 4] = (sr[c * 4] xor ((kw ushr 24) and 0xff)).toByte()
                out[c * 4 + 1] = (sr[c * 4 + 1] xor ((kw ushr 16) and 0xff)).toByte()
                out[c * 4 + 2] = (sr[c * 4 + 2] xor ((kw ushr 8) and 0xff)).toByte()
                out[c * 4 + 3] = (sr[c * 4 + 3] xor (kw and 0xff)).toByte()
            }
        }

        private fun gfMul(x0: Long, x1: Long, h0: Long, h1: Long): LongArray {
            var z0 = 0L
            var z1 = 0L
            var v0 = h0
            var v1 = h1
            for (i in 0 until 64) {
                if (((x0 ushr (63 - i)) and 1L) != 0L) {
                    z0 = z0 xor v0
                    z1 = z1 xor v1
                }
                val lsb = v1 and 1L
                v1 = (v1 ushr 1) or (v0 shl 63)
                v0 = v0 ushr 1
                if (lsb != 0L) v0 = v0 xor (0xe1L shl 56)
            }
            for (i in 0 until 64) {
                if (((x1 ushr (63 - i)) and 1L) != 0L) {
                    z0 = z0 xor v0
                    z1 = z1 xor v1
                }
                val lsb = v1 and 1L
                v1 = (v1 ushr 1) or (v0 shl 63)
                v0 = v0 ushr 1
                if (lsb != 0L) v0 = v0 xor (0xe1L shl 56)
            }
            return longArrayOf(z0, z1)
        }

        private fun readBeLong(buf: ByteArray, off: Int): Long {
            var r = 0L
            for (i in 0 until 8) r = (r shl 8) or (buf[off + i].toLong() and 0xffL)
            return r
        }

        private fun writeBeLong(v: Long, buf: ByteArray, off: Int) {
            for (i in 0 until 8) {
                buf[off + i] = ((v ushr (56 - i * 8)) and 0xffL).toByte()
            }
        }

        private fun computeGhash(
            h0: Long,
            h1: Long,
            aad: ByteArray,
            ctBuf: ByteArray,
            ctLen: Int
        ): LongArray {
            var y0 = 0L
            var y1 = 0L
            val block = ByteArray(16)

            var aOff = 0
            while (aOff < aad.size) {
                val chunk = minOf(16, aad.size - aOff)
                java.util.Arrays.fill(block, 0.toByte())
                System.arraycopy(aad, aOff, block, 0, chunk)
                val res = gfMul(y0 xor readBeLong(block, 0), y1 xor readBeLong(block, 8), h0, h1)
                y0 = res[0]
                y1 = res[1]
                aOff += chunk
            }

            var cOff = 0
            while (cOff < ctLen) {
                val chunk = minOf(16, ctLen - cOff)
                java.util.Arrays.fill(block, 0.toByte())
                System.arraycopy(ctBuf, cOff, block, 0, chunk)
                val res = gfMul(y0 xor readBeLong(block, 0), y1 xor readBeLong(block, 8), h0, h1)
                y0 = res[0]
                y1 = res[1]
                cOff += chunk
            }

            return gfMul(y0 xor (aad.size.toLong() * 8L), y1 xor (ctLen.toLong() * 8L), h0, h1)
        }

        fun encrypt(
            key: ByteArray,
            iv12: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray = ByteArray(0)
        ): ByteArray {
            val rk = expandKey256(key)
            val hBytes = ByteArray(16)
            encryptBlock(rk, ByteArray(16), hBytes)
            val h0 = readBeLong(hBytes, 0)
            val h1 = readBeLong(hBytes, 8)

            val out = ByteArray(plaintext.size + 16)
            val cb = ByteArray(16)
            System.arraycopy(iv12, 0, cb, 0, 12)
            val ks = ByteArray(16)
            var ctr = 2
            var off = 0
            while (off < plaintext.size) {
                cb[12] = (ctr ushr 24).toByte()
                cb[13] = (ctr ushr 16).toByte()
                cb[14] = (ctr ushr 8).toByte()
                cb[15] = ctr.toByte()
                encryptBlock(rk, cb, ks)
                val chunk = minOf(16, plaintext.size - off)
                for (i in 0 until chunk) {
                    out[off + i] = (plaintext[off + i].toInt() xor ks[i].toInt()).toByte()
                }
                ctr++
                off += chunk
            }

            val ghash = computeGhash(h0, h1, aad, out, plaintext.size)
            cb[12] = 0; cb[13] = 0; cb[14] = 0; cb[15] = 1
            val ej0 = ByteArray(16)
            encryptBlock(rk, cb, ej0)
            writeBeLong(readBeLong(ej0, 0) xor ghash[0], out, plaintext.size)
            writeBeLong(readBeLong(ej0, 8) xor ghash[1], out, plaintext.size + 8)
            return out
        }

        fun decrypt(
            key: ByteArray,
            iv12: ByteArray,
            ctAndTag: ByteArray,
            aad: ByteArray = ByteArray(0)
        ): ByteArray? {
            val ctLen = ctAndTag.size - 16
            if (ctLen < 0) return null
            val rk = expandKey256(key)
            val hBytes = ByteArray(16)
            encryptBlock(rk, ByteArray(16), hBytes)
            val h0 = readBeLong(hBytes, 0)
            val h1 = readBeLong(hBytes, 8)

            val ghash = computeGhash(h0, h1, aad, ctAndTag, ctLen)

            val cb = ByteArray(16)
            System.arraycopy(iv12, 0, cb, 0, 12)
            cb[15] = 1
            val ej0 = ByteArray(16)
            encryptBlock(rk, cb, ej0)
            val expTag0 = readBeLong(ej0, 0) xor ghash[0]
            val expTag1 = readBeLong(ej0, 8) xor ghash[1]
            val gotTag0 = readBeLong(ctAndTag, ctLen)
            val gotTag1 = readBeLong(ctAndTag, ctLen + 8)
            if ((expTag0 xor gotTag0) or (expTag1 xor gotTag1) != 0L) return null

            val plain = ByteArray(ctLen)
            val ks = ByteArray(16)
            var ctr = 2
            var off = 0
            while (off < ctLen) {
                cb[12] = (ctr ushr 24).toByte()
                cb[13] = (ctr ushr 16).toByte()
                cb[14] = (ctr ushr 8).toByte()
                cb[15] = ctr.toByte()
                encryptBlock(rk, cb, ks)
                val chunk = minOf(16, ctLen - off)
                for (i in 0 until chunk) {
                    plain[off + i] = (ctAndTag[off + i].toInt() xor ks[i].toInt()).toByte()
                }
                ctr++
                off += chunk
            }
            return plain
        }
    }
}
