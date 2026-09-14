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
import com.symmetricalpalmtree.notesproutsn.extension.PassageText
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
 *
 * **Arc 40 "Verses"** put the passage's *words* on the page through the same three doors and one
 * more: the dialog's "Insert the verses" pill (convert and insert), the reader's Send choosing
 * the verses ([insertVersesSent]), and the lasso bar's Verses on a placed reference ([expand]).
 * All four end in [landVerses]: the Markdown the reader built (`PassageText`) as a text object in
 * the verses column ([VersePlacement]), wrapped in a [LinkPayload.KIND_BIBLE_TEXT] link — the
 * same follow, one undo step, and an Edit that is the ordinary text dialog ([editVerses]). The
 * two refusals — the reader's cap and the page-fit — are **alerts with OK, never toasts** (the
 * user's rule), and nothing exists until the verses have been read and measured.
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

        /** Whether a reader that can [resolve] is installed right now — [edit]'s gate, so a
         *  missing reader is named as missing rather than the words called wrong. */
        val supportsReferences: Boolean

        /** Arc 40: whether the discovered reader serves `passageText` — the verses doors' gate,
         *  read at every showing (discovery re-runs on every resume). */
        val supportsVerses: Boolean

        /**
         * Arc 40: the verses of [wire] as Markdown, or a `STATUS_TOO_LONG` refusal, or null for
         * "could not ask" / "could not read". Suspends — a Binder call.
         */
        suspend fun passageText(wire: String): PassageText?

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

        /** Every box already on the displayed page — texts, shapes, stickies, headings, links and
         *  the live ink — for [FreePlacement]: a drop lands at the nearest clear spot to the
         *  centre, never on top of what is there. */
        fun occupied(): List<Bounds>

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
                dialog(recognized, offerVerses = true) { typed, resolved, verses ->
                    if (verses) {
                        readVerses(resolved) { text ->
                            landVerses(pageId, resolved, text, strokeIds, anchor = null, preferredTop = bounds.top, inkBounds = bounds)
                        }
                    } else {
                        createFromConversion(pageId, strokeIds, bounds, typed, resolved)
                    }
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
        dialog("", offerVerses = true) { typed, resolved, verses ->
            if (verses) {
                readVerses(resolved) { text -> landVerses(pageId, resolved, text, emptyList(), anchor = null, preferredTop = null, inkBounds = null) }
            } else {
                insert(pageId, typed, resolved)
            }
        }
    }

    /**
     * The reader's Send choosing **the verses** (arc 40): the reference it parked and the
     * Markdown it built, both taken over the held bind by `BibleEntry` — or a refusal, or null
     * when the read failed after the reader had already closed, which is explained rather than
     * dropped (a Send that lands nothing silently is the one thing worse than an alert).
     */
    fun insertVersesSent(resolved: ResolvedReference, text: PassageText?) {
        if (!host.alive) return
        when {
            text == null -> problem(R.string.bible_verses_unavailable_title, activity.getString(R.string.bible_verses_unavailable_body))
            !text.isOk -> tooLong(resolved)
            else -> landVerses(host.pageId, resolved, text.text, emptyList(), anchor = null, preferredTop = null, inkBounds = null)
        }
    }

    /**
     * The lasso bar's **Verses** on a lone placed Bible reference (arc 40): read its passage and
     * land the words directly below it, same left edge as the column allows ([VersePlacement
     * .below]). The reference stays. A link that is not a single-text Bible reference is
     * explained, as [edit] explains it.
     */
    fun expand(link: PageLink) {
        if (!host.alive) return
        val wire = LinkPayload.referenceOf(link.payload)
        if (wire == null || LinkPayload.isBibleText(link.payload)) return
        val pageId = host.pageId
        // The label the reader answers is canonical; the placed words are the user's own, and it
        // is those the alerts should name — they are what is on the page. Cut to the contract's
        // cap: a label over it would refuse the whole tap in silence.
        val label = link.texts.singleOrNull()?.text?.take(ResolvedReference.MAX_LABEL_CHARS)
            ?.ifBlank { null } ?: link.id
        val resolved = runCatching { ResolvedReference(wire, label) }.getOrNull() ?: return
        readVerses(resolved) { text ->
            val anchor = Bounds(link.x, link.y, link.x + link.width, link.y + link.height)
            landVerses(pageId, resolved, text, emptyList(), anchor = anchor, preferredTop = null, inkBounds = null)
        }
    }

    /**
     * **Edit** on a lone selected verses link (arc 40) — the ordinary text dialog on the wrapped
     * words, because they are scripture the user may want to trim or annotate, not a reference
     * to resolve. The payload (and so the follow) is left exactly as it was; a **blank Save is a
     * Cancel** here, the reference dialog's rule — a link must wrap its one text, and Delete is
     * how the verses leave the page.
     */
    fun editVerses(link: PageLink) {
        if (!host.alive) return
        val text = link.texts.singleOrNull()
        if (text == null || link.strokes.isNotEmpty() || link.headings.isNotEmpty() ||
            link.shapes.isNotEmpty() || link.stickies.isNotEmpty()
        ) {
            Slog.d(TAG) { "editVerses: ${link.id} is not a single-text Bible link" }
            Dialogs.problem(activity, R.string.bible_reference_problem_title, R.string.bible_reference_unwrappable)
            return
        }
        val pageId = host.pageId
        TextEditDialog.show(activity, text.text, onSave = { source ->
            if (source.isBlank()) return@show
            applyWords(pageId, link.id, source, link.payload, toast = null)
        })
    }

    /**
     * The reader's **Send to notebook** (B9, the user's decision 2026-09-13): a reference the
     * extension already resolved — the chapter being read, or the passage on screen — lands at
     * the page centre exactly as an Insert does, with **the canonical label as its words** (the
     * one door where there are no user's words to keep). No dialog, no resolve: the wire came
     * from the reader itself. Selected under the lasso, so it can be moved; one undo step; the
     * same toast.
     */
    fun insertResolved(resolved: ResolvedReference) {
        if (!host.alive) return
        insert(host.pageId, resolved.label, resolved)
    }

    /**
     * **Edit** on a lone selected Bible link — the reference dialog, never the page picker (the
     * locked decision: a Bible link's target is a passage, and the picker has nothing to say about
     * one). A Bible link wraps exactly one text object by construction; a link that does not is a
     * row no flow of ours wrote, and it is explained rather than guessed at.
     */
    fun edit(link: PageLink) {
        if (!host.alive) return
        if (!host.supportsReferences) {
            // The dead-target dialog's own Edit lands here with no reader to ask: say that,
            // never "not a reference" over words the app cannot check.
            Slog.d(TAG) { "edit: no reader to resolve against" }
            Dialogs.problem(activity, R.string.bible_reference_problem_title, R.string.link_target_bible_body)
            return
        }
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
    private fun dialog(prefill: String, onResolved: (typed: String, resolved: ResolvedReference) -> Unit) =
        dialog(prefill, offerVerses = false) { typed, resolved, _ -> onResolved(typed, resolved) }

    /**
     * The creating doors' showing (arc 40): [offerVerses] puts the "Insert the verses" pill under
     * the field (only against a reader that [Host.supportsVerses]), and [onResolved]'s third
     * argument is whether it was on. The resolve is the same either way — the verses are read
     * only once the reference is known good.
     */
    private fun dialog(
        prefill: String,
        offerVerses: Boolean,
        onResolved: (typed: String, resolved: ResolvedReference, verses: Boolean) -> Unit,
    ) {
        if (!host.alive) return
        val offer = offerVerses && host.supportsVerses
        BibleRefDialog.show(activity, prefill, offer) { typed, verses ->
            activity.lifecycleScope.launch {
                RecognizingOverlay.show(activity, R.string.bible_reference_checking)
                val resolved = try {
                    host.resolve(typed)
                } finally {
                    RecognizingOverlay.hide(activity)
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (resolved == null) {
                    notAReference(typed, offer, onResolved)
                } else {
                    onResolved(typed, resolved, verses)
                }
            }
        }
    }

    /** "…is not a reference this Bible knows" — two buttons, because Edit is a real offer and
     *  [Dialogs.problem] is OK-only. Nothing was written, so there is nothing to undo. */
    private fun notAReference(
        typed: String,
        offerVerses: Boolean,
        onResolved: (String, ResolvedReference, Boolean) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        Slog.d(TAG) { "resolve: ${typed.length} chars are not a reference" }
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bible_reference_unknown_title)
                .setMessage(activity.getString(R.string.bible_reference_unknown_body, typed))
                .setPositiveButton(R.string.bible_reference_edit_action) { _, _ -> dialog(typed, offerVerses, onResolved) }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    // ── The verses (arc 40) ──────────────────────────────────────────────────

    /**
     * Ask the reader for [resolved]'s words behind the "Reading verses…" box, then either hand
     * [onText] the Markdown or explain: the reader's own refusal (a whole chapter or more than
     * its cap) and "could not read" are the two alerts, and both leave the page exactly as it
     * was. The text is never logged — a length.
     */
    private fun readVerses(resolved: ResolvedReference, onText: (String) -> Unit) {
        if (!host.alive) return
        activity.lifecycleScope.launch {
            RecognizingOverlay.show(activity, R.string.bible_verses_reading)
            val text = try {
                host.passageText(resolved.wire)
            } finally {
                RecognizingOverlay.hide(activity)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when {
                text == null -> problem(R.string.bible_verses_unavailable_title, activity.getString(R.string.bible_verses_unavailable_body))
                !text.isOk -> tooLong(resolved)
                else -> onText(text.text)
            }
        }
    }

    /**
     * The landing every verses door ends in: [markdown] measured in the column
     * ([VersePlacement.leftEdge], wrapping at the page's right edge), refused when taller than
     * the page **or when no band of the page is clear** (the "no room" alert — nothing written;
     * a block of verses over what is there is unreadable), else placed at the nearest clear `y` to
     * where the door pointed — under [anchor] for an expand, at [preferredTop] for a conversion
     * (whose [inkBounds] are cleared from the occupied set and whose [strokeIds] are erased), and
     * at the page's vertical centre otherwise — then wrapped in a [LinkPayload.KIND_BIBLE_TEXT]
     * link, selected under the lasso, one undo step, the "Placed …" toast.
     */
    private fun landVerses(
        pageId: String,
        resolved: ResolvedReference,
        markdown: String,
        strokeIds: List<String>,
        anchor: Bounds?,
        preferredTop: Float?,
        inkBounds: Bounds?,
    ) {
        if (!host.alive || pageId != host.pageId) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val pageW = page.width.toFloat()
        val pageH = page.height.toFloat()
        val x = VersePlacement.leftEdge(pageW)
        val (w, h) = host.objects.measure(markdown, x, page.width)
        val occupied = host.occupied().let { all ->
            when {
                // The ink being converted is excluded by identity — its strokes' own boxes — never
                // by the selection's bounding box, which can hold an unselected word between lines.
                strokeIds.isNotEmpty() -> VersePlacement.without(all, host.strokesIn(strokeIds.toSet()).map { it.bounds })
                inkBounds != null -> VersePlacement.without(all, inkBounds)
                anchor != null -> all.filterNot { it.left == anchor.left && it.top == anchor.top && it.right == anchor.right && it.bottom == anchor.bottom }
                else -> all
            }
        }
        val y = when {
            anchor != null -> VersePlacement.below(anchor, x, w, h, pageH, occupied, host.density)
            preferredTop != null -> VersePlacement.nearY(x, preferredTop, w, h, pageH, occupied, host.density)
            else -> VersePlacement.nearY(x, (pageH - h) / 2f, w, h, pageH, occupied, host.density)
        }
        if (y == null) {
            Slog.d(TAG) { "verses: ${markdown.length} chars do not fit the page" }
            problem(R.string.bible_verses_no_room_title, activity.getString(R.string.bible_verses_no_room_body, resolved.label))
            return
        }
        val text = PageText(
            id = UUID.randomUUID().toString(), text = markdown,
            x = x, y = y, width = w, height = h, order = 0,
        )
        if (strokeIds.isNotEmpty()) host.session.store.erase(strokeIds)
        host.session.texts.create(pageId, text)
        host.armLassoForLanding()
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE_TEXT, resolved.wire, null,
        )
        val link = host.wrapTextAsLink(pageId, text, payload, strokeIds) ?: return
        host.record(Action.BibleRefCreated(pageId, link, text, strokeIds))
        host.toast(activity.getString(R.string.bible_verses_placed_toast, resolved.label))
        Slog.d(TAG) { "placed the verses: ${markdown.length} chars, ${strokeIds.size} strokes consumed" }
    }

    private fun tooLong(resolved: ResolvedReference) {
        Slog.d(TAG) { "verses: refused by the reader's cap" }
        problem(R.string.bible_verses_too_long_title, activity.getString(R.string.bible_verses_too_long_body, resolved.label))
    }

    private fun problem(titleRes: Int, body: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, titleRes, body)
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

    /** The success half of an insert: [FreePlacement.nearCentre], then the same wrap. The ink list is
     *  empty, so the undo entry's revive is a no-op — an insert is a conversion of nothing. */
    private fun insert(pageId: String, source: String, resolved: ResolvedReference) {
        if (!host.alive || pageId != host.pageId) return
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        // Measured at x = 0 first: that is the widest column this page can offer, so the natural
        // width it comes back with is the one the centre is computed from (TextFlow.insert's rule).
        val (w0, h0) = host.objects.measure(source, 0f, page.width)
        // The centre when it is clear, else the nearest clear spot (FreePlacement).
        val (x, y) = FreePlacement.nearCentre(
            page.width.toFloat(), page.height.toFloat(), w0, h0, host.occupied(), host.density,
        )
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
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, resolved.wire, null,
        )
        applyWords(pageId, linkId, source, payload, toast = activity.getString(R.string.bible_reference_linked_toast, resolved.label))
    }

    /** The write both Edits share (arc 40 split it out of [applyEdit]): the words and the payload
     *  together, the box re-derived, one undo step; [toast] null for the verses' Edit, whose
     *  target did not change and so has nothing to confirm. */
    private fun applyWords(pageId: String, linkId: String, source: String, payload: String, toast: String?) {
        if (!host.alive || pageId != host.pageId) return
        val before = host.liveLink(linkId) ?: return
        val text = before.texts.singleOrNull() ?: return
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
        if (toast != null) host.toast(toast)
        Slog.d(TAG) { "edited a Bible link: ${source.length} chars" }
    }

    private companion object {
        const val TAG = "BibleRefFlow"
    }
}
