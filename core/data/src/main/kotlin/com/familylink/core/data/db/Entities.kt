package com.familylink.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.familylink.core.crypto.IdentityKeyPair

@Entity(tableName = "local_identity")
data class LocalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val peerId: String,
    val ed25519PrivateKey: String,
    val x25519PublicKey: String,
    val x25519PrivateKey: String,
    val displayName: String,
    val createdAtMs: Long
) {
    fun toKeyPair(): IdentityKeyPair = IdentityKeyPair(
        ed25519PublicKey = peerId,
        ed25519PrivateKey = ed25519PrivateKey,
        x25519PublicKey = x25519PublicKey,
        x25519PrivateKey = x25519PrivateKey
    )
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val x25519PublicKey: String,
    val displayName: String,
    val lastLanHost: String? = null,
    val lastLanPort: Int? = null,
    val lastSeenMs: Long = 0L,
    val verifiedViaQr: Boolean = false
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val adminPeerId: String,
    val epoch: Int,
    val groupKeyBase64: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "peerId"]
)
data class GroupMemberEntity(
    val groupId: String,
    val peerId: String,
    val displayName: String,
    val role: String, // "ADMIN" or "MEMBER"
    val joinedAtMs: Long
)

object MessageStatus {
    const val QUEUED = "QUEUED"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val READ = "READ"
    const val SYNCED = "SYNCED"
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestampMs"]),
        Index(value = ["conversationId", "senderPk", "seq"]),
        Index(value = ["fileId"])
    ]
)
data class MessageEntity(
    @PrimaryKey val packetId: String,
    val conversationId: String, // "dm:<peerId>" or "grp:<groupId>"
    val senderPk: String,
    val senderName: String,
    val seq: Long,
    val type: String, // PacketType.name
    val bodyText: String,
    val mediaPath: String? = null,
    val fileId: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
    val mediaDurationMs: Long = 0L,
    val status: String = MessageStatus.QUEUED,
    val isOutgoing: Boolean,
    val timestampMs: Long,
    val rawEnvelopeJson: String? = null
)

@Entity(
    tableName = "reactions",
    primaryKeys = ["targetPacketId", "senderPk", "emoji"],
    indices = [Index(value = ["conversationId"])]
)
data class ReactionEntity(
    val targetPacketId: String,
    val conversationId: String,
    val senderPk: String,
    val emoji: String,
    val timestampMs: Long
)

@Entity(
    tableName = "outbox_packets",
    primaryKeys = ["packetId", "targetPeerPk"]
)
data class OutboxPacketEntity(
    val packetId: String,
    val targetPeerPk: String,
    val envelopeJson: String,
    val retryCount: Int = 0,
    val nextAttemptMs: Long = 0L,
    val createdAtMs: Long
)

object RelayType {
    const val RELAY = "RELAY"
    const val STUN = "STUN"
    const val TURN = "TURN"
}

@Entity(tableName = "relay_configs")
data class RelayConfigEntity(
    @PrimaryKey val url: String,
    val type: String, // RelayType
    val enabled: Boolean = true,
    val username: String? = null,
    val credential: String? = null
)

data class SenderMaxSeq(
    val senderPk: String,
    val maxSeq: Long
)
