package com.whispercppdemo.transcribe

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import com.whispercppdemo.diag.Diag

private const val LOG_TAG = "WhisperEngine"

/**
 * The one and only WhisperContext in this process.
 *
 * The model is ~400 MB resident, so a second instance is not a leak to tidy
 * up later — it is an OOM. Ownership therefore lives here, in an
 * application-scoped singleton, rather than in an Activity-scoped ViewModel:
 *
 *  - Activity recreation (rotation) does not touch it.
 *  - A share intent arriving does not touch it (MainActivity is singleTask,
 *    so there is only ever one Activity anyway).
 *  - The foreground service and the ViewModel share this instance instead of
 *    each loading their own.
 *
 * [load] is idempotent and mutex-guarded, so concurrent callers -- typically
 * the ViewModel starting up while a share is already being handled -- collapse
 * onto a single load. [use] serialises access, which whisper.cpp requires
 * (one thread at a time per context).
 */
object WhisperEngine {

    /**
     * The validated production model. Selected by name so that a stale model
     * left in the directory cannot silently take over: [listFiles] order is
     * filesystem-dependent, not alphabetical. Any other single `.bin` is still
     * accepted, which keeps sideloading a different model working as before.
     */
    const val PRODUCTION_MODEL = "apex-q4_k.bin"

    private val loadMutex = Mutex()
    private val useMutex = Mutex()

    private fun pickModel(app: Context): File? {
        val files = app.getExternalFilesDir("models")?.listFiles()
            ?.filter { it.name.endsWith(".bin") }
            ?: return null
        return files.firstOrNull { it.name == PRODUCTION_MODEL } ?: files.firstOrNull()
    }

    @Volatile
    private var context: WhisperContext? = null

    @Volatile
    var modelName: String? = null
        private set

    @Volatile
    var lastLoadMillis: Long = 0
        private set

    val isLoaded: Boolean get() = context != null

    suspend fun systemInfo(): String = WhisperContext.getSystemInfo()

    /**
     * Loads the model if it is not loaded already. Safe to call from anywhere,
     * any number of times, concurrently.
     */
    suspend fun load(app: Context): WhisperContext = loadMutex.withLock {
        context?.let { return@withLock it }

        val extModels = app.getExternalFilesDir("models")
        val pushed = pickModel(app)
            ?: throw IllegalStateException("No model found in ${extModels?.absolutePath}")

        val t0 = System.currentTimeMillis()
        com.whispercppdemo.diag.RunTrace.stageStart(
            com.whispercppdemo.diag.RunTrace.Stage.MODEL_LOAD
        )
        val ctx = WhisperContext.createContextFromFile(pushed.absolutePath)
        lastLoadMillis = System.currentTimeMillis() - t0
        // Only a cold start pays this. It lands inside the user's wait on the
        // share path because the intent arrives while the model is still
        // loading, so it belongs in the same breakdown.
        com.whispercppdemo.diag.RunTrace.stageEnd(
            com.whispercppdemo.diag.RunTrace.Stage.MODEL_LOAD,
            "model=${pushed.name}|bytes=${pushed.length()}"
        )
        modelName = pushed.name
        context = ctx
        Diag.d(LOG_TAG, "loaded ${pushed.name} (${pushed.length()} bytes) in $lastLoadMillis ms")
        ctx
    }

    /** Runs [block] against the shared context, serialised against other users. */
    suspend fun <T> use(app: Context, block: suspend (WhisperContext) -> T): T {
        val ctx = load(app)
        return useMutex.withLock { block(ctx) }
    }

    /** Size of the model file backing the loaded context, for logging. */
    fun modelFile(app: Context): File? = pickModel(app)
}
