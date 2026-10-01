package com.whispercppdemo

import com.whispercppdemo.history.TranscriptHistoryRepository
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Deletion, against a real directory on disk.
 *
 * The file-per-record layout is what makes this testable without Android: a
 * delete is one unlink, and "did it persist" is answered by building a second
 * repository over the same directory.
 */
class TranscriptDeleteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun repo(dir: File = temp.root) = TranscriptHistoryRepository(dir)

    private fun record(
        id: String,
        text: String = "hello",
        createdAt: Long = 1_000L
    ) = TranscriptRecord(
        id = id,
        displayName = "clip-$id",
        createdAt = createdAt,
        text = text,
        source = TranscriptSource.RECORDING,
        durationMs = 5_000L
    )

    @Test
    fun `deleting an existing record removes it`() {
        val r = repo()
        r.save(record("a"))
        assertTrue(r.delete("a"))
        assertTrue(r.loadAll().isEmpty())
    }

    @Test
    fun `deleting a missing record is safe and reports false`() {
        val r = repo()
        r.save(record("a"))
        assertFalse(r.delete("does-not-exist"))
        // The real record is untouched.
        assertEquals(listOf("a"), r.loadAll().map { it.id })
    }

    @Test
    fun `deleting the same record twice is safe`() {
        val r = repo()
        r.save(record("a"))
        assertTrue(r.delete("a"))
        assertFalse(r.delete("a"))
        assertTrue(r.loadAll().isEmpty())
    }

    @Test
    fun `deleting one leaves every other record untouched`() {
        val r = repo()
        r.save(record("a", text = "first"))
        r.save(record("b", text = "second", createdAt = 2_000L))
        r.save(record("c", text = "third", createdAt = 3_000L))

        assertTrue(r.delete("b"))

        val left = r.loadAll()
        assertEquals(listOf("c", "a"), left.map { it.id })
        assertEquals("third", left[0].text)
        assertEquals("first", left[1].text)
    }

    @Test
    fun `delete all removes every record and reports the count`() {
        val r = repo()
        r.save(record("a"))
        r.save(record("b", createdAt = 2_000L))
        r.save(record("c", createdAt = 3_000L))

        assertEquals(3, r.deleteAll())
        assertTrue(r.loadAll().isEmpty())
    }

    @Test
    fun `delete all on an empty history is safe`() {
        val r = repo()
        assertEquals(0, r.deleteAll())
        assertTrue(r.loadAll().isEmpty())
    }

    @Test
    fun `delete all on a directory that does not exist is safe`() {
        val r = repo(File(temp.root, "never-created"))
        assertEquals(0, r.deleteAll())
        assertTrue(r.loadAll().isEmpty())
    }

    @Test
    fun `delete all leaves non-record files in the directory alone`() {
        // deleteAll must be scoped to the repository's own files. Nothing else
        // lives in this directory today, but the guarantee is what stops a
        // future caller pointing it somewhere shared and losing data.
        val r = repo()
        r.save(record("a"))
        val bystander = File(temp.root, "keep-me.bin").apply { writeText("not a transcript") }

        r.deleteAll()

        assertTrue(bystander.exists())
        assertEquals("not a transcript", bystander.readText())
    }

    @Test
    fun `a deletion survives repository recreation`() {
        repo().save(record("a"))
        repo().save(record("b", createdAt = 2_000L))
        assertTrue(repo().delete("a"))

        // A fresh repository over the same directory sees the same result.
        assertEquals(listOf("b"), repo().loadAll().map { it.id })
    }

    @Test
    fun `delete all survives repository recreation`() {
        repo().save(record("a"))
        repo().save(record("b", createdAt = 2_000L))
        repo().deleteAll()
        assertTrue(repo().loadAll().isEmpty())
    }

    @Test
    fun `a Devanagari transcript survives an unrelated deletion`() {
        val devanagari = "आज हम स्थानीय ट्रांसक्रिप्शन की बात करेंगे\tline=two\nAap thoda — 100%"
        val r = repo()
        r.save(record("keep", text = devanagari, createdAt = 2_000L))
        r.save(record("drop", text = "throwaway"))

        assertTrue(r.delete("drop"))

        val left = r.loadAll()
        assertEquals(1, left.size)
        assertEquals(devanagari, left[0].text)
    }

    @Test
    fun `an id that would escape the history directory is refused`() {
        val r = repo()
        r.save(record("a"))
        val outside = File(temp.root.parentFile, "outside.rec").apply { writeText("v=1") }

        assertFalse(r.delete("../outside"))

        assertTrue(outside.exists())
        assertEquals(listOf("a"), r.loadAll().map { it.id })
        outside.delete()
    }

    @Test
    fun `loadAll reports nothing after every record is deleted`() {
        val r = repo()
        repeat(5) { i -> r.save(record("id$i", createdAt = i.toLong())) }
        r.deleteAll()
        assertTrue(r.loadAll().isEmpty())
        assertNull(r.loadAll().firstOrNull())
    }
}
