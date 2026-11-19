package org.tucsoncommunity.kokoho.posture

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-sealed (`StrongBox -> TEE`) priority store for enrolled seat tiers (`v1.3.12`):
 *  - `p1_plates.seal`: Priority 1 incremental license plate snapshot + delta store (`/v1/sync/p1-plates`)
 *  - `p2_sector_dir.seal`: Priority 2 sector facilities & stationed personnel (`/v1/sync/tier/p2_sector_dir`)
 *  - `p3_ext_dir.seal`: Priority 3 extended personnel & reporting edges (`/v1/sync/tier/p3_ext_dir`)
 *  - `vault_meta.json`: Sync cursors (`p1SinceMs`, `p2Etag`, `p3Etag`)
 *
 * Raw hardware key bytes (`kokoho_vault_aes_v1`) never enter the JVM heap on Android devices,
 * and decrypted RAM caches are automatically cleared on `Intent.ACTION_SCREEN_OFF`.
 */
object PriorityVault {

    const val VAULT_KEY_ALIAS = "kokoho_vault_aes_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val VAULT_DIR_NAME = "vault"

    const val P1_PLATES_FILE = "p1_plates.seal"
    const val P2_SECTOR_DIR_FILE = "p2_sector_dir.seal"
    const val P3_EXT_DIR_FILE = "p3_ext_dir.seal"
    const val VAULT_META_FILE = "vault_meta.json"

    data class PlatesSyncResponseDto(
        @SerializedName("mode") val mode: String = "SNAPSHOT",
        @SerializedName("server_time_ms") val serverTimeMs: Long = 0L,
        @SerializedName("plates") val plates: List<KokohoHubClient.SyncedPlateRecord> = emptyList()
    )

    data class SyncedLocationDto(
        @SerializedName("location_id") val locationId: String = "",
        @SerializedName("title") val title: String = "",
        @SerializedName("agency_code") val agencyCode: String = "DHS_INTEROP",
        @SerializedName("lat") val lat: Double = 0.0,
        @SerializedName("lon") val lon: Double = 0.0,
        @SerializedName("address") val address: String = "",
        @SerializedName("description") val description: String = "",
        @SerializedName("location_group") val locationGroup: String = "FEDERAL",
        @SerializedName("state_code") val stateCode: String = "AZ"
    )

    data class SyncedPersonDto(
        @SerializedName("person_id") val personId: String = "",
        @SerializedName("full_name") val fullName: String = "",
        @SerializedName("title") val title: String = "",
        @SerializedName("agency_code") val agencyCode: String = "DHS_INTEROP",
        @SerializedName("agency_display") val agencyDisplay: String = "",
        @SerializedName("location_id") val locationId: String = "",
        @SerializedName("street_address") val streetAddress: String = "",
        @SerializedName("suite") val suite: String = "",
        @SerializedName("state_code") val stateCode: String = "AZ",
        @SerializedName("email") val email: String = "",
        @SerializedName("second_email") val secondEmail: String = "",
        @SerializedName("primary_phone") val primaryPhone: String = "",
        @SerializedName("mobile_phone") val mobilePhone: String = "",
        @SerializedName("badge_number") val badgeNumber: String = "",
        @SerializedName("managed_by") val managedBy: String = ""
    )

    data class SyncedEdgeDto(
        @SerializedName("person_id") val personId: String = "",
        @SerializedName("manager_id") val managerId: String = ""
    )

    data class DirectoryTierSyncDto(
        @SerializedName("tier") val tier: String = "",
        @SerializedName("etag") val etag: String = "",
        @SerializedName("locations") val locations: List<SyncedLocationDto> = emptyList(),
        @SerializedName("persons") val persons: List<SyncedPersonDto> = emptyList(),
        @SerializedName("edges") val edges: List<SyncedEdgeDto> = emptyList()
    )

    data class VaultMetaDto(
        @SerializedName("p1SinceMs") val p1SinceMs: Long = 0L,
        @SerializedName("p2Etag") val p2Etag: String = "",
        @SerializedName("p3Etag") val p3Etag: String = ""
    )

    internal class HydratedDirectoryData(
        val locations: List<KokohoDirectory.FacilityLocation>,
        val orderedPersons: List<KokohoDirectory.PersonRecord>,
        val personsById: Map<String, KokohoDirectory.PersonRecord>,
        val personsByLocation: Map<String, List<KokohoDirectory.PersonRecord>>,
        val managerByReportId: Map<String, KokohoDirectory.PersonRecord>,
        val reportsByManagerId: Map<String, List<KokohoDirectory.PersonRecord>>
    )

    private enum class SealOutcome {
        SEALED,
        DEFERRED_LOCKED,
        FAILED
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vaultLock = Any()
    private val syncRunLock = Any()
    private val receiverLock = Any()
    private val revisionCounter = AtomicLong(1L)

    val vaultDataRevision: Long
        get() = revisionCounter.get()

    @Volatile
    private var inMemoryPlates: List<KokohoHubClient.SyncedPlateRecord>? = null

    @Volatile
    private var inMemoryP2: DirectoryTierSyncDto? = null

    @Volatile
    private var inMemoryP3: DirectoryTierSyncDto? = null

    @Volatile
    private var inMemoryHydratedDir: HydratedDirectoryData? = null

    @Volatile
    private var registeredAppContext: Context? = null

    @Volatile
    var syncDeferredWhileLocked: Boolean = false
        private set

    @Volatile
    internal var simulateDeviceLockedForTest: Boolean = false

    @Volatile
    private var robolectricTestKeyBytes: ByteArray? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    clearInMemoryCaches(context)
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (context != null && syncDeferredWhileLocked && DeviceTrustManager.isEnrolled(context)) {
                        syncAllAsync(context)
                    }
                }
            }
        }
    }

    fun ensureScreenOffReceiverRegistered(context: Context) {
        val app = context.applicationContext ?: context
        if (registeredAppContext === app) return
        synchronized(receiverLock) {
            if (registeredAppContext === app) return
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(
                    app,
                    screenStateReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                registeredAppContext = app
            }
        }
    }

    fun vaultDir(context: Context): File {
        val app = context.applicationContext ?: context
        val base = app.noBackupFilesDir ?: app.filesDir
        return File(base, VAULT_DIR_NAME)
    }

    fun hasAnySyncedData(context: Context): Boolean {
        if (inMemoryPlates?.isNotEmpty() == true || inMemoryHydratedDir != null) return true
        val dir = vaultDir(context)
        if (!dir.exists()) return false
        return File(dir, P1_PLATES_FILE).let { it.exists() && it.length() > 29L } ||
            File(dir, P2_SECTOR_DIR_FILE).let { it.exists() && it.length() > 29L } ||
            File(dir, P3_EXT_DIR_FILE).let { it.exists() && it.length() > 29L }
    }

    fun hasSyncedDirectoryFiles(context: Context): Boolean {
        if (inMemoryHydratedDir != null || inMemoryP2 != null || inMemoryP3 != null) return true
        val dir = vaultDir(context)
        if (!dir.exists()) return false
        return File(dir, P2_SECTOR_DIR_FILE).let { it.exists() && it.length() > 29L } ||
            File(dir, P3_EXT_DIR_FILE).let { it.exists() && it.length() > 29L }
    }

    fun isInMemoryCacheZeroed(): Boolean =
        inMemoryPlates == null && inMemoryP2 == null && inMemoryP3 == null && inMemoryHydratedDir == null

    fun readVaultMeta(context: Context): VaultMetaDto = synchronized(vaultLock) {
        val metaFile = File(vaultDir(context), VAULT_META_FILE)
        if (!metaFile.exists()) return VaultMetaDto()
        return runCatching {
            gson.fromJson(metaFile.readText(Charsets.UTF_8), VaultMetaDto::class.java)
        }.getOrNull() ?: VaultMetaDto()
    }

    private fun writeVaultMeta(context: Context, meta: VaultMetaDto) = synchronized(vaultLock) {
        runCatching {
            val dir = vaultDir(context).apply { mkdirs() }
            val target = File(dir, VAULT_META_FILE)
            val tmp = File(dir, "$VAULT_META_FILE.tmp")
            tmp.writeText(gson.toJson(meta), Charsets.UTF_8)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isRobolectricOrNonAndroidJvm(): Boolean {
        if (Build.FINGERPRINT == null || Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return true
        }
        if (Security.getProvider(KEYSTORE_PROVIDER) == null) {
            return true
        }
        return runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            false
        }.getOrDefault(true)
    }

    private fun getOrCreateHardwareVaultKey(): SecretKey? {
        val ks = runCatching { KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) } }.getOrNull()
            ?: return null
        val existing = runCatching {
            (ks.getEntry(VAULT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: (ks.getKey(VAULT_KEY_ALIAS, null) as? SecretKey)
        }.getOrNull()
        if (existing != null) return existing

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sbKey = try {
                generateKeystoreAesKey(useStrongBox = true, requireUnlocked = true)
            } catch (_: StrongBoxUnavailableException) {
                null
            } catch (_: Throwable) {
                null
            }
            if (sbKey != null) return sbKey
            runCatching { if (ks.containsAlias(VAULT_KEY_ALIAS)) ks.deleteEntry(VAULT_KEY_ALIAS) }
        }

        val teeUnlocked = runCatching {
            generateKeystoreAesKey(
                useStrongBox = false,
                requireUnlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            )
        }.getOrNull()
        if (teeUnlocked != null) return teeUnlocked

        runCatching { if (ks.containsAlias(VAULT_KEY_ALIAS)) ks.deleteEntry(VAULT_KEY_ALIAS) }
        return runCatching {
            generateKeystoreAesKey(useStrongBox = false, requireUnlocked = false)
        }.getOrNull()
    }

    private fun generateKeystoreAesKey(useStrongBox: Boolean, requireUnlocked: Boolean): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val specBuilder = KeyGenParameterSpec.Builder(
            VAULT_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(useStrongBox)
            if (requireUnlocked) {
                specBuilder.setUnlockedDeviceRequired(true)
            }
        }
        kg.init(specBuilder.build())
        return kg.generateKey()
    }

    private fun getOrCreateRobolectricFallbackKey(): ByteArray {
        robolectricTestKeyBytes?.let { return it }
        val fresh = ByteArray(32)
        SecureRandom().nextBytes(fresh)
        robolectricTestKeyBytes = fresh
        return fresh
    }

    private fun isDeviceCurrentlyLocked(context: Context): Boolean {
        if (simulateDeviceLockedForTest) return true
        val km = runCatching { context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }.getOrNull()
        return km?.isDeviceLocked == true
    }

    private fun isDeviceLockedThrowable(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is UserNotAuthenticatedException) return true
            val clsName = cur.javaClass.name
            if (clsName.endsWith("DeviceLockedException")) return true
            val msg = cur.message.orEmpty()
            if (msg.contains("device is locked", ignoreCase = true) ||
                msg.contains("User not authenticated", ignoreCase = true) ||
                msg.contains("KEY_USER_NOT_AUTHENTICATED", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun gzipCompress(utf8Json: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            gz.write(utf8Json.toByteArray(Charsets.UTF_8))
        }
        return baos.toByteArray()
    }

    private fun gzipDecompress(compressed: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
    }

    private fun sealJsonToFile(context: Context, targetFile: File, json: String): SealOutcome = synchronized(vaultLock) {
        if (isDeviceCurrentlyLocked(context)) {
            syncDeferredWhileLocked = true
            return SealOutcome.DEFERRED_LOCKED
        }
        val compressed = runCatching { gzipCompress(json) }.getOrNull() ?: return SealOutcome.FAILED

        val sealedBlob: ByteArray = if (!isRobolectricOrNonAndroidJvm()) {
            val secretKey = getOrCreateHardwareVaultKey() ?: return SealOutcome.FAILED
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val ct = cipher.doFinal(compressed)
                if (iv == null || iv.size !in 12..16) return SealOutcome.FAILED
                ByteArray(1 + iv.size + ct.size).also { out ->
                    out[0] = iv.size.toByte()
                    System.arraycopy(iv, 0, out, 1, iv.size)
                    System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
                }
            } catch (t: Throwable) {
                if (isDeviceLockedThrowable(t)) {
                    syncDeferredWhileLocked = true
                    return SealOutcome.DEFERRED_LOCKED
                }
                return SealOutcome.FAILED
            }
        } else {
            val keyBytes = getOrCreateRobolectricFallbackKey()
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val ct = DeviceTrustManager.aes256GcmEncrypt(keyBytes, iv, compressed) ?: return SealOutcome.FAILED
            ByteArray(1 + iv.size + ct.size).also { out ->
                out[0] = iv.size.toByte()
                System.arraycopy(iv, 0, out, 1, iv.size)
                System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            }
        }

        return runCatching {
            val parent = targetFile.parentFile ?: vaultDir(context)
            parent.mkdirs()
            val tmp = File(parent, "${targetFile.name}.tmp")
            tmp.writeBytes(sealedBlob)
            Files.move(tmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            SealOutcome.SEALED
        }.getOrDefault(SealOutcome.FAILED)
    }

    private fun unsealJsonFromFile(context: Context, sourceFile: File): String? = synchronized(vaultLock) {
        if (!sourceFile.exists() || sourceFile.length() <= 29L) return null
        if (isDeviceCurrentlyLocked(context)) {
            syncDeferredWhileLocked = true
            return null
        }
        val blob = runCatching { sourceFile.readBytes() }.getOrNull() ?: return null
        if (blob.size <= 29) return null
        val ivLen = blob[0].toInt() and 0xFF
        if (ivLen !in 12..16 || blob.size <= 1 + ivLen + 16) return null
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ctAndTag = blob.copyOfRange(1 + ivLen, blob.size)

        val compressed: ByteArray = if (!isRobolectricOrNonAndroidJvm()) {
            val secretKey = getOrCreateHardwareVaultKey() ?: return null
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.doFinal(ctAndTag)
            } catch (t: Throwable) {
                if (isDeviceLockedThrowable(t)) {
                    syncDeferredWhileLocked = true
                }
                return null
            }
        } else {
            val keyBytes = robolectricTestKeyBytes ?: return null
            if (iv.size != 12) return null
            DeviceTrustManager.aes256GcmDecrypt(keyBytes, iv, ctAndTag) ?: return null
        }

        return runCatching { gzipDecompress(compressed) }.getOrNull()
    }

    fun getPlates(context: Context): List<KokohoHubClient.SyncedPlateRecord> {
        val app = context.applicationContext ?: context
        ensureScreenOffReceiverRegistered(app)
        inMemoryPlates?.let { return it }
        synchronized(vaultLock) {
            inMemoryPlates?.let { return it }
            val p1File = File(vaultDir(app), P1_PLATES_FILE)
            val json = unsealJsonFromFile(app, p1File) ?: return emptyList()
            val dto = runCatching { gson.fromJson(json, PlatesSyncResponseDto::class.java) }.getOrNull()
            val loaded = dto?.plates.orEmpty()
            inMemoryPlates = loaded
            return loaded
        }
    }

    internal fun getHydratedDirectory(context: Context): HydratedDirectoryData? {
        val app = context.applicationContext ?: context
        ensureScreenOffReceiverRegistered(app)
        inMemoryHydratedDir?.let { return it }
        synchronized(vaultLock) {
            inMemoryHydratedDir?.let { return it }
            val dir = vaultDir(app)
            val p2File = File(dir, P2_SECTOR_DIR_FILE)
            val p3File = File(dir, P3_EXT_DIR_FILE)
            val hasP2 = p2File.exists() && p2File.length() > 29L
            val hasP3 = p3File.exists() && p3File.length() > 29L
            if (!hasP2 && !hasP3 && inMemoryP2 == null && inMemoryP3 == null) {
                return null
            }

            val p2 = inMemoryP2 ?: if (hasP2) {
                val j2 = unsealJsonFromFile(app, p2File) ?: return null
                runCatching { gson.fromJson(j2, DirectoryTierSyncDto::class.java) }.getOrNull()?.also {
                    inMemoryP2 = it
                }
            } else {
                null
            }

            val p3 = inMemoryP3 ?: if (hasP3) {
                val j3 = unsealJsonFromFile(app, p3File) ?: return null
                runCatching { gson.fromJson(j3, DirectoryTierSyncDto::class.java) }.getOrNull()?.also {
                    inMemoryP3 = it
                }
            } else {
                null
            }

            if (p2 == null && p3 == null) return null
            val hydrated = buildHydratedDirectory(p2, p3)
            inMemoryHydratedDir = hydrated
            return hydrated
        }
    }

    private fun buildHydratedDirectory(
        p2: DirectoryTierSyncDto?,
        p3: DirectoryTierSyncDto?
    ): HydratedDirectoryData {
        val locList = ArrayList<KokohoDirectory.FacilityLocation>()
        for (dto in p2?.locations.orEmpty() + p3?.locations.orEmpty()) {
            if (dto.locationId.isBlank() || dto.title.isBlank()) continue
            locList += KokohoDirectory.FacilityLocation(
                id = KokohoDirectory.canonicalLocationId(dto.locationId),
                title = dto.title.trim(),
                agencyCode = dto.agencyCode.trim().ifEmpty { "DHS_INTEROP" },
                lat = dto.lat,
                lon = dto.lon,
                address = dto.address.trim(),
                description = dto.description.trim(),
                locationGroup = dto.locationGroup.trim().ifEmpty { "FEDERAL" },
                stateCode = dto.stateCode.trim().ifEmpty { "AZ" }
            )
        }

        val personsById = LinkedHashMap<String, KokohoDirectory.PersonRecord>()
        val personsByNameLower = HashMap<String, KokohoDirectory.PersonRecord>()
        for (dto in p2?.persons.orEmpty() + p3?.persons.orEmpty()) {
            val cleanId = KokohoDirectory.canonicalPersonId(dto.personId)
            val cleanName = dto.fullName.trim()
            if (cleanId.isEmpty() || cleanName.isEmpty()) continue
            val canonLoc = if (dto.locationId.isNotBlank()) {
                KokohoDirectory.canonicalLocationId(dto.locationId)
            } else {
                ""
            }
            val rec = KokohoDirectory.PersonRecord(
                personId = cleanId,
                fullName = cleanName,
                title = dto.title.trim(),
                agencyCode = dto.agencyCode.trim().ifEmpty { "DHS_INTEROP" },
                agencyDisplay = dto.agencyDisplay.trim(),
                locationId = canonLoc,
                streetAddress = dto.streetAddress.trim(),
                suite = dto.suite.trim(),
                stateCode = dto.stateCode.trim().ifEmpty { "AZ" },
                email = dto.email.trim(),
                secondEmail = dto.secondEmail.trim(),
                primaryPhone = dto.primaryPhone.trim(),
                mobilePhone = dto.mobilePhone.trim(),
                badgeNumber = dto.badgeNumber.trim(),
                managedByNameOrId = dto.managedBy.trim()
            )
            personsById[cleanId] = rec
            personsByNameLower.putIfAbsent(cleanName.lowercase(Locale.US), rec)
        }

        val orderedPersons = personsById.values.sortedWith(
            compareBy<KokohoDirectory.PersonRecord> { if (it.stateCode.equals("AZ", ignoreCase = true)) 0 else 1 }
                .thenBy { it.fullName.lowercase(Locale.US) }
        )

        val locBuckets = HashMap<String, MutableList<KokohoDirectory.PersonRecord>>()
        for (p in orderedPersons) {
            if (p.locationId.isNotBlank()) {
                locBuckets.getOrPut(p.locationId) { ArrayList() }.add(p)
            }
        }
        for (list in locBuckets.values) {
            list.sortBy { it.fullName.lowercase(Locale.US) }
        }

        val reportToManagerId = LinkedHashMap<String, String>()
        for (edge in p2?.edges.orEmpty() + p3?.edges.orEmpty()) {
            val repId = KokohoDirectory.canonicalPersonId(edge.personId)
            val mgrId = KokohoDirectory.canonicalPersonId(edge.managerId)
            if (repId.isNotEmpty() && mgrId.isNotEmpty() && repId != mgrId) {
                reportToManagerId[repId] = mgrId
            }
        }
        for (p in orderedPersons) {
            if (p.managedByNameOrId.isNotBlank() && p.personId !in reportToManagerId) {
                val candId = KokohoDirectory.canonicalPersonId(p.managedByNameOrId)
                val resolved = personsById[candId]
                    ?: personsByNameLower[p.managedByNameOrId.lowercase(Locale.US)]
                if (resolved != null && resolved.personId != p.personId) {
                    reportToManagerId[p.personId] = resolved.personId
                }
            }
        }

        val managerByReport = HashMap<String, KokohoDirectory.PersonRecord>(reportToManagerId.size)
        val reportsBuckets = HashMap<String, MutableList<KokohoDirectory.PersonRecord>>()
        for ((repId, mgrId) in reportToManagerId) {
            val mgr = personsById[mgrId] ?: continue
            val rep = personsById[repId] ?: continue
            managerByReport[repId] = mgr
            reportsBuckets.getOrPut(mgrId) { ArrayList() }.add(rep)
        }
        for (list in reportsBuckets.values) {
            list.sortBy { it.fullName.lowercase(Locale.US) }
        }

        return HydratedDirectoryData(
            locations = locList,
            orderedPersons = orderedPersons,
            personsById = personsById,
            personsByLocation = locBuckets,
            managerByReportId = managerByReport,
            reportsByManagerId = reportsBuckets
        )
    }

    private fun formatIfNoneMatch(rawEtag: String): String {
        val clean = rawEtag.trim().trim('"')
        return "\"$clean\""
    }

    private fun plateMergeKey(p: KokohoHubClient.SyncedPlateRecord): String =
        p.plateId.trim().ifEmpty { "${p.plateNumber.trim().uppercase(Locale.US)}:${p.stateCode.trim().uppercase(Locale.US)}" }

    fun syncAllAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        ensureScreenOffReceiverRegistered(app)
        scope.launch {
            val ok = syncAllBlocking(app)
            if (onComplete != null) {
                withContext(Dispatchers.Main) { onComplete(ok) }
            }
        }
    }

    fun syncP1PlatesAsync(
        context: Context,
        onComplete: ((List<KokohoHubClient.SyncedPlateRecord>) -> Unit)? = null
    ) {
        val app = context.applicationContext ?: context
        ensureScreenOffReceiverRegistered(app)
        scope.launch {
            syncP1PlatesBlocking(app)
            val plates = getPlates(app)
            if (onComplete != null) {
                withContext(Dispatchers.Main) { onComplete(plates) }
            }
        }
    }

    fun syncP1PlatesBlocking(context: Context): Boolean = synchronized(syncRunLock) {
        val app = context.applicationContext ?: context
        ensureScreenOffReceiverRegistered(app)
        if (!DeviceTrustManager.isEnrolled(app)) return false
        if (isDeviceCurrentlyLocked(app)) {
            syncDeferredWhileLocked = true
            return false
        }
        return syncP1Internal(app)
    }

    /**
     * Executes strict priority-ordered synchronization:
     *  1. `p1_plates` (`SNAPSHOT` or `DELTA`)
     *  2. `p2_sector_dir` (`If-None-Match` -> `304` or `200`)
     *  3. `p3_ext_dir` (`If-None-Match` -> `304` or `200`)
     */
    fun syncAllBlocking(context: Context): Boolean = synchronized(syncRunLock) {
        val app = context.applicationContext ?: context
        ensureScreenOffReceiverRegistered(app)
        if (!DeviceTrustManager.isEnrolled(app)) return false
        if (isDeviceCurrentlyLocked(app)) {
            syncDeferredWhileLocked = true
            return false
        }

        val ok1 = syncP1Internal(app)
        if (!DeviceTrustManager.isEnrolled(app)) return false
        val ok2 = syncTierInternal(app, tierName = "p2_sector_dir", fileName = P2_SECTOR_DIR_FILE, isP2 = true)
        if (!DeviceTrustManager.isEnrolled(app)) return false
        val ok3 = syncTierInternal(app, tierName = "p3_ext_dir", fileName = P3_EXT_DIR_FILE, isP2 = false)

        if (ok1 && ok2 && ok3 && !isDeviceCurrentlyLocked(app)) {
            syncDeferredWhileLocked = false
        }
        DeviceTrustManager.notifyTrustStateListeners(app)
        return ok1 || ok2 || ok3
    }

    private fun syncP1Internal(app: Context): Boolean {
        var meta = readVaultMeta(app)
        val p1File = File(vaultDir(app), P1_PLATES_FILE)
        val effectiveSince = if (p1File.exists() && p1File.length() > 29L) meta.p1SinceMs.coerceAtLeast(0L) else 0L

        val resp = DeviceTrustManager.executeEnvelopedGet(
            app,
            "/v1/sync/p1-plates?since_ms=$effectiveSince"
        ) ?: return false
        if (resp.code !in 200..299 || resp.body.isNullOrBlank()) return false

        val dto = runCatching { gson.fromJson(resp.body, PlatesSyncResponseDto::class.java) }.getOrNull()
            ?: return false

        val nextServerTime = dto.serverTimeMs.takeIf { it > 0L } ?: meta.p1SinceMs

        if (dto.mode.equals("SNAPSHOT", ignoreCase = true) || effectiveSince <= 0L) {
            val toSeal = PlatesSyncResponseDto(
                mode = "SNAPSHOT",
                serverTimeMs = nextServerTime,
                plates = dto.plates
            )
            val outcome = sealJsonToFile(app, p1File, gson.toJson(toSeal))
            if (outcome != SealOutcome.SEALED) return false
            synchronized(vaultLock) {
                inMemoryPlates = dto.plates
            }
            meta = readVaultMeta(app).copy(p1SinceMs = nextServerTime)
            writeVaultMeta(app, meta)
            revisionCounter.incrementAndGet()
            return true
        }

        // DELTA mode
        if (dto.plates.isEmpty()) {
            if (nextServerTime > meta.p1SinceMs) {
                meta = readVaultMeta(app).copy(p1SinceMs = nextServerTime)
                writeVaultMeta(app, meta)
            }
            return true
        }

        val existing: List<KokohoHubClient.SyncedPlateRecord> = synchronized(vaultLock) {
            inMemoryPlates ?: run {
                val raw = unsealJsonFromFile(app, p1File) ?: return@synchronized null
                runCatching { gson.fromJson(raw, PlatesSyncResponseDto::class.java)?.plates }.getOrNull()
            }
        } ?: run {
            // If device is locked, defer without advancing p1SinceMs; if corrupt while unlocked, refetch snapshot!
            if (syncDeferredWhileLocked || isDeviceCurrentlyLocked(app)) {
                syncDeferredWhileLocked = true
                return false
            }
            writeVaultMeta(app, meta.copy(p1SinceMs = 0L))
            runCatching { p1File.delete() }
            return syncP1Internal(app)
        }

        val mergedMap = LinkedHashMap<String, KokohoHubClient.SyncedPlateRecord>(existing.size + dto.plates.size)
        for (p in existing) {
            mergedMap[plateMergeKey(p)] = p
        }
        for (deltaPlate in dto.plates) {
            mergedMap[plateMergeKey(deltaPlate)] = deltaPlate
        }
        val mergedList = mergedMap.values.toList()
        val toSeal = PlatesSyncResponseDto(
            mode = "SNAPSHOT",
            serverTimeMs = nextServerTime,
            plates = mergedList
        )
        val outcome = sealJsonToFile(app, p1File, gson.toJson(toSeal))
        if (outcome != SealOutcome.SEALED) return false
        synchronized(vaultLock) {
            inMemoryPlates = mergedList
        }
        meta = readVaultMeta(app).copy(p1SinceMs = nextServerTime)
        writeVaultMeta(app, meta)
        revisionCounter.incrementAndGet()
        return true
    }

    private fun syncTierInternal(
        app: Context,
        tierName: String,
        fileName: String,
        isP2: Boolean
    ): Boolean {
        val meta = readVaultMeta(app)
        val tierFile = File(vaultDir(app), fileName)
        val prevEtag = if (isP2) meta.p2Etag else meta.p3Etag
        val extraHeaders = if (tierFile.exists() && tierFile.length() > 29L && prevEtag.isNotBlank()) {
            mapOf("If-None-Match" to formatIfNoneMatch(prevEtag))
        } else {
            emptyMap()
        }

        val resp = DeviceTrustManager.executeEnvelopedGet(
            app,
            "/v1/sync/tier/$tierName",
            extraHeaders = extraHeaders
        ) ?: return false

        if (resp.code == 304) {
            return true
        }
        if (resp.code !in 200..299 || resp.body.isNullOrBlank()) {
            return false
        }

        val dto = runCatching { gson.fromJson(resp.body, DirectoryTierSyncDto::class.java) }.getOrNull()
            ?: return false
        val canonicalJson = gson.toJson(dto)
        val outcome = sealJsonToFile(app, tierFile, canonicalJson)
        if (outcome != SealOutcome.SEALED) return false

        val resolvedEtag = dto.etag.ifBlank { resp.etag.orEmpty() }.trim().trim('"')
        val updatedMeta = readVaultMeta(app).let { cur ->
            if (isP2) cur.copy(p2Etag = resolvedEtag) else cur.copy(p3Etag = resolvedEtag)
        }
        writeVaultMeta(app, updatedMeta)

        synchronized(vaultLock) {
            if (isP2) {
                inMemoryP2 = dto
            } else {
                inMemoryP3 = dto
            }
            inMemoryHydratedDir = null
        }
        revisionCounter.incrementAndGet()
        KokohoDirectory.onVaultTierUpdated(app)
        return true
    }

    /**
     * Clears decrypted in-RAM caches (invoked automatically on `ACTION_SCREEN_OFF` and revocation).
     * Encrypted `.seal` files on disk remain untouched when screen turns off.
     */
    fun clearInMemoryCaches(context: Context? = null) {
        synchronized(vaultLock) {
            inMemoryPlates = null
            inMemoryP2 = null
            inMemoryP3 = null
            inMemoryHydratedDir = null
        }
        revisionCounter.incrementAndGet()
        KokohoHubClient.clearInMemoryPlatesCache()
        KokohoDirectory.clearInMemoryVaultCache()
    }

    /**
     * Atomically destroys the hardware `kokoho_vault_aes_v1` key in `AndroidKeyStore`,
     * deletes all `.seal` & `vault_meta.json` files in `noBackupFilesDir/vault/`,
     * and zeroes all in-RAM caches.
     */
    fun destroyVaultKeyAndFiles(context: Context) {
        val app = context.applicationContext ?: context
        synchronized(vaultLock) {
            inMemoryPlates = null
            inMemoryP2 = null
            inMemoryP3 = null
            inMemoryHydratedDir = null
            syncDeferredWhileLocked = false
            robolectricTestKeyBytes?.fill(0)
            robolectricTestKeyBytes = null

            runCatching {
                if (Security.getProvider(KEYSTORE_PROVIDER) != null) {
                    val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                    if (ks.containsAlias(VAULT_KEY_ALIAS)) {
                        ks.deleteEntry(VAULT_KEY_ALIAS)
                    }
                }
            }

            val vDir = vaultDir(app)
            if (vDir.exists()) {
                vDir.listFiles()?.forEach { f -> runCatching { f.delete() } }
                runCatching { vDir.delete() }
            }
        }
        revisionCounter.incrementAndGet()
    }

    /** Test-only helper for seeding sealed tier payloads in Robolectric unit tests. */
    internal fun sealTierForTest(context: Context, fileName: String, json: String): Boolean {
        val target = File(vaultDir(context), fileName)
        val ok = sealJsonToFile(context, target, json) == SealOutcome.SEALED
        if (ok) {
            synchronized(vaultLock) {
                inMemoryPlates = null
                inMemoryP2 = null
                inMemoryP3 = null
                inMemoryHydratedDir = null
            }
            revisionCounter.incrementAndGet()
        }
        return ok
    }
}
