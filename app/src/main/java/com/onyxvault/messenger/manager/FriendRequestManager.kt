package com.onyxvault.messenger.manager

import android.content.Context
import android.util.Log
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.crypto.MessageEncryption
import com.onyxvault.messenger.data.AppDatabase
import com.onyxvault.messenger.data.FriendRequestEntity
import com.onyxvault.messenger.nostr.NostrEvent
import com.onyxvault.messenger.nostr.NostrRelayPool
import com.onyxvault.messenger.utils.NotificationHelper
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/**
 * FriendRequestManager – ONYX VAULT (Forensic Sovereign Edition)
 * Handhabt dezentrale Kontaktanfragen via Nostr.
 */
class FriendRequestManager(
    private val context: Context,
    private val relayPool: NostrRelayPool,
    private val database: AppDatabase,
    private val notificationHelper: NotificationHelper
) {
    private val TAG = "FriendRequestManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = SecurePrefs(context)
    private val requestDao = database.friendRequestDao()

    val pendingRequests: Flow<List<FriendRequestEntity>> = requestDao.getPendingRequests()

    init {
        // Lausche dezentral auf Kontaktanfragen (Kind 30077)
        scope.launch {
            relayPool.incomingEvents.collectLatest { event ->
                if (event.kind == NostrEvent.KIND_ONYX_FRIEND_REQUEST) {
                    handleIncomingNostrRequest(event)
                }
            }
        }
    }

    /**
     * Verarbeitet eingehende Nostr-Events vom Typ Friend-Request.
     */
    fun handleIncomingNostrRequest(event: NostrEvent) {
        scope.launch {
            try {
                val myPubKey = prefs.getMasterPublicKey() ?: return@launch
                if (event.getRecipientPubkey() != myPubKey) return@launch

                val myPrivHex = prefs.getMasterPrivateKey() ?: return@launch
                
                // Dezentrale Entschlüsselung (Hardware-gestützt via Secp256k1)
                val decrypted = MessageEncryption.decryptFromPeer(event.content, myPrivHex, event.pubkey)
                val json = JSONObject(decrypted)

                if (json.getString("type") == "friend_request") {
                    val entity = FriendRequestEntity(
                        pubKey = event.pubkey,
                        username = json.getString("name"),
                        onion = json.getString("onion"),
                        timestamp = json.getLong("ts"),
                        status = "pending"
                    )
                    requestDao.insertRequest(entity)
                    notificationHelper.showNewMessageNotification("Neue Kontaktanfrage")
                    Log.i(TAG, "Dezentrale Anfrage von @${entity.username} empfangen.")
                }
            } catch (e: Exception) { 
                Log.w(TAG, "Inkompatibles oder korruptes Request-Event ignoriert.")
            }
        }
    }

    fun acceptRequest(request: FriendRequestEntity, contactManager: ContactManager) {
        scope.launch {
            contactManager.addContact(request.username, request.onion, request.pubKey)
            requestDao.deleteRequest(request.pubKey)
        }
    }

    fun denyRequest(pubKey: String) {
        scope.launch { requestDao.deleteRequest(pubKey) }
    }
}
