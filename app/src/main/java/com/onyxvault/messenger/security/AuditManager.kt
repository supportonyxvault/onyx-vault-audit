package com.onyxvault.messenger.security

import android.content.Context
import com.onyxvault.messenger.data.AppDatabase
import com.onyxvault.messenger.data.AuditEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * AuditManager – ONYX VAULT (Forensic Grade)
 * Protokolliert NUR Pegasus-Scans, Hacker-Angriffe und physische Manipulationen.
 */
class AuditManager(private val context: Context) {
    
    private var database: AppDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(db: AppDatabase) {
        this.database = db
    }

    /**
     * Liefert die verbleibenden Sicherheits-Logs aus der verschlüsselten DB.
     */
    fun getAllLogs(): Flow<List<AuditEntity>> {
        return database?.auditDao()?.getAllLogs() ?: flowOf(emptyList())
    }

    /**
     * Löscht alle Logs (Bereinigung von Fehlalarmen).
     */
    fun clearLogs() {
        scope.launch {
            try {
                database?.auditDao()?.deleteAll()
            } catch (e: Exception) { }
        }
    }

    /**
     * Protokolliert AUSSCHLIESSLICH kritische Sicherheitsereignisse.
     */
    fun logEvent(category: String, message: String, severity: String) {
        val upperSeverity = severity.uppercase()
        // Strenge Filterung: Nur Warnungen und echte Gefahren speichern.
        if (upperSeverity != "CRITICAL" && upperSeverity != "WARNING") return

        scope.launch {
            try {
                val db = database ?: return@launch
                val entity = AuditEntity(
                    timestamp = System.currentTimeMillis(),
                    category = category,
                    message = message,
                    severity = upperSeverity,
                    deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} [ONYX-SECURE]"
                )
                db.auditDao().insert(entity)
            } catch (e: Exception) { }
        }
    }

    companion object {
        fun formatTime(ms: Long): String {
            return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(ms))
        }
    }
}
