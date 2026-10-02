package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportParserTest {

    @Test
    fun telegramExportKeepsOnlyAudio() {
        val json = """
            {"name":"Music chat","messages":[
              {"id":1,"type":"message","text":"hello"},
              {"id":2,"type":"message","media_type":"voice_message","duration_seconds":5},
              {"id":3,"type":"message","media_type":"audio_file","performer":"Guns N' Roses","title":"Welcome to the Jungle","duration_seconds":273,"file":"(File not included. Change data exporting settings to download.)"},
              {"id":4,"type":"message","photo":"photos/p.jpg"},
              {"id":5,"type":"message","media_type":"audio_file","file_name":"Asava - Mahjong.mp3","duration_seconds":201}
            ]}
        """.trimIndent()
        val parsed = PlaylistImportParser.parse(json)
        assertEquals(2, parsed.entries.size)
        assertEquals(PlaylistImportParser.Entry("Guns N' Roses", "Welcome to the Jungle", 273), parsed.entries[0])
        assertEquals(PlaylistImportParser.Entry("Asava", "Mahjong", 201), parsed.entries[1])
    }

    @Test
    fun m3uUsesExtinf() {
        val m3u = "#EXTM3U\n#EXTINF:257,Intercessor - Monolith\nC:\\Music\\x.mp3\nD:/Music/Asava - Will.flac\n"
        val e = PlaylistImportParser.parse(m3u).entries
        assertEquals(listOf("Intercessor – Monolith", "Asava – Will"), e.map { it.label })
        assertEquals(257, e[0].durationSec)
    }

    @Test
    fun exportifyCsv() {
        val csv = "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n" +
            "\"spotify:track:1\",\"Monolith\",\"Intercessor\",\"Giovanni's Scorn\",\"257000\"\n" +
            "\"spotify:track:2\",\"Song, With Comma\",\"A, B\",\"X\",\"100000\""
        val e = PlaylistImportParser.parse(csv).entries
        assertEquals(PlaylistImportParser.Entry("Intercessor", "Monolith", 257), e[0])
        assertEquals(PlaylistImportParser.Entry("A", "Song, With Comma", 100), e[1])
    }

    @Test
    fun plainLinesAndLinks() {
        val text = "01. Sewerslvt - Newlove\nhttps://open.spotify.com/playlist/37i9dQZF1DX\n.m0lly — concrete\njust a title"
        val p = PlaylistImportParser.parse(text)
        assertEquals(listOf("Sewerslvt – Newlove", ".m0lly – concrete", "just a title"), p.entries.map { it.label })
        assertTrue(p.links.single().contains("/playlist/"))
    }

    @Test
    fun telegramHtmlExport() {
        val html = """
            <div class="message default clearfix" id="message5"><div class="body"><div class="text">hello</div></div></div>
            <div class="media clearfix pull_left media_audio_file">
             <div class="body">
              <div class="title bold">
            Nubia &amp; Friends – Development
              </div>
              <div class="description">Not included</div>
              <div class="status details">
            05:48, 13.8 MB
              </div>
             </div>
            </div>
            <div class="media clearfix pull_left media_photo"></div>
            <div class="media clearfix pull_left media_audio_file">
             <div class="body"><div class="title bold">Intro</div><div class="status details">1:02:03, 90 MB</div></div>
            </div>
        """.trimIndent()
        val e = PlaylistImportParser.parse(html).entries
        assertEquals(PlaylistImportParser.Entry("Nubia & Friends", "Development", 348), e[0])
        assertEquals(PlaylistImportParser.Entry(null, "Intro", 3723), e[1])
    }
}
