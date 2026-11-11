package org.tucsoncommunity.kokoho.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The FOSS build downloads exactly the files the full build carries. */
class FirmwareTest {

    @Test
    fun fingerprintsMatchBundledOfflineFirmwareFiles() {
        for (b in Firmware.ALL) {
            val f = File("src/main/assets/usbwifi/${b.name}")
            assertTrue("${b.name} is bundled in main assets for offline use", f.isFile)
            val bytes = f.readBytes()
            assertEquals(b.name, b.size, bytes.size)
            assertEquals(b.name, b.sha256, Firmware.sha256(bytes))
        }
    }

    @Test
    fun onlyTheNonFreeFirmwareIsDownloaded() {
        assertTrue(Firmware.needed(UsbWifi.Driver.AR9271).isEmpty())
        assertTrue(Firmware.needed(UsbWifi.Driver.RTL88XXAU).isEmpty())
        assertEquals(2, Firmware.needed(UsbWifi.Driver.MT7921U).size)
        assertTrue(Firmware.url(Firmware.needed(UsbWifi.Driver.RT3070).single()).startsWith("https://git.kernel.org/"))
        assertTrue(Firmware.url(Firmware.ALL.first()).endsWith("?id=${Firmware.COMMIT}"))
    }
}
