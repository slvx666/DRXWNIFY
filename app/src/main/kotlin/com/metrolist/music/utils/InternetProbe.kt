/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Whether the internet actually answers, as opposed to Android merely reporting a network. In the
 * metro the phone keeps a mobile network that carries nothing, so a failed track lookup there says
 * nothing about the track: every source "failed" only because nothing got through.
 *
 * Several unrelated hosts are asked at once, so one blocked service (a region, a carrier, a VPN)
 * doesn't read as "offline".
 */
object InternetProbe {
    private val PROBES = listOf(
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://ya.ru/",
    )
    private const val TIMEOUT_S = 4L
    private const val REMEMBER_MS = 5_000L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @Volatile
    private var last: Pair<Long, Boolean>? = null

    suspend fun isOnline(): Boolean {
        last?.let { (at, online) -> if (System.currentTimeMillis() - at < REMEMBER_MS) return online }
        val online = withContext(Dispatchers.IO) {
            coroutineScope {
                PROBES.map { url ->
                    async {
                        runCatching {
                            client.newCall(Request.Builder().url(url).head().build()).execute().use { true }
                        }.getOrDefault(false)
                    }
                }.awaitAll().any { it }
            }
        }
        last = System.currentTimeMillis() to online
        return online
    }
}
