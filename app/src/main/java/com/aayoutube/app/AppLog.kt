package com.aayoutube.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Detailed in-app log for the Logs page (web process). Only kept in memory: every app start
 * begins empty. While the switch is off nothing is recorded.
 */
object AppLog {
    private const val MAX = 5000
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    /** Changes every time the log changes, so the screen knows to redraw. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (on) add("LOG", "Logging on")
    }

    fun add(tag: String, message: String) {
        if (!_enabled.value) return
        val time = clock.get()!!.format(Date())
        val thread = Thread.currentThread().name.take(14)
        synchronized(lock) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast("$time [$tag] ($thread) $message")
        }
        _version.value = _version.value + 1
    }

    val size: Int get() = synchronized(lock) { lines.size }

    fun slice(from: Int, count: Int): List<String> = synchronized(lock) {
        lines.drop(from).take(count)
    }

    fun all(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lock) { lines.clear() }
        _version.value = _version.value + 1
    }
}
