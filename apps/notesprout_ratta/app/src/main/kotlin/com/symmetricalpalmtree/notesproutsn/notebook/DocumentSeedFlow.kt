package com.symmetricalpalmtree.notesproutsn.notebook

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerClient
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerReadiness
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerStatus
import kotlinx.coroutines.launch

/**
 * The open-time seed (arc 19 / M6) — og's order, kept whole: **flush the ink, look for a stored
 * document, and only then recognize the page** — behind "Reading this page…", with the notebook
 * still front-most so the model-consent flow has a window to live in. Whatever comes back is
 * *staged* on [DocumentHostHooks], not written: the editor's first `current()` serves it as an
 * unsaved draft, and it becomes real only when the editor saves it.
 *
 * Two entry points, and the difference between them is the whole reason this class exists:
 *  - [start] is the **tap**. The notebook is on screen, so a download may be offered
 *    ([RecognizerReadiness], the point's standing rule that only `prepare()` starts one), a dialog
 *    may be shown, and the user is watching a wait they asked for.
 *  - [recognize] is the **editor's** ([DocumentHostHooks.recognizePageText]) — a page flip or a
 *    Bring in, arriving on a Binder thread behind a stopped host. It is silent by contract: no
 *    dialog (there is no window to put one in), and **never** a download; a model that is not
 *    already READY is simply "not available", which the editor says in its own words.
 *
 * **A failed seed never blocks the editor.** Every path here ends in the editor opening — with the
 * page's text if there was any, empty if not. An empty document leaves the page seedable, so
 * nothing is lost but the one recognition.
 *
 * Recognized text is never logged — counts and durations only.
 */
class DocumentSeedFlow(
    private val activity: AppCompatActivity,
    /** The open session, read at call time — the screen may be on its way out. */
    private val session: () -> NotebookSession,
    /** The page the user is looking at, resolved **at the tap** (the R6 torn-read rule). */
    private val displayedPageId: () -> String,
    /** False once the screen is finishing/closing. */
    private val alive: () -> Boolean,
    /** Where a staged seed goes. A lambda because the hooks and this flow are wired to each other. */
    private val hooks: () -> DocumentHostHooks,
    /** Open the editor — the end of every path through this flow. */
    private val openEditor: () -> Unit,
) {

    /**
     * Latched at the tap and released the moment the editor is asked to open. E-ink gives a tap no
     * feedback for hundreds of ms, and the seed adds a recognition on top of that — the second tap
     * is taken as read. ([com.symmetricalpalmtree.notesproutsn.extension.DocumentEditorEntry] has
     * its own latch for the open; this one covers the window in front of it.)
     */
    private var busy = false

    /** The tap: flush → stored document? → recognizer? → consent → recognize → stage → open. */
    fun start() {
        if (busy) { Slog.d(TAG) { "seed: already in flight" }; return }
        if (!alive()) return
        busy = true
        val pageId = displayedPageId()
        activity.lifecycleScope.launch {
            val nb = session()
            // The draft is recognized from the `.soil`, so the ink has to be in it first: strokes
            // committed seconds ago are still on the writer's queue.
            try { nb.store.drain() } catch (e: Exception) { Slog.d(TAG) { "drain failed: ${e.javaClass.simpleName}" } }
            if (!here()) { done(); return@launch }

            // Seed **once**: a page that already has a document hands it over untouched. This is
            // what keeps a hand-edited document safe from every later pass over the same page.
            val documented = try {
                nb.documents.get(pageId) != null
            } catch (e: Exception) {
                Slog.d(TAG) { "document read failed: ${e.javaClass.simpleName}" }
                true   // unreadable is not "absent" — never seed over something we could not see
            }
            if (documented) { done(); return@launch }

            val ref = ExtensionRegistry.handwritingRecognizer(activity)
            if (!here()) { done(); return@launch }
            // No recognizer is not a failure worth a dialog: the editor opens empty and the page
            // stays seedable, so installing one later still works.
            if (ref == null) { Slog.d(TAG) { "seed: no recognizer — opening empty" }; done(); return@launch }

            val client = RecognizerClient(activity, ref)
            // The one place a download may be offered, because the notebook is front-most here.
            RecognizerReadiness.ensureReady(
                activity, client,
                onReady = { seed(pageId, client) },
                onGaveUp = { done() },
            )
        }
    }

    /**
     * The READY path: the "Reading this page…" box, the page's ink out to the extension, and the
     * result staged for the editor's first `current()`.
     *
     * The watermark is read **before** the recognition — a stroke drawn while it runs must read as
     * "the page has changed since this draft", never as the state the draft was built from.
     */
    private suspend fun seed(pageId: String, client: RecognizerClient) {
        if (!here()) { done(); return }
        val nb = session()
        val dialog = readingDialog()
        try {
            val strokes = nb.store.loadPage(pageId)
            if (strokes.isEmpty()) {
                Slog.d(TAG) { "seed: nothing on the page" }
                return
            }
            val page = nb.pages.firstOrNull { it.id == pageId } ?: return
            val watermark = nb.db.documentDao().maxContentUpdatedAt(pageId)
            val t0 = System.currentTimeMillis()
            val text = client.recognizePage(
                InkPayload.fromStrokes(strokes),
                page.width.toFloat(),
                page.height.toFloat(),
            )
            Slog.d(TAG) {
                "seed: ${strokes.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms"
            }
            // Blank is not a seed: staging it would serve an empty draft the editor would then be
            // asked to store. The page simply opens empty, and stays seedable.
            if (text.isNotBlank()) hooks().stageSeed(pageId, text, watermark)
        } catch (e: Exception) {
            // Every recognition failure is the same answer here: open the editor empty. A tap that
            // ends in a problem dialog instead of the editor would be the worse of the two.
            Slog.d(TAG) { "seed failed: ${e.javaClass.simpleName}" }
        } finally {
            dialog?.let { if (it.isShowing) it.dismiss() }
            done()
        }
    }

    /**
     * The editor's own recognition (a flip, or a Bring in) — **silent**, and never a download.
     * null = recognition is not there to run; "" = it ran and the page had nothing to give. See
     * [DocumentHostHooks.recognizePageText] for what each answer means on the far side.
     *
     * Runs on a Binder thread inside the hook's `runBlocking`.
     */
    suspend fun recognize(pageId: String): String? {
        val ref = ExtensionRegistry.handwritingRecognizer(activity) ?: return null
        val client = RecognizerClient(activity, ref)
        // READY or nothing: `prepare()` is the only thing that may start a download, and it needs a
        // consent dialog this side of the seam cannot show from here.
        val ready = try {
            client.status() == RecognizerStatus.READY
        } catch (e: Exception) {
            Slog.d(TAG) { "status failed: ${e.javaClass.simpleName}" }
            false
        }
        if (!ready) return null
        val nb = session()
        nb.store.drain()
        val strokes = nb.store.loadPage(pageId)
        if (strokes.isEmpty()) return ""
        val page = nb.pages.firstOrNull { it.id == pageId } ?: return null
        return try {
            val t0 = System.currentTimeMillis()
            val text = client.recognizePage(
                InkPayload.fromStrokes(strokes),
                page.width.toFloat(),
                page.height.toFloat(),
            )
            Slog.d(TAG) {
                "recognize: ${strokes.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms"
            }
            text
        } catch (e: Exception) {
            Slog.d(TAG) { "recognize failed: ${e.javaClass.simpleName}" }
            null
        }
    }

    /**
     * The wait the user asked for. Deliberately **non-cancelable and buttonless**: cancelling would
     * leave the tap with no answer at all, and the wait ends on its own in seconds. A dialog rather
     * than a [com.symmetricalpalmtree.notesproutsn.core.RecognizingOverlay] box because the editor
     * is about to take the whole screen anyway — there is no page underneath worth keeping visible.
     */
    private fun readingDialog(): AlertDialog? {
        if (!here()) return null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setMessage(R.string.document_reading_page)
                .setCancelable(false)
                .create()
        )
        dialog.show()
        return dialog
    }

    /** Release the latch and open the editor — the end of every path. */
    private fun done() {
        busy = false
        if (here()) openEditor()
    }

    private fun here(): Boolean = alive() && !activity.isFinishing && !activity.isDestroyed

    private companion object {
        const val TAG = "DocumentSeedFlow"
    }
}
