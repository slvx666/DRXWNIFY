package com.metrolist.spotify

import com.metrolist.spotify.SpotifyMapper.Candidate
import com.metrolist.spotify.SpotifyMapper.MatchResult
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [SpotifyMapper.selectBestMatch] — the strict candidate selector that decides
 * which YouTube video plays for a Spotify track. Guiding invariant: a WRONG track is worse than NO
 * track, so the wrong-audio cases below must resolve to [MatchResult.NoMatch], never a bad match.
 *
 * Each case models the candidate set the resolver would collect from YouTube search and asserts the
 * selector's verdict. Durations are in seconds (as YouTube returns them); Spotify duration in ms.
 */
class SpotifyMapperSelectMatchTest {

    private fun cand(id: String, title: String, artist: String, dur: Int?, video: Boolean = false) =
        Candidate(id = id, title = title, artist = artist, durationSec = dur, isVideo = video)

    private fun select(
        title: String,
        artist: String,
        durationSec: Int,
        candidates: List<Candidate>,
        allArtists: String = artist,
        loose: Boolean = false,
    ): MatchResult = SpotifyMapper.selectBestMatch(
        spotifyTitle = title,
        spotifyPrimaryArtist = artist,
        spotifyArtistsAll = allArtists,
        spotifyDurationMs = durationSec * 1000,
        candidates = candidates,
        loose = loose,
    )

    private fun matchedId(r: MatchResult): String {
        assertTrue("expected a match, got NoMatch", r is MatchResult.Matched)
        return (r as MatchResult.Matched).id
    }

    // 1. Exact match.
    @Test
    fun `exact title artist duration matches`() {
        val r = select("Blinding Lights", "The Weeknd", 200, listOf(
            cand("RIGHT", "Blinding Lights", "The Weeknd", 200),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    // 2. Right title+artist but the only candidate's duration is wildly off -> reject.
    @Test
    fun `duration off by more than wide tolerance is rejected`() {
        val r = select("Blinding Lights", "The Weeknd", 200, listOf(
            cand("LONG", "Blinding Lights", "The Weeknd", 200 + 90),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 3. Same artist, different song -> title gate rejects.
    @Test
    fun `same artist different song is rejected`() {
        val r = select("Mi Gente", "J Balvin", 189, listOf(
            cand("WRONG", "Ginza", "J Balvin", 190),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 4. Same title, different artist -> artist gate rejects.
    @Test
    fun `same title different artist is rejected`() {
        val r = select("Imagine", "John Lennon", 183, listOf(
            cand("WRONG", "Imagine", "Ariana Grande", 183),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 5. Live version: a studio upload is preferred over a live one when both are present…
    @Test
    fun `studio preferred over live when both present`() {
        val r = select("As It Was", "Harry Styles", 167, listOf(
            cand("LIVE", "As It Was (Live)", "Harry Styles", 170),
            cand("STUDIO", "As It Was", "Harry Styles", 167),
        ))
        assertEquals("STUDIO", matchedId(r))
    }

    // …but a track that ONLY exists as a live version still resolves (variant is demoted, not banned).
    @Test
    fun `live-only track still resolves`() {
        val r = select("As It Was", "Harry Styles", 167, listOf(
            cand("LIVE", "As It Was (Live)", "Harry Styles", 169),
        ))
        assertEquals("LIVE", matchedId(r))
    }

    // 6. Cover by a different artist -> artist gate rejects.
    @Test
    fun `cover by different artist is rejected`() {
        val r = select("Hello", "Adele", 295, listOf(
            cand("COVER", "Hello (Adele Cover)", "Boyce Avenue", 300),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 7. Remix present alongside the original -> original wins.
    @Test
    fun `original preferred over remix`() {
        val r = select("Closer", "The Chainsmokers", 244, listOf(
            cand("REMIX", "Closer (Remix)", "The Chainsmokers", 245),
            cand("ORIG", "Closer", "The Chainsmokers", 244),
        ))
        assertEquals("ORIG", matchedId(r))
    }

    // 8. Slowed + reverb edit present alongside original -> original wins.
    @Test
    fun `original preferred over slowed reverb`() {
        val r = select("Sunflower", "Post Malone", 158, listOf(
            cand("SLOW", "Sunflower (slowed + reverb)", "Post Malone", 160),
            cand("ORIG", "Sunflower", "Post Malone", 158),
        ))
        assertEquals("ORIG", matchedId(r))
    }

    // 9. Sped up edit present alongside original -> original wins.
    @Test
    fun `original preferred over sped up`() {
        val r = select("Say So", "Doja Cat", 237, listOf(
            cand("FAST", "Say So (sped up)", "Doja Cat", 200),
            cand("ORIG", "Say So", "Doja Cat", 237),
        ))
        assertEquals("ORIG", matchedId(r))
    }

    // 10. Karaoke by a karaoke channel -> artist gate rejects.
    @Test
    fun `karaoke-only is rejected`() {
        val r = select("Rolling in the Deep", "Adele", 228, listOf(
            cand("KAR", "Rolling in the Deep (Karaoke Version)", "Sing King Karaoke", 230),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 11. "Official Audio" tag is not a variant marker -> matches cleanly.
    @Test
    fun `official audio matches`() {
        val r = select("Levitating", "Dua Lipa", 203, listOf(
            cand("OA", "Levitating (Official Audio)", "Dua Lipa", 203),
        ))
        assertEquals("OA", matchedId(r))
    }

    // 12. Official video only -> still resolves (demoted, not banned).
    @Test
    fun `official video only still resolves`() {
        val r = select("Bad Guy", "Billie Eilish", 194, listOf(
            cand("MV", "bad guy (Official Music Video)", "Billie Eilish", 194, video = true),
        ))
        assertEquals("MV", matchedId(r))
    }

    // 13. Spotify title carries "(feat. …)" the YouTube upload drops -> still matches.
    @Test
    fun `feat tag difference still matches`() {
        val r = select("Lose Yourself (feat. Eminem)", "Eminem", 326, listOf(
            cand("YT", "Lose Yourself", "Eminem", 326),
        ))
        assertEquals("YT", matchedId(r))
    }

    // 14. Punctuation differences normalize away.
    @Test
    fun `punctuation differences still match`() {
        val r = select("Hello, World!", "Louis Armstrong", 140, listOf(
            cand("YT", "Hello World", "Louis Armstrong", 141),
        ))
        assertEquals("YT", matchedId(r))
    }

    // 15. Spotify "- Remastered" suffix is dropped before comparison.
    @Test
    fun `remastered suffix still matches plain upload`() {
        val r = select("Bohemian Rhapsody - Remastered 2011", "Queen", 354, listOf(
            cand("YT", "Bohemian Rhapsody", "Queen", 355),
        ))
        assertEquals("YT", matchedId(r))
    }

    // 16. Track genuinely unavailable on YouTube -> NoMatch.
    @Test
    fun `unavailable track yields no match`() {
        val r = select("Totally Obscure B-Side", "Some Indie Band", 210, listOf(
            cand("A", "Completely Different Song", "Another Band", 200),
            cand("B", "Yet Another Track", "Third Band", 215),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 17. An unrelated high-ranked video sits above the correct result -> gates skip it.
    @Test
    fun `unrelated high-ranked video is skipped for the correct one`() {
        val r = select("Виктор Цой", "Кино", 240, listOf(
            cand("UNREL", "Best Songs Compilation 2024 Mix", "Music Channel", 241),
            cand("RIGHT", "Виктор Цой", "Кино", 240),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    // 18. First candidate is wrong, the second is correct -> second chosen (not just the top hit).
    @Test
    fun `second candidate chosen when first is wrong`() {
        val r = select("Mi Gente", "J Balvin", 189, listOf(
            cand("WRONG", "Mi Chico", "DJ Goja", 191),        // same-ish duration, different song
            cand("RIGHT", "Mi Gente", "J Balvin", 189),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    // 19. ASAVA — Mahjong regression: a game-video decoy contains the word "Mahjong" but is not the
    // track; the artist gate must reject it, and the real ASAVA upload must be the pick.
    @Test
    fun `ASAVA Mahjong regression picks the real track not the decoy`() {
        val r = select("Mahjong", "ASAVA", 132, listOf(
            cand("DECOY", "Mahjong Solitaire Relaxing Gameplay", "Game Channel", 130),
            cand("RIGHT", "ASAVA - Mahjong", "ASAVA", 132),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    @Test
    fun `ASAVA Mahjong with only the decoy yields no match`() {
        val r = select("Mahjong", "ASAVA", 132, listOf(
            cand("DECOY", "Mahjong Solitaire Relaxing Gameplay", "Game Channel", 130),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    // 20. Album with one unresolved track: the others resolve independently; one NoMatch doesn't
    // sink the album (mirrors per-track resolution in the queues).
    @Test
    fun `album resolves independently per track`() {
        val a = select("Track A", "The Band", 180, listOf(cand("YA", "Track A", "The Band", 181)))
        val b = select("Track B", "The Band", 200, listOf(cand("YB", "Track B", "The Band", 200)))
        val c = select("Track C", "The Band", 210, listOf(cand("WRONG", "Track C", "A Cover Artist", 210)))
        val d = select("Track D", "The Band", 190, listOf(cand("YD", "Track D", "The Band", 189)))

        assertEquals("YA", matchedId(a))
        assertEquals("YB", matchedId(b))
        assertEquals(MatchResult.NoMatch, c) // unavailable -> skipped, not fatal
        assertEquals("YD", matchedId(d))
    }

    // Loose mode: a cross-script title the ascii gate can't match is recovered when opted in.
    @Test
    fun `loose mode recovers when strict gates find nothing`() {
        val strict = select("Song", "Артист", 200, listOf(cand("X", "完全に違うタイトル", "別のチャンネル", 200)))
        assertEquals(MatchResult.NoMatch, strict)

        val loose = select("Song", "Артист", 200, listOf(cand("X", "完全に違うタイトル", "別のチャンネル", 200)), loose = true)
        assertEquals("X", matchedId(loose))
    }

    // Query cascade: strict form first, and the variant-suffix form is included.
    @Test
    fun `buildSearchQueries yields strict form first and a normalized variant`() {
        val track = SpotifyTrack(
            name = "Bohemian Rhapsody - Remastered 2011",
            artists = listOf(SpotifySimpleArtist(name = "Queen")),
            durationMs = 354_000,
        )
        val queries = SpotifyMapper.buildSearchQueries(track)
        assertEquals("Queen Bohemian Rhapsody - Remastered 2011", queries.first())
        assertTrue("expected a variant-stripped query", queries.any { it == "Queen Bohemian Rhapsody" })
    }

    // A different act whose name merely contains the artist must not pass the artist gate.
    @Test
    fun `band name containing the artist is not the artist`() {
        val r = select("Machine", "Architects", 240, listOf(
            cand("WRONG", "Machine", "Mercury & The Architects", 240),
        ))
        assertEquals(MatchResult.NoMatch, r)
    }

    @Test
    fun `real artist wins over a band that contains its name`() {
        val r = select("Machine", "Architects", 240, listOf(
            cand("WRONG", "Machine", "Mercury & The Architects", 240),
            cand("RIGHT", "Machine", "Architects", 241),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    @Test
    fun `duo credited with an ampersand still matches`() {
        val r = select("The Sound of Silence", "Simon & Garfunkel", 185, listOf(
            cand("RIGHT", "The Sound of Silence", "Simon & Garfunkel", 185),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    @Test
    fun `single uploader name containing the artist still matches`() {
        val r = select("Machine", "Architects", 240, listOf(
            cand("RIGHT", "Machine", "architectsuk", 240),
        ))
        assertEquals("RIGHT", matchedId(r))
    }

    @Test
    fun `a cover or remix under the original title never beats the original`() {
        val r = select(
            "Welcome To The Jungle", "Guns N' Roses", 273,
            listOf(
                cand("remix", "Guns N' Roses - Welcome To The Jungle (Female Version Remix)", "SomeChannel", 270),
                cand("orig", "Welcome To The Jungle", "Guns N' Roses", 272),
            ),
        )
        assertEquals("orig", matchedId(r))
    }

    @Test
    fun `only covers found means no match`() {
        val r = select(
            "Welcome To The Jungle", "Guns N' Roses", 273,
            listOf(
                cand("c1", "Welcome To The Jungle - Guns N' Roses (cover)", "Singer", 271),
                cand("c2", "Guns N' Roses - Welcome To The Jungle | Karaoke", "Karaoke Hits", 273),
            ),
        )
        assertTrue(r is MatchResult.NoMatch)
    }

    @Test
    fun `a remix the catalog itself names is kept`() {
        val r = select(
            "Blinding Lights - Chromatics Remix", "The Weeknd", 360,
            listOf(cand("rmx", "The Weeknd - Blinding Lights (Chromatics Remix)", "The Weeknd", 360)),
        )
        assertEquals("rmx", matchedId(r))
    }
}
