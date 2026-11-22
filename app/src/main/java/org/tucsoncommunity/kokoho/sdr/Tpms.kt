package org.tucsoncommunity.kokoho.sdr

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tire-pressure sensors (TPMS) on 315 MHz (North America) and 433.92 MHz (modern GM/Stellantis/import fleets).
 *
 * Pure I/Q decoder: raw unsigned 8-bit RTL-SDR I/Q in, [Reading]s out. Never emits standalone non-agent alerts or map
 * clutter; readings feed [TpmsLog] for silent own-vehicle exclusion, colocated agent corroboration, and post-verification
 * radio-silent agent vehicle identification in KokohoPostureEngine.
 */
object Tpms {

    data class Reading(
        val protocol: String,
        val id: String,
        val kpa: Double?,
        val tempC: Double?
    ) {
        val key: String
            get() = "${protocol.trim()}:${id.trim().uppercase(Locale.US)}"

        val psi: Double?
            get() = kpa?.div(PSI_KPA)

        val canonicalFacetKey: String
            get() {
                val protoClean = protocol.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "_")
                val idClean = id.trim().uppercase(Locale.US).filter { it.isLetterOrDigit() }
                return "hw:kit:tpms:$protoClean:$idClean"
            }
    }

    const val RATE = 256_000
    val CHANNELS = listOf(315_000_000L, 433_920_000L)
    private const val BLE_TPMS_SERVICE_UUID16 = 0xFBB0
    private const val BLE_TPMS_COMPANY_SYSGRATION = 0x0100
    private val BLE_TPMS_NAME_REGEX = Regex("(?i)^(?:TPMS[1-4]?_[0-9A-F]{4,12}|BLE[_-]?TPMS[_-]?[0-9A-F]{4,12}|BR[0-9A-F]{6})$")

    /**
     * Decodes aftermarket and OEM BLE TPMS sensor advertisements (`0xFBB0` service UUID/data,
     * Sysgration `0x0100` 16-byte tire telemetry frames, or explicit `TPMS*` sensor names).
     * Never matches generic consumer phones, wearables, or audio devices.
     */
    fun decodeBle(advert: org.tucsoncommunity.kokoho.detect.Advert?): Reading? {
        if (advert == null || !advert.isBle) return null
        val shorts = (advert.serviceUuids + advert.serviceData.keys)
            .mapNotNull { org.tucsoncommunity.kokoho.detect.Advert.shortOf(it) }
        val hasTpmsUuid = BLE_TPMS_SERVICE_UUID16 in shorts
        val cleanName = advert.name?.trim().orEmpty()
        val hasTpmsName = cleanName.isNotEmpty() && BLE_TPMS_NAME_REGEX.matches(cleanName)
        val mfgFrame = advert.manufacturerData[BLE_TPMS_COMPANY_SYSGRATION]
        val svcFrame = advert.serviceData[org.tucsoncommunity.kokoho.detect.Advert.uuid16(BLE_TPMS_SERVICE_UUID16)]
        val payload = when {
            svcFrame != null && svcFrame.size >= 6 -> svcFrame
            hasTpmsUuid && mfgFrame != null && mfgFrame.size >= 6 -> mfgFrame
            mfgFrame != null && mfgFrame.size == 16 -> mfgFrame
            else -> null
        }
        if (!hasTpmsUuid && !hasTpmsName && payload == null) return null

        var kpa: Double? = null
        var tempC: Double? = null
        var sensorHexId: String? = null

        if (payload != null && payload.size >= 12) {
            // Standard 16-byte Sysgration BLE TPMS layout:
            // [0..5] 6-byte sensor address/ID, [6..9] pressure Pa LE uint32, [10..13] temp 0.01 C LE int32
            val idBytes = payload.sliceArray(0 until 6)
            sensorHexId = idBytes.joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
            val rawPa = (payload[6].toLong() and 0xFFL) or
                ((payload[7].toLong() and 0xFFL) shl 8) or
                ((payload[8].toLong() and 0xFFL) shl 16) or
                ((payload[9].toLong() and 0xFFL) shl 24)
            val candKpa = rawPa / 1000.0
            if (candKpa in 50.0..950.0) {
                kpa = candKpa
            }
            if (payload.size >= 14) {
                val rawTemp = (payload[10].toInt() and 0xFF) or
                    ((payload[11].toInt() and 0xFF) shl 8) or
                    ((payload[12].toInt() and 0xFF) shl 16) or
                    (payload[13].toInt() shl 24)
                val candTemp = rawTemp / 100.0
                if (candTemp in -45.0..125.0) {
                    tempC = candTemp
                }
            }
        }
        if (sensorHexId == null) {
            val fromName = cleanName.substringAfterLast('_').substringAfterLast('-')
                .uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
            val fromMac = advert.mac.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
            sensorHexId = when {
                fromName.length >= 6 -> fromName.takeLast(8)
                fromMac.length >= 8 -> fromMac.takeLast(8)
                else -> return null
            }
        }
        return Reading(protocol = "BLE", id = sensorHexId, kpa = kpa, tempC = tempC)
    }
    private const val BURST_DB = 10.0
    private const val GAP_S = 1e-3
    private const val MIN_BURST_S = 2e-3
    private const val MAX_BURST_S = 60e-3
    private const val WARM = 1024

    class Demod(
        sampleRate: Int,
        offsetHz: Double,
        private val onReading: (Reading, Double) -> Unit
    ) {
        constructor(sampleRate: Int, offsetHz: Double, onSimple: (Reading) -> Unit) :
            this(sampleRate, offsetHz, { r, _ -> onSimple(r) })

        private val decim = sampleRate / RATE
        init {
            require(decim >= 1 && decim * RATE == sampleRate) {
                "sample rate $sampleRate is not a multiple of $RATE"
            }
        }

        private var pr = 1.0
        private var pi = 0.0
        private val stepR = cos(-2 * PI * offsetHz / sampleRate)
        private val stepI = sin(-2 * PI * offsetHz / sampleRate)
        private var accR = 0.0
        private var accI = 0.0
        private var accN = 0
        private var lastR = 0.0
        private var lastI = 0.0
        private var smooth = 0.0
        private var noise = 0.0
        private var warm = 0
        private val maxLen = (MAX_BURST_S * RATE).toInt()
        private val freq = DoubleArray(maxLen)
        private val power = DoubleArray(maxLen)
        private var len = 0
        private var inBurst = false
        private var quiet = 0
        private val gap = (GAP_S * RATE).toInt()

        var bursts = 0
            private set
        var frames = 0
            private set
        var peakDb = 0.0
            private set

        fun feed(buf: ByteArray, count: Int) {
            var k = 0
            for (s in 0 until count) {
                val i = (buf[k++].toInt() and 0xff) - 127.5
                val q = (buf[k++].toInt() and 0xff) - 127.5
                accR += i * pr - q * pi
                accI += i * pi + q * pr
                val nr = pr * stepR - pi * stepI
                pi = pr * stepI + pi * stepR
                pr = nr
                if (++accN == decim) {
                    accN = 0
                    sample(accR, accI)
                    accR = 0.0
                    accI = 0.0
                    val m = 1.5 - 0.5 * (pr * pr + pi * pi)
                    pr *= m
                    pi *= m
                }
            }
        }

        private fun sample(r: Double, i: Double) {
            val p = r * r + i * i
            val f = atan2(i * lastR - r * lastI, r * lastR + i * lastI)
            lastR = r
            lastI = i
            smooth += (p - smooth) / 8
            if (warm < WARM) {
                warm++
                noise += (p - noise) / warm
                return
            }
            val on = smooth > noise * ON
            if (!inBurst) {
                noise = if (smooth < noise) noise + (smooth - noise) / 64 else noise + (smooth - noise) / 4096
                noise = noise.coerceAtLeast(1e-9)
                if (on) {
                    inBurst = true
                    len = 0
                    quiet = 0
                }
            }
            if (!inBurst) return
            if (len < maxLen) {
                freq[len] = f
                power[len] = p
                len++
            }
            quiet = if (smooth > noise * OFF) 0 else quiet + 1
            if (quiet >= gap || len >= maxLen) {
                inBurst = false
                val end = len - quiet
                if (end >= MIN_BURST_S * RATE) burst(end)
                if (quiet < gap) noise = maxOf(noise, smooth)
            }
        }

        private fun burst(n: Int) {
            bursts++
            val found = LinkedHashMap<String, Reading>()
            fun take(r: Reading) {
                found.putIfAbsent(r.key, r)
            }
            val sorted = freq.copyOf(n).also { it.sort() }
            val mid = (sorted[n / 10] + sorted[n * 9 / 10]) / 2
            val fsk = BooleanArray(n) { freq[it] > mid }
            for (polarity in listOf(false, true)) {
                decodeFsk(chips(fsk, FSK_FAST, polarity)).forEach(::take)
                decodePmv(chips(fsk, FSK_SLOW, polarity)).forEach(::take)
            }
            val p90 = power.copyOf(n).also { it.sort() }[n * 9 / 10]
            val burstSnrDb = (10.0 * log10(p90 / noise.coerceAtLeast(1e-9))).coerceAtLeast(1.0)
            peakDb = maxOf(peakDb, burstSnrDb)
            val cut = sqrt(noise * p90)
            val ook = BooleanArray(n) { power[it] > cut }
            decodeOok(chips(ook, OOK_CHIP, false)).forEach(::take)
            frames += found.size
            for (r in found.values) {
                onReading(r, burstSnrDb)
            }
        }
    }

    private val ON = 10.0.pow(BURST_DB / 10.0)
    private val OFF = 10.0.pow((BURST_DB - 4.0) / 10.0)
    private const val FSK_FAST = 52e-6 * RATE
    private const val FSK_SLOW = 100e-6 * RATE
    private const val OOK_CHIP = 122e-6 * RATE

    internal fun chips(levels: BooleanArray, width: Double, invert: Boolean): IntArray {
        val runLevel = ArrayList<Boolean>()
        val runLen = ArrayList<Int>()
        var i = 0
        while (i < levels.size) {
            var j = i
            while (j < levels.size && levels[j] == levels[i]) j++
            val l = j - i
            if (l < width / 2 && runLen.isNotEmpty()) runLen[runLen.size - 1] += l
            else if (runLevel.isNotEmpty() && runLevel.last() == levels[i]) runLen[runLen.size - 1] += l
            else {
                runLevel += levels[i]
                runLen += l
            }
            i = j
        }
        val out = ArrayList<Int>()
        for (k in runLevel.indices) {
            val c = (runLen[k] / width).roundToInt().coerceAtLeast(1)
            repeat(c) { out += if (runLevel[k] != invert) 1 else 0 }
        }
        return out.toIntArray()
    }

    internal fun search(b: IntArray, from: Int, pattern: Long, bits: Int): Int {
        outer@ for (s in from..b.size - bits) {
            for (k in 0 until bits) {
                if (b[s + k] != ((pattern shr (bits - 1 - k)) and 1L).toInt()) continue@outer
            }
            return s
        }
        return -1
    }

    internal fun manchester(b: IntArray, start: Int, max: Int): IntArray {
        val out = ArrayList<Int>()
        var p = start
        while (p + 1 < b.size && out.size < max) {
            if (b[p] == b[p + 1]) break
            out += b[p + 1]
            p += 2
        }
        return out.toIntArray()
    }

    internal fun diffManchester(b: IntArray, start: Int, max: Int): Pair<IntArray, Int> {
        val out = ArrayList<Int>()
        val len = minOf(b.size, start + max * 2)
        var p = start
        var bit2 = 0
        fun at(i: Int) = if (i < b.size) b[i] else 0
        while (p < len) {
            val bit1 = at(p++)
            bit2 = at(p++)
            val bit3 = at(p)
            if (bit1 != bit2) {
                if (bit2 != bit3) out += 0
                else {
                    bit2 = bit1
                    p -= 1
                    break
                }
            } else {
                bit2 = 1 - bit1
                p -= 2
                break
            }
        }
        while (p < len) {
            val bit1 = at(p++)
            if (bit1 == bit2) break
            bit2 = at(p++)
            out += if (bit1 == bit2) 1 else 0
        }
        return out.toIntArray() to p
    }

    internal fun bytes(b: IntArray, from: Int, bits: Int): IntArray {
        val out = IntArray((bits + 7) / 8)
        for (k in 0 until bits) {
            if (from + k < b.size && b[from + k] == 1) out[k / 8] = out[k / 8] or (0x80 shr (k % 8))
        }
        return out
    }

    internal fun crc8(m: IntArray, n: Int, poly: Int, init: Int): Int {
        var r = init
        for (i in 0 until n) {
            r = r xor m[i]
            repeat(8) { r = if (r and 0x80 != 0) ((r shl 1) xor poly) and 0xff else (r shl 1) and 0xff }
        }
        return r
    }

    private fun hex(v: Long, digits: Int) = "%0${digits}X".format(Locale.US, v)

    internal fun decodeFsk(b: IntArray): List<Reading> = ford(b) + toyota(b) + citroen(b) + hyundaiVdo(b)

    internal fun ford(raw: IntArray): List<Reading> {
        val b0 = inv(raw)
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xaaa9L, 16)
        while (at >= 0 && at + 144 <= b0.size) {
            val bits = manchester(b0, at + 16, 160)
            if (bits.size >= 64) {
                val b = bytes(bits, 0, 64)
                val sum = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6]) and 0xff
                val flags = b[6] and 0x4c
                val unknown = (if (flags == 0x08 || flags == 0x04 || flags == 0x44) 0 else flags) or (b[6] and 0x90)
                if (sum == b[7] && unknown == 0) {
                    val id = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
                    val psi = ((((b[6] and 0x20) shl 3) or b[4]) * 0.25)
                    val temp = if (b[5] and 0x80 == 0) ((b[5] and 0x7f) - 56).toDouble() else null
                    out += Reading("Ford", hex(id, 8), psi * PSI_KPA, temp)
                }
            }
            at = search(b0, at + 15, 0xaaa9L, 16)
        }
        return out
    }

    internal fun toyota(b0: IntArray): List<Reading> {
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xa9eL, 12)
        while (at >= 0 && at + 156 <= b0.size) {
            val (bits, end) = diffManchester(b0, at + 11, 80)
            if (end - (at + 11) >= 144 && bits.size >= 72) {
                val b = bytes(bits, 0, 72)
                val p1 = ((b[4] and 0x7f) shl 1) or (b[5] shr 7)
                val temp = ((b[5] and 0x7f) shl 1) or (b[6] shr 7)
                if (crc8(b, 8, 0x07, 0x80) == b[8] && p1 == (b[7] xor 0xff)) {
                    val id = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
                    out += Reading("Toyota", hex(id, 8), (p1 * 0.25 - 7.0) * PSI_KPA, temp - 40.0)
                }
            }
            at = search(b0, at + 2, 0xa9eL, 12)
        }
        return out
    }

    internal fun citroen(raw: IntArray): List<Reading> {
        val b0 = inv(raw)
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xaaa9L, 16)
        while (at >= 0 && at + 178 <= b0.size) {
            val bits = manchester(b0, at + 16, 88)
            if (bits.size >= 80) {
                val b = bytes(bits, 0, 80)
                var x = 0
                for (k in 1..9) x = x xor b[k]
                if (x == 0 && b[6] != 0 && b[7] != 0) {
                    val id = (b[1].toLong() shl 24) or (b[2].toLong() shl 16) or (b[3].toLong() shl 8) or b[4].toLong()
                    out += Reading("Citroen", hex(id, 8), b[6] * 1.364, b[7] - 50.0)
                }
            }
            at = search(b0, at + 2, 0xaaa9L, 16)
        }
        return out
    }

    internal fun hyundaiVdo(raw: IntArray): List<Reading> {
        val b0 = inv(raw)
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xaaaaaaa9L, 32)
        while (at >= 0 && at + 80 <= b0.size) {
            val bits = manchester(b0, at + 32, 80)
            if (bits.size >= 80) {
                val b = bytes(bits, 0, 80)
                if (crc8(b, 9, 0x07, 0xaa) == b[9]) {
                    val id = (b[1].toLong() shl 24) or (b[2].toLong() shl 16) or (b[3].toLong() shl 8) or b[4].toLong()
                    out += Reading("Hyundai", hex(id, 8), b[6] * 1.375, b[7] - 50.0)
                }
            }
            at = search(b0, at + 2, 0xaaaaaaa9L, 32)
        }
        return out
    }

    internal fun decodePmv(b0: IntArray): List<Reading> {
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0x3eL, 6)
        while (at >= 0 && at + 67 * 2 <= b0.size) {
            val (bits, end) = diffManchester(b0, at + 6, 70)
            if (end - (at + 6) >= 67 * 2 && bits.size >= 66) {
                val b = bytes(IntArray(6) + bits, 0, 72)
                if (crc8(b, 8, 0x13, 0x00) == b[8] && b[5] == (b[6] xor 0xff)) {
                    val id = (b[0].toLong() shl 26) or (b[1].toLong() shl 18) or (b[2].toLong() shl 10) or (b[3].toLong() shl 2) or (b[4].toLong() shr 6)
                    out += Reading("PMV-107J", hex(id, 7), (b[5] - 40.0) * 2.48, b[7] - 40.0)
                }
            }
            at = search(b0, at + 2, 0x3eL, 6)
        }
        return out
    }

    internal fun decodeOok(chips: IntArray): List<Reading> {
        val out = ArrayList<Reading>()
        for (row in rows(chips, ROW_GAP_PCM)) out += schraderSmd(row)
        for (row in rows(chips, ROW_GAP_MANCHESTER)) {
            val bits = zeroBit(row)
            when (bits.size) {
                68 -> out += schraderGg4(bits)
                120 -> out += schraderEg53(bits)
                61 -> out += schraderBmw(bits)
            }
        }
        return out
    }

    private const val ROW_GAP_PCM = 4
    private const val ROW_GAP_MANCHESTER = 3

    internal fun rows(c: IntArray, gap: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < c.size) {
            while (i < c.size && c[i] == 0) i++
            if (i >= c.size) break
            var j = i
            var off = 0
            while (j < c.size) {
                if (c[j] == 0) {
                    if (++off >= gap) break
                } else {
                    off = 0
                }
                j++
            }
            val r = c.copyOfRange(i, minOf(j, c.size))
            out += r.copyOf(minOf(r.lastIndexOf(1) + 2, r.size))
            i = j + 1
        }
        return out
    }

    internal fun zeroBit(row: IntArray): IntArray {
        val c = IntArray(1) + row + IntArray(2)
        val out = ArrayList<Int>()
        var p = 0
        while (p + 1 < c.size) {
            if (c[p] == c[p + 1]) break
            out += if (c[p] == 0) 0 else 1
            p += 2
        }
        return out.toIntArray()
    }

    internal fun schraderGg4(bits: IntArray): List<Reading> {
        val b = bytes(bits, 4, 64)
        if (crc8(b, 7, 0x07, 0xf0) != b[7]) return emptyList()
        val id = ((b[1] and 0x0f).toLong() shl 24) or (b[2].toLong() shl 16) or (b[3].toLong() shl 8) or b[4].toLong()
        return listOf(Reading("Schrader", hex(id, 7), b[5] * 2.5, b[6] - 50.0))
    }

    internal fun schraderEg53(bits: IntArray): List<Reading> {
        val b = bytes(bits, 40, 80)
        if (b[1] == 0 && b[2] == 0 && b[4] == 0 && b[5] == 0 && b[7] == 0 && b[8] == 0) return emptyList()
        if ((b.take(9).sum() and 0xff) != b[9]) return emptyList()
        val id = (b[4].toLong() shl 16) or (b[5].toLong() shl 8) or (b[6].toLong() shl 0)
        return listOf(Reading("Schrader EG53MA4", hex(id, 6), b[7] * 2.5, (b[8] - 32) / 1.8))
    }

    internal fun schraderBmw(bits: IntArray): List<Reading> {
        if (search(bits, 0, 0x7fffL, 16) != 0) return emptyList()
        val b = bytes(bits, 16, 46)
        val id = ((b[0] and 0x1f).toLong() shl 19) or (b[1].toLong() shl 11) or (b[2].toLong() shl 3) or (b[3].toLong() shr 5)
        if (id == 0L || id == 0xffffffL) return emptyList()
        var even = 0
        var n = 0
        for (i in 3 until 38) {
            if ((b[i / 8] shr (7 - i % 8)) and 1 == 1) {
                n++
                if ((i - 3) % 2 == 0) even++
            }
        }
        if ((even + 2 * n - 1) and 3 != (((b[4] shr 3) and 1) shl 1 or ((b[4] shr 2) and 1))) return emptyList()
        val kpa = ((b[3] and 0x1f) shl 4) or (b[4] shr 4)
        val temp = (((b[4] and 0x03) shl 5) or (b[5] shr 3)) - 50
        if (kpa > 450 || temp < -40 || temp > 85) return emptyList()
        return listOf(Reading("Schrader BMW", hex(id, 6), kpa.toDouble(), temp.toDouble()))
    }

    internal fun schraderSmd(row: IntArray): List<Reading> {
        if (row.size < 94 || row.size >= 120) return emptyList()
        val at = search(row, 0, 0x555eL, 16)
        if (at < 0) return emptyList()
        val from = at + 14
        if (from + 38 * 2 > row.size) return emptyList()
        val raw = manchester(row, from, 38)
        if (raw.size != 38) return emptyList()
        val b = bytes(IntArray(38) { 1 - raw[it] }, 0, 40)
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return emptyList()
        var sum = 0
        for (i in 0 until 5) sum += (b[i] and 3) + ((b[i] shr 2) and 3) + ((b[i] shr 4) and 3) + ((b[i] shr 6) and 3)
        if (sum and 3 != 1) return emptyList()
        val id = ((b[0] and 0x0f).toLong() shl 20) or (b[1].toLong() shl 12) or (b[2].toLong() shl 4) or (b[3].toLong() shr 4)
        return listOf(Reading("Schrader SMD3MA4", hex(id, 6), null, null))
    }

    private fun inv(b: IntArray) = IntArray(b.size) { 1 - b[it] }
    private const val PSI_KPA = 6.894757
}

/**
 * Ephemeral in-RAM buffer of recent TPMS readings (90-second rolling window, max 128 entries).
 * Used exclusively by KokohoPostureEngine for silent own-vehicle filtering, colocated agent corroboration,
 * and post-verification agent detection - never shown as raw non-agent rows on Map or Monitor.
 */
object TpmsLog {

    private const val LIVE_TTL_MS = 90_000L
    private const val MAX_ENTRIES = 128

    data class Entry(
        val reading: Tpms.Reading,
        val facetKey: String,
        val peakSnrDb: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val firstLat: Double?,
        val firstLon: Double?,
        val lastLat: Double?,
        val lastLon: Double?,
        val sightings: Int
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun observe(
        reading: Tpms.Reading,
        snrDb: Double,
        nowMs: Long,
        lat: Double? = null,
        lon: Double? = null
    ): Entry {
        val fKey = reading.canonicalFacetKey
        val roundedSnr = snrDb.roundToInt().coerceIn(8, 60)
        val prev = entries[fKey]
        val updated = if (prev == null || nowMs - prev.lastSeen > LIVE_TTL_MS) {
            Entry(
                reading = reading,
                facetKey = fKey,
                peakSnrDb = roundedSnr,
                firstSeen = nowMs,
                lastSeen = nowMs,
                firstLat = lat,
                firstLon = lon,
                lastLat = lat,
                lastLon = lon,
                sightings = 1
            )
        } else {
            prev.copy(
                reading = if (reading.kpa != null) reading else prev.reading,
                peakSnrDb = maxOf(prev.peakSnrDb, roundedSnr),
                lastSeen = nowMs,
                lastLat = lat ?: prev.lastLat,
                lastLon = lon ?: prev.lastLon,
                sightings = prev.sightings + 1
            )
        }
        entries[fKey] = updated
        if (entries.size > MAX_ENTRIES) {
            prune(nowMs)
        }
        return updated
    }

    fun current(nowMs: Long = System.currentTimeMillis()): List<Entry> {
        prune(nowMs)
        return entries.values.sortedByDescending { it.lastSeen }
    }

    fun prune(nowMs: Long) {
        entries.entries.removeIf { nowMs - it.value.lastSeen > LIVE_TTL_MS }
        if (entries.size > MAX_ENTRIES) {
            val overflow = entries.values.sortedBy { it.lastSeen }.take(entries.size - MAX_ENTRIES)
            for (e in overflow) entries.remove(e.facetKey)
        }
    }

    fun clear() {
        entries.clear()
    }
}
