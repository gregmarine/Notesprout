package com.notesprout.android.recognition.trocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CerMetricTest {

    @Test
    fun identicalStringsHaveZeroCer() {
        assertEquals(0.0, CerMetric.cer("hello world", "hello world"), 1e-9)
    }

    @Test
    fun classicLevenshteinCases() {
        assertEquals(3, CerMetric.levenshtein("kitten", "sitting"))
        assertEquals(5, CerMetric.levenshtein("", "hello"))
        assertEquals(5, CerMetric.levenshtein("hello", ""))
    }

    @Test
    fun cerIsEditsOverReferenceLength() {
        assertEquals(3.0 / 6.0, CerMetric.cer("kitten", "sitting"), 1e-9)
    }

    @Test
    fun corpusCerAggregatesAcrossLines() {
        val refs = listOf("abc", "defg")
        val hyps = listOf("abc", "dxfg") // 1 edit / 7 chars
        assertEquals(1.0 / 7.0, CerMetric.corpusCer(refs, hyps), 1e-9)
    }

    @Test
    fun emptyReferenceEdgeCases() {
        assertEquals(0.0, CerMetric.cer("", ""), 1e-9)
        assertEquals(1.0, CerMetric.cer("", "x"), 1e-9)
    }
}
