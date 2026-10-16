package com.onyxvault.messenger.crypto

import android.content.Context
import android.util.Base64
import com.onyxvault.messenger.utils.SecurePrefs
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.util.encoders.Hex
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import java.math.BigInteger
import java.security.Signature

/**
 * EncryptionManager – ONYX VAULT (Forensic Sovereign Grade)
 * Implementiert Hardware-Verschlüsselung, Argon2id-Verschränkung und Nostr-Signaturen.
 */
object EncryptionManager {

    private val random = SecureRandom()

    fun encryptWithHardware(context: Context, data: String): String {
        return try {
            val keystore = AdaptiveKeystore(context)
            val masterKey = keystore.getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun decryptWithHardware(context: Context, encryptedBase64: String): String {
        return try {
            val keystore = AdaptiveKeystore(context)
            val masterKey = keystore.getOrCreateMasterKey()
            val data = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (data.size < 13) return ""
            val iv = data.sliceArray(0 until 12)
            val encrypted = data.sliceArray(12 until data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    fun encryptWithAppId(data: String, appId: String): String {
        return encryptWithPassphrase(data, appId)
    }

    fun encryptWithPassphrase(data: String, passphrase: String): String {
        if (passphrase.isBlank()) return data
        return try {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val key = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun decryptWithPassphrase(encryptedB64: String, passphrase: String): String {
        if (passphrase.isBlank()) return ""
        return try {
            val data = Base64.decode(encryptedB64, Base64.NO_WRAP)
            if (data.size < 13) return ""
            val iv = data.sliceArray(0 until 12)
            val encrypted = data.sliceArray(12 until data.size)
            val key = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    fun stretchKeyWithPin(hardwareSeed: String, pin: String): String {
        return try {
            val hash = Argon2Helper.deriveKey(
                passphrase = pin.toByteArray(Charsets.UTF_8),
                salt = MessageDigest.getInstance("SHA-256").digest(hardwareSeed.toByteArray())
            )
            Hex.toHexString(hash)
        } catch (e: Exception) { 
            android.util.Log.e("EncryptionManager", "Argon2 stretching failed", e)
            hardwareSeed 
        }
    }

    fun signNostrEvent(eventIdHex: String, privateKeyHex: String): String {
        if (privateKeyHex.isBlank() || eventIdHex.isBlank()) return ""
        return try {
            // BIP-340 Schnorr (Nostr NIP-01 requirement).
            // Previously used ECDSA which caused ALL relays to reject with
            // "Event id does not match content hash".
            SchnorrBip340.sign(eventIdHex, privateKeyHex)
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatTo32Bytes(bi: BigInteger): ByteArray {
        val bytes = bi.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.sliceArray(bytes.size - 32 until bytes.size)
            else -> {
                val res = ByteArray(32)
                System.arraycopy(bytes, 0, res, 32 - bytes.size, bytes.size)
                res
            }
        }
    }

    fun signData(data: ByteArray, keystore: AdaptiveKeystore): String {
        val keyPair = keystore.getSigningKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(data)
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun verifySignature(data: ByteArray, signatureBase64: String, keystore: AdaptiveKeystore): Boolean {
        return try {
            val keyPair = keystore.getSigningKeyPair()
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(keyPair.public)
            sig.update(data)
            sig.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        } catch (e: Exception) { false }
    }

    fun encryptQRData(context: Context, data: String): String {
        if (data.isBlank()) return ""
        return try {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val seed = SecurePrefs(context).getAppId() ?: "ONYX_VAULT_INTERNAL"
            val key = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun decryptQRData(context: Context, encB64: String): String {
        if (encB64.isBlank()) return ""
        return try {
            val data = Base64.decode(encB64, Base64.NO_WRAP)
            if (data.size < 13) return ""
            val iv = data.sliceArray(0 until 12)
            val encrypted = data.sliceArray(12 until data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val seed = SecurePrefs(context).getAppId() ?: "ONYX_VAULT_INTERNAL"
            val key = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
}
