package com.notesprout.android.recognition.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentAlignerTest {

    @Test
    fun singleLineGetsWholeSentence() {
        assertEquals(
            listOf("The quick brown fox."),
            EnrollmentAligner.align("The quick brown fox.", listOf("whatever")),
        )
    }

    @Test
    fun cleanTwoLineSplit() {
        val labels = EnrollmentAligner.align(
            "The quick brown fox jumps over the lazy dog.",
            listOf("The quick brown fox", "jumps over the lazy dog"),
        )
        assertEquals(
            listOf("The quick brown fox", "jumps over the lazy dog."),
            labels,
        )
    }

    @Test
    fun noisyTranscriptionsStillFindTheBoundary() {
        // Misreads ("quick"→"quiek", "lazy"→"1azy") must not move the split point.
        val labels = EnrollmentAligner.align(
            "The quick brown fox jumps over the lazy dog.",
            listOf("the quiek brown fax", "jumps ovr the 1azy dog"),
        )
        assertEquals(
            listOf("The quick brown fox", "jumps over the lazy dog."),
            labels,
        )
    }

    @Test
    fun threeLineSplit() {
        val labels = EnrollmentAligner.align(
            "Groceries: milk, eggs, bread, coffee (2 bags).",
            listOf("Groceries milk", "eggs bread", "coffee 2 bags"),
        )
        assertEquals(
            listOf("Groceries: milk,", "eggs, bread,", "coffee (2 bags)."),
            labels,
        )
    }

    @Test
    fun moreLinesThanWordsIsRejected() {
        assertNull(EnrollmentAligner.align("Two words", listOf("a", "b", "c")))
    }

    @Test
    fun emptyLinesRejected() {
        assertNull(EnrollmentAligner.align("Some sentence here", emptyList()))
    }
}
