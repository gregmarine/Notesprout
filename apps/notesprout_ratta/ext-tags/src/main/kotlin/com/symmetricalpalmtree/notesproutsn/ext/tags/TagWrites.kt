package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex

/**
 * The one read-modify-write of the tag index (arc 21 / W1), shared by both writers in this process:
 * the screen's edits ([TagsActivity]) and the service's call-shaped `assign`
 * ([TagManagerService]). One place, because the index is a **single store value** — two writers each
 * applying their change to the version they happened to be holding is how one of them silently
 * erases the other.
 *
 * The rules it encodes, in order:
 *  1. take [TagSession.writes] for the whole cycle;
 *  2. read the index **fresh** — never the one the caller is showing;
 *  3. run the caller's change, which may answer `null` for "nothing to do";
 *  4. write, and only then hand the new index back.
 *
 * Every failure is a typed [Reason] rather than an exception, because the two callers say them
 * differently: the service turns one into a marshalable `IllegalStateException` message the host
 * compares verbatim, the screen into a sentence in a dialog. Neither may guess from a string.
 *
 * **Blocking.** IO thread or Binder thread, never Main.
 */
object TagWrites {

    /** Why a cycle did not land. */
    enum class Reason {
        /** The store could not be reached — the binder was revoked, or the host is gone. */
        STORE_UNAVAILABLE,

        /** There **is** an index and it could not be decoded. Nothing was written, and nothing may
         *  be: a blank index saved over a library's tags is a loss nobody can undo. */
        INDEX_UNREADABLE,

        /** A cap refused it ([ExtensionContract.TAG_INDEX_FULL]). */
        INDEX_FULL,

        /** The text was not a tag ([com.symmetricalpalmtree.notesproutsn.extension.TagRules.isValid]). */
        NOT_A_TAG,

        /** The change was made and the write failed. */
        SAVE_FAILED,
    }

    sealed class Outcome {
        /** The index was changed and written. */
        class Written(val index: TagIndex) : Outcome()

        /** There was nothing to do — the freshly read index is handed back so the caller can adopt
         *  it anyway (someone else may have moved it since the caller last looked). */
        class Unchanged(val index: TagIndex) : Outcome()

        class Failed(val reason: Reason) : Outcome()
    }

    fun apply(store: TagStore, transform: (TagIndex) -> TagIndex?): Outcome =
        synchronized(TagSession.writes) {
            val current = try {
                store.read()
            } catch (e: IndexUnreadable) {
                return@synchronized Outcome.Failed(Reason.INDEX_UNREADABLE)
            } catch (e: StoreUnavailable) {
                return@synchronized Outcome.Failed(Reason.STORE_UNAVAILABLE)
            }
            val next = try {
                transform(current) ?: return@synchronized Outcome.Unchanged(current)
            } catch (e: IllegalStateException) {
                return@synchronized Outcome.Failed(
                    if (e.message == ExtensionContract.TAG_INDEX_FULL) Reason.INDEX_FULL else Reason.SAVE_FAILED,
                )
            } catch (e: IllegalArgumentException) {
                return@synchronized Outcome.Failed(Reason.NOT_A_TAG)
            }
            try {
                store.write(next)
            } catch (e: StoreUnavailable) {
                return@synchronized Outcome.Failed(Reason.SAVE_FAILED)
            }
            Outcome.Written(next)
        }
}
