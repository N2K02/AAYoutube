package com.aayoutube.app

import java.net.URI

/** Android shares can include a title or message alongside the link. */
internal object YouTubeSharedLink {
    private val url = Regex("""(?i)(?<![\w=/?&#%])https?://[^\s<>"']+""")
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val videoPath = Regex("/(?:shorts|live)/[A-Za-z0-9_-]{11}/?")
    private val watchQuery = Regex("(?:^|&)v=[A-Za-z0-9_-]{11}(?:&|$)")
    private val playlistQuery = Regex("(?:^|&)list=[A-Za-z0-9_-]+(?:&|$)")

    fun find(text: CharSequence?): String? = text?.let {
        url.findAll(it).map { match -> match.value.trimEnd('.', ',', ')', ']', '}', '!', ';') }
            .firstOrNull(::isPlayableLink)
    }

    private fun isPlayableLink(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null || uri.port != -1) return false
        val host = uri.host?.lowercase()
        val path = uri.rawPath.orEmpty()
        if (host == "youtu.be") return videoId.matches(path.removePrefix("/").removeSuffix("/"))
        if (host !in hosts) return false
        val query = uri.rawQuery.orEmpty()
        return when (path) {
            "/watch" -> watchQuery.containsMatchIn(query)
            "/playlist" -> playlistQuery.containsMatchIn(query)
            else -> videoPath.matches(path)
        }
    }
}
