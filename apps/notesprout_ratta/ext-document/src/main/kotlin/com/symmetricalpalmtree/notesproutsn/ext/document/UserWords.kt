package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * The user dictionary as data — the words this writer has vouched for, with no Android in it
 * (arc 19 / M10).
 *
 * og keeps these in a global-index table; SN's editor is an extension, and an extension writes
 * nothing to disk itself — so the words live in the host's extension store, encrypted at rest like
 * everything there, under [EditorPrefs.KEY_USER_WORDS]. What is stored is the **normalized form**
 * (`SpellEngine.normalizeWord` — lowercase, plain apostrophe): the same form every ignore-set
 * membership check uses, so a stored word can never fail to vouch for a casing of itself.
 *
 * **The encoding is a persistence format**, spelled out rather than derived: one UTF-8 blob, one
 * word per `\n`-terminated line, oldest first — [CaretMemory]'s line-codec idiom, because this
 * module carries no serialization dependency and never will. Insertion order is kept (a re-add
 * does not move a word): it is og's `addedAt` ordering with the clock removed, and it is what the
 * manage list shows.
 *
 * The word is the identity and the payload, so removal is a hard drop — a removed word must stop
 * vouching for itself immediately, and there is nothing a tombstone could preserve (og's rule).
 * No entry cap: a hand-added vocabulary is bounded by the hand that adds it, and the store's value
 * cap is orders of magnitude past any real one. Decode degrades to an empty set — losing the list
 * costs re-adding a word, and these words are the writer's own vocabulary, so nothing here logs.
 */
object UserWords {

    /** The stored words, oldest first. `null`, empty or unreadable bytes → an empty set. */
    fun decode(bytes: ByteArray?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (bytes == null || bytes.isEmpty()) return out
        return try {
            for (line in bytes.toString(Charsets.UTF_8).split('\n')) {
                if (line.isEmpty()) continue
                out.add(line)
            }
            out
        } catch (e: Exception) {
            LinkedHashSet()
        }
    }

    /**
     * [words] as the stored blob, in iteration order.
     *
     * A word carrying a newline is dropped rather than escaped: nothing that has been through
     * `normalizeWord` can contain one, so this is defense against a future caller — and a dropped
     * word is one re-add, while a corrupted line would split into two wrong words.
     */
    fun encode(words: Set<String>): ByteArray {
        val sb = StringBuilder()
        for (word in words) {
            if (word.isEmpty() || word.contains('\n')) continue
            sb.append(word).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
