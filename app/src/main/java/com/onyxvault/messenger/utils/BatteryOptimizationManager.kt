package com.onyxvault.messenger.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * BatteryOptimizationManager – ONYX VAULT (Sovereign Edition)
 * Garantiert, dass dezentrale Hintergrunddienste (Tor/Nostr) nicht vom System gekillt werden.
 */
class BatteryOptimizationManager(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Prüft, ob die App von der Akku-Optimierung ausgenommen ist.
     * Dies ist notwendig für die dauerhafte dezentrale Erreichbarkeit.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Fordert den Nutzer auf, die Optimierung zu deaktivieren.
     * Dies geschieht forensisch sauber über einen System-Dialog.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i("ONYX-Battery", "Antrag auf Akku-Ausnahme gestellt.")
        } catch (e: Exception) {
            Log.e("ONYX-Battery", "Konnte Akku-Dialog nicht öffnen: ${e.message}")
        }
    }
}
