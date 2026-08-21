package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * A selection-toolbar button as the core draws it (arc 4 / H2) — the host-side, already-capped form
 * of a contributed `SelectionAction` (see `ActionCaps`) or the core's own Delete. [iconRes] null =
 * the [label] is drawn as text; [hint] is the long-press text; a [subActions] non-empty entry is a
 * parent that opens the sub-toolbar and is never performed itself. Pure.
 */
data class ToolbarAction(
    val id: String,
    val label: String,
    val iconRes: Int?,
    val hint: String,
    val appliesTo: Int,
    val requires: Int,
    val subActions: List<ToolbarAction> = emptyList(),
) {
    val isParent: Boolean get() = subActions.isNotEmpty()
}

/**
 * One provider's contribution set: [providerKey] = its package name (the left half of an object
 * identity `<pkg>:<typeId>`), its user-facing [providerLabel], the [typeIds] it owns and its
 * capped [actions] in display order.
 */
data class Contribution(
    val providerKey: String,
    val providerLabel: String,
    val typeIds: Set<String>,
    val actions: List<ToolbarAction>,
    /** Answers `describeOutline` (arc 5 — the load probe passed); false for a provider built before the method. */
    val outline: Boolean = false,
)

/** One entry on the toolbar: [providerKey] null = a core action (Delete). */
data class ToolbarItem(val providerKey: String?, val action: ToolbarAction)

/**
 * The pure merge behind the selection toolbar's contents (JVM-tested): **the core actions first**
 * (Delete, then the Scratch Pad's `scratch` while installed — each filtered by its own `appliesTo`),
 * then every provider's actions in registry order, filtered by `appliesTo` — INK when the selection is
 * strokes-only, OBJECT when it is exactly one object of that provider's types; a **mixed** selection
 * (strokes and objects, or several objects) shows core actions carrying every bit only (Delete). Parents are filtered through
 * their leaves: a parent whose leaves all fall away is dropped.
 */
object SelectionActions {

    /** The core Delete's id — the toolbar routes it to `Listener.onDelete`. */
    const val CORE_DELETE_ID = "delete"
    /** The core "Send to Scratch Pad" action's id (arc 6 / S2, `appliesTo = INK`; present only while the extension is installed). */
    const val CORE_SCRATCH_ID = "scratch"
    /** The core "Link" (wrap the selection) id — arc 7 / L1, `appliesTo = ALL` so it shows for every
     *  shape; present only while the Links extension is installed **and** the selection contains no
     *  link (no link-inside-link — the caller gates the list, not the merge). */
    const val CORE_LINK_ID = "link"
    /** The core "Edit" (reopen the picker over a selected link) id — [Shape.OneLink] only, extension-gated. */
    const val CORE_LINK_EDIT_ID = "link_edit"
    /** The core "Unlink" id — [Shape.OneLink] only, **not** extension-gated (unwrap is structural). */
    const val CORE_LINK_UNLINK_ID = "link_unlink"

    sealed interface Shape {
        /** A pure-stroke selection. */
        data object Ink : Shape
        /** Exactly one object, no strokes: its provider identity split into (pkg, typeId). */
        data class OneObject(val providerKey: String, val typeId: String) : Shape
        /** Exactly one link, nothing else (arc 7 / L1) — core actions only (Delete · Edit · Unlink). */
        data class OneLink(val linkId: String) : Shape
        /** Strokes + objects, several objects, an object with an unparseable identity — or any mix
         *  containing a link beside anything else (arc 7). */
        data object Mixed : Shape
    }

    /** Classify a selection from what the screen knows: stroke count, the selected objects'
     *  identities, and the selected link ids (arc 7 / L1). */
    fun shapeOf(strokeCount: Int, objectIdentities: List<String>, linkIds: List<String> = emptyList()): Shape = when {
        linkIds.size == 1 && strokeCount == 0 && objectIdentities.isEmpty() -> Shape.OneLink(linkIds[0])
        linkIds.isNotEmpty() -> Shape.Mixed
        objectIdentities.isEmpty() -> Shape.Ink
        strokeCount == 0 && objectIdentities.size == 1 ->
            ExtensionContract.parseIdentity(objectIdentities[0])?.let { (pkg, type) -> Shape.OneObject(pkg, type) } ?: Shape.Mixed
        else -> Shape.Mixed
    }

    fun merge(core: List<ToolbarAction>, contributions: List<Contribution>, shape: Shape): List<ToolbarItem> {
        val out = ArrayList<ToolbarItem>()
        // Core actions are filtered by `appliesTo` too (arc 6 / S2): Delete carries every bit and shows
        // for every shape; `scratch` (INK) shows for ink only. Mixed — and OneLink (arc 7), whose
        // extra actions the caller adds to `core` for that shape alone — need every bit.
        val coreMask = when (shape) {
            Shape.Ink -> ActionApplies.INK
            is Shape.OneObject -> ActionApplies.OBJECT
            is Shape.OneLink -> ActionApplies.ALL
            Shape.Mixed -> ActionApplies.ALL
        }
        for (a in core) if (a.appliesTo and coreMask == coreMask) out += ToolbarItem(null, a)
        val bit = when (shape) {
            Shape.Ink -> ActionApplies.INK
            is Shape.OneObject -> ActionApplies.OBJECT
            is Shape.OneLink -> return out   // links are core-owned: no provider action applies
            Shape.Mixed -> return out
        }
        for (c in contributions) {
            if (shape is Shape.OneObject && (c.providerKey != shape.providerKey || shape.typeId !in c.typeIds)) continue
            for (a in c.actions) filterApplies(a, bit)?.let { out += ToolbarItem(c.providerKey, it) }
        }
        return out
    }

    private fun filterApplies(a: ToolbarAction, bit: Int): ToolbarAction? {
        if (a.appliesTo and bit == 0) return null
        if (!a.isParent) return a
        val leaves = a.subActions.filter { it.appliesTo and bit != 0 }
        return if (leaves.isEmpty()) null else a.copy(subActions = leaves)
    }
}
