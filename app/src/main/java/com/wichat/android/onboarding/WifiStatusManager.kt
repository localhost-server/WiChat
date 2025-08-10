package com.wichat.android.onboarding

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Manages Wi-Fi enable/disable state and user prompts.
 */
class WifiStatusManager(
    private val activity: ComponentActivity,
    private val context: Context,
    private val onWifiEnabled: () -> Unit,
    private val onWifiDisabled: (String) -> Unit
) {

    companion object {
        private const val TAG = "WifiStatusManager"
    }

    private var wifiManager: WifiManager? = null
    private var wifiEnableLauncher: ActivityResultLauncher<Intent>? = null

    init {
        setupWifiManager()
        setupWifiEnableLauncher()
    }

    private fun setupWifiManager() {
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun setupWifiEnableLauncher() {
        wifiEnableLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { 
            if (isWifiEnabled()) {
                onWifiEnabled()
            } else {
                onWifiDisabled("Wi-Fi is required for bitchat to discover and connect to nearby users.")
            }
        }
    }

    fun isWifiSupported(): Boolean {
        return wifiManager != null
    }

    fun isWifiEnabled(): Boolean {
        return wifiManager?.isWifiEnabled == true
    }

    fun checkWifiStatus(): WifiStatus {
        return when {
            !isWifiSupported() -> WifiStatus.NOT_SUPPORTED
            !isWifiEnabled() -> WifiStatus.DISABLED
            else -> WifiStatus.ENABLED
        }
    }

    fun requestEnableWifi() {
        Log.d(TAG, "Requesting user to enable Wi-Fi")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            wifiEnableLauncher?.launch(panelIntent)
        } else {
            // For older versions, we can only open the Wi-Fi settings screen
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            wifiEnableLauncher?.launch(intent)
        }
    }

    fun getDiagnostics(): String {
        return buildString {
            appendLine("Wifi Status Diagnostics:")
            appendLine("Wifi supported: ${isWifiSupported()}")
            appendLine("Wifi enabled: ${isWifiEnabled()}")
            appendLine("Current status: ${checkWifiStatus()}")
        }
    }

    fun logWifiStatus() {
        Log.d(TAG, getDiagnostics())
    }
}

enum class WifiStatus {
    ENABLED,
    DISABLED,
    NOT_SUPPORTED
}
