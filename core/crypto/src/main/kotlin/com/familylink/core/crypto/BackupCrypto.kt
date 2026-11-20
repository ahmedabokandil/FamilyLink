package com.familylink.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

@Serializable
data class BackupPeerRecord(
    val peerId: String,
    val x25519PublicKey: String,
    val displayName: String,
    val verifiedViaQr: Boolean
)

@Serializable
data class BackupGroupRecord(
    val groupId: String,
    val name: String,
    val adminPeerId: String,
    val epoch: Int,
    val groupKeyBase64: String,
    val memberPeerIds: List<String>
)

@Serializable
data class BackupBundle(
    val version: Int = 1,
    val exportedAtMs: Long,
    val displayName: String,
    val identity: IdentityKeyPair,
    val peers: List<BackupPeerRecord> = emptyList(),
    val groups: List<BackupGroupRecord> = emptyList(),
    val relayUrls: List<String> = emptyList()
)

@Serializable
data class EncryptedBackupFile(
    val format: String = "famlink-backup-v1",
    val kdf: String = "argon2id",
    val memoryKb: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String
)

/**
 * Encrypts/decrypts FamilyLink identity & group backups using Argon2id + ChaCha20-Poly1305,
 * and derives/encodes deterministic 24-word recovery phrases for the 32-byte Ed25519 master seed.
 */
object BackupCrypto {
    private val json = Json { ignoreUnknownKeys = true }

    // 256 distinct, easy-to-spell English words (1 word = 8 bits; 32-byte seed + 1-byte checksum = 33 words,
    // or compact 24-word 11-bit BIP-39 style using a 2048-word deterministic table; here we use a 256-word
    // clean checksummed 33-word or 24-token Base32/word hybrid. Actually a 256-word wordlist with 1-byte
    // checksum = 33 unambiguous words, or 24 hex/word blocks. Let's use 256 human-friendly words + 1 checksum word!)
    private val WORDLIST_256: List<String> = listOf(
        "acorn", "amber", "anchor", "apple", "apron", "arrow", "aspen", "atlas",
        "autumn", "badge", "baker", "bamboo", "banjo", "barge", "basil", "beacon",
        "beaver", "berry", "birch", "bison", "blade", "bloom", "board", "boulder",
        "branch", "breeze", "brick", "bridge", "brook", "brush", "cabin", "cactus",
        "camel", "candle", "canyon", "cargo", "carpet", "carrot", "castle", "cedar",
        "celery", "chalk", "cherry", "chime", "cider", "circle", "citrus", "clam",
        "cliff", "clock", "cloud", "clover", "coach", "coast", "cobra", "cocoa",
        "comet", "compass", "copper", "coral", "cotton", "crane", "crate", "creek",
        "crest", "cricket", "crown", "crystal", "cypress", "daisy", "dawn", "delta",
        "desert", "diamond", "dolphin", "domino", "dragon", "drift", "drum", "dune",
        "eagle", "earth", "echo", "elder", "elm", "ember", "emerald", "engine",
        "falcon", "feather", "fern", "fiddle", "field", "fig", "finch", "fjord",
        "flame", "flint", "flora", "flute", "forest", "fossil", "fountain", "fox",
        "frost", "galaxy", "garden", "garnet", "geyser", "ginger", "glacier", "glade",
        "globe", "gold", "goose", "granite", "grape", "grass", "gravel", "grove",
        "guitar", "harbor", "harvest", "hawk", "hazel", "hearth", "heather", "helmet",
        "heron", "hill", "hollow", "honey", "horizon", "horn", "hound", "ibis",
        "icicle", "indigo", "inlet", "iris", "iron", "island", "ivory", "ivy",
        "jacket", "jade", "jaguar", "jasper", "jigsaw", "juniper", "kayak", "kelp",
        "kernel", "kettle", "kestrel", "key", "kiln", "kite", "kiwi", "koala",
        "lagoon", "lake", "lantern", "larch", "lark", "laurel", "lava", "leaf",
        "lemon", "leopard", "lichen", "lilac", "lily", "lime", "linden", "linen",
        "lion", "lizard", "locket", "lotus", "lumber", "lunar", "lynx", "magnet",
        "magpie", "mango", "maple", "marble", "marina", "market", "marsh", "marten",
        "meadow", "melon", "mercury", "mesa", "meteor", "mill", "mint", "mirror",
        "mist", "monarch", "moon", "moose", "moss", "moth", "mountain", "mulberry",
        "nectar", "needle", "nest", "nettle", "nickel", "night", "north", "nutmeg",
        "oak", "oasis", "oat", "ocean", "ochre", "olive", "onyx", "opal",
        "orange", "orbit", "orchid", "oriole", "osprey", "otter", "owl", "oyster",
        "paddle", "palm", "panda", "pansy", "papaya", "paper", "parrot", "peach",
        "peak", "peanut", "pear", "pearl", "pebble", "pelican", "pepper", "perch",
        "petal", "pewter", "phoenix", "piano", "pigeon", "pillar", "pine", "piper"
    )

    init {
        require(WORDLIST_256.size == 256) { "Wordlist must have exactly 256 entries" }
        require(WORDLIST_256.distinct().size == 256) { "Wordlist entries must be unique" }
    }

    /**
     * Derives a complete Ed25519 + X25519 IdentityKeyPair deterministically from a 32-byte seed.
     */
    fun deriveIdentityFromSeed(seed32: ByteArray): IdentityKeyPair {
        require(seed32.size == 32) { "Seed must be exactly 32 bytes" }
        val edPriv = Ed25519PrivateKeyParameters(seed32, 0)
        val edPub = edPriv.generatePublicKey()

        val xSeed = EnvelopeCipher.hkdfSha256(
            ikm = seed32,
            salt = "famlink-seed-x25519-v1".toByteArray(Charsets.UTF_8),
            info = "x25519-private".toByteArray(Charsets.UTF_8),
            length = 32
        )
        val xPriv = X25519PrivateKeyParameters(xSeed, 0)
        val xPub = xPriv.generatePublicKey()

        return IdentityKeyPair(
            ed25519PublicKey = Base64Url.encode(edPub.encoded),
            ed25519PrivateKey = Base64Url.encode(edPriv.encoded),
            x25519PublicKey = Base64Url.encode(xPub.encoded),
            x25519PrivateKey = Base64Url.encode(xPriv.encoded)
        )
    }

    /**
     * Encodes the 32-byte Ed25519 private seed + 1-byte SHA-256 checksum into a human-writable
     * recovery phrase (33 words, each representing 1 byte with built-in typo checksum).
     */
    fun encodeRecoveryPhrase(ed25519PrivateKeyBase64: String): String {
        val seed = Base64Url.decode(ed25519PrivateKeyBase64)
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val checksumByte = IdentityManager.sha256(seed)[0]
        val allBytes = seed + checksumByte
        return allBytes.joinToString(" ") { b -> WORDLIST_256[b.toInt() and 0xFF] }
    }

    /**
     * Decodes and checksum-verifies a recovery phrase back into a deterministic IdentityKeyPair.
     */
    fun recoverIdentityFromPhrase(phrase: String): IdentityKeyPair {
        val words = phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size != 33) {
            throw CryptoVerificationException("Recovery phrase must contain 33 words (found ${words.size})")
        }
        val raw = ByteArray(33) { idx ->
            val wordIndex = WORDLIST_256.indexOf(words[idx])
            if (wordIndex < 0) {
                throw CryptoVerificationException("Unknown recovery word at position ${idx + 1}: '${words[idx]}'")
            }
            wordIndex.toByte()
        }
        val seed = raw.copyOfRange(0, 32)
        val expectedChecksum = IdentityManager.sha256(seed)[0]
        if (raw[32] != expectedChecksum) {
            throw CryptoVerificationException("Recovery phrase checksum mismatch — please check for typos")
        }
        return deriveIdentityFromSeed(seed)
    }

    fun encryptBackup(
        bundle: BackupBundle,
        passphrase: String,
        memoryKb: Int = DEFAULT_MEMORY_KB,
        iterations: Int = DEFAULT_ITERATIONS
    ): String {
        require(passphrase.length >= 6) { "Backup passphrase must be at least 6 characters" }
        val salt = IdentityManager.generateRandomBytes(16)
        val nonce = IdentityManager.generateRandomBytes(12)
        val key = deriveArgon2idKey(passphrase, salt, memoryKb, iterations, 1)

        val aad = "famlink-backup-v1:$memoryKb:$iterations".toByteArray(Charsets.UTF_8)
        val plaintext = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)
        val ciphertext = EnvelopeCipher.aeadEncrypt(key, nonce, plaintext, aad)

        val wrapper = EncryptedBackupFile(
            memoryKb = memoryKb,
            iterations = iterations,
            parallelism = 1,
            saltBase64 = Base64Url.encode(salt),
            nonceBase64 = Base64Url.encode(nonce),
            ciphertextBase64 = Base64Url.encode(ciphertext)
        )
        return json.encodeToString(wrapper)
    }

    fun decryptBackup(encryptedJson: String, passphrase: String): BackupBundle {
        val wrapper = try {
            json.decodeFromString<EncryptedBackupFile>(encryptedJson)
        } catch (e: Exception) {
            throw CryptoVerificationException("Invalid backup file format", e)
        }
        if (wrapper.format != "famlink-backup-v1") {
            throw CryptoVerificationException("Unsupported backup format: ${wrapper.format}")
        }
        val salt = Base64Url.decode(wrapper.saltBase64)
        val nonce = Base64Url.decode(wrapper.nonceBase64)
        val ciphertext = Base64Url.decode(wrapper.ciphertextBase64)
        val key = deriveArgon2idKey(passphrase, salt, wrapper.memoryKb, wrapper.iterations, wrapper.parallelism)
        val aad = "famlink-backup-v1:${wrapper.memoryKb}:${wrapper.iterations}".toByteArray(Charsets.UTF_8)
        val plaintext = EnvelopeCipher.aeadDecrypt(key, nonce, ciphertext, aad)
        return json.decodeFromString(String(plaintext, Charsets.UTF_8))
    }

    private fun deriveArgon2idKey(
        passphrase: String,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(32)
        generator.generateBytes(passphrase.toByteArray(Charsets.UTF_8), out, 0, out.size)
        return out
    }

    private const val DEFAULT_MEMORY_KB = 32 * 1024 // 32 MiB — strong & smooth on mid-range Android devices
    private const val DEFAULT_ITERATIONS = 3
}
