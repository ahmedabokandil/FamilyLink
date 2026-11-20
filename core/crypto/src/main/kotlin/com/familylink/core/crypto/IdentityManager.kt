package com.familylink.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Generates and manages Ed25519 / X25519 cryptographic identities using BouncyCastle's
 * lightweight engine so behavior is identical on JVM unit tests and all Android API levels.
 */
object IdentityManager {
    private val secureRandom = SecureRandom()

    fun generateIdentity(): IdentityKeyPair {
        val masterSeed = generateRandomBytes(32)
        return BackupCrypto.deriveIdentityFromSeed(masterSeed)
    }

    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun generateGroupKeyBase64(): String = Base64Url.encode(generateRandomBytes(32))

    fun sign(ed25519PrivateKeyBase64: String, message: ByteArray): ByteArray {
        val privParams = Ed25519PrivateKeyParameters(Base64Url.decode(ed25519PrivateKeyBase64), 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(ed25519PublicKeyBase64: String, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pubBytes = Base64Url.decode(ed25519PublicKeyBase64)
            if (pubBytes.size != 32 || signature.size != 64) return false
            val pubParams = Ed25519PublicKeyParameters(pubBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /**
     * Produces a symmetric, human-comparable 60-digit safety number (12 groups of 5 digits)
     * derived from both peers' Ed25519 public keys sorted lexicographically.
     */
    fun computeSafetyNumber(peerIdA: String, peerIdB: String): String {
        val (first, second) = if (peerIdA <= peerIdB) peerIdA to peerIdB else peerIdB to peerIdA
        var hash = sha256("famlink-safety-v1:$first:$second".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        repeat(12) { chunkIndex ->
            if (chunkIndex > 0) sb.append(' ')
            val offset = (chunkIndex * 2) % (hash.size - 4)
            val value = (
                ((hash[offset].toInt() and 0xFF) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
                ).toLong() and 0xFFFFFFFFL
            sb.append((value % 100000L).toString().padStart(5, '0'))
            if (chunkIndex == 5) {
                hash = sha256(hash)
            }
        }
        return sb.toString()
    }
}
