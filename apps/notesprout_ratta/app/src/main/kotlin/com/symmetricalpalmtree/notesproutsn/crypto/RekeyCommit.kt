package com.symmetricalpalmtree.notesproutsn.crypto

import java.io.File

/**
 * The names an in-place rekey leaves beside a Garden file while it runs (arc 26 / U2, D1) — and,
 * after an interruption, the names [RekeyRecovery] looks for. `X` is the original's full name
 * (`<uuid>.soil`, `<pkg>.db`, `notesprout.db`); `X.rekey.tmp` is the accepted output waiting to
 * be swapped in, `X.old.bak` the original stepping aside for it.
 */
object RekeyNames {
    const val TMP_SUFFIX = ".rekey.tmp"
    const val BAK_SUFFIX = ".old.bak"

    fun tmpFor(original: File): File = File(original.parentFile, original.name + TMP_SUFFIX)
    fun bakFor(original: File): File = File(original.parentFile, original.name + BAK_SUFFIX)

    /** Sidecars a SQLite file may leave beside itself; deleted with it, never with anything else. */
    fun sidecarsOf(file: File): List<File> = listOf("-wal", "-shm", "-journal").map { File(file.path + it) }

    /**
     * The originals that have leftovers in a directory listing — the pure half of
     * [RekeyRecovery]'s sweep. `X.rekey.tmp` and `X.old.bak` both name `X`; a sidecar of either
     * (`X.rekey.tmp-journal`) names nothing, and so does every other file.
     */
    fun leftoverOriginals(names: Iterable<String>): Set<String> = names.mapNotNullTo(LinkedHashSet()) { n ->
        when {
            n.endsWith(TMP_SUFFIX) -> n.dropLast(TMP_SUFFIX.length)
            n.endsWith(BAK_SUFFIX) -> n.dropLast(BAK_SUFFIX.length)
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }
}

/**
 * og's `commitReplace`, as a state machine over [RekeyFs] (arc 26 / U2, D1 step 3): swap an
 * **accepted** `X.rekey.tmp` into `X`'s place with the original never at risk —
 *
 *  1. fsync the tmp;
 *  2. refuse if the original has a **non-empty** `-wal` (live data the caller failed to absorb —
 *     a rename would orphan it), else delete its empty `-wal` / `-shm` so the new file does not
 *     inherit a stale shared-memory index;
 *  3. clear a stale `X.old.bak` (a leftover of an earlier run whose original has since been
 *     proven good by the export that just ran from it);
 *  4. rename `X` → `X.old.bak`;
 *  5. rename `X.rekey.tmp` → `X`; on failure rename the `.old.bak` straight back;
 *  6. fsync the directory, delete the `.old.bak`.
 *
 * Every exit is an [Outcome]; nothing throws. The two-rename window is what [RekeyRecovery] exists
 * for: a death between 4 and 5 leaves `X.old.bak` + `X.rekey.tmp` and no `X`, a death between 5
 * and 6 leaves `X` + `X.old.bak`, and both are recoverable without guessing because every file
 * involved can be verified against a key.
 */
object RekeyCommit {

    sealed class Outcome {
        /** The new file is in place; a lingering `.old.bak` (delete failed) is recovery's to clean. */
        object Committed : Outcome()
        /** The original still has a live WAL — nothing was touched. The caller drops the tmp. */
        object RefusedLiveWal : Outcome()
        /** Step 4 failed — the original never moved. The caller drops the tmp. */
        object OriginalNotMoved : Outcome()
        /** Step 5 failed and the original is back in place. The caller drops the tmp. */
        object RolledBack : Outcome()
        /** Step 5 failed **and** the roll-back failed: `X.old.bak` and `X.rekey.tmp` both stand,
         *  `X` is absent. Nothing is deleted; Bootstrap's recovery finishes the job. */
        object BothKept : Outcome()
    }

    fun commitReplace(fs: RekeyFs, original: File, tmp: File): Outcome {
        val bak = RekeyNames.bakFor(original)
        fs.fsync(tmp)

        val wal = File(original.path + "-wal")
        if (fs.exists(wal) && fs.length(wal) > 0L) return Outcome.RefusedLiveWal
        fs.delete(wal)
        fs.delete(File(original.path + "-shm"))

        if (fs.exists(bak)) fs.delete(bak)

        if (!fs.rename(original, bak)) return Outcome.OriginalNotMoved
        if (!fs.rename(tmp, original)) {
            return if (fs.rename(bak, original)) Outcome.RolledBack else Outcome.BothKept
        }
        original.parentFile?.let { fs.fsyncDir(it) }
        fs.delete(bak)
        return Outcome.Committed
    }
}
