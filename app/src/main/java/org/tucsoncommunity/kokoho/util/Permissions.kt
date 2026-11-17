package org.tucsoncommunity.kokoho.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {

    /**
     * Core runtime permissions required to scan BLE and Wi-Fi continuously.
     * On API 31+ FINE location must be requested together with COARSE, and BLUETOOTH_SCAN +
     * BLUETOOTH_CONNECT enable full advertisement + adapter state inspection.
     */
    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /**
     * High-rate & background scan permissions requested alongside or immediately after core permissions:
     *  - `POST_NOTIFICATIONS` (Android 13+)
     *  - `NEARBY_WIFI_DEVICES` (Android 13+ Wi-Fi Aware / NAN Remote ID & unthrottled Wi-Fi scan)
     */
    fun optional(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    fun missingRequired(context: Context): List<String> = required().filterNot { granted(context, it) }

    fun hasBackgroundLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun isBluetoothEnabled(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = bm.adapter ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
