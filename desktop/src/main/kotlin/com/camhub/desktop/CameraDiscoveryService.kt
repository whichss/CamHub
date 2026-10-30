package com.camhub.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    private val registrationLock = Any()
    private val endpoints = linkedMapOf<String, CameraEndpoint>()
    private val mdnsEndpointIds = mutableMapOf<String, Set<String>>()
    private val mdnsInstances = CopyOnWriteArrayList<JmDNS>()

    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var udpThread: Thread? = null
    @Volatile private var mdnsThread: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val runId = generation.incrementAndGet()
        udpThread = Thread({ listenUdpLoop(runId) }, "DesktopCameraDiscoveryUdp").apply {
            isDaemon = true
            start()
        }
        mdnsThread = Thread({ startMdnsListeners(runId) }, "DesktopCameraDiscoveryMdns").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
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

    @Synchronized
    override fun close() {
        stop(clearEndpoints = true)
    }

    private fun stop(clearEndpoints: Boolean) {
        val oldMdns = synchronized(registrationLock) {
            running.set(false)
            generation.incrementAndGet()
            udpSocket?.close()
            udpSocket = null
            mdnsInstances.toList().also { mdnsInstances.clear() }
        }
        oldMdns.forEach { runCatching { it.close() } }
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

    private fun listenUdpLoop(runId: Long) {
        var ownedSocket: DatagramSocket? = null
        try {
            val activeSocket = DatagramSocket(null).also { ownedSocket = it }.apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 750
                bind(InetSocketAddress(ANNOUNCEMENT_PORT))
            }
            synchronized(registrationLock) {
                if (generation.get() != runId || !running.get()) { activeSocket.close(); return }
                udpSocket = activeSocket
            }
            val buffer = ByteArray(1_024)
            while (running.get() && generation.get() == runId && !activeSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    val announcement = json.decodeFromString<CameraAnnouncement>(
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    )
                    val sourceHost = packet.address.hostAddress ?: continue
                    // Android's kotlinx.serialization encoder omits default-valued fields,
                    // including service="camhub". Accept that wire-compatible legacy shape.
                    if (
                        (announcement.service.isNotEmpty() && announcement.service != "camhub") ||
                        announcement.port !in 1..65_535
                    ) continue
                    addOrUpdate(
                        CameraEndpoint(
                            host = sourceHost,
                            signalingPort = announcement.port,
                            discoveredName = announcement.deviceName.take(64).ifBlank { "Camera-$sourceHost" }
                        ),
                        runId
                    )
                } catch (_: SocketTimeoutException) {
                    synchronized(endpoints) {
                        if (generation.get() == runId && running.get() && removeStaleUdpLocked()) publishLocked()
                    }
                } catch (error: Exception) {
                    if (running.get() && generation.get() == runId) onError("UDP: ${error.message ?: "search failed"}")
                }
            }
        } catch (error: SocketException) {
            if (running.get() && generation.get() == runId) onError("UDP: ${error.message ?: "port unavailable"}")
        } finally {
            ownedSocket?.close()
            synchronized(registrationLock) {
                if (udpSocket === ownedSocket) udpSocket = null
            }
        }
    }

    private fun startMdnsListeners(runId: Long) {
        val addresses = localDiscoveryAddresses()
        if (addresses.isEmpty()) {
            if (running.get() && generation.get() == runId) onError("mDNS: no active multicast interface")
            return
        }
        addresses.forEach { address ->
            if (!running.get() || generation.get() != runId) return
            try {
                val mdns = JmDNS.create(address, "CamHubDesktop-${address.hostAddress}")
                synchronized(registrationLock) {
                    if (!running.get() || generation.get() != runId) { mdns.close(); return }
                    mdnsInstances += mdns
                }
                mdns.addServiceListener(SERVICE_TYPE, mdnsListener(address.hostAddress, runId))
            } catch (error: Exception) {
                if (running.get() && generation.get() == runId) {
                    onError("mDNS ${address.hostAddress}: ${error.message ?: "unavailable"}")
                }
            }
        }
    }

    private fun mdnsListener(interfaceId: String, runId: Long) = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            if (!running.get() || generation.get() != runId) return
            event.dns.requestServiceInfo(event.type, event.name, true, 2_000)
        }

        override fun serviceResolved(event: ServiceEvent) {
            if (!running.get() || generation.get() != runId) return
            val port = event.info.port
            if (port !in 1..65_535) return
            val serviceKey = "$interfaceId:${event.name}"
            val allAddresses = event.info.inetAddresses.filter {
                !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress
            }
            val ipv4Addresses = allAddresses.filterIsInstance<Inet4Address>()
            val preferredAddresses = ipv4Addresses.ifEmpty {
                allAddresses.filterIsInstance<Inet6Address>()
            }
            val resolved = preferredAddresses.mapNotNull { address ->
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
                if (generation.get() != runId || !running.get()) return
                mdnsEndpointIds[serviceKey] = resolved.mapTo(mutableSetOf()) { it.id }
                resolved.forEach { endpoints[it.id] = it }
                publishLocked()
            }
        }

        override fun serviceRemoved(event: ServiceEvent) {
            if (!running.get() || generation.get() != runId) return
            val serviceKey = "$interfaceId:${event.name}"
            synchronized(endpoints) {
                if (generation.get() != runId || !running.get()) return
                mdnsEndpointIds.remove(serviceKey)
                if (removeStaleUdpLocked()) publishLocked()
            }
        }
    }

    private fun localDiscoveryAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filter { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
                    (address is Inet4Address || address is Inet6Address)
            }
            .distinctBy { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())

    private fun addOrUpdate(endpoint: CameraEndpoint, runId: Long) {
        synchronized(endpoints) {
            if (generation.get() != runId || !running.get()) return
            val existing = endpoints[endpoint.id]
            val name = if (endpoint.discoveredName == "Camera-${endpoint.host}" && existing != null) {
                existing.discoveredName
            } else endpoint.discoveredName
            endpoints[endpoint.id] = endpoint.copy(discoveredName = name)
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
