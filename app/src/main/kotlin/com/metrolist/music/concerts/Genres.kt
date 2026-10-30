/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.concerts

import java.time.LocalDate
import java.util.Locale

/**
 * The sources tag genres their own way (Concertinfo in English, Ковельт in lower-case Russian);
 * everything the app filters on is one of the broad families below.
 */
object Genres {
    const val REGION_SPB = "Санкт-Петербург"
    const val REGION_OBLAST = "Ленобласть"

    /** Order of the filter chips. */
    val ORDER = listOf(
        "Метал", "Рок", "Панк", "Хардкор", "Альтернатива", "Экспериментальная", "Готика", "Электроника",
        "Джаз/блюз", "Фолк", "Акустика", "Ска/регги", "Симфоническая", "Кавер", "Поп", "Рэп", "Шансон", "Мюзикл",
    )

    /** What the home recommendations look for until the user saves their own. */
    val DEFAULT_TASTE = setOf("Метал", "Рок", "Панк", "Хардкор", "Альтернатива", "Экспериментальная", "Готика", "Джаз/блюз")

    /** Families that, alone, make a gig unwanted. */
    val UNWANTED = setOf("Поп", "Рэп", "Шансон", "Мюзикл")

    private val rules: List<Pair<String, List<String>>> = listOf(
        "Метал" to listOf("metal", "метал", "grind", "doom", "sludge", "deathcore", "metalcore", "грайнд", "дум"),
        "Хардкор" to listOf("hardcore", "хардкор", "emocore", "эмокор", "metalcore", "deathcore", "core"),
        "Панк" to listOf("punk", "панк", "ска-панк"),
        "Рок" to listOf("rock", "рок", "grunge", "гранж", "stoner", "стоунер", "glam", "rockabilly", "surf", "garage"),
        "Альтернатива" to listOf("alternative", "альтернатив", "indie", "инди", "shoegaze", "шугейз"),
        "Экспериментальная" to listOf("post", "пост", "noise", "нойз", "experimental", "эксперимент", "avant", "авангард", "ambient", "эмбиент", "progressive", "прогрес", "math", "мат-рок", "instrumental", "инструментал"),
        "Готика" to listOf("gothic", "готик", "industrial", "индастриал", "ebm", "darkwave", "дарквейв", "neue deutsche", "ndh", "dark metal"),
        "Электроника" to listOf("electronic", "электрон", "techno", "техно", "house", "хаус", "trip-hop", "синтивейв", "synth"),
        "Джаз/блюз" to listOf("jazz", "джаз", "blues", "блюз", "soul", "соул", "funk", "фанк"),
        "Фолк" to listOf("folk", "фолк", "бард", "country", "кантри", "ethno", "этно", "neofolk"),
        "Акустика" to listOf("acoustic", "акустик"),
        "Ска/регги" to listOf("reggae", "регги", "ska", "ска"),
        "Симфоническая" to listOf("symphonic", "симфон", "classical", "классик"),
        "Кавер" to listOf("cover", "кавер", "tribute", "трибьют"),
        "Поп" to listOf("pop", "поп", "эстрад"),
        "Рэп" to listOf("rap", "реп", "рэп", "hip-hop", "хип-хоп"),
        "Шансон" to listOf("шансон", "chanson"),
        "Мюзикл" to listOf("мюзикл", "musical"),
    )

    fun families(genres: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (g in genres) {
            val l = g.lowercase(Locale.ROOT)
            for ((family, keys) in rules) {
                // "pop" must not fire on "post-…", "rock" is fine anywhere.
                if (keys.any { k -> if (k.length <= 3) l.split(' ', '/', '-', ',').any { it == k } else l.contains(k) }) out.add(family)
            }
        }
        return out
    }

    /** Families present in [list], in chip order. */
    fun presentIn(list: List<Concert>): List<String> {
        val present = list.flatMapTo(HashSet()) { it.families }
        return ORDER.filter { it in present }
    }

    /** Whether [c] fits the taste: a wanted family, and not only unwanted ones. */
    fun fits(c: Concert, taste: Set<String>): Boolean {
        val f = c.families
        if (f.isEmpty()) return false
        return f.any { it in taste }
    }

    /**
     * The home picks: the coming [days] days, gigs of the taste at most [maxPrice] (free first by
     * time), then — only to fill the row — those whose price isn't known.
     */
    fun recommend(all: List<Concert>, taste: Set<String>, maxPrice: Int, days: Long = 7, limit: Int = 12): List<Concert> {
        val today = LocalDate.now(Concert.ZONE)
        val window = all.filter { !it.date.isBefore(today) && it.date.isBefore(today.plusDays(days)) && fits(it, taste) }
        val cheap = window.filter { it.price != null && it.price <= maxPrice }.sortedBy { it.start }
        val unknown = window.filter { it.price == null }.sortedBy { it.start }
        return (cheap + unknown).take(limit)
    }
}
