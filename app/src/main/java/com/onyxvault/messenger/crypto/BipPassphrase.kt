package com.onyxvault.messenger.crypto

import android.content.Context
import android.util.Log
import java.security.SecureRandom

/**
 * ONYX VAULT - BIP-39 Passphrase (5 Woerter, Standard-Wortliste)
 * Entropie: 5 Woerter = 55 bit = 36 Billiarden Kombinationen
 */
object BipPassphrase {
    private const val TAG = "BipPassphrase"
    const val DEFAULT_WORD_COUNT = 5
    const val MIN_WORD_COUNT = 4
    const val MAX_WORD_COUNT = 8

    enum class Locale { GERMAN, ENGLISH }
    enum class Strength(val wordCount: Int, val label: String, val entropyBits: Int) {
        BASIC(4, "Grundschutz", 44),
        RECOMMENDED(5, "Empfohlen", 55),
        PARANOID(6, "Paranoid", 66),
        OVERKILL(8, "Overkill", 88)
    }

    private var wordlistEn: List<String>? = null
    private var wordlistDe: List<String>? = null

    fun initialize(context: Context) {
        if (wordlistEn == null) {
            try { wordlistEn = loadWordlist(context, "bip39_en.txt") }
            catch (e: Exception) { Log.e(TAG, "EN wordlist missing: ${e.message}") }
        }
        if (wordlistDe == null) {
            try { wordlistDe = loadWordlist(context, "bip39_de.txt") }
            catch (e: Exception) { Log.w(TAG, "DE wordlist missing, EN only") }
        }
    }

    private fun loadWordlist(context: Context, filename: String): List<String> {
        return context.assets.open(filename).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }

    fun generate(wordCount: Int = DEFAULT_WORD_COUNT, locale: Locale = Locale.ENGLISH): List<String> {
        require(wordCount in MIN_WORD_COUNT..MAX_WORD_COUNT)
        val wordlist = when (locale) {
            Locale.GERMAN -> wordlistDe ?: wordlistEn
            Locale.ENGLISH -> wordlistEn
        } ?: error("No wordlist. Call initialize() first.")
        val sr = SecureRandom()
        return List(wordCount) { wordlist[sr.nextInt(wordlist.size)] }
    }

    fun toBytes(passphrase: List<String>): ByteArray {
        return passphrase.joinToString(" ") { it.lowercase().trim() }.toByteArray(Charsets.UTF_8)
    }

    fun wipe(bytes: ByteArray) {
        SecureRandom().nextBytes(bytes); bytes.fill(0)
    }

    fun isWeak(passphrase: List<String>): Boolean {
        if (passphrase.distinct().size == 1) return true
        if (passphrase.size < MIN_WORD_COUNT) return true
        return false
    }
}
