/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.spotify

/**
 * Latin letters with a stroke or bar and ligatures, which Unicode doesn't decompose into a base
 * letter plus a mark: "LARCɆNIA ROɆ" on Spotify is "Larcenia Roe" everywhere else, and the two never
 * matched. Applied to lower-case text, before diacritics are folded away.
 */
object LetterFold {
    private val MAP = mapOf(
        'ɇ' to "e", 'ø' to "o", 'đ' to "d", 'ð' to "d", 'ł' to "l", 'ħ' to "h", 'ŧ' to "t",
        'ƀ' to "b", 'ɍ' to "r", 'ʉ' to "u", 'ɏ' to "y", 'ɨ' to "i", 'ı' to "i", 'ȼ' to "c",
        'ɉ' to "j", 'ƶ' to "z", 'ǥ' to "g", 'ɫ' to "l", 'ƚ' to "l", 'ᵾ' to "u", 'ß' to "ss",
        'æ' to "ae", 'œ' to "oe", 'þ' to "th",
    )

    fun fold(lowerCase: String): String {
        if (lowerCase.none { it in MAP }) return lowerCase
        val sb = StringBuilder(lowerCase.length + 4)
        for (ch in lowerCase) MAP[ch]?.let(sb::append) ?: sb.append(ch)
        return sb.toString()
    }
}
