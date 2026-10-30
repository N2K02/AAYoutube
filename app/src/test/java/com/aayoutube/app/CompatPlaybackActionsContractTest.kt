package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CompatPlaybackActionsContractTest {
    private val source by lazy {
        sequenceOf(
            File("app/src/main/java/com/aayoutube/app/CompatPlaybackService.kt"),
            File("../app/src/main/java/com/aayoutube/app/CompatPlaybackService.kt")
        ).first { it.isFile }.readText()
    }

    @Test fun mediaSessionExposesLegacyCustomActions() {
        assertTrue(source.contains("override fun onCustomAction(action: String?, extras: Bundle?)"))
        assertTrue(source.contains("Library.toggleFavourite(snapshot)"))
        assertTrue(source.contains("notifyChildrenChanged(\"favourites\")"))
        assertTrue(source.contains("send(Msg.QUIT)"))
        assertTrue(source.contains("stopSelf()"))
    }

    @Test fun queueIsBoundedAndPauseInvalidatesOldCommands() {
        assertTrue(source.contains("maxPendingCommands = 32"))
        assertTrue(source.contains("command == Msg.PAUSE || command == Msg.STOP || command == Msg.QUIT"))
        assertTrue(source.contains("pending.clear()"))
    }
}
