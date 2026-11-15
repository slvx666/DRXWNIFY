/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.portrait

/**
 * What counts as having listened to a track — for the history, "recently played", the profile's
 * portrait and what friends see. The rule Last.fm scrobbles by: half of the track, but never less
 * than [floorMs] (the "add to history after" setting, 30 s by default) and never more than four
 * minutes. A track shorter than the floor counts when nearly all of it was heard; one of unknown
 * length after a minute.
 *
 * 2:30 → 75 s, 3:30 → 1:45, 6:00 → 3:00, 12:00 → 4:00, 0:45 → 30 s, 0:20 → 18 s.
 */
object Listening {
    const val DEFAULT_FLOOR_MS = 30_000L
    private const val CEILING_MS = 4 * 60_000L
    private const val UNKNOWN_LENGTH_MS = 60_000L

    /** Kept equal to the "add to history after" setting by the player. */
    @Volatile
    var floorMs: Long = DEFAULT_FLOOR_MS

    fun neededMs(durationMs: Long): Long = when {
        durationMs <= 0 -> maxOf(UNKNOWN_LENGTH_MS, floorMs)
        durationMs < floorMs -> durationMs * 9 / 10
        else -> (durationMs / 2).coerceIn(floorMs, maxOf(CEILING_MS, floorMs))
    }

    fun counts(playedMs: Long, durationMs: Long): Boolean = playedMs >= neededMs(durationMs)

    /**
     * A listen in the history, looked at again later: the player already saved it by [counts], but
     * the track's length in the database can differ from the one it had then (unknown, or found out
     * later), and the floor may have been changed since. Any listen past the floor stays a listen.
     */
    fun kept(playedMs: Long, durationMs: Long): Boolean = playedMs >= minOf(neededMs(durationMs), floorMs)
}
