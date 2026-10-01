package com.whispercppdemo

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.media.ImportNames
import com.whispercppdemo.media.queryDisplayName
import com.whispercppdemo.media.stageAudio
import com.whispercppdemo.ui.history.HistoryScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the real device: a content URI whose last segment is an opaque id (as a
 * picker or search result hands over) is named from DISPLAY_NAME, and an old
 * record holding a document id is never shown with it. No network.
 */
@RunWith(AndroidJUnit4::class)
class ImportNameDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun anOpaqueIdUriIsNamedFromDisplayName() = runBlocking {
        assumeTrue("MediaStore.Downloads needs API 29", Build.VERSION.SDK_INT >= 29)
        val name = "import_name_device_test_${System.currentTimeMillis()}.wav"
        val resolver = context.contentResolver
        var uri: Uri? = null
        var staged: java.io.File? = null
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            })!!
            resolver.openOutputStream(uri)!!.use { it.write(ByteArray(2048) { i -> (i % 7).toByte() }) }

            val segment = uri.lastPathSegment!!
            assertTrue("the URI carries an id, not the name ($segment)", segment.all { it.isDigit() })
            assertEquals(name, queryDisplayName(context, uri))
            assertEquals(name, ImportNames.forImport(queryDisplayName(context, uri), segment))
            assertEquals("without DISPLAY_NAME the id is not used", ImportNames.FALLBACK,
                         ImportNames.forImport(null, segment))

            val result = stageAudio(context, uri)
            staged = result.file
            assertEquals(name, result.displayName)
            assertTrue("the staged copy is a UUID file, never the title", result.file.name != name)
        } finally {
            staged?.delete()
            uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    @Test
    fun historyNeverShowsAStoredDocumentId() {
        val now = System.currentTimeMillis()
        val records = listOf(
            TranscriptRecord("i1", "msf:1000081972", now - 60_000, "Old import.", TranscriptSource.IMPORT, 31_000L),
            TranscriptRecord("i2", "hinglish_30s.wav", now - 120_000, "New import.", TranscriptSource.IMPORT, 30_000L),
            TranscriptRecord("i3", "Client call notes", now - 180_000, "Renamed import.", TranscriptSource.IMPORT, 30_000L)
        )
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 900.dp)) {
                    HistoryScreen(history = records, attempts = emptyList(), selection = emptyList(),
                        onSelectionChange = {}, onOpenTranscript = {}, onOpenAttempt = {}, onEdit = {},
                        onRename = { _, _ -> }, onDelete = { _, _ -> }, onRetry = {}, onDeleteAll = {},
                        onStartTranscription = {}, onMessage = {})
                }
            }
        }
        compose.waitForIdle()
        fun count(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertEquals(0, count("msf:"))
        assertTrue(count(ImportNames.FALLBACK) >= 1)
        assertTrue(count("hinglish_30s.wav") >= 1)
        assertTrue("a renamed import keeps its name", count("Client call notes") >= 1)
    }
}
