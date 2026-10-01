package com.whispercppdemo.diag

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash and ANR reporting, built allowlist-first.
 *
 * THE RULE: audio, transcript text, keyterms, filenames and provider
 * credentials never reach telemetry. Not truncated, not hashed, not "probably
 * safe" — absent.
 *
 * That rule is why this collects by ALLOWLIST rather than scrubbing a denylist.
 * A denylist has to anticipate every way content can leak; an allowlist only
 * has to enumerate what is safe. Concretely, exception MESSAGES are dropped
 * entirely: ours are harmless ("audio could not be read"), but a message is a
 * free-text field that any library can fill with a file path, a URI or a
 * response body, and one careless `IOException(file.name)` would leak a
 * filename the user chose. Stack frames — class, method, file, line — carry no
 * user content and are what actually localises a crash.
 *
 * [Sink] is the vendor slot. [FileSink] is the local spool and is always
 * active: every report is written to disk BEFORE any delivery is attempted, so
 * a crash that happens before a vendor SDK initialises, or while the device is
 * offline, is not the one crash that gets lost.
 *
 * Wiring Crashlytics/Sentry later means implementing Sink and calling
 * [deliverTo], which also hands over everything already spooled. The redaction
 * happens before any sink is called, so a vendor can never see more than this
 * file allows — the spooled file itself contains only the rendered, redacted
 * report.
 */
object CrashReporter {

    /** Where a redacted report goes. Implement this to plug in a vendor. */
    fun interface Sink {
        fun send(report: Report)
    }

    data class Report(
        val kind: String,               // "crash" or "anr"
        val throwableChain: List<String>,
        val frames: List<String>,
        val metadata: Map<String, String>,
        /**
         * Identifies this report so a sink that receives it twice -- once live,
         * once from the backlog -- can drop the duplicate. Derived from the
         * report's own content, which is already redacted, so the id cannot
         * carry anything the report does not.
         */
        val id: String = ""
    ) {
        fun render(): String = buildString {
            append("kind=").append(kind).append('\n')
            if (id.isNotBlank()) append("id=").append(id).append('\n')
            metadata.toSortedMap().forEach { (k, v) ->
                append(k).append('=').append(v).append('\n')
            }
            throwableChain.forEach { append("caused-by=").append(it).append('\n') }
            frames.forEach { append("  at ").append(it).append('\n') }
        }
    }

    /**
     * Fields permitted in metadata. Anything not on this list is dropped, so
     * adding a field is a deliberate act reviewed against the rule above.
     */
    private val ALLOWED_KEYS = setOf(
        "appVersion", "androidSdk", "device", "manufacturer", "abi",
        "engine", "jobState", "providerId", "failureReason",
        "availableMemoryMb", "thread"
    )

    private val installed = AtomicBoolean(false)

    @Volatile
    private var sink: Sink? = null

    /** The local spool. Always present, so nothing is lost before delivery. */
    @Volatile
    private var spool: FileSink? = null

    @Volatile
    private var context: Context? = null

    fun install(context: Context, sink: Sink? = null) {
        if (!installed.compareAndSet(false, true)) return
        this.context = context.applicationContext
        // The file spool is NOT optional and is not replaced by a vendor sink.
        // A crash that happens before a vendor SDK initialises, or while the
        // device is offline, is exactly the crash worth having -- so every
        // report is written locally first and delivery is a second step.
        this.spool = FileSink(context)
        this.sink = sink

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { report("crash", error, mapOf("thread" to thread.name)) }
            // Never swallow: the platform still needs to tear the process down.
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records a handled problem. Same redaction as an uncaught crash. */
    fun report(kind: String, error: Throwable, extra: Map<String, String> = emptyMap()) {
        val r = runCatching { build(kind, error, extra) }.getOrNull() ?: return
        // Spool first. If the vendor sink throws -- an uninitialised SDK, a
        // dead network -- the report still exists on disk and is delivered on
        // the next drain, rather than being lost at the moment it mattered.
        runCatching { spool?.send(r) }
        runCatching { sink?.send(r) }
    }

    /**
     * Attaches the production sink after start-up and hands it everything
     * already spooled.
     *
     * This is what makes crashes "deliverable later" rather than merely
     * recorded: a vendor SDK that initialises on a background thread, or is
     * only configured in some builds, still receives the reports captured
     * before it existed. Reports carry [Report.id] so a sink can drop a
     * duplicate.
     *
     * Returns how many spooled reports were handed over.
     */
    fun deliverTo(sink: Sink): Int {
        this.sink = sink
        val pending = spool?.pending().orEmpty()
        var delivered = 0
        pending.forEach { report ->
            val ok = runCatching { sink.send(report) }.isSuccess
            if (ok) {
                delivered++
                // Only dropped once the sink has actually taken it.
                spool?.discard(report.id)
            }
        }
        return delivered
    }

    /** Visible for tests; resets the singleton between cases. */
    internal fun resetForTest() {
        installed.set(false)
        sink = null
        spool = null
        context = null
    }

    internal fun build(kind: String, error: Throwable,
                       extra: Map<String, String>): Report {
        // Class names only. Messages are deliberately discarded -- see above.
        val chain = generateSequence(error) { it.cause }
            .take(5)
            .map { it.javaClass.name }
            .toList()

        val frames = error.stackTrace.take(40).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val meta = buildMap {
            put("androidSdk", Build.VERSION.SDK_INT.toString())
            put("device", Build.MODEL ?: "?")
            put("manufacturer", Build.MANUFACTURER ?: "?")
            put("abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: "?")
            extra.forEach { (k, v) -> put(k, v) }
        }.filterKeys { it in ALLOWED_KEYS }

        // Content-derived, so the same crash spooled and re-delivered is
        // recognisably the same report. Not a user identifier of any kind.
        val id = kind + "-" + java.lang.Integer.toHexString(
            (chain.joinToString("|") + frames.take(5).joinToString("|")).hashCode()
        ) + "-" + System.nanoTime().toString(16)

        return Report(kind, chain, frames, meta, id)
    }

    /**
     * Watches the main thread and reports a stall as an ANR.
     *
     * Deliberately simple: a heartbeat posted to the main looper, checked from
     * a daemon thread. It cannot see a true system ANR after the process is
     * killed, but it catches the long main-thread blocks that cause them --
     * which is the part we can actually fix.
     */
    fun startAnrWatchdog(thresholdMs: Long = 5_000L) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var reported = false
            while (true) {
                val ticked = AtomicBoolean(false)
                main.post { ticked.set(true) }
                Thread.sleep(thresholdMs)
                if (!ticked.get()) {
                    if (!reported) {
                        reported = true
                        val t = Looper.getMainLooper().thread
                        val anr = Throwable("main thread stalled").apply {
                            stackTrace = t.stackTrace
                        }
                        report("anr", anr, mapOf("thread" to "main"))
                    }
                } else {
                    reported = false
                }
            }
        }, "anr-watchdog").apply { isDaemon = true }.start()
    }

    /**
     * The local spool: redacted reports under `filesDir/crash`. No network, no
     * vendor, no account.
     *
     * Readable as well as writable, because its job is not only to record a
     * crash but to hold it until something can deliver it. The file contains
     * exactly what [Report.render] produced, which has already been through
     * the allowlist -- so even this file cannot contain transcript text,
     * filenames or credentials.
     */
    class FileSink(context: Context) : Sink {
        private val dir = File(context.filesDir, "crash").apply { mkdirs() }

        override fun send(report: Report) {
            runCatching {
                File(dir, fileName(report)).writeText(report.render())
                // Keep the directory bounded; these are diagnostics, not data.
                dir.listFiles()?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_SPOOLED)?.forEach { it.delete() }
            }
        }

        /** Everything spooled and not yet delivered, oldest first. */
        fun pending(): List<Report> =
            dir.listFiles().orEmpty().sortedBy { it.lastModified() }
                .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }

        fun discard(id: String) {
            runCatching { File(dir, "$id.txt").delete() }
        }

        private fun fileName(report: Report) =
            (report.id.ifBlank { "${report.kind}-${System.currentTimeMillis()}" }) + ".txt"

        /**
         * Reads a spooled report back. Deliberately reconstructs only the
         * fields render() writes: a round-trip through the rendered form means
         * nothing can re-enter a report that the allowlist kept out of it.
         */
        private fun parse(text: String): Report {
            val meta = LinkedHashMap<String, String>()
            val chain = mutableListOf<String>()
            val frames = mutableListOf<String>()
            var kind = "crash"
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("kind=") -> kind = line.removePrefix("kind=")
                    line.startsWith("caused-by=") ->
                        chain += line.removePrefix("caused-by=")
                    line.startsWith("  at ") -> frames += line.removePrefix("  at ")
                    line.contains('=') -> {
                        val i = line.indexOf('=')
                        meta[line.take(i)] = line.substring(i + 1)
                    }
                }
            }
            return Report(kind, chain, frames, meta, meta["id"] ?: "")
        }

        private companion object {
            const val MAX_SPOOLED = 20
        }
    }
}
