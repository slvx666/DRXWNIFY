/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.music.constants.MetadataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Likes and follows across the two catalogs. Tracks and artists reach the app from Spotify (the
 * search and most pages) while the linked account may be Yandex Music, or the other way round: a
 * like then goes on the same track in the account's catalog, found by title, artist and length,
 * and a follow on the artist of the same name. What the account's catalog doesn't have stays liked
 * in the app only; [missing] keeps it for the library, which shows it with a mark.
 */
object CrossCatalog {
    private const val PREFS = "cross_catalog"
    private const val NONE = ""

    private var prefs: SharedPreferences? = null

    private val _missing = MutableStateFlow<Set<String>>(emptySet())

    /**
     * What is liked or followed in the app but not in the account's catalog: "t:<id>" for tracks,
     * "a:<id>" for artists, and "s:<song id>" for the app's own row of such a track.
     */
    val missing: StateFlow<Set<String>> = _missing

    fun init(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The account is read a moment after start: before that the list would be "no account"'s.
        scope.launch {
            Catalog.awaitAccounts()
            ensureLoaded()
        }
    }

    /** Per account, so switching it starts the list over. */
    private fun missingKey() = "missing:${Catalog.source?.name ?: "none"}"

    /** The key [_missing] was read under. */
    private var loadedKey: String? = null

    /** [_missing] read for the account linked now (again after a switch), before it is shown or changed. */
    @Synchronized
    private fun ensureLoaded() {
        val key = missingKey()
        if (key == loadedKey) return
        loadedKey = key
        _missing.value = prefs?.getStringSet(key, emptySet()).orEmpty()
    }

    @Synchronized
    private fun setMissing(key: String, missing: Boolean) {
        ensureLoaded()
        val now = _missing.value
        val updated = if (missing) now + key else now - key
        if (updated == now) return
        _missing.value = updated
        prefs?.edit()?.putStringSet(missingKey(), updated)?.apply()
    }

    /**
     * The id to like [id] with on the account: itself when the account takes it, else the same track
     * in the account's catalog, else null (no account, or the track isn't there — then remembered in
     * [missing] while it is liked). [title], [artists] and [durationSec] are what the app knows of it.
     */
    suspend fun trackForAccount(
        id: String,
        title: String,
        artists: List<String>,
        durationSec: Int,
        liked: Boolean,
        songId: String? = null,
        album: String? = null,
    ): String? {
        if (Catalog.canWrite(id)) return id
        val target = Catalog.source ?: return null
        val key = "t:${target.name}:$id"
        val known = prefs?.getString(key, null)
        val match = if (known != null) {
            known.ifEmpty { null }
        } else {
            if (!Catalog.ensureSearchable(target)) return null
            // Not answered (no connection): neither remembered nor marked, asked again next time.
            findTrack(target, title, artists, durationSec, album).getOrElse { return null }
                .also { prefs?.edit()?.putString(key, it ?: NONE)?.apply() }
        }
        setMissing("t:$id", liked && match == null)
        songId?.let { setMissing("s:$it", liked && match == null) }
        return match
    }

    /** Like [trackForAccount], for an artist followed by [name]. */
    suspend fun artistForAccount(id: String, name: String, followed: Boolean): String? {
        if (Catalog.canWrite(id)) return id
        val target = Catalog.source ?: return null
        val key = "a:${target.name}:$id"
        val known = prefs?.getString(key, null)
        val match = if (known != null) {
            known.ifEmpty { null }
        } else {
            if (!Catalog.ensureSearchable(target)) return null
            findArtist(target, name).getOrElse { return null }
                .also { prefs?.edit()?.putString(key, it ?: NONE)?.apply() }
        }
        setMissing("a:$id", followed && match == null)
        return match
    }

    /**
     * Once per account: what was liked or followed in the app before (from the other catalog) goes
     * on the account the same way. The library refreshes once at the end, not for every track.
     */
    suspend fun syncApp(database: com.metrolist.music.db.MusicDatabase) {
        Catalog.awaitAccounts()
        val source = Catalog.source ?: return
        val flag = "synced5:${source.name}"
        val p = prefs ?: return
        ensureLoaded()
        if (p.getBoolean(flag, false)) return
        var changed = false
        val songs = database.likedSongs(com.metrolist.music.constants.SongSortType.CREATE_DATE, true)
            .first()
        for (song in songs) {
            val id = com.metrolist.music.playback.LikeTarget.catalogId(database, song.id) ?: continue
            if (Catalog.canWrite(id)) continue
            val target = trackForAccount(
                id, song.song.title, song.artists.map { it.name }, song.song.duration,
                liked = true, songId = song.id, album = song.song.albumName,
            ) ?: continue
            val saved = if (Catalog.isYandexId(target)) {
                com.metrolist.yandex.YandexMusic.setTrackLiked(target, true)
            } else {
                com.metrolist.spotify.Spotify.saveTrack(target)
            }
            if (saved.isSuccess) changed = true
            Timber.i("CrossCatalog: \"%s\" → %s, liked %s", song.song.title, target, saved.exceptionOrNull() ?: "ok")
        }
        // All of them: artistsBookmarked() keeps YouTube and local artists only.
        val artists = database.artistsBookmarkedByCreateDateAsc().first()
        for (artist in artists) {
            val id = catalogArtistId(artist.artist) ?: continue
            if (Catalog.canWrite(id)) continue
            val target = artistForAccount(id, artist.artist.name, followed = true) ?: continue
            val followed = if (Catalog.isYandexId(target)) {
                com.metrolist.yandex.YandexMusic.setArtistLiked(target, true)
            } else {
                com.metrolist.spotify.Spotify.addToLibrary(listOf("spotify:artist:$target"))
            }
            if (followed.isSuccess) changed = true
            Timber.i("CrossCatalog: %s → %s, followed %s", artist.artist.name, target, followed.exceptionOrNull() ?: "ok")
        }
        p.edit().putBoolean(flag, true).apply()
        if (changed) Catalog.invalidateCaches()
        Timber.i("CrossCatalog: app library put on %s, %d not there", source, _missing.value.size)
    }

    /**
     * Followed artists saved without a photo (a follow from a page that had none at hand) get the
     * one their catalog has, so the library doesn't show an empty circle.
     */
    suspend fun fillArtistPhotos(database: com.metrolist.music.db.MusicDatabase) {
        Catalog.awaitAccounts()
        val bare = database.artistsBookmarkedByCreateDateAsc().first().filter { it.artist.thumbnailUrl.isNullOrBlank() }
        if (bare.isEmpty() || !Catalog.ensureSearchable(MetadataSource.SPOTIFY)) return
        for (artist in bare) {
            val id = catalogArtistId(artist.artist) ?: continue
            if (Catalog.isYandexId(id) && !Catalog.canWrite(id)) continue
            val photo = Catalog.artist(id).getOrNull()?.images?.let { images ->
                images.firstOrNull { it.width in 200..400 } ?: images.firstOrNull()
            }?.url ?: continue
            database.update(artist.artist.copy(thumbnailUrl = photo))
        }
    }

    // ── Pages from Spotify ──────────────────────────────────────────────────────────────────────

    /**
     * The Spotify artist for a Yandex one (opened from the Yandex library): pages are built from
     * Spotify, whose catalog is larger; null when Spotify has no such artist or can't be reached.
     * Given up after [PAIR_TIMEOUT_MS]: where Spotify is blocked its token request hangs, and the
     * page waits on this.
     */
    suspend fun spotifyArtistFor(yandexId: String, name: String?): String? =
        if (!Catalog.isYandexId(yandexId)) null else withTimeoutOrNull(PAIR_TIMEOUT_MS) { pairArtist(yandexId, name) }

    /** Like [spotifyArtistFor], for a Yandex album: the same title by the same artist. */
    suspend fun spotifyAlbumFor(yandexId: String, title: String, artist: String?): String? =
        if (!Catalog.isYandexId(yandexId)) null else withTimeoutOrNull(PAIR_TIMEOUT_MS) { pairAlbum(yandexId, title, artist) }

    private const val PAIR_TIMEOUT_MS = 6_000L

    private suspend fun pairArtist(yandexId: String, name: String?): String? {
        val key = "pa:$yandexId"
        // A remembered pair still needs Spotify reachable (its token is gone after a restart).
        prefs?.getString(key, null)?.let { known ->
            return known.ifEmpty { null }?.takeIf { Catalog.ensureSearchable(MetadataSource.SPOTIFY) }
        }
        val wanted = name?.takeIf { it.isNotBlank() } ?: Catalog.artist(yandexId).getOrNull()?.name ?: return null
        if (!Catalog.ensureSearchable(MetadataSource.SPOTIFY)) return null
        val found = Catalog.searchIn(MetadataSource.SPOTIFY, wanted, listOf("artist"), 10).getOrNull() ?: return null
        val match = found.artists?.items.orEmpty().firstOrNull { normalize(it.name) == normalize(wanted) }?.id
        prefs?.edit()?.putString(key, match ?: NONE)?.apply()
        return match
    }

    private suspend fun pairAlbum(yandexId: String, title: String, artist: String?): String? {
        val key = "pl:$yandexId"
        // A remembered pair still needs Spotify reachable (its token is gone after a restart).
        prefs?.getString(key, null)?.let { known ->
            return known.ifEmpty { null }?.takeIf { Catalog.ensureSearchable(MetadataSource.SPOTIFY) }
        }
        if (!Catalog.ensureSearchable(MetadataSource.SPOTIFY)) return null
        val query = listOfNotNull(artist, title).joinToString(" ")
        val found = Catalog.searchIn(MetadataSource.SPOTIFY, query, listOf("album"), 15).getOrNull() ?: return null
        val wantedTitle = normalize(title)
        val wantedArtist = artist?.let(::normalize).orEmpty()
        val match = found.albums?.items.orEmpty().firstOrNull { a ->
            normalize(a.name) == wantedTitle &&
                (wantedArtist.isEmpty() || a.artists.any { normalize(it.name) == wantedArtist })
        }?.id
        prefs?.edit()?.putString(key, match ?: NONE)?.apply()
        return match
    }

    /** The catalog id of an artist saved in the app ("SP_…", a bare Spotify id, a Yandex one), or null. */
    fun catalogArtistId(artist: com.metrolist.music.db.entities.ArtistEntity): String? =
        com.metrolist.music.utils.ArtistIdentity.spotifyIdOf(artist.spotifyId ?: artist.id)

    /** The same track in [target]'s catalog, by title, artist and length (±8 s); null when it hasn't it or doesn't answer. */
    suspend fun trackIn(target: MetadataSource, title: String, artists: List<String>, durationSec: Int, album: String? = null): String? =
        if (Catalog.ensureSearchable(target)) findTrack(target, title, artists, durationSec, album).getOrNull() else null

    private suspend fun findTrack(
        target: MetadataSource,
        title: String,
        artists: List<String>,
        durationSec: Int,
        album: String? = null,
    ): Result<String?> {
        val wantedTitle = normalize(title)
        val wantedArtists = artists.map(::normalize).filter { it.isNotEmpty() }
        if (wantedTitle.isEmpty()) return Result.success(null)
        // A failed search is not "the catalog hasn't it": only an answer may be remembered as such.
        var answered = false
        val artist = artists.firstOrNull()
        // Spotify answers "artist title" with the artist's popular tracks first, so the track itself
        // may not be among them: the title is tried first, alone too, then the other order.
        val queries = listOfNotNull(
            listOfNotNull(title, artist).joinToString(" "),
            title.takeIf { artist != null },
            artist?.let { "$it $title" },
        ).distinct()
        fun bestOf(found: List<com.metrolist.spotify.models.SpotifyTrack>, checkArtist: Boolean = true) =
            found.filter { !it.isLocal && it.id.isNotBlank() }.map { track ->
                val t = normalize(track.name)
                val titleScore = when {
                    t == wantedTitle -> 3
                    t.startsWith(wantedTitle) || wantedTitle.startsWith(t) -> 2
                    else -> 0
                }
                val names = track.artists.map { normalize(it.name) }
                val artistOk = !checkArtist || wantedArtists.isEmpty() ||
                    names.any { n -> wantedArtists.any { it == n || it.contains(n) || n.contains(it) } }
                val lengthOk = durationSec <= 0 || track.durationMs <= 0 || kotlin.math.abs(track.durationMs / 1000 - durationSec) <= 8
                track.id to if (artistOk && lengthOk) titleScore else 0
            }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
        for (query in queries) {
            val found = Catalog.searchIn(target, query, listOf("track"), 20).getOrNull() ?: continue
            answered = true
            bestOf(found.tracks?.items.orEmpty())?.let { return Result.success(it) }
        }
        // Some tracks never come up in a track search (Spotify's anonymous search shows the artist's
        // hits instead), while their album does: the track is taken from the album's list.
        if (!album.isNullOrBlank()) {
            val wantedAlbum = normalize(album)
            // "artist album" can bring the artist's other albums only; the title alone finds it then.
            for (query in listOfNotNull(artist?.let { "$it $album" }, album)) {
                val found = Catalog.searchIn(target, query, listOf("album"), 10).getOrNull() ?: continue
                answered = true
                val match = found.albums?.items.orEmpty()
                    .firstOrNull { a ->
                        normalize(a.name) == wantedAlbum && (wantedArtists.isEmpty() || a.artists.any { normalize(it.name) in wantedArtists })
                    } ?: continue
                // The album's artist checked already; its tracks may name a featured one first.
                Catalog.album(match.id).getOrNull()?.tracks?.items?.let { bestOf(it, checkArtist = false) }?.let { return Result.success(it) }
                break
            }
        }
        if (!answered) return Result.failure(IllegalStateException("$target didn't answer"))
        Timber.i("CrossCatalog: \"%s\" by %s not in %s", title, artist, target)
        return Result.success(null)
    }

    private suspend fun findArtist(target: MetadataSource, name: String): Result<String?> {
        val wanted = normalize(name).ifEmpty { return Result.success(null) }
        return Catalog.searchIn(target, name, listOf("artist"), 10).map { found ->
            found.artists?.items.orEmpty().firstOrNull { normalize(it.name) == wanted }?.id
        }
    }

    /** Lower case, accents and punctuation folded, "(feat. …)" / "- Remastered" notes dropped. */
    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(com.metrolist.spotify.LetterFold.fold(text.lowercase()), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[(\\[].*?(feat|ft\\.|with|remaster|version|edit)[^)\\]]*[)\\]]"), " ")
            .replace(Regex(" - .*(remaster|version|edit).*$"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
