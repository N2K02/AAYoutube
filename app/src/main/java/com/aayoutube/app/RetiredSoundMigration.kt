package com.aayoutube.app

/** Retire only the old EQ preference store; failed deletion can be retried. */
internal object RetiredSoundMigration {
    fun run(completed: Boolean, hasLegacySettings: () -> Boolean,
            deleteLegacySettings: () -> Boolean, markCompleted: () -> Unit) {
        if (completed) return
        if (!hasLegacySettings() || deleteLegacySettings()) markCompleted()
    }
}
