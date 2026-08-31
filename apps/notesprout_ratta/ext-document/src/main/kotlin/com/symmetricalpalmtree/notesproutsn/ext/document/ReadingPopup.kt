package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * "Reading this page…" / "Reading the pages…" — the editor's own progress dialog, and the visual
 * twin of the banner the host shows when it seeds a page before launching this screen. The host
 * cannot show it for a flip or a scope switch: it is *stopped* behind the editor, and a dialog on a
 * stopped activity's window is invisible at best (og's rule, and the EPD rules' reason).
 *
 * **Never `cancelable`, and that is not the same as never cancellable.** Back and a tap outside must
 * not take the dialog away and leave a read running unwatched — so when a wait *can* be taken back,
 * it is taken back by a real Cancel **button** and nothing else (M7). Two waits earn one:
 * entering the notebook scope, and a Merge — both can walk every page of a notebook and recognize
 * each one. A page flip, a page's Bring in and the switch back to a page read exactly one page, and
 * carry no button at all: there is nothing there worth interrupting.
 *
 * The delay is the whole reason this is a class rather than three lines: a **flip** (and a scope
 * switch) shows it only if the move is still running after
 * [PageFlipController.READING_POPUP_DELAY_MS], so an already-drafted page arrives with no dialog
 * flash — a flash on e-ink is a full black frame and back. A **Bring in / Merge** shows it
 * immediately, because it always reads in full.
 */
internal class ReadingPopup(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val showTick = Runnable { showNow() }
    private var dialog: AlertDialog? = null

    /** What the next show says, and whether it offers a way out — armed by [show] / [showAfter]. */
    @StringRes
    private var messageRes: Int = R.string.document_reading_page
    private var onCancel: (() -> Unit)? = null

    /** Up now — the caller knows the wait is real. */
    fun show(
        @StringRes messageRes: Int = R.string.document_reading_page,
        onCancel: (() -> Unit)? = null,
    ) {
        arm(messageRes, onCancel)
        showNow()
    }

    /** Up in [delayMs], unless [hide] gets there first. */
    fun showAfter(
        delayMs: Long,
        @StringRes messageRes: Int = R.string.document_reading_page,
        onCancel: (() -> Unit)? = null,
    ) {
        arm(messageRes, onCancel)
        main.postDelayed(showTick, delayMs)
    }

    /** Down, and un-scheduled. Safe to call when it was never shown. */
    fun hide() {
        main.removeCallbacks(showTick)
        dialog?.dismiss()
        dialog = null
        onCancel = null
    }

    private fun arm(@StringRes messageRes: Int, onCancel: (() -> Unit)?) {
        main.removeCallbacks(showTick)
        this.messageRes = messageRes
        this.onCancel = onCancel
    }

    private fun showNow() {
        if (activity.isFinishing || activity.isDestroyed || dialog != null) return
        // Read before the builder runs: `hide()` clears the field, and the listener below calls it.
        val cancel = onCancel
        val builder = AlertDialog.Builder(activity)
            .setMessage(messageRes)
            .setCancelable(false)
        if (cancel != null) {
            builder.setNegativeButton(R.string.cancel) { _, _ ->
                // The dialog goes at once — the reader asked for it — and the host is told after,
                // off Main. The in-flight request then comes back empty-handed on its own.
                hide()
                cancel()
            }
        }
        dialog = Dialogs.style(builder.create()).also { it.show() }
    }
}
