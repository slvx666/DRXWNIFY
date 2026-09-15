package com.metrolist.music.resolver.soulseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class SlskProtocolTest {

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(bytes)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun loginMessageIsFramedLittleEndianWithMd5() {
        val msg = SlskProtocol.login("user", "pass")
        val r = SlskProtocol.Reader(msg)
        assertEquals((msg.size - 4).toLong(), r.u32())
        assertEquals(SlskProtocol.S_LOGIN, r.i32())
        assertEquals("user", r.str())
        assertEquals("pass", r.str())
        assertEquals(160, r.i32())
        // md5("userpass")
        assertEquals("63e780c3f321d13109c71bf81805476e", r.str())
        assertEquals(1, r.i32())
        assertEquals(0, r.remaining)
    }

    @Test
    fun peerInitUsesByteCode() {
        val msg = SlskProtocol.pierceFirewall(0x01020304)
        assertEquals(listOf(5, 0, 0, 0, 0, 4, 3, 2, 1), msg.map { it.toInt() and 0xFF })
    }

    @Test
    fun connectToPeerIpIsDecodedInNetworkOrder() {
        val payload = SlskProtocol.Writer()
            .str("alice").str("F")
            .u32(0x01020304L) // 1.2.3.4
            .u32(2234).u32(777).bool(false)
            .bytes()
        val c = SlskProtocol.parseConnectToPeer(payload)
        assertEquals("alice", c.username)
        assertEquals("F", c.type)
        assertEquals("1.2.3.4", c.ip)
        assertEquals(2234, c.port)
        assertEquals(777, c.token)
    }

    @Test
    fun searchResponseIsInflatedAndParsed() {
        val raw = SlskProtocol.Writer()
            .str("bob").u32(42).u32(2)
            .u8(1).str("@@music\\Deafheaven\\2025 - Lonely People With Power\\03 - Winona.flac").u64(52_000_000).str("")
            .u32(2).u32(1).u32(448).u32(5).u32(24)
            .u8(1).str("@@music\\Deafheaven\\Sunbather\\01 - Dream House.mp3").u64(21_000_000).str("")
            .u32(1).u32(0).u32(320)
            .bool(true).u32(125_000).u32(3)
            .bytes()
        val response = SlskProtocol.parseSearchResponse(deflate(raw))
        assertEquals("bob", response.username)
        assertEquals(42, response.token)
        assertTrue(response.freeSlot)
        assertEquals(3L, response.queueLength)
        assertEquals(2, response.files.size)
        val winona = response.files[0]
        assertEquals("flac", winona.extension)
        assertEquals(448, winona.durationSec)
        assertEquals(24, winona.bitDepth)
        assertEquals(listOf("@@music", "Deafheaven", "2025 - Lonely People With Power", "03 - Winona.flac"), winona.segments)
        assertEquals(320, response.files[1].bitrate)
    }

    @Test
    fun uploadTransferRequestCarriesSize() {
        val payload = SlskProtocol.Writer().u32(1).u32(99).str("a\\b.mp3").u64(1234).bytes()
        val t = SlskProtocol.parseTransferRequest(payload)
        assertEquals(1, t.direction)
        assertEquals(99, t.token)
        assertEquals("a\\b.mp3", t.filename)
        assertEquals(1234L, t.size)
    }

    @Test
    fun loginFailureReasonIsRead() {
        val payload = SlskProtocol.Writer().bool(false).str("INVALIDPASS").bytes()
        assertEquals(SlskProtocol.LoginResult.Failure("INVALIDPASS"), SlskProtocol.parseLogin(payload))
    }
}
