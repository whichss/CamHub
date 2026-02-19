package com.camhub.studio.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camhub.studio.data.audio.AudioStreamClient
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.ui.audio.model.AudioChannel
import com.camhub.studio.ui.audio.model.AudioUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.log10

@HiltViewModel
class AudioMixerViewModel @Inject constructor(
    private val connectionManager: PeerConnectionManager,
    private val audioStreamClient: AudioStreamClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AudioUiState(
            masterFaderValue = audioStreamClient.masterFader,
            isMasterMuted = audioStreamClient.isMasterMuted
        )
    )
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    init {
        // Build audio channels from connected peers
        viewModelScope.launch {
            connectionManager.connectedPeers.collect { peers ->
                _uiState.update { state ->
                    val existingById = state.channels.associateBy { it.id }
                    val channels = peers.mapIndexed { index, peer ->
                        val id = "ch${index + 1}"
                        existingById[id]?.copy(label = peer.name)
                            ?: AudioChannel(
                                id = id,
                                label = peer.name,
                                level = 0f,
                                faderValue = 0.75f,
                                isAfv = false,
                                syncOffsetMs = 0
                            )
                    }
                    state.copy(channels = channels)
                }
            }
        }

        // Real-time channel levels from audio engine
        viewModelScope.launch {
            audioStreamClient.channelStates.collect { channelStates ->
                _uiState.update { state ->
                    val updated = state.channels.map { ch ->
                        val audio = channelStates[ch.label]
                        if (audio != null) ch.copy(level = audio.level) else ch
                    }
                    state.copy(channels = updated)
                }
            }
        }

        // Master level from audio engine (already normalized 0-1 from rmsToNormalizedDb)
        viewModelScope.launch {
            audioStreamClient.masterLevel.collect { normalizedLevel ->
                // Convert normalized level (0-1) back to dB for display: 0→-60dB, 1→0dB
                val db = (normalizedLevel * 60f - 60f).coerceIn(-60f, 0f)
                _uiState.update { it.copy(masterLevel = normalizedLevel, masterDbValue = db) }
            }
        }
    }

    fun updateChannelFader(channelId: String, value: Float) {
        _uiState.update { state ->
            val channel = state.channels.find { it.id == channelId }
            if (channel != null) {
                audioStreamClient.setChannelFader(channel.label, value)
            }
            state.copy(
                channels = state.channels.map { ch ->
                    if (ch.id == channelId) ch.copy(faderValue = value) else ch
                }
            )
        }
    }

    fun toggleAfv(channelId: String) {
        _uiState.update { state ->
            val channel = state.channels.find { it.id == channelId }
            if (channel != null) {
                val newIsAfv = !channel.isAfv
                audioStreamClient.setChannelAfv(channel.label, newIsAfv)
            }
            state.copy(
                channels = state.channels.map { ch ->
                    if (ch.id == channelId) ch.copy(isAfv = !ch.isAfv) else ch
                }
            )
        }
    }

    fun updateSyncOffset(channelId: String, offsetMs: Int) {
        _uiState.update { state ->
            val channel = state.channels.find { it.id == channelId }
            if (channel != null) {
                audioStreamClient.setChannelSyncOffset(channel.label, offsetMs)
            }
            state.copy(
                channels = state.channels.map { ch ->
                    if (ch.id == channelId) ch.copy(syncOffsetMs = offsetMs) else ch
                }
            )
        }
    }

    fun updateMasterFader(value: Float) {
        audioStreamClient.masterFader = value
        _uiState.update { it.copy(masterFaderValue = value) }
    }

    fun toggleMasterMute() {
        val newIsMuted = !_uiState.value.isMasterMuted
        audioStreamClient.isMasterMuted = newIsMuted
        _uiState.update { it.copy(isMasterMuted = newIsMuted) }
    }
}
