package com.symmetricalpalmtree.notesproutsn.extension

import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.LinkRow
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The Rebuild door (arc 42 "Notes", decision 9) — the answer to everything the live push could not
 * see: a notebook edited before the reader was installed, a push that failed while the extension
 * was being replaced, an index restored from a backup older than the library.
 *
 * It walks **every alive notebook in the library**, prunes the index down to that list first, and
 * replaces each notebook's rows from its own `.soil`. What it deliberately does not do:
 *
 *  - **It never prompts.** A `NOTEBOOK`-scope notebook the person has not unlocked this process is
 *    counted as skipped and **keeps the rows it already has** — a rebuild must never be a way to
 *    lose what was indexed while it was open (the arc's locked call).
 *  - **It never opens a second connection to an open file.** The notebook screen hands its own
 *    session's reads in as [OpenSessionReads]; [SoilDatabase.readOnce]'s contract forbids the
 *    alternative, one file one connection family-wide.
 *
 * Progress is the backup screen's shape — a non-cancelable message dialog counting notebooks —
 * and the done dialog is [Dialogs.confirm], for the same reason the backup run's is: the result
 * *is* the counts. [run] returns only after that dialog has been dismissed, so a caller can chain
 * the reader's reopen onto it.
 *
 * Counts and durations are logged; **no notebook name ever is.**
 */
object BibleNoteRebuild {

    private const val TAG = "BibleNoteRebuild"

    /** The open notebook's own reads, so the rebuild never opens its `.soil` a second time. */
    class OpenSessionReads(
        val notebookId: String,
        val linkRows: suspend () -> List<LinkRow>,
        val livePageIds: suspend () -> List<String>,
    )

    /** What the run did, in the numbers the done dialog shows. */
    data class Outcome(
        val notebooks: Int = 0,
        val references: Int = 0,
        val lockedSkipped: Int = 0,
        val failed: Int = 0,
    )

    /**
     * Rebuild the whole index, with progress, and answer once the done dialog is gone.
     *
     * Every failure is counted, never thrown: a notebook that cannot be read leaves whatever rows
     * it had (the prune already dropped the ones the library no longer knows).
     */
    suspend fun run(activity: AppCompatActivity, openSession: OpenSessionReads?): Outcome {
        if (activity.isFinishing || activity.isDestroyed) return Outcome()
        val app = activity.applicationContext
        val t0 = System.currentTimeMillis()
        val all = withContext(Dispatchers.IO) {
            runCatching { IndexRepository().allNotebooks() }.getOrElse {
                Log.w(TAG, "the library could not be read", it)
                emptyList()
            }
        }
        if (activity.isFinishing || activity.isDestroyed) return Outcome()
        val total = all.size
        val progress = showProgress(activity, total)
        var outcome = Outcome()
        try {
            // Prune first: a notebook the library no longer knows must not survive a run that
            // never reaches it (a locked one is skipped, not visited).
            BibleNoteIndex.prune(app, all.map { it.id })
            all.forEachIndexed { ix, summary ->
                if (activity.isFinishing || activity.isDestroyed) return@forEachIndexed
                updateProgress(activity, progress, ix, total)
                val read: Pair<List<LinkRow>, List<String>>? =
                    if (summary.id == openSession?.notebookId) {
                        runCatching { openSession.linkRows() to openSession.livePageIds() }.getOrNull()
                    } else {
                        val resolved = SoilDatabase.resolve(app, summary.id)
                        if (resolved is KeyResolver.Resolved.NeedsPrompt || resolved is KeyResolver.Resolved.NoKey) {
                            outcome = outcome.copy(lockedSkipped = outcome.lockedSkipped + 1)
                            return@forEachIndexed
                        }
                        SoilDatabase.readOnce(app, summary.id, resolved) { dao ->
                            dao.liveLinkRows() to dao.livePageIds(summary.id)
                        }
                    }
                if (read == null) {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                    return@forEachIndexed
                }
                val (rows, pages) = read
                val notes = BibleNoteIndex.notebookNotes(rows, pages)
                BibleNoteIndex.pushNotebook(app, summary.id, summary.name, pages, notes)
                outcome = outcome.copy(
                    notebooks = outcome.notebooks + 1,
                    references = outcome.references + notes.size,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rebuild failed partway", e)
        } finally {
            hideProgress(progress)
        }
        Slog.d(TAG) {
            "rebuild: ${outcome.notebooks}/$total notebooks, ${outcome.references} refs, " +
                "${outcome.lockedSkipped} locked, ${outcome.failed} failed in ${System.currentTimeMillis() - t0} ms"
        }
        report(activity, outcome)
        return outcome
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun showProgress(activity: AppCompatActivity, total: Int): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        return Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bible_notes_rebuilding_title)
                .setMessage(activity.getString(R.string.bible_notes_rebuilding, 0, total))
                .setCancelable(false)
                .create()
        ).also { it.show() }
    }

    private suspend fun updateProgress(activity: AppCompatActivity, dialog: AlertDialog?, done: Int, total: Int) {
        val d = dialog ?: return
        withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext
            runCatching { d.setMessage(activity.getString(R.string.bible_notes_rebuilding, done, total)) }
        }
    }

    private fun hideProgress(dialog: AlertDialog?) {
        dialog?.let { runCatching { it.dismiss() } }
    }

    /**
     * The counts, then the caller's reopen — which is why this **waits for the dismissal**: the
     * reader must come back over a screen with nothing on top of it.
     *
     * The dialog is only ever raised on a live screen (checked immediately before, on Main, so
     * nothing can slip in between), and a screen torn down under it takes the whole `run` with it
     * — the caller's `lifecycleScope` cancels this continuation rather than stranding it.
     */
    private suspend fun report(activity: AppCompatActivity, outcome: Outcome) {
        if (activity.isFinishing || activity.isDestroyed) return
        suspendCancellableCoroutine { cont ->
            Dialogs.confirm(
                activity,
                R.string.bible_notes_rebuilt_title,
                activity.getString(
                    R.string.bible_notes_rebuilt_body,
                    outcome.notebooks, outcome.references, outcome.lockedSkipped,
                ),
            ) { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
        }
    }
}
