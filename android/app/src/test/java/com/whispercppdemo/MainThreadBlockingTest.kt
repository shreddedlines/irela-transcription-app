package com.whispercppdemo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel teardown must not block the main thread.
 *
 * The device ANR:
 *
 *     ANR in com.whispercppdemo (com.whispercppdemo/.MainActivity)
 *     Reason: Input dispatching timed out
 *       at kotlinx.coroutines.BlockingCoroutine.joinBlocking
 *       at com.whispercppdemo.ui.main.MainScreenViewModel.onCleared
 *
 * `onCleared()` ran `runBlocking { stopPlayback() }`; `stopPlayback()` was
 * `withContext(Dispatchers.Main)`. On the main thread that is a deadlock by
 * construction — the thread blocks waiting for work that can only run on the
 * thread it just blocked.
 *
 * This suite proves the hazard is real (so the guard below is not cargo cult),
 * proves a non-suspending cleanup is immune to it, and scans the source so the
 * pattern cannot come back into any lifecycle callback.
 */
class MainThreadBlockingTest {

    /** Stands in for `Dispatchers.Main`: one thread, and it is *the* thread. */
    private fun singleThread(): Pair<CoroutineDispatcher, java.util.concurrent.ExecutorService> {
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-main").apply { isDaemon = true }
        }
        return exec.asCoroutineDispatcher() to exec
    }

    /** Runs [block] on the fake main thread; true if it finished in time. */
    private fun onFakeMain(exec: java.util.concurrent.ExecutorService,
                           timeoutMs: Long, block: () -> Unit): Boolean {
        val done = AtomicBoolean(false)
        exec.execute {
            block()
            done.set(true)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !done.get()) {
            Thread.sleep(20)
        }
        return done.get()
    }

    // ---- the hazard is real ---------------------------------------------

    @Test
    fun `the old pattern deadlocks -- runBlocking on the thread it then waits for`() {
        val (dispatcher, exec) = singleThread()
        val finished = onFakeMain(exec, timeoutMs = 1_500) {
            // Exactly what onCleared used to do.
            runBlocking { withContext(dispatcher) { /* cleanup */ } }
        }
        assertFalse(
            "if this ever passes, the deadlock is gone and this test is obsolete",
            finished
        )
        exec.shutdownNow()
    }

    // ---- the replacement is immune --------------------------------------

    @Test
    fun `a non-suspending cleanup completes on the main thread`() {
        val (_, exec) = singleThread()
        var released = 0
        // The shape of releasePlayer(): plain calls, no dispatcher, no wait.
        val finished = onFakeMain(exec, timeoutMs = 1_500) { released++ }
        assertTrue("cleanup must complete immediately", finished)
        assertEquals(1, released)
        exec.shutdownNow()
    }

    @Test
    fun `cleanup semantics are preserved -- stop, release, then clear`() {
        // Models releasePlayer(): the order matters (release before clearing
        // the reference, or the player leaks) and it must be idempotent,
        // because onCleared can follow an explicit stop.
        val calls = mutableListOf<String>()
        var player: String? = "player"

        fun releasePlayer() {
            player?.let { calls += "stop" }
            player?.let { calls += "release" }
            player = null
        }

        releasePlayer()
        assertEquals(listOf("stop", "release"), calls)
        assertEquals(null, player)

        releasePlayer()   // second teardown must be a no-op, not a crash
        assertEquals(listOf("stop", "release"), calls)
    }

    // ---- the source cannot regress --------------------------------------

    private val appSources: List<File>
        get() = File("src/main/java/com/whispercppdemo")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Strips comment lines so prose about the bug does not trip the scan. */
    private fun codeLines(f: File): List<Pair<Int, String>> =
        f.readLines().withIndex()
            .map { (i, l) -> (i + 1) to l }
            .filterNot { (_, l) ->
                val t = l.trimStart()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }

    @Test
    fun `the scan is looking at real sources`() {
        assertTrue(File("src/main/java/com/whispercppdemo").isDirectory)
        assertTrue(appSources.size > 20)
    }

    @Test
    fun `no production code calls runBlocking`() {
        // runBlocking is legitimate in tests and in a `main()`. In an Android
        // app every entry point is already on a thread that something else
        // owns, so there is no safe caller left.
        val offenders = appSources.flatMap { f ->
            codeLines(f).filter { (_, l) -> Regex("""runBlocking\s*[({]""").containsMatchIn(l) }
                .map { (n, l) -> "${f.name}:$n: ${l.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `no lifecycle or UI callback performs a blocking coroutine wait`() {
        // The callbacks that run on the main thread. A blocking wait in any of
        // them freezes the UI exactly as onCleared did.
        val callbacks = listOf(
            "onCleared", "onCreate", "onStart", "onResume", "onPause",
            "onStop", "onDestroy", "onNewIntent", "onConfigurationChanged",
            "onBackPressed", "onClick", "onSelect"
        )
        val blocking = listOf(
            Regex("""runBlocking\s*[({]"""),
            Regex("""\.get\(\)\s*$"""),             // Future.get
            Regex("""Thread\.sleep\s*\("""),
            Regex("""\.join\(\)"""),                // Thread.join
            Regex("""latch\.await\s*\("""),
        )

        val offenders = mutableListOf<String>()
        appSources.forEach { f ->
            val lines = codeLines(f)
            var depth = 0
            var inCallback: String? = null
            lines.forEach { (n, line) ->
                if (inCallback == null) {
                    val hit = callbacks.firstOrNull {
                        Regex("""fun\s+$it\s*\(""").containsMatchIn(line)
                    }
                    if (hit != null) {
                        inCallback = hit
                        depth = line.count { it == '{' } - line.count { it == '}' }
                    }
                } else {
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    blocking.forEach { rx ->
                        if (rx.containsMatchIn(line)) {
                            offenders += "${f.name}:$n in $inCallback(): ${line.trim()}"
                        }
                    }
                    if (depth <= 0) inCallback = null
                }
            }
        }
        assertEquals("blocking wait inside a main-thread callback",
                     emptyList<String>(), offenders)
    }

    @Test
    fun `onCleared does exactly one thing and does not suspend`() {
        val vm = appSources.first { it.name == "MainScreenViewModel.kt" }
        val src = vm.readText()
        val start = src.indexOf("override fun onCleared()")
        assertTrue("onCleared not found", start > 0)
        val body = src.substring(start, src.indexOf("\n    }", start))

        assertFalse("must not block", body.contains("runBlocking"))
        assertFalse("must not switch dispatchers", body.contains("withContext"))
        assertFalse("viewModelScope is already cancelled here", body.contains("viewModelScope"))
        assertTrue("must still release the player", body.contains("releasePlayer()"))
    }
}
