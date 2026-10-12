package com.metrolist.innertube

import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual probe of YouTube Music search (network): run with SEARCH_PROBE=1. */
class SearchProbeTest {
    @Test
    fun probe() {
        assumeTrue(System.getenv("SEARCH_PROBE") == "1")
        val queries = System.getenv("PROBE_Q")?.split("|") ?: listOf(
            "Drudkh Solitude (Самітність)",
            "Drudkh Solitude",
            "Psyche Je T'Aime",
            "Psyche Je T'Aime (feat. Sara Noxx)",
            "Old Style Values Хочу",
            "ХАРК! Пошёл нахуй",
        )
        System.getenv("PROBE_GL")?.let { gl -> YouTube.locale = com.metrolist.innertube.models.YouTubeLocale(gl = gl, hl = System.getenv("PROBE_HL") ?: "ru") }
        runBlocking {
            for (q in queries) {
                val items = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG, incognito = true).getOrNull()?.items.orEmpty()
                println("=== $q (${items.size})")
                items.filterIsInstance<SongItem>().take(6).forEach { s ->
                    println("  ${s.id} | ${s.title} | ${s.artists.joinToString { it.name + "(" + it.id + ")" }} | ${s.album?.name} | ${s.duration}s | explicit=${s.explicit}")
                }
            }
        }
    }
}
