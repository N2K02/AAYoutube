package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUpdateGateTest {
    @Test fun unchangedMetadataIsNotRepublished() {
        val gate = MediaUpdateGate()
        assertTrue(gate.metadataChanged("video", "Title", "Artist", "https://image", 60_000))
        assertFalse(gate.metadataChanged("video", "Title", "Artist", "https://image", 60_000))
        assertTrue(gate.metadataChanged("video", "New title", "Artist", "https://image", 60_000))
        assertTrue(gate.metadataChanged("video", "New title", "Artist", "https://new-image", 60_000))
        assertTrue(gate.metadataChanged("other", "New title", "Artist", "https://new-image", 60_000))
        assertFalse(gate.metadataChanged("other", "New title", "Artist", "https://new-image", 60_000))
        gate.invalidateMetadata()
        assertTrue(gate.metadataChanged("other", "New title", "Artist", "https://new-image", 60_000))
    }

    @Test fun extrapolatedPositionsDoNotFloodMediaSession() {
        val gate = MediaUpdateGate()
        assertTrue(gate.playbackChanged(3, 7L, 1_000, 1f, 1_000))
        assertFalse(gate.playbackChanged(3, 7L, 1_500, 1f, 1_500))
        assertFalse(gate.playbackChanged(3, 7L, 2_000, 1f, 2_000))
        assertTrue(gate.playbackChanged(3, 7L, 11_000, 1f, 11_000))
        assertFalse(gate.playbackChanged(3, 7L, 12_000, 1f, 12_000))
    }

    @Test fun changedActionsStateAndSeekingAreImmediate() {
        val gate = MediaUpdateGate()
        assertTrue(gate.playbackChanged(3, 7L, 1_000, 1f, 1_000))
        assertTrue(gate.playbackChanged(3, 15L, 1_500, 1f, 1_500))
        assertTrue(gate.playbackChanged(2, 15L, 1_500, 0f, 2_000))
        assertFalse(gate.playbackChanged(2, 15L, 1_500, 0f, 2_500))
        assertTrue(gate.playbackChanged(2, 15L, 1_600, 0f, 3_000))
        assertTrue(gate.playbackChanged(3, 15L, 1_600, 1.25f, 3_500))
        assertTrue(gate.playbackChanged(3, 15L, 20_000, 1.25f, 4_000))
    }
}
