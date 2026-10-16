package com.onyxvault.messenger.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest

/**
 * BIP-340 Schnorr Signature Verifier for Nostr events.
 *
 * Implementiert nach BIP-340 Spec (https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki).
 * Nutzt nur BouncyCastle secp256k1-Primitiven, keine zusaetzliche Dependency noetig.
 *
 * SICHERHEITS-KRITISCH: Diese Verifikation ist Pflicht bevor Nostr-Events (Kill/Recovery)
 * verarbeitet werden. Audit Finding #CRITICAL-2 (2026-10-16).
 */
object Bip340Verifier {

    private val curve = SECNamedCurves.getByName("secp256k1")
    private val p: BigInteger = curve.curve.field.characteristic  // Field prime
    private val n: BigInteger = curve.n                            // Group order
    private val G: ECPoint = curve.g

    /**
     * Verify BIP-340 Schnorr signature.
     *
     * @param msgHex    32-byte message as hex (fuer Nostr: event.id)
     * @param pubkeyHex 32-byte x-only public key as hex (event.pubkey)
     * @param sigHex    64-byte signature as hex (r || s), (event.sig)
     * @return true wenn Signatur gueltig, sonst false
     */
    fun verify(msgHex: String, pubkeyHex: String, sigHex: String): Boolean {
        return try {
            val msg = hexToBytes(msgHex)
            val pubkey = hexToBytes(pubkeyHex)
            val sig = hexToBytes(sigHex)

            if (msg.size != 32) return false
            if (pubkey.size != 32) return false
            if (sig.size != 64) return false

            // Lift x-only pubkey to point P (y = sqrt(x^3 + 7) mod p, pick even y)
            val px = BigInteger(1, pubkey)
            if (px >= p) return false
            val P = liftX(px) ?: return false

            val r = BigInteger(1, sig.sliceArray(0 until 32))
            val s = BigInteger(1, sig.sliceArray(32 until 64))
            if (r >= p) return false
            if (s >= n) return false

            // e = int(tagged_hash("BIP0340/challenge", r || pubkey || msg)) mod n
            val eBytes = taggedHash("BIP0340/challenge", bytesConcat(sig.sliceArray(0 until 32), pubkey, msg))
            val e = BigInteger(1, eBytes).mod(n)

            // R = s*G - e*P
            val R = G.multiply(s).subtract(P.multiply(e)).normalize()

            if (R.isInfinity) return false
            if (!hasEvenY(R)) return false
            if (R.xCoord.toBigInteger() != r) return false

            true
        } catch (e: Exception) {
            false
        }
    }

    /** Lift x-only coordinate to EC point with even y. */
    private fun liftX(x: BigInteger): ECPoint? {
        if (x >= p) return null
        // c = x^3 + 7 mod p
        val c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        // y = c^((p+1)/4) mod p   (works because p ≡ 3 mod 4 for secp256k1)
        val y = c.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        // Check: y^2 == c
        if (y.modPow(BigInteger.valueOf(2), p) != c) return null
        // BIP-340: pick even y
        val evenY = if (y.testBit(0)) p.subtract(y) else y
        return curve.curve.createPoint(x, evenY)
    }

    private fun hasEvenY(point: ECPoint): Boolean {
        val y = point.yCoord.toBigInteger()
        return !y.testBit(0)
    }

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val tagHash = md.digest(tag.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(tagHash)
        md.update(tagHash)
        md.update(msg)
        return md.digest()
    }

    private fun bytesConcat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, offset, a.size)
            offset += a.size
        }
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4)
                    + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
