package com.notesprout.android.state

import java.io.File

/**
 * A notebook that could not be opened, handed from the failing surface back to the library.
 *
 * A `.soil` open can fail for reasons the app cannot fix in place — most notably a **schema drift**
 * (a notebook written before the columnar migration whose column set no longer matches the current
 * entity, which Room rejects as `Pre-packaged database has an invalid schema`). Before this existed,
 * that exception escaped `NotebookActivity`'s open coroutine and killed the process. Because cold
 * launch reopens the previous surface stack, the same notebook was reopened on the next launch and
 * killed it again — an unrecoverable crash-loop whose only exit was clearing app data.
 *
 * So the open is now caught broadly and reported here instead: the notebook finishes back to the
 * library, `MainActivity` drains this and explains what happened. Broad on purpose — *any* failure
 * to open is better surfaced than crashed on. Note that failing back to the library is itself the
 * loop fix: `MainActivity.onResume` calls [SurfaceStack.reset], so the bad notebook is no longer in
 * the stack the next launch rebuilds.
 *
 * The report is deliberately **verbose** — this is a dogfooding aid meant to be brought back for
 * investigation, not finished user-facing copy. Trim it before a public release.
 *
 * In-memory only: the hand-off is within one process (the notebook finishes straight to the library),
 * and a report that outlived the process would resurface detached from the action that caused it.
 * Main thread only.
 */
object NotebookOpenFailure {

    /** Set by the failing surface, drained by the library. */
    private var pending: Report? = null

    data class Report(
        val notebookName: String,
        val notebookId: String,
        /** Human-readable summary of the likeliest cause, in plain language. */
        val diagnosis: String,
        /** Exception class + message chain, truncated. */
        val technical: String,
    )

    fun set(report: Report) { pending = report }

    /** Returns the pending report and clears it, so it is shown exactly once. */
    fun take(): Report? = pending.also { pending = null }

    /**
     * Build a report from a failed open. [error] is inspected to name the cause: Room's strict schema
     * validation and SQLCipher's "not a database" both have recognisable signatures and very
     * different meanings — one is a stale notebook, the other a key problem.
     */
    fun from(
        notebookName: String,
        notebookId: String,
        soilPath: String?,
        encrypted: Boolean,
        keyScope: String,
        error: Throwable,
    ): Report {
        val chain = buildString {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 4) {
                if (depth > 0) append("\ncaused by: ")
                append(cause::class.java.simpleName).append(": ")
                // Room's schema-mismatch message dumps every column of both schemas — hundreds of
                // lines. Keep the head, which is where the table name and the verdict are.
                append(cause.message?.take(300)?.replace(Regex("\\s+"), " ") ?: "(no message)")
                cause = cause.cause
                depth++
            }
        }
        val file = soilPath?.let { File(it) }
        val sizeNote = when {
            file == null      -> "file path unknown"
            !file.exists()    -> "file is MISSING from disk"
            else              -> "file is ${file.length() / 1024} KB on disk (intact)"
        }

        val message = error.message.orEmpty()
        val diagnosis = when {
            message.contains("invalid schema") -> "This notebook was written by an older version of " +
                "Notesprout. Its internal table layout no longer matches what this build expects " +
                "(the columnar migration), so the database refused to open. This is a known gap — " +
                "there is no migration for notebooks that drifted this way."
            message.contains("file is not a database") || message.contains("corrupt") ->
                "The database could not be read. For an encrypted notebook this usually means the " +
                "wrong key was used rather than real damage — encrypted contents look like garbage " +
                "to a plaintext reader. The file was NOT deleted or rewritten."
            else -> "The notebook database failed to open. See the technical detail below."
        }

        return Report(
            notebookName = notebookName.ifBlank { "(unnamed)" },
            notebookId   = notebookId,
            diagnosis    = diagnosis,
            technical    = buildString {
                append("Notebook: ").append(notebookName.ifBlank { "(unnamed)" }).append('\n')
                append("Id: ").append(notebookId).append('\n')
                append("Encrypted: ").append(if (encrypted) "yes ($keyScope)" else "no").append('\n')
                append("Storage: ").append(sizeNote).append('\n')
                append('\n')
                append(chain)
            },
        )
    }
}
