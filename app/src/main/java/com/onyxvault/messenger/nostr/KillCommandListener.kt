package com.onyxvault.messenger.nostr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.onyxvault.messenger.utils.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ONYX VAULT - Kill-/Recovery-Command Listener via Nostr
 *
 * AUDIT-FIXES 2026-10-16 (Findings CRITICAL-2, HIGH-3, MEDIUM-5):
 *  - BIP-340 Schnorr Signatur-Verifikation ERZWUNGEN vor Verarbeitung
 *  - target MUSS == myNostrPubkey sein (nicht nur optString)
 *  - event.id MUSS mit NIP-01 canonical hash uebereinstimmen
 *  - created_at Toleranzfenster: now - 7d bis now + 300s
 *  - seenEventIds Dedup-Set mit TTL (verhindert Replay innerhalb der 7d Age-Window)
 *
 * RE-AUDIT-FIX 2026-10-16 (NEU-3):
 *  - seenEventIds PERSISTIERT in SharedPreferences (EncryptedSharedPreferences wenn vorhanden)
 *    sodass App-Restart kein Replay-Fenster oeffnet
 */
class KillCommandListener(
    private val context: Context,
    private val relayPool: NostrRelayPool,
    private val myNostrPubkey: String
) {
    companion object {
        private const val TAG = "KillCmdListener"
        const val KIND_KILL = 30078
        const val KIND_RECOVERY = 30081
        private const val MAX_AGE_SEC = 7L * 24 * 3600
        private const val MAX_FUTURE_SEC = 300L
        private const val SEEN_EVENT_TTL_MS = 7L * 24 * 3600_000
        private const val PREFS_NAME = "onyx_nostr_dedup"
        private const val KEY_SEEN = "seen_events_json"
        private const val MAX_PERSISTED = 500  // cap damit prefs nicht explodieren
    }

    data class KillCommand(
        val eventId: String,
        val targetPubkey: String,
        val commandType: CommandType,
        val timestamp: Long,
        val reason: String?,
        val recoveryToken: String?
    )

    enum class CommandType { FULL_WIPE, RECOVERY_UNLOCK, PEER_WARNING }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var subscriptionId: String? = null

    // NEU-3: persistiert ueber App-Restart
    private val seenEventIds = ConcurrentHashMap<String, Long>()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        loadSeenEventsFromDisk()
    }

    private fun loadSeenEventsFromDisk() {
        try {
            val json = prefs.getString(KEY_SEEN, null) ?: return
            val obj = JSONObject(json)
            val now = System.currentTimeMillis()
            val keys = obj.keys()
            var loaded = 0
            while (keys.hasNext()) {
                val k = keys.next()
                val ts = obj.optLong(k)
                if (ts > 0 && now - ts < SEEN_EVENT_TTL_MS) {
                    seenEventIds[k] = ts
                    loaded++
                }
            }
            DiagnosticLogger.log("NOSTR", "loaded $loaded persisted seen-events from prefs")
        } catch (e: Exception) {
            DiagnosticLogger.log("NOSTR", "could not restore seen-events: ${e.message}")
        }
    }

    @Volatile private var flushScheduled = false
    private fun persistSeenEventsDebounced() {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            kotlinx.coroutines.delay(5000)  // Debounce
            flushScheduled = false
            try {
                // Cap: nimm die juengsten MAX_PERSISTED
                val sorted = seenEventIds.entries.sortedByDescending { it.value }.take(MAX_PERSISTED)
                val obj = JSONObject()
                for ((id, ts) in sorted) obj.put(id, ts)
                prefs.edit().putString(KEY_SEEN, obj.toString()).apply()
            } catch (e: Exception) {
                DiagnosticLogger.log("NOSTR", "persist seen-events failed: ${e.message}")
            }
        }
    }

    fun startListening(onKillCommand: (KillCommand) -> Unit, onRecoveryCommand: (KillCommand) -> Unit) {
        val subId = relayPool.subscribe(
            kinds = listOf(KIND_KILL, KIND_RECOVERY),
            tags = mapOf("p" to listOf(myNostrPubkey))
        )
        subscriptionId = subId
        DiagnosticLogger.log("NOSTR", "KillCommandListener STARTED kinds=[$KIND_KILL,$KIND_RECOVERY] pubkey=${myNostrPubkey.take(16)}...")
        scope.launch {
            relayPool.incomingEvents
                .filter { it.kind == KIND_KILL || it.kind == KIND_RECOVERY }
                .filter {
                    val match = it.getRecipientPubkey() == myNostrPubkey
                    if (!match) {
                        DiagnosticLogger.log("NOSTR", "Event skipped (p-tag=${it.getRecipientPubkey()?.take(16)}... != my ${myNostrPubkey.take(16)}...)")
                    }
                    match
                }
                .collect { event ->
                    DiagnosticLogger.log("NOSTR", "Event matched kind=${event.kind} eventId=${event.id.take(16)}...")
                    processCommand(event, onKillCommand, onRecoveryCommand)
                }
        }
    }

    /**
     * SECURITY: Verifiziert kryptografische Integritaet des Events bevor es verarbeitet wird.
     *   1. event.id == NIP-01 canonical hash?
     *   2. BIP-340 Schnorr Sig gueltig?
     *   3. created_at im Toleranzfenster?
     *   4. event.id nicht schon gesehen? (persistiert ueber App-Restart - NEU-3)
     *   5. target == myNostrPubkey?
     */
    private fun verifyAndCheckEvent(event: NostrEvent): Boolean {
        val expectedId = NostrEvent.computeId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        if (expectedId != event.id) {
            DiagnosticLogger.log("NOSTR", "DROP: event.id mismatch (claimed=${event.id.take(16)}..., actual=${expectedId.take(16)}...)")
            return false
        }

        if (!Bip340Verifier.verify(event.id, event.pubkey, event.sig)) {
            DiagnosticLogger.log("NOSTR", "DROP: BIP-340 signature verification FAILED for event ${event.id.take(16)}...")
            return false
        }

        val nowSec = System.currentTimeMillis() / 1000
        val ageSec = nowSec - event.createdAt
        if (ageSec > MAX_AGE_SEC) {
            DiagnosticLogger.log("NOSTR", "DROP: event too old (age=${ageSec}s > ${MAX_AGE_SEC}s)")
            return false
        }
        if (ageSec < -MAX_FUTURE_SEC) {
            DiagnosticLogger.log("NOSTR", "DROP: event too far in future (future=${-ageSec}s > ${MAX_FUTURE_SEC}s)")
            return false
        }

        val nowMs = System.currentTimeMillis()
        // Opportunistischer Cleanup (nur im RAM; persistiert nur die juengsten)
        if (seenEventIds.size > 1000) {
            seenEventIds.entries.removeAll { nowMs - it.value > SEEN_EVENT_TTL_MS }
        }
        val previous = seenEventIds.putIfAbsent(event.id, nowMs)
        if (previous != null) {
            DiagnosticLogger.log("NOSTR", "DROP: duplicate event (replay) ${event.id.take(16)}... first_seen=${nowMs - previous}ms_ago")
            return false
        }
        // NEU-3: Persistieren damit Restart kein Replay-Fenster oeffnet
        persistSeenEventsDebounced()

        return true
    }

    private fun processCommand(event: NostrEvent, onKill: (KillCommand) -> Unit, onRecovery: (KillCommand) -> Unit) {
        try {
            if (!verifyAndCheckEvent(event)) {
                return
            }

            val json = JSONObject(event.content)
            val target: String = json.optString("target", "")
            val typeStr: String = json.optString("type", "")
            val timestamp: Long = json.optLong("timestamp", event.createdAt)
            val reason: String? = if (!json.isNull("reason")) json.optString("reason") else null
            val recoveryToken: String? = if (!json.isNull("recovery_token")) json.optString("recovery_token") else null

            if (target.isEmpty() || target != myNostrPubkey) {
                DiagnosticLogger.log("NOSTR", "DROP: target mismatch (payload.target=${target.take(16)}... != my ${myNostrPubkey.take(16)}...)")
                return
            }

            when (event.kind) {
                KIND_KILL -> {
                    val commandType = runCatching { CommandType.valueOf(typeStr) }.getOrDefault(CommandType.FULL_WIPE)
                    DiagnosticLogger.log("NOSTR", "KILL command VERIFIED+ACCEPTED: type=$typeStr reason=$reason")
                    val cmd = KillCommand(event.id, target, commandType, timestamp, reason, recoveryToken)
                    onKill(cmd)
                }
                KIND_RECOVERY -> {
                    DiagnosticLogger.log("NOSTR", "RECOVERY command VERIFIED+ACCEPTED: token=${recoveryToken?.take(16)}... reason=$reason")
                    val cmd = KillCommand(event.id, target, CommandType.RECOVERY_UNLOCK, timestamp, reason, recoveryToken)
                    onRecovery(cmd)
                }
                else -> {
                    DiagnosticLogger.log("NOSTR", "Unknown kind=${event.kind} - ignored")
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("NOSTR", "Command parse error: ${e.message}")
            Log.e(TAG, "Command parse error: ${e.message}")
        }
    }

    fun stopListening() {
        subscriptionId?.let { relayPool.unsubscribe(it) }
        subscriptionId = null
        DiagnosticLogger.log("NOSTR", "KillCommandListener STOPPED")
    }
}
