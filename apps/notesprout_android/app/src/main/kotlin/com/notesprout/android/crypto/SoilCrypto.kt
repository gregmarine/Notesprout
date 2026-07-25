package com.notesprout.android.crypto

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

enum class SoilFileKind { Plaintext, Encrypted, Invalid }

/**
 * Single canonical crypto-aware open helper — the encryption analogue of soilFile().
 *
 * Every .soil open that may be encrypted routes through here. Never construct a
 * SupportOpenHelperFactory or open a zetetic SQLiteDatabase outside this object.
 *
 * Key encoding: passphrase string → UTF-8 bytes. This encoding must be identical on every
 * platform for portability — a notebook encrypted on Android opens with the same passphrase
 * on any stock SQLCipher build using the same default KDF parameters.
 *
 * Type split: plaintext opens return android.database.sqlite.SQLiteDatabase (unchanged from
 * today's behavior). Encrypted raw opens return net.zetetic.database.sqlcipher.SQLiteDatabase.
 * Do NOT try to unify these under a single return type — callers branch by encryption state and
 * use the typed helper that matches.
 */
object SoilCrypto {

    /** Canonical passphrase → key bytes encoding. Must be UTF-8; do not change. */
    fun keyBytes(passphrase: String): ByteArray = passphrase.toByteArray(Charsets.UTF_8)

    /** Room SupportFactory for an encrypted .soil. Wire into Room.databaseBuilder when keyed.
     *  Wrapped so a wrong-key open reports corruption WITHOUT deleting the file (data-loss guard). */
    fun roomFactory(passphrase: String): SupportSQLiteOpenHelper.Factory =
        com.notesprout.android.data.NonDestructiveOpenHelperFactory(
            SupportOpenHelperFactory(keyBytes(passphrase))
        )

    // ── Raw-key open path (derive-once cache) ────────────────────────────────
    // These take a pre-derived 32-byte key ([RawKeyDerivation]/[KeyMaterial]) and open via
    // `PRAGMA key = "x'<hex>'"`, which SQLCipher applies directly and skips the KDF. Same file,
    // same passphrase-derived key — just without paying PBKDF2 on every open.

    /** Room SupportFactory that opens with a raw key (KDF skipped).
     *  Also non-destructive: a wrong raw key must never delete-and-recreate the index/notebook. */
    fun roomFactoryRawKey(rawKey: ByteArray): SupportSQLiteOpenHelper.Factory =
        com.notesprout.android.data.NonDestructiveOpenHelperFactory(
            SupportOpenHelperFactory(RawKeyDerivation.rawKeyLiteral(rawKey).toByteArray(Charsets.US_ASCII))
        )

    /** Raw (non-Room) encrypted open with a raw key (KDF skipped). [file] must already exist —
     *  the zetetic open is create-capable, and fabricating an empty DB at a missing path both
     *  masquerades as the real notebook and blocks manual recovery of any orphaned copy. */
    fun openRawEncryptedRawKey(file: File, rawKey: ByteArray): ZeticDB {
        requireExisting(file)
        return ZeticDB.openOrCreateDatabase(file, RawKeyDerivation.rawKeyLiteral(rawKey), null, null)
    }

    /** Verify a raw key opens [file]. Always closes; false on any failure or a missing file
     *  (the create-capable open would otherwise mint an empty DB that "verifies"). */
    fun verifyRawKey(file: File, rawKey: ByteArray): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val db = openRawEncryptedRawKey(file, rawKey)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            db.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open a plaintext .soil for raw (non-Room) read/write access.
     * Returns the standard android.database.sqlite.SQLiteDatabase — no zetetic involvement.
     * Use this for all plaintext sites (S7/S8). For encrypted raw opens, use openRawEncrypted().
     *
     * Opened with [NonDeletingErrorHandler] — the framework default would DELETE the file on a
     * corruption report, which is how an encrypted notebook opened without a key gets destroyed.
     */
    fun openRawPlaintext(file: File): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path,
            null,
            android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY,
            NonDeletingErrorHandler,
        )

    /**
     * Open an encrypted .soil for raw (non-Room) read/write access. [file] must already exist
     * (see [openRawEncryptedRawKey]).
     * Returns net.zetetic.database.sqlcipher.SQLiteDatabase — the zetetic type.
     * Callers that need a read-only handle should open with this and treat it as read-only.
     */
    fun openRawEncrypted(file: File, passphrase: String): ZeticDB {
        requireExisting(file)
        return ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
    }

    /**
     * Verify that a passphrase unlocks the given encrypted .soil.
     * Always closes the connection. Returns false on any failure (wrong key, not a DB, etc.) and
     * on a missing/empty file — the create-capable zetetic open would otherwise mint an empty DB
     * keyed to whatever was typed and report success against a notebook that isn't there.
     */
    fun verifyPassphrase(file: File, passphrase: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val db = ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            db.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun requireExisting(file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw SoilLockedException("Notebook file is missing or empty: ${file.name}")
        }
    }

    // ── Creation-only opens (new-notebook bootstrap) ─────────────────────────
    // The ONLY paths allowed to create a .soil. Everything else goes through the exists-guarded
    // opens above — a create-capable open pointed at a missing notebook fabricates an empty stub.

    /** Create (or reopen) a brand-new encrypted .soil at [file] — new-notebook bootstrap only. */
    fun createRawEncrypted(file: File, passphrase: String): ZeticDB =
        ZeticDB.openOrCreateDatabase(file, passphrase, null, null)

    /** Create (or reopen) a brand-new plaintext .soil at [file] — new-notebook bootstrap only.
     *  Non-deleting handler: even a creation open must never let a corruption report delete. */
    fun createRawPlaintext(file: File): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file.path, null, NonDeletingErrorHandler)

    /**
     * Open a .soil for raw (non-Room) access, dispatching to the plaintext or encrypted path.
     * Returns a [SoilRawDb] that wraps whichever underlying type is needed.
     * Plaintext pass: passphrase == null → android.database.sqlite.SQLiteDatabase.
     * Encrypted pass: passphrase != null → net.zetetic.database.sqlcipher.SQLiteDatabase.
     */
    fun openRaw(file: File, passphrase: String?): SoilRawDb {
        // Every openRaw caller targets an existing notebook. A missing file must fail loudly —
        // both underlying opens are create-capable and would silently leave an empty stub .soil.
        requireExisting(file)
        if (passphrase == null) {
            // A null key means "this notebook is plaintext". If the file on disk says otherwise,
            // the key simply wasn't resolved (KeySession miss on a non-foreground notebook) — do
            // NOT open it as plaintext. SQLite would read ciphertext as a corrupt database, and
            // historically that wiped the notebook. Fail loudly; the file stays intact.
            if (probe(file) == SoilFileKind.Encrypted) {
                throw SoilLockedException(
                    "Refusing to open an encrypted .soil as plaintext (no key resolved): ${file.name}"
                )
            }
            return SoilRawDb.Plaintext(openRawPlaintext(file))
        }
        return SoilRawDb.Encrypted(openRawEncrypted(file, passphrase))
    }

    /**
     * Probe a file to determine whether it is a plaintext .soil, an encrypted .soil, or invalid.
     *
     * SQLite plaintext files always start with the 16-byte magic "SQLite format 3\0".
     * SQLCipher encrypts the entire first page (including the header), so that magic is absent —
     * the standard Android SQLite driver would open the file without error but see only garbage.
     * Checking the magic first avoids that false-positive.
     *
     * A definitive encrypted-vs-garbage distinction requires the passphrase (deferred to S3).
     */
    fun probe(file: File): SoilFileKind {
        if (!file.exists() || file.length() == 0L) return SoilFileKind.Invalid
        val magic = "SQLite format 3 ".toByteArray(Charsets.US_ASCII)
        val header = ByteArray(magic.size)
        try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            return SoilFileKind.Invalid
        }
        if (!header.contentEquals(magic)) return SoilFileKind.Encrypted
        return try {
            // Non-deleting open: a genuinely damaged plaintext .soil must not be wiped by a probe.
            val db = openRawPlaintext(file)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            db.close()
            SoilFileKind.Plaintext
        } catch (_: Exception) {
            SoilFileKind.Encrypted
        }
    }
}

/**
 * Thrown when a `.soil` is encrypted but no key was resolved for it.
 *
 * Callers that already treat "cannot read" as "nothing to show" (page lists, thumbnails, pickers)
 * catch this and degrade to an empty/locked state. The important part is what it replaces: opening
 * the file as plaintext, which let the framework's corruption handler delete the notebook.
 */
class SoilLockedException(message: String) : RuntimeException(message)

/**
 * Corruption handler for raw framework-SQLite opens that refuses to delete the database.
 *
 * The raw analogue of [com.notesprout.android.data.NonDestructiveOpenHelperFactory], which covers
 * only the Room path. [android.database.DefaultDatabaseErrorHandler] deletes and recreates the file
 * on a corruption report — for a `.soil` opened with the wrong key (or none) that destroys the
 * user's notebook and leaves an empty stub behind.
 */
internal object NonDeletingErrorHandler : android.database.DatabaseErrorHandler {
    override fun onCorruption(db: android.database.sqlite.SQLiteDatabase) {
        val path = try { db.path } catch (_: Exception) { "?" }
        android.util.Log.e(
            "SoilCrypto",
            "Corruption reported on raw open of $path — refusing to delete, file left intact",
        )
        try { db.close() } catch (_: Exception) { }
        throw android.database.sqlite.SQLiteDatabaseCorruptException(
            "Raw .soil open reported corruption; refusing to delete the file"
        )
    }
}

/**
 * Thin sealed wrapper over the two raw DB types, providing a common API for the operations
 * used by PageCopier and page-list loaders. Neither type shares a supertype so this acts as
 * the bridge. Only expose the methods actually needed — don't grow this surface.
 */
sealed class SoilRawDb {

    abstract fun rawQuery(sql: String, selectionArgs: Array<String>?): android.database.Cursor
    abstract fun beginTransaction()
    abstract fun endTransaction()
    abstract fun setTransactionSuccessful()
    abstract fun close()
    abstract fun update(table: String, values: ContentValues, whereClause: String, whereArgs: Array<String>?): Int
    abstract fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long

    /** Flush the WAL back into the main DB file and reclaim free pages. Call before close(). */
    fun checkpointAndVacuum() {
        rawQuery("PRAGMA incremental_vacuum", null).use { it.moveToFirst() }
        rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    class Plaintext(private val db: android.database.sqlite.SQLiteDatabase) : SoilRawDb() {
        override fun rawQuery(sql: String, selectionArgs: Array<String>?) = db.rawQuery(sql, selectionArgs)
        override fun beginTransaction() = db.beginTransaction()
        override fun endTransaction() = db.endTransaction()
        override fun setTransactionSuccessful() = db.setTransactionSuccessful()
        override fun close() = db.close()
        override fun update(table: String, values: ContentValues, whereClause: String, whereArgs: Array<String>?) =
            db.update(table, values, whereClause, whereArgs)
        override fun insert(table: String, nullColumnHack: String?, values: ContentValues) =
            db.insert(table, nullColumnHack, values)
    }

    class Encrypted(private val db: ZeticDB) : SoilRawDb() {
        override fun rawQuery(sql: String, selectionArgs: Array<String>?) = db.rawQuery(sql, selectionArgs)
        override fun beginTransaction() = db.beginTransaction()
        override fun endTransaction() = db.endTransaction()
        override fun setTransactionSuccessful() = db.setTransactionSuccessful()
        override fun close() = db.close()
        override fun update(table: String, values: ContentValues, whereClause: String, whereArgs: Array<String>?) =
            db.update(table, values, whereClause, whereArgs)
        override fun insert(table: String, nullColumnHack: String?, values: ContentValues) =
            db.insert(table, nullColumnHack, values)
    }
}
