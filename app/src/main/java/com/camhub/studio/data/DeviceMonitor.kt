package com.camhub.studio.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceStatus(
    val batteryPercent: Int = 100,
    val wifiStrength: Int = 0,
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 0f
)

@Singleton
class DeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private var monitorJob: Job? = null

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                _status.value = DeviceStatus(
                    batteryPercent = getBatteryPercent(),
                    wifiStrength = getWifiStrength(),
                    storageUsedGb = getStorageUsedGb(),
                    storageTotalGb = getStorageTotalGb()
                )
                delay(3000)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun getBatteryPercent(): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        } catch (_: Exception) { 100 }
    }

    private fun getWifiStrength(): Int {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val rssi = wifiManager.connectionInfo?.rssi ?: -100
            WifiManager.calculateSignalLevel(rssi, 5)
        } catch (_: Exception) { 0 }
    }

    private fun getStorageTotalGb(): Float {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.totalBytes / (1024f * 1024f * 1024f)
        } catch (_: Exception) { 0f }
    }

    private fun getStorageUsedGb(): Float {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.totalBytes - stat.availableBytes) / (1024f * 1024f * 1024f)
        } catch (_: Exception) { 0f }
    }
}
