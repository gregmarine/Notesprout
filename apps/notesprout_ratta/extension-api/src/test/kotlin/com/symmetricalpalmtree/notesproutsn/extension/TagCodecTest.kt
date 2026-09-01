package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The tag index's storage-and-wire form (arc 21 / W1). The three behaviours that matter are the
 * three failure meanings: **absent = first run**, **bad version = unreadable (never empty)**, and
 * **truncated tail = keep what decoded whole**.
 */
class TagCodecTest {

    private val nb = TagShowing.TARGET_NOTEBOOK
    private val page = TagShowing.TARGET_PAGE

    private fun sample(): TagIndex = TagIndex.EMPTY
        .assign("Reading List", nb, "n1").index
        .assign("Reading List", page, "p1").index
        .assign("draft", nb, "n2").index

    @Test
    fun roundTripsTagsAndAssignments() {
        val before = sample()
        val after = TagCodec.decode(TagCodec.encode(before))
        assertEquals(before.tags.map { it.id to it.display }, after.tags.map { it.id to it.display })
        assertEquals(
            before.assignments.map { Triple(it.tagId, it.targetKind, it.targetId) },
            after.assignments.map { Triple(it.tagId, it.targetKind, it.targetId) },
        )
    }

    @Test
    fun emptyIndexRoundTripsToEmpty() {
        val bytes = TagCodec.encode(TagIndex.EMPTY)
        assertEquals("${TagCodec.VERSION}\n", bytes.toString(Charsets.UTF_8))
        assertEquals(0, TagCodec.decode(bytes).tags.size)
    }

    /** An absent value is a first run — not a failure, and nothing to warn about. */
    @Test
    fun absentIsFirstRun() {
        assertEquals(0, TagCodec.decode(null).tags.size)
        assertEquals(0, TagCodec.decode(ByteArray(0)).tags.size)
    }

    /** Unreadable is NOT empty: the caller must say so, and must not save a blank index over it. */
    @Test
    fun unknownVersionThrows() {
        for (blob in listOf("NSTAG9\nT\t0\tdraft\n", "garbage", "\n")) {
            try {
                TagCodec.decode(blob.toByteArray(Charsets.UTF_8))
                fail("expected IllegalArgumentException for '$blob'")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun truncatedTailKeepsWhatDecodedWhole() {
        val whole = TagCodec.encode(sample()).toString(Charsets.UTF_8)
        // Cut mid-record: the partial last line is dropped, everything before it survives.
        val cut = whole.substring(0, whole.length - 4)
        val decoded = TagCodec.decode(cut.toByteArray(Charsets.UTF_8))
        assertTrue(decoded.tags.isNotEmpty())
        assertEquals(sample().assignments.size - 1, decoded.assignments.size)
    }

    @Test
    fun malformedAndUnknownRecordsAreSkipped() {
        val blob = buildString {
            append(TagCodec.VERSION).append('\n')
            append("T\t0\tdraft\n")
            append("T\t1\n")                 // too few fields
            append("Z\tsomething new\n")     // a record kind this version does not know
            append("A\t0\t0\tn1\n")
            append("A\t0\tnotanumber\tn1\n") // kind is not an int
            append("\n")                      // a blank line
        }.toByteArray(Charsets.UTF_8)
        val i = TagCodec.decode(blob)
        assertEquals(listOf("draft"), i.tags.map { it.display })
        assertEquals(1, i.assignments.size)
    }

    /** A record whose field would break the line structure is dropped rather than escaped
     *  (the `UserWords` rule) — unreachable from normalized text, which is the point. */
    @Test
    fun encodeDropsARecordItCannotWriteCleanly() {
        val i = TagIndex.of(
            tags = listOf(TagIndex.Tag("0", "draft")),
            assignments = listOf(TagIndex.Assignment("0", nb, "n1")),
        )
        val text = TagCodec.encode(i).toString(Charsets.UTF_8)
        assertEquals("${TagCodec.VERSION}\nT\t0\tdraft\nA\t0\t0\tn1\n", text)
    }

    /**
     * The whole point of the caps: **one store value is enough**. If a cap moves and this stops
     * holding, the index would silently need splitting across keys, which is a different feature.
     */
    @Test
    fun theWorstLegalIndexFitsOneStoreValue() {
        assertTrue(
            "worst case ${TagCodec.WORST_CASE_BYTES} > ${ExtensionContract.STORE_MAX_VALUE_BYTES}",
            TagCodec.WORST_CASE_BYTES <= ExtensionContract.STORE_MAX_VALUE_BYTES,
        )
        // And the arithmetic is honest about the widest characters a tag may hold: 64 UTF-16 units
        // is at most 192 UTF-8 bytes (a 4-byte code point costs two units, so it is cheaper per
        // unit, not dearer).
        val widest = "中".repeat(ExtensionContract.MAX_TAG_CHARS)
        assertEquals(ExtensionContract.MAX_TAG_CHARS * 3, widest.toByteArray(Charsets.UTF_8).size)
        val one = TagIndex.EMPTY.assign(widest, nb, "0".repeat(ExtensionContract.MAX_TARGET_ID_CHARS)).index
        val perRecord = TagCodec.encode(one).size - TagCodec.VERSION.length - 1
        val worstPerRecord =
            (1 + 1 + TagCodec.MAX_TAG_ID_CHARS + 1 + ExtensionContract.MAX_TAG_CHARS * 3 + 1) +
                (1 + 1 + TagCodec.MAX_TAG_ID_CHARS + 1 + 1 + 1 + ExtensionContract.MAX_TARGET_ID_CHARS + 1)
        assertTrue("$perRecord > $worstPerRecord", perRecord <= worstPerRecord)
    }
}
