package com.symmetricalpalmtree.notesproutsn.extension

/**
 * A [TagIndex] ⇄ its stored bytes (arc 21 / W1, reshaped in W4) — pure, stdlib only, and the **wire
 * form as well as the storage form**: the extension writes exactly these bytes into its store under
 * `TagStore.KEY_INDEX`, and `ITagManager.snapshot` hands the host exactly the same blob. One
 * encoding, so a snapshot can never be a lossy view of what is stored.
 *
 * A line codec, not JSON: `:extension-api` carries **no serialization dependency** and never will
 * (the `UserWords` / `CaretMemory` precedent). UTF-8, `\n`-terminated lines, tab-separated fields:
 *
 * ```
 *   NSTAG2                        ← the version line; anything unknown is UNREADABLE, not empty
 *   T <id> <display>              ← one per tag, in the index's insertion order
 *   A <tagId> <notebook>          ← a notebook tag
 *   A <tagId> <notebook> <page>   ← a page tag
 * ```
 *
 * **No kind field, and that is the point** (W4). A page assignment is one that names a page; a
 * notebook assignment is one that does not. Storing a kind alongside would be a second copy of the
 * answer that could disagree with the question — the same reason W1 declined to store a tag's
 * identity key, which is likewise a pure function of what *is* stored.
 *
 * **Ids are written compacted** ([CompactId]): a UUID's 128 bits as 22 base64url characters rather
 * than 36. Every id in this family is a canonical UUID (`SafeImportId` enforces it even out of a
 * stranger's file), and the index pays for two of them fifty thousand times — at 36 characters
 * apiece the worst legal index no longer fits one store value. In memory ids stay UUIDs; the
 * compact form exists between these two functions and nowhere else.
 *
 * **The identity key is not stored** either, for the same reason, and both omissions together are
 * what make [WORST_CASE_BYTES] fit.
 *
 * **Tabs and newlines are dropped, not escaped** — the `UserWords` rule. Nothing that has been
 * through [TagRules.display] can contain either (whitespace runs collapse to a single space), so an
 * escape layer here would be unreachable code pretending to be a guarantee; a record that somehow
 * carries one is skipped, which costs a re-add, where a broken line would silently become two wrong
 * tags.
 *
 * **Failure has two different meanings and they are not interchangeable.** An absent or empty value
 * is a **first run** — [TagIndex.EMPTY], write freely. A value whose version line is not a version
 * this build knows is **unreadable**, and [decode] throws: the caller must say so and must not save
 * an empty index over it (the arc-11 blob rule — losing a library's tags to a blank overwrite
 * is not a failure anyone can undo). A **truncated tail** is neither: the last line, if the blob does
 * not end in a newline, is dropped and everything before it is kept.
 */
object TagCodec {

    /** The version line written today. */
    const val VERSION: String = "NSTAG2"

    /**
     * W1's version, still **read** and never written (arc 21 / W4).
     *
     * Its assignment record was `A <tagId> <kind> <targetId>`, which for a notebook tag already
     * carried exactly what W4 wants — the notebook's id — so those migrate whole. Its **page** tags
     * do not: they named a page and nothing else, and the notebook that page belongs to is not
     * recoverable from anywhere (the global index holds no pages, and finding the owner would mean
     * opening every `.soil` in the Garden). They are dropped, which is the honest reading of a
     * record that no longer says enough to be used.
     */
    const val VERSION_1: String = "NSTAG1"

    /** Longest an id [decode] will honour. Ids are minted as base-36 counters, so within
     *  [ExtensionContract.MAX_TAGS] they are at most 3 characters; 4 is the slack the worst-case
     *  arithmetic pays for, and it still addresses 1.6 M tags. */
    const val MAX_TAG_ID_CHARS: Int = 4

    private const val TAG_RECORD = "T"
    private const val ASSIGNMENT_RECORD = "A"
    private const val SEP = '\t'

    /**
     * The largest number of bytes a **legal** index can encode to — the proof that one store value
     * is enough, checked by a test that fails if any cap moves.
     *
     * Per record, worst case:
     *  - a tag: `T` + tab + id (≤ [MAX_TAG_ID_CHARS]) + tab + display + `\n`. A display is
     *    [ExtensionContract.MAX_TAG_CHARS] **UTF-16 units**, and the most UTF-8 bytes one of those
     *    can cost is 3 (a 4-byte code point is a surrogate *pair*, so it costs 2 units to buy those
     *    4 bytes — cheaper per unit, not dearer);
     *  - an assignment, at its largest a page tag: `A` + tab + tagId + tab + notebook
     *    ([CompactId.CHARS] ASCII) + tab + page (the same) + `\n`.
     */
    const val WORST_CASE_BYTES: Int =
        VERSION.length + 1 +
            ExtensionContract.MAX_TAGS * (1 + 1 + MAX_TAG_ID_CHARS + 1 + ExtensionContract.MAX_TAG_CHARS * 3 + 1) +
            ExtensionContract.MAX_TAG_ASSIGNMENTS *
            (1 + 1 + MAX_TAG_ID_CHARS + 1 + CompactId.CHARS + 1 + CompactId.CHARS + 1)

    fun encode(index: TagIndex): ByteArray {
        val sb = StringBuilder(64 + index.tags.size * 48 + index.assignments.size * 40)
        sb.append(VERSION).append('\n')
        for (t in index.tags) {
            if (!writable(t.id) || !writable(t.display)) continue
            sb.append(TAG_RECORD).append(SEP).append(t.id).append(SEP).append(t.display).append('\n')
        }
        for (a in index.assignments) {
            if (!writable(a.tagId)) continue
            // An id that will not compact is one this family did not mint. Dropping the record is
            // the same choice made for a field carrying a tab: the alternative is writing a form
            // the size arithmetic above does not cover.
            val notebook = CompactId.compact(a.notebookId) ?: continue
            val page = if (a.pageId == null) null else (CompactId.compact(a.pageId) ?: continue)
            sb.append(ASSIGNMENT_RECORD).append(SEP).append(a.tagId).append(SEP).append(notebook)
            if (page != null) sb.append(SEP).append(page)
            sb.append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The index [bytes] hold. `null` / empty → [TagIndex.EMPTY] (first run).
     *
     * @throws IllegalArgumentException the version line is not one this build reads — the value is
     * unreadable, and the caller must not write over it.
     */
    fun decode(bytes: ByteArray?): TagIndex {
        if (bytes == null || bytes.isEmpty()) return TagIndex.EMPTY
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n')
        val version = lines.firstOrNull()
        require(version == VERSION || version == VERSION_1) { "unknown tag index version" }
        val legacy = version == VERSION_1
        // A blob that does not end in a newline ends mid-record: `split` leaves that partial line
        // last, and it is the one thing here that is dropped for being incomplete rather than wrong.
        val last = lines.size - 1
        val end = if (text.endsWith('\n')) last else last - 1
        val tags = ArrayList<TagIndex.Tag>()
        val assignments = ArrayList<TagIndex.Assignment>()
        for (i in 1..end) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val f = line.split(SEP)
            when (f[0]) {
                TAG_RECORD -> if (f.size == 3) tags += TagIndex.Tag(f[1], f[2])
                ASSIGNMENT_RECORD ->
                    if (legacy) readLegacyAssignment(f)?.let { assignments += it }
                    else readAssignment(f)?.let { assignments += it }
                // Anything else is a record kind this version does not know. Skipped, not fatal:
                // the version line is what declares a format, and a stray line is not one.
            }
        }
        return TagIndex.of(tags, assignments)
    }

    /** `A <tagId> <notebook>` or `A <tagId> <notebook> <page>`, ids compacted. */
    private fun readAssignment(f: List<String>): TagIndex.Assignment? {
        if (f.size != 3 && f.size != 4) return null
        val notebook = CompactId.expand(f[2]) ?: return null
        if (f.size == 3) return TagIndex.Assignment(f[1], notebook, null)
        val page = CompactId.expand(f[3]) ?: return null
        return TagIndex.Assignment(f[1], notebook, page)
    }

    /**
     * W1's `A <tagId> <kind> <targetId>`, with plain UUIDs.
     *
     * A notebook assignment's target *was* its notebook, so it migrates exactly. A page assignment
     * is dropped — see [VERSION_1].
     */
    private fun readLegacyAssignment(f: List<String>): TagIndex.Assignment? {
        if (f.size != 4) return null
        if (f[2].toIntOrNull() != TagShowing.TARGET_NOTEBOOK) return null
        return TagIndex.Assignment(f[1], f[3], null)
    }

    /** A field that would break the line structure is dropped with its record ([UserWords]' rule). */
    private fun writable(field: String): Boolean =
        field.isNotEmpty() && SEP !in field && '\n' !in field
}
