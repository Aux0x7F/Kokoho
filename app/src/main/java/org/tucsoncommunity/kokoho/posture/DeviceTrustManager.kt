package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.os.Build
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Single-instance hardware trust & web-session grant manager (`v1.3.9`).
 *
 *  - Extracts per-seat voucher metadata (`0x54434B56`) from the APK Signing Block on first launch
 *    and persists it inside `noBackupFilesDir` so subsequent universal OTA updates retain seat trust.
 *  - Generates a non-exportable `EC P-256` (`SHA256withECDSA`) keypair in `AndroidKeyStore`
 *    (StrongBox when available, TEE fallback) and binds it to the Hub via `/v1/auth/enroll`.
 *  - Wraps any provisioned asset decryption key under a non-exportable `AndroidKeyStore`
 *    `AES/GCM/NoPadding` key (`kokoho_dir_wrap_v1`) stored only in `noBackupFilesDir`.
 *  - Signs ephemeral single-session web QR grants (`/v1/auth/grant-web`) and authenticated Hub headers.
 */
object DeviceTrustManager {

    private const val APK_SEAT_BLOCK_ID = 0x54434B56
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val EC_KEY_ALIAS = "kokoho_seat_ec_v1"
    private const val AES_WRAP_ALIAS = "kokoho_dir_wrap_v1"

    private const val PREFS = "kokoho_trust_prefs"
    private const val KEY_STATUS = "trust_status"
    private const val KEY_ROLE = "trust_role"
    private const val KEY_LABEL = "trust_label"

    private const val SEAT_FILE_NAME = "seat_identity.json"
    private const val WRAPPED_DIR_KEY_FILE = "dir_key.wrap"

    data class SeatIdentity(
        @SerializedName("seat_id") val seatId: String = "",
        val slug: String = "",
        val voucher: String = ""
    )

    data class AuthResponseDto(
        val ok: Boolean = false,
        val status: String = "",
        @SerializedName("seat_id") val seatId: String = "",
        val slug: String = "",
        val role: String = "MEMBER",
        val label: String = "",
        @SerializedName("dir_key_hex") val dirKeyHex: String? = null,
        val message: String? = null
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyLock = Any()

    @Volatile
    private var cachedDirKeyBytes: ByteArray? = null

    @Volatile
    private var fallbackJvmKeyPair: KeyPair? = null

    /** Test-only hook for JVM/Robolectric unit tests where AndroidKeyStore hardware is unavailable. */
    fun injectTestDirectoryKeyHex(context: Context, hex: String?) {
        cachedDirKeyBytes = hex?.trim()?.takeIf { it.length == 64 }?.let { hexToBytes(it) }
        KokohoDirectory.invalidateForReload()
        if (cachedDirKeyBytes != null) {
            KokohoDirectory.ensureLoaded(context)
        }
    }

    fun enrollmentStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "UNENROLLED") ?: "UNENROLLED"

    fun enrolledRole(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "MEMBER") ?: "MEMBER"

    fun isEnrolled(context: Context): Boolean =
        enrollmentStatus(context).equals("ENROLLED", ignoreCase = true) &&
            loadOrExtractSeatIdentity(context)?.seatId?.isNotBlank() == true

    /**
     * Reads `{ seat_id, slug, voucher }` from `noBackupFilesDir/seat_identity.json`, or extracts it
     * from the installed APK's Signing Block (`0x54434B56`) on first launch / seat-stamped upgrade.
     */
    fun loadOrExtractSeatIdentity(context: Context): SeatIdentity? = synchronized(keyLock) {
        val app = context.applicationContext ?: context
        val seatFile = File(app.noBackupFilesDir, SEAT_FILE_NAME)

        // Always check APK Signing Block first in case user installed a newly stamped seat APK
        val apkPath = runCatching { app.applicationInfo?.sourceDir }.getOrNull()
        if (!apkPath.isNullOrBlank()) {
            val fromApk = runCatching { readSeatIdentityFromApk(File(apkPath)) }.getOrNull()
            if (fromApk != null && (fromApk.seatId.isNotBlank() || fromApk.slug.isNotBlank())) {
                runCatching {
                    app.noBackupFilesDir.mkdirs()
                    seatFile.writeText(gson.toJson(fromApk), Charsets.UTF_8)
                }
                return fromApk
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
        // 1. Hardware-backed AndroidKeyStore EC P-256
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (!ks.containsAlias(EC_KEY_ALIAS)) {
                val generated = generateEcKeyInKeystore(useStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                if (!generated) {
                    generateEcKeyInKeystore(useStrongBox = false)
                }
            }
            val entry = ks.getEntry(EC_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                val spkiB64 = Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
                return entry.privateKey to spkiB64
            }
        }

        // 2. Standard JCE fallback (for Robolectric / non-Keystore environments)
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

    private fun getOrCreateWrapSecretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = (ks.getEntry(AES_WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        kg.init(
            KeyGenParameterSpec.Builder(
                AES_WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        kg.generateKey()
    }.getOrNull()

    private fun persistDirectoryKeyHex(context: Context, hex: String): Unit = synchronized(keyLock) {
        val raw = hexToBytes(hex.trim())
        if (raw.size != 32) return
        cachedDirKeyBytes = raw
        val app = context.applicationContext ?: context
        val secretKey = getOrCreateWrapSecretKey() ?: return
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ct = cipher.doFinal(raw)
            val out = ByteArray(1 + iv.size + ct.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            app.noBackupFilesDir.mkdirs()
            File(app.noBackupFilesDir, WRAPPED_DIR_KEY_FILE).writeBytes(out)
        }
    }

    fun getDirectoryKeyBytes(context: Context): ByteArray? {
        cachedDirKeyBytes?.let { return it }
        synchronized(keyLock) {
            cachedDirKeyBytes?.let { return it }
            val app = context.applicationContext ?: context
            val wrapFile = File(app.noBackupFilesDir, WRAPPED_DIR_KEY_FILE)
            if (!wrapFile.exists()) return null
            val secretKey = getOrCreateWrapSecretKey() ?: return null
            return runCatching {
                val blob = wrapFile.readBytes()
                if (blob.size < 29) return null
                val ivLen = blob[0].toInt() and 0xFF
                if (ivLen !in 12..16 || blob.size <= 1 + ivLen + 16) return null
                val iv = blob.copyOfRange(1, 1 + ivLen)
                val ct = blob.copyOfRange(1 + ivLen, blob.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.doFinal(ct).takeIf { it.size == 32 }?.also { cachedDirKeyBytes = it }
            }.getOrNull()
        }
    }

    private fun clearDirectoryKey(context: Context) = synchronized(keyLock) {
        cachedDirKeyBytes = null
        val app = context.applicationContext ?: context
        runCatching { File(app.noBackupFilesDir, WRAPPED_DIR_KEY_FILE).delete() }
        KokohoDirectory.purgeDecryptedCache(app)
    }

    /**
     * Attaches signed hardware-key seat headers (`x-kokoho-seat-id`, `x-kokoho-ts`, `x-kokoho-sig`)
     * to outgoing Hub HTTP connections when enrolled.
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

    /**
     * Automatically performs hardware enrollment (or status verification) in the background when
     * a seat voucher is bundled or previously claimed.
     */
    fun ensureEnrolledAsync(context: Context, onComplete: ((AuthResponseDto?) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        val seat = loadOrExtractSeatIdentity(app)
        if (seat == null) {
            onComplete?.invoke(null)
            return
        }
        scope.launch {
            val resp = if (isEnrolled(app) && getDirectoryKeyBytes(app) != null && seat.seatId.isNotBlank()) {
                checkDeviceStatusBlocking(app, seat) ?: enrollBlocking(app, seat)
            } else {
                enrollBlocking(app, seat)
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
            onResult(false, "Invalid invitation QR code")
            return
        }
        scope.launch {
            val tempIdentity = SeatIdentity(seatId = "", slug = cleanSlug, voucher = "")
            val resp = enrollBlocking(app, tempIdentity)
            withContext(Dispatchers.Main) {
                when {
                    resp == null -> onResult(false, "Could not reach Kokoho Hub — check VPN/network")
                    resp.status == "ENROLLED" -> onResult(true, "Device enrolled (${resp.role.lowercase().replaceFirstChar { it.uppercase() }})")
                    resp.status == "PENDING_REBIND" -> onResult(false, resp.message ?: "Pending Owner rebind approval")
                    resp.status == "REVOKED" -> onResult(false, "Invitation has been revoked")
                    else -> onResult(false, resp.message ?: "Enrollment declined")
                }
            }
        }
    }

    fun grantWebSession(
        context: Context,
        challengeId: String,
        secret: String,
        slugHint: String?,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        val app = context.applicationContext ?: context
        scope.launch {
            var seat = loadOrExtractSeatIdentity(app)
            if ((seat == null || seat.seatId.isBlank()) && !slugHint.isNullOrBlank()) {
                enrollBlocking(app, SeatIdentity(seatId = "", slug = slugHint.trim(), voucher = ""))
                seat = loadOrExtractSeatIdentity(app)
            } else if (seat != null && !isEnrolled(app)) {
                enrollBlocking(app, seat)
                seat = loadOrExtractSeatIdentity(app)
            }

            if (seat == null || seat.seatId.isBlank() || !isEnrolled(app)) {
                withContext(Dispatchers.Main) {
                    onResult(false, "This phone is not yet enrolled. Scan your onboarding QR or install your seat APK first.")
                }
                return@launch
            }

            val keys = getOrCreateSigningKeys()
            if (keys == null) {
                withContext(Dispatchers.Main) { onResult(false, "Hardware keystore unavailable") }
                return@launch
            }
            val ts = System.currentTimeMillis()
            val msg = "grant|${seat.seatId}|${challengeId.trim()}|${secret.trim()}|$ts"
            val sig = signMessageBase64(keys.first, msg)
            if (sig == null) {
                withContext(Dispatchers.Main) { onResult(false, "Signature failed") }
                return@launch
            }

            val payload = mapOf(
                "seat_id" to seat.seatId,
                "challenge_id" to challengeId.trim(),
                "secret" to secret.trim(),
                "timestamp_ms" to ts,
                "signature_b64" to sig
            )
            val base = KokohoHubClient.hubUrl(app)
            val okPair = runCatching {
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
                    if (code in 200..299) {
                        true to "Single-session browser access authorized"
                    } else {
                        val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                        false to (err.takeIf { it.isNotBlank() } ?: "QR challenge expired — try scanning again")
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { false to "Network error reaching Hub" }

            withContext(Dispatchers.Main) {
                onResult(okPair.first, okPair.second)
            }
        }
    }

    private fun enrollBlocking(context: Context, seat: SeatIdentity): AuthResponseDto? {
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
        applyAuthOutcome(context, seat, dto)
        return dto
    }

    private fun checkDeviceStatusBlocking(context: Context, seat: SeatIdentity): AuthResponseDto? {
        if (seat.seatId.isBlank()) return null
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
        applyAuthOutcome(context, seat, dto)
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
                if (conn.responseCode in 200..299) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    gson.fromJson(txt, AuthResponseDto::class.java)
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    private fun applyAuthOutcome(context: Context, prevSeat: SeatIdentity, dto: AuthResponseDto) {
        val app = context.applicationContext ?: context
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
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_STATUS, dto.status.ifBlank { "UNENROLLED" })
            if (dto.role.isNotBlank()) putString(KEY_ROLE, dto.role)
            if (dto.label.isNotBlank()) putString(KEY_LABEL, dto.label)
        }
        when (dto.status.uppercase()) {
            "ENROLLED" -> {
                if (!dto.dirKeyHex.isNullOrBlank()) {
                    persistDirectoryKeyHex(app, dto.dirKeyHex)
                    KokohoDirectory.reloadAfterKeyUnlock(app)
                }
                KokohoHubClient.syncPlates(app)
            }
            "REVOKED" -> clearDirectoryKey(app)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Decrypts `AES-256-GCM` (`12-byte IV` + `ciphertext || 16-byte tag`) using hardware JCE when
     * available and falling back cleanly to pure-Kotlin NIST SP 800-38D AES-256-GCM under JVM/Robolectric
     * sandboxed classloaders where `JceSecurity` cannot initialize.
     */
    fun aes256GcmDecrypt(keyBytes: ByteArray, iv12: ByteArray, ctAndTag: ByteArray): ByteArray? {
        if (keyBytes.size != 32 || iv12.size != 12 || ctAndTag.size < 16) return null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, iv12)
            )
            return cipher.doFinal(ctAndTag)
        } catch (_: Throwable) {
            return PureAes256Gcm.decrypt(keyBytes, iv12, ctAndTag)
        }
    }

    private object PureAes256Gcm {
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
                // ShiftRows
                s[0] = t[0]; s[4] = t[4]; s[8] = t[8]; s[12] = t[12]
                s[1] = t[5]; s[5] = t[9]; s[9] = t[13]; s[13] = t[1]
                s[2] = t[10]; s[6] = t[14]; s[10] = t[2]; s[14] = t[6]
                s[3] = t[15]; s[7] = t[3]; s[11] = t[7]; s[15] = t[11]
                // MixColumns + AddRoundKey
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
            // Final round 14
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

        fun decrypt(key: ByteArray, iv12: ByteArray, ctAndTag: ByteArray): ByteArray? {
            val ctLen = ctAndTag.size - 16
            val rk = expandKey256(key)
            val hBytes = ByteArray(16)
            encryptBlock(rk, ByteArray(16), hBytes)
            val h0 = readBeLong(hBytes, 0)
            val h1 = readBeLong(hBytes, 8)

            // GHASH over ciphertext (AAD = empty)
            var y0 = 0L
            var y1 = 0L
            val block = ByteArray(16)
            var off = 0
            while (off < ctLen) {
                val chunk = minOf(16, ctLen - off)
                java.util.Arrays.fill(block, 0.toByte())
                System.arraycopy(ctAndTag, off, block, 0, chunk)
                val res = gfMul(y0 xor readBeLong(block, 0), y1 xor readBeLong(block, 8), h0, h1)
                y0 = res[0]
                y1 = res[1]
                off += chunk
            }
            val resLen = gfMul(y0, y1 xor (ctLen.toLong() * 8L), h0, h1)
            y0 = resLen[0]
            y1 = resLen[1]

            // J0 = IV || 0x00000001
            val cb = ByteArray(16)
            System.arraycopy(iv12, 0, cb, 0, 12)
            cb[15] = 1
            val ej0 = ByteArray(16)
            encryptBlock(rk, cb, ej0)
            val expTag0 = readBeLong(ej0, 0) xor y0
            val expTag1 = readBeLong(ej0, 8) xor y1
            val gotTag0 = readBeLong(ctAndTag, ctLen)
            val gotTag1 = readBeLong(ctAndTag, ctLen + 8)
            if ((expTag0 xor gotTag0) or (expTag1 xor gotTag1) != 0L) return null

            // GCTR decrypt
            val plain = ByteArray(ctLen)
            val ks = ByteArray(16)
            var ctr = 2
            off = 0
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
