package com.camhub.studio.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingConfig @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("camhub_streaming", Context.MODE_PRIVATE)

    var fps: Int = prefs.getInt("stream_fps", 30)
        set(value) {
            field = value
            prefs.edit().putInt("stream_fps", value).apply()
        }

    var maxResolution: Int = prefs.getInt("stream_max_resolution", 1080)
        set(value) {
            field = value
            prefs.edit().putInt("stream_max_resolution", value).apply()
        }

    var bitrateMbps: Int = prefs.getInt("stream_bitrate", 6)
        set(value) {
            field = value
            prefs.edit().putInt("stream_bitrate", value).apply()
        }

    var lowLatencyDecode: Boolean = prefs.getBoolean("low_latency_decode", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("low_latency_decode", value).apply()
        }

    // Safe default for new installs. A user's explicit off choice remains persisted.
    var adaptiveBitrate: Boolean = prefs.getBoolean("adaptive_bitrate", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("adaptive_bitrate", value).apply()
        }

    // Select limits from measured device capability/runtime pressure, not a phone model.
    var automaticHubProfile: Boolean = prefs.getBoolean("automatic_hub_profile", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("automatic_hub_profile", value).apply()
        }

    val bitrateBytes: Int get() = bitrateMbps * 1_000_000
}
