package com.metrolist.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify titles that credit other artists in brackets ("(with …)", "(feat …)" without a dot,
 * a credit list with "feat." in the middle) used to match no upload on any source.
 */
class CollaborationTitleMatchTest {
    private fun matches(spotifyTitle: String, artists: String, durationS: Int, candidate: SpotifyMapper.Candidate) =
        SpotifyMapper.selectBestMatch(
            spotifyTitle, artists.substringBefore(","), artists, durationS * 1000, listOf(candidate),
        ) is SpotifyMapper.MatchResult.Matched

    @Test
    fun withCreditIsIgnored() {
        assertTrue(matches("One Kiss (with Dua Lipa)", "Calvin Harris, Dua Lipa", 214, SpotifyMapper.Candidate("a", "One Kiss", "Calvin Harris", 215)))
        assertTrue(matches("family ties (with Kendrick Lamar)", "Baby Keem, Kendrick Lamar", 252, SpotifyMapper.Candidate("b", "family ties", "Baby Keem", 252)))
        assertTrue(
            matches(
                "I Like You (A Happier Song) (with Doja Cat)", "Post Malone, Doja Cat", 192,
                SpotifyMapper.Candidate("c", "I Like You (A Happier Song) (with Doja Cat)", "Post Malone", 193),
            ),
        )
    }

    @Test
    fun featWithoutDotAndCreditListsAreIgnored() {
        assertTrue(matches("Timeless (feat Playboi Carti)", "The Weeknd, Playboi Carti", 256, SpotifyMapper.Candidate("d", "Timeless", "The Weeknd", 256)))
        assertTrue(
            matches(
                "Calling (Spider-Man: Across the Spider-Verse) (Metro Boomin & Swae Lee, NAV, feat. A Boogie Wit da Hoodie)",
                "Metro Boomin, Swae Lee, NAV, A Boogie Wit da Hoodie", 219,
                SpotifyMapper.Candidate("e", "Calling (Spider-Man: Across the Spider-Verse)", "Metro Boomin", 219),
            ),
        )
    }

    @Test
    fun anotherSongIsStillRejected() {
        assertEquals(
            false,
            matches("One Kiss (with Dua Lipa)", "Calvin Harris, Dua Lipa", 214, SpotifyMapper.Candidate("f", "Promises", "Calvin Harris", 214)),
        )
    }
}

/** Names written differently on Spotify and on the source: spaces, script, own-credit brackets. */
class ArtistSpellingMatchTest {
    private fun matches(spotifyTitle: String, artists: String, durationS: Int, candidate: SpotifyMapper.Candidate) =
        SpotifyMapper.selectBestMatch(
            spotifyTitle, artists.substringBefore(","), artists, durationS * 1000, listOf(candidate),
        ) is SpotifyMapper.MatchResult.Matched

    @Test
    fun nameWithAndWithoutSpace() {
        assertTrue(matches("Hot N Cold - Sped Up", "Newmagick", 116, SpotifyMapper.Candidate("a", "Hot N Cold (Sped Up)", "New Magick", 116)))
    }

    @Test
    fun latinOnSpotifyCyrillicOnTheSource() {
        assertTrue(matches("Остров", "My", 146, SpotifyMapper.Candidate("b", "Остров", "МЫ", 147)))
        assertTrue(
            matches(
                "Я поднимаюсь над землей - Krot Remix", "Basta, Alena Omargalieva, Krot", 326,
                SpotifyMapper.Candidate("c", "Я поднимаюсь над землей (Krot Remix) (feat. Алена Омаргалиева)", "Баста", 326),
            ),
        )
    }

    @Test
    fun bracketListingTheTracksOwnArtists() {
        assertTrue(
            matches(
                "Am I Dreaming (Metro Boomin & A\$AP Rocky, Roisee)", "Metro Boomin, A\$AP Rocky, Roisee", 256,
                SpotifyMapper.Candidate("d", "Am I Dreaming", "Metro Boomin", 256),
            ),
        )
    }

    @Test
    fun anotherArtistInAnotherScriptIsStillRejected() {
        assertEquals(false, matches("Остров", "My", 146, SpotifyMapper.Candidate("e", "Остров", "Олег Винник", 147)))
        assertEquals(false, matches("Hot N Cold - Sped Up", "Newmagick", 116, SpotifyMapper.Candidate("f", "Hot N Cold (Sped Up)", "Sped Up Mage", 116)))
    }
}
