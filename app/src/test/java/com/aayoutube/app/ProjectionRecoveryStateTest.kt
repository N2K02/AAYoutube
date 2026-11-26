package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class ProjectionRecoveryStateTest {
    @Test fun cookieTimeoutCannotUnlockNewLoginUntilExactRemovalCompletes() {
        val gate = SessionResetGate()
        val first = gate.begin()!!
        assertTrue(gate.cookiePending)
        assertFalse(gate.finish())
        assertFalse(gate.cancelIfSafe())
        assertNull(gate.begin())
        assertFalse(gate.cookiesFinished(first + 1))
        assertTrue(gate.cookiesFinished(first))
        assertTrue(gate.homePending)
        assertFalse(gate.cookiesFinished(first))
        assertTrue(gate.finish())
        val next = gate.begin()!!
        assertFalse(gate.cookiesFinished(first))
        assertTrue(gate.cookiesFinished(next))
        assertTrue(gate.finish())
        assertFalse(gate.clearing)
    }

    @Test fun failedDisplayTrialFinishesOnceAndLaterTrialCanRecover() {
        val trial = RendererTrial()
        trial.start(100)
        assertFalse(trial.expired(8099))
        assertTrue(trial.expired(8100))
        assertEquals(8000L, trial.finish(8100))
        assertNull(trial.finish(8200))
        assertFalse(trial.active)
        trial.start(70_000)
        assertTrue(CarRendererReusePolicy.trialVerified(true, true, 40))
        assertEquals(2000L, trial.finish(72_000))
        assertFalse(trial.active)
    }
}
