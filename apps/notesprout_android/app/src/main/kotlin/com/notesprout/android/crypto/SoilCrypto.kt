package com.notesprout.android.crypto

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

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

    /** Room SupportFactory for an encrypted .soil. Wire into Room.databaseBuilder when keyed. */
    fun roomFactory(passphrase: String): SupportSQLiteOpenHelper.Factory =
        SupportOpenHelperFactory(keyBytes(passphrase))

    /**
     * Open a plaintext .soil for raw (non-Room) read/write access.
     * Returns the standard android.database.sqlite.SQLiteDatabase — no zetetic involvement.
     * Use this for all plaintext sites (S7/S8). For encrypted raw opens, use openRawEncrypted().
     */
    fun openRawPlaintext(file: File): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)

    /**
     * Open an encrypted .soil for raw (non-Room) read/write access.
     * Returns net.zetetic.database.sqlcipher.SQLiteDatabase — the zetetic type.
     * Callers that need a read-only handle should open with this and treat it as read-only.
     */
    fun openRawEncrypted(file: File, passphrase: String): ZeticDB =
        ZeticDB.openOrCreateDatabase(file, passphrase, null, null)

    /**
     * Verify that a passphrase unlocks the given encrypted .soil.
     * Always closes the connection. Returns false on any failure (wrong key, not a DB, etc.).
     */
    fun verifyPassphrase(file: File, passphrase: String): Boolean {
        return try {
            val db = ZeticDB.openOrCreateDatabase(file, passphrase, null, null)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            db.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
