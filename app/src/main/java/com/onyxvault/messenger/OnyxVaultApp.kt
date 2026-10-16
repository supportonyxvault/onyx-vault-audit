@file:Suppress("DEPRECATION")
package com.onyxvault.messenger

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.onyxvault.messenger.chat.ChatManager
import com.onyxvault.messenger.crypto.SecureWiper
import com.onyxvault.messenger.killswitch.KillSwitchManager
import com.onyxvault.messenger.manager.ContactManager
import com.onyxvault.messenger.manager.FriendRequestManager
import com.onyxvault.messenger.model.User
import com.onyxvault.messenger.p2p.P2PServer
import com.onyxvault.messenger.recovery.RecoveryManager
import com.onyxvault.messenger.tor.TorManager
import com.onyxvault.messenger.utils.NotificationHelper
import com.onyxvault.messenger.utils.SecurePrefs
import com.onyxvault.messenger.utils.DiagnosticLogger
import com.onyxvault.messenger.nostr.NostrRelayPool
import com.onyxvault.messenger.nostr.NostrManager
import com.onyxvault.messenger.nostr.KillCommandListener
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.data.AppDatabase
import com.onyxvault.messenger.security.AuditManager
import com.onyxvault.messenger.security.PegasusDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.Security

/**
 * ONYX VAULT - Forensic Core Hub
 */
class OnyxVaultApp : Application() {

    companion object { 
        private const val TAG = "OnyxVaultApp"
        private const val EXPECTED_SIGNATURE_HASH = "OFFICIAL_RELEASE_HASH_REQUIRED"
    }

    lateinit var securePrefs: SecurePrefs
    lateinit var secureWiper: SecureWiper
    lateinit var torManager: TorManager
    lateinit var notificationHelper: NotificationHelper
    lateinit var nostrRelayPool: NostrRelayPool
    lateinit var nostrManager: NostrManager
    lateinit var adaptiveKeystore: AdaptiveKeystore
    lateinit var auditManager: AuditManager
    lateinit var recoveryManager: RecoveryManager
    
    private val _chatManager = MutableStateFlow<ChatManager?>(null)
    val chatManager = _chatManager.asStateFlow()

    private val _contactManager = MutableStateFlow<ContactManager?>(null)
    val contactManager = _contactManager.asStateFlow()

    private val _killSwitchManager = MutableStateFlow<KillSwitchManager?>(null)
    val killSwitchManager = _killSwitchManager.asStateFlow()

    private val _friendRequestManager = MutableStateFlow<FriendRequestManager?>(null)
    val friendRequestManager = _friendRequestManager.asStateFlow()
    
    var p2pServer: P2PServer? = null
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _isDbUnlocked = MutableStateFlow(false)
    val isDbUnlocked = _isDbUnlocked.asStateFlow()

    @Volatile private var killCommandListener: KillCommandListener? = null
    @Volatile private var _killCommandListenerActive: Boolean = false

    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.init(this)
        DiagnosticLogger.log("APP", "onCreate - process start")
        
        adaptiveKeystore = AdaptiveKeystore(this)
        securePrefs = SecurePrefs(this)
        notificationHelper = NotificationHelper(this)
        secureWiper = SecureWiper(this)
        auditManager = AuditManager(this)
        recoveryManager = RecoveryManager(this, secureWiper)
        torManager = TorManager(this)
        nostrRelayPool = NostrRelayPool()
        nostrManager = NostrManager(this, nostrRelayPool, adaptiveKeystore)

        if (!verifyAppIntegrity()) {
            appScope.launch { secureWiper.performSecureWipe("IntegritÃ¤tsfehler") }
            return
        }
        
        appScope.launch {
            try {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                }
                // KRITISCH v36: Nostr-Infrastruktur VOR dem PIN-Unlock hochziehen,
                // damit Recovery/Killswitch-Signale auch bei gesperrter App ankommen.
                ensureKillSwitchKeys()
                if (securePrefs.isTorEnabled()) {
                    try { nostrRelayPool.connectAll() } catch (e: Exception) { DiagnosticLogger.log("NOSTR", "connectAll error: ${e.message}") }
                }
                startKillCommandListener()
                launch(Dispatchers.Main) {
                    _isInitialized.value = true
                    Log.i(TAG, "ONYX Core Ready.")
                }
            } catch (e: Exception) { Log.e(TAG, "Init Error: ${e.message}") }
        }
    }

    /**
     * KRITISCH v36: Stellt sicher dass ks_pub+ks_priv in SecurePrefs liegen.
     * Falls fehlend, werden sie beim ersten App-Start generiert und gespeichert.
     * Ohne das: KillCommandListener skipt mit "no ks_pub stored" und keine
     * Website kann je ein Signal auf diesem Handy zustellen.
     */
    private fun ensureKillSwitchKeys() {
        val existingPub = securePrefs.getKillSwitchPubKey()
        val existingPriv = securePrefs.getKillSwitchPrivKey()
        if (!existingPub.isNullOrBlank() && !existingPriv.isNullOrBlank()) {
            DiagnosticLogger.log("APP", "ensureKillSwitchKeys: existing ks_pub=${existingPub.take(16)}...")
            return
        }
        try {
            val kp = com.onyxvault.messenger.crypto.MessageEncryption.generateNostrKeyPair()
            securePrefs.saveKillSwitchKeys(kp.publicKeyHex, kp.privateKeyHex)
            DiagnosticLogger.log("APP", "ensureKillSwitchKeys: GENERATED new ks_pub=${kp.publicKeyHex.take(16)}...")
        } catch (e: Exception) {
            DiagnosticLogger.log("APP", "ensureKillSwitchKeys ERROR: ${e.message}")
        }
    }

    fun unlockAndInitialize(pin: String) {
        appScope.launch(Dispatchers.IO) {
            // KRITISCH: IMMER PIN gegen DB pruefen - niemals skippen,
            // auch wenn _isDbUnlocked schon true ist (stale state aus vorheriger Session).
            if (!AppDatabase.checkAccess(this@OnyxVaultApp, pin)) {
                DiagnosticLogger.log("APP", "unlockAndInitialize: PIN REJECTED by AppDatabase.checkAccess")
                _isDbUnlocked.value = false
                // registerFailedAttempt wird in PinLockScreen gerufen - nicht doppelt zaehlen
                return@launch
            }
            // PIN war korrekt. Falls DB bereits initialisiert, nur Erfolg melden.
            if (_isDbUnlocked.value) {
                DiagnosticLogger.log("APP", "unlockAndInitialize: PIN correct, DB already initialized")
                return@launch
            }

            try {
                val db = AppDatabase.getInstance(this@OnyxVaultApp, pin)
                
                launch(Dispatchers.Main) {
                    auditManager.initialize(db)
                    
                    // Forensischer Scan beim Entriegeln
                    val detector = PegasusDetector(this@OnyxVaultApp)
                    val report = detector.scanDevice()
                    
                    if (report.threatsDetected.isNotEmpty()) {
                        report.threatsDetected.forEach { threat ->
                            // FIX: Strenge Einstufung. INFO (Dev-Optionen) wird NIEMALS geloggt.
                            val auditSeverity = when (threat.severity) {
                                PegasusDetector.Severity.CRITICAL -> "CRITICAL"
                                PegasusDetector.Severity.HIGH -> "CRITICAL"
                                PegasusDetector.Severity.MEDIUM -> "WARNING"
                                else -> null // Verwirft INFO/LOW komplett
                            }
                            
                            if (auditSeverity != null) {
                                auditManager.logEvent("SECURITY", "Befund: ${threat.details}", auditSeverity)
                            }
                        }
                    }

                    // KRITISCH: Ãœberwachung der Onion-IdentitÃ¤t & Dezentrale Discovery
                    appScope.launch {
                        torManager.onionAddress.collectLatest { onion ->
                            if (!onion.isNullOrBlank()) {
                                auditManager.logEvent("NETWORK", "IdentitÃ¤t aktiv: $onion", "WARNING")
                                nostrManager.broadcastIdentity(onion)
                                nostrManager.syncSovereignIdentity()
                            }
                        }
                    }

                    val cm = ContactManager(this@OnyxVaultApp, torManager, db)
                    cm.setNostrManager(nostrManager)
                    _contactManager.value = cm
                    
                    _friendRequestManager.value = FriendRequestManager(this@OnyxVaultApp, nostrRelayPool, db, notificationHelper)
                    _killSwitchManager.value = KillSwitchManager(this@OnyxVaultApp, torManager, secureWiper, cm)
                    _chatManager.value = ChatManager(this@OnyxVaultApp, nostrRelayPool, cm, notificationHelper, db)
                    
                    if (securePrefs.isTorEnabled()) {
                        startP2PServer()
                        nostrRelayPool.connectAll()
                    }
                    
                    _isDbUnlocked.value = true
                    startKillCommandListener()
                    auditManager.logEvent("SYSTEM", "Tresor forensisch entriegelt", "WARNING")

                    // v37: Recovery-Blob sicherstellen - fuer neues Setup und Upgrade von <=v36
                    if (!securePrefs.hasDbRecoveryBlob()) {
                        DiagnosticLogger.log("APP", "unlock: no recovery blob - creating one now")
                        val blobOk = AppDatabase.createRecoveryBlob(this@OnyxVaultApp, pin)
                        DiagnosticLogger.log("APP", "unlock: createRecoveryBlob returned=$blobOk")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Boot Error: ${e.message}")
            }
        }
    }

    private fun verifyAppIntegrity(): Boolean {
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) return true 
        try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                info.signatures
            }

            if (signatures.isNullOrEmpty()) return false

            for (sig in signatures) {
                val md = MessageDigest.getInstance("SHA-256")
                val hash = Base64.encodeToString(md.digest(sig.toByteArray()), Base64.NO_WRAP)
                if (EXPECTED_SIGNATURE_HASH != "OFFICIAL_RELEASE_HASH_REQUIRED" && hash != EXPECTED_SIGNATURE_HASH) return false
            }
            return true
        } catch (e: Exception) { return false }
    }

    fun startP2PServer() {
        appScope.launch(Dispatchers.IO) {
            val chat = _chatManager.value ?: return@launch
            val contacts = _contactManager.value ?: return@launch
            val ks = _killSwitchManager.value ?: return@launch
            try {
                if (p2pServer != null) return@launch
                p2pServer = P2PServer(chat, contacts, ks, securePrefs, 8080)
                p2pServer!!.start(this@OnyxVaultApp)
            } catch (e: Exception) { Log.e(TAG, "P2P Error") }
        }
    }

    fun shutdownApp() {
        try { killCommandListener?.stopListening() } catch (e: Exception) {}
        _killCommandListenerActive = false
        torManager.stopTor()
        try { nostrRelayPool.disconnect() } catch (e: Exception) {}
        try { p2pServer?.stop() } catch (e: Exception) {}
        p2pServer = null
        try { AppDatabase.forceClose() } catch (e: Exception) {}
        try { _chatManager.value?.cleanupOnAppClose() } catch (e: Exception) {}
        // v36: Explizit TorService mit ACTION_SHUTDOWN + stopService.
        // ACTION_SHUTDOWN sorgt dafuer dass der Service NOT_STICKY zurueckkommt
        // -> Android started ihn NICHT neu. Sonst Zombie-Notification nach Beenden.
        try {
            val stopIntent = android.content.Intent(this, com.onyxvault.messenger.tor.TorService::class.java)
            stopIntent.action = com.onyxvault.messenger.tor.TorService.ACTION_SHUTDOWN
            startService(stopIntent)
        } catch (e: Exception) {}
        try {
            stopService(android.content.Intent(this, com.onyxvault.messenger.tor.TorService::class.java))
        } catch (e: Exception) {}
        // Explizit alle Notifications canceln - sonst bleiben Zombie-Notifications nach mehreren Zyklen
        try {
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancelAll()
        } catch (e: Exception) {}
    }

    fun loadCurrentUser(): User {
        val username = securePrefs.getUsername() ?: "user"
        val pubKey = securePrefs.getMasterPublicKey() ?: ""
        return User(username = username, pin = "", masterPublicKey = pubKey, appId = securePrefs.getAppId() ?: "")
    }
    /**
     * Lockt die DB im RAM - setzt _isDbUnlocked auf false und schliesst die DB-Verbindung.
     * MUSS beim onPause/Beenden aufgerufen werden, damit beim naechsten Entriegeln
     * der PIN REAL gegen die DB geprueft wird (nicht nur ein stale isUnlocked-Flag).
     */
    fun lockDb() {
        try {
            AppDatabase.forceClose()
        } catch (e: Exception) {
            DiagnosticLogger.log("APP", "lockDb forceClose error: ${e.message}")
        }
        _isDbUnlocked.value = false
        DiagnosticLogger.log("APP", "lockDb - DB im RAM gelockt, PIN wird beim naechsten Unlock neu geprueft")
    }

    /**
     * Startet den Nostr Kill/Recovery Listener fuer dieses Geraet.
     * Hoert auf das ks_pub (BIP-340 x-only), auf das die Website Events taggt.
     */
    fun startKillCommandListener() {
        if (_killCommandListenerActive) {
            DiagnosticLogger.log("NOSTR", "startKillCommandListener: already active - skip")
            return
        }
        val myPub = securePrefs.getKillSwitchPubKey()
        if (myPub.isNullOrBlank()) {
            DiagnosticLogger.log("NOSTR", "startKillCommandListener: no ks_pub stored - skip")
            return
        }
        DiagnosticLogger.log("NOSTR", "startKillCommandListener: starting with ks_pub=${myPub.take(16)}...")
        val listener = KillCommandListener(this, nostrRelayPool, myPub)
        killCommandListener = listener
        _killCommandListenerActive = true
        listener.startListening(
            onKillCommand = { cmd ->
                val lastId = securePrefs.getLastKillEventId()
                if (lastId == cmd.eventId) {
                    DiagnosticLogger.log("NOSTR", "KILL event ${cmd.eventId.take(16)}... already processed - skip (dedupe)")
                    return@startListening
                }
                securePrefs.setLastKillEventId(cmd.eventId)
                DiagnosticLogger.log("NOSTR", "onKillCommand lambda: executing kill (locked=${_killSwitchManager.value == null}) eventId=${cmd.eventId.take(16)}...")
                val ksm = _killSwitchManager.value
                if (ksm != null) {
                    ksm.executeManualKillSwitch()
                } else {
                    // KRITISCH v36: App ist noch gelockt, aber Kill-Signal muss durchgehen!
                    DiagnosticLogger.log("NOSTR", "FALLBACK: direct secureWiper.performSecureWipe (app locked)")
                    appScope.launch(Dispatchers.IO) {
                        try { secureWiper.performSecureWipe("Remote Kill-Switch (App war gelockt)") }
                        catch (e: Exception) { DiagnosticLogger.log("NOSTR", "wipe error: ${e.message}") }
                    }
                }
            },
            onRecoveryCommand = { cmd ->
                val lastId = securePrefs.getLastRecoveryEventId()
                if (lastId == cmd.eventId) {
                    DiagnosticLogger.log("NOSTR", "RECOVERY event ${cmd.eventId.take(16)}... already processed - skip (dedupe)")
                } else {
                    securePrefs.setLastRecoveryEventId(cmd.eventId)
                    DiagnosticLogger.log("NOSTR", "onRecoveryCommand lambda: unlocking recovery eventId=${cmd.eventId.take(16)}...")
                    recoveryManager.unlockFromRemote()
                }
            }
        )
    }
}
