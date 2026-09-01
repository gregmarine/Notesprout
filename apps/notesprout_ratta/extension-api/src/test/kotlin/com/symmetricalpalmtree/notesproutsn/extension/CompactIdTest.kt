package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The compact id transform (arc 21 / W4). It exists for one reason — the tag index pays for two ids
 * fifty thousand times — so what is pinned here is that it **round-trips exactly**, that it is the
 * width the budget assumes, and that it refuses everything that is not a canonical UUID.
 */
class CompactIdTest {

    @Test
    fun roundTripsEveryUuid() {
        val random = java.util.Random(20260901)
        repeat(500) {
            val uuid = UUID(random.nextLong(), random.nextLong()).toString()
            val compact = CompactId.compact(uuid)
            assertEquals("$uuid did not compact", CompactId.CHARS, compact!!.length)
            assertEquals(uuid, CompactId.expand(compact))
        }
    }

    /** The width the whole budget rests on. If this changes, `TagCodec.WORST_CASE_BYTES` is wrong. */
    @Test
    fun isAlwaysTwentyTwoCharacters() {
        assertEquals(22, CompactId.CHARS)
        // The extremes, where a naive encoder would pad or truncate.
        for (uuid in listOf(UUID(0L, 0L), UUID(-1L, -1L), UUID(Long.MIN_VALUE, Long.MAX_VALUE))) {
            val compact = CompactId.compact(uuid.toString())!!
            assertEquals(CompactId.CHARS, compact.length)
            assertEquals(uuid.toString(), CompactId.expand(compact))
        }
    }

    /** base64**url**: the alphabet has no `/` and no `+`, so a compact id can never look like a path
     *  segment — which is what lets the seam stop hand-checking for one. */
    @Test
    fun usesTheUrlSafeAlphabetAndNoPadding() {
        val random = java.util.Random(7)
        repeat(200) {
            val compact = CompactId.compact(UUID(random.nextLong(), random.nextLong()).toString())!!
            assertTrue(compact, compact.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        }
    }

    /**
     * `UUID.fromString` is famously lenient — it accepts `1-2-3-4-5` and pads it out — so [compact]
     * round-trips through `toString()` and takes only the canonical form. Anything else would let an
     * id in that expands back to a *different* string than the caller handed over.
     */
    @Test
    fun refusesAnythingThatIsNotACanonicalUuid() {
        for (bad in listOf(
            "",
            "n1",
            "1-2-3-4-5",
            "11111111111141118111111111111111",                 // no dashes
            "{11111111-1111-4111-8111-111111111111}",           // braces
            "urn:uuid:11111111-1111-4111-8111-111111111111",
            "11111111-1111-4111-8111-11111111111",              // one short
            "11111111-1111-4111-8111-1111111111111",            // one long
            "zzzzzzzz-1111-4111-8111-111111111111",             // not hex
        )) {
            assertNull("accepted '$bad'", CompactId.compact(bad))
            assertFalse("accepted '$bad'", CompactId.isId(bad))
        }
    }

    /** Hex case carries no meaning, but the answer is canonical lower case — callers matching
     *  expanded ids against ids read elsewhere should know which they get. */
    @Test
    fun expandsToLowerCase() {
        val upper = "AAAAAAAA-1111-4111-8111-111111111111"
        val compact = CompactId.compact(upper)!!
        assertEquals("aaaaaaaa-1111-4111-8111-111111111111", CompactId.expand(compact))
    }

    @Test
    fun expandRefusesWhatIsNotACompactId() {
        assertNull(CompactId.expand(""))
        assertNull(CompactId.expand("short"))
        assertNull(CompactId.expand("x".repeat(CompactId.CHARS + 1)))
        assertNull(CompactId.expand("!".repeat(CompactId.CHARS)))
    }
}
