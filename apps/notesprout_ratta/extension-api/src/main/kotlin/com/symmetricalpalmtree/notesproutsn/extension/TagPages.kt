package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The paging loop both sides of the tag seam run (arc 22 / X3) — pure, stdlib only, one copy.
 *
 * A tag reply is a `List<TagRecord>` or a `List<AssignmentRecord>` in a Binder parcel, and five
 * thousand records will not fit one transaction, so `ITagManager.tags` / `assignmentsOf` answer a
 * page at a time and the caller asks again from the next offset. **A short page ends the loop** —
 * fewer rows than were asked for means the table ran out — which costs one extra empty call when
 * the row count happens to be an exact multiple of the page size, and that is the cheap half of the
 * trade: the alternative is a separate count, which is a second question whose answer can already
 * be stale by the time the pages are read.
 *
 * The host runs it over the Binder (the host's `TagClient.search`) and the extension runs it over its own
 * store ([StoreReads]) — the same loop, so the two can never disagree about where a listing ends.
 */
object TagPages {

    /**
     * Every row [fetch] will give, page by page, starting at offset 0 and stopping at the first page
     * shorter than [pageSize].
     *
     * [maxPages] is a **runaway guard**, not a limit anyone should reach: a peer that answered a
     * full page forever would otherwise spin here for as long as the caller's timeout allows. Size
     * it from the cap that bounds the table (`MAX_TAGS / TAGS_PAGE + 1`), so tripping it means the
     * other side is misbehaving, and say so rather than answering with a truncated list.
     *
     * @throws IllegalStateException [maxPages] pages came back full.
     */
    fun <T> collect(pageSize: Int, maxPages: Int, fetch: (offset: Int) -> List<T>): List<T> {
        require(pageSize > 0) { "page size must be positive ($pageSize)" }
        require(maxPages > 0) { "max pages must be positive ($maxPages)" }
        val all = ArrayList<T>()
        var pages = 0
        while (true) {
            val page = fetch(all.size)
            all += page
            pages++
            if (page.size < pageSize) return all
            check(pages < maxPages) { "paging past $maxPages pages of $pageSize" }
        }
    }
}
