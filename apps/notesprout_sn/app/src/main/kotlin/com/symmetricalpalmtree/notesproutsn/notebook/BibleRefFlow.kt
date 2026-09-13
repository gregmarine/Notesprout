package com.symmetricalpalmtree.notesproutsn.notebook

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.RecognizingOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The three ways a **Bible reference** comes into being or changes (arc 38 / R3) — **convert**,
 * **insert** and **edit** — kept out of [NotebookActivity] the way [TextFlow] and [LinkPickFlow]
 * are, and for the same reason: the screen file is over its documented size cap, and a per-kind
 * flow is exactly the kind of thing that can leave it.
 *
 * What a Bible reference *is* on the page is an ordinary text object holding **the user's own
 * words** (the arc's decision 2), wrapped in an ordinary link whose payload is
 * [LinkPayload.KIND_BIBLE] and whose target is the passage the extension resolved those words to.
 * Everything a link already does — render, underline, move, delete, copy, undo, unlink — is reused
 * untouched; only the payload's kind and this flow are new.
 *
 * The shape all three share, and the rules behind it:
 *
 *  - **The dialog comes first, the store last.** A convert recognizes and then *prefills* the
 *    dialog; an insert opens it empty; an edit opens it on the text's own words. Nothing is
 *    written until the reference has resolved.
 *  - **A dead Bible link cannot be created** (the locked decision): the extension is asked before
 *    anything exists, and a text it does not know is a problem dialog whose positive button
 *    reopens the dialog with the words kept. Cancel leaves the ink exactly as it was.
 *  - **The host never parses a reference.** Only the `.bible` knows how many verses Psalm 117 has,
 *    so validity is [Host.resolve]'s answer and the wire it comes back with is opaque here.
 *  - **One act, one undo step** ([Action.BibleRefCreated] / [Action.BibleRefEdited]) — a reference
 *    that cost two undos would come apart into a text object that used to be a link.
 *  - **Nothing typed, recognized or resolved is ever logged** — counts and durations only. A
 *    reference names where the user has read.
 */
class BibleRefFlow(
    private val activity: AppCompatActivity,
    private val host: Host,
) {

    /** What [BibleRefFlow] needs of [NotebookActivity], and nothing more. */
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

        fun record(action: Action)

        /**
         * Read [text] as scripture, or null — "not a reference" and "could not ask" are one answer
         * here, because both mean nothing may be created. Suspends: it is a Binder call, and a
         * cold reader's first one copies its database out of its APK.
         */
        suspend fun resolve(text: String): ResolvedReference?

        /**
         * Wrap a freshly created [text] in a link carrying [payload] and land it selected — the
         * wrap machinery `createLinkFromSelection` uses, reached through the host because the
         * working copies, the renderers and the successor-selection latch are all the screen's.
         * [consumedStrokeIds] is the ink a conversion replaced (empty for an insert): those are the
         * strokes the paper is told to drop in the same frame.
         *
         * Null when nothing could be wrapped (the page turned, the selection died).
         */
        fun wrapTextAsLink(
            pageId: String,
            text: PageText,
            payload: String,
            consumedStrokeIds: List<String>,
        ): PageLink?

        /** The live link by id, or null — the screen's working copy is the only place a link's
         *  current state lives, and an edit must be applied to *that*, never to a capture the
         *  dialog was opened with. */
        fun liveLink(id: String): PageLink?

        /** An edit landed: swap the working copy, rebuild this link's composite and re-select it.
         *  The composite must be rebuilt by hand — its size often does not change when the words
         *  do ([LinkRenderer.invalidate]). */
        fun relandEditedLink(link: PageLink)

        /** Arm the lasso before a selection lands under a tool that is not it — Insert is a command
         *  and leaves PEN armed, and a selection under PEN is a picture of one. Call BEFORE the
         *  wrap. */
        fun armLassoForLanding()

        /** One line at the bottom naming what the reference matched — a toast confirms something
         *  that already happened, which is the one thing a toast is for. */
        fun toast(text: String)
    }

    // ── The three doors ──────────────────────────────────────────────────────

    /**
     * The lasso bar's **Bible**: recognize the lassoed handwriting as one line, then offer it in
     * the dialog for the user to correct before it is resolved.
     *
     * Everything the creation needs is captured NOW — the recognition and the resolve are both
     * async and the selection may die (a tap-away, a flip) before either answers; the captured
     * strokes and bounds are what the user pointed at. Recognition failure creates nothing and says
     * so ([HeadingConvert]'s locked failure path); the ink is left exactly as it was.
     */
    fun convert(sel: Selection) {
        if (!host.alive) return
        val pageId = host.pageId
        val strokes = host.strokesIn(sel.strokeIds)
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        val strokeIds = strokes.map { it.id }
        HeadingConvert.run(
            activity, strokes, bounds.width, bounds.height, multiLine = false,
            onRecognized = { recognized ->
                dialog(recognized) { typed, resolved ->
                    createFromConversion(pageId, strokeIds, bounds, typed, resolved)
                }
            },
        )
    }

    /**
     * The Insert bar's **Bible**: the dialog empty, and what it resolves lands at the page centre.
     * **Nothing exists until the reference resolves** — no placeholder row, no minted id, so a
     * Cancel or a refusal leaves the page exactly as it was.
     */
    fun insertAtCentre() {
        if (!host.alive) return
        val pageId = host.pageId
        dialog("") { typed, resolved -> insert(pageId, typed, resolved) }
    }

    /**
     * **Edit** on a lone selected Bible link — the reference dialog, never the page picker (the
     * locked decision: a Bible link's target is a passage, and the picker has nothing to say about
     * one). A Bible link wraps exactly one text object by construction; a link that does not is a
     * row no flow of ours wrote, and it is explained rather than guessed at.
     */
    fun edit(link: PageLink) {
        if (!host.alive) return
        val text = link.texts.singleOrNull()
        if (text == null || link.strokes.isNotEmpty() || link.headings.isNotEmpty() ||
            link.shapes.isNotEmpty() || link.stickies.isNotEmpty()
        ) {
            Slog.d(TAG) { "edit: ${link.id} is not a single-text Bible link" }
            Dialogs.problem(activity, R.string.bible_reference_problem_title, R.string.bible_reference_unwrappable)
            return
        }
        val pageId = host.pageId
        dialog(text.text) { typed, resolved -> applyEdit(pageId, link.id, typed, resolved) }
    }

    // ── The dialog, and the one resolve behind it ────────────────────────────

    /**
     * Ask for the words, then ask the extension what they mean. A Save with something in it raises
     * the "Checking reference…" box (never a dialog — the [RecognizingOverlay] rule: the wait
     * belongs to the tap that started it and repaints only its own region), calls out, and then
     * either hands [onResolved] the pair or explains.
     *
     * The **problem dialog keeps the words**: its positive button reopens this dialog prefilled
     * with exactly what was typed, because the fix is almost always one character. Cancel there
     * ends the whole flow, having written nothing.
     */
    private fun dialog(prefill: String, onResolved: (typed: String, resolved: ResolvedReference) -> Unit) {
        if (!host.alive) return
        BibleRefDialog.show(activity, prefill) { typed ->
            activity.lifecycleScope.launch {
                RecognizingOverlay.show(activity, R.string.bible_reference_checking)
                val resolved = try {
                    host.resolve(typed)
                } finally {
                    RecognizingOverlay.hide(activity)
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (resolved == null) {
                    notAReference(typed, onResolved)
                } else {
                    onResolved(typed, resolved)
                }
            }
        }
    }

    /** "…is not a reference this Bible knows" — two buttons, because Edit is a real offer and
     *  [Dialogs.problem] is OK-only. Nothing was written, so there is nothing to undo. */
    private fun notAReference(typed: String, onResolved: (String, ResolvedReference) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        Slog.d(TAG) { "resolve: ${typed.length} chars are not a reference" }
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bible_reference_unknown_title)
                .setMessage(activity.getString(R.string.bible_reference_unknown_body, typed))
                .setPositiveButton(R.string.link_edit_action) { _, _ -> dialog(typed, onResolved) }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    // ── Applying it ──────────────────────────────────────────────────────────

    /**
     * The success half of a conversion: the text object exactly as [TextFlow.createFromConversion]
     * makes one — anchored at the lassoed ink's top-left, measured for that anchor (D1's cap is
     * `pageWidth − x`) — the ink soft-deleted, and the whole thing wrapped in a Bible link. One
     * undo step ([Action.BibleRefCreated]) covers all three rows.
     */
    private fun createFromConversion(
        pageId: String,
        strokeIds: List<String>,
        inkBounds: Bounds,
        source: String,
        resolved: ResolvedReference,
    ) {
        if (!host.alive || pageId != host.pageId) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val (w, h) = host.objects.measure(source, inkBounds.left, page.width)
        val text = PageText(
            id = UUID.randomUUID().toString(), text = source,
            x = inkBounds.left, y = inkBounds.top, width = w, height = h, order = 0,
        )
        host.session.store.erase(strokeIds)
        host.session.texts.create(pageId, text)
        land(pageId, text, resolved, strokeIds)
        Slog.d(TAG) { "converted ${strokeIds.size} strokes → a Bible reference of ${source.length} chars" }
    }

    /** The success half of an insert: [TextPlacement.centred], then the same wrap. The ink list is
     *  empty, so the undo entry's revive is a no-op — an insert is a conversion of nothing. */
    private fun insert(pageId: String, source: String, resolved: ResolvedReference) {
        if (!host.alive || pageId != host.pageId) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        // Measured at x = 0 first: that is the widest column this page can offer, so the natural
        // width it comes back with is the one the centre is computed from (TextFlow.insert's rule).
        val (w0, h0) = host.objects.measure(source, 0f, page.width)
        val (x, y) = TextPlacement.centred(page.width.toFloat(), page.height.toFloat(), w0, h0)
        val (w, h) = if (page.width - x < w0) host.objects.measure(source, x, page.width) else w0 to h0
        val text = PageText(
            id = UUID.randomUUID().toString(), text = source,
            x = x, y = y, width = w, height = h, order = 0,
        )
        host.session.texts.create(pageId, text)
        // Insert leaves the pen armed; a selection under PEN can be neither dragged nor tapped.
        host.armLassoForLanding()
        land(pageId, text, resolved, emptyList())
        Slog.d(TAG) { "inserted a Bible reference of ${source.length} chars" }
    }

    /** The wrap both creation paths end in: the payload, the link, the one undo entry, the toast. */
    private fun land(
        pageId: String,
        text: PageText,
        resolved: ResolvedReference,
        strokeIds: List<String>,
    ) {
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, resolved.wire, null,
        )
        val link = host.wrapTextAsLink(pageId, text, payload, strokeIds) ?: return
        host.record(Action.BibleRefCreated(pageId, link, text, strokeIds))
        host.toast(activity.getString(R.string.bible_reference_linked_toast, resolved.label))
    }

    /**
     * The Save from an Edit: the wrapped text's words and the link's payload rewritten together,
     * and the link's own box re-derived from the re-measured text ([PageLink.unionBounds] — the
     * hit target and the underline band both hang off it).
     *
     * The link is re-read from the working copy rather than trusted from the capture: a move, an
     * undo or an erase could have landed while the dialog was up. **An unchanged text and an
     * unchanged wire is a no-op** — no write, no undo step, exactly as an unchanged payload is on
     * the picker's Edit path.
     */
    private fun applyEdit(pageId: String, linkId: String, source: String, resolved: ResolvedReference) {
        if (!host.alive || pageId != host.pageId) return
        val before = host.liveLink(linkId) ?: return
        val text = before.texts.singleOrNull() ?: return
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, resolved.wire, null,
        )
        if (source == text.text && payload == before.payload) {
            Slog.d(TAG) { "edit: nothing changed" }
            return
        }
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val (w, h) = host.objects.measure(source, text.x, page.width)
        val newText = text.copy(text = source, width = w, height = h)
        // The link's box is the union of what it wraps plus the underline band — one text here, so
        // the text's own re-measured box decides it.
        val bounds = PageLink.unionBounds(
            emptyList(), emptyList(), host.density, listOf(newText),
        ) ?: return
        val after = before.copy(
            payload = payload, chrome = LinkPayload.chromeOf(payload),
            x = bounds.left, y = bounds.top, width = bounds.width, height = bounds.height,
            texts = listOf(newText),
        )
        host.session.texts.updateContent(newText)
        host.session.links.updatePayload(linkId, payload)
        host.session.links.updateBounds(linkId, after.x, after.y, after.width, after.height)
        host.relandEditedLink(after)
        host.record(Action.BibleRefEdited(pageId, before, after))
        host.toast(activity.getString(R.string.bible_reference_linked_toast, resolved.label))
        Slog.d(TAG) { "edited a Bible reference: ${source.length} chars" }
    }

    private companion object {
        const val TAG = "BibleRefFlow"
    }
}
