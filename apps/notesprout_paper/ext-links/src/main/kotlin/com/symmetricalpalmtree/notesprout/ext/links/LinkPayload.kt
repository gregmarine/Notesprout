package com.symmetricalpalmtree.notesprout.ext.links

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The link payload codec (arc 7 / L0) — the whole of what the core stores in a link row's `text`
 * column, and the whole of what the core does **not** understand: the row is opaque to it, written
 * by the picker and read back only here.
 *
 * Grammar (fixed at L0, wizard Q2): `"L1|<chrome>|<kind>|<notebookId>|<pageId>"` — a versioned
 * leading tag so a later arc can extend the format, `|` as the separator (it cannot occur in an id:
 * they are UUIDs), [chrome] `0|1` (`ExtensionContract.LINK_CHROME_*`), [kind] `0|1|2`
 * (`ExtensionContract.DEST_*`), and an empty slot for each id the kind does not carry:
 *
 * - `"L1|1|0||<pageId>"` — an underlined link to a page of the link's own notebook (`DEST_PAGE`
 *   carries **no** notebookId).
 * - `"L1|0|1|<notebookId>|"` — another notebook, no chrome (`DEST_NOTEBOOK`).
 * - `"L1|1|2|<notebookId>|<pageId>"` — a specific page of another notebook (`DEST_NOTEBOOK_PAGE`).
 *
 * Pure Kotlin — no Android, directly JVM-testable. [encode] throws on a caller bug (only our own
 * picker composes payloads); [decode] never throws: an unknown version is a *future* payload, and
 * `resolve` must answer null for it so the core shows its honest dead-link dialog rather than dying.
 */
object LinkPayload {

    /** The current version tag. A payload that starts with anything else decodes to null. */
    const val VERSION = "L1"

    private const val SEP = '|'

    /** A decoded payload; the id fields follow the same kind rules as `LinkDestination`. */
    data class Decoded(
        val chrome: Int,
        val kind: Int,
        val notebookId: String?,
        val pageId: String?,
    )

    /**
     * Compose a payload. Throws [IllegalArgumentException] for anything the picker should never
     * produce: an unknown chrome or kind, a missing or blank required id, an id carrying the
     * separator, an id over `MAX_LINK_ID_CHARS`, or an id the kind forbids.
     */
    fun encode(chrome: Int, kind: Int, notebookId: String?, pageId: String?): String {
        require(chrome == ExtensionContract.LINK_CHROME_NONE || chrome == ExtensionContract.LINK_CHROME_UNDERLINE) {
            "unknown chrome $chrome"
        }
        when (kind) {
            ExtensionContract.DEST_PAGE -> {
                require(notebookId == null) { "DEST_PAGE carries no notebookId" }
                requireId(pageId, "pageId")
            }
            ExtensionContract.DEST_NOTEBOOK -> {
                requireId(notebookId, "notebookId")
                require(pageId == null) { "DEST_NOTEBOOK carries no pageId" }
            }
            ExtensionContract.DEST_NOTEBOOK_PAGE -> {
                requireId(notebookId, "notebookId")
                requireId(pageId, "pageId")
            }
            else -> throw IllegalArgumentException("unknown destination kind $kind")
        }
        return "$VERSION$SEP$chrome$SEP$kind$SEP${notebookId.orEmpty()}$SEP${pageId.orEmpty()}"
    }

    /**
     * Read a payload back, or null when it is unusable for **any** reason — over the payload cap, not
     * exactly five parts, a version tag we do not know, an out-of-range chrome or kind, a required id
     * blank or over `MAX_LINK_ID_CHARS`, or an id present that the kind forbids. Never throws.
     */
    fun decode(payload: String): Decoded? {
        if (payload.length > ExtensionContract.MAX_LINK_PAYLOAD_CHARS) return null
        val parts = payload.split(SEP)
        if (parts.size != 5) return null
        if (parts[0] != VERSION) return null
        val chrome = parts[1].toIntOrNull() ?: return null
        if (chrome != ExtensionContract.LINK_CHROME_NONE && chrome != ExtensionContract.LINK_CHROME_UNDERLINE) return null
        val kind = parts[2].toIntOrNull() ?: return null
        val notebookId = parts[3].ifEmpty { null }
        val pageId = parts[4].ifEmpty { null }
        when (kind) {
            ExtensionContract.DEST_PAGE -> {
                if (notebookId != null) return null
                if (!validId(pageId)) return null
            }
            ExtensionContract.DEST_NOTEBOOK -> {
                if (pageId != null) return null
                if (!validId(notebookId)) return null
            }
            ExtensionContract.DEST_NOTEBOOK_PAGE -> {
                if (!validId(notebookId) || !validId(pageId)) return null
            }
            else -> return null
        }
        return Decoded(chrome, kind, notebookId, pageId)
    }

    private fun validId(id: String?): Boolean =
        id != null && id.isNotBlank() && id.length <= ExtensionContract.MAX_LINK_ID_CHARS

    private fun requireId(id: String?, name: String) {
        require(!id.isNullOrBlank()) { "$name is blank" }
        require(id.length <= ExtensionContract.MAX_LINK_ID_CHARS) { "$name too long" }
        require(!id.contains(SEP)) { "$name contains the separator" }
    }
}
