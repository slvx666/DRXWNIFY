/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.concerts

import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime

/**
 * kovelt.ru — the poster of Saint Petersburg's independent venues. It reads 67 venue channels
 * (VK / Telegram), the venues' own sites and KudaGo four times a day, which is where free and
 * local gigs show up first. A day page lists every event as an `<article class="ev">` with the
 * kind, genres, a "free" flag, the venue and the link to the original announcement.
 */
internal object Kovelt {
    private const val BASE = "https://kovelt.ru"
    private const val DAYS = 14

    /** What the site calls the music nights; theatre, exhibitions, films … are left out. */
    private val MUSIC_KINDS = setOf("Концерт", "Фестиваль", "Вечеринка")
    private val NO_KIND = setOf("", "Событие")

    private val article = Regex("""<article class="ev" ([^>]*)>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
    private val attr = Regex("""data-(\w+)="([^"]*)"""")
    private val time = Regex("""<div class="tm">(\d{1,2}):(\d{2})</div>""")
    private val title = Regex("""<h3 class="t"><a href="(/e/[^"]+)">([^<]*)</a>""")
    private val venue = Regex("""<a class="vn" href="[^"]*">([^<]*)</a>""")
    private val source = Regex("""class="src">[^<]*<a href="(https?://[^"]+)"""")

    fun fetch(get: (String) -> String): List<Concert> {
        val today = LocalDate.now(Concert.ZONE)
        val seen = HashSet<String>()
        val out = ArrayList<Concert>()
        var ok = 0
        for (i in 0 until DAYS) {
            val page = runCatching { get("$BASE/d/${today.plusDays(i.toLong())}/") }.getOrNull() ?: continue
            ok++
            article.findAll(page).forEach { m ->
                val c = runCatching { parse(m.groupValues[1], m.groupValues[2]) }.getOrNull() ?: return@forEach
                if (seen.add(c.id)) out.add(c)
            }
        }
        if (ok == 0) error("kovelt: no day page loaded")
        Timber.d("Concerts: kovelt gave ${out.size}")
        return out
    }

    private fun parse(attrs: String, body: String): Concert? {
        val a = attr.findAll(attrs).associate { it.groupValues[1] to it.groupValues[2] }
        val kind = htmlDecode(a["k"].orEmpty())
        val genres = a["g"].orEmpty().split('|').map { htmlDecode(it).trim() }.filter { it.isNotEmpty() }
        // A night without a stated kind counts only when the site tagged a genre on it.
        val music = kind in MUSIC_KINDS || (kind in NO_KIND && genres.isNotEmpty())
        if (!music) return null
        val date = runCatching { LocalDate.parse(a["d"]) }.getOrNull() ?: return null
        val t = time.find(body)
        val clock = t?.let { LocalTime.of(it.groupValues[1].toInt().coerceIn(0, 23), it.groupValues[2].toInt().coerceIn(0, 59)) }
            ?: LocalTime.NOON
        val link = title.find(body) ?: return null
        val place = venue.find(body)?.groupValues?.get(1)?.let(::htmlDecode)?.takeIf { it.isNotBlank() } ?: return null
        val id = a["id"] ?: return null
        return Concert(
            id = "kv$id",
            title = htmlDecode(link.groupValues[2]),
            start = date.atTime(clock).atZone(Concert.ZONE).toInstant().toEpochMilli(),
            venue = place,
            locality = "Санкт-Петербург",
            // The site states a price only as "free"; anything else is unknown.
            price = if (a["free"] == "1") 0 else null,
            genres = genres.map { it.replaceFirstChar { c -> c.uppercase() } },
            url = BASE + link.groupValues[1],
            altUrl = source.find(body)?.groupValues?.get(1),
            sources = listOf("Ковельт"),
            timeKnown = t != null,
        )
    }

    private fun htmlDecode(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()
}
