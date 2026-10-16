package com.onyxvault.messenger.tor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.onyxvault.messenger.utils.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Calendar

/**
 * TorManager – Steuert den Tor-Lifecycle und überwacht den Status.
 * FIX: Korrekte Action-Strings für die Onion-Rotation.
 */
class TorManager(private val context: Context) {

    companion object {
        private const val TAG        = "TorManager"
        private const val SOCKS_PORT = 9050
        private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        private const val EXTRA_STATUS  = "org.torproject.android.intent.extra.STATUS"

        private const val STATUS_ON       = "ON"
        private const val STATUS_OFF      = "OFF"
        private const val STATUS_STOPPING = "STOPPING"
    }

    private val _torReady     = MutableStateFlow(false)
    val torReady              = _torReady.asStateFlow()

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress          = _onionAddress.asStateFlow()

    private val securePrefs = SecurePrefs(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var onionWatchJob: Job? = null

    val socksProxy: Proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress("127.0.0.1", SOCKS_PORT)
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(EXTRA_STATUS) ?: return
            Log.d(TAG, "Empfange Tor-Status: $status")
            when (status) {
                STATUS_ON -> {
                    _torReady.value = true
                    startOnionWatch()
                }
                STATUS_OFF, STATUS_STOPPING -> {
                    _torReady.value     = false
                    _onionAddress.value = null
                    onionWatchJob?.cancel()
                }
            }
        }
    }

    init {
        registerReceiver()
        scheduleDailyRotation()
    }

    fun startTor() {
        Log.i(TAG, "Tor-Netzwerk Start angefordert...")
        securePrefs.setTorEnabled(true)
        try {
            val intent = Intent(context, TorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten von TorService: ${e.message}")
        }
    }

    fun stopTor() {
        Log.i(TAG, "Tor-Netzwerk STOPP angefordert.")
        // WICHTIG: setTorEnabled(false) wird hier NICHT aufgerufen!
        // Das ist eine User-Praeferenz. BEENDEN soll Tor beim naechsten Start
        // wieder normal starten. Nur der TorService wird durch ACTION_SHUTDOWN gestoppt.
        val shutdownIntent = Intent(context, TorService::class.java).apply {
            action = TorService.ACTION_SHUTDOWN
        }
        context.startService(shutdownIntent)

        _torReady.value     = false
        _onionAddress.value = null
        onionWatchJob?.cancel()
    }

    /**
     * Rotiert die Identität über den TorService.
     * FIX: Nutzt jetzt den korrekten ACTION-String aus dem TorService.
     */
    fun rotateOnionIdentity() {
        if (!securePrefs.isTorEnabled()) return
        val intent = Intent(context, TorService::class.java).apply { 
            action = TorService.ACTION_ROTATE_ONION 
        }
        context.startService(intent)
    }

    private fun startOnionWatch() {
        onionWatchJob?.cancel()
        onionWatchJob = scope.launch {
            val torServiceDir = context.getDir("TorService", Context.MODE_PRIVATE)
            val hostnameFile  = File(File(torServiceDir, "hs"), "hostname")
            repeat(60) {
                if (hostnameFile.exists()) {
                    val onion = hostnameFile.readText().trim()
                    if (onion.endsWith(".onion")) {
                        _onionAddress.value = onion
                        return@launch
                    }
                }
                delay(2000)
            }
        }
    }

    private fun scheduleDailyRotation() {
        scope.launch {
            while (isActive) {
                delay(millisUntilMidnight())
                if (securePrefs.isTorEnabled()) rotateOnionIdentity()
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter(ACTION_STATUS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun millisUntilMidnight(): Long {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return midnight.timeInMillis - System.currentTimeMillis()
    }
}
