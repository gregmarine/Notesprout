package com.notesprout.android.data.toolbar

import com.notesprout.android.notebook.ToolbarButtonRegistry
import kotlinx.serialization.Serializable

/**
 * Global, device-local configuration for the notebook toolbar. One config applies to every
 * notebook. Persisted by [ToolbarPreferencesManager] as a single JSON blob.
 *
 * The default value reproduces today's fixed top toolbar exactly (full button set, top-anchored).
 *
 * Field meanings:
 * - [placement] — which edge the bar is anchored to, or FLOAT. One axis of the layout.
 * - [order] — the full button order as stable [ToolbarButtonRegistry] keys, first-to-last.
 *   There is no user-facing spacer; buttons pack against the leading edge.
 * - [hidden] — keys the user has hidden. The pinned Close button can never be hidden.
 * - [miniSet] — the up-to-5 *extra* keys shown when [miniEnabled] is on; Close and the gear are
 *   always added on top of these, for a 7-button maximum. Excludes Close/gear.
 * - [miniEnabled] — on/off mini toggle; **only takes effect when [placement] is FLOAT**.
 * - [floatX]/[floatY] — last persisted float position; -1 means uninitialised → center on first show.
 * - [floatAxis] — orientation of the floating bar.
 * - [toggleGestureEnabled] — whether the one-finger double-tap hide/show gesture is active.
 * - [collapsed] — whether the toolbar is currently hidden (peek tab showing).
 */
@Serializable
data class ToolbarConfig(
    val placement: ToolbarPlacement = ToolbarPlacement.TOP,
    val order: List<String> = ToolbarButtonRegistry.DEFAULT_ORDER,
    val hidden: Set<String> = emptySet(),
    val miniSet: List<String> = ToolbarButtonRegistry.DEFAULT_MINI,
    val miniEnabled: Boolean = false,
    val floatX: Float = -1f,
    val floatY: Float = -1f,
    val floatAxis: ToolbarAxis = ToolbarAxis.HORIZONTAL,
    val toggleGestureEnabled: Boolean = true,
    val collapsed: Boolean = false,
)
