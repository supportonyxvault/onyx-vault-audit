package com.onyxvault.messenger.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.model.User
import com.onyxvault.messenger.qr.QRCodeManager
import com.onyxvault.messenger.savecard.SaveCardGenerator
import com.onyxvault.messenger.ui.theme.ErrorRed
import com.onyxvault.messenger.ui.theme.OnyxBubbleReceived
import com.onyxvault.messenger.ui.theme.OnyxBubbleSent
import com.onyxvault.messenger.ui.theme.OnyxChatBackground
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import com.onyxvault.messenger.ui.theme.WarnAmber

/**
 * ONYX VAULT - SaveCard Screen V1.1
 * Erzeugt klappbare Partner-Notfall-Karte als PDF direkt in der App.
 * Komplett im ONYX VAULT Dark-Teal Design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveCardScreen(
    user: User,
    keystore: AdaptiveKeystore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var preferOnion by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SaveCardGenerator.SaveCardResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = OnyxChatBackground,
        topBar = {
            TopAppBar(
                title = { Text("SaveCard - Notfall-Karte", fontWeight = FontWeight.Bold, color = OnyxTurquoise) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = OnyxTurquoise) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OnyxChatBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info Card
            Card(colors = CardDefaults.cardColors(containerColor = OnyxBubbleReceived)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Was ist SaveCard?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OnyxTurquoise)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Erzeuge eine klappbare Scheckkarte zum Ausdrucken. " +
                        "Deine Vertrauensperson (Partner, Anwalt, Familie) kann im Notfall den Killswitch " +
                        "ausloesen - ohne App, ohne Tor Browser, nur mit Phone-Kamera.",
                        color = Color.White, fontSize = 14.sp
                    )
                }
            }

            // Mode Selection
            Card(colors = CardDefaults.cardColors(containerColor = OnyxBubbleReceived)) {
                Column(Modifier.padding(16.dp)) {
                    Text("1. URL-Modus waehlen", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OnyxTurquoise)
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.Top) {
                        RadioButton(
                            selected = !preferOnion,
                            onClick = { preferOnion = false },
                            colors = RadioButtonDefaults.colors(selectedColor = OnyxTurquoise, unselectedColor = Color.White)
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("Standard (Massen-Ernstfall)", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Partner braucht nur normale Phone-Kamera. GitHub Pages Fallback. IP leakt im Notfall - akzeptiert.",
                                fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        RadioButton(
                            selected = preferOnion,
                            onClick = { preferOnion = true },
                            colors = RadioButtonDefaults.colors(selectedColor = OnyxTurquoise, unselectedColor = Color.White)
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("Max-Anonym (Tor-only)", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Partner MUSS Tor Browser installiert haben. 100% anonym - keine IP leaks.",
                                fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Generate Button
            Button(
                onClick = {
                    isGenerating = true; error = null
                    try {
                        val masterQrText = QRCodeManager.generateMasterQRString(context, user)
                        result = SaveCardGenerator.createSaveCard(context, masterQrText, preferOnion)
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        isGenerating = false
                    }
                },
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise, contentColor = Color.Black)
            ) {
                Text(if (isGenerating) "Erzeuge..." else "SaveCard PDF generieren", fontWeight = FontWeight.Bold)
            }

            // Result
            result?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = OnyxBubbleSent)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SaveCard erzeugt!", fontWeight = FontWeight.Bold, color = OnyxTurquoise, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Token: ${r.tokenHex.take(12)}...", fontSize = 12.sp, color = Color.White)
                        Text("Trigger: ${r.triggerUrl.take(60)}...", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    r.pdfFile
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "SaveCard teilen / drucken"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise, contentColor = Color.Black)
                        ) { Text("Teilen / Drucken / Speichern", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // Error (ONYX-styled)
            error?.let { e ->
                Card(colors = CardDefaults.cardColors(containerColor = OnyxBubbleReceived)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Fehler: ", color = ErrorRed, fontWeight = FontWeight.Bold)
                        Text(e, color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // Warnings (ONYX-styled, kein Braun!)
            Card(colors = CardDefaults.cardColors(containerColor = OnyxBubbleReceived)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("WICHTIG", fontWeight = FontWeight.Bold, color = WarnAmber, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "- Drucke die Karte aus - nicht digital speichern\n" +
                        "- In versiegelten Umschlag mit Hinweis 'NUR IM NOTFALL'\n" +
                        "- An vertrauenswuerdige Person uebergeben\n" +
                        "- Einmal mit Partner durchspielen (ohne auszuloesen)\n" +
                        "- Jeder, der die Karte hat, kann den Killswitch starten",
                        fontSize = 13.sp, color = Color.White, lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
