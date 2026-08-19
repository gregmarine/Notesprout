package com.symmetricalpalmtree.notesprout.core

import androidx.annotation.DrawableRes
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.extension.IconNames

/**
 * The core's icon catalog (arc 4 / H2): the only way a contributed selection-toolbar button gets a
 * glyph. Each [IconNames] name maps to a Tabler outline drawable the core ships; anything else is
 * unknown and the host draws the action's label as text instead (`ActionCaps`). Extensions never
 * send pixels — the UI rule (`docs/extensions.md` §"Selection-toolbar contributions").
 */
object IconCatalog {
    private val byName: Map<String, Int> = mapOf(
        IconNames.HEADING to R.drawable.ic_heading,
        IconNames.H1 to R.drawable.ic_h_1,
        IconNames.H2 to R.drawable.ic_h_2,
        IconNames.H3 to R.drawable.ic_h_3,
        IconNames.H4 to R.drawable.ic_h_4,
        IconNames.H5 to R.drawable.ic_h_5,
        IconNames.H6 to R.drawable.ic_h_6,
        IconNames.TEXT to R.drawable.ic_cursor_text,
        IconNames.EDIT to R.drawable.ic_edit,
        IconNames.X to R.drawable.ic_x,
        IconNames.CHECK to R.drawable.ic_check,
        IconNames.PLUS to R.drawable.ic_plus,
        IconNames.TRASH to R.drawable.ic_trash,
        IconNames.LIST to R.drawable.ic_list,
    )

    /** The drawable for [name], or null when the name is null or not in the catalog. */
    @DrawableRes
    fun resolve(name: String?): Int? = name?.let { byName[it] }
}
