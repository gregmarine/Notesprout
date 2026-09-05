package com.symmetricalpalmtree.notesproutsn.library

import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseCache
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseRules
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilRekey
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFile
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.extstore.StoreFormat
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_NOTEBOOK
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.databinding.DialogPassphraseNewBinding
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import com.symmetricalpalmtree.notesproutsn.extension.StorePayload
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Debug build only (this file has a no-op twin in `src/release`): a ⋯ button at the right of the
 * library's bottom bar with the actions that make key and store testing practical on a device —
 *  (Its first two items, **Show recovery key** and **Forget cached key**, were removed at arc 26 / U1
 *  — the Encryption screen carries both in every build now, decision 14.)
 *  - **Extension store self-test** (arc 11 / J2, grown to tables at arc 22 / X1) — the store's only
 *    on-device check, because SQLCipher, `SharedMemory` and a real `Binder` cannot run on the JVM.
 *    Through a **real** [ExtensionStoreBinder] (calling uid = our own, so the gate's uid check
 *    passes) over the store of a fake package `probe.test` (`Garden/probe.test.db`, recreated fresh
 *    each run): encrypted header → `applySchema` v1 → `exec` of 5 000 stroke-shaped rows in two
 *    batches (each rides ashmem) → a `query` that streams them back in more than one chunk with
 *    byte-exact equality → a failing batch (a constraint violation mid-list) leaves zero new rows →
 *    `applySchema` v2 (`ADD COLUMN`) then v1 again refused with `STORE_SCHEMA_NEWER` → a denylisted
 *    statement, `host_schema` and a two-statement string all refused as `IllegalArgumentException`
 *    → `exec` before `applySchema` refused with `STORE_SCHEMA_UNAPPLIED` → wrong uid / revoked
 *    refused → a **legacy-shaped file** (`Garden/probe.legacy.db`, built by the probe itself with a
 *    `kv` table at `user_version 1`) opens as a wipe to format version 2. Timings in the summary.
 *  - **Cloud status** (arc 25 / V1) — the one on-device proof that a trusted cloud provider is
 *    discovered and binds: `ExtensionRegistry.cloud()` then one `CloudClient.status()`, reported in
 *    a dialog (provider, api version, provider name, configured, connected, account). Package
 *    visibility, the signature check and the API-8 floor cannot be exercised on the JVM.
 *  - **Cloud probe** ([CloudProbe], arc 25 / V2) — the measurement tool that fills in
 *    `CloudTimeouts`: every method of the cloud seam once, in order, timed, with a 1 MiB and a
 *    20 MiB upload and a 20 MiB download against the person's real account. Durations land in a
 *    dialog **and** in logcat as `probe: <op> <n> ms`. It writes to `Exports/probe/` and deletes
 *    what it wrote.
 *  - **Rekey one notebook round-trip** / **Break a rekey commit** ([RekeyProbe], arc 26 / U2) —
 *    the on-device proof of `SoilRekey`: global → throwaway → global with `integrity_check` and row
 *    counts before/between/after, and a hand-made "death between the two renames" that the next
 *    launch's `recoverGarden` must put right (the process is killed on purpose, like Forget).
 *  - **Change key scope (debug)** (arc 26 / U4) — the only way to *make* a `NOTEBOOK`-scope
 *    notebook until U5 puts the door on the glass: pick a notebook, and it re-keys in place either
 *    onto a passphrase you type (GLOBAL → NOTEBOOK) or back onto the device's own (NOTEBOOK →
 *    GLOBAL, after the real unlock prompt has verified the current one). Both directions are one
 *    `SoilRekey.rekeyInPlace` plus `IndexRepository.setEncryptionState`, which is exactly what U5's
 *    real door will do — so what is walked here is the shipping path, not a stand-in.
 *  - **WEBP encoder measurement** ([WebpProbe]) — lossless vs lossy-q100 on this device's own page
 *    size, for the open question in `BuiltInTemplates.toWebp`. Skia's encoders are the subject, so
 *    no host tool can answer it; run it on every device tier before changing the format.
 */
object DebugMenu {

    fun install(activity: AppCompatActivity, bar: ViewGroup) {
        val btn = AppCompatImageButton(activity, null, 0).apply {
            setImageResource(R.drawable.ic_dots)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            val size = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
            val pad = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            contentDescription = activity.getString(R.string.cd_debug)
            scaleType = ImageView.ScaleType.FIT_CENTER
            stateListAnimator = null
        }
        TooltipCompat.setTooltipText(btn, btn.contentDescription)
        btn.setOnClickListener { showSheet(activity) }
        bar.addView(btn)
    }

    private fun showSheet(activity: AppCompatActivity) {
        val labels = arrayOf<CharSequence>(
            "Extension store self-test",
            "Cloud status",
            "Cloud probe",
            "WEBP encoder measurement",
            "Rekey one notebook round-trip (debug)",
            "Break a rekey commit (debug)",
            "Change key scope (debug)",
        )
        val actions = listOf<() -> Unit>(
            { storeSelfTest(activity) },
            { cloudStatus(activity) },
            { cloudProbe(activity) },
            { webpProbe(activity) },
            { pickNotebook(activity, "Rekey round-trip") { id -> rekeyRoundTrip(activity, id) } },
            { pickNotebook(activity, "Break a rekey commit") { id -> breakRekeyCommit(activity, id) } },
            { pickNotebook(activity, "Change key scope") { id -> changeKeyScope(activity, id) } },
        )
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Debug tools")
                .setItems(labels) { _, which -> actions[which]() }
                .create()
        ).show()
    }

    /** A one-tap list of the alive notebooks, by name, for the two rekey tools. */
    private fun pickNotebook(activity: AppCompatActivity, title: String, onPick: (String) -> Unit) {
        activity.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                SnIndex.dao().allAliveOfType(SoilSchema.TYPE_NOTEBOOK).sortedBy { it.name.lowercase() }
            }
            if (rows.isEmpty()) {
                Dialogs.problem(activity, title, "No notebooks in the library.")
                return@launch
            }
            Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setItems(rows.map { it.name.ifEmpty { it.id } as CharSequence }.toTypedArray()) { _, which -> onPick(rows[which].id) }
                    .create()
            ).show()
        }
    }

    /**
     * **Change key scope** (arc 26 / U4) — GLOBAL ⇄ NOTEBOOK for one notebook, on the device.
     *
     * Both directions are the same three steps in the same order, and the order is the safety:
     * re-key the file first ([SoilRekey.rekeyInPlace], which is atomic and leaves the original
     * untouched on any failure), then record the scope in the index
     * ([IndexRepository.setEncryptionState], which nulls the cover, clears both backup stamps and
     * forgets the unlock), then park the new passphrase for the very next open
     * ([PassphraseCache.storeOnce]) so the walk is not asked for what it typed a second ago. An
     * index that disagrees with the file is the one state worth avoiding, and a re-key that failed
     * never reaches step two.
     *
     * The current passphrase is never guessed: going *to* NOTEBOOK it is the session's, going
     * *back* it is collected by the real [NotebookPassphrasePrompt], which verifies against the
     * file before this ever runs. A notebook open in this process is refused outright — one file,
     * one connection, and a re-key under a live writer is not a thing to find out about later.
     */
    private fun changeKeyScope(activity: AppCompatActivity, notebookId: String) {
        val file = soilFile(activity, notebookId)
        if (SoilOpenFiles.isOpen(file)) {
            Toast.makeText(activity, "That notebook is open in this process — close it first.", Toast.LENGTH_LONG).show()
            return
        }
        activity.lifecycleScope.launch {
            val repo = IndexRepository()
            val name = repo.summary(notebookId)?.name.orEmpty().ifEmpty { notebookId }
            when (repo.keyScope(notebookId)) {
                KeyScope.GLOBAL -> {
                    val session = KeySession.get()
                    if (session == null) {
                        Dialogs.problem(activity, "Change key scope", "No global key in session.")
                        return@launch
                    }
                    val typed = askNewPassphrase(activity, name) ?: return@launch
                    runRekey(activity, "Now NOTEBOOK scope") {
                        SoilRekey.rekeyInPlace(
                            activity, file, notebookId,
                            oldPassphrase = session, newPassphrase = typed,
                            keyScope = KEY_SCOPE_NOTEBOOK,
                        )
                        repo.setEncryptionState(notebookId, KeyScope.NOTEBOOK)
                        PassphraseCache.storeOnce(notebookId, typed)
                    }
                }

                KeyScope.NOTEBOOK -> {
                    // The shipping prompt, not a debug one: it verifies against the file, so the
                    // passphrase this hands back is known to be the one the re-key must read with.
                    val current = NotebookPassphrasePrompt.ask(activity, notebookId, name) ?: return@launch
                    val session = KeySession.get()
                    if (session == null) {
                        Dialogs.problem(activity, "Change key scope", "No global key in session.")
                        return@launch
                    }
                    runRekey(activity, "Now GLOBAL scope") {
                        SoilRekey.rekeyInPlace(
                            activity, file, notebookId,
                            oldPassphrase = current, newPassphrase = session,
                            keyScope = KEY_SCOPE_GLOBAL,
                        )
                        repo.setEncryptionState(notebookId, KeyScope.GLOBAL)
                    }
                }
            }
        }
    }

    /** New + confirm, [PassphraseRules] on the way out, and the IME never hidden (a Ratta hardware
     *  keyboard types only while it is shown). The "new passphrase" layout with its mode radios
     *  taken away — a debug tool that only ever chooses its own. */
    private suspend fun askNewPassphrase(activity: AppCompatActivity, name: String): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val view = DialogPassphraseNewBinding.inflate(activity.layoutInflater)
            view.modeGroup.visibility = android.view.View.GONE
            view.ownFields.visibility = android.view.View.VISIBLE
            var accepted: String? = null
            val dialog = Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle("Passphrase for $name")
                    .setView(view.root)
                    .setPositiveButton("Set", null)
                    .setNegativeButton("Cancel", null)
                    .create()
            )
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            dialog.setOnDismissListener { if (cont.isActive) cont.resume(accepted) }
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = view.newField.text?.toString().orEmpty()
                val confirm = view.confirmField.text?.toString().orEmpty()
                when (PassphraseRules.check(typed, confirm)) {
                    PassphraseRules.Verdict.OK -> {
                        accepted = PassphraseRules.normalize(typed)
                        dialog.dismiss()
                    }

                    PassphraseRules.Verdict.TOO_SHORT -> showError(view, "At least ${PassphraseRules.MIN_LENGTH} characters.")
                    PassphraseRules.Verdict.MISMATCH -> showError(view, "The two entries do not match.")
                    PassphraseRules.Verdict.SAME_AS_CURRENT -> showError(view, "That is the passphrase already in force.")
                }
            }
        }

    private fun showError(view: DialogPassphraseNewBinding, message: String) {
        view.error.visibility = android.view.View.VISIBLE
        view.error.text = message
    }

    /** The re-key itself under a non-cancellable dialog — 4–8 s on the Nomad, and a screen that
     *  says nothing for that long reads as a hang on e-ink. */
    private suspend fun runRekey(activity: AppCompatActivity, done: String, work: suspend () -> Unit) {
        val progress = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Change key scope")
                .setMessage("Re-keying…")
                .setCancelable(false)
                .create()
        ).also { it.show() }
        val error = try {
            work()
            null
        } catch (e: Exception) {
            e
        } finally {
            runCatching { progress.dismiss() }
        }
        if (error != null) {
            Slog.d("DebugMenu") { "change key scope failed: ${error.javaClass.simpleName}" }
            Dialogs.problem(activity, "Change key scope", error.message ?: error.javaClass.simpleName)
            return
        }
        Toast.makeText(activity, done, Toast.LENGTH_LONG).show()
        // The library's cards are built from the index row this just changed — the cover is gone
        // and a lock belongs in its place, and only a rebind will show that.
        activity.recreate()
    }

    private fun rekeyRoundTrip(activity: AppCompatActivity, notebookId: String) {
        val progress = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Rekey round-trip")
                .setMessage("Re-keying — two KDFs and two full copies…")
                .setCancelable(false)
                .create()
        ).also { it.show() }
        activity.lifecycleScope.launch {
            val text = try {
                RekeyProbe.roundTrip(activity, notebookId)
            } finally {
                runCatching { progress.dismiss() }
            }
            Slog.d("DebugMenu") { "rekey round-trip\n$text" }
            Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle("Rekey round-trip")
                    .setMessage(text)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("rekey round-trip", text))
                        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .create()
            ).show()
        }
    }

    /** Variant chooser, then the file ops, then the process ends (Forget's shape) so the next
     *  launch is a real Bootstrap over the broken Garden. */
    private fun breakRekeyCommit(activity: AppCompatActivity, notebookId: String) {
        val variants = arrayOf<CharSequence>(
            "A — tmp verifies (Bootstrap renames the tmp in)",
            "B — tmp is garbage (Bootstrap renames .old.bak back)",
        )
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Break a rekey commit")
                .setItems(variants) { _, which ->
                    val variant = if (which == 0) RekeyProbe.Break.TMP_VERIFIES else RekeyProbe.Break.TMP_GARBAGE
                    activity.lifecycleScope.launch {
                        val text = RekeyProbe.breakCommit(activity, notebookId, variant)
                        Slog.d("DebugMenu") { "break rekey commit: $text" }
                        val broke = !text.startsWith("FAIL")
                        Dialogs.style(
                            AlertDialog.Builder(activity)
                                .setTitle("Break a rekey commit")
                                .setMessage(text + if (broke) "\n\nClosing the app now." else "")
                                .setCancelable(false)
                                .setPositiveButton("OK") { _, _ ->
                                    if (broke) {
                                        activity.finishAffinity()
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            android.os.Process.killProcess(android.os.Process.myPid())
                                        }, 300L)
                                    }
                                }
                                .create()
                        ).show()
                    }
                }
                .create()
        ).show()
    }

    /**
     * [WebpProbe] off Main, then the report in a dialog with a Copy button — the numbers are meant
     * to be pasted back into a decision, and a toast cannot hold a table.
     *
     * **The run takes minutes, not seconds** (a page-sized `WEBP_LOSSLESS` encode is seconds by
     * itself, and there are four encodes per case), which is why the dialog is not the only way out:
     * every row is logged and appended to [WebpProbe.reportFile] as it finishes, so `adb logcat -s
     * DebugMenu` or an `adb pull` of that file gets the numbers even if the screen is left. The
     * first version reported only at the end and read as "it just shows a toast".
     */
    private fun webpProbe(activity: AppCompatActivity) {
        Toast.makeText(
            activity,
            "Measuring — MINUTES, not seconds. Rows land in ${WebpProbe.reportFile(activity).name} as they finish.",
            Toast.LENGTH_LONG,
        ).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val rows = WebpProbe.run(activity) { row ->
                        // Progress on the glass: one toast per finished case, so a long run never
                        // looks hung. The file and the log already have the row by this point.
                        activity.runOnUiThread {
                            Toast.makeText(activity, "${row.label}: ${row.pixels}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    WebpProbe.report(activity, rows)
                }
            }
            val text = result.getOrElse { "FAILED — ${it.message}" }
            Slog.d("DebugMenu") { "webp probe\n$text" }
            Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle("WEBP encoder measurement")
                    .setMessage(text)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("webp probe", text))
                        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .create()
            ).show()
        }
    }

    /**
     * Arc 25 / V1's one on-device proof: that the host **discovers** a trusted cloud provider and can
     * **bind** it and get an answer back. Nothing else on the glass reaches the cloud point until V2,
     * and neither package visibility, the same-signature check, the API floor nor a real Binder can
     * be exercised on the JVM.
     *
     * A dialog, not a toast: it is a small table the tester has to read (and often paste into a
     * phase note), and on e-ink a toast that has already faded reads as "nothing happened".
     *
     * The account label is shown here — this is the person's own screen — but it is **never logged**;
     * [CloudClient]'s own line carries the booleans and the duration only.
     */
    private fun cloudStatus(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val ref = ExtensionRegistry.cloud(activity)
            if (ref == null) {
                Dialogs.style(
                    AlertDialog.Builder(activity)
                        .setTitle("Cloud status")
                        .setMessage("No cloud provider installed (or none trusted).")
                        .setPositiveButton("Close", null)
                        .create()
                ).show()
                return@launch
            }
            val text = try {
                val status = CloudClient.status(activity, ref)
                buildString {
                    append("Provider: ${ref.label} (${ref.packageName}, api ${ref.apiVersion})\n")
                    append("Name: ${status.providerName}\n")
                    append("Configured: ${if (status.configured) "yes" else "no"}\n")
                    append("Connected: ${if (status.connected) "yes" else "no"}\n")
                    append("Account: ${status.accountLabel.ifEmpty { "—" }}")
                }
            } catch (e: ExtensionCallException) {
                "Cloud status: FAIL — ${e.message}"
            }
            Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle("Cloud status")
                    .setMessage(text)
                    .setPositiveButton("Close", null)
                    .create()
            ).show()
        }
    }

    /**
     * The V2 measurement run ([CloudProbe]). A progress dialog names each step as it lands, because
     * the whole thing is a minute or more of network on a Supernote and a screen that says nothing
     * for that long reads as a hang; every row is also in logcat by the time it appears there.
     *
     * The report gets a Copy button for the same reason [webpProbe]'s does — these numbers go
     * straight into `CloudTimeouts` and into the phase note.
     */
    private fun cloudProbe(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val ref = ExtensionRegistry.cloud(activity)
            if (ref == null) {
                Dialogs.style(
                    AlertDialog.Builder(activity)
                        .setTitle("Cloud probe")
                        .setMessage("No cloud provider installed (or none trusted).")
                        .setPositiveButton("Close", null)
                        .create()
                ).show()
                return@launch
            }
            val progress = Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle("Cloud probe")
                    .setMessage("Starting…")
                    .setCancelable(false)
                    .create()
            ).also { it.show() }
            val text = try {
                CloudProbe.run(activity, ref) { step ->
                    activity.runOnUiThread { progress.setMessage("${step.op}: ${step.ms} ms") }
                }
            } finally {
                runCatching { progress.dismiss() }
            }
            Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle("Cloud probe")
                    .setMessage(text)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("cloud probe", text))
                        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .create()
            ).show()
        }
    }

    private fun storeSelfTest(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { runStoreProbe(activity) } }
            val msg = result.fold({ "Extension store: OK ($it)" }, { "Extension store: FAIL — ${it.message}" })
            Slog.d("DebugMenu") { msg }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** IO. Returns a short summary; throws on the first failed check. */
    private fun runStoreProbe(activity: AppCompatActivity): String {
        val pkg = "probe.test"
        // The probe's own files — recreated fresh so every run proves the create door. Anything this
        // process holds open is closed first (a cached store is never closed otherwise).
        ExtensionStores.closeAll()
        val file = extensionStoreFile(activity, pkg)
        deleteWithSidecars(file)
        val t0 = System.currentTimeMillis()
        val db = ExtensionStores.open(activity, pkg)
        val openMs = System.currentTimeMillis() - t0
        check(SoilCrypto.probe(file) == SoilFileKind.Encrypted) { "file is not encrypted" }
        check(userVersion(db.writable()) == StoreFormat.VERSION) { "fresh store not at format ${StoreFormat.VERSION}" }
        // Called in-process, Binder.getCallingUid() is our own uid — so a real binder works here.
        val store: IExtensionStore = ExtensionStoreBinder(db, android.os.Process.myUid())

        // Structural: nothing before the declaration.
        expectIse(ExtensionContract.STORE_SCHEMA_UNAPPLIED, "exec before applySchema") {
            StoreReads.exec(store, "DELETE FROM stroke")
        }
        check(store.schemaVersion() == 0) { "fresh store schemaVersion != 0" }
        val v1 = StoreSchema(1, listOf(listOf(
            "CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL, \"order\" INTEGER NOT NULL, blob BLOB NOT NULL)",
            "CREATE INDEX stroke_page_order ON stroke(pageId, \"order\")",
        )))
        store.applySchema(v1)
        check(store.schemaVersion() == 1) { "schemaVersion after v1 != 1" }
        store.applySchema(v1)   // idempotent
        check(store.schemaVersion() == 1) { "schemaVersion after v1 again != 1" }

        // 5 000 stroke-shaped rows, 1 KiB blobs, two batches — each batch rides ashmem (≈ 2.7 MB).
        val rows = 5_000
        val blobOf = { i: Int -> ByteArray(1024) { j -> ((i * 31 + j) % 251).toByte() } }
        val t1 = System.currentTimeMillis()
        for (half in 0 until 2) {
            val batch = (half * rows / 2 until (half + 1) * rows / 2).map { i ->
                Statement("INSERT INTO stroke (id, pageId, \"order\", blob) VALUES (?, ?, ?, ?)", "s$i", "p${i % 20}", i, blobOf(i))
            }
            val changes = StoreReads.exec(store, batch)
            check(changes.size == batch.size && changes.all { it == 1L }) { "batch $half changes() wrong" }
        }
        val batchMs = System.currentTimeMillis() - t1

        // Read back in more than one chunk (≈ 5.2 MB of rows against a 4 MiB chunk), byte-exact.
        val t2 = System.currentTimeMillis()
        var chunks = 0
        val statement = StorePayload.of(StoreCodec.encodeStatements(listOf(Statement("SELECT id, \"order\", blob FROM stroke ORDER BY \"order\""))))
        var result = store.query(statement)
        var seen = 0
        while (true) {
            chunks++
            val decoded = StoreCodec.decodeRows(result.payload.readAndClose())
            for (row in decoded) {
                val i = row.long("order").toInt()
                check(i == seen) { "row order broke at $seen (got $i)" }
                check(row.text("id") == "s$i") { "id mismatch at $i" }
                check(row.blob("blob").contentEquals(blobOf(i))) { "blob mismatch at $i" }
                seen++
            }
            if (!result.more) break
            result = store.next(result.handle)
        }
        val readMs = System.currentTimeMillis() - t2
        check(seen == rows) { "read back $seen of $rows rows" }
        check(chunks > 1) { "read back in $chunks chunk(s) — expected more than one" }
        check(StoreReads.all(store, "SELECT count(*) AS n FROM stroke")[0].long("n") == rows.toLong()) { "count != $rows" }

        // A failing batch (duplicate primary key mid-list) leaves ZERO new rows.
        val bad = listOf(
            Statement("INSERT INTO stroke (id, pageId, \"order\", blob) VALUES (?, ?, ?, ?)", "new-1", "p0", 900_001, byteArrayOf(1)),
            Statement("INSERT INTO stroke (id, pageId, \"order\", blob) VALUES (?, ?, ?, ?)", "s0", "p0", 900_002, byteArrayOf(2)),
            Statement("INSERT INTO stroke (id, pageId, \"order\", blob) VALUES (?, ?, ?, ?)", "new-3", "p0", 900_003, byteArrayOf(3)),
        )
        check(runCatching { StoreReads.exec(store, bad) }.exceptionOrNull() is IllegalStateException) { "constraint violation not an ISE" }
        check(StoreReads.all(store, "SELECT count(*) AS n FROM stroke WHERE id LIKE 'new-%'")[0].long("n") == 0L) { "failed batch left rows behind" }

        // Schema forward, never back.
        val v2 = StoreSchema(2, v1.steps + listOf(listOf("ALTER TABLE stroke ADD COLUMN width REAL NOT NULL DEFAULT 1")))
        store.applySchema(v2)
        check(store.schemaVersion() == 2) { "schemaVersion after v2 != 2" }
        check(StoreReads.all(store, "SELECT width FROM stroke LIMIT 1")[0].real("width") == 1.0) { "ADD COLUMN default not seen" }
        expectIse(ExtensionContract.STORE_SCHEMA_NEWER, "downgrade") { store.applySchema(v1) }

        // The validator at the door.
        expectIae("PRAGMA") { StoreReads.all(store, "PRAGMA user_version") }
        expectIae("host_schema") { StoreReads.all(store, "SELECT * FROM host_schema") }
        expectIae("two statements") { StoreReads.exec(store, "DELETE FROM stroke; DROP TABLE stroke") }
        expectIae("write via query") { StoreReads.all(store, "WITH x AS (SELECT 1) DELETE FROM stroke") }
        expectIae("bad schema") { StoreSchema(1, listOf(listOf("CREATE VIEW v AS SELECT 1"))) }
        // host_schema is still exactly what it was after all that.
        check(store.schemaVersion() == 2) { "host_schema moved" }

        // Trust: wrong uid, then revoked.
        val other = ExtensionStoreBinder(db, android.os.Process.myUid() + 1)
        check(runCatching { other.schemaVersion() }.exceptionOrNull() is SecurityException) { "wrong uid accepted" }
        (store as ExtensionStoreBinder).revoke()
        check(runCatching { store.schemaVersion() }.exceptionOrNull() is SecurityException) { "revoked binder accepted" }

        // A legacy-shaped file (the arc-11 Room key/value store) opens as a wipe to format 2.
        val legacyPkg = "probe.legacy"
        val legacyFile = extensionStoreFile(activity, legacyPkg)
        deleteWithSidecars(legacyFile)
        buildLegacyStore(legacyFile)
        val t3 = System.currentTimeMillis()
        val legacy = ExtensionStores.open(activity, legacyPkg)
        val wipeMs = System.currentTimeMillis() - t3
        check(userVersion(legacy.writable()) == StoreFormat.VERSION) { "legacy store not at format ${StoreFormat.VERSION} after open" }
        check(!legacy.hasTable("kv") && !legacy.hasTable("room_master_table")) { "legacy tables survived the wipe" }
        check(legacy.hasTable(StoreFormat.HOST_SCHEMA_TABLE)) { "host_schema missing after the wipe" }
        val legacyStore = ExtensionStoreBinder(legacy, android.os.Process.myUid())
        check(legacyStore.schemaVersion() == 0) { "wiped store schemaVersion != 0" }
        legacyStore.revoke()

        return "open ${openMs}ms · 5 000 rows in ${batchMs}ms · read back $chunks chunks in ${readMs}ms · legacy wipe ${wipeMs}ms · ${file.name}"
    }

    private fun expectIse(message: String, what: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        check(e is IllegalStateException && e.message == message) { "$what: expected ISE($message), got ${e?.javaClass?.simpleName}: ${e?.message}" }
    }

    private fun expectIae(what: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        check(e is IllegalArgumentException) { "$what: expected IAE, got ${e?.javaClass?.simpleName}: ${e?.message}" }
    }

    private fun userVersion(db: androidx.sqlite.db.SupportSQLiteDatabase): Int =
        db.query("PRAGMA user_version").use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun deleteWithSidecars(file: File) {
        for (f in listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))) {
            if (f.exists()) check(f.delete()) { "could not delete ${f.name}" }
        }
    }

    /** The arc-11 shape: `kv` + `room_master_table`, `user_version 1`, under the global key. */
    private fun buildLegacyStore(file: File) {
        val pass = KeySession.get() ?: error("no global key in session")
        val db = SoilCrypto.createRaw(file, pass)
        try {
            db.execSQL("CREATE TABLE kv (`key` TEXT NOT NULL PRIMARY KEY, `value` BLOB NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO kv VALUES ('pages', X'00', 0)")
            db.execSQL("INSERT INTO kv VALUES ('current', X'00', 0)")
            db.execSQL("PRAGMA user_version = ${StoreFormat.LEGACY_KV_VERSION}")
        } finally {
            db.close()
        }
    }
}
