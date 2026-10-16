package com.onyxvault.messenger.ui

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.R
import com.onyxvault.messenger.crypto.MessageEncryption
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import com.onyxvault.messenger.utils.SecurePrefs
import com.onyxvault.messenger.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SetupScreen – ONYX VAULT (Forensic sovereign Edition)
 * Erzeugt die dezentrale Nostr-Identität (Secp256k1) ohne PIN-Hashes zu hinterlassen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val app     = context.applicationContext as OnyxVaultApp
    val scope   = rememberCoroutineScope()

    var username     by remember { mutableStateOf("") }
    var pin          by remember { mutableStateOf("") }
    var confirmPin   by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading    by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter            = painterResource(id = R.drawable.app_icon_ov),
                    contentDescription = "ONYX VAULT",
                    modifier           = Modifier.size(100.dp),
                    contentScale       = ContentScale.Fit
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = OnyxTurquoise, fontWeight = FontWeight.Black)) { append("ONYX ") }
                    withStyle(style = SpanStyle(color = Color.White, fontWeight = FontWeight.Black)) { append("VAULT") }
                },
                style      = MaterialTheme.typography.headlineMedium,
                fontSize   = 32.sp 
            )
            Text(
                "Dezentrale Souveränität. Hardware-verriegelt.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                fontSize = 14.sp
            )

            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Benutzername", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Person, null, tint = OnyxTurquoise) },
                shape = RoundedCornerShape(12.dp), singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OnyxTurquoise,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedContainerColor = Color(0xFF111111),
                    unfocusedContainerColor = Color.Black,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = pin, onValueChange = { if (it.length <= 6) pin = it },
                label = { Text("Master-PIN (4–6 Ziffern)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, null, tint = OnyxTurquoise) },
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OnyxTurquoise,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedContainerColor = Color(0xFF111111),
                    unfocusedContainerColor = Color.Black,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = confirmPin, onValueChange = { if (it.length <= 6) confirmPin = it },
                label = { Text("PIN bestätigen") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, null, tint = OnyxTurquoise) },
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OnyxTurquoise,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedContainerColor = Color(0xFF111111),
                    unfocusedContainerColor = Color.Black,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            errorMessage?.let {
                Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    when {
                        username.length < 3 -> errorMessage = "Name zu kurz"
                        pin.length !in 4..6 -> errorMessage = "PIN ungültig"
                        pin != confirmPin -> errorMessage = "PINs ungleich"
                        else -> {
                            errorMessage = null
                            isLoading    = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val sp = SecurePrefs(context)
                                        sp.saveUsername(username)
                                        sp.saveAppId(User.generateAppId())
                                        
                                        // 1. Souveräne Nostr-Identität (Secp256k1) erzeugen
                                        val kp = MessageEncryption.generateNostrKeyPair()
                                        sp.saveMasterKeys(
                                            kp.publicKeyHex,
                                            kp.privateKeyHex
                                        )
                                        
                                        // 2. KRITISCH: Tresor mathematisch mit der PIN verschränken (Zero-Hash)
                                        withContext(Dispatchers.Main) {
                                            app.unlockAndInitialize(pin)
                                            onSetupComplete()
                                        }
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Krypto-Fehler: ${e.message}"
                                    isLoading = false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise),
                enabled  = !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                else Text("ONYX ACCOUNT ERSTELLEN", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 16.sp)
            }
        }
    }
}
