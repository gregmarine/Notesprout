package com.notesprout.android.crypto

import android.util.Log
import com.notesprout.android.core.Slog
import com.notesprout.android.data.SoilSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * Converts a .soil file between plaintext and SQLCipher-encrypted in place using sqlcipher_export().
 *
 * Both functions use a temp file + atomic rename so the original is never corrupted on failure.
 *
 * Encrypt direction: open the encrypted destination as the main zetetic connection, ATTACH the
 * plaintext source with KEY '' (SQLCipher plaintext mode), then copy FROM the attachment TO main
 * using sqlcipher_export('main', 'plain'). Opening plaintext as the PRIMARY zetetic connection
 * with empty-key does not expose existing data reliably; reversing the roles avoids this entirely.
 *
 * Decrypt direction: open the encrypted source as the main connection (key known), ATTACH an
 * empty plaintext destination with KEY '', export from main to attachment.
 */
object SoilMigrator {

    private const val TAG = "SoilMigrator"

    /**
     * Encrypts a plaintext .soil file in place.
     *
     * Creates a new encrypted temp DB via zetetic, attaches the plaintext source with KEY '',
     * copies data FROM plaintext attachment TO encrypted main, verifies, then replaces the
     * original file.
     *
     * Must be called on a dispatcher that allows blocking I/O (Dispatchers.IO).
     * Throws on failure — caller is responsible for surfacing the error.
     */
    suspend fun encryptInPlace(file: File, passphrase: String) = withContext(Dispatchers.IO) {
        // If the file on disk is not actually plaintext, encrypting it would read ciphertext as an
        // empty/corrupt source and could replace real data with an empty output. The classic way in
        // is index drift: a prior run encrypted the file but died before the index row was updated,
        // and the user retries "Encrypt".
        val kind = SoilCrypto.probe(file)
        check(kind == SoilFileKind.Plaintext) {
            "Refusing to encrypt ${file.name}: the file is not a plaintext database (found: $kind). " +
            "It may already be encrypted by an interrupted earlier attempt."
        }

        val tmp = File("${file.absolutePath}.enc.tmp")
        file.parentFile?.listFiles { f -> f.name.startsWith(tmp.name) }?.forEach { it.delete() }

        // Checkpoint any pending WAL data into the main file using the standard driver first.
        // NonDeletingErrorHandler is mandatory: the framework default DELETES the database on a
        // corruption report, and this open targets the live original.
        try {
            val stdDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE, NonDeletingErrorHandler,
            )
            stdDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            stdDb.close()
        } catch (e: Exception) {
            Log.w(TAG, "WAL checkpoint before encrypt skipped: ${e.message}")
        }

        // Open the encrypted destination as the primary zetetic connection.
        val dest = ZeticDB.openOrCreateDatabase(tmp, passphrase, null, null)
        try {
            // Attach the plaintext source. KEY '' = SQLCipher plaintext mode for ATTACH.
            dest.execSQL("ATTACH DATABASE '${sqlPath(file)}' AS plain KEY ''")
            try {
                // Two-argument export: copies FROM 'plain' attachment TO 'main' (encrypted dest).
                dest.rawQuery("SELECT sqlcipher_export('main', 'plain')", null).use { it.moveToFirst() }
                // sqlcipher_export does NOT copy PRAGMA user_version — carry it over by hand. Without
                // this the encrypted output is version 0, and if the source was below the current Room
                // schema, Room treats it as a new/prepackaged DB and rejects the old-schema tables
                // instead of migrating them. That bricked G6's v3 notebooks. See docs/encryption.md.
                copyUserVersion(dest, from = "plain", to = "main")
            } finally {
                dest.execSQL("DETACH DATABASE plain")
            }
        } finally {
            dest.close()
        }

        // Verify the output is readable with the passphrase before replacing the original.
        if (!SoilCrypto.verifyPassphrase(tmp, passphrase)) {
            tmp.delete()
            error("Encrypted output failed verification — original notebook is unchanged.")
        }

        commitReplace(file, tmp)
        Slog.d(TAG) { "encryptInPlace complete: ${file.name}" }
    }

    /**
     * Decrypts an encrypted .soil file in place.
     *
     * Opens the encrypted source, exports into a plaintext temp, verifies the result is a valid
     * SQLite database, deletes the original + sidecars, and renames the temp to the original path.
     *
     * Must be called on a dispatcher that allows blocking I/O (Dispatchers.IO).
     * Throws on failure — caller is responsible for surfacing the error.
     */
    suspend fun decryptInPlace(file: File, passphrase: String) = withContext(Dispatchers.IO) {
        val tmp = File("${file.absolutePath}.dec.tmp")
        file.parentFile?.listFiles { f -> f.name.startsWith(tmp.name) }?.forEach { it.delete() }

        val src = ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
        try {
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            // Attach a plaintext destination (empty key = no encryption).
            src.execSQL("ATTACH DATABASE '${sqlPath(tmp)}' AS plaintext KEY ''")
            try {
                src.rawQuery("SELECT sqlcipher_export('plaintext')", null).use { it.moveToFirst() }
                // Preserve the Room schema version — sqlcipher_export drops it (see encryptInPlace).
                copyUserVersion(src, from = "main", to = "plaintext")
            } finally {
                src.execSQL("DETACH DATABASE plaintext")
            }
        } finally {
            src.close()
        }

        // Verify the output is a readable plaintext SQLite file.
        if (!verifyPlaintext(tmp)) {
            tmp.delete()
            error("Decrypted output is not a valid SQLite database — original notebook is unchanged.")
        }

        commitReplace(file, tmp)
        Slog.d(TAG) { "decryptInPlace complete: ${file.name}" }
    }

    /**
     * Re-keys an encrypted .soil file in place using the export round-trip.
     *
     * PRAGMA rekey was found unreliable on-device. Mirrors the encryptInPlace pattern:
     * open the new encrypted destination as the primary zetetic connection, ATTACH the
     * old encrypted source with the old key, export FROM source TO main, verify, replace.
     *
     * Must be called on a dispatcher that allows blocking I/O (Dispatchers.IO).
     * Throws on failure — caller is responsible for surfacing the error.
     */
    suspend fun rekeyInPlace(file: File, oldPassphrase: String, newPassphrase: String) = withContext(Dispatchers.IO) {
        val tmp = File("${file.absolutePath}.rekey.tmp")
        // Clean tmp AND its WAL/SHM sidecars — a previous aborted run may have left them behind.
        file.parentFile?.listFiles { f -> f.name.startsWith(tmp.name) }?.forEach { it.delete() }

        // Checkpoint the source WAL before the export so the ATTACH sees a consistent state.
        // Mirrors decryptInPlace; skipped silently on failure (WAL is optional).
        try {
            val src = ZeticDB.openOrCreateDatabase(file, oldPassphrase, null, null)
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            src.close()
        } catch (e: Exception) {
            Log.w(TAG, "WAL checkpoint before rekey skipped: ${e.message}")
        }

        // Open the new-encrypted destination as the primary zetetic connection (mirrors encryptInPlace).
        val dest = ZeticDB.openOrCreateDatabase(tmp, newPassphrase, null, null)
        try {
            // Attach the old-encrypted source with the old key.
            dest.execSQL("ATTACH DATABASE '${sqlPath(file)}' AS old_src KEY '${oldPassphrase.replace("'", "''")}'")
            try {
                // Two-argument export: copies FROM 'old_src' attachment TO 'main' (new-encrypted dest).
                dest.rawQuery("SELECT sqlcipher_export('main', 'old_src')", null).use { it.moveToFirst() }
                // Preserve the Room schema version — sqlcipher_export drops it (see encryptInPlace).
                copyUserVersion(dest, from = "old_src", to = "main")
            } finally {
                dest.execSQL("DETACH DATABASE old_src")
            }
        } finally {
            dest.close()
        }

        if (!SoilCrypto.verifyPassphrase(tmp, newPassphrase)) {
            tmp.delete()
            error("Re-key verification failed — original notebook is unchanged.")
        }

        commitReplace(file, tmp)
        Slog.d(TAG) { "rekeyInPlace complete: ${file.name}" }
    }

    /**
     * Repair a `.soil` bricked by the historical `sqlcipher_export` user-version loss.
     *
     * Signature of the brick: the file **decrypts fine** (so this is not a key problem) but Room
     * refuses it with "Pre-packaged database has an invalid schema" because its `PRAGMA user_version`
     * is 0 while it still holds an older `notebook` schema. Room reads version 0 as "brand-new DB",
     * runs `onCreate`, and validates the pre-existing old-schema tables against the current entity —
     * which fails, instead of running the additive migration that would upgrade them.
     *
     * The fix is to restore the version Room needs to see so it migrates normally. We derive it from
     * which schema-version tables are already present (every historical migration only *adds* tables/
     * columns, so table presence is a reliable version floor): `notebook_meta` ⇒ v3, else
     * `undo_redo_state` ⇒ v2, else v1. Room then runs the remaining migrations (all idempotent for a
     * pre-v4 file — the v3→v4 `ADD COLUMN`s target columns this file is missing).
     *
     * No-ops (returns null, file untouched) unless the exact brick signature holds: opens with the
     * key, `user_version == 0`, `room_master_table` present (it *was* a versioned Room DB), a
     * `notebook` table exists, and it lacks the v4 columnar columns. [passphrase] null ⇒ plaintext.
     *
     * Returns the version stamped, or null if nothing was done. Must run on Dispatchers.IO.
     */
    suspend fun repairMissingUserVersion(file: File, passphrase: String?): Int? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null
        val db = try {
            SoilCrypto.openRaw(file, passphrase)
        } catch (e: Exception) {
            // Won't even open with the key → not this bug (a real key/corruption problem).
            Slog.d(TAG) { "repair: open failed for ${file.name}: ${e.message}" }
            return@withContext null
        }
        try {
            fun queryLong(sql: String): Long? =
                db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            fun tableExists(name: String): Boolean = (queryLong(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$name'"
            ) ?: 0L) > 0L

            val version = queryLong("PRAGMA user_version") ?: return@withContext null
            if (version != 0L) return@withContext null   // already versioned → not the brick

            // A Room DB (has room_master_table) whose version was zeroed. No room_master_table, or no
            // notebook table, means this is some other file we must not touch.
            if (!tableExists("room_master_table") || !tableExists("notebook")) return@withContext null

            // If the notebook table already has the v4 columns, the schema matches and this isn't the
            // brick (a v4 file with version 0 opens fine) — leave it alone.
            val v4Marker = SoilSchema.ADDED_COLUMNS_V4.firstOrNull()?.first
            if (v4Marker != null) {
                val hasV4 = runCatching {
                    var found = false
                    db.rawQuery("PRAGMA table_info(notebook)", null).use { c ->
                        val nameIdx = c.getColumnIndex("name")
                        while (c.moveToNext()) if (c.getString(nameIdx) == v4Marker) { found = true; break }
                    }
                    found
                }.getOrDefault(false)
                if (hasV4) return@withContext null
            }

            // Derive the version floor from present tables (every historical migration only *adds*).
            val target = when {
                tableExists("notebook_meta")    -> 3
                tableExists("undo_redo_state")  -> 2
                else                            -> 1
            }
            db.rawQuery("PRAGMA user_version = $target", null).use { it.moveToFirst() }
            // Flush the header write out of the WAL into the main file before Room reopens it.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            Slog.d(TAG) { "repair: stamped user_version=$target on ${file.name}" }
            target
        } finally {
            runCatching { db.close() }
        }
    }

    /** Copy `PRAGMA user_version` from one attached schema to another (sqlcipher_export omits it). */
    private fun copyUserVersion(db: ZeticDB, from: String, to: String) {
        val version = db.rawQuery("PRAGMA $from.user_version", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        db.execSQL("PRAGMA $to.user_version = $version")
    }

    // ── Replace / recovery machinery ─────────────────────────────────────────

    private const val ASIDE_SUFFIX = ".old.bak"
    private val TMP_SUFFIXES = listOf(".enc.tmp", ".dec.tmp", ".rekey.tmp")

    /**
     * Swap a verified converted [tmp] into [file]'s place without ever holding zero copies.
     *
     * Ordering: original → `.old.bak` (rename), tmp → original name (rename), delete `.old.bak`.
     * At every instant at least one intact copy of the data exists under a known name, so any
     * failure (or a process kill — see [recoverInterruptedMigration]) is recoverable. On a rename
     * failure the original is rolled back and the converted tmp is left on disk for the retry.
     */
    private fun commitReplace(file: File, tmp: File) {
        fsyncFile(tmp)
        val aside = File("${file.absolutePath}$ASIDE_SUFFIX")
        aside.delete() // stale aside from a completed prior swap ([file] exists, so it's disposable)
        deleteSidecars(file)
        if (!file.renameTo(aside)) {
            error("Could not move ${file.name} aside for replacement — the original is unchanged.")
        }
        if (!tmp.renameTo(file)) {
            if (!aside.renameTo(file)) {
                // Both renames failed; delete nothing — both copies stay on disk for recovery.
                error(
                    "Rename failed and rollback failed — data preserved as ${aside.name} and ${tmp.name}. " +
                    "Restart the app to recover."
                )
            }
            error("Failed to rename ${tmp.name} into place — the original was restored unchanged.")
        }
        fsyncDir(file.parentFile)
        aside.delete()
    }

    /**
     * Repair the on-disk state of [file] after an in-place migration was killed mid-swap.
     * Call sites: index open ([NotesproutIndex.ensureReady]) and the bootstrap Garden sweep.
     *
     *  - [file] present: the swap completed (or never started) — drop a stale `.old.bak` if any.
     *  - [file] missing + `.old.bak` present: killed between the two renames — restore the original.
     *  - [file] missing + only a `*.tmp` present (a pre-`.old.bak` build's delete-then-rename window):
     *    the tmp was verified before the swap began — rename it in. The index may still describe the
     *    pre-migration keying; the self-healing open paths reconcile that on next open.
     *
     * Returns true if anything was renamed back into place.
     */
    fun recoverInterruptedMigration(file: File): Boolean {
        val aside = File("${file.absolutePath}$ASIDE_SUFFIX")
        if (file.exists()) {
            if (aside.exists()) aside.delete()
            return false
        }
        if (aside.exists()) {
            val ok = aside.renameTo(file)
            if (ok) Log.w(TAG, "Recovered ${file.name} from interrupted migration (restored original)")
            return ok
        }
        for (suffix in TMP_SUFFIXES) {
            val tmp = File("${file.absolutePath}$suffix")
            if (tmp.exists() && tmp.length() > 0L && tmp.renameTo(file)) {
                Log.w(TAG, "Recovered ${file.name} from orphaned $suffix (converted copy)")
                return true
            }
        }
        return false
    }

    /** Bootstrap sweep: recover every Garden notebook left mid-swap by a killed migration. */
    fun recoverGardenOrphans(garden: File) {
        val markers = TMP_SUFFIXES + ASIDE_SUFFIX
        garden.listFiles()
            ?.mapNotNull { f ->
                markers.firstOrNull { f.name.endsWith(it) }
                    ?.let { suffix -> File(garden, f.name.removeSuffix(suffix)) }
            }
            ?.distinct()
            ?.forEach { runCatching { recoverInterruptedMigration(it) } }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Single-quote-escape a filesystem path for embedding in an ATTACH statement. */
    private fun sqlPath(file: File): String = file.absolutePath.replace("'", "''")

    private fun deleteSidecars(file: File) {
        listOf("-wal", "-shm", "-journal").forEach { File("${file.absolutePath}$it").delete() }
    }

    private fun fsyncFile(file: File) {
        runCatching { java.io.FileInputStream(file).use { it.fd.sync() } }
    }

    private fun fsyncDir(dir: File?) {
        dir ?: return
        runCatching {
            val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }
    }

    private fun verifyPlaintext(file: File): Boolean {
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Plaintext verification failed", e)
            false
        }
    }
}
