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

    /** One attachment: [tagId] on ([targetKind], [targetId]). Assignments are a set — the pair
     *  cannot appear twice, and [assign] is what enforces that. */
    class Assignment(val tagId: String, val targetKind: Int, val targetId: String)

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

    /** The tags attached to one target, in [sortedTags] order. */
    fun tagsOf(targetKind: Int, targetId: String): List<Tag> {
        val ids = HashSet<String>()
        for (a in assignments) if (a.targetKind == targetKind && a.targetId == targetId) ids += a.tagId
        return sortedTags().filter { it.id in ids }
    }

    /** True when [tagId] is already on that target. */
    fun isAssigned(tagId: String, targetKind: Int, targetId: String): Boolean =
        assignments.any { it.tagId == tagId && it.targetKind == targetKind && it.targetId == targetId }

    /** Every target one tag reaches, in index order — what the host filters against alive rows. */
    fun targetsOf(tagId: String): List<Assignment> = assignments.filter { it.tagId == tagId }

    /** The blast radius of deleting [tagId], counted by kind. */
    fun usageOf(tagId: String): Usage {
        var notebooks = 0
        var pages = 0
        for (a in assignments) {
            if (a.tagId != tagId) continue
            if (a.targetKind == TagShowing.TARGET_NOTEBOOK) notebooks++ else pages++
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
    fun assign(text: String, targetKind: Int, targetId: String): Assigned {
        require(TagRules.isValid(text)) { "not a tag" }
        require(targetKind == TagShowing.TARGET_NOTEBOOK || targetKind == TagShowing.TARGET_PAGE) {
            "unknown target kind ($targetKind)"
        }
        require(targetId.isNotEmpty() && targetId.length <= ExtensionContract.MAX_TARGET_ID_CHARS) {
            "target id length ${targetId.length} outside 1..${ExtensionContract.MAX_TARGET_ID_CHARS}"
        }
        val existing = find(text)
        if (existing != null && isAssigned(existing.id, targetKind, targetId)) {
            return Assigned(this, existing.display, existing.id, created = false)
        }
        if (assignments.size >= ExtensionContract.MAX_TAG_ASSIGNMENTS) {
            throw IllegalStateException(ExtensionContract.TAG_INDEX_FULL)
        }
        if (existing != null) {
            val next = TagIndex(tags, assignments + Assignment(existing.id, targetKind, targetId))
            return Assigned(next, existing.display, existing.id, created = false)
        }
        if (tags.size >= ExtensionContract.MAX_TAGS) {
            throw IllegalStateException(ExtensionContract.TAG_INDEX_FULL)
        }
        val tag = Tag(mintId(), TagRules.display(text))
        val next = TagIndex(tags + tag, assignments + Assignment(tag.id, targetKind, targetId))
        return Assigned(next, tag.display, tag.id, created = true)
    }

    /**
     * Detach [tagId] from one target. **The tag itself stays** — the wizard's lifecycle call: a tag
     * persists until it is explicitly deleted, so removing its last assignment leaves it in the
     * suggestion list, ready to be used again.
     */
    fun unassign(tagId: String, targetKind: Int, targetId: String): TagIndex {
        val next = assignments.filterNot {
            it.tagId == tagId && it.targetKind == targetKind && it.targetId == targetId
        }
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
        val kept = assignments.filter {
            if (it.targetKind == TagShowing.TARGET_NOTEBOOK) it.targetId in aliveNotebooks
            else it.targetId in alivePages
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
         * not there, an unknown target kind, a repeated assignment. Caps truncate.
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
                if (a.targetKind != TagShowing.TARGET_NOTEBOOK && a.targetKind != TagShowing.TARGET_PAGE) continue
                if (a.targetId.isEmpty() || a.targetId.length > ExtensionContract.MAX_TARGET_ID_CHARS) continue
                if (!seen.add("${a.tagId}\u0000${a.targetKind}\u0000${a.targetId}")) continue
                keptAssignments += a
            }
            return TagIndex(keptTags, keptAssignments)
        }
    }
}
