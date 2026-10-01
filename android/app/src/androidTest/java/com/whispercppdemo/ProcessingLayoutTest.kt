package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import com.whispercppdemo.ui.processing.ProcessingScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Processing screen's progress and Cancel must be reachable at any
 * viewport height.
 *
 * The device bug: in landscape the progress bar and Cancel Transcription were
 * laid out below the visible area, the screen did not scroll, and with Back
 * consumed and the bottom bar inert while a job runs, the notification was
 * the only way to cancel.
 *
 * The landscape height is at least as tight as the real content area on the
 * test device once both the status-bar and navigation-bar insets are reserved.
 *
 * The progress bar sweep and the status badge are infinite animations, which
 * would never let the test clock idle, so the clock is driven by hand.
 */
@RunWith(AndroidJUnit4::class)
class ProcessingLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var cancels = 0
    private lateinit var jobsDir: File

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // A private scratch store: never the app's real files/jobs.
        jobsDir = File(ctx.cacheDir, "processing-layout-test-jobs").apply {
            deleteRecursively(); mkdirs()
        }
    }

    @After
    fun tearDown() {
        jobsDir.deleteRecursively()
    }

    private val transcribing = TranscriptionState.Transcribing("clip.ogg", chunk = 2, chunks = 3)

    private fun processingAt(
        width: Dp, height: Dp,
        state: TranscriptionState = transcribing,
        isCancelling: Boolean = false,
        onCancel: () -> Unit = { cancels++ }
    ) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(width, height)) {
                    ProcessingScreen(
                        state = state,
                        isCancelling = isCancelling,
                        continuesInBackground = true,
                        cloud = false,
                        onCancel = onCancel
                    )
                }
            }
        }
        frames()
    }

    private fun frames(n: Int = 3) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    /** performScrollTo animates; with the clock frozen it needs real time to land. */
    private fun settleScroll() = compose.mainClock.advanceTimeBy(2_000)

    private fun node(text: String): SemanticsNodeInteraction = compose.onNodeWithText(text)

    private fun reach(text: String): SemanticsNodeInteraction {
        node(text).performScrollTo()
        settleScroll()
        return node(text).assertIsDisplayed()
    }

    // ---- portrait: unchanged ----------------------------------------------

    @Test
    fun portrait_progressAndCancelVisibleWithoutScrolling() {
        processingAt(400.dp, 760.dp)
        node("Part 2 of 3").assertIsDisplayed()
        node("Cancel transcription").assertIsDisplayed().assertIsEnabled()
    }

    // ---- landscape ----------------------------------------------------------

    @Test
    fun landscape_progressStateIsReachable() {
        processingAt(900.dp, 220.dp)
        // The part counter is the active step's caption, directly above its progress bar.
        reach("Part 2 of 3")
    }

    @Test
    fun landscape_cancelIsReachableAndInvokesCallback() {
        processingAt(900.dp, 220.dp)
        reach("Cancel transcription").assertHasClickAction().performClick()
        frames()
        assertEquals(1, cancels)
    }

    @Test
    fun landscape_cancellingStateStaysReachable() {
        // After the tap, Cancel becomes "Cancelling…" plus an explanatory line
        // -- the screen gets TALLER exactly when the user is looking at it.
        processingAt(900.dp, 220.dp, isCancelling = true)
        reach("Cancelling…").assertIsNotEnabled()
        reach("Finishing the current chunk before stopping.")
    }

    @Test
    fun landscape_everyRunningStageKeepsCancelReachable() {
        // One composition, stage swapped in place: setContent may only be
        // called once per test, and this is also how the real screen changes.
        var state by mutableStateOf<TranscriptionState>(TranscriptionState.Queued("clip.ogg", 1))
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(900.dp, 220.dp)) {
                    ProcessingScreen(
                        state = state,
                        isCancelling = false,
                        continuesInBackground = true,
                        cloud = false,
                        onCancel = { cancels++ }
                    )
                }
            }
        }
        frames()
        listOf(
            TranscriptionState.Queued("clip.ogg", 1),
            TranscriptionState.Staging("clip.ogg"),
            TranscriptionState.Uploading("clip.ogg", null),
            TranscriptionState.Decoding("clip.ogg", 0.4f),
            TranscriptionState.Retrying("clip.ogg", 1, 3)
        ).forEach { next ->
            state = next
            frames()
            reach("Cancel transcription")
        }
    }

    @Test
    fun landscape_longRecordingNoticeDoesNotPushCancelAway() {
        // The long-audio notice adds the most height of any state.
        processingAt(900.dp, 220.dp,
            state = TranscriptionState.Transcribing("long.ogg", chunk = 5, chunks = 30))
        reach("Cancel transcription")
    }

    @Test
    fun shortViewport_progressAndCancelStillReachable() {
        processingAt(600.dp, 150.dp)
        reach("Part 2 of 3")
        reach("Cancel transcription")
    }

    // ---- the tap really cancels the persisted job ------------------------

    @Test
    fun landscape_cancelTapCancelsThePersistedJob() {
        val store = JobStore(jobsDir)
        val runner = JobRunner(
            store = store,
            provider = object : TranscriptionProvider {
                override val id = "never"
                override val displayName = "Never"
                override suspend fun transcribe(
                    request: TranscriptionRequest, onProgress: (Float) -> Unit
                ) = TranscriptionOutcome.Success("unused", "never")
            },
            prepare = { PreparedAudio(File(jobsDir, "x.ogg"), "audio/ogg") },
            persistTranscript = { _, _, _ -> "unused" }
        )
        // A job exactly where a killed-mid-run screen would find it.
        val job = store.update(
            store.create("file:///clip.ogg", "clip.ogg", System.currentTimeMillis())
                .copy(state = JobState.TRANSCRIBING)
        )

        var isCancelling by mutableStateOf(false)
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(900.dp, 220.dp)) {
                    ProcessingScreen(
                        state = transcribing,
                        isCancelling = isCancelling,
                        continuesInBackground = true,
                        cloud = false,
                        // What the service does on ACTION_CANCEL.
                        onCancel = { isCancelling = true; runner.cancel(job.id) }
                    )
                }
            }
        }
        frames()

        reach("Cancel transcription").performClick()
        frames()

        // Re-read from disk with a fresh store: persisted, not just in memory.
        assertEquals(JobState.CANCELLED, JobStore(jobsDir).get(job.id)!!.state)
        reach("Cancelling…").assertIsNotEnabled()
    }

    // ---- Concept A stepper -------------------------------------------------------

    @Test
    fun localStepper_showsPrepareDoneAndTranscribeActive() {
        processingAt(412.dp, 760.dp)
        node("Transcribing locally").assertIsDisplayed()
        node("Prepare audio").assertIsDisplayed()
        node("Done").assertIsDisplayed()
        node("Transcribe on this phone").assertIsDisplayed()
        assertEquals("no send step when nothing is uploaded", 0,
                     compose.onAllNodes(androidx.compose.ui.test.hasText("Send audio")).fetchSemanticsNodes().size)
    }

    @Test
    fun cloudStepper_showsTheSendStepWhileUploading() {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 760.dp)) {
                    ProcessingScreen(
                        state = TranscriptionState.Uploading("Conversation", null),
                        isCancelling = false,
                        continuesInBackground = true,
                        cloud = true,
                        onCancel = { cancels++ }
                    )
                }
            }
        }
        frames()
        node("Transcribing in the cloud").assertIsDisplayed()
        node("Send audio").assertIsDisplayed()
        node("Sending audio for transcription…").assertIsDisplayed()
        node("Transcribe").assertIsDisplayed()
    }

    @Test
    fun aStagedCopyNameIsNotShownAsTheTitle() {
        processingAt(412.dp, 760.dp,
            state = TranscriptionState.Transcribing("bdeafe6e-e0a2-4a56-84e3-9cf1a3d2c4b5.wav", chunk = 1, chunks = 2))
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("bdeafe6e", substring = true))
            .fetchSemanticsNodes().size)
    }
}
