package com.metrolist.music.portrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTest {
    private fun s(seconds: Int) = seconds * 1000L

    @Test
    fun halfTheTrackBetweenThirtySecondsAndFourMinutes() {
        Listening.floorMs = Listening.DEFAULT_FLOOR_MS
        assertEquals(s(75), Listening.neededMs(s(150)))
        assertEquals(s(105), Listening.neededMs(s(210)))
        assertEquals(s(180), Listening.neededMs(s(360)))
        assertEquals(s(240), Listening.neededMs(s(720)))
        assertEquals(s(30), Listening.neededMs(s(45)))
        assertEquals(s(18), Listening.neededMs(s(20)))
        assertEquals(s(60), Listening.neededMs(0))
    }

    @Test
    fun aSkippedTrackDoesNotCount() {
        Listening.floorMs = Listening.DEFAULT_FLOOR_MS
        assertFalse(Listening.counts(s(40), s(210)))
        assertTrue(Listening.counts(s(110), s(210)))
    }

    @Test
    fun aHigherSettingRaisesTheFloor() {
        Listening.floorMs = s(60)
        assertEquals(s(60), Listening.neededMs(s(90)))
        Listening.floorMs = Listening.DEFAULT_FLOOR_MS
    }
}
