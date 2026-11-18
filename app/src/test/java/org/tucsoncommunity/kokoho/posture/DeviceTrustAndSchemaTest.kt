package org.tucsoncommunity.kokoho.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeviceTrustAndSchemaTest {

    @Test
    fun apkSigningBlockSeatIdentityParserExtractsCustomBlock() {
        val json = """{"seat_id":"seat-007","slug":"k-0123456789abcdef","voucher":"deadbeef001122"}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val pairLen = 4L + jsonBytes.size
        val blockSize = 8L + pairLen + 24L // [pairLen(8) + id(4) + val] + [footerSize(8) + magic(16)]
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
            assertEquals("k-0123456789abcdef", parsed.slug)
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
}
