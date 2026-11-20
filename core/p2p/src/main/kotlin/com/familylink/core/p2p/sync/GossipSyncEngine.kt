package com.familylink.core.p2p.sync

import com.familylink.core.crypto.EnvelopeCipher
import com.familylink.core.crypto.PacketEnvelope
import com.familylink.core.crypto.SyncDigestPayload

/**
 * Pure-Kotlin anti-entropy vector-clock gossip engine for peer-assisted offline catch-up.
 *
 * Each conversation tracks the highest seen sequence number per sender (VectorClock).
 * When an offline family member reconnects, ANY online family member holding signed
 * historical envelopes can compute the exact delta and forward them; the receiver
 * cryptographically verifies every original sender's Ed25519 signature before merging.
 */
object GossipSyncEngine {

    fun computeVectorClock(envelopes: List<PacketEnvelope>): Map<String, Long> {
        val clock = mutableMapOf<String, Long>()
        for (env in envelopes) {
            val current = clock[env.senderPk] ?: 0L
            if (env.seq > current) {
                clock[env.senderPk] = env.seq
            }
        }
        return clock
    }

    fun buildDigest(conversationId: String, localVectorClock: Map<String, Long>): SyncDigestPayload =
        SyncDigestPayload(conversationId = conversationId, vectorClock = localVectorClock)

    /**
     * Returns all locally stored envelopes that the remote peer has not yet seen
     * according to their advertised vectorClock, ordered causally by timestamp & seq.
     */
    fun computeMissingEnvelopes(
        localEnvelopes: List<PacketEnvelope>,
        remoteVectorClock: Map<String, Long>,
        maxBatchSize: Int = 50
    ): List<PacketEnvelope> {
        return localEnvelopes
            .filter { env -> env.seq > (remoteVectorClock[env.senderPk] ?: 0L) }
            .sortedWith(compareBy<PacketEnvelope>({ it.timestampMs }, { it.seq }))
            .take(maxBatchSize)
    }

    /**
     * Verifies Ed25519 signatures on all envelopes in a received gossip batch and drops
     * any forged or tampered envelopes before storage/decryption.
     */
    fun verifyIncomingBatch(envelopes: List<PacketEnvelope>): List<PacketEnvelope> {
        return envelopes.filter { env -> EnvelopeCipher.verifyEnvelopeSignature(env) }
    }
}
