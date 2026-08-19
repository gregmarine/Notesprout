package com.symmetricalpalmtree.notesprout.extension

/**
 * The host's icon catalog, by name — the only way a contributed button gets a glyph (the host maps
 * each name to a Tabler outline drawable it ships; an extension never sends pixels). An unknown or
 * absent [SelectionAction.iconName] draws the action's label as text instead.
 */
object IconNames {
    const val HEADING: String = "heading"
    const val H1: String = "h-1"
    const val H2: String = "h-2"
    const val H3: String = "h-3"
    const val H4: String = "h-4"
    const val H5: String = "h-5"
    const val H6: String = "h-6"
    const val TEXT: String = "text"
    const val EDIT: String = "edit"
    const val X: String = "x"
    const val CHECK: String = "check"
    const val PLUS: String = "plus"
    /** Delete's own glyph — listed so an extension may reuse it. */
    const val TRASH: String = "trash"
    /** The core's Contents button glyph (arc 5) — listed so an extension may reuse it. */
    const val LIST: String = "list"
    /** The core's Scratch Pad glyph (arc 6) — the two entry buttons and the core `scratch` action; listed so an extension may reuse it. */
    const val NOTES: String = "notes"

    /** Every name the catalog knows, in declaration order. */
    val ALL: List<String> = listOf(HEADING, H1, H2, H3, H4, H5, H6, TEXT, EDIT, X, CHECK, PLUS, TRASH, LIST, NOTES)
}
