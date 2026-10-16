package com.onyxvault.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.OnyxUrls
import com.onyxvault.messenger.qr.QRCodeManager
import com.onyxvault.messenger.recovery.RecoveryManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    recoveryManager: RecoveryManager,
    onRecoveryComplete: () -> Unit
) {
    val remainingTime by recoveryManager.remainingTime.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedQR by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> selectedQR = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sicherheits-Sperre", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFB71C1C),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.GppBad, null, modifier = Modifier.size(80.dp), tint = Color(0xFFB71C1C))
            Spacer(Modifier.height(16.dp))
            Text("App gesperrt", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFFB71C1C))
            Text("Zu viele PIN-Fehlversuche.", color = Color.Gray, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))

            // === Countdown-Karte ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Automatischer Daten-Wipe in:", fontSize = 14.sp, color = Color(0xFFB71C1C))
                    Text(remainingTime, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = Color(0xFFB71C1C))
                    Text("Laden Sie Ihren Master-QR hoch oder nutzen Sie die Website zum Entsperren.",
                        fontSize = 12.sp, textAlign = TextAlign.Center, color = Color.DarkGray)
                }
            }

            Spacer(Modifier.height(32.dp))

            // === WEG 1: Master-QR hochladen ===
            Text("Weg 1: Master-QR aus Galerie",
                fontWeight = FontWeight.Bold, color = Color.DarkGray,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { filePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.QrCode, null)
                Spacer(Modifier.width(12.dp))
                Text(if (selectedQR != null) "Master-QR geladen" else "Master-QR auswaehlen")
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    selectedQR?.let { uri ->
                        isLoading = true
                        scope.launch {
                            try {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                val bitmap = BitmapFactory.decodeStream(inputStream)
                                val qrContent = QRCodeManager.scanQRCode(bitmap)

                                if (qrContent != null) {
                                    val success = recoveryManager.processRecoveryQR(qrContent)
                                    if (success) {
                                        statusMessage = "Account erfolgreich wiederhergestellt!"
                                        isError = false
                                        onRecoveryComplete()
                                    } else {
                                        statusMessage = "Ungueltiger Master-QR oder appId stimmt nicht mit lokalem Tresor ueberein."
                                        isError = true
                                    }
                                } else {
                                    statusMessage = "Kein QR-Code im Bild gefunden."
                                    isError = true
                                }
                            } catch (e: Exception) {
                                statusMessage = "Fehler beim Verarbeiten: ${e.message}"
                                isError = true
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = selectedQR != null && !isLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                else Text("Account entsperren", fontWeight = FontWeight.Bold)
            }

            statusMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = if (isError) Color.Red else Color(0xFF2E7D32),
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(32.dp))

            // === WEG 2: Externe Recovery-Website oeffnen ===
            HorizontalDivider(color = Color.LightGray)
            Spacer(Modifier.height(24.dp))

            Text("Weg 2: Recovery via Website (Nostr)",
                fontWeight = FontWeight.Bold, color = Color.DarkGray,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            Spacer(Modifier.height(8.dp))
            Text(
                "Oeffne die Recovery-Seite auf einem anderen Geraet, lade dort deinen Master-QR hoch und sende das Recovery-Signal ueber Nostr. Dieses Handy entsperrt sich dann automatisch sobald es online geht.",
                fontSize = 13.sp, color = Color.DarkGray, textAlign = TextAlign.Start
            )
            Spacer(Modifier.height(12.dp))

            // URL als lesbarer Text (zum abtippen falls Browser kaputt)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Link, null, tint = Color.DarkGray, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        OnyxUrls.HUMAN_READABLE + "/recovery.html",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray
                    )
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ONYX Recovery URL", OnyxUrls.RECOVERY_URL))
                            Toast.makeText(context, "Link kopiert", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, "Kopieren", tint = Color.DarkGray, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(OnyxUrls.RECOVERY_URL))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Kein Browser gefunden - Link kopieren und manuell öffnen.", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Icon(Icons.Default.OpenInBrowser, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Recovery-Website öffnen", fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Hinweis: Die Website ist Zero-Server. Dein Master-QR wird lokal im Browser entschluesselt. Niemand (auch nicht wir) sieht deine Identitaet oder deine Daten.",
                fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
