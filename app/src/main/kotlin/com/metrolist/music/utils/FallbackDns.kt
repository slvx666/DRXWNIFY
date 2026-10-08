/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The phone's own DNS first; when it cannot resolve a name ("Unable to resolve host oauth.vk.com" —
 * a flaky mobile network, a VPN or a provider that breaks some names), the name is asked over HTTPS
 * from Cloudflare and Google by their IP addresses, so no DNS is needed to reach them.
 */
object FallbackDns : Dns {
    private val resolvers = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build() // only ever IP literals here, which need no DNS
    }

    private class Entry(val addresses: List<InetAddress>, val until: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }
        system.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        cache[hostname]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.addresses }
        for (template in resolvers) {
            val found = runCatching { overHttps(template.format(hostname)) }.getOrNull().orEmpty()
            if (found.isNotEmpty()) {
                cache[hostname] = Entry(found, System.currentTimeMillis() + TTL_MS)
                return found
            }
        }
        throw system.exceptionOrNull() as? UnknownHostException ?: UnknownHostException(hostname)
    }

    private fun overHttps(url: String): List<InetAddress> {
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val answers = JSONObject(response.body?.string().orEmpty()).optJSONArray("Answer") ?: return emptyList()
            return (0 until answers.length()).mapNotNull { i ->
                val answer = answers.optJSONObject(i) ?: return@mapNotNull null
                if (answer.optInt("type") != 1) return@mapNotNull null
                runCatching { InetAddress.getByName(answer.optString("data")) }.getOrNull()
            }
        }
    }

    private const val TTL_MS = 10 * 60_000L
}
