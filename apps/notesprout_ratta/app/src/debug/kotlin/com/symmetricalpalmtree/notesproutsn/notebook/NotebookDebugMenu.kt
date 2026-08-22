package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import com.symmetricalpalmtree.notesproutsn.extension.InkTooLargeException
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerClient
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerNotReadyException
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Debug builds only — this file has a no-op twin in `src/release`, so the call site in
 * `NotebookActivity` compiles in both variants. A ⋯ at the **end** of the notebook's top bar (the
 * twin of the library's `DebugMenu`), holding the arc-3 test surface **"Recognize page"**: it hands
 * the visible page's ink — bare x/y geometry via [InkPayload], in writing order — to the one trusted
 * `HANDWRITING_RECOGNIZER` extension through [RecognizerClient] and shows the text in a dialog.
 *
 * The model-consent flow ([RecognizerReadiness]) sits in front of the call: READY recognizes at
 * once; NEEDS_DOWNLOAD asks first and, once the download finishes, recognizes without a second tap.
 * Every dialog here is the host's — the extension shows nothing — and the only toast confirms a copy
 * that already happened. **Nothing recognized is stored or logged**; the dialog is its only sink.
 */
object NotebookDebugMenu {

    private const val TAG = "NotebookDebugMenu"

    /**
     * The one-flow-at-a-time guard, owned by the activity that started the flow: a flow whose dialog
     * is torn down with its activity fires no `onCancel`, so a plain process-lifetime boolean would
     * stick forever. Busy ⇔ the owner is still alive.
     */
    private var busyOwner: WeakReference<AppCompatActivity>? = null
    private fun busy(): Boolean = busyOwner?.get()?.let { !it.isFinishing && !it.isDestroyed } ?: false
    private fun claim(activity: AppCompatActivity) { busyOwner = WeakReference(activity) }
    private fun release() { busyOwner = null }

    /**
     * Append the ⋯ to [bar] — the top bar's button row. A weight-1 spacer goes in first so the real
     * tools stay left and the ⋯ sits at the right edge, where a debug affordance belongs.
     */
    fun install(activity: AppCompatActivity, bar: ViewGroup, provider: () -> RecognizeContext?) {
        bar.addView(
            View(activity),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        val size = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val pad = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
        val button = AppCompatImageButton(activity, null, 0).apply {
            setImageResource(R.drawable.ic_dots)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            contentDescription = activity.getString(R.string.cd_debug)
            scaleType = ImageView.ScaleType.FIT_CENTER
            stateListAnimator = null
        }
        TooltipCompat.setTooltipText(button, button.contentDescription)
        button.setOnClickListener { showChooser(activity, provider) }
        bar.addView(button)
    }

    private fun showChooser(activity: AppCompatActivity, provider: () -> RecognizeContext?) {
        val items = arrayOf<CharSequence>(activity.getString(R.string.debug_recognize_page))
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.debug_tools_title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> recognizePage(activity, provider)
                    }
                }
                .create()
        ).show()
    }

    private fun recognizePage(activity: AppCompatActivity, provider: () -> RecognizeContext?) {
        if (busy()) return
        val ctx = provider() ?: return   // the page isn't on the paper yet — nothing to recognize
        // A tap that recognized nothing must say why: on e-ink a toast is easy to miss and its
        // absence reads as "broken".
        if (ctx.strokes.isEmpty()) { problem(activity, R.string.recognize_nothing); return }
        claim(activity)
        activity.lifecycleScope.launch {
            val ref = ExtensionRegistry.handwritingRecognizer(activity)   // IO; re-discovered per tap
            if (activity.isFinishing || activity.isDestroyed) { release(); return@launch }
            if (ref == null) {
                problem(activity, R.string.recognize_no_extension)
                release(); return@launch
            }
            val client = RecognizerClient(activity, ref)
            val ink = withContext(Dispatchers.Default) { InkPayload.fromStrokes(ctx.strokes) }
            // The busy guard stays up for the whole flow and is released by whichever exit runs.
            RecognizerReadiness.ensureReady(
                activity, client,
                onReady = { try { run(activity, client, ink, ctx) } finally { release() } },
                onGaveUp = { release() },
            )
        }
    }

    /** The READY path: a "Recognizing…" box, the page call off Main, the result dialog. */
    private suspend fun run(activity: AppCompatActivity, client: RecognizerClient, ink: List<InkStroke>, ctx: RecognizeContext) {
        if (activity.isFinishing || activity.isDestroyed) return
        // Not a toast: this stays up for the whole call — half a second warm, several seconds when
        // the extension process is cold on a Nomad — and a toast would be gone long before then.
        val working = Dialogs.style(
            AlertDialog.Builder(activity).setMessage(R.string.recognize_running).setCancelable(false).create()
        )
        working.show()
        try {
            val t0 = System.currentTimeMillis()
            val text = client.recognizePage(ink, ctx.pageWidth, ctx.pageHeight)
            val ms = System.currentTimeMillis() - t0
            Slog.d(TAG) { "recognized ${ink.size} strokes → ${text.length} chars in $ms ms" }
            working.dismiss()
            if (!activity.isFinishing && !activity.isDestroyed) showResult(activity, text, ink.size, ms)
        } catch (e: InkTooLargeException) {
            Slog.d(TAG) { "too dense: ${e.message}" }
            problem(activity, R.string.recognize_too_dense)
        } catch (e: RecognizerNotReadyException) {
            // READY was reported and then the extension lost it — a process restart mid-flow. Rare.
            Slog.d(TAG) { "not ready after READY: ${e.cause?.message}" }
            problem(activity, R.string.recognize_still_downloading)
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "recognize failed: ${e.javaClass.simpleName}: ${e.message}" }
            problem(activity, R.string.recognize_failed)
        } finally {
            if (working.isShowing) working.dismiss()
        }
    }

    /** The text, then one secondary line of timing. Copy takes the text alone. */
    private fun showResult(activity: AppCompatActivity, text: String, strokes: Int, ms: Long) {
        val body = text.ifEmpty { activity.getString(R.string.recognize_empty_result) }
        val timing = activity.getString(
            R.string.recognize_result_timing,
            strokes,
            text.length,
            String.format("%.1f", ms / 1000f),
        )
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.recognize_result_title)
            .setMessage("$body\n\n$timing")
            .setPositiveButton(R.string.recognize_copy) { _, _ ->
                val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.recognize_clip_label), text))
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, R.string.recognize_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.ok, null)
            .create()
        Dialogs.style(dialog)
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun problem(activity: AppCompatActivity, messageRes: Int) =
        Dialogs.problem(activity, R.string.recognize_problem_title, messageRes)
}
