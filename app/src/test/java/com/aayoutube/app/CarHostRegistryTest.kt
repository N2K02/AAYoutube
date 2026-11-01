package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

/** Android Auto recreating the projected car activity while the old one is alive. */
class CarHostRegistryTest {
    @Test fun newestCarActivityOwnsThePlayer() {
        val hosts = CarHostRegistry()
        hosts.claim("A") {}
        assertTrue(hosts.isActive("A"))
        hosts.claim("B") {}
        assertTrue(hosts.isActive("B"))
        assertFalse("A replaced window must not steal the player back", hosts.isActive("A"))
        assertEquals(2, hosts.size)
    }

    @Test fun oldInstanceTeardownKeepsNewOwner() {
        val hosts = CarHostRegistry()
        var rebinds = 0
        hosts.claim("A") { rebinds++ }
        hosts.claim("B") { rebinds++ }
        // A.onStop/onDestroy run after B is already projecting.
        assertNull(hosts.release("A"))
        assertNull(hosts.release("A"))
        assertTrue(hosts.isActive("B"))
        assertEquals(0, rebinds)
    }

    @Test fun releasingActiveHostHandsPlayerToRemainingOne() {
        val hosts = CarHostRegistry()
        var aRebinds = 0
        hosts.claim("A") { aRebinds++ }
        hosts.claim("B") {}
        hosts.release("B")?.invoke()
        assertTrue(hosts.isActive("A"))
        assertEquals(1, aRebinds)
    }

    @Test fun oldInstanceLateOnStartDoesNotStealReplacement() {
        val hosts = CarHostRegistry()
        hosts.claim("A") {}
        hosts.claim("B") {}
        // Older A receives onStart after B's new Presentation is created.
        assertTrue(hosts.resume("A") {})
        assertTrue(hosts.isActive("B"))
        // A duplicate claim also cannot reorder the same instance.
        hosts.claim("A") {}
        assertTrue(hosts.isActive("B"))
        assertEquals(2, hosts.size)
    }

    @Test fun stoppedHostCannotResurrectBehindActiveNewerWindow() {
        val hosts = CarHostRegistry()
        hosts.claim("A") {}
        hosts.claim("B") {}
        assertNull(hosts.release("A"))
        assertFalse(hosts.resume("A") {})
        assertTrue(hosts.isActive("B"))
        assertEquals(1, hosts.size)
    }

    @Test fun stoppedHostCanResumeWhenNoCarWindowRemains() {
        val hosts = CarHostRegistry()
        hosts.claim("A") {}
        assertNull(hosts.release("A"))
        assertTrue(hosts.resume("A") {})
        assertTrue(hosts.isActive("A"))
    }

    @Test fun lastHostReleaseAndQuitClearOwnership() {
        val hosts = CarHostRegistry()
        hosts.claim("A") {}
        assertNull(hosts.release("A"))
        assertNull(hosts.active)
        hosts.claim("B") {}
        hosts.clear()
        assertNull(hosts.active)
        assertEquals(0, hosts.size)
    }
}
