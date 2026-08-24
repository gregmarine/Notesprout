package com.symmetricalpalmtree.notesproutsn.core

import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipHeader
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The in-memory mirror of the clipboard **header** (arc 7) — process-wide, so the long-press sheet
 * can decide whether a Paste row exists without a suspend call in the middle of building itself.
 *
 * The payload never lives here: [ClipStore.readEnvelope] reads the blob out of the index only when
 * a paste actually happens.
 *
 * **Rehydrated at notebook open, not at process start.** og warms its clipboard in `Application`;
 * SN cannot, because the global index is encrypted and only `BootstrapActivity` opens it — at
 * `Application.onCreate` there is nothing to read. The notebook screen is the only consumer of the
 * header and always runs after Bootstrap, so [ensureLoaded] in its open path covers every route in
 * (including the unlock route, which never passes through a warm Bootstrap).
 */
object SnClipboard {

    @Volatile
    var header: ClipHeader? = null
        private set

    @Volatile
    private var loaded = false

    private val mutex = Mutex()

    /** True when a whole page is on the clipboard — the sheet's Paste-row question. */
    val hasPage: Boolean get() = header?.kind == ClipEnvelope.KIND_PAGE

    /**
     * True when a lassoed set of objects is on the clipboard (arc 8) — the lasso popup's Paste-row
     * question, and what decides whether a bare-paper pen tap places anything.
     *
     * **Kind wins, one slot:** [hasPage] and this are mutually exclusive by construction, so a page
     * copy silently takes the objects' place and vice versa. That is why each surface offers only
     * its own kind: Paste leaves the page sheet while objects are held, and leaves the popup while a
     * page is.
     */
    val hasObjects: Boolean get() = header?.kind == ClipEnvelope.KIND_OBJECTS

    /**
     * Read the header once per process. Idempotent and cheap (one indexed, blob-free row read);
     * a failure leaves the clipboard reading as empty rather than taking the caller down.
     *
     * **A failed read does not latch** (B3 review): only a read that actually answered sets
     * [loaded], so one transient index error costs this open, not the whole process — otherwise a
     * clipboard sitting right there in the index would stay invisible until the app restarted.
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            runCatching { ClipStore().readHeader() }
                .onSuccess { header = it; loaded = true }
                .onFailure { Slog.d("SnClipboard") { "clipboard header read failed: $it" } }
        }
    }

    /** Publish what a copy/cut just wrote (or null — a payload that turned out to be unusable). */
    fun set(newHeader: ClipHeader?) {
        header = newHeader
        loaded = true
    }
}
