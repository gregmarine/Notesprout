package com.symmetricalpalmtree.notesproutsn.importing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cloud download's corroboration (arc 25 / V5): two first-hand counts must agree, and the
 * listing is corroboration rather than authority — except where it claims bytes that never arrived.
 */
class CloudImportRulesTest {

    @Test
    fun `three agreeing accounts are OK`() {
        assertEquals(
            CloudImportRules.Verdict.OK,
            CloudImportRules.downloadVerdict(reported = 1024, landed = 1024, listed = 1024),
        )
    }

    @Test
    fun `what the provider says it wrote and what landed must agree`() {
        assertEquals(
            CloudImportRules.Verdict.SHORT,
            CloudImportRules.downloadVerdict(reported = 2048, landed = 1024, listed = 2048),
        )
        assertEquals(
            CloudImportRules.Verdict.SHORT,
            CloudImportRules.downloadVerdict(reported = 512, landed = 1024, listed = 1024),
        )
    }

    @Test
    fun `a listing claiming more than landed is bytes that never arrived`() {
        assertEquals(
            CloudImportRules.Verdict.SHORT,
            CloudImportRules.downloadVerdict(reported = 1024, landed = 1024, listed = 4096),
        )
    }

    @Test
    fun `a listing claiming less merely disagrees — a listing can lag its own write`() {
        assertEquals(
            CloudImportRules.Verdict.DISAGREE,
            CloudImportRules.downloadVerdict(reported = 1024, landed = 1024, listed = 900),
        )
        assertEquals(
            CloudImportRules.Verdict.DISAGREE,
            CloudImportRules.downloadVerdict(reported = 1024, landed = 1024, listed = 0),
        )
    }

    @Test
    fun `a listing that said nothing contradicts nobody`() {
        assertEquals(
            CloudImportRules.Verdict.OK,
            CloudImportRules.downloadVerdict(reported = 1024, landed = 1024, listed = -1),
        )
    }

    @Test
    fun `zero everywhere is agreement — the empty rule is routing's, not this one's`() {
        assertEquals(
            CloudImportRules.Verdict.OK,
            CloudImportRules.downloadVerdict(reported = 0, landed = 0, listed = 0),
        )
        assertEquals(
            CloudImportRules.Verdict.OK,
            CloudImportRules.downloadVerdict(reported = 0, landed = 0, listed = -1),
        )
    }
}
