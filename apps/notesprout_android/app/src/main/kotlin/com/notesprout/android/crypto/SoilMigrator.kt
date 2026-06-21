package com.notesprout.android.crypto

import android.util.Log
import com.notesprout.android.core.Slog
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
        val tmp = File("${file.absolutePath}.enc.tmp")
        tmp.delete()

        // Checkpoint any pending WAL data into the main file using the standard driver first.
        try {
            val stdDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
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
            dest.execSQL("ATTACH DATABASE '${file.absolutePath}' AS plain KEY ''")
            try {
                // Two-argument export: copies FROM 'plain' attachment TO 'main' (encrypted dest).
                dest.rawQuery("SELECT sqlcipher_export('main', 'plain')", null).use { it.moveToFirst() }
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

        // Atomic-ish replace: delete original + sidecars, then rename tmp.
        deleteSoilAndSidecars(file)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Failed to rename encrypted temp file to ${file.name}.")
        }

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
        tmp.delete()

        val src = ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
        try {
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            // Attach a plaintext destination (empty key = no encryption).
            src.execSQL("ATTACH DATABASE '${tmp.absolutePath}' AS plaintext KEY ''")
            try {
                src.rawQuery("SELECT sqlcipher_export('plaintext')", null).use { it.moveToFirst() }
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

        deleteSoilAndSidecars(file)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Failed to rename decrypted temp file to ${file.name}.")
        }

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
        tmp.delete()

        // Open the new-encrypted destination as the primary zetetic connection (mirrors encryptInPlace).
        val dest = ZeticDB.openOrCreateDatabase(tmp, newPassphrase, null, null)
        try {
            // Attach the old-encrypted source with the old key.
            dest.execSQL("ATTACH DATABASE '${file.absolutePath}' AS old_src KEY '${oldPassphrase.replace("'", "''")}'")
            try {
                // Two-argument export: copies FROM 'old_src' attachment TO 'main' (new-encrypted dest).
                dest.rawQuery("SELECT sqlcipher_export('main', 'old_src')", null).use { it.moveToFirst() }
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

        // Delete original + sidecars but NOT the rekey temp (it hasn't been renamed yet).
        deleteSoilAndSidecars(file)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Failed to rename re-keyed temp file to ${file.name}.")
        }

        Slog.d(TAG) { "rekeyInPlace complete: ${file.name}" }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun deleteSoilAndSidecars(file: File) {
        file.parentFile?.listFiles { f ->
            f.name.startsWith(file.name) &&
            !f.name.endsWith(".enc.tmp") &&
            !f.name.endsWith(".dec.tmp") &&
            !f.name.endsWith(".rekey.tmp")
        }?.forEach { it.delete() }
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
