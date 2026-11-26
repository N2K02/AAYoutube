package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class MirrorSchedulerHealthTest {
    @Test fun earliestDeadlineWinsUntilConsumedOrCancelled() {
        val s = MirrorScheduler()
        assertEquals(500L, s.request(1000, 500))
        assertNull(s.request(1100, 500))
        assertEquals(100L, s.request(1200, 100))
        assertNull(s.request(1250, 100))
        s.clear()
        assertEquals(0L, s.request(1300, -1))
        s.clear()
        assertEquals(500L, s.request(1400, 500))
    }

    @Test fun temporaryFailuresBackOffAndRecoverAfterNewEngineGeneration() {
        val h = MirrorHealth()
        assertTrue(h.observeGeneration(1))
        h.backoff(1000)
        assertTrue(h.waiting(5999))
        assertFalse(h.waiting(6000))
        h.backoff(6000)
        assertEquals(10000L, h.remaining(6000))
        assertFalse(h.observeGeneration(1))
        assertTrue(h.observeGeneration(2))
        assertFalse(h.waiting(6001))
        h.backoff(7000)
        assertEquals(5000L, h.remaining(7000))
        h.success()
        assertFalse(h.waiting(7000))
    }

    @Test fun cooldownIsBoundedAndResettable() {
        val h = MirrorHealth()
        repeat(10) { h.backoff(1000L + it) }
        assertEquals(40000L, h.remaining(1009))
        h.reset()
        assertFalse(h.waiting(1010))
    }

    @Test fun navigationAuthorizationIsConsumedOnceOnlyOnMatchingDestination() {
        val n = NavAuthorizer()
        n.onIntent("watch?v=123", 1000)
        assertFalse(n.authorize("watch?v=456", 1001))
        assertTrue(n.matches("watch?v=123", 1002))
        assertTrue(n.authorize("watch?v=123", 1003))
        assertFalse(n.authorize("watch?v=123", 1004))
    }
}
