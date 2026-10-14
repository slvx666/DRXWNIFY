/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.music.resolver.AudioDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The country YouTube sees the user in. From a Russian address YouTube is throttled and may answer
 * slowly or partly, so when the VPN doesn't cover the app the user is told — quietly, in the
 * download hint and the audio log.
 *
 * YouTube writes the country into the visitor id it hands out (a protobuf: field 6 holds a message
 * whose field 1 is the two-letter code), so one small request tells it. The stored visitor id is
 * not used for this: it carries the country of the day it was created.
 */
object YouTubeRegion {
    private val _country = MutableStateFlow<String?>(null)

    /** Two-letter country code, or null while unknown. */
    val country: StateFlow<String?> = _country.asStateFlow()

    /** Countries where YouTube is throttled or restricted. */
    private val LIMITED = setOf("RU", "BY")

    val limited: Boolean get() = _country.value in LIMITED

    @Volatile
    private var checkedAt = 0L

    /** Asks YouTube again (at most every few minutes; [force] after the network changed). */
    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - checkedAt < RECHECK_MS) return
        checkedAt = now
        val code = YouTube.visitorData().getOrNull()?.let(::countryOf) ?: return
        val previous = _country.value
        _country.value = code
        if (code != previous && code in LIMITED) {
            AudioDiagnostics.warn(
                "YouTube sees this device in $code: without a VPN for Drxwnify YouTube may be slow " +
                    "or find less. Route the app through the VPN.",
            )
        }
    }

    /** The country code inside a YouTube visitor id, or null when it has none. */
    fun countryOf(visitorData: String): String? {
        val raw = runCatching {
            val text = java.net.URLDecoder.decode(visitorData, "UTF-8")
            java.util.Base64.getUrlDecoder().decode(text.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
        }.getOrNull() ?: return null
        // Field 6, length-delimited (0x32), whose first field (0x0A) is a 2-byte string.
        for (i in 0 until raw.size - 5) {
            if (raw[i] == 0x32.toByte() && raw[i + 2] == 0x0A.toByte() && raw[i + 3] == 0x02.toByte()) {
                val a = raw[i + 4].toInt().toChar()
                val b = raw[i + 5].toInt().toChar()
                if (a in 'A'..'Z' && b in 'A'..'Z') return "$a$b"
            }
        }
        return null
    }

    private const val RECHECK_MS = 10 * 60 * 1000L
}
