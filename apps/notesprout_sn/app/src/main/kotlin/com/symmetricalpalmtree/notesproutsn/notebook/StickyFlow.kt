package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Intent
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import java.util.UUID

/**
 * How a sticky note comes into being and how its editor is opened and returned from (arc 28 / H5,
 * decision 2 + D2) — out of [NotebookActivity] on [ShapeFlow]'s pattern.
 *
 * **Insert** is [ShapeFlow.insertAtCentre] with a door after it: the icon row, the working copy
 * and the undo entry land in one Main block, and then the editor **opens at once** (og's flow).
 * When that first showing closes, the icon lands **selected under the lasso** so the very next
 * drag puts it where it belongs. A later **finger tap** on the icon reopens the note; stylus taps
 * stay ink (the link-follow gate, [PageGestures.Listener.onFingerTap]).
 *
 * **The handoff chain** (Z3's rule, binding): the notebook ends any transform mode and releases
 * the EPD pipeline **immediately before** the launch; the editor reclaims in its `onResume` and
 * releases before every `finish()`; the notebook reclaims **at the top of the result callback**
 * ([onEditorClosed]) — result callbacks run before `onResume`, and a reclaim any later would let
 * the editor's window close land after it and tear the reclaimed session down.
 *
 * **One undo entry per showing.** The editor writes rows as it goes (whole sets, debounced); the
 * page's history gets a single [Action.StickyContentEdited] from `initial → what the rows now
 * hold`, recorded here once the writer is drained and the children re-read (og's re-read rule —
 * the rows are the truth, not the editor's parting word). Nothing repaints for it: the icon is the
 * page's whole knowledge of the note.
 */
class StickyFlow(private val host: Host) {

    /** What [StickyFlow] needs of [NotebookActivity], and nothing more. */
    interface Host {
        val alive: Boolean
        val session: NotebookSession
        /** The page whose strokes are on the paper — never `session.currentPage` (R6). */
        val pageId: String
        val objects: PageObjects
        val paper: PaperView
        val density: Float

        /** The editor's paper area on this device — [StickyDefaults.contentSize] over the real
         *  window, which since arc 33 / F2 is the whole of it (the editor's paper is full-bleed
         *  under a floating bar). */
        fun contentSize(): Pair<Int, Int>

        fun record(action: Action)

        /** Serialise with every other page mutation (`runPageOp`). */
        fun runPageOp(block: suspend () -> Unit)

        /** Land the selection on [sticky] (host-initiated — flags and bar set over there). */
        fun selectAsSticky(sticky: PageSticky)

        /** Arm the lasso before a selection lands under another tool (the O2 ordering). */
        fun armLassoForLanding()

        /** Every box already on the displayed page — texts, shapes, stickies, headings, links and
         *  the live ink — for [FreePlacement]: a drop lands at the nearest clear spot to the
         *  centre, never on top of what is there. */
        fun occupied(): List<Bounds>

        /** A transform still running when the pipeline goes over would take its geometry with it. */
        fun endTransformIfRunning()

        /** `paper.resumeDrawing()` — guarded, because the result callback also fires on a screen
         *  Android rebuilt after a process death, whose `onCreate` bounced on `IndexGuard` and
         *  built no surface at all. */
        fun reclaimPipeline()

        /** Take down floating chrome that must not outlive the handoff (bars, popups). */
        fun dismissFloatingChrome()

        /** Start the editor through the screen's `ActivityResultLauncher`. */
        fun launchEditor(intent: Intent)

        /** The editor may have changed the clipboard — re-read the lasso button's mark. */
        fun syncClipboardMark()
    }

    /** The showing in flight, from launch to result. Null between showings. */
    private class InFlight(val stickyId: String, val pageId: String, val initialCreate: Boolean)

    private var inFlight: InFlight? = null

    // ── Insert (the Insert bar's Sticky button) ──────────────────────────────

    /**
     * Insert → Sticky: the 72 dp icon at the page centre, the row, the working copy and the undo
     * entry in one Main block — then the editor. The content size is fixed now, from this device's
     * editor paper area (D2), so the row is complete from its first write.
     */
    fun insertAtCentre() {
        if (!host.alive || inFlight != null) return
        val pageId = host.pageId
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val (cw, ch) = host.contentSize()
        val sticky = StickyDefaults.at(
            id = UUID.randomUUID().toString(),
            pageWidth = page.width.toFloat(),
            pageHeight = page.height.toFloat(),
            density = host.density,
            contentW = cw,
            contentH = ch,
        ).let { built ->
            // Sized by the defaults, placed by FreePlacement (the centre when clear).
            val (x, y) = FreePlacement.nearCentre(
                page.width.toFloat(), page.height.toFloat(), built.width, built.height,
                host.occupied(), host.density,
            )
            built.copy(x = x, y = y)
        }
        host.session.stickies.create(pageId, sticky)
        host.objects.put(stickies = listOf(sticky))
        host.record(Action.StickyInserted(pageId, sticky))
        host.paper.notifyContentChanged()
        Slog.d(TAG) { "inserted a sticky ${cw}x$ch" }
        open(sticky.id, initialCreate = true)
    }

    // ── Open (a finger tap on the icon) ──────────────────────────────────────

    /**
     * A finger tap at ([x], [y]) in paper px: the topmost sticky under it opens (later rows draw
     * over earlier ones, so the last match is what the user sees). Answers whether one did — the
     * caller falls through to the links otherwise (stickies sit above links in the hit order).
     */
    fun openAt(x: Float, y: Float): Boolean {
        if (!host.alive || inFlight != null) return false
        val hit = host.objects.stickies.values.lastOrNull { it.bounds.contains(x, y) } ?: return false
        open(hit.id, initialCreate = false)
        return true
    }

    /**
     * Read the note's content (drained first — the standing trap), stage the showing, hand the
     * pipeline over, launch. Under the page-op lock, so a delete or a flip cannot interleave with
     * the read; the row is re-checked after the drain (the icon may have been erased since the
     * tap that asked).
     */
    private fun open(stickyId: String, initialCreate: Boolean) {
        val pageId = host.pageId
        val session = host.session
        inFlight = InFlight(stickyId, pageId, initialCreate)
        host.runPageOp {
            try {
                session.store.drain()
                val sticky = host.objects.stickies[stickyId]
                if (sticky == null || !host.alive || host.pageId != pageId) { inFlight = null; return@runPageOp }
                val initial = session.stickies.content(stickyId)
                StickyEditorTransfer.stage(
                    StickyEditorTransfer.Showing(
                        notebookId = session.notebookId,
                        pageId = pageId,
                        stickyId = stickyId,
                        contentW = sticky.contentW,
                        contentH = sticky.contentH,
                        initial = initial,
                        sink = object : StickyEditorTransfer.Sink {
                            override fun setContent(strokes: List<Stroke>) { session.stickies.setContent(stickyId, strokes) }
                            override suspend fun drain() = session.store.drain()
                        },
                    ),
                )
                host.dismissFloatingChrome()
                // The transform first: `releaseForHandoff` is a silent release (H4's rule).
                host.endTransformIfRunning()
                host.paper.releaseForHandoff()
                host.launchEditor(StickyEditorActivity.intent(host.paper.asView().context, session.notebookId, pageId, stickyId))
                Slog.d(TAG) { "editor opened for $stickyId (${initial.size} stroke(s), create=$initialCreate)" }
            } catch (e: Exception) {
                Log.w(TAG, "sticky editor open failed", e)
                StickyEditorTransfer.clear()
                inFlight = null
                host.reclaimPipeline()
            }
        }
    }

    // ── Result ───────────────────────────────────────────────────────────────

    /**
     * The result callback, whatever the result code. **First statement: reclaim the pipeline.**
     * Then drain, re-read the children, record the one entry, and — after an initial create — land
     * the icon selected. A showing whose transfer is gone (the process died under the editor) is
     * simply over: the rows hold whatever was written.
     */
    fun onEditorClosed() {
        host.reclaimPipeline()
        val flight = inFlight
        inFlight = null
        val showing = StickyEditorTransfer.current
        StickyEditorTransfer.clear()
        host.syncClipboardMark()
        if (flight == null || showing == null || showing.stickyId != flight.stickyId) {
            Slog.d(TAG) { "editor closed with no showing to settle" }
            return
        }
        val session = host.session
        host.runPageOp {
            session.store.drain()
            val after = session.stickies.content(showing.stickyId)
            val before = showing.initial
            if (after != before) {
                host.record(Action.StickyContentEdited(showing.pageId, showing.stickyId, before, after))
            }
            Slog.d(TAG) { "editor closed: ${before.size} → ${after.size} stroke(s)${if (after != before) "" else " (unchanged)"}" }
            if (!flight.initialCreate || !host.alive || host.pageId != flight.pageId) return@runPageOp
            val sticky = host.objects.stickies[showing.stickyId] ?: return@runPageOp
            // The icon lands selected so the next drag places it (decision 2) — the lasso armed
            // first, or the pen would ink straight through the selection.
            host.armLassoForLanding()
            host.selectAsSticky(sticky)
        }
    }

    private companion object {
        const val TAG = "StickyFlow"
    }
}
