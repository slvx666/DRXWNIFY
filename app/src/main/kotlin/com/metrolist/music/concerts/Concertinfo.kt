/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.concerts

import timber.log.Timber

/**
 * concertinfo.ru — the rock/metal poster. One page (`/filter/`) lists every upcoming gig of the
 * site as an `ev-line` block whose CSS classes carry genre ids (`t37`), the venue (`p847`) and the
 * city (`c1`); the start is `data-begin` (epoch seconds).
 */
internal object Concertinfo {
    private const val BASE = "https://concertinfo.ru"

    /** City ids of the site: 1 Saint Petersburg, 5 Leningrad oblast. */
    private val CITIES = setOf("1", "5")

    private val GENRES = mapOf(
        31 to "Acoustic", 7 to "Alternative", 41 to "Ambient", 28 to "Avant-garde", 9 to "Black Metal", 42 to "Blues",
        45 to "Country", 50 to "Cover", 27 to "Dark Metal", 40 to "Darkwave", 13 to "Death Metal", 58 to "Deathcore",
        16 to "Doom Metal", 30 to "EBM", 12 to "Electronic", 56 to "Emocore", 39 to "Experimental", 6 to "Folk",
        48 to "Funk", 49 to "Garage Rock", 34 to "Glam", 5 to "Gothic", 20 to "Grindcore", 47 to "Groove Metal",
        36 to "Grunge", 60 to "Hard Rock", 24 to "Hardcore", 14 to "Heavy Metal", 37 to "Indie", 25 to "Industrial",
        17 to "Jazz", 15 to "Melodic Metal", 4 to "Metal", 57 to "Metalcore", 53 to "Neofolk",
        29 to "Neue Deutsche Härte", 35 to "Noise", 46 to "Nu Metal", 32 to "Pop", 11 to "Post", 52 to "Power Metal",
        33 to "Progressive", 3 to "Punk", 51 to "Rap", 22 to "Reggae", 2 to "Rock", 44 to "Rock'n'roll",
        19 to "Rockabilly", 21 to "Ska", 26 to "Sludge Metal", 43 to "Stoner", 23 to "Surf", 8 to "Symphonic",
        10 to "Thrash Metal", 38 to "Trip-Hop", 54 to "Мюзикл", 1 to "Русский рок", 55 to "Шансон",
    )

    private val cityNames = mapOf("1" to "Санкт-Петербург", "5" to "Ленинградская область")
    private val blockSplit = Regex("""<div class="ev-line search_line """)
    private val classes = Regex("""^([^"]*)"""")
    private val begin = Regex("""data-begin="(\d+)"""")
    private val gigId = Regex("""class="ev-name[^"]*" href="/gig/(\d+)/"""")
    private val name = Regex("""class="ev-name[^>]*><span[^>]*>([^<]*)</span>""")
    private val venue = Regex("""<span class="ev-venue" title="([^"]*)"""")
    private val venueName = Regex("""<span class="search_cell">([^<]*)</span></span></span></span>""")
    private val price = Regex("""itemprop="price" content="(\d+)"""")
    private val firstNumber = Regex("""\d[\d\s ]*""")
    private val priceText = Regex("""<span class="ev-price">([^<]*)</span>""")
    private val thumb = Regex("""class="thumb" href="[^"]*"[^>]*><img src="([^"]*)"""")
    private val status = Regex("""eventStatus" href="https://schema\.org/(\w+)"""")

    fun fetch(get: (String) -> String): List<Concert> {
        val html = get("$BASE/filter/?city=1")
        val out = ArrayList<Concert>()
        // The page lists every upcoming gig of the site; the oblast shares it, so one request does both.
        html.split(blockSplit).drop(1).forEach { block ->
            runCatching { parse(block) }.getOrNull()?.let { out.add(it) }
        }
        Timber.d("Concerts: concertinfo gave ${out.size}")
        return out
    }

    private fun parse(block: String): Concert? {
        val cls = classes.find(block)?.groupValues?.get(1)?.split(' ').orEmpty()
        val city = cls.firstOrNull { it.length > 1 && it[0] == 'c' && it.drop(1).all(Char::isDigit) }?.drop(1) ?: return null
        if (city !in CITIES) return null
        if (status.find(block)?.groupValues?.get(1)?.contains("Cancel", true) == true) return null
        val startSec = begin.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val id = gigId.find(block)?.groupValues?.get(1) ?: return null
        val title = htmlDecode(name.find(block)?.groupValues?.get(1) ?: return null)
        val genres = cls.filter { it.length > 1 && it[0] == 't' && it.drop(1).all(Char::isDigit) }
            .mapNotNull { GENRES[it.drop(1).toInt()] }
        val place = venueName.find(block)?.groupValues?.get(1)?.let(::htmlDecode)
            ?: venue.find(block)?.groupValues?.get(1)?.substringBefore(',')?.let(::htmlDecode) ?: return null
        val rub = price.find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: priceText.find(block)?.groupValues?.get(1)?.let { t ->
                if (t.contains("беспл", true)) 0 else firstNumber.find(t)?.value?.filter(Char::isDigit)?.toIntOrNull()
            }
        val img = thumb.find(block)?.groupValues?.get(1)?.let { if (it.startsWith("http")) it else BASE + it }
        return Concert(
            id = "ci$id",
            title = title,
            start = startSec * 1000L,
            venue = place,
            locality = cityNames[city] ?: "",
            price = rub,
            genres = genres,
            imageUrl = img,
            url = "$BASE/gig/$id/",
            sources = listOf("Concertinfo"),
        )
    }

    private fun htmlDecode(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()
}
