package com.symmetricalpalmtree.notesproutsn.crypto

/**
 * The one rule set for a **typed** passphrase (arc 26 / U2, decision 13) — a chosen global
 * passphrase at rotation and every notebook passphrase alike: trimmed, at least [MIN_LENGTH]
 * characters, the confirm field equal, and not the passphrase already in force. **No other rule**
 * (no character classes, no dictionary) — the wizard's call. Pure; the dialogs that collect a
 * passphrase call [check] and show the matching string.
 *
 * The passphrase never appears in the verdict — a [Verdict] is a reason, never an echo.
 */
object PassphraseRules {

    const val MIN_LENGTH = 8

    enum class Verdict { OK, TOO_SHORT, MISMATCH, SAME_AS_CURRENT }

    /**
     * [typed] and [confirm] as the person entered them (both are trimmed here — a trailing space
     * from an on-screen keyboard is never part of a key); [current] is the passphrase in force,
     * when the caller knows it, so a "change" to the same value is refused instead of re-keying
     * every file to what it already has.
     */
    fun check(typed: String, confirm: String, current: String? = null): Verdict {
        val p = normalize(typed)
        if (p.length < MIN_LENGTH) return Verdict.TOO_SHORT
        if (p != normalize(confirm)) return Verdict.MISMATCH
        if (current != null && p == normalize(current)) return Verdict.SAME_AS_CURRENT
        return Verdict.OK
    }

    /** What is keyed once [check] said OK — the trimmed form, and only that. */
    fun normalize(typed: String): String = typed.trim()
}
