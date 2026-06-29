package com.camhub.studio.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Holds Android's low-latency Wi-Fi mode while live streaming is active.
 *
 * This reduces packet latency and jitter on supported devices. The lock is
 * intentionally non-reference-counted; callers can safely call acquire/release
 * around their own streaming lifecycle.
 */
class LowLatencyWifiLock(
    context: Context,
    private val tag: String
) {
    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.WifiLock? = null

    fun acquire() {
        if (lock?.isHeld == true) return
        val manager = wifiManager ?: return
        try {
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            lock = manager.createWifiLock(lockType, tag).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Wi-Fi low-latency lock acquired: " + tag)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire Wi-Fi low-latency lock: " + e.message)
        }
    }

    fun release() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release Wi-Fi low-latency lock: " + e.message)
        } finally {
            lock = null
        }
    }

    private companion object {
        private const val TAG = "LowLatencyWifiLock"
    }
}
