package com.aayoutube.app

import com.googlevideo.sabr.datasource.SabrHttpException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SabrRejectionPolicyTest {
    @Test fun rejectsOnlyTerminalStatusesEvenWhenWrapped() {
        for (code in listOf(401,403,410)) {
            assertEquals(code, SabrRejectionPolicy.terminalStatus(IOException(SabrHttpException(code))))
            assertTrue(SabrRejectionPolicy.isTerminalDetail("SABR_HTTP_$code"))
        }
        for (code in listOf(404,429,500,503)) {
            assertNull(SabrRejectionPolicy.terminalStatus(SabrHttpException(code)))
            assertFalse(SabrRejectionPolicy.isTerminalDetail("SABR_HTTP_$code"))
        }
        assertNull(SabrRejectionPolicy.terminalStatus(IOException("403")))
    }

    @Test fun rejectionBlockSurvivesOrdinaryModeChangesAndExpires() {
        val b = SabrRejectionPolicy.Blocks()
        b.record("a", 100)
        assertTrue(b.blocked("a", 101))
        assertFalse(b.blocked("b", 101))
        assertFalse(b.blocked("a", 100 + 30 * 60_000L))
        b.record("a", 200)
        b.clear()
        assertFalse(b.blocked("a", 201))
    }
}
