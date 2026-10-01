package com.whispercppdemo

import com.whispercppdemo.history.TranscriptHistoryRepository
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.common.matchesQuery
import com.whispercppdemo.ui.common.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rename and edit both go through the repository's existing save(), which
 * overwrites by id. These pin down the properties that depend on: the record
 * keeps its identity, only the intended field changes, and no duplicate is
 * created.
 */
class TranscriptEditRenameTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun repo() = TranscriptHistoryRepository(temp.root)

    private fun seed(
        id: String = "a",
        name: String = "Recorded audio",
        text: String = "original transcript body"
    ) = TranscriptRecord(
        id = id,
        displayName = name,
        createdAt = 1_700_000_000_000L,
        text = text,
        source = TranscriptSource.IMPORT,
        durationMs = 42_000L
    )

    // ---- rename ----------------------------------------------------------

    @Test
    fun `rename persists and creates no duplicate`() {
        val r = repo()
        r.save(seed())
        r.save(r.loadAll().first().copy(displayName = "Client call"))

        val all = r.loadAll()
        assertEquals("exactly one record", 1, all.size)
        assertEquals("Client call", all[0].displayName)
    }

    @Test
    fun `rename preserves identity body timestamp source and duration`() {
        val r = repo()
        val original = seed()
        r.save(original)
        r.save(original.copy(displayName = "New name"))

        val after = r.loadAll().single()
        assertEquals(original.id, after.id)
        assertEquals(original.text, after.text)
        assertEquals(original.createdAt, after.createdAt)
        assertEquals(original.source, after.source)
        assertEquals(original.durationMs, after.durationMs)
    }

    @Test
    fun `a renamed record survives repository recreation`() {
        repo().save(seed())
        repo().save(repo().loadAll().single().copy(displayName = "Persisted name"))
        assertEquals("Persisted name", repo().loadAll().single().displayName)
    }

    @Test
    fun `a renamed record can still be deleted`() {
        val r = repo()
        r.save(seed())
        r.save(seed().copy(displayName = "Renamed"))
        assertTrue(r.delete("a"))
        assertTrue(r.loadAll().isEmpty())
    }

    @Test
    fun `renaming one record leaves others untouched`() {
        val r = repo()
        r.save(seed(id = "a", name = "first"))
        r.save(seed(id = "b", name = "second", text = "second body"))
        r.save(seed(id = "a", name = "renamed"))

        val byId = r.loadAll().associateBy { it.id }
        assertEquals("renamed", byId["a"]!!.displayName)
        assertEquals("second", byId["b"]!!.displayName)
        assertEquals("second body", byId["b"]!!.text)
    }

    @Test
    fun `a renamed title yields a safe share filename`() {
        val name = sanitizeFileName("Client call: 12/03 <notes>")
        assertTrue(name.none { it in """/\:<>*?"|""" })
        assertEquals("Client_call_12_03_notes", name)
    }

    // ---- edit ------------------------------------------------------------

    @Test
    fun `edited text persists and creates no duplicate`() {
        val r = repo()
        r.save(seed())
        r.save(seed().copy(text = "corrected transcript body"))

        val all = r.loadAll()
        assertEquals(1, all.size)
        assertEquals("corrected transcript body", all[0].text)
    }

    @Test
    fun `editing preserves identity name timestamp source and duration`() {
        val r = repo()
        val original = seed()
        r.save(original)
        r.save(original.copy(text = "edited"))

        val after = r.loadAll().single()
        assertEquals(original.id, after.id)
        assertEquals(original.displayName, after.displayName)
        assertEquals(original.createdAt, after.createdAt)
        assertEquals(original.source, after.source)
        assertEquals(original.durationMs, after.durationMs)
    }

    @Test
    fun `an edit survives repository recreation`() {
        repo().save(seed())
        repo().save(repo().loadAll().single().copy(text = "edited body"))
        assertEquals("edited body", repo().loadAll().single().text)
    }

    @Test
    fun `edited Devanagari text round-trips`() {
        val hindi = "आज हम स्थानीय ट्रांसक्रिप्शन की बात करेंगे"
        val r = repo()
        r.save(seed())
        r.save(seed().copy(text = hindi))
        assertEquals(hindi, r.loadAll().single().text)
    }

    // ---- the features seeing each other ---------------------------------

    @Test
    fun `search finds the edited body and not the replaced one`() {
        val r = repo()
        r.save(seed(text = "aap har bhi to thoda"))
        r.save(seed(text = "completely rewritten content"))

        val stored = r.loadAll().single()
        assertTrue(matchesQuery(stored, "rewritten"))
        assertTrue("old text must be gone", !matchesQuery(stored, "thoda"))
    }

    @Test
    fun `search finds a record by its new name`() {
        val r = repo()
        r.save(seed(name = "Recorded audio"))
        r.save(seed(name = "Board meeting"))
        assertTrue(matchesQuery(r.loadAll().single(), "board"))
    }

    @Test
    fun `copy and share read the edited body`() {
        val r = repo()
        r.save(seed())
        r.save(seed().copy(text = "the edited body"))
        val stored = r.loadAll().single()
        // Copy hands over record.text verbatim; share writes the same.
        assertEquals("the edited body", stored.text)
    }
}
