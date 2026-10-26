package com.camhub.studio.data

/** Serializes recorder session transitions independently from codec and muxer work. */
internal class RecorderLifecycle {
    enum class State { IDLE, STARTING, RECORDING, STOPPING }
    enum class StopRequest { IGNORE, CANCEL_START, STOP_RECORDING }

    private var state = State.IDLE
    private var cancelStartRequested = false

    @Synchronized
    fun beginStart(): Boolean {
        if (state != State.IDLE) return false
        state = State.STARTING
        cancelStartRequested = false
        return true
    }

    @Synchronized
    fun completeStart(): Boolean {
        if (state != State.STARTING || cancelStartRequested) return false
        state = State.RECORDING
        return true
    }

    @Synchronized
    fun requestStop(): StopRequest = when (state) {
        State.STARTING -> {
            cancelStartRequested = true
            StopRequest.CANCEL_START
        }
        State.RECORDING -> {
            state = State.STOPPING
            StopRequest.STOP_RECORDING
        }
        State.IDLE, State.STOPPING -> StopRequest.IGNORE
    }

    @Synchronized
    fun failRecording(): Boolean {
        if (state != State.RECORDING) return false
        state = State.STOPPING
        return true
    }

    @Synchronized
    fun abortStart() {
        if (state == State.STARTING) state = State.IDLE
        cancelStartRequested = false
    }

    @Synchronized
    fun completeStop() {
        state = State.IDLE
        cancelStartRequested = false
    }

    @Synchronized
    fun isStarting(): Boolean = state == State.STARTING
}
