package com.symmetricalpalmtree.notesproutsn.notebook

import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import java.util.UUID

/**
 * The three ways a text object comes into being or changes (arc 28 / H2) — **insert**, **convert**
 * and **edit** — kept out of [NotebookActivity] the way [LinkPickFlow] and [ContentsFlow] are, and
 * for the reason H1's ledger gave: the screen file is over its documented size cap and a per-kind
 * flow is exactly the kind of thing that can leave it.
 *
 * What all three share is the shape the heading flows established (N2) and this one copies:
 *
 *  - the box's **top-left is authored and the size is derived** — an insert centres the measured
 *    box, a conversion anchors it at the lassoed ink's top-left, and an edit re-measures at the `x`
 *    the object already has, so a text grows down and right from its anchor and never wanders;
 *  - **the available width is `pageWidth − x`, never the page width** (D1) — [PageObjects.measure]
 *    is the one call that knows it;
 *  - **a blank text object never exists** (D1): a Save with nothing in it deletes, and a Cancel or
 *    a blank Save on the *insert* dialog leaves no trace at all, because nothing was written yet;
 *  - **one act is one frame**: every mutation ends with a single `notifyContentChanged()` in the
 *    same Main block as the store and working-copy writes.
 *
 * **Nothing typed and nothing recognized is ever logged** — character counts only, the rule
 * [HeadingConvert] already keeps.
 *
 * The screen's own state stays the screen's: everything this flow needs of it arrives through
 * [Host], including the selection landing ([Host.selectAsText]) and the successor-selection latch,
 * whose timing inside `onSelectionDismissed` is load-bearing and belongs where that callback is.
 */
class TextFlow(
    private val activity: AppCompatActivity,
    private val host: Host,
) {

    /** What [TextFlow] needs of [NotebookActivity], and nothing more. */
    interface Host {

        /** `opened && !closing` — a page is on the glass and the screen is not on its way out. */
        val alive: Boolean

        val session: NotebookSession

        /** The page whose strokes are on the paper — never `session.currentPage` (the R6 torn-read
         *  rule): what the user inked is the page they were looking at. */
        val pageId: String

        val objects: PageObjects

        val paper: PaperView

        val density: Float

        /** The named strokes off the visible page's mirror, in **writing order** — never the
         *  selection's Set, whose iteration order would scramble the recognizer's input. */
        fun strokesIn(ids: Set<String>): List<Stroke>

        /** Forget strokes a conversion consumed: the mirror is the only place their geometry lives
         *  once the engine drops them, so it is emptied in the same block the engine is told. */
        fun dropLiveStrokes(ids: List<String>)

        fun record(action: Action)

        /** Land the selection on [text] — `setSelection` is host-initiated (no `onSelectionCreated`
         *  echo), so the screen's flags and the bar are set by hand over there. */
        fun selectAsText(text: PageText)

        /** Arm the lasso before a selection lands under a tool that is not it — Insert is a
         *  command and leaves PEN armed, and a selection under PEN is a picture of one. The prior
         *  tool comes back at the selection's dismissal. Call BEFORE [selectAsText]. */
        fun armLassoForLanding()

        /** Every box already on the displayed page — texts, shapes, stickies, headings, links and
         *  the live ink — for [FreePlacement]: a drop lands at the nearest clear spot to the
         *  centre, never on top of what is there. */
        fun occupied(): List<Bounds>

        /** Arm the successor selection that rides the dismissal `removeStrokes` is about to
         *  perform; it must be injected inside `onSelectionDismissed` or the engine has already
         *  restored PEN under the new selection. */
        fun armPendingSelection(select: () -> Unit)

        /** Fire the armed successor if no dismissal fired (the selection had already died). */
        fun drainPendingSelection()
    }

    // ── Insert (the Insert bar's Text button) ────────────────────────────────

    /**
     * Insert → Text: ask for the words first, then place what they measure at the page centre.
     *
     * **Nothing exists until Save.** The row, the working-copy entry and the undo record are all
     * written together at the end; Cancel — or a Save with nothing in it — leaves the page exactly
     * as it was, with no placeholder to clean up. That is what "a blank text object never exists"
     * means at the one moment there is no object yet.
     */
    fun insertAtCentre() {
        if (!host.alive) return
        val pageId = host.pageId
        TextEditDialog.show(activity, initial = "", onSave = { source -> insert(pageId, source) })
    }

    private fun insert(pageId: String, source: String) {
        // A blank first Save is the whole of the answer: nothing was written, so nothing is undone.
        if (source.isEmpty()) return
        // The dialog is modal, so the page cannot have turned under it — but the screen can have
        // been torn down or recreated while it was up, and an insert onto a page nobody is looking
        // at is not what was asked for.
        if (!host.alive || pageId != host.pageId) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        // Measured at x = 0 first: that is the widest column this page can offer, so the natural
        // width it comes back with is the one the centre is computed from.
        val (w0, h0) = host.objects.measure(source, 0f, page.width)
        // The centre when it is clear, else the nearest clear spot (FreePlacement — the user's
        // decision 2026-09-13: nothing drops onto what is already there).
        val (x, y) = FreePlacement.nearCentre(
            page.width.toFloat(), page.height.toFloat(), w0, h0, host.occupied(), host.density,
        )
        // D1's cap is `pageWidth − x`, and a centred box always leaves at least its own width to
        // the right of x — so this re-measure cannot normally fire. It stands because the rule is
        // "measure at the final x", not "measure at the page width and hope".
        val (w, h) = if (page.width - x < w0) host.objects.measure(source, x, page.width) else w0 to h0
        val text = PageText(
            id = UUID.randomUUID().toString(), text = source,
            x = x, y = y, width = w, height = h, order = 0,   // the store lands it at MAX(order)+1
        )
        host.session.texts.create(pageId, text)
        host.objects.put(texts = listOf(text))
        // No strokes were consumed, so the entry's strokeIds is empty and its undo's revive is a
        // no-op. Texts are not in the outline, so Contents is not refreshed — unlike a heading.
        host.record(Action.TextCreated(pageId, text))
        // Insert leaves the pen armed; a selection under PEN can be neither dragged nor tapped.
        host.armLassoForLanding()
        host.selectAsText(text)
        // One Main block → one EPD frame: the selection and the new text land together.
        host.paper.notifyContentChanged()
        Slog.d(TAG) { "inserted a text: ${source.length} chars, ${w.toInt()}x${h.toInt()} px" }
    }

    // ── Convert (the lasso bar's Text button) ────────────────────────────────

    /**
     * Ink → text: recognize the lassoed handwriting and put one text object in its place. Everything
     * the creation needs is captured NOW — the recognition runs async and the selection may die (a
     * tap-away, a flip) before it answers; the captured strokes are what the user pointed at.
     *
     * The writing area is the **selection box**, not the page, for [HeadingConvert]'s reason: ML Kit
     * reads the area as the scale of the writing. What differs from the heading's call is the one
     * flag — `multiLine`, which keeps the recognizer's line breaks instead of collapsing them, since
     * a text object is allowed to be a paragraph.
     *
     * On failure this simply never fires: the ink is left exactly as it was and the dialog
     * [HeadingConvert] puts up says so (the locked failure path).
     */
    fun convert(sel: Selection) {
        if (!host.alive) return
        val pageId = host.pageId
        val strokes = host.strokesIn(sel.strokeIds)
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        HeadingConvert.run(
            activity, strokes, bounds.width, bounds.height, multiLine = true,
            onRecognized = { source ->
                createFromConversion(pageId, strokes.map { it.id }, bounds, source)
            },
        )
    }

    /**
     * The success half of the conversion: one text row up, the consumed ink soft-deleted, recorded
     * as **one undo step** ([Action.TextCreated] with the ink's ids). The box anchors at the lassoed
     * ink's top-left and takes the size measured for that anchor — free growth downward, capped only
     * by the page's right edge (D1).
     */
    private fun createFromConversion(
        pageId: String,
        strokeIds: List<String>,
        inkBounds: Bounds,
        source: String,
    ) {
        if (!host.alive) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val (w, h) = host.objects.measure(source, inkBounds.left, page.width)
        val text = PageText(
            id = UUID.randomUUID().toString(), text = source,
            x = inkBounds.left, y = inkBounds.top, width = w, height = h, order = 0,
        )
        host.session.store.erase(strokeIds)
        host.session.texts.create(pageId, text)
        host.record(Action.TextCreated(pageId, text, strokeIds))
        Slog.d(TAG) { "converted ${strokeIds.size} strokes → a text of ${source.length} chars" }
        if (pageId != host.pageId) return   // the user flipped away mid-recognize; rows are right
        host.dropLiveStrokes(strokeIds)
        host.objects.put(texts = listOf(text))
        // The successor selection rides the dismissal `removeStrokes` is about to perform — see
        // `onSelectionDismissed`. Injecting it there keeps a smart-lasso session alive across the
        // conversion, so the engine restores PEN when the *text's* selection is dismissed.
        host.armPendingSelection { host.selectAsText(text) }
        host.paper.removeStrokes(strokeIds)
        // No dismissal fired (the selection had already died mid-recognize) — select directly.
        host.drainPendingSelection()
        // removeStrokes only re-records when it dropped something; if the captured ids went stale
        // mid-recognize (scribble-erased under the overlay) the text still has to paint. Both calls
        // land in one Main block → one frame.
        host.paper.notifyContentChanged()
    }

    // ── Edit (a stylus tap inside a lone selected text) ──────────────────────

    /** Open the dialog on [text]'s own source. Cancel does nothing at all. */
    fun edit(text: PageText) {
        if (!host.alive) return
        val pageId = host.pageId
        TextEditDialog.show(activity, text.text, onSave = { source -> applyEdit(pageId, text.id, source) })
    }

    /**
     * Save from the edit dialog. [source] is the dialog's tidied field text: empty means **delete**
     * (the heading dialog's locked rule, and D1's — a blank text object never exists); anything else
     * is re-measured **at the same `x`**, because a text object grows from its anchor.
     *
     * The object is re-read from the working copy rather than trusted from the capture: a move, an
     * undo or an erase could have landed while the dialog was up.
     */
    private fun applyEdit(pageId: String, textId: String, source: String) {
        if (!host.alive || pageId != host.pageId) return
        val before = host.objects.texts[textId] ?: return
        if (source.isEmpty()) {
            host.session.texts.erase(listOf(textId))
            host.objects.drop(host.objects.split(listOf(textId)))
            host.paper.clearSelection()
            // The widened Deleted, not a kind of its own: an empty Save is a delete of one text,
            // and it replays through the same arm a lasso Delete does.
            host.record(Action.Deleted(pageId, strokes = emptyList(), textIds = listOf(textId)))
            host.paper.notifyContentChanged()
            Slog.d(TAG) { "empty save deleted a text" }
            return
        }
        if (source == before.text) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val (w, h) = host.objects.measure(source, before.x, page.width)
        val after = before.copy(text = source, width = w, height = h)
        host.session.texts.updateContent(after)
        host.objects.put(texts = listOf(after))
        host.record(Action.TextEdited(pageId, before, after))
        // The box resized under the selection frame, so the frame is stale — land it again.
        host.selectAsText(after)
        host.paper.notifyContentChanged()
        Slog.d(TAG) { "edited a text: ${source.length} chars, ${w.toInt()}x${h.toInt()} px" }
    }

    private companion object {
        const val TAG = "TextFlow"
    }
}
