package com.rfsentinel.app.util

import androidx.appcompat.app.AppCompatActivity

/**
 * Disabled in Kokoho — camera locations are never used or prompted.
 */
object CameraQuestion {
    fun askIfNeeded(activity: AppCompatActivity, then: () -> Unit) {
        Prefs.setAutoCameras(activity, false)
        Prefs.setCamerasAsked(activity, true)
        then()
    }
}
