/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * A whole album at once from VK, when VK has that album. Track by track, each song was searched on
 * its own: VK answers about three calls a second, so on an album most searches came back after
 * YouTube had already been taken, and one album played half from VK (320) and half from YouTube
 * (~160). One album search and one track list give every song of it — they are then stored as the
 * tracks' VK matches, which the quality order picks over YouTube.
 */
object AlbumSources {
    private val done = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()
    private const val RECHECK_MS = 24L * 60 * 60 * 1000

    /** Looks [albumName] by [artist] up on VK and pins its tracks to [tracks]; how many matched. */
    suspend fun prefetchVkAlbum(albumId: String, albumName: String, artist: String, tracks: List<SpotifyTrack>): Int = lock.withLock {
        if (!VkMusic.isReady || tracks.isEmpty() || albumName.isBlank()) return 0
        done[albumId]?.let { if (System.currentTimeMillis() - it < RECHECK_MS) return 0 }
        done[albumId] = System.currentTimeMillis()
        val candidates = VkMusic.searchPlaylists("$artist $albumName").getOrNull().orEmpty()
        val tokens = SpotifyMapper.artistTokens(artist)
        val album = candidates
            .filter { p ->
                normalize(p.title) == normalize(albumName) &&
                    SpotifyMapper.artistPlausiblyMatches(p.title, p.artist, tokens) &&
                    kotlin.math.abs(p.count - tracks.size) <= 2
            }
            .maxByOrNull { if (it.isAlbum) 1 else 0 }
            ?: return 0
        val vkTracks = VkMusic.tracks(album).getOrNull().orEmpty()
        if (vkTracks.isEmpty()) return 0

        var matched = 0
        val used = HashSet<String>()
        for (track in tracks) {
            val wantS = track.durationMs / 1000
            val vk = vkTracks.firstOrNull { m ->
                m.trackId !in used &&
                    SpotifyMapper.titlePlausiblyMatches(m.title, track.name) &&
                    !SpotifyMapper.isForeignRecording(track.name, m.title) &&
                    (m.durationMs == null || wantS <= 0 || kotlin.math.abs(m.durationMs / 1000 - wantS) <= MAX_GAP_S)
            } ?: continue
            used += vk.trackId
            // Same album, same title, same length: as exact as a match gets.
            AudioFallbackEngine.storeMatch(AudioQuery.from(track), vk.copy(confidence = 1.0))
            matched++
        }
        Timber.i("AlbumSources: VK album '%s' (%s) gave %d of %d tracks", album.title, album.key, matched, tracks.size)
        AudioDiagnostics.info("VK album '${album.artist} – ${album.title}': $matched of ${tracks.size} tracks pinned from it")
        matched
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[(\\[].*?[)\\]]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private const val MAX_GAP_S = 5
}
