package com.symmetricalpalmtree.notesprout.notebook

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Connectivity
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.InkStroke
import com.symmetricalpalmtree.notesprout.extension.InkTooLargeException
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.RecognizerClient
import com.symmetricalpalmtree.notesprout.extension.RecognizerNotReadyException
import com.symmetricalpalmtree.notesprout.extension.RecognizerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug build only (no-op twin in `src/release`): a ⋯ at the end of the notebook's top bar — the
 * twin of the library `DebugMenu` — with the arc-4 / H1 test surface **"Insert test object"** /
 * **"Delete selection"** (plain [DebugHooks] callbacks into the screen; gone in H5) and the arc-3
 * test surface **"Recognize page (ML Kit)"**:
 * present only while a trusted `HANDWRITING_RECOGNIZER` extension is installed (re-discovered on
 * every sheet open), it hands the current page's ink — bare x/y geometry via [InkPayload] — to the
 * extension through [RecognizerClient] and shows the text in a dialog (selectable, Copy / OK).
 * `status()` first: READY → recognize at once; UNAVAILABLE → dialog; otherwise the **one-time model
 * flow** — a "Recognition model needed" dialog (Download / Cancel; nothing downloads before
 * Download — the extension's `prepare()` is the only thing that starts one), then a progress dialog
 * with an elapsed-time counter that polls `status()` until READY and recognizes without another tap
 * (Cancel hides it; a started download keeps running in the extension).
 *
 * Every dialog here is the core's (the extension shows nothing; the only toast is "copied"); nothing
 * recognized is stored or logged — the dialog is the only sink.
 */
object NotebookDebugMenu {

    private const val TAG = "NotebookDebugMenu"
    /** Progress-dialog poll period (also its e-ink refresh cadence) and the give-up cap. */
    private const val POLL_MS = 2_000L
    private const val DOWNLOAD_CAP_S = 300
    private const val OFFLINE_GIVE_UP_MS = 30_000L
    /** Consecutive failed `status()` polls tolerated before the download is declared failed (M2). */
    private const val MAX_POLL_FAILURES = 5

    /**
     * The one-flow-at-a-time guard, **owned by the activity that started the flow** (M2): a flow whose
     * dialog is torn down with its activity (recreate, finish — no `onCancel` fires) must not leave a
     * process-lifetime flag stuck. Busy ⇔ the owner is alive.
     */
    private var busyOwner: java.lang.ref.WeakReference<AppCompatActivity>? = null
    private fun busy(): Boolean = busyOwner?.get()?.let { !it.isFinishing && !it.isDestroyed } ?: false
    private fun claim(activity: AppCompatActivity) { busyOwner = java.lang.ref.WeakReference(activity) }
    private fun release() { busyOwner = null }

    fun install(activity: AppCompatActivity, bar: ViewGroup, provider: () -> RecognizeContext?, hooks: DebugHooks) {
        // Push the ⋯ to the far end of the row (the row's own buttons stay where they are).
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
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
        btn.setOnClickListener { showSheet(activity, provider, hooks) }
        bar.addView(btn)
    }

    private fun showSheet(activity: AppCompatActivity, provider: () -> RecognizeContext?, hooks: DebugHooks) {
        activity.lifecycleScope.launch {
            val ref = ExtensionRegistry.handwritingRecognizer(activity)   // IO; refreshed per open
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val sheet = ActionSheetDialog(activity).title(activity.getString(R.string.debug_tools_title))
            // No recognizer installed → the item is absent (the sheet opens with its title only).
            if (ref != null) {
                sheet.addAction(null, activity.getString(R.string.debug_recognize_page)) { recognize(activity, ref, provider) }
            }
            // Arc 4 / H1 test surface (removed in H5 with the real selection toolbar in place).
            sheet.addAction(null, activity.getString(R.string.debug_insert_test_object)) { hooks.insertTestObject() }
            sheet.addAction(null, activity.getString(R.string.debug_delete_selection)) { hooks.deleteSelection() }
            sheet.show()
        }
    }

    private fun recognize(activity: AppCompatActivity, ref: ProviderRef, provider: () -> RecognizeContext?) {
        if (busy()) return
        val ctx = provider() ?: return   // not open yet — nothing to recognize
        if (ctx.strokes.isEmpty()) { problem(activity, R.string.recognize_nothing); return }
        claim(activity)
        activity.lifecycleScope.launch {
            var releaseAfter = true
            try {
                val client = RecognizerClient(activity, ref)
                val ink = withContext(Dispatchers.Default) { InkPayload.fromStrokes(ctx.strokes) }
                when (client.status()) {
                    RecognizerStatus.UNAVAILABLE -> problem(activity, R.string.recognize_unavailable)
                    RecognizerStatus.READY -> runRecognition(activity, client, ink, ctx)
                    else -> {
                        // First time on this device (or the model is gone): ask, then show progress. The
                        // busy guard stays up for the whole dialog flow; the flow releases it itself.
                        releaseAfter = false
                        promptDownload(activity, client, ink, ctx)
                    }
                }
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "status failed: ${e.javaClass.simpleName}: ${e.message}" }
                problem(activity, R.string.recognize_failed)
            } finally {
                if (releaseAfter) release()
            }
        }
    }

    /** READY path: toast, page call, result dialog. Releases nothing — the caller owns the busy guard. */
    private suspend fun runRecognition(activity: AppCompatActivity, client: RecognizerClient, ink: List<InkStroke>, ctx: RecognizeContext) {
        if (activity.isFinishing || activity.isDestroyed) return
        // "Recognizing…" as an Opening-style popup, not a toast: a bordered message with no buttons,
        // up for the whole call (0.5 s warm, ~6 s when the extension process is cold on a Nomad).
        val busy = Dialogs.style(
            AlertDialog.Builder(activity).setMessage(R.string.recognize_running).setCancelable(false).create()
        )
        busy.show()
        try {
            val t0 = System.currentTimeMillis()
            val text = client.recognizePage(ink, ctx.pageWidth, ctx.pageHeight)
            val ms = System.currentTimeMillis() - t0
            Slog.d(TAG) { "recognized ${ink.size} strokes → ${text.length} chars in $ms ms" }
            busy.dismiss()
            if (!activity.isFinishing && !activity.isDestroyed) showResult(activity, text, ink.size, ms)
        } catch (e: InkTooLargeException) {
            Slog.d(TAG) { "too dense: ${e.message}" }
            problem(activity, R.string.recognize_too_dense)
        } catch (e: RecognizerNotReadyException) {
            // READY was reported and then the extension lost it (process restart mid-flow) — rare.
            Slog.d(TAG) { "not ready after READY: ${e.cause?.message}" }
            problem(activity, R.string.recognize_still_downloading)
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "recognize failed: ${e.javaClass.simpleName}: ${e.message}" }
            problem(activity, R.string.recognize_failed)
        } finally {
            if (busy.isShowing) busy.dismiss()
        }
    }

    /** "Recognition model needed" — Download / Cancel. Cancel releases the busy guard. */
    private fun promptDownload(activity: AppCompatActivity, client: RecognizerClient, ink: List<InkStroke>, ctx: RecognizeContext) {
        if (activity.isFinishing || activity.isDestroyed) { release(); return }
        // Pre-flight: ML Kit's downloader hangs rather than fails when offline (M1: no error after a
        // minute on the Nomad), so the core checks first and says so instead of offering Download.
        val online = Connectivity.isOnline(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.recognize_model_needed_title)
            .setOnCancelListener { release() }
        if (online) {
            builder.setMessage(R.string.recognize_model_needed_body)
                .setPositiveButton(R.string.recognize_download) { _, _ -> downloadThenRecognize(activity, client, ink, ctx) }
                .setNegativeButton(R.string.cancel) { _, _ -> release() }
        } else {
            builder.setMessage(R.string.recognize_model_needed_offline_body)
                .setPositiveButton(R.string.ok) { _, _ -> release() }
        }
        val dialog = builder.create()
        Dialogs.style(dialog)
        dialog.show()
    }

    /**
     * `prepare()`, then a progress dialog (elapsed-time counter — the e-ink-safe indeterminate
     * indicator, refreshed every [POLL_MS]) that polls `status()` until READY → recognize; a chain
     * failure or the cap → "Download failed". Cancel hides the dialog only — the download itself keeps
     * running in the extension, so the next Recognize finds it further along or done.
     */
    private fun downloadThenRecognize(activity: AppCompatActivity, client: RecognizerClient, ink: List<InkStroke>, ctx: RecognizeContext) {
        activity.lifecycleScope.launch {
            var cancelled = false
            val progress = AlertDialog.Builder(activity)
                .setTitle(R.string.recognize_downloading_title)
                .setMessage(activity.getString(R.string.recognize_downloading_body, 0))
                .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
                .setOnCancelListener { cancelled = true }
                .create()
            Dialogs.style(progress)
            try {
                try {
                    client.prepare()
                } catch (e: ExtensionCallException) {
                    Slog.d(TAG) { "prepare failed: ${e.javaClass.simpleName}: ${e.message}" }
                    showDownloadFailed(activity); return@launch
                }
                progress.show()
                val t0 = System.currentTimeMillis()
                var offlineSinceMs = -1L
                var pollFailures = 0
                while (!cancelled) {
                    kotlinx.coroutines.delay(POLL_MS)
                    if (cancelled || activity.isFinishing || activity.isDestroyed) break
                    val now = System.currentTimeMillis()
                    val elapsedS = ((now - t0) / 1000L).toInt()
                    // The network dropping mid-download: ML Kit does not fail, it waits. Say so, and give
                    // up after OFFLINE_GIVE_UP_MS offline rather than after the 5-minute cap.
                    if (!Connectivity.isOnline(activity)) {
                        if (offlineSinceMs < 0) offlineSinceMs = now
                        if (now - offlineSinceMs >= OFFLINE_GIVE_UP_MS) {
                            Slog.d(TAG) { "offline for ${(now - offlineSinceMs) / 1000} s during download — giving up" }
                            progress.dismiss(); showDownloadFailed(activity); return@launch
                        }
                        progress.setMessage(activity.getString(R.string.recognize_downloading_offline_body, elapsedS))
                        continue
                    }
                    offlineSinceMs = -1L
                    // A failed poll (bind refused / died / timed out) is tolerated as transient a few times;
                    // an extension that stays unbindable (BOOX re-disabling it, a crash on start) is a
                    // failure the host knows now — not after the 5-minute cap.
                    val status = try {
                        client.status().also { pollFailures = 0 }
                    } catch (e: ExtensionCallException) {
                        pollFailures++
                        Slog.d(TAG) { "status poll failed ($pollFailures): ${e.message}" }
                        if (pollFailures >= MAX_POLL_FAILURES) { progress.dismiss(); showDownloadFailed(activity); return@launch }
                        RecognizerStatus.DOWNLOADING
                    }
                    when (status) {
                        RecognizerStatus.READY -> {
                            Slog.d(TAG) { "model ready after $elapsedS s" }
                            progress.dismiss()
                            runRecognition(activity, client, ink, ctx)
                            return@launch
                        }
                        RecognizerStatus.DOWNLOADING -> {
                            progress.setMessage(activity.getString(R.string.recognize_downloading_body, elapsedS))
                            if (elapsedS >= DOWNLOAD_CAP_S) { progress.dismiss(); showDownloadFailed(activity); return@launch }
                        }
                        else -> {   // NEEDS_DOWNLOAD after prepare() = the chain failed; UNAVAILABLE = engine gone
                            Slog.d(TAG) { "download failed (status $status) after $elapsedS s" }
                            progress.dismiss(); showDownloadFailed(activity); return@launch
                        }
                    }
                }
                Slog.d(TAG) { "download dialog cancelled — download continues in the extension" }
            } finally {
                if (progress.isShowing) progress.dismiss()
                release()
            }
        }
    }

    private fun showDownloadFailed(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.recognize_download_failed_title)
                .setMessage(R.string.recognize_download_failed_body)
                .setPositiveButton(R.string.ok, null)
                .create()
        ).show()
    }

    private fun showResult(activity: AppCompatActivity, text: String, strokes: Int, ms: Long) {
        val shown = text.ifEmpty { activity.getString(R.string.recognize_empty_result) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.recognize_result_title, strokes, ms))
            .setMessage(shown)
            .setPositiveButton(R.string.recognize_copy) { _, _ ->
                val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.recognize_clip_label), text))
                toast(activity, R.string.recognize_copied)
            }
            .setNegativeButton(R.string.ok, null)
            .create()
        Dialogs.style(dialog)
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    /**
     * Anything that explains why the tap did *not* recognize is a dialog, not a toast: on e-ink a
     * toast is easy to miss and its absence reads as "broken". Toasts remain only for confirmations
     * of something that already happened ("copied").
     */
    private fun problem(activity: AppCompatActivity, messageRes: Int) =
        Dialogs.problem(activity, R.string.recognize_problem_title, messageRes)

    private fun toast(activity: AppCompatActivity, res: Int) {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
    }
}
