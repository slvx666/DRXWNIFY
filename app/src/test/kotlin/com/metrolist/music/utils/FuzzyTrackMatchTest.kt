package com.metrolist.music.utils

import com.metrolist.music.utils.FuzzyTrackMatch.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTrackMatchTest {

    @Test
    fun guestsInTitleAreArtists() {
        assertEquals(listOf("Orthodox", "Adam Easterling"), FuzzyTrackMatch.featuredArtists("Tears on Lambo Leather (feat. Orthodox & Adam Easterling)"))
        val best = FuzzyTrackMatch.best(
            "The Callous Daoboys", "Tears on Lambo Leather (feat. Orthodox & Adam Easterling)", null,
            listOf(
                Candidate("other", "Beautiful Dude Missile", listOf("The Callous Daoboys"), 200),
                Candidate("right", "Tears on Lambo Leather", listOf("The Callous Daoboys", "Orthodox", "Adam Easterling"), 230),
            ),
        )
        assertEquals("right", best?.id)
    }

    @Test
    fun yearAndTrackNumberAreDropped() {
        assertTrue("The World We Saved" in FuzzyTrackMatch.titleVariants("The World We Saved 2025"))
        assertTrue("Country Song in Reverse" in FuzzyTrackMatch.titleVariants("III. Country Song in Reverse (feat. low before the breeze)"))
        val best = FuzzyTrackMatch.best("Mechina", "The World We Saved 2025", null, listOf(Candidate("right", "The World We Saved", listOf("Mechina"), 300)))
        assertEquals("right", best?.id)
    }

    @Test
    fun typoStillMatches() {
        val best = FuzzyTrackMatch.best(
            "Amorphis", "Dispair", null,
            listOf(Candidate("x", "House of Sleep", listOf("Amorphis"), 250), Candidate("right", "Despair", listOf("Amorphis"), 240)),
        )
        assertEquals("right", best?.id)
    }

    @Test
    fun otherSongOrCoverIsRefused() {
        assertNull(FuzzyTrackMatch.best("Varia", "Moonlight", null, listOf(Candidate("x", "Sunlight Theory", listOf("Varia"), 200))))
        assertNull(FuzzyTrackMatch.best("Volumes", "Suffer On", null, listOf(Candidate("x", "Suffer On (Cover)", listOf("Volumes"), 200))))
        assertNull(FuzzyTrackMatch.best("Volumes", "Suffer On", null, listOf(Candidate("x", "Suffer On", listOf("Someone Else"), 200))))
    }

    @Test
    fun lengthFarOffIsRefused() {
        assertNull(FuzzyTrackMatch.best("Kardashev", "Speak Silence", 260, listOf(Candidate("x", "Speak Silence", listOf("Kardashev"), 420))))
    }
}
