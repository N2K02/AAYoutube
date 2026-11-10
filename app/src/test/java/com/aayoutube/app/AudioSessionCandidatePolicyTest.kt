package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AudioSessionCandidatePolicyTest {
    private val mine = 10620
    private fun player(uid: Int, pid: Int, session: Int, state: String = "started",
        index: Int = 7): String =
        "AudioPlaybackConfiguration piid:$index deviceIds:[2] " +
            "type:android.media.AudioTrack u/pid:$uid/$pid state:$state " +
            "attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC " +
            "sessionId:$session mutedState:none"

    @Test fun selectsOnlySingleActiveOwnUidSession() {
        val dump = sequenceOf(
            player(10099, 333, 25),
            player(mine, 444, 95),
            player(mine, 444, 95, index = 8),
            player(10000, 7, 9),
        )
        assertEquals(95, AudioSessionCandidatePolicy.select(dump, mine))
    }

    @Test fun wrongUidInactiveAndInvalidSessionsAreNotBound() {
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine + 1, 444, 95)), mine))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 95, state = "paused")), mine))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 0)), mine))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 95)), 0))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 999999)), mine))
    }

    @Test fun twoIndependentSessionsFailClosedWithoutGuessing() {
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 95, index = 7),
            player(mine, 444, 96, index = 8)), mine))
    }

    @Test fun refusesTruncatedAnonymizedAndSpoofedDumpLines() {
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            "AudioPlaybackConfiguration piid:7 state:started sessionId:95"), mine))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            "u/pid:$mine/444 state:started sessionId:95"), mine))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 95).replace("state:started", "state:released")), mine))
        assertNull(AudioSessionCandidatePolicy.select(sequenceOf(
            player(mine, 444, 95).replace("u/pid:$mine", "u/pid:0")), mine))
    }

    private fun source(path: String) =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun elevatedDiscoveryIsOptionalAsyncAndNeverLogsRawDumps() {
        val locator = source("app/src/main/java/com/aayoutube/app/AudioSessionDiscovery.kt")
        val effects = source("app/src/main/java/com/aayoutube/app/GlobalAudioEffects.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val ui = source("app/src/main/java/com/aayoutube/app/EqualizerSheet.kt")
        val app = source("app/src/main/java/com/aayoutube/app/AAYSoundFx.kt")
        assertTrue(locator.contains("hasPermission()"))
        assertTrue(locator.contains("ProcessBuilder(\"dumpsys\", \"audio\")"))
        assertTrue(locator.contains("DUMP_TIMEOUT_MS = 2_500L"))
        assertTrue(locator.contains("Process.myUid()"))
        assertTrue(locator.contains("AudioSessionCandidatePolicy.select("))
        assertTrue(locator.contains("destroyForcibly()"))
        assertTrue(locator.contains("serial != generation"))
        assertFalse(locator.contains("Log.i("))
        assertTrue(locator.contains("AppLog.add(\"EQ\""))
        assertTrue(locator.contains("ownPlayerRows"))
        assertTrue(locator.contains("lastDurationMs"))
        assertTrue(locator.contains("line.contains(ownUidTag)"))
        assertFalse(locator.contains("AppLog.add(\"EQ\", line"))
        assertFalse(locator.contains("SharedPreferences"))
        assertTrue(effects.contains("AudioSessionDiscovery.start()"))
        assertTrue(effects.contains("AudioSessionDiscovery.stop()"))
        assertTrue(effects.contains("if (selectedSession == null)"))
        assertTrue(effects.contains("Equalizer(0, selectedSession)"))
        assertTrue(effects.contains("BassBoost(0, selectedSession)"))
        assertTrue(effects.contains("Virtualizer(0, selectedSession)"))
        assertTrue(app.contains("AudioSessionDiscovery.init(context)"))
        assertTrue(manifest.contains("android.permission.DUMP"))
        assertTrue(ui.contains("adb shell pm grant com.aayoutube.app android.permission.DUMP"))
    }
}
