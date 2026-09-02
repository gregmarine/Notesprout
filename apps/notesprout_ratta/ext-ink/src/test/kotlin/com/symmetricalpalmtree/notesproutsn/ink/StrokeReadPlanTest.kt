package com.symmetricalpalmtree.notesproutsn.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a page of any size back in planned `BETWEEN` ranges (arc 22 / X2). */
class StrokeReadPlanTest {

    private val overhead = StrokeReadPlan.ROW_OVERHEAD.toLong()

    @Test
    fun anEmptyPageIsNoReads() {
        assertEquals(emptyList<LongRange>(), StrokeReadPlan.ranges(emptyList(), emptyList()))
    }

    @Test
    fun onePageThatFitsIsOneRange() {
        val orders = listOf(0L, 1L, 2L, 7L)
        assertEquals(listOf(0L..7L), StrokeReadPlan.ranges(orders, List(4) { 100 }, budget = 10_000))
    }

    @Test
    fun packingSplitsAtTheBudget_andCoversEveryOrderExactlyOnce() {
        val orders = (0L until 10L).toList()
        val lengths = List(10) { 400 }
        val budget = (3 * (400 + overhead)).toInt()
        val ranges = StrokeReadPlan.ranges(orders, lengths, budget)
        assertEquals(listOf(0L..2L, 3L..5L, 6L..8L, 9L..9L), ranges)
        // Contiguous, in order, and every planned range is inside the budget.
        var i = 0
        for (r in ranges) {
            var bytes = 0L
            while (i < orders.size && orders[i] <= r.last) { bytes += lengths[i] + overhead; i++ }
            assertTrue("$r is $bytes bytes", bytes <= budget)
        }
        assertEquals(orders.size, i)
    }

    @Test
    fun aLoneOversizeStrokeGetsItsOwnRange() {
        val orders = listOf(0L, 1L, 2L)
        val lengths = listOf(10, 100_000, 10)
        val ranges = StrokeReadPlan.ranges(orders, lengths, budget = 1_000)
        assertEquals(listOf(0L..0L, 1L..1L, 2L..2L), ranges)
    }

    @Test
    fun nonContiguousOrdersStillSelectExactlyTheirStrokes() {
        // Orders are unique and monotone but not dense — erases leave gaps, and a range's endpoints
        // are real orders, so nothing outside the group can fall inside it.
        val orders = listOf(3L, 9L, 40L, 41L)
        val ranges = StrokeReadPlan.ranges(orders, List(4) { 500 }, budget = (2 * (500 + overhead)).toInt())
        assertEquals(listOf(3L..9L, 40L..41L), ranges)
    }

    @Test
    fun ordersAndLengthsMustAgree() {
        try {
            StrokeReadPlan.ranges(listOf(0L), emptyList())
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // the plan is built from one index read — a mismatch is a bug, not a state
        }
    }
}
