/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A Nostr event (NIP-01). */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", JSONArray(tags.map { JSONArray(it) }))
        put("content", content)
        put("sig", sig)
    }

    /** The id matches the content and the signature the author. */
    fun isValid(): Boolean = runCatching {
        Nostr.idOf(pubkey, createdAt, kind, tags, content) == id &&
            Secp256k1.verify(id.hexToBytes(), pubkey.hexToBytes(), sig.hexToBytes())
    }.getOrDefault(false)

    companion object {
        fun fromJson(o: JSONObject): NostrEvent? = runCatching {
            val tagsJson = o.getJSONArray("tags")
            NostrEvent(
                id = o.getString("id"),
                pubkey = o.getString("pubkey"),
                createdAt = o.getLong("created_at"),
                kind = o.getInt("kind"),
                tags = (0 until tagsJson.length()).map { i ->
                    val t = tagsJson.getJSONArray(i)
                    (0 until t.length()).map { t.getString(it) }
                },
                content = o.getString("content"),
                sig = o.getString("sig"),
            )
        }.getOrNull()
    }
}

internal object Nostr {
    /** NIP-78: arbitrary app data, replaceable per (author, "d" tag). */
    const val KIND_APP_DATA = 30078

    /** Profile name (NIP-01 kind 0), published only when the user lets others find them. */
    const val KIND_METADATA = 0

    fun sign(privateKey: ByteArray, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val pubkey = Secp256k1.publicKey(privateKey).toHex()
        val createdAt = System.currentTimeMillis() / 1000
        val id = idOf(pubkey, createdAt, kind, tags, content)
        val sig = Secp256k1.sign(id.hexToBytes(), privateKey).toHex()
        return NostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
    }

    fun idOf(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val serialized = buildString {
            append("[0,")
            appendJsonString(pubkey)
            append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, tag ->
                if (i > 0) append(',')
                append('[')
                tag.forEachIndexed { j, value ->
                    if (j > 0) append(',')
                    appendJsonString(value)
                }
                append(']')
            }
            append("],")
            appendJsonString(content)
            append(']')
        }
        return Secp256k1.sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** NIP-01 escaping: only these characters are escaped, everything else is written as is. */
    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    // ── NIP-04 encryption (AES-256-CBC with the ECDH secret) ──────────────────────────────────────

    private val random = SecureRandom()

    fun encrypt(privateKey: ByteArray, toPubkey: String, plain: String): String {
        val key = Secp256k1.sharedSecret(privateKey, toPubkey.hexToBytes())
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP) + "?iv=" +
            android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
    }

    fun decrypt(privateKey: ByteArray, fromPubkey: String, payload: String): String? = runCatching {
        val (data, ivPart) = payload.split("?iv=", limit = 2).let { it[0] to it[1] }
        val key = Secp256k1.sharedSecret(privateKey, fromPubkey.hexToBytes())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            IvParameterSpec(android.util.Base64.decode(ivPart, android.util.Base64.DEFAULT)),
        )
        String(cipher.doFinal(android.util.Base64.decode(data, android.util.Base64.DEFAULT)), Charsets.UTF_8)
    }.getOrNull()

    // ── Bech32 (npub / nsec, NIP-19) ─────────────────────────────────────────────────────────────

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            if (value ushr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc ushr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and maxv
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    fun bech32Encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, true)!!
        val checksumInput = hrpExpand(hrp) + values + IntArray(6)
        val mod = polymod(checksumInput) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (values + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** (hrp, bytes) or null when [text] isn't valid bech32. */
    fun bech32Decode(text: String): Pair<String, ByteArray>? {
        val s = text.trim().lowercase()
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length) return null
        val hrp = s.substring(0, pos)
        val values = s.substring(pos + 1).map { CHARSET.indexOf(it).takeIf { i -> i >= 0 } ?: return null }.toIntArray()
        if (polymod(hrpExpand(hrp) + values) != 1) return null
        val data = values.copyOfRange(0, values.size - 6)
        val bytes = ByteArray(data.size) { data[it].toByte() }
        val eight = convertBits(bytes, 5, 8, false) ?: return null
        return hrp to ByteArray(eight.size) { eight[it].toByte() }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
