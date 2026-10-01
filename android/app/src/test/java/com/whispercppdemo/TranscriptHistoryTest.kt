package com.whispercppdemo

import com.whispercppdemo.history.HistoryWriter
import com.whispercppdemo.history.TranscriptHistoryRepository
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.transcribe.TranscriptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for completed-transcript history.
 *
 * The repository is deliberately free of Android types, so these run as
 * ordinary JVM unit tests against a temp directory.
 */
class TranscriptHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = TranscriptHistoryRepository(tmp.root)

    // ---- 1-3: which outcomes become history -------------------------------

    @Test
    fun `successful transcription creates one history record`() {
        val r = repo()
        val stored = HistoryWriter.recordIfCompleted(
            repository = r,
            state = TranscriptionState.Done("PTT-20260901-WA0039.opus", "hello world"),
            source = TranscriptSource.IMPORT,
            durationMs = 64273
        )
        assertNotNull(stored)

        val all = r.loadAll()
        assertEquals(1, all.size)
        assertEquals("PTT-20260901-WA0039.opus", all[0].displayName)
        assertEquals("hello world", all[0].text)
        assertEquals(TranscriptSource.IMPORT, all[0].source)
        assertEquals(64273L, all[0].durationMs)
    }

    @Test
    fun `cancelled transcription creates no history record`() {
        val r = repo()
        val stored = HistoryWriter.recordIfCompleted(
            repository = r,
            state = TranscriptionState.Cancelled("wa_voice.ogg"),
            source = TranscriptSource.IMPORT,
            durationMs = 96833
        )
        assertNull(stored)
        assertEquals(0, r.loadAll().size)
    }

    @Test
    fun `failed transcription creates no history record`() {
        val r = repo()
        val stored = HistoryWriter.recordIfCompleted(
            repository = r,
            state = TranscriptionState.Failed("broken.opus", "That audio file appears to be damaged"),
            source = TranscriptSource.IMPORT,
            durationMs = null
        )
        assertNull(stored)
        assertEquals(0, r.loadAll().size)
    }

    @Test
    fun `in-flight states create no history record`() {
        val r = repo()
        assertNull(HistoryWriter.recordIfCompleted(r, TranscriptionState.Idle, TranscriptSource.IMPORT, null))
        assertNull(HistoryWriter.recordIfCompleted(r, TranscriptionState.Staging("a"), TranscriptSource.IMPORT, null))
        assertNull(HistoryWriter.recordIfCompleted(r, TranscriptionState.Decoding("a", 0.5f), TranscriptSource.IMPORT, null))
        assertNull(HistoryWriter.recordIfCompleted(r, TranscriptionState.Transcribing("a", 1, 4), TranscriptSource.IMPORT, null))
        assertEquals(0, r.loadAll().size)
    }

    @Test
    fun `an empty transcript is not stored`() {
        val r = repo()
        assertNull(
            HistoryWriter.recordIfCompleted(
                r, TranscriptionState.Done("silence.opus", "   "), TranscriptSource.IMPORT, 1000
            )
        )
        assertEquals(0, r.loadAll().size)
    }

    // ---- 4: durability ----------------------------------------------------

    @Test
    fun `records survive repository recreation`() {
        repo().save(
            TranscriptRecord("id-1", "note.opus", 1000L, "persisted text", TranscriptSource.IMPORT, 5000L)
        )

        // A brand new repository over the same directory, as after a restart.
        val reopened = TranscriptHistoryRepository(tmp.root).loadAll()
        assertEquals(1, reopened.size)
        assertEquals("id-1", reopened[0].id)
        assertEquals("persisted text", reopened[0].text)
        assertEquals(5000L, reopened[0].durationMs)
        assertEquals(TranscriptSource.IMPORT, reopened[0].source)
        assertEquals(1000L, reopened[0].createdAt)
    }

    // ---- 5: ordering ------------------------------------------------------

    @Test
    fun `newest records appear first`() {
        val r = repo()
        r.save(TranscriptRecord("old", "old.opus", 1_000L, "oldest", TranscriptSource.IMPORT, null))
        r.save(TranscriptRecord("new", "new.opus", 3_000L, "newest", TranscriptSource.RECORDING, null))
        r.save(TranscriptRecord("mid", "mid.opus", 2_000L, "middle", TranscriptSource.IMPORT, null))

        assertEquals(listOf("newest", "middle", "oldest"), r.loadAll().map { it.text })
    }

    @Test
    fun `ordering is deterministic when timestamps collide`() {
        val r = repo()
        r.save(TranscriptRecord("aaa", "a", 5L, "a", TranscriptSource.IMPORT, null))
        r.save(TranscriptRecord("bbb", "b", 5L, "b", TranscriptSource.IMPORT, null))
        assertEquals(r.loadAll().map { it.id }, r.loadAll().map { it.id })
        assertEquals(listOf("bbb", "aaa"), r.loadAll().map { it.id })
    }

    // ---- round-trip integrity ---------------------------------------------

    @Test
    fun `transcript text with newlines tabs and equals signs round-trips`() {
        val r = repo()
        val nasty = "line one\nline=two\tthird\r\n  Aap thoda — 09:00 100% हिंदी"
        r.save(TranscriptRecord("x", "n=a\tme.opus", 42L, nasty, TranscriptSource.RECORDING, 7L))
        val back = TranscriptHistoryRepository(tmp.root).loadAll().single()
        assertEquals(nasty, back.text)
        assertEquals("n=a\tme.opus", back.displayName)
    }

    @Test
    fun `unknown duration round-trips as null`() {
        val r = repo()
        r.save(TranscriptRecord("x", "a.opus", 1L, "t", TranscriptSource.IMPORT, null))
        assertNull(TranscriptHistoryRepository(tmp.root).loadAll().single().durationMs)
    }

    @Test
    fun `both source types round-trip`() {
        val r = repo()
        r.save(TranscriptRecord("a", "a", 2L, "t", TranscriptSource.RECORDING, null))
        r.save(TranscriptRecord("b", "b", 1L, "t", TranscriptSource.IMPORT, null))
        assertEquals(
            listOf(TranscriptSource.RECORDING, TranscriptSource.IMPORT),
            r.loadAll().map { it.source }
        )
    }

    // ---- robustness -------------------------------------------------------

    @Test
    fun `empty or missing directory loads as empty`() {
        assertEquals(0, repo().loadAll().size)
        assertEquals(0, TranscriptHistoryRepository(tmp.newFolder("nope").also { it.delete() }).loadAll().size)
    }

    @Test
    fun `a malformed file is skipped rather than failing the whole load`() {
        val r = repo()
        r.save(TranscriptRecord("good", "g.opus", 10L, "kept", TranscriptSource.IMPORT, null))
        java.io.File(tmp.root, "junk.rec").writeText("not a record at all")
        val all = r.loadAll()
        assertEquals(1, all.size)
        assertEquals("kept", all[0].text)
    }

    @Test
    fun `saving twice with the same id replaces rather than duplicates`() {
        val r = repo()
        r.save(TranscriptRecord("same", "a.opus", 1L, "first", TranscriptSource.IMPORT, null))
        r.save(TranscriptRecord("same", "a.opus", 2L, "second", TranscriptSource.IMPORT, null))
        val all = r.loadAll()
        assertEquals(1, all.size)
        assertEquals("second", all[0].text)
    }

    @Test
    fun `newId returns distinct ids`() {
        val ids = (1..100).map { TranscriptHistoryRepository.newId() }.toSet()
        assertTrue(ids.size == 100)
    }
}
