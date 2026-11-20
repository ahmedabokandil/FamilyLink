package com.familylink.core.webrtc

import kotlinx.serialization.Serializable

@Serializable
enum class CallSignalType {
    RING,
    ACCEPT,
    DECLINE,
    JOIN,
    LEAVE,
    OFFER,
    ANSWER,
    ICE,
    MEDIA_STATE
}

@Serializable
data class CallSignalMessage(
    val callId: String,
    val conversationId: String,
    val callerName: String,
    val isVideoCall: Boolean,
    val type: CallSignalType,
    val sdp: String? = null,
    val iceCandidate: String? = null,
    val iceSdpMid: String? = null,
    val iceSdpMLineIndex: Int? = null,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = false,
    val screenSharing: Boolean = false
)

enum class CallSessionStatus {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ACTIVE,
    ENDED
}

data class ParticipantMediaState(
    val peerId: String,
    val displayName: String,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = false,
    val screenSharing: Boolean = false,
    val connected: Boolean = false
)

data class ActiveCallState(
    val status: CallSessionStatus = CallSessionStatus.IDLE,
    val callId: String? = null,
    val conversationId: String? = null,
    val title: String = "",
    val isVideoCall: Boolean = false,
    val localAudioEnabled: Boolean = true,
    val localVideoEnabled: Boolean = false,
    val localScreenSharing: Boolean = false,
    val speakerEnabled: Boolean = false,
    val incomingFromPeerId: String? = null,
    val participants: Map<String, ParticipantMediaState> = emptyMap(),
    val startedAtMs: Long = 0L
)

data class VideoQualityProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val maxBitrateBps: Int
)

/**
 * Pure-Kotlin adaptive bitrate/resolution policy and deterministic glare resolver
 * for serverless full-mesh family video calls (2-6 video callers, up to ~10 voice).
 */
object AdaptiveBitratePolicy {

    /**
     * Lexicographically higher PeerId always initiates the WebRTC SDP Offer so two peers
     * joining a family group call simultaneously never collide in offer glare.
     */
    fun shouldInitiateOffer(localPeerId: String, remotePeerId: String): Boolean {
        require(localPeerId != remotePeerId) { "Cannot negotiate WebRTC call with self" }
        return localPeerId > remotePeerId
    }

    fun profileForParticipantCount(
        totalParticipants: Int,
        isScreenShare: Boolean = false
    ): VideoQualityProfile {
        if (isScreenShare) {
            return VideoQualityProfile(
                width = 1280,
                height = 720,
                fps = 12,
                maxBitrateBps = 1_200_000
            )
        }
        return when {
            totalParticipants <= 2 -> VideoQualityProfile(
                width = 1280,
                height = 720,
                fps = 30,
                maxBitrateBps = 1_500_000
            )
            totalParticipants == 3 -> VideoQualityProfile(
                width = 640,
                height = 480,
                fps = 24,
                maxBitrateBps = 700_000
            )
            else -> VideoQualityProfile(
                width = 480,
                height = 360,
                fps = 15,
                maxBitrateBps = 350_000
            )
        }
    }
}
