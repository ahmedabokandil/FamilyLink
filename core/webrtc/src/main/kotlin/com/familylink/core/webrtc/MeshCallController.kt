package com.familylink.core.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.familylink.core.data.db.RelayType
import com.familylink.core.data.repository.ConversationId
import com.familylink.core.data.repository.FamilyLinkRepository
import com.familylink.core.p2p.IncomingCallSignal
import com.familylink.core.p2p.P2pCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serverless Full-Mesh WebRTC Call Controller supporting 1:1 and multi-member family group
 * voice calls, video conferences (2-6 video peers), and live screen sharing.
 *
 * All SDP/ICE signaling travels directly over P2pCoordinator's E2EE HybridTransportRouter
 * with deterministic glare resolution (higher PeerId creates the Offer).
 */
class MeshCallController(
    context: Context,
    private val repository: FamilyLinkRepository,
    private val coordinator: P2pCoordinator,
    private val onIncomingCallNotification: (ActiveCallState) -> Unit,
    private val onDismissIncomingNotification: () -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = WebRtcEngine(context.applicationContext)
    private val audioRouter = CallAudioRouter(context.applicationContext)

    private val _callState = MutableStateFlow(ActiveCallState())
    val callState: StateFlow<ActiveCallState> = _callState.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val videoSenders = ConcurrentHashMap<String, RtpSender>()
    private val pendingIceCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    init {
        scope.launch {
            coordinator.incomingCallSignals.collect { signal ->
                handleIncomingSignal(signal)
            }
        }
    }

    fun startCall(conversationId: String, videoCall: Boolean) {
        scope.launch {
            val me = repository.getIdentity() ?: return@launch
            val isGroup = ConversationId.isGroup(conversationId)
            val rawId = ConversationId.extractId(conversationId)
            val title = if (isGroup) {
                repository.getGroup(rawId)?.name ?: "Family Group"
            } else {
                repository.getPeer(rawId)?.displayName ?: "Contact"
            }
            val targetPeers = if (isGroup) {
                repository.getGroupMembers(rawId).map { it.peerId }.filter { it != me.peerId }
            } else {
                listOf(rawId)
            }
            if (targetPeers.isEmpty()) return@launch

            val callId = UUID.randomUUID().toString()
            val profile = AdaptiveBitratePolicy.profileForParticipantCount(targetPeers.size + 1)
            engine.startLocalMedia(enableVideo = videoCall, profile = profile)
            _localVideoTrack.value = engine.localCameraTrack
            audioRouter.startCallAudio(preferSpeaker = videoCall)

            _callState.value = ActiveCallState(
                status = CallSessionStatus.OUTGOING_RINGING,
                callId = callId,
                conversationId = conversationId,
                title = title,
                isVideoCall = videoCall,
                localAudioEnabled = true,
                localVideoEnabled = videoCall,
                speakerEnabled = videoCall,
                startedAtMs = System.currentTimeMillis()
            )

            val ringSignal = CallSignalMessage(
                callId = callId,
                conversationId = conversationId,
                callerName = me.displayName,
                isVideoCall = videoCall,
                type = CallSignalType.RING,
                audioEnabled = true,
                videoEnabled = videoCall
            )
            val payload = json.encodeToString(ringSignal)
            val groupId = if (isGroup) rawId else null
            targetPeers.forEach { peerId ->
                coordinator.sendCallSignal(peerId, groupId, payload)
            }
        }
    }

    fun acceptIncomingCall(enableVideo: Boolean) {
        scope.launch {
            val state = _callState.value
            val callId = state.callId ?: return@launch
            val convId = state.conversationId ?: return@launch
            val me = repository.getIdentity() ?: return@launch
            onDismissIncomingNotification()

            val profile = AdaptiveBitratePolicy.profileForParticipantCount(2)
            engine.startLocalMedia(enableVideo = enableVideo, profile = profile)
            _localVideoTrack.value = engine.localCameraTrack
            audioRouter.startCallAudio(preferSpeaker = enableVideo)

            _callState.update {
                it.copy(
                    status = CallSessionStatus.ACTIVE,
                    localAudioEnabled = true,
                    localVideoEnabled = enableVideo,
                    speakerEnabled = enableVideo,
                    startedAtMs = System.currentTimeMillis()
                )
            }

            val isGroup = ConversationId.isGroup(convId)
            val rawId = ConversationId.extractId(convId)
            val peersToJoin = if (isGroup) {
                repository.getGroupMembers(rawId).map { it.peerId }.filter { it != me.peerId }
            } else {
                listOfNotNull(state.incomingFromPeerId)
            }

            val joinSignal = CallSignalMessage(
                callId = callId,
                conversationId = convId,
                callerName = me.displayName,
                isVideoCall = state.isVideoCall,
                type = CallSignalType.JOIN,
                audioEnabled = true,
                videoEnabled = enableVideo
            )
            val payload = json.encodeToString(joinSignal)
            val groupId = if (isGroup) rawId else null
            peersToJoin.forEach { peerId ->
                coordinator.sendCallSignal(peerId, groupId, payload)
                ensurePeerConnectionAndNegotiate(peerId, me.peerId, callId, convId, state.isVideoCall)
            }
        }
    }

    fun declineIncomingCall() {
        scope.launch {
            val state = _callState.value
            val me = repository.getIdentity()
            val caller = state.incomingFromPeerId
            onDismissIncomingNotification()
            if (me != null && caller != null && state.callId != null && state.conversationId != null) {
                val msg = CallSignalMessage(
                    callId = state.callId,
                    conversationId = state.conversationId,
                    callerName = me.displayName,
                    isVideoCall = state.isVideoCall,
                    type = CallSignalType.DECLINE
                )
                coordinator.sendCallSignal(caller, null, json.encodeToString(msg))
            }
            resetCallState()
        }
    }

    fun hangUp() {
        scope.launch {
            val state = _callState.value
            val me = repository.getIdentity()
            onDismissIncomingNotification()
            if (me != null && state.callId != null && state.conversationId != null) {
                val leave = CallSignalMessage(
                    callId = state.callId,
                    conversationId = state.conversationId,
                    callerName = me.displayName,
                    isVideoCall = state.isVideoCall,
                    type = CallSignalType.LEAVE
                )
                val payload = json.encodeToString(leave)
                val targets = (peerConnections.keys + listOfNotNull(state.incomingFromPeerId)).distinct()
                targets.forEach { peerId ->
                    coordinator.sendCallSignal(peerId, null, payload)
                }
            }
            resetCallState()
        }
    }

    fun toggleMute() {
        val next = !_callState.value.localAudioEnabled
        engine.localAudioTrack?.setEnabled(next)
        _callState.update { it.copy(localAudioEnabled = next) }
        broadcastMediaState()
    }

    fun toggleCamera() {
        val next = !_callState.value.localVideoEnabled
        if (next && engine.localCameraTrack == null) {
            val profile = AdaptiveBitratePolicy.profileForParticipantCount(_callState.value.participants.size + 1)
            engine.startCameraCapture(profile)
            val track = engine.localCameraTrack
            if (track != null) {
                _localVideoTrack.value = track
                videoSenders.values.forEach { sender -> sender.setTrack(track, false) }
            }
        } else {
            engine.localCameraTrack?.setEnabled(next)
        }
        _callState.update { it.copy(localVideoEnabled = next) }
        broadcastMediaState()
    }

    fun flipCamera() {
        engine.flipCamera()
    }

    fun toggleSpeaker() {
        val next = !_callState.value.speakerEnabled
        audioRouter.setSpeakerphone(next)
        _callState.update { it.copy(speakerEnabled = next) }
    }

    fun startScreenShare(mediaProjectionData: Intent) {
        val screenTrack = engine.startScreenShare(mediaProjectionData) {
            stopScreenShare()
        } ?: return
        _localVideoTrack.value = screenTrack
        videoSenders.values.forEach { sender ->
            sender.setTrack(screenTrack, false)
        }
        _callState.update { it.copy(localScreenSharing = true, localVideoEnabled = true) }
        applyAdaptiveBitrateToAllPeers()
        broadcastMediaState()
    }

    fun stopScreenShare() {
        engine.stopScreenShare()
        val camTrack = engine.localCameraTrack
        _localVideoTrack.value = camTrack
        videoSenders.values.forEach { sender ->
            sender.setTrack(camTrack, false)
        }
        _callState.update { it.copy(localScreenSharing = false) }
        applyAdaptiveBitrateToAllPeers()
        broadcastMediaState()
    }

    private fun broadcastMediaState() {
        scope.launch {
            val state = _callState.value
            val me = repository.getIdentity() ?: return@launch
            val callId = state.callId ?: return@launch
            val convId = state.conversationId ?: return@launch
            val msg = CallSignalMessage(
                callId = callId,
                conversationId = convId,
                callerName = me.displayName,
                isVideoCall = state.isVideoCall,
                type = CallSignalType.MEDIA_STATE,
                audioEnabled = state.localAudioEnabled,
                videoEnabled = state.localVideoEnabled,
                screenSharing = state.localScreenSharing
            )
            val payload = json.encodeToString(msg)
            peerConnections.keys.forEach { peerId ->
                coordinator.sendCallSignal(peerId, null, payload)
            }
        }
    }

    private suspend fun handleIncomingSignal(incoming: IncomingCallSignal) {
        val msg = runCatching { json.decodeFromString<CallSignalMessage>(incoming.payloadJson) }
            .getOrNull() ?: return
        val me = repository.getIdentity() ?: return
        val senderId = incoming.senderPk

        when (msg.type) {
            CallSignalType.RING -> {
                if (_callState.value.status != CallSessionStatus.IDLE) return
                val isGroup = ConversationId.isGroup(msg.conversationId)
                val title = if (isGroup) {
                    repository.getGroup(ConversationId.extractId(msg.conversationId))?.name ?: msg.callerName
                } else {
                    msg.callerName
                }
                val ringingState = ActiveCallState(
                    status = CallSessionStatus.INCOMING_RINGING,
                    callId = msg.callId,
                    conversationId = msg.conversationId,
                    title = title,
                    isVideoCall = msg.isVideoCall,
                    incomingFromPeerId = senderId,
                    participants = mapOf(
                        senderId to ParticipantMediaState(
                            peerId = senderId,
                            displayName = msg.callerName,
                            audioEnabled = msg.audioEnabled,
                            videoEnabled = msg.videoEnabled
                        )
                    )
                )
                _callState.value = ringingState
                onIncomingCallNotification(ringingState)
            }

            CallSignalType.JOIN, CallSignalType.ACCEPT -> {
                if (_callState.value.callId != msg.callId) return
                _callState.update { state ->
                    state.copy(
                        status = CallSessionStatus.ACTIVE,
                        participants = state.participants + (
                            senderId to ParticipantMediaState(
                                peerId = senderId,
                                displayName = msg.callerName,
                                audioEnabled = msg.audioEnabled,
                                videoEnabled = msg.videoEnabled,
                                screenSharing = msg.screenSharing
                            )
                        )
                    )
                }
                ensurePeerConnectionAndNegotiate(senderId, me.peerId, msg.callId, msg.conversationId, msg.isVideoCall)
                applyAdaptiveBitrateToAllPeers()
            }

            CallSignalType.OFFER -> {
                if (_callState.value.callId != msg.callId) return
                val pc = getOrCreatePeerConnection(senderId, msg.callId, msg.conversationId, msg.isVideoCall) ?: return
                val sdpText = msg.sdp ?: return
                pc.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.OFFER, sdpText)
                )
                drainPendingIceCandidates(senderId, pc)
                pc.createAnswer(
                    object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(SimpleSdpObserver(), desc)
                            scope.launch {
                                val ans = CallSignalMessage(
                                    callId = msg.callId,
                                    conversationId = msg.conversationId,
                                    callerName = me.displayName,
                                    isVideoCall = msg.isVideoCall,
                                    type = CallSignalType.ANSWER,
                                    sdp = desc.description
                                )
                                coordinator.sendCallSignal(senderId, null, json.encodeToString(ans))
                            }
                        }
                    },
                    MediaConstraints()
                )
            }

            CallSignalType.ANSWER -> {
                if (_callState.value.callId != msg.callId) return
                val pc = peerConnections[senderId] ?: return
                val sdpText = msg.sdp ?: return
                pc.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdpText)
                )
                drainPendingIceCandidates(senderId, pc)
            }

            CallSignalType.ICE -> {
                if (_callState.value.callId != msg.callId) return
                val candidate = IceCandidate(
                    msg.iceSdpMid ?: return,
                    msg.iceSdpMLineIndex ?: return,
                    msg.iceCandidate ?: return
                )
                val pc = peerConnections[senderId]
                if (pc?.remoteDescription != null) {
                    pc.addIceCandidate(candidate)
                } else {
                    pendingIceCandidates.computeIfAbsent(senderId) { mutableListOf() }.add(candidate)
                }
            }

            CallSignalType.MEDIA_STATE -> {
                if (_callState.value.callId != msg.callId) return
                _callState.update { state ->
                    val existing = state.participants[senderId] ?: ParticipantMediaState(senderId, msg.callerName)
                    state.copy(
                        participants = state.participants + (
                            senderId to existing.copy(
                                audioEnabled = msg.audioEnabled,
                                videoEnabled = msg.videoEnabled,
                                screenSharing = msg.screenSharing
                            )
                        )
                    )
                }
            }

            CallSignalType.DECLINE, CallSignalType.LEAVE -> {
                if (_callState.value.callId != msg.callId) return
                removePeer(senderId)
                if (_callState.value.participants.isEmpty()) {
                    onDismissIncomingNotification()
                    resetCallState()
                } else {
                    applyAdaptiveBitrateToAllPeers()
                }
            }
        }
    }

    private suspend fun ensurePeerConnectionAndNegotiate(
        remotePeerId: String,
        localPeerId: String,
        callId: String,
        conversationId: String,
        isVideoCall: Boolean
    ) {
        val pc = getOrCreatePeerConnection(remotePeerId, callId, conversationId, isVideoCall) ?: return
        if (AdaptiveBitratePolicy.shouldInitiateOffer(localPeerId, remotePeerId)) {
            val me = repository.getIdentity() ?: return
            pc.createOffer(
                object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObserver(), desc)
                        scope.launch {
                            val offer = CallSignalMessage(
                                callId = callId,
                                conversationId = conversationId,
                                callerName = me.displayName,
                                isVideoCall = isVideoCall,
                                type = CallSignalType.OFFER,
                                sdp = desc.description
                            )
                            coordinator.sendCallSignal(remotePeerId, null, json.encodeToString(offer))
                        }
                    }
                },
                MediaConstraints()
            )
        }
    }

    private suspend fun getOrCreatePeerConnection(
        remotePeerId: String,
        callId: String,
        conversationId: String,
        isVideoCall: Boolean
    ): PeerConnection? {
        peerConnections[remotePeerId]?.let { return it }
        val me = repository.getIdentity() ?: return null
        val iceServers = buildIceServers()

        val pc = engine.createPeerConnection(
            iceServers = iceServers,
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    scope.launch {
                        val iceMsg = CallSignalMessage(
                            callId = callId,
                            conversationId = conversationId,
                            callerName = me.displayName,
                            isVideoCall = isVideoCall,
                            type = CallSignalType.ICE,
                            iceCandidate = candidate.sdp,
                            iceSdpMid = candidate.sdpMid,
                            iceSdpMLineIndex = candidate.sdpMLineIndex
                        )
                        coordinator.sendCallSignal(remotePeerId, null, json.encodeToString(iceMsg))
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    val connected = state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED
                    _callState.update { current ->
                        val p = current.participants[remotePeerId] ?: return@update current
                        current.copy(participants = current.participants + (remotePeerId to p.copy(connected = connected)))
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    val track = receiver.track()
                    if (track is VideoTrack) {
                        _remoteVideoTracks.update { it + (remotePeerId to track) }
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }
        ) ?: return null

        engine.localAudioTrack?.let { pc.addTrack(it, listOf("fl_stream")) }
        val activeVideo = engine.localScreenTrack ?: engine.localCameraTrack
        if (activeVideo != null) {
            val sender = pc.addTrack(activeVideo, listOf("fl_stream"))
            if (sender != null) {
                videoSenders[remotePeerId] = sender
            }
        }
        peerConnections[remotePeerId] = pc
        return pc
    }

    private suspend fun buildIceServers(): List<PeerConnection.IceServer> {
        val configs = repository.getEnabledIceServers()
        if (configs.isEmpty()) {
            return listOf(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        }
        return configs.map { cfg ->
            val builder = PeerConnection.IceServer.builder(cfg.url)
            if (cfg.type == RelayType.TURN && !cfg.username.isNullOrBlank()) {
                builder.setUsername(cfg.username)
                builder.setPassword(cfg.credential.orEmpty())
            }
            builder.createIceServer()
        }
    }

    private fun drainPendingIceCandidates(peerId: String, pc: PeerConnection) {
        val list = pendingIceCandidates.remove(peerId) ?: return
        list.forEach { pc.addIceCandidate(it) }
    }

    private fun applyAdaptiveBitrateToAllPeers() {
        val total = _callState.value.participants.size + 1
        val isScreen = _callState.value.localScreenSharing
        val profile = AdaptiveBitratePolicy.profileForParticipantCount(total, isScreen)
        if (!isScreen) {
            engine.updateCameraFormat(profile)
        }
        videoSenders.values.forEach { sender ->
            try {
                val params = sender.parameters
                params.encodings.forEach { enc ->
                    enc.maxBitrateBps = profile.maxBitrateBps
                    enc.maxFramerate = profile.fps
                }
                sender.parameters = params
            } catch (e: Exception) {
                Log.d(TAG, "Adaptive bitrate update skipped: ${e.message}")
            }
        }
    }

    private fun removePeer(peerId: String) {
        peerConnections.remove(peerId)?.close()
        videoSenders.remove(peerId)
        pendingIceCandidates.remove(peerId)
        _remoteVideoTracks.update { it - peerId }
        _callState.update { it.copy(participants = it.participants - peerId) }
    }

    private fun resetCallState() {
        peerConnections.values.forEach { runCatching { it.close() } }
        peerConnections.clear()
        videoSenders.clear()
        pendingIceCandidates.clear()
        engine.stopLocalMedia()
        audioRouter.stopCallAudio()
        _localVideoTrack.value = null
        _remoteVideoTracks.value = emptyMap()
        _callState.value = ActiveCallState(status = CallSessionStatus.IDLE)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val TAG = "MeshCallController"
    }
}
