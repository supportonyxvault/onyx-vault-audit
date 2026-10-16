package com.onyxvault.messenger.manager

import android.content.Context
import android.util.Log
import com.onyxvault.messenger.data.AppDatabase
import com.onyxvault.messenger.data.ContactEntity
import com.onyxvault.messenger.model.Contact
import com.onyxvault.messenger.tor.TorManager
import com.onyxvault.messenger.utils.SecurePrefs
import com.onyxvault.messenger.nostr.NostrManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ContactManager – ONYX VAULT (Forensic Sovereign Edition)
 * Verwaltet Kontakte dezentral via Nostr & Tor.
 */
class ContactManager(
    private val context: Context,
    private val torManager: TorManager,
    private val database: AppDatabase
) {
    private val TAG = "ContactManager"
    private val contactDao = database.contactDao()
    private val prefs = SecurePrefs(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts = _contacts.asStateFlow()

    private var nostrManager: NostrManager? = null

    init {
        loadContacts()
        
        // Sobald die anonyme Onion-Adresse steht, wird sie via Nostr dezentral bekanntgegeben
        scope.launch {
            torManager.onionAddress.collectLatest { onion ->
                if (!onion.isNullOrBlank()) {
                    nostrManager?.broadcastIdentity(onion)
                }
            }
        }
    }

    fun setNostrManager(nm: NostrManager) {
        this.nostrManager = nm
    }

    private fun loadContacts() {
        scope.launch {
            contactDao.getAllContacts().collect { entities ->
                _contacts.value = entities.map { it.toModel() }
            }
        }
    }

    /**
     * Aktualisiert die Route eines Kontakts dezentral (Nostr Discovery).
     */
    fun updateContactRoute(pubKey: String, newOnion: String) {
        scope.launch {
            val contact = _contacts.value.find { it.masterPublicKey == pubKey }
            if (contact != null && contact.sessionOnion != newOnion) {
                val entity = contactDao.getContact(contact.username)
                if (entity != null) {
                    contactDao.updateContact(entity.copy(onionAddress = newOnion))
                    Log.i(TAG, "Dezentrales Routing-Update für @${contact.username}")
                }
            }
        }
    }

    /**
     * Löscht einen Kontakt dezentral, wenn dieser ein Peer-Gone Signal gesendet hat.
     */
    fun deleteContactByPubKey(pubKey: String) {
        scope.launch {
            val contact = _contacts.value.find { it.masterPublicKey == pubKey }
            if (contact != null) {
                deleteContact(contact.username)
                Log.e(TAG, "Verbindung zu @${contact.username} wurde vom Peer dezentral beendet.")
            }
        }
    }

    fun addContact(username: String, onionAddress: String, publicKey: String) {
        scope.launch {
            val entity = ContactEntity(
                username = username,
                onionAddress = onionAddress,
                publicKey = publicKey,
                sharedSecret = ByteArray(0),
                addedAt = System.currentTimeMillis(),
                lastActive = System.currentTimeMillis(),
                messageCounter = 0
            )
            contactDao.insertContact(entity)
        }
    }

    fun deleteContact(username: String) {
        scope.launch {
            val entity = contactDao.getContact(username)
            if (entity != null) {
                // Hier könnte zusätzlich ein 3-Phasen-Wipe der Chatnachrichten erfolgen
                contactDao.deleteContact(entity)
            }
        }
    }

    suspend fun getContact(username: String): Contact? = withContext(Dispatchers.IO) {
        contactDao.getContact(username)?.toModel()
    }

    fun getMyUsername(): String = prefs.getUsername() ?: "user"

    private fun ContactEntity.toModel() = Contact(
        username = username,
        masterPublicKey = publicKey,
        sessionOnion = onionAddress,
        sessionPublicKey = publicKey
    )
}
