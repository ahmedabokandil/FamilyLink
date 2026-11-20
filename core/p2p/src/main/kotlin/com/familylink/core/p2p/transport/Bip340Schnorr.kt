package com.familylink.core.p2p.transport

import com.familylink.core.crypto.IdentityManager
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Pure-Kotlin BIP-340 Schnorr signer over secp256k1 using BouncyCastle EC math.
 * Used with throwaway ephemeral secp256k1 keypairs so public Nostr relays accept
 * FamilyLink's opaque end-to-end encrypted sealed packets without learning sender identity.
 */
object Bip340Schnorr {
    private val curveParams = SECNamedCurves.getByName("secp256k1")
    private val n: BigInteger = curveParams.n
    private val p: BigInteger = curveParams.curve.field.characteristic
    private val g: ECPoint = curveParams.g
    private val secureRandom = SecureRandom()

    data class EphemeralKeyPair(
        val privKey: ByteArray,
        val xOnlyPubKeyHex: String
    )

    fun generateEphemeralKeyPair(): EphemeralKeyPair {
        while (true) {
            val dBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
            val d = BigInteger(1, dBytes)
            if (d > BigInteger.ZERO && d < n) {
                val point = g.multiply(d).normalize()
                val pubX = bigIntTo32Bytes(point.affineXCoord.toBigInteger())
                return EphemeralKeyPair(
                    privKey = bigIntTo32Bytes(d),
                    xOnlyPubKeyHex = pubX.toHex()
                )
            }
        }
    }

    fun sign(message32: ByteArray, privKey32: ByteArray): ByteArray {
        require(message32.size == 32) { "BIP-340 message must be 32 bytes" }
        val d0 = BigInteger(1, privKey32)
        require(d0 > BigInteger.ZERO && d0 < n) { "Invalid secp256k1 private key" }

        val pPoint = g.multiply(d0).normalize()
        val d = if (pPoint.affineYCoord.toBigInteger().testBit(0)) n.subtract(d0) else d0
        val px = bigIntTo32Bytes(pPoint.affineXCoord.toBigInteger())

        val aux = ByteArray(32).also { secureRandom.nextBytes(it) }
        val t = xorBytes(bigIntTo32Bytes(d), taggedHash("BIP0340/aux", aux))
        val rand = taggedHash("BIP0340/nonce", t + px + message32)
        val k0 = BigInteger(1, rand).mod(n)
        require(k0 != BigInteger.ZERO) { "Nonce k0 is zero" }

        val rPoint = g.multiply(k0).normalize()
        val k = if (rPoint.affineYCoord.toBigInteger().testBit(0)) n.subtract(k0) else k0
        val rx = bigIntTo32Bytes(rPoint.affineXCoord.toBigInteger())

        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + px + message32)).mod(n)
        val s = k.add(e.multiply(d)).mod(n)
        return rx + bigIntTo32Bytes(s)
    }

    fun verify(message32: ByteArray, pubKey32: ByteArray, sig64: ByteArray): Boolean {
        if (message32.size != 32 || pubKey32.size != 32 || sig64.size != 64) return false
        return try {
            val px = BigInteger(1, pubKey32)
            val r = BigInteger(1, sig64.copyOfRange(0, 32))
            val s = BigInteger(1, sig64.copyOfRange(32, 64))
            if (px >= p || r >= p || s >= n) return false

            val pPoint = liftX(px) ?: return false
            val e = BigInteger(1, taggedHash("BIP0340/challenge", sig64.copyOfRange(0, 32) + pubKey32 + message32)).mod(n)
            val negE = n.subtract(e).mod(n)
            val rPoint = g.multiply(s).add(pPoint.multiply(negE)).normalize()
            if (rPoint.isInfinity) return false
            if (rPoint.affineYCoord.toBigInteger().testBit(0)) return false
            rPoint.affineXCoord.toBigInteger() == r
        } catch (_: Exception) {
            false
        }
    }

    private fun liftX(x: BigInteger): ECPoint? {
        if (x >= p) return null
        val c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        val y = c.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y.modPow(BigInteger.TWO, p) != c) return null
        val evenY = if (y.testBit(0)) p.subtract(y) else y
        return curveParams.curve.createPoint(x, evenY)
    }

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = IdentityManager.sha256(tag.toByteArray(Charsets.UTF_8))
        return IdentityManager.sha256(tagHash + tagHash + msg)
    }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

    private fun bigIntTo32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        } else {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        }
        return out
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
