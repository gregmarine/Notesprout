package com.symmetricalpalmtree.sketchcompanion.session

import com.symmetricalpalmtree.sketchcompanion.crop.CropState
import com.symmetricalpalmtree.sketchcompanion.grid.Grid
import com.symmetricalpalmtree.sketchcompanion.grid.GridWeight
import kotlinx.serialization.Serializable

/** Everything the app restores on relaunch. [photoFile] is a name under `filesDir`. */
@Serializable
data class Session(
    val photoFile: String? = null,
    val crop: CropState = CropState(),
    val gridKind: Int = Grid.DEFAULT_KIND,
    val gridCount: Int = Grid.DEFAULT_COUNT,
    val gridColor: Int = Grid.DEFAULT_COLOR,
    val gridWeight: Int = GridWeight.DEFAULT.ordinal,
    /** The frame's zoom, pan and turn are held still until the user unlocks them — locked by default. */
    val locked: Boolean = true,
) {
    /** A session read back with values this build does not know falls to the defaults. */
    fun sanitized(): Session = copy(
        gridKind = if (Grid.isKind(gridKind)) gridKind else Grid.DEFAULT_KIND,
        gridCount = if (gridCount in Grid.COUNTS) gridCount else Grid.DEFAULT_COUNT,
        gridColor = if (gridColor in Grid.SWATCHES) gridColor else Grid.DEFAULT_COLOR,
        gridWeight = GridWeight.fromOrdinal(gridWeight).ordinal,
    )
}
