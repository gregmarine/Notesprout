package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.bible.databinding.ActivityBibleBinding
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.Atom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ChapterPaginator
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderPage
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderTypography
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderView
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One paginated chapter, ready to page through: the atoms each page carries (the
 * position anchor is read off them — [ChapterPaginator.firstVerseKey]) and the
 * laid-out page beside it, both built together on IO. Page 0 wears the book
 * title and the big chapter number.
 */
class ChapterPages(
    val usfm: String,
    val chapter: Int,
    val bookName: String,
    val pages: List<List<Atom>>,
    val rendered: List<ReaderPage>,
) {
    val size: Int get() = pages.size
}

/**
 * The Bible reader's screen (arc 37 / B1; UI-rule tier 2) — SN's **fifth**
 * screen-owning point and, like the tag manager, one whose screen carries **no paper**. There is
 * no `PaperView`, no g-paper call and therefore **no EPD handoff**. Do not add one.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is
 * exported (it has to be — the host launches it by action) and only a
 * `startActivityForResult` from the host package gets in. A plain `am start` from a shell has a
 * null `callingPackage` and is refused.
 *
 * The work: install the bundled source once ([ContentInstaller]), open it read-only
 * ([BibleDatabase]), turn a chapter's blocks into atoms ([ChapterPaginator]) and paginate them
 * against the band's real size — **all of it on `Dispatchers.IO`**, because measuring a page is
 * building a `StaticLayout` and a chapter is measured many times over. Main only draws the
 * finished page. The band must be laid out before any of it starts: pagination is against
 * `readingWidth()`/`readingHeight()`, which are zero until then.
 *
 * **Nothing about what is read is ever logged** — book and chapter numbers, page counts and
 * durations only ("where, not what"), on this side of the seam as on the other.
 *
 * B2 adds the swipe, the chapter/book flow and the stored position (the store this screen
 * already holds via [BibleSession]); B3 the index behind the title and `btnIndex`.
 */
class BibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBibleBinding
    private var store: IExtensionStore? = null
    private lateinit var readerView: ReaderView

    /** Guards [bible] / [typography] between the IO loader and [onDestroy]. Only ever held
     *  around cheap field work — never around the install copy or an open. */
    private val lock = Any()
    private var bible: BibleDatabase? = null
    private var typography: ReaderTypography? = null
    private var closed = false

    private var chapter: ChapterPages? = null
    private var pageIndex = 0

    /** The "Loading…" line, shown only if the work outlasts [LOADING_DELAY_MS]. */
    private val showLoading = Runnable { binding.loading.visibility = View.VISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            super.onCreate(savedInstanceState)
            return
        }
        super.onCreate(savedInstanceState)
        binding = ActivityBibleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Null-tolerant: a null store only costs the remembered position (B2). The reader
        // itself reads scripture out of its own installed file, never out of the store.
        store = BibleSession.store

        readerView = ReaderView(this)
        binding.readerBand.addView(
            readerView,
            0, // under the loading line, which the band keeps on top
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        binding.title.setText(R.string.bible_title)
        binding.btnBack.setOnClickListener { leave() }
        binding.btnBack.setOnLongClickListener { hint(R.string.cd_bible_back) }
        binding.btnIndex.setOnLongClickListener { hint(R.string.cd_bible_index) }
        binding.btnPrevPage.setOnClickListener { turnTo(pageIndex - 1) }
        binding.btnPrevPage.setOnLongClickListener { hint(R.string.cd_bible_prev_page) }
        binding.btnNextPage.setOnClickListener { turnTo(pageIndex + 1) }
        binding.btnNextPage.setOnLongClickListener { hint(R.string.cd_bible_next_page) }
        for (button in listOf(binding.btnBack, binding.btnIndex, binding.btnPrevPage, binding.btnNextPage)) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        // Pagination needs the band's real size, so nothing starts before its first layout.
        binding.readerBand.doOnLayout { openChapter(START_USFM, START_CHAPTER) }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks(showLoading)
        synchronized(lock) {
            closed = true
            bible?.close()
            bible = null
        }
    }

    // --- reading ------------------------------------------------------------

    /** Loads, paginates and shows a chapter from its first page. */
    private fun openChapter(usfm: String, number: Int) {
        val width = readerView.readingWidth()
        val height = readerView.readingHeight()
        if (width <= 0 || height <= 0) return
        binding.root.postDelayed(showLoading, LOADING_DELAY_MS)
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val built = withContext(Dispatchers.IO) {
                runCatching { buildChapter(usfm, number, width, height) }
            }
            binding.root.removeCallbacks(showLoading)
            binding.loading.visibility = View.GONE
            built
                .onSuccess { pages ->
                    chapter = pages
                    Slog.d(TAG) {
                        "chapter ${pages.usfm} ${pages.chapter}: ${pages.size} page(s) in " +
                            "${SystemClock.elapsedRealtime() - began} ms"
                    }
                    binding.title.text =
                        getString(R.string.bible_chapter_title, pages.bookName, pages.chapter)
                    show(0)
                }
                .onFailure { e ->
                    // The chapter reference, never its text: "where, not what".
                    Log.w(TAG, "could not open $usfm $number", e)
                    Dialogs.problem(this@BibleActivity, R.string.bible_unavailable_title, R.string.bible_unavailable_body)
                }
        }
    }

    /** Blocking — IO only. Installs the source if needed, reads the chapter and paginates it. */
    private fun buildChapter(usfm: String, number: Int, width: Int, height: Int): ChapterPages {
        val db = openSource()
        val typo = typography()
        val blocks = db.blocksForChapter(usfm, number)
        val footnotes = db.footnotesForChapter(usfm, number)
        check(blocks.isNotEmpty()) { "no blocks for $usfm $number" }
        val atoms = ChapterPaginator.atomsForBlocks(blocks, footnotes)
        // Canon's names are the ones `build_bible_db.py` wrote into the `book` table, so the
        // title on the page and the row in the database say the same thing.
        val bookName = Canon.byUsfm(usfm).name
        val safety = typo.dp(SAFETY_PAD_DP)
        val headingHeight = typo.headingHeight(bookName, number, width)
        val pages = ChapterPaginator.paginate(
            atoms,
            typo,
            width,
            firstPageHeight = height - headingHeight - safety,
            otherPageHeight = height - safety,
        )
        // Lay every page out here, on IO: a page turn then costs one invalidate on Main.
        val heading = typo.headingLayouts(bookName, number, width)
        val rendered = pages.mapIndexed { index, page ->
            val body = typo.bodyLayout(page, width)
            if (index == 0) ReaderPage(body, heading.first, heading.second) else ReaderPage(body)
        }
        return ChapterPages(usfm, number, bookName, pages, rendered)
    }

    /** The installed source, opened once for the life of the screen. Blocking — IO only. */
    private fun openSource(): BibleDatabase {
        synchronized(lock) {
            check(!closed) { "screen is gone" }
            bible?.let { return it }
        }
        // Outside the lock: the first run copies ~12 MB out of the APK, and Main must never
        // wait on that in onDestroy.
        val file = ContentInstaller(this)
            .ensureInstalled(ContentInstaller.BSB_ASSET, ContentInstaller.BSB_NAME)
        val opened = BibleDatabase.open(file.absolutePath)
        synchronized(lock) {
            val existing = bible
            if (closed || existing != null) {
                opened.close()
                check(!closed) { "screen is gone" }
                return existing!!
            }
            bible = opened
            return opened
        }
    }

    /** The typography, built once. Blocking (it loads a font) — IO only. */
    private fun typography(): ReaderTypography {
        synchronized(lock) { typography?.let { return it } }
        val typo = ReaderTypography(this)
        synchronized(lock) {
            typography?.let { return it }
            typography = typo
            return typo
        }
    }

    /** A pager tap. Out-of-range is a **no-op**, never a disabled button (invisible on e-ink). */
    private fun turnTo(index: Int) {
        val pages = chapter ?: return
        if (index < 0 || index >= pages.size) return
        show(index)
    }

    private fun show(index: Int) {
        val pages = chapter ?: return
        pageIndex = index
        readerView.show(pages.rendered[index])
        binding.pageIndicator.text =
            getString(R.string.bible_page_indicator, index + 1, pages.size)
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

        /** First ever open (decision 7): Genesis 1. B2 reads the stored position instead. */
        private const val START_USFM = "GEN"
        private const val START_CHAPTER = 1

        /** Slack under the measured page height — a rounding error must never clip a line. */
        private const val SAFETY_PAD_DP = 8f

        /** A load faster than this says nothing; only a slow one gets a word on screen. */
        private const val LOADING_DELAY_MS = 300L
    }
}
