package com.metrolist.music.friends

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in (FRIENDS_LIVE=1): a real relay must accept an event signed the way the app signs. */
class RelayLiveCheck {
    @Test
    fun relayAcceptsOurEvent() {
        assumeTrue(System.getenv("FRIENDS_LIVE") == "1")
        val sk = Secp256k1.newPrivateKey()
        val event = Nostr.sign(sk, 30078, listOf(listOf("d", "drxw:test"), listOf("t", "drxwnify")), "{\"hello\":\"мир\"}")
        val results = mutableListOf<String>()
        RelayPool.RELAYS.forEach { url ->
            val latch = CountDownLatch(1)
            OkHttpClient().newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONArray().put("EVENT").put(event.toJson()).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = JSONArray(text)
                    if (m.optString(0) == "OK") {
                        results += "$url ok=${m.optBoolean(2)} ${m.optString(3)}"
                        latch.countDown()
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    results += "$url failed: ${t.message}"
                    latch.countDown()
                }
            })
            latch.await(20, TimeUnit.SECONDS)
        }
        println(results.joinToString("\n"))
        assertTrue(results.joinToString(), results.any { "ok=true" in it })
    }
}
