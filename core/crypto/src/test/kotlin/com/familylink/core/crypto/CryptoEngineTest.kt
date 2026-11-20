package com.familylink.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoEngineTest {

    @Test
    fun `identity generation and safety number are symmetric and deterministic`() {
        val alice = IdentityManager.generateIdentity()
        val bob = IdentityManager.generateIdentity()

        assertNotEquals(alice.peerId, bob.peerId)
        val snAB = IdentityManager.computeSafetyNumber(alice.peerId, bob.peerId)
        val snBA = IdentityManager.computeSafetyNumber(bob.peerId, alice.peerId)
        assertEquals(snAB, snBA)
        assertEquals(12, snAB.split(" ").size)
    }

    @Test
    fun `direct 1 to 1 envelope round trip and tamper detection`() {
        val alice = IdentityManager.generateIdentity()
        val bob = IdentityManager.generateIdentity()
        val eve = IdentityManager.generateIdentity()
        val message = "Hello Bob, family dinner at 7pm!".toByteArray(Charsets.UTF_8)

        val envelope = EnvelopeCipher.sealDirect(
            sender = alice,
            recipientPk = bob.peerId,
            recipientXk = bob.x25519PublicKey,
            type = PacketType.CHAT_MSG,
            plaintext = message,
            seq = 1
        )

        assertTrue(EnvelopeCipher.verifyEnvelopeSignature(envelope))
        val decrypted = EnvelopeCipher.openDirect(bob, envelope)
        assertArrayEquals(message, decrypted)

        // Eve cannot decrypt
        try {
            EnvelopeCipher.openDirect(eve, envelope)
            fail("Expected CryptoVerificationException for wrong recipient")
        } catch (_: CryptoVerificationException) {
            // expected
        }

        // Tampering with seq breaks both Ed25519 signature and AEAD AAD
        val tampered = envelope.copy(seq = 999)
        assertFalse(EnvelopeCipher.verifyEnvelopeSignature(tampered))
        try {
            EnvelopeCipher.openDirect(bob, tampered)
            fail("Expected CryptoVerificationException on tampered header")
        } catch (_: CryptoVerificationException) {
            // expected
        }
    }

    @Test
    fun `group envelope round trip and post-rotation key isolation`() {
        val alice = IdentityManager.generateIdentity()
        val oldGroupKey = IdentityManager.generateGroupKeyBase64()
        val newGroupKey = IdentityManager.generateGroupKeyBase64()
        val groupId = "family-group-001"

        val msgEpoch1 = EnvelopeCipher.sealGroup(
            sender = alice,
            groupId = groupId,
            groupKeyBase64 = oldGroupKey,
            epoch = 1,
            type = PacketType.CHAT_MSG,
            plaintext = "Welcome to the family group!".toByteArray(),
            seq = 1
        )
        assertEquals(
            "Welcome to the family group!",
            String(EnvelopeCipher.openGroup(oldGroupKey, msgEpoch1))
        )

        // After member removal, key rotates to epoch 2
        val msgEpoch2 = EnvelopeCipher.sealGroup(
            sender = alice,
            groupId = groupId,
            groupKeyBase64 = newGroupKey,
            epoch = 2,
            type = PacketType.CHAT_MSG,
            plaintext = "Secret birthday plan".toByteArray(),
            seq = 2
        )
        assertEquals(
            "Secret birthday plan",
            String(EnvelopeCipher.openGroup(newGroupKey, msgEpoch2))
        )

        // Removed member holding oldGroupKey cannot decrypt epoch 2 message
        try {
            EnvelopeCipher.openGroup(oldGroupKey, msgEpoch2)
            fail("Old group key must not decrypt rotated epoch")
        } catch (_: CryptoVerificationException) {
            // expected
        }
    }

    @Test
    fun `contact and group QR URI encode parse verify and reject tampering`() {
        val alice = IdentityManager.generateIdentity()
        val contactUri = QrPayloadCodec.encodeContactUri(
            identity = alice,
            displayName = "Alice Mom",
            relays = listOf("wss://relay.damus.io", "wss://nos.lol"),
            lanHint = "192.168.1.42:47890"
        )
        val parsedContact = QrPayloadCodec.parseAndVerify(contactUri) as ParsedQrInvite.Contact
        assertEquals(alice.peerId, parsedContact.payload.peerId)
        assertEquals("Alice Mom", parsedContact.payload.displayName)
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), parsedContact.payload.relays)
        assertEquals("192.168.1.42:47890", parsedContact.payload.lanHint)

        // Tamper display name in URI
        val tamperedUri = contactUri.replace("Alice+Mom", "Mallory")
        try {
            QrPayloadCodec.parseAndVerify(tamperedUri)
            fail("Tampered Contact QR should be rejected")
        } catch (_: CryptoVerificationException) {
            // expected
        }

        // Group Invite QR
        val groupKey = IdentityManager.generateGroupKeyBase64()
        val groupUri = QrPayloadCodec.encodeGroupInviteUri(
            adminIdentity = alice,
            adminDisplayName = "Alice Mom",
            groupId = "grp-123",
            epoch = 1,
            groupKeyBase64 = groupKey,
            groupName = "Smith Family",
            relays = listOf("wss://relay.damus.io")
        )
        val parsedGroup = QrPayloadCodec.parseAndVerify(groupUri) as ParsedQrInvite.Group
        assertEquals("grp-123", parsedGroup.payload.groupId)
        assertEquals("Smith Family", parsedGroup.payload.groupName)
        assertEquals(groupKey, parsedGroup.payload.groupKeyBase64)
        assertEquals(alice.peerId, parsedGroup.payload.adminPeerId)
    }

    @Test
    fun `blinded relay tag is deterministic and hides raw peerId`() {
        val alice = IdentityManager.generateIdentity()
        val tag1 = EnvelopeCipher.deriveBlindedRelayTag("peer", alice.peerId)
        val tag2 = EnvelopeCipher.deriveBlindedRelayTag("peer", alice.peerId)
        assertEquals(tag1, tag2)
        assertEquals(64, tag1.length)
        assertFalse(tag1.contains(alice.peerId))
    }
}
