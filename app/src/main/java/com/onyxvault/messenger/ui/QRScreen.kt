package com.onyxvault.messenger.ui

import android.content.ContentValues
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.qr.QRCodeManager
import com.onyxvault.messenger.R
import com.onyxvault.messenger.ui.theme.OnyxTurquoise
import com.onyxvault.messenger.utils.SecurePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val securePrefs = remember { SecurePrefs(context) }
    val username = securePrefs.getUsername() ?: "Unknown"
    val app = context.applicationContext as OnyxVaultApp
    
    // ECHTER ONYX3-Format Reg-QR (nicht nur Username!) - fuer Website-Erkennung noetig
    val appRegBitmap = remember {
        try {
            val user = app.loadCurrentUser()
            if (user != null) QRCodeManager.generateRegistrationQR(context, user, app.adaptiveKeystore)
            else generateQrCode(username)
        } catch (e: Exception) {
            generateQrCode(username)
        }
    }
    
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MEIN IDENTITÄTS-HUB", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = OnyxTurquoise)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                QRSection(
                    title = "DEIN APP-REGISTRIERUNGS-CODE",
                    description = "Dies ist dein APP-Registrierungs-QR-Code. Alle QR-Codes sind verschlüsselt und nicht für andere lesbar. Bei Verlust einfach in der App einen neuen generieren.\n\n⚠️ WICHTIG: Eine Recovery bei 5× falschem PIN oder der Killswitch ist nur möglich wenn du den APP-REG-QR kostenlos auf einer unserer Seiten (.onion oder GitHub Pages) registrierst. Du bekommst dann einen MASTER-QR zurück der für Recovery UND Killswitch nutzbar ist.\n\nBeide QR-Codes (App-Reg + Master) sicher verwahren — alternativ auf eigene Cloud oder USB-Stick speichern. Niemals offen im Handy liegen lassen. Jeder der deinen QR-Code besitzt kann diese Aktionen auf dein Profil durchführen.\n\n👥 KENNST DU SCHON UNSERE SAVECARD? Lass deinen Partner deinen Account löschen falls du nicht mehr kannst! Weitere Infos in der App und auf unserer Website.",
                    bitmap = appRegBitmap,
                    codeString = "ONYX3 Registrierungscode fuer: $username",
                    context = context
                )
            }
        }
    }
}

@Composable
fun QRSection(title: String, description: String, bitmap: Bitmap?, codeString: String, context: Context) {
    Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    Spacer(Modifier.height(8.dp))
    Text(description, color = Color.Gray, fontSize = 12.sp)
    
    Spacer(Modifier.height(24.dp))
    
    Box(
        modifier = Modifier
            .size(280.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("KEIN QR VERFÜGBAR", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
    
    Spacer(Modifier.height(24.dp))
    
    Text("CODE-STRING:", color = Color.Gray, fontSize = 10.sp)
    Text(codeString, color = OnyxTurquoise, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    
    Spacer(Modifier.height(32.dp))
    
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Onyx Code", codeString)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "In Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("KOPIEREN")
        }
        
        Button(
            onClick = {
                bitmap?.let { saveImageToGallery(context, it, "OnyxVault_QR") }
            },
            colors = ButtonDefaults.buttonColors(containerColor = OnyxTurquoise)
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("SPEICHERN")
        }
    }
}

private fun generateQrCode(text: String): Bitmap {
    val size = 512
    val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

private fun saveImageToGallery(context: Context, bitmap: Bitmap, name: String) {
    val filename = "${name}_${System.currentTimeMillis()}.png"
    var fos: java.io.OutputStream? = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
        val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        fos = imageUri?.let { context.contentResolver.openOutputStream(it) }
    } else {
        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val image = java.io.File(imagesDir, filename)
        fos = java.io.FileOutputStream(image)
    }
    fos?.use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        Toast.makeText(context, "QR-Code in Galerie gespeichert", Toast.LENGTH_LONG).show()
    }
}
