package com.onyxvault.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.data.AppDatabase
import com.onyxvault.messenger.utils.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NewPinSetupScreen - ONYX VAULT V1
 *
 * Wird NACH erfolgreicher Recovery (Master-QR lokal oder via Nostr) angezeigt.
 * Zwingt den User zur Vergabe einer NEUEN PIN.
 *
 * v37 KEY DIFF ggü v36: NUTZT AppDatabase.rekeyUsingRecoveryBlob() statt DB-Delete.
 * ALLE DATEN BLEIBEN ERHALTEN - Chats, Kontakte, Onion-Adresse.
 * Nur die PIN tauscht.
 *
 * Fallback: Wenn Recovery-Blob fehlt (v<=36 User die nie v37-Unlock hatten vor Recovery),
 * dann hard reset wie v36 - DB wird gelöscht. User wird gewarnt.
 */
@Composable
fun NewPinSetupScreen(onPinSet: () -> Unit) {
    val context = LocalContext.current
    val app     = context.applicationContext as OnyxVaultApp
    val scope   = rememberCoroutineScope()

    val hasBlob = remember { app.securePrefs.hasDbRecoveryBlob() }
    val ksPriv  = remember { app.securePrefs.getKillSwitchPrivKey() }

    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E12)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(72.dp), tint = Color(0xFF00B8A0))
            Spacer(Modifier.height(16.dp))

            Text("NEUE MASTER-PIN", fontWeight = FontWeight.Black, color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Recovery erfolgreich. Vergib jetzt eine NEUE PIN.",
                color = Color(0xFF9FB4C2), fontSize = 13.sp, textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // Hinweis-Karte: Datenerhalt (gruen) oder Warning (gelb) je nach Blob-Verfuegbarkeit
            if (hasBlob && !ksPriv.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Deine Chats, Kontakte und Onion-Adresse bleiben erhalten. Nur die PIN tauscht.",
                            color = Color(0xFFA8E6B8), fontSize = 12.sp
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A0A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "ACHTUNG: Recovery-Blob fehlt (v<=36 Setup). Chats und Kontakte werden geloescht. App-ID und Nostr-Keys bleiben.",
                            color = Color(0xFFFFCC80), fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = newPin,
                onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) { newPin = it; errorText = null } },
                label = { Text("Neue PIN (4-8 Ziffern)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00B8A0),
                    unfocusedBorderColor = Color.DarkGray,
                    focusedLabelColor = Color(0xFF00B8A0),
                    unfocusedLabelColor = Color.Gray
                )
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) { confirmPin = it; errorText = null } },
                label = { Text("Neue PIN wiederholen") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00B8A0),
                    unfocusedBorderColor = Color.DarkGray,
                    focusedLabelColor = Color(0xFF00B8A0),
                    unfocusedLabelColor = Color.Gray
                )
            )

            errorText?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    when {
                        newPin.length < 4 -> errorText = "PIN muss mindestens 4 Ziffern haben"
                        newPin != confirmPin -> errorText = "PINs stimmen nicht ueberein"
                        else -> {
                            isLoading = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        DiagnosticLogger.log("APP", "NewPinSetupScreen: starting rekey/reset (hasBlob=$hasBlob)")

                                        if (hasBlob && !ksPriv.isNullOrBlank()) {
                                            // v37 Happy Path: PRAGMA rekey auf bestehender DB
                                            val ok = AppDatabase.rekeyUsingRecoveryBlob(context, ksPriv, newPin)
                                            if (!ok) {
                                                throw Exception("Rekey fehlgeschlagen - Recovery-Blob konnte nicht entschluesselt werden")
                                            }
                                            DiagnosticLogger.log("APP", "NewPinSetupScreen: rekey OK - alle Daten erhalten")
                                        } else {
                                            // v36-Fallback: DB loeschen und neu anlegen
                                            AppDatabase.forceClose()
                                            val dbFile = context.getDatabasePath("onyx_vault_forensic.db")
                                            val dbShm = java.io.File(dbFile.path + "-shm")
                                            val dbWal = java.io.File(dbFile.path + "-wal")
                                            if (dbFile.exists()) dbFile.delete()
                                            if (dbShm.exists()) dbShm.delete()
                                            if (dbWal.exists()) dbWal.delete()
                                            app.securePrefs.saveDatabasePassword("")
                                            AppDatabase.getInstance(context, newPin).close()
                                            // Jetzt sofort auch den Recovery-Blob fuer die Zukunft erstellen
                                            AppDatabase.createRecoveryBlob(context, newPin)
                                            DiagnosticLogger.log("APP", "NewPinSetupScreen: v36-fallback reset done, blob created for future")
                                        }

                                        // Flag loeschen + app-lock
                                        app.securePrefs.setRequireNewPin(false)
                                        app.securePrefs.setAppLocked(true)
                                        DiagnosticLogger.log("APP", "NewPinSetupScreen: requireNewPin cleared, app re-locked for restart")
                                    }

                                    DiagnosticLogger.log("APP", "NewPinSetupScreen: shutting down for restart")
                                    app.shutdownApp()
                                    val activity = context as? android.app.Activity
                                    activity?.finishAffinity()
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        kotlin.system.exitProcess(0)
                                    }, 800)
                                } catch (e: Exception) {
                                    isLoading = false
                                    errorText = "Fehler: ${e.message}"
                                    DiagnosticLogger.log("APP", "NewPinSetupScreen ERROR: ${e.message}")
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading && newPin.length >= 4 && confirmPin.length >= 4,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B8A0))
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                else Text("NEUE PIN SETZEN & NEU STARTEN", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}
