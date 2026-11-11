package org.tucsoncommunity.kokoho.usb

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Opened by Android when a USB device Kokoho drives is plugged in (res/xml/usb_device_filter.xml). Accepting
 * Android's "Open Kokoho?" prompt gives the app USB access to it, and with "Always" ticked later plug-ins get it
 * without asking. Shows nothing: it tells the running scan the device is ready ([ACTION_READY]) and closes.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
        finish()
    }

    companion object {
        /** A plugged-in device was handed to Kokoho (it has USB access now). */
        const val ACTION_READY = "org.tucsoncommunity.kokoho.USB_READY"
        /**
         * After a plug-in, how long Android's own prompt gets before Kokoho asks for access itself (the prompt
         * was turned down, or another app is the device's default), so two prompts never stack.
         */
        const val ATTACH_GRACE_MS = 8_000L
    }
}
