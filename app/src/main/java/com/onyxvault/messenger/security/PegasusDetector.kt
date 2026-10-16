package com.onyxvault.messenger.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * ONYX VAULT - Pegasus/Spyware Detector
 * Fokus auf echte forensische Bedrohungen. 
 * Überwacht physische Einfallstore (ADB via USB-Kabel), da diese für Kabel-Angriffe kritisch sind.
 */
class PegasusDetector(private val context: Context) {
    companion object {
        private const val TAG = "PegasusDetector"
        private val SUSPICIOUS_PACKAGES = listOf(
            "com.pegasus.spy", "com.nso.pegasus",
            "com.intellexa.predator", "com.quadream.reign")
    }

    data class ThreatReport(
        val threatsDetected: List<Threat>, 
        val overallRisk: RiskLevel,
        val timestamp: Long = System.currentTimeMillis(), 
        val recommendation: String
    )

    data class Threat(val type: ThreatType, val details: String, val severity: Severity)

    enum class ThreatType {
        SUSPICIOUS_PACKAGE_INSTALLED, ADB_ENABLED, DEVELOPER_OPTIONS_ENABLED,
        UNKNOWN_SOURCES_ENABLED, ROOT_DETECTED, FRIDA_DETECTED, XPOSED_DETECTED,
        EMULATOR_DETECTED, DEBUGGER_ATTACHED, SUSPICIOUS_FILES
    }
    enum class Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }
    enum class RiskLevel { SAFE, SUSPICIOUS, COMPROMISED, CRITICAL }

    fun scanDevice(): ThreatReport {
        val threats = mutableListOf<Threat>()
        
        // 1. SCAN NACH SPYWARE & MANIPULATION (CRITICAL/HIGH)
        checkSuspiciousPackages()?.let { threats.add(it) }
        checkRoot()?.let { threats.add(it) }
        checkFrida()?.let { threats.add(it) }
        checkXposed()?.let { threats.add(it) }
        
        // 2. SCAN NACH PHYSISCHEN ZUGRIFFSWEGEN (MEDIUM/LOW)
        // ADB ist ein Risiko für Kabel-Extraktion und wird als MEDIUM eingestuft.
        checkAdbEnabled()?.let { threats.add(it) }
        checkDeveloperOptions()?.let { threats.add(it) }

        checkEmulator()?.let { threats.add(it) }
        
        val risk = calculateRisk(threats)
        return ThreatReport(
            threatsDetected = threats,
            overallRisk = risk,
            timestamp = System.currentTimeMillis(),
            recommendation = buildRecommendation(risk)
        )
    }

    private fun checkAdbEnabled(): Threat? {
        val adb = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) { false }
        
        // MEDIUM Einstufung: Damit es im Audit-Log als WARNING erscheint (Schutz vor Kabel-Zugriff)
        return if (adb) Threat(ThreatType.ADB_ENABLED, "USB-Debugging (ADB) aktiv - Risiko für Kabel-Extraktion!", Severity.MEDIUM) else null
    }

    private fun checkDeveloperOptions(): Threat? {
        val dev = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) { false }
        // LOW wird als Information gewertet und im Audit-Log gefiltert
        return if (dev) Threat(ThreatType.DEVELOPER_OPTIONS_ENABLED, "Entwickleroptionen aktiv", Severity.LOW) else null
    }

    private fun checkSuspiciousPackages(): Threat? {
        val found = SUSPICIOUS_PACKAGES.filter { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        }
        return if (found.isNotEmpty()) Threat(ThreatType.SUSPICIOUS_PACKAGE_INSTALLED, "Spyware-Signatur gefunden!", Severity.CRITICAL) else null
    }

    private fun checkRoot(): Threat? {
        val paths = listOf("/system/app/Superuser.apk", "/system/xbin/su", "/system/bin/su", "/sbin/su")
        return if (paths.any { File(it).exists() }) Threat(ThreatType.ROOT_DETECTED, "Root erkannt", Severity.HIGH) else null
    }

    private fun checkFrida(): Threat? {
        return if (File("/data/local/tmp/frida-server").exists() || File("/data/local/tmp/re.frida.server").exists()) {
            Threat(ThreatType.FRIDA_DETECTED, "Frida-Manipulation erkannt", Severity.CRITICAL)
        } else null
    }

    private fun checkXposed(): Threat? {
        return try {
            context.packageManager.getPackageInfo("de.robv.android.xposed.installer", 0)
            Threat(ThreatType.XPOSED_DETECTED, "Xposed Hooking Framework!", Severity.CRITICAL)
        } catch (e: Exception) { null }
    }

    private fun checkDebugger(): Threat? = null

    private fun checkEmulator(): Threat? {
        val isEmu = Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.MODEL.contains("Emulator")
        return if (isEmu) Threat(ThreatType.EMULATOR_DETECTED, "Unsichere Hardware (Emulator)", Severity.MEDIUM) else null
    }

    private fun calculateRisk(threats: List<Threat>): RiskLevel {
        // Dev-Optionen (LOW) ignorieren wir für das Gesamtrisiko
        val realThreats = threats.filter { it.severity != Severity.INFO && it.severity != Severity.LOW }
        if (realThreats.any { it.severity == Severity.CRITICAL }) return RiskLevel.CRITICAL
        if (realThreats.any { it.severity == Severity.HIGH }) return RiskLevel.COMPROMISED
        // Erst wenn ADB (MEDIUM) und ein weiteres Risiko (z.B. Emulator) da sind, wird es SUSPICIOUS
        if (realThreats.count { it.severity == Severity.MEDIUM } >= 2) return RiskLevel.SUSPICIOUS
        return RiskLevel.SAFE
    }

    private fun buildRecommendation(risk: RiskLevel): String = when (risk) {
        RiskLevel.SAFE -> "Geraet ist forensisch sauber."
        RiskLevel.CRITICAL -> "KRITISCH: Datenvernichtung wird empfohlen!"
        else -> "Bitte Sicherheitsstatus im Audit-Log pruefen."
    }
}
