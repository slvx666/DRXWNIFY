/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.home

/**
 * What of Spotify's own home feed the home page keeps (pure, so it is unit tested).
 *
 * Left out: "Albums featuring songs you like", "Albums by artists you follow", "Your favourite
 * artists", "More like …" (the app's own rows do these from what is really loved) and "Recently
 * played" / "Jump back in" (history, not something new), "Friday releases for you" (the user didn't
 * want it). Titles come in the app's language, so
 * both the English and the Russian wordings are matched.
 *
 * Spotify's own playlists are kept only when they are the listener's: the personal mixes (Daily
 * Mix, Discover Weekly, Release Radar…) and mixes named after one of the listener's artists or
 * genres ("Sewerslvt Mix", "Black Metal Mix"). Editorial ones ("Happy Hits!", "Chill Mix" for
 * someone who doesn't listen to chill) go.
 */
object SpotifyHomeFilter {
    private val REMOVED_SECTIONS = listOf(
        Regex("songs you like"),
        Regex("artists you follow"),
        Regex("favou?rite artists"),
        Regex("^more like"),
        Regex("recently played"),
        Regex("jump back in"),
        Regex("альбом.*(нрав|любим)"),
        Regex("(нрав|любим).*альбом"),
        Regex("подписан"),
        Regex("любим\\S* исполнител"),
        Regex("похож"),
        Regex("недавно"),
        Regex("^(слушать )?снова"),
        // "Пятничные новинки для тебя" / "New Music Friday for you": Friday's releases, a repeat of
        // "New releases" (and of editorial picks) — not wanted on the home page.
        Regex("friday"),
        Regex("пятнич"),
    )

    fun isRemovedSection(title: String?): Boolean {
        val t = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        return REMOVED_SECTIONS.any { it.containsMatchIn(t) }
    }

    private val PERSONAL_MIX = Regex(
        "daily mix|discover weekly|release radar|on repeat|repeat rewind|daylist|time capsule|blend|" +
            "микс дня|открытия недели|радар новинок|на повторе|капсула времени",
        RegexOption.IGNORE_CASE,
    )

    // "<subject> Mix" / "<subject> микс" / "Mix: <subject>" / "Микс: <subject>".
    private val SUFFIX_MIX = Regex("^(.+?)\\s+(mix|микс)$", RegexOption.IGNORE_CASE)
    private val PREFIX_MIX = Regex("^(mix|микс)\\s*[:\\-–—]?\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun isOwnMix(name: String, artists: Collection<String>, genres: Collection<String>): Boolean {
        val n = name.trim()
        if (PERSONAL_MIX.containsMatchIn(n)) return true
        val subject = (SUFFIX_MIX.find(n)?.groupValues?.get(1) ?: PREFIX_MIX.find(n)?.groupValues?.get(2))
            ?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return (artists + genres).any { raw ->
            val key = raw.trim().lowercase()
            key.isNotEmpty() && (key == subject || (key.length >= 4 && subject.length >= 4 && (subject in key || key in subject)))
        }
    }
}
