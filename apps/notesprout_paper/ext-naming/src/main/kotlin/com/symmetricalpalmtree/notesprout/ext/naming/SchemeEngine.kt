package com.symmetricalpalmtree.notesprout.ext.naming

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * The scheme language (v1), pure Kotlin — JVM-tested. A scheme is literal text plus tokens:
 * `{date}` (`yyyyMMdd`), `{time}` (`HHmmss`), `{n}` / `{n:K}` (next number in the folder,
 * zero-padded to K digits; at most once). Literal text must satisfy the core's name rule
 * (`[a-zA-Z0-9_\-. ]`); the whole scheme — and its expansion, counted at the counter's width — is
 * capped at [MAX_SCHEME_CHARS] (= the host's `MAX_NAME_CHARS`).
 *
 * Errors are codes ([Error]) — the service maps them to user-facing strings; the engine has no
 * Android dependency.
 */
object SchemeEngine {

    const val MAX_SCHEME_CHARS = 100
    private const val MAX_COUNTER_WIDTH = 9

    sealed class Part {
        data class Literal(val text: String) : Part()
        data object Date : Part()
        data object Time : Part()
        data class Counter(val width: Int) : Part()
    }

    /** Why a scheme was rejected; [detail] carries the offending token for [UNKNOWN_TOKEN]. */
    enum class Error { UNKNOWN_TOKEN, UNCLOSED_BRACE, COUNTER_TWICE, ILLEGAL_CHAR, EMPTY, TOO_LONG }

    class SchemeException(val error: Error, val detail: String = "") : Exception("$error $detail")

    private val LITERAL_PATTERN = Regex("^[a-zA-Z0-9_\\-. ]*$")
    private val COUNTER_PATTERN = Regex("^n(?::([1-9]))?$")

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
                    COUNTER_PATTERN.matches(token) -> {
                        if (counterSeen) throw SchemeException(Error.COUNTER_TWICE)
                        counterSeen = true
                        val width = COUNTER_PATTERN.find(token)!!.groupValues[1].toIntOrNull() ?: 1
                        Part.Counter(width.coerceIn(1, MAX_COUNTER_WIDTH))
                    }
                    else -> throw SchemeException(Error.UNKNOWN_TOKEN, "{$token}")
                }
                i = close + 1
            } else if (c == '}') {
                throw SchemeException(Error.UNKNOWN_TOKEN, "}")
            } else {
                literal.append(c)
                i++
            }
        }
        flushLiteral()
        if (parts.isEmpty()) throw SchemeException(Error.EMPTY)
        // The expanded name must also fit the host's name cap (same 100): {date} grows 6 → 8 chars.
        // A counter is counted at its width — one that outgrows it is the one thing not knowable here.
        val minExpanded = parts.sumOf {
            when (it) {
                is Part.Literal -> it.text.length
                Part.Date -> 8
                Part.Time -> 6
                is Part.Counter -> it.width
            }
        }
        if (minExpanded > MAX_SCHEME_CHARS) throw SchemeException(Error.TOO_LONG)
        // A literal-only scheme expands to itself: it must not be "." or "..".
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
     * number among siblings that match the scheme's skeleton — date/time positions match as
     * wildcards (any 8 / 6 digits), so the counter runs across days. Numbers wider than the width
     * are not truncated. Throws [SchemeException] if the scheme does not parse.
     */
    fun expand(scheme: String, now: Long, siblingNames: List<String>): String {
        val parts = parse(scheme)
        val next = if (parts.any { it is Part.Counter }) nextCounter(parts, siblingNames) else 0
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is Part.Literal -> sb.append(p.text)
                Part.Date -> sb.append(SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(now)))
                Part.Time -> sb.append(SimpleDateFormat("HHmmss", Locale.ROOT).format(Date(now)))
                is Part.Counter -> sb.append(next.toString().padStart(p.width, '0'))
            }
        }
        return sb.toString()
    }

    /** Anchored regex of the scheme's skeleton; the counter position is the single capture group. */
    fun skeleton(parts: List<Part>): Regex {
        val sb = StringBuilder("^")
        for (p in parts) {
            when (p) {
                is Part.Literal -> sb.append(Pattern.quote(p.text))
                Part.Date -> sb.append("\\d{8}")
                Part.Time -> sb.append("\\d{6}")
                is Part.Counter -> sb.append("(\\d{1,9})")
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }

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
