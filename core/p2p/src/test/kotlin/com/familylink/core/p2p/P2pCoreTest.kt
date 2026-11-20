package com.familylink.core.p2p

import com.familylink.core.crypto.EnvelopeCipher
import com.familylink.core.crypto.IdentityManager
import com.familylink.core.crypto.PacketEnvelope
import com.familylink.core.crypto.PacketType
import com.familylink.core.p2p.router.HybridTransportRouter
import com.familylink.core.p2p.router.OutboxStore
import com.familylink.core.p2p.router.QueuedOutboxItem
import com.familylink.core.p2p.sync.GossipSyncEngine
import com.familylink.core.p2p.transport.Bip340Schnorr
import com.familylink.core.p2p.transport.IncomingPacket
import com.familylink.core.p2p.transport.PeerEndpoint
import com.familylink.core.p2p.transport.PeerTransport
import com.familylink.core.p2p.transport.TransportKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pCoreTest {

    private class FakeTransport(
        override val kind: TransportKind,
        var shouldSucceed: Boolean = true
    ) : PeerTransport {
        val sentPackets = mutableListOf<Pair<String, PacketEnvelope>>()
        val incoming = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 32)
        val peers = MutableStateFlow<Map<String, PeerEndpoint>>(emptyMap())

        override val incomingPackets: SharedFlow<IncomingPacket> = incoming
        override val reachablePeers: StateFlow<Map<String, PeerEndpoint>> = peers

        override suspend fun start() {}
        override suspend fun stop() {}

        override suspend fun send(targetPeerId: String, envelope: PacketEnvelope): Boolean {
            if (!shouldSucceed) return false
            sentPackets += targetPeerId to envelope
            return true
        }
    }

    private class InMemoryOutbox : OutboxStore {
        val items = mutableListOf<QueuedOutboxItem>()
        override suspend fun enqueue(item: QueuedOutboxItem) {
            items.removeAll { it.packetId == item.packetId && it.targetPeerPk == item.targetPeerPk }
            items.add(item)
        }
        override suspend fun getPendingForPeer(peerId: String): List<QueuedOutboxItem> =
            items.filter { it.targetPeerPk == peerId }
        override suspend fun remove(packetId: String, peerId: String) {
            items.removeAll { it.packetId == packetId && it.targetPeerPk == peerId }
        }
        override suspend fun markRetry(packetId: String, peerId: String, nextAttemptMs: Long) {
            val idx = items.indexOfFirst { it.packetId == packetId && it.targetPeerPk == peerId }
            if (idx >= 0) items[idx] = items[idx].copy(retryCount = items[idx].retryCount + 1)
        }
    }

    @Test
    fun `BIP-340 Schnorr signing verifies valid signatures and rejects tampered messages`() {
        val keyPair = Bip340Schnorr.generateEphemeralKeyPair()
        val msgHash = IdentityManager.sha256("nostr-event-commitment-test".toByteArray())
        val sig = Bip340Schnorr.sign(msgHash, keyPair.privKey)
        val pubBytes = Bip340Schnorr.hexToBytes(keyPair.xOnlyPubKeyHex)

        assertTrue(Bip340Schnorr.verify(msgHash, pubBytes, sig))

        val tamperedHash = IdentityManager.sha256("tampered-event".toByteArray())
        assertFalse(Bip340Schnorr.verify(tamperedHash, pubBytes, sig))
    }

    @Test
    fun `HybridTransportRouter prefers LAN over Wi-Fi Direct over Relay and queues offline packets`() = runTest {
        val alice = IdentityManager.generateIdentity()
        val bob = IdentityManager.generateIdentity()
        val lan = FakeTransport(TransportKind.LAN, shouldSucceed = true)
        val wfd = FakeTransport(TransportKind.WIFI_DIRECT, shouldSucceed = true)
        val relay = FakeTransport(TransportKind.RELAY, shouldSucceed = true)
        val outbox = InMemoryOutbox()

        val router = HybridTransportRouter(listOf(relay, wfd, lan), outbox, backgroundScope)
        router.start()

        val env1 = EnvelopeCipher.sealDirect(alice, bob.peerId, bob.x25519PublicKey, PacketType.CHAT_MSG, "hi".toByteArray(), 1)
        assertEquals(TransportKind.LAN, router.send(bob.peerId, env1))
        assertEquals(1, lan.sentPackets.size)
        assertEquals(0, relay.sentPackets.size)

        // Fail LAN -> falls back to Wi-Fi Direct
        lan.shouldSucceed = false
        val env2 = EnvelopeCipher.sealDirect(alice, bob.peerId, bob.x25519PublicKey, PacketType.CHAT_MSG, "hi2".toByteArray(), 2)
        assertEquals(TransportKind.WIFI_DIRECT, router.send(bob.peerId, env2))

        // Fail LAN & Wi-Fi Direct -> falls back to Relay
        wfd.shouldSucceed = false
        val env3 = EnvelopeCipher.sealDirect(alice, bob.peerId, bob.x25519PublicKey, PacketType.CHAT_MSG, "hi3".toByteArray(), 3)
        assertEquals(TransportKind.RELAY, router.send(bob.peerId, env3))

        // Fail all -> persists in Outbox, then flushes on reconnect
        relay.shouldSucceed = false
        val env4 = EnvelopeCipher.sealDirect(alice, bob.peerId, bob.x25519PublicKey, PacketType.CHAT_MSG, "offline".toByteArray(), 4)
        assertNull(router.send(bob.peerId, env4))
        assertEquals(1, outbox.items.size)

        lan.shouldSucceed = true
        val flushed = router.flushOutboxForPeer(bob.peerId)
        assertEquals(1, flushed)
        assertEquals(0, outbox.items.size)
        assertEquals(2, lan.sentPackets.size)

        router.stop()
    }

    @Test
    fun `GossipSyncEngine converges missed messages across 3 peers and rejects forged envelopes`() {
        val alice = IdentityManager.generateIdentity()
        val bob = IdentityManager.generateIdentity()
        val groupKey = IdentityManager.generateGroupKeyBase64()
        val groupId = "smith-family"

        val m1 = EnvelopeCipher.sealGroup(alice, groupId, groupKey, 1, PacketType.CHAT_MSG, "Msg 1".toByteArray(), seq = 1, timestampMs = 100)
        val m2 = EnvelopeCipher.sealGroup(alice, groupId, groupKey, 1, PacketType.CHAT_MSG, "Msg 2".toByteArray(), seq = 2, timestampMs = 200)
        val m3 = EnvelopeCipher.sealGroup(bob, groupId, groupKey, 1, PacketType.CHAT_MSG, "Msg 3".toByteArray(), seq = 1, timestampMs = 300)

        // Charlie only saw Alice seq=1 before going offline
        val charlieClock = mapOf(alice.peerId to 1L)
        val bobLocalLog = listOf(m1, m2, m3)

        val delta = GossipSyncEngine.computeMissingEnvelopes(bobLocalLog, charlieClock)
        assertEquals(listOf(m2.packetId, m3.packetId), delta.map { it.packetId })

        // Tamper one packet in transit and verify GossipSyncEngine drops only the forged one
        val forged = m2.copy(seq = 99)
        val verified = GossipSyncEngine.verifyIncomingBatch(listOf(forged, m3))
        assertEquals(listOf(m3.packetId), verified.map { it.packetId })
    }
}
