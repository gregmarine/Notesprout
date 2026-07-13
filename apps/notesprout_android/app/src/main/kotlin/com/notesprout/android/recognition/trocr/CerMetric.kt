package com.notesprout.android.recognition.trocr

/**
 * Character Error Rate: Levenshtein distance / reference length. Pure JVM.
 * Used by HwrLab to score engines against pasted reference text.
 */
object CerMetric {

    /** CER of [hypothesis] against [reference]; 0.0 = perfect. Empty reference → 0 or 1. */
    fun cer(reference: String, hypothesis: String): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        return levenshtein(reference, hypothesis).toDouble() / reference.length
    }

    /** Aggregate CER over paired lines: total edits / total reference chars. */
    fun corpusCer(references: List<String>, hypotheses: List<String>): Double {
        require(references.size == hypotheses.size) { "line count mismatch" }
        var edits = 0L
        var chars = 0L
        for (i in references.indices) {
            edits += levenshtein(references[i], hypotheses[i])
            chars += references[i].length
        }
        return if (chars == 0L) 0.0 else edits.toDouble() / chars
    }

    /** Classic two-row Levenshtein. */
    fun levenshtein(a: CharSequence, b: CharSequence): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = curr; curr = t
        }
        return prev[b.length]
    }
}
