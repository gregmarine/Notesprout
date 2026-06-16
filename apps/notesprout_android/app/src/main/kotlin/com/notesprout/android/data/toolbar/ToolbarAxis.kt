package com.notesprout.android.data.toolbar

import kotlinx.serialization.Serializable

/**
 * Orientation of a floating toolbar ([ToolbarPlacement.FLOAT]). Ignored for the edge-anchored
 * placements, which derive their orientation from the anchored edge.
 *
 * Persisted in [ToolbarConfig]; enum names must never change once shipped.
 */
@Serializable
enum class ToolbarAxis { HORIZONTAL, VERTICAL }
