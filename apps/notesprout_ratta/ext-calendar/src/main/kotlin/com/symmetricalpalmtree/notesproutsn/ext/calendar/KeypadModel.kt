package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * What the keypad has been typed so far (arc 24 / Z5b) — a digit string and the number it means.
 * Pure; [KeypadDialog] is the grid that calls it.
 *
 * **A string, not an Int.** "0" and "" are different states on a keypad and the same number, and the
 * difference is what tells the preview whether to show the value the person arrived with or the one
 * they are typing. So the model starts **empty**, [value] is null until a digit lands, and the
 * dialog shows [current] in the meantime.
 *
 * Two rules keep the string honest, and they are here rather than in the listener because both are
 * silently wrong on a device and obvious in a test:
 * - a leading zero is **replaced** by the next digit, never built on — "05" is not a number anybody
 *   typed on purpose;
 * - a digit that would make the string longer than the range's own longest number is **refused**.
 *   Refusing is what a clamp cannot do: 999 typed into a 1..99 field would otherwise land as 99 and
 *   look like the keypad had eaten a digit.
 *
 * [value] still coerces into [range] on the way out, for the one case the width rule cannot catch —
 * a two-digit number below a two-digit floor, or above a cap like 99 in a 1..50 field.
 */
class KeypadModel(private val range: IntRange, val current: Int) {

    /** How many digits the range's largest number takes — the typing cap. */
    private val maxDigits = range.last.toString().length

    /** What has been typed, or "" before anything has. */
    var text: String = ""
        private set

    /** One digit typed. Out-of-range input is a caller bug, not a person's tap. */
    fun digit(d: Int) {
        require(d in 0..9) { "not a digit: $d" }
        text = when {
            text == "0" -> d.toString()
            text.length >= maxDigits -> return
            else -> text + d
        }
    }

    /** The last digit rubbed out. Empty stays empty — there is no state before "nothing typed". */
    fun backspace() {
        if (text.isNotEmpty()) text = text.dropLast(1)
    }

    /** Everything rubbed out at once. */
    fun clear() {
        text = ""
    }

    /** The number typed, inside [range], or null while nothing has been. */
    fun value(): Int? = text.toIntOrNull()?.coerceIn(range)
}
