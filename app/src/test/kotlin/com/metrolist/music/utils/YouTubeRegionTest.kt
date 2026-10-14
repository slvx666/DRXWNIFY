package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeRegionTest {
    @Test
    fun countryIsReadFromTheVisitorId() {
        // A real visitor id handed out to a German address.
        assertEquals("DE", YouTubeRegion.countryOf("CgtBX1pxODhuT0FDcyjt8LjWBjIKCgJERRIEEgAg"))
        assertEquals("DE", YouTubeRegion.countryOf("CgtBX1pxODhuT0FDcyjt8LjWBjIKCgJERRIEEgAg%3D%3D"))
    }

    @Test
    fun garbageGivesNoCountry() {
        assertNull(YouTubeRegion.countryOf("not base64 at all"))
        assertNull(YouTubeRegion.countryOf(""))
    }
}
