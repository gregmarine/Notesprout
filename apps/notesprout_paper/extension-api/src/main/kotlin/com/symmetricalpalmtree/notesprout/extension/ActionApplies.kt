package com.symmetricalpalmtree.notesprout.extension

/**
 * Bit flags for [SelectionAction.appliesTo] — which kind of lasso selection shows the action (AIDL
 * carries an `int`). A mixed selection (strokes **and** objects) shows core actions only.
 */
object ActionApplies {
    /** A pure-stroke selection (the action turns ink into an object — `createFromInk`). */
    const val INK: Int = 1
    /** Exactly one selected object of one of the provider's own types (`applyAction`). */
    const val OBJECT: Int = 2
    /** Every known bit; the host masks unknown ones off. */
    const val ALL: Int = INK or OBJECT
}

/**
 * Bit flags for [SelectionAction.requires] — the capabilities an action needs the host to lend the
 * provider. The host explains a missing one *before* binding ("This action needs …") instead of
 * letting the call fail.
 */
object Requires {
    const val RECOGNIZER: Int = 1
    const val MARKDOWN: Int = 2
    const val ALL: Int = RECOGNIZER or MARKDOWN
}
