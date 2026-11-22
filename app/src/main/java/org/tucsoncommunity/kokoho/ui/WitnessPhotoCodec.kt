package org.tucsoncommunity.kokoho.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Witness & report photo encoder preserving 100% retrospective zoom detail up to 8 MB per image.
 *
 *  - Images `<= 8 MB` (JPEG, PNG, WebP) bypass pixel decoding/rescaling/recompression completely
 *    so distant license plates, unit numbers, and patches retain 100% original sensor detail,
 *    while losslessly stripping camera/phone EXIF (`Make`, `Model`, `GPS`, `Software`, `XMP`)
 *    at the container segment/chunk level.
 *  - Only images `> 8 MB` are re-encoded at full resolution stepping JPEG quality (`95 -> 90 -> 85 -> 80`),
 *    falling back to `0.85x` dimension steps only if full-resolution quality `80` still exceeds `8 MB`.
 */
object WitnessPhotoCodec {

    const val MAX_WITNESS_PHOTO_BYTES: Int = 8 * 1024 * 1024
    private const val MAX_RAW_READ_BYTES: Int = 48 * 1024 * 1024

    fun encodePreservingZoomDetail(context: Context, uri: Uri): String? = runCatching {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_RAW_READ_BYTES) return@use null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        encodeBytesPreservingZoomDetail(rawBytes)
    }.getOrNull()

    fun encodeBytesPreservingZoomDetail(rawBytes: ByteArray): String? {
        if (rawBytes.isEmpty()) return null
        var jpegOrientation = 1
        when {
            isJpeg(rawBytes) -> {
                val (stripped, orient) = stripJpegExifLossless(rawBytes)
                jpegOrientation = orient
                if (stripped.size <= MAX_WITNESS_PHOTO_BYTES) {
                    return "data:image/jpeg;base64," + Base64.encodeToString(stripped, Base64.NO_WRAP)
                }
            }
            isPng(rawBytes) -> {
                val stripped = stripPngMetadataChunksLossless(rawBytes)
                if (stripped.size <= MAX_WITNESS_PHOTO_BYTES) {
                    return "data:image/png;base64," + Base64.encodeToString(stripped, Base64.NO_WRAP)
                }
            }
            isWebp(rawBytes) -> {
                val stripped = stripWebpMetadataChunksLossless(rawBytes)
                if (stripped.size <= MAX_WITNESS_PHOTO_BYTES) {
                    return "data:image/webp;base64," + Base64.encodeToString(stripped, Base64.NO_WRAP)
                }
            }
        }

        // Fallback ONLY when raw/stripped file exceeds 8 MB (or is an unsupported container like BMP/HEIC):
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        val upright = applyExifOrientation(decoded, jpegOrientation)

        val bos = ByteArrayOutputStream()
        for (quality in intArrayOf(95, 90, 85, 80)) {
            bos.reset()
            if (upright.compress(Bitmap.CompressFormat.JPEG, quality, bos) &&
                bos.size() in 1..MAX_WITNESS_PHOTO_BYTES
            ) {
                return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }
        }

        var cur = upright
        repeat(10) {
            val nextW = (cur.width * 0.85f).roundToInt().coerceAtLeast(1)
            val nextH = (cur.height * 0.85f).roundToInt().coerceAtLeast(1)
            if (nextW == cur.width && nextH == cur.height) return@repeat
            cur = Bitmap.createScaledBitmap(cur, nextW, nextH, true)
            bos.reset()
            if (cur.compress(Bitmap.CompressFormat.JPEG, 82, bos) &&
                bos.size() in 1..MAX_WITNESS_PHOTO_BYTES
            ) {
                return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }
        }
        return if (bos.size() in 1..MAX_WITNESS_PHOTO_BYTES) {
            "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } else {
            null
        }
    }

    internal fun isJpeg(b: ByteArray): Boolean =
        b.size >= 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8

    internal fun isPng(b: ByteArray): Boolean =
        b.size >= 8 &&
            b[0] == 0x89.toByte() &&
            b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() &&
            b[3] == 0x47.toByte() &&
            b[4] == 0x0D.toByte() &&
            b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() &&
            b[7] == 0x0A.toByte()

    internal fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 &&
            b[0] == 'R'.code.toByte() &&
            b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() &&
            b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() &&
            b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() &&
            b[11] == 'P'.code.toByte()

    /**
     * Losslessly slices JPEG segments without touching `SOF`, `DQT`, `DHT`, or `SOS` scan bytes:
     * strips `APP1` (`Exif\0\0` and XMP) and `COM` metadata segments, emitting a minimal 36-byte
     * orientation-only `APP1` segment only when EXIF `Orientation` != 1 (so rotated portrait photos
     * render upright with zero phone `Make`/`Model`/`GPS` metadata).
     */
    internal fun stripJpegExifLossless(jpeg: ByteArray): Pair<ByteArray, Int> {
        if (!isJpeg(jpeg)) return jpeg to 1
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(0xFF)
        out.write(0xD8)
        var pos = 2
        var orientation = 1
        var orientationEmitted = false

        while (pos < jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != 0xFF) {
                // Unexpected non-marker byte outside scan; copy remainder safely
                out.write(jpeg, pos, jpeg.size - pos)
                return out.toByteArray() to orientation
            }
            val markerStart = pos
            while (pos < jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
                pos++
            }
            if (pos >= jpeg.size) break
            val marker = jpeg[pos].toInt() and 0xFF
            pos++

            if (marker == 0xD9) {
                // EOI
                out.write(0xFF)
                out.write(0xD9)
                break
            }
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) {
                out.write(jpeg, markerStart, pos - markerStart)
                continue
            }
            if (marker == 0xDA) {
                // SOS: copy SOS header + entire entropy-coded scan stream bit-for-bit to end of file
                if (orientation in 2..8 && !orientationEmitted) {
                    val minimalOrient = buildMinimalOrientationApp1Segment(orientation)
                    out.write(minimalOrient, 0, minimalOrient.size)
                    orientationEmitted = true
                }
                out.write(jpeg, markerStart, jpeg.size - markerStart)
                break
            }
            if (pos + 2 > jpeg.size) {
                out.write(jpeg, markerStart, jpeg.size - markerStart)
                break
            }
            val segLen = ((jpeg[pos].toInt() and 0xFF) shl 8) or (jpeg[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > jpeg.size) {
                out.write(jpeg, markerStart, jpeg.size - markerStart)
                break
            }
            val payloadStart = pos + 2
            val payloadLen = segLen - 2

            val stripSegment = when (marker) {
                0xE1 -> {
                    if (isExifPayload(jpeg, payloadStart, payloadLen)) {
                        val parsedOrient = extractExifOrientation(jpeg, payloadStart, payloadLen)
                        if (parsedOrient in 2..8) orientation = parsedOrient
                        true
                    } else {
                        // Strip XMP / Adobe APP1 metadata packets as well
                        isXmpPayload(jpeg, payloadStart, payloadLen)
                    }
                }
                0xFE -> true // Strip JPEG COM comment segment (may contain camera/phone strings)
                else -> false
            }

            if (!stripSegment) {
                out.write(jpeg, markerStart, (pos + segLen) - markerStart)
            }
            pos += segLen
        }

        return out.toByteArray() to orientation
    }

    private fun isExifPayload(b: ByteArray, start: Int, len: Int): Boolean =
        len >= 6 &&
            b[start] == 'E'.code.toByte() &&
            b[start + 1] == 'x'.code.toByte() &&
            b[start + 2] == 'i'.code.toByte() &&
            b[start + 3] == 'f'.code.toByte() &&
            b[start + 4] == 0.toByte() &&
            b[start + 5] == 0.toByte()

    private fun isXmpPayload(b: ByteArray, start: Int, len: Int): Boolean {
        val prefix = "http://ns.adobe.com/xap/"
        if (len < prefix.length) return false
        for (i in prefix.indices) {
            if (b[start + i] != prefix[i].code.toByte()) return false
        }
        return true
    }

    internal fun extractExifOrientation(b: ByteArray, exifStart: Int, exifLen: Int): Int {
        if (exifLen < 16) return 1
        val tiffStart = exifStart + 6
        val exifEnd = exifStart + exifLen
        val littleEndian = when {
            b[tiffStart] == 0x49.toByte() && b[tiffStart + 1] == 0x49.toByte() -> true
            b[tiffStart] == 0x4D.toByte() && b[tiffStart + 1] == 0x4D.toByte() -> false
            else -> return 1
        }
        fun u16(off: Int): Int {
            if (off < tiffStart || off + 2 > exifEnd) return -1
            val b0 = b[off].toInt() and 0xFF
            val b1 = b[off + 1].toInt() and 0xFF
            return if (littleEndian) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
        }
        fun u32(off: Int): Long {
            if (off < tiffStart || off + 4 > exifEnd) return -1L
            val b0 = (b[off].toInt() and 0xFF).toLong()
            val b1 = (b[off + 1].toInt() and 0xFF).toLong()
            val b2 = (b[off + 2].toInt() and 0xFF).toLong()
            val b3 = (b[off + 3].toInt() and 0xFF).toLong()
            return if (littleEndian) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }
        if (u16(tiffStart + 2) != 42) return 1
        val ifd0Rel = u32(tiffStart + 4)
        if (ifd0Rel < 8L || ifd0Rel > exifLen.toLong()) return 1
        val ifd0Pos = tiffStart + ifd0Rel.toInt()
        val count = u16(ifd0Pos)
        if (count <= 0) return 1
        val safeCount = count.coerceAtMost(128)
        for (i in 0 until safeCount) {
            val entryPos = ifd0Pos + 2 + i * 12
            if (entryPos + 12 > exifEnd) break
            val tag = u16(entryPos)
            if (tag == 0x0112) {
                val type = u16(entryPos + 2)
                val valCount = u32(entryPos + 4)
                if (type == 3 && valCount >= 1L) {
                    val orient = u16(entryPos + 8)
                    return if (orient in 1..8) orient else 1
                }
            }
        }
        return 1
    }

    private fun buildMinimalOrientationApp1Segment(orientation: Int): ByteArray {
        val o = orientation.coerceIn(1, 8)
        return byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            0x00, 0x22, // 34 bytes segment length
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00, 0x00,
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // Big-endian TIFF header, IFD0 @ 8
            0x00, 0x01, // 1 IFD entry
            0x01, 0x12, // Tag 0x0112 (Orientation)
            0x00, 0x03, // Type SHORT (3)
            0x00, 0x00, 0x00, 0x01, // Count 1
            ((o ushr 8) and 0xFF).toByte(), (o and 0xFF).toByte(), 0x00, 0x00, // Value
            0x00, 0x00, 0x00, 0x00 // Next IFD = 0
        )
    }

    internal fun stripPngMetadataChunksLossless(png: ByteArray): ByteArray {
        if (!isPng(png)) return png
        val out = ByteArrayOutputStream(png.size)
        out.write(png, 0, 8)
        var pos = 8
        while (pos + 12 <= png.size) {
            val len = ((png[pos].toInt() and 0xFF) shl 24) or
                ((png[pos + 1].toInt() and 0xFF) shl 16) or
                ((png[pos + 2].toInt() and 0xFF) shl 8) or
                (png[pos + 3].toInt() and 0xFF)
            val totalChunkBytes = 12L + (len.toLong() and 0xFFFFFFFFL)
            if (len < 0 || pos + totalChunkBytes > png.size.toLong()) {
                return png
            }
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val drop = type == "eXIf" || type == "tEXt" || type == "zTXt" || type == "iTXt"
            if (!drop) {
                out.write(png, pos, totalChunkBytes.toInt())
            }
            pos += totalChunkBytes.toInt()
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    internal fun stripWebpMetadataChunksLossless(webp: ByteArray): ByteArray {
        if (!isWebp(webp)) return webp
        val body = ByteArrayOutputStream(webp.size)
        var pos = 12
        var droppedAny = false
        while (pos + 8 <= webp.size) {
            val fourCc = String(webp, pos, 4, Charsets.US_ASCII)
            val chunkSize = (webp[pos + 4].toInt() and 0xFF) or
                ((webp[pos + 5].toInt() and 0xFF) shl 8) or
                ((webp[pos + 6].toInt() and 0xFF) shl 16) or
                ((webp[pos + 7].toInt() and 0xFF) shl 24)
            if (chunkSize < 0) return webp
            val padded = chunkSize + (chunkSize and 1)
            if (pos + 8L + padded.toLong() > webp.size.toLong()) return webp
            if (fourCc == "EXIF" || fourCc == "XMP ") {
                droppedAny = true
            } else {
                body.write(webp, pos, 8 + padded)
            }
            pos += 8 + padded
        }
        if (!droppedAny) return webp
        val payload = body.toByteArray()
        val riffSize = 4 + payload.size
        val out = ByteArrayOutputStream(12 + payload.size)
        out.write(webp, 0, 4) // "RIFF"
        out.write(riffSize and 0xFF)
        out.write((riffSize ushr 8) and 0xFF)
        out.write((riffSize ushr 16) and 0xFF)
        out.write((riffSize ushr 24) and 0xFF)
        out.write(webp, 8, 4) // "WEBP"
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    private fun applyExifOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        if (orientation !in 2..8) return bmp
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            5 -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            6 -> m.setRotate(90f)
            7 -> {
                m.setRotate(270f)
                m.postScale(-1f, 1f)
            }
            8 -> m.setRotate(270f)
        }
        return runCatching { Bitmap.createBitmap(bmp, 0, 0, max(1, bmp.width), max(1, bmp.height), m, true) }.getOrDefault(bmp)
    }
}
