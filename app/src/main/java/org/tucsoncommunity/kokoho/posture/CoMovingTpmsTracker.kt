package org.tucsoncommunity.kokoho.posture

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rolling co-moving RSSI & arrival/peak/departure correlator (`iss-1795334753017-f45d5c`) that pairs
 * ground/vehicle emitters (`(t, emitterSignalDb)`) against non-own-vehicle TPMS readings (`(t, tpmsRssiDb)`).
 *
 *  1. Narrows down the exact 4-tire vehicle chassis by scoring co-arrival (`|dt_first| <= 8s`),
 *     co-peak (`|dt_peak| <= 4s`), co-departure (`|dt_last| <= 8s`), and Pearson / slope concordance
 *     across `>= 2` RSSI samples.
 *  2. Disambiguates **Mobile Vehicle** vs **Fixed Infrastructure**:
 *     - `MOBILE_VEHICLE` (`"Mobile Vehicle (Co-Moving TPMS)"`): `>= 1` non-own TPMS sensor with `coMovingScore >= 0.70`.
 *     - `FIXED_INFRA` (`"Fixed / Pole Infrastructure"`): steady ground emitter across `>= 90s` (`>= 4` samples,
 *       RSSI variance `<= 5.0 dB^2` while observer is stationary, or stationary centroid while observer moves)
 *       with **zero** co-moving TPMS sensors.
 */
object CoMovingTpmsTracker {

    const val SERIES_WINDOW_MS = 180_000L
    const val MAX_SERIES_SAMPLES = 64
    const val CO_ARRIVAL_MAX_DT_MS = 8_000L
    const val CO_PEAK_MAX_DT_MS = 4_000L
    const val CO_DEPARTURE_MAX_DT_MS = 8_000L
    const val PAIR_INTERPOLATION_MAX_DT_MS = 8_000L
    const val MOBILE_CO_MOVING_SCORE_THRESHOLD = 0.70
    const val FIXED_INFRA_MIN_SPAN_MS = 90_000L
    const val FIXED_INFRA_MIN_SAMPLES = 4
    const val FIXED_INFRA_MAX_RSSI_VARIANCE_DB = 5.0

    enum class ChassisPosture(val code: String, val label: String) {
        UNKNOWN("UNKNOWN", ""),
        MOBILE_VEHICLE("MOBILE_VEHICLE", "Mobile Vehicle (Co-Moving TPMS)"),
        FIXED_INFRA("FIXED_INFRA", "Fixed / Pole Infrastructure")
    }

    data class EmitterSample(
        val timestampMs: Long,
        val signalDb: Double,
        val obsLat: Double? = null,
        val obsLon: Double? = null,
        val observerMoving: Boolean = false
    )

    data class TpmsSample(
        val timestampMs: Long,
        val rssiDb: Double
    )

    data class CoMovingMatch(
        val tpmsFacetKey: String,
        val coMovingScore: Double,
        val rssiCorrelation: Double,
        val dtFirstMs: Long,
        val dtPeakMs: Long,
        val dtLastMs: Long,
        val sampleCount: Int
    )

    data class EmitterDisambiguation(
        val emitterKey: String,
        val posture: ChassisPosture,
        val coMovingMatches: List<CoMovingMatch>,
        val spanMs: Long,
        val sampleCount: Int,
        val rssiVarianceDb: Double
    ) {
        val bestRssiCorrelation: Double?
            get() = coMovingMatches.maxOfOrNull { it.rssiCorrelation }

        fun toAdditionalFacets(): List<String> {
            return when (posture) {
                ChassisPosture.MOBILE_VEHICLE -> {
                    val out = ArrayList<String>(coMovingMatches.size + 2)
                    for (m in coMovingMatches.take(4)) {
                        out += m.tpmsFacetKey
                    }
                    val topCorr = coMovingMatches.maxOfOrNull { max(it.rssiCorrelation, it.coMovingScore) } ?: 0.75
                    out += String.format(Locale.US, "tpms:rssi_corr:%.3f", topCorr.coerceIn(-1.0, 1.0))
                    out += "tpms:disambig:MOBILE_VEHICLE"
                    out
                }
                ChassisPosture.FIXED_INFRA -> listOf("tpms:disambig:FIXED_INFRA")
                ChassisPosture.UNKNOWN -> emptyList()
            }
        }
    }

    private val emitterSeries = ConcurrentHashMap<String, MutableList<EmitterSample>>()
    private val tpmsSeries = ConcurrentHashMap<String, MutableList<TpmsSample>>()

    fun recordEmitterSample(
        emitterKey: String,
        timestampMs: Long,
        signalDb: Double,
        obsLat: Double? = null,
        obsLon: Double? = null,
        observerMoving: Boolean = KinematicPrivacyGate.state == KinematicPrivacyGate.State.MOVING
    ) {
        val key = emitterKey.trim()
        if (key.isEmpty() || !signalDb.isFinite()) return
        val list = emitterSeries.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { timestampMs - it.timestampMs > SERIES_WINDOW_MS }
            val last = list.lastOrNull()
            if (last != null && timestampMs <= last.timestampMs) {
                if (timestampMs == last.timestampMs && signalDb > last.signalDb) {
                    list[list.lastIndex] = EmitterSample(timestampMs, signalDb, obsLat ?: last.obsLat, obsLon ?: last.obsLon, observerMoving)
                }
                return
            }
            list.add(EmitterSample(timestampMs, signalDb, obsLat, obsLon, observerMoving))
            while (list.size > MAX_SERIES_SAMPLES) {
                list.removeAt(0)
            }
        }
    }

    fun recordTpmsSample(
        context: Context?,
        rawTpmsFacetKey: String,
        timestampMs: Long,
        rssiDb: Double
    ) {
        val canonKey = KokohoSignatures.canonicalTpmsFacetKey(rawTpmsFacetKey) ?: return
        if (KokohoSignatures.isOwnVehicleTpms(context, canonKey)) {
            tpmsSeries.remove(canonKey)
            return
        }
        if (!rssiDb.isFinite()) return
        val list = tpmsSeries.getOrPut(canonKey) { mutableListOf() }
        synchronized(list) {
            list.removeAll { timestampMs - it.timestampMs > SERIES_WINDOW_MS }
            val last = list.lastOrNull()
            if (last != null && timestampMs <= last.timestampMs) {
                if (timestampMs == last.timestampMs && rssiDb > last.rssiDb) {
                    list[list.lastIndex] = TpmsSample(timestampMs, rssiDb)
                }
                return
            }
            list.add(TpmsSample(timestampMs, rssiDb))
            while (list.size > MAX_SERIES_SAMPLES) {
                list.removeAt(0)
            }
        }
    }

    fun evaluateEmitter(
        context: Context?,
        emitterKey: String,
        nowMs: Long = System.currentTimeMillis(),
        stationaryCentroidLocked: Boolean = false
    ): EmitterDisambiguation {
        val eListRaw = emitterSeries[emitterKey.trim()]
        val eSamples = if (eListRaw != null) {
            synchronized(eListRaw) {
                eListRaw.removeAll { nowMs - it.timestampMs > SERIES_WINDOW_MS }
                eListRaw.toList()
            }
        } else emptyList()

        if (eSamples.isEmpty()) {
            return EmitterDisambiguation(
                emitterKey = emitterKey,
                posture = ChassisPosture.UNKNOWN,
                coMovingMatches = emptyList(),
                spanMs = 0L,
                sampleCount = 0,
                rssiVarianceDb = 0.0
            )
        }

        val spanMs = (eSamples.last().timestampMs - eSamples.first().timestampMs).coerceAtLeast(0L)
        val meanDb = eSamples.sumOf { it.signalDb } / eSamples.size.toDouble()
        val varianceDb = eSamples.sumOf { (it.signalDb - meanDb) * (it.signalDb - meanDb) } / eSamples.size.toDouble()
        val observerSpreadM = computeObserverSpreadMeters(eSamples)

        val candidateMatches = ArrayList<CoMovingMatch>()
        for ((tpmsKey, tListRaw) in tpmsSeries) {
            if (KokohoSignatures.isOwnVehicleTpms(context, tpmsKey)) {
                tpmsSeries.remove(tpmsKey)
                continue
            }
            val tSamples = synchronized(tListRaw) {
                tListRaw.removeAll { nowMs - it.timestampMs > SERIES_WINDOW_MS }
                tListRaw.toList()
            }
            if (tSamples.size < 2 || eSamples.size < 2) continue
            val match = scorePair(tpmsKey, eSamples, tSamples, observerSpreadM) ?: continue
            if (match.coMovingScore >= MOBILE_CO_MOVING_SCORE_THRESHOLD) {
                candidateMatches += match
            }
        }
        val sortedMatches = candidateMatches.sortedByDescending { it.coMovingScore }

        val posture = when {
            sortedMatches.isNotEmpty() -> ChassisPosture.MOBILE_VEHICLE
            eSamples.size >= FIXED_INFRA_MIN_SAMPLES && spanMs >= FIXED_INFRA_MIN_SPAN_MS -> {
                val stationaryObserverSteady = eSamples.count { !it.observerMoving } >= FIXED_INFRA_MIN_SAMPLES &&
                    varianceDb <= FIXED_INFRA_MAX_RSSI_VARIANCE_DB
                val movingObserverFixedCentroid = stationaryCentroidLocked ||
                    (observerSpreadM >= 45.0 && isDriveByFixedProfile(eSamples))
                if (stationaryObserverSteady || movingObserverFixedCentroid) {
                    ChassisPosture.FIXED_INFRA
                } else {
                    ChassisPosture.UNKNOWN
                }
            }
            else -> ChassisPosture.UNKNOWN
        }

        return EmitterDisambiguation(
            emitterKey = emitterKey,
            posture = posture,
            coMovingMatches = sortedMatches,
            spanMs = spanMs,
            sampleCount = eSamples.size,
            rssiVarianceDb = varianceDb
        )
    }

    private fun scorePair(
        tpmsKey: String,
        eSamples: List<EmitterSample>,
        tSamples: List<TpmsSample>,
        observerSpreadM: Double
    ): CoMovingMatch? {
        if (eSamples.size < 2 || tSamples.size < 2) return null

        val dtFirstMs = abs(eSamples.first().timestampMs - tSamples.first().timestampMs)
        val ePeak = eSamples.maxBy { it.signalDb }
        val tPeak = tSamples.maxBy { it.rssiDb }
        val dtPeakMs = abs(ePeak.timestampMs - tPeak.timestampMs)
        val dtLastMs = abs(eSamples.last().timestampMs - tSamples.last().timestampMs)

        val paired = pairTimeSeries(eSamples, tSamples)
        if (paired.size < 2) return null

        val rssiCorr = computeCorrelationAndSlopeConcordance(paired, observerSpreadM)

        val arrivalScore = when {
            dtFirstMs <= CO_ARRIVAL_MAX_DT_MS -> 1.0
            dtFirstMs >= 24_000L -> 0.0
            else -> (24_000.0 - dtFirstMs.toDouble()) / 16_000.0
        }
        val peakScore = when {
            dtPeakMs <= CO_PEAK_MAX_DT_MS -> 1.0
            dtPeakMs >= 16_000L -> 0.0
            else -> (16_000.0 - dtPeakMs.toDouble()) / 12_000.0
        }
        val departureScore = when {
            dtLastMs <= CO_DEPARTURE_MAX_DT_MS -> 1.0
            dtLastMs >= 24_000L -> 0.0
            else -> (24_000.0 - dtLastMs.toDouble()) / 16_000.0
        }

        if (rssiCorr < 0.35) {
            return CoMovingMatch(
                tpmsFacetKey = tpmsKey,
                coMovingScore = (0.20 * arrivalScore + 0.15 * max(0.0, rssiCorr)).coerceIn(0.0, 0.45),
                rssiCorrelation = rssiCorr,
                dtFirstMs = dtFirstMs,
                dtPeakMs = dtPeakMs,
                dtLastMs = dtLastMs,
                sampleCount = paired.size
            )
        }

        val combinedScore = (
            0.48 * rssiCorr.coerceIn(0.0, 1.0) +
                0.22 * arrivalScore +
                0.20 * peakScore +
                0.10 * departureScore
            ).coerceIn(0.0, 1.0)

        return CoMovingMatch(
            tpmsFacetKey = tpmsKey,
            coMovingScore = combinedScore,
            rssiCorrelation = rssiCorr,
            dtFirstMs = dtFirstMs,
            dtPeakMs = dtPeakMs,
            dtLastMs = dtLastMs,
            sampleCount = paired.size
        )
    }

    private fun pairTimeSeries(
        eSamples: List<EmitterSample>,
        tSamples: List<TpmsSample>
    ): List<Pair<Double, Double>> {
        val anchorTimes = ArrayList<Long>(eSamples.size + tSamples.size)
        for (e in eSamples) anchorTimes += e.timestampMs
        for (t in tSamples) anchorTimes += t.timestampMs
        anchorTimes.sort()

        val dedupedTimes = ArrayList<Long>(anchorTimes.size)
        for (ts in anchorTimes) {
            if (dedupedTimes.isEmpty() || ts - dedupedTimes.last() >= 400L) {
                dedupedTimes += ts
            }
        }

        val out = ArrayList<Pair<Double, Double>>(dedupedTimes.size)
        for (ts in dedupedTimes) {
            val eVal = interpolateAt(ts, eSamples.map { it.timestampMs to it.signalDb }) ?: continue
            val tVal = interpolateAt(ts, tSamples.map { it.timestampMs to it.rssiDb }) ?: continue
            out += eVal to tVal
        }
        return out
    }

    private fun interpolateAt(targetMs: Long, points: List<Pair<Long, Double>>): Double? {
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        if (targetMs < first.first) {
            return if (first.first - targetMs <= PAIR_INTERPOLATION_MAX_DT_MS) first.second else null
        }
        if (targetMs > last.first) {
            return if (targetMs - last.first <= PAIR_INTERPOLATION_MAX_DT_MS) last.second else null
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (targetMs in a.first..b.first) {
                val span = b.first - a.first
                if (span <= 0L) return a.second
                if (min(targetMs - a.first, b.first - targetMs) > PAIR_INTERPOLATION_MAX_DT_MS) return null
                val alpha = (targetMs - a.first).toDouble() / span.toDouble()
                return a.second + (b.second - a.second) * alpha
            }
        }
        return last.second
    }

    private fun computeCorrelationAndSlopeConcordance(
        paired: List<Pair<Double, Double>>,
        observerSpreadM: Double
    ): Double {
        val n = paired.size
        if (n < 2) return 0.0

        val meanE = paired.sumOf { it.first } / n.toDouble()
        val meanT = paired.sumOf { it.second } / n.toDouble()
        var cov = 0.0
        var varE = 0.0
        var varT = 0.0
        for ((e, t) in paired) {
            val de = e - meanE
            val dt = t - meanT
            cov += de * dt
            varE += de * de
            varT += dt * dt
        }
        val stdE = sqrt(varE / n.toDouble())
        val stdT = sqrt(varT / n.toDouble())

        // Guard against fixed pole-cams / building routers (flat RSSI, stdE < 1.2 dB) falsely pairing
        // with a civilian car driving past (high TPMS swing, stdT >= 3.5 dB) while observer stands still:
        if (observerSpreadM < 45.0 && ((stdE < 1.2 && stdT >= 3.0) || (stdT < 1.2 && stdE >= 3.0))) {
            return 0.0
        }

        // Co-traveling convoy case: observer moved >= 45m and both emitter & TPMS stayed steadily in close range together
        if (observerSpreadM >= 45.0 && n >= 3 && stdE <= 4.0 && stdT <= 4.0) {
            return 0.90
        }

        var concordantSteps = 0.0
        var activeSteps = 0
        for (i in 1 until n) {
            val dE = paired[i].first - paired[i - 1].first
            val dT = paired[i].second - paired[i - 1].second
            if (abs(dE) < 0.5 && abs(dT) < 0.5) continue
            activeSteps++
            concordantSteps += when {
                dE * dT > 0.0 -> {
                    val ratio = (min(abs(dE), abs(dT)) / max(abs(dE), abs(dT)).coerceAtLeast(1e-6)).coerceIn(0.25, 1.0)
                    0.65 + 0.35 * ratio
                }
                abs(dE) < 0.75 || abs(dT) < 0.75 -> 0.0
                else -> -1.0
            }
        }
        val slopeConcordance = if (activeSteps > 0) (concordantSteps / activeSteps.toDouble()).coerceIn(-1.0, 1.0) else 0.0

        if (n == 2 || stdE < 0.4 || stdT < 0.4) {
            return slopeConcordance
        }
        val pearson = (cov / sqrt((varE * varT).coerceAtLeast(1e-12))).coerceIn(-1.0, 1.0)
        return (0.65 * pearson + 0.35 * slopeConcordance).coerceIn(-1.0, 1.0)
    }

    private fun computeObserverSpreadMeters(samples: List<EmitterSample>): Double {
        val located = samples.filter { it.obsLat != null && it.obsLon != null }
        if (located.size < 2) return 0.0
        var maxDist = 0.0
        for (i in located.indices) {
            for (j in i + 1 until located.size) {
                val d = KokohoPostureEngine.haversineMeters(
                    located[i].obsLat!!,
                    located[i].obsLon!!,
                    located[j].obsLat!!,
                    located[j].obsLon!!
                )
                if (d > maxDist) maxDist = d
            }
        }
        return maxDist
    }

    private fun isDriveByFixedProfile(samples: List<EmitterSample>): Boolean {
        val peak = samples.maxBy { it.signalDb }
        val tail = samples.last()
        return peak.signalDb - tail.signalDb >= 6.0
    }

    fun clear() {
        emitterSeries.clear()
        tpmsSeries.clear()
    }
}
