package com.whispercppdemo.history

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

private const val FORMAT_VERSION = "1"
private const val EXTENSION = ".rec"
private const val CHARSET = "UTF-8"

/**
 * Completed-transcript history, stored as one small text file per record in
 * a directory.
 *
 * Chosen over Room/DataStore deliberately: this is a short, append-mostly
 * list with no querying, and a plain file store adds no dependencies, no
 * annotation processor, and no Android runtime — so the whole thing is
 * exercisable by ordinary JVM unit tests against a temp directory.
 *
 * One file per record rather than one index file, so a torn write can only
 * ever cost the record being written. Writes go to a temp file and are
 * renamed into place. Fields are URL-encoded, so transcripts containing
 * newlines, tabs or '=' round-trip safely.
 */
class TranscriptHistoryRepository(private val dir: File) {

    /** Persists [record] and returns it. Overwrites any record with the same id. */
    @Throws(IOException::class)
    fun save(record: TranscriptRecord): TranscriptRecord {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create history dir: $dir")
        }
        val target = File(dir, record.id + EXTENSION)
        val tmp = File(dir, record.id + EXTENSION + ".tmp")
        tmp.writeText(encode(record))
        if (!tmp.renameTo(target)) {
            // renameTo will not replace on some filesystems.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("cannot write history record ${record.id}")
            }
        }
        return record
    }

    /**
     * Where [id] is stored. Exposed so a caller can verify -- and report -- the
     * exact path and byte count a save produced, rather than inferring that a
     * write happened because save() returned.
     */
    fun fileFor(id: String): File = File(dir, id + EXTENSION)

    /**
     * One record by id, or null.
     *
     * Exists so the service can publish a completion by reading back what it
     * (or an earlier identical job) stored, without loading the whole history
     * to find one file.
     */
    fun load(id: String): TranscriptRecord? = runCatching {
        val f = fileFor(id)
        if (f.isFile) decode(f.readText()) else null
    }.getOrNull()

    /** All persisted records, newest first. Malformed files are skipped. */
    fun loadAll(): List<TranscriptRecord> {
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(EXTENSION) }
            ?: return emptyList()
        return files
            .mapNotNull { runCatching { decode(it.readText()) }.getOrNull() }
            // Newest first; id breaks ties so ordering is deterministic when
            // two records share a millisecond.
            .sortedWith(compareByDescending<TranscriptRecord> { it.createdAt }.thenByDescending { it.id })
    }

    /**
     * Removes the record with [id]. Returns true if a file was actually
     * deleted.
     *
     * Safe when the record is already gone -- a stale Detail screen or a
     * double tap just returns false rather than throwing. One file per record
     * is what makes this a single unlink with no index to keep consistent.
     */
    fun delete(id: String): Boolean {
        val target = File(dir, id + EXTENSION)
        // Guard against an id that would escape the history directory. Ids are
        // UUIDs today, but nothing in the type system says so.
        if (target.parentFile != dir) return false
        return target.isFile && target.delete()
    }

    /**
     * Removes every persisted transcript. Returns how many were deleted.
     *
     * Scoped deliberately: it enumerates only `*.rec` files inside this
     * repository's own directory, so it can never touch the model, the caches,
     * or anything else under filesDir. The directory itself is left in place.
     */
    fun deleteAll(): Int {
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(EXTENSION) }
            ?: return 0
        return files.count { it.delete() }
    }

    // ---- serialisation ----------------------------------------------------

    private fun encode(r: TranscriptRecord): String = buildString {
        append("v=").append(FORMAT_VERSION).append('\n')
        append("id=").append(esc(r.id)).append('\n')
        append("name=").append(esc(r.displayName)).append('\n')
        append("createdAt=").append(r.createdAt).append('\n')
        append("source=").append(r.source.name).append('\n')
        append("durationMs=").append(r.durationMs ?: -1L).append('\n')
        append("text=").append(esc(r.text)).append('\n')
    }

    private fun decode(raw: String): TranscriptRecord {
        val fields = raw.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()

        require(fields["v"] == FORMAT_VERSION) { "unsupported record version" }
        val duration = fields["durationMs"]?.toLongOrNull() ?: -1L
        return TranscriptRecord(
            id = unesc(requireNotNull(fields["id"])),
            displayName = unesc(requireNotNull(fields["name"])),
            createdAt = requireNotNull(fields["createdAt"]).toLong(),
            text = unesc(fields["text"].orEmpty()),
            source = TranscriptSource.valueOf(requireNotNull(fields["source"])),
            durationMs = if (duration < 0) null else duration
        )
    }

    private fun esc(s: String): String = URLEncoder.encode(s, CHARSET)
    private fun unesc(s: String): String = URLDecoder.decode(s, CHARSET)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
