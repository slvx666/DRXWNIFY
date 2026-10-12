/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.portrait

/**
 * What counts as having listened to a track (the rule Last.fm scrobbles by): at least half of it,
 * or four minutes of a long one. A track flicked past or skipped after a minute is not a listen —
 * not in the history, not in "recently played", not in the portrait.
 */
object Listening {
    private const val LONG_TRACK_ENOUGH_MS = 4 * 60_000L
    private const val UNKNOWN_LENGTH_ENOUGH_MS = 90_000L

    fun counts(playedMs: Long, durationMs: Long): Boolean = when {
        durationMs <= 0 -> playedMs >= UNKNOWN_LENGTH_ENOUGH_MS
        else -> playedMs >= minOf(durationMs / 2, LONG_TRACK_ENOUGH_MS)
    }
}
