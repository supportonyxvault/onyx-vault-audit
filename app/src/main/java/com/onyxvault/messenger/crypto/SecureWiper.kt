package com.onyxvault.messenger.crypto

import android.content.Context
import android.util.Log
import com.onyxvault.messenger.utils.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * SecureWiper V1 - Forensische Datenvernichtung nach NIST SP 800-88.
 *
 * VERBINDLICHE 7-PHASEN REIHENFOLGE (User-Spec):
 *  Phase 1: SQLCipher-DB (+ sidecars) 3x overwrite -> delete
 *  Phase 2: QR-Code-Artefakte in Cache/Downloads 3x overwrite -> delete
 *  Phase 3: SharedPreferences 3x overwrite -> delete
 *  Phase 4: TorService-Dir (Onion-Keys, hostname) 3x overwrite -> delete
 *  Phase 5: Rest filesDir + cacheDir 3x overwrite -> delete
 *  Phase 6: Hardware-Keystore destroyAllKeys() <- ALS LETZTES
 *  Phase 7: Process kill
 *
 * Begruendung: Die DB braucht den Hardware-Key solange sie offen ist.
 * Wenn man den Keystore zuerst zerstoert, kann der Overwrite auf halbwegs
 * offenen Handles stolpern. Erst Daten vernichten, dann die Keys.
 */
class SecureWiper(private val context: Context) {

    private val secureRandom = SecureRandom()
    private val TAG = "SecureWiper"

    suspend fun performSecureWipe(reason: String) {
        withContext(Dispatchers.IO) {
            Log.e(TAG, "!!! FORENSISCHER WIPE GESTARTET: $reason !!!")
            DiagnosticLogger.log("WIPE", "===== KILLSWITCH START: $reason =====")

            try {
                // --- Phase 1: SQLCipher-DB ---
                DiagnosticLogger.log("WIPE", "Phase 1: SQLCipher-DB (+ sidecars) 3-pass overwrite")
                val dbFile = context.getDatabasePath("onyx_vault_forensic.db")
                wipeFileAndSidecars(dbFile)

                // --- Phase 2: QR-Code-Artefakte ---
                DiagnosticLogger.log("WIPE", "Phase 2: QR-Code-Artefakte in Cache 3-pass overwrite")
                wipeQRArtifacts()

                // --- Phase 3: SharedPreferences (ks_keys, master_keys, app_id, db_seed, recovery_blob) ---
                DiagnosticLogger.log("WIPE", "Phase 3: SharedPreferences 3-pass overwrite")
                val sharedPrefsDir = File(context.filesDir.parent, "shared_prefs")
                if (sharedPrefsDir.exists()) secureOverwriteDirectory(sharedPrefsDir)

                // --- Phase 4: TorService-Dir (Onion-Keys, hostname) ---
                DiagnosticLogger.log("WIPE", "Phase 4: TorService-Dir (Onion-Keys) 3-pass overwrite")
                val torServiceDir = context.getDir("TorService", Context.MODE_PRIVATE)
                if (torServiceDir.exists()) secureOverwriteDirectory(torServiceDir)
                // Zusaetzlich bekannte Tor Hidden-Service-Pfade
                val torSubDirs = listOf("hs", "hs_ed25519_secret_key", "hs_ed25519_public_key", "hostname")
                torSubDirs.forEach {
                    val f = File(torServiceDir, it)
                    if (f.exists()) if (f.isDirectory) secureOverwriteDirectory(f) else secureOverwriteFile(f)
                }

                // --- Phase 5: Rest filesDir + cacheDir ---
                DiagnosticLogger.log("WIPE", "Phase 5: filesDir + cacheDir 3-pass overwrite")
                secureOverwriteDirectory(context.filesDir)
                secureOverwriteDirectory(context.cacheDir)
                // Auch external cache falls vorhanden
                try {
                    context.externalCacheDir?.let { if (it.exists()) secureOverwriteDirectory(it) }
                } catch (e: Exception) {}

                // --- Phase 6: Hardware-Keystore destroyAllKeys() ---
                DiagnosticLogger.log("WIPE", "Phase 6: Hardware-Keystore destroyAllKeys() (als letztes)")
                try {
                    val keystore = AdaptiveKeystore(context)
                    keystore.destroyAllKeys()
                } catch (e: Exception) {
                    DiagnosticLogger.log("WIPE", "destroyAllKeys error: ${e.message}")
                }

                DiagnosticLogger.log("WIPE", "===== KILLSWITCH COMPLETE =====")
                Log.e(TAG, "✅ Alle Daten und Hardware-Keys vernichtet. Beende Prozess.")
            } catch (e: Exception) {
                Log.e(TAG, "Fehler während Vernichtung: ${e.message}")
                DiagnosticLogger.log("WIPE", "KILLSWITCH ERROR: ${e.message}")
            } finally {
                // --- Phase 7: Process kill ---
                exitProcess(0)
            }
        }
    }

    /**
     * Loescht bekannte QR-Artefakte (temporaer gespeicherte QR-Bilder beim Export/Share)
     * aus cacheDir und externalCacheDir.
     */
    private suspend fun wipeQRArtifacts() {
        val patterns = listOf("ONYX_", "master_qr", "reg_qr", ".png", ".jpg")
        val dirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
        for (dir in dirs) {
            if (!dir.exists()) continue
            try {
                dir.walkTopDown().forEach { f ->
                    if (f.isFile && patterns.any { p -> f.name.contains(p, ignoreCase = true) }) {
                        secureOverwriteFile(f)
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.log("WIPE", "wipeQRArtifacts error in ${dir.path}: ${e.message}")
            }
        }
    }

    private suspend fun wipeFileAndSidecars(file: File) {
        if (!file.exists()) return
        secureOverwriteFile(file)
        secureOverwriteFile(File(file.path + "-shm"))
        secureOverwriteFile(File(file.path + "-wal"))
        secureOverwriteFile(File(file.path + "-journal"))
    }

    suspend fun secureOverwriteFile(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext true
        try {
            val size = file.length()
            if (size <= 0) return@withContext file.delete()

            // 3-Pass Overwrite nach NIST SP 800-88: Zufall -> Nullen -> Muster -> delete
            val buffer = ByteArray(if (size > 1024 * 1024) 1024 * 1024 else size.toInt())

            repeat(3) { pass ->
                file.outputStream().use { out ->
                    var remaining = size
                    while (remaining > 0) {
                        val toWrite = if (remaining > buffer.size) buffer.size else remaining.toInt()
                        when (pass) {
                            0 -> secureRandom.nextBytes(buffer) // Zufall
                            1 -> buffer.fill(0x00)              // Nullen
                            2 -> buffer.fill(0xFF.toByte())     // Muster (1-Pattern)
                        }
                        out.write(buffer, 0, toWrite)
                        remaining -= toWrite
                    }
                    out.flush()
                }
            }
            file.delete()
        } catch (e: Exception) {
            file.delete()
            false
        }
    }

    private suspend fun secureOverwriteDirectory(dir: File): Boolean = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext true
        try {
            dir.walkTopDown().forEach { if (it.isFile) secureOverwriteFile(it) }
            dir.deleteRecursively()
        } catch (e: Exception) {
            dir.deleteRecursively()
            false
        }
    }
}
