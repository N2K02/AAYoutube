package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression tests for background playback, quality changes and media-process lifecycle. */
class BackgroundQualityRecoveryContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun automaticRecoveryRunsAcrossPhoneCarAndPipWithoutCancellingManualPause() {
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val js = source("app/src/main/assets/aay.js")

        // Native recovery observes real transitions; the page watchdog continues
        // at a capped rate even after native's short retry budget has expired.
        assertTrue(web.contains("val background = !shown && !inPip.value && !carShown"))
        assertTrue(web.contains("if (background && !Features.current.backgroundPlay) return"))
        assertTrue(web.contains("now - userPausedAt <= 5_000L"))
        assertTrue(web.contains("resumeTries >= 2"))
        assertTrue(web.contains("cmd(\"play\")"))
        assertTrue(js.contains("setInterval(backgroundWatchdog, 5000)"))
        assertTrue(js.contains("Math.min(60000, 5000 * Math.pow(2, Math.min(bgWatch.attempts, 4)))"))
        assertTrue(js.contains("if (bgWatch.explicitPause || !bgWatch.wanted) return"))
        assertFalse(js.contains("if (bgWatch.attempts >= 3) return"))
        assertFalse(js.contains("if ((!A.isBg && !A.hold && !A.isMini)"))
    }

    @Test fun youtubePlayerGestureAndMediaCommandBothOverridePauseProtection() {
        val js = source("app/src/main/assets/aay.js")
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(js.contains("function manualPauseGesture(e)"))
        assertTrue(js.contains("if (BROWSE || e.isTrusted === false) return"))
        assertTrue(js.contains("var manual = !allowPause && Date.now() <= userPauseEligibleUntil"))
        assertTrue(js.contains("if (!allowPause && !manual && holding() && !this.ended)"))
        assertTrue(js.contains("if (manual) noteManualPause()"))
        assertTrue(js.contains("if (ev === 'pause' && Date.now() <= userPauseEligibleUntil) noteManualPause()"))
        assertTrue(js.contains("N.userPause()"))
        assertTrue(js.contains("case 'pause': AUTO = false; PAUSED = true; userPauseEligibleUntil = 0; bgWatch.explicitPause = true"))
        assertTrue(web.contains("fun onJsUserPause(generation: Int)"))
        assertTrue(web.contains("WebHost.onJsUserPause(generation)"))
        assertTrue(web.contains("if (generation != webGeneration) return@post"))
        assertTrue(web.contains("playbackWanted = false"))
        assertTrue(web.contains("userPausedAt = SystemClock.elapsedRealtime()"))
    }

    @Test fun intentionalPauseIsPreservedAcrossServiceAndWebProcessReconnection() {
        val protocol = source("app/src/main/java/com/aayoutube/app/Protocol.kt")
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val service = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        val js = source("app/src/main/assets/aay.js")
        assertTrue(protocol.contains("val userPaused: Boolean = false"))
        assertTrue(protocol.contains("putBoolean(\"userPaused\", userPaused)"))
        assertTrue(protocol.contains("userPaused = b.getBoolean(\"userPaused\")"))
        assertTrue(js.contains("userPaused: !!(bgWatch && bgWatch.explicitPause && v.paused)"))
        assertTrue(web.contains("userPaused = o.optBoolean(\"userPaused\")"))
        assertTrue(web.contains("publish(snapshot.value.copy(playing = false, buffering = false, userPaused = true))"))
        assertTrue(service.contains("if (fresh.userPaused) recovery.cancel()"))
    }

    @Test fun recoveryIntentPreventsIdleFreezeButStopsOnPauseOrQuit() {
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("private fun keepRecovering(): Boolean = playbackWanted"))
        assertTrue(web.contains("Features.current.backgroundPlay || carShown || inPip.value || shown"))
        assertTrue(web.contains("if (keepRecovering()) return"))
        assertTrue(web.contains("if (!shown && !snapshot.value.playing && !keepRecovering()) freeze()"))
        assertTrue(web.contains("if (incoming.ended || incoming.id.isBlank()) playbackWanted = false"))
        assertTrue(web.contains("if (!preserveCarOwners) playbackWanted = false"))
        assertTrue(web.contains("main.postDelayed(freezeSoon, maxOf(45_000L, recoveryMs))"))
        assertTrue(web.contains("if (s.playing) unexpectedPauseRecoveryUntil = 0L"))
        assertTrue(web.contains("Msg.STOP -> {"))
        assertTrue(web.contains("Msg.QUIT -> {"))
        assertTrue(web.contains("cmd(\"pause\")"))
    }

    @Test fun explicitMediaPauseIsReplayedAfterWebProcessReconnect() {
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(media.contains("terminalIntent.pendingCommand?.let { pending ->"))
        assertTrue(media.contains("putLong(\"terminalId\", terminalIntent.pendingId ?: 0L)"))
        assertTrue(media.contains("terminalIntent.onCommand(command)"))
        assertTrue(media.contains("terminalIntent.acknowledge("))
        assertTrue(media.contains("message.data.getLong(\"terminalId\", -1L)"))
        assertTrue(media.contains("terminalIntent.pendingCommand != null || snapshot.playing"))
        assertTrue(media.contains("!bound && snapshot.id.isNotBlank()) ensureWeb()"))
        assertTrue(media.contains("terminalIntent.clear()"))
        val protocol = source("app/src/main/java/com/aayoutube/app/Protocol.kt")
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(protocol.contains("const val TRANSPORT_ACK = 106"))
        assertTrue(web.contains("send(Msg.TRANSPORT_ACK"))
        assertTrue(web.contains("what == Msg.TRANSPORT_ACK"))
        assertTrue(media.contains("terminalIntent.pendingCommand == null) stopSelf()"))
    }

    @Test fun fullDocumentNavigationClearsStaleMediaBeforeScriptIsReplaced() {
        val host = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(host.contains("!PlaybackRoutePolicy.isPlaybackRoute(route) && previous.id.isNotBlank()"))
        assertTrue(host.contains("snapshot.value = Snap()"))
        assertTrue(host.contains("send(Msg.STATE, Snap().toBundle())"))
        assertTrue(host.contains("playbackWanted = false"))
    }

    @Test fun unrequestedPauseRetainsForegroundWhileWatchdogRecovers() {
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        val proto = source("app/src/main/java/com/aayoutube/app/Protocol.kt")
        assertTrue(proto.contains("val recovering: Boolean = false"))
        assertTrue(proto.contains("putBoolean(\"recovering\", recovering)"))
        assertTrue(proto.contains("recovering = b.getBoolean(\"recovering\")"))
        assertTrue(web.contains("incoming.copy(recovering = !incoming.playing"))
        assertTrue(web.contains("&& incoming.id.isNotBlank() && keepRecovering()"))
        assertTrue(media.contains("recoveringPlayback = recovery.active || fresh.recovering"))
        assertTrue(media.contains("s.buffering || s.recovering -> PlaybackStateCompat.STATE_BUFFERING"))
    }

    @Test fun transportPauseStopAndQuitDropStaleRecoveryImmediately() {
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(media.contains("snapshot.copy(playing = false, userPaused = true, buffering = false, recovering = false)"))
        assertTrue(media.contains("snapshot.copy(playing = false, buffering = false, recovering = false, userPaused = true)"))
    }

    @Test fun mediaServiceKeepsForegroundThroughTransientPause() {
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(media.contains("handler.removeCallbacks(demoteForeground)"))
        assertTrue(media.contains("handler.postDelayed(demoteForeground, 8_000L)"))
        assertTrue(media.contains("!snapshot.playing && !recoveringPlayback"))
        assertTrue(media.contains("handler.removeCallbacksAndMessages(null)"))
    }

    @Test fun switchingQualityKeepsPositionOnSameMediaOnly() {
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("function rememberSourceChange(v, reason)"))
        assertTrue(js.contains("function restoreAfterSourceChange(v)"))
        assertTrue(js.contains("qualityState.seekAt"))
        assertTrue(js.contains("pending.id !== qualityVideoId()"))
        assertTrue(js.contains("A.restoreAt = function (id, seconds)"))
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("incomingId == outgoingId"))
        assertTrue(web.contains("incomingId == previous.id"))
        assertTrue(web.contains("sameVideoReloadPosition = null"))
        assertTrue(web.contains("JSONObject.quote(videoId)"))
    }
}
