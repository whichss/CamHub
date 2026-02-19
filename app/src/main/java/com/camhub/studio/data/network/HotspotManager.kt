package com.camhub.studio.data.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class HotspotInfo(
    val isActive: Boolean = false,
    val ssid: String = "",
    val password: String = "",
    val errorMessage: String? = null
)

@Singleton
class HotspotManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "HotspotManager"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _hotspotState = MutableStateFlow(HotspotInfo())
    val hotspotState: StateFlow<HotspotInfo> = _hotspotState.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun startHotspot() {
        if (_hotspotState.value.isActive) return

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val config = res.wifiConfiguration
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        res.softApConfiguration?.ssid ?: config?.SSID ?: "Unknown"
                    } else {
                        @Suppress("DEPRECATION")
                        config?.SSID ?: "Unknown"
                    }
                    val password = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        res.softApConfiguration?.passphrase ?: config?.preSharedKey ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        config?.preSharedKey ?: ""
                    }
                    Log.d(TAG, "Hotspot started: SSID=$ssid")
                    _hotspotState.value = HotspotInfo(
                        isActive = true,
                        ssid = ssid,
                        password = password
                    )
                }

                override fun onStopped() {
                    Log.d(TAG, "Hotspot stopped")
                    reservation = null
                    _hotspotState.value = HotspotInfo()
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "Hotspot failed: reason=$reason")
                    val errorMsg = when (reason) {
                        ERROR_NO_CHANNEL -> "채널을 찾을 수 없습니다"
                        ERROR_GENERIC -> "핫스팟을 시작할 수 없습니다"
                        ERROR_INCOMPATIBLE_MODE -> "호환되지 않는 모드입니다"
                        ERROR_TETHERING_DISALLOWED -> "테더링이 허용되지 않습니다"
                        else -> "핫스팟 시작 실패 (코드: $reason)"
                    }
                    _hotspotState.value = HotspotInfo(errorMessage = errorMsg)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: missing location permission", e)
            _hotspotState.value = HotspotInfo(
                errorMessage = "위치 권한이 필요합니다. 설정에서 위치 권한을 허용해주세요."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotspot", e)
            _hotspotState.value = HotspotInfo(
                errorMessage = "핫스팟 시작 실패: ${e.message}"
            )
        }
    }

    fun stopHotspot() {
        try {
            reservation?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing hotspot", e)
        }
        reservation = null
        _hotspotState.value = HotspotInfo()
    }

    fun cleanup() {
        stopHotspot()
    }
}
