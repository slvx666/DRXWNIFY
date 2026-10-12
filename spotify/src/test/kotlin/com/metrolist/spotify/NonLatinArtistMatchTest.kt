package com.metrolist.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Artists written without Latin letters: a transliteration may stand in for the name, a stranger may not. */
class NonLatinArtistMatchTest {
    private fun pick(title: String, artist: String, durationS: Int, vararg candidates: SpotifyMapper.Candidate) =
        SpotifyMapper.selectBestMatch(title, artist, artist, durationS * 1000, candidates.toList())

    @Test
    fun anotherUploaderWithTheSameTitleIsRejected() {
        val result = pick("Пошёл нахуй", "ХАРК!", 130, SpotifyMapper.Candidate("sc1", "Пошел нахуй", "Stuff", 130))
        assertEquals(SpotifyMapper.MatchResult.NoMatch, result)
    }

    @Test
    fun anotherBandWithTheSameTitleIsRejected() {
        assertEquals(
            SpotifyMapper.MatchResult.NoMatch,
            pick("Ты так хотела стать", "Пурген", 175, SpotifyMapper.Candidate("sc2", "Ты так хотела стать", "IJEQEZO", 175)),
        )
        assertEquals(
            SpotifyMapper.MatchResult.NoMatch,
            pick("Летний день", "Четвертое сословие", 195, SpotifyMapper.Candidate("sc3", "Летний день | Summer Day", "Naukograd", 195)),
        )
    }

    @Test
    fun transliteratedArtistIsAccepted() {
        val result = pick(
            "Пока я молод, я жив", "Скамейка Запасных", 179,
            SpotifyMapper.Candidate("bc1", "Пока я молод, я жив", "Skameyka Zapasnih", 179),
        )
        assertTrue(result is SpotifyMapper.MatchResult.Matched)
    }

    @Test
    fun artistNamedInTheTitleIsAccepted() {
        val result = pick(
            "Пока я молод, я жив", "Скамейка Запасных", 179,
            SpotifyMapper.Candidate("sc4", "Скамейка запасных - Пока я молод, я жив!", "Alexandr  Kuznetsov", 179),
        )
        assertTrue(result is SpotifyMapper.MatchResult.Matched)
    }

    @Test
    fun theRightArtistStillWins() {
        val result = pick(
            "Пошёл нахуй", "ХАРК!", 130,
            SpotifyMapper.Candidate("sc1", "Пошел нахуй", "Stuff", 130),
            SpotifyMapper.Candidate("yt1", "Пошёл нахуй", "ХАРК!", 130),
        )
        assertEquals("yt1", (result as SpotifyMapper.MatchResult.Matched).id)
    }
}

class LongTrackProbeTest {
    @Test
    fun drudkhSolitude() {
        val c = SpotifyMapper.Candidate("NSx6G_vhXYM", "Solitude (Самітність)", "Drudkh", 744)
        val r = SpotifyMapper.selectBestMatch("Solitude (Самітність)", "Drudkh", "Drudkh", 743_000, listOf(c))
        assertTrue(r is SpotifyMapper.MatchResult.Matched)
    }
}
