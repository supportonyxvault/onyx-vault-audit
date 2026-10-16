package com.onyxvault.messenger.security

import android.content.Context
import java.io.File
import java.security.SecureRandom

object SecureDelete {
    
    private val secureRandom = SecureRandom()
    
    /**
     * 3-Schichten Überschreibung eines ByteArrays im RAM
     */
    fun overwriteByteArray(data: ByteArray) {
        // Schicht 1: Nullen (0x00)
        data.fill(0)
        
        // Schicht 2: Einsen (0xFF)
        data.fill(0xFF.toByte())
        
        // Schicht 3: Zufallswerte
        secureRandom.nextBytes(data)
        
        // Finales Nullen
        data.fill(0)
    }
    
    /**
     * 3-Schichten Überschreibung einer Datei auf dem Speicher
     */
    fun overwriteFile(file: File) {
        if (!file.exists()) return
        
        try {
            val fileSize = file.length().toInt()
            val buffer = ByteArray(4096)
            
            // Schicht 1: Nullen
            file.outputStream().use { out ->
                var written = 0L
                while (written < fileSize) {
                    buffer.fill(0)
                    val toWrite = minOf(buffer.size.toLong(), fileSize - written).toInt()
                    out.write(buffer, 0, toWrite)
                    written += toWrite
                }
                out.flush()
            }
            
            // Schicht 2: Einsen
            file.outputStream().use { out ->
                var written = 0L
                while (written < fileSize) {
                    buffer.fill(0xFF.toByte())
                    val toWrite = minOf(buffer.size.toLong(), fileSize - written).toInt()
                    out.write(buffer, 0, toWrite)
                    written += toWrite
                }
                out.flush()
            }
            
            // Schicht 3: Zufall
            file.outputStream().use { out ->
                var written = 0L
                while (written < fileSize) {
                    secureRandom.nextBytes(buffer)
                    val toWrite = minOf(buffer.size.toLong(), fileSize - written).toInt()
                    out.write(buffer, 0, toWrite)
                    written += toWrite
                }
                out.flush()
            }
            
            file.delete()
        } catch (e: Exception) {
            file.delete() // Wenigstens löschen, wenn Überschreiben fehlschlägt
        }
    }

    /**
     * Zerstört alle App-Daten (Nuclear Option / Killswitch)
     */
    fun overwriteAllAppData(context: Context) {
        // 1. Datenbanken
        val dbDir = File(context.filesDir.parent, "databases")
        if (dbDir.exists()) {
            dbDir.listFiles()?.forEach { overwriteFile(it) }
        }
        
        // 2. SharedPreferences
        val prefsDir = File(context.filesDir.parent, "shared_prefs")
        if (prefsDir.exists()) {
            prefsDir.listFiles()?.forEach { overwriteFile(it) }
        }
        
        // 3. Tor & Files
        context.filesDir.walkBottomUp().forEach { file ->
            if (file.isFile) {
                overwriteFile(file)
            } else {
                file.delete()
            }
        }
        
        // 4. Cache
        context.cacheDir.deleteRecursively()
    }
}
