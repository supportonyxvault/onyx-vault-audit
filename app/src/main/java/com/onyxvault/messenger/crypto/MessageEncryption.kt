package com.onyxvault.messenger.crypto

import android.util.Base64
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import java.security.*
import java.math.BigInteger
import org.bouncycastle.util.encoders.Hex
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters

/**
 * MessageEncryption – ONYX VAULT (Forensic Sovereign Edition)
 * Modernisierte Implementierung ohne Deprecated-Warnungen.
 */
object MessageEncryption {

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Erzeugt ein Secp256k1 Schlüsselpaar für die dezentrale Nostr-Identität.
     */
    fun generateNostrKeyPair(): NostrKeyPair {
        val curve = ECNamedCurveTable.getByName("secp256k1")
        val domain = org.bouncycastle.crypto.params.ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val generator = ECKeyPairGenerator()
        generator.init(ECKeyGenerationParameters(domain, SecureRandom()))
        val pair = generator.generateKeyPair()
        
        val priv = (pair.private as ECPrivateKeyParameters).d.toString(16).padStart(64, '0')
        val pub = (pair.public as ECPublicKeyParameters).q.getEncoded(true).sliceArray(1..32)
        
        return NostrKeyPair(Hex.toHexString(pub), priv)
    }

    data class NostrKeyPair(val publicKeyHex: String, val privateKeyHex: String)

    fun getSharedSecret(myPrivateKeyHex: String, peerPublicKeyHex: String): ByteArray {
        val curve = ECNamedCurveTable.getByName("secp256k1")
        val domain = org.bouncycastle.crypto.params.ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val privKey = ECPrivateKeyParameters(BigInteger(myPrivateKeyHex, 16), domain)
        val pubPoint = curve.curve.decodePoint(Hex.decode("02$peerPublicKeyHex"))
        val pubKey = ECPublicKeyParameters(pubPoint, domain)
        val agreement = ECDHBasicAgreement()
        agreement.init(privKey)
        val secret = agreement.calculateAgreement(pubKey)
        return MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
    }

    fun encryptForPeer(text: String, myPrivateKeyHex: String, peerPublicKeyHex: String): String {
        val sharedSecret = getSharedSecret(myPrivateKeyHex, peerPublicKeyHex)
        val encrypted = encryptMessage(text, sharedSecret)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptFromPeer(encryptedBase64: String, myPrivateKeyHex: String, peerPublicKeyHex: String): String {
        val sharedSecret = getSharedSecret(myPrivateKeyHex, peerPublicKeyHex)
        val data = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        return decryptMessage(data, sharedSecret)
    }

    /**
     * Modernisiert: Nutzt Factory-Methoden statt Konstruktoren (Warnungen entfernt).
     */
    fun encryptMessage(text: String, key: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key.copyOf(32)), 128, iv)
        cipher.init(true, params)
        val input = text.toByteArray(Charsets.UTF_8)
        val output = ByteArray(cipher.getOutputSize(input.size))
        val len = cipher.processBytes(input, 0, input.size, output, 0)
        val finalLen = cipher.doFinal(output, len)
        return iv + output.sliceArray(0 until len + finalLen)
    }

    /**
     * Modernisiert: Nutzt Factory-Methoden statt Konstruktoren (Warnungen entfernt).
     */
    fun decryptMessage(encrypted: ByteArray, key: ByteArray): String {
        if (encrypted.size < 13) return "ERROR: DATA_CORRUPT"
        val iv = encrypted.sliceArray(0 until 12)
        val data = encrypted.sliceArray(12 until encrypted.size)
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key.copyOf(32)), 128, iv)
        cipher.init(false, params)
        val output = ByteArray(cipher.getOutputSize(data.size))
        val len = cipher.processBytes(data, 0, data.size, output, 0)
        val finalLen = try { cipher.doFinal(output, len) } catch (e: Exception) { 0 }
        return String(output, 0, len + finalLen, Charsets.UTF_8)
    }

    fun encryptMessageWithPin(text: String, key: ByteArray, pin: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val pinKey = md.digest((key + pin.toByteArray(Charsets.UTF_8)))
        return encryptMessage(text, pinKey)
    }

    fun decryptMessageWithPin(encrypted: ByteArray, key: ByteArray, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val pinKey = md.digest((key + pin.toByteArray(Charsets.UTF_8)))
        return decryptMessage(encrypted, pinKey)
    }
}
