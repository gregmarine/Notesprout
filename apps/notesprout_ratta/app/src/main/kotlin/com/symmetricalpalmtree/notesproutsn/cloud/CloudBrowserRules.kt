package com.symmetricalpalmtree.notesproutsn.cloud

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import kotlin.math.floor

/**
 * **The cloud browser's rules** (arc 25 / V3) — everything [CloudBrowserDialog] decides that is not
 * a view, pure so it is pinned by JVM test: the browser itself is a list of rows over a Binder call,
 * and a Binder call is the one part of it no test can drive.
 *
 * The rules, and why each is a rule:
 *
 *  - **[crumb]** — the trail is `<provider> › Exports › …`, always headed by the provider's own
 *    name, because the person is looking at *their* cloud and the folder names alone would not say
 *    whose. No path, no id, no URL: the browser has none of those and this is the only line that
 *    could leak one.
 *  - **[rows] / [page] / [itemsPerPage]** — the list **paginates, it never scrolls** (the e-ink
 *    rule, the Contents dialog's shape). *New folder…* is a row of the list rather than a fixture
 *    above it, so it pages with everything else and cannot sit on top of a folder row that has
 *    scrolled under it.
 *  - **[folderNamed] / [fileNamed]** — the two lookups over the listing the browser last drew.
 *    Names match **exactly**: `upload` is replace-by-name and resolves the same way, so a
 *    case-insensitive match here would warn about replacing a file the upload would in fact leave
 *    alone.
 *  - **[newFolderOutcome]** — the New-folder answer. A name already listed as a **folder** is not a
 *    second folder to make: the browser just enters it, which is what the person meant. A name the
 *    seam cannot carry, or a depth past [CloudContract.MAX_PATH_DEPTH], is [REFUSED] here rather
 *    than at the call — a refusal must never cost a bind, and the sentence is better said before
 *    the wait than after it.
 *
 * **Browsing creates nothing.** Nothing in this file makes a folder; `ensureFolder` is reached only
 * from the New-folder row, and `Exports/` itself is made by the upload on the way past.
 */
object CloudBrowserRules {

    /** Row height + separator (dp) — `item_cloud_entry.xml`'s minHeight and its 1 dp line, the
     *  Contents row's measurements so the two lists read as one family. */
    const val ROW_HEIGHT_DP = 68f
    const val ROW_SEPARATOR_DP = 1f

    /** One drawn line of the browser. */
    sealed class Row {
        /** The *New folder…* row — first in every folder, and only where a folder is being picked. */
        object NewFolder : Row()

        /** One folder or file the provider listed. */
        class Entry(val entry: CloudEntry) : Row()
    }

    /** What tapping **Create** on a typed name should do. */
    enum class NewFolderOutcome {
        /** A folder of that name is already listed — enter it rather than make a second one. */
        ENTER_EXISTING,

        /** `ensureFolder(path + name)`, then enter. */
        CREATE,

        /** The name or the depth is not something this seam can carry — say so, create nothing. */
        REFUSED,
    }

    /** The trail across the top bar. [separator] comes from a string resource so the glyph is the
     *  app's, not this file's. */
    fun crumb(providerName: String, path: List<String>, separator: String): String =
        (listOf(providerName) + path).joinToString(separator)

    /** The list as it is drawn: the New-folder row (when offered) and then the provider's own
     *  order — folders first, then files, each group by name, which is what `list` promises. */
    fun rows(entries: List<CloudEntry>, offersNewFolder: Boolean): List<Row> {
        val out = ArrayList<Row>(entries.size + 1)
        if (offersNewFolder) out += Row.NewFolder
        for (e in entries) out += Row.Entry(e)
        return out
    }

    /** How many pages [rowCount] rows make at [perPage] — at least one, so an empty folder still
     *  has a page to be empty on. */
    fun pageCount(rowCount: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return maxOf(1, (rowCount + perPage - 1) / perPage)
    }

    /** The slice of [rows] on [page]. A page past the end answers empty rather than throwing —
     *  the caller clamps, and a browser must never crash on a listing that shrank under it. */
    fun page(rows: List<Row>, page: Int, perPage: Int): List<Row> {
        if (perPage <= 0) return emptyList()
        val start = page * perPage
        if (start < 0 || start >= rows.size) return emptyList()
        return rows.subList(start, minOf(start + perPage, rows.size))
    }

    /** How many rows fit a body of [bodyHeightPx] at [density] — at least 1 (the Contents rule). */
    fun itemsPerPage(bodyHeightPx: Int, density: Float): Int {
        val rowPx = (ROW_HEIGHT_DP + ROW_SEPARATOR_DP) * density
        if (rowPx <= 0f) return 1
        return maxOf(1, floor(bodyHeightPx / rowPx).toInt())
    }

    /** Whether the Up arrow has anywhere to go: the browser never climbs above the folder it was
     *  opened on ([baseDepth]) — Cancel is the way out of that one. */
    fun canGoUp(depth: Int, baseDepth: Int): Boolean = depth > baseDepth

    /** The folder called [name] in this listing, or null. Exact match. */
    fun folderNamed(entries: List<CloudEntry>, name: String): CloudEntry? =
        entries.firstOrNull { it.isFolder && it.name == name }

    /** The file called [name] in this listing, or null — the replace-by-name warning's one
     *  question. Exact match, because that is how the upload resolves it. */
    fun fileNamed(entries: List<CloudEntry>, name: String): CloudEntry? =
        entries.firstOrNull { !it.isFolder && it.name == name }

    /**
     * What to do with a typed folder name. [depth] is the depth of the folder being created *in*
     * (so the new folder would sit at `depth + 1`), and [entries] the listing the browser last drew
     * of it.
     *
     * A same-named **file** is not in the way: a provider may hold both, and the folder is what was
     * asked for.
     */
    fun newFolderOutcome(name: String, entries: List<CloudEntry>, depth: Int): NewFolderOutcome = when {
        !CloudContract.isName(name) -> NewFolderOutcome.REFUSED
        depth + 1 > CloudContract.MAX_PATH_DEPTH -> NewFolderOutcome.REFUSED
        folderNamed(entries, name) != null -> NewFolderOutcome.ENTER_EXISTING
        else -> NewFolderOutcome.CREATE
    }
}
