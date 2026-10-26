package com.camhub.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlinx.serialization.json.Json

/** Combines Android NSD/mDNS with its UDP announcement fallback. */
class CameraDiscoveryService(
    private val onSnapshot: (List<CameraEndpoint>) -> Unit,
    private val onError: (String) -> Unit = {}
) : AutoCloseable {
    companion object {
        const val ANNOUNCEMENT_PORT = 45_678
        private const val SERVICE_TYPE = "_camhub._tcp.local."
        private const val STALE_AFTER_MS = 4_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private val endpoints = linkedMapOf<String, CameraEndpoint>()
    private val mdnsEndpointIds = mutableMapOf<String, Set<String>>()
    private val mdnsInstances = CopyOnWriteArrayList<JmDNS>()

    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var udpThread: Thread? = null
    @Volatile private var mdnsThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        udpThread = Thread(::listenUdpLoop, "DesktopCameraDiscoveryUdp").apply {
            isDaemon = true
            start()
        }
        mdnsThread = Thread(::startMdnsListeners, "DesktopCameraDiscoveryMdns").apply {
            isDaemon = true
            start()
        }
    }

    fun refresh() {
        stop(clearEndpoints = true)
        start()
    }

    fun addManual(host: String, port: Int) {
        require(host.isNotBlank())
        require(port in 1..65_535)
        val endpoint = CameraEndpoint(host.trim(), port, "Manual-${host.trim()}")
        synchronized(endpoints) {
            endpoints[endpoint.id] = endpoint
            publishLocked()
        }
    }

    override fun close() {
        stop(clearEndpoints = true)
    }

    private fun stop(clearEndpoints: Boolean) {
        running.set(false)
        udpSocket?.close()
        udpSocket = null
        mdnsInstances.forEach { runCatching { it.close() } }
        mdnsInstances.clear()
        udpThread?.interrupt()
        mdnsThread?.interrupt()
        runCatching { udpThread?.join(500) }
        runCatching { mdnsThread?.join(500) }
        udpThread = null
        mdnsThread = null
        if (clearEndpoints) {
            synchronized(endpoints) {
                endpoints.clear()
                mdnsEndpointIds.clear()
                publishLocked()
            }
        }
    }

    private fun listenUdpLoop() {
        try {
            val activeSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 750
                bind(InetSocketAddress(ANNOUNCEMENT_PORT))
            }
            udpSocket = activeSocket
            val buffer = ByteArray(1_024)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    val announcement = json.decodeFromString<CameraAnnouncement>(
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    )
                    val sourceHost = packet.address.hostAddress ?: continue
                    if (announcement.service != "camhub" || announcement.port !in 1..65_535) continue
                    addOrUpdate(
                        CameraEndpoint(
                            host = sourceHost,
                            signalingPort = announcement.port,
                            discoveredName = "Camera-$sourceHost"
                        )
                    )
                } catch (_: SocketTimeoutException) {
                    synchronized(endpoints) {
                        if (removeStaleUdpLocked()) publishLocked()
                    }
                } catch (error: Exception) {
                    if (running.get()) onError("UDP: ${error.message ?: "search failed"}")
                }
            }
        } catch (error: SocketException) {
            if (running.get()) onError("UDP: ${error.message ?: "port unavailable"}")
        } finally {
            udpSocket = null
        }
    }

    private fun startMdnsListeners() {
        val addresses = localDiscoveryAddresses()
        if (addresses.isEmpty()) {
            onError("mDNS: no active local IPv4 interface")
            return
        }
        addresses.forEach { address ->
            if (!running.get()) return
            try {
                val mdns = JmDNS.create(address, "CamHubDesktop-${address.hostAddress}")
                mdnsInstances += mdns
                mdns.addServiceListener(SERVICE_TYPE, mdnsListener(address.hostAddress))
            } catch (error: Exception) {
                if (running.get()) {
                    onError("mDNS ${address.hostAddress}: ${error.message ?: "unavailable"}")
                }
            }
        }
    }

    private fun mdnsListener(interfaceId: String) = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, true, 2_000)
        }

        override fun serviceResolved(event: ServiceEvent) {
            val port = event.info.port
            if (port !in 1..65_535) return
            val serviceKey = "$interfaceId:${event.name}"
            val resolved = event.info.inet4Addresses.mapNotNull { address ->
                val host = address.hostAddress ?: return@mapNotNull null
                CameraEndpoint(
                    host = host,
                    signalingPort = port,
                    discoveredName = event.name.removePrefix("CamHub_")
                        .ifBlank { "Camera-$host" }
                )
            }
            if (resolved.isEmpty()) return
            synchronized(endpoints) {
                mdnsEndpointIds[serviceKey] = resolved.mapTo(mutableSetOf()) { it.id }
                resolved.forEach { endpoints[it.id] = it }
                publishLocked()
            }
        }

        override fun serviceRemoved(event: ServiceEvent) {
            val serviceKey = "$interfaceId:${event.name}"
            synchronized(endpoints) {
                mdnsEndpointIds.remove(serviceKey)
                if (removeStaleUdpLocked()) publishLocked()
            }
        }
    }

    private fun localDiscoveryAddresses(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .distinctBy { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())

    private fun addOrUpdate(endpoint: CameraEndpoint) {
        synchronized(endpoints) {
            endpoints[endpoint.id] = endpoint
            removeStaleUdpLocked()
            publishLocked()
        }
    }

    private fun removeStaleUdpLocked(): Boolean {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        val protectedIds = mdnsEndpointIds.values.flatten().toSet()
        val stale = endpoints.values
            .filter { it.id !in protectedIds && it.lastSeenAtMs < cutoff }
            .map { it.id }
        stale.forEach(endpoints::remove)
        return stale.isNotEmpty()
    }

    private fun publishLocked() {
        onSnapshot(endpoints.values.sortedBy { it.id })
    }
}
