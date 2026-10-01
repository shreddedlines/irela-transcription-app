package com.whispercppdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every diagnostic in production code goes through [com.whispercppdemo.diag.Diag].
 *
 * A raw `android.util.Log` call is not merely untidy: it is unconditional, so
 * it keeps writing in a release build. What it writes here is filenames,
 * content URIs and audio metadata -- the user's file names, in a buffer any
 * ADB session or OEM log collector can read. Diag is the gate that silences
 * that in release, so bypassing it is a real defect, and a source scan is the
 * only way to catch a reintroduction.
 */
class LogGateTest {

    private val sourceRoot = File("src/main/java/com/whispercppdemo")

    private fun sources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val callSite = Regex("""(?:android\.util\.)?\bLog\.[diwev]\(""")

    @Test
    fun `the source tree is where this test thinks it is`() {
        // Otherwise an empty scan would pass silently and prove nothing.
        assertTrue("source root not found: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        assertTrue(sources().size > 20)
    }

    @Test
    fun `no production code calls android util Log directly`() {
        val offenders = sources()
            .filter { it.name != "Diag.kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) ->
                        callSite.containsMatchIn(line) && !line.trimStart().startsWith("//")
                    }
                    .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
            }
        assertEquals("route these through Diag: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `no log call interpolates transcript text`() {
        // Rule 1: transcript text is never logged in any build. Lengths and
        // counts are fine, which is why `.length` is allowed through.
        val suspicious = Regex("""Diag\.[dw]\([^)]*\$\{?[A-Za-z]*\b(text|transcript)\b(?!\.length)""")
        val offenders = sources().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, l) -> suspicious.containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
