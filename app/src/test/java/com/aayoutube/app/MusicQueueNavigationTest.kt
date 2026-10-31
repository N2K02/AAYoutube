package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class MusicQueueNavigationTest {
    private val items = listOf(
        QItem("a", "A", ""), QItem("b", "B", ""), QItem("c", "C", ""))

    @Test fun forwardAndBackwardFollowVisibleQueue() {
        assertEquals("b", MusicQueueNavigation.next("a", items))
        assertEquals("c", MusicQueueNavigation.next("b", items))
        assertNull(MusicQueueNavigation.next("c", items))
        assertEquals("a", MusicQueueNavigation.previous("b", items))
        assertNull(MusicQueueNavigation.previous("a", items))
    }
    @Test fun unknownCurrentCanSkipForwardToFirstButNotGoBack() {
        assertEquals("a", MusicQueueNavigation.next("missing", items))
        assertNull(MusicQueueNavigation.previous("missing", items))
        assertNull(MusicQueueNavigation.next("", items))
    }
    @Test fun selectionCannotEscapeQueue() {
        assertEquals("c", MusicQueueNavigation.at(2, items))
        assertNull(MusicQueueNavigation.at(-1, items))
        assertNull(MusicQueueNavigation.at(3, items))
        assertNull(MusicQueueNavigation.at(Long.MAX_VALUE, items))
    }
}
