package com.symmetricalpalmtree.notesproutsn.importing

/**
 * **The download's corroboration** (arc 25 / V5) — what the host believes about the bytes a cloud
 * provider just streamed into its cache, as a pure rule.
 *
 * It is arc 16's delivery discipline pointed at a provider instead of a document picker, and it
 * keeps both halves of that discipline:
 *
 *  - **Two first-hand counts must agree.** What the provider says it wrote and what the file
 *    actually holds are both first-hand: a disagreement is a truncated stream and the import stops
 *    ([Verdict.SHORT]).
 *  - **The listing is corroboration, never authority.** The size in the entry the browser drew was
 *    read at listing time and can lag the file it describes (the arc's standing trap). Claiming
 *    *more* than landed is the one thing that cannot be a lag — it describes bytes that never
 *    arrived — and fails as SHORT; claiming *less*, or anything else that simply disagrees, is
 *    logged ([Verdict.DISAGREE]) and the import goes on, because the probe and the keying
 *    acceptance downstream answer for what the bytes actually are.
 *
 * A listing that says nothing at all (a negative size) is not a disagreement: nobody can be
 * contradicted by a number they did not give.
 *
 * The **empty** rule is deliberately not here. Whether zero bytes is a refusal depends on what the
 * matched importer says the bytes ARE, and that is [ImportRouting.rejectsEmptyDelivery]'s question
 * for both sources at once.
 */
object CloudImportRules {

    /** What the three accounts of the downloaded file add up to. */
    enum class Verdict {
        /** They agree, or the only disagreement is a listing that said nothing. */
        OK,

        /** Fewer bytes landed than were claimed — the download stops the import. */
        SHORT,

        /** The listing disagrees in a way a lagging listing can explain: log it, carry on. */
        DISAGREE,
    }

    /**
     * @param reported what the provider says it wrote into the descriptor.
     * @param landed what the cache file actually holds.
     * @param listed the size the listing gave for that entry — negative when it gave none.
     */
    fun downloadVerdict(reported: Long, landed: Long, listed: Long): Verdict = when {
        reported != landed -> Verdict.SHORT
        listed > landed -> Verdict.SHORT
        listed >= 0 && listed != landed -> Verdict.DISAGREE
        else -> Verdict.OK
    }
}

/**
 * A cloud step of the import failed, and **nothing was imported** (arc 25 / V5). Its own type rather
 * than a [NotebookImport.Problem] value because one of the four is not a plain problem dialog: no
 * connected account is answered with a **Connect** button, and the `Problem` table's one-body-per-
 * value shape has nowhere to put that. The `Problem` meanings are left exactly as they were.
 */
class CloudImportFailure(val kind: Kind, cause: Throwable? = null) : Exception(cause) {

    enum class Kind {
        /** The provider was uninstalled or disabled between the tap and the call. */
        GONE,

        /** No account is connected — the one failure that offers Connect. */
        NOT_CONNECTED,

        /** The provider could not reach its service; nothing was downloaded. */
        NETWORK,

        /** The provider did not answer at all. Nothing was imported either way. */
        UNANSWERED,
    }
}
