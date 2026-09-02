package com.symmetricalpalmtree.notesproutsn.notebook

import android.view.View
import com.symmetricalpalmtree.gpaper.core.PaperView

/**
 * The two pen-activity gates every paper-hosting screen writes against (arc 23 — one copy, here,
 * rather than the four that had grown across the pad's and the calendar's toolbars and screens).
 * Both are one line each; the reason they are shared is that they are *rules*, and a rule that is
 * restated is a rule that drifts.
 *
 * - [whenIdle] is the **frame-silence** gate: never present an app frame while
 *   [PaperView.isPenActive]. It re-posts itself one [PaperView.PEN_ACTIVE_TAIL_MS] at a time on the
 *   host view, so the frame lands at the first quiet moment rather than under live ink. Remember
 *   `isPenActive` counts **hover** — never gate a show/hide that must answer a deliberate act.
 * - [releaseRenderIfIdle] is [PaperView.releaseRender]'s own contract: an ungated release inside
 *   the pen-active window can cost a live stroke, and while the pen is active nobody is looking at
 *   a pressed state anyway.
 */
object PenIdle {

    /** Run [action] as soon as the pen is idle — now, if it already is. Posts on [host]. */
    fun whenIdle(paper: PaperView, host: View, action: () -> Unit) {
        if (!paper.isPenActive) {
            action()
            return
        }
        host.postDelayed({ whenIdle(paper, host, action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** Release the EPD writing overlay so a tap can show its result — pen-gated. */
    fun releaseRenderIfIdle(paper: PaperView) {
        if (!paper.isPenActive) paper.releaseRender()
    }
}
