package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class RetiredSoundMigrationTest {
    @Test fun completedMigrationDoesNotAccessLegacyStore() {
        RetiredSoundMigration.run(true, { error("unexpected read") },
            { error("unexpected delete") }, { error("unexpected write") })
    }

    @Test fun absentLegacyStoreMarksCompletedWithoutDeletion() {
        var completed = false
        RetiredSoundMigration.run(false, { false }, { error("unexpected delete") }, { completed = true })
        assertTrue(completed)
    }

    @Test fun unsuccessfulDeletionRemainsRetryable() {
        var completed = false
        var attempts = 0
        val delete = { ++attempts > 1 }
        repeat(2) { RetiredSoundMigration.run(completed, { true }, delete, { completed = true })
            assertEquals(it == 1, completed)
        }
        assertEquals(2, attempts)
    }
}
