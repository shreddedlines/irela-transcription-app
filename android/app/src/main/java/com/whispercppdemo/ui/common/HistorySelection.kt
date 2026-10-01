package com.whispercppdemo.ui.common

/**
 * History multi-selection rules, kept free of Compose so they can be unit
 * tested exactly as the approved prototype specifies them.
 *
 * A selection key is "t:<recordId>" for a transcript or "a:<jobId>" for a
 * failed/cancelled attempt: the two id spaces are independent and could
 * collide, so they are never compared bare.
 */
object SelectionKeys {
    fun transcript(id: String) = "t:$id"
    fun attempt(jobId: String) = "a:$jobId"
    fun isTranscript(key: String) = key.startsWith("t:")
    fun isAttempt(key: String) = key.startsWith("a:")
    fun id(key: String) = key.substring(2)
}

enum class SelectionAction { EDIT, RENAME, DELETE, RETRY, REMOVE, SELECT_ALL, DELETE_ALL }

object HistorySelection {

    /** Taps and long presses both toggle; an empty selection ends the mode. */
    fun toggle(selection: List<String>, key: String): List<String> =
        if (key in selection) selection - key else selection + key

    /** Keys that no longer exist on screen are dropped (after a delete or search). */
    fun prune(selection: List<String>, visible: Collection<String>): List<String> =
        selection.filter { it in visible }

    /**
     * The ⋮ menu. Only actions that apply to exactly what is selected appear:
     *  - one completed transcript: Edit transcript · Rename · Delete
     *  - one failed/cancelled item: Try again (only if retryable) · Remove
     *  - several: Delete, or Remove when every selected item is an attempt
     *  - Select all while anything visible is still unselected
     *  - nothing selected: Select all · Delete all transcripts
     */
    fun menu(
        selection: List<String>,
        visible: List<String>,
        hasTranscripts: Boolean,
        isRetryable: (jobId: String) -> Boolean
    ): List<SelectionAction> {
        val items = mutableListOf<SelectionAction>()
        if (selection.isEmpty()) {
            if (visible.isNotEmpty()) items += SelectionAction.SELECT_ALL
            if (hasTranscripts) items += SelectionAction.DELETE_ALL
            return items
        }
        val only = selection.singleOrNull()
        when {
            only != null && SelectionKeys.isTranscript(only) ->
                items += listOf(SelectionAction.EDIT, SelectionAction.RENAME, SelectionAction.DELETE)
            only != null -> {
                if (isRetryable(SelectionKeys.id(only))) items += SelectionAction.RETRY
                items += SelectionAction.REMOVE
            }
            selection.none(SelectionKeys::isTranscript) -> items += SelectionAction.REMOVE
            else -> items += SelectionAction.DELETE
        }
        if (visible.isNotEmpty() && !visible.all { it in selection }) items += SelectionAction.SELECT_ALL
        return items
    }

    data class DeleteCopy(val title: String, val body: String, val confirm: String)

    /** One confirmation for the whole selection, worded for what it contains. */
    fun deleteCopy(transcripts: Int, attempts: Int): DeleteCopy {
        val n = transcripts + attempts
        return when {
            attempts == 0 -> DeleteCopy(
                if (n == 1) "Delete transcript?" else "Delete $n transcripts?",
                "This can't be undone.", "Delete")
            transcripts == 0 -> DeleteCopy(
                if (n == 1) "Remove this item?" else "Remove $n items?",
                if (n == 1) "It will be removed from History." else "They will be removed from History.",
                "Remove")
            else -> DeleteCopy(
                "Delete $n items?",
                "$transcripts ${if (transcripts == 1) "transcript" else "transcripts"} and $attempts " +
                    "failed or cancelled ${if (attempts == 1) "item" else "items"}. This can't be undone.",
                "Delete")
        }
    }

    /** The snackbar after a confirmed delete. */
    fun deletedMessage(transcripts: Int, attempts: Int): String {
        val n = transcripts + attempts
        return when {
            attempts == 0 -> if (n == 1) "Transcript deleted" else "$n transcripts deleted"
            transcripts == 0 -> if (n == 1) "Removed from History" else "$n items removed"
            else -> "$n items deleted"
        }
    }
}
