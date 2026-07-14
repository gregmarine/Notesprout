package com.notesprout.android.recognition.personal

import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentScriptTest {

    @Test
    fun coversFullAlphabetAndDigits() {
        val all = EnrollmentScript.SENTENCES.joinToString(" ")
        val lower = all.lowercase()
        for (c in 'a'..'z') {
            assertTrue("missing letter $c", c in lower)
        }
        for (d in '0'..'9') {
            assertTrue("missing digit $d", d in all)
        }
    }

    @Test
    fun coversCommonPunctuation() {
        val all = EnrollmentScript.SENTENCES.joinToString(" ")
        for (p in listOf('.', ',', '!', '?', ':', '-', '$', '@', '&', '(', ')', '\'')) {
            assertTrue("missing punctuation $p", p in all)
        }
    }

    @Test
    fun sentencesStayFamilyFriendly() {
        // The teaching flow is first-run UX — keep the wording inoffensive (user request:
        // weird is fine, off-putting is not).
        val banned = listOf("liquor", "whiskey", "vodka", "beer", "wine", "drunk", "damn", "hell")
        val lower = EnrollmentScript.SENTENCES.joinToString(" ").lowercase()
        for (word in banned) {
            // Whole-word match — "hello" must not trip on "hell".
            assertTrue(
                "banned word '$word' in enrollment script",
                !Regex("\\b$word\\b").containsMatchIn(lower),
            )
        }
    }

    @Test
    fun sentencesAreSingleLineSized() {
        for (s in EnrollmentScript.SENTENCES) {
            assertTrue("too long for one written line: $s", s.length <= 60)
        }
    }
}
