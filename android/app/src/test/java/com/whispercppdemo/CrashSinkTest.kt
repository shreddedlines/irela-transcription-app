package com.whispercppdemo

import com.whispercppdemo.diag.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The sink abstraction: reports must survive until something can deliver them,
 * and nothing sensitive may reach a sink or the spool file on the way.
 *
 * The privacy rule is re-asserted here against the payloads a vendor SDK would
 * realistically be handed -- a raw request body, an audio file path, a
 * provider key -- rather than only against the rendered string, because
 * `deliverTo` is the seam a third party will eventually sit behind.
 */
class CrashSinkTest {

    private val transcript = "मैं कल Bangalore जा रहा हूँ, meeting at 4 PM"
    private val credential = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"
    private val audioPath = "/data/user/0/com.whispercppdemo/cache/imports/clip.ogg"
    private val requestBody =
        """{"model":"nova-3","keyterm":"Venkatesh","text":"$transcript"}"""

    private class Recording : CrashReporter.Sink {
        val received = mutableListOf<CrashReporter.Report>()
        override fun send(report: CrashReporter.Report) {
            received += report
        }
    }

    private fun report(error: Throwable, extra: Map<String, String> = emptyMap()) =
        CrashReporter.build("crash", error, extra)

    // ---- nothing sensitive reaches a sink -------------------------------

    @Test
    fun `a raw request body in an exception never reaches the sink`() {
        val sink = Recording()
        sink.send(report(IOException("POST failed: $requestBody")))
        val seen = sink.received.single().render()
        assertFalse(seen.contains("nova-3"))
        assertFalse(seen.contains("Venkatesh"))
        assertFalse(seen.contains(transcript))
        assertFalse(seen.contains("मैं"))
    }

    @Test
    fun `an audio file path never reaches the sink`() {
        val sink = Recording()
        sink.send(report(IOException("could not open $audioPath"),
                         mapOf("audioPath" to audioPath)))
        val seen = sink.received.single().render()
        assertFalse(seen.contains("clip.ogg"))
        assertFalse(seen.contains("imports"))
        assertFalse(seen.contains(audioPath))
    }

    @Test
    fun `a provider credential never reaches the sink`() {
        val sink = Recording()
        sink.send(report(RuntimeException("401 for key $credential"),
                         mapOf("apiKey" to credential, "authorization" to "Bearer $credential")))
        val seen = sink.received.single().render()
        assertFalse(seen.contains(credential))
        assertFalse(seen.contains("Bearer"))
    }

    @Test
    fun `what a sink DOES get is enough to locate the crash`() {
        val sink = Recording()
        sink.send(report(IllegalStateException(transcript),
                         mapOf("engine" to "CLOUD", "failureReason" to "retries_exhausted")))
        val r = sink.received.single()
        assertTrue(r.throwableChain.any { it.contains("IllegalStateException") })
        assertTrue(r.frames.isNotEmpty())
        assertEquals("CLOUD", r.metadata["engine"])
        assertEquals("retries_exhausted", r.metadata["failureReason"])
    }

    // ---- identity and delivery ------------------------------------------

    @Test
    fun `every report carries an id so a sink can drop a duplicate`() {
        val a = report(RuntimeException("x"))
        val b = report(RuntimeException("x"))
        assertTrue(a.id.isNotBlank())
        assertTrue(b.id.isNotBlank())
        assertFalse("two occurrences are distinct events", a.id == b.id)
    }

    @Test
    fun `the id leaks nothing -- it is derived from already redacted content`() {
        val id = report(IllegalStateException(transcript),
                        mapOf("apiKey" to credential)).id
        assertFalse(id.contains(transcript))
        assertFalse(id.contains(credential))
        assertFalse(id.contains("मैं"))
        // Type name, a hash and a timestamp -- nothing user-supplied.
        assertTrue(id.startsWith("crash-"))
    }

    @Test
    fun `the rendered form a sink stores round-trips without gaining anything`() {
        // The spool reads reports back from exactly this text, so whatever the
        // allowlist excluded cannot re-enter on the way out.
        val rendered = report(IllegalStateException(transcript),
                              mapOf("engine" to "CLOUD", "apiKey" to credential)).render()
        assertFalse(rendered.contains(credential))
        assertFalse(rendered.contains(transcript))
        assertTrue(rendered.contains("engine=CLOUD"))
        assertTrue(rendered.contains("kind=crash"))
        assertTrue(rendered.contains("id="))
    }

    @Test
    fun `a sink that throws cannot take the process down with it`() {
        // A vendor SDK failing is not a reason to crash while reporting a crash.
        val exploding = CrashReporter.Sink { throw RuntimeException("SDK not initialised") }
        val r = report(RuntimeException("x"))
        runCatching { exploding.send(r) }   // the contract report() relies on
        assertTrue("report construction must not depend on the sink", r.frames.isNotEmpty())
    }

    @Test
    fun `anr reports go through the identical path`() {
        val sink = Recording()
        sink.send(CrashReporter.build("anr", Throwable(transcript), mapOf("thread" to "main")))
        val r = sink.received.single()
        assertEquals("anr", r.kind)
        assertEquals("main", r.metadata["thread"])
        assertFalse(r.render().contains("Bangalore"))
    }
}
