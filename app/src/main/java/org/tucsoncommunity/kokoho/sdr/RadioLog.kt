package org.tucsoncommunity.kokoho.sdr

/**
 * The RTL-SDR transmissions heard recently, one entry per frequency, for the main list: last
 * signal strength, strongest, and whether the transmitter is getting closer or farther. A
 * trend is called when the signal moved by at least the step set in Settings (in dB) since the
 * last call - signal strength only, so it's a rough guide (antenna, terrain and power all count).
 */
object RadioLog {

    enum class Trend { NEW, CLOSER, FARTHER, STEADY }

    data class Entry(
        val freqHz: Long,
        val bandLabel: String,
        val kind: RadioWatch.Kind,
        val firstSeen: Long,
        val lastSeen: Long,
        val snrDb: Int,
        val peakSnrDb: Int,
        val confidence: Int,
        /** From the imported frequency list or RadioReference, when one matched. */
        val name: String?,
        val trend: Trend,
        /** The signal when the trend was last called: the next call compares to it. */
        val refSnrDb: Int,
        val minSnrDb: Int = snrDb,
        val hitCount: Int = 1,
        val firstLat: Double? = null,
        val firstLon: Double? = null,
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val peakLat: Double? = null,
        val peakLon: Double? = null,
        val travelSpanM: Double = 0.0,
        /** Digital mode & cipher label decoded on this channel (e.g. "digital radio (DMR) · clear", "MDC-1200 unit 4102"). */
        val digital: String? = null
    ) {
        val mhz: String get() = "%.4f".format(java.util.Locale.US, freqHz / 1e6)
    }

    /** A gap this long starts a new episode (a fresh "new", no trend from last time). */
    private const val EPISODE_GAP_MS = 90_000L
    /** Entries leave the list this long after they were last heard. */
    const val KEEP_MS = 5 * 60_000L

    private val entries = LinkedHashMap<Long, Entry>()
    private val repeaterBursts = ArrayDeque<Long>()

    /** Records a regional repeater downlink burst for non-spatial Sector Tempo tracking. */
    @Synchronized
    fun recordRepeaterBurst(now: Long = System.currentTimeMillis()) {
        repeaterBursts.addLast(now)
        pruneRepeaterBursts(now)
    }

    /** Count of repeater downlink bursts observed in the last 5 minutes. */
    @Synchronized
    fun repeaterBurstCount(now: Long = System.currentTimeMillis()): Int {
        pruneRepeaterBursts(now)
        return repeaterBursts.size
    }

    /** Count of concurrently active DoD 406.1-411.0 MHz UHF trunk base channels in the last 90 seconds. */
    @Synchronized
    fun activeDodTrunkChannelCount(now: Long = System.currentTimeMillis()): Int {
        prune(now)
        return entries.values.count {
            it.freqHz in 406_100_000L..411_000_000L && now - it.lastSeen <= EPISODE_GAP_MS
        }
    }

    /** Human-readable non-spatial Sector Tempo summary ("Elevated" / "High Tempo" / DoD UHF Trunk) when repeater or base trunk activity is brisk. */
    @Synchronized
    fun sectorTempoLabel(now: Long = System.currentTimeMillis()): String? {
        val count = repeaterBurstCount(now)
        val dodChannels = activeDodTrunkChannelCount(now)
        val parts = mutableListOf<String>()
        when {
            count >= 12 -> parts += "High ($count bursts/5m)"
            count >= 4 -> parts += "Elevated ($count bursts/5m)"
        }
        if (dodChannels >= 3) {
            parts += "DoD UHF Trunk ($dodChannels ch)"
        }
        return if (parts.isEmpty()) null else "Sector Tempo: ${parts.joinToString(" · ")}"
    }

    private fun pruneRepeaterBursts(now: Long) {
        while (repeaterBursts.isNotEmpty() && now - repeaterBursts.first() > KEEP_MS) {
            repeaterBursts.removeFirst()
        }
    }

    /** Records a reading; returns CLOSER / FARTHER when that should be called out, else null. */
    @Synchronized
    fun observe(
        e: RadioWatch.Event,
        name: String?,
        stepDb: Int,
        now: Long,
        lat: Double? = null,
        lon: Double? = null
    ): Trend? {
        val prev = entries[e.freqHz]
        if (prev == null || now - prev.lastSeen > EPISODE_GAP_MS) {
            entries[e.freqHz] = Entry(
                freqHz = e.freqHz,
                bandLabel = e.band.label,
                kind = e.band.kind,
                firstSeen = now,
                lastSeen = now,
                snrDb = e.snrDb,
                peakSnrDb = e.snrDb,
                confidence = e.confidence,
                name = name,
                trend = Trend.NEW,
                refSnrDb = e.snrDb,
                minSnrDb = e.snrDb,
                hitCount = 1,
                firstLat = lat,
                firstLon = lon,
                lastLat = lat,
                lastLon = lon,
                peakLat = lat,
                peakLon = lon,
                travelSpanM = 0.0
            )
            prune(now)
            return null
        }
        val delta = e.snrDb - prev.refSnrDb
        val trend = when {
            stepDb > 0 && delta >= stepDb -> Trend.CLOSER
            stepDb > 0 && delta <= -stepDb -> Trend.FARTHER
            else -> null
        }
        val newFirstLat = prev.firstLat ?: lat
        val newFirstLon = prev.firstLon ?: lon
        val isNewPeak = e.snrDb >= prev.peakSnrDb
        val newPeakLat = if (isNewPeak && lat != null) lat else (prev.peakLat ?: lat)
        val newPeakLon = if (isNewPeak && lon != null) lon else (prev.peakLon ?: lon)
        val spanFromOrigin = if (newFirstLat != null && newFirstLon != null && lat != null && lon != null) {
            org.tucsoncommunity.kokoho.service.DeviceRegistry.metersBetween(newFirstLat, newFirstLon, lat, lon)
        } else {
            prev.travelSpanM
        }
        entries[e.freqHz] = prev.copy(
            lastSeen = now,
            snrDb = e.snrDb,
            peakSnrDb = maxOf(prev.peakSnrDb, e.snrDb),
            confidence = maxOf(prev.confidence, e.confidence),
            name = name ?: prev.name,
            trend = trend ?: (if (prev.trend == Trend.NEW) Trend.STEADY else prev.trend),
            refSnrDb = if (trend != null) e.snrDb else prev.refSnrDb,
            minSnrDb = minOf(prev.minSnrDb, e.snrDb),
            hitCount = prev.hitCount + 1,
            firstLat = newFirstLat,
            firstLon = newFirstLon,
            lastLat = lat ?: prev.lastLat,
            lastLon = lon ?: prev.lastLon,
            peakLat = newPeakLat,
            peakLon = newPeakLon,
            travelSpanM = maxOf(prev.travelSpanM, spanFromOrigin)
        )
        return trend
    }

    /** Records a decoded digital voice/signaling note (DMR, NXDN, MDC-1200) on [freqHz]. */
    @Synchronized
    fun digital(freqHz: Long, note: String) {
        val prev = entries[freqHz] ?: return
        entries[freqHz] = prev.copy(digital = note)
    }

    /** Heard within [KEEP_MS], most recent first. */
    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): List<Entry> {
        prune(now)
        return entries.values.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        repeaterBursts.clear()
    }

    private fun prune(now: Long) {
        entries.values.removeAll { now - it.lastSeen > KEEP_MS }
    }
}
