package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat

/**
 * **How the Pencil button reports the armed shade** (arc 44 / T3, the user's answer to the phase's
 * open question): Tabler's pencil outline, solid black as every glyph in the app is, over its own
 * **body filled with the shade that is armed**. Black armed reads as a completely black pencil;
 * any other level as a black outline with that grey inside it.
 *
 * It is one recipe here rather than one in [SketchToolbar] and another in [SketchActivity]'s
 * collapsed chrome, for the reason every shared rule in this family lives in one place: two
 * spellings of the same picture are two things to keep in step, and the family's standing answer to
 * that is the `RattaNotebookView` sibling-copy trap.
 *
 * ## The colour rule
 *
 * Greys **are ink**, and the root `CLAUDE.md` grants exactly one opening for ink in chrome: "the
 * pen button's icon tinted with the armed ink", beside the swatches that choose it. This is that
 * opening in its fill form, and this file plus [PencilBar]'s swatches are the whole of it on this
 * screen — no other control anywhere may take a colour or a grey to say something.
 *
 * ## Why a fill, not a tint of the whole glyph
 *
 * Tinting `ic_pen` itself would take the outline with it, and a `#DDDDDD` pencil on white paper is
 * a button that has gone missing on an e-paper panel. Keeping the outline black and colouring only
 * the body means the button is always fully legible and the shade is a thing *inside* it. The fill
 * path (`ic_pen_fill`) is `ic_pen`'s own body path closed, and the outline's 2 px stroke is centred
 * on it — so the outline covers the fill's edge and no shade can bleed past the glyph.
 *
 * ## The token
 *
 * The ARGB **is** the token both bars compare ([CollapsedChrome.PenIcon]): two levels never share
 * one, a level this build no longer has folds onto the default's ([SketchPalette.shade]) and so
 * repaints nothing, and there is no second number to keep in step with the picture.
 */
object PencilIcon {

    /** The body's layer inside [filled] — under the outline, which is why it is index 0. */
    private const val FILL = 0

    /**
     * A fresh pencil glyph with its body in [ink]. **Fresh per call on purpose**: a `Drawable` in
     * two views fights over its bounds and its callback, and both bars may be showing one at once.
     *
     * The fill is [Drawable.mutate]d before it is tinted, so the tint cannot reach any other
     * instance through the shared constant state — including `ic_pen_fill` as some other button
     * might be using it. The outline is never touched, so it is left shared.
     */
    fun filled(ctx: Context, ink: Int): LayerDrawable {
        val fill = checkNotNull(AppCompatResources.getDrawable(ctx, R.drawable.ic_pen_fill)).mutate()
        val outline = checkNotNull(AppCompatResources.getDrawable(ctx, R.drawable.ic_pen))
        return LayerDrawable(arrayOf(fill, outline)).also { tint(it, ink) }
    }

    /**
     * Re-ink a glyph [filled] made — what a button that already wears one does when the shade
     * changes, rather than inflating a second vector for a tap.
     *
     * The caller is the one that skips the unchanged case: a `Drawable` invalidates itself on a
     * tint change and does nothing on a repeat, but asking it costs the same either way and this
     * screen's rule is that nothing is asked twice for one answer.
     */
    fun tint(icon: LayerDrawable, ink: Int) {
        DrawableCompat.setTint(icon.getDrawable(FILL), ink)
    }
}
