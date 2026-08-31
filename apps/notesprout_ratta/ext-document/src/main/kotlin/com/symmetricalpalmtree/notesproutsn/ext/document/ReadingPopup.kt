package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * "Reading this page…" — the editor's own progress dialog, and the visual twin of the banner the
 * host shows when it seeds a page before launching this screen. The host cannot show it for a flip:
 * it is *stopped* behind the editor, and a dialog on a stopped activity's window is invisible at
 * best (og's rule, and the EPD rules' reason).
 *
 * **Not cancelable.** By the time it is up the host is already reading the page; a Cancel button
 * would promise something this phase cannot deliver (M7's merge is where a cancel becomes real).
 *
 * The delay is the whole reason this is a class rather than three lines: a **flip** shows it only if
 * the flip is still running after [PageFlipController.READING_POPUP_DELAY_MS], so an already-drafted
 * page flips with no dialog flash — a flash on e-ink is a full black frame and back. A **Bring in**
 * shows it immediately, because it always reads the page in full.
 */
internal class ReadingPopup(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val showTick = Runnable { showNow() }
    private var dialog: AlertDialog? = null

    /** Up now — the caller knows the wait is real. */
    fun show() {
        main.removeCallbacks(showTick)
        showNow()
    }

    /** Up in [delayMs], unless [hide] gets there first. */
    fun showAfter(delayMs: Long) {
        main.removeCallbacks(showTick)
        main.postDelayed(showTick, delayMs)
    }

    /** Down, and un-scheduled. Safe to call when it was never shown. */
    fun hide() {
        main.removeCallbacks(showTick)
        dialog?.dismiss()
        dialog = null
    }

    private fun showNow() {
        if (activity.isFinishing || activity.isDestroyed || dialog != null) return
        dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setMessage(R.string.document_reading_page)
                .setCancelable(false)
                .create(),
        ).also { it.show() }
    }
}
