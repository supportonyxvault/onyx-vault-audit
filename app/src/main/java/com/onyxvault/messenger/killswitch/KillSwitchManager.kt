package com.onyxvault.messenger.killswitch

import android.content.Context
import android.util.Log
import com.onyxvault.messenger.crypto.SecureWiper
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.manager.ContactManager
import com.onyxvault.messenger.tor.TorManager
import com.onyxvault.messenger.utils.SecurePrefs
import com.onyxvault.messenger.nostr.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * KillSwitchManager – Absolute Vernichtung aller Daten (Lokal & Remote)
 * Nutzt NOSTR zur dezentralen Signalisierung der Selbstzerstörung.
 */
class KillSwitchManager(
    private val context: Context,
    private val torManager: TorManager,
    private val secureWiper: SecureWiper,
    private val contactManager: ContactManager
) {
    companion object {
        private const val TAG = "KillSwitchManager"
    }

    private val _killSwitchActive = MutableStateFlow(false)
    val killSwitchActive = _killSwitchActive.asStateFlow()

    private val _wipeStatus = MutableStateFlow("")
    val wipeStatus = _wipeStatus.asStateFlow()

    private val _wipeProgress = MutableStateFlow(0f)
    val wipeProgress = _wipeProgress.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = SecurePrefs(context)

    fun processRemoteKill(eventContent: String, eventPubKey: String): Boolean {
        return try {
            val ksPubKey = prefs.getKillSwitchPubKey()
            if (ksPubKey == null || eventPubKey != ksPubKey) {
                Log.w(TAG, "Nicht autorisierter Kill-Versuch (Falscher Key).")
                return false
            }

            Log.e(TAG, "!!! AUTHENTIFIZIERTER REMOTE KILL EMPFANGEN !!!")
            executeManualKillSwitch()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei Remote Kill Verarbeitung.")
            false
        }
    }

    fun executeManualKillSwitch() {
        if (_killSwitchActive.value) return
        _killSwitchActive.value = true
        scope.launch {
            Log.e(TAG, "!!! TOTALER KILL SWITCH AKTIVIERT !!!")
            performEnhancedGhostAndWipe()
        }
    }

    private suspend fun performEnhancedGhostAndWipe() {
        _wipeStatus.value = "Phase 1: Ghost-Signale streuen..."
        _wipeProgress.value = 0.2f
        
        broadcastGhostSignal()
        delay(3000) // Zeit fuer Netzwerk-Broadcast geben

        _wipeStatus.value = "Phase 2: RAM-Spülung..."
        _wipeProgress.value = 0.4f
        torManager.stopTor()
        
        _wipeStatus.value = "Phase 3: Hardware-Keys vernichten..."
        _wipeProgress.value = 0.7f
        
        try {
            val keystore = AdaptiveKeystore(context)
            keystore.destroyAllKeys()
        } catch (e: Exception) { }
        
        delay(1000)

        _wipeStatus.value = "Phase 4: Sektor-Überschreibung..."
        _wipeProgress.value = 0.9f
        
        secureWiper.performSecureWipe("Remote Kill Switch Execution")

        _wipeStatus.value = "Vernichtet."
        _wipeProgress.value = 1.0f
    }

    private fun broadcastGhostSignal() {
        val app = context.applicationContext as? com.onyxvault.messenger.OnyxVaultApp ?: return
        val relayPool = app.nostrRelayPool
        val myPubKey = prefs.getMasterPublicKey() ?: return
        val myPrivKey = prefs.getMasterPrivateKey() ?: return

        scope.launch {
            try {
                val content = JSONObject().apply {
                    put("msg", "IDENTITY_VOID")
                    put("ts", System.currentTimeMillis())
                }.toString()

                val createdAt = System.currentTimeMillis() / 1000
                val tags = listOf(listOf("t", "onyx_void"))
                val id = NostrEvent.computeId(myPubKey, createdAt, NostrEvent.KIND_ONYX_PEER_GONE, tags, content)
                
                // KRITISCH: Event muss signiert werden, sonst lehnen Relays es ab!
                val sig = EncryptionManager.signNostrEvent(id, myPrivKey)

                val event = NostrEvent(
                    id = id,
                    pubkey = myPubKey,
                    createdAt = createdAt,
                    kind = NostrEvent.KIND_ONYX_PEER_GONE,
                    tags = tags,
                    content = content,
                    sig = sig
                )
                relayPool.publish(event)
                Log.i(TAG, "Ghost-Signal signiert und dezentral abgesetzt.")
            } catch (e: Exception) {
                Log.e(TAG, "Ghost-Signal fehlgeschlagen: ${e.message}")
            }
        }
    }
}
