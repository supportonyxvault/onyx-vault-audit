package com.onyxvault.messenger.p2p

import android.util.Log
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.chat.ChatManager
import com.onyxvault.messenger.killswitch.KillSwitchManager
import com.onyxvault.messenger.manager.ContactManager
import com.onyxvault.messenger.utils.SecurePrefs
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

/**
 * P2PServer – ONYX VAULT (Forensic Grade)
 * Ermöglicht serverlose Kommunikation über Tor und dezentrale Nostr-Ankündigung.
 */
class P2PServer(
    private val chatManager: ChatManager,
    private val contactManager: ContactManager,
    private val killSwitchManager: KillSwitchManager,
    private val securePrefs: SecurePrefs,
    private val port: Int = 8080
) {
    private val TAG = "ONYX-P2PServer"
    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(app: OnyxVaultApp) {
        if (server != null) return
        
        val newServer = embeddedServer(Netty, port = port) {
            routing {
                // Endpoint für eingehende Nachrichten von Peers
                post("/message") {
                    val body = call.receiveText()
                    val ok = chatManager.handleIncomingP2PMessage(body)
                    if (ok) call.respond(HttpStatusCode.OK)
                    else call.respond(HttpStatusCode.BadRequest)
                }

                // Forensic-Command: Remote Kill (Verschlüsselt)
                post("/command/kill") {
                    val body = call.receiveText()
                    val ok = try {
                        val json = JSONObject(body)
                        val content = json.optString("content", "")
                        val pubKey = json.optString("pubkey", "")
                        killSwitchManager.processRemoteKill(content, pubKey)
                    } catch (e: Exception) {
                        // Fallback: Falls kein JSON, betrachten wir den Body als den autorisierten PubKey
                        killSwitchManager.processRemoteKill("", body)
                    }

                    if (ok) call.respond(HttpStatusCode.OK)
                    else call.respond(HttpStatusCode.Forbidden)
                }
                
                get("/ping") { call.respondText("ONYX_ACTIVE") }
            }
        }
        server = newServer
        
        scope.launch {
            try {
                Log.i(TAG, "P2P Server startet auf Port $port...")
                newServer.start(wait = false)
                
                // Überwache Tor-Status und kündige Adresse via Nostr an
                app.torManager.onionAddress.collectLatest { onion ->
                    if (!onion.isNullOrBlank()) {
                        Log.i(TAG, "Onion bereit: $onion. Streue Adresse via Nostr...")
                        app.nostrManager.broadcastIdentity(onion)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server Error: ${e.message}")
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        scope.cancel()
    }
}
