@file:Suppress("DEPRECATION")
package com.onyxvault.messenger.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePrefs – ONYX VAULT (Zero-Knowledge Edition)
 * Handhabt hardware-verschlüsselte Einstellungen.
 * Warnungen unterdrückt, da Google aktuell keine stabilen Nachfolger anbietet.
 */
class SecurePrefs(context: Context) {

    companion object {
        private const val PREFS_NAME = "onyxvault_secure_prefs"

        private const val KEY_USERNAME = "username"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_MASTER_PUBLIC_KEY = "master_public_key"
        private const val KEY_MASTER_PRIVATE_KEY = "master_private_key"
        private const val KEY_DB_PASSWORD = "db_seed_hardware_wrapped"
        private const val KEY_KILL_SWITCH_PUBKEY = "kill_switch_pubkey"
        private const val KEY_KILL_SWITCH_PRIVKEY = "kill_switch_privkey"
        private const val KEY_REQUIRE_NEW_PIN = "require_new_pin_after_recovery"
        private const val KEY_LAST_RECOVERY_EVENT_ID = "last_processed_recovery_event_id"
        private const val KEY_LAST_KILL_EVENT_ID = "last_processed_kill_event_id"
        private const val KEY_DB_RECOVERY_BLOB = "db_recovery_blob_v37"
        private const val KEY_PIN_LOCK_ENABLED = "pin_lock_enabled"
        private const val KEY_APP_LOCKED = "app_is_locked"
        private const val KEY_SHOW_NOTIFICATION = "show_notification"
    private const val KEY_TOR_ENABLED = "tor_enabled"
        private const val KEY_SIDEBAR_VISIBLE = "sidebar_visible"
        private const val KEY_CHAT_BG_TYPE = "chat_bg_type"
        private const val KEY_CHAT_BG_URI = "chat_bg_uri"
        private const val KEY_CONTACTS_BG_TYPE = "contacts_bg_type"
        private const val KEY_CONTACTS_BG_URI = "contacts_bg_uri"
    }

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyGenParameterSpec(
            KeyGenParameterSpec.Builder(
                MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveUsername(username: String) = prefs.edit().putString(KEY_USERNAME, username).apply()
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    fun saveAppId(appId: String) = prefs.edit().putString(KEY_APP_ID, appId).apply()
    fun getAppId(): String? = prefs.getString(KEY_APP_ID, null)

    fun getDatabasePassword(): String? = prefs.getString(KEY_DB_PASSWORD, null)
    fun saveDatabasePassword(password: String) = prefs.edit().putString(KEY_DB_PASSWORD, password).apply()

    fun saveKillSwitchKeys(pubKey: String, privKey: String) {
        prefs.edit().putString(KEY_KILL_SWITCH_PUBKEY, pubKey)
            .putString(KEY_KILL_SWITCH_PRIVKEY, privKey).apply()
    }
    
    fun saveKillSwitchPubKey(pubKey: String) = prefs.edit().putString(KEY_KILL_SWITCH_PUBKEY, pubKey).apply()
    fun getKillSwitchPubKey(): String? = prefs.getString(KEY_KILL_SWITCH_PUBKEY, null)
    fun getKillSwitchPrivKey(): String? = prefs.getString(KEY_KILL_SWITCH_PRIVKEY, null)
    fun setRequireNewPin(require: Boolean) = prefs.edit().putBoolean(KEY_REQUIRE_NEW_PIN, require).apply()
    fun isRequireNewPin(): Boolean = prefs.getBoolean(KEY_REQUIRE_NEW_PIN, false)
    fun getLastRecoveryEventId(): String? = prefs.getString(KEY_LAST_RECOVERY_EVENT_ID, null)
    fun setLastRecoveryEventId(id: String) = prefs.edit().putString(KEY_LAST_RECOVERY_EVENT_ID, id).apply()
    fun getLastKillEventId(): String? = prefs.getString(KEY_LAST_KILL_EVENT_ID, null)
    fun setLastKillEventId(id: String) = prefs.edit().putString(KEY_LAST_KILL_EVENT_ID, id).apply()
    fun getDbRecoveryBlob(): String? = prefs.getString(KEY_DB_RECOVERY_BLOB, null)
    fun saveDbRecoveryBlob(blob: String) = prefs.edit().putString(KEY_DB_RECOVERY_BLOB, blob).apply()
    fun hasDbRecoveryBlob(): Boolean = !prefs.getString(KEY_DB_RECOVERY_BLOB, null).isNullOrBlank()

    fun setTorEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_TOR_ENABLED, enabled).apply()
    fun isTorEnabled(): Boolean = prefs.getBoolean(KEY_TOR_ENABLED, true)

    fun setAppLocked(locked: Boolean) = prefs.edit().putBoolean(KEY_APP_LOCKED, locked).apply()
    fun isAppLocked(): Boolean = prefs.getBoolean(KEY_APP_LOCKED, false)
    fun isPinLockEnabled(): Boolean = prefs.getBoolean(KEY_PIN_LOCK_ENABLED, true)

    fun saveMasterKeys(publicKey: String, privateKey: String) {
        prefs.edit().putString(KEY_MASTER_PUBLIC_KEY, publicKey)
            .putString(KEY_MASTER_PRIVATE_KEY, privateKey).apply()
    }
    fun getMasterPublicKey(): String? = prefs.getString(KEY_MASTER_PUBLIC_KEY, null)
    fun getMasterPrivateKey(): String? = prefs.getString(KEY_MASTER_PRIVATE_KEY, null)

    // UI-Methoden (Hintergründe & Sidebar)
    fun getChatBgType(): Int = prefs.getInt(KEY_CHAT_BG_TYPE, 0)
    fun setChatBgType(type: Int) = prefs.edit().putInt(KEY_CHAT_BG_TYPE, type).apply()
    fun getChatCustomBgUri(): String? = prefs.getString(KEY_CHAT_BG_URI, null)
    fun setChatCustomBgUri(uri: String?) = prefs.edit().putString(KEY_CHAT_BG_URI, uri).apply()
    fun getContactsBgType(): Int = prefs.getInt(KEY_CONTACTS_BG_TYPE, 0)
    fun setContactsBgType(type: Int) = prefs.edit().putInt(KEY_CONTACTS_BG_TYPE, type).apply()
    fun getContactsCustomBgUri(): String? = prefs.getString(KEY_CONTACTS_BG_URI, null)
    fun setContactsCustomBgUri(uri: String?) = prefs.edit().putString(KEY_CONTACTS_BG_URI, uri).apply()
    fun isSidebarVisible(): Boolean = prefs.getBoolean(KEY_SIDEBAR_VISIBLE, true)
    fun setSidebarVisible(visible: Boolean) = prefs.edit().putBoolean(KEY_SIDEBAR_VISIBLE, visible).apply()

    fun clearAll() = prefs.edit().clear().apply()
    fun isNotificationVisible(): Boolean = prefs.getBoolean(KEY_SHOW_NOTIFICATION, false)
    fun setNotificationVisible(visible: Boolean) = prefs.edit().putBoolean(KEY_SHOW_NOTIFICATION, visible).apply()

}
