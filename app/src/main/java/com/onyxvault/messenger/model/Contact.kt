package com.onyxvault.messenger.model

/**
 * Ein Kontakt wird AUSSCHLIESSLICH über den Token-Handshake hinzugefügt.
 * KEIN QR-Code für Kontakte – nur für die Website-Registrierung.
 *
 * Handshake-Ablauf:
 * 1. Sender sucht exakten Username → schickt Anfrage + TokenA
 * 2. Empfänger bestätigt → schickt TokenB zurück
 * 3. Aus TokenA + TokenB wird der gemeinsame ChatKey abgeleitet
 */
data class Contact(
    val username: String,
    val masterPublicKey: String,
    var sessionOnion: String = "",       // Aktuelle .onion Adresse des Kontakts
    var sessionPublicKey: String = "",   // Öffentlicher Schlüssel dieser Session
    var chatKey: ByteArray? = null,      // Abgeleiteter gemeinsamer Schlüssel
    var pendingToken: String = "",       // Token A (von uns gesendet)
    var receivedToken: String = ""       // Token B (vom Kontakt erhalten)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Contact
        if (username != other.username) return false
        if (masterPublicKey != other.masterPublicKey) return false
        if (sessionOnion != other.sessionOnion) return false
        if (!chatKey.contentEquals(other.chatKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = username.hashCode()
        result = 31 * result + masterPublicKey.hashCode()
        result = 31 * result + sessionOnion.hashCode()
        result = 31 * result + (chatKey?.contentHashCode() ?: 0)
        return result
    }
}

// Hilfs-Extension für null-safe contentEquals
private fun ByteArray?.contentEquals(other: ByteArray?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}
