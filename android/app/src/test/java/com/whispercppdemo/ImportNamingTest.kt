package com.whispercppdemo

import com.whispercppdemo.history.AutoNames
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.media.ImportNames
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import com.whispercppdemo.ui.common.TitleClock
import com.whispercppdemo.ui.common.Titled
import com.whispercppdemo.ui.common.TranscriptTitles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Imported files are titled with their real file name. Found in the first real
 * AssemblyAI run: a picker search result became "msf:1000081972" in History,
 * because the job runner's in-memory record overwrote the DISPLAY_NAME that
 * staging had stored.
 */
class ImportNamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- the name chosen for an import -------------------------------------

    @Test
    fun normalImportedFilenameIsKept() {
        assertEquals("hinglish_30s.wav", ImportNames.forImport("hinglish_30s.wav", "hinglish_30s.wav"))
        assertEquals("PTT-20260912-WA0014.opus", ImportNames.forImport("PTT-20260912-WA0014.opus", null))
        assertEquals("Client call 12.m4a", ImportNames.forImport(null, "raw:/storage/emulated/0/Download/Client%20call%2012.m4a"))
    }

    @Test
    fun pickerSearchResultUsesTheQueriedDisplayNameNotTheDocumentId() {
        assertEquals("hinglish_30s.wav", ImportNames.forImport("hinglish_30s.wav", "msf:1000081972"))
        assertEquals("hinglish_30s.wav", ImportNames.forImport("hinglish_30s.wav", "msf%3A1000081972"))
        assertEquals("voice note.ogg", ImportNames.forImport("voice note.ogg", "1000081972"))   // MediaStore row id
        assertEquals("song.mp3", ImportNames.forImport("song.mp3", "audio:42"))
    }

    @Test
    fun longFilenamesAreTruncatedKeepingTheExtension() {
        val long = "Quarterly planning meeting with the regional distribution team ".repeat(6).trim() + ".m4a"
        val stored = ImportNames.forImport(long, "msf:7")
        assertEquals(ImportNames.MAX_LENGTH, stored.length)
        assertTrue(stored, stored.endsWith("….m4a"))
        assertTrue(stored.startsWith("Quarterly planning meeting"))
        val noExt = "x".repeat(300)
        assertEquals(ImportNames.MAX_LENGTH, ImportNames.forImport(noExt, null).length)
        assertTrue(ImportNames.forImport(noExt, null).endsWith("…"))
        assertEquals("short.wav", ImportNames.truncate("short.wav"))
    }

    @Test
    fun missingOrUnusableNamesFallBackAndNeverShowAnId() {
        listOf(
            null to "msf:1000081972", null to "msf%3A1000081972", null to "1000081972",
            "" to "document:acc=1;doc=7", "   " to "3f2a9c1e-7b4d-4e8a-9c2b-1d5e6f7a8b9c.aac",
            null to null, "msf:1000081972" to null
        ).forEach { (display, segment) ->
            assertEquals("$display / $segment", ImportNames.FALLBACK, ImportNames.forImport(display, segment))
        }
        // Control characters never reach a title.
        assertEquals("call.wav", ImportNames.forImport("call\u0000.wav", null))
    }

    // ---- titles already stored ---------------------------------------------

    private val clock = object : TitleClock {
        override fun now() = 1_789_500_000_000L
        override fun time(ms: Long) = "8:30 pm"
        override fun timeWithSeconds(ms: Long) = "8:30:12 pm"
        override fun dayMonth(ms: Long) = "13 Sept"
    }

    @Test
    fun anExistingRecordHoldingADocumentIdIsTitledAsAnImport() {
        assertEquals(ImportNames.FALLBACK, TranscriptTitles.storedTitle("msf:1000081972"))
        assertEquals(ImportNames.FALLBACK, TranscriptTitles(clock).short(Titled("msf:1000081972", 1L)))
        assertEquals(ImportNames.FALLBACK, TranscriptTitles.storedTitle("3f2a9c1e-7b4d-4e8a-9c2b-1d5e6f7a8b9c.wav"))
    }

    @Test
    fun renamedImportRemainsRenamed() {
        val titles = TranscriptTitles(clock)
        val item = Titled("hinglish_30s.wav", 1L)
        assertEquals("Client call notes", titles.nameToStore(item, "Client call notes"))
        listOf("Client call notes", "2024", "Budget: Q3", "Meeting notes 12.m4a").forEach {
            assertEquals(it, TranscriptTitles.storedTitle(it))
            assertEquals(it, titles.short(Titled(it, 1L)))
        }
    }

    @Test
    fun automaticRecordingNamesAreUnaffected() {
        assertFalse(ImportNames.looksLikeDocumentId(AutoNames.RECORDING))
        assertFalse(ImportNames.looksLikeDocumentId(AutoNames.CONVERSATION))
        assertTrue(TranscriptTitles(clock).short(Titled(AutoNames.RECORDING, 1L)).startsWith("Recording · "))
    }

    // ---- where the name was lost: the job runner --------------------------

    private class Ok : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        override suspend fun transcribe(request: TranscriptionRequest, onProgress: (Float) -> Unit) =
            TranscriptionOutcome.Success("hello there", "assemblyai", audioDurationMs = 30_020L)
    }

    private fun runJob(submittedName: String, stagedName: String): Pair<JobRecord, String?> = runBlocking {
        val store = JobStore(tmp.newFolder())
        var persistedName: String? = null
        val audio = File(tmp.root, "staged-${System.nanoTime()}.wav").apply { writeText("x") }
        val runner = JobRunner(
            store = store, provider = Ok(),
            // Exactly what TranscriptionService.prepareAudio does after staging.
            prepare = { job ->
                if (stagedName.isNotBlank() && stagedName != job.displayName && !AutoNames.isKindName(job.displayName))
                    store.update(job.copy(displayName = stagedName))
                PreparedAudio(audio, "audio/wav")
            },
            persistTranscript = { job, _, _ -> persistedName = job.displayName; "t-1" },
            sleep = { }
        )
        val done = runner.run(runner.submit("content://com.android.providers.media.documents/document/msf%3A1000081972", submittedName))
        done to persistedName
    }

    @Test
    fun theStagedDisplayNameSurvivesEveryLaterTransitionAndTitlesTheTranscript() {
        val (job, persisted) = runJob(submittedName = ImportNames.forImport(null, "msf:1000081972"),
                                      stagedName = "hinglish_30s.wav")
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals("the History record gets the real file name", "hinglish_30s.wav", persisted)
        assertEquals("the job record keeps it too", "hinglish_30s.wav", job.displayName)
    }

    @Test
    fun aRecordingKeepsItsKindName() {
        val (job, persisted) = runJob(submittedName = AutoNames.RECORDING, stagedName = "recording-3f2a9c1e.wav")
        assertEquals(AutoNames.RECORDING, persisted)
        assertEquals(AutoNames.RECORDING, job.displayName)
    }

    @Test
    fun noUsableStagedNameKeepsTheFallback() {
        val (_, persisted) = runJob(submittedName = ImportNames.FALLBACK, stagedName = "")
        assertEquals(ImportNames.FALLBACK, persisted)
    }

    @Test
    fun serviceNamesImportsThroughImportNames_notTheRawUriSegment() {
        val service = File("src/main/java/com/whispercppdemo/transcribe/TranscriptionService.kt").readText()
        val submit = service.substring(service.indexOf("val submittedName ="), service.indexOf("// No silent drop."))
        assertTrue(submit.contains("ImportNames.forImport("))
        assertTrue(submit.contains("queryDisplayName(applicationContext, uri)"))
        assertNull("the bare URI segment is no longer a name on its own", Regex("""\?: uri\.lastPathSegment \?:""").find(submit))
    }
}
