package com.onyxvault.messenger.nostr

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import java.security.SecureRandom
import com.onyxvault.messenger.crypto.EncryptionManager

/**
 * ONYX VAULT - Offline Mailbox via Nostr
 * Speichert verschlüsselte Nachrichten dezentral, wenn der Peer offline ist.
 */
class OfflineMailbox(
    private val relayPool: NostrRelayPool,
    private val myNostrPubkey: String,
    private val myNostrPrivateKeyHex: String
) {
    companion object {
        private const val TAG = "OfflineMailbox"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var subscriptionId: String? = null

    fun startListening(onMessageReceived: (fromPubkey: String, content: String) -> Unit) {
        // Dezentrales Abo für verschlüsselte DMs der letzten 72 Stunden
        val subId = relayPool.subscribe(
            kinds = listOf(NostrEvent.KIND_ENCRYPTED_DM),
            tags = mapOf("p" to listOf(myNostrPubkey)),
            since = (System.currentTimeMillis() / 1000L) - (72 * 3600)
        )
        subscriptionId = subId
        
        scope.launch {
            relayPool.incomingEvents
                .filter { it.kind == NostrEvent.KIND_ENCRYPTED_DM }
                .filter { it.getRecipientPubkey() == myNostrPubkey }
                .collect { event ->
                    onMessageReceived(event.pubkey, event.content)
                }
        }
    }

    fun stopListening() {
        subscriptionId?.let { relayPool.unsubscribe(it) }
        subscriptionId = null
    }
}
