package com.metrolist.music.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHomeFilterTest {
    @Test
    fun theSectionsAskedToGoAreLeftOut() {
        listOf(
            "Albums featuring songs you like",
            "Albums by artists you follow",
            "Your favorite artists",
            "Your favourite artists",
            "More like Sewerslvt",
            "Recently played",
            "Jump back in",
            "Альбомы с треками, которые вам нравятся",
            "Альбомы исполнителей, на которых вы подписаны",
            "Ваши любимые исполнители",
            "Похоже на Sewerslvt",
            "Недавно прослушано",
            "Снова в эфире",
            "Пятничные новинки для тебя",
            "New Music Friday for you",
        ).forEach { assertTrue(it, SpotifyHomeFilter.isRemovedSection(it)) }
    }

    @Test
    fun theOtherSectionsStay() {
        listOf(
            "Made For You",
            "Your top mixes",
            "Created for drxwnslvt",
            "Создано для drxwnslvt",
            "Твои лучшие миксы",
            "Popular radio",
            "Fresh new music",
        ).forEach { assertFalse(it, SpotifyHomeFilter.isRemovedSection(it)) }
    }

    private val artists = listOf("Sewerslvt", "Deftones", "Drudkh")
    private val genres = listOf("Black Metal", "Breakcore", "Shoegaze")

    @Test
    fun personalMixesAndMixesOfOwnArtistsOrGenresStay() {
        listOf(
            "Daily Mix 3", "Discover Weekly", "Release Radar", "On Repeat", "daylist • melancholic night",
            "Микс дня 2", "Открытия недели", "Радар новинок",
            "Sewerslvt Mix", "Black Metal Mix", "Breakcore микс", "Микс: Deftones",
        ).forEach { assertTrue(it, SpotifyHomeFilter.isOwnMix(it, artists, genres)) }
    }

    @Test
    fun editorialAndForeignMixesGo() {
        listOf("Happy Hits!", "Chill Mix", "Pop Mix", "Today's Top Hits", "Mood Booster", "Hip-Hop Mix", "Rock Mix")
            .forEach { assertFalse(it, SpotifyHomeFilter.isOwnMix(it, artists, genres)) }
    }
}
