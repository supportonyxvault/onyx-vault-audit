package com.onyxvault.messenger.nostr

import android.content.Context
import android.util.Log
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*

/**
 * NostrManager – ONYX VAULT (Forensic Sovereign Edition)
 * Synchronisiert Identitäten und Backups dezentral.
 */
class NostrManager(
    private val context: Context,
    private val relayPool: NostrRelayPool,
    private val keystore: AdaptiveKeystore
) {
    private val TAG = "NostrManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = SecurePrefs(context)

    init {
        scope.launch {
            relayPool.incomingEvents.collectLatest { event ->
                when (event.kind) {
                    NostrEvent.KIND_ONYX_ONION_UPDATE -> handleIncomingDiscovery(event)
                    NostrEvent.KIND_ONYX_KILL_COMMAND -> handleRemoteKill(event)
                    NostrEvent.KIND_ONYX_FRIEND_REQUEST -> handleIncomingFriendRequest(event)
                }
            }
        }
    }

    /**
     * Broadcastet die neue Onion-Adresse dezentral via Nostr.
     */
    fun broadcastIdentity(onionAddress: String) {
        scope.launch {
            try {
                val myPubKey = prefs.getMasterPublicKey() ?: return@launch
                val myPrivKey = prefs.getMasterPrivateKey() ?: return@launch
                
                val payload = JSONObject().apply {
                    put("type", "onyx_discovery")
                    put("onion", onionAddress)
                    put("name", prefs.getUsername())
                    put("ts", System.currentTimeMillis())
                }

                val hardwareSig = EncryptionManager.signData(payload.toString().toByteArray(), keystore)
                
                val content = JSONObject().apply {
                    put("p", payload.toString())
                    put("s", hardwareSig)
                }.toString()

                val createdAt = System.currentTimeMillis() / 1000
                val id = NostrEvent.computeId(myPubKey, createdAt, NostrEvent.KIND_ONYX_ONION_UPDATE, emptyList(), content)
                val nostrSig = EncryptionManager.signNostrEvent(id, myPrivKey)

                val event = NostrEvent(id, myPubKey, createdAt, NostrEvent.KIND_ONYX_ONION_UPDATE, listOf(listOf("t", "onyx_vault")), content, nostrSig)
                relayPool.publish(event)
                Log.i(TAG, "Identität dezentral via Nostr gestreut.")
            } catch (e: Exception) { Log.e(TAG, "Nostr Broadcast Error: ${e.message}") }
        }
    }

    /**
     * Sichert die Sovereign Identity (Backup) auf Nostr (KIND 30081).
     * Dies ermöglicht der Website den Zugriff ohne manuelle Dateneingabe.
     */
    fun syncSovereignIdentity() {
        scope.launch {
            try {
                val myPubKey = prefs.getMasterPublicKey() ?: return@launch
                val myPrivKey = prefs.getMasterPrivateKey() ?: return@launch
                val appId = prefs.getAppId() ?: return@launch
                
                val backupData = JSONObject().apply {
                    put("username", prefs.getUsername())
                    put("app_id", appId)
                    // Aktuelle Onion aus dem TorManager holen
                    put("onion", (context.applicationContext as? com.onyxvault.messenger.OnyxVaultApp)?.torManager?.onionAddress?.value)
                    put("ks_pub", prefs.getKillSwitchPubKey())
                    put("ts", System.currentTimeMillis())
                }

                // Daten werden mit der App-ID verschlüsselt
                val encryptedBackup = EncryptionManager.encryptWithAppId(backupData.toString(), appId)
                
                val createdAt = System.currentTimeMillis() / 1000
                val tags = listOf(
                    listOf("t", "onyx_backup"),
                    listOf("d", "sovereign_identity")
                )
                
                val id = NostrEvent.computeId(myPubKey, createdAt, NostrEvent.KIND_ONYX_BACKUP, tags, encryptedBackup)
                val sig = EncryptionManager.signNostrEvent(id, myPrivKey)

                val event = NostrEvent(id, myPubKey, createdAt, NostrEvent.KIND_ONYX_BACKUP, tags, encryptedBackup, sig)
                relayPool.publish(event)
                Log.i(TAG, "Sovereign Backup auf Nostr synchronisiert.")
            } catch (e: Exception) { Log.e(TAG, "Nostr Sync Error: ${e.message}") }
        }
    }

    private fun handleIncomingDiscovery(event: NostrEvent) {
        try {
            val envelope = JSONObject(event.content)
            val payloadStr = envelope.getString("p")
            val signature = envelope.getString("s")

            if (EncryptionManager.verifySignature(payloadStr.toByteArray(), signature, keystore)) {
                val payload = JSONObject(payloadStr)
                val onion = payload.getString("onion")
                val app = context.applicationContext as? com.onyxvault.messenger.OnyxVaultApp
                app?.contactManager?.value?.updateContactRoute(event.pubkey, onion)
            }
        } catch (e: Exception) { }
    }

    private fun handleIncomingFriendRequest(event: NostrEvent) {
        val app = context.applicationContext as? com.onyxvault.messenger.OnyxVaultApp
        app?.friendRequestManager?.value?.handleIncomingNostrRequest(event)
    }

    private fun handleRemoteKill(event: NostrEvent) {
        val app = context.applicationContext as? com.onyxvault.messenger.OnyxVaultApp
        if (event.getRecipientPubkey() == prefs.getMasterPublicKey()) {
            app?.killSwitchManager?.value?.processRemoteKill(event.content, event.pubkey)
        }
    }
}
