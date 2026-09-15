/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import android.content.Context
import android.util.LruCache
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.QobuzAudioQuality
import com.metrolist.music.constants.QobuzAudioQualityKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.SoundCloudClientIdKey
import com.metrolist.music.constants.VkAccessTokenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AudioFallbackMatchEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.resolver.providers.QobuzFallbackProvider
import com.metrolist.music.resolver.providers.SoundCloudAudioProvider
import com.metrolist.music.resolver.providers.VkAudioProvider
import com.metrolist.music.resolver.providers.YouTubeAudioProvider
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide entry point of the audio fallback system.
 *
 *  - [resolve]: find where a catalog track's audio lives (cache → parallel race), remembering every
 *    confirmed provider match.
 *  - [streamPlan]: turn a media id into something playable right now, switching to an alternate
 *    provider / another upload (or a fresh race) when the stored one fails. Used by playback AND
 *    downloads.
 *
 * Metadata never flows back from providers: the catalog track stays authoritative, providers only
 * contribute a stream. Every decision is written to [AudioDiagnostics].
 */
object AudioFallbackEngine {
    /** A remembered winner is reused without racing again for this long. */
    private const val SELECTION_TTL_MS = 3L * 24 * 60 * 60 * 1000

    /** Recent "no provider has it" results, so re-opening a list doesn't re-search unplayable tracks. */
    private const val MISS_TTL_MS = 10L * 60 * 1000

    private lateinit var appContext: Context
    private lateinit var database: MusicDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val racer = ParallelAudioResolver()
    private val misses = LruCache<String, Long>(512)

    /** Winners resolved in this process, keyed by catalog id (skips even the DB read). */
    private val memory = LruCache<String, ProviderMatch>(1024)

    /** Callback that drops a cached catalog→YouTube match whose video turned out unplayable. */
    @Volatile
    var onYouTubeVideoUnplayable: ((videoId: String) -> Unit)? = null

    fun init(context: Context, database: MusicDatabase) {
        appContext = context.applicationContext
        this.database = database
    }

    private val providers: Map<AudioProviderId, AudioProvider> by lazy {
        listOf(
            YouTubeAudioProvider(),
            QobuzFallbackProvider(settings = ::qobuzSettings),
            VkAudioProvider(token = { appContext.dataStore.get(VkAccessTokenKey, "") }),
            com.metrolist.music.resolver.providers.BandcampAudioProvider(),
            com.metrolist.music.resolver.providers.AudiusAudioProvider(),
            com.metrolist.music.resolver.providers.SoulseekAudioProvider(
                credentials = {
                    val user = appContext.dataStore.get(com.metrolist.music.constants.SoulseekUsernameKey, "")
                    val pass = appContext.dataStore.get(com.metrolist.music.constants.SoulseekPasswordKey, "")
                    if (user.isNotBlank() && pass.isNotBlank()) user to pass else null
                },
                allowedNow = {
                    !appContext.dataStore.get(com.metrolist.music.constants.SoulseekWifiOnlyKey, true) || isUnmeteredNetwork()
                },
                cacheDir = { appContext.cacheDir },
            ),
            SoundCloudAudioProvider(
                loadClientId = { appContext.dataStore.get(SoundCloudClientIdKey, "") },
                saveClientId = { id -> scope.launch { appContext.dataStore.edit { it[SoundCloudClientIdKey] = id } } },
            ),
        ).associateBy { it.id }
    }

    fun provider(id: AudioProviderId): AudioProvider? = providers[id]

    private fun isUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun qobuzSettings(): QobuzFallbackProvider.Settings {
        val backend = when (appContext.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.MONOKENNY)) {
            QobuzBackend.MONOKENNY -> QobuzAudioProvider.ResolverBackend.MONOKENNY
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
        }
        val country = appContext.dataStore.get(QobuzCountryKey, "US").trim().uppercase()
            .takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        val quality = appContext.dataStore.get(QobuzAudioQualityKey).toEnum(QobuzAudioQuality.CD_QUALITY)
        return QobuzFallbackProvider.Settings(backend, country, QobuzAudioProvider.qualityCodeFor(quality))
    }

    private fun rank(id: AudioProviderId): Int = ResolverPreferences.order.indexOf(id).let { if (it < 0) 99 else it }

    /** Enabled providers that can work right now, in the user's order. */
    fun activeProviders(exclude: Set<AudioProviderId> = emptySet()): List<AudioProvider> =
        ResolverPreferences.order
            .filter { it !in exclude && ResolverPreferences.isEnabled(it) }
            .mapNotNull { providers[it] }
            .filter { it.isReady() }

    private fun AudioQuery.label() = "'${artists.joinToString(", ")} – $title'"

    private fun ProviderMatch.usableFor(query: AudioQuery, exclude: Set<AudioProviderId>) =
        provider !in exclude && trackId !in query.excludedTrackIds && ResolverPreferences.isEnabled(provider)

    // ── Resolution ───────────────────────────────────────────────────────────────────────────────

    /**
     * The provider match to use for [query], or null when no enabled provider has the track.
     * [exclude] skips providers; `query.excludedTrackIds` skips specific uploads.
     */
    suspend fun resolve(query: AudioQuery, exclude: Set<AudioProviderId> = emptySet()): ProviderMatch? =
        withContext(Dispatchers.IO) {
            val key = query.cacheKey
            val missKey = "$key|${exclude.sorted()}|${query.excludedTrackIds.sorted()}|${ResolverPreferences.order}"

            memory.get(key)?.takeIf { it.usableFor(query, exclude) }?.let { return@withContext it }

            val stored = storedMatches(query)
            stored.firstOrNull { row ->
                row.selected && System.currentTimeMillis() - row.matchedAt < SELECTION_TTL_MS
            }?.toMatch()?.takeIf { it.usableFor(query, exclude) }?.let { cached ->
                memory.put(key, cached)
                return@withContext cached
            }

            misses.get(missKey)?.takeIf { System.currentTimeMillis() - it < MISS_TTL_MS }?.let {
                return@withContext null
            }

            val active = activeProviders(exclude)
            if (active.isEmpty()) {
                AudioDiagnostics.warn("no enabled/ready audio source for ${query.label()} (excluded=$exclude)")
                return@withContext null
            }
            val outcome = racer.resolve(query, active, ResolverPreferences.order)
            val winner = outcome.winner
            AudioDiagnostics.info(
                "search ${query.label()} ${query.durationMs / 1000}s → ${winner?.provider ?: "NOT FOUND"} in ${outcome.elapsedMs}ms\n  " +
                    outcome.report.joinToString("\n  "),
            )
            if (winner == null) {
                misses.put(missKey, System.currentTimeMillis())
                // A stored alternate is better than nothing when the live race found no match
                // (e.g. a provider is temporarily down).
                return@withContext stored.firstNotNullOfOrNull { row -> row.toMatch()?.takeIf { it.usableFor(query, exclude) } }
            }
            memory.put(key, winner)
            remember(query, outcome.matches, winner)
            winner
        }

    private fun storedMatches(query: AudioQuery): List<AudioFallbackMatchEntity> =
        query.catalogId?.let { runCatching { database.getAudioFallbackMatches(it) }.getOrNull() }.orEmpty()

    private fun remember(query: AudioQuery, matches: List<ProviderMatch>, winner: ProviderMatch) {
        val catalogId = query.catalogId ?: return
        scope.launch {
            runCatching {
                database.clearAudioFallbackSelection(catalogId)
                database.upsertAudioFallbackMatches(
                    matches.map { m ->
                        AudioFallbackMatchEntity(
                            catalogId = catalogId,
                            provider = m.provider.name,
                            providerTrackId = m.trackId,
                            matchedTitle = m.title,
                            matchedArtist = m.artist,
                            confidence = m.confidence,
                            title = query.title,
                            artists = query.artists.joinToString(ARTIST_SEPARATOR),
                            album = query.album,
                            durationMs = query.durationMs,
                            isrc = query.isrc,
                            selected = m == winner,
                        )
                    },
                )
            }
        }
    }

    // ── Streams ──────────────────────────────────────────────────────────────────────────────────

    sealed interface StreamPlan {
        val provider: AudioProviderId

        /** Play/download this uri directly. */
        data class Direct(override val provider: AudioProviderId, val trackId: String, val stream: AudioStream) : StreamPlan

        /** Use the native YouTube pipeline with this video id. */
        data class YouTube(val videoId: String) : StreamPlan {
            override val provider get() = AudioProviderId.YOUTUBE
        }
    }

    /**
     * Best-effort metadata for a media id: registry (fresh from the catalog) → stored fallback row →
     * the song row written when the track was queued/downloaded.
     */
    fun queryFor(mediaId: String, dbSong: Song?): AudioQuery? {
        SpotifyMetadataRegistry.get(mediaId)?.let { return AudioQuery.from(it) }
        val catalogId = FallbackIds.catalogIdOf(mediaId)
            ?: runCatching { database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
        if (catalogId != null) {
            runCatching { database.getAudioFallbackMatches(catalogId) }.getOrNull()?.firstOrNull()?.let { row ->
                return AudioQuery(
                    catalogId = catalogId,
                    title = row.title,
                    artists = row.artists.split(ARTIST_SEPARATOR).filter { it.isNotBlank() },
                    album = row.album,
                    durationMs = row.durationMs,
                    isrc = row.isrc,
                )
            }
        }
        val song = dbSong ?: return null
        val artists = song.artists.map { it.name }.filter { it.isNotBlank() }
        if (song.song.title.isBlank() || artists.isEmpty()) return null
        return AudioQuery(
            catalogId = catalogId,
            title = song.song.title,
            artists = artists,
            album = song.song.albumName ?: song.album?.title,
            durationMs = song.song.duration.toLong().coerceAtLeast(0) * 1000,
            isrc = song.song.isrc,
        )
    }

    /**
     * A playable plan for [mediaId]: remembered match → alternates → fresh race, skipping providers in
     * [failed] and uploads in [excludedTrackIds] (e.g. an age-restricted YouTube video — another upload
     * of the same song on YouTube is still allowed).
     */
    suspend fun streamPlan(
        mediaId: String,
        dbSong: Song?,
        failed: Set<AudioProviderId> = emptySet(),
        excludedTrackIds: Set<String> = emptySet(),
    ): StreamPlan? = withContext(Dispatchers.IO) {
        val base = queryFor(mediaId, dbSong) ?: run {
            AudioDiagnostics.warn("no metadata to find audio for $mediaId")
            AudioDiagnostics.recordFailure(mediaId, AudioDiagnostics.FailureKind.NO_METADATA)
            return@withContext null
        }
        val query = base.copy(excludedTrackIds = base.excludedTrackIds + excludedTrackIds)
        val tried = failed.toMutableSet()
        val badTracks = query.excludedTrackIds.toMutableSet()

        // Known matches first (no search), in the user's provider order.
        val stored = storedMatches(query)
            .mapNotNull { it.toMatch() }
            .sortedBy { rank(it.provider) }
        for (match in stored) {
            if (!match.usableFor(query.copy(excludedTrackIds = badTracks), tried)) continue
            val plan = planFor(query, match)
            if (plan != null) {
                AudioDiagnostics.clearProblems(mediaId)
                return@withContext plan
            }
            badTracks += match.trackId
            forget(query, match)
        }

        // Then races, each time without what already failed.
        repeat(6) {
            val match = resolve(query.copy(excludedTrackIds = badTracks), exclude = tried) ?: run {
                AudioDiagnostics.warn("no playable audio for ${query.label()} (failed providers=$tried, bad uploads=${badTracks.size})")
                AudioDiagnostics.recordFailure(
                    mediaId,
                    if (activeProviders().isEmpty()) AudioDiagnostics.FailureKind.ALL_SOURCES_DISABLED
                    else AudioDiagnostics.FailureKind.NOT_FOUND,
                )
                return@withContext null
            }
            val plan = planFor(query, match)
            if (plan != null) {
                AudioDiagnostics.clearProblems(mediaId)
                return@withContext plan
            }
            badTracks += match.trackId
            forget(query, match)
            // A provider whose best match can't stream is unlikely to have a second playable upload.
            if (match.provider != AudioProviderId.YOUTUBE) tried += match.provider
        }
        null
    }

    private suspend fun planFor(query: AudioQuery, match: ProviderMatch): StreamPlan? {
        if (match.provider == AudioProviderId.YOUTUBE) return StreamPlan.YouTube(match.trackId)
        val provider = providers[match.provider] ?: return null
        if (!provider.isReady()) return null
        val stream = runCatching { provider.stream(query, match) }
            .onFailure { AudioDiagnostics.warn("stream ${match.provider} ✘ ${query.label()}: ${it.message}") }
            .getOrNull()
        if (stream == null) {
            AudioDiagnostics.warn("stream ${match.provider} ✘ ${query.label()} (track ${match.trackId}): no playable stream")
            return null
        }
        AudioDiagnostics.info("stream ✔ ${query.label()} via ${match.provider} (${stream.mimeType}, ${stream.bitrate / 1000}kbps)")
        return StreamPlan.Direct(match.provider, match.trackId, stream)
    }

    /** Drops a provider match that no longer yields a stream, so it isn't retried first next time. */
    private fun forget(query: AudioQuery, match: ProviderMatch) {
        val key = query.cacheKey
        memory.get(key)?.takeIf { it.trackId == match.trackId }?.let { memory.remove(key) }
        val catalogId = query.catalogId ?: return
        scope.launch { runCatching { database.deleteAudioFallbackMatch(catalogId, match.provider.name) } }
    }

    /**
     * Called by playback/download when a YouTube video id failed to stream for a reason tied to that
     * video (age restriction, removed, region lock): forget every cached match pointing at it.
     */
    fun markYouTubeVideoUnplayable(videoId: String, catalogId: String?) {
        AudioDiagnostics.warn("YouTube video $videoId unplayable — will use another upload/source")
        memory.snapshot().filterValues { it.provider == AudioProviderId.YOUTUBE && it.trackId == videoId }
            .keys.forEach { memory.remove(it) }
        onYouTubeVideoUnplayable?.invoke(videoId)
        if (catalogId != null) {
            scope.launch { runCatching { database.deleteAudioFallbackMatch(catalogId, AudioProviderId.YOUTUBE.name) } }
        }
    }

    private fun String.toProviderId(): AudioProviderId? = AudioProviderId.entries.firstOrNull { it.name == this }

    private fun AudioFallbackMatchEntity.toMatch(): ProviderMatch? {
        val id = provider.toProviderId() ?: return null
        return ProviderMatch(
            provider = id,
            trackId = providerTrackId,
            title = matchedTitle,
            artist = matchedArtist,
            durationMs = null,
            confidence = confidence,
        )
    }

    private const val ARTIST_SEPARATOR = "\u001F"
}
