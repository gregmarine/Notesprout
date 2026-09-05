package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soilFile

/**
 * **The one door every export opens the notebook through** — the guard preamble [ExportArtifact],
 * [ExportRender], [ExportText] and [DocumentPdfRender] each stood on, written once.
 *
 * The **order is the data-safety invariant**, not a style: each guard exists because the one before
 * it cannot answer for what it guards, and a preparer that reordered them would be a preparer that
 * reads a notebook while it is being written to.
 *
 *  1. **The file is there.** A missing or empty `.soil` is an index row that outlived its file, and
 *     it must not be reported as a key problem or a damaged file.
 *  2. **The file is not held.** One file, one connection: [SoilOpenFiles] is the door written down,
 *     so this checks rather than assumes — **first**, because opening it to find out is the very
 *     thing being prevented.
 *  3. **There is a key.** Asked before the open because the open needs it, and asked of
 *     [SoilDatabase.resolve] rather than of the session (arc 26 / U4): a `GLOBAL` notebook answers
 *     with the cached global passphrase (plus a rotation's second candidate), a `NOTEBOOK` one
 *     answers [Guard.LOCKED] unless the caller already holds the key it typed for. No session at
 *     all means the process was killed and nothing has unlocked since — [Guard.NO_KEY].
 *  4. **Read-only open** through the one [SoilDatabase.open] door.
 *  5. **Seal, always, in a `finally`** — an unsealed open strands the connection and its WAL sidecar
 *     for the process lifetime (the R6 lesson). It runs whatever the body did, exception included.
 *
 * **The refusal is reported, never translated here** ([Guard]). Each caller keeps its own `Problem`
 * enum and its own sentence, because what the user is told belongs to the thing that was being made
 * — a copy, a bake, a text file — not to the door they all came through.
 *
 * Nothing is written: not one caller of this needs to, and `notebook_meta`'s `exportedAt` stamp
 * (the one write in the family) is [ExportArtifact]'s own body, not this preamble's business.
 *
 * IO by contract — every caller is already inside `withContext(Dispatchers.IO)`, and this adds no
 * dispatcher of its own.
 */
object ExportOpen {

    private const val TAG = "ExportOpen"

    /** Which guard refused, for the caller to translate into its own `Problem`. */
    enum class Guard {
        /** The `.soil` is missing or empty — the index row outlived its file. */
        MISSING,

        /** A connection to this `.soil` is open in this process — never read under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /**
         * **After [NO_KEY] on purpose** (arc 26 / U4): the file is there, it is not in use, and
         * this process simply holds no key for it — a `NOTEBOOK`-scope notebook nobody has typed
         * the passphrase for. Not a missing session and not a damaged file: the library is open,
         * this one notebook is not, and the way out is to open it once (or to hand [readOnly] the
         * key the caller just collected).
         */
        LOCKED,

        /** The file would not open (wrong key, damaged). */
        UNREADABLE,
    }

    /** What [readOnly] answers: the body's own result, or the guard that stopped it running. */
    sealed class Opened<out T> {
        class Read<T>(val value: T) : Opened<T>()
        class Blocked(val guard: Guard) : Opened<Nothing>()
    }

    /**
     * Run [body] against [notebookId]'s open `.soil`, behind the guards above and sealed after.
     *
     * [label] appears in log lines only ("render", "export", …) so a failure in logcat names the
     * flow it came from; it is never a path and never a name. [body]'s own exceptions are the
     * caller's to catch — they mean the *work* failed, which is a different sentence from the file
     * not opening, and the seal in the `finally` runs for them either way.
     *
     * [resolved] is the key a caller **already holds** (arc 26 / U4) — the screen that has just
     * put up [com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt] passes
     * `Passphrases(typed)`, so its export does not wait on the raw-key warm and does not ask a
     * second time. Left null, the key is resolved here, and a `NOTEBOOK`-scope notebook nothing
     * has unlocked is [Guard.LOCKED]: **this door never prompts** — a prompt belongs to a screen.
     */
    suspend fun <T> readOnly(
        context: Context,
        notebookId: String,
        label: String,
        resolved: KeyResolver.Resolved? = null,
        body: suspend (SoilDatabase) -> T,
    ): Opened<T> {
        val source = soilFile(context, notebookId)
        if (!source.exists() || source.length() == 0L) return Opened.Blocked(Guard.MISSING)
        if (SoilOpenFiles.isOpen(source)) {
            Log.w(TAG, "refusing to $label a notebook that is open in this process")
            return Opened.Blocked(Guard.IN_USE)
        }
        val key = resolved ?: SoilDatabase.resolve(context, notebookId)
        when (key) {
            is KeyResolver.Resolved.NoKey -> return Opened.Blocked(Guard.NO_KEY)
            is KeyResolver.Resolved.NeedsPrompt -> return Opened.Blocked(Guard.LOCKED)
            else -> Unit
        }

        val db = try {
            SoilDatabase.open(context, notebookId, source, key)
        } catch (e: Exception) {
            Log.w(TAG, "$label open failed", e)
            return Opened.Blocked(Guard.UNREADABLE)
        }
        return try {
            Opened.Read(body(db))
        } finally {
            db.seal(source)
        }
    }
}
