package com.familylink.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalIdentityDao {
    @Query("SELECT * FROM local_identity WHERE id = 1")
    fun observeIdentity(): Flow<LocalIdentityEntity?>

    @Query("SELECT * FROM local_identity WHERE id = 1")
    suspend fun getIdentity(): LocalIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: LocalIdentityEntity)

    @Query("UPDATE local_identity SET displayName = :displayName WHERE id = 1")
    suspend fun updateDisplayName(displayName: String)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY displayName ASC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getAllPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    suspend fun getPeer(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    fun observePeer(peerId: String): Flow<PeerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET lastLanHost = :host, lastLanPort = :port, lastSeenMs = :seenMs WHERE peerId = :peerId")
    suspend fun updateEndpoint(peerId: String, host: String?, port: Int?, seenMs: Long)

    @Query("UPDATE peers SET lastSeenMs = :seenMs WHERE peerId = :peerId")
    suspend fun updateLastSeen(peerId: String, seenMs: Long)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAtMs DESC")
    fun observeAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun observeGroup(groupId: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("UPDATE groups SET epoch = :newEpoch, groupKeyBase64 = :newKey WHERE groupId = :groupId")
    suspend fun updateGroupKey(groupId: String, newEpoch: Int, newKey: String)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY joinedAtMs ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND peerId = :peerId")
    suspend fun removeMember(groupId: String, peerId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs ASC, seq ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs DESC LIMIT 1")
    fun observeLastMessage(conversationId: String): Flow<MessageEntity?>

    @Query("SELECT MAX(seq) FROM messages WHERE conversationId = :conversationId AND senderPk = :senderPk")
    suspend fun getMaxSeqForSender(conversationId: String, senderPk: String): Long?

    @Query(
        "SELECT senderPk, MAX(seq) AS maxSeq FROM messages " +
            "WHERE conversationId = :conversationId AND rawEnvelopeJson IS NOT NULL GROUP BY senderPk"
    )
    suspend fun getVectorClock(conversationId: String): List<SenderMaxSeq>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND senderPk = :senderPk AND seq > :afterSeq AND rawEnvelopeJson IS NOT NULL " +
            "ORDER BY seq ASC LIMIT :limit"
    )
    suspend fun getMessagesAfterSeq(
        conversationId: String,
        senderPk: String,
        afterSeq: Long,
        limit: Int = 50
    ): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE packetId = :packetId")
    suspend fun exists(packetId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    suspend fun updateStatus(packetId: String, status: String)

    @Query("UPDATE messages SET mediaPath = :mediaPath WHERE fileId = :fileId")
    suspend fun updateMediaPathByFileId(fileId: String, mediaPath: String)
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE conversationId = :conversationId")
    fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE targetPacketId = :targetPacketId AND senderPk = :senderPk AND emoji = :emoji")
    suspend fun remove(targetPacketId: String, senderPk: String, emoji: String)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(packet: OutboxPacketEntity)

    @Query("SELECT * FROM outbox_packets WHERE targetPeerPk = :peerId ORDER BY createdAtMs ASC LIMIT :limit")
    suspend fun getPendingForPeer(peerId: String, limit: Int = 100): List<OutboxPacketEntity>

    @Query("SELECT * FROM outbox_packets WHERE nextAttemptMs <= :nowMs ORDER BY createdAtMs ASC LIMIT :limit")
    suspend fun getDuePackets(nowMs: Long, limit: Int = 100): List<OutboxPacketEntity>

    @Query("DELETE FROM outbox_packets WHERE packetId = :packetId AND targetPeerPk = :peerId")
    suspend fun delete(packetId: String, peerId: String)

    @Query(
        "UPDATE outbox_packets SET retryCount = retryCount + 1, nextAttemptMs = :nextAttemptMs " +
            "WHERE packetId = :packetId AND targetPeerPk = :peerId"
    )
    suspend fun markRetry(packetId: String, peerId: String, nextAttemptMs: Long)
}

@Dao
interface RelayConfigDao {
    @Query("SELECT * FROM relay_configs ORDER BY type ASC, url ASC")
    fun observeAll(): Flow<List<RelayConfigEntity>>

    @Query("SELECT * FROM relay_configs WHERE type = :type AND enabled = 1")
    suspend fun getEnabledByType(type: String): List<RelayConfigEntity>

    @Query("SELECT COUNT(*) FROM relay_configs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: RelayConfigEntity)

    @Query("DELETE FROM relay_configs WHERE url = :url")
    suspend fun delete(url: String)
}
