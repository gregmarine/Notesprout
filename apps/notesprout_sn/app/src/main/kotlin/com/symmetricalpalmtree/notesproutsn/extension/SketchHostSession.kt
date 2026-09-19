package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The pure half of the host's `ISketchHost` stub (arc 43 / K4, made per-layer at arc 45 / G2) — the
 * **two read windows** (a page's graphite and ink rasters, each chunked for `readSketchChunk`), the
 * **ink window** (the bare strokes a "Bring in ink" staged, for `readInkChunk`) and the **two save
 * accumulators**, with no Android types precisely so it is JVM-testable ([DocumentHostSession]'s
 * recipe, which is the recipe this file follows line for line: the binder is an `android.os.Binder`
 * and cannot be constructed in a unit test, so everything the binder must get right beyond uid
 * gating lives here instead).
 *
 * One instance per showing, **one monitor for all four**: Binder calls arrive on arbitrary pooled
 * threads, and a save landing while the windows swap must see one state or the other, never a half
 * of each. One lock rather than one per layer because the atomicity that matters is across layers —
 * [setWindows] loads both together, and a reader that caught the new graphite beside the old ink
 * would be looking at two different pages.
 *
 * The rules it enforces (each pinned by test):
 *  - **Both read windows are loaded atomically with the state that describes them.** [setWindows]
 *    chunks each raster by the shared [ByteChunks] rule and answers both counts in one
 *    [Windows] — which is what a [SketchPageState] carries — so there is no call shape in which one
 *    layer is loaded and the other is not. [readChunk] refuses an index outside the layer's window.
 *    An empty raster is one empty chunk — the contract's "this page has no graphite / no ink".
 *  - **The ink window is the same shape for strokes.** [setInkWindow] parks what the host chunked
 *    ([InkChunks]) and answers the count; **zero chunks is legal** and is the only answer for a
 *    page with no bare ink, so [readInkChunk] on such a window refuses every index.
 *  - **A save names a page and a layer, and the accumulation belongs to both.** Unlike the
 *    document editor's, a sketch save is accepted for **any live page of the open notebook** — the
 *    sketch screen turns its own pages and a flush a moment after a turn still belongs to the page
 *    it was drawn on. So the key is taken from chunk 0 and every later chunk of that layer must
 *    repeat it; the read window's key does not enter into it, and a window swap mid-save leaves
 *    both accumulations exactly where they were. What the key still guarantees is that pixels can
 *    never land on a page nobody drew them on — which the hooks complete by refusing a key that is
 *    not a live page.
 *  - **The two accumulations are independent** (G2). A graphite stream and an ink stream may cross
 *    chunk-for-chunk without harm — separate key, index, parts and byte total per layer — and a
 *    refusal on one resets only that one, so a too-large ink push never throws away the graphite
 *    the face is halfway through sending. Two streams on the *same* layer may not interleave, which
 *    the extension's one push lock already guarantees (`SketchSaver`'s rule).
 *  - **Chunks arrive in order from 0, each within [SketchContract.SKETCH_CHUNK_BYTES], and each
 *    layer's running total within [SketchContract.MAX_BYTES]** — the untrusted-inward re-check of
 *    the cap the other side already applied (the `receiveInk` recipe). The byte cap is the seam's
 *    **one deliberate hard refusal** and carries the contract's exact string
 *    ([SketchContract.SKETCH_TOO_LARGE]) so the screen can say what happened and keep its pixels.
 *  - **An unknown layer is refused before anything else is looked at**, on every call that names
 *    one: a layer number this seam does not know came from somewhere, and nothing about the rest of
 *    the call is worth reading.
 *
 * **The header guard is not here.** Whether the committed bytes are a WebP of exactly this page's
 * size is a question about the page, and the page's size lives in the `.soil` — so it is asked one
 * layer down, where the write happens ([com.symmetricalpalmtree.notesproutsn.data.soil.SketchRepository],
 * which throws [SketchContract.SKETCH_BAD_IMAGE] and writes nothing). Putting a page size in here
 * would mean trusting whoever set it.
 *
 * **Pixels are never logged; nothing here logs at all** — the binder wrapping it logs counts,
 * layers and durations.
 */
class SketchHostSession {

    /** A committed save, handed to the binder's commit hook: the target's key, the raster it is of,
     *  and the whole image. **Empty bytes are the wire form for "clear this layer"**, not an
     *  error. */
    class Commit(val pageKey: String, val layer: Int, val bytes: ByteArray)

    /** What [setWindows] answers: the chunk count of each raster now parked, which is exactly the
     *  pair a [SketchPageState] carries. One return value rather than two calls, because the two
     *  windows are loaded together or the state describing them would be a lie. */
    class Windows(val graphiteChunks: Int, val inkChunks: Int)

    private val lock = Any()

    // ── The two read windows ──────
    private var windowKey: String? = null

    /** Chunks per layer, indexed by layer number ([SketchContract.LAYERS] is 0, 1). */
    private val windowChunks = Array(LAYER_COUNT) { emptyList<ByteArray>() }
    private val windowBytes = IntArray(LAYER_COUNT)

    // ── The ink window ──────
    private var inkChunks: List<List<WireStroke>> = emptyList()

    // ── The two save accumulators ──────
    private val saveKey = arrayOfNulls<String>(LAYER_COUNT)
    private val saveIndex = IntArray(LAYER_COUNT)
    private val saveParts = Array(LAYER_COUNT) { ArrayList<ByteArray>() }
    private val saveBytes = IntArray(LAYER_COUNT)

    /**
     * Load **both** read windows for [pageKey] — [graphite] and [ink], either of which may be empty
     * — and answer the chunk counts for the state that describes them.
     *
     * One call, because the contract says the reply is atomic with *both* images it describes: a
     * page turn that loaded one raster and then the other would leave a window on each of two pages
     * for as long as it took the second call to arrive. **The save accumulators are deliberately
     * untouched**: a window swap here is a page turn, and the pixels still in flight belong to the
     * page they named — see the class doc.
     */
    fun setWindows(pageKey: String, graphite: ByteArray, ink: ByteArray): Windows = synchronized(lock) {
        requireKey(pageKey)
        requireSize(graphite)
        requireSize(ink)
        windowKey = pageKey
        load(SketchContract.LAYER_GRAPHITE, graphite)
        load(SketchContract.LAYER_INK, ink)
        Windows(
            graphiteChunks = windowChunks[SketchContract.LAYER_GRAPHITE].size,
            inkChunks = windowChunks[SketchContract.LAYER_INK].size,
        )
    }

    /** The current windows' key, or null before the first [setWindows]. Both windows are always the
     *  same page's — that is what makes one key enough. */
    val currentKey: String? get() = synchronized(lock) { windowKey }

    /** The [layer] window's image length — what a [SketchPageState]'s `graphiteBytes` / `inkBytes`
     *  carries. */
    fun windowByteCount(layer: Int): Int = synchronized(lock) {
        requireLayer(layer)
        windowBytes[layer]
    }

    /** One chunk of the [layer] read window. Outside the window is the caller's error, never a
     *  blank; an unknown layer is refused before the index is even looked at. */
    fun readChunk(layer: Int, chunkIndex: Int): ByteArray = synchronized(lock) {
        requireLayer(layer)
        val chunks = windowChunks[layer]
        require(chunkIndex in chunks.indices) {
            "chunk $chunkIndex outside 0..${chunks.size - 1}"
        }
        chunks[chunkIndex]
    }

    /**
     * Park [chunks] as the ink window and answer how many [readInkChunk] calls serve them — the
     * host has already reduced the page's bare strokes to the wire and chunked them
     * ([InkChunks]). **Empty is legal**: a page with no bare ink answers 0 and the screen words
     * its own "no ink here" from the count.
     *
     * Named for the page's handwriting, not for [SketchContract.LAYER_INK] — the two words mean
     * different things on this seam and this one is older: this window carries *strokes* the person
     * wrote on the page, the ink raster carries *pixels* they drew on the sketch.
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
     * One inbound save chunk of the raster [layer] names. Returns the full [Commit] when [last]
     * closes a valid accumulation, null while more chunks of that layer are expected. Throws (and
     * resets **that layer only**) on any rule breach; the exception types are the marshalable set on
     * purpose — the binder rethrows them as-is, and the byte cap carries
     * [SketchContract.SKETCH_TOO_LARGE] verbatim because the screen matches it with `==`.
     */
    fun acceptChunk(
        pageKey: String,
        layer: Int,
        chunkIndex: Int,
        chunk: ByteArray,
        last: Boolean,
    ): Commit? = synchronized(lock) {
        requireLayer(layer)
        requireKey(pageKey)
        if (chunkIndex == 0) {
            // A fresh accumulation on this layer, for whatever page the screen names — the restart
            // after any refusal rides this same door. The other layer's stream is not disturbed.
            resetSaveLocked(layer)
            saveKey[layer] = pageKey
        }
        val expected = saveKey[layer]
        if (expected == null || pageKey != expected) {
            resetSaveLocked(layer)
            throw IllegalArgumentException("save target changed mid-save")
        }
        if (chunkIndex != saveIndex[layer]) {
            resetSaveLocked(layer)
            throw IllegalArgumentException("chunk $chunkIndex out of order (expected ${saveIndex[layer]})")
        }
        if (chunk.size > SketchContract.SKETCH_CHUNK_BYTES) {
            resetSaveLocked(layer)
            throw IllegalArgumentException("chunk over ${SketchContract.SKETCH_CHUNK_BYTES} bytes")
        }
        if (saveBytes[layer] + chunk.size > SketchContract.MAX_BYTES ||
            saveParts[layer].size + 1 > SketchContract.MAX_CHUNKS
        ) {
            // The one deliberate refusal on this seam — nothing is written, the stored row is what
            // it was, and the typed message is what lets the screen say so. Per layer, like the cap.
            resetSaveLocked(layer)
            throw IllegalStateException(SketchContract.SKETCH_TOO_LARGE)
        }
        saveParts[layer] += chunk
        saveBytes[layer] += chunk.size
        saveIndex[layer]++
        if (!last) return null
        val bytes = ByteChunks.join(saveParts[layer])
        resetSaveLocked(layer)
        Commit(pageKey, layer, bytes)
    }

    /** How many bytes of a save are accumulated on [layer] right now — the binder's log line,
     *  nothing more. */
    fun pendingSaveBytes(layer: Int): Int = synchronized(lock) {
        requireLayer(layer)
        saveBytes[layer]
    }

    /** Drop everything — both windows, the ink window and both accumulations: the showing is over
     *  ([ISketch.end] / the binder's revoke). */
    fun clear(): Unit = synchronized(lock) {
        windowKey = null
        for (layer in 0 until LAYER_COUNT) {
            windowChunks[layer] = emptyList()
            windowBytes[layer] = 0
            resetSaveLocked(layer)
        }
        inkChunks = emptyList()
    }

    /** Chunk one raster into its window. */
    private fun load(layer: Int, bytes: ByteArray) {
        windowBytes[layer] = bytes.size
        windowChunks[layer] = ByteChunks.chunk(bytes)
    }

    /** A failed or finished accumulation always resets whole, key included: the next chunk 0 on
     *  that layer is what names the page again. The other layer is never touched. */
    private fun resetSaveLocked(layer: Int) {
        saveKey[layer] = null
        saveIndex[layer] = 0
        saveParts[layer] = ArrayList()
        saveBytes[layer] = 0
    }

    /** The layer's the seam's, checked first on every call that names one — before the key, before
     *  the index, before anything: an unknown layer says the caller is not speaking this contract. */
    private fun requireLayer(layer: Int) {
        require(SketchContract.isLayer(layer)) { "Unknown layer $layer" }
    }

    /** A window's image is bounded by the same per-row cap a save is. */
    private fun requireSize(bytes: ByteArray) {
        require(bytes.size <= SketchContract.MAX_BYTES) {
            "raster over ${SketchContract.MAX_BYTES} bytes"
        }
    }

    /** The key's shape, checked wherever one arrives — a [SketchPageState] requires the same of
     *  the one it carries outward, and the two must agree. */
    private fun requireKey(pageKey: String) {
        require(pageKey.isNotEmpty() && pageKey.length <= SketchContract.MAX_PAGE_KEY_CHARS) {
            "pageKey length ${pageKey.length} outside 1..${SketchContract.MAX_PAGE_KEY_CHARS}"
        }
        require(' ' !in pageKey && '/' !in pageKey) { "pageKey carries a path character" }
    }

    private companion object {
        /** How many per-layer slots the arrays hold — [SketchContract.LAYERS]' size, read from the
         *  contract so a third raster would be a compile-time-visible change here rather than an
         *  index nobody updated. The layer numbers are 0 and 1 and are used as indices directly,
         *  which [requireLayer] is what makes safe. */
        val LAYER_COUNT = SketchContract.LAYERS.size
    }
}
