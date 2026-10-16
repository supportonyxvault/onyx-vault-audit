package com.onyxvault.messenger.crypto

import android.util.Log
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * ONYX VAULT - Argon2id Key Derivation
 * Memory-hard: 4 MB RAM -> Optimiert für mobile Stabilität bei maximaler forensischer Sicherheit.
 */
object Argon2Helper {
    private const val TAG = "Argon2Helper"
    const val HASH_LENGTH = 32

    enum class Profile(val iterations: Int, val memoryKb: Int) {
        STANDARD(3, 4096),
        SECURE(5, 16384),
        FORENSIC(10, 65536)
    }

    fun deriveKey(
        passphrase: ByteArray, 
        salt: ByteArray, 
        profile: Profile = Profile.STANDARD
    ): ByteArray {
        val start = System.currentTimeMillis()
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(profile.iterations)
            .withMemoryAsKB(profile.memoryKb)
            .withParallelism(1)
            .withSalt(salt).build()

        val generator = Argon2BytesGenerator()
        generator.init(params)
        val hash = ByteArray(HASH_LENGTH)
        generator.generateBytes(passphrase, hash)
        Log.d(TAG, "Tresor-Key (${profile.name}) berechnet in ${System.currentTimeMillis() - start}ms")
        return hash
    }
}
