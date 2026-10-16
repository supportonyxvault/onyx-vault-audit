package com.onyxvault.messenger.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.onyxvault.messenger.MainActivity
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.crypto.MessageEncryption
import org.json.JSONArray
import org.json.JSONObject

/**
 * ONYX VAULT - Website Screen (Zero-Knowledge Edition)
 * Implementiert die Hermes-Brücke mit dem ONYX3-Protokoll für automatischen QR-Sync.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebsiteScreen(startPage: String = "index.html") {
    val context = LocalContext.current
    val app = context.applicationContext as OnyxVaultApp
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false 
                    allowContentAccess = false
                }

                val webInterface = OnyxWebInterface(ctx, app)
                addJavascriptInterface(webInterface, "OnyxVault")

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        params: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = callback
                        MainActivity.startMediaGracePeriod()
                        fileChooserLauncher.launch("image/*") // Spezifisch für QR-Uploads
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (url != null && !url.startsWith("file:///android_asset/")) {
                            removeJavascriptInterface("OnyxVault")
                        } else {
                            addJavascriptInterface(webInterface, "OnyxVault")
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (url == null) return false
                        if (url.startsWith("file:///android_asset/")) return false
                        
                        return try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            true 
                        } catch (e: Exception) { true }
                    }
                }

                loadUrl("file:///android_asset/website/$startPage")
            }
        }
    )
}

/**
 * OnyxWebInterface – Die Hermes-Brücke (Sovereign Sync Logic)
 */
class OnyxWebInterface(private val context: Context, private val app: OnyxVaultApp) {
    
    @JavascriptInterface
    fun getAppDetails(): String {
        return JSONObject().apply {
            put("appId", app.securePrefs.getAppId())
            put("torReady", app.torManager.torReady.value)
            put("protocol", "ONYX3-Nostr-Sovereign")
        }.toString()
    }

    /**
     * Hermes liefert den Registrierungs-Token im ONYX3-Format.
     * Ermöglicht der Website die App-ID automatisch aus dem QR zu lesen.
     */
    @JavascriptInterface
    fun getRegistrationToken(): String {
        return generateOnyx3TokenInternal("")
    }

    /**
     * Erlaubt der Website den Token passwortgeschützt für die Cloud anzufordern (Format: ONYX3P).
     */
    @JavascriptInterface
    fun getEncryptedToken(passphrase: String): String {
        return generateOnyx3TokenInternal(passphrase)
    }

    private fun generateOnyx3TokenInternal(passphrase: String): String {
        val prefs = app.securePrefs
        val user = app.loadCurrentUser()
        val appId = user.appId
        
        var ksPubKey = prefs.getKillSwitchPubKey()
        var ksPrivKey = prefs.getKillSwitchPrivKey()
        
        if (ksPubKey == null || ksPrivKey == null) {
            val keyPair = MessageEncryption.generateNostrKeyPair()
            ksPubKey = keyPair.publicKeyHex
            ksPrivKey = keyPair.privateKeyHex
            prefs.saveKillSwitchKeys(ksPubKey, ksPrivKey)
        }

        val rawData = JSONObject().apply {
            put("type", "registration")
            put("app_id", appId)
            put("pub", user.masterPublicKey)
            put("ks_pub", ksPubKey)
            put("ks_priv", ksPrivKey)
            put("ts", System.currentTimeMillis())
        }.toString()

        return if (passphrase.isBlank()) {
            // Standard ONYX3 Format: App-ID als Klartext-Präfix zur Auto-Entschlüsselung
            val encrypted = EncryptionManager.encryptWithAppId(rawData, appId)
            "ONYX3:$appId:$encrypted"
        } else {
            // Passwortgeschütztes Format für Cloud-Sicherung
            val encrypted = EncryptionManager.encryptWithPassphrase(rawData, passphrase)
            "ONYX3P:$appId:$encrypted"
        }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Speichert einen Master-QR als PNG in die Galerie.
     * dataUrl: "data:image/png;base64,..."
     * Vom WebView (register.html) aus aufgerufen.
     */
    @JavascriptInterface
    fun saveMasterQR(dataUrl: String, filename: String): Boolean {
        return try {
            val commaIdx = dataUrl.indexOf(',')
            if (commaIdx < 0) return false
            val base64 = dataUrl.substring(commaIdx + 1)
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val safeName = if (filename.endsWith(".png")) filename else "ONYX_VAULT_MASTER_${System.currentTimeMillis()}.png"
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, safeName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/ONYX_VAULT")
                }
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Master-QR in Galerie gespeichert", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("OnyxWeb", "saveMasterQR fail", e)
            false
        }
    }
}
