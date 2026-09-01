package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The tag index's storage-and-wire form (arc 21 / W1, reshaped in W4). The three behaviours that
 * matter are the three failure meanings: **absent = first run**, **bad version = unreadable (never
 * empty)**, and **truncated tail = keep what decoded whole** — plus W4's fourth: a **v1 blob still
 * reads**, as far as it can honestly be read.
 */
class TagCodecTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun sample(): TagIndex = TagIndex.EMPTY
        .assign("Reading List", n1).index
        .assign("Reading List", n1, p1).index
        .assign("draft", n2).index

    @Test
    fun roundTripsTagsAndAssignments() {
        val before = sample()
        val after = TagCodec.decode(TagCodec.encode(before))
        assertEquals(before.tags.map { it.id to it.display }, after.tags.map { it.id to it.display })
        assertEquals(
            before.assignments.map { Triple(it.tagId, it.notebookId, it.pageId) },
            after.assignments.map { Triple(it.tagId, it.notebookId, it.pageId) },
        )
    }

    /** Ids go in as UUIDs and come back as UUIDs; the compact form lives only in the bytes. */
    @Test
    fun idsAreCompactedInTheBytesAndNowhereElse() {
        val text = TagCodec.encode(sample()).toString(Charsets.UTF_8)
        assertTrue("a raw UUID in the blob", n1 !in text)
        assertTrue("the compact notebook id is missing", CompactId.compact(n1)!! in text)
        val after = TagCodec.decode(TagCodec.encode(sample()))
        assertEquals(n1, after.assignments.first().notebookId)
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

    /**
     * A W1 blob still reads. Its **notebook** assignments migrate exactly — the target of one already
     * was its notebook — and its **page** assignments are dropped, because a page id with no
     * notebook is a record that no longer says enough to be used and there is nowhere to recover the
     * missing half from.
     */
    @Test
    fun aVersionOneBlobMigratesNotebookTagsAndDropsPageTags() {
        val blob = buildString {
            append(TagCodec.VERSION_1).append('\n')
            append("T\t0\tdraft\n")
            append("T\t1\twip\n")
            append("A\t0\t${TagShowing.TARGET_NOTEBOOK}\t$n1\n")
            append("A\t1\t${TagShowing.TARGET_PAGE}\t$p1\n")
        }.toByteArray(Charsets.UTF_8)

        val i = TagCodec.decode(blob)
        assertEquals(listOf("draft", "wip"), i.tags.map { it.display })
        assertEquals(1, i.assignments.size)
        val kept = i.assignments.single()
        assertEquals(n1, kept.notebookId)
        assertNull(kept.pageId)
        // The tag whose only assignment was a page tag survives — a dropped assignment is not a
        // dropped tag, which is the same lifecycle rule a manual removal follows.
        assertEquals(2, i.tags.size)
    }

    /** v1 is read, never written: a decode-then-encode leaves the store on the current version. */
    @Test
    fun aVersionOneBlobIsRewrittenAtTheCurrentVersion() {
        val blob = "${TagCodec.VERSION_1}\nT\t0\tdraft\nA\t0\t0\t$n1\n".toByteArray(Charsets.UTF_8)
        val text = TagCodec.encode(TagCodec.decode(blob)).toString(Charsets.UTF_8)
        assertTrue(text.startsWith("${TagCodec.VERSION}\n"))
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
        val c1 = CompactId.compact(n1)!!
        val blob = buildString {
            append(TagCodec.VERSION).append('\n')
            append("T\t0\tdraft\n")
            append("T\t1\n")                    // too few fields
            append("Z\tsomething new\n")        // a record kind this version does not know
            append("A\t0\t$c1\n")
            append("A\t0\tnot-compact\n")       // an id that will not expand
            append("A\t0\t$c1\t$c1\t$c1\n")     // too many fields
            append("\n")                        // a blank line
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
            assignments = listOf(TagIndex.Assignment("0", n1)),
        )
        val text = TagCodec.encode(i).toString(Charsets.UTF_8)
        assertEquals("${TagCodec.VERSION}\nT\t0\tdraft\nA\t0\t${CompactId.compact(n1)}\n", text)
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
        // The widest legal pair of records: one tag at its cap, plus a PAGE assignment, which is the
        // larger of the two assignment shapes because it carries both ids.
        val one = TagIndex.EMPTY.assign(widest, n1, p1).index
        val perRecord = TagCodec.encode(one).size - TagCodec.VERSION.length - 1
        val worstPerRecord =
            (1 + 1 + TagCodec.MAX_TAG_ID_CHARS + 1 + ExtensionContract.MAX_TAG_CHARS * 3 + 1) +
                (1 + 1 + TagCodec.MAX_TAG_ID_CHARS + 1 + CompactId.CHARS + 1 + CompactId.CHARS + 1)
        assertTrue("$perRecord > $worstPerRecord", perRecord <= worstPerRecord)
    }

    /** W4 shrank the record; the budget must have got *looser*, not tighter, or a cap moved by
     *  accident. */
    @Test
    fun theWorstCaseLeavesRoomAtEveryCapTheWizardSet() {
        assertEquals(5_000, ExtensionContract.MAX_TAGS)
        assertEquals(50_000, ExtensionContract.MAX_TAG_ASSIGNMENTS)
        assertEquals(64, ExtensionContract.MAX_TAG_CHARS)
        assertEquals(3_650_007, TagCodec.WORST_CASE_BYTES)
    }
}
