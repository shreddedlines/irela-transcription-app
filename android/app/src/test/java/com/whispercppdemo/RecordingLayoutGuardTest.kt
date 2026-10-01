package com.whispercppdemo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-side guard for the Recording landscape fix and the top-bar inset fix.
 *
 * The real proof is `RecordingLayoutTest` (androidTest). This runs in every
 * unit run and fails fast if the structure that keeps the controls reachable,
 * or the title clear of the status bar, is removed.
 */
class RecordingLayoutGuardTest {

    private fun code(path: String) = File(path).readText().lines().filterNot {
        val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }.joinToString("\n")

    private val recording = code("src/main/java/com/whispercppdemo/ui/recording/RecordingScreen.kt")
    private val chrome = code("src/main/java/com/whispercppdemo/ui/common/AppChrome.kt")
    private val processing = code("src/main/java/com/whispercppdemo/ui/processing/ProcessingScreen.kt")
    private val permission = code("src/main/java/com/whispercppdemo/ui/permission/MicrophonePermissionScreen.kt")

    @Test
    fun `processing content scrolls with the viewport minimum after the scroll`() {
        val scroll = processing.indexOf(".verticalScroll(")
        val minH = processing.indexOf(".heightIn(min = viewport)")
        assertTrue(scroll in 0 until minH)
        assertTrue("no weighted spacer pushing Cancel off-screen",
                   !processing.contains("Spacer(Modifier.weight("))
    }

    @Test
    fun `bottom nav reserves the navigation bar inset below its tabs`() {
        val start = chrome.indexOf("fun AppBottomNav(")
        val body = chrome.substring(start, chrome.indexOf("\n}", start))
        assertTrue(body.contains("WindowInsets.navigationBars"))
        assertTrue("background before inset, or the strip under the handle is unpainted",
                   body.indexOf(".background(") in 0 until body.indexOf(".windowInsetsPadding("))
    }

    @Test
    fun `recording content scrolls when it overflows`() {
        assertTrue(recording.contains(".verticalScroll("))
    }

    @Test
    fun `scroll comes before the viewport minimum height`() {
        val scroll = recording.indexOf(".verticalScroll(")
        val minH = recording.indexOf(".heightIn(min = viewport)")
        assertTrue("heightIn(min) must follow verticalScroll or scrolling never engages",
                   scroll in 0 until minH)
    }

    @Test
    fun `no weighted spacers inside the scrolling column`() {
        // The layout relies on SpaceBetween, not weights, so its behaviour in
        // an unbounded scrolling column never depends on weight measurement.
        assertTrue(!recording.contains(".weight("))
        assertTrue(recording.contains("Arrangement.SpaceBetween"))
    }

    @Test
    fun `recording controls are still all present`() {
        listOf("\"Discard recording\"", "\"Stop recording\"",
               "\"Pause recording\"", "\"Resume recording\"").forEach {
            assertTrue("missing $it", recording.contains(it))
        }
    }

    @Test
    fun `recording screen never starts a capture by itself`() {
        // Capture starts from the record-mode sheet (and after the permission
        // screen). An auto-start here would record voice when the user chose
        // conversation or playback.
        assertTrue(!recording.contains("onStart"))
        assertTrue(!recording.contains("startRecording"))
    }

    @Test
    fun `permission screen scrolls with the viewport minimum after the scroll`() {
        val scroll = permission.indexOf(".verticalScroll(")
        val minH = permission.indexOf(".heightIn(min = viewport)")
        assertTrue(scroll in 0 until minH)
        // fillMaxSize belongs on the BoxWithConstraints, never on the scrolling column.
        assertTrue(permission.indexOf("BoxWithConstraints(modifier.fillMaxSize())") in 0 until scroll)
    }

    @Test
    fun `top bar reserves the status bar inset`() {
        val start = chrome.indexOf("fun AppTopBar(")
        val body = chrome.substring(start, chrome.indexOf("\n}", start))
        assertTrue("edge-to-edge: title must not draw under the status bar",
                   body.contains("WindowInsets.statusBars"))
        assertTrue(body.contains(".windowInsetsPadding("))
        assertTrue("side cutout must be honoured in landscape",
                   body.contains("WindowInsets.displayCutout"))
    }

    @Test
    fun `top bar background is applied before the inset padding`() {
        // Otherwise the status-bar strip is unpainted.
        val start = chrome.indexOf("fun AppTopBar(")
        val body = chrome.substring(start, chrome.indexOf("\n}", start))
        assertTrue(body.indexOf(".background(") in 0 until body.indexOf(".windowInsetsPadding("))
    }
}
