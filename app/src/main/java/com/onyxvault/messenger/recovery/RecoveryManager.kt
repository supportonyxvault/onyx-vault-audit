package com.onyxvault.messenger.recovery

import android.content.Context
import android.util.Base64
import android.util.Log
import com.onyxvault.messenger.crypto.SecureWiper
import com.onyxvault.messenger.utils.DiagnosticLogger
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RecoveryManager - ONYX VAULT (Forensic Sovereign Edition)
 *
 * Verwaltet:
 *  - Brute-Force Lock (nach 5 falschen PINs: 24h Sperre)
 *  - Lokale Recovery via Master-QR
 *  - Remote Recovery via Nostr (unlockFromRemote)
 *
 * Master-QR Format (von register.html erzeugt):
 *   ONYX_MASTER:<appId>:<base64(iv[12] || ciphertext_mit_tag)>
 *   Key = SHA-256(appId)   (ohne Cloud-Passwort)
 *   Payload = JSON mit app_id, ks_priv, ks_pub, pub, type=MASTER, ...
 */
class RecoveryManager(
    private val context: Context,
    private val secureWiper: SecureWiper
) {
    companion object {
        private const val TAG = "RecoveryManager"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCK_DURATION_MS = 24 * 60 * 60 * 1000L
        private const val PREFS_RECOVERY = "recovery_stats"
        private const val KEY_DEADLINE = "lock_deadline"
        private const val KEY_ATTEMPTS = "failed_attempts"
    }

    private val prefs = context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)

    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()

    private val _remainingTime = MutableStateFlow("00:00:00")
    val remainingTime = _remainingTime.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        checkLockStatus()
        startTimer()
    }

    private fun checkLockStatus() {
        val deadline = prefs.getLong(KEY_DEADLINE, 0L)
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        val now = System.currentTimeMillis()

        if (deadline > 0L && deadline <= now) {
            // Deadline abgelaufen -> alles zuruecksetzen
            prefs.edit().putLong(KEY_DEADLINE, 0L).putInt(KEY_ATTEMPTS, 0).apply()
            _isLocked.value = false
            DiagnosticLogger.log("RECOVERY", "init: expired lock cleared (was deadline=$deadline, attempts=$attempts)")
        } else {
            _isLocked.value = deadline > now
            DiagnosticLogger.log("RECOVERY", "init: deadline=$deadline attempts=$attempts isLocked=${_isLocked.value}")
        }
    }

    private fun startTimer() {
        scope.launch {
            while (isActive) {
                val deadline = prefs.getLong(KEY_DEADLINE, 0L)
                val diff = deadline - System.currentTimeMillis()
                if (deadline > 0 && diff > 0) {
                    // Noch im Lock-Fenster
                    _isLocked.value = true
                    val h = TimeUnit.MILLISECONDS.toHours(diff)
                    val m = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
                    val s = TimeUnit.MILLISECONDS.toSeconds(diff) % 60
                    _remainingTime.value = String.format("%02d:%02d:%02d", h, m, s)
                } else if (deadline > 0 && diff <= 0 && _isLocked.value) {
                    // DEADLINE ABGELAUFEN & war aktiv -> FORENSISCHER WIPE
                    DiagnosticLogger.log("RECOVERY", "!!! 24h LOCK EXPIRED - FORENSIC WIPE INITIATED !!!")
                    _remainingTime.value = "00:00:00"
                    prefs.edit().clear().apply()
                    _isLocked.value = false
                    launch(Dispatchers.IO) {
                        try {
                            secureWiper.performSecureWipe("24h Brute-Force Lock abgelaufen - Daten vernichtet")
                        } catch (e: Exception) {
                            DiagnosticLogger.log("RECOVERY", "wipe error: ${e.message}")
                        }
                    }
                    break
                } else {
                    _isLocked.value = false
                    _remainingTime.value = "00:00:00"
                }
                delay(1000)
            }
        }
    }

    fun registerFailedAttempt() {
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply()
        DiagnosticLogger.log("RECOVERY", "registerFailedAttempt: count=$attempts/$MAX_FAILED_ATTEMPTS")

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            Log.e(TAG, "!!! BRUTE FORCE !!! Forensic lock active.")
            val deadline = System.currentTimeMillis() + LOCK_DURATION_MS
            prefs.edit().putLong(KEY_DEADLINE, deadline).apply()
            _isLocked.value = true
            DiagnosticLogger.log("RECOVERY", "BRUTE FORCE LOCK ACTIVATED (deadline=$deadline, isLocked=true)")
        }
    }

    /**
     * Entsperrt den Tresor nach einer autorisierten Remote-Recovery (Nostr).
     * Loescht die Deadline, setzt den Fehlversuch-Zaehler zurueck und entriegelt.
     */
    fun unlockFromRemote() {
        prefs.edit().putLong(KEY_DEADLINE, 0L).putInt(KEY_ATTEMPTS, 0).apply()
        _isLocked.value = false
        _remainingTime.value = "00:00:00"
        SecurePrefs(context).setRequireNewPin(true)
        DiagnosticLogger.log("RECOVERY", "UNLOCKED via Nostr-Recovery (counter + deadline cleared) - requireNewPin=true")
    }

    /**
     * Lokale Recovery via Master-QR (gescannt oder aus Datei).
     *
     * Format: ONYX_MASTER:<appId>:<base64>
     * - Entschluesselt mit SHA-256(appId) als AES-GCM key
     * - Prueft dass app_id im Payload mit lokaler SecurePrefs.getAppId() uebereinstimmt
     * - Prueft dass type == "MASTER"
     *
     * Bei Erfolg: Lock geloescht, Fehlversuche zurueckgesetzt.
     */
    suspend fun processRecoveryQR(qrContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            DiagnosticLogger.log("RECOVERY", "processRecoveryQR: input length=${qrContent.length} prefix=${qrContent.take(20)}")

            val trimmed = qrContent.trim()
            val isPassword = trimmed.startsWith("ONYX_MASTER_P:")
            val isPlain = trimmed.startsWith("ONYX_MASTER:")
            if (!isPlain && !isPassword) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: wrong prefix -> ABORT")
                return@withContext false
            }

            val parts = trimmed.split(":", limit = 3)
            if (parts.size < 3) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: malformed QR (parts=${parts.size})")
                return@withContext false
            }
            val appIdFromQr = parts[1]
            val encBase64 = parts[2]

            // Entschluesseln mit SHA-256(appId) als key
            val passphrase = appIdFromQr  // ONYX_MASTER_P braucht separates Passwort - hier nur ONYX_MASTER
            if (isPassword) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: ONYX_MASTER_P not yet supported in-app")
                return@withContext false
            }

            val plaintext = decryptMasterQr(encBase64, passphrase)
            if (plaintext.isBlank()) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: decrypt FAILED")
                return@withContext false
            }

            val identity = parseMasterPayload(plaintext)
            if (identity == null) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: payload is not valid MASTER identity")
                return@withContext false
            }

            // Pruefen: app_id im QR == app_id im Payload == lokale app_id
            val localAppId = SecurePrefs(context).getAppId()
            val payloadAppId = identity.optString("app_id", "")
            val type = identity.optString("type", "")

            DiagnosticLogger.log("RECOVERY", "processRecoveryQR: qr_appId=${appIdFromQr.take(16)} payload_appId=${payloadAppId.take(16)} local_appId=${localAppId?.take(16)} type=$type")

            if (type != "MASTER") {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: wrong type (expected MASTER, got $type)")
                return@withContext false
            }
            if (appIdFromQr != payloadAppId) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: appId mismatch QR vs payload")
                return@withContext false
            }
            if (localAppId != null && localAppId != payloadAppId) {
                DiagnosticLogger.log("RECOVERY", "processRecoveryQR: appId mismatch payload vs local - foreign QR!")
                return@withContext false
            }

            // SUCCESS: Lock loeschen
            prefs.edit().putLong(KEY_DEADLINE, 0L).putInt(KEY_ATTEMPTS, 0).apply()
            _isLocked.value = false
            _remainingTime.value = "00:00:00"
            DiagnosticLogger.log("RECOVERY", "UNLOCKED via local Master-QR (appId=${payloadAppId.take(16)})")
            return@withContext true
        } catch (e: Exception) {
            DiagnosticLogger.log("RECOVERY", "processRecoveryQR: Exception ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Recovery Error", e)
            false
        }
    }

    /**
     * Entschluesselt base64(iv[12] || ciphertext_mit_tag) mit SHA-256(passphrase) als Key.
     * Identisches Format wie hermes.js encryptAppFormat() auf der Website.
     */
    private fun decryptMasterQr(encBase64: String, passphrase: String): String {
        return try {
            val data = Base64.decode(encBase64, Base64.NO_WRAP)
            if (data.size < 13) return ""
            val iv = data.sliceArray(0 until 12)
            val encrypted = data.sliceArray(12 until data.size)
            val key = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            DiagnosticLogger.log("RECOVERY", "decryptMasterQr: ${e.message}")
            ""
        }
    }

    /**
     * Parst den Master-QR Payload. Unterstuetzt Envelope-Wrap {p,s} und direktes JSON.
     */
    private fun parseMasterPayload(plaintext: String): JSONObject? {
        return try {
            val obj = JSONObject(plaintext)
            // Envelope mit p+s auspacken (vom RegQR geerbt)
            if (obj.has("p") && obj.has("s")) {
                try { JSONObject(obj.getString("p")) } catch (e: Exception) { obj }
            } else obj
        } catch (e: Exception) { null }
    }
}
