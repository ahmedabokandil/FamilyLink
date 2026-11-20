package com.familylink.core.p2p.transport

import android.util.Log
import com.familylink.core.crypto.EnvelopeCipher
import com.familylink.core.crypto.IdentityManager
import com.familylink.core.crypto.PacketEnvelope
import com.familylink.core.p2p.transport.Bip340Schnorr.toHex
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Serverless internet transport backed by a pool of user-configurable public Nostr-compatible
 * WebSocket relays acting as zero-knowledge dumb pipes.
 *
 * Every outbound PacketEnvelope is already E2EE + Ed25519-signed and is wrapped in a throwaway
 * ephemeral secp256k1/BIP-340 Nostr event addressed to the recipient's blinded inbox tag so
 * relays cannot read messages or correlate sender identities.
 */
class DecentralizedRelayTransport(
    private val localPeerId: String,
    private val relayUrlsProvider: suspend () -> List<String>,
    private val groupIdsProvider: suspend () -> List<String>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : PeerTransport {

    override val kind: TransportKind = TransportKind.RELAY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _incomingPackets = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 256)
    override val incomingPackets: SharedFlow<IncomingPacket> = _incomingPackets.asSharedFlow()

    private val _reachablePeers = MutableStateFlow<Map<String, PeerEndpoint>>(emptyMap())
    override val reachablePeers: StateFlow<Map<String, PeerEndpoint>> = _reachablePeers.asStateFlow()

    private val _connectedRelays = MutableStateFlow<Set<String>>(emptySet())
    val connectedRelays: StateFlow<Set<String>> = _connectedRelays.asStateFlow()

    private val activeSockets = ConcurrentHashMap<String, WebSocket>()
    private var managerJob: Job? = null

    @Volatile
    private var running = false

    override suspend fun start() {
        if (running) return
        running = true
        refreshRelays()
    }

    suspend fun refreshRelays() {
        if (!running) return
        val urls = relayUrlsProvider().distinct()
        // Close removed relays
        activeSockets.keys.filter { it !in urls }.forEach { oldUrl ->
            activeSockets.remove(oldUrl)?.close(1000, "Relay removed")
            _connectedRelays.value = _connectedRelays.value - oldUrl
        }
        // Connect new relays
        urls.forEach { url ->
            if (!activeSockets.containsKey(url)) {
                connectRelay(url)
            } else {
                activeSockets[url]?.let { sendSubscription(it) }
            }
        }
    }

    override suspend fun stop() {
        running = false
        managerJob?.cancel()
        managerJob = null
        activeSockets.values.forEach { it.close(1000, "Stopped") }
        activeSockets.clear()
        _connectedRelays.value = emptySet()
    }

    override suspend fun send(targetPeerId: String, envelope: PacketEnvelope): Boolean {
        val sockets = activeSockets.values.toList()
        if (sockets.isEmpty()) return false

        val recipientTag = EnvelopeCipher.deriveBlindedRelayTag("peer", targetPeerId)
        val groupTag = envelope.groupId?.let { EnvelopeCipher.deriveBlindedRelayTag("group", it) }
        val eventFrame = buildSignedNostrEventFrame(
            envelopeJson = json.encodeToString(envelope),
            tags = buildList {
                add(listOf("t", recipientTag))
                if (groupTag != null) add(listOf("t", groupTag))
            }
        )

        var deliveredCount = 0
        sockets.forEach { ws ->
            if (ws.send(eventFrame)) {
                deliveredCount++
            }
        }
        return deliveredCount > 0
    }

    private fun connectRelay(url: String) {
        if (!running) return
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Log.w(TAG, "Invalid relay URL $url: ${e.message}")
            return
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSockets[url] = webSocket
                _connectedRelays.value = _connectedRelays.value + url
                scope.launch { sendSubscription(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeSockets.remove(url)
                _connectedRelays.value = _connectedRelays.value - url
                scheduleReconnect(url)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeSockets.remove(url)
                _connectedRelays.value = _connectedRelays.value - url
                if (running) scheduleReconnect(url)
            }
        }
        client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(url: String) {
        if (!running) return
        scope.launch {
            delay(5_000)
            if (running && !activeSockets.containsKey(url)) {
                val allowed = relayUrlsProvider()
                if (url in allowed) {
                    connectRelay(url)
                }
            }
        }
    }

    private suspend fun sendSubscription(ws: WebSocket) {
        val myTag = EnvelopeCipher.deriveBlindedRelayTag("peer", localPeerId)
        val groupTags = groupIdsProvider().map { EnvelopeCipher.deriveBlindedRelayTag("group", it) }
        val allTags = (listOf(myTag) + groupTags).distinct()
        val sinceSeconds = (System.currentTimeMillis() / 1000L) - LOOKBACK_SECONDS

        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(SUBSCRIPTION_ID))
            add(
                buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(FAMLINK_NOSTR_KIND)) })
                    put("#t", buildJsonArray { allTags.forEach { add(JsonPrimitive(it)) } })
                    put("since", JsonPrimitive(sinceSeconds))
                }
            )
        }
        ws.send(req.toString())
    }

    private fun handleRelayMessage(raw: String) {
        try {
            val arr = json.parseToJsonElement(raw).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return
            val eventObj = arr[2].jsonObject
            val content = eventObj["content"]?.jsonPrimitive?.content ?: return
            val envelope = json.decodeFromString<PacketEnvelope>(content)
            if (envelope.senderPk == localPeerId) return
            if (!EnvelopeCipher.verifyEnvelopeSignature(envelope)) {
                Log.w(TAG, "Dropped relay packet with invalid Ed25519 signature")
                return
            }

            val updated = _reachablePeers.value.toMutableMap()
            updated[envelope.senderPk] = PeerEndpoint(
                peerId = envelope.senderPk,
                kind = TransportKind.RELAY
            )
            _reachablePeers.value = updated

            scope.launch {
                _incomingPackets.emit(
                    IncomingPacket(
                        envelope = envelope,
                        sourceKind = TransportKind.RELAY
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore unrelated or malformed relay frames
        }
    }

    internal fun buildSignedNostrEventFrame(
        envelopeJson: String,
        tags: List<List<String>>,
        createdAtSec: Long = System.currentTimeMillis() / 1000L
    ): String {
        val eph = Bip340Schnorr.generateEphemeralKeyPair()
        val tagsJson = buildJsonArray {
            tags.forEach { tag ->
                add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } })
            }
        }
        val commitmentArray = JsonArray(
            listOf(
                JsonPrimitive(0),
                JsonPrimitive(eph.xOnlyPubKeyHex),
                JsonPrimitive(createdAtSec),
                JsonPrimitive(FAMLINK_NOSTR_KIND),
                tagsJson,
                JsonPrimitive(envelopeJson)
            )
        )
        val idBytes = IdentityManager.sha256(commitmentArray.toString().toByteArray(Charsets.UTF_8))
        val idHex = idBytes.toHex()
        val sigHex = Bip340Schnorr.sign(idBytes, eph.privKey).toHex()

        val eventObj = buildJsonObject {
            put("id", idHex)
            put("pubkey", eph.xOnlyPubKeyHex)
            put("created_at", createdAtSec)
            put("kind", FAMLINK_NOSTR_KIND)
            put("tags", tagsJson)
            put("content", envelopeJson)
            put("sig", sigHex)
        }
        return buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(eventObj)
        }.toString()
    }

    companion object {
        private const val TAG = "DecentralizedRelay"
        // Standard NIP-01 regular event kind in custom application range so relays store short-term history for offline catch-up
        const val FAMLINK_NOSTR_KIND = 4789
        private const val SUBSCRIPTION_ID = "fl_inbox_v1"
        private const val LOOKBACK_SECONDS = 72 * 3600L
    }
}
