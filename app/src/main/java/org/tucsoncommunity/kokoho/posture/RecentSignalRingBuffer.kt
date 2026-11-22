package org.tucsoncommunity.kokoho.posture

import android.content.Context
import com.google.gson.annotations.SerializedName
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Strictly volatile in-RAM 10-minute circular signal buffer (`MAX_ENTRIES = 2048`, zero disk writes).
 *
 * Powers:
 *  1. Silent 90-second `TIER_B` context flushes when `/v1/room/ingest` returns `"collect_investigative_context": true`.
 *  2. Contextual report attachments in `KokohoReportWizard`:
 *     - Default: `TIER_A + TIER_B` (including non-own-vehicle TPMS) from the last `<= 10 min`.
 *     - Opt-in checkbox only: adds `AMBIENT` Wi-Fi BSSIDs and BLE advertisements heard along the path.
 */
object RecentSignalRingBuffer {

    const val MAX_ENTRIES = 2048
    const val WINDOW_MS = 10 * 60_000L
    const val SILENT_HOTSPOT_FLUSH_WINDOW_MS = 90_000L

    data class Entry(
        val facetKey: String,
        val tier: KokohoSignatures.WatchlistTier,
        val modality: String,
        val signalDb: Double,
        val label: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val timestampMs: Long
    )

    data class NearbySignalDto(
        @SerializedName("facet_key") val facetKey: String = "",
        @SerializedName("tier") val tier: String = "TIER_B",
        @SerializedName("modality") val modality: String = "",
        @SerializedName("signal_db") val signalDb: Double = 0.0,
        @SerializedName("label") val label: String? = null,
        @SerializedName("lat") val lat: Double? = null,
        @SerializedName("lon") val lon: Double? = null,
        @SerializedName("timestamp_ms") val timestampMs: Long = 0L
    )

    private val lock = Any()
    private val ring = ArrayDeque<Entry>(MAX_ENTRIES)

    private fun pruneLocked(nowMs: Long) {
        val cutoffMs = nowMs - WINDOW_MS
        while (ring.isNotEmpty() && ring.first().timestampMs < cutoffMs) {
            ring.removeFirst()
        }
        while (ring.size > MAX_ENTRIES) {
            ring.removeFirst()
        }
    }

    fun record(
        context: Context?,
        facetKey: String,
        tier: KokohoSignatures.WatchlistTier,
        modality: String,
        signalDb: Double,
        label: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val cleanKey = facetKey.trim()
        if (cleanKey.isEmpty()) return
        val canonicalTpms = KokohoSignatures.canonicalTpmsFacetKey(cleanKey)
        if (canonicalTpms != null && KokohoSignatures.isOwnVehicleTpms(context, canonicalTpms)) {
            return
        }
        val storedKey = canonicalTpms ?: cleanKey
        val entry = Entry(
            facetKey = storedKey,
            tier = tier,
            modality = modality.trim().ifEmpty { "UNKNOWN" },
            signalDb = signalDb,
            label = label?.trim()?.takeIf { it.isNotEmpty() },
            lat = lat?.takeIf { it.isFinite() },
            lon = lon?.takeIf { it.isFinite() },
            timestampMs = timestampMs
        )
        synchronized(lock) {
            pruneLocked(timestampMs)
            val tail = ring.lastOrNull()
            if (tail != null && tail.facetKey == storedKey && abs(timestampMs - tail.timestampMs) < 150L) {
                ring.removeLast()
                ring.addLast(
                    if (signalDb >= tail.signalDb) entry else entry.copy(signalDb = tail.signalDb)
                )
                return
            }
            if (ring.size >= MAX_ENTRIES) {
                ring.removeFirst()
            }
            ring.addLast(entry)
        }
    }

    /**
     * Returns signals observed within `[nowMs - 10m, nowMs]`:
     *  - `includeAmbient = false` (default): `TIER_A` and `TIER_B` only (including non-own TPMS).
     *  - `includeAmbient = true`: `TIER_A`, `TIER_B`, and `AMBIENT`.
     */
    fun snapshotForReport(
        context: Context? = null,
        nowMs: Long = System.currentTimeMillis(),
        includeAmbient: Boolean = false
    ): List<Entry> = synchronized(lock) {
        pruneLocked(nowMs)
        val cutoffMs = nowMs - WINDOW_MS
        ring.filter { e ->
            e.timestampMs in cutoffMs..nowMs &&
                !KokohoSignatures.isOwnVehicleTpms(context, e.facetKey) &&
                (includeAmbient || e.tier != KokohoSignatures.WatchlistTier.AMBIENT)
        }
    }

    fun snapshotDtosForReport(
        context: Context? = null,
        nowMs: Long = System.currentTimeMillis(),
        includeAmbient: Boolean = false
    ): List<NearbySignalDto> {
        val raw = snapshotForReport(context = context, nowMs = nowMs, includeAmbient = includeAmbient)
        val byFacet = LinkedHashMap<String, Entry>()
        for (e in raw) {
            val prev = byFacet[e.facetKey]
            if (prev == null || e.signalDb >= prev.signalDb || e.timestampMs > prev.timestampMs) {
                byFacet[e.facetKey] = e
            }
        }
        return byFacet.values.map { e ->
            NearbySignalDto(
                facetKey = e.facetKey,
                tier = e.tier.name,
                modality = e.modality,
                signalDb = e.signalDb,
                label = e.label,
                lat = e.lat,
                lon = e.lon,
                timestampMs = e.timestampMs
            )
        }
    }

    /**
     * Returns `TIER_B` signals from the last [windowMs] (default 90 seconds) for silent hotspot corroboration.
     */
    fun snapshotRecentTierB(
        context: Context? = null,
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = SILENT_HOTSPOT_FLUSH_WINDOW_MS
    ): List<Entry> = synchronized(lock) {
        pruneLocked(nowMs)
        val cutoffMs = nowMs - windowMs
        ring.filter { e ->
            e.timestampMs in cutoffMs..nowMs &&
                e.tier == KokohoSignatures.WatchlistTier.TIER_B &&
                !KokohoSignatures.isOwnVehicleTpms(context, e.facetKey)
        }
    }

    /** Computes `X` in `1..10` minutes for `"Share all nearby signals from the last X min"`. */
    fun observedSpanMinutes(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneLocked(nowMs)
        val oldest = ring.firstOrNull() ?: return 10
        val spanMs = (nowMs - oldest.timestampMs).coerceAtLeast(60_000L)
        ceil(spanMs / 60_000.0).toInt().coerceIn(1, 10)
    }

    fun size(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneLocked(nowMs)
        ring.size
    }

    fun clear() = synchronized(lock) {
        ring.clear()
    }
}
