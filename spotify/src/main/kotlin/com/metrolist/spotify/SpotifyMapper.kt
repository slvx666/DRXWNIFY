package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Utility object for creating search queries from Spotify track data.
 * The actual mapping to Metrolist MediaMetadata is done in the app module
 * where MediaMetadata class is available.
 */
object SpotifyMapper {

    // Pre-compiled regex patterns for title normalization (avoids re-creation on each call)
    private val FEAT_PATTERN = Regex("\\(feat\\..*?\\)")
    private val FT_PATTERN = Regex("\\(ft\\..*?\\)")
    private val BRACKET_PATTERN = Regex("\\[.*?]")
    private val REMASTER_PATTERN = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val REMIX_PATTERN = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    private const val NORM_CACHE_MAX_SIZE = 256
    private const val EARLY_EXIT_THRESHOLD = 0.95

    /**
     * LRU cache for normalized strings. Avoids re-running 7 regex replacements
     * on the same Spotify title/artist across multiple candidate comparisons.
     * Bounded to [NORM_CACHE_MAX_SIZE] entries to limit memory usage.
     */
    private val normalizeCache = object : LinkedHashMap<String, String>(
        NORM_CACHE_MAX_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > NORM_CACHE_MAX_SIZE
    }

    /**
     * LRU cache for pre-computed bigram sets. Avoids re-creating Set<String>
     * on every stringSimilarity call for the same normalized string.
     */
    private val bigramCache = object : LinkedHashMap<String, Set<String>>(
        NORM_CACHE_MAX_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean =
            size > NORM_CACHE_MAX_SIZE
    }

    /**
     * Pre-computed data for one side of a match comparison.
     * Created once per Spotify track and reused across all candidates.
     */
    data class PrecomputedTrack(
        val normalizedTitle: String,
        val titleBigrams: Set<String>,
        val normalizedArtist: String,
        val artistBigrams: Set<String>,
        val durationMs: Int,
    )

    /**
     * Builds a YouTube search query from a Spotify track.
     * The query is optimized for finding the matching song on YouTube Music.
     */
    fun buildSearchQuery(track: SpotifyTrack): String {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val title = track.name
        return if (artist.isEmpty()) title else "$artist $title"
    }

    /**
     * Returns the best thumbnail URL from a Spotify playlist, preferring medium resolution.
     */
    fun getPlaylistThumbnail(playlist: SpotifyPlaylist): String? {
        return playlist.images.let { images ->
            // Prefer 300x300 or similar medium size, fallback to first
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    /**
     * Returns the best thumbnail URL from a Spotify track's album art.
     * Prefers the highest available resolution so the full-screen player cover isn't blurry
     * (Spotify CDN URLs cannot be up-scaled via resize(), so the source variant must be large).
     */
    fun getTrackThumbnail(track: SpotifyTrack): String? {
        return track.album?.images?.let { images ->
            images.maxByOrNull { it.width ?: 0 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    /**
     * Returns a medium-resolution (200–400px) thumbnail from a Spotify track's album
     * art, for list/grid tiles where the full-resolution player cover would waste
     * bandwidth and decode memory. Falls back to the first available variant.
     */
    fun getTrackThumbnailMedium(track: SpotifyTrack): String? {
        return track.album?.images?.let { images ->
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    /**
     * Pre-computes normalized title/artist and their bigrams for a Spotify track.
     * Call once before scoring against multiple candidates to avoid redundant work.
     */
    fun precompute(
        title: String,
        artist: String,
        durationMs: Int,
    ): PrecomputedTrack {
        val normTitle = cachedNormalize(title)
        val normArtist = cachedNormalize(artist)
        return PrecomputedTrack(
            normalizedTitle = normTitle,
            titleBigrams = cachedBigrams(normTitle),
            normalizedArtist = normArtist,
            artistBigrams = cachedBigrams(normArtist),
            durationMs = durationMs,
        )
    }

    /**
     * Computes a match confidence score (0.0 - 1.0) between a Spotify track and
     * a candidate result based on title, artist, and duration similarity.
     */
    fun matchScore(
        spotifyTitle: String,
        spotifyArtist: String,
        spotifyDurationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normSpotifyTitle = cachedNormalize(spotifyTitle)
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normSpotifyArtist = cachedNormalize(spotifyArtist)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore = bigramSimilarity(
            normSpotifyTitle, cachedBigrams(normSpotifyTitle),
            normCandidateTitle, cachedBigrams(normCandidateTitle),
        )
        val artistScore = bigramSimilarity(
            normSpotifyArtist, cachedBigrams(normSpotifyArtist),
            normCandidateArtist, cachedBigrams(normCandidateArtist),
        )

        val durationScore = durationScore(spotifyDurationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /**
     * Scores a candidate against pre-computed Spotify track data.
     * This is the fast path: normalization and bigrams for the Spotify side
     * are computed once and reused across all candidates.
     */
    fun matchScorePrecomputed(
        precomputed: PrecomputedTrack,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore = bigramSimilarity(
            precomputed.normalizedTitle, precomputed.titleBigrams,
            normCandidateTitle, cachedBigrams(normCandidateTitle),
        )
        val artistScore = bigramSimilarity(
            precomputed.normalizedArtist, precomputed.artistBigrams,
            normCandidateArtist, cachedBigrams(normCandidateArtist),
        )

        val durationScore = durationScore(precomputed.durationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /** Threshold above which we consider a match good enough to skip remaining candidates. */
    fun earlyExitThreshold(): Double = EARLY_EXIT_THRESHOLD

    // ---------------------------------------------------------------------------------------------
    // Strict candidate selection (ported/adapted from Sunnify's MusicScraper._select_youtube_match)
    //
    // Guiding principle: A WRONG track is worse than NO track. The gates below reject a candidate
    // outright rather than shipping the closest-scoring-but-wrong result. Everything here is pure
    // string/number logic so it is unit-testable in the `spotify` module without Android/innertube.
    // ---------------------------------------------------------------------------------------------

    /** Max Spotify-vs-candidate length gap (seconds) still treated as the same recording. */
    const val DURATION_TOLERANCE_S = 7

    /**
     * Wider bound: right title+artist but more than this many seconds off means a
     * remix / live / extended / sped-up edit — reject rather than ship wrong audio.
     */
    const val DURATION_TOLERANCE_WIDE_S = 30

    /** Minimum raw match score for an accepted candidate (a floor beneath the hard gates). */
    const val MIN_MATCH_THRESHOLD = 0.35

    /** Per-marker ranking penalty for non-studio variants, capped by [MAX_VARIANT_PENALTY]. */
    private const val VARIANT_PENALTY_PER_MARKER = 0.15
    private const val MAX_VARIANT_PENALTY = 0.30

    /** Whole-word markers that indicate a non-studio upload (live/MV/edit/cover/…). */
    private val VARIANT_MARKER_REGEX = Regex(
        "\\b(live|en vivo|en directo|ao vivo|karaoke|cover|instrumental|" +
            "sped up|spedup|slowed|reverb|nightcore|8d|remix|bootleg|mashup|" +
            "music video|official video|lyric video)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Markers of a DIFFERENT recording of the song — someone else's cover, a remix, a sped-up or
     * karaoke edit. Unlike [VARIANT_MARKER_REGEX] (a ranking penalty) these reject the candidate
     * outright unless the catalog track itself carries the marker: a female-vocal remix titled
     * "Welcome to the Jungle" used to win for Guns N' Roses because only a penalty stood against it.
     */
    private val FOREIGN_RECORDING_REGEX = Regex(
        "\\b(cover|covered by|karaoke|remix|rmx|bootleg|mashup|nightcore|sped up|spedup|speed up|" +
            "slowed|reverb|8d|tribute|instrumental|minus|lofi|lo-fi|8-bit|8 bit|" +
            "(?:female|male|girl|boy|piano|acoustic|rock|metal|orchestral|russian|english) version|" +
            "кавер|ремикс|минус|караоке)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** True when [candidateTitle] is another recording (cover/remix/edit) the catalog title isn't. */
    fun isForeignRecording(catalogTitle: String, candidateTitle: String): Boolean {
        val cand = FOREIGN_RECORDING_REGEX.findAll(candidateTitle.lowercase()).map { it.value.lowercase() }.toSet()
        if (cand.isEmpty()) return false
        val own = FOREIGN_RECORDING_REGEX.findAll(catalogTitle.lowercase()).map { it.value.lowercase() }.toSet()
        return (cand - own).isNotEmpty()
    }

    /** Splits multi-artist strings on collaboration separators before normalization. */
    private val ARTIST_SPLIT_REGEX = Regex("[,&/]+|\\s+(?:feat\\.?|ft\\.?|x|vs\\.?)\\s+", RegexOption.IGNORE_CASE)

    /** A lightweight, source-agnostic candidate. The app adapter maps YouTube SongItems onto this. */
    data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationSec: Int?,
        val isVideo: Boolean = false,
        val thumbnailUrl: String? = null,
    )

    sealed interface MatchResult {
        data class Matched(
            val id: String,
            val score: Double,
            val title: String,
            val artist: String,
        ) : MatchResult
        data object NoMatch : MatchResult
    }

    /**
     * Drops the " - Variant" suffix Spotify adds to differentiate releases, applied ONLY to the
     * Spotify side ("Bohemian Rhapsody - Remastered 2011" -> "Bohemian Rhapsody"). Never apply to a
     * YouTube title: there " - " usually separates "Artist - Song" and the strip would lose the song.
     */
    fun spotifyTitleCore(title: String): String =
        if (title.isBlank()) "" else title.split(" - ", limit = 2)[0]

    /**
     * True when a candidate's title could reasonably be the Spotify track. The Spotify side gets
     * its variant suffix dropped first; both sides are normalized. Substring match for targets of
     * >= 4 chars, whole-word match for shorter ones (so a 1–3 char song name like "i" doesn't match
     * every video). Guards the failure mode where the top hit is a DIFFERENT song by the SAME artist.
     */
    fun titlePlausiblyMatches(candidateTitle: String, spotifyTitle: String): Boolean {
        val target = cachedNormalize(spotifyTitleCore(spotifyTitle))
        // A title made only of symbols ("./", "...", "?!") normalizes to nothing: compare the
        // symbols themselves instead of failing every candidate.
        if (target.isEmpty()) {
            val symbols = symbolsOf(spotifyTitle)
            return symbols.isNotEmpty() && symbols in symbolsOf(candidateTitle)
        }
        val cand = cachedNormalize(candidateTitle)
        if (cand.isEmpty()) return false
        return if (target.length >= 4) target in cand else target in cand.split(' ')
    }

    /** True when the title has no letters or digits at all ("./", "…"). */
    fun isSymbolOnlyTitle(title: String): Boolean =
        title.isNotBlank() && cachedNormalize(spotifyTitleCore(title)).isEmpty()

    private fun symbolsOf(s: String): String = s.filterNot { it.isWhitespace() }

    /** Normalized artist tokens from a (possibly multi-artist) credit string, empties removed. */
    fun artistTokens(spotifyArtists: String): List<String> =
        ARTIST_SPLIT_REGEX.split(spotifyArtists)
            .map { cachedNormalize(it) }
            .filter { it.isNotEmpty() }

    /**
     * True when an expected artist appears in the candidate's title, or in its artist field without
     * being part of a different act. "Mercury & The Architects" contains "Architects" but is another
     * band: when the candidate credits several names, each of them must be one of the expected
     * artists (a single uploader name like "architectsuk" still passes as before).
     */
    fun artistPlausiblyMatches(candidateTitle: String, candidateArtist: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val titleN = cachedNormalize(candidateTitle)
        if (tokens.any { it in titleN }) return true
        val artistN = cachedNormalize(candidateArtist)
        if (tokens.none { it in artistN }) return false
        val parts = ARTIST_SPLIT_REGEX.split(candidateArtist)
            .map { stripArticle(cachedNormalize(it)) }
            .filter { it.isNotEmpty() }
        if (parts.size <= 1) return true
        val expected = tokens.map { stripArticle(it) }
        return parts.all { part -> expected.any { e -> part == e || e in part || part in e } }
    }

    private fun stripArticle(name: String): String = name.removePrefix("the ").trim()

    private fun anyTokenHasLatin(tokens: List<String>): Boolean =
        tokens.any { t -> t.any { it in 'a'..'z' || it in '0'..'9' } }

    /**
     * Ranking penalty for candidates that look like a non-studio variant (live/MV/cover/remix/
     * sped-up/…) when the Spotify track itself is not such a variant. Steers ranking toward the
     * studio audio without hard-rejecting — a track that ONLY exists as e.g. a live version still
     * resolves, it is just deprioritised when a studio upload is also present.
     */
    fun variantPenalty(spotifyTitle: String, candidateTitle: String): Double {
        val candMarkers = VARIANT_MARKER_REGEX.findAll(candidateTitle.lowercase()).map { it.value.lowercase() }.toSet()
        if (candMarkers.isEmpty()) return 0.0
        val spotifyMarkers = VARIANT_MARKER_REGEX.findAll(spotifyTitle.lowercase()).map { it.value.lowercase() }.toSet()
        val extra = candMarkers - spotifyMarkers
        return (extra.size * VARIANT_PENALTY_PER_MARKER).coerceAtMost(MAX_VARIANT_PENALTY)
    }

    /**
     * Picks the best candidate for a Spotify track, or [MatchResult.NoMatch] when none clears the
     * gates. Policy (strict-by-default):
     *   1. Keep only candidates whose title plausibly matches (rules out same-artist/other-song).
     *   2. Prefer the subset whose artist also matches; fall back to title-only when the expected
     *      artist is entirely non-latin (romanized on YouTube) or no candidate carries the artist.
     *   3. Rank by (score − variantPenalty), tie-broken by duration closeness.
     *   4. Hard-reject the winner if its known duration is off by more than the wide tolerance
     *      (a remix/live/extended edit) or it falls below the score floor.
     *   5. [loose] (opt-in) trades the never-ship-wrong-audio guarantee for coverage, returning the
     *      duration-closest candidate when the strict gates find nothing.
     */
    fun selectBestMatch(
        spotifyTitle: String,
        spotifyPrimaryArtist: String,
        spotifyArtistsAll: String,
        spotifyDurationMs: Int,
        candidates: List<Candidate>,
        loose: Boolean = false,
    ): MatchResult {
        if (candidates.isEmpty()) return MatchResult.NoMatch

        val precomputed = precompute(spotifyTitle, spotifyPrimaryArtist, spotifyDurationMs)
        fun score(c: Candidate): Double =
            matchScorePrecomputed(precomputed, c.title, c.artist, c.durationSec)
        fun durationOff(c: Candidate): Int? =
            if (c.durationSec != null && spotifyDurationMs > 0) {
                kotlin.math.abs(spotifyDurationMs / 1000 - c.durationSec)
            } else null

        val tokens = artistTokens(spotifyArtistsAll.ifBlank { spotifyPrimaryArtist })
        var titleOk = candidates.filter {
            titlePlausiblyMatches(it.title, spotifyTitle) && !isForeignRecording(spotifyTitle, it.title)
        }
        // Symbol-only titles are often written differently by uploaders ("./" → "Untitled", "dot
        // slash"); the right artist with a near-exact length is then the best evidence there is.
        if (titleOk.isEmpty() && isSymbolOnlyTitle(spotifyTitle)) {
            titleOk = candidates.filter { c ->
                artistPlausiblyMatches(c.title, c.artist, tokens) &&
                    !isForeignRecording(spotifyTitle, c.title) &&
                    (durationOff(c) ?: Int.MAX_VALUE) <= DURATION_TOLERANCE_S
            }
        }
        if (titleOk.isEmpty()) return if (loose) loosePick(candidates, ::durationOff) else MatchResult.NoMatch

        var pool = titleOk.filter { artistPlausiblyMatches(it.title, it.artist, tokens) }
        if (pool.isEmpty()) {
            pool = if (!anyTokenHasLatin(tokens)) titleOk
            else return if (loose) loosePick(titleOk, ::durationOff) else MatchResult.NoMatch
        }

        // Highest adjusted score wins; ties broken toward the duration-closest candidate.
        val chosen = pool.minWithOrNull(
            compareByDescending<Candidate> { score(it) - variantPenalty(spotifyTitle, it.title) }
                .thenBy { durationOff(it) ?: Int.MAX_VALUE },
        ) ?: return MatchResult.NoMatch

        val off = durationOff(chosen)
        if (off != null && off > DURATION_TOLERANCE_WIDE_S) return MatchResult.NoMatch
        val chosenScore = score(chosen)
        if (chosenScore < MIN_MATCH_THRESHOLD) return MatchResult.NoMatch

        return MatchResult.Matched(chosen.id, chosenScore, chosen.title, chosen.artist)
    }

    private fun loosePick(candidates: List<Candidate>, durationOff: (Candidate) -> Int?): MatchResult {
        if (candidates.isEmpty()) return MatchResult.NoMatch
        val chosen = candidates.minByOrNull { durationOff(it) ?: Int.MAX_VALUE } ?: candidates.first()
        return MatchResult.Matched(chosen.id, 0.0, chosen.title, chosen.artist)
    }

    /**
     * Ordered, de-duplicated YouTube search queries for a track: strict → normalized → expanded.
     * Each later variant widens the net when the earlier ones return nothing usable.
     */
    fun buildSearchQueries(track: SpotifyTrack): List<String> {
        val primary = track.artists.firstOrNull()?.name.orEmpty()
        val allArtists = track.artists.joinToString(" ") { it.name }.trim()
        val title = track.name
        val titleCore = spotifyTitleCore(title)
        val bracketless = title.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ").replace(MULTI_SPACE_PATTERN, " ").trim()

        return listOf(
            if (primary.isEmpty()) title else "$primary $title",       // strict, as-is
            if (primary.isEmpty()) titleCore else "$primary $titleCore", // variant suffix dropped
            if (allArtists.isEmpty()) bracketless else "$allArtists $bracketless", // all artists, brackets stripped
            titleCore.ifBlank { title },                                 // title-only last resort
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun durationScore(spotifyDurationMs: Int, candidateDurationSec: Int?): Double {
        if (candidateDurationSec == null || spotifyDurationMs <= 0) return 0.5
        val diff = kotlin.math.abs(spotifyDurationMs / 1000 - candidateDurationSec)
        return when {
            diff <= 2 -> 1.0
            diff <= 5 -> 0.8
            diff <= 10 -> 0.5
            diff <= 30 -> 0.2
            else -> 0.0
        }
    }

    /**
     * Normalizes a title for comparison, with LRU caching.
     */
    private fun cachedNormalize(title: String): String {
        normalizeCache[title]?.let { return it }
        val normalized = normalizeTitle(title)
        normalizeCache[title] = normalized
        return normalized
    }

    /**
     * Returns cached bigrams for a normalized string.
     */
    private fun cachedBigrams(normalized: String): Set<String> {
        bigramCache[normalized]?.let { return it }
        val bigrams = if (normalized.length < 2) emptySet() else normalized.windowed(2).toSet()
        bigramCache[normalized] = bigrams
        return bigrams
    }

    private fun normalizeTitle(title: String): String {
        var s = title.lowercase()
            .replace(FEAT_PATTERN, "")
            .replace(FT_PATTERN, "")
            .replace(BRACKET_PATTERN, "")
            .replace(REMASTER_PATTERN, "")
            .replace(REMIX_PATTERN, "")
        // NFKD + drop combining marks so diacritics fold away ("Café" -> "cafe") without
        // deleting the base letter. Then keep letters/digits of ANY script (Cyrillic, CJK,
        // Greek, …) — the previous `[^a-z0-9\s]` filter erased every non-Latin title to an
        // empty string, so those tracks never matched anything. Apostrophes are dropped with
        // no separator so "I'm" -> "im" (not "i m"); other punctuation becomes a space.
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
            .replace("'", "")
            .replace("’", "")
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.isWhitespace() -> sb.append(' ')
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {} // diacritic mark
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return MULTI_SPACE_PATTERN.replace(sb.toString(), " ").trim()
    }

    /**
     * Dice coefficient using pre-computed bigram sets.
     */
    private fun bigramSimilarity(
        a: String, bigramsA: Set<String>,
        b: String, bigramsB: Set<String>,
    ): Double {
        if (a == b) return 1.0
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        val intersection = bigramsA.count { it in bigramsB }
        return (2.0 * intersection) / (bigramsA.size + bigramsB.size)
    }
}
