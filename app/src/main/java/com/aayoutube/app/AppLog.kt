package com.aayoutube.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional daily private logs; fatal crashes use a separate always-on journal. */
object AppLog {
    private const val MAX = 5000
    private const val RETENTION_DAYS = 7L
    private const val DAY_BYTES = 256 * 1024L
    private const val TOTAL_BYTES = 2 * 1024 * 1024L
    private val lock = Any()
    private const val ENABLED_MARKER = "logging-enabled"
    private fun marker(app: Context): File =
        File(File(app.filesDir, "diagnostics"), ENABLED_MARKER)
    private val lines = ArrayDeque<String>()
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aayoutube-log-writer").apply { isDaemon = true }
    }
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    @Volatile private var context: Context? = null
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(app: Context) {
        synchronized(lock) {
            if (context != null) return
            context = app.applicationContext
            val home = File(app.filesDir, "diagnostics").apply { mkdirs() }
            prune(home)
            home.listFiles()?.filter { it.name.matches(Regex("log-\\d{4}-\\d{2}-\\d{2}\\.txt")) }
                ?.sortedBy { it.name }?.forEach { file ->
                    file.forEachLine { appendMemory(it) }
                }
            listOf("crash-journal.log", "crash-journal-web.log").forEach { name ->
                File(app.filesDir, name).takeIf { it.exists() }
                    ?.forEachLine { appendMemory("[CRASH] " + it) }
            }
            val prefEnabled = app.getSharedPreferences("diagnostic_settings", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
            _enabled.value = prefEnabled || marker(app).isFile
            if (_enabled.value && !marker(app).isFile) runCatching {
                marker(app).parentFile?.mkdirs()
                marker(app).createNewFile()
            }
            _version.value++
        }
    }

    fun setEnabled(on: Boolean) {
        val app = context
        // A file marker is visible to :web and the media process immediately.
        // SharedPreferences caches are not coherent across Android processes.
        app?.let { runCatching {
            marker(it).parentFile?.mkdirs()
            if (on) marker(it).createNewFile() else marker(it).delete()
        } }
        _enabled.value = on
        app?.getSharedPreferences("diagnostic_settings", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("enabled", on)?.apply()
        if (on) add("LOG", "Logging enabled") else _version.value++
    }

    /** Never pass secrets or request bodies here: redaction is a defense in depth. */
    fun add(tag: String, message: String) {
        if (context?.let { marker(it).isFile } != true) return
        val now = Date()
        val row: String
        val fileName: String
        synchronized(lock) {
            val safeTag = tag.take(24).replace(Regex("[^A-Za-z0-9_-]"), "_")
            row = clock.format(now) + " [" + safeTag + "] (" +
                Thread.currentThread().name.take(14) + ") " + redact(message)
                    .replace('\n', ' ').replace('\r', ' ').take(1000)
            fileName = "log-" + date.format(now) + ".txt"
            appendMemory(row)
            _version.value++
        }
        val app = context ?: return
        io.execute {
            try {
                val dir = File(app.filesDir, "diagnostics").apply { mkdirs() }
                val file = File(dir, fileName)
                if (file.length() + row.length * 4 > DAY_BYTES) {
                    val recent = file.readLines().takeLast(600)
                    file.writeText(recent.joinToString("\n", postfix = if (recent.isEmpty()) "" else "\n"))
                }
                file.appendText(row + "\n")
                prune(dir)
            } catch (_: Exception) { /* Logging must never crash the app. */ }
        }
    }

    internal fun redact(value: String): String = value
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(token|key|password|secret|authorization)(\\s*[:=]\\s*)[^\\s&,;]+"), "$1$2[REDACTED]")
        .replace(Regex("(?i)https?://\\S+"), "[URL]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
        .replace(Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9() -]{7,}[0-9](?![A-Za-z0-9])"), "[NUMBER]")

    private fun appendMemory(row: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast(row)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.name.matches(Regex("log-\\d{4}-\\d{2}-\\d{2}\\.txt")) }
            ?.sortedBy { it.name } ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86400000L
        files.forEach { if (it.lastModified() < cutoff) it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        files.forEach {
            if (total > TOTAL_BYTES && it.exists()) {
                total -= it.length()
                it.delete()
            }
        }
    }

    /** Refresh the Logs screen from both processes' append-only diagnostic file.
     * Run disk access off the UI thread; no IPC or service binding is needed.
     * Pending in-memory lines are retained until their async write is flushed. */
    suspend fun refreshFromDisk() {
        val app = context ?: return
        val diskLines = withContext(Dispatchers.IO) {
            val dir = File(app.filesDir, "diagnostics")
            val recent = dir.listFiles()?.filter {
                it.name.matches(Regex("log-\\d{4}-\\d{2}-\\d{2}\\.txt"))
            }?.sortedBy { it.name }.orEmpty()
            val items = recent.flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
                .takeLast(MAX).toMutableList()
            listOf("crash-journal.log", "crash-journal-web.log").forEach { name ->
                File(app.filesDir, name).takeIf { it.isFile }?.let { file ->
                    runCatching { file.forEachLine { items.add("[CRASH] " + it) } }
                }
            }
            items.takeLast(MAX)
        }
        synchronized(lock) {
            // A media process can add log rows while this page is open.
            // Pending appends should not disappear from the UI during flush.
            val merged = diskLines.toMutableList()
            for (entry in lines) if (entry !in merged && merged.size < MAX) merged.add(entry)
            if (merged != lines.toList()) {
                lines.clear()
                merged.takeLast(MAX).forEach { lines.addLast(it) }
                _version.value++
            }
        }
    }

    val size: Int get() = synchronized(lock) { lines.size }
    fun slice(from: Int, count: Int): List<String> = synchronized(lock) { lines.drop(from).take(count) }
    fun all(): String = synchronized(lock) { lines.joinToString("\n") }
    fun clear() {
        synchronized(lock) { lines.clear(); _version.value++ }
        val app = context ?: return
        io.execute {
            File(app.filesDir, "diagnostics").listFiles()?.filter {
                it.name.startsWith("log-")
            }?.forEach { it.delete() }
            File(app.filesDir, "crash-journal.log").delete()
            File(app.filesDir, "crash-journal-web.log").delete()
        }
    }
}
