/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.catalog.AccountLikes
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.LikedSongsCache
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The home page's "For you" covers and the dice's endless radio.
 *
 * Both grow from the listener's whole taste, not from what played a moment ago: each round draws
 * six liked tracks of the main account at random (its whole Liked Songs, the app's own likes),
 * takes two artists the catalog lists as similar to each one's artist, and a couple of random
 * popular songs of each. With too few likes, followed artists and the most played ones overall fill
 * in. Every round draws other likes, so the radio wanders across everything the listener likes.
 *
 * Built for speed: Spotify's gateway answers 500 to a burst of artist overviews, so a round is a
 * handful of requests (the overviews are shared and kept, see Spotify.artistUnion), each one cut off
 * after a few seconds, and tracks are handed out as they arrive — the first ones after two requests,
 * not after the whole round. A session keeps its next tracks ready in the background.
 *
 * Whatever the source, the result is filtered against the taste: nothing already liked or recently
 * played, at most two tracks per artist, and — for someone who hardly listens to Cyrillic-titled
 * music — no Cyrillic-titled tracks.
 */
object ForYouRecommender {
    /** 35 covers + the dice = 36 cells: four full pages of 3×3. */
    const val TARGET = 35
    private const val MAX_PER_ARTIST = 2

    /** Liked tracks drawn per round; each brings artists similar to its own. */
    private const val SEEDS_PER_ROUND = 6
    private const val RELATED_PER_SEED = 2
    private const val TRACKS_PER_RELATED = 2

    /** Among an artist's popular songs, picks are made from this many. */
    private const val TOP_TRACKS_POOL = 10

    /** One catalog request: a stuck connection (the client waits 30 s) must not hold a round. */
    private const val REQUEST_TIMEOUT_MS = 6_000L

    /** Tracks a session keeps ready beyond what was asked for, so the radio never waits. */
    private const val AHEAD = 20
    private const val MAX_EMPTY_ROUNDS = 6
    private const val EMPTY_ROUND_PAUSE_MS = 1_500L

    /** How long [Session.next] waits by default (the covers, built in the background). */
    const val DEFAULT_WAIT_MS = 25_000L

    /** Below this share of Cyrillic in what was listened to, Cyrillic results are left out. */
    private const val CYRILLIC_MIN_SHARE = 0.15

    private data class Taste(
        /** "artist|title" of everything liked or recently played: not recommended again. */
        val knownTracks: Set<String>,
        val cyrillicShare: Double,
    )

    /** An artist a round grows from: its catalog id when known, else it is looked up by name. */
    private data class Seed(val id: String?, val name: String) {
        val key: String get() = nameKey(name)
    }

    private class Seeds(
        /** One entry per liked track (an artist liked often is drawn more often). */
        val liked: List<Seed>,
        val followed: List<Seed>,
        /** The most played artists of all time. */
        val listened: List<Seed>,
    ) {
        val isEmpty: Boolean get() = liked.isEmpty() && followed.isEmpty() && listened.isEmpty()
    }

    // Changes slowly and every session needs it: kept for a while instead of read again per dice.
    @Volatile private var sharedTaste: Pair<Long, Taste>? = null
    @Volatile private var sharedSeeds: Pair<Long, Seeds>? = null
    @Volatile private var sharedMixes: Pair<Long, List<SpotifyTrack>>? = null
    private val artistByName = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val SHARED_TTL_MS = 30 * 60 * 1000L

    @Volatile private var searchTargetCache: Pair<Long, MetadataSource?>? = null

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun <T> Pair<Long, T>?.fresh(): T? = this?.takeIf { System.currentTimeMillis() - it.first < SHARED_TTL_MS }?.second

    /** The liked list was read anew (LikedSongsSync): the next session draws from all of it. */
    fun likesChanged() {
        sharedSeeds = null
        sharedTaste = null
    }

    /** The tracks shown on the home page. */
    suspend fun build(database: MusicDatabase, hideExplicit: Boolean): List<SpotifyTrack> {
        val session = Session(database, hideExplicit)
        return try {
            session.next(TARGET)
        } finally {
            session.close()
        }
    }

    /**
     * An endless stream of recommendations, handed out in chunks as they are needed. Nothing is
     * repeated within a session ([exclude] and everything already handed out), and what was handed
     * out in the last days comes back only when there is nothing new.
     */
    class Session(
        private val database: MusicDatabase,
        private val hideExplicit: Boolean,
        exclude: Collection<String> = emptySet(),
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private val used = HashSet(exclude)
        private val pooledIds = HashSet<String>()
        private val pooledKeys = HashSet<String>()
        private val perArtist = HashMap<String, Int>()

        /** Ready to hand out, in order. */
        private val pool = ArrayDeque<SpotifyTrack>()

        /** The rest of a round in progress (an artist's second song), spread in when the round ends. */
        private val later = ArrayList<SpotifyTrack>()

        /** Handed out in the last days: used only when there is nothing else. */
        private val heardPool = ArrayList<SpotifyTrack>()
        private val seenBefore = ForYouSeen.recent()

        private val changed = MutableStateFlow(0L)
        private var want = 0
        private var filling = false

        @Volatile private var exhausted = false
        private var added = 0

        private val prepareLock = Mutex()
        private val nextLock = Mutex()

        @Volatile private var taste: Taste? = null
        @Volatile private var seeds: Seeds? = null
        private val seedsUsed = HashSet<String>()
        private var rounds = 0
        private var mixesTried = false

        /** Starts finding the first [count] tracks in the background (the dice prepared ahead). */
        fun warm(count: Int) {
            scope.launch { if (runCatching { prepare() }.getOrDefault(false)) fill(count) }
        }

        /** Tracks ready to hand out right now. */
        fun ready(): Int = synchronized(lock) { pool.size + later.size }

        /** Stops the background work of a session no longer used. */
        fun close() = scope.cancel()

        /**
         * Up to [count] tracks: as many as arrived within [waitMs] (all of them when the catalog
         * answers), never waiting longer. The next ones are found meanwhile.
         */
        suspend fun next(count: Int, waitMs: Long = DEFAULT_WAIT_MS): List<SpotifyTrack> = nextLock.withLock {
            if (count <= 0) return@withLock emptyList()
            withTimeoutOrNull(waitMs) {
                if (prepare()) {
                    fill(count)
                    changed.first { ready() >= count || exhausted }
                }
                Unit
            }
            val out = take(count)
            fill(AHEAD)
            ForYouSeen.record(out.map { it.id })
            out
        }

        private suspend fun prepare(): Boolean = prepareLock.withLock {
            if (taste != null && seeds != null) return@withLock true
            if (!Catalog.ensureAuthenticated()) return@withLock false
            taste = sharedTaste.fresh() ?: taste(database).also { sharedTaste = System.currentTimeMillis() to it }
            seeds = sharedSeeds.fresh() ?: seeds(database).also { if (!it.isEmpty) sharedSeeds = System.currentTimeMillis() to it }
            true
        }

        private fun take(count: Int): List<SpotifyTrack> = synchronized(lock) {
            val out = ArrayList<SpotifyTrack>()
            fun from(source: MutableList<SpotifyTrack>) {
                while (out.size < count && source.isNotEmpty()) {
                    val track = source.removeAt(0)
                    if (used.add(track.id)) out += track
                }
            }
            from(pool)
            from(later)
            // Nothing new enough: better an older recommendation than an empty radio — the ones
            // heard longest ago first.
            if (out.isEmpty() || (exhausted && out.size < count)) {
                heardPool.sortBy { ForYouSeen.seenAt(it.id) }
                from(heardPool)
            }
            out
        }

        /** Keeps [count] tracks ready, one round after another, in the background. */
        private fun fill(count: Int) {
            synchronized(lock) {
                want = count
                if (filling) return
                filling = true
                exhausted = false
            }
            scope.launch {
                var empty = 0
                try {
                    while (true) {
                        synchronized(lock) {
                            if (ready() >= want || empty >= MAX_EMPTY_ROUNDS) {
                                if (empty >= MAX_EMPTY_ROUNDS) exhausted = true
                                filling = false
                                return@launch
                            }
                        }
                        val brought = runCatching { round() }
                            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                            .getOrDefault(0)
                        if (brought == 0) {
                            empty++
                            changed.update { it + 1 }
                            delay(EMPTY_ROUND_PAUSE_MS * empty)
                        } else {
                            empty = 0
                        }
                    }
                } finally {
                    synchronized(lock) { filling = false }
                    changed.update { it + 1 }
                }
            }
        }

        /** One round; the number of new tracks it brought. */
        private suspend fun round(): Int {
            val s = seeds ?: return 0
            val before = synchronized(lock) { added }
            val picked = pickSeeds(s, fast = rounds == 0)
            rounds++
            if (picked.isEmpty()) {
                // No likes, follows or listens at all: Spotify's own mixes, once.
                if (!mixesTried) {
                    mixesTried = true
                    emit(runCatching { mixes() }.getOrDefault(emptyList()))
                }
            } else {
                // The first round is the one someone is waiting for: popular songs only (two requests
                // to the first track). The rounds after it run in the background and dig deeper.
                val deep = rounds > 1
                coroutineScope { picked.forEach { seed -> launch { grow(seed, deep) } } }
            }
            synchronized(lock) {
                pool.addAll(later.shuffled())
                later.clear()
            }
            changed.update { it + 1 }
            val brought = synchronized(lock) { added } - before
            Timber.d("forYou: round %d from %s → %d tracks", rounds, picked.map { it.name }, brought)
            return brought
        }

        /**
         * [SEEDS_PER_ROUND] artists of liked tracks drawn at random, each artist once, not one of
         * the last rounds' while others are left. The first round prefers artists whose catalog id
         * is known (no name lookup before the first tracks).
         */
        private fun pickSeeds(s: Seeds, fast: Boolean): List<Seed> {
            val out = LinkedHashMap<String, Seed>()
            fun takeFrom(list: List<Seed>) {
                if (out.size >= SEEDS_PER_ROUND || list.isEmpty()) return
                val candidates = list.shuffled().let { l -> if (fast) l.sortedBy { if (it.id != null) 0 else 1 } else l }
                for (seed in candidates) {
                    if (out.size >= SEEDS_PER_ROUND) break
                    val key = seed.key
                    if (key.isEmpty() || key in seedsUsed || key in out) continue
                    out[key] = seed
                }
            }
            fun takeAll() {
                takeFrom(s.liked)
                takeFrom(s.followed)
                takeFrom(s.listened)
            }
            takeAll()
            if (out.size < SEEDS_PER_ROUND && seedsUsed.isNotEmpty()) {
                seedsUsed.clear()
                takeAll()
            }
            seedsUsed += out.keys
            return out.values.toList()
        }

        /**
         * Songs of artists similar to [seed]'s, put in the pool as each artist's answer arrives.
         * [deep]: one popular song per similar artist and one from deeper in its releases, plus a
         * deeper song of an artist one step further (similar to a similar one — often a smaller
         * name), instead of popular songs only.
         */
        private suspend fun grow(seed: Seed, deep: Boolean) {
            val id = resolve(seed) ?: return
            val related = timed { Catalog.relatedArtists(id) }.orEmpty()
                .filter { it.id.isNotBlank() && it.id != id }
                .take(20)
                .shuffled()
                .take(RELATED_PER_SEED)
            if (related.isEmpty()) {
                // No similar artists known: the artist's own songs (not the liked ones).
                val top = topTracks(id)
                emit(top.shuffled().take(if (deep) 1 else TRACKS_PER_RELATED))
                if (deep) emit(deepTracks(id, top, 1), soon = false)
                return
            }
            coroutineScope {
                related.forEach { artist ->
                    launch {
                        val top = topTracks(artist.id)
                        if (!deep) {
                            emit(top.shuffled().take(TRACKS_PER_RELATED))
                            return@launch
                        }
                        emit(top.shuffled().take(1))
                        emit(deepTracks(artist.id, top, 1), soon = false)
                    }
                }
                if (deep) {
                    launch {
                        val near = related.mapTo(HashSet()) { it.id } + id
                        // Its list comes with the overview already read for its songs: no extra request.
                        val further = timed { Catalog.relatedArtists(related.random().id) }.orEmpty()
                            .filter { it.id.isNotBlank() && it.id !in near }
                            .randomOrNull() ?: return@launch
                        emit(deepTracks(further.id, topTracks(further.id), 1), soon = false)
                    }
                }
            }
        }

        /** [soon]: one of these goes into the pool at once; else all wait for the round's end. */
        private fun emit(tracks: List<SpotifyTrack>, soon: Boolean = true) {
            val t = taste ?: return
            val fresh = filter(tracks, t, hideExplicit)
            if (fresh.isEmpty()) return
            synchronized(lock) {
                var first = soon
                for (track in fresh) {
                    val key = trackKey(track.artists.firstOrNull()?.name.orEmpty(), track.name)
                    if (track.id in used || track.id in pooledIds || key in pooledKeys) continue
                    val artist = track.artists.firstOrNull()?.name?.lowercase().orEmpty()
                    val n = perArtist[artist] ?: 0
                    if (n >= MAX_PER_ARTIST) continue
                    perArtist[artist] = n + 1
                    pooledIds += track.id
                    pooledKeys += key
                    when {
                        track.id in seenBefore -> heardPool += track
                        // One song per artist right away; the second waits for the round's end, so
                        // the same artist doesn't play twice in a row.
                        first -> {
                            pool.addLast(track)
                            first = false
                            added++
                        }
                        else -> {
                            later += track
                            added++
                        }
                    }
                }
            }
            changed.update { it + 1 }
        }
    }

    private suspend fun <T> timed(block: suspend () -> Result<T>): T? =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { block().getOrNull() }

    /** An artist's popular songs (the first [TOP_TRACKS_POOL]). */
    private suspend fun topTracks(artistId: String): List<SpotifyTrack> =
        timed { Catalog.artistTopTracks(artistId) }?.tracks.orEmpty().take(TOP_TRACKS_POOL)

    /** Album requests at once: a round asks for one per artist, and they must not come as a burst. */
    private val albumGate = kotlinx.coroutines.sync.Semaphore(2)

    /**
     * [count] songs from deeper in an artist's releases — a random album or single, a song that is
     * not among its popular ones ([top]), the less played ones more likely: what a radio of popular
     * songs alone never reaches. The release list comes with the artist's overview (no extra
     * request on Spotify); the release itself is one request, kept for a day.
     */
    private suspend fun deepTracks(artistId: String, top: List<SpotifyTrack>, count: Int): List<SpotifyTrack> {
        val releases = timed { Catalog.latestReleases(artistId) }.orEmpty().filter { it.id.isNotBlank() }
        val release = releases.randomOrNull() ?: return emptyList()
        val topIds = top.mapTo(HashSet()) { it.id }
        val topNames = top.mapTo(HashSet()) { nameKey(it.name) }
        val songs = albumGate.withPermit { timed { Catalog.album(release.id) } }?.tracks?.items.orEmpty()
            .filter { t ->
                t.id.isNotBlank() && t.id !in topIds && nameKey(t.name) !in topNames &&
                    // Its own song, not a guest's on a split or a compilation.
                    t.artists.any { it.id == artistId }
            }
        if (songs.isEmpty()) return emptyList()
        // Half of the time the least played half of the release: the true deep cuts.
        val known = songs.filter { it.playCount != null }.sortedBy { it.playCount }
        val pool = if (known.size >= 4 && kotlin.random.Random.nextBoolean()) known.take(known.size / 2) else songs
        return pool.shuffled().take(count)
    }

    /** The catalog id of [seed]'s artist: known, remembered, or found by its exact name. */
    private suspend fun resolve(seed: Seed): String? {
        seed.id?.let { return it }
        val target = searchTarget() ?: return null
        val cacheKey = "$target|${seed.key}"
        artistByName[cacheKey]?.let { return it }
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { exactArtist(target, seed.name) }
            ?.also { artistByName[cacheKey] = it }
    }

    /** Where names are looked up: Spotify when it can be reached (no VPN: the main account's catalog). */
    private suspend fun searchTarget(): MetadataSource? {
        searchTargetCache?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000L }?.let { return it.second }
        val spotify = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { Catalog.ensureSearchable(MetadataSource.SPOTIFY) } == true
        val target = if (spotify) MetadataSource.SPOTIFY else Catalog.source
        searchTargetCache = System.currentTimeMillis() to target
        return target
    }

    /**
     * A name counts only when the catalog has an artist of exactly that name: a SoundCloud uploader
     * ("mellstroyCR7") used to bring whoever came up first, and that stranger's "similar" with it.
     */
    private suspend fun exactArtist(target: MetadataSource, name: String): String? {
        val wanted = nameKey(name).ifEmpty { return null }
        return Catalog.searchIn(target, name, listOf("artist"), 5).getOrNull()?.artists?.items.orEmpty()
            .firstOrNull { it.id.isNotEmpty() && nameKey(it.name) == wanted }?.id
    }

    private fun nameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** A catalog id kept in the app's database ("spotify:…"); other ids (YouTube channels) are not one. */
    private fun dbCatalogId(id: String?): String? =
        id?.takeIf { it.startsWith(SPOTIFY_ID_PREFIX) }?.removePrefix(SPOTIFY_ID_PREFIX)?.takeIf { it.isNotBlank() }

    // ── Taste ────────────────────────────────────────────────────────────────────────────────────

    /** What is not recommended again, and how much Cyrillic is listened to. Read from what is at hand. */
    private suspend fun taste(database: MusicDatabase): Taste {
        val known = HashSet<String>()
        var cyrillic = 0
        var total = 0

        fun sample(artist: String, title: String) {
            total++
            if (hasCyrillic(artist) || hasCyrillic(title)) cyrillic++
            known += trackKey(artist, title)
        }

        database.events().first().take(600).forEach { e ->
            sample(e.song.artists.firstOrNull()?.name.orEmpty(), e.song.song.title)
        }
        database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().forEach { s ->
            sample(s.artists.firstOrNull()?.name.orEmpty(), s.song.title)
        }
        mainLiked().forEach { t -> sample(t.artists.firstOrNull()?.name.orEmpty(), t.name) }
        // The linked accounts as they were last read: asking them takes up to 15 s, so they are
        // refreshed behind for the next time instead.
        AccountLikes.lastKnown().tracks.forEach { sample(it.artist, it.title) }
        background.launch { runCatching { AccountLikes.all() } }

        return Taste(
            knownTracks = known,
            cyrillicShare = if (total == 0) 1.0 else cyrillic.toDouble() / total,
        )
    }

    /** The main account's whole Liked Songs, as kept on the device (no request). */
    private fun mainLiked(): List<SpotifyTrack> =
        Catalog.source?.let { runCatching { LikedSongsCache.get(it.name) }.getOrNull() }?.tracks.orEmpty()

    private suspend fun seeds(database: MusicDatabase): Seeds {
        val likedKeys = HashSet<String>()
        val liked = ArrayList<Seed>()
        fun like(artistId: String?, artist: String, title: String) {
            if (artist.isBlank()) return
            if (likedKeys.add(trackKey(artist, title))) liked += Seed(artistId?.takeIf { it.isNotBlank() }, artist)
        }
        // The main account's likes, the whole list; then the likes made in the app.
        mainLiked().forEach { t ->
            val a = t.artists.firstOrNull() ?: return@forEach
            like(a.id, a.name, t.name)
        }
        database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().forEach { s ->
            val a = s.artists.firstOrNull() ?: return@forEach
            like(dbCatalogId(a.id), a.name, s.song.title)
        }
        val account = AccountLikes.lastKnown()
        // Liked Songs not read on this device yet: the accounts' latest likes as last read.
        if (liked.size < SEEDS_PER_ROUND * 4) {
            account.tracks.forEach { t ->
                val (id, name) = t.artists.firstOrNull() ?: return@forEach
                like(id, name, t.title)
            }
        }
        val followed = (
            database.artistsBookmarked(ArtistSortType.CREATE_DATE, descending = true).first()
                .map { Seed(dbCatalogId(it.artist.id), it.artist.name) } +
                account.artists.map { Seed(null, it) }
            ).distinctBy { it.key }
        val listened = runCatching { database.mostPlayedArtists(fromTimeStamp = 0L, limit = 40).first() }
            .getOrDefault(emptyList())
            .map { Seed(dbCatalogId(it.artist.id), it.artist.name) }
        Timber.i("forYou: seeds from %d likes, %d followed, %d most played", liked.size, followed.size, listened.size)
        return Seeds(liked, followed, listened)
    }

    // ── Spotify's mixes (only without any taste to grow from) ────────────────────────────────────

    private suspend fun mixes(): List<SpotifyTrack> {
        if (!Catalog.state.value.spotifyConnected) return emptyList()
        sharedMixes.fresh()?.let { return it }
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS * 2) { madeForYou() }.orEmpty()
            .also { if (it.isNotEmpty()) sharedMixes = System.currentTimeMillis() to it }
    }

    /** Tracks of Spotify's personal mixes, interleaved so no single mix dominates. */
    private suspend fun madeForYou(): List<SpotifyTrack> = coroutineScope {
        val playlists = Spotify.home(sectionItemsLimit = 20).getOrThrow().sections
            .flatMap { it.items }
            .filterIsInstance<SpotifyHomeFeedItem.Playlist>()
            .filter { p -> p.madeForUsername != null || MIX_NAME.containsMatchIn(p.name) }
            .distinctBy { it.id }
            .take(6)
        val lists = playlists.map { p ->
            async {
                Catalog.playlistTracks(p.id, limit = 50, offset = 0).getOrNull()
                    ?.items?.mapNotNull { it.track }?.filter { it.id.isNotEmpty() && !it.isLocal }
                    .orEmpty()
                    .shuffled()
            }
        }.awaitAll()
        interleave(lists).shuffled()
    }

    // ── Filtering ────────────────────────────────────────────────────────────────────────────────

    private fun filter(tracks: List<SpotifyTrack>, taste: Taste, hideExplicit: Boolean): List<SpotifyTrack> =
        tracks.filter { t ->
            val artist = t.artists.firstOrNull()?.name.orEmpty()
            t.id.isNotEmpty() && !t.isLocal &&
                (!hideExplicit || !t.explicit) &&
                trackKey(artist, t.name) !in taste.knownTracks &&
                (taste.cyrillicShare >= CYRILLIC_MIN_SHARE || !(hasCyrillic(t.name) || t.artists.any { hasCyrillic(it.name) }))
        }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val queues = lists.map { ArrayDeque(it) }
        val out = ArrayList<T>()
        while (queues.any { it.isNotEmpty() }) queues.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    private fun trackKey(artist: String, title: String): String =
        artist.lowercase().filter { it.isLetterOrDigit() } + "|" +
            title.lowercase().substringBefore(" (").substringBefore(" - ").filter { it.isLetterOrDigit() }

    private fun hasCyrillic(text: String): Boolean = text.any { it in 'Ѐ'..'ӿ' }

    private val MIX_NAME = Regex(
        "Discover Weekly|Daily Mix|Release Radar|Mix|daylist|Открытия недели|Микс|Радар новинок",
        RegexOption.IGNORE_CASE,
    )
}
