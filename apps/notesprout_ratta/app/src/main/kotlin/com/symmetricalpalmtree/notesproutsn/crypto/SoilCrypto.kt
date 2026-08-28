package com.symmetricalpalmtree.notesproutsn.crypto

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesproutsn.data.NonDestructiveOpenHelperFactory
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/** What a probe of a database file found. SN has no plaintext mode: the one plaintext open in the
 *  app is [ExportKeying]'s read-only acceptance check of its own transform output (arc 15 / E2). */
enum class SoilFileKind { Plaintext, Encrypted, Invalid }

/**
 * Single canonical crypto-aware open helper — the encryption analogue of `soilFile()`.
 *
 * Every SQLCipher open in SN (index or `.soil`) routes through here. Never construct a
 * [SupportOpenHelperFactory] or open a zetetic [ZeticDB] anywhere else.
 *
 * Rules that keep the data-loss bug family out:
 *  - Every Room factory is wrapped in [NonDestructiveOpenHelperFactory] — a wrong key reports
 *    corruption **without** deleting the file.
 *  - Every *open* requires the file to exist and be non-empty ([requireExisting]). The underlying
 *    opens are create-capable; pointed at a missing path they would fabricate an empty database
 *    that masquerades as the real one. Creation has its own explicitly named entry point, used
 *    only by the new-index bootstrap and the new-notebook flow.
 *  - Key encoding: passphrase to UTF-8 bytes ([keyBytes]). Identical on every platform, so a file
 *    opens with the same passphrase on any stock SQLCipher 4 build (default KDF, default page
 *    size — never customise `kdf_iter` / `cipher_page_size`).
 */
object SoilCrypto {

    /** Canonical passphrase to key-bytes encoding. Must be UTF-8; do not change. */
    fun keyBytes(passphrase: String): ByteArray = passphrase.toByteArray(Charsets.UTF_8)

    // ── Room factories (Room itself is create-capable — callers guard existence) ──

    /** Room factory keyed by the passphrase (native KDF on this connection). Non-destructive. */
    fun roomFactory(passphrase: String): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(SupportOpenHelperFactory(keyBytes(passphrase)))

    /** Room factory keyed by a pre-derived raw key (KDF skipped). Non-destructive. */
    fun roomFactoryRawKey(rawKey: ByteArray): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(
            SupportOpenHelperFactory(RawKeyDerivation.rawKeyLiteral(rawKey).toByteArray(Charsets.US_ASCII))
        )

    // ── Raw (non-Room) opens — exists-guarded ────────────────────────────────

    /** Raw encrypted open with the passphrase. [file] must exist and be non-empty. */
    fun openRaw(file: File, passphrase: String): ZeticDB {
        requireExisting(file)
        return ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
    }

    /** Raw encrypted open with a raw key (KDF skipped). [file] must exist and be non-empty. */
    fun openRawKey(file: File, rawKey: ByteArray): ZeticDB {
        requireExisting(file)
        return ZeticDB.openOrCreateDatabase(file, RawKeyDerivation.rawKeyLiteral(rawKey), null, null)
    }

    /** True iff [passphrase] opens [file]. False for a missing/empty file (a create-capable open
     *  would otherwise mint an empty DB keyed to whatever was typed and "verify" against nothing). */
    fun verifyPassphrase(file: File, passphrase: String): Boolean =
        verifyWith { openRaw(file, passphrase) }

    /** True iff [rawKey] opens [file]. Same missing-file rule as [verifyPassphrase]. */
    fun verifyRawKey(file: File, rawKey: ByteArray): Boolean =
        verifyWith { openRawKey(file, rawKey) }

    private inline fun verifyWith(open: () -> ZeticDB): Boolean = try {
        val db = open()
        try {
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            true
        } finally {
            runCatching { db.close() }
        }
    } catch (_: Exception) {
        false
    }

    /** Throws [SoilLockedException] unless [file] exists and is non-empty. */
    fun requireExisting(file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw SoilLockedException("Database file is missing or empty: ${file.name}")
        }
    }

    // ── Creation-only open ───────────────────────────────────────────────────
    // The ONLY raw path allowed to bring a database file into existence.

    /** Create a brand-new encrypted database at [file] (raw handle) — new-notebook bootstrap only.
     *  Refuses to touch an existing non-empty file: creation is never a repair. */
    fun createRaw(file: File, passphrase: String): ZeticDB {
        require(!file.exists() || file.length() == 0L) { "refusing to create over an existing file: ${file.name}" }
        file.parentFile?.mkdirs()
        return ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
    }

    // ── Probe ────────────────────────────────────────────────────────────────

    /**
     * Header-only probe. Plaintext SQLite starts with the 16-byte magic `SQLite format 3` + NUL;
     * SQLCipher encrypts the whole first page so the magic is absent. Never opens the file.
     * Missing / empty / unreadable / short → [SoilFileKind.Invalid].
     */
    fun probe(file: File): SoilFileKind {
        if (!file.exists() || file.length() == 0L) return SoilFileKind.Invalid
        val header = ByteArray(SQLITE_MAGIC.size)
        val n = try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            return SoilFileKind.Invalid
        }
        if (n < header.size) return SoilFileKind.Invalid
        return if (header.contentEquals(SQLITE_MAGIC)) SoilFileKind.Plaintext else SoilFileKind.Encrypted
    }

    /** "SQLite format 3" followed by a NUL — 16 bytes. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}

/** Thrown when a database is asked to open but cannot be — missing file, or no key resolved. */
class SoilLockedException(message: String) : RuntimeException(message)
