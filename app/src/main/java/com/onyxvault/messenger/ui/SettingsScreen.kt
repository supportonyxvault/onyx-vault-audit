package com.onyxvault.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.OnyxUrls
import com.onyxvault.messenger.R
import com.onyxvault.messenger.chat.ChatManager
import com.onyxvault.messenger.killswitch.KillSwitchManager
import com.onyxvault.messenger.tor.TorManager
import com.onyxvault.messenger.ui.theme.OnyxGreen
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import com.onyxvault.messenger.utils.SecurePrefs
import android.content.Intent
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    torManager:           TorManager,
    chatManager:          ChatManager,
    killSwitchManager:    KillSwitchManager,
    onNavigateToAudit: () -> Unit,
    onBackPressed: () -> Unit,
    onNavigateToQR: () -> Unit,
    onNavigateToWebsite: () -> Unit,
    onNavigateToWebsiteRegister: () -> Unit,
    onNavigateToWebsiteRecovery: () -> Unit,
    onNavigateToSaveCard: () -> Unit,
    onNavigateToWebsiteKillswitch: () -> Unit
) {
    val context      = LocalContext.current
    val app          = context.applicationContext as OnyxVaultApp
    val torReady     by torManager.torReady.collectAsState()
    val onionAddress by torManager.onionAddress.collectAsState()
    val securePrefs  = remember { SecurePrefs(context) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showKillswitchInfo by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AccountDeleteConfirmDialog(
            onConfirm = {
                showDeleteDialog = false
                killSwitchManager.executeManualKillSwitch()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showKillswitchInfo) {
        KillswitchInfoDialog(onDismiss = { showKillswitchInfo = false })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.onyx_bg_dark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.app_icon_ov),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("KONTROLLZENTRUM", fontWeight = FontWeight.Black, color = Color.White)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackPressed) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = OnyxTurquoise)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.7f))
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // ═════════ BLOCK 1: TOR INTEGRATION ═════════
                SettingsCard(title = "🧅 100% ANONYM — TOR INTEGRATION") {
                    Text(
                        "Alle Services laufen über das Tor-Netzwerk mit unseren eigenen .onion-Adressen und Onion-Nostr-Relays. Deine IP bleibt verborgen, deine Identität ist nie zuordenbar.",
                        color = Color.Gray, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Startbahn: ${OnyxUrls.ONION_HOST.take(20)}...onion", color = OnyxTurquoise, fontSize = 10.sp)
                    Spacer(Modifier.height(12.dp))

                    ServiceButton("MEIN APP-REG-CODE (QR anzeigen)", Icons.Default.QrCode2, onNavigateToQR)
                    ServiceButton("ÜBERSICHT & INFO", Icons.Default.Language, onNavigateToWebsite)
                    ServiceButton("KOSTENLOSE REGISTRIERUNG", Icons.Default.AppRegistration, onNavigateToWebsiteRegister)
                    ServiceButton("APP RECOVERY", Icons.Default.CloudUpload, onNavigateToWebsiteRecovery)
                    ServiceButton("SAVECARD FÜR PARTNER (PDF) — TOR", Icons.Default.Shield, onNavigateToSaveCard, OnyxGreen)

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showKillswitchInfo = true },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Info, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ℹ️ KILLSWITCH — Was ist das?", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                // ═════════ BLOCK 2: CLEARNET (GITHUB) ═════════
                SettingsCard(title = "🌍 CLEARNET — GITHUB PAGES") {
                    Text(
                        "⚠️ Schnelle Alternative wenn Tor nicht verfügbar. GitHub sieht nur deine IP beim Öffnen — nicht wohin dein Code gesendet wird. Mit eigenem VPN komplett anonym nutzbar.",
                        color = Color(0xFFFFC107), fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    ClearnetButton("ÜBERSICHT & INFO (GITHUB)", Icons.Default.Public,
                        onClick = { openUrl(context, OnyxUrls.CLEARNET_INDEX) })
                    ClearnetButton("KOSTENLOSE REGISTRIERUNG (GITHUB)", Icons.Default.AppRegistration,
                        onClick = { openUrl(context, "${OnyxUrls.CLEARNET_BASE}/register.html") })
                    ClearnetButton("APP RECOVERY (GITHUB)", Icons.Default.CloudUpload,
                        onClick = { openUrl(context, "${OnyxUrls.CLEARNET_BASE}/recovery.html") })
                    ClearnetButton("SAVECARD GENERATOR (GITHUB)", Icons.Default.Shield,
                        onClick = { openUrl(context, "${OnyxUrls.CLEARNET_BASE}/savecard.html") })
                }

                // ═════════ BLOCK 3: ROTIERENDE IDENTITÄT ═════════
                SettingsCard(title = "🔄 ROTIERENDE IDENTITÄT") {
                    Text(
                        "Deine Onion-Adresse für App-Nutzung und Chats. Automatische Rotation alle 24 Std inkl. 3× Überschreiben der alten Adresse vor dem Löschen.",
                        color = Color.Gray, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    StatusRow("Tor-Netzwerk", if (torReady) "Verbunden" else "Verbinde...", torReady)

                    if (onionAddress != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Aktuelle Onion-Adresse:", fontSize = 10.sp, color = Color.Gray)
                        Text(onionAddress!!, fontSize = 11.sp, color = OnyxTurquoise, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { torManager.rotateOnionIdentity() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("IDENTITÄT ROTIEREN (MANUELL)")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Automatische Rotation alle 24 Std neue Identität inkl. 3× Überschreiben der alten Adresse vor dem Löschen. Eine Rotation kann 10–15 Sekunden dauern — bitte warten.",
                            fontSize = 10.sp, color = Color.Gray
                        )
                    }
                }

                // ═════════ BLOCK 4: ACCOUNT KOMPLETT LÖSCHEN ═════════
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0000).copy(alpha = 0.8f)),
                    shape    = RoundedCornerShape(16.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🔴 ACCOUNT KOMPLETT LÖSCHEN", color = Color.Red, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Empfehlung von uns, wenn du die App nicht mehr nutzen oder einen neuen Account anlegen möchtest.",
                            color = Color.White, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Vorgehensweise des Löschvorgangs:\n" +
                            "• 3× Überschreiben sämtlicher Keys\n" +
                            "• 3× Überschreiben aller QR-Codes\n" +
                            "• 3× Überschreiben Kontakte & Chats\n" +
                            "• 3× Überschreiben Onion-Adressen\n" +
                            "• 3× Überschreiben Nostr-Nodes & Relay-Verbindungen\n" +
                            "• Name & PIN gelöscht\n" +
                            "• Zurücksetzen in Werkseinstellung — forensisch sauber",
                            color = Color.Gray, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ ACHTUNG: Daten können NICHT wiederhergestellt werden!",
                            color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick  = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) {
                            Text("ACCOUNT KOMPLETT LÖSCHEN", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "ONYX VAULT V2 — Dezentral · Verschlüsselt · Privat",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}
}

@Composable
fun KillswitchInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1419))) {
            Column(Modifier.padding(24.dp)) {
                Text("ℹ️ KILLSWITCH", fontWeight = FontWeight.Black, color = OnyxTurquoise, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Der Killswitch ist eine Fernlöschungs-Funktion. Du oder eine Vertrauensperson kann damit über die Website (SaveCard oder Master-QR) deinen Account komplett vernichten — auch wenn das Handy weg ist.\n\n" +
                    "Die Löschung läuft automatisch sobald das Handy wieder online geht und das signierte Lösch-Event aus dem Nostr-Netzwerk empfängt.\n\n" +
                    "Weitere Infos & Auslösen → auf unserer Website (TOR oder GitHub).",
                    color = Color.White, fontSize = 13.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise)
                ) {
                    Text("VERSTANDEN", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun RecoveryInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1419))) {
            Column(Modifier.padding(24.dp)) {
                Text("ℹ️ RECOVERY", fontWeight = FontWeight.Black, color = OnyxTurquoise, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Recovery hilft dir, wenn du deine PIN vergessen hast — OHNE Daten zu verlieren.\n\n" +
                    "So funktioniert's:\n" +
                    "1. Auf dem Lock-Screen auf „PIN vergessen?“ tippen\n" +
                    "2. Deinen Master-QR scannen (Papier/Foto)\n" +
                    "3. Neue PIN festlegen — fertig\n\n" +
                    "WICHTIG:\n" +
                    "• Recovery passiert komplett IN DER APP, kein Netzwerk nötig\n" +
                    "• Deine Chats, Kontakte, Keys bleiben alle erhalten\n" +
                    "• Nur die PIN wird zurückgesetzt\n\n" +
                    "Nicht verwechseln:\n" +
                    "• RECOVERY = PIN neu setzen, Daten bleiben\n" +
                    "• KILLSWITCH = Alles löschen, keine Rückkehr\n\n" +
                    "Dein Master-QR ist der Universalschlüssel — verliere ihn nie und gib ihn nur an eine Vertrauensperson weiter (oder nutze stattdessen die SaveCard).",
                    color = Color.White, fontSize = 13.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise)
                ) {
                    Text("VERSTANDEN", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AccountDeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF150000))) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("☠️", fontSize = 54.sp)
                Spacer(Modifier.height(16.dp))
                Text("ACCOUNT WIRKLICH LÖSCHEN?", fontWeight = FontWeight.Black, color = Color.Red, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dies kann NICHT rückgängig gemacht werden. Alle Daten, Keys, QR-Codes, Kontakte und Chats werden forensisch 3× überschrieben.",
                    color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("JA, LÖSCHEN", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) { Text("ABBRECHEN", color = Color.Gray) }
            }
        }
    }
}

@Composable
fun ServiceButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, color: Color = OnyxTurquoise) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(52.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun ClearnetButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(52.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFC107).copy(alpha = 0.5f))
    ) {
        Icon(icon, null, tint = Color(0xFFFFC107), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color(0xFFFFC107), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun StatusRow(label: String, value: String, isOk: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = if (isOk) OnyxGreen else Color.Red) {}
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall, color = Color.White, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}


// Backward-compat Alias fuer MainScreen
@Composable
fun KillSwitchConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AccountDeleteConfirmDialog(onConfirm = onConfirm, onDismiss = onDismiss)
}