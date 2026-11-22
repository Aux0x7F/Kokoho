package org.tucsoncommunity.kokoho.posture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client-resolved Tucson GeoIP VPN gate for Seated Kokoho installs (`v1.3.14`).
 *
 *  - Verifies that seated devices (holding a seat identity or actively claiming/authorizing a seat)
 *    are not routing directly from a Tucson metropolitan IP address before opening outbound
 *    connections to Kokoho Hub.
 *  - Requires an active VPN routed to an exit outside
 *    Southern Arizona.
 *  - Listens to [ConnectivityManager.NetworkCallback] (`TRANSPORT_VPN` and active network changes)
 *    to invalidate cached verdicts immediately and auto-resume Hub sync when the user connects
 *    their VPN.
 */
object VpnGeoGate {

    enum class Verdict {
        ALLOWED,
        BLOCKED_TUCSON,
        UNSEATED
    }

    data class GeoSample(
        val country: String = "",
        val region: String = "",
        val city: String = "",
        val lat: Double = Double.NaN,
        val lon: Double = Double.NaN
    )

    const val STATUS_VPN_REQUIRED_TUCSON = "VPN_REQUIRED_TUCSON"

    const val TUCSON_VPN_ADVISORY_MESSAGE =
        "Tucson network detected without an active VPN. Hub sync, enrollment, and sharing are paused. " +
            "Connect your VPN to an exit outside Southern Arizona, then tap Re-Check VPN."

    private const val ALLOWED_TTL_MS = 90_000L
    private const val BLOCKED_TTL_MS = 20_000L
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 3_500

    private val TUCSON_CITY_REGEX = Regex(
        "\\b(tucson|south tucson|oro valley|marana|sahuarita|vail|catalina|cortaro|" +
            "green valley|rita ranch|flowing wells|casas adobes|tanque verde|" +
            "drexel heights|picture rocks|three points|corona de tucson)\\b",
        RegexOption.IGNORE_CASE
    )

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val cacheLock = Any()
    private val callbackRegistered = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var cachedVerdict: Verdict? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var lastObservedBlockedTucson: Boolean = false

    @Volatile
    private var lastVpnTransportActive: Boolean? = null

    /** Optional deterministic override for JVM / Robolectric unit tests. */
    @Volatile
    internal var testOverrideVerdict: Verdict? = null

    fun isBlockedTucson(): Boolean = lastObservedBlockedTucson

    fun addListener(listener: (blockedTucson: Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun updateBlockedFlagAndNotify(blocked: Boolean) {
        val changed = lastObservedBlockedTucson != blocked
        lastObservedBlockedTucson = blocked
        if (!changed && listeners.isEmpty()) return
        if (changed) {
            val dispatch = Runnable {
                for (l in listeners) {
                    runCatching { l(blocked) }
                }
            }
            val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post(dispatch)
            } else {
                dispatch.run()
            }
            DeviceTrustManager.notifyTrustStateListeners()
        }
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedVerdict = null
            cachedAtMs = 0L
        }
    }

    internal fun resetForTest() {
        scopeJob.cancelChildren()
        testOverrideVerdict = null
        invalidateCache()
        lastObservedBlockedTucson = false
        lastVpnTransportActive = null
    }

    fun isTucsonGeo(sample: GeoSample?): Boolean {
        if (sample == null) return false
        val lat = sample.lat
        val lon = sample.lon
        if (lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)) {
            if (lat in 31.80..32.60 && lon in -111.40..-110.50) {
                return true
            }
        }
        val country = sample.country.trim().uppercase()
        val region = sample.region.trim().uppercase()
        val city = sample.city.trim()
        val isUs = country.isEmpty() || country == "US" || country == "USA" || country == "UNITED STATES"
        val isAz = region.isEmpty() || region == "AZ" || region == "ARIZONA" || region == "US-AZ"
        return isUs && isAz && city.isNotEmpty() && TUCSON_CITY_REGEX.containsMatchIn(city)
    }

    internal fun isLoopbackHubUrl(hubUrl: String): Boolean {
        val host = runCatching { URI(hubUrl.trim()).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }

    fun ensureNetworkCallbackRegistered(context: Context) {
        val app = context.applicationContext ?: context
        if (!callbackRegistered.compareAndSet(false, true)) return
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onTopologyMaybeChanged(app, cm, network)
                }

                override fun onLost(network: Network) {
                    invalidateCache()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val prevVpn = lastVpnTransportActive
                    if (prevVpn == null || prevVpn != hasVpn) {
                        lastVpnTransportActive = hasVpn
                        onTopologyMaybeChanged(app, cm, network)
                    }
                }
            })
        }
    }

    private fun onTopologyMaybeChanged(app: Context, cm: ConnectivityManager, network: Network) {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        if (caps != null) {
            lastVpnTransportActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val wasBlocked = lastObservedBlockedTucson
        invalidateCache()
        if (wasBlocked || DeviceTrustManager.loadOrExtractSeatIdentity(app) != null) {
            scope.launch {
                val verdict = checkHubEgressAllowedBlocking(app, forceSeatedCheck = wasBlocked, bypassCache = true)
                if (wasBlocked && verdict == Verdict.ALLOWED) {
                    DeviceTrustManager.ensureEnrolledAsync(app)
                    if (KokohoSignatures.isAuxnetEnabled(app)) {
                        KokohoHubClient.checkinNow(app)
                    }
                }
            }
        }
    }

    /**
     * Checks whether outbound Hub communication is permitted for this client.
     *
     * @param forceSeatedCheck true when claiming an invitation or granting a web session before
     *                         `seat_identity.json` has been persisted.
     * @param bypassCache true when user explicitly taps Re-Check VPN or network topology changed.
     */
    fun checkHubEgressAllowedBlocking(
        context: Context,
        forceSeatedCheck: Boolean = false,
        bypassCache: Boolean = false
    ): Verdict {
        val app = context.applicationContext ?: context
        ensureNetworkCallbackRegistered(app)

        val isSeated = forceSeatedCheck || DeviceTrustManager.loadOrExtractSeatIdentity(app) != null
        if (!isSeated) {
            updateBlockedFlagAndNotify(false)
            return Verdict.UNSEATED
        }

        testOverrideVerdict?.let { override ->
            updateBlockedFlagAndNotify(override == Verdict.BLOCKED_TUCSON)
            return override
        }

        val hubBase = KokohoHubClient.hubUrl(app)
        if (isLoopbackHubUrl(hubBase)) {
            updateBlockedFlagAndNotify(false)
            return Verdict.ALLOWED
        }

        val now = System.currentTimeMillis()
        if (!bypassCache) {
            synchronized(cacheLock) {
                val cur = cachedVerdict
                if (cur != null) {
                    val ttl = if (cur == Verdict.BLOCKED_TUCSON) BLOCKED_TTL_MS else ALLOWED_TTL_MS
                    if (now - cachedAtMs in 0..ttl) {
                        updateBlockedFlagAndNotify(cur == Verdict.BLOCKED_TUCSON)
                        return cur
                    }
                }
            }
        }

        val sample = resolveClientGeoBlocking()
        val resolvedVerdict = if (sample != null && isTucsonGeo(sample)) {
            Verdict.BLOCKED_TUCSON
        } else {
            Verdict.ALLOWED
        }

        synchronized(cacheLock) {
            cachedVerdict = resolvedVerdict
            cachedAtMs = System.currentTimeMillis()
        }
        updateBlockedFlagAndNotify(resolvedVerdict == Verdict.BLOCKED_TUCSON)
        return resolvedVerdict
    }

    fun recheckAsync(
        context: Context,
        forceSeatedCheck: Boolean = true,
        onResult: ((Verdict) -> Unit)? = null
    ) {
        val app = context.applicationContext ?: context
        scope.launch {
            val verdict = checkHubEgressAllowedBlocking(
                context = app,
                forceSeatedCheck = forceSeatedCheck,
                bypassCache = true
            )
            if (verdict == Verdict.ALLOWED && DeviceTrustManager.loadOrExtractSeatIdentity(app) != null) {
                DeviceTrustManager.ensureEnrolledAsync(app)
                if (KokohoSignatures.isAuxnetEnabled(app)) {
                    KokohoHubClient.checkinNow(app)
                }
            }
            if (onResult != null) {
                withContext(Dispatchers.Main) {
                    onResult(verdict)
                }
            }
        }
    }

    private fun resolveClientGeoBlocking(): GeoSample? {
        val providers: List<() -> GeoSample?> = listOf(
            {
                val raw = fetchGeoJson("https://get.geojs.io/v1/ip/geo.json") ?: return@listOf null
                val j = JSONObject(raw)
                GeoSample(
                    country = j.optString("country_code").ifBlank { j.optString("country") },
                    region = j.optString("region"),
                    city = j.optString("city"),
                    lat = j.optString("latitude").toDoubleOrNull() ?: Double.NaN,
                    lon = j.optString("longitude").toDoubleOrNull() ?: Double.NaN
                )
            },
            {
                val raw = fetchGeoJson("https://ipwho.is/") ?: return@listOf null
                val j = JSONObject(raw)
                if (j.has("success") && !j.optBoolean("success", true)) return@listOf null
                GeoSample(
                    country = j.optString("country_code").ifBlank { j.optString("country") },
                    region = j.optString("region_code").ifBlank { j.optString("region") },
                    city = j.optString("city"),
                    lat = j.optDouble("latitude", Double.NaN),
                    lon = j.optDouble("longitude", Double.NaN)
                )
            },
            {
                val raw = fetchGeoJson("https://freeipapi.com/api/json") ?: return@listOf null
                val j = JSONObject(raw)
                GeoSample(
                    country = j.optString("countryCode").ifBlank { j.optString("countryName") },
                    region = j.optString("regionName"),
                    city = j.optString("cityName"),
                    lat = j.optDouble("latitude", Double.NaN),
                    lon = j.optDouble("longitude", Double.NaN)
                )
            }
        )

        for (provider in providers) {
            val sample = runCatching { provider() }.getOrNull()
            if (sample != null && (sample.city.isNotBlank() || sample.lat.isFinite())) {
                return sample
            }
        }
        return null
    }

    private fun fetchGeoJson(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
