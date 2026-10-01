package com.whispercppdemo

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.transcribe.TranscriptionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * orphan exists -> app launch -> recovery -> service exits cleanly.
 *
 * The device crash: a transcription process died, its job stayed persisted as
 * TRANSCRIBING, and on the next launch recoverIfNeeded() started the service
 * with startForegroundService(). With nothing queued, ACTION_RECOVER called
 * stopSelf() without ever calling startForeground(), and Android 16 killed the
 * app with ForegroundServiceDidNotStartInTimeException.
 *
 * This runs in the app's own process against the real service and the real
 * job store, through the same recoverIfNeeded() entry MainActivity uses. If the
 * contract is violated the process dies and the run reports a crash.
 *
 * It writes exactly one job record of its own and deletes it afterwards; no
 * other app data is touched.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryLifecycleTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val jobsDir = File(ctx.filesDir, "jobs")
    private val testJobId = "recovery-lifecycle-test-orphan"

    @Before
    fun noServiceRunning() {
        waitUntil(10_000) { !serviceRunning() }
        assertFalse("precondition: service must not already be running", serviceRunning())
    }

    @After
    fun removeOwnRecord() {
        File(jobsDir, "$testJobId.job").delete()
    }

    private fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        val running = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == TranscriptionService::class.java.name }
    }

    private fun runningNotificationShown(): Boolean =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.any { it.id == 1001 }

    /** Observation only: polls a condition up to a bound; the app waits on nothing. */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    @Test
    fun orphanIsRecoveredAndServiceExitsCleanly() {
        val store = JobStore(jobsDir)
        store.update(
            store.create("file:///recovery-test.aac", "recovery-test.aac",
                         System.currentTimeMillis(), testJobId)
                .copy(state = JobState.TRANSCRIBING)
        )

        // Exactly what MainActivity.onCreate does on a cold launch.
        TranscriptionService.recoverIfNeeded(ctx)

        assertTrue("recovery must happen immediately",
            waitUntil(10_000) { JobStore(jobsDir).get(testJobId)?.state == JobState.FAILED })
        assertEquals(FailureReason.INTERRUPTED, JobStore(jobsDir).get(testJobId)!!.failureReason)

        assertTrue("with nothing to process the service must exit",
            waitUntil(15_000) { !serviceRunning() })

        // The platform raises the contract violation after the service is
        // destroyed, so keep observing past that point. Surviving this window
        // is the assertion: a violation kills this very process.
        SystemClock.sleep(12_000)
        assertFalse("service must stay stopped", serviceRunning())
        assertFalse("no foreground notification may remain", runningNotificationShown())
    }

    @Test
    fun nothingToRecoverStartsNoService() {
        // No test orphan written. If the real store holds no unfinished job,
        // no service may start at all; if it does, recovery must still end.
        val unfinished = JobStore(jobsDir).all().any { !it.state.terminal }
        TranscriptionService.recoverIfNeeded(ctx)
        if (!unfinished) {
            SystemClock.sleep(2_000)
            assertFalse("must not start a service with nothing to do", serviceRunning())
        } else {
            assertTrue(waitUntil(15_000) { !serviceRunning() })
        }
        assertFalse(runningNotificationShown())
    }
}
