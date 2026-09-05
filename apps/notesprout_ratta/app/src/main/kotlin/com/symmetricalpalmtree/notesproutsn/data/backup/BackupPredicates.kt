package com.symmetricalpalmtree.notesproutsn.data.backup

import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags

/**
 * The pure half of the backup run (arc 17 / K2, JVM-tested): og's D8 needs-backup rule, the
 * work-list math over it, and the destination filename scheme. The engine
 * ([BackupEngine]) owns everything with a side effect; nothing here touches a file or a database.
 */
object BackupPredicates {

    // ── Filename scheme (og D5: UUID names give replace-in-place identity) ───

    /** The global index's destination name. */
    const val INDEX_NAME = "notesprout.db"

    /** A live WAL sidecar copied alongside its `.soil` keeps its natural name: `<name>-wal`. */
    const val WAL_SUFFIX = "-wal"

    /** In-flight writes stream to `<name>.part`, renamed in only when complete ([SafBackupWriter]). */
    const val PART_SUFFIX = ".part"

    /** The previous good copy, moved aside for the swap and deleted after it. */
    const val OLD_SUFFIX = ".old"

    /** The cloud tree's first segment (arc 25 / V4): `Backups/<device folder>/` under the
     *  provider's own root. Debug builds get **no** `dev/` inside it — the provider's root is
     *  already "Notesprout SN Dev" in a debug build (`DRIVE_PLAN.md` decision 9). */
    const val CLOUD_BACKUPS_FOLDER = "Backups"

    /** Debug builds write here inside the chosen tree — debug and release coexist on the Nomad
     *  and must not share a root (the arc-17 wizard's per-device answer). */
    const val DEV_SUBDIR = "dev"

    fun soilName(notebookId: String): String = "$notebookId.soil"

    // ── og D8: the needs-backup rule ─────────────────────────────────────────

    /** True when notebook `flags` carry the exclude bit. */
    fun isExcluded(flags: Int?): Boolean =
        flags != null && (flags and NotebookFlags.EXCLUDE_FROM_BACKUP) != 0

    /**
     * og's D8: copy when not excluded and either never stamped or edited since. Equal means
     * backed up — the stamp *is* the `updatedAt` the last successful copy carried
     * ([BackupConfig.stamps]).
     */
    fun needsBackup(updatedAt: Long, stamp: Long?, excluded: Boolean): Boolean =
        !excluded && (stamp == null || updatedAt > stamp)

    // ── Work-list math ───────────────────────────────────────────────────────

    /** One notebook as the work-list decision sees it — identity, clock, policy, and (arc 26 /
     *  U4) which key opens it. [keyScope] takes no part in the *work-list* decision: a
     *  `NOTEBOOK`-scope notebook is still copied like any other — its bytes are bytes. It rides
     *  here because the copy's one *open* step, the pre-copy compaction, must be skipped for it
     *  (no key is available unattended), and the per-notebook loop is where that is known. */
    data class Candidate(
        val id: String,
        val updatedAt: Long,
        val flags: Int?,
        val keyScope: KeyScope = KeyScope.GLOBAL,
    )

    /**
     * What a run will do: [toCopy] in the order given (the caller's listing order), plus the
     * counts the summary owes the user for what it deliberately did not copy.
     */
    data class WorkList(val toCopy: List<Candidate>, val excluded: Int, val upToDate: Int)

    fun workList(notebooks: List<Candidate>, stamps: Map<String, Long>): WorkList {
        val toCopy = ArrayList<Candidate>()
        var excluded = 0
        var upToDate = 0
        for (n in notebooks) {
            when {
                isExcluded(n.flags) -> excluded++
                needsBackup(n.updatedAt, stamps[n.id], excluded = false) -> toCopy.add(n)
                else -> upToDate++
            }
        }
        return WorkList(toCopy, excluded, upToDate)
    }

    /**
     * The stamp map without entries for notebooks that no longer exist — K1's index purge
     * hard-deletes their rows, and a stamp for a purged notebook is dead weight the config would
     * otherwise carry forever. Run only after a successful run (a mid-failure prune could drop a
     * stamp for a row a torn read failed to list).
     */
    fun pruneStamps(stamps: Map<String, Long>, aliveIds: Set<String>): Map<String, Long> =
        stamps.filterKeys { it in aliveIds }
}
