/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sound of the month-in-review story: a piece of the page's track, from a moment in its
 * middle, on a player of its own made by the music service ([newPlayer]) — the same sources as the
 * main player (VK, YouTube, any catalog track, the cache), while the queue is left alone. Each
 * track fades in and out; [volume] is the slider on top of the story. The same track asked for
 * again (the next page about it) just goes on.
 */
class StoryAudio(private val database: MusicDatabase, private val newPlayer: () -> ExoPlayer?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var current: String? = null

    var volume: Float = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player?.volume = field
        }

    private fun ensurePlayer(): ExoPlayer? = player ?: newPlayer()?.also { player = it }

    /**
     * Plays [songId] (or keeps playing it); null fades the sound out. The new track rises from
     * silence to full volume by [fullAt] (wall clock): the moment its title shows on the page.
     */
    fun play(songId: String?, fullAt: Long = System.currentTimeMillis() + FADE_IN_MS) {
        if (songId == current) return
        current = songId
        job?.cancel()
        job = scope.launch {
            fadeTo(0f, FADE_OUT_MS)
            if (songId == null) {
                player?.pause()
                return@launch
            }
            val song = withContext(Dispatchers.IO) { database.getSongById(songId) } ?: return@launch
            val p = ensurePlayer() ?: return@launch
            p.volume = 0f
            p.setMediaItem(song.toMediaItem())
            p.prepare()
            // A moment from its second third: past the intro, where a track is most itself.
            val length = song.song.duration
            if (length > 60) p.seekTo(length * 1000L / 3)
            p.play()
            // The rise starts once the sound is actually there, and still ends on time.
            val waitUntil = System.currentTimeMillis() + 6_000
            while (!p.isPlaying && System.currentTimeMillis() < waitUntil) delay(50)
            fadeTo(volume, (fullAt - System.currentTimeMillis()).coerceAtLeast(MIN_FADE_IN_MS))
        }
    }

    fun release() {
        job?.cancel()
        scope.launch {
            fadeTo(0f, FADE_OUT_MS)
            player?.release()
            player = null
        }
    }

    /** A smooth fade: slow at first when rising (the ear hears loudness that way), even when falling. */
    private suspend fun fadeTo(target: Float, durationMs: Long) {
        val p = player ?: return
        val start = p.volume
        val steps = 40
        val rising = target > start
        for (i in 1..steps) {
            val x = i.toFloat() / steps
            val curve = if (rising) x * x * (3f - 2f * x) * x else x
            p.volume = start + (target - start) * curve
            delay(durationMs / steps)
        }
    }

    private companion object {
        const val FADE_IN_MS = 3_000L
        const val MIN_FADE_IN_MS = 600L
        const val FADE_OUT_MS = 700L
    }
}
