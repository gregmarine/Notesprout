package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * "Is what is on screen a draft the host has not stored yet?" — **pure Kotlin**, because it decides
 * whether a save claims provenance, and a wrong answer there is a document that lies about where it
 * came from.
 *
 * A seed (the host recognized the page at open or at a flip) and a Bring in (the writer asked for it)
 * both put text in the editor that the host is holding **only in its read window**. The host's
 * document row is still what it was, and the watermark that makes "drafted from this page" true is
 * parked, not stamped. The seed becomes real the moment the editor stores it — one save carrying
 * `drafted = true`, which is what tells the host to stamp the watermark it parked.
 *
 * So this holds exactly one bit and the three things that can happen to it:
 *
 * - **[arm]** — a seed or a merge was adopted; the next push is the one that makes it real.
 * - **[onPushSucceeded]** — that push landed: the draft is stored, the anchor is spent.
 * - **[onPushFailed]** — the push threw. One failure is special: [DocumentContract.NO_DRAFT_PENDING]
 *   means the host had no parked watermark to stamp (its process restarted under the editor), so
 *   **nothing was written** and nothing ever will be under that flag. The honest recovery is to drop
 *   the claim and send the same words again as an ordinary save — the words land, and only the
 *   provenance is lost. Every other failure is transport: the flag stays and the retry carries it.
 *
 * The message is matched with `==` against the exact contract string, never `contains` and never a
 * prefix — the `RecognizerClient` recipe for a typed refusal crossing a Binder.
 */
class DraftAnchor {

    /** True while a seed / Bring in is on screen and unstored. */
    var pending: Boolean = false
        private set

    /** A seed or a Bring in was adopted. */
    fun arm() {
        pending = true
    }

    /** The incoming document owns no draft — a flip onto a stored page, or a downgrade. */
    fun clear() {
        pending = false
    }

    /**
     * A push landed. [drafted] is the flag that push **carried**, snapshotted when it was triggered
     * — not the flag now, which a Bring in during the push could already have re-armed.
     */
    fun onPushSucceeded(drafted: Boolean): Outcome {
        if (!drafted) return Outcome.UNCHANGED
        pending = false
        return Outcome.ANCHORED
    }

    /** A push threw. [exceptionMessage] is the exception's message as it crossed the Binder. */
    fun onPushFailed(drafted: Boolean, exceptionMessage: String?): Outcome {
        if (!drafted) return Outcome.UNCHANGED
        if (exceptionMessage == DocumentContract.NO_DRAFT_PENDING) {
            pending = false
            return Outcome.DOWNGRADED
        }
        return Outcome.RETRY
    }

    /** What a completed push meant for the draft claim. */
    enum class Outcome {
        /** An ordinary save: the claim is untouched, whatever it was. */
        UNCHANGED,

        /** The draft is stored. The strip may say "drafted from this page" and mean it. */
        ANCHORED,

        /** The host had no watermark parked: nothing was written, the claim is dropped, and the
         *  same words must go again as an ordinary save. */
        DOWNGRADED,

        /** A transport failure: the claim stands and the retry carries it. */
        RETRY,
    }
}
