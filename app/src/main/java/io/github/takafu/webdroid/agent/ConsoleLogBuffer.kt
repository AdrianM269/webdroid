package io.github.takafu.webdroid.agent

import android.webkit.ConsoleMessage

/**
 * One captured console line from the page, tagged with the URL that produced it.
 *
 * The URL matters for scraping: a page that loads a third-party script which
 * throws will log an error with the *script's* URL, and without tagging the
 * error is unattributable.
 */
data class ConsoleLogEntry(
    val level: String,
    val message: String,
    val source: String,
    val line: Int,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Bounded ring buffer of page console output.
 *
 * Unbounded capture is a memory leak on any long scraping run: a page with a
 * debug loop logs thousands of lines a minute, and a stuck retry loop can fill
 * an unbounded list until the app is killed. Oldest entries are dropped once
 * [capacity] is reached.
 */
class ConsoleLogBuffer(private val capacity: Int = 500) {

    private val lock = Any()
    private val entries = ArrayDeque<ConsoleLogEntry>()

    /** Errors seen since the buffer was created, used by the bug-fix hints. */
    @Volatile
    var errorCount: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun add(entry: ConsoleLogEntry) {
        synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }
        if (entry.level.equals("ERROR", ignoreCase = true)) {
            errorCount++
            lastError = "${entry.message} (${entry.source}:${entry.line})"
        }
    }

    fun add(message: ConsoleMessage?, pageUrl: String?) {
        if (message == null) return
        val level = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
            ConsoleMessage.MessageLevel.WARNING -> "WARN"
            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
            else -> "LOG"
        }
        add(
            ConsoleLogEntry(
                level = level,
                message = message.message().orEmpty(),
                source = message.sourceId().orEmpty(),
                line = message.lineNumber(),
                url = pageUrl ?: "",
            )
        )
    }

    /** Records a WebView-level failure that never reaches onConsoleMessage. */
    fun addNavigationError(description: String, pageUrl: String?) {
        add(
            ConsoleLogEntry(
                level = "ERROR",
                message = description,
                source = "webview",
                line = 0,
                url = pageUrl ?: "",
            )
        )
    }

    fun get(levelFilter: String? = null, sourceFilter: String? = null, limit: Int = 200): List<ConsoleLogEntry> {
        synchronized(lock) {
            return entries
                .filter { levelFilter.isNullOrBlank() || it.level.equals(levelFilter, ignoreCase = true) }
                .filter { sourceFilter.isNullOrBlank() || it.url.contains(sourceFilter, true) || it.source.contains(sourceFilter, true) }
                .takeLast(limit)
                .toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            errorCount = 0
            lastError = null
        }
    }

    val size: Int get() = synchronized(lock) { entries.size }
}