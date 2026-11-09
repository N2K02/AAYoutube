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
        state.onPlaybackState("a")
        assertEquals("c", state.next("a", queue, 1_100))
    }

    @Test fun arrivalAndTimeoutResetPrediction() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        state.onPlaybackState("b")
        assertEquals("c", state.next("b", queue, 1_100))
        assertEquals("b", state.next("a", queue, 10_000))
    }

    @Test fun clearDropsPendingAfterExplicitPauseOrSelection() {
        val state = TransportSkipState()
        assertEquals("b", state.next("a", queue, 1_000))
        state.clear()
        assertEquals("b", state.next("a", queue, 1_100))
    }

    @Test fun unrelatedQueueDoesNotCauseUnexpectedJump() {
        val state = TransportSkipState()
        assertNull(state.next("unknown", queue, 1_000))
        assertNull(state.previous("unknown", queue, 1_100))
    }
}
