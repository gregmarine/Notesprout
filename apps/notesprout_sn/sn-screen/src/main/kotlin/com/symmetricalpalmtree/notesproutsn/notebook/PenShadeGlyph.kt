package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.drawable.LayerDrawable
import android.widget.ImageButton

/**
 * A writing face's **pen button wearing its shade** (arc 49 / P4, the user's decision 4): the
 * ballpen outline over a barrel filled with the armed grey ([ShadeIcon.pen]), swapped onto the
 * button once at construction and re-inked on every change — **frame-silent on a repeat**, because
 * a pick that lands on the tone already showing, and every by-hand sync that passes the same tone
 * again, must not invalidate a button nobody asked to repaint.
 *
 * It is one small class rather than four copies of the same four lines: `NotebookToolbar`,
 * `ScratchToolbar`, `CalendarToolbar` and the sticky editor's bar each own one, which is how the
 * sketch face's `SketchToolbar.reportShades` rule (arc 44 / T3) reaches the writing faces without
 * a second spelling. The mini toolbar and the corner button get the same picture through
 * [CollapsedChrome.PenIcon], where the ARGB is the token — two levels never share one, so the
 * compare is the fill itself.
 *
 * **Greys are ink.** This is the root `CLAUDE.md`'s one standing exception — "the pen button's icon
 * tinted with the armed ink" — in its fill form; the outline stays solid black so a pale pen never
 * goes missing on the panel ([ShadeIcon] says why).
 */
class PenShadeGlyph(private val button: ImageButton, ink: Int) {

    private val icon: LayerDrawable = ShadeIcon.pen(button.context, ink).also { button.setImageDrawable(it) }

    /** The ARGB the button is wearing — the token every repaint compares. */
    var ink: Int = ink
        private set

    /** Wear [ink]. Unchanged is silent. */
    fun report(ink: Int) {
        if (ink == this.ink) return
        this.ink = ink
        ShadeIcon.tint(icon, ink)
    }
}
