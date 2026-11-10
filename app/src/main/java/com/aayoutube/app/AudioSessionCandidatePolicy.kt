package com.aayoutube.app

/**
 * Read-only parsing of a bounded Android audio-service diagnostic.
 * Never accepts a session from a different UID or a non-started player.
 * No hidden AudioPlaybackConfiguration APIs, media capture, or external DSP.
 */
internal object AudioSessionCandidatePolicy {
    private val owner = Regex("""\bu/pid:(\d{1,10})/(\d{1,10})\b""")
    private val state = Regex("""\bstate:([a-zA-Z]+)\b""")
    private val session = Regex("""\bsessionId:(\d{1,10})\b""")
    private val player = Regex("""\bpiid:(\d{1,10})\b""")

    /**
     * A session is eligible only if the system attributes exactly ONE
     * active playback stream to this application's actual Unix UID.
     *
     * A Chromium isolated renderer may have a different UID; in that case
     * this function returns null instead of applying EQ to somebody else.
     */
    fun select(dump: Sequence<String>, ownUid: Int): Int? {
        if (ownUid <= 0) return null
        val sessions = mutableSetOf<Int>()
        val players = mutableSetOf<Int>()
        var inspected = 0
        for (line in dump) {
            if (++inspected > 12_000) break
            if (!line.contains("sessionId:") || !line.contains("u/pid:")) continue
            if (!line.contains("AudioPlaybackConfiguration piid:")) continue
            val candidateUid = owner.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            if (candidateUid != ownUid) continue
            if (state.find(line)?.groupValues?.get(1) != "started") continue
            val id = session.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            if (id !in 1..65535) continue
            val playerId = player.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            // Distinct player IDs may share a valid audio session.
            players += playerId
            sessions += id
            if (sessions.size > 1) return null
        }
        return sessions.singleOrNull().takeIf { players.isNotEmpty() }
    }
}
