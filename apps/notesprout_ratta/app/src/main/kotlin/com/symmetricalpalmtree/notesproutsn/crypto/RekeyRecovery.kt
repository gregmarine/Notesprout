package com.symmetricalpalmtree.notesproutsn.crypto

import java.io.File

/**
 * Recovery of an interrupted [RekeyCommit] (arc 26 / U2, D1 "recovery of an interrupted commit"),
 * as a pure decision over what each of the three names is right now, plus an executor over
 * [RekeyFs] that re-verifies before every delete. The **rule that binds every branch: nothing is
 * deleted unless the file that will survive verifies** under a key the caller trusts.
 *
 * What a name can be: [Presence.ABSENT]; [Presence.VERIFIES] — present and the caller's verifier
 * opened it; [Presence.UNVERIFIED] — present but no trusted key opens it (mid-rotation, that can be
 * a file already under the *other* key; the rotation resume passes a verifier that knows both).
 *
 * | original | tmp | bak | plan |
 * |---|---|---|---|
 * | VERIFIES | any | any | [Plan.DropLeftovers] — the swap finished (or never started); the leftovers are noise |
 * | UNVERIFIED | any | any | [Plan.Leave] — a present file no trusted key opens is never touched |
 * | ABSENT | VERIFIES | any | [Plan.RestoreTmp] — the death was between the two renames or the export's key is the trusted one; the tmp becomes the original, then the bak may go |
 * | ABSENT | other | VERIFIES | [Plan.RestoreBak] — the tmp is not trusted; the original comes back, then the tmp may go |
 * | ABSENT | other | other | [Plan.Leave] — two files, neither opens: keep both for a person to look at |
 */
object RekeyRecovery {

    enum class Presence { ABSENT, VERIFIES, UNVERIFIED }

    enum class Plan { DropLeftovers, Leave, RestoreTmp, RestoreBak }

    fun decide(original: Presence, tmp: Presence, bak: Presence): Plan = when {
        original == Presence.VERIFIES -> Plan.DropLeftovers
        original == Presence.UNVERIFIED -> Plan.Leave
        tmp == Presence.VERIFIES -> Plan.RestoreTmp
        bak == Presence.VERIFIES -> Plan.RestoreBak
        else -> Plan.Leave
    }

    /** What the executor did for one original — for logs and the debug report; never a path. */
    enum class Result { NOTHING_TO_DO, CLEANED, RESTORED_TMP, RESTORED_BAK, LEFT_ALONE, FAILED }

    /**
     * Recover one [original] from whatever `X.rekey.tmp` / `X.old.bak` stand beside it. [verifies]
     * is the caller's trusted-key test (missing file → false). Idempotent; a second run over the
     * same directory answers [Result.NOTHING_TO_DO].
     */
    fun recover(fs: RekeyFs, original: File, verifies: (File) -> Boolean): Result {
        val tmp = RekeyNames.tmpFor(original)
        val bak = RekeyNames.bakFor(original)
        if (!fs.exists(tmp) && !fs.exists(bak)) return Result.NOTHING_TO_DO

        fun presence(f: File): Presence = when {
            !fs.exists(f) -> Presence.ABSENT
            verifies(f) -> Presence.VERIFIES
            else -> Presence.UNVERIFIED
        }

        return when (decide(presence(original), presence(tmp), presence(bak))) {
            Plan.DropLeftovers -> {
                dropWithSidecars(fs, tmp); dropWithSidecars(fs, bak)
                Result.CLEANED
            }
            Plan.Leave -> Result.LEFT_ALONE
            Plan.RestoreTmp -> {
                if (!fs.rename(tmp, original)) return Result.FAILED
                original.parentFile?.let { fs.fsyncDir(it) }
                // The bak goes only once the file now standing as the original still verifies —
                // a rename that "succeeded" onto a file we then cannot open keeps its fallback.
                if (verifies(original)) dropWithSidecars(fs, bak)
                Result.RESTORED_TMP
            }
            Plan.RestoreBak -> {
                if (!fs.rename(bak, original)) return Result.FAILED
                original.parentFile?.let { fs.fsyncDir(it) }
                if (verifies(original)) dropWithSidecars(fs, tmp)
                Result.RESTORED_BAK
            }
        }
    }

    private fun dropWithSidecars(fs: RekeyFs, file: File) {
        fs.delete(file)
        RekeyNames.sidecarsOf(file).forEach { fs.delete(it) }
    }
}
