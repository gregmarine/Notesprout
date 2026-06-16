package com.notesprout.android.data.toolbar

import kotlinx.serialization.Serializable

/**
 * Where the notebook toolbar is anchored. One axis of the orthogonal placement model —
 * [com.notesprout.android.data.toolbar.ToolbarConfig.miniEnabled] is layered independently
 * on top of this.
 *
 * Persisted in [ToolbarConfig]; enum names must never change once shipped.
 */
@Serializable
enum class ToolbarPlacement { TOP, RIGHT, BOTTOM, LEFT, FLOAT }
