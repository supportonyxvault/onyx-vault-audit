package com.onyxvault.messenger.tor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.onyxvault.messenger.OnyxVaultApp
import kotlinx.coroutines.*
import org.torproject.jni.TorService as TorJni
import java.io.File

class TorService : Service() {

    companion object {
        private const val TAG             = "ONYX-TorService"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID      = "tor_channel"
        private const val WAKE_LOCK_TAG   = "OnyxVault:WakeLock"
        private const val STANDBY_MS      = 30 * 60 * 1000L
        private const val STANDBY_DIALOG_TIMEOUT_MS = 60 * 1000L
        private const val SOCKS_PORT      = 9050
        private const val CONTROL_PORT    = 9051
        private const val HS_PORT         = 8080

        const val ACTION_APP_FOREGROUND = "com.onyxvault.messenger.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.onyxvault.messenger.APP_BACKGROUND"
        const val ACTION_CANCEL_STANDBY = "com.onyxvault.messenger.CANCEL_STANDBY"
        const val ACTION_START_IMMEDIATE_STANDBY = "com.onyxvault.messenger.START_IMMEDIATE_STANDBY"
        const val ACTION_STANDBY_CONFIRM = "com.onyxvault.messenger.STANDBY_CONFIRM"
        const val ACTION_STANDBY_DENY    = "com.onyxvault.messenger.STANDBY_DENY"
        const val ACTION_SHUTDOWN        = "com.onyxvault.messenger.SHUTDOWN"
        const val ACTION_ROTATE_ONION    = "com.onyxvault.messenger.ROTATE_ONION"

        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var standbyJob: Job? = null
    private lateinit var torManager: TorManager

    override fun onCreate() {
        super.onCreate()
        val app = application as OnyxVaultApp
        if (!app.securePrefs.isTorEnabled()) {
            stopSelf()
            return
        }

        isRunning = true
        createChannel()
        startForegroundCompat(buildNotification("🔄 Tor-Netzwerk wird verbunden…"))
        acquireWakeLock()

        val torrcFile = writeTorrc()
        startTorDaemon(torrcFile)
        
        torManager = app.torManager
        monitorStatus()
        scheduleStandby()
    }

    private fun startTorDaemon(torrcFile: File) {
        val torServiceDir = getDir("TorService", MODE_PRIVATE)
        val torIntent = Intent(this, TorJni::class.java).apply {
            putExtra("org.torproject.android.intent.extra.TORRC_PATH", torrcFile.absolutePath)
            putExtra("org.torproject.android.intent.extra.DATA_PATH", torServiceDir.absolutePath)
        }
        startService(torIntent)
    }

    private fun writeTorrc(): File {
        val torServiceDir = getDir("TorService", MODE_PRIVATE)
        val hsDir = File(torServiceDir, "hs").also { if (!it.exists()) it.mkdirs() }
        val torrc = File(torServiceDir, "torrc")
        try {
            torrc.writeText(buildString {
                appendLine("SocksPort $SOCKS_PORT")
                appendLine("ControlPort $CONTROL_PORT")
                appendLine("DataDirectory ${torServiceDir.absolutePath}")
                appendLine("HiddenServiceDir ${hsDir.absolutePath}")
                appendLine("HiddenServicePort 80 127.0.0.1:$HS_PORT")
                appendLine("SafeLogging 0")
                appendLine("Log notice stdout")
            })
        } catch (e: Exception) { Log.e(TAG, "torrc write failed") }
        return torrc
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as OnyxVaultApp
        if (!app.securePrefs.isTorEnabled() || intent?.action == ACTION_SHUTDOWN) {
            shutdownAndStop()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_ROTATE_ONION -> rotateOnion()
            ACTION_APP_FOREGROUND -> { standbyJob?.cancel(); acquireWakeLock() }
            ACTION_APP_BACKGROUND -> scheduleStandby()
            ACTION_CANCEL_STANDBY -> resetStandby()
            ACTION_STANDBY_CONFIRM -> { releaseWakeLock(); notify("💤 Tor im Standby") }
            else -> resetStandby()
        }
        return START_STICKY
    }

    private fun shutdownAndStop() {
        isRunning = false
        releaseWakeLock()
        stopService(Intent(this, TorJni::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            nm.cancel(NOTIFICATION_ID + 1)
        } catch (e: Exception) {}
        scope.cancel()
        stopSelf()
    }

    /**
     * Aggressive forensische Rotation.
     * Stoppt alles, löscht Identität & Locks, wartet 5s, startet neu.
     */
    private fun rotateOnion() {
        scope.launch {
            Log.w(TAG, "Starte Onion-Rotation (Aggressiv)...")
            notify("🔄 Rotiere Identität...")
            stopService(Intent(this@TorService, TorJni::class.java))
            
            // WICHTIG: Genug Zeit für Dateisystem-Freigabe geben
            delay(5000)
            
            val torServiceDir = getDir("TorService", MODE_PRIVATE)
            File(torServiceDir, "hs").deleteRecursively()
            File(torServiceDir, "lock").delete()
            
            val torrcFile = writeTorrc()
            startTorDaemon(torrcFile)
            Log.i(TAG, "Tor erfolgreich mit neuer Identität gestartet.")
        }
    }

    private fun monitorStatus() {
        scope.launch {
            torManager.torReady.collect { ready ->
                if (!isRunning) return@collect
                val onion = torManager.onionAddress.value
                val text = if (ready && onion != null) "🟢 Tor aktiv: ${onion.take(12)}…" else "🔄 Verbinde…"
                notify(text)
            }
        }
    }

    private fun scheduleStandby() {
        standbyJob?.cancel()
        if (!isRunning) return
        standbyJob = scope.launch {
            delay(STANDBY_MS)
            showStandbyDialog()
            delay(STANDBY_DIALOG_TIMEOUT_MS)
            if (isRunning) { releaseWakeLock(); notify("💤 Standby aktiv") }
        }
    }

    private fun showStandbyDialog() {
        if (!isRunning) return
        val confirmIntent = Intent(this, TorService::class.java).apply { action = ACTION_STANDBY_CONFIRM }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val confirmPending = PendingIntent.getService(this, 1, confirmIntent, flags)
        
        // Standby-Confirm wird NICHT mehr gezeigt - User-Wunsch keine zusaetzlichen Notifications
        // val notification = ...  (ausgeschaltet)
    }

    private fun resetStandby() { if (isRunning) { acquireWakeLock(); scheduleStandby() } }
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { acquire(10 * 60 * 1000L) }
    }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }
    private fun notify(text: String) {
        if (!isRunning) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
    private fun buildNotification(text: String): Notification {
        val app = application as OnyxVaultApp
        val visible = try { app.securePrefs.isNotificationVisible() } catch (e: Exception) { false }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.onyxvault.messenger.R.mipmap.ic_launcher_monochrome)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (visible) {
            builder.setContentTitle("ONYX VAULT")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        } else {
            // Default: fast unsichtbar - Android zwingt Foreground-Service zu einer Notification,
            // aber mit leerem Content + IMPORTANCE_MIN sieht man praktisch nichts
            builder.setContentTitle(" ")
                .setContentText(" ")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
        return builder.build()
    }
    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "ONYX VAULT (minimal)", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { shutdownAndStop(); super.onDestroy() }
}
