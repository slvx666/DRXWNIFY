/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Experimental: searching the audio sources THEMSELVES, with no catalog track behind the result.
 *
 * Everywhere else in the app a track is described by Spotify / Yandex Music and a provider only
 * supplies its audio. Artists that exist on none of those services (small noise/underground acts)
 * are therefore invisible — which is what this mode is for: the query goes straight to VK,
 * SoundCloud, Bandcamp and Audius and whatever they return is playable as-is.
 *
 * The price is that such a track has no catalog identity: no likes, no artist/album pages, no radio.
 * It is given a synthetic catalog id `src:<PROVIDER>:<trackId>` (so its media id is a normal
 * `mfb:` fallback id) and the provider it came from is pinned as a manual source choice, which is
 * exactly how "choose the audio source" already works — playback and downloads need no special case.
 */
object SourceSearch {
    /** Synthetic catalog id prefix — the marker that a track came from this mode. */
    const val CATALOG_PREFIX = "src:"

    /**
     * Providers that can answer a free-text search. YouTube is missing on purpose (it has its own,
     * much better search mode) and so is Soulseek, whose searches take tens of seconds.
     */
    val PROVIDERS = listOf(
        AudioProviderId.VK,
        AudioProviderId.SOUNDCLOUD,
        AudioProviderId.BANDCAMP,
        AudioProviderId.AUDIUS,
    )

    fun catalogIdOf(match: ProviderMatch): String = "$CATALOG_PREFIX${match.provider.name}:${match.trackId}"

    fun mediaIdOf(match: ProviderMatch): String = FallbackIds.of(catalogIdOf(match))

    /** True for a track found by this mode (the one place the restricted actions are decided). */
    fun isSourceTrack(mediaId: String?): Boolean =
        mediaId != null && FallbackIds.catalogIdOf(mediaId)?.startsWith(CATALOG_PREFIX) == true

    /** Which source a track came from, for the badge under its name. */
    fun providerOf(mediaId: String?): AudioProviderId? {
        val catalogId = mediaId?.let { FallbackIds.catalogIdOf(it) } ?: return null
        if (!catalogId.startsWith(CATALOG_PREFIX)) return null
        val name = catalogId.removePrefix(CATALOG_PREFIX).substringBefore(':')
        return AudioProviderId.entries.firstOrNull { it.name == name }
    }

    /** Why a source has no results of its own. */
    enum class Status { READY, DISABLED, NEEDS_ACCOUNT }

    fun statusOf(id: AudioProviderId): Status = when {
        !ResolverPreferences.isEnabled(id) -> Status.DISABLED
        AudioFallbackEngine.provider(id)?.isReady() != true -> Status.NEEDS_ACCOUNT
        else -> Status.READY
    }

    /**
     * Results for [text]. With [only] set just that source answers (and it answers with more), else
     * every usable source is asked in parallel and the answers are merged.
     *
     * Merging: the same song is often on several sources, so near-identical results are folded into
     * one. What survives is decided by how well the result matches what was typed, then by having a
     * cover, then by the user's own source order — the same order the player uses.
     */
    suspend fun search(
        text: String,
        only: AudioProviderId? = null,
        perProvider: Int = if (only != null) 60 else 25,
    ): List<ProviderMatch> {
        val query = text.trim()
        if (query.isBlank()) return emptyList()
        val providers = AudioFallbackEngine.activeProviders()
            .filter { it.id in PROVIDERS }
            .filter { only == null || it.id == only }
        if (providers.isEmpty()) {
            AudioDiagnostics.warn(
                "source search '$query': no usable source" +
                    (only?.let { " ($it is ${statusOf(it).name.lowercase()})" } ?: ""),
            )
            return emptyList()
        }

        val perSource = coroutineScope {
            providers.map { provider ->
                async(Dispatchers.IO) {
                    val started = System.currentTimeMillis()
                    val found = withTimeoutOrNull(provider.searchTimeoutMs) {
                        runCatching { provider.searchFree(query, perProvider) }
                            .onFailure {
                                AudioDiagnostics.warn("source search ${provider.id} ✘ '$query': ${it.message}")
                            }
                            .getOrDefault(emptyList())
                    }
                    if (found == null) {
                        AudioDiagnostics.warn("source search ${provider.id} ✘ '$query': timed out")
                    }
                    Timber.tag(TAG).d(
                        "%s → %d result(s) for '%s' in %dms",
                        provider.id, found?.size ?: -1, query, System.currentTimeMillis() - started,
                    )
                    provider.id to found.orEmpty()
                }
            }.map { it.await() }
        }
        AudioDiagnostics.info(
            "source search '$query' → " + perSource.joinToString { "${it.first} ${it.second.size}" },
        )

        val all = perSource.flatMap { it.second }
        val ranked = all.sortedWith(
            compareByDescending<ProviderMatch> { relevance(query, it) }
                .thenByDescending { it.thumbnailUrl != null }
                .thenBy { rank(it.provider) },
        )
        // Same song from two sources: keep the better one, but never merge across sources the user
        // asked to see on their own.
        val seen = HashSet<String>()
        return ranked.filter { match ->
            seen.add(if (only != null) catalogIdOf(match) else dedupKey(match))
        }
    }

    private fun rank(id: AudioProviderId): Int =
        ResolverPreferences.order.indexOf(id).let { if (it < 0) 99 else it }

    /** How much of what the user typed is actually in the result, 0..1. */
    private fun relevance(query: String, match: ProviderMatch): Double {
        val tokens = query.lowercase().split(' ', '-', '_', ',').filter { it.length > 1 }
        if (tokens.isEmpty()) return 0.0
        val haystack = "${match.artist} ${match.title}".lowercase()
        val hits = tokens.count { haystack.contains(it) }
        // An exact artist match counts for more than the same word buried in a long title.
        val artistBonus = if (match.artist.lowercase().trim() == query.lowercase().trim()) 0.5 else 0.0
        return hits.toDouble() / tokens.size + artistBonus
    }

    private fun dedupKey(match: ProviderMatch): String {
        val title = match.title.lowercase().filter { it.isLetterOrDigit() }
        val artist = match.artist.lowercase().filter { it.isLetterOrDigit() }
        val bucket = ((match.durationMs ?: 0L) / 1000 / 5)
        return "$artist|$title|$bucket"
    }

    fun metadataOf(match: ProviderMatch): MediaMetadata = MediaMetadata(
        id = mediaIdOf(match),
        title = match.title.ifBlank { "—" },
        artists = listOf(MediaMetadata.Artist(id = null, name = match.artist)),
        duration = ((match.durationMs ?: 0L) / 1000).toInt(),
        thumbnailUrl = match.thumbnailUrl,
        album = null,
    )

    /** A playable item for [match]; its metadata is registered so the resolver can describe it. */
    fun mediaItemOf(match: ProviderMatch): MediaItem {
        register(match)
        return metadataOf(match).toMediaItem()
    }

    private fun register(match: ProviderMatch) {
        SpotifyMetadataRegistry.register(
            mediaIdOf(match),
            SpotifyTrack(
                id = catalogIdOf(match),
                name = match.title,
                artists = listOf(SpotifySimpleArtist(name = match.artist)),
                album = null,
                durationMs = (match.durationMs ?: 0L).toInt(),
            ),
        )
    }

    /**
     * Writes the provider choice for [matches] to the database, the way "choose the audio source"
     * does. Without it the tracks would only play while the app is running: after a restart there is
     * no catalog to look them up in.
     */
    suspend fun remember(matches: List<ProviderMatch>) = withContext(Dispatchers.IO) {
        matches.forEach { match ->
            register(match)
            runCatching { AudioFallbackEngine.setManualChoice(mediaIdOf(match), null, match) }
                .onFailure { Timber.tag(TAG).w("could not pin %s: %s", match.trackId, it.message) }
        }
    }

    private const val TAG = "SourceSearch"
}
