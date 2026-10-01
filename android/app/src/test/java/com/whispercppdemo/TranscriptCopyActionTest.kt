package com.whispercppdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The transcript screen has exactly Copy and Share; Copy copies at once. */
class TranscriptCopyActionTest {

    private val detail = File("src/main/java/com/whispercppdemo/ui/detail/TranscriptDetailScreen.kt").readText()

    @Test
    fun copyIsOneImmediateActionWithNoMenu() {
        listOf("Copy with paragraphs", "Copied with paragraphs", "withParagraphs", "AppMenu", "MenuEntry",
               "ArrowDropDown", "trailingIcon", "copyMenuOpen").forEach {
            assertFalse("transcript screen still has $it", detail.contains(it))
        }
        assertEquals(1, Regex("""AppButton\("Copy", onCopy""").findAll(detail).count())
        assertEquals(1, Regex("""AppButton\("Share", onShare""").findAll(detail).count())
        assertTrue(detail.contains("clipboard.setText(AnnotatedString(record.text))"))
        assertTrue(detail.contains("onMessage(\"Copied to clipboard\")"))
    }

    @Test
    fun theParagraphHelperIsGoneFromTheApp() {
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach {
            assertFalse(it.name, it.readText().contains("withParagraphs"))
        }
    }
}
