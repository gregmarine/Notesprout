package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference

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
 * [chrome] `0|1` ([CHROME_NONE]/[CHROME_UNDERLINE]), [kind] `0|1|2|3` ([KIND_PAGE]/[KIND_NOTEBOOK]/
 * [KIND_NOTEBOOK_PAGE]/[KIND_BIBLE]), and an empty slot for each id the kind does not carry:
 *
 * | kind | payload | carries |
 * |---|---|---|
 * | [KIND_PAGE] | `"L1|1|0||<pageId>"` | a page of the link's own notebook — **no** notebookId |
 * | [KIND_NOTEBOOK] | `"L1|0|1|<notebookId>|"` | another notebook — **no** pageId |
 * | [KIND_NOTEBOOK_PAGE] | `"L1|1|2|<notebookId>|<pageId>"` | a page of another notebook |
 * | [KIND_BIBLE] | `"L1|1|3|<wire>|"` | a passage of scripture — **no** pageId |
 *
 * **[KIND_BIBLE] (arc 38 / R3)** is the one kind whose target is not a row of ours: the notebookId
 * *slot* carries a resolved reference's opaque wire form (`JHN:3:14-3:18,PRO:3:5-3:6` — the
 * extension's `ReferenceCodec` is its only reader, and [ResolvedReference.isWire] is the whole of
 * what this codec checks). A **decoded** Bible payload reports `notebookId = null` and hands the
 * wire back as [Decoded.reference] instead, so nothing that re-points notebook ids
 * (`NotebookRemap`, `ObjectClip`, `PageClip`) can ever mistake a reference for one.
 *
 * Paper and og decode kind 3 as unusable — a dead link there, accepted (the arc-38 plan).
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

    /** A passage of scripture (arc 38 / R3) — the wire rides the notebookId slot, pageId empty.
     *  SN's own, with no Paper counterpart: the family reads it as an unusable payload. */
    const val KIND_BIBLE = 3

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
        /** The resolved reference's wire form when [kind] is [KIND_BIBLE], else null (arc 38 / R3).
         *  It travelled in the notebookId slot; it arrives here so that a reference is never a
         *  notebook id to anything downstream. Opaque — only the Bible extension reads it. */
        val reference: String? = null,
    )

    /**
     * Compose a payload. Throws [IllegalArgumentException] for anything our own flows should never
     * produce: an unknown chrome or kind, a missing or blank required id, an id carrying the
     * separator, an id over [MAX_ID_CHARS], or an id the kind forbids.
     *
     * [KIND_BIBLE] takes the reference wire in the [notebookId] slot and checks it with
     * [ResolvedReference.isWire] rather than the id rules — a wire is longer than an id, is not a
     * UUID, and carries its own grammar; what both rules share is that neither may hold a `|`.
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
            KIND_BIBLE -> {
                require(notebookId != null && ResolvedReference.isWire(notebookId)) {
                    "KIND_BIBLE carries a reference wire"
                }
                require(pageId == null) { "KIND_BIBLE carries no pageId" }
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
            KIND_BIBLE -> {
                if (pageId != null) return null
                if (notebookId == null || !ResolvedReference.isWire(notebookId)) return null
                // The wire travelled in the notebookId slot and stops there: a decoded Bible
                // payload has no notebook id, so nothing that re-points one can reach the wire.
                return Decoded(chrome, kind, notebookId = null, pageId = null, reference = notebookId)
            }
            else -> return null
        }
        return Decoded(chrome, kind, notebookId, pageId)
    }

    /** The reference wire a Bible payload names, or null for every other payload (arc 38 / R3) —
     *  the one predicate the notebook screen asks to tell a Bible link from any other. */
    fun referenceOf(payload: String): String? = decode(payload)?.reference

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
