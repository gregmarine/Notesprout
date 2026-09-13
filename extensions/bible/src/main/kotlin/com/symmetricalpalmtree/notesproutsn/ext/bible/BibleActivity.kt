package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.bible.databinding.ActivityBibleBinding
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ChapterPaginator
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderView
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
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
 * B7's addition: **the Recents** — the notebook's Recents panel in a second subject, mirrored to
 * the right ([RecentsPanel]): the chapters the user has **picked** (from the Contents, or from
 * this panel — never a page turn or a chapter the swipe flowed into), newest first, in the host's
 * `recent` table. Two doors, the notebook's own: `btnRecents` (the clock) at the bar's right
 * edge, and a **two-finger swipe down over the page** — `ListSwipe.onTwoFingerSwipeDown`, the
 * detector the flip and the Contents swipe already ride. Neither door is gated: "No recent
 * chapters" is a real answer the panel gives, never a reason to hide a control. A tap opens the
 * chapter at its first page and re-stamps it at the front of the list.
 */
class BibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBibleBinding
    private lateinit var readerView: ReaderView
    private lateinit var loader: ChapterLoader

    /** The host's store, or null when the showing arrived without one — see [remember]. */
    private var bibleStore: BibleStore? = null

    private var chapter: ChapterPages? = null
    private var pageIndex = 0

    /** True while a chapter is being built. The latch that makes a fast flip drop, not queue. */
    private var loading = false

    /** The one-finger flip — and swipe-down — over the reading band. Built in [onCreate]. */
    private var swipe: ListSwipe? = null

    /** The Contents while it is up; null otherwise. One panel at a time, dismissed on the way out. */
    private var contentsPanel: ContentsPanel? = null

    /** The Recents while it is up; null otherwise. [gatheringRecents] covers the read before it. */
    private var recentsPanel: RecentsPanel? = null
    private var gatheringRecents = false

    /** Position writes: the one in flight, and the latest one that arrived while it was. */
    private var writing = false
    private var pendingWrite: String? = null

    /** The "Loading…" line, shown only if the work outlasts [LOADING_DELAY_MS]. */
    private val showLoading = Runnable { binding.loading.visibility = View.VISIBLE }

    /** False when the caller check bounced the launch. `onDestroy` still runs on a bounce, and
     *  nothing below was built — the root `IndexGuard.bounced` rule, in the extension's shape. */
    private var admitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            super.onCreate(savedInstanceState)
            return
        }
        super.onCreate(savedInstanceState)
        admitted = true
        binding = ActivityBibleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Null-tolerant: a null store only costs the remembered position. The reader itself reads
        // scripture out of its own installed file, never out of the store.
        bibleStore = BibleSession.store?.let { BibleStore(it) }
        loader = ChapterLoader(this)

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
        binding.btnPrevPage.setOnClickListener { turnTo(pageIndex - 1) }
        binding.btnPrevPage.setOnLongClickListener { hint(R.string.cd_bible_prev_page) }
        binding.btnNextPage.setOnClickListener { turnTo(pageIndex + 1) }
        binding.btnNextPage.setOnLongClickListener { hint(R.string.cd_bible_next_page) }
        for (button in listOf(
            binding.btnBack, binding.btnContents, binding.btnRecents, binding.btnPrevPage, binding.btnNextPage,
        )) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        // Pagination needs the band's real size, so nothing starts before its first layout.
        binding.readerBand.doOnLayout { openWhereWeLeftOff() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!admitted) return
        // A Dialog outliving its finishing Activity is a window leak (a config-change recreate,
        // "don't keep activities" — destroys that bypass leave()).
        contentsPanel?.dismiss()
        recentsPanel?.dismiss()
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

    /** The first open: the stored position, or Genesis 1 when there is none to be had. */
    private fun openWhereWeLeftOff() {
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
        val pages = chapter ?: return
        when {
            index in 0 until pages.size -> show(index)
            index >= pages.size ->
                loader.cursorNow()?.next(pages.ref)?.let { openChapter(it) { 0 } }
            else ->
                loader.cursorNow()?.prev(pages.ref)?.let { next -> openChapter(next) { it.size - 1 } }
        }
    }

    /** Draws page [index] and remembers it — every committed turn is a written position. */
    private fun show(index: Int) {
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
        val at = chapter?.ref ?: return
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
            val stored = withContext(Dispatchers.IO) {
                runCatching { store?.readRecents(RecentChapters.KEEP) }.getOrNull().orEmpty()
            }
            gatheringRecents = false
            if (recentsPanel != null || isFinishing || isDestroyed) return@launch
            val rows = RecentChapters.select(stored, chapter?.ref)
            Slog.d(TAG) { "recents: ${rows.size} of ${stored.size} in ${SystemClock.elapsedRealtime() - began} ms" }
            recentsPanel = RecentsPanel(
                this@BibleActivity, rows,
                onDismissed = { recentsPanel = null },
                onPicked = { picked -> goTo(picked) },
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

    // --- chrome -------------------------------------------------------------

    private fun leave() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    /** Every icon button names itself on a long press — words read better than glyphs on e-ink. */
    private fun hint(res: Int): Boolean {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        return true
    }

    companion object {
        private const val TAG = "BibleScreen"

        /** A load faster than this says nothing; only a slow one gets a word on screen. */
        private const val LOADING_DELAY_MS = 300L
    }
}
