package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IBible
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference

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
            val passages = ReferenceParser.parseAll(text.orEmpty())
            if (passages.isEmpty()) {
                Slog.d(TAG) { "resolve: not a reference (${SystemClock.elapsedRealtime() - began} ms)" }
                return null
            }
            val ok = runCatching {
                openSource().use { db ->
                    val counts = db.books().associate { it.usfm to it.chapterCount }
                    ReferenceResolver.valid(passages, { counts[it] ?: 0 }, db::verseExists)
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
    }

    /** The installed source, opened for one call and closed by the caller's `use`. Blocking. */
    private fun openSource(): BibleDatabase {
        val file = ContentInstaller(this).ensureInstalled(ContentInstaller.BSB_ASSET, ContentInstaller.BSB_NAME)
        return BibleDatabase.open(file.absolutePath)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "BibleService"
    }
}
