package com.symmetricalpalmtree.notesproutsn.data.index

/**
 * What a notebook **opens as** (arc 43 / K3) — the create screen's three-way radio, carried in the
 * index row's [ObjectEntity.flags] and mirrored into `notebook_meta`.
 *
 * Underneath, all three are the same notebook: pages, ink, an optional document per page, and —
 * since arc 43 — an optional sketch per page. The kind changes nothing about what a notebook *can*
 * hold; it says which surface the person meant to be looking at when they made it, and therefore
 * which one an open puts up first. A text document is still a notebook with pages behind the
 * ✓ *Show pages* door, and a sketch notebook's pages still take ink.
 *
 * It is an enum rather than two booleans travelling side by side because the two bits are
 * **exclusive** (decision 1 — the radio is a radio), and a pair of booleans is a shape in which
 * "both" is representable at every call site that passes it. Here it is representable in exactly
 * one place — [of], reading flags this app did not necessarily write — and answered there once.
 *
 * Everything here is pure, and deliberately so: [of] cannot log, so **the caller logs**. The two
 * sites that read a kind off a row that may not be ours (the notebook session's open and the
 * import) ask [conflicting] beside it and say so with a `Log.w`.
 */
enum class NotebookKind {

    /** The default, and this app's centre of gravity: a notebook that opens onto paper. */
    HANDWRITTEN,

    /** Arc 19 / M8 — opens straight into the document editor ([NotebookFlags.TEXT_DOCUMENT]). */
    TEXT,

    /** Arc 43 / K3 — opens into the sketch face ([NotebookFlags.SKETCH]). */
    SKETCH;

    companion object {

        /**
         * The kind [flags] describes. Absent flags are [HANDWRITTEN] — the family's default and the
         * safe way to be wrong: every notebook written before either bit existed is one, and so is
         * every notebook Paper writes.
         *
         * **[TEXT] wins when both bits are set.** Nothing this app writes can set both, so a row
         * that does is foreign or damaged — and the two mistakes are not the same size. A text
         * document opened as a sketchbook shows a blank page where its words are; a sketchbook
         * opened as a text document shows an empty editor with ✓ *Show pages* one tap away. The
         * recoverable answer wins, and [conflicting] is how the caller knows to say so.
         */
        fun of(flags: Int?): NotebookKind {
            val bits = flags ?: 0
            return when {
                (bits and NotebookFlags.TEXT_DOCUMENT) != 0 -> TEXT
                (bits and NotebookFlags.SKETCH) != 0 -> SKETCH
                else -> HANDWRITTEN
            }
        }

        /** Whether [flags] claims to be **both** a text document and a sketch notebook — which no
         *  build of this app can write. [of] answers [TEXT] for it; this is what lets the reading
         *  site log that it had to choose. */
        fun conflicting(flags: Int?): Boolean {
            val bits = flags ?: 0
            return (bits and NotebookFlags.TEXT_DOCUMENT) != 0 && (bits and NotebookFlags.SKETCH) != 0
        }

        /** The flag bits [kind] is written as — the inverse of [of], and the one place a create or
         *  an import turns a chosen kind back into the index's authority. [HANDWRITTEN] is 0: the
         *  question was asked and answered, not left unsaid. */
        fun flagBits(kind: NotebookKind): Int = when (kind) {
            HANDWRITTEN -> 0
            TEXT -> NotebookFlags.TEXT_DOCUMENT
            SKETCH -> NotebookFlags.SKETCH
        }

        /**
         * The kind an arriving file's `notebook_meta` describes — [of]'s rule over the two mirrored
         * booleans instead of the two bits, for the one caller that has the meta and not yet a row
         * (the import, which writes the index row *from* this answer).
         *
         * Untrusted input, like the rest of a manifest: a file claiming both is [TEXT] here for
         * exactly the reason it is there, and the import logs it.
         */
        fun fromMeta(textDocument: Boolean, sketch: Boolean): NotebookKind = when {
            textDocument -> TEXT
            sketch -> SKETCH
            else -> HANDWRITTEN
        }

        /** Whether an arriving file's meta claims both kinds at once — [conflicting] over the two
         *  mirrored booleans, for the same caller as [fromMeta]. */
        fun metaConflicting(textDocument: Boolean, sketch: Boolean): Boolean = textDocument && sketch
    }
}
