package com.camhub.studio.ui.audio.model

data class AudioUiState(
    val channels: List<AudioChannel> = emptyList(),
    val masterLevel: Float = 0f,
    val masterFaderValue: Float = 1f,
    val isMasterMuted: Boolean = false,
    val masterDbValue: Float = -60f,
    val isSyncActive: Boolean = false
)

data class AudioChannel(
    val id: String,
    val label: String,
    val level: Float = 0f,
    val faderValue: Float = 0.75f,
    val isAfv: Boolean = false,
    val syncOffsetMs: Int = 0
)
