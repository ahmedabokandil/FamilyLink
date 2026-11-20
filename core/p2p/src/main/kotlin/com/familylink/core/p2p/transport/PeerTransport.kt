package com.familylink.core.p2p.transport

import com.familylink.core.crypto.PacketEnvelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportKind(val priority: Int, val label: String) {
    LAN(1, "LAN"),
    WIFI_DIRECT(2, "Wi-Fi Direct"),
    RELAY(3, "Relay")
}

data class PeerEndpoint(
    val peerId: String,
    val kind: TransportKind,
    val host: String? = null,
    val port: Int? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class IncomingPacket(
    val envelope: PacketEnvelope,
    val sourceKind: TransportKind,
    val sourceHost: String? = null,
    val sourcePort: Int? = null
)

interface PeerTransport {
    val kind: TransportKind
    val incomingPackets: SharedFlow<IncomingPacket>
    val reachablePeers: StateFlow<Map<String, PeerEndpoint>>

    suspend fun start()
    suspend fun stop()
    suspend fun send(targetPeerId: String, envelope: PacketEnvelope): Boolean
}
