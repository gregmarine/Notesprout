package com.symmetricalpalmtree.notesproutsn.data.backup

import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry

/**
 * **The two-leg run's rules** (arc 25 / V4), pure so they are pinned by JVM test rather than
 * reasoned about: which legs a run has, what the progress dialog counts, how a listing answers the
 * stale-sidecar question, what ends a leg, and which blocks the report dialog draws.
 *
 * The engine ([BackupEngine]) and the leg ([CloudBackupLeg]) own everything with a side effect;
 * nothing here touches a file, a database or a Binder.
 *
 * The rules, and why each is one:
 *
 *  - **[legs]** — a run has a local leg when a folder is chosen and a cloud leg when the tick is on
 *    **and** a provider is actually installed. The tick alone is an intention; a provider that has
 *    been uninstalled cannot be uploaded to, and a run that silently did nothing would be the worst
 *    possible way to find that out. Neither leg is [Legs.none] — the one case the screen pre-checks.
 *  - **[units] / [total]** — the progress dialog counts *both* legs from the start, because a bar
 *    that reaches its end and then keeps going says the wrong thing twice. Each leg's units are its
 *    notebooks, its extension stores, and the one index at the end.
 *  - **[staleSidecar]** — the cloud never holds a `-wal` ([SelfContainedSnapshot] is why), so one
 *    found in the listing is a leftover from something else and is deleted **before** the stamp, as
 *    the local leg deletes its own. Names match **exactly**: `upload` is replace-by-name and
 *    resolves the same way. This is the only remote delete in the whole arc.
 *  - **[problemFor] / [endsLeg]** — the mid-leg stop table. Not connected, unreachable, or no answer
 *    at all ends the leg **with the counts so far**: every stamp already earned stays, and piling
 *    60–120 second upload budgets onto a dead link would turn a failed backup into a frozen screen.
 *    A corroboration miss is not on this table — it is per-file, and the leg carries on.
 *  - **[legClean] / [clean]** — which dialog the report is, and which blocks it holds. A leg that
 *    did not run has **no block**: zero-copied sentences about a destination nobody chose are noise.
 */
object CloudBackupRules {

    /** Which destinations this run has. */
    data class Legs(val local: Boolean, val cloud: Boolean) {
        /** Nothing to write to — the run does nothing and says so. */
        val none: Boolean get() = !local && !cloud
    }

    fun legs(hasFolder: Boolean, cloudEnabled: Boolean, hasProvider: Boolean): Legs =
        Legs(local = hasFolder, cloud = cloudEnabled && hasProvider)

    // ── Progress ─────────────────────────────────────────────────────────────

    /** One leg's units: every notebook it will visit, every extension store, and the index last. */
    fun units(notebooksToCopy: Int, stores: Int): Int = notebooksToCopy + stores + 1

    /** The progress dialog's total — both legs, counted before either runs. */
    fun total(localUnits: Int, cloudUnits: Int): Int = localUnits + cloudUnits

    // ── The listing ──────────────────────────────────────────────────────────

    /**
     * The `<name>-wal` entry in [listing], if one is there — a **file** of that exact name, never a
     * folder: deleting a folder because it happened to be called that would be the one destructive
     * mistake this arc must not make.
     */
    fun staleSidecar(listing: List<CloudEntry>, mainName: String): CloudEntry? {
        val walName = mainName + BackupPredicates.WAL_SUFFIX
        return listing.firstOrNull { !it.isFolder && it.name == walName }
    }

    // ── The mid-leg stop table ───────────────────────────────────────────────

    /** How a cloud call failed, as the leg sees it — one kind per sentence the report can say. */
    enum class Failure {
        /** No account is connected (or the token was revoked out from under the provider). */
        NOT_CONNECTED,

        /** The provider could not reach its service; nothing was written. */
        NETWORK,

        /** The provider did not answer at all — nothing is known about what landed. */
        UNANSWERED,

        /** The provider is no longer installed on this device. */
        GONE,
    }

    fun problemFor(failure: Failure): BackupEngine.Problem = when (failure) {
        Failure.NOT_CONNECTED -> BackupEngine.Problem.CLOUD_NOT_CONNECTED
        Failure.NETWORK -> BackupEngine.Problem.CLOUD_NETWORK
        Failure.UNANSWERED -> BackupEngine.Problem.CLOUD_UNANSWERED
        Failure.GONE -> BackupEngine.Problem.CLOUD_GONE
    }

    /** True when [problem] means the cloud leg stops where it stands, keeping what it has earned. */
    fun endsLeg(problem: BackupEngine.Problem?): Boolean = when (problem) {
        BackupEngine.Problem.CLOUD_NOT_CONNECTED,
        BackupEngine.Problem.CLOUD_NETWORK,
        BackupEngine.Problem.CLOUD_UNANSWERED,
        BackupEngine.Problem.CLOUD_GONE,
        -> true

        else -> false
    }

    // ── The report ───────────────────────────────────────────────────────────

    /** A leg with nothing to explain: it did not run, or it ran and everything landed. */
    fun legClean(result: BackupEngine.Result?): Boolean =
        result == null ||
            (result.problem == null && result.failed == 0 && result.storesFailed == 0 && result.indexCopied)

    /** True when the report is *Backup complete* rather than *Backup didn't finish*. */
    fun clean(outcome: BackupEngine.Outcome): Boolean =
        outcome.problem == null && legClean(outcome.local) && legClean(outcome.cloud)

    /** Whether the report draws a block for a leg at all — a leg that did not run has none. */
    fun showsBlock(result: BackupEngine.Result?): Boolean = result != null
}
