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
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.SharedBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug build only (this file has a no-op twin in `src/release`): a ⋯ button at the right of the
 * library's bottom bar with the actions that make key and store testing practical on a device —
 *  - **Show recovery key** — reveal + copy the global passphrase.
 *  - **Forget cached key** — clear the Keystore-cached passphrase and raw keys, then kill the
 *    process; the next launch must land on the Unlock screen with the file intact.
 *  - **Extension store self-test** (arc 11 / J2) — open-or-create the store of a fake package
 *    `probe.test` (`Garden/probe.test.db`), round-trip a value through a **real**
 *    [ExtensionStoreBinder] (calling uid = our own, so the gate's uid check passes), check the file
 *    header is encrypted, drive the 4 MiB `putLarge` / `getLarge` path through real ashmem, and
 *    prove the inline cap, the `STORE_VALUE_LARGE` refusal, the wrong-uid refusal and the revoked
 *    refusal; toast OK / FAIL. Room + SQLCipher + `SharedMemory` cannot run on the JVM, so this is
 *    the store's only on-device check until an extension actually uses it.
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
        )
        val actions = listOf<() -> Unit>(
            { showKey(activity) },
            { confirmForget(activity) },
            { storeSelfTest(activity) },
        )
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Debug tools")
                .setItems(labels) { _, which -> actions[which]() }
                .create()
        ).show()
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
        val t0 = System.currentTimeMillis()
        val db = ExtensionStores.open(activity, pkg)
        val openMs = System.currentTimeMillis() - t0
        val file = extensionStoreFile(activity, pkg)
        check(SoilCrypto.probe(file) == SoilFileKind.Encrypted) { "file is not encrypted" }
        // Called in-process, Binder.getCallingUid() is our own uid — so a real binder works here.
        val store = ExtensionStoreBinder(db, android.os.Process.myUid())
        val key = "probe:" + System.currentTimeMillis()
        val value = "hello".toByteArray()
        store.put(key, value)
        check(store.get(key)?.contentEquals(value) == true) { "get after put mismatch" }
        check(key in store.keys("probe:")) { "keys(prefix) missing the key" }
        check(store.keys("zzz").isEmpty()) { "keys(zzz) not empty" }
        store.delete(key)
        check(store.get(key) == null) { "get after delete not null" }
        // The large path: 4 MiB through real ashmem both ways.
        val bigKey = "probe:big:" + System.currentTimeMillis()
        val big = ByteArray(ExtensionContract.STORE_MAX_VALUE_BYTES) { (it % 251).toByte() }
        val t1 = System.currentTimeMillis()
        // In-process there is no parcel: the binder receives this very object and closes it itself
        // (over IPC the sender closes its own handle after the call — the region is dup'd into it).
        store.putLarge(bigKey, SharedBytes.write(big))
        val got = store.getLarge(bigKey) ?: error("getLarge returned null")
        val back = SharedBytes.readAndClose(got)
        val bigMs = System.currentTimeMillis() - t1
        check(back.contentEquals(big)) { "4 MiB round trip mismatch" }
        check(runCatching { store.get(bigKey) }.exceptionOrNull()?.message == ExtensionContract.STORE_VALUE_LARGE) {
            "get of a large value not refused"
        }
        check(runCatching { store.put("probe:inline", ByteArray(ExtensionContract.STORE_MAX_INLINE_BYTES + 1)) }
            .exceptionOrNull() is IllegalArgumentException) { "inline cap not enforced" }
        // An empty value rides a 1-byte region — ashmem refuses a zero-size one.
        val emptyKey = "probe:empty:" + System.currentTimeMillis()
        store.putLarge(emptyKey, SharedBytes.write(ByteArray(0)))
        check(SharedBytes.readAndClose(store.getLarge(emptyKey)!!).isEmpty()) { "empty large value mismatch" }
        store.delete(emptyKey)
        store.delete(bigKey)
        check(store.getLarge(bigKey) == null) { "getLarge after delete not null" }
        check(runCatching { store.get("") }.exceptionOrNull() is IllegalArgumentException) { "empty key accepted" }
        val other = ExtensionStoreBinder(db, android.os.Process.myUid() + 1)
        check(runCatching { other.get("x") }.exceptionOrNull() is SecurityException) { "wrong uid accepted" }
        store.revoke()
        check(runCatching { store.get("x") }.exceptionOrNull() is SecurityException) { "revoked binder accepted" }
        return "open ${openMs}ms, 4 MiB round trip ${bigMs}ms, ${file.name}"
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
