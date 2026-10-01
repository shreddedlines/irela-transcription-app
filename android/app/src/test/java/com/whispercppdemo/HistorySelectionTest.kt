package com.whispercppdemo

import com.whispercppdemo.ui.common.HistorySelection
import com.whispercppdemo.ui.common.SelectionAction.DELETE
import com.whispercppdemo.ui.common.SelectionAction.DELETE_ALL
import com.whispercppdemo.ui.common.SelectionAction.EDIT
import com.whispercppdemo.ui.common.SelectionAction.REMOVE
import com.whispercppdemo.ui.common.SelectionAction.RENAME
import com.whispercppdemo.ui.common.SelectionAction.RETRY
import com.whispercppdemo.ui.common.SelectionAction.SELECT_ALL
import com.whispercppdemo.ui.common.SelectionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WhatsApp-style History selection: toggling and the ⋮ menu rules. */
class HistorySelectionTest {

    private val t1 = SelectionKeys.transcript("t1")
    private val t2 = SelectionKeys.transcript("t2")
    private val t3 = SelectionKeys.transcript("t3")
    private val failed = SelectionKeys.attempt("j1")      // retryable
    private val cancelled = SelectionKeys.attempt("j2")   // audio gone
    private val visible = listOf(t1, failed, t2, cancelled, t3)
    private val retryable: (String) -> Boolean = { it == "j1" }

    private fun menu(vararg sel: String) =
        HistorySelection.menu(sel.toList(), visible, hasTranscripts = true, isRetryable = retryable)

    @Test
    fun `keys keep the two id spaces apart`() {
        assertTrue(SelectionKeys.isTranscript("t:42"))
        assertTrue(SelectionKeys.isAttempt("a:42"))
        assertEquals("42", SelectionKeys.id("a:42"))
        assertTrue(SelectionKeys.transcript("x") != SelectionKeys.attempt("x"))
    }

    @Test
    fun `toggle adds, removes, and an empty selection ends the mode`() {
        var sel = HistorySelection.toggle(emptyList(), t1)
        assertEquals(listOf(t1), sel)
        sel = HistorySelection.toggle(sel, t2)
        sel = HistorySelection.toggle(sel, failed)
        assertEquals(3, sel.size)
        sel = HistorySelection.toggle(sel, t2)
        assertEquals(listOf(t1, failed), sel)
        sel = HistorySelection.toggle(HistorySelection.toggle(sel, t1), failed)
        assertTrue("deselecting the last row leaves selection mode", sel.isEmpty())
    }

    @Test
    fun `one completed transcript offers edit, rename and delete`() {
        assertEquals(listOf(EDIT, RENAME, DELETE, SELECT_ALL), menu(t1))
    }

    @Test
    fun `one retryable failed item offers try again and remove`() {
        assertEquals(listOf(RETRY, REMOVE, SELECT_ALL), menu(failed))
    }

    @Test
    fun `one item whose audio is gone offers only remove`() {
        assertEquals(listOf(REMOVE, SELECT_ALL), menu(cancelled))
    }

    @Test
    fun `several transcripts offer delete, never edit or rename`() {
        val items = menu(t1, t2, t3)
        assertEquals(listOf(DELETE, SELECT_ALL), items)
    }

    @Test
    fun `several attempts only are removed, a mix is deleted`() {
        assertEquals(listOf(REMOVE, SELECT_ALL), menu(failed, cancelled))
        assertEquals(listOf(DELETE, SELECT_ALL), menu(t1, failed))
    }

    @Test
    fun `select all disappears once everything visible is selected`() {
        assertEquals(listOf(DELETE), menu(*visible.toTypedArray()))
    }

    @Test
    fun `normal mode offers select all and delete all`() {
        assertEquals(listOf(SELECT_ALL, DELETE_ALL),
                     HistorySelection.menu(emptyList(), visible, hasTranscripts = true, isRetryable = retryable))
        assertEquals("no transcripts, nothing to delete-all",
                     listOf(SELECT_ALL),
                     HistorySelection.menu(emptyList(), listOf(failed), hasTranscripts = false, isRetryable = retryable))
    }

    @Test
    fun `prune drops keys that no longer exist`() {
        assertEquals(listOf(t1), HistorySelection.prune(listOf(t1, t2), listOf(t1, t3)))
    }

    @Test
    fun `one confirmation, worded for what is selected`() {
        HistorySelection.deleteCopy(1, 0).let {
            assertEquals("Delete transcript?", it.title); assertEquals("This can't be undone.", it.body); assertEquals("Delete", it.confirm)
        }
        assertEquals("Delete 3 transcripts?", HistorySelection.deleteCopy(3, 0).title)
        HistorySelection.deleteCopy(0, 1).let {
            assertEquals("Remove this item?", it.title); assertEquals("It will be removed from History.", it.body); assertEquals("Remove", it.confirm)
        }
        assertEquals("Remove 2 items?", HistorySelection.deleteCopy(0, 2).title)
        HistorySelection.deleteCopy(1, 2).let {
            assertEquals("Delete 3 items?", it.title)
            assertEquals("1 transcript and 2 failed or cancelled items. This can't be undone.", it.body)
        }
    }

    @Test
    fun `snackbar after delete`() {
        assertEquals("Transcript deleted", HistorySelection.deletedMessage(1, 0))
        assertEquals("3 transcripts deleted", HistorySelection.deletedMessage(3, 0))
        assertEquals("Removed from History", HistorySelection.deletedMessage(0, 1))
        assertEquals("2 items removed", HistorySelection.deletedMessage(0, 2))
        assertEquals("3 items deleted", HistorySelection.deletedMessage(2, 1))
    }
}
