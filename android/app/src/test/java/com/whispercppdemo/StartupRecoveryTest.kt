package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.ui.common.PrivacyCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Cold-start recovery, and the staged audio it depends on.
 *
 * The device bug: a force-stop left a job persisted as TRANSCRIBING, and
 * nothing started the service again, so `recoverOrphans()` never ran and the
 * job stayed invisible in History for ten minutes until unrelated work
 * happened to start the service. Meanwhile the Activity's cache sweep deleted
 * the very audio Retry needed.
 *
 * These tests cover the decisions the Activity makes at startup. The full
 * round trip through a real process kill needs a device, because no JVM test
 * can kill a process.
 */
class StartupRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
    }

    private fun job(name: String, state: JobState, staged: String? = null) =
        store.update(
            store.create("content://x/$name", name, clock++).copy(
                state = state, stagedPath = staged
            )
        )

    /** Mirrors `TranscriptionService.recoverIfNeeded`'s decision. */
    private fun serviceNeeded(): Boolean = store.all().any { !it.state.terminal }

    /** Mirrors `MainActivity.stagedAudioStillNeeded`. */
    private fun keepSet(): Set<String> =
        store.all().filter { it.state != JobState.COMPLETED }
            .mapNotNull { it.stagedPath }.toSet()

    // ---- when the service should start at all ---------------------------

    @Test
    fun `no jobs means no service is started`() {
        assertFalse("must not run a service with nothing to do", serviceNeeded())
    }

    @Test
    fun `only terminal jobs means no service is started`() {
        job("a", JobState.COMPLETED)
        job("b", JobState.FAILED)
        job("c", JobState.CANCELLED)
        assertFalse(serviceNeeded())
    }

    @Test
    fun `an orphan stuck mid-flight starts the service`() {
        job("killed", JobState.TRANSCRIBING)
        assertTrue(serviceNeeded())
    }

    @Test
    fun `every non-terminal state triggers recovery`() {
        listOf(JobState.PENDING, JobState.QUEUED, JobState.UPLOADING,
               JobState.TRANSCRIBING, JobState.RETRYING).forEach { state ->
            val fresh = JobStore(tmp.newFolder("jobs-$state"))
            fresh.update(fresh.create("content://x", "j", 1).copy(state = state))
            assertTrue("$state must be recovered",
                       fresh.all().any { !it.state.terminal })
        }
    }

    @Test
    fun `a queued job left by a previous process starts the service`() {
        job("waiting", JobState.QUEUED)
        assertTrue(serviceNeeded())
    }

    // ---- what recovery turns an orphan into ------------------------------

    @Test
    fun `recovery marks the orphan failed and interrupted`() {
        val orphan = job("killed", JobState.TRANSCRIBING, "/cache/imports/x.aac")
        val recovered = store.recoverOrphans(clock)
        assertEquals(1, recovered.size)
        val after = store.get(orphan.id)!!
        assertEquals(JobState.FAILED, after.state)
        assertEquals(FailureReason.INTERRUPTED, after.failureReason)
        // The staged path must survive the transition, or Retry has no source.
        assertEquals("/cache/imports/x.aac", after.stagedPath)
    }

    @Test
    fun `recovery leaves finished jobs alone`() {
        val done = job("done", JobState.COMPLETED)
        store.recoverOrphans(clock)
        assertEquals(JobState.COMPLETED, store.get(done.id)!!.state)
    }

    // ---- the cache sweep must not destroy what Retry needs ---------------

    @Test
    fun `staged audio for an orphan is protected from the startup sweep`() {
        job("killed", JobState.TRANSCRIBING, "/cache/imports/orphan.aac")
        assertTrue("/cache/imports/orphan.aac" in keepSet())
    }

    @Test
    fun `staged audio for failed and cancelled jobs is protected`() {
        job("f", JobState.FAILED, "/cache/imports/failed.aac")
        job("c", JobState.CANCELLED, "/cache/imports/cancelled.aac")
        val keep = keepSet()
        assertTrue("/cache/imports/failed.aac" in keep)
        assertTrue("/cache/imports/cancelled.aac" in keep)
    }

    @Test
    fun `staged audio for a completed job is NOT protected`() {
        // The transcript is the artefact; keeping the audio forever would grow
        // without bound and retain user speech nobody asked us to keep.
        job("done", JobState.COMPLETED, "/cache/imports/done.aac")
        assertFalse("/cache/imports/done.aac" in keepSet())
    }

    @Test
    fun `the sweep actually deletes only unprotected files`() {
        val dir = tmp.newFolder("imports")
        val keepFile = File(dir, "keep.aac").apply { writeBytes(ByteArray(8)) }
        val dropFile = File(dir, "drop.aac").apply { writeBytes(ByteArray(8)) }
        job("killed", JobState.TRANSCRIBING, keepFile.absolutePath)
        job("done", JobState.COMPLETED, dropFile.absolutePath)

        val keep = keepSet()
        dir.listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }

        assertTrue("audio an unfinished job needs must survive", keepFile.isFile)
        assertFalse("audio nothing needs must go", dropFile.isFile)
    }

    // ---- Fix 4: privacy copy follows the engine --------------------------

    @Test
    fun `local and cloud make different claims about the audio`() {
        val local = PrivacyCopy.audioHandling(Engine.LOCAL)
        val cloud = PrivacyCopy.audioHandling(Engine.CLOUD)
        assertEquals("Audio stays on your phone.", local)
        assertEquals("Audio is sent to the transcription service for transcription.", cloud)
        assertNotEquals(local, cloud)
    }

    @Test
    fun `the cloud claim never says the audio stays on the device`() {
        listOf(PrivacyCopy.audioHandling(Engine.CLOUD),
               PrivacyCopy.microphoneRationale(Engine.CLOUD),
               PrivacyCopy.transcribingHeading(Engine.CLOUD)).forEach { s ->
            listOf("stays on your phone", "never leaves", "on this device",
                   "on-device", "locally").forEach { claim ->
                assertFalse("cloud copy must not claim '$claim': $s",
                            s.contains(claim, ignoreCase = true))
            }
        }
    }

    @Test
    fun `the microphone rationale states where the recording goes`() {
        assertTrue(PrivacyCopy.microphoneRationale(Engine.LOCAL)
                       .contains("stays on your phone"))
        assertTrue(PrivacyCopy.microphoneRationale(Engine.CLOUD)
                       .contains("sent to the transcription service"))
        // Both must still explain why the permission is needed.
        listOf(Engine.LOCAL, Engine.CLOUD).forEach {
            assertTrue(PrivacyCopy.microphoneRationale(it).contains("microphone"))
        }
    }

    @Test
    fun `the transcribing heading does not claim local work in cloud mode`() {
        assertEquals("Transcribing locally", PrivacyCopy.transcribingHeading(Engine.LOCAL))
        assertEquals("Transcribing in the cloud", PrivacyCopy.transcribingHeading(Engine.CLOUD))
    }
}
