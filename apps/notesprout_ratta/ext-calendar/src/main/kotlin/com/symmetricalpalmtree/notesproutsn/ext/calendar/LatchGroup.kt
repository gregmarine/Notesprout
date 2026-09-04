package com.symmetricalpalmtree.notesproutsn.ext.calendar

/** A one-armed row of latches: exactly one of [options] is down. Pure — the view side pairs
 *  each option with its button and applies [pressed]; the weekday bar does NOT use this (it is
 *  multi-select). */
class LatchGroup<T>(val options: List<T>) {
    init { require(options.isNotEmpty()); require(options.toSet().size == options.size) }

    /** Which latch is down for [selected], in [options] order — exactly one true; a [selected]
     *  that is not an option leaves the FIRST down, never none (a row with nothing down reads as
     *  broken on e-ink). */
    fun pressed(selected: T): List<Boolean> {
        val index = options.indexOf(selected).let { if (it < 0) 0 else it }
        return options.indices.map { it == index }
    }

    /** The selection after a tap on [tapped]: the tapped option, or [current] unchanged when
     *  [tapped] is not an option. Tapping the down latch keeps it down — there is no "off". */
    fun resolve(current: T, tapped: T): T = if (tapped in options) tapped else current
}
