package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.Atom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ChapterPaginator
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderPage
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderTypography

/**
 * One paginated chapter, ready to page through: the atoms each page carries, the laid-out page
 * beside it, and the verse each page opens on ([ChapterPaginator.anchorVerses] — the position
 * anchor), all built together on IO. Page 0 wears the book title and the big chapter number.
 */
class ChapterPages(
    val ref: ChapterRef,
    val bookName: String,
    val pages: List<List<Atom>>,
    val rendered: List<ReaderPage>,
    val anchors: List<Int>,
) {
    val size: Int get() = pages.size
    val usfm: String get() = ref.usfm
    val chapter: Int get() = ref.chapter
}

/**
 * Everything the reader's screen needs off the main thread, in one place (arc 37 / B2): the
 * installed source, the typography, the canon's chapter counts — and a small cache of built
 * chapters, so a flow across a chapter edge is usually one `invalidate()` rather than a read, a
 * pagination and a dozen `StaticLayout`s.
 *
 * **Every method here blocks — call them on `Dispatchers.IO`, never Main.** [close] is the one
 * exception; it is called from `onDestroy` and only takes the cheap field lock.
 *
 * Two monitors, deliberately:
 * - [lock] guards the fields and the cache. Only ever held around field work — never around the
 *   first-run asset copy or a database open, which Main must never end up waiting on.
 * - [buildLock] serializes chapter building. One [ReaderTypography] means one shared `TextPaint`,
 *   and a `Paint` measured from two threads at once is not safe. The cost is that a foreground
 *   load can wait for one already-running prefetch build; the prefetch is what makes the edge
 *   free in the first place, and the screen is showing its "Loading…" line meanwhile.
 *
 * **Nothing about what is read is ever logged** — this class holds scripture and says nothing.
 */
class ChapterLoader(private val context: Context) {

    private val lock = Any()
    private val buildLock = Any()

    private var bible: BibleDatabase? = null
    private var typography: ReaderTypography? = null
    private var cursor: ChapterCursor? = null
    private var closed = false

    /** Built chapters, oldest evicted first. Guarded by [lock]; see [CACHE_MAX]. */
    private val cache = object : LinkedHashMap<ChapterRef, ChapterPages>(8, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChapterRef, ChapterPages>) =
            size > CACHE_MAX
    }

    /** The geometry every cached chapter was paginated against; a change empties the cache. */
    private var cacheWidth = 0
    private var cacheHeight = 0

    /**
     * The canon walker, or null until the first chapter has been built — [chapter] reads the
     * source's `book` table once on its way through, so the screen can ask for it on Main
     * afterwards without blocking.
     */
    fun cursorNow(): ChapterCursor? = synchronized(lock) { cursor }

    /** A chapter already built and still cached, or null. Cheap — safe on Main. */
    fun cached(ref: ChapterRef): ChapterPages? = synchronized(lock) { cache[ref] }

    /**
     * [ref], paginated for a page of [width] × [height] px. Cached: asking twice costs one map
     * lookup. Blocking — IO only.
     */
    fun chapter(ref: ChapterRef, width: Int, height: Int): ChapterPages {
        cachedAt(ref, width, height)?.let { return it }
        synchronized(buildLock) {
            cachedAt(ref, width, height)?.let { return it }
            val built = build(ref, width, height)
            synchronized(lock) {
                if (width != cacheWidth || height != cacheHeight) {
                    cache.clear()
                    cacheWidth = width
                    cacheHeight = height
                }
                cache[ref] = built
            }
            return built
        }
    }

    /** Closes the source. Main-safe: it never waits on a build, only on the field lock. */
    fun close() {
        val open = synchronized(lock) {
            closed = true
            cache.clear()
            val db = bible
            bible = null
            db
        }
        open?.close()
    }

    // --- the work -----------------------------------------------------------

    private fun cachedAt(ref: ChapterRef, width: Int, height: Int): ChapterPages? =
        synchronized(lock) { if (width == cacheWidth && height == cacheHeight) cache[ref] else null }

    private fun build(ref: ChapterRef, width: Int, height: Int): ChapterPages {
        val db = source()
        val typo = typography()
        val blocks = db.blocksForChapter(ref.usfm, ref.chapter)
        val footnotes = db.footnotesForChapter(ref.usfm, ref.chapter)
        check(blocks.isNotEmpty()) { "no blocks for ${ref.usfm} ${ref.chapter}" }
        val atoms = ChapterPaginator.atomsForBlocks(blocks, footnotes)
        // Canon's names are the ones `build_bible_db.py` wrote into the `book` table, so the
        // title on the page and the row in the database say the same thing.
        val bookName = Canon.byUsfm(ref.usfm).name
        val safety = typo.dp(SAFETY_PAD_DP)
        val headingHeight = typo.headingHeight(bookName, ref.chapter, width)
        val pages = ChapterPaginator.paginate(
            atoms,
            typo,
            width,
            firstPageHeight = height - headingHeight - safety,
            otherPageHeight = height - safety,
        )
        check(pages.isNotEmpty()) { "no pages for ${ref.usfm} ${ref.chapter}" }
        // Lay every page out here, on IO: a page turn then costs one invalidate on Main.
        val heading = typo.headingLayouts(bookName, ref.chapter, width)
        val rendered = pages.mapIndexed { index, page ->
            val body = typo.bodyLayout(page, width)
            if (index == 0) ReaderPage(body, heading.first, heading.second) else ReaderPage(body)
        }
        return ChapterPages(ref, bookName, pages, rendered, ChapterPaginator.anchorVerses(pages))
    }

    /** The installed source, opened once for the life of the screen. Blocking — IO only. */
    private fun source(): BibleDatabase {
        synchronized(lock) {
            check(!closed) { "screen is gone" }
            bible?.let { return it }
        }
        // Outside the lock: the first run copies ~12 MB out of the APK, and Main must never
        // wait on that in onDestroy.
        val file = ContentInstaller(context)
            .ensureInstalled(ContentInstaller.BSB_ASSET, ContentInstaller.BSB_NAME)
        val opened = BibleDatabase.open(file.absolutePath)
        val counts = opened.books().associate { it.usfm to it.chapterCount }
        synchronized(lock) {
            val existing = bible
            if (closed || existing != null) {
                opened.close()
                check(!closed) { "screen is gone" }
                return existing!!
            }
            bible = opened
            // The chain of chapters comes from the source itself, not from a hardcoded table:
            // read once here, beside the open that made it readable.
            cursor = ChapterCursor(counts)
            return opened
        }
    }

    /** The typography, built once. Blocking (it loads a font) — IO only. */
    private fun typography(): ReaderTypography {
        synchronized(lock) { typography?.let { return it } }
        val typo = ReaderTypography(context)
        synchronized(lock) {
            typography?.let { return it }
            typography = typo
            return typo
        }
    }

    companion object {
        /** Slack under the measured page height — a rounding error must never clip a line. */
        private const val SAFETY_PAD_DP = 8f

        /** The current chapter and its two neighbours, with room for the pair behind them. */
        private const val CACHE_MAX = 5
    }
}
