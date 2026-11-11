package org.tucsoncommunity.kokoho.util

import org.tucsoncommunity.kokoho.detect.Category
import org.tucsoncommunity.kokoho.detect.Hit
import org.junit.Assert.assertEquals
import org.junit.Test

class CarAlertTextTest {
    private val hit = Hit(Category.BODY_CAM, "Axon body camera", 90, "", "")

    @Test
    fun carLineSaysWhatHowSureHowFarAndHowManyMore() {
        assertEquals("BODY CAM · strong · ~40 m · near (-58 dBm) · +2 more flagged",
            NotificationHelper.carAlertText(hit, -58, 41.0, 2))
        assertEquals("BODY CAM · weak · far (-88 dBm)",
            NotificationHelper.carAlertText(hit.copy(confidence = 30), -88, null, 0))
    }
}
