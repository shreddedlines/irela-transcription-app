package com.whispercppdemo.history

import android.content.Context
import java.io.File

/**
 * App-wide location of the history store.
 *
 * Kept apart from TranscriptHistoryRepository so the repository itself stays
 * free of Android types and can be unit-tested against a temp directory.
 */
object HistoryStore {
    fun repository(context: Context): TranscriptHistoryRepository =
        TranscriptHistoryRepository(File(context.filesDir, "history"))
}
