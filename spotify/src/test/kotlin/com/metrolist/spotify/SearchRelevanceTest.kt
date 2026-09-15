package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRelevanceTest {
    private fun artist(name: String) = SpotifyArtist(id = name, name = name)
    private fun track(name: String, vararg artists: String) =
        SpotifyTrack(id = name + artists.joinToString(), name = name, artists = artists.map { SpotifySimpleArtist(name = it) })

    @Test
    fun exactArtistComesFirstAndLookalikesAreDropped() {
        val ranked = SearchRelevance.rankArtists(".m0lly", listOf(artist("Molly"), artist("m0lly lover"), artist(".m0lly"), artist("Holly")))
        assertEquals(listOf(".m0lly", "m0lly lover"), ranked.map { it.name })
        assertTrue(SearchRelevance.isArtistQuery(".m0lly", ranked))
    }

    @Test
    fun songsByTheArtistBeatUnrelatedPopularSongs() {
        val tracks = listOf(
            track("Popular Song", "Someone"),
            track("concrete", ".m0lly"),
            track("m0lly", "Other"),
            track("/bin", ".m0lly"),
        )
        val ranked = SearchRelevance.rankTracks(".m0lly", tracks)
        assertEquals(listOf("m0lly", "concrete", "/bin"), ranked.map { it.name })
    }

    @Test
    fun cyrillicQueriesAreRanked() {
        val albums = listOf(
            SpotifyAlbum(id = "1", name = "Группа крови", artists = listOf(SpotifySimpleArtist(name = "Кино"))),
            SpotifyAlbum(id = "2", name = "Кинофильм", artists = listOf(SpotifySimpleArtist(name = "X"))),
            SpotifyAlbum(id = "3", name = "Другое", artists = listOf(SpotifySimpleArtist(name = "Y"))),
        )
        // Exact artist "Кино" (95) outranks a title that merely starts with the query (80).
        assertEquals(listOf("1", "2"), SearchRelevance.rankAlbums("кино", albums).map { it.id })
    }

    @Test
    fun nothingRelevantKeepsCatalogOrder() {
        val ranked = SearchRelevance.rankArtists("zzz", listOf(artist("A"), artist("B")))
        assertEquals(listOf("A", "B"), ranked.map { it.name })
    }
}
