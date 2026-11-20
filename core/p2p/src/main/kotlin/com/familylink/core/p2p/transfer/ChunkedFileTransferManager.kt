package com.familylink.core.p2p.transfer

import com.familylink.core.crypto.Base64Url
import com.familylink.core.crypto.CryptoVerificationException
import com.familylink.core.crypto.EnvelopeCipher
import com.familylink.core.crypto.FileChunkPayload
import com.familylink.core.crypto.FileMetaPayload
import com.familylink.core.crypto.IdentityManager
import com.familylink.core.p2p.transport.Bip340Schnorr.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PreparedFileTransfer(
    val meta: FileMetaPayload,
    val chunks: List<FileChunkPayload>
)

/**
 * Content-addressed, E2EE, resumable 64 KB chunked P2P file/photo/video/voice-note transfer engine.
 * Every chunk is independently encrypted with ChaCha20-Poly1305 and verified with SHA-256;
 * whole-file SHA-256 is verified upon completion before committing the assembled file.
 */
class ChunkedFileTransferManager(
    private val mediaDir: File,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {
    private data class IncomingSession(
        val meta: FileMetaPayload,
        val fileKey: ByteArray,
        val decryptedChunks: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap()
    )

    private val incomingSessions = ConcurrentHashMap<String, IncomingSession>()
    private val outgoingCache = ConcurrentHashMap<String, List<FileChunkPayload>>()

    private val _transferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Float>> = _transferProgress.asStateFlow()

    init {
        if (!mediaDir.exists()) mediaDir.mkdirs()
    }

    fun prepareOutgoingFile(
        sourceFile: File,
        conversationId: String,
        fileName: String = sourceFile.name,
        mimeType: String = "application/octet-stream",
        isVoiceNote: Boolean = false,
        durationMs: Long = 0L
    ): PreparedFileTransfer {
        val rawBytes = sourceFile.readBytes()
        return prepareOutgoingBytes(
            rawBytes = rawBytes,
            conversationId = conversationId,
            fileName = fileName,
            mimeType = mimeType,
            isVoiceNote = isVoiceNote,
            durationMs = durationMs
        )
    }

    fun prepareOutgoingBytes(
        rawBytes: ByteArray,
        conversationId: String,
        fileName: String,
        mimeType: String,
        isVoiceNote: Boolean = false,
        durationMs: Long = 0L
    ): PreparedFileTransfer {
        val fileId = UUID.randomUUID().toString()
        val fileKey = IdentityManager.generateRandomBytes(32)
        val fileKeyB64 = Base64Url.encode(fileKey)
        val fileSha256Hex = IdentityManager.sha256(rawBytes).toHex()

        val totalChunks = maxOf(1, (rawBytes.size + chunkSize - 1) / chunkSize)
        val chunks = ArrayList<FileChunkPayload>(totalChunks)

        for (index in 0 until totalChunks) {
            val start = index * chunkSize
            val end = minOf(rawBytes.size, start + chunkSize)
            val slice = if (start < end) rawBytes.copyOfRange(start, end) else ByteArray(0)
            val chunkSha = IdentityManager.sha256(slice).toHex()
            val nonce = deriveChunkNonce(fileId, index)
            val aad = "$fileId:$index:$chunkSha".toByteArray(Charsets.UTF_8)
            val encrypted = EnvelopeCipher.aeadEncrypt(fileKey, nonce, slice, aad)
            chunks.add(
                FileChunkPayload(
                    fileId = fileId,
                    chunkIndex = index,
                    chunkSha256Hex = chunkSha,
                    encryptedChunkBase64 = Base64Url.encode(encrypted)
                )
            )
        }

        val meta = FileMetaPayload(
            fileId = fileId,
            conversationId = conversationId,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = rawBytes.size.toLong(),
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            fileSha256Hex = fileSha256Hex,
            fileKeyBase64 = fileKeyB64,
            isVoiceNote = isVoiceNote,
            durationMs = durationMs
        )
        outgoingCache[fileId] = chunks
        return PreparedFileTransfer(meta, chunks)
    }

    fun getOutgoingChunks(fileId: String, indices: List<Int>): List<FileChunkPayload> {
        val all = outgoingCache[fileId] ?: return emptyList()
        return indices.mapNotNull { idx -> all.getOrNull(idx) }
    }

    fun registerIncomingMeta(meta: FileMetaPayload) {
        incomingSessions.putIfAbsent(
            meta.fileId,
            IncomingSession(
                meta = meta,
                fileKey = Base64Url.decode(meta.fileKeyBase64)
            )
        )
        _transferProgress.update { it + (meta.fileId to 0f) }
    }

    fun getMissingChunkIndices(fileId: String): List<Int> {
        val session = incomingSessions[fileId] ?: return emptyList()
        return (0 until session.meta.totalChunks).filter { !session.decryptedChunks.containsKey(it) }
    }

    /**
     * Decrypts, authenticates, and stores a single chunk. Returns the fully assembled,
     * SHA-256-verified File once all chunks have arrived, or null while still in progress.
     */
    fun ingestChunk(chunk: FileChunkPayload): File? {
        val session = incomingSessions[chunk.fileId] ?: return null
        if (chunk.chunkIndex < 0 || chunk.chunkIndex >= session.meta.totalChunks) {
            throw CryptoVerificationException("Chunk index out of bounds: ${chunk.chunkIndex}")
        }

        val nonce = deriveChunkNonce(chunk.fileId, chunk.chunkIndex)
        val aad = "${chunk.fileId}:${chunk.chunkIndex}:${chunk.chunkSha256Hex}".toByteArray(Charsets.UTF_8)
        val plaintext = EnvelopeCipher.aeadDecrypt(
            key = session.fileKey,
            nonce = nonce,
            ciphertext = Base64Url.decode(chunk.encryptedChunkBase64),
            aad = aad
        )
        val actualChunkSha = IdentityManager.sha256(plaintext).toHex()
        if (actualChunkSha != chunk.chunkSha256Hex) {
            throw CryptoVerificationException("Chunk ${chunk.chunkIndex} SHA-256 mismatch")
        }

        session.decryptedChunks[chunk.chunkIndex] = plaintext
        val progress = session.decryptedChunks.size.toFloat() / session.meta.totalChunks.toFloat()
        _transferProgress.update { it + (chunk.fileId to progress) }

        if (session.decryptedChunks.size < session.meta.totalChunks) {
            return null
        }

        // Reassemble & verify whole-file SHA-256
        val totalSize = session.decryptedChunks.values.sumOf { it.size }
        val assembled = ByteArray(totalSize)
        var offset = 0
        for (idx in 0 until session.meta.totalChunks) {
            val part = session.decryptedChunks[idx] ?: return null
            System.arraycopy(part, 0, assembled, offset, part.size)
            offset += part.size
        }
        val wholeSha = IdentityManager.sha256(assembled).toHex()
        if (wholeSha != session.meta.fileSha256Hex) {
            throw CryptoVerificationException("Whole-file SHA-256 integrity verification failed for ${chunk.fileId}")
        }

        val safeName = session.meta.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(mediaDir, "${chunk.fileId.take(8)}_$safeName")
        outFile.writeBytes(assembled)
        incomingSessions.remove(chunk.fileId)
        _transferProgress.update { it + (chunk.fileId to 1f) }
        return outFile
    }

    private fun deriveChunkNonce(fileId: String, index: Int): ByteArray =
        IdentityManager.sha256("famlink-chunk-nonce-v1:$fileId:$index".toByteArray(Charsets.UTF_8))
            .copyOf(12)

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }
}
