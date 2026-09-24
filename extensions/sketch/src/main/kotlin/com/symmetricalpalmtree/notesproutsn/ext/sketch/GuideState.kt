package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings

/**
 * The face's model of **one page's guides** (arc 51 "Guides" / J3) — the grid's kind, count and
 * visibility, and the reference image's opacity, visibility and presence. Pure Kotlin: the
 * [GuidesBar] paints from it, [GuideSheet] draws from it, and [toSettings] is the only thing that
 * crosses the seam from it.
 *
 * **Out of range is the default, never an exception** ([fromSettings] — [SketchToolState.of]'s
 * rule). The host stores what it is handed and never learns this face's ladders, so a count or an
 * opacity this build does not offer (a later build's ladder, a hand-edited `.soil`) reads as
 * [GuideSheet.DEFAULT_COUNT] / [GuideSheet.DEFAULT_OPACITY], and a grid kind this build does not
 * know reads as lines — each field on its own, so one stale number never throws away the rest.
 *
 * **Hidden is not gone** (the user's decision 6): a hidden grid keeps its kind and count, a hidden
 * image its pixels and opacity, and both are stored per page. **Off is gone** for the grid (the
 * host soft-deletes its row) and **Remove** is gone for the image — but the face keeps the grid's
 * count for the rest of the sitting, so Off then Lines comes back at the count it left.
 */
data class GuideState(
    /** [SketchContract.GRID_OFF] / [SketchContract.GRID_LINES] / [SketchContract.GRID_DOTS]. */
    val gridKind: Int,
    /** Cells across the page's width — always one of [GuideSheet.COUNTS]. */
    val gridCount: Int,
    val gridVisible: Boolean,
    /** Percent — always one of [GuideSheet.OPACITIES]. */
    val imageOpacity: Int,
    val imageVisible: Boolean,
    /** Whether the page carries a reference image row at all. */
    val hasImage: Boolean,
) {

    val gridOn: Boolean get() = gridKind != SketchContract.GRID_OFF

    /** Whether the sheet draws a grid: one exists and it is not hidden. */
    val showsGrid: Boolean get() = gridOn && gridVisible

    /** Whether the sheet draws the image: one exists and it is not hidden. */
    val showsImage: Boolean get() = hasImage && imageVisible

    /** Whether there is anything to lay under the page at all — the sheet is null otherwise. */
    val showsAnything: Boolean get() = showsGrid || showsImage

    /** A kind from the Off · Lines · Dots latches. Picking Lines or Dots **shows** the grid —
     *  a pick that drew nothing would read as broken. The count is kept through Off. */
    fun withGrid(kind: Int): GuideState = copy(
        gridKind = knownKind(kind),
        gridVisible = if (kind == SketchContract.GRID_OFF) gridVisible else true,
    )

    /** A count from the ladder; off-ladder is the default. A count picked with the grid off turns
     *  it on as lines. */
    fun withCount(count: Int): GuideState = copy(
        gridKind = if (gridOn) gridKind else DEFAULT_KIND,
        gridCount = ladderCount(count),
        gridVisible = true,
    )

    /** An opacity from the ladder; off-ladder is the default. Shows the image. */
    fun withOpacity(percent: Int): GuideState = copy(imageOpacity = ladderOpacity(percent), imageVisible = true)

    fun toggleGridVisible(): GuideState = copy(gridVisible = !gridVisible)

    fun toggleImageVisible(): GuideState = copy(imageVisible = !imageVisible)

    /** A freshly picked image: present and shown, at the opacity already chosen. */
    fun withImage(): GuideState = copy(hasImage = true, imageVisible = true)

    /** Remove: no image. The opacity is kept for the next pick in this sitting. */
    fun withoutImage(): GuideState = copy(hasImage = false, imageVisible = true)

    /** This state as the seam's parcel — every field is already legal ([fromSettings]' and the
     *  `with…` verbs' guarantee), which keeps it inside the parcel's own sanity bounds. */
    fun toSettings(): SketchGuideSettings = SketchGuideSettings(
        gridKind = gridKind,
        gridCount = gridCount,
        gridVisible = gridVisible,
        imageOpacity = imageOpacity,
        imageVisible = imageVisible,
    )

    companion object {

        /** The kind a grid comes back as when a count is picked with none on — the user's default. */
        val DEFAULT_KIND: Int = SketchContract.GRID_LINES

        /** A page with neither row: no grid (count ready at the default), no image. */
        val NONE: GuideState = GuideState(
            gridKind = SketchContract.GRID_OFF,
            gridCount = GuideSheet.DEFAULT_COUNT,
            gridVisible = true,
            imageOpacity = GuideSheet.DEFAULT_OPACITY,
            imageVisible = true,
            hasImage = false,
        )

        /** What the host answered, read as a state — each field falling back on its own default. */
        fun fromSettings(settings: SketchGuideSettings?, hasImage: Boolean): GuideState {
            if (settings == null) return NONE.copy(hasImage = hasImage)
            return GuideState(
                gridKind = knownKind(settings.gridKind),
                gridCount = ladderCount(settings.gridCount),
                gridVisible = settings.gridVisible,
                imageOpacity = ladderOpacity(settings.imageOpacity),
                imageVisible = settings.imageVisible,
                hasImage = hasImage,
            )
        }

        private fun knownKind(kind: Int): Int = when (kind) {
            SketchContract.GRID_OFF, SketchContract.GRID_LINES, SketchContract.GRID_DOTS -> kind
            else -> DEFAULT_KIND
        }

        private fun ladderCount(count: Int): Int =
            if (count in GuideSheet.COUNTS) count else GuideSheet.DEFAULT_COUNT

        private fun ladderOpacity(percent: Int): Int =
            if (percent in GuideSheet.OPACITIES) percent else GuideSheet.DEFAULT_OPACITY
    }
}
