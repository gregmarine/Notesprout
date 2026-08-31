package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * **What "exported" is allowed to mean, per source kind** (arc 18 / D1). Pure — the screen asks
 * this one question after the extension returns and acts on the verdict.
 *
 * The E3 rule this splits in two: the `bytesWritten == streamBytes` equality is a
 * **verbatim-streaming contract** — it holds for [ExporterContract.SOURCE_SOIL] because the soil
 * exporter copies the artifact byte-for-byte, and for nothing else. A
 * [ExporterContract.SOURCE_PAGES] exporter *transforms* (a PDF's size is not the container's), so
 * the source-length equality would fail every honest export; what remains is the extension's own
 * account of what it wrote, corroborated **against the destination's answers only** (the E3
 * corroboration rule, minus the equality). E3's refutation of a "transforming exporter" finding
 * was scoped to soil's verbatim stream — this is that exporter arrived for real, with its own
 * verification.
 *
 * The two failure verdicts stay distinct because the screen owes them different honesty: [SHORT]
 * is a failed export (the destination holds wreckage — the failure path may delete it under its
 * usual rules), while [UNCONFIRMED] means the stream itself completed and only the provider's
 * lagging metadata disagrees — a check-the-file dialog, **never** a delete (a cloud provider's
 * stale answer must not destroy the very file that was just made).
 */
object ExportVerification {

    enum class Verdict {
        /** Claim success. */
        OK,

        /** The extension did not deliver what it owed — a failed export. */
        SHORT,

        /** Delivery looked complete but the destination's own accounts all disagree — tell the
         *  user to check the file; never delete over it. */
        UNCONFIRMED,
    }

    /**
     * Judge one finished `export()` call. [bytesWritten] is the extension's report;
     * [streamBytes] the size of the file the host actually streamed (the keyed artifact, or the
     * page bundle — only consulted for [ExporterContract.SOURCE_SOIL]); [destinationSizes] every
     * account the destination provider would give of what it now holds (empty when it will not
     * say — corroboration, not authority: any agreeing answer is enough).
     */
    fun verdict(
        sourceKind: Int,
        bytesWritten: Long,
        streamBytes: Long,
        destinationSizes: List<Long>,
    ): Verdict = when (sourceKind) {
        // SOURCE_DOCUMENT rides the verbatim rule on purpose (arc 19 / M9): the host assembles
        // the FINAL text bytes — the plain-text strip runs host-side, before the stream — so the
        // extension is a byte-for-byte copier exactly like soil's and owes the same equality.
        ExporterContract.SOURCE_SOIL, ExporterContract.SOURCE_DOCUMENT -> when {
            bytesWritten != streamBytes -> Verdict.SHORT
            destinationSizes.isNotEmpty() && destinationSizes.none { it == streamBytes } -> Verdict.UNCONFIRMED
            else -> Verdict.OK
        }
        ExporterContract.SOURCE_PAGES -> when {
            // No source-length equality — but zero bytes is never a document, whatever the format.
            bytesWritten <= 0L -> Verdict.SHORT
            destinationSizes.isNotEmpty() && destinationSizes.none { it == bytesWritten } -> Verdict.UNCONFIRMED
            else -> Verdict.OK
        }
        // Unreachable behind discovery (an unknown kind fails ExporterInfo's unmarshal), but a
        // verification must never default to trust.
        else -> Verdict.SHORT
    }
}
