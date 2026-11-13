/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.FallbackIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The sound of the month-in-review story: a piece of the page's track (from its middle part),
 * on a player of its own — the user's queue and what was playing are left alone (the main player
 * is paused while the story plays and goes on after it only if it was playing). Each track fades
 * in and out; [volume] is the slider on top of the story.
 */
class StoryAudio(private val context: Context, private val database: MusicDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var current: String? = null

    var volume: Float = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player?.volume = field
        }

    private fun ensurePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(context).build().also { player = it }

    /** Plays [songId] (or keeps playing it); null stops the sound. */
    fun play(songId: String?) {
        if (songId == current) return
        current = songId
        job?.cancel()
        job = scope.launch {
            fadeTo(0f)
            if (songId == null) {
                player?.pause()
                return@launch
            }
            val uri = withContext(Dispatchers.IO) { streamOf(songId) } ?: run {
                player?.pause()
                return@launch
            }
            val p = ensurePlayer()
            p.volume = 0f
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(songId).build())
            p.prepare()
            // From the second third: past the intro, where a track is most itself.
            val length = withContext(Dispatchers.IO) { database.getSongByIdBlocking(songId)?.song?.duration ?: 0 }
            if (length > 60) p.seekTo(length * 1000L / 3)
            p.play()
            fadeTo(volume)
        }
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        if (current != null) player?.play()
    }

    fun release() {
        job?.cancel()
        scope.launch {
            fadeTo(0f)
            player?.release()
            player = null
        }
    }

    private suspend fun fadeTo(target: Float) {
        val p = player ?: return
        val start = p.volume
        val steps = 12
        for (i in 1..steps) {
            p.volume = start + (target - start) * i / steps
            delay(25)
        }
    }

    /** An address a plain player can stream: a YouTube video's, or the source upload the engine picks. */
    private suspend fun streamOf(songId: String): android.net.Uri? = runCatching {
        if (!FallbackIds.isFallbackId(songId) && !songId.startsWith("local:")) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val quality = com.metrolist.music.constants.AudioQuality.HIGH
            return@runCatching com.metrolist.music.utils.YTPlayerUtils
                .playerResponseForPlayback(videoId = songId, audioQuality = quality, connectivityManager = cm)
                .getOrNull()?.streamUrl?.takeIf { it.isNotBlank() }?.toUri()
        }
        val song = database.getSongById(songId)
        val query = AudioFallbackEngine.queryFor(songId, song) ?: return@runCatching null
        val match = AudioFallbackEngine.resolve(query) ?: return@runCatching null
        val stream = AudioFallbackEngine.provider(match.provider)?.stream(query, match) ?: return@runCatching null
        // VK's HLS goes through the app's own data source; a plain player plays plain addresses only.
        stream.uri.takeIf { it.startsWith("http") && !it.contains(".m3u8") }?.toUri()
    }.onFailure { Timber.w(it, "StoryAudio: no stream for %s", songId) }.getOrNull()
}
