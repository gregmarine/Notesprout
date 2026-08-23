package com.symmetricalpalmtree.notesproutsn.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * The naming-scheme language (v2), pure Kotlin — no Android, JVM-tested.
 *
 * A scheme is literal text plus tokens, and it answers one question: *what should the next notebook
 * in this folder be called?* The tokens are the ones a paper notebook's spine would carry — a date,
 * a month, a running number:
 *
 * | token | expands to | example |
 * |---|---|---|
 * | `{date}` | `yyyyMMdd` | `20260822` |
 * | `{time}` | `HHmmss` | `143005` |
 * | `{year}` | `yyyy` | `2026` |
 * | `{month}` | `MM` | `08` |
 * | `{day}` | `dd` | `22` |
 * | `{monthname}` | `MMMM` | `August` |
 * | `{weekday}` | `EEEE` | `Saturday` |
 * | `{mon}` | `MMM` | `Aug` |
 * | `{wd}` | `EEE` | `Sat` |
 * | `{n}` / `{n:K}` | the next number, zero-padded to K (1–9) | `07` |
 *
 * Three rules hold the whole thing together:
 *
 *  - **Literal text obeys the core name rule** ([NameRules]' charset) — a scheme can only ever
 *    produce a name the library would have accepted by hand.
 *  - **The counter is a sibling question, not stored state.** `{n}` is 1 + the highest number among
 *    the folder's existing notebook names that match this scheme's [skeleton] — where every date /
 *    time / name position is a *wildcard*, so the run continues across days and months. Nothing is
 *    persisted, so a rename or a delete simply changes the answer.
 *  - **Everything is capped at [MAX_SCHEME_CHARS]**, counted at the *expansion*, not the source:
 *    `{monthname}` is 11 characters of scheme but up to 9 of name.
 *
 * All formatting is pinned to [FORMAT_LOCALE] (English) on purpose: the month and weekday names are
 * part of the skeleton's alphabet, so a device-locale change must never make yesterday's notebooks
 * stop counting.
 *
 * Failures are codes ([Error]) — the dialog maps them to sentences; the engine has no strings.
 */
object SchemeEngine {

    /** The name cap the library enforces by hand, applied to a scheme and to its expansion alike. */
    const val MAX_SCHEME_CHARS = 100

    private const val MAX_COUNTER_WIDTH = 9

    /**
     * The formatting locale — **`Locale.US`, not `Locale.ROOT`**. The numeric patterns are identical
     * either way, but CLDR's *root* locale renders `MMMM` and `EEEE` as the abbreviated forms
     * ("Aug", "Sun"), which would make `{monthname}` and `{mon}` the same token. `Locale.US` is what
     * actually delivers the English names the language promises — and it is fixed, so the scheme a
     * folder holds means the same thing whatever the device's locale is set to.
     */
    private val FORMAT_LOCALE: Locale = Locale.US

    sealed class Part {
        data class Literal(val text: String) : Part()
        data object Date : Part()
        data object Time : Part()
        data object Year : Part()
        data object Month : Part()
        data object Day : Part()
        data object MonthName : Part()
        data object Weekday : Part()
        data object Mon : Part()
        data object Wd : Part()
        data class Counter(val width: Int) : Part()
    }

    /** Why a scheme was rejected; [SchemeException.detail] carries the offending token for [UNKNOWN_TOKEN]. */
    enum class Error { UNKNOWN_TOKEN, UNCLOSED_BRACE, COUNTER_TWICE, ILLEGAL_CHAR, EMPTY, TOO_LONG }

    class SchemeException(val error: Error, val detail: String = "") : Exception("$error $detail")

    private val LITERAL_PATTERN = Regex("^[a-zA-Z0-9_\\-. ]*$")
    private val COUNTER_PATTERN = Regex("^n(?::([1-9]))?$")

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val WEEKDAY_NAMES = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
    )
    private val MONTH_ABBREVIATIONS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val WEEKDAY_ABBREVIATIONS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Parse [scheme] into parts; throws [SchemeException] on the first problem. */
    fun parse(scheme: String): List<Part> {
        if (scheme.length > MAX_SCHEME_CHARS) throw SchemeException(Error.TOO_LONG)
        val parts = ArrayList<Part>()
        val literal = StringBuilder()
        var counterSeen = false
        var i = 0
        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                val text = literal.toString()
                if (!LITERAL_PATTERN.matches(text)) throw SchemeException(Error.ILLEGAL_CHAR)
                parts += Part.Literal(text)
                literal.setLength(0)
            }
        }
        while (i < scheme.length) {
            val c = scheme[i]
            if (c == '{') {
                val close = scheme.indexOf('}', i + 1)
                if (close < 0) throw SchemeException(Error.UNCLOSED_BRACE)
                val token = scheme.substring(i + 1, close)
                flushLiteral()
                parts += when {
                    token == "date" -> Part.Date
                    token == "time" -> Part.Time
                    token == "year" -> Part.Year
                    token == "month" -> Part.Month
                    token == "day" -> Part.Day
                    token == "monthname" -> Part.MonthName
                    token == "weekday" -> Part.Weekday
                    token == "mon" -> Part.Mon
                    token == "wd" -> Part.Wd
                    COUNTER_PATTERN.matches(token) -> {
                        // At most one counter: two running numbers in one name is a scheme whose
                        // "next" has no single answer.
                        if (counterSeen) throw SchemeException(Error.COUNTER_TWICE)
                        counterSeen = true
                        val width = COUNTER_PATTERN.find(token)!!.groupValues[1].toIntOrNull() ?: 1
                        Part.Counter(width.coerceIn(1, MAX_COUNTER_WIDTH))
                    }
                    else -> throw SchemeException(Error.UNKNOWN_TOKEN, "{$token}")
                }
                i = close + 1
            } else if (c == '}') {
                // A stray closer is a mistyped token, not a literal brace — the charset forbids
                // braces anyway, so naming it as an unknown token is the honest message.
                throw SchemeException(Error.UNKNOWN_TOKEN, "}")
            } else {
                literal.append(c)
                i++
            }
        }
        flushLiteral()
        if (parts.isEmpty()) throw SchemeException(Error.EMPTY)
        // The expansion has to fit the name cap too, and every token grows: {date} is 6 characters
        // of scheme and 8 of name. A counter is counted at its width — one that outgrows it (the
        // 100th notebook under {n:2}) is the one thing not knowable from the scheme alone.
        val expandedWorstCase = parts.sumOf { maxWidth(it) }
        if (expandedWorstCase > MAX_SCHEME_CHARS) throw SchemeException(Error.TOO_LONG)
        // A literal-only scheme expands to itself, so it must be a name the library would take.
        if (parts.all { it is Part.Literal }) {
            val joined = parts.joinToString("") { (it as Part.Literal).text }
            if (joined.isBlank() || joined == "." || joined == "..") throw SchemeException(Error.EMPTY)
        }
        return parts
    }

    /** The parse error for [scheme], or null if it is acceptable. */
    fun validate(scheme: String): SchemeException? =
        try { parse(scheme); null } catch (e: SchemeException) { e }

    /**
     * Expand [scheme] at [now] given the folder's existing [siblingNames]. `{n}` = 1 + the highest
     * number among siblings matching the scheme's [skeleton]; numbers wider than the declared width
     * are never truncated. Throws [SchemeException] if the scheme does not parse.
     */
    fun expand(scheme: String, now: Long, siblingNames: List<String>): String {
        val parts = parse(scheme)
        val next = if (parts.any { it is Part.Counter }) nextCounter(parts, siblingNames) else 0
        val at = Date(now)
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is Part.Literal -> sb.append(p.text)
                Part.Date -> sb.append(format("yyyyMMdd", at))
                Part.Time -> sb.append(format("HHmmss", at))
                Part.Year -> sb.append(format("yyyy", at))
                Part.Month -> sb.append(format("MM", at))
                Part.Day -> sb.append(format("dd", at))
                Part.MonthName -> sb.append(format("MMMM", at))
                Part.Weekday -> sb.append(format("EEEE", at))
                Part.Mon -> sb.append(format("MMM", at))
                Part.Wd -> sb.append(format("EEE", at))
                is Part.Counter -> sb.append(next.toString().padStart(p.width, '0'))
            }
        }
        return sb.toString()
    }

    /**
     * Anchored regex of the scheme's skeleton: literals exactly, every date / time position a
     * wildcard of the right shape, and the counter as the single capture group. This is what makes
     * `{n}` continue across a day, a month, or a year boundary.
     */
    fun skeleton(parts: List<Part>): Regex {
        val sb = StringBuilder("^")
        for (p in parts) {
            when (p) {
                is Part.Literal -> sb.append(Pattern.quote(p.text))
                Part.Date -> sb.append("\\d{8}")
                Part.Time -> sb.append("\\d{6}")
                Part.Year -> sb.append("\\d{4}")
                Part.Month -> sb.append("\\d{2}")
                Part.Day -> sb.append("\\d{2}")
                Part.MonthName -> sb.append(alternation(MONTH_NAMES))
                Part.Weekday -> sb.append(alternation(WEEKDAY_NAMES))
                Part.Mon -> sb.append(alternation(MONTH_ABBREVIATIONS))
                Part.Wd -> sb.append(alternation(WEEKDAY_ABBREVIATIONS))
                is Part.Counter -> sb.append("(\\d{1,$MAX_COUNTER_WIDTH})")
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    /** Widest expansion of one part, in characters — what the [MAX_SCHEME_CHARS] cap counts. */
    private fun maxWidth(part: Part): Int = when (part) {
        is Part.Literal -> part.text.length
        Part.Date -> 8
        Part.Time -> 6
        Part.Year -> 4
        Part.Month -> 2
        Part.Day -> 2
        Part.MonthName -> 9   // September
        Part.Weekday -> 9     // Wednesday
        Part.Mon -> 3
        Part.Wd -> 3
        is Part.Counter -> part.width
    }

    /** Non-capturing, so the counter stays capture group 1 whatever else the scheme holds. */
    private fun alternation(words: List<String>): String =
        words.joinToString("|", prefix = "(?:", postfix = ")")

    private fun format(pattern: String, at: Date): String =
        SimpleDateFormat(pattern, FORMAT_LOCALE).format(at)

    private fun nextCounter(parts: List<Part>, siblingNames: List<String>): Int {
        val skeleton = skeleton(parts)
        var highest = 0
        for (name in siblingNames) {
            val m = skeleton.matchEntire(name) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n > highest) highest = n
        }
        return highest + 1
    }
}
