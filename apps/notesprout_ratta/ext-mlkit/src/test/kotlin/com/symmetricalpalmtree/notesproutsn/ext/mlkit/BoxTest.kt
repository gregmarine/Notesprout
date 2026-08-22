package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxTest {

    @Test
    fun boundsOfAPointSet() {
        val b = Box.of(floatArrayOf(5f, 1f, 3f), floatArrayOf(2f, 9f, 4f))
        assertEquals(Box(1f, 2f, 5f, 9f), b)
        assertEquals(4f, b.width, 0f)
        assertEquals(7f, b.height, 0f)
        assertEquals(3f, b.centerX, 0f)
        assertEquals(5.5f, b.centerY, 0f)
    }

    @Test
    fun aSinglePointIsAZeroSizedBox() {
        val b = Box.of(floatArrayOf(3f), floatArrayOf(4f))
        assertEquals(Box(3f, 4f, 3f, 4f), b)
        assertEquals(0f, b.width, 0f)
        assertEquals(0f, b.height, 0f)
    }

    @Test
    fun unionIsSymmetricAndCoversBoth() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(5f, -5f, 20f, 8f)
        assertEquals(Box(0f, -5f, 20f, 10f), a.union(b))
        assertEquals(a.union(b), b.union(a))
        assertEquals(a, a.union(a))
    }

    @Test
    fun verticalOverlapIsAFractionOfTheShorterBox() {
        val a = Box(0f, 0f, 10f, 10f)
        assertEquals(0f, a.verticalOverlapFrac(Box(0f, 10f, 10f, 20f)), 0f)    // touching counts as disjoint
        assertEquals(0f, a.verticalOverlapFrac(Box(0f, 15f, 10f, 20f)), 0f)    // clear of it
        assertEquals(0.5f, a.verticalOverlapFrac(Box(0f, 5f, 10f, 15f)), 1e-6f)  // 5 px of the shorter 10
        assertEquals(1f, a.verticalOverlapFrac(Box(0f, 2f, 10f, 6f)), 1e-6f)     // contained
        assertEquals(1f, Box(0f, 2f, 10f, 6f).verticalOverlapFrac(a), 1e-6f)     // and symmetric
    }
}
