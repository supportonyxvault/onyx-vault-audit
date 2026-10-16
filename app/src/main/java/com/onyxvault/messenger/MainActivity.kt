@file:Suppress("DEPRECATION")
package com.onyxvault.messenger

import android.content.Intent
import android.net.Uri
import com.onyxvault.messenger.OnyxUrls
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.crypto.BipPassphrase
import com.onyxvault.messenger.security.PegasusDetector
import com.onyxvault.messenger.tor.TorService
import com.onyxvault.messenger.ui.*
import com.onyxvault.messenger.ui.theme.OnyxVaultTheme
import com.onyxvault.messenger.utils.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ONYX VAULT - Forensic Entry Point
 */
class MainActivity : ComponentActivity() {

    companion object {
        var isAppLocked by mutableStateOf(false)
        var isMediaActionActive by mutableStateOf(false)
        var mediaActionStartTime by mutableLongStateOf(0L)

        fun startMediaGracePeriod() {
            isMediaActionActive = true
            mediaActionStartTime = System.currentTimeMillis()
        }
        
        fun stopMediaGracePeriod() {
            isMediaActionActive = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Initialisiere DiagnosticLogger fuer die Session
        DiagnosticLogger.init(this)

        val app = application as OnyxVaultApp
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        BipPassphrase.initialize(this)
        val adaptiveKeystore = AdaptiveKeystore(this)
        try { adaptiveKeystore.getOrCreateMasterKey() } catch (_: Exception) { }

        val detector = PegasusDetector(this)
        val report = detector.scanDevice()
        if (report.overallRisk == PegasusDetector.RiskLevel.CRITICAL) {
            app.appScope.launch {
                app.secureWiper.performSecureWipe("Kritische Bedrohung erkannt")
            }
        }
        
        if (app.securePrefs.getUsername() != null && app.securePrefs.isTorEnabled()) {
            // Stale Tor-Lock-Dateien aufraeumen, falls letzter Shutdown hart war.
            // Das verhindert die "beim 1. Start Dauerschleife" Situation.
            try {
                val torDir = getDir("TorService", android.content.Context.MODE_PRIVATE)
                java.io.File(torDir, "lock").delete()
                java.io.File(torDir, "cached-microdesc-consensus.tmp").delete()
                java.io.File(torDir, "cached-descriptors.new").delete()
                DiagnosticLogger.log("MainActivity", "Stale Tor-Lock entfernt (falls vorhanden)")
            } catch (e: Exception) { /* ignore */ }
            app.torManager.startTor()
        }
        
        isAppLocked = if (!app.securePrefs.isPinLockEnabled()) false else app.securePrefs.isAppLocked()
        splashScreen.setKeepOnScreenCondition { !app.isInitialized.value }
        enableEdgeToEdge()

        setContent {
            OnyxVaultTheme {
                val appReady by app.isInitialized.collectAsState()
                val dbUnlocked by app.isDbUnlocked.collectAsState()
                val ksManager by app.killSwitchManager.collectAsState()

                if (appReady) {
                    val now = System.currentTimeMillis()
                    val isWithinGrace = isMediaActionActive && (now - mediaActionStartTime < 30000L /* 30s - PIN sicherer */)
                    val setupDone = app.securePrefs.getUsername() != null
                    // WICHTIG: Direkt aus prefs lesen, NICHT aus memory-flag isAppLocked
                    // (sonst wird nach App-Wisch der persistierte Lock-State ignoriert)
                    val persistedLocked = app.securePrefs.isAppLocked()
                    val pinLockEnabled = app.securePrefs.isPinLockEnabled()
                    val showPinLock = setupDone && pinLockEnabled && (isAppLocked || persistedLocked) && !isWithinGrace
                    val recoveryIsLocked by app.recoveryManager.isLocked.collectAsState()
                    // v36: Flag das von RecoveryManager gesetzt wird nach erfolgreicher Recovery
                    var requireNewPin by remember { mutableStateOf(app.securePrefs.isRequireNewPin()) }

                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            // v36: NACH Recovery MUSS eine neue PIN vergeben werden (hoechste Prioritaet)
                            requireNewPin -> NewPinSetupScreen(
                                onPinSet = { requireNewPin = false }
                            )
                            // Brute-force protection: wenn Lock aktiv ist, zeige NUR RecoveryScreen
                            recoveryIsLocked -> RecoveryScreen(
                                recoveryManager = app.recoveryManager,
                                onRecoveryComplete = {
                                    // v36: nach Recovery -> requireNewPin ist jetzt true, UI schaltet um
                                    requireNewPin = app.securePrefs.isRequireNewPin()
                                    isAppLocked = false
                                    app.securePrefs.setAppLocked(false)
                                }
                            )
                            showPinLock -> PinLockScreen(
                                onUnlocked = {
                                    isAppLocked = false
                                    app.securePrefs.setAppLocked(false)
                                },
                                onTooManyAttempts = {
                                    // Nur UI-Trigger - registerFailedAttempt wird bereits
                                    // in PinLockScreen gerufen (sonst doppelt gezaehlt!)
                                }
                            )
                            setupDone && !dbUnlocked -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            else -> OnyxVaultNav(app)
                        }

                        ksManager?.let { manager ->
                            val killSwitchActive by manager.killSwitchActive.collectAsState()
                            if (killSwitchActive) {
                                 val status by manager.wipeStatus.collectAsState()
                                 val progress by manager.wipeProgress.collectAsState()
                                 KillSwitchProgressOverlay(progress = progress, status = status)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val app = application as OnyxVaultApp
        if (app.securePrefs.isTorEnabled()) {
            startService(Intent(this, TorService::class.java).apply { action = TorService.ACTION_APP_BACKGROUND })
        }
        if (!(isMediaActionActive && (System.currentTimeMillis() - mediaActionStartTime < 30000L /* 30s - PIN sicherer */)) && app.securePrefs.isPinLockEnabled()) {
            isAppLocked = true
            app.securePrefs.setAppLocked(true)
            // KRITISCH: DB im RAM locken - PIN wird beim naechsten Entriegeln REAL geprueft
            app.lockDb()
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as OnyxVaultApp
        if (app.securePrefs.isTorEnabled()) {
            startService(Intent(this, TorService::class.java).apply { action = TorService.ACTION_APP_FOREGROUND })
        }
        if (isMediaActionActive && (System.currentTimeMillis() - mediaActionStartTime < 30000L /* 30s - PIN sicherer */)) {
            isAppLocked = false
            app.securePrefs.setAppLocked(false)
            stopMediaGracePeriod()
        }
    }
}

@Composable
fun OnyxVaultNav(app: OnyxVaultApp) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val isSetupDone = app.securePrefs.getUsername() != null
    val startDestination = if (isSetupDone) "main" else "setup"
    
    // States sammeln, um .value Zugriffe in Composables zu vermeiden
    val isDbUnlocked by app.isDbUnlocked.collectAsState()
    val contactManager by app.contactManager.collectAsState()
    val chatManager by app.chatManager.collectAsState()
    val killSwitchManager by app.killSwitchManager.collectAsState()

    NavHost(navController = navController, startDestination = startDestination) {
        composable("setup") {
            SetupScreen(onSetupComplete = {
                navController.navigate("main") { popUpTo("setup") { inclusive = true } }
            })
        }
        composable("main") {
            if (isDbUnlocked && contactManager != null && chatManager != null && killSwitchManager != null) {
                MainScreen(
                    torManager = app.torManager,
                    contactManager = contactManager!!,
                    chatManager = chatManager!!,
                    killSwitchManager = killSwitchManager!!,
                    onNavigateToChat = { partner -> navController.navigate("chat/$partner") },
                    onNavigateToAudit = { navController.navigate("audit") },
                    onNavigateToQR = { navController.navigate("qr") },
                    onNavigateToScanQR = { navController.navigate("contact_scan") },
                    onNavigateToWebsite = { navController.navigate("website") },
                    onNavigateToWebsiteRegister = { navController.navigate("website_register") },
                    onNavigateToWebsiteRecovery = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OnyxUrls.RECOVERY_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    },
                    onNavigateToSaveCard = { navController.navigate("savecard") },
                    onNavigateToWebsiteKillswitch = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OnyxUrls.KILLSWITCH_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }
                )
            }
        }
        composable("chat/{partner}") { back: NavBackStackEntry ->
            val partner = back.arguments?.getString("partner") ?: ""
            if (isDbUnlocked && chatManager != null) {
                ChatScreen(
                    chatPartner = partner,
                    chatManager = chatManager!!,
                    onBackPressed = { navController.popBackStack() }
                )
            }
        }
        composable("audit") { AuditScreen(onBackPressed = { navController.popBackStack() }) }
        composable("qr") { QRScreen(onBackPressed = { navController.popBackStack() }) }
        composable("savecard") {
            if (isDbUnlocked) {
                val user = app.loadCurrentUser()
                if (user != null) {
                    SaveCardScreen(
                        user = user,
                        keystore = app.adaptiveKeystore,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
        composable("contact_scan") {
            if (isDbUnlocked && contactManager != null) {
                ContactQRScanScreen(
                    contactManager = contactManager!!,
                    currentUser = app.loadCurrentUser(),
                    onBackPressed = { navController.popBackStack() },
                    onSuccess = { navController.popBackStack() }
                )
            }
        }
        composable("website") { WebsiteScreen("index.html") }
        composable("website_register") { WebsiteScreen("register.html") }
        composable("website_recovery") { WebsiteScreen("recovery.html") }
        composable("website_killswitch") { WebsiteScreen("killswitch.html") }
    }
}
