package com.whispercppdemo.ui.common

import com.whispercppdemo.history.TranscriptRecord
import java.text.Normalizer
import java.util.Locale

/**
 * Text operations shared by search, copy and share.
 *
 * All pure and all local: nothing here touches the network, a model, or the
 * stored record. Keeping them out of the composables is what lets every rule
 * below be pinned down by a JVM test.
 */

/**
 * Folds text for comparison: NFC first so that a composed and a decomposed
 * Devanagari spelling of the same word match, then lower-cased.
 *
 * [Locale.ROOT] rather than the device locale on purpose -- under a Turkish
 * locale the default lowercase maps 'I' to a dotless i, so a Hinglish search
 * for "India" would stop matching on those devices.
 */
private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * True when [query] appears in the record's title or its transcript body.
 *
 * Searches the real persisted text, so an edited transcript is found by its
 * edited content. A blank query matches everything, which is what returns
 * History to its normal listing.
 */
fun matchesQuery(record: TranscriptRecord, query: String): Boolean {
    val q = fold(query.trim())
    if (q.isEmpty()) return true
    return fold(record.displayName).contains(q) || fold(record.text).contains(q)
}

/**
 * A safe file name stem for a shared .txt.
 *
 * An allow-list rather than a block-list: letters, digits and combining marks
 * survive, everything else becomes '_'. Keeping combining marks is what lets
 * Devanagari titles through -- they are Mn/Mc, not letters, so a bare
 * isLetterOrDigit() check would strip the vowel signs out of a Hindi title and
 * leave mojibake. Path separators and dots cannot survive, so no title can
 * escape the share directory. Falls back to "transcript" when nothing usable
 * remains.
 */
fun sanitizeFileName(title: String, maxLength: Int = 60): String {
    val normalized = Normalizer.normalize(title.trim(), Normalizer.Form.NFC)
    val replaced = buildString(normalized.length) {
        for (ch in normalized) {
            append(if (ch.isLetterOrDigit() || ch == '_' || ch.isCombiningMark()) ch else '_')
        }
    }
    val collapsed = replaced
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(maxLength)
        .trim('_')
    return collapsed.ifEmpty { "transcript" }
}

/** Devanagari vowel signs and the like: part of the word, not punctuation. */
private fun Char.isCombiningMark(): Boolean = when (category) {
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK -> true
    else -> false
}
