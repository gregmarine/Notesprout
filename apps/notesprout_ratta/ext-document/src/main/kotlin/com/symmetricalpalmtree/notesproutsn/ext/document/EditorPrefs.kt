package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * The editor's small per-device state — the text size, the caret memory, and since M10 the
 * proofread toggle and the user dictionary — over the host's extension store (arc 19 / M5).
 *
 * **Arc 22 / X1 — an "unavailable" stub.** The host's store became real SQLite tables
 * (`IExtensionStore` v6) and the key/value methods this object was written over are gone. Until X4
 * declares the `prefs` / `word` / `caret` schema and rewrites these over statements, every read
 * answers its default and every write is a silent no-op — exactly the "store unavailable" branch
 * each of them already had — and the editor service still declares API version 2, so a version-6
 * host does not list it at all (the floor rule): nothing reaches this code from a live device.
 *
 * What stays is the part that never touched the store: the size ladder and the two constants the
 * screen lays itself out with. TODO(X4): schema v1 + statements; delete `CaretMemory`'s codec and
 * `UserWords`' line codec (the normalization rule stays as a pure function).
 */
@Suppress("UNUSED_PARAMETER")
object EditorPrefs {

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

    // ── Text size ─────────────────────────────────────────────────────────────

    /** X1 stub: the default. */
    fun textSize(): Float = DEFAULT_TEXT_SIZE

    /** X1 stub: not remembered. */
    fun saveTextSize(sp: Float) = Unit

    // ── Proofread (arc 19 / M10) ──────────────────────────────────────────────

    /** X1 stub: on (the feature's default). */
    fun proofreadEnabled(): Boolean = true

    /** X1 stub: not remembered. */
    fun saveProofreadEnabled(on: Boolean) = Unit

    // ── The user dictionary (arc 19 / M10) ────────────────────────────────────

    /** X1 stub: empty. */
    fun userWords(): LinkedHashSet<String> = LinkedHashSet()

    /** X1 stub: the write does not land (the caller's in-memory mirror still honours the vouch). */
    fun addUserWord(word: String): Boolean = false

    /** X1 stub: nothing stored to remove. */
    fun removeUserWord(word: String) = Unit

    // ── Where the writer left off ─────────────────────────────────────────────

    /** X1 stub: the top. */
    fun caret(pageKey: String): Int = 0

    /** X1 stub: not remembered. */
    fun rememberCaret(pageKey: String, offset: Int) = Unit

    /** X1 stub: not remembered. */
    fun rememberCaretAsync(pageKey: String, offset: Int) = Unit
}
