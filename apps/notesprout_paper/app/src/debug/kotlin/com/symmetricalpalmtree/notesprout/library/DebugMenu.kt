package com.symmetricalpalmtree.notesprout.library

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.crypto.KeyMaterial
import com.symmetricalpalmtree.notesprout.crypto.KeySession
import com.symmetricalpalmtree.notesprout.crypto.PassphraseStore

/**
 * Debug build only (this file has a no-op twin in `src/release`): a ⋯ button on the library's
 * top bar with the two actions that make unlock testing practical on a device —
 *  - **Show recovery key** — reveal + copy the global passphrase.
 *  - **Forget cached key** — clear the Keystore-cached passphrase and raw keys, then kill the
 *    process; the next launch must land on the Unlock screen with the file intact.
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
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            stateListAnimator = null
        }
        TooltipCompat.setTooltipText(btn, btn.contentDescription)
        btn.setOnClickListener { showSheet(activity) }
        bar.addView(btn)
    }

    private fun showSheet(activity: AppCompatActivity) {
        ActionSheetDialog(activity)
            .title("Debug tools")
            .addAction(null, "Show recovery key") { showKey(activity) }
            .addAction(null, "Forget cached key (relaunch → Unlock)") { confirmForget(activity) }
            .show()
    }

    private fun showKey(activity: AppCompatActivity) {
        val key = PassphraseStore.getGlobalPassphrase(activity) ?: "(none cached)"
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle("Recovery key")
                .setMessage(key)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Paper recovery key", key))
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
                    Toast.makeText(activity, "Forgotten — relaunch Paper", Toast.LENGTH_SHORT).show()
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
