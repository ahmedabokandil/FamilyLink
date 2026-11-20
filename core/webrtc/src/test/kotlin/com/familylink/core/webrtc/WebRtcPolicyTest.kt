package com.familylink.core.webrtc

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WebRtcPolicyTest {

    @Test
    fun `deterministic glare resolution guarantees exactly one peer initiates offer`() {
        val pairs = listOf(
            "peer_alice" to "peer_bob",
            "zzzKey123" to "aaaKey999",
            "001" to "002"
        )
        for ((a, b) in pairs) {
            val aInitiates = AdaptiveBitratePolicy.shouldInitiateOffer(a, b)
            val bInitiates = AdaptiveBitratePolicy.shouldInitiateOffer(b, a)
            assertNotEquals("Exactly one peer must initiate offer for ($a, $b)", aInitiates, bInitiates)
        }
    }

    @Test
    fun `adaptive video quality scales down cleanly for 3 and 4 to 6 callers and prioritizes screen share`() {
        val oneToOne = AdaptiveBitratePolicy.profileForParticipantCount(2)
        assertEquals(1280, oneToOne.width)
        assertEquals(30, oneToOne.fps)
        assertEquals(1_500_000, oneToOne.maxBitrateBps)

        val threeWay = AdaptiveBitratePolicy.profileForParticipantCount(3)
        assertEquals(640, threeWay.width)
        assertEquals(24, threeWay.fps)
        assertEquals(700_000, threeWay.maxBitrateBps)

        val fiveWay = AdaptiveBitratePolicy.profileForParticipantCount(5)
        assertEquals(480, fiveWay.width)
        assertEquals(15, fiveWay.fps)
        assertEquals(350_000, fiveWay.maxBitrateBps)

        val screen = AdaptiveBitratePolicy.profileForParticipantCount(5, isScreenShare = true)
        assertEquals(1280, screen.width)
        assertEquals(12, screen.fps)
        assertEquals(1_200_000, screen.maxBitrateBps)
    }

    @Test
    fun `call signal message serializes and deserializes cleanly`() {
        val json = Json { ignoreUnknownKeys = true }
        val offer = CallSignalMessage(
            callId = "call-1",
            conversationId = "grp:smith",
            callerName = "Alice",
            isVideoCall = true,
            type = CallSignalType.OFFER,
            sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
        )
        val encoded = json.encodeToString(offer)
        val decoded = json.decodeFromString<CallSignalMessage>(encoded)
        assertEquals(offer, decoded)
    }
}
