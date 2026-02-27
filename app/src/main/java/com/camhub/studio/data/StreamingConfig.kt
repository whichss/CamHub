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

    var bitrateMbps: Int = prefs.getInt("stream_bitrate", 4)
        set(value) {
            field = value
            prefs.edit().putInt("stream_bitrate", value).apply()
        }

    val bitrateBytes: Int get() = bitrateMbps * 1_000_000
}
