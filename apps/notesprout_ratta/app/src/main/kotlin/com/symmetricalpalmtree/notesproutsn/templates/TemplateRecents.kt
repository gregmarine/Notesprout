package com.symmetricalpalmtree.notesproutsn.templates

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs

/**
 * Recording a use of paper (arc 13 / G5) — the one place that decides what counts as one.
 *
 * It is its own file for the same reason [TemplatePicks] is: **both hosts that turn a pick into
 * pixels need it and neither owns it**. The New Notebook screen records when it bakes page 1, the
 * notebook records when it re-papers a page, and nothing else in the app records at all.
 *
 * What is deliberately *not* a use: creating a folder, importing a template, renaming or moving or
 * duplicating one, changing its fit, exporting it. Those are things done **to** the library; the
 * shelf answers "what paper have I been writing on", and an import that was never applied has not
 * been written on. The browser itself never calls this — a tap that only ticks a card in the New
 * Notebook screen is a choice the user may still back out of.
 */
object TemplateRecents {

    /**
     * Record [pick] as just used. **Blank is not recorded**: it is the absence of paper, it is
     * already the first card at the templates root forever, and a Recents shelf whose top entry is
     * "no paper" is a shelf that has learned nothing.
     *
     * Called only after the pick actually resolved into pixels — a static row that vanished under
     * the user's finger raises its problem dialog and records nothing, because it is not paper the
     * user can go back to.
     */
    fun record(context: Context, pick: TemplatePick) {
        if (pick is TemplatePick.Blank) return
        RecentsPrefs.templates(context).record(pick.cardId)
        Slog.d(TAG) { "template use recorded" }
    }

    private const val TAG = "TemplateRecents"
}
