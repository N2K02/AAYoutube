package com.aayoutube.app

import android.os.Bundle
import android.os.SystemClock

/**
 * Messages between the two processes.
 *
 * - media process (PlaybackService): Android Auto, notification, media session. No WebView.
 * - web process (WebHostService + MainActivity): the WebView and the screens.
 *
 * Everything crossing the boundary is a small Bundle, sent without waiting for an answer, so
 * neither side can ever block on the other.
 */
object Msg {
    // media process -> web process
    const val REGISTER = 1   // replyTo = the media process's inbox
    const val OPEN = 2       // id, sec
    const val SEARCH = 3     // q
    const val PLAY = 4
    const val PAUSE = 5
    const val SEEK = 6       // ms
    const val NEXT = 7
    const val PREV = 8
    const val RATE = 9       // rate
    const val STOP = 10
    const val FREEZE = 11
    const val QUIT = 12      // Exit pressed: close the screen too

    // web process -> media process
    const val STATE = 101
    const val QUIT_ACK = 102  // web process confirms quit command received
}

/** Custom commands on the media session (app -> service, and notification buttons). */
object Commands {
    const val HELLO = "com.aayoutube.app.HELLO"
    const val FAVOURITE = "com.aayoutube.app.FAVOURITE"
    const val EXIT = "com.aayoutube.app.EXIT"
}

/** One entry of the queue shown in Android Auto (playlist, Mix, or Up next). */
data class QItem(val id: String, val title: String, val artist: String)

/** What the YouTube page is doing right now, as reported by the injected script. */
data class Snap(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val art: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val live: Boolean = false,
    val ad: Boolean = false,
    val posMs: Long = 0L,
    val durMs: Long = 0L,
    val rate: Float = 1f,
    val canNext: Boolean = false,
    val canPrev: Boolean = false,
    /** Playlist / Mix / Up next, including the current video. Empty: only the current video. */
    val queue: List<QItem> = emptyList(),
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("id", id)
        putString("title", title)
        putString("artist", artist)
        putString("art", art)
        putBoolean("playing", playing)
        putBoolean("buffering", buffering)
        putBoolean("ended", ended)
        putBoolean("live", live)
        putBoolean("ad", ad)
        putLong("pos", posMs)
        putLong("dur", durMs)
        putFloat("rate", rate)
        putBoolean("next", canNext)
        putBoolean("prev", canPrev)
        putStringArrayList("qid", ArrayList(queue.map { it.id }))
        putStringArrayList("qt", ArrayList(queue.map { it.title }))
        putStringArrayList("qa", ArrayList(queue.map { it.artist }))
        // Both processes share the device clock, so the receiver can add the travel time.
        putLong("stamp", SystemClock.elapsedRealtime())
    }

    companion object {
        fun from(b: Bundle): Snap {
            val playing = b.getBoolean("playing")
            val rate = b.getFloat("rate", 1f)
            val age = (SystemClock.elapsedRealtime() - b.getLong("stamp")).coerceAtLeast(0L)
            val pos = b.getLong("pos") + if (playing) (age * rate).toLong() else 0L
            return Snap(
                id = b.getString("id").orEmpty(),
                title = b.getString("title").orEmpty(),
                artist = b.getString("artist").orEmpty(),
                art = b.getString("art").orEmpty(),
                playing = playing,
                buffering = b.getBoolean("buffering"),
                ended = b.getBoolean("ended"),
                live = b.getBoolean("live"),
                ad = b.getBoolean("ad"),
                posMs = pos,
                durMs = b.getLong("dur"),
                rate = rate,
                canNext = b.getBoolean("next"),
                canPrev = b.getBoolean("prev"),
                queue = run {
                    val ids = b.getStringArrayList("qid").orEmpty()
                    val ts = b.getStringArrayList("qt").orEmpty()
                    val ars = b.getStringArrayList("qa").orEmpty()
                    ids.indices.map { i -> QItem(ids[i], ts.getOrElse(i) { "" }, ars.getOrElse(i) { "" }) }
                },
            )
        }
    }
}
