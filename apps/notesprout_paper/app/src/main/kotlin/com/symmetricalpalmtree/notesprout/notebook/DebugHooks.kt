package com.symmetricalpalmtree.notesprout.notebook

/**
 * What the notebook screen lends its debug ⋯ menu for the arc-4 test surface (removed with the menu
 * item in H5): drop a placeholder object at the page centre. A plain callback — the menu never sees
 * the session, the paper or the stores; the release twin of `NotebookDebugMenu` ignores it. (H1's
 * "Delete selection" hook went away in H2 — Delete lives on the selection toolbar now.)
 */
class DebugHooks(
    val insertTestObject: () -> Unit,
)
