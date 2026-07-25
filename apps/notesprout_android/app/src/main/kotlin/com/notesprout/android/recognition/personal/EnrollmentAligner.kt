package com.notesprout.android.recognition.personal

import com.notesprout.android.recognition.trocr.CerMetric

/**
 * Splits a known enrollment sentence across multiple written lines.
 *
 * When the user wraps a sentence over several lines (long sentence, small screen, large
 * handwriting), each line becomes its own training pair — so the sentence's words must be
 * distributed to the right lines. We don't trust any engine's transcription as a label,
 * but a rough per-line transcription is plenty to find the SPLIT POINTS: dynamic
 * programming assigns a contiguous word range to each line minimizing the summed edit
 * distance against the rough readings. A misread word doesn't matter; only the boundary
 * has to land between the right words.
 *
 * Pure JVM, unit-testable with fake transcriptions.
 */
object EnrollmentAligner {

    private val WHITESPACE = Regex("\\s+")

    /**
     * Distribute [sentence]'s words over [lineTexts] (rough per-line transcriptions in
     * reading order). Returns one label per line, or null when no sensible split exists
     * (more lines than words, or a line would receive no words).
     */
    fun align(sentence: String, lineTexts: List<String>): List<String>? {
        val k = lineTexts.size
        if (k == 0) return null
        if (k == 1) return listOf(sentence)
        val words = sentence.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val n = words.size
        if (n < k) return null

        // cost of labeling line j with words[a until b)
        fun cost(j: Int, a: Int, b: Int): Int =
            CerMetric.levenshtein(
                normalize(lineTexts[j]),
                normalize(words.subList(a, b).joinToString(" ")),
            )

        // dp[j][i] = min cost of assigning the first i words to the first j lines
        // (each line gets >= 1 word). split[j][i] = chosen start of line j-1's range.
        val inf = Int.MAX_VALUE / 2
        val dp = Array(k + 1) { IntArray(n + 1) { inf } }
        val split = Array(k + 1) { IntArray(n + 1) }
        dp[0][0] = 0
        for (j in 1..k) {
            for (i in j..(n - (k - j))) {
                for (a in (j - 1) until i) {
                    if (dp[j - 1][a] >= inf) continue
                    val c = dp[j - 1][a] + cost(j - 1, a, i)
                    if (c < dp[j][i]) {
                        dp[j][i] = c
                        split[j][i] = a
                    }
                }
            }
        }
        if (dp[k][n] >= inf) return null

        val labels = arrayOfNulls<String>(k)
        var i = n
        for (j in k downTo 1) {
            val a = split[j][i]
            labels[j - 1] = words.subList(a, i).joinToString(" ")
            i = a
        }
        return labels.filterNotNull().takeIf { it.size == k }
    }

    /** Case/spacing-insensitive comparison basis — split points care about letters, not case. */
    private fun normalize(s: String) = s.trim().replace(WHITESPACE, " ").lowercase()
}
