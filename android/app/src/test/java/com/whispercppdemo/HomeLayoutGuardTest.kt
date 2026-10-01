package com.whispercppdemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-side guard for Home: the landscape reachability fix, and the approved
 * Concept A contents (one Record entry point, Import, nothing else competing).
 *
 * The real proof is `HomeLayoutTest` (androidTest), which measures Compose
 * layout at landscape sizes. This runs in every unit test run and fails fast
 * if the structure that makes the actions reachable, or the approved contents,
 * are removed.
 */
class HomeLayoutGuardTest {

    private val src = File("src/main/java/com/whispercppdemo/ui/home/HomeScreen.kt").readText()

    /** Source minus comments, so the explanation of the bug cannot satisfy a check. */
    private val code = src.lines().filterNot {
        val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }.joinToString("\n")

    @Test
    fun `home content scrolls when it overflows`() {
        assertTrue("Home must be scrollable or landscape clips the actions",
                   code.contains(".verticalScroll("))
    }

    @Test
    fun `the composition fills at least the viewport`() {
        assertTrue("min height must track the viewport",
                   code.contains(".heightIn(min = viewport)"))
    }

    @Test
    fun `the scroll is applied before the minimum height`() {
        // heightIn(min) BEFORE verticalScroll would constrain the scroll
        // viewport itself and scrolling would never engage.
        val scroll = code.indexOf(".verticalScroll(")
        val minH = code.indexOf(".heightIn(min = viewport)")
        assertTrue(scroll in 0 until minH)
    }

    @Test
    fun `the top offset shrinks in a short viewport`() {
        // The approved 56dp top offset is pure dead space in landscape, where it
        // would push the actions further off-screen.
        assertTrue(code.contains("if (viewport < 560.dp) 16.dp else 56.dp"))
    }

    @Test
    fun `record and import are both on Home and never hidden`() {
        assertTrue(code.contains("\"Record audio\""))
        assertTrue(code.contains("\"Import audio\""))
        assertFalse("actions must not be hidden behind a condition",
                    Regex("""if\s*\([^)]*\)\s*\{\s*(RecordButton|ImportButton)""").containsMatchIn(code))
    }

    @Test
    fun `home has one recording entry point and no source row`() {
        assertFalse("conversation capture lives in the record-mode sheet, not on Home",
                    code.contains("Transcribe conversation"))
        assertFalse("no microphone source row on Home", code.contains("Using microphone"))
        assertFalse("no Change action on Home", code.contains("\"Change\""))
        assertFalse("Home is not a transcript browser", code.contains("TranscriptRow"))
    }

    @Test
    fun `enlarged text stacks the top band instead of overlapping`() {
        assertTrue(code.contains("fontScale > 1.15f"))
    }
}
