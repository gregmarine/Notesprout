package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.ByteChunks
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 51 / J3 — the reference image's chunk stream and the pick's sample size. */
class GuidePushTest {

    private fun stream(bytes: ByteArray): List<Triple<Int, ByteArray, Boolean>> {
        val sent = ArrayList<Triple<Int, ByteArray, Boolean>>()
        GuidePush.forEachChunk(bytes) { i, chunk, last -> sent += Triple(i, chunk, last) }
        return sent
    }

    @Test fun removeIsOneEmptyLastChunk() {
        val sent = stream(ByteArray(0))
        assertEquals(1, sent.size)
        assertEquals(0, sent[0].first)
        assertEquals(0, sent[0].second.size)
        assertTrue(sent[0].third)
    }

    @Test fun aLargeImageCrossesInOrderAndRejoins() {
        val bytes = ByteArray(SketchContract.SKETCH_CHUNK_BYTES * 2 + 17) { (it % 251).toByte() }
        val sent = stream(bytes)
        assertEquals(3, sent.size)
        assertEquals(listOf(0, 1, 2), sent.map { it.first })
        assertEquals(listOf(false, false, true), sent.map { it.third })
        assertArrayEquals(bytes, ByteChunks.join(sent.map { it.second }))
    }

    @Test fun sampleSizeKeepsTheLongEdgeAtOrAboveThePage() {
        // Nomad page 1404 × 1872: long edge 1872.
        assertEquals(1, GuideSheet.sampleSize(1000, 800, 1404, 1872))
        assertEquals(1, GuideSheet.sampleSize(3000, 2000, 1404, 1872))    // 3000 / 2 = 1500 < 1872
        assertEquals(2, GuideSheet.sampleSize(4000, 3000, 1404, 1872))    // 2000 ≥ 1872, 1000 not
        assertEquals(4, GuideSheet.sampleSize(8000, 6000, 1404, 1872))
        assertEquals(1, GuideSheet.sampleSize(0, 0, 1404, 1872))
    }
}
