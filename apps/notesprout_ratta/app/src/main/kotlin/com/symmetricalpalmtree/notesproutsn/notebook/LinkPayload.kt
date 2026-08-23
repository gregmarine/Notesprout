package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * The link payload codec (arc 6 / K1) — what a `link` row's `text` column holds. The grammar is
 * **Paper's v1, byte-for-byte** (`apps/notesprout_paper/PAPER_LINKS_PLAN.md`, its extension's
 * `LinkPayload`), so link rows stay family-compatible: SN reads a Paper-created link's chrome and
 * target (a cross-app id simply resolves dead — the dead-target rule), and Paper reads SN's. The
 * one family delta is *who* understands it: Paper's core treated the payload as opaque and asked
 * its Links extension; in SN the core owns link meaning, so the codec lives here.
 *
 * Grammar: `"L1|<chrome>|<kind>|<notebookId>|<pageId>"` — a versioned leading tag so a later arc
 * can extend the format, `|` as the separator (it cannot occur in an id: they are UUIDs),
 * [chrome] `0|1` ([CHROME_NONE]/[CHROME_UNDERLINE]), [kind] `0|1|2` ([KIND_PAGE]/[KIND_NOTEBOOK]/
 * [KIND_NOTEBOOK_PAGE]), and an empty slot for each id the kind does not carry:
 *
 * - `"L1|1|0||<pageId>"` — an underlined link to a page of the link's own notebook ([KIND_PAGE]
 *   carries **no** notebookId).
 * - `"L1|0|1|<notebookId>|"` — another notebook, no chrome ([KIND_NOTEBOOK]).
 * - `"L1|1|2|<notebookId>|<pageId>"` — a specific page of another notebook ([KIND_NOTEBOOK_PAGE]).
 *
 * Pure Kotlin — JVM-tested, with fixtures against Paper's grammar. [encode] throws on a caller
 * bug (only our own flows compose payloads); [decode] never throws: an unknown version is a
 * *future* (or foreign) payload, and the caller must treat it as unusable — chrome falls back to
 * [CHROME_NONE] and a follow lands in the dead-target dialog, never a crash.
 */
object LinkPayload {

    /** The current version tag. A payload that starts with anything else decodes to null. */
    const val VERSION = "L1"

    private const val SEP = '|'

    // Family constants — Paper's ExtensionContract values, verbatim (the format contract).
    const val CHROME_NONE = 0
    const val CHROME_UNDERLINE = 1
    const val KIND_PAGE = 0
    const val KIND_NOTEBOOK = 1
    const val KIND_NOTEBOOK_PAGE = 2

    /** Paper's `MAX_LINK_PAYLOAD_CHARS` — enforced in both directions (a file is untrusted input). */
    const val MAX_PAYLOAD_CHARS = 2_000

    /** Paper's `MAX_LINK_ID_CHARS` — ids are UUIDs (36 chars); the cap only bounds foreign input. */
    const val MAX_ID_CHARS = 64

    /** A decoded payload; the id fields follow the kind rules in the class KDoc. */
    data class Decoded(
        val chrome: Int,
        val kind: Int,
        val notebookId: String?,
        val pageId: String?,
    )

    /**
     * Compose a payload. Throws [IllegalArgumentException] for anything our own flows should never
     * produce: an unknown chrome or kind, a missing or blank required id, an id carrying the
     * separator, an id over [MAX_ID_CHARS], or an id the kind forbids.
     */
    fun encode(chrome: Int, kind: Int, notebookId: String?, pageId: String?): String {
        require(chrome == CHROME_NONE || chrome == CHROME_UNDERLINE) { "unknown chrome $chrome" }
        when (kind) {
            KIND_PAGE -> {
                require(notebookId == null) { "KIND_PAGE carries no notebookId" }
                requireId(pageId, "pageId")
            }
            KIND_NOTEBOOK -> {
                requireId(notebookId, "notebookId")
                require(pageId == null) { "KIND_NOTEBOOK carries no pageId" }
            }
            KIND_NOTEBOOK_PAGE -> {
                requireId(notebookId, "notebookId")
                requireId(pageId, "pageId")
            }
            else -> throw IllegalArgumentException("unknown destination kind $kind")
        }
        return "$VERSION$SEP$chrome$SEP$kind$SEP${notebookId.orEmpty()}$SEP${pageId.orEmpty()}"
    }

    /**
     * Read a payload back, or null when it is unusable for **any** reason — over the payload cap,
     * not exactly five parts, a version tag we do not know, an out-of-range chrome or kind, a
     * required id blank or over [MAX_ID_CHARS], or an id present that the kind forbids. Never
     * throws.
     */
    fun decode(payload: String): Decoded? {
        if (payload.length > MAX_PAYLOAD_CHARS) return null
        val parts = payload.split(SEP)
        if (parts.size != 5) return null
        if (parts[0] != VERSION) return null
        val chrome = parts[1].toIntOrNull() ?: return null
        if (chrome != CHROME_NONE && chrome != CHROME_UNDERLINE) return null
        val kind = parts[2].toIntOrNull() ?: return null
        val notebookId = parts[3].ifEmpty { null }
        val pageId = parts[4].ifEmpty { null }
        when (kind) {
            KIND_PAGE -> {
                if (notebookId != null) return null
                if (!validId(pageId)) return null
            }
            KIND_NOTEBOOK -> {
                if (pageId != null) return null
                if (!validId(notebookId)) return null
            }
            KIND_NOTEBOOK_PAGE -> {
                if (!validId(notebookId) || !validId(pageId)) return null
            }
            else -> return null
        }
        return Decoded(chrome, kind, notebookId, pageId)
    }

    /** The chrome a stored payload asks for — [CHROME_NONE] when the payload is unusable, so a
     *  foreign or future link still renders its content, just without chrome. */
    fun chromeOf(payload: String): Int = decode(payload)?.chrome ?: CHROME_NONE

    private fun validId(id: String?): Boolean =
        id != null && id.isNotBlank() && id.length <= MAX_ID_CHARS

    private fun requireId(id: String?, name: String) {
        require(!id.isNullOrBlank()) { "$name is blank" }
        require(id.length <= MAX_ID_CHARS) { "$name too long" }
        require(!id.contains(SEP)) { "$name contains the separator" }
    }
}
