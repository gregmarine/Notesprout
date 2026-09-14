package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.bible.databinding.ActivityBibleBinding
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ChapterPaginator
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderView
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Over the ~800-line rule, with reason:** this one screen is the reader's whole surface — the
 * chapter flow (B1–B2), the three side panels' doors (B3, B6–B8), the passage mode with its Full
 * chapter door (arc 38 / R2), Send (B9) and the verses chooser (arc 40) — and every door shares the
 * one `loading` latch, the one mode pair and the one position writer. Splitting the doors out
 * would spread that latch across files; the panels themselves already live in their own.
 *
 * The Bible reader's screen (arc 37 / B1, grown by B2; UI-rule tier 2) — SN's **fifth**
 * screen-owning point and, like the tag manager, one whose screen carries **no paper**. There is
 * no `PaperView`, no g-paper call and therefore **no EPD handoff**. Do not add one.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is
 * exported (it has to be — the host launches it by action) and only a
 * `startActivityForResult` from the host package gets in. A plain `am start` from a shell has a
 * null `callingPackage` and is refused.
 *
 * The work all belongs to [ChapterLoader] and happens on `Dispatchers.IO` — installing the
 * bundled source, opening it, turning a chapter's blocks into atoms and paginating them against
 * the band's real size. Main only draws the finished page. The band must be laid out before any
 * of it starts: pagination is against `readingWidth()`/`readingHeight()`, which are zero until
 * then.
 *
 * B2's three additions:
 * - **Where the user was.** The stored [Position] is read on the first layout and the chapter
 *   opens on the page carrying that verse; every committed turn writes the new one back,
 *   fire-and-forget. A store that will not answer costs the bookmark and nothing else — never a
 *   dialog (decision 7).
 * - **The chapter flows.** A turn past the last page opens the next chapter at its first page, a
 *   turn back off page 0 the previous chapter at its **last**; across books, by [ChapterCursor].
 *   Genesis 1 page 1 and Revelation 22's last page are silent no-ops (decision 10). While a
 *   chapter is loading, further turns are ignored — a latch, never a queue: on e-ink a queued
 *   turn arrives long after the hand has given up on it.
 * - **The hand turns the page.** One `ListSwipe` over the reader, fed from `dispatchTouchEvent`
 *   as an observer — it consumes nothing, so the pager buttons keep working, and it drops stylus
 *   and eraser sequences itself, so a pen resting on the page never turns it.
 *
 * **Nothing about what is read is ever logged** — book and chapter numbers, page counts and
 * durations only ("where, not what"), on this side of the seam as on the other.
 *
 * B3's addition, reshaped by B6 and named by B7: **the Contents** (decision 11, amended; "Index"
 * until B7) — a side panel in the notebook Contents' shape ([ContentsPanel]) over the source's
 * own book table, which the loader already read for the cursor. Three doors: `btnContents`, the
 * title, and — B6 — a **one-finger swipe down over the page**, the gesture the notebook teaches
 * for its Contents, on the same `ListSwipe` that turns the page (the two axes are exclusive by
 * dominance). Picking a chapter opens it at its first page, down the same path a chapter edge
 * takes. A tap before the first chapter has shown is a silent no-op: there is nothing to list yet.
 *
 * **Arc 38 / R2 — the passage view.** The same screen in a second **mode**: when the host opened
 * the showing with `beginAt` ([BibleSession.reference]), the reader shows exactly the verses of
 * that reference — the pages [PassageLoader] built — under the reference's own canonical label,
 * with a **Full chapter** door at the bar's end. Two rules make it a mode and not a screen: a
 * turn past either end is a **silent no-op** (a passage has no neighbours to flow into — the
 * chapter flow belongs to reading, not to a citation), and **no position is written** (a passage
 * is not a place the reader was left; the bookmark still names the chapter they were reading).
 * The Contents and the Recents keep both their doors, and picking a chapter from either switches
 * this screen **into chapter mode in place** — the title changes, Full chapter goes.
 *
 * **Full chapter** launches a SECOND instance of this Activity in our own process, in chapter
 * mode, at the first range's verse — the only launch that is not the host's, which is why the
 * caller check admits our own package first. Its Back finishes back onto the passage; the
 * passage's Back finishes to the host, as it always did.
 *
 * B7's addition: **the Recents** — the notebook's Recents panel in a second subject, mirrored to
 * the right ([RecentsPanel]): the chapters the user has **picked** (from the Contents, or from
 * this panel — never a page turn or a chapter the swipe flowed into), newest first, in the host's
 * `recent` table. Two doors, the notebook's own: `btnRecents` (the clock) at the bar's right
 * edge, and a **two-finger swipe down over the page** — `ListSwipe.onTwoFingerSwipeDown`, the
 * detector the flip and the Contents swipe already ride. Neither door is gated: "No recent
 * chapters" is a real answer the panel gives, never a reason to hide a control. A tap opens the
 * chapter at its first page and re-stamps it at the front of the list. Since R2 the list is the
 * merge of two histories — chapters picked by name, and passages followed here from a notebook —
 * and a passage row opens the passage view in place.
 *
 * B8's addition (the user's decision 2026-09-13): **Search** — `btnSearch` left of the clock,
 * [SearchPanel] on the right. One field, two answers ([SearchRoute]): a reference that the
 * source has verses for **goes there** — a lone whole chapter as a chapter pick, anything else
 * as a passage, both stamped as recents because typing a place is the most deliberate pick
 * there is; anything else is words, searched on IO ([BibleDatabase.search]) and shown as a
 * ranked list in the panel, a row opening its chapter on that verse's page (a pick too). The last
 * results outlive the panel for the life of this screen, so closing it does not lose them.
 *
 * B9's addition (the user's decision 2026-09-13): **Send to notebook** — `btnSend` at the far
 * right, shown only when the host opened the reader from a notebook
 * (`EXTRA_BIBLE_SEND_ENABLED`). A tap parks the current reference — the chapter being read as a
 * whole chapter, or the passage on screen — in [BibleSession.outgoing] for the host's
 * `takeOutgoingReference` and finishes with `RESULT_BIBLE_SEND`; the host lands it on the page as
 * a Bible reference object, selected, so it can be moved. The reader closes (the calendar's
 * rule: what landed is what the person is looking at). Our own Full chapter launch forwards the
 * flag, and its Send is echoed up so the passage instance finishes with it too.
 */
class BibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBibleBinding
    private lateinit var readerView: ReaderView
    private lateinit var loader: ChapterLoader
    private lateinit var passages: PassageLoader

    /** The host's store, or null when the showing arrived without one — see [remember]. */
    private var bibleStore: BibleStore? = null

    private var chapter: ChapterPages? = null

    /** The passage on screen (arc 38 / R2). **Non-null is passage mode** — the one flag the
     *  screen branches on; [chapter] is null while it stands, and vice versa. */
    private var passage: PassagePages? = null

    private var pageIndex = 0

    /** A chapter-mode landing from our own Full chapter button, read from the Intent in
     *  [onCreate]; null for every launch the host made. Its presence wins over
     *  [BibleSession.reference] — extras are how *we* open this screen. */
    private var landing: Landing? = null

    /** The reference this showing opens on, read once from [BibleSession] in [onCreate]. */
    private var openingReference: String? = null

    /** A notebook is behind this showing (B9): the host's `EXTRA_BIBLE_SEND_ENABLED`, forwarded
     *  to our own Full chapter launch. */
    private var sendEnabled = false

    /** The in-process Full chapter launch (arc 38 / R2). Registered in [onCreate]. */
    private lateinit var fullChapter: ActivityResultLauncher<Intent>

    /** True while a chapter is being built. The latch that makes a fast flip drop, not queue. */
    private var loading = false

    /** The one-finger flip — and swipe-down — over the reading band. Built in [onCreate]. */
    private var swipe: ListSwipe? = null

    /** The Contents while it is up; null otherwise. One panel at a time, dismissed on the way out. */
    private var contentsPanel: ContentsPanel? = null

    /** The Recents while it is up; null otherwise. [gatheringRecents] covers the read before it. */
    private var recentsPanel: RecentsPanel? = null
    private var gatheringRecents = false

    /** The Search while it is up; null otherwise. [lastSearch] is what it re-opens on. */
    private var searchPanel: SearchPanel? = null
    private var lastSearch: SearchResults? = null
    private var searching = false

    /** Position writes: the one in flight, and the latest one that arrived while it was. */
    private var writing = false
    private var pendingWrite: String? = null

    /** The "Loading…" line, shown only if the work outlasts [LOADING_DELAY_MS]. */
    private val showLoading = Runnable { binding.loading.visibility = View.VISIBLE }

    /** False when the caller check bounced the launch. `onDestroy` still runs on a bounce, and
     *  nothing below was built — the root `IndexGuard.bounced` rule, in the extension's shape. */
    private var admitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Our own package FIRST (arc 38 / R2): the Full chapter door is a `startActivityForResult`
        // from this very process, so `callingPackage` is us — and `enforceActivity` finishes the
        // Activity when it refuses, which short-circuiting is what keeps it from doing so here.
        if (callingPackage != packageName && !HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            super.onCreate(savedInstanceState)
            return
        }
        super.onCreate(savedInstanceState)
        admitted = true
        landing = landingFromIntent()
        // A landing is our own chapter launch and ignores the showing's reference; otherwise the
        // reference — read ONCE here — decides the mode for the life of this instance.
        openingReference = if (landing != null) null else BibleSession.reference
        binding = ActivityBibleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Null-tolerant: a null store only costs the remembered position. The reader itself reads
        // scripture out of its own installed file, never out of the store.
        bibleStore = BibleSession.store?.let { BibleStore(it) }
        loader = ChapterLoader(this)
        passages = PassageLoader(loader)
        sendEnabled = intent?.getBooleanExtra(ExtensionContract.EXTRA_BIBLE_SEND_ENABLED, false) ?: false
        fullChapter = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // The chapter instance read and wrote its own position, and this one is exactly the
            // passage it was — nothing to do, unless it Sent (B9): the parked reference is
            // already in the session, so this instance only has to carry the result code up.
            // The registration exists so the launch is a `startActivityForResult` — which is what
            // makes `callingPackage` us.
            // Both Send codes (B9's reference, arc 40's verses): the chapter instance can be in
            // passage mode itself — a passage picked from its Recents or Search — and its
            // "The verses" answer carries the same parked reference under code 2.
            if (result.resultCode == ExtensionContract.RESULT_BIBLE_SEND ||
                result.resultCode == ExtensionContract.RESULT_BIBLE_SEND_TEXT
            ) {
                setResult(result.resultCode)
                finish()
            }
        }

        readerView = ReaderView(this)
        binding.readerBand.addView(
            readerView,
            0, // under the loading line, which the band keeps on top
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Armed on the reading band only — a drag across the chrome is not a page turn, and not
        // a call for the index either.
        swipe = ListSwipe(
            region = { readerView },
            onFlipNext = { turnTo(pageIndex + 1) },
            onFlipPrevious = { turnTo(pageIndex - 1) },
            onSwipeDown = { openContents() },
            onTwoFingerSwipeDown = { openRecents() },
        )

        binding.title.setText(R.string.bible_title)
        binding.btnBack.setOnClickListener { leave() }
        binding.btnBack.setOnLongClickListener { hint(R.string.cd_bible_back) }
        // Two doors to the same dialog: the button, and the title that names where you are —
        // "Genesis 1" is the obvious thing to tap when you want to be somewhere else.
        binding.btnContents.setOnClickListener { openContents() }
        binding.btnContents.setOnLongClickListener { hint(R.string.cd_bible_contents) }
        binding.title.setOnClickListener { openContents() }
        binding.title.setOnLongClickListener { hint(R.string.cd_bible_contents) }
        binding.btnRecents.setOnClickListener { openRecents() }
        binding.btnRecents.setOnLongClickListener { hint(R.string.cd_bible_recents) }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnSearch.setOnLongClickListener { hint(R.string.cd_bible_search) }
        binding.btnFullChapter.setOnClickListener { openFullChapter() }
        binding.btnFullChapter.setOnLongClickListener { hint(R.string.bible_full_chapter) }
        binding.btnSend.visibility = if (sendEnabled) View.VISIBLE else View.GONE
        binding.btnSend.setOnClickListener { sendToNotebook() }
        binding.btnSend.setOnLongClickListener { hint(R.string.cd_bible_send) }
        binding.btnPrevPage.setOnClickListener { turnTo(pageIndex - 1) }
        binding.btnPrevPage.setOnLongClickListener { hint(R.string.cd_bible_prev_page) }
        binding.btnNextPage.setOnClickListener { turnTo(pageIndex + 1) }
        binding.btnNextPage.setOnLongClickListener { hint(R.string.cd_bible_next_page) }
        for (button in listOf<View>(
            binding.btnBack, binding.btnContents, binding.btnSearch, binding.btnRecents,
            binding.btnFullChapter, binding.btnSend, binding.btnPrevPage, binding.btnNextPage,
        )) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }
        // The title is centred on the SCREEN, so its margins must clear the WIDER of the two
        // groups — and the end one grows by a word when Full chapter shows (arc 38 / R2).
        binding.topBarRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> balanceTitle() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        // Pagination needs the band's real size, so nothing starts before its first layout.
        binding.readerBand.doOnLayout { openFirst() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!admitted) return
        // A Dialog outliving its finishing Activity is a window leak (a config-change recreate,
        // "don't keep activities" — destroys that bypass leave()).
        contentsPanel?.dismiss()
        recentsPanel?.dismiss()
        searchPanel?.dismiss()
        binding.root.removeCallbacks(showLoading)
        loader.close()
    }

    /** The swipe detector is an observer fed from here — it consumes nothing ([ListSwipe]), so
     *  dispatch always continues to the views and every button keeps its tap. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // --- reading ------------------------------------------------------------

    /**
     * The first open, in the mode this instance was launched in: our own Full chapter landing,
     * else the showing's reference (arc 38 / R2), else the stored position — or Genesis 1 when
     * there is none to be had.
     */
    private fun openFirst() {
        landing?.let { at ->
            openChapter(at.ref) { pages -> ChapterPaginator.pageContaining(pages.anchors, at.verse) }
            return
        }
        openingReference?.let { wire ->
            // Followed from a notebook: a deliberate move, and therefore a pick.
            openPassage(wire, stamp = true)
            return
        }
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { bibleStore?.readPosition() }.getOrNull()
            }
            val at = Position.decode(raw) ?: Position.GENESIS_1
            openChapter(ChapterRef(at.usfm, at.chapter)) { pages ->
                ChapterPaginator.pageContaining(pages.anchors, at.verse)
            }
        }
    }

    /** Loads [ref] and shows the page [pageFor] picks out of it (first, last, or a verse's). */
    private fun openChapter(ref: ChapterRef, pageFor: (ChapterPages) -> Int) {
        if (loading) return
        val width = readerView.readingWidth()
        val height = readerView.readingHeight()
        if (width <= 0 || height <= 0) return
        loading = true
        binding.root.postDelayed(showLoading, LOADING_DELAY_MS)
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val built = withContext(Dispatchers.IO) {
                runCatching { loader.chapter(ref, width, height) }
            }
            binding.root.removeCallbacks(showLoading)
            binding.loading.visibility = View.GONE
            loading = false
            built
                .onSuccess { pages ->
                    chapter = pages
                    // A chapter established: whatever passage stood here is over (a Contents or
                    // Recents pick made from the passage view lands exactly here). Done on
                    // SUCCESS only — a failed open must leave the screen as it was.
                    passage = null
                    applyMode()
                    Slog.d(TAG) {
                        "chapter ${pages.usfm} ${pages.chapter}: ${pages.size} page(s) in " +
                            "${SystemClock.elapsedRealtime() - began} ms"
                    }
                    binding.title.text =
                        getString(R.string.bible_chapter_title, pages.bookName, pages.chapter)
                    show(pageFor(pages).coerceIn(0, pages.size - 1))
                    prefetchNeighbours(ref, width, height)
                }
                .onFailure { e ->
                    // The chapter reference, never its text: "where, not what".
                    Log.w(TAG, "could not open ${ref.usfm} ${ref.chapter}", e)
                    Dialogs.problem(this@BibleActivity, R.string.bible_unavailable_title, R.string.bible_unavailable_body)
                }
        }
    }

    /**
     * Opens a passage (arc 38 / R2): [wire] decoded, read, flowed and paginated by
     * [PassageLoader], then page 1. [stamp] records it as a pick — true for the reference the
     * host opened us on and for a row re-picked from the Recents, and there is no other way in.
     *
     * The same `loading` latch a chapter takes: while one build runs nothing else starts, so a
     * fast Contents pick over a passage cannot land twice. A wire the source has nothing for is
     * the problem dialog — **the wire itself is never logged**, here or on failure.
     */
    private fun openPassage(wire: String, stamp: Boolean) {
        if (loading) return
        val width = readerView.readingWidth()
        val height = readerView.readingHeight()
        if (width <= 0 || height <= 0) return
        loading = true
        binding.root.postDelayed(showLoading, LOADING_DELAY_MS)
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val built = withContext(Dispatchers.IO) {
                runCatching { passages.passage(wire, width, height) }
            }
            binding.root.removeCallbacks(showLoading)
            binding.loading.visibility = View.GONE
            loading = false
            built
                .onSuccess { pages ->
                    passage = pages
                    chapter = null
                    Slog.d(TAG) {
                        "passage: ${pages.size} page(s) in ${SystemClock.elapsedRealtime() - began} ms"
                    }
                    binding.title.text = pages.label
                    applyMode()
                    show(0)
                    if (stamp) recordRecentReference(pages.wire)
                }
                .onFailure { e ->
                    // Not even the reference: a passage names where the user has read.
                    Log.w(TAG, "could not open a passage", e)
                    Dialogs.problem(
                        this@BibleActivity,
                        R.string.bible_unavailable_title,
                        R.string.bible_passage_unavailable_body,
                    )
                }
        }
    }

    /**
     * Builds the chapters on either side into the loader's cache, so a flow across a chapter edge
     * is one `invalidate()`. Started only **after** the current chapter is on screen — the page
     * the hand is waiting for always goes first.
     */
    private fun prefetchNeighbours(ref: ChapterRef, width: Int, height: Int) {
        val cursor = loader.cursorNow() ?: return
        val neighbours = listOfNotNull(cursor.next(ref), cursor.prev(ref))
            .filter { loader.cached(it) == null }
        if (neighbours.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            for (neighbour in neighbours) {
                if (!isActive) return@launch // the screen is gone; nothing to prefetch it for
                runCatching { loader.chapter(neighbour, width, height) }
                    .onFailure { Slog.d(TAG) { "prefetch ${neighbour.usfm} ${neighbour.chapter} skipped" } }
            }
        }
    }

    /**
     * A turn, from a pager tap or a swipe. Inside the chapter it is a page; off either end it
     * **flows** into the neighbouring chapter (decision 10) — and at Genesis 1 page 1 or
     * Revelation 22's last page it is a silent no-op, never a disabled button (invisible on
     * e-ink) and never a bounce.
     */
    private fun turnTo(index: Int) {
        if (loading) return
        // Passage mode has no neighbours: a turn past either end is a silent no-op, because the
        // chapter flow belongs to reading and a citation is not a place in the book (arc 38 / R2).
        passage?.let { pages ->
            if (index in 0 until pages.size) show(index)
            return
        }
        val pages = chapter ?: return
        when {
            index in 0 until pages.size -> show(index)
            index >= pages.size ->
                loader.cursorNow()?.next(pages.ref)?.let { openChapter(it) { 0 } }
            else ->
                loader.cursorNow()?.prev(pages.ref)?.let { next -> openChapter(next) { it.size - 1 } }
        }
    }

    /** Draws page [index] and remembers it — every committed turn in **chapter** mode is a
     *  written position; a passage writes none (arc 38 / R2). */
    private fun show(index: Int) {
        passage?.let { pages ->
            pageIndex = index
            readerView.show(pages.rendered[index])
            binding.pageIndicator.text =
                getString(R.string.bible_page_indicator, index + 1, pages.size)
            return
        }
        val pages = chapter ?: return
        pageIndex = index
        readerView.show(pages.rendered[index])
        binding.pageIndicator.text =
            getString(R.string.bible_page_indicator, index + 1, pages.size)
        remember(pages, index)
    }

    // --- the Contents -------------------------------------------------------

    /**
     * The Contents panel (arc 37 / B3, reshaped by B6). The books come from the loader's one read
     * of the source's `book` table; before the first chapter has shown there are none, and the
     * call does **nothing** — a panel that said "not ready" would be noise for the half-second it
     * is true. While one is already up a second call is a no-op too (the swipe and the button
     * can land together). A picked chapter opens at its first page; the position write follows
     * from the show, as it does for every other turn.
     */
    private fun openContents() {
        if (contentsPanel != null) return
        val books = loader.booksNow()
        // In passage mode the chapter the Contents highlights is the passage's first (arc 38 / R2).
        val at = currentChapter() ?: return
        if (books.isEmpty()) return
        contentsPanel = ContentsPanel(
            this, books, at,
            onDismissed = { contentsPanel = null },
            onPicked = { picked -> goTo(picked) },
        ).also { it.show() }
    }

    /**
     * A **pick** — a chapter chosen by name from the Contents or the Recents, as opposed to a
     * turn: it opens at its first page and is recorded as a recent. Dropped whole while a load is
     * running (the same latch a turn hits), so a pick that did not open is never remembered.
     */
    private fun goTo(ref: ChapterRef) {
        if (loading) return
        recordRecent(ref)
        openChapter(ref) { 0 }
    }

    /** A **passage** pick — a reference chosen by name from the Recents (arc 38 / R2). It opens
     *  in place, whichever mode the screen is in, and is re-stamped as the newest recent. */
    private fun goToPassage(wire: String) {
        if (loading) return
        openPassage(wire, stamp = true)
    }

    // --- the Recents --------------------------------------------------------

    /**
     * The Recents panel (arc 37 / B7). The rows are read from the store on IO first — a store
     * that will not answer, or none lent, is an empty list, never a dialog — then selected by
     * [RecentChapters.select] (stored order, the chapter being read dropped) and shown. One
     * showing at a time, and one gather at a time: the button and the swipe can land together.
     */
    private fun openRecents() {
        if (recentsPanel != null || gatheringRecents) return
        gatheringRecents = true
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val store = bibleStore
            // Two tables, one read: the chapters picked by name and the passages followed here
            // (arc 38 / R2). Either failing costs its half of the history, never a dialog.
            val stored = withContext(Dispatchers.IO) {
                runCatching { store?.readRecents(RecentChapters.KEEP) }.getOrNull().orEmpty() to
                    runCatching { store?.readRecentRefs(RecentChapters.KEEP) }.getOrNull().orEmpty()
            }
            gatheringRecents = false
            if (recentsPanel != null || isFinishing || isDestroyed) return@launch
            val (storedChapters, storedRefs) = stored
            val rows = RecentChapters.select(storedChapters, storedRefs, chapter?.ref, passage?.wire)
            Slog.d(TAG) {
                "recents: ${rows.size} of ${storedChapters.size}+${storedRefs.size} in " +
                    "${SystemClock.elapsedRealtime() - began} ms"
            }
            recentsPanel = RecentsPanel(
                this@BibleActivity, rows,
                onDismissed = { recentsPanel = null },
                onPicked = { picked -> goTo(picked) },
                onPickedPassage = { wire -> goToPassage(wire) },
            ).also { it.show() }
        }
    }

    /** Stamps [ref] at the front of the recents, fire-and-forget on IO; the table is trimmed to
     *  [RecentChapters.KEEP] in the same batch. A failure is a log line, never a dialog — and the
     *  reference itself is never logged. */
    private fun recordRecent(ref: ChapterRef) {
        val store = bibleStore ?: return
        val at = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.writeRecent(ref, at, RecentChapters.KEEP) }
                .onFailure { Slog.d(TAG) { "recent not saved" } }
        }
    }

    /** [recordRecent] for a passage (arc 38 / R2): the wire is the row's key, and — like every
     *  other reference in this class — it is never logged. */
    private fun recordRecentReference(wire: String) {
        val store = bibleStore ?: return
        val at = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.writeRecentRef(wire, at, RecentChapters.KEEP) }
                .onFailure { Slog.d(TAG) { "recent reference not saved" } }
        }
    }

    // --- the Search ---------------------------------------------------------

    /**
     * The Search panel (arc 37 / B8). One showing at a time; it opens on the last results, if
     * any. A store is not needed: search reads the source alone, so the door is never gated.
     */
    private fun openSearch() {
        if (searchPanel != null) return
        searchPanel = SearchPanel(
            this, lastSearch,
            onDismissed = { searchPanel = null },
            onQuery = { typed -> search(typed) },
            onPicked = { hit -> goTo(hit.ref, hit.verse) },
        ).also { it.show() }
    }

    /**
     * What was typed, answered. A reference is checked against the source on IO — a reference
     * the source has no verses for is searched as words, as Biblesprout does — and then opened
     * through the same doors a Contents or Recents pick takes, the panel dismissed first. Words
     * are searched on IO and handed back to the panel. One search at a time: a second submit
     * while one runs is dropped, not queued.
     *
     * **Nothing typed is ever logged**, and neither is what was found.
     */
    private fun search(typed: String) {
        if (searching) return
        val panel = searchPanel ?: return
        searching = true
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            // Classified against the source's chapter counts, so "Jude 5" is the verse, not a
            // fifth chapter Jude does not have; a source that will not open classifies blind.
            val route = withContext(Dispatchers.IO) {
                runCatching { loader.withDatabase { db -> SearchRoute.classify(typed, db::chapterCount) } }
                    .getOrElse { SearchRoute.classify(typed) }
            }
            val exists = when (route) {
                is SearchRoute.Words -> false
                is SearchRoute.Chapter -> withContext(Dispatchers.IO) {
                    runCatching {
                        loader.withDatabase { db -> route.ref.chapter <= db.chapterCount(route.ref.usfm) }
                    }.getOrDefault(false)
                }
                is SearchRoute.Passage -> withContext(Dispatchers.IO) {
                    runCatching {
                        loader.withDatabase { db ->
                            ReferenceResolver.valid(route.passages, db::chapterCount, db::verseExists)
                        }
                    }.getOrDefault(false)
                }
            }
            if (isFinishing || isDestroyed) { searching = false; return@launch }
            if (exists) {
                searching = false
                panel.dismiss()
                when (route) {
                    is SearchRoute.Chapter -> goTo(route.ref)
                    is SearchRoute.Passage -> goToPassage(route.wire)
                    is SearchRoute.Words -> Unit
                }
                return@launch
            }
            if (panel.isShowing) panel.showSearching()
            val found = withContext(Dispatchers.IO) {
                runCatching { loader.withDatabase { db -> db.search(typed) } }
            }
            searching = false
            found
                .onSuccess { results ->
                    lastSearch = results
                    Slog.d(TAG) {
                        "search: ${results.hits.size} of ${results.total} in ${SystemClock.elapsedRealtime() - began} ms"
                    }
                    if (panel.isShowing) panel.showResults(results)
                }
                .onFailure { e ->
                    Log.w(TAG, "search failed", e)
                    if (panel.isShowing) panel.showResults(SearchResults(typed, emptyList(), emptyList(), 0))
                }
        }
    }

    /** A [goTo] that lands on the page carrying [verse] — a search hit's door (arc 37 / B8). */
    private fun goTo(ref: ChapterRef, verse: Int) {
        if (loading) return
        recordRecent(ref)
        openChapter(ref) { pages -> ChapterPaginator.pageContaining(pages.anchors, verse) }
    }

    // --- where the user was -------------------------------------------------

    /**
     * Writes the page's position, fire-and-forget. **Coalesced**: while one write is in flight the
     * next replaces the one waiting, so ten fast flips cost two writes, not ten. A failure costs
     * the bookmark and is swallowed with a log line — a lost position is not a dialog.
     */
    private fun remember(pages: ChapterPages, index: Int) {
        val store = bibleStore ?: return
        val verse = pages.anchors.getOrElse(index) { 1 }
        val value = Position(pages.usfm, pages.chapter, verse).encode()
        if (writing) {
            pendingWrite = value
            return
        }
        writing = true
        lifecycleScope.launch {
            var next: String? = value
            while (next != null) {
                val writeMe = next
                withContext(Dispatchers.IO) { runCatching { store.writePosition(writeMe) } }
                    // The value itself is never logged: it names where the user has read.
                    .onFailure { Slog.d(TAG) { "position not saved" } }
                next = pendingWrite
                pendingWrite = null
            }
            writing = false
        }
    }

    // --- the passage's doors ------------------------------------------------

    /**
     * **Full chapter** (arc 38 / R2): a SECOND instance of this Activity, in our own process, in
     * chapter mode, opened at the first range's verse. A second instance rather than a mode flip
     * because the chapter is somewhere the reader has *gone*: Back must come back to the passage,
     * which is exactly what an Activity on the stack means — and the chapter instance then reads,
     * flows and bookmarks like any other reading, with the whole screen's behaviour for free.
     *
     * The extras never cross a process boundary (this is `this` launching `this`), so they are
     * not the seam and carry no contract; [BibleSession.store] is process-wide, so the second
     * instance finds the same lent store.
     */
    private fun openFullChapter() {
        val pages = passage ?: return
        fullChapter.launch(
            Intent(this, BibleActivity::class.java)
                .putExtra(EXTRA_USFM, pages.openAt.usfm)
                .putExtra(EXTRA_CHAPTER, pages.openAt.chapter)
                .putExtra(EXTRA_VERSE, pages.openVerse)
                .putExtra(ExtensionContract.EXTRA_BIBLE_SEND_ENABLED, sendEnabled),
        )
    }

    // --- Send to notebook (B9) ----------------------------------------------

    /**
     * What Send carries: the passage on screen as it is, or the chapter being read as a whole
     * chapter (the user's call — "John 3", never the verse at the top of the page). Null while
     * nothing is open yet.
     */
    private fun currentReference(): ResolvedReference? {
        passage?.let { return ResolvedReference(it.wire, it.label) }
        val ref = chapter?.ref ?: return null
        val whole = ReferenceCodec.wholeChapter(ref)
        return ResolvedReference(ReferenceCodec.encode(listOf(whole)), whole.format())
    }

    /**
     * Park the current reference for the host and leave with [ExtensionContract.RESULT_BIBLE_SEND].
     * The host reads it back over the bind it is still holding (`takeOutgoingReference`) and lands
     * it on the page selected; the reader closes, the calendar's rule. A tap before anything is
     * open, or while a load runs, does nothing — there is no reference to send yet.
     */
    private fun sendToNotebook() {
        if (loading) return
        val reference = currentReference() ?: return
        if (passage == null) {
            leaveWith(reference, ExtensionContract.RESULT_BIBLE_SEND)
            return
        }
        // Arc 40 "Verses": a passage can go as its reference or as its words. A chapter cannot —
        // the user's rule — so chapter mode never asks. The host behind a reader declaring 15
        // understands both codes (the declared number is what we require of the host).
        ActionSheetDialog(this)
            .title(getString(R.string.bible_send_title))
            .addAction(null, getString(R.string.bible_send_reference)) {
                leaveWith(reference, ExtensionContract.RESULT_BIBLE_SEND)
            }
            .addAction(null, getString(R.string.bible_send_verses)) {
                leaveWith(reference, ExtensionContract.RESULT_BIBLE_SEND_TEXT)
            }
            .show()
    }

    /** Park [reference] and close with [code] — `RESULT_BIBLE_SEND` for the reference alone,
     *  `RESULT_BIBLE_SEND_TEXT` when the host should follow the take with `passageText`. */
    private fun leaveWith(reference: ResolvedReference, code: Int) {
        if (isFinishing) return
        synchronized(BibleSession) { BibleSession.outgoing = reference }
        Slog.d(TAG) { "send to notebook: ${if (passage != null) "passage" else "chapter"}, code $code" }
        setResult(code)
        finish()
    }

    /** Our own chapter launch, or null for every launch the host made. Total: an extra set this
     *  build cannot read opens the reader where it was left, never a failure. */
    private fun landingFromIntent(): Landing? {
        val extras = intent ?: return null
        val book = Canon.tryUsfm(extras.getStringExtra(EXTRA_USFM) ?: return null) ?: return null
        val chapter = extras.getIntExtra(EXTRA_CHAPTER, 0)
        if (chapter < 1) return null
        return Landing(
            ChapterRef(book.usfm, chapter),
            extras.getIntExtra(EXTRA_VERSE, 1).coerceAtLeast(1),
        )
    }

    /** Where the Contents and the Recents think the reader is: the chapter, or the passage's
     *  first (arc 38 / R2). */
    private fun currentChapter(): ChapterRef? = chapter?.ref ?: passage?.openAt

    // --- chrome -------------------------------------------------------------

    /** The chrome the mode owns: the Full chapter door, which belongs to a passage and to
     *  nothing else. GONE, never disabled — a disabled button is invisible on e-ink. */
    private fun applyMode() {
        binding.btnFullChapter.visibility = if (passage != null) View.VISIBLE else View.GONE
    }

    /**
     * Keeps the title's centre honest (arc 38 / R2). The title is centred on the SCREEN, so its
     * two margins must be equal and must clear the **wider** of the bar's two groups — and the
     * end group grows by a word whenever Full chapter shows. Measured, never a dp constant: the
     * width of "Full chapter" is a font's answer, not a designer's.
     *
     * Runs from the bar's layout and does nothing when the margins already match, so the extra
     * pass it asks for settles immediately instead of looping.
     */
    private fun balanceTitle() {
        val widest = maxOf(binding.startGroup.width, binding.endGroup.width)
        if (widest <= 0) return
        val params = binding.title.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.marginStart == widest && params.marginEnd == widest) return
        params.marginStart = widest
        params.marginEnd = widest
        // Posted: we are inside the bar's own layout pass, and a requestLayout from in there is
        // the "improperly called during layout" trap.
        binding.title.post { binding.title.requestLayout() }
    }

    private fun leave() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    /** Every icon button names itself on a long press — words read better than glyphs on e-ink. */
    private fun hint(res: Int): Boolean {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        return true
    }

    /** Where our own Full chapter launch lands: the chapter, and the verse to open on. */
    private class Landing(val ref: ChapterRef, val verse: Int)

    companion object {
        private const val TAG = "BibleScreen"

        /** A load faster than this says nothing; only a slow one gets a word on screen. */
        private const val LOADING_DELAY_MS = 300L

        // The Full chapter launch's extras (arc 38 / R2). In-process only — this screen launching
        // itself — so they are not part of any contract and nothing outside this file writes them.
        private const val EXTRA_USFM = "com.symmetricalpalmtree.notesproutsn.ext.bible.USFM"
        private const val EXTRA_CHAPTER = "com.symmetricalpalmtree.notesproutsn.ext.bible.CHAPTER"
        private const val EXTRA_VERSE = "com.symmetricalpalmtree.notesproutsn.ext.bible.VERSE"
    }
}
