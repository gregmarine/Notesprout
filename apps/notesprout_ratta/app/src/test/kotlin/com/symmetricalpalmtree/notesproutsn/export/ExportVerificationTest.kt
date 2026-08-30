package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.export.ExportVerification.Verdict
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-source-kind verification split (arc 18 / D1): the `bytesWritten == streamBytes`
 * equality is a verbatim-streaming contract — SOURCE_SOIL only. A SOURCE_PAGES exporter
 * transforms, so its verdict rests on its own report corroborated against the destination alone.
 */
class ExportVerificationTest {

    private fun soil(bytesWritten: Long, streamBytes: Long, dest: List<Long>) =
        ExportVerification.verdict(ExporterContract.SOURCE_SOIL, bytesWritten, streamBytes, dest)

    private fun pages(bytesWritten: Long, dest: List<Long>) =
        ExportVerification.verdict(ExporterContract.SOURCE_PAGES, bytesWritten, streamBytes = 12345L, destinationSizes = dest)

    @Test
    fun soilKeepsTheVerbatimEquality() {
        assertEquals(Verdict.OK, soil(100L, 100L, listOf(100L)))
        assertEquals(Verdict.OK, soil(100L, 100L, emptyList()))
        assertEquals(Verdict.SHORT, soil(99L, 100L, listOf(100L)))
        assertEquals(Verdict.SHORT, soil(0L, 100L, emptyList()))
    }

    @Test
    fun soilCorroborationNeedsOnlyOneAgreeingAnswer() {
        assertEquals(Verdict.OK, soil(100L, 100L, listOf(37L, 100L)))
        assertEquals(Verdict.UNCONFIRMED, soil(100L, 100L, listOf(37L, 38L)))
    }

    @Test
    fun pagesNeverComparesAgainstTheSource() {
        // 4096 bytes of PDF from a 12345-byte bundle is an honest export, not a short one.
        assertEquals(Verdict.OK, pages(4096L, listOf(4096L)))
        assertEquals(Verdict.OK, pages(4096L, emptyList()))
    }

    @Test
    fun pagesStillRefusesNothingAndDisagreement() {
        assertEquals(Verdict.SHORT, pages(0L, emptyList()))
        assertEquals(Verdict.SHORT, pages(-1L, listOf(0L)))
        assertEquals(Verdict.UNCONFIRMED, pages(4096L, listOf(37L, 38L)))
        assertEquals(Verdict.OK, pages(4096L, listOf(37L, 4096L)))
    }

    @Test
    fun unknownKindNeverDefaultsToTrust() {
        assertEquals(Verdict.SHORT, ExportVerification.verdict(99, 100L, 100L, listOf(100L)))
    }
}
