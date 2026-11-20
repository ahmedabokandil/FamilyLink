package com.familylink.core.crypto

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

sealed class ParsedQrInvite {
    data class Contact(val payload: ContactQrPayload) : ParsedQrInvite()
    data class Group(val payload: GroupInviteQrPayload) : ParsedQrInvite()
}

/**
 * Encodes, signs, parses, and cryptographically verifies FamilyLink Contact & Group Invite URIs
 * used in both camera QR codes and shareable remote text invites.
 */
object QrPayloadCodec {
    private const val SCHEME = "famlink"

    fun encodeContactUri(
        identity: IdentityKeyPair,
        displayName: String,
        relays: List<String> = emptyList(),
        lanHint: String? = null
    ): String {
        val relaysCsv = relays.filter { it.isNotBlank() }.joinToString(",")
        val signData = contactSignBytes(
            peerId = identity.peerId,
            x25519PublicKey = identity.x25519PublicKey,
            displayName = displayName,
            relaysCsv = relaysCsv,
            lanHint = lanHint.orEmpty()
        )
        val sig = Base64Url.encode(IdentityManager.sign(identity.ed25519PrivateKey, signData))
        val params = linkedMapOf(
            "pk" to identity.peerId,
            "xk" to identity.x25519PublicKey,
            "n" to displayName,
            "r" to relaysCsv,
            "lan" to lanHint.orEmpty(),
            "sig" to sig
        )
        return "$SCHEME://peer/v1?${encodeQuery(params)}"
    }

    fun encodeGroupInviteUri(
        adminIdentity: IdentityKeyPair,
        adminDisplayName: String,
        groupId: String,
        epoch: Int,
        groupKeyBase64: String,
        groupName: String,
        relays: List<String> = emptyList(),
        lanHint: String? = null
    ): String {
        val relaysCsv = relays.filter { it.isNotBlank() }.joinToString(",")
        val signData = groupSignBytes(
            groupId = groupId,
            epoch = epoch,
            groupKeyBase64 = groupKeyBase64,
            groupName = groupName,
            adminPeerId = adminIdentity.peerId,
            adminX25519PublicKey = adminIdentity.x25519PublicKey,
            adminDisplayName = adminDisplayName,
            relaysCsv = relaysCsv,
            lanHint = lanHint.orEmpty()
        )
        val sig = Base64Url.encode(IdentityManager.sign(adminIdentity.ed25519PrivateKey, signData))
        val params = linkedMapOf(
            "gid" to groupId,
            "ep" to epoch.toString(),
            "gk" to groupKeyBase64,
            "n" to groupName,
            "adm" to adminIdentity.peerId,
            "axk" to adminIdentity.x25519PublicKey,
            "an" to adminDisplayName,
            "r" to relaysCsv,
            "lan" to lanHint.orEmpty(),
            "sig" to sig
        )
        return "$SCHEME://group/v1?${encodeQuery(params)}"
    }

    fun parseAndVerify(rawUri: String): ParsedQrInvite {
        val trimmed = rawUri.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw CryptoVerificationException("Malformed FamilyLink URI", e)
        }
        if (uri.scheme != SCHEME) {
            throw CryptoVerificationException("Unsupported URI scheme: ${uri.scheme}")
        }
        val params = decodeQuery(uri.rawQuery.orEmpty())
        return when (uri.host) {
            "peer" -> parseContact(params)
            "group" -> parseGroup(params)
            else -> throw CryptoVerificationException("Unsupported FamilyLink QR type: ${uri.host}")
        }
    }

    private fun parseContact(params: Map<String, String>): ParsedQrInvite.Contact {
        val pk = params["pk"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing pk in Contact QR")
        val xk = params["xk"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing xk in Contact QR")
        val name = params["n"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing display name in Contact QR")
        val relaysCsv = params["r"].orEmpty()
        val lanHint = params["lan"].orEmpty()
        val sig = params["sig"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing signature in Contact QR")

        val valid = IdentityManager.verify(
            ed25519PublicKeyBase64 = pk,
            message = contactSignBytes(pk, xk, name, relaysCsv, lanHint),
            signature = Base64Url.decode(sig)
        )
        if (!valid) {
            throw CryptoVerificationException("Contact QR signature verification failed")
        }
        return ParsedQrInvite.Contact(
            ContactQrPayload(
                peerId = pk,
                x25519PublicKey = xk,
                displayName = name,
                relays = relaysCsv.split(",").filter { it.isNotBlank() },
                lanHint = lanHint.ifBlank { null },
                signature = sig
            )
        )
    }

    private fun parseGroup(params: Map<String, String>): ParsedQrInvite.Group {
        val gid = params["gid"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing gid in Group QR")
        val epoch = params["ep"]?.toIntOrNull()
            ?: throw CryptoVerificationException("Invalid epoch in Group QR")
        val gk = params["gk"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing group key in Group QR")
        val name = params["n"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing group name in Group QR")
        val adm = params["adm"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing admin peerId in Group QR")
        val axk = params["axk"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing admin X25519 key in Group QR")
        val an = params["an"]?.takeIf { it.isNotBlank() } ?: "Admin"
        val relaysCsv = params["r"].orEmpty()
        val lanHint = params["lan"].orEmpty()
        val sig = params["sig"]?.takeIf { it.isNotBlank() }
            ?: throw CryptoVerificationException("Missing signature in Group QR")

        val valid = IdentityManager.verify(
            ed25519PublicKeyBase64 = adm,
            message = groupSignBytes(gid, epoch, gk, name, adm, axk, an, relaysCsv, lanHint),
            signature = Base64Url.decode(sig)
        )
        if (!valid) {
            throw CryptoVerificationException("Group Invite QR signature verification failed")
        }
        return ParsedQrInvite.Group(
            GroupInviteQrPayload(
                groupId = gid,
                epoch = epoch,
                groupKeyBase64 = gk,
                groupName = name,
                adminPeerId = adm,
                adminX25519PublicKey = axk,
                adminDisplayName = an,
                relays = relaysCsv.split(",").filter { it.isNotBlank() },
                lanHint = lanHint.ifBlank { null },
                signature = sig
            )
        )
    }

    private fun contactSignBytes(
        peerId: String,
        x25519PublicKey: String,
        displayName: String,
        relaysCsv: String,
        lanHint: String
    ): ByteArray = "famlink-qr-peer-v1\n$peerId\n$x25519PublicKey\n$displayName\n$relaysCsv\n$lanHint"
        .toByteArray(Charsets.UTF_8)

    private fun groupSignBytes(
        groupId: String,
        epoch: Int,
        groupKeyBase64: String,
        groupName: String,
        adminPeerId: String,
        adminX25519PublicKey: String,
        adminDisplayName: String,
        relaysCsv: String,
        lanHint: String
    ): ByteArray = listOf(
        "famlink-qr-group-v1",
        groupId,
        epoch.toString(),
        groupKeyBase64,
        groupName,
        adminPeerId,
        adminX25519PublicKey,
        adminDisplayName,
        relaysCsv,
        lanHint
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

    private fun decodeQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val k = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                k to v
            }.toMap()
    }
}
