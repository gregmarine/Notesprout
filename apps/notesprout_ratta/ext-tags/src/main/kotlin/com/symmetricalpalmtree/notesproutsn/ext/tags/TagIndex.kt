package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord
import com.symmetricalpalmtree.notesproutsn.extension.TagRecord
import com.symmetricalpalmtree.notesproutsn.extension.TagRules

/**
 * **The tag screen's in-memory model** (arc 21 / W1, moved here and shrunk to queries in arc 22 /
 * X3) — the library's tags and the assignments of the one notebook the showing is about, loaded once
 * and asked the same questions over and over.
 *
 * It used to live in `:extension-api` and be shared by both sides, because the host decoded the same
 * blob the extension wrote. There is no blob: the host asks the store for [TagRecord]s and
 * [AssignmentRecord]s and does its own ranking, so this model is the extension's alone and belongs
 * here. What is left is the **query half** — the edits went with it, because an edit is now a
 * statement and the store's transaction is what makes it true. Nothing here writes; the screen
 * writes through [TagStore] and then reloads.
 *
 * **The filter runs against this, never against the store.** The arc-21 lock stands: a keystroke
 * repaints a list from memory and never costs a call.
 *
 * Two orders, both deliberate:
 *  - [tags] keeps the order it was read in, which is already the browse order (the store's
 *    `ORDER BY identityKey, display`);
 *  - [sortedTags] re-applies that order anyway, so a caller that built an index by hand — every test
 *    does — gets the same answers as one that read it.
 */
class TagIndex(
    val tags: List<TagRecord>,
    val assignments: List<AssignmentRecord>,
) {

    private val byId: Map<String, TagRecord> = tags.associateBy { it.id }

    private val byIdentity: Map<String, TagRecord> = HashMap<String, TagRecord>(tags.size * 2).also { m ->
        // First wins. Two rows folding to one identity cannot happen — `tag.identityKey` is UNIQUE —
        // so this is a tie-break for a hand-built index, not a repair of a stored one.
        for (t in tags) m.putIfAbsent(t.identityKey, t)
    }

    fun tag(id: String): TagRecord? = byId[id]

    /** The tag [text] names, or null — the "does this already exist?" question. */
    fun find(text: String): TagRecord? = byIdentity[TagRules.identityKey(text)]

    /** [tags] ordered for reading: by identity, ties broken by the stored form. */
    fun sortedTags(): List<TagRecord> = tags.sortedWith(compareBy({ it.identityKey }, { it.display }))

    /** The tags attached to one target, in [sortedTags] order. [pageId] null asks about the
     *  notebook itself; a page id asks about that page. */
    fun tagsOf(notebookId: String, pageId: String? = null): List<TagRecord> {
        val ids = HashSet<String>()
        for (a in assignments) if (a.isOn(notebookId, pageId)) ids += a.tagId
        return sortedTags().filter { it.id in ids }
    }

    /** True when [tagId] is already on that target. */
    fun isAssigned(tagId: String, notebookId: String, pageId: String? = null): Boolean =
        assignments.any { it.tagId == tagId && it.isOn(notebookId, pageId) }

    /**
     * The tags [query] should offer, best first: an exact identity, then prefix matches, then
     * substring matches, each group in [sortedTags] order. A blank query offers everything.
     *
     * Deliberately **not** the host's `FuzzyRank`: that lives in `:app`, and matching a name you are
     * *typing* is a different question from matching one you are *searching for* — here the answer
     * changes under the caret and its job is to stop you creating "reading list" twice.
     */
    fun suggest(query: String): List<TagRecord> {
        val key = TagRules.identityKey(query)
        val sorted = sortedTags()
        if (key.isEmpty()) return sorted
        val exact = ArrayList<TagRecord>(1)
        val prefix = ArrayList<TagRecord>()
        val infix = ArrayList<TagRecord>()
        for (t in sorted) {
            val k = t.identityKey
            when {
                k == key -> exact += t
                k.startsWith(key) -> prefix += t
                k.contains(key) -> infix += t
            }
        }
        return exact + prefix + infix
    }

    // There is deliberately no `filterAlive` here. Staleness is answered **structurally**, not by a
    // filtering pass: the host's `SearchAssembly.rank` reads assignments *through* the index's own
    // live notebook listing, so an assignment naming a deleted notebook is never looked at, and
    // `PageNumbers` answers a page's aliveness the same way against the notebook's live page rows.
    // A function that filtered an index nobody filters would be a doc comment asserting a role it
    // does not have. Pruning the stored rows is a `BACKLOG.md` note and is now one `DELETE … WHERE
    // notebookId NOT IN (…)`.

    companion object {
        val EMPTY = TagIndex(emptyList(), emptyList())
    }
}
