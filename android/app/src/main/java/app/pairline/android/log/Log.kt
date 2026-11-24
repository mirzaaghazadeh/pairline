package app.pairline.android.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's only log (AGENTS.md rule 9): local, redacted, readable by the user, kept 7 days, and shared only
 * from the diagnostics screen after showing the exact text. Never pass message text, numbers, names or keys.
 */
object Log {
    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    private const val KEEP_DAYS = 7
    private const val MEMORY_LINES = 400

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-log").apply { isDaemon = true } }
    private var dir: File? = null
    private var echoToLogcat = false
    private val recent = ArrayDeque<String>()
    private val _version = MutableStateFlow(0)

    /** Bumps whenever a line is added, so the diagnostics screen can refresh. */
    val version: StateFlow<Int> = _version

    fun init(logDir: File, logcat: Boolean) {
        dir = logDir.apply { mkdirs() }
        echoToLogcat = logcat
        writer.execute { pruneOldFiles() }
    }

    fun d(tag: String, message: String) = add(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = add(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = add(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = add(Level.ERROR, tag, message, error)

    /**
     * Records an uncaught exception before the process dies, so it shows in diagnostics after the restart. Written
     * synchronously; the stack trace holds only code locations, and the messages go through [Redactor].
     */
    fun crash(thread: String, error: Throwable) {
        val text = buildString {
            append("crash in thread ").append(thread).append(": ")
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 4) {
                if (depth > 0) append("\n  caused by: ")
                append(cause.javaClass.name)
                cause.message?.let { append(": ").append(it) }
                for (frame in cause.stackTrace.take(if (depth == 0) 25 else 10)) append("\n    at ").append(frame)
                cause = cause.cause.takeIf { it !== cause }
                depth++
            }
        }
        val line = "${timestamp()} E/crash: ${Redactor.redact(text)}"
        synchronized(recent) { recent.addLast(line) }
        if (echoToLogcat) logcat(Level.ERROR, "crash", line)
        append(line)
    }

    /** The newest lines, oldest first. Already redacted. */
    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    /** Every stored line from the last 7 days, oldest first, up to [maxLines]. */
    fun storedLines(maxLines: Int = 2000): List<String> {
        val d = dir ?: return recentLines()
        val files = d.listFiles { f -> f.name.endsWith(".log") }?.sortedBy { it.name } ?: return recentLines()
        val lines = ArrayList<String>()
        for (f in files) runCatching { lines += f.readLines(Charsets.UTF_8) }
        return if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
    }

    fun clear() {
        synchronized(recent) { recent.clear() }
        writer.execute { dir?.listFiles()?.forEach { it.delete() } }
        _version.value++
    }

    private fun add(level: Level, tag: String, message: String, error: Throwable?) {
        val text = buildString {
            append(message)
            if (error != null) {
                append(" (").append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it) }
                append(')')
            }
        }
        val safe = Redactor.redact(text)
        val line = "${timestamp()} ${level.letter}/$tag: $safe"
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > MEMORY_LINES) recent.removeFirst()
        }
        _version.value++
        if (echoToLogcat) logcat(level, tag, safe)
        writer.execute { append(line) }
    }

    private fun logcat(level: Level, tag: String, safe: String) {
        val t = "Pairline/$tag"
        when (level) {
            Level.DEBUG -> android.util.Log.d(t, safe)
            Level.INFO -> android.util.Log.i(t, safe)
            Level.WARN -> android.util.Log.w(t, safe)
            Level.ERROR -> android.util.Log.e(t, safe)
        }
    }

    private fun append(line: String) {
        val d = dir ?: return
        runCatching { File(d, "${day()}.log").appendText(line + "\n", Charsets.UTF_8) }
    }

    private fun pruneOldFiles() {
        val d = dir ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date())
}
