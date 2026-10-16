package com.onyxvault.messenger.nostr

import android.util.Log
import com.onyxvault.messenger.utils.DiagnosticLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NostrRelayPool - ONYX VAULT (Sovereign Edition)
 *
 * Ping-Test (Node.js, 2026-10-11) hat bewiesen: Pipeline mit kind 30081 funktioniert
 * auf relay.damus.io, nos.lol, offchain.pub, relay.snort.social.
 * Wenn Events ausbleiben liegt es am Transport (Tor nicht bereit) oder Filter-Mismatch.
 */
class NostrRelayPool(
    private val torProxyPort: Int = 9050
) {
    companion object {
        private const val TAG = "NostrRelayPool"
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io", "wss://nos.lol", "wss://nostr.wine",
            "wss://purplerelay.com", "wss://offchain.pub", "wss://relay.snort.social",
            "ws://[REDACTED-ONION]",
            "ws://[REDACTED-ONION]",
            "ws://[REDACTED-ONION]"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionFilter>()

    private val _incomingEvents = MutableSharedFlow<NostrEvent>(0, 100)
    val incomingEvents: SharedFlow<NostrEvent> = _incomingEvents.asSharedFlow()

    data class SubscriptionFilter(
        val subscriptionId: String,
        val kinds: List<Int>? = null,
        val authors: List<String>? = null,
        val tags: Map<String, List<String>>? = null,
        val since: Long? = null,
        val until: Long? = null,
        val limit: Int? = null
    )

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", torProxyPort)))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connectAll(relays: List<String> = DEFAULT_RELAYS) {
        DiagnosticLogger.log("NOSTR", "connectAll: ${relays.size} relays (tor-proxy=SOCKS:$torProxyPort)")
        relays.forEach { connect(it) }
    }

    fun connect(relayUrl: String) {
        if (connections.containsKey(relayUrl)) {
            DiagnosticLogger.log("NOSTR", "connect($relayUrl): already connected - skip")
            return
        }
        val client = buildClient()
        val request = Request.Builder().url(relayUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                DiagnosticLogger.log("NOSTR", "WS OPEN: $relayUrl (code=${response.code})")
                Log.i(TAG, "Verbunden: $relayUrl")
                // Alle aktiven Subs auf dieser neuen Verbindung einrichten
                activeSubscriptions.values.forEach {
                    DiagnosticLogger.log("NOSTR", "WS OPEN: re-sending sub ${it.subscriptionId.take(8)} to $relayUrl")
                    sendSub(ws, it)
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val arr = JSONArray(text)
                    val type = arr.getString(0)
                    when (type) {
                        "EVENT" -> {
                            val event = parseEvent(arr.getJSONObject(2))
                            DiagnosticLogger.log("NOSTR", "WS EVENT from $relayUrl: kind=${event.kind} id=${event.id.take(16)}... pubkey=${event.pubkey.take(16)}...")
                            scope.launch { _incomingEvents.emit(event) }
                        }
                        "EOSE" -> {
                            // End of stored events - subscriber bekommt ab jetzt nur neue
                            DiagnosticLogger.log("NOSTR", "WS EOSE from $relayUrl (sub=${arr.optString(1).take(8)})")
                        }
                        "OK" -> {
                            DiagnosticLogger.log("NOSTR", "WS OK from $relayUrl: ${arr.optString(1).take(16)} accepted=${arr.optBoolean(2)}")
                        }
                        "NOTICE" -> {
                            DiagnosticLogger.log("NOSTR", "WS NOTICE from $relayUrl: ${arr.optString(1)}")
                        }
                        "CLOSED" -> {
                            DiagnosticLogger.log("NOSTR", "WS CLOSED from $relayUrl: ${arr.optString(1)} reason=${arr.optString(2)}")
                        }
                    }
                } catch (e: Exception) {
                    DiagnosticLogger.log("NOSTR", "WS parse error from $relayUrl: ${e.message}")
                }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                DiagnosticLogger.log("NOSTR", "WS CLOSED: $relayUrl (code=$code reason=$reason)")
                connections.remove(relayUrl); scheduleReconnect(relayUrl)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                DiagnosticLogger.log("NOSTR", "WS FAIL: $relayUrl - ${t.message} (http=${response?.code})")
                connections.remove(relayUrl); scheduleReconnect(relayUrl)
            }
        }
        connections[relayUrl] = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(url: String) {
        scope.launch { delay(30_000); connect(url) }
    }

    fun publish(event: NostrEvent): Int {
        // WICHTIG: NICHT ueber JSONObject round-trip - das wuerde NIP-01-Escape zerstoeren!
            // Direkter String-Build mit event.toJson() (das macht NIP-01 escape intern).
            val msg = "[\"EVENT\",${event.toJson()}]"
        var ok = 0
        connections.values.forEach { if (it.send(msg)) ok++ }
        DiagnosticLogger.log("NOSTR", "publish: sent to $ok/${connections.size} relays (eventId=${event.id.take(16)}...)")
        return ok
    }

    fun subscribe(
        kinds: List<Int>? = null,
        authors: List<String>? = null,
        tags: Map<String, List<String>>? = null,
        since: Long? = null,
        until: Long? = null,
        limit: Int? = null
    ): String {
        val id = UUID.randomUUID().toString().take(16)
        val filter = SubscriptionFilter(id, kinds, authors, tags, since, until, limit)
        activeSubscriptions[id] = filter
        val tagsStr = tags?.entries?.joinToString(",") { "${it.key}=${it.value.firstOrNull()?.take(16)}..." } ?: "none"
        DiagnosticLogger.log("NOSTR", "subscribe: id=${id.take(8)} kinds=$kinds tags=[$tagsStr] -> ${connections.size} relays")
        connections.values.forEach { sendSub(it, filter) }
        return id
    }

    fun unsubscribe(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)
        val msg = JSONArray().apply { put("CLOSE"); put(subscriptionId) }.toString()
        connections.values.forEach { it.send(msg) }
        DiagnosticLogger.log("NOSTR", "unsubscribe: id=${subscriptionId.take(8)}")
    }

    private fun sendSub(ws: WebSocket, f: SubscriptionFilter) {
        val fj = JSONObject().apply {
            f.kinds?.let { put("kinds", JSONArray(it)) }
            f.authors?.let { put("authors", JSONArray(it)) }
            f.tags?.forEach { (t, v) -> put("#$t", JSONArray(v)) }
            f.since?.let { put("since", it) }
            f.until?.let { put("until", it) }
            f.limit?.let { put("limit", it) }
        }
        val reqJson = JSONArray().apply { put("REQ"); put(f.subscriptionId); put(fj) }.toString()
        val sent = ws.send(reqJson)
        DiagnosticLogger.log("NOSTR", "sendSub: ${f.subscriptionId.take(8)} sent=$sent json=${reqJson.take(120)}...")
    }

    private fun parseEvent(obj: JSONObject): NostrEvent {
        val ta = obj.getJSONArray("tags")
        val tags = (0 until ta.length()).map { i ->
            val t = ta.getJSONArray(i); (0 until t.length()).map { j -> t.getString(j) }
        }
        return NostrEvent(obj.getString("id"), obj.getString("pubkey"),
            obj.getLong("created_at"), obj.getInt("kind"), tags,
            obj.getString("content"), obj.getString("sig"))
    }

    fun getConnectedRelayCount(): Int = connections.size
    fun disconnect() {
        DiagnosticLogger.log("NOSTR", "disconnect: closing ${connections.size} connections")
        connections.values.forEach { it.close(1000, "Shutdown") }; connections.clear()
    }
}
