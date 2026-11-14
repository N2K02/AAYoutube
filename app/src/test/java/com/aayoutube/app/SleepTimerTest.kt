package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun defaultAndValidDurations() {
        assertEquals(15L, SleepTimerPolicy.DEFAULT_MINUTES)
        assertEquals(900_000L, SleepTimerPolicy.durationMillis(15L, false))
        assertEquals(3_600_000L, SleepTimerPolicy.durationMillis(1L, true))
        assertEquals(86_400_000L, SleepTimerPolicy.durationMillis(24L, true))
        assertEquals(86_400_000L, SleepTimerPolicy.durationMillis(1440L, false))
    }

    @Test fun rejectsZeroNegativeOutOfRangeAndOverflow() {
        assertNull(SleepTimerPolicy.durationMillis(0L, false))
        assertNull(SleepTimerPolicy.durationMillis(-1L, true))
        assertNull(SleepTimerPolicy.durationMillis(25L, true))
        assertNull(SleepTimerPolicy.durationMillis(1441L, false))
        assertNull(SleepTimerPolicy.durationMillis(Long.MAX_VALUE, true))
        assertNull(SleepTimerPolicy.durationMillis(Long.MAX_VALUE, false))
    }

    @Test fun plusMinusRespectUnitLimitsAndDefault() {
        assertEquals(16L, SleepTimerPolicy.adjustAmount(15L, 1, false))
        assertEquals(14L, SleepTimerPolicy.adjustAmount(15L, -1, false))
        assertEquals(15L, SleepTimerPolicy.adjustAmount(null, 0, false))
        assertEquals(1L, SleepTimerPolicy.adjustAmount(null, 0, true))
        assertEquals(1L, SleepTimerPolicy.adjustAmount(1L, -1, false))
        assertEquals(1440L, SleepTimerPolicy.adjustAmount(1440L, 1, false))
        assertEquals(24L, SleepTimerPolicy.adjustAmount(24L, 1, true))
        assertEquals(1L, SleepTimerPolicy.adjustAmount(1L, -1, true))
        assertEquals(24L, SleepTimerPolicy.adjustAmount(Long.MAX_VALUE, 1, true))
        assertEquals(1L, SleepTimerPolicy.adjustAmount(Long.MIN_VALUE, -1, true))
    }

    @Test fun durationInputIsCenteredRoundedAndHasAccessibleButtons() {
        val page = listOf(File("app/src/main/java/com/aayoutube/app/SleepTimerPage.kt"),
            File("../app/src/main/java/com/aayoutube/app/SleepTimerPage.kt"))
            .first { it.isFile }.readText()
        assertTrue(page.contains("RoundedCornerShape(20.dp)"))
        assertTrue(page.contains("TextAlign.Center"))
        assertTrue(page.contains("contentDescription = \"Decrease duration\""))
        assertTrue(page.contains("contentDescription = \"Increase duration\""))
        assertTrue(page.contains("SleepTimerPolicy.adjustAmount(current, -1, hours)"))
        assertTrue(page.contains("SleepTimerPolicy.adjustAmount(current, 1, hours)"))
    }

    @Test fun restoredTimerSettingUsesSavedUnitAndRejectsInvalidValues() {
        assertEquals(15L, SleepTimerSettings.restoredAmount(15L, false))
        assertEquals(90L, SleepTimerSettings.restoredAmount(90L, false))
        assertEquals(2L, SleepTimerSettings.restoredAmount(2L, true))
        assertEquals(24L, SleepTimerSettings.restoredAmount(24L, true))
        assertEquals(15L, SleepTimerSettings.restoredAmount(0L, false))
        assertEquals(1L, SleepTimerSettings.restoredAmount(0L, true))
        assertEquals(1L, SleepTimerSettings.restoredAmount(25L, true))
        assertEquals(15L, SleepTimerSettings.restoredAmount(1441L, false))
    }

    @Test fun timerSettingsAreSavedOnEditWithoutAutoStartingCountdown() {
        fun src(path: String) = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val page = src("app/src/main/java/com/aayoutube/app/SleepTimerPage.kt")
        val engine = src("app/src/main/java/com/aayoutube/app/SleepTimer.kt")
        assertTrue(page.contains("SleepTimerSettings.loadAmount(context, hours)"))
        assertTrue(page.contains("SleepTimerSettings.loadHours(context)"))
        assertTrue(page.contains("LaunchedEffect(amount, hours)"))
        assertTrue(page.contains("SleepTimerSettings.saveSelection(context, selected, hours)"))
        assertTrue(engine.contains("getSharedPreferences(PREFS, Context.MODE_PRIVATE)"))
        assertTrue(engine.contains(".putLong(AMOUNT, amount).putBoolean(HOURS, hours).apply()"))
        assertFalse(engine.substringAfter("internal object SleepTimerSettings")
            .substringBefore("/** Process-wide countdown").contains("SleepTimer.start("))
    }

    @Test fun countdownIsMonotonicAndFormatsCorrectly() {
        assertEquals(900_000L, SleepTimerPolicy.remainingMillis(901_000L, 1_000L))
        assertEquals(0L, SleepTimerPolicy.remainingMillis(901_000L, 902_000L))
        assertEquals("15:00", SleepTimerPolicy.formatRemaining(900_000L))
        assertEquals("01:01:01", SleepTimerPolicy.formatRemaining(3_661_000L))
        assertEquals("00:01", SleepTimerPolicy.formatRemaining(1L))
    }

    @Test fun notificationMinutesRoundsUpAndStopsAtZero() {
        assertNull(SleepTimerPolicy.notificationMinutes(null))
        assertNull(SleepTimerPolicy.notificationMinutes(0L))
        assertEquals(1L, SleepTimerPolicy.notificationMinutes(1L))
        assertEquals(1L, SleepTimerPolicy.notificationMinutes(60_000L))
        assertEquals(2L, SleepTimerPolicy.notificationMinutes(60_001L))
        assertEquals(15L, SleepTimerPolicy.notificationMinutes(900_000L))
        assertEquals(1440L, SleepTimerPolicy.notificationMinutes(86_400_000L))
    }

    @Test fun phoneOnlyTimerChipReplacesNotificationTimerUi() {
        fun src(path: String) = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val chip = src("app/src/main/java/com/aayoutube/app/SleepTimerChip.kt")
        val screens = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val notification = src("app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt")
        val service = src("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        val timer = src("app/src/main/java/com/aayoutube/app/SleepTimer.kt")
        assertTrue(chip.contains("SleepTimer.chipMinutes.collectAsState()"))
        assertTrue(chip.contains("Sleep in:"))
        assertTrue(chip.contains("detectDragGestures"))
        assertTrue(chip.contains(".clickable(onClick = onClick)"))
        assertTrue(screens.contains("if (phoneDisplay && !inPip && fullscreen == null)"))
        assertTrue(screens.contains("SleepTimerChip(onClick = { tab = Tab.SleepTimer.ordinal })"))
        assertFalse(notification.contains("Sleep timer:"))
        assertFalse(notification.contains("Cancel sleep timer"))
        assertFalse(service.contains("ACTION_CANCEL_SLEEP_TIMER"))
        assertTrue(notification.contains("setShowActionsInCompactView(*compact.toIntArray())"))
        assertTrue(timer.contains("private val _chipMinutes = MutableStateFlow<Long?>(null)"))
        assertTrue(timer.contains("updateChipMinutes()"))
    }

    @Test fun timerIntegratedIntoExistingSettingsWithoutNewService() {
        fun src(path: String) = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val screens = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val timer = src("app/src/main/java/com/aayoutube/app/SleepTimer.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(screens.contains("Tab.SleepTimer -> SleepTimerPage()"))
        assertTrue(screens.contains("Tab.Features, Tab.SleepTimer"))
        assertTrue(timer.contains("PlaybackService.ACTION_QUIT"))
        assertTrue(timer.contains("handler.removeCallbacks(tick)"))
        assertTrue(timer.contains("ContextCompat.startForegroundService("))
        assertTrue(timer.contains("val close = WebHost.onQuit ?: WebHost.onCarQuit"))
        assertTrue(src("app/src/main/java/com/aayoutube/app/AAYoutubeApp.kt").contains("SleepTimer.init(this)"))
        assertTrue(src("app/src/main/java/com/aayoutube/app/SleepTimerPage.kt").contains("Automatically quit AAYouTube"))
        assertFalse(manifest.contains("SleepTimerService"))
    }
}
