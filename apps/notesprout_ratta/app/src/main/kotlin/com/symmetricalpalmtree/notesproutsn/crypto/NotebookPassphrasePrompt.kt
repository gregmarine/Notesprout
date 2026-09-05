package com.symmetricalpalmtree.notesproutsn.crypto

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.databinding.DialogNotebookPassphraseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * **The one "this notebook's passphrase" dialog** (arc 26 / U4, D3) — every open site that may
 * prompt for a `NOTEBOOK`-scope notebook uses it: the notebook screen, a link follow, the picker's
 * lock row, the export screen.
 *
 * Verify-then-accept, inside one dialog: a wrong entry keeps the dialog and its typing and shows
 * the inline error; the attempt bucket is **the notebook id** ([AttemptLimiter]), and while it is
 * locked out the entry row is GONE and the countdown takes its place (Unlock's shape). The IME is
 * never hidden — on Ratta a hardware keyboard types only while it is shown.
 *
 * [PassphraseCache] is **not** consulted here. The parked hand-off (create, import, a scope
 * change, a link follow) belongs to the notebook screen's own open alone — [takeParked] is what
 * that screen calls first — so a picker drill or an export never silently spends it and the next
 * open of the notebook asks as expected. A parked value is still verified before it is accepted:
 * a hand-off, not a promise.
 *
 * On success the passphrase is returned to the caller for the open, the notebook is marked in
 * [NotebookUnlocks], and the raw key is warmed ([KeyOpener.warm]) so later silent reads and the
 * next open are raw-key fast. The passphrase is never stored, logged, or put in an Intent; the
 * caller lets it fall out of scope with the open.
 */
object NotebookPassphrasePrompt {

    /** The passphrase that opens [notebookId], or null when the person cancelled (or the activity
     *  is going away). Suspends across the whole verify loop; call from the activity's scope. */
    suspend fun ask(activity: Activity, notebookId: String, name: String): String? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val typed = dialog(activity, notebookId, name) ?: return null
        accept(activity, notebookId, typed)
        return typed
    }

    /**
     * The notebook screen's first question (arc 26 / U4): a passphrase parked for this one open by
     * the door that just collected it — verified against the file, accepted like a typed one, and
     * gone. Null when nothing is parked, it expired, or it does not fit; the caller then [ask]s.
     */
    suspend fun takeParked(activity: Activity, notebookId: String): String? {
        val parked = PassphraseCache.takeOnce(notebookId) ?: return null
        val file = soilFile(activity, notebookId)
        if (!withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, parked) }) return null
        accept(activity, notebookId, parked)
        return parked
    }

    private fun accept(activity: Activity, notebookId: String, passphrase: String) {
        AttemptLimiter.recordSuccess(activity, notebookId)
        NotebookUnlocks.mark(notebookId)
        KeyOpener.warm(activity, notebookId, soilFile(activity, notebookId), passphrase)
    }

    /** The dialog itself; resumes with a **verified** passphrase or null. */
    private suspend fun dialog(activity: Activity, notebookId: String, name: String): String? =
        suspendCancellableCoroutine { cont ->
            val app = activity.applicationContext
            val file = soilFile(activity, notebookId)
            val view = DialogNotebookPassphraseBinding.inflate(activity.layoutInflater)
            view.body.text = activity.getString(R.string.notebook_passphrase_body, name)
            val handler = Handler(Looper.getMainLooper())
            val scope = CoroutineScope(Dispatchers.Main + Job())
            var accepted: String? = null
            var busy = false
            var lockedOut = false
            val dialog = Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle(R.string.notebook_passphrase_title)
                    .setView(view.root)
                    .setPositiveButton(R.string.notebook_passphrase_open, null)
                    .setNegativeButton(R.string.cancel, null)
                    .create()
            )
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )

            fun refreshLockout() {
                val remaining = AttemptLimiter.check(app, notebookId) - System.currentTimeMillis()
                if (remaining > 0) {
                    lockedOut = true
                    view.entryRow.visibility = View.GONE
                    view.lockoutText.visibility = View.VISIBLE
                    view.lockoutText.text = activity.getString(R.string.unlock_locked_out, formatSeconds(remaining))
                    handler.postDelayed({ if (dialog.isShowing) refreshLockout() }, 1000L)
                } else {
                    view.lockoutText.visibility = View.GONE
                    view.entryRow.visibility = View.VISIBLE
                    // A lockout that just lifted is a fresh start: the error that earned it goes,
                    // and the field takes focus so the keyboard is up for the next try.
                    if (lockedOut) { lockedOut = false; view.error.visibility = View.GONE; view.field.requestFocus() }
                }
            }

            fun attempt() {
                if (busy) return
                val typed = view.field.text?.toString()?.trim().orEmpty()
                if (typed.isEmpty()) return
                if (AttemptLimiter.check(app, notebookId) > System.currentTimeMillis()) { refreshLockout(); return }
                busy = true
                view.error.visibility = View.GONE
                view.progress.visibility = View.VISIBLE
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, typed) }
                    busy = false
                    if (!dialog.isShowing) return@launch
                    view.progress.visibility = View.GONE
                    if (ok) {
                        accepted = typed
                        dialog.dismiss()
                    } else {
                        AttemptLimiter.recordFailure(app, notebookId)
                        view.error.visibility = View.VISIBLE
                        view.field.text?.clear()
                        refreshLockout()
                    }
                }
            }

            dialog.setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                scope.coroutineContext[Job]?.cancel()
                if (cont.isActive) cont.resume(accepted)
            }
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
            // Wired after show(): the default listener dismisses before anything can object.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { attempt() }
            view.field.setOnEditorActionListener { _, _, _ -> attempt(); true }
            refreshLockout()
            // Focus first, so the IME rises with the dialog instead of after a tap on the field.
            view.field.requestFocus()
        }

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }
}
