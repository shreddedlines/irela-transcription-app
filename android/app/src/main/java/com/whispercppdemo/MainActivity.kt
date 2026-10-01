package com.whispercppdemo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.whispercppdemo.diag.CrashReporter
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.diag.RunTrace
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.recorder.RecordingFiles
import com.whispercppdemo.transcribe.TranscriptionService
import com.whispercppdemo.media.sweepImportCache
import com.whispercppdemo.media.sweepShareCache
import com.whispercppdemo.ui.nav.AppNavHost
import com.whispercppdemo.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme

private const val LOG_TAG = "MainActivity"

/** Marks an intent whose audio we have already picked up. */
private const val EXTRA_IMPORT_CONSUMED = "com.whispercppdemo.IMPORT_CONSUMED"

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diag and BackendConfig are initialised in ApexApp, before any
        // component can ask which engine is effective. The watchdog is
        // Activity-scoped because it watches the main looper.
        CrashReporter.startAnrWatchdog()
        if (savedInstanceState == null) {
            // Clear anything a previous process left mid-import -- except the
            // staged audio that an unfinished job still needs. Skipped on
            // recreation so a rotation cannot delete a live staged file.
            // A recording the process died in the middle of is already on
            // disk, sample for sample. Make it a visible interrupted job with
            // Retry rather than a file nobody can reach. Recordings a live
            // recorder in this process is still writing are left alone.
            // Recordings live in permanent storage; move any an earlier
            // version left in the cache first, so nothing below misses them.
            runCatching {
                com.whispercppdemo.recorder.RecordingStorage.migrateFromCache(
                    cacheDir, filesDir, JobStore(java.io.File(filesDir, "jobs")))
            }.onSuccess { if (it > 0) Diag.w(LOG_TAG, "moved $it recording file(s) out of the cache") }
                .onFailure { Diag.w(LOG_TAG, "recording migration skipped", it) }
            runCatching {
                RecordingFiles.recoverAbandoned(
                    com.whispercppdemo.recorder.RecordingStorage.recordingsDir(filesDir),
                    JobStore(java.io.File(filesDir, "jobs")), System.currentTimeMillis())
            }.onSuccess { if (it.isNotEmpty()) Diag.w(LOG_TAG, "recovered ${it.size} interrupted recording(s)") }
            // Job-aware cleanup: never audio a job still needs; recordings no
            // job knows about are recovered, not deleted. Replaces the import
            // sweep, which ignored finished recordings entirely.
            runCatching {
                val store = JobStore(java.io.File(filesDir, "jobs"))
                val session = com.whispercppdemo.recorder.Recordings.controller(this).session.value
                val inUse = setOfNotNull(session?.target?.absolutePath)
                val plan = com.whispercppdemo.jobs.StorageJanitor.plan(
                    com.whispercppdemo.recorder.RecordingStorage.recordingsDir(filesDir),
                    java.io.File(cacheDir, "imports"), store.all(), inUse,
                    System.currentTimeMillis(),
                    stagingDirs = listOf(com.whispercppdemo.recorder.RecordingStorage.uploadDir(filesDir)))
                com.whispercppdemo.jobs.StorageJanitor.apply(plan, store, System.currentTimeMillis())
                Diag.d(LOG_TAG, "storage: deleted=${plan.delete.size} recovered=${plan.recover.size} kept=${plan.keep.size}")
            }.onFailure { Diag.w(LOG_TAG, "storage cleanup skipped", it) }
            // Shared .txt files from a previous run. Swept at launch, not
            // after each share, so a receiving app is never racing the delete.
            sweepShareCache(this)
            // A job left mid-flight by a killed process must become a visible
            // failure the moment the user comes back -- not whenever some
            // unrelated work happens to start the service next.
            TranscriptionService.recoverIfNeeded(this)
            // Cloud builds: confirm any Irela Pro purchase the backend has not
            // acknowledged yet (bought offline, or confirmation failed). It
            // grants nothing itself; it only asks the backend.
            if (com.whispercppdemo.transcribe.EngineSelector.effective() ==
                com.whispercppdemo.transcribe.Engine.CLOUD) {
                com.whispercppdemo.billing.ProBilling.confirmOutstanding(this)
            }
        }
        maybeRequestNotificationPermission()
        setContent {
            WhisperCppDemoTheme {
                AppNavHost(viewModel)
            }
        }
        handleIncomingAudio(intent)
    }

    /**
     * The foreground service runs regardless, but on API 33+ its progress
     * notification is invisible without this. Asked for once, non-blocking.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * Staged copies that are still the only source for an unfinished job.
     *
     * Anything not COMPLETED qualifies: orphans stuck mid-flight, failures
     * waiting on Retry, cancelled jobs, and anything still queued. A COMPLETED
     * job's audio is disposable -- the transcript is the artefact.
     */
    private fun stagedAudioStillNeeded(): Set<String> = runCatching {
        JobStore(java.io.File(filesDir, "jobs")).all()
            .filter { it.state != JobState.COMPLETED }
            .mapNotNull { it.stagedPath }
            .toSet()
    }.getOrDefault(emptySet())

    /** singleTask means shares arrive here rather than in a new activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAudio(intent)
    }

    private fun handleIncomingAudio(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_IMPORT_CONSUMED, false)) return

        // Stage 1 opens the instant the intent lands, so the WhatsApp share
        // path and the Open-with path are measured from the same moment on the
        // same clock and the two runs are directly comparable.
        RunTrace.begin(
            id = java.util.UUID.randomUUID().toString().take(8),
            note = "entry=${intent.action}|mime=${intent.type}|from=${runCatching { referrer?.host }.getOrNull()}"
        )
        RunTrace.stageStart(RunTrace.Stage.INTENT)
        RunTrace.stageEnd(RunTrace.Stage.INTENT, "action=${intent.action}")

        RunTrace.stageStart(RunTrace.Stage.URI_RESOLVE)
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.streamExtra()
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        RunTrace.stageEnd(
            RunTrace.Stage.URI_RESOLVE,
            "scheme=${uri?.scheme}|authority=${uri?.authority}"
        )
        if (uri == null) return

        // Stamp the intent so recreation cannot re-import the same file: the
        // same Intent instance is redelivered to onCreate after rotation.
        intent.putExtra(EXTRA_IMPORT_CONSUMED, true)
        setIntent(intent)

        Diag.d(LOG_TAG, "import intent ${intent.action} type=${intent.type} uri=$uri")
        viewModel.importAudio(uri)
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
