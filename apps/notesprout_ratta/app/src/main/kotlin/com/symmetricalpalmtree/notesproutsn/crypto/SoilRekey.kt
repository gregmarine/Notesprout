package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.gardenDir
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilCompactor
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The in-place rekey of a Garden file** (arc 26 / U2, D1) — one recipe for a `.soil`, a
 * `Garden/<pkg>.db` extension store and the index alike, and the only thing in SN allowed to
 * change the key a file on disk is under. Rotation (U3), the library sheet's scope and passphrase
 * changes (U5) and recovery's *Repair* (U6) all call [rekeyInPlace]; nothing else re-keys.
 *
 *  1. **The file must be cold** — no connection in this process ([SoilOpenFiles] for a `.soil`;
 *     the caller has run `ExtensionStores.closeAll()` / `SnIndex.closeForRotation()` for the other
 *     two kinds) — and its WAL absorbed: a raw open under the *current* key, `wal_checkpoint
 *     (TRUNCATE)`, close, then the sidecar sweep. A WAL that will not absorb stops the rekey
 *     before anything is written; **a non-empty `-wal` is never deleted**.
 *  2. [ExportKeying.exportAndKeyToPrimary] from the file into `X.rekey.tmp` under the new key —
 *     `user_version` carried, `notebook_meta` restamped for a `.soil` ([keyScope] is what the
 *     caller says the file *becomes*), acceptance = probe + open + `integrity_check` + version.
 *     A failure deletes only the tmp.
 *  3. [RekeyCommit.commitReplace] — og's order, fsync'd, the original never at risk.
 *  4. [KeyMaterial.invalidate] — the salt changed, so the cached raw key is stale.
 *
 * `PRAGMA rekey` is never used (og's on-device finding, the arc-15 law). Passphrases reach this
 * object as parameters and leave it only as SQL literals on a local connection; none is logged.
 *
 * [recoverGarden] is the other half: Bootstrap runs it once the index is open, and rotation's
 * resume runs it before its loop, so a death anywhere inside step 3 is put right on the next
 * launch by [RekeyRecovery]'s decision table. The index has no directory listing of its own —
 * `SnIndex.ensureReady` calls [recoverOne] for it before it would ever treat a missing file as
 * a fresh install.
 */
object SoilRekey {

    private const val TAG = "SoilRekey"

    /**
     * Re-key [file] (cache id [fileId]) from [oldPassphrase] to [newPassphrase] in place. For a
     * `.soil`, [keyScope] is the scope the file will describe itself as; null for a store or the
     * index (no meta to restamp). IO. Throws `IllegalStateException` with a path-free message on
     * any failure, and in every failure the original is exactly as it was.
     */
    suspend fun rekeyInPlace(
        context: Context,
        file: File,
        fileId: String,
        oldPassphrase: String,
        newPassphrase: String,
        keyScope: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        SoilCrypto.requireExisting(file)
        if (SoilOpenFiles.isOpen(file)) throw IllegalStateException("the file is open in this process")

        absorbWal(file, oldPassphrase)

        val tmp = RekeyNames.tmpFor(file)
        ExportKeying.rejectOutput(tmp)
        ExportKeying.exportAndKeyToPrimary(
            out = tmp,
            sourcePath = file.path,
            attachKeyLiteral = ExportKeying.sqlLiteral(oldPassphrase),
            destPassphrase = newPassphrase,
            keyScope = keyScope,
            what = "re-keyed",
            restamp = keyScope != null,
        )

        when (val outcome = RekeyCommit.commitReplace(RealRekeyFs, file, tmp)) {
            RekeyCommit.Outcome.Committed -> Unit
            RekeyCommit.Outcome.BothKept -> {
                // The original is `.old.bak`, the new file is `.rekey.tmp`, neither deleted —
                // Bootstrap's recovery renames whichever verifies back in.
                Log.w(TAG, "commit left both copies for $fileId; recovery will finish it")
                throw IllegalStateException("the re-keyed file could not be moved into place")
            }
            else -> {
                ExportKeying.rejectOutput(tmp)
                Log.w(TAG, "commit did not happen for $fileId: ${outcome.javaClass.simpleName}")
                throw IllegalStateException("the re-keyed file could not be moved into place")
            }
        }

        KeyMaterial.invalidate(app, fileId)
        Slog.d(TAG) { "re-keyed $fileId (${file.length()} bytes)" }
    }

    /**
     * Fold the WAL into the main file under the current key and sweep the empty sidecars — the
     * shape `BackupEngine.compactPass` uses, on a raw connection so it fits all three file kinds.
     * Throws when a non-empty WAL is left afterwards: the commit would refuse it anyway, and
     * stopping here writes nothing.
     */
    private fun absorbWal(file: File, passphrase: String) {
        val db = try {
            SoilCrypto.openRaw(file, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "absorb open failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the file could not be opened with its current key")
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { db.close() }
        }
        SoilCompactor.sweepSidecars(file)
        val wal = File(file.path + "-wal")
        if (wal.exists() && wal.length() > 0L) throw IllegalStateException("the file still has unabsorbed writes")
    }

    // ── Recovery ─────────────────────────────────────────────────────────────

    /**
     * Put right every interrupted commit in `Garden/` (arc 26 / U2 — Bootstrap, after the index
     * is open; rotation resume, before its loop). [verifies] is the trusted-key test; Bootstrap
     * passes the cached global passphrase, the rotation resume one that knows the marker's new
     * passphrase too. Never throws; returns how many originals had leftovers. IO.
     */
    suspend fun recoverGarden(context: Context, verifies: (File) -> Boolean): Int = withContext(Dispatchers.IO) {
        val garden = gardenDir(context.applicationContext)
        val names = garden.list()?.toList() ?: return@withContext 0
        val originals = RekeyNames.leftoverOriginals(names)
        for (name in originals) {
            val result = try {
                RekeyRecovery.recover(RealRekeyFs, File(garden, name), verifies)
            } catch (e: Exception) {
                Log.w(TAG, "recovery threw for a Garden file: ${e.javaClass.simpleName}")
                RekeyRecovery.Result.FAILED
            }
            Log.w(TAG, "recovered ${name.substringAfterLast('.')} leftovers: $result")
        }
        originals.size
    }

    /** [RekeyRecovery.recover] for one named file outside the Garden listing — the index. */
    fun recoverOne(original: File, verifies: (File) -> Boolean): RekeyRecovery.Result =
        try {
            RekeyRecovery.recover(RealRekeyFs, original, verifies)
        } catch (e: Exception) {
            Log.w(TAG, "recovery threw for ${original.name}: ${e.javaClass.simpleName}")
            RekeyRecovery.Result.FAILED
        }

    /** True iff a `.rekey.tmp` or `.old.bak` stands beside [original]. */
    fun hasLeftovers(original: File): Boolean =
        RekeyNames.tmpFor(original).exists() || RekeyNames.bakFor(original).exists()
}
