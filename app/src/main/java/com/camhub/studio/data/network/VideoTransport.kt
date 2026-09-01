package com.camhub.studio.data.network

enum class VideoTransport(val displayLabel: String) {
    NONE("WAIT"),
    UDP_RTP("UDP/RTP"),
    SRT("SRT"),
    TCP("TCP")
}
