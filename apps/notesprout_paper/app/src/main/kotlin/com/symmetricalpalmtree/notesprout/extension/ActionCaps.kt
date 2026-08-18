package com.symmetricalpalmtree.notesprout.extension

import com.symmetricalpalmtree.notesprout.notebook.ToolbarAction

/**
 * Inward caps for a provider's `describeActions()` (arc 4 / H2 — pure, JVM-tested). Everything a
 * provider sends is untrusted: lists are capped ([ExtensionContract.MAX_ACTIONS] /
 * [ExtensionContract.MAX_SUB_ACTIONS]), ids re-validated (`[A-Za-z0-9_.-]+`, ≤ 32, unique within the
 * provider — first wins), labels trimmed + truncated to [ExtensionContract.MAX_ACTION_LABEL_CHARS]
 * (blank → dropped), `appliesTo` / `requires` masked to their known bits (`appliesTo == 0` →
 * dropped), the icon name resolved through the core catalog (unknown → null → the label is drawn as
 * text), sub-actions capped and **their** sub-actions dropped (one level only), and the long-press
 * hint composed as `"<label> · <provider label>"` truncated to [ExtensionContract.MAX_ACTION_HINT_CHARS].
 */
object ActionCaps {

    fun sanitize(
        actions: List<SelectionAction>,
        providerLabel: String,
        resolveIcon: (String) -> Int?,
    ): List<ToolbarAction> {
        val seen = HashSet<String>()
        val out = ArrayList<ToolbarAction>()
        for (a in actions.take(ExtensionContract.MAX_ACTIONS)) {
            val top = one(a, providerLabel, resolveIcon, seen) ?: continue
            val subs = ArrayList<ToolbarAction>()
            for (s in a.subActions.take(ExtensionContract.MAX_SUB_ACTIONS)) {
                one(s, providerLabel, resolveIcon, seen)?.let { subs += it }   // its own subActions are dropped
            }
            out += top.copy(subActions = subs)
        }
        return out
    }

    private fun one(a: SelectionAction, providerLabel: String, resolveIcon: (String) -> Int?, seen: MutableSet<String>): ToolbarAction? {
        val id = a.id
        if (id.length !in 1..ExtensionContract.MAX_ACTION_ID_CHARS || !SelectionAction.ID_PATTERN.matches(id)) return null
        val label = a.label.trim().take(ExtensionContract.MAX_ACTION_LABEL_CHARS)
        if (label.isBlank()) return null
        val applies = a.appliesTo and ActionApplies.ALL
        if (applies == 0) return null
        if (!seen.add(id)) return null
        val hint = "$label · $providerLabel".take(ExtensionContract.MAX_ACTION_HINT_CHARS)
        return ToolbarAction(
            id = id, label = label, iconRes = a.iconName?.let(resolveIcon), hint = hint,
            appliesTo = applies, requires = a.requires and Requires.ALL,
        )
    }
}
