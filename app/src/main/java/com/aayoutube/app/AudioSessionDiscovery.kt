package com.aayoutube.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * Optional ADB-assisted session detection for our EXISTING Chromium player.
 *
 * The user must independently grant android.permission.DUMP via ADB.
 * DUMP allows the process to request an audio service dump on some Android
 * releases. It does NOT grant MODIFY_AUDIO_ROUTING, bypass hidden APIs,
 * or authorize reading/copying other applications' session data.
 *
 * We retain ONLY the one verified own-UID session number in memory.
 * Never retain/log raw dumps or another app's UID/session, and never select
 * an ambiguous player. Always fail closed.
 */
internal data class SessionDiscoveryStatus(
    val granted: Boolean = false,
    val sessionId: Int? = null,
    val message: String = "Enhanced player tracking not enabled",
)

internal object AudioSessionDiscovery {
    private const val SCAN_INTERVAL_MS = 8_000L
    private const val DUMP_TIMEOUT_MS = 2_500L
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(SessionDiscoveryStatus())
    val status: StateFlow<SessionDiscoveryStatus> = _status
    private var appContext: Context? = null
    private var active = false
    private var processing = false
    private var generation = 0
    @Volatile private var process: java.lang.Process? = null
    private var scheduled: Runnable? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _status.value = SessionDiscoveryStatus(granted = hasPermission())
    }

    fun hasPermission(): Boolean {
        val ctx = appContext ?: return false
        return ctx.checkSelfPermission("android.permission.DUMP") ==
            PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start() }
            return
        }
        if (!hasPermission()) {
            stop()
            _status.value = SessionDiscoveryStatus(
                message = "No ADB DUMP grant. Using optional legacy output-mix mode.",
            )
            return
        }
        if (active) return
        active = true
        generation++
        _status.value = SessionDiscoveryStatus(
            granted = true, message = "Checking for AAYouTube's active audio session",
        )
        scan()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        active = false
        generation++
        scheduled?.let { main.removeCallbacks(it) }
        scheduled = null
        process?.destroyForcibly()
        _status.value = SessionDiscoveryStatus(granted = hasPermission())
    }

    private fun scan() {
        if (!active || processing || !hasPermission()) return
        val ctx = appContext ?: return
        processing = true
        val serial = generation
        val timeout = Runnable { process?.destroyForcibly() }
        Thread({
            var spawned: java.lang.Process? = null
            var candidate: Int? = null
            var readable = false
            try {
                val p = ProcessBuilder("dumpsys", "audio")
                    .redirectErrorStream(true).start()
                spawned = p
                process = p
                main.postDelayed(timeout, DUMP_TIMEOUT_MS)
                // Only store lines that might identify our own media player.
                // Nonmatching system dump lines never leave this worker.
                val owned = ArrayList<String>(32)
                p.inputStream.bufferedReader().use { stream ->
                    var lines = 0
                    while (lines++ < 12_000) {
                        val line = stream.readLine() ?: break
                        if (line.contains("u/pid:") &&
                            line.contains("sessionId:") &&
                            line.length <= 2048) owned += line
                        if (owned.size >= 256) break
                    }
                }
                readable = p.waitFor(150, TimeUnit.MILLISECONDS) && p.exitValue() == 0
                if (readable) {
                    candidate = AudioSessionCandidatePolicy.select(
                        owned.asSequence(), Process.myUid())
                }
            } catch (_: Exception) {
                // Missing or restricted dumpsys access is normal on OEM builds.
            } finally {
                main.removeCallbacks(timeout)
                spawned?.destroy()
                if (process === spawned) process = null
                val result = candidate
                val success = readable
                main.post {
                    processing = false
                    if (!active || serial != generation) return@post
                    val previous = _status.value.sessionId
                    _status.value = SessionDiscoveryStatus(
                        granted = true,
                        sessionId = result,
                        message = when {
                            !success -> "Audio-service dump unavailable; targeted EQ disabled"
                            result == null ->
                                "No single AAYouTube audio session verified; targeted EQ disabled"
                            else -> "AAYouTube audio session found; targeting native effects"
                        },
                    )
                    if (previous != result) {
                        GlobalAudioEffects.onVerifiedSessionChanged(result)
                    }
                    scheduled = Runnable { scheduled = null; scan() }
                        .also { main.postDelayed(it, SCAN_INTERVAL_MS) }
                }
            }
        }, "aay-own-audio-session-check").apply {
            isDaemon = true
            start()
        }
    }
}
