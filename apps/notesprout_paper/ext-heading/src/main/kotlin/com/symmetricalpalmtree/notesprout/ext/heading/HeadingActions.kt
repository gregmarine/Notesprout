package com.symmetricalpalmtree.notesprout.ext.heading

import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.IconNames
import com.symmetricalpalmtree.notesprout.extension.Requires
import com.symmetricalpalmtree.notesprout.extension.SelectionAction

/**
 * The Heading extension's selection-toolbar contribution (arc 4 / H3): one parent **H** (icon
 * `heading`, ink **and** object, needs recognizer + markdown) with six leaves `h1`…`h6` (icons
 * `h-1`…`h-6`, same flags). Leaf ids map to levels ([levelOf]); the active leaf for a selected
 * heading is the one matching its payload's level. Pure — the core draws all of it.
 */
object HeadingActions {

    const val TYPE_ID = "heading"
    const val PARENT_ID = "heading"

    private const val APPLIES = ActionApplies.INK or ActionApplies.OBJECT
    private const val REQUIRES = Requires.RECOGNIZER or Requires.MARKDOWN

    private val LEAF_ICONS = listOf(IconNames.H1, IconNames.H2, IconNames.H3, IconNames.H4, IconNames.H5, IconNames.H6)

    /** `"h3"` for level 3. */
    fun leafId(level: Int): String = "h$level"

    /** The level a leaf id names, or null for anything that is not `h1`…`h6`. */
    fun levelOf(actionId: String?): Int? {
        if (actionId == null || actionId.length != 2 || actionId[0] != 'h') return null
        val level = actionId[1] - '0'
        return level.takeIf { it in HeadingText.MIN_LEVEL..HeadingText.MAX_LEVEL }
    }

    fun describe(): List<SelectionAction> = listOf(
        SelectionAction(
            PARENT_ID, "H", IconNames.HEADING, APPLIES, REQUIRES,
            (HeadingText.MIN_LEVEL..HeadingText.MAX_LEVEL).map { level ->
                SelectionAction(leafId(level), "H$level", LEAF_ICONS[level - 1], APPLIES, REQUIRES, emptyList())
            },
        ),
    )
}
