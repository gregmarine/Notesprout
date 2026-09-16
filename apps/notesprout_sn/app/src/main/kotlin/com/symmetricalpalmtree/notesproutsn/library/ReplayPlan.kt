package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry

/**
 * What a cold launch does with the surface stack it found (arc 32 "Resume") — a pure function of
 * the stack's shape, so the library's replay is one `when` over an answer the JVM tests own.
 *
 * Only two shapes exist in SN: a notebook at the bottom with the chain above it handed down as
 * `EXTRA_RESUME_ABOVE`, or an extension screen the library itself opened. The Bible reader (arc 37
 * / B0) is a one-screen chain in either place — it has no door to another extension, so `BIBLE`
 * is only ever the last surface, and anything after it is truncated by [legalAbove]. The validity gates
 * (alive row · type NOTEBOOK · `.soil` on disk · a trusted service) are the caller's — they are
 * IO, and a failed one drops the entry by the rules in `RESUME_PLAN.md`.
 *
 * **RS2 acts on both arms**: the library hands [Notebook.above] down to `NotebookActivity` as
 * `EXTRA_RESUME_ABOVE` and reopens a [LibraryLevel] screen itself. Above-lists are normalized by
 * [legalAbove] on both roads — the stack is untrusted input on a cold launch, and only the shapes
 * SN can actually put back are ever acted on.
 */
sealed class ReplayPlan {

    /** A notebook was at the bottom: reopen it, with the surfaces that were above it. */
    data class Notebook(val id: String, val viaLink: Boolean, val above: List<Surface>) : ReplayPlan()

    /** An extension screen was open over the library itself, with nothing beneath it. */
    data class LibraryLevel(val top: Surface, val calendarBeneath: Boolean) : ReplayPlan()

    /** An empty stack, or one whose bottom cannot be stood on. */
    object Nothing : ReplayPlan()

    companion object {
        fun of(stack: List<SurfaceEntry>): ReplayPlan {
            val bottom = stack.firstOrNull() ?: return Nothing
            if (bottom.surface == Surface.NOTEBOOK) {
                val id = bottom.notebookId ?: return Nothing
                // Anything above the notebook is an extension screen; a second NOTEBOOK entry above
                // (a link followed into another notebook mid-switch) cannot be replayed and ends the
                // chain there.
                val above = stack.drop(1).map { it.surface }.takeWhile { it != Surface.NOTEBOOK }
                return Notebook(id, bottom.viaLink, legalAbove(above))
            }
            // Library level: the calendar's pad door is a CALENDAR beneath a SCRATCH_PAD; the top
            // entry is what is reopened, the one beneath is the latch.
            val top = stack.last().surface
            if (top == Surface.NOTEBOOK) return Nothing
            val calendarBeneath = top == Surface.SCRATCH_PAD && stack.size >= 2 &&
                stack[stack.size - 2].surface == Surface.CALENDAR
            return LibraryLevel(top, calendarBeneath)
        }

        /**
         * The chain above a notebook, cut down to what SN can actually reopen (arc 32 / RS2).
         * Exactly one extension screen is showing at a time, so the only legal shapes are nothing,
         * one screen, or `CALENDAR, SCRATCH_PAD` — the calendar's own pad door, the one place two
         * of them are stacked (a `BIBLE` is one screen like any other, and never latches; so is a
         * `SKETCH`, arc 43 / K4 — the sketch face has no door to another extension). Anything else is a stack this build did not write (or wrote across
         * a surface it has since dropped), and is truncated to its **longest legal prefix** rather
         * than refused: what is below the first illegal entry was really open, and the entries
         * above a dropped one can never stand.
         */
        fun legalAbove(above: List<Surface>): List<Surface> {
            val first = above.firstOrNull() ?: return emptyList()
            // A NOTEBOOK above a notebook is not a screen this replay can raise; nothing stands.
            if (first == Surface.NOTEBOOK) return emptyList()
            if (first == Surface.CALENDAR && above.getOrNull(1) == Surface.SCRATCH_PAD) {
                return listOf(Surface.CALENDAR, Surface.SCRATCH_PAD)
            }
            return listOf(first)
        }

        /**
         * `EXTRA_RESUME_ABOVE`'s read (arc 32 / RS2): surface **names** off an Intent extra, back
         * into surfaces. Untrusted the way the stored blob is — a name this build does not know is
         * dropped on its own, never a crash — and normalized by [legalAbove] afterwards, so the
         * caller only ever holds a shape it can act on.
         */
        fun decodeAbove(names: List<String>?): List<Surface> {
            if (names.isNullOrEmpty()) return emptyList()
            return legalAbove(names.mapNotNull { runCatching { Surface.valueOf(it) }.getOrNull() })
        }
    }
}
