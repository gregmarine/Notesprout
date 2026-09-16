package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The pure half of the host's `ISketchHost` stub (arc 43 / K4) — the **read window** (a page's PNG,
 * chunked for `readSketchChunk`), the **ink window** (the bare strokes a "Bring in ink" staged, for
 * `readInkChunk`) and the **save accumulator**, with no Android types precisely so it is
 * JVM-testable ([DocumentHostSession]'s recipe, which is the recipe this file follows line for
 * line: the binder is an `android.os.Binder` and cannot be constructed in a unit test, so
 * everything the binder must get right beyond uid gating lives here instead).
 *
 * One instance per showing, one monitor: Binder calls arrive on arbitrary pooled threads, and a
 * save landing while a window swaps must see one state or the other, never a half of each.
 *
 * The rules it enforces (each pinned by test):
 *  - **The read window is loaded atomically with the state that describes it.** [setWindow] chunks
 *    the PNG by the shared [ByteChunks] rule and answers the count a [SketchPageState] carries;
 *    [readChunk] refuses an index outside it. An empty PNG is one empty chunk — the contract's
 *    "this page has no sketch".
 *  - **The ink window is the same shape for strokes.** [setInkWindow] parks what the host chunked
 *    ([InkChunks]) and answers the count; **zero chunks is legal** and is the only answer for a
 *    page with no bare ink, so [readInkChunk] on such a window refuses every index.
 *  - **A save names a page, and the accumulation belongs to the page it named.** Unlike the
 *    document editor's, a sketch save is accepted for **any live page of the open notebook** — the
 *    sketch screen turns its own pages and a flush a moment after a turn still belongs to the page
 *    it was drawn on. So the key is taken from chunk 0 and every later chunk must repeat it; the
 *    read window's key does not enter into it, and a window swap mid-save leaves the accumulation
 *    exactly where it was. What the key still guarantees is that pixels can never land on a page
 *    nobody drew them on — which the hooks complete by refusing a key that is not a live page.
 *  - **Chunks arrive in order from 0, each within [SketchContract.SKETCH_CHUNK_BYTES], and the
 *    running total within [SketchContract.MAX_BYTES]** — the untrusted-inward re-check of the cap
 *    the other side already applied (the `receiveInk` recipe). Any refusal resets the whole
 *    accumulation; the extension restarts from chunk 0. The byte cap is the seam's **one
 *    deliberate hard refusal** and carries the contract's exact string
 *    ([SketchContract.SKETCH_TOO_LARGE]) so the screen can say what happened and keep its pixels.
 *
 * **The header guard is not here.** Whether the committed bytes are a PNG of exactly this page's
 * size is a question about the page, and the page's size lives in the `.soil` — so it is asked one
 * layer down, where the write happens ([com.symmetricalpalmtree.notesproutsn.data.soil.SketchRepository],
 * which throws [SketchContract.SKETCH_BAD_PNG] and writes nothing). Putting a page size in here
 * would mean trusting whoever set it.
 *
 * **Pixels are never logged; nothing here logs at all** — the binder wrapping it logs counts and
 * durations.
 */
class SketchHostSession {

    /** A committed save, handed to the binder's commit hook: the target's key and the whole PNG.
     *  **Empty bytes are the wire form for "clear this page"**, not an error. */
    class Commit(val pageKey: String, val png: ByteArray)

    private val lock = Any()

    // ── The read window ──────
    private var windowKey: String? = null
    private var windowChunks: List<ByteArray> = emptyList()
    private var windowBytes: Int = 0

    // ── The ink window ──────
    private var inkChunks: List<List<WireStroke>> = emptyList()

    // ── The save accumulator ──────
    private var saveKey: String? = null
    private var saveIndex = 0
    private var saveParts = ArrayList<ByteArray>()
    private var saveBytes = 0

    /**
     * Load the read window for [pageKey] with [png] and answer the chunk count for the state that
     * describes it. **The save accumulator is deliberately untouched**: a window swap here is a
     * page turn, and the pixels still in flight belong to the page they named — see the class doc.
     */
    fun setWindow(pageKey: String, png: ByteArray): Int = synchronized(lock) {
        requireKey(pageKey)
        require(png.size <= SketchContract.MAX_BYTES) { "sketch over ${SketchContract.MAX_BYTES} bytes" }
        windowKey = pageKey
        windowBytes = png.size
        windowChunks = ByteChunks.chunk(png)
        windowChunks.size
    }

    /** The current window's key, or null before the first [setWindow]. */
    val currentKey: String? get() = synchronized(lock) { windowKey }

    /** The window's PNG length — what a [SketchPageState]'s `sketchBytes` carries. */
    val windowByteCount: Int get() = synchronized(lock) { windowBytes }

    /** One chunk of the read window. Outside the window is the caller's error, never a blank. */
    fun readChunk(chunkIndex: Int): ByteArray = synchronized(lock) {
        require(chunkIndex in windowChunks.indices) {
            "chunk $chunkIndex outside 0..${windowChunks.size - 1}"
        }
        windowChunks[chunkIndex]
    }

    /**
     * Park [chunks] as the ink window and answer how many [readInkChunk] calls serve them — the
     * host has already reduced the page's bare strokes to the wire and chunked them
     * ([InkChunks]). **Empty is legal**: a page with no bare ink answers 0 and the screen words
     * its own "no ink here" from the count.
     */
    fun setInkWindow(chunks: List<List<WireStroke>>): Int = synchronized(lock) {
        inkChunks = chunks
        chunks.size
    }

    /** One chunk of the ink window; outside it — every index, when the window is empty — is
     *  refused rather than answered with an empty list, which would read as "done". */
    fun readInkChunk(chunkIndex: Int): List<WireStroke> = synchronized(lock) {
        require(chunkIndex in inkChunks.indices) {
            "ink chunk $chunkIndex outside 0..${inkChunks.size - 1}"
        }
        inkChunks[chunkIndex]
    }

    /**
     * One inbound save chunk. Returns the full [Commit] when [last] closes a valid accumulation,
     * null while more chunks are expected. Throws (and resets) on any rule breach; the exception
     * types are the marshalable set on purpose — the binder rethrows them as-is, and the byte cap
     * carries [SketchContract.SKETCH_TOO_LARGE] verbatim because the screen matches it with `==`.
     */
    fun acceptChunk(pageKey: String, chunkIndex: Int, chunk: ByteArray, last: Boolean): Commit? =
        synchronized(lock) {
            requireKey(pageKey)
            if (chunkIndex == 0) {
                // A fresh accumulation, for whatever page the screen names — the restart after any
                // refusal rides this same door.
                resetSaveLocked()
                saveKey = pageKey
            }
            val expected = saveKey
            if (expected == null || pageKey != expected) {
                resetSaveLocked()
                throw IllegalArgumentException("save target changed mid-save")
            }
            if (chunkIndex != saveIndex) {
                resetSaveLocked()
                throw IllegalArgumentException("chunk $chunkIndex out of order (expected $saveIndex)")
            }
            if (chunk.size > SketchContract.SKETCH_CHUNK_BYTES) {
                resetSaveLocked()
                throw IllegalArgumentException("chunk over ${SketchContract.SKETCH_CHUNK_BYTES} bytes")
            }
            if (saveBytes + chunk.size > SketchContract.MAX_BYTES ||
                saveParts.size + 1 > SketchContract.MAX_CHUNKS
            ) {
                // The one deliberate refusal on this seam — nothing is written, the stored row is
                // what it was, and the typed message is what lets the screen say so.
                resetSaveLocked()
                throw IllegalStateException(SketchContract.SKETCH_TOO_LARGE)
            }
            saveParts += chunk
            saveBytes += chunk.size
            saveIndex++
            if (!last) return null
            val png = ByteChunks.join(saveParts)
            resetSaveLocked()
            Commit(pageKey, png)
        }

    /** How many bytes of a save are accumulated right now — the binder's log line, nothing more. */
    val pendingSaveBytes: Int get() = synchronized(lock) { saveBytes }

    /** Drop everything — the showing is over ([ISketch.end] / the binder's revoke). */
    fun clear(): Unit = synchronized(lock) {
        windowKey = null
        windowChunks = emptyList()
        windowBytes = 0
        inkChunks = emptyList()
        resetSaveLocked()
    }

    /** A failed or finished accumulation always resets whole, key included: the next chunk 0 is
     *  what names the page again. */
    private fun resetSaveLocked() {
        saveKey = null
        saveIndex = 0
        saveParts = ArrayList()
        saveBytes = 0
    }

    /** The key's shape, checked wherever one arrives — a [SketchPageState] requires the same of
     *  the one it carries outward, and the two must agree. */
    private fun requireKey(pageKey: String) {
        require(pageKey.isNotEmpty() && pageKey.length <= SketchContract.MAX_PAGE_KEY_CHARS) {
            "pageKey length ${pageKey.length} outside 1..${SketchContract.MAX_PAGE_KEY_CHARS}"
        }
        require(' ' !in pageKey && '/' !in pageKey) { "pageKey carries a path character" }
    }
}
