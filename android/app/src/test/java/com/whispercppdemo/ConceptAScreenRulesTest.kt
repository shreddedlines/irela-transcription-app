package com.whispercppdemo

import com.whispercppdemo.capture.BlockReason
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.transcribe.Reachability
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.common.FailureTone
import com.whispercppdemo.ui.common.formatElapsed
import com.whispercppdemo.ui.processing.ProcessingStep
import com.whispercppdemo.ui.processing.realProgress
import com.whispercppdemo.ui.processing.stepFor
import com.whispercppdemo.ui.recording.DISCARD_CONFIRM_MS
import com.whispercppdemo.ui.recording.NEAR_LIMIT_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Screen rules that do not need a device: failure headings, the processing
 * stepper, the 60-minute UI boundary, and the Home -> sheet -> Recording flow's
 * structural guarantees.
 */
class ConceptAScreenRulesTest {

    // ---- failure headings ----------------------------------------------------

    @Test
    fun `stored failure codes map to accurate headings`() {
        assertEquals(FailureCategory.OFFLINE, FailureCategory.forReason(FailureReason.OFFLINE))
        assertEquals(FailureCategory.UNAVAILABLE, FailureCategory.forReason(FailureReason.RETRIES_EXHAUSTED))
        assertEquals(FailureCategory.UNAVAILABLE, FailureCategory.forReason(FailureReason.PROVIDER_UNAVAILABLE))
        assertEquals(FailureCategory.INTERRUPTED, FailureCategory.forReason(FailureReason.INTERRUPTED))
        assertEquals(FailureCategory.NO_SPEECH, FailureCategory.forReason(FailureReason.EMPTY_TRANSCRIPT))
        assertEquals(FailureCategory.UNUSABLE_AUDIO, FailureCategory.forReason(FailureReason.AUDIO_REJECTED))
        assertEquals(FailureCategory.NOT_SAVED, FailureCategory.forReason(FailureReason.PERSISTENCE_FAILED))
        assertEquals(FailureCategory.GENERIC, FailureCategory.forReason(null))
    }

    @Test
    fun `live failure messages map to the same headings`() {
        listOf(FailureReason.OFFLINE, FailureReason.RETRIES_EXHAUSTED, FailureReason.INTERRUPTED,
               FailureReason.EMPTY_TRANSCRIPT, FailureReason.AUDIO_REJECTED, FailureReason.PERSISTENCE_FAILED,
               FailureReason.PROVIDER_UNAVAILABLE).forEach { code ->
            assertEquals(code, FailureCategory.forReason(code), FailureCategory.forMessage(failureMessageFor(code)))
        }
        assertEquals(FailureCategory.OFFLINE, FailureCategory.forMessage(Reachability.INTERNET_UNAVAILABLE.userMessage))
        assertEquals(FailureCategory.UNAVAILABLE, FailureCategory.forMessage(Reachability.BACKEND_UNAVAILABLE.userMessage))
        assertEquals(FailureCategory.MICROPHONE, FailureCategory.forMessage(BlockReason.MICROPHONE_UNAVAILABLE.message))
        assertEquals(FailureCategory.UNUSABLE_AUDIO,
                     FailureCategory.forMessage(AudioLimits.message(AudioLimits.Verdict.TooLong(3700))))
        assertEquals(FailureCategory.UNUSABLE_AUDIO,
                     FailureCategory.forMessage(AudioLimits.message(AudioLimits.Verdict.TooLarge(200_000_000))))
        assertEquals(FailureCategory.GENERIC, FailureCategory.forMessage("Something new"))
    }

    @Test
    fun `the view model literals the failed screen titles are still the ones it publishes`() {
        // If either message is reworded in the ViewModel, this fails rather than
        // the Failed screen silently falling back to a generic heading.
        val vm = File("src/main/java/com/whispercppdemo/ui/main/MainScreenViewModel.kt").readText()
        listOf("The recording could not be saved. Please try again.",
               "Finish or cancel the recording first, then share again.").forEach {
            assertEquals("ViewModel no longer publishes: $it", true, vm.contains(it))
            assertEquals(true, FailureCategory.forMessage(it) != FailureCategory.GENERIC)
        }
    }

    @Test
    fun `cancellation-like outcomes are not painted as errors`() {
        assertEquals(FailureTone.NEUTRAL, FailureCategory.INTERRUPTED.tone)
        assertEquals(FailureTone.WARNING, FailureCategory.OFFLINE.tone)
        assertEquals(FailureTone.ERROR, FailureCategory.UNUSABLE_AUDIO.tone)
    }

    // ---- processing stepper ----------------------------------------------------

    @Test
    fun `cloud steps follow the real service states`() {
        assertEquals(ProcessingStep.PREPARE, stepFor(TranscriptionState.Queued("a", 0), cloud = true))
        assertEquals(ProcessingStep.PREPARE, stepFor(TranscriptionState.Staging("a"), cloud = true))
        assertEquals(ProcessingStep.SEND, stepFor(TranscriptionState.Uploading("a", null), cloud = true))
        assertEquals(ProcessingStep.SEND, stepFor(TranscriptionState.Retrying("a", 1, 3), cloud = true))
        assertEquals(ProcessingStep.TRANSCRIBE, stepFor(TranscriptionState.Transcribing("a", 0, 0), cloud = true))
        assertNull(stepFor(TranscriptionState.Idle, cloud = true))
    }

    @Test
    fun `local has no send step`() {
        assertEquals(ProcessingStep.PREPARE, stepFor(TranscriptionState.Decoding("a", 0.3f), cloud = false))
        assertEquals(ProcessingStep.PREPARE, stepFor(TranscriptionState.Uploading("a", null), cloud = false))
        assertEquals(ProcessingStep.TRANSCRIBE, stepFor(TranscriptionState.Transcribing("a", 2, 5), cloud = false))
    }

    @Test
    fun `progress is only ever real`() {
        assertEquals(0.4f, realProgress(TranscriptionState.Decoding("a", 0.4f)))
        assertNull("an upload without a reported fraction is indeterminate",
                   realProgress(TranscriptionState.Uploading("a", null)))
        assertEquals(0.5f, realProgress(TranscriptionState.Uploading("a", 0.5f)))
        // Chunk 3 of 4 is IN FLIGHT: two are done.
        assertEquals(0.5f, realProgress(TranscriptionState.Transcribing("a", 3, 4)))
        assertNull("cloud transcription reports no chunks", realProgress(TranscriptionState.Transcribing("a", 0, 0)))
        assertNull(realProgress(TranscriptionState.Queued("a", 1)))
        assertNull(realProgress(TranscriptionState.Retrying("a", 1, 3)))
    }

    @Test
    fun `the staged copy's uuid name is never shown as the job name`() {
        assertEquals(true, com.whispercppdemo.ui.processing.isStagedCopyName("bdeafe6e-e0a2-4a56-84e3-9cf1a3d2c4b5.wav"))
        assertEquals(true, com.whispercppdemo.ui.processing.isStagedCopyName("bdeafe6e-e0a2-4a56-84e3-9cf1a3d2c4b5"))
        assertEquals(false, com.whispercppdemo.ui.processing.isStagedCopyName("Conversation"))
        assertEquals(false, com.whispercppdemo.ui.processing.isStagedCopyName("PTT-20260912-WA0014.opus"))
    }

    // ---- 60-minute UI boundary ---------------------------------------------------

    @Test
    fun `timer reads the limit exactly and the warning starts five minutes before`() {
        assertEquals("59:59", formatElapsed(59 * 60_000L + 59_999L))
        assertEquals("60:00", formatElapsed(AudioLimits.MAX_DURATION_MS))
        assertEquals(55 * 60_000L, NEAR_LIMIT_MS)
        assertEquals(AudioLimits.MAX_DURATION_MS - 5 * 60_000L, NEAR_LIMIT_MS)
    }

    @Test
    fun `short recordings are discarded without a dialog`() {
        assertEquals(5_000L, DISCARD_CONFIRM_MS)
    }

    // ---- Home -> Record -> mode sheet -> Recording, structurally ----------------

    private fun code(path: String) = File(path).readText().lines().filterNot {
        val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }.joinToString("\n")

    @Test
    fun `record opens the mode sheet, and each mode uses the existing view model entry point`() {
        val nav = code("src/main/java/com/whispercppdemo/ui/nav/AppNavHost.kt")
        assertEquals(true, nav.contains("onRecord = { viewModel.clearCaptureBlock(); modeSheetOpen = true }"))
        assertEquals(true, nav.contains("RecordMode.VOICE -> viewModel.startRecording()"))
        assertEquals(true, nav.contains("RecordMode.CONVERSATION -> viewModel.startConversation()"))
        assertEquals(true, nav.contains("viewModel.preparePlaybackCapture()"))
        assertEquals(true, nav.contains("mpm.createScreenCaptureIntent()"))
        assertEquals("no route to the retired conversation screen", false, nav.contains("Dest.CONVERSATION"))
        // Call audio is explained only.
        assertEquals(false, nav.contains("requestCallAudioCapture"))
    }

    @Test
    fun `every mode asks for the microphone first`() {
        val nav = code("src/main/java/com/whispercppdemo/ui/nav/AppNavHost.kt")
        val choose = nav.substring(nav.indexOf("fun chooseMode("), nav.indexOf("startMode(mode)\n    }", nav.indexOf("fun chooseMode(")))
        assertEquals(true, choose.contains("if (!micPermission.status.isGranted)"))
        assertEquals(true, choose.contains("navController.navigate(Dest.PERMISSION)"))
    }

    @Test
    fun `the job routing effect is intact`() {
        val nav = code("src/main/java/com/whispercppdemo/ui/nav/AppNavHost.kt")
        listOf("terminalRoute(", "TerminalRoute.OpenTranscript", "TerminalRoute.ShowFailed", "TerminalRoute.ShowCancelled",
               "TerminalRoute.LeaveProcessing ->", "navController.popBackStack(Dest.HOME, false)",
               "BackHandler(enabled = viewModel.serviceState.isRunning)", "viewModel.consumeTerminalState()")
            .forEach { assertEquals("missing $it", true, nav.contains(it)) }
    }

    @Test
    fun `the playback sheet row is disabled below Android 10`() {
        val sheet = code("src/main/java/com/whispercppdemo/ui/recording/RecordModeSheet.kt")
        assertEquals(true, sheet.contains("enabled = playbackAvailable"))
        assertEquals(true, sheet.contains("BlockReason.PROTECTED_CALL_AUDIO.message"))
    }
}
