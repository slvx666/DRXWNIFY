/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.reviews

import java.util.Locale

/**
 * The site's "country" is whatever the post or Last.fm said: a country in Russian ("Швеция"), one
 * in English ("Czechia"), or just a city ("Örebro", "Salt Lake City"). Only real countries are kept,
 * as an ISO code, and shown in the app's language; a city (no way to tell its country offline) is
 * dropped, so the filter never lists places that aren't countries. New posts are handled the same
 * way: every country name Android knows, in English and Russian, is recognised.
 */
object Countries {
    private val byName: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val locale = Locale("", code)
            for (language in listOf(Locale.ENGLISH, Locale("ru"))) {
                map[key(locale.getDisplayCountry(language))] = code
            }
            map[key(code)] = code
        }
        ALIASES.forEach { (name, code) -> map[key(name)] = code }
        map
    }

    private val ALIASES = mapOf(
        "США" to "US", "USA" to "US", "United States of America" to "US", "Америка" to "US",
        "UK" to "GB", "Великобритания" to "GB", "England" to "GB", "Англия" to "GB", "Scotland" to "GB",
        "Шотландия" to "GB", "Wales" to "GB", "Уэльс" to "GB", "Britain" to "GB",
        "Czechia" to "CZ", "Чехия" to "CZ", "Russia" to "RU", "Россия" to "RU",
        "Holland" to "NL", "Нидерланды" to "NL", "Голландия" to "NL", "South Korea" to "KR",
        "Корея" to "KR", "Южная Корея" to "KR", "ОАЭ" to "AE", "UAE" to "AE",
    )

    private fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}]+"), "")

    /** The ISO code for [raw], or "" when it isn't a country. */
    fun normalize(raw: String): String = if (raw.isBlank()) "" else byName[key(raw)].orEmpty()

    /** [code] as people say it in the app's language ("США", not "Соединенные Штаты"). */
    fun displayName(code: String): String {
        val language = Locale.getDefault().language
        return when {
            code == "US" -> if (language == "ru") "США" else "USA"
            code == "GB" -> if (language == "ru") "Великобритания" else "UK"
            else -> Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
        }
    }
}
