package com.familylink.core.p2p

import android.util.Log
import com.familylink.core.crypto.ChatMessagePayload
import com.familylink.core.crypto.EnvelopeCipher
import com.familylink.core.crypto.FileChunkPayload
import com.familylink.core.crypto.FileChunkReqPayload
import com.familylink.core.crypto.FileMetaPayload
import com.familylink.core.crypto.GroupEventAction
import com.familylink.core.crypto.GroupEventPayload
import com.familylink.core.crypto.PacketEnvelope
import com.familylink.core.crypto.PacketType
import com.familylink.core.crypto.PeerHelloPayload
import com.familylink.core.crypto.ReactionPayload
import com.familylink.core.crypto.ReceiptPayload
import com.familylink.core.crypto.ReceiptType
import com.familylink.core.crypto.SyncBatchPayload
import com.familylink.core.crypto.SyncDigestPayload
import com.familylink.core.crypto.TypingPayload
import com.familylink.core.data.db.GroupMemberEntity
import com.familylink.core.data.db.MessageEntity
import com.familylink.core.data.db.MessageStatus
import com.familylink.core.data.db.OutboxPacketEntity
import com.familylink.core.data.db.PeerEntity
import com.familylink.core.data.db.ReactionEntity
import com.familylink.core.data.repository.ConversationId
import com.familylink.core.data.repository.FamilyLinkRepository
import com.familylink.core.p2p.router.HybridTransportRouter
import com.familylink.core.p2p.router.OutboxStore
import com.familylink.core.p2p.router.QueuedOutboxItem
import com.familylink.core.p2p.sync.GossipSyncEngine
import com.familylink.core.p2p.transfer.ChunkedFileTransferManager
import com.familylink.core.p2p.transport.DecentralizedRelayTransport
import com.familylink.core.p2p.transport.IncomingPacket
import com.familylink.core.p2p.transport.LanNsdTransport
import com.familylink.core.p2p.transport.TransportKind
import com.familylink.core.p2p.transport.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class IncomingCallSignal(
    val senderPk: String,
    val groupId: String?,
    val payloadJson: String
)

/**
 * Central orchestrator connecting encrypted Room persistence, HybridTransportRouter,
 * E2EE EnvelopeCipher, GossipSyncEngine, ChunkedFileTransferManager, and WebRTC signaling.
 */
class P2pCoordinator(
    private val repository: FamilyLinkRepository,
    val lanTransport: LanNsdTransport,
    val wifiDirectTransport: WifiDirectTransport,
    val relayTransport: DecentralizedRelayTransport,
    mediaDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val fileTransferManager = ChunkedFileTransferManager(mediaDir)

    private val outboxStore = object : OutboxStore {
        override suspend fun enqueue(item: QueuedOutboxItem) {
            repository.enqueueOutbox(
                OutboxPacketEntity(
                    packetId = item.packetId,
                    targetPeerPk = item.targetPeerPk,
                    envelopeJson = item.envelopeJson,
                    retryCount = item.retryCount,
                    nextAttemptMs = System.currentTimeMillis() + 3_000L,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }

        override suspend fun getPendingForPeer(peerId: String): List<QueuedOutboxItem> =
            repository.getPendingOutboxForPeer(peerId).map {
                QueuedOutboxItem(it.packetId, it.targetPeerPk, it.envelopeJson, it.retryCount)
            }

        override suspend fun remove(packetId: String, peerId: String) {
            repository.removeOutbox(packetId, peerId)
        }

        override suspend fun markRetry(packetId: String, peerId: String, nextAttemptMs: Long) {
            repository.markOutboxRetry(packetId, peerId, nextAttemptMs)
        }
    }

    val router = HybridTransportRouter(
        transports = listOf(lanTransport, wifiDirectTransport, relayTransport),
        outboxStore = outboxStore,
        scope = scope,
        json = json
    )

    val peerTransports: StateFlow<Map<String, TransportKind>> get() = router.peerBestTransport
    val connectedRelays: StateFlow<Set<String>> get() = relayTransport.connectedRelays
    val transferProgress: StateFlow<Map<String, Float>> get() = fileTransferManager.transferProgress

    // Map of conversationId -> Set of peer display names currently typing
    private val _typingIndicators = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typingIndicators: StateFlow<Map<String, Set<String>>> = _typingIndicators.asStateFlow()

    // Emits decrypted CALL_SIGNAL payloads for Phase 3 WebRTC MeshCallController
    private val _incomingCallSignals = MutableSharedFlow<IncomingCallSignal>(extraBufferCapacity = 128)
    val incomingCallSignals: SharedFlow<IncomingCallSignal> = _incomingCallSignals.asSharedFlow()

    private var started = false
    private val jobs = mutableListOf<Job>()

    suspend fun start() {
        if (started) return
        started = true

        repository.getPeers().forEach { peer ->
            val host = peer.lastLanHost
            val port = peer.lastLanPort
            if (!host.isNullOrBlank() && port != null && port > 0) {
                lanTransport.registerPeerHint(peer.peerId, host, port)
            }
        }

        router.start()

        jobs += scope.launch {
            router.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
        jobs += scope.launch {
            router.peerReconnected.collect { peerId ->
                sendPeerHello(peerId)
                sendGroupDigestsToPeer(peerId)
            }
        }
    }

    suspend fun stop() {
        started = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        router.stop()
    }

    suspend fun refreshRelays() {
        relayTransport.refreshRelays()
    }

    suspend fun sendPeerHello(peerId: String) {
        val me = repository.getIdentity() ?: return
        val peer = repository.getPeer(peerId) ?: return
        val lanHint = lanTransport.getLocalLanHint()?.split(":")
        val payload = PeerHelloPayload(
            displayName = me.displayName,
            x25519PublicKey = me.x25519PublicKey,
            lanHost = lanHint?.getOrNull(0),
            lanPort = lanHint?.getOrNull(1)?.toIntOrNull(),
            relays = repository.getEnabledRelayUrls()
        )
        val env = EnvelopeCipher.sealDirect(
            sender = me.toKeyPair(),
            recipientPk = peer.peerId,
            recipientXk = peer.x25519PublicKey,
            type = PacketType.PEER_HELLO,
            plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        )
        router.send(peer.peerId, env, queueIfOffline = true)
    }

    suspend fun sendDirectTextMessage(peerId: String, text: String): MessageEntity? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val me = repository.getIdentity() ?: return null
        val peer = repository.getPeer(peerId) ?: return null

        val convId = ConversationId.direct(peerId)
        val seq = repository.nextSeq(convId, me.peerId)
        val payload = ChatMessagePayload(text = trimmed)
        val env = EnvelopeCipher.sealDirect(
            sender = me.toKeyPair(),
            recipientPk = peer.peerId,
            recipientXk = peer.x25519PublicKey,
            type = PacketType.CHAT_MSG,
            plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8),
            seq = seq
        )
        val envJson = json.encodeToString(env)
        val msg = MessageEntity(
            packetId = env.packetId,
            conversationId = convId,
            senderPk = me.peerId,
            senderName = me.displayName,
            seq = seq,
            type = PacketType.CHAT_MSG.name,
            bodyText = trimmed,
            status = MessageStatus.QUEUED,
            isOutgoing = true,
            timestampMs = env.timestampMs,
            rawEnvelopeJson = envJson
        )
        repository.insertMessageIfNew(msg)

        val deliveredVia = router.send(peer.peerId, env, queueIfOffline = true)
        if (deliveredVia != null) {
            repository.updateMessageStatus(env.packetId, MessageStatus.SENT)
        }
        return msg
    }

    suspend fun sendGroupTextMessage(groupId: String, text: String): MessageEntity? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val me = repository.getIdentity() ?: return null
        val group = repository.getGroup(groupId) ?: return null
        val members = repository.getGroupMembers(groupId).filter { it.peerId != me.peerId }

        val convId = ConversationId.group(groupId)
        val seq = repository.nextSeq(convId, me.peerId)
        val payload = ChatMessagePayload(text = trimmed)
        val env = EnvelopeCipher.sealGroup(
            sender = me.toKeyPair(),
            groupId = groupId,
            groupKeyBase64 = group.groupKeyBase64,
            epoch = group.epoch,
            type = PacketType.CHAT_MSG,
            plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8),
            seq = seq
        )
        val envJson = json.encodeToString(env)
        val msg = MessageEntity(
            packetId = env.packetId,
            conversationId = convId,
            senderPk = me.peerId,
            senderName = me.displayName,
            seq = seq,
            type = PacketType.CHAT_MSG.name,
            bodyText = trimmed,
            status = MessageStatus.QUEUED,
            isOutgoing = true,
            timestampMs = env.timestampMs,
            rawEnvelopeJson = envJson
        )
        repository.insertMessageIfNew(msg)

        var anyDelivered = false
        for (member in members) {
            if (router.send(member.peerId, env, queueIfOffline = true) != null) {
                anyDelivered = true
            }
        }
        if (anyDelivered) {
            repository.updateMessageStatus(env.packetId, MessageStatus.SENT)
        }
        return msg
    }

    /**
     * Encrypts and streams a photo, video, voice note, or generic file as 64 KB content-addressed chunks.
     */
    suspend fun sendAttachment(
        conversationId: String,
        localFile: File,
        fileName: String,
        mimeType: String,
        isVoiceNote: Boolean = false,
        durationMs: Long = 0L
    ): MessageEntity? {
        val me = repository.getIdentity() ?: return null
        val prepared = fileTransferManager.prepareOutgoingFile(
            sourceFile = localFile,
            conversationId = conversationId,
            fileName = fileName,
            mimeType = mimeType,
            isVoiceNote = isVoiceNote,
            durationMs = durationMs
        )
        val seq = repository.nextSeq(conversationId, me.peerId)
        val metaJson = json.encodeToString(prepared.meta).toByteArray(Charsets.UTF_8)
        val isGroup = ConversationId.isGroup(conversationId)
        val targetId = ConversationId.extractId(conversationId)

        val metaEnv = if (isGroup) {
            val group = repository.getGroup(targetId) ?: return null
            EnvelopeCipher.sealGroup(
                sender = me.toKeyPair(),
                groupId = group.groupId,
                groupKeyBase64 = group.groupKeyBase64,
                epoch = group.epoch,
                type = PacketType.FILE_META,
                plaintext = metaJson,
                seq = seq
            )
        } else {
            val peer = repository.getPeer(targetId) ?: return null
            EnvelopeCipher.sealDirect(
                sender = me.toKeyPair(),
                recipientPk = peer.peerId,
                recipientXk = peer.x25519PublicKey,
                type = PacketType.FILE_META,
                plaintext = metaJson,
                seq = seq
            )
        }

        val label = when {
            isVoiceNote -> "Voice note (${durationMs / 1000}s)"
            mimeType.startsWith("image/") -> "Photo: $fileName"
            mimeType.startsWith("video/") -> "Video: $fileName"
            else -> "File: $fileName"
        }

        val msg = MessageEntity(
            packetId = metaEnv.packetId,
            conversationId = conversationId,
            senderPk = me.peerId,
            senderName = me.displayName,
            seq = seq,
            type = PacketType.FILE_META.name,
            bodyText = label,
            mediaPath = localFile.absolutePath,
            fileId = prepared.meta.fileId,
            mediaMime = mimeType,
            mediaName = fileName,
            mediaDurationMs = durationMs,
            status = MessageStatus.QUEUED,
            isOutgoing = true,
            timestampMs = metaEnv.timestampMs,
            rawEnvelopeJson = json.encodeToString(metaEnv)
        )
        repository.insertMessageIfNew(msg)

        val recipients = resolveRecipients(conversationId, me.peerId)
        var anyDelivered = false
        for (peerId in recipients) {
            if (router.send(peerId, metaEnv, queueIfOffline = true) != null) {
                anyDelivered = true
            }
        }
        if (anyDelivered) {
            repository.updateMessageStatus(metaEnv.packetId, MessageStatus.SENT)
        }

        // Stream chunks asynchronously so UI stays responsive
        scope.launch {
            for (chunk in prepared.chunks) {
                val chunkBytes = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
                for (peerId in recipients) {
                    val env = sealForConversationOrDirect(me, conversationId, peerId, PacketType.FILE_CHUNK, chunkBytes)
                        ?: continue
                    router.send(peerId, env, queueIfOffline = true)
                }
            }
        }
        return msg
    }

    suspend fun sendReaction(conversationId: String, targetPacketId: String, emoji: String, removed: Boolean = false) {
        val me = repository.getIdentity() ?: return
        if (removed) {
            repository.removeReaction(targetPacketId, me.peerId, emoji)
        } else {
            repository.upsertReaction(
                ReactionEntity(
                    targetPacketId = targetPacketId,
                    conversationId = conversationId,
                    senderPk = me.peerId,
                    emoji = emoji,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
        val payload = ReactionPayload(conversationId, targetPacketId, emoji, removed)
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        for (peerId in resolveRecipients(conversationId, me.peerId)) {
            val env = sealForConversationOrDirect(me, conversationId, peerId, PacketType.REACTION, bytes) ?: continue
            router.send(peerId, env, queueIfOffline = true)
        }
    }

    suspend fun sendReadReceipt(conversationId: String, targetPacketId: String, senderPeerId: String) {
        val me = repository.getIdentity() ?: return
        if (senderPeerId == me.peerId) return
        val peer = repository.getPeer(senderPeerId) ?: return
        val payload = ReceiptPayload(conversationId, targetPacketId, ReceiptType.READ)
        val env = EnvelopeCipher.sealDirect(
            sender = me.toKeyPair(),
            recipientPk = peer.peerId,
            recipientXk = peer.x25519PublicKey,
            type = PacketType.RECEIPT,
            plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        )
        router.send(peer.peerId, env, queueIfOffline = false)
    }

    suspend fun sendTypingIndicator(conversationId: String, isTyping: Boolean) {
        val me = repository.getIdentity() ?: return
        val payload = TypingPayload(conversationId, isTyping)
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        for (peerId in resolveRecipients(conversationId, me.peerId)) {
            val env = sealForConversationOrDirect(me, conversationId, peerId, PacketType.TYPING, bytes) ?: continue
            router.send(peerId, env, queueIfOffline = false)
        }
    }

    /**
     * Transports WebRTC call signaling (CALL_RING, CALL_OFFER, CALL_ANSWER, CALL_ICE, CALL_LEAVE)
     * directly over the E2EE HybridTransportRouter with zero external signaling server.
     */
    suspend fun sendCallSignal(targetPeerId: String, groupId: String?, payloadJson: String): Boolean {
        val me = repository.getIdentity() ?: return false
        val peer = repository.getPeer(targetPeerId) ?: return false
        val env = EnvelopeCipher.sealDirect(
            sender = me.toKeyPair(),
            recipientPk = peer.peerId,
            recipientXk = peer.x25519PublicKey,
            type = PacketType.CALL_SIGNAL,
            plaintext = payloadJson.toByteArray(Charsets.UTF_8)
        )
        return router.send(peer.peerId, env, queueIfOffline = false) != null
    }

    suspend fun announceGroupJoin(groupId: String) {
        val me = repository.getIdentity() ?: return
        val group = repository.getGroup(groupId) ?: return
        val members = repository.getGroupMembers(groupId).filter { it.peerId != me.peerId }
        val payload = GroupEventPayload(
            action = GroupEventAction.MEMBER_JOINED,
            targetPeerId = me.peerId,
            targetX25519Pk = me.x25519PublicKey,
            targetDisplayName = me.displayName
        )
        val env = EnvelopeCipher.sealGroup(
            sender = me.toKeyPair(),
            groupId = groupId,
            groupKeyBase64 = group.groupKeyBase64,
            epoch = group.epoch,
            type = PacketType.GROUP_EVENT,
            plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8),
            seq = 0
        )
        for (member in members) {
            router.send(member.peerId, env, queueIfOffline = true)
            sendSyncDigest(member.peerId, ConversationId.group(groupId))
        }
        relayTransport.refreshRelays()
    }

    suspend fun removeGroupMemberAndDistributeRotatedKey(groupId: String, targetPeerId: String): Boolean {
        val me = repository.getIdentity() ?: return false
        val updatedGroup = repository.removeGroupMemberAndRotateKey(groupId, targetPeerId) ?: return false
        val remainingMembers = repository.getGroupMembers(groupId).filter { it.peerId != me.peerId }

        val event = GroupEventPayload(
            action = GroupEventAction.GROUP_KEY_ROTATE,
            targetPeerId = targetPeerId,
            newEpoch = updatedGroup.epoch,
            newGroupKeyBase64 = updatedGroup.groupKeyBase64,
            memberPeerIds = remainingMembers.map { it.peerId } + me.peerId
        )

        for (member in remainingMembers) {
            val peer = repository.getPeer(member.peerId) ?: continue
            val cleanDirectEnv = EnvelopeCipher.sealDirect(
                sender = me.toKeyPair(),
                recipientPk = peer.peerId,
                recipientXk = peer.x25519PublicKey,
                type = PacketType.GROUP_EVENT,
                plaintext = json.encodeToString(
                    event.copy(newGroupName = groupId)
                ).toByteArray(Charsets.UTF_8)
            )
            router.send(peer.peerId, cleanDirectEnv, queueIfOffline = true)
        }
        return true
    }

    private suspend fun resolveRecipients(conversationId: String, myPeerId: String): List<String> {
        return if (ConversationId.isGroup(conversationId)) {
            repository.getGroupMembers(ConversationId.extractId(conversationId))
                .map { it.peerId }
                .filter { it != myPeerId }
        } else {
            listOf(ConversationId.extractId(conversationId))
        }
    }

    private suspend fun sealForConversationOrDirect(
        me: com.familylink.core.data.db.LocalIdentityEntity,
        conversationId: String,
        targetPeerId: String,
        type: PacketType,
        plaintext: ByteArray
    ): PacketEnvelope? {
        return if (ConversationId.isGroup(conversationId)) {
            val group = repository.getGroup(ConversationId.extractId(conversationId)) ?: return null
            EnvelopeCipher.sealGroup(
                sender = me.toKeyPair(),
                groupId = group.groupId,
                groupKeyBase64 = group.groupKeyBase64,
                epoch = group.epoch,
                type = type,
                plaintext = plaintext,
                seq = 0
            )
        } else {
            val peer = repository.getPeer(targetPeerId) ?: return null
            EnvelopeCipher.sealDirect(
                sender = me.toKeyPair(),
                recipientPk = peer.peerId,
                recipientXk = peer.x25519PublicKey,
                type = type,
                plaintext = plaintext
            )
        }
    }

    private suspend fun sendGroupDigestsToPeer(peerId: String) {
        val groups = repository.getGroups()
        for (group in groups) {
            val members = repository.getGroupMembers(group.groupId)
            if (members.any { it.peerId == peerId }) {
                sendSyncDigest(peerId, ConversationId.group(group.groupId))
            }
        }
        sendSyncDigest(peerId, ConversationId.direct(peerId))
    }

    private suspend fun sendSyncDigest(peerId: String, conversationId: String) {
        val me = repository.getIdentity() ?: return
        val peer = repository.getPeer(peerId) ?: return
        val clock = repository.getVectorClock(conversationId)
        val digest = GossipSyncEngine.buildDigest(conversationId, clock)
        val env = EnvelopeCipher.sealDirect(
            sender = me.toKeyPair(),
            recipientPk = peer.peerId,
            recipientXk = peer.x25519PublicKey,
            type = PacketType.SYNC_DIGEST,
            plaintext = json.encodeToString(digest).toByteArray(Charsets.UTF_8)
        )
        router.send(peer.peerId, env, queueIfOffline = false)
    }

    private suspend fun handleIncomingPacket(incoming: IncomingPacket) {
        val me = repository.getIdentity() ?: return
        val env = incoming.envelope
        if (env.senderPk == me.peerId) return

        repository.touchPeerSeen(env.senderPk)

        try {
            val plaintextBytes = if (env.groupId != null) {
                val group = repository.getGroup(env.groupId!!) ?: return
                EnvelopeCipher.openGroup(group.groupKeyBase64, env)
            } else {
                EnvelopeCipher.openDirect(me.toKeyPair(), env)
            }
            val plaintext = String(plaintextBytes, Charsets.UTF_8)

            when (env.type) {
                PacketType.PEER_HELLO, PacketType.PEER_ACK -> handlePeerHello(env, plaintext, incoming)
                PacketType.CHAT_MSG -> handleChatMessage(env, plaintext)
                PacketType.REACTION -> handleReaction(env, plaintext)
                PacketType.RECEIPT -> handleReceipt(plaintext)
                PacketType.TYPING -> handleTyping(env, plaintext)
                PacketType.FILE_META -> handleFileMeta(env, plaintext)
                PacketType.FILE_CHUNK -> handleFileChunk(plaintext)
                PacketType.FILE_CHUNK_REQ -> handleFileChunkReq(env, plaintext)
                PacketType.GROUP_EVENT -> handleGroupEvent(env, plaintext)
                PacketType.SYNC_DIGEST -> handleSyncDigest(env, plaintext)
                PacketType.SYNC_BATCH -> handleSyncBatch(plaintext)
                PacketType.CALL_SIGNAL -> _incomingCallSignals.emit(
                    IncomingCallSignal(
                        senderPk = env.senderPk,
                        groupId = env.groupId,
                        payloadJson = plaintext
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process incoming packet ${env.packetId}: ${e.message}")
        }
    }

    private suspend fun handlePeerHello(env: PacketEnvelope, plaintext: String, incoming: IncomingPacket) {
        val hello = json.decodeFromString<PeerHelloPayload>(plaintext)
        val existing = repository.getPeer(env.senderPk)
        val lanHost = hello.lanHost ?: incoming.sourceHost ?: existing?.lastLanHost
        val lanPort = hello.lanPort ?: existing?.lastLanPort
        repository.upsertPeer(
            PeerEntity(
                peerId = env.senderPk,
                x25519PublicKey = hello.x25519PublicKey,
                displayName = hello.displayName,
                lastLanHost = lanHost,
                lastLanPort = lanPort,
                lastSeenMs = System.currentTimeMillis(),
                verifiedViaQr = existing?.verifiedViaQr ?: false
            )
        )
        if (!lanHost.isNullOrBlank() && lanPort != null && lanPort > 0) {
            lanTransport.registerPeerHint(env.senderPk, lanHost, lanPort)
        }
        if (env.type == PacketType.PEER_HELLO) {
            val me = repository.getIdentity() ?: return
            val myLan = lanTransport.getLocalLanHint()?.split(":")
            val ackPayload = PeerHelloPayload(
                displayName = me.displayName,
                x25519PublicKey = me.x25519PublicKey,
                lanHost = myLan?.getOrNull(0),
                lanPort = myLan?.getOrNull(1)?.toIntOrNull(),
                relays = repository.getEnabledRelayUrls()
            )
            val ackEnv = EnvelopeCipher.sealDirect(
                sender = me.toKeyPair(),
                recipientPk = env.senderPk,
                recipientXk = hello.x25519PublicKey,
                type = PacketType.PEER_ACK,
                plaintext = json.encodeToString(ackPayload).toByteArray(Charsets.UTF_8)
            )
            router.send(env.senderPk, ackEnv, queueIfOffline = false)
        }
    }

    private suspend fun handleChatMessage(env: PacketEnvelope, plaintext: String) {
        val payload = json.decodeFromString<ChatMessagePayload>(plaintext)
        val convId = if (env.groupId != null) {
            ConversationId.group(env.groupId!!)
        } else {
            ConversationId.direct(env.senderPk)
        }
        val senderName = repository.getPeer(env.senderPk)?.displayName ?: env.senderPk.take(8)
        val inserted = repository.insertMessageIfNew(
            MessageEntity(
                packetId = env.packetId,
                conversationId = convId,
                senderPk = env.senderPk,
                senderName = senderName,
                seq = env.seq,
                type = PacketType.CHAT_MSG.name,
                bodyText = payload.text,
                status = MessageStatus.DELIVERED,
                isOutgoing = false,
                timestampMs = env.timestampMs,
                rawEnvelopeJson = json.encodeToString(env)
            )
        )
        if (inserted) {
            // Send automatic DELIVERED receipt back to sender
            val me = repository.getIdentity() ?: return
            val peer = repository.getPeer(env.senderPk) ?: return
            val receipt = ReceiptPayload(convId, env.packetId, ReceiptType.DELIVERED)
            val recEnv = EnvelopeCipher.sealDirect(
                sender = me.toKeyPair(),
                recipientPk = peer.peerId,
                recipientXk = peer.x25519PublicKey,
                type = PacketType.RECEIPT,
                plaintext = json.encodeToString(receipt).toByteArray(Charsets.UTF_8)
            )
            router.send(peer.peerId, recEnv, queueIfOffline = false)
        }
    }

    private suspend fun handleReaction(env: PacketEnvelope, plaintext: String) {
        val r = json.decodeFromString<ReactionPayload>(plaintext)
        val convId = if (env.groupId != null) ConversationId.group(env.groupId!!) else ConversationId.direct(env.senderPk)
        if (r.removed) {
            repository.removeReaction(r.targetPacketId, env.senderPk, r.emoji)
        } else {
            repository.upsertReaction(
                ReactionEntity(
                    targetPacketId = r.targetPacketId,
                    conversationId = convId,
                    senderPk = env.senderPk,
                    emoji = r.emoji,
                    timestampMs = env.timestampMs
                )
            )
        }
    }

    private suspend fun handleReceipt(plaintext: String) {
        val r = json.decodeFromString<ReceiptPayload>(plaintext)
        val status = if (r.receiptType == ReceiptType.READ) MessageStatus.READ else MessageStatus.DELIVERED
        repository.updateMessageStatus(r.targetPacketId, status)
    }

    private suspend fun handleTyping(env: PacketEnvelope, plaintext: String) {
        val t = json.decodeFromString<TypingPayload>(plaintext)
        val convId = if (env.groupId != null) ConversationId.group(env.groupId!!) else ConversationId.direct(env.senderPk)
        val senderName = repository.getPeer(env.senderPk)?.displayName ?: env.senderPk.take(8)
        _typingIndicators.update { current ->
            val set = current[convId].orEmpty().toMutableSet()
            if (t.isTyping) set.add(senderName) else set.remove(senderName)
            current + (convId to set)
        }
        if (t.isTyping) {
            scope.launch {
                delay(4_500L)
                _typingIndicators.update { current ->
                    val set = current[convId].orEmpty() - senderName
                    current + (convId to set)
                }
            }
        }
    }

    private suspend fun handleFileMeta(env: PacketEnvelope, plaintext: String) {
        val meta = json.decodeFromString<FileMetaPayload>(plaintext)
        val convId = if (env.groupId != null) ConversationId.group(env.groupId!!) else ConversationId.direct(env.senderPk)
        fileTransferManager.registerIncomingMeta(meta)
        val senderName = repository.getPeer(env.senderPk)?.displayName ?: env.senderPk.take(8)
        val label = when {
            meta.isVoiceNote -> "Voice note (${meta.durationMs / 1000}s)"
            meta.mimeType.startsWith("image/") -> "Photo: ${meta.fileName}"
            meta.mimeType.startsWith("video/") -> "Video: ${meta.fileName}"
            else -> "File: ${meta.fileName}"
        }
        repository.insertMessageIfNew(
            MessageEntity(
                packetId = env.packetId,
                conversationId = convId,
                senderPk = env.senderPk,
                senderName = senderName,
                seq = env.seq,
                type = PacketType.FILE_META.name,
                bodyText = label,
                fileId = meta.fileId,
                mediaMime = meta.mimeType,
                mediaName = meta.fileName,
                mediaDurationMs = meta.durationMs,
                status = MessageStatus.DELIVERED,
                isOutgoing = false,
                timestampMs = env.timestampMs,
                rawEnvelopeJson = json.encodeToString(env)
            )
        )
    }

    private suspend fun handleFileChunk(plaintext: String) {
        val chunk = json.decodeFromString<FileChunkPayload>(plaintext)
        val completedFile = fileTransferManager.ingestChunk(chunk)
        if (completedFile != null) {
            repository.updateMessageMediaPath(chunk.fileId, completedFile.absolutePath)
        }
    }

    private suspend fun handleFileChunkReq(env: PacketEnvelope, plaintext: String) {
        val req = json.decodeFromString<FileChunkReqPayload>(plaintext)
        val me = repository.getIdentity() ?: return
        val peer = repository.getPeer(env.senderPk) ?: return
        val chunks = fileTransferManager.getOutgoingChunks(req.fileId, req.missingIndices)
        for (chunk in chunks) {
            val chunkEnv = EnvelopeCipher.sealDirect(
                sender = me.toKeyPair(),
                recipientPk = peer.peerId,
                recipientXk = peer.x25519PublicKey,
                type = PacketType.FILE_CHUNK,
                plaintext = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
            )
            router.send(peer.peerId, chunkEnv, queueIfOffline = false)
        }
    }

    private suspend fun handleGroupEvent(env: PacketEnvelope, plaintext: String) {
        val event = json.decodeFromString<GroupEventPayload>(plaintext)
        when (event.action) {
            GroupEventAction.MEMBER_JOINED -> {
                val groupId = env.groupId ?: return
                val joinedPeerId = event.targetPeerId ?: env.senderPk
                val joinedName = event.targetDisplayName ?: joinedPeerId.take(8)
                val joinedXk = event.targetX25519Pk
                if (!joinedXk.isNullOrBlank()) {
                    val existing = repository.getPeer(joinedPeerId)
                    repository.upsertPeer(
                        PeerEntity(
                            peerId = joinedPeerId,
                            x25519PublicKey = joinedXk,
                            displayName = joinedName,
                            lastLanHost = existing?.lastLanHost,
                            lastLanPort = existing?.lastLanPort,
                            lastSeenMs = System.currentTimeMillis(),
                            verifiedViaQr = existing?.verifiedViaQr ?: false
                        )
                    )
                }
                repository.upsertGroupMember(
                    GroupMemberEntity(
                        groupId = groupId,
                        peerId = joinedPeerId,
                        displayName = joinedName,
                        role = "MEMBER",
                        joinedAtMs = System.currentTimeMillis()
                    )
                )
            }
            GroupEventAction.GROUP_KEY_ROTATE -> {
                val groupId = env.groupId ?: event.newGroupName ?: return
                val group = repository.getGroup(groupId) ?: return
                if (env.senderPk != group.adminPeerId) return
                val newEpoch = event.newEpoch ?: return
                val newKey = event.newGroupKeyBase64 ?: return
                repository.applyRotatedGroupKey(groupId, newEpoch, newKey)
            }
            else -> Unit
        }
    }

    private suspend fun handleSyncDigest(env: PacketEnvelope, plaintext: String) {
        val me = repository.getIdentity() ?: return
        val peer = repository.getPeer(env.senderPk) ?: return
        val digest = json.decodeFromString<SyncDigestPayload>(plaintext)
        val localClock = repository.getVectorClock(digest.conversationId)

        val missingEnvelopes = mutableListOf<PacketEnvelope>()
        for ((senderPk, localMax) in localClock) {
            val remoteMax = digest.vectorClock[senderPk] ?: 0L
            if (localMax > remoteMax) {
                val rows = repository.getMessagesAfterSeq(digest.conversationId, senderPk, remoteMax)
                rows.mapNotNull { it.rawEnvelopeJson }.forEach { raw ->
                    runCatching { json.decodeFromString<PacketEnvelope>(raw) }
                        .getOrNull()
                        ?.let { missingEnvelopes.add(it) }
                }
            }
        }
        if (missingEnvelopes.isEmpty()) return

        val batch = SyncBatchPayload(
            conversationId = digest.conversationId,
            envelopes = missingEnvelopes.take(50)
        )
        val batchEnv = EnvelopeCipher.sealDirect(
            sender = me.toKeyPair(),
            recipientPk = peer.peerId,
            recipientXk = peer.x25519PublicKey,
            type = PacketType.SYNC_BATCH,
            plaintext = json.encodeToString(batch).toByteArray(Charsets.UTF_8)
        )
        router.send(peer.peerId, batchEnv, queueIfOffline = false)
    }

    private suspend fun handleSyncBatch(plaintext: String) {
        val batch = json.decodeFromString<SyncBatchPayload>(plaintext)
        val verified = GossipSyncEngine.verifyIncomingBatch(batch.envelopes)
        for (relayedEnv in verified) {
            if (relayedEnv.type == PacketType.CHAT_MSG || relayedEnv.type == PacketType.FILE_META) {
                handleIncomingPacket(
                    IncomingPacket(
                        envelope = relayedEnv,
                        sourceKind = TransportKind.RELAY
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "P2pCoordinator"
    }
}
