package com.whispercppdemo.media

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.ui.common.sanitizeFileName
import java.io.File

private const val LOG_TAG = "TranscriptShare"

/** Where staged .txt files live. Matches res/xml/share_paths.xml exactly. */
private const val SHARE_DIR = "share"

/**
 * Writes [record] to a temporary .txt and returns a share intent for it, or
 * null if the file could not be written.
 *
 * The file is staged inside our own cache and handed over by content URI with
 * a one-file read grant. Nothing leaves the device that the user did not
 * explicitly send, and no INTERNET permission is involved -- the receiving app
 * does whatever it does.
 *
 * The body is [TranscriptRecord.text] verbatim, so an edited transcript shares
 * its edited content and nothing is added, reformatted or labelled.
 */
fun buildTranscriptShareIntent(context: Context, record: TranscriptRecord): Intent? {
    return try {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, "${sanitizeFileName(record.displayName)}.txt")
        file.writeText(record.text)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.shareprovider",
            file
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Some receivers only read EXTRA_TEXT; supplying both means the
            // transcript arrives either way rather than as an empty message.
            putExtra(Intent.EXTRA_TEXT, record.text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (e: Exception) {
        Diag.w(LOG_TAG, "could not stage transcript for sharing", e)
        null
    }
}

/**
 * Deletes previously shared .txt files.
 *
 * Called at launch rather than after each share: the receiving app may still
 * be reading the file when the chooser closes, so deleting it immediately
 * would race a legitimate reader. They are small, they are in cache, and the
 * OS can evict them anyway.
 */
fun sweepShareCache(context: Context) {
    runCatching {
        File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { it.delete() }
    }
}
