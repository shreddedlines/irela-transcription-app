package com.whispercppdemo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A provider API key must never exist anywhere in the Android project.
 *
 * A key inside an installed app is extractable no matter how it is stored —
 * obfuscation, NDK, split strings, none of it helps against someone with the
 * APK. The backend holds the credentials; the app knows only a URL. This test
 * is the guard against the mistake creeping back in during a hurried change.
 */
class NoCredentialsInAppTest {

    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java/com/whispercppdemo").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    /**
     * Only `src/main` and the build script. Test sources are excluded because
     * they do not ship in the APK -- and because this file necessarily contains
     * the very strings it is searching for.
     */
    private fun sources(): List<File> =
        listOf(File(moduleRoot(), "src/main"), File(moduleRoot(), "build.gradle"))
            .filter { it.exists() }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "java", "xml", "gradle", "properties") }
            .filter { !it.path.contains("${File.separator}build${File.separator}") }
            .filter { !it.name.endsWith(".pre-trace") && !it.name.endsWith(".pre-fix") }
            .toList()

    /** Deepgram keys are 40 hex chars; AssemblyAI 32 hex. */
    private val hexKey = Regex("""["'][0-9a-fA-F]{32,}["']""")

    @Test
    fun `no api key literal in any source file`() {
        val offenders = sources().mapNotNull { f ->
            val hit = hexKey.find(f.readText())
            if (hit != null) "${f.name}: ${hit.value.take(12)}..." else null
        }
        assertTrue("credential-shaped literals found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no provider credential field names appear in sources`() {
        val banned = listOf(
            "DEEPGRAM_API_KEY", "ASSEMBLYAI_API_KEY", "SARVAM_API_KEY",
            "deepgramApiKey", "assemblyAiApiKey"
        )
        val offenders = sources().flatMap { f ->
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        assertTrue("provider credential names found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no static bearer or installation token is built into the app`() {
        // The installation token is issued by our backend at runtime. A literal
        // bearer value in the sources would be a shared secret in every APK.
        val staticBearer = Regex("""Bearer [A-Za-z0-9._~+/=-]{16,}""")
        val offenders = sources().mapNotNull { f ->
            staticBearer.find(f.readText())?.let { "${f.name}: ${it.value.take(14)}..." }
        }
        assertTrue("static bearer token literal found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the installation credential is excluded from backup and device transfer`() {
        val res = File(moduleRoot(), "src/main/res/xml")
        val extraction = File(res, "data_extraction_rules.xml").readText()
        val legacy = File(res, "backup_rules.xml").readText()
        val rule = """<exclude domain="sharedpref" path="backend_installation.xml" />"""
        assertTrue("cloud backup", extraction.substringAfter("<cloud-backup>")
            .substringBefore("</cloud-backup>").contains(rule))
        assertTrue("device transfer", extraction.substringAfter("<device-transfer>")
            .substringBefore("</device-transfer>").contains(rule))
        assertTrue("legacy auto backup", legacy.contains(rule))
    }

    @Test
    fun `the app never names a provider endpoint directly`() {
        // All provider traffic goes through our backend. A provider hostname in
        // the app would mean some path bypasses it.
        val hosts = listOf("api.deepgram.com", "api.assemblyai.com", "api.sarvam.ai")
        val offenders = sources()
            .filter { it.extension in setOf("kt", "java", "xml") }
            .flatMap { f ->
                val text = f.readText()
                hosts.filter { text.contains(it) }.map { "${f.name}: $it" }
            }
        assertTrue("provider endpoints referenced in app: $offenders", offenders.isEmpty())
    }
}
