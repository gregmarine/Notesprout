package com.symmetricalpalmtree.notesprout.notebook

/**
 * What the notebook screen lends its debug ⋯ menu for the arc-4 / H1 test surface (both are removed
 * with the menu items in H5): drop a placeholder object at the page centre, delete the current
 * selection. Plain callbacks — the menu never sees the session, the paper or the stores; the
 * release twin of `NotebookDebugMenu` ignores them.
 */
class DebugHooks(
    val insertTestObject: () -> Unit,
    val deleteSelection: () -> Unit,
)
