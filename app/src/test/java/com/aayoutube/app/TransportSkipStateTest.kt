package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class TransportSkipStateTest {
    private val queue = listOf(
        QItem("a", "A", ""), QItem("b", "B", ""),
        QItem("c", "C", ""), QItem("d", "D", "")
    )

    @Test fun repeatedNextAdvancesFromPendingTarget() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        assertEquals("c", state.next("a", queue, 1_100))
        assertEquals("d", state.next("", emptyList(), 1_200))
        assertNull(state.next("", emptyList(), 1_300))
    }

    @Test fun previousReversesPendingNextBeforePlaybackAcknowledged() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        assertEquals("a", state.previous("a", queue, 1_100))
    }

    @Test fun staleReportsDoNotUndoPendingTarget() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        state.onPlaybackState("a", true)
        assertEquals("c", state.next("a", queue, 1_100))
    }

    @Test fun arrivalAndTimeoutResetPrediction() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        state.onPlaybackState("b", true)
        assertEquals("c", state.next("b", queue, 1_100))
        assertEquals("b", state.next("a", queue, 10_000))
    }

    @Test fun clearDropsPendingAfterExplicitPauseOrSelection() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        state.clear()
        assertEquals("b", state.next("a", queue, 1_100))
    }

    @Test fun previousRestartsOnlyStablePlaybackBeyondFiveSeconds() {
        val state = TransportSkipState()
        assertFalse(state.shouldRestartPrevious("b", 5_000L, 1_000L))
        assertTrue(state.shouldRestartPrevious("b", 5_001L, 1_000L))
        assertFalse(state.shouldRestartPrevious("", 20_000L, 1_000L))
        assertEquals("c", state.next("b", queue, 1_100L))
        assertFalse(state.shouldRestartPrevious("b", 20_000L, 1_200L))
        assertEquals("b", state.previous("b", queue, 1_250L))
    }

    @Test fun previousReturnsToRestartModeAfterTargetConfirmedOrExpired() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000L))
        assertTrue(state.hasPending(1_001L))
        state.onPlaybackState("b", true)
        assertFalse(state.hasPending(1_002L))
        assertTrue(state.shouldRestartPrevious("b", 9_000L, 1_002L))
        assertEquals("c", state.next("b", queue, 2_000L))
        assertFalse(state.hasPending(10_000L))
        assertTrue(state.shouldRestartPrevious("b", 9_000L, 10_000L))
    }

    @Test fun pendingTargetOnlyConfirmsOncePlaybackActuallyBegins() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000L))
        state.onPlaybackState("b", false)
        assertTrue(state.hasPending(1_100L))
        assertEquals("c", state.next("", emptyList(), 1_150L))
        state.onPlaybackState("c", true)
        assertFalse(state.hasPending(1_200L))
    }

    @Test fun newQueueSnapshotSupersedesOldPendingQueueWhenTargetStillExists() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000L))
        val reordered = listOf(QItem("b", "B", ""), QItem("d", "D", ""))
        assertEquals("d", state.next("a", reordered, 1_100L))
    }

    @Test fun unrelatedQueueDoesNotCauseUnexpectedJump() {
        val state = TransportSkipState()
        assertNull(state.next("unknown", queue, 1_000))
        assertNull(state.previous("unknown", queue, 1_100))
    }
}
