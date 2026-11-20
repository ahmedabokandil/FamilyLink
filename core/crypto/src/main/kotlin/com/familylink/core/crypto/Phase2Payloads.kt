package com.familylink.core.crypto

import kotlinx.serialization.Serializable

@Serializable
data class ReactionPayload(
    val conversationId: String,
    val targetPacketId: String,
    val emoji: String,
    val removed: Boolean = false
)

@Serializable
enum class ReceiptType {
    DELIVERED,
    READ
}

@Serializable
data class ReceiptPayload(
    val conversationId: String,
    val targetPacketId: String,
    val receiptType: ReceiptType
)

@Serializable
data class TypingPayload(
    val conversationId: String,
    val isTyping: Boolean
)

@Serializable
data class FileMetaPayload(
    val fileId: String,
    val conversationId: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val fileSha256Hex: String,
    val fileKeyBase64: String,
    val isVoiceNote: Boolean = false,
    val durationMs: Long = 0L
)

@Serializable
data class FileChunkPayload(
    val fileId: String,
    val chunkIndex: Int,
    val chunkSha256Hex: String,
    val encryptedChunkBase64: String
)

@Serializable
data class FileChunkReqPayload(
    val fileId: String,
    val missingIndices: List<Int>
)
