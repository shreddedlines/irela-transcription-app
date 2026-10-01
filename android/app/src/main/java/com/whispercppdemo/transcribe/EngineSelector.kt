package com.whispercppdemo.transcribe

import android.content.Context
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.DeepgramProvider
import com.whispercppdemo.transcribe.provider.SharedPrefsInstallationCredentialStore
import com.whispercppdemo.transcribe.provider.LocalWhisperProvider
import com.whispercppdemo.transcribe.provider.TranscriptionProvider

/** Which engine performs the transcription. */
enum class Engine { CLOUD, LOCAL }

/**
 * Chooses the engine. One decision, one place.
 *
 * Both engines run through the SAME JobRunner and the same
 * TranscriptionProvider interface, so nothing about persistence, retry,
 * cancellation, limits or dedupe is duplicated per engine — switching is a
 * provider swap, not a second pipeline.
 *
 * THE PRODUCTION INTENT IS CLOUD. On-device Apex was measured at 6 min 35 s for
 * 3 min 52 s of audio, 80 C peak and 820 MB PSS; Deepgram returns the same
 * audio in about 5 s. Apex stays in the codebase behind [devOverride] so the
 * app still works while the backend is unavailable, and so the measurement rig
 * keeps working — it is not deleted.
 *
 * The effective engine is deliberately NOT simply [PRODUCTION_ENGINE]: cloud
 * requires a configured backend, and a build without one must fall back to
 * LOCAL rather than fail every request. That is also what keeps today's release
 * on Apex — the released APK ships no base URL, so nothing flips until a build
 * actually configures one.
 */
object EngineSelector {

    /** What production is meant to use once a backend URL is configured. */
    val PRODUCTION_ENGINE = Engine.CLOUD

    /**
     * Development override. Set from a debug build or a test to force an
     * engine regardless of configuration.
     *
     * Null means "decide normally". This exists so Apex can be run deliberately
     * while the backend is down — never as a silent fallback when the network
     * fails, which would hand the user a slow, hot, lower-quality transcript
     * without telling them.
     */
    @Volatile
    var devOverride: Engine? = null

    /**
     * CLOUD only when a backend is actually configured.
     *
     * Note what this does NOT do: it never downgrades to LOCAL because the
     * network is down. Connectivity failures surface as failures.
     */
    fun effective(): Engine =
        devOverride ?: if (BackendConfig.configured) PRODUCTION_ENGINE else Engine.LOCAL

    /**
     * Whether the app should load the local Whisper model when it starts.
     *
     * Only for LOCAL. A CLOUD build transcribes on the backend, so loading the
     * ~400 MB model (and the native library) at startup would cost memory and
     * time for nothing. The local path stays intact: [LocalWhisperProvider]
     * loads the model on demand, so a deliberate [devOverride] to LOCAL still
     * works.
     */
    fun loadsLocalModelAtStartup(engine: Engine = effective()): Boolean = engine == Engine.LOCAL

    /** True when the selected engine needs the network to work at all. */
    fun requiresNetwork(): Boolean = effective() == Engine.CLOUD

    /**
     * The provider for the effective engine.
     *
     * [onStage] carries the finer-grained decode/chunk progress the local
     * engine can report and a network upload cannot.
     */
    fun provider(
        context: Context,
        onStage: (TranscriptionState) -> Unit = {}
    ): TranscriptionProvider = when (effective()) {
        Engine.CLOUD -> DeepgramProvider(SharedPrefsInstallationCredentialStore(context))
        Engine.LOCAL -> LocalWhisperProvider(context, onStage)
    }
}
