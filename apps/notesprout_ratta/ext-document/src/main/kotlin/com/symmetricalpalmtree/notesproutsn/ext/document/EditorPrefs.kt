package com.symmetricalpalmtree.notesproutsn.ext.document

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The editor's small per-device state — the text size and the caret memory — over the host's
 * extension store (arc 19 / M5).
 *
 * **The extension writes nothing to disk itself, ever.** This is the host's store, minted per bind
 * and revoked with the unbind, so everything here goes through the binder parked in
 * [EditorSession] — and the binder is **fetched per call**, never cached: the host can restart
 * underneath a live screen and lend a new one, and a held reference would be a binder that throws
 * on every call for the rest of the showing.
 *
 * **Every method blocks on Binder I/O — call only from `Dispatchers.IO` or another background
 * thread.** None of them may be called from Main.
 *
 * **Every exception means "store unavailable"** (the host's rule, `ScratchStore`'s idiom): a read
 * returns its default and a write is skipped, silently. This state is comfort, not content — losing
 * it costs a scroll and a tap, and no failure of it may ever surface as a problem the writer has to
 * deal with. Nothing here logs: the values are small, but the keys name pages.
 *
 * The key layout is a **persistence format**: renaming [KEY_TEXT_SIZE] or [KEY_CARETS] orphans what
 * is already stored, which is why both are pinned by a test.
 */
object EditorPrefs {

    /** Editing-surface text size in sp, as the float's `toString` in UTF-8. */
    const val KEY_TEXT_SIZE = "size"

    /** The caret LRU as [CaretMemory]'s line blob. */
    const val KEY_CARETS = "carets"

    /** What the editor opens at before anything has been chosen. */
    const val DEFAULT_TEXT_SIZE = 16f

    /**
     * Preview reads a little larger than the source it came from: source is monospace Markdown
     * where columns carry meaning, preview is prose meant to be read.
     */
    const val PREVIEW_BUMP = 2f

    /** The offered sizes, smallest first — the label to show and the sp it means. */
    val SIZES: List<Pair<Int, Float>> = listOf(
        R.string.text_size_small to 14f,
        R.string.text_size_medium to 16f,
        R.string.text_size_large to 18f,
        R.string.text_size_larger to 21f,
        R.string.text_size_largest to 25f,
    )

    /** Writes are read-modify-write, so two of them must not interleave into a lost entry. */
    private val caretLock = Any()

    /** For the fire-and-forget caret handover, which must land even after `finish()` has started —
     *  so it cannot ride the screen's lifecycle scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Text size ─────────────────────────────────────────────────────────────

    /** The stored size, or [DEFAULT_TEXT_SIZE]. **Blocking — never on Main.** */
    fun textSize(): Float {
        return try {
            val raw = (EditorSession.store ?: return DEFAULT_TEXT_SIZE).get(KEY_TEXT_SIZE)
                ?: return DEFAULT_TEXT_SIZE
            // A value from a future build with a wider range must not render the editor unusable.
            raw.toString(Charsets.UTF_8).trim().toFloat()
                .coerceIn(SIZES.first().second, SIZES.last().second)
        } catch (e: Exception) {
            DEFAULT_TEXT_SIZE
        }
    }

    /** Remember [sp] for next time. **Blocking — never on Main.** */
    fun saveTextSize(sp: Float) {
        try {
            val store = EditorSession.store ?: return
            store.put(KEY_TEXT_SIZE, sp.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Unavailable: the size still applies to this showing, it just will not outlive it.
        }
    }

    // ── Where the writer left off ─────────────────────────────────────────────

    /** The caret last seen on [pageKey]'s document, or 0 — the top — when we have never seen it.
     *  **Blocking — never on Main.** */
    fun caret(pageKey: String): Int {
        if (pageKey.isEmpty()) return 0
        return try {
            val store = EditorSession.store ?: return 0
            CaretMemory.decode(store.get(KEY_CARETS))[pageKey] ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** Record where the caret is on [pageKey]. **Blocking — never on Main.** */
    fun rememberCaret(pageKey: String, offset: Int) {
        if (pageKey.isEmpty()) return
        synchronized(caretLock) {
            try {
                val store = EditorSession.store ?: return
                val raw = store.get(KEY_CARETS)
                val encoded = CaretMemory.encode(
                    CaretMemory.record(CaretMemory.decode(raw), pageKey, offset),
                )
                // An unchanged map is not worth a write: every save hands the caret over, and most
                // of them hand over the one already stored.
                if (raw != null && raw.contentEquals(encoded)) return
                store.put(KEY_CARETS, encoded)
            } catch (e: Exception) {
                // Unavailable: the next open lands at the top, which is where it lands anyway when
                // nothing is known.
            }
        }
    }

    /** [rememberCaret] from Main — fire and forget, serialized behind the same lock. */
    fun rememberCaretAsync(pageKey: String, offset: Int) {
        if (pageKey.isEmpty()) return
        scope.launch { rememberCaret(pageKey, offset) }
    }
}
