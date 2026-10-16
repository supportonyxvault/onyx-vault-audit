package com.onyxvault.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.manager.ContactManager
import com.onyxvault.messenger.model.User
import com.onyxvault.messenger.qr.ContactQRData
import com.onyxvault.messenger.qr.QRCodeManager
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import kotlinx.coroutines.launch

/**
 * ContactQRScanScreen – ONYX VAULT (Forensic Edition)
 * Unterstützt das ONYX3-Protokoll für automatische Entschlüsselung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactQRScanScreen(
    contactManager: ContactManager,
    currentUser:    User,
    onBackPressed:  () -> Unit,
    onSuccess:      () -> Unit
) {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val keystore       = remember { AdaptiveKeystore(context) }

    var scannedData       by remember { mutableStateOf<ContactQRData?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var errorMessage      by remember { mutableStateOf<String?>(null) }
    var isLoading         by remember { mutableStateOf(false) }
    var showCamera        by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                val text = if (bitmap != null) QRCodeManager.scanQRCode(bitmap) else null
                val data = if (text != null) QRCodeManager.decryptContactQR(context, text, keystore) else null
                
                if (data != null) {
                    scannedData = data
                    showConfirmDialog = true
                } else {
                    errorMessage = "Ungültiges ONYX3-Format oder Key-Mismatch."
                }
            } catch (e: Exception) { errorMessage = "Fehler beim Lesen der Galerie." }
        }
    }

    if (showCamera) {
        Dialog(onDismissRequest = { showCamera = false }) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        val preview = PreviewView(ctx)
                        val future  = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                            val sel  = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, sel, prev)
                        }, ContextCompat.getMainExecutor(ctx))
                        preview
                    },
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(onClick = { showCamera = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
            }
        }
    }

    if (showConfirmDialog && scannedData != null) {
        val data = scannedData!!
        Dialog(onDismissRequest = { if (!isLoading) showConfirmDialog = false }) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Security, null, tint = OnyxTurquoise, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("IDENTITÄT GEPRÜFT", fontWeight = FontWeight.Black, color = Color.Black)
                    Text("@${data.username}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OnyxTurquoise)
                    Spacer(Modifier.height(8.dp))
                    Text("Dezentrale Onion-Route verifiziert.", fontSize = 12.sp, color = Color.Gray)
                    
                    Spacer(Modifier.height(24.dp))
                    
                    Button(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                contactManager.addContact(data.username, data.onion, data.publicKey)
                                isLoading = false
                                showConfirmDialog = false
                                Toast.makeText(context, "Tresor-Verbindung etabliert.", Toast.LENGTH_SHORT).show()
                                onSuccess()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading
                    ) { 
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
                        else Text("KONTAKT HINZUFÜGEN", fontWeight = FontWeight.Bold) 
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HARDWARE HANDSHAKE", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBackPressed) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = OnyxTurquoise) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Icon(Icons.Default.QrCodeScanner, null, tint = OnyxTurquoise, modifier = Modifier.size(80.dp))
            Text("Handshake via ONYX3", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
            Text("Die App-ID wird automatisch aus dem QR extrahiert.", fontSize = 12.sp, color = Color.Gray)
            
            Spacer(Modifier.height(48.dp))
            
            Button(
                onClick = { if (hasCameraPermission) showCamera = true else permissionLauncher.launch(Manifest.permission.CAMERA) }, 
                modifier = Modifier.fillMaxWidth().height(58.dp), 
                colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise, contentColor = Color.Black), 
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.CameraAlt, null)
                Spacer(Modifier.width(12.dp))
                Text("KAMERA STARTEN", fontWeight = FontWeight.Bold)
            }
            
            Spacer(Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") }, 
                modifier = Modifier.fillMaxWidth().height(58.dp), 
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, OnyxTurquoise.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.PhotoLibrary, null, tint = OnyxTurquoise)
                Spacer(Modifier.width(12.dp))
                Text("AUS GALERIE LADEN", fontWeight = FontWeight.Bold, color = OnyxTurquoise)
            }
            
            errorMessage?.let { 
                Spacer(Modifier.height(24.dp))
                Text(it, color = Color.Red, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center) 
            }
        }
    }
}
