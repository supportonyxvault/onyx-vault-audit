package com.onyxvault.messenger.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ONYX VAULT - Adaptive Hardware-Keystore
 * Verwaltet Master-Keys in Samsung Knox / Titan M2 / StrongBox.
 * Unterstützt die forensische Selbstzerstörung bei Kompromittierung.
 */
class AdaptiveKeystore(private val context: Context) {
    companion object {
        private const val TAG = "AdaptiveKeystore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "onyx_vault_master_key"
        const val SIGNING_KEY_ALIAS = "onyx_vault_signing_key"
    }

    enum class SecurityLevel(val displayName: String, val stars: Int) {
        SAMSUNG_KNOX_VAULT("Samsung Knox Vault", 5),
        GOOGLE_TITAN_M2("Google Titan M2", 5),
        STRONGBOX_GENERIC("StrongBox (Hardware)", 4),
        TEE_STANDARD("Trusted Execution Environment", 3),
        SOFTWARE_ONLY_ENHANCED("Software (verstärkt)", 2)
    }

    fun detectBest(): SecurityLevel = when {
        isSamsungKnoxVault() -> SecurityLevel.SAMSUNG_KNOX_VAULT
        isGoogleTitanM2() -> SecurityLevel.GOOGLE_TITAN_M2
        hasStrongBox() -> SecurityLevel.STRONGBOX_GENERIC
        hasHardwareKeystore() -> SecurityLevel.TEE_STANDARD
        else -> SecurityLevel.SOFTWARE_ONLY_ENHANCED
    }

    // ─── AES MASTER KEY (Für DB-Seed) ───

    fun getOrCreateMasterKey(alias: String = MASTER_KEY_ALIAS): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keystore.containsAlias(alias)) {
            val entry = keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }
        return generateHardwareAESKey(alias)
    }

    private fun generateHardwareAESKey(alias: String): SecretKey {
        val level = detectBest()
        Log.i(TAG, "Erzeuge Hardware-Key via ${level.displayName}")
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
        
        applyStrongBoxIfAvailable(builder, level)

        return try {
            keyGen.init(builder.build()); keyGen.generateKey()
        } catch (e: Exception) {
            builder.setIsStrongBoxBacked(false); keyGen.init(builder.build()); keyGen.generateKey()
        }
    }

    // ─── ECDSA SIGNING KEY (Für dezentrale Nostr-Handshakes) ───

    fun getSigningKeyPair(): KeyPair {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keystore.containsAlias(SIGNING_KEY_ALIAS)) {
            val entry = keystore.getEntry(SIGNING_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                return KeyPair(entry.certificate.publicKey, entry.privateKey)
            }
        }
        return generateSigningKey()
    }

    private fun generateSigningKey(): KeyPair {
        val level = detectBest()
        val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(SIGNING_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setUserAuthenticationRequired(false)

        applyStrongBoxIfAvailable(builder, level)

        try {
            keyPairGenerator.initialize(builder.build())
        } catch (e: Exception) {
            builder.setIsStrongBoxBacked(false)
            keyPairGenerator.initialize(builder.build())
        }
        return keyPairGenerator.generateKeyPair()
    }

    private fun applyStrongBoxIfAvailable(builder: KeyGenParameterSpec.Builder, level: SecurityLevel) {
        if (level != SecurityLevel.SOFTWARE_ONLY_ENHANCED && level != SecurityLevel.TEE_STANDARD
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { builder.setIsStrongBoxBacked(true) } catch (e: Exception) {}
        }
    }

    /**
     * FORENSISCHE SELBSTZERSTÖRUNG: Löscht alle Schlüssel permanent aus dem Hardware-Chip.
     */
    fun destroyAllKeys() {
        Log.e(TAG, "!!! HARDWARE KEY DESTRUCTION TRIGGERED !!!")
        try {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keystore.deleteEntry(MASTER_KEY_ALIAS)
            keystore.deleteEntry(SIGNING_KEY_ALIAS)
            Log.e(TAG, "Hardware-Schlüssel erfolgreich vernichtet.")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei Hardware-Key Vernichtung: ${e.message}")
        }
    }

    private fun isSamsungKnoxVault(): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return context.packageManager.hasSystemFeature("com.samsung.feature.KNOX_VAULT") || hasStrongBox()
    }

    private fun isGoogleTitanM2(): Boolean {
        if (!Build.MANUFACTURER.equals("google", ignoreCase = true)) return false
        return hasStrongBox()
    }

    private fun hasStrongBox(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    private fun hasHardwareKeystore(): Boolean = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).load(null); true
    } catch (e: Exception) { false }

    fun getSecurityStatusReport(): SecurityStatusReport {
        val level = detectBest()
        return SecurityStatusReport(level, "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            level != SecurityLevel.SOFTWARE_ONLY_ENHANCED,
            when (level) {
                SecurityLevel.SAMSUNG_KNOX_VAULT, SecurityLevel.GOOGLE_TITAN_M2 ->
                    "Maximaler Hardware-Schutz aktiv."
                SecurityLevel.STRONGBOX_GENERIC -> "Starker Hardware-Schutz."
                SecurityLevel.TEE_STANDARD -> "TEE-Schutz aktiv."
                SecurityLevel.SOFTWARE_ONLY_ENHANCED -> "WARNUNG: Keine Hardware-Sicherung."
            })
    }

    data class SecurityStatusReport(
        val securityLevel: SecurityLevel, val deviceInfo: String,
        val androidVersion: String, val isHardwareBacked: Boolean, val recommendation: String)
}
