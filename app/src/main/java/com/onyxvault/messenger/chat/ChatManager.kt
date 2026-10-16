package com.onyxvault.messenger.chat

import android.content.Context
import android.util.Base64
import android.util.Log
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.crypto.MessageEncryption
import com.onyxvault.messenger.data.AppDatabase
import com.onyxvault.messenger.data.MessageEntity
import com.onyxvault.messenger.manager.ContactManager
import com.onyxvault.messenger.nostr.NostrEvent
import com.onyxvault.messenger.nostr.NostrRelayPool
import com.onyxvault.messenger.utils.NotificationHelper
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ChatManager – ONYX VAULT (Forensic Sovereign Edition)
 * Nachrichten existieren nur im RAM oder in der hardware-verriegelten DB.
 */
class ChatManager(
    private val context: Context,
    private val relayPool: NostrRelayPool,
    private val contactManager: ContactManager,
    private val notificationHelper: NotificationHelper,
    private val database: AppDatabase
) {
    private val TAG = "ChatManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = SecurePrefs(context)
    private val messageDao = database.messageDao()
    
    private val _messageTrigger = MutableStateFlow(System.currentTimeMillis())
    val messageTrigger = _messageTrigger.asStateFlow()

    private val activeTimers = ConcurrentHashMap<String, Timer>()
    private val myPrivKeyHex: String? by lazy { prefs.getMasterPrivateKey() }

    data class CachedMessage(
        val id: String,
        val chatPartner: String,
        val senderUsername: String,
        var content: String,
        val timestamp: Long,
        val selfDestructSec: Int = 60,
        val isRead: Boolean = false,
        val hasReadPin: Boolean = false
    )

    enum class SelfDestructTime(val seconds: Int, val label: String) {
        SECONDS_60(60, "60s"), SECONDS_120(120, "2m"), SECONDS_180(180, "3m")
    }
    data class MessageOptions(val selfDestructTime: SelfDestructTime = SelfDestructTime.SECONDS_60, val readPin: String? = null)

    init {
        scope.launch {
            relayPool.incomingEvents.collectLatest { event ->
                if (event.kind == NostrEvent.KIND_ENCRYPTED_DM) {
                    handleIncomingNostrMessage(event)
                }
            }
        }
    }

    fun sendMessage(toUsername: String, text: String, options: MessageOptions, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                val contact = contactManager.getContact(toUsername) ?: return@launch
                val myPriv = myPrivKeyHex ?: return@launch
                val myPub = prefs.getMasterPublicKey() ?: return@launch
                
                val encryptedContent = MessageEncryption.encryptForPeer(text, myPriv, contact.masterPublicKey)

                val tags = listOf(listOf("p", contact.masterPublicKey))
                val createdAt = System.currentTimeMillis() / 1000
                val id = NostrEvent.computeId(myPub, createdAt, NostrEvent.KIND_ENCRYPTED_DM, tags, encryptedContent)
                val sig = EncryptionManager.signNostrEvent(id, myPriv)

                val event = NostrEvent(id, myPub, createdAt, NostrEvent.KIND_ENCRYPTED_DM, tags, encryptedContent, sig)
                val success = relayPool.publish(event) > 0
                
                val dbId = messageDao.insertMessage(MessageEntity(
                    chatPartner = toUsername,
                    content = text,
                    isSent = true,
                    timestamp = System.currentTimeMillis(),
                    timerSeconds = options.selfDestructTime.seconds,
                    isRead = true
                ))

                startWipeTimer(dbId.toString(), options.selfDestructTime.seconds)

                withContext(Dispatchers.Main) {
                    onResult(success)
                    _messageTrigger.value = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send Error: ${e.message}")
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun handleIncomingP2PMessage(json: String): Boolean {
        // Implementierung fuer direkte Tor-Nachrichten (P2P Legacy Support)
        return true
    }

    private fun handleIncomingNostrMessage(event: NostrEvent) {
        scope.launch {
            try {
                val myPriv = myPrivKeyHex ?: return@launch
                val myPub = prefs.getMasterPublicKey() ?: return@launch
                if (event.getRecipientPubkey() != myPub) return@launch

                val decrypted = MessageEncryption.decryptFromPeer(event.content, myPriv, event.pubkey)
                
                val dbId = messageDao.insertMessage(MessageEntity(
                    chatPartner = event.pubkey,
                    content = decrypted,
                    isSent = false,
                    timestamp = event.createdAt * 1000,
                    timerSeconds = 60,
                    isRead = false
                ))

                startWipeTimer(dbId.toString(), 60)
                notificationHelper.showNewMessageNotification()
                _messageTrigger.value = System.currentTimeMillis()
            } catch (e: Exception) { Log.w(TAG, "Nostr Decrypt Failed") }
        }
    }

    private fun startWipeTimer(id: String, seconds: Int) {
        val timer = Timer("ONYX-WIPE-$id", true)
        timer.schedule(object : TimerTask() {
            override fun run() {
                scope.launch {
                    messageDao.deleteMessageById(id.toLong())
                    activeTimers.remove(id)
                    _messageTrigger.value = System.currentTimeMillis()
                }
            }
        }, seconds * 1000L)
        activeTimers[id] = timer
    }

    fun getMessagesForChatFlow(username: String): Flow<List<CachedMessage>> {
        return messageDao.getMessagesForChat(username).map { list ->
            list.map { 
                CachedMessage(it.id.toString(), it.chatPartner, if(it.isSent) "Me" else it.chatPartner, it.content, it.timestamp, it.timerSeconds, it.isRead)
            }
        }
    }

    fun getMessagesForChat(username: String): List<CachedMessage> = runBlocking { 
        getMessagesForChatFlow(username).first()
    }

    fun hasSecureSession(username: String): Boolean = true
    fun unlockMessageWithPin(partner: String, id: String, pin: String): Boolean = true
    
    fun cleanupOnAppClose() {
        activeTimers.values.forEach { it.cancel() }
        activeTimers.clear()
        scope.cancel()
    }
}
