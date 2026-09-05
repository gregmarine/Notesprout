package com.symmetricalpalmtree.notesproutsn.library

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
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
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFile
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.extstore.StoreFormat
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

/**
 * Debug build only (this file has a no-op twin in `src/release`): a ⋯ button at the right of the
 * library's bottom bar with the actions that make key and store testing practical on a device —
 *  - **Show recovery key** — reveal + copy the global passphrase.
 *  - **Forget cached key** — clear the Keystore-cached passphrase and raw keys, then kill the
 *    process; the next launch must land on the Unlock screen with the file intact.
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
            "Show recovery key",
            "Forget cached key (relaunch → Unlock)",
            "Extension store self-test",
            "Cloud status",
            "WEBP encoder measurement",
        )
        val actions = listOf<() -> Unit>(
            { showKey(activity) },
            { confirmForget(activity) },
            { storeSelfTest(activity) },
            { cloudStatus(activity) },
            { webpProbe(activity) },
        )
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Debug tools")
                .setItems(labels) { _, which -> actions[which]() }
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

    private fun showKey(activity: AppCompatActivity) {
        val key = PassphraseStore.getGlobalPassphrase(activity) ?: "(none cached)"
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Recovery key")
                .setMessage(key)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText(activity.getString(R.string.recovery_clip_label), key)
                    )
                    Toast.makeText(activity, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .create()
        ).show()
    }

    private fun confirmForget(activity: AppCompatActivity) {
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Forget cached key?")
                .setMessage("Clears the Keystore-cached passphrase and raw keys and closes the app. " +
                    "The next launch shows the Unlock screen. Have the recovery key ready.")
                .setPositiveButton("Forget & close") { _, _ ->
                    PassphraseStore.clearGlobalPassphrase(activity)
                    KeyMaterial.clearAll(activity)
                    KeySession.clear()
                    Toast.makeText(activity, "Forgotten — relaunch Notesprout SN", Toast.LENGTH_SHORT).show()
                    activity.finishAffinity()
                    // The index is still open in this process; a relaunch of the same process would
                    // find it READY. Kill the process so the next launch really re-runs bootstrap.
                    Handler(Looper.getMainLooper()).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 400L)
                }
                .setNegativeButton("Cancel", null)
                .create()
        ).show()
    }
}
