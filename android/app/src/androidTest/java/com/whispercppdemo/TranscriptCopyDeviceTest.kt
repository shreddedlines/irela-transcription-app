package com.whispercppdemo

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.detail.TranscriptDetailScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Transcript actions are exactly Copy and Share; one tap on Copy copies the text. */
@RunWith(AndroidJUnit4::class)
class TranscriptCopyDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val record = TranscriptRecord("t1", "Client call notes", System.currentTimeMillis(),
        "First sentence. Second sentence.\nनमस्ते, कल मिलते हैं।", TranscriptSource.RECORDING, 28_930L)
    private val messages = mutableListOf<String>()

    private fun show() = compose.setContent {
        WhisperCppDemoTheme {
            Box(Modifier.size(412.dp, 800.dp)) { TranscriptDetailScreen(record, onMessage = { messages += it }) }
        }
    }

    @Test
    fun copyCopiesTheTranscriptImmediately_withNoMenu() {
        show()
        compose.onNodeWithText("Copy").assertIsDisplayed().assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val clip = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("exactly the transcript, unchanged", record.text,
                         clip.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(listOf("Copied to clipboard"), messages)
        }
        listOf("Copy text", "Copy with paragraphs").forEach {
            assertEquals("no copy menu ($it)", 0, compose.onAllNodes(hasText(it)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun onlyCopyAndShare_andCopyIsAPlainButtonForAccessibility() {
        show()
        val actions = compose.onAllNodes(hasClickAction() and (hasText("Copy") or hasText("Share")))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(listOf("Copy", "Share"), actions.sorted())
        // No popup affordance: nothing expandable/collapsible and no dropdown state.
        val copy = compose.onNodeWithText("Copy").fetchSemanticsNode()
        assertEquals(false, copy.config.contains(SemanticsActions.Expand))
        assertEquals(false, copy.config.contains(SemanticsActions.Collapse))
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .fetchSemanticsNodes().size)
    }
}
