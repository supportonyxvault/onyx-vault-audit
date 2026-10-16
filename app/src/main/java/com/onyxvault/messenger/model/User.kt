package com.onyxvault.messenger.model

import java.util.UUID

/**
 * User – ONYX VAULT (Forensic Identity)
 * Die Identität ist dezentral und an die Hardware gebunden.
 */
data class User(
    val username: String,
    val pin: String,
    val masterPublicKey: String,
    val appId: String
) {
    companion object {
        /**
         * Generiert eine forensisch eindeutige ID für die Hardware-Verschränkung.
         */
        fun generateAppId(): String = UUID.randomUUID().toString()
    }
}
