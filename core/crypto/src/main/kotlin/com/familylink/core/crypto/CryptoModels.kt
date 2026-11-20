package com.familylink.core.crypto

import kotlinx.serialization.Serializable

/**
 * Cryptographic identity of a FamilyLink device.
 * Ed25519 public key is the canonical PeerId; X25519 is used for ECDH key agreement.
 */
@Serializable
data class IdentityKeyPair(
    val ed25519PublicKey: String,
    val ed25519PrivateKey: String,
    val x25519PublicKey: String,
    val x25519PrivateKey: String
) {
    val peerId: String get() = ed25519PublicKey
}

@Serializable
enum class PacketType {
    PEER_HELLO,
    PEER_ACK,
    CHAT_MSG,
    REACTION,
    RECEIPT,
    TYPING,
    SYNC_DIGEST,
    SYNC_BATCH,
    GROUP_EVENT,
    FILE_META,
    FILE_CHUNK,
    FILE_CHUNK_REQ,
    CALL_SIGNAL
}

/**
 * End-to-end encrypted, Ed25519-signed wire envelope used across LAN, Wi-Fi Direct, and relays.
 */
@Serializable
data class PacketEnvelope(
    val version: Int = 1,
    val packetId: String,
    val type: PacketType,
    val senderPk: String,
    val targetPeerPk: String? = null,
    val groupId: String? = null,
    val epoch: Int = 0,
    val seq: Long = 0,
    val timestampMs: Long,
    val ephemeralXk: String? = null,
    val nonce: String,
    val ciphertext: String,
    val signature: String
)

@Serializable
data class ContactQrPayload(
    val peerId: String,
    val x25519PublicKey: String,
    val displayName: String,
    val relays: List<String> = emptyList(),
    val lanHint: String? = null,
    val signature: String
)

@Serializable
data class GroupInviteQrPayload(
    val groupId: String,
    val epoch: Int,
    val groupKeyBase64: String,
    val groupName: String,
    val adminPeerId: String,
    val adminX25519PublicKey: String,
    val adminDisplayName: String,
    val relays: List<String> = emptyList(),
    val lanHint: String? = null,
    val signature: String
)

@Serializable
data class PeerHelloPayload(
    val displayName: String,
    val x25519PublicKey: String,
    val lanHost: String? = null,
    val lanPort: Int? = null,
    val relays: List<String> = emptyList()
)

@Serializable
data class ChatMessagePayload(
    val text: String,
    val replyToPacketId: String? = null,
    val attachmentId: String? = null,
    val attachmentMime: String? = null,
    val attachmentName: String? = null
)

@Serializable
data class SyncDigestPayload(
    val conversationId: String,
    val vectorClock: Map<String, Long>
)

@Serializable
data class SyncBatchPayload(
    val conversationId: String,
    val envelopes: List<PacketEnvelope>
)

@Serializable
enum class GroupEventAction {
    MEMBER_JOINED,
    MEMBER_REMOVED,
    GROUP_KEY_ROTATE,
    GROUP_RENAMED
}

@Serializable
data class GroupEventPayload(
    val action: GroupEventAction,
    val targetPeerId: String? = null,
    val targetX25519Pk: String? = null,
    val targetDisplayName: String? = null,
    val newGroupName: String? = null,
    val newEpoch: Int? = null,
    val newGroupKeyBase64: String? = null,
    val memberPeerIds: List<String> = emptyList()
)
