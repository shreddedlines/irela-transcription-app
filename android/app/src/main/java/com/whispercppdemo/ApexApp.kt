package com.whispercppdemo

import android.app.Application
import com.whispercppdemo.diag.CrashReporter
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.transcribe.provider.BackendConfig

/**
 * Process-wide initialisation, done once and before anything can ask a
 * question that depends on it.
 *
 * This exists because of a real device failure: [BackendConfig] used to be
 * initialised only inside `TranscriptionService.onCreate`, so on a cold start
 * the Activity asked `EngineSelector.effective()` before any service had run,
 * got `LOCAL` for a cloud build, and skipped the cloud disclosure gate
 * entirely. The service then started, initialised the config, saw `CLOUD`, and
 * refused the job -- telling the user to review a screen they were never
 * shown.
 *
 * Configuration must therefore be a property of the PROCESS, not of whichever
 * component happened to start first.
 */
class ApexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Diagnostics default to OFF until this runs, so ordering it first can
        // only ever make us quieter, never leakier.
        Diag.init(this)
        CrashReporter.install(this)
        // A URL, never a credential. Blank means "no backend in this build".
        BackendConfig.init(this)
    }
}
