package com.whispercppdemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a completed job's duration used to be lost: the service wrote every
 * History record with `durationMs = null`, and the backend's measured
 * `audio_duration_s` was never parsed. Pins both links of the chain.
 */
class HistoryDurationWiringTest {

    private fun src(path: String) = File("src/main/java/com/whispercppdemo/$path").readText()

    @Test
    fun serviceStoresTheReportedDuration_notANullConstant() {
        val service = src("transcribe/TranscriptionService.kt")
        val persist = service.substring(service.indexOf("private suspend fun persistTranscript("))
            .let { it.substring(0, it.indexOf("return saved.id")) }
        assertTrue(persist.contains("durationMs: Long?"))
        assertTrue(persist.contains("durationMs = durationMs"))
        assertFalse("the duration must not be dropped again", persist.contains("durationMs = null"))
        assertTrue(service.contains("persistTranscript = { job, text, durationMs -> persistTranscript(job, text, durationMs) }"))
    }

    @Test
    fun runnerHandsTheProviderDurationToPersistence() {
        assertTrue(src("jobs/JobRunner.kt").contains("persistTranscript(current, outcome.text, outcome.audioDurationMs)"))
    }

    @Test
    fun backendAndLocalProvidersReportMeasuredAudioLength() {
        assertTrue(src("transcribe/provider/BackendProvider.kt")
            .contains("audioDurationMs = audioDurationMsFrom(j.optDouble(\"audio_duration_s\"))"))
        assertTrue(src("transcribe/provider/LocalWhisperProvider.kt")
            .contains("audioDurationMs = pcm.size * 1000L / com.whispercppdemo.media.TARGET_SAMPLE_RATE"))
    }
}
