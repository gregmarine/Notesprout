package com.symmetricalpalmtree.notesproutsn.importing

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.data.sidecarsOf
import com.symmetricalpalmtree.notesproutsn.data.soil.FolderRef
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookRemap
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.data.soilStagingFile
import com.symmetricalpalmtree.notesproutsn.templates.TemplateLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * **The import engine** (arc 16 / I1): every step of getting a picked document onto the device,
 * with no UI in it. [ImportFlow] owns the questions; this owns the bytes.
 *
 * The order below *is* the design, and it is arc 15's in reverse:
 *
 *  1. **The cache is the only place anything happens** until the very end. `cacheDir/import/` is
 *     wiped and recreated per import (og's hygiene, and the exporter's); the extension streams into
 *     it, the keying transform writes a sibling in it, and the remap runs on that sibling. A failure
 *     anywhere up to step 3 has touched nothing outside it — the Garden and the index are untouched
 *     by construction, not by care.
 *  2. **Nothing is read until it has been keyed.** The incoming bytes are a stranger's; the probe,
 *     the unlock and [com.symmetricalpalmtree.notesproutsn.crypto.ImportKeying] all run before
 *     [readManifest] opens anything, and the manifest that comes out is still treated as untrusted
 *     ([SafeImportId]).
 *  3. **The Garden write is a rename.** [placeInGarden] copies into a
 *     [soilStagingFile] sibling, verifies it, and only then swaps — so a copy that dies half-way can
 *     never leave a notebook the user already had (an id-collision *Replace*) in pieces.
 *  4. **The index row lands last**, after the file is verifiably in place (og's step 9 → 10
 *     ordering): a crash mid-import must leave the library exactly as it was.
 *
 * Never-delete-on-corruption covers the temps too — the only thing this object ever deletes is its
 * own cache directory, its own staging file, and (at the caller's explicit instruction) the Garden
 * file it is replacing.
 */
object NotebookImport {

    private const val TAG = "NotebookImport"

    /** The cache subdirectory, wiped and recreated per import. */
    private const val DIR = "import"

    /** The file the extension streams the picked document into. */
    private const val INCOMING = "incoming.soil"

    /** Why an import could not be finished. Each maps to one sentence on screen. */
    enum class Problem {
        /** The extension's delivery failed, timed out, or it died mid-stream. */
        DELIVERY,

        /** The bytes that arrived are not the bytes the picker said it had. */
        SHORT,

        /** The document is not a `.soil` — the probe refused it, or it holds no `notebook` table. */
        NOT_A_NOTEBOOK,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** The re-key to this device's key failed, or its output would not be accepted. */
        KEYING,

        /** The file opened but would not be read — a damaged or truncated database. */
        UNREADABLE,

        /** The notebook being replaced has a live connection in this process. */
        IN_USE,

        /** The Garden write failed or came out short — no room, or IO died mid-copy. */
        WRITE,
    }

    /** An engine failure with a [Problem] the screen can turn into one honest sentence. Messages
     *  never carry a path and never carry a secret. */
    class ImportProblem(val problem: Problem, cause: Throwable? = null) :
        Exception("import problem: $problem", cause)

    // ── The cache ────────────────────────────────────────────────────────────

    /** Wipe and recreate `cacheDir/import/`, and return the file the extension will write into.
     *  Throws [ImportProblem] with [Problem.WRITE] when the cache cannot be made at all. */
    fun prepareCache(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) {
            Log.w(TAG, "could not create the import cache directory")
            throw ImportProblem(Problem.WRITE)
        }
        return File(dir, INCOMING)
    }

    /** Wipe the cache dir. Called in a `finally` — the incoming copy is the user's notes and has no
     *  business outliving the import that made it. Best effort by design. */
    fun clean(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }

    // ── The manifest ─────────────────────────────────────────────────────────

    /**
     * What the incoming file says about itself. [fileId] is the id the **rows inside** are parented
     * to — the notebook root row's own id where there is one, the meta's otherwise, and null when
     * neither survives [SafeImportId]. That is the id a remap must rewrite *from*, and the reason it
     * is read from the row rather than from the meta: the meta is a description, the row is the
     * thing pages are actually attached to (the arc-15 / E3 round-trip finding).
     */
    class Manifest(
        val fileId: String?,
        /**
         * The same id **unvalidated** — what the rows inside are literally parented to. It is the
         * remap's `oldId`, and it is deliberately not held to [SafeImportId]: an odd id is unsafe
         * as a *path component* and as an *index key*, which is what [fileId] guards, but as the
         * left-hand side of a parameterised `WHERE parentId = ?` it is just a string. Refusing to
         * remap from it would import a notebook that opens empty — the one outcome the remap pass
         * exists to prevent.
         */
        val rawFileId: String?,
        val meta: NotebookMeta?,
        val pageCount: Int,
        /** The first page's paper, as an index `templateKind` label (`BLANK`/`LINED`/`DOTTED`/
         *  `GRID`/`IMAGE`) — the imported row's card placeholder until the first open seeds a real
         *  cover. Null when the file will not say (the I2 review's finding: carrying a *replaced*
         *  notebook's old kind forward described paper the file behind the id no longer uses). */
        val templateKind: String?,
    ) {
        /** The ancestry the file remembers, validated segment by segment when it is planned. */
        val folderPath: List<FolderRef> get() = meta?.folderPath.orEmpty()
    }

    /**
     * Read [file] (already keyed to this device) through one raw connection: prove it holds a
     * `notebook` table, take the root row's id, the `notebook_meta` row if there is one, and the
     * live page count.
     *
     * A missing `notebook_meta` is **not** a failure (og's pre-S1 files, and any file written by a
     * build that did not stamp one): the import goes ahead under the picked file's display name
     * with an empty ancestry. A missing `notebook` table **is** — there is no notebook in there to
     * import.
     */
    suspend fun readManifest(file: File, passphrase: String): Manifest = withContext(Dispatchers.IO) {
        val db = try {
            SoilCrypto.openRaw(file, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "manifest open failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.UNREADABLE, e)
        }
        try {
            if (!hasTable(db, SoilSchema.TABLE)) {
                Log.w(TAG, "no ${SoilSchema.TABLE} table — not a notebook")
                throw ImportProblem(Problem.NOT_A_NOTEBOOK)
            }
            val rowId = queryString(
                db,
                "SELECT id FROM ${SoilSchema.TABLE} WHERE type = ? AND deletedAt IS NULL " +
                    "ORDER BY createdAt LIMIT 1",
                arrayOf(SoilSchema.TYPE_NOTEBOOK),
            )
            val meta = readMeta(db)
            val rawFileId = rowId?.takeIf { it.isNotBlank() } ?: meta?.notebookId?.takeIf { it.isNotBlank() }
            val fileId = SafeImportId.orNull(rowId) ?: SafeImportId.orNull(meta?.notebookId)
            // Pages are parented to the id the rows literally carry — rawFileId, not the validated
            // fileId, which can fall back to a *different* meta id (the I2 review's finding: the
            // count came out 0 for a notebook full of pages).
            val pages = if (rawFileId != null) {
                queryLong(
                    db,
                    "SELECT count(*) FROM ${SoilSchema.TABLE} WHERE type = ? AND deletedAt IS NULL AND parentId = ?",
                    arrayOf(SoilSchema.TYPE_PAGE, rawFileId),
                )
            } else {
                queryLong(
                    db,
                    "SELECT count(*) FROM ${SoilSchema.TABLE} WHERE type = ? AND deletedAt IS NULL",
                    arrayOf(SoilSchema.TYPE_PAGE),
                )
            }
            Slog.d(TAG) { "manifest: ${if (meta == null) "no meta" else "meta"}, $pages page(s)" }
            Manifest(fileId, rawFileId, meta, pages.toInt().coerceAtLeast(0), readTemplateKind(db, rawFileId))
        } catch (e: ImportProblem) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "manifest read failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.UNREADABLE, e)
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * The first live page's paper, as an index `templateKind` label. Best effort and never a
     * failure — an answer the file will not give is null, and the card falls back to BLANK.
     * A page with no `refId` **is** blank paper; a token this build does not draw (a foreign
     * `IMG#` variant, a future kind) is null rather than a guess.
     */
    private fun readTemplateKind(db: ZeticDB, rawFileId: String?): String? = try {
        if (rawFileId == null) null
        else {
            val refId = queryString(
                db,
                "SELECT refId FROM ${SoilSchema.TABLE} WHERE type = ? AND deletedAt IS NULL AND parentId = ? " +
                    "ORDER BY \"order\", createdAt LIMIT 1",
                arrayOf(SoilSchema.TYPE_PAGE, rawFileId),
            ) ?: return SoilSchema.TEMPLATE_BLANK
            val token = queryString(
                db, "SELECT text FROM ${SoilSchema.TABLE} WHERE id = ?", arrayOf(refId),
            )
            when {
                token == null || token.isEmpty() -> SoilSchema.TEMPLATE_BLANK
                token == "LINED" || token == "DOTTED" || token == "GRID" -> token
                token.startsWith("IMG#") -> TemplateLibrary.KIND_IMAGE
                else -> null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "template kind read skipped: ${e.javaClass.simpleName}")
        null
    }

    /** The meta row, or null when the table is absent, the row is missing, or the JSON will not
     *  parse. Never a failure: a file that cannot describe itself still imports. */
    private fun readMeta(db: ZeticDB): NotebookMeta? = try {
        if (!hasTable(db, SoilSchema.META_TABLE)) null
        else queryString(db, "SELECT json FROM ${SoilSchema.META_TABLE} WHERE id = 0", null)
            ?.let { json -> runCatching { NotebookMeta.fromJson(json) }.getOrNull() }
    } catch (e: Exception) {
        Log.w(TAG, "meta read skipped: ${e.javaClass.simpleName}")
        null
    }

    // ── The remap ────────────────────────────────────────────────────────────

    /**
     * Re-identify [file] from [oldId] to [newId] — in the cache, **before** the Garden copy, on a
     * connection this owns. Without it a Keep-both import is a notebook that opens empty: the pages
     * inside are parented to the old id and `NotebookSession` asks for the index row's (the arc-15 /
     * E3 finding). The checkpoint before the close is what puts the rewrite in the main file, which
     * is the only file the Garden copy takes.
     */
    suspend fun remap(file: File, passphrase: String, oldId: String, newId: String) =
        withContext(Dispatchers.IO) {
            val db = try {
                SoilCrypto.openRaw(file, passphrase)
            } catch (e: Exception) {
                Log.w(TAG, "remap open failed: ${e.javaClass.simpleName}")
                throw ImportProblem(Problem.UNREADABLE, e)
            }
            try {
                NotebookRemap.remap(db, oldId, newId)
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } catch (e: Exception) {
                Log.w(TAG, "remap failed: ${e.javaClass.simpleName}")
                throw ImportProblem(Problem.UNREADABLE, e)
            } finally {
                runCatching { db.close() }
            }
        }

    // ── The Garden write ─────────────────────────────────────────────────────

    /**
     * Put [keyed] in the Garden as [notebookId]'s file. The last step that can fail without leaving
     * the library changed, and the one that has to be careful:
     *
     *  - a **live connection** to the target refuses outright ([Problem.IN_USE]) — one file, one
     *    connection, and the registry is the door written down ([SoilOpenFiles]);
     *  - a `-wal` still holding frames beside [keyed] refuses too: the copy takes the main file
     *    only, and one with un-checkpointed frames behind it is a notebook missing its newest rows
     *    (arc 15's rule, in the other direction);
     *  - the copy goes to a [soilStagingFile] **sibling** and is length-verified there, so a
     *    half-written copy never becomes the target; the swap itself is **one `rename` over the
     *    live target** — `rename(2)` replaces atomically, so there is no instant at which the
     *    user's existing notebook is deleted-but-not-yet-replaced, and a rename that fails (the
     *    two are same-directory siblings, so it has no failure mode a copy would survive) leaves
     *    the target exactly as it was — never a fallback copy over a target already torn down
     *    (the I2 review's data-loss finding);
     *  - the replaced file's sidecars go **after** the swap, not before: they described the old
     *    bytes, and deleting them first would strip the old notebook's `-wal` on a path that can
     *    still fail and keep it;
     *  - the cached raw key for this id is invalidated afterwards, because the file behind the id
     *    has different bytes — and therefore a different SQLCipher salt — than the one that key was
     *    derived from. (`KeyOpener` self-heals a stale key, but a key that is known wrong is not
     *    left lying around.)
     */
    suspend fun placeInGarden(context: Context, keyed: File, notebookId: String) =
        withContext(Dispatchers.IO) {
            val target = soilFile(context, notebookId)
            if (SoilOpenFiles.isOpen(target)) {
                Log.w(TAG, "refusing to write a notebook that is open in this process")
                throw ImportProblem(Problem.IN_USE)
            }
            val wal = File(keyed.path + "-wal")
            if (wal.exists() && wal.length() > 0L) {
                Log.w(TAG, "incoming WAL not checkpointed (${wal.length()} bytes) — refusing")
                throw ImportProblem(Problem.WRITE)
            }
            val bytes = keyed.length()
            if (bytes == 0L) throw ImportProblem(Problem.WRITE)

            val staging = soilStagingFile(context, notebookId)
            try {
                target.parentFile?.mkdirs()
                runCatching { staging.delete() }
                keyed.copyTo(staging, overwrite = true)
                if (staging.length() != bytes) {
                    Log.w(TAG, "staged ${staging.length()} of $bytes bytes")
                    throw ImportProblem(Problem.WRITE)
                }
                if (!staging.renameTo(target)) {
                    // rename(2) replaces an existing target atomically, and staging and target are
                    // same-directory siblings — a failure here has no mode a copy would survive,
                    // and the target is still exactly whatever it was.
                    Log.w(TAG, "staging rename failed")
                    throw ImportProblem(Problem.WRITE)
                }
                // Only now, with the swap durable, do the replaced file's sidecars go: they
                // described the old bytes (a stale -wal's salts cannot match the new file, but a
                // stale sidecar is still nothing to keep).
                sidecarsOf(target).forEach { runCatching { it.delete() } }
                if (target.length() != bytes) {
                    Log.w(TAG, "wrote ${target.length()} of $bytes bytes")
                    throw ImportProblem(Problem.WRITE)
                }
            } catch (e: ImportProblem) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "garden write failed: ${e.javaClass.simpleName}")
                throw ImportProblem(Problem.WRITE, e)
            } finally {
                runCatching { if (staging.exists()) staging.delete() }
            }
            KeyMaterial.invalidate(context, notebookId)
            Slog.d(TAG) { "placed $bytes bytes in the Garden" }
        }

    // ── The meta refresh ─────────────────────────────────────────────────────

    /**
     * Rewrite the Garden file's `notebook_meta` so it describes what it now is: this id, this name,
     * this ancestry, encrypted under this device's GLOBAL key. **Best effort** — like every meta
     * write in the family, a failure is logged and the import stands (the file already describes
     * itself well enough to have got this far, and [com.symmetricalpalmtree.notesproutsn.crypto.ImportKeying]
     * has already restamped its keying).
     *
     * It goes through [SoilDatabase.open] / [SoilDatabase.seal] — the one door — so the
     * [SoilOpenFiles] claim and the closing checkpoint both happen by construction.
     */
    suspend fun refreshMeta(
        context: Context,
        notebookId: String,
        name: String,
        folderPath: List<FolderRef>,
        passphrase: String,
        appVersionCode: Int,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val file = soilFile(context, notebookId)
        val db = try {
            SoilDatabase.open(context, notebookId, file, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "meta refresh open skipped: ${e.javaClass.simpleName}")
            return@withContext
        }
        try {
            val existing = NotebookMetaStore.read(db.raw())
            NotebookMetaStore.write(
                db.raw(),
                NotebookMeta(
                    notebookId = notebookId,
                    name = name,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    // The cover never travels for an encrypted file (the family rule), and the row
                    // it would have described belongs to whatever used to be behind this id.
                    cover = null,
                    folderPath = folderPath,
                    appVersionCode = appVersionCode,
                    textDocument = existing?.textDocument ?: false,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "meta refresh skipped: ${e.javaClass.simpleName}")
        } finally {
            db.seal(file)
        }
    }

    // ── Raw-connection plumbing ──────────────────────────────────────────────

    private fun hasTable(db: ZeticDB, name: String): Boolean =
        queryLong(db, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)) > 0L

    private fun queryLong(db: ZeticDB, sql: String, args: Array<String>?): Long =
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun queryString(db: ZeticDB, sql: String, args: Array<String>?): String? =
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
