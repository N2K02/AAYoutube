package com.aayoutube.app

internal enum class EngineId { WEB, NATIVE }

/** One playback engine as the coordinator sees it. Implementations run on the main thread. */
internal interface PlaybackEngine {
    val id: EngineId
    /** Stop audible output and media decoding/downloading; metadata may stay. */
    fun silence()
    /** Confirm actual source silence before starting the target. */
    fun silenceConfirmed(videoId: String, confirmed: (Boolean) -> Unit) { silence(); confirmed(true) }
    /** Start (or continue) at [positionMs] (-1 = keep position); play only when [resume]. */
    fun start(videoId: String, positionMs: Long, resume: Boolean)
    fun pause()
    fun play()
    fun seek(positionMs: Long)
    fun positionMs(): Long
    fun release()
}

/** Native side additionally prepares a stream before it may become audible. */
internal interface PreparableEngine : PlaybackEngine {
    /** Prepare silently (never audible until [start]); readiness is reported to the coordinator. */
    fun prepare(stream: AudioStream, positionMs: Long, offload: OffloadDecision)
}

/**
 * Exclusive playback ownership. Exactly one engine owns playback; at most one is audible:
 *
 *  WEB ──prepare──▶ PREPARING (web still audible, native silent)
 *  PREPARING ──ready──▶ web.silence() then native.start()  ▶ NATIVE
 *  PREPARING ──timeout/error──▶ native.release()           ▶ WEB (nothing changed for the user)
 *  NATIVE ──error/next/prev/other video/Quit──▶ native.silence(), web.start(at native position) ▶ WEB
 *
 * The source engine is always silenced before the target starts. Position and user intent move
 * with every handoff ([PlaybackHandoffPolicy]). Pure: tests drive it with fake engines.
 */
internal class PlaybackEngineCoordinator(
    private val web: PlaybackEngine,
    private val native: PreparableEngine,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    enum class Phase { WEB, PREPARING, NATIVE }

    var phase = Phase.WEB
        private set
    val owner: EngineId get() = if (phase == Phase.NATIVE) EngineId.NATIVE else EngineId.WEB
    /** Engines currently allowed to make sound (invariant: size <= 1). */
    val audible: Set<EngineId> get() = audibleSet.toSet()
    private val audibleSet = mutableSetOf(EngineId.WEB)

    var nativeVideoId = ""
        private set
    private var pending: HandoffState? = null
    private var silencePending = false
    private var handoffSerial = 0L
    private var preparingSince = 0L
    var lastTransitionMs = -1L
        private set
    /** User intent while native owns playback. */
    var userPaused = false
        private set
    private var handoffStartedAt = 0L

    /** Begin a handoff to native: web keeps playing until native is ready. */
    fun prepareNative(state: HandoffState, stream: AudioStream, offload: OffloadDecision): Boolean {
        if (phase != Phase.WEB) return false
        phase = Phase.PREPARING
        handoffSerial++
        silencePending = false
        pending = state
        nativeVideoId = state.videoId
        preparingSince = clock()
        handoffStartedAt = preparingSince
        log("handoff=prepare video=${state.videoId} codec=${stream.codecMime} bitrate=${stream.bitrate} offload=${offload.enabled}(${offload.reason})")
        native.prepare(stream, PlaybackHandoffPolicy.positionAt(state, clock()), offload)
        return true
    }

    /**
     * Native is ready. [current] is the web's latest state (it kept playing during preparation):
     * silence web first, then start native at the compensated position with the user's intent.
     */
    fun onNativeReady(current: HandoffState?) {
        if (phase != Phase.PREPARING || silencePending) return
        // A stale readiness callback must not silence a newly selected web video.
        val state = current ?: run { abortPreparation("missing-web-state"); return }
        if (state.videoId != nativeVideoId) { abortPreparation("web-video-changed"); return }
        val now = clock()
        if (now - preparingSince > PlaybackHandoffPolicy.PREPARE_TIMEOUT_MS) { abortPreparation("timeout"); return }
        silencePending = true
        pending = state
        val serial = handoffSerial
        val pos = PlaybackHandoffPolicy.positionAt(state, now)
        web.silenceConfirmed(state.videoId) { confirmed ->
            if (phase != Phase.PREPARING || serial != handoffSerial || !silencePending) return@silenceConfirmed
            if (!confirmed || clock() - preparingSince > PlaybackHandoffPolicy.PREPARE_TIMEOUT_MS) {
                abortPreparation("web-silence-unconfirmed"); return@silenceConfirmed
            }
            silencePending = false
            audibleSet.remove(EngineId.WEB)
            val resume = PlaybackHandoffPolicy.shouldResume(state)
            userPaused = state.userPaused || !resume
            phase = Phase.NATIVE
            pending = null
            audibleSet.add(EngineId.NATIVE)
            native.start(state.videoId, pos, resume)
            lastTransitionMs = clock() - handoffStartedAt
            log("handoff=native video=${state.videoId} posMs=$pos resume=$resume latencyMs=$lastTransitionMs webSilence=confirmed")
        }
    }

    /** Preparation failed or took too long: nothing audible changed. */
    fun abortPreparation(reason: String) {
        if (phase != Phase.PREPARING) return
        val restore = if (silencePending) pending else null
        silencePending = false
        handoffSerial++
        native.release()
        phase = Phase.WEB
        pending = null
        if (restore != null) web.start(restore.videoId, -1L,
            reason !in setOf("web-video-changed", "transport:pause", "transport:next", "transport:prev", "web-intent-changed") && PlaybackHandoffPolicy.shouldResume(restore))
        log("handoff=aborted reason=$reason video=$nativeVideoId engine=web")
        nativeVideoId = ""
    }

    fun checkTimeout() {
        if (phase == Phase.PREPARING && clock() - preparingSince > PlaybackHandoffPolicy.PREPARE_TIMEOUT_MS) abortPreparation("timeout")
    }

    /**
     * Back to the WebView (native error, next/prev, another video, mode off, Quit). Native is
     * silenced before web starts; web resumes at native's position only if the user wanted play.
     */
    fun returnToWeb(reason: String, resume: Boolean = !userPaused, startWeb: Boolean = true) {
        when (phase) {
            Phase.PREPARING -> { abortPreparation(reason); return }
            Phase.WEB -> return
            Phase.NATIVE -> Unit
        }
        val started = clock()
        val pos = native.positionMs()
        native.silence()
        audibleSet.remove(EngineId.NATIVE)
        native.release()
        val video = nativeVideoId
        phase = Phase.WEB
        nativeVideoId = ""
        // startWeb=false: the web is changing video itself (next/prev/page navigation); it only
        // drops the native-ownership marker (-1 = no seek) and plays what it navigates to.
        web.start(video, if (startWeb) pos else -1L, startWeb && resume)
        audibleSet.add(EngineId.WEB)
        lastTransitionMs = clock() - started
        log("handoff=web reason=$reason video=$video posMs=$pos resume=$resume latencyMs=$lastTransitionMs")
        userPaused = false
    }

    /** Media-session/car/headset transports while native owns playback. False = not handled here. */
    fun command(name: String, positionMs: Long = 0L): Boolean {
        if (phase == Phase.PREPARING && name in setOf("play", "pause", "seek", "next", "prev")) {
            abortPreparation("transport:$name"); return false
        }
        if (phase != Phase.NATIVE) return false
        when (name) {
            "play" -> { userPaused = false; native.play() }
            "pause" -> { userPaused = true; native.pause() }
            "seek" -> native.seek(positionMs.coerceAtLeast(0L))
            // The web engine owns the queue: it changes video, native gives way.
            "next", "prev" -> { returnToWeb(name, resume = true, startWeb = false); return false }
            else -> return false
        }
        return true
    }

    /**
     * The web reported a state while native owns playback. A different video means the user
     * navigated in the page: native gives way. A web that is audible again is silenced
     * (never two audible engines). Returns true when the report was a duplicate-audio risk.
     */
    fun onWebReport(webVideoId: String, webPlaying: Boolean): Boolean {
        if (phase == Phase.PREPARING) {
            if (webVideoId != nativeVideoId) abortPreparation("web-video-changed")
            return false
        }
        if (phase != Phase.NATIVE) return false
        if (webVideoId != nativeVideoId) {
            returnToWeb("web-video-changed", resume = true, startWeb = false)
            return false
        }
        if (webPlaying) {
            // Web pause is asynchronous. Stop native immediately.
            returnToWeb("unexpected-web-playback", resume = true)
            return true
        }
        return false
    }

    /** Preserve a permanent focus loss or becoming-noisy pause on return to web. */
    fun onNativeIntent(playWhenReady: Boolean) {
        if (phase == Phase.NATIVE) userPaused = !playWhenReady
    }

    /** Native playback failed (offload fallback already tried by the engine): web takes over. */
    fun onNativeError(detail: String) {
        if (phase == Phase.PREPARING) abortPreparation("error:$detail")
        else if (phase == Phase.NATIVE) returnToWeb("error:$detail")
    }

    /** Quit / session end: native released, web left as it is (it is being torn down too). */
    fun release() {
        if (phase != Phase.WEB) {
            if (phase == Phase.NATIVE) { native.silence(); audibleSet.remove(EngineId.NATIVE) }
            native.release()
            log("handoff=released phase=$phase video=$nativeVideoId")
        }
        phase = Phase.WEB
        handoffSerial++
        silencePending = false
        pending = null
        nativeVideoId = ""
        userPaused = false
        audibleSet.clear()
        audibleSet.add(EngineId.WEB)
    }
}
