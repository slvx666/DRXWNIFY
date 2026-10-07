package com.metrolist.music.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BIP-340 reference vectors: relays drop events whose signature is off by a single bit. */
class Secp256k1Test {
    private fun h(s: String) = s.hexToBytes()

    @Test
    fun `vector 0`() {
        val sk = h("0000000000000000000000000000000000000000000000000000000000000003")
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Secp256k1.publicKey(sk).toHex())
        val sig = Secp256k1.sign(h("0000000000000000000000000000000000000000000000000000000000000000"), sk, ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            sig.toHex(),
        )
    }

    @Test
    fun `vector 1 and verification`() {
        val sk = h("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
        val pk = Secp256k1.publicKey(sk)
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", pk.toHex())
        val msg = h("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
        val sig = Secp256k1.sign(msg, sk, h("0000000000000000000000000000000000000000000000000000000000000001"))
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            sig.toHex(),
        )
        assertTrue(Secp256k1.verify(msg, pk, sig))
        val tampered = sig.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }
        assertFalse(Secp256k1.verify(msg, pk, tampered))
    }

    @Test
    fun `shared secret is symmetric and npub round trips`() {
        val a = Secp256k1.newPrivateKey()
        val b = Secp256k1.newPrivateKey()
        val pa = Secp256k1.publicKey(a)
        val pb = Secp256k1.publicKey(b)
        assertEquals(Secp256k1.sharedSecret(a, pb).toHex(), Secp256k1.sharedSecret(b, pa).toHex())
        val npub = Nostr.bech32Encode("npub", pa)
        val (hrp, bytes) = Nostr.bech32Decode(npub)!!
        assertEquals("npub", hrp)
        assertEquals(pa.toHex(), bytes.toHex())
        // Known NIP-19 example.
        assertEquals(
            "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6",
            Nostr.bech32Encode("npub", h("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")),
        )
    }

    @Test
    fun `signed event verifies`() {
        val sk = Secp256k1.newPrivateKey()
        val event = Nostr.sign(sk, 30078, listOf(listOf("d", "drxw:now"), listOf("t", "drxwnify")), "{\"a\":\"Тест \\\"q\\\"\\n\"}")
        assertTrue(event.isValid())
        assertFalse(event.copy(content = event.content + " ").isValid())
    }
}
