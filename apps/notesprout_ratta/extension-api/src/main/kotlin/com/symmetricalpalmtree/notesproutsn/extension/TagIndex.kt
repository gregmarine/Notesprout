package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The whole tag index as data (arc 21 / W1) — every tag the library knows and everything each one is
 * attached to. Pure, immutable, stdlib only, and **shared by both sides**: the extension edits it and
 * writes it back to its store, the host decodes a [TagCodec] snapshot of it to merge tags into
 * search. One model, so the two processes can never disagree about what a tag is.
 *
 * Immutable on purpose. Every edit returns a **new** index, which is what lets the screen show the
 * result of an edit and still hold the version it would fall back to if the write failed — the
 * store is the truth, and a half-applied in-memory mutation is how that stops being true.
 *
 * Two orders, both deliberate:
 *  - [tags] keeps **insertion order** — that is what was stored, and it is the order the codec
 *    round-trips ([UserWords]' rule: a re-add does not move a word).
 *  - [sortedTags] is the browse order — by display, case-insensitively — because a list you page
 *    through alphabetically is a list you can find something in.
 *
 * A tag id is short on purpose ([TagCodec] pays for it 50 000 times in a 4 MiB budget) and means
 * nothing outside this index: it is minted here, never shown, and never crosses into a `.soil`.
 */
class TagIndex private constructor(
    val tags: List<Tag>,
    val assignments: List<Assignment>,
) {

    /** One tag: the short internal id and the display form (the casing whoever entered it first used). */
    class Tag(val id: String, val display: String) {
        /** Re-derived, never stored — [TagRules] is the one definition of "the same tag". */
        val identityKey: String get() = TagRules.identityKey(display)
    }

    /**
     * One attachment: [tagId] on a notebook, or on one page **of** a notebook (arc 21 / W4).
     *
     * **Every assignment names a notebook.** A page tag names its page as well, and that is the only
     * difference between the two kinds — [pageId] present *is* what makes it a page tag, so no kind
     * flag is stored. A stored kind would be a second copy of the answer that could disagree with
     * the question, which is the same reason W1 declined to store a tag's identity key.
     *
     * The notebook is not decoration. Before W4 a page assignment carried a page id and nothing
     * else, and the library had no way on earth to say which notebook that page was in: the global
     * index holds folders and notebooks only, pages live inside each `.soil`, and opening every
     * `.soil` to look costs a key derivation apiece. A relationship has to be stored to be known.
     *
     * Assignments are a set — the same tag cannot land on the same target twice, and [assign]
     * enforces it.
     *
     * Both ids are canonical UUIDs ([CompactId.isId]); [TagCodec] stores them compacted and
     * [of] drops anything else.
     */
    class Assignment(val tagId: String, val notebookId: String, val pageId: String? = null) {

        /** [TagShowing.TARGET_NOTEBOOK] or [TARGET_PAGE][TagShowing.TARGET_PAGE] — derived, never
         *  stored. */
        val targetKind: Int
            get() = if (pageId == null) TagShowing.TARGET_NOTEBOOK else TagShowing.TARGET_PAGE

        /** The thing the tag hangs on: the page when there is one, else the notebook. */
        val targetId: String get() = pageId ?: notebookId

        /** True when this attaches to the given target — the one comparison every query makes. */
        fun isOn(notebookId: String, pageId: String?): Boolean =
            this.notebookId == notebookId && this.pageId == pageId
    }

    /** What [assign] did: the index to write, and the tag's canonical display text for the toast. */
    class Assigned(val index: TagIndex, val display: String, val tagId: String, val created: Boolean)

    /** How much of the library one tag reaches — the numbers the delete confirm names. */
    class Usage(val notebooks: Int, val pages: Int) {
        val total: Int get() = notebooks + pages
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    private val byId: Map<String, Tag> = tags.associateBy { it.id }
    private val byIdentity: Map<String, Tag> = HashMap<String, Tag>(tags.size * 2).also { m ->
        // First wins: a blob carrying two records that fold to one identity is a blob that was
        // written by something that did not use these rules. Keeping the first is the same
        // tie-break `assign` makes, so a decode-then-encode settles it once.
        for (t in tags) m.putIfAbsent(t.identityKey, t)
    }

    fun tag(id: String): Tag? = byId[id]

    /** The tag [text] names, or null — the "does this already exist?" question. */
    fun find(text: String): Tag? = byIdentity[TagRules.identityKey(text)]

    /** [tags] ordered for reading: by display, case-insensitively, ties broken by the stored form. */
    fun sortedTags(): List<Tag> = tags.sortedWith(
        compareBy({ it.identityKey }, { it.display }),
    )

    /** The tags attached to one target, in [sortedTags] order. [pageId] null asks about the
     *  notebook itself; a page id asks about that page. */
    fun tagsOf(notebookId: String, pageId: String? = null): List<Tag> {
        val ids = HashSet<String>()
        for (a in assignments) if (a.isOn(notebookId, pageId)) ids += a.tagId
        return sortedTags().filter { it.id in ids }
    }

    /** True when [tagId] is already on that target. */
    fun isAssigned(tagId: String, notebookId: String, pageId: String? = null): Boolean =
        assignments.any { it.tagId == tagId && it.isOn(notebookId, pageId) }

    /** Every target one tag reaches, in index order — what the host filters against alive rows. */
    fun targetsOf(tagId: String): List<Assignment> = assignments.filter { it.tagId == tagId }

    /** Every assignment inside one notebook — the notebook's own tag and every page tag in it.
     *  The host's search merge groups by this (arc 21 / W4). */
    fun assignmentsIn(notebookId: String): List<Assignment> =
        assignments.filter { it.notebookId == notebookId }

    /** The blast radius of deleting [tagId], counted by kind. */
    fun usageOf(tagId: String): Usage {
        var notebooks = 0
        var pages = 0
        for (a in assignments) {
            if (a.tagId != tagId) continue
            if (a.pageId == null) notebooks++ else pages++
        }
        return Usage(notebooks, pages)
    }

    /**
     * The tags [query] should offer, best first: an exact identity, then prefix matches, then
     * substring matches, each group in [sortedTags] order. A blank query offers everything.
     *
     * Deliberately **not** the host's `FuzzyRank`: that lives in `:app` and matching a name you are
     * *typing* is a different question from matching one you are *searching for* — here the answer
     * changes under the caret and its job is to stop you creating "reading list" twice.
     */
    fun suggest(query: String): List<Tag> {
        val key = TagRules.identityKey(query)
        val sorted = sortedTags()
        if (key.isEmpty()) return sorted
        val exact = ArrayList<Tag>(1)
        val prefix = ArrayList<Tag>()
        val infix = ArrayList<Tag>()
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

    // ── Edits (each returns a new index) ─────────────────────────────────────

    /**
     * Normalize [text], create the tag if the library has never seen it, and attach it to the
     * target — the whole of `assign` in one pure step.
     *
     * Idempotent: re-assigning an attached tag returns **this** index with the canonical display, so
     * a double tap on e-ink costs nothing. Creation keeps the **first** casing (the wizard's rule):
     * assigning "Reading List" to a library that already holds "reading list" attaches the existing
     * tag and answers with the existing spelling.
     *
     * @throws IllegalArgumentException [text] is not a valid tag ([TagRules.isValid]).
     * @throws IllegalStateException [ExtensionContract.TAG_INDEX_FULL] — a cap refused it; nothing changed.
     */
    fun assign(text: String, notebookId: String, pageId: String? = null): Assigned {
        require(TagRules.isValid(text)) { "not a tag" }
        // The one shape a target may take. Not taste: [TagCodec] stores ids compacted, and the
        // arithmetic that proves the worst legal index fits one store value assumes it can.
        require(CompactId.isId(notebookId)) { "notebook id is not a UUID" }
        require(pageId == null || CompactId.isId(pageId)) { "page id is not a UUID" }
        val existing = find(text)
        if (existing != null && isAssigned(existing.id, notebookId, pageId)) {
            return Assigned(this, existing.display, existing.id, created = false)
        }
        if (assignments.size >= ExtensionContract.MAX_TAG_ASSIGNMENTS) {
            throw IllegalStateException(ExtensionContract.TAG_INDEX_FULL)
        }
        if (existing != null) {
            val next = TagIndex(tags, assignments + Assignment(existing.id, notebookId, pageId))
            return Assigned(next, existing.display, existing.id, created = false)
        }
        if (tags.size >= ExtensionContract.MAX_TAGS) {
            throw IllegalStateException(ExtensionContract.TAG_INDEX_FULL)
        }
        val tag = Tag(mintId(), TagRules.display(text))
        val next = TagIndex(tags + tag, assignments + Assignment(tag.id, notebookId, pageId))
        return Assigned(next, tag.display, tag.id, created = true)
    }

    /**
     * Detach [tagId] from one target. **The tag itself stays** — the wizard's lifecycle call: a tag
     * persists until it is explicitly deleted, so removing its last assignment leaves it in the
     * suggestion list, ready to be used again.
     */
    fun unassign(tagId: String, notebookId: String, pageId: String? = null): TagIndex {
        val next = assignments.filterNot { it.tagId == tagId && it.isOn(notebookId, pageId) }
        return if (next.size == assignments.size) this else TagIndex(tags, next)
    }

    /** Delete a tag and every assignment of it — the only thing that removes a tag, and the one the
     *  screen guards with a confirm naming the count [usageOf] gives it. */
    fun deleteTag(tagId: String): TagIndex {
        if (byId[tagId] == null) return this
        return TagIndex(tags.filterNot { it.id == tagId }, assignments.filterNot { it.tagId == tagId })
    }

    /**
     * Drop every assignment whose target is not in [aliveNotebooks] / [alivePages]. Not used to
     * *store* anything in this arc — pruning the blob is a `BACKLOG.md` note, because a notebook the
     * user has not deleted can be absent from one snapshot for reasons that are not permanent. It is
     * the **query-time** filter: what the host shows must describe the library that exists.
     */
    fun filterAlive(aliveNotebooks: Set<String>, alivePages: Set<String>): TagIndex {
        // A page assignment must clear **both** gates: its notebook alive in the index, and the page
        // itself still in that notebook. Since W4 the first is answerable at all — before it, an
        // orphaned page tag was indistinguishable from a live one.
        val kept = assignments.filter {
            it.notebookId in aliveNotebooks && (it.pageId == null || it.pageId in alivePages)
        }
        return if (kept.size == assignments.size) this else TagIndex(tags, kept)
    }

    /** The smallest base-36 id no tag holds — short because [TagCodec] pays for it per assignment. */
    private fun mintId(): String {
        val used = HashSet<String>(tags.size * 2).also { s -> for (t in tags) s += t.id }
        var n = 0
        while (true) {
            val id = n.toString(36)
            if (id !in used) return id
            n++
        }
    }

    companion object {
        val EMPTY = TagIndex(emptyList(), emptyList())

        /**
         * Build an index from decoded records — the codec's door, and the only place a foreign
         * shape is made trustworthy. Records that cannot be honoured are **dropped, not thrown**
         * (the tail-tolerance rule): a tag with a blank or over-long display, a duplicate id, a
         * second record folding to an identity already taken, an assignment naming a tag that is
         * not there, an id that is not a UUID, a repeated assignment. Caps truncate.
         */
        fun of(tags: List<Tag>, assignments: List<Assignment>): TagIndex {
            val keptTags = ArrayList<Tag>(minOf(tags.size, ExtensionContract.MAX_TAGS))
            val ids = HashSet<String>()
            val identities = HashSet<String>()
            for (t in tags) {
                if (keptTags.size >= ExtensionContract.MAX_TAGS) break
                if (t.id.isEmpty() || t.id.length > TagCodec.MAX_TAG_ID_CHARS) continue
                if (!TagRules.isValid(t.display)) continue
                val display = TagRules.display(t.display)
                if (!ids.add(t.id)) continue
                if (!identities.add(TagRules.identityKey(display))) continue
                keptTags += if (display == t.display) t else Tag(t.id, display)
            }
            val keptAssignments = ArrayList<Assignment>(minOf(assignments.size, ExtensionContract.MAX_TAG_ASSIGNMENTS))
            val seen = HashSet<String>()
            for (a in assignments) {
                if (keptAssignments.size >= ExtensionContract.MAX_TAG_ASSIGNMENTS) break
                if (a.tagId !in ids) continue
                if (!CompactId.isId(a.notebookId)) continue
                if (a.pageId != null && !CompactId.isId(a.pageId)) continue
                if (!seen.add("${a.tagId}\u0000${a.notebookId}\u0000${a.pageId ?: ""}")) continue
                keptAssignments += a
            }
            return TagIndex(keptTags, keptAssignments)
        }
    }
}
