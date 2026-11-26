package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class QueueUpdateGateTest {
    @Test fun noNotificationOnAnUnchangedQueue() {
        val gate = QueueUpdateGate()
        assertFalse(gate.update(emptyList()))
        assertTrue(gate.update(listOf(QItem("v1", "Track", "Artist"))))
        assertFalse(gate.update(listOf(QItem("v1", "Track", "Artist"))))
        assertEquals("v1", gate.items().single().id)
        assertTrue(gate.update(emptyList()))
        assertTrue(gate.items().isEmpty())
    }

    @Test fun blankAndRepeatedVideoIdsAreFiltered() {
        val gate = QueueUpdateGate()
        assertTrue(gate.update(listOf(
            QItem("", "Invalid", ""),
            QItem("v1", "Track 1", ""),
            QItem("v1", "Duplicate", ""),
            QItem("v2", "Track 2", "")
        )))
        assertEquals(listOf("v1", "v2"), gate.items().map { it.id })
        assertEquals("Track 1", gate.items().first().title)
        assertFalse(gate.update(listOf(QItem("v1", "Track 1", ""), QItem("v2", "Track 2", ""))))
        assertTrue(gate.update(listOf(QItem("v1", "New title", ""), QItem("v2", "Track 2", ""))))
    }

    @Test fun transientEmptyReportsPreserveQueueUntilTransitionFinishes() {
        val gate = QueueUpdateGate()
        val tracks = listOf(QItem("v1", "First", ""), QItem("v2", "Second", ""))
        assertTrue(gate.update(tracks))
        assertFalse(gate.update(emptyList(), retainDuringTransition = true))
        assertEquals(tracks, gate.items())
        assertTrue(gate.update(emptyList(), retainDuringTransition = false))
        assertTrue(gate.items().isEmpty())
    }

    @Test fun newNonemptyQueueSupersedesRetainedQueueDuringTransition() {
        val gate = QueueUpdateGate()
        assertTrue(gate.update(listOf(QItem("v1", "First", ""))))
        val next = listOf(QItem("v2", "Second", ""))
        assertTrue(gate.update(next, retainDuringTransition = true))
        assertEquals(next, gate.items())
    }

    @Test fun boundedQueueMatchesWebViewTransportLimit() {
        val gate = QueueUpdateGate()
        assertTrue(gate.update((0 until 200).map { QItem("v$it", "Track $it", "") }))
        assertEquals(40, gate.items().size)
        assertEquals("v39", gate.items().last().id)
        assertFalse(gate.update((0 until 200).map { QItem("v$it", "Track $it", "") }))
    }
}
