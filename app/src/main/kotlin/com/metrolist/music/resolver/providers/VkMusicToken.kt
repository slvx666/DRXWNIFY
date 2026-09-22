/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLSocketFactory

/**
 * Makes a VK token that VK's music API actually answers.
 *
 * A token from the ordinary login is only half of what the music clients hold: VK hands out music
 * only to a token that was afterwards exchanged through `auth.refreshToken` together with a *receipt*
 * — the push-registration Google issues to the client. Without it every audio method comes back as
 * "unknown method" (error 3), which is exactly what this app's own login produced.
 *
 * So the same three steps the music clients take on first run happen here: register a device with
 * Google, ask Google for the push receipt, then exchange the token at VK. Nothing is stored: only the
 * exchanged token comes back.
 */
object VkMusicToken {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /** The exchanged token for [token], or a failure describing the step that did not work. */
    suspend fun refresh(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "no token" }
            val device = checkIn()
            // The clients connect to the push server right after registering; skipping it can make
            // the receipt request fail, and failing at it does not (the reference does the same).
            runCatching { greetPushServer(device) }
                .onFailure { Timber.tag(TAG).d(it, "push greeting skipped") }
            val receipt = pushReceipt(device)
            exchange(token, receipt)
        }.onFailure { Timber.tag(TAG).w(it, "could not exchange the VK token") }
    }

    // ── VK ───────────────────────────────────────────────────────────────────────────────────────

    private fun exchange(token: String, receipt: String): String {
        val url = "https://api.vk.com/method/auth.refreshToken".toHttpUrl().newBuilder()
            .addQueryParameter("access_token", token)
            .addQueryParameter("receipt", receipt)
            .addQueryParameter("v", VkAudioProvider.API_VERSION)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VkAudioProvider.KATE_USER_AGENT)
            .build()
        val body = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { error ->
            error("VK refused to exchange the token: ${error.optInt("error_code")} ${error.optString("error_msg")}")
        }
        val exchanged = json.optJSONObject("response")?.optString("token").orEmpty()
        check(exchanged.isNotBlank()) { "VK returned no token" }
        check(exchanged != token) { "VK returned the same token" }
        return exchanged
    }

    // ── Google ───────────────────────────────────────────────────────────────────────────────────

    /** Identity Google hands back for a registered device. */
    private data class Device(val id: String, val token: String, val idBytes: ByteArray)

    private fun checkIn(): Device {
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(checkInRequest()) }
        }.toByteArray()
        val request = Request.Builder()
            .url("https://android.clients.google.com/checkin")
            .header("User-Agent", GCM_USER_AGENT)
            .header("Content-Encoding", "gzip")
            .post(gzipped.toRequestBody(PROTOBUF))
            .build()
        val response = http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "device registration HTTP ${r.code}" }
            r.body?.bytes() ?: error("device registration returned nothing")
        }
        return readDevice(response)
    }

    /** Connects to the push server once, the way a freshly registered device does. */
    private fun greetPushServer(device: Device) {
        val payload = pushLoginRequest(device)
        (SSLSocketFactory.getDefault().createSocket(PUSH_HOST, PUSH_PORT)).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
            // Two bytes are enough to know the server accepted the login.
            socket.getInputStream().read()
            socket.getInputStream().read()
        }
    }

    private fun pushReceipt(device: Device): String {
        val form = FormBody.Builder().apply {
            RECEIPT_FIELDS.forEach { (key, value) -> add(key, value) }
            add("X-appid", randomAppId())
            add("device", device.id)
        }.build()
        val request = Request.Builder()
            .url("https://android.clients.google.com/c2dm/register3")
            .header("User-Agent", GCM_USER_AGENT)
            .header("Authorization", "AidLogin ${device.id}:${device.token}")
            .post(form)
            .build()
        val body = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        val receipt = body.substringAfter(RECEIPT_MARKER, missingDelimiterValue = "").trim()
        check(receipt.isNotBlank()) { "no receipt in the answer: ${body.take(120)}" }
        check(receipt != "PHONE_REGISTRATION_ERROR") { "the receipt was refused" }
        return receipt
    }

    private fun randomAppId(): String =
        (1..11).map { APP_ID_ALPHABET.random() }.joinToString("")

    // ── The two Google messages ──────────────────────────────────────────────────────────────────

    /**
     * The registration message, field for field as the reference implementation sends it: a stock
     * emulator image. The values are deliberately identical — Google answers this shape.
     */
    private fun checkInRequest(): ByteArray {
        val build = concat(
            string(1, "generic_x86/google_sdk_x86/generic_x86:4.4.2/KK/3079183:eng/test-keys"),
            string(2, "ranchu"),
            string(3, "generic_x86"),
            string(5, "unknown"),
            string(6, "android-google"),
            rawVarint(8, byteArrayOf(0x85.toByte(), 0xb5.toByte(), 0x86.toByte(), 0x06)),
            string(9, "generic_x86"),
            varint(10, 19),
            string(11, "Android SDK built for x86"),
            string(12, "unknown"),
            string(13, "google_sdk_x86"),
            varint(14, 0),
        )
        val checkin = concat(
            message(1, build),
            varint(2, 0),
            string(6, "310260"),
            string(7, "310260"),
            string(8, "mobile:LTE:"),
            varint(9, 0),
        )
        return concat(
            varint(2, 0),
            string(3, "1-929a0dca0eee55513280171a8585da7dcd3700f8"),
            message(4, checkin),
            string(6, "en_US"),
            rawVarint(
                7,
                byteArrayOf(
                    0xf0.toByte(), 0xb4.toByte(), 0xdf.toByte(), 0xa6.toByte(), 0xb9.toByte(),
                    0x9a.toByte(), 0xb8.toByte(), 0x83.toByte(), 0x8e.toByte(), 0x01,
                ),
            ),
            string(10, "358240051111110"),
            string(11, ""),
            string(12, "America/New_York"),
            varint(14, 3),
            string(15, "71Q6Rn2DDZl1zPDVaaeEHItd+Yg="),
            varint(20, 0),
            varint(22, 0),
        )
    }

    /** The push server's login message for a device that was just registered. */
    private fun pushLoginRequest(device: Device): ByteArray {
        val deviceId = device.id.toByteArray(Charsets.US_ASCII)
        val body = concat(
            string(1, "android-19"),
            string(2, "mcs.android.com"),
            bytes(3, deviceId),
            bytes(4, deviceId),
            string(5, device.token),
            string(6, "android-" + device.idBytes.joinToString("") { "%02x".format(it) }),
            message(8, concat(string(1, "new_vc"), string(2, "1"))),
            varint(12, 0),
            varint(14, 1),
            varint(16, 2),
            varint(17, 1),
        )
        return byteArrayOf(0x29, 0x02) + writeVarint(body.size.toLong()) + body
    }

    /** Pulls the device id (field 7) and its token (field 8) out of the registration answer. */
    private fun readDevice(data: ByteArray): Device {
        var i = 0
        var id: String? = null
        var idBytes: ByteArray? = null
        var token: String? = null

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                check(i < data.size) { "truncated answer" }
                val b = data[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readFixed64(): Pair<String, ByteArray> {
            check(i + 8 <= data.size) { "truncated answer" }
            val raw = data.copyOfRange(i, i + 8)
            i += 8
            var value = 0L
            for (index in 7 downTo 0) value = (value shl 8) or (raw[index].toLong() and 0xFF)
            return java.lang.Long.toUnsignedString(value) to raw
        }

        while (i < data.size) {
            val tag = readVarint()
            when ((tag and 0x7).toInt()) {
                0 -> readVarint()
                1 -> {
                    val (value, raw) = readFixed64()
                    when ((tag shr 3).toInt()) {
                        7 -> { id = value; idBytes = raw }
                        8 -> token = value
                    }
                    if (id != null && token != null) {
                        return Device(id, token, idBytes ?: ByteArray(8))
                    }
                }
                2 -> {
                    val length = readVarint().toInt()
                    check(i + length <= data.size) { "truncated answer" }
                    i += length
                }
                5 -> i += 4
                else -> error("unexpected field in the answer")
            }
        }
        error("the answer carried no device id")
    }

    // ── Tiny protobuf writer ─────────────────────────────────────────────────────────────────────

    private fun writeVarint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        do {
            val part = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            out.write(if (remaining != 0L) part or 0x80 else part)
        } while (remaining != 0L)
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = writeVarint(((field shl 3) or wire).toLong())

    private fun varint(field: Int, value: Long) = tag(field, 0) + writeVarint(value)

    private fun rawVarint(field: Int, encoded: ByteArray) = tag(field, 0) + encoded

    private fun bytes(field: Int, payload: ByteArray) =
        tag(field, 2) + writeVarint(payload.size.toLong()) + payload

    private fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    private fun message(field: Int, payload: ByteArray) = bytes(field, payload)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private const val TAG = "VkMusicToken"
    private const val PUSH_HOST = "mtalk.google.com"
    private const val PUSH_PORT = 5228
    private const val GCM_USER_AGENT = "Android-GCM/1.5 (generic_x86 KK)"
    private const val RECEIPT_MARKER = "|ID|1|:"
    private const val APP_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-"
    private val PROTOBUF = "application/x-protobuffer".toMediaType()

    /** The client the receipt is issued for; VK only exchanges tokens for a music client's receipt. */
    private val RECEIPT_FIELDS = listOf(
        "X-scope" to "GCM",
        "X-osv" to "23",
        "X-subtype" to "54740537194",
        "X-app_ver" to "460",
        "X-kid" to "|ID|1|",
        "X-gmsv" to "200313005",
        "X-cliv" to "iid-12211000",
        "X-app_ver_name" to "56 lite",
        "X-X-kid" to "|ID|1|",
        "X-subscription" to "54740537194",
        "X-X-subscription" to "54740537194",
        "X-X-subtype" to "54740537194",
        "app" to "com.perm.kate_new_6",
        "sender" to "54740537194",
        "cert" to "966882ba564c2619d55d0a9afd4327a38c327456",
        "app_ver" to "460",
        "info" to "U_ojcf1ahbQaUO6eTSP7b7WomakK_hY",
        "gcm_ver" to "200313005",
        "plat" to "0",
        "target_ver" to "28",
    )
}
