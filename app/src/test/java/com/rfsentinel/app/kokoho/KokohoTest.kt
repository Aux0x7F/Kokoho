package com.rfsentinel.app.kokoho

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokohoTest {

    @Test
    fun dhsAgenciesDefaultOnAndOptionalAgenciesDefaultOff() {
        val dhs = KokohoAgency.DHS_AGENCIES
        assertTrue(dhs.contains(KokohoAgency.DHS_CBP))
        assertTrue(dhs.contains(KokohoAgency.DHS_ICE))
        assertTrue(dhs.contains(KokohoAgency.DHS_HSI))
        assertTrue(dhs.contains(KokohoAgency.DHS_AMO))
        assertTrue(dhs.contains(KokohoAgency.DHS_FPS))
        assertTrue(dhs.all { it.isDhs && it.defaultEnabled })

        val opt = KokohoAgency.OPTIONAL_AGENCIES
        assertTrue(opt.contains(KokohoAgency.POLICE))
        assertTrue(opt.contains(KokohoAgency.SHERIFF))
        assertTrue(opt.contains(KokohoAgency.FIRE))
        assertTrue(opt.contains(KokohoAgency.SAR))
        assertTrue(opt.none { it.defaultEnabled })
    }

    @Test
    fun postureColorPaletteMatchesSpecification() {
        assertEquals(0xFF1B5E20.toInt(), PostureColor.GREEN.colorArgb)
        assertEquals("CLEAR", PostureColor.GREEN.badgeLabel)
        assertEquals(0xFFB26A00.toInt(), PostureColor.YELLOW.colorArgb)
        assertEquals(0xFFD84315.toInt(), PostureColor.ORANGE.colorArgb)
        assertEquals(0xFFB71C1C.toInt(), PostureColor.RED.colorArgb)
    }

    @Test
    fun agencyFromIdHandlesAliases() {
        assertEquals(KokohoAgency.DHS_CBP, KokohoAgency.fromId("CBP"))
        assertEquals(KokohoAgency.DHS_ICE, KokohoAgency.fromId("DHS_ICE"))
        assertEquals(KokohoAgency.POLICE, KokohoAgency.fromId("TPD"))
        assertEquals(KokohoAgency.SHERIFF, KokohoAgency.fromId("PCSO"))
        assertFalse(KokohoAgency.FIRE.isDhs)
    }
}
