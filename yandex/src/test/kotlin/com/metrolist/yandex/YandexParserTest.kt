package com.metrolist.yandex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexParserTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun trackMapsIdsVersionCoverAndAlbum() {
        val t = YandexParser.track(
            obj(
                """
                {"id": 123456, "title": "Song", "version": "Remix", "durationMs": 201345,
                 "contentWarning": "explicit",
                 "artists": [{"id": 77, "name": "Кино"}],
                 "albums": [{"id": "9001", "title": "Album", "type": "single", "trackCount": 1,
                             "coverUri": "avatars.yandex.net/get-music-content/1/ab/%%",
                             "releaseDate": "2024-05-01T00:00:00+03:00",
                             "trackPosition": {"volume": 1, "index": 3}}]}
                """.trimIndent(),
            ),
        )!!
        assertEquals("ym_123456", t.id)
        assertEquals("Song (Remix)", t.name)
        assertEquals(201345, t.durationMs)
        assertTrue(t.explicit)
        assertEquals("ym_77", t.artists.single().id)
        assertEquals("Кино", t.artists.single().name)
        assertEquals("ym_9001", t.album!!.id)
        assertEquals("single", t.album!!.albumType)
        assertEquals("2024-05-01", t.album!!.releaseDate)
        assertEquals(3, t.trackNumber)
        assertEquals("https://avatars.yandex.net/get-music-content/1/ab/400x400", t.album!!.images[1].url)
    }

    @Test
    fun versionAlreadyInTitleIsNotRepeated() {
        val t = YandexParser.track(obj("""{"id":"1","title":"Song (Remix)","version":"Remix"}"""))!!
        assertEquals("Song (Remix)", t.name)
    }

    @Test
    fun albumFlattensVolumesAndPinsAlbum() {
        val a = YandexParser.album(
            obj(
                """
                {"id": 5, "title": "Mahjong", "trackCount": 3, "year": 2023, "genre": "rock",
                 "artists": [{"id": 1, "name": "ASAVA"}],
                 "volumes": [[{"id": 10, "title": "Will", "albums": [{"id": 999, "title": "Other"}]},
                              {"id": 11, "title": "Again"}],
                             [{"id": 12, "title": "Death"}]]}
                """.trimIndent(),
            ),
        )!!
        assertEquals("ym_5", a.id)
        assertEquals("album", a.albumType)
        assertEquals("2023", a.releaseDate)
        assertEquals(listOf("ym_10", "ym_11", "ym_12"), a.tracks!!.items.map { it.id })
        assertTrue(a.tracks!!.items.all { it.album?.id == "ym_5" })
        assertEquals(listOf(1, 2, 3), a.tracks!!.items.map { it.trackNumber })
    }

    @Test
    fun playlistIdRoundTrips() {
        val p = YandexParser.playlist(
            obj("""{"uid": 42, "kind": 1003, "title": "Mix", "trackCount": 12, "owner": {"uid": 42, "login": "me"}}"""),
        )!!
        assertEquals("ym_42_1003", p.id)
        assertEquals("42" to "1003", YandexIds.parsePlaylist(p.id))
        assertEquals(12, p.tracks!!.total)
        assertNull(YandexIds.parsePlaylist("37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun likedRefsKeepAlbumForRequest() {
        val refs = YandexParser.likedTrackRefs(
            obj("""{"library": {"uid": 1, "tracks": [{"id": "5", "albumId": "6", "timestamp": "2026-01-01"}, {"id": 7}]}}"""),
        )
        assertEquals(listOf("5:6", "7"), refs.map { it.requestId })
    }

    @Test
    fun manyTrackSingleIsEp() {
        assertEquals("ep", YandexParser.albumType("single", 5))
        assertEquals("single", YandexParser.albumType("single", 1))
        assertEquals("compilation", YandexParser.albumType("compilation", 20))
    }
}
