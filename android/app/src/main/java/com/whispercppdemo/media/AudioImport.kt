package com.whispercppdemo.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.whispercppdemo.diag.Diag

private const val LOG_TAG = "AudioImport"

/** Distinct failure modes, so the UI can say something specific. */
sealed class ImportError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class InaccessibleUri(cause: Throwable? = null) :
        ImportError("Could not read that file. The app that shared it may have revoked access.", cause)

    class UnsupportedType(val mime: String?) :
        ImportError("That file type isn't supported${if (mime != null) " ($mime)" else ""}.")

    class CorruptAudio(cause: Throwable? = null) :
        ImportError("That audio file appears to be damaged or incomplete.", cause)

    class DecodeFailed(cause: Throwable? = null) :
        ImportError("Could not decode that audio.", cause)

    class Empty : ImportError("That file contains no audio.")
}

/** An imported file copied into our own cache, with what we know about it. */
data class StagedAudio(
    val file: File,
    val displayName: String,
    val bytes: Long
)

/**
 * Copies [uri] into app cache immediately.
 *
 * ACTION_SEND grants read access only for the lifetime of the receiving task
 * and cannot be persisted (takePersistableUriPermission applies to SAF
 * ACTION_OPEN_DOCUMENT only). Transcription can run for many minutes, so the
 * bytes must be ours before any long work starts.
 */
/**
 * The file's DISPLAY_NAME as its provider reports it, or null. Picker and
 * search-result URIs carry an internal document id, so the name must be asked
 * for; it is never derived from the URI here.
 */
fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getString(i) else null
    }
}.onFailure { Diag.w(LOG_TAG, "display name query failed", it) }.getOrNull()

suspend fun stageAudio(context: Context, uri: Uri): StagedAudio = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver

    var name = ""
    var size = -1L
    try {
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let { if (!c.isNull(it)) name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
    } catch (e: Exception) {
        Diag.w(LOG_TAG, "metadata query failed for $uri", e)   // non-fatal
    }

    val dir = File(context.cacheDir, "imports").apply { mkdirs() }
    val ext = name.substringAfterLast('.', "").take(8).ifEmpty { "bin" }
    val dest = File(dir, "${UUID.randomUUID()}.$ext")

    try {
        // Stage 3. On the WhatsApp path this reads across that app's
        // ContentProvider binder rather than from a local file, which is the
        // first place the share and Open-with entry paths can diverge in cost.
        com.whispercppdemo.diag.RunTrace.stageStart(com.whispercppdemo.diag.RunTrace.Stage.STAGE_COPY)
        val input = resolver.openInputStream(uri) ?: throw ImportError.InaccessibleUri()
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        com.whispercppdemo.diag.RunTrace.stageEnd(com.whispercppdemo.diag.RunTrace.Stage.STAGE_COPY, "bytes=${dest.length()}")
    } catch (e: ImportError) {
        dest.delete(); throw e
    } catch (e: Exception) {
        dest.delete(); throw ImportError.InaccessibleUri(e)
    }

    if (dest.length() == 0L) {
        dest.delete()
        throw ImportError.Empty()
    }

    Diag.d(LOG_TAG, "staged (${dest.length()} bytes) -> ${dest.name}")
    // Blank when Android exposed no usable name: the job keeps what it has.
    StagedAudio(dest, ImportNames.clean(name).orEmpty(), if (size >= 0) size else dest.length())
}

/** Removes leftovers from a previous process that died mid-import. */
fun sweepImportCache(context: Context, keep: Set<String> = emptySet()) {
    runCatching {
        File(context.cacheDir, "imports").listFiles()?.forEach { f ->
            // A staged copy belonging to a job that is not finished -- an
            // orphan from a killed process, a failure waiting on Retry, a
            // cancelled job -- is the ONLY copy of that audio: the share URI
            // that delivered it died with the sending task. Deleting it here
            // turned Retry into a button that could never work, and this sweep
            // ran on every cold start, which is exactly when a user returns to
            // a job that died.
            if (f.absolutePath !in keep) f.delete()
        }
    }
}
