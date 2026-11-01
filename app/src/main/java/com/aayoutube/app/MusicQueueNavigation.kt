package com.aayoutube.app

/** Pure, bounded fallback when YouTube reports no transport capability for
 * an otherwise valid playlist or Mix. Never crosses the visible queue bounds. */
internal object MusicQueueNavigation {
    fun next(current: String, queue: List<QItem>): String? {
        val index = queue.indexOfFirst { it.id == current }
        return when {
            index >= 0 -> queue.getOrNull(index + 1)?.id
            current.isNotBlank() -> queue.firstOrNull()?.id?.takeIf { it != current }
            else -> null
        }
    }
    fun previous(current: String, queue: List<QItem>): String? {
        val index = queue.indexOfFirst { it.id == current }
        return queue.getOrNull(index - 1)?.id
    }
    fun at(position: Long, queue: List<QItem>): String? =
        if (position >= 0 && position < queue.size) queue[position.toInt()].id else null
}
