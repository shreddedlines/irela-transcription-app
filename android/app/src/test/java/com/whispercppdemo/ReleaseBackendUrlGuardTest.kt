package com.whispercppdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The release build must carry a production backend URL.
 *
 * Without one, EngineSelector falls back to the on-device engine whose model is
 * not shipped: a release that looks fine and never transcribes. The guard lives
 * in build.gradle (`verifyReleaseBackendUrl`); this test reads its rules FROM
 * that file, so the two cannot drift apart, and checks that only `release` is
 * gated.
 */
class ReleaseBackendUrlGuardTest {

    private val gradle = File("build.gradle").readText()

    /** The URL pattern the build actually uses, lifted from the build file. */
    private val pattern: Regex by lazy {
        val line = gradle.lineSequence().first { it.startsWith("ext.RELEASE_URL_PATTERN") }
        Regex(line.substringAfter("~/").substringBeforeLast("/"))
    }

    private val rejectedHosts: List<String> by lazy {
        Regex("""RELEASE_URL_REJECTED_HOSTS = \[([^\]]*)]""", RegexOption.DOT_MATCHES_ALL)
            .find(gradle)!!.groupValues[1]
            .split(",").map { it.trim().trim('\'') }.filter { it.isNotEmpty() }
    }

    /** The guard's decision, as the build applies it. */
    private fun refused(raw: String?): Boolean {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return true
        if (url.lowercase().startsWith("http://")) return true
        if (!pattern.matches(url)) return true
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        return host in rejectedHosts || host.endsWith(".local")
    }

    @Test
    fun theProductionUrlIsAccepted() {
        listOf("https://203-0-113-10.sslip.io",
               "https://api.transcribe.example.in",
               "https://api.example.in:8443",
               "https://host.example.in/base/path").forEach {
            assertFalse("must accept $it", refused(it))
        }
    }

    @Test
    fun aMissingUrlIsRefused() {
        listOf(null, "", "   ").forEach { assertTrue(refused(it)) }
    }

    @Test
    fun cleartextIsRefused() {
        listOf("http://api.example.in", "HTTP://api.example.in", "http://203-0-113-10.sslip.io")
            .forEach { assertTrue("must refuse $it", refused(it)) }
    }

    @Test
    fun malformedUrlsAreRefused() {
        listOf("not a url", "api.example.in", "https://", "https:// api.example.in",
               "ftp://api.example.in", "https://api.example.in?x=1", "https://api.example.in#f",
               "https://api example.in", "https://-bad-.example.in").forEach {
            assertTrue("must refuse '$it'", refused(it))
        }
    }

    @Test
    fun developmentHostsAreRefused() {
        assertTrue(rejectedHosts.containsAll(listOf("localhost", "127.0.0.1", "10.0.2.2", "example.com")))
        listOf("https://localhost", "https://127.0.0.1:8080", "https://10.0.2.2:8080",
               "https://example.com", "https://api.example.com", "https://mac.local")
            .forEach { assertTrue("must refuse $it", refused(it)) }
    }

    // ---- wiring ------------------------------------------------------------

    @Test
    fun onlyTheReleaseVariantIsGated() {
        assertTrue("the task exists", gradle.contains("tasks.register('verifyReleaseBackendUrl')"))
        val wiring = gradle.substringAfter("afterEvaluate {")
        assertTrue("assembleRelease and bundleRelease both require it",
                   wiring.contains("it.name in ['assembleRelease', 'bundleRelease']") &&
                       wiring.contains("dependsOn 'verifyReleaseBackendUrl'"))
        // Debug builds keep building with no URL.
        listOf("assembleDebug", "bundleDebug").forEach {
            assertFalse("$it must not be gated", wiring.contains("'$it'"))
        }
    }

    @Test
    fun theUrlIsTreatedAsConfigurationNotAsASecret() {
        val task = gradle.substringAfter("tasks.register('verifyReleaseBackendUrl')")
            .substringBefore("// The debug key is publicly known")
        // The failure text names the offending URL (it is not sensitive) and
        // never touches signing material or provider keys.
        assertTrue(task.contains("\${url}"))
        listOf("Password", "KEYSTORE", "keyAlias", "API_KEY").forEach {
            assertFalse("the guard must not touch $it", task.contains(it))
        }
    }

    @Test
    fun theBuildTimeMechanismIsUnchanged() {
        // Still one property, still resValue-injected, still empty by default.
        assertTrue(gradle.contains("resValue \"string\", \"backend_base_url\","))
        assertTrue(gradle.contains("(project.findProperty('backendBaseUrl') ?: '')"))
        assertEquals(2, Regex("""findProperty\('backendBaseUrl'\)""").findAll(gradle).count())
    }
}
