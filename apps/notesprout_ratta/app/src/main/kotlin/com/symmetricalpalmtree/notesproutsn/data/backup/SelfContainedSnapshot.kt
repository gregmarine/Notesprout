package com.symmetricalpalmtree.notesproutsn.data.backup

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import java.io.File

/**
 * **One file, complete in itself** (arc 25 / V4) — the cloud leg's answer to the sidecar problem.
 *
 * The local leg can land a `.soil` and its `-wal` as a pair because [SafBackupWriter]'s
 * `.part`/`.old` swap makes each write near-atomic and both land inside one run. **The cloud has no
 * swap.** Two uploads can tear — the second one fails, the link drops, the process dies — and a
 * fresh main file paired with a stale `-wal` is not a slow backup, it is a corrupt one that looks
 * fine until the day it is needed.
 *
 * So the cloud never holds a sidecar at all. Before every upload the file is copied into the cache
 * **with its WAL**, the copy is opened and `PRAGMA wal_checkpoint(TRUNCATE)`d so the frames move
 * into the main file, and only a copy that comes out of that with **no WAL frames left** and still
 * probing as encrypted is uploaded. Anything else answers null and that file is refused this run —
 * counted failed, retried next run, nothing uploaded and nothing deleted.
 *
 * **The key.** The copy is opened with the file's cached raw key ([KeyMaterial.peekOrLoad] — the
 * notebook id for a `.soil`, [KeyMaterial.INDEX_FILE_ID] for the index,
 * `ExtensionStores.fileIdFor(pkg)` for a store), and only falls back to the session passphrase when
 * no key has been derived on this device yet. Neither ever leaves this process, and neither is
 * logged: the lines here carry byte counts and booleans.
 *
 * The work happens in [dir], which is **wiped before every file** and by the caller's `finally` —
 * a plaintext-free but still key-shaped copy of the library has no business outliving the run.
 */
object SelfContainedSnapshot {

    private const val TAG = "CloudSnapshot"

    /** The cache subdirectory the snapshots are built in, under the engine's own `backup/`. */
    private const val DIR = "backup/cloud"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** Remove the working directory and everything in it. Safe to call when it is not there. */
    fun clean(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    /**
     * A self-contained copy of [live] named [destName], or **null** when one could not be made.
     *
     * [fileId] is the raw-key cache id for [live] — the notebook id, [KeyMaterial.INDEX_FILE_ID],
     * or `ExtensionStores.fileIdFor(pkg)`. Blocking IO and a SQLCipher open: call from IO.
     */
    fun of(context: Context, live: File, destName: String, fileId: String): File? {
        if (!live.exists() || live.length() == 0L) return null
        val dir = dir(context)
        val snap = File(dir, destName)
        val snapWal = File(dir, destName + BackupPredicates.WAL_SUFFIX)
        try {
            // Wiped per file: only one snapshot is ever in flight, and a leftover from the previous
            // file would be the wrong bytes under the right name.
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw java.io.IOException("could not create the cloud snapshot directory")
            live.copyTo(snap, overwrite = true)
            // Main first, then the WAL: frames the WAL holds beyond the main copy replay forward on
            // open, which is exactly what the checkpoint below is about to fold in.
            val liveWal = File(live.path + BackupPredicates.WAL_SUFFIX)
            if (liveWal.exists() && liveWal.length() > 0L) liveWal.copyTo(snapWal, overwrite = true)
            absorbWal(context, snap, fileId)
        } catch (e: Exception) {
            Log.w(TAG, "snapshot of a ${live.length()}-byte file failed", e)
            runCatching { dir.deleteRecursively() }
            return null
        }
        // The two questions that decide whether this is one whole file: no frames left outside it,
        // and still the encrypted database it claims to be.
        val walLeft = snapWal.exists() && snapWal.length() > 0L
        val encrypted = SoilCrypto.probe(snap) == SoilFileKind.Encrypted
        if (walLeft || !encrypted || snap.length() == 0L) {
            Slog.d(TAG) { "snapshot refused: walLeft=$walLeft encrypted=$encrypted bytes=${snap.length()}" }
            runCatching { dir.deleteRecursively() }
            return null
        }
        Slog.d(TAG) { "snapshot ready: ${snap.length()} B, no sidecar" }
        return snap
    }

    /**
     * Open the **copy** (never the live file) and checkpoint its WAL into it, then close. The open
     * is the one place a key is needed at all, and a copy that will not open is a copy that cannot
     * be trusted whole — the caller's probe is what turns that into a refusal.
     */
    private fun absorbWal(context: Context, snap: File, fileId: String) {
        // KeyOpener's recipe, not a bare peek (the V4 walk's finding): a cached key can be STALE
        // for a file this process has not opened — a store wiped and re-minted since the key was
        // derived keeps the old key in the Keystore until something opens it through KeyOpener.
        // A stale key opens as "file is not a database", so verify first, drop a key that does not
        // fit, and take the passphrase (one KDF) for this copy.
        val cached = KeyMaterial.peekOrLoad(context, fileId)
        val rawKey = cached?.takeIf { SoilCrypto.verifyRawKey(snap, it) }
        if (cached != null && rawKey == null) {
            Slog.d(TAG) { "cached raw key stale for this file — invalidating" }
            KeyMaterial.invalidate(context, fileId)
        }
        val db = if (rawKey != null) {
            SoilCrypto.openRawKey(snap, rawKey)
        } else {
            val passphrase = KeySession.get() ?: throw IllegalStateException("no key session")
            SoilCrypto.openRaw(snap, passphrase)
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            runCatching { db.close() }
        }
    }
}
