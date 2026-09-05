package com.symmetricalpalmtree.notesproutsn.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.RecognizingOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupEngine
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupStore
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityBackupBinding
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudConnectEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudNetworkException
import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus
import com.symmetricalpalmtree.notesproutsn.extension.CloudWording
import com.symmetricalpalmtree.notesproutsn.extension.CloudWords
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
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
 *
 * **The Cloud section** (arc 25 / V2, `DRIVE_PLAN.md` decision 8) is the one part of the screen that
 * is not always there: it is **GONE** unless a trusted cloud provider is installed, re-asked on
 * every `onResume` because a package can be disabled or replaced under a standing screen. It is
 * never *disabled* — on e-ink that is invisible. It holds the account line, the "back up to it" tick
 * (V4 is what will read it; V2 only records the intention), and one button that flips between
 * Connect and Disconnect. `status()` never touches the network, so reading it on every resume costs
 * a bind and nothing else.
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

    /** The connect door: discovery, the busy latch, the held bracket around the provider's sign-in
     *  screen. Constructed in [onCreate] because it registers an `ActivityResultLauncher`. */
    private var cloud: CloudConnectEntry? = null

    /** The last status the provider gave, or null when it did not answer (or is not installed).
     *  What the button reads and what the line says are both decided from this one value. */
    private var cloudStatus: CloudStatus? = null

    /** The last provider display name seen, so an *unavailable* line still has something to name.
     *  Falls back to the extension's own label, which is the only other name the host knows. */
    private var cloudName: String? = null

    /** True from a Connect/Disconnect tap until it resolves — the e-ink feedback gap again. */
    private var cloudBusy = false

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
        // Registered here and nowhere else: a launcher may not be registered after STARTED.
        cloud = CloudConnectEntry(this) { lifecycleScope.launch { renderCloud() } }
        binding.btnCloudConnect.setOnClickListener { onCloudButtonTap() }

        lifecycleScope.launch { render() }
    }

    /** Re-ask whether a provider is installed, and re-read the account. Both can change while this
     *  screen is stopped — a sign-in happened, or the extension was disabled. */
    override fun onResume() {
        super.onResume()
        if (IndexGuard.bounced(this)) return
        lifecycleScope.launch { renderCloud() }
    }

    override fun onDestroy() {
        // The guard bounce still runs this callback, and a `lateinit` teardown would crash on the
        // way out of a task Android rebuilt after a background process kill.
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        // The dialog is attached to this window; leaving it up past the teardown leaks it.
        progress?.let { runCatching { it.dismiss() } }
        progress = null
        // The connect bracket must not outlive the screen that opened it, result or no result.
        cloud?.close()
        cloud = null
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
     *
     * **Extension stores get their own sentence** (arc 21 / W5, the user's call): they are not
     * notebooks, so folding them into "N copied" would make a number the user can check against
     * the library stop matching it.
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

            result.failed > 0 || result.storesFailed > 0 || !result.indexCopied -> Dialogs.problem(
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
                    storesLine(result),
                ),
            )

            else -> Dialogs.confirm(
                this,
                R.string.backup_done_title,
                getString(R.string.backup_done_body, result.copied, skipped) + storesLine(result),
            ) { finish() }
        }
    }

    /**
     * The extension-store sentences, on their own line under the notebook counts — nothing at all
     * when there is no store on the device, because a line reading "0 extension stores copied" is a
     * sentence about something the reader has never heard of.
     */
    private fun storesLine(result: BackupEngine.Result): String = buildString {
        if (result.storesCopied > 0) {
            append('\n')
            append(
                getString(
                    if (result.storesCopied == 1) R.string.backup_stores_copied
                    else R.string.backup_stores_copied_plural,
                    result.storesCopied,
                )
            )
        }
        if (result.storesFailed > 0) {
            append('\n')
            append(
                getString(
                    if (result.storesFailed == 1) R.string.backup_stores_failed
                    else R.string.backup_stores_failed_plural,
                    result.storesFailed,
                )
            )
        }
    }

    // ── The Cloud section (arc 25 / V2) ──────────────────────────────────────

    /**
     * The whole section, from one discovery and one `status()`.
     *
     * Discovery decides whether the section exists at all — **GONE**, never disabled. The status
     * decides the line and the button. A provider that is installed but does not answer is its own
     * case: the line says *unavailable* and the button stays **Connect**, because a screen that
     * could not ask has no business claiming the account is gone.
     *
     * Every step re-checks that the screen is still here: a `status()` is a bind and a Binder call,
     * and the person can leave in the middle of one.
     */
    private suspend fun renderCloud() {
        val entry = cloud ?: return
        val ref = entry.discover()
        if (isFinishing || isDestroyed) return
        if (ref == null) {
            cloudStatus = null
            binding.cloudSection.visibility = View.GONE
            return
        }
        binding.cloudSection.visibility = View.VISIBLE
        val status = try {
            CloudClient.status(this, ref)
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
        if (isFinishing || isDestroyed) return
        cloudStatus = status
        if (status != null) cloudName = status.providerName
        // The provider's own name when it gave one; otherwise the extension's label — the only other
        // name the host has, and better than an unnamed line.
        val name = cloudName ?: ref.label.toString()
        binding.cloudStatus.text = if (status == null) {
            CloudWording.unavailableLine(name, cloudWords(), ::cloudLine)
        } else {
            CloudWording.statusLine(status, cloudWords(), ::cloudLine)
        }
        binding.btnCloudConnect.setText(
            if (CloudWording.showsDisconnect(status)) R.string.cloud_disconnect else R.string.cloud_connect
        )
        binding.cloudEnabled.text = getString(R.string.cloud_backup_enabled, name)
        val config = store.read()
        if (isFinishing || isDestroyed) return
        // Detached while the box is set, so restoring the stored state never reads as a user tap.
        binding.cloudEnabled.setOnCheckedChangeListener(null)
        binding.cloudEnabled.isChecked = config.cloudEnabled
        binding.cloudEnabled.setOnCheckedChangeListener { _, checked -> onCloudEnabledChanged(checked) }
    }

    private fun cloudWords() = CloudWords(
        notConnected = getString(R.string.cloud_state_not_connected),
        connected = getString(R.string.cloud_state_connected),
        notConfigured = getString(R.string.cloud_state_not_configured),
        unavailable = getString(R.string.cloud_state_unavailable),
    )

    private fun cloudLine(provider: String, detail: String): String =
        getString(R.string.cloud_status_line, provider, detail)

    /** The tick is an intention, not an action: it is written to the config and nothing else runs.
     *  V4 is what reads it, when there is a cloud destination for a run to write to. */
    private fun onCloudEnabledChanged(checked: Boolean) {
        lifecycleScope.launch {
            val config = store.read()
            if (config.cloudEnabled == checked) return@launch
            store.write(config.copy(cloudEnabled = checked))
            Slog.d(TAG) { "cloud backup enabled=$checked" }
        }
    }

    /** One button, two meanings — decided by the status the line is already showing. */
    private fun onCloudButtonTap() {
        if (cloudBusy) { Slog.d(TAG) { "cloud tap ignored: busy" }; return }
        val status = cloudStatus
        if (CloudWording.showsDisconnect(status)) confirmDisconnect() else connect()
    }

    /**
     * Connect. An **unconfigured** build is refused here rather than at the sign-in screen: the
     * extension was built without its credentials, so nobody can sign in on this build at all, and
     * opening a WebView that cannot work would be a worse way to say so.
     *
     * A provider that did not answer its `status()` is *not* refused — it may simply have been cold,
     * and the open will explain itself if it fails too.
     */
    private fun connect() {
        val entry = cloud ?: return
        val status = cloudStatus
        if (status != null && !status.configured) {
            Dialogs.problem(this, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body)
            return
        }
        entry.open()
    }

    private fun confirmDisconnect() {
        val name = cloudName ?: cloud?.ref?.label?.toString() ?: return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cloud_disconnect_title, name))
                .setMessage(R.string.cloud_disconnect_body)
                .setPositiveButton(R.string.cloud_disconnect) { _, _ -> disconnect() }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /**
     * Forget the account.
     *
     * **A network failure here is not a failure to report.** The provider revokes its token with its
     * service best-effort and forgets it locally either way, so the account is disconnected on this
     * device whatever the network did — saying "it didn't work" would leave the person tapping at
     * something that is already done. Anything else (the provider did not answer at all) is a
     * problem dialog, because then nothing is known.
     */
    private fun disconnect() {
        val entry = cloud ?: return
        val ref = entry.ref ?: return
        cloudBusy = true
        RecognizingOverlay.show(this, R.string.cloud_disconnecting)
        lifecycleScope.launch {
            var failed = false
            try {
                CloudClient.disconnect(this@BackupActivity, ref)
            } catch (e: CloudNetworkException) {
                // The revoke could not reach the service; the token is forgotten locally regardless.
                Slog.d(TAG) { "disconnect: revoke could not reach the provider — forgotten locally" }
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "disconnect failed: ${e.javaClass.simpleName}: ${e.message}" }
                failed = true
            } finally {
                RecognizingOverlay.hide(this@BackupActivity)
                cloudBusy = false
            }
            if (isFinishing || isDestroyed) return@launch
            renderCloud()
            if (isFinishing || isDestroyed) return@launch
            if (failed) {
                Dialogs.problem(
                    this@BackupActivity,
                    R.string.cloud_disconnect_failed_title,
                    R.string.cloud_disconnect_failed_body,
                )
            }
        }
    }

    companion object {
        private const val TAG = "BackupActivity"

        fun intent(context: Context): Intent = Intent(context, BackupActivity::class.java)
    }
}
