package com.symmetricalpalmtree.notesproutsn.ext.sketch

import kotlin.math.max
import kotlin.math.min

/**
 * When the debounced save of a raster page fires — pure Kotlin, so the rule is pinned by a test.
 *
 * The debounce alone was the whole cadence until arc 50's walk 4 (2026-09-23): three seconds after
 * the last change, then the pen-idle gate. Every change restarts the three seconds and the gate
 * waits for the pen to leave hover range, so a hand that keeps sketching — a fast rub, or just
 * steady work with the pen near the glass — never reaches a save until it pauses and lifts away.
 * A hundred seconds of smudging went with a force-stop that day, and a crash would take the same.
 *
 * So a page has a **deadline**: from its first unsaved change it may stay unsaved
 * [MAX_DIRTY_MS] at most. The debounce wait is the shorter of its own three seconds and what is
 * left to the deadline ([debounceWait]); once the deadline has passed, the idle gate is bounded
 * too — [IDLE_LIMIT_MS] of waiting for the pen to go idle, then [LIFT_LIMIT_MS] for the next pen
 * lift (a copy between strokes, never inside one), and then the copy is taken regardless: a
 * page-sized memcpy on the main thread costs tens of milliseconds, and the encode runs off it.
 */
object SketchSaveCadence {

    /** Quiet time before a page is written — Paintsprout's three seconds, and its reason: a
     *  rubbing sweep reports a change dozens of times a second and a page encode is not cheap. */
    const val DEBOUNCE_MS = 3_000L

    /** The longest a page may hold unsaved work while the hand keeps it changing. */
    const val MAX_DIRTY_MS = 15_000L

    /** Past the deadline, how long the save still waits for the pen to go fully idle. */
    const val IDLE_LIMIT_MS = 5_000L

    /** …and then how long it waits for a pen lift before copying regardless. */
    const val LIFT_LIMIT_MS = 5_000L

    /**
     * How long the debounce should wait, given when the page first became dirty and now — the
     * debounce, or what is left to the deadline, whichever comes first; never negative.
     */
    fun debounceWait(dirtySinceMs: Long, nowMs: Long): Long =
        min(DEBOUNCE_MS, max(0L, dirtySinceMs + MAX_DIRTY_MS - nowMs))

    /** Whether the deadline has passed: the idle gate is to be bounded rather than waited on. */
    fun pastDeadline(dirtySinceMs: Long, nowMs: Long): Boolean = nowMs >= dirtySinceMs + MAX_DIRTY_MS
}
