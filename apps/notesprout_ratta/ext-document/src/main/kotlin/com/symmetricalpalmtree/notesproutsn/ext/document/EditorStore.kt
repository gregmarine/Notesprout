package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads

/**
 * The editor's per-device state as rows over the host's `IExtensionStore` (arc 19 / M5, rewritten
 * onto tables in arc 22 / X4) — the text size, the proofread toggle, the user dictionary and the
 * caret memory. **The one place in this module where SQL runs.**
 *
 * **Blocking Binder I/O — never Main.** Every call arrives on `Dispatchers.IO` or a Binder thread.
 *
 * Every public method applies [EditorSchema.V1] first. That is idempotent, and a matching version
 * costs one `SELECT` host-side — the price of never having to reason about whether some other path
 * declared first. It is not optional book-keeping: the host's gate refuses `exec` / `query` on a
 * binder that has not declared, and [EditorSession] hands out a **fresh binder per call**, so a host
 * that restarted under a live screen lends an undeclared one. The per-call apply is what makes that
 * invisible to the writer.
 *
 * **There is no lock here, and no read-modify-write left to need one.** Arc 19 held a `caretLock`
 * and a `wordsLock` because adding one word meant decoding a whole blob, changing it and writing it
 * back — two of those interleaving is how one silently erases the other. Every write below is a
 * single statement (or one batch of two, in one transaction) that is correct whoever else is
 * writing: **the transaction is the lock** (X3's rule), and it covers the case a process-local
 * monitor never could — two host processes, or a host that restarted mid-edit.
 *
 * This class lets every exception through; [EditorPrefs] is where "any failure means the default"
 * is decided, because that is a policy about *comfort state*, not about storage.
 *
 * Nothing here logs. The values are small, but a word is the writer's own vocabulary and a page key
 * names a page.
 */
class EditorStore(private val store: IExtensionStore) {

    // ── Text size ────────────────────────────────────────────────────────────

    /**
     * The stored editing-surface size in sp, or [EditorPrefs.DEFAULT_TEXT_SIZE] when there is none.
     * A value that will not parse — absent, garbage, NaN — is the default rather than a failure; a
     * value that parses but lies outside the offered ladder is **coerced**, so a size written by a
     * future build with a wider range cannot render this one's editor unusable.
     */
    fun textSize(): Float {
        val parsed = pref(DocumentContract.PREF_TEXT_SIZE)?.trim()?.toFloatOrNull()
            ?: return EditorPrefs.DEFAULT_TEXT_SIZE
        if (parsed.isNaN()) return EditorPrefs.DEFAULT_TEXT_SIZE
        return parsed.coerceIn(EditorPrefs.SIZES.first().second, EditorPrefs.SIZES.last().second)
    }

    /** Remember [sp]. Stored as the float's `toString` — the form `DocumentPdfMetrics.textSizeSp`
     *  parses when the host reads this table for an export. */
    fun saveTextSize(sp: Float) =
        write(EditorSql.upsertPref(DocumentContract.PREF_TEXT_SIZE, sp.toString()))

    // ── Proofread ────────────────────────────────────────────────────────────

    /** Whether proofread is on. **Absent means on** — the feature's default — and anything that is
     *  not exactly `"0"` reads as on, so a value this build did not write cannot silently remove a
     *  feature. */
    fun proofreadEnabled(): Boolean = pref(EditorSql.PREF_PROOFREAD)?.trim() != "0"

    fun saveProofreadEnabled(on: Boolean) =
        write(EditorSql.upsertPref(EditorSql.PREF_PROOFREAD, if (on) "1" else "0"))

    // ── The user dictionary ──────────────────────────────────────────────────

    /** Every vouched word, oldest first. Words are the normalized form
     *  (`SpellEngine.normalizeWord`, applied by the caller) — the same form every ignore-set
     *  membership check uses, so a stored word can never fail to vouch for a casing of itself. */
    fun userWords(): LinkedHashSet<String> {
        store.applySchema(EditorSchema.V1)
        val out = LinkedHashSet<String>()
        for (row in StoreReads.all(store, EditorSql.selectWords())) out += row.text("word")
        return out
    }

    /**
     * Vouch for [word] (already normalized); true when it is now in the dictionary — whether this
     * call put it there or it was already present. `INSERT OR IGNORE` reports "already there" as
     * `changes() == 0`, and a re-add is a success, not a failure: the writer asked for a state and
     * that state holds. Only an exception means it does not. An empty word touches nothing.
     */
    fun addUserWord(word: String): Boolean {
        if (word.isEmpty()) return false
        write(EditorSql.insertWord(word, System.currentTimeMillis()))
        return true
    }

    /** Take [word] back out — a hard drop, effective immediately. */
    fun removeUserWord(word: String) {
        if (word.isEmpty()) return
        write(EditorSql.deleteWord(word))
    }

    // ── Where the writer left off ────────────────────────────────────────────

    /** The caret last seen on [pageKey]'s document, or 0 — the top — when we have never seen it.
     *  A stored negative is clamped rather than trusted: it would be a `setSelection` crash. */
    fun caret(pageKey: String): Int {
        if (pageKey.isEmpty()) return 0
        store.applySchema(EditorSchema.V1)
        val row = StoreReads.all(store, EditorSql.selectCaret(pageKey)).rows.firstOrNull()
            ?: return 0
        return row.long("offset").coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Record where the caret is on [pageKey] — **one `exec` of exactly two statements**, the upsert
     * and the LRU trim, in one transaction, so the row just written is inside the window the trim
     * measures and the memory can never be left over its cap.
     *
     * Arc 19 read the whole map first and skipped the write when nothing had changed. That
     * optimization is gone with the blob it was for: it cost a read to save a write, and one upsert
     * is cheaper than a read plus a compare.
     */
    fun rememberCaret(pageKey: String, offset: Int) {
        if (pageKey.isEmpty()) return
        store.applySchema(EditorSchema.V1)
        val now = System.currentTimeMillis()
        // Clamped on the way in as well as out: [caret] never trusts a stored negative, and there
        // is no reason to store one (arc 19's encoder clamped here too).
        StoreReads.exec(
            store,
            listOf(EditorSql.upsertCaret(pageKey, offset.coerceAtLeast(0), now), EditorSql.trimCarets()),
        )
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** One `prefs` value, or null when the key has never been written. */
    private fun pref(key: String): String? {
        store.applySchema(EditorSchema.V1)
        val row = StoreReads.all(store, EditorSql.selectPref(key)).rows.firstOrNull() ?: return null
        return row.textOrNull(DocumentContract.PREFS_VALUE_COLUMN)
    }

    private fun write(statement: Statement) {
        store.applySchema(EditorSchema.V1)
        StoreReads.exec(store, statement)
    }
}
