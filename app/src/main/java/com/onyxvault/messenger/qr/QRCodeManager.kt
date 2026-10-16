package com.onyxvault.messenger.qr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.onyxvault.messenger.crypto.AdaptiveKeystore
import com.onyxvault.messenger.crypto.EncryptionManager
import com.onyxvault.messenger.crypto.MessageEncryption
import com.onyxvault.messenger.model.User
import com.onyxvault.messenger.utils.SecurePrefs

/**
 * Modell für den dezentralen Kontakt-Austausch.
 */
data class ContactQRData(
    val username: String,
    val onion: String,
    val appId: String,
    val publicKey: String,
    val validUntil: Long = 0L
)

/**
 * QRCodeManager – ONYX VAULT (Forensic Grade)
 * Implementiert das ONYX3-Format für automatischen Website-Sync.
 */
object QRCodeManager {

    private const val TAG = "QRCodeManager"
    private const val QR_VERSION = "ONYX3"
    private val gson = Gson()

    fun scanQRCode(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap).text
        } catch (e: Exception) {
            Log.e(TAG, "QR Scan Error: ${e.message}")
            null
        }
    }

    /**
     * Erzeugt den APP REGISTRIERUNGSCODE im ONYX3-Format.
     */
    /**
     * V1: Erzeugt den MASTER-QR direkt in der App - OHNE Website-Umweg.
     * Format kompatibel mit hermes.js: ONYX_MASTER:<appId>:<base64(iv[12]+ciphertext_mit_tag)>
     * Verschluesselung: AES-GCM mit SHA-256(appId) als Key.
     *
     * Payload JSON: {type:"MASTER", app_id, pub, ks_pub, ks_priv, ts}
     * Dies ist der QR fuer Recovery + Killswitch via Website.
     */
    fun generateMasterQR(context: Context, user: User, keystore: AdaptiveKeystore): Bitmap {
        val prefs = SecurePrefs(context)
        val ksPubKey = prefs.getKillSwitchPubKey() ?: run {
            val kp = MessageEncryption.generateNostrKeyPair()
            prefs.saveKillSwitchKeys(kp.publicKeyHex, kp.privateKeyHex)
            kp.publicKeyHex
        }
        val ksPrivKey = prefs.getKillSwitchPrivKey() ?: throw IllegalStateException("ks_priv missing nach saveKillSwitchKeys")

        val payloadMap = mapOf(
            "type"    to "MASTER",
            "app_id"  to user.appId,
            "pub"     to user.masterPublicKey,
            "ks_pub"  to ksPubKey,
            "ks_priv" to ksPrivKey,
            "ts"      to System.currentTimeMillis().toString()
        )
        val json = gson.toJson(payloadMap)
        val encrypted = EncryptionManager.encryptWithAppId(json, user.appId)
        val qrText = "ONYX_MASTER:${user.appId}:$encrypted"

        return generateQRBitmap(qrText, 900)
    }

    /**
     * V1.1: Gibt den Master-QR String zurueck ohne Bitmap-Generation.
     * Fuer SaveCard-Generator der den String neu verschluesselt.
     */
    fun generateMasterQRString(context: Context, user: User): String {
        val prefs = SecurePrefs(context)
        val ksPubKey = prefs.getKillSwitchPubKey() ?: run {
            val kp = MessageEncryption.generateNostrKeyPair()
            prefs.saveKillSwitchKeys(kp.publicKeyHex, kp.privateKeyHex)
            kp.publicKeyHex
        }
        val ksPrivKey = prefs.getKillSwitchPrivKey() ?: throw IllegalStateException("ks_priv missing")
        val payloadMap = mapOf(
            "type"    to "MASTER",
            "app_id"  to user.appId,
            "pub"     to user.masterPublicKey,
            "ks_pub"  to ksPubKey,
            "ks_priv" to ksPrivKey,
            "ts"      to System.currentTimeMillis().toString()
        )
        val json = gson.toJson(payloadMap)
        val encrypted = EncryptionManager.encryptWithAppId(json, user.appId)
        return "ONYX_MASTER:${user.appId}:$encrypted"
    }

    fun generateRegistrationQR(context: Context, user: User, keystore: AdaptiveKeystore): Bitmap {
        val prefs = SecurePrefs(context)
        val ksPubKey = prefs.getKillSwitchPubKey() ?: MessageEncryption.generateNostrKeyPair().publicKeyHex.also { prefs.saveKillSwitchPubKey(it) }
        val ksPrivKey = prefs.getKillSwitchPrivKey() ?: MessageEncryption.generateNostrKeyPair().privateKeyHex.also { prefs.saveKillSwitchKeys(ksPubKey, it) }

        val payload = mapOf(
            "type"    to "registration",
            "app_id"  to user.appId,
            "pub"     to user.masterPublicKey,
            "ks_pub"  to ksPubKey,
            "ks_priv" to ksPrivKey,
            "ts"      to System.currentTimeMillis().toString()
        )
        return generateOnyx3QR(context, payload, keystore, encrypt = true)
    }

    /**
     * Erzeugt den Kontakt-QR für P2P-Verbindungen.
     */
    fun generateContactQR(context: Context, username: String, onion: String, appId: String, publicKey: String, keystore: AdaptiveKeystore): Bitmap {
        val payload = mapOf(
            "type"     to "contact",
            "username" to username,
            "onion"    to onion,
            "app_id"   to appId,
            "pubkey"   to publicKey
        )
        return generateOnyx3QR(context, payload, keystore, encrypt = true)
    }

    private fun generateOnyx3QR(context: Context, payload: Map<String, String>, keystore: AdaptiveKeystore, encrypt: Boolean): Bitmap {
        val jsonPayload = gson.toJson(payload)
        val signature = EncryptionManager.signData(jsonPayload.toByteArray(), keystore)
        val envelope = mapOf("p" to jsonPayload, "s" to signature)
        val jsonEnvelope = gson.toJson(envelope)
        
        val appId = SecurePrefs(context).getAppId() ?: "ONYX_DEFAULT"
        
        val finalContent = if (encrypt) {
            val encrypted = EncryptionManager.encryptWithAppId(jsonEnvelope, appId)
            "$QR_VERSION:$appId:$encrypted"
        } else {
            jsonEnvelope
        }
        return generateQRBitmap(finalContent, 600)
    }

    fun decryptContactQR(context: Context, qrContent: String, keystore: AdaptiveKeystore): ContactQRData? {
        return try {
            val decrypted = if (qrContent.startsWith("$QR_VERSION:")) {
                val parts = qrContent.split(":")
                if (parts.size < 3) return null
                val appId = parts[1]
                val encrypted = parts[2]
                EncryptionManager.decryptWithPassphrase(encrypted, appId)
            } else {
                qrContent
            }

            val envelope = gson.fromJson(decrypted, Map::class.java) ?: return null
            val jsonPayload = envelope["p"] as String
            
            val map = gson.fromJson(jsonPayload, Map::class.java)
            ContactQRData(
                username = map["username"] as String,
                onion = map["onion"] as String,
                appId = map["app_id"] as String,
                publicKey = map["pubkey"] as String
            )
        } catch (e: Exception) { null }
    }

    private fun generateQRBitmap(content: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return BarcodeEncoder().createBitmap(matrix)
    }
}
