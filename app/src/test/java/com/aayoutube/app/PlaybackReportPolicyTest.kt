package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportPolicyTest {
    @Test fun lateReportFromThePreviousVideoIsDropped() {
        assertTrue(PlaybackReportPolicy.stale("A", expectedId = "B", navigatedAwayFromId = "A"))
    }

    @Test fun playerAheadOfTheAddressBarStillUpdatesPlayState() {
        // Mix/autoplay: player plays C while the URL still says B.
        assertFalse(PlaybackReportPolicy.stale("C", expectedId = "B", navigatedAwayFromId = "A"))
        assertFalse(PlaybackReportPolicy.stale("C", expectedId = "B", navigatedAwayFromId = null))
    }

    @Test fun matchingOrUnknownExpectationIsNeverStale() {
        assertFalse(PlaybackReportPolicy.stale("B", expectedId = "B", navigatedAwayFromId = "A"))
        assertFalse(PlaybackReportPolicy.stale("A", expectedId = null, navigatedAwayFromId = "A"))
    }
}
