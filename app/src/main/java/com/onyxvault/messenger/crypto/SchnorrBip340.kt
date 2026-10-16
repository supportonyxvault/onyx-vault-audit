package com.onyxvault.messenger.crypto

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger

/**
 * BIP-340 Schnorr Signatures for secp256k1 (as required by Nostr NIP-01).
 *
 * Reference: https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki
 *
 * Note: ECDSA (as previously used) does NOT work with Nostr - all relays reject it
 * with "Event id does not match content hash". BIP-340 Schnorr is mandatory.
 */
object SchnorrBip340 {

    private val secp256k1 = SECNamedCurves.getByName("secp256k1")
    private val n: BigInteger = secp256k1.n
    private val G = secp256k1.g
    private val curve = secp256k1.curve
    private val fieldPrime: BigInteger = (curve as org.bouncycastle.math.ec.ECCurve.Fp).q

    /**
     * Signs a 32-byte message with a 32-byte private key per BIP-340.
     * Returns 64-byte signature (R.x || s) as lowercase hex (128 chars).
     *
     * @param messageHex 32-byte message hash as hex (e.g. Nostr event id)
     * @param privateKeyHex 32-byte secp256k1 private key as hex
     * @param auxRand optional 32-byte entropy (default: zeros for determinism)
     */
    fun sign(messageHex: String, privateKeyHex: String, auxRand: ByteArray = ByteArray(32)): String {
        val msg = Hex.decode(messageHex)
        require(msg.size == 32) { "message must be 32 bytes" }
        require(auxRand.size == 32) { "aux_rand must be 32 bytes" }

        val d0 = BigInteger(1, Hex.decode(privateKeyHex))
        if (d0 <= BigInteger.ZERO || d0 >= n) {
            throw IllegalArgumentException("private key out of range")
        }

        // 1. P = d'*G, if P.y is odd then d = n-d' else d = d'
        val P = G.multiply(d0).normalize()
        val d = if (P.affineYCoord.toBigInteger().testBit(0)) n.subtract(d0) else d0

        // 2. t = bytes(d) XOR H_aux(a)
        val tHash = taggedHash("BIP0340/aux", auxRand)
        val dBytes = bigIntToBytes32(d)
        val t = ByteArray(32)
        for (i in 0..31) t[i] = (dBytes[i].toInt() xor tHash[i].toInt()).toByte()

        // 3. rand = H_nonce(t || P.x || m)
        val pxBytes = bigIntToBytes32(P.affineXCoord.toBigInteger())
        val nonceInput = ByteArray(96)
        System.arraycopy(t, 0, nonceInput, 0, 32)
        System.arraycopy(pxBytes, 0, nonceInput, 32, 32)
        System.arraycopy(msg, 0, nonceInput, 64, 32)
        val rand = taggedHash("BIP0340/nonce", nonceInput)

        // 4. k' = int(rand) mod n
        val k0 = BigInteger(1, rand).mod(n)
        if (k0 == BigInteger.ZERO) {
            throw IllegalStateException("k' is zero - should retry with new aux_rand")
        }

        // 5. R = k'*G, if R.y is odd then k = n-k' else k = k'
        val R = G.multiply(k0).normalize()
        val k = if (R.affineYCoord.toBigInteger().testBit(0)) n.subtract(k0) else k0

        // 6. e = int(H_challenge(R.x || P.x || m)) mod n
        val rxBytes = bigIntToBytes32(R.affineXCoord.toBigInteger())
        val challengeInput = ByteArray(96)
        System.arraycopy(rxBytes, 0, challengeInput, 0, 32)
        System.arraycopy(pxBytes, 0, challengeInput, 32, 32)
        System.arraycopy(msg, 0, challengeInput, 64, 32)
        val eHash = taggedHash("BIP0340/challenge", challengeInput)
        val e = BigInteger(1, eHash).mod(n)

        // 7. sig = R.x || (k + e*d) mod n
        val s = k.add(e.multiply(d)).mod(n)
        val sBytes = bigIntToBytes32(s)

        return Hex.toHexString(rxBytes + sBytes)
    }

    /**
     * Verifies a BIP-340 Schnorr signature.
     * Used primarily for incoming Nostr events.
     */
    fun verify(messageHex: String, publicKeyHex: String, signatureHex: String): Boolean {
        return try {
            val msg = Hex.decode(messageHex)
            val sig = Hex.decode(signatureHex)
            if (msg.size != 32 || sig.size != 64) return false

            val px = BigInteger(1, Hex.decode(publicKeyHex))
            val p = fieldPrime
            if (px >= p) return false

            // Lift x-only pubkey (BIP-340 forces even y)
            val P = liftX(px) ?: return false

            val r = BigInteger(1, sig.copyOfRange(0, 32))
            val s = BigInteger(1, sig.copyOfRange(32, 64))
            if (r >= p || s >= n) return false

            val pxBytes = bigIntToBytes32(px)
            val rxBytes = bigIntToBytes32(r)
            val challengeInput = ByteArray(96)
            System.arraycopy(rxBytes, 0, challengeInput, 0, 32)
            System.arraycopy(pxBytes, 0, challengeInput, 32, 32)
            System.arraycopy(msg, 0, challengeInput, 64, 32)
            val e = BigInteger(1, taggedHash("BIP0340/challenge", challengeInput)).mod(n)

            // R = s*G - e*P
            val sG = G.multiply(s)
            val eP = P.multiply(e)
            val R = sG.subtract(eP).normalize()
            if (R.isInfinity) return false
            if (R.affineYCoord.toBigInteger().testBit(0)) return false // y must be even
            R.affineXCoord.toBigInteger() == r
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lifts an x-only pubkey to a full curve point with even y.
     */
    private fun liftX(x: BigInteger): org.bouncycastle.math.ec.ECPoint? {
        return try {
            val p = fieldPrime
            val a = curve.a.toBigInteger()
            val b = curve.b.toBigInteger()
            if (x >= p) return null
            val ySq = x.pow(3).add(a.multiply(x)).add(b).mod(p)
            // sqrt via (p+1)/4 since secp256k1.p mod 4 == 3
            val exponent = p.add(BigInteger.ONE).shiftRight(2)
            val y = ySq.modPow(exponent, p)
            if (y.multiply(y).mod(p) != ySq) return null
            val yEven = if (y.testBit(0)) p.subtract(y) else y
            curve.createPoint(x, yEven).normalize()
        } catch (e: Exception) { null }
    }

    /**
     * tagged_hash(tag, msg) = SHA256(SHA256(tag) || SHA256(tag) || msg)
     */
    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagBytes = tag.toByteArray(Charsets.US_ASCII)
        val tagDigest = SHA256Digest()
        tagDigest.update(tagBytes, 0, tagBytes.size)
        val tagHash = ByteArray(32)
        tagDigest.doFinal(tagHash, 0)

        val finalDigest = SHA256Digest()
        finalDigest.update(tagHash, 0, 32)
        finalDigest.update(tagHash, 0, 32)
        finalDigest.update(msg, 0, msg.size)
        val result = ByteArray(32)
        finalDigest.doFinal(result, 0)
        return result
    }

    /**
     * Converts BigInteger to exactly 32 bytes big-endian (left-padded with zeros).
     */
    private fun bigIntToBytes32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32).also { System.arraycopy(raw, 0, it, 32 - raw.size, raw.size) }
            else -> raw.copyOfRange(raw.size - 32, raw.size)
        }
    }
}
