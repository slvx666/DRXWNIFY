/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.datasource

import com.metrolist.music.resolver.AudioDiagnostics
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A stream's server can't be reached at all (not a slow or missing track): no point retrying it. */
class HostUnreachableException(val host: String, cause: Throwable?) : IOException("$host unreachable", cause)

/**
 * Audio servers that can't be reached from this network. VK spreads tracks over many servers and
 * some of them may be cut off (a VPN or a provider drops the connection at the handshake): the
 * player used to try such a server again and again — 16 handshakes of 5 s each, 83 s of silence —
 * before the track played from YouTube. A server that fails is remembered for a while, and a
 * server not seen yet is checked in a few seconds before a track is started from it.
 */
object CdnHosts {
    private const val DOWN_MS = 10 * 60_000L
    private const val UP_MS = 30 * 60_000L

    private val downUntil = ConcurrentHashMap<String, Long>()
    private val upUntil = ConcurrentHashMap<String, Long>()

    /**
     * Networks (first three bytes of the address) of servers found out of reach: VK's "cs9-…"
     * servers sit side by side, and when one is cut off its neighbours are too — each used to cost
     * its own wait of several seconds before it was known.
     */
    private val downNets = ConcurrentHashMap<String, Long>()

    private fun netsOf(host: String): List<String> =
        runCatching { com.metrolist.music.utils.FallbackDns.lookup(host) }.getOrDefault(emptyList())
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
            .distinct()

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .dns(com.metrolist.music.utils.FallbackDns)
            .build()
    }

    fun hostOf(url: String?): String? = url?.toHttpUrlOrNull()?.host

    fun isDown(host: String?): Boolean = host != null && (downUntil[host] ?: 0L) > System.currentTimeMillis()

    /** [isDown], or on a network found out of reach (a name lookup: off the main thread). */
    fun likelyDown(host: String?): Boolean {
        if (host == null) return false
        if (isDown(host)) return true
        val now = System.currentTimeMillis()
        if (downNets.values.none { it > now }) return false
        val nets = netsOf(host)
        return nets.isNotEmpty() && nets.all { (downNets[it] ?: 0L) > now }
    }

    fun markDown(host: String, reason: String) {
        val firstTime = !isDown(host)
        val until = System.currentTimeMillis() + DOWN_MS
        downUntil[host] = until
        upUntil.remove(host)
        netsOf(host).forEach { downNets[it] = until }
        if (firstTime) AudioDiagnostics.warn("$host can't be reached ($reason) — not used for ${DOWN_MS / 60_000} min")
    }

    /** Another network (or the internet back after an outage): every server gets a new chance. */
    fun forgetAll() {
        downUntil.clear()
        upUntil.clear()
        downNets.clear()
    }

    private fun markUp(host: String) {
        upUntil[host] = System.currentTimeMillis() + UP_MS
        downUntil.remove(host)
    }

    /** Whether [error] means the server itself is out of reach (rather than a slow or broken answer). */
    fun isUnreachable(error: Throwable?): Boolean {
        var e = error
        while (e != null) {
            if (e is HostUnreachableException || e is javax.net.ssl.SSLHandshakeException ||
                e is java.net.ConnectException || e is java.net.NoRouteToHostException ||
                e is java.net.UnknownHostException
            ) {
                return true
            }
            e = e.cause
        }
        return false
    }

    enum class Check {
        OK,

        /** The server can't be reached from this network. */
        UNREACHABLE,

        /** The server answers, but this stream is gone (VK hands out addresses of deleted uploads). */
        GONE,
    }

    /**
     * Whether a stream at [url] can be fetched, asked of the stream itself: an HLS playlist is a few
     * KB, anything else gets a HEAD. A server out of reach is found out in a few seconds (and its
     * neighbours with it), and an address that answers 404 — VK still gives out links to uploads
     * it deleted, and three tracks of an album failed to download one after another — is not
     * played or downloaded at all. Blocking: call it off the main thread.
     */
    fun check(url: String): Check {
        val host = hostOf(url) ?: return Check.OK
        if (isDown(host)) return Check.UNREACHABLE
        val nets = netsOf(host)
        if (nets.isNotEmpty() && nets.all { (downNets[it] ?: 0L) > System.currentTimeMillis() }) {
            markDown(host, "its network ${nets.first()}.x is out of reach")
            return Check.UNREACHABLE
        }
        val started = System.currentTimeMillis()
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").apply {
            if (!url.contains(".m3u8")) head()
        }.build()
        return try {
            probeClient.newCall(request).execute().use { response ->
                markUp(host)
                when (response.code) {
                    404, 403, 410 -> Check.GONE
                    else -> Check.OK
                }
            }
        } catch (e: IOException) {
            // A handshake that never completes ends in the call timeout, not in an SSL error.
            markDown(host, "${e.javaClass.simpleName} after ${System.currentTimeMillis() - started} ms")
            Check.UNREACHABLE
        }
    }
}
