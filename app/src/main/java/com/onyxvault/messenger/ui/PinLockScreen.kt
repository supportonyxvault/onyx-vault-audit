package com.onyxvault.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.R
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import com.onyxvault.messenger.utils.DiagnosticLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PinLockScreen - ONYX VAULT (Zero-Hash Edition)
 *
 * KRITISCHE FIXES in dieser Version:
 *  1. KEIN LaunchedEffect(Unit) mit collectLatest mehr -> StateFlow emittiert aktuellen Wert
 *     SOFORT, dh. ein alter 'unlocked=true' Zustand hat die PIN-Pruefung uebersprungen.
 *     Jetzt wird NUR nach einem echten Submit-Versuch gepollt.
 *  2. Falsch-PIN registriert sich jetzt am RecoveryManager (vorher: Kommentar statt Code).
 *  3. onTooManyAttempts() wird wirklich aufgerufen wenn das Limit erreicht ist.
 *  4. Logging ueber DiagnosticLogger damit wir in der naechsten Session sehen was passiert.
 */
@Composable
fun PinLockScreen(
    onUnlocked: () -> Unit,
    onTooManyAttempts: () -> Unit
) {
    val context = LocalContext.current
    val app     = context.applicationContext as OnyxVaultApp
    val scope   = rememberCoroutineScope()

    var pin       by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon_ov),
                contentDescription = "ONYX VAULT",
                modifier = Modifier.size(100.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(Modifier.height(24.dp))
            Text("MASTER-PIN", fontWeight = FontWeight.Black, color = Color.White, fontSize = 20.sp)
            Text("Hardware-Verschluesselung aktiv", color = Color.Gray, fontSize = 12.sp)

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6) pin = it; errorText = null },
                label = { Text("Master-PIN") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                isError = errorText != null,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = OnyxTurquoise,
                    unfocusedBorderColor = Color.DarkGray
                )
            )

            errorText?.let {
                Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val attemptedPin = pin
                    isLoading = true
                    errorText = null
                    DiagnosticLogger.log("PIN", "PinLockScreen: Unlock attempt (pin len=${attemptedPin.length})")

                    // Mathematische Key-Derivation der DB im RAM
                    app.unlockAndInitialize(attemptedPin)

                    // Bis zu 3 Sekunden auf isDbUnlocked warten, dann entscheiden
                    scope.launch {
                        var waited = 0L
                        var unlocked = false
                        while (waited < 3000L) {
                            if (app.isDbUnlocked.value) { unlocked = true; break }
                            delay(100)
                            waited += 100
                        }
                        isLoading = false
                        if (unlocked) {
                            DiagnosticLogger.log("PIN", "Unlock SUCCESS - PIN korrekt")
                            onUnlocked()
                        } else {
                            errorText = "Zugriff verweigert: PIN mathematisch inkorrekt."
                            pin = ""
                            // Fehlversuch REGISTRIEREN - sonst triggert Recovery nie
                            try {
                                app.recoveryManager.registerFailedAttempt()
                                val locked = app.recoveryManager.isLocked.value
                                DiagnosticLogger.log("PIN", "Unlock FAILED - registered. isLocked=$locked")
                                if (locked) {
                                    DiagnosticLogger.log("PIN", "TOO MANY ATTEMPTS - triggering recovery screen")
                                    onTooManyAttempts()
                                }
                            } catch (e: Exception) {
                                DiagnosticLogger.log("PIN", "registerFailedAttempt ERROR: ${e.message}")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = pin.length >= 4 && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise)
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                else Text("ENTSPERREN", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}
