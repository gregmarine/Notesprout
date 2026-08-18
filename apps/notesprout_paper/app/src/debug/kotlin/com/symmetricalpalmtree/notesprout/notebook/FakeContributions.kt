package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.core.IconCatalog
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.ActionCaps
import com.symmetricalpalmtree.notesprout.extension.EditSpec
import com.symmetricalpalmtree.notesprout.extension.IconNames
import com.symmetricalpalmtree.notesprout.extension.SelectionAction

/**
 * Debug build only (no-op twin in `src/release`): the arc-4 / H2 stand-in for a real object
 * provider, so the selection toolbar, sub-toolbar and edit dialog can be verified on-device before
 * an extension is behind them (H3/H4 swap this for `ObjectProviderClient`). It "owns" the H1 test
 * object `debug:box` (provider key `debug`, type `box`) and contributes:
 *
 * - **T** — a parent (label `T`, unknown icon → drawn as text) for ink **and** objects, with three
 *   leaves: `t1` (icon `h-1`), `t2` (icon `h-2`), `t3` (unknown icon → text `T3`);
 * - **Ink** — an ink-only leaf (icon `plus`); **Obj** — an object-only leaf (icon `edit`).
 *
 * `t2` reports as the object's *active* sub-action (drawn `state_selected`). Every leaf tap and every
 * saved edit is logged here — this is debug scaffolding, and the "payload" is the fake's own text.
 */
object FakeContributions {

    private const val TAG = "FakeContributions"
    private const val KEY = "debug"

    private val actions: List<SelectionAction> = listOf(
        SelectionAction(
            "t", "T", "not-an-icon", ActionApplies.INK or ActionApplies.OBJECT, 0,
            listOf(
                SelectionAction("t1", "T1", IconNames.H1, ActionApplies.INK or ActionApplies.OBJECT, 0, emptyList()),
                SelectionAction("t2", "T2", IconNames.H2, ActionApplies.INK or ActionApplies.OBJECT, 0, emptyList()),
                SelectionAction("t3", "T3", "nope", ActionApplies.INK or ActionApplies.OBJECT, 0, emptyList()),
            ),
        ),
        SelectionAction("ink", "Ink", IconNames.PLUS, ActionApplies.INK, 0, emptyList()),
        SelectionAction("obj", "Obj", IconNames.EDIT, ActionApplies.OBJECT, 0, emptyList()),
    )

    fun contributions(): List<Contribution> = listOf(
        Contribution(KEY, "Debug", setOf("box"), ActionCaps.sanitize(actions, "Debug", IconCatalog::resolve)),
    )

    fun activeActionIds(obj: PageObject): Set<String> = if (obj.providerIdentity == "$KEY:box") setOf("t2") else emptySet()

    fun editSpec(obj: PageObject): EditSpec? =
        if (obj.providerIdentity == "$KEY:box") EditSpec("Edit test object", obj.payload, "Test text", 200, false) else null

    fun onLeafTapped(providerKey: String?, action: ToolbarAction) {
        Slog.d(TAG) { "leaf tapped: provider=$providerKey id=${action.id}" }
    }

    fun onEditSaved(obj: PageObject, text: String) {
        Slog.d(TAG) { "edit saved for ${obj.id}: \"$text\"" }
    }
}
