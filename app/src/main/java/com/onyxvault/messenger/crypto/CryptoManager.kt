package com.onyxvault.messenger.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoManager – ONYX VAULT (Hacker-Safe Version)
 * Keine Hardcoded Keys mehr. Alles wird dynamisch abgeleitet.
 */
object CryptoManager {

    private const val AES_MODE = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATION_COUNT = 50_000 // Erhöht für bessere Anti-Bruteforce
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16

    // -----------------------------------------------------------------------
    // Datenbank-Passwort Ableitung (Der Kern der Anti-Forensik)
    // -----------------------------------------------------------------------
    
    /**
     * Erzeugt ein unknackbares DB-Passwort basierend auf Hardware + AppID + PIN.
     */
    fun deriveDatabasePassword(hardwareKey: ByteArray, appId: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(hardwareKey)
        md.update(appId.toByteArray(Charsets.UTF_8))
        md.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    // -----------------------------------------------------------------------
    // Verschlüsselung mit dynamischen Keys
    // -----------------------------------------------------------------------

    fun encrypt(data: String, key: ByteArray): String {
        return try {
            val secretKey = SecretKeySpec(key.copyOf(32), "AES")
            val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun decrypt(encryptedBase64: String, key: ByteArray): String {
        return try {
            val data = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = data.sliceArray(0 until IV_LENGTH)
            val encrypted = data.sliceArray(IV_LENGTH until data.size)
            val secretKey = SecretKeySpec(key.copyOf(32), "AES")
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    fun hashPin(pin: String, salt: String): String {
        val saltBytes = Base64.decode(salt, Base64.NO_WRAP)
        val keySpec: KeySpec = PBEKeySpec(pin.toCharArray(), saltBytes, ITERATION_COUNT, KEY_LENGTH)
        val hash = SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(keySpec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun generateSalt(): String {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(saltBytes, Base64.NO_WRAP)
    }

    fun deriveSharedSecret(token1: String, token2: String): ByteArray {
        val combined = token1.toByteArray() + token2.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(combined)
    }
}
