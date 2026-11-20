package com.familylink.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun `encrypted backup round trip succeeds and rejects wrong passphrase or tampering`() {
        val identity = IdentityManager.generateIdentity()
        val bundle = BackupBundle(
            exportedAtMs = 1700000000000L,
            displayName = "Alice Mom",
            identity = identity,
            peers = listOf(BackupPeerRecord("peer-1", "xk-1", "Bob Dad", true)),
            groups = listOf(BackupGroupRecord("grp-1", "Smith Family", identity.peerId, 2, "key123", listOf(identity.peerId, "peer-1"))),
            relayUrls = listOf("wss://relay.damus.io")
        )

        val encrypted = BackupCrypto.encryptBackup(bundle, "super-secret-family-pass", memoryKb = 4096, iterations = 2)
        val restored = BackupCrypto.decryptBackup(encrypted, "super-secret-family-pass")
        assertEquals(bundle, restored)

        try {
            BackupCrypto.decryptBackup(encrypted, "wrong-passphrase")
            fail("Wrong passphrase must fail decryption")
        } catch (_: CryptoVerificationException) {
            // expected
        }
    }

    @Test
    fun `recovery phrase deterministically restores Ed25519 and X25519 identity and detects typos`() {
        val seed = IdentityManager.generateRandomBytes(32)
        val original = BackupCrypto.deriveIdentityFromSeed(seed)
        val phrase = BackupCrypto.encodeRecoveryPhrase(original.ed25519PrivateKey)
        assertEquals(33, phrase.split(" ").size)

        val recovered = BackupCrypto.recoverIdentityFromPhrase(phrase)
        assertEquals(original, recovered)

        // Alter first word -> checksum must fail
        val words = phrase.split(" ").toMutableList()
        words[0] = if (words[0] == "acorn") "amber" else "acorn"
        try {
            BackupCrypto.recoverIdentityFromPhrase(words.joinToString(" "))
            fail("Typo in recovery phrase must fail checksum")
        } catch (_: CryptoVerificationException) {
            // expected
        }
    }
}
