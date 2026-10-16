package com.onyxvault.messenger.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import org.bouncycastle.util.encoders.Hex

/**
 * ONYX VAULT - Nostr Event Model (NIP-01)
 *
 * KRITISCH: Die Event-ID muss nach EXAKTER NIP-01 Spezifikation serialisiert werden:
 *   [0, pubkey, created_at, kind, tags, content]
 * Das JSON darf KEINEN Whitespace enthalten UND UTF-8 Zeichen > 0x7F
 * duerfen NICHT als \uXXXX escaped werden (nur \n \" \\ \r \t \b \f).
 *
 * org.json.JSONArray.toString() verletzt beide Regeln - deshalb hat die
 * App vor diesem Fix immer "Event id does not match content hash" von
 * Relays bekommen. Jetzt benutzen wir einen eigenen Serializer.
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    var sig: String
) {
    companion object {
        // NIP-04 / NIP-44 Kinds
        const val KIND_ENCRYPTED_DM = 4

        // ONYX VAULT Custom Kinds (Dezentrale Infrastruktur)
        const val KIND_ONYX_FRIEND_REQUEST = 30077
        const val KIND_ONYX_KILL_COMMAND   = 30078
        const val KIND_ONYX_ONION_UPDATE   = 30079
        const val KIND_ONYX_PEER_GONE      = 30080
        const val KIND_ONYX_BACKUP         = 30081

        fun computeId(pubkey: String, createdAt: Long, kind: Int,
                      tags: List<List<String>>, content: String): String {
            val serialized = serializeNip01(pubkey, createdAt, kind, tags, content)
            val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
            return Hex.toHexString(digest)
        }

        /**
         * NIP-01 compliant JSON serialization:
         *   [0, "pubkey", created_at, kind, [[tag,tag,...],...], "content"]
         * NO whitespace, UTF-8 chars > 0x7F stay as-is (NOT escaped as \uXXXX).
         */
        private fun serializeNip01(pubkey: String, createdAt: Long, kind: Int,
                                   tags: List<List<String>>, content: String): String {
            val sb = StringBuilder()
            sb.append("[0,\"").append(escape(pubkey)).append("\",")
            sb.append(createdAt).append(",")
            sb.append(kind).append(",[")
            for ((i, tag) in tags.withIndex()) {
                if (i > 0) sb.append(",")
                sb.append("[")
                for ((j, s) in tag.withIndex()) {
                    if (j > 0) sb.append(",")
                    sb.append("\"").append(escape(s)).append("\"")
                }
                sb.append("]")
            }
            sb.append("],\"").append(escape(content)).append("\"]")
            return sb.toString()
        }

        /**
         * NIP-01 string escape:
         *  \n \" \\ \r \t \b \f are the ONLY escapes.
         *  UTF-8 characters > 0x7F are NOT escaped.
         *  Control characters < 0x20 (except those above) escape as \u00XX.
         */
        private fun escape(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '\n'     -> sb.append("\\n")
                    '"'      -> sb.append("\\\"")
                    '\\'     -> sb.append("\\\\")
                    '\r'     -> sb.append("\\r")
                    '\t'     -> sb.append("\\t")
                    '\b'     -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> {
                        if (c.code < 0x20) {
                            sb.append("\\u").append(String.format("%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            return sb.toString()
        }
    }

    /**
     * Serialize for sending to relay via WebSocket.
     * Format: ["EVENT", {id, pubkey, created_at, kind, tags, content, sig}]
     * Here we also use NIP-01 compliant escaping to be safe.
     */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"id\":\"").append(id).append("\",")
        sb.append("\"pubkey\":\"").append(pubkey).append("\",")
        sb.append("\"created_at\":").append(createdAt).append(",")
        sb.append("\"kind\":").append(kind).append(",")
        sb.append("\"tags\":[")
        for ((i, tag) in tags.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("[")
            for ((j, s) in tag.withIndex()) {
                if (j > 0) sb.append(",")
                sb.append("\"").append(escapeForWire(s)).append("\"")
            }
            sb.append("]")
        }
        sb.append("],")
        sb.append("\"content\":\"").append(escapeForWire(content)).append("\",")
        sb.append("\"sig\":\"").append(sig).append("\"}")
        return sb.toString()
    }

    private fun escapeForWire(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\n'     -> sb.append("\\n")
                '"'      -> sb.append("\\\"")
                '\\'     -> sb.append("\\\\")
                '\r'     -> sb.append("\\r")
                '\t'     -> sb.append("\\t")
                '\b'     -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun getRecipientPubkey(): String? =
        tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
}
