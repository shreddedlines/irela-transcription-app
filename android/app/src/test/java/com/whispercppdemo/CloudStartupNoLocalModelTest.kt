package com.whispercppdemo

import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A CLOUD build must not load the local Whisper model (or its native library)
 * at startup; LOCAL keeps loading it exactly as before.
 */
class CloudStartupNoLocalModelTest {

    private val vm = File("src/main/java/com/whispercppdemo/ui/main/MainScreenViewModel.kt").readText()

    @Test
    fun onlyLocalLoadsTheModelAtStartup() {
        assertTrue(EngineSelector.loadsLocalModelAtStartup(Engine.LOCAL))
        assertFalse(EngineSelector.loadsLocalModelAtStartup(Engine.CLOUD))
    }

    @Test
    fun viewModelStartupGatesBothNativeTouchesOnTheEngine() {
        val init = vm.substring(vm.indexOf("    init {"))
            .let { it.substring(0, it.indexOf("refreshHistory(\"vm_init\")")) }
        val gate = init.indexOf("if (EngineSelector.loadsLocalModelAtStartup())")
        val elseBranch = init.indexOf("} else {")
        assertTrue("startup is gated on the engine", gate >= 0 && elseBranch > gate)
        // getSystemInfo() loads the native library; loadData() loads the model.
        listOf("printSystemInfo()", "loadData()").forEach {
            val at = init.indexOf(it)
            assertTrue("$it runs only on the LOCAL branch", at in (gate + 1) until elseBranch)
        }
        val cloud = init.substring(elseBranch)
        assertTrue("CLOUD is ready at once", cloud.contains("canTranscribe = true"))
        assertTrue(cloud.contains("modelReady.complete(Unit)"))
        assertFalse(cloud.contains("WhisperEngine") || cloud.contains("WhisperContext"))
    }

    @Test
    fun modelReadyExistsBeforeInitCompletesIt() {
        // Property initialisers run in declaration order; the CLOUD branch can
        // complete modelReady synchronously inside init.
        assertTrue(vm.indexOf("private val modelReady") in 0 until vm.indexOf("    init {"))
    }

    @Test
    fun nothingElseTouchesTheLocalEngineAtProcessOrActivityStart() {
        listOf("ApexApp.kt", "MainActivity.kt", "transcribe/TranscriptionService.kt",
               "recorder/RecordingService.kt", "ui/nav/AppNavHost.kt").forEach { path ->
            // Code only: comments may name the engine.
            val src = File("src/main/java/com/whispercppdemo/$path").readLines()
                .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }
                .joinToString("\n")
            listOf("WhisperEngine", "WhisperContext", "LibWhisper").forEach {
                assertFalse("$path references $it", src.contains(it))
            }
        }
        // The on-demand local path is kept for development.
        assertEquals(1, Regex("WhisperEngine\\.use\\(").findAll(
            File("src/main/java/com/whispercppdemo/transcribe/provider/LocalWhisperProvider.kt").readText()).count())
    }
}
