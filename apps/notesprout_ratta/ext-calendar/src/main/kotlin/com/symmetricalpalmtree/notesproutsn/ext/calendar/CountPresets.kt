package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * The small-number row (arc 24 / Z5b) — six presets and a way out. Pure; [CountLatches] is the seven
 * buttons that read it.
 *
 * **Why six and a "More".** Every count this row answers — every N days, after N times, N days
 * before — is 1 to 6 almost every time it is anything at all, and a stepper made those common
 * answers cost up to six taps each. Seven latches make them cost one, and the seventh admits that
 * the range does not end at six: it opens a keypad ([KeypadModel]) for the rare answer, and then
 * *shows* that answer, so a value of 30 is never hidden behind a word.
 *
 * **Exactly one latch is always down.** A row with nothing down reads as broken on e-ink, so a value
 * below the range's floor is treated as 1 rather than left un-answered — the same call [LatchGroup]
 * makes for the Ends row.
 */
object CountPresets {

    /** The values that get a latch of their own. */
    val PRESETS = 1..6

    /** How many latches the row has — the presets, plus More. */
    val SIZE = PRESETS.count() + 1

    /** Which latch is down for [value], in row order (the six presets, then More) — exactly one
     *  true. Anything above the presets is More; anything below them is the floor. */
    fun pressed(value: Int): List<Boolean> {
        val v = value.coerceAtLeast(PRESETS.first)
        val index = if (v > PRESETS.last) SIZE - 1 else v - PRESETS.first
        return List(SIZE) { it == index }
    }

    /** What the More latch reads: the number itself once the value has left the presets, and
     *  [moreWord] while it has not. A latch that is down must say what it is down *for*. */
    fun moreLabel(value: Int, moreWord: String): String =
        if (value > PRESETS.last) value.toString() else moreWord
}
