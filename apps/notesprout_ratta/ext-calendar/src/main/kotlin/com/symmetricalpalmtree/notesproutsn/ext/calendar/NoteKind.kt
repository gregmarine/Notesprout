package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * Which half of the event's note is showing (arc 24 / Z3) — the two kinds behind the editor's one
 * toggle, and the rule that decides which of them an event opens on.
 *
 * **Both contents are always kept.** The toggle chooses what is *shown*, never what is stored: an
 * event may carry ink and typed text at once, and saving writes both whichever latch is down. So
 * this is a view state, and the only decision worth pinning is where a showing starts.
 *
 * **The default is Handwriting**, because this is a handwriting-first app and a blank note is an
 * invitation to write on it. The one case that overrides it is an event whose note is *only* text:
 * opening it on the paper would show an empty page over a note the person did write, which reads
 * as data loss. Ink wins a tie (an event with both) — it is the half that cannot be scrolled to.
 */
enum class NoteKind {
    HANDWRITING,
    TEXT,
    ;

    companion object {
        /** Which kind an event with [hasStrokes] ink and [hasText] typed text opens on. */
        fun defaultFor(hasStrokes: Boolean, hasText: Boolean): NoteKind =
            if (!hasStrokes && hasText) TEXT else HANDWRITING
    }
}
