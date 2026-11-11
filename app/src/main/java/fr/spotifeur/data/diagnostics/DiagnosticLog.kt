package fr.spotifeur.data.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recent failures and account decisions, kept on the device so a user can attach them to a bug report.
 * Entries are written by Spotifeur itself: operation names, HTTP statuses, attribute values and audio
 * bitrates. They never contain tokens, usernames, URLs or content identifiers.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "spotifeur-diagnostics").apply { isDaemon = true } }
    private val writeScheduled = AtomicBoolean()
    @Volatile private var file: File? = null
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun init(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.noBackupFilesDir, "diagnostics.log")
        synchronized(entries) {
            if (file != null) return
            val stored = runCatching { target.readLines() }.getOrDefault(emptyList())
            val merged = stored.takeLast(MAX_ENTRIES) + entries
            entries.clear()
            merged.takeLast(MAX_ENTRIES).forEach(entries::addLast)
            file = target
        }
        scheduleWrite()
    }

    fun record(area: String, message: String) {
        val line = "${synchronized(format) { format.format(Date()) }} $area ${message.replace('\n', ' ').take(300)}"
        runCatching { Log.i("SpotifeurDiagnostics", line) } // Not available in JVM unit tests.
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        scheduleWrite()
    }

    /** Like [record], written to disk before returning: the process may be about to end. */
    fun recordNow(area: String, message: String, limit: Int = 300) {
        val line = "${synchronized(format) { format.format(Date()) }} $area ${message.replace('\n', ' ').take(limit)}"
        runCatching { Log.e("SpotifeurDiagnostics", line) }
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        val target = file ?: return
        runCatching { target.writeText(lines().joinToString("\n", postfix = "\n")) }
    }

    /**
     * Uncaught exceptions go to the report with the top of their stack (class and method names only),
     * and the process exits as usual afterwards.
     */
    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { cause ->
                    val frames = cause.stackTrace.take(6).joinToString(" ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    "${cause.javaClass.name} \"${scrub(cause.message.orEmpty()).take(120)}\" at $frames"
                }
                recordNow("crash", "thread=${thread.name} $chain", limit = 2000)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Native crashes and ANRs of earlier runs, as Android kept them, once each. */
    fun recordPastExits(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            val seen = preferences.getLong("last_exit", 0L)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > seen && it.reason in setOf(
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR,
                    android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ) }
            exits.sortedBy { it.timestamp }.forEach { exit ->
                val kind = when (exit.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
                    android.app.ApplicationExitInfo.REASON_ANR -> "anr"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init-failure"
                    else -> "crash"
                }
                val at = synchronized(format) { format.format(Date(exit.timestamp)) }
                record("exit", "$kind at=$at status=${exit.status} \"${exit.description?.take(200).orEmpty()}\"")
            }
            exits.maxOfOrNull { it.timestamp }?.let { latest -> preferences.edit().putLong("last_exit", latest).apply() }
        }
    }

    /** Addresses and content identifiers stay out of the report. */
    private fun scrub(text: String): String = text.replace(Regex("""https?://\S+"""), "<url>")
        .replace(Regex("""(spotify|ytm):[a-z]+:\S+"""), "<id>").replace(Regex("""[0-9a-fA-F]{32,}"""), "<hex>")

    fun lines(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        val target = file ?: return
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.schedule({
            writeScheduled.set(false)
            runCatching { target.writeText(lines().joinToString("\n", postfix = "\n")) }
        }, 2, TimeUnit.SECONDS)
    }
}
