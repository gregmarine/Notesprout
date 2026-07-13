package com.notesprout.android.recognition.personal

/**
 * Post-recognition correction layer learned from the user's confirmed fixes.
 * Two mechanisms, both derived from (engine original → human label) pairs:
 *
 *  1. **Exact line map** — a line whose normalized text the user has corrected before is
 *     replaced wholesale with the last correction (re-writing the same shopping-list
 *     header every week should stick after one fix).
 *  2. **Word substitutions** — aligned word diffs between original and label; a
 *     substitution is applied only after the user has confirmed the SAME wrong→right
 *     mapping at least [MIN_CONFIRMATIONS] times (guards against one-off context fixes).
 *
 * Pure JVM. Applied as a post-pass in the TrOCR recognizer; text never leaves the device.
 */
class CorrectionMemory private constructor(
    private val exactLines: Map<String, String>,
    private val wordSubs: Map<String, String>,
) {

    val isEmpty: Boolean get() = exactLines.isEmpty() && wordSubs.isEmpty()

    fun apply(line: String): String {
        if (line.isBlank()) return line
        exactLines[normalize(line)]?.let { return it }
        if (wordSubs.isEmpty()) return line
        // Whole-word, case-sensitive replacement preserving separators.
        val out = StringBuilder(line.length + 8)
        var i = 0
        while (i < line.length) {
            if (!line[i].isLetterOrDigit()) { out.append(line[i]); i++; continue }
            var j = i
            while (j < line.length && line[j].isLetterOrDigit()) j++
            val word = line.substring(i, j)
            out.append(wordSubs[word] ?: word)
            i = j
        }
        return out.toString()
    }

    companion object {
        const val MIN_CONFIRMATIONS = 2

        private fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

        private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** Build from (originalEngineText, humanLabel) pairs, oldest first (newest wins the exact map). */
        fun build(pairs: List<Pair<String, String>>): CorrectionMemory {
            val exact = HashMap<String, String>()
            val subCounts = HashMap<Pair<String, String>, Int>()
            for ((orig, label) in pairs) {
                if (orig.isBlank() || label.isBlank()) continue
                exact[normalize(orig)] = label

                // Positional word alignment — only equal-length diffs contribute
                // substitutions (insertions/deletions are ambiguous; skip them).
                val ow = words(orig)
                val lw = words(label)
                if (ow.size == lw.size) {
                    for (k in ow.indices) {
                        if (ow[k] != lw[k] && ow[k].any { it.isLetterOrDigit() }) {
                            val key = ow[k].trim { !it.isLetterOrDigit() }
                            val value = lw[k].trim { !it.isLetterOrDigit() }
                            if (key.isNotEmpty() && value.isNotEmpty() && key != value) {
                                subCounts[key to value] = (subCounts[key to value] ?: 0) + 1
                            }
                        }
                    }
                }
            }
            val subs = HashMap<String, String>()
            for ((pair, count) in subCounts) {
                if (count >= MIN_CONFIRMATIONS) subs[pair.first] = pair.second
            }
            return CorrectionMemory(exact, subs)
        }
    }
}
