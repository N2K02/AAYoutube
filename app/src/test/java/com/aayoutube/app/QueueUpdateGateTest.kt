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

    @Test fun boundedQueueMatchesWebViewTransportLimit() {
        val gate = QueueUpdateGate()
        assertTrue(gate.update((0 until 200).map { QItem("v$it", "Track $it", "") }))
        assertEquals(40, gate.items().size)
        assertEquals("v39", gate.items().last().id)
        assertFalse(gate.update((0 until 200).map { QItem("v$it", "Track $it", "") }))
    }
}
