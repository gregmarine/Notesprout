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
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.InkStroke
import com.symmetricalpalmtree.notesprout.extension.InkTooLargeException
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.RecognizerClient
import com.symmetricalpalmtree.notesprout.extension.RecognizerNotReadyException
import com.symmetricalpalmtree.notesprout.extension.RecognizerReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug build only (no-op twin in `src/release`): a ⋯ at the end of the notebook's top bar — the
 * twin of the library `DebugMenu` — with the arc-3 test surface **"Recognize page (ML Kit)"**:
 * present only while a trusted `HANDWRITING_RECOGNIZER` extension is installed (re-discovered on
 * every sheet open), it hands the current page's ink — bare x/y geometry via [InkPayload] — to the
 * extension through [RecognizerClient] and shows the text in a dialog (selectable, Copy / OK).
 * The model-consent flow (READY → recognize at once; UNAVAILABLE → dialog; otherwise the one-time
 * "Recognition model needed" → Download → progress → recognize without another tap) is
 * [RecognizerReadiness] since H3 — main source, shared with the heading action; this menu only calls
 * it. (The arc-4 test surfaces "Insert test object" / "Probe object providers" were removed in H5.)
 * Arc 5 / C0 adds **"Probe contents"** (removed in C2): runs the Contents gather ([ContentsSource]
 * via the [contents] lambda the screen hands in) and logs counts + durations only — never a label.
 *
 * Every dialog here is the core's (the extension shows nothing; the only toast is "copied"); nothing
 * recognized is stored or logged — the dialog is the only sink.
 */
object NotebookDebugMenu {

    private const val TAG = "NotebookDebugMenu"

    /**
     * The one-flow-at-a-time guard, **owned by the activity that started the flow** (M2): a flow whose
     * dialog is torn down with its activity (recreate, finish — no `onCancel` fires) must not leave a
     * process-lifetime flag stuck. Busy ⇔ the owner is alive.
     */
    private var busyOwner: java.lang.ref.WeakReference<AppCompatActivity>? = null
    private fun busy(): Boolean = busyOwner?.get()?.let { !it.isFinishing && !it.isDestroyed } ?: false
    private fun claim(activity: AppCompatActivity) { busyOwner = java.lang.ref.WeakReference(activity) }
    private fun release() { busyOwner = null }

    fun install(activity: AppCompatActivity, bar: ViewGroup, provider: () -> RecognizeContext?, contents: suspend () -> ContentsSource.Result?) {
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
        btn.setOnClickListener { showSheet(activity, provider, contents) }
        bar.addView(btn)
    }

    private fun showSheet(activity: AppCompatActivity, provider: () -> RecognizeContext?, contents: suspend () -> ContentsSource.Result?) {
        activity.lifecycleScope.launch {
            val ref = ExtensionRegistry.handwritingRecognizer(activity)   // IO; refreshed per open
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val sheet = ActionSheetDialog(activity).title(activity.getString(R.string.debug_tools_title))
            // No recognizer installed → the item is absent (the sheet opens with its title only).
            if (ref != null) {
                sheet.addAction(null, activity.getString(R.string.debug_recognize_page)) { recognize(activity, ref, provider) }
            }
            sheet.addAction(null, activity.getString(R.string.debug_probe_contents)) { probeContents(activity, contents) }
            sheet.show()
        }
    }

    private fun recognize(activity: AppCompatActivity, ref: ProviderRef, provider: () -> RecognizeContext?) {
        if (busy()) return
        val ctx = provider() ?: return   // not open yet — nothing to recognize
        if (ctx.strokes.isEmpty()) { problem(activity, R.string.recognize_nothing); return }
        claim(activity)
        activity.lifecycleScope.launch {
            val client = RecognizerClient(activity, ref)
            val ink = withContext(Dispatchers.Default) { InkPayload.fromStrokes(ctx.strokes) }
            // The consent flow is main-source since H3; the busy guard stays up for the whole flow and
            // is released by whichever exit runs.
            RecognizerReadiness.ensureReady(
                activity, client,
                onReady = { try { runRecognition(activity, client, ink, ctx) } finally { release() } },
                onGaveUp = { release() },
            )
        }
    }

    /** Arc 5 / C0 (removed in C2): the C1 gather path end to end — log counts + durations, toast "Probe done". */
    private fun probeContents(activity: AppCompatActivity, contents: suspend () -> ContentsSource.Result?) {
        if (busy()) return
        claim(activity)
        activity.lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                when (val r = contents()) {
                    null -> Slog.d(TAG) { "probe contents: not open" }
                    is ContentsSource.Result.Failed -> Slog.d(TAG) { "probe contents: failed — provider '${r.providerLabel}' did not answer in ${System.currentTimeMillis() - t0} ms" }
                    is ContentsSource.Result.Ok -> Slog.d(TAG) { "probe contents: entries=${r.count} roots=${r.roots.size} truncated=${r.truncated} in ${System.currentTimeMillis() - t0} ms" }
                }
                toast(activity, R.string.debug_probe_contents_done)
            } finally {
                release()
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

    private fun toast(activity: AppCompatActivity, res: Int) = toast(activity, activity.getString(res))

    private fun toast(activity: AppCompatActivity, text: String) {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
