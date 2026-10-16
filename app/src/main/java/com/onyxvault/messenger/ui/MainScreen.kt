package com.onyxvault.messenger.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.onyxvault.messenger.utils.DiagnosticLogger
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.onyxvault.messenger.MainActivity
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.R
import com.onyxvault.messenger.chat.ChatManager
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.data.FriendRequestEntity
import com.onyxvault.messenger.killswitch.KillSwitchManager
import com.onyxvault.messenger.manager.ContactManager
import com.onyxvault.messenger.model.Contact
import com.onyxvault.messenger.qr.QRCodeManager
import com.onyxvault.messenger.tor.TorManager
import com.onyxvault.messenger.ui.theme.OnyxGreen
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    torManager:           TorManager,
    contactManager:       ContactManager,
    chatManager:          ChatManager,
    killSwitchManager:    KillSwitchManager,
    onNavigateToChat:     (String) -> Unit,
    onNavigateToAudit:    () -> Unit,
    onNavigateToQR:       () -> Unit,
    onNavigateToScanQR:   () -> Unit = {},
    onNavigateToWebsite: () -> Unit,
    onNavigateToWebsiteRegister: () -> Unit,
    onNavigateToWebsiteRecovery: () -> Unit,
    onNavigateToSaveCard: () -> Unit = {},
    onNavigateToWebsiteKillswitch: () -> Unit
) {
    val context          = LocalContext.current
    val app              = context.applicationContext as OnyxVaultApp
    val torReady         by torManager.torReady.collectAsState()
    val onionAddress     by torManager.onionAddress.collectAsState()
    val contacts         by contactManager.contacts.collectAsState()
    val trigger          by chatManager.messageTrigger.collectAsState()
    val scope            = rememberCoroutineScope()
    val securePrefs      = remember { SecurePrefs(context) }
    val keystore         = remember { AdaptiveKeystore(context) }

    val pagerState = rememberPagerState(pageCount = { 3 })
    val selectedTab = pagerState.currentPage
    
    var showExitDialog   by remember { mutableStateOf(false) }
    
    val chatBgType       = remember { mutableStateOf(securePrefs.getChatBgType()) }
    val chatBgUri        = remember { mutableStateOf<String?>(securePrefs.getChatCustomBgUri()) }
    val contactsBgType   = remember { mutableStateOf(securePrefs.getContactsBgType()) }
    val contactsBgUri    = remember { mutableStateOf<String?>(securePrefs.getContactsCustomBgUri()) }

    var isBgSidebarOpen  by remember { mutableStateOf(false) }
    var showCustomBgMenu by remember { mutableStateOf(false) }
    val sidebarEnabled   = remember { securePrefs.isSidebarVisible() }

    var contactToDelete  by remember { mutableStateOf<String?>(null) }
    var isWipingContact  by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            MainActivity.startMediaGracePeriod()
            scope.launch {
                val internalPath = withContext(Dispatchers.IO) {
                    copyUriToInternal(context, uri, "custom_bg_${selectedTab}_${System.currentTimeMillis()}.jpg")
                }
                if (internalPath != null) {
                    if (selectedTab == 0) {
                        securePrefs.setChatCustomBgUri(internalPath)
                        securePrefs.setChatBgType(2)
                        chatBgUri.value = internalPath
                        chatBgType.value = 2
                    } else if (selectedTab == 1) {
                        securePrefs.setContactsCustomBgUri(internalPath)
                        securePrefs.setContactsBgType(2)
                        contactsBgUri.value = internalPath
                        contactsBgType.value = 2
                    }
                }
            }
        }
    }

    val currentType = if (selectedTab == 0) chatBgType.value else if (selectedTab == 1) contactsBgType.value else 0
    val currentUri = if (selectedTab == 0) chatBgUri.value else if (selectedTab == 1) contactsBgUri.value else null

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            
            if (selectedTab < 2) {
                when (currentType) {
                    0 -> {
                        val res = if (selectedTab == 0) R.drawable.onyx_bg_dark else R.drawable.onyx_bg_white
                        Image(painter = painterResource(id = res), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    1 -> {
                        val res = if (selectedTab == 0) R.drawable.onyx_bg_white else R.drawable.onyx_bg_dark
                        Image(painter = painterResource(id = res), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    2 -> {
                        if (currentUri != null) {
                            AsyncImage(model = currentUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Image(painter = painterResource(id = R.drawable.onyx_bg_dark), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            } else {
                Image(painter = painterResource(id = R.drawable.onyx_bg_dark), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }

            Scaffold(
                topBar = {
                    if (selectedTab < 2) {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.app_icon_ov),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = buildAnnotatedString {
                                                val textColor = if(selectedTab == 1 && currentType == 0) Color.Black else Color.White
                                                withStyle(style = SpanStyle(color = textColor, fontWeight = FontWeight.Black)) { append("ONYX ") }
                                                withStyle(style = SpanStyle(color = OnyxTurquoise, fontWeight = FontWeight.Black)) { append("VAULT") }
                                            },
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (torReady) Color.Green else Color.Red))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (torReady) "Dezentral aktiv" else "Tor-Discovery...", style = MaterialTheme.typography.labelSmall, color = if (torReady) Color.Green else Color.Red)
                                        }
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = onNavigateToQR) { Icon(Icons.Default.QrCode, null, tint = OnyxTurquoise) }
                                IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(2) } }) { 
                                    Icon(Icons.Default.Settings, null, tint = if(selectedTab == 1 && currentType == 0) Color.Black else Color.White) 
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = if(selectedTab == 1 && currentType == 0) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f))
                        )
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.Black.copy(alpha = 0.8f), tonalElevation = 8.dp) {
                        NavigationBarItem(
                            selected = selectedTab == 0, 
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } }, 
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = if(selectedTab==0) OnyxTurquoise else Color.Gray) }, 
                            label = { Text("Chats", color = Color.White) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1, 
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } }, 
                            icon = { Icon(Icons.Default.People, null, tint = if(selectedTab==1) OnyxTurquoise else Color.Gray) }, 
                            label = { Text("Kontakte", color = Color.White) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2, 
                            onClick = { scope.launch { pagerState.animateScrollToPage(2) } }, 
                            icon = { Icon(Icons.Default.Settings, null, tint = if(selectedTab==2) OnyxTurquoise else Color.Gray) }, 
                            label = { Text("Setup", color = Color.White) }
                        )
                        NavigationBarItem(selected = false, onClick = { showExitDialog = true }, icon = { Icon(Icons.Default.PowerSettingsNew, null, tint = Color.Red) }, label = { Text("Exit", color = Color.White) })
                    }
                },
                containerColor = Color.Transparent
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1
                    ) { page ->
                        when (page) {
                            0 -> Column(Modifier.fillMaxSize()) {
                                MainRequestHeaderView(app, contactManager, selectedTab, currentType)
                                Box(Modifier.weight(1f)) {
                                    ChatsTab(contacts, chatManager, trigger, onNavigateToChat, onLongClick = { 
                                        MainActivity.startMediaGracePeriod() 
                                        contactToDelete = it 
                                    })
                                }
                            }
                            1 -> Column(Modifier.fillMaxSize()) {
                                MainRequestHeaderView(app, contactManager, selectedTab, currentType)
                                Box(Modifier.weight(1f)) {
                                    KontakteTab(contacts, contactManager, chatManager, trigger, onionAddress, torReady, securePrefs, keystore, onScan = onNavigateToScanQR, onChat = onNavigateToChat, onLongClick = {
                                        MainActivity.startMediaGracePeriod() 
                                        contactToDelete = it 
                                    }, isWhiteBg = (currentType == 0 && selectedTab == 1))
                                }
                            }
                            2 -> MainSetupScreenInlay(
                                torManager = torManager,
                                app = app,
                                killSwitchManager = killSwitchManager,
                                onNavigateToAudit = onNavigateToAudit,
                                onNavigateToQR = onNavigateToQR,
                                onNavigateToWebsite = onNavigateToWebsite,
                                onNavigateToWebsiteRegister = onNavigateToWebsiteRegister,
                                onNavigateToWebsiteRecovery = onNavigateToWebsiteRecovery,
                                onNavigateToSaveCard = onNavigateToSaveCard,
                                onNavigateToWebsiteKillswitch = onNavigateToWebsiteKillswitch
                            )
                        }
                    }

                    if (sidebarEnabled && selectedTab < 2) {
                        SidebarLayer(isBgSidebarOpen, { isBgSidebarOpen = !isBgSidebarOpen }, selectedTab, chatBgType, chatBgUri, contactsBgType, contactsBgUri, securePrefs, currentType, currentUri, galleryLauncher, { showCustomBgMenu = true })
                    }
                }
            }

            if (showExitDialog) {
                MainEnergyOptionsDialog(
                    onDismiss = { showExitDialog = false },
                    onStandby = {
                        showExitDialog = false
                        securePrefs.setAppLocked(true)
                        MainActivity.isAppLocked = true
                        (context as? android.app.Activity)?.moveTaskToBack(true)
                    },
                    onExit = {
                        showExitDialog = false
                        app.shutdownApp()
                        val activity = context as? android.app.Activity
                        // Sauberes Beenden ohne "App abgestürzt"-Dialog:
                        // 1. finishAffinity() schliesst ALLE Activities der App
                        // 2. exitProcess(0) nach kurzem Delay beendet den JVM-Prozess normal
                        //    (nicht killProcess - das verursacht genau diesen Crash-Dialog)
                        activity?.finishAffinity()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            kotlin.system.exitProcess(0)
                        }, 1500)
                    }
                )
            }
            
            if (showCustomBgMenu) {
                CustomBgDialog({ showCustomBgMenu = false }, { MainActivity.startMediaGracePeriod(); galleryLauncher.launch("image/*") }, {
                    showCustomBgMenu = false
                    if(selectedTab == 0) { chatBgUri.value = null; chatBgType.value = 0; securePrefs.setChatCustomBgUri(null); securePrefs.setChatBgType(0) }
                    else if(selectedTab == 1) { contactsBgUri.value = null; contactsBgType.value = 0; securePrefs.setContactsCustomBgUri(null); securePrefs.setContactsBgType(0) }
                })
            }

            contactToDelete?.let { username ->
                DeleteContactDialog(username, isWipingContact, { contactToDelete = null }, {
                    isWipingContact = true
                    scope.launch {
                        contactManager.deleteContact(username)
                        delay(1000)
                        isWipingContact = false
                        contactToDelete = null
                    }
                })
            }
        }
    }
}

@Composable
private fun MainEnergyOptionsDialog(onDismiss: () -> Unit, onStandby: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ENERGIE-KONTROLLE", fontWeight = FontWeight.Black) },
        text = { Text("STANDBY: Minimale Tor-Verbindung bleibt aktiv.\nBEENDEN: Alles aus, 0% Akku-Verbrauch.") },
        confirmButton = {
            // Alle 3 Buttons untereinander, kein overlap mehr
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStandby, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise, contentColor = Color.Black)) {
                    Text("STANDBY (Empfang aktiv)", fontWeight = FontWeight.Bold)
                }
                Button(onClick = onExit, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B58))) {
                    Text("BEENDEN (Vollständig aus)", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("ABBRECHEN", color = Color.Gray)
                }
            }
        }
    )
}

@Composable
private fun MainRequestHeaderView(app: OnyxVaultApp, contactManager: ContactManager, selectedTab: Int, currentType: Int) {
    val frManager by app.friendRequestManager.collectAsState()
    val incomingRequests by produceState<List<FriendRequestEntity>>(initialValue = emptyList(), frManager) {
        frManager?.pendingRequests?.collect { value = it }
    }

    Column {
        incomingRequests.forEach { req ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = OnyxTurquoise.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, null, tint = OnyxTurquoise, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dezentrale Anfrage", fontSize = 10.sp, color = if(selectedTab == 1 && currentType == 0) Color.DarkGray else Color.Gray)
                        Text("@${req.username}", fontWeight = FontWeight.Bold, color = if(selectedTab == 1 && currentType == 0) Color.Black else Color.White, fontSize = 14.sp)
                    }
                    Button(onClick = { frManager?.acceptRequest(req, contactManager) }, colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise), modifier = Modifier.height(32.dp)) { Text("✓", color = Color.Black, fontWeight = FontWeight.Bold) }
                    Button(onClick = { frManager?.denyRequest(req.pubKey) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2244)), modifier = Modifier.height(32.dp)) { Text("✕", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainSetupScreenInlay(
    torManager: TorManager,
    app: OnyxVaultApp,
    killSwitchManager: KillSwitchManager,
    onNavigateToAudit: () -> Unit,
    onNavigateToQR: () -> Unit,
    onNavigateToWebsite: () -> Unit,
    onNavigateToWebsiteRegister: () -> Unit,
    onNavigateToWebsiteRecovery: () -> Unit,
    onNavigateToSaveCard: () -> Unit = {},
    onNavigateToWebsiteKillswitch: () -> Unit
) {
    val context = LocalContext.current
    val torReady by torManager.torReady.collectAsState()
    val onionAddress by torManager.onionAddress.collectAsState()
    var showKillSwitchDialog by remember { mutableStateOf(false) }
    var showKillswitchInfo by remember { mutableStateOf(false) }
    var showRecoveryInfo by remember { mutableStateOf(false) }
    if (showKillswitchInfo) { KillswitchInfoDialog(onDismiss = { showKillswitchInfo = false }) }
    if (showRecoveryInfo) { RecoveryInfoDialog(onDismiss = { showRecoveryInfo = false }) }

    if (showKillSwitchDialog) {
        KillSwitchConfirmDialog(
            onConfirm = {
                showKillSwitchDialog = false
                killSwitchManager.executeManualKillSwitch()
            },
            onDismiss = { showKillSwitchDialog = false }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("KONTROLLZENTRUM", fontWeight = FontWeight.Black, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        
        MainInlayCardBox(title = "Hardware-Netzwerk & Identität") {
            MainRowStatusLine(label = "Tor-Netzwerk", value = if (torReady) "Verbunden" else "Verbinde...", isOk = torReady)
            if (onionAddress != null) {
                Spacer(Modifier.height(8.dp))
                Text("Aktuelle Onion-Adresse:", fontSize = 10.sp, color = Color.Gray)
                Text(onionAddress!!, fontSize = 11.sp, color = OnyxTurquoise, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { 
                        torManager.rotateOnionIdentity()
                        app.auditManager.logEvent("SECURITY", "Manuelle Identitäts-Rotation durch Nutzer", "WARNING")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("IDENTITÄT ROTIEREN (MANUELL)")
                }
            }
        }

        MainInlayCardBox(title = "🧅 100% ANONYM — TOR INTEGRATION") {
            Text("Alle Services laufen über Tor-Netzwerk mit eigenen .onion-Adressen. IP bleibt verborgen, 100% anonym.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            MainRowActionBtn(label = "MEIN APP-REG-CODE (QR anzeigen)", icon = Icons.Default.QrCode2, onClick = onNavigateToQR)
            MainRowActionBtn(label = "ÜBERSICHT & INFO (TOR)", icon = Icons.Default.Language, onClick = onNavigateToWebsite)
            MainRowActionBtn(label = "KOSTENLOSE REGISTRIERUNG (TOR)", icon = Icons.Default.AppRegistration, onClick = onNavigateToWebsiteRegister)
            MainRowActionBtn(label = "SAVECARD LOKAL ERZEUGEN (OFFLINE)", icon = Icons.Default.Shield, onClick = onNavigateToSaveCard, color = Color(0xFF00E5FF))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showKillswitchInfo = true }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))) {
                Icon(Icons.Default.Info, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ℹ️ KILLSWITCH — Was ist das?", color = Color.Gray, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showRecoveryInfo = true }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))) {
                Icon(Icons.Default.Info, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ℹ️ RECOVERY — PIN vergessen?", color = Color.Gray, fontSize = 12.sp)
            }
        }

        MainInlayCardBox(title = "🌍 CLEARNET — GITHUB PAGES") {
            Text("⚠️ Alternative wenn Tor nicht geht. GitHub sieht nur deine IP beim Öffnen, nicht wohin dein Code gesendet wird. Mit VPN komplett anonym.", color = Color(0xFFFFC107), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            MainRowActionBtn(label = "ÜBERSICHT (GITHUB)", icon = Icons.Default.Public, onClick = { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.onyxvault.messenger.OnyxUrls.CLEARNET_INDEX)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(_: Exception) {} }, color = Color(0xFFFFC107))
            MainRowActionBtn(label = "REGISTRIERUNG (GITHUB)", icon = Icons.Default.AppRegistration, onClick = { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.onyxvault.messenger.OnyxUrls.CLEARNET_BASE + "/register.html")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(_: Exception) {} }, color = Color(0xFFFFC107))
            MainRowActionBtn(label = "SAVECARD GENERATOR (GITHUB)", icon = Icons.Default.Shield, onClick = { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.onyxvault.messenger.OnyxUrls.CLEARNET_BASE + "/savecard.html")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(_: Exception) {} }, color = Color(0xFFFFC107))
        }

        MainInlayCardBox(title = "Sicherheit & Forensik") {
            val securityReport = app.adaptiveKeystore.getSecurityStatusReport()
            MainRowStatusLine("Hardware-Keystore", securityReport.securityLevel.displayName, securityReport.isHardwareBacked)

            // Notification-Toggle (default: aus = fast unsichtbar)
            var notifVisible by remember { mutableStateOf(app.securePrefs.isNotificationVisible()) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hintergrund-Benachrichtigung sichtbar", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                Switch(
                    checked = notifVisible,
                    onCheckedChange = { v ->
                        notifVisible = v
                        app.securePrefs.setNotificationVisible(v)
                    }
                )
            }
            Text(
                if (notifVisible) "Zeigt Status in der Benachrichtigungsleiste." else "Standard: leere Minimal-Notification (Android zwingt Foreground-Service dazu).",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try { context.startActivity(intent) } catch (e: Exception) {}
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ANDROID BENACHRICHTIGUNGEN")
            }

            MainRowInfoLine("Verschlüsselung", "Argon2id + AES-256-GCM")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNavigateToAudit,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Icon(Icons.Default.History, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AUDIT-LOG ANZEIGEN")
            }

            Spacer(Modifier.height(12.dp))

            // === DIAGNOSE-LOG BUTTONS ===
            Button(
                onClick = {
                    val logFile = DiagnosticLogger.getLogFile(context)
                    if (logFile != null && logFile.exists() && logFile.length() > 0) {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                logFile
                            )
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "ONYX VAULT Diagnose-Log")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Diagnose-Log teilen").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (e: Exception) {
                            Toast.makeText(context, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, "Logdatei leer", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise)
            ) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("DIAGNOSE-LOG TEILEN", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    DiagnosticLogger.clear(context)
                    Toast.makeText(context, "Log geleert", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("DIAGNOSE-LOG LEEREN")
            }

            Spacer(Modifier.height(8.dp))
            // v37: TEST-DATEN einfuegen (fuer Recovery-Test)
            Button(
                onClick = {
                    try {
                        val cm = app.contactManager.value
                        if (cm == null) {
                            Toast.makeText(context, "ContactManager nicht bereit - erst Setup abschliessen", Toast.LENGTH_SHORT).show()
                        } else {
                            cm.addContact("TEST-Alice", "testalice1234567890abcdef1234567890abcdef1234567890abcdef12.onion", "02aabbccddeeff0011223344556677889900aabbccddeeff0011223344556677889")
                            cm.addContact("TEST-Bob", "testbobabcdef1234567890abcdef1234567890abcdef1234567890abcdef.onion", "03bbaaddccffee0022334455667788990011aabbccddeeff00223344556677889900")
                            DiagnosticLogger.log("TEST", "Dummy-Daten eingefuegt via ContactManager.addContact (TEST-Alice + TEST-Bob)")
                            Toast.makeText(context, "Test-Daten eingefuegt: TEST-Alice + TEST-Bob", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        DiagnosticLogger.log("TEST", "Dummy insert ERROR: ${e.message}")
                        Toast.makeText(context, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2E8F))
            ) {
                Icon(Icons.Default.Science, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("TEST-DATEN EINFUEGEN (Dummy)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0000).copy(alpha = 0.8f)),
            shape    = RoundedCornerShape(16.dp),
            border   = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("🔴 ACCOUNT KOMPLETT LÖSCHEN", color = Color.Red, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Vollständige Vernichtung deines Tresors nach NIST SP 800-88 — dem militärischen Standard für forensische Datenlöschung.",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(14.dp))

                Text("So läuft der Löschvorgang ab:", color = com.onyxvault.messenger.ui.theme.OnyxTurquoise, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Jeder Datenbereich — deine Keys, Kontakte, Chats, QR-Codes, Onion-Adressen und Nostr-Verbindungen — wird dreimal hintereinander überschrieben:\n\n" +
                    "1. Durchgang: Zufallswerte\n" +
                    "2. Durchgang: Nullen\n" +
                    "3. Durchgang: festes Prüfmuster\n\n" +
                    "Erst danach werden die Bereiche freigegeben. Dieser 3-Pass-Overwrite macht die ursprünglichen Daten physikalisch nicht wiederherstellbar — auch nicht mit forensischen Datenrettungs-Werkzeugen.",
                    color = Color.Gray, fontSize = 12.sp, lineHeight = 17.sp
                )

                Spacer(Modifier.height(12.dp))
                Text("Finale Schritte:", color = com.onyxvault.messenger.ui.theme.OnyxTurquoise, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Zum Abschluss werden deine Hardware-Keystore-Schlüssel endgültig vernichtet und die App in den Werkszustand zurückgesetzt. Dein Account existiert nach diesem Vorgang nirgends mehr.",
                    color = Color.Gray, fontSize = 12.sp, lineHeight = 17.sp
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", fontSize = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Diese Aktion ist UNUMKEHRBAR. Verwende sie nur wenn du die App dauerhaft aufgeben oder einen frischen Account starten möchtest.",
                        color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick  = { showKillSwitchDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("ACCOUNT KOMPLETT LÖSCHEN", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MainRowStatusLine(label: String, value: String, isOk: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = if (isOk) Color.Green else Color.Red) {}
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun MainRowInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Color.White)
    }
}

@Composable
private fun MainInlayCardBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), 
        shape = RoundedCornerShape(16.dp), 
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall, color = Color.White, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun MainRowActionBtn(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, color: Color = OnyxTurquoise) {
    OutlinedButton(
        onClick = onClick, 
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(52.dp), 
        shape = RoundedCornerShape(12.dp), 
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarLayer(
    isOpen: Boolean,
    onToggle: () -> Unit,
    tab: Int,
    chatBgType: MutableState<Int>,
    chatBgUri: MutableState<String?>,
    contactsBgType: MutableState<Int>,
    contactsBgUri: MutableState<String?>,
    prefs: SecurePrefs,
    currentType: Int,
    currentUri: String?,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxHeight().offset(x = if (isOpen) 0.dp else (-85).dp).animateContentSize(animationSpec = tween(500)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            modifier = Modifier.width(80.dp).wrapContentHeight(),
            shape = RoundedCornerShape(0.dp, 20.dp, 20.dp, 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
            elevation = CardDefaults.cardElevation(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(
                    modifier = Modifier.size(50.dp).clickable { 
                        if(tab == 0) { chatBgType.value = 0; prefs.setChatBgType(0) } else { contactsBgType.value = 1; prefs.setContactsBgType(1) }
                    },
                    shape = RoundedCornerShape(10.dp), border = if((currentType==0 && tab==0) || (currentType==1 && tab==1)) BorderStroke(2.dp, OnyxTurquoise) else null
                ) { Image(painter = painterResource(id = R.drawable.onyx_bg_dark), contentDescription = null, contentScale = ContentScale.Crop) }
                
                Surface(
                    modifier = Modifier.size(50.dp).clickable { 
                        if(tab == 0) { chatBgType.value = 1; prefs.setChatBgType(1) } else { contactsBgType.value = 0; prefs.setContactsBgType(0) }
                    },
                    shape = RoundedCornerShape(10.dp), border = if((currentType==1 && tab==0) || (currentType==0 && tab==1)) BorderStroke(2.dp, OnyxTurquoise) else null
                ) { Image(painter = painterResource(id = R.drawable.onyx_bg_white), contentDescription = null, contentScale = ContentScale.Crop) }
                
                Surface(
                    modifier = Modifier.size(50.dp).combinedClickable(
                        onClick = { 
                            if (currentUri != null) { 
                                if(tab == 0) { chatBgType.value = 2; prefs.setChatBgType(2) } else { contactsBgType.value = 2; prefs.setContactsBgType(2) }
                            } else { MainActivity.startMediaGracePeriod(); launcher.launch("image/*") }
                        },
                        onLongClick = onLongClick
                    ),
                    shape = RoundedCornerShape(10.dp), color = Color.DarkGray, border = if(currentType==2) BorderStroke(2.dp, OnyxTurquoise) else null
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (currentUri != null) AsyncImage(model = currentUri, contentDescription = null, contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.AddAPhoto, null, tint = OnyxTurquoise)
                    }
                }
            }
        }
        Box(modifier = Modifier.size(26.dp, 100.dp).clickable { onToggle() }, contentAlignment = Alignment.Center) {
            Icon(if(isOpen) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ExitConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ONYX VAULT") },
        text = { Text("BEENDEN: Alles aus - Tor stoppt. Nachrichten landen in der Nostr-Mailbox bis zum nächsten Start.") },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B58))) { Text("BEENDEN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun CustomBgDialog(onDismiss: () -> Unit, onSwap: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hintergrund") },
        text = { Text("Bild tauschen oder löschen?") },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSwap, modifier = Modifier.fillMaxWidth()) { Text("Tauschen") }
                Button(onClick = onDelete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Löschen") }
            }
        }
    )
}

@Composable
private fun DeleteContactDialog(username: String, isLoading: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if(!isLoading) onDismiss() },
        title = { Text("KONTAKT VERNICHTEN?") },
        text = { Text("Die Verbindung zu @$username wird forensisch getrennt. Nachrichten werden 3-fach überschrieben.") },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), enabled = !isLoading) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                else Text("WIPE & LÖSCHEN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Abbrechen") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatsTab(contacts: List<Contact>, chatManager: ChatManager, trigger: Long, onNavigate: (String) -> Unit, onLongClick: (String) -> Unit) {
    val visibleContacts = remember(contacts, trigger) {
        contacts.filter { it.username.isNotBlank() && chatManager.getMessagesForChat(it.username).isNotEmpty() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(visibleContacts, key = { it.username }) { contact ->
            Surface(
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onNavigate(contact.username) }, onLongClick = { onLongClick(contact.username) }).padding(vertical = 4.dp), 
                color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp)
            ) { ContactRow(contact.username, 0, Color.White) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun KontakteTab(contacts: List<Contact>, contactManager: ContactManager, chatManager: ChatManager, trigger: Long, onion: String?, torReady: Boolean, prefs: SecurePrefs, keystore: AdaptiveKeystore, onScan: () -> Unit, onChat: (String) -> Unit, onLongClick: (String) -> Unit, isWhiteBg: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var myQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showMyQR by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    val filteredContacts = remember(contacts, trigger) { contacts.filter { it.username.isNotBlank() } }

    Column(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { 
                if (!torReady || onion == null) { Toast.makeText(context, "Tor-Netzwerk erforderlich.", Toast.LENGTH_SHORT).show(); return@Button }
                isGenerating = true
                scope.launch { 
                    val result = withContext(Dispatchers.IO) { 
                        try { QRCodeManager.generateContactQR(context, contactManager.getMyUsername(), onion, prefs.getAppId() ?: "", prefs.getMasterPublicKey() ?: "", keystore) } catch (_: Exception) { null }
                    }
                    myQrBitmap = result; isGenerating = false; showMyQR = true 
                } 
            }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, OnyxTurquoise), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = OnyxTurquoise)) { 
                if (isGenerating) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = OnyxTurquoise) else Text("MEIN KONTAKT QR", fontWeight = FontWeight.Bold, fontSize = 11.sp) 
            }
            Button(onClick = onScan, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise)) { Text("SCANNEN", fontWeight = FontWeight.Bold, color = Color.Black) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) { 
            items(filteredContacts, key = { it.username }) { contact -> 
                Surface(
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onChat(contact.username) }, onLongClick = { onLongClick(contact.username) }).padding(vertical = 4.dp), 
                    color = (if(isWhiteBg) Color.White else Color.Black).copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp),
                    border = if(isWhiteBg) BorderStroke(0.5.dp, Color.LightGray) else null
                ) { ContactRow(contact.username, 0, if(isWhiteBg) Color.Black else Color.White) } 
            } 
        }
    }

    if (showMyQR && myQrBitmap != null) {
        Dialog(onDismissRequest = { showMyQR = false }) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("KONTAKT QR CODE", fontWeight = FontWeight.Black, color = Color.Black)
                    Spacer(Modifier.height(16.dp))
                    Image(bitmap = myQrBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(220.dp))
                    Spacer(Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { 
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) { saveQRToGallery(context, myQrBitmap!!, prefs.getUsername() ?: "user") }
                                Toast.makeText(context, if(saved) "Gesichert!" else "Fehler", Toast.LENGTH_SHORT).show()
                            }
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text("SICHERN", fontSize = 10.sp) }
                        
                        Button(onClick = { shareQR(context, myQrBitmap!!, prefs.getUsername() ?: "user") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise, contentColor = Color.Black)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("TEILEN", fontSize = 10.sp) }
                    }
                    TextButton(onClick = { showMyQR = false }, modifier = Modifier.fillMaxWidth()) { Text("SCHLIESSEN", color = Color.Gray) }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(username: String, unreadCount: Int, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
        Surface(Modifier.size(48.dp), shape = CircleShape, color = OnyxTurquoise.copy(alpha = 0.2f)) { Box(contentAlignment = Alignment.Center) { Text(username.take(1).uppercase(), color = OnyxTurquoise, fontWeight = FontWeight.Bold) } }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) { Text("@$username", color = textColor, fontWeight = FontWeight.Bold); Text("Forensisch verriegelt", color = Color.Gray, fontSize = 11.sp) }
        if (unreadCount > 0) { Surface(color = OnyxTurquoise, shape = CircleShape) { Text(text = unreadCount.toString(), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) } }
    }
}

private fun copyUriToInternal(context: android.content.Context, uri: Uri, fileName: String): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.filesDir, fileName)
        file.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
        file.absolutePath
    } catch (_: Exception) { null }
}

private fun saveQRToGallery(context: android.content.Context, bitmap: Bitmap, username: String): Boolean {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ONYX_VAULT_CONTACT_${username}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ONYX_VAULT")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) { false }
}

private fun shareQR(context: android.content.Context, bitmap: Bitmap, username: String) {
    try {
        val cachePath = File(context.cacheDir, "images")
        cachePath.mkdirs()
        val file = File(cachePath, "onyx_contact_qr.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val contentUri = androidx.core.content.FileProvider.getUriForFile(context, "com.onyxvault.messenger.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Kontakt-QR teilen"))
    } catch (e: Exception) { }
}
