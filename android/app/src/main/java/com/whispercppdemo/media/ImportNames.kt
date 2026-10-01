package com.whispercppdemo.media

/**
 * The user-facing name of an imported file.
 *
 * Android's picker hands over document URIs whose last path segment is often a
 * provider-internal id ("msf:1000081972" for a search result, "1000081972" for
 * a MediaStore row), not a file name. The real name is OpenableColumns.DISPLAY_NAME;
 * when that is unavailable the app must still never title a transcript with an
 * id, a URI fragment or one of its own UUID staging names.
 */
object ImportNames {

    /** Title for an import whose real name Android did not expose. */
    const val FALLBACK = "Imported audio"

    /** Longest stored import name; longer names keep their start and extension. */
    const val MAX_LENGTH = 120

    /** "msf:1000081972", "audio:42": a provider prefix and a number. */
    private val SCHEME_NUMBER_ID = Regex("""^[A-Za-z_]+:\d+$""")
    /** Known document-provider prefixes with any opaque id ("document:acc=1;doc=7"). */
    private val KNOWN_PREFIX_ID = Regex("""^(msf|audio|video|image|document|media):\S+$""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ID = Regex("""^\d+$""")
    private val UUID_NAME = Regex(
        """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(\.[A-Za-z0-9]{1,8})?$""")

    /** The name to store for an import: DISPLAY_NAME, else a usable URI segment, else [FALLBACK]. */
    fun forImport(displayName: String?, uriLastSegment: String?): String =
        clean(displayName) ?: clean(uriLastSegment) ?: FALLBACK

    /**
     * A readable file name from something Android reported, or null when it is
     * not one: blank, a document or MediaStore id, or a UUID storage name.
     * Percent-encoding and path prefixes ("raw:/storage/.../call.m4a") are removed.
     */
    fun clean(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(s)) {
            s = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        }
        s = s.trimEnd('/').substringAfterLast('/')
        s = s.filterNot { it.isISOControl() }.trim()
        if (s.isEmpty() || NUMERIC_ID.matches(s) || looksLikeDocumentId(s)) return null
        return truncate(s)
    }

    /**
     * True for provider-internal identifiers that must never be shown as a
     * title ("msf:1000081972", "audio:42", a UUID file name). Deliberately not
     * true for plain numbers, which a person may choose as a title.
     */
    fun looksLikeDocumentId(name: String): Boolean {
        val s = name.trim()
        return SCHEME_NUMBER_ID.matches(s) || (KNOWN_PREFIX_ID.matches(s) && '.' !in s.substringAfter(':')) ||
            UUID_NAME.matches(s)
    }

    /** Caps [name] at [MAX_LENGTH], keeping a short extension and marking the cut. */
    fun truncate(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 && ' ' !in it }
        val suffix = if (ext != null) "….$ext" else "…"
        return name.substring(0, MAX_LENGTH - suffix.length).trimEnd() + suffix
    }
}
