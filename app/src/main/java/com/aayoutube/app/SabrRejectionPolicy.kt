package com.aayoutube.app

import com.googlevideo.sabr.datasource.SabrHttpException

/** Authentication/authorization rejection or a gone session: fail closed, use WebView. */
internal object SabrRejectionPolicy {
    private val terminal = setOf(401, 403, 410)
    fun terminalStatus(error: Throwable?): Int? {
        var cause = error
        repeat(12) {
            val current = cause ?: return null
            if (current is SabrHttpException && current.status in terminal) return current.status
            cause = current.cause
        }
        return null
    }
    fun isTerminalDetail(detail: String): Boolean =
        terminal.any { detail == "SABR_HTTP_$it" }

    /** Bounded per-video suppression, survives visibility changes. Explicit provider change clears it. */
    class Blocks {
        private val blocked = linkedMapOf<String, Long>()
        fun record(videoId: String, nowMs: Long) {
            if (videoId.isBlank()) return
            blocked.remove(videoId)
            blocked[videoId] = nowMs
            if (blocked.size > 64) blocked.remove(blocked.keys.first())
        }
        fun blocked(videoId: String, nowMs: Long): Boolean {
            val at = blocked[videoId] ?: return false
            if (nowMs - at in 0 until 30 * 60_000L) return true
            blocked.remove(videoId)
            return false
        }
        fun clear() = blocked.clear()
    }
}
