package com.onyxvault.messenger.data

import android.content.Context
import android.util.Log
import androidx.room.*
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.util.UUID

// ========== ENTITIES (Zero-Hash: No PIN stored) ==========

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val username: String,
    val appId: String,
    val publicKey: String,
    val privateKeyEncrypted: String,
    val onionAddress: String?,
    val createdAt: Long
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val username: String,
    val onionAddress: String,
    val publicKey: String,
    val sharedSecret: ByteArray,
    val addedAt: Long,
    val lastActive: Long,
    val messageCounter: Int
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatPartner: String,
    val content: String,
    val isSent: Boolean,
    val timestamp: Long,
    val timerSeconds: Int,
    val isRead: Boolean = false
)

@Entity(tableName = "audit_logs")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val category: String,
    val message: String,
    val severity: String,
    val deviceInfo: String
)

@Entity(tableName = "friend_requests")
data class FriendRequestEntity(
    @PrimaryKey val pubKey: String,
    val username: String,
    val onion: String,
    val timestamp: Long,
    var status: String 
)

// ========== DAOs ==========

@Dao interface UserDao {
    @Query("SELECT * FROM users LIMIT 1") suspend fun getAnyUser(): UserEntity?
    @Insert suspend fun insertUser(user: UserEntity)
    @Query("DELETE FROM users") suspend fun deleteAll()
}

@Dao interface ContactDao {
    @Query("SELECT * FROM contacts") fun getAllContacts(): Flow<List<ContactEntity>>
    @Query("SELECT * FROM contacts WHERE username = :username") suspend fun getContact(username: String): ContactEntity?
    @Insert suspend fun insertContact(contact: ContactEntity)
    @Update suspend fun updateContact(contact: ContactEntity)
    @Delete suspend fun deleteContact(contact: ContactEntity)
}

@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatPartner = :partner ORDER BY timestamp ASC")
    fun getMessagesForChat(partner: String): Flow<List<MessageEntity>>
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessageById(id: Long)
    @Query("DELETE FROM messages WHERE chatPartner = :partner") suspend fun deleteChatMessages(partner: String)
}

@Dao interface AuditDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC") fun getAllLogs(): Flow<List<AuditEntity>>
    @Insert suspend fun insert(log: AuditEntity)
    @Query("DELETE FROM audit_logs") suspend fun deleteAll()
}

@Dao interface FriendRequestDao {
    @Query("SELECT * FROM friend_requests WHERE status = 'pending'") fun getPendingRequests(): Flow<List<FriendRequestEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRequest(request: FriendRequestEntity)
    @Query("DELETE FROM friend_requests WHERE pubKey = :pubKey") suspend fun deleteRequest(pubKey: String)
}

// ========== DATABASE (SQLCIPHER HARDWARE-PIN-BONDING) ==========

@Database(
    entities = [UserEntity::class, ContactEntity::class, MessageEntity::class, AuditEntity::class, FriendRequestEntity::class],
    version = 5, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun auditDao(): AuditDao
    abstract fun friendRequestDao(): FriendRequestDao
    
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "onyx_vault_forensic.db"
        
        fun getInstance(context: Context, userPin: String): AppDatabase {
            synchronized(this) {
                // Falls bereits eine Instanz da ist, muessen wir sie schliessen wenn wir eine neue PIN nutzen
                // Room/SQLCipher unterstuetzen keinen Key-Wechsel im laufenden Betrieb
                INSTANCE?.close()
                INSTANCE = null

                SQLiteDatabase.loadLibs(context)
                val dbKey = deriveForensicKey(context, userPin)
                val factory = SupportFactory(SQLiteDatabase.getBytes(dbKey.toCharArray()))

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
                
                INSTANCE = instance
                return instance
            }
        }

        /**
         * Forensischer Canary-Test: Kann die Datenbank mit dieser PIN geoeffnet werden?
         */
        fun checkAccess(context: Context, userPin: String): Boolean {
            return try {
                SQLiteDatabase.loadLibs(context)
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) return true 

                val dbKey = deriveForensicKey(context, userPin)
                val db = SQLiteDatabase.openDatabase(dbFile.path, dbKey, null, SQLiteDatabase.OPEN_READONLY)
                // Canary query to trigger decryption
                val cursor = db.rawQuery("SELECT count(*) FROM sqlite_master", null)
                val ok = cursor != null
                cursor?.close()
                db.close()
                ok
            } catch (e: Exception) {
                Log.w("AppDatabase", "Tresor-Zugriff verweigert (PIN falsch)")
                false
            }
        }

        fun forceClose() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /**
         * v37: Erstellt oder aktualisiert den Recovery-Blob.
         * recoveryBlob = AES-GCM(userDbKey, ks_priv) gespeichert in SecurePrefs.
         */
        fun createRecoveryBlob(context: Context, currentPin: String): Boolean {
            return try {
                val prefs = SecurePrefs(context)
                val ksPriv = prefs.getKillSwitchPrivKey() ?: run {
                    com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "createRecoveryBlob: ks_priv missing - skip")
                    return false
                }
                val userDbKey = deriveForensicKey(context, currentPin)
                val blob = com.onyxvault.messenger.crypto.EncryptionManager.encryptWithPassphrase(userDbKey, ksPriv + "ONYX_DB_RECOVERY_v37")
                prefs.saveDbRecoveryBlob(blob)
                com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "createRecoveryBlob: OK blob stored len=${blob.length}")
                true
            } catch (e: Exception) {
                com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "createRecoveryBlob ERROR: ${e.message}")
                false
            }
        }

        /**
         * v37: Entschluesselt Recovery-Blob mit ks_priv aus Master-QR,
         * holt den alten userDbKey und re-verschluesselt die DB mit neuer PIN
         * via SQLCipher PRAGMA rekey. ALLE DATEN BLEIBEN ERHALTEN.
         */
        fun rekeyUsingRecoveryBlob(context: Context, ksPriv: String, newPin: String): Boolean {
            return try {
                val prefs = SecurePrefs(context)
                val blob = prefs.getDbRecoveryBlob() ?: run {
                    com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "rekey: no recovery blob - cannot rekey")
                    return false
                }
                val oldUserDbKey = com.onyxvault.messenger.crypto.EncryptionManager.decryptWithPassphrase(blob, ksPriv + "ONYX_DB_RECOVERY_v37")
                if (oldUserDbKey.isBlank()) {
                    com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "rekey: decrypted dbKey blank - abort")
                    return false
                }
                SQLiteDatabase.loadLibs(context)
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) {
                    com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "rekey: DB file missing")
                    return false
                }
                forceClose()
                val db = SQLiteDatabase.openDatabase(dbFile.path, oldUserDbKey, null, SQLiteDatabase.OPEN_READWRITE)
                val newUserDbKey = deriveForensicKey(context, newPin)
                db.rawExecSQL("PRAGMA rekey = '" + newUserDbKey.replace("'", "''") + "'")
                db.close()
                val newBlob = com.onyxvault.messenger.crypto.EncryptionManager.encryptWithPassphrase(newUserDbKey, ksPriv + "ONYX_DB_RECOVERY_v37")
                prefs.saveDbRecoveryBlob(newBlob)
                com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "rekey: SUCCESS data preserved")
                true
            } catch (e: Exception) {
                com.onyxvault.messenger.utils.DiagnosticLogger.log("APP", "rekey ERROR: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        private fun deriveForensicKey(context: Context, pin: String): String {
            if (pin.isEmpty()) return "ONYX_BOOTSTRAP_KEY" 
            
            val prefs = SecurePrefs(context)
            var encryptedSeed = prefs.getDatabasePassword()
            if (encryptedSeed == null) {
                val newSeed = UUID.randomUUID().toString()
                encryptedSeed = EncryptionManager.encryptWithHardware(context, newSeed)
                prefs.saveDatabasePassword(encryptedSeed)
            }
            
            val hardwareSeed = EncryptionManager.decryptWithHardware(context, encryptedSeed)
            return EncryptionManager.stretchKeyWithPin(hardwareSeed, pin)
        }
    }
}
