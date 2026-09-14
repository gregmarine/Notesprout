package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * What the editor hands the host for a Bible lookup (arc 39 "Lookup") — the selection, prepared
 * and nothing more. Pure, JVM-tested; it never sees a view.
 *
 * The reader's parser is tolerant of spacing and punctuation already, so this does not try to be
 * clever: it trims, folds any run of whitespace (a selection dragged across a line break included)
 * to one space, and refuses what is not worth a Binder call — nothing, or more than
 * [DocumentContract.MAX_REFERENCE_CHARS]. A refusal is the same "not a reference" alert the reader's
 * own "no" earns, so the writer sees one thing either way.
 */
internal object LookupText {

    private val whitespace = Regex("\\s+")

    /** The words to send, or null when the selection is not worth sending. */
    fun prepare(selected: CharSequence?): String? {
        if (selected == null) return null
        val folded = whitespace.replace(selected, " ").trim()
        if (folded.isEmpty()) return null
        if (folded.length > DocumentContract.MAX_REFERENCE_CHARS) return null
        return folded
    }
}
