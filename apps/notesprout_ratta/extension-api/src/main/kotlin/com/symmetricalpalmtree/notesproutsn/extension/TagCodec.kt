package com.symmetricalpalmtree.notesproutsn.extension

/**
 * A [TagIndex] ⇄ its stored bytes (arc 21 / W1) — pure, stdlib only, and the **wire form as well as
 * the storage form**: the extension writes exactly these bytes into its store under
 * `TagStore.KEY_INDEX`, and `ITagManager.snapshot` hands the host exactly the same blob. One
 * encoding, so a snapshot can never be a lossy view of what is stored.
 *
 * A line codec, not JSON: `:extension-api` carries **no serialization dependency** and never will
 * (the `UserWords` / `CaretMemory` precedent). UTF-8, `\n`-terminated lines, tab-separated fields:
 *
 * ```
 *   NSTAG1                       ← the version line; anything else is UNREADABLE, not empty
 *   T <id> <display>             ← one per tag, in the index's insertion order
 *   A <tagId> <kind> <targetId>  ← one per assignment
 * ```
 *
 * **The identity key is not stored.** It is a pure function of the display form ([TagRules]), so
 * writing it down would be a second copy of the answer that could disagree with the question — and
 * it is what makes the worst legal index fit the store's value cap (see [WORST_CASE_BYTES]).
 *
 * **Tabs and newlines are dropped, not escaped** — the `UserWords` rule. Nothing that has been
 * through [TagRules.display] can contain either (whitespace runs collapse to a single space), so an
 * escape layer here would be unreachable code pretending to be a guarantee; a record that somehow
 * carries one is skipped, which costs a re-add, where a broken line would silently become two wrong
 * tags.
 *
 * **Failure has two different meanings and they are not interchangeable.** An absent or empty value
 * is a **first run** — [TagIndex.EMPTY], write freely. A value whose version line is not [VERSION]
 * is **unreadable**, and [decode] throws: the caller must say so and must not save an empty index
 * over it (the `ScratchPageCodec` rule — losing a library's tags to a blank overwrite is not a
 * failure anyone can undo). A **truncated tail** is neither: the last line, if the blob does not end
 * in a newline, is dropped and everything before it is kept.
 */
object TagCodec {

    /** The version line. A blob that does not start with it is unreadable — never empty. */
    const val VERSION: String = "NSTAG1"

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
     *  - an assignment: `A` + tab + tagId + tab + one kind digit + tab + target id
     *    (≤ [ExtensionContract.MAX_TARGET_ID_CHARS], an ASCII UUID).
     */
    const val WORST_CASE_BYTES: Int =
        VERSION.length + 1 +
            ExtensionContract.MAX_TAGS * (1 + 1 + MAX_TAG_ID_CHARS + 1 + ExtensionContract.MAX_TAG_CHARS * 3 + 1) +
            ExtensionContract.MAX_TAG_ASSIGNMENTS *
            (1 + 1 + MAX_TAG_ID_CHARS + 1 + 1 + 1 + ExtensionContract.MAX_TARGET_ID_CHARS + 1)

    fun encode(index: TagIndex): ByteArray {
        val sb = StringBuilder(64 + index.tags.size * 48 + index.assignments.size * 48)
        sb.append(VERSION).append('\n')
        for (t in index.tags) {
            if (!writable(t.id) || !writable(t.display)) continue
            sb.append(TAG_RECORD).append(SEP).append(t.id).append(SEP).append(t.display).append('\n')
        }
        for (a in index.assignments) {
            if (!writable(a.tagId) || !writable(a.targetId)) continue
            sb.append(ASSIGNMENT_RECORD).append(SEP).append(a.tagId).append(SEP)
                .append(a.targetKind).append(SEP).append(a.targetId).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The index [bytes] hold. `null` / empty → [TagIndex.EMPTY] (first run).
     *
     * @throws IllegalArgumentException the version line is not [VERSION] — the value is unreadable,
     * and the caller must not write over it.
     */
    fun decode(bytes: ByteArray?): TagIndex {
        if (bytes == null || bytes.isEmpty()) return TagIndex.EMPTY
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n')
        require(lines.isNotEmpty() && lines[0] == VERSION) { "unknown tag index version" }
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
                ASSIGNMENT_RECORD -> if (f.size == 4) {
                    val kind = f[2].toIntOrNull() ?: continue
                    assignments += TagIndex.Assignment(f[1], kind, f[3])
                }
                // Anything else is a record kind this version does not know. Skipped, not fatal:
                // the version line is what declares a format, and a stray line is not one.
            }
        }
        return TagIndex.of(tags, assignments)
    }

    /** A field that would break the line structure is dropped with its record ([UserWords]' rule). */
    private fun writable(field: String): Boolean =
        field.isNotEmpty() && SEP !in field && '\n' !in field
}
