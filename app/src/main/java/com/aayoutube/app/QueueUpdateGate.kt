package com.aayoutube.app

/**
 * Keeps the live Android Auto Up next listing stable across frequent player snapshots.
 * Pure state transition policy so duplicate/empty IDs and unchanged player reports do
 * not spam MediaBrowser children-change notifications.
 */
internal class QueueUpdateGate {
    private var queue: List<QItem> = emptyList()

    fun items(): List<QItem> = queue

    fun update(reported: List<QItem>, retainDuringTransition: Boolean = false): Boolean {
        val next = reported.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .take(40)
            .toList()
        if (next.isEmpty() && retainDuringTransition) return false
        if (next == queue) return false
        queue = next
        return true
    }
}
