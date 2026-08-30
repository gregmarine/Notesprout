package com.symmetricalpalmtree.notesproutsn.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupEngine
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupStore
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityBackupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Backup** (arc 17 / K2) — the whole user-facing side of getting a copy of the library off the
 * device. Three things and nothing else: where backups go, a button that runs one, and what the
 * last run did.
 *
 * The screen owns no policy. [BackupEngine] decides what to copy, in what order, and never throws;
 * this screen decides only what the user is told:
 *
 *  - **Manual only.** There is no schedule, no watcher and no background run. A backup happens
 *    because someone tapped for one, which is why the button is the biggest thing on the screen.
 *  - **One run at a time** ([running]) — an [AtomicBoolean], because the tap arrives on Main but
 *    the flag is cleared from a coroutine continuation.
 *  - **Never a disabled control.** With no folder chosen the button still looks live and the tap
 *    explains itself in a dialog: on e-ink `isEnabled = false` is invisible and reads as broken.
 *  - **Every outcome is a dialog.** A clean run confirms its counts in one too, not a toast — this
 *    screen exists to answer "did it work", and the counts are the answer. Anything a run could not
 *    do — a folder that has gone, a locked library, a failed copy, an index that did not land — gets
 *    the same treatment with the honest per-count summary.
 *
 * The status line is read back from the config rather than the [BackupEngine.Result], so it says
 * the same thing after a relaunch as it does the moment a run ends.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private val store by lazy { BackupStore() }

    /** True from the "Back up now" tap until the run ends. A second tap in the e-ink feedback gap
     *  does nothing; the modal progress dialog covers the rest of the screen meanwhile. */
    private val running = AtomicBoolean(false)

    /** The run's progress dialog while one is up. Non-cancelable — a backup is not something to
     *  half-leave, and the engine's Binder-free IO has no cancel to offer anyway. */
    private var progress: AlertDialog? = null

    /**
     * The SAF folder pick. A tree URI is worthless without a persisted grant — the next launch
     * would find it and be refused — so the grant is taken first and a folder whose grant will not
     * persist is not stored at all.
     */
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        // Cancelled at the picker: nothing chosen, nothing to explain, the screen stays as it was.
        if (uri == null) { Slog.d(TAG) { "folder picker cancelled" }; return@registerForActivityResult }
        lifecycleScope.launch { adoptFolder(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The guard is 0 on Ratta — chrome sits flush at the top edge; the inset pass is still how
        // the screen clears a navigation bar if the device has one.
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnChoose.setOnClickListener { onChooseTap() }
        binding.btnRun.setOnClickListener { onRunTap() }

        lifecycleScope.launch { render() }
    }

    override fun onDestroy() {
        // The guard bounce still runs this callback, and a `lateinit` teardown would crash on the
        // way out of a task Android rebuilt after a background process kill.
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        // The dialog is attached to this window; leaving it up past the teardown leaks it.
        progress?.let { runCatching { it.dismiss() } }
        progress = null
        super.onDestroy()
    }

    // ── The two lines the screen shows ───────────────────────────────────────

    /** Both lines, from one read of the stored config — the folder and what the last run did. */
    private suspend fun render() {
        val config = store.read()
        if (isFinishing || isDestroyed) return
        binding.folderPath.text =
            config.treeUri?.let { folderLabel(it) } ?: getString(R.string.backup_no_folder)
        val at = config.lastRunAt
        binding.status.text = if (at == null) {
            getString(R.string.backup_status_never)
        } else {
            getString(
                R.string.backup_status_last,
                DateFormat.getDateTimeInstance().format(Date(at)),
                config.lastCopied ?: 0,
                config.lastSkipped ?: 0,
            )
        }
    }

    /**
     * The chosen folder, as readably as a tree URI honestly allows. A document id is
     * `<volume>:<relative path>` (`primary:Backups`), so the volume prefix goes and what is left is
     * the path the user picked. Anything that is not shaped that way — another provider's opaque
     * id — is shown as it is rather than guessed at: naming the wrong folder would be worse than
     * naming an ugly one.
     */
    private fun folderLabel(treeUri: String): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull()
            ?: return treeUri
        return id.substringAfter(':').ifEmpty { id }
    }

    // ── Choosing the folder ──────────────────────────────────────────────────

    private fun onChooseTap() {
        try {
            folderLauncher.launch(null)
        } catch (e: Exception) {
            Log.w(TAG, "no folder picker: $e")
            Dialogs.problem(this, R.string.backup_no_picker_title, R.string.backup_no_picker_body)
        }
    }

    /**
     * Take the lasting grant, then store the folder.
     *
     * **A different folder resets the stamp map.** The stamps say "this notebook has been copied as
     * of that edit" — a statement about a destination, and it is not true of a folder that has
     * never seen them. Carrying them across would leave the new folder holding the index and
     * nothing else, and the user would not find out until they needed the backup. Re-picking the
     * folder already stored keeps them, because that is the same destination and re-copying the
     * whole library over it would cost minutes for nothing.
     */
    private suspend fun adoptFolder(uri: Uri) {
        val granted = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "could not persist the folder grant: ${it.message}") }.isSuccess
        }
        if (isFinishing || isDestroyed) return
        if (!granted) {
            Dialogs.problem(this, R.string.backup_folder_failed_title, R.string.backup_folder_failed_body)
            return
        }
        val stored = uri.toString()
        val config = store.read()
        val changed = config.treeUri != stored
        if (changed) config.treeUri?.let { previous ->
            // Persisted grants are a capped per-app resource (K3 review): without this, enough
            // re-picks exhaust the cap and takePersistableUriPermission refuses every folder
            // from then on. Best effort — a grant that will not release just idles.
            withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure { Slog.d(TAG) { "previous grant not released: ${it.javaClass.simpleName}" } }
            }
        }
        store.write(
            config.copy(treeUri = stored, stamps = if (changed) emptyMap() else config.stamps)
        )
        Slog.d(TAG) { "backup folder set (destination changed: $changed)" }
        render()
    }

    // ── The run ──────────────────────────────────────────────────────────────

    /**
     * One run, start to finish. The no-folder case is answered here rather than by leaving the
     * button dead: the engine would return [BackupEngine.Problem.NO_FOLDER] anyway, but asking
     * first means the user never watches a progress dialog appear only to be told there was
     * nowhere to put anything.
     */
    private fun onRunTap() {
        if (!running.compareAndSet(false, true)) {
            Slog.d(TAG) { "back-up tap ignored: a run is already going" }
            return
        }
        lifecycleScope.launch {
            val config = store.read()
            if (config.treeUri == null) {
                running.set(false)
                if (!isFinishing && !isDestroyed) {
                    Dialogs.problem(this@BackupActivity, R.string.backup_no_folder_title, R.string.backup_no_folder_body)
                }
                return@launch
            }
            showProgress()
            val result = try {
                // The engine is IO internally and never throws; the progress callback arrives on
                // its thread, so every touch of the dialog is posted back to Main. The catch is
                // the belt to the engine's own top-level one (K3 review) — a throw here used to
                // crash the app under the non-cancelable progress dialog.
                BackupEngine.run(applicationContext) { p -> runOnUiThread { updateProgress(p) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "backup run threw", e)
                BackupEngine.Result(failed = 1)
            } finally {
                running.set(false)
                hideProgress()
            }
            // The status line is refreshed either way — a partly successful run still moved it.
            render()
            report(result)
        }
    }

    private fun showProgress() {
        if (isFinishing || isDestroyed) return
        progress = Dialogs.style(
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.backup_progress, 0, 0))
                .setCancelable(false)
                .create()
        ).also { it.show() }
    }

    private fun updateProgress(p: BackupEngine.Progress) {
        if (isFinishing || isDestroyed) return
        progress?.setMessage(getString(R.string.backup_progress, p.done, p.total))
    }

    private fun hideProgress() {
        progress?.let { runCatching { it.dismiss() } }
        progress = null
    }

    /**
     * What the run did, in the user's terms — every branch a dialog. The clean case still gets one:
     * this screen is the entire reason the run happened, and "N copied, M skipped" is exactly the
     * number a toast would let slip past unread.
     *
     * "Skipped" is one number covering four honest reasons — already up to date, deliberately
     * excluded, open elsewhere in the app, or a file no longer on the device. The distinction
     * matters to the engine and not to the person reading the line.
     */
    private fun report(result: BackupEngine.Result) {
        if (isFinishing || isDestroyed) return
        val skipped = result.upToDate + result.excluded + result.held + result.missing
        when {
            result.problem == BackupEngine.Problem.NO_FOLDER ->
                Dialogs.problem(this, R.string.backup_no_folder_title, R.string.backup_no_folder_body)

            result.problem == BackupEngine.Problem.FOLDER_GONE ->
                Dialogs.problem(this, R.string.backup_folder_gone_title, R.string.backup_folder_gone_body)

            // Behind IndexGuard this should be unreachable — the process cannot have a live index
            // and no key session — but a run that quietly did nothing would be the worst possible
            // way to find out otherwise.
            result.problem == BackupEngine.Problem.NO_KEY ->
                Dialogs.problem(this, R.string.backup_locked_title, R.string.backup_locked_body)

            result.failed > 0 || !result.indexCopied -> Dialogs.problem(
                this,
                R.string.backup_problem_title,
                getString(
                    R.string.backup_problem_body,
                    result.copied,
                    skipped,
                    result.failed,
                    getString(
                        if (result.indexCopied) R.string.backup_index_copied else R.string.backup_index_failed
                    ),
                ),
            )

            else -> Dialogs.confirm(
                this,
                R.string.backup_done_title,
                getString(R.string.backup_done_body, result.copied, skipped),
            ) { finish() }
        }
    }

    companion object {
        private const val TAG = "BackupActivity"

        fun intent(context: Context): Intent = Intent(context, BackupActivity::class.java)
    }
}
