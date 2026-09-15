package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle
import kotlinx.coroutines.delay

/**
 * [PenIdle.whenIdle]'s shape for a coroutine (arc 43 / K2): **suspend until the pen is idle**,
 * rather than handing the gate a callback to post.
 *
 * The frame-silence rule is the same rule — never present an app frame while
 * [PaperView.isPenActive] — but a raster replay is a *sequence* of waits: write the store, turn a
 * page if the entry belongs to another one, then swap pixels under a pen that may still be down.
 * Expressed with `whenIdle`'s callback, that sequence becomes a ladder of nested posts with the
 * page-op lock held across it; expressed as one `awaitIdle()` in the middle of a suspending
 * function, it is a line.
 *
 * It lives here, in `:ext-ink`, rather than beside [PenIdle] in `:sn-screen`, for the reason
 * `:sn-screen`'s dependency list says: that module is g-paper plus androidx and **no coroutines**,
 * and pulling `kotlinx-coroutines` into the shared paper-screen library so one extension can write
 * `await` would put a concurrency runtime inside every consumer of it. `:ext-ink` already has it.
 *
 * **Main dispatcher assumed.** [PaperView.isPenActive] is main-thread state, and every caller is a
 * screen's own `lifecycleScope` job. The poll interval is [PaperView.PEN_ACTIVE_TAIL_MS], exactly
 * what [PenIdle.whenIdle] re-posts at: a tail shorter than the engine's own would spin, and a
 * longer one would make a deliberate act wait for nothing.
 *
 * Remember `isPenActive` counts **hover** — never await this for something that must answer a
 * deliberate act (a tap, a door). It is for the frames nobody asked for *now*.
 */
suspend fun PaperView.awaitPenIdle() {
    while (isPenActive) delay(PaperView.PEN_ACTIVE_TAIL_MS)
}

/** [awaitPenIdle] named from the gate it belongs to, so a call site reads as the rule it is
 *  obeying: `PenIdle.awaitIdle(paper)` beside `PenIdle.whenIdle(paper, root) { … }`. */
suspend fun PenIdle.awaitIdle(paper: PaperView) = paper.awaitPenIdle()
