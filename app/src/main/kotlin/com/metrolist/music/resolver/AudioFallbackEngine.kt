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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
            VkAudioProvider(
                // The user's own login, else the shared test account from the remote config.
                token = { SharedVk.tokenFor(appContext.dataStore.get(VkAccessTokenKey, "")) },
                allowTrack = { SharedVk.allowTrack(appContext.dataStore.get(VkAccessTokenKey, "")) },
                userId = { appContext.dataStore.get(com.metrolist.music.constants.VkUserIdKey, "") },
            ),
            com.metrolist.music.resolver.providers.BandcampAudioProvider(),
            com.metrolist.music.resolver.providers.LosslessMirrorProvider(),
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
    suspend fun resolve(
        query: AudioQuery,
        exclude: Set<AudioProviderId> = emptySet(),
        /** Race the sources even though a recent automatic choice is stored (a quality re-check). */
        forceRace: Boolean = false,
    ): ProviderMatch? =
        withContext(Dispatchers.IO) {
            val key = query.cacheKey
            val missKey = "$key|${exclude.sorted()}|${query.excludedTrackIds.sorted()}|${ResolverPreferences.order}"

            if (!forceRace) memory.get(key)?.takeIf { it.usableFor(query, exclude) }?.let { return@withContext it }

            val stored = storedMatches(query)
            // A version the user picked by hand is kept for good (playback, downloads, after
            // restarts) — it never expires like an automatic choice does.
            storedCandidates(query).firstOrNull {
                it.confidence >= ParallelAudioResolver.MANUAL_CONFIDENCE && it.usableFor(query, exclude)
            }?.let { manual ->
                memory.put(key, manual)
                return@withContext manual
            }
            val fresh = !forceRace && stored.any { row -> row.selected && System.currentTimeMillis() - row.matchedAt < SELECTION_TTL_MS }
            if (fresh) {
                // Re-decide over what is stored instead of trusting the old "selected" flag: matches
                // picked before the stricter rules (e.g. a similarly named band) lose to exact ones.
                storedCandidates(query).firstOrNull { it.usableFor(query, exclude) }?.let { cached ->
                    memory.put(key, cached)
                    return@withContext cached
                }
            }

            misses.get(missKey)?.takeIf { System.currentTimeMillis() - it < MISS_TTL_MS }?.let {
                return@withContext null
            }

            val active = activeProviders(exclude)
            if (active.isEmpty()) {
                AudioDiagnostics.warn("no enabled/ready audio source for ${query.label()} (excluded=$exclude)")
                return@withContext null
            }
            // Soulseek is the last resort: it only runs when every other source has come back empty.
            // Racing it for each track (a queue resolves many at once) flooded the network with
            // searches, each answered by hundreds of peers.
            val (lastResort, regular) = active.partition { it.id == AudioProviderId.SOULSEEK }
            val outcome = racer.resolve(query, regular, ResolverPreferences.order)
                .takeIf { it.winner != null || lastResort.isEmpty() }
                ?: racer.resolve(query, lastResort, ResolverPreferences.order)
            val winner = outcome.winner
            AudioDiagnostics.info(
                "search ${query.label()} ${query.durationMs / 1000}s → ${winner?.provider ?: "NOT FOUND"} in ${outcome.elapsedMs}ms\n  " +
                    outcome.report.joinToString("\n  "),
            )
            if (winner == null) {
                misses.put(missKey, System.currentTimeMillis())
                // A stored alternate is better than nothing when the live race found no match
                // (e.g. a provider is temporarily down).
                return@withContext storedCandidates(query).firstOrNull { it.usableFor(query, exclude) }
            }
            memory.put(key, winner)
            remember(query, outcome.matches, winner)
            winner
        }

    /**
     * Stores [match] as the choice for [query] — used when a whole album was found on one source
     * (see [AlbumSources]). Not a hand-picked choice: a better source can still replace it.
     */
    suspend fun storeMatch(query: AudioQuery, match: ProviderMatch) = withContext(Dispatchers.IO) {
        val catalogId = query.catalogId ?: return@withContext
        memory.put(query.cacheKey, match)
        runCatching {
            database.clearAudioFallbackSelection(catalogId)
            database.upsertAudioFallbackMatches(
                listOf(
                    AudioFallbackMatchEntity(
                        catalogId = catalogId,
                        provider = match.provider.name,
                        providerTrackId = match.trackId,
                        matchedTitle = match.title,
                        matchedArtist = match.artist,
                        confidence = match.confidence,
                        title = query.title,
                        artists = query.artists.joinToString(ARTIST_SEPARATOR),
                        album = query.album,
                        durationMs = query.durationMs,
                        isrc = query.isrc,
                        selected = true,
                    ),
                ),
            )
        }
    }

    /**
     * A stored source for [query] that sounds better than YouTube while being just as exact — what
     * plays instead of a cached YouTube match in the "accuracy and quality" mode.
     */
    fun betterThanYouTube(query: AudioQuery): ProviderMatch? {
        val best = storedCandidates(query).firstOrNull { it.usableFor(query, emptySet()) } ?: return null
        return best.takeIf {
            it.provider != AudioProviderId.YOUTUBE &&
                it.expectedKbps > AudioProviderId.YOUTUBE.typicalKbps &&
                it.confidence >= ParallelAudioResolver.STRONG_MATCH
        }
    }

    /** Tracks whose sources were compared in the quality mode at least once (see SpotifyYouTubeMapper). */
    private val qualityChecked by lazy { appContext.getSharedPreferences("quality_checked", Context.MODE_PRIVATE) }

    fun wasQualityChecked(catalogId: String): Boolean = qualityChecked.contains(catalogId)

    fun markQualityChecked(catalogId: String) {
        qualityChecked.edit().putBoolean(catalogId, true).apply()
    }

    private fun storedMatches(query: AudioQuery): List<AudioFallbackMatchEntity> =
        query.catalogId?.let { runCatching { database.getAudioFallbackMatches(it) }.getOrNull() }.orEmpty()

    /** Stored matches that still pass the gates, best first (a manual pick always first). */
    private fun storedCandidates(query: AudioQuery): List<ProviderMatch> {
        val matches = storedMatches(query).mapNotNull { it.toMatch() }.filter { isPlausible(query, it) }
        val manual = matches.filter { it.confidence >= ParallelAudioResolver.MANUAL_CONFIDENCE }
        return manual + ParallelAudioResolver.inPickOrder(matches - manual.toSet(), ::rank)
    }

    private fun isPlausible(query: AudioQuery, match: ProviderMatch): Boolean {
        if (match.confidence >= ParallelAudioResolver.MANUAL_CONFIDENCE) return true
        if (match.title.isBlank()) return true
        val tokens = com.metrolist.spotify.SpotifyMapper.artistTokens(query.artists.joinToString(", "))
        return com.metrolist.spotify.SpotifyMapper.titlePlausiblyMatches(match.title, query.title) &&
            com.metrolist.spotify.SpotifyMapper.artistPlausiblyMatches(match.title, match.artist, tokens)
    }

    // -- Manual source choice ----------------------------------------------------------------------

    /** Metadata the source picker searches with, or null when the track has none (plain YouTube). */
    fun catalogQueryFor(mediaId: String, dbSong: Song?): AudioQuery? =
        queryFor(mediaId, dbSong)?.takeIf { it.catalogId != null }

    /**
     * Every enabled source's best match for [mediaId], for the "choose audio source" dialog. Unlike a
     * normal resolution nothing is cut short: each provider gets its full search time.
     */
    suspend fun candidatesFor(mediaId: String, dbSong: Song?): List<ProviderMatch> = withContext(Dispatchers.IO) {
        val query = queryFor(mediaId, dbSong) ?: return@withContext emptyList()
        val found = kotlinx.coroutines.coroutineScope {
            activeProviders().map { provider ->
                async {
                    runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(provider.searchTimeoutMs) { provider.search(query) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        ParallelAudioResolver.inPickOrder(found, ::rank)
    }

    /** One recording offered by "change track version", with its audio bitrate when it could be read. */
    data class VersionCandidate(val match: ProviderMatch, val bitrateKbps: Int?)

    /**
     * Recordings of [mediaId] to choose from: several YouTube uploads plus every other source's best
     * match, each with its bitrate. Closest match first, then the highest bitrate. Soulseek is left
     * out: its "bitrate" would mean downloading the whole file first.
     */
    suspend fun versionCandidates(mediaId: String, dbSong: Song?): List<VersionCandidate> = withContext(Dispatchers.IO) {
        val query = queryFor(mediaId, dbSong) ?: return@withContext emptyList()
        val found = kotlinx.coroutines.coroutineScope {
            val youtube = async { runCatching { youtubeVersions(query) }.getOrDefault(emptyList()) }
            val others = activeProviders()
                .filter { it.id != AudioProviderId.YOUTUBE && it.id != AudioProviderId.SOULSEEK }
                .map { provider ->
                    async {
                        val match = runCatching {
                            kotlinx.coroutines.withTimeoutOrNull(provider.searchTimeoutMs) { provider.search(query) }
                        }.getOrNull() ?: return@async null
                        // The bitrate is measured afterwards, row by row (see measureVersion): the list
                        // must not wait for it, and VK doesn't state it at all.
                        VersionCandidate(match, null)
                    }
                }
            youtube.await() + others.awaitAll().filterNotNull()
        }
        found.sortedWith(
            // Matches within 5% of each other count as equally close; the better sound wins there.
            compareByDescending<VersionCandidate> { (it.match.confidence.coerceAtMost(1.0) * 20).toInt() }
                .thenByDescending { it.bitrateKbps ?: it.match.expectedKbps },
        )
    }

    /**
     * The real bitrate of one version, kbps: what its service states, else measured from the start of
     * the audio itself (VK sends HLS without saying its bitrate). Null when it can't be read.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun measureVersion(mediaId: String, dbSong: Song?, match: ProviderMatch): Int? = withContext(Dispatchers.IO) {
        if (match.provider == AudioProviderId.YOUTUBE) return@withContext null
        val query = queryFor(mediaId, dbSong) ?: return@withContext null
        val provider = providers[match.provider] ?: return@withContext null
        kotlinx.coroutines.withTimeoutOrNull(VERSION_MEASURE_TIMEOUT_MS) {
            val stream = runCatching { provider.stream(query, match) }.getOrNull() ?: return@withTimeoutOrNull null
            if (stream.bitrate > 0) return@withTimeoutOrNull stream.bitrate / 1000
            runInterruptible {
                val source = com.metrolist.music.playback.datasource.HlsConcatDataSource.Factory(
                    androidx.media3.datasource.DefaultHttpDataSource.Factory(),
                    okhttp3.OkHttpClient(),
                ).createDataSource()
                runCatching {
                    source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(stream.uri)))
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (out.size() < MEASURE_BYTES) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    com.metrolist.music.playback.datasource.MeasuredAudio.analyze(out.toByteArray())?.bitrate?.div(1000)
                }.also { runCatching { source.close() } }.getOrNull()
            }
        }
    }

    private const val VERSION_MEASURE_TIMEOUT_MS = 25_000L
    private const val MEASURE_BYTES = 600_000

    private suspend fun youtubeVersions(query: AudioQuery): List<VersionCandidate> {
        if (!ResolverPreferences.isEnabled(AudioProviderId.YOUTUBE)) return emptyList()
        val items = com.metrolist.innertube.YouTube.search(
            com.metrolist.music.resolver.providers.ProviderGate.searchText(query),
            com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG,
            incognito = true,
        ).getOrNull()?.items?.filterIsInstance<com.metrolist.innertube.models.SongItem>().orEmpty()
        val byId = items.associateBy { it.id }
        val ranked = com.metrolist.music.resolver.providers.ProviderGate.ranked(
            query,
            items.map {
                com.metrolist.spotify.SpotifyMapper.Candidate(
                    id = it.id,
                    title = it.title,
                    artist = it.artists.firstOrNull()?.name.orEmpty(),
                    durationSec = it.duration,
                    thumbnailUrl = it.thumbnail,
                )
            },
            maxDurationGapS = 20,
            limit = 5,
        )
        return kotlinx.coroutines.coroutineScope {
            ranked.map { m ->
                async {
                    val bitrate = runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(VERSION_BITRATE_TIMEOUT_MS) {
                            com.metrolist.music.utils.YTPlayerUtils.playerResponseForMetadata(m.id).getOrNull()
                                ?.streamingData?.adaptiveFormats?.filter { it.isAudio }?.maxOfOrNull { it.bitrate }
                        }
                    }.getOrNull()
                    val item = byId[m.id]
                    VersionCandidate(
                        ProviderMatch(
                            provider = AudioProviderId.YOUTUBE,
                            trackId = m.id,
                            title = m.title,
                            artist = m.artist,
                            durationMs = item?.duration?.times(1000L),
                            confidence = m.score,
                            thumbnailUrl = item?.thumbnail,
                        ),
                        bitrate?.let { it / 1000 },
                    )
                }
            }.awaitAll()
        }
    }

    private const val VERSION_BITRATE_TIMEOUT_MS = 8_000L

    private suspend fun <T> runInterruptible(block: () -> T): T = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { block() }

    /** Which service the audio of [mediaId] comes from (null when not known yet). */
    fun sourceOf(mediaId: String, dbSong: Song?): AudioProviderId? = when {
        !FallbackIds.isFallbackId(mediaId) -> AudioProviderId.YOUTUBE
        else -> SourceSearch.providerOf(mediaId) ?: currentChoice(mediaId, dbSong)?.provider
    }

    /** The source currently remembered for [mediaId] (the one a manual pick or the last race chose). */
    fun currentChoice(mediaId: String, dbSong: Song?): ProviderMatch? {
        val query = queryFor(mediaId, dbSong) ?: return null
        memory.get(query.cacheKey)?.let { return it }
        return storedCandidates(query).firstOrNull()
    }

    /** Pins [match] as the audio for [mediaId]; `null` forgets every stored match (automatic again). */
    suspend fun setManualChoice(mediaId: String, dbSong: Song?, match: ProviderMatch?) = withContext(Dispatchers.IO) {
        val query = queryFor(mediaId, dbSong) ?: return@withContext
        val catalogId = query.catalogId ?: return@withContext
        memory.remove(query.cacheKey)
        misses.evictAll()
        runCatching {
            if (match == null) {
                database.deleteAudioFallbackMatches(catalogId)
            } else {
                database.clearAudioFallbackSelection(catalogId)
                database.upsertAudioFallbackMatches(
                    listOf(
                        AudioFallbackMatchEntity(
                            catalogId = catalogId,
                            provider = match.provider.name,
                            providerTrackId = match.trackId,
                            matchedTitle = match.title,
                            matchedArtist = match.artist,
                            confidence = ParallelAudioResolver.MANUAL_CONFIDENCE,
                            title = query.title,
                            artists = query.artists.joinToString(ARTIST_SEPARATOR),
                            album = query.album,
                            durationMs = query.durationMs,
                            isrc = query.isrc,
                            selected = true,
                        ),
                    ),
                )
                memory.put(query.cacheKey, match.copy(confidence = ParallelAudioResolver.MANUAL_CONFIDENCE))
            }
        }.onFailure { AudioDiagnostics.warn("manual source choice for $mediaId failed: ${it.message}") }
        AudioDiagnostics.info(
            if (match == null) "source for ${query.label()} set back to automatic"
            else "source for ${query.label()} pinned to ${match.provider} '${match.artist} – ${match.title}'",
        )
    }

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

        // Quality mode: what was chosen before it existed is compared with every source once.
        val catalogId = query.catalogId
        if (catalogId != null && ResolverPreferences.pickMode == com.metrolist.music.constants.SourcePickMode.ACCURACY &&
            !wasQualityChecked(catalogId) && storedCandidates(query).none { it.confidence >= ParallelAudioResolver.MANUAL_CONFIDENCE }
        ) {
            runCatching { resolve(query, failed, forceRace = true) }
            markQualityChecked(catalogId)
        }

        // Known matches first (no search): comparably confident ones in the user's order.
        val stored = storedCandidates(query)
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
