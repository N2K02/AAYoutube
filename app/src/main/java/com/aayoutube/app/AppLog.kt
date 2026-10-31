package com.aayoutube.app
import android.content.Context
import android.util.Log
import java.io.File
/** All application diagnostics go to redacted Logcat; no in-app file storage. */
object AppLog {
    fun init(context: Context) {
        File(context.filesDir, "diagnostics").listFiles()?.filter {
            it.name.startsWith("log-") || it.name == "logging-enabled"
        }?.forEach { it.delete() }
        File(context.filesDir, "crash-journal.log").delete()
        File(context.filesDir, "crash-journal-web.log").delete()
    }
    fun add(tag: String, message: String) {
        val safe = redact(message).replace('\n', ' ').replace('\r', ' ').take(1000)
        if (tag == "CAR") Log.i("AAYouTube-CAR", safe)
        else Log.d("AAYouTube-" + tag.take(18), safe)
    }
    internal fun redact(value: String): String = value
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(token|key|password|secret|authorization)(\\s*[:=]\\s*)[^\\s&,;]+"), "$1$2[REDACTED]")
        .replace(Regex("(?i)https?://\\S+"), "[URL]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
        .replace(Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9() -]{7,}[0-9](?![A-Za-z0-9])"), "[NUMBER]")
}
