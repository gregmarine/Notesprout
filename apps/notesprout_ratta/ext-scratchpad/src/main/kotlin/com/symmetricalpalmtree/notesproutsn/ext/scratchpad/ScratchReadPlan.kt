package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract

/**
 * How a page's strokes are read back (arc 22 / X2) — pure, JVM-tested.
 *
 * A page has no size ceiling any more, so it can easily hold more ink than one result may carry.
 * The page is read in two steps: the orders and blob lengths first (small — one row per stroke,
 * two integers), then the strokes themselves, one `BETWEEN` range at a time, each range planned to
 * fit inside a chunk. That keeps the read off `STORE_RESULT_LARGE` entirely rather than
 * discovering it at the row that crosses.
 *
 * Ranges run over the `"order"` column, and the orders handed in are the page's whole set in
 * ascending order, so a range's endpoints select exactly the strokes it was planned for. A single
 * stroke bigger than the budget gets its own range — the host will refuse that row and the read
 * drops it, keeping the rest of the page.
 */
object ScratchReadPlan {

    /**
     * What a stroke row costs besides its blob: the id, order, colour, width and style cells plus
     * their tags. A canonical UUID id is 41 bytes encoded, the three numbers 9 each, a style name
     * well under 16, and the blob's own tag and length 5 — 128 is that with room to spare, and it
     * only has to be an over-estimate for the plan to hold.
     */
    const val ROW_OVERHEAD: Int = 128

    fun ranges(
        orders: List<Long>,
        lengths: List<Int>,
        budget: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
    ): List<LongRange> {
        require(orders.size == lengths.size) { "${orders.size} orders for ${lengths.size} lengths" }
        if (orders.isEmpty()) return emptyList()
        val out = ArrayList<LongRange>()
        var start = 0
        var bytes = 0L
        for (i in orders.indices) {
            val cost = lengths[i].toLong() + ROW_OVERHEAD
            if (i > start && bytes + cost > budget) {
                out += orders[start]..orders[i - 1]
                start = i
                bytes = 0L
            }
            bytes += cost
        }
        out += orders[start]..orders[orders.size - 1]
        return out
    }
}
