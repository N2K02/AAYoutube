package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterColdStartTest {
    @Test fun coldStartCreatesControllerAndLaterHostsReuseIt() {
        var creates = 0
        val controller = Any()
        val create = { creates++; controller }
        val first = UpdaterInitialization.ensure(null, create) { throw AssertionError(it) }
        val second = UpdaterInitialization.ensure(first, create) { throw AssertionError(it) }
        assertSame(controller, first)
        assertSame(first, second)
        assertEquals(1, creates)
    }

    @Test fun initializationFailureDoesNotThrowAndCanBeRetried() {
        val failure = IllegalStateException("unavailable")
        var reported: Exception? = null
        val missing = UpdaterInitialization.ensure<Any>(null, { throw failure }) { reported = it }
        assertNull(missing)
        assertSame(failure, reported)
        val controller = Any()
        assertSame(controller, UpdaterInitialization.ensure(missing, { controller }) {
            throw AssertionError(it)
        })
    }

    private fun source(name: String): String {
        val path = "app/src/main/java/com/aayoutube/app/$name"
        return listOf(File(path), File("../$path"), File(path.removePrefix("app/")))
            .first { it.isFile }.readText()
    }

    @Test fun processStartupInitializesUpdaterWithoutPhoneActivity() {
        val app = source("AAYoutubeApp.kt")
        assertTrue(app.contains("override fun onCreate()"))
        assertTrue(app.contains("LiveUpdater.init(this)"))
        val projection = source("LegacyProjectionActivity.kt")
        assertTrue(projection.indexOf("LiveUpdater.init(this)") in 0 until projection.indexOf("setContent { AAYoutubeTheme { LegacyProjectionScreen() } }"))
        assertFalse(projection.contains("startActivity(Intent(this, MainActivity::class.java))"))
    }

    @Test fun unavailableUpdaterCannotCrashPhoneOrProjectionRendering() {
        val updater = source("LiveUpdater.kt")
        assertFalse(updater.contains("requireNotNull(instance)"))
        assertTrue(updater.contains("instance?.onHostResume"))
        for (screen in listOf(source("Screens.kt"), source("LegacyProjectionActivity.kt"))) {
            assertFalse(screen.contains("LiveUpdater.controller.status"))
            assertTrue(screen.contains("LiveUpdater.controllerOrNull?.status?.collectAsState()?.value"))
        }
        assertTrue(source("Screens.kt").contains("if (controller == null)"))
    }
}
