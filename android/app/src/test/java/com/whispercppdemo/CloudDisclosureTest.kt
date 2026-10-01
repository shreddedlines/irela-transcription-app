package com.whispercppdemo

import com.whispercppdemo.privacy.CloudDisclosure
import com.whispercppdemo.transcribe.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disclosure shown before the first upload.
 *
 * Two things are pinned here. First, that every fact the user must be told is
 * actually in the list the screen renders -- the screen reads
 * [CloudDisclosure.POINTS] rather than hardcoding text, so a fact cannot be
 * dropped from the UI without failing here. Second, that the wording stays
 * factual: this app is in no position to assert compliance with any law, and
 * a screen that claims it would be worse than no screen at all.
 */
class CloudDisclosureTest {

    private val text = CloudDisclosure.POINTS.joinToString(" ").lowercase()

    // ---- the five required facts ----------------------------------------

    @Test
    fun `says transcription happens online, not on the phone`() {
        assertTrue(text.contains("online"))
        assertTrue("must deny on-device processing", text.contains("not run on your phone"))
    }

    @Test
    fun `says the audio is uploaded`() {
        assertTrue(text.contains("uploaded"))
        assertTrue(text.contains("audio"))
    }

    @Test
    fun `says a third party does the transcription`() {
        assertTrue(text.contains("third-party"))
    }

    @Test
    fun `says processing may happen in another country, worded for any market`() {
        assertEquals("Processing may take place in another country.", CloudDisclosure.POINTS[3])
        val all = (CloudDisclosure.POINTS + CloudDisclosure.RETENTION_NOTE).joinToString(" ").lowercase()
        assertFalse("the disclosure must not name one country", all.contains("india"))
    }

    @Test
    fun `the other facts are unchanged, word for word`() {
        assertEquals(listOf(
            "Transcription uses an online service. It does not run on your phone.",
            "The audio you record or share is uploaded so it can be transcribed.",
            "The transcription is performed by a third-party provider, not by us.",
            "Processing may take place in another country.",
            "Without an internet connection, transcription cannot run at all."
        ), CloudDisclosure.POINTS)
        assertEquals("Your transcripts are stored only on this phone. If a transcription fails, your " +
                     "recording stays on this phone so you can try again. It is deleted once it " +
                     "has been transcribed or when you remove it. A copy of an imported file is " +
                     "kept for up to 7 days.", CloudDisclosure.RETENTION_NOTE)
    }

    @Test
    fun `says transcription is impossible without internet`() {
        assertTrue(text.contains("without an internet connection"))
    }

    @Test
    fun `retention is disclosed alongside what is sent`() {
        val r = CloudDisclosure.RETENTION_NOTE.lowercase()
        assertTrue("where transcripts live", r.contains("only on this phone"))
        assertTrue("how long audio is kept", r.contains("7 days"))
    }

    // ---- what it must NOT say -------------------------------------------

    @Test
    fun `makes no legal compliance claim`() {
        val all = (CloudDisclosure.POINTS + CloudDisclosure.RETENTION_NOTE)
            .joinToString(" ").lowercase()
        listOf("dpdp", "gdpr", "compliant", "compliance", "lawful",
               "legally", "guarantee", "guaranteed", "certified").forEach {
            assertFalse("must not claim '$it'", all.contains(it))
        }
    }

    @Test
    fun `does not promise encryption or safety it cannot verify`() {
        val all = (CloudDisclosure.POINTS + CloudDisclosure.RETENTION_NOTE)
            .joinToString(" ").lowercase()
        // "secure", "private", "safe" are claims about someone else's servers.
        listOf("100%", "completely secure", "never shared", "anonymous").forEach {
            assertFalse("must not claim '$it'", all.contains(it))
        }
    }

    @Test
    fun `every point is a complete, readable statement`() {
        assertEquals(5, CloudDisclosure.POINTS.size)
        CloudDisclosure.POINTS.forEach {
            assertTrue("too terse to be a disclosure: '$it'", it.length > 40)
            assertTrue("not a sentence: '$it'", it.trim().endsWith("."))
        }
    }

    // ---- versioning ------------------------------------------------------

    @Test
    fun `the retention wording is version 3`() {
        assertEquals(3, CloudDisclosure.CURRENT_VERSION)
    }

    @Test
    fun `an acknowledgement of older text does not carry forward`() {
        // The stored value is a version, not a boolean, precisely so that
        // changing the disclosed facts re-asks rather than silently reusing
        // consent given to different facts.
        assertFalse("never accepted", CloudDisclosure.coversCurrentText(0))
        assertFalse("accepted the 'outside India' text: must be asked again", CloudDisclosure.coversCurrentText(1))
        assertFalse("accepted the '7 days' retention text: must be asked again", CloudDisclosure.coversCurrentText(2))
        assertTrue("accepted this text", CloudDisclosure.coversCurrentText(3))
        assertTrue("a newer build's consent is not downgraded", CloudDisclosure.coversCurrentText(4))
    }

    // ---- shown once per version ------------------------------------------

    @Test
    fun `cloud shows the disclosure once, then never for later recordings or imports`() {
        var stored = 0                                       // nothing accepted yet
        fun nextJob(): Boolean {
            val show = CloudDisclosure.required(Engine.CLOUD, stored)
            if (show) stored = CloudDisclosure.CURRENT_VERSION   // "I understand -- transcribe"
            return show
        }
        assertTrue("first cloud transcription shows it", nextJob())
        repeat(5) { assertFalse("job ${it + 2} must not show it again", nextJob()) }
    }

    @Test
    fun `local transcription never shows the disclosure`() {
        listOf(0, 1, CloudDisclosure.CURRENT_VERSION).forEach {
            assertFalse(CloudDisclosure.required(Engine.LOCAL, it))
        }
    }

    @Test
    fun `a future version bump shows the new text once`() {
        // Accepted the previous version: the new one is shown, then accepted.
        var stored = CloudDisclosure.CURRENT_VERSION - 1
        assertTrue(CloudDisclosure.required(Engine.CLOUD, stored))
        stored = CloudDisclosure.CURRENT_VERSION
        assertFalse(CloudDisclosure.required(Engine.CLOUD, stored))
        assertFalse(CloudDisclosure.required(Engine.CLOUD, stored))
    }

    @Test
    fun `the app's gates use the one rule, and accepting stores the version before the parked job runs`() {
        val vm = java.io.File("src/main/java/com/whispercppdemo/ui/main/MainScreenViewModel.kt").readText()
        assertEquals("startJob and retryJob", 2, Regex("""CloudDisclosure\.isRequired\(application\)""").findAll(vm).count())
        assertFalse(vm.contains("!CloudDisclosure.isAcknowledged(application)"))
        val accept = vm.substring(vm.indexOf("fun acceptCloudDisclosure()"))
        assertTrue(accept.indexOf("CloudDisclosure.acknowledge(application)") in 0 until accept.indexOf("startJob("))
        val src = java.io.File("src/main/java/com/whispercppdemo/privacy/CloudDisclosure.kt").readText()
        assertTrue(src.contains(".putInt(KEY_VERSION, CURRENT_VERSION)"))
    }

    @Test
    fun `changing the disclosed text requires a version bump`() {
        // Fingerprint of the exact text each version showed. Editing POINTS or
        // RETENTION_NOTE fails here until CURRENT_VERSION is bumped and the new
        // fingerprint recorded -- so no one can change the facts and keep old consent.
        val fingerprints = mapOf(
            2 to "67e9ed833512a0a0a5e093a4f6a4d6d7a68f12d527f791ae5514d02e93277f66",
            3 to "8f812cc2df0512c876576386d51e29aae00b605317abc0e7298b09e6715b7f82"
        )
        val shown = (CloudDisclosure.POINTS + CloudDisclosure.RETENTION_NOTE).joinToString("\n")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(shown.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals("disclosure text changed: bump CURRENT_VERSION and record its fingerprint",
                     fingerprints[CloudDisclosure.CURRENT_VERSION], digest)
    }

    @Test
    fun `the wording lives only in the disclosure, and no general UI names India`() {
        val main = java.io.File("src/main")
        val sources = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
        // The sentence is defined once and rendered by exactly one screen.
        assertEquals(listOf("CloudDisclosure.kt"),
                     sources.filter { it.readText().contains("in another country") }.map { it.name })
        assertEquals(listOf("CloudDisclosure.kt", "CloudDisclosureScreen.kt"),
                     sources.filter { it.readText().contains("CloudDisclosure.POINTS") || it.name == "CloudDisclosure.kt" }
                         .map { it.name }.sorted())
        // No user-facing string literal anywhere mentions India (comments aside).
        val literal = Regex("\"[^\"]*\\bIndia\\b[^\"]*\"",RegexOption.IGNORE_CASE)
        sources.forEach { f ->
            f.readLines().filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }
                .forEach { line -> assertFalse("${f.name}: $line", literal.containsMatchIn(line)) }
        }
    }
}
