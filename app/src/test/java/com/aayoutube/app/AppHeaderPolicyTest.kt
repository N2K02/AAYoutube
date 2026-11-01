package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHeaderPolicyTest {

    @Test
    fun phonePagesShowTheHeader() {
        assertTrue(showAAYoutubeHeader(true, false, false, false))
        assertTrue(showAAYoutubeHeader(false, false, false, false))
    }

    @Test
    fun carShowsHeaderAndSettingsOnWatchAndSettingsPages() {
        assertTrue(showAAYoutubeHeader(true, true, false, false))
        assertTrue(showAAYoutubeHeader(false, true, false, false))
    }

    @Test
    fun fullscreenNeverShowsHeader() {
        assertFalse(showAAYoutubeHeader(true, false, false, true))
        assertFalse(showAAYoutubeHeader(false, false, false, true))
        assertFalse(showAAYoutubeHeader(false, true, false, true))
    }

    @Test
    fun pictureInPictureNeverShowsHeader() {
        assertFalse(showAAYoutubeHeader(true, false, true, false))
        assertFalse(showAAYoutubeHeader(false, false, true, false))
        assertFalse(showAAYoutubeHeader(false, true, true, false))
    }

    @Test
    fun headerReturnsWhenImmersiveModeEnds() {
        assertFalse(showAAYoutubeHeader(true, false, true, false))
        assertTrue(showAAYoutubeHeader(true, false, false, false))
        assertFalse(showAAYoutubeHeader(true, false, false, true))
        assertTrue(showAAYoutubeHeader(true, false, false, false))
    }
}
