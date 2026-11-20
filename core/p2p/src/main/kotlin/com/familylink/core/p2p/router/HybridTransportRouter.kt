package com.familylink.core.p2p.router

import com.familylink.core.crypto.PacketEnvelope
import com.familylink.core.p2p.transport.IncomingPacket
import com.familylink.core.p2p.transport.PeerTransport
import com.familylink.core.p2p.transport.TransportKind
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
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections

data class QueuedOutboxItem(
    val packetId: String,
    val targetPeerPk: String,
    val envelopeJson: String,
    val retryCount: Int = 0
)

interface OutboxStore {
    suspend fun enqueue(item: QueuedOutboxItem)
    suspend fun getPendingForPeer(peerId: String): List<QueuedOutboxItem>
    suspend fun remove(packetId: String, peerId: String)
    suspend fun markRetry(packetId: String, peerId: String, nextAttemptMs: Long)
}

/**
 * Routes E2EE packets across available transports in strict priority order:
 * 1. LAN (mDNS / direct local TCP)
 * 2. Wi-Fi Direct (routerless local P2P)
 * 3. Decentralized Relay Pool (internet fallback)
 *
 * Undeliverable packets are persisted to the OutboxStore and flushed automatically
 * as soon as any transport detects that the peer is reachable again.
 */
class HybridTransportRouter(
    private val transports: List<PeerTransport>,
    private val outboxStore: OutboxStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val sortedTransports = transports.sortedBy { it.kind.priority }

    private val _incomingPackets = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 256)
    val incomingPackets: SharedFlow<IncomingPacket> = _incomingPackets.asSharedFlow()

    private val _peerBestTransport = MutableStateFlow<Map<String, TransportKind>>(emptyMap())
    val peerBestTransport: StateFlow<Map<String, TransportKind>> = _peerBestTransport.asStateFlow()

    private val _peerReconnected = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val peerReconnected: SharedFlow<String> = _peerReconnected.asSharedFlow()

    private val seenPacketIds: MutableSet<String> = Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(2048, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > MAX_SEEN_CACHE
            }
        }
    )

    private val jobs = mutableListOf<Job>()

    suspend fun start() {
        sortedTransports.forEach { transport ->
            transport.start()
            jobs += scope.launch {
                transport.incomingPackets.collect { packet ->
                    if (markPacketSeen(packet.envelope.packetId)) {
                        _incomingPackets.emit(packet)
                    }
                }
            }
            jobs += scope.launch {
                transport.reachablePeers.collect {
                    recomputeBestTransports()
                }
            }
        }
    }

    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        sortedTransports.forEach { it.stop() }
        _peerBestTransport.value = emptyMap()
    }

    /**
     * Attempts delivery across transports in priority order.
     * Returns the TransportKind that delivered the packet, or null if queued in outbox.
     */
    suspend fun send(
        targetPeerId: String,
        envelope: PacketEnvelope,
        queueIfOffline: Boolean = true
    ): TransportKind? {
        for (transport in sortedTransports) {
            val delivered = try {
                transport.send(targetPeerId, envelope)
            } catch (_: Exception) {
                false
            }
            if (delivered) {
                return transport.kind
            }
        }
        if (queueIfOffline) {
            outboxStore.enqueue(
                QueuedOutboxItem(
                    packetId = envelope.packetId,
                    targetPeerPk = targetPeerId,
                    envelopeJson = json.encodeToString(envelope)
                )
            )
        }
        return null
    }

    suspend fun flushOutboxForPeer(peerId: String): Int {
        val pending = outboxStore.getPendingForPeer(peerId)
        var deliveredCount = 0
        for (item in pending) {
            val envelope = try {
                json.decodeFromString<PacketEnvelope>(item.envelopeJson)
            } catch (_: Exception) {
                outboxStore.remove(item.packetId, peerId)
                continue
            }
            val deliveredVia = send(peerId, envelope, queueIfOffline = false)
            if (deliveredVia != null) {
                outboxStore.remove(item.packetId, peerId)
                deliveredCount++
            } else {
                val backoffMs = minOf(60_000L, 2_000L * (1L shl minOf(item.retryCount, 5)))
                outboxStore.markRetry(item.packetId, peerId, System.currentTimeMillis() + backoffMs)
            }
        }
        return deliveredCount
    }

    private suspend fun recomputeBestTransports() {
        val previous = _peerBestTransport.value
        val best = mutableMapOf<String, TransportKind>()
        for (transport in sortedTransports) {
            for (peerId in transport.reachablePeers.value.keys) {
                if (!best.containsKey(peerId)) {
                    best[peerId] = transport.kind
                }
            }
        }
        _peerBestTransport.value = best

        for (peerId in best.keys) {
            if (!previous.containsKey(peerId)) {
                _peerReconnected.emit(peerId)
                scope.launch { flushOutboxForPeer(peerId) }
            }
        }
    }

    private fun markPacketSeen(packetId: String): Boolean = synchronized(seenPacketIds) {
        seenPacketIds.add(packetId)
    }

    companion object {
        private const val MAX_SEEN_CACHE = 10_000
    }
}
