package com.symmetricalpalmtree.notesproutsn.crypto

import android.database.DatabaseErrorHandler
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_NOTEBOOK
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * **The keying transforms** (arc 15 / E2) — the host-side half of the reserved
 * [ExporterContract.OPTION_KEYING] option, and the reason a typed passphrase never crosses the
 * exporter seam: the whole transform runs here, beside [SoilCrypto], and the exporter only ever
 * streams a finished artifact.
 *
 * Both transforms operate **only on the cache artifact** ([com.symmetricalpalmtree.notesproutsn.export.ExportArtifact]'s
 * copy) and write a **sibling** output file in the same cache directory — the Garden file is
 * never touched, the artifact itself is never mutated, and a failure anywhere leaves both exactly
 * as they were (the never-delete-on-corruption rule covers the temps too: a transform that fails
 * deletes only its **own** unaccepted output, never its input).
 *
 * The mechanism is **export-and-key, not `PRAGMA rekey`** — og's recorded on-device finding
 * (`SoilMigrator.rekeyInPlace`): rekey was unreliable on device, so both directions run
 * `sqlcipher_export` between an attached database and the primary connection, in og's proven
 * orientation — **the destination is always the primary connection** (opening plaintext as the
 * primary zetetic connection with an empty key does not expose data reliably, so the plain
 * transform keeps the encrypted source primary and attaches the plaintext destination instead).
 *
 * Three things `sqlcipher_export` does **not** do are done by hand, and each is a recorded og trap:
 *
 *  - **`PRAGMA user_version` is not copied.** Without it the output is version 0 and Room on the
 *    importing side reads an old-schema file as a brand-new database and refuses it — og's bricked
 *    G6 notebooks. [copyUserVersion] carries it over inside the same connection, and acceptance
 *    re-reads it from the finished file and refuses a mismatch.
 *  - **`notebook_meta` still describes the source's keying.** The output's single meta row is
 *    restamped ([restampMeta] — og's fix, best effort like the refresh at prepare) so a plaintext
 *    export does not claim `encrypted: true` and a re-keyed one claims its own scope, not GLOBAL.
 *  - **Nothing is accepted unverified.** The output must probe as the kind it claims to be
 *    ([SoilFileKind]), open, answer `PRAGMA integrity_check` with `ok`, and hold the source's
 *    `user_version` — only then is it handed back. An output that fails any of that is deleted
 *    and the transform throws; the caller shows a problem dialog and the export never reaches
 *    the destination.
 *
 * Passphrases appear here only as SQL string literals on a local connection ([sqlLiteral] — the
 * escaping is pure and pinned by test) and are never logged, never in a name, never in a message.
 */
object ExportKeying {

    private const val TAG = "ExportKeying"

    /** What the host must run before the fds are opened. The exporter never sees the difference —
     *  every plan ends in the same streamed copy of whatever file this object accepted. */
    enum class Plan { KEEP, REKEY, PLAIN }

    /**
     * Map the spec's keying value onto a transform. Absent means the default, which is Keep — the
     * same defaulting the exporter's own `SoilExportSpec.keying` applies on its side of the seam.
     *
     * @throws IllegalArgumentException for a keying value nothing declared, or a rekey with no
     *   passphrase collected — both are host bugs surfaced loudly, not user states: the screen
     *   cannot arm rekey without non-empty matching fields.
     */
    fun plan(keying: String?, hasNewPassphrase: Boolean): Plan = when (keying) {
        null, ExporterContract.KEYING_KEEP -> Plan.KEEP
        ExporterContract.KEYING_REKEY -> {
            require(hasNewPassphrase) { "rekey planned with no passphrase collected" }
            Plan.REKEY
        }
        ExporterContract.KEYING_PLAIN -> Plan.PLAIN
        else -> throw IllegalArgumentException("unknown keying value")
    }

    /**
     * Run [plan] over the prepared [artifact] (encrypted under [passphrase]) and return the file
     * to stream — the artifact itself for [Plan.KEEP], an accepted sibling output otherwise.
     * IO throughout; throws [IllegalStateException] with a path-free message on any failure.
     */
    suspend fun apply(artifact: File, passphrase: String, plan: Plan, newPassphrase: String?): File =
        when (plan) {
            Plan.KEEP -> artifact
            Plan.REKEY -> rekey(artifact, passphrase, checkNotNull(newPassphrase) { "rekey with no passphrase" })
            Plan.PLAIN -> plain(artifact, passphrase)
        }

    // ── The two transforms ───────────────────────────────────────────────────

    /**
     * Decrypt [artifact] into a plaintext sibling. og's `decryptInPlace` orientation: the
     * encrypted source stays the primary connection, the plaintext destination is attached with
     * `KEY ''`, and the one-argument `sqlcipher_export` copies main → attachment.
     */
    private suspend fun plain(artifact: File, passphrase: String): File = withContext(Dispatchers.IO) {
        val out = sibling(artifact, "plain")
        val sourceVersion: Long
        val src = openArtifact(artifact, passphrase)
        try {
            sourceVersion = queryLong(src, "PRAGMA main.user_version")
            src.execSQL("ATTACH DATABASE ${sqlLiteral(out.path)} AS plaintext KEY ''")
            try {
                src.rawQuery("SELECT sqlcipher_export('plaintext')", null).use { it.moveToFirst() }
                copyUserVersion(src, from = "main", to = "plaintext")
                restampMeta(src, schema = "plaintext", encrypted = false, keyScope = null)
            } finally {
                src.execSQL("DETACH DATABASE plaintext")
            }
        } catch (e: Exception) {
            rejectOutput(out)
            Log.w(TAG, "plain transform failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the plaintext copy could not be made")
        } finally {
            runCatching { src.close() }
        }

        // Acceptance: really plaintext, really intact, really the source's schema version.
        if (SoilCrypto.probe(out) != SoilFileKind.Plaintext) {
            rejectOutput(out)
            throw IllegalStateException("the plaintext copy did not come out plaintext")
        }
        verifyPlaintext(out, sourceVersion)
        Slog.d(TAG) { "plain transform accepted (${out.length()} bytes)" }
        out
    }

    /**
     * Re-key [artifact] to [newPassphrase] in a sibling. og's `rekeyInPlace` orientation: the
     * new-keyed destination is the primary connection, the old-keyed source is attached with the
     * old key, and the two-argument `sqlcipher_export('main', 'old_src')` copies attachment → main.
     */
    private suspend fun rekey(artifact: File, passphrase: String, newPassphrase: String): File =
        exportAndKeyToPrimary(
            out = sibling(artifact, "rekeyed"),
            sourcePath = artifact.path,
            attachKeyLiteral = sqlLiteral(passphrase),
            destPassphrase = newPassphrase,
            keyScope = KEY_SCOPE_NOTEBOOK,
            what = "re-keyed",
        )

    /**
     * **The destination-primary export-and-key core, shared with
     * [ImportKeying]** (the I2 review's dedup finding — this family of transforms has a recorded
     * history of on-device traps, and a fix that lands in one copy and not the other is exactly the
     * sibling-copy trap): create [out] under [destPassphrase] as the primary connection, attach
     * [sourcePath] with [attachKeyLiteral] (already SQL-quoted — `''` **is** how a plaintext key is
     * spelled), `sqlcipher_export('main', 'old_src')`, carry `user_version` by hand, restamp the
     * meta to `encrypted: true` / [keyScope] — then accept nothing unverified: the output must
     * probe [SoilFileKind.Encrypted], open under [destPassphrase], answer `integrity_check` with
     * `ok` and hold the source's version. A failure deletes only the unaccepted output and throws
     * with a path-free message built on [what].
     */
    internal suspend fun exportAndKeyToPrimary(
        out: File,
        sourcePath: String,
        attachKeyLiteral: String,
        destPassphrase: String,
        keyScope: String?,
        what: String,
    ): File = withContext(Dispatchers.IO) {
        val sourceVersion: Long
        val dest = try {
            SoilCrypto.createRaw(out, destPassphrase)
        } catch (e: Exception) {
            Log.w(TAG, "$what destination could not be created: ${e.javaClass.simpleName}")
            throw IllegalStateException("the $what copy could not be made")
        }
        try {
            dest.execSQL("ATTACH DATABASE ${sqlLiteral(sourcePath)} AS old_src KEY $attachKeyLiteral")
            try {
                sourceVersion = queryLong(dest, "PRAGMA old_src.user_version")
                dest.rawQuery("SELECT sqlcipher_export('main', 'old_src')", null).use { it.moveToFirst() }
                copyUserVersion(dest, from = "old_src", to = "main")
                restampMeta(dest, schema = "main", encrypted = true, keyScope = keyScope)
            } finally {
                dest.execSQL("DETACH DATABASE old_src")
            }
        } catch (e: Exception) {
            rejectOutput(out)
            Log.w(TAG, "$what transform failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the $what copy could not be made")
        } finally {
            runCatching { dest.close() }
        }

        // Acceptance: the destination passphrase opens it, it is intact, and the version travelled.
        if (SoilCrypto.probe(out) != SoilFileKind.Encrypted) {
            rejectOutput(out)
            throw IllegalStateException("the $what copy did not come out encrypted")
        }
        val check = openArtifact(out, destPassphrase)
        try {
            requireIntact(queryString(check, "PRAGMA integrity_check"))
            requireVersion(queryLong(check, "PRAGMA main.user_version"), sourceVersion)
        } catch (e: Exception) {
            rejectOutput(out)
            throw e
        } finally {
            runCatching { check.close() }
        }
        Slog.d(TAG) { "$what transform accepted (${out.length()} bytes)" }
        out
    }

    // ── Acceptance ───────────────────────────────────────────────────────────

    /**
     * The plaintext acceptance open. The one place SN reads a plaintext database — its own
     * transform's output in its own cache, read-only, before anyone else is allowed to see it.
     * The framework's default error handler **deletes** a database it deems corrupt, so the no-op
     * handler is not optional even on a read-only open of a temp.
     */
    private fun verifyPlaintext(out: File, sourceVersion: Long) {
        val db = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                out.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY, KeepFileErrorHandler,
            )
        } catch (e: Exception) {
            rejectOutput(out)
            Log.w(TAG, "plaintext verify open failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the plaintext copy could not be read back")
        }
        try {
            requireIntact(db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            })
            val version = db.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
            requireVersion(version, sourceVersion)
        } catch (e: Exception) {
            rejectOutput(out)
            throw e
        } finally {
            runCatching { db.close() }
        }
    }

    private fun requireIntact(integrity: String?) {
        if (integrity != "ok") {
            Log.w(TAG, "integrity check answered ${integrity?.take(40) ?: "nothing"}")
            throw IllegalStateException("the copy failed its integrity check")
        }
    }

    /** The og trap, pinned at acceptance too: a version-less export imports as garbage. */
    private fun requireVersion(actual: Long, expected: Long) {
        if (actual != expected) {
            Log.w(TAG, "user_version is $actual, source said $expected")
            throw IllegalStateException("the copy lost its schema version")
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** `sqlcipher_export` copies data, not `PRAGMA user_version` — carry it over by hand
     *  (og's recorded finding; without it the output is version 0). */
    private fun copyUserVersion(db: ZeticDB, from: String, to: String) {
        val version = queryLong(db, "PRAGMA $from.user_version")
        db.execSQL("PRAGMA $to.user_version = $version")
    }

    /**
     * Rewrite the output's `notebook_meta` encryption fields so the file describes itself — og's
     * `restampMeta`, and og's recorded bug shape too: without it a decrypted export still claims
     * `encrypted: true` and a re-keyed one still claims the source's scope. **Best effort**, like
     * the meta refresh at prepare: a meta row that is missing or will not parse is logged and the
     * export goes ahead — the header, not the meta, is what a probe trusts.
     */
    private fun restampMeta(db: ZeticDB, schema: String, encrypted: Boolean, keyScope: String?) {
        try {
            val json = db.rawQuery("SELECT json FROM $schema.notebook_meta WHERE id = 0", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return
            val stamped = restampedJson(json, encrypted, keyScope) ?: return
            db.execSQL("UPDATE $schema.notebook_meta SET json = ${sqlLiteral(stamped)} WHERE id = 0")
        } catch (e: Exception) {
            Log.w(TAG, "meta restamp skipped: ${e.javaClass.simpleName}")
        }
    }

    /** The pure half of [restampMeta], pinned by test: the same row with only the encryption
     *  fields changed. Null when the row will not parse — restamping is then skipped, never
     *  guessed. */
    fun restampedJson(json: String, encrypted: Boolean, keyScope: String?): String? = try {
        NotebookMeta.fromJson(json).copy(encrypted = encrypted, keyScope = keyScope).toJson()
    } catch (_: Exception) {
        null
    }

    /** The artifact open — through the one crypto door, exists-guarded, full native KDF (the
     *  artifact is a byte copy, so the salt in bytes 0..15 travelled with it). */
    private fun openArtifact(file: File, passphrase: String): ZeticDB = SoilCrypto.openRaw(file, passphrase)

    /** The output path beside its input, with any leftovers of an earlier aborted run — sidecars
     *  included — cleared first. Deleting an *unaccepted own output* is the one deletion this
     *  object is allowed. */
    private fun sibling(artifact: File, tag: String): File {
        val out = File(artifact.parentFile, "${artifact.nameWithoutExtension}-$tag.soil")
        rejectOutput(out)
        return out
    }

    /** Remove an output that was never accepted, and its sidecars. Never pointed at an input.
     *  Internal so [ImportKeying]'s own sibling naming can clear its leftovers the same way. */
    internal fun rejectOutput(out: File) {
        out.parentFile?.listFiles { f -> f.name.startsWith(out.name) }?.forEach { runCatching { it.delete() } }
    }

    private fun queryLong(db: ZeticDB, sql: String): Long =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    private fun queryString(db: ZeticDB, sql: String): String? =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /**
     * A string as a single-quoted SQL literal, quotes doubled. Pure and pinned by test: the
     * ATTACH statements above carry a path and a passphrase this way, and `''` **is** how a
     * plaintext key is spelled — [sqlLiteral] of an empty string produces exactly that.
     */
    fun sqlLiteral(s: String): String = "'" + s.replace("'", "''") + "'"

    /** Report corruption without deleting — the framework default deletes the file. */
    private object KeepFileErrorHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: android.database.sqlite.SQLiteDatabase?) {
            Log.w(TAG, "verify open reported corruption")
        }
    }
}
