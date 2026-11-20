package com.familylink.core.p2p.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.familylink.core.crypto.EnvelopeCipher
import com.familylink.core.crypto.PacketEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct zero-internet LAN transport using Android NSD (mDNS `_famlink._tcp.`)
 * and length-prefixed TCP sockets. Automatically discovers nearby family devices
 * on the same Wi-Fi router or personal hotspot.
 */
class LanNsdTransport(
    private val context: Context,
    private val localPeerId: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : PeerTransport {

    override val kind: TransportKind = TransportKind.LAN

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incomingPackets = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 128)
    override val incomingPackets: SharedFlow<IncomingPacket> = _incomingPackets.asSharedFlow()

    private val _reachablePeers = MutableStateFlow<Map<String, PeerEndpoint>>(emptyMap())
    override val reachablePeers: StateFlow<Map<String, PeerEndpoint>> = _reachablePeers.asStateFlow()

    private val peerEndpoints = ConcurrentHashMap<String, PeerEndpoint>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    var listeningPort: Int = 0
        private set

    fun getLocalLanHint(): String? {
        val port = listeningPort
        if (port <= 0) return null
        val ip = findLocalIpv4Address() ?: return null
        return "$ip:$port"
    }

    fun registerPeerHint(peerId: String, host: String, port: Int) {
        if (peerId == localPeerId || host.isBlank() || port <= 0) return
        val ep = PeerEndpoint(peerId = peerId, kind = TransportKind.LAN, host = host, port = port)
        peerEndpoints[peerId] = ep
        _reachablePeers.update { peerEndpoints.toMap() }
    }

    override suspend fun start() {
        if (serverSocket != null) return
        withContext(Dispatchers.IO) {
            val socket = ServerSocket(0)
            serverSocket = socket
            listeningPort = socket.localPort

            acceptJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { handleIncomingSocket(client) }
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "LAN accept loop error: ${e.message}")
                    }
                }
            }

            startNsd(listeningPort)
        }
    }

    override suspend fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        listeningPort = 0
        stopNsd()
        peerEndpoints.clear()
        _reachablePeers.value = emptyMap()
    }

    override suspend fun send(targetPeerId: String, envelope: PacketEnvelope): Boolean {
        val endpoint = peerEndpoints[targetPeerId] ?: return false
        val host = endpoint.host ?: return false
        val port = endpoint.port ?: return false

        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = IO_TIMEOUT_MS
                    val payload = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeInt(payload.size)
                    out.write(payload)
                    out.flush()
                }
                true
            } catch (e: Exception) {
                Log.d(TAG, "LAN send to $host:$port failed: ${e.message}")
                peerEndpoints.remove(targetPeerId)
                _reachablePeers.update { peerEndpoints.toMap() }
                false
            }
        }
    }

    private suspend fun handleIncomingSocket(client: Socket) {
        try {
            client.soTimeout = IO_TIMEOUT_MS
            val remoteHost = client.inetAddress.hostAddress
            val input = DataInputStream(client.getInputStream())
            val length = input.readInt()
            if (length <= 0 || length > MAX_FRAME_BYTES) return
            val buffer = ByteArray(length)
            input.readFully(buffer)
            val envelope = json.decodeFromString<PacketEnvelope>(String(buffer, Charsets.UTF_8))
            if (!EnvelopeCipher.verifyEnvelopeSignature(envelope)) {
                Log.w(TAG, "Rejected LAN packet with invalid Ed25519 signature")
                return
            }
            _incomingPackets.emit(
                IncomingPacket(
                    envelope = envelope,
                    sourceKind = TransportKind.LAN,
                    sourceHost = remoteHost,
                    sourcePort = client.port
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "Incoming LAN socket read failed: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun startNsd(port: Int) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "fl_${localPeerId.take(12)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("pk", localPeerId)
        }

        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD registered: ${info.serviceName} on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = regListener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD register exception: ${e.message}")
        }

        val discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val pkBytes = info.attributes["pk"] ?: return
                                val remotePk = String(pkBytes, Charsets.UTF_8)
                                if (remotePk == localPeerId) return
                                @Suppress("DEPRECATION")
                                val host = info.host?.hostAddress ?: return
                                val resolvedPort = info.port
                                registerPeerHint(remotePk, host, resolvedPort)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "NSD resolve failed: ${e.message}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = discListener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery exception: ${e.message}")
        }
    }

    private fun stopNsd() {
        val manager = nsdManager ?: return
        registrationListener?.let {
            try {
                manager.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        discoveryListener?.let {
            try {
                manager.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        registrationListener = null
        discoveryListener = null
    }

    private fun findLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "LanNsdTransport"
        private const val SERVICE_TYPE = "_famlink._tcp."
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val IO_TIMEOUT_MS = 5_000
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
