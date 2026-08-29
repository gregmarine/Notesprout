package com.symmetricalpalmtree.notesproutsn.crypto

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * **The import keying transform** (arc 16 / I1) — [ExportKeying]'s mirror, and the reason the
 * arc-16 wizard could drop og's import keying chooser entirely: SN only ever opens files under
 * this device's global key, so **every accepted import is re-keyed to it**, unconditionally.
 * Plaintext is encrypted; a foreign passphrase (another device's GLOBAL export, or a NOTEBOOK
 * re-keyed export) is re-keyed; a file the global passphrase already opens (a same-device Keep
 * export) passes through untouched — a pure copy needs no transform.
 *
 * The mechanism is [ExportKeying]'s, in the inward direction: **export-and-key, never
 * `PRAGMA rekey`** (og's recorded on-device finding), with **the destination always the primary
 * connection** — created under the global key by [SoilCrypto.createRaw], the incoming file
 * attached (with its passphrase, or `KEY ''` for plaintext), and the two-argument
 * `sqlcipher_export('main', 'old_src')` copying attachment → main. The two things
 * `sqlcipher_export` does not do are done by hand, both og traps pinned in [ExportKeying]:
 * `PRAGMA user_version` is copied explicitly and re-verified from the finished file, and
 * `notebook_meta` is restamped (`encrypted: true`, `keyScope: GLOBAL`) so the file describes its
 * own new keying. Nothing is accepted unverified — the output must probe [SoilFileKind.Encrypted],
 * open under the global passphrase, answer `PRAGMA integrity_check` with `ok`, and hold the
 * source's `user_version`.
 *
 * Everything runs **only in the import cache** (`cacheDir/import/`): the transform writes a
 * sibling of [incoming] and a failure deletes only that unaccepted sibling — never the incoming
 * copy, and the Garden is not in sight yet (never-delete-on-corruption covers the temps too).
 * The incoming bytes are untrusted; the acceptance reads answer for them before anything
 * downstream may.
 *
 * Passphrases appear here only as SQL string literals on a local connection
 * ([ExportKeying.sqlLiteral] — pure, pinned by test) and are never logged, never in a name,
 * never in a message.
 */
object ImportKeying {

    private const val TAG = "ImportKeying"

    /** How the incoming file opens — decided by the pipeline's probe + verify (and, for a foreign
     *  file, the user's typed passphrase). */
    sealed interface Opening {
        /** Probed plaintext — no key at all. */
        data object Plaintext : Opening

        /** Probed encrypted and [passphrase] verified against it. */
        data class Encrypted(val passphrase: String) : Opening
    }

    /**
     * Return a file in the import cache that opens under [globalPassphrase]: [incoming] itself
     * when it already does, an accepted re-keyed sibling otherwise. IO throughout; throws
     * [IllegalStateException] with a path-free message on any failure, deleting only its own
     * unaccepted output.
     */
    suspend fun toGlobal(incoming: File, opening: Opening, globalPassphrase: String): File =
        when (opening) {
            is Opening.Encrypted ->
                if (opening.passphrase == globalPassphrase) {
                    Slog.d(TAG) { "already under the device key — pure pass-through" }
                    incoming
                } else {
                    transform(incoming, attachKeyLiteral = ExportKeying.sqlLiteral(opening.passphrase), globalPassphrase)
                }
            Opening.Plaintext ->
                transform(incoming, attachKeyLiteral = "''", globalPassphrase)
        }

    /**
     * The one transform both cases share — only the ATTACH key differs. [attachKeyLiteral] is
     * already SQL-quoted ([ExportKeying.sqlLiteral]; `''` **is** how a plaintext key is spelled).
     */
    private suspend fun transform(
        incoming: File,
        attachKeyLiteral: String,
        globalPassphrase: String,
    ): File = withContext(Dispatchers.IO) {
        val out = sibling(incoming)
        val sourceVersion: Long
        val dest = try {
            SoilCrypto.createRaw(out, globalPassphrase)
        } catch (e: Exception) {
            Log.w(TAG, "keyed destination could not be created: ${e.javaClass.simpleName}")
            throw IllegalStateException("the imported copy could not be made")
        }
        try {
            dest.execSQL("ATTACH DATABASE ${ExportKeying.sqlLiteral(incoming.path)} AS old_src KEY $attachKeyLiteral")
            try {
                sourceVersion = queryLong(dest, "PRAGMA old_src.user_version")
                dest.rawQuery("SELECT sqlcipher_export('main', 'old_src')", null).use { it.moveToFirst() }
                copyUserVersion(dest, sourceVersion)
                restampMeta(dest)
            } finally {
                dest.execSQL("DETACH DATABASE old_src")
            }
        } catch (e: Exception) {
            rejectOutput(out)
            Log.w(TAG, "import keying transform failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the imported copy could not be made")
        } finally {
            runCatching { dest.close() }
        }

        // Acceptance: really encrypted, really opens under the device key, really intact, really
        // the source's schema version. Untrusted input earns every one of these.
        if (SoilCrypto.probe(out) != SoilFileKind.Encrypted) {
            rejectOutput(out)
            throw IllegalStateException("the imported copy did not come out encrypted")
        }
        val check = try {
            SoilCrypto.openRaw(out, globalPassphrase)
        } catch (e: Exception) {
            rejectOutput(out)
            Log.w(TAG, "acceptance open failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the imported copy could not be read back")
        }
        try {
            requireIntact(queryString(check, "PRAGMA integrity_check"))
            requireVersion(queryLong(check, "PRAGMA main.user_version"), sourceVersion)
        } catch (e: Exception) {
            rejectOutput(out)
            throw e
        } finally {
            runCatching { check.close() }
        }
        Slog.d(TAG) { "import keying accepted (${out.length()} bytes)" }
        out
    }

    // ── Plumbing (ExportKeying's shapes; the pure pieces are reused, the tiny ones local) ─────

    /** `sqlcipher_export` copies data, not `PRAGMA user_version` — carry it over by hand. */
    private fun copyUserVersion(db: ZeticDB, version: Long) {
        db.execSQL("PRAGMA main.user_version = $version")
    }

    /** Restamp the output's meta to its new truth: encrypted under this device's GLOBAL scope.
     *  Best effort, like every meta write — a row that is missing or will not parse is logged and
     *  skipped (the pipeline's own post-import refresh rewrites the row properly anyway). */
    private fun restampMeta(db: ZeticDB) {
        try {
            val json = db.rawQuery("SELECT json FROM main.notebook_meta WHERE id = 0", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return
            val stamped = ExportKeying.restampedJson(json, encrypted = true, keyScope = KEY_SCOPE_GLOBAL) ?: return
            db.execSQL("UPDATE main.notebook_meta SET json = ${ExportKeying.sqlLiteral(stamped)} WHERE id = 0")
        } catch (e: Exception) {
            Log.w(TAG, "meta restamp skipped: ${e.javaClass.simpleName}")
        }
    }

    private fun requireIntact(integrity: String?) {
        if (integrity != "ok") {
            Log.w(TAG, "integrity check answered ${integrity?.take(40) ?: "nothing"}")
            throw IllegalStateException("the imported copy failed its integrity check")
        }
    }

    /** The og trap, pinned at acceptance too: a version-less import reads as garbage. */
    private fun requireVersion(actual: Long, expected: Long) {
        if (actual != expected) {
            Log.w(TAG, "user_version is $actual, source said $expected")
            throw IllegalStateException("the imported copy lost its schema version")
        }
    }

    /** The output path beside its input, with any leftovers of an earlier aborted run — sidecars
     *  included — cleared first. Deleting an *unaccepted own output* is the one deletion this
     *  object is allowed. */
    private fun sibling(incoming: File): File {
        val out = File(incoming.parentFile, "${incoming.nameWithoutExtension}-keyed.soil")
        rejectOutput(out)
        return out
    }

    /** Remove an output that was never accepted, and its sidecars. Never pointed at an input. */
    private fun rejectOutput(out: File) {
        out.parentFile?.listFiles { f -> f.name.startsWith(out.name) }?.forEach { runCatching { it.delete() } }
    }

    private fun queryLong(db: ZeticDB, sql: String): Long =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    private fun queryString(db: ZeticDB, sql: String): String? =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
