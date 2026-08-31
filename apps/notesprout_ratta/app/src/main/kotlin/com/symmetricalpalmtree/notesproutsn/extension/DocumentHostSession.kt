package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The pure half of the host's `IDocumentHost` stub (arc 19 / M3) — the read window, the save
 * accumulator and the parked draft watermark, **with no Android types precisely so it is
 * JVM-testable** (the `ExtensionStoreGate` recipe: the binder is an `android.os.Binder` and
 * cannot be constructed in a unit test; everything the binder must get right beyond uid gating
 * lives here instead).
 *
 * One instance per showing, one monitor: Binder calls arrive on arbitrary pooled threads, and
 * a save landing while a window swaps must see one state or the other, never a half of each.
 *
 * The rules it enforces (each pinned by test):
 *  - **The read window is loaded atomically with the state that describes it.** [setWindow]
 *    chunks the text by the shared [TextChunks] rule; [readChunk] refuses an index outside it.
 *  - **A save names the current target or is refused.** [acceptChunk]'s `pageKey` must equal
 *    the window's — the mode-routing guard made structural: notebook-document text can never
 *    land on a page row or vice versa, because the wrong key never accumulates a single chunk.
 *  - **Chunks arrive in order from 0, each within [DocumentContract.TEXT_CHUNK_CHARS], and the
 *    running total within [DocumentContract.MAX_DOCUMENT_CHARS]** — the untrusted-inward
 *    re-check of the cap the other side already applied (the receiveInk recipe). Any refusal
 *    resets the whole accumulation; the editor restarts from chunk 0.
 *  - **The watermark moves only through a drafted save, and only when one is parked.** The
 *    host parks it when it serves a seed/merge ([parkWatermark], M6/M7); a drafted commit with
 *    nothing parked is an `IllegalStateException` — an ordinary edit can never invent one.
 *
 * Document text is never logged; nothing here logs at all — the binder wrapping it logs
 * counts and durations.
 */
class DocumentHostSession {

    /** A committed save, handed to the binder's commit hook: the target's key, the full text,
     *  and the watermark to stamp (non-null only for a drafted save). */
    class Commit(val pageKey: String, val text: String, val draftWatermark: Long?)

    private val lock = Any()
    private var windowKey: String? = null
    private var windowChunks: List<String> = emptyList()

    private var saveIndex = 0
    private val saveParts = StringBuilder()
    private var parkedWatermark: Long? = null

    /**
     * Load the read window for [pageKey] with [text], atomically dropping any half-received
     * save (a window swap is a target swap — a stale accumulation must not commit onto it).
     * Returns the chunk count for the state answer.
     *
     * A parked watermark survives a **same-key** reload (a recreated editor re-`current()`s the
     * same target, and its drafted save is still owed the anchor) but is cleared by a
     * **different-key** one (a flip is a new target; the old page's unconsumed draft anchor must
     * never be stamped onto the new page's row). M6 made that split explicit — M3 cleared on
     * neither, which left a cross-target park reachable in theory.
     */
    fun setWindow(pageKey: String, text: String): Int = synchronized(lock) {
        require(pageKey.isNotEmpty() && pageKey.length <= DocumentContract.MAX_PAGE_KEY_CHARS) {
            "bad pageKey length"
        }
        require(text.length <= DocumentContract.MAX_DOCUMENT_CHARS) { "document too large" }
        if (windowKey != null && pageKey != windowKey) parkedWatermark = null
        windowKey = pageKey
        windowChunks = TextChunks.chunk(text)
        resetSaveLocked()
        windowChunks.size
    }

    /** The current window's key, or null before the first [setWindow]. */
    val currentKey: String? get() = synchronized(lock) { windowKey }

    /** One chunk of the read window. Outside the window is the caller's error, never a blank. */
    fun readChunk(chunkIndex: Int): String = synchronized(lock) {
        require(chunkIndex in windowChunks.indices) {
            "chunk $chunkIndex outside 0..${windowChunks.size - 1}"
        }
        windowChunks[chunkIndex]
    }

    /** Park the watermark a just-served seed/merge was built against (M6/M7); the next drafted
     *  commit consumes it. Serving a **different target's** window clears it — see [setWindow]. */
    fun parkWatermark(watermark: Long): Unit = synchronized(lock) {
        parkedWatermark = watermark
    }

    /**
     * One inbound save chunk. Returns the full [Commit] when [last] closes a valid
     * accumulation, null while more chunks are expected. Throws (and resets) on any rule
     * breach; the exception types are the marshalable set on purpose — the binder rethrows
     * them as-is.
     */
    fun acceptChunk(
        pageKey: String,
        chunkIndex: Int,
        chunk: String,
        last: Boolean,
        drafted: Boolean,
    ): Commit? = synchronized(lock) {
        val expected = windowKey
        if (expected == null || pageKey != expected) {
            resetSaveLocked()
            throw IllegalArgumentException("save target is not the current one")
        }
        if (chunkIndex != saveIndex) {
            resetSaveLocked()
            throw IllegalArgumentException("chunk $chunkIndex out of order (expected $saveIndex)")
        }
        if (chunk.length > DocumentContract.TEXT_CHUNK_CHARS) {
            resetSaveLocked()
            throw IllegalArgumentException("chunk over ${DocumentContract.TEXT_CHUNK_CHARS} chars")
        }
        if (saveParts.length + chunk.length > DocumentContract.MAX_DOCUMENT_CHARS) {
            resetSaveLocked()
            throw IllegalArgumentException("document over ${DocumentContract.MAX_DOCUMENT_CHARS} chars")
        }
        saveParts.append(chunk)
        saveIndex++
        if (!last) return null
        val watermark = if (drafted) {
            parkedWatermark ?: run {
                resetSaveLocked()
                // The typed message ([DocumentContract.NO_DRAFT_PENDING], matched with `==`): the
                // editor's honest recovery is to clear its draft-pending flag and retry the same
                // text as an ordinary save — the words land, only the provenance anchor is lost.
                throw IllegalStateException(DocumentContract.NO_DRAFT_PENDING)
            }
        } else null
        val text = saveParts.toString()
        resetSaveLocked()
        if (drafted) parkedWatermark = null   // consumed — reset keeps it for a retry of the same draft
        Commit(pageKey, text, watermark)
    }

    /** Drop everything — the showing is over ([IDocumentEditor.end] / revoke). */
    fun clear(): Unit = synchronized(lock) {
        windowKey = null
        windowChunks = emptyList()
        resetSaveLocked()
        parkedWatermark = null
    }

    /** A failed or finished accumulation always resets whole; the parked watermark survives a
     *  plain reset so a refused drafted save can be retried against the same served draft. */
    private fun resetSaveLocked() {
        saveIndex = 0
        saveParts.setLength(0)
    }
}
