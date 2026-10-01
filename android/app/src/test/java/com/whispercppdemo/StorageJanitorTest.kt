package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.StorageJanitor
import com.whispercppdemo.recorder.RecordingFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Job-aware cleanup. The assertions that matter most are the negative ones:
 * audio a job needs is never deleted, and unknown recordings are recovered.
 */
class StorageJanitorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cache: File
    private lateinit var imports: File
    private lateinit var store: JobStore
    private val now = 10_000_000L

    @Before
    fun setUp() {
        cache = tmp.newFolder("cache")
        imports = File(cache, "imports").apply { mkdirs() }
        store = JobStore(tmp.newFolder("jobs"))
    }

    private fun file(dir: File, name: String) = File(dir, name).apply { writeBytes(ByteArray(2048)) }

    private fun job(state: JobState, source: File?, staged: File?, reason: String? = null): JobRecord {
        val j = store.create(source?.let { "file://${it.absolutePath}" } ?: "content://x", "x", 1)
        return store.update(j.copy(state = state, stagedPath = staged?.absolutePath, failureReason = reason))
    }

    private fun plan(inUse: Set<String> = emptySet(), age: Long = StorageJanitor.ORPHAN_GRACE_MS) =
        StorageJanitor.plan(cache, imports, store.all(), inUse, now, lastModified = { now - age })

    @Test
    fun `completed local recording - source WAV and staged copy are deleted`() {
        val wav = file(cache, "recording-a.wav"); val bin = file(imports, "a.bin")
        job(JobState.COMPLETED, wav, bin)
        val p = plan()
        assertEquals(setOf(wav, bin), p.delete.toSet())
        StorageJanitor.apply(p, store, now)
        assertFalse(wav.exists() || bin.exists())
    }

    @Test
    fun `legacy leaked recordings from the old naming are cleaned when their job completed`() {
        val legacy = file(cache, "recording1446715852250690428wav"); val bin = file(imports, "b.bin")
        job(JobState.COMPLETED, legacy, bin)
        assertEquals(setOf(legacy, bin), plan().delete.toSet())
    }

    @Test
    fun `failed job keeps the audio its Retry needs`() {
        val wav = file(cache, "recording-c.wav"); val m4a = file(imports, "rec-c.m4a")
        job(JobState.FAILED, wav, m4a, FailureReason.PROVIDER_UNAVAILABLE)
        val p = plan()
        assertTrue(m4a in p.keep)                                   // Retry sends this
        assertEquals(listOf(wav), p.delete)                         // the WAV it was made from is not needed
    }

    @Test
    fun `failed job with no staged copy keeps its source`() {
        val wav = file(cache, "recording-d.wav")
        job(JobState.FAILED, wav, null, FailureReason.INTERRUPTED)
        assertTrue(wav in plan().keep)
    }

    @Test
    fun `cancelled job keeps its staged audio`() {
        val m4a = file(imports, "rec-e.m4a")
        job(JobState.CANCELLED, null, m4a)
        assertTrue(m4a in plan().keep)
    }

    @Test
    fun `active job's audio and in-flight staging are never touched`() {
        val wav = file(cache, "recording-f.wav"); val partial = file(imports, "rec-f.m4a.partial")
        val copying = file(imports, "g.bin")
        job(JobState.UPLOADING, wav, null)
        val p = plan()
        assertTrue(p.delete.isEmpty())
        assertTrue(wav in p.keep && partial in p.keep && copying in p.keep)
    }

    @Test
    fun `stale partial encodes and orphan staged copies are deleted when idle`() {
        val partial = file(imports, "rec-h.m4a.partial"); val orphan = file(imports, "i.bin")
        val done = file(cache, "recording-h.wav")
        job(JobState.COMPLETED, done, null)
        assertTrue(plan().delete.containsAll(listOf(partial, orphan)))
    }

    @Test
    fun `a finished recording no job knows about is recovered, never deleted`() {
        val orphan = file(cache, "recording-j.wav")
        val p = plan()
        assertEquals(listOf(orphan), p.recover)
        val jobs = StorageJanitor.apply(p, store, now)
        assertTrue(orphan.isFile)
        val j = jobs.single()
        assertEquals(JobState.FAILED, j.state)
        assertEquals(FailureReason.INTERRUPTED, j.failureReason)
        assertEquals(orphan.absolutePath, j.stagedPath)
    }

    @Test
    fun `a just-finished recording waiting for consent or submission is kept during the grace period`() {
        val fresh = file(cache, "recording-k.wav")
        val p = plan(age = 60_000)
        assertTrue(fresh in p.keep && p.recover.isEmpty() && p.delete.isEmpty())
    }

    @Test
    fun `a live recording is left alone`() {
        val live = file(cache, "recording-l.wav")
        assertTrue(live in plan(inUse = setOf(live.absolutePath)).keep)
    }

    @Test
    fun `in-progress recording files are not the janitor's to touch`() {
        val partial = file(cache, "recording-m.wav" + RecordingFiles.IN_PROGRESS_SUFFIX)
        val p = plan()
        assertFalse(partial in p.delete || partial in p.recover || partial in p.keep)
    }

    @Test
    fun `other cache content is ignored`() {
        val share = File(cache, "share").apply { mkdirs() }
        val txt = file(share, "t.txt"); val other = file(cache, "something.bin")
        val p = plan()
        assertFalse(txt in p.delete || other in p.delete)
    }

    @Test
    fun `46 existing jobs survive a cleanup pass intact`() {
        repeat(46) { i ->
            val state = if (i % 2 == 0) JobState.COMPLETED else JobState.FAILED
            job(state, null, if (state == JobState.FAILED) file(imports, "keep-$i.bin") else null)
        }
        val before = store.all()
        StorageJanitor.apply(plan(), store, now)
        assertEquals(before, store.all())
        assertTrue(before.filter { it.state == JobState.FAILED }.all { File(it.stagedPath!!).isFile })
    }
}
