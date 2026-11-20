package com.familylink.core.p2p.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Off-grid routerless Wi-Fi Direct transport using Android's WifiP2pManager.
 * Allows nearby family devices to communicate directly with no Wi-Fi router or internet.
 */
class WifiDirectTransport(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : PeerTransport {

    override val kind: TransportKind = TransportKind.WIFI_DIRECT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incomingPackets = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 128)
    override val incomingPackets: SharedFlow<IncomingPacket> = _incomingPackets.asSharedFlow()

    private val _reachablePeers = MutableStateFlow<Map<String, PeerEndpoint>>(emptyMap())
    override val reachablePeers: StateFlow<Map<String, PeerEndpoint>> = _reachablePeers.asStateFlow()

    private val _discoveredWifiDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredWifiDevices: StateFlow<List<WifiP2pDevice>> = _discoveredWifiDevices.asStateFlow()

    private val peerEndpoints = ConcurrentHashMap<String, PeerEndpoint>()
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var groupOwnerHost: String? = null

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        manager = p2pManager
        channel = p2pManager.initialize(context, Looper.getMainLooper(), null)

        startDirectSocketServer()

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        try {
                            p2pManager.requestPeers(ch) { list ->
                                _discoveredWifiDevices.value = list.deviceList.toList()
                            }
                        } catch (_: SecurityException) {
                            Log.d(TAG, "Missing Wi-Fi Direct peer permission")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        p2pManager.requestConnectionInfo(ch) { info ->
                            if (info.groupFormed) {
                                groupOwnerHost = info.groupOwnerAddress?.hostAddress
                                Log.d(TAG, "Wi-Fi Direct group formed. GO=${info.isGroupOwner}, host=$groupOwnerHost")
                            } else {
                                groupOwnerHost = null
                            }
                        }
                    }
                }
            }
        }
        receiver = br
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(br, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(br, filter)
        }

        triggerDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun triggerDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.discoverPeers(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Wi-Fi Direct discovery started")
                    }
                    override fun onFailure(reason: Int) {
                        Log.d(TAG, "Wi-Fi Direct discovery failed: $reason")
                    }
                }
            )
        } catch (_: SecurityException) {
            Log.d(TAG, "Wi-Fi Direct permission not yet granted")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        try {
            m.connect(
                ch,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Wi-Fi Direct connect initiated to $deviceAddress")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Wi-Fi Direct connect failed: $reason")
                    }
                }
            )
        } catch (_: SecurityException) {
            Log.d(TAG, "Wi-Fi Direct connect permission missing")
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
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
        peerEndpoints.clear()
        _reachablePeers.value = emptyMap()
    }

    override suspend fun send(targetPeerId: String, envelope: PacketEnvelope): Boolean {
        val host = peerEndpoints[targetPeerId]?.host ?: groupOwnerHost ?: return false
        val port = peerEndpoints[targetPeerId]?.port ?: WIFI_DIRECT_PORT
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
                Log.d(TAG, "Wi-Fi Direct send failed to $host:$port: ${e.message}")
                false
            }
        }
    }

    private fun startDirectSocketServer() {
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket(WIFI_DIRECT_PORT)
                serverSocket = socket
                while (isActive && !socket.isClosed) {
                    val client = socket.accept()
                    launch { handleIncomingClient(client) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Wi-Fi Direct socket server stopped: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingClient(client: Socket) {
        try {
            client.soTimeout = IO_TIMEOUT_MS
            val remoteHost = client.inetAddress.hostAddress
            val input = DataInputStream(client.getInputStream())
            val len = input.readInt()
            if (len <= 0 || len > MAX_FRAME_BYTES) return
            val bytes = ByteArray(len)
            input.readFully(bytes)
            val envelope = json.decodeFromString<PacketEnvelope>(String(bytes, Charsets.UTF_8))
            if (!EnvelopeCipher.verifyEnvelopeSignature(envelope)) return

            if (remoteHost != null) {
                peerEndpoints[envelope.senderPk] = PeerEndpoint(
                    peerId = envelope.senderPk,
                    kind = TransportKind.WIFI_DIRECT,
                    host = remoteHost,
                    port = WIFI_DIRECT_PORT
                )
                _reachablePeers.update { peerEndpoints.toMap() }
            }
            _incomingPackets.emit(
                IncomingPacket(
                    envelope = envelope,
                    sourceKind = TransportKind.WIFI_DIRECT,
                    sourceHost = remoteHost,
                    sourcePort = WIFI_DIRECT_PORT
                )
            )
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "WifiDirectTransport"
        private const val WIFI_DIRECT_PORT = 47891
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val IO_TIMEOUT_MS = 5_000
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
