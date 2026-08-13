package com.notesprout.android.core.proofread

/**
 * One grammar finding: `[start, end)` in the checked text, a short human [message] for the popup,
 * and — when the correction is mechanical — a one-tap [replacement] for the flagged range.
 * [rule] names the rule that fired, stable across passes; session-scoped ignores key on it.
 */
data class GrammarFlag(
    val start: Int,
    val end: Int,
    val rule: String,
    val message: String,
    val replacement: String?,
)

/**
 * The grammar-essentials pass: five hand-written rules, each tuned for precision over recall.
 * There is no viable offline grammar library for this app (see the proofread plan), and a noisy
 * home-grown one would be worse than none — every rule here prefers silence to a wrong flag, and
 * every guard below exists because the unguarded rule fires on legitimate prose.
 *
 * Pure Kotlin like the rest of `core/proofread`; Markdown awareness comes from the same
 * [ProofreadTokenizer] mask the spelling pass uses, so code, URLs, and link targets are never
 * judged. Every rule reads only within one line — a document's paragraphs are single (soft-wrapped)
 * lines after reflow — which keeps the editor's line-bounded incremental re-check exact for
 * grammar just as it is for spelling.
 */
object GrammarRules {

    const val RULE_REPEATED = "repeated-word"
    const val RULE_CAPITALIZE = "sentence-capital"
    const val RULE_LONE_I = "lone-i"
    const val RULE_A_AN = "a-an"
    const val RULE_UNPAIRED = "unpaired"

    /** Doubles that are legitimate English — intensifiers and the classic "had had" family. */
    private val VALID_DOUBLES = setOf(
        "had", "that", "very", "really", "so", "no", "ha", "la", "bye", "yeah", "blah",
    )

    /**
     * Words whose trailing period is an abbreviation, not a sentence end. Includes month/day
     * abbreviations — skipping a real sentence break after "Dec." costs recall, never precision.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "rev", "st", "ave", "blvd", "rd", "vs", "etc", "al",
        "approx", "dept", "est", "min", "max", "misc", "inc", "ltd", "co", "corp", "jr", "sr",
        "no", "vol", "fig", "ca", "cf", "pp", "ex", "sec", "gen", "esp",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "mon", "tue", "tues", "wed", "thu", "thurs", "fri", "sat", "sun",
    )

    /** Silent-h starts that take "an" ("herb" is regional, so it stays unjudged). */
    private val SILENT_H_PREFIXES = listOf("hour", "honest", "honor", "honour", "heir")

    /** Contraction tails that make a lowercase "i'…" worth flagging (i'm, i'll, i've, i'd). */
    private val I_CONTRACTION_TAILS = setOf("m", "ll", "ve", "d")

    /** Markdown/quote characters that may legitimately sit between a terminator and the space. */
    private const val SENTENCE_CLOSERS = "\"'”’)*_]~"

    /**
     * Every rule's findings whose span intersects [region], in document order. The whole [text] is
     * analyzed so mask and neighbour context are exact regardless of how small the region is —
     * mirroring [ProofreadCheck.misspelled].
     */
    fun check(text: String, region: ProofreadCheck.Region): List<GrammarFlag> {
        if (text.isEmpty() || region.end <= region.start) return emptyList()
        val skip = ProofreadTokenizer.skipMask(text)
        val spans = ProofreadTokenizer.wordSpans(text, skip)
        val out = mutableListOf<GrammarFlag>()
        repeatedWords(text, spans, out)
        sentenceCapitals(text, spans, out)
        loneLowercaseI(text, spans, out)
        articleAgreement(text, spans, out)
        unpaired(text, skip, out)
        return out
            .filter { it.end > region.start && it.start < region.end }
            .sortedBy { it.start }
    }

    // ── Repeated word ─────────────────────────────────────────────────────────

    /**
     * "the the" — two identical all-letter words separated by same-line whitespace. Guards:
     * intensifiers and grammatical doubles ([VALID_DOUBLES]); both-capitalized pairs, which are
     * usually proper nouns (Walla Walla, New New York); anything containing a digit.
     */
    private fun repeatedWords(text: String, spans: List<WordSpan>, out: MutableList<GrammarFlag>) {
        for (i in 1 until spans.size) {
            val a = spans[i - 1]
            val b = spans[i]
            if (!sameLineGap(text, a.end, b.start)) continue
            if (!a.word.all { it.isLetter() } || !b.word.all { it.isLetter() }) continue
            if (!a.word.equals(b.word, ignoreCase = true)) continue
            if (a.word.lowercase() in VALID_DOUBLES) continue
            if (a.word[0].isUpperCase() && b.word[0].isUpperCase()) continue
            out += GrammarFlag(a.start, b.end, RULE_REPEATED, "Repeated word", a.word)
        }
    }

    // ── Sentence capitalization ───────────────────────────────────────────────

    /**
     * A lowercase word opening a new sentence: terminator (`.` `!` `?`), optional closing
     * punctuation, at least one same-line space, then the word. Paragraph-opening words are *not*
     * judged — notes are full of deliberate fragments. For a period, the preceding word must look
     * like a real sentence end: present, no digits (decimals, versions), longer than one letter
     * (initials, "e.g."), not a known abbreviation, and not an ellipsis.
     */
    private fun sentenceCapitals(text: String, spans: List<WordSpan>, out: MutableList<GrammarFlag>) {
        for (span in spans) {
            val w = span.word
            val first = w[0]
            if (!first.isLowerCase()) continue
            if (!w.all { it.isLetter() }) continue
            if (w == "i") continue // the lone-i rule owns that span, with a better message

            var i = span.start - 1
            var sawSpace = false
            while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) { sawSpace = true; i-- }
            if (!sawSpace || i < 0) continue
            while (i >= 0 && text[i] in SENTENCE_CLOSERS) i--
            if (i < 0) continue
            val term = text[i]
            if (term != '.' && term != '!' && term != '?') continue
            if (term == '.') {
                if (i > 0 && text[i - 1] == '.') continue // ellipsis trails off, not ends
                var j = i - 1
                while (j >= 0 && text[j].isLetterOrDigit()) j--
                val prev = text.substring(j + 1, i)
                if (prev.isEmpty() || prev.length == 1) continue
                if (prev.any { it.isDigit() }) continue
                if (prev.lowercase() in ABBREVIATIONS) continue
            }
            out += GrammarFlag(
                span.start, span.end, RULE_CAPITALIZE,
                "Sentence should start with a capital",
                first.uppercaseChar() + w.substring(1),
            )
        }
    }

    // ── Lone lowercase i ──────────────────────────────────────────────────────

    /**
     * "i" the pronoun, and its contractions (i'm, i'll, i've, i'd). Guards for the bare letter:
     * an adjacent hyphen (i-beam) or a following period (i.e.) means it is notation, not a word.
     */
    private fun loneLowercaseI(text: String, spans: List<WordSpan>, out: MutableList<GrammarFlag>) {
        for (span in spans) {
            val w = span.word
            val fix: String
            if (w == "i") {
                val before = text.getOrNull(span.start - 1)
                val after = text.getOrNull(span.end)
                if (before == '-' || after == '-' || after == '.') continue
                fix = "I"
            } else if (w.length > 2 && w[0] == 'i' && (w[1] == '\'' || w[1] == '’') &&
                w.substring(2).lowercase() in I_CONTRACTION_TAILS
            ) {
                fix = "I" + w.substring(1)
            } else {
                continue
            }
            out += GrammarFlag(span.start, span.end, RULE_LONE_I, "“i” should be capital", fix)
        }
    }

    // ── a / an ────────────────────────────────────────────────────────────────

    /**
     * Article–vowel agreement, judged by *sound* where the letter is reliable and silent where it
     * is not: every word starting with "u" is skipped in both directions (university vs.
     * uninteresting), "one/once" and "eu…/ew…" starts are consonant sounds, and the silent-h set
     * takes "an". Acronyms, capitalized-after-capitalized, digits, and cross-line pairs are never
     * judged. The flag sits on the article alone, so the fix touches one word.
     */
    private fun articleAgreement(text: String, spans: List<WordSpan>, out: MutableList<GrammarFlag>) {
        for (i in 0 until spans.size - 1) {
            val art = spans[i]
            val artLower = art.word.lowercase()
            if (artLower != "a" && artLower != "an") continue
            if (art.word != "a" && art.word != "an" && art.word != "A" && art.word != "An") continue
            val next = spans[i + 1]
            if (!sameLineGap(text, art.end, next.start)) continue
            val word = next.word
            if (!word.all { it.isLetter() }) continue
            if (word.length > 1 && word.all { it.isUpperCase() }) continue // acronyms are read letter-wise
            val nw = word.lowercase()
            if (nw == artLower) continue // "a a" is the repeated-word rule's finding
            if (nw.startsWith("u")) continue // university/uninteresting — the letter tells nothing

            val startsWithVowelLetter = nw[0] in "aeiou"
            val consonantSound = !startsWithVowelLetter ||
                nw.startsWith("one") || nw.startsWith("once") ||
                nw.startsWith("eu") || nw.startsWith("ew")
            val silentH = SILENT_H_PREFIXES.any { nw.startsWith(it) }

            val wrong: Boolean
            val fix: String
            if (artLower == "a") {
                wrong = (!consonantSound || silentH)
                fix = if (art.word[0].isUpperCase()) "An" else "an"
            } else {
                // "an" before a vowel letter stays unjudged — flagging only consonant starts is
                // what keeps "an umbrella" safe without a pronunciation model.
                wrong = startsWithVowelLetter.not() && !silentH
                fix = if (art.word[0].isUpperCase()) "A" else "a"
            }
            if (!wrong) continue
            out += GrammarFlag(
                art.start, art.end, RULE_A_AN,
                if (artLower == "a") "Should be “an”" else "Should be “a”",
                fix,
            )
        }
    }

    // ── Unpaired quotes and brackets ──────────────────────────────────────────

    /**
     * Per line (multi-line pairs are legitimate in prose; a line is the safe judging unit — and
     * the incremental region's, too): straight double quotes must pair up, smart quotes must
     * balance, and `()[]{}` must nest. Guards: masked characters (code, URLs, link targets) are
     * invisible here; a quote after a digit is inches/seconds; a lone `)` after an emoticon eye
     * or a short enumeration marker ("1)", "a)") is deliberate. No fix — which side is wrong is
     * the writer's call.
     */
    private fun unpaired(text: String, skip: BooleanArray, out: MutableList<GrammarFlag>) {
        var lineStart = 0
        while (lineStart <= text.lastIndex) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = text.length
            checkLinePairs(text, skip, lineStart, lineEnd, out)
            lineStart = lineEnd + 1
        }
    }

    private fun checkLinePairs(
        text: String,
        skip: BooleanArray,
        lineStart: Int,
        lineEnd: Int,
        out: MutableList<GrammarFlag>,
    ) {
        // Straight double quotes: an odd count flags the last one.
        var lastQuote = -1
        var quoteCount = 0
        // Smart quotes: an imbalance flags the last of the surplus kind.
        var lastOpen = -1
        var lastClose = -1
        var opens = 0
        var closes = 0
        val stack = ArrayDeque<Pair<Char, Int>>()

        var i = lineStart
        while (i < lineEnd) {
            if (skip[i]) { i++; continue }
            when (val c = text[i]) {
                '"' -> if (text.getOrNull(i - 1)?.isDigit() != true) { quoteCount++; lastQuote = i }
                '“' -> { opens++; lastOpen = i }
                '”' -> { closes++; lastClose = i }
                '(', '[', '{' -> stack.addLast(c to i)
                ')', ']', '}' -> {
                    val opener = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.isNotEmpty() && stack.last().first == opener) {
                        stack.removeLast()
                    } else if (!(c == ')' && (emoticonBefore(text, i) || enumMarkerBefore(text, lineStart, i)))) {
                        out += GrammarFlag(i, i + 1, RULE_UNPAIRED, "Unmatched bracket", null)
                    }
                }
            }
            i++
        }
        for ((_, idx) in stack) {
            out += GrammarFlag(idx, idx + 1, RULE_UNPAIRED, "Unmatched bracket", null)
        }
        if (quoteCount % 2 == 1) {
            out += GrammarFlag(lastQuote, lastQuote + 1, RULE_UNPAIRED, "Unmatched quotation mark", null)
        }
        if (opens != closes) {
            val idx = if (opens > closes) lastOpen else lastClose
            out += GrammarFlag(idx, idx + 1, RULE_UNPAIRED, "Unmatched quotation mark", null)
        }
    }

    /** `:)` `;)` `:-)` `:^)` — the eye, optionally a nose, then this `)`. */
    private fun emoticonBefore(text: String, idx: Int): Boolean {
        val prev = text.getOrNull(idx - 1) ?: return false
        if (prev == ':' || prev == ';') return true
        if (prev == '-' || prev == '^') {
            val eye = text.getOrNull(idx - 2)
            return eye == ':' || eye == ';'
        }
        return false
    }

    /** "1)", "22)", "a)" — up to three digits or one letter, bounded left by space or line start. */
    private fun enumMarkerBefore(text: String, lineStart: Int, idx: Int): Boolean {
        var j = idx - 1
        while (j >= lineStart && text[j].isLetterOrDigit()) j--
        val marker = text.substring(j + 1, idx)
        if (marker.isEmpty()) return false
        val bounded = j < lineStart || text[j] == ' ' || text[j] == '\t'
        if (!bounded) return false
        return (marker.length <= 3 && marker.all { it.isDigit() }) ||
            (marker.length == 1 && marker[0].isLetter())
    }

    /** True when `[from, to)` is same-line whitespace — the gap two neighbour rules require. */
    private fun sameLineGap(text: String, from: Int, to: Int): Boolean {
        if (to <= from) return false
        for (k in from until to) {
            val c = text[k]
            if (c != ' ' && c != '\t') return false
        }
        return true
    }
}
