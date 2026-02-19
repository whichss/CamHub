package com.camhub.studio.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredPeer(
    val name: String,
    val ip: String,
    val port: Int,
    val isConnected: Boolean = false
)

@Singleton
class NsdDiscoveryManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NsdDiscovery"
        private const val SERVICE_TYPE = "_camhub._tcp."
        private const val SERVICE_NAME_PREFIX = "CamHub_"
        private const val UDP_BROADCAST_PORT = 45678
        private const val UDP_BROADCAST_INTERVAL_MS = 800L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private var isRegistered = false

    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resolveLoopStarted = false

    // UDP broadcast jobs
    private var udpBroadcastJob: Job? = null
    private var udpListenJob: Job? = null

    @Serializable
    private data class UdpAnnounce(
        val service: String = "camhub",
        val ip: String,
        val port: Int
    )

    @Suppress("deprecation")
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            multicastLock = wifiManager.createMulticastLock("CamHub_NSD").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }
        // WifiLock prevents WiFi radio from sleeping during streaming
        if (wifiLock?.isHeld != true) {
            try {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "CamHub_Stream"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "WifiLock acquired")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire WifiLock", e)
            }
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        }
        multicastLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WifiLock", e)
        }
        wifiLock = null
    }

    fun getLocalIpAddress(): String {
        // Prefer WiFi interface address
        try {
            @Suppress("deprecation")
            val wifiIp = wifiManager.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }
        } catch (e: Exception) {
            Log.w(TAG, "WiFi IP lookup failed", e)
        }

        // Fallback: iterate network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return "0.0.0.0"
    }

    // ==================== Camera side: register service ====================

    fun registerService(deviceName: String, port: Int) {
        if (isRegistered) return
        acquireMulticastLock()

        // 1) NSD registration (use generic name — device identity is shared only after PIN auth)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME_PREFIX}${(1000..9999).random()}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD registered: ${info.serviceName} on port $port")
                isRegistered = true
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: errorCode=$errorCode")
                isRegistered = false
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD unregistered: ${info.serviceName}")
                isRegistered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "registerService exception", e)
        }

        // 2) UDP broadcast as fallback (announce ourselves periodically)
        startUdpBroadcast(deviceName, port)
    }

    fun unregisterService() {
        stopUdpBroadcast()
        if (!isRegistered && registrationListener == null) return
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister service", e)
        }
        registrationListener = null
        isRegistered = false
    }

    // ==================== Director side: discover services ====================

    fun startDiscovery() {
        if (isDiscovering) return
        acquireMulticastLock()

        // Clear only non-connected peers; connected ones are preserved
        _discoveredPeers.value = _discoveredPeers.value.filter { it.isConnected }
        startResolveLoop()

        // 1) NSD discovery
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for: $serviceType")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                val type = serviceInfo.serviceType ?: ""
                if (type.contains("_camhub")) {
                    resolveQueue.trySend(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${serviceInfo.serviceName}")
                val name = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                _discoveredPeers.value = _discoveredPeers.value.filter { it.name != name }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD start discovery failed: errorCode=$errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop discovery failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices exception", e)
        }

        // 2) UDP listen as fallback
        startUdpListen()
    }

    fun stopDiscovery() {
        stopUdpListen()
        if (!isDiscovering && discoveryListener == null) return
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
        discoveryListener = null
        isDiscovering = false
    }

    // ==================== NSD Resolve (serialized) ====================

    private fun startResolveLoop() {
        if (resolveLoopStarted) return
        resolveLoopStarted = true

        scope.launch {
            for (serviceInfo in resolveQueue) {
                resolveServiceBlocking(serviceInfo)
            }
        }
    }

    @Suppress("deprecation")
    private fun resolveServiceBlocking(serviceInfo: NsdServiceInfo) {
        val latch = java.util.concurrent.CountDownLatch(1)

        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: errorCode=$errorCode for ${info.serviceName}")
                    latch.countDown()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val rawName = info.serviceName ?: ""
                    val name = rawName.removePrefix(SERVICE_NAME_PREFIX)
                    val ip = info.host?.hostAddress
                    val port = info.port

                    if (ip != null && port > 0) {
                        Log.d(TAG, "NSD resolved: $name at $ip:$port")
                        addOrUpdatePeer(DiscoveredPeer(name = name, ip = ip, port = port))
                    }
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "resolveService exception", e)
            latch.countDown()
        }

        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
    }

    // ==================== UDP Broadcast fallback ====================

    private fun startUdpBroadcast(deviceName: String, port: Int) {
        udpBroadcastJob?.cancel()
        udpBroadcastJob = scope.launch {
            val localIp = getLocalIpAddress()
            val announce = UdpAnnounce(ip = localIp, port = port)
            val message = json.encodeToString(announce).toByteArray()

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val broadcastAddr = InetAddress.getByName("255.255.255.255")

                Log.d(TAG, "UDP broadcast started: $deviceName at $localIp:$port")
                while (isActive) {
                    try {
                        val packet = DatagramPacket(message, message.size, broadcastAddr, UDP_BROADCAST_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP broadcast send failed: ${e.message}")
                    }
                    delay(UDP_BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP broadcast error", e)
            } finally {
                socket?.close()
            }
        }
    }

    private fun stopUdpBroadcast() {
        udpBroadcastJob?.cancel()
        udpBroadcastJob = null
    }

    private fun startUdpListen() {
        udpListenJob?.cancel()
        udpListenJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_BROADCAST_PORT))
                    broadcast = true
                    soTimeout = 0 // blocking
                }

                val buffer = ByteArray(1024)
                Log.d(TAG, "UDP listen started on port $UDP_BROADCAST_PORT")

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val data = String(packet.data, 0, packet.length)
                        val announce = json.decodeFromString<UdpAnnounce>(data)

                        // Don't add ourselves
                        val localIp = getLocalIpAddress()
                        if (announce.ip != localIp && announce.service == "camhub") {
                            val peerName = "Camera-${announce.ip}"
                            val isNew = _discoveredPeers.value.none { it.ip == announce.ip && it.port == announce.port }
                            if (isNew) {
                                Log.d(TAG, "UDP discovered: $peerName at ${announce.ip}:${announce.port}")
                            }
                            addOrUpdatePeer(
                                DiscoveredPeer(
                                    name = peerName,
                                    ip = announce.ip,
                                    port = announce.port
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "UDP receive error: ${e.message}")
                            delay(500)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP listen socket error", e)
            } finally {
                socket?.close()
            }
        }
    }

    private fun stopUdpListen() {
        udpListenJob?.cancel()
        udpListenJob = null
    }

    // ==================== Manual connection ====================

    fun addManualPeer(ip: String, port: Int) {
        val name = "Manual-$ip"
        addOrUpdatePeer(DiscoveredPeer(name = name, ip = ip, port = port))
    }

    // ==================== Shared helpers ====================

    private fun addOrUpdatePeer(peer: DiscoveredPeer) {
        val current = _discoveredPeers.value.toMutableList()
        // Match by IP to avoid duplicates from NSD + UDP
        val existingIndex = current.indexOfFirst { it.ip == peer.ip && it.port == peer.port }
        if (existingIndex >= 0) {
            current[existingIndex] = peer.copy(isConnected = current[existingIndex].isConnected)
        } else {
            current.add(peer)
        }
        _discoveredPeers.value = current
    }

    fun cleanup() {
        unregisterService()
        stopDiscovery()
        releaseMulticastLock()
        scope.cancel()
    }
}
