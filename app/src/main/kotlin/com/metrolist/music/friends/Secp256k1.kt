/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secp256k1 curve as Nostr uses it: x-only public keys, BIP-340 Schnorr signatures and the ECDH
 * shared point for encrypting to a friend. Plain BigInteger arithmetic in Jacobian coordinates —
 * a few milliseconds per signature, which is plenty for a handful of events.
 */
internal object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val TWO = BigInteger.valueOf(2)
    private val random = SecureRandom()

    private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val infinity: Boolean get() = z.signum() == 0
    }

    private val G = Jac(GX, GY, BigInteger.ONE)
    private val INF = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun double(p: Jac): Jac {
        if (p.infinity || p.y.signum() == 0) return INF
        val ysq = p.y.multiply(p.y).mod(P)
        val s = BigInteger.valueOf(4).multiply(p.x).multiply(ysq).mod(P)
        val m = BigInteger.valueOf(3).multiply(p.x).multiply(p.x).mod(P)
        val nx = m.multiply(m).subtract(s.shiftLeft(1)).mod(P)
        val ny = m.multiply(s.subtract(nx)).subtract(BigInteger.valueOf(8).multiply(ysq).multiply(ysq)).mod(P)
        val nz = TWO.multiply(p.y).multiply(p.z).mod(P)
        return Jac(nx, ny, nz)
    }

    private fun add(p: Jac, q: Jac): Jac {
        if (p.infinity) return q
        if (q.infinity) return p
        val z1z1 = p.z.multiply(p.z).mod(P)
        val z2z2 = q.z.multiply(q.z).mod(P)
        val u1 = p.x.multiply(z2z2).mod(P)
        val u2 = q.x.multiply(z1z1).mod(P)
        val s1 = p.y.multiply(q.z).multiply(z2z2).mod(P)
        val s2 = q.y.multiply(p.z).multiply(z1z1).mod(P)
        if (u1 == u2) return if (s1 == s2) double(p) else INF
        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val h2 = h.multiply(h).mod(P)
        val h3 = h2.multiply(h).mod(P)
        val u1h2 = u1.multiply(h2).mod(P)
        val nx = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P)
        val ny = r.multiply(u1h2.subtract(nx)).subtract(s1.multiply(h3)).mod(P)
        val nz = h.multiply(p.z).multiply(q.z).mod(P)
        return Jac(nx, ny, nz)
    }

    private fun multiply(p: Jac, k: BigInteger): Jac {
        var result = INF
        var addend = p
        var e = k
        while (e.signum() > 0) {
            if (e.testBit(0)) result = add(result, addend)
            addend = double(addend)
            e = e.shiftRight(1)
        }
        return result
    }

    /** Affine (x, y); null for the point at infinity. */
    private fun affine(p: Jac): Pair<BigInteger, BigInteger>? {
        if (p.infinity) return null
        val zi = p.z.modInverse(P)
        val zi2 = zi.multiply(zi).mod(P)
        return p.x.multiply(zi2).mod(P) to p.y.multiply(zi2).multiply(zi).mod(P)
    }

    /** The point with x = [x] and an even y (BIP-340 lift_x); null when there is none. */
    private fun liftX(x: BigInteger): Jac? {
        if (x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != c) return null
        return Jac(x, if (y.testBit(0)) P.subtract(y) else y, BigInteger.ONE)
    }

    fun newPrivateKey(): ByteArray {
        while (true) {
            val bytes = ByteArray(32).also(random::nextBytes)
            val d = BigInteger(1, bytes)
            if (d.signum() > 0 && d < N) return bytes
        }
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        val (x, _) = affine(multiply(G, BigInteger(1, privateKey)))!!
        return bytes32(x)
    }

    fun sign(message: ByteArray, privateKey: ByteArray, auxRand: ByteArray? = null): ByteArray {
        val d0 = BigInteger(1, privateKey)
        val (px, py) = affine(multiply(G, d0))!!
        val d = if (py.testBit(0)) N.subtract(d0) else d0
        val aux = auxRand ?: ByteArray(32).also(random::nextBytes)
        val t = bytes32(d).xor(taggedHash("BIP0340/aux", aux))
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t + bytes32(px) + message)).mod(N)
        require(k0.signum() != 0)
        val (rx, ry) = affine(multiply(G, k0))!!
        val k = if (ry.testBit(0)) N.subtract(k0) else k0
        val e = BigInteger(1, taggedHash("BIP0340/challenge", bytes32(rx) + bytes32(px) + message)).mod(N)
        return bytes32(rx) + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(message: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (signature.size != 64 || publicKey.size != 32) return false
        val pk = liftX(BigInteger(1, publicKey)) ?: return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + publicKey + message)).mod(N)
        val point = add(multiply(G, s), multiply(pk, N.subtract(e)))
        val (x, y) = affine(point) ?: return false
        !y.testBit(0) && x == r
    }.getOrDefault(false)

    /** x of d·Q, the secret two keys share (NIP-04). */
    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val q = liftX(BigInteger(1, publicKey)) ?: error("bad public key")
        val (x, _) = affine(multiply(q, BigInteger(1, privateKey)))!!
        return bytes32(x)
    }

    fun isValidPublicKey(publicKey: ByteArray): Boolean = publicKey.size == 32 && liftX(BigInteger(1, publicKey)) != null

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray())
        return sha256(tagHash + tagHash + data)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun ByteArray.xor(other: ByteArray) = ByteArray(size) { (this[it].toInt() xor other[it].toInt()).toByte() }
}
