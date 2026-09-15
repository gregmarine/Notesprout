package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.BibleNote
import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IBible
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.PassageText
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference
import com.symmetricalpalmtree.notesproutsn.extension.TagRules

/**
 * The BIBLE point (arc 37 / B0) — SN's NINTH capability point and the fifth screen-owning one.
 * The tag manager's showing bracket and nothing more, minus `configureShowing`: the host
 * pre-opens the store, holds the bind, calls `begin(store)`, launches [BibleActivity] for a
 * result, and calls `end()` when the screen has returned.
 *
 * **Scripture never crosses this seam.** The extension reads whatever text it shows from its own
 * assets or its own bundled data — never from the host, never over this Binder. The store carries
 * only the reader's one row of per-device state (the last-read position), and [BibleStore] never
 * logs its value.
 *
 * **Logs are structure only**: `begin` / `end`, never the position, never a scripture reference.
 *
 * **Arc 38 / R1** appended two compatible tails behind the method floor 12: [resolve] (bind-per-
 * call, no store — the notebook's reference dialog asks it whether the words are a reference this
 * Bible knows) and [beginAt] (the showing bracket opened on a passage). `REFERENCE_PLAN.md`.
 *
 * **B9 "Send"** appended a third behind the method floor 13: [takeOutgoingReference] — the
 * reference the screen's Send to notebook parked in [BibleSession], read once by the host on the
 * held bind after the screen returned `RESULT_BIBLE_SEND`.
 *
 * **Arc 42 "Notes"** appended seven behind the method floor 16: six store-taking, bind-per-call
 * pushes into the notes index (`note_ref` — the tag manager's `assign` shape: the store rides
 * the call, a [BibleStore] is built per call and the schema declared on that binder) and
 * [takeOutgoingNote] on the held bind. A notebook name is user content and a wire is where the
 * user has read: **neither is ever logged** — ids' counts, row counts and durations only.
 */
class BibleService : Service() {

    private val binder = object : IBible.Stub() {

        override fun begin(store: IExtensionStore?) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            requireNotNull(store) { "store is null" }
            synchronized(BibleSession) {
                BibleSession.clear()
                BibleSession.store = store
            }
            Slog.d(TAG) { "begin" }
        }

        override fun end() {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            synchronized(BibleSession) { BibleSession.clear() }
            Slog.d(TAG) { "end" }
        }

        /**
         * Arc 38 / R1 — bind-per-call, no store: the user's words → the canonical reference, or
         * null. Parsing is pure ([ReferenceParser]); the bounds check opens the installed source
         * on this Binder thread ([ReferenceResolver] over `book.chapter_count` and the `verse`
         * table), which on a first-ever run includes the asset copy — the host's call budget
         * allows for it. **The text is never logged**: a count of passages and a duration only.
         */
        override fun resolve(text: String?): ResolvedReference? {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val began = SystemClock.elapsedRealtime()
            val parsed = ReferenceParser.parseAll(text.orEmpty())
            if (parsed.isEmpty()) {
                Slog.d(TAG) { "resolve: not a reference (${SystemClock.elapsedRealtime() - began} ms)" }
                return null
            }
            var passages = parsed
            val ok = runCatching {
                openSource().let { db ->
                    val counts = db.books().associate { it.usfm to it.chapterCount }
                    val count = { usfm: String -> counts[usfm] ?: 0 }
                    passages = ReferenceResolver.normalize(parsed, count)
                    ReferenceResolver.valid(passages, count, db::verseExists)
                }
            }.getOrElse { e ->
                Log.w(TAG, "resolve: source unavailable", e)
                false
            }
            Slog.d(TAG) { "resolve: ${passages.size} passage(s) ${if (ok) "ok" else "refused"} in ${SystemClock.elapsedRealtime() - began} ms" }
            if (!ok) return null
            val wire = ReferenceCodec.encode(passages)
            return runCatching { ResolvedReference(wire, ReferenceCodec.label(passages)) }.getOrNull()
        }

        /** Arc 38 / R1 — `begin` with a reference: the screen opens on the passage view. */
        override fun beginAt(store: IExtensionStore?, reference: String?) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            requireNotNull(store) { "store is null" }
            require(ReferenceCodec.decode(reference) != null) { "reference is unreadable" }
            synchronized(BibleSession) {
                BibleSession.clear()
                BibleSession.store = store
                BibleSession.reference = reference
            }
            Slog.d(TAG) { "beginAt" }
        }

        /** B9 — once-only: the parked reference or null; the wire is never logged. */
        override fun takeOutgoingReference(): ResolvedReference? {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val taken = BibleSession.takeOutgoing()
            Slog.d(TAG) { "takeOutgoingReference: ${if (taken == null) "nothing" else "a reference"}" }
            return taken
        }

        /**
         * Arc 40 "Verses" — the verses [wire] names as Markdown ([PassageMarkdown]), or a
         * `STATUS_TOO_LONG` refusal for a whole chapter or more than [PassageMarkdown.MAX_VERSES],
         * or null for a wire this build cannot read or a source it cannot open. Opens the source
         * for the call, like `resolve`. **Neither the wire nor the text is logged**: a status, a
         * count and a duration.
         */
        override fun passageText(wire: String?): PassageText? {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val began = SystemClock.elapsedRealtime()
            val passages = ReferenceCodec.decode(wire)
            if (passages == null) {
                Slog.d(TAG) { "passageText: unreadable wire" }
                return null
            }
            if (!PassageMarkdown.withinCap(passages)) {
                Slog.d(TAG) { "passageText: over the cap (${SystemClock.elapsedRealtime() - began} ms)" }
                return PassageText.tooLong()
            }
            val text = runCatching {
                openSource().let { db ->
                    val verses = ArrayList<VerseRow>()
                    for (passage in passages) {
                        for (range in passage.ranges) verses.addAll(db.versesForRange(range.startKey, range.endKey))
                    }
                    // A chapter-crossing range is counted here, by its rows — the one count the
                    // reference alone could not make.
                    if (!PassageMarkdown.rowsWithinCap(verses)) return PassageText.tooLong()
                    PassageMarkdown.build(ReferenceCodec.label(passages), verses)
                }
            }.getOrElse { e ->
                Log.w(TAG, "passageText: source unavailable", e)
                return null
            }
            Slog.d(TAG) { "passageText: ${text.length} chars in ${SystemClock.elapsedRealtime() - began} ms" }
            return runCatching { PassageText(PassageText.STATUS_OK, text) }.getOrNull()
        }

        // ── Arc 42 "Notes" ───────────────────────────────────────────────────────────────

        override fun replacePageNotes(
            store: IExtensionStore?, notebookId: String?, notebookName: String?,
            pageId: String?, pageNumber: Int, notes: MutableList<BibleNote>?,
        ) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val began = SystemClock.elapsedRealtime()
            val nb = requireId(notebookId, "notebookId")
            val page = requireId(pageId, "pageId")
            val name = requireName(notebookName)
            val list = requireNotes(notes)
            require(list.all { it.pageId == page }) { "a note names another page" }
            require(pageNumber >= 1) { "pageNumber is not an ordinal" }
            val readable = withStore(store) { it.replacePageNotes(nb, name, page, pageNumber, list) }
            Slog.d(TAG) { "replacePageNotes: ${list.size} note(s), $readable readable, in ${SystemClock.elapsedRealtime() - began} ms" }
        }

        override fun replaceNotebookNotes(
            store: IExtensionStore?, notebookId: String?, notebookName: String?,
            livePageIds: MutableList<String>?, notes: MutableList<BibleNote>?,
        ) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val began = SystemClock.elapsedRealtime()
            val nb = requireId(notebookId, "notebookId")
            val name = requireName(notebookName)
            val pages = requireIds(livePageIds)
            val list = requireNotes(notes)
            val readable = withStore(store) { it.replaceNotebookNotes(nb, name, pages, list) }
            Slog.d(TAG) { "replaceNotebookNotes: ${pages.size} page(s), ${list.size} note(s), $readable readable, in ${SystemClock.elapsedRealtime() - began} ms" }
        }

        override fun renameNotebookNotes(store: IExtensionStore?, notebookId: String?, notebookName: String?) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val nb = requireId(notebookId, "notebookId")
            val name = requireName(notebookName)
            withStore(store) { it.renameNotes(nb, name) }
            Slog.d(TAG) { "renameNotebookNotes" }
        }

        override fun deleteNotebookNotes(store: IExtensionStore?, notebookId: String?) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val nb = requireId(notebookId, "notebookId")
            withStore(store) { it.deleteNotes(nb) }
            Slog.d(TAG) { "deleteNotebookNotes" }
        }

        override fun pruneNotes(store: IExtensionStore?, aliveNotebookIds: MutableList<String>?) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val alive = requireIds(aliveNotebookIds).toSet()
            val dropped = withStore(store) { it.pruneNotes(alive) }
            Slog.d(TAG) { "pruneNotes: ${alive.size} alive, $dropped dropped" }
        }

        override fun noteDocumentReference(
            store: IExtensionStore?, notebookId: String?, notebookName: String?,
            pageId: String?, pageNumber: Int, wire: String?,
        ) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val nb = requireId(notebookId, "notebookId")
            val name = requireName(notebookName)
            val page = pageId.orEmpty()
            require(page.isEmpty() || TagRules.isId(page)) { "pageId is not an id" }
            require(if (page.isEmpty()) pageNumber == 0 else pageNumber >= 1) { "pageNumber does not match the page" }
            require(ResolvedReference.isWire(wire.orEmpty())) { "wire is not a reference" }
            val written = withStore(store) {
                it.noteDocument(nb, name, page, pageNumber, wire!!, System.currentTimeMillis())
            }
            Slog.d(TAG) { "noteDocumentReference: ${if (written) "written" else "unreadable wire"}" }
        }

        /** Once-only: the parked note target or null. */
        override fun takeOutgoingNote(): BibleNoteTarget? {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            val taken = BibleSession.takeOutgoingNote()
            Slog.d(TAG) { "takeOutgoingNote: ${if (taken == null) "nothing" else "a target"}" }
            return taken
        }

        private fun requireId(id: String?, what: String): String {
            require(id != null && TagRules.isId(id)) { "$what is not an id" }
            return id
        }

        private fun requireName(name: String?): String {
            require(name != null && name.length <= ExtensionContract.BIBLE_NOTE_MAX_NAME_CHARS) { "name is too long" }
            return name
        }

        private fun requireIds(ids: List<String>?): List<String> {
            val list = ids.orEmpty()
            require(list.size <= ExtensionContract.BIBLE_NOTE_IDS_PER_CALL) { "too many ids" }
            require(list.all { TagRules.isId(it) }) { "an id is not an id" }
            return list
        }

        private fun requireNotes(notes: List<BibleNote>?): List<BibleNote> {
            val list = notes.orEmpty()
            require(list.size <= ExtensionContract.BIBLE_NOTES_PER_CALL) { "too many notes" }
            return list
        }

        /** The tag manager's rule: any store failure is `IllegalStateException("store unavailable")`. */
        private fun <T> withStore(store: IExtensionStore?, block: (BibleStore) -> T): T {
            requireNotNull(store) { "store is null" }
            return try {
                block(BibleStore(store))
            } catch (e: StoreUnavailable) {
                Log.w(TAG, "notes: store unavailable", e)
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
        }
    }

    /** The source, opened on the first call that needs it and closed in [onDestroy]. */
    private var source: BibleDatabase? = null

    /**
     * The installed source, opened once for the life of this service — the host's `resolve` and
     * `passageText` land back to back on one held bind (the reference dialog's "Insert the
     * verses"), and the install check plus a fresh open per call was the larger half of each.
     * Blocking — a Binder thread, never Main.
     */
    private fun openSource(): BibleDatabase = synchronized(this) {
        source?.let { return it }
        val file = ContentInstaller(this).ensureInstalled(ContentInstaller.BSB_ASSET, ContentInstaller.BSB_NAME)
        BibleDatabase.open(file.absolutePath).also { source = it }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(this) { source?.close(); source = null }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BibleService"

        /** Compared verbatim by the host, the tag manager's spelling. */
        const val STORE_UNAVAILABLE = "store unavailable"
    }
}
