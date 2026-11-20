package com.familylink.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.util.UUID

class CryptoVerificationException(message: String, cause: Throwable? = null) :
    SecurityException(message, cause)

/**
 * End-to-end envelope encryption and signing for both 1:1 (ephemeral X25519 + ChaCha20-Poly1305)
 * and Group (HKDF-derived per-message Sender Keys + ChaCha20-Poly1305) communication.
 */
object EnvelopeCipher {
    private val secureRandom = SecureRandom()
    private const val AEAD_MAC_BITS = 128
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32

    fun sealDirect(
        sender: IdentityKeyPair,
        recipientPk: String,
        recipientXk: String,
        type: PacketType,
        plaintext: ByteArray,
        seq: Long = 0,
        packetId: String = UUID.randomUUID().toString(),
        timestampMs: Long = System.currentTimeMillis()
    ): PacketEnvelope {
        val ephGen = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(secureRandom))
        }
        val ephPair = ephGen.generateKeyPair()
        val ephPriv = ephPair.private as X25519PrivateKeyParameters
        val ephPubBytes = (ephPair.public as X25519PublicKeyParameters).encoded
        val ephPubB64 = Base64Url.encode(ephPubBytes)

        val sharedSecret = computeX25519SharedSecret(ephPriv, Base64Url.decode(recipientXk))
        val aeadKey = hkdfSha256(
            ikm = sharedSecret,
            salt = "famlink-direct-salt-v1".toByteArray(Charsets.UTF_8),
            info = "${sender.peerId}|$recipientPk|$packetId".toByteArray(Charsets.UTF_8),
            length = KEY_BYTES
        )

        val nonce = IdentityManager.generateRandomBytes(NONCE_BYTES)
        val nonceB64 = Base64Url.encode(nonce)

        val aad = canonicalHeaderBytes(
            version = 1,
            packetId = packetId,
            type = type,
            senderPk = sender.peerId,
            targetPeerPk = recipientPk,
            groupId = null,
            epoch = 0,
            seq = seq,
            timestampMs = timestampMs,
            ephemeralXk = ephPubB64,
            nonce = nonceB64
        )
        val ciphertext = aeadEncrypt(aeadKey, nonce, plaintext, aad)
        val ciphertextB64 = Base64Url.encode(ciphertext)

        val signInput = aad + ciphertext
        val signature = IdentityManager.sign(sender.ed25519PrivateKey, signInput)

        return PacketEnvelope(
            version = 1,
            packetId = packetId,
            type = type,
            senderPk = sender.peerId,
            targetPeerPk = recipientPk,
            groupId = null,
            epoch = 0,
            seq = seq,
            timestampMs = timestampMs,
            ephemeralXk = ephPubB64,
            nonce = nonceB64,
            ciphertext = ciphertextB64,
            signature = Base64Url.encode(signature)
        )
    }

    fun openDirect(recipient: IdentityKeyPair, envelope: PacketEnvelope): ByteArray {
        if (!verifyEnvelopeSignature(envelope)) {
            throw CryptoVerificationException("Invalid Ed25519 signature on direct envelope ${envelope.packetId}")
        }
        val ephPubB64 = envelope.ephemeralXk
            ?: throw CryptoVerificationException("Missing ephemeral X25519 key in direct envelope")
        val targetPk = envelope.targetPeerPk
            ?: throw CryptoVerificationException("Missing targetPeerPk in direct envelope")
        if (targetPk != recipient.peerId) {
            throw CryptoVerificationException("Direct envelope addressed to different peer")
        }

        val recipientPriv = X25519PrivateKeyParameters(Base64Url.decode(recipient.x25519PrivateKey), 0)
        val sharedSecret = computeX25519SharedSecret(recipientPriv, Base64Url.decode(ephPubB64))
        val aeadKey = hkdfSha256(
            ikm = sharedSecret,
            salt = "famlink-direct-salt-v1".toByteArray(Charsets.UTF_8),
            info = "${envelope.senderPk}|$targetPk|${envelope.packetId}".toByteArray(Charsets.UTF_8),
            length = KEY_BYTES
        )

        val aad = canonicalHeaderBytes(envelope)
        return aeadDecrypt(
            key = aeadKey,
            nonce = Base64Url.decode(envelope.nonce),
            ciphertext = Base64Url.decode(envelope.ciphertext),
            aad = aad
        )
    }

    fun sealGroup(
        sender: IdentityKeyPair,
        groupId: String,
        groupKeyBase64: String,
        epoch: Int,
        type: PacketType,
        plaintext: ByteArray,
        seq: Long,
        packetId: String = UUID.randomUUID().toString(),
        timestampMs: Long = System.currentTimeMillis()
    ): PacketEnvelope {
        val groupKey = Base64Url.decode(groupKeyBase64)
        require(groupKey.size == KEY_BYTES) { "Group key must be 32 bytes" }

        val msgKey = hkdfSha256(
            ikm = groupKey,
            salt = "famlink-group-salt-v1:$groupId:$epoch".toByteArray(Charsets.UTF_8),
            info = "${sender.peerId}|$seq|$packetId".toByteArray(Charsets.UTF_8),
            length = KEY_BYTES
        )

        val nonce = IdentityManager.generateRandomBytes(NONCE_BYTES)
        val nonceB64 = Base64Url.encode(nonce)
        val aad = canonicalHeaderBytes(
            version = 1,
            packetId = packetId,
            type = type,
            senderPk = sender.peerId,
            targetPeerPk = null,
            groupId = groupId,
            epoch = epoch,
            seq = seq,
            timestampMs = timestampMs,
            ephemeralXk = null,
            nonce = nonceB64
        )
        val ciphertext = aeadEncrypt(msgKey, nonce, plaintext, aad)
        val signature = IdentityManager.sign(sender.ed25519PrivateKey, aad + ciphertext)

        return PacketEnvelope(
            version = 1,
            packetId = packetId,
            type = type,
            senderPk = sender.peerId,
            targetPeerPk = null,
            groupId = groupId,
            epoch = epoch,
            seq = seq,
            timestampMs = timestampMs,
            ephemeralXk = null,
            nonce = nonceB64,
            ciphertext = Base64Url.encode(ciphertext),
            signature = Base64Url.encode(signature)
        )
    }

    fun openGroup(groupKeyBase64: String, envelope: PacketEnvelope): ByteArray {
        if (!verifyEnvelopeSignature(envelope)) {
            throw CryptoVerificationException("Invalid Ed25519 signature on group envelope ${envelope.packetId}")
        }
        val groupId = envelope.groupId
            ?: throw CryptoVerificationException("Missing groupId on group envelope")
        val groupKey = Base64Url.decode(groupKeyBase64)
        if (groupKey.size != KEY_BYTES) {
            throw CryptoVerificationException("Invalid group key size")
        }

        val msgKey = hkdfSha256(
            ikm = groupKey,
            salt = "famlink-group-salt-v1:$groupId:${envelope.epoch}".toByteArray(Charsets.UTF_8),
            info = "${envelope.senderPk}|${envelope.seq}|${envelope.packetId}".toByteArray(Charsets.UTF_8),
            length = KEY_BYTES
        )
        val aad = canonicalHeaderBytes(envelope)
        return aeadDecrypt(
            key = msgKey,
            nonce = Base64Url.decode(envelope.nonce),
            ciphertext = Base64Url.decode(envelope.ciphertext),
            aad = aad
        )
    }

    fun verifyEnvelopeSignature(envelope: PacketEnvelope): Boolean {
        return try {
            val aad = canonicalHeaderBytes(envelope)
            val ciphertext = Base64Url.decode(envelope.ciphertext)
            val signature = Base64Url.decode(envelope.signature)
            IdentityManager.verify(envelope.senderPk, aad + ciphertext, signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Blinds a PeerId or GroupId into a deterministic lowercase hex inbox tag for dumb public relays
     * so relays cannot learn raw public keys or correlate plain group UUIDs.
     */
    fun deriveBlindedRelayTag(scope: String, id: String): String {
        val hash = IdentityManager.sha256("famlink-relay-tag-v1:$scope:$id".toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), AEAD_MAC_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        val finalLen = cipher.doFinal(out, len)
        return out.copyOf(len + finalLen)
    }

    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        return try {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), AEAD_MAC_BITS, nonce, aad))
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val finalLen = cipher.doFinal(out, len)
            out.copyOf(len + finalLen)
        } catch (e: Exception) {
            throw CryptoVerificationException("AEAD authentication/decryption failed", e)
        }
    }

    private fun computeX25519SharedSecret(
        privateKey: X25519PrivateKeyParameters,
        remotePublicBytes: ByteArray
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(remotePublicBytes, 0), secret, 0)
        return secret
    }

    private fun canonicalHeaderBytes(envelope: PacketEnvelope): ByteArray = canonicalHeaderBytes(
        version = envelope.version,
        packetId = envelope.packetId,
        type = envelope.type,
        senderPk = envelope.senderPk,
        targetPeerPk = envelope.targetPeerPk,
        groupId = envelope.groupId,
        epoch = envelope.epoch,
        seq = envelope.seq,
        timestampMs = envelope.timestampMs,
        ephemeralXk = envelope.ephemeralXk,
        nonce = envelope.nonce
    )

    private fun canonicalHeaderBytes(
        version: Int,
        packetId: String,
        type: PacketType,
        senderPk: String,
        targetPeerPk: String?,
        groupId: String?,
        epoch: Int,
        seq: Long,
        timestampMs: Long,
        ephemeralXk: String?,
        nonce: String
    ): ByteArray {
        val canonical = listOf(
            version.toString(),
            packetId,
            type.name,
            senderPk,
            targetPeerPk.orEmpty(),
            groupId.orEmpty(),
            epoch.toString(),
            seq.toString(),
            timestampMs.toString(),
            ephemeralXk.orEmpty(),
            nonce
        ).joinToString("\n")
        return canonical.toByteArray(Charsets.UTF_8)
    }
}
