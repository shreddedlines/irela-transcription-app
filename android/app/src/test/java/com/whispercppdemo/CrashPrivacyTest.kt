package com.whispercppdemo

import com.whispercppdemo.diag.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The hard privacy rule, enforced by test.
 *
 * Audio, transcript text, keyterms, filenames and credentials must never reach
 * telemetry. These tests deliberately stuff each of those into the places a
 * careless implementation would leak them from — exception messages, causes,
 * and metadata — and assert the rendered report is clean.
 */
class CrashPrivacyTest {

    private val transcript =
        "जो companies हैं वह अपनी jobs डालती हैं because we don't want any fake company"
    private val keyterm = "Priyanka Deshmukh"
    private val filename = "WhatsApp Ptt 2026-09-12 at 7.21.23 PM.ogg"
    private val credential = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"

    private fun render(error: Throwable, extra: Map<String, String> = emptyMap()) =
        CrashReporter.build("crash", error, extra).render()

    @Test
    fun `transcript text in an exception message never reaches the report`() {
        val out = render(IllegalStateException(transcript))
        assertFalse(out.contains("companies"))
        assertFalse(out.contains("जो"))
        assertFalse(out.contains(transcript))
        // The exception TYPE is still reported -- that is what localises it.
        assertTrue(out.contains("IllegalStateException"))
    }

    @Test
    fun `transcript in a nested cause never reaches the report`() {
        val out = render(RuntimeException("outer", IOException(transcript)))
        assertFalse(out.contains("companies"))
        assertTrue(out.contains("IOException"))
    }

    @Test
    fun `filenames never reach the report`() {
        val out = render(IOException("could not read $filename"))
        assertFalse(out.contains("WhatsApp"))
        assertFalse(out.contains(".ogg"))
        assertFalse(out.contains(filename))
    }

    @Test
    fun `keyterms never reach the report`() {
        val out = render(RuntimeException("keyterm failed: $keyterm"))
        assertFalse(out.contains("Priyanka"))
        assertFalse(out.contains("Deshmukh"))
    }

    @Test
    fun `a credential in a message never reaches the report`() {
        val out = render(RuntimeException("auth failed for $credential"))
        assertFalse(out.contains(credential))
        assertFalse(out.contains("a1b2c3d4"))
    }

    @Test
    fun `metadata outside the allowlist is dropped`() {
        val out = render(
            RuntimeException("x"),
            mapOf(
                "transcript" to transcript,
                "audioPath" to "/data/user/0/app/cache/imports/clip.ogg",
                "apiKey" to credential,
                "keyterms" to keyterm,
                // these two ARE on the allowlist and must survive
                "engine" to "CLOUD",
                "failureReason" to "retries_exhausted"
            )
        )
        assertFalse(out.contains(transcript))
        assertFalse(out.contains("clip.ogg"))
        assertFalse(out.contains(credential))
        assertFalse(out.contains("Priyanka"))
        assertTrue("allowlisted fields must survive", out.contains("engine=CLOUD"))
        assertTrue(out.contains("failureReason=retries_exhausted"))
    }

    @Test
    fun `no exception message is ever emitted, whatever it contains`() {
        // Even a harmless-looking message is dropped: the field is free text
        // and any library can put anything in it.
        val out = render(RuntimeException("totally harmless"))
        assertFalse(out.contains("totally harmless"))
    }

    @Test
    fun `stack frames are kept because they carry no user content`() {
        val out = render(RuntimeException("x"))
        assertTrue(out.contains("at "))
        assertTrue(out.contains("CrashPrivacyTest"))
    }

    @Test
    fun `report is bounded in size`() {
        val deep = generateSequence(RuntimeException("root") as Throwable) {
            RuntimeException("wrap", it)
        }.take(30).last()
        val r = CrashReporter.build("crash", deep, emptyMap())
        assertTrue("cause chain capped", r.throwableChain.size <= 5)
        assertTrue("frames capped", r.frames.size <= 40)
    }

    @Test
    fun `anr reports follow the same rules`() {
        val r = CrashReporter.build("anr", Throwable(transcript), mapOf("thread" to "main"))
        val out = r.render()
        assertEquals("anr", r.kind)
        assertFalse(out.contains("companies"))
        assertTrue(out.contains("thread=main"))
    }
}
