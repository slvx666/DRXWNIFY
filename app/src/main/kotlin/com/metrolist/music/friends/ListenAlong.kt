/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.SpotifyQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Following a friend's playback: their "now playing" carries the track, the position and when it
 * was taken, so this phone plays the same track at the same moment and keeps up with play/pause,
 * seeks and track changes as their updates arrive. No connection between the phones is needed.
 */
internal object ListenAlong {
    private var serviceRef: WeakReference<MusicService>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentKey: String? = null
    private var job: Job? = null

    fun attach(service: MusicService) {
        serviceRef = WeakReference(service)
    }

    fun reset() {
        currentKey = null
        job?.cancel()
    }

    fun follow(now: FriendsHub.NowPlaying, force: Boolean = false) {
        if (!now.listenAlong) return
        val service = serviceRef?.get() ?: return
        val key = now.track.catalogId ?: now.track.mediaId ?: "${now.track.artist}|${now.track.title}"
        job?.cancel()
        job = scope.launch {
            val player = service.player
            if (force || key != currentKey) {
                currentKey = key
                val queue = withContext(Dispatchers.IO) { FriendPlayback.queueFor(now.track) } ?: return@launch
                service.playQueue(queue, playWhenReady = now.playing)
                // Wait for the track to load, then jump to where the friend is now.
                withTimeoutOrNull(20_000L) {
                    while (player.currentMediaItem == null || player.playbackState != Player.STATE_READY) delay(250)
                }
                player.seekTo(now.positionNow())
                if (now.playing) player.play() else player.pause()
                return@launch
            }
            if (abs(player.currentPosition - now.positionNow()) > MAX_DRIFT_MS) player.seekTo(now.positionNow())
            if (now.playing && !player.isPlaying) player.play()
            if (!now.playing && player.isPlaying) player.pause()
        }
    }

    private const val MAX_DRIFT_MS = 3_000L
}

/** Playing what a friend shared: by its catalog id, else its YouTube id, else by searching. */
object FriendPlayback {
    private val database get() = FriendsHub.databaseOrNull()

    suspend fun queueFor(track: FriendsHub.TrackRef): Queue? {
        val db = database ?: return null
        catalogTrackOf(track)?.let { return SpotifyQueue(initialTrack = it, mapper = SpotifyYouTubeMapper(db)) }
        val videoId = track.mediaId?.takeIf(::isYouTubeId) ?: searchYouTube(track) ?: return null
        return YouTubeQueue(WatchEndpoint(videoId = videoId))
    }

    /** A friend's playlist (or history) from [start] on, resolved a few tracks at a time. */
    fun listQueue(tracks: List<FriendsHub.TrackRef>, start: Int): Queue = RefsQueue(tracks, start)

    private suspend fun catalogTrackOf(track: FriendsHub.TrackRef): SpotifyTrack? {
        track.catalogId?.let { id -> Catalog.getTrack(id).getOrNull()?.let { return it } }
        val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val results = runCatching { Catalog.search(query, listOf("track"), 8).getOrNull()?.tracks?.items }.getOrNull().orEmpty()
        if (results.isEmpty()) return null
        val match = SpotifyMapper.selectBestMatch(
            spotifyTitle = track.title,
            spotifyPrimaryArtist = track.artist.substringBefore(',').trim(),
            spotifyArtistsAll = track.artist,
            spotifyDurationMs = track.durationMs.toInt(),
            candidates = results.map { SpotifyMapper.Candidate(it.id, it.name, it.artists.joinToString(", ") { a -> a.name }, it.durationMs / 1000) },
        ) as? SpotifyMapper.MatchResult.Matched ?: return null
        return results.firstOrNull { it.id == match.id }
    }

    private suspend fun searchYouTube(track: FriendsHub.TrackRef): String? =
        YouTube.search("${track.artist} ${track.title}", YouTube.SearchFilter.FILTER_SONG, incognito = true)
            .getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.id

    private suspend fun itemFor(track: FriendsHub.TrackRef): MediaItem? {
        val db = database ?: return null
        catalogTrackOf(track)?.let { return SpotifyYouTubeMapper(db).quickMetadata(it).toMediaItem() }
        val videoId = track.mediaId?.takeIf(::isYouTubeId) ?: return null
        return MediaMetadata(
            id = videoId,
            title = track.title,
            artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
            duration = (track.durationMs / 1000).toInt(),
            thumbnailUrl = track.cover,
        ).toMediaItem()
    }

    private fun isYouTubeId(id: String) = id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * A friend's [track] as a catalog track built from what was shared (no lookup): it plays,
     * downloads and goes into playlists like any other, its audio found when it is needed.
     */
    fun asCatalogTrack(track: FriendsHub.TrackRef): SpotifyTrack? {
        val id = track.catalogId
            ?.takeIf { it.isNotBlank() && !com.metrolist.music.resolver.SourceSearch.isSourceTrack(com.metrolist.music.resolver.FallbackIds.of(it)) }
            ?: return null
        return SpotifyTrack(
            id = id,
            name = track.title,
            artists = track.artist.split(", ").filter { it.isNotBlank() }
                .map { com.metrolist.spotify.models.SpotifySimpleArtist(name = it) },
            album = track.cover?.let {
                com.metrolist.spotify.models.SpotifySimpleAlbum(images = listOf(com.metrolist.spotify.models.SpotifyImage(url = it)))
            },
            durationMs = track.durationMs.toInt(),
        )
    }

    /** What the track menu (long press) shows for a friend's [track]: its metadata and catalog track, if any. */
    suspend fun menuTarget(track: FriendsHub.TrackRef): Pair<MediaMetadata, SpotifyTrack?>? {
        // The real catalog track (with its artists' ids, for "go to artist" and the like); what the
        // friend shared only has names. A short wait, else the shared version.
        val db = database
        val full = withTimeoutOrNull(MENU_LOOKUP_MS) { runCatching { catalogTrackOf(track) }.getOrNull() }
        if (full != null && db != null) return SpotifyYouTubeMapper(db).quickMetadata(full) to full
        val metadata = instantItem(track) ?: return null
        return metadata to com.metrolist.music.playback.SpotifyMetadataRegistry.get(metadata.id)
    }

    private val enrichScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A track playing from what a friend shared gets its real catalog entry behind the scenes, so
     * the player's menu has the artists to go to (shared tracks carry names only).
     */
    private fun enrichLater(mediaId: String, track: FriendsHub.TrackRef) {
        if (track.catalogId == null) return
        enrichScope.launch {
            val full = runCatching { catalogTrackOf(track) }.getOrNull() ?: return@launch
            if (full.artists.any { !it.id.isNullOrBlank() }) {
                com.metrolist.music.playback.SpotifyMetadataRegistry.register(mediaId, full)
            }
        }
    }

    /**
     * Something the player can switch to at once for [track], from what the friend shared: the
     * catalog id (its audio is found as it starts) or the YouTube video. No lookup, so a tap shows
     * the track playing right away instead of after the catalog search.
     */
    private fun instantItem(track: FriendsHub.TrackRef): MediaMetadata? {
        val db = database ?: return null
        asCatalogTrack(track)?.let { catalogTrack ->
            return SpotifyYouTubeMapper(db).quickMetadata(catalogTrack).also { enrichLater(it.id, track) }
        }
        val videoId = track.mediaId?.takeIf(::isYouTubeId) ?: return null
        return MediaMetadata(
            id = videoId,
            title = track.title,
            artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
            duration = (track.durationMs / 1000).toInt(),
            thumbnailUrl = track.cover,
        )
    }

    private class RefsQueue(private val tracks: List<FriendsHub.TrackRef>, private val start: Int) : Queue {
        // The tapped track plays at once; the ones after it are looked up behind it.
        override val preloadItem: MediaMetadata? = tracks.getOrNull(start)?.let { runCatching { instantItem(it) }.getOrNull() }
        private var offset = start

        override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
            val first = preloadItem
            if (first == null) {
                Queue.Status(title = null, items = next(BATCH_FIRST), mediaItemIndex = 0)
            } else {
                offset = start + 1
                // Index 0 is the item already playing (the player keeps it and adds the rest around it).
                Queue.Status(title = null, items = listOf(first.toMediaItem()) + next(BATCH_FIRST), mediaItemIndex = 0)
            }
        }

        override fun hasNextPage(): Boolean = offset < tracks.size

        override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) { next(BATCH) }

        private suspend fun next(count: Int): List<MediaItem> {
            val out = mutableListOf<MediaItem>()
            while (offset < tracks.size && out.size < count) {
                // What the friend shared plays as is (instantly); a lookup only for what it can't describe.
                runCatching { instantItem(tracks[offset])?.toMediaItem() ?: itemFor(tracks[offset]) }.getOrNull()?.let(out::add)
                offset++
            }
            return out
        }
    }

    private const val MENU_LOOKUP_MS = 4_000L
    private const val BATCH_FIRST = 3
    private const val BATCH = 8
}
