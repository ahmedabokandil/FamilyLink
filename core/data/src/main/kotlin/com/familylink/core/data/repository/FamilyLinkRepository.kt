package com.familylink.core.data.repository

import com.familylink.core.crypto.ContactQrPayload
import com.familylink.core.crypto.GroupInviteQrPayload
import com.familylink.core.crypto.IdentityManager
import com.familylink.core.data.db.FamilyLinkDatabase
import com.familylink.core.data.db.GroupEntity
import com.familylink.core.data.db.GroupMemberEntity
import com.familylink.core.data.db.LocalIdentityEntity
import com.familylink.core.data.db.MessageEntity
import com.familylink.core.data.db.OutboxPacketEntity
import com.familylink.core.data.db.PeerEntity
import com.familylink.core.data.db.RelayConfigEntity
import com.familylink.core.data.db.RelayType
import kotlinx.coroutines.flow.Flow
import java.util.UUID

object ConversationId {
    fun direct(peerId: String): String = "dm:$peerId"
    fun group(groupId: String): String = "grp:$groupId"
    fun isGroup(conversationId: String): Boolean = conversationId.startsWith("grp:")
    fun extractId(conversationId: String): String = conversationId.substringAfter(":")
}

class FamilyLinkRepository(private val db: FamilyLinkDatabase) {

    fun observeIdentity(): Flow<LocalIdentityEntity?> = db.localIdentityDao().observeIdentity()
    suspend fun getIdentity(): LocalIdentityEntity? = db.localIdentityDao().getIdentity()

    suspend fun createIdentityIfNeeded(displayName: String): LocalIdentityEntity {
        val existing = db.localIdentityDao().getIdentity()
        if (existing != null) {
            if (displayName.isNotBlank() && displayName != existing.displayName) {
                db.localIdentityDao().updateDisplayName(displayName.trim())
                return existing.copy(displayName = displayName.trim())
            }
            return existing
        }
        val keyPair = IdentityManager.generateIdentity()
        val entity = LocalIdentityEntity(
            id = 1,
            peerId = keyPair.peerId,
            ed25519PrivateKey = keyPair.ed25519PrivateKey,
            x25519PublicKey = keyPair.x25519PublicKey,
            x25519PrivateKey = keyPair.x25519PrivateKey,
            displayName = displayName.trim().ifBlank { "Family Member" },
            createdAtMs = System.currentTimeMillis()
        )
        db.localIdentityDao().upsert(entity)
        seedDefaultRelaysIfEmpty()
        return entity
    }

    suspend fun seedDefaultRelaysIfEmpty() {
        if (db.relayConfigDao().count() > 0) return
        val defaults = listOf(
            RelayConfigEntity("wss://relay.damus.io", RelayType.RELAY, true),
            RelayConfigEntity("wss://nos.lol", RelayType.RELAY, true),
            RelayConfigEntity("wss://relay.nostr.band", RelayType.RELAY, true),
            RelayConfigEntity("stun:stun.cloudflare.com:3478", RelayType.STUN, true),
            RelayConfigEntity("stun:stun.l.google.com:19302", RelayType.STUN, true)
        )
        defaults.forEach { db.relayConfigDao().upsert(it) }
    }

    fun observePeers(): Flow<List<PeerEntity>> = db.peerDao().observeAllPeers()
    suspend fun getPeers(): List<PeerEntity> = db.peerDao().getAllPeers()
    suspend fun getPeer(peerId: String): PeerEntity? = db.peerDao().getPeer(peerId)
    fun observePeer(peerId: String): Flow<PeerEntity?> = db.peerDao().observePeer(peerId)

    suspend fun upsertPeer(peer: PeerEntity) = db.peerDao().upsert(peer)

    suspend fun addContactFromQr(payload: ContactQrPayload): PeerEntity {
        val lanParts = payload.lanHint?.split(":")
        val host = lanParts?.getOrNull(0)?.takeIf { it.isNotBlank() }
        val port = lanParts?.getOrNull(1)?.toIntOrNull()
        val existing = db.peerDao().getPeer(payload.peerId)
        val peer = PeerEntity(
            peerId = payload.peerId,
            x25519PublicKey = payload.x25519PublicKey,
            displayName = payload.displayName,
            lastLanHost = host ?: existing?.lastLanHost,
            lastLanPort = port ?: existing?.lastLanPort,
            lastSeenMs = maxOf(existing?.lastSeenMs ?: 0L, System.currentTimeMillis()),
            verifiedViaQr = true
        )
        db.peerDao().upsert(peer)
        payload.relays.forEach { url ->
            if (url.startsWith("wss://") || url.startsWith("ws://")) {
                db.relayConfigDao().upsert(RelayConfigEntity(url, RelayType.RELAY, true))
            }
        }
        return peer
    }

    suspend fun updatePeerEndpoint(peerId: String, host: String?, port: Int?) {
        db.peerDao().updateEndpoint(peerId, host, port, System.currentTimeMillis())
    }

    suspend fun touchPeerSeen(peerId: String) {
        db.peerDao().updateLastSeen(peerId, System.currentTimeMillis())
    }

    // Groups
    fun observeGroups(): Flow<List<GroupEntity>> = db.groupDao().observeAllGroups()
    suspend fun getGroups(): List<GroupEntity> = db.groupDao().getAllGroups()
    suspend fun getGroup(groupId: String): GroupEntity? = db.groupDao().getGroup(groupId)
    fun observeGroup(groupId: String): Flow<GroupEntity?> = db.groupDao().observeGroup(groupId)
    fun observeGroupMembers(groupId: String): Flow<List<GroupMemberEntity>> = db.groupDao().observeMembers(groupId)
    suspend fun getGroupMembers(groupId: String): List<GroupMemberEntity> = db.groupDao().getMembers(groupId)

    suspend fun createFamilyGroup(name: String, initialMemberPeerIds: List<String> = emptyList()): GroupEntity {
        val me = requireNotNull(getIdentity()) { "Local identity must be initialized first" }
        val now = System.currentTimeMillis()
        val group = GroupEntity(
            groupId = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "My Family" },
            adminPeerId = me.peerId,
            epoch = 1,
            groupKeyBase64 = IdentityManager.generateGroupKeyBase64(),
            createdAtMs = now
        )
        db.groupDao().upsertGroup(group)
        db.groupDao().upsertMember(
            GroupMemberEntity(
                groupId = group.groupId,
                peerId = me.peerId,
                displayName = me.displayName,
                role = "ADMIN",
                joinedAtMs = now
            )
        )
        initialMemberPeerIds.distinct().filter { it != me.peerId }.forEach { peerId ->
            val peer = db.peerDao().getPeer(peerId)
            if (peer != null) {
                db.groupDao().upsertMember(
                    GroupMemberEntity(
                        groupId = group.groupId,
                        peerId = peer.peerId,
                        displayName = peer.displayName,
                        role = "MEMBER",
                        joinedAtMs = now
                    )
                )
            }
        }
        return group
    }

    suspend fun joinGroupFromQr(payload: GroupInviteQrPayload): GroupEntity {
        val me = requireNotNull(getIdentity()) { "Local identity must be initialized first" }
        val now = System.currentTimeMillis()
        val lanParts = payload.lanHint?.split(":")
        val host = lanParts?.getOrNull(0)?.takeIf { it.isNotBlank() }
        val port = lanParts?.getOrNull(1)?.toIntOrNull()

        if (payload.adminPeerId != me.peerId) {
            db.peerDao().upsert(
                PeerEntity(
                    peerId = payload.adminPeerId,
                    x25519PublicKey = payload.adminX25519PublicKey,
                    displayName = payload.adminDisplayName,
                    lastLanHost = host,
                    lastLanPort = port,
                    lastSeenMs = now,
                    verifiedViaQr = true
                )
            )
        }

        val group = GroupEntity(
            groupId = payload.groupId,
            name = payload.groupName,
            adminPeerId = payload.adminPeerId,
            epoch = payload.epoch,
            groupKeyBase64 = payload.groupKeyBase64,
            createdAtMs = now
        )
        db.groupDao().upsertGroup(group)
        db.groupDao().upsertMember(
            GroupMemberEntity(
                groupId = payload.groupId,
                peerId = payload.adminPeerId,
                displayName = payload.adminDisplayName,
                role = "ADMIN",
                joinedAtMs = now
            )
        )
        db.groupDao().upsertMember(
            GroupMemberEntity(
                groupId = payload.groupId,
                peerId = me.peerId,
                displayName = me.displayName,
                role = if (me.peerId == payload.adminPeerId) "ADMIN" else "MEMBER",
                joinedAtMs = now
            )
        )
        payload.relays.forEach { url ->
            if (url.startsWith("wss://") || url.startsWith("ws://")) {
                db.relayConfigDao().upsert(RelayConfigEntity(url, RelayType.RELAY, true))
            }
        }
        return group
    }

    suspend fun upsertGroupMember(member: GroupMemberEntity) = db.groupDao().upsertMember(member)

    suspend fun removeGroupMemberAndRotateKey(groupId: String, targetPeerId: String): GroupEntity? {
        val group = db.groupDao().getGroup(groupId) ?: return null
        db.groupDao().removeMember(groupId, targetPeerId)
        val newEpoch = group.epoch + 1
        val newKey = IdentityManager.generateGroupKeyBase64()
        db.groupDao().updateGroupKey(groupId, newEpoch, newKey)
        return group.copy(epoch = newEpoch, groupKeyBase64 = newKey)
    }

    suspend fun applyRotatedGroupKey(groupId: String, newEpoch: Int, newKeyBase64: String) {
        val current = db.groupDao().getGroup(groupId) ?: return
        if (newEpoch > current.epoch) {
            db.groupDao().updateGroupKey(groupId, newEpoch, newKeyBase64)
        }
    }

    // Messages & Vector Clock
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeMessages(conversationId)

    fun observeLastMessage(conversationId: String): Flow<MessageEntity?> =
        db.messageDao().observeLastMessage(conversationId)

    suspend fun nextSeq(conversationId: String, senderPk: String): Long =
        (db.messageDao().getMaxSeqForSender(conversationId, senderPk) ?: 0L) + 1L

    suspend fun insertMessageIfNew(message: MessageEntity): Boolean =
        db.messageDao().insertIgnore(message) != -1L

    suspend fun updateMessageStatus(packetId: String, status: String) =
        db.messageDao().updateStatus(packetId, status)

    suspend fun updateMessageMediaPath(fileId: String, mediaPath: String) =
        db.messageDao().updateMediaPathByFileId(fileId, mediaPath)

    // Reactions
    fun observeReactions(conversationId: String): Flow<List<com.familylink.core.data.db.ReactionEntity>> =
        db.reactionDao().observeForConversation(conversationId)

    suspend fun upsertReaction(reaction: com.familylink.core.data.db.ReactionEntity) =
        db.reactionDao().upsert(reaction)

    suspend fun removeReaction(targetPacketId: String, senderPk: String, emoji: String) =
        db.reactionDao().remove(targetPacketId, senderPk, emoji)

    // Encrypted Backup & Recovery
    suspend fun exportEncryptedBackup(passphrase: String): String {
        val me = requireNotNull(getIdentity()) { "No identity to export" }
        val peers = getPeers().map {
            com.familylink.core.crypto.BackupPeerRecord(
                peerId = it.peerId,
                x25519PublicKey = it.x25519PublicKey,
                displayName = it.displayName,
                verifiedViaQr = it.verifiedViaQr
            )
        }
        val groups = getGroups().map { grp ->
            val members = getGroupMembers(grp.groupId).map { it.peerId }
            com.familylink.core.crypto.BackupGroupRecord(
                groupId = grp.groupId,
                name = grp.name,
                adminPeerId = grp.adminPeerId,
                epoch = grp.epoch,
                groupKeyBase64 = grp.groupKeyBase64,
                memberPeerIds = members
            )
        }
        val bundle = com.familylink.core.crypto.BackupBundle(
            exportedAtMs = System.currentTimeMillis(),
            displayName = me.displayName,
            identity = me.toKeyPair(),
            peers = peers,
            groups = groups,
            relayUrls = getEnabledRelayUrls()
        )
        return com.familylink.core.crypto.BackupCrypto.encryptBackup(bundle, passphrase)
    }

    suspend fun importEncryptedBackup(encryptedJson: String, passphrase: String): LocalIdentityEntity {
        val bundle = com.familylink.core.crypto.BackupCrypto.decryptBackup(encryptedJson, passphrase)
        val now = System.currentTimeMillis()
        val identity = LocalIdentityEntity(
            id = 1,
            peerId = bundle.identity.peerId,
            ed25519PrivateKey = bundle.identity.ed25519PrivateKey,
            x25519PublicKey = bundle.identity.x25519PublicKey,
            x25519PrivateKey = bundle.identity.x25519PrivateKey,
            displayName = bundle.displayName,
            createdAtMs = now
        )
        db.localIdentityDao().upsert(identity)
        bundle.peers.forEach { p ->
            db.peerDao().upsert(
                PeerEntity(
                    peerId = p.peerId,
                    x25519PublicKey = p.x25519PublicKey,
                    displayName = p.displayName,
                    verifiedViaQr = p.verifiedViaQr,
                    lastSeenMs = now
                )
            )
        }
        bundle.groups.forEach { g ->
            db.groupDao().upsertGroup(
                GroupEntity(
                    groupId = g.groupId,
                    name = g.name,
                    adminPeerId = g.adminPeerId,
                    epoch = g.epoch,
                    groupKeyBase64 = g.groupKeyBase64,
                    createdAtMs = now
                )
            )
            g.memberPeerIds.forEach { pid ->
                val name = if (pid == identity.peerId) identity.displayName
                else bundle.peers.find { it.peerId == pid }?.displayName ?: pid.take(8)
                db.groupDao().upsertMember(
                    GroupMemberEntity(
                        groupId = g.groupId,
                        peerId = pid,
                        displayName = name,
                        role = if (pid == g.adminPeerId) "ADMIN" else "MEMBER",
                        joinedAtMs = now
                    )
                )
            }
        }
        bundle.relayUrls.forEach { url ->
            db.relayConfigDao().upsert(RelayConfigEntity(url, RelayType.RELAY, true))
        }
        seedDefaultRelaysIfEmpty()
        return identity
    }

    suspend fun restoreIdentityFromPhrase(displayName: String, phrase: String): LocalIdentityEntity {
        val kp = com.familylink.core.crypto.BackupCrypto.recoverIdentityFromPhrase(phrase)
        val entity = LocalIdentityEntity(
            id = 1,
            peerId = kp.peerId,
            ed25519PrivateKey = kp.ed25519PrivateKey,
            x25519PublicKey = kp.x25519PublicKey,
            x25519PrivateKey = kp.x25519PrivateKey,
            displayName = displayName.trim().ifBlank { "Family Member" },
            createdAtMs = System.currentTimeMillis()
        )
        db.localIdentityDao().upsert(entity)
        seedDefaultRelaysIfEmpty()
        return entity
    }

    suspend fun getVectorClock(conversationId: String): Map<String, Long> =
        db.messageDao().getVectorClock(conversationId).associate { it.senderPk to it.maxSeq }

    suspend fun getMessagesAfterSeq(
        conversationId: String,
        senderPk: String,
        afterSeq: Long,
        limit: Int = 50
    ): List<MessageEntity> = db.messageDao().getMessagesAfterSeq(conversationId, senderPk, afterSeq, limit)

    // Outbox
    suspend fun enqueueOutbox(packet: OutboxPacketEntity) = db.outboxDao().enqueue(packet)
    suspend fun getPendingOutboxForPeer(peerId: String): List<OutboxPacketEntity> =
        db.outboxDao().getPendingForPeer(peerId)
    suspend fun getDueOutbox(nowMs: Long): List<OutboxPacketEntity> = db.outboxDao().getDuePackets(nowMs)
    suspend fun removeOutbox(packetId: String, peerId: String) = db.outboxDao().delete(packetId, peerId)
    suspend fun markOutboxRetry(packetId: String, peerId: String, nextAttemptMs: Long) =
        db.outboxDao().markRetry(packetId, peerId, nextAttemptMs)

    // Relays
    fun observeRelays(): Flow<List<RelayConfigEntity>> = db.relayConfigDao().observeAll()
    suspend fun getEnabledRelayUrls(): List<String> =
        db.relayConfigDao().getEnabledByType(RelayType.RELAY).map { it.url }
    suspend fun getEnabledIceServers(): List<RelayConfigEntity> =
        db.relayConfigDao().getEnabledByType(RelayType.STUN) +
            db.relayConfigDao().getEnabledByType(RelayType.TURN)
    suspend fun upsertRelay(config: RelayConfigEntity) = db.relayConfigDao().upsert(config)
    suspend fun deleteRelay(url: String) = db.relayConfigDao().delete(url)
}
