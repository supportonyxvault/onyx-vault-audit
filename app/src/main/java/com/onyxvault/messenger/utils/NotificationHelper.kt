package com.onyxvault.messenger.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.onyxvault.messenger.R

/**
 * NotificationHelper – ONYX VAULT (Anti-Forensik Edition)
 * Garantiert, dass keine sensiblen Daten (Inhalt/Sender) im Android-System-Log
 * oder auf dem Sperrbildschirm landen.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "onyx_secure_messages"
        private const val CHANNEL_NAME = "Gesicherte Nachrichten"
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hinweise auf neue dezentrale Nachrichten"
                // Forensische Anforderung: Keine Details auf dem Sperrbildschirm
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)  // v36: App-Symbolindikator (Badge) aktivieren
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Zeigt eine anonymisierte Benachrichtigung an.
     * Es wird niemals der Inhalt oder der echte Username angezeigt.
     */
    fun showNewMessageNotification(senderPlaceholder: String = "Ein Kontakt") {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ONYX VAULT")
            .setContentText("Neue verschlüsselte Nachricht empfangen")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Nur Symbol auf Sperrbildschirm
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun showPingNotification(from: String, count: Int) {
        // Auch Pings werden anonymisiert
        showNewMessageNotification()
    }

    fun cancelAll() {
        manager.cancelAll()
    }
}
