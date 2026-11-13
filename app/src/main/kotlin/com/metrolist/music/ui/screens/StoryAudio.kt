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

    /** Plays [songId] (or keeps playing it); null fades the sound out. */
    fun play(songId: String?) {
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
            fadeTo(volume, FADE_IN_MS)
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

    private suspend fun fadeTo(target: Float, durationMs: Long) {
        val p = player ?: return
        val start = p.volume
        val steps = 20
        for (i in 1..steps) {
            p.volume = start + (target - start) * i / steps
            delay(durationMs / steps)
        }
    }

    private companion object {
        const val FADE_IN_MS = 1_600L
        const val FADE_OUT_MS = 700L
    }
}
