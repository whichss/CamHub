package com.camhub.desktop

import java.awt.image.BufferedImage
import java.net.InetAddress

interface DesktopHubListener {
    fun onDiscoveryChanged(endpoints: List<CameraEndpoint>, message: String)
    fun onCameraStateChanged(slot: Int, snapshot: CameraConnectionSnapshot)
    fun onCameraImage(slot: Int, image: BufferedImage)
    fun onCameraRemoved(slot: Int)
}

/** Coordinates automatic discovery and up to four independent camera connections. */
class DesktopHubController(
    private val listener: DesktopHubListener,
    private val maxCameras: Int = 4
) : DesktopCameraConnectionListener, AutoCloseable {
    private val lock = Any()
    private val connections = linkedMapOf<String, DesktopCameraConnection>()
    private val slots = linkedMapOf<String, Int>()
    private var lastDiscovered: List<CameraEndpoint> = emptyList()
    private val directorName = buildDirectorName()
    private val discovery = CameraDiscoveryService(
        onSnapshot = ::handleDiscoverySnapshot,
        onError = { message -> publishDiscovery("Discovery: $message") }
    )

    fun start() {
        listener.onDiscoveryChanged(
            emptyList(),
            "Searching on UDP ${CameraDiscoveryService.ANNOUNCEMENT_PORT}"
        )
        discovery.start()
    }

    fun connect(endpoint: CameraEndpoint) {
        val connection = synchronized(lock) {
            connections[endpoint.id]?.let { return }
            if (connections.size >= maxCameras) return
            val slot = (0 until maxCameras).firstOrNull { it !in slots.values } ?: return
            DesktopCameraConnection(endpoint, directorName, this).also {
                slots[endpoint.id] = slot
                connections[endpoint.id] = it
            }
        }
        connection.start()
    }

    fun connectAll(endpoints: List<CameraEndpoint>) {
        endpoints.take(maxCameras).forEach(::connect)
    }

    fun connectManual(host: String, port: Int) {
        val endpoint = CameraEndpoint(host.trim(), port, "Manual-${host.trim()}")
        discovery.addManual(endpoint.host, endpoint.signalingPort)
        connect(endpoint)
    }

    fun refreshDiscovery() {
        publishDiscovery("Refreshing camera discovery")
        Thread(discovery::refresh, "DesktopCameraDiscoveryRefresh").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect(endpointId: String) {
        val removed = synchronized(lock) {
            val connection = connections[endpointId] ?: return
            val slot = slots[endpointId] ?: return
            connections.remove(endpointId)
            slots.remove(endpointId)
            connection to slot
        }
        removed.first.close()
        listener.onCameraRemoved(removed.second)
        publishDiscovery("Camera disconnected")
    }

    fun requestPtz(slot: Int, zoom: Float, centerX: Float, centerY: Float): Boolean {
        val connection = synchronized(lock) {
            slots.entries.firstOrNull { it.value == slot }?.key?.let(connections::get)
        } ?: return false
        return connection.requestPtz(zoom, centerX, centerY)
    }

    override fun close() {
        discovery.close()
        val active = synchronized(lock) {
            connections.values.toList().also {
                connections.clear()
                slots.clear()
            }
        }
        active.forEach(DesktopCameraConnection::close)
    }

    override fun onState(
        connection: DesktopCameraConnection,
        snapshot: CameraConnectionSnapshot
    ) {
        val slot = synchronized(lock) {
            if (connections[connection.endpoint.id] !== connection) null else slots[connection.endpoint.id]
        } ?: return
        listener.onCameraStateChanged(slot, snapshot)
    }

    override fun onImage(connection: DesktopCameraConnection, image: BufferedImage) {
        val slot = synchronized(lock) {
            if (connections[connection.endpoint.id] !== connection) null else slots[connection.endpoint.id]
        } ?: return
        listener.onCameraImage(slot, image)
    }

    private fun handleDiscoverySnapshot(endpoints: List<CameraEndpoint>) {
        synchronized(lock) { lastDiscovered = endpoints }
        publishDiscovery(
            if (endpoints.isEmpty()) "Searching for CamHub cameras"
            else "Found ${endpoints.size} · select a camera and press CONNECT"
        )
    }

    private fun publishDiscovery(message: String) {
        val visible = synchronized(lock) {
            (lastDiscovered + connections.values.map { it.endpoint })
                .distinctBy { it.id }
                .sortedBy { it.id }
        }
        listener.onDiscoveryChanged(visible, message)
    }

    private fun buildDirectorName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?.substringBefore('.')
            ?.take(24)
            ?.takeIf(String::isNotBlank)
            ?: "Desktop"
        return "CamHub-$host"
    }
}
